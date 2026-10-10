package ir.tahaarefi.tagents

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Token vault manager: add / rename / remove GitHub PATs.
 * Storage is TokenVault (EncryptedSharedPreferences + MasterKey only).
 * Vault I/O runs off the main thread (Keystore ops can block).
 * New tokens are validated against api.github.com before being stored
 * (when the device is online).
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var listBox: LinearLayout
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        listBox = findViewById(R.id.tokenList)
        findViewById<Button>(R.id.addBtn).setOnClickListener { showAddDialog() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Keystore/security failures get a friendly message; raw exception text
     *  is never shown to the user (often English/technical). */
    private fun keystoreMessage(e: Exception): String {
        var c: Throwable? = e
        while (c != null) {
            if (c is GeneralSecurityException) {
                return getString(R.string.sentinel_err_keystore)
            }
            c = c.cause
        }
        return getString(R.string.sentinel_vault_error_plain)
    }

    private fun refresh() {
        listBox.removeAllViews()
        scope.launch(Dispatchers.IO) {
            val tokens = try {
                TokenVault.list(this@SettingsActivity)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(keystoreMessage(e)) }
                return@launch
            }
            withContext(Dispatchers.Main) { renderList(tokens) }
        }
    }

    private fun renderList(tokens: List<VaultToken>) {
        listBox.removeAllViews()
        if (tokens.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = getString(R.string.sentinel_empty)
                setTextColor(0xFF8A8F98.toInt())
                textSize = 14f
            })
            return
        }
        val df = SimpleDateFormat("yyyy/MM/dd", Locale.US)
        for (t in tokens) {
            listBox.addView(buildRow(t, df.format(Date(t.createdAt))))
        }
    }

    private fun buildRow(t: VaultToken, dateStr: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 10)
        }
        val info = TextView(this).apply {
            text = "${t.label}\n$dateStr"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val renameBtn = Button(this).apply {
            text = "✏️"
            setOnClickListener { showRenameDialog(t) }
        }
        val delBtn = Button(this).apply {
            text = "🗑️"
            setOnClickListener { confirmDelete(t) }
        }
        row.addView(info)
        row.addView(renameBtn)
        row.addView(delBtn)
        return row
    }

    private fun showAddDialog() {
        val labelInput = EditText(this).apply { hint = getString(R.string.sentinel_label_hint) }
        val tokenInput = EditText(this).apply {
            hint = getString(R.string.sentinel_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(labelInput)
            addView(tokenInput)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.vault_add))
            .setView(box)
            .setPositiveButton(getString(R.string.sentinel_save)) { _, _ ->
                val label = labelInput.text.toString()
                val token = tokenInput.text.toString().trim()
                if (token.isEmpty()) {
                    toast(getString(R.string.sentinel_token_empty))
                    return@setPositiveButton
                }
                addValidated(label, token)
            }
            .setNegativeButton(getString(R.string.sentinel_cancel), null)
            .show()
    }

    /**
     * Validates the token with GitHub before storing. Offline devices still
     * store the token, but the user is told it was not verified.
     */
    private fun addValidated(label: String, token: String) {
        toast(getString(R.string.sentinel_checking))
        scope.launch(Dispatchers.IO) {
            try {
                val valid = TokenInspector.validate(token)
                if (!valid) {
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.sentinel_err_invalid))
                    }
                    return@launch
                }
                TokenVault.add(this@SettingsActivity, label, token)
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.sentinel_saved))
                    refresh()
                }
            } catch (e: TokenNetworkException) {
                // Offline: store anyway, unverified.
                try {
                    TokenVault.add(this@SettingsActivity, label, token)
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.sentinel_saved_unverified))
                        refresh()
                    }
                } catch (e2: Exception) {
                    withContext(Dispatchers.Main) { toast(keystoreMessage(e2)) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(keystoreMessage(e)) }
            }
        }
    }

    private fun showRenameDialog(t: VaultToken) {
        val input = EditText(this).apply {
            setText(t.label)
            hint = getString(R.string.sentinel_label_hint)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sentinel_rename_title))
            .setView(box)
            .setPositiveButton(getString(R.string.sentinel_save)) { _, _ ->
                try {
                    TokenVault.rename(this, t.id, input.text.toString())
                    refresh()
                } catch (e: Exception) {
                    toast(keystoreMessage(e))
                }
            }
            .setNegativeButton(getString(R.string.sentinel_cancel), null)
            .show()
    }

    private fun confirmDelete(t: VaultToken) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sentinel_delete_title))
            .setMessage(getString(R.string.sentinel_delete_body, t.label))
            .setPositiveButton(getString(R.string.sentinel_delete_confirm)) { _, _ ->
                try {
                    TokenVault.remove(this, t.id)
                    toast(getString(R.string.sentinel_deleted))
                    refresh()
                } catch (e: Exception) {
                    toast(keystoreMessage(e))
                }
            }
            .setNegativeButton(getString(R.string.sentinel_cancel), null)
            .show()
    }
}
