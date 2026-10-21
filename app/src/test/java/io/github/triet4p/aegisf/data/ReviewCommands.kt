package io.github.triet4p.aegisf.data

/**
 * The draft-review writes as the production command boundary offers them.
 *
 * The boundary is the only public way to change a draft, so a test that drives the review
 * drives it the way the app does: the preview first, and then the confirmation that
 * carries the preview's binding. The answers are reported in the storage layer's own
 * vocabulary, so a test can state what it observes without restating the boundary.
 */
internal class ReviewCommands(
    private val commands: WorkflowCommandService,
    private val drafts: PendingBankDraftDao,
) {

    /** Stores the user's explicit edits on a still-pending draft. */
    suspend fun saveReview(
        draftId: Long,
        expectedRevision: Int,
        edits: BankDraftReviewEdits,
    ): BankDraftReviewResult {
        val outcome = commands.saveDraftReview(
            scope = WorkflowCallerScope.User,
            request = SaveDraftReviewRequest(
                idempotencyKey = "test-review:$draftId:$expectedRevision:${nextKey()}",
                draftId = draftId,
                expectedRevision = expectedRevision,
                edits = edits,
            ),
        )
        return outcome.toReviewResult()
    }

    /**
     * Confirms a draft as a ledger record.
     *
     * The preview is asked first, the way the screen asks it, and the confirmation that
     * follows carries the preview's binding: a preview that has gone stale is a conflict
     * and writes nothing.
     */
    suspend fun confirmDraft(
        draftId: Long,
        expectedRevision: Int,
        edits: BankDraftReviewEdits,
    ): BankDraftReviewResult {
        val preview = commands.previewDraftConfirmation(
            scope = WorkflowCallerScope.User,
            request = PreviewDraftConfirmationRequest(draftId = draftId, edits = edits),
        )
        when (preview) {
            is DraftConfirmationPreview.NeedsCorrection ->
                return BankDraftReviewResult.NeedsCorrection(preview.fields)
            is DraftConfirmationPreview.NotFound -> return BankDraftReviewResult.NotFound
            is DraftConfirmationPreview.Denied ->
                return BankDraftReviewResult.CategoryUnavailable
            // The preview refuses a draft that is already terminal.
            is DraftConfirmationPreview.Conflict -> return BankDraftReviewResult.AlreadyReviewed
            is DraftConfirmationPreview.Ready -> {
                if (preview.expectedRevision != expectedRevision) {
                    return BankDraftReviewResult.Changed
                }
            }
        }
        val ready = preview as DraftConfirmationPreview.Ready
        val outcome = commands.confirmDraft(
            scope = WorkflowCallerScope.User,
            request = ConfirmDraftRequest(
                idempotencyKey = "test-confirm:$draftId:$expectedRevision:${nextKey()}",
                draftId = draftId,
                expectedRevision = expectedRevision,
                expectedBinding = ready.binding,
                edits = edits,
            ),
        )
        return outcome.toReviewResult()
    }

    /** Dismisses a still-pending draft. */
    suspend fun dismissDraft(draftId: Long): BankDraftReviewResult {
        val draft = drafts.findById(draftId) ?: return BankDraftReviewResult.NotFound
        val outcome = commands.dismissDraft(
            scope = WorkflowCallerScope.User,
            request = DismissDraftRequest(
                idempotencyKey = "test-dismiss:$draftId:${draft.reviewRevision}:${nextKey()}",
                draftId = draftId,
                expectedRevision = draft.reviewRevision,
            ),
        )
        return outcome.toReviewResult()
    }

    /** How one outcome of the boundary is reported in the storage layer's vocabulary. */
    private fun WorkflowCommandOutcome.toReviewResult(): BankDraftReviewResult = when (this) {
        is WorkflowCommandOutcome.Applied -> when (val change = change) {
            is WorkflowChange.DraftReviewSaved -> BankDraftReviewResult.Saved(change.revision)
            is WorkflowChange.DraftConfirmed -> BankDraftReviewResult.Confirmed(change.transactionId)
            is WorkflowChange.DraftDismissed -> BankDraftReviewResult.Dismissed
            else -> BankDraftReviewResult.Changed
        }
        is WorkflowCommandOutcome.NoChange -> when (val change = change) {
            is WorkflowChange.DraftReviewSaved -> BankDraftReviewResult.Saved(change.revision)
            // A draft that is already decided answers with the state it is really in —
            // confirmed or dismissed — and the review reports it the way the review has
            // always reported it: the draft is terminal and no second record was written.
            else -> BankDraftReviewResult.AlreadyReviewed
        }
        is WorkflowCommandOutcome.NeedsClarification -> BankDraftReviewResult.NeedsCorrection(
            BankDraftReviewField.entries.filter { it.name == field }.toSet(),
        )
        is WorkflowCommandOutcome.Denied -> when (reason) {
            WorkflowDenialReason.CATEGORY_UNAVAILABLE -> BankDraftReviewResult.CategoryUnavailable
            else -> BankDraftReviewResult.AlreadyReviewed
        }
        is WorkflowCommandOutcome.Conflict -> BankDraftReviewResult.Changed
        WorkflowCommandOutcome.NotFound -> BankDraftReviewResult.NotFound
        is WorkflowCommandOutcome.Failed -> BankDraftReviewResult.Changed
        WorkflowCommandOutcome.Cancelled -> BankDraftReviewResult.Changed
    }
}

/**
 * The review commands of one database, built the way the app builds its boundary.
 *
 * The classifier is stood in for: a financial command never asks the local model
 * anything, and the model is not installed on a host test machine.
 */
internal fun reviewCommands(
    database: LedgerDatabase,
    clock: LedgerClock = SystemLedgerClock,
): ReviewCommands = ReviewCommands(
    commands = WorkflowCommandService(
        database = database,
        clock = clock,
        engine = object : ClassificationEngine {
            override suspend fun classify(
                context: io.github.triet4p.aegisf.data.laya.LayaEffectiveContext,
                categories: List<CategoryEntity>,
            ): ClassificationEngineResult = ClassificationEngineResult.Failed("not used here")
        },
    ),
    drafts = database.pendingBankDraftDao(),
)

/** A key that names one attempt, so a repeated call is a fresh request and not a replay. */
private fun nextKey(): String = java.util.UUID.randomUUID().toString()
