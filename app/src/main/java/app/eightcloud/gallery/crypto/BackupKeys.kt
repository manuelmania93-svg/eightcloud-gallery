package app.eightcloud.gallery.crypto

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import javax.crypto.SecretKey

/**
 * Chooses and unlocks the key that encrypts backup content.
 *
 * Two modes:
 * - Legacy (no passphrase set): the device-bound Android Keystore key. Files
 *   encrypted this way are tagged 'ks' and only decryptable on this install.
 * - Passphrase: a random DEK, wrapped by the passphrase KEK (mirrored to the
 *   cloud keyslot so a fresh install can recover) AND wrapped by the keystore
 *   key locally so background uploads after a reboot never need the
 *   passphrase again. Files are tagged 'pp'.
 *
 * The passphrase is never stored, logged, or uploaded.
 */
object BackupKeys {

    const val TAG_KEYSTORE = "ks"
    const val TAG_PASSPHRASE = "pp"

    private const val P_SALT = "pp_salt"
    private const val P_ITER = "pp_iterations"
    private const val P_WRAPPED = "pp_wrapped_dek" // DEK wrapped by passphrase KEK
    private const val P_KS_WRAPPED = "pp_ks_wrapped_dek" // DEK wrapped by keystore key

    @Volatile
    private var cachedDek: SecretKey? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun passphraseEnabled(context: Context): Boolean =
        prefs(context).getString(P_WRAPPED, null) != null

    /** Builds the cloud keyslot JSON from local prefs (null when not set). */
    fun keyslotJson(context: Context): String? {
        val p = prefs(context)
        val salt = p.getString(P_SALT, null) ?: return null
        val wrapped = p.getString(P_WRAPPED, null) ?: return null
        return JSONObject().apply {
            put("version", 1)
            put("kdf", PassphraseCrypto.KDF)
            put("iterations", p.getInt(P_ITER, PassphraseCrypto.ITERATIONS))
            put("salt", salt)
            put("wrappedDek", wrapped)
        }.toString()
    }

    /** Stores a keyslot fetched from the cloud (fresh-install recovery). */
    fun applyKeyslot(context: Context, json: String) {
        val o = JSONObject(json)
        prefs(context).edit()
            .putString(P_SALT, o.getString("salt"))
            .putInt(P_ITER, o.optInt("iterations", PassphraseCrypto.ITERATIONS))
            .putString(P_WRAPPED, o.getString("wrappedDek"))
            .apply()
    }

    /**
     * Sets (or replaces) the passphrase: creates a fresh DEK, wraps it under
     * the passphrase KEK and the keystore key, stores both wraps.
     */
    fun setPassphrase(context: Context, passphrase: CharArray) {
        val dek = PassphraseCrypto.generateDek()
        val salt = PassphraseCrypto.randomBytes(PassphraseCrypto.SALT_BYTES)
        val kek = PassphraseCrypto.deriveKek(passphrase, salt)
        val wrapped = PassphraseCrypto.wrapDek(dek, kek)
        val ksWrapped = BackupCrypto.encrypt(KeystoreKeys.backupKey(), dek.encoded)
        prefs(context).edit()
            .putString(P_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt(P_ITER, PassphraseCrypto.ITERATIONS)
            .putString(P_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString(P_KS_WRAPPED, Base64.encodeToString(ksWrapped, Base64.NO_WRAP))
            .apply()
        cachedDek = dek
    }

    /** Changes only the passphrase wrap; the DEK (and all files) stay the same. */
    fun changePassphrase(context: Context, newPassphrase: CharArray) {
        val dek = unlockLocal(context) ?: throw IllegalStateException("locked")
        val salt = PassphraseCrypto.randomBytes(PassphraseCrypto.SALT_BYTES)
        val kek = PassphraseCrypto.deriveKek(newPassphrase, salt)
        val wrapped = PassphraseCrypto.wrapDek(dek, kek)
        prefs(context).edit()
            .putString(P_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt(P_ITER, PassphraseCrypto.ITERATIONS)
            .putString(P_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Unlocks the DEK from the local keystore wrap (same install, after a
     * reboot/process death). Returns null when passphrase mode needs the
     * passphrase instead.
     */
    fun unlockLocal(context: Context): SecretKey? {
        cachedDek?.let { return it }
        val p = prefs(context)
        val ksWrapped = p.getString(P_KS_WRAPPED, null) ?: return null
        return runCatching {
            val plain = BackupCrypto.decrypt(
                KeystoreKeys.backupKey(), Base64.decode(ksWrapped, Base64.NO_WRAP)
            )
            javax.crypto.spec.SecretKeySpec(plain, "AES")
        }.getOrNull()?.also { cachedDek = it }
    }

    /**
     * Verifies/unlocks with the passphrase. Returns true when it unwraps the
     * DEK (which also proves the passphrase correct). Re-wraps under the
     * keystore key afterwards so background runs stay passphrase-free.
     */
    fun unlockWithPassphrase(context: Context, passphrase: CharArray): Boolean {
        val p = prefs(context)
        val salt = p.getString(P_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val wrapped = p.getString(P_WRAPPED, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val iterations = p.getInt(P_ITER, PassphraseCrypto.ITERATIONS)
        return runCatching {
            val kek = PassphraseCrypto.deriveKek(passphrase, salt, iterations)
            val dek = PassphraseCrypto.unwrapDek(wrapped, kek)
            val ksWrapped = BackupCrypto.encrypt(KeystoreKeys.backupKey(), dek.encoded)
            p.edit().putString(P_KS_WRAPPED, Base64.encodeToString(ksWrapped, Base64.NO_WRAP)).apply()
            cachedDek = dek
            true
        }.getOrDefault(false)
    }

    /** The key new content should be encrypted with, plus its tag. */
    fun uploadKey(context: Context): Pair<SecretKey, String> {
        if (!passphraseEnabled(context)) return KeystoreKeys.backupKey() to TAG_KEYSTORE
        val dek = unlockLocal(context)
            ?: throw IllegalStateException("backup key locked - unlock with passphrase")
        return dek to TAG_PASSPHRASE
    }

    /** The key for an existing entry, picked by its recorded tag. */
    fun keyForTag(context: Context, tag: String): SecretKey = when (tag) {
        TAG_PASSPHRASE -> unlockLocal(context)
            ?: throw IllegalStateException("backup key locked - unlock with passphrase")
        else -> KeystoreKeys.backupKey()
    }
}
