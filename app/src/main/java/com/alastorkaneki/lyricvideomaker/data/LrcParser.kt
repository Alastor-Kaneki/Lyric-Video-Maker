package com.alastorkaneki.lyricvideomaker.data

import com.alastorkaneki.lyricvideomaker.model.TimedLyricLine

internal object LrcParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<TimedLyricLine> {
        val globalOffset = offset.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<TimedLyricLine>()

        text.lineSequence().forEach lineLoop@ { rawLine ->
            val matches = timestamp.findAll(rawLine).toList()
            if (matches.isEmpty()) return@lineLoop
            val lyric = timestamp.replace(rawLine, "").trim()
            if (lyric.isBlank()) return@lineLoop

            matches.forEach timestampLoop@ { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@timestampLoop
                val seconds = match.groupValues[2].toLongOrNull() ?: return@timestampLoop
                val fractionText = match.groupValues.getOrNull(3).orEmpty()
                val millis = when (fractionText.length) {
                    0 -> 0L
                    1 -> fractionText.toLong() * 100L
                    2 -> fractionText.toLong() * 10L
                    else -> fractionText.take(3).padEnd(3, '0').toLong()
                }
                val time = (minutes * 60_000L + seconds * 1_000L + millis + globalOffset)
                    .coerceAtLeast(0L)
                lines += TimedLyricLine(time, lyric)
            }
        }

        return lines.distinctBy { it.startTimeMs to it.text }.sortedBy { it.startTimeMs }
    }
}
