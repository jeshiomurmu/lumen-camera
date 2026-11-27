package app.lumen.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.camera.camera.CameraCaps
import app.lumen.camera.camera.EncoderCatalog
import app.lumen.camera.camera.VideoCodec
import app.lumen.camera.core.NativeCore
import app.lumen.camera.processing.AppSettings
import app.lumen.camera.processing.PhotoEngine
import app.lumen.camera.ui.theme.LumenColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheets(vm: CameraViewModel, ui: UiState, st: AppSettings) {
    if (ui.sheet == Sheet.NONE) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { vm.showSheet(Sheet.NONE) },
        sheetState = state,
        containerColor = LumenColors.Surface,
    ) {
        when (ui.sheet) {
            Sheet.SETTINGS -> SettingsContent(vm, ui, st)
            Sheet.HARDWARE -> HardwareContent(vm)
            Sheet.MORE -> MoreContent(vm, ui)
            Sheet.PRESETS -> PresetsContent(vm, ui)
            Sheet.NONE -> {}
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = LumenColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else LumenColors.TextDim, fontSize = 15.sp)
            sub?.let { Text(it, color = LumenColors.TextDim, fontSize = 12.sp) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> OptionRow(title: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (label, v) ->
                val sel = v == selected
                Text(
                    label,
                    color = if (sel) Color.Black else Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (sel) LumenColors.Accent else LumenColors.SurfaceHigh)
                        .clickable { onSelect(v) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(vm: CameraViewModel, ui: UiState, st: AppSettings) {
    val caps = ui.current
    val encoders = remember { EncoderCatalog.encoders() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Section("Photo")
        OptionRow("Processing", PhotoEngine.entries.map { it.label to it }, st.photoEngine) { v -> vm.updateSettings { it.copy(photoEngine = v) } }
        OptionRow("Color profile", listOf("Natural" to NativeCore.PROFILE_NATURAL, "Vivid" to NativeCore.PROFILE_VIVID,
            "Film" to NativeCore.PROFILE_FILM, "Mono" to NativeCore.PROFILE_MONO), st.profile) { v -> vm.updateSettings { it.copy(profile = v) } }
        SwitchRow("Save RAW (DNG)", "Merged bursts give a cleaner, higher bit-depth DNG", st.saveRaw, caps?.supportsRaw == true) { v ->
            vm.updateSettings { it.copy(saveRaw = v) }
        }
        SwitchRow(
            "Maximum resolution",
            caps?.let { if (it.ultraHighRes) "Full sensor: %.0f MP".format(it.maxResMegapixels) else "Not offered by this camera" },
            st.maxResolution, caps?.ultraHighRes == true,
        ) { v -> vm.updateSettings { it.copy(maxResolution = v) } }
        OptionRow("Burst frames", listOf("Auto" to 0, "4" to 4, "6" to 6, "8" to 8, "10" to 10, "12" to 12, "15" to 15), st.burstFrames) { v ->
            vm.updateSettings { it.copy(burstFrames = v) }
        }
        SwitchRow("AI denoise", "FFDNet (open source), noise-level aware", st.aiDenoise) { v -> vm.updateSettings { it.copy(aiDenoise = v) } }
        SwitchRow("AI zoom", "Real-ESRGAN x4 for digital zoom beyond the lenses", st.aiZoom) { v -> vm.updateSettings { it.copy(aiZoom = v) } }
        SwitchRow("Display P3 colors", null, st.displayP3) { v -> vm.updateSettings { it.copy(displayP3 = v) } }
        OptionRow("JPEG quality", listOf("90" to 90, "95" to 95, "97" to 97, "100" to 100), st.jpegQuality) { v -> vm.updateSettings { it.copy(jpegQuality = v) } }
        OptionRow("Device noise reduction (ISP)", listOf("Default" to -1, "Off" to 0, "Fast" to 1, "High quality" to 2, "Minimal" to 3), st.ispNoiseReduction) { v ->
            vm.updateSettings { it.copy(ispNoiseReduction = v) }
        }
        OptionRow("Device sharpening (ISP)", listOf("Default" to -1, "Off" to 0, "Fast" to 1, "High quality" to 2), st.ispSharpening) { v ->
            vm.updateSettings { it.copy(ispSharpening = v) }
        }

        Section("Video")
        val modes = ui.videoModes
        if (modes.isNotEmpty()) {
            OptionRow("Resolution & frame rate", modes.map { it.label to vm.keyOf(it) }, ui.selectedVideo?.let { vm.keyOf(it) } ?: "") { k ->
                vm.updateSettings { it.copy(videoModeKey = k) }
            }
        }
        val codecs = VideoCodec.entries.filter { c -> encoders.any { it.codec == c } }
        OptionRow("Codec", codecs.map { it.label to it }, st.videoCodec) { v -> vm.updateSettings { it.copy(videoCodec = v) } }
        val maxBr = encoders.filter { it.codec == st.videoCodec }.maxOfOrNull { it.maxBitrate } ?: 0
        OptionRow(
            "Bitrate (encoder max ${maxBr / 1_000_000} Mb/s)",
            listOf("Auto" to 0) + listOf(20, 50, 100, 150, 200, 300, 400, 600, 800).filter { it * 1_000_000 <= maxBr }.map { "$it" to it },
            st.bitrateMbps,
        ) { v -> vm.updateSettings { it.copy(bitrateMbps = v) } }
        SwitchRow("10-bit HDR (HLG)", if (caps?.supportsHlg == true) "Rec.2020 HLG" else "Not offered by this camera", st.tenBitHlg, caps?.supportsHlg == true) { v ->
            vm.updateSettings { it.copy(tenBitHlg = v) }
        }
        SwitchRow("Lumen Log (Pro Video)", "Flat log curve + Rec.709 LUT for grading", st.logProfile) { v -> vm.updateSettings { it.copy(logProfile = v) } }
        SwitchRow("Constant bitrate", null, st.cbr) { v -> vm.updateSettings { it.copy(cbr = v) } }
        SwitchRow("Video stabilization", null, st.stabilization) { v -> vm.updateSettings { it.copy(stabilization = v) } }
        SwitchRow("Record audio", null, st.recordAudio) { v -> vm.updateSettings { it.copy(recordAudio = v) } }
        OptionRow("Audio input gain", listOf(-12, -6, 0, 6, 12, 18).map { (if (it > 0) "+$it dB" else "$it dB") to it }, st.audioGainDb) { v ->
            vm.updateSettings { it.copy(audioGainDb = v) }
        }
        SwitchRow("Audio meters", "Peak levels while recording", st.audioMeters) { v -> vm.updateSettings { it.copy(audioMeters = v) } }

        Section("Cinema")
        SwitchRow("Shutter angle (Pro Video)", "180° = 1/(2 × frame rate)", st.shutterAngle) { v -> vm.updateSettings { it.copy(shutterAngle = v) } }
        OptionRow("Flicker-free", listOf("Auto" to 0, "50 Hz" to 50, "60 Hz" to 60), st.antibanding) { v -> vm.updateSettings { it.copy(antibanding = v) } }
        OptionRow(
            "Frame guide",
            listOf("Off" to 0f, "9:16" to 9f / 16f, "4:5" to 0.8f, "1:1" to 1f, "4:3" to 4f / 3f, "14:9" to 14f / 9f, "1.85" to 1.85f,
                "2:1" to 2f, "2.35" to 2.35f, "2.39" to 2.39f, "2.4" to 2.4f, "2.76" to 2.76f),
            st.frameGuide,
        ) { v -> vm.updateSettings { it.copy(frameGuide = v) } }
        OptionRow("Safe area", listOf("Off" to 0, "80%" to 80, "90%" to 90, "93%" to 93), st.safeArea) { v -> vm.updateSettings { it.copy(safeArea = v) } }
        SwitchRow("False color", "Purple crushed · green 18% grey · pink skin · red clipped", st.falseColor) { v ->
            vm.updateSettings { it.copy(falseColor = v) }
        }
        OptionRow("Timecode", listOf("Record run" to false, "Time of day" to true), st.timecodeTod) { v -> vm.updateSettings { it.copy(timecodeTod = v) } }
        SwitchRow("Lock white balance on record", null, st.lockWbOnRecord) { v -> vm.updateSettings { it.copy(lockWbOnRecord = v) } }

        Section("Astro (tripod)")
        OptionRow("Frames", listOf(10, 20, 30, 60, 100, 200).map { "$it" to it }, st.astroFrames) { v -> vm.updateSettings { it.copy(astroFrames = v) } }
        val maxExpS = (caps?.exposureRange?.upper ?: 1_000_000_000L) / 1e9f
        OptionRow("Exposure per frame (max %.1f s)".format(maxExpS),
            listOf(0.5f, 1f, 2f, 4f, 8f, 10f, 15f, 20f, 30f).filter { it <= maxExpS + 0.01f }.map { "${it}s" to it }, st.astroExposureS) { v ->
            vm.updateSettings { it.copy(astroExposureS = v) }
        }
        OptionRow("ISO", listOf(400, 800, 1600, 3200, 6400).map { "$it" to it }, st.astroIso) { v -> vm.updateSettings { it.copy(astroIso = v) } }
        OptionRow("Stacking", listOf("Sigma clip" to NativeCore.STACK_SIGMA_CLIP, "Median" to NativeCore.STACK_MEDIAN,
            "Mean" to NativeCore.STACK_MEAN, "Star trails" to NativeCore.STACK_MAXIMUM), st.astroStackMode) { v -> vm.updateSettings { it.copy(astroStackMode = v) } }
        SwitchRow("Align stars", "Compensates Earth's rotation", st.astroAlign) { v -> vm.updateSettings { it.copy(astroAlign = v) } }
        OptionRow("Dark frames (cover the lens first)", listOf("0" to 0, "5" to 5, "10" to 10), st.astroDarkFrames) { v ->
            vm.updateSettings { it.copy(astroDarkFrames = v) }
        }

        Section("Viewfinder")
        SwitchRow("Grid", null, st.grid) { v -> vm.updateSettings { it.copy(grid = v) } }
        SwitchRow("Histogram", null, st.histogram) { v -> vm.updateSettings { it.copy(histogram = v) } }
        SwitchRow("Focus peaking", null, st.focusPeaking) { v -> vm.updateSettings { it.copy(focusPeaking = v) } }
        SwitchRow("Zebras (clipping)", null, st.zebras) { v -> vm.updateSettings { it.copy(zebras = v) } }
        SwitchRow("Level", null, st.level) { v -> vm.updateSettings { it.copy(level = v) } }
        SwitchRow("Volume keys take photos", null, st.volumeShutter) { v -> vm.updateSettings { it.copy(volumeShutter = v) } }

        Section("Tools")
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = vm::exportLut) { Text("Export Log LUT") }
            Button(onClick = { vm.showSheet(Sheet.HARDWARE) }) { Text("Hardware report") }
        }
    }
}

@Composable
private fun HardwareContent(vm: CameraViewModel) {
    val rows = remember { vm.hardwareReport() }
    LazyColumn(Modifier.fillMaxWidth().height(620.dp).padding(horizontal = 16.dp)) {
        items(rows) { (k, v) ->
            if (k.isEmpty() && v.isEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = LumenColors.SurfaceHigh)
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(k, color = LumenColors.TextDim, fontSize = 12.sp, modifier = Modifier.weight(0.4f))
                    Text(v, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(0.6f))
                }
            }
        }
    }
}

@Composable
private fun MoreContent(vm: CameraViewModel, ui: UiState) {
    Column(Modifier.padding(bottom = 32.dp)) {
        Section("Device processing modes")
        val ext = ui.current?.extensions ?: emptyList()
        if (ext.isEmpty()) {
            Text("This camera does not offer Camera2 extensions.", color = LumenColors.TextDim, modifier = Modifier.padding(horizontal = 20.dp))
        }
        ext.forEach { e ->
            Text(
                CameraCaps.extensionName(e) + if (ui.extension == e) "  ✓" else "",
                color = Color.White, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clickable { vm.setExtension(if (ui.extension == e) null else e) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        Section("Lenses (all detected cameras)")
        ui.cameras.forEach { c ->
            Text(
                c.label + if (c.id == ui.current?.id) "  ✓" else "",
                color = if (c.id == ui.current?.id) LumenColors.Accent else Color.White, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { vm.selectCamera(c); vm.showSheet(Sheet.NONE) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        Section("More")
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.showSheet(Sheet.HARDWARE) }) { Text("Hardware") }
            Button(onClick = { vm.showSheet(Sheet.SETTINGS) }) { Text("Settings") }
        }
    }
}

@Composable
private fun PresetsContent(vm: CameraViewModel, ui: UiState) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.padding(bottom = 32.dp)) {
        Section("Pro presets")
        if (ui.presets.isEmpty()) Text("No presets yet", color = LumenColors.TextDim, modifier = Modifier.padding(horizontal = 20.dp))
        ui.presets.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { vm.applyPreset(p) }) {
                    Text(p.name, color = Color.White, fontSize = 15.sp)
                    Text(
                        listOfNotNull(p.iso?.let { "ISO $it" }, p.shutterNs?.let { CameraCaps.formatShutter(it) },
                            p.wbKelvin?.let { "${it}K" }, if (p.evSteps != 0) "EV ${p.evSteps}" else null).joinToString(" · ").ifEmpty { "Auto" },
                        color = LumenColors.TextDim, fontSize = 12.sp,
                    )
                }
                TextButton(onClick = { vm.deletePreset(p) }) { Text("Delete") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.height(4.dp))
            Button(onClick = { if (name.isNotBlank()) { vm.savePreset(name.trim()); name = "" } }, modifier = Modifier.padding(start = 8.dp)) {
                Text("Save current")
            }
        }
    }
}
