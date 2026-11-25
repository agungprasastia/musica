package com.music.musica.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.music.musica.core.model.AccountProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class GoogleSession(
    val cookie: String,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val profile: AccountProfile? = null,
) {
    val isSignedIn: Boolean
        get() = SapisidHashSigner.extractSapisid(cookie) != null
}

/**
 * Stores YouTube Music session cookies encrypted at rest using Android Keystore (AES-256-GCM).
 * Works cleanly on minSdk 26+ without deprecated security-crypto alpha dependencies.
 */
class KeystoreAuthStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<GoogleSession?> = _session.asStateFlow()

    val currentCookie: String?
        get() = _session.value?.cookie?.takeIf { it.isNotBlank() }

    val isSignedIn: Boolean
        get() = _session.value?.isSignedIn == true

    fun saveSession(session: GoogleSession) {
        val raw = json.encodeToString(session)
        val encrypted = encrypt(raw)
        prefs.edit().putString(KEY_SESSION, encrypted).apply()
        _session.value = session
    }

    fun updateProfile(profile: AccountProfile) {
        val current = _session.value ?: return
        saveSession(current.copy(profile = profile))
    }

    fun saveVisitorData(visitorData: String) {
        prefs.edit().putString(KEY_VISITOR_DATA, visitorData).apply()
    }

    fun getVisitorData(): String? =
        _session.value?.visitorData?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_VISITOR_DATA, null)?.takeIf { it.isNotBlank() }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).apply()
        _session.value = null
    }

    private fun loadSession(): GoogleSession? {
        val blob = prefs.getString(KEY_SESSION, null) ?: return null
        val plain = runCatching { decrypt(blob) }.getOrNull() ?: return null
        return runCatching { json.decodeFromString<GoogleSession>(plain) }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH) { "Invalid encrypted blob" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    companion object {
        private const val PREFS_NAME = "musica_secure_auth"
        private const val KEY_SESSION = "encrypted_session"
        private const val KEY_VISITOR_DATA = "guest_visitor_data"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "musica_auth_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
