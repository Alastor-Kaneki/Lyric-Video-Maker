package com.alastorkaneki.lyricvideomaker.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionChunkPlannerTest {
    @Test
    fun splitsLongAudioIntoOverlappingWhisperWindowsWithoutDoubleCountingProgress() {
        val sampleCount = 60L * WHISPER_SAMPLE_RATE
        val chunks = TranscriptionChunkPlanner.plan(
            sampleCount,
            LocalTranscriptionOptions(chunkDurationMs = 28_000, overlapMs = 1_000),
        )

        assertEquals(3, chunks.size)
        assertEquals(0L, chunks[0].startSample)
        assertEquals(27L * WHISPER_SAMPLE_RATE, chunks[1].startSample)
        assertEquals(54L * WHISPER_SAMPLE_RATE, chunks[2].startSample)
        assertEquals(sampleCount, chunks.sumOf { it.uniqueSampleCount })
        assertTrue(chunks.all { it.sampleCount <= 28 * WHISPER_SAMPLE_RATE })
    }

    @Test
    fun keepsShortAudioInOneWindow() {
        val sampleCount = 5L * WHISPER_SAMPLE_RATE
        val chunks = TranscriptionChunkPlanner.plan(sampleCount, LocalTranscriptionOptions())

        assertEquals(1, chunks.size)
        assertEquals(sampleCount, chunks.single().sampleCount.toLong())
        assertEquals(sampleCount, chunks.single().uniqueSampleCount)
    }
}
