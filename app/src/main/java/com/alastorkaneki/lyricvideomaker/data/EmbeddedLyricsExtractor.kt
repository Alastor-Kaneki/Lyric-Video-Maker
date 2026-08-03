package com.alastorkaneki.lyricvideomaker.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.alastorkaneki.lyricvideomaker.model.LyricsExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class EmbeddedLyricsExtractor(private val context: Context) {
    suspend fun extract(uri: Uri): LyricsExtractionResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "audio"
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                    name to size
                }
            }
        val displayName = metadata?.first ?: "audio"
        val mimeType = resolver.getType(uri)
        val temp = File.createTempFile("lyrics-", ".audio", context.cacheDir)

        try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("The selected file could not be opened.")

            RandomAccessFile(temp, "r").use { raf ->
                val parsed = LyricsTagParsers.parse(raf)
                LyricsExtractionResult(
                    fileName = displayName,
                    fileSizeBytes = if ((metadata?.second ?: -1L) >= 0L) metadata!!.second else temp.length(),
                    mimeType = mimeType,
                    container = parsed.container,
                    candidates = parsed.candidates
                        .filter { it.text.isNotBlank() }
                        .distinctBy { Triple(it.source, it.tagName, it.text) },
                    warnings = parsed.warnings,
                )
            }
        } finally {
            temp.delete()
        }
    }
}
