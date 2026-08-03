package com.alastorkaneki.lyricvideomaker.data

import com.alastorkaneki.lyricvideomaker.model.LyricsCandidate
import java.io.RandomAccessFile
import kotlin.math.min

internal data class ParserResult(
    val container: String,
    val candidates: List<LyricsCandidate>,
    val warnings: List<String> = emptyList(),
)

internal object LyricsTagParsers {
    fun parse(raf: RandomAccessFile): ParserResult {
        val header = ByteArray(min(16L, raf.length()).toInt())
        raf.seek(0)
        raf.readFully(header)
        val candidates = mutableListOf<LyricsCandidate>()
        val warnings = mutableListOf<String>()
        var container = "Unknown"

        runCatching {
            when {
                header.startsWithAscii("ID3") -> {
                    container = "MP3 / ID3"
                    candidates += Id3LyricsParser.parseAt(raf, 0L, "ID3")
                    val flacOffset = Id3LyricsParser.totalSizeAt(raf, 0L)
                    if (flacOffset >= 0 && raf.matchesAscii(flacOffset, "fLaC")) {
                        container = "FLAC with ID3"
                        candidates += VorbisContainerParsers.parseFlac(raf, flacOffset)
                    }
                }
                header.startsWithAscii("fLaC") -> {
                    container = "FLAC"
                    candidates += VorbisContainerParsers.parseFlac(raf, 0L)
                }
                header.startsWithAscii("OggS") -> {
                    container = "Ogg / Opus"
                    candidates += VorbisContainerParsers.parseOgg(raf)
                }
                header.size >= 12 && header.copyOfRange(4, 8).decodeAscii() == "ftyp" -> {
                    container = "MP4 / M4A"
                    candidates += Mp4LyricsParser.parse(raf)
                }
                header.startsWithAscii("RIFF") || header.startsWithAscii("RF64") -> {
                    container = "WAV / RIFF"
                    candidates += RiffApeLyricsParsers.parseRiff(raf)
                }
                else -> container = "Audio"
            }
        }.onFailure { warnings += "Primary metadata parser: ${it.message ?: it::class.java.simpleName}" }

        runCatching { candidates += RiffApeLyricsParsers.parseApeV2(raf) }
            .onFailure { warnings += "APEv2 parser: ${it.message ?: it::class.java.simpleName}" }

        return ParserResult(container, candidates, warnings)
    }
}
