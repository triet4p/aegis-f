package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.data.CaptureEventQuery
import io.github.triet4p.aegisf.data.CaptureMessageEntity
import io.github.triet4p.aegisf.data.ContextLinkState
import io.github.triet4p.aegisf.data.DraftCandidateQuery
import io.github.triet4p.aegisf.data.DraftCandidateEvidenceState
import io.github.triet4p.aegisf.data.DraftCandidateMatch
import io.github.triet4p.aegisf.data.DraftCandidateMatchOutcome
import io.github.triet4p.aegisf.data.DraftCandidateMatchSet
import io.github.triet4p.aegisf.data.effectiveCandidateAmount
import io.github.triet4p.aegisf.data.effectiveCandidateDate
import io.github.triet4p.aegisf.data.effectiveCandidateDescription
import io.github.triet4p.aegisf.data.effectiveCandidateTime
import io.github.triet4p.aegisf.data.IntakeFieldState
import io.github.triet4p.aegisf.data.IntakeReviewStatus
import io.github.triet4p.aegisf.data.LinkEligibility
import io.github.triet4p.aegisf.data.LinkOperation
import io.github.triet4p.aegisf.data.LinkOperationQuery
import io.github.triet4p.aegisf.data.NoteEventEntity
import io.github.triet4p.aegisf.data.NoteEventInterpretation
import io.github.triet4p.aegisf.data.NoteEventStatus
import io.github.triet4p.aegisf.data.NoteEventTimeAnchor
import io.github.triet4p.aegisf.data.OPERATION_OUTCOME_PENDING
import io.github.triet4p.aegisf.data.PendingBankDraftEntity
import io.github.triet4p.aegisf.data.ProposeLinkRequest
import io.github.triet4p.aegisf.data.QueryPageRequest
import io.github.triet4p.aegisf.data.SelectDraftCategoryRequest
import io.github.triet4p.aegisf.data.UpdateEventRequest
import io.github.triet4p.aegisf.data.WorkflowCallerScope
import io.github.triet4p.aegisf.data.WorkflowChange
import io.github.triet4p.aegisf.data.WorkflowCommandOutcome
import io.github.triet4p.aegisf.data.WorkflowCommandService
import io.github.triet4p.aegisf.data.WorkflowDenialReason
import io.github.triet4p.aegisf.data.WorkflowOperation
import io.github.triet4p.aegisf.data.WorkflowOperationQuery
import io.github.triet4p.aegisf.data.WorkflowQueryService
import io.github.triet4p.aegisf.data.categoryCatalogRevision
import io.github.triet4p.aegisf.data.workingDescription
import io.github.triet4p.aegisf.privacy.InboundScan
import io.github.triet4p.aegisf.privacy.MaskedApplicationEffect
import io.github.triet4p.aegisf.privacy.MaskedPath
import io.github.triet4p.aegisf.privacy.MaskedPromptBoundary
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.privacy.TokenValidationException
import io.github.triet4p.aegisf.provider.HostedToolDeclaration
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/** Trusted host state of one run; none of it comes from model arguments. */
data class AgentToolSession(
    val scope: WorkflowCallerScope.AgentSession,
    val boundary: MaskedPromptBoundary,
    val registry: AgentReferenceRegistry,
    val clock: WorkflowRunClock = WorkflowRunClock { System.currentTimeMillis() },
)

/**
 * The one production implementation of the closed eleven-tool agent interface.
 *
 * Every tool returns the shared [AgentEnvelope] result and, for settled mutations, a local
 * [AgentEffectClaim] the executor re-reads before the run may claim completion. The runtime
 * owns reference issuance/resolution, source mapping, atomic event recording, trusted link
 * inspection, the ordinary draft-review update that stores the hosted workflow's category
 * choice, and journal replay. It never widens scope, invents a value, runs SQL/shell/filesystem
 * work or writes a ledger row.
 */
class AgentToolRuntime(
    private val commands: WorkflowCommandService,
    private val queries: WorkflowQueryService,
    private val session: AgentToolSession,
    private val declarations: List<HostedToolDeclaration>,
) {
    private val declarationByName = declarations.associateBy(HostedToolDeclaration::name)
    private val boundary = session.boundary
    private val scope = session.scope
    private val registry = session.registry

    init {
        require(scope.scopeId.isNotBlank()) { "An agent session needs a trusted identity." }
        require(boundary.vault.scopeTag == SensitiveVault.scopeTagOf(scope.scopeId)) {
            "The privacy boundary belongs to a different trusted session."
        }
        require(declarations.isNotEmpty() && declarations.all { it.name in AgentInterface.TOOL_NAMES }) {
            "The loaded declarations are not part of the closed agent interface."
        }
    }

    // ---- Entry point ---------------------------------------------------------------------

    /**
     * Executes one already-selected tool call. [operationKey] is the host-owned identity the
     * executor assigned to this exact call; a mutation never accepts one from the model.
     */
    suspend fun execute(
        name: String,
        arguments: JSONObject?,
        operationKey: String,
    ): AgentToolExecution {
        val declaration = declarationByName[name]
            ?: return AgentToolExecution(
                AgentEnvelope.error(
                    tool = name,
                    code = AgentErrorCode.SCHEMA_INVALID,
                    issues = listOf(
                        AgentIssue(
                            "$.tool",
                            "loaded_tool",
                            JSONObject().put("loaded", JSONArray(declarationByName.keys.toList())),
                            "Use a tool declared for this run.",
                        ),
                    ),
                    dispatch = "not_dispatched",
                    allowedTools = declarationByName.keys.toList(),
                ),
            )
        if (arguments == null) {
            return failed(
                name,
                AgentErrorCode.SCHEMA_INVALID,
                listOf(AgentIssue("$", "object", JSONObject().put("type", "object"), "Send one complete argument object.")),
            )
        }
        val issues = validateFlat(declaration.parameters, arguments)
        if (issues.isNotEmpty()) {
            return failed(name, AgentErrorCode.SCHEMA_INVALID, issues)
        }
        if (boundary.scanInbound(arguments.toString()) is InboundScan.Refused) {
            return AgentEnvelope.error(
                tool = name,
                code = AgentErrorCode.MASKING_REFUSED,
                issues = listOf(
                    AgentIssue(
                        "$",
                        "masked_values_only",
                        JSONObject().put("rule", "references_from_current_observations"),
                        "A submitted argument carried an unmasked or foreign value. Hosted work stops here.",
                    ),
                ),
                dispatch = "not_dispatched",
                effect = "none",
                subjectRef = null,
                allowedTools = emptyList(),
            ).let { AgentToolExecution(it) }
        }
        return try {
            when (name) {
                "observe" -> observe(arguments)
                "select_source" -> selectSource(arguments)
                "list_drafts" -> listDrafts(arguments)
                "make_time_anchor" -> makeTimeAnchor(arguments)
                "record_event" -> recordEvent(arguments, operationKey)
                "inspect_link" -> inspectLink(arguments)
                "propose_link" -> proposeLink(arguments, operationKey)
                "attach_link" -> attachLink(arguments, operationKey)
                "update_draft_review" -> updateDraftReview(arguments, operationKey)
                "replay_operation" -> replayOperation(arguments)
                "ask_user" -> askUser(arguments)
                else -> failed(name, AgentErrorCode.SCHEMA_INVALID, emptyList())
            }
        } catch (error: AgentToolException) {
            AgentToolExecution(
                envelope = AgentEnvelope.error(
                    tool = name,
                    code = error.code,
                    issues = error.issues.ifEmpty {
                        listOf(
                            AgentIssue(
                                error.path ?: "$",
                                error.constraint ?: error.code.name.lowercase(),
                                error.expected,
                                error.message,
                            ),
                        )
                    },
                    dispatch = "dispatched",
                    // These codes are raised only when a write may already have happened
                    // without a readable receipt or a settled effect. They are never
                    // reported as "none", so the model and the host cannot read a failed
                    // reconciliation as a no-write guarantee.
                    effect = when (error.code) {
                        AgentErrorCode.EFFECT_UNKNOWN,
                        AgentErrorCode.EFFECT_NOT_VERIFIED,
                        AgentErrorCode.OPERATION_PENDING,
                        -> "unknown"
                        else -> error.effect
                    },
                    observations = error.observations,
                    subjectRef = error.subjectRef,
                    allowedTools = repairableTools(name, error.code),
                    retryable = error.retryableOverride ?: error.code.defaultRetryable,
                ),
            )
        } catch (_: TokenValidationException) {
            failed(name, AgentErrorCode.REFERENCE_KIND_MISMATCH, emptyList())
        } catch (_: IllegalArgumentException) {
            failed(name, AgentErrorCode.SCHEMA_INVALID, emptyList())
        }
    }

    // ---- observe -------------------------------------------------------------------------

    private suspend fun observe(arguments: JSONObject): AgentToolExecution {
        val reference = registry.resolveAny(
            arguments.getString("ref"),
            setOf(
                AgentReferenceKind.CAPTURE,
                AgentReferenceKind.DRAFT,
                AgentReferenceKind.EVENT,
                AgentReferenceKind.PAIR,
                AgentReferenceKind.PROPOSAL,
                AgentReferenceKind.OPERATION,
            ),
            "$.ref",
        )
        val observation = when (reference.kind) {
            AgentReferenceKind.CAPTURE -> captureObservation(requireCapture(reference.captureId))
            AgentReferenceKind.DRAFT -> draftContextObservation(requireDraft(reference.draftId))
            AgentReferenceKind.EVENT -> eventObservation(requireEvent(reference.eventId))
            AgentReferenceKind.PAIR, AgentReferenceKind.PROPOSAL -> pairObservation(
                requireEvent(reference.eventId),
                requireDraft(reference.draftId),
            )
            AgentReferenceKind.OPERATION -> operationObservation(reference)
            else -> throw AgentToolException(
                AgentErrorCode.REFERENCE_KIND_MISMATCH,
                "$.ref",
                "observable_reference_kind",
                message = "Only capture, draft, event, pair or operation references are observable.",
            )
        }
        return AgentToolExecution(
            envelope = AgentEnvelope.ok(
                tool = "observe",
                data = JSONObject().put("observation", observation),
            ),
        )
    }

    // ---- select_source -------------------------------------------------------------------

    private suspend fun selectSource(arguments: JSONObject): AgentToolExecution {
        val captureRef = registry.resolve(arguments.getString("capture_ref"), AgentReferenceKind.CAPTURE, "$.capture_ref")
        val capture = requireCapture(captureRef.captureId)
        if (captureRef.captureRevision != null && captureRef.captureRevision != capture.revision) {
            val fresh = captureObservation(capture)
            throw AgentToolException(
                AgentErrorCode.STALE_OBSERVATION,
                "$.capture_ref",
                "observed_revision_matches_current",
                JSONObject().put("fresh_observation_required", true),
                "The selected capture changed. Inspect the fresh capture before choosing a span.",
                subjectRef = fresh.getString("ref"),
                observations = listOf(fresh),
            )
        }
        val sourceText = capture.bodyText ?: throw subjectUnavailable("$.capture_ref", "The capture holds no readable text.")
        val maskedSource = boundary.vault.maskedPreview(sourceText)
        val quote = arguments.getString("quote")
        if (quote.isBlank()) {
            throw AgentToolException(
                AgentErrorCode.SCHEMA_INVALID,
                "$.quote",
                "nonblank_quote",
                message = "Copy a nonblank exact quote from the observed masked source.",
            )
        }
        val occurrence = arguments.getInt("occurrence")
        val ranges = occurrences(maskedSource, quote)
        if (occurrence < 1 || occurrence > ranges.size) {
            throw AgentToolException(
                AgentErrorCode.SOURCE_QUOTE_NOT_FOUND,
                "$.quote",
                "exact_quote_occurrence",
                JSONObject().put("occurrences", ranges.size),
                "The quote does not occur that many times in the observed masked source.",
                subjectRef = captureObservation(capture).getString("ref"),
            )
        }
        val range = ranges[occurrence - 1]
        val maskedStart = range.first
        val maskedEnd = range.last + 1
        if (splitsToken(maskedSource, maskedStart, maskedEnd)) {
            throw AgentToolException(
                AgentErrorCode.SOURCE_QUOTE_NOT_FOUND,
                "$.quote",
                "token_boundary_preserved",
                message = "The quote splits a masked protected value. Select a complete span.",
            )
        }
        val (sourceStart, sourceEnd) = try {
            MaskedApplicationEffect.sourceOffsetsForMaskedSpan(
                scope = scope,
                boundary = boundary,
                captureId = capture.id,
                captureText = sourceText,
                maskedStart = maskedStart,
                maskedEnd = maskedEnd,
                maskedSpanText = quote,
            )
        } catch (_: TokenValidationException) {
            throw AgentToolException(
                AgentErrorCode.SOURCE_QUOTE_NOT_FOUND,
                "$.quote",
                "exact_masked_span",
                message = "The quote did not map to an exact current source span. Observe again and reselect.",
            )
        }
        val existing = findEventAt(capture.id, sourceStart, sourceEnd)
        val handle = registry.nextHandle()
        val sourceRef = registry.issue(
            AgentReference(
                handle = handle,
                kind = AgentReferenceKind.SOURCE,
                scopeId = scope.scopeId,
                subjectId = existing?.id,
                captureId = capture.id,
                captureRevision = capture.revision,
                eventId = existing?.id,
                eventRevision = existing?.revision,
                spanStart = sourceStart,
                spanEnd = sourceEnd,
                maskedStart = maskedStart,
                maskedEnd = maskedEnd,
                display = quote,
            ),
        )
        val values = valueRefsIn(
            captureId = capture.id,
            sourceKey = sourceRefKey(sourceStart, sourceEnd, capture.id),
            quote = quote,
            eventId = existing?.id,
        )
        val relativeDates = relativeDateRefs(
            captureId = capture.id,
            sourceKey = sourceRefKey(sourceStart, sourceEnd, capture.id),
            quote = quote,
            eventId = existing?.id,
        )
        val anchors = existing?.let { event -> anchorsFor(event, capture.id, sourceRefKey(sourceStart, sourceEnd, capture.id)) }.orEmpty()
        val eventObservation = existing?.let { eventObservation(it) }
        // Selecting an occurrence that already has a verified reading is truthful evidence that
        // INTERPRET_EVENTS is already satisfied; the executor re-reads the event before settling.
        val alreadyInterpreted = existing
            ?.takeIf { it.status == NoteEventStatus.INTERPRETED }
            ?.let { AgentNoEffectClaim.AlreadyInterpreted(it.id, it.revision) }
        val data = JSONObject()
            .put("source_ref", sourceRef)
            .put("quote", quote)
            .put("masked_start", maskedStart)
            .put("masked_end", maskedEnd)
            .put("capture_revision", capture.revision)
            .put("event", eventObservation ?: JSONObject.NULL)
            .put("values", JSONArray(values))
            .put("relative_dates", JSONArray(relativeDates))
            .put("anchors", JSONArray(anchors))
        return AgentToolExecution(
            envelope = AgentEnvelope.ok(
                tool = "select_source",
                data = data,
            ),
            noEffectClaim = alreadyInterpreted,
        )
    }

    // ---- list_drafts ---------------------------------------------------------------------

    private suspend fun listDrafts(arguments: JSONObject): AgentToolExecution {
        val reviewStatus = when (val raw = arguments.getString("review_status")) {
            "ALL" -> null
            "PENDING" -> IntakeReviewStatus.PENDING
            "CONFIRMED" -> IntakeReviewStatus.CONFIRMED
            "DISMISSED" -> IntakeReviewStatus.DISMISSED
            else -> throw AgentToolException(
                AgentErrorCode.SCHEMA_INVALID,
                "$.review_status",
                "enum",
                JSONObject().put("enum", JSONArray(listOf("PENDING", "CONFIRMED", "DISMISSED", "ALL"))),
                "Choose one declared review status; ALL disables only this filter.",
            )
        }
        val duplicatesOnly = arguments.getBoolean("duplicates_only")
        val eventReference = optionalReference(arguments, "event_ref", AgentReferenceKind.EVENT, "$.event_ref")
        val event = eventReference?.let { reference ->
            val current = requireEvent(reference.eventId)
            if (reference.eventRevision != null && reference.eventRevision != current.revision) {
                throw staleFrom("$.event_ref", requireCapture(current.captureId))
            }
            if (reviewStatus != IntakeReviewStatus.PENDING || duplicatesOnly) {
                throw AgentToolException(
                    AgentErrorCode.SCHEMA_INVALID,
                    "$.event_ref",
                    "pending_candidate_query",
                    JSONObject()
                        .put("review_status", IntakeReviewStatus.PENDING.name)
                        .put("duplicates_only", false),
                    "An event-bound candidate query always reads every eligible pending draft.",
                )
            }
            current
        }
        val cursorValue = optionalString(arguments, "cursor")
        val filter = DraftFilter(
            reviewStatus = reviewStatus,
            duplicatesOnly = duplicatesOnly,
            eventId = event?.id,
            eventRevision = event?.revision,
        )
        val scan = visibleDrafts(filter)
        val visible = scan.rows
        val signature = draftSignature(filter, visible, scan.matchSet)
        val startOffset = if (cursorValue == null) {
            0
        } else {
            val cursor = registry.resolve(cursorValue, AgentReferenceKind.CURSOR, "$.cursor")
            if (cursor.cursorSignature != signature) {
                val fresh = issueDraftCursor(signature, filter)
                throw AgentToolException(
                    AgentErrorCode.CURSOR_STALE,
                    "$.cursor",
                    "cursor_matches_observed_page",
                    JSONObject().put("fresh_cursor", fresh),
                    "The observed draft page changed. Restart the bounded read from the returned cursor.",
                )
            }
            cursor.cursorOffset ?: 0
        }
        val page = visible.drop(startOffset).take(DRAFT_PAGE_SIZE)
        val nextOffset = startOffset + page.size
        val more = nextOffset < visible.size
        val nextCursor = if (more) issueDraftCursor(signature, filter, startOffset = nextOffset) else null
        val matchRanks = scan.matchSet?.candidates
            ?.mapIndexed { index, match -> match.draft.id to (index + 1 to match) }
            ?.toMap()
            .orEmpty()
        val rows = page.map { draft ->
            draftObservation(draft).also { observation ->
                matchRanks[draft.id]?.let { (rank, match) ->
                    observation.put("match_evidence", candidateMatchObservation(match, rank))
                }
            }
        }
        val data = JSONObject()
            .put("items", JSONArray(rows))
            .put("page_size", DRAFT_PAGE_SIZE)
            .put("next_cursor", nextCursor ?: JSONObject.NULL)
            .put("scope_exhausted", !more && !scan.truncated)
        scan.matchSet?.let {
            data.put(
                "match",
                candidateMatchSetObservation(it, candidatePageComplete = !more),
            )
        }
        return AgentToolExecution(
            envelope = AgentEnvelope.ok(
                tool = "list_drafts",
                data = data,
            ),
            noEffectClaim = AgentNoEffectClaim.DraftScope(
                reviewStatus = reviewStatus?.name,
                duplicatesOnly = duplicatesOnly,
                draftIds = visible.map { it.id },
                scopeExhausted = !more && !scan.truncated,
                draftRevisionById = visible.associate { it.id to it.reviewRevision },
                eventId = event?.id,
                eventRevision = event?.revision,
            ),
        )
    }

    // ---- make_time_anchor ----------------------------------------------------------------

    private suspend fun makeTimeAnchor(arguments: JSONObject): AgentToolExecution {
        val date = registry.resolveAny(
            arguments.getString("date_ref"),
            setOf(AgentReferenceKind.VALUE, AgentReferenceKind.RELATIVE_DATE),
            "$.date_ref",
        )
        val time = optionalReference(arguments, "time_ref", AgentReferenceKind.VALUE, "$.time_ref")
        val endDate = optionalReference(arguments, "end_date_ref", AgentReferenceKind.VALUE, "$.end_date_ref")
        val endTime = optionalReference(arguments, "end_time_ref", AgentReferenceKind.VALUE, "$.end_time_ref")
        val all = listOfNotNull(date, time, endDate, endTime)
        if (all.any { !isGroundedValue(it) }) {
            AgentErrorCode.REFERENCE_KIND_MISMATCH.asIssue(
                "$.date_ref", "value_from_selected_occurrence",
                "Temporal components must come from the selected occurrence or its linked clarification, not from a bare capture page.",
            )
        }
        val captureIds = all.mapNotNull { it.captureId }.toSet()
        if (captureIds.size != 1) {
            AgentErrorCode.REFERENCE_KIND_MISMATCH.asIssue(
                "$","same_capture_binding",
                "The temporal components belong to different captures and cannot form one anchor.",
            )
        }
        // A day/month value that states no year resolves only against the trusted calendar year
        // of the capture it was read from; the app never borrows a year on its own. A day word
        // such as "yesterday" resolves only against that capture's own recorded local date and
        // zone, because nothing else says which day the note meant.
        val referenceCapture = captureIds.singleOrNull()
            ?.let { captureId -> runCatching { requireCapture(captureId) }.getOrNull() }
        if (!bindingCompatible(date, time) || !bindingCompatible(date, endDate) || !bindingCompatible(date, endTime)) {
            AgentErrorCode.REFERENCE_KIND_MISMATCH.asIssue(
                "$","same_occurrence_binding",
                "A temporal component came from another occurrence. Use components of the selected source or its clarification.",
            )
        }
        // The model chose these values for date/time arguments; the host parses each one locally
        // and refuses the argument when the chosen value is not the needed temporal value.
        val dateText = parseDateValue(date, referenceCapture, "$.date_ref")
        val timeText = time?.let { parseTimeValue(it, "$.time_ref") }
        val endDateText = endDate?.let { parseDateValue(it, referenceCapture, "$.end_date_ref") }
        val endTimeText = endTime?.let { parseTimeValue(it, "$.end_time_ref") }
        if (endDateText != null && endDateText < dateText) {
            AgentErrorCode.TEMPORAL_CONSTRAINT.asIssue(
                "$.end_date_ref","end_not_before_start",
                "An interval end cannot precede its start. Choose the grounded values, do not swap them.",
            )
        }
        if (endTimeText != null) {
            if (endDateText == null || timeText == null) {
                AgentErrorCode.TEMPORAL_CONSTRAINT.asIssue(
                    "$.end_time_ref","requires_end_date_and_start_time",
                    "An interval end time needs its grounded end date and start time. Ask if those facts are unavailable.",
                )
            }
            if (endDateText == dateText && endTimeText <= timeText) {
                AgentErrorCode.TEMPORAL_CONSTRAINT.asIssue(
                    "$.end_time_ref","end_after_start",
                    "The interval end must follow its start. Correct the grounded selection or ask.",
                )
            }
        }
        val key = date.bindingKey()
        val handle = registry.nextHandle()
        val anchorRef = registry.issue(
            AgentReference(
                handle = handle,
                kind = AgentReferenceKind.ANCHOR,
                scopeId = scope.scopeId,
                captureId = captureIds.single(),
                dateToken = date.valueToken,
                // A relative day word is evidence of one day, not a stored date value: the
                // offset travels with the anchor and is resolved again at the write.
                dateRelativeDayOffset = date.relativeDayOffset,
                timeToken = time?.valueToken,
                endDateToken = endDate?.valueToken,
                endTimeToken = endTime?.valueToken,
                linkedSubjectKey = key,
                display = buildString {
                    append(date.valueToken ?: date.display.orEmpty())
                    time?.valueToken?.let { append(' ').append(it) }
                    endDate?.valueToken?.let { append("..").append(it) }
                    endTime?.valueToken?.let { append(' ').append(it) }
                },
            ),
        )
        val data = JSONObject()
            .put("anchor_ref", anchorRef)
            .put("date", date.valueToken ?: date.display ?: JSONObject.NULL)
            .put("time", time?.valueToken ?: JSONObject.NULL)
            .put("end_date", endDate?.valueToken ?: JSONObject.NULL)
            .put("end_time", endTime?.valueToken ?: JSONObject.NULL)
            .put("date_only", time == null)
        return AgentToolExecution(
            envelope = AgentEnvelope.ok(tool = "make_time_anchor", data = data),
        )
    }

    /** The stored date the model selected for a temporal argument, or a repairable refusal. */
    private fun parseDateValue(
        reference: AgentReference,
        capture: CaptureMessageEntity?,
        path: String,
    ): String = when (reference.kind) {
        AgentReferenceKind.RELATIVE_DATE -> resolveRelativeDay(reference, capture, path)
        else -> try {
            boundary.vault.parseDate(requireNotNull(reference.valueToken), capture?.let(::captureReferenceYear))
        } catch (_: TokenValidationException) {
            throw AgentToolException(
                AgentErrorCode.TEMPORAL_CONSTRAINT,
                path,
                "selected_value_is_a_calendar_date",
                message = "The selected value is not a calendar date the host can read. " +
                    "Choose the grounded date value or ask.",
            )
        }
    }

    /**
     * The calendar date a selected relative-day alias names, resolved from the capture itself.
     *
     * The capture's recorded instant and zone are the only trusted reference for "yesterday";
     * when either is missing the alias stays unresolved and the user is asked for an absolute
     * date rather than the host borrowing the device's current day or another zone's.
     */
    private fun resolveRelativeDay(
        reference: AgentReference,
        capture: CaptureMessageEntity?,
        path: String,
    ): String {
        val offset = reference.relativeDayOffset
            ?: throw AgentToolException(
                AgentErrorCode.TEMPORAL_CONSTRAINT,
                path,
                "relative_day_alias_from_selected_occurrence",
                message = "That alias carries no day word. Select the occurrence again and use its relative date alias.",
            )
        return resolveRelativeDay(offset, capture, path)
    }

    private fun resolveRelativeDay(
        offset: Int,
        capture: CaptureMessageEntity?,
        path: String,
    ): String {
        val referenceDate = capture?.let(::captureReferenceDate)
            ?: throw AgentToolException(
                AgentErrorCode.TEMPORAL_CONSTRAINT,
                path,
                "capture_records_local_date_and_zone",
                message = "The capture records no local date and zone, so a relative day cannot be " +
                    "resolved. Ask for the absolute date instead of guessing one.",
            )
        return RelativeDayExpressions.resolve(referenceDate, offset)
            ?: throw AgentToolException(
                AgentErrorCode.TEMPORAL_CONSTRAINT,
                path,
                "relative_day_states_one_calendar_day",
                message = "The relative day could not be resolved to one calendar day. Ask for the absolute date.",
            )
    }

    /**
     * The stored date an assembled anchor names: either a masked value token or the day offset
     * of a relative day word, resolved against the same capture the anchor was bound to.
     */
    private fun resolveAnchorDate(
        anchor: AgentReference?,
        capture: CaptureMessageEntity,
        path: String,
    ): String? {
        anchor ?: return null
        anchor.dateToken?.let { token -> return rehydrateAnchorDate(token, captureReferenceYear(capture), path) }
        val offset = anchor.dateRelativeDayOffset ?: return null
        return resolveRelativeDay(offset, capture, path)
    }

    /** The wall-clock time the model selected for a temporal argument, or a repairable refusal. */
    private fun parseTimeValue(reference: AgentReference, path: String): String =
        try {
            boundary.vault.parseTime(requireNotNull(reference.valueToken))
        } catch (_: TokenValidationException) {
            throw AgentToolException(
                AgentErrorCode.TEMPORAL_CONSTRAINT,
                path,
                "selected_value_is_a_wall_clock_time",
                message = "The selected value is not a wall-clock time the host can read. " +
                    "Choose the grounded time value or ask.",
            )
        }

    // ---- record_event --------------------------------------------------------------------

    private suspend fun recordEvent(arguments: JSONObject, operationKey: String): AgentToolExecution {
        val source = registry.resolve(arguments.getString("source_ref"), AgentReferenceKind.SOURCE, "$.source_ref")
        val capture = requireCapture(source.captureId)
        val current = findEventAt(capture.id, source.spanStart!!, source.spanEnd!!)
        if (!bindingStillValid(source, current)) {
            val fresh = captureObservation(capture)
            throw AgentToolException(
                AgentErrorCode.STALE_OBSERVATION,
                "$.source_ref",
                "observed_event_presence_matches_current",
                JSONObject().put("fresh_observation_required", true),
                "A reading appeared or changed at that span. Inspect the fresh capture and reassess.",
                subjectRef = fresh.getString("ref"),
                observations = listOf(fresh),
            )
        }
        if (source.eventRevision == null) {
            // The selected span held no reading when it was observed. A different boundary
            // over an occurrence that already has a reading must not become a second one:
            // rewriting a reading's source boundaries is not supported by this interface.
            // The repository write transaction re-checks the same overlap atomically, so a
            // competing writer cannot slip between this observation and the insert; this
            // pre-check exists to return the specific repair guidance.
            val overlapping = findOverlappingEvent(capture.id, source.spanStart!!, source.spanEnd!!)
            if (overlapping != null) {
                val fresh = captureObservation(capture)
                throw AgentToolException(
                    AgentErrorCode.STALE_OBSERVATION,
                    "$.source_ref",
                    "selected_span_matches_existing_reading",
                    JSONObject().put("fresh_observation_required", true),
                    "The selected span overlaps an existing reading at different source boundaries. " +
                        "Select that reading's exact source and correct it, or ask; a changed selection " +
                        "cannot create a second reading.",
                    subjectRef = fresh.getString("ref"),
                    observations = listOf(fresh),
                )
            }
        }
        val summary = arguments.getString("summary").trim()
        if (summary.isEmpty()) {
            AgentErrorCode.MISSING_REQUIRED_MEANING.asIssue(
                "$.summary","nonblank_summary",
                "State the grounded normalized meaning of the selected occurrence.",
            )
        }
        val clarification = arguments.getString("clarification")
        val amount = optionalReference(arguments, "amount_ref", AgentReferenceKind.VALUE, "$.amount_ref")
        if (amount != null && !bindingCompatible(source, amount)) {
            AgentErrorCode.REFERENCE_KIND_MISMATCH.asIssue(
                "$.amount_ref","value_from_selected_occurrence",
                "Choose a value observed in this occurrence or its linked clarification.",
            )
        }
        val anchor = optionalReference(arguments, "anchor_ref", AgentReferenceKind.ANCHOR, "$.anchor_ref")
        if (anchor != null && !bindingCompatible(source, anchor)) {
            AgentErrorCode.REFERENCE_KIND_MISMATCH.asIssue(
                "$.anchor_ref","anchor_from_selected_occurrence",
                "Use an anchor built from this occurrence's observed date/time references.",
            )
        }
        val merchant = optionalReference(arguments, "merchant_ref", AgentReferenceKind.SOURCE, "$.merchant_ref")
        if (merchant != null && !merchantWithin(merchant, source)) {
            AgentErrorCode.REFERENCE_KIND_MISMATCH.asIssue(
                "$.merchant_ref","source_within_selected_source",
                "Choose a source handle for an exact merchant substring of this occurrence, or its clarification.",
            )
        }
        // Update semantics replace the selected interpretation, and a null is absence in
        // the new reading. A previously stored value is never discarded merely because the
        // optional argument was left null: that needs an explicit, grounded correction.
        val erasures = destructiveCorrectionIssues(current, amount, anchor, merchant)
        if (erasures.isNotEmpty()) {
            throw AgentToolException(
                AgentErrorCode.MISSING_REQUIRED_MEANING,
                constraint = "observed_reading_field_preserved_or_replaced",
                message = "This call would silently erase a stored field of the selected reading. " +
                    "Pass that field's observed alias to keep or replace it; removing a stored " +
                    "value needs an explicit user correction, not a null.",
                issues = erasures,
            )
        }
        if (amount == null && clarification != "amount") {
            AgentErrorCode.MISSING_REQUIRED_MEANING.asIssue(
                "$.clarification","missing_amount_requires_amount_clarification",
                "The amount is missing. Preserve a grounded partial reading and ask for its amount; do not infer it.",
                JSONObject().put("enum", JSONArray(listOf("amount"))),
            )
        }
        val amountValue = amount?.let {
            try {
                boundary.vault.parseAmount(requireNotNull(it.valueToken))
            } catch (_: TokenValidationException) {
                throw AgentToolException(
                    AgentErrorCode.MISSING_REQUIRED_MEANING,
                    "$.amount_ref",
                    "selected_value_is_a_whole_dong_amount",
                    message = "The value chosen for the amount is not a whole-dong number. " +
                        "Choose the amount value from this occurrence, or ask for the amount.",
                )
            }
        }
        val referenceYear = captureReferenceYear(capture)
        val anchorDate = resolveAnchorDate(anchor, capture, "$.anchor_ref")
        val anchorTime = anchor?.timeToken?.let { parseAnchorTime(it, "$.anchor_ref") }
        val intervalEndDate = anchor?.endDateToken?.let {
            rehydrateAnchorDate(it, referenceYear, "$.anchor_ref")
        }
        val intervalEndTime = anchor?.endTimeToken?.let { parseAnchorTime(it, "$.anchor_ref") }
        val merchantValue = merchant?.display?.let { boundary.vault.rehydrateText(it) }
        val interpretation = NoteEventInterpretation(
            spanStart = source.spanStart,
            spanEnd = source.spanEnd,
            normalizedText = boundary.vault.rehydrateText(summary),
            amountVnd = amountValue,
            merchant = merchantValue,
            timeAnchor = if (anchorDate == null && anchorTime == null && intervalEndDate == null && intervalEndTime == null) {
                null
            } else {
                NoteEventTimeAnchor(
                    date = anchorDate,
                    time = anchorTime,
                    timezoneId = capture.captureTimezone.takeIf { anchorTime != null },
                    intervalEndDate = intervalEndDate,
                    intervalEndTime = intervalEndTime,
                )
            },
            unresolvedReason = unresolvedReasonFor(clarification),
        )
        val result = commands.updateEventInterpretation(
            scope,
            UpdateEventRequest(
                idempotencyKey = operationKey,
                captureId = capture.id,
                expectedCaptureRevision = requireNotNull(source.captureRevision) {
                    "source reference has no observed capture revision"
                },
                expectedEventRevision = source.eventRevision,
                extractionVersion = AgentInterface.EXTRACTION_VERSION,
                interpretation = interpretation,
            ),
        )
        return settledEvent("record_event", operationKey, capture, result, clarification)
    }

    private suspend fun settledEvent(
        tool: String,
        operationKey: String,
        capture: CaptureMessageEntity,
        result: WorkflowCommandOutcome,
        clarification: String,
    ): AgentToolExecution = when (result) {
        is WorkflowCommandOutcome.Applied, is WorkflowCommandOutcome.NoChange -> {
            val change = (result as? WorkflowCommandOutcome.Applied)?.change
                ?: (result as WorkflowCommandOutcome.NoChange).change
            val eventId = (change as? WorkflowChange.EventStored)?.eventId
                ?: (change as? WorkflowChange.EventRevised)?.eventId
                ?: throw AgentToolException(
                    AgentErrorCode.EFFECT_UNKNOWN,
                    null,
                    "journaled_event_change",
                    message = "The application returned no event identity; reconcile before retrying.",
                )
            val row = queries.noteEvent(eventId) ?: throw AgentToolException(
                AgentErrorCode.EFFECT_NOT_VERIFIED,
                null,
                "event_readable_after_write",
                message = "The write was reported but the reading could not be re-read. Reconcile before retrying.",
            )
            if (row.captureId != capture.id) {
                throw AgentToolException(
                    AgentErrorCode.EFFECT_NOT_VERIFIED,
                    null,
                    "event_belongs_to_capture",
                    message = "The re-read reading does not belong to the selected capture.",
                )
            }
            val observation = eventObservation(row)
            val operationRef = issueOperationRef(operationKey, eventId = row.id, draftId = null)
            val verified = changeRevision(change) == null || changeRevision(change) == row.revision
            AgentToolExecution(
                envelope = AgentEnvelope.ok(
                    tool = tool,
                    data = JSONObject()
                        .put("event", observation)
                        .put("clarification", clarification)
                        .put("clarification_pending", clarification != "none")
                        .put("change", changeJson(change)),
                    observations = listOf(observation),
                    effect = if (result is WorkflowCommandOutcome.Applied) "applied" else "unchanged",
                    verified = verified,
                    operationRef = operationRef,
                ),
                effectClaim = AgentEffectClaim.EventReading(row.id, capture.id, row.revision),
            )
        }
        is WorkflowCommandOutcome.Conflict -> staleFrom(
            "$.source_ref",
            requireCapture(capture.id),
            constraint = "workflow_command_compare_and_set",
        )
        is WorkflowCommandOutcome.NeedsClarification -> AgentErrorCode.MISSING_REQUIRED_MEANING.asIssue(
            "$.${fieldPathFor(result.field)}",
            "domain_field_constraint",
            "The application needs a different grounded value for that field.",
            null,
            subjectRef = null,
        )
        is WorkflowCommandOutcome.Denied -> deny(null, result.reason)
        WorkflowCommandOutcome.NotFound -> subjectUnavailable("$.source_ref", "The selected source is gone.")
        WorkflowCommandOutcome.Cancelled -> throw AgentToolException(
            AgentErrorCode.CANCELLED, null, "request_not_cancelled",
            message = "The application cancelled the attempt before an effect.",
        )
        is WorkflowCommandOutcome.Failed -> throw AgentToolException(
            AgentErrorCode.EFFECT_UNKNOWN, null, "settled_effect_readable",
            message = "The application did not report a settled effect; reconcile before retrying.",
        )
    }

    // ---- inspect_link --------------------------------------------------------------------

    private suspend fun inspectLink(arguments: JSONObject): AgentToolExecution {
        val eventRef = registry.resolve(arguments.getString("event_ref"), AgentReferenceKind.EVENT, "$.event_ref")
        val draftRef = registry.resolve(arguments.getString("draft_ref"), AgentReferenceKind.DRAFT, "$.draft_ref")
        val event = requireEvent(eventRef.eventId)
        val draft = requireDraft(draftRef.draftId)
        if (eventRef.eventRevision != null && eventRef.eventRevision != event.revision) {
            throw staleFrom("$.event_ref", requireCapture(event.captureId))
        }
        if (draftRef.draftReviewRevision != null && draftRef.draftReviewRevision != draft.reviewRevision) {
            throw staleDraft("$.draft_ref", draft)
        }
        val inspection = commands.inspectLinkEvidence(scope, event.id, draft.id)
            ?: subjectUnavailable("$.draft_ref", "The chosen pair is not readable in this scope.")
        val pair = pairObservation(event, draft)
        return AgentToolExecution(
            envelope = AgentEnvelope.ok(
                tool = "inspect_link",
                data = JSONObject()
                    .put("pair_ref", pair.getString("ref"))
                    .put("eligibility", inspection.eligibility.name.lowercase())
                    .put("reason", inspection.reason ?: JSONObject.NULL)
                    .put("evidence", JSONObject()
                        .put("event_amount", maskAmount(inspection.eventAmountVnd))
                        .put("draft_amount", maskAmount(inspection.draftAmountVnd))
                        .put("event_date", maskDate(inspection.eventAnchorDate))
                        .put("draft_date", maskDate(inspection.draftStatedDate))
                        .put("candidate_draft_count", inspection.candidateDraftCount)
                        .put("duplicate_partner_count", inspection.duplicatePartnerCount)
                    ),
                observations = listOf(pair),
            ),
            noEffectClaim = AgentNoEffectClaim.PairInspection(
                eventId = event.id,
                draftId = draft.id,
                eventRevision = event.revision,
                eligibility = inspection.eligibility.name,
            ),
        )
    }

    // ---- propose_link --------------------------------------------------------------------

    private suspend fun proposeLink(arguments: JSONObject, operationKey: String): AgentToolExecution {
        val pairRef = registry.resolve(arguments.getString("pair_ref"), AgentReferenceKind.PAIR, "$.pair_ref")
        val event = requireEvent(pairRef.eventId)
        val draft = requireDraft(pairRef.draftId)
        if (pairRef.eventRevision != null && pairRef.eventRevision != event.revision) {
            throw staleFrom("$.pair_ref", requireCapture(event.captureId))
        }
        if (pairRef.draftReviewRevision != null && pairRef.draftReviewRevision != draft.reviewRevision) {
            throw staleDraft("$.pair_ref", draft)
        }
        val inspection = commands.inspectLinkEvidence(scope, event.id, draft.id)
            ?: subjectUnavailable("$.pair_ref", "The chosen pair is not readable in this scope.")
        if (pairRef.eligibility != null && pairRef.eligibility != inspection.eligibility.name) {
            val fresh = pairObservation(event, draft)
            throw AgentToolException(
                AgentErrorCode.STALE_OBSERVATION,
                "$.pair_ref",
                "observed_evidence_matches_current",
                JSONObject().put("fresh_observation_required", true),
                "The stored evidence changed since the inspection. Inspect the fresh pair and reassess.",
                subjectRef = fresh.getString("ref"),
                observations = listOf(fresh),
            )
        }
        val result = commands.proposeLink(scope, ProposeLinkRequest(operationKey, event.id, draft.id))
        return settledLink("propose_link", operationKey, event, draft, result)
    }

    // ---- attach_link ---------------------------------------------------------------------

    private suspend fun attachLink(arguments: JSONObject, operationKey: String): AgentToolExecution {
        val proposal = registry.resolve(
            arguments.getString("proposal_ref"),
            AgentReferenceKind.PROPOSAL,
            "$.proposal_ref",
        )
        val event = requireEvent(proposal.eventId)
        val draft = requireDraft(proposal.draftId)
        val link = queries.linkOperation(proposal.linkId!!)
            ?: throw staleDraft("$.proposal_ref", draft)
        if (link.state == ContextLinkState.ACCEPTED) {
            val pair = pairObservation(event, draft)
            return AgentToolExecution(
                envelope = AgentEnvelope.ok(
                    tool = "attach_link",
                    data = JSONObject()
                        .put("proposal_ref", pair.getString("ref"))
                        .put("state", link.state.name)
                        .put("already_attached", true),
                    observations = listOf(pair),
                    effect = "unchanged",
                    verified = true,
                ),
                effectClaim = AgentEffectClaim.LinkDecision(
                    event.id, draft.id, link.linkId, link.state.name, link.revision,
                ),
            )
        }
        if (proposal.linkRevision == null || proposal.linkRevision != link.revision) {
            val fresh = pairObservation(event, draft)
            throw AgentToolException(
                AgentErrorCode.STALE_OBSERVATION,
                "$.proposal_ref",
                "observed_link_revision_matches_current",
                JSONObject().put("fresh_observation_required", true),
                "The proposal changed. Inspect the fresh proposal before attaching.",
                subjectRef = fresh.getString("ref"),
                observations = listOf(fresh),
            )
        }
        val result = commands.attachLink(
            scope,
            io.github.triet4p.aegisf.data.AttachLinkRequest(
                idempotencyKey = operationKey,
                eventId = event.id,
                draftId = draft.id,
                expectedRevision = link.revision,
                userOverride = null,
            ),
        )
        return settledLink("attach_link", operationKey, event, draft, result)
    }

    private suspend fun settledLink(
        tool: String,
        operationKey: String,
        event: NoteEventEntity,
        draft: PendingBankDraftEntity,
        result: WorkflowCommandOutcome,
    ): AgentToolExecution = when (result) {
        is WorkflowCommandOutcome.Applied, is WorkflowCommandOutcome.NoChange -> {
            val change = (result as? WorkflowCommandOutcome.Applied)?.change
                ?: (result as WorkflowCommandOutcome.NoChange).change
            val linkId = (change as? WorkflowChange.LinkDecided)?.linkId
                ?: throw AgentToolException(
                    AgentErrorCode.EFFECT_UNKNOWN, null, "journaled_link_change",
                    message = "The application returned no link identity; reconcile before retrying.",
                )
            val link = queries.linkOperation(linkId)
                ?: throw AgentToolException(
                    AgentErrorCode.EFFECT_NOT_VERIFIED, null, "link_readable_after_write",
                    message = "The link could not be re-read. Reconcile before retrying.",
                )
            val pair = pairObservation(requireEvent(event.id), requireDraft(draft.id))
            val proposalRef = issueProposalRef(link, event.id, draft.id)
            val operationRef = issueOperationRef(operationKey, eventId = event.id, draftId = draft.id)
            AgentToolExecution(
                envelope = AgentEnvelope.ok(
                    tool = tool,
                    data = JSONObject()
                        .put("proposal_ref", proposalRef)
                        .put("link", JSONObject()
                            .put("state", link.state.name)
                            .put("revision", link.revision))
                        .put("pair", pair)
                        .put("change", changeJson(change)),
                    observations = listOf(pair),
                    effect = if (result is WorkflowCommandOutcome.Applied) "applied" else "unchanged",
                    verified = true,
                    operationRef = operationRef,
                ),
                effectClaim = AgentEffectClaim.LinkDecision(
                    event.id, draft.id, link.linkId, link.state.name, link.revision,
                ),
            )
        }
        is WorkflowCommandOutcome.Conflict -> {
            val pair = pairObservation(requireEvent(event.id), requireDraft(draft.id))
            throw AgentToolException(
                AgentErrorCode.STALE_OBSERVATION,
                "$.proposal_ref",
                "observed_revision_matches_current",
                JSONObject().put("fresh_observation_required", true),
                "The pair or its evidence changed. Inspect the fresh observation and reassess.",
                subjectRef = pair.getString("ref"),
            )
        }
        is WorkflowCommandOutcome.Denied -> deny(null, result.reason)
        WorkflowCommandOutcome.NotFound -> subjectUnavailable("$.proposal_ref", "The chosen pair is gone.")
        WorkflowCommandOutcome.Cancelled -> throw AgentToolException(
            AgentErrorCode.CANCELLED, null, "request_not_cancelled",
            message = "The application cancelled the attempt before an effect.",
        )
        is WorkflowCommandOutcome.Failed -> throw AgentToolException(
            AgentErrorCode.EFFECT_UNKNOWN, null, "settled_effect_readable",
            message = "The application did not report a settled effect; reconcile before retrying.",
        )
        is WorkflowCommandOutcome.NeedsClarification -> AgentErrorCode.MISSING_REQUIRED_MEANING.asIssue(
            "$", "domain_field_constraint", "The application needs a grounded value before it can continue.",
        )
    }

    // ---- update_draft_review -------------------------------------------------------------

    /**
     * Saves the category the hosted workflow chose for one observed draft.
     *
     * The model supplies two aliases only: the current draft and one of the category choices
     * issued by that draft's own observation. The host re-checks the draft's review revision,
     * the category's current assignability and the catalog digest the choice was observed
     * against, then writes the choice through the ordinary draft-review mutation. The draft
     * stays pending and no ledger row is written; only the user can confirm it.
     */
    private suspend fun updateDraftReview(
        arguments: JSONObject,
        operationKey: String,
    ): AgentToolExecution {
        val draftRef = registry.resolve(arguments.getString("draft_ref"), AgentReferenceKind.DRAFT, "$.draft_ref")
        val draft = requireDraft(draftRef.draftId)
        if (draftRef.draftReviewRevision != null && draftRef.draftReviewRevision != draft.reviewRevision) {
            throw staleDraft("$.draft_ref", draft)
        }
        val categoryRef = registry.resolve(
            arguments.getString("category_ref"),
            AgentReferenceKind.CATEGORY,
            "$.category_ref",
        )
        val categoryId = categoryRef.categoryId
            ?: throw AgentToolException(
                AgentErrorCode.REFERENCE_UNAVAILABLE,
                "$.category_ref",
                "observed_category_identity",
                message = "The category reference holds no stored category identity.",
            )
        val currentCatalog = queries.categoryCatalog()
        if (categoryRef.categoryCatalogRevision != null &&
            categoryRef.categoryCatalogRevision != categoryCatalogRevision(currentCatalog)
        ) {
            throw staleCategoryChoices(draft)
        }
        if (currentCatalog.none { it.id == categoryId && !it.archived }) {
            throw staleCategoryChoices(draft)
        }
        val result = commands.selectDraftCategory(
            scope = scope,
            request = SelectDraftCategoryRequest(
                idempotencyKey = operationKey,
                draftId = draft.id,
                expectedReviewRevision = draftRef.draftReviewRevision ?: draft.reviewRevision,
                categoryId = categoryId,
            ),
        )
        return when (result) {
            is WorkflowCommandOutcome.Applied -> {
                val change = result.change as? WorkflowChange.DraftCategorySelected
                    ?: throw AgentToolException(
                        AgentErrorCode.EFFECT_UNKNOWN, null, "journaled_draft_category",
                        message = "The application returned no category identity; reconcile.",
                    )
                categorySelected(change, operationKey, applied = true)
            }
            is WorkflowCommandOutcome.NoChange -> {
                val change = result.change as? WorkflowChange.DraftCategorySelected
                    ?: throw staleDraft("$.draft_ref", requireDraft(draft.id))
                categorySelected(change, operationKey, applied = false)
            }
            is WorkflowCommandOutcome.Denied -> when (result.reason) {
                WorkflowDenialReason.CATEGORY_UNAVAILABLE -> throw staleCategoryChoices(draft)
                WorkflowDenialReason.CALLER_SCOPE -> throw AgentToolException(
                    AgentErrorCode.REFERENCE_SCOPE_REFUSED, "$.draft_ref", "draft_in_trusted_scope",
                    message = "The draft is outside this authorized session.",
                )
                WorkflowDenialReason.USER_DECISION_REQUIRED -> throw AgentToolException(
                    AgentErrorCode.USER_DECISION_REQUIRED, "$.draft_ref", "user_only_decision",
                    message = "Only the user can confirm or dismiss this draft.",
                )
                else -> deny(null, result.reason)
            }
            is WorkflowCommandOutcome.Conflict -> throw staleDraft("$.draft_ref", requireDraft(draft.id))
            WorkflowCommandOutcome.NotFound -> subjectUnavailable("$.draft_ref", "The chosen draft is gone.")
            WorkflowCommandOutcome.Cancelled -> throw AgentToolException(
                AgentErrorCode.CANCELLED, null, "request_not_cancelled",
                message = "The application cancelled the category save before an effect.",
            )
            is WorkflowCommandOutcome.Failed -> {
                val journal = queries.workflowOperation(operationKey)
                if (journal != null && journal.outcome != OPERATION_OUTCOME_PENDING) {
                    throw AgentToolException(
                        AgentErrorCode.EFFECT_NOT_VERIFIED, "$.draft_ref", "settled_change_readable",
                        message = "The journal holds a settled change this attempt could not read. " +
                            "Reconcile before claiming or retrying.",
                    )
                }
                throw AgentToolException(
                    AgentErrorCode.EFFECT_UNKNOWN, "$.draft_ref", "settled_effect_readable",
                    message = "The application did not report a settled effect; reconcile before retrying.",
                )
            }
            is WorkflowCommandOutcome.NeedsClarification -> AgentErrorCode.MISSING_REQUIRED_MEANING.asIssue(
                "$.${fieldPathFor(result.field)}",
                "domain_field_constraint",
                "The application needs a grounded value before it can continue.",
                subjectRef = draftRef.handle,
            )
        }
    }

    /**
     * Reports the stored category as the settled review state it is.
     *
     * The fresh draft is re-read after the mutation, so the observation the model continues
     * from carries the new review revision and the same current category choices. Nothing
     * here confirms the draft or writes a ledger row.
     */
    private suspend fun categorySelected(
        change: WorkflowChange.DraftCategorySelected,
        operationKey: String,
        applied: Boolean,
    ): AgentToolExecution {
        val fresh = requireDraft(change.draftId)
        val observation = draftContextObservation(fresh)
        val categoryName = queries.categoryCatalog()
            .firstOrNull { it.id == change.categoryId }
            ?.name
        return AgentToolExecution(
            envelope = AgentEnvelope.ok(
                tool = "update_draft_review",
                data = JSONObject()
                    .put("draft", observation)
                    .put(
                        "category",
                        JSONObject()
                            .put("id", change.categoryId)
                            .put("name", categoryName ?: JSONObject.NULL),
                    )
                    .put("review_status", fresh.reviewStatus.name)
                    .put("review_only", true),
                observations = listOf(observation),
                effect = if (applied) "applied" else "unchanged",
                verified = true,
                // The stored category is journaled like every other mutation, so its exact
                // operation identity is replayable in this run.
                operationRef = issueOperationRef(operationKey, eventId = null, draftId = fresh.id),
            ),
            effectClaim = AgentEffectClaim.DraftCategory(
                draftId = fresh.id,
                categoryId = change.categoryId,
                reviewStatus = fresh.reviewStatus.name,
                reviewRevision = fresh.reviewRevision,
            ),
        )
    }

    /** Refuses a category choice whose catalog or category identity is no longer current. */
    private suspend fun staleCategoryChoices(draft: PendingBankDraftEntity): Nothing {
        val fresh = draftContextObservation(requireDraft(draft.id))
        throw AgentToolException(
            AgentErrorCode.STALE_OBSERVATION,
            "$.category_ref",
            "current_category_choices",
            JSONObject().put("fresh_observation_required", true),
            "The category choices changed. Re-observe the draft and choose a current category.",
            subjectRef = fresh.getString("ref"),
            observations = listOf(fresh),
        )
    }

    // ---- replay_operation ----------------------------------------------------------------

    private suspend fun replayOperation(arguments: JSONObject): AgentToolExecution {
        val reference = registry.resolve(
            arguments.getString("operation_ref"),
            AgentReferenceKind.OPERATION,
            "$.operation_ref",
        )
        val key = reference.operationKey
            ?: throw AgentToolException(
                AgentErrorCode.REFERENCE_UNAVAILABLE, "$.operation_ref", "recorded_operation_identity",
                message = "The operation reference holds no recorded identity.",
            )
        val command = reference.operationCommand
            ?: throw AgentToolException(
                AgentErrorCode.REFERENCE_UNAVAILABLE, "$.operation_ref", "recorded_operation_command",
                message = "The operation reference holds no recorded command family.",
            )
        val digest = reference.operationDigest
            ?: throw AgentToolException(
                AgentErrorCode.REFERENCE_UNAVAILABLE, "$.operation_ref", "recorded_operation_digest",
                message = "The operation reference holds no recorded request digest.",
            )
        val result = commands.replayOperation(scope, key, command, digest)
        return when (result) {
            is WorkflowCommandOutcome.NoChange -> {
                val change = result.change
                    ?: throw AgentToolException(
                        AgentErrorCode.EFFECT_UNKNOWN, "$.operation_ref", "recorded_change",
                        message = "The journal holds no change to verify; reconcile.",
                    )
                val matches = currentEffectMatches(change, reference)
                AgentToolExecution(
                    envelope = AgentEnvelope.ok(
                        tool = "replay_operation",
                        data = JSONObject()
                            .put("replayed", true)
                            .put("journal_outcome", "no_change")
                            .put("current_effect_matches", matches)
                            .put("change", changeJson(change)),
                        effect = "unchanged",
                        verified = matches,
                        operationRef = registry.issue(
                            AgentReference(
                                handle = registry.nextHandle(),
                                kind = AgentReferenceKind.OPERATION,
                                scopeId = scope.scopeId,
                                subjectId = reference.subjectId,
                                captureId = reference.captureId,
                                eventId = reference.eventId,
                                draftId = reference.draftId,
                                operationKey = key,
                                operationCommand = command,
                                operationDigest = digest,
                            ),
                        ),
                    ),
                    effectClaim = AgentEffectClaim.Replay(key, matches),
                )
            }
            is WorkflowCommandOutcome.Failed -> throw AgentToolException(
                AgentErrorCode.OPERATION_PENDING, "$.operation_ref", "recorded_operation_settled",
                message = "The recorded operation is still pending. Reconcile before replaying.",
            )
            WorkflowCommandOutcome.NotFound -> throw AgentToolException(
                AgentErrorCode.REFERENCE_UNAVAILABLE, "$.operation_ref", "recorded_operation_exists",
                message = "That recorded operation is no longer available in this scope.",
            )
            is WorkflowCommandOutcome.Conflict -> throw AgentToolException(
                AgentErrorCode.REFERENCE_SCOPE_REFUSED, "$.operation_ref", "recorded_operation_ownership",
                message = "That operation receipt is not replayable from this session.",
            )
            is WorkflowCommandOutcome.Denied -> throw AgentToolException(
                AgentErrorCode.REFERENCE_SCOPE_REFUSED, "$.operation_ref", "recorded_operation_ownership",
                message = "That operation receipt is not replayable from this session.",
            )
            is WorkflowCommandOutcome.NeedsClarification -> throw AgentToolException(
                AgentErrorCode.EFFECT_UNKNOWN, "$.operation_ref", "recorded_operation_settled",
                message = "The recorded operation is not settled; reconcile.",
            )
            WorkflowCommandOutcome.Cancelled -> throw AgentToolException(
                AgentErrorCode.CANCELLED, "$.operation_ref", "recorded_operation_settled",
                message = "The recorded operation was cancelled; there is nothing to replay.",
            )
            is WorkflowCommandOutcome.Applied -> throw AgentToolException(
                AgentErrorCode.EFFECT_NOT_VERIFIED, "$.operation_ref", "replay_changes_nothing",
                message = "Replay reported a new change. Reconcile; do not treat it as a replay receipt.",
            )
        }
    }

    // ---- ask_user ------------------------------------------------------------------------

    private suspend fun askUser(arguments: JSONObject): AgentToolExecution {
        val subject = registry.resolveAny(
            arguments.getString("subject_ref"),
            setOf(
                AgentReferenceKind.CAPTURE,
                AgentReferenceKind.SOURCE,
                AgentReferenceKind.EVENT,
                AgentReferenceKind.DRAFT,
                AgentReferenceKind.PAIR,
            ),
            "$.subject_ref",
        )
        val requested = arguments.getString("field")
        val question = arguments.getString("question").trim()
        if (question.isEmpty()) {
            AgentErrorCode.SCHEMA_INVALID.asIssue(
                "$.question", "nonblank_question", "Ask one concise question about the chosen fact.",
            )
        }
        val kind = subject.kind
        val allowed = setOf("amount", "date", "time", "merchant", "occurrence", "candidate", "meaning")
        if (requested !in allowed) {
            AgentErrorCode.SCHEMA_INVALID.asIssue(
                "$.field", "enum", "Choose one declared clarification field.",
            )
        }
        val consistent = when (requested) {
            "amount", "date", "time", "merchant" -> kind == AgentReferenceKind.SOURCE || kind == AgentReferenceKind.EVENT
            "occurrence" -> kind == AgentReferenceKind.CAPTURE || kind == AgentReferenceKind.SOURCE
            "candidate" -> kind == AgentReferenceKind.DRAFT || kind == AgentReferenceKind.PAIR
            else -> true
        }
        if (!consistent) {
            AgentErrorCode.REFERENCE_KIND_MISMATCH.asIssue(
                "$.subject_ref", "subject_supports_field",
                "Choose a subject that can carry this clarification, or ask about a different fact.",
            )
        }
        return AgentToolExecution(
            envelope = AgentEnvelope.clarifying(
                tool = "ask_user",
                subjectRef = subject.handle,
                field = requested,
                question = question,
            ),
            clarificationField = requested,
            clarificationQuestion = question,
        )
    }

    // ---- Clarification ingress (host-only) -----------------------------------------------

    /**
     * Turns a user answer into scoped clarification evidence for the suspended subject.
     *
     * Host-only: the model neither calls this nor names the answer. The raw answer is handled
     * only inside this local call; the observation carries its masked rendering plus, when the
     * requested fact is unambiguous, a host-issued typed value reference. A bare amount answer
     * to an amount question is minted as an AMOUNT value (with its unit) instead of being
     * guessed from an ambiguous account-like digit run. A merchant answer becomes a
     * clarification source bound to the subject. No raw value leaves this method.
     */
    suspend fun registerClarification(
        subjectRef: String,
        field: String,
        userAnswer: String,
    ): JSONObject {
        val subject = registry.resolveAny(
            subjectRef,
            setOf(
                AgentReferenceKind.CAPTURE,
                AgentReferenceKind.SOURCE,
                AgentReferenceKind.EVENT,
                AgentReferenceKind.DRAFT,
                AgentReferenceKind.PAIR,
            ),
            "subject_ref",
        )
        val maskedAnswer = mask(userAnswer)
        var display = maskedAnswer
        val linked = subject.bindingKey()
        val observation = JSONObject()
            .put("kind", "clarification")
            .put("subject_ref", subject.handle)
            .put("field", field)
        when (field) {
            "amount" -> {
                val token = amountClarificationToken(userAnswer, maskedAnswer)
                if (token != null) {
                    display = token
                    val ref = issueClarificationValue(subject, linked, token)
                    observation.put("value_ref", ref)
                        .put("value_shape", boundary.vault.shapeOf(token)?.wire ?: JSONObject.NULL)
                } else {
                    observation.put("value_ref", JSONObject.NULL)
                        .put("note", "The answer did not state one unambiguous value. Ask again or use the observed source.")
                }
            }
            "date", "time" -> {
                val tokens = SensitiveVault.TOKEN_PATTERN.findAll(maskedAnswer)
                    .map { it.value }
                    .distinct()
                    .toList()
                if (tokens.size == 1) {
                    display = tokens.single()
                    val ref = issueClarificationValue(subject, linked, tokens.single())
                    observation.put("value_ref", ref)
                        .put("value_shape", boundary.vault.shapeOf(tokens.single())?.wire ?: JSONObject.NULL)
                } else {
                    observation.put("value_ref", JSONObject.NULL)
                        .put("note", "The answer did not state one unambiguous value. Ask again or use the observed source.")
                }
            }
            "merchant" -> {
                if (maskedAnswer.isNotBlank()) {
                    val handle = registry.nextHandle()
                    val ref = registry.issue(
                        AgentReference(
                            handle = handle,
                            kind = AgentReferenceKind.SOURCE,
                            scopeId = scope.scopeId,
                            subjectId = subject.subjectId,
                            captureId = subject.captureId,
                            eventId = subject.eventId,
                            draftId = subject.draftId,
                            display = maskedAnswer,
                            origin = AgentReferenceOrigin.CLARIFICATION,
                            linkedSubjectKey = linked,
                        ),
                    )
                    observation.put("merchant_ref", ref)
                }
            }
            else -> Unit
        }
        observation.put("answer", display)
        return observation
    }

    private fun issueClarificationValue(
        subject: AgentReference,
        linkedSubjectKey: String,
        token: String,
    ): String {
        val handle = registry.nextHandle()
        return registry.issue(
            AgentReference(
                handle = handle,
                kind = AgentReferenceKind.VALUE,
                scopeId = scope.scopeId,
                subjectId = subject.subjectId,
                captureId = subject.captureId,
                eventId = subject.eventId,
                draftId = subject.draftId,
                valueToken = token,
                display = token,
                origin = AgentReferenceOrigin.CLARIFICATION,
                linkedSubjectKey = linkedSubjectKey,
            ),
        )
    }

    /**
     * The neutral token for an answer to a value question. Whole-dong input is accepted with
     * optional VN grouping and currency word; a unit-bearing or grouped answer is otherwise
     * resolved from the masked text. The token carries no semantic kind: the clarification
     * field names the role, and record_event/make_time_anchor parse the value for that role.
     * Anything ambiguous stays unresolved.
     */
    private fun amountClarificationToken(userAnswer: String, maskedAnswer: String): String? {
        val digits = amountDigits(userAnswer)
        if (digits != null) {
            // Mint under a unit so even a short bare answer becomes a masked value; the token
            // stays neutral and the tool argument supplies the meaning.
            val minted = mask("$digits VND")
            return SensitiveVault.TOKEN_PATTERN.find(minted)?.value
        }
        return SensitiveVault.TOKEN_PATTERN.findAll(maskedAnswer)
            .map { it.value }
            .distinct()
            .singleOrNull()
    }

    private fun amountDigits(raw: String): String? {
        val trimmed = raw.trim()
        val withoutUnit = trimmed
            .removeSuffix("₫")
            .removeSuffix("đ")
            .removeSuffix("VND")
            .removeSuffix("vnd")
            .removeSuffix("Dong")
            .removeSuffix("dong")
            .trim()
        if (withoutUnit.isEmpty()) return null
        if (!withoutUnit.matches(Regex("\\d{1,3}(?:[.,]\\d{3})+|\\d+"))) return null
        val digits = withoutUnit.replace(".", "").replace(",", "")
        return digits.takeIf { it.length in 1..12 && it.all(Char::isDigit) }
    }

    // ---- Initial observations (host-only) ------------------------------------------------

    /** The bounded capture observations the first prompt carries. */
    suspend fun initialCaptureObservations(limit: Int): List<JSONObject> {
        if (limit <= 0 || scope.captureIds.isEmpty()) return emptyList()
        val captures = queries.sourceExcerptsByIdsNewestFirst(scope.captureIds)
        val observations = ArrayList<JSONObject>(minOf(limit, captures.size))
        for (capture in captures) {
            if (observations.size >= limit) break
            if (capture.tombstone || capture.bodyText == null) continue
            runCatching { captureObservation(capture) }.getOrNull()?.let(observations::add)
        }
        return observations
    }

    /** The bounded draft page the first prompt carries for a draft-dependent workflow. */
    suspend fun initialDraftObservation(): JSONObject? =
        runCatching {
            val scan = visibleDrafts(DraftFilter(null, false))
            val signature = draftSignature(DraftFilter(null, false), scan.rows)
            val page = scan.rows.take(DRAFT_PAGE_SIZE)
            val next = if (page.size < scan.rows.size) issueDraftCursor(signature, DraftFilter(null, false), startOffset = page.size) else null
            JSONObject()
                .put("kind", "draft_page")
                .put("items", JSONArray(page.map { draftObservation(it) }))
                .put("page_size", DRAFT_PAGE_SIZE)
                .put("next_cursor", next ?: JSONObject.NULL)
                .put("scope_exhausted", next == null && !scan.truncated)
        }.getOrNull()

    /** Settled journal receipts that can be replayed or observed after a restart. */
    suspend fun initialOperationObservations(limit: Int): List<JSONObject> {
        if (limit <= 0) return emptyList()
        val page = runCatching {
            queries.workflowOperations(WorkflowOperationQuery(bounds = QueryPageRequest(limit, 0)))
        }.getOrNull() ?: return emptyList()
        val observations = mutableListOf<JSONObject>()
        for (operation in page.items) {
            if (operation.scopeId != scope.scopeId) continue
            val ref = issueOperationReference(operation)
            observations += observedOperation(operation, ref)
            if (observations.size >= limit) break
        }
        return observations
    }

    // ---- Verification (executor) ---------------------------------------------------------

    /**
     * The host target a mutation call addresses, used only by the executor's retry accounting.
     * Resolved from the same aliases the call uses; an unusable call yields null.
     */
    fun targetKey(name: String, arguments: JSONObject): String? = try {
        when (name) {
            "record_event" -> registry.resolve(
                arguments.getString("source_ref"), AgentReferenceKind.SOURCE, "$.source_ref",
            ).let { "source:${it.captureId}:${it.spanStart}:${it.spanEnd}" }
            "propose_link" -> registry.resolve(
                arguments.getString("pair_ref"), AgentReferenceKind.PAIR, "$.pair_ref",
            ).let { "link:${it.eventId}:${it.draftId}" }
            "attach_link" -> registry.resolve(
                arguments.getString("proposal_ref"), AgentReferenceKind.PROPOSAL, "$.proposal_ref",
            ).let { "link:${it.eventId}:${it.draftId}" }
            "update_draft_review" -> registry.resolve(
                arguments.getString("draft_ref"), AgentReferenceKind.DRAFT, "$.draft_ref",
            ).let { "draft:${it.draftId}" }
            "replay_operation" -> registry.resolve(
                arguments.getString("operation_ref"), AgentReferenceKind.OPERATION, "$.operation_ref",
            ).let { "operation:${it.operationKey}" }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** Re-reads one local effect claim from live application state. */
    suspend fun verify(claim: AgentEffectClaim): Boolean = when (claim) {
        is AgentEffectClaim.EventReading -> {
            val event = queries.noteEvent(claim.eventId)
            event != null && event.captureId == claim.captureId && event.revision == claim.revision
        }
        is AgentEffectClaim.LinkDecision -> {
            val link = queries.linkOperation(claim.linkId)
            link != null && link.eventId == claim.eventId && link.draftId == claim.draftId &&
                link.state.name == claim.state && link.revision == claim.revision
        }
        is AgentEffectClaim.DraftCategory -> {
            val draft = queries.draftCandidate(claim.draftId)
            draft != null && draft.reviewStatus.name == claim.reviewStatus &&
                draft.reviewRevision == claim.reviewRevision &&
                draft.reviewCategoryId == claim.categoryId
        }
        // The replay request itself is settled and verified by the domain journal; whether the
        // historical effect is still present is reported separately as currentEffectMatches and
        // never silently asserted as current.
        is AgentEffectClaim.Replay -> true
    }

    /**
     * Re-reads one absence claim from live application state.
     *
     * A draft-scope claim must still return exactly the same in-scope rows under the same bound
     * filter. A pair claim must still be a stored-evidence non-eligible pair with the observed
     * event revision and no stored proposal or attachment for that pair now. Anything that moved
     * makes the claim false, so the run reports `Incomplete` instead of an unproved absence.
     */
    suspend fun verifyNoEffect(claim: AgentNoEffectClaim): Boolean {
        return when (claim) {
            is AgentNoEffectClaim.DraftScope -> {
                val status = when (val raw = claim.reviewStatus) {
                    null -> null
                    else -> IntakeReviewStatus.entries.firstOrNull { it.name == raw } ?: return false
                }
                if (claim.eventId != null) {
                    val event = queries.noteEvent(claim.eventId) ?: return false
                    if (event.revision != claim.eventRevision) return false
                }
                val scan = try {
                    visibleDrafts(
                        DraftFilter(
                            reviewStatus = status,
                            duplicatesOnly = claim.duplicatesOnly,
                            eventId = claim.eventId,
                            eventRevision = claim.eventRevision,
                        ),
                    )
                } catch (_: AgentToolException) {
                    return false
                }
                val currentIds = scan.rows.map { it.id }.sorted()
                val currentRevisions = scan.rows.associate { it.id to it.reviewRevision }
                currentIds == claim.draftIds.sorted() &&
                    currentRevisions == claim.draftRevisionById &&
                    (!claim.scopeExhausted || !scan.truncated)
            }

            is AgentNoEffectClaim.PairInspection -> {
                val event = queries.noteEvent(claim.eventId) ?: return false
                if (event.revision != claim.eventRevision) return false
                if (claim.eligibility == LinkEligibility.ELIGIBLE.name) return false
                pairIsUnattachableNow(claim.eventId, claim.draftId)
            }

            is AgentNoEffectClaim.AlreadyInterpreted -> {
                val event = queries.noteEvent(claim.eventId) ?: return false
                event.revision == claim.revision && event.status == NoteEventStatus.INTERPRETED
            }
        }
    }

    /**
     * Re-reads every inspected candidate of one event: each pair is still unattachable under
     * the stored evidence policy and still has no proposal or attachment.
     */
    suspend fun verifyLinkAbsence(
        eventId: Long,
        inspections: List<AgentNoEffectClaim.PairInspection>,
    ): Boolean {
        if (inspections.isEmpty()) return false
        if (inspections.any { it.eventId != eventId }) return false
        return inspections.all { verifyNoEffect(it) }
    }

    private suspend fun pairIsUnattachableNow(eventId: Long, draftId: Long): Boolean {
        val draft = queries.draftCandidate(draftId) ?: return false
        val inspection = commands.inspectLinkEvidence(scope, eventId, draft.id) ?: return false
        if (inspection.eligibility == LinkEligibility.ELIGIBLE) return false
        val links = queries.linkOperations(
            LinkOperationQuery(
                eventId = eventId,
                draftId = draft.id,
                bounds = QueryPageRequest(10, 0),
            ),
        )
        return links.items.none { link ->
            link.eventId == eventId && link.draftId == draft.id &&
                (link.state == ContextLinkState.PROPOSED || link.state == ContextLinkState.ACCEPTED)
        }
    }

    // ---- Observations --------------------------------------------------------------------

    private suspend fun captureObservation(capture: CaptureMessageEntity): JSONObject {
        val sourceText = capture.bodyText
        // This is a sanctioned outbound observation: mint the scope's typed tokens here so the
        // model can select and reference sensitive values. Preview-only rendering would expose
        // UNMAPPED placeholders that no later tool can resolve.
        val maskedSource = sourceText?.let { mask(it) }
        val events = queries.captureEvents(
            CaptureEventQuery(capture.id, QueryPageRequest(MAX_OBSERVATION_ROWS, 0)),
        )
        val eventObservations = events.items.map { eventObservation(it) }
        val values = maskedSource?.let { valueRefs(capture.id, it) }.orEmpty()
        val anchors = maskedSource?.let { masked ->
            events.items.flatMap { event ->
                anchorsFor(event, capture.id, eventBindingKey(event))
            }
        }.orEmpty()
        val handle = registry.nextHandle()
        val ref = registry.issue(
            AgentReference(
                handle = handle,
                kind = AgentReferenceKind.CAPTURE,
                scopeId = scope.scopeId,
                subjectId = capture.id,
                captureId = capture.id,
                captureRevision = capture.revision,
                display = maskedSource,
            ),
        )
        return JSONObject()
            .put("ref", ref)
            .put("kind", "capture")
            .put("capture_revision", capture.revision)
            .put("source_text", maskedSource ?: JSONObject.NULL)
            .put("source_is_complete", !events.hasMore && capture.bodyText != null)
            .put("values", JSONArray(values))
            .put("events", JSONArray(eventObservations))
            .put("anchors", JSONArray(anchors))
    }

    private suspend fun eventObservation(event: NoteEventEntity): JSONObject {
        val capture = requireCapture(event.captureId)
        val sourceText = capture.bodyText
        val maskedSpan = if (sourceText != null && event.sourceRevision == capture.revision) {
            runCatching {
                MaskedApplicationEffect.maskedOffsetsForSourceSpan(
                    scope, boundary, capture.id, sourceText, event.spanStart, event.spanEnd,
                )
            }.getOrNull()
        } else {
            null
        }
        val handle = registry.nextHandle()
        val ref = registry.issue(
            AgentReference(
                handle = handle,
                kind = AgentReferenceKind.EVENT,
                scopeId = scope.scopeId,
                subjectId = event.id,
                captureId = event.captureId,
                captureRevision = capture.revision,
                eventId = event.id,
                eventRevision = event.revision,
                spanStart = event.spanStart,
                spanEnd = event.spanEnd,
            ),
        )
        val captureRef = registry.issue(
            AgentReference(
                handle = registry.nextHandle(),
                kind = AgentReferenceKind.CAPTURE,
                scopeId = scope.scopeId,
                subjectId = capture.id,
                captureId = capture.id,
                captureRevision = capture.revision,
                display = sourceText?.let { boundary.vault.maskedPreview(it) },
            ),
        )
        return JSONObject()
            .put("ref", ref)
            .put("kind", "event")
            .put("user_label", "event #${event.id}")
            .put("capture_ref", captureRef)
            .put("capture_revision", capture.revision)
            .put("event_revision", event.revision)
            .put("source_revision", event.sourceRevision)
            .put("status", event.status.name)
            .put("source_span", if (maskedSpan == null) JSONObject.NULL else JSONObject()
                .put("masked_start", maskedSpan.first)
                .put("masked_end", maskedSpan.second))
            .put("masked_quote", maskedSpan?.let { sourceText!!.let { text -> boundary.vault.maskedPreview(text).substring(it.first, it.second) } } ?: JSONObject.NULL)
            .put("text", event.normalizedText?.let { mask(it) } ?: JSONObject.NULL)
            .put("amount", maskAmount(event.amountVnd))
            .put("merchant", event.merchant?.let { mask(it) } ?: JSONObject.NULL)
            .put("date", maskDate(event.anchorDate))
            .put("time", maskDate(event.anchorTime))
            .put("interval_end_date", maskDate(event.intervalEndDate))
            .put("interval_end_time", maskDate(event.intervalEndTime))
            .put("unresolved_reason", event.unresolvedReason?.let { mask(it) } ?: JSONObject.NULL)
    }

    private suspend fun draftObservation(draft: PendingBankDraftEntity): JSONObject {
        val amount = effectiveCandidateAmount(draft)
        val transactionDate = effectiveCandidateDate(draft)
        val transactionTime = effectiveCandidateTime(draft)
        val description = effectiveCandidateDescription(draft)
        val catalog = queries.categoryCatalog()
        val catalogRevision = categoryCatalogRevision(catalog)
        val handle = registry.nextHandle()
        val ref = registry.issue(
            AgentReference(
                handle = handle,
                kind = AgentReferenceKind.DRAFT,
                scopeId = scope.scopeId,
                subjectId = draft.id,
                draftId = draft.id,
                draftReviewRevision = draft.reviewRevision,
                display = mask(draft.workingDescription()),
            ),
        )
        val choices = JSONArray()
        for (category in catalog.filterNot { it.archived }) {
            val categoryRef = registry.issue(
                AgentReference(
                    handle = registry.nextHandle(),
                    kind = AgentReferenceKind.CATEGORY,
                    scopeId = scope.scopeId,
                    subjectId = category.id,
                    categoryId = category.id,
                    categoryCatalogRevision = catalogRevision,
                ),
            )
            choices.put(
                JSONObject()
                    .put("ref", categoryRef)
                    .put("name", category.name),
            )
        }
        val chosen = draft.reviewCategoryId?.let { id -> catalog.firstOrNull { it.id == id } }
        return JSONObject()
            .put("ref", ref)
            .put("kind", "draft")
            .put("user_label", "draft #${draft.id}")
            .put("review_revision", draft.reviewRevision)
            .put("review_status", draft.reviewStatus.name)
            .put("bank", mask(draft.bank))
            .put("amount", maskAmount(amount))
            .put(
                "amount_source",
                if (draft.reviewAmountText != null) {
                    "USER_CORRECTION"
                } else if (draft.amountState == IntakeFieldState.PRESENT) {
                    "BANK_PARSED"
                } else {
                    draft.amountState.name
                },
            )
            .put("transaction_date", maskDate(transactionDate))
            .put(
                "transaction_date_source",
                if (draft.reviewDateText != null) {
                    "USER_CORRECTION"
                } else if (draft.momentState == IntakeFieldState.PRESENT) {
                    "BANK_PARSED"
                } else {
                    draft.momentState.name
                },
            )
            .put("transaction_time", maskDate(transactionTime))
            .put(
                "transaction_time_source",
                if (draft.reviewTimeText != null) {
                    "USER_CORRECTION"
                } else if (draft.momentState == IntakeFieldState.PRESENT) {
                    "BANK_PARSED"
                } else {
                    draft.momentState.name
                },
            )
            .put("memo", draft.memo?.takeIf { draft.memoState == IntakeFieldState.PRESENT }?.let { mask(it) } ?: JSONObject.NULL)
            .put("description", description?.let { mask(it) } ?: JSONObject.NULL)
            .put(
                "description_source",
                if (draft.reviewMemoEdited) {
                    "USER_CORRECTION"
                } else if (draft.memoState == IntakeFieldState.PRESENT) {
                    "BANK_PARSED"
                } else {
                    draft.memoState.name
                },
            )
            .put("category_resolved", draft.reviewCategoryResolved)
            .put(
                "selected_category",
                chosen?.let { JSONObject().put("id", it.id).put("name", it.name) }
                    ?: JSONObject.NULL,
            )
            .put("category_choices", choices)
    }

    private fun candidateMatchObservation(
        match: DraftCandidateMatch,
        rank: Int,
    ): JSONObject = JSONObject()
        .put("rank", rank)
        .put("amount", match.amount.wireValue())
        .put("transaction_time_compatibility", match.transactionTime.wireValue())
        .put("merchant", match.merchant.wireValue())
        .put("time_proximity_ordering_only", match.pointTimeDistanceMinutes != null)
        .put("ranking_rationale", JSONArray(match.rankingRationale))
        .put("unknown_dimensions", JSONArray(listOf("account", "transaction_type")))

    private fun candidateMatchSetObservation(
        matchSet: DraftCandidateMatchSet,
        candidatePageComplete: Boolean,
    ): JSONObject =
        JSONObject()
            .put("outcome", matchSet.outcome.wireValue())
            .put("candidate_count", matchSet.candidates.size)
            .put("conflicting_count", matchSet.conflictingDraftCount)
            .put("candidate_set_complete_within_granted_scope", true)
            .put("candidate_page_complete", candidatePageComplete)
            .put("pending_database_complete_at_recheck", matchSet.pendingDatabaseComplete)
            .put(
                "scope_note",
                if (matchSet.pendingDatabaseComplete) {
                    "The bounded current pending-id recheck found every pending draft inside this session's grant."
                } else {
                    "The initial grant was incomplete, or the bounded current pending-id recheck found fresh/out-of-grant pending ids or another page. Candidate rows are complete only within the granted ids; the pending database remains incomplete."
                },
            )
            .put("receipt_time_used", false)
            .put("unknown_dimensions", JSONArray(listOf("account", "transaction_type")))
            .put("ranking_rationale", JSONArray(matchSet.rankingRationale))

    private fun DraftCandidateEvidenceState.wireValue(): String = when (this) {
        DraftCandidateEvidenceState.MATCH -> "match"
        DraftCandidateEvidenceState.CONFLICT -> "conflict"
        DraftCandidateEvidenceState.MISSING -> "missing"
    }

    private fun DraftCandidateMatchOutcome.wireValue(): String = when (this) {
        DraftCandidateMatchOutcome.MISSING -> "missing"
        DraftCandidateMatchOutcome.UNIQUE -> "unique"
        DraftCandidateMatchOutcome.AMBIGUOUS -> "ambiguous"
        DraftCandidateMatchOutcome.CONFLICTING -> "conflicting"
        DraftCandidateMatchOutcome.INCOMPLETE -> "incomplete"
    }
    /**
     * One fresh draft observation plus the masked readings of its accepted links.
     *
     * The linked readings are the source context a category choice may rest on; every text
     * passes through the same local masking boundary as the draft's own description. A link
     * in any other state is not context and is not shown as though it were.
     */
    private suspend fun draftContextObservation(draft: PendingBankDraftEntity): JSONObject {
        val observation = draftObservation(draft)
        val linkedContext = JSONArray()
        val links = queries.draftContextLinks(
            draftId = draft.id,
            bounds = QueryPageRequest(MAX_DRAFT_CONTEXT_LINKS, 0),
        )
        for (link in links.items) {
            if (link.state != ContextLinkState.ACCEPTED) continue
            val reading = link.readingText ?: link.excerpt
            linkedContext.put(
                JSONObject()
                    .put("event_id", link.eventId)
                    .put("user_label", "event #${link.eventId}")
                    .put("text", reading?.let { mask(it) } ?: JSONObject.NULL)
                    .put("amount", maskAmount(link.amountVnd))
                    .put("date", maskDate(link.anchorDate))
                    .put("merchant", link.merchant?.let { mask(it) } ?: JSONObject.NULL),
            )
        }
        return observation
            .put("linked_context", linkedContext)
            .put("linked_context_count", linkedContext.length())
    }

    private suspend fun pairObservation(
        event: NoteEventEntity,
        draft: PendingBankDraftEntity,
    ): JSONObject {
        val inspection = commands.inspectLinkEvidence(scope, event.id, draft.id)
            ?: throw subjectUnavailable("$.pair_ref", "The chosen pair is not readable in this scope.")
        val link = queries.linkOperations(
            LinkOperationQuery(eventId = event.id, draftId = draft.id, bounds = QueryPageRequest(1, 0)),
        ).items.firstOrNull()
        val handle = registry.nextHandle()
        val ref = registry.issue(
            AgentReference(
                handle = handle,
                kind = AgentReferenceKind.PAIR,
                scopeId = scope.scopeId,
                subjectId = link?.linkId,
                captureId = event.captureId,
                captureRevision = inspection.captureRevision,
                eventId = event.id,
                eventRevision = event.revision,
                draftId = draft.id,
                draftReviewRevision = draft.reviewRevision,
                linkId = link?.linkId,
                linkState = link?.state?.name,
                linkRevision = link?.revision,
                eligibility = inspection.eligibility.name,
            ),
        )
        return JSONObject()
            .put("ref", ref)
            .put("kind", "pair")
            .put("event_label", "event #${event.id}")
            .put("draft_label", "draft #${draft.id}")
            .put("event_ref", eventObservation(event).getString("ref"))
            .put("draft_ref", draftObservation(draft).getString("ref"))
            .put("eligibility", inspection.eligibility.name.lowercase())
            .put("reason", inspection.reason ?: JSONObject.NULL)
            .put("event_revision", event.revision)
            .put("draft_review_revision", draft.reviewRevision)
            .put("link", if (link == null) JSONObject.NULL else JSONObject()
                .put("state", link.state.name)
                .put("revision", link.revision))
    }

    private suspend fun operationObservation(reference: AgentReference): JSONObject {
        val key = reference.operationKey
            ?: throw AgentToolException(
                AgentErrorCode.REFERENCE_UNAVAILABLE, "$.ref", "recorded_operation_identity",
                message = "The operation reference holds no recorded identity.",
            )
        val operation = queries.workflowOperation(key)
            ?: throw AgentToolException(
                AgentErrorCode.REFERENCE_UNAVAILABLE, "$.ref", "recorded_operation_exists",
                message = "That recorded operation is no longer available in this scope.",
            )
        val ref = issueOperationReference(operation)
        return observedOperation(operation, ref)
    }

    private suspend fun observedOperation(operation: WorkflowOperation, ref: String): JSONObject {
        val change = operation.change
        val matches = change != null && currentEffectMatches(
            change,
            AgentReference(
                handle = ref,
                kind = AgentReferenceKind.OPERATION,
                scopeId = scope.scopeId,
                eventId = (change as? WorkflowChange.EventStored)?.eventId
                    ?: (change as? WorkflowChange.EventRevised)?.eventId,
                draftId = (change as? WorkflowChange.DraftCategorySelected)?.draftId,
            ),
        )
        return JSONObject()
            .put("ref", ref)
            .put("kind", "operation")
            .put("journal_outcome", operation.outcome)
            .put("command", operation.command)
            .put("current_effect_matches", matches)
            .put("change", change?.let { changeJson(it) } ?: JSONObject.NULL)
    }

    // ---- Reference helpers ---------------------------------------------------------------

    private suspend fun requireCapture(captureId: Long?): CaptureMessageEntity {
        val id = captureId ?: throw subjectUnavailable("$.ref", "The observation names no capture.")
        if (id !in scope.captureIds) {
            throw AgentToolException(
                AgentErrorCode.REFERENCE_SCOPE_REFUSED, "$.ref", "capture_in_trusted_scope",
                message = "The capture is outside this authorized session.",
            )
        }
        val capture = queries.sourceExcerpt(id)
            ?: throw subjectUnavailable("$.ref", "The capture is no longer stored.")
        if (capture.tombstone || capture.bodyText == null) {
            throw subjectUnavailable("$.ref", "The capture holds no readable text.")
        }
        return capture
    }

    private suspend fun requireDraft(draftId: Long?): PendingBankDraftEntity {
        val id = draftId ?: throw subjectUnavailable("$.ref", "The observation names no draft.")
        if (id !in scope.draftIds) {
            throw AgentToolException(
                AgentErrorCode.REFERENCE_SCOPE_REFUSED, "$.ref", "draft_in_trusted_scope",
                message = "The draft is outside this authorized session.",
            )
        }
        return queries.draftCandidate(id)
            ?: throw subjectUnavailable("$.ref", "The draft is no longer stored.")
    }

    private suspend fun requireEvent(eventId: Long?): NoteEventEntity {
        val id = eventId ?: throw subjectUnavailable("$.ref", "The observation names no event.")
        val event = queries.noteEvent(id)
            ?: throw subjectUnavailable("$.ref", "The reading is no longer stored.")
        if (event.captureId !in scope.captureIds) {
            throw AgentToolException(
                AgentErrorCode.REFERENCE_SCOPE_REFUSED, "$.ref", "event_in_trusted_scope",
                message = "The reading is outside this authorized session.",
            )
        }
        return event
    }

    private suspend fun findEventAt(captureId: Long, sourceStart: Int, sourceEnd: Int): NoteEventEntity? {
        var offset = 0
        var pages = 0
        while (pages < MAX_SCAN_PAGES) {
            pages += 1
            val page = queries.captureEvents(
                CaptureEventQuery(captureId, QueryPageRequest(MAX_OBSERVATION_ROWS, offset)),
            )
            page.items.firstOrNull { it.spanStart == sourceStart && it.spanEnd == sourceEnd }
                ?.let { return it }
            if (!page.hasMore) return null
            offset = page.nextOffset ?: return null
        }
        return null
    }

    /**
     * A reading of the same capture whose source span overlaps [start]..[end) without
     * being that exact span, or null.
     *
     * Two distinct occurrences have separate spans, so they never overlap. A selection
     * that overlaps an existing reading is a changed boundary over that occurrence; this
     * interface does not rewrite source boundaries, so the mutation is refused rather
     * than stored as a duplicate reading.
     */
    private suspend fun findOverlappingEvent(captureId: Long, start: Int, end: Int): NoteEventEntity? {
        var offset = 0
        var pages = 0
        while (pages < MAX_SCAN_PAGES) {
            pages += 1
            val page = queries.captureEvents(
                CaptureEventQuery(captureId, QueryPageRequest(MAX_OBSERVATION_ROWS, offset)),
            )
            page.items.firstOrNull { event ->
                event.spanStart < end && event.spanEnd > start &&
                    !(event.spanStart == start && event.spanEnd == end)
            }?.let { return it }
            if (!page.hasMore) return null
            offset = page.nextOffset ?: return null
        }
        return null
    }

    /**
     * The stored fields this correction would silently erase.
     *
     * A full replacement is the update semantics, but absence is only a statement about
     * the new reading: dropping a value that the stored reading holds needs an explicit
     * correction. The wire contract has no removal argument, so the host refuses and
     * names the field instead of merging or erasing it unseen.
     */
    private fun destructiveCorrectionIssues(
        current: NoteEventEntity?,
        amount: AgentReference?,
        anchor: AgentReference?,
        merchant: AgentReference?,
    ): List<AgentIssue> {
        if (current == null) return emptyList()
        val issues = mutableListOf<AgentIssue>()
        fun erased(path: String, field: String) {
            issues += AgentIssue(
                path,
                "observed_reading_field_preserved_or_replaced",
                JSONObject().put("field", field),
                "The selected reading already has a $field. Pass its observed alias to keep " +
                    "or replace it; a null would silently erase it.",
            )
        }
        if (current.amountVnd != null && amount == null) erased("$.amount_ref", "amount")
        val hasStatedDate = anchor != null &&
            (anchor.dateToken != null || anchor.dateRelativeDayOffset != null)
        if (current.anchorDate != null && !hasStatedDate) erased("$.anchor_ref", "date")
        if (current.anchorTime != null && anchor?.timeToken == null) erased("$.anchor_ref", "time")
        if (current.intervalEndDate != null && anchor?.endDateToken == null) erased("$.anchor_ref", "interval end date")
        if (current.intervalEndTime != null && anchor?.endTimeToken == null) erased("$.anchor_ref", "interval end time")
        if (current.merchant != null && merchant == null) erased("$.merchant_ref", "merchant")
        return issues
    }

    private fun bindingStillValid(source: AgentReference, current: NoteEventEntity?): Boolean =
        source.eventRevision == current?.revision && (current == null || source.eventId == current.id)

    private fun bindingCompatible(first: AgentReference, second: AgentReference?): Boolean {
        if (second == null) return true
        if (first.captureId != null && second.captureId != null && first.captureId != second.captureId) return false
        if (first.eventId != null && first.eventId == second.eventId) return true
        val firstKey = effectiveKey(first)
        val secondKey = effectiveKey(second)
        return firstKey == secondKey ||
            (first.origin == AgentReferenceOrigin.CLARIFICATION && first.linkedSubjectKey == second.bindingKey()) ||
            (second.origin == AgentReferenceOrigin.CLARIFICATION && second.linkedSubjectKey == first.bindingKey()) ||
            compatibleCaptureClarification(first, second) ||
            compatibleCaptureClarification(second, first)
    }

    private fun effectiveKey(reference: AgentReference): String =
        if (reference.origin == AgentReferenceOrigin.CLARIFICATION) {
            reference.linkedSubjectKey ?: reference.bindingKey()
        } else {
            reference.bindingKey()
        }

    /** A typed value is usable only from a selected source span or its linked clarification. */
    private fun isGroundedValue(reference: AgentReference): Boolean =
        reference.origin == AgentReferenceOrigin.CLARIFICATION ||
            reference.linkedSubjectKey?.startsWith("source:") == true

    private fun compatibleCaptureClarification(
        clarification: AgentReference,
        other: AgentReference,
    ): Boolean = clarification.origin == AgentReferenceOrigin.CLARIFICATION &&
        clarification.linkedSubjectKey == "capture:${clarification.captureId}" &&
        clarification.captureId != null &&
        clarification.captureId == other.captureId

    private fun merchantWithin(merchant: AgentReference, source: AgentReference): Boolean {
        if (merchant.origin == AgentReferenceOrigin.CLARIFICATION) {
            return bindingCompatible(merchant, source)
        }
        if (merchant.captureId != source.captureId) return false
        val merchantStart = merchant.spanStart ?: return false
        val merchantEnd = merchant.spanEnd ?: return false
        return merchantStart >= (source.spanStart ?: 0) && merchantEnd <= (source.spanEnd ?: 0)
    }

    private fun sourceRefKey(start: Int, end: Int, captureId: Long): String = "source:$captureId:$start:$end"

    private fun eventBindingKey(event: NoteEventEntity): String = "event:${event.id}"

    private fun anchorsFor(
        event: NoteEventEntity,
        captureId: Long,
        bindingKey: String,
    ): List<JSONObject> {
        if (event.anchorDate == null) return emptyList()
        val dateToken = mask(event.anchorDate)
        if (!SensitiveVault.TOKEN_PATTERN.matches(dateToken)) return emptyList()
        val timeToken = event.anchorTime?.let { mask(it) }
            ?.takeIf { SensitiveVault.TOKEN_PATTERN.matches(it) }
        val endDateToken = event.intervalEndDate?.let { mask(it) }
            ?.takeIf { SensitiveVault.TOKEN_PATTERN.matches(it) }
        val endTimeToken = event.intervalEndTime?.let { mask(it) }
            ?.takeIf { SensitiveVault.TOKEN_PATTERN.matches(it) }
        val handle = registry.nextHandle()
        val ref = registry.issue(
            AgentReference(
                handle = handle,
                kind = AgentReferenceKind.ANCHOR,
                scopeId = scope.scopeId,
                subjectId = event.id,
                captureId = captureId,
                eventId = event.id,
                eventRevision = event.revision,
                dateToken = dateToken,
                timeToken = timeToken,
                endDateToken = endDateToken,
                endTimeToken = endTimeToken,
                linkedSubjectKey = bindingKey,
            ),
        )
        return listOf(
            JSONObject()
                .put("ref", ref)
                .put("kind", "anchor")
                .put("date", dateToken)
                .put("time", timeToken ?: JSONObject.NULL)
                .put("date_only", timeToken == null),
        )
    }

    private fun valueRefs(captureId: Long, maskedSource: String): List<JSONObject> =
        valueRefsIn(
            captureId = captureId,
            sourceKey = "capture:$captureId",
            quote = maskedSource,
            eventId = null,
        )

    /**
     * Issues one neutral value alias for every masked value in [quote].
     *
     * The selection is deliberately semantic-free: every value the model can see receives the
     * same `value` kind plus its lexical shape hint, so a plainly written amount, a date, a
     * time, an account digit run and a reference code are all selectable. Which argument the
     * model passes the alias to decides the role, and the runtime parses the value locally for
     * that argument. Repeating the same token in one quote yields one alias.
     */
    private fun valueRefsIn(
        captureId: Long,
        sourceKey: String,
        quote: String,
        eventId: Long?,
    ): List<JSONObject> {
        val values = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        SensitiveVault.TOKEN_PATTERN.findAll(quote).forEach { match ->
            if (!seen.add(match.value)) return@forEach
            val handle = registry.nextHandle()
            val ref = registry.issue(
                AgentReference(
                    handle = handle,
                    kind = AgentReferenceKind.VALUE,
                    scopeId = scope.scopeId,
                    captureId = captureId,
                    eventId = eventId,
                    valueToken = match.value,
                    display = match.value,
                    linkedSubjectKey = sourceKey,
                ),
            )
            values += JSONObject()
                .put("ref", ref)
                .put("kind", AgentReferenceKind.VALUE.wire)
                .put("shape", boundary.vault.shapeOf(match.value)?.wire ?: JSONObject.NULL)
                .put("display", match.value)
        }
        return values
    }

    /**
     * Issues one alias for every relative-day expression inside the selected quote.
     *
     * The expression is found in the *selected* masked text, so the alias can only name a day
     * word the model actually selected. The handle carries the bounded day offset and is bound
     * to the same occurrence key as that selection's value aliases; the anchor the model builds
     * from it is therefore occurrence evidence, and the calendar date is resolved locally from
     * the capture's own recorded local date and zone.
     */
    private fun relativeDateRefs(
        captureId: Long,
        sourceKey: String,
        quote: String,
        eventId: Long?,
    ): List<JSONObject> = RelativeDayExpressions.findIn(quote).map { relative ->
        val handle = registry.nextHandle()
        val ref = registry.issue(
            AgentReference(
                handle = handle,
                kind = AgentReferenceKind.RELATIVE_DATE,
                scopeId = scope.scopeId,
                subjectId = eventId,
                captureId = captureId,
                eventId = eventId,
                display = relative.surface,
                linkedSubjectKey = sourceKey,
                relativeDayOffset = relative.dayOffset,
            ),
        )
        JSONObject()
            .put("ref", ref)
            .put("kind", AgentReferenceKind.RELATIVE_DATE.wire)
            .put("text", relative.surface)
            .put("day_offset", relative.dayOffset)
    }

    private fun occurrences(text: String, quote: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var index = text.indexOf(quote)
        while (index >= 0) {
            ranges += index until (index + quote.length)
            index = text.indexOf(quote, index + 1)
        }
        return ranges
    }

    private fun splitsToken(maskedSource: String, start: Int, end: Int): Boolean =
        SensitiveVault.TOKEN_PATTERN.findAll(maskedSource).any { token ->
            (start > token.range.first && start <= token.range.last) ||
                (end > token.range.first && end <= token.range.last)
        }

    private fun issueDraftCursor(signature: String, filter: DraftFilter, startOffset: Int = 0): String =
        registry.issue(
            AgentReference(
                handle = registry.nextHandle(),
                kind = AgentReferenceKind.CURSOR,
                scopeId = scope.scopeId,
                cursorSignature = signature,
                cursorOffset = startOffset,
                display = buildString {
                    append(filter.reviewStatus?.name ?: "ALL").append(':').append(filter.duplicatesOnly)
                    filter.eventId?.let { append(":event-").append(it).append('@').append(filter.eventRevision) }
                },
            ),
        )

    private fun draftSignature(
        filter: DraftFilter,
        visible: List<PendingBankDraftEntity>,
        matchSet: DraftCandidateMatchSet? = null,
    ): String = sha256Hex(
        buildString {
            append(scope.scopeId).append('|')
            append(filter.reviewStatus?.name ?: "ALL").append('|')
            append(filter.duplicatesOnly).append('|')
            append(scope.draftScopeComplete).append('|')
            append(filter.eventId).append(':').append(filter.eventRevision).append('|')
            matchSet?.let {
                append(it.outcome.name).append(':')
                append(it.conflictingDraftCount).append(':')
                append(it.pendingDatabaseComplete).append('|')
            }
            visible.forEach { append(it.id).append(':').append(it.reviewRevision).append(';') }
        },
    )

    private suspend fun visibleDrafts(filter: DraftFilter): DraftScan {
        if (filter.eventId != null) {
            val event = queries.noteEvent(filter.eventId)
                ?: return DraftScan(emptyList(), truncated = true)
            if (event.revision != filter.eventRevision) {
                throw staleFrom("$.event_ref", requireCapture(event.captureId))
            }
            val matchSet = queries.matchDraftCandidates(
                eventId = filter.eventId,
                authorizedDraftIds = scope.draftIds,
                pendingDatabaseComplete = scope.draftScopeComplete,
            ) ?: return DraftScan(emptyList(), truncated = true)
            return DraftScan(
                rows = matchSet.candidates.map { it.draft },
                truncated = !matchSet.pendingDatabaseComplete,
                matchSet = matchSet,
            )
        }
        if (scope.draftIds.isEmpty()) {
            return DraftScan(emptyList(), truncated = !scope.draftScopeComplete)
        }
        val visible = mutableListOf<PendingBankDraftEntity>()
        var offset = 0
        var pages = 0
        var truncated = !scope.draftScopeComplete
        while (true) {
            if (pages >= MAX_SCAN_PAGES) {
                truncated = true
                break
            }
            pages += 1
            val page = queries.draftCandidates(
                DraftCandidateQuery(
                    reviewStatus = filter.reviewStatus,
                    onlyPossibleDuplicates = filter.duplicatesOnly,
                    bounds = QueryPageRequest(MAX_OBSERVATION_ROWS, offset),
                ),
            )
            visible.addAll(page.items.filter { it.id in scope.draftIds })
            if (!page.hasMore) break
            offset = page.nextOffset ?: break
        }
        return DraftScan(rows = visible, truncated = truncated)
    }

    private suspend fun issueOperationRef(
        operationKey: String,
        eventId: Long?,
        draftId: Long?,
    ): String? {
        return issueOperationRefFromJournal(operationKey, eventId, draftId)
    }

    private suspend fun issueOperationRefFromJournal(
        operationKey: String,
        eventId: Long?,
        draftId: Long?,
    ): String? {
        val operation = queries.workflowOperation(operationKey) ?: return null
        return issueOperationReference(operation, eventId, draftId)
    }

    private fun issueOperationReference(
        operation: WorkflowOperation,
        eventId: Long? = null,
        draftId: Long? = null,
    ): String {
        val change = operation.change
        return registry.issue(
            AgentReference(
                handle = registry.nextHandle(),
                kind = AgentReferenceKind.OPERATION,
                scopeId = scope.scopeId,
                subjectId = change?.let { workflowChangeSubjectId(it) },
                eventId = eventId
                    ?: (change as? WorkflowChange.EventStored)?.eventId
                    ?: (change as? WorkflowChange.EventRevised)?.eventId,
                draftId = draftId ?: (change as? WorkflowChange.DraftCategorySelected)?.draftId,
                operationKey = operation.idempotencyKey,
                operationCommand = operation.command,
                operationDigest = operation.payloadDigest,
            ),
        )
    }

    private fun workflowChangeSubjectId(change: WorkflowChange): Long? = when (change) {
        is WorkflowChange.EventStored -> change.eventId
        is WorkflowChange.EventRevised -> change.eventId
        is WorkflowChange.LinkDecided -> change.linkId
        is WorkflowChange.DraftCategorySelected -> change.draftId
        is WorkflowChange.CaptureSaved -> change.captureId
        is WorkflowChange.CaptureRevised -> change.captureId
        is WorkflowChange.CaptureDeleted -> change.captureId
        is WorkflowChange.LinkUnlinked -> change.linkId
        else -> null
    }

    private fun issueProposalRef(link: LinkOperation, eventId: Long, draftId: Long): String =
        registry.issue(
            AgentReference(
                handle = registry.nextHandle(),
                kind = AgentReferenceKind.PROPOSAL,
                scopeId = scope.scopeId,
                subjectId = link.linkId,
                eventId = eventId,
                draftId = draftId,
                linkId = link.linkId,
                linkState = link.state.name,
                linkRevision = link.revision,
            ),
        )

    private suspend fun currentEffectMatches(
        change: WorkflowChange,
        reference: AgentReference,
    ): Boolean = when (change) {
        is WorkflowChange.EventStored -> queries.noteEvent(change.eventId)?.let {
            changeRevisionMatches(it.revision, change.revision)
        } ?: false
        is WorkflowChange.EventRevised -> queries.noteEvent(change.eventId)?.let {
            changeRevisionMatches(it.revision, change.revision)
        } ?: false
        is WorkflowChange.LinkDecided -> {
            val link = queries.linkOperation(change.linkId)
            link != null && link.revision == change.revision && link.state == change.state
        }
        is WorkflowChange.DraftCategorySelected -> {
            val draft = queries.draftCandidate(change.draftId)
            draft != null && draft.reviewCategoryResolved &&
                draft.reviewCategoryId == change.categoryId
        }
        else -> false
    }

    private fun changeRevisionMatches(current: Int, recorded: Int): Boolean = current == recorded

    private fun changeJson(change: WorkflowChange?): Any = when (change) {
        null -> JSONObject.NULL
        is WorkflowChange.EventStored -> JSONObject().put("kind", "event").put("event_id", change.eventId).put("revision", change.revision)
        is WorkflowChange.EventRevised -> JSONObject().put("kind", "event").put("event_id", change.eventId).put("revision", change.revision)
        is WorkflowChange.LinkDecided -> JSONObject().put("kind", "link").put("link_id", change.linkId).put("state", change.state.name).put("revision", change.revision)
        is WorkflowChange.DraftCategorySelected -> JSONObject().put("kind", "draft_category").put("draft_id", change.draftId).put("category_id", change.categoryId).put("revision", change.revision)
        is WorkflowChange.CaptureSaved -> JSONObject().put("kind", "capture").put("capture_id", change.captureId).put("revision", change.revision)
        is WorkflowChange.CaptureRevised -> JSONObject().put("kind", "capture").put("capture_id", change.captureId).put("revision", change.revision)
        is WorkflowChange.CaptureDeleted -> JSONObject().put("kind", "capture").put("capture_id", change.captureId).put("revision", change.revision)
        is WorkflowChange.LinkUnlinked -> JSONObject().put("kind", "link").put("link_id", change.linkId)
        else -> JSONObject().put("kind", "unsupported_change")
    }

    private fun changeRevision(change: WorkflowChange?): Int? = when (change) {
        is WorkflowChange.EventStored -> change.revision
        is WorkflowChange.EventRevised -> change.revision
        is WorkflowChange.LinkDecided -> change.revision
        is WorkflowChange.DraftCategorySelected -> change.revision
        is WorkflowChange.CaptureSaved -> change.revision
        is WorkflowChange.CaptureRevised -> change.revision
        is WorkflowChange.CaptureDeleted -> change.revision
        else -> null
    }

    private fun unresolvedReasonFor(clarification: String): String? = when (clarification) {
        "none" -> null
        "amount" -> "Clarification required: amount"
        "date" -> "Clarification required: date"
        "time" -> "Clarification required: time"
        "merchant" -> "Clarification required: merchant"
        else -> null
    }

    private fun fieldPathFor(field: String): String = when (field) {
        "amount" -> "amount_ref"
        "time" -> "anchor_ref"
        "merchant" -> "merchant_ref"
        else -> "summary"
    }

    // ---- Masking -------------------------------------------------------------------------

    private fun mask(text: String): String = boundary.maskOutbound(text, MaskedPath.TOOL_RESULT)

    /**
     * The capture's own local calendar year: the trusted reference used only to resolve a
     * stated day/month that carries no year. Null when the capture has no truthful instant,
     * in which case a year-less date stays unresolved and is clarified instead of guessed.
     */
    private fun captureReferenceYear(capture: CaptureMessageEntity): Int? {
        val instant = capture.capturedAtEpochMillis ?: return null
        val zone = capture.captureTimezone
            ?.takeIf { runCatching { TimeZone.getTimeZone(it).id == it }.getOrDefault(false) }
            ?.let(TimeZone::getTimeZone)
            ?: TimeZone.getDefault()
        return Calendar.getInstance(zone).apply { timeInMillis = instant }.get(Calendar.YEAR)
    }

    /**
     * The capture's own local calendar day, `YYYY-MM-DD`, read in the zone it recorded.
     *
     * This is the only trusted reference for a day word such as "yesterday": it is derived from
     * the note's own stored instant, never from the current clock and never from a zone the
     * capture did not state. Null when either fact is missing, so the day stays unresolved.
     */
    private fun captureReferenceDate(capture: CaptureMessageEntity): String? =
        RelativeDayExpressions.localDate(capture.capturedAtEpochMillis, capture.captureTimezone)

    private fun rehydrateAnchorDate(token: String, referenceYear: Int?, path: String): String =
        try {
            boundary.vault.parseDate(token, referenceYear)
        } catch (_: TokenValidationException) {
            throw AgentToolException(
                AgentErrorCode.TEMPORAL_CONSTRAINT,
                path,
                "date_states_complete_calendar_date",
                message = "The masked date states no year and the capture records no local year. " +
                    "Ask for the full date instead of guessing one.",
            )
        }

    private fun parseAnchorTime(token: String, path: String): String =
        try {
            boundary.vault.parseTime(token)
        } catch (_: TokenValidationException) {
            throw AgentToolException(
                AgentErrorCode.TEMPORAL_CONSTRAINT,
                path,
                "time_states_wall_clock_value",
                message = "The masked time is not a wall-clock time the host can read. Ask for the time.",
            )
        }

    private fun maskAmount(amount: Long?): Any = amount?.let { mask("$it VND") } ?: JSONObject.NULL

    private fun maskDate(value: String?): Any = value?.let { mask(it) } ?: JSONObject.NULL

    // ---- Failures ------------------------------------------------------------------------

    private fun failed(
        tool: String,
        code: AgentErrorCode,
        issues: List<AgentIssue>,
    ): AgentToolExecution = AgentToolExecution(
        AgentEnvelope.error(
            tool = tool,
            code = code,
            issues = issues,
            dispatch = "not_dispatched",
            allowedTools = repairableTools(tool, code),
        ),
    )

    private fun repairableTools(tool: String, code: AgentErrorCode): List<String> {
        if (code.terminal) return emptyList()
        return when (code) {
            AgentErrorCode.STALE_OBSERVATION, AgentErrorCode.CURSOR_STALE ->
                listOf("observe", tool)
            AgentErrorCode.SOURCE_QUOTE_NOT_FOUND ->
                listOf("observe", "select_source")
            AgentErrorCode.UNKNOWN_REFERENCE, AgentErrorCode.REFERENCE_KIND_MISMATCH ->
                listOf("observe", tool)
            AgentErrorCode.MISSING_REQUIRED_MEANING, AgentErrorCode.TEMPORAL_CONSTRAINT ->
                listOf(tool, "ask_user")
            else -> listOf(tool)
        }
    }

    private suspend fun staleFrom(
        path: String,
        capture: CaptureMessageEntity,
        constraint: String = "observed_revision_matches_current",
    ): Nothing {
        val fresh = captureObservation(capture)
        throw AgentToolException(
            AgentErrorCode.STALE_OBSERVATION,
            path,
            constraint,
            JSONObject().put("fresh_observation_required", true),
            "The selected state changed. Inspect the fresh observation before choosing a new span.",
            subjectRef = fresh.getString("ref"),
            observations = listOf(fresh),
        )
    }

    private suspend fun staleDraft(path: String, draft: PendingBankDraftEntity): Nothing {
        val fresh = draftObservation(requireDraft(draft.id))
        throw AgentToolException(
            AgentErrorCode.STALE_OBSERVATION,
            path,
            "observed_review_revision_matches_current",
            JSONObject().put("fresh_observation_required", true),
            "The draft changed. Inspect the fresh review state before acting.",
            subjectRef = fresh.getString("ref"),
            observations = listOf(fresh),
        )
    }

    private fun deny(subjectRef: String?, reason: WorkflowDenialReason): Nothing {
        when (reason) {
            WorkflowDenialReason.USER_DECISION_REQUIRED -> throw AgentToolException(
                AgentErrorCode.USER_DECISION_REQUIRED,
                "$", "user_only_decision",
                message = "That decision belongs to the user and cannot be supplied by the agent.",
                subjectRef = subjectRef,
            )
            WorkflowDenialReason.CALLER_SCOPE -> throw AgentToolException(
                AgentErrorCode.REFERENCE_SCOPE_REFUSED, "$", "trusted_scope",
                message = "The request names a subject outside this authorized session.",
            )
            WorkflowDenialReason.EVENT_UNAVAILABLE -> throw subjectUnavailable("$", "The reading is no longer current.")
            WorkflowDenialReason.ATTACH_POLICY,
            WorkflowDenialReason.LINK_ALREADY_REJECTED,
            WorkflowDenialReason.MULTIPLE_ACTIVE_DRAFTS,
            WorkflowDenialReason.EVENT_ALREADY_RECORDED -> throw AgentToolException(
                AgentErrorCode.LINK_INELIGIBLE, "$", "trusted_link_policy",
                message = "The application's stored evidence does not permit this association.",
                subjectRef = subjectRef,
            )
            else -> throw AgentToolException(
                AgentErrorCode.EFFECT_UNKNOWN, "$", "settled_effect_readable",
                message = "The application refused the request without a settled effect.",
            )
        }
    }

    private fun subjectUnavailable(path: String, message: String): Nothing =
        throw AgentToolException(AgentErrorCode.SUBJECT_UNAVAILABLE, path, "subject_readable", null, message)

    // ---- Schema validation (flat catalog) ------------------------------------------------

    private fun validateFlat(schema: JSONObject, arguments: JSONObject): List<AgentIssue> {
        val issues = mutableListOf<AgentIssue>()
        val properties = schema.optJSONObject("properties") ?: return issues
        val required = schema.optJSONArray("required") ?: JSONArray()
        val requiredNames = (0 until required.length()).mapNotNull { required.optString(it, "").ifEmpty { null } }
        requiredNames.forEach { name ->
            if (!arguments.has(name)) {
                issues += AgentIssue(
                    "$.$name",
                    "required",
                    JSONObject().put("type", declaredTypeLabel(properties.optJSONObject(name))),
                    "Include this field; a nullable field uses JSON null.",
                )
            }
        }
        val keys = arguments.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val property = properties.optJSONObject(key)
            if (property == null) {
                issues += AgentIssue("$.$key", "additional_properties", JSONObject().put("allowed", JSONArray(properties.keys().asSequence().toList())), "Remove the extra field.")
                continue
            }
            val value = arguments.opt(key)
            val issue = validateValue(key, property, value)
            if (issue != null) issues += issue
        }
        return issues
    }

    private fun validateValue(
        name: String,
        schema: JSONObject,
        value: Any?,
    ): AgentIssue? {
        val path = "$.$name"
        val types = declaredTypes(schema)
        if (value == null || value === JSONObject.NULL) {
            return if ("null" in types || types.isEmpty()) null else AgentIssue(
                path, "type", JSONObject().put("type", declaredTypeLabel(schema)), "Supply a non-null value of this type.",
            )
        }
        if (types.isNotEmpty() && types.none { typeMatches(it, value) }) {
            return AgentIssue(
                path, "type", JSONObject().put("type", declaredTypeLabel(schema)), "Use the declared primitive type.",
            )
        }
        val enumValues = schema.optJSONArray("enum")
        if (enumValues != null) {
            val allowed = (0 until enumValues.length()).mapNotNull { enumValues.opt(it)?.takeUnless { item -> item === JSONObject.NULL } }
            if (allowed.none { it == value }) {
                return AgentIssue(
                    path, "enum", JSONObject().put("enum", JSONArray(allowed)), "Choose one declared value.",
                )
            }
        }
        if (value is String) {
            val minimum = schema.optInt("minLength", -1)
            if (minimum > 0 && value.length < minimum) {
                return AgentIssue(path, "min_length", JSONObject().put("minLength", minimum), "The value is too short.")
            }
            if (value.isBlank()) {
                return AgentIssue(path, "nonblank", JSONObject().put("type", "string"), "The value must not be blank.")
            }
        }
        if (value is Number) {
            val minimum = schema.optInt("minimum", Int.MIN_VALUE)
            val maximum = schema.optInt("maximum", Int.MAX_VALUE)
            val number = value.toDouble()
            if (number < minimum || number > maximum) {
                return AgentIssue(
                    path, "range", JSONObject().put("minimum", minimum).put("maximum", maximum), "Use a value inside the declared range.",
                )
            }
        }
        return null
    }

    private fun declaredTypes(schema: JSONObject?): Set<String> {
        if (schema == null) return emptySet()
        return when (val declared = schema.opt("type")) {
            is String -> setOf(declared)
            is JSONArray -> (0 until declared.length()).mapNotNull { declared.optString(it, "").ifEmpty { null } }.toSet()
            else -> emptySet()
        }
    }

    private fun declaredTypeLabel(schema: JSONObject?): String =
        if (schema == null) "unknown" else declaredTypes(schema).joinToString("|")

    private fun typeMatches(type: String, value: Any): Boolean = when (type) {
        "string" -> value is String
        "integer" -> value is Int || value is Long || value is Short || value is Byte ||
            (value is Number && value.toDouble() % 1.0 == 0.0)
        "boolean" -> value is Boolean
        "object" -> value is JSONObject
        "array" -> value is JSONArray
        else -> false
    }

    private fun optionalString(arguments: JSONObject, key: String): String? =
        if (!arguments.has(key) || arguments.isNull(key)) null else arguments.getString(key)

    private fun optionalReference(
        arguments: JSONObject,
        key: String,
        kind: AgentReferenceKind,
        path: String,
    ): AgentReference? {
        val handle = optionalString(arguments, key) ?: return null
        return registry.resolve(handle, kind, path)
    }

    private data class DraftScan(
        val rows: List<PendingBankDraftEntity>,
        val truncated: Boolean,
        val matchSet: DraftCandidateMatchSet? = null,
    )

    private data class DraftFilter(
        val reviewStatus: IntakeReviewStatus?,
        val duplicatesOnly: Boolean,
        val eventId: Long? = null,
        val eventRevision: Int? = null,
    )

    private companion object {
        const val DRAFT_PAGE_SIZE = 10
        const val MAX_OBSERVATION_ROWS = 200
        const val MAX_DRAFT_CONTEXT_LINKS = 20
        const val MAX_SCAN_PAGES = 50
    }
}

/** Convenience for turning an error code into one thrown issue. */
private fun AgentErrorCode.asIssue(
    path: String,
    constraint: String,
    message: String,
    expected: JSONObject? = null,
    subjectRef: String? = null,
): Nothing = throw AgentToolException(this, path, constraint, expected, message, subjectRef)

/** Reads the single canonical declaration file copied from tools/slm at build time. */
object AgentToolDeclarations {
    const val ASSET_NAME = "s8_agent_tools_v8.json"

    fun load(open: (String) -> java.io.InputStream): List<HostedToolDeclaration> {
        val root = JSONObject(open(ASSET_NAME).bufferedReader().use { it.readText() })
        require(root.getString("schema") == AgentInterface.TOOL_SCHEMA) {
            "Unsupported bundled agent tool schema."
        }
        val tools = root.getJSONArray("tools")
        val declarations = (0 until tools.length()).map { index ->
            val tool = tools.getJSONObject(index)
            HostedToolDeclaration(
                name = tool.getString("name"),
                description = tool.getString("description"),
                parameters = tool.getJSONObject("parameters"),
            )
        }
        require(declarations.map(HostedToolDeclaration::name).toSet() == AgentInterface.TOOL_NAMES) {
            "The bundled agent tool catalog is incomplete or contains extra tools."
        }
        return declarations
    }
}
