package ir.tahaarefi.tagents

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Command loop against the TAgents repo via the GitHub Contents API:
 *  - createCommand(): PUT inbox/cmd-<epoch>.json  {"id","action":"pause|resume","agent","ts","by":"taha"}
 *  - pollReport():   GET outbox/ and scan rep-*.json for a report whose "cmd_id" matches.
 *
 * Runs on background threads only; never on the main thread.
 */
object GitHubClient {
    const val OWNER = "thaarfknight-star"
    const val REPO = "TAgents"
    private const val API = "https://api.github.com"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun request(token: String, method: String, path: String, body: JSONObject? = null): Response {
        val b = Request.Builder()
            .url(API + path)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (method == "PUT") {
            b.put((body?.toString() ?: "{}").toRequestBody(JSON_MEDIA))
        } else {
            b.get()
        }
        return client.newCall(b.build()).execute()
    }

    /** Creates inbox/cmd-<epoch>.json and returns the command id. */
    fun createCommand(token: String, agentId: String, action: String): String {
        val epoch = System.currentTimeMillis() / 1000
        val cmdId = "cmd-$epoch"
        val payload = JSONObject()
            .put("id", cmdId)
            .put("action", action)
            .put("agent", agentId)
            .put("ts", Instant.now().toString())
            .put("by", "taha")
        val body = JSONObject()
            .put("message", "cmd $action $agentId by taha")
            .put(
                "content",
                Base64.encodeToString(
                    payload.toString().toByteArray(StandardCharsets.UTF_8),
                    Base64.NO_WRAP
                )
            )
        request(token, "PUT", "/repos/$OWNER/$REPO/contents/inbox/$cmdId.json", body).use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("GitHub ${resp.code}")
        }
        return cmdId
    }

    /** Polls outbox/rep-*.json for a report matching cmdId. Null on timeout. */
    fun pollReport(token: String, cmdId: String, timeoutMs: Long = 45000): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val seen = mutableSetOf<String>()
        while (System.currentTimeMillis() < deadline) {
            try {
                request(token, "GET", "/repos/$OWNER/$REPO/contents/outbox").use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body!!.string())
                        for (i in 0 until arr.length()) {
                            val f = arr.getJSONObject(i)
                            val name = f.optString("name")
                            if (name.startsWith("rep-") && name.endsWith(".json") && seen.add(name)) {
                                val rep = fetchJsonFile(token, f.getString("path"))
                                if (rep != null && rep.optString("cmd_id") == cmdId) return rep
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // transient network hiccup -> keep polling until deadline
            }
            Thread.sleep(2500)
        }
        return null
    }

    private fun fetchJsonFile(token: String, path: String): JSONObject? {
        return try {
            request(token, "GET", "/repos/$OWNER/$REPO/contents/$path").use { resp ->
                if (!resp.isSuccessful) return null
                val obj = JSONObject(resp.body!!.string())
                val raw = obj.optString("content", "").replace("\n", "")
                if (raw.isEmpty()) return null
                JSONObject(String(Base64.decode(raw, Base64.DEFAULT), StandardCharsets.UTF_8))
            }
        } catch (_: Exception) {
            null
        }
    }
}
