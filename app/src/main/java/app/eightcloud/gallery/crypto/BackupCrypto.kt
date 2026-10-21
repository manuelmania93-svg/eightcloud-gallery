package app.eightcloud.gallery.crypto

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption for backup payloads. Pure crypto, no Android types,
 * so it is unit-testable. The key itself lives in the Android Keystore
 * (see [KeystoreKeys]).
 */
object BackupCrypto {

    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun randomIv(): ByteArray = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }

    /** Encrypts [plain]; returns iv + ciphertext (iv is not a secret). */
    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = randomIv()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    /** Decrypts a payload produced by [encrypt] (expects the leading iv). */
    fun decrypt(key: SecretKey, payload: ByteArray): ByteArray {
        require(payload.size > IV_BYTES) { "payload too short" }
        val iv = payload.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES)
    }

    /**
     * Streams [input] encrypted into [output]; returns the iv used.
     * Caller stores the iv next to the chunk metadata.
     */
    fun encryptStream(key: SecretKey, iv: ByteArray, input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        CipherOutputStream(output, cipher).use { cos ->
            input.use { it.copyTo(cos, 64 * 1024) }
        }
    }

    /** Decrypts a stream encrypted with [encryptStream]. */
    fun decryptStream(key: SecretKey, iv: ByteArray, input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        CipherInputStream(input, cipher).use { cis ->
            cis.copyTo(output, 64 * 1024)
        }
    }
}
