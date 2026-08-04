package com.alastorkaneki.lyricvideomaker.transcription

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

internal data class DecodedWhisperPcm(
    val file: File,
    val sampleCount: Long,
) {
    val durationMs: Long get() = sampleCount * 1_000L / WHISPER_SAMPLE_RATE
}

internal const val WHISPER_SAMPLE_RATE = 16_000

internal class AudioPcmDecoder(private val context: Context) {
    suspend fun decodeToWhisperPcm(
        uri: Uri,
        onProgress: suspend (Int) -> Unit = {},
    ): DecodedWhisperPcm = withContext(Dispatchers.IO) {
        val outputFile = File.createTempFile("whisper-audio-", ".pcm", context.cacheDir)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var sink: Pcm16Sink? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("The selected file does not contain a decodable audio track.")

            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME type is missing.")
            val durationUs = sourceFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L).coerceAtLeast(0L)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            var sampleRate = sourceFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channelCount = sourceFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            sink = Pcm16Sink(outputFile)
            var resampler = StreamingLinearResampler(sampleRate, WHISPER_SAMPLE_RATE, sink)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var lastProgress = -1

            suspend fun reportProgress(timestampUs: Long) {
                if (durationUs <= 0L) return
                val progress = ((timestampUs.coerceIn(0L, durationUs) * 100L) / durationUs)
                    .toInt()
                    .coerceIn(0, 99)
                if (progress != lastProgress) {
                    lastProgress = progress
                    onProgress(progress)
                }
            }

            while (!outputEnded) {
                coroutineContext.ensureActive()

                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("Audio decoder input buffer was unavailable.")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                            extractor.advance()
                            reportProgress(presentationTimeUs)
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        val newSampleRate = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_SAMPLE_RATE,
                            sampleRate,
                        )
                        channelCount = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_CHANNEL_COUNT,
                            channelCount,
                        ).coerceAtLeast(1)
                        pcmEncoding = outputFormat.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        if (newSampleRate != sampleRate) {
                            require(sink.sampleCount == 0L) {
                                "The decoder changed sample rate after audio output began."
                            }
                            sampleRate = newSampleRate
                            resampler = StreamingLinearResampler(sampleRate, WHISPER_SAMPLE_RATE, sink)
                        }
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
                                        resampler.accept((sum / channelCount).coerceIn(-1f, 1f))
                                    }
                                }
                                else -> {
                                    val shorts = buffer.asShortBuffer()
                                    val frameCount = shorts.remaining() / channelCount
                                    repeat(frameCount) {
                                        var sum = 0f
                                        repeat(channelCount) { sum += shorts.get() / 32768f }
                                        resampler.accept((sum / channelCount).coerceIn(-1f, 1f))
                                    }
                                }
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        reportProgress(info.presentationTimeUs)
                    }
                }
            }

            sink.close()
            val sampleCount = sink.sampleCount
            require(sampleCount > 0L) { "The audio decoder produced no samples." }
            onProgress(100)
            DecodedWhisperPcm(outputFile, sampleCount)
        } catch (error: Throwable) {
            runCatching { sink?.close() }
            outputFile.delete()
            throw error
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun MediaFormat.getLongOrDefault(key: String, fallback: Long): Long =
        if (containsKey(key)) getLong(key) else fallback

    private class StreamingLinearResampler(
        sourceRate: Int,
        targetRate: Int,
        private val sink: Pcm16Sink,
    ) {
        private val sourceSamplesPerOutput = sourceRate.toDouble() / targetRate.toDouble()
        private var hasPrevious = false
        private var previousSample = 0f
        private var inputIndex = 0L
        private var nextOutputPosition = 0.0

        init {
            require(sourceRate > 0) { "Invalid decoded sample rate: $sourceRate" }
            require(targetRate > 0) { "Invalid target sample rate: $targetRate" }
        }

        fun accept(sample: Float) {
            if (!hasPrevious) {
                previousSample = sample
                hasPrevious = true
                sink.add(sample)
                nextOutputPosition = sourceSamplesPerOutput
                return
            }

            inputIndex++
            val leftIndex = inputIndex - 1L
            while (nextOutputPosition <= inputIndex.toDouble()) {
                val fraction = (nextOutputPosition - leftIndex.toDouble()).toFloat().coerceIn(0f, 1f)
                val interpolated = previousSample + (sample - previousSample) * fraction
                sink.add(interpolated)
                nextOutputPosition += sourceSamplesPerOutput
            }
            previousSample = sample
        }
    }

    private class Pcm16Sink(file: File) : AutoCloseable {
        private val output = BufferedOutputStream(file.outputStream(), 64 * 1024)
        private val buffer = ByteArray(16 * 1024)
        private var bufferSize = 0
        var sampleCount: Long = 0L
            private set

        fun add(sample: Float) {
            val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            if (bufferSize + 2 > buffer.size) flushBuffer()
            buffer[bufferSize++] = (pcm and 0xff).toByte()
            buffer[bufferSize++] = ((pcm ushr 8) and 0xff).toByte()
            sampleCount++
        }

        override fun close() {
            flushBuffer()
            output.close()
        }

        private fun flushBuffer() {
            if (bufferSize <= 0) return
            output.write(buffer, 0, bufferSize)
            bufferSize = 0
        }
    }
}
