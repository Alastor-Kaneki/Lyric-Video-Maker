package com.alastorkaneki.lyricvideomaker.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.alastorkaneki.lyricvideomaker.model.OutputDimensions
import java.io.File
import java.io.FileInputStream
import kotlin.math.min
import kotlin.math.roundToInt

internal fun ContentResolver.displayNameAndSize(uri: Uri): Pair<String, Long> =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "media"
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
        name to size
    } ?: ("media" to -1L)

internal fun Context.copyUriToCache(uri: Uri, prefix: String, suffix: String): File {
    val file = File.createTempFile(prefix, suffix, cacheDir)
    contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    } ?: error("The selected file could not be opened.")
    return file
}

internal fun readImageBounds(context: Context, uri: Uri): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val stream = if (uri.scheme == "file") FileInputStream(requireNotNull(uri.path)) else context.contentResolver.openInputStream(uri)
    stream?.use { BitmapFactory.decodeStream(it, null, options) }
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

fun encoderFriendlyDimensions(sourceWidth: Int, sourceHeight: Int, maxLongSide: Int = 1920): OutputDimensions {
    require(sourceWidth > 0 && sourceHeight > 0)
    val scale = min(1f, maxLongSide.toFloat() / maxOf(sourceWidth, sourceHeight).toFloat())
    var width = (sourceWidth * scale).roundToInt().coerceAtLeast(2)
    var height = (sourceHeight * scale).roundToInt().coerceAtLeast(2)
    if (width % 2 != 0) width -= 1
    if (height % 2 != 0) height -= 1
    return OutputDimensions(width.coerceAtLeast(2), height.coerceAtLeast(2))
}

internal fun formatDuration(milliseconds: Long): String {
    if (milliseconds < 0) return "Unknown"
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "Unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
