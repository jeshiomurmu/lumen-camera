package app.lumen.camera.core

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * JNI bridge to the shared C++ imaging core (repo: core/include/lumen_core.h).
 * Packed array layouts must match android/app/src/main/cpp/lumen_jni.cpp.
 */
object NativeCore {
    init {
        System.loadLibrary("lumen_jni")
    }

    /** [0..2] wb gains, [3..11] cam->sRGB, [12..20] CM1, [21..29] CM2, [30..38] FM1,
     *  [39..47] FM2, [48] illum1, [49] illum2, [50..52] as-shot neutral, [53] baseline EV */
    const val COLOR_SIZE = 54

    /** [profile, exposureEv, autoExposure, shadows, contrast, saturation, sharpen,
     *   chromaNr, warmth, outputP3, halfSize] */
    const val FINISH_SIZE = 11

    const val OK = 0
    const val ERR_NOT_ENOUGH_STARS = -5

    const val PROFILE_NATURAL = 0
    const val PROFILE_VIVID = 1
    const val PROFILE_FILM = 2
    const val PROFILE_MONO = 3
    const val PROFILE_NIGHT = 4

    const val STACK_SIGMA_CLIP = 0
    const val STACK_MEDIAN = 1
    const val STACK_MEAN = 2
    const val STACK_MAXIMUM = 3

    external fun version(): String

    // ---- burst merge (HDR+-style) -------------------------------------------------
    external fun burstCreate(
        width: Int, height: Int, rowStride: Int, cfa: Int, quadBayer: Boolean,
        black: FloatArray, white: Float, noiseScale: FloatArray, noiseOffset: FloatArray,
        robustness: Float, spatialDenoise: Float, referenceMode: Int,
    ): Long

    external fun burstAddFrame(handle: Long, data: ByteBuffer): Int
    external fun burstAddFrameRaw10(handle: Long, data: ByteBuffer, rowStride: Int): Int

    /** Returns [framesMerged, referenceIndex, meanMotionPx, noiseScale, noiseOffset] or null. */
    external fun burstFinalize(handle: Long): FloatArray?
    external fun burstRender(handle: Long, color: FloatArray, finish: FloatArray, out: Bitmap): Int
    external fun burstDisplaySigma(handle: Long): Float
    external fun burstWriteDng(
        handle: Long, color: FloatArray, metaStr: Array<String?>, metaNum: FloatArray, path: String,
    ): Int

    external fun burstDestroy(handle: Long)

    // ---- astro stacking ---------------------------------------------------------------
    external fun astroCreate(
        width: Int, height: Int, rowStride: Int, cfa: Int, quadBayer: Boolean,
        black: FloatArray, white: Float, noiseScale: FloatArray, noiseOffset: FloatArray,
        alignStars: Boolean, stackMode: Int, kappa: Float, hotPixels: Boolean, removeGradient: Boolean,
        tempDir: String,
    ): Long

    external fun astroAddDark(handle: Long, data: ByteBuffer): Int

    /** Returns [rc, starsDetected, starsMatched, rotationDeg, shiftX, shiftY, rmsPx, accepted]. */
    external fun astroAddLight(handle: Long, data: ByteBuffer): FloatArray
    external fun astroFrameCount(handle: Long): Int
    external fun astroFinalize(handle: Long): Int
    external fun astroRender(handle: Long, color: FloatArray, finish: FloatArray?, out: Bitmap): Int
    external fun astroPreview(handle: Long, color: FloatArray, out: Bitmap): Int
    external fun astroWriteDng(
        handle: Long, color: FloatArray, metaStr: Array<String?>, metaNum: FloatArray, path: String,
    ): Int

    external fun astroDestroy(handle: Long)

    // ---- single frame / color / misc ---------------------------------------------------
    external fun writeRawDng(
        width: Int, height: Int, rowStride: Int, cfa: Int, quadBayer: Boolean,
        black: FloatArray, white: Float, noiseScale: FloatArray, noiseOffset: FloatArray,
        data: ByteBuffer, color: FloatArray, metaStr: Array<String?>, metaNum: FloatArray, path: String,
    ): Int

    external fun colorResolve(color: FloatArray): FloatArray?
    external fun wbGainsForCct(color: FloatArray, kelvin: Float, tint: Float): FloatArray?
    external fun estimateCct(color: FloatArray): Float
    /** Multi-frame merge of ISP YUV_420_888 frames (phones without RAW). First frame = reference. */
    external fun yuvMergeCreate(width: Int, height: Int, strength: Float): Long
    external fun yuvMergeAdd(handle: Long, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, yRowStride: Int, uvRowStride: Int, uvPixelStride: Int): Int
    /** Returns [frames, noiseSigma, meanWeight] or null. */
    external fun yuvMergeRender(handle: Long, out: Bitmap): FloatArray?
    external fun yuvMergeDestroy(handle: Long)
    external fun logEncode(x: Float): Float
    external fun writeLogLut(path: String): Int
}

/** Description of one RAW stream (all frames of a burst share it). */
data class RawSpec(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val cfa: Int,
    val quadBayer: Boolean,
    val black: FloatArray,
    val white: Float,
    val noiseScale: FloatArray,
    val noiseOffset: FloatArray,
    val packedRaw10: Boolean = false,
)

/** Finishing parameters (mirrors LumenFinishParams). */
data class FinishParams(
    val profile: Int = NativeCore.PROFILE_NATURAL,
    val exposureEv: Float = 0f,
    val autoExposure: Boolean = true,
    val shadows: Float = 0.45f,
    val contrast: Float = 0.10f,
    val saturation: Float = 0f,
    val sharpen: Float = 0.55f,
    val chromaNr: Float = 0.6f,
    val warmth: Float = 0f,
    val outputP3: Boolean = false,
    val halfSize: Boolean = false,
) {
    fun pack(): FloatArray = floatArrayOf(
        profile.toFloat(), exposureEv, if (autoExposure) 1f else 0f, shadows, contrast, saturation,
        sharpen, chromaNr, warmth, if (outputP3) 1f else 0f, if (halfSize) 1f else 0f,
    )

    companion object {
        fun forProfile(profile: Int): FinishParams = when (profile) {
            NativeCore.PROFILE_VIVID -> FinishParams(profile, shadows = 0.55f, contrast = 0.20f, saturation = 0.22f, sharpen = 0.8f)
            NativeCore.PROFILE_FILM -> FinishParams(profile, shadows = 0.40f, contrast = 0.08f, saturation = -0.12f, sharpen = 0.35f, warmth = 0.25f)
            NativeCore.PROFILE_MONO -> FinishParams(profile, shadows = 0.50f, contrast = 0.20f, saturation = -1f, sharpen = 0.7f, chromaNr = 0f)
            NativeCore.PROFILE_NIGHT -> FinishParams(profile, shadows = 0.70f, contrast = 0.08f, saturation = 0.05f, sharpen = 0.35f, chromaNr = 0.9f)
            else -> FinishParams()
        }
    }
}

/** Strings + numbers for DNG tags. */
data class DngMeta(
    val make: String,
    val model: String,
    val orientation: Int,
    val exposureTimeS: Float,
    val fNumber: Float,
    val focalLengthMm: Float,
    val iso: Int,
    val datetime: String,
) {
    fun strings(): Array<String?> = arrayOf(make, model, "$make $model", "Lumen Camera ${NativeCore.version()}", datetime)
    fun numbers(): FloatArray = floatArrayOf(orientation.toFloat(), exposureTimeS, fNumber, focalLengthMm, iso.toFloat())
}
