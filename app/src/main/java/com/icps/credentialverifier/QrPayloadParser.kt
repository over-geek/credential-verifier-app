package com.icps.credentialverifier

import java.net.URI

object QrPayloadParser {
    fun parseToken(payload: String): String? {
        val uri = runCatching { URI(payload.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "cv" || uri.host != "verify") {
            return null
        }

        if (uri.rawQuery != null || uri.rawFragment != null) {
            return null
        }

        val token = uri.path?.removePrefix("/") ?: return null
        if (token.isBlank() || token.contains("/")) {
            return null
        }

        return token
    }
}
