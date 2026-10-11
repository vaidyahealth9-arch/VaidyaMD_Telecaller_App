package com.vaidyamd.telecaller

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    // Root views
    private lateinit var contentContainer: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private val navButtons = mutableListOf<Button>()

    // Tab 1: Queue Views
    private lateinit var queueView: View
    private lateinit var queueStatusText: TextView
    private lateinit var queueLeadName: TextView
    private lateinit var queueLeadPhone: TextView
    private lateinit var queueLeadDetails: TextView
    private lateinit var queueDialBtn: Button
    private lateinit var queueNextBtn: Button
    private var activeLeadId: String? = null
    private var activeLeadPhone: String? = null

    // Tab 2: All Leads Views
    private lateinit var leadsView: View
    private lateinit var leadsListContainer: LinearLayout
    private lateinit var leadsSearchInput: EditText
    private lateinit var leadsCountText: TextView

    // Tab 3: History Views
    private lateinit var historyView: View
    private lateinit var historyListContainer: LinearLayout

    // Tab 4: KPIs / Settings Views
    private lateinit var statsView: View
    private lateinit var statCallsText: TextView
    private lateinit var statTalkTimeText: TextView
    private lateinit var statConversionsText: TextView
    private lateinit var statFollowupsText: TextView
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText

    private var heartbeatTimer: java.util.Timer? = null
    private var webSocket: WebSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("vaidyamd_crm", MODE_PRIVATE)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }

        // Top Header
        val header = createHeader()
        rootLayout.addView(header)

        // Dynamic Content Container (Switching 4 tabs)
        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rootLayout.addView(contentContainer)

        // Build 4 tab views
        queueView = buildQueueTab()
        leadsView = buildLeadsTab()
        historyView = buildHistoryTab()
        statsView = buildStatsTab()

        // Bottom Navigation Bar
        bottomNav = buildBottomNav()
        rootLayout.addView(bottomNav)

        setContentView(rootLayout)

        // Select Tab 1 by default
        switchTab(0)

        // Permissions & Background presence
        requestPermissionsSafely()
        startPresenceSync()
        fetchNextLeadFromQueue()
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatTimer?.cancel()
        webSocket?.close(1000, "Activity destroyed")
    }

    // ========================================================================
    // Header & Bottom Navigation
    // ========================================================================

    private fun createHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 28, 32, 24)
            setBackgroundColor(Color.WHITE)
            elevation = 6f

            val logo = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(76, 76).apply {
                    marginEnd = 20
                }
            }
            addView(logo)

            val titles = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val titleText = TextView(this@MainActivity).apply {
                text = "VaidyaMD Telecaller"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0B4F6C"))
            }
            val subtitleText = TextView(this@MainActivity).apply {
                text = "Zero-Cost SIM Dialer & Whisper Sync"
                textSize = 11f
                setTextColor(Color.parseColor("#64748B"))
            }
            titles.addView(titleText)
            titles.addView(subtitleText)
            addView(titles)
        }
    }

    private fun buildBottomNav(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val tabs = listOf("Queue", "Leads", "History", "Stats")
            for (i in tabs.indices) {
                val btn = Button(this@MainActivity).apply {
                    text = tabs[i]
                    textSize = 12f
                    setBackgroundColor(Color.TRANSPARENT)
                    setTextColor(Color.parseColor("#64748B"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { switchTab(i) }
                }
                navButtons.add(btn)
                addView(btn)
            }
        }
    }

    private fun switchTab(index: Int) {
        contentContainer.removeAllViews()
        for (i in navButtons.indices) {
            if (i == index) {
                navButtons[i].setTextColor(Color.parseColor("#0B4F6C"))
                navButtons[i].setTypeface(null, Typeface.BOLD)
            } else {
                navButtons[i].setTextColor(Color.parseColor("#64748B"))
                navButtons[i].setTypeface(null, Typeface.NORMAL)
            }
        }
        when (index) {
            0 -> contentContainer.addView(queueView)
            1 -> {
                contentContainer.addView(leadsView)
                fetchAllLeads()
            }
            2 -> {
                contentContainer.addView(historyView)
                fetchCallHistory()
            }
            3 -> {
                contentContainer.addView(statsView)
                fetchPerformanceStats()
            }
        }
    }

    // ========================================================================
    // TAB 1: DIALER QUEUE
    // ========================================================================

    private fun buildQueueTab(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        queueStatusText = TextView(this).apply {
            text = "● Offline (Connecting...)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 14, 20, 14)
            setTextColor(Color.parseColor("#D97706"))
            setBackgroundColor(Color.parseColor("#FEF3C7"))
        }
        layout.addView(queueStatusText)

        val queueCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
            elevation = 3f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 28 }
        }

        queueLeadName = TextView(this).apply {
            text = "Loading next lead..."
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
        }
        queueCard.addView(queueLeadName)

        queueLeadPhone = TextView(this).apply {
            text = "+91 ----------"
            textSize = 15f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#2878A8"))
            setPadding(0, 8, 0, 8)
        }
        queueCard.addView(queueLeadPhone)

        queueLeadDetails = TextView(this).apply {
            text = "Tap 'Refresh Lead Queue' to begin"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 24)
        }
        queueCard.addView(queueLeadDetails)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        queueDialBtn = Button(this).apply {
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
                    Toast.makeText(this@MainActivity, "No active lead in queue", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnRow.addView(queueDialBtn)

        queueNextBtn = Button(this).apply {
            text = "Next Lead"
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            setTextColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { fetchNextLeadFromQueue() }
        }
        btnRow.addView(queueNextBtn)
        queueCard.addView(btnRow)
        layout.addView(queueCard)

        val refreshBtn = Button(this).apply {
            text = "🔄 Refresh Lead Queue"
            setBackgroundColor(Color.parseColor("#0B4F6C"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
            setOnClickListener { fetchNextLeadFromQueue() }
        }
        layout.addView(refreshBtn)

        scroll.addView(layout)
        return scroll
    }

    // ========================================================================
    // TAB 2: ALL LEADS DIRECTORY
    // ========================================================================

    private fun buildLeadsTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
        }

        leadsSearchInput = EditText(this).apply {
            hint = "🔍 Search patient name, phone, or city..."
            textSize = 13f
            setBackgroundColor(Color.WHITE)
            setPadding(24, 20, 24, 20)
        }
        root.addView(leadsSearchInput)

        val searchBtn = Button(this).apply {
            text = "Search Leads"
            setBackgroundColor(Color.parseColor("#0B4F6C"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            setOnClickListener { fetchAllLeads(leadsSearchInput.text.toString().trim()) }
        }
        root.addView(searchBtn)

        leadsCountText = TextView(this).apply {
            text = "Loading leads..."
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 16, 0, 12)
        }
        root.addView(leadsCountText)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        leadsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(leadsListContainer)
        root.addView(scroll)

        return root
    }

    // ========================================================================
    // TAB 3: CALL HISTORY & SYNC STATUS
    // ========================================================================

    private fun buildHistoryTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
        }

        val title = TextView(this).apply {
            text = "RECENT CALLS & RECORDINGS"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        historyListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(historyListContainer)
        root.addView(scroll)

        return root
    }

    // ========================================================================
    // TAB 4: STATS & CONNECTION SETTINGS
    // ========================================================================

    private fun buildStatsTab(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
        }

        val perfTitle = TextView(this).apply {
            text = "TODAY'S PERFORMANCE"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 12)
        }
        layout.addView(perfTitle)

        // KPI Metric Cards
        val grid1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statCallsText = addKpiCard(grid1, "Calls Made Today", "0")
        statTalkTimeText = addKpiCard(grid1, "Total Talk Time", "0m")
        layout.addView(grid1)

        val grid2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        statConversionsText = addKpiCard(grid2, "Conversions", "0")
        statFollowupsText = addKpiCard(grid2, "Pending Follow-ups", "0")
        layout.addView(grid2)

        // Connection Settings
        val setHeader = TextView(this).apply {
            text = "CONNECTION SETTINGS"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 36, 0, 12)
        }
        layout.addView(setHeader)

        val savedUrl = prefs.getString("server_url", "http://192.168.1.3:8000")
        val savedToken = prefs.getString("auth_token", "demo-telecaller-token")

        serverInput = EditText(this).apply {
            hint = "HMS API URL (e.g. http://192.168.1.3:8000)"
            setText(savedUrl)
            textSize = 13f
        }
        layout.addView(serverInput)

        tokenInput = EditText(this).apply {
            hint = "Telecaller Auth Token"
            setText(savedToken)
            textSize = 13f
        }
        layout.addView(tokenInput)

        val saveBtn = Button(this).apply {
            text = "Save & Reconnect"
            setBackgroundColor(Color.parseColor("#0B4F6C"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
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
        layout.addView(saveBtn)

        scroll.addView(layout)
        return scroll
    }

    private fun addKpiCard(parent: LinearLayout, title: String, value: String): TextView {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.WHITE)
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
        }
        val label = TextView(this).apply {
            text = title
            textSize = 10f
            setTextColor(Color.parseColor("#64748B"))
        }
        val valText = TextView(this).apply {
            text = value
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B4F6C"))
            setPadding(0, 4, 0, 0)
        }
        card.addView(label)
        card.addView(valText)
        parent.addView(card)
        return valText
    }

    // ========================================================================
    // Network Operations: Queue, Leads, Presence, History
    // ========================================================================

    private fun cleanBaseUrl(): String {
        var url = prefs.getString("server_url", "http://192.168.1.3:8000")?.trim()?.trimEnd('/') ?: "http://192.168.1.3:8000"
        if (url.endsWith("/api")) {
            url = url.substring(0, url.length - 4)
        }
        return url
    }

    private fun getAuthToken(): String {
        return prefs.getString("auth_token", "demo-telecaller-token")?.trim() ?: ""
    }

    private fun fetchNextLeadFromQueue() {
        val baseUrl = cleanBaseUrl()
        val token = getAuthToken()

        queueLeadName.text = "Loading next patient..."
        queueLeadPhone.text = "Checking queue..."

        val request = Request.Builder()
            .url("$baseUrl/api/crm/dialer/next-lead")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    queueLeadName.text = "Cannot Reach Server"
                    queueLeadPhone.text = "Check connection settings"
                    queueLeadDetails.text = e.message ?: "Network error"
                    queueDialBtn.isEnabled = false
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                runOnUiThread {
                    if (!response.isSuccessful) {
                        queueLeadName.text = "Queue Error (${response.code})"
                        queueLeadPhone.text = "HTTP ${response.code}"
                        queueLeadDetails.text = body.take(120)
                        queueDialBtn.isEnabled = false
                        return@runOnUiThread
                    }
                    if (body.trim() != "null" && body.isNotEmpty()) {
                        try {
                            val json = JSONObject(body)
                            val leadObj = json.getJSONObject("lead")
                            activeLeadId = leadObj.getString("id")
                            activeLeadPhone = leadObj.getString("phone")
                            val name = leadObj.getString("name")
                            val city = leadObj.optString("city", "City not specified")
                            val callsCount = json.optInt("previous_call_count", 0)

                            queueLeadName.text = name
                            queueLeadPhone.text = activeLeadPhone
                            queueLeadDetails.text = "📍 $city  •  Previous Calls: $callsCount"
                            queueDialBtn.isEnabled = true
                        } catch (e: Exception) {
                            queueLeadName.text = "Queue Clear"
                            queueLeadPhone.text = "No pending leads"
                            queueLeadDetails.text = "All patient leads have been contacted!"
                            queueDialBtn.isEnabled = false
                        }
                    } else {
                        queueLeadName.text = "Queue Clear"
                        queueLeadPhone.text = "No pending leads"
                        queueLeadDetails.text = "All patient leads have been contacted!"
                        queueDialBtn.isEnabled = false
                    }
                }
            }
        })
    }

    private fun fetchAllLeads(query: String = "") {
        val baseUrl = cleanBaseUrl()
        val token = getAuthToken()

        leadsListContainer.removeAllViews()
        leadsCountText.text = "Searching leads..."

        val url = if (query.isNotEmpty()) "$baseUrl/api/crm/leads?search=$query&limit=50" else "$baseUrl/api/crm/leads?limit=50"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    leadsCountText.text = "Failed to load leads: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                runOnUiThread {
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(body)
                            val items = json.getJSONArray("items")
                            leadsCountText.text = "Showing ${items.length()} patient leads"
                            renderLeadsList(items)
                        } catch (e: Exception) {
                            leadsCountText.text = "Error parsing leads"
                        }
                    } else {
                        leadsCountText.text = "Error HTTP ${response.code}"
                    }
                }
            }
        })
    }

    private fun renderLeadsList(items: JSONArray) {
        leadsListContainer.removeAllViews()
        for (i in 0 until items.length()) {
            val lead = items.getJSONObject(i)
            val name = lead.getString("name")
            val phone = lead.getString("phone")
            val city = lead.optString("city", "N/A")
            val status = lead.optString("status", "NEW").replace('_', ' ')

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                setBackgroundColor(Color.WHITE)
                elevation = 2f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
            }

            val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val nameView = TextView(this).apply {
                text = name
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val statusBadge = TextView(this).apply {
                text = status
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0B4F6C"))
                setBackgroundColor(Color.parseColor("#E0F2FE"))
                setPadding(12, 6, 12, 6)
            }
            titleRow.addView(nameView)
            titleRow.addView(statusBadge)
            card.addView(titleRow)

            val details = TextView(this).apply {
                text = "$phone  •  📍 $city"
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, 8, 0, 16)
            }
            card.addView(details)

            val callBtn = Button(this).apply {
                text = "📞 Call $phone"
                setBackgroundColor(Color.parseColor("#15803D"))
                setTextColor(Color.WHITE)
                textSize = 12f
                setOnClickListener {
                    val id = lead.getString("id")
                    placeCallToPatient(id, phone)
                }
            }
            card.addView(callBtn)

            leadsListContainer.addView(card)
        }
    }

    private fun fetchCallHistory() {
        historyListContainer.removeAllViews()
        val notice = TextView(this).apply {
            text = "Automated Call Recording Sync Active.\nAny outgoing calls made from this device are automatically checked upon hang-up and synced to the VaidyaMD server for Faster-Whisper transcription."
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }
        historyListContainer.addView(notice)
    }

    private fun fetchPerformanceStats() {
        val baseUrl = cleanBaseUrl()
        val token = getAuthToken()

        val request = Request.Builder()
            .url("$baseUrl/api/crm/analytics/dashboard")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                runOnUiThread {
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(body)
                            statCallsText.text = json.optInt("calls_made_today", 0).toString()
                            statConversionsText.text = json.optInt("conversions_total", 0).toString()
                            statFollowupsText.text = json.optInt("pending_follow_ups_today", 0).toString()
                        } catch (e: Exception) {}
                    }
                }
            }
        })
    }

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
        val baseUrl = cleanBaseUrl()
        val token = getAuthToken()

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
                    queueStatusText.text = "● Offline (${e.message ?: "Server Unreachable"})"
                    queueStatusText.setTextColor(Color.parseColor("#DC2626"))
                    queueStatusText.setBackgroundColor(Color.parseColor("#FEE2E2"))
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
                        queueStatusText.text = "● Online (Ready for SIM Calls)"
                        queueStatusText.setTextColor(Color.parseColor("#15803D"))
                        queueStatusText.setBackgroundColor(Color.parseColor("#DCFCE7"))
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        queueStatusText.text = "● Connected (VaidyaMD Bridge Active)"
                        queueStatusText.setTextColor(Color.parseColor("#15803D"))
                        queueStatusText.setBackgroundColor(Color.parseColor("#DCFCE7"))
                    }
                }
            }
        })
    }

    private fun handleDeviceRevoked() {
        prefs.edit().clear().apply()
        AlertDialog.Builder(this)
            .setTitle("Access Revoked")
            .setMessage("This mobile device has been de-authorized by the hospital administration.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                queueStatusText.text = "● REVOKED (Access Terminated)"
                queueStatusText.setTextColor(Color.parseColor("#DC2626"))
                queueStatusText.setBackgroundColor(Color.parseColor("#FEE2E2"))
            }
            .show()
    }

    private fun connectWebSocketBridge() {
        val baseUrl = cleanBaseUrl()
        val token = getAuthToken()

        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/api/ws/notifications/demo?token=$token"
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
                } catch (e: Exception) {}
            }
        })
    }

    private fun placeCallToPatient(leadIdOrCallId: String, phoneNumber: String) {
        CallStateReceiver.setActiveCallId(leadIdOrCallId)
        try {
            val dialIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(dialIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Dial permission error: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        } catch (e: Exception) {}
    }
}
