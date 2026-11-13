package io.github.triet4p.aegisf.data

import io.github.triet4p.aegisf.provider.HostedProfileImportResult
import io.github.triet4p.aegisf.provider.HostedProtocol
import io.github.triet4p.aegisf.provider.HostedProviderCapabilities
import io.github.triet4p.aegisf.provider.HostedProviderExportProfile
import io.github.triet4p.aegisf.provider.HostedProviderExportSnapshot
import io.github.triet4p.aegisf.provider.HostedReasoningEffort
import io.github.triet4p.aegisf.provider.sanitizedProviderEndpointOrigin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.Callable

/** The largest backup this restore path accepts, and the largest previous-state copy it keeps. */
const val MAX_COMPLETE_STATE_RESTORE_BYTES: Int = 8 * 1024 * 1024

/** How many pre-restore copies are kept newest-first; older copies are pruned after a restore. */
private const val RECOVERY_COPY_LIMIT = 3

/** The file name prefix of one pre-restore copy inside the app's private no-backup directory. */
private const val RECOVERY_COPY_PREFIX = "complete-state-before-restore-"

/** Why a picked file was refused. The UI localizes these; the detail stays diagnostic text. */
enum class RestoreRejectionCode {
    /** The file is not an `aegis-f-complete-state` document. */
    UNSUPPORTED_FORMAT,

    /** The document version is newer or otherwise not one this build can read. */
    UNSUPPORTED_VERSION,

    /** The document was written by a different database schema than the one running. */
    UNSUPPORTED_SCHEMA,

    /** The file, or the previous state to be preserved, exceeds [MAX_COMPLETE_STATE_RESTORE_BYTES]. */
    TOO_LARGE,

    /** The document is well-typed JSON but violates a schema, type, range, or ownership rule. */
    INVALID,

    /**
     * An earlier cutover is still completing or needs the user's previous-state recovery; the
     * command boundary and a new restore both refuse until it settles.
     */
    RECOVERY_PENDING,
}

/** One refused restore. Nothing has been written when this is thrown. */
class RestoreRejection(
    val code: RestoreRejectionCode,
    override val message: String,
    /** The byte ceiling that was exceeded, for [RestoreRejectionCode.TOO_LARGE]. */
    val limitBytes: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** One backup's contents, read and validated but not yet applied. */
data class RestorePreview(
    /** SHA-256 of the exact bytes the preview describes; the same bytes are applied on confirm. */
    val digestSha256: String,
    val exportedAtEpochMillis: Long,
    val schemaVersion: Long,
    val categoryCount: Int,
    val ruleCount: Int,
    val transactionCount: Int,
    val draftCount: Int,
    val pendingDraftCount: Int,
    val dismissedDraftCount: Int,
    val captureCount: Int,
    val eventCount: Int,
    val linkCount: Int,
    val historicalSnapshotCount: Int,
    val hostedProfileCount: Int,
    /**
     * Pending hosted category choices whose category is archived in this backup. They are
     * reopened during the replacement, because the category is no longer assignable.
     */
    val reopenedHostedCategoryCount: Int,
) {
    val restoredRowCount: Int
        get() = categoryCount + ruleCount + transactionCount + draftCount + captureCount +
            eventCount + linkCount + historicalSnapshotCount
}

/** The result of one completed replacement. */
data class RestoreResult(
    val preview: RestorePreview,
    /** The app-private copy of the state as it was immediately before this replacement. */
    val previousState: RestoreRecoveryCopy,
    /** Rows imported into the eight business/provenance tables. */
    val restoredRowCount: Int,
    /** What the backup's non-secret provider profile descriptors did to the saved profiles. */
    val hostedProfiles: HostedProfileImportResult,
)

/** What an interrupted cutover did when it was completed or put back at start-up. */
sealed interface RestoreRecoveryOutcome {
    /** No journal or marker was pending. */
    data object NothingPending : RestoreRecoveryOutcome

    /** The journal's replacement never committed; provider descriptors were put back. */
    data object RolledBack : RestoreRecoveryOutcome

    /** The replacement had committed; descriptors are applied and the journal is cleared. */
    data object Completed : RestoreRecoveryOutcome

    /**
     * The interrupted cutover cannot finish without the user. The journal is kept, the command
     * boundary stays closed for ordinary work, and the previous-state copies remain available.
     */
    data class Blocked(val detail: String) : RestoreRecoveryOutcome
}

/** One app-private copy of the state that existed before a restore. */
data class RestoreRecoveryCopy(
    val file: File,
    val createdAtEpochMillis: Long,
    val sizeBytes: Long,
)

/**
 * Validates one `aegis-f-complete-state` document and replaces the stored business state with it.
 *
 * The document is validated in full before any stored row is touched: envelope, fixed table set,
 * column set, JSON types, enum vocabulary, numeric ranges, cross-table references and the
 * ownership rules the running app maintains. Nothing is merged — the eight exported tables,
 * their rows, ids, revisions and nulls are replaced exactly — and the replacement, together with
 * the clearing of the operation journals below, runs as one SQLite transaction, so an
 * interruption or a failed statement leaves the previous state untouched.
 *
 * A pre-restore copy of the current state is exported to app-private no-backup storage before
 * the transaction starts, and that copy is refused unless it fits the same size ceiling the
 * picked backup must fit: recoverability is part of the contract, not a best-effort extra.
 *
 * Two kinds of stored work are dropped rather than imported, because they can only replay stale
 * intentions against different data:
 *
 * - `workflow_operations` idempotency keys: a later command under a restored key would be
 *   answered from a journal entry that describes the replaced world.
 * - `arrival_reconciliation_operations`: the process-start resume path would otherwise re-run
 *   old link decisions against the restored drafts.
 *
 * Historical classification snapshots are imported exactly as stored and remain provenance: no
 * active code reads them for a decision, and this restorer does not rewrite review categories,
 * review revisions or queue order from them.
 *
 * A replacement is a two-store cutover: the SQLite rows and the device-local provider profile
 * descriptors. It is journaled so the cutover is all-or-nothing across both stores even when a
 * process dies:
 *
 * - A durable non-secret intent (previous descriptors, imported descriptors, backup digest) is
 *   written before either store changes.
 * - Descriptors are applied first; the SQLite replacement then runs with a commit marker row in
 *   its own transaction, and the SQLite commit is the point of no return.
 * - If the SQLite transaction does not commit, the provider store is put back exactly from the
 *   intent (descriptor fields restored, ids the import added removed, previous active id
 *   returned); credentials and full endpoints are never read or written by that rollback.
 * - If the process dies before the commit, start-up recovery performs the same rollback. If it
 *   dies after the commit, the marker proves the rows were replaced and recovery completes the
 *   descriptor application. The intent is cleared only after descriptor persistence is verified,
 *   then the marker; a marker left alone means the cutover completed.
 * - While an intent is pending, [isRecoveryPending] is true and the command boundary refuses
 *   ordinary work, so nothing user-visible happens against a half-applied cutover.
 *
 * The journal never contains a credential, a full endpoint, a mapping or a cache handle: it holds
 * only the same non-secret descriptors the export itself carries.
 *
 * A replacement also advances [advanceStateEpoch] before the stores change, and every command
 * from an agent session admitted against the earlier state is refused by the command boundary.
 * That is what stops an in-flight hosted result — even one whose expected revision would
 * coincidentally match the restored row — from being applied to the replaced data.
 */
class CompleteStateRestorer(
    private val database: LedgerDatabase,
    private val exporter: CompleteStateExporter,
    private val recoveryDirectory: File,
    private val profileTransfer: HostedProfileTransfer,
    private val maxBackupBytes: Int = MAX_COMPLETE_STATE_RESTORE_BYTES,
    private val advanceStateEpoch: () -> Unit = {},
) {
    private val intentStore = RestoreIntentStore(recoveryDirectory)

    /**
     * Reads one picked file into memory, enforcing [maxBackupBytes].
     *
     * The bytes are what the preview describes and what [restore] applies, so the confirmed
     * document is exactly the previewed one even if the picked file changes afterwards.
     */
    fun readBackup(input: InputStream): ByteArray {
        val buffer = ByteArray(64 * 1024)
        val collected = java.io.ByteArrayOutputStream()
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            collected.write(buffer, 0, read)
            if (collected.size() > maxBackupBytes) {
                throw RestoreRejection(
                    RestoreRejectionCode.TOO_LARGE,
                    "The selected file is larger than the ${maxBackupBytes / 1024 / 1024} MB " +
                        "limit; nothing was changed.",
                    limitBytes = maxBackupBytes,
                )
            }
        }
        return collected.toByteArray()
    }

    /** Reads and validates [bytes] without touching stored data. */
    fun preview(bytes: ByteArray): RestorePreview = parse(bytes).preview

    /**
     * True while a cutover journal has not been completed or rolled back.
     *
     * The command boundary consults this so no ordinary or hosted work runs against a state
     * whose two stores are still being settled.
     */
    fun isRecoveryPending(): Boolean = intentStore.pending()

    /**
     * Replaces the stored business state with the validated [bytes].
     *
     * The SQLite rows and the provider descriptors change together or not at all: descriptors are
     * applied first, the SQLite replacement commits second, and a failed commit puts the
     * descriptors back exactly. A process death in either window is finished by
     * [completeInterruptedRestore]. [allowPendingReplacement] is used only by the explicit
     * previous-state recovery action, which may start over an interrupted journal.
     */
    fun restore(
        bytes: ByteArray,
        now: Long = System.currentTimeMillis(),
        allowPendingReplacement: Boolean = false,
    ): RestoreResult {
        val parsed = parse(bytes)
        if (intentStore.pending()) {
            when (val outcome = completeInterruptedRestore(now)) {
                is RestoreRecoveryOutcome.Blocked -> if (!allowPendingReplacement) {
                    throw RestoreRejection(
                        RestoreRejectionCode.RECOVERY_PENDING,
                        "An interrupted restore is still being settled (${outcome.detail}). " +
                            "Restore the previous-state copy to return to a known state, or retry.",
                    )
                } else {
                    // Explicit recovery: keep the unreadable journal as evidence and start over
                    // from a copy the user chose.
                    intentStore.archiveUnreadable()
                }

                else -> Unit
            }
        }
        val previous = writePreviousStateCopy()
        // Advanced before either store changes so no session admitted against the previous rows
        // can pass the command boundary once the rows change.
        advanceStateEpoch()
        val intent = RestoreIntent(
            backupDigestSha256 = parsed.preview.digestSha256,
            createdAtEpochMillis = now,
            previousProfiles = profileTransfer.exportSnapshot(),
            importedProfiles = parsed.hostedProfiles,
        )
        intentStore.write(intent)

        // Descriptors first. The provider store commits atomically per write, so a failure here
        // leaves it as it was; the revert is still run so the journal is settled the same way.
        val imported = try {
            profileTransfer.importSnapshot(intent.importedProfiles)
        } catch (failure: Exception) {
            val reverted = revertDescriptors(intent)
            if (!reverted) {
                throw RestoreRejection(
                    RestoreRejectionCode.RECOVERY_PENDING,
                    "The provider profile descriptors could not be imported and are being put " +
                        "back; the journal will finish the rollback automatically.",
                    cause = failure,
                )
            }
            throw RestoreRejection(
                RestoreRejectionCode.INVALID,
                "The provider profile descriptors could not be imported; nothing was changed.",
                cause = failure,
            )
        }

        try {
            database.runInTransaction(
                Callable {
                    apply(parsed)
                    writeCommitMarker(parsed.preview.digestSha256, now)
                    Unit
                },
            )
        } catch (failure: Throwable) {
            // SQLite rolled back. Put the descriptors back; if that write fails, the journal
            // stays and start-up recovery retries it.
            if (!revertDescriptors(intent)) {
                throw RestoreRejection(
                    RestoreRejectionCode.RECOVERY_PENDING,
                    "The replacement did not commit and the provider descriptors are still being " +
                        "put back; the journal will finish the rollback automatically.",
                    cause = failure,
                )
            }
            throw failure
        }

        // Both stores are durable now. Clearing the intent first means a marker left alone always
        // describes a completed cutover; clearing it in the other order could abandon a committed
        // marker behind a journal that still says the replacement did not happen.
        intentStore.clear()
        deleteCommitMarker(parsed.preview.digestSha256)
        pruneRecoveryCopies()
        return RestoreResult(
            preview = parsed.preview,
            previousState = previous,
            restoredRowCount = parsed.preview.restoredRowCount,
            hostedProfiles = imported,
        )
    }

    /**
     * Settles a cutover interrupted by a process death.
     *
     * - Journal present and its SQLite marker absent: the replacement never committed, so the
     *   provider descriptors are put back and the journal cleared.
     * - Journal present and its marker present: the rows were replaced, so the descriptors are
     *   applied (idempotently) and the journal and marker cleared.
     * - Journal unreadable or a store write failing: [RestoreRecoveryOutcome.Blocked]; the
     *   journal is kept and ordinary work stays closed until the user recovers.
     * - No journal but a restore marker: the cutover had already completed; the stale marker is
     *   removed and nothing else changes.
     */
    fun completeInterruptedRestore(now: Long = System.currentTimeMillis()): RestoreRecoveryOutcome {
        if (!intentStore.pending()) {
            return if (removeRestoreMarkers()) {
                RestoreRecoveryOutcome.Completed
            } else {
                RestoreRecoveryOutcome.NothingPending
            }
        }
        val intent = try {
            intentStore.read()
        } catch (failure: RestoreIntentUnreadable) {
            return RestoreRecoveryOutcome.Blocked(
                "the restore journal is unreadable; restore the previous-state copy",
            )
        }
        if (hasCommitMarker(intent.backupDigestSha256)) {
            return try {
                profileTransfer.importSnapshot(intent.importedProfiles)
                intentStore.clear()
                deleteCommitMarker(intent.backupDigestSha256)
                RestoreRecoveryOutcome.Completed
            } catch (failure: Exception) {
                RestoreRecoveryOutcome.Blocked("the provider descriptors are still being applied")
            }
        }
        return if (revertDescriptors(intent)) {
            RestoreRecoveryOutcome.RolledBack
        } else {
            RestoreRecoveryOutcome.Blocked("the provider descriptors are still being put back")
        }
    }

    /** Puts the provider store back from [intent] and clears the journal, or leaves both. */
    private fun revertDescriptors(intent: RestoreIntent): Boolean = try {
        profileTransfer.revertImport(intent.previousProfiles, intent.importedProfiles)
        intentStore.clear()
        true
    } catch (failure: Exception) {
        false
    }

    /** The app-private copies of previous states, newest first. */
    fun recoveryCopies(): List<RestoreRecoveryCopy> {
        val files = recoveryDirectory.listFiles { file ->
            file.isFile && file.name.startsWith(RECOVERY_COPY_PREFIX) && file.name.endsWith(".json")
        } ?: return emptyList()
        return files
            .sortedByDescending { it.lastModified() }
            .map { file ->
                RestoreRecoveryCopy(
                    file = file,
                    createdAtEpochMillis = file.lastModified(),
                    sizeBytes = file.length(),
                )
            }
    }

    /** Reads one previous-state copy under the same size ceiling as a picked backup. */
    fun readRecoveryCopy(copy: RestoreRecoveryCopy): ByteArray =
        copy.file.inputStream().use(::readBackup)

    // --- Validation ---------------------------------------------------------

    private fun parse(bytes: ByteArray): ParsedState {
        if (bytes.size > maxBackupBytes) {
            throw RestoreRejection(
                RestoreRejectionCode.TOO_LARGE,
                "The selected file is larger than the ${maxBackupBytes / 1024 / 1024} MB limit; " +
                    "nothing was changed.",
                limitBytes = maxBackupBytes,
            )
        }
        val text = decodeUtf8(bytes)
        val root = try {
            JSONObject(text)
        } catch (failure: Exception) {
            throw RestoreRejection(
                RestoreRejectionCode.UNSUPPORTED_FORMAT,
                "The selected file is not readable JSON; nothing was changed.",
            )
        }
        requireNoCredentialMaterial(root)
        val format = root.stringOrNull("format")
            ?: invalid("The document has no format name.")
        if (format != FORMAT) {
            throw RestoreRejection(
                RestoreRejectionCode.UNSUPPORTED_FORMAT,
                "The selected file is not an Aegis F complete-state backup; nothing was changed.",
            )
        }
        val formatVersion = root.longOrNull("format_version")
            ?: invalid("The document has no format version.")
        if (formatVersion != FORMAT_VERSION) {
            throw RestoreRejection(
                RestoreRejectionCode.UNSUPPORTED_VERSION,
                "This backup format version ($formatVersion) is not supported by this app.",
            )
        }
        val schemaVersion = root.longOrNull("database_schema_version")
            ?: invalid("The document has no database schema version.")
        if (schemaVersion != LedgerDatabase.VERSION.toLong()) {
            throw RestoreRejection(
                RestoreRejectionCode.UNSUPPORTED_SCHEMA,
                "This backup was written by database schema $schemaVersion; this app stores " +
                    "schema ${LedgerDatabase.VERSION}. Restoring a different schema is not " +
                    "supported yet; nothing was changed.",
            )
        }
        val exportedAt = root.longOrNull("exported_at_epoch_millis")
            ?: invalid("The document has no export instant.")
        if (exportedAt <= 0L) invalid("The export instant is not a valid time.")
        if (root.stringOrNull("money_unit") != "VND") {
            invalid("The backup does not declare exact VND amounts.")
        }
        if (root.stringOrNull("encryption") != "none") {
            invalid("This restore path reads only the documented plaintext export.")
        }
        val tables = root.objectOrNull("tables")
            ?: invalid("The document has no stored tables.")
        val tableNames = tables.keys().asSequence().toSet()
        if (tableNames != TABLE_SPECS.map { it.exportName }.toSet()) {
            invalid(
                "The document's tables are not the complete-state set this app stores " +
                    "(${tableNames.sorted().joinToString()} were present).",
            )
        }

        val rows = TABLE_SPECS.associate { spec ->
            val array = tables.optJSONArray(spec.exportName)
                ?: invalid("The stored table ${spec.exportName} is not a list of rows.")
            spec.exportName to decodeTable(spec, array)
        }
        val hostedProfiles = buildHostedProfiles(root.optJSONObject("hosted_profiles"))
        val preview = validateState(
            rows = rows,
            digestSha256 = sha256Hex(bytes),
            exportedAtEpochMillis = exportedAt,
            schemaVersion = schemaVersion,
            hostedProfiles = hostedProfiles,
        )
        return ParsedState(preview = preview, rows = rows, hostedProfiles = hostedProfiles)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw RestoreRejection(
            RestoreRejectionCode.UNSUPPORTED_FORMAT,
            "The selected file is not valid UTF-8 text; nothing was changed.",
        )
    }

    private fun decodeTable(spec: TableSpec, array: JSONArray): List<Row> {
        val expected = spec.columns.map { it.name }.toSet()
        return List(array.length()) { index ->
            val row = array.optJSONObject(index)
                ?: invalid("${spec.exportName}[$index] is not a row object.")
            val present = row.keys().asSequence().toSet()
            if (present != expected) {
                val missing = expected - present
                val extra = present - expected
                invalid(
                    buildString {
                        append("${spec.exportName}[$index] does not match the stored columns")
                        if (missing.isNotEmpty()) append("; missing ${missing.sorted().joinToString()}")
                        if (extra.isNotEmpty()) append("; unknown ${extra.sorted().joinToString()}")
                        append('.')
                    },
                )
            }
            val decoded = LinkedHashMap<String, Any?>(spec.columns.size)
            for (column in spec.columns) {
                val where = "${spec.exportName}[$index].${column.name}"
                val raw = row.get(column.name)
                val value = decodeValue(column, raw, where)
                decoded[column.name] = value
            }
            decoded
        }
    }

    private fun decodeValue(column: ColumnSpec, raw: Any?, where: String): Any? {
        if (raw == null || raw == JSONObject.NULL) {
            if (!column.nullable) invalid("$where is required but was null.")
            return null
        }
        return when (column.kind) {
            ColumnKind.INTEGER -> when (raw) {
                is Int -> raw.toLong()
                is Long -> raw
                else -> invalid("$where must be a whole number.")
            }

            ColumnKind.REAL -> when (raw) {
                is Double -> raw
                is Float -> raw.toDouble()
                is Int -> raw.toDouble()
                is Long -> raw.toDouble()
                else -> invalid("$where must be a number.")
            }

            ColumnKind.TEXT -> raw as? String
                ?: invalid("$where must be text.")
        }
    }

    /**
     * Rejects a document that carries credential-like object keys anywhere.
     *
     * The exporter never writes such a key, so finding one means the file was edited or produced
     * by something else. Values are not inspected: a saved note may legitimately contain any
     * text, and rejecting it for its wording would refuse the owner's own records.
     */
    private fun requireNoCredentialMaterial(value: Any?, where: String = "document") {
        when (value) {
            is JSONObject -> {
                for (key in value.keys().asSequence()) {
                    if (normalizeKey(key) in FORBIDDEN_KEYS) {
                        invalid("$where.$key is a credential or reversible-mapping field, which a backup must not contain.")
                    }
                    requireNoCredentialMaterial(value.get(key), "$where.$key")
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    requireNoCredentialMaterial(value.get(index), "$where[$index]")
                }
            }
        }
    }

    private fun validateState(
        rows: Map<String, List<Row>>,
        digestSha256: String,
        exportedAtEpochMillis: Long,
        schemaVersion: Long,
        hostedProfiles: HostedProviderExportSnapshot,
    ): RestorePreview {
        val categories = rows.getValue("categories")
        val rules = rows.getValue("remembered_category_rules")
        val transactions = rows.getValue("transactions")
        val drafts = rows.getValue("pending_bank_drafts")
        val captures = rows.getValue("capture_messages")
        val events = rows.getValue("note_events")
        val links = rows.getValue("context_links")
        val snapshots = rows.getValue("classification_snapshots")

        val categoryIds = collectIds(categories, "categories")
        val transactionIds = collectIds(transactions, "transactions")
        val captureIds = collectIds(captures, "capture_messages")
        val eventIds = collectIds(events, "note_events")
        val draftIds = collectIds(drafts, "pending_bank_drafts")

        validateCategories(categories)
        validateRules(rules, categoryIds)
        validateTransactions(transactions, categoryIds)
        validateCaptures(captures, draftIds, drafts)
        validateEvents(events, captureIds, transactionIds)
        validateDrafts(drafts, categoryIds, transactionIds)
        validateLinks(links, eventIds, draftIds)
        validateSnapshots(snapshots, draftIds)

        val pending = drafts.count {
            it.stringOrNull("reviewStatus") == IntakeReviewStatus.PENDING.name
        }
        val dismissed = drafts.count {
            it.stringOrNull("reviewStatus") == IntakeReviewStatus.DISMISSED.name
        }
        val archivedCategoryIds = categories
            .filter { it["archived"] == 1L }
            .map { it["id"] as Long }
            .toSet()
        val reopened = drafts.count { row ->
            row.stringOrNull("reviewStatus") == IntakeReviewStatus.PENDING.name &&
                row["confirmedTransactionId"] == null &&
                row.stringOrNull("reviewCategoryOrigin") == ReviewCategoryOrigin.HOSTED.name &&
                (row["reviewCategoryId"] as? Long) in archivedCategoryIds
        }

        return RestorePreview(
            digestSha256 = digestSha256,
            exportedAtEpochMillis = exportedAtEpochMillis,
            schemaVersion = schemaVersion,
            categoryCount = categories.size,
            ruleCount = rules.size,
            transactionCount = transactions.size,
            draftCount = drafts.size,
            pendingDraftCount = pending,
            dismissedDraftCount = dismissed,
            captureCount = captures.size,
            eventCount = events.size,
            linkCount = links.size,
            historicalSnapshotCount = snapshots.size,
            hostedProfileCount = hostedProfiles.profiles.size,
            reopenedHostedCategoryCount = reopened,
        )
    }

    private fun validateCategories(categories: List<Row>) {
        for ((index, row) in categories.withIndex()) {
            val where = "categories[$index]"
            val id = row.long("id", where)
            requirePositiveId(id, where)
            val name = row.text("name", where)
            if (name.isBlank()) invalid("$where.name is blank.")
            row.requireFlag("archived", where)
            val created = row.long("createdAtEpochMillis", where)
            val updated = row.long("updatedAtEpochMillis", where)
            requireInstant(created, "$where.createdAtEpochMillis")
            requireInstant(updated, "$where.updatedAtEpochMillis")
            if (updated < created) invalid("$where.updatedAtEpochMillis is before its creation.")
        }
    }

    private fun validateRules(rules: List<Row>, categoryIds: Set<Long>) {
        val keys = HashSet<String>()
        for ((index, row) in rules.withIndex()) {
            val where = "remembered_category_rules[$index]"
            val key = row.text("patternKey", where)
            if (key.isBlank()) invalid("$where.patternKey is blank.")
            if (!keys.add(key)) invalid("$where.patternKey is duplicated.")
            requireReference(row.long("categoryId", where), categoryIds, "$where.categoryId")
        }
    }

    private fun validateTransactions(transactions: List<Row>, categoryIds: Set<Long>) {
        for ((index, row) in transactions.withIndex()) {
            val where = "transactions[$index]"
            if (row.text("type", where) !in TRANSACTION_TYPES) {
                invalid("$where.type is not a stored ledger type.")
            }
            val amount = row.longOrNull("amountVnd", where)
                ?: invalid("$where.amountVnd is required.")
            if (amount < VndMoney.MIN_AMOUNT_VND) {
                invalid("$where.amountVnd must be at least ${VndMoney.MIN_AMOUNT_VND} whole dong.")
            }
            requireInstant(row.long("occurredAtEpochMillis", where), "$where.occurredAtEpochMillis")
            row.longOrNull("categoryId", where)?.let {
                requireReference(it, categoryIds, "$where.categoryId")
            }
            if (row.text("confirmation", where) !in CONFIRMATION_STATUSES) {
                invalid("$where.confirmation is not a stored confirmation state.")
            }
            if (row.text("source", where) !in RECORD_SOURCES) {
                invalid("$where.source is not a stored record source.")
            }
            requireInstant(row.long("createdAtEpochMillis", where), "$where.createdAtEpochMillis")
            requireInstant(row.long("updatedAtEpochMillis", where), "$where.updatedAtEpochMillis")
            row.requireRevision("revision", where)
        }
    }

    private fun validateCaptures(
        captures: List<Row>,
        draftIds: Set<Long>,
        drafts: List<Row>,
    ) {
        val pairs = HashSet<String>()
        val draftsById = drafts.associateBy { it.long("id", "pending_bank_drafts") }
        for ((index, row) in captures.withIndex()) {
            val where = "capture_messages[$index]"
            val kind = row.text("captureKind", where)
            if (kind !in CAPTURE_KINDS) invalid("$where.captureKind is not a stored capture kind.")
            val sourceKind = row.text("sourceKind", where)
            if (sourceKind !in CAPTURE_SOURCE_KINDS) {
                invalid("$where.sourceKind is not a stored capture source.")
            }
            if (!captureKindMatchesSource(kind, sourceKind)) {
                invalid("$where.captureKind and sourceKind do not belong together.")
            }
            val identity = row.text("sourceIdentity", where)
            if (identity.isBlank()) invalid("$where.sourceIdentity is blank.")
            if (!pairs.add("$sourceKind\u001F$identity")) {
                invalid("$where.sourceIdentity is duplicated for its source kind.")
            }
            row.requireRevision("revision", where)
            row.requireFlag("tombstone", where)
            val capturedAt = row.longOrNull("capturedAtEpochMillis", where)
            if (capturedAt != null) requireInstant(capturedAt, "$where.capturedAtEpochMillis")
            val body = row.stringOrNull("bodyText")
            if (row.long("tombstone", where) == 1L && body != null) {
                invalid("$where is a text-free tombstone and must not carry body text.")
            }
            val timezone = row.stringOrNull("captureTimezone")
            if (timezone != null && timezone.isBlank()) invalid("$where.captureTimezone is blank.")

            if (sourceKind == CaptureSourceKind.BANK_NOTIFICATION.name) {
                if (kind != CaptureKind.BANK_EVIDENCE_REF.name) {
                    invalid("$where is bank evidence but is not stored as a bank evidence reference.")
                }
                val draftId = bankEvidenceDraftId(identity)
                    ?: invalid("$where.sourceIdentity is not a bank-draft reference.")
                requireReference(draftId, draftIds, "$where.sourceIdentity")
                if (row.stringOrNull("originRef") != identity) {
                    invalid("$where.originRef does not own the same draft as its source identity.")
                }
                if (row.long("revision", where) != 0L) {
                    invalid("$where.revision must stay 0: bank evidence is immutable.")
                }
                val draft = draftsById.getValue(draftId)
                val expectedBody = if (draft.text("memoState", "pending_bank_drafts") ==
                    IntakeFieldState.PRESENT.name
                ) {
                    draft.stringOrNull("memo")
                } else {
                    null
                }
                if (body != expectedBody) {
                    invalid("$where.bodyText is not the parsed memo of its own draft.")
                }
                if (capturedAt != null && capturedAt != draft.long("postedAtEpochMillis", "pending_bank_drafts")) {
                    invalid("$where.capturedAtEpochMillis is not its draft's post time.")
                }
            }
        }
    }

    private fun validateEvents(events: List<Row>, captureIds: Set<Long>, transactionIds: Set<Long>) {
        val identities = HashSet<String>()
        for ((index, row) in events.withIndex()) {
            val where = "note_events[$index]"
            val captureId = row.long("captureId", where)
            requireReference(captureId, captureIds, "$where.captureId")
            val identity = row.text("eventIdentity", where)
            if (identity.isBlank()) invalid("$where.eventIdentity is blank.")
            if (!identities.add("$captureId\u001F$identity")) {
                invalid("$where.eventIdentity is duplicated inside its capture.")
            }
            val spanStart = row.long("spanStart", where)
            val spanEnd = row.long("spanEnd", where)
            if (spanStart < 0L) invalid("$where.spanStart is negative.")
            if (spanEnd <= spanStart) invalid("$where.spanEnd must be after spanStart.")
            row.longOrNull("amountVnd", where)?.let {
                if (it < 0L) invalid("$where.amountVnd is negative.")
            }
            requireDateText(row.stringOrNull("anchorDate"), "$where.anchorDate")
            requireTimeText(row.stringOrNull("anchorTime"), "$where.anchorTime")
            requireDateText(row.stringOrNull("intervalEndDate"), "$where.intervalEndDate")
            requireTimeText(row.stringOrNull("intervalEndTime"), "$where.intervalEndTime")
            if (row.stringOrNull("anchorTime") != null && row.stringOrNull("anchorDate") == null) {
                invalid("$where states a time without the date it belongs to.")
            }
            if (row.stringOrNull("intervalEndTime") != null && row.stringOrNull("intervalEndDate") == null) {
                invalid("$where states an interval end time without its end date.")
            }
            if (row.stringOrNull("intervalEndDate") != null && row.stringOrNull("anchorDate") == null) {
                invalid("$where states an interval end without the anchor it ends.")
            }
            if (row.text("extractionVersion", where).isBlank()) {
                invalid("$where.extractionVersion is blank.")
            }
            if (row.text("status", where) !in NOTE_EVENT_STATUSES) {
                invalid("$where.status is not a stored reading status.")
            }
            row.requireRevision("revision", where)
            row.requireRevision("sourceRevision", where)
            requireInstant(row.long("createdAtEpochMillis", where), "$where.createdAtEpochMillis")
            val resolvedTransactionId = row.longOrNull("resolvedTransactionId", where)
            val resolvedAt = row.longOrNull("resolvedAtEpochMillis", where)
            if (resolvedTransactionId != null) {
                requireReference(resolvedTransactionId, transactionIds, "$where.resolvedTransactionId")
            }
            if ((resolvedTransactionId == null) != (resolvedAt == null)) {
                invalid("$where must store its resolving record and its resolution instant together.")
            }
            if (resolvedAt != null) requireInstant(resolvedAt, "$where.resolvedAtEpochMillis")
            row.requireFlag("userEdited", where)
        }
    }

    private fun validateDrafts(
        drafts: List<Row>,
        categoryIds: Set<Long>,
        transactionIds: Set<Long>,
    ) {
        val ids = drafts.map { it.long("id", "pending_bank_drafts") }.toSet()
        val pairs = HashSet<String>()
        val confirmedBy = java.util.HashSet<Long>()
        val byId = drafts.associateBy { it.long("id", "pending_bank_drafts") }
        for ((index, row) in drafts.withIndex()) {
            val where = "pending_bank_drafts[$index]"
            if (row.text("deliveryId", where).isBlank()) invalid("$where.deliveryId is blank.")
            if (row.text("sourcePackage", where).isBlank()) invalid("$where.sourcePackage is blank.")
            requireInstant(row.long("postedAtEpochMillis", where), "$where.postedAtEpochMillis")
            if (row.text("bank", where).isBlank()) invalid("$where.bank is blank.")
            if (row.text("parseStatus", where) !in PARSE_STATUSES) {
                invalid("$where.parseStatus is not a stored parse state.")
            }
            row.requireFieldState("amountState", "amountVnd", "amountUnresolvedReason", where)
            row.longOrNull("amountVnd", where)?.let {
                if (it < 0L) invalid("$where.amountVnd is negative.")
            }
            if (row.text("direction", where) !in INTAKE_DIRECTIONS) {
                invalid("$where.direction is not a stored bank direction.")
            }
            row.requireFieldState("momentState", null, "momentUnresolvedReason", where)
            if (row.text("momentState", where) == IntakeFieldState.PRESENT.name &&
                row.stringOrNull("statedDate") == null && row.stringOrNull("statedTime") == null
            ) {
                invalid("$where states a present moment without a date or time.")
            }
            requireDateText(row.stringOrNull("statedDate"), "$where.statedDate")
            requireTimeText(row.stringOrNull("statedTime"), "$where.statedTime")
            if (row.stringOrNull("statedTime") != null && row.stringOrNull("statedDate") == null) {
                invalid("$where states a time without the date it belongs to.")
            }
            row.requireFieldState("memoState", "memo", "memoUnresolvedReason", where)
            val evidenceDigest = row.text("evidenceDigest", where)
            if (!EVIDENCE_DIGEST_PATTERN.matches(evidenceDigest)) {
                invalid("$where.evidenceDigest is not a canonical evidence digest.")
            }
            val expectedDigest = draftEvidenceDigest(
                bank = row.text("bank", where),
                parseStatus = IntakeParseStatus.valueOf(row.text("parseStatus", where)),
                amountState = IntakeFieldState.valueOf(row.text("amountState", where)),
                amountVnd = row.longOrNull("amountVnd", where),
                amountUnresolvedReason = row.stringOrNull("amountUnresolvedReason"),
                direction = IntakeDirection.valueOf(row.text("direction", where)),
                momentState = IntakeFieldState.valueOf(row.text("momentState", where)),
                statedDate = row.stringOrNull("statedDate"),
                statedTime = row.stringOrNull("statedTime"),
                momentUnresolvedReason = row.stringOrNull("momentUnresolvedReason"),
                memoState = IntakeFieldState.valueOf(row.text("memoState", where)),
                memo = row.stringOrNull("memo"),
                memoUnresolvedReason = row.stringOrNull("memoUnresolvedReason"),
            )
            if (evidenceDigest != expectedDigest) {
                invalid("$where.evidenceDigest does not describe this row's own evidence.")
            }
            if (!pairs.add("${row.text("deliveryId", where)}\u001F$evidenceDigest")) {
                invalid("$where repeats another draft's delivery and evidence.")
            }
            val reviewStatus = row.text("reviewStatus", where)
            if (reviewStatus !in REVIEW_STATUSES) {
                invalid("$where.reviewStatus is not a stored review state.")
            }
            val confirmedTransactionId = row.longOrNull("confirmedTransactionId", where)
            if (confirmedTransactionId != null) {
                requireReference(confirmedTransactionId, transactionIds, "$where.confirmedTransactionId")
                if (reviewStatus != IntakeReviewStatus.CONFIRMED.name) {
                    invalid("$where links a ledger record but is not confirmed.")
                }
                if (!confirmedBy.add(confirmedTransactionId)) {
                    invalid("$where links a ledger record another draft already owns.")
                }
            }
            val duplicateOf = row.longOrNull("possibleDuplicateOfDraftId", where)
            if (duplicateOf != null) {
                val id = row.long("id", where)
                requireReference(duplicateOf, ids, "$where.possibleDuplicateOfDraftId")
                if (duplicateOf == id) invalid("$where points at itself as its possible duplicate.")
                if (byId.getValue(duplicateOf).text("evidenceDigest", "pending_bank_drafts") != evidenceDigest) {
                    invalid("$where's possible duplicate states different evidence.")
                }
            }
            requireInstant(row.long("createdAtEpochMillis", where), "$where.createdAtEpochMillis")
            row.requireFlag("reviewMemoEdited", where)
            row.stringOrNull("reviewType")?.let {
                if (it !in TRANSACTION_TYPES) invalid("$where.reviewType is not a stored ledger type.")
            }
            row.longOrNull("reviewCategoryId", where)?.let {
                requireReference(it, categoryIds, "$where.reviewCategoryId")
            }
            row.requireFlag("reviewCategoryResolved", where)
            if (row.text("reviewCategoryOrigin", where) !in REVIEW_CATEGORY_ORIGINS) {
                invalid("$where.reviewCategoryOrigin is not a stored choice authority.")
            }
            row.requireRevision("reviewRevision", where)
        }
    }

    private fun validateLinks(links: List<Row>, eventIds: Set<Long>, draftIds: Set<Long>) {
        val pairs = HashSet<String>()
        val active = HashSet<Long>()
        for ((index, row) in links.withIndex()) {
            val where = "context_links[$index]"
            val eventId = row.long("eventId", where)
            val draftId = row.long("draftId", where)
            requireReference(eventId, eventIds, "$where.eventId")
            requireReference(draftId, draftIds, "$where.draftId")
            if (!pairs.add("$eventId\u001F$draftId")) {
                invalid("$where repeats another link's event and draft.")
            }
            val state = row.text("state", where)
            if (state !in CONTEXT_LINK_STATES) invalid("$where.state is not a stored link state.")
            val activeEventId = row.longOrNull("activeEventId", where)
            if (state == ContextLinkState.REJECTED.name) {
                if (activeEventId != null) invalid("$where is rejected but still holds its active event.")
            } else {
                if (activeEventId != eventId) {
                    invalid("$where's active event is not the event it links.")
                }
                if (!active.add(eventId)) {
                    invalid("$where gives one event a second active draft.")
                }
            }
            row.requireRevision("revision", where)
            requireInstant(row.long("createdAtEpochMillis", where), "$where.createdAtEpochMillis")
        }
    }

    private fun validateSnapshots(snapshots: List<Row>, draftIds: Set<Long>) {
        val drafts = HashSet<Long>()
        for ((index, row) in snapshots.withIndex()) {
            val where = "classification_snapshots[$index]"
            val draftId = row.long("draftId", where)
            requireReference(draftId, draftIds, "$where.draftId")
            if (!drafts.add(draftId)) invalid("$where is a second snapshot for one draft.")
            requireInstant(row.long("predictionTime", where), "$where.predictionTime")
            row.longOrNull("suggestedCategoryId", where)?.let {
                // Historical provenance: the suggested category a past prediction named may have
                // been deleted since, so it is deliberately not required to still exist.
                if (it <= 0L) invalid("$where.suggestedCategoryId is not a stored identity.")
            }
            val candidates = requireJsonNumberArray(row.text("candidateIds", where), "$where.candidateIds")
            val probabilities = requireJsonNumberArray(row.text("probabilities", where), "$where.probabilities")
            if (probabilities.size != candidates.size) {
                invalid("$where.probabilities do not line up with its candidates.")
            }
            requireJsonNumberArray(row.text("eventIds", where), "$where.eventIds")
            row.doubleOrNull("unknownShare", where)?.let {
                if (it < 0.0 || it > 1.0) invalid("$where.unknownShare is outside 0..1.")
            }
            row.doubleOrNull("confidence", where)?.let {
                if (it < 0.0 || it > 1.0) invalid("$where.confidence is outside 0..1.")
            }
            listOf(
                "contextRevision",
                "categoryRevision",
                "ruleRevision",
                "modelRevision",
                "candidateSetVersion",
                "decisionSource",
                "effectiveInputDigest",
                "classificationInputKind",
            ).forEach { name ->
                // classificationInputIdentity is deliberately absent: legacy rows store the
                // column's empty default, and a historical row is still valid provenance.
                if (row.text(name, where).isBlank()) invalid("$where.$name is blank.")
            }
        }
    }

    private fun buildHostedProfiles(profiles: JSONObject?): HostedProviderExportSnapshot {
        if (profiles == null) {
            return HostedProviderExportSnapshot(activeProfileId = null, profiles = emptyList())
        }
        val allowed = HOSTED_PROFILE_KEYS
        if (profiles.keys().asSequence().toSet() != setOf("active_profile_id", "profiles")) {
            invalid("hosted_profiles is not the documented descriptor set.")
        }
        val entries = profiles.optJSONArray("profiles")
            ?: invalid("hosted_profiles.profiles is not a list.")
        val ids = HashSet<String>()
        val descriptors = ArrayList<HostedProviderExportProfile>(entries.length())
        for (index in 0 until entries.length()) {
            val profile = entries.optJSONObject(index)
                ?: invalid("hosted_profiles.profiles[$index] is not a descriptor.")
            if (profile.keys().asSequence().toSet() != allowed) {
                invalid("hosted_profiles.profiles[$index] is not the documented descriptor set.")
            }
            val id = profile.stringValue("id", "hosted_profiles.profiles[$index]")
            if (id.isBlank() || !ids.add(id)) {
                invalid("hosted_profiles.profiles[$index].id is blank or duplicated.")
            }
            val name = profile.stringValue("name", "hosted_profiles.profiles[$index]")
            if (name.isBlank()) {
                invalid("hosted_profiles.profiles[$index].name is blank.")
            }
            val protocol = profile.stringValue("protocol", "hosted_profiles.profiles[$index]")
            if (protocol !in HOSTED_PROTOCOLS) {
                invalid("hosted_profiles.profiles[$index].protocol is not a stored protocol.")
            }
            val origin = profile.stringValue("endpoint_origin", "hosted_profiles.profiles[$index]")
            val sanitized = runCatching { sanitizedProviderEndpointOrigin(origin) }.getOrNull()
            if (sanitized == null || sanitized != origin) {
                invalid("hosted_profiles.profiles[$index].endpoint_origin carries more than a scheme, host, and port.")
            }
            val model = profile.stringValue("model", "hosted_profiles.profiles[$index]")
            if (model.isBlank()) {
                invalid("hosted_profiles.profiles[$index].model is blank.")
            }
            val outputTokens = profile.nullableLong("max_output_tokens", "hosted_profiles.profiles[$index]")
                ?.also {
                    if (it < 1L || it > Int.MAX_VALUE.toLong()) {
                        invalid("hosted_profiles.profiles[$index].max_output_tokens is not a usable limit.")
                    }
                }?.toInt()
            val reasoningEffort = profile.nullableString("reasoning_effort", "hosted_profiles.profiles[$index]")
                ?.also {
                    if (it !in HOSTED_REASONING_EFFORTS) {
                        invalid("hosted_profiles.profiles[$index].reasoning_effort is not a stored effort.")
                    }
                }
            val temperature = profile.nullableDouble("temperature", "hosted_profiles.profiles[$index]")
                ?.also {
                    if (it < 0.0 || it > 2.0) {
                        invalid("hosted_profiles.profiles[$index].temperature is outside 0..2.")
                    }
                }
            val parallelToolCalls = profile.nullableBoolean(
                "parallel_tool_calls",
                "hosted_profiles.profiles[$index]",
            )
            val capabilities = profile.optJSONObject("capabilities")
                ?: invalid("hosted_profiles.profiles[$index].capabilities is missing.")
            if (capabilities.keys().asSequence().toSet() != HOSTED_CAPABILITY_KEYS) {
                invalid("hosted_profiles.profiles[$index].capabilities is not the documented set.")
            }
            fun flag(name: String): Boolean {
                val value = capabilities.get(name)
                if (value !is Boolean) {
                    invalid("hosted_profiles.profiles[$index].capabilities.$name is not a flag.")
                }
                return value
            }
            val toolCalls = flag("tool_calls")
            val parallelCalls = flag("parallel_tool_calls")
            val reasoningBudget = flag("reasoning_budget")
            val temperatureSupported = flag("temperature")
            val limit = capabilities.nullableLong(
                "max_output_tokens_limit",
                "hosted_profiles.profiles[$index].capabilities",
            )?.also {
                if (it < 1L || it > Int.MAX_VALUE.toLong()) {
                    invalid("hosted_profiles.profiles[$index].capabilities.max_output_tokens_limit is not a usable limit.")
                }
            }?.toInt()
            descriptors.add(
                HostedProviderExportProfile(
                    id = id,
                    name = name,
                    protocol = HostedProtocol.valueOf(protocol),
                    endpointOrigin = origin,
                    model = model,
                    maxOutputTokens = outputTokens,
                    reasoningEffort = reasoningEffort?.let(HostedReasoningEffort::valueOf),
                    temperature = temperature,
                    parallelToolCalls = parallelToolCalls,
                    capabilities = HostedProviderCapabilities(
                        supportsToolCalls = toolCalls,
                        supportsParallelToolCalls = parallelCalls,
                        supportsReasoningBudget = reasoningBudget,
                        supportsTemperature = temperatureSupported,
                        maxOutputTokensLimit = limit,
                    ),
                ),
            )
        }
        val active = profiles.nullableString("active_profile_id", "hosted_profiles")
        if (active != null && active !in ids) {
            invalid("hosted_profiles.active_profile_id does not name one of its profiles.")
        }
        return HostedProviderExportSnapshot(activeProfileId = active, profiles = descriptors)
    }

    // --- Application --------------------------------------------------------

    private fun apply(parsed: ParsedState) {
        val sqlite = database.openHelper.writableDatabase
        for (table in DELETE_ORDER) {
            sqlite.execSQL("DELETE FROM `${table.tableName}`")
        }
        // Stored intentions belong to the replaced world: an old command key would answer a new
        // request from a stale outcome, and the start-up resume path would re-run old links.
        sqlite.execSQL("DELETE FROM `workflow_operations`")
        sqlite.execSQL("DELETE FROM `arrival_reconciliation_operations`")

        for (table in INSERT_ORDER) {
            val rows = parsed.rows.getValue(table.exportName)
            val columns = table.columns.joinToString(separator = ", ") { "`${it.name}`" }
            val placeholders = table.columns.joinToString(separator = ", ") { "?" }
            val sql = "INSERT INTO `${table.tableName}` ($columns) VALUES ($placeholders)"
            for (row in rows) {
                val args = Array<Any?>(table.columns.size) { index ->
                    val column = table.columns[index]
                    // Written after every draft exists, so a pointer to a later row id still
                    // imports without relying on insertion order.
                    if (table === DRAFT_SPEC && column.name == "possibleDuplicateOfDraftId") {
                        null
                    } else {
                        row[column.name]
                    }
                }
                sqlite.execSQL(sql, args)
            }
        }
        // The possible-duplicate pointer is written after every draft exists, so a backup whose
        // pointer names a later row still imports without relying on insertion order.
        for (row in parsed.rows.getValue(DRAFT_SPEC.exportName)) {
            val duplicateOf = row["possibleDuplicateOfDraftId"] as? Long ?: continue
            sqlite.execSQL(
                "UPDATE `pending_bank_drafts` SET `possibleDuplicateOfDraftId` = ? WHERE `id` = ?",
                arrayOf<Any?>(duplicateOf, row["id"]),
            )
        }
        // A restored pending draft whose category is archived cannot be confirmed with it, and a
        // hosted choice is only valid for the context it was made from. Reopen exactly those
        // choices with the app's own reopen shape and revision bump; a user's own choice is left
        // as stored, because archiving never withdraws the user's attribution.
        sqlite.execSQL(
            "UPDATE `pending_bank_drafts` SET " +
                "`reviewCategoryId` = NULL, " +
                "`reviewCategoryResolved` = 0, " +
                "`reviewCategoryOrigin` = 'NONE', " +
                "`reviewRevision` = `reviewRevision` + 1 " +
                "WHERE `reviewStatus` = 'PENDING' " +
                "AND `confirmedTransactionId` IS NULL " +
                "AND `reviewCategoryOrigin` = 'HOSTED' " +
                "AND `reviewCategoryId` IN (SELECT `id` FROM `categories` WHERE `archived` = 1)",
        )
    }

    /**
     * Writes the cutover's commit marker inside the replacement transaction.
     *
     * The operation journal is this app's durable record of an applied operation; the reserved
     * key namespace keeps it out of every caller's way, the table is excluded from the export,
     * and the marker is written in the same transaction as the rows, so its presence is proof
     * that the replacement committed.
     */
    private fun writeCommitMarker(digest: String, now: Long) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO `workflow_operations` (`idempotencyKey`, `scopeId`, `command`, " +
                "`payloadDigest`, `outcome`, `createdAtEpochMillis`) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                markerKey(digest),
                RESTORE_MARKER_SCOPE,
                RESTORE_MARKER_COMMAND,
                digest,
                "APPLIED",
                now,
            ),
        )
    }

    private fun hasCommitMarker(digest: String): Boolean =
        database.openHelper.writableDatabase.query(
            "SELECT 1 FROM `workflow_operations` WHERE `idempotencyKey` = ? LIMIT 1",
            arrayOf(markerKey(digest)),
        ).use { cursor -> cursor.moveToFirst() }

    private fun deleteCommitMarker(digest: String) {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM `workflow_operations` WHERE `idempotencyKey` = ?",
            arrayOf(markerKey(digest)),
        )
    }

    /** Removes markers left by a completed cutover whose cleanup was interrupted. */
    private fun removeRestoreMarkers(): Boolean {
        val sqlite = database.openHelper.writableDatabase
        val present = sqlite.query(
            "SELECT COUNT(*) FROM `workflow_operations` WHERE `scopeId` = ? AND `command` = ?",
            arrayOf(RESTORE_MARKER_SCOPE, RESTORE_MARKER_COMMAND),
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        if (present == 0) return false
        sqlite.execSQL(
            "DELETE FROM `workflow_operations` WHERE `scopeId` = ? AND `command` = ?",
            arrayOf(RESTORE_MARKER_SCOPE, RESTORE_MARKER_COMMAND),
        )
        return true
    }

    private fun markerKey(digest: String): String = "$RESTORE_MARKER_PREFIX$digest"

    /**
     * Exports the current state to app-private storage and proves it is restorable before the
     * live rows are touched.
     *
     * The copy is read back through the same validator the picked backup uses, so recovery is a
     * checked property: a copy this app could not restore again would abort the replacement
     * instead of silently leaving the user without a way back.
     */
    private fun writePreviousStateCopy(): RestoreRecoveryCopy {
        if (!recoveryDirectory.isDirectory && !recoveryDirectory.mkdirs()) {
            throw RestoreRejection(
                RestoreRejectionCode.INVALID,
                "The app could not create the directory for the previous-state copy; nothing was changed.",
            )
        }
        val file = File(
            recoveryDirectory,
            "$RECOVERY_COPY_PREFIX${System.currentTimeMillis()}.json",
        )
        try {
            FileOutputStream(file).use { stream ->
                exporter.writeTo(stream)
                stream.flush()
            }
            val copy = RestoreRecoveryCopy(
                file = file,
                createdAtEpochMillis = file.lastModified(),
                sizeBytes = file.length(),
            )
            if (copy.sizeBytes > maxBackupBytes) {
                file.delete()
                throw RestoreRejection(
                    RestoreRejectionCode.TOO_LARGE,
                    "The current state is too large to keep a recoverable copy of; nothing was changed.",
                    limitBytes = maxBackupBytes,
                )
            }
            preview(file.readBytes())
            return copy
        } catch (rejection: RestoreRejection) {
            file.delete()
            throw rejection
        } catch (failure: Exception) {
            file.delete()
            throw RestoreRejection(
                RestoreRejectionCode.INVALID,
                "The current state could not be copied before restoring; nothing was changed.",
                cause = failure,
            )
        }
    }

    private fun pruneRecoveryCopies() {
        runCatching {
            recoveryCopies().drop(RECOVERY_COPY_LIMIT).forEach { it.file.delete() }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }

    // --- Small helpers ------------------------------------------------------

    private fun collectIds(rows: List<Row>, table: String): Set<Long> {
        val ids = HashSet<Long>(rows.size)
        for ((index, row) in rows.withIndex()) {
            val where = "$table[$index]"
            val id = row.long("id", where)
            requirePositiveId(id, where)
            if (!ids.add(id)) invalid("$where.id $id is duplicated.")
        }
        return ids
    }

    private fun requirePositiveId(id: Long, where: String) {
        if (id <= 0L) invalid("$where.id is not a stored identity.")
    }

    private fun requireReference(id: Long, ids: Set<Long>, where: String) {
        if (id !in ids) invalid("$where names a record that this backup does not contain.")
    }

    private fun requireInstant(value: Long, where: String) {
        if (value <= 0L) invalid("$where is not a valid instant.")
    }

    private fun Row.requireRevision(name: String, where: String) {
        val value = long(name, where)
        if (value < 0L || value > Int.MAX_VALUE.toLong()) invalid("$where.$name is outside its range.")
    }

    private fun Row.requireFlag(name: String, where: String) {
        val value = long(name, where)
        if (value != 0L && value != 1L) invalid("$where.$name is not a stored flag.")
    }

    /**
     * Enforces the storage contract each parsed bank field keeps: a present field carries its
     * value and no reason, an unresolved field carries its reason and no value, and a missing
     * field carries neither.
     */
    private fun Row.requireFieldState(stateName: String, valueName: String?, reasonName: String, where: String) {
        val state = text(stateName, where)
        if (state !in FIELD_STATES) invalid("$where.$stateName is not a stored field state.")
        val value = valueName?.let { name -> stringOrNull(name) ?: longOrNull(name, where) }
        val reason = stringOrNull(reasonName)
        when (state) {
            IntakeFieldState.PRESENT.name -> {
                if (valueName != null && value == null) invalid("$where.$valueName is required when $stateName is PRESENT.")
                if (reason != null) invalid("$where.$reasonName is set on a present field.")
            }

            IntakeFieldState.UNRESOLVED.name -> {
                if (value != null) invalid("$where.$valueName must stay null when $stateName is UNRESOLVED.")
                if (reason.isNullOrBlank()) invalid("$where.$reasonName is required when $stateName is UNRESOLVED.")
            }

            else -> {
                if (value != null) invalid("$where.$valueName must stay null when $stateName is MISSING.")
                if (reason != null) invalid("$where.$reasonName is set on a missing field.")
            }
        }
    }

    private fun Row.long(name: String, where: String): Long =
        this[name] as? Long ?: invalid("$where.$name is not a whole number.")

    private fun Row.longOrNull(name: String, where: String): Long? = this[name] as? Long

    private fun Row.doubleOrNull(name: String, where: String): Double? = this[name] as? Double

    private fun Row.stringOrNull(name: String): String? = this[name] as? String

    private fun Row.text(name: String, where: String): String =
        this[name] as? String ?: invalid("$where.$name is not text.")

    private fun requireDateText(value: String?, where: String) {
        if (value != null && !DATE_PATTERN.matches(value)) invalid("$where is not a YYYY-MM-DD date.")
    }

    private fun requireTimeText(value: String?, where: String) {
        if (value != null && !TIME_PATTERN.matches(value)) invalid("$where is not an HH:mm time.")
    }

    private fun requireJsonNumberArray(value: String, where: String): List<Double> {
        val array = runCatching { JSONArray(value) }.getOrNull()
            ?: invalid("$where is not a stored JSON array.")
        return List(array.length()) { index ->
            when (val item = array.get(index)) {
                is Int -> item.toDouble()
                is Long -> item.toDouble()
                is Double -> item
                else -> invalid("$where[$index] is not a number.")
            }
        }
    }

    private fun bankEvidenceDraftId(identity: String): Long? {
        if (!identity.startsWith("BANK_DRAFT:")) return null
        return identity.removePrefix("BANK_DRAFT:").toLongOrNull()?.takeIf { it > 0L }
    }

    private fun captureKindMatchesSource(kind: String, sourceKind: String): Boolean = when (kind) {
        CaptureKind.BANK_EVIDENCE_REF.name -> sourceKind == CaptureSourceKind.BANK_NOTIFICATION.name
        CaptureKind.USER_NOTE.name -> sourceKind == CaptureSourceKind.USER_QUICK_CAPTURE.name
        CaptureKind.MANUAL_ANNOTATION.name -> sourceKind == CaptureSourceKind.USER_ANNOTATION.name ||
            sourceKind == CaptureSourceKind.LEGACY_LEDGER_DESCRIPTION.name
        else -> false
    }

    private fun normalizeKey(key: String): String =
        key.lowercase().filter { it.isLetterOrDigit() }

    private fun invalid(detail: String): Nothing = throw RestoreRejection(
        RestoreRejectionCode.INVALID,
        "$detail Nothing was changed.",
    )

    private fun JSONObject.stringOrNull(name: String): String? = nullableString(name, "document")

    private fun JSONObject.objectOrNull(name: String): JSONObject? = optJSONObject(name)

    private fun JSONObject.longOrNull(name: String): Long? = nullableLong(name, "document")

    private fun JSONObject.stringValue(name: String, where: String): String =
        nullableString(name, where) ?: invalid("$where.$name is required.")

    private fun JSONObject.nullableString(name: String, where: String): String? {
        if (!has(name)) invalid("$where.$name is missing.")
        val value = get(name)
        if (value == JSONObject.NULL) return null
        return value as? String ?: invalid("$where.$name must be text.")
    }

    private fun JSONObject.nullableLong(name: String, where: String): Long? {
        if (!has(name)) invalid("$where.$name is missing.")
        return when (val value = get(name)) {
            JSONObject.NULL -> null
            is Int -> value.toLong()
            is Long -> value
            else -> invalid("$where.$name must be a whole number.")
        }
    }

    private fun JSONObject.nullableDouble(name: String, where: String): Double? {
        if (!has(name)) invalid("$where.$name is missing.")
        return when (val value = get(name)) {
            JSONObject.NULL -> null
            is Double -> value
            is Float -> value.toDouble()
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            else -> invalid("$where.$name must be a number.")
        }
    }

    private fun JSONObject.nullableBoolean(name: String, where: String): Boolean? {
        if (!has(name)) invalid("$where.$name is missing.")
        return when (val value = get(name)) {
            JSONObject.NULL -> null
            is Boolean -> value
            else -> invalid("$where.$name must be a flag.")
        }
    }

    private data class ParsedState(
        val preview: RestorePreview,
        val rows: Map<String, List<Row>>,
        val hostedProfiles: HostedProviderExportSnapshot,
    )

    private class TableSpec(
        val exportName: String,
        val tableName: String,
        val columns: List<ColumnSpec>,
    )

    private class ColumnSpec(
        val name: String,
        val kind: ColumnKind,
        val nullable: Boolean,
    )

    private enum class ColumnKind { INTEGER, TEXT, REAL }

    private companion object {
        const val FORMAT = "aegis-f-complete-state"
        const val FORMAT_VERSION = 1L
        const val RESTORE_MARKER_PREFIX = "restore-commit:"
        const val RESTORE_MARKER_SCOPE = "restore"
        const val RESTORE_MARKER_COMMAND = "complete-state-restore"

        val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
        val TIME_PATTERN = Regex("""\d{2}:\d{2}""")
        val EVIDENCE_DIGEST_PATTERN = Regex("""[0-9a-f]{64}""")

        val TRANSACTION_TYPES = TransactionType.entries.map { it.name }.toSet()
        val CONFIRMATION_STATUSES = ConfirmationStatus.entries.map { it.name }.toSet()
        val RECORD_SOURCES = RecordSource.entries.map { it.name }.toSet()
        val INTAKE_DIRECTIONS = IntakeDirection.entries.map { it.name }.toSet()
        val FIELD_STATES = IntakeFieldState.entries.map { it.name }.toSet()
        val PARSE_STATUSES = IntakeParseStatus.entries.map { it.name }.toSet()
        val REVIEW_STATUSES = IntakeReviewStatus.entries.map { it.name }.toSet()
        val REVIEW_CATEGORY_ORIGINS = ReviewCategoryOrigin.entries.map { it.name }.toSet()
        val CAPTURE_KINDS = CaptureKind.entries.map { it.name }.toSet()
        val CAPTURE_SOURCE_KINDS = CaptureSourceKind.entries.map { it.name }.toSet()
        val NOTE_EVENT_STATUSES = NoteEventStatus.entries.map { it.name }.toSet()
        val CONTEXT_LINK_STATES = ContextLinkState.entries.map { it.name }.toSet()
        val HOSTED_PROTOCOLS = HostedProtocol.entries.map { it.name }.toSet()
        val HOSTED_REASONING_EFFORTS = HostedReasoningEffort.entries.map { it.name }.toSet()

        val FORBIDDEN_KEYS = setOf(
            "credential",
            "credentials",
            "apikey",
            "apitoken",
            "accesstoken",
            "refreshtoken",
            "authorization",
            "password",
            "secret",
            "clientsecret",
            "privatekey",
            "sessionkey",
            "cookie",
            "otp",
            "mapping",
            "mappings",
            "reversiblemapping",
            "cachehandle",
            "cachekey",
            "cache",
        )

        val HOSTED_PROFILE_KEYS = setOf(
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

        val HOSTED_CAPABILITY_KEYS = setOf(
            "tool_calls",
            "parallel_tool_calls",
            "reasoning_budget",
            "temperature",
            "max_output_tokens_limit",
        )

        private fun column(name: String, kind: ColumnKind, nullable: Boolean) =
            ColumnSpec(name, kind, nullable)

        val CATEGORY_SPEC = TableSpec(
            exportName = "categories",
            tableName = "categories",
            columns = listOf(
                column("id", ColumnKind.INTEGER, false),
                column("name", ColumnKind.TEXT, false),
                column("archived", ColumnKind.INTEGER, false),
                column("createdAtEpochMillis", ColumnKind.INTEGER, false),
                column("updatedAtEpochMillis", ColumnKind.INTEGER, false),
            ),
        )

        val RULE_SPEC = TableSpec(
            exportName = "remembered_category_rules",
            tableName = "remembered_category_rules",
            columns = listOf(
                column("patternKey", ColumnKind.TEXT, false),
                column("categoryId", ColumnKind.INTEGER, false),
            ),
        )

        val TRANSACTION_SPEC = TableSpec(
            exportName = "transactions",
            tableName = "transactions",
            columns = listOf(
                column("id", ColumnKind.INTEGER, false),
                column("type", ColumnKind.TEXT, false),
                column("amountVnd", ColumnKind.INTEGER, false),
                column("occurredAtEpochMillis", ColumnKind.INTEGER, false),
                column("description", ColumnKind.TEXT, false),
                column("categoryId", ColumnKind.INTEGER, true),
                column("confirmation", ColumnKind.TEXT, false),
                column("source", ColumnKind.TEXT, false),
                column("createdAtEpochMillis", ColumnKind.INTEGER, false),
                column("updatedAtEpochMillis", ColumnKind.INTEGER, false),
                column("revision", ColumnKind.INTEGER, false),
            ),
        )

        val DRAFT_SPEC = TableSpec(
            exportName = "pending_bank_drafts",
            tableName = "pending_bank_drafts",
            columns = listOf(
                column("id", ColumnKind.INTEGER, false),
                column("deliveryId", ColumnKind.TEXT, false),
                column("sourcePackage", ColumnKind.TEXT, false),
                column("notificationKey", ColumnKind.TEXT, true),
                column("postedAtEpochMillis", ColumnKind.INTEGER, false),
                column("bank", ColumnKind.TEXT, false),
                column("parseStatus", ColumnKind.TEXT, false),
                column("amountState", ColumnKind.TEXT, false),
                column("amountVnd", ColumnKind.INTEGER, true),
                column("amountUnresolvedReason", ColumnKind.TEXT, true),
                column("direction", ColumnKind.TEXT, false),
                column("momentState", ColumnKind.TEXT, false),
                column("statedDate", ColumnKind.TEXT, true),
                column("statedTime", ColumnKind.TEXT, true),
                column("momentUnresolvedReason", ColumnKind.TEXT, true),
                column("memoState", ColumnKind.TEXT, false),
                column("memo", ColumnKind.TEXT, true),
                column("memoUnresolvedReason", ColumnKind.TEXT, true),
                column("evidenceDigest", ColumnKind.TEXT, false),
                column("reviewStatus", ColumnKind.TEXT, false),
                column("confirmedTransactionId", ColumnKind.INTEGER, true),
                column("possibleDuplicateOfDraftId", ColumnKind.INTEGER, true),
                column("createdAtEpochMillis", ColumnKind.INTEGER, false),
                column("reviewAmountText", ColumnKind.TEXT, true),
                column("reviewDateText", ColumnKind.TEXT, true),
                column("reviewTimeText", ColumnKind.TEXT, true),
                column("reviewMemo", ColumnKind.TEXT, true),
                column("reviewMemoEdited", ColumnKind.INTEGER, false),
                column("reviewType", ColumnKind.TEXT, true),
                column("reviewCategoryId", ColumnKind.INTEGER, true),
                column("reviewCategoryResolved", ColumnKind.INTEGER, false),
                column("reviewCategoryOrigin", ColumnKind.TEXT, false),
                column("reviewRevision", ColumnKind.INTEGER, false),
            ),
        )

        val CAPTURE_SPEC = TableSpec(
            exportName = "capture_messages",
            tableName = "capture_messages",
            columns = listOf(
                column("id", ColumnKind.INTEGER, false),
                column("captureKind", ColumnKind.TEXT, false),
                column("sourceKind", ColumnKind.TEXT, false),
                column("sourceIdentity", ColumnKind.TEXT, false),
                column("bodyText", ColumnKind.TEXT, true),
                column("capturedAtEpochMillis", ColumnKind.INTEGER, true),
                column("captureTimezone", ColumnKind.TEXT, true),
                column("revision", ColumnKind.INTEGER, false),
                column("originRef", ColumnKind.TEXT, true),
                column("tombstone", ColumnKind.INTEGER, false),
            ),
        )

        val EVENT_SPEC = TableSpec(
            exportName = "note_events",
            tableName = "note_events",
            columns = listOf(
                column("id", ColumnKind.INTEGER, false),
                column("captureId", ColumnKind.INTEGER, false),
                column("eventIdentity", ColumnKind.TEXT, false),
                column("spanStart", ColumnKind.INTEGER, false),
                column("spanEnd", ColumnKind.INTEGER, false),
                column("normalizedText", ColumnKind.TEXT, true),
                column("amountVnd", ColumnKind.INTEGER, true),
                column("merchant", ColumnKind.TEXT, true),
                column("anchorDate", ColumnKind.TEXT, true),
                column("anchorTime", ColumnKind.TEXT, true),
                column("anchorTimezone", ColumnKind.TEXT, true),
                column("intervalEndDate", ColumnKind.TEXT, true),
                column("intervalEndTime", ColumnKind.TEXT, true),
                column("extractionVersion", ColumnKind.TEXT, false),
                column("unresolvedReason", ColumnKind.TEXT, true),
                column("status", ColumnKind.TEXT, false),
                column("revision", ColumnKind.INTEGER, false),
                column("sourceRevision", ColumnKind.INTEGER, false),
                column("createdAtEpochMillis", ColumnKind.INTEGER, false),
                column("resolvedTransactionId", ColumnKind.INTEGER, true),
                column("userEdited", ColumnKind.INTEGER, false),
                column("resolvedAtEpochMillis", ColumnKind.INTEGER, true),
            ),
        )

        val LINK_SPEC = TableSpec(
            exportName = "context_links",
            tableName = "context_links",
            columns = listOf(
                column("id", ColumnKind.INTEGER, false),
                column("eventId", ColumnKind.INTEGER, false),
                column("draftId", ColumnKind.INTEGER, false),
                column("state", ColumnKind.TEXT, false),
                column("evidence", ColumnKind.TEXT, true),
                column("revision", ColumnKind.INTEGER, false),
                column("userOverride", ColumnKind.TEXT, true),
                column("activeEventId", ColumnKind.INTEGER, true),
                column("createdAtEpochMillis", ColumnKind.INTEGER, false),
            ),
        )

        val SNAPSHOT_SPEC = TableSpec(
            exportName = "classification_snapshots",
            tableName = "classification_snapshots",
            columns = listOf(
                column("id", ColumnKind.INTEGER, false),
                column("draftId", ColumnKind.INTEGER, false),
                column("contextRevision", ColumnKind.TEXT, false),
                column("categoryRevision", ColumnKind.TEXT, false),
                column("ruleRevision", ColumnKind.TEXT, false),
                column("modelRevision", ColumnKind.TEXT, false),
                column("candidateSetVersion", ColumnKind.TEXT, false),
                column("decisionSource", ColumnKind.TEXT, false),
                column("suggestedCategoryId", ColumnKind.INTEGER, true),
                column("candidateIds", ColumnKind.TEXT, false),
                column("probabilities", ColumnKind.TEXT, false),
                column("unknownShare", ColumnKind.REAL, true),
                column("confidence", ColumnKind.REAL, true),
                column("effectiveInputDigest", ColumnKind.TEXT, false),
                column("classificationInputKind", ColumnKind.TEXT, false),
                column("classificationInputIdentity", ColumnKind.TEXT, false),
                column("translationSourceRevision", ColumnKind.TEXT, true),
                column("eventIds", ColumnKind.TEXT, false),
                column("predictionTime", ColumnKind.INTEGER, false),
                column("staleReason", ColumnKind.TEXT, true),
            ),
        )

        val TABLE_SPECS = listOf(
            CATEGORY_SPEC,
            RULE_SPEC,
            TRANSACTION_SPEC,
            DRAFT_SPEC,
            CAPTURE_SPEC,
            EVENT_SPEC,
            LINK_SPEC,
            SNAPSHOT_SPEC,
        )

        /** Children before the rows they reference, so no foreign key is left dangling. */
        val DELETE_ORDER = listOf(
            LINK_SPEC,
            SNAPSHOT_SPEC,
            EVENT_SPEC,
            DRAFT_SPEC,
            TRANSACTION_SPEC,
            RULE_SPEC,
            CATEGORY_SPEC,
            CAPTURE_SPEC,
        )

        /** Parents before the rows that reference them. */
        val INSERT_ORDER = listOf(
            CATEGORY_SPEC,
            RULE_SPEC,
            TRANSACTION_SPEC,
            CAPTURE_SPEC,
            EVENT_SPEC,
            DRAFT_SPEC,
            LINK_SPEC,
            SNAPSHOT_SPEC,
        )
    }
}

/** One decoded row, keyed by its stored column name. */
private typealias Row = Map<String, Any?>
