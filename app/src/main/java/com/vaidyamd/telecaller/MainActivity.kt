package com.vaidyamd.telecaller

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val callStateReceiver = CallStateReceiver()
    private var isReceiverRegistered = false

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
    private lateinit var queueWhatsAppBtn: Button
    private var activeLeadId: String? = null
    private var activeLeadPhone: String? = null
    private var activeLeadFullName: String? = null

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

    // Media Player for call recordings
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingCallId: String? = null

    // QR Scanner Activity Result Launcher
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedQrPayload(result.contents)
        }
    }

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
        registerCallListeners()
        setupCallEndedCallback()
        handleDeepLink(intent)
        startPresenceSync()
        fetchNextLeadFromQueue()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatTimer?.cancel()
        webSocket?.close(1000, "Activity destroyed")
        mediaPlayer?.release()
        try {
            if (isReceiverRegistered) {
                unregisterReceiver(callStateReceiver)
                isReceiverRegistered = false
            }
        } catch (e: Exception) {}
    }

    // ========================================================================
    // Header & Bottom Navigation
    // ========================================================================

    private fun createHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(36, 32, 36, 28)
            setBackgroundColor(Color.WHITE)
            elevation = 6f

            val logo = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(80, 80).apply {
                    marginEnd = 24
                }
            }
            addView(logo)

            val titles = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val titleText = TextView(this@MainActivity).apply {
                text = "VaidyaMD Telecaller"
                textSize = 19f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0B4F6C"))
            }
            val subtitleText = TextView(this@MainActivity).apply {
                text = "Modern SIM Dialer & Faster-Whisper Sync"
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
            val tabs = listOf("Queue", "Leads", "History", "Settings")
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
            setPadding(24, 16, 24, 16)
            setTextColor(Color.parseColor("#D97706"))
            setBackgroundColor(Color.parseColor("#FEF3C7"))
        }
        layout.addView(queueStatusText)

        val queueCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.WHITE)
            elevation = 4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 28 }
        }

        queueLeadName = TextView(this).apply {
            text = "Loading next patient lead..."
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
        }
        queueCard.addView(queueLeadName)

        queueLeadPhone = TextView(this).apply {
            text = "+91 ----------"
            textSize = 16f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B4F6C"))
            setPadding(0, 10, 0, 10)
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
                marginEnd = 12
            }
            setOnClickListener {
                val leadId = activeLeadId
                val phone = activeLeadPhone
                val name = activeLeadFullName
                if (leadId != null && phone != null) {
                    initiateCallAndDial(leadId, phone, "mobile", name)
                } else {
                    Toast.makeText(this@MainActivity, "No active lead in queue", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnRow.addView(queueDialBtn)

        queueWhatsAppBtn = Button(this).apply {
            text = "💬 WhatsApp"
            setBackgroundColor(Color.parseColor("#25D366"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
            setOnClickListener {
                val phone = activeLeadPhone
                val name = activeLeadFullName
                if (phone != null) {
                    openWhatsApp(phone, name ?: "Patient")
                }
            }
        }
        btnRow.addView(queueWhatsAppBtn)

        queueNextBtn = Button(this).apply {
            text = "🔄 Next"
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            setTextColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            setOnClickListener { fetchNextLeadFromQueue() }
        }
        btnRow.addView(queueNextBtn)
        queueCard.addView(btnRow)
        layout.addView(queueCard)

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
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString()?.trim() ?: ""
                    if (q.length >= 2 || q.isEmpty()) {
                        fetchAllLeads(q)
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
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
            text = "Loading patient leads..."
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
    // TAB 3: CALL HISTORY & IN-APP AUDIO PLAYER
    // ========================================================================

    private fun buildHistoryTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
        }

        val title = TextView(this).apply {
            text = "RECENT CALLS & TRANSCRIPTS"
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

        // QR Camera Pairing Section
        val pairSectionTitle = TextView(this).apply {
            text = "INSTANT DEVICE PAIRING"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 36, 0, 12)
        }
        layout.addView(pairSectionTitle)

        val scanQrBtn = Button(this).apply {
            text = "📷 Scan QR to Pair Phone"
            setBackgroundColor(Color.parseColor("#0B4F6C"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { launchQrScanner() }
        }
        layout.addView(scanQrBtn)

        val setHeader = TextView(this).apply {
            text = "MANUAL CONNECTION SETTINGS"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 28, 0, 12)
        }
        layout.addView(setHeader)

        val savedUrl = prefs.getString("server_url", BuildConfig.DEFAULT_SERVER_URL) ?: BuildConfig.DEFAULT_SERVER_URL
        val savedToken = prefs.getString("auth_token", "") ?: ""

        serverInput = EditText(this).apply {
            hint = "HMS API URL (e.g. ${BuildConfig.DEFAULT_SERVER_URL})"
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
            setBackgroundColor(Color.parseColor("#334155"))
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

        val updateBtn = Button(this).apply {
            text = "🔄 Check for App Update"
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            setTextColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            setOnClickListener { checkAppUpdate(manual = true) }
        }
        layout.addView(updateBtn)

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
    // QR Scanner & Deep Linking
    // ========================================================================

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setPrompt("Scan VaidyaMD CRM Pairing QR Code")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        qrScanLauncher.launch(options)
    }

    private fun handleScannedQrPayload(payload: String) {
        try {
            if (payload.startsWith("vaidyamd://pair") || payload.contains("/api/crm/pair")) {
                val uri = Uri.parse(payload)
                val token = uri.getQueryParameter("token") ?: ""
                val server = uri.getQueryParameter("server") ?: ""
                applyPairingCredentials(server, token)
            } else if (payload.startsWith("{")) {
                val json = JSONObject(payload)
                val token = json.optString("auth_token", json.optString("token", ""))
                val server = json.optString("server_url", "")
                val userName = json.optString("user_name", "")
                val userId = json.optString("user_id", "")
                applyPairingCredentials(server, token, userId, userName)
            } else {
                Toast.makeText(this, "Unrecognized QR payload format", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "QR Parse Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "vaidyamd" && data.host == "pair") {
            val token = data.getQueryParameter("token") ?: ""
            val server = data.getQueryParameter("server") ?: ""
            applyPairingCredentials(server, token)
        }
    }

    private fun applyPairingCredentials(server: String, token: String, userId: String = "", userName: String = "") {
        if (token.isNotEmpty()) {
            val editor = prefs.edit().putString("auth_token", token)
            if (server.isNotEmpty()) editor.putString("server_url", server)
            if (userId.isNotEmpty()) editor.putString("user_id", userId)
            if (userName.isNotEmpty()) editor.putString("user_name", userName)
            editor.apply()

            if (::serverInput.isInitialized) serverInput.setText(cleanBaseUrl())
            if (::tokenInput.isInitialized) tokenInput.setText(token)

            Toast.makeText(this, "Device Paired Successfully${if (userName.isNotEmpty()) " with $userName" else ""}!", Toast.LENGTH_LONG).show()
            startPresenceSync()
            fetchNextLeadFromQueue()
        }
    }

    // ========================================================================
    // Post-Call Outcome & Symmetrical Workflow
    // ========================================================================

    private fun setupCallEndedCallback() {
        CallStateReceiver.setOnCallEndedListener { callId, durationSec, initiatedFrom, leadName ->
            if (initiatedFrom == "mobile") {
                showPostCallDispositionDialog(callId, durationSec, leadName)
            } else {
                Toast.makeText(this, "PC Call finished: ${durationSec}s. Notes logged on web desk.", Toast.LENGTH_SHORT).show()
            }
            fetchCallHistory()
        }
    }

    private fun showPostCallDispositionDialog(callId: String, durationSec: Int, leadName: String?) {
        runOnUiThread {
            val dialogView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 32, 40, 24)
                setBackgroundColor(Color.WHITE)
            }

            val title = TextView(this).apply {
                text = "Call Ended (${durationSec / 60}m ${durationSec % 60}s)"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
            }
            dialogView.addView(title)

            val sub = TextView(this).apply {
                text = "Patient: ${leadName ?: "Patient Lead"}"
                textSize = 13f
                setTextColor(Color.parseColor("#0B4F6C"))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 6, 0, 18)
            }
            dialogView.addView(sub)

            val outcomeLabel = TextView(this).apply {
                text = "SELECT CALL OUTCOME:"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 0, 0, 8)
            }
            dialogView.addView(outcomeLabel)

            val dispositions = listOf(
                Pair("Interested in Care", "interested"),
                Pair("Appointment Booked", "converted_appointment"),
                Pair("Callback / Follow-Up", "callback_requested"),
                Pair("Busy / No Answer", "no_answer"),
                Pair("Not Interested", "not_interested")
            )

            var selectedDisp = "interested"
            val radioGroup = RadioGroup(this)
            for (i in dispositions.indices) {
                val (label, value) = dispositions[i]
                val rb = RadioButton(this).apply {
                    text = label
                    textSize = 13f
                    id = i
                    if (i == 0) isChecked = true
                }
                radioGroup.addView(rb)
            }
            radioGroup.setOnCheckedChangeListener { _, checkedId ->
                if (checkedId in dispositions.indices) {
                    selectedDisp = dispositions[checkedId].second
                }
            }
            dialogView.addView(radioGroup)

            val notesInput = EditText(this).apply {
                hint = "Enter call notes / patient summary..."
                textSize = 12f
                setBackgroundColor(Color.parseColor("#F8FAFC"))
                setPadding(20, 16, 20, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16 }
            }
            dialogView.addView(notesInput)

            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton("Save Outcome") { _, _ ->
                    submitCallDisposition(callId, selectedDisp, notesInput.text.toString().trim())
                }
                .setNegativeButton("Skip", null)
                .create()

            dialog.show()
        }
    }

    private fun submitCallDisposition(callId: String, disposition: String, notes: String) {
        val baseUrl = cleanBaseUrl()
        val token = getAuthToken()

        val payload = JSONObject().apply {
            put("disposition", disposition)
            put("notes", notes)
            val leadStatus = when (disposition) {
                "converted_appointment" -> "converted"
                "interested" -> "interested"
                "callback_requested" -> "follow_up"
                "not_interested" -> "lost"
                else -> "contacted"
            }
            put("lead_status", leadStatus)
        }

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = payload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url("$baseUrl/api/crm/calls/$callId/disposition")
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to save outcome: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Call outcome saved!", Toast.LENGTH_SHORT).show()
                    fetchCallHistory()
                    fetchPerformanceStats()
                }
            }
        })
    }

    // ========================================================================
    // WhatsApp Integration
    // ========================================================================

    private fun openWhatsApp(phoneNumber: String, leadName: String = "Patient") {
        try {
            val cleanPhone = phoneNumber.replace(Regex("[^0-9]"), "").takeLast(10)
            val msg = "Hello $leadName, thank you for contacting VaidyaMD. How can our clinic team assist you today?"
            val uri = Uri.parse("https://wa.me/91$cleanPhone?text=" + Uri.encode(msg))
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ========================================================================
    // Network Operations: Queue, Leads, Presence, History
    // ========================================================================

    private fun cleanBaseUrl(): String {
        var url = prefs.getString("server_url", BuildConfig.DEFAULT_SERVER_URL)?.trim()?.trimEnd('/') ?: BuildConfig.DEFAULT_SERVER_URL
        if (url.endsWith("/api")) {
            url = url.substring(0, url.length - 4)
        }
        return url
    }

    private fun getAuthToken(): String {
        return prefs.getString("auth_token", "")?.trim() ?: ""
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
                    queueWhatsAppBtn.isEnabled = false
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
                        queueWhatsAppBtn.isEnabled = false
                        return@runOnUiThread
                    }
                    if (body.trim() != "null" && body.isNotEmpty()) {
                        try {
                            val json = JSONObject(body)
                            val leadObj = json.getJSONObject("lead")
                            activeLeadId = leadObj.getString("id")
                            activeLeadPhone = leadObj.getString("phone")
                            val name = leadObj.getString("name")
                            activeLeadFullName = name
                            val city = leadObj.optString("city", "City not specified")
                            val callsCount = json.optInt("previous_call_count", 0)

                            queueLeadName.text = name
                            queueLeadPhone.text = activeLeadPhone
                            val lastNotes = json.optString("last_notes", "")
                            if (lastNotes.isNotEmpty() && lastNotes != "null") {
                                queueLeadDetails.text = "📍 $city  •  Previous Calls: $callsCount\n💬 Note: $lastNotes"
                            } else {
                                queueLeadDetails.text = "📍 $city  •  Previous Calls: $callsCount"
                            }
                            queueDialBtn.isEnabled = true
                            queueWhatsAppBtn.isEnabled = true
                        } catch (e: Exception) {
                            queueLeadName.text = "Queue Clear"
                            queueLeadPhone.text = "No pending leads"
                            queueLeadDetails.text = "All patient leads have been contacted!"
                            queueDialBtn.isEnabled = false
                            queueWhatsAppBtn.isEnabled = false
                        }
                    } else {
                        queueLeadName.text = "Queue Clear"
                        queueLeadPhone.text = "No pending leads"
                        queueLeadDetails.text = "All patient leads have been contacted!"
                        queueDialBtn.isEnabled = false
                        queueWhatsAppBtn.isEnabled = false
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
                setPadding(28, 24, 28, 24)
                setBackgroundColor(Color.WHITE)
                elevation = 3f
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
            val (badgeTextCol, badgeBgCol) = when (status.uppercase()) {
                "NEW" -> Pair("#1D4ED8", "#EFF6FF")
                "FOLLOW UP" -> Pair("#B45309", "#FEF3C7")
                "INTERESTED" -> Pair("#15803D", "#DCFCE7")
                "CONVERTED" -> Pair("#0B4F6C", "#E0F2FE")
                "LOST", "DO NOT CALL" -> Pair("#B91C1C", "#FEE2E2")
                else -> Pair("#334155", "#F1F5F9")
            }
            val statusBadge = TextView(this).apply {
                text = status
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(badgeTextCol))
                setBackgroundColor(Color.parseColor(badgeBgCol))
                setPadding(14, 6, 14, 6)
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

            val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            val callBtn = Button(this).apply {
                text = "📞 Call"
                setBackgroundColor(Color.parseColor("#15803D"))
                setTextColor(Color.WHITE)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                    marginEnd = 12
                }
                setOnClickListener {
                    val id = lead.getString("id")
                    initiateCallAndDial(id, phone, "mobile", name)
                }
            }
            actionsRow.addView(callBtn)

            val waBtn = Button(this).apply {
                text = "💬 WhatsApp"
                setBackgroundColor(Color.parseColor("#25D366"))
                setTextColor(Color.WHITE)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { openWhatsApp(phone, name) }
            }
            actionsRow.addView(waBtn)

            card.addView(actionsRow)
            leadsListContainer.addView(card)
        }
    }

    private fun fetchCallHistory() {
        historyListContainer.removeAllViews()
        val baseUrl = cleanBaseUrl()
        val token = getAuthToken()

        val loading = TextView(this).apply {
            text = "Loading recent calls & recordings..."
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(24, 24, 24, 24)
        }
        historyListContainer.addView(loading)

        val request = Request.Builder()
            .url("$baseUrl/api/crm/calls?limit=25")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    historyListContainer.removeAllViews()
                    val err = TextView(this@MainActivity).apply {
                        text = "Failed to load call history: ${e.message}"
                        textSize = 12f
                        setTextColor(Color.parseColor("#DC2626"))
                        setPadding(24, 24, 24, 24)
                    }
                    historyListContainer.addView(err)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                runOnUiThread {
                    historyListContainer.removeAllViews()
                    if (!response.isSuccessful) {
                        val err = TextView(this@MainActivity).apply {
                            text = "Error loading history (HTTP ${response.code})"
                            textSize = 12f
                            setTextColor(Color.parseColor("#DC2626"))
                            setPadding(24, 24, 24, 24)
                        }
                        historyListContainer.addView(err)
                        return@runOnUiThread
                    }
                    try {
                        val items = JSONArray(body)
                        if (items.length() == 0) {
                            val empty = TextView(this@MainActivity).apply {
                                text = "No call logs recorded yet.\nOutgoing SIM calls will appear here along with transcripts."
                                textSize = 13f
                                setTextColor(Color.parseColor("#64748B"))
                                setPadding(32, 48, 32, 48)
                                gravity = Gravity.CENTER
                            }
                            historyListContainer.addView(empty)
                            return@runOnUiThread
                        }
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val callId = item.optString("id", "")
                            val leadName = item.optString("lead_name", "Patient")
                            val phone = item.optString("phone_dialed", "Unknown")
                            val duration = item.optInt("duration_seconds", 0)
                            val disp = item.optString("disposition", "Not Logged").replace('_', ' ')
                            val transcriptStatus = item.optString("transcript_status", "pending")
                            val transcriptText = item.optString("transcript_text", "")
                            val recordingPath = item.optString("recording_file_path", "")

                            val card = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(28, 22, 28, 22)
                                setBackgroundColor(Color.WHITE)
                                elevation = 2f
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = 16 }
                            }

                            val row1 = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                            }
                            val phoneTxt = TextView(this@MainActivity).apply {
                                text = "📞 $phone ($leadName)"
                                textSize = 14f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.parseColor("#0F172A"))
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            val durTxt = TextView(this@MainActivity).apply {
                                text = "${duration / 60}m ${duration % 60}s"
                                textSize = 12f
                                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                                setTextColor(Color.parseColor("#0B4F6C"))
                            }
                            row1.addView(phoneTxt)
                            row1.addView(durTxt)
                            card.addView(row1)

                            val dispTxt = TextView(this@MainActivity).apply {
                                text = "Outcome: ${disp.capitalize()}"
                                textSize = 12f
                                setTextColor(Color.parseColor("#475569"))
                                setPadding(0, 6, 0, 4)
                            }
                            card.addView(dispTxt)

                            // Transcript box
                            val sttTxt = TextView(this@MainActivity).apply {
                                if (transcriptText.isNotEmpty() && !transcriptText.startsWith("[")) {
                                    text = "📝 Whisper: \"$transcriptText\""
                                    setTextColor(Color.parseColor("#0F766E"))
                                } else {
                                    text = "🎙 Whisper Status: $transcriptStatus"
                                    setTextColor(Color.parseColor("#94A3B8"))
                                }
                                textSize = 11f
                                setPadding(0, 4, 0, 8)
                            }
                            card.addView(sttTxt)

                            // Audio player button if recording exists
                            if (recordingPath.isNotEmpty() && recordingPath != "null") {
                                val audioBtn = Button(this@MainActivity).apply {
                                    text = "▶ Play Recording"
                                    textSize = 11f
                                    setBackgroundColor(Color.parseColor("#F1F5F9"))
                                    setTextColor(Color.parseColor("#0B4F6C"))
                                    setTypeface(null, Typeface.BOLD)
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    setOnClickListener {
                                        val fullAudioUrl = "$baseUrl/api/crm/calls/$callId/recording"
                                        playCallAudio(callId, fullAudioUrl, this)
                                    }
                                }
                                card.addView(audioBtn)
                            }

                            historyListContainer.addView(card)
                        }
                    } catch (e: Exception) {
                        val err = TextView(this@MainActivity).apply {
                            text = "Error parsing history: ${e.message}"
                            textSize = 12f
                            setTextColor(Color.parseColor("#DC2626"))
                            setPadding(24, 24, 24, 24)
                        }
                        historyListContainer.addView(err)
                    }
                }
            }
        })
    }

    private fun playCallAudio(callId: String, audioUrl: String, playBtn: Button) {
        try {
            if (currentPlayingCallId == callId && mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                currentPlayingCallId = null
                playBtn.text = "▶ Play Recording"
                return
            }

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer()
            mediaPlayer?.setDataSource(audioUrl)
            mediaPlayer?.prepareAsync()
            playBtn.text = "⏳ Loading..."

            mediaPlayer?.setOnPreparedListener { mp ->
                mp.start()
                currentPlayingCallId = callId
                playBtn.text = "⏸ Stop Audio"
            }
            mediaPlayer?.setOnCompletionListener {
                playBtn.text = "▶ Play Recording"
                currentPlayingCallId = null
            }
            mediaPlayer?.setOnErrorListener { _, _, _ ->
                playBtn.text = "▶ Play Recording"
                Toast.makeText(this, "Cannot stream recording audio", Toast.LENGTH_SHORT).show()
                true
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Audio playback error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
                CallStateReceiver.flushPendingCallEnds(this@MainActivity)
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

        val appVer = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.3.0"
        } catch (e: Exception) { "1.3.0" }

        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("app_version", appVer)
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

                    val returnedUserId = json.optString("user_id", "")
                    if (returnedUserId.isNotEmpty() && returnedUserId != prefs.getString("user_id", "")) {
                        prefs.edit().putString("user_id", returnedUserId).apply()
                        connectWebSocketBridge()
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
        prefs.edit().remove("auth_token").remove("user_id").putBoolean("is_revoked", true).apply()
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
        val userId = prefs.getString("user_id", "") ?: ""
        val channelId = if (userId.isNotEmpty()) userId else "demo"

        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/api/ws/notifications/$channelId?token=$token"
        val request = Request.Builder().url(wsUrl).build()

        webSocket?.close(1000, "Reconnecting")
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "CRM_DIAL_TRIGGER") {
                        val phone = json.optString("phone")
                        val callId = json.optString("call_id")
                        val source = json.optString("initiated_from", "web")
                        val leadName = json.optString("lead_name", "")
                        if (phone.isNotEmpty()) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "PC Triggered Dial: $phone", Toast.LENGTH_SHORT).show()
                                placeCallDirect(callId, phone, source, leadName)
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        })
    }

    private fun initiateCallAndDial(leadId: String, phoneNumber: String, initiatedFrom: String = "mobile", leadName: String? = null) {
        val baseUrl = cleanBaseUrl()
        val token = getAuthToken()

        Toast.makeText(this, "Starting call session...", Toast.LENGTH_SHORT).show()

        val formBody = FormBody.Builder()
            .add("lead_id", leadId)
            .add("phone", phoneNumber)
            .add("initiated_from", initiatedFrom)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/api/crm/calls/start")
            .header("Authorization", "Bearer $token")
            .post(formBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Calling (Offline session): ${e.message}", Toast.LENGTH_SHORT).show()
                    placeCallDirect(leadId, phoneNumber, initiatedFrom, leadName)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var assignedCallId = leadId
                if (response.isSuccessful && body.isNotEmpty()) {
                    try {
                        val json = JSONObject(body)
                        assignedCallId = json.optString("id", leadId)
                    } catch (e: Exception) {}
                }
                runOnUiThread {
                    placeCallDirect(assignedCallId, phoneNumber, initiatedFrom, leadName)
                }
            }
        })
    }

    private fun placeCallDirect(callId: String, phoneNumber: String, initiatedFrom: String = "mobile", leadName: String? = null) {
        CallStateReceiver.setActiveCallId(callId, initiatedFrom, leadName)
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
            val perms = mutableListOf(
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.CAMERA
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(android.Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            val missing = perms.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 101)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Permission request failed: ${e.message}")
        }
    }

    private fun registerCallListeners() {
        try {
            if (!isReceiverRegistered) {
                val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(callStateReceiver, filter, RECEIVER_EXPORTED)
                } else {
                    registerReceiver(callStateReceiver, filter)
                }
                isReceiverRegistered = true
                Log.i("MainActivity", "CallStateReceiver registered dynamically")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to register CallStateReceiver: ${e.message}")
        }

        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    tm.registerTelephonyCallback(mainExecutor, object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            CallStateReceiver.handleStateChange(this@MainActivity, state)
                        }
                    })
                } else {
                    @Suppress("DEPRECATION")
                    tm.listen(object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            CallStateReceiver.handleStateChange(this@MainActivity, state)
                        }
                    }, PhoneStateListener.LISTEN_CALL_STATE)
                }
                Log.i("MainActivity", "TelephonyCallback/PhoneStateListener registered")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to register TelephonyCallback: ${e.message}")
        }
    }

    private fun checkAppUpdate(manual: Boolean = false) {
        val baseUrl = cleanBaseUrl()
        val currentCode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode
            }
        } catch (e: Exception) { 1 }

        val request = Request.Builder()
            .url("$baseUrl/api/crm/app/version")
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (manual) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Update check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                if (response.isSuccessful && body.isNotEmpty()) {
                    try {
                        val json = JSONObject(body)
                        val remoteCode = json.optInt("version_code", 0)
                        val versionName = json.optString("version_name", "")
                        val changelog = json.optString("changelog", "Performance improvements.")
                        val downloadPath = json.optString("download_url", "/api/crm/app/download")

                        if (remoteCode > currentCode) {
                            runOnUiThread {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("New Update Available: v$versionName")
                                    .setMessage(changelog)
                                    .setPositiveButton("Download & Install") { _, _ ->
                                        val fullUrl = if (downloadPath.startsWith("http://") || downloadPath.startsWith("https://")) {
                                            downloadPath
                                        } else {
                                            "$baseUrl$downloadPath"
                                        }
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                                        startActivity(intent)
                                    }
                                    .setNegativeButton("Later", null)
                                    .show()
                            }
                        } else if (manual) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "You are on the latest version (v${versionName.ifEmpty { "1.3.0" }})", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        })
    }
}
