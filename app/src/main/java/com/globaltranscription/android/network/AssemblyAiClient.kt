package com.globaltranscription.android.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class AssemblyAiClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun transcribeAudio(
        wavFile: File,
        apiKey: String,
        onStatus: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("AssemblyAI API key is missing"))
        }
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            return@withContext Result.failure(IllegalArgumentException("Audio file is empty"))
        }

        try {
            onStatus("Uploading cleaned speech...")
            val uploadUrl = uploadAudio(wavFile, trimmedKey)

            coroutineContext.ensureActive()
            onStatus("Submitting to Universal...")
            val transcriptId = submitTranscript(uploadUrl, trimmedKey)

            onStatus("Transcribing...")
            val text = pollTranscript(transcriptId, trimmedKey, onStatus)
            Result.success(text)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun uploadAudio(wavFile: File, apiKey: String): String {
        val requestBody = wavFile.asRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url("$BASE_URL/upload")
            .header("Authorization", apiKey)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(extractApiError("Upload failed (${response.code})", bodyText))
            }
            val json = JSONObject(bodyText)
            val uploadUrl = json.optString("upload_url", "")
            if (uploadUrl.isBlank()) {
                throw IOException("AssemblyAI upload response missing upload_url")
            }
            return uploadUrl
        }
    }

    private fun submitTranscript(uploadUrl: String, apiKey: String): String {
        val payloads = listOf(
            JSONObject().apply {
                put("audio_url", uploadUrl)
                put("speech_models", JSONArray().apply {
                    put("universal-3-5-pro")
                    put("universal-2")
                })
                put("language_detection", true)
            },
            JSONObject().apply {
                put("audio_url", uploadUrl)
                put("speech_model", "universal")
                put("language_detection", true)
            },
            JSONObject().apply {
                put("audio_url", uploadUrl)
                put("language_detection", true)
            }
        )

        var lastError: IOException? = null
        for ((index, payload) in payloads.withIndex()) {
            val request = Request.Builder()
                .url("$BASE_URL/transcript")
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val json = JSONObject(bodyText)
                    val id = json.optString("id", "")
                    if (id.isBlank()) {
                        throw IOException("AssemblyAI transcript response missing id")
                    }
                    return id
                }
                val err = IOException(extractApiError("Transcript submit failed (${response.code})", bodyText))
                if (response.code == 400 && index < payloads.lastIndex) {
                    lastError = err
                } else {
                    throw err
                }
            }
        }
        throw lastError ?: IOException("Transcript submission failed")
    }

    private suspend fun pollTranscript(
        transcriptId: String,
        apiKey: String,
        onStatus: (String) -> Unit
    ): String {
        val url = "$BASE_URL/transcript/$transcriptId"
        val maxAttempts = 150 // 150 * 800ms = 120 seconds
        for (attempt in 1..maxAttempts) {
            coroutineContext.ensureActive()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(extractApiError("Status check failed (${response.code})", bodyText))
                }
                val json = JSONObject(bodyText)
                when (val status = json.optString("status", "")) {
                    "completed" -> return json.optString("text", "").trim()
                    "error" -> {
                        val apiErr = json.optString("error", "Unknown transcription error")
                        throw IOException("Transcription failed: $apiErr")
                    }
                    "queued" -> onStatus("Queued...")
                    "processing" -> onStatus("Transcribing...")
                    else -> if (status.isNotBlank()) onStatus("Status: $status")
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        throw IOException("Transcription timed out after 120s")
    }

    private fun extractApiError(prefix: String, bodyText: String): String {
        if (bodyText.isBlank()) return prefix
        return try {
            val msg = JSONObject(bodyText).optString("error", "")
            if (msg.isNotBlank()) "$prefix: $msg" else "$prefix: $bodyText"
        } catch (_: Exception) {
            "$prefix: $bodyText"
        }
    }

    companion object {
        private const val BASE_URL = "https://api.assemblyai.com/v2"
        private const val POLL_INTERVAL_MS = 800L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
