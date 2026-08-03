package com.alastorkaneki.lyricvideomaker.transcription

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.floor

internal class AudioPcmDecoder(private val context: Context) {
    suspend fun decodeToWhisperSamples(uri: Uri): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("The selected file does not contain a decodable audio track.")

            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME type is missing.")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            var outputFormat = sourceFormat
            var sampleRate = sourceFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channelCount = sourceFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val mono = FloatArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Audio decoder input buffer was unavailable.")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = outputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount).coerceAtLeast(1)
                        pcmEncoding = outputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val floats = buffer.asFloatBuffer()
                                    val frameCount = floats.remaining() / channelCount
                                    repeat(frameCount) {
                                        var sum = 0f
                                        repeat(channelCount) { sum += floats.get() }
                                        mono.add((sum / channelCount).coerceIn(-1f, 1f))
                                    }
                                }
                                else -> {
                                    val shorts = buffer.asShortBuffer()
                                    val frameCount = shorts.remaining() / channelCount
                                    repeat(frameCount) {
                                        var sum = 0f
                                        repeat(channelCount) { sum += shorts.get() / 32768f }
                                        mono.add((sum / channelCount).coerceIn(-1f, 1f))
                                    }
                                }
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val source = mono.toArray()
            require(source.isNotEmpty()) { "The audio decoder produced no samples." }
            resampleLinear(source, sampleRate, 16_000)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun resampleLinear(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (sourceRate == targetRate) return input
        require(sourceRate > 0) { "Invalid decoded sample rate: $sourceRate" }
        val outputSize = ((input.size.toLong() * targetRate) / sourceRate).toInt().coerceAtLeast(1)
        val output = FloatArray(outputSize)
        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        for (index in output.indices) {
            val sourcePosition = index * ratio
            val left = floor(sourcePosition).toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (sourcePosition - left).toFloat()
            output[index] = input[left] + (input[right] - input[left]) * fraction
        }
        return output
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private class FloatArrayBuilder(initialCapacity: Int = 16_384) {
        private var values = FloatArray(initialCapacity)
        private var size = 0

        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }
}
