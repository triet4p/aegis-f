package io.github.triet4p.aegisf.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit TEST-ONLY batch-continuation policy for the S8.13 exhaustive evaluation.
 *
 * The frozen fixture, expected rows, rubric verdicts, per-case turn/tool budgets and numeric gates
 * are unchanged. Only the *batch* reaction to a case-level failure changes: a rubric stop
 * (financially unsafe link/event, extra mutation, semantic failure, policy refusal, provider
 * protocol/tool error) is retained on its own case record and the next independent case still runs.
 * The batch stops only for a fatal condition that makes a valid, safe continuation impossible:
 * credential material reaching evidence, broken case isolation, unreachable infrastructure, or a
 * capture that cannot be written.
 *
 * The host declares its retained prefix explicitly. A fresh full batch has no retained prefix and
 * measures the whole frozen order three times; a continuation batch keeps an immutable prior prefix
 * whose pass-1 slots are never rerun. Both shapes share exactly this batch policy.
 *
 * Case isolation is structural, not a policy switch: every case builds its own in-memory Room
 * database, its own `SensitiveVault`/scope id and its own seeded fixture rows, so a failed case
 * cannot contaminate the next one.
 */

internal const val S813_EXHAUSTIVE_CONTRACT = "aegis-s813-exhaustive-continuation/v1"

/** The host's declaration for a fresh plan with no retained prefix (an empty `-e` value is lost). */
internal const val S813_EXHAUSTIVE_NO_RETAINED = "none"

internal const val S813_EXHAUSTIVE_DECISION_CONTINUE = "CONTINUE"
internal const val S813_EXHAUSTIVE_DECISION_STOP = "STOP"

internal val S813_FATAL_CONTINUATION_CLASSES = setOf(
    "credential_secrecy_breach",
    "isolation_failure",
    "unreachable_infrastructure",
    "capture_integrity_failure",
)

/** Raised only for a condition that makes continuing the remaining cases unsafe or meaningless. */
internal class S813FatalContinuationException(
    val fatalClass: String,
    cause: Throwable? = null,
) : IllegalStateException("fatal batch condition: $fatalClass", cause) {
    init {
        require(fatalClass in S813_FATAL_CONTINUATION_CLASSES) {
            "unknown fatal continuation class: $fatalClass"
        }
    }
}

/**
 * Pass 1 measures the frozen order after the host-declared retained prefix; passes 2 and 3 repeat
 * the whole frozen order. An empty prefix is the fresh full-batch shape. No other selection is
 * accepted.
 */
internal fun s813ExhaustivePassCaseIds(
    frozenOrder: List<String>,
    pass: String,
    retainedCaseIds: List<String>,
): List<String> {
    require(retainedCaseIds == frozenOrder.take(retainedCaseIds.size)) {
        "the retained prefix must be the leading frozen case order"
    }
    require(retainedCaseIds.size < frozenOrder.size) {
        "the exhaustive plan must measure at least one slot per repetition"
    }
    return when (pass) {
        "1" -> frozenOrder.drop(retainedCaseIds.size)
        "2", "3" -> frozenOrder
        else -> error("the exhaustive continuation requires repetition 1, 2, or 3")
    }
}

internal fun s813ExhaustiveCaseIdsSha256(caseIds: List<String>): String =
    s813Sha256Hex(JSONArray(caseIds).toString())

/** Digest of the exhaustive continuation identity; the host must supply exactly this value. */
internal fun s813ExhaustiveIdentityDigest(
    attemptId: String,
    batchId: String,
    snapshotId: String,
    pass: String,
    caseIds: List<String>,
    retainedCaseIds: List<String>,
    priorProvenanceSha256: String,
): String = s813Sha256Hex(
    listOf(
        "contract=$S813_EXHAUSTIVE_CONTRACT",
        "policy=batch_continuation_only",
        "attempt_id=$attemptId",
        "batch_id=$batchId",
        "snapshot_id=$snapshotId",
        "pass=$pass",
        "case_ids_sha256=${s813ExhaustiveCaseIdsSha256(caseIds)}",
        "case_count=${caseIds.size}",
        "retained_case_ids_sha256=${s813ExhaustiveCaseIdsSha256(retainedCaseIds)}",
        "retained_slot_count=${retainedCaseIds.size}",
        "prior_provenance_sha256=$priorProvenanceSha256",
    ).joinToString("\n"),
)

internal data class S813ExhaustiveContinuation(
    val caseIds: List<String>,
    val retainedCaseIds: List<String>,
    val identitySha256: String,
    val totalSlots: Int,
    val measuredSlots: Int,
)

/**
 * Reads and validates the exhaustive binding, re-deriving the slot plan from the frozen case order.
 * Returns null for the unchanged non-exhaustive launch shapes.
 */
internal fun s813ExhaustiveContinuation(
    args: Map<String, String?>,
    launch: S813QualificationLaunch,
    pass: String,
    frozenOrder: List<String>,
): S813ExhaustiveContinuation? {
    val contract = args["S813_EXHAUSTIVE_CONTRACT"] ?: return null
    require(contract == S813_EXHAUSTIVE_CONTRACT) {
        "unknown exhaustive continuation contract: $contract"
    }
    require(pass in setOf("1", "2", "3")) {
        "the exhaustive continuation is only defined for repetitions 1, 2, and 3"
    }
    val retainedRaw = args["S813_EXHAUSTIVE_RETAINED_CASE_IDS"]
        ?: error("the exhaustive contract must declare its retained prefix, none for a fresh batch")
    val retained = if (retainedRaw == S813_EXHAUSTIVE_NO_RETAINED) {
        emptyList()
    } else {
        retainedRaw.split(',').filter { it.isNotBlank() }
    }
    require(retained == frozenOrder.take(retained.size)) {
        "the retained exhaustive prefix is not the leading frozen case order"
    }
    require(retained.size < frozenOrder.size) {
        "the exhaustive plan must measure at least one slot per repetition"
    }
    val expected = s813ExhaustivePassCaseIds(frozenOrder, pass, retained)
    val declared = s813RequiredArgument(args, "S813_EXHAUSTIVE_CASE_IDS")
        .split(',')
        .filter { it.isNotBlank() }
    require(declared == expected) {
        "the host exhaustive selection does not match the frozen slot plan for pass-$pass"
    }
    val priorProvenance = s813RequiredArgument(args, "S813_EXHAUSTIVE_PRIOR_PROVENANCE_SHA256")
    require(priorProvenance.matches(Regex("[0-9a-f]{64}"))) {
        "the retained prior mechanism provenance digest is malformed"
    }
    val digest = s813ExhaustiveIdentityDigest(
        attemptId = launch.attemptId,
        batchId = launch.batchId,
        snapshotId = launch.snapshotId,
        pass = pass,
        caseIds = expected,
        retainedCaseIds = retained,
        priorProvenanceSha256 = priorProvenance,
    )
    require(s813RequiredArgument(args, "S813_EXHAUSTIVE_CASE_IDS_SHA256") ==
        s813ExhaustiveCaseIdsSha256(expected)) {
        "the host exhaustive case-order digest does not match the frozen selection"
    }
    require(s813RequiredArgument(args, "S813_EXHAUSTIVE_IDENTITY_SHA256") == digest) {
        "the host exhaustive identity digest does not match this launch"
    }
    return S813ExhaustiveContinuation(
        caseIds = expected,
        retainedCaseIds = retained,
        identitySha256 = digest,
        totalSlots = frozenOrder.size * 3,
        measuredSlots = (frozenOrder.size - retained.size) + frozenOrder.size * 2,
    )
}

/**
 * One case-level decision. A rubric verdict or provider/tool error keeps the case verdict exactly
 * as recorded and continues; only an explicitly classified fatal condition stops the batch.
 */
internal fun s813ContinuationDecision(
    caseId: String,
    result: JSONObject,
    adjudication: JSONObject?,
    fatalClass: String? = null,
): JSONObject {
    val caseSafetyStop = adjudication?.let { if (it.isNull("safety_stop")) null else it.getString("safety_stop") }
    val caseStatusStop = if (result.optString("attempt_status") != "hosted_run_recorded") {
        "case_execution_error"
    } else {
        null
    }
    val stop = fatalClass != null
    return JSONObject()
        .put("contract", S813_EXHAUSTIVE_CONTRACT)
        .put("policy", "batch_continuation_only")
        .put("case_id", caseId)
        .put("decision", if (stop) S813_EXHAUSTIVE_DECISION_STOP else S813_EXHAUSTIVE_DECISION_CONTINUE)
        .put("case_safety_stop", caseSafetyStop ?: JSONObject.NULL)
        .put("case_status_stop", caseStatusStop ?: JSONObject.NULL)
        .put("fatal_class", fatalClass ?: JSONObject.NULL)
        .put("reason", fatalClass ?: caseSafetyStop ?: caseStatusStop ?: JSONObject.NULL)
        .put("verdict_retained", true)
}

/** The live-run summary of the explicit continuation policy and the decisions actually taken. */
internal fun s813ExhaustiveReportBlock(
    continuation: S813ExhaustiveContinuation,
    pass: String,
    decisions: List<JSONObject>,
    stopReason: String?,
): JSONObject = JSONObject()
    .put("contract", S813_EXHAUSTIVE_CONTRACT)
    .put("policy", "batch_continuation_only")
    .put("pass", pass)
    .put("identity_sha256", continuation.identitySha256)
    .put("selected_case_ids", JSONArray(continuation.caseIds))
    .put("retained_case_ids", JSONArray(continuation.retainedCaseIds))
    .put("retained_slot_count", continuation.retainedCaseIds.size)
    .put("measured_slot_count", continuation.caseIds.size)
    .put("total_slots", continuation.totalSlots)
    .put("measured_slots_in_batch", continuation.measuredSlots)
    .put("case_decisions", JSONArray(decisions.toString()))
    .put("continued_after_case_failure", decisions.any {
        it.getString("decision") == S813_EXHAUSTIVE_DECISION_CONTINUE &&
            !it.isNull("case_safety_stop")
    })
    .put("stop_reason", stopReason ?: JSONObject.NULL)
