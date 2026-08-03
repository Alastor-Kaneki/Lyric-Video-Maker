package com.alastorkaneki.lyricvideomaker.data

import com.alastorkaneki.lyricvideomaker.alignment.LyricsAlignmentEngine
import com.alastorkaneki.lyricvideomaker.model.TranscriptWord
import com.alastorkaneki.lyricvideomaker.model.TranscriptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsAlignmentEngineTest {
    @Test
    fun alignsProvidedLinesAgainstTranscriptWords() {
        val transcript = TranscriptionResult(
            text = "hello from the other side",
            language = "en",
            durationMs = 3_000,
            words = listOf(
                TranscriptWord("hello", 100, 500),
                TranscriptWord("from", 600, 900),
                TranscriptWord("the", 950, 1_100),
                TranscriptWord("other", 1_200, 1_700),
                TranscriptWord("side", 1_800, 2_300),
            ),
            segments = emptyList(),
        )
        val result = LyricsAlignmentEngine.align("Hello from\nthe other side", transcript, 3_000, "test")
        assertEquals(2, result.lines.size)
        assertTrue(result.confidence > 0.9f)
        assertEquals(100, result.lines.first().startMs)
        assertEquals(2_300, result.lines.last().endMs)
    }

    @Test
    fun preservesLrcTimingWithoutTranscription() {
        val result = LyricsAlignmentEngine.align("[00:01.00]First line\n[00:03.50]Second line", null, 6_000, "LRC")
        assertEquals(1_000, result.lines[0].startMs)
        assertEquals(3_500, result.lines[1].startMs)
        assertEquals(1f, result.confidence)
    }
}
