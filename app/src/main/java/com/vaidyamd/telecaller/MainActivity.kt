package com.vaidyamd.telecaller

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var queueCard: LinearLayout
    private lateinit var leadNameText: TextView
    private lateinit var leadPhoneText: TextView
    private lateinit var leadDetailsText: TextView
    private lateinit var dialBtn: Button
    private lateinit var skipBtn: Button
    private lateinit var fetchQueueBtn: Button

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var activeLeadId: String? = null
    private var activeLeadPhone: String? = null
    private var currentActiveCallId: String? = null

    private var webSocket: WebSocket? = null
    private var heartbeatTimer: java.util.Timer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Master ScrollView Container
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 48)
        }

        // --- Brand Header: VaidyaMD Teal & Gold ---
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }

        val logoIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(96, 96).apply {
                marginEnd = 24
            }
        }
        headerLayout.addView(logoIcon)

        val headerTitles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val appTitle = TextView(this).apply {
            text = "VaidyaMD Telecaller"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B4F6C"))
        }
        val appSubtitle = TextView(this).apply {
            text = "Zero-Cost SIM Dialer & Whisper Sync"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
        }
        headerTitles.addView(appTitle)
        headerTitles.addView(appSubtitle)
        headerLayout.addView(headerTitles)
        mainLayout.addView(headerLayout)

        // Status Banner
        statusText = TextView(this).apply {
            text = "● Offline (Connecting to Hospital Server...)"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(24, 16, 24, 16)
            setTextColor(Color.parseColor("#D97706"))
            setBackgroundColor(Color.parseColor("#FEF3C7"))
        }
        mainLayout.addView(statusText)

        // --- SECTION 1: Telecaller Dialer Queue Card ---
        val sectionTitle = TextView(this).apply {
            text = "PATIENT QUEUE"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 36, 0, 12)
        }
        mainLayout.addView(sectionTitle)

        queueCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.WHITE)
            elevation = 4f
        }

        leadNameText = TextView(this).apply {
            text = "Loading next patient..."
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
        }
        queueCard.addView(leadNameText)

        leadPhoneText = TextView(this).apply {
            text = "+91 ----------"
            textSize = 15f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#2878A8"))
            setPadding(0, 8, 0, 8)
        }
        queueCard.addView(leadPhoneText)

        leadDetailsText = TextView(this).apply {
            text = "Tap 'Fetch Next Lead' to begin"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 24)
        }
        queueCard.addView(leadDetailsText)

        // Action Buttons Row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        dialBtn = Button(this).apply {
            text = "📞 Call via SIM"
            setBackgroundColor(Color.parseColor("#15803D"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                marginEnd = 16
            }
            setOnClickListener {
                val leadId = activeLeadId
                val phone = activeLeadPhone
                if (leadId != null && phone != null) {
                    placeCallToPatient(leadId, phone)
                } else {
                    Toast.makeText(this@MainActivity, "No lead selected in queue", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnRow.addView(dialBtn)

        skipBtn = Button(this).apply {
            text = "Next Lead"
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            setTextColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                fetchNextLeadFromQueue()
            }
        }
        btnRow.addView(skipBtn)

        queueCard.addView(btnRow)
        mainLayout.addView(queueCard)

        fetchQueueBtn = Button(this).apply {
            text = "🔄 Refresh Lead Queue"
            setBackgroundColor(Color.parseColor("#0B4F6C"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
            }
            setOnClickListener {
                fetchNextLeadFromQueue()
            }
        }
        mainLayout.addView(fetchQueueBtn)

        // --- SECTION 2: Server Settings ---
        val settingsTitle = TextView(this).apply {
            text = "CONNECTION SETTINGS"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 36, 0, 12)
        }
        mainLayout.addView(settingsTitle)

        val prefs = getSharedPreferences("vaidyamd_crm", MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "http://192.168.1.3:8000")
        val savedToken = prefs.getString("auth_token", "demo-telecaller-token")

        val serverInput = EditText(this).apply {
            hint = "HMS API URL (e.g. http://192.168.1.3:8000)"
            setText(savedUrl)
            textSize = 13f
        }
        mainLayout.addView(serverInput)

        val tokenInput = EditText(this).apply {
            hint = "Telecaller Auth Token"
            setText(savedToken)
            textSize = 13f
        }
        mainLayout.addView(tokenInput)

        val saveBtn = Button(this).apply {
            text = "Save & Reconnect"
            setBackgroundColor(Color.parseColor("#2878A8"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                prefs.edit()
                    .putString("server_url", serverInput.text.toString().trim())
                    .putString("auth_token", tokenInput.text.toString().trim())
                    .apply()
                Toast.makeText(this@MainActivity, "Settings updated!", Toast.LENGTH_SHORT).show()
                startPresenceSync()
                fetchNextLeadFromQueue()
            }
        }
        mainLayout.addView(saveBtn)

        scrollView.addView(mainLayout)
        setContentView(scrollView)

        // Request Permissions on Startup
        requestPermissionsSafely()

        // Initialize Background Services
        startPresenceSync()
        fetchNextLeadFromQueue()
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatTimer?.cancel()
        webSocket?.close(1000, "App closed")
    }

    private fun requestPermissionsSafely() {
        try {
            val perms = arrayOf(
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.READ_PHONE_STATE
            )
            val missing = perms.filter {
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
     * Periodically sends Heartbeat & maintains WebSocket connection for auto-dial trigger.
     */
    private fun startPresenceSync() {
        heartbeatTimer?.cancel()
        heartbeatTimer = java.util.Timer()
        heartbeatTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                sendHeartbeat()
            }
        }, 0, 30000)

        connectWebSocketBridge()
    }

    private fun sendHeartbeat() {
        val prefs = getSharedPreferences("vaidyamd_crm", MODE_PRIVATE)
        val rawServerUrl = prefs.getString("server_url", "http://192.168.1.3:8000")?.trim()?.trimEnd('/') ?: return
        val baseUrl = if (rawServerUrl.endsWith("/api")) rawServerUrl.removeSuffix("/api") else rawServerUrl
        val token = prefs.getString("auth_token", "")?.trim() ?: ""

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val batteryLevel = try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { 100 }

        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("app_version", "1.0.0")
            put("sim_operator", "SIM Active")
            put("battery_level", batteryLevel)
            put("current_state", "idle")
        }

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = payload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url("$baseUrl/api/crm/fleet/heartbeat")
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    statusText.text = "● Offline (Cannot reach server)"
                    statusText.setTextColor(Color.parseColor("#DC2626"))
                    statusText.setBackgroundColor(Color.parseColor("#FEE2E2"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                try {
                    val json = JSONObject(body)
                    val isRevoked = json.optBoolean("is_revoked", false)
                    if (isRevoked) {
                        runOnUiThread { handleDeviceRevoked() }
                        return
                    }
                    runOnUiThread {
                        statusText.text = "● Online (Ready for SIM Calls)"
                        statusText.setTextColor(Color.parseColor("#15803D"))
                        statusText.setBackgroundColor(Color.parseColor("#DCFCE7"))
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusText.text = "● Connected (VaidyaMD Bridge Active)"
                        statusText.setTextColor(Color.parseColor("#15803D"))
                        statusText.setBackgroundColor(Color.parseColor("#DCFCE7"))
                    }
                }
            }
        })
    }

    private fun handleDeviceRevoked() {
        val prefs = getSharedPreferences("vaidyamd_crm", MODE_PRIVATE)
        prefs.edit().clear().apply()

        AlertDialog.Builder(this)
            .setTitle("Access Revoked")
            .setMessage("This mobile device has been de-authorized by the hospital administration.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                statusText.text = "● REVOKED (Access Terminated)"
                statusText.setTextColor(Color.parseColor("#DC2626"))
                statusText.setBackgroundColor(Color.parseColor("#FEE2E2"))
            }
            .show()
    }

    /**
     * Connects to WebSocket to allow PC Web Desk to trigger calls on this phone automatically.
     */
    private fun connectWebSocketBridge() {
        val prefs = getSharedPreferences("vaidyamd_crm", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "http://192.168.1.3:8000")?.trimEnd('/') ?: return
        val token = prefs.getString("auth_token", "") ?: ""

        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://") + "/api/ws/notifications/demo?token=$token"

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "CRM_DIAL_TRIGGER") {
                        val phone = json.optString("phone")
                        val callId = json.optString("call_id")
                        if (phone.isNotEmpty()) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "PC Triggered Dial: $phone", Toast.LENGTH_SHORT).show()
                                placeCallToPatient(callId, phone)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "WS message error", e)
                }
            }
        })
    }

    /**
     * Fetches the next priority lead from the Progressive Queue
     */
    private fun fetchNextLeadFromQueue() {
        val rawServerUrl = prefs.getString("server_url", "http://192.168.1.3:8000")?.trim()?.trimEnd('/') ?: return
        val baseUrl = if (rawServerUrl.endsWith("/api")) rawServerUrl.removeSuffix("/api") else rawServerUrl
        val token = prefs.getString("auth_token", "")?.trim() ?: ""

        leadNameText.text = "Loading next patient..."
        leadPhoneText.text = "Checking queue..."

        val request = Request.Builder()
            .url("$baseUrl/api/crm/dialer/next-lead")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    leadNameText.text = "No Queue Available"
                    leadPhoneText.text = "Check connection settings"
                    leadDetailsText.text = e.message ?: "Failed to connect"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                runOnUiThread {
                    if (!response.isSuccessful) {
                        leadNameText.text = "Queue Error (${response.code})"
                        leadPhoneText.text = "HTTP ${response.code}"
                        leadDetailsText.text = body.take(120)
                        dialBtn.isEnabled = false
                        return@runOnUiThread
                    }
                    if (body.trim() != "null" && body.isNotEmpty()) {
                        try {
                            val json = JSONObject(body)
                            val leadObj = json.getJSONObject("lead")
                            activeLeadId = leadObj.getString("id")
                            activeLeadPhone = leadObj.getString("phone")
                            val name = leadObj.getString("name")
                            val city = leadObj.optString("city", "Location not specified")
                            val callsCount = json.optInt("previous_call_count", 0)

                            leadNameText.text = name
                            leadPhoneText.text = activeLeadPhone
                            leadDetailsText.text = "📍 $city  •  Calls Made: $callsCount"
                            dialBtn.isEnabled = true
                        } catch (e: Exception) {
                            leadNameText.text = "Queue Clear"
                            leadPhoneText.text = "No pending leads"
                            leadDetailsText.text = "All patient leads have been contacted!"
                            dialBtn.isEnabled = false
                        }
                    } else {
                        leadNameText.text = "Queue Clear"
                        leadPhoneText.text = "No pending leads"
                        leadDetailsText.text = "All patient leads have been contacted!"
                        dialBtn.isEnabled = false
                    }
                }
            }
        })
    }

    /**
     * Places physical call via standard Android SIM
     */
    private fun placeCallToPatient(leadIdOrCallId: String, phoneNumber: String) {
        CallStateReceiver.setActiveCallId(leadIdOrCallId)
        currentActiveCallId = leadIdOrCallId

        try {
            val dialIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(dialIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Call permission error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
