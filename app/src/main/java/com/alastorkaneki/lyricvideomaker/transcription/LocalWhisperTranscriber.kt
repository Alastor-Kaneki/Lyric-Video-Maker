package com.alastorkaneki.lyricvideomaker.transcription

import android.content.Context
import android.net.Uri
import com.alastorkaneki.lyricvideomaker.model.TranscriptSegment
import com.alastorkaneki.lyricvideomaker.model.TranscriptWord
import com.alastorkaneki.lyricvideomaker.model.TranscriptionResult
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalWhisperTranscriber(context: Context) {
    private val decoder = AudioPcmDecoder(context.applicationContext)

    suspend fun transcribe(audioUri: Uri, modelFile: java.io.File): TranscriptionResult {
        require(modelFile.isFile) { "Download a Whisper model before transcribing." }
        val samples = decoder.decodeToWhisperSamples(audioUri)
        val durationMs = samples.size * 1_000L / 16_000L
        val raw = withContext(Dispatchers.Default) {
            val whisper = WhisperContext.createContextFromFile(modelFile.absolutePath)
            try {
                whisper.transcribeData(samples, printTimestamp = true)
            } finally {
                whisper.release()
            }
        }
        return parse(raw, durationMs)
    }

    private fun parse(raw: String, durationMs: Long): TranscriptionResult {
        val segments = raw.lineSequence().mapNotNull { line ->
            TIMESTAMPED_LINE.matchEntire(line.trim())?.let { match ->
                val start = parseTimestamp(match.groupValues[1])
                val end = parseTimestamp(match.groupValues[2]).coerceAtLeast(start + 80L)
                val text = match.groupValues[3].trim()
                text.takeIf(String::isNotBlank)?.let { TranscriptSegment(it, start, end) }
            }
        }.toList()

        val fallbackSegments = if (segments.isNotEmpty()) segments else {
            raw.trim().takeIf(String::isNotBlank)?.let { listOf(TranscriptSegment(it, 0L, durationMs)) }.orEmpty()
        }
        require(fallbackSegments.isNotEmpty()) { "Whisper did not detect any speech in this audio." }

        val words = fallbackSegments.flatMap { segment ->
            val tokens = Regex("\\S+").findAll(segment.text).map { it.value }.toList()
            if (tokens.isEmpty()) emptyList() else {
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
        val finalDuration = maxOf(durationMs, fallbackSegments.maxOf { it.endMs })
        return TranscriptionResult(
            text = fallbackSegments.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim(),
            language = null,
            durationMs = finalDuration,
            words = words,
            segments = fallbackSegments,
        )
    }

    private fun parseTimestamp(value: String): Long {
        val parts = value.split(':')
        require(parts.size == 3) { "Invalid Whisper timestamp: $value" }
        val seconds = parts[2].toDouble()
        return ((parts[0].toLong() * 3_600L + parts[1].toLong() * 60L) * 1_000L + seconds * 1_000.0).toLong()
    }

    private companion object {
        val TIMESTAMPED_LINE = Regex("^\\[(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) --> (\\d{2}:\\d{2}:\\d{2}\\.\\d{3})]:\\s*(.*)$")
    }
}
