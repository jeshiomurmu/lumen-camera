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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
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

/**
 * Photo framing. [stream] is the sensor output ratio (long/short) that is captured; 1:1 and
 * Full are centre crops of it, like the stock camera. [stored] is the value kept in settings.
 */
enum class Aspect(val label: String, val stream: Float, val stored: Float) {
    R3_4("3:4", 4f / 3f, 4f / 3f),
    R9_16("9:16", 16f / 9f, 16f / 9f),
    R1_1("1:1", 4f / 3f, 1f),
    FULL("Full", 16f / 9f, 0f);

    companion object {
        fun of(stored: Float): Aspect = entries.firstOrNull { abs(it.stored - stored) < 0.02f } ?: R3_4
    }
}

/** Live recording read-outs, kept apart from [UiState] so they don't redraw the whole screen. */
data class RecordInfo(val seconds: Int = 0, val timecode: String? = null, val audioDb: FloatArray? = null)

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
    val thermalWarning: Boolean = false,
    /** Increments on every shutter press (drives the capture blink). */
    val shutterTick: Int = 0,
    val showAspectPicker: Boolean = false,
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
    /**
     * Every camera call (session rebuilds, repeating requests, captures) runs on this one
     * thread, never on the UI thread: Camera2 blocks the caller while it reconfigures.
     */
    private val camExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "LumenControl") }
    private val cam = camExec.asCoroutineDispatcher()
    /** Background work (saving, merging) that must not hold up the next shot. */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastHeavy: Deferred<PhotoPipeline.Output>? = null
    private val _processing = MutableStateFlow(0)
    /** Number of photos still being saved / merged (spinner on the gallery button). */
    val processing: StateFlow<Int> = _processing
    private val _record = MutableStateFlow(RecordInfo())
    val record: StateFlow<RecordInfo> = _record
    private val _analysis = MutableStateFlow<FrameAnalysis?>(null)
    val analysis: StateFlow<FrameAnalysis?> = _analysis

    /** Screen long/short ratio, used for the Full framing (e.g. 19.5:9 = 2.17). */
    val screenRatio: Float = run {
        val dm = app.resources.displayMetrics
        val wm = app.getSystemService(android.view.WindowManager::class.java)
        val b = if (android.os.Build.VERSION.SDK_INT >= 30) wm?.maximumWindowMetrics?.bounds else null
        val w = b?.width() ?: dm.widthPixels
        val h = b?.height() ?: dm.heightPixels
        (maxOf(w, h).toFloat() / minOf(w, h)).coerceIn(1.6f, 2.6f)
    }

    fun aspect(): Aspect = Aspect.of(_settings.value.aspect)
    /** Centre-crop ratio for the saved photo (null = keep the stream as is). */
    private fun cropFor(a: Aspect): Float? = when (a) {
        Aspect.R1_1 -> 1f
        Aspect.FULL -> screenRatio
        else -> null
    }
    /** Viewfinder height / width in portrait. */
    fun displayRatio(a: Aspect): Float = when (a) {
        Aspect.R1_1 -> 1f
        Aspect.FULL -> screenRatio
        else -> a.stream
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private val _settings = MutableStateFlow(store.load()).also { engine.antibanding = it.value.antibanding }
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
        if (tex != null) reconfigure() else viewModelScope.launch(cam) { opLock.withLock { engine.closeSession() } }
    }

    fun onPause() {
        if (_ui.value.recording) stopRecording()
        astroCancel = true
        viewModelScope.launch(cam) { opLock.withLock { engine.closeCamera() } }
        orientation.stop()
    }

    fun onResume() {
        orientation.start()
        if (_ui.value.ready && texture != null) reconfigure()
    }

    override fun onCleared() {
        bg.cancel()
        engine.release()
        camExec.shutdown()
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
        // without a RAW stream, Night needs an extra YUV stream for the merge
        if (engine.rawReader == null) reconfigure()
    }

    fun setAspect(a: Aspect) {
        val old = aspect()
        _ui.update { it.copy(showAspectPicker = false) }
        if (old == a) return
        updateSettings { it.copy(aspect = a.stored) }
    }

    fun toggleAspectPicker() = _ui.update { it.copy(showAspectPicker = !it.showAspectPicker) }

    fun setExtension(ext: Int?) {
        _ui.update { it.copy(extension = ext, sheet = Sheet.NONE, mode = Mode.PHOTO) }
        reconfigure()
    }

    fun showSheet(s: Sheet) = _ui.update { it.copy(sheet = s) }

    // ---- controls ---------------------------------------------------------------------------
    fun setControls(c: Controls) {
        _ui.update { it.copy(controls = c) }
        val req = c.copy(
            noiseReductionMode = _settings.value.ispNoiseReduction.takeIf { it >= 0 },
            edgeMode = _settings.value.ispSharpening.takeIf { it >= 0 },
            ois = c.ois,
            logCurve = _ui.value.mode == Mode.PRO_VIDEO && _settings.value.logProfile,
            videoStabilization = _settings.value.stabilization,
        )
        viewModelScope.launch(cam) { engine.applyControls(req) }
    }

    fun selectProParam(p: ProParam?) = _ui.update { it.copy(proParam = if (it.proParam == p) null else p) }

    fun tapFocus(nx: Float, ny: Float) {
        viewModelScope.launch(cam) { engine.focusAt(nx, ny) }
    }

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
        engine.antibanding = new.antibanding
        val streamsChanged = old.maxResolution != new.maxResolution || old.saveRaw != new.saveRaw ||
            Aspect.of(old.aspect).stream != Aspect.of(new.aspect).stream || old.photoEngine != new.photoEngine ||
            old.videoModeKey != new.videoModeKey || old.tenBitHlg != new.tenBitHlg
        if (old.videoModeKey != new.videoModeKey) {
            _ui.value.current?.let { c -> _ui.update { it.copy(selectedVideo = pickVideoMode(c, new.videoModeKey)) } }
        }
        if (streamsChanged) reconfigure() else setControls(_ui.value.controls)
    }

    fun onAnalysisFrame(bmp: Bitmap) {
        val s = _settings.value
        if (!s.histogram && !s.focusPeaking && !s.zebras && !s.falseColor) return
        viewModelScope.launch(Dispatchers.Default) {
            _analysis.value = FrameAnalysis.analyze(bmp, s.focusPeaking, s.zebras, s.falseColor)
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
        viewModelScope.launch(cam) {
            opLock.withLock {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val tex = texture ?: return@withLock
                val s = _ui.value
                val c = s.current ?: return@withLock
                val st = _settings.value
                try {
                    if (engine.caps?.id != c.id || !engine.isOpen) engine.open(c)
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
                            val aspect = Aspect.of(st.aspect).stream
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
                            // No RAW (e.g. Galaxy A): Night merges the ISP's YUV frames, so add that
                            // stream only while Night is on (keeps the normal photo session light).
                            val yuvCands = if (rawSize == null && s.night && s.mode == Mode.PHOTO) {
                                val same = c.yuvSizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f }
                                (same.filter { it.width * it.height <= 12_600_000 }.take(1) +
                                    same.filter { it.width * it.height <= 8_000_000 }.take(1) + same.take(1)).distinct()
                            } else emptyList()
                            engine.startPhotoSession(tex, pv, if (maxRes && rawSize != null) null else jpeg, rawSize, rawFmt, maxRes, yuvCands)
                            _ui.update { it.copy(previewSize = pv) }
                            if (yuvCands.isNotEmpty() && engine.yuvReader == null) toast("Night merge is not available on this camera")
                        }
                    }
                    setControls(_ui.value.controls)
                    perf("session mode=${s.mode} aspect=${aspect().label} night=${s.night} " +
                        "yuv=${engine.yuvReader != null} raw=${engine.rawReader != null} ${android.os.SystemClock.elapsedRealtime() - t0}ms")
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

    private suspend fun takePhoto() {
        if (_processing.value >= 8) return   // storage can't keep up: drop the press
        _ui.update { it.copy(shutterTick = it.shutterTick + 1) }
        val tPress = android.os.SystemClock.elapsedRealtime()
        var kind = "isp"
        val pending: Deferred<PhotoPipeline.Output> = withContext(cam) { opLock.withLock<Deferred<PhotoPipeline.Output>?> {
            val s = _ui.value
            val st = _settings.value
            val caps = engine.caps ?: return@withLock null
            val rot = orientation.captureRotation(caps)
            val crop = cropFor(aspect())
            val progress: (String, Float) -> Unit = { text, p ->
                _ui.update { if (it.busy) it.copy(busyText = text, progress = p) else it }
            }
            // "Device processing" keeps the phone's own look everywhere (Night merges ISP frames)
            val rawBurst = st.photoEngine == PhotoEngine.LUMEN && engine.rawReader != null &&
                !(s.mode == Mode.PRO && st.saveRaw)
            val yuvNight = s.night && engine.yuvReader != null
            val heavy = rawBurst || yuvNight
            kind = when { rawBurst -> "raw-burst"; yuvNight -> "night-merge"; s.extension != null -> "extension"; s.mode == Mode.ASTRO -> "astro"; else -> "isp" }
            try {
                if (heavy) {
                    lastHeavy?.join()   // one merge at a time keeps memory in check
                    _ui.update { it.copy(busy = true, busyText = "Hold still", progress = 0f) }
                }
                when {
                    s.extension != null -> {
                        _ui.update { it.copy(busy = true, busyText = "Processing", progress = 0f) }
                        CompletableDeferred(pipeline.extension(rot, st.jpegQuality, CameraCaps.extensionName(s.extension)))
                    }
                    s.mode == Mode.ASTRO -> {
                        astroCancel = false
                        _ui.update { it.copy(busy = true, busyText = "Starting", progress = 0f) }
                        CompletableDeferred(pipeline.astro(st, rot, st.astroDarkFrames, { p ->
                            // stacked preview arrives in sensor orientation; rotate for the portrait viewfinder
                            val pv = p.preview?.let { b ->
                                val m = android.graphics.Matrix().apply { postRotate(caps.sensorOrientation.toFloat()) }
                                Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it != b) b.recycle() }
                            }
                            _ui.update { it.copy(busyText = p.text, progress = p.frame.toFloat() / p.total, astroPreview = pv ?: it.astroPreview) }
                        }, { astroCancel }))
                    }
                    s.mode == Mode.PRO && st.saveRaw && engine.rawReader != null -> {
                        _ui.update { it.copy(busy = true, busyText = "Saving RAW", progress = 0f) }
                        CompletableDeferred(pipeline.proRaw(st, rot, s.controls.wbKelvin, s.controls.wbTint))
                    }
                    rawBurst -> {
                        val plan = pipeline.planBurst(caps, s.night, st, s.controls.iso.takeIf { s.mode == Mode.PRO }, s.controls.shutterNs.takeIf { s.mode == Mode.PRO })
                        pipeline.lumenBurst(bg, plan, s.night, st, rot, s.controls.zoomRatio, s.controls.wbKelvin, s.controls.wbTint, crop, progress)
                            .also { lastHeavy = it }
                    }
                    yuvNight -> pipeline.yuvNight(bg, st, rot, crop, progress).also { lastHeavy = it }
                    // Everything else: the phone's own ISP, zero shutter lag, saved in the background.
                    else -> pipeline.ispJpeg(bg, rot, st.jpegQuality, crop)
                }
            } catch (e: Exception) {
                toast("Capture failed: ${e.message}")
                null
            } finally {
                _ui.update { it.copy(busy = false, busyText = "", progress = 0f, astroPreview = null) }
            }
        } } ?: return
        val tFree = android.os.SystemClock.elapsedRealtime()
        _processing.update { it + 1 }
        viewModelScope.launch {
            try {
                val out = pending.await()
                perf("shot kind=$kind shutter_free=${tFree - tPress}ms saved=${android.os.SystemClock.elapsedRealtime() - tPress}ms aspect=${aspect().label}")
                _ui.update { it.copy(thumbnail = out.thumbnail ?: it.thumbnail, lastUri = out.uri ?: it.lastUri) }
                if (out.message.isNotEmpty()) toast(out.message)
            } catch (e: Exception) {
                toast("Save failed: ${e.message}")
            } finally {
                _processing.update { it - 1 }
            }
        }
    }

    // ---- video ----------------------------------------------------------------------------------
    private fun startRecording() {
        viewModelScope.launch(cam) {
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
                val auto = autoBitrate(vm, enc.codec).coerceAtMost(enc.maxBitrate)
                // A chosen bitrate is tried as is: many encoders accept more than they advertise.
                var bitrate = if (st.bitrateMbps > 0) st.bitrateMbps * 1_000_000 else auto
                val audioOk = st.recordAudio && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val (uri, pfd) = MediaSaver.createVideo(ctx, MediaSaver.timestampName("VID"))
                try {
                    fun make(br: Int) = VideoRecorder(
                        VideoRecorder.Config(
                            vm.size.width, vm.size.height, vm.fps, br, enc.codec, enc.name, hlg,
                            st.logProfile && _ui.value.mode == Mode.PRO_VIDEO, st.cbr, audioOk,
                            audioGainDb = st.audioGainDb.toFloat(),
                        ),
                        pfd.fileDescriptor, orientation.captureRotation(caps), caps.timestampRealtime,
                    )
                    val rec = try { make(bitrate) } catch (e: Exception) {
                        if (bitrate <= enc.maxBitrate) throw e
                        bitrate = enc.maxBitrate
                        make(bitrate)
                    }
                    val pv = if (vm.highSpeed) vm.size else _ui.value.previewSize
                    engine.startVideoSession(tex, pv, rec.inputSurface, vm, hlg)
                    // lock white balance for the take so colour doesn't drift mid-shot
                    setControls(_ui.value.controls.copy(fps = vm.fps, awbLock = st.lockWbOnRecord && _ui.value.controls.wbKelvin == null))
                    rec.start()
                    recorder = rec
                    recordFd = pfd
                    recordUri = uri
                    _ui.update { it.copy(recording = true) }
                    _record.value = RecordInfo()
                    toast("${enc.codec.label} · ${bitrate / 1_000_000} Mb/s" + if (hlg) " · 10-bit HLG" else "")
                    perf("record ${vm.label} codec=${enc.codec.label} encoder=${enc.name} bitrate=${bitrate / 1_000_000}Mb/s (encoder max ${enc.maxBitrate / 1_000_000})")
                    val startMs = android.os.SystemClock.elapsedRealtime()
                    val fps = vm.fps
                    recordJob = viewModelScope.launch {
                        while (isActive) {
                            delay(100)
                            val el = android.os.SystemClock.elapsedRealtime() - startMs
                            val tc = if (st.timecodeTod) {
                                val c = java.util.Calendar.getInstance()
                                timecode(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
                                    c.get(java.util.Calendar.SECOND), (c.get(java.util.Calendar.MILLISECOND) * fps / 1000))
                            } else {
                                val s = el / 1000
                                timecode((s / 3600).toInt(), ((s / 60) % 60).toInt(), (s % 60).toInt(), ((el % 1000) * fps / 1000).toInt())
                            }
                            _record.value = RecordInfo((el / 1000).toInt(), tc, if (st.audioMeters && audioOk) rec.levelsDb else null)
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

    private fun perf(msg: String) = android.util.Log.i("LumenPerf", msg)

    private fun timecode(h: Int, m: Int, s: Int, f: Int) = "%02d:%02d:%02d:%02d".format(h, m, s, f)

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
        _record.value = RecordInfo()
        if (_ui.value.controls.awbLock) setControls(_ui.value.controls.copy(awbLock = false))
        viewModelScope.launch(cam) {
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
