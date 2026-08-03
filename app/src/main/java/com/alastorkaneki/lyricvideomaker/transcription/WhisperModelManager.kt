package com.alastorkaneki.lyricvideomaker.transcription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class WhisperModel(
    val displayName: String,
    val description: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
) {
    TINY_MULTILINGUAL(
        displayName = "Tiny multilingual",
        description = "About 32 MB • supports many languages",
        fileName = "ggml-tiny-q5_1.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin?download=true",
        sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
    ),
    TINY_ENGLISH(
        displayName = "Tiny English",
        description = "About 32 MB • optimized for English",
        fileName = "ggml-tiny.en-q5_1.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin?download=true",
        sha256 = "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b",
    ),
}

class WhisperModelManager(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .build(),
) {
    private val modelDirectory = File(context.filesDir, "whisper-models").apply { mkdirs() }

    fun modelFile(model: WhisperModel): File = File(modelDirectory, model.fileName)

    fun isInstalled(model: WhisperModel): Boolean = modelFile(model).let { it.isFile && it.length() > 10_000_000L }

    fun delete(model: WhisperModel): Boolean {
        val target = modelFile(model)
        val partial = File(modelDirectory, "${model.fileName}.part")
        partial.delete()
        return !target.exists() || target.delete()
    }

    suspend fun download(
        model: WhisperModel,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val target = modelFile(model)
        if (isInstalled(model) && sha256(target) == model.sha256) {
            onProgress(100)
            return@withContext target
        }

        target.delete()
        val partial = File(modelDirectory, "${model.fileName}.part")
        partial.delete()

        val request = Request.Builder().url(model.downloadUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Model download failed with HTTP ${response.code}.")
            val body = response.body ?: error("The model download was empty.")
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastProgress = -1

            body.byteStream().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count
                        val progress = if (total > 0L) ((written * 100L) / total).toInt().coerceIn(0, 99) else 0
                        if (progress != lastProgress) {
                            lastProgress = progress
                            onProgress(progress)
                        }
                    }
                }
            }

            require(written > 10_000_000L) { "The downloaded model was unexpectedly small." }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(model.sha256, ignoreCase = true)) {
                partial.delete()
                error("The downloaded model failed its integrity check.")
            }
        }

        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        onProgress(100)
        target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
