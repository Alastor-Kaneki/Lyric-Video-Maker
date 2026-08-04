package com.alastorkaneki.lyricvideomaker.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.alastorkaneki.lyricvideomaker.alignment.LyricsAlignmentEngine
import com.alastorkaneki.lyricvideomaker.data.AudioAssetLoader
import com.alastorkaneki.lyricvideomaker.model.AudioAsset
import com.alastorkaneki.lyricvideomaker.model.LyricsAlignmentResult
import com.alastorkaneki.lyricvideomaker.model.LyricsPosition
import com.alastorkaneki.lyricvideomaker.model.OutputDimensions
import com.alastorkaneki.lyricvideomaker.model.RenderStyle
import com.alastorkaneki.lyricvideomaker.model.TranscriptionResult
import com.alastorkaneki.lyricvideomaker.render.LyricVideoRenderer
import com.alastorkaneki.lyricvideomaker.transcription.LocalTranscriptionOptions
import com.alastorkaneki.lyricvideomaker.transcription.LocalTranscriptionProgress
import com.alastorkaneki.lyricvideomaker.transcription.LocalWhisperTranscriber
import com.alastorkaneki.lyricvideomaker.transcription.WhisperModel
import com.alastorkaneki.lyricvideomaker.transcription.WhisperModelManager
import com.alastorkaneki.lyricvideomaker.util.encoderFriendlyDimensions
import com.alastorkaneki.lyricvideomaker.util.formatDuration
import com.alastorkaneki.lyricvideomaker.util.readImageBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricVideoMakerScreen(amoled: Boolean, onAmoledChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = remember { AudioAssetLoader(context) }
    val modelManager = remember { WhisperModelManager(context) }
    val transcriber = remember { LocalWhisperTranscriber(context) }
    val renderer = remember { LyricVideoRenderer(context) }
    val player = remember { ExoPlayer.Builder(context).build() }

    var audio by remember { mutableStateOf<AudioAsset?>(null) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var dimensions by remember { mutableStateOf<OutputDimensions?>(null) }
    var lyrics by remember { mutableStateOf("") }
    var lyricsSource by remember { mutableStateOf("None") }
    var transcription by remember { mutableStateOf<TranscriptionResult?>(null) }
    var alignment by remember { mutableStateOf<LyricsAlignmentResult?>(null) }
    var selectedModel by remember {
        mutableStateOf(
            when {
                modelManager.isInstalled(WhisperModel.TINY_ENGLISH) -> WhisperModel.TINY_ENGLISH
                modelManager.isInstalled(WhisperModel.TINY_MULTILINGUAL) -> WhisperModel.TINY_MULTILINGUAL
                else -> WhisperModel.TINY_ENGLISH
            },
        )
    }
    var modelRevision by remember { mutableIntStateOf(0) }
    var modelDownloadProgress by remember { mutableIntStateOf(0) }
    var turboMode by remember { mutableStateOf(true) }
    var transcriptionProgress by remember { mutableIntStateOf(0) }
    var transcriptionProcessedMs by remember { mutableLongStateOf(0L) }
    var transcriptionTotalMs by remember { mutableLongStateOf(0L) }
    var transcriptionElapsedMs by remember { mutableLongStateOf(0L) }
    var transcriptionSkippedChunks by remember { mutableIntStateOf(0) }
    var transcriptionStage by remember {
        mutableStateOf(LocalTranscriptionProgress.Stage.DECODING)
    }
    var transcriptionJob by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Choose an audio file to begin.") }
    var renderProgress by remember { mutableIntStateOf(0) }
    var playbackMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var fontScale by remember { mutableFloatStateOf(1f) }
    var dim by remember { mutableFloatStateOf(0f) }
    var karaoke by remember { mutableStateOf(true) }
    var showNext by remember { mutableStateOf(true) }
    var position by remember { mutableStateOf(LyricsPosition.LOWER_THIRD) }
    var fps by remember { mutableIntStateOf(30) }

    val modelInstalled = remember(selectedModel, modelRevision) {
        modelManager.isInstalled(selectedModel)
    }
    val isTranscribing = transcriptionJob?.isActive == true

    fun persist(uri: Uri) = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    fun chooseImage(uri: Uri, label: String) = scope.launch {
        persist(uri)
        val bounds = withContext(Dispatchers.IO) { readImageBounds(context, uri) }
        if (bounds == null) {
            status = "The selected image could not be decoded."
        } else {
            imageUri = uri
            dimensions = encoderFriendlyDimensions(bounds.first, bounds.second)
            status = "$label artwork: ${bounds.first}×${bounds.second}; output ${dimensions?.label}."
        }
    }

    fun startTranscription() {
        val selectedAudio = audio ?: return
        if (!modelInstalled || isTranscribing) return

        transcriptionProgress = 0
        transcriptionProcessedMs = 0L
        transcriptionTotalMs = selectedAudio.durationMs
        transcriptionElapsedMs = 0L
        transcriptionSkippedChunks = 0
        transcriptionStage = LocalTranscriptionProgress.Stage.DECODING

        transcriptionJob = scope.launch {
            busy = "Transcribing locally"
            try {
                val result = transcriber.transcribe(
                    audioUri = selectedAudio.uri,
                    modelFile = modelManager.modelFile(selectedModel),
                    options = LocalTranscriptionOptions(turboMode = turboMode),
                ) { progress ->
                    transcriptionStage = progress.stage
                    transcriptionProgress = progress.percent
                    transcriptionProcessedMs = progress.processedMs
                    transcriptionTotalMs = progress.totalMs.takeIf { it > 0L }
                        ?: selectedAudio.durationMs
                    transcriptionElapsedMs = progress.elapsedMs
                    transcriptionSkippedChunks = progress.skippedChunks
                }

                transcription = result
                if (lyrics.isBlank()) {
                    lyrics = result.text
                    lyricsSource = "Offline transcription"
                }
                alignment = LyricsAlignmentEngine.align(
                    lyrics,
                    result,
                    selectedAudio.durationMs,
                    lyricsSource,
                )
                status = buildString {
                    append("Optimized offline transcription complete. Aligned ")
                    append(alignment?.matchedWords)
                    append("/")
                    append(alignment?.totalReferenceWords)
                    append(" words.")
                    if (transcriptionSkippedChunks > 0) {
                        append(" Skipped ")
                        append(transcriptionSkippedChunks)
                        append(" silent chunk")
                        if (transcriptionSkippedChunks != 1) append("s")
                        append(".")
                    }
                }
            } catch (_: CancellationException) {
                status = "Transcription canceled."
            } catch (error: Throwable) {
                status = error.message ?: "Offline transcription failed."
            } finally {
                busy = null
                transcriptionJob = null
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            persist(uri)
            busy = "Reading audio metadata"
            runCatching { loader.load(uri) }
                .onSuccess { loaded ->
                    audio = loaded
                    transcription = null
                    alignment = null
                    loaded.lyrics.bestCandidate?.let {
                        lyrics = it.text
                        lyricsSource = it.displayName
                    }
                    loaded.embeddedArtwork?.let {
                        imageUri = it.uri
                        dimensions = encoderFriendlyDimensions(it.width, it.height)
                    }
                    player.setMediaItem(MediaItem.fromUri(uri))
                    player.prepare()
                    isPlaying = false
                    status = buildString {
                        append("Loaded ${loaded.displayName} (${formatDuration(loaded.durationMs)}).")
                        if (loaded.embeddedArtwork != null) {
                            append(" Embedded artwork extracted automatically.")
                        }
                        if (loaded.lyrics.bestCandidate != null) append(" Embedded lyrics loaded.")
                    }
                }
                .onFailure { status = it.message ?: "Could not load audio." }
            busy = null
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) chooseImage(uri, "Selected")
    }

    val lyricPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
            if (text.isNullOrBlank()) {
                status = "That lyric file was empty."
            } else {
                lyrics = text
                lyricsSource = "Imported lyrics"
                alignment = null
                status = "Lyrics imported."
            }
        }
    }

    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri ->
        val selectedAudio = audio
        val artwork = imageUri
        val size = dimensions
        val timedLyrics = alignment
        if (
            uri != null &&
            selectedAudio != null &&
            artwork != null &&
            size != null &&
            timedLyrics != null
        ) {
            scope.launch {
                busy = "Rendering MP4"
                renderProgress = 0
                val style = RenderStyle(position, fontScale, showNext, false, karaoke, dim, fps)
                runCatching {
                    renderer.render(
                        LyricVideoRenderer.Request(
                            selectedAudio.uri,
                            artwork,
                            selectedAudio.durationMs.coerceAtLeast(timedLyrics.durationMs),
                            size,
                            timedLyrics.lines,
                            style,
                        ),
                        uri,
                    ) { renderProgress = it }
                }.onSuccess { status = "Lyric video saved." }
                    .onFailure { status = it.message ?: "Video export failed." }
                busy = null
            }
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player) {
        while (true) {
            playbackMs = player.currentPosition.coerceAtLeast(0L)
            if (player.playbackState == ExoPlayer.STATE_ENDED) isPlaying = false
            delay(100)
        }
    }

    LaunchedEffect(imageUri) {
        val selectedImage = imageUri
        previewBitmap = withContext(Dispatchers.IO) {
            selectedImage?.let { uri ->
                val stream = if (uri.scheme == "file") {
                    FileInputStream(requireNotNull(uri.path))
                } else {
                    context.contentResolver.openInputStream(uri)
                }
                stream?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }
        }
    }

    val activeLine = alignment?.lines?.lastOrNull { it.startMs <= playbackMs }
    val stageLabel = when (transcriptionStage) {
        LocalTranscriptionProgress.Stage.DECODING -> "Decoding audio"
        LocalTranscriptionProgress.Stage.LOADING_MODEL -> "Loading cached model"
        LocalTranscriptionProgress.Stage.TRANSCRIBING -> {
            if (turboMode) "Turbo transcription" else "Transcribing"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Lyric Video Maker") }) }) { inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inset).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("1. Audio, embedded lyrics, and cover") {
                    Button(
                        onClick = { audioPicker.launch(arrayOf("audio/*", "video/mp4")) },
                        enabled = busy == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose audio") }
                    audio?.let { Text("${it.displayName} • ${formatDuration(it.durationMs)}") }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                Section("2. Artwork and automatic canvas") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { imagePicker.launch(arrayOf("image/*")) },
                            enabled = busy == null,
                        ) { Text("Replace image") }
                        Text(
                            dimensions?.label ?: "No image",
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                    previewBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(bitmap.width.toFloat() / bitmap.height),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Text(
                        "The output follows the image aspect ratio. Brightness stays unchanged unless dimming is raised.",
                    )
                }
            }

            item {
                Section("3. Offline transcription model") {
                    Text("No API key. Audio never leaves your device.")
                    WhisperModel.entries.forEach { model ->
                        FilterChip(
                            selected = selectedModel == model,
                            onClick = { selectedModel = model },
                            label = { Text("${model.displayName} — ${model.description}") },
                            enabled = busy == null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        if (modelInstalled) {
                            "Model installed. It remains loaded after the first run so repeated transcriptions start faster."
                        } else {
                            "Download this model once; it works offline afterward."
                        },
                    )
                    if (busy == "Downloading Whisper model") {
                        LinearProgressIndicator(
                            progress = { modelDownloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("$modelDownloadProgress%")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = "Downloading Whisper model"
                                    modelDownloadProgress = 0
                                    runCatching {
                                        modelManager.download(selectedModel) {
                                            modelDownloadProgress = it
                                        }
                                    }.onSuccess {
                                        modelRevision++
                                        status = "${selectedModel.displayName} is installed."
                                    }.onFailure {
                                        status = it.message ?: "Model download failed."
                                    }
                                    busy = null
                                }
                            },
                            enabled = !modelInstalled && busy == null,
                        ) { Text("Download model") }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    busy = "Removing model"
                                    transcriber.unloadModel()
                                    modelManager.delete(selectedModel)
                                    modelRevision++
                                    status = "${selectedModel.displayName} removed."
                                    busy = null
                                }
                            },
                            enabled = modelInstalled && busy == null,
                        ) { Text("Remove") }
                    }
                }
            }

            item {
                Section("4. Lyrics and optimized timing") {
                    OutlinedButton(
                        onClick = {
                            lyricPicker.launch(arrayOf("text/*", "application/octet-stream"))
                        },
                        enabled = busy == null,
                    ) { Text("Import TXT/LRC") }

                    OutlinedTextField(
                        value = lyrics,
                        onValueChange = {
                            lyrics = it
                            lyricsSource = "Edited lyrics"
                            alignment = null
                        },
                        label = { Text("Lyrics ($lyricsSource)") },
                        minLines = 8,
                        enabled = !isTranscribing,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Toggle(
                        label = "Turbo mode — two Whisper workers",
                        checked = turboMode,
                        enabled = !isTranscribing,
                    ) { turboMode = it }
                    Text(
                        if (turboMode) {
                            "Faster on multi-core phones; uses more battery and may warm the device."
                        } else {
                            "Single worker mode uses less memory and heat."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )

                    if (isTranscribing) {
                        LinearProgressIndicator(
                            progress = { transcriptionProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("$stageLabel • $transcriptionProgress%")
                        if (transcriptionTotalMs > 0L) {
                            Text(
                                "${formatDuration(transcriptionProcessedMs.coerceAtMost(transcriptionTotalMs))} / " +
                                    "${formatDuration(transcriptionTotalMs)} • elapsed " +
                                    formatDuration(transcriptionElapsedMs),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (transcriptionSkippedChunks > 0) {
                            Text(
                                "Skipped $transcriptionSkippedChunks near-silent chunk" +
                                    if (transcriptionSkippedChunks == 1) "." else "s.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(
                            onClick = { transcriptionJob?.cancel() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel transcription") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { startTranscription() },
                                enabled = audio != null && modelInstalled && busy == null,
                            ) { Text("Transcribe faster + align") }

                            OutlinedButton(
                                onClick = {
                                    val selectedAudio = audio ?: return@OutlinedButton
                                    runCatching {
                                        LyricsAlignmentEngine.align(
                                            lyrics,
                                            transcription,
                                            selectedAudio.durationMs,
                                            lyricsSource,
                                        )
                                    }.onSuccess {
                                        alignment = it
                                        status = "Timing ready (${(it.confidence * 100).toInt()}% matched)."
                                    }.onFailure {
                                        status = it.message ?: "Alignment failed."
                                    }
                                },
                                enabled = audio != null && lyrics.isNotBlank() && busy == null,
                            ) { Text("Use existing timing") }
                        }
                    }

                    alignment?.let {
                        Text(
                            "${it.lines.size} lines • ${(it.confidence * 100).toInt()}% alignment confidence",
                        )
                    }
                }
            }

            item {
                Section("5. Synchronized preview") {
                    Text(
                        activeLine?.text ?: "No timed line at this position",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(formatDuration(playbackMs))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (isPlaying) player.pause() else player.play()
                                isPlaying = !isPlaying
                            },
                            enabled = audio != null,
                        ) { Text(if (isPlaying) "Pause" else "Play") }
                        OutlinedButton(
                            onClick = {
                                player.seekTo(0)
                                player.pause()
                                isPlaying = false
                            },
                            enabled = audio != null,
                        ) { Text("Stop") }
                    }
                }
            }

            item {
                Section("6. Style and export") {
                    Text("Text size ${(fontScale * 100).toInt()}%")
                    Slider(
                        value = fontScale,
                        onValueChange = { fontScale = it },
                        valueRange = 0.7f..1.6f,
                    )
                    Text("Background dim ${(dim * 100).toInt()}%")
                    Slider(
                        value = dim,
                        onValueChange = { dim = it },
                        valueRange = 0f..0.75f,
                    )
                    Toggle("Karaoke word highlighting", karaoke) { karaoke = it }
                    Toggle("Show next line", showNext) { showNext = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LyricsPosition.entries.forEach { value ->
                            FilterChip(
                                selected = position == value,
                                onClick = { position = value },
                                label = { Text(value.name.replace('_', ' ')) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(24, 30, 60).forEach { value ->
                            FilterChip(
                                selected = fps == value,
                                onClick = { fps = value },
                                label = { Text("$value FPS") },
                            )
                        }
                    }
                    Button(
                        onClick = { outputPicker.launch("Lyric Video.mp4") },
                        enabled = audio != null &&
                            imageUri != null &&
                            alignment != null &&
                            busy == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Render MP4") }

                    if (busy != null && !isTranscribing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "$busy" +
                                    if (busy == "Rendering MP4") " • $renderProgress%" else "",
                            )
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AMOLED black")
                    Spacer(Modifier.weight(1f))
                    Switch(checked = amoled, onCheckedChange = onAmoledChanged)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
        )
    }
}
