package app.lumen.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.camera.camera.EncoderCatalog
import app.lumen.camera.camera.VideoCodec
import app.lumen.camera.camera.VideoMode
import app.lumen.camera.core.NativeCore
import app.lumen.camera.processing.AppSettings
import app.lumen.camera.processing.PhotoEngine
import app.lumen.camera.ui.theme.LumenColors

private val Page = Color(0xFF0E0E10)
private val Card = Color(0xFF1B1B1E)
private val Dim = Color(0xFF9A9AA2)

private enum class Category(val title: String, val sub: String) {
    PHOTO("Photo", "Processing, colour, resolution, RAW"),
    VIDEO("Video", "Resolution, frame rate, codec, bitrate, audio"),
    CINEMA("Cinema tools", "Shutter angle, flicker-free, guides, false colour, timecode"),
    ASSIST("Viewfinder", "Grid, level, histogram, peaking, zebras"),
    ASTRO("Night sky", "Astro frames, exposure, stacking"),
    GENERAL("General", "Shutter keys, timer, tools, hardware report"),
}

/** Full-screen settings: a short list of categories, each a simple page of rows. */
@Composable
fun SettingsScreen(vm: CameraViewModel, ui: UiState, st: AppSettings, onClose: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val cat = page?.let { Category.valueOf(it) }
    androidx.activity.compose.BackHandler { if (cat != null) page = null else onClose() }
    Column(Modifier.fillMaxSize().background(Page).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (cat != null) page = null else onClose() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White)
            }
            Text(cat?.title ?: "Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        AnimatedContent(
            cat,
            transitionSpec = {
                val forward = targetState != null
                (slideInHorizontally(tween(220)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(220)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(160)))
            },
            label = "settingsPage",
        ) { c ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
                when (c) {
                    null -> Category.entries.forEach { e -> item(e.name) { CategoryRow(e) { page = e.name } } }
                    Category.PHOTO -> photo(vm, ui, st)
                    Category.VIDEO -> video(vm, ui, st)
                    Category.CINEMA -> cinema(vm, st)
                    Category.ASSIST -> assist(vm, st)
                    Category.ASTRO -> astro(vm, ui, st)
                    Category.GENERAL -> general(vm, st)
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(c: Category, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(Card)
            .clickable(onClick = onClick).semantics { contentDescription = "Settings ${c.title}" }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(c.sub, color = Dim, fontSize = 13.sp)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Dim)
    }
}

// ---- row building blocks ------------------------------------------------------------------------
private fun LazyListScope.header(t: String) = item("h_$t") {
    Text(t.uppercase(), color = LumenColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 6.dp))
}

private fun LazyListScope.toggle(key: String, title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    item(key) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }
                .semantics { contentDescription = "Setting $title" }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (enabled) Color.White else Dim, fontSize = 16.sp)
                sub?.let { Text(it, color = Dim, fontSize = 13.sp) }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = LumenColors.Accent, checkedThumbColor = Color.Black))
        }
    }

/** A row showing the current value; tap to open the choices right below it. */
private fun <T> LazyListScope.choice(
    key: String, title: String, options: List<Pair<String, T>>, selected: T, sub: String? = null, onSelect: (T) -> Unit,
) = item(key) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.second == selected }?.first ?: "—"
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.semantics { contentDescription = "Setting $title" }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 16.sp)
                sub?.let { Text(it, color = Dim, fontSize = 13.sp) }
            }
            Text(current, color = LumenColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        AnimatedVisibility(open, enter = expandVertically(tween(180)) + fadeIn(), exit = shrinkVertically(tween(150)) + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(Card)) {
                options.forEach { (label, v) ->
                    val sel = v == selected
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(v); open = false }
                            .semantics { contentDescription = "Option $label" }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = if (sel) LumenColors.Accent else Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (sel) Icon(Icons.Outlined.Check, null, tint = LumenColors.Accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

private fun LazyListScope.action(key: String, title: String, sub: String?, onClick: () -> Unit) = item(key) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = "Setting $title" }
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(title, color = Color.White, fontSize = 16.sp)
        sub?.let { Text(it, color = Dim, fontSize = 13.sp) }
    }
}

// ---- pages ------------------------------------------------------------------------------------------
private fun LazyListScope.photo(vm: CameraViewModel, ui: UiState, st: AppSettings) {
    val caps = ui.current
    val noRaw = caps?.supportsRaw == false
    header("Picture")
    choice("ratio", "Aspect ratio", Aspect.entries.map { it.label to it }, vm.aspect()) { vm.setAspect(it) }
    choice("engine", "Processing", PhotoEngine.entries.map { it.label to it }, st.photoEngine,
        if (noRaw) "This phone gives apps no RAW: photos use the phone's own processing, Night merges frames" else null,
    ) { v -> vm.updateSettings { it.copy(photoEngine = v) } }
    choice("profile", "Colour", listOf("Natural" to NativeCore.PROFILE_NATURAL, "Vivid" to NativeCore.PROFILE_VIVID,
        "Film" to NativeCore.PROFILE_FILM, "Mono" to NativeCore.PROFILE_MONO), st.profile, "Lumen-processed photos") { v ->
        vm.updateSettings { it.copy(profile = v) }
    }
    choice("q", "JPEG quality", listOf("90" to 90, "95" to 95, "97" to 97, "100" to 100), st.jpegQuality) { v -> vm.updateSettings { it.copy(jpegQuality = v) } }
    toggle("maxres", "Maximum resolution",
        caps?.let { if (it.ultraHighRes) "Full sensor: %.0f MP".format(it.maxResMegapixels) else "Not offered by this camera" },
        st.maxResolution, caps?.ultraHighRes == true) { v -> vm.updateSettings { it.copy(maxResolution = v) } }
    toggle("raw", "Save RAW (DNG)", if (noRaw) "Not offered by this camera" else null, st.saveRaw, caps?.supportsRaw == true) { v ->
        vm.updateSettings { it.copy(saveRaw = v) }
    }
    toggle("p3", "Display P3 colour", null, st.displayP3) { v -> vm.updateSettings { it.copy(displayP3 = v) } }
    header("Multi-frame")
    choice("frames", "Burst frames", listOf("Auto" to 0, "4" to 4, "6" to 6, "8" to 8, "10" to 10, "12" to 12, "15" to 15), st.burstFrames) { v ->
        vm.updateSettings { it.copy(burstFrames = v) }
    }
    header("Phone processing (ISP)")
    choice("nr", "Noise reduction", listOf("Phone default" to -1, "Off" to 0, "Fast" to 1, "High quality" to 2, "Minimal" to 3), st.ispNoiseReduction) { v ->
        vm.updateSettings { it.copy(ispNoiseReduction = v) }
    }
    choice("edge", "Sharpening", listOf("Phone default" to -1, "Off" to 0, "Fast" to 1, "High quality" to 2), st.ispSharpening) { v ->
        vm.updateSettings { it.copy(ispSharpening = v) }
    }
    header("AI (optional)")
    toggle("aid", "AI denoise", "Off keeps real texture. On: FFDNet smoothing for very dark scenes", st.aiDenoise) { v ->
        vm.updateSettings { it.copy(aiDenoise = v) }
    }
    toggle("aiz", "AI zoom", "Real-ESRGAN upscaling beyond the lenses (adds invented detail)", st.aiZoom) { v ->
        vm.updateSettings { it.copy(aiZoom = v) }
    }
}

private fun sizeLabel(m: VideoMode): String {
    val s = m.size
    val name = when {
        kotlin.math.abs(s.width.toFloat() / s.height - 4f / 3f) < 0.02f -> "Open gate"
        s.width >= 7680 -> "8K"
        s.width == 4096 -> "4K DCI"
        s.width >= 3840 -> "4K UHD"
        s.width >= 2560 -> "QHD"
        s.width >= 1920 -> "Full HD"
        s.width >= 1280 -> "HD"
        else -> ""
    }
    return "$name  ${s.width}×${s.height}".trim()
}

private fun LazyListScope.video(vm: CameraViewModel, ui: UiState, st: AppSettings) {
    val caps = ui.current
    val modes = ui.videoModes
    val sel = ui.selectedVideo
    header("Format")
    if (modes.isNotEmpty() && sel != null) {
        val sizes = modes.map { it.size }.distinct()
        choice("vsize", "Resolution", sizes.map { sz -> sizeLabel(modes.first { it.size == sz }) to sz }, sel.size) { sz ->
            // keep the frame rate if the new size has it, otherwise the closest one
            val cands = modes.filter { it.size == sz }
            val m = cands.firstOrNull { it.fps == sel.fps && it.highSpeed == sel.highSpeed } ?: cands.minBy { kotlin.math.abs(it.fps - sel.fps) }
            vm.updateSettings { it.copy(videoModeKey = vm.keyOf(m)) }
        }
        val rates = modes.filter { it.size == sel.size }.sortedBy { it.fps }
        choice("vfps", "Frame rate", rates.map { (if (it.highSpeed) "${it.fps} fps slow motion" else "${it.fps} fps") to vm.keyOf(it) },
            vm.keyOf(sel), "Up to ${modes.maxOf { it.fps }} fps on this camera") { k ->
            vm.updateSettings { it.copy(videoModeKey = k) }
        }
    }
    val encoders = EncoderCatalog.encoders()
    val codecs = VideoCodec.entries.filter { c -> encoders.any { it.codec == c } }
    choice("codec", "Codec", codecs.map { it.label to it }, st.videoCodec) { v -> vm.updateSettings { it.copy(videoCodec = v) } }
    val maxBr = encoders.filter { it.codec == st.videoCodec }.maxOfOrNull { it.maxBitrate } ?: 0
    val rates = (listOf(10, 20, 35, 50, 75, 100, 120, 150) + listOf(200, 300, 400, 600, 800).filter { it * 1_000_000 <= maxBr })
    choice("br", "Bitrate", listOf("Auto" to 0) + rates.map { "$it Mb/s" to it }, st.bitrateMbps,
        "Encoder reports up to ${maxBr / 1_000_000} Mb/s; higher is tried and falls back if refused") { v ->
        vm.updateSettings { it.copy(bitrateMbps = v) }
    }
    toggle("cbr", "Constant bitrate", null, st.cbr) { v -> vm.updateSettings { it.copy(cbr = v) } }
    toggle("hlg", "10-bit HDR (HLG)", if (caps?.supportsHlg == true) "Rec.2020 HLG" else "Not offered by this camera", st.tenBitHlg,
        caps?.supportsHlg == true) { v -> vm.updateSettings { it.copy(tenBitHlg = v) } }
    toggle("log", "Lumen Log (Pro Video)", "Flat curve for grading; LUT in General", st.logProfile) { v -> vm.updateSettings { it.copy(logProfile = v) } }
    toggle("stab", "Stabilization", null, st.stabilization) { v -> vm.updateSettings { it.copy(stabilization = v) } }
    header("Audio")
    toggle("aud", "Record audio", null, st.recordAudio) { v -> vm.updateSettings { it.copy(recordAudio = v) } }
    choice("gain", "Input gain", listOf(-12, -6, 0, 6, 12, 18).map { (if (it > 0) "+$it dB" else "$it dB") to it }, st.audioGainDb) { v ->
        vm.updateSettings { it.copy(audioGainDb = v) }
    }
    toggle("meters", "Level meters", "Peak levels while recording", st.audioMeters) { v -> vm.updateSettings { it.copy(audioMeters = v) } }
}

private fun LazyListScope.cinema(vm: CameraViewModel, st: AppSettings) {
    header("Exposure")
    toggle("angle", "Shutter angle", "Pro Video shows 180° instead of 1/50", st.shutterAngle) { v -> vm.updateSettings { it.copy(shutterAngle = v) } }
    choice("flicker", "Flicker-free", listOf("Auto" to 0, "50 Hz" to 50, "60 Hz" to 60), st.antibanding, "Match your mains frequency under artificial light") { v ->
        vm.updateSettings { it.copy(antibanding = v) }
    }
    toggle("wbl", "Lock white balance while recording", null, st.lockWbOnRecord) { v -> vm.updateSettings { it.copy(lockWbOnRecord = v) } }
    header("Monitoring")
    choice("guide", "Frame guide",
        listOf("Off" to 0f, "9:16" to 9f / 16f, "4:5" to 0.8f, "1:1" to 1f, "4:3" to 4f / 3f, "14:9" to 14f / 9f, "1.85:1" to 1.85f,
            "2:1" to 2f, "2.35:1" to 2.35f, "2.39:1" to 2.39f, "2.4:1" to 2.4f, "2.76:1" to 2.76f), st.frameGuide) { v ->
        vm.updateSettings { it.copy(frameGuide = v) }
    }
    choice("safe", "Safe area", listOf("Off" to 0, "80%" to 80, "90%" to 90, "93%" to 93), st.safeArea) { v -> vm.updateSettings { it.copy(safeArea = v) } }
    toggle("fc", "False colour", "Purple crushed · green mid grey · pink skin · red clipped", st.falseColor) { v ->
        vm.updateSettings { it.copy(falseColor = v) }
    }
    choice("tc", "Timecode", listOf("Record run" to false, "Time of day" to true), st.timecodeTod) { v -> vm.updateSettings { it.copy(timecodeTod = v) } }
}

private fun LazyListScope.assist(vm: CameraViewModel, st: AppSettings) {
    header("Overlays")
    toggle("grid", "Grid", null, st.grid) { v -> vm.updateSettings { it.copy(grid = v) } }
    toggle("level", "Level", null, st.level) { v -> vm.updateSettings { it.copy(level = v) } }
    toggle("hist", "Histogram", null, st.histogram) { v -> vm.updateSettings { it.copy(histogram = v) } }
    toggle("peak", "Focus peaking", null, st.focusPeaking) { v -> vm.updateSettings { it.copy(focusPeaking = v) } }
    toggle("zeb", "Zebras", "Stripes on clipped highlights", st.zebras) { v -> vm.updateSettings { it.copy(zebras = v) } }
}

private fun LazyListScope.astro(vm: CameraViewModel, ui: UiState, st: AppSettings) {
    val caps = ui.current
    header("Astro (tripod)")
    choice("af", "Frames", listOf(10, 20, 30, 60, 100, 200).map { "$it" to it }, st.astroFrames) { v -> vm.updateSettings { it.copy(astroFrames = v) } }
    val maxExpS = (caps?.exposureRange?.upper ?: 1_000_000_000L) / 1e9f
    choice("ae", "Exposure per frame", listOf(0.5f, 1f, 2f, 4f, 8f, 10f, 15f, 20f, 30f).filter { it <= maxExpS + 0.01f }.map { "${it}s" to it },
        st.astroExposureS, "This camera allows up to %.1f s".format(maxExpS)) { v -> vm.updateSettings { it.copy(astroExposureS = v) } }
    choice("ai", "ISO", listOf(400, 800, 1600, 3200, 6400).map { "$it" to it }, st.astroIso) { v -> vm.updateSettings { it.copy(astroIso = v) } }
    choice("as", "Stacking", listOf("Sigma clip" to NativeCore.STACK_SIGMA_CLIP, "Median" to NativeCore.STACK_MEDIAN,
        "Mean" to NativeCore.STACK_MEAN, "Star trails" to NativeCore.STACK_MAXIMUM), st.astroStackMode) { v -> vm.updateSettings { it.copy(astroStackMode = v) } }
    toggle("aal", "Align stars", "Compensates Earth's rotation", st.astroAlign) { v -> vm.updateSettings { it.copy(astroAlign = v) } }
    choice("adk", "Dark frames", listOf("0" to 0, "5" to 5, "10" to 10), st.astroDarkFrames, "Cover the lens when asked") { v ->
        vm.updateSettings { it.copy(astroDarkFrames = v) }
    }
}

private fun LazyListScope.general(vm: CameraViewModel, st: AppSettings) {
    header("Shooting")
    toggle("vol", "Volume keys take photos", null, st.volumeShutter) { v -> vm.updateSettings { it.copy(volumeShutter = v) } }
    choice("timer", "Self-timer", listOf("Off" to 0, "3 s" to 3, "10 s" to 10), st.timerSeconds) { v -> vm.updateSettings { it.copy(timerSeconds = v) } }
    header("Tools")
    action("lut", "Export Log LUT", "Lumen Log → Rec.709 .cube, saved to Download/Lumen") { vm.exportLut() }
    action("hw", "Hardware report", "Every camera, stream and encoder this phone exposes") { vm.showSheet(Sheet.HARDWARE) }
}
