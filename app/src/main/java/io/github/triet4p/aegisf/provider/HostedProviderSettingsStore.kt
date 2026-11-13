package io.github.triet4p.aegisf.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.triet4p.aegisf.data.HostedProfileTransfer
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
    /**
     * The locally established credential, or empty when this profile is a descriptor restored
     * from a backup. A credential is never imported; the user establishes one here.
     */
    val credential: String,
    /** `null` sends no output-limit field and leaves the model's own limit in force. */
    val maxOutputTokens: Int?,
    val reasoningEffort: HostedReasoningEffort?,
    val temperature: Double?,
    val parallelToolCalls: Boolean?,
    val capabilities: HostedProviderCapabilities,
) {
    /** Whether a credential was established locally for this profile. */
    val hasCredential: Boolean get() = credential.isNotBlank()

    fun validatedConfig(sessionId: String? = null): HostedProviderConfig {
        if (!hasCredential) {
            throw HostedConfigurationException(
                "This provider profile has no saved credential yet. Open Provider & model and " +
                    "enter its key before using it.",
            )
        }
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

    /**
     * Validates the non-secret descriptor a restored profile carries.
     *
     * A profile imported from a backup has no credential and only the endpoint origin the export
     * preserves, so it is not yet a runnable configuration: the protocol's path rules need the
     * full endpoint, which a backup deliberately does not carry. The descriptor must still be a
     * sane, openable profile, and every rule that does not need authentication or a complete
     * path is enforced. Once the user establishes a credential, [validatedConfig] applies the
     * complete protocol validation again.
     */
    internal fun validateDescriptor() {
        if (id.isBlank()) {
            throw HostedConfigurationException("provider profile id must be nonblank")
        }
        if (name.isBlank()) {
            throw HostedConfigurationException("provider profile name must be nonblank")
        }
        if (maxOutputTokens != null && maxOutputTokens < MIN_EXPLICIT_OUTPUT_TOKENS) {
            throw HostedConfigurationException(
                "An explicit output limit must be at least $MIN_EXPLICIT_OUTPUT_TOKENS tokens. " +
                    "Clear the field to use the model's own default.",
            )
        }
        if (model.isBlank() || model.length > 200) {
            throw HostedConfigurationException("model id must be a nonblank bounded value")
        }
        if (model.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' || it.code < 0x20 }) {
            throw HostedConfigurationException("model id must travel in a request path unchanged")
        }
        if (!endpoint.startsWith("https://")) {
            throw HostedConfigurationException("endpoint must use https")
        }
        val uri = runCatching { URI(endpoint.replace("{model}", "model")) }.getOrNull()
            ?: throw HostedConfigurationException("endpoint is not a valid absolute URL")
        if (uri.host.isNullOrBlank()) throw HostedConfigurationException("endpoint must name a host")
        if (uri.userInfo != null) throw HostedConfigurationException("endpoint must not carry user info")
        if (uri.fragment != null) throw HostedConfigurationException("endpoint must not carry a fragment")
        if (protocol != HostedProtocol.GEMINI_GENERATE_CONTENT && endpoint.contains("{model}")) {
            throw HostedConfigurationException("OpenAI-style endpoint must not carry {model}")
        }
        // Budget construction enforces the numeric ranges; the capability rules mirror
        // HostedProviderConfig so a descriptor cannot declare a feature its model lacks.
        val budget = HostedGenerationBudget(maxOutputTokens, reasoningEffort, temperature, parallelToolCalls)
        val limit = capabilities.maxOutputTokensLimit
        if (limit != null && budget.maxOutputTokens != null && budget.maxOutputTokens > limit) {
            throw HostedConfigurationException(
                "maxOutputTokens ${budget.maxOutputTokens} exceeds the declared model limit $limit",
            )
        }
        if (budget.reasoningEffort != null && !capabilities.supportsReasoningBudget) {
            throw HostedConfigurationException("reasoning budget was requested but the model declares no support")
        }
        if (budget.temperature != null && !capabilities.supportsTemperature) {
            throw HostedConfigurationException("temperature was requested but the model declares no support")
        }
        if (budget.parallelToolCalls != null && !capabilities.supportsParallelToolCalls) {
            throw HostedConfigurationException(
                "parallel tool calls were configured but the model declares no support",
            )
        }
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
/** Portable, non-secret description of one saved hosted profile. */
data class HostedProviderExportProfile(
    val id: String,
    val name: String,
    val protocol: HostedProtocol,
    val endpointOrigin: String,
    val model: String,
    val maxOutputTokens: Int?,
    val reasoningEffort: HostedReasoningEffort?,
    val temperature: Double?,
    val parallelToolCalls: Boolean?,
    val capabilities: HostedProviderCapabilities,
)

/** A synchronized snapshot of the selected hosted profile and its non-secret descriptors. */
data class HostedProviderExportSnapshot(
    val activeProfileId: String?,
    val profiles: List<HostedProviderExportProfile>,
)

/** Keeps only the endpoint origin; paths and all URI decorations are not backup descriptors. */
internal fun sanitizedProviderEndpointOrigin(endpoint: String): String {
    val uri = URI(endpoint.replace("{model}", "model"))
    val scheme = requireNotNull(uri.scheme)
    val host = requireNotNull(uri.host?.takeIf { it.isNotBlank() })
    return URI(scheme, null, host, uri.port, null, null, null).toASCIIString()
}

/** What one complete-state restore did to the saved provider profile descriptors. */
data class HostedProfileImportResult(
    /** Descriptors imported from the backup (updated in place or added). */
    val imported: Int,
    /** Imported profiles that still have no credential and need their key entered. */
    val withoutCredential: Int,
    /** The selected profile after the import, or null when no usable profile is selected. */
    val activeProfileId: String?,
)

/** The merged profile list and the outcome of merging a backup's descriptors. */
internal data class HostedProfileImport(
    val profiles: List<SavedHostedProvider>,
    val activeProfileId: String?,
    val result: HostedProfileImportResult,
)

/**
 * Merges a backup's non-secret profile descriptors into the device's saved profiles.
 *
 * Descriptors are imported; credentials are not. For a profile id that already exists on this
 * device the stored credential and full endpoint are kept — a backup carries neither a secret
 * nor an endpoint path — while its descriptor fields follow the backup. An id that exists only
 * in the backup is added with no credential and only the endpoint origin the export preserves,
 * so the user completes it through the normal provider flow. Profiles the backup does not
 * mention stay as they are: a restore never destroys a credential it cannot carry.
 *
 * The active selection follows only a profile that has a credential, so a restore never leaves
 * the app pointed at a profile that cannot run yet.
 */
internal fun mergeImportedDescriptors(
    local: List<SavedHostedProvider>,
    activeProfileId: String?,
    snapshot: HostedProviderExportSnapshot,
): HostedProfileImport {
    val merged = local.toMutableList()
    var imported = 0
    var withoutCredential = 0
    for (descriptor in snapshot.profiles) {
        val index = merged.indexOfFirst { it.id == descriptor.id }
        val existing = if (index >= 0) merged[index] else null
        val profile = if (existing != null) {
            existing.copy(
                name = descriptor.name,
                protocol = descriptor.protocol,
                model = descriptor.model,
                maxOutputTokens = descriptor.maxOutputTokens,
                reasoningEffort = descriptor.reasoningEffort,
                temperature = descriptor.temperature,
                parallelToolCalls = descriptor.parallelToolCalls,
                capabilities = descriptor.capabilities,
            )
        } else {
            SavedHostedProvider(
                id = descriptor.id,
                name = descriptor.name,
                protocol = descriptor.protocol,
                endpoint = descriptor.endpointOrigin,
                model = descriptor.model,
                credential = "",
                maxOutputTokens = descriptor.maxOutputTokens,
                reasoningEffort = descriptor.reasoningEffort,
                temperature = descriptor.temperature,
                parallelToolCalls = descriptor.parallelToolCalls,
                capabilities = descriptor.capabilities,
            )
        }
        if (index >= 0) merged[index] = profile else merged.add(profile)
        imported++
        if (!profile.hasCredential) withoutCredential++
    }
    val preferredActive = snapshot.activeProfileId
        ?.takeIf { id -> merged.any { it.id == id && it.hasCredential } }
        ?: activeProfileId?.takeIf { id -> merged.any { it.id == id && it.hasCredential } }
    return HostedProfileImport(
        profiles = merged,
        activeProfileId = preferredActive,
        result = HostedProfileImportResult(
            imported = imported,
            withoutCredential = withoutCredential,
            activeProfileId = preferredActive,
        ),
    )
}

/**
 * The profile list a rollback of [imported] restores from [local].
 *
 * Descriptor fields of profiles that existed in [previous] are put back; ids the import added
 * (present in [imported], absent from [previous]) are dropped. Credentials and full endpoints
 * are carried through untouched, which is what makes a cutover rollback safe to run without
 * reading or writing a secret.
 */
internal fun revertedImportProfiles(
    local: List<SavedHostedProvider>,
    previous: HostedProviderExportSnapshot,
    imported: HostedProviderExportSnapshot,
): List<SavedHostedProvider> {
    val previousById = previous.profiles.associateBy { it.id }
    val importedIds = imported.profiles.mapTo(HashSet()) { it.id }
    return local.mapNotNull { profile ->
        if (profile.id in importedIds && profile.id !in previousById) {
            null
        } else {
            previousById[profile.id]?.let { prior ->
                profile.copy(
                    name = prior.name,
                    protocol = prior.protocol,
                    model = prior.model,
                    maxOutputTokens = prior.maxOutputTokens,
                    reasoningEffort = prior.reasoningEffort,
                    temperature = prior.temperature,
                    parallelToolCalls = prior.parallelToolCalls,
                    capabilities = prior.capabilities,
                )
            } ?: profile
        }
    }
}

/**
 * Encrypted-at-rest, device-local provider profiles. The preference contains only AES-GCM
 * ciphertext; the AES key is non-exportable in Android Keystore. Backup is disabled by the
 * app manifest so encrypted provider profiles cannot leave the device through auto-backup.
 */
class HostedProviderSettingsStore(context: Context) : HostedProfileTransfer {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun active(): SavedHostedProvider? {
        val id = preferences.getString(ACTIVE_PROFILE_KEY, null) ?: return null
        return load().firstOrNull { it.id == id }
    }

    /** Returns no credentials or endpoint path/query material to callers that build an export. */
    @Synchronized
    override fun exportSnapshot(): HostedProviderExportSnapshot {
        val profiles = load()
        val activeId = preferences.getString(ACTIVE_PROFILE_KEY, null)
            ?.takeIf { id -> profiles.any { profile -> profile.id == id } }
        return HostedProviderExportSnapshot(
            activeProfileId = activeId,
            profiles = profiles.map { profile ->
                HostedProviderExportProfile(
                    id = profile.id,
                    name = profile.name,
                    protocol = profile.protocol,
                    endpointOrigin = sanitizedProviderEndpointOrigin(profile.endpoint),
                    model = profile.model,
                    maxOutputTokens = profile.maxOutputTokens,
                    reasoningEffort = profile.reasoningEffort,
                    temperature = profile.temperature,
                    parallelToolCalls = profile.parallelToolCalls,
                    capabilities = profile.capabilities,
                )
            },
        )
    }

    @Synchronized
    fun activate(id: String) {
        val profile = load().firstOrNull { it.id == id }
        require(profile != null) { "The selected provider profile is unavailable." }
        require(profile.hasCredential) {
            "This provider profile has no saved credential yet; enter its key before selecting it."
        }
        check(preferences.edit().putString(ACTIVE_PROFILE_KEY, id).commit()) {
            "The selected provider could not be saved."
        }
    }

    /**
     * Imports a complete-state backup's non-secret profile descriptors.
     *
     * Credentials are never imported; a profile already saved on this device keeps its own
     * credential and full endpoint (see [mergeImportedDescriptors]). Returns how many
     * descriptors were imported and how many still need a locally established key.
     */
    @Synchronized
    override fun importSnapshot(snapshot: HostedProviderExportSnapshot): HostedProfileImportResult {
        if (snapshot.profiles.isEmpty()) {
            return HostedProfileImportResult(
                imported = 0,
                withoutCredential = 0,
                activeProfileId = active()?.takeIf { it.hasCredential }?.id,
            )
        }
        val merged = mergeImportedDescriptors(
            local = load(),
            activeProfileId = preferences.getString(ACTIVE_PROFILE_KEY, null),
            snapshot = snapshot,
        )
        merged.profiles.forEach { profile ->
            if (profile.hasCredential) profile.validatedConfig() else profile.validateDescriptor()
        }
        save(merged.profiles)
        val editor = preferences.edit()
        if (merged.activeProfileId == null) {
            editor.remove(ACTIVE_PROFILE_KEY)
        } else {
            editor.putString(ACTIVE_PROFILE_KEY, merged.activeProfileId)
        }
        check(editor.commit()) { "The selected provider could not be saved." }
        return merged.result
    }

    /**
     * Puts the provider store back to the state before an import that did not commit.
     *
     * Only descriptor fields move: a profile that existed before gets its previous descriptor
     * back, an id the import added is removed, and the previous active id returns. The stored
     * credential and the full endpoint are never read, changed or copied, so rolling back a
     * cutover never touches a secret.
     */
    @Synchronized
    override fun revertImport(
        previous: HostedProviderExportSnapshot,
        imported: HostedProviderExportSnapshot,
    ) {
        val reverted = revertedImportProfiles(load(), previous, imported)
        save(reverted)
        val previousActive = previous.activeProfileId
            ?.takeIf { id -> reverted.any { it.id == id && it.hasCredential } }
            ?: preferences.getString(ACTIVE_PROFILE_KEY, null)
                ?.takeIf { id -> reverted.any { it.id == id && it.hasCredential } }
        val editor = preferences.edit()
        if (previousActive == null) {
            editor.remove(ACTIVE_PROFILE_KEY)
        } else {
            editor.putString(ACTIVE_PROFILE_KEY, previousActive)
        }
        check(editor.commit()) { "The selected provider could not be saved." }
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
        profiles.forEach { profile ->
            // A restored descriptor is stored without a credential; every profile that has one
            // still goes through the full runnable-configuration validation.
            if (profile.hasCredential) profile.validatedConfig() else profile.validateDescriptor()
        }
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
            endpoint = getString("endpoint"), model = getString("model"),
            credential = if (isNull("credential")) "" else getString("credential"),
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
