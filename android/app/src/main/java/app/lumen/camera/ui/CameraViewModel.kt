package app.lumen.camera.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lumen.camera.ai.AiEngine
import app.lumen.camera.camera.CameraCatalog
import app.lumen.camera.camera.CameraCaps
import app.lumen.camera.camera.CameraEngine
import app.lumen.camera.camera.Controls
import app.lumen.camera.camera.EncoderCatalog
import app.lumen.camera.camera.FlashMode
import app.lumen.camera.camera.LiveInfo
import app.lumen.camera.camera.ProPreset
import app.lumen.camera.camera.VideoCodec
import app.lumen.camera.camera.VideoMode
import app.lumen.camera.camera.VideoRecorder
import app.lumen.camera.core.NativeCore
import app.lumen.camera.processing.AppSettings
import app.lumen.camera.processing.PhotoEngine
import app.lumen.camera.processing.PhotoPipeline
import app.lumen.camera.processing.SettingsStore
import app.lumen.camera.storage.MediaSaver
import app.lumen.camera.util.DeviceOrientation
import app.lumen.camera.util.FrameAnalysis
import app.lumen.camera.util.ThermalMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Mode(val label: String) {
    ASTRO("ASTRO"), PORTRAIT("PORTRAIT"), PHOTO("PHOTO"), VIDEO("VIDEO"), PRO("PRO"), PRO_VIDEO("PRO VIDEO");

    val isVideo get() = this == VIDEO || this == PRO_VIDEO
    val isPro get() = this == PRO || this == PRO_VIDEO
}

enum class ProParam(val label: String) { ISO("ISO"), SHUTTER("SPEED"), EV("EV"), FOCUS("FOCUS"), WB("WB") }

data class ZoomStop(val label: String, val cameraId: String, val ratio: Float, val factor: Float)

data class UiState(
    val ready: Boolean = false,
    val cameras: List<CameraCaps> = emptyList(),
    val current: CameraCaps? = null,
    val mode: Mode = Mode.PHOTO,
    val night: Boolean = false,
    val nightSuggested: Boolean = false,
    val extension: Int? = null,
    val controls: Controls = Controls(),
    val proParam: ProParam? = null,
    val zoomStops: List<ZoomStop> = emptyList(),
    val zoomFactor: Float = 1f,
    val recording: Boolean = false,
    val recordSeconds: Int = 0,
    val busy: Boolean = false,
    val busyText: String = "",
    val progress: Float = 0f,
    val thumbnail: Bitmap? = null,
    val lastUri: Uri? = null,
    val message: String? = null,
    val previewSize: Size = Size(1440, 1080),
    val astroPreview: Bitmap? = null,
    val countdown: Int = 0,
    val sheet: Sheet = Sheet.NONE,
    val presets: List<ProPreset> = emptyList(),
    val videoModes: List<VideoMode> = emptyList(),
    val selectedVideo: VideoMode? = null,
    val analysis: FrameAnalysis? = null,
    val thermalWarning: Boolean = false,
)

enum class Sheet { NONE, SETTINGS, HARDWARE, MORE, PRESETS }

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    val engine = CameraEngine(ctx)
    private val ai = AiEngine(ctx)
    private val store = SettingsStore(ctx)
    val orientation = DeviceOrientation(ctx)
    private val thermal = ThermalMonitor(ctx)
    private val pipeline = PhotoPipeline(ctx, engine, ai) { _ui.value.cameras }
    private val opLock = Mutex()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<AppSettings> = _settings
    val live: StateFlow<LiveInfo> get() = engine.live

    private var texture: SurfaceTexture? = null
    private var recorder: VideoRecorder? = null
    private var recordFd: ParcelFileDescriptor? = null
    private var recordUri: Uri? = null
    private var recordJob: Job? = null
    @Volatile private var astroCancel = false

    // ---- startup ------------------------------------------------------------------------
    fun onPermissionsGranted() {
        if (_ui.value.ready) return
        viewModelScope.launch {
            val cams = withContext(Dispatchers.IO) { CameraCatalog.scan(ctx) }
            val main = cams.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && !it.hidden && it.physicalOf == null }
                ?: cams.firstOrNull()
            _ui.update { it.copy(ready = true, cameras = cams, current = main, presets = store.presets()) }
            main?.let { selectCameraInternal(it) }
            orientation.start()
            thermal.start()
            launch {
                engine.live.collect { li ->
                    val suggest = li.ev100 < 3.5f && _ui.value.mode == Mode.PHOTO && !_ui.value.night
                    if (suggest != _ui.value.nightSuggested) _ui.update { it.copy(nightSuggested = suggest) }
                }
            }
            launch {
                thermal.status.collect {
                    _ui.update { s -> s.copy(thermalWarning = thermal.isWarm) }
                    if (thermal.shouldStopRecording && _ui.value.recording) {
                        stopRecording()
                        toast("Recording stopped: the phone is too hot")
                    }
                }
            }
        }
    }

    private fun selectCameraInternal(c: CameraCaps) {
        _ui.update {
            it.copy(
                current = c,
                zoomStops = buildZoomStops(it.cameras, c),
                videoModes = c.videoModes,
                selectedVideo = pickVideoMode(c, _settings.value.videoModeKey),
                controls = Controls(),
                zoomFactor = 1f,
            )
        }
        reconfigure()
    }

    fun onSurface(tex: SurfaceTexture?) {
        texture = tex
        if (tex != null) reconfigure() else viewModelScope.launch { opLock.withLock { engine.closeSession() } }
    }

    fun onPause() {
        if (_ui.value.recording) stopRecording()
        astroCancel = true
        viewModelScope.launch { opLock.withLock { engine.closeCamera() } }
        orientation.stop()
    }

    fun onResume() {
        orientation.start()
        if (_ui.value.ready && texture != null) reconfigure()
    }

    override fun onCleared() {
        engine.release()
        ai.close()
        thermal.stop()
        super.onCleared()
    }

    // ---- zoom stops (lens pills) ------------------------------------------------------------
    private fun buildZoomStops(all: List<CameraCaps>, main: CameraCaps): List<ZoomStop> {
        if (main.facing == CameraCharacteristics.LENS_FACING_FRONT) {
            return listOf(ZoomStop("1", main.id, 1f, 1f))
        }
        val base = main.equivFocal
        val backs = all.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK && !it.isLogical }
        val stops = mutableListOf<ZoomStop>()
        val zr = main.zoomRange
        val factors = (backs.map { it to it.equivFocal / base } + (main to 1f)).sortedBy { it.second }
        for ((cam, f) in factors) {
            if (stops.any { abs(it.factor - f) / f < 0.12f }) continue
            val viaZoom = zr != null && f >= zr.lower - 0.01f && f <= zr.upper && (main.isLogical || cam.id == main.id)
            stops += if (viaZoom) ZoomStop(label(f), main.id, f, f) else ZoomStop(label(f), cam.id, 1f, f)
        }
        // digital stops (cropped from the nearest lens; RAW pipeline can use AI zoom)
        val longest = stops.maxOf { it.factor }
        for (d in listOf(2f, longest * 2f, longest * 4f)) {
            if (stops.any { abs(it.factor - d) / d < 0.15f }) continue
            if (zr != null && d <= zr.upper) stops += ZoomStop(label(d), main.id, d, d)
        }
        return stops.sortedBy { it.factor }
    }

    private fun label(f: Float): String =
        if (f < 1f) ".${(f * 10).roundToInt()}" else if (abs(f - f.roundToInt()) < 0.05f) "${f.roundToInt()}" else "%.1f".format(f)

    fun selectZoom(stop: ZoomStop) {
        val s = _ui.value
        if (stop.cameraId != s.current?.id) {
            val cam = s.cameras.firstOrNull { it.id == stop.cameraId } ?: return
            _ui.update { it.copy(current = cam, zoomFactor = stop.factor, controls = it.controls.copy(zoomRatio = 1f)) }
            reconfigure()
        } else {
            setControls(s.controls.copy(zoomRatio = stop.ratio))
            _ui.update { it.copy(zoomFactor = stop.factor) }
        }
    }

    fun pinchZoom(scale: Float) {
        val c = _ui.value.current ?: return
        val zr = c.zoomRange
        val maxZ = zr?.upper ?: c.maxDigitalZoom
        val minZ = zr?.lower ?: 1f
        val z = (_ui.value.controls.zoomRatio * scale).coerceIn(minZ, maxZ)
        setControls(_ui.value.controls.copy(zoomRatio = z))
        val camFactor = _ui.value.zoomStops.firstOrNull { it.cameraId == c.id && it.ratio == 1f }?.factor ?: 1f
        _ui.update { it.copy(zoomFactor = z * (if (c.isLogical) 1f else camFactor)) }
    }

    fun flipCamera() {
        val s = _ui.value
        val wantFront = s.current?.facing != CameraCharacteristics.LENS_FACING_FRONT
        val target = s.cameras.firstOrNull {
            (it.facing == CameraCharacteristics.LENS_FACING_FRONT) == wantFront && !it.hidden && it.physicalOf == null
        } ?: return
        selectCameraInternal(target)
    }

    fun selectCamera(c: CameraCaps) = selectCameraInternal(c)

    // ---- modes -------------------------------------------------------------------------------
    fun setMode(m: Mode) {
        if (_ui.value.recording || _ui.value.busy) return
        if (m == Mode.PORTRAIT && _ui.value.current?.extensions?.contains(2) != true) {
            toast("Portrait uses the phone's Bokeh extension, which this camera does not offer")
        }
        _ui.update {
            it.copy(
                mode = m,
                proParam = null,
                extension = if (m == Mode.PORTRAIT) 2 else null,
                night = if (m != Mode.PHOTO) false else it.night,
                controls = if (m.isPro) it.controls else it.controls.copy(
                    iso = null, shutterNs = null, focusDiopters = null, wbKelvin = null, evSteps = 0,
                ),
            )
        }
        reconfigure()
    }

    fun toggleNight() {
        _ui.update { it.copy(night = !it.night, nightSuggested = false) }
    }

    fun setExtension(ext: Int?) {
        _ui.update { it.copy(extension = ext, sheet = Sheet.NONE, mode = Mode.PHOTO) }
        reconfigure()
    }

    fun showSheet(s: Sheet) = _ui.update { it.copy(sheet = s) }

    // ---- controls ---------------------------------------------------------------------------
    fun setControls(c: Controls) {
        _ui.update { it.copy(controls = c) }
        engine.applyControls(c.copy(
            noiseReductionMode = _settings.value.ispNoiseReduction.takeIf { it >= 0 },
            edgeMode = _settings.value.ispSharpening.takeIf { it >= 0 },
            ois = c.ois,
            logCurve = _ui.value.mode == Mode.PRO_VIDEO && _settings.value.logProfile,
            videoStabilization = _settings.value.stabilization,
        ))
    }

    fun selectProParam(p: ProParam?) = _ui.update { it.copy(proParam = if (it.proParam == p) null else p) }

    fun tapFocus(nx: Float, ny: Float) = engine.focusAt(nx, ny)

    fun cycleFlash() {
        val c = _ui.value.controls
        val next = when (c.flash) {
            FlashMode.OFF -> if (_ui.value.mode.isVideo) FlashMode.TORCH else FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        setControls(c.copy(flash = next))
    }

    fun savePreset(name: String) {
        val c = _ui.value.controls
        val list = store.presets().filter { it.name != name } + ProPreset(name, c.iso, c.shutterNs, c.evSteps, c.focusDiopters, c.wbKelvin, c.wbTint)
        store.savePresets(list)
        _ui.update { it.copy(presets = list) }
        toast("Preset \"$name\" saved")
    }

    fun applyPreset(p: ProPreset) {
        setControls(_ui.value.controls.copy(iso = p.iso, shutterNs = p.shutterNs, evSteps = p.evSteps,
            focusDiopters = p.focusDiopters, wbKelvin = p.wbKelvin, wbTint = p.wbTint))
        _ui.update { it.copy(sheet = Sheet.NONE) }
    }

    fun deletePreset(p: ProPreset) {
        val list = store.presets().filter { it.name != p.name }
        store.savePresets(list)
        _ui.update { it.copy(presets = list) }
    }

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        val old = _settings.value
        val new = f(old)
        _settings.value = new
        store.save(new)
        val streamsChanged = old.maxResolution != new.maxResolution || old.saveRaw != new.saveRaw ||
            old.aspect != new.aspect || old.photoEngine != new.photoEngine || old.videoModeKey != new.videoModeKey ||
            old.tenBitHlg != new.tenBitHlg
        if (old.videoModeKey != new.videoModeKey) {
            _ui.value.current?.let { c -> _ui.update { it.copy(selectedVideo = pickVideoMode(c, new.videoModeKey)) } }
        }
        if (streamsChanged) reconfigure() else setControls(_ui.value.controls)
    }

    fun onAnalysisFrame(bmp: Bitmap) {
        val s = _settings.value
        if (!s.histogram && !s.focusPeaking && !s.zebras) return
        viewModelScope.launch(Dispatchers.Default) {
            val a = FrameAnalysis.analyze(bmp, s.focusPeaking, s.zebras)
            _ui.update { it.copy(analysis = a) }
        }
    }

    private fun toast(msg: String) {
        _ui.update { it.copy(message = msg) }
        viewModelScope.launch {
            delay(2500)
            _ui.update { if (it.message == msg) it.copy(message = null) else it }
        }
    }

    // ---- session setup -------------------------------------------------------------------------
    private fun pickVideoMode(c: CameraCaps, key: String): VideoMode? {
        val modes = c.videoModes
        return modes.firstOrNull { keyOf(it) == key }
            ?: modes.firstOrNull { it.size.width == 3840 && it.fps == 30 && !it.highSpeed }
            ?: modes.firstOrNull { it.size.width == 1920 && it.fps == 30 }
            ?: modes.firstOrNull()
    }

    fun keyOf(m: VideoMode) = "${m.size.width}x${m.size.height}@${m.fps}${if (m.highSpeed) "h" else ""}"

    private fun choosePreview(c: CameraCaps, aspect: Float): Size {
        val cands = c.previewSizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= 1920 }
        return cands.maxByOrNull { it.width.toLong() * it.height }
            ?: c.previewSizes.firstOrNull { it.width <= 1920 } ?: Size(1280, 960)
    }

    private fun reconfigure() {
        viewModelScope.launch {
            opLock.withLock {
                val tex = texture ?: return@withLock
                val s = _ui.value
                val c = s.current ?: return@withLock
                val st = _settings.value
                try {
                    if (engine.caps?.id != c.id) engine.open(c)
                    val ext = s.extension
                    when {
                        ext != null && c.extensions.contains(ext) -> {
                            val pv = engine.startExtensionSession(tex, ext, st.aspect)
                            if (pv != null) _ui.update { it.copy(previewSize = pv) }
                        }
                        s.mode.isVideo -> {
                            val vm = s.selectedVideo
                            val aspect = vm?.let { it.size.width.toFloat() / it.size.height } ?: (16f / 9f)
                            val pv = if (vm?.highSpeed == true) vm.size else choosePreview(c, aspect)
                            engine.startPhotoSession(tex, pv, null, null, 0, false)
                            _ui.update { it.copy(previewSize = pv) }
                        }
                        else -> {
                            val aspect = st.aspect
                            val maxRes = st.maxResolution && c.ultraHighRes
                            val wantRaw = c.supportsRaw && (st.photoEngine == PhotoEngine.LUMEN || st.saveRaw || s.mode == Mode.ASTRO || s.mode == Mode.PRO)
                            val rawFmt = PhotoPipeline.rawFormatFor(c)
                            val rawSize = when {
                                !wantRaw -> null
                                maxRes && c.maxResRawSizes.isNotEmpty() -> c.maxResRawSizes.first()
                                rawFmt == android.graphics.ImageFormat.RAW10 -> c.raw10Sizes.firstOrNull()
                                else -> c.rawSizes.firstOrNull()
                            }
                            val jpegList = if (maxRes && c.maxResJpegSizes.isNotEmpty()) c.maxResJpegSizes else c.jpegSizes
                            val jpeg = jpegList.firstOrNull { abs(it.width.toFloat() / it.height - aspect) < 0.02f } ?: jpegList.firstOrNull()
                            val pv = choosePreview(c, aspect)
                            engine.startPhotoSession(tex, pv, if (maxRes && rawSize != null) null else jpeg, rawSize, rawFmt, maxRes)
                            _ui.update { it.copy(previewSize = pv) }
                        }
                    }
                    setControls(_ui.value.controls)
                } catch (e: Exception) {
                    toast("Camera error: ${e.message}")
                }
            }
        }
    }

    // ---- shutter --------------------------------------------------------------------------------
    fun shutter() {
        val s = _ui.value
        if (s.busy) {
            if (s.mode == Mode.ASTRO) astroCancel = true
            return
        }
        if (s.mode.isVideo) {
            if (s.recording) stopRecording() else startRecording()
            return
        }
        viewModelScope.launch {
            val timer = _settings.value.timerSeconds
            for (i in timer downTo 1) {
                _ui.update { it.copy(countdown = i) }
                delay(1000)
            }
            _ui.update { it.copy(countdown = 0) }
            takePhoto()
        }
    }

    private suspend fun takePhoto() = opLock.withLock {
        val s = _ui.value
        val st = _settings.value
        val caps = engine.caps ?: return@withLock
        val rot = orientation.captureRotation(caps)
        _ui.update { it.copy(busy = true, busyText = "Capturing", progress = 0f) }
        try {
            val out = when {
                s.extension != null -> pipeline.extension(rot, st.jpegQuality, app.lumen.camera.camera.CameraCaps.extensionName(s.extension))
                s.mode == Mode.ASTRO -> {
                    astroCancel = false
                    pipeline.astro(st, rot, st.astroDarkFrames, { p ->
                        // stacked preview arrives in sensor orientation; rotate for the portrait viewfinder
                        val pv = p.preview?.let { b ->
                            val m = android.graphics.Matrix().apply { postRotate(caps.sensorOrientation.toFloat()) }
                            Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it != b) b.recycle() }
                        }
                        _ui.update { it.copy(busyText = p.text, progress = p.frame.toFloat() / p.total, astroPreview = pv ?: it.astroPreview) }
                    }, { astroCancel })
                }
                s.mode == Mode.PRO && st.saveRaw && engine.rawReader != null ->
                    pipeline.proRaw(st, rot, s.controls.wbKelvin, s.controls.wbTint)
                (st.photoEngine == PhotoEngine.LUMEN || s.night || s.mode == Mode.PRO) && engine.rawReader != null -> {
                    val plan = pipeline.planBurst(caps, s.night, st, s.controls.iso.takeIf { s.mode == Mode.PRO }, s.controls.shutterNs.takeIf { s.mode == Mode.PRO })
                    pipeline.lumenBurst(plan, s.night, st, rot, s.controls.zoomRatio, s.controls.wbKelvin, s.controls.wbTint) { text, p ->
                        _ui.update { it.copy(busyText = text, progress = p) }
                    }
                }
                else -> pipeline.ispJpeg(rot, st.jpegQuality)
            }
            _ui.update { it.copy(thumbnail = out.thumbnail ?: it.thumbnail, lastUri = out.uri ?: it.lastUri, astroPreview = null) }
            toast(out.message)
        } catch (e: Exception) {
            toast("Capture failed: ${e.message}")
        } finally {
            _ui.update { it.copy(busy = false, busyText = "", progress = 0f) }
        }
    }

    // ---- video ----------------------------------------------------------------------------------
    private fun startRecording() {
        viewModelScope.launch {
            opLock.withLock {
                val tex = texture ?: return@withLock
                val caps = engine.caps ?: return@withLock
                val st = _settings.value
                val vm = _ui.value.selectedVideo ?: return@withLock
                val hlg = st.tenBitHlg && caps.supportsHlg
                val enc = EncoderCatalog.find(st.videoCodec, vm.size.width, vm.size.height, vm.fps, hlg && st.videoCodec != VideoCodec.APV)
                    ?: EncoderCatalog.find(VideoCodec.HEVC, vm.size.width, vm.size.height, vm.fps, hlg)
                    ?: EncoderCatalog.find(VideoCodec.AVC, vm.size.width, vm.size.height, vm.fps, false)
                if (enc == null) {
                    toast("No encoder supports ${vm.label}")
                    return@withLock
                }
                val auto = autoBitrate(vm, enc.codec)
                val bitrate = (if (st.bitrateMbps > 0) st.bitrateMbps * 1_000_000 else auto).coerceAtMost(enc.maxBitrate)
                val audioOk = st.recordAudio && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val (uri, pfd) = MediaSaver.createVideo(ctx, MediaSaver.timestampName("VID"))
                try {
                    val rec = VideoRecorder(
                        VideoRecorder.Config(
                            vm.size.width, vm.size.height, vm.fps, bitrate, enc.codec, enc.name, hlg,
                            st.logProfile && _ui.value.mode == Mode.PRO_VIDEO, st.cbr, audioOk,
                        ),
                        pfd.fileDescriptor, orientation.captureRotation(caps), caps.timestampRealtime,
                    )
                    val pv = if (vm.highSpeed) vm.size else _ui.value.previewSize
                    engine.startVideoSession(tex, pv, rec.inputSurface, vm, hlg)
                    setControls(_ui.value.controls.copy(fps = vm.fps))
                    rec.start()
                    recorder = rec
                    recordFd = pfd
                    recordUri = uri
                    _ui.update { it.copy(recording = true, recordSeconds = 0) }
                    toast("${enc.codec.label} · ${bitrate / 1_000_000} Mb/s" + if (hlg) " · 10-bit HLG" else "")
                    recordJob = viewModelScope.launch {
                        while (isActive) {
                            delay(1000)
                            _ui.update { it.copy(recordSeconds = it.recordSeconds + 1) }
                        }
                    }
                } catch (e: Exception) {
                    pfd.close()
                    MediaSaver.delete(ctx, uri)
                    toast("Recording failed: ${e.message}")
                }
            }
        }
    }

    private fun autoBitrate(vm: VideoMode, codec: VideoCodec): Int {
        val bpp = when (codec) {
            VideoCodec.AVC -> 0.5
            VideoCodec.APV -> 4.0
            else -> 0.35
        }
        return (vm.size.width.toDouble() * vm.size.height * vm.fps.coerceAtMost(60) * bpp).toInt().coerceIn(8_000_000, 800_000_000)
    }

    private fun stopRecording() {
        recordJob?.cancel()
        val rec = recorder ?: return
        recorder = null
        _ui.update { it.copy(recording = false, busy = true, busyText = "Saving video") }
        viewModelScope.launch {
            opLock.withLock {
                withContext(Dispatchers.IO) {
                    try { rec.stop() } catch (_: Exception) {}
                    recordFd?.close()
                    recordFd = null
                }
                val uri = recordUri
                recordUri = null
                if (uri != null) {
                    MediaSaver.publish(ctx, uri)
                    val thumb = withContext(Dispatchers.IO) {
                        try {
                            MediaMetadataRetriever().run {
                                setDataSource(ctx, uri)
                                val f = getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
                                release()
                                f
                            }
                        } catch (_: Exception) { null }
                    }
                    _ui.update { it.copy(thumbnail = thumb ?: it.thumbnail, lastUri = uri) }
                }
            }
            _ui.update { it.copy(busy = false, busyText = "") }
            reconfigure()
        }
    }

    // ---- misc ----------------------------------------------------------------------------------------
    fun exportLut() {
        viewModelScope.launch(Dispatchers.IO) {
            val f = File(ctx.cacheDir, "LumenLog_to_Rec709.cube")
            if (NativeCore.writeLogLut(f.absolutePath) != NativeCore.OK) return@launch
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Lumen")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                toast("LUT saved to Download/Lumen")
            }
        }
    }

    fun hardwareReport(): List<Pair<String, String>> {
        val s = _ui.value
        val out = mutableListOf<Pair<String, String>>()
        out += "Imaging core" to NativeCore.version()
        out += "AI models" to "denoise=${ai.hasDenoiser()} · AI zoom=${ai.hasUpscaler()}"
        s.cameras.forEach { c -> out += c.report(); out += "" to "" }
        EncoderCatalog.describe().forEach { out += "Encoder" to it }
        return out
    }
}
