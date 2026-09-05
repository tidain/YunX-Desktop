package com.yunx.app.data.security

import com.yunx.app.AppContext
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 凭证加解密接口（与原 Android 版一致）。
 */
internal interface CredentialCipher {
    fun encrypt(plaintext: String, purpose: String): String
    fun decrypt(stored: String, purpose: String): String
    fun isEncrypted(stored: String): Boolean
}

/**
 * 桌面版凭证加密：AES-256-GCM。
 * 密钥为首次启动生成的随机 32 字节，保存在 <dataDir>/credential.key。
 * 输出格式与原 Android 版完全相同："yunx:v1:<iv Base64>:<ct Base64>"，AAD = purpose。
 */
internal class FileCredentialCipher : CredentialCipher {

    private val key: SecretKeySpec by lazy {
        val keyFile = File(AppContext.dataDir, "credential.key")
        val bytes = if (keyFile.exists()) {
            keyFile.readBytes()
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also { newKey ->
                keyFile.parentFile?.mkdirs()
                keyFile.writeBytes(newKey)
            }
        }
        require(bytes.size == 32) { "invalid credential key file" }
        SecretKeySpec(bytes, "AES")
    }

    override fun encrypt(plaintext: String, purpose: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val ctB64 = Base64.getEncoder().encodeToString(ct)
        return "$PREFIX$ivB64:$ctB64"
    }

    override fun decrypt(stored: String, purpose: String): String {
        require(stored.startsWith(PREFIX)) { "stored value is not encrypted" }
        val parts = stored.removePrefix(PREFIX).split(":")
        require(parts.size == 2) { "malformed encrypted value" }
        val iv = Base64.getDecoder().decode(parts[0])
        val ct = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    override fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    private companion object {
        const val PREFIX = "yunx:v1:"
    }
}
