package app.lumen.camera.camera

import android.hardware.camera2.CaptureResult
import android.media.Image
import android.os.Build
import android.graphics.ImageFormat
import app.lumen.camera.core.NativeCore
import app.lumen.camera.core.RawSpec

/** Converts Camera2 metadata into the imaging core's color + RAW descriptions. */
object ColorMath {

    fun staticColor(caps: CameraCaps): FloatArray {
        val c = FloatArray(NativeCore.COLOR_SIZE)
        caps.colorMatrix1?.copyInto(c, 12)
        caps.colorMatrix2?.copyInto(c, 21)
        caps.forwardMatrix1?.copyInto(c, 30)
        caps.forwardMatrix2?.copyInto(c, 39)
        c[48] = caps.illuminant1.toFloat()
        c[49] = caps.illuminant2.toFloat()
        return c
    }

    /**
     * Color for processing a RAW capture. With auto WB we reuse the ISP's own
     * AWB decision (gains + matrix), with manual Kelvin we derive the gains from
     * the calibrated DNG-style matrices.
     */
    fun colorFor(caps: CameraCaps, result: CaptureResult?, kelvin: Int?, tint: Int): FloatArray {
        val c = staticColor(caps)
        result?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { n ->
            if (n.size >= 3) for (i in 0..2) c[50 + i] = n[i].toFloat()
        }
        if (kelvin != null) {
            NativeCore.wbGainsForCct(c, kelvin.toFloat(), tint.toFloat())?.copyInto(c, 0)
            // cam->sRGB left at zero: the core derives it for this white point
        } else {
            result?.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { g ->
                val gg = (g.greenEven + g.greenOdd) / 2f
                if (gg > 0f) {
                    c[0] = g.red / gg; c[1] = 1f; c[2] = g.blue / gg
                }
            }
            result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { t ->
                for (r in 0..2) for (col in 0..2) c[3 + r * 3 + col] = t.getElement(col, r).toFloat()
            }
        }
        return c
    }

    fun rawSpec(caps: CameraCaps, image: Image, result: CaptureResult?, maxResMode: Boolean): RawSpec {
        val black = FloatArray(4)
        val dyn = if (Build.VERSION.SDK_INT >= 29) result?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) else null
        if (dyn != null && dyn.size >= 4) dyn.copyInto(black, 0, 0, 4) else caps.blackPattern.copyInto(black)
        val white = (if (Build.VERSION.SDK_INT >= 29) result?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) else null)
            ?: caps.whiteLevel
        val ns = FloatArray(4)
        val no = FloatArray(4)
        result?.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { prof ->
            for (k in 0 until minOf(4, prof.size)) {
                ns[k] = prof[k].first.toFloat()
                no[k] = prof[k].second.toFloat()
            }
            if (prof.size == 1) for (k in 1..3) { ns[k] = ns[0]; no[k] = no[0] }
        }
        val binned = if (Build.VERSION.SDK_INT >= 31) result?.get(CaptureResult.SENSOR_RAW_BINNING_FACTOR_USED) else null
        val quad = maxResMode && binned == true && caps.binningFactor?.let { it.width == 2 && it.height == 2 } == true
        return RawSpec(
            width = image.width,
            height = image.height,
            rowStride = image.planes[0].rowStride,
            cfa = caps.cfa.coerceIn(0, 3),
            quadBayer = quad,
            black = black,
            white = white.toFloat(),
            noiseScale = ns,
            noiseOffset = no,
            packedRaw10 = image.format == ImageFormat.RAW10,
        )
    }

    /** EV100 of the current scene from the auto-exposure result. */
    fun ev100(exposureNs: Long, iso: Int, aperture: Float): Float {
        if (exposureNs <= 0 || iso <= 0) return 10f
        val t = exposureNs / 1e9
        val n = if (aperture > 0) aperture.toDouble() else 1.8
        return (Math.log(n * n / t) / Math.log(2.0) - Math.log(iso / 100.0) / Math.log(2.0)).toFloat()
    }
}
