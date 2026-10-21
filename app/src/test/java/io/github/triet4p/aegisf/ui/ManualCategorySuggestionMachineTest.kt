package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.data.laya.LayaEffectiveContext
import io.github.triet4p.aegisf.data.CategoryEntity
import io.github.triet4p.aegisf.data.ManualCategoryDecision
import io.github.triet4p.aegisf.data.TransactionType
import io.github.triet4p.aegisf.data.decideRememberedBeforeInference
import io.github.triet4p.aegisf.data.laya.LayaCandidatePlanner
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `remembered rule resolves before inference and misses use the model`() = runTest {
        val food = category(1L, "Food")
        var inferenceCalls = 0
        val matched = decideRememberedBeforeInference(
            description = "Synthetic cafe pattern",
            context = LayaEffectiveContext.of("Synthetic cafe pattern"),
            activeCategories = listOf(food),
            findRememberedCategory = { description ->
                if (description == "Synthetic cafe pattern") food else null
            },
            infer = { _, _ ->
                inferenceCalls += 1
                "model"
            },
        )

        assertEquals(ManualCategoryDecision.Remembered(food), matched)
        assertEquals(0, inferenceCalls)

        val missed = decideRememberedBeforeInference(
            description = "Synthetic cafe pattern!",
            context = LayaEffectiveContext.of("Synthetic cafe pattern!"),
            activeCategories = listOf(food),
            findRememberedCategory = { description ->
                if (description == "Synthetic cafe pattern") food else null
            },
            infer = { _, _ ->
                inferenceCalls += 1
                "model"
            },
        )

        assertEquals(ManualCategoryDecision.Inferred("model"), missed)
        assertEquals(1, inferenceCalls)
    }

    @Test
    fun `archived remembered category falls through without assigning the stale id`() = runTest {
        val archived = category(1L, "Food", archived = true)
        val current = category(2L, "Transport")
        var inferenceCalls = 0
        val decision = decideRememberedBeforeInference(
            description = "Synthetic cafe pattern",
            context = LayaEffectiveContext.of("Synthetic cafe pattern"),
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
    fun `remembered category stays review only and archived completion is rejected`() {
        val food = category(1L, "Food")
        val intent = intent()
        val machine = ManualCategorySuggestionMachine()
        val token = checkNotNull(machine.begin(request(intent, listOf(food))))
        var selectedCategoryId: Long? = null
        var savedRecordCount = 0

        assertTrue(
            machine.completeRemembered(token, intent, listOf(food), listOf(food), food),
        )
        assertNull(selectedCategoryId)
        assertEquals(0, savedRecordCount)
        selectedCategoryId = machine.acceptRememberedRule()?.id
        assertEquals(food.id, selectedCategoryId)
        assertEquals(0, savedRecordCount)


        val manualMachine = ManualCategorySuggestionMachine()
        val manualToken = checkNotNull(manualMachine.begin(request(intent, listOf(food))))
        assertTrue(
            manualMachine.completeRemembered(
                manualToken,
                intent,
                listOf(food),
                listOf(food),
                food,
            ),
        )
        manualMachine.chooseManually(intent)
        assertEquals(
            ManualCategorySuggestionState.Remembered(food, manualSelection = true),
            manualMachine.state,
        )
        assertNull(manualMachine.begin(request(intent, listOf(food))))
        assertEquals(
            ManualCategorySuggestionState.Remembered(food, manualSelection = true),
            manualMachine.state,
        )

        val staleMachine = ManualCategorySuggestionMachine()
        val archived = food.copy(archived = true)
        val archivedToken = checkNotNull(staleMachine.begin(request(intent, listOf(archived))))
        assertTrue(
            staleMachine.completeRemembered(
                archivedToken,
                intent,
                listOf(archived),
                listOf(archived),
                food,
            ),
        )
        assertEquals(
            ManualCategorySuggestionState.Stale(
                LayaCandidatePlanner.StaleReason.MISSING_OR_ARCHIVED,
            ),
            staleMachine.state,
        )
    }

    @Test
    fun `late remembered rule lookup cannot apply to changed form or category snapshot`() {
        val food = category(1L, "Food")
        val categories = listOf(food)
        val originalIntent = intent(form = "first-form")
        val machine = ManualCategorySuggestionMachine()
        val descriptionToken = checkNotNull(machine.begin(request(originalIntent, categories)))

        assertFalse(
            machine.completeRemembered(
                descriptionToken,
                originalIntent.copy(description = "different pattern"),
                categories,
                categories,
                food,
            ),
        )
        assertEquals(ManualCategorySuggestionState.Idle, machine.state)

        val categoryToken = checkNotNull(machine.begin(request(originalIntent, categories)))
        val changedCategories = categories + category(2L, "Transport")
        assertFalse(
            machine.completeRemembered(
                categoryToken,
                originalIntent,
                changedCategories,
                changedCategories,
                food,
            ),
        )
        assertEquals(ManualCategorySuggestionState.Stale(), machine.state)
    }

    @Test
    fun `ranked distribution allows a non top choice without saving`() {
        val machine = ManualCategorySuggestionMachine()
        val top = category(1L, "Food")
        val other = category(2L, "Transport")
        val categories = listOf(top, other)
        val request = request(intent(), categories)
        val token = checkNotNull(machine.begin(request))
        val distribution = LayaCandidatePlanner.ScoredDistribution(
            options = listOf(
                LayaCandidatePlanner.ResolvedOption(1L, "Food", 0.5, top),
                LayaCandidatePlanner.ResolvedOption(2L, "Transport", 0.3, other),
            ),
            unknownProbability = 0.2,
            confidence = 0.1,
        )
        assertTrue(
            machine.complete(
                token,
                request.intent,
                request.categories,
                request.categories,
                LayaCandidatePlanner.CurrentResolution.ActiveCategory(
                    category = top,
                    source = LayaCandidatePlanner.SuggestionSource.MODEL,
                    confidence = 0.1,
                    uncertainty = null,
                    scores = null,
                    distribution = distribution,
                ),
            ),
        )
        // Tapping a non-top row only stages it; the machine itself saves nothing.
        val staged = machine.chooseDistributionOption(2L)
        assertEquals(other.id, staged?.id)
        val accepted = machine.state as ManualCategorySuggestionState.Accepted
        assertEquals(other.id, accepted.category.id)
        assertEquals(distribution, accepted.distribution)
    }

    @Test
    fun `unknown choice and invalid rows never assign a category`() {
        val machine = ManualCategorySuggestionMachine()
        val top = category(1L, "Food")
        val stale = category(2L, "Transport")
        val categories = listOf(top, stale)
        val request = request(intent(), categories)
        val token = checkNotNull(machine.begin(request))
        val distribution = LayaCandidatePlanner.ScoredDistribution(
            options = listOf(
                LayaCandidatePlanner.ResolvedOption(1L, "Food", 0.4, top),
                // Visible with its model share, but no current category: not selectable.
                LayaCandidatePlanner.ResolvedOption(2L, "Transport", 0.35, null),
            ),
            unknownProbability = 0.25,
            confidence = 0.05,
        )
        assertTrue(
            machine.complete(
                token,
                request.intent,
                request.categories,
                request.categories,
                LayaCandidatePlanner.CurrentResolution.ActiveCategory(
                    category = top,
                    source = LayaCandidatePlanner.SuggestionSource.MODEL,
                    confidence = 0.05,
                    uncertainty = null,
                    scores = null,
                    distribution = distribution,
                ),
            ),
        )
        assertEquals(null, machine.chooseDistributionOption(2L))
        assertTrue(machine.state is ManualCategorySuggestionState.Suggested)
        assertTrue(machine.chooseNoCategoryOption())
        assertEquals(ManualCategorySuggestionState.NoCategory, machine.state)
    }

    @Test
    fun `unknown top distribution stays selectable for manual review`() {
        val machine = ManualCategorySuggestionMachine()
        val first = category(1L, "Food")
        val second = category(2L, "Transport")
        val categories = listOf(first, second)
        val request = request(intent(), categories)
        val token = checkNotNull(machine.begin(request))
        val distribution = LayaCandidatePlanner.ScoredDistribution(
            options = listOf(
                LayaCandidatePlanner.ResolvedOption(2L, "Transport", 0.25, second),
                LayaCandidatePlanner.ResolvedOption(1L, "Food", 0.2, first),
            ),
            unknownProbability = 0.55,
            confidence = 0.1,
        )
        assertTrue(
            machine.complete(
                token,
                request.intent,
                request.categories,
                request.categories,
                LayaCandidatePlanner.CurrentResolution.ManualReview(
                    LayaCandidatePlanner.ManualReason.MODEL_NO_FIT,
                    uncertainty = null,
                    scores = null,
                    distribution = distribution,
                ),
            ),
        )
        val staged = machine.chooseDistributionOption(1L)
        assertEquals(first.id, staged?.id)
        assertTrue(machine.state is ManualCategorySuggestionState.Accepted)
    }

    @Test
    fun `an over-budget request is reported as a refusal without changing the selection`() {
        val machine = ManualCategorySuggestionMachine()
        val categories = listOf(category(1L, "Food"))
        val request = request(intent(), categories)
        val token = checkNotNull(machine.begin(request))
        assertTrue(
            machine.complete(
                token,
                request.intent,
                request.categories,
                request.categories,
                LayaCandidatePlanner.CurrentResolution.ManualReview(
                    LayaCandidatePlanner.ManualReason.EFFECTIVE_INPUT_OVER_BUDGET,
                    uncertainty = null,
                ),
            ),
        )
        assertEquals(
            ManualCategorySuggestionState.NoFit(
                LayaCandidatePlanner.ManualReason.EFFECTIVE_INPUT_OVER_BUDGET,
                null,
            ),
            machine.state,
        )
        // The refusal names what happened and leaves the user's own choice alone.
        val message = effectiveInputOverBudgetMessage()
        assertTrue(message.contains("longer than the model's input budget"))
        assertTrue(message.contains("no suggestion was made"))
        assertTrue(message.contains("the model was not asked"))
        assertEquals(null, machine.acceptSuggestion())
    }
}
