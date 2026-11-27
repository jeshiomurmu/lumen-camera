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
    if (ui.sheet == Sheet.NONE || ui.sheet == Sheet.SETTINGS) return   // settings is a full-screen page
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { vm.showSheet(Sheet.NONE) },
        sheetState = state,
        containerColor = LumenColors.Surface,
    ) {
        when (ui.sheet) {
            Sheet.SETTINGS -> {}
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
