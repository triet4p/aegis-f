package io.github.triet4p.aegisf.data

import io.github.triet4p.aegisf.provider.HostedProfileImportResult
import io.github.triet4p.aegisf.provider.HostedProviderExportProfile
import io.github.triet4p.aegisf.provider.HostedProviderExportSnapshot
import io.github.triet4p.aegisf.provider.HostedProviderCapabilities
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedReasoningEffort
import io.github.triet4p.aegisf.provider.sanitizedProviderEndpointOrigin
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * The provider-store work one complete-state cutover needs.
 *
 * A cutover spans the SQLite database and the encrypted provider profile store, so it is
 * journaled: the previous and the imported non-secret descriptors are written to a durable
 * intent before the provider store is touched, and the provider store is put back exactly if
 * the database replacement does not commit.
 */
interface HostedProfileTransfer {
    /** The current descriptors, with only scheme/host/port endpoints and no credentials. */
    fun exportSnapshot(): HostedProviderExportSnapshot

    /** Imports the backup's descriptors; never imports a credential. */
    fun importSnapshot(snapshot: HostedProviderExportSnapshot): HostedProfileImportResult

    /**
     * Puts the provider store back to [previous]: descriptor fields of [previous] profiles are
     * restored, ids the import added are removed, and the previous active id returns. Credentials
     * and full endpoints are never read or written by this operation.
     */
    fun revertImport(
        previous: HostedProviderExportSnapshot,
        imported: HostedProviderExportSnapshot,
    )
}

/** One durable cutover intent: the previous and imported provider descriptors, bound to a digest. */
internal data class RestoreIntent(
    val backupDigestSha256: String,
    val createdAtEpochMillis: Long,
    val previousProfiles: HostedProviderExportSnapshot,
    val importedProfiles: HostedProviderExportSnapshot,
)

/** The journal exists but cannot be read; never cleared, because that would abandon recovery. */
internal class RestoreIntentUnreadable(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The write-ahead journal of one cutover, kept beside the pre-restore copies.
 *
 * The file holds only non-secret descriptors (ids, names, protocols, origin endpoints, model
 * limits, capabilities, the active id); it never holds a credential, a full endpoint, a mapping
 * or a cache handle. Writing goes through a synchronously written temporary file and an atomic
 * rename, so a crash leaves either the previous journal or the complete new one.
 */
internal class RestoreIntentStore(private val directory: File) {
    private val file = File(directory, FILE_NAME)
    private val temporary = File(directory, "$FILE_NAME.tmp")

    /** True while a cutover has a journal that has not been completed or rolled back. */
    fun pending(): Boolean = file.isFile

    fun read(): RestoreIntent {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (failure: Exception) {
            throw RestoreIntentUnreadable("The restore journal could not be read.", failure)
        }
        val root = try {
            JSONObject(text)
        } catch (failure: Exception) {
            throw RestoreIntentUnreadable("The restore journal is not valid JSON.", failure)
        }
        if (root.optString("format") != FORMAT || root.optInt("format_version", 0) != FORMAT_VERSION) {
            throw RestoreIntentUnreadable("The restore journal is not a recognized version.")
        }
        val digest = root.optString("backup_digest_sha256")
        if (!DIGEST.matches(digest)) {
            throw RestoreIntentUnreadable("The restore journal has no backup digest.")
        }
        val createdAt = root.optLong("created_at_epoch_millis", 0L)
        if (createdAt <= 0L) throw RestoreIntentUnreadable("The restore journal has no creation time.")
        return RestoreIntent(
            backupDigestSha256 = digest,
            createdAtEpochMillis = createdAt,
            previousProfiles = decodeSnapshot(root.optJSONObject("previous"), "previous"),
            importedProfiles = decodeSnapshot(root.optJSONObject("imported"), "imported"),
        )
    }

    fun write(intent: RestoreIntent) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw RestoreIntentUnreadable("The restore journal directory could not be created.")
        }
        val bytes = encode(intent).toString().toByteArray(Charsets.UTF_8)
        try {
            // "rws" makes every write synchronous: the temporary file is durable before the
            // rename, so a crash leaves either no journal or the complete one.
            RandomAccessFile(temporary, "rws").use { handle ->
                handle.write(bytes)
            }
        } catch (failure: Exception) {
            temporary.delete()
            throw RestoreIntentUnreadable("The restore journal could not be written.", failure)
        }
        if (!temporary.renameTo(file)) {
            // Android's POSIX rename replaces; this retry keeps the same path working where a
            // rename over an existing file is refused.
            file.delete()
            if (!temporary.renameTo(file)) {
                temporary.delete()
                throw RestoreIntentUnreadable("The restore journal could not be installed.")
            }
        }
    }

    fun clear() {
        file.delete()
        temporary.delete()
    }

    /**
     * Moves an unreadable journal aside so an explicit previous-state recovery can start.
     *
     * Returns the archived file, or null when there was none. The file is kept, not deleted:
     * it is the only remaining evidence of the interrupted cutover.
     */
    fun archiveUnreadable(): File? {
        if (!file.isFile) return null
        val archive = File(directory, "restore-intent-unreadable-${System.currentTimeMillis()}.json")
        return if (file.renameTo(archive)) archive else null
    }

    private fun encode(intent: RestoreIntent): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("format_version", FORMAT_VERSION)
        .put("backup_digest_sha256", intent.backupDigestSha256)
        .put("created_at_epoch_millis", intent.createdAtEpochMillis)
        .put("previous", encodeSnapshot(intent.previousProfiles))
        .put("imported", encodeSnapshot(intent.importedProfiles))

    private fun encodeSnapshot(snapshot: HostedProviderExportSnapshot): JSONObject = JSONObject()
        .put("active_profile_id", snapshot.activeProfileId ?: JSONObject.NULL)
        .put(
            "profiles",
            JSONArray().apply {
                snapshot.profiles.forEach { profile ->
                    put(
                        JSONObject()
                            .put("id", profile.id)
                            .put("name", profile.name)
                            .put("protocol", profile.protocol.name)
                            .put("endpoint_origin", profile.endpointOrigin)
                            .put("model", profile.model)
                            .put("max_output_tokens", profile.maxOutputTokens ?: JSONObject.NULL)
                            .put("reasoning_effort", profile.reasoningEffort?.name ?: JSONObject.NULL)
                            .put("temperature", profile.temperature ?: JSONObject.NULL)
                            .put("parallel_tool_calls", profile.parallelToolCalls ?: JSONObject.NULL)
                            .put(
                                "capabilities",
                                JSONObject()
                                    .put("tool_calls", profile.capabilities.supportsToolCalls)
                                    .put(
                                        "parallel_tool_calls",
                                        profile.capabilities.supportsParallelToolCalls,
                                    )
                                    .put(
                                        "reasoning_budget",
                                        profile.capabilities.supportsReasoningBudget,
                                    )
                                    .put(
                                        "temperature",
                                        profile.capabilities.supportsTemperature,
                                    )
                                    .put(
                                        "max_output_tokens_limit",
                                        profile.capabilities.maxOutputTokensLimit ?: JSONObject.NULL,
                                    ),
                            ),
                    )
                }
            },
        )

    private fun decodeSnapshot(value: JSONObject?, where: String): HostedProviderExportSnapshot {
        if (value == null) throw RestoreIntentUnreadable("The restore journal has no $where descriptors.")
        if (value.keys().asSequence().toSet() != setOf("active_profile_id", "profiles")) {
            throw RestoreIntentUnreadable("The restore journal's $where descriptors are not recognized.")
        }
        val entries = value.optJSONArray("profiles")
            ?: throw RestoreIntentUnreadable("The restore journal's $where descriptor list is missing.")
        val ids = HashSet<String>()
        val profiles = ArrayList<HostedProviderExportProfile>(entries.length())
        for (index in 0 until entries.length()) {
            val profile = entries.optJSONObject(index)
                ?: throw RestoreIntentUnreadable("The restore journal's $where descriptor $index is missing.")
            if (profile.keys().asSequence().toSet() != PROFILE_KEYS) {
                throw RestoreIntentUnreadable("The restore journal's $where descriptor $index is not recognized.")
            }
            val id = profile.optString("id")
            if (id.isBlank() || !ids.add(id)) {
                throw RestoreIntentUnreadable("The restore journal's $where descriptor id is unusable.")
            }
            val protocol = runCatching { HostedProtocol.valueOf(profile.getString("protocol")) }.getOrNull()
                ?: throw RestoreIntentUnreadable("The restore journal's $where protocol is unusable.")
            val origin = profile.optString("endpoint_origin")
            if (runCatching { sanitizedProviderEndpointOrigin(origin) }.getOrNull() != origin) {
                throw RestoreIntentUnreadable("The restore journal's $where endpoint is not an origin.")
            }
            val capabilities = profile.optJSONObject("capabilities")
                ?: throw RestoreIntentUnreadable("The restore journal's $where capabilities are missing.")
            if (capabilities.keys().asSequence().toSet() != CAPABILITY_KEYS) {
                throw RestoreIntentUnreadable("The restore journal's $where capabilities are not recognized.")
            }
            profiles.add(
                HostedProviderExportProfile(
                    id = id,
                    name = profile.optString("name"),
                    protocol = protocol,
                    endpointOrigin = origin,
                    model = profile.optString("model"),
                    maxOutputTokens = profile.optionalInt("max_output_tokens", where),
                    reasoningEffort = profile.optionalString("reasoning_effort", where)?.let { name ->
                        runCatching { HostedReasoningEffort.valueOf(name) }.getOrNull()
                            ?: throw RestoreIntentUnreadable("The restore journal's $where effort is unusable.")
                    },
                    temperature = profile.optionalDouble("temperature", where),
                    parallelToolCalls = profile.optionalBoolean("parallel_tool_calls", where),
                    capabilities = HostedProviderCapabilities(
                        supportsToolCalls = capabilities.requiredBoolean("tool_calls", where),
                        supportsParallelToolCalls = capabilities.requiredBoolean("parallel_tool_calls", where),
                        supportsReasoningBudget = capabilities.requiredBoolean("reasoning_budget", where),
                        supportsTemperature = capabilities.requiredBoolean("temperature", where),
                        maxOutputTokensLimit = capabilities.optionalInt("max_output_tokens_limit", where),
                    ),
                ),
            )
        }
        val active = value.optionalString("active_profile_id", where)
        return HostedProviderExportSnapshot(activeProfileId = active, profiles = profiles)
    }

    private fun JSONObject.optionalString(name: String, where: String): String? {
        if (!has(name)) throw RestoreIntentUnreadable("The restore journal's $where $name is missing.")
        return if (isNull(name)) null else getString(name)
    }

    private fun JSONObject.optionalInt(name: String, where: String): Int? {
        if (!has(name)) throw RestoreIntentUnreadable("The restore journal's $where $name is missing.")
        return if (isNull(name)) null else getInt(name)
    }

    private fun JSONObject.optionalDouble(name: String, where: String): Double? {
        if (!has(name)) throw RestoreIntentUnreadable("The restore journal's $where $name is missing.")
        return if (isNull(name)) null else getDouble(name)
    }

    private fun JSONObject.optionalBoolean(name: String, where: String): Boolean? {
        if (!has(name)) throw RestoreIntentUnreadable("The restore journal's $where $name is missing.")
        return if (isNull(name)) null else getBoolean(name)
    }

    private fun JSONObject.requiredBoolean(name: String, where: String): Boolean {
        if (!has(name) || isNull(name)) {
            throw RestoreIntentUnreadable("The restore journal's $where $name is missing.")
        }
        return getBoolean(name)
    }

    private companion object {
        const val FILE_NAME = "restore-intent.json"
        const val FORMAT = "aegis-f-restore-intent"
        const val FORMAT_VERSION = 1
        val DIGEST = Regex("""[0-9a-f]{64}""")
        val PROFILE_KEYS = setOf(
            "id",
            "name",
            "protocol",
            "endpoint_origin",
            "model",
            "max_output_tokens",
            "reasoning_effort",
            "temperature",
            "parallel_tool_calls",
            "capabilities",
        )
        val CAPABILITY_KEYS = setOf(
            "tool_calls",
            "parallel_tool_calls",
            "reasoning_budget",
            "temperature",
            "max_output_tokens_limit",
        )
    }
}
