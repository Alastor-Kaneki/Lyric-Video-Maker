package com.alastorkaneki.lyricvideomaker.data

import com.alastorkaneki.lyricvideomaker.model.LyricsCandidate
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal const val MAX_TAG_BYTES = 16 * 1024 * 1024

internal fun lyricsCandidate(text: String, source: String, tag: String): LyricsCandidate {
    val cleaned = text.trimNullsAndWhitespace()
    return LyricsCandidate(
        text = cleaned,
        timedLines = LrcParser.parse(cleaned),
        source = source,
        tagName = tag,
    )
}

internal fun isLyricsKey(key: String): Boolean {
    val normalized = key.trim().replace("_", "").replace("-", "").lowercase()
    return normalized in setOf(
        "lyrics", "lyric", "unsyncedlyrics", "unsynchronizedlyrics",
        "syncedlyrics", "synchronizedlyrics", "lyrc", "ilyr", "uslt", "sylt",
    )
}

internal fun removeUnsynchronization(data: ByteArray): ByteArray {
    val output = ByteArrayOutputStream(data.size)
    var i = 0
    while (i < data.size) {
        output.write(data[i].toInt())
        if ((data[i].toInt() and 0xFF) == 0xFF && i + 1 < data.size && data[i + 1].toInt() == 0) i++
        i++
    }
    return output.toByteArray()
}

internal fun findTextTerminator(data: ByteArray, start: Int, encoding: Int): Int {
    if (encoding == 1 || encoding == 2) {
        var i = start
        while (i + 1 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
            i += 2
        }
    } else {
        for (i in start until data.size) if (data[i].toInt() == 0) return i
    }
    return -1
}

internal fun decodeId3Text(bytes: ByteArray, encoding: Int): String = when (encoding) {
    0 -> bytes.toString(Charsets.ISO_8859_1)
    1 -> bytes.toString(Charset.forName("UTF-16"))
    2 -> bytes.toString(Charset.forName("UTF-16BE"))
    3 -> bytes.toString(StandardCharsets.UTF_8)
    else -> decodeUnknownText(bytes)
}

internal fun decodeUnknownText(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    return when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> bytes.toString(Charset.forName("UTF-16LE"))
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> bytes.toString(Charset.forName("UTF-16BE"))
        else -> bytes.toString(StandardCharsets.UTF_8)
    }
}

internal fun String.trimNullsAndWhitespace(): String = trim { it.isWhitespace() || it == '\u0000' }
internal fun ByteArray.startsWithAscii(value: String): Boolean = size >= value.length && copyOfRange(0, value.length).decodeAscii() == value
internal fun ByteArray.decodeAscii(): String = toString(StandardCharsets.US_ASCII)
internal fun ByteArray.decodeLatin1(): String = toString(Charsets.ISO_8859_1)
internal fun ByteArray.indexOfZero(startIndex: Int): Int {
    for (index in startIndex until size) if (this[index].toInt() == 0) return index
    return -1
}

internal fun RandomAccessFile.matchesAscii(offset: Long, value: String): Boolean {
    if (offset < 0 || offset + value.length > length()) return false
    seek(offset)
    val bytes = ByteArray(value.length)
    readFully(bytes)
    return bytes.decodeAscii() == value
}

internal fun synchsafe(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0x7F) shl 21) or
        ((data[offset + 1].toInt() and 0x7F) shl 14) or
        ((data[offset + 2].toInt() and 0x7F) shl 7) or
        (data[offset + 3].toInt() and 0x7F)

internal fun int24be(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 16) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        (data[offset + 2].toInt() and 0xFF)

internal fun int32be(data: ByteArray, offset: Int): Int = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
internal fun int32le(data: ByteArray, offset: Int): Int = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
internal fun uint32be(data: ByteArray, offset: Int): Long = int32be(data, offset).toLong() and 0xFFFF_FFFFL
