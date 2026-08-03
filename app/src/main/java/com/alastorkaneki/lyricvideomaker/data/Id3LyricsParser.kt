package com.alastorkaneki.lyricvideomaker.data

import com.alastorkaneki.lyricvideomaker.model.LyricsCandidate
import com.alastorkaneki.lyricvideomaker.model.TimedLyricLine
import java.io.RandomAccessFile

internal object Id3LyricsParser {
    fun parseAt(raf: RandomAccessFile, start: Long, source: String): List<LyricsCandidate> {
        if (!raf.matchesAscii(start, "ID3")) return emptyList()
        raf.seek(start)
        val header = ByteArray(10)
        raf.readFully(header)
        val version = header[3].toInt() and 0xFF
        if (version !in 2..4) return emptyList()
        val flags = header[5].toInt() and 0xFF
        val declaredSize = synchsafe(header, 6)
        if (declaredSize <= 0 || declaredSize > MAX_TAG_BYTES) return emptyList()
        val raw = ByteArray(declaredSize)
        raf.readFully(raw)
        val tag = if ((flags and 0x80) != 0) removeUnsynchronization(raw) else raw
        var offset = 0

        if ((flags and 0x40) != 0 && version >= 3 && tag.size >= 4) {
            val extSize = if (version == 4) synchsafe(tag, 0) else int32be(tag, 0)
            offset = (if (version == 4) extSize else extSize + 4).coerceIn(0, tag.size)
        }

        val out = mutableListOf<LyricsCandidate>()
        while (offset < tag.size) {
            val idLength = if (version == 2) 3 else 4
            val headerLength = if (version == 2) 6 else 10
            if (offset + headerLength > tag.size) break
            val id = tag.copyOfRange(offset, offset + idLength).decodeAscii()
            if (id.all { it == '\u0000' } || id.any { !it.isLetterOrDigit() }) break
            val size = when (version) {
                2 -> int24be(tag, offset + 3)
                4 -> synchsafe(tag, offset + 4)
                else -> int32be(tag, offset + 4)
            }
            if (size <= 0 || offset + headerLength + size > tag.size) break
            val frame = tag.copyOfRange(offset + headerLength, offset + headerLength + size)
            when (id) {
                "USLT", "ULT" -> parseUslt(frame)?.let { out += lyricsCandidate(it, source, id) }
                "SYLT", "SLT" -> parseSylt(frame)?.let { (text, timed) -> out += LyricsCandidate(text, timed, source, id) }
                "TXXX", "TXX" -> parseTxxx(frame)?.let { (description, value) ->
                    if (description.contains("lyric", ignoreCase = true)) out += lyricsCandidate(value, source, "$id:$description")
                }
            }
            offset += headerLength + size
        }
        return out
    }

    fun totalSizeAt(raf: RandomAccessFile, start: Long): Long {
        if (!raf.matchesAscii(start, "ID3") || start + 10 > raf.length()) return -1
        raf.seek(start + 6)
        val size = ByteArray(4)
        raf.readFully(size)
        return start + 10 + synchsafe(size, 0)
    }

    private fun parseUslt(frame: ByteArray): String? {
        if (frame.size < 4) return null
        val encoding = frame[0].toInt() and 0xFF
        val textStart = findTextTerminator(frame, 4, encoding)
        if (textStart < 0) return null
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        return decodeId3Text(frame.copyOfRange((textStart + step).coerceAtMost(frame.size), frame.size), encoding)
            .trimNullsAndWhitespace().takeIf { it.isNotBlank() }
    }

    private fun parseSylt(frame: ByteArray): Pair<String, List<TimedLyricLine>>? {
        if (frame.size < 7) return null
        val encoding = frame[0].toInt() and 0xFF
        val timestampFormat = frame[4].toInt() and 0xFF
        var cursor = 6
        val descriptorEnd = findTextTerminator(frame, cursor, encoding)
        if (descriptorEnd < 0) return null
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        cursor = descriptorEnd + step
        val timed = mutableListOf<TimedLyricLine>()
        val plain = mutableListOf<String>()

        while (cursor < frame.size) {
            val textEnd = findTextTerminator(frame, cursor, encoding)
            if (textEnd < 0 || textEnd + step + 4 > frame.size) break
            val piece = decodeId3Text(frame.copyOfRange(cursor, textEnd), encoding).trimNullsAndWhitespace()
            val time = uint32be(frame, textEnd + step)
            if (piece.isNotBlank()) {
                plain += piece
                if (timestampFormat == 2) timed += TimedLyricLine(time, piece)
            }
            cursor = textEnd + step + 4
        }
        if (plain.isEmpty()) return null
        return plain.joinToString("\n") to timed.sortedBy { it.startTimeMs }
    }

    private fun parseTxxx(frame: ByteArray): Pair<String, String>? {
        if (frame.isEmpty()) return null
        val encoding = frame[0].toInt() and 0xFF
        val end = findTextTerminator(frame, 1, encoding)
        if (end < 0) return null
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        val description = decodeId3Text(frame.copyOfRange(1, end), encoding).trimNullsAndWhitespace()
        val value = decodeId3Text(frame.copyOfRange((end + step).coerceAtMost(frame.size), frame.size), encoding)
            .trimNullsAndWhitespace()
        return description to value
    }
}
