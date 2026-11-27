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
