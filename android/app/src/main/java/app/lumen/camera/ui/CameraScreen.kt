package app.lumen.camera.ui

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.camera.camera.CameraCaps
import app.lumen.camera.camera.Controls
import app.lumen.camera.processing.PhotoEngine
import app.lumen.camera.ui.components.InfoText
import app.lumen.camera.ui.components.ModeCarousel
import app.lumen.camera.ui.components.ProChips
import app.lumen.camera.ui.components.ProValue
import app.lumen.camera.ui.components.RulerDial
import app.lumen.camera.ui.components.ShutterRow
import app.lumen.camera.ui.components.TopBar
import app.lumen.camera.ui.components.Viewfinder
import app.lumen.camera.ui.components.ViewfinderOverlays
import app.lumen.camera.ui.components.ZoomBar
import app.lumen.camera.ui.theme.LumenColors
import kotlin.math.roundToInt

private val MODES = listOf(Mode.ASTRO, Mode.PORTRAIT, Mode.PHOTO, Mode.VIDEO, Mode.PRO, Mode.PRO_VIDEO)

@Composable
fun CameraScreen(vm: CameraViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val st by vm.settings.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val devDeg by vm.orientation.degrees.collectAsStateWithLifecycle()
    val roll by vm.orientation.roll.collectAsStateWithLifecycle()
    val iconRot by animateFloatAsState(-(if (devDeg == 270) -90 else devDeg).toFloat(), label = "iconRot")
    val ctx = LocalContext.current
    val caps = ui.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(
                flash = ui.controls.flash,
                timer = st.timerSeconds,
                aspectLabel = aspectLabel(st.aspect),
                resolutionLabel = resolutionLabel(caps, st.maxResolution),
                rawOn = st.saveRaw,
                aiOn = st.aiDenoise || st.aiZoom,
                showNight = ui.mode == Mode.PHOTO,
                nightOn = ui.night,
                nightSuggested = ui.nightSuggested,
                iconRotation = iconRot,
                onSettings = { vm.showSheet(Sheet.SETTINGS) },
                onFlash = vm::cycleFlash,
                onTimer = { vm.updateSettings { it.copy(timerSeconds = when (it.timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }) } },
                onAspect = { vm.updateSettings { it.copy(aspect = nextAspect(it.aspect)) } },
                onResolution = { if (caps?.ultraHighRes == true) vm.updateSettings { it.copy(maxResolution = !it.maxResolution) } },
                onRaw = { if (caps?.supportsRaw == true) vm.updateSettings { it.copy(saveRaw = !it.saveRaw) } },
                onAi = { vm.updateSettings { val on = !(it.aiDenoise || it.aiZoom); it.copy(aiDenoise = on, aiZoom = on) } },
                onNight = vm::toggleNight,
            )
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val aspect = ui.previewSize.height.toFloat() / ui.previewSize.width
                Box(Modifier.fillMaxWidth().aspectRatio(aspect)) {
                    Viewfinder(
                        previewSize = ui.previewSize,
                        modifier = Modifier.fillMaxSize(),
                        onSurface = vm::onSurface,
                        onTap = vm::tapFocus,
                        onPinch = vm::pinchZoom,
                        onAnalysisFrame = vm::onAnalysisFrame,
                        analysisEnabled = st.histogram || st.focusPeaking || st.zebras,
                    )
                    ViewfinderOverlays(st.grid, st.level, roll, st.histogram, ui.analysis, Modifier.fillMaxSize())
                    ui.astroPreview?.let {
                        Image(it.asImageBitmap(), "Stacking preview", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (ui.mode.isPro || ui.mode == Mode.ASTRO) {
                            InfoText(
                                "ISO ${live.iso} · ${CameraCaps.formatShutter(live.exposureNs.coerceAtLeast(1))} · ƒ${"%.1f".format(live.aperture)} · ${live.kelvin}K",
                                Modifier,
                            )
                        }
                        if (ui.recording) {
                            Spacer(Modifier.height(6.dp))
                            InfoText("● REC  %02d:%02d".format(ui.recordSeconds / 60, ui.recordSeconds % 60), Modifier)
                        }
                        if (ui.night) {
                            Spacer(Modifier.height(6.dp))
                            InfoText("Night · hold steady", Modifier)
                        }
                        if (ui.extension != null) {
                            Spacer(Modifier.height(6.dp))
                            InfoText("Device ${CameraCaps.extensionName(ui.extension!!)}", Modifier)
                        }
                        if (ui.thermalWarning) {
                            Spacer(Modifier.height(6.dp))
                            InfoText("Phone is warm — performance may be limited", Modifier)
                        }
                    }
                    if (ui.busy || ui.message != null) {
                        Column(
                            Modifier.align(Alignment.BottomCenter).padding(12.dp)
                                .background(Color(0xAA000000), RoundedCornerShape(10.dp)).padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(if (ui.busy) ui.busyText else ui.message ?: "", color = Color.White, fontSize = 13.sp)
                            if (ui.busy && ui.progress > 0f) {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.height(3.dp), color = LumenColors.Accent)
                            }
                        }
                    }
                }
            }
            if (ui.mode.isPro && caps != null) ProPanel(vm, ui.controls, caps, ui.proParam, live.kelvin)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
                ZoomBar(ui.zoomStops, ui.zoomFactor, iconRot) { vm.selectZoom(it) }
            }
            ModeCarousel(MODES, ui.mode, vm::setMode) { vm.showSheet(Sheet.MORE) }
            ShutterRow(
                mode = ui.mode, recording = ui.recording, busy = ui.busy, progress = ui.progress,
                countdown = ui.countdown, thumbnail = ui.thumbnail, rotation = iconRot,
                onShutter = vm::shutter,
                onGallery = {
                    ui.lastUri?.let { uri ->
                        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try { ctx.startActivity(i) } catch (_: Exception) {}
                    }
                },
                onFlip = vm::flipCamera,
            )
            if (st.photoEngine == PhotoEngine.LUMEN && ui.mode == Mode.PHOTO && caps?.supportsRaw == false) {
                Text("This camera has no RAW stream: using device processing", color = LumenColors.TextDim, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        Sheets(vm, ui, st)
    }
}

private fun aspectLabel(a: Float) = when {
    kotlin.math.abs(a - 4f / 3f) < 0.02f -> "3:4"
    kotlin.math.abs(a - 16f / 9f) < 0.02f -> "9:16"
    kotlin.math.abs(a - 1f) < 0.02f -> "1:1"
    else -> "Full"
}

private fun nextAspect(a: Float) = when {
    kotlin.math.abs(a - 4f / 3f) < 0.02f -> 16f / 9f
    kotlin.math.abs(a - 16f / 9f) < 0.02f -> 1f
    else -> 4f / 3f
}

private fun resolutionLabel(c: CameraCaps?, max: Boolean): String {
    c ?: return "--"
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
private fun ProPanel(vm: CameraViewModel, c: Controls, caps: CameraCaps, sel: ProParam?, liveK: Int) {
    val isoList = caps.isoRange?.let { r -> ISO_STOPS.filter { it in r.lower..r.upper } } ?: emptyList()
    val shList = caps.exposureRange?.let { r -> SHUTTER_STOPS.filter { it in r.lower..r.upper } } ?: emptyList()
    val step = caps.aeCompStep.toFloat()
    val evList = (caps.aeCompRange.lower..caps.aeCompRange.upper).toList()
    val focusSteps = 48
    val kList = caps.cctRange?.let { r -> (r.lower..r.upper step 100).toList() } ?: (2000..10000 step 100).toList()
    fun focusLabel(d: Float) = if (d <= 0.001f) "∞" else { val m = 1f / d; if (m >= 1f) "%.1fm".format(m) else "${(m * 100).roundToInt()}cm" }
    val values = listOf(
        ProValue(ProParam.ISO.label, c.iso?.toString() ?: "A", c.iso == null),
        ProValue(ProParam.SHUTTER.label, c.shutterNs?.let { CameraCaps.formatShutter(it) } ?: "A", c.shutterNs == null),
        ProValue(ProParam.EV.label, "%+.1f".format(c.evSteps * step), c.evSteps == 0),
        ProValue(ProParam.FOCUS.label, c.focusDiopters?.let { focusLabel(it) } ?: "AF", c.focusDiopters == null),
        ProValue(ProParam.WB.label, c.wbKelvin?.let { "${it}K" } ?: "AWB", c.wbKelvin == null),
    )
    Column(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(vertical = 4.dp)) {
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
        when (sel) {
            ProParam.ISO -> if (isoList.isNotEmpty() && caps.manualSensor) {
                val labels = listOf("A") + isoList.map { it.toString() }
                val idx = c.iso?.let { v -> 1 + isoList.indexOfFirst { it >= v }.coerceAtLeast(0) } ?: 0
                RulerDial(labels, idx) { i -> vm.setControls(c.copy(iso = if (i == 0) null else isoList[i - 1])) }
            } else Text("Manual ISO not supported on this camera", color = LumenColors.TextDim, modifier = Modifier.padding(8.dp))
            ProParam.SHUTTER -> if (shList.isNotEmpty() && caps.manualSensor) {
                val labels = listOf("A") + shList.map { CameraCaps.formatShutter(it) }
                val idx = c.shutterNs?.let { v -> 1 + shList.indexOfFirst { it >= v }.coerceAtLeast(0) } ?: 0
                RulerDial(labels, idx) { i -> vm.setControls(c.copy(shutterNs = if (i == 0) null else shList[i - 1])) }
            } else Text("Manual shutter not supported on this camera", color = LumenColors.TextDim, modifier = Modifier.padding(8.dp))
            ProParam.EV -> RulerDial(evList.map { "%+.1f".format(it * step) }, evList.indexOf(c.evSteps).coerceAtLeast(0)) { i ->
                vm.setControls(c.copy(evSteps = evList[i]))
            }
            ProParam.FOCUS -> if (caps.minFocusDistance > 0f) {
                val labels = listOf("AF") + (0..focusSteps).map { focusLabel(caps.minFocusDistance * it / focusSteps) }
                val idx = c.focusDiopters?.let { 1 + (it / caps.minFocusDistance * focusSteps).roundToInt() } ?: 0
                RulerDial(labels, idx) { i ->
                    vm.setControls(c.copy(focusDiopters = if (i == 0) null else caps.minFocusDistance * (i - 1) / focusSteps))
                }
            } else Text("Fixed-focus lens", color = LumenColors.TextDim, modifier = Modifier.padding(8.dp))
            ProParam.WB -> {
                val labels = listOf("AWB") + kList.map { "${it}K" }
                val idx = c.wbKelvin?.let { v -> 1 + kList.indexOfFirst { it >= v }.coerceAtLeast(0) } ?: 0
                RulerDial(labels, idx) { i -> vm.setControls(c.copy(wbKelvin = if (i == 0) null else kList[i - 1])) }
                if (c.wbKelvin != null) {
                    val tints = (-50..50 step 5).toList()
                    RulerDial(tints.map { "Tint %+d".format(it) }, tints.indexOfFirst { it >= c.wbTint }.coerceAtLeast(0)) { i ->
                        vm.setControls(c.copy(wbTint = tints[i]))
                    }
                } else if (liveK > 0) {
                    Text("Auto ≈ ${liveK}K", color = LumenColors.TextDim, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
            null -> {}
        }
    }
}

@Composable
private fun BoldLabel(t: String) = Text(t, color = Color.White, fontWeight = FontWeight.Bold)
