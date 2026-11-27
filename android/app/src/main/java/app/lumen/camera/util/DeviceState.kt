package app.lumen.camera.util

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.os.PowerManager
import android.view.OrientationEventListener
import app.lumen.camera.camera.CameraCaps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/** Physical device orientation (0/90/180/270) and roll angle for the level. */
class DeviceOrientation(context: Context) {
    private val _degrees = MutableStateFlow(0)
    val degrees: StateFlow<Int> = _degrees
    private val _roll = MutableStateFlow(0f)
    /** Degrees away from the nearest straight orientation (for the level line). */
    val roll: StateFlow<Float> = _roll

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(o: Int) {
            if (o == ORIENTATION_UNKNOWN) return
            val snapped = ((o + 45) / 90 * 90) % 360
            if (snapped != _degrees.value) _degrees.value = snapped
            var d = (o - snapped).toFloat()
            if (d > 180) d -= 360f
            _roll.value = d
        }
    }

    fun start() { if (listener.canDetectOrientation()) listener.enable() }
    fun stop() = listener.disable()

    /** JPEG/EXIF rotation for a capture, as documented for CaptureRequest.JPEG_ORIENTATION. */
    fun captureRotation(caps: CameraCaps): Int {
        var dev = _degrees.value
        if (caps.facing == CameraCharacteristics.LENS_FACING_FRONT) dev = -dev
        return (caps.sensorOrientation + dev + 360) % 360
    }
}

/** Thermal status (Android 10+). The app never disables thermal protection. */
class ThermalMonitor(context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val _status = MutableStateFlow(pm.currentThermalStatus)
    val status: StateFlow<Int> = _status
    private val listener = PowerManager.OnThermalStatusChangedListener { _status.value = it }

    fun start() = pm.addThermalStatusListener(listener)
    fun stop() = pm.removeThermalStatusListener(listener)

    val shouldStopRecording: Boolean get() = _status.value >= PowerManager.THERMAL_STATUS_SEVERE
    val isWarm: Boolean get() = _status.value >= PowerManager.THERMAL_STATUS_MODERATE
}

/** Live analysis of a small preview frame: histogram, focus peaking, zebras. */
class FrameAnalysis(
    val lumaHist: FloatArray,
    val rgbHist: Array<FloatArray>,
    val peaking: Bitmap?,
    val zebras: Bitmap?,
    val falseColor: Bitmap? = null,
) {
    companion object {
        private const val BINS = 64

        /** Exposure bands (percent of white, display referred), like cinema-camera false color. */
        fun falseColorFor(y: Float): Int {
            val p = y / 255f * 100f
            return when {
                p < 2.5f -> 0xFF7B2FBE.toInt()              // purple: crushed black
                p < 10f -> 0xFF1E5BFF.toInt()               // blue: just above black
                p in 38f..44f -> 0xFF39D353.toInt()         // green: 18% grey
                p in 52f..56f -> 0xFFFF8FB1.toInt()         // pink: one stop over grey (skin)
                p in 92f..98f -> 0xFFFFD60A.toInt()         // yellow: near clipping
                p >= 98f -> 0xFFFF3B30.toInt()              // red: clipped
                else -> { val g = (p / 100f * 160f + 40f).toInt(); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            }
        }

        fun analyze(src: Bitmap, peaking: Boolean, zebras: Boolean, falseColor: Boolean = false): FrameAnalysis {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val lh = FloatArray(BINS)
            val rh = Array(3) { FloatArray(BINS) }
            val luma = FloatArray(w * h)
            for (i in px.indices) {
                val c = px[i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
                luma[i] = y
                lh[(y.toInt() * BINS) shr 8]++
                rh[0][(r * BINS) shr 8]++
                rh[1][(g * BINS) shr 8]++
                rh[2][(b * BINS) shr 8]++
            }
            fun norm(a: FloatArray) {
                val m = a.maxOrNull()?.takeIf { it > 0 } ?: 1f
                for (i in a.indices) a[i] = sqrt(a[i] / m)
            }
            norm(lh); rh.forEach { norm(it) }

            var peak: Bitmap? = null
            if (peaking) {
                val out = IntArray(w * h)
                // Sobel magnitude, threshold relative to the frame's strongest edges
                val mag = FloatArray(w * h)
                var maxMag = 1f
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val i = y * w + x
                    val gx = luma[i - w + 1] + 2 * luma[i + 1] + luma[i + w + 1] - luma[i - w - 1] - 2 * luma[i - 1] - luma[i + w - 1]
                    val gy = luma[i + w - 1] + 2 * luma[i + w] + luma[i + w + 1] - luma[i - w - 1] - 2 * luma[i - w] - luma[i - w + 1]
                    val m = abs(gx) + abs(gy)
                    mag[i] = m
                    if (m > maxMag) maxMag = m
                }
                val thr = maxOf(90f, maxMag * 0.35f)
                for (i in mag.indices) if (mag[i] > thr) out[i] = 0xFFFF3B30.toInt()
                peak = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            }
            var zeb: Bitmap? = null
            if (zebras) {
                val out = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    if (luma[i] > 242f && ((x + y) / 3) % 2 == 0) out[i] = 0xCCFFFFFF.toInt()
                }
                zeb = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            }
            var fc: Bitmap? = null
            if (falseColor) {
                val out = IntArray(w * h) { falseColorFor(luma[it]) }
                fc = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            }
            return FrameAnalysis(lh, rh, peak, zeb, fc)
        }
    }
}
