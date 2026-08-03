package com.alastorkaneki.lyricvideomaker.alignment

import com.alastorkaneki.lyricvideomaker.data.LrcParser
import com.alastorkaneki.lyricvideomaker.model.AlignedLyricLine
import com.alastorkaneki.lyricvideomaker.model.AlignedWord
import com.alastorkaneki.lyricvideomaker.model.LyricsAlignmentResult
import com.alastorkaneki.lyricvideomaker.model.TranscriptWord
import com.alastorkaneki.lyricvideomaker.model.TranscriptionResult
import kotlin.math.max

object LyricsAlignmentEngine {
    fun align(
        referenceLyrics: String,
        transcription: TranscriptionResult?,
        audioDurationMs: Long,
        sourceLabel: String,
    ): LyricsAlignmentResult {
        val timed = LrcParser.parse(referenceLyrics)
        if (timed.isNotEmpty()) return fromTimedLyrics(timed.map { it.startTimeMs to it.text }, audioDurationMs, sourceLabel)
        if (referenceLyrics.isBlank()) {
            requireNotNull(transcription) { "Provide lyrics or transcribe the audio first." }
            return fromTranscription(transcription)
        }
        requireNotNull(transcription) { "Plain lyrics need a transcription before they can be timed." }
        return alignPlainLyrics(referenceLyrics, transcription, audioDurationMs, sourceLabel)
    }

    private fun fromTimedLyrics(
        timedLines: List<Pair<Long, String>>,
        audioDurationMs: Long,
        sourceLabel: String,
    ): LyricsAlignmentResult {
        val duration = audioDurationMs.coerceAtLeast((timedLines.lastOrNull()?.first ?: 0L) + 2_000L)
        val lines = timedLines.mapIndexed { index, (start, text) ->
            val nextStart = timedLines.getOrNull(index + 1)?.first ?: duration
            val end = max(start + 300L, nextStart)
            val tokens = visibleTokens(text)
            val span = (end - start).coerceAtLeast(tokens.size * 80L)
            val words = tokens.mapIndexed { wordIndex, token ->
                val wordStart = start + span * wordIndex / tokens.size.coerceAtLeast(1)
                val wordEnd = start + span * (wordIndex + 1) / tokens.size.coerceAtLeast(1)
                AlignedWord(token, wordStart, wordEnd, true)
            }
            AlignedLyricLine(text, start, end, words)
        }
        return LyricsAlignmentResult(lines, 1f, lines.sumOf { it.words.size }, lines.sumOf { it.words.size }, sourceLabel)
    }

    private fun fromTranscription(transcription: TranscriptionResult): LyricsAlignmentResult {
        if (transcription.words.isEmpty()) {
            val lines = transcription.segments.map { segment ->
                val words = distributeWords(visibleTokens(segment.text), segment.startMs, segment.endMs, true)
                AlignedLyricLine(segment.text, segment.startMs, segment.endMs, words)
            }
            return LyricsAlignmentResult(lines, 1f, lines.sumOf { it.words.size }, lines.sumOf { it.words.size }, "Transcription")
        }
        val groups = mutableListOf<List<TranscriptWord>>()
        var current = mutableListOf<TranscriptWord>()
        transcription.words.forEachIndexed { index, word ->
            current += word
            val next = transcription.words.getOrNull(index + 1)
            val punctuationBreak = word.text.lastOrNull() in listOf('.', '!', '?', ';', ':')
            val gapBreak = next != null && next.startMs - word.endMs > 850L
            if (current.size >= 8 || punctuationBreak || gapBreak) {
                groups += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) groups += current
        val lines = groups.map { group ->
            AlignedLyricLine(
                text = group.joinToString(" ") { it.text }.cleanSpacing(),
                startMs = group.first().startMs,
                endMs = group.last().endMs.coerceAtLeast(group.first().startMs + 200L),
                words = group.map { AlignedWord(it.text, it.startMs, it.endMs, true) },
            )
        }
        return LyricsAlignmentResult(lines, 1f, lines.sumOf { it.words.size }, lines.sumOf { it.words.size }, "Transcription")
    }

    private fun alignPlainLyrics(
        referenceLyrics: String,
        transcription: TranscriptionResult,
        audioDurationMs: Long,
        sourceLabel: String,
    ): LyricsAlignmentResult {
        val referenceLines = referenceLyrics.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.matches(Regex("^\\s*\\[[^]]+]\\s*$")) }
            .map { line -> ReferenceLine(line, visibleTokens(line)) }
            .filter { it.words.isNotEmpty() }
            .toList()
        val flatReference = referenceLines.flatMapIndexed { lineIndex, line ->
            line.words.mapIndexed { wordIndex, text -> ReferenceWord(text, normalize(text), lineIndex, wordIndex) }
        }
        require(flatReference.isNotEmpty()) { "The provided lyrics do not contain any words." }
        require(flatReference.size <= 2_500) { "The lyrics are too long for automatic alignment." }
        val transcriptWords = transcription.words.ifEmpty {
            transcription.segments.flatMap { segment ->
                val tokens = visibleTokens(segment.text)
                val span = (segment.endMs - segment.startMs).coerceAtLeast(tokens.size * 80L)
                tokens.mapIndexed { index, token ->
                    TranscriptWord(
                        token,
                        segment.startMs + span * index / tokens.size.coerceAtLeast(1),
                        segment.startMs + span * (index + 1) / tokens.size.coerceAtLeast(1),
                    )
                }
            }
        }
        require(transcriptWords.isNotEmpty()) { "The transcription did not return word or segment timestamps." }
        require(transcriptWords.size <= 3_500) { "The transcription is too long for automatic alignment." }

        val mapping = sequenceAlign(flatReference, transcriptWords)
        val duration = maxOf(audioDurationMs, transcription.durationMs, transcriptWords.last().endMs)
        val alignedFlat = fillWordTimings(flatReference, transcriptWords, mapping, duration)
        val lines = referenceLines.mapIndexed { lineIndex, line ->
            val words = alignedFlat.filter { it.lineIndex == lineIndex }.map { it.word }
            val start = words.minOfOrNull { it.startMs } ?: 0L
            val end = words.maxOfOrNull { it.endMs }?.coerceAtLeast(start + 200L) ?: start + 1_000L
            AlignedLyricLine(line.original, start, end, words)
        }
        val matches = mapping.count { it >= 0 }
        return LyricsAlignmentResult(
            lines = lines,
            confidence = (matches.toFloat() / flatReference.size.toFloat()).coerceIn(0f, 1f),
            matchedWords = matches,
            totalReferenceWords = flatReference.size,
            sourceLabel = sourceLabel,
        )
    }

    private fun sequenceAlign(reference: List<ReferenceWord>, transcript: List<TranscriptWord>): IntArray {
        val n = reference.size
        val m = transcript.size
        val cols = m + 1
        val costs = IntArray((n + 1) * cols)
        for (i in 0..n) costs[i * cols] = i
        for (j in 0..m) costs[j] = j
        for (i in 1..n) {
            val a = reference[i - 1].normalized
            for (j in 1..m) {
                val b = normalize(transcript[j - 1].text)
                val substitution = when {
                    a == b -> 0
                    nearMatch(a, b) -> 1
                    else -> 2
                }
                val replace = costs[(i - 1) * cols + j - 1] + substitution
                val delete = costs[(i - 1) * cols + j] + 1
                val insert = costs[i * cols + j - 1] + 1
                costs[i * cols + j] = minOf(replace, delete, insert)
            }
        }
        val mapping = IntArray(n) { -1 }
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                val a = reference[i - 1].normalized
                val b = normalize(transcript[j - 1].text)
                val substitution = when {
                    a == b -> 0
                    nearMatch(a, b) -> 1
                    else -> 2
                }
                if (costs[i * cols + j] == costs[(i - 1) * cols + j - 1] + substitution) {
                    if (substitution <= 1) mapping[i - 1] = j - 1
                    i--
                    j--
                    continue
                }
            }
            if (i > 0 && costs[i * cols + j] == costs[(i - 1) * cols + j] + 1) i-- else if (j > 0) j--
        }
        return mapping
    }

    private fun fillWordTimings(
        reference: List<ReferenceWord>,
        transcript: List<TranscriptWord>,
        mapping: IntArray,
        durationMs: Long,
    ): List<PlacedReferenceWord> {
        val matched = mapping.indices.filter { mapping[it] >= 0 }
        return reference.indices.map { index ->
            val direct = mapping[index]
            val startEnd = if (direct >= 0) {
                transcript[direct].startMs to transcript[direct].endMs.coerceAtLeast(transcript[direct].startMs + 60L)
            } else {
                val previous = matched.lastOrNull { it < index }
                val next = matched.firstOrNull { it > index }
                when {
                    previous != null && next != null -> {
                        val previousEnd = transcript[mapping[previous]].endMs
                        val nextStart = transcript[mapping[next]].startMs.coerceAtLeast(previousEnd)
                        val slots = next - previous
                        val offset = index - previous
                        val start = previousEnd + (nextStart - previousEnd) * (offset - 1) / slots.coerceAtLeast(1)
                        val end = previousEnd + (nextStart - previousEnd) * offset / slots.coerceAtLeast(1)
                        start to end.coerceAtLeast(start + 60L)
                    }
                    next != null -> {
                        val boundary = transcript[mapping[next]].startMs
                        val slots = next.coerceAtLeast(1)
                        val start = boundary * index / slots
                        val end = boundary * (index + 1) / slots
                        start to end.coerceAtLeast(start + 60L)
                    }
                    previous != null -> {
                        val boundary = transcript[mapping[previous]].endMs
                        val remaining = (reference.size - previous - 1).coerceAtLeast(1)
                        val offset = index - previous - 1
                        val endBoundary = durationMs.coerceAtLeast(boundary + remaining * 80L)
                        val start = boundary + (endBoundary - boundary) * offset / remaining
                        val end = boundary + (endBoundary - boundary) * (offset + 1) / remaining
                        start to end.coerceAtLeast(start + 60L)
                    }
                    else -> {
                        val start = durationMs * index / reference.size
                        val end = durationMs * (index + 1) / reference.size
                        start to end.coerceAtLeast(start + 60L)
                    }
                }
            }
            PlacedReferenceWord(
                lineIndex = reference[index].lineIndex,
                word = AlignedWord(reference[index].original, startEnd.first, startEnd.second, direct >= 0),
            )
        }
    }

    private fun distributeWords(tokens: List<String>, start: Long, end: Long, matched: Boolean): List<AlignedWord> {
        val duration = (end - start).coerceAtLeast(tokens.size * 80L)
        return tokens.mapIndexed { index, token ->
            AlignedWord(
                token,
                start + duration * index / tokens.size.coerceAtLeast(1),
                start + duration * (index + 1) / tokens.size.coerceAtLeast(1),
                matched,
            )
        }
    }

    private fun visibleTokens(text: String): List<String> = Regex("\\S+").findAll(text).map { it.value }.toList()

    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    private fun nearMatch(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a.startsWith(b) || b.startsWith(a)) return minOf(a.length, b.length) >= 4
        if (max(a.length, b.length) > 12 || minOf(a.length, b.length) < 4) return false
        return levenshtein(a, b) <= 1
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        a.forEachIndexed { i, charA ->
            current[0] = i + 1
            b.forEachIndexed { j, charB ->
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (charA == charB) 0 else 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun String.cleanSpacing(): String = replace(Regex("\\s+([,.!?;:])"), "$1").trim()

    private data class ReferenceLine(val original: String, val words: List<String>)
    private data class ReferenceWord(val original: String, val normalized: String, val lineIndex: Int, val wordIndex: Int)
    private data class PlacedReferenceWord(val lineIndex: Int, val word: AlignedWord)
}
