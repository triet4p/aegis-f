package io.github.triet4p.aegisf.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bounded rule read behind the Categories & Rules screen.
 *
 * It protects the observable contract the UI depends on: rules are ordered by their canonical
 * description, each row carries the category it really names, an archived category is still
 * reported with that state, and a page says truthfully whether more rules exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryRuleQueryTest {

    private class FixedClock(private val now: Long = 1_700_000_000_000L) : LedgerClock {
        override fun nowEpochMillis(): Long = now
    }

    private lateinit var context: Context
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository
    private lateinit var query: WorkflowQueryService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LedgerDatabase.NAME)
        database = LedgerDatabase.build(context)
        query = WorkflowQueryService(database)
        repository = LedgerRepository(
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
            rememberedCategoryRuleDao = database.rememberedCategoryRuleDao(),
            captureMessageDao = database.captureMessageDao(),
            clock = FixedClock(),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(LedgerDatabase.NAME)
    }

    @Test
    fun `rules carry their category and are ordered by the canonical description`() = runTest {
        val food = repository.addCategory("Food")
        val transport = repository.addCategory("Transport")
        assertTrue(repository.rememberCategoryForDescription("Circle K", food))
        assertTrue(repository.rememberCategoryForDescription("Grab ride", transport))

        val page = query.categoryRules(
            CategoryRuleQuery(QueryPageRequest(limit = 10, offset = 0)),
        )

        assertEquals(
            listOf("circle k" to food, "grab ride" to transport),
            page.items.map { it.patternKey to it.categoryId },
        )
        assertEquals(listOf("Food", "Transport"), page.items.map { it.categoryName })
        assertFalse(page.hasMore)
        assertEquals(2, query.observeCategoryRuleCount().first())
    }

    @Test
    fun `an archived category is still named and a later page reports more`() = runTest {
        val food = repository.addCategory("Food")
        val transport = repository.addCategory("Transport")
        assertTrue(repository.rememberCategoryForDescription("Circle K", food))
        assertTrue(repository.rememberCategoryForDescription("Grab ride", transport))
        repository.archiveCategory(food)

        val first = query.categoryRules(
            CategoryRuleQuery(QueryPageRequest(limit = 1, offset = 0)),
        )
        assertTrue(first.hasMore)
        assertEquals("circle k", first.items.single().patternKey)
        assertEquals("Food", first.items.single().categoryName)
        assertTrue(first.items.single().categoryArchived)

        val second = query.categoryRules(
            CategoryRuleQuery(QueryPageRequest(limit = 1, offset = 1)),
        )
        assertFalse(second.hasMore)
        assertEquals("grab ride", second.items.single().patternKey)
        assertFalse(second.items.single().categoryArchived)
    }
}
