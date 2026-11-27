package app.lumen.camera.camera

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/** Video codecs the app can record with, and the real limits of the device's encoders. */
enum class VideoCodec(val mime: String, val label: String) {
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "HEVC (H.265)"),
    AVC(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264"),
    AV1("video/av01", "AV1"),
    // Advanced Professional Video, mandatory from Android 16 (MediaFormat.MIMETYPE_VIDEO_APV).
    APV("video/apv", "APV 422 10-bit"),
}

data class EncoderInfo(
    val codec: VideoCodec,
    val name: String,
    val hardware: Boolean,
    val maxBitrate: Int,
    val supportsMain10: Boolean,
    val supportsCbr: Boolean,
    val info: MediaCodecInfo,
)

object EncoderCatalog {
    // MediaCodecInfo.CodecProfileLevel.APVProfile422_10 / ...HDR10 (API 36)
    const val APV_PROFILE_422_10 = 1
    const val APV_PROFILE_422_10_HDR10 = 0x1000

    fun encoders(): List<EncoderInfo> {
        val out = mutableListOf<EncoderInfo>()
        val list = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos } catch (_: Exception) { emptyArray() }
        for (ci in list) {
            if (!ci.isEncoder) continue
            for (codec in VideoCodec.entries) {
                if (ci.supportedTypes.none { it.equals(codec.mime, ignoreCase = true) }) continue
                val caps = try { ci.getCapabilitiesForType(codec.mime) } catch (_: Exception) { continue }
                val vc = caps.videoCapabilities ?: continue
                val hw = if (Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated else !ci.name.startsWith("c2.android")
                val main10 = caps.profileLevels.any {
                    (codec == VideoCodec.HEVC && it.profile == CodecProfileLevel.HEVCProfileMain10) ||
                        (codec == VideoCodec.APV) ||
                        (codec == VideoCodec.AV1 && it.profile == CodecProfileLevel.AV1ProfileMain10)
                }
                val cbr = caps.encoderCapabilities?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                ) ?: false
                out += EncoderInfo(codec, ci.name, hw, vc.bitrateRange.upper, main10, cbr, ci)
            }
        }
        // hardware first, then highest bitrate
        return out.sortedWith(compareByDescending<EncoderInfo> { it.hardware }.thenByDescending { it.maxBitrate })
    }

    /** Best encoder for a codec that can do the requested size and frame rate. */
    fun find(codec: VideoCodec, width: Int, height: Int, fps: Int, needMain10: Boolean): EncoderInfo? =
        encoders().firstOrNull { e ->
            if (e.codec != codec) return@firstOrNull false
            if (needMain10 && !e.supportsMain10) return@firstOrNull false
            val vc = try { e.info.getCapabilitiesForType(codec.mime).videoCapabilities } catch (_: Exception) { null }
                ?: return@firstOrNull false
            try {
                vc.areSizeAndRateSupported(width, height, fps.toDouble()) ||
                    vc.areSizeAndRateSupported(height, width, fps.toDouble())
            } catch (_: Exception) {
                false
            }
        }

    fun describe(): List<String> = encoders().map {
        "${it.codec.label}: ${it.name} ${if (it.hardware) "HW" else "SW"} · max ${it.maxBitrate / 1_000_000} Mb/s" +
            (if (it.supportsMain10) " · 10-bit" else "") + (if (it.supportsCbr) " · CBR" else "")
    }
}
