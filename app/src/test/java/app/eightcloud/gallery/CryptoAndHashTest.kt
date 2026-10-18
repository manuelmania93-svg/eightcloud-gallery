package app.eightcloud.gallery

import app.eightcloud.gallery.crypto.BackupCrypto
import app.eightcloud.gallery.data.Hasher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class CryptoAndHashTest {

    private fun key(): SecretKey {
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256)
        return gen.generateKey()
    }

    @Test
    fun sha256KnownVector() {
        // SHA-256 of "abc" (well-known test vector)
        val hash = Hasher.sha256Hex(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
    }

    @Test
    fun encryptDecryptRoundTrip() {
        val key = key()
        val plain = "hello 8cloud".toByteArray()
        val payload = BackupCrypto.encrypt(key, plain)
        assertNotEquals(String(plain), String(payload))
        assertArrayEquals(plain, BackupCrypto.decrypt(key, payload))
    }

    @Test
    fun streamRoundTripLarge() {
        val key = key()
        val plain = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val iv = BackupCrypto.randomIv()
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encryptStream(key, iv, ByteArrayInputStream(plain), encrypted)
        val decrypted = ByteArrayOutputStream()
        BackupCrypto.decryptStream(key, iv, ByteArrayInputStream(encrypted.toByteArray()), decrypted)
        assertArrayEquals(plain, decrypted.toByteArray())
    }

    @Test(expected = Exception::class)
    fun decryptWithWrongKeyFails() {
        val payload = BackupCrypto.encrypt(key(), "secret".toByteArray())
        BackupCrypto.decrypt(key(), payload)
    }
}
