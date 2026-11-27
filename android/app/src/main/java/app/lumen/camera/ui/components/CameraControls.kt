package app.lumen.camera.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer10
import androidx.compose.material.icons.outlined.Timer3
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumen.camera.camera.FlashMode
import app.lumen.camera.ui.Aspect
import app.lumen.camera.ui.Mode
import app.lumen.camera.ui.ZoomStop
import app.lumen.camera.ui.theme.LumenColors
import kotlin.math.roundToInt

// All controls float directly over the picture: white glyphs with a soft shadow, no plates.

@Composable
private fun Tap(desc: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.size(44.dp).clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, ripple(bounded = false, radius = 22.dp), onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---- top bar ------------------------------------------------------------------------------
@Composable
fun TopBar(
    flash: FlashMode,
    timer: Int,
    aspect: Aspect,
    resolutionLabel: String?,
    rawOn: Boolean?,
    showNight: Boolean,
    nightOn: Boolean,
    nightSuggested: Boolean,
    rotation: () -> Float,
    onSettings: () -> Unit,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onAspect: () -> Unit,
    onResolution: () -> Unit,
    onRaw: () -> Unit,
    onNight: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tap("Settings", onSettings) { ShadowIcon(Icons.Outlined.Settings, null, Color.White, rotation = rotation) }
        Tap("Flash ${flash.name.lowercase()}", onFlash) {
            val icon = when (flash) {
                FlashMode.OFF -> Icons.Outlined.FlashOff
                FlashMode.AUTO -> Icons.Outlined.FlashAuto
                FlashMode.ON -> Icons.Outlined.FlashOn
                FlashMode.TORCH -> Icons.Outlined.FlashlightOn
            }
            ShadowIcon(icon, null, if (flash == FlashMode.OFF) Color.White else LumenColors.Accent, rotation = rotation)
        }
        Tap("Timer ${timer}s", onTimer) {
            val icon = when (timer) { 3 -> Icons.Outlined.Timer3; 10 -> Icons.Outlined.Timer10; else -> Icons.Outlined.TimerOff }
            ShadowIcon(icon, null, if (timer == 0) Color.White else LumenColors.Accent, rotation = rotation)
        }
        Tap("Aspect ratio ${aspect.label}", onAspect) { OutlineLabel(aspect.label, false, rotation) }
        if (resolutionLabel != null) Tap("Resolution $resolutionLabel", onResolution) { OutlineLabel(resolutionLabel, false, rotation) }
        if (rawOn != null) Tap("RAW", onRaw) { OutlineLabel("RAW", rawOn, rotation) }
        if (showNight) {
            Tap("Night", onNight) {
                ShadowIcon(
                    Icons.Outlined.Bedtime, null,
                    when { nightOn -> LumenColors.Accent; nightSuggested -> Color(0xFFFFE08A); else -> Color.White },
                    rotation = rotation,
                )
            }
        }
    }
}

@Composable
private fun OutlineLabel(text: String, on: Boolean, rotation: () -> Float) {
    val c = if (on) LumenColors.Accent else Color.White
    Box(
        Modifier.graphicsLayer { rotationZ = rotation() }
            .border(3.dp, Color.Black.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(0.75.dp)
            .border(1.5.dp, c, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, style = overlayStyle(11.sp, FontWeight.Bold, c))
    }
}

/** Framing choices, shown in place under the top bar. */
@Composable
fun AspectPicker(current: Aspect, onSelect: (Aspect) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Aspect.entries.forEach { a ->
            val sel = a == current
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSelect(a) }
                    .semantics { contentDescription = "Aspect ${a.label}" }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(a.label, style = overlayStyle(15.sp, if (sel) FontWeight.Bold else FontWeight.Medium, if (sel) LumenColors.Accent else Color.White))
            }
        }
    }
}

// ---- zoom ----------------------------------------------------------------------------------
@Composable
fun ZoomBar(stops: List<ZoomStop>, factor: Float, rotation: () -> Float, onSelect: (ZoomStop) -> Unit) {
    if (stops.isEmpty()) return
    val active = stops.minByOrNull { kotlin.math.abs(it.factor - factor) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        stops.forEach { s ->
            val sel = s == active
            val label = if (sel) {
                if (factor < 1f) "%.1f".format(factor).trimStart('0') + "x"
                else if (kotlin.math.abs(factor - factor.roundToInt()) < 0.05f) "${factor.roundToInt()}x" else "%.1fx".format(factor)
            } else s.label
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .then(if (sel) Modifier.border(3.dp, Color.Black.copy(alpha = 0.3f), CircleShape).padding(0.75.dp)
                        .border(1.5.dp, LumenColors.Accent, CircleShape) else Modifier)
                    .clickable { onSelect(s) }
                    .semantics { contentDescription = "Zoom ${s.label}" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = overlayStyle(if (sel) 13.sp else 12.sp, FontWeight.Bold, if (sel) LumenColors.Accent else Color.White),
                    modifier = Modifier.graphicsLayer { rotationZ = rotation() },
                )
            }
        }
    }
}

// ---- mode selector ---------------------------------------------------------------------------
/** Modes laid out with the current one centred; tap a neighbour or swipe the viewfinder. */
@Composable
fun ModeCarousel(modes: List<Mode>, current: Mode, onSelect: (Mode) -> Unit, onMore: () -> Unit) {
    val density = LocalDensity.current
    val items = modes.map { it.label } + "MORE"
    val idx = modes.indexOf(current).coerceAtLeast(0)
    val itemW = 92.dp
    val pos by animateFloatAsState(idx.toFloat(), tween(220), label = "modePos")
    BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) {
        val centre = with(density) { (maxWidth / 2 - itemW / 2).toPx() }
        val step = with(density) { itemW.toPx() }
        items.forEachIndexed { i, label ->
            val sel = i == idx
            Box(
                Modifier.width(itemW).height(40.dp)
                    .offset { IntOffset((centre + (i - pos) * step).roundToInt(), 0) }
                    .clickable(remember { MutableInteractionSource() }, null) { if (i < modes.size) onSelect(modes[i]) else onMore() }
                    .semantics { contentDescription = "Mode $label" },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = overlayStyle(13.sp, if (sel) FontWeight.Bold else FontWeight.Medium,
                        if (sel) Color.White else Color.White.copy(alpha = 0.72f)))
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(4.dp).clip(CircleShape).then(
                        if (sel) Modifier.border(2.dp, LumenColors.Accent, CircleShape) else Modifier))
                }
            }
        }
    }
}

// ---- shutter row ------------------------------------------------------------------------------
@Composable
fun ShutterRow(
    mode: Mode,
    recording: Boolean,
    busy: Boolean,
    progress: Float,
    countdown: Int,
    processing: Int,
    thumbnail: Bitmap?,
    rotation: () -> Float,
    onShutter: () -> Unit,
    onGallery: () -> Unit,
    onFlip: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(50.dp).clip(CircleShape).border(1.5.dp, Color.White, CircleShape)
                .clickable(onClick = onGallery).semantics { contentDescription = "Gallery" },
            contentAlignment = Alignment.Center,
        ) {
            thumbnail?.let {
                Image(it.asImageBitmap(), "Last capture", Modifier.size(50.dp).graphicsLayer { rotationZ = rotation() },
                    contentScale = ContentScale.Crop)
            }
            if (processing > 0) CircularProgressIndicator(Modifier.size(50.dp), color = LumenColors.Accent, strokeWidth = 2.dp)
        }
        ShutterButton(mode, recording, busy, progress, countdown, onShutter)
        Tap("Switch camera", onFlip, Modifier.size(50.dp)) {
            ShadowIcon(Icons.Outlined.Cameraswitch, null, Color.White, rotation = rotation)
        }
    }
}

@Composable
fun ShutterButton(mode: Mode, recording: Boolean, busy: Boolean, progress: Float, countdown: Int, onClick: () -> Unit) {
    val inner by animateFloatAsState(if (recording) 0.42f else 0.8f, tween(160), label = "shutter")
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.size(76.dp).clip(CircleShape)
            .clickable(src, ripple(bounded = false, radius = 38.dp, color = Color.White), onClick = onClick)
            .semantics { contentDescription = "Shutter" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(76.dp)) {
            val r = size.minDimension / 2
            drawCircle(Color.Black.copy(alpha = 0.25f), r - 2.dp.toPx(), center = center + Offset(0f, 1.5f), style = Stroke(4.dp.toPx()))
            drawCircle(Color.White, r - 2.dp.toPx(), style = Stroke(3.5.dp.toPx()))
            if (busy && progress > 0f) {
                drawArc(LumenColors.Accent, -90f, 360f * progress.coerceIn(0f, 1f), false,
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                    style = Stroke(3.5.dp.toPx()))
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
                drawCircle(if (busy) fill.copy(alpha = 0.55f) else fill, r * inner)
            }
        }
        if (countdown > 0) Text("$countdown", color = Color.Black, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

// ---- Pro controls -----------------------------------------------------------------------------
data class ProValue(val label: String, val value: String, val auto: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProChips(values: List<ProValue>, selected: String?, onSelect: (String) -> Unit, onReset: (String) -> Unit, onPresets: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        values.forEach { v ->
            val sel = v.label == selected
            Column(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = { onSelect(v.label) }, onLongClick = { onReset(v.label) })
                    .semantics { contentDescription = "Pro ${v.label}" }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(v.label, style = overlayStyle(10.sp, FontWeight.Medium, if (sel) LumenColors.Accent else Color.White.copy(alpha = 0.8f)))
                Text(v.value, style = overlayStyle(14.sp, FontWeight.Bold, if (sel || !v.auto) LumenColors.Accent else Color.White))
            }
        }
        Column(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPresets).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PRESET", style = overlayStyle(10.sp, FontWeight.Medium, Color.White.copy(alpha = 0.8f)))
            Text("···", style = overlayStyle(14.sp, FontWeight.Bold))
        }
    }
}

/**
 * Horizontal "ruler" dial: drag to move through [labels]; index 0 can be "A" (auto).
 * Ticks move with the finger between steps for a smooth feel.
 */
@Composable
fun RulerDial(labels: List<String>, index: Int, onIndex: (Int) -> Unit) {
    if (labels.isEmpty()) return
    var acc by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val stepPx = with(density) { 14.dp.toPx() }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(labels[index.coerceIn(0, labels.lastIndex)], style = overlayStyle(15.sp, FontWeight.Bold, LumenColors.Accent))
        Canvas(
            Modifier.fillMaxWidth().height(30.dp).pointerInput(labels, index) {
                detectHorizontalDragGestures(onDragEnd = { acc = 0f }, onDragCancel = { acc = 0f }) { change, d ->
                    change.consume()
                    acc += d
                    val steps = (acc / stepPx).toInt()
                    if (steps != 0) {
                        val target = (index - steps).coerceIn(0, labels.lastIndex)
                        acc -= steps * stepPx
                        if (target != index) onIndex(target)
                    }
                }
            },
        ) {
            val cx = size.width / 2 + acc
            for (i in labels.indices) {
                val x = cx + (i - index) * stepPx
                if (x < 0 || x > size.width) continue
                val major = i % 3 == 0
                val top = if (major) 4f else 12f
                drawLine(Color.Black.copy(alpha = 0.3f), Offset(x, top + 1.5f), Offset(x, size.height - 2.5f), 2.5f)
                drawLine(
                    if (i == index) LumenColors.Accent else Color.White.copy(alpha = if (major) 0.95f else 0.6f),
                    Offset(x, top), Offset(x, size.height - 4f), if (i == index) 3f else 1.5f,
                )
            }
        }
    }
}
