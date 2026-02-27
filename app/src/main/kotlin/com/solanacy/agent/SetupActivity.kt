package com.solanacy.agent

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import android.widget.EditText

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already setup? Go to main
        if (TokenManager.getToken(this).isNotBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_setup)

        val etUsername = findViewById<EditText>(R.id.etUsername)
        val etToken = findViewById<EditText>(R.id.etToken)
        val btnSave = findViewById<MaterialButton>(R.id.btnSave)

        etUsername.setText("Saumikpaul")

        btnSave.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val token = etToken.text.toString().trim()

            if (username.isBlank() || token.isBlank()) {
                Toast.makeText(this, "Fill all fields!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            TokenManager.saveGitHub(this, token, username)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
