package app.lumen.camera.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.camera.camera.CameraCaps
import app.lumen.camera.camera.Controls
import app.lumen.camera.ui.components.AspectPicker
import app.lumen.camera.ui.components.InfoText
import app.lumen.camera.ui.components.ModeCarousel
import app.lumen.camera.ui.components.ProChips
import app.lumen.camera.ui.components.ProValue
import app.lumen.camera.ui.components.RecordOverlay
import app.lumen.camera.ui.components.RulerDial
import app.lumen.camera.ui.components.ShutterRow
import app.lumen.camera.ui.components.TopBar
import app.lumen.camera.ui.components.Viewfinder
import app.lumen.camera.ui.components.ViewfinderOverlays
import app.lumen.camera.ui.components.ZoomBar
import app.lumen.camera.ui.components.overlayStyle
import app.lumen.camera.ui.theme.LumenColors
import kotlin.math.roundToInt

private val MODES = listOf(Mode.ASTRO, Mode.PORTRAIT, Mode.PHOTO, Mode.VIDEO, Mode.PRO, Mode.PRO_VIDEO)

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val st by vm.settings.collectAsStateWithLifecycle()
    val processing by vm.processing.collectAsStateWithLifecycle()
    val devDeg by vm.orientation.degrees.collectAsStateWithLifecycle()
    // icons turn with the phone; read in the draw phase only (no recomposition per frame)
    val iconRot = animateFloatAsState(-(if (devDeg == 270) -90 else devDeg).toFloat(), tween(250), label = "iconRot")
    val rotation = { iconRot.value }
    val ctx = LocalContext.current
    val caps = ui.current
    val aspect = vm.aspect()

    BackHandler(ui.sheet != Sheet.NONE || ui.showAspectPicker) {
        if (ui.showAspectPicker) vm.toggleAspectPicker() else vm.showSheet(Sheet.NONE)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        // viewfinder size and placement (portrait height / width)
        val ratio = if (ui.mode.isVideo) {
            ui.selectedVideo?.let { it.size.width.toFloat() / it.size.height } ?: (16f / 9f)
        } else vm.displayRatio(aspect)
        val pvH = (maxWidth * ratio).coerceAtMost(maxHeight)
        val topBar = 52.dp
        val top = when {
            statusTop + topBar + pvH + 170.dp <= maxHeight -> statusTop + topBar        // 3:4, 1:1: below the top bar
            pvH >= maxHeight - 8.dp -> 0.dp                                            // full screen
            else -> ((maxHeight - pvH) / 2).coerceAtMost(statusTop)                      // 9:16
        }

        Box(Modifier.offset(y = top).fillMaxWidth().height(pvH)) {
            Viewfinder(
                previewSize = ui.previewSize,
                modifier = Modifier.fillMaxSize(),
                onSurface = vm::onSurface,
                onTap = vm::tapFocus,
                onPinch = vm::pinchZoom,
                onSwipe = { dir ->
                    val i = MODES.indexOf(ui.mode)
                    MODES.getOrNull(i + dir)?.let(vm::setMode)
                },
                onAnalysisFrame = vm::onAnalysisFrame,
                analysisEnabled = st.histogram || st.focusPeaking || st.zebras || st.falseColor,
            )
            Overlays(vm, st, ui, devDeg)
            ui.astroPreview?.let {
                Image(it.asImageBitmap(), "Stacking preview", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            ShutterBlink(ui.shutterTick)
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(
                flash = ui.controls.flash,
                timer = st.timerSeconds,
                aspect = aspect,
                resolutionLabel = caps?.takeIf { it.ultraHighRes && !ui.mode.isVideo }?.let { resolutionLabel(it, st.maxResolution) },
                rawOn = if (caps?.supportsRaw == true && !ui.mode.isVideo) st.saveRaw else null,
                showNight = ui.mode == Mode.PHOTO,
                nightOn = ui.night,
                nightSuggested = ui.nightSuggested,
                rotation = rotation,
                onSettings = { vm.showSheet(Sheet.SETTINGS) },
                onFlash = vm::cycleFlash,
                onTimer = { vm.updateSettings { it.copy(timerSeconds = when (it.timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }) } },
                onAspect = { if (!ui.mode.isVideo) vm.toggleAspectPicker() else vm.showSheet(Sheet.SETTINGS) },
                onResolution = { vm.updateSettings { it.copy(maxResolution = !it.maxResolution) } },
                onRaw = { vm.updateSettings { it.copy(saveRaw = !it.saveRaw) } },
                onNight = vm::toggleNight,
            )
            AnimatedVisibility(ui.showAspectPicker, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                AspectPicker(aspect, vm::setAspect)
            }
            StatusLine(vm, ui)
            Spacer(Modifier.weight(1f))
            Messages(ui)
            if (ui.mode.isPro && caps != null) ProPanel(vm, ui.controls, caps, ui.proParam,
                angleFps = if (ui.mode == Mode.PRO_VIDEO && st.shutterAngle) ui.selectedVideo?.fps else null)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
                ZoomBar(ui.zoomStops, ui.zoomFactor, rotation) { vm.selectZoom(it) }
            }
            ModeCarousel(MODES, ui.mode, vm::setMode) { vm.showSheet(Sheet.MORE) }
            ShutterRow(
                mode = ui.mode, recording = ui.recording, busy = ui.busy, progress = ui.progress,
                countdown = ui.countdown, processing = processing, thumbnail = ui.thumbnail, rotation = rotation,
                onShutter = vm::shutter,
                onGallery = {
                    ui.lastUri?.let { uri ->
                        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try { ctx.startActivity(i) } catch (_: Exception) {}
                    }
                },
                onFlip = vm::flipCamera,
            )
        }
        Sheets(vm, ui, st)
        if (ui.sheet == Sheet.SETTINGS) SettingsScreen(vm, ui, st) { vm.showSheet(Sheet.NONE) }
    }
}

/** Overlays that update often collect their own state, so the rest of the screen stays still. */
@Composable
private fun Overlays(vm: CameraViewModel, st: app.lumen.camera.processing.AppSettings, ui: UiState, devDeg: Int) {
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val roll = vm.orientation.roll.collectAsStateWithLifecycle()
    ViewfinderOverlays(
        st.grid, st.level, { roll.value }, st.histogram, analysis, Modifier.fillMaxSize(),
        frameGuide = if (ui.mode.isVideo || ui.mode.isPro) st.frameGuide else 0f,
        safeArea = if (ui.mode.isVideo) st.safeArea else 0,
        landscape = devDeg == 90 || devDeg == 270,
    )
    if (ui.recording) {
        val rec by vm.record.collectAsStateWithLifecycle()
        RecordOverlay(rec.timecode, rec.audioDb, Modifier.fillMaxSize())
    }
}

/** Short dark blink on the viewfinder when the shutter fires (the shot is already taken). */
@Composable
private fun ShutterBlink(tick: Int) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        a.snapTo(0.7f)
        a.animateTo(0f, tween(140))
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }.background(Color.Black))
}

@Composable
private fun StatusLine(vm: CameraViewModel, ui: UiState) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (ui.mode.isPro || ui.mode == Mode.ASTRO) {
            val live by vm.live.collectAsStateWithLifecycle()
            InfoText("ISO ${live.iso}  ·  ${CameraCaps.formatShutter(live.exposureNs.coerceAtLeast(1))}  ·  ƒ${"%.1f".format(live.aperture)}  ·  ${live.kelvin}K", Modifier)
        }
        if (ui.recording) {
            val rec by vm.record.collectAsStateWithLifecycle()
            InfoText("●  %02d:%02d".format(rec.seconds / 60, rec.seconds % 60), Modifier.padding(top = 4.dp), LumenColors.Record)
        }
        if (ui.night) InfoText("Night", Modifier.padding(top = 4.dp), LumenColors.Accent)
        if (ui.extension != null) InfoText("Device ${CameraCaps.extensionName(ui.extension)}", Modifier.padding(top = 4.dp))
        if (ui.thermalWarning) InfoText("Phone is warm — performance may be limited", Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Messages(ui: UiState) {
    val text = if (ui.busy) ui.busyText else ui.message
    if (text.isNullOrEmpty()) return
    Text(text, style = overlayStyle(14.sp, FontWeight.SemiBold), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

private fun resolutionLabel(c: CameraCaps, max: Boolean): String {
    val mp = if (max && c.ultraHighRes) c.maxResMegapixels else c.megapixels
    return "${mp.roundToInt()}M"
}

// ---- Pro panel --------------------------------------------------------------------------------
private val ISO_STOPS = listOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
    2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 51200)
private val SHUTTER_STOPS: List<Long> = listOf(24000, 16000, 12000, 8000, 6000, 4000, 3000, 2000, 1500, 1000, 750, 500, 350,
    250, 180, 125, 90, 60, 45, 30, 20, 15, 10, 8, 6, 4).map { 1_000_000_000L / it } +
    listOf(0.3, 0.5, 0.8, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 15.0, 20.0, 30.0).map { (it * 1e9).toLong() }

@Composable
private fun ProPanel(vm: CameraViewModel, c: Controls, caps: CameraCaps, sel: ProParam?, angleFps: Int? = null) {
    val isoList = caps.isoRange?.let { r -> ISO_STOPS.filter { it in r.lower..r.upper } } ?: emptyList()
    // Video: shutter angle (cinema convention); 180° = 1/(2·fps). Never longer than one frame.
    val angles = listOf(11.25, 22.5, 45.0, 90.0, 144.0, 172.8, 180.0, 216.0, 270.0, 360.0)
    val shList = if (angleFps != null) {
        angles.map { (it / 360.0 / angleFps * 1e9).toLong() }.filter { ns -> caps.exposureRange?.let { ns in it.lower..it.upper } ?: false }
    } else caps.exposureRange?.let { r -> SHUTTER_STOPS.filter { it in r.lower..r.upper } } ?: emptyList()
    fun shutterLabel(ns: Long) = if (angleFps != null) {
        val a = ns / 1e9 * angleFps * 360.0
        if (kotlin.math.abs(a - a.roundToInt()) < 0.05) "${a.roundToInt()}°" else "%.1f°".format(a)
    } else CameraCaps.formatShutter(ns)
    val step = caps.aeCompStep.toFloat()
    val evList = (caps.aeCompRange.lower..caps.aeCompRange.upper).toList()
    val focusSteps = 48
    val kList = caps.cctRange?.let { r -> (r.lower..r.upper step 100).toList() } ?: (2000..10000 step 100).toList()
    fun focusLabel(d: Float) = if (d <= 0.001f) "∞" else { val m = 1f / d; if (m >= 1f) "%.1fm".format(m) else "${(m * 100).roundToInt()}cm" }
    val values = listOf(
        ProValue(ProParam.ISO.label, c.iso?.toString() ?: "A", c.iso == null),
        ProValue(ProParam.SHUTTER.label, c.shutterNs?.let { shutterLabel(it) } ?: "A", c.shutterNs == null),
        ProValue(ProParam.EV.label, "%+.1f".format(c.evSteps * step), c.evSteps == 0),
        ProValue(ProParam.FOCUS.label, c.focusDiopters?.let { focusLabel(it) } ?: "AF", c.focusDiopters == null),
        ProValue(ProParam.WB.label, c.wbKelvin?.let { "${it}K" } ?: "AWB", c.wbKelvin == null),
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        when (sel) {
            ProParam.ISO -> if (isoList.isNotEmpty() && caps.manualSensor) {
                val labels = listOf("A") + isoList.map { it.toString() }
                val idx = c.iso?.let { v -> 1 + isoList.indexOfFirst { it >= v }.coerceAtLeast(0) } ?: 0
                RulerDial(labels, idx) { i -> vm.setControls(c.copy(iso = if (i == 0) null else isoList[i - 1])) }
            } else Hint("Manual ISO is not offered by this camera")
            ProParam.SHUTTER -> if (shList.isNotEmpty() && caps.manualSensor) {
                val labels = listOf("A") + shList.map { shutterLabel(it) }
                val idx = c.shutterNs?.let { v -> 1 + shList.indexOfFirst { it >= v }.coerceAtLeast(0) } ?: 0
                RulerDial(labels, idx) { i -> vm.setControls(c.copy(shutterNs = if (i == 0) null else shList[i - 1])) }
            } else Hint("Manual shutter is not offered by this camera")
            ProParam.EV -> RulerDial(evList.map { "%+.1f".format(it * step) }, evList.indexOf(c.evSteps).coerceAtLeast(0)) { i ->
                vm.setControls(c.copy(evSteps = evList[i]))
            }
            ProParam.FOCUS -> if (caps.minFocusDistance > 0f) {
                val labels = listOf("AF") + (0..focusSteps).map { focusLabel(caps.minFocusDistance * it / focusSteps) }
                val idx = c.focusDiopters?.let { 1 + (it / caps.minFocusDistance * focusSteps).roundToInt() } ?: 0
                RulerDial(labels, idx) { i ->
                    vm.setControls(c.copy(focusDiopters = if (i == 0) null else caps.minFocusDistance * (i - 1) / focusSteps))
                }
            } else Hint("Fixed-focus lens")
            ProParam.WB -> {
                val labels = listOf("AWB") + kList.map { "${it}K" }
                val idx = c.wbKelvin?.let { v -> 1 + kList.indexOfFirst { it >= v }.coerceAtLeast(0) } ?: 0
                RulerDial(labels, idx) { i -> vm.setControls(c.copy(wbKelvin = if (i == 0) null else kList[i - 1])) }
                if (c.wbKelvin != null) {
                    val tints = (-50..50 step 5).toList()
                    RulerDial(tints.map { "Tint %+d".format(it) }, tints.indexOfFirst { it >= c.wbTint }.coerceAtLeast(0)) { i ->
                        vm.setControls(c.copy(wbTint = tints[i]))
                    }
                }
            }
            null -> {}
        }
        ProChips(
            values, sel?.label,
            onSelect = { lbl -> vm.selectProParam(ProParam.entries.first { it.label == lbl }) },
            onReset = { lbl ->
                vm.setControls(when (lbl) {
                    ProParam.ISO.label -> c.copy(iso = null)
                    ProParam.SHUTTER.label -> c.copy(shutterNs = null)
                    ProParam.EV.label -> c.copy(evSteps = 0)
                    ProParam.FOCUS.label -> c.copy(focusDiopters = null)
                    else -> c.copy(wbKelvin = null, wbTint = 0)
                })
            },
            onPresets = { vm.showSheet(Sheet.PRESETS) },
        )
    }
}

@Composable
private fun Hint(t: String) = Text(t, style = overlayStyle(12.sp, FontWeight.Medium, Color.White.copy(alpha = 0.85f)),
    modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
