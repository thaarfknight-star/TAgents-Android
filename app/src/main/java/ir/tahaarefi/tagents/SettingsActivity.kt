package ir.tahaarefi.tagents

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val input = findViewById<EditText>(R.id.tokenInput)
        input.setText(TokenStore.get(this) ?: "")

        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            val token = input.text.toString().trim()
            if (token.isEmpty()) {
                Toast.makeText(this, "توکن خالی است", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            TokenStore.save(this, token)
            Toast.makeText(this, getString(R.string.token_saved), Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.clearBtn).setOnClickListener {
            TokenStore.clear(this)
            input.setText("")
            Toast.makeText(this, getString(R.string.token_cleared), Toast.LENGTH_SHORT).show()
        }
    }
}
