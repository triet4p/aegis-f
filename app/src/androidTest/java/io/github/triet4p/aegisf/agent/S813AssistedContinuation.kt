package io.github.triet4p.aegisf.agent

import java.io.File
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit TEST-ONLY assisted-evaluation protocol for the S8.13 qualification.
 *
 * The frozen fixture, rubric, per-case budgets, prompts, tools and scoring are unchanged. Only
 * the batch reaction after a first attempt changes: each slot's first attempt is measured and
 * persisted exactly once, then the device publishes the first record and waits for the host's
 * one decision. A slot the frozen rubric already accepts gets `pass_no_feedback` and is never
 * touched again. A failing slot gets exactly one host-authored user message; the device appends
 * it to the SAME provider conversation and continues the SAME run, with the same scope, vault,
 * reference registry, journal and current state. No fixture is reseeded, no observation is
 * replayed and no second message or further phase exists.
 *
 * The wait is an in-session pause, so the host must coordinate while the instrumentation process
 * stays alive: it polls the published `assisted/ready/<case>.json`, decides with the frozen
 * scorer, and writes `assisted/decision/<case>.json`. A missing decision inside the declared
 * timeout means "no assisted attempt"; it is never replaced by a fabricated message.
 *
 * The host-authored message is data authored outside the model. It is validated before use:
 * one message, bounded length, no opaque reference, no tool name, no masked-token delimiter and
 * no case identifier, plus an explicit author attestation for the prohibitions that prose alone
 * cannot prove. It is masked outbound like any user instruction and is never registered as
 * clarification evidence, so it cannot mint a typed value the user did not supply.
 */

internal const val S813_ASSISTED_CONTRACT = "aegis-s813-assisted-continuation/v1"
internal const val S813_ASSISTED_CONTRACT_ARG = "S813_ASSISTED_CONTRACT"
internal const val S813_ASSISTED_FEEDBACK_TIMEOUT_ARG = "S813_ASSISTED_FEEDBACK_TIMEOUT_MS"

internal const val S813_ASSISTED_DECISION_PASS = "pass_no_feedback"
internal const val S813_ASSISTED_DECISION_FEEDBACK = "fail_feedback"

internal const val S813_ASSISTED_STATUS_PASS = "pass_no_feedback"
internal const val S813_ASSISTED_STATUS_FEEDBACK = "feedback_applied"
internal const val S813_ASSISTED_STATUS_ABSENT = "absent_timeout"
internal const val S813_ASSISTED_STATUS_INVALID = "invalid_decision"
internal const val S813_ASSISTED_STATUS_UNAVAILABLE = "continuation_unavailable"

internal const val S813_ASSISTED_MAX_MESSAGE_CHARS = 2_000
internal const val S813_ASSISTED_MIN_TIMEOUT_MILLIS = 60_000L
internal const val S813_ASSISTED_MAX_TIMEOUT_MILLIS = 3_600_000L
internal const val S813_ASSISTED_DEFAULT_TIMEOUT_MILLIS = 1_800_000L
internal const val S813_ASSISTED_POLL_MILLIS = 500L

internal val S813_ASSISTED_TOOL_NAMES = setOf(
    "observe",
    "select_source",
    "list_drafts",
    "make_time_anchor",
    "record_event",
    "inspect_link",
    "propose_link",
    "attach_link",
    "classify_draft",
    "replay_operation",
    "ask_user",
)

private val S813_ASSISTED_AUTHOR = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val S813_ASSISTED_OPAQUE_REF = Regex("\\br_[0-9a-f]{20,}\\b")
private val S813_ASSISTED_ATTESTATION_KEYS = listOf(
    "no_hidden_expected_ids",
    "no_tool_sequence",
    "no_opaque_references",
    "no_fabricated_financial_authorization",
)
private val S813_ASSISTED_DECISION_KEYS = setOf(
    "contract",
    "attempt_id",
    "snapshot_id",
    "pass",
    "case_id",
    "first_record_sha256",
    "decision",
    "feedback",
)
private val S813_ASSISTED_FEEDBACK_KEYS = setOf(
    "message",
    "author_id",
    "author_role",
    "kind",
    "source_trace_sha256",
    "attestation",
)

/** One validated host-authored user message for a failed slot. */
internal data class S813AssistedFeedback(
    val message: String,
    val authorId: String,
    val authorRole: String,
    val kind: String,
    val sourceTraceSha256: String,
    val attestation: JSONObject,
) {
    fun json(): JSONObject = JSONObject()
        .put("message", message)
        .put("author_id", authorId)
        .put("author_role", authorRole)
        .put("kind", kind)
        .put("source_trace_sha256", sourceTraceSha256)
        .put("attestation", JSONObject(attestation.toString()))
}

/** One validated host decision for a slot: either accepted as-is or one feedback message. */
internal data class S813AssistedDecision(
    val kind: String,
    val feedback: S813AssistedFeedback?,
)

/**
 * The device-side hooks one finished first attempt exposes to the assisted step.
 *
 * [runFollowUp] is null when the run ended without an idle provider conversation (unanswered
 * tool calls or a terminal outcome); the slot is then recorded as continuation-unavailable and
 * keeps its first verdict. Otherwise it appends exactly one validated user message and returns
 * the complete second-phase case record built from the same run.
 */
internal class S813AssistedSlot(
    val followUpOffered: Boolean,
    val initialProviderExchanges: Int,
    val runFollowUp: (suspend (String) -> JSONObject)?,
)

/**
 * One case's first record plus the assisted hooks it exposes.
 *
 * The first attempt's Room database and Laya lifecycle stay owned by this execution until
 * [close], because the assisted step reads that same live state for its postconditions, masking
 * and Laya evidence after the host's decision. The batch closes it exactly once on every path;
 * a failure inside [runCase] itself closes it before the failure propagates.
 */
internal class S813CaseExecution(
    val record: JSONObject,
    val assisted: S813AssistedSlot?,
    private val release: (() -> Unit)? = null,
) : AutoCloseable {
    private var released = false

    override fun close() {
        if (released) return
        released = true
        release?.invoke()
    }
}

internal const val S813_ASSISTED_STATUS_STEP_ERROR = "assisted_step_error"

/**
 * TEST_ONLY lifecycle trace for the assisted resource-lifetime smoke.
 *
 * It stays inert until [enable] is called, so a hosted run records nothing extra. The trace
 * exists to prove one observable boundary: a case's Room database and Laya lifecycle stay open
 * through the in-session host pause and the assisted record that reads them, and are released
 * exactly once afterwards, before the next case starts.
 */
internal object S813CaseLifecycleProbe {
    private val events = mutableListOf<JSONObject>()
    private var enabled = false

    fun enable() {
        enabled = true
        events.clear()
    }

    fun record(caseId: String, event: String) {
        if (!enabled) return
        events += JSONObject()
            .put("sequence", events.size)
            .put("case_id", caseId)
            .put("event", event)
    }

    fun eventsFor(caseId: String): List<JSONObject> =
        events.filter { it.optString("case_id") == caseId }

    fun snapshot(): JSONArray =
        JSONArray().also { out -> events.forEach { out.put(JSONObject(it.toString())) } }
}

/** What the device learned when it waited for a slot decision. */
internal data class S813AssistedAwait(
    val status: String,
    val decision: S813AssistedDecision?,
    val detail: String?,
)

internal class S813AssistedProtocolException(val reason: String) : IllegalStateException(reason)

/**
 * Reads the assisted wait window. A malformed binding is a launch error; a bounded default keeps
 * a lost host from hanging the device forever.
 */
internal fun s813AssistedFeedbackTimeoutMillis(args: Map<String, String?>): Long {
    val raw = args[S813_ASSISTED_FEEDBACK_TIMEOUT_ARG]
    if (raw == null) return S813_ASSISTED_DEFAULT_TIMEOUT_MILLIS
    val parsed = raw.toLongOrNull() ?: throw S813AssistedProtocolException("timeout is not an integer")
    if (parsed < S813_ASSISTED_MIN_TIMEOUT_MILLIS || parsed > S813_ASSISTED_MAX_TIMEOUT_MILLIS) {
        throw S813AssistedProtocolException("timeout is outside the declared window")
    }
    return parsed
}

/**
 * Validates one host decision against the exact slot it belongs to.
 *
 * The decision must bind this attempt, snapshot, pass and case, must quote the exact first-record
 * digest the device published, and (for a failing slot) must carry exactly one message that
 * satisfies every mechanically checkable prohibition. Nothing here inspects model reasoning.
 */
internal fun s813AssistedDecisionFrom(
    raw: JSONObject,
    attemptId: String,
    snapshotId: String,
    pass: String,
    caseId: String,
    firstRecordSha256: String,
): S813AssistedDecision {
    val keys = raw.keys().asSequence().toSet()
    if (!S813_ASSISTED_DECISION_KEYS.containsAll(keys)) {
        throw S813AssistedProtocolException("decision carries undeclared fields")
    }
    if (raw.optString("contract") != S813_ASSISTED_CONTRACT) {
        throw S813AssistedProtocolException("decision contract mismatch")
    }
    if (raw.optString("attempt_id") != attemptId ||
        raw.optString("snapshot_id") != snapshotId ||
        raw.optString("pass") != pass ||
        raw.optString("case_id") != caseId
    ) {
        throw S813AssistedProtocolException("decision identity mismatch")
    }
    if (raw.optString("first_record_sha256") != firstRecordSha256) {
        throw S813AssistedProtocolException("decision does not bind the published first record")
    }
    return when (val decision = raw.optString("decision")) {
        S813_ASSISTED_DECISION_PASS -> {
            if (raw.has("feedback")) {
                throw S813AssistedProtocolException("an accepted slot cannot carry feedback")
            }
            S813AssistedDecision(kind = S813_ASSISTED_DECISION_PASS, feedback = null)
        }
        S813_ASSISTED_DECISION_FEEDBACK -> {
            val feedback = raw.optJSONObject("feedback")
                ?: throw S813AssistedProtocolException("a failing slot needs one feedback object")
            S813AssistedDecision(
                kind = S813_ASSISTED_DECISION_FEEDBACK,
                feedback = s813AssistedFeedbackFrom(feedback, caseId, firstRecordSha256),
            )
        }
        else -> throw S813AssistedProtocolException("unknown decision kind")
    }
}

internal fun s813AssistedFeedbackFrom(
    raw: JSONObject,
    caseId: String,
    firstRecordSha256: String,
): S813AssistedFeedback {
    val keys = raw.keys().asSequence().toSet()
    if (keys != S813_ASSISTED_FEEDBACK_KEYS) {
        throw S813AssistedProtocolException("feedback schema mismatch")
    }
    val message = raw.optString("message")
    if (message.isBlank() || message.length > S813_ASSISTED_MAX_MESSAGE_CHARS) {
        throw S813AssistedProtocolException("feedback message is blank or too long")
    }
    val authorId = raw.optString("author_id")
    val authorRole = raw.optString("author_role")
    if (!S813_ASSISTED_AUTHOR.matches(authorId) || !S813_ASSISTED_AUTHOR.matches(authorRole)) {
        throw S813AssistedProtocolException("feedback author identity is malformed")
    }
    val kind = raw.optString("kind")
    if (kind != "remaining_goal" && kind != "erroneous_claim") {
        throw S813AssistedProtocolException("feedback kind is not declared")
    }
    if (raw.optString("source_trace_sha256") != firstRecordSha256) {
        throw S813AssistedProtocolException("feedback does not bind the source trace")
    }
    val attestation = raw.optJSONObject("attestation")
        ?: throw S813AssistedProtocolException("feedback needs an author attestation")
    if (attestation.keys().asSequence().toSet() != S813_ASSISTED_ATTESTATION_KEYS.toSet()) {
        throw S813AssistedProtocolException("feedback attestation is incomplete")
    }
    if (S813_ASSISTED_ATTESTATION_KEYS.any { attestation.opt(it) != true }) {
        throw S813AssistedProtocolException("feedback attestation is not affirmative")
    }
    s813AssistedMessageCheck(message, caseId)
    return S813AssistedFeedback(
        message = message,
        authorId = authorId,
        authorRole = authorRole,
        kind = kind,
        sourceTraceSha256 = firstRecordSha256,
        attestation = JSONObject(attestation.toString()),
    )
}

/**
 * Rejects mechanically detectable protocol violations in the one user message.
 *
 * The message must read like a user's own words: it never names an internal tool, an opaque
 * reference, a masked-token delimiter or the case identity. Prose that hides an oracle answer is
 * not provable here, so the author attests to it and the verbatim message stays in the evidence.
 */
internal fun s813AssistedMessageCheck(message: String, caseId: String) {
    if (message.contains('⟦') || message.contains('⟧')) {
        throw S813AssistedProtocolException("feedback must not carry masked-token delimiters")
    }
    if (S813_ASSISTED_OPAQUE_REF.containsMatchIn(message)) {
        throw S813AssistedProtocolException("feedback must not carry an opaque reference")
    }
    if (message.contains(caseId)) {
        throw S813AssistedProtocolException("feedback must not carry the case identifier")
    }
    for (tool in S813_ASSISTED_TOOL_NAMES) {
        if (Regex("\\b${Regex.escape(tool)}\\b").containsMatchIn(message)) {
            throw S813AssistedProtocolException("feedback must not name a tool")
        }
    }
}

/**
 * The device half of the assisted handshake for one pass directory.
 *
 * Files are written atomically (temporary file plus rename) so the host never reads a partial
 * decision, and every path stays beneath the pass's own run directory.
 */
internal class S813AssistedCoordinator(
    private val runDir: File,
    val attemptId: String,
    val snapshotId: String,
    val pass: String,
    val timeoutMillis: Long,
) {
    private val assistedDir = File(runDir, "assisted")
    private val readyDir = File(assistedDir, "ready")
    private val decisionDir = File(assistedDir, "decision")

    /**
     * Persists the exact first record and publishes the slot as ready.
     *
     * The first record is already written to `<runDir>/<caseId>.json` before the pause, so a
     * lost host or a dead process still leaves the complete first rubric decision and raw trace.
     */
    fun publishReady(caseId: String, firstRecord: JSONObject): String {
        require(readyDir.isDirectory || readyDir.mkdirs()) { "could not create the assisted ready directory" }
        require(decisionDir.isDirectory || decisionDir.mkdirs()) { "could not create the assisted decision directory" }
        val text = firstRecord.toString(2)
        val digest = s813Sha256Hex(text)
        val ready = JSONObject()
            .put("contract", S813_ASSISTED_CONTRACT)
            .put("attempt_id", attemptId)
            .put("snapshot_id", snapshotId)
            .put("pass", pass)
            .put("case_id", caseId)
            .put("first_record_sha256", digest)
            .put("first_record_path", "$caseId.json")
            .put("first_attempt", firstRecord)
        writeAtomically(File(readyDir, "$caseId.json"), ready.toString(2))
        return digest
    }

    /**
     * Waits for the host decision for this slot.
     *
     * Returns `absent_timeout` when no decision appears, `invalid_decision` when one appears but
     * fails validation (the slot then keeps its first verdict), and the validated decision
     * otherwise. A file that is present but not yet complete keeps polling until the window ends.
     */
    suspend fun awaitDecision(caseId: String, firstRecordSha256: String): S813AssistedAwait {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastFailure: String? = null
        while (System.currentTimeMillis() < deadline) {
            val file = File(decisionDir, "$caseId.json")
            if (file.isFile) {
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                if (text != null) {
                    val parsed = runCatching { JSONObject(text) }.getOrNull()
                    if (parsed != null) {
                        val validated = runCatching {
                            s813AssistedDecisionFrom(
                                parsed,
                                attemptId = attemptId,
                                snapshotId = snapshotId,
                                pass = pass,
                                caseId = caseId,
                                firstRecordSha256 = firstRecordSha256,
                            )
                        }
                        val decision = validated.getOrNull()
                        if (decision != null) {
                            return when (decision.kind) {
                                S813_ASSISTED_DECISION_PASS -> S813AssistedAwait(
                                    status = S813_ASSISTED_STATUS_PASS,
                                    decision = decision,
                                    detail = null,
                                )
                                else -> S813AssistedAwait(
                                    status = S813_ASSISTED_STATUS_FEEDBACK,
                                    decision = decision,
                                    detail = null,
                                )
                            }
                        }
                        lastFailure = (validated.exceptionOrNull() as? S813AssistedProtocolException)?.reason
                            ?: "decision_rejected"
                    }
                }
            }
            delay(S813_ASSISTED_POLL_MILLIS)
        }
        return if (lastFailure == null) {
            S813AssistedAwait(status = S813_ASSISTED_STATUS_ABSENT, decision = null, detail = null)
        } else {
            S813AssistedAwait(
                status = S813_ASSISTED_STATUS_INVALID,
                decision = null,
                detail = lastFailure,
            )
        }
    }

    /** Persists the one assisted-phase record for a slot. Never overwrites another slot. */
    fun writeRecord(caseId: String, record: JSONObject) {
        require(assistedDir.isDirectory || assistedDir.mkdirs()) { "could not create the assisted record directory" }
        writeAtomically(File(assistedDir, "$caseId.json"), record.toString(2))
    }

    private fun writeAtomically(target: File, text: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(text, Charsets.UTF_8)
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "could not publish ${target.name}" }
    }
}
