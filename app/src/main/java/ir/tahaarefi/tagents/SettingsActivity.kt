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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Token vault manager: add / rename / remove GitHub PATs.
 * Storage is TokenVault (EncryptedSharedPreferences + MasterKey only).
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var listBox: LinearLayout

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

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun refresh() {
        listBox.removeAllViews()
        val tokens = try {
            TokenVault.list(this)
        } catch (e: Exception) {
            toast("خطا در باز کردن صندوق: ${e.message}")
            return
        }
        if (tokens.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "هنوز توکنی ثبت نشده است."
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

    private fun buildRow(t: VaultToken, dateFa: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 10)
        }
        val info = TextView(this).apply {
            text = "${t.label}\n$dateFa"
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
        val labelInput = EditText(this).apply { hint = "برچسب (مثلاً: گوشی طه)" }
        val tokenInput = EditText(this).apply {
            hint = "ghp_… یا github_pat_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(labelInput)
            addView(tokenInput)
        }
        AlertDialog.Builder(this)
            .setTitle("افزودن توکن")
            .setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                val token = tokenInput.text.toString().trim()
                if (token.isEmpty()) {
                    toast("توکن خالی است")
                    return@setPositiveButton
                }
                try {
                    TokenVault.add(this, labelInput.text.toString(), token)
                    toast("توکن ذخیره شد")
                    refresh()
                } catch (e: Exception) {
                    toast("خطا: ${e.message}")
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showRenameDialog(t: VaultToken) {
        val input = EditText(this).apply {
            setText(t.label)
            hint = "این توکن دست کیه؟"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("ویرایش برچسب")
            .setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                try {
                    TokenVault.rename(this, t.id, input.text.toString())
                    refresh()
                } catch (e: Exception) {
                    toast("خطا: ${e.message}")
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun confirmDelete(t: VaultToken) {
        AlertDialog.Builder(this)
            .setTitle("حذف توکن")
            .setMessage("«${t.label}» فقط از صندوق این گوشی حذف می‌شود (خود توکن در گیت‌هاب باطل نمی‌شود). ادامه می‌دهی؟")
            .setPositiveButton("حذف") { _, _ ->
                try {
                    TokenVault.remove(this, t.id)
                    toast("حذف شد")
                    refresh()
                } catch (e: Exception) {
                    toast("خطا: ${e.message}")
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
}
