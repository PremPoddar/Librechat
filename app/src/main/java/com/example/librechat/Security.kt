package com.example.librechat

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

object Security {
    private const val PASS = "LibreChat2026"

    private fun getKey(): SecretKeySpec {
        val hash = MessageDigest.getInstance("SHA-256").digest(PASS.toByteArray())
        return SecretKeySpec(hash, "AES")
    }

    fun encrypt(message: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16) { 0 }
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(message.toByteArray())
            "ENC:" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            message
        }
    }

    fun decrypt(cipherText: String): String {
        if (!cipherText.startsWith("ENC:")) return cipherText
        return try {
            val raw = cipherText.removePrefix("ENC:")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16) { 0 }
            cipher.init(Cipher.DECRYPT_MODE, getKey(), IvParameterSpec(iv))
            val decoded = Base64.decode(raw, Base64.NO_WRAP)
            String(cipher.doFinal(decoded))
        } catch (e: Exception) {
            "[Encrypted]"
        }
    }
}