package app.eightcloud.gallery.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class PassphraseCryptoTest {

    private fun fastKek(passphrase: String, salt: ByteArray) =
        PassphraseCrypto.deriveKek(passphrase.toCharArray(), salt, iterations = 1_000)

    @Test
    fun wrapAndUnwrapRoundTripsTheDek() {
        val salt = PassphraseCrypto.randomBytes(PassphraseCrypto.SALT_BYTES)
        val kek = fastKek("correct horse battery staple", salt)
        val dek = PassphraseCrypto.generateDek()
        val wrapped = PassphraseCrypto.wrapDek(dek, kek)
        val unwrapped = PassphraseCrypto.unwrapDek(wrapped, kek)
        assertArrayEquals(dek.encoded, unwrapped.encoded)
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongPassphraseFails() {
        val salt = PassphraseCrypto.randomBytes(PassphraseCrypto.SALT_BYTES)
        val dek = PassphraseCrypto.generateDek()
        val wrapped = PassphraseCrypto.wrapDek(dek, fastKek("right", salt))
        PassphraseCrypto.unwrapDek(wrapped, fastKek("wrong", salt))
    }

    @Test
    fun samePassphraseSameSaltDerivesSameKey() {
        val salt = PassphraseCrypto.randomBytes(PassphraseCrypto.SALT_BYTES)
        val a = fastKek("repeatable", salt)
        val b = fastKek("repeatable", salt)
        assertArrayEquals(a.encoded, b.encoded)
    }

    @Test
    fun differentSaltDerivesDifferentKey() {
        val a = fastKek("repeatable", PassphraseCrypto.randomBytes(PassphraseCrypto.SALT_BYTES))
        val b = fastKek("repeatable", PassphraseCrypto.randomBytes(PassphraseCrypto.SALT_BYTES))
        assertNotEquals(a.encoded.toList(), b.encoded.toList())
    }

    /**
     * Reinstall simulation: device 1 encrypts an index payload and a sample
     * file with a DEK wrapped by a passphrase KEK. Device 2 has NO previous
     * state - only the passphrase, the salt, and the wrapped DEK (the cloud
     * keyslot). It must recover the DEK and decrypt both payloads.
     */
    @Test
    fun reinstallSimulation() {
        // --- device 1 ---
        val passphrase = "manuels backup phrase"
        val salt = PassphraseCrypto.randomBytes(PassphraseCrypto.SALT_BYTES)
        val kek1 = fastKek(passphrase, salt)
        val dek1 = PassphraseCrypto.generateDek()
        val keyslot = PassphraseCrypto.wrapDek(dek1, kek1) // uploaded to the cloud

        val indexJson = """{"version":1,"entries":[{"hash":"abc123"}]}""".toByteArray()
        val sampleFile = ByteArray(100_000) { (it % 251).toByte() } // fake photo bytes
        val encIndex = BackupCrypto.encrypt(dek1, indexJson)
        val encFile = BackupCrypto.encrypt(dek1, sampleFile)

        // --- device 2: fresh install, only passphrase + keyslot + salt ---
        val kek2 = fastKek(passphrase, salt)
        val dek2 = PassphraseCrypto.unwrapDek(keyslot, kek2)
        assertArrayEquals(dek1.encoded, dek2.encoded)
        assertArrayEquals(indexJson, BackupCrypto.decrypt(dek2, encIndex))
        assertArrayEquals(sampleFile, BackupCrypto.decrypt(dek2, encFile))

        // And a wrong passphrase on device 2 must fail loudly, not corrupt.
        val wrongKek = fastKek("not the phrase", salt)
        try {
            PassphraseCrypto.unwrapDek(keyslot, wrongKek)
            throw AssertionError("wrong passphrase must not unwrap")
        } catch (expected: AEADBadTagException) {
            assertTrue(true)
        }
    }
}
