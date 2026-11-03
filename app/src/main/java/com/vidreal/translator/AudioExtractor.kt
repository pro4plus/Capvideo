package com.vidreal.translator

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the audio track of a video to a 16 kHz mono 16-bit WAV file,
 * which is the format offline speech recognisers (Vosk / Whisper) expect.
 * Uses only Android's built-in MediaCodec, no native code needed.
 */
object AudioExtractor {

    fun extractToWav(context: Context, videoUri: Uri, outFile: File): File {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, videoUri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        require(trackIndex >= 0) { "No audio track found in this video" }
        extractor.selectTrack(trackIndex)

        val decoder = MediaCodec.createDecoderByType(format!!.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()

        // Resampling to 16 kHz is done in two steps: decode to PCM, then
        // down-mix / resample in code below. Most phone audio is 44.1/48 kHz.
        val pcm = mutableListOf<Short>()
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        val timeoutUs = 10_000L

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(info, timeoutUs)
            if (outIndex >= 0) {
                val buffer = decoder.getOutputBuffer(outIndex)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                } else if (info.size > 0) {
                    val shorts = ShortArray(info.size / 2)
                    buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                    pcm.addAll(shorts.toList())
                }
                decoder.releaseOutputBuffer(outIndex, false)
            }
        }
        decoder.stop()
        decoder.release()
        extractor.release()

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mono16k = resampleToMono16k(pcm.toShortArray(), sampleRate, channels)
        writeWav(outFile, mono16k, 16000)
        return outFile
    }

    private fun resampleToMono16k(input: ShortArray, sampleRate: Int, channels: Int): ShortArray {
        // Down-mix to mono first.
        val mono = ShortArray(input.size / channels.coerceAtLeast(1)) { i ->
            var sum = 0
            for (c in 0 until channels) sum += input[i * channels + c]
            (sum / channels).toShort()
        }
        if (sampleRate == 16000) return mono
        val ratio = sampleRate / 16000.0
        val outSize = (mono.size / ratio).toInt()
        return ShortArray(outSize) { i -> mono[(i * ratio).toInt().coerceAtMost(mono.size - 1)] }
    }

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        FileOutputStream(file).use { out ->
            val byteRate = sampleRate * 2
            val dataSize = samples.size * 2
            fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun writeInt(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
                out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
            }
            fun writeShort(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            }
            writeString("RIFF"); writeInt(36 + dataSize); writeString("WAVE")
            writeString("fmt "); writeInt(16); writeShort(1); writeShort(1)
            writeInt(sampleRate); writeInt(byteRate); writeShort(2); writeShort(16)
            writeString("data"); writeInt(dataSize)
            val bytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(samples)
            out.write(bytes.array())
        }
    }
}
