package com.alastorkaneki.lyricvideomaker.model

import android.net.Uri

data class EmbeddedArtwork(
    val uri: Uri,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val byteCount: Int,
)

data class AudioAsset(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val durationMs: Long,
    val lyrics: LyricsExtractionResult,
    val embeddedArtwork: EmbeddedArtwork?,
)

data class TranscriptWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

data class TranscriptSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

data class TranscriptionResult(
    val text: String,
    val language: String?,
    val durationMs: Long,
    val words: List<TranscriptWord>,
    val segments: List<TranscriptSegment>,
)

data class AlignedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val matchedTranscript: Boolean,
)

data class AlignedLyricLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<AlignedWord>,
)

data class LyricsAlignmentResult(
    val lines: List<AlignedLyricLine>,
    val confidence: Float,
    val matchedWords: Int,
    val totalReferenceWords: Int,
    val sourceLabel: String,
) {
    val durationMs: Long get() = lines.maxOfOrNull { it.endMs } ?: 0L
}

enum class LyricsPosition { CENTER, LOWER_THIRD, BOTTOM }

data class RenderStyle(
    val position: LyricsPosition = LyricsPosition.LOWER_THIRD,
    val fontScale: Float = 1f,
    val showNextLine: Boolean = true,
    val showPreviousLine: Boolean = false,
    val karaokeHighlight: Boolean = true,
    val dimBackground: Float = 0f,
    val framesPerSecond: Int = 30,
)

data class OutputDimensions(
    val width: Int,
    val height: Int,
) {
    val label: String get() = "${width}×${height}"
}
