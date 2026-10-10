package ir.tahaarefi.tagents

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Public view of a vault entry. The raw token value is deliberately NOT
 * exposed here — native code only (see [tokenOf]).
 */
data class VaultToken(val id: String, val label: String, val createdAt: Long)

/**
 * Multi-token vault for GitHub PATs ("Token Sentinel").
 *
 * Storage is EncryptedSharedPreferences + MasterKey (Android Keystore) ONLY.
 * There is intentionally no plaintext fallback: if the Keystore is
 * unavailable the operation throws and the UI must show the error.
 *
 * All entries live in a single encrypted JSON blob. Raw token values are
 * never logged, never written to plain files, and never handed to the
 * WebView/JS layer.
 */
object TokenVault {
    private const val PREF = "tagents_vault"
    private const val KEY_BLOB = "tokens_v1"
    private const val KEY_MIGRATED = "migrated_v1"

    // Legacy single-token store (TokenStore) locations, for one-time import.
    private const val LEGACY_PREF = "tagents_secure"
    private const val LEGACY_FALLBACK = "tagents_plain"
    private const val LEGACY_KEY = "github_token"

    private data class Entry(val id: String, val label: String, val token: String, val createdAt: Long)

    private fun prefs(ctx: Context) =
        EncryptedSharedPreferences.create(
            ctx.applicationContext, PREF,
            MasterKey.Builder(ctx.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    private fun readRaw(ctx: Context): MutableList<Entry> {
        val out = mutableListOf<Entry>()
        val raw = prefs(ctx).getString(KEY_BLOB, null) ?: return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val token = o.optString("t", "")
                if (token.isBlank()) continue
                out += Entry(
                    id = o.optString("id", UUID.randomUUID().toString()).ifBlank { UUID.randomUUID().toString() },
                    label = o.optString("label", "بدون برچسب").ifBlank { "بدون برچسب" },
                    token = token,
                    createdAt = o.optLong("ts", System.currentTimeMillis())
                )
            }
        } catch (_: Exception) {
            // Corrupted blob: treat as empty rather than crashing.
        }
        return out
    }

    private fun writeAll(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("label", e.label)
                    .put("t", e.token)
                    .put("ts", e.createdAt)
            )
        }
        prefs(ctx).edit().putString(KEY_BLOB, arr.toString()).apply()
    }

    /** One-time import of the old single-token store into the vault. */
    private fun migrateLegacy(ctx: Context) {
        if (prefs(ctx).getBoolean(KEY_MIGRATED, false)) return
        var legacy: String? = null
        try {
            legacy = ctx.applicationContext
                .getSharedPreferences(LEGACY_PREF, Context.MODE_PRIVATE)
                .getString(LEGACY_KEY, null)
            if (legacy.isNullOrBlank()) {
                legacy = ctx.applicationContext
                    .getSharedPreferences(LEGACY_FALLBACK, Context.MODE_PRIVATE)
                    .getString(LEGACY_KEY, null)
            }
        } catch (_: Exception) {
        }
        if (!legacy.isNullOrBlank()) {
            val all = readRaw(ctx)
            all += Entry(UUID.randomUUID().toString(), "توکن اصلی", legacy.trim(), System.currentTimeMillis())
            writeAll(ctx, all)
            try {
                ctx.applicationContext.getSharedPreferences(LEGACY_PREF, Context.MODE_PRIVATE)
                    .edit().clear().apply()
                ctx.applicationContext.getSharedPreferences(LEGACY_FALLBACK, Context.MODE_PRIVATE)
                    .edit().clear().apply()
            } catch (_: Exception) {
            }
        }
        prefs(ctx).edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    fun list(ctx: Context): List<VaultToken> {
        migrateLegacy(ctx)
        return readRaw(ctx).map { VaultToken(it.id, it.label, it.createdAt) }
    }

    fun add(ctx: Context, label: String, token: String): VaultToken {
        val clean = token.trim()
        require(clean.isNotEmpty()) { "توکن خالی است" }
        migrateLegacy(ctx)
        val all = readRaw(ctx)
        val e = Entry(
            UUID.randomUUID().toString(),
            label.trim().ifBlank { "بدون برچسب" },
            clean,
            System.currentTimeMillis()
        )
        all += e
        writeAll(ctx, all)
        return VaultToken(e.id, e.label, e.createdAt)
    }

    fun remove(ctx: Context, id: String): Boolean {
        migrateLegacy(ctx)
        val all = readRaw(ctx)
        val kept = all.filter { it.id != id }
        if (kept.size == all.size) return false
        writeAll(ctx, kept)
        return true
    }

    fun rename(ctx: Context, id: String, label: String): Boolean {
        migrateLegacy(ctx)
        val all = readRaw(ctx)
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) return false
        val e = all[i]
        all[i] = e.copy(label = label.trim().ifBlank { "بدون برچسب" })
        writeAll(ctx, all)
        return true
    }

    /** Raw token value. NATIVE USE ONLY — never pass to JS, logs, or files. */
    fun tokenOf(ctx: Context, id: String): String? {
        migrateLegacy(ctx)
        return readRaw(ctx).firstOrNull { it.id == id }?.token
    }

    /** Raw value of the first token (used by the command loop). NATIVE USE ONLY. */
    fun primaryToken(ctx: Context): String? {
        migrateLegacy(ctx)
        return readRaw(ctx).firstOrNull()?.token
    }

    fun hasAny(ctx: Context): Boolean {
        migrateLegacy(ctx)
        return readRaw(ctx).isNotEmpty()
    }
}
