package com.alastorkaneki.lyricvideomaker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EmbeddedLyricsExtractorTest {
    @Test
    fun extractsId3Uslt() = withTempFile(buildId3Uslt("[00:01.00]First line\n[00:02.50]Second line")) { raf ->
        val result = LyricsTagParsers.parse(raf)
        assertEquals("MP3 / ID3", result.container)
        assertEquals(1, result.candidates.size)
        assertEquals(2, result.candidates.first().timedLines.size)
    }

    @Test
    fun extractsFlacVorbisLyrics() = withTempFile(buildFlac("LYRICS=Hello from FLAC")) { raf ->
        val result = LyricsTagParsers.parse(raf)
        assertEquals("FLAC", result.container)
        assertTrue(result.candidates.any { it.text == "Hello from FLAC" })
    }

    private fun buildId3Uslt(text: String): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(3)
            write("eng".toByteArray())
            write(0)
            write(text.toByteArray())
        }.toByteArray()
        val frame = ByteArrayOutputStream().apply {
            write("USLT".toByteArray())
            write(ByteBuffer.allocate(4).putInt(body.size).array())
            write(byteArrayOf(0, 0))
            write(body)
        }.toByteArray()
        val header = ByteArrayOutputStream().apply {
            write("ID3".toByteArray())
            write(byteArrayOf(3, 0, 0))
            write(synchsafe(frame.size))
        }.toByteArray()
        return header + frame
    }

    private fun buildFlac(comment: String): ByteArray {
        val vendor = "test".toByteArray()
        val entry = comment.toByteArray()
        val block = ByteArrayOutputStream().apply {
            write(leInt(vendor.size))
            write(vendor)
            write(leInt(1))
            write(leInt(entry.size))
            write(entry)
        }.toByteArray()
        return "fLaC".toByteArray() + byteArrayOf((0x80 or 4).toByte(), ((block.size shr 16) and 0xFF).toByte(), ((block.size shr 8) and 0xFF).toByte(), (block.size and 0xFF).toByte()) + block
    }

    private fun synchsafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun leInt(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun withTempFile(bytes: ByteArray, block: (RandomAccessFile) -> Unit) {
        val file = File.createTempFile("lyrics-test", ".bin")
        try {
            file.writeBytes(bytes)
            RandomAccessFile(file, "r").use(block)
        } finally {
            file.delete()
        }
    }
}
