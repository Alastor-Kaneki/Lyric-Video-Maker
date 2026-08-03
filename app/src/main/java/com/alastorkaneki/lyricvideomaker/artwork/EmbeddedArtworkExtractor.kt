package com.alastorkaneki.lyricvideomaker.artwork

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.alastorkaneki.lyricvideomaker.model.EmbeddedArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class EmbeddedArtworkExtractor(private val context: Context) {
    suspend fun extract(audioUri: Uri): Pair<EmbeddedArtwork?, Long> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, audioUri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            val bytes = retriever.embeddedPicture ?: return@withContext null to duration
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null to duration
            val mime = detectMime(bytes)
            val extension = when (mime) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                else -> ".jpg"
            }
            val directory = File(context.cacheDir, "embedded-artwork").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            val output = File(directory, "cover$extension")
            output.writeBytes(bytes)
            EmbeddedArtwork(
                uri = Uri.fromFile(output),
                width = options.outWidth,
                height = options.outHeight,
                mimeType = mime,
                byteCount = bytes.size,
            ) to duration
        } finally {
            retriever.release()
        }
    }

    private fun detectMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }
}
