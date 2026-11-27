package app.lumen.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One recordable (size, fps) combination advertised by the camera HAL. */
data class VideoMode(val size: Size, val fps: Int, val highSpeed: Boolean) {
    val label: String
        get() = "${shortName(size)} ${fps}fps" + if (highSpeed) " (HFR)" else ""

    companion object {
        fun shortName(s: Size): String = when {
            kotlin.math.abs(s.width.toFloat() / s.height - 4f / 3f) < 0.02f -> "Open gate ${s.width}x${s.height}"
            s.width >= 7680 -> "8K"
            s.width >= 3840 -> "4K"
            s.width >= 2560 -> "QHD"
            s.width >= 1920 -> "FHD"
            s.width >= 1280 -> "HD"
            else -> "${s.width}x${s.height}"
        }
    }
}

/**
 * Everything the camera HAL advertises for one camera ID — including IDs that
 * are not in getCameraIdList() ("hidden") and physical sub-cameras of logical
 * multi-cameras. Nothing here is guessed: unsupported features are simply absent.
 */
class CameraCaps(
    val id: String,
    val chars: CameraCharacteristics,
    val hidden: Boolean,
    val physicalOf: String?,
    extensionList: List<Int>,
) {
    private inline fun <reified T> get(key: CameraCharacteristics.Key<T>): T? =
        try { chars.get(key) } catch (_: Exception) { null }

    val facing: Int = get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
    val hwLevel: Int = get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
    val capabilities: IntArray = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
    private fun hasCap(c: Int) = capabilities.contains(c)

    val isLogical = hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    val physicalIds: Set<String> = if (isLogical) chars.physicalCameraIds else emptySet()
    val manualSensor = hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    val manualPost = hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
    val rawCapable = hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
    val burstCapable = hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)
    val ultraHighRes = Build.VERSION.SDK_INT >= 31 &&
        hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
    val tenBit = Build.VERSION.SDK_INT >= 33 &&
        hasCap(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)

    val sensorOrientation: Int = get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val focalLengths: FloatArray = get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4f)
    val apertures: FloatArray = get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)
    val physicalSize: SizeF? = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val pixelArray: Size? = get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    val activeArray: Rect = get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
    val isoRange: Range<Int>? = get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val maxAnalogIso: Int? = get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
    val exposureRange: Range<Long>? = get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val maxFrameDuration: Long? = get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
    val minFocusDistance: Float = get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val focusCalibration: Int = get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) ?: 0
    val zoomRange: Range<Float>? = if (Build.VERSION.SDK_INT >= 30) get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
    val maxDigitalZoom: Float = get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    val aeCompRange: Range<Int> = get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    val aeCompStep: Rational = get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 3)
    val oisModes: IntArray = get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
    val videoStabModes: IntArray = get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
    val tonemapModes: IntArray = get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
    val maxCurvePoints: Int = get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
    val awbModes: IntArray = get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
    val afModes: IntArray = get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
    val nrModes: IntArray = get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
    val edgeModes: IntArray = get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
    val flash: Boolean = get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
    val maxAfRegions: Int = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    val maxAeRegions: Int = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
    val fpsRanges: Array<Range<Int>> = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
    val timestampRealtime: Boolean =
        get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    val cfa: Int = get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
    val whiteLevel: Int = get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
    val binningFactor: Size? = if (Build.VERSION.SDK_INT >= 31) get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR) else null

    // Android 16 (API 36): hybrid auto-exposure + correlated color temperature WB
    val aePriorityModes: IntArray =
        if (Build.VERSION.SDK_INT >= 36) get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES) ?: IntArray(0) else IntArray(0)
    val cctRange: Range<Int>? =
        if (Build.VERSION.SDK_INT >= 36) get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE) else null

    val dynamicRanges: Set<Long> =
        if (Build.VERSION.SDK_INT >= 33) {
            get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles ?: emptySet()
        } else emptySet()
    val supportsHlg: Boolean = Build.VERSION.SDK_INT >= 33 && dynamicRanges.contains(DynamicRangeProfiles.HLG10)

    val extensions: List<Int> = extensionList

    private val map: StreamConfigurationMap? = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    private val mapMaxRes: StreamConfigurationMap? =
        if (Build.VERSION.SDK_INT >= 31) get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) else null

    private fun sizes(m: StreamConfigurationMap?, fmt: Int): List<Size> =
        try { m?.getOutputSizes(fmt)?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }

    private fun Size.area() = width.toLong() * height

    /** JPEG sizes incl. "high resolution" sizes some OEMs only list there (e.g. 50/200 MP). */
    val jpegSizes: List<Size> = (sizes(map, ImageFormat.JPEG) +
        (try { map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList() } catch (_: Exception) { null } ?: emptyList()))
        .distinct().sortedByDescending { it.area() }
    val rawSizes: List<Size> = sizes(map, ImageFormat.RAW_SENSOR).sortedByDescending { it.area() }
    val raw10Sizes: List<Size> = sizes(map, ImageFormat.RAW10).sortedByDescending { it.area() }
    val maxResJpegSizes: List<Size> = sizes(mapMaxRes, ImageFormat.JPEG).sortedByDescending { it.area() }
    val maxResRawSizes: List<Size> = sizes(mapMaxRes, ImageFormat.RAW_SENSOR).sortedByDescending { it.area() }
    val previewSizes: List<Size> =
        (try { map?.getOutputSizes(SurfaceTexture::class.java)?.toList() } catch (_: Exception) { null } ?: emptyList())
            .sortedByDescending { it.area() }

    /** Processed YUV sizes (largest first): burst input on phones without RAW. */
    val yuvSizes: List<Size> = sizes(map, ImageFormat.YUV_420_888).sortedByDescending { it.area() }
    val supportsRaw: Boolean = rawCapable && (rawSizes.isNotEmpty() || raw10Sizes.isNotEmpty())

    /** Equivalent 35mm focal length of the main focal length. */
    val equivFocal: Float by lazy {
        val ps = physicalSize ?: return@lazy 26f
        val pa = pixelArray
        val frac = if (pa != null && pa.width > 0) activeArray.width().toFloat() / pa.width else 1f
        val diag = hypot(ps.width * frac, ps.height * frac)
        if (diag <= 0f) 26f else focalLengths.first() * 43.27f / diag
    }

    val megapixels: Float get() = (jpegSizes.firstOrNull()?.area() ?: 0L) / 1e6f
    val maxResMegapixels: Float get() = maxOf(
        maxResJpegSizes.firstOrNull()?.area() ?: 0L, maxResRawSizes.firstOrNull()?.area() ?: 0L,
    ) / 1e6f

    /** Every (size, fps) the HAL can stream to an encoder surface. */
    val videoModes: List<VideoMode> by lazy {
        val out = mutableListOf<VideoMode>()
        val m = map ?: return@lazy out
        val sizes = try { m.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val candidates = listOf(24, 25, 30, 48, 50, 60, 120, 240)
        for (s in sizes) {
            val ratio = s.width.toFloat() / s.height
            val is169 = kotlin.math.abs(ratio - 16f / 9f) < 0.02f
            val isDci = s.width == 4096 && s.height == 2160
            val isOpenGate = kotlin.math.abs(ratio - 4f / 3f) < 0.02f && s.width >= 1920  // full-sensor 4:3
            if (s.width < 1280 || !(is169 || isDci || isOpenGate)) continue
            val minDur = try { m.getOutputMinFrameDuration(SurfaceTexture::class.java, s) } catch (_: Exception) { 0L }
            val maxFps = if (minDur > 0) (1e9 / minDur).toInt() else 30
            for (f in candidates) {
                if (f > maxFps + 1) continue
                if (fpsRanges.none { it.lower <= f && it.upper >= f }) continue
                out += VideoMode(s, f, false)
            }
        }
        try {
            for (s in m.highSpeedVideoSizes) {
                for (r in m.getHighSpeedVideoFpsRangesFor(s)) {
                    if (r.lower == r.upper && r.upper >= 120) out += VideoMode(s, r.upper, true)
                }
            }
        } catch (_: Exception) {}
        out.distinctBy { Triple(it.size, it.fps, it.highSpeed) }
            .sortedWith(compareByDescending<VideoMode> { it.size.width.toLong() * it.size.height }.thenByDescending { it.fps })
    }

    // ---- static color metadata (DNG ColorMatrix / ForwardMatrix) ------------------
    private fun cst(t: ColorSpaceTransform?): FloatArray? {
        t ?: return null
        val r = Array(9) { Rational.ZERO }
        t.copyElements(r, 0)
        return FloatArray(9) { r[it].toFloat() }
    }

    private fun mul(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    /** Color matrices including the per-unit calibration (CC x CM). */
    val colorMatrix1: FloatArray? by lazy {
        val cm = cst(get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)) ?: return@lazy null
        cst(get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))?.let { mul(it, cm) } ?: cm
    }
    val colorMatrix2: FloatArray? by lazy {
        val cm = cst(get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)) ?: return@lazy null
        cst(get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))?.let { mul(it, cm) } ?: cm
    }
    val forwardMatrix1: FloatArray? by lazy { cst(get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)) }
    val forwardMatrix2: FloatArray? by lazy { cst(get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)) }
    val illuminant1: Int = get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 21
    val illuminant2: Int = (get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()) ?: 0

    val blackPattern: FloatArray by lazy {
        val p = get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        FloatArray(4) { k -> p?.getOffsetForIndex(k % 2, k / 2)?.toFloat() ?: 64f }
    }

    val label: String
        get() {
            val face = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                else -> "Back"
            }
            val kind = when {
                isLogical -> "logical"
                physicalOf != null -> "physical of $physicalOf"
                hidden -> "hidden"
                else -> "camera"
            }
            return "$face ${equivFocal.roundToInt()}mm ($kind #$id)"
        }

    fun hwLevelName(): String = when (hwLevel) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($hwLevel)"
    }

    /** Human-readable report for the Hardware screen. */
    fun report(): List<Pair<String, String>> {
        val r = mutableListOf<Pair<String, String>>()
        fun add(k: String, v: Any?) { r += k to (v?.toString() ?: "—") }
        add("Camera", label)
        add("Hardware level", hwLevelName())
        add("Physical cameras", physicalIds.joinToString().ifEmpty { "—" })
        add("Sensor", "${pixelArray?.let { "${it.width}x${it.height}" }} · ${physicalSize?.let { "%.2fx%.2f mm".format(it.width, it.height) }}")
        add("Focal / aperture", "${focalLengths.joinToString { "%.2f".format(it) }} mm · f/${apertures.joinToString()} · ${equivFocal.roundToInt()} mm eq.")
        add("ISO range", isoRange?.let { "${it.lower}–${it.upper} (analog ≤ ${maxAnalogIso ?: "?"})" })
        add("Shutter range", exposureRange?.let { "${formatShutter(it.lower)} – ${formatShutter(it.upper)}" })
        add("Min focus", if (minFocusDistance > 0) "%.1f diopters (%.1f cm)".format(minFocusDistance, 100f / minFocusDistance) else "fixed")
        add("Manual sensor / post", "$manualSensor / $manualPost")
        add("RAW", if (supportsRaw) "${rawSizes.firstOrNull() ?: raw10Sizes.firstOrNull()}" else "no")
        add("Max JPEG", jpegSizes.firstOrNull()?.let { "$it (%.1f MP)".format(it.width * it.height / 1e6f) })
        add("Ultra-high-res mode", if (ultraHighRes) "%.1f MP".format(maxResMegapixels) else "no")
        add("Quad-Bayer binning", binningFactor?.toString())
        add("Zoom ratio", zoomRange?.let { "${it.lower}–${it.upper}" } ?: "crop ≤ ${maxDigitalZoom}x")
        add("OIS modes", oisModes.joinToString())
        add("Video stabilization", videoStabModes.joinToString())
        add("Tonemap modes / curve points", "${tonemapModes.joinToString()} / $maxCurvePoints")
        add("10-bit / HLG", "$tenBit / $supportsHlg")
        add("Dynamic range profiles", dynamicRanges.joinToString())
        add("Hybrid AE (Android 16)", aePriorityModes.joinToString().ifEmpty { "—" })
        add("Kelvin WB (Android 16)", cctRange?.toString())
        add("OEM extensions", extensions.joinToString { extensionName(it) }.ifEmpty { "—" })
        add("Noise reduction modes", nrModes.joinToString())
        add("Color matrices", if (colorMatrix1 != null) "yes (illum $illuminant1/$illuminant2)" else "no")
        add("Video modes", videoModes.take(14).joinToString { it.label })
        return r
    }

    companion object {
        fun formatShutter(ns: Long): String =
            if (ns >= 1_000_000_000L) "%.1fs".format(ns / 1e9) else "1/${(1e9 / ns).roundToInt()}"

        fun extensionName(e: Int): String = when (e) {
            0 -> "Auto"
            1 -> "Face retouch"
            2 -> "Bokeh"
            3 -> "HDR"
            4 -> "Night"
            else -> "Vendor #$e"
        }
    }
}

/** Enumerates every reachable camera, including unlisted and physical IDs. */
object CameraCatalog {
    fun scan(context: Context): List<CameraCaps> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val listed = try { mgr.cameraIdList.toList() } catch (_: Exception) { emptyList() }
        val result = linkedMapOf<String, CameraCaps>()

        fun extensionsFor(id: String): List<Int> =
            if (Build.VERSION.SDK_INT >= 31) {
                try { mgr.getCameraExtensionCharacteristics(id).supportedExtensions } catch (_: Exception) { emptyList() }
            } else emptyList()

        fun add(id: String, hidden: Boolean, physicalOf: String?) {
            if (result.containsKey(id)) return
            val ch = try { mgr.getCameraCharacteristics(id) } catch (_: Exception) { return }
            result[id] = CameraCaps(id, ch, hidden, physicalOf, if (physicalOf == null) extensionsFor(id) else emptyList())
        }
        listed.forEach { add(it, hidden = false, physicalOf = null) }
        // Probe IDs that the HAL did not list (some OEMs expose extra lenses this way).
        for (i in 0 until 64) add(i.toString(), hidden = true, physicalOf = null)
        // Physical sub-cameras of logical cameras.
        result.values.toList().filter { it.isLogical }.forEach { lc ->
            lc.physicalIds.forEach { add(it, hidden = !listed.contains(it), physicalOf = lc.id) }
        }
        return result.values.toList()
    }
}
