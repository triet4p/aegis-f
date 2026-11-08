package io.github.triet4p.aegisf.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID
import java.net.URI

/**
 * The smallest output ceiling a stored profile may send explicitly. Owner policy: the default is
 * the model's own output limit (no field on the wire); when a person does configure a ceiling it
 * must leave a reasoning model enough room to finish its thinking and still answer or call a tool.
 */
const val MIN_EXPLICIT_OUTPUT_TOKENS: Int = 32_768

/** A provider configuration whose credential is only ever kept in encrypted local storage. */
data class SavedHostedProvider(
    val id: String,
    val name: String,
    val protocol: HostedProtocol,
    val endpoint: String,
    val model: String,
    val credential: String,
    /** `null` sends no output-limit field and leaves the model's own limit in force. */
    val maxOutputTokens: Int?,
    val reasoningEffort: HostedReasoningEffort?,
    val temperature: Double?,
    val parallelToolCalls: Boolean?,
    val capabilities: HostedProviderCapabilities,
) {
    fun validatedConfig(sessionId: String? = null): HostedProviderConfig {
        if (maxOutputTokens != null && maxOutputTokens < MIN_EXPLICIT_OUTPUT_TOKENS) {
            throw HostedConfigurationException(
                "An explicit output limit must be at least $MIN_EXPLICIT_OUTPUT_TOKENS tokens. " +
                    "Clear the field to use the model's own default.",
            )
        }
        val clientHeaders = if (
            sessionId != null &&
            runCatching { URI(endpoint).host?.equals(OPEN_CODE_HOST, ignoreCase = true) == true }
                .getOrDefault(false)
        ) {
            mapOf(
                "x-opencode-session" to "aegis-f-$sessionId",
                "User-Agent" to ANDROID_USER_AGENT,
            )
        } else {
            emptyMap()
        }
        return HostedProviderConfig(
            protocol = protocol,
            endpoint = endpoint,
            model = model,
            authentication = if (protocol == HostedProtocol.GEMINI_GENERATE_CONTENT) {
                HostedAuthentication.GoogleApiKey(credential)
            } else {
                HostedAuthentication.Bearer(credential)
            },
            budget = HostedGenerationBudget(maxOutputTokens, reasoningEffort, temperature, parallelToolCalls),
            capabilities = capabilities,
            clientHeaders = clientHeaders,
        )
    }

    private companion object {
        const val OPEN_CODE_HOST = "opencode.ai"
        const val ANDROID_USER_AGENT = "aegis-f-android/1.0"
    }

    override fun toString(): String =
        "SavedHostedProvider(id=$id, name=$name, protocol=$protocol, endpoint=$endpoint, model=$model, " +
            "credential=[redacted], maxOutputTokens=$maxOutputTokens, reasoningEffort=$reasoningEffort, " +
            "temperature=$temperature, parallelToolCalls=$parallelToolCalls, capabilities=$capabilities)"
}

/**
 * Encrypted-at-rest, device-local provider profiles. The preference contains only AES-GCM
 * ciphertext; the AES key is non-exportable in Android Keystore. Backup is disabled by the
 * app manifest so encrypted provider profiles cannot leave the device through auto-backup.
 */
class HostedProviderSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun active(): SavedHostedProvider? {
        val id = preferences.getString(ACTIVE_PROFILE_KEY, null) ?: return null
        return load().firstOrNull { it.id == id }
    }

    @Synchronized
    fun activate(id: String) {
        require(load().any { it.id == id }) { "The selected provider profile is unavailable." }
        check(preferences.edit().putString(ACTIVE_PROFILE_KEY, id).commit()) {
            "The selected provider could not be saved."
        }
    }

    @Synchronized
    fun delete(id: String) {
        val remaining = load().filterNot { it.id == id }
        if (remaining.isEmpty()) {
            deleteAll()
            return
        }
        save(remaining)
        if (preferences.getString(ACTIVE_PROFILE_KEY, null) == id) {
            check(preferences.edit().remove(ACTIVE_PROFILE_KEY).commit()) {
                "The selected provider could not be removed."
            }
        }
    }

    @Synchronized
    fun load(): List<SavedHostedProvider> {
        val encoded = preferences.getString(SETTINGS_KEY, null) ?: return emptyList()
        val alias = requireNotNull(preferences.getString(KEY_ALIAS_KEY, null)) {
            "Stored provider settings have no encryption key reference."
        }
        val plaintext = decrypt(Base64.decode(encoded, Base64.NO_WRAP), secretKey(alias))
        val array = JSONArray(plaintext.toString(Charsets.UTF_8))
        return List(array.length()) { index -> array.getJSONObject(index).toProfile() }
    }

    @Synchronized
    fun save(profiles: List<SavedHostedProvider>) {
        require(profiles.map { it.id }.toSet().size == profiles.size) { "Provider profile ids must be unique." }
        profiles.forEach { it.validatedConfig() }
        val array = JSONArray().apply { profiles.forEach { put(it.toJson()) } }
        val previousAlias = preferences.getString(KEY_ALIAS_KEY, null)
        val newAlias = "$KEY_ALIAS.${UUID.randomUUID()}"
        val newKey = createSecretKey(newAlias)
        val encrypted = encrypt(array.toString().toByteArray(Charsets.UTF_8), newKey)
        val saved = preferences.edit()
            .putString(SETTINGS_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_ALIAS_KEY, newAlias)
            .commit()
        if (!saved) {
            deleteSecretKey(newAlias)
            error("Provider settings could not be saved.")
        }
        previousAlias?.takeIf { it != newAlias }?.let(::deleteSecretKey)
    }

    @Synchronized
    fun deleteAll() {
        val alias = preferences.getString(KEY_ALIAS_KEY, null)
        check(
            preferences.edit()
                .remove(SETTINGS_KEY)
                .remove(KEY_ALIAS_KEY)
                .remove(ACTIVE_PROFILE_KEY)
                .commit(),
        ) { "Provider settings could not be deleted." }
        alias?.let(::deleteSecretKey)
    }

    private fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(ciphertext: ByteArray, key: SecretKey): ByteArray {
        require(ciphertext.size > GCM_IV_BYTES) { "Stored provider settings are unreadable." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, ciphertext.copyOfRange(0, GCM_IV_BYTES)),
        )
        return cipher.doFinal(ciphertext.copyOfRange(GCM_IV_BYTES, ciphertext.size))
    }

    private fun secretKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return requireNotNull(keyStore.getKey(alias, null) as? SecretKey) {
            "Provider settings encryption key is unavailable."
        }
    }

    private fun createSecretKey(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deleteSecretKey(alias: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun SavedHostedProvider.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("protocol", protocol.name)
        .put("endpoint", endpoint)
        .put("model", model)
        .put("credential", credential)
        .put("maxOutputTokens", maxOutputTokens ?: JSONObject.NULL)
        .put("reasoningEffort", reasoningEffort?.name)
        .put("temperature", temperature)
        .put("parallelToolCalls", parallelToolCalls)
        .put("capabilities", JSONObject()
            .put("toolCalls", capabilities.supportsToolCalls)
            .put("parallelToolCalls", capabilities.supportsParallelToolCalls)
            .put("reasoningBudget", capabilities.supportsReasoningBudget)
            .put("temperature", capabilities.supportsTemperature)
            .put("maxOutputTokensLimit", capabilities.maxOutputTokensLimit))

    private fun JSONObject.toProfile(): SavedHostedProvider {
        val caps = getJSONObject("capabilities")
        return SavedHostedProvider(
            id = getString("id"), name = getString("name"),
            protocol = HostedProtocol.valueOf(getString("protocol")),
            endpoint = getString("endpoint"), model = getString("model"), credential = getString("credential"),
            maxOutputTokens = storedOutputTokens(),
            reasoningEffort = optString("reasoningEffort").takeIf { it.isNotEmpty() && it != "null" }
                ?.let(HostedReasoningEffort::valueOf),
            temperature = if (isNull("temperature")) null else getDouble("temperature"),
            parallelToolCalls = if (isNull("parallelToolCalls")) null else getBoolean("parallelToolCalls"),
            capabilities = HostedProviderCapabilities(
                supportsToolCalls = caps.getBoolean("toolCalls"),
                supportsParallelToolCalls = caps.getBoolean("parallelToolCalls"),
                supportsReasoningBudget = caps.getBoolean("reasoningBudget"),
                supportsTemperature = caps.getBoolean("temperature"),
                maxOutputTokensLimit = if (caps.isNull("maxOutputTokensLimit")) null else caps.getInt("maxOutputTokensLimit"),
            ),
        )
    }

    /**
     * Reads a stored output ceiling. A value below [MIN_EXPLICIT_OUTPUT_TOKENS] predates this
     * policy — the app used to seed every profile with a fixed 8192/4096 cap — so it is returned
     * as `null` and the model's own default applies. The stored document is left untouched until
     * the profile is saved again, and every reader (`load`/`active`) sees the corrected value.
     */
    private fun JSONObject.storedOutputTokens(): Int? {
        if (!has("maxOutputTokens") || isNull("maxOutputTokens")) return null
        return getInt("maxOutputTokens").takeIf { it >= MIN_EXPLICIT_OUTPUT_TOKENS }
    }

    private companion object {
        const val PREFERENCES = "hosted_provider_settings"
        const val SETTINGS_KEY = "encrypted_profiles"
        const val KEY_ALIAS_KEY = "encryption_key_alias"
        const val ACTIVE_PROFILE_KEY = "active_profile_id"
        const val KEY_ALIAS = "aegis.hosted-provider-settings.v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
