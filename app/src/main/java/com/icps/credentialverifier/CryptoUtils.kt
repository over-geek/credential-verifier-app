package com.icps.credentialverifier

import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    // I hardcoded it because it's just a POC :)
    private const val AES_KEY_B64 = "O76aMoUOMOD5pa0l6WCcaDv6DTCCiaG4BKOMrc3Yqxo="
    private const val ED25519_PUBLIC_B64 = "MCowBQYDK2VwAyEAHRi6pqjb58V76wXxRX4VaUEM6LFTOYWMoJBUQlUeEDc="
    
    // This is the custom key we use to authenticate to the MIFARE Classic sector (A0 A1 A2 A3 A4 A5)
    val MIFARE_CUSTOM_KEY_A = byteArrayOf(
        0x1C.toByte(), 0x2F.toByte(), 0x3E.toByte(), 
        0x4A.toByte(), 0x5B.toByte(), 0x6D.toByte()
    )

    private val aesKey: SecretKeySpec by lazy {
        SecretKeySpec(Base64.decode(AES_KEY_B64, Base64.DEFAULT), "AES")
    }

    private val publicKey: PublicKey by lazy {
        val kf = KeyFactory.getInstance("Ed25519")
        kf.generatePublic(X509EncodedKeySpec(Base64.decode(ED25519_PUBLIC_B64, Base64.DEFAULT)))
    }


    // Signature is computed over: [chipUidBytes] + [IV] + [Ciphertext + Tag]. that's how we get the signature :)
    fun decryptAndVerify(rawPayload: ByteArray, chipUid: String): String {
        if (rawPayload.size < 4) {
            throw Exception("Payload too small to contain length header.")
        }
        val buffer = ByteBuffer.wrap(rawPayload)
        val totalLength = buffer.int
        if (totalLength <= 0 || totalLength > rawPayload.size - 4) {
            throw Exception("Invalid payload length header.")
        }
        val payload = ByteArray(totalLength)
        buffer.get(payload)

        if (payload.size < 12 + 64 + 1) { // Min size: IV + Sig + at least 1 byte of ciphertext
            throw Exception("Payload too small")
        }

        val iv = payload.copyOfRange(0, 12)
        val signature = payload.copyOfRange(payload.size - 64, payload.size)
        val ciphertext = payload.copyOfRange(12, payload.size - 64)

        val sig = Signature.getInstance("Ed25519")
        sig.initVerify(publicKey)
        sig.update(chipUid.toByteArray(Charsets.UTF_8))
        sig.update(iv)
        sig.update(ciphertext)
        
        if (!sig.verify(signature)) {
            throw Exception("Signature verification failed. The card might be cloned or corrupted.")
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec)
        
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    fun verifyQrSignature(dataJson: String, signatureB64: String): Boolean {
        return try {
            val signature = Base64.decode(signatureB64, Base64.DEFAULT)
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(publicKey)
            sig.update(dataJson.toByteArray(Charsets.UTF_8))
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }
}
