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

data class PresenceEvent(
    val id: String,
    val type: String,
    val text: String,
    val audio: Boolean,
    val volume: Float,
    val category: String,
    val timestamp: String
)

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String
)

class JarvisTailnetClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Dedicated long-poll HTTP client with 35s read timeout for 15-20s server hold
    private val pollHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    // Dynamic Tailnet discovery with fallback
    var baseServerUrl: String = resolveTailnetEndpoint()
    var basePagesUrl: String = resolvePagesEndpoint()

    fun resolveTailnetEndpoint(): String {
        val prefs = context.getSharedPreferences("sheeda_config", Context.MODE_PRIVATE)
        return prefs.getString("jarvis_url", "http://100.72.183.79:8777") ?: "http://100.72.183.79:8777"
    }

    fun resolvePagesEndpoint(): String {
        val prefs = context.getSharedPreferences("sheeda_config", Context.MODE_PRIVATE)
        val customUrl = prefs.getString("pages_url", null)
        if (!customUrl.isNullOrBlank()) return customUrl

        // Derive from baseServerUrl by replacing port 8777 with 8792
        val current = resolveTailnetEndpoint()
        return if (current.contains(":8777")) {
            current.replace(":8777", ":8792")
        } else {
            "http://100.72.183.79:8792"
        }
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

    /**
     * Long-poll presence endpoint on PC (:8777/api/presence/poll).
     * Waits up to timeoutSeconds on the PC daemon, or returns immediately when events arrive.
     */
    suspend fun pollPresenceEvents(timeoutSeconds: Int = 15): Result<List<PresenceEvent>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseServerUrl/api/presence/poll?limit=10&timeout=$timeoutSeconds"
            val req = Request.Builder().url(url).build()
            pollHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val eventsArr = json.optJSONArray("events")
                    val resultList = mutableListOf<PresenceEvent>()
                    if (eventsArr != null) {
                        for (i in 0 until eventsArr.length()) {
                            val item = eventsArr.optJSONObject(i) ?: continue
                            resultList.add(
                                PresenceEvent(
                                    id = item.optString("id", "evt_${System.currentTimeMillis()}"),
                                    type = item.optString("type", "speak"),
                                    text = item.optString("text", ""),
                                    audio = item.optBoolean("audio", true),
                                    volume = item.optDouble("volume", 1.0).toFloat(),
                                    category = item.optString("category", "reminder"),
                                    timestamp = item.optString("timestamp", "")
                                )
                            )
                        }
                    }
                    Result.success(resultList)
                } else {
                    Result.failure(Exception("HTTP ${resp.code} on presence poll"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Acknowledge event back to PC to prevent 10-minute repeat and inbox escalation.
     */
    suspend fun acknowledgeEvent(eventId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("id", eventId)
            }
            val reqBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$baseServerUrl/api/presence/ack").post(reqBody).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    // Fallback to reminders/ack if presence/ack differs
                    val fallbackReq = Request.Builder().url("$baseServerUrl/api/reminders/ack").post(reqBody).build()
                    client.newCall(fallbackReq).execute().use { fbResp ->
                        Result.success(fbResp.isSuccessful)
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Voice Reminders from phone: routes through PC Phase 10B reminder engine with origin='phone'.
     */
    suspend fun sendReminderVoice(queryText: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("text", queryText)
                put("origin", "phone")
            }
            val reqBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$baseServerUrl/api/reminders/voice").post(reqBody).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val reply = json.optString("reply", "Reminder recorded.")
                    Result.success(reply)
                } else {
                    Result.failure(Exception("HTTP ${resp.code} on reminder voice turn"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Self-update check: inspects :8792/downloads/version.json for newer signed APK.
     */
    suspend fun checkUpdate(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val url = "$basePagesUrl/downloads/version.json"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val vCode = json.optInt("versionCode", 0)
                    val vName = json.optString("versionName", "")
                    val apkUrl = json.optString("apkUrl", "$basePagesUrl/downloads/sheeda-aneko-release.apk")
                    val sha = json.optString("sha256", "")
                    val notes = json.optString("releaseNotes", "")
                    if (vCode > 0) {
                        Result.success(
                            UpdateInfo(
                                versionCode = vCode,
                                versionName = vName,
                                apkUrl = apkUrl,
                                sha256 = sha,
                                releaseNotes = notes
                            )
                        )
                    } else {
                        Result.success(null)
                    }
                } else {
                    Result.success(null)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
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
