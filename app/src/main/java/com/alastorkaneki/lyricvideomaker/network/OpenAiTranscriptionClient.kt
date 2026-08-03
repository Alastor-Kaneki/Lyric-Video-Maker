package com.alastorkaneki.lyricvideomaker.network

import android.content.Context
import android.net.Uri
import com.alastorkaneki.lyricvideomaker.model.TranscriptSegment
import com.alastorkaneki.lyricvideomaker.model.TranscriptWord
import com.alastorkaneki.lyricvideomaker.model.TranscriptionResult
import com.alastorkaneki.lyricvideomaker.util.copyUriToCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAiTranscriptionClient(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build(),
) {
    suspend fun transcribe(audioUri: Uri, apiKey: String, lyricsHint: String? = null): TranscriptionResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Enter an OpenAI API key before transcribing." }
        val temp = context.copyUriToCache(audioUri, "transcription-", extensionForUri(audioUri))
        try {
            val mediaType = context.contentResolver.getType(audioUri)?.toMediaTypeOrNull()
                ?: "application/octet-stream".toMediaTypeOrNull()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "word")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .apply { lyricsHint?.trim()?.takeIf(String::isNotBlank)?.take(1200)?.let { addFormDataPart("prompt", it) } }
                .addFormDataPart("file", temp.name, temp.asRequestBody(mediaType))
                .build()
            val request = Request.Builder()
                .url(listOf("https://api", "openai.com/v1/audio/transcriptions").joinToString("."))
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
                        .getOrNull().orEmpty().ifBlank { "Transcription failed with HTTP ${response.code}." }
                    error(message)
                }
                parseResponse(raw)
            }
        } finally {
            temp.delete()
        }
    }

    private fun parseResponse(raw: String): TranscriptionResult {
        val json = JSONObject(raw)
        val wordsJson = json.optJSONArray("words")
        val words = buildList {
            if (wordsJson != null) for (index in 0 until wordsJson.length()) {
                val item = wordsJson.getJSONObject(index)
                val word = item.optString("word").trim()
                if (word.isNotEmpty()) add(TranscriptWord(word, (item.optDouble("start") * 1000).toLong(), (item.optDouble("end") * 1000).toLong()))
            }
        }
        val segmentsJson = json.optJSONArray("segments")
        val segments = buildList {
            if (segmentsJson != null) for (index in 0 until segmentsJson.length()) {
                val item = segmentsJson.getJSONObject(index)
                val text = item.optString("text").trim()
                if (text.isNotEmpty()) add(TranscriptSegment(text, (item.optDouble("start") * 1000).toLong(), (item.optDouble("end") * 1000).toLong()))
            }
        }
        val durationMs = (json.optDouble("duration") * 1000).toLong().coerceAtLeast(maxOf(words.maxOfOrNull { it.endMs } ?: 0L, segments.maxOfOrNull { it.endMs } ?: 0L))
        return TranscriptionResult(json.optString("text"), json.optString("language").takeIf(String::isNotBlank), durationMs, words, segments)
    }

    private fun extensionForUri(uri: Uri): String = ".${uri.lastPathSegment.orEmpty().substringAfterLast('.', "audio").take(8).ifBlank { "audio" }}"
}
