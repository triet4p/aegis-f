package io.github.triet4p.aegisf.data.laya

import io.github.triet4p.aegisf.data.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayaCandidatePlannerTest {

    private fun tokenizer(): LayaTokenizer = LayaTokenizerLoader.loadFromRepoFile()

    private fun category(id: Long, name: String, archived: Boolean = false) = CategoryEntity(
        id = id,
        name = name,
        archived = archived,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
    )

    @Test
    fun `keeps every active choice when exact sequence budget fits`() {
        val tokenizer = tokenizer()
        val categories = listOf(
            category(31L, "Food"),
            category(32L, "Transport"),
            category(33L, "Housing", archived = true),
            category(34L, "Health"),
        )

        val plan = LayaCandidatePlanner.plan(tokenizer, "Coffee after work", categories)
            as LayaCandidatePlanner.Plan.Model

        assertEquals(listOf(31L, 32L, 34L), plan.categories.map { it.id })
        assertEquals(plan.categories.size + 1, plan.built.markerPositions.size)
        assertTrue(plan.built.headTokenCount <= LayaRuntimeContract.HEAD_MAX_LEN)
        assertTrue(plan.built.inputIds.size <= LayaRuntimeContract.MAX_LEN)
        assertTrue(plan.built.markerPositions.all { plan.built.inputIds[it] == tokenizer.maskId })
        assertEquals(null, plan.uncertainty)
        val optionTokenCounts = (
            plan.categories.map { LayaSequence.measureOptionTokens(tokenizer, it.name) } +
                LayaSequence.measureOptionTokens(tokenizer, LayaSequence.renderUnknown())
            ).toIntArray()
        val measuredHead = LayaSequence.measureHeadTokens(
            questionTokens = LayaSequence.measureQuestionTokens(
                tokenizer,
                LayaRuntimeContract.INSTRUCTIONS,
            ),
            optionTokenCounts = optionTokenCounts,
            headMaxLen = LayaRuntimeContract.HEAD_MAX_LEN,
        )
        assertEquals(measuredHead, plan.built.headTokenCount)

        val longDescription = "Coffee after work " + "extra detail ".repeat(100)
        val longPlan = LayaCandidatePlanner.plan(tokenizer, longDescription, categories)
            as LayaCandidatePlanner.Plan.Model
        assertEquals(listOf(31L, 32L, 34L), longPlan.categories.map { it.id })
        assertTrue(longPlan.built.inputIds.size <= LayaRuntimeContract.MAX_LEN)
        assertEquals(longPlan.categories.size + 1, longPlan.built.markerPositions.size)
    }

    @Test
    fun `explicit correction overrides exact category metadata and conflicts stay manual`() {
        val tokenizer = tokenizer()
        val categories = listOf(category(41L, "Food"), category(42L, "Monthly rent"))
        val rule = LayaCategoryCorrectionRule(" Monthly rent! ", 41L)

        val metadata = LayaCandidatePlanner.plan(tokenizer, "Monthly rent", categories)
            as LayaCandidatePlanner.Plan.MetadataMatch
        val metadataDecision = LayaCandidatePlanner.decide(metadata) {
            error("exact metadata match must not call inference")
        } as LayaCandidatePlanner.Decision.Category
        assertEquals(42L, metadataDecision.categoryId)

        val metadataPhrase = LayaCandidatePlanner.plan(
            tokenizer,
            "Hotel monthly rent bill",
            categories,
        ) as LayaCandidatePlanner.Plan.MetadataMatch
        assertEquals(42L, metadataPhrase.category.id)

        val corrected = LayaCandidatePlanner.plan(
            tokenizer,
            "Monthly rent",
            categories,
            correctionRules = listOf(rule),
        )
        val decision = LayaCandidatePlanner.decide(corrected) {
            error("explicit correction must not call inference")
        } as LayaCandidatePlanner.Decision.Category
        assertEquals(41L, decision.categoryId)
        assertEquals(LayaCandidatePlanner.SuggestionSource.EXPLICIT_CORRECTION, decision.source)

        val conflict = LayaCandidatePlanner.plan(
            tokenizer,
            "Monthly rent",
            categories,
            correctionRules = listOf(rule, rule.copy(categoryId = 42L)),
        ) as LayaCandidatePlanner.Plan.ManualReview
        assertEquals(LayaCandidatePlanner.ManualReason.AMBIGUOUS_CORRECTIONS, conflict.reason)

        val archivedTarget = LayaCandidatePlanner.plan(
            tokenizer,
            "Monthly rent",
            listOf(categories[0], categories[1].copy(archived = true)),
            correctionRules = listOf(rule.copy(categoryId = 42L)),
        ) as LayaCandidatePlanner.Plan.ManualReview
        assertEquals(
            LayaCandidatePlanner.ManualReason.CORRECTION_TARGET_UNAVAILABLE,
            archivedTarget.reason,
        )
    }

    @Test
    fun `oversized metadata shortlist ranks deterministically and reports omitted evidence`() {
        val tokenizer = tokenizer()
        val categories = (1L..180L).map { id -> category(id, "Food") }
        val description = "Dinner near Food"
        val first = LayaCandidatePlanner.plan(tokenizer, description, categories)
            as LayaCandidatePlanner.Plan.Model
        val second = LayaCandidatePlanner.plan(tokenizer, description, categories)
            as LayaCandidatePlanner.Plan.Model

        assertEquals(first.categories.map { it.id }, second.categories.map { it.id })
        assertEquals(first.categories.map { it.id }.sorted(), first.categories.map { it.id })
        assertTrue(first.categories.size < categories.size)
        assertTrue(first.built.headTokenCount <= LayaRuntimeContract.HEAD_MAX_LEN)
        assertEquals(first.categories.size + 1, first.built.markerPositions.size)
        val uncertainty = first.uncertainty ?: error("oversized plan must disclose omitted candidates")
        assertEquals(categories.size, uncertainty.activeCategoryCount)
        assertEquals(first.categories.size, uncertainty.offeredCategoryCount)
        assertEquals(categories.size - first.categories.size, uncertainty.omittedCategoryCount)
        assertEquals(uncertainty.omittedCategoryCount, uncertainty.omittedMetadataEvidenceCount)
        val duplicateLabelChoice = LayaCandidatePlanner.modelDecision(first, 1, 0.5)
            as LayaCandidatePlanner.Decision.Category
        assertEquals(first.categories[1].id, duplicateLabelChoice.categoryId)
    }

    @Test
    fun `marker text and long duplicate labels preserve positional ids`() {
        val tokenizer = tokenizer()
        val categories = listOf(
            category(51L, "[MASK] Food"),
            category(52L, "Food " + "detail ".repeat(80)),
            category(53L, "Food"),
        )
        val plan = LayaCandidatePlanner.plan(
            tokenizer,
            "Dinner [MASK] Food",
            categories,
        ) as LayaCandidatePlanner.Plan.Model

        assertEquals(listOf(51L, 52L, 53L), plan.categories.map { it.id })
        assertEquals(plan.categories.size + 1, plan.built.markerPositions.size)
        assertTrue(plan.built.headTokenCount <= LayaRuntimeContract.HEAD_MAX_LEN)
        assertTrue(plan.built.markerPositions.all { plan.built.inputIds[it] == tokenizer.maskId })
        val decision = LayaCandidatePlanner.modelDecision(plan, bestIndex = 1, confidence = 0.7)
            as LayaCandidatePlanner.Decision.Category
        assertEquals(52L, decision.categoryId)
    }

    @Test
    fun `user category named unknown stays distinct from the no fit result`() {
        val tokenizer = tokenizer()
        val categories = listOf(category(61L, "unknown"))
        val plan = LayaCandidatePlanner.plan(tokenizer, "Ordinary memo", categories)
            as LayaCandidatePlanner.Plan.Model

        assertEquals(listOf(61L), plan.categories.map { it.id })
        assertEquals(2, plan.built.markerPositions.size)
        val userCategory = LayaCandidatePlanner.modelDecision(plan, 0, 0.6)
            as LayaCandidatePlanner.Decision.Category
        assertEquals(61L, userCategory.categoryId)
        val noFit = LayaCandidatePlanner.modelDecision(plan, 1, 0.6)
        assertTrue(noFit is LayaCandidatePlanner.Decision.Unknown)
        assertFalse(noFit is LayaCandidatePlanner.Decision.Category)
    }

    @Test
    fun `empty inputs and oversized no evidence remain unknown for manual review`() {
        val tokenizer = tokenizer()
        val noDescription = LayaCandidatePlanner.plan(tokenizer, "", listOf(category(71L, "Food")))
            as LayaCandidatePlanner.Plan.ManualReview
        assertEquals(LayaCandidatePlanner.ManualReason.EMPTY_DESCRIPTION, noDescription.reason)

        val noCategories = LayaCandidatePlanner.plan(tokenizer, "Lunch", emptyList())
            as LayaCandidatePlanner.Plan.ManualReview
        assertEquals(LayaCandidatePlanner.ManualReason.NO_ACTIVE_CATEGORIES, noCategories.reason)

        val noEvidence = LayaCandidatePlanner.plan(
            tokenizer,
            "Payment to Alex",
            (1L..180L).map { id -> category(id, "Category $id") },
        ) as LayaCandidatePlanner.Plan.ManualReview
        assertEquals(LayaCandidatePlanner.ManualReason.NO_METADATA_EVIDENCE, noEvidence.reason)
        assertEquals(180, noEvidence.uncertainty?.omittedCategoryCount)
    }

    @Test
    fun `current resolution rejects archive rename and duplicate ids`() {
        val tokenizer = tokenizer()
        val original = category(81L, "Food")
        val plan = LayaCandidatePlanner.plan(tokenizer, "Dinner at home", listOf(original))
            as LayaCandidatePlanner.Plan.Model
        val decision = LayaCandidatePlanner.modelDecision(plan, 0, 0.5)

        val unchanged = LayaCandidatePlanner.resolveCurrent(decision, listOf(original))
            as LayaCandidatePlanner.CurrentResolution.ActiveCategory
        assertEquals(81L, unchanged.category.id)

        val renamed = LayaCandidatePlanner.resolveCurrent(
            decision,
            listOf(original.copy(name = "Meals")),
        ) as LayaCandidatePlanner.CurrentResolution.StaleCategory
        assertEquals(LayaCandidatePlanner.StaleReason.RENAMED, renamed.reason)

        val archived = LayaCandidatePlanner.resolveCurrent(
            decision,
            listOf(original.copy(archived = true)),
        ) as LayaCandidatePlanner.CurrentResolution.StaleCategory
        assertEquals(LayaCandidatePlanner.StaleReason.MISSING_OR_ARCHIVED, archived.reason)

        val duplicateId = LayaCandidatePlanner.resolveCurrent(
            decision,
            listOf(original, original.copy(name = "Food copy")),
        ) as LayaCandidatePlanner.CurrentResolution.StaleCategory
        assertEquals(LayaCandidatePlanner.StaleReason.DUPLICATE_ID, duplicateId.reason)
    }

    @Test
    fun `full distribution ranks evaluated categories and unknown without fabrication`() {
        val tokenizer = tokenizer()
        val categories = listOf(
            category(71L, "Food"),
            category(72L, "Transport"),
            category(73L, "Housing"),
            category(74L, "Health"),
        )
        val plan = LayaCandidatePlanner.plan(tokenizer, "Coffee after work", categories)
            as LayaCandidatePlanner.Plan.Model
        assertEquals(listOf(71L, 72L, 73L, 74L), plan.categories.map { it.id })

        // Synthetic calibrated shares: rank order and unknown come straight from the model
        // vector; nothing is invented for unevaluated or omitted options.
        val probabilities = listOf(0.1, 0.5, 0.2, 0.05, 0.15)
        val decision = LayaCandidatePlanner.modelDecision(plan, probabilities, 1, 0.3)
            as LayaCandidatePlanner.Decision.Category
        assertEquals(72L, decision.categoryId)
        val scores = checkNotNull(decision.scores)
        assertEquals(listOf(72L, 73L, 71L, 74L), scores.ranked.map { it.categoryId })
        assertEquals(0.15, scores.unknownProbability, 1e-9)
        assertEquals(0.3, scores.confidence, 1e-9)

        val resolved = LayaCandidatePlanner.resolveCurrent(decision, categories)
            as LayaCandidatePlanner.CurrentResolution.ActiveCategory
        val distribution = checkNotNull(resolved.distribution)
        assertEquals(listOf(72L, 73L, 71L, 74L), distribution.options.map { it.categoryId })
        assertEquals(0.15, distribution.unknownProbability, 1e-9)
        assertTrue(distribution.options.all { it.currentCategory != null })
        // Full-catalog subset scope stays explicit: a fitting in-budget set has no omission.
        assertEquals(null, resolved.uncertainty)
    }

    @Test
    fun `unknown top still exposes selectable rows and rejects stale ids`() {
        val tokenizer = tokenizer()
        val first = category(81L, "Food")
        val second = category(82L, "Transport")
        val plan = LayaCandidatePlanner.plan(tokenizer, "Dinner at home", listOf(first, second))
            as LayaCandidatePlanner.Plan.Model
        val decision = LayaCandidatePlanner.modelDecision(plan, listOf(0.2, 0.25, 0.55), 2, 0.1)
        assertTrue(decision is LayaCandidatePlanner.Decision.Unknown)
        val resolved = LayaCandidatePlanner.resolveCurrent(decision, listOf(first, second))
            as LayaCandidatePlanner.CurrentResolution.ManualReview
        assertEquals(LayaCandidatePlanner.ManualReason.MODEL_NO_FIT, resolved.reason)
        val distribution = checkNotNull(resolved.distribution)
        assertEquals(listOf(82L, 81L), distribution.options.map { it.categoryId })
        assertEquals(0.55, distribution.unknownProbability, 1e-9)
        assertTrue(distribution.options.all { it.currentCategory != null })

        // A renamed top-hit resolves stale by stable id; the same invalidation shape will
        // serve permanent deletion (S4.11) without a new mechanism.
        val renamedDecision = LayaCandidatePlanner.modelDecision(
            plan,
            listOf(0.6, 0.1, 0.3),
            0,
            0.2,
        ) as LayaCandidatePlanner.Decision.Category
        val renamedResolution = LayaCandidatePlanner.resolveCurrent(
            renamedDecision,
            listOf(first.copy(name = "Meals"), second),
        )
        assertTrue(renamedResolution is LayaCandidatePlanner.CurrentResolution.StaleCategory)
    }

    @Test
    fun `rules and metadata matches carry no fabricated probabilities`() {
        val tokenizer = tokenizer()
        val categories = listOf(category(91L, "Food"), category(92L, "Monthly rent"))
        val rule = LayaCategoryCorrectionRule(" Monthly rent! ", 91L)
        val corrected = LayaCandidatePlanner.plan(
            tokenizer,
            "Monthly rent",
            categories,
            correctionRules = listOf(rule),
        )
        val ruleDecision = LayaCandidatePlanner.decide(corrected) {
            error("explicit correction must not call inference")
        } as LayaCandidatePlanner.Decision.Category
        assertEquals(null, ruleDecision.scores)
        val ruleResolved = LayaCandidatePlanner.resolveCurrent(ruleDecision, categories)
            as LayaCandidatePlanner.CurrentResolution.ActiveCategory
        assertEquals(null, ruleResolved.scores)
        assertEquals(null, ruleResolved.distribution)

        val metadata = LayaCandidatePlanner.plan(tokenizer, "Monthly rent", categories)
            as LayaCandidatePlanner.Plan.MetadataMatch
        val metadataDecision = LayaCandidatePlanner.decide(metadata) {
            error("exact metadata match must not call inference")
        } as LayaCandidatePlanner.Decision.Category
        assertEquals(null, metadataDecision.scores)
    }
}
