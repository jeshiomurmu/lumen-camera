package app.lumen.camera.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes photos, DNGs and videos into the shared gallery (DCIM/Lumen). */
object MediaSaver {
    private const val DIR = "DCIM/Lumen"

    fun timestampName(prefix: String): String =
        prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun exifDate(): String = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())

    private fun insert(context: Context, name: String, mime: String, video: Boolean): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return context.contentResolver.insert(coll, values) ?: error("MediaStore insert failed")
    }

    fun publish(context: Context, uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, v, null, null)
    }

    data class Exif(
        val orientation: Int,          // ExifInterface.ORIENTATION_*
        val make: String,
        val model: String,
        val exposureTimeS: Double,
        val fNumber: Double,
        val iso: Int,
        val focalLengthMm: Double,
        val software: String,
    )

    /** JPEG bytes straight from the ISP (already contain EXIF). */
    fun saveJpegBytes(context: Context, bytes: ByteArray, name: String): Uri {
        val uri = insert(context, "$name.jpg", "image/jpeg", false)
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        publish(context, uri)
        return uri
    }

    /** Centred crop rectangle with the given long/short side ratio (1 = square). */
    fun cropRect(w: Int, h: Int, longOverShort: Float): android.graphics.Rect {
        val long = maxOf(w, h)
        val short = minOf(w, h)
        var nl = long
        var ns = (long / longOverShort).toInt()
        if (ns > short) { ns = short; nl = (short * longOverShort).toInt().coerceAtMost(long) }
        val cw = if (w >= h) nl else ns
        val ch = if (w >= h) ns else nl
        val l = ((w - cw) / 2) and 1.inv()
        val t = ((h - ch) / 2) and 1.inv()
        return android.graphics.Rect(l, t, l + (cw and 1.inv()), t + (ch and 1.inv()))
    }

    /**
     * Crops an ISP JPEG (1:1 or full-screen framing) without decoding the parts that are thrown
     * away, and carries over the camera's EXIF (orientation, exposure, lens, date).
     */
    fun saveJpegCropped(context: Context, bytes: ByteArray, name: String, longOverShort: Float, quality: Int): Uri {
        val dec = if (android.os.Build.VERSION.SDK_INT >= 31) android.graphics.BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        else @Suppress("DEPRECATION") android.graphics.BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        val rect = cropRect(dec.width, dec.height, longOverShort)
        val bmp = dec.decodeRegion(rect, null)
        dec.recycle()
        val uri = insert(context, "$name.jpg", "image/jpeg", false)
        context.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        bmp.recycle()
        try {
            val src = ExifInterface(java.io.ByteArrayInputStream(bytes))
            context.contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                val e = ExifInterface(pfd.fileDescriptor)
                for (tag in COPY_TAGS) src.getAttribute(tag)?.let { e.setAttribute(tag, it) }
                e.saveAttributes()
            }
        } catch (_: Exception) {}
        publish(context, uri)
        return uri
    }

    private val COPY_TAGS = listOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    )

    /** Small upright thumbnail of a JPEG (EXIF orientation applied, crop applied). */
    fun thumbnailFromJpeg(bytes: ByteArray, longOverShort: Float?): Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
        var b = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        if (longOverShort != null && b != null) {
            val r = cropRect(b.width, b.height, longOverShort)
            b = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height())
        }
        val deg = ExifInterface(java.io.ByteArrayInputStream(bytes)).rotationDegrees
        if (b != null && deg != 0) {
            val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        b
    } catch (_: Exception) { null }

    /** Encodes a processed bitmap and writes EXIF (orientation instead of rotating pixels). */
    fun saveBitmap(context: Context, bmp: Bitmap, name: String, quality: Int, exif: Exif): Uri {
        val uri = insert(context, "$name.jpg", "image/jpeg", false)
        context.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                val e = ExifInterface(pfd.fileDescriptor)
                e.setAttribute(ExifInterface.TAG_ORIENTATION, exif.orientation.toString())
                e.setAttribute(ExifInterface.TAG_MAKE, exif.make)
                e.setAttribute(ExifInterface.TAG_MODEL, exif.model)
                e.setAttribute(ExifInterface.TAG_SOFTWARE, exif.software)
                e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate())
                e.setAttribute(ExifInterface.TAG_DATETIME, exifDate())
                if (exif.exposureTimeS > 0) e.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exif.exposureTimeS.toString())
                if (exif.fNumber > 0) e.setAttribute(ExifInterface.TAG_F_NUMBER, exif.fNumber.toString())
                if (exif.iso > 0) e.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, exif.iso.toString())
                if (exif.focalLengthMm > 0) {
                    e.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(exif.focalLengthMm * 1000).toLong()}/1000")
                }
                e.saveAttributes()
            }
        } catch (_: Exception) {
            // EXIF is best effort; the image itself is already written
        }
        publish(context, uri)
        return uri
    }

    /** Copies a DNG written by the native core (to cacheDir) into the gallery. */
    fun saveDngFile(context: Context, file: File, name: String): Uri {
        val uri = insert(context, "$name.dng", "image/x-adobe-dng", false)
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
        file.delete()
        publish(context, uri)
        return uri
    }

    /** Creates a pending video entry; returns its Uri and a writable descriptor for MediaMuxer. */
    fun createVideo(context: Context, name: String): Pair<Uri, ParcelFileDescriptor> {
        val uri = insert(context, "$name.mp4", "video/mp4", true)
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: error("cannot open video file")
        return uri to pfd
    }

    fun delete(context: Context, uri: Uri) {
        try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
    }

    fun exifOrientationFor(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /** TIFF orientation for DNG files (same numbering as EXIF). */
    fun tiffOrientationFor(degrees: Int): Int = exifOrientationFor(degrees)
}
