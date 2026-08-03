package com.alastorkaneki.lyricvideomaker.data

import android.content.Context
import android.net.Uri
import com.alastorkaneki.lyricvideomaker.artwork.EmbeddedArtworkExtractor
import com.alastorkaneki.lyricvideomaker.model.AudioAsset
import com.alastorkaneki.lyricvideomaker.util.displayNameAndSize
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class AudioAssetLoader(private val context: Context) {
    private val lyricsExtractor = EmbeddedLyricsExtractor(context)
    private val artworkExtractor = EmbeddedArtworkExtractor(context)

    suspend fun load(uri: Uri): AudioAsset = coroutineScope {
        val resolver = context.contentResolver
        val (displayName, size) = resolver.displayNameAndSize(uri)
        val lyricsDeferred = async { lyricsExtractor.extract(uri) }
        val artworkDeferred = async { artworkExtractor.extract(uri) }
        val lyrics = lyricsDeferred.await()
        val (artwork, durationMs) = artworkDeferred.await()
        AudioAsset(
            uri = uri,
            displayName = displayName,
            sizeBytes = if (size >= 0) size else lyrics.fileSizeBytes,
            mimeType = resolver.getType(uri),
            durationMs = durationMs,
            lyrics = lyrics,
            embeddedArtwork = artwork,
        )
    }
}
