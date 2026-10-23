package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.triet4p.aegisf.notification.PermittedNotificationContent
import io.github.triet4p.aegisf.ui.ManualDateTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * The shared query surface against the real database: the figures it returns, the scope it
 * states, the boundaries it keeps, and the decisions it reports.
 *
 * These are the properties a later tool or a screen depends on, so they are pinned against
 * stored rows and not against the statements that read them: the exact whole-dong spending
 * of a known ledger, the drafts and transfers that never enter it, the day a record belongs
 * to when it sits on either side of midnight, the spending an archived or deleted category
 * keeps, the drill-down that reconciles with the aggregate, the page boundaries that never
 * skip or repeat a row, and the operation reads that report the stored decision instead of
 * an invented one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkflowQueryServiceTest {

    private val zone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val zoneId = "Asia/Ho_Chi_Minh"

    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var query: WorkflowQueryService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        database = LedgerDatabase.build(context)
        repository = LedgerRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
            rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
            captureMessageDao = database.captureMessageDao(),
        )
        query = WorkflowQueryService(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun at(date: String, time: String): Long =
        ManualDateTime.toEpochMillis(date, time, zone)

    private fun monthRange(): LedgerDateRange = LedgerDateRange.closed(
        fromEpochMillis = ManualDateTime.monthStartEpochMillis(at("2026-10-15", "12:00"), zone),
        throughExclusiveEpochMillis = ManualDateTime.nextMonthStartEpochMillis(
            ManualDateTime.monthStartEpochMillis(at("2026-10-15", "12:00"), zone),
            zone,
        ),
        zoneId = zoneId,
    )

    /**
     * A known ledger: three confirmed expenses (two of them on the same day, one after
     * midnight), one income, one internal transfer, and one draft that was never confirmed.
     */
    private suspend fun seedKnownLedger(): Seeded {
        val food = repository.addCategory("Food")
        val travel = repository.addCategory("Travel")
        val lunch = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 45_000L,
                occurredAtEpochMillis = at("2026-10-15", "09:00"),
                description = "Lunch",
                categoryId = food,
            ),
        )
        val dinner = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 120_000L,
                occurredAtEpochMillis = at("2026-10-15", "23:59"),
                description = "Dinner",
                categoryId = food,
            ),
        )
        val taxi = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 30_000L,
                occurredAtEpochMillis = at("2026-10-16", "00:01"),
                description = "Taxi",
                categoryId = travel,
            ),
        )
        val salary = repository.addTransaction(
            TransactionInput(
                type = TransactionType.INCOME,
                amountVnd = 1_000_000L,
                occurredAtEpochMillis = at("2026-10-16", "10:00"),
                description = "Salary",
            ),
        )
        val transfer = repository.addTransaction(
            TransactionInput(
                type = TransactionType.TRANSFER,
                amountVnd = 500_000L,
                occurredAtEpochMillis = at("2026-10-16", "11:00"),
                description = "Move to savings",
            ),
        )
        val draft = repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = 999_000L,
                occurredAtEpochMillis = at("2026-10-16", "12:00"),
                description = "Not confirmed",
                categoryId = food,
                confirmation = ConfirmationStatus.DRAFT,
            ),
        )
        return Seeded(food, travel, lunch, dinner, taxi, salary, transfer, draft)
    }

    private data class Seeded(
        val food: Long,
        val travel: Long,
        val lunch: Long,
        val dinner: Long,
        val taxi: Long,
        val salary: Long,
        val transfer: Long,
        val draft: Long,
    )

    @Test
    fun `the aggregate returns the exact confirmed spending and excludes drafts and transfers`() =
        runTest {
            seedKnownLedger()

            val aggregate = query.confirmedLedgerAggregate(monthRange())

            // 45,000 + 120,000 + 30,000 — the income, the internal transfer, and the draft
            // are in no spending figure.
            assertEquals(195_000L, aggregate.spending.totalVnd)
            assertEquals(3, aggregate.spending.recordCount)
            assertFalse(aggregate.spending.overflowed)
            assertEquals(1_000_000L, aggregate.income.totalVnd)
            assertEquals(1, aggregate.income.recordCount)
            assertEquals(500_000L, aggregate.transfers.totalVnd)
            assertEquals(1, aggregate.transfers.recordCount)
            assertEquals(1, aggregate.excludedDraftCount)
        }

    @Test
    fun `a record on either side of midnight belongs to its own local day`() = runTest {
        seedKnownLedger()

        val aggregate = query.confirmedLedgerAggregate(monthRange())

        // 23:59 and 00:01 are the same day in UTC and two different days in the zone the
        // range names, so the daily buckets must not merge them.
        assertEquals(2, aggregate.dailySpending.size)
        val first = aggregate.dailySpending.first()
        val second = aggregate.dailySpending.last()
        assertEquals("2026-10-15", first.dayText)
        assertEquals(165_000L, first.totalVnd)
        assertEquals(2, first.recordCount)
        assertEquals("2026-10-16", second.dayText)
        assertEquals(30_000L, second.totalVnd)
        assertEquals(1, second.recordCount)
        // The days are consecutive local midnights, an hour apart in absolute time only
        // because the zone is seven hours ahead of UTC.
        assertEquals(
            24L * 60L * 60L * 1000L,
            second.dayStartEpochMillis - first.dayStartEpochMillis,
        )
    }

    @Test
    fun `daily spending follows its category selector while the range total stays whole`() =
        runTest {
            val seeded = seedKnownLedger()

            val food = query.confirmedLedgerAggregate(
                monthRange(),
                dailySpendingCategory = LedgerCategoryFilter.Specific(seeded.food),
            )

            assertEquals(195_000L, food.spending.totalVnd)
            assertEquals(
                LedgerCategoryFilter.Specific(seeded.food),
                food.dailySpendingCategory,
            )
            assertEquals(listOf("2026-10-15"), food.dailySpending.map { it.dayText })
            assertEquals(165_000L, food.dailySpending.single().totalVnd)
            assertEquals(2, food.dailySpending.single().recordCount)

            val travel = query.confirmedLedgerAggregate(
                monthRange(),
                dailySpendingCategory = LedgerCategoryFilter.Specific(seeded.travel),
            )
            assertEquals(195_000L, travel.spending.totalVnd)
            assertEquals(listOf("2026-10-16"), travel.dailySpending.map { it.dayText })
            assertEquals(30_000L, travel.dailySpending.single().totalVnd)

            repository.addTransaction(
                TransactionInput(
                    type = TransactionType.EXPENSE,
                    amountVnd = 7_000L,
                    occurredAtEpochMillis = at("2026-10-20", "14:00"),
                    description = "Uncategorized expense",
                ),
            )
            val uncategorized = query.confirmedLedgerAggregate(
                monthRange(),
                dailySpendingCategory = LedgerCategoryFilter.Uncategorized,
            )
            assertEquals(202_000L, uncategorized.spending.totalVnd)
            assertEquals(listOf("2026-10-20"), uncategorized.dailySpending.map { it.dayText })
            assertEquals(7_000L, uncategorized.dailySpending.single().totalVnd)
        }

    @Test
    fun `an archived category keeps its spending and its name`() = runTest {
        val seeded = seedKnownLedger()

        repository.archiveCategory(seeded.travel)

        val aggregate = query.confirmedLedgerAggregate(monthRange())
        val travel = aggregate.spending.categoryTotals.single { it.categoryId == seeded.travel }
        assertEquals(30_000L, travel.totalVnd)
        assertEquals("Travel", travel.categoryName)
        assertTrue(travel.categoryArchived)
        // The archived category leaves the pool a new record may be filed under, but the
        // spending its records already carry stays where it was.
        assertFalse(
            query.categories(ALL_ASSIGNABLE_CATEGORIES).items.any { it.id == seeded.travel },
        )
        assertTrue(query.categories(ALL_CATEGORIES).items.any { it.id == seeded.travel })
    }

    @Test
    fun `a deleted category leaves its spending in the uncategorized bucket`() = runTest {
        val seeded = seedKnownLedger()

        repository.deleteCategory(seeded.food)

        val aggregate = query.confirmedLedgerAggregate(monthRange())
        val uncategorized = aggregate.spending.categoryTotals.single { it.categoryId == null }
        assertEquals(165_000L, uncategorized.totalVnd)
        assertEquals(2, uncategorized.recordCount)
        assertNull(uncategorized.categoryName)
        assertFalse(
            aggregate.spending.categoryTotals.any { it.categoryId == seeded.food },
        )
    }

    @Test
    fun `a total past the largest whole-dong value is a proven lower bound`() = runTest {
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = Long.MAX_VALUE,
                occurredAtEpochMillis = at("2026-10-15", "09:00"),
                description = "Huge one",
            ),
        )
        repository.addTransaction(
            TransactionInput(
                type = TransactionType.EXPENSE,
                amountVnd = Long.MAX_VALUE,
                occurredAtEpochMillis = at("2026-10-15", "10:00"),
                description = "Huge two",
            ),
        )

        val aggregate = query.confirmedLedgerAggregate(monthRange())

        assertTrue(aggregate.spending.overflowed)
        assertEquals(Long.MAX_VALUE, aggregate.spending.totalVnd)
        assertEquals(2, aggregate.spending.recordCount)
        val bucket = aggregate.spending.categoryTotals.single()
        assertTrue(bucket.overflowed)
        assertEquals(Long.MAX_VALUE, bucket.totalVnd)
    }

    @Test
    fun `the drill down holds exactly the records the aggregate summed`() = runTest {
        val seeded = seedKnownLedger()

        val aggregate = query.confirmedLedgerAggregate(monthRange())
        val drillDown = query.spendingDrillDown(
            range = monthRange(),
            category = LedgerCategoryFilter.All,
            bounds = QueryPageRequest(LIST_PAGE_SIZE),
        )

        assertEquals(aggregate.spending.recordCount, drillDown.items.size)
        assertEquals(
            aggregate.spending.totalVnd,
            drillDown.items.sumOf { it.amountVnd },
        )
        assertTrue(drillDown.items.none { it.id == seeded.draft })
        assertTrue(drillDown.items.none { it.id == seeded.transfer })
        assertTrue(drillDown.items.none { it.id == seeded.salary })

        // The drill-down of one category is the aggregate's own bucket, row for row.
        val foodDrillDown = query.spendingDrillDown(
            range = monthRange(),
            category = LedgerCategoryFilter.Specific(seeded.food),
            bounds = QueryPageRequest(LIST_PAGE_SIZE),
        )
        assertEquals(2, foodDrillDown.items.size)
        assertEquals(
            165_000L,
            foodDrillDown.items.sumOf { it.amountVnd },
        )
    }

    @Test
    fun `pages are deterministic and never skip or repeat a row`() = runTest {
        seedKnownLedger()

        val all = query.ledgerRecords(ALL_LEDGER_RECORDS)
        val first = query.ledgerRecords(
            LedgerRecordQuery(bounds = QueryPageRequest(2, offset = 0)),
        )
        val second = query.ledgerRecords(
            LedgerRecordQuery(bounds = QueryPageRequest(2, offset = 2)),
        )
        val third = query.ledgerRecords(
            LedgerRecordQuery(bounds = QueryPageRequest(2, offset = 4)),
        )

        assertTrue(first.hasMore)
        assertEquals(2, first.nextOffset)
        assertTrue(second.hasMore)
        assertFalse(third.hasMore)
        assertNull(third.nextOffset)
        assertEquals(all.items.map { it.id }, (first.items + second.items + third.items).map { it.id })
        // The order is the instant the record happened, newest first: the draft of noon, the
        // transfer of eleven, the income of ten, the taxi of one minute past midnight, the
        // dinner of the day before at 23:59, and the lunch of that morning.
        assertEquals(
            listOf(6L, 5L, 4L, 3L, 2L, 1L),
            all.items.map { it.id },
        )
    }

    @Test
    fun `the record read states its scope`() = runTest {
        val seeded = seedKnownLedger()

        val expenses = query.ledgerRecords(
            LedgerRecordQuery(
                type = TransactionType.EXPENSE,
                confirmation = ConfirmationStatus.CONFIRMED,
                bounds = QueryPageRequest(LIST_PAGE_SIZE),
            ),
        )
        assertEquals(
            listOf(seeded.taxi, seeded.dinner, seeded.lunch),
            expenses.items.map { it.id },
        )

        val uncategorized = query.ledgerRecords(
            LedgerRecordQuery(
                category = LedgerCategoryFilter.Uncategorized,
                bounds = QueryPageRequest(LIST_PAGE_SIZE),
            ),
        )
        assertEquals(
            listOf(seeded.transfer, seeded.salary),
            uncategorized.items.map { it.id },
        )

        val inOctober = query.ledgerRecords(
            LedgerRecordQuery(
                occurredIn = LedgerDateRange.closed(
                    fromEpochMillis = at("2026-10-16", "00:00"),
                    throughExclusiveEpochMillis = at("2026-10-17", "00:00"),
                    zoneId = zoneId,
                ),
                bounds = QueryPageRequest(LIST_PAGE_SIZE),
            ),
        )
        assertEquals(
            listOf(seeded.draft, seeded.transfer, seeded.salary, seeded.taxi),
            inOctober.items.map { it.id },
        )
    }

    @Test
    fun `the source read returns stored text and never deleted text`() = runTest {
        val captures = CaptureMessageRepository(database = database)
        val noteId = captures.saveQuickCapture("Fuel 50,000 at 15:00")

        val stored = query.sourceExcerpts(
            SourceQuery(kind = CaptureKind.USER_NOTE, bounds = QueryPageRequest(LIST_PAGE_SIZE)),
        )
        assertEquals(1, stored.items.size)
        assertEquals("Fuel 50,000 at 15:00", stored.items.single().bodyText)
        assertEquals(noteId, stored.items.single().id)

        // An explicit deletion leaves a tombstone: the identity survives, the text does not.
        captures.delete(noteId, 0)

        val active = query.sourceExcerpts(
            SourceQuery(kind = CaptureKind.USER_NOTE, bounds = QueryPageRequest(LIST_PAGE_SIZE)),
        )
        assertTrue(active.items.isEmpty())
        val deleted = query.sourceExcerpts(
            SourceQuery(
                kind = CaptureKind.USER_NOTE,
                state = SourceState.DELETED,
                bounds = QueryPageRequest(LIST_PAGE_SIZE),
            ),
        )
        assertEquals(1, deleted.items.size)
        assertNull(deleted.items.single().bodyText)
        assertTrue(deleted.items.single().tombstone)
    }


    @Test
    fun `capture event reads are bounded and exact manual lookup excludes active links`() = runTest {
        val source = "S65: Cafe 50000; Book 20000"
        val captureId = CaptureMessageRepository(database).saveQuickCapture(source)
        val events = EventContextRepository(database)
        val cafeStart = source.indexOf("Cafe")
        val cafe = events.recordEvent(
            captureId = captureId,
            expectedCaptureRevision = 0,
            interpretation = NoteEventInterpretation(
                spanStart = cafeStart,
                spanEnd = cafeStart + "Cafe 50000".length,
                normalizedText = "Cafe purchase",
                amountVnd = 50_000L,
            ),
            extractionVersion = "user-manual-v1",
        ) as NoteEventWriteResult.Stored
        val bookStart = source.indexOf("Book")
        val book = events.recordEvent(
            captureId = captureId,
            expectedCaptureRevision = 0,
            interpretation = NoteEventInterpretation(
                spanStart = bookStart,
                spanEnd = bookStart + "Book 20000".length,
                normalizedText = "Book purchase",
                amountVnd = 20_000L,
            ),
            extractionVersion = "user-manual-v1",
        ) as NoteEventWriteResult.Stored

        val firstPage = query.captureEvents(
            CaptureEventQuery(captureId, QueryPageRequest(limit = 1)),
        )
        assertEquals(listOf(cafe.eventId), firstPage.items.map { it.id })
        assertTrue(firstPage.hasMore)
        val secondPage = query.captureEvents(
            CaptureEventQuery(captureId, QueryPageRequest(limit = 1, offset = 1)),
        )
        assertEquals(listOf(book.eventId), secondPage.items.map { it.id })
        assertFalse(secondPage.hasMore)

        val manualInput = query.unrecordedNoteEvent(cafe.eventId)
        assertNotNull(manualInput)
        assertEquals(captureId, manualInput?.captureId)
        assertEquals(source, manualInput?.captureText)
        assertEquals(50_000L, manualInput?.amountVnd)

        val draftId = IntakeRepository(database).storePermitted(
            sourcePackage = "com.vietinbank.ipay",
            notificationKey = "0|com.vietinbank.ipay|1|s65|100",
            postedAtEpochMillis = 1_792_745_400_000L,
            content = PermittedNotificationContent(
                title = "VietinBank",
                text = listOf(
                    "VietinBank: 23/10/2026 10:30",
                    "GD: -50,000 VND",
                    "ND: Cafe",
                ).joinToString("\n"),
                bigText = null,
            ),
        )
        assertTrue(events.proposeLink(cafe.eventId, draftId) is ContextLinkResult.Linked)
        assertNull(query.unrecordedNoteEvent(cafe.eventId))
        assertNotNull(query.unrecordedNoteEvent(book.eventId))
    }

    @Test
    fun `the operation reads report the stored decisions and invent nothing`() = runTest {
        val captures = CaptureMessageRepository(database = database)
        val captureId = captures.saveQuickCapture("Fuel 50,000 at 15:00")
        val events = EventContextRepository(database = database)
        val stored = events.recordEvent(
            captureId = captureId,
            expectedCaptureRevision = 0,
            interpretation = NoteEventInterpretation(
                spanStart = 0,
                spanEnd = 4,
                normalizedText = "Fuel",
                amountVnd = 50_000L,
            ),
            extractionVersion = "test-v1",
        )
        assertTrue(stored is NoteEventWriteResult.Stored)
        val eventId = (stored as NoteEventWriteResult.Stored).eventId

        val captureOperations = query.captureOperations(
            CaptureOperationQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)),
        )
        assertEquals(1, captureOperations.items.size)
        assertEquals(captureId, captureOperations.items.single().captureId)
        assertEquals(CaptureKind.USER_NOTE, captureOperations.items.single().captureKind)
        assertEquals(0, captureOperations.items.single().revision)
        assertFalse(captureOperations.items.single().tombstone)

        val eventOperations = query.eventOperations(
            EventOperationQuery(captureId = captureId, bounds = QueryPageRequest(LIST_PAGE_SIZE)),
        )
        assertEquals(1, eventOperations.items.size)
        assertEquals(eventId, eventOperations.items.single().eventId)
        assertEquals(NoteEventStatus.INTERPRETED, eventOperations.items.single().status)
        assertEquals(0, eventOperations.items.single().revision)

        // No link was ever decided, so the link read is empty rather than invented.
        val links = query.linkOperations(
            LinkOperationQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)),
        )
        assertTrue(links.items.isEmpty())
    }

    @Test
    fun `the classification read reports the stored prediction and its stale reason`() = runTest {
        val draftId = IntakeRepository(database = database).storePermitted(
            sourcePackage = "com.vietinbank.ipay",
            notificationKey = "0|com.vietinbank.ipay|1|s55|100",
            postedAtEpochMillis = 1_760_000_000_000L,
            content = PermittedNotificationContent(
                title = "Tin biến động số dư",
                text = listOf(
                    "VietinBank: 16/10/2026 07:38",
                    "TK: Redacted",
                    "GD: -50,000 VND",
                    "SDC: xxx,000 VND",
                    "ND: ........",
                ).joinToString("\n"),
                bigText = null,
            ),
        )
        database.classificationSnapshotDao().insert(
            ClassificationSnapshotEntity(
                draftId = draftId,
                contextRevision = "context",
                categoryRevision = "categories",
                ruleRevision = "rules",
                modelRevision = "model",
                candidateSetVersion = "candidates",
                decisionSource = PredictionSource.MODEL.name,
                suggestedCategoryId = 7L,
                candidateIds = "7,9",
                probabilities = "0.6,0.4",
                unknownShare = 0.0,
                confidence = 0.5,
                effectiveInputDigest = "digest",
                eventIds = "1",
                predictionTime = 1_760_000_000_000L,
                staleReason = null,
            ),
        )

        val stored = query.classificationOperations(
            ClassificationOperationQuery(bounds = QueryPageRequest(LIST_PAGE_SIZE)),
        )
        assertEquals(1, stored.items.size)
        val operation = stored.items.single()
        assertEquals(draftId, operation.draftId)
        assertEquals(PredictionSource.MODEL, operation.decisionSource)
        assertEquals(7L, operation.suggestedCategoryId)
        assertEquals(listOf(7L, 9L), operation.candidateIds)
        assertEquals(0.5, operation.confidence!!, 0.0001)
        assertNull(operation.staleReason)

        // A prediction that no longer applies keeps its first reason and is reported as
        // such; a fresh one is not in the stale read.
        database.classificationSnapshotDao().markStaleForDraft(
            draftId,
            PredictionStaleReason.CATEGORY_CHANGED.name,
        )
        val stale = query.classificationOperations(
            ClassificationOperationQuery(staleOnly = true, bounds = QueryPageRequest(LIST_PAGE_SIZE)),
        )
        assertEquals(1, stale.items.size)
        assertEquals(PredictionStaleReason.CATEGORY_CHANGED, stale.items.single().staleReason)
    }

    @Test
    fun `a bounded read refuses an unbounded page`() {
        val refused = runCatching { QueryPageRequest(0) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException)
        val tooLarge = runCatching { QueryPageRequest(QueryPageRequest.MAX_PAGE_SIZE + 1) }
            .exceptionOrNull()
        assertTrue(tooLarge is IllegalArgumentException)
        val negativeOffset = runCatching { QueryPageRequest(1, offset = -1) }.exceptionOrNull()
        assertTrue(negativeOffset is IllegalArgumentException)
    }

    @Test
    fun `a single stored record and category are read by their stable ids`() = runTest {
        val seeded = seedKnownLedger()

        val record = query.findLedgerRecord(seeded.lunch)
        assertNotNull(record)
        assertEquals(45_000L, record!!.amountVnd)
        assertNull(query.findLedgerRecord(9_999L))

        val category = query.findCategory(seeded.food)
        assertNotNull(category)
        assertEquals("Food", category!!.name)
        assertNull(query.findCategory(9_999L))
    }

    /**
     * Every stored category is reachable through the pages of the one bounded read.
     *
     * One category more than the largest page the surface allows, so the boundary is
     * exact: the first page holds the page size and says more are stored, and the second
     * page holds the rest.
     */
    @Test
    fun `every stored category is reachable through the pages of the bounded read`() = runTest {
        val names = (1..(LIST_PAGE_SIZE + 1)).map { index -> "Category %03d".format(index) }
        names.forEach { name -> repository.addCategory(name) }

        val first = query.categories(ALL_CATEGORIES)
        assertEquals(LIST_PAGE_SIZE, first.items.size)
        assertTrue(first.hasMore)
        assertEquals(LIST_PAGE_SIZE, first.nextOffset)

        val second = query.categories(
            CategoryQuery(
                state = CategoryState.ALL,
                bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = LIST_PAGE_SIZE),
            ),
        )
        assertEquals(1, second.items.size)
        assertFalse(second.hasMore)
        assertNull(second.nextOffset)

        val read = first.items + second.items
        assertEquals(names.size, read.size)
        assertEquals(names.sorted(), read.map { it.name })
        // The category on the second page is reachable by its own id, so a screen can name
        // the one that is not on the page it happens to be showing.
        val beyondTheFirstPage = read.last()
        assertEquals(beyondTheFirstPage, query.findCategory(beyondTheFirstPage.id))
    }

    /** Every stored record is reachable through the pages of the one bounded read. */
    @Test
    fun `every stored record is reachable through the pages of the bounded read`() = runTest {
        val stored = 25
        val dao = database.transactionDao()
        val firstMoment = at("2026-10-15", "09:00")
        repeat(stored) { index ->
            dao.insert(
                TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amountVnd = 1_000L + index,
                    occurredAtEpochMillis = firstMoment + index * 60_000L,
                    description = "Record $index",
                    createdAtEpochMillis = firstMoment,
                    updatedAtEpochMillis = firstMoment,
                ),
            )
        }

        val first = query.ledgerRecords(LedgerRecordQuery(bounds = QueryPageRequest(10)))
        val second = query.ledgerRecords(
            LedgerRecordQuery(bounds = QueryPageRequest(10, offset = 10)),
        )
        val third = query.ledgerRecords(
            LedgerRecordQuery(bounds = QueryPageRequest(10, offset = 20)),
        )
        assertTrue(first.hasMore)
        assertTrue(second.hasMore)
        assertFalse(third.hasMore)

        val read = (first.items + second.items + third.items).map { it.id }
        assertEquals(stored, read.size)
        assertEquals(read.size, read.distinct().size)
        assertEquals(
            stored,
            query.ledgerRecords(ALL_LEDGER_RECORDS).items.size,
        )
    }

    /**
     * The aggregate of a range with more records than one chunk holds is still exact.
     *
     * The fold reads the range in bounded chunks, so a range with several chunks of
     * records has to give the same figure a small range would: the exact whole-dong sum,
     * the exact record count, the exact day buckets on either side of the local midnight,
     * and the drafts, income, and transfers that are in no spending figure at all.
     */
    @Test
    fun `an aggregate over more records than one chunk holds is still exact`() = runTest {
        val dao = database.transactionDao()
        val firstMoment = at("2026-10-15", "09:00")
        // More than two chunks of the fold, so the range is read in several pieces.
        val chunked = LIST_PAGE_SIZE * 2 + 50
        repeat(chunked) { index ->
            dao.insert(
                TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amountVnd = 100L,
                    occurredAtEpochMillis = firstMoment + index * 1_000L,
                    description = "Spend $index",
                    createdAtEpochMillis = firstMoment,
                    updatedAtEpochMillis = firstMoment,
                ),
            )
        }
        // One record on the other side of the local midnight.
        dao.insert(
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amountVnd = 700L,
                occurredAtEpochMillis = at("2026-10-16", "00:01"),
                description = "After midnight",
                createdAtEpochMillis = firstMoment,
                updatedAtEpochMillis = firstMoment,
            ),
        )
        // The income, the internal transfer, and the draft that are in no spending figure.
        dao.insert(
            TransactionEntity(
                type = TransactionType.INCOME,
                amountVnd = 5_000L,
                occurredAtEpochMillis = at("2026-10-16", "10:00"),
                description = "Salary",
                createdAtEpochMillis = firstMoment,
                updatedAtEpochMillis = firstMoment,
            ),
        )
        dao.insert(
            TransactionEntity(
                type = TransactionType.TRANSFER,
                amountVnd = 6_000L,
                occurredAtEpochMillis = at("2026-10-16", "11:00"),
                description = "Move to savings",
                createdAtEpochMillis = firstMoment,
                updatedAtEpochMillis = firstMoment,
            ),
        )
        dao.insert(
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amountVnd = 900L,
                occurredAtEpochMillis = at("2026-10-16", "12:00"),
                description = "Not confirmed",
                confirmation = ConfirmationStatus.DRAFT,
                createdAtEpochMillis = firstMoment,
                updatedAtEpochMillis = firstMoment,
            ),
        )

        val aggregate = query.confirmedLedgerAggregate(monthRange())

        assertEquals(chunked * 100L + 700L, aggregate.spending.totalVnd)
        assertEquals(chunked + 1, aggregate.spending.recordCount)
        assertFalse(aggregate.spending.overflowed)
        assertEquals(5_000L, aggregate.income.totalVnd)
        assertEquals(1, aggregate.income.recordCount)
        assertEquals(6_000L, aggregate.transfers.totalVnd)
        assertEquals(1, aggregate.transfers.recordCount)
        assertEquals(1, aggregate.excludedDraftCount)

        // The days are the range's own days, and the midnight split still holds.
        assertEquals(2, aggregate.dailySpending.size)
        assertEquals("2026-10-15", aggregate.dailySpending.first().dayText)
        assertEquals(chunked * 100L, aggregate.dailySpending.first().totalVnd)
        assertEquals("2026-10-16", aggregate.dailySpending.last().dayText)
        assertEquals(700L, aggregate.dailySpending.last().totalVnd)

        // The drill-down of the same scope holds exactly the records the figure summed.
        var rows = 0
        var offset = 0
        while (true) {
            val page = query.spendingDrillDown(
                range = monthRange(),
                bounds = QueryPageRequest(LIST_PAGE_SIZE, offset = offset),
            )
            rows += page.items.size
            val next = page.nextOffset ?: break
            offset = next
        }
        assertEquals(aggregate.spending.recordCount, rows)
    }

    /** A total that overflows is still a proven lower bound when the range spans chunks. */
    @Test
    fun `a total past the largest whole-dong value is a proven lower bound across chunks`() =
        runTest {
            val dao = database.transactionDao()
            val firstMoment = at("2026-10-15", "09:00")
            // One huge amount in the first chunk of the fold.
            dao.insert(
                TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amountVnd = Long.MAX_VALUE,
                    occurredAtEpochMillis = firstMoment,
                    description = "Huge one",
                    createdAtEpochMillis = firstMoment,
                    updatedAtEpochMillis = firstMoment,
                ),
            )
            // One huge amount in the last chunk of the fold, so the overflow happens
            // halfway through the range and not in the first read.
            repeat(LIST_PAGE_SIZE) { index ->
                dao.insert(
                    TransactionEntity(
                        type = TransactionType.EXPENSE,
                        amountVnd = 10L,
                        occurredAtEpochMillis = firstMoment + (index + 1) * 1_000L,
                        description = "Small $index",
                        createdAtEpochMillis = firstMoment,
                        updatedAtEpochMillis = firstMoment,
                    ),
                )
            }
            dao.insert(
                TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amountVnd = Long.MAX_VALUE,
                    occurredAtEpochMillis = firstMoment + (LIST_PAGE_SIZE + 1) * 1_000L,
                    description = "Huge two",
                    createdAtEpochMillis = firstMoment,
                    updatedAtEpochMillis = firstMoment,
                ),
            )

            val aggregate = query.confirmedLedgerAggregate(monthRange())

            assertTrue(aggregate.spending.overflowed)
            assertEquals(Long.MAX_VALUE, aggregate.spending.totalVnd)
            assertEquals(LIST_PAGE_SIZE + 2, aggregate.spending.recordCount)
            val bucket = aggregate.spending.categoryTotals.single()
            assertTrue(bucket.overflowed)
            assertEquals(Long.MAX_VALUE, bucket.totalVnd)
        }

    /** The live figure follows a correction, even one that changes no count. */
    @Test
    fun `the live figure follows a correction of a record of the range`() = runTest {
        val seeded = seedKnownLedger()

        val figures = mutableListOf<LedgerAggregate>()
        val job = launch {
            query.observeConfirmedLedgerAggregate(monthRange()).collect { figures += it }
        }
        // The first emission is the stored figure.
        while (figures.isEmpty()) {
            kotlinx.coroutines.yield()
        }
        assertEquals(195_000L, figures.last().spending.totalVnd)

        // A correction changes the amount of one record and no count at all.
        repository.editTransaction(
            transactionId = seeded.lunch,
            expectedRevision = 0,
            correction = TransactionCorrection(
                type = TransactionType.EXPENSE,
                amountVnd = 50_000L,
                occurredAtEpochMillis = at("2026-10-15", "09:00"),
                description = "Lunch",
                categoryId = seeded.food,
            ),
        )
        while (figures.last().spending.totalVnd == 195_000L) {
            kotlinx.coroutines.yield()
        }

        assertEquals(200_000L, figures.last().spending.totalVnd)
        assertEquals(3, figures.last().spending.recordCount)
        job.cancel()
    }

    /**
     * The draft-context read is the user's own words beside the bank evidence: exact
     * excerpts, a bounded page, and a truthful stale state instead of an old offset
     * presented as the current source.
     */
    @Test
    fun `draft context reads carry exact excerpts, stay bounded, and report a stale source`() =
        runTest {
            val source = "S66: Cafe 50000; Book 20000"
            val captureId = CaptureMessageRepository(database).saveQuickCapture(source)
            val events = EventContextRepository(database)
            val cafeStart = source.indexOf("Cafe")
            val cafe = events.recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = cafeStart,
                    spanEnd = cafeStart + "Cafe 50000".length,
                    normalizedText = "Cafe purchase",
                    amountVnd = 50_000L,
                ),
                extractionVersion = "user-manual-v1",
            ) as NoteEventWriteResult.Stored
            val bookStart = source.indexOf("Book")
            val book = events.recordEvent(
                captureId = captureId,
                expectedCaptureRevision = 0,
                interpretation = NoteEventInterpretation(
                    spanStart = bookStart,
                    spanEnd = bookStart + "Book 20000".length,
                    normalizedText = "Book purchase",
                    amountVnd = 20_000L,
                ),
                extractionVersion = "user-manual-v1",
            ) as NoteEventWriteResult.Stored

            // Before any link, the manual path carries the same exact excerpt.
            val unrecorded = query.unrecordedNoteEvents(QueryPageRequest(limit = LIST_PAGE_SIZE))
            assertEquals(
                setOf("Cafe 50000", "Book 20000"),
                unrecorded.items.mapNotNull { it.excerpt }.toSet(),
            )

            val draftId = IntakeRepository(database).storePermitted(
                sourcePackage = "com.vietinbank.ipay",
                notificationKey = "0|com.vietinbank.ipay|1|s66|100",
                postedAtEpochMillis = 1_792_745_400_000L,
                content = PermittedNotificationContent(
                    title = "VietinBank",
                    text = listOf(
                        "VietinBank: 23/10/2026 10:30",
                        "GD: -50,000 VND",
                        "ND: Cafe",
                    ).joinToString("\n"),
                    bigText = null,
                ),
            )
            assertTrue(events.proposeLink(cafe.eventId, draftId) is ContextLinkResult.Linked)
            assertTrue(events.proposeLink(book.eventId, draftId) is ContextLinkResult.Linked)

            val firstPage = query.draftContextLinks(draftId, QueryPageRequest(limit = 1))
            assertEquals(1, firstPage.items.size)
            assertTrue(firstPage.hasMore)
            val cafeLink = firstPage.items.single()
            assertEquals(cafe.eventId, cafeLink.eventId)
            assertEquals(ContextLinkState.PROPOSED, cafeLink.state)
            assertEquals(captureId, cafeLink.captureId)
            assertEquals("Cafe 50000", cafeLink.excerpt)
            assertEquals("Cafe purchase", cafeLink.readingText)
            assertEquals(50_000L, cafeLink.amountVnd)
            assertTrue(cafeLink.sourceCurrent)

            val secondPage = query.draftContextLinks(
                draftId,
                QueryPageRequest(limit = 1, offset = 1),
            )
            assertFalse(secondPage.hasMore)
            assertEquals("Book 20000", secondPage.items.single().excerpt)

            // A refusal outlives the source it was made against; the accepted path does not.
            assertTrue(
                events.rejectLink(book.eventId, draftId, expectedRevision = 0) is
                    ContextLinkResult.Linked,
            )
            CaptureMessageRepository(database).revise(
                id = captureId,
                expectedRevision = 0,
                bodyText = "S66: Cafe 50000 edited; Book 20000",
            )

            val afterEdit = query.draftContextLinks(draftId, QueryPageRequest(limit = LIST_PAGE_SIZE))
            val retained = afterEdit.items.single()
            assertEquals(book.eventId, retained.eventId)
            assertEquals(ContextLinkState.REJECTED, retained.state)
            assertNull(retained.excerpt)
            assertFalse(retained.sourceCurrent)
            assertEquals(1, retained.captureRevision)
            assertNull(retained.readingText)
        }
}
