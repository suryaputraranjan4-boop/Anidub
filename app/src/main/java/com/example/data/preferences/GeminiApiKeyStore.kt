package com.example.data.preferences

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.BuildConfig
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class GeminiKeySource {
    USER_ENCRYPTED_KEYSTORE,
    BUILD_CONFIG_SECRET,
    NOT_CONFIGURED
}

data class GeminiApiKeyState(
    val isConfigured: Boolean = false,
    val source: GeminiKeySource = GeminiKeySource.NOT_CONFIGURED,
    val maskedKey: String = "",
    val hasUserStoredKey: Boolean = false,
    val hasBuildConfigFallback: Boolean = false,
    val storageStatusNote: String = "No Gemini API key configured."
)

/**
 * Stores the user's Gemini API key encrypted at rest using Android Keystore (AES-256-GCM)
 * backed by app-private SharedPreferences. Never logs or prints the raw API key.
 *
 * Precedence:
 * 1. User-supplied API key stored encrypted in Android Keystore.
 * 2. Optional build-time BuildConfig.GEMINI_API_KEY (injected via AI Studio Secrets / .env) if non-placeholder.
 * 3. NOT_CONFIGURED (triggers explicit error instead of silent fallback or fake response).
 */
class GeminiApiKeyStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveApiKey(rawKey: String): Result<GeminiApiKeyState> {
        val cleaned = rawKey.trim()
        if (cleaned.isEmpty()) {
            return Result.failure(IllegalArgumentException("Gemini API key cannot be empty."))
        }
        if (cleaned.equals(PLACEHOLDER_KEY, ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Please enter a real Gemini API key, not the placeholder value."))
        }
        if (cleaned.any { it.isWhitespace() || it.isISOControl() }) {
            return Result.failure(IllegalArgumentException("Gemini API key must not contain spaces or line breaks."))
        }
        if (cleaned.length < 16) {
            return Result.failure(IllegalArgumentException("Gemini API key appears too short to be valid."))
        }

        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(cleaned.toByteArray(StandardCharsets.UTF_8))

            prefs.edit()
                .putString(KEY_CIPHERTEXT_B64, Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
                .putString(KEY_IV_B64, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()

            Result.success(getCurrentState())
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "Unable to encrypt Gemini API key with Android Keystore: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    e
                )
            )
        }
    }

    fun removeApiKey(): GeminiApiKeyState {
        prefs.edit()
            .remove(KEY_CIPHERTEXT_B64)
            .remove(KEY_IV_B64)
            .apply()
        return getCurrentState()
    }

    fun getEffectiveApiKey(): String {
        val userKey = readDecryptedUserKey()
        if (!userKey.isNullOrBlank()) return userKey
        return readBuildConfigKey().orEmpty()
    }

    fun hasConfiguredKey(): Boolean = getEffectiveApiKey().isNotBlank()

    fun getCurrentState(): GeminiApiKeyState {
        val userKey = readDecryptedUserKey()
        val buildKey = readBuildConfigKey()
        return when {
            !userKey.isNullOrBlank() -> GeminiApiKeyState(
                isConfigured = true,
                source = GeminiKeySource.USER_ENCRYPTED_KEYSTORE,
                maskedKey = maskApiKey(userKey),
                hasUserStoredKey = true,
                hasBuildConfigFallback = !buildKey.isNullOrBlank(),
                storageStatusNote = "Stored encrypted locally via Android Keystore (AES-256-GCM)."
            )
            !buildKey.isNullOrBlank() -> GeminiApiKeyState(
                isConfigured = true,
                source = GeminiKeySource.BUILD_CONFIG_SECRET,
                maskedKey = maskApiKey(buildKey),
                hasUserStoredKey = false,
                hasBuildConfigFallback = true,
                storageStatusNote = "Using build-configured GEMINI_API_KEY secret. You can override it with your own key below."
            )
            else -> GeminiApiKeyState(
                isConfigured = false,
                source = GeminiKeySource.NOT_CONFIGURED,
                maskedKey = "",
                hasUserStoredKey = false,
                hasBuildConfigFallback = false,
                storageStatusNote = "No Gemini API key configured. Enter your Google AI Studio API key in Settings to enable Gemini translation, transcription, and review."
            )
        }
    }

    private fun readDecryptedUserKey(): String? {
        val cipherB64 = prefs.getString(KEY_CIPHERTEXT_B64, null) ?: return null
        val ivB64 = prefs.getString(KEY_IV_B64, null) ?: return null
        return try {
            val secretKey = getExistingSecretKey() ?: return null
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val cipherBytes = Base64.decode(cipherB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, StandardCharsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            // Keystore entry invalidated or unreadable; clear stale ciphertext safely
            prefs.edit().remove(KEY_CIPHERTEXT_B64).remove(KEY_IV_B64).apply()
            null
        }
    }

    private fun readBuildConfigKey(): String? {
        val raw = BuildConfig.GEMINI_API_KEY.trim()
        if (raw.isEmpty() ||
            raw.equals(PLACEHOLDER_KEY, ignoreCase = true) ||
            raw.equals("null", ignoreCase = true)
        ) {
            return null
        }
        return raw
    }

    private fun getExistingSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateSecretKey(): SecretKey {
        getExistingSecretKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "animedub_direct_gemini_api_key_aes256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PREFS_NAME = "gemini_api_key_secure_prefs"
        private const val KEY_CIPHERTEXT_B64 = "enc_api_key_ct"
        private const val KEY_IV_B64 = "enc_api_key_iv"
        const val PLACEHOLDER_KEY = "GEMINI_API_KEY_DEFAULT_VALUE"

        fun maskApiKey(key: String): String {
            val clean = key.trim()
            if (clean.isEmpty()) return ""
            if (clean.length <= 8) return "••••••••"
            return "${clean.take(4)}••••••••${clean.takeLast(4)}"
        }
    }
}
