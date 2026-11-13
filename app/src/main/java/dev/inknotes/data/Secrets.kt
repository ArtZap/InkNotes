package dev.inknotes.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keys kept in the app's settings (the Google Drive sign-in) are stored encrypted with a key of
 * the Android Keystore, which never leaves the device: a copy of the settings file is no use
 * elsewhere. Values written before 0.39 (plain text) are still read; they are encrypted the
 * next time they are written.
 */
object Secrets {
    private const val KEY = "inknotes-secrets"
    private const val PREFIX = "k1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** [plain] encrypted for storing; as it is if the Keystore can't be used on this device. */
    fun seal(plain: String): String = runCatching {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = c.iv + c.doFinal(plain.toByteArray())
        PREFIX + Base64.encodeToString(data, Base64.NO_WRAP)
    }.getOrDefault(plain)

    /** What [seal] stored; null if it can no longer be read (the key is gone: sign in again). */
    fun open(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val data = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)) }
            String(c.doFinal(data, 12, data.size - 12))
        }.getOrNull()
    }
}
