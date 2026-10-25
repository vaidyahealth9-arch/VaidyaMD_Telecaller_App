package com.vaidyamd.telecaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.telephony.TelephonyManager
import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Listens for Call State transitions via dynamic BroadcastReceiver and TelephonyCallback.
 * When call terminates (CALL_STATE_IDLE after OFFHOOK or Dialing):
 * 1. Computes talk time duration and immediately informs backend /api/crm/calls/{call_id}/end
 * 2. Scans storage + MediaStore with a 2.5-second buffer for native call recording audio and triggers Faster-Whisper sync
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStateReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var activeCallId: String? = null
        private var callStartTime: Long = 0
        private var offhookStartTime: Long = 0
        private val httpClient = OkHttpClient()

        fun setActiveCallId(callId: String) {
            activeCallId = callId
            callStartTime = System.currentTimeMillis()
            offhookStartTime = 0
            Log.i(TAG, "Active call ID registered: $callId at $callStartTime")
        }

        fun handleStateChange(context: Context, state: Int) {
            if (lastState == state) return
            Log.d(TAG, "Telephony state transitioned: $lastState -> $state (activeCallId: $activeCallId)")

            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    offhookStartTime = System.currentTimeMillis()
                    Log.i(TAG, "Call connected (OFFHOOK). Timer started.")
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    val targetCallId = activeCallId
                    if (targetCallId != null) {
                        val durationSec = when {
                            offhookStartTime > 0 -> ((System.currentTimeMillis() - offhookStartTime) / 1000).toInt()
                            callStartTime > 0 -> ((System.currentTimeMillis() - callStartTime) / 1000).toInt()
                            else -> 0
                        }
                        Log.i(TAG, "Call finished. Duration: ${durationSec}s for callId: $targetCallId")

                        // Clear activeCallId to prevent duplicate executions from dual receiver/callback
                        activeCallId = null
                        val wasConnected = offhookStartTime > 0
                        offhookStartTime = 0
                        callStartTime = 0

                        // 1. Immediately report call duration and completion to backend
                        notifyCallEnded(context, targetCallId, durationSec)

                        // 2. Schedule recording scan with a 2.5s delay to allow phone dialer to flush audio file
                        Handler(Looper.getMainLooper()).postDelayed({
                            uploadLatestCallRecording(context, targetCallId)
                        }, 2500)
                    }
                }
            }
            lastState = state
        }

        private fun notifyCallEnded(context: Context, callId: String, durationSec: Int) {
            val prefs = context.getSharedPreferences("vaidyamd_crm", Context.MODE_PRIVATE)
            var serverBaseUrl = prefs.getString("server_url", "http://192.168.1.4:8000")?.trim()?.trimEnd('/') ?: "http://192.168.1.4:8000"
            if (serverBaseUrl.endsWith("/api")) {
                serverBaseUrl = serverBaseUrl.substring(0, serverBaseUrl.length - 4)
            }
            val authToken = prefs.getString("auth_token", "") ?: ""

            val formBody = FormBody.Builder()
                .add("duration_seconds", durationSec.toString())
                .build()

            val request = Request.Builder()
                .url("$serverBaseUrl/api/crm/calls/$callId/end")
                .header("Authorization", "Bearer $authToken")
                .post(formBody)
                .build()

            httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    Log.e(TAG, "Failed to report call end to server: ${e.message}")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    Log.i(TAG, "Call end registered on server (HTTP ${response.code})")
                }
            })
        }

        private fun uploadLatestCallRecording(context: Context, targetCallId: String) {
            Log.i(TAG, "Searching for audio recording to upload for call $targetCallId")

            // Standard locations across Infinix, Tecno, Samsung, Xiaomi, OnePlus, Oppo, Vivo
            val searchDirs = listOf(
                File("/storage/emulated/0/PhoneRecord"),
                File("/storage/emulated/0/PhoneRecord/Call"),
                File("/storage/emulated/0/Recordings/Call"),
                File("/storage/emulated/0/Recordings"),
                File("/storage/emulated/0/Record"),
                File("/storage/emulated/0/Audio/Recordings"),
                File("/storage/emulated/0/MIUI/sound_recorder/call_rec"),
                File("/storage/emulated/0/Music/Recordings"),
                File("/storage/emulated/0/Android/data/com.transsion.recorder/files")
            )

            var latestFile: File? = null
            for (dir in searchDirs) {
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles { f ->
                        f.extension.lowercase() in listOf("mp3", "m4a", "aac", "wav", "amr")
                    }
                    val candidate = files?.maxByOrNull { it.lastModified() }
                    if (candidate != null && (latestFile == null || candidate.lastModified() > latestFile.lastModified())) {
                        latestFile = candidate
                    }
                }
            }

            // Also query MediaStore for newly added audio in the last 10 minutes
            if (latestFile == null) {
                try {
                    val projection = arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_MODIFIED)
                    val tenMinutesAgo = (System.currentTimeMillis() / 1000) - 600
                    val cursor = context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        "${MediaStore.Audio.Media.DATE_MODIFIED} >= ?",
                        arrayOf(tenMinutesAgo.toString()),
                        "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val pathCol = it.getColumnIndex(MediaStore.Audio.Media.DATA)
                            if (pathCol != -1) {
                                val path = it.getString(pathCol)
                                val f = File(path)
                                if (f.exists() && f.length() > 0) {
                                    latestFile = f
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore scan error: ${e.message}")
                }
            }

            if (latestFile != null) {
                Log.i(TAG, "Found call recording file: ${latestFile?.absolutePath} (${latestFile?.length()} bytes)")
                CallAudioUploader.upload(context, targetCallId, latestFile!!)
            } else {
                Log.w(TAG, "No native call recording file discovered. Auto Call Recording in Infinix Phone Settings must be turned ON.")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val state = when (stateStr) {
                TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                else -> TelephonyManager.CALL_STATE_IDLE
            }

            handleStateChange(context, state)
        }
    }
}
