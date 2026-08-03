package com.alastorkaneki.lyricvideomaker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alastorkaneki.lyricvideomaker.data.EmbeddedLyricsExtractor
import com.alastorkaneki.lyricvideomaker.model.LyricsExtractionResult
import com.alastorkaneki.lyricvideomaker.ui.theme.LyricVideoMakerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}

@Composable
private fun AppRoot() {
    var amoled by rememberSaveable { mutableStateOf(true) }
    LyricVideoMakerTheme(amoled = amoled) {
        LyricsHome(amoled = amoled, onAmoledChanged = { amoled = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsHome(
    amoled: Boolean,
    onAmoledChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val extractor = remember(context) { EmbeddedLyricsExtractor(context) }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LyricsExtractionResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedCandidate by remember { mutableIntStateOf(0) }
    var editorText by remember { mutableStateOf("") }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(editorText) }
                    ?: error("Could not open the destination.")
            }.onSuccess {
                Toast.makeText(context, "Lyrics saved", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Save failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            loading = true
            result = null
            error = null
            scope.launch {
                runCatching { extractor.extract(uri) }
                    .onSuccess { extracted ->
                        result = extracted
                        val best = extracted.bestCandidate
                        selectedCandidate = extracted.candidates.indexOf(best).coerceAtLeast(0)
                        editorText = best?.text.orEmpty()
                    }
                    .onFailure { error = it.message ?: "The file could not be read." }
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Lyric Video Maker", fontWeight = FontWeight.Bold)
                        Text("Embedded lyrics extractor", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(20.dp))
                    Switch(checked = amoled, onCheckedChange = onAmoledChanged)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                IntroCard(onPickAudio = { picker.launch(arrayOf("audio/*", "video/mp4")) })
            }
            if (loading) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Text("Reading audio metadata…", fontWeight = FontWeight.SemiBold)
                            }
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("The file is copied to temporary app storage, parsed, then deleted.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            error?.let { message ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            result?.let { extracted ->
                item { FileSummary(extracted) }
                if (extracted.candidates.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("No embedded lyrics found", fontWeight = FontWeight.Bold)
                                Text(
                                    "The file was readable, but it did not contain a supported lyrics tag. The next milestone will offer transcription and provided-lyrics alignment as fallbacks.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Text("Detected lyric tracks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            itemsIndexed(extracted.candidates) { index, candidate ->
                                FilterChip(
                                    selected = index == selectedCandidate,
                                    onClick = {
                                        selectedCandidate = index
                                        editorText = candidate.text
                                    },
                                    label = { Text(candidate.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                    }
                    item {
                        val active = extracted.candidates.getOrNull(selectedCandidate)
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Extracted lyrics", fontWeight = FontWeight.Bold)
                                        Text(
                                            if (active?.isSynchronized == true) "${active.timedLines.size} timed lines detected" else "Unsynchronized text",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    IconButton(onClick = { copyLyrics(context, editorText) }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy lyrics")
                                    }
                                    IconButton(onClick = {
                                        val extension = if (active?.isSynchronized == true) "lrc" else "txt"
                                        saveLauncher.launch("${extracted.fileName.substringBeforeLast('.')}-lyrics.$extension")
                                    }) {
                                        Icon(Icons.Default.Save, contentDescription = "Save lyrics")
                                    }
                                }
                                OutlinedTextField(
                                    value = editorText,
                                    onValueChange = { editorText = it },
                                    modifier = Modifier.fillMaxWidth().height(360.dp),
                                    label = { Text("Lyrics") },
                                    supportingText = { Text("Editable. Original metadata is never modified.") },
                                )
                            }
                        }
                    }
                }
                if (extracted.warnings.isNotEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Parser notes", fontWeight = FontWeight.Bold)
                                extracted.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntroCard(onPickAudio: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Lyrics, contentDescription = null, modifier = Modifier.size(38.dp))
                Column {
                    Text("Start with the audio file", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Read embedded lyrics before transcribing anything.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                "Supports ID3 USLT/SYLT, FLAC and Ogg/Opus comments, MP4/M4A lyric atoms, RIFF lyric chunks, APEv2, and embedded LRC timestamps.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onPickAudio, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AudioFile, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Choose audio")
            }
        }
    }
}

@Composable
private fun FileSummary(result: LyricsExtractionResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(result.fileName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(result.container) })
                AssistChip(onClick = {}, label = { Text(formatBytes(result.fileSizeBytes)) })
            }
            Text(
                "${result.candidates.size} lyric track${if (result.candidates.size == 1) "" else "s"} found",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun copyLyrics(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Lyrics", text))
    Toast.makeText(context, "Lyrics copied", Toast.LENGTH_SHORT).show()
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "Unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
