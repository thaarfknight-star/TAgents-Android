package ir.tahaarefi.tagents

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Bridge between dashboard.html and native code. */
class JsBridge(private val activity: Activity, private val webView: WebView) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** True when the vault holds at least one token. */
    @JavascriptInterface
    fun hasToken(): Boolean = try {
        TokenVault.hasAny(activity)
    } catch (_: Exception) {
        false
    }

    @JavascriptInterface
    fun openSettings() {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
    }

    @JavascriptInterface
    fun onFeedRefreshed() {
        activity.runOnUiThread {
            (activity as? MainActivity)?.stopRefreshing()
        }
    }

    /**
     * Called from JS: pause/resume an agent. Creates the inbox command on
     * GitHub, polls outbox for the matching report, then calls back into JS.
     */
    @JavascriptInterface
    fun sendCommand(agentId: String, action: String) {
        scope.launch(Dispatchers.IO) {
            val out = JSONObject()
            try {
                val token = TokenVault.primaryToken(activity) ?: throw RuntimeException("no_token")
                val cmdId = GitHubClient.createCommand(token, agentId, action)
                val rep = GitHubClient.pollReport(token, cmdId)
                out.put("ok", rep != null && rep.optBoolean("ok", true))
                out.put(
                    "message",
                    rep?.optString("message_fa")
                        ?: "فرمان ثبت شد ولی هنوز گزارشی از ایجنت نرسیده."
                )
                out.put("agent", agentId)
                out.put("action", action)
            } catch (e: Exception) {
                out.put("ok", false)
                out.put(
                    "message",
                    if (e.message == "no_token") "اول توکن گیت‌هاب را وارد کن."
                    else activity.getString(R.string.sentinel_cmd_error)
                )
            }
            jsCallback("onCommandResult", out)
        }
    }

    // ------------------------------------------------------------------
    // Token Sentinel
    // ------------------------------------------------------------------

    /**
     * Lists vault entries as JSON: [{id, label, ts}].
     * Raw token values are NEVER exposed to JS.
     */
    @JavascriptInterface
    fun vaultList(): String {
        return try {
            val arr = JSONArray()
            for (t in TokenVault.list(activity)) {
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("label", t.label)
                        .put("ts", t.createdAt)
                )
            }
            arr.toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "vault").toString()
        }
    }

    /** Full token inspection (owner, kind, rate limit, recent events). */
    @JavascriptInterface
    fun inspectToken(id: String) {
        scope.launch(Dispatchers.IO) {
            val out = JSONObject()
            try {
                val token = TokenVault.tokenOf(activity, id)
                    ?: throw RuntimeException(activity.getString(R.string.sentinel_token_not_found))
                val r = TokenInspector.inspect(token)
                out.put("ok", true)
                out.put(
                    "owner", JSONObject()
                        .put("login", r.owner.login)
                        .put("name", r.owner.name ?: "")
                        .put("avatar", r.owner.avatarUrl ?: "")
                        .put("accountType", r.owner.accountType ?: "")
                )
                out.put("kind", r.kind.kind)
                out.put("kindFa", r.kind.kindFa)
                val perms = JSONArray()
                r.kind.permissions.forEach { perms.put(it) }
                out.put("permissions", perms)
                r.rate?.let { rate ->
                    out.put(
                        "rate", JSONObject()
                            .put("limit", rate.limit)
                            .put("remaining", rate.remaining)
                            .put("reset", rate.resetEpochSec)
                            .put("suspicious", rate.suspicious)
                    )
                }
                val evs = JSONArray()
                r.events.forEach { e ->
                    evs.put(
                        JSONObject()
                            .put("type", e.type)
                            .put("repo", e.repo ?: "")
                            .put("at", e.createdAt ?: "")
                    )
                }
                out.put("events", evs)
            } catch (e: Exception) {
                out.put("ok", false)
                out.put("message", userMessage(e))
            }
            jsCallback("onInspectResult", out)
        }
    }

    /** Maps inspector exceptions to user-facing Persian strings (no raw text). */
    private fun userMessage(e: Exception): String = when (e) {
        is TokenInvalidException -> activity.getString(R.string.sentinel_err_invalid)
        is TokenNetworkException -> activity.getString(R.string.sentinel_err_no_internet)
        is TokenRateLimitedException -> activity.getString(R.string.sentinel_err_rate_limited)
        is TokenRevokeForbiddenException -> activity.getString(R.string.sentinel_err_weak)
        else -> activity.getString(R.string.sentinel_err_unknown)
    }

    /**
     * Starts the two-step Persian confirmation for revoking a token.
     * Result arrives via onRevokeResult(json).
     */
    @JavascriptInterface
    fun requestRevoke(id: String) {
        val entry = try {
            TokenVault.list(activity).firstOrNull { it.id == id }
        } catch (_: Exception) {
            null
        }
        if (entry == null) {
            jsCallback(
                "onRevokeResult",
                JSONObject().put("ok", false)
                    .put("message", activity.getString(R.string.sentinel_token_not_found))
            )
            return
        }
        // Warn explicitly when revoking the vault's primary token (the one the
        // app itself uses for the command loop).
        val isPrimary = try {
            TokenVault.list(activity).firstOrNull()?.id == id
        } catch (_: Exception) {
            false
        }
        activity.runOnUiThread { confirmRevokeStep1(entry, isPrimary) }
    }

    private fun confirmRevokeStep1(entry: VaultToken, isPrimary: Boolean) {
        var msg = activity.getString(R.string.sentinel_revoke_body, entry.label)
        if (isPrimary) msg += activity.getString(R.string.sentinel_revoke_self_warning)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.sentinel_revoke_title))
            .setMessage(msg)
            .setPositiveButton(activity.getString(R.string.sentinel_revoke_continue)) { _, _ ->
                confirmRevokeStep2(entry)
            }
            .setNegativeButton(activity.getString(R.string.sentinel_cancel), null)
            .show()
    }

    private fun confirmRevokeStep2(entry: VaultToken) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.sentinel_revoke_final_title))
            .setMessage(activity.getString(R.string.sentinel_revoke_final_body, entry.label))
            .setPositiveButton(activity.getString(R.string.sentinel_revoke_confirm)) { _, _ -> doRevoke(entry) }
            .setNegativeButton(activity.getString(R.string.sentinel_cancel), null)
            .show()
    }

    private fun doRevoke(entry: VaultToken) {
        scope.launch(Dispatchers.IO) {
            val out = JSONObject()
            try {
                val token = TokenVault.tokenOf(activity, entry.id)
                    ?: throw RuntimeException(activity.getString(R.string.sentinel_token_not_found))
                val ok = TokenInspector.revoke(token)
                if (ok) {
                    TokenVault.remove(activity, entry.id)
                    out.put("ok", true)
                    out.put("message", activity.getString(R.string.sentinel_revoked, entry.label))
                } else {
                    out.put("ok", false)
                    out.put("message", activity.getString(R.string.sentinel_revoke_denied))
                }
            } catch (e: Exception) {
                out.put("ok", false)
                out.put("message", userMessage(e))
            }
            jsCallback("onRevokeResult", out)
        }
    }

    /** Opens the GitHub security log so the user can manually review IPs. */
    @JavascriptInterface
    fun openSecurityLog() {
        activity.runOnUiThread {
            try {
                activity.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/settings/security-log")
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun jsCallback(fn: String, payload: JSONObject) {
        val escaped = payload.toString().replace("\\", "\\\\").replace("'", "\\'")
        scope.launch(Dispatchers.Main) {
            webView.evaluateJavascript("$fn('$escaped')", null)
        }
    }
}
