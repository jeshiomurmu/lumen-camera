package app.lumen.camera.camera

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * MediaCodec + MediaMuxer recorder with full control of codec, profile,
 * bit depth, color tags and bitrate (up to the encoder's real maximum).
 * The camera renders into [inputSurface]; audio is captured with AudioRecord.
 */
class VideoRecorder(
    private val config: Config,
    fd: FileDescriptor,
    orientationHint: Int,
    private val cameraTimestampRealtime: Boolean,
) {
    data class Config(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val codec: VideoCodec,
        val encoderName: String?,
        val tenBitHlg: Boolean,
        val logCurve: Boolean,
        val cbr: Boolean,
        val audio: Boolean,
        val audioBitrate: Int = 320_000,
        val audioDevice: AudioDeviceInfo? = null,
        val audioGainDb: Float = 0f,
    )

    /** Latest peak level per channel in dBFS (-90 = silence), for the on-screen meters. */
    @Volatile var levelsDb: FloatArray = floatArrayOf(-90f, -90f)
        private set

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
        setOrientationHint(orientationHint)
    }
    private val video: MediaCodec
    lateinit var inputSurface: Surface
        private set
    private var audio: MediaCodec? = null
    private var record: AudioRecord? = null

    @Volatile private var running = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var baseNs = -1L
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L
    private val pending = ArrayList<Sample>()
    private val realtimeToMonoNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime()

    private class Sample(val video: Boolean, val data: ByteArray, val ptsNs: Long, val flags: Int)

    var bytesWritten = 0L
        private set

    init {
        val c = config
        val mime = c.codec.mime
        video = if (c.encoderName != null) MediaCodec.createByCodecName(c.encoderName) else MediaCodec.createEncoderByType(mime)
        val fmt = MediaFormat.createVideoFormat(mime, c.width, c.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, c.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, c.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_OPERATING_RATE, c.fps)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (c.cbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            when (c.codec) {
                VideoCodec.HEVC -> if (c.tenBitHlg) setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
                VideoCodec.AV1 -> if (c.tenBitHlg) setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.AV1ProfileMain10)
                VideoCodec.APV -> setInteger(MediaFormat.KEY_PROFILE, EncoderCatalog.APV_PROFILE_422_10)
                VideoCodec.AVC -> setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.AVCProfileHigh)
            }
            if (c.tenBitHlg) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            } else {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        try {
            video.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject optional keys: retry with the essentials only.
            Log.w(TAG, "encoder rejected full format, retrying minimal: $e")
            fmt.removeKey(MediaFormat.KEY_BITRATE_MODE)
            fmt.removeKey(MediaFormat.KEY_OPERATING_RATE)
            fmt.removeKey(MediaFormat.KEY_PRIORITY)
            video.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = video.createInputSurface()
        if (c.audio) setupAudio()
    }

    @SuppressLint("MissingPermission")  // RECORD_AUDIO is checked by the caller
    private fun setupAudio() {
        val rate = 48_000
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val minBuf = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (e: Exception) {
            Log.w(TAG, "no audio: $e"); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        config.audioDevice?.let { rec.setPreferredDevice(it) }
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audio = enc
        record = rec
    }

    fun start() {
        running = true
        video.start()
        audio?.start()
        record?.startRecording()
        videoThread = Thread({ drainVideo() }, "LumenVideoEnc").apply { start() }
        if (audio != null) audioThread = Thread({ audioLoop() }, "LumenAudioEnc").apply { start() }
    }

    /** Stops and finalizes the file. Safe to call once. */
    fun stop() {
        if (!running) return
        running = false
        try { video.signalEndOfInputStream() } catch (_: Exception) {}
        audioThread?.join(3000)
        videoThread?.join(5000)
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        try { audio?.stop() } catch (_: Exception) {}
        audio?.release()
        try { video.stop() } catch (_: Exception) {}
        video.release()
        inputSurface.release()
        synchronized(lock) {
            try { if (muxerStarted) muxer.stop() } catch (e: Exception) { Log.w(TAG, "muxer stop: $e") }
        }
        muxer.release()
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try { video.dequeueOutputBuffer(info, 10_000) } catch (_: Exception) { break }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(true, video.outputFormat)
                idx >= 0 -> {
                    val buf = video.getOutputBuffer(idx)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && info.size > 0 && !isConfig) {
                        var ns = info.presentationTimeUs * 1000L
                        if (cameraTimestampRealtime) ns -= realtimeToMonoNs
                        write(true, buf, info, ns)
                    }
                    video.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    /** Applies the input gain (with clipping) and measures per-channel peaks. */
    private fun processAudio(buf: ByteBuffer, bytes: Int) {
        val gain = Math.pow(10.0, config.audioGainDb / 20.0).toFloat()
        val sb = buf.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        sb.position(0)
        val samples = bytes / 2
        var pl = 0
        var pr = 0
        for (i in 0 until samples) {
            var v = sb.getShort(i * 2).toInt()
            if (gain != 1f) {
                v = (v * gain).toInt().coerceIn(-32768, 32767)
                sb.putShort(i * 2, v.toShort())
            }
            val a = kotlin.math.abs(v)
            if (i and 1 == 0) { if (a > pl) pl = a } else { if (a > pr) pr = a }
        }
        fun db(p: Int) = if (p <= 0) -90f else (20.0 * kotlin.math.log10(p / 32768.0)).toFloat()
        levelsDb = floatArrayOf(db(pl), db(pr))
    }

    private fun audioLoop() {
        val enc = audio ?: return
        val rec = record ?: return
        val rate = 48_000
        val frameBytes = 4  // 16-bit stereo
        var frames = 0L
        var startNs = -1L
        val ts = AudioTimestamp()
        val info = MediaCodec.BufferInfo()
        var eosQueued = false
        while (true) {
            if (!eosQueued) {
                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = enc.getInputBuffer(inIdx)!!
                    ib.clear()
                    if (!running) {
                        val pts = if (startNs >= 0) (startNs + frames * 1_000_000_000L / rate) / 1000 else 0
                        enc.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    } else {
                        val n = rec.read(ib, minOf(ib.capacity(), 8192))
                        if (n > 0) processAudio(ib, n)
                        if (n > 0) {
                            if (startNs < 0) {
                                startNs = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                    ts.nanoTime - ts.framePosition * 1_000_000_000L / rate
                                } else System.nanoTime()
                            }
                            val ptsNs = startNs + frames * 1_000_000_000L / rate
                            frames += n / frameBytes
                            enc.queueInputBuffer(inIdx, 0, n, ptsNs / 1000, 0)
                        } else {
                            enc.queueInputBuffer(inIdx, 0, 0, 0, 0)
                        }
                    }
                }
            }
            // drain
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, if (eosQueued) 10_000 else 0)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { addTrack(false, enc.outputFormat); continue }
                if (idx < 0) continue
                val buf = enc.getOutputBuffer(idx)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (buf != null && info.size > 0 && !isConfig) write(false, buf, info, info.presentationTimeUs * 1000L)
                enc.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    private fun addTrack(isVideo: Boolean, format: MediaFormat) {
        synchronized(lock) {
            if (isVideo && videoTrack < 0) videoTrack = muxer.addTrack(format)
            if (!isVideo && audioTrack < 0) audioTrack = muxer.addTrack(format)
            val ready = videoTrack >= 0 && (audio == null || audioTrack >= 0)
            if (ready && !muxerStarted) {
                muxer.start()
                muxerStarted = true
                val queued = pending.sortedBy { it.ptsNs }
                pending.clear()
                for (s in queued) {
                    val bi = MediaCodec.BufferInfo().apply { set(0, s.data.size, 0, s.flags) }
                    writeLocked(s.video, ByteBuffer.wrap(s.data), bi, s.ptsNs)
                }
            }
        }
    }

    private fun write(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo, ptsNs: Long) {
        synchronized(lock) {
            if (!muxerStarted) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(bytes, 0, info.size)
                pending += Sample(isVideo, bytes, ptsNs, info.flags)
                return
            }
            writeLocked(isVideo, buf, info, ptsNs)
        }
    }

    private fun writeLocked(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo, ptsNs: Long) {
        // Timeline starts at the first video frame; earlier audio is dropped.
        if (baseNs < 0) {
            if (!isVideo) return
            baseNs = ptsNs
        }
        var us = (ptsNs - baseNs) / 1000
        if (us < 0) return
        if (isVideo) {
            if (us <= lastVideoUs) us = lastVideoUs + 1
            lastVideoUs = us
        } else {
            if (us <= lastAudioUs) us = lastAudioUs + 1
            lastAudioUs = us
        }
        val bi = MediaCodec.BufferInfo().apply { set(info.offset, info.size, us, info.flags) }
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        try {
            muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, bi)
            bytesWritten += info.size
        } catch (e: Exception) {
            Log.w(TAG, "writeSampleData: $e")
        }
    }

    companion object {
        private const val TAG = "LumenRecorder"
    }
}
