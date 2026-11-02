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
    /** Digest of the stored source description and linked event readings. */
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
    /** Whether the composed input was source text or a hosted English translation. */
    val classificationInputKind: ClassificationInputKind,
    /** Exact current source/draft/link revisions bound to a hosted translation. */
    val translationSourceRevision: String?,
    /** The current snapshot identity, fencing a late answer from replacing newer input. */
    val snapshotInputIdentity: String?,
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
    /** Whether the exact input was source-authored or machine-translated. */
    val classificationInputKind: ClassificationInputKind,
    /** One-way identity of the actual composed input and its source binding. */
    val classificationInputIdentity: String,
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
 * - A hosted English translation may replace the composed source text only after the current
 *   draft and every active link/event/capture revision are revalidated. The English input is
 *   primary text only; raw source readings are not appended again or stored with the snapshot.
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
    private val captureDao = database.captureMessageDao()
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
        val readings = activeReadings(state)
        val snapshotInputIdentity = snapshotDao.findForDraft(draftId)?.classificationInputIdentity
        return EffectiveDraftContext(
            draftId = draftId,
            context = LayaEffectiveContext(primaryText = primaryText, eventReadings = readings),
            dependencies = dependencies(
                draft = draft,
                state = state,
                readings = readings,
                categories = categoryDao.findAll(),
                snapshotInputIdentity = snapshotInputIdentity,
            ),
        )
    }

    /**
     * Re-reads the complete current Laya source context before the agent may use a translation.
     * It supports the draft description alone or every active linked event reading.
     */
    suspend fun translationSource(
        binding: ClassificationTranslationBinding,
    ): ClassificationTranslationSource? {
        val draft = draftDao.findById(binding.draftId) ?: return null
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.reviewCategoryResolved) return null
        val state = contextState(binding.draftId)
        val current = currentTranslationBinding(binding, draft, state) ?: return null
        val readings = activeReadings(state)
        return ClassificationTranslationSource(
            binding = current,
            primaryText = draft.classificationDescription().trim(),
            eventReadings = readings,
            eventCaptureIds = readings.mapNotNull { reading ->
                state.events.firstOrNull { it.id == reading.eventId }?.captureId
            },
        )
    }

    /**
     * Builds an English-only Laya input after rechecking the exact source binding.
     * Stored source context remains the dependency state; translation text is never made evidence.
     */
    suspend fun effectiveContextForTranslated(
        draftId: Long,
        input: HostedEnglishClassificationInput,
    ): EffectiveDraftContext? {
        val draft = draftDao.findById(draftId) ?: return null
        if (draft.reviewStatus != IntakeReviewStatus.PENDING || draft.reviewCategoryResolved) return null
        if (input.binding.draftId != draftId || input.text.isBlank()) return null
        val state = contextState(draftId)
        val current = currentTranslationBinding(input.binding, draft, state) ?: return null
        if (current != input.binding) return null
        val readings = activeReadings(state)
        val snapshotInputIdentity = snapshotDao.findForDraft(draftId)?.classificationInputIdentity
        return EffectiveDraftContext(
            draftId = draftId,
            context = LayaEffectiveContext(primaryText = input.text),
            dependencies = dependencies(
                draft = draft,
                state = state,
                readings = readings,
                categories = categoryDao.findAll(),
                classificationInputKind = ClassificationInputKind.HOSTED_ENGLISH_TRANSLATION,
                snapshotInputIdentity = snapshotInputIdentity,
            ),
        )
    }

    /**
     * The readings one draft's model input actually carries, in ascending event id.
     *
     * Only active links contribute, and a reading with no text contributes nothing: it is
     * not part of what the model is asked. The same list feeds both the composed context
     * and the dependency digest, so the stored revision is the identity of the exact text
     * the prediction is about.
     */
    private fun activeReadings(state: DraftContextState): List<LayaEventReading> {
        val readings = ArrayList<LayaEventReading>()
        for (link in state.activeLinks) {
            val event = state.events.firstOrNull { it.id == link.eventId } ?: continue
            val text = event.normalizedText?.trim().orEmpty()
            if (text.isEmpty()) continue
            readings.add(LayaEventReading(event.id, text))
        }
        readings.sortBy { it.eventId }
        return readings
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
        val categories = categoryDao.findAll()
        val existing = snapshotDao.findForDraft(draftId)
        val current = dependencies(
            draft = draft,
            state = state,
            readings = activeReadings(state),
            categories = categories,
            classificationInputKind = expected.classificationInputKind,
            snapshotInputIdentity = existing?.classificationInputIdentity,
        )
        if (current != expected) {
            // A source/dependency change invalidates the late answer. A different prediction
            // with the same source has its own identity and wins without being marked stale.
            if (current.copy(snapshotInputIdentity = expected.snapshotInputIdentity) != expected) {
                markFirstReason(draftId, existing, current, state, draft, categories)
            }
            return@withTransaction SnapshotWriteResult.Stale
        }
        if (!suggestionResolves(prediction.suggestedCategoryId, categories)) {
            // The answer names a category the review may not offer — an archived, removed,
            // or never-assignable row. It is not stored as a choice; the prediction already
            // on the draft is marked stale under the same category reason.
            snapshotDao.markStaleForDraft(draftId, PredictionStaleReason.CATEGORY_CHANGED.name)
            return@withTransaction SnapshotWriteResult.Stale
        }
        val now = clock.nowEpochMillis()
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
                    classificationInputKind = current.classificationInputKind.name,
                    classificationInputIdentity = classificationInputIdentity(
                        current.classificationInputKind,
                        current.translationSourceRevision,
                        prediction.effectiveInputDigest,
                    ),
                    translationSourceRevision = current.translationSourceRevision,
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
                classificationInputKind = current.classificationInputKind.name,
                classificationInputIdentity = classificationInputIdentity(
                    current.classificationInputKind,
                    current.translationSourceRevision,
                    prediction.effectiveInputDigest,
                ),
                translationSourceRevision = current.translationSourceRevision,
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
        val categories = categoryDao.findAll()
        val current = dependencies(
            draft = draft,
            state = state,
            readings = activeReadings(state),
            categories = categories,
            classificationInputKind = ClassificationInputKind.valueOf(stored.classificationInputKind),
            snapshotInputIdentity = stored.classificationInputIdentity,
        )
        val reason = staleReason(stored, current, state, draft, categories)
        if (reason == null) return stored.toStoredPrediction()
        snapshotDao.markStaleForDraft(draftId, reason.name)
        return stored.copy(staleReason = reason.name).toStoredPrediction()
    }

    /** The stored snapshot row of one draft, without re-evaluating its freshness. */
    suspend fun storedSnapshot(draftId: Long): ClassificationSnapshotEntity? =
        snapshotDao.findForDraft(draftId)

    /**
     * Records that every prediction made against an older category catalog is stale.
     *
     * The command boundary calls this inside the transaction that changed the catalog, so
     * the stored staleness commits with the change: a reviewer reading the stored rows
     * afterwards sees the invalidation even when no classification job ever asked for the
     * draft again. A prediction that already carries the current revision is left alone.
     */
    suspend fun markCategoryCatalogChanged(): Int =
        snapshotDao.markStaleForCategoryRevision(
            PredictionStaleReason.CATEGORY_CHANGED.name,
            categoryCatalogRevision(categoryDao.findAll()),
        )

    /** The same in-transaction invalidation for a change of the remembered rules. */
    suspend fun markRememberedRulesChanged(): Int =
        snapshotDao.markStaleForRuleRevision(
            PredictionStaleReason.RULE_CHANGED.name,
            canonicalDependencyDigest(ruleRevisionParts()),
        )

    /**
     * Records that the user chose this draft's category, so the stored prediction must
     * never rank or be offered again.
     *
     * Called inside the transaction that stored the user's review, so the marker is
     * durable with the choice itself; a later read of the raw snapshot rows sees it
     * without having to recompute the dependency state first.
     */
    suspend fun markUserCategoryChosen(draftId: Long): Int =
        snapshotDao.markStaleForDraft(draftId, PredictionStaleReason.USER_CATEGORY_CHOSEN.name)

    /**
     * Records that the draft's working description changed, so a prediction made from the
     * previous wording is stale at once.
     *
     * An accepted edit that leaves the model's input alone — an amount or moment
     * correction — never calls this: nothing a model would be asked has changed.
     */
    suspend fun markDescriptionChanged(draftId: Long): Int =
        snapshotDao.markStaleForDraft(draftId, PredictionStaleReason.CONTEXT_CHANGED.name)

    /**
     * The reason a stored prediction no longer applies, or null while it does.
     *
     * The order is fixed: the user's own choice first, then the context, then the category
     * catalog, the remembered rules, the model, and the candidate-set construction. The
     * catalog step also resolves the stored suggestion by stable id against the live
     * catalog, so a prediction that pointed at a category which has since been archived,
     * renamed, or removed can never read back as current — whatever an older row's stored
     * digests say.
     */
    private fun staleReason(
        stored: ClassificationSnapshotEntity,
        current: PredictionDependencies,
        state: DraftContextState,
        draft: PendingBankDraftEntity,
        categories: List<CategoryEntity>,
    ): PredictionStaleReason? = when {
        draft.reviewCategoryResolved -> PredictionStaleReason.USER_CATEGORY_CHOSEN
        stored.contextRevision != current.contextRevision ->
            if (state.sourceChanged) {
                PredictionStaleReason.SOURCE_CHANGED
            } else {
                PredictionStaleReason.CONTEXT_CHANGED
            }
        stored.classificationInputKind != current.classificationInputKind.name ||
            stored.translationSourceRevision != current.translationSourceRevision ->
            PredictionStaleReason.CONTEXT_CHANGED
        stored.categoryRevision != current.categoryRevision ||
            !suggestionResolves(stored.suggestedCategoryId, categories) ->
            PredictionStaleReason.CATEGORY_CHANGED
        stored.ruleRevision != current.ruleRevision -> PredictionStaleReason.RULE_CHANGED
        stored.modelRevision != current.modelRevision -> PredictionStaleReason.MODEL_CHANGED
        stored.candidateSetVersion != current.candidateSetVersion ->
            PredictionStaleReason.CANDIDATE_SET_CHANGED
        else -> null
    }

    /**
     * Whether a stored suggestion still names exactly one active category.
     *
     * A null suggestion is a no-fit outcome and has no category to resolve. Anything else
     * must still be a single, currently assignable row: a missing or archived category is
     * not a choice the review may offer.
     */
    private fun suggestionResolves(
        categoryId: Long?,
        categories: List<CategoryEntity>,
    ): Boolean {
        if (categoryId == null) return true
        var matches = 0
        var active = false
        for (category in categories) {
            if (category.id != categoryId) continue
            matches++
            active = !category.archived
        }
        return matches == 1 && active
    }

    /**
     * Marks the stored prediction with the first reason it stopped applying.
     *
     * Nothing is marked when the stored row still answers the current state; that happens
     * when a late result was made for a different input than the draft holds now, and the
     * prediction already stored is still the answer to the current input.
     */
    private suspend fun markFirstReason(
        draftId: Long,
        stored: ClassificationSnapshotEntity?,
        current: PredictionDependencies,
        state: DraftContextState,
        draft: PendingBankDraftEntity,
        categories: List<CategoryEntity>,
    ) {
        if (stored == null) return
        val reason = staleReason(stored, current, state, draft, categories) ?: return
        snapshotDao.markStaleForDraft(draftId, reason.name)
    }

    /** The links and the readings one draft's context is built from. */
    private data class DraftContextState(
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
        val activeEventIds = activeLinks.mapTo(HashSet(activeLinks.size)) { it.eventId }
        val sourceChanged = events.any {
            it.id in activeEventIds &&
                (it.unresolvedReason == SOURCE_EDITED || it.unresolvedReason == SOURCE_DELETED)
        }
        return DraftContextState(activeLinks, events, sourceChanged)
    }

    /**
     * Validates the draft revision and the composite source identity of all active linked readings.
     * A valid binding may contain zero, one, or several current event readings.
     */
    private suspend fun currentTranslationBinding(
        binding: ClassificationTranslationBinding,
        draft: PendingBankDraftEntity,
        state: DraftContextState,
    ): ClassificationTranslationBinding? {
        if (draft.id != binding.draftId || draft.reviewRevision != binding.draftReviewRevision) return null
        val currentSourceRevision = translationSourceRevision(draft, state)
        if (binding.sourceRevision.isNotEmpty() && binding.sourceRevision != currentSourceRevision) return null
        return binding.copy(sourceRevision = currentSourceRevision)
    }

    /** A digest of the current stored draft, all links, and their source/event revisions. */
    private suspend fun translationSourceRevision(
        draft: PendingBankDraftEntity,
        state: DraftContextState,
    ): String {
        val parts = ArrayList<String?>()
        parts += "hosted-english-source/v1"
        parts += draft.id.toString()
        parts += draft.reviewRevision.toString()
        parts += draft.classificationDescription()
        val activeLinks = state.activeLinks.sortedBy { it.id }
        val activeEventIds = activeLinks.mapTo(HashSet(activeLinks.size)) { it.eventId }
        for (link in activeLinks) {
            parts += link.id.toString()
            parts += link.eventId.toString()
            parts += link.state.name
            parts += link.revision.toString()
        }
        for (event in state.events.filter { it.id in activeEventIds }.sortedBy { it.id }) {
            val capture = captureDao.findById(event.captureId)
            parts += event.id.toString()
            parts += event.captureId.toString()
            parts += event.revision.toString()
            parts += event.sourceRevision.toString()
            parts += event.status.name
            parts += event.normalizedText
            parts += capture?.revision?.toString()
            parts += capture?.tombstone?.toString()
        }
        return canonicalDependencyDigest(parts)
    }

    /**
     * The dependency state of one draft, as the digests a prediction is recorded against.
     *
     * [contextRevision] is the identity of the exact model input class: the draft's
     * working description plus the linked readings it would be composed with. The draft's
     * own revision and amount deliberately do not contribute — an amount or moment
     * correction changes no model input, so it must not make a fresh prediction look
     * stale, and a description correction or a link change always changes this digest.
     */
    private suspend fun dependencies(
        draft: PendingBankDraftEntity,
        state: DraftContextState,
        readings: List<LayaEventReading>,
        categories: List<CategoryEntity>,
        classificationInputKind: ClassificationInputKind = ClassificationInputKind.STORED_SOURCE,
        snapshotInputIdentity: String?,
    ): PredictionDependencies {
        val contextParts = ArrayList<String?>()
        contextParts.add(draft.classificationDescription())
        for (reading in readings) {
            contextParts.add(reading.eventId.toString())
            contextParts.add(reading.text)
        }
        val translationSourceRevision =
            if (classificationInputKind == ClassificationInputKind.HOSTED_ENGLISH_TRANSLATION) {
                translationSourceRevision(draft, state)
            } else {
                null
            }
        return PredictionDependencies(
            contextRevision = canonicalDependencyDigest(contextParts),
            categoryRevision = categoryCatalogRevision(categories),
            ruleRevision = canonicalDependencyDigest(ruleRevisionParts()),
            modelRevision = modelRevision,
            candidateSetVersion = candidateSetVersion,
            eventIds = readings.map { it.eventId },
            classificationInputKind = classificationInputKind,
            translationSourceRevision = translationSourceRevision,
            snapshotInputIdentity = snapshotInputIdentity,
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
        classificationInputKind = ClassificationInputKind.valueOf(classificationInputKind),
        classificationInputIdentity = classificationInputIdentity,
        staleReason = staleReason?.let { PredictionStaleReason.valueOf(it) },
    )
}

/** The comma-separated ids of a stored list. */
internal fun String.toIdList(): List<Long> =
    if (isEmpty()) emptyList() else split(",").map { it.toLong() }

/** The comma-separated shares of a stored list. */
internal fun String.toDoubleList(): List<Double> =
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
