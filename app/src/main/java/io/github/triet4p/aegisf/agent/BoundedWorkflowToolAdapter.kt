package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.data.AttachLinkRequest
import io.github.triet4p.aegisf.data.CaptureEventQuery
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.ClassificationRequest
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.ProposeLinkRequest
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.classificationDescription
import io.github.triet4p.aegisf.privacy.InboundScan
import io.github.triet4p.aegisf.privacy.MaskedApplicationEffect
import io.github.triet4p.aegisf.privacy.MaskedInterpretation
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.privacy.TokenValidationException
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import io.github.triet4p.aegisf.provider.ProviderJson
import org.json.JSONArray
import org.json.JSONObject

/** Trusted state supplied by the host for one run; none of these values comes from model arguments. */
data class WorkflowToolSession(
    val scope: WorkflowCallerScope.AgentSession,
    val boundary: MaskedPromptBoundary,
    val operationKey: String,
    /** When true, mutations use the caller-supplied key verbatim (durable attempt replay). */
    val fixedOperationKey: Boolean = false,
)

/** Closed adapter for the five S8.4 typed tools, over the application's existing services. */
class BoundedWorkflowToolAdapter(
    private val commands: WorkflowCommandService,
    private val queries: WorkflowQueryService,
    private val session: WorkflowToolSession,
    private val declarations: List<HostedToolDeclaration>,
) {
    private val schemaByName = declarations.associateBy(HostedToolDeclaration::name)

    init {
        require(session.scope.scopeId.isNotBlank()) { "An agent session needs a trusted identity." }
        require(session.operationKey.isNotBlank() && session.operationKey.length <= 128 &&
            session.operationKey.none(Char::isISOControl)) { "A trusted operation identity is required." }
        require(declarations.map(HostedToolDeclaration::name).toSet() == ALLOWED_TOOLS) {
            "The canonical S8 tool declaration set is incomplete or contains extra tools."
        }
        require(session.boundary.vault.scopeTag == SensitiveVault.scopeTagOf(session.scope.scopeId)) {
            "The privacy boundary belongs to a different trusted session."
        }
    }

    /** An unknown, partial, malformed, or unauthorized call returns a bounded masked refusal. */
    suspend fun execute(name: String, arguments: JSONObject?): JSONObject {
        val declaration = schemaByName[name]
            ?: return refusal("unknown_tool")
        if (arguments == null) return refusal("malformed_arguments")
        if (session.boundary.scanInbound(arguments.toString()) is InboundScan.Refused) {
            return refusal("invalid_scoped_value")
        }
        if (ProviderJson.validateArguments(declaration.parameters, arguments) !is
            io.github.triet4p.aegisf.provider.ToolArgumentAssessment.Complete
        ) return refusal("schema_invalid")
        return try {
            if (session.fixedOperationKey && name != QUERY_DRAFT_CANDIDATES) {
                executeFixed(name, arguments)
            } else if (!session.fixedOperationKey && name != QUERY_DRAFT_CANDIDATES &&
                queries.workflowOperation(session.operationKey) != null
            ) {
                replayOrConflict(name, arguments)
            } else {
                when (name) {
                    QUERY_DRAFT_CANDIDATES -> queryDrafts(arguments.getJSONObject("query"))
                    UPDATE_EVENT_INTERPRETATION -> updateEvent(arguments)
                    PROPOSE_LINK -> proposeLink(arguments)
                    ATTACH_LINK -> attachLink(arguments)
                    REQUEST_LAYA_CLASSIFICATION -> classify(arguments)
                    else -> refusal("unknown_tool")
                }
            }
        } catch (_: TokenValidationException) {
            refusal("invalid_scoped_value")
        } catch (_: io.github.triet4p.aegisf.privacy.MaskingBlockedException) {
            refusal("privacy_boundary_refused")
        } catch (_: IllegalArgumentException) {
            refusal("invalid_arguments")
        }
    }

    /**
     * Durable resume/retry under a fixed attempt key: the command service replays the journaled
     * result for an identical repeat and surfaces a conflict only for a materially different
     * request. Reads still serve fresh state; no stale row authorizes an effect.
     */
    private suspend fun executeFixed(name: String, args: JSONObject): JSONObject {
        return when (name) {
            QUERY_DRAFT_CANDIDATES -> queryDrafts(args.getJSONObject("query"))
            UPDATE_EVENT_INTERPRETATION -> updateEvent(args)
            PROPOSE_LINK -> proposeLink(args)
            ATTACH_LINK -> attachLink(args)
            REQUEST_LAYA_CLASSIFICATION -> classify(args)
            else -> refusal("unknown_tool")
        }
    }

    private suspend fun replayOrConflict(name: String, args: JSONObject): JSONObject {
        val scope = session.scope
        val result = when (name) {
            UPDATE_EVENT_INTERPRETATION -> {
                val captureId = args.getLong("captureId")
                if (captureId !in scope.captureIds) return refusal("caller_scope")
                val capture = currentCapture(captureId)
                    ?: return refusal("replay_state_unavailable")
                val captureText = capture.bodyText ?: return refusal("replay_state_unavailable")
                val input = args.getJSONObject("interpretation")
                commands.updateEventInterpretation(
                    scope,
                    UpdateEventRequest(
                        idempotencyKey = session.operationKey,
                        captureId = captureId,
                        expectedCaptureRevision = args.getInt("expectedCaptureRevision"),
                        expectedEventRevision = if (args.isNull("expectedEventRevision")) {
                            null
                        } else {
                            args.getInt("expectedEventRevision")
                        },
                        extractionVersion = args.getString("extractionVersion"),
                        interpretation = checkedEventInterpretation(
                            captureId,
                            captureText,
                            capture.captureTimezone,
                            input,
                        ),
                    ),
                )
            }
            PROPOSE_LINK -> {
                val eventId = args.getLong("eventId")
                val draftId = args.getLong("draftId")
                MaskedApplicationEffect.validatedLinkPair(scope, eventId, draftId)
                commands.proposeLink(
                    scope,
                    ProposeLinkRequest(session.operationKey, eventId, draftId),
                )
            }
            ATTACH_LINK -> {
                val eventId = args.getLong("eventId")
                val draftId = args.getLong("draftId")
                MaskedApplicationEffect.validatedLinkPair(scope, eventId, draftId)
                if (nullableString(args, "userOverride") != null) {
                    return refusal("user_decision_required")
                }
                commands.attachLink(
                    scope,
                    AttachLinkRequest(
                        session.operationKey,
                        eventId,
                        draftId,
                        args.getInt("expectedRevision"),
                        null,
                    ),
                )
            }
            REQUEST_LAYA_CLASSIFICATION -> {
                val draftId = args.getLong("draftId")
                if (draftId !in scope.draftIds) return refusal("caller_scope")
                val description = session.boundary.vault.rehydrateText(args.getString("description"))
                commands.requestClassification(
                    scope,
                    ClassificationRequest(
                        session.operationKey,
                        draftId,
                        description,
                        if (args.isNull("expectedReviewRevision")) null
                        else args.getInt("expectedReviewRevision"),
                    ),
                )
            }
            else -> return refusal("unknown_tool")
        }
        return outcome(result)
    }


    /** Fresh, scoped masked context for a host turn; no cached rows or caller-supplied ids. */
    suspend fun observeCaptureContext(captureId: Long): JSONObject {
        if (captureId !in session.scope.captureIds) return refusal("caller_scope")
        val capture = currentCapture(captureId) ?: return refusal("not_found")
        val maskedCaptureText = capture.bodyText?.let(::mask)
        val events = queries.captureEvents(CaptureEventQuery(captureId, QueryPageRequest(500, 0)))
        val eventRows = events.items.map { event ->
            val scopedLinks = mutableListOf<JSONObject>()
            var linkOffset = 0
            var linkMore = true
            while (linkMore && scopedLinks.size <= 500) {
                val linkPage = queries.linkOperations(
                    LinkOperationQuery(
                        eventId = event.id,
                        bounds = QueryPageRequest(500, linkOffset),
                    ),
                )
                linkPage.items.asSequence()
                    .filter { it.draftId in session.scope.draftIds }
                    .forEach { link ->
                        scopedLinks += JSONObject()
                            .put("draftId", link.draftId)
                            .put("state", link.state.name)
                            .put("revision", link.revision)
                    }
                linkMore = linkPage.hasMore
                linkOffset = linkPage.nextOffset ?: break
            }
            val currentSourceSpan = capture.bodyText
                ?.takeIf { event.sourceRevision == capture.revision }
                ?.let { sourceText ->
                    try {
                        MaskedApplicationEffect.maskedOffsetsForSourceSpan(
                            session.scope,
                            session.boundary,
                            captureId,
                            sourceText,
                            event.spanStart,
                            event.spanEnd,
                        )
                    } catch (_: TokenValidationException) {
                        null
                    }
                }
            JSONObject()
                .put("eventId", event.id)
                .put("revision", event.revision)
                .put("sourceRevision", event.sourceRevision)
                .put("status", event.status.name)
                .put("spanStart", currentSourceSpan?.first ?: JSONObject.NULL)
                .put("spanEnd", currentSourceSpan?.second ?: JSONObject.NULL)
                .put("normalizedText", event.normalizedText?.let(::mask) ?: JSONObject.NULL)
                .put("amountVnd", event.amountVnd?.let { mask("$it VND") } ?: JSONObject.NULL)
                .put("merchant", event.merchant?.let(::mask) ?: JSONObject.NULL)
                .put("anchorDate", event.anchorDate?.let(::mask) ?: JSONObject.NULL)
                .put("anchorTime", event.anchorTime?.let(::mask) ?: JSONObject.NULL)
                .put("links", JSONArray(scopedLinks.take(500)))
                .put("hasMoreLinks", scopedLinks.size > 500)
        }
        val summary = queries.captureContextSummaries(listOf(captureId))[captureId]
        return maskResult(
            JSONObject()
                .put("captureId", capture.id)
                .put("captureRevision", capture.revision)
                .put("sourceText", maskedCaptureText ?: JSONObject.NULL)
                .put("events", JSONArray(eventRows))
                .put("hasMoreEvents", events.hasMore)
                .put(
                    "contextSummary",
                    JSONObject()
                        .put("eventCount", summary?.eventCount ?: 0)
                        .put("interpretedEventCount", summary?.interpretedEventCount ?: 0)
                        .put("unresolvedEventCount", summary?.unresolvedEventCount ?: 0)
                        .put("unmatchedEventCount", summary?.unmatchedEventCount ?: 0)
                        .put("resolvedEventCount", summary?.resolvedEventCount ?: 0)
                        .put("proposedLinkCount", summary?.proposedLinkCount ?: 0)
                        .put("acceptedLinkCount", summary?.acceptedLinkCount ?: 0)
                        .put("rejectedLinkCount", summary?.rejectedLinkCount ?: 0),
                ),
        )
    }

    private suspend fun queryDrafts(query: JSONObject): JSONObject {
        val review = when (val status = nullableString(query, "reviewStatus")) {
            null -> null
            "PENDING" -> IntakeReviewStatus.PENDING
            "CONFIRMED" -> IntakeReviewStatus.CONFIRMED
            "DISMISSED" -> IntakeReviewStatus.DISMISSED
            else -> return refusal("invalid_arguments")
        }
        val bounds = query.getJSONObject("bounds")
        val request = MaskedApplicationEffect.rehydrateDraftQuery(
            scope = session.scope,
            limit = bounds.getInt("limit"),
            offset = bounds.getInt("offset"),
            onlyPossibleDuplicates = query.getBoolean("onlyPossibleDuplicates"),
            reviewStatus = review,
        )
        if (session.scope.draftIds.isEmpty()) {
            return JSONObject()
                .put("items", JSONArray())
                .put("offset", request.bounds.offset)
                .put("limit", request.bounds.limit)
                .put("hasMore", false)
                .put("nextOffset", JSONObject.NULL)
        }
        val visible = mutableListOf<io.github.triet4p.aegisf.data.PendingBankDraftEntity>()
        var scanOffset = 0
        var scanMore = true
        while (scanMore && visible.size <= request.bounds.offset + request.bounds.limit) {
            val page = queries.draftCandidates(
                request.copy(bounds = QueryPageRequest(500, scanOffset)),
            )
            visible.addAll(page.items.filter { it.id in session.scope.draftIds })
            scanMore = page.hasMore
            scanOffset = page.nextOffset ?: break
        }
        val selected = visible.drop(request.bounds.offset).take(request.bounds.limit)
        val rows = selected.map { draft ->
            JSONObject()
                .put("draftId", draft.id)
                .put("reviewRevision", draft.reviewRevision)
                .put("reviewStatus", draft.reviewStatus.name)
                .put("bank", mask(draft.bank))
                .put("amountVnd", draft.amountVnd?.let { mask("$it VND") } ?: JSONObject.NULL)
                .put("statedDate", draft.statedDate?.let(::mask) ?: JSONObject.NULL)
                .put("statedTime", draft.statedTime?.let(::mask) ?: JSONObject.NULL)
                .put("memo", draft.memo?.let(::mask) ?: JSONObject.NULL)
                .put("description", mask(draft.classificationDescription()))
                .put(
                    "possibleDuplicateOfDraftId",
                    draft.possibleDuplicateOfDraftId?.takeIf { it in session.scope.draftIds }
                        ?: JSONObject.NULL,
                )
        }
        val nextOffset = (request.bounds.offset + selected.size).takeIf {
            visible.size > it || scanMore
        }
        return maskResult(
            JSONObject()
                .put("items", JSONArray(rows))
                .put("offset", request.bounds.offset)
                .put("limit", request.bounds.limit)
                .put("hasMore", nextOffset != null)
                .put("nextOffset", nextOffset ?: JSONObject.NULL),
        )
    }


    private suspend fun updateEvent(args: JSONObject): JSONObject {
        val captureId = args.getLong("captureId")
        if (captureId !in session.scope.captureIds) return refusal("caller_scope")
        val capture = currentCapture(captureId) ?: return refusal("not_found")
        val input = args.getJSONObject("interpretation")
        val captureText = capture.bodyText ?: return refusal("not_found")
        val interpretation = checkedEventInterpretation(
            captureId,
            captureText,
            capture.captureTimezone,
            input,
        )
        val claimedCaptureRevision = args.getInt("expectedCaptureRevision")
        val claimedEventRevision =
            if (args.isNull("expectedEventRevision")) null else args.getInt("expectedEventRevision")
        val result = commands.updateEventInterpretation(
            session.scope,
            UpdateEventRequest(
                idempotencyKey = session.operationKey,
                captureId = captureId,
                expectedCaptureRevision = claimedCaptureRevision,
                expectedEventRevision = claimedEventRevision,
                extractionVersion = args.getString("extractionVersion"),
                interpretation = interpretation,
            ),
        )
        return outcome(result)
    }

    private fun checkedEventInterpretation(
        captureId: Long,
        captureText: String,
        trustedTimezoneId: String?,
        input: JSONObject,
    ): NoteEventInterpretation {
        val maskedStart = input.getInt("spanStart")
        val maskedEnd = input.getInt("spanEnd")
        val maskedSpan = input.getString("maskedSpanText")
        val (sourceStart, sourceEnd) = MaskedApplicationEffect.sourceOffsetsForMaskedSpan(
            scope = session.scope,
            boundary = session.boundary,
            captureId = captureId,
            captureText = captureText,
            maskedStart = maskedStart,
            maskedEnd = maskedEnd,
            maskedSpanText = maskedSpan,
        )
        val anchor = input.optJSONObject("timeAnchor")
        val canonical = MaskedApplicationEffect.rehydrateInterpretation(
            scope = session.scope,
            boundary = session.boundary,
            captureId = captureId,
            captureText = captureText,
            spanStart = sourceStart,
            spanEnd = sourceEnd,
            maskedSpanText = maskedSpan,
            masked = MaskedInterpretation(
                normalizedText = input.getString("normalizedText"),
                amountToken = nullableString(input, "amountVnd"),
                dateToken = anchor?.let { nullableString(it, "date") },
                timeToken = anchor?.let { nullableString(it, "time") },
                spanSourceStart = sourceStart,
                spanSourceEnd = sourceEnd,
            ),
        )
        val intervalEndDate = anchor?.let { nullableString(it, "intervalEndDate") }
            ?.let(session.boundary.vault::rehydrateDate)
        val intervalEndTime = anchor?.let { nullableString(it, "intervalEndTime") }
            ?.let(session.boundary.vault::rehydrateTime)
        val canonicalAnchor = canonical.timeAnchor
        val timeAnchor =
            if (canonicalAnchor == null && intervalEndDate == null && intervalEndTime == null) {
                null
            } else {
                NoteEventTimeAnchor(
                    canonicalAnchor?.date,
                    canonicalAnchor?.time,
                    trustedTimezoneId,
                    intervalEndDate,
                    intervalEndTime,
                )
            }
        return canonical.copy(
            merchant = nullableString(input, "merchant")?.let(session.boundary.vault::rehydrateText),
            unresolvedReason = nullableString(input, "unresolvedReason")
                ?.let(session.boundary.vault::rehydrateText),
            timeAnchor = timeAnchor,
        )
    }

    private suspend fun proposeLink(args: JSONObject): JSONObject {
        val eventId = args.getLong("eventId")
        val draftId = args.getLong("draftId")
        MaskedApplicationEffect.validatedLinkPair(session.scope, eventId, draftId)
        if (!scopedEvent(eventId)) return refusal("caller_scope")
        val currentPair = queries.linkOperations(
            LinkOperationQuery(eventId = eventId, draftId = draftId, bounds = QueryPageRequest(1, 0)),
        ).items.firstOrNull()
        val result = commands.proposeLink(session.scope, ProposeLinkRequest(session.operationKey, eventId, draftId))
        return outcome(result, currentPair?.revision)
    }

    private suspend fun attachLink(args: JSONObject): JSONObject {
        val eventId = args.getLong("eventId")
        val draftId = args.getLong("draftId")
        MaskedApplicationEffect.validatedLinkPair(session.scope, eventId, draftId)
        if (!scopedEvent(eventId)) return refusal("caller_scope")
        val pair = queries.linkOperations(
            LinkOperationQuery(eventId = eventId, draftId = draftId, bounds = QueryPageRequest(1, 0)),
        ).items.firstOrNull() ?: return refusal("not_found")
        val claimedRevision = args.getInt("expectedRevision")
        val override = nullableString(args, "userOverride")
        if (override != null) return refusal("user_decision_required")
        val result = commands.attachLink(
            session.scope,
            AttachLinkRequest(session.operationKey, eventId, draftId, claimedRevision, null),
        )
        return outcome(result, pair.revision)
    }

    private suspend fun classify(args: JSONObject): JSONObject {
        val draftId = args.getLong("draftId")
        if (draftId !in session.scope.draftIds) return refusal("caller_scope")
        val draft = queries.draftCandidate(draftId) ?: return refusal("not_found")
        val supplied = args.getString("description")
        val actualDescription = draft.classificationDescription()
        if (session.boundary.vault.maskedPreview(supplied) !=
            session.boundary.vault.maskedPreview(actualDescription)
        ) return refusal("context_mismatch")
        val claimedRevision =
            if (args.isNull("expectedReviewRevision")) null else args.getInt("expectedReviewRevision")
        return outcome(
            commands.requestClassification(
                session.scope,
                ClassificationRequest(session.operationKey, draftId, actualDescription, claimedRevision),
            ),
        )
    }

    private suspend fun currentCapture(captureId: Long): CaptureMessageEntity? =
        queries.sourceExcerpt(captureId)

    private suspend fun scopedEvent(eventId: Long): Boolean {
        for (captureId in session.scope.captureIds) {
            var offset = 0
            while (true) {
                val page = queries.captureEvents(
                    CaptureEventQuery(captureId, QueryPageRequest(500, offset)),
                )
                if (page.items.any { it.id == eventId }) return true
                if (!page.hasMore) break
                offset = page.nextOffset ?: break
            }
        }
        return false
    }

    private fun outcome(result: WorkflowCommandOutcome, previousRevision: Int? = null): JSONObject {
        val status = when (result) {
            is WorkflowCommandOutcome.Applied -> "applied"
            is WorkflowCommandOutcome.NoChange -> "no_change"
            is WorkflowCommandOutcome.Conflict -> "conflict"
            is WorkflowCommandOutcome.Denied -> "denied"
            is WorkflowCommandOutcome.NeedsClarification -> "clarification_required"
            WorkflowCommandOutcome.NotFound -> "not_found"
            WorkflowCommandOutcome.Cancelled -> "cancelled"
            is WorkflowCommandOutcome.Failed -> "failed"
        }
        val out = JSONObject().put("status", status)
        val change = when (result) {
            is WorkflowCommandOutcome.Applied -> result.change
            is WorkflowCommandOutcome.NoChange -> result.change
            else -> null
        }
        change?.let { out.put("change", changeJson(it)) }
        when (result) {
            is WorkflowCommandOutcome.Conflict -> out.put("reason", "stale_or_conflicting_state")
            is WorkflowCommandOutcome.Denied -> out.put("reason", result.reason.name.lowercase())
            is WorkflowCommandOutcome.NeedsClarification -> {
                out.put("field", result.field)
                out.put("reason", mask(result.reason))
            }
            is WorkflowCommandOutcome.Failed -> out.put("reason", "operation_failed")
            else -> Unit
        }
        if (previousRevision != null) out.put("observedRevision", previousRevision)
        return maskResult(out)
    }

    private fun changeJson(change: WorkflowChange): JSONObject = when (change) {
        is WorkflowChange.EventStored -> JSONObject().put("kind", "event").put("eventId", change.eventId).put("revision", change.revision)
        is WorkflowChange.EventRevised -> JSONObject().put("kind", "event").put("eventId", change.eventId).put("revision", change.revision)
        is WorkflowChange.LinkDecided -> JSONObject().put("kind", "link").put("linkId", change.linkId).put("state", change.state.name).put("revision", change.revision)
        is WorkflowChange.Classified -> JSONObject().put("kind", "classification").put("draftId", change.draftId).put("snapshotId", change.snapshotId).put("source", change.source.name).put("categoryId", change.suggestedCategoryId ?: JSONObject.NULL).put("confidence", change.confidence ?: JSONObject.NULL)
        else -> JSONObject().put("kind", "unsupported_change")
    }

    private fun mask(text: String): String = session.boundary.maskOutbound(text, MaskedPath.TOOL_RESULT)

    private fun maskResult(result: JSONObject): JSONObject = try {
        JSONObject(session.boundary.maskToolResultJson(result.toString()))
    } catch (_: Exception) {
        refusal("masked_result_refused")
    }

    private fun refusal(code: String): JSONObject = JSONObject().put("status", "refused").put("reason", code)

    private fun nullableString(obj: JSONObject, key: String): String? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getString(key)

    companion object {
        const val QUERY_DRAFT_CANDIDATES = "query_draft_candidates"
        const val UPDATE_EVENT_INTERPRETATION = "update_event_interpretation"
        const val PROPOSE_LINK = "propose_link"
        const val ATTACH_LINK = "attach_link"
        const val REQUEST_LAYA_CLASSIFICATION = "request_laya_classification"
        private val ALLOWED_TOOLS = setOf(
            QUERY_DRAFT_CANDIDATES,
            UPDATE_EVENT_INTERPRETATION,
            PROPOSE_LINK,
            ATTACH_LINK,
            REQUEST_LAYA_CLASSIFICATION,
        )
    }
}

/** Reads the single canonical declaration file copied from tools/slm at build time. */
object S8AgentToolDeclarations {
    fun load(open: (String) -> java.io.InputStream): List<HostedToolDeclaration> {
        val root = JSONObject(open("s8_agent_tools_v2.json").bufferedReader().use { it.readText() })
        require(root.getString("schema") == "aegis-f-s8-agent-tools/v2")
        val tools = root.getJSONArray("tools")
        return (0 until tools.length()).map { index ->
            val tool = tools.getJSONObject(index)
            HostedToolDeclaration(
                name = tool.getString("name"),
                description = tool.getString("description"),
                parameters = tool.getJSONObject("parameters"),
            )
        }
    }
}
