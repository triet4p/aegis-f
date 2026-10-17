package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCategorySuggestionMachineTest {

    private fun category(
        id: Long,
        name: String,
        archived: Boolean = false,
        updatedAt: Long = 1L,
    ) = CategoryEntity(
        id = id,
        name = name,
        archived = archived,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = updatedAt,
    )

    private fun intent(
        form: Any = Any(),
        description: String = "Lunch at a cafe",
        type: TransactionType = TransactionType.EXPENSE,
    ) = ManualSuggestionIntent(form, description, type)

    private fun request(
        intent: ManualSuggestionIntent = intent(),
        categories: List<CategoryEntity> = listOf(category(1L, "Food")),
    ) = ManualSuggestionRequest(intent, categories)

    private fun active(category: CategoryEntity) =
        LayaCandidatePlanner.CurrentResolution.ActiveCategory(
            category = category,
            source = LayaCandidatePlanner.SuggestionSource.MODEL,
            confidence = null,
            uncertainty = null,
        )

    @Test
    fun `category field changes only after explicit acceptance and no record is saved`() {
        val machine = ManualCategorySuggestionMachine()
        val food = category(1L, "Food")
        val request = request(categories = listOf(food))
        val token = checkNotNull(machine.begin(request))
        var selectedCategoryId: Long? = null
        var savedRecordCount = 0

        assertTrue(
            machine.complete(token, request.intent, request.categories, request.categories, active(food)),
        )
        assertEquals(ManualCategorySuggestionState.Suggested(food, null), machine.state)
        assertNull(selectedCategoryId)
        assertEquals(0, savedRecordCount)

        selectedCategoryId = machine.acceptSuggestion()?.id
        assertEquals(food.id, selectedCategoryId)
        assertEquals(ManualCategorySuggestionState.Accepted(food, null), machine.state)
        assertEquals(0, savedRecordCount)
    }

    @Test
    fun `manual category choice invalidates a pending result`() {
        val machine = ManualCategorySuggestionMachine()
        val currentIntent = intent()
        val categories = listOf(category(1L, "Food"))
        val token = checkNotNull(machine.begin(request(currentIntent, categories)))

        machine.chooseManually(currentIntent)

        assertFalse(machine.complete(token, currentIntent, categories, categories, active(categories.single())))
        assertEquals(ManualCategorySuggestionState.ManuallySelected, machine.state)
        assertNull(machine.begin(request(currentIntent, categories)))
        assertEquals(ManualCategorySuggestionState.ManuallySelected, machine.state)
    }

    @Test
    fun `ignored result stays ignored while the same description is pending`() {
        val machine = ManualCategorySuggestionMachine()
        val currentIntent = intent()
        val categories = listOf(category(1L, "Food"))
        val token = checkNotNull(machine.begin(request(currentIntent, categories)))

        machine.ignore(currentIntent)

        assertFalse(machine.complete(token, currentIntent, categories, categories, active(categories.single())))
        val changedCategories = categories + category(2L, "Transport")
        assertNull(machine.begin(request(currentIntent, changedCategories)))
        assertEquals(ManualCategorySuggestionState.Ignored, machine.state)
    }

    @Test
    fun `description kind and reentered form reject late and out of order results`() {
        val machine = ManualCategorySuggestionMachine()
        val food = category(1L, "Food")
        val categories = listOf(food)
        val firstIntent = intent(form = "first-form")
        val first = checkNotNull(machine.begin(request(firstIntent, categories)))
        val changedDescription = firstIntent.copy(description = "Monthly rent")
        val second = checkNotNull(machine.begin(request(changedDescription, categories)))
        val changedKind = changedDescription.copy(type = TransactionType.INCOME)
        machine.notApplicableToKind()
        assertEquals(ManualCategorySuggestionState.NotApplicableToKind, machine.state)
        assertFalse(machine.complete(second, changedKind, categories, categories, active(food)))
        val reenteredForm = changedKind.copy(
            formIdentity = "second-form",
            type = TransactionType.EXPENSE,
        )
        val third = checkNotNull(machine.begin(request(reenteredForm, categories)))

        assertFalse(machine.complete(first, reenteredForm, categories, categories, active(food)))
        assertFalse(machine.complete(second, reenteredForm, categories, categories, active(food)))
        assertTrue(machine.complete(third, reenteredForm, categories, categories, active(food)))
        assertEquals(ManualCategorySuggestionState.Suggested(food, null), machine.state)
    }

    @Test
    fun `category snapshot changes reject an otherwise active suggestion`() {
        val machine = ManualCategorySuggestionMachine()
        val food = category(1L, "Food")
        val initialCategories = listOf(food)
        val request = request(intent(), initialCategories)
        val token = checkNotNull(machine.begin(request))
        val currentCategories = listOf(food, category(2L, "Transport"))

        assertFalse(
            machine.complete(
                token,
                request.intent,
                currentCategories,
                currentCategories,
                active(food),
            ),
        )
        assertEquals(ManualCategorySuggestionState.Stale(), machine.state)
    }

    @Test
    fun `renamed or archived suggested category is rejected against fresh rows`() {
        val machine = ManualCategorySuggestionMachine()
        val original = category(1L, "Food")
        val originalRows = listOf(original)
        val currentIntent = intent()
        val renamedToken = checkNotNull(machine.begin(request(currentIntent, originalRows)))
        val renamed = category(1L, "Meals", updatedAt = 2L)
        val renamedResolution = LayaCandidatePlanner.resolveCurrent(
            LayaCandidatePlanner.Decision.Category(
                categoryId = original.id,
                snapshotName = original.name,
                source = LayaCandidatePlanner.SuggestionSource.MODEL,
                confidence = null,
            ),
            listOf(renamed),
        )

        assertFalse(
            machine.complete(
                renamedToken,
                currentIntent,
                listOf(renamed),
                listOf(renamed),
                renamedResolution,
            ),
        )
        assertEquals(ManualCategorySuggestionState.Stale(), machine.state)

        val archivedMachine = ManualCategorySuggestionMachine()
        val archivedToken = checkNotNull(archivedMachine.begin(request(currentIntent, originalRows)))
        val archived = original.copy(archived = true, updatedAtEpochMillis = 2L)
        val archivedResolution = LayaCandidatePlanner.resolveCurrent(
            LayaCandidatePlanner.Decision.Category(
                categoryId = original.id,
                snapshotName = original.name,
                source = LayaCandidatePlanner.SuggestionSource.MODEL,
                confidence = null,
            ),
            listOf(archived),
        )
        assertFalse(
            archivedMachine.complete(
                archivedToken,
                currentIntent,
                listOf(archived),
                listOf(archived),
                archivedResolution,
            ),
        )
        assertEquals(ManualCategorySuggestionState.Stale(), archivedMachine.state)
    }

    @Test
    fun `unknown and unavailable outcomes leave the manual form reviewable`() {
        val machine = ManualCategorySuggestionMachine()
        val currentIntent = intent()
        val categories = listOf(category(1L, "Food"))
        val unknownToken = checkNotNull(machine.begin(request(currentIntent, categories)))
        val noFit = LayaCandidatePlanner.CurrentResolution.ManualReview(
            LayaCandidatePlanner.ManualReason.MODEL_NO_FIT,
            uncertainty = null,
        )

        assertTrue(machine.complete(unknownToken, currentIntent, categories, categories, noFit))
        assertEquals(
            ManualCategorySuggestionState.NoFit(LayaCandidatePlanner.ManualReason.MODEL_NO_FIT, null),
            machine.state,
        )

        val unavailableToken = checkNotNull(machine.begin(request(currentIntent, categories)))
        assertTrue(
            machine.unavailable(
                unavailableToken,
                currentIntent,
                categories,
                categories,
                UnavailableReason.MISSING,
            ),
        )
        assertEquals(
            ManualCategorySuggestionState.Unavailable(UnavailableReason.MISSING),
            machine.state,
        )
        assertNull(machine.acceptSuggestion())
    }
}
