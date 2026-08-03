package com.alastorkaneki.lyricvideomaker.data

import com.alastorkaneki.lyricvideomaker.model.LyricsCandidate
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

internal object VorbisContainerParsers {
    fun parseFlac(raf: RandomAccessFile, start: Long): List<LyricsCandidate> {
        if (!raf.matchesAscii(start, "fLaC")) return emptyList()
        var offset = start + 4
        var last = false
        val out = mutableListOf<LyricsCandidate>()
        while (!last && offset + 4 <= raf.length()) {
            raf.seek(offset)
            val first = raf.readUnsignedByte()
            last = (first and 0x80) != 0
            val type = first and 0x7F
            val length = (raf.readUnsignedByte() shl 16) or (raf.readUnsignedByte() shl 8) or raf.readUnsignedByte()
            if (length < 0 || length > MAX_TAG_BYTES || offset + 4L + length > raf.length()) break
            if (type == 4) {
                val block = ByteArray(length)
                raf.readFully(block)
                out += parseComments(block, "FLAC Vorbis comments", 0)
            }
            offset += 4L + length
        }
        return out
    }

    fun parseOgg(raf: RandomAccessFile): List<LyricsCandidate> {
        raf.seek(0)
        val packets = mutableListOf<ByteArray>()
        var packet = ByteArrayOutputStream()
        while (raf.filePointer + 27 <= raf.length() && packets.size < 8) {
            val capture = ByteArray(4)
            raf.readFully(capture)
            if (capture.decodeAscii() != "OggS") break
            raf.skipBytes(22)
            val segments = raf.readUnsignedByte()
            val laces = ByteArray(segments)
            raf.readFully(laces)
            for (laceByte in laces) {
                val lace = laceByte.toInt() and 0xFF
                if (raf.filePointer + lace > raf.length()) return packetsToLyrics(packets)
                val data = ByteArray(lace)
                raf.readFully(data)
                packet.write(data)
                if (lace < 255) {
                    packets += packet.toByteArray()
                    packet = ByteArrayOutputStream()
                }
            }
        }
        return packetsToLyrics(packets)
    }

    private fun packetsToLyrics(packets: List<ByteArray>): List<LyricsCandidate> {
        packets.firstOrNull { it.size >= 8 && it.copyOfRange(0, 8).decodeAscii() == "OpusTags" }
            ?.let { return parseComments(it, "OpusTags", 8) }
        packets.firstOrNull { it.size >= 7 && it[0].toInt() == 3 && it.copyOfRange(1, 7).decodeAscii() == "vorbis" }
            ?.let { return parseComments(it, "Ogg Vorbis comments", 7) }
        return emptyList()
    }

    private fun parseComments(data: ByteArray, source: String, start: Int): List<LyricsCandidate> {
        var cursor = start
        if (cursor + 4 > data.size) return emptyList()
        val vendorLength = int32le(data, cursor)
        cursor += 4
        if (vendorLength < 0 || cursor + vendorLength + 4 > data.size) return emptyList()
        cursor += vendorLength
        val count = int32le(data, cursor)
        cursor += 4
        if (count !in 0..100_000) return emptyList()
        val out = mutableListOf<LyricsCandidate>()
        repeat(count) {
            if (cursor + 4 > data.size) return@repeat
            val length = int32le(data, cursor)
            cursor += 4
            if (length < 0 || cursor + length > data.size) return@repeat
            val entry = data.copyOfRange(cursor, cursor + length).toString(StandardCharsets.UTF_8)
            cursor += length
            val split = entry.indexOf('=')
            if (split <= 0) return@repeat
            val key = entry.substring(0, split)
            val value = entry.substring(split + 1).trimNullsAndWhitespace()
            if (isLyricsKey(key) && value.isNotBlank()) out += lyricsCandidate(value, source, key)
        }
        return out
    }
}
