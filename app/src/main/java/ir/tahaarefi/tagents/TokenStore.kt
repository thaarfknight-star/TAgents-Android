package ir.tahaarefi.tagents

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * GitHub PAT storage. EncryptedSharedPreferences first, plain
 * SharedPreferences as a graceful fallback. The token never leaves
 * the device except toward api.github.com.
 */
object TokenStore {
    private const val PREF = "tagents_secure"
    private const val KEY = "github_token"
    private const val FALLBACK = "tagents_plain"

    private fun prefs(ctx: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx, PREF, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            ctx.getSharedPreferences(FALLBACK, Context.MODE_PRIVATE)
        }
    }

    fun get(ctx: Context): String? =
        prefs(ctx).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun save(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY, token.trim()).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}
