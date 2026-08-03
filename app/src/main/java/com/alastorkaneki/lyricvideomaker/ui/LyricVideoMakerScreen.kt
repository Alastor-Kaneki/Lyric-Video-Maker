package com.alastorkaneki.lyricvideomaker.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.alastorkaneki.lyricvideomaker.network.OpenAiTranscriptionClient
import com.alastorkaneki.lyricvideomaker.render.LyricVideoRenderer
import com.alastorkaneki.lyricvideomaker.util.encoderFriendlyDimensions
import com.alastorkaneki.lyricvideomaker.util.formatDuration
import com.alastorkaneki.lyricvideomaker.util.readImageBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream

@Composable
fun LyricVideoMakerScreen(amoled: Boolean, onAmoledChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = remember { AudioAssetLoader(context) }
    val client = remember { OpenAiTranscriptionClient(context) }
    val renderer = remember { LyricVideoRenderer(context) }
    val player = remember { ExoPlayer.Builder(context).build() }

    var audio by remember { mutableStateOf<AudioAsset?>(null) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var dimensions by remember { mutableStateOf<OutputDimensions?>(null) }
    var lyrics by remember { mutableStateOf("") }
    var lyricsSource by remember { mutableStateOf("None") }
    var apiKey by remember { mutableStateOf("") }
    var transcription by remember { mutableStateOf<TranscriptionResult?>(null) }
    var alignment by remember { mutableStateOf<LyricsAlignmentResult?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Choose an audio file to begin.") }
    var progress by remember { mutableIntStateOf(0) }
    var playbackMs by remember { mutableLongStateOf(0L) }
    var fontScale by remember { mutableFloatStateOf(1f) }
    var dim by remember { mutableFloatStateOf(0f) }
    var karaoke by remember { mutableStateOf(true) }
    var showNext by remember { mutableStateOf(true) }
    var position by remember { mutableStateOf(LyricsPosition.LOWER_THIRD) }
    var fps by remember { mutableIntStateOf(30) }

    fun persist(uri: Uri) = runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun chooseImage(uri: Uri, label: String) = scope.launch {
        persist(uri)
        val bounds = withContext(Dispatchers.IO) { readImageBounds(context, uri) }
        if (bounds == null) status = "The selected image could not be decoded."
        else {
            imageUri = uri
            dimensions = encoderFriendlyDimensions(bounds.first, bounds.second)
            status = "$label artwork: ${bounds.first}×${bounds.second}; output ${dimensions?.label}."
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            persist(uri); busy = "Reading audio metadata"
            runCatching { loader.load(uri) }.onSuccess { loaded ->
                audio = loaded; transcription = null; alignment = null
                loaded.lyrics.bestCandidate?.let { lyrics = it.text; lyricsSource = it.displayName }
                loaded.embeddedArtwork?.let {
                    imageUri = it.uri
                    dimensions = encoderFriendlyDimensions(it.width, it.height)
                }
                player.setMediaItem(MediaItem.fromUri(uri)); player.prepare()
                status = buildString {
                    append("Loaded ${loaded.displayName} (${formatDuration(loaded.durationMs)}).")
                    if (loaded.embeddedArtwork != null) append(" Embedded artwork extracted automatically.")
                    if (loaded.lyrics.bestCandidate != null) append(" Embedded lyrics loaded.")
                }
            }.onFailure { status = it.message ?: "Could not load audio." }
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
            if (text.isNullOrBlank()) status = "That lyric file was empty."
            else { lyrics = text; lyricsSource = "Imported lyrics"; alignment = null; status = "Lyrics imported." }
        }
    }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val a = audio; val art = imageUri; val size = dimensions; val timed = alignment
        if (uri != null && a != null && art != null && size != null && timed != null) scope.launch {
            busy = "Rendering MP4"; progress = 0
            val style = RenderStyle(position, fontScale, showNext, false, karaoke, dim, fps)
            runCatching {
                renderer.render(
                    LyricVideoRenderer.Request(a.uri, art, a.durationMs.coerceAtLeast(timed.durationMs), size, timed.lines, style),
                    uri,
                ) { progress = it }
            }.onSuccess { status = "Lyric video saved." }
                .onFailure { status = it.message ?: "Video export failed." }
            busy = null
        }
    }

    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) { while (true) { playbackMs = player.currentPosition.coerceAtLeast(0L); delay(100) } }

    val previewBitmap by produceState<ImageBitmap?>(null, imageUri) {
        value = withContext(Dispatchers.IO) {
            imageUri?.let { uri ->
                val stream = if (uri.scheme == "file") FileInputStream(requireNotNull(uri.path))
                else context.contentResolver.openInputStream(uri)
                stream?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }
        }
    }
    val activeLine = alignment?.lines?.lastOrNull { it.startMs <= playbackMs }

    Scaffold(topBar = { TopAppBar(title = { Text("Lyric Video Maker") }) }) { inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inset).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Section("1. Audio, embedded lyrics, and cover") {
                Button({ audioPicker.launch(arrayOf("audio/*")) }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Choose audio") }
                audio?.let { Text("${it.displayName} • ${formatDuration(it.durationMs)}") }
                Text(status, style = MaterialTheme.typography.bodySmall)
            } }
            item { Section("2. Artwork and automatic canvas") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ imagePicker.launch(arrayOf("image/*")) }) { Text("Replace image") }
                    Text(dimensions?.label ?: "No image", modifier = Modifier.align(Alignment.CenterVertically))
                }
                previewBitmap?.let { bitmap ->
                    Image(bitmap, null, Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height), contentScale = ContentScale.Fit)
                }
                Text("The output follows the image aspect ratio; brightness is unchanged unless dimming is raised.")
            } }
            item { Section("3. Lyrics and timing") {
                OutlinedButton({ lyricPicker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import TXT/LRC") }
                OutlinedTextField(lyrics, { lyrics = it; lyricsSource = "Edited lyrics"; alignment = null }, label = { Text("Lyrics ($lyricsSource)") }, minLines = 8, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("OpenAI API key for transcription") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val selected = audio ?: return@Button
                        scope.launch {
                            busy = "Transcribing audio"
                            runCatching { client.transcribe(selected.uri, apiKey, lyrics.takeIf { it.isNotBlank() }) }
                                .onSuccess { result ->
                                    transcription = result
                                    if (lyrics.isBlank()) { lyrics = result.text; lyricsSource = "Transcription" }
                                    alignment = LyricsAlignmentEngine.align(lyrics, result, selected.durationMs, lyricsSource)
                                    status = "Transcribed and aligned ${alignment?.matchedWords}/${alignment?.totalReferenceWords} words."
                                }.onFailure { status = it.message ?: "Transcription failed." }
                            busy = null
                        }
                    }, enabled = audio != null && apiKey.isNotBlank() && busy == null) { Text("Transcribe + align") }
                    OutlinedButton(onClick = {
                        val selected = audio ?: return@OutlinedButton
                        runCatching { LyricsAlignmentEngine.align(lyrics, transcription, selected.durationMs, lyricsSource) }
                            .onSuccess { alignment = it; status = "Timing ready (${(it.confidence * 100).toInt()}% matched)." }
                            .onFailure { status = it.message ?: "Alignment failed." }
                    }, enabled = audio != null && lyrics.isNotBlank()) { Text("Use existing timing") }
                }
                alignment?.let { Text("${it.lines.size} lines • ${(it.confidence * 100).toInt()}% alignment confidence") }
            } }
            item { Section("4. Synchronized preview") {
                Text(activeLine?.text ?: "No timed line at this position", style = MaterialTheme.typography.headlineSmall)
                Text(formatDuration(playbackMs))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ if (player.isPlaying) player.pause() else player.play() }, enabled = audio != null) { Text(if (player.isPlaying) "Pause" else "Play") }
                    OutlinedButton({ player.seekTo(0); player.pause() }, enabled = audio != null) { Text("Stop") }
                }
            } }
            item { Section("5. Style and export") {
                Text("Text size ${(fontScale * 100).toInt()}%"); Slider(fontScale, { fontScale = it }, valueRange = 0.7f..1.6f)
                Text("Background dim ${(dim * 100).toInt()}%"); Slider(dim, { dim = it }, valueRange = 0f..0.75f)
                Toggle("Karaoke word highlighting", karaoke) { karaoke = it }
                Toggle("Show next line", showNext) { showNext = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LyricsPosition.entries.forEach { p -> FilterChip(selected = position == p, onClick = { position = p }, label = { Text(p.name.replace('_', ' ')) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(24, 30, 60).forEach { value -> FilterChip(selected = fps == value, onClick = { fps = value }, label = { Text("$value FPS") }) }
                }
                Button(
                    onClick = { outputPicker.launch("Lyric Video.mp4") },
                    enabled = audio != null && imageUri != null && alignment != null && busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Render MP4") }
                if (busy != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(); Text("$busy${if (busy == "Rendering MP4") " • $progress%" else ""}")
                    }
                }
            } }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("AMOLED black"); Spacer(Modifier.weight(1f)); Switch(checked = amoled, onCheckedChange = onAmoledChanged) } }
        }
    }
}

@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() } }
}

@Composable private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label); Spacer(Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange) }
}
