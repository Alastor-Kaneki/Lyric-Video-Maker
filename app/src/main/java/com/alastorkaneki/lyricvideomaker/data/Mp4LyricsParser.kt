package com.alastorkaneki.lyricvideomaker.data

import com.alastorkaneki.lyricvideomaker.model.LyricsCandidate
import java.io.RandomAccessFile

internal object Mp4LyricsParser {
    fun parse(raf: RandomAccessFile): List<LyricsCandidate> {
        val out = mutableListOf<LyricsCandidate>()
        scanBoxes(raf, 0L, raf.length(), 0, out)
        return out
    }

    private fun scanBoxes(raf: RandomAccessFile, start: Long, end: Long, depth: Int, out: MutableList<LyricsCandidate>) {
        if (depth > 10) return
        var offset = start
        while (offset + 8 <= end && offset + 8 <= raf.length()) {
            raf.seek(offset)
            var size = raf.readInt().toLong() and 0xFFFF_FFFFL
            val type = ByteArray(4).also { raf.readFully(it) }.decodeLatin1()
            var header = 8L
            if (size == 1L) {
                if (offset + 16 > end) break
                size = raf.readLong()
                header = 16L
            } else if (size == 0L) {
                size = end - offset
            }
            if (size < header || offset + size > end || offset + size > raf.length()) break
            val payloadStart = offset + header
            val payloadEnd = offset + size

            when (type) {
                "©lyr", "lyrc" -> parseDataChildren(raf, payloadStart, payloadEnd)?.let {
                    out += lyricsCandidate(it, "MP4 metadata", type)
                }
                "----" -> parseFreeform(raf, payloadStart, payloadEnd)?.let { (name, value) ->
                    if (isLyricsKey(name)) out += lyricsCandidate(value, "MP4 freeform", name)
                }
                "moov", "udta", "ilst" -> scanBoxes(raf, payloadStart, payloadEnd, depth + 1, out)
                "meta" -> scanBoxes(raf, (payloadStart + 4).coerceAtMost(payloadEnd), payloadEnd, depth + 1, out)
            }
            offset += size
        }
    }

    private fun parseDataChildren(raf: RandomAccessFile, start: Long, end: Long): String? {
        var offset = start
        while (offset + 8 <= end) {
            raf.seek(offset)
            val size = raf.readInt().toLong() and 0xFFFF_FFFFL
            val type = ByteArray(4).also { raf.readFully(it) }.decodeLatin1()
            if (size < 8 || offset + size > end) break
            if (type == "data" && size > 16) {
                raf.seek(offset + 16)
                val length = (size - 16).coerceAtMost(MAX_TAG_BYTES.toLong()).toInt()
                val bytes = ByteArray(length)
                raf.readFully(bytes)
                return decodeUnknownText(bytes).trimNullsAndWhitespace().takeIf { it.isNotBlank() }
            }
            offset += size
        }
        return null
    }

    private fun parseFreeform(raf: RandomAccessFile, start: Long, end: Long): Pair<String, String>? {
        var offset = start
        var name: String? = null
        var value: String? = null
        while (offset + 8 <= end) {
            raf.seek(offset)
            val size = raf.readInt().toLong() and 0xFFFF_FFFFL
            val type = ByteArray(4).also { raf.readFully(it) }.decodeLatin1()
            if (size < 8 || offset + size > end) break
            when (type) {
                "name" -> if (size >= 12) {
                    raf.seek(offset + 12)
                    val bytes = ByteArray((size - 12).coerceAtMost(MAX_TAG_BYTES.toLong()).toInt())
                    raf.readFully(bytes)
                    name = decodeUnknownText(bytes).trimNullsAndWhitespace()
                }
                "data" -> if (size >= 16) {
                    raf.seek(offset + 16)
                    val bytes = ByteArray((size - 16).coerceAtMost(MAX_TAG_BYTES.toLong()).toInt())
                    raf.readFully(bytes)
                    value = decodeUnknownText(bytes).trimNullsAndWhitespace()
                }
            }
            offset += size
        }
        return if (!name.isNullOrBlank() && !value.isNullOrBlank()) name to value else null
    }
}
