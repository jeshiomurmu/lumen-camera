package app.lumen.camera.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Timer10
import androidx.compose.material.icons.outlined.Timer3
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.camera.camera.FlashMode
import app.lumen.camera.ui.Mode
import app.lumen.camera.ui.ZoomStop
import app.lumen.camera.ui.theme.LumenColors
import kotlin.math.roundToInt

// ---- top bar ------------------------------------------------------------------------------
@Composable
fun TopBar(
    flash: FlashMode,
    timer: Int,
    aspectLabel: String,
    resolutionLabel: String,
    rawOn: Boolean,
    aiOn: Boolean,
    showNight: Boolean,
    nightOn: Boolean,
    nightSuggested: Boolean,
    iconRotation: Float,
    onSettings: () -> Unit,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onAspect: () -> Unit,
    onResolution: () -> Unit,
    onRaw: () -> Unit,
    onAi: () -> Unit,
    onNight: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = Color.White, modifier = Modifier.rotate(iconRotation)) }
        IconButton(onClick = onFlash) {
            val icon = when (flash) {
                FlashMode.OFF -> Icons.Outlined.FlashOff
                FlashMode.AUTO -> Icons.Outlined.FlashAuto
                FlashMode.ON -> Icons.Outlined.FlashOn
                FlashMode.TORCH -> Icons.Outlined.FlashlightOn
            }
            Icon(icon, "Flash", tint = if (flash == FlashMode.OFF) Color.White else LumenColors.Accent, modifier = Modifier.rotate(iconRotation))
        }
        IconButton(onClick = onTimer) {
            val icon = when (timer) { 0 -> Icons.Outlined.TimerOff; 3 -> Icons.Outlined.Timer3; 10 -> Icons.Outlined.Timer10; else -> Icons.Outlined.Timer }
            Icon(icon, "Timer", tint = if (timer == 0) Color.White else LumenColors.Accent, modifier = Modifier.rotate(iconRotation))
        }
        TextChip(aspectLabel, false, iconRotation, onAspect)
        TextChip(resolutionLabel, false, iconRotation, onResolution)
        TextChip("RAW", rawOn, iconRotation, onRaw)
        IconButton(onClick = onAi) {
            Icon(Icons.Outlined.AutoAwesome, "AI enhance", tint = if (aiOn) LumenColors.Accent else Color.White, modifier = Modifier.rotate(iconRotation))
        }
        if (showNight) {
            IconButton(onClick = onNight) {
                Icon(
                    Icons.Outlined.Bedtime, "Night",
                    tint = when { nightOn -> LumenColors.Accent; nightSuggested -> Color(0xFFFFE08A); else -> Color.White },
                    modifier = Modifier.rotate(iconRotation),
                )
            }
        }
    }
}

@Composable
fun TextChip(text: String, on: Boolean, rotation: Float, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (on) LumenColors.Accent else Color.White.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .rotate(rotation),
    ) {
        Text(text, color = if (on) LumenColors.Accent else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// ---- zoom pills ---------------------------------------------------------------------------
@Composable
fun ZoomBar(stops: List<ZoomStop>, factor: Float, rotation: Float, onSelect: (ZoomStop) -> Unit) {
    if (stops.isEmpty()) return
    val active = stops.minByOrNull { kotlin.math.abs(it.factor - factor) }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0x55000000)).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stops.forEach { s ->
            val sel = s == active
            val label = if (sel) {
                if (factor < 1f) "%.1fx".format(factor).trimStart('0') else if (kotlin.math.abs(factor - factor.roundToInt()) < 0.05f) "${factor.roundToInt()}x" else "%.1fx".format(factor)
            } else s.label
            Box(
                Modifier.size(if (sel) 42.dp else 34.dp).clip(CircleShape)
                    .background(if (sel) Color(0xCC1E1E1E) else Color(0x66000000))
                    .clickable { onSelect(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) LumenColors.Accent else Color.White, fontSize = if (sel) 13.sp else 11.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.rotate(rotation))
            }
        }
    }
}

// ---- mode carousel -----------------------------------------------------------------------
@Composable
fun ModeCarousel(modes: List<Mode>, current: Mode, onSelect: (Mode) -> Unit, onMore: () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(current) {
        val idx = modes.indexOf(current)
        scroll.animateScrollTo((idx * 220).coerceAtMost(scroll.maxValue))
    }
    Row(
        Modifier.fillMaxWidth().height(44.dp).horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(80.dp))
        modes.forEach { m ->
            val sel = m == current
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (sel) Color(0xFF2B2B2E) else Color.Transparent)
                    .clickable { onSelect(m) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(m.label, color = if (sel) Color.White else LumenColors.TextDim, fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
            }
        }
        Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onMore).padding(horizontal = 14.dp, vertical = 7.dp)) {
            Text("MORE", color = LumenColors.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(80.dp))
    }
}

// ---- shutter row ---------------------------------------------------------------------------
@Composable
fun ShutterRow(
    mode: Mode,
    recording: Boolean,
    busy: Boolean,
    progress: Float,
    countdown: Int,
    thumbnail: Bitmap?,
    rotation: Float,
    onShutter: () -> Unit,
    onGallery: () -> Unit,
    onFlip: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 34.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF222224)).border(1.5.dp, Color.White, CircleShape)
                .clickable(onClick = onGallery),
        ) {
            thumbnail?.let {
                Image(it.asImageBitmap(), "Last capture", Modifier.size(52.dp).rotate(rotation), contentScale = ContentScale.Crop)
            }
        }
        ShutterButton(mode, recording, busy, progress, countdown, onShutter)
        IconButton(onClick = onFlip, modifier = Modifier.size(52.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
            Icon(Icons.Outlined.Cameraswitch, "Switch camera", tint = Color.White, modifier = Modifier.rotate(rotation))
        }
    }
}

@Composable
fun ShutterButton(mode: Mode, recording: Boolean, busy: Boolean, progress: Float, countdown: Int, onClick: () -> Unit) {
    val inner by animateFloatAsState(if (recording) 0.42f else 0.78f, label = "shutter")
    Box(Modifier.size(78.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(78.dp)) {
            val r = size.minDimension / 2
            drawCircle(Color.White, r - 2.dp.toPx(), style = Stroke(4.dp.toPx()))
            if (busy && progress > 0f) {
                drawArc(LumenColors.Accent, -90f, 360f * progress.coerceIn(0f, 1f), false,
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                    style = Stroke(4.dp.toPx()))
            }
            val fill = when {
                mode.isVideo -> LumenColors.Record
                mode == Mode.ASTRO -> Color(0xFFDDE3FF)
                else -> Color.White
            }
            if (recording) {
                val s = r * inner * 1.2f
                drawRoundRect(fill, topLeft = Offset(center.x - s / 2, center.y - s / 2),
                    size = androidx.compose.ui.geometry.Size(s, s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            } else {
                drawCircle(if (busy) fill.copy(alpha = 0.5f) else fill, r * inner)
            }
        }
        if (countdown > 0) Text("$countdown", color = Color.Black, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

// ---- Pro panel -----------------------------------------------------------------------------
data class ProValue(val label: String, val value: String, val auto: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProChips(values: List<ProValue>, selected: String?, onSelect: (String) -> Unit, onReset: (String) -> Unit, onPresets: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        values.forEach { v ->
            val sel = v.label == selected
            Column(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Color(0xFF2B2B2E) else Color(0x44000000))
                    .combinedClickable(onClick = { onSelect(v.label) }, onLongClick = { onReset(v.label) })
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(v.label, color = LumenColors.TextDim, fontSize = 10.sp)
                Text(v.value, color = if (v.auto) Color.White else LumenColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(
            Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x44000000)).clickable(onClick = onPresets)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PRESET", color = LumenColors.TextDim, fontSize = 10.sp)
            Text("☰", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Horizontal "ruler" dial: drag to move through [labels]; index 0 can be "A" (auto).
 */
@Composable
fun RulerDial(labels: List<String>, index: Int, onIndex: (Int) -> Unit) {
    if (labels.isEmpty()) return
    var acc by remember { mutableFloatStateOf(0f) }
    val stepPx = 26f
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(labels[index.coerceIn(0, labels.lastIndex)], color = LumenColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Canvas(
            Modifier.fillMaxWidth().height(34.dp).pointerInput(labels, index) {
                detectHorizontalDragGestures(onDragEnd = { acc = 0f }) { change, d ->
                    change.consume()
                    acc += d
                    val steps = (acc / stepPx).toInt()
                    if (steps != 0) {
                        acc -= steps * stepPx
                        onIndex((index - steps).coerceIn(0, labels.lastIndex))
                    }
                }
            },
        ) {
            val cx = size.width / 2
            val spacing = 13.dp.toPx()
            for (i in labels.indices) {
                val x = cx + (i - index) * spacing
                if (x < 0 || x > size.width) continue
                val major = i % 3 == 0
                drawLine(
                    if (i == index) LumenColors.Accent else Color.White.copy(alpha = if (major) 0.9f else 0.45f),
                    Offset(x, if (major) 4f else 12f), Offset(x, size.height - 4f), if (i == index) 3f else 1.5f,
                )
            }
        }
    }
}
