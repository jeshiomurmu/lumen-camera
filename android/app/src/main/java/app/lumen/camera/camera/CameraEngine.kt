package app.lumen.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import app.lumen.camera.core.NativeCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SessionKind { NONE, PHOTO, VIDEO, HIGH_SPEED, EXTENSION }

/** A captured image together with the metadata that produced it. */
class Shot(val image: Image, val result: TotalCaptureResult?)

/**
 * Camera2 engine: device + session lifecycle, request building from [Controls],
 * focus/metering, and still/burst/long-exposure/extension captures.
 */
class CameraEngine(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val camThread = HandlerThread("LumenCamera").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val imgThread = HandlerThread("LumenImages").apply { start() }
    private val imgHandler = Handler(imgThread.looper)
    private val executor = Executor { camHandler.post(it) }

    var caps: CameraCaps? = null
        private set
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var extSession: Any? = null  // CameraExtensionSession on API 31+
    private var previewSurface: Surface? = null
    private var recordSurface: Surface? = null
    var jpegReader: ImageReader? = null
        private set
    var rawReader: ImageReader? = null
    /** Full-size processed frames for burst merging on phones without RAW. */
    var yuvReader: ImageReader? = null
        private set
    /** Flicker-free exposure: 0 = auto, 50 / 60 Hz mains. */
    var antibanding: Int = 0
    var kind = SessionKind.NONE
        private set
    private var maxResMode = false
    private var hlg = false

    @Volatile var controls = Controls()
        private set
    @Volatile var lastResult: TotalCaptureResult? = null
        private set
    private val _live = MutableStateFlow(LiveInfo())
    val live: StateFlow<LiveInfo> = _live
    private var lastLiveUpdate = 0L
    private var focusRegions: Array<MeteringRectangle>? = null

    val isMaxResMode get() = maxResMode

    // ---- lifecycle ------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    suspend fun open(c: CameraCaps) {
        closeCamera()
        caps = c
        device = suspendCancellableCoroutine { cont ->
            manager.openCamera(c.id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { if (cont.isActive) cont.resume(d) }
                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera error $error"))
                }
            })
        }
    }

    fun closeSession() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        if (Build.VERSION.SDK_INT >= 31) {
            try { (extSession as? CameraExtensionSession)?.close() } catch (_: Exception) {}
        }
        extSession = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        yuvReader?.close(); yuvReader = null
        previewSurface?.release(); previewSurface = null
        recordSurface = null
        kind = SessionKind.NONE
    }

    val isOpen: Boolean get() = device != null

    fun closeCamera() {
        closeSession()
        try { device?.close() } catch (_: Exception) {}
        device = null
        caps = null   // forces a reopen on resume (Activity recreated / app returned to)
    }

    fun release() {
        closeCamera()
        camThread.quitSafely()
        imgThread.quitSafely()
    }

    // ---- sessions ---------------------------------------------------------------------
    private suspend fun createSession(type: Int, outputs: List<OutputConfiguration>): CameraCaptureSession {
        val dev = device ?: error("camera not open")
        return suspendCancellableCoroutine { cont ->
            val cfg = SessionConfiguration(type, outputs, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { if (cont.isActive) cont.resume(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("session configuration failed"))
                }
            })
            dev.createCaptureSession(cfg)
        }
    }

    /**
     * Photo session: preview + JPEG (ISP pipeline) + RAW (Lumen pipeline).
     * In ultra-high-resolution mode the still streams use the sensor's full
     * resolution (SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, Android 12+).
     */
    suspend fun startPhotoSession(
        texture: SurfaceTexture, preview: Size, jpeg: Size?, raw: Size?, rawFormat: Int, maxRes: Boolean,
        yuvCandidates: List<Size> = emptyList(),
    ) {
        closeSession()
        maxResMode = maxRes && Build.VERSION.SDK_INT >= 31
        texture.setDefaultBufferSize(preview.width, preview.height)
        val ps = Surface(texture).also { previewSurface = it }
        val outs = mutableListOf(OutputConfiguration(ps))
        if (jpeg != null) {
            jpegReader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
            outs += OutputConfiguration(jpegReader!!.surface).also { if (maxResMode) markMaxRes(it) }
        }
        if (raw != null) {
            rawReader = ImageReader.newInstance(raw.width, raw.height, rawFormat, 4)
            outs += OutputConfiguration(rawReader!!.surface).also { if (maxResMode) markMaxRes(it) }
        }
        if (raw == null && yuvCandidates.isNotEmpty()) {
            // No RAW: try a processed YUV stream for burst merging, largest size the HAL accepts.
            val base = outs.toList()
            for (sz in yuvCandidates) {
                val rd = ImageReader.newInstance(sz.width, sz.height, ImageFormat.YUV_420_888, 3)
                val ok = try {
                    session = createSession(SessionConfiguration.SESSION_REGULAR, base + OutputConfiguration(rd.surface))
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "YUV ${sz} stream refused: $e")
                    rd.close()
                    false
                }
                if (ok) {
                    yuvReader = rd
                    kind = SessionKind.PHOTO
                    applyControls(controls)
                    return
                }
            }
        }
        session = try {
            createSession(SessionConfiguration.SESSION_REGULAR, outs)
        } catch (e: Exception) {
            // Some HALs refuse 3 streams at full size: fall back to preview + JPEG only.
            Log.w(TAG, "photo session failed with RAW, retrying without: $e")
            rawReader?.close(); rawReader = null
            createSession(SessionConfiguration.SESSION_REGULAR, outs.take(if (jpeg != null) 2 else 1))
        }
        kind = SessionKind.PHOTO
        applyControls(controls)
    }

    private fun markMaxRes(o: OutputConfiguration) {
        if (Build.VERSION.SDK_INT >= 31) o.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
    }

    suspend fun startVideoSession(texture: SurfaceTexture, preview: Size, encoder: Surface, mode: VideoMode, tenBitHlg: Boolean) {
        closeSession()
        hlg = tenBitHlg && Build.VERSION.SDK_INT >= 33 && caps?.supportsHlg == true
        val pSize = if (mode.highSpeed) mode.size else preview
        texture.setDefaultBufferSize(pSize.width, pSize.height)
        val ps = Surface(texture).also { previewSurface = it }
        recordSurface = encoder
        val outs = listOf(OutputConfiguration(ps), OutputConfiguration(encoder))
        if (hlg && Build.VERSION.SDK_INT >= 33) outs.forEach { it.dynamicRangeProfile = DynamicRangeProfiles.HLG10 }
        session = createSession(
            if (mode.highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR, outs,
        )
        kind = if (mode.highSpeed) SessionKind.HIGH_SPEED else SessionKind.VIDEO
        controls = controls.copy(fps = mode.fps)
        applyControls(controls)
    }

    /** Device-maker processing (Night/HDR/Bokeh/Auto/vendor) via Camera2 Extensions (Android 12+). */
    suspend fun startExtensionSession(texture: SurfaceTexture, extension: Int, aspect: Float): Size? {
        if (Build.VERSION.SDK_INT < 31) return null
        closeSession()
        val c = caps ?: return null
        val dev = device ?: return null
        val ec = manager.getCameraExtensionCharacteristics(c.id)
        val pSizes = ec.getExtensionSupportedSizes(extension, SurfaceTexture::class.java)
        val jSizes = ec.getExtensionSupportedSizes(extension, ImageFormat.JPEG)
        if (pSizes.isEmpty() || jSizes.isEmpty()) return null
        val js = jSizes.filter { kotlin.math.abs(it.width.toFloat() / it.height - aspect) < 0.05f }
            .maxByOrNull { it.width.toLong() * it.height } ?: jSizes.maxBy { it.width.toLong() * it.height }
        val jsAspect = js.width.toFloat() / js.height
        val pv = pSizes.filter { kotlin.math.abs(it.width.toFloat() / it.height - jsAspect) < 0.05f && it.width <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height } ?: pSizes.first()
        texture.setDefaultBufferSize(pv.width, pv.height)
        val ps = Surface(texture).also { previewSurface = it }
        jpegReader = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2)
        val outs = listOf(OutputConfiguration(ps), OutputConfiguration(jpegReader!!.surface))
        val s: CameraExtensionSession = suspendCancellableCoroutine { cont ->
            val cfg = ExtensionSessionConfiguration(extension, outs, executor, object : CameraExtensionSession.StateCallback() {
                override fun onConfigured(session: CameraExtensionSession) { if (cont.isActive) cont.resume(session) }
                override fun onConfigureFailed(session: CameraExtensionSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("extension session failed"))
                }
            })
            dev.createExtensionSession(cfg)
        }
        extSession = s
        kind = SessionKind.EXTENSION
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(ps) }
        s.setRepeatingRequest(req.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
        return pv
    }

    // ---- requests -----------------------------------------------------------------------
    fun applyControls(c: Controls) {
        controls = c
        val dev = device ?: return
        val ps = previewSurface ?: return
        try {
            when (kind) {
                SessionKind.PHOTO -> {
                    val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    b.addTarget(ps)
                    fill(b, c, forPreview = true, video = false)
                    session?.setRepeatingRequest(b.build(), repeatingCallback, camHandler)
                }
                SessionKind.VIDEO -> {
                    val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    b.addTarget(ps)
                    recordSurface?.let { b.addTarget(it) }
                    fill(b, c, forPreview = false, video = true)
                    session?.setRepeatingRequest(b.build(), repeatingCallback, camHandler)
                }
                SessionKind.HIGH_SPEED -> {
                    val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    b.addTarget(ps)
                    recordSurface?.let { b.addTarget(it) }
                    val f = c.fps ?: 120
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(f, f))
                    applyZoom(b, c.zoomRatio)
                    val hs = session as? CameraConstrainedHighSpeedCaptureSession
                    hs?.setRepeatingBurst(hs.createHighSpeedRequestList(b.build()), repeatingCallback, camHandler)
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyControls: $e")
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder, ratio: Float) {
        val c = caps ?: return
        val zr = c.zoomRange
        if (Build.VERSION.SDK_INT >= 30 && zr != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio.coerceIn(zr.lower, zr.upper))
        } else {
            val z = ratio.coerceIn(1f, c.maxDigitalZoom)
            val a = c.activeArray
            val w = (a.width() / z).roundToInt()
            val h = (a.height() / z).roundToInt()
            val l = a.left + (a.width() - w) / 2
            val t = a.top + (a.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
        }
    }

    /** Fills a request from [Controls], respecting what the camera supports. */
    private fun fill(b: CaptureRequest.Builder, c: Controls, forPreview: Boolean, video: Boolean) {
        b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, when (antibanding) {
            50 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            60 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
            else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        })
        val k = caps ?: return
        val last = lastResult
        applyZoom(b, c.zoomRatio)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // ---- exposure
        val isoR = k.isoRange
        val expR = k.exposureRange
        val manualOk = k.manualSensor && isoR != null && expR != null
        if (c.iso != null && c.shutterNs != null && manualOk) {
            var iso = c.iso.coerceIn(isoR!!.lower, isoR.upper)
            var shutter = c.shutterNs.coerceIn(expR!!.lower, expR.upper)
            if (forPreview && shutter > MAX_PREVIEW_SHUTTER) {
                // keep the preview responsive: same brightness, shorter exposure
                iso = (iso * shutter / MAX_PREVIEW_SHUTTER).toInt().coerceIn(isoR.lower, isoR.upper)
                shutter = MAX_PREVIEW_SHUTTER
            }
            if (video) shutter = min(shutter, 1_000_000_000L / (c.fps ?: 30))
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (video) 1_000_000_000L / (c.fps ?: 30) else shutter)
        } else if (c.iso != null && Build.VERSION.SDK_INT >= 36 &&
            k.aePriorityModes.contains(CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY)) {
            // Android 16 hybrid AE: fixed ISO, auto shutter
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, c.iso)
        } else if (c.shutterNs != null && Build.VERSION.SDK_INT >= 36 &&
            k.aePriorityModes.contains(CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY)) {
            // Android 16 hybrid AE: fixed shutter, auto ISO
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, c.shutterNs)
        } else if (c.manualExposure && manualOk && last != null) {
            // Emulate ISO/shutter priority: keep the auto exposure value, move it to the other axis.
            val aeIso = last.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val aeT = last.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
            val ev = aeIso.toDouble() * aeT
            val iso = c.iso ?: (ev / c.shutterNs!!).toInt()
            val t = c.shutterNs ?: (ev / c.iso!!).toLong()
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoR!!.lower, isoR.upper))
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, t.coerceIn(expR!!.lower, min(expR.upper, if (forPreview) MAX_PREVIEW_SHUTTER else expR.upper)))
        } else {
            val aeMode = when (c.flash) {
                FlashMode.AUTO -> if (k.flash) CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH else CameraMetadata.CONTROL_AE_MODE_ON
                FlashMode.ON -> if (k.flash) CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH else CameraMetadata.CONTROL_AE_MODE_ON
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.evSteps.coerceIn(k.aeCompRange.lower, k.aeCompRange.upper))
            b.set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
            focusRegions?.let { if (k.maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, it) }
        }
        if (video && !c.manualExposure) {
            val f = c.fps ?: 30
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(f, f))
        }
        if (c.flash == FlashMode.TORCH && k.flash) b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)

        // ---- focus
        if (c.focusDiopters != null && k.minFocusDistance > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDiopters.coerceIn(0f, k.minFocusDistance))
        } else if (focusRegions != null && k.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            if (k.maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, focusRegions)
        } else {
            val cont = if (video) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            if (k.afModes.contains(cont)) b.set(CaptureRequest.CONTROL_AF_MODE, cont)
        }

        // ---- white balance
        if (c.wbKelvin != null) {
            val cctR = k.cctRange
            if (Build.VERSION.SDK_INT >= 36 && cctR != null) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_CCT)
                b.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, c.wbKelvin.coerceIn(cctR.lower, cctR.upper))
                b.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, c.wbTint)
            } else if (k.manualPost && k.colorMatrix1 != null) {
                val gains = NativeCore.wbGainsForCct(ColorMath.staticColor(k), c.wbKelvin.toFloat(), c.wbTint.toFloat())
                val ccm = last?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                if (gains != null && ccm != null) {
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                    b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(gains[0], 1f, 1f, gains[2]))
                    b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ccm)
                } else {
                    b.set(CaptureRequest.CONTROL_AWB_MODE, presetFor(c.wbKelvin))
                }
            } else {
                b.set(CaptureRequest.CONTROL_AWB_MODE, presetFor(c.wbKelvin))
            }
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, c.awbLock)
        }

        // ---- stabilization, NR, sharpening, tone curve
        if (k.oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (c.ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }
        if (video) {
            val mode = when {
                !c.videoStabilization -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                Build.VERSION.SDK_INT >= 33 && k.videoStabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) ->
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                k.videoStabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            }
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mode)
        }
        c.noiseReductionMode?.let { if (k.nrModes.contains(it)) b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
        c.edgeMode?.let { if (k.edgeModes.contains(it)) b.set(CaptureRequest.EDGE_MODE, it) }
        if (c.logCurve && !hlg && k.tonemapModes.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) && k.maxCurvePoints >= 16) {
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, logCurve(min(64, k.maxCurvePoints)))
        }
        if (maxResMode && Build.VERSION.SDK_INT >= 31) {
            b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
        }
    }

    private fun logCurve(points: Int): TonemapCurve {
        val arr = FloatArray(points * 2)
        for (i in 0 until points) {
            val x = i / (points - 1f)
            arr[2 * i] = x
            arr[2 * i + 1] = NativeCore.logEncode(x)
        }
        return TonemapCurve(arr, arr.copyOf(), arr.copyOf())
    }

    private fun presetFor(kelvin: Int): Int = when {
        kelvin < 3300 -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        kelvin < 4500 -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        kelvin < 5800 -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        kelvin < 7000 -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        else -> CameraMetadata.CONTROL_AWB_MODE_SHADE
    }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            lastResult = result
            val now = System.currentTimeMillis()
            if (now - lastLiveUpdate < 150) return
            lastLiveUpdate = now
            val k = caps ?: return
            val t = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val ap = result.get(CaptureResult.LENS_APERTURE) ?: k.apertures.first()
            val color = ColorMath.colorFor(k, result, null, 0)
            val cct = if (color[0] > 0f) NativeCore.estimateCct(color) else 0f
            _live.value = LiveInfo(
                iso = iso,
                exposureNs = t,
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                kelvin = cct.roundToInt(),
                aperture = ap,
                focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: k.focalLengths.first(),
                aeConverged = result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_CONVERGED,
                afLocked = result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                ev100 = ColorMath.ev100(t, iso, ap),
                zoomRatio = if (Build.VERSION.SDK_INT >= 30) result.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: controls.zoomRatio else controls.zoomRatio,
            )
        }
    }

    // ---- focus & metering -------------------------------------------------------------
    /**
     * Tap to focus/expose at normalized view coordinates (0..1, portrait UI).
     * Maps through the sensor orientation into active-array coordinates.
     */
    fun focusAt(nx: Float, ny: Float) {
        val k = caps ?: return
        val dev = device ?: return
        val ps = previewSurface ?: return
        if (kind != SessionKind.PHOTO && kind != SessionKind.VIDEO) return
        // view (portrait) -> sensor (landscape) normalized coordinates
        var sx: Float
        var sy: Float
        when (k.sensorOrientation) {
            90 -> { sx = ny; sy = 1f - nx }
            180 -> { sx = 1f - nx; sy = 1f - ny }
            270 -> { sx = 1f - ny; sy = nx }
            else -> { sx = nx; sy = ny }
        }
        if (k.facing == CameraCharacteristics.LENS_FACING_FRONT) sy = 1f - sy
        val a = k.activeArray
        val crop = if (Build.VERSION.SDK_INT >= 30 && k.zoomRange != null) Rect(0, 0, a.width(), a.height())
        else lastResult?.get(CaptureResult.SCALER_CROP_REGION) ?: Rect(0, 0, a.width(), a.height())
        val cx = crop.left + (sx * crop.width()).toInt()
        val cy = crop.top + (sy * crop.height()).toInt()
        val half = max(crop.width(), crop.height()) / 20
        val rect = Rect(
            (cx - half).coerceIn(0, a.width() - 1), (cy - half).coerceIn(0, a.height() - 1),
            (cx + half).coerceIn(1, a.width()), (cy + half).coerceIn(1, a.height()),
        )
        focusRegions = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1))
        // drop manual focus when the user taps
        if (controls.focusDiopters != null) controls = controls.copy(focusDiopters = null)
        try {
            val b = dev.createCaptureRequest(if (kind == SessionKind.VIDEO) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            if (kind == SessionKind.VIDEO) recordSurface?.let { b.addTarget(it) }
            fill(b, controls, forPreview = true, video = kind == SessionKind.VIDEO)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            if (!controls.manualExposure) b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            session?.capture(b.build(), null, camHandler)
            applyControls(controls)
        } catch (e: Exception) {
            Log.w(TAG, "focusAt: $e")
        }
    }

    fun resetFocus() {
        focusRegions = null
        applyControls(controls)
    }

    // ---- captures --------------------------------------------------------------------------
    private fun stillBuilder(target: Surface, includePreview: Boolean, orientation: Int, jpegQuality: Int): CaptureRequest.Builder {
        val dev = device ?: error("camera closed")
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(target)
        if (includePreview && !maxResMode) previewSurface?.let { b.addTarget(it) }
        fill(b, controls, forPreview = false, video = false)
        b.set(CaptureRequest.JPEG_ORIENTATION, orientation)
        b.set(CaptureRequest.JPEG_QUALITY, jpegQuality.coerceIn(1, 100).toByte())
        if (maxResMode && Build.VERSION.SDK_INT >= 31) {
            b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
        }
        if (controls.manualExposure && controls.flash == FlashMode.ON && caps?.flash == true) {
            b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
        }
        return b
    }

    private suspend fun captureOne(reader: ImageReader, builder: CaptureRequest.Builder, timeoutMs: Long): Shot {
        val s = session ?: error("no session")
        val image = CompletableDeferred<Image>()
        val result = CompletableDeferred<TotalCaptureResult>()
        reader.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.let { if (!image.complete(it)) it.close() }
        }, imgHandler)
        s.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, tr: TotalCaptureResult) {
                result.complete(tr)
            }
            override fun onCaptureFailed(cs: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                result.completeExceptionally(IllegalStateException("capture failed (${f.reason})"))
            }
        }, camHandler)
        val img = withTimeout(timeoutMs) { image.await() }
        val res = withTimeoutOrNull(2_000) { result.await() }
        return Shot(img, res)
    }

    /** One JPEG through the device ISP (vendor processing, fastest). */
    suspend fun captureJpeg(orientation: Int, quality: Int): Shot {
        val r = jpegReader ?: error("JPEG stream not configured")
        precaptureIfFlash()
        return captureOne(r, stillBuilder(r.surface, true, orientation, quality), 10_000)
    }

    /** One RAW frame with the current (or the given manual) exposure. */
    suspend fun captureRaw(orientation: Int, exposureNs: Long? = null, iso: Int? = null): Shot {
        val r = rawReader ?: error("RAW stream not configured")
        val b = stillBuilder(r.surface, true, orientation, 95)
        if (exposureNs != null && iso != null) lockExposure(b, exposureNs, iso)
        val timeout = 10_000L + (exposureNs ?: 0L) / 1_000_000L * 2
        return captureOne(r, b, timeout)
    }

    private fun lockExposure(b: CaptureRequest.Builder, exposureNs: Long, iso: Int) {
        val k = caps ?: return
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, k.exposureRange?.let { exposureNs.coerceIn(it.lower, it.upper) } ?: exposureNs)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, k.isoRange?.let { iso.coerceIn(it.lower, it.upper) } ?: iso)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, exposureNs)
        lastResult?.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { fd ->
            if (k.minFocusDistance > 0f) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd)
            }
        }
        b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
    }

    /**
     * Burst of [count] RAW frames at a fixed exposure. Frames are handed to [onFrame]
     * as they arrive (the callback must close the image quickly).
     */
    suspend fun captureRawBurst(count: Int, exposureNs: Long, iso: Int, orientation: Int, onFrame: suspend (Shot) -> Unit) {
        val r = rawReader ?: error("RAW stream not configured")
        val s = session ?: error("no session")
        val images = Channel<Image>(Channel.UNLIMITED)
        val results = ConcurrentHashMap<Long, TotalCaptureResult>()
        r.setOnImageAvailableListener({ rd -> rd.acquireNextImage()?.let { images.trySend(it) } }, imgHandler)
        val reqs = (0 until count).map {
            val b = stillBuilder(r.surface, true, orientation, 95)
            lockExposure(b, exposureNs, iso)
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            b.build()
        }
        s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(cs: CameraCaptureSession, rq: CaptureRequest, tr: TotalCaptureResult) {
                tr.get(CaptureResult.SENSOR_TIMESTAMP)?.let { results[it] = tr }
            }
        }, camHandler)
        val perFrameTimeout = 5_000L + exposureNs / 1_000_000L * 3
        repeat(count) {
            val img = withTimeout(perFrameTimeout) { images.receive() }
            var res: TotalCaptureResult? = null
            for (i in 0 until 40) {
                res = results.remove(img.timestamp)
                if (res != null) break
                delay(10)
            }
            onFrame(Shot(img, res ?: lastResult))
        }
        r.setOnImageAvailableListener(null, null)
        images.close()
    }

    /**
     * Burst of processed YUV frames. With manual-sensor support the exposure is fixed;
     * otherwise auto exposure is locked (shifted by [evSteps]) so every frame matches.
     */
    suspend fun captureYuvBurst(
        count: Int, exposureNs: Long?, iso: Int?, evSteps: Int, onFrame: suspend (Shot) -> Unit,
    ) {
        val r = yuvReader ?: error("YUV stream not configured")
        val s = session ?: error("no session")
        val k = caps ?: error("camera closed")
        val images = Channel<Image>(Channel.UNLIMITED)
        val results = ConcurrentHashMap<Long, TotalCaptureResult>()
        r.setOnImageAvailableListener({ rd -> rd.acquireNextImage()?.let { images.trySend(it) } }, imgHandler)
        val manual = exposureNs != null && iso != null && k.manualSensor
        if (!manual) {
            // settle AE at the requested compensation, then lock it for the burst
            val dev = device ?: error("camera closed")
            val pre = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { pre.addTarget(it) }
            fill(pre, controls.copy(evSteps = evSteps), forPreview = true, video = false)
            s.setRepeatingRequest(pre.build(), repeatingCallback, camHandler)
            delay(if (evSteps != 0) 700 else 250)
            pre.set(CaptureRequest.CONTROL_AE_LOCK, true)
            pre.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            s.setRepeatingRequest(pre.build(), repeatingCallback, camHandler)
            delay(150)
        }
        val reqs = (0 until count).map {
            val b = stillBuilder(r.surface, true, 0, 95)
            if (manual) lockExposure(b, exposureNs!!, iso!!) else {
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps)
                b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
            b.build()
        }
        try {
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(cs: CameraCaptureSession, rq: CaptureRequest, tr: TotalCaptureResult) {
                    tr.get(CaptureResult.SENSOR_TIMESTAMP)?.let { results[it] = tr }
                }
            }, camHandler)
            repeat(count) {
                val img = withTimeout(6_000L + (exposureNs ?: 0L) / 1_000_000L * 3) { images.receive() }
                onFrame(Shot(img, results.remove(img.timestamp) ?: lastResult))
            }
        } finally {
            r.setOnImageAvailableListener(null, null)
            images.close()
            if (!manual) applyControls(controls)   // unlock AE/AWB, restore preview
        }
    }

    /** Capture through an OEM extension session; returns JPEG bytes. */
    suspend fun captureExtension(orientation: Int, quality: Int): ByteArray {
        if (Build.VERSION.SDK_INT < 31) error("extensions need Android 12")
        val s = extSession as? CameraExtensionSession ?: error("no extension session")
        val r = jpegReader ?: error("no JPEG stream")
        val dev = device ?: error("camera closed")
        val image = CompletableDeferred<Image>()
        r.setOnImageAvailableListener({ rd -> rd.acquireNextImage()?.let { if (!image.complete(it)) it.close() } }, imgHandler)
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(r.surface)
        b.set(CaptureRequest.JPEG_ORIENTATION, orientation)
        b.set(CaptureRequest.JPEG_QUALITY, quality.coerceIn(1, 100).toByte())
        s.capture(b.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
            override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                image.completeExceptionally(IllegalStateException("extension capture failed"))
            }
        })
        val img = withTimeout(20_000) { image.await() }
        val buf = img.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        img.close()
        return bytes
    }

    /** Runs the AE precapture sequence when flash may fire. */
    private suspend fun precaptureIfFlash() {
        if (controls.flash != FlashMode.AUTO && controls.flash != FlashMode.ON) return
        if (caps?.flash != true || controls.manualExposure) return
        val dev = device ?: return
        val ps = previewSurface ?: return
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(ps)
        fill(b, controls, forPreview = true, video = false)
        b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        session?.capture(b.build(), null, camHandler)
        for (i in 0 until 30) {
            delay(50)
            val st = lastResult?.get(CaptureResult.CONTROL_AE_STATE)
            if (st == CaptureResult.CONTROL_AE_STATE_CONVERGED || st == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) break
        }
    }

    companion object {
        private const val TAG = "LumenEngine"
        const val MAX_PREVIEW_SHUTTER = 250_000_000L  // 1/4 s
    }
}
