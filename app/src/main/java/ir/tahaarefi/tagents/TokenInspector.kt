package ir.tahaarefi.tagents

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The token was rejected by GitHub (HTTP 401): invalid, expired, or revoked. */
class TokenInvalidException : IOException("توکن نامعتبر یا منقضی است")

/** Network-level failure while talking to api.github.com. */
class TokenNetworkException(cause: Throwable) : IOException("خطای شبکه", cause)

data class TokenOwner(
    val login: String,
    val name: String?,
    val avatarUrl: String?,
    val accountType: String?
)

data class TokenKind(
    /** "classic" | "fine-grained" | "unknown" */
    val kind: String,
    val kindFa: String,
    /** Human-readable permission hints, e.g. "scope: repo". */
    val permissions: List<String>
)

data class RateStatus(
    val limit: Int,
    val remaining: Int,
    val resetEpochSec: Long,
    val suspicious: Boolean
)

data class PublicEvent(val type: String, val repo: String?, val createdAt: String?)

data class InspectResult(
    val owner: TokenOwner,
    val kind: TokenKind,
    val rate: RateStatus?,
    val events: List<PublicEvent>
)

/**
 * Token Sentinel inspector. Talks to api.github.com with OkHttp.
 * All methods are blocking — call only from a background thread.
 * The raw token never leaves this object except toward api.github.com.
 */
object TokenInspector {
    private const val API = "https://api.github.com"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun get(token: String?, path: String): Response {
        val b = Request.Builder()
            .url(API + path)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
        if (token != null) b.header("Authorization", "Bearer $token")
        return client.newCall(b.build()).execute()
    }

    /**
     * Full inspection: owner identity, token kind + permissions,
     * rate-limit usage signal, and recent public events (account-activity hint).
     */
    @Throws(IOException::class)
    fun inspect(token: String): InspectResult {
        val userResp = try {
            get(token, "/user")
        } catch (e: IOException) {
            throw TokenNetworkException(e)
        }
        userResp.use { resp ->
            if (resp.code == 401) throw TokenInvalidException()
            if (!resp.isSuccessful) throw IOException("خطای گیت‌هاب (${resp.code})")
            val body = JSONObject(resp.body?.string() ?: "{}")
            val owner = TokenOwner(
                login = body.optString("login"),
                name = body.optString("name").ifBlank { null },
                avatarUrl = body.optString("avatar_url").ifBlank { null },
                accountType = body.optString("type").ifBlank { null }
            )
            val kind = detectKind(token, resp)
            val rate = try {
                readRate(token)
            } catch (_: Exception) {
                null
            }
            val events = try {
                readEvents(owner.login)
            } catch (_: Exception) {
                emptyList()
            }
            return InspectResult(owner, kind, rate, events)
        }
    }

    /**
     * Token kind: ghp_* = classic, github_pat_* = fine-grained.
     * Confirmed with response headers — X-OAuth-Scopes (classic) or
     * X-Accepted-GitHub-Permissions (fine-grained).
     */
    private fun detectKind(token: String, userResp: Response): TokenKind {
        val byPrefix = when {
            token.startsWith("ghp_") -> "classic"
            token.startsWith("github_pat_") -> "fine-grained"
            else -> "unknown"
        }
        val scopes = userResp.header("X-OAuth-Scopes")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val accepted = userResp.header("X-Accepted-GitHub-Permissions")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val kind = when {
            byPrefix != "unknown" -> byPrefix
            scopes.isNotEmpty() -> "classic"
            else -> "unknown"
        }
        val kindFa = when (kind) {
            "classic" -> "کلاسیک"
            "fine-grained" -> "دقیق (fine-grained)"
            else -> "نامشخص"
        }
        val perms = if (scopes.isNotEmpty()) scopes.map { "scope: $it" } else accepted
        return TokenKind(kind, kindFa, perms)
    }

    private fun readRate(token: String): RateStatus {
        get(token, "/rate_limit").use { resp ->
            if (!resp.isSuccessful) throw IOException("rate_limit: ${resp.code}")
            val core = JSONObject(resp.body?.string() ?: "{}")
                .optJSONObject("resources")?.optJSONObject("core")
                ?: throw IOException("پاسخ rate_limit نامعتبر است")
            val limit = core.optInt("limit", 0)
            val remaining = core.optInt("remaining", 0)
            val reset = core.optLong("reset", 0)
            // Abnormally low remaining budget hints that someone else may be
            // spending this token's quota.
            val suspicious = limit > 0 && remaining < limit / 10
            return RateStatus(limit, remaining, reset, suspicious)
        }
    }

    /** Last ~10 public events of the account — an activity *hint*, nothing more. */
    private fun readEvents(login: String): List<PublicEvent> {
        if (login.isBlank()) return emptyList()
        get(null, "/users/$login/events/public?per_page=10").use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONArray(resp.body?.string() ?: "[]")
            val out = mutableListOf<PublicEvent>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                out += PublicEvent(
                    type = e.optString("type"),
                    repo = e.optJSONObject("repo")?.optString("name"),
                    createdAt = e.optString("created_at").ifBlank { null }
                )
            }
            return out
        }
    }

    /**
     * Revokes the token for ALL of its consumers.
     * Per GitHub API this call carries NO Authorization header; the token to
     * revoke travels in the request body only. Returns true on HTTP 204.
     */
    @Throws(IOException::class)
    fun revoke(token: String): Boolean {
        val body = JSONObject()
            .put("credentials", JSONArray().put(token))
            .toString()
            .toRequestBody(JSON)
        val req = Request.Builder()
            .url("$API/credentials/revoke")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(body)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                return resp.code == 204
            }
        } catch (e: IOException) {
            throw TokenNetworkException(e)
        }
    }
}
