package app.lumen.camera.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

/**
 * On-device open-source AI with ONNX Runtime (MIT):
 *  - lumen_denoise.onnx : FFDNet colour denoiser (KAIR, MIT) — noise-level aware
 *  - lumen_sr_x4.onnx   : Real-ESRGAN general-x4v3 (BSD-3-Clause) — "AI zoom"
 * Models are produced by tools/export_models.py and placed in assets/models/.
 * Tiling follows tools/export_models.py::tiled_run exactly (fixed tiles, edge-clamped context).
 */
class AiEngine(private val context: Context) {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var denoiser: OrtSession? = null
    private var upscaler: OrtSession? = null

    fun hasDenoiser(): Boolean = assetExists(DENOISE)
    fun hasUpscaler(): Boolean = assetExists(SR)

    private fun assetExists(name: String): Boolean =
        try { context.assets.open("models/$name").use { true } } catch (_: Exception) { false }

    private fun session(name: String): OrtSession {
        val bytes = context.assets.open("models/$name").use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
            try { addNnapi() } catch (e: Exception) { Log.i(TAG, "NNAPI unavailable, using CPU: $e") }
        }
        return env.createSession(bytes, opts)
    }

    @Synchronized
    private fun denoiserSession(): OrtSession = denoiser ?: session(DENOISE).also { denoiser = it }

    @Synchronized
    private fun upscalerSession(): OrtSession = upscaler ?: session(SR).also { upscaler = it }

    /** Denoises an ARGB_8888 bitmap in place. sigma = noise std in 0..1 display units. */
    fun denoise(bmp: Bitmap, sigma: Float, onProgress: (Float) -> Unit = {}) {
        val s = denoiserSession()
        val sig = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(sigma.coerceIn(0f, 0.3f))), longArrayOf(1, 1, 1, 1))
        // read from an untouched copy so context pixels of later tiles are not already denoised
        val source = bmp.copy(Bitmap.Config.ARGB_8888, false)
        sig.use {
            runTiled(source, bmp, 1, TILE, OVERLAP, onProgress) { input, h, w ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
                    s.run(mapOf("image" to t, "sigma" to sig)).use { r -> (r.get(0) as OnnxTensor).floatBuffer.let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } } }
                }
            }
        }
        source.recycle()
    }

    /** 4x super-resolution of [src]; returns a new bitmap 4x larger. */
    fun upscale4x(src: Bitmap, onProgress: (Float) -> Unit = {}): Bitmap {
        val s = upscalerSession()
        val out = Bitmap.createBitmap((src.width and 1.inv()) * 4, (src.height and 1.inv()) * 4, Bitmap.Config.ARGB_8888)
        runTiled(src, out, 4, SR_TILE, SR_OVERLAP, onProgress) { input, h, w ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
                s.run(mapOf("image" to t)).use { r -> (r.get(0) as OnnxTensor).floatBuffer.let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } } }
            }
        }
        return out
    }

    /**
     * Fixed-size tiling (tools/export_models.py::tiled_run): the image is cut into
     * step x step cores (step = tile - 2*overlap) and every model call gets exactly
     * tile x tile pixels — the core plus [overlap] px of context, with coordinates
     * outside the image clamped to the edge. The models have static shapes, so the
     * whole graph runs on NNAPI instead of falling back to the CPU.
     */
    private fun runTiled(
        src: Bitmap, dst: Bitmap, scale: Int, tile: Int, overlap: Int, onProgress: (Float) -> Unit,
        run: (FloatArray, Int, Int) -> FloatArray,
    ) {
        val W = src.width and 1.inv()
        val H = src.height and 1.inv()
        val step = tile - 2 * overlap
        val tilesY = (H + step - 1) / step
        val tilesX = (W + step - 1) / step
        val band = IntArray(tile * W)  // the tile rows of one band, edge-clamped
        val plane = tile * tile
        val inp = FloatArray(3 * plane)
        var done = 0
        var cy0 = 0
        while (cy0 < H) {
            val cy1 = minOf(H, cy0 + step)
            for (ty in 0 until tile) {
                val sy = (cy0 - overlap + ty).coerceIn(0, H - 1)
                src.getPixels(band, ty * W, W, 0, sy, W, 1)
            }
            var cx0 = 0
            while (cx0 < W) {
                val cx1 = minOf(W, cx0 + step)
                for (ty in 0 until tile) {
                    val rowOff = ty * W
                    for (tx in 0 until tile) {
                        val c = band[rowOff + (cx0 - overlap + tx).coerceIn(0, W - 1)]
                        val i = ty * tile + tx
                        inp[i] = ((c shr 16) and 0xff) / 255f
                        inp[plane + i] = ((c shr 8) and 0xff) / 255f
                        inp[2 * plane + i] = (c and 0xff) / 255f
                    }
                }
                val res = run(inp, tile, tile)
                val ow = tile * scale
                val outPlane = ow * ow
                val o = overlap * scale
                val cw = (cx1 - cx0) * scale
                val ch = (cy1 - cy0) * scale
                val outPx = IntArray(cw * ch)
                for (y in 0 until ch) for (x in 0 until cw) {
                    val si = (o + y) * ow + (o + x)
                    val r = (res[si].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    val g = (res[outPlane + si].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    val b = (res[2 * outPlane + si].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    outPx[y * cw + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
                dst.setPixels(outPx, 0, cw, cx0 * scale, cy0 * scale, cw, ch)
                done++
                onProgress(done.toFloat() / (tilesX * tilesY))
                cx0 += step
            }
            cy0 += step
        }
    }

    fun close() {
        denoiser?.close(); denoiser = null
        upscaler?.close(); upscaler = null
    }

    companion object {
        private const val TAG = "LumenAI"
        const val DENOISE = "lumen_denoise.onnx"
        const val SR = "lumen_sr_x4.onnx"
        private const val TILE = 256       // must match the exported model shapes
        private const val OVERLAP = 32
        private const val SR_TILE = 192
        private const val SR_OVERLAP = 40
    }
}
