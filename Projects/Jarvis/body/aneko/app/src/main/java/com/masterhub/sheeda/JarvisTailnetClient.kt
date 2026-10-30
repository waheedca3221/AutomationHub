package com.masterhub.sheeda

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class JarvisTailnetClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Dynamic Tailnet discovery with fallback
    var baseServerUrl: String = resolveTailnetEndpoint()

    fun resolveTailnetEndpoint(): String {
        val prefs = context.getSharedPreferences("sheeda_config", Context.MODE_PRIVATE)
        return prefs.getString("jarvis_url", "http://100.72.183.79:8777") ?: "http://100.72.183.79:8777"
    }

    fun getTtsUrl(text: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        return "$baseServerUrl/api/tts?text=$encoded&fmt=mp3"
    }

    suspend fun getHealthStatus(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseServerUrl/api/health").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(JSONObject(resp.body?.string() ?: "{}"))
                } else {
                    Result.failure(Exception("HTTP ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCircadianBrief(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseServerUrl/api/circadian").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val phase = json.optString("phase", "General")
                    val action = json.optString("predicted_action", "All routines on track.")
                    Result.success("[$phase]\n$action")
                } else {
                    Result.failure(Exception("HTTP ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getInboxCount(): Int = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseServerUrl/api/timeline").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val notes = json.optJSONArray("notes")
                    notes?.length() ?: 0
                } else 0
            }
        } catch (e: Exception) {
            0
        }
    }

    suspend fun sendChatCompletion(queryText: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("model", "jarvis")
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", queryText)
                    })
                }
                put("messages", messages)
            }
            val reqBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$baseServerUrl/v1/chat/completions").post(reqBody).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val choices = json.optJSONArray("choices")
                    val firstChoice = choices?.optJSONObject(0)
                    val message = firstChoice?.optJSONObject("message")
                    val content = message?.optString("content", "") ?: ""
                    if (content.isNotBlank()) {
                        Result.success(content)
                    } else {
                        sendVoiceTurn(queryText)
                    }
                } else {
                    sendVoiceTurn(queryText)
                }
            }
        } catch (e: Exception) {
            sendVoiceTurn(queryText)
        }
    }

    suspend fun sendVoiceTurn(queryText: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("text", queryText)
                put("source", "phone_aneko_overlay")
                put("persona", "Sheeda")
            }
            val reqBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$baseServerUrl/api/turn").post(reqBody).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    Result.success(json.optString("reply", "Meow!"))
                } else {
                    Result.failure(Exception("HTTP ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
