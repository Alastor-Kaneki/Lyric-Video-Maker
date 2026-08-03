package com.alastorkaneki.lyricvideomaker.data

import com.alastorkaneki.lyricvideomaker.model.LyricsCandidate
import java.io.RandomAccessFile

internal object RiffApeLyricsParsers {
    fun parseRiff(raf: RandomAccessFile): List<LyricsCandidate> {
        if (raf.length() < 12) return emptyList()
        var offset = 12L
        val out = mutableListOf<LyricsCandidate>()
        while (offset + 8 <= raf.length()) {
            raf.seek(offset)
            val id = ByteArray(4).also { raf.readFully(it) }.decodeAscii()
            val size = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFF_FFFFL
            val dataStart = offset + 8
            if (dataStart + size > raf.length()) break
            when (id.lowercase()) {
                "id3 " -> out += Id3LyricsParser.parseAt(raf, dataStart, "WAV ID3 chunk")
                "list" -> out += parseRiffInfo(raf, dataStart, dataStart + size)
            }
            offset = dataStart + size + (size and 1L)
        }
        return out
    }

    fun parseApeV2(raf: RandomAccessFile): List<LyricsCandidate> {
        if (raf.length() < 32) return emptyList()
        raf.seek(raf.length() - 32)
        val footer = ByteArray(32)
        raf.readFully(footer)
        if (!footer.startsWithAscii("APETAGEX")) return emptyList()
        val tagSize = int32le(footer, 12)
        val itemCount = int32le(footer, 16)
        if (tagSize !in 32..MAX_TAG_BYTES || itemCount !in 0..100_000 || tagSize > raf.length()) return emptyList()
        raf.seek(raf.length() - tagSize)
        val tag = ByteArray(tagSize)
        raf.readFully(tag)
        var cursor = if (tag.startsWithAscii("APETAGEX")) 32 else 0
        val out = mutableListOf<LyricsCandidate>()
        repeat(itemCount) {
            if (cursor + 8 > tag.size - 32) return@repeat
            val valueSize = int32le(tag, cursor)
            cursor += 8
            val keyEnd = tag.indexOfZero(cursor)
            if (keyEnd < 0 || valueSize < 0 || keyEnd + 1 + valueSize > tag.size) return@repeat
            val key = tag.copyOfRange(cursor, keyEnd).decodeAscii()
            cursor = keyEnd + 1
            val valueBytes = tag.copyOfRange(cursor, cursor + valueSize)
            cursor += valueSize
            if (isLyricsKey(key)) {
                val value = decodeUnknownText(valueBytes).trimNullsAndWhitespace()
                if (value.isNotBlank()) out += lyricsCandidate(value, "APEv2", key)
            }
        }
        return out
    }

    private fun parseRiffInfo(raf: RandomAccessFile, start: Long, end: Long): List<LyricsCandidate> {
        if (start + 4 > end) return emptyList()
        raf.seek(start)
        if (ByteArray(4).also { raf.readFully(it) }.decodeAscii() != "INFO") return emptyList()
        var offset = start + 4
        val out = mutableListOf<LyricsCandidate>()
        while (offset + 8 <= end) {
            raf.seek(offset)
            val key = ByteArray(4).also { raf.readFully(it) }.decodeAscii()
            val size = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFF_FFFFL
            if (offset + 8 + size > end) break
            if (isLyricsKey(key)) {
                val bytes = ByteArray(size.coerceAtMost(MAX_TAG_BYTES.toLong()).toInt())
                raf.readFully(bytes)
                val value = decodeUnknownText(bytes).trimNullsAndWhitespace()
                if (value.isNotBlank()) out += lyricsCandidate(value, "RIFF INFO", key)
            }
            offset += 8 + size + (size and 1L)
        }
        return out
    }
}
