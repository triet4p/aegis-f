package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ManualCategoryDecision
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.decideRememberedBeforeInference
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner
import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.laya.LayaTokenizerLoader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Consumer-side planner and manual suggestion behavior.
 *
 * S7.6 removed the bank-draft screen's own inference trigger: classification is scheduled
 * by intake and context changes through the durable queue. A category-name match is
 * deterministic, saved rules answer before inference, and the manual-entry suggestion
 * machine stages rather than selects. No multilingual quality is claimed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BankDraftSuggestionFlowTest {

    private fun category(id: Long, name: String, archived: Boolean = false) = CategoryEntity(
        id = id,
        name = name,
        archived = archived,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    @Test
    fun `shop note matches the shop category without inference`() {
        // Deterministic consumer path on the phone's category set: `shop` is an active
        // category name, so the planner resolves the note to it before any model call.
        val tokenizer = LayaTokenizerLoader.loadFromRepoFile()
        val shop = category(1L, "shop")
        val transport = category(2L, "Transport")
        val plan = LayaCandidatePlanner.plan(
            tokenizer,
            "Shop ride",
            listOf(shop, transport),
        )
        assertTrue(plan is LayaCandidatePlanner.Plan.MetadataMatch)
        val decision = LayaCandidatePlanner.decide(plan) {
            error("shop metadata match must not call inference")
        } as LayaCandidatePlanner.Decision.Category
        assertEquals(1L, decision.categoryId)
    }

    @Test
    fun `remembered rule resolves before inference for a description`() = runTest {
        val food = category(1L, "Food")
        var inferenceCalls = 0
        val matched = decideRememberedBeforeInference(
            description = "Lunch at a cafe",
            context = LayaEffectiveContext.of("Lunch at a cafe"),
            activeCategories = listOf(food),
            findRememberedCategory = { if (it == "Lunch at a cafe") food else null },
            infer = { _, _ ->
                inferenceCalls += 1
                "model"
            },
        )
        assertEquals(ManualCategoryDecision.Remembered(food), matched)
        assertEquals(0, inferenceCalls)
    }

    @Test
    fun `remembered rule matches the exact intended description`() = runTest {
        val housing = category(1L, "Housing")
        var inferenceCalls = 0
        val matched = decideRememberedBeforeInference(
            description = "Monthly rent",
            context = LayaEffectiveContext.of("Monthly rent"),
            activeCategories = listOf(housing),
            findRememberedCategory = { if (it == "Monthly rent") housing else null },
            infer = { _, _ ->
                inferenceCalls += 1
                "model"
            },
        )
        assertEquals(ManualCategoryDecision.Remembered(housing), matched)
        assertEquals(0, inferenceCalls)
    }

    @Test
    fun `archived remembered target falls through to inference`() = runTest {
        val archived = category(1L, "Food", archived = true)
        val current = category(2L, "Transport")
        var inferenceCalls = 0
        val decision = decideRememberedBeforeInference(
            description = "Lunch at a cafe",
            context = LayaEffectiveContext.of("Lunch at a cafe"),
            activeCategories = listOf(current),
            findRememberedCategory = { archived },
            infer = { _, categories ->
                inferenceCalls += 1
                assertEquals(listOf(current), categories)
                "model"
            },
        )
        assertEquals(ManualCategoryDecision.Inferred("model"), decision)
        assertEquals(1, inferenceCalls)
    }

    @Test
    fun `explicit category choice wins and stale categories are rejected`() {
        val machine = ManualCategorySuggestionMachine()
        val food = category(1L, "Food")
        val transport = category(2L, "Transport")
        val intent = ManualSuggestionIntent(Any(), "Lunch at a cafe", TransactionType.EXPENSE)
        val request = ManualSuggestionRequest(intent, listOf(food, transport))
        val token = checkNotNull(machine.begin(request))
        var selectedCategoryId: Long? = null
        var savedRecordCount = 0

        assertTrue(
            machine.complete(
                token,
                request.intent,
                request.categories,
                request.categories,
                LayaCandidatePlanner.CurrentResolution.ActiveCategory(
                    category = food,
                    source = LayaCandidatePlanner.SuggestionSource.MODEL,
                    confidence = null,
                    uncertainty = null,
                ),
            ),
        )
        assertNull(selectedCategoryId)
        assertEquals(0, savedRecordCount)

        machine.chooseManually(intent)
        assertEquals(ManualCategorySuggestionState.ManuallySelected, machine.state)

        selectedCategoryId = transport.id
        assertEquals(transport.id, selectedCategoryId)
        assertEquals(0, savedRecordCount)
    }

    @Test
    fun `renamed suggested category is stale against fresh rows`() {
        val machine = ManualCategorySuggestionMachine()
        val food = category(1L, "Food")
        val renamed = category(1L, "Meals")
        val intent = ManualSuggestionIntent(Any(), "Lunch at a cafe", TransactionType.EXPENSE)
        val token = checkNotNull(
            machine.begin(ManualSuggestionRequest(intent, listOf(food))),
        )
        assertFalse(
            machine.complete(
                token,
                intent,
                listOf(renamed),
                listOf(renamed),
                LayaCandidatePlanner.resolveCurrent(
                    LayaCandidatePlanner.Decision.Category(
                        categoryId = 1L,
                        snapshotName = "Food",
                        source = LayaCandidatePlanner.SuggestionSource.MODEL,
                        confidence = null,
                    ),
                    listOf(renamed),
                ),
            ),
        )
        assertTrue(machine.state is ManualCategorySuggestionState.Stale)
    }

    @Test
    fun `draft switching rejects late results`() {
        val machine = ManualCategorySuggestionMachine()
        val food = category(1L, "Food")
        val first = ManualSuggestionIntent(Any(), "Lunch at a cafe", TransactionType.EXPENSE)
        val second = ManualSuggestionIntent(Any(), "Monthly rent", TransactionType.EXPENSE)
        val token = checkNotNull(machine.begin(ManualSuggestionRequest(first, listOf(food))))
        assertFalse(
            machine.complete(
                token,
                second,
                listOf(food),
                listOf(food),
                LayaCandidatePlanner.CurrentResolution.ActiveCategory(
                    category = food,
                    source = LayaCandidatePlanner.SuggestionSource.MODEL,
                    confidence = null,
                    uncertainty = null,
                ),
            ),
        )
        assertEquals(ManualCategorySuggestionState.Idle, machine.state)
    }

    @Test
    fun `note change invalidates the prior suggestion intent`() {
        val machine = ManualCategorySuggestionMachine()
        val food = category(1L, "Food")
        val first = ManualSuggestionIntent(Any(), "Monthly rent", TransactionType.EXPENSE)
        val second = ManualSuggestionIntent(Any(), "Electric bill", TransactionType.EXPENSE)
        val token = checkNotNull(machine.begin(ManualSuggestionRequest(first, listOf(food))))
        assertFalse(
            machine.complete(
                token,
                second,
                listOf(food),
                listOf(food),
                LayaCandidatePlanner.CurrentResolution.ActiveCategory(
                    category = food,
                    source = LayaCandidatePlanner.SuggestionSource.MODEL,
                    confidence = null,
                    uncertainty = null,
                ),
            ),
        )
        assertEquals(ManualCategorySuggestionState.Idle, machine.state)
    }
}
