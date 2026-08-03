package com.alastorkaneki.lyricvideomaker.render

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ProgressHolder
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.alastorkaneki.lyricvideomaker.model.AlignedLyricLine
import com.alastorkaneki.lyricvideomaker.model.OutputDimensions
import com.alastorkaneki.lyricvideomaker.model.RenderStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
class LyricVideoRenderer(private val context: Context) {
    data class Request(
        val audioUri: Uri,
        val imageUri: Uri,
        val durationMs: Long,
        val dimensions: OutputDimensions,
        val lyrics: List<AlignedLyricLine>,
        val style: RenderStyle,
    )

    suspend fun render(
        request: Request,
        destinationUri: Uri,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Main) {
        require(request.durationMs > 0) { "The audio duration could not be determined." }
        require(request.lyrics.isNotEmpty()) { "There are no timed lyrics to render." }
        val temp = File.createTempFile("lyric-video-", ".mp4", context.cacheDir).apply { delete() }
        try {
            exportToFile(request, temp, onProgress)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output) }
                } ?: error("The selected output destination could not be opened.")
            }
        } finally {
            temp.delete()
        }
    }

    private suspend fun exportToFile(
        request: Request,
        output: File,
        onProgress: (Int) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val overlay = TimedLyricsCanvasOverlay(request.lyrics, request.style)
        val presentation = Presentation.createForWidthAndHeight(
            request.dimensions.width,
            request.dimensions.height,
            Presentation.LAYOUT_SCALE_TO_FIT,
        )
        val imageItem = MediaItem.Builder()
            .setUri(request.imageUri)
            .setImageDurationMs(request.durationMs)
            .build()
        val editedImage = EditedMediaItem.Builder(imageItem)
            .setFrameRate(request.style.framesPerSecond)
            .setEffects(Effects(emptyList(), listOf(presentation, OverlayEffect(listOf(overlay)))))
            .build()
        val editedAudio = EditedMediaItem.Builder(MediaItem.fromUri(request.audioUri))
            .setRemoveVideo(true)
            .build()
        val videoSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItem(editedImage)
            .build()
        val audioSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            .addItem(editedAudio)
            .build()
        val composition = Composition.Builder(videoSequence, audioSequence).build()
        val handler = Handler(Looper.getMainLooper())
        lateinit var transformer: Transformer
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                handler.removeCallbacksAndMessages(null)
                onProgress(100)
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                handler.removeCallbacksAndMessages(null)
                if (continuation.isActive) continuation.resumeWithException(exportException)
            }
        }
        transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(listener)
            .build()
        val progressHolder = ProgressHolder()
        val progressRunnable = object : Runnable {
            override fun run() {
                if (!continuation.isActive) return
                if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress)
                }
                handler.postDelayed(this, 400L)
            }
        }
        continuation.invokeOnCancellation {
            handler.post {
                transformer.cancel()
                handler.removeCallbacksAndMessages(null)
                output.delete()
            }
        }
        runCatching {
            transformer.start(composition, output.absolutePath)
            handler.post(progressRunnable)
        }.onFailure {
            handler.removeCallbacksAndMessages(null)
            if (continuation.isActive) continuation.resumeWithException(it)
        }
    }
}
