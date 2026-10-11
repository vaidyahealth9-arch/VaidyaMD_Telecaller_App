package com.vaidyamd.telecaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import java.io.File

/**
 * Listens for Call Hang-Up state.
 * When the call terminates (CALL_STATE_IDLE after OFFHOOK):
 * 1. Finds the newest recorded call audio file from the phone's native Call Recordings directory
 * 2. Uploads the audio file to VaidyaMD HMS Backend /api/crm/calls/{call_id}/recording
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStateReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var isIncoming = false
        private var activeCallId: String? = null

        fun setActiveCallId(callId: String) {
            activeCallId = callId
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

            onCallStateChanged(context, state)
        }
    }

    private fun onCallStateChanged(context: Context, state: Int) {
        if (lastState == state) return

        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Call connected / in progress via local SIM.")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (lastState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    Log.d(TAG, "Call finished / disconnected. Triggering audio upload...")
                    uploadLatestCallRecording(context)
                }
            }
        }
        lastState = state
    }

    private fun uploadLatestCallRecording(context: Context) {
        val targetCallId = activeCallId ?: return
        Log.i(TAG, "Searching for audio recording to upload for call $targetCallId")

        // Standard locations for native call recording across Samsung/Xiaomi/OnePlus/Oppo
        val searchDirs = listOf(
            File("/storage/emulated/0/Recordings/Call"),
            File("/storage/emulated/0/Recordings"),
            File("/storage/emulated/0/MIUI/sound_recorder/call_rec"),
            File("/storage/emulated/0/Music/Recordings")
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

        if (latestFile != null) {
            Log.i(TAG, "Found call recording file: ${latestFile.absolutePath} (${latestFile.length()} bytes)")
            CallAudioUploader.upload(context, targetCallId, latestFile)
        } else {
            Log.w(TAG, "No native call recording file discovered. Verify Auto Call Recording is turned ON in Phone settings.")
        }
    }
}
