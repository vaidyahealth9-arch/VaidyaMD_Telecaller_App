package com.vaidyamd.telecaller

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

object CallAudioUploader {
    private const val TAG = "CallAudioUploader"
    private val client = OkHttpClient()

    fun upload(context: Context, callId: String, audioFile: File) {
        val prefs = context.getSharedPreferences("vaidyamd_crm", Context.MODE_PRIVATE)
        var serverBaseUrl = prefs.getString("server_url", "http://192.168.1.3:8000")?.trim()?.trimEnd('/') ?: "http://192.168.1.3:8000"
        if (serverBaseUrl.endsWith("/api")) {
            serverBaseUrl = serverBaseUrl.substring(0, serverBaseUrl.length - 4)
        }
        val authToken = prefs.getString("auth_token", "") ?: ""

        val requestUrl = "$serverBaseUrl/api/crm/calls/$callId/recording"

        val mediaType = when (audioFile.extension.lowercase()) {
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            else -> "audio/mpeg"
        }.toMediaTypeOrNull()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody(mediaType)
            )
            .build()

        val request = Request.Builder()
            .url(requestUrl)
            .header("Authorization", "Bearer $authToken")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to upload recording for call $callId: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.i(TAG, "Call recording successfully uploaded! Faster-Whisper background processing queued.")
                } else {
                    Log.w(TAG, "Server responded with HTTP ${response.code}: ${response.body?.string()}")
                }
            }
        })
    }
}
