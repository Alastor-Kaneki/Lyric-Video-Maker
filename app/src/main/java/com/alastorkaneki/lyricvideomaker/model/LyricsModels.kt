package com.alastorkaneki.lyricvideomaker.model

data class TimedLyricLine(
    val startTimeMs: Long,
    val text: String,
)

data class LyricsCandidate(
    val text: String,
    val timedLines: List<TimedLyricLine> = emptyList(),
    val source: String,
    val tagName: String,
) {
    val isSynchronized: Boolean get() = timedLines.isNotEmpty()
    val displayName: String
        get() = buildString {
            append(source)
            append(" • ")
            append(tagName)
            if (isSynchronized) append(" • timed")
        }
}

data class LyricsExtractionResult(
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String?,
    val container: String,
    val candidates: List<LyricsCandidate>,
    val warnings: List<String> = emptyList(),
) {
    val bestCandidate: LyricsCandidate?
        get() = candidates.maxWithOrNull(
            compareBy<LyricsCandidate> { it.isSynchronized }
                .thenBy { it.text.length },
        )
}
