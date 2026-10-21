package io.github.triet4p.aegisf.data

import androidx.room.withTransaction
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner
import io.github.triet4p.aegisf.data.laya.LayaContextDecision
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.laya.LayaEventReading
import io.github.triet4p.aegisf.data.laya.LayaRuntimeContract

/**
 * The model revision a prediction is recorded against.
 *
 * It names the pinned export the runtime accepts together with the budget pins that decide
 * what a request may carry, so a build that runs a different model — or the same model under
 * a different budget — cannot be compared with a prediction an older build made.
 */
val CURRENT_MODEL_REVISION: String = "${LayaRuntimeContract.PINNED_EXPORT_REVISION}/" +
    "${LayaRuntimeContract.MAX_LEN}/${LayaRuntimeContract.HEAD_MAX_LEN}"

/**
 * The candidate-set construction a prediction is recorded against.
 *
 * The version names how the evaluated set was chosen. A change to that construction changes
 * which categories a description is offered, so the predictions made under the old one stop
 * describing what the app would do now.
 */
const val CURRENT_CANDIDATE_SET_VERSION: String = "laya-candidates-v1"

/**
 * The state a prediction was made against.
 *
 * Every dependency is a digest, so a later read can tell whether the context, the category
 * catalog, the remembered rules, the model, or the candidate-set construction have moved on
 * since the prediction was made, without keeping a copy of anything the prediction was made
 * from.
 */
data class PredictionDependencies(
    /** Digest of the linked events, the links, and the draft's own review state. */
    val contextRevision: String,
    /** Digest of the category catalog. */
    val categoryRevision: String,
    /** Digest of the remembered category rules. */
    val ruleRevision: String,
    /** The model revision that made the prediction. */
    val modelRevision: String,
    /** The candidate-set construction that made the prediction. */
    val candidateSetVersion: String,
    /** The stored event readings the prediction's input was composed from, ascending. */
    val eventIds: List<Long>,
)

/** The effective context of one draft, together with the dependencies it was built from. */
data class EffectiveDraftContext(
    val draftId: Long,
    val context: LayaEffectiveContext,
    val dependencies: PredictionDependencies,
)

/** One recorded prediction about a draft. */
data class ClassificationPrediction(
    /** Where the decision came from. */
    val source: PredictionSource,
    /** The category the decision pointed at, or null when it pointed at none. */
    val suggestedCategoryId: Long?,
    /** The evaluated category ids, in the order the model evaluated them. */
    val candidateIds: List<Long>,
    /** The calibrated share of each evaluated category, in the same order. */
    val probabilities: List<Double>,
    /** The calibrated no-fit share, or null when no distribution was produced. */
    val unknownShare: Double?,
    /** The one Jev sharpness value of the same distribution, or null when none was produced. */
    val confidence: Double?,
    /** The digest of the composed model input. */
    val effectiveInputDigest: String,
)

/** What storing one prediction did. */
sealed interface SnapshotWriteResult {
    /** The prediction is now the stored snapshot of the draft. */
    data class Stored(val snapshotId: Long) : SnapshotWriteResult

    /**
     * The context moved on while the prediction was being made, so the late result was
     * refused: it describes a state that no longer exists and must not replace the newer one.
     */
    data object Stale : SnapshotWriteResult

    /**
     * The user already chose a category for this draft, so the prediction was not stored over
     * their choice.
     */
    data object UserSelectionPreserved : SnapshotWriteResult

    /** The draft does not exist. */
    data object NotFound : SnapshotWriteResult
}

/** A stored prediction, read back with the state it is in. */
data class StoredPrediction(
    val draftId: Long,
    val source: PredictionSource,
    val suggestedCategoryId: Long?,
    val candidateIds: List<Long>,
    val probabilities: List<Double>,
    val unknownShare: Double?,
    val confidence: Double?,
    val effectiveInputDigest: String,
    val eventIds: List<Long>,
    val predictionTime: Long,
    /** Why the prediction no longer applies, or null while it does. */
    val staleReason: PredictionStaleReason?,
)

/**
 * Owns the effective context of a bank draft and the prediction snapshot stored about it.
 *
 * The rules this class owns, and which no caller can bypass:
 *
 * - A draft's effective context is the permitted description the review form works with plus
 *   the stored readings of the events that are linked to THAT draft. A note with several
 *   events contributes only the readings of the events linked to the draft being reviewed, so
 *   a multi-event note is never copied into every draft.
 * - A prediction is stored only against the state it was actually made in. A result that
 *   arrives after the context moved on is refused, so a late result can never replace a
 *   newer context.
 * - A prediction is never stored over the user's own choice: when the user already resolved
 *   the draft's category, the stored snapshot is marked stale and the late prediction is
 *   refused.
 * - The bank fields are never touched. Storing a prediction, marking it stale, or refusing
 *   one changes no amount, date, time, memo, type, or category of the draft.
 * - The composed model input is never stored; only its digest is, so a prompt stays
 *   ephemeral.
 *
 * The class is internal: the stored prediction is written through the application's one
 * command boundary, so a caller cannot store a prediction around the boundary's scope,
 * idempotency, and freshness rules.
 */
internal class ClassificationSnapshotRepository(
    private val database: LedgerDatabase,
    private val clock: LedgerClock = SystemLedgerClock,
    private val modelRevision: String = CURRENT_MODEL_REVISION,
    private val candidateSetVersion: String = CURRENT_CANDIDATE_SET_VERSION,
) {
    private val snapshotDao = database.classificationSnapshotDao()
    private val draftDao = database.pendingBankDraftDao()
    private val linkDao = database.contextLinkDao()
    private val eventDao = database.noteEventDao()
    private val categoryDao = database.categoryDao()
    private val ruleDao = database.rememberedCategoryRuleDao()

    /**
     * The effective context of one draft: the review's own description plus the stored
     * readings of the events linked to that draft, and the dependency state they were read
     * in.
     *
     * The readings are returned in ascending event id, so the composed input of the same
     * draft is the same text every time, whatever order the links were made in.
     */
    suspend fun effectiveContextFor(draftId: Long, primaryText: String): EffectiveDraftContext? {
        val draft = draftDao.findById(draftId) ?: return null
        val state = contextState(draftId)
        val readings = ArrayList<LayaEventReading>()
        for (link in state.activeLinks) {
            val event = state.events.firstOrNull { it.id == link.eventId } ?: continue
            val text = event.normalizedText?.trim().orEmpty()
            if (text.isEmpty()) continue
            readings.add(LayaEventReading(event.id, text))
        }
        readings.sortBy { it.eventId }
        return EffectiveDraftContext(
            draftId = draftId,
            context = LayaEffectiveContext(primaryText = primaryText, eventReadings = readings),
            dependencies = dependencies(draft, state),
        )
    }

    /**
     * Stores one prediction about a draft, against the dependency state it was made in.
     *
     * The prediction is refused when the state moved on ([SnapshotWriteResult.Stale]) or when
     * the user already chose the draft's category
     * ([SnapshotWriteResult.UserSelectionPreserved]). A stored snapshot is replaced only by a
     * prediction that was made against the state that is current now.
     */
    suspend fun recordPrediction(
        draftId: Long,
        prediction: ClassificationPrediction,
        expected: PredictionDependencies,
    ): SnapshotWriteResult = database.withTransaction {
        val draft = draftDao.findById(draftId) ?: return@withTransaction SnapshotWriteResult.NotFound
        // The user's own choice is checked first: when the draft already carries the
        // category the user chose, that choice is the reason a prediction is not stored,
        // whatever else moved on in the meantime.
        if (draft.reviewCategoryResolved) {
            snapshotDao.markStaleForDraft(
                draftId,
                PredictionStaleReason.USER_CATEGORY_CHOSEN.name,
            )
            return@withTransaction SnapshotWriteResult.UserSelectionPreserved
        }
        val state = contextState(draftId)
        val current = dependencies(draft, state)
        if (current != expected) return@withTransaction SnapshotWriteResult.Stale
        val now = clock.nowEpochMillis()
        val existing = snapshotDao.findForDraft(draftId)
        if (existing == null) {
            val id = snapshotDao.insert(
                ClassificationSnapshotEntity(
                    draftId = draftId,
                    contextRevision = current.contextRevision,
                    categoryRevision = current.categoryRevision,
                    ruleRevision = current.ruleRevision,
                    modelRevision = current.modelRevision,
                    candidateSetVersion = current.candidateSetVersion,
                    decisionSource = prediction.source.name,
                    suggestedCategoryId = prediction.suggestedCategoryId,
                    candidateIds = prediction.candidateIds.joinToString(","),
                    probabilities = prediction.probabilities.joinToString(","),
                    unknownShare = prediction.unknownShare,
                    confidence = prediction.confidence,
                    effectiveInputDigest = prediction.effectiveInputDigest,
                    eventIds = current.eventIds.joinToString(","),
                    predictionTime = now,
                    staleReason = null,
                ),
            )
            SnapshotWriteResult.Stored(id)
        } else {
            snapshotDao.replace(
                draftId = draftId,
                contextRevision = current.contextRevision,
                categoryRevision = current.categoryRevision,
                ruleRevision = current.ruleRevision,
                modelRevision = current.modelRevision,
                candidateSetVersion = current.candidateSetVersion,
                decisionSource = prediction.source.name,
                suggestedCategoryId = prediction.suggestedCategoryId,
                candidateIds = prediction.candidateIds.joinToString(","),
                probabilities = prediction.probabilities.joinToString(","),
                unknownShare = prediction.unknownShare,
                confidence = prediction.confidence,
                effectiveInputDigest = prediction.effectiveInputDigest,
                eventIds = current.eventIds.joinToString(","),
                predictionTime = now,
            )
            SnapshotWriteResult.Stored(existing.id)
        }
    }

    /**
     * The stored prediction of one draft, with the state it is in.
     *
     * A prediction whose dependencies have moved on is marked stale and that state is stored,
     * so the staleness survives a restart instead of being rediscovered on every read. The
     * order the reasons are checked in is fixed, so the same change always yields the same
     * reason.
     */
    suspend fun snapshotFor(draftId: Long): StoredPrediction? {
        val stored = snapshotDao.findForDraft(draftId) ?: return null
        val draft = draftDao.findById(draftId) ?: return stored.toStoredPrediction()
        if (stored.staleReason != null) return stored.toStoredPrediction()
        val state = contextState(draftId)
        val current = dependencies(draft, state)
        val reason = staleReason(stored, current, state, draft)
        if (reason == null) return stored.toStoredPrediction()
        snapshotDao.markStaleForDraft(draftId, reason.name)
        return stored.copy(staleReason = reason.name).toStoredPrediction()
    }

    /** The stored snapshot row of one draft, without re-evaluating its freshness. */
    suspend fun storedSnapshot(draftId: Long): ClassificationSnapshotEntity? =
        snapshotDao.findForDraft(draftId)

    /**
     * The reason a stored prediction no longer applies, or null while it does.
     *
     * The order is fixed: the user's own choice first, then the context, then the category
     * catalog, the remembered rules, the model, and the candidate-set construction.
     */
    private fun staleReason(
        stored: ClassificationSnapshotEntity,
        current: PredictionDependencies,
        state: DraftContextState,
        draft: PendingBankDraftEntity,
    ): PredictionStaleReason? = when {
        draft.reviewCategoryResolved -> PredictionStaleReason.USER_CATEGORY_CHOSEN
        stored.contextRevision != current.contextRevision ->
            if (state.sourceChanged) {
                PredictionStaleReason.SOURCE_CHANGED
            } else {
                PredictionStaleReason.CONTEXT_CHANGED
            }
        stored.categoryRevision != current.categoryRevision -> PredictionStaleReason.CATEGORY_CHANGED
        stored.ruleRevision != current.ruleRevision -> PredictionStaleReason.RULE_CHANGED
        stored.modelRevision != current.modelRevision -> PredictionStaleReason.MODEL_CHANGED
        stored.candidateSetVersion != current.candidateSetVersion ->
            PredictionStaleReason.CANDIDATE_SET_CHANGED
        else -> null
    }

    /** The links and the readings one draft's context is built from. */
    private data class DraftContextState(
        val links: List<ContextLinkEntity>,
        val activeLinks: List<ContextLinkEntity>,
        val events: List<NoteEventEntity>,
        val sourceChanged: Boolean,
    )

    private suspend fun contextState(draftId: Long): DraftContextState {
        val links = linkDao.findForDraft(draftId)
        val activeLinks = links.filter {
            it.state == ContextLinkState.PROPOSED || it.state == ContextLinkState.ACCEPTED
        }
        val events = ArrayList<NoteEventEntity>()
        val seen = HashSet<Long>()
        for (link in links) {
            if (!seen.add(link.eventId)) continue
            eventDao.findById(link.eventId)?.let { events.add(it) }
        }
        events.sortBy { it.id }
        val sourceChanged = events.any {
            it.unresolvedReason == SOURCE_EDITED || it.unresolvedReason == SOURCE_DELETED
        }
        return DraftContextState(links, activeLinks, events, sourceChanged)
    }

    /** The dependency state of one draft, as the digests a prediction is recorded against. */
    private suspend fun dependencies(
        draft: PendingBankDraftEntity,
        state: DraftContextState,
    ): PredictionDependencies {
        val contextParts = ArrayList<String?>()
        contextParts.add(draft.evidenceDigest)
        contextParts.add(draft.reviewRevision.toString())
        contextParts.add(draft.reviewMemo)
        contextParts.add(draft.reviewMemoEdited.toString())
        for (link in state.links) {
            contextParts.add(link.id.toString())
            contextParts.add(link.state.name)
            contextParts.add(link.revision.toString())
        }
        for (event in state.events) {
            contextParts.add(event.id.toString())
            contextParts.add(event.revision.toString())
            contextParts.add(event.sourceRevision.toString())
            contextParts.add(event.status.name)
            contextParts.add(event.unresolvedReason)
            contextParts.add(event.normalizedText)
        }
        val eventIds = state.activeLinks
            .map { it.eventId }
            .distinct()
            .sorted()
        return PredictionDependencies(
            contextRevision = canonicalDependencyDigest(contextParts),
            categoryRevision = categoryCatalogRevision(categoryDao.findAll()),
            ruleRevision = canonicalDependencyDigest(ruleRevisionParts()),
            modelRevision = modelRevision,
            candidateSetVersion = candidateSetVersion,
            eventIds = eventIds,
        )
    }

    /** The digest parts of the remembered rules: every rule's key and target. */
    private suspend fun ruleRevisionParts(): List<String?> {
        val parts = ArrayList<String?>()
        for (rule in ruleDao.findAll().sortedBy { it.patternKey }) {
            parts.add(rule.patternKey)
            parts.add(rule.categoryId.toString())
        }
        return parts
    }

    /** The stored row, read back as the review's own view of it. */
    private fun ClassificationSnapshotEntity.toStoredPrediction(): StoredPrediction = StoredPrediction(
        draftId = draftId,
        source = PredictionSource.valueOf(decisionSource),
        suggestedCategoryId = suggestedCategoryId,
        candidateIds = candidateIds.toIdList(),
        probabilities = probabilities.toDoubleList(),
        unknownShare = unknownShare,
        confidence = confidence,
        effectiveInputDigest = effectiveInputDigest,
        eventIds = eventIds.toIdList(),
        predictionTime = predictionTime,
        staleReason = staleReason?.let { PredictionStaleReason.valueOf(it) },
    )
}

/** The comma-separated ids of a stored list. */
internal fun String.toIdList(): List<Long> =
    if (isEmpty()) emptyList() else split(",").map { it.toLong() }

/** The comma-separated shares of a stored list. */
private fun String.toDoubleList(): List<Double> =
    if (isEmpty()) emptyList() else split(",").map { it.toDouble() }

/**
 * The prediction one completed decision describes.
 *
 * A direct decision (an explicit correction or a category-name match) never ran the model, so
 * it carries no evaluated set, no share, and no sharpness; a model decision carries exactly
 * the categories the model evaluated, in the order it evaluated them, with their calibrated
 * shares and the calibrated no-fit share of the same distribution.
 */
fun LayaContextDecision.classificationPrediction(): ClassificationPrediction {
    val source = when (val decision = this.decision) {
        is LayaCandidatePlanner.Decision.Category -> when (decision.source) {
            LayaCandidatePlanner.SuggestionSource.EXPLICIT_CORRECTION ->
                PredictionSource.EXPLICIT_CORRECTION
            LayaCandidatePlanner.SuggestionSource.CATEGORY_METADATA ->
                PredictionSource.CATEGORY_METADATA
            LayaCandidatePlanner.SuggestionSource.MODEL -> PredictionSource.MODEL
        }
        is LayaCandidatePlanner.Decision.Unknown -> PredictionSource.NO_FIT
    }
    val suggested = (decision as? LayaCandidatePlanner.Decision.Category)?.categoryId
    return ClassificationPrediction(
        source = source,
        suggestedCategoryId = suggested,
        candidateIds = evaluation?.categoryIds.orEmpty(),
        probabilities = evaluation?.probabilities.orEmpty(),
        unknownShare = evaluation?.unknownShare,
        confidence = evaluation?.confidence,
        effectiveInputDigest = effectiveInput.digest,
    )
}
