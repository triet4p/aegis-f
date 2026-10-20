package io.github.triet4p.aegisf.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * How many records one chunk of a confirmed-ledger fold holds.
 *
 * The chunk is the unit of work of the fold: one chunk is read, folded, and forgotten, so
 * the memory a figure needs is the size of its own breakdown and not the size of the
 * range. It is the same size the surface's largest page may hold, so one rule bounds
 * every read of the ledger.
 */
private const val AGGREGATE_CHUNK_SIZE: Int = LIST_PAGE_SIZE

/**
 * The app's one read surface: the typed, bounded queries the screens and the later tools
 * read through.
 *
 * The service exists so that a question about the stored data has exactly one answer,
 * wherever it is asked from. A screen, a driver, and a later agent tool ask the same
 * question in the same words and get the same rows in the same order, and none of them can
 * ask a question the surface does not state.
 *
 * What the surface guarantees:
 *
 * - **Bounded.** Every read takes a [QueryPageRequest], and there is no unbounded form.
 *   A read asks for a page size and an offset, and the answer says whether more rows
 *   match. A read that wants everything pages through the rest.
 * - **Deterministic.** Every read has a total order with a unique tie-breaker, so a page
 *   boundary always falls between two whole rows and the same page of the same data is
 *   always the same rows.
 * - **Exact money.** The aggregates sum whole dong with the ledger's own overflow rule
 *   ([VndMoney.checkedAddToTotal]) and report an overflow as a proven lower bound
 *   instead of wrapping it.
 * - **Explicit dates.** A range is an explicit half-open range of instants plus the zone
 *   its calendar days are counted in; no read infers a day from the machine's clock.
 * - **No arbitrary query language.** There is no SQL here and no way to pass one in: the
 *   statements are fixed, and what a caller may narrow is a set of named dimensions.
 *
 * What the surface does not do: it never writes. The commands that write are a later
 * task's business, and the read surface deliberately has no write path, so a reader can
 * never turn a read into a change.
 */
class WorkflowQueryService(
    private val database: LedgerDatabase,
) {
    private val categoryDao = database.categoryDao()
    private val transactionDao = database.transactionDao()
    private val captureMessageDao = database.captureMessageDao()
    private val pendingBankDraftDao = database.pendingBankDraftDao()
    private val noteEventDao = database.noteEventDao()
    private val contextLinkDao = database.contextLinkDao()
    private val classificationSnapshotDao = database.classificationSnapshotDao()

    // --- Categories ---------------------------------------------------------

    /**
     * A bounded page of the stored categories.
     *
     * The order is the name without case, then the stable id. A renamed category keeps
     * its place under its new name, an archived one keeps its place among the others,
     * and two categories whose names compare equal are still told apart by their ids.
     */
    suspend fun categories(query: CategoryQuery): QueryPage<CategoryEntity> = pageOf(
        rows = categoryDao.findPage(
            archived = query.state.archivedFlag(),
            limit = query.bounds.limit + 1,
            offset = query.bounds.offset,
        ),
        bounds = query.bounds,
    )

    /** The same bounded page as [categories], as a live stream. */
    fun observeCategories(query: CategoryQuery): Flow<QueryPage<CategoryEntity>> =
        categoryDao.observePage(
            archived = query.state.archivedFlag(),
            limit = query.bounds.limit + 1,
            offset = query.bounds.offset,
        ).map { rows -> pageOf(rows, query.bounds) }

    /** One stored category by its stable id, or null when no category carries it. */
    suspend fun findCategory(categoryId: Long): CategoryEntity? = categoryDao.findById(categoryId)

    /**
     * The stored categories with the given ids.
     *
     * A list that shows rows resolves the categories of exactly the rows it shows, so a
     * row is named by the category it is filed under even when that category is not on
     * the page of the catalog the screen browses. The read is bounded by the ids it is
     * given: one row per id, and no row for an id no category carries.
     */
    suspend fun categoriesByIds(categoryIds: List<Long>): List<CategoryEntity> =
        if (categoryIds.isEmpty()) {
            emptyList()
        } else {
            categoryDao.findByIds(categoryIds)
        }

    /**
     * One stored category by its stable id, as a live stream.
     *
     * The read answers for the category itself and not for the page a screen happens to
     * be showing, so the category a record is filed under keeps its name and its archived
     * marker even when that category is not on the visible page.
     */
    fun observeCategory(categoryId: Long): Flow<CategoryEntity?> =
        categoryDao.observeById(categoryId)

    /**
     * How many stored categories there are, and how many of them are archived.
     *
     * The counts are live and describe the whole catalog, so a screen that pages through
     * the catalog can say how many categories exist however many pages it shows.
     */
    fun observeCategoryCatalogCounts(): Flow<CategoryCatalogCounts> =
        categoryDao.observeCatalogCounts()

    /**
     * The whole stored category catalog, read in bounded chunks.
     *
     * The local model's candidate ranking needs every active category, so this read walks
     * the catalog in bounded chunks and hands back the assembled set. It is the one read
     * that needs the whole catalog: every browsing and selection surface reads the
     * catalog page by page instead, and reaches every category that way.
     */
    suspend fun categoryCatalog(): List<CategoryEntity> {
        val catalog = mutableListOf<CategoryEntity>()
        var offset = 0
        while (true) {
            val page = categoryDao.findPage(
                archived = null,
                limit = LIST_PAGE_SIZE,
                offset = offset,
            )
            if (page.isEmpty()) break
            catalog += page
            if (page.size < LIST_PAGE_SIZE) break
            offset += page.size
        }
        return catalog
    }

    /**
     * The whole stored category catalog, as a live stream.
     *
     * The catalog is re-read whenever a stored category changed, so a rename or an archive
     * is followed by everything that reasons about the catalog — the model's candidate set
     * among them.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCategoryCatalog(): Flow<List<CategoryEntity>> =
        categoryDao.observeCatalogRevision().mapLatest { categoryCatalog() }

    // --- Ledger records -----------------------------------------------------

    /**
     * A bounded page of the stored ledger records.
     *
     * The order is the instant the record happened, newest first, with the stored id as
     * the tie-breaker. Every dimension of the query is open when it is not stated.
     */
    suspend fun ledgerRecords(query: LedgerRecordQuery): QueryPage<TransactionEntity> = pageOf(
        rows = transactionDao.findPage(
            type = query.type?.name,
            confirmation = query.confirmation?.name,
            categoryId = query.category.specificCategoryId(),
            uncategorizedOnly = query.category.uncategorizedOnly(),
            fromEpochMillis = query.occurredIn?.fromEpochMillis,
            throughExclusiveEpochMillis = query.occurredIn?.throughExclusiveEpochMillis,
            limit = query.bounds.limit + 1,
            offset = query.bounds.offset,
        ),
        bounds = query.bounds,
    )

    /** The same bounded page as [ledgerRecords], as a live stream. */
    fun observeLedgerRecords(query: LedgerRecordQuery): Flow<QueryPage<TransactionEntity>> =
        transactionDao.observePage(
            type = query.type?.name,
            confirmation = query.confirmation?.name,
            categoryId = query.category.specificCategoryId(),
            uncategorizedOnly = query.category.uncategorizedOnly(),
            fromEpochMillis = query.occurredIn?.fromEpochMillis,
            throughExclusiveEpochMillis = query.occurredIn?.throughExclusiveEpochMillis,
            limit = query.bounds.limit + 1,
            offset = query.bounds.offset,
        ).map { rows -> pageOf(rows, query.bounds) }

    /** One stored ledger record by its stable id, or null when no record carries it. */
    suspend fun findLedgerRecord(transactionId: Long): TransactionEntity? =
        transactionDao.findById(transactionId)

    // --- Confirmed-ledger aggregates ----------------------------------------

    /**
     * The exact confirmed-ledger figures of one explicit range.
     *
     * The range must be closed: an aggregate that does not know where it ends cannot say
     * what it counted. Every figure is built from the confirmed records of that range
     * only, so a draft is in no figure at all and an internal transfer is reported on its
     * own and never added to spending or income.
     *
     * The reading is exact: whole dong, summed with the ledger's own overflow rule. A
     * total that passes the largest whole-dong value is reported as a proven lower bound
     * with the overflow marked, never as a wrapped or invented number.
     *
     * The range is not held in memory. The records of the range are read in bounded
     * chunks, inside one database transaction, and each chunk is folded into the running
     * figures and forgotten: what the figure needs is the size of its own breakdown and
     * not the number of records the range holds. The one transaction is what makes the
     * figure one consistent view of the range even while the ledger is being written.
     */
    suspend fun confirmedLedgerAggregate(range: LedgerDateRange): LedgerAggregate {
        val aggregate = foldRange(range)
        val categories = categoryDao.findByIds(aggregate.categoryIds())
        return aggregate.withCategoryNames(categories)
    }

    /**
     * The same confirmed-ledger aggregate as [confirmedLedgerAggregate], as a live stream.
     *
     * The stream re-emits when a record of the range is stored, corrected, or deleted, and
     * when a category of the breakdown is renamed or archived — so a summary that shows
     * these figures follows the ledger on its own.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeConfirmedLedgerAggregate(range: LedgerDateRange): Flow<LedgerAggregate> {
        val from = requireClosedRange(range)
        val through = requireNotNull(range.throughExclusiveEpochMillis)
        return transactionDao.observeRangeRevision(from, through)
            .mapLatest { foldRange(range) }
            .flatMapLatest { aggregate ->
                val ids = aggregate.categoryIds()
                if (ids.isEmpty()) {
                    flowOf(aggregate)
                } else {
                    categoryDao.observeByIds(ids).map { categories ->
                        aggregate.withCategoryNames(categories)
                    }
                }
            }
    }

    /**
     * Folds the confirmed records of one range into its exact figures.
     *
     * The fold reads the range in bounded chunks inside one database transaction, so the
     * figures are one snapshot of the range and the read never holds more than a chunk.
     */
    private suspend fun foldRange(range: LedgerDateRange): LedgerAggregate {
        val from = requireClosedRange(range)
        val through = requireNotNull(range.throughExclusiveEpochMillis)
        val fold = LedgerRangeFold(from, through, TimeZone.getTimeZone(range.zoneId))
        withContext(Dispatchers.IO) {
            transactionDao.foldConfirmedRange(AGGREGATE_CHUNK_SIZE, fold)
        }
        return fold.build(range)
    }

    /** The first instant of a closed range; an open range cannot be counted. */
    private fun requireClosedRange(range: LedgerDateRange): Long {
        val from = range.fromEpochMillis
        require(from != null && range.throughExclusiveEpochMillis != null) {
            "A confirmed-ledger aggregate counts a closed range."
        }
        return from
    }

    /**
     * The records behind one spending figure: the confirmed expenses of the range, in the
     * same order the ledger read uses.
     *
     * The drill-down is the aggregate's own scope and no other: the same range, the same
     * confirmed state, the same expense type, and the same category dimension. A caller
     * that pages through the whole drill-down holds exactly the records the aggregate
     * summed, so the two can never disagree.
     */
    suspend fun spendingDrillDown(
        range: LedgerDateRange,
        category: LedgerCategoryFilter = LedgerCategoryFilter.All,
        bounds: QueryPageRequest,
    ): QueryPage<TransactionEntity> = ledgerRecords(
        LedgerRecordQuery(
            type = TransactionType.EXPENSE,
            category = category,
            confirmation = ConfirmationStatus.CONFIRMED,
            occurredIn = range,
            bounds = bounds,
        ),
    )

    // --- Sources ------------------------------------------------------------

    /**
     * A bounded page of the stored sources.
     *
     * The returned rows are the stored captures themselves, so the text a read returns is
     * the text that is stored: a bank evidence row holds only its parsed memo, and a
     * deleted source is a tombstone that holds no text at all.
     */
    suspend fun sourceExcerpts(query: SourceQuery): QueryPage<CaptureMessageEntity> = pageOf(
        rows = captureMessageDao.findPage(
            captureKind = query.kind?.name,
            sourceKind = query.sourceKind?.name,
            tombstone = query.state.tombstoneFlag(),
            fromEpochMillis = query.capturedIn?.fromEpochMillis,
            throughExclusiveEpochMillis = query.capturedIn?.throughExclusiveEpochMillis,
            limit = query.bounds.limit + 1,
            offset = query.bounds.offset,
        ),
        bounds = query.bounds,
    )

    // --- Draft candidates ---------------------------------------------------

    /**
     * A bounded page of the stored bank drafts.
     *
     * A draft is evidence waiting for a decision, never a ledger record: no draft of any
     * state enters a ledger figure.
     */
    suspend fun draftCandidates(query: DraftCandidateQuery): QueryPage<PendingBankDraftEntity> =
        pageOf(
            rows = pendingBankDraftDao.findPage(
                reviewStatus = query.reviewStatus?.name,
                onlyDuplicates = if (query.onlyPossibleDuplicates) 1 else 0,
                limit = query.bounds.limit + 1,
                offset = query.bounds.offset,
            ),
            bounds = query.bounds,
        )

    /** The same bounded page as [draftCandidates], as a live stream. */
    fun observeDraftCandidates(query: DraftCandidateQuery): Flow<QueryPage<PendingBankDraftEntity>> =
        pendingBankDraftDao.observePage(
            reviewStatus = query.reviewStatus?.name,
            onlyDuplicates = if (query.onlyPossibleDuplicates) 1 else 0,
            limit = query.bounds.limit + 1,
            offset = query.bounds.offset,
        ).map { rows -> pageOf(rows, query.bounds) }

    /** One stored draft as a live stream, so a review screen sees its own decisions. */
    fun observeDraftCandidate(draftId: Long): Flow<PendingBankDraftEntity?> =
        pendingBankDraftDao.observeById(draftId)

    /**
     * How many stored bank drafts are in one review state, as a live count.
     *
     * The count describes the whole inbox and not one page of it, so an inbox that pages
     * through its drafts can still say how many are waiting.
     */
    fun observeDraftCandidateCount(reviewStatus: IntakeReviewStatus?): Flow<Int> =
        pendingBankDraftDao.observeCountByStatus(reviewStatus?.name)

    /**
     * The drafts that share their evidence with the given ones, as a live stream.
     *
     * The family is the earlier drafts they point at and the later drafts that point at
     * them. A possible duplicate is an uncertain identity, so both drafts are kept and
     * shown beside each other.
     */
    fun observeRelatedDraftCandidates(draftIds: List<Long>): Flow<List<PendingBankDraftEntity>> =
        if (draftIds.isEmpty()) {
            flowOf(emptyList())
        } else {
            pendingBankDraftDao.observeRelated(draftIds)
        }

    // --- Operation results --------------------------------------------------

    /**
     * A bounded page of the stored link decisions.
     *
     * This is the current-source result of the link operations: there is no operation log
     * yet, so the read reports the stored link itself — its state, its revision, and the
     * evidence and override recorded with the decision — and invents no idempotency
     * identity, caller scope, or change summary that the database does not hold.
     */
    suspend fun linkOperations(query: LinkOperationQuery): QueryPage<LinkOperation> = pageOf(
        rows = contextLinkDao.findPage(
            state = query.state?.name,
            eventId = query.eventId,
            draftId = query.draftId,
            limit = query.bounds.limit + 1,
            offset = query.bounds.offset,
        ).map { link ->
            LinkOperation(
                linkId = link.id,
                eventId = link.eventId,
                draftId = link.draftId,
                state = link.state,
                revision = link.revision,
                evidence = link.evidence,
                userOverride = link.userOverride,
                createdAtEpochMillis = link.createdAtEpochMillis,
            )
        },
        bounds = query.bounds,
    )

    /**
     * A bounded page of the stored review decisions of the bank drafts.
     *
     * The stored state is the lifecycle state itself: pending, confirmed, or dismissed.
     */
    suspend fun draftOperations(query: DraftOperationQuery): QueryPage<DraftOperation> = pageOf(
        rows = pendingBankDraftDao.findPage(
            reviewStatus = query.reviewStatus?.name,
            onlyDuplicates = 0,
            limit = query.bounds.limit + 1,
            offset = query.bounds.offset,
        ).map { draft ->
            DraftOperation(
                draftId = draft.id,
                reviewStatus = draft.reviewStatus,
                reviewRevision = draft.reviewRevision,
                confirmedTransactionId = draft.confirmedTransactionId,
                possibleDuplicateOfDraftId = draft.possibleDuplicateOfDraftId,
                createdAtEpochMillis = draft.createdAtEpochMillis,
            )
        },
        bounds = query.bounds,
    )

    /**
     * A bounded page of the stored capture saves and revisions.
     *
     * The stored state is the truth about the row: whether its text is still stored, and
     * which revision of it is current. A deleted capture is reported as a tombstone and
     * never carries text.
     */
    suspend fun captureOperations(query: CaptureOperationQuery): QueryPage<CaptureOperation> =
        pageOf(
            rows = captureMessageDao.findPage(
                captureKind = null,
                sourceKind = null,
                tombstone = query.state.tombstoneFlag(),
                fromEpochMillis = null,
                throughExclusiveEpochMillis = null,
                limit = query.bounds.limit + 1,
                offset = query.bounds.offset,
            ).map { capture ->
                CaptureOperation(
                    captureId = capture.id,
                    captureKind = capture.captureKind,
                    sourceKind = capture.sourceKind,
                    revision = capture.revision,
                    tombstone = capture.tombstone,
                    capturedAtEpochMillis = capture.capturedAtEpochMillis,
                    captureTimezone = capture.captureTimezone,
                )
            },
            bounds = query.bounds,
        )

    /**
     * A bounded page of the stored event interpretations.
     *
     * The stored state is the interpretation state itself: interpreted, unresolved, or
     * unmatched.
     */
    suspend fun eventOperations(query: EventOperationQuery): QueryPage<EventOperation> = pageOf(
        rows = noteEventDao.findPage(
            captureId = query.captureId,
            status = query.status?.name,
            limit = query.bounds.limit + 1,
            offset = query.bounds.offset,
        ).map { event ->
            EventOperation(
                eventId = event.id,
                captureId = event.captureId,
                status = event.status,
                revision = event.revision,
                sourceRevision = event.sourceRevision,
                unresolvedReason = event.unresolvedReason,
            )
        },
        bounds = query.bounds,
    )

    /**
     * A bounded page of the stored classification results.
     *
     * The prediction is review-only evidence: the read reports where the decision came
     * from, the category it pointed at, the categories the model evaluated, and the one
     * sharpness value of that distribution — plus why it no longer applies, if it does.
     */
    suspend fun classificationOperations(
        query: ClassificationOperationQuery,
    ): QueryPage<ClassificationOperation> = pageOf(
        rows = classificationSnapshotDao.findPage(
            staleOnly = if (query.staleOnly) 1 else 0,
            limit = query.bounds.limit + 1,
            offset = query.bounds.offset,
        ).map { snapshot ->
            ClassificationOperation(
                draftId = snapshot.draftId,
                decisionSource = PredictionSource.valueOf(snapshot.decisionSource),
                suggestedCategoryId = snapshot.suggestedCategoryId,
                candidateIds = snapshot.candidateIds.toIdList(),
                confidence = snapshot.confidence,
                predictionTimeEpochMillis = snapshot.predictionTime,
                staleReason = snapshot.staleReason?.let { PredictionStaleReason.valueOf(it) },
            )
        },
        bounds = query.bounds,
    )
}

/**
 * Turns the rows a read fetched into one bounded page.
 *
 * The read asks for one row more than the page holds, so a page knows whether more rows
 * match without a second count query over the same filters — and without a second filter
 * that could drift from the first.
 */
private fun <T> pageOf(rows: List<T>, bounds: QueryPageRequest): QueryPage<T> {
    val hasMore = rows.size > bounds.limit
    val items = if (hasMore) rows.subList(0, bounds.limit) else rows
    return QueryPage(
        items = items,
        offset = bounds.offset,
        limit = bounds.limit,
        hasMore = hasMore,
    )
}

/** The stored state a category query reads: null for every state. */
private fun CategoryState.archivedFlag(): Boolean? = when (this) {
    CategoryState.ACTIVE -> false
    CategoryState.ARCHIVED -> true
    CategoryState.ALL -> null
}

/** The stored state a source query reads: null for every state. */
private fun SourceState.tombstoneFlag(): Boolean? = when (this) {
    SourceState.ACTIVE -> false
    SourceState.DELETED -> true
    SourceState.ALL -> null
}

/** The one named category a query narrows to, or null when it narrows to none. */
private fun LedgerCategoryFilter.specificCategoryId(): Long? =
    (this as? LedgerCategoryFilter.Specific)?.categoryId

/** Whether a query narrows to the records filed under no category. */
private fun LedgerCategoryFilter.uncategorizedOnly(): Int =
    if (this is LedgerCategoryFilter.Uncategorized) 1 else 0

/** The category ids the spending breakdown of the range is made of. */
private fun LedgerAggregate.categoryIds(): List<Long> = spending.categoryTotals
    .mapNotNull { it.categoryId }
    .distinct()

/**
 * The stored names of the categories a breakdown is made of.
 *
 * A bucket whose category was deleted has no name and no id — the records it holds are
 * simply uncategorised now — and an archived category keeps its name and is marked
 * archived, so historical attribution stays readable.
 */
private fun LedgerAggregate.withCategoryNames(categories: List<CategoryEntity>): LedgerAggregate =
    copy(spending = spending.withCategoryNames(categories))

private fun LedgerSpending.withCategoryNames(categories: List<CategoryEntity>): LedgerSpending {
    val byId = categories.associateBy { it.id }
    return copy(
        categoryTotals = categoryTotals.map { bucket ->
            val category = bucket.categoryId?.let { byId[it] }
            bucket.copy(
                categoryName = category?.name,
                categoryArchived = category?.archived ?: false,
            )
        },
    )
}
