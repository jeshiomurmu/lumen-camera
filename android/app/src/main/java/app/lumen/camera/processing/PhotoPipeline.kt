package app.lumen.camera.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CaptureResult
import android.net.Uri
import android.os.Build
import app.lumen.camera.ai.AiEngine
import app.lumen.camera.camera.CameraCaps
import app.lumen.camera.camera.CameraEngine
import app.lumen.camera.camera.ColorMath
import app.lumen.camera.camera.Shot
import app.lumen.camera.core.DngMeta
import app.lumen.camera.core.FinishParams
import app.lumen.camera.core.NativeCore
import app.lumen.camera.core.RawSpec
import app.lumen.camera.storage.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photo pipelines:
 *  - ISP JPEG (device processing)
 *  - Lumen burst: RAW burst -> align + robust merge -> finishing -> AI -> JPEG (+DNG)
 *  - Pro RAW: one RAW -> DNG (+ Lumen-rendered JPEG)
 *  - Astro: long exposures -> star-aligned sigma-clipped stack -> stretch -> JPEG + DNG
 *  - OEM extension (Night/HDR/Bokeh/vendor) via Camera2 Extensions
 */
class PhotoPipeline(
    private val context: Context,
    private val engine: CameraEngine,
    private val ai: AiEngine,
    private val catalog: () -> List<CameraCaps>,
) {
    data class Output(val uri: Uri?, val thumbnail: Bitmap?, val message: String)

    private fun bufferFor(capacity: Int, pool: ArrayDeque<ByteBuffer>): ByteBuffer =
        synchronized(pool) {
            pool.removeFirstOrNull()?.takeIf { it.capacity() >= capacity }
        } ?: ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())

    private fun copyPlane(shot: Shot, pool: ArrayDeque<ByteBuffer>): ByteBuffer {
        val src = shot.image.planes[0].buffer
        src.rewind()
        val dst = bufferFor(src.remaining(), pool)
        dst.clear()
        dst.put(src)
        dst.flip()
        return dst
    }

    /** Camera characteristics of the physical lens that produced a RAW frame. */
    private fun effectiveCaps(base: CameraCaps, result: CaptureResult?): CameraCaps {
        if (Build.VERSION.SDK_INT < 29 || result == null || !base.isLogical) return base
        val pid = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: return base
        return catalog().firstOrNull { it.id == pid } ?: base
    }

    private fun meta(caps: CameraCaps, result: CaptureResult?, orientationDeg: Int) = DngMeta(
        make = Build.MANUFACTURER,
        model = Build.MODEL,
        orientation = MediaSaver.tiffOrientationFor(orientationDeg),
        exposureTimeS = (result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1e9f,
        fNumber = result?.get(CaptureResult.LENS_APERTURE) ?: caps.apertures.first(),
        focalLengthMm = result?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: caps.focalLengths.first(),
        iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
        datetime = MediaSaver.exifDate(),
    )

    private fun exif(m: DngMeta, orientationDeg: Int) = MediaSaver.Exif(
        orientation = MediaSaver.exifOrientationFor(orientationDeg),
        make = m.make, model = m.model, exposureTimeS = m.exposureTimeS.toDouble(), fNumber = m.fNumber.toDouble(),
        iso = m.iso, focalLengthMm = m.focalLengthMm.toDouble(), software = "Lumen Camera ${NativeCore.version()}",
    )

    private fun thumbnail(bmp: Bitmap, orientationDeg: Int): Bitmap {
        val scale = 256f / max(bmp.width, bmp.height)
        val m = Matrix().apply { postScale(scale, scale); postRotate(orientationDeg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // ---- device ISP ----------------------------------------------------------------------
    suspend fun ispJpeg(orientationDeg: Int, quality: Int): Output = withContext(Dispatchers.IO) {
        val shot = engine.captureJpeg(orientationDeg, quality)
        val buf = shot.image.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        shot.image.close()
        val uri = MediaSaver.saveJpegBytes(context, bytes, MediaSaver.timestampName("IMG"))
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 16 }
        val thumb = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        Output(uri, thumb, "Saved (device processing)")
    }

    suspend fun extension(orientationDeg: Int, quality: Int, name: String): Output = withContext(Dispatchers.IO) {
        val bytes = engine.captureExtension(orientationDeg, quality)
        val uri = MediaSaver.saveJpegBytes(context, bytes, MediaSaver.timestampName("IMG"))
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 16 }
        Output(uri, android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts), "Saved ($name)")
    }

    // ---- Lumen burst (photo / night) ----------------------------------------------------
    data class BurstPlan(val frames: Int, val exposureNs: Long, val iso: Int)

    /** Exposure plan from the live auto-exposure, HDR+ style. */
    fun planBurst(caps: CameraCaps, night: Boolean, settings: AppSettings, manualIso: Int?, manualShutter: Long?): BurstPlan {
        val last = engine.lastResult
        val t0 = last?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 16_000_000L
        val iso0 = last?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 200
        val isoR = caps.isoRange ?: android.util.Range(50, 3200)
        val expR = caps.exposureRange ?: android.util.Range(100_000L, 250_000_000L)
        if (manualIso != null && manualShutter != null) {
            return BurstPlan(if (settings.burstFrames > 0) settings.burstFrames else 6, manualShutter, manualIso)
        }
        val ev = ColorMath.ev100(t0, iso0, caps.apertures.first())
        return if (night) {
            // brighter total exposure, longer frames (OIS helps), capped for hand-holding
            val target = t0.toDouble() * iso0 * 2.0
            val handheld = if (caps.oisModes.isNotEmpty()) 125_000_000L else 66_000_000L
            val t = min(handheld, max(t0 * 2, 33_000_000L)).coerceIn(expR.lower, expR.upper)
            val iso = (target / t).roundToInt().coerceIn(isoR.lower, isoR.upper)
            val n = if (settings.burstFrames > 0) settings.burstFrames else ceil(1.2e9 / t).toInt().coerceIn(8, 15)
            BurstPlan(n, t, iso)
        } else {
            // HDR+: underexpose ~1 EV to protect highlights; merging restores the shadows.
            val t = min(t0, 33_000_000L).coerceIn(expR.lower, expR.upper)
            val iso = (iso0.toDouble() * t0 / t / 2.0).roundToInt().coerceIn(isoR.lower, isoR.upper)
            val n = if (settings.burstFrames > 0) settings.burstFrames else when {
                ev >= 10 -> 6
                ev >= 6 -> 8
                else -> 10
            }
            BurstPlan(n, t, iso)
        }
    }

    suspend fun lumenBurst(
        plan: BurstPlan,
        night: Boolean,
        settings: AppSettings,
        orientationDeg: Int,
        zoomRatio: Float,
        manualKelvin: Int?,
        manualTint: Int,
        progress: (String, Float) -> Unit,
    ): Output = coroutineScope {
        val baseCaps = engine.caps ?: error("no camera")
        val pool = ArrayDeque<ByteBuffer>()
        val queue = Channel<Pair<ByteBuffer, Shot?>>(Channel.UNLIMITED)
        var handle = 0L
        var spec: RawSpec? = null
        var firstResult: CaptureResult? = null
        val maxRes = engine.isMaxResMode

        // consumer: native align + merge, one frame at a time
        val consumer = launch(Dispatchers.Default) {
            var n = 0
            for ((buf, _) in queue) {
                val s = spec!!
                if (handle == 0L) {
                    handle = NativeCore.burstCreate(
                        s.width, s.height, s.rowStride, s.cfa, s.quadBayer, s.black, s.white, s.noiseScale, s.noiseOffset,
                        if (night) 12f else 10f, if (night) 0.45f else 0.25f, 0,
                    )
                }
                if (s.packedRaw10) NativeCore.burstAddFrameRaw10(handle, buf, s.rowStride) else NativeCore.burstAddFrame(handle, buf)
                synchronized(pool) { pool.addLast(buf) }
                n++
                progress("Merging $n/${plan.frames}", 0.1f + 0.5f * n / plan.frames)
            }
        }
        var captured = 0
        engine.captureRawBurst(plan.frames, plan.exposureNs, plan.iso, orientationDeg) { shot ->
            if (spec == null) {
                spec = ColorMath.rawSpec(effectiveCaps(baseCaps, shot.result), shot.image, shot.result, maxRes)
                firstResult = shot.result
            }
            val buf = copyPlane(shot, pool)
            shot.image.close()
            queue.send(buf to null)
            captured++
            progress("Capturing $captured/${plan.frames}", 0.1f * captured / plan.frames)
        }
        queue.close()
        consumer.join()
        if (handle == 0L) return@coroutineScope Output(null, null, "No frames captured")
        try {
            withContext(Dispatchers.Default) {
                val stats = NativeCore.burstFinalize(handle) ?: error("merge failed")
                progress("Finishing", 0.7f)
                val caps = effectiveCaps(baseCaps, firstResult)
                val color = ColorMath.colorFor(caps, firstResult, manualKelvin, manualTint)
                val s = spec!!
                val half = s.width.toLong() * s.height > 60_000_000L  // keep memory sane on 200 MP
                val fp = FinishParams.forProfile(if (night && settings.profile == NativeCore.PROFILE_NATURAL) NativeCore.PROFILE_NIGHT else settings.profile)
                    .copy(outputP3 = settings.displayP3, halfSize = half)
                val w = if (half) s.width / 2 else s.width
                val h = if (half) s.height / 2 else s.height
                var bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val rc = NativeCore.burstRender(handle, color, fp.pack(), bmp)
                if (rc != NativeCore.OK) error("render failed ($rc)")
                val sigma = NativeCore.burstDisplaySigma(handle)
                if (settings.aiDenoise && ai.hasDenoiser() && sigma > 0.008f) {
                    progress("AI denoise", 0.8f)
                    ai.denoise(bmp, sigma * 1.2f)
                }
                // RAW is never cropped by the ISP: apply the zoom here. On a logical
                // multi-camera the RAW comes from the active physical lens.
                val zoomCrop = if (baseCaps.isLogical) zoomRatio * baseCaps.equivFocal / caps.equivFocal else zoomRatio
                if (zoomCrop > 1.05f) bmp = cropZoom(bmp, zoomCrop, settings.aiZoom && ai.hasUpscaler()) { p -> progress("AI zoom", 0.85f + 0.1f * p) }
                val m = meta(caps, firstResult, orientationDeg)
                val name = MediaSaver.timestampName("LUMEN")
                val uri = MediaSaver.saveBitmap(context, bmp, name, settings.jpegQuality, exif(m, orientationDeg))
                if (settings.saveRaw) {
                    val f = File(context.cacheDir, "$name.dng")
                    if (NativeCore.burstWriteDng(handle, color, m.strings(), m.numbers(), f.absolutePath) == NativeCore.OK) {
                        MediaSaver.saveDngFile(context, f, name)
                    }
                }
                val thumb = thumbnail(bmp, orientationDeg)
                bmp.recycle()
                Output(uri, thumb, "Merged ${stats[0].toInt()} frames")
            }
        } finally {
            NativeCore.burstDestroy(handle)
        }
    }

    /** Digital zoom on the rendered RAW (RAW is never cropped by the ISP). */
    private fun cropZoom(src: Bitmap, factor: Float, useAi: Boolean, p: (Float) -> Unit): Bitmap {
        val cw = (src.width / factor).roundToInt() and 1.inv()
        val ch = (src.height / factor).roundToInt() and 1.inv()
        val crop = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        val out = if (useAi && factor >= 1.8f && cw.toLong() * ch * 16 <= 80_000_000L) {
            val up = ai.upscale4x(crop, p)
            Bitmap.createScaledBitmap(up, src.width, src.height, true).also { if (it != up) up.recycle() }
        } else {
            Bitmap.createScaledBitmap(crop, src.width, src.height, true)
        }
        if (crop != src) crop.recycle()
        src.recycle()
        return out
    }

    // ---- Pro RAW --------------------------------------------------------------------------
    suspend fun proRaw(settings: AppSettings, orientationDeg: Int, manualKelvin: Int?, manualTint: Int): Output =
        withContext(Dispatchers.Default) {
            val shot = engine.captureRaw(orientationDeg)
            val caps = effectiveCaps(engine.caps ?: error("camera closed"), shot.result)
            val spec = ColorMath.rawSpec(caps, shot.image, shot.result, engine.isMaxResMode)
            val buf = copyPlane(shot, ArrayDeque())
            shot.image.close()
            val color = ColorMath.colorFor(caps, shot.result, manualKelvin, manualTint)
            val m = meta(caps, shot.result, orientationDeg)
            val name = MediaSaver.timestampName("RAW")
            var uri: Uri? = null
            if (!spec.packedRaw10) {
                val f = File(context.cacheDir, "$name.dng")
                if (NativeCore.writeRawDng(spec.width, spec.height, spec.rowStride, spec.cfa, spec.quadBayer, spec.black,
                        spec.white, spec.noiseScale, spec.noiseOffset, buf, color, m.strings(), m.numbers(), f.absolutePath) == NativeCore.OK) {
                    uri = MediaSaver.saveDngFile(context, f, name)
                }
            }
            // companion JPEG rendered by the Lumen pipeline (single frame)
            val h = NativeCore.burstCreate(spec.width, spec.height, spec.rowStride, spec.cfa, spec.quadBayer, spec.black,
                spec.white, spec.noiseScale, spec.noiseOffset, 10f, 0.4f, 1)
            var thumb: Bitmap? = null
            try {
                if (spec.packedRaw10) NativeCore.burstAddFrameRaw10(h, buf, spec.rowStride) else NativeCore.burstAddFrame(h, buf)
                NativeCore.burstFinalize(h)
                val half = spec.width.toLong() * spec.height > 60_000_000L
                val bmp = Bitmap.createBitmap(if (half) spec.width / 2 else spec.width, if (half) spec.height / 2 else spec.height, Bitmap.Config.ARGB_8888)
                val fp = FinishParams.forProfile(settings.profile).copy(outputP3 = settings.displayP3, halfSize = half)
                if (NativeCore.burstRender(h, color, fp.pack(), bmp) == NativeCore.OK) {
                    val jpgUri = MediaSaver.saveBitmap(context, bmp, name, settings.jpegQuality, exif(m, orientationDeg))
                    if (uri == null) uri = jpgUri
                    thumb = thumbnail(bmp, orientationDeg)
                }
                bmp.recycle()
            } finally {
                NativeCore.burstDestroy(h)
            }
            Output(uri, thumb, if (spec.packedRaw10) "Saved (RAW10 rendered)" else "Saved DNG + JPEG")
        }

    // ---- Astro ---------------------------------------------------------------------------------
    data class AstroProgress(val frame: Int, val total: Int, val text: String, val preview: Bitmap?)

    suspend fun astro(
        settings: AppSettings,
        orientationDeg: Int,
        darkFrames: Int,
        onProgress: (AstroProgress) -> Unit,
        cancelled: () -> Boolean,
    ): Output = withContext(Dispatchers.Default) {
        val caps = engine.caps ?: error("no camera")
        val maxExp = caps.exposureRange?.upper ?: 1_000_000_000L
        val exp = min((settings.astroExposureS * 1e9).toLong(), maxExp)
        val iso = caps.isoRange?.let { settings.astroIso.coerceIn(it.lower, it.upper) } ?: settings.astroIso
        var handle = 0L
        var spec: RawSpec? = null
        var first: CaptureResult? = null
        val buffers = ArrayDeque<ByteBuffer>()
        try {
            fun ensure(shot: Shot) {
                if (handle != 0L) return
                val s = ColorMath.rawSpec(caps, shot.image, shot.result, engine.isMaxResMode)
                if (s.packedRaw10) error("Astro stacking needs a RAW_SENSOR (16-bit) stream")
                spec = s
                first = shot.result
                handle = NativeCore.astroCreate(
                    s.width, s.height, s.rowStride, s.cfa, s.quadBayer, s.black, s.white, s.noiseScale, s.noiseOffset,
                    settings.astroAlign, settings.astroStackMode, 2.5f, true, true, context.cacheDir.absolutePath,
                )
            }
            for (i in 0 until darkFrames) {
                if (cancelled()) break
                onProgress(AstroProgress(i, darkFrames, "Dark frame ${i + 1}/$darkFrames (lens covered)", null))
                val shot = engine.captureRaw(orientationDeg, exp, iso)
                ensure(shot)
                val b = copyPlane(shot, buffers)
                shot.image.close()
                NativeCore.astroAddDark(handle, b)
                synchronized(buffers) { buffers.addLast(b) }
            }
            var accepted = 0
            for (i in 0 until settings.astroFrames) {
                if (cancelled()) break
                val shot = engine.captureRaw(orientationDeg, exp, iso)
                ensure(shot)
                val b = copyPlane(shot, buffers)
                shot.image.close()
                val st = NativeCore.astroAddLight(handle, b)
                synchronized(buffers) { buffers.addLast(b) }
                if (st[0].toInt() == NativeCore.OK) accepted++
                var preview: Bitmap? = null
                if (accepted > 0 && (i % 3 == 0 || i == settings.astroFrames - 1)) {
                    val s = spec!!
                    preview = Bitmap.createBitmap(s.width / 2, s.height / 2, Bitmap.Config.ARGB_8888)
                    NativeCore.astroPreview(handle, ColorMath.colorFor(caps, first, null, 0), preview)
                }
                val text = if (st[0].toInt() == NativeCore.OK) {
                    "Frame ${i + 1}/${settings.astroFrames} · ${st[2].toInt()} stars · rot %.2f°".format(st[3])
                } else "Frame ${i + 1}: not enough stars, skipped"
                onProgress(AstroProgress(i + 1, settings.astroFrames, text, preview))
            }
            if (handle == 0L || accepted == 0) return@withContext Output(null, null, "No frames stacked")
            onProgress(AstroProgress(settings.astroFrames, settings.astroFrames, "Stacking $accepted frames", null))
            if (NativeCore.astroFinalize(handle) != NativeCore.OK) error("stacking failed")
            val s = spec!!
            val color = ColorMath.colorFor(caps, first, null, 0)
            val half = s.width.toLong() * s.height > 60_000_000L
            val bmp = Bitmap.createBitmap(if (half) s.width / 2 else s.width, if (half) s.height / 2 else s.height, Bitmap.Config.ARGB_8888)
            val fp = FinishParams(sharpen = 0.25f, outputP3 = settings.displayP3, halfSize = half)
            NativeCore.astroRender(handle, color, fp.pack(), bmp)
            val m = meta(caps, first, orientationDeg).copy(exposureTimeS = exp / 1e9f * accepted)
            val name = MediaSaver.timestampName("ASTRO")
            val uri = MediaSaver.saveBitmap(context, bmp, name, settings.jpegQuality, exif(m, orientationDeg))
            val f = File(context.cacheDir, "$name.dng")
            if (NativeCore.astroWriteDng(handle, color, m.strings(), m.numbers(), f.absolutePath) == NativeCore.OK) {
                MediaSaver.saveDngFile(context, f, name)
            }
            val thumb = thumbnail(bmp, orientationDeg)
            bmp.recycle()
            Output(uri, thumb, "Stacked $accepted frames (${"%.0f".format(exp / 1e9 * accepted)} s total)")
        } finally {
            if (handle != 0L) NativeCore.astroDestroy(handle)
        }
    }

    companion object {
        fun rawFormatFor(caps: CameraCaps): Int = when {
            caps.rawSizes.isNotEmpty() -> ImageFormat.RAW_SENSOR
            caps.raw10Sizes.isNotEmpty() -> ImageFormat.RAW10
            else -> ImageFormat.RAW_SENSOR
        }
    }
}
