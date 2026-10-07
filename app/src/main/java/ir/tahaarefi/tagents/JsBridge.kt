package ir.tahaarefi.tagents

import android.app.Activity
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Bridge between dashboard.html and native code. */
class JsBridge(private val activity: Activity, private val webView: WebView) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @JavascriptInterface
    fun hasToken(): Boolean = TokenStore.get(activity) != null

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
                val token = TokenStore.get(activity) ?: throw RuntimeException("no_token")
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
                out.put("message", if (e.message == "no_token") "اول توکن گیت‌هاب را وارد کن." else "خطا: ${e.message}")
            }
            val escaped = out.toString().replace("\\", "\\\\").replace("'", "\\'")
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript("onCommandResult('$escaped')", null)
            }
        }
    }
}
