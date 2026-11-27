package app.lumen.camera.ui.components

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.lumen.camera.ui.theme.LumenColors
import app.lumen.camera.util.FrameAnalysis
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Soft drop shadow that keeps white type readable over any scene without a dark backing. */
val OverlayShadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 1.5f), 6f)

fun overlayStyle(size: TextUnit, weight: FontWeight = FontWeight.Medium, color: Color = Color.White) =
    TextStyle(color = color, fontSize = size, fontWeight = weight, shadow = OverlayShadow)

/**
 * Camera preview. The stream is centre-cropped to fill this composable (so 1:1 and Full
 * framings show exactly what is saved). One gesture handler does tap-to-focus, pinch-zoom
 * and horizontal swipes between modes.
 */
@Composable
fun Viewfinder(
    previewSize: Size,
    modifier: Modifier,
    onSurface: (SurfaceTexture?) -> Unit,
    onTap: (Float, Float) -> Unit,
    onPinch: (Float) -> Unit,
    onSwipe: (Int) -> Unit,
    onAnalysisFrame: (android.graphics.Bitmap) -> Unit,
    analysisEnabled: Boolean,
) {
    var view by remember { mutableStateOf<TextureView?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val currentPreview by rememberUpdatedState(previewSize)
    val currentOnSurface by rememberUpdatedState(onSurface)
    val tap by rememberUpdatedState(onTap)
    val pinch by rememberUpdatedState(onPinch)
    val swipe by rememberUpdatedState(onSwipe)
    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            centerCrop(this@apply, currentPreview, w, h)
                            currentOnSurface(st)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            centerCrop(this@apply, currentPreview, w, h)
                        }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            currentOnSurface(null)
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                    view = this
                }
            },
            update = { tv -> centerCrop(tv, previewSize, tv.width, tv.height) },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                val swipeMin = 64.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var pan = Offset.Zero
                    var multi = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            multi = true
                            val z = ev.calculateZoom()
                            if (z != 1f) pinch(z)
                        } else {
                            pan += ev.changes.first().positionChange()
                        }
                        ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                    }
                    if (!multi) {
                        when {
                            abs(pan.x) > swipeMin && abs(pan.x) > 2 * abs(pan.y) -> swipe(if (pan.x < 0) 1 else -1)
                            pan.getDistance() < 24.dp.toPx() -> {
                                val p = down.position
                                focusPoint = p
                                val (nx, ny) = toBuffer(p.x, p.y, size.width.toFloat(), size.height.toFloat(), currentPreview)
                                tap(nx, ny)
                            }
                        }
                    }
                }
            },
        )
        focusPoint?.let { p ->
            val anim = remember(p) { Animatable(1.4f) }
            LaunchedEffect(p) {
                anim.animateTo(1f, tween(180))
                delay(1100)
                focusPoint = null
            }
            Canvas(Modifier.fillMaxSize()) {
                val r = 34.dp.toPx() * anim.value
                drawCircle(Color.Black.copy(alpha = 0.35f), radius = r, center = p + Offset(0f, 1.5f), style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, radius = r, center = p, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
    LaunchedEffect(analysisEnabled, view) {
        while (analysisEnabled) {
            delay(200)
            val tv = view ?: continue
            if (!tv.isAvailable || tv.width == 0) continue
            val w = 160
            val h = (w * tv.height.toFloat() / tv.width).toInt().coerceAtLeast(16)
            tv.getBitmap(w, h)?.let(onAnalysisFrame)
        }
    }
}

/** Portrait content size of the preview buffer (the sensor stream is landscape). */
private fun content(preview: Size) = preview.height.toFloat() to preview.width.toFloat()

/** Scale the TextureView content so it covers the view without distortion (centre crop). */
private fun centerCrop(tv: TextureView, preview: Size, viewW: Int, viewH: Int) {
    if (viewW == 0 || viewH == 0) return
    val (cw, ch) = content(preview)
    val s = maxOf(viewW / cw, viewH / ch)
    val m = Matrix()
    // TextureView stretches the content to the view; undo that, then scale uniformly
    m.setScale(cw * s / viewW, ch * s / viewH, viewW / 2f, viewH / 2f)
    tv.setTransform(m)
}

/** View point -> normalised position in the full preview frame (for metering regions). */
private fun toBuffer(x: Float, y: Float, viewW: Float, viewH: Float, preview: Size): Pair<Float, Float> {
    val (cw, ch) = content(preview)
    val s = maxOf(viewW / cw, viewH / ch)
    val nx = 0.5f + (x - viewW / 2f) / (cw * s)
    val ny = 0.5f + (y - viewH / 2f) / (ch * s)
    return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
}

/** Grid, level, frame guides, histogram, peaking/zebra/false-colour over the viewfinder. */
@Composable
fun ViewfinderOverlays(
    grid: Boolean,
    level: Boolean,
    roll: () -> Float,
    histogram: Boolean,
    analysis: FrameAnalysis?,
    modifier: Modifier,
    frameGuide: Float = 0f,
    safeArea: Int = 0,
    landscape: Boolean = false,
) {
    Box(modifier) {
        analysis?.falseColor?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        analysis?.peaking?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        analysis?.zebras?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        if (grid || frameGuide > 0f || safeArea in 1..99) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (grid) {
                    val c = Color.White.copy(alpha = 0.4f)
                    for (i in 1..2) {
                        drawLine(c, Offset(w * i / 3, 0f), Offset(w * i / 3, h), 1f)
                        drawLine(c, Offset(0f, h * i / 3), Offset(w, h * i / 3), 1f)
                    }
                }
                // frame guide (e.g. 2.39:1) for the way the phone is held, outside darkened
                if (frameGuide > 0f) {
                    val vfLong = if (landscape) h else w
                    val vfShort = if (landscape) w else h
                    var gl = vfLong
                    var gs = gl / frameGuide
                    if (gs > vfShort) { gs = vfShort; gl = gs * frameGuide }
                    val gw = if (landscape) gs else gl
                    val gh = if (landscape) gl else gs
                    val l = (w - gw) / 2; val t = (h - gh) / 2
                    val shade = Color.Black.copy(alpha = 0.55f)
                    drawRect(shade, Offset(0f, 0f), GSize(w, t))
                    drawRect(shade, Offset(0f, t + gh), GSize(w, h - t - gh))
                    drawRect(shade, Offset(0f, t), GSize(l, gh))
                    drawRect(shade, Offset(l + gw, t), GSize(w - l - gw, gh))
                    drawRect(Color.White.copy(alpha = 0.8f), Offset(l, t), GSize(gw, gh), style = Stroke(1.5f))
                }
                if (safeArea in 1..99) {
                    val f = safeArea / 100f
                    drawRect(LumenColors.Accent.copy(alpha = 0.8f), Offset(w * (1 - f) / 2, h * (1 - f) / 2), GSize(w * f, h * f), style = Stroke(1.5f))
                }
            }
        }
        if (level) LevelLine(roll, Modifier.fillMaxSize())
        if (histogram && analysis != null) {
            Histogram(analysis, Modifier.align(Alignment.TopEnd).padding(10.dp).size(104.dp, 48.dp))
        }
    }
}

/** Horizon line; reads the roll in the draw phase so sensor updates never recompose. */
@Composable
private fun LevelLine(roll: () -> Float, modifier: Modifier) {
    Canvas(modifier) {
        val r = roll()
        if (abs(r) >= 30f) return@Canvas
        val w = size.width
        val h = size.height
        val ok = abs(r) < 1f
        rotate(-r, pivot = Offset(w / 2, h / 2)) {
            val c = if (ok) LumenColors.Accent else Color.White.copy(alpha = 0.85f)
            drawLine(Color.Black.copy(alpha = 0.3f), Offset(w * 0.38f, h / 2 + 1.5f), Offset(w * 0.62f, h / 2 + 1.5f), 3f)
            drawLine(c, Offset(w * 0.38f, h / 2), Offset(w * 0.62f, h / 2), if (ok) 2.5f else 1.5f)
        }
    }
}

/** Recording read-outs: timecode and audio meters. */
@Composable
fun RecordOverlay(timecode: String?, audio: FloatArray?, modifier: Modifier) {
    Box(modifier) {
        if (audio != null) AudioMeters(audio, Modifier.align(Alignment.CenterStart).padding(8.dp).size(14.dp, 110.dp))
        if (timecode != null) {
            Text(
                timecode, style = overlayStyle(14.sp, FontWeight.SemiBold).copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
    }
}

/** Two-channel peak meters, dBFS from -60 to 0 (green / yellow above -12 / red above -3). */
@Composable
fun AudioMeters(db: FloatArray, modifier: Modifier) {
    Canvas(modifier) {
        val bw = size.width / 2f
        for (ch in 0 until minOf(2, db.size)) {
            val f = ((db[ch] + 60f) / 60f).coerceIn(0f, 1f)
            val hgt = f * size.height
            val c = when {
                db[ch] > -3f -> Color(0xFFFF3B30)
                db[ch] > -12f -> Color(0xFFFFD60A)
                else -> Color(0xFF34C759)
            }
            drawRect(Color.White.copy(alpha = 0.6f), Offset(ch * bw + 1f, 0f), GSize(bw - 2f, size.height), style = Stroke(1f))
            drawRect(c, Offset(ch * bw + 1f, size.height - hgt), GSize(bw - 2f, hgt))
        }
        for (mark in listOf(-12f, -3f)) {
            val y = size.height * (1f - (mark + 60f) / 60f)
            drawLine(Color.White.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1f)
        }
    }
}

@Composable
fun Histogram(a: FrameAnalysis, modifier: Modifier) {
    Canvas(modifier) {
        val n = a.lumaHist.size
        val bw = size.width / n
        drawRect(Color.White.copy(alpha = 0.5f), Offset.Zero, size, style = Stroke(1f))
        val colors = listOf(Color(0x99FF453A), Color(0x9930D158), Color(0x990A84FF))
        a.rgbHist.forEachIndexed { ci, hist ->
            for (i in 0 until n) {
                val hgt = hist[i] * size.height
                drawRect(colors[ci], Offset(i * bw, size.height - hgt), GSize(bw, hgt))
            }
        }
        for (i in 0 until n) {
            val hgt = a.lumaHist[i] * size.height
            drawRect(Color.White.copy(alpha = 0.55f), Offset(i * bw, size.height - hgt), GSize(bw, hgt))
        }
    }
}

/** White status text with a drop shadow (no background box). */
@Composable
fun InfoText(text: String, modifier: Modifier, color: Color = Color.White) {
    Text(text, style = overlayStyle(12.sp, FontWeight.SemiBold, color), modifier = modifier)
}

/** Shadowed icon: a soft dark copy under the white glyph, so it reads on bright skies. */
@Composable
fun ShadowIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    rotation: () -> Float = { 0f },
) {
    Box(modifier.graphicsLayer { rotationZ = rotation() }) {
        androidx.compose.material3.Icon(icon, null, tint = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier.size(24.dp).graphicsLayer { translationY = 1.5.dp.toPx() })
        androidx.compose.material3.Icon(icon, desc, tint = tint, modifier = Modifier.size(24.dp))
    }
}
