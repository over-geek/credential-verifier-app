package com.icps.credentialverifier

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI

object QrPayloadParser {
    fun parseOfflineQr(payload: String): CredentialResponseDto? {
        val uri = runCatching { URI(payload.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "cv" || uri.host != "data") {
            return null
        }

        if (uri.rawQuery != null || uri.rawFragment != null) {
            return null
        }

        val base64Payload = uri.path?.removePrefix("/") ?: return null
        if (base64Payload.isBlank() || base64Payload.contains("/")) {
            return null
        }

        return try {
            val jsonString = String(Base64.decode(base64Payload, Base64.DEFAULT), Charsets.UTF_8)
            val jsonObject = Gson().fromJson(jsonString, JsonObject::class.java)
            
            val dataJson = jsonObject.getAsJsonObject("data").toString()
            val signature = jsonObject.get("signature").asString
            
            if (CryptoUtils.verifyQrSignature(dataJson, signature)) {
                Gson().fromJson(dataJson, CredentialResponseDto::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
