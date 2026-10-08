package com.amp.player.loudness

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.amp.player.data.db.TrackEntity
import com.amp.player.util.AmpLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PcmTrackDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun measure(track: TrackEntity, contentUri: Uri): LoudnessResult? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val file = File(track.path)
            if (file.isFile) {
                extractor.setDataSource(file.absolutePath)
                AmpLog.i("ReplayGain", "decode file id=${track.id} path=${file.absolutePath}")
            } else {
                extractor.setDataSource(context, contentUri, null)
                AmpLog.i("ReplayGain", "decode uri id=${track.id} uri=$contentUri")
            }
            val audioTrack = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var receivedAudio = false
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val meter = LoudnessMeter()
            meter.reset(sampleRate, channels)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val timeoutUs = 1_000L
            val deadline = SystemClock.elapsedRealtime() + TRACK_TIMEOUT_MS
            val shortBuf = ShortArray(8192)
            val floatBuf = FloatArray(8192)
            while (!outputDone) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    throw IllegalStateException("timeout after ${TRACK_TIMEOUT_MS}ms")
                }
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, timeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                .coerceIn(1, 2)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        if (!receivedAudio) meter.reset(sampleRate, channels)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        val out = decoder.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            feedPcm(meter, out, info, shortBuf, floatBuf, pcmEncoding)
                            receivedAudio = true
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            return meter.result()
        } catch (t: Throwable) {
            AmpLog.w("ReplayGain", "decode failed id=${track.id} title=${track.title}: ${t.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun feedPcm(
        meter: LoudnessMeter,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        shortBuf: ShortArray,
        floatBuf: FloatArray,
        pcmEncoding: Int,
    ) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val ordered = buffer.order(ByteOrder.nativeOrder())
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = info.size / 4
            if (floats <= 0) return
            var offset = 0
            while (offset < floats) {
                val n = minOf(floatBuf.size, floats - offset)
                for (i in 0 until n) {
                    floatBuf[i] = ordered.float
                }
                meter.addInterleaved(floatBuf, n)
                offset += n
            }
            return
        }
        val shorts = info.size / 2
        if (shorts <= 0) return
        val pcm = ordered.asShortBuffer()
        var remaining = shorts
        while (remaining > 0) {
            val n = minOf(shortBuf.size, remaining)
            pcm.get(shortBuf, 0, n)
            meter.addPcm16(shortBuf, n)
            remaining -= n
        }
    }

    companion object {
        private const val TRACK_TIMEOUT_MS = 180_000L
    }
}
