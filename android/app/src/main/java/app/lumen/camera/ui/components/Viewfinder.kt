package app.lumen.camera.ui.components

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.lumen.camera.ui.theme.LumenColors
import app.lumen.camera.util.FrameAnalysis
import kotlinx.coroutines.delay

/**
 * Camera preview (TextureView) with tap-to-focus, pinch-to-zoom and periodic
 * small frames for histogram / focus peaking / zebras.
 */
@Composable
fun Viewfinder(
    previewSize: Size,
    modifier: Modifier,
    onSurface: (SurfaceTexture?) -> Unit,
    onTap: (Float, Float) -> Unit,
    onPinch: (Float) -> Unit,
    onAnalysisFrame: (android.graphics.Bitmap) -> Unit,
    analysisEnabled: Boolean,
) {
    var view by remember { mutableStateOf<TextureView?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val currentPreview by rememberUpdatedState(previewSize)
    val currentOnSurface by rememberUpdatedState(onSurface)
    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            configureTransform(this@apply, currentPreview, w, h)
                            currentOnSurface(st)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            configureTransform(this@apply, currentPreview, w, h)
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
            update = { tv -> configureTransform(tv, previewSize, tv.width, tv.height) },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        focusPoint = off
                        onTap(off.x / size.width, off.y / size.height)
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onPinch(zoom) }
                },
        )
        focusPoint?.let { p ->
            LaunchedEffect(p) {
                delay(1200)
                focusPoint = null
            }
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(LumenColors.Accent, radius = 36.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
            }
        }
    }
    LaunchedEffect(analysisEnabled, view) {
        while (analysisEnabled) {
            delay(160)
            val tv = view ?: continue
            if (!tv.isAvailable || tv.width == 0) continue
            val w = 192
            val h = (w * tv.height.toFloat() / tv.width).toInt().coerceAtLeast(16)
            tv.getBitmap(w, h)?.let(onAnalysisFrame)
        }
    }
}

/** Camera2Basic-style transform so the preview is upright for any display rotation. */
private fun configureTransform(tv: TextureView, preview: Size, viewW: Int, viewH: Int) {
    if (viewW == 0 || viewH == 0) return
    val rotation = tv.display?.rotation ?: Surface.ROTATION_0
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
    val bufferRect = RectF(0f, 0f, preview.height.toFloat(), preview.width.toFloat())
    val cx = viewRect.centerX()
    val cy = viewRect.centerY()
    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
        bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = maxOf(viewH.toFloat() / preview.height, viewW.toFloat() / preview.width)
        matrix.postScale(scale, scale, cx, cy)
        matrix.postRotate(90f * (rotation - 2), cx, cy)
    } else if (rotation == Surface.ROTATION_180) {
        matrix.postRotate(180f, cx, cy)
    }
    tv.setTransform(matrix)
}

/** Grid, level, histogram, peaking/zebra overlays drawn over the viewfinder. */
@Composable
fun ViewfinderOverlays(
    grid: Boolean,
    level: Boolean,
    roll: Float,
    histogram: Boolean,
    analysis: FrameAnalysis?,
    modifier: Modifier,
    frameGuide: Float = 0f,
    safeArea: Int = 0,
    landscape: Boolean = false,
    audio: FloatArray? = null,
    timecode: String? = null,
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
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (grid) {
                val c = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) {
                    drawLine(c, Offset(w * i / 3, 0f), Offset(w * i / 3, h), 1f)
                    drawLine(c, Offset(0f, h * i / 3), Offset(w, h * i / 3), 1f)
                }
            }
            // frame guide (e.g. 2.39:1) for the way the phone is held, with darkened outside
            if (frameGuide > 0f) {
                val vfLong = if (landscape) h else w
                val vfShort = if (landscape) w else h
                // guide is wider than tall in the recorded (held) orientation
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
            if (level && kotlin.math.abs(roll) < 30f) {
                val ok = kotlin.math.abs(roll) < 1f
                rotate(-roll, pivot = Offset(w / 2, h / 2)) {
                    drawLine(
                        if (ok) LumenColors.Good else Color.White.copy(alpha = 0.8f),
                        Offset(w * 0.35f, h / 2), Offset(w * 0.65f, h / 2), if (ok) 3f else 2f,
                    )
                }
            }
        }
        if (histogram && analysis != null) {
            Histogram(analysis, Modifier.align(Alignment.TopEnd).padding(8.dp).size(112.dp, 56.dp))
        }
        if (audio != null) {
            AudioMeters(audio, Modifier.align(Alignment.CenterStart).padding(6.dp).size(18.dp, 120.dp))
        }
        if (timecode != null) {
            Text(
                timecode, color = Color.White, fontSize = 13.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color(0x99000000), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Two-channel peak meters, dBFS from -60 to 0 (green / yellow above -12 / red above -3). */
@Composable
fun AudioMeters(db: FloatArray, modifier: Modifier) {
    Canvas(modifier.background(Color(0x88000000))) {
        val bw = size.width / 2f
        for (ch in 0 until minOf(2, db.size)) {
            val f = ((db[ch] + 60f) / 60f).coerceIn(0f, 1f)
            val hgt = f * size.height
            val c = when {
                db[ch] > -3f -> Color(0xFFFF3B30)
                db[ch] > -12f -> Color(0xFFFFD60A)
                else -> Color(0xFF34C759)
            }
            drawRect(c, Offset(ch * bw + 1f, size.height - hgt), GSize(bw - 2f, hgt))
        }
        for (mark in listOf(-12f, -3f)) {
            val y = size.height * (1f - (mark + 60f) / 60f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 1f)
        }
    }
}

@Composable
fun Histogram(a: FrameAnalysis, modifier: Modifier) {
    Canvas(modifier.background(Color(0x88000000))) {
        val n = a.lumaHist.size
        val bw = size.width / n
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

@Composable
fun InfoText(text: String, modifier: Modifier) {
    Text(
        text, color = Color.White, fontSize = 12.sp,
        modifier = modifier.background(Color(0x66000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
