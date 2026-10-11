package com.vaidyamd.telecaller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.app.Activity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple runtime configuration screen for telecaller
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "VaidyaMD Telecaller Companion"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#2878A8"))
        }
        layout.addView(title)

        val status = TextView(this).apply {
            text = "Status: Bridge Active (Ready for SIM Dialing)"
            textSize = 14f
            setPadding(0, 24, 0, 36)
            setTextColor(android.graphics.Color.parseColor("#15803D"))
        }
        layout.addView(status)

        val serverInput = EditText(this).apply {
            hint = "HMS API Base URL (e.g. https://api.vaidyamd.com)"
            setText("http://10.0.2.2:8000") // Localhost emulator default
        }
        layout.addView(serverInput)

        val tokenInput = EditText(this).apply {
            hint = "Telecaller Auth Token"
        }
        layout.addView(tokenInput)

        val saveBtn = Button(this).apply {
            text = "Save Credentials"
            setBackgroundColor(android.graphics.Color.parseColor("#2878A8"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                val prefs = getSharedPreferences("vaidyamd_crm", MODE_PRIVATE)
                prefs.edit()
                    .putString("server_url", serverInput.text.toString())
                    .putString("auth_token", tokenInput.text.toString())
                    .apply()
                Toast.makeText(this@MainActivity, "Settings saved!", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(saveBtn)

        setContentView(layout)

        // Request runtime permissions safely on Android 6+
        try {
            val requiredPerms = arrayOf(
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.READ_PHONE_STATE
            )
            val missing = requiredPerms.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 101)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Permission request error", e)
        }
    }

    /**
     * Triggered by WebSocket or push event from HMS Web Tab
     */
    fun dialPatientNumber(callId: String, phoneNumber: String) {
        CallStateReceiver.setActiveCallId(callId)
        val dialIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(dialIntent)
    }
}
