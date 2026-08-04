package com.alastorkaneki.lyricvideomaker.transcription

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.alastorkaneki.lyricvideomaker.model.TranscriptSegment
import com.alastorkaneki.lyricvideomaker.model.TranscriptWord
import com.alastorkaneki.lyricvideomaker.model.TranscriptionResult
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

data class LocalTranscriptionProgress(
    val stage: Stage,
    val percent: Int,
    val processedMs: Long,
    val totalMs: Long,
    val skippedChunks: Int,
    val elapsedMs: Long,
) {
    enum class Stage {
        DECODING,
        LOADING_MODEL,
        TRANSCRIBING,
    }
}

data class LocalTranscriptionOptions(
    val turboMode: Boolean = true,
    val chunkDurationMs: Int = 28_000,
    val overlapMs: Int = 1_000,
    val silenceRmsThreshold: Float = 0.0035f,
)

internal data class TranscriptionChunk(
    val index: Int,
    val startSample: Long,
    val sampleCount: Int,
    val uniqueSampleCount: Long,
) {
    val startMs: Long get() = startSample * 1_000L / WHISPER_SAMPLE_RATE
    val endMs: Long get() = (startSample + sampleCount) * 1_000L / WHISPER_SAMPLE_RATE
}

internal object TranscriptionChunkPlanner {
    fun plan(sampleCount: Long, options: LocalTranscriptionOptions): List<TranscriptionChunk> {
        require(options.chunkDurationMs in 10_000..30_000) {
            "Chunk duration must be between 10 and 30 seconds."
        }
        require(options.overlapMs in 0 until options.chunkDurationMs) {
            "Chunk overlap must be shorter than the chunk duration."
        }
        if (sampleCount <= 0L) return emptyList()

        val chunkSamples = options.chunkDurationMs.toLong() * WHISPER_SAMPLE_RATE / 1_000L
        val overlapSamples = options.overlapMs.toLong() * WHISPER_SAMPLE_RATE / 1_000L
        val stepSamples = (chunkSamples - overlapSamples).coerceAtLeast(1L)
        val chunks = mutableListOf<TranscriptionChunk>()
        var start = 0L
        var index = 0

        while (start < sampleCount) {
            val count = minOf(chunkSamples, sampleCount - start).toInt()
            val unique = if (sampleCount <= chunkSamples) {
                count.toLong()
            } else {
                minOf(stepSamples, sampleCount - start)
            }
            chunks += TranscriptionChunk(index, start, count, unique)
            if (start + count >= sampleCount) break
            start += stepSamples
            index++
        }
        return chunks
    }
}

class LocalWhisperTranscriber(context: Context) {
    private val decoder = AudioPcmDecoder(context.applicationContext)
    private val operationMutex = Mutex()
    private var loadedModelPath: String? = null
    private var loadedContexts: List<WhisperContext> = emptyList()

    suspend fun transcribe(
        audioUri: Uri,
        modelFile: File,
        options: LocalTranscriptionOptions = LocalTranscriptionOptions(),
        onProgress: suspend (LocalTranscriptionProgress) -> Unit = {},
    ): TranscriptionResult = operationMutex.withLock {
        require(modelFile.isFile) { "Download a Whisper model before transcribing." }
        val startedAt = SystemClock.elapsedRealtime()

        suspend fun report(
            stage: LocalTranscriptionProgress.Stage,
            percent: Int,
            processedMs: Long,
            totalMs: Long,
            skippedChunks: Int,
        ) {
            withContext(Dispatchers.Main.immediate) {
                onProgress(
                    LocalTranscriptionProgress(
                        stage = stage,
                        percent = percent.coerceIn(0, 100),
                        processedMs = processedMs.coerceAtLeast(0L),
                        totalMs = totalMs.coerceAtLeast(0L),
                        skippedChunks = skippedChunks.coerceAtLeast(0),
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    ),
                )
            }
        }

        report(LocalTranscriptionProgress.Stage.DECODING, 0, 0L, 0L, 0)
        val decoded = decoder.decodeToWhisperPcm(audioUri) { decodePercent ->
            report(
                stage = LocalTranscriptionProgress.Stage.DECODING,
                percent = (decodePercent * 10) / 100,
                processedMs = 0L,
                totalMs = 0L,
                skippedChunks = 0,
            )
        }

        try {
            coroutineContext.ensureActive()
            val chunks = TranscriptionChunkPlanner.plan(decoded.sampleCount, options)
            require(chunks.isNotEmpty()) { "The decoded audio was empty." }

            val workerCount = when {
                !options.turboMode -> 1
                chunks.size < 2 -> 1
                else -> 2
            }

            report(
                stage = LocalTranscriptionProgress.Stage.LOADING_MODEL,
                percent = 11,
                processedMs = 0L,
                totalMs = decoded.durationMs,
                skippedChunks = 0,
            )
            val contexts = ensureContexts(modelFile, workerCount)
            report(
                stage = LocalTranscriptionProgress.Stage.TRANSCRIBING,
                percent = 15,
                processedMs = 0L,
                totalMs = decoded.durationMs,
                skippedChunks = 0,
            )

            val completedChunks = AtomicInteger(0)
            val skippedChunks = AtomicInteger(0)
            val processedSamples = AtomicLong(0L)
            val workerResults = coroutineScope {
                contexts.mapIndexed { workerIndex, whisper ->
                    async(Dispatchers.Default) {
                        RandomAccessFile(decoded.file, "r").use { input ->
                            val segments = mutableListOf<TranscriptSegment>()
                            var chunkIndex = workerIndex
                            while (chunkIndex < chunks.size) {
                                coroutineContext.ensureActive()
                                val chunk = chunks[chunkIndex]
                                val samples = readChunk(input, chunk)
                                val silent = isNearSilence(samples, options.silenceRmsThreshold)

                                if (silent) {
                                    skippedChunks.incrementAndGet()
                                } else {
                                    val raw = whisper.transcribeData(samples, printTimestamp = true)
                                    segments += parseChunkSegments(
                                        raw = raw,
                                        chunk = chunk,
                                        overlapMs = options.overlapMs,
                                    )
                                }

                                processedSamples.addAndGet(chunk.uniqueSampleCount)
                                val complete = completedChunks.incrementAndGet()
                                val progress = 15 + (complete * 85 / chunks.size)
                                report(
                                    stage = LocalTranscriptionProgress.Stage.TRANSCRIBING,
                                    percent = progress,
                                    processedMs = processedSamples.get() * 1_000L / WHISPER_SAMPLE_RATE,
                                    totalMs = decoded.durationMs,
                                    skippedChunks = skippedChunks.get(),
                                )
                                chunkIndex += workerCount
                            }
                            segments
                        }
                    }
                }.awaitAll()
            }

            val segments = deduplicateSegments(workerResults.flatten())
            require(segments.isNotEmpty()) {
                "Whisper did not detect speech. Try the English model for English audio or disable Turbo mode."
            }

            val words = segments.flatMap { segment ->
                val tokens = Regex("\\S+").findAll(segment.text).map { it.value }.toList()
                if (tokens.isEmpty()) {
                    emptyList()
                } else {
                    val span = (segment.endMs - segment.startMs).coerceAtLeast(tokens.size * 70L)
                    tokens.mapIndexed { index, token ->
                        TranscriptWord(
                            text = token,
                            startMs = segment.startMs + span * index / tokens.size,
                            endMs = segment.startMs + span * (index + 1) / tokens.size,
                        )
                    }
                }
            }

            report(
                stage = LocalTranscriptionProgress.Stage.TRANSCRIBING,
                percent = 100,
                processedMs = decoded.durationMs,
                totalMs = decoded.durationMs,
                skippedChunks = skippedChunks.get(),
            )
            TranscriptionResult(
                text = segments.joinToString(" ") { it.text }
                    .replace(Regex("\\s+"), " ")
                    .trim(),
                language = if (modelFile.name.contains(".en-", ignoreCase = true)) "en" else null,
                durationMs = maxOf(decoded.durationMs, segments.maxOf { it.endMs }),
                words = words,
                segments = segments,
            )
        } finally {
            decoded.file.delete()
        }
    }

    suspend fun unloadModel() {
        operationMutex.withLock {
            releaseContexts()
        }
    }

    private suspend fun ensureContexts(modelFile: File, count: Int): List<WhisperContext> {
        val path = modelFile.canonicalPath
        if (loadedModelPath == path && loadedContexts.size == count) return loadedContexts

        releaseContexts()
        val created = mutableListOf<WhisperContext>()
        try {
            withContext(Dispatchers.Default) {
                repeat(count) {
                    coroutineContext.ensureActive()
                    created += WhisperContext.createContextFromFile(path)
                }
            }
        } catch (error: Throwable) {
            created.forEach { context -> runCatching { context.release() } }
            throw error
        }
        loadedModelPath = path
        loadedContexts = created
        return loadedContexts
    }

    private suspend fun releaseContexts() {
        val contexts = loadedContexts
        loadedContexts = emptyList()
        loadedModelPath = null
        contexts.forEach { context -> runCatching { context.release() } }
    }

    private fun readChunk(input: RandomAccessFile, chunk: TranscriptionChunk): FloatArray {
        input.seek(chunk.startSample * 2L)
        val bytes = ByteArray(chunk.sampleCount * 2)
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) break
            offset += read
        }
        val actualSamples = offset / 2
        return FloatArray(actualSamples) { index ->
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort() / 32768f
        }
    }

    private fun isNearSilence(samples: FloatArray, threshold: Float): Boolean {
        if (samples.isEmpty()) return true
        var sumSquares = 0.0
        var peak = 0f
        var count = 0
        var index = 0
        while (index < samples.size) {
            val value = abs(samples[index])
            peak = maxOf(peak, value)
            sumSquares += value * value
            count++
            index += 16
        }
        val rms = sqrt(sumSquares / count.coerceAtLeast(1)).toFloat()
        return peak < threshold * 4f && rms < threshold
    }

    private fun parseChunkSegments(
        raw: String,
        chunk: TranscriptionChunk,
        overlapMs: Int,
    ): List<TranscriptSegment> {
        val acceptFromMs = if (chunk.index == 0) chunk.startMs else chunk.startMs + overlapMs
        return raw.lineSequence().mapNotNull { line ->
            TIMESTAMPED_LINE.matchEntire(line.trim())?.let { match ->
                val localStart = parseTimestamp(match.groupValues[1])
                val localEnd = parseTimestamp(match.groupValues[2]).coerceAtLeast(localStart + 80L)
                val globalStart = chunk.startMs + localStart
                val globalEnd = (chunk.startMs + localEnd).coerceAtMost(chunk.endMs)
                val text = match.groupValues[3].trim()
                val midpoint = globalStart + (globalEnd - globalStart) / 2L
                if (text.isNotBlank() && midpoint >= acceptFromMs && globalEnd > globalStart) {
                    TranscriptSegment(text, globalStart, globalEnd)
                } else {
                    null
                }
            }
        }.toList()
    }

    private fun deduplicateSegments(input: List<TranscriptSegment>): List<TranscriptSegment> {
        val output = mutableListOf<TranscriptSegment>()
        input.sortedBy { it.startMs }.forEach { candidate ->
            val normalized = normalize(candidate.text)
            val duplicate = output.asReversed().take(3).any { previous ->
                normalize(previous.text) == normalized &&
                    abs(previous.startMs - candidate.startMs) < 3_000L
            }
            if (!duplicate && normalized.isNotBlank()) output += candidate
        }
        return output
    }

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    private fun parseTimestamp(value: String): Long {
        val parts = value.split(':')
        require(parts.size == 3) { "Invalid Whisper timestamp: $value" }
        val seconds = parts[2].toDouble()
        return (
            (parts[0].toLong() * 3_600L + parts[1].toLong() * 60L) * 1_000L +
                seconds * 1_000.0
            ).toLong()
    }

    private companion object {
        val TIMESTAMPED_LINE =
            Regex("^\\[(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) --> (\\d{2}:\\d{2}:\\d{2}\\.\\d{3})]:\\s*(.*)$")
    }
}
