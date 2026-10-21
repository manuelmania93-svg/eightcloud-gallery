package app.eightcloud.gallery.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-derived key wrapping. Pure JVM/Android-free crypto so the whole
 * reinstall flow is unit-testable: PBKDF2 turns the passphrase into a KEK,
 * the KEK wraps a random data-encryption key (DEK) with AES-GCM. Changing the
 * passphrase re-wraps the DEK; files stay encrypted with the same DEK.
 * The passphrase itself is never stored anywhere.
 */
object PassphraseCrypto {

    const val KDF = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 600_000
    const val SALT_BYTES = 32
    const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    /** Derives the key-encryption key from a passphrase. ~1s on modern phones. */
    fun deriveKek(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    /** Generates the random data-encryption key files are actually encrypted with. */
    fun generateDek(): SecretKey =
        SecretKeySpec(randomBytes(KEY_BITS / 8), "AES")

    /** Wraps [dek] under [kek]; returns iv + wrapped key bytes. */
    fun wrapDek(dek: SecretKey, kek: SecretKey): ByteArray {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(dek.encoded)
    }

    /**
     * Unwraps a payload from [wrapDek]. Throws (AEADBadTagException) on a
     * wrong passphrase-derived KEK, which doubles as passphrase verification.
     */
    fun unwrapDek(payload: ByteArray, kek: SecretKey): SecretKey {
        require(payload.size > IV_BYTES + KEY_BITS / 8) { "payload too short" }
        val iv = payload.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        val bytes = cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES)
        return SecretKeySpec(bytes, "AES")
    }
}
