package io.github.triet4p.aegisf.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The versioned Aegis agent interface: opaque observation references, one tool-result
 * envelope and the stable error vocabulary shared by the declarations, the bundled skills,
 * the runtime validators and the local result masking.
 *
 * A reference is an alias for a host observation, never a capability. Every handle is minted
 * locally, is unguessable, carries no scope/identity/authority text, and is resolved only by
 * lookup in the registry that issued it. Resolving a handle never widens scope and never
 * refreshes a stale binding: mutations compare the observed revisions at the application
 * boundary and refuse with [AgentErrorCode.STALE_OBSERVATION] instead of overwriting.
 */
object AgentInterface {
    /**
     * Envelope contract shared by every tool result.
     *
     * v4 replaced typed value-reference kinds with one neutral `value` kind: a masked value's
     * role comes from the tool argument the model passes it to, not from the token or alias.
     * v5 adds only a complete-current-context-bound optional English translation input for
     * local classification; arbitrary text replacement and category targeting remain absent.
     */
    const val CONTRACT: String = "aegis-agent-interface/5"

    /** Declaration schema identity; changing semantics changes the cache identity. */
    const val TOOL_SCHEMA: String = "aegis-f-s8-agent-tools/v5"

    /** Bundled skill schema identity. */
    const val SKILL_SCHEMA: String = "aegis-f-s8-agent-runtime-skills/v5"

    /** Version of the authored runtime examples bundled with the skills. */
    const val EXAMPLES_VERSION: String = "s8-agent-examples/4"

    /** Extraction provenance label the host records for model-derived readings. */
    const val EXTRACTION_VERSION: String = "agent-interface/4"

    /** The complete closed model-facing catalog; a run loads only the selected subset. */
    val TOOL_NAMES: Set<String> = setOf(
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
}

/**
 * What one host-issued handle refers to. The wire value is stated in field descriptions.
 *
 * A value handle is deliberately one neutral kind: it names a masked value the model saw, and
 * nothing about what that value means. The role comes from the tool argument the model passes
 * it to (`amount_ref`, `date_ref`, `time_ref`), and the runtime parses, validates and binds the
 * underlying value locally for that argument. There is no amount/date/time reference kind and
 * therefore no way for a spelling to pre-authorize a semantic field.
 */
enum class AgentReferenceKind(val wire: String) {
    CAPTURE("capture"),
    SOURCE("source"),
    VALUE("value"),
    DRAFT("draft"),
    ANCHOR("anchor"),
    CLASSIFICATION_CONTEXT("classification_context"),
    EVENT("event"),
    PAIR("pair"),
    PROPOSAL("proposal"),
    OPERATION("operation"),
    CURSOR("cursor"),
}

/** How a reference came to exist; clarification evidence is explicitly linked to its subject. */
enum class AgentReferenceOrigin { OBSERVATION, CLARIFICATION }

/**
 * One immutable local descriptor bound to a host-issued handle.
 *
 * Only the fields relevant to [kind] are populated. Everything here is trusted host state:
 * a model argument can name a handle but cannot supply any of these values.
 */
data class AgentReference(
    val handle: String,
    val kind: AgentReferenceKind,
    val scopeId: String,
    val subjectId: Long? = null,
    val captureId: Long? = null,
    val captureRevision: Int? = null,
    val eventId: Long? = null,
    /** Null with a capture binding means the selected span held no reading when observed. */
    val eventRevision: Int? = null,
    val draftId: Long? = null,
    val draftReviewRevision: Int? = null,
    /** Digest of the complete current Laya source context for a draft-bound translation. */
    val classificationSourceRevision: String? = null,
    val linkId: Long? = null,
    val linkRevision: Int? = null,
    /** The state of the observed event/draft link. */
    val linkState: String? = null,
    val spanStart: Int? = null,
    val spanEnd: Int? = null,
    val maskedStart: Int? = null,
    val maskedEnd: Int? = null,
    /** The complete masked typed token for a value reference. */
    val valueToken: String? = null,
    /** Masked display text for a source/anchor/clarification reference. */
    val display: String? = null,
    val origin: AgentReferenceOrigin = AgentReferenceOrigin.OBSERVATION,
    /** The binding key of the subject a clarification answer is linked to. */
    val linkedSubjectKey: String? = null,
    val dateToken: String? = null,
    val timeToken: String? = null,
    val endDateToken: String? = null,
    val endTimeToken: String? = null,
    val operationKey: String? = null,
    val operationCommand: String? = null,
    val operationDigest: String? = null,
    val cursorSignature: String? = null,
    val cursorOffset: Int? = null,
    /** The pair's stored eligibility when it was inspected. */
    val eligibility: String? = null,
) {
    /**
     * The occurrence binding a value participates in: the same source span, the same event,
     * or the clarification's own subject. Values from another occurrence never share a key.
     */
    fun bindingKey(): String = linkedSubjectKey ?: when {
        // A selected source occurrence keeps its capture/span identity even after a reading
        // appears there; the event id and revision are separate staleness bindings.
        kind == AgentReferenceKind.SOURCE && captureId != null && spanStart != null && spanEnd != null ->
            "source:$captureId:$spanStart:$spanEnd"
        eventId != null -> "event:$eventId"
        captureId != null && spanStart != null && spanEnd != null ->
            "source:$captureId:$spanStart:$spanEnd"
        captureId != null -> "capture:$captureId"
        draftId != null -> "draft:$draftId"
        linkId != null -> "pair:$linkId"
        else -> "subject:${subjectId ?: "none"}"
    }
}

/** A reference was not usable; converted into the shared envelope by [AgentToolRuntime]. */
class AgentToolException(
    val code: AgentErrorCode,
    val path: String? = null,
    val constraint: String? = null,
    val expected: JSONObject? = null,
    override val message: String,
    val subjectRef: String? = null,
    val retryableOverride: Boolean? = null,
    /**
     * What this failed call knows about the effect it may have had.
     *
     * `none` is a positive statement that the domain action was refused before a write.
     * A call that may have written and then lost the receipt says `unknown`, and the
     * envelope never lets a consumer read that as a no-write guarantee.
     */
    val effect: String = "none",
    /** Several independently known argument issues, when more than one applies. */
    val issues: List<AgentIssue> = emptyList(),
    /** Fresh masked observations carried back with a repairable state error. */
    val observations: List<JSONObject> = emptyList(),
) : Exception(message)

/** The stable error codes and their safe recovery guidance. */
enum class AgentErrorCode(
    val category: String,
    val defaultRetryable: Boolean,
    val nextAction: String,
    val terminal: Boolean = false,
) {
    SCHEMA_INVALID("arguments", true, "correct_arguments"),
    UNKNOWN_REFERENCE("reference", true, "observe_authorized_parent"),
    REFERENCE_KIND_MISMATCH("arguments", true, "correct_arguments"),
    REFERENCE_SCOPE_REFUSED("authority", false, "stop", terminal = true),
    REFERENCE_UNAVAILABLE("reference", false, "reconcile_or_stop", terminal = true),
    SUBJECT_UNAVAILABLE("state", true, "re_observe_or_stop"),
    STALE_OBSERVATION("state", true, "observe_and_reassess"),
    SOURCE_QUOTE_NOT_FOUND("arguments", true, "observe_and_reselect"),
    CURSOR_STALE("state", true, "restart_bounded_read"),
    TEMPORAL_CONSTRAINT("meaning", true, "correct_or_clarify"),
    MISSING_REQUIRED_MEANING("meaning", true, "correct_or_clarify"),
    LINK_INELIGIBLE("policy", false, "stop_pair_or_clarify"),
    USER_DECISION_REQUIRED("authority", false, "await_user_or_stop", terminal = true),
    MODEL_UNAVAILABLE("model", false, "manual_path"),
    INPUT_OVER_BUDGET("model", false, "manual_path"),
    CANCELLED("state", false, "stop", terminal = true),
    OPERATION_PENDING("state", false, "reconcile", terminal = true),
    EFFECT_UNKNOWN("state", false, "reconcile", terminal = true),
    EFFECT_NOT_VERIFIED("state", false, "reconcile_or_stop", terminal = true),
    MASKING_REFUSED("privacy", false, "stop", terminal = true),
    PROVIDER_TRANSIENT("provider", true, "host_retry", terminal = true),
    PROVIDER_TRUNCATED("provider", false, "stop", terminal = true),
}

/** One actionable field issue inside an error envelope; never carries a submitted value. */
data class AgentIssue(
    val path: String,
    val constraint: String,
    val expected: JSONObject?,
    val message: String,
) {
    fun json(): JSONObject = JSONObject()
        .put("path", path)
        .put("constraint", constraint)
        .put("expected", expected ?: JSONObject.NULL)
        .put("message", message)
}

/** The builder of the one result envelope every tool result uses. */
object AgentEnvelope {
    fun ok(
        tool: String,
        data: JSONObject?,
        observations: List<JSONObject> = emptyList(),
        effect: String = "none",
        verified: Boolean = false,
        operationRef: String? = null,
    ): JSONObject = base(tool)
        .put("outcome", "ok")
        .put("dispatch", "dispatched")
        .put("effect", effect)
        .put("verified", verified)
        .put("data", data ?: JSONObject.NULL)
        .put("operation_ref", operationRef ?: JSONObject.NULL)
        .put("observations", JSONArray(observations))
        .put("error", JSONObject.NULL)
        .put("control", JSONObject().put("action", "continue"))

    fun error(
        tool: String,
        code: AgentErrorCode,
        issues: List<AgentIssue>,
        dispatch: String,
        effect: String = "none",
        observations: List<JSONObject> = emptyList(),
        subjectRef: String? = null,
        allowedTools: List<String> = emptyList(),
        retryable: Boolean = code.defaultRetryable,
        detail: String? = null,
    ): JSONObject {
        val error = JSONObject()
            .put("code", code.name)
            .put("category", code.category)
            .put("issues", JSONArray(issues.map { it.json() }))
            .put("retryable", retryable)
            .put("next_action", code.nextAction)
            .put("subject_ref", subjectRef ?: JSONObject.NULL)
            .put("allowed_tools", JSONArray(allowedTools))
        if (!detail.isNullOrBlank()) error.put("detail", detail)
        return base(tool)
            .put("outcome", "error")
            .put("dispatch", dispatch)
            .put("effect", effect)
            .put("verified", false)
            .put("data", JSONObject.NULL)
            .put("operation_ref", JSONObject.NULL)
            .put("observations", JSONArray(observations))
            .put("error", error)
            .put(
                "control",
                JSONObject().put("action", if (code.terminal) "stop" else "continue"),
            )
    }

    fun clarifying(
        tool: String,
        subjectRef: String,
        field: String,
        question: String,
        observations: List<JSONObject> = emptyList(),
    ): JSONObject = base(tool)
        .put("outcome", "clarifying")
        .put("dispatch", "dispatched")
        .put("effect", "none")
        .put("verified", false)
        .put(
            "data",
            JSONObject()
                .put("field", field)
                .put("question", question)
                .put("subject_ref", subjectRef),
        )
        .put("operation_ref", JSONObject.NULL)
        .put("observations", JSONArray(observations))
        .put("error", JSONObject.NULL)
        .put("control", JSONObject().put("action", "await_user"))

    private fun base(tool: String): JSONObject = JSONObject()
        .put("contract", AgentInterface.CONTRACT)
        .put("tool", tool)
}

/** What a settled mutation claims, kept locally for postcondition re-reading. */
sealed interface AgentEffectClaim {
    data class EventReading(
        val eventId: Long,
        val captureId: Long,
        val revision: Int,
    ) : AgentEffectClaim

    data class LinkDecision(
        val eventId: Long,
        val draftId: Long,
        val linkId: Long,
        val state: String,
        val revision: Int,
    ) : AgentEffectClaim

    data class Classification(
        val draftId: Long,
        val source: String,
        val categoryId: Long?,
        val confidence: Double?,
        val reviewStatus: String,
        val reviewRevision: Int,
        val observedAtMillis: Long,
    ) : AgentEffectClaim

    /** A truthful classification attempt that produced no prediction (manual/unavailable). */
    data class ClassificationManual(
        val draftId: Long,
        val reasonCode: String,
        val reviewStatus: String,
        val reviewRevision: Int,
    ) : AgentEffectClaim

    /** The journal replayed a settled operation; [currentEffectMatches] is the live re-read. */
    data class Replay(
        val operationKey: String,
        val currentEffectMatches: Boolean,
    ) : AgentEffectClaim
}

/** One executed tool call: the model-facing envelope plus its local effect claim. */
data class AgentToolExecution(
    val envelope: JSONObject,
    val effectClaim: AgentEffectClaim? = null,
    val clarificationField: String? = null,
    val clarificationQuestion: String? = null,
    /**
     * What a read-only call actually observed about an absence, re-verified by the executor
     * before any no-effect settlement. Null when the call observed nothing about absence.
     */
    val noEffectClaim: AgentNoEffectClaim? = null,
) {
    val outcome: String get() = envelope.optString("outcome", "")
    val code: String? get() = envelope.optJSONObject("error")?.optString("code")
}

/**
 * A content-free claim that one read-only call observed an absence, kept only so the executor
 * can re-read live state before reporting a truthful no-effect completion.
 *
 * The claim carries host facts (identifiers, revisions, a bounded filter and the stored-evidence
 * eligibility) and never model text, a raw value or a token. It is not an authority by itself:
 * [AgentToolRuntime.verifyNoEffect] re-reads the application state behind every field.
 */
sealed interface AgentNoEffectClaim {
    /** One bounded draft page read under the declared filter over the trusted scope. */
    data class DraftScope(
        val reviewStatus: String?,
        val duplicatesOnly: Boolean,
        val draftIds: List<Long>,
        val scopeExhausted: Boolean,
    ) : AgentNoEffectClaim

    /** One inspected event/draft pair and the eligibility the stored evidence actually gave. */
    data class PairInspection(
        val eventId: Long,
        val draftId: Long,
        val eventRevision: Int,
        val eligibility: String,
    ) : AgentNoEffectClaim

    /**
     * One selected source that already carries an INTERPRETED reading at the observed revision.
     * The executor re-reads the event before settling, so an already-satisfied interpretation is
     * proved instead of being claimed or forced into a redundant write.
     */
    data class AlreadyInterpreted(
        val eventId: Long,
        val revision: Int,
    ) : AgentNoEffectClaim
}

/**
 * The bounded registry of one run's host-issued handles.
 *
 * Issuance is local and random; resolution is by lookup only. When the registry reaches its
 * bound the oldest handle is evicted, so an evicted alias is answered as unknown rather than
 * silently reissued to a different observation. Nothing here is persisted: after a restart
 * the host re-establishes observations and old aliases are not current.
 */
class AgentReferenceRegistry(
    private val scopeId: String,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val random: SecureRandom = SecureRandom(),
) {
    private val entries = LinkedHashMap<String, AgentReference>()

    init {
        require(scopeId.isNotBlank()) { "A reference registry needs a trusted session identity." }
        require(maxEntries in 16..4_096) { "The reference registry bound is not usable." }
    }

    fun issue(reference: AgentReference): String {
        require(reference.scopeId == scopeId) { "A reference belongs to another trusted session." }
        val handle = reference.handle
        require(handle.matches(HANDLE_PATTERN)) { "A host-issued handle is not well formed." }
        synchronized(entries) {
            entries[handle] = reference
            while (entries.size > maxEntries) {
                val eldest = entries.keys.firstOrNull() ?: break
                entries.remove(eldest)
            }
        }
        return handle
    }

    /** Mints an unguessable alias with no embedded kind, identity or authority. */
    fun nextHandle(): String {
        val bytes = ByteArray(HANDLE_BYTES)
        random.nextBytes(bytes)
        return "r_" + bytes.joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    fun resolve(handle: String?, expected: AgentReferenceKind, path: String): AgentReference {
        if (handle.isNullOrBlank()) {
            throw AgentToolException(
                code = AgentErrorCode.UNKNOWN_REFERENCE,
                path = path,
                constraint = "nonblank_reference",
                message = "Copy a reference issued in this run for ${expected.wire}.",
            )
        }
        val reference = synchronized(entries) { entries[handle] }
            ?: throw AgentToolException(
                code = AgentErrorCode.UNKNOWN_REFERENCE,
                path = path,
                constraint = "issued_in_this_run",
                message = "That reference was not issued in this run. Observe again instead of probing variants.",
            )
        if (reference.scopeId != scopeId) {
            throw AgentToolException(
                code = AgentErrorCode.REFERENCE_SCOPE_REFUSED,
                path = path,
                constraint = "same_trusted_session",
                message = "The reference belongs to another authorized session.",
            )
        }
        if (reference.kind != expected) {
            throw AgentToolException(
                code = AgentErrorCode.REFERENCE_KIND_MISMATCH,
                path = path,
                constraint = "reference_kind",
                expected = JSONObject().put("kind", expected.wire),
                message = "Choose a ${expected.wire} reference for this field.",
            )
        }
        return reference
    }

    fun resolveAny(
        handle: String?,
        expected: Set<AgentReferenceKind>,
        path: String,
    ): AgentReference {
        if (handle.isNullOrBlank()) {
            throw AgentToolException(
                code = AgentErrorCode.UNKNOWN_REFERENCE,
                path = path,
                constraint = "nonblank_reference",
                message = "Copy a reference issued in this run.",
            )
        }
        val reference = synchronized(entries) { entries[handle] }
            ?: throw AgentToolException(
                code = AgentErrorCode.UNKNOWN_REFERENCE,
                path = path,
                constraint = "issued_in_this_run",
                message = "That reference was not issued in this run. Observe again instead of probing variants.",
            )
        if (reference.kind !in expected) {
            throw AgentToolException(
                code = AgentErrorCode.REFERENCE_KIND_MISMATCH,
                path = path,
                constraint = "reference_kind",
                expected = JSONObject().put(
                    "kinds",
                    JSONArray(expected.map { it.wire }),
                ),
                message = "Choose one of the listed reference kinds for this field.",
            )
        }
        return reference
    }

    fun size(): Int = synchronized(entries) { entries.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 512
        private const val HANDLE_BYTES = 12
        private val HANDLE_PATTERN = Regex("^r_[0-9a-f]{24}$")
    }
}

/** One-way digest used for stable pagination signatures; never carries content. */
internal fun sha256Hex(material: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
