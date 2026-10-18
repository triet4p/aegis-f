package io.github.triet4p.aegisf.data.laya

import io.github.triet4p.aegisf.data.CategoryEntity
import java.text.Normalizer
import java.util.Locale
import java.util.TreeSet
import java.util.regex.Pattern

/** An explicitly supplied, exact-description correction; storage and remembering belong to S4.7. */
data class LayaCategoryCorrectionRule(
    val exactDescription: String,
    val categoryId: Long,
)

/** Builds a budget-exact shortlist from current category metadata before model inference. */
object LayaCandidatePlanner {

    data class RetrievalUncertainty(
        val activeCategoryCount: Int,
        val offeredCategoryCount: Int,
        val omittedCategoryCount: Int,
        val omittedMetadataEvidenceCount: Int,
    )

    enum class ManualReason {
        EMPTY_DESCRIPTION,
        NO_ACTIVE_CATEGORIES,
        INVALID_CATEGORY_METADATA,
        CORRECTION_TARGET_UNAVAILABLE,
        AMBIGUOUS_CORRECTIONS,
        NO_METADATA_EVIDENCE,
        NO_FIT_CANDIDATES,
        SEQUENCE_UNFIT,
        MODEL_NO_FIT,
    }

    enum class SuggestionSource { EXPLICIT_CORRECTION, CATEGORY_METADATA, MODEL }

    sealed interface Plan {
        data class Correction(val category: CategoryEntity) : Plan
        data class MetadataMatch(val category: CategoryEntity) : Plan
        data class Model(
            val categories: List<CategoryEntity>,
            val built: LayaSequence.Built,
            val uncertainty: RetrievalUncertainty?,
        ) : Plan
        data class ManualReview(
            val reason: ManualReason,
            val uncertainty: RetrievalUncertainty? = null,
        ) : Plan
    }

    /**
     * One evaluated category's calibrated share of the model distribution. Omitted
     * categories never appear here: no score is fabricated for unevaluated options.
     */
    data class ScoredCategory(
        val categoryId: Long,
        val snapshotName: String,
        /** Calibrated softmax probability over exactly the evaluated options plus unknown. */
        val probability: Double,
    )

    /**
     * The full model-returned distribution for one request. [ranked] covers only the
     * evaluated categories in descending probability order (ties keep plan order, so the
     * first row is always the decision top); [unknownProbability] is the calibrated no-fit
     * share from the same softmax. [confidence] is the Jev sharpness over that same
     * vector — a peakedness score, never a per-category accuracy claim. Subset scope
     * travels in [RetrievalUncertainty] alongside, never relabeled as full-catalog.
     */
    data class CategoryScores(
        val ranked: List<ScoredCategory>,
        val unknownProbability: Double,
        val confidence: Double,
    )

    sealed interface Decision {
        data class Category(
            val categoryId: Long,
            val snapshotName: String,
            val source: SuggestionSource,
            val confidence: Double?,
            val uncertainty: RetrievalUncertainty? = null,
            /** Model path only; explicit rules and category-name matches carry no scores. */
            val scores: CategoryScores? = null,
        ) : Decision

        data class Unknown(
            val reason: ManualReason,
            val uncertainty: RetrievalUncertainty? = null,
            /** Set only when a model plan scored unknown highest; the rows stay selectable. */
            val scores: CategoryScores? = null,
        ) : Decision
    }

    /**
     * One evaluated row re-checked against fresh metadata at display time. A null
     * [currentCategory] means the stable id no longer resolves (archived, renamed,
     * missing, or duplicated): the model probability is still shown truthfully, but the
     * row is not selectable. Permanent deletion (S4.11) reuses this same stable-id
     * invalidation without a new mechanism.
     */
    data class ResolvedOption(
        val categoryId: Long,
        val snapshotName: String,
        val probability: Double,
        val currentCategory: CategoryEntity?,
    )

    /**
     * The display-ready distribution: evaluated rows in rank order plus the calibrated
     * unknown share from the same softmax. [confidence] is the Jev sharpness over that
     * same vector — a peakedness score, never a per-category accuracy claim.
     */
    data class ScoredDistribution(
        val options: List<ResolvedOption>,
        val unknownProbability: Double,
        val confidence: Double,
    )

    sealed interface CurrentResolution {
        data class ActiveCategory(
            val category: CategoryEntity,
            val source: SuggestionSource,
            val confidence: Double?,
            val uncertainty: RetrievalUncertainty?,
            val scores: CategoryScores? = null,
            val distribution: ScoredDistribution? = null,
        ) : CurrentResolution

        data class ManualReview(
            val reason: ManualReason,
            val uncertainty: RetrievalUncertainty?,
            val scores: CategoryScores? = null,
            val distribution: ScoredDistribution? = null,
        ) : CurrentResolution

        data class StaleCategory(
            val categoryId: Long,
            val reason: StaleReason,
        ) : CurrentResolution
    }

    enum class StaleReason { MISSING_OR_ARCHIVED, RENAMED, DUPLICATE_ID }

    private data class RankedCategory(
        val category: CategoryEntity,
        val quality: Int,
        val matchedWords: Int,
        val nameWordCount: Int,
        val optionTokens: Int,
    )

    private val wordPattern = Pattern.compile("[\\p{L}\\p{N}]+")

    private val bestFirst = Comparator<RankedCategory> { left, right ->
        var comparison = right.quality.compareTo(left.quality)
        if (comparison == 0) {
            comparison = (right.matchedWords.toLong() * left.nameWordCount)
                .compareTo(left.matchedWords.toLong() * right.nameWordCount)
        }
        if (comparison == 0) comparison = right.matchedWords.compareTo(left.matchedWords)
        if (comparison == 0) comparison = left.category.id.compareTo(right.category.id)
        comparison
    }

    fun plan(
        tokenizer: LayaTokenizer,
        description: String,
        categories: List<CategoryEntity>,
        correctionRules: List<LayaCategoryCorrectionRule> = emptyList(),
        maxLen: Int = LayaRuntimeContract.MAX_LEN,
        headMaxLen: Int = LayaRuntimeContract.HEAD_MAX_LEN,
    ): Plan {
        val active = categories.filterNot { it.archived }
        val ids = HashSet<Long>(active.size)
        if (active.any { it.id <= 0L || it.name.trim().isEmpty() || !ids.add(it.id) }) {
            return Plan.ManualReview(ManualReason.INVALID_CATEGORY_METADATA)
        }

        val descriptionWords = words(description)
        val normalizedDescription = descriptionWords.joinToString(" ")
        if (normalizedDescription.isEmpty()) {
            return Plan.ManualReview(ManualReason.EMPTY_DESCRIPTION)
        }
        if (active.isEmpty()) {
            return Plan.ManualReview(ManualReason.NO_ACTIVE_CATEGORIES)
        }

        var matchingRuleId: Long? = null
        var conflictingRules = false
        for (rule in correctionRules) {
            val trigger = normalized(rule.exactDescription)
            if (trigger.isEmpty() || trigger != normalizedDescription) continue
            if (matchingRuleId == null) matchingRuleId = rule.categoryId
            else if (matchingRuleId != rule.categoryId) conflictingRules = true
        }
        if (matchingRuleId != null) {
            if (conflictingRules) return Plan.ManualReview(ManualReason.AMBIGUOUS_CORRECTIONS)
            val target = active.firstOrNull { it.id == matchingRuleId }
                ?: return Plan.ManualReview(ManualReason.CORRECTION_TARGET_UNAVAILABLE)
            return Plan.Correction(target)
        }

        var metadataMatch: CategoryEntity? = null
        var metadataMatchCount = 0
        for (category in active) {
            val nameWords = words(category.name)
            if (nameWords.isNotEmpty() && containsPhrase(descriptionWords, nameWords)) {
                metadataMatch = category
                metadataMatchCount++
            }
        }
        if (metadataMatchCount == 1) return Plan.MetadataMatch(checkNotNull(metadataMatch))


        val optionTokenCounts = IntArray(active.size + 1)
        for (index in active.indices) {
            optionTokenCounts[index] = LayaSequence.measureOptionTokens(
                tokenizer,
                renderCategoryOption(active[index]),
            )
        }
        val unknownText = LayaSequence.renderUnknown()
        val unknownTokenCount = LayaSequence.measureOptionTokens(tokenizer, unknownText)
        optionTokenCounts[active.size] = unknownTokenCount
        val questionTokens = LayaSequence.measureQuestionTokens(
            tokenizer,
            LayaRuntimeContract.INSTRUCTIONS,
        )

        if (LayaSequence.measureHeadTokens(questionTokens, optionTokenCounts, headMaxLen) <= headMaxLen) {
            val optionTexts = ArrayList<String>(active.size + 1)
            for (category in active) optionTexts.add(renderCategoryOption(category))
            optionTexts.add(unknownText)
            val built = buildSequence(
                tokenizer,
                description,
                optionTexts,
                maxLen,
                headMaxLen,
            ) ?: return Plan.ManualReview(ManualReason.SEQUENCE_UNFIT)
            return Plan.Model(active, built, uncertainty = null)
        }

        val descriptionTokens = descriptionWords.toSet()
        val maximumShortlistSize = maxOf(
            0,
            headMaxLen - LayaSequence.HEAD_MIN_KEPT - unknownTokenCount,
        )
        val ranked = TreeSet(bestFirst)
        var metadataEvidenceCount = 0
        for (index in active.indices) {
            val category = active[index]
            val nameWords = words(category.name)
            val matchedWords = nameWords.count { it in descriptionTokens }
            if (matchedWords == 0 || nameWords.isEmpty()) continue
            metadataEvidenceCount++
            val quality = when {
                containsPhrase(descriptionWords, nameWords) -> 2
                matchedWords == nameWords.size -> 1
                else -> 0
            }
            ranked.add(
                RankedCategory(
                    category = category,
                    quality = quality,
                    matchedWords = matchedWords,
                    nameWordCount = nameWords.size,
                    optionTokens = optionTokenCounts[index],
                ),
            )
            if (ranked.size > maximumShortlistSize) ranked.pollLast()
        }

        if (metadataEvidenceCount == 0) {
            return Plan.ManualReview(
                reason = ManualReason.NO_METADATA_EVIDENCE,
                uncertainty = RetrievalUncertainty(
                    activeCategoryCount = active.size,
                    offeredCategoryCount = 0,
                    omittedCategoryCount = active.size,
                    omittedMetadataEvidenceCount = 0,
                ),
            )
        }

        val selected = ArrayList<RankedCategory>()
        val selectedTokenCounts = IntArray(ranked.size + 1)
        for (candidate in ranked) {
            selectedTokenCounts[selected.size] = candidate.optionTokens
            selectedTokenCounts[selected.size + 1] = unknownTokenCount
            val measured = LayaSequence.measureHeadTokens(
                questionTokens = questionTokens,
                optionTokenCounts = selectedTokenCounts,
                headMaxLen = headMaxLen,
                optionCount = selected.size + 2,
            )
            if (measured <= headMaxLen) selected.add(candidate)
        }

        val uncertainty = RetrievalUncertainty(
            activeCategoryCount = active.size,
            offeredCategoryCount = selected.size,
            omittedCategoryCount = active.size - selected.size,
            omittedMetadataEvidenceCount = metadataEvidenceCount - selected.size,
        )
        if (selected.isEmpty()) {
            return Plan.ManualReview(ManualReason.NO_FIT_CANDIDATES, uncertainty)
        }

        val shortlist = ArrayList<CategoryEntity>(selected.size)
        val optionTexts = ArrayList<String>(selected.size + 1)
        for (candidate in selected) {
            shortlist.add(candidate.category)
            optionTexts.add(renderCategoryOption(candidate.category))
        }
        optionTexts.add(unknownText)
        val built = buildSequence(
            tokenizer,
            description,
            optionTexts,
            maxLen,
            headMaxLen,
        ) ?: return Plan.ManualReview(ManualReason.SEQUENCE_UNFIT, uncertainty)
        return Plan.Model(
            categories = shortlist,
            built = built,
            uncertainty = uncertainty.takeIf { it.omittedCategoryCount > 0 },
        )
    }

    fun directDecision(plan: Plan): Decision = when (plan) {
        is Plan.Correction -> Decision.Category(
            categoryId = plan.category.id,
            snapshotName = plan.category.name,
            source = SuggestionSource.EXPLICIT_CORRECTION,
            confidence = null,
        )
        is Plan.MetadataMatch -> Decision.Category(
            categoryId = plan.category.id,
            snapshotName = plan.category.name,
            source = SuggestionSource.CATEGORY_METADATA,
            confidence = null,
        )
        is Plan.ManualReview -> Decision.Unknown(plan.reason, plan.uncertainty)
        is Plan.Model -> error("A model plan needs an inference result")
    }

    /** Calibrates model plans and maps the full positional distribution; direct plans never invoke inference. */
    fun decide(
        plan: Plan,
        logitsFor: (LayaSequence.Built) -> List<Double>,
    ): Decision = when (plan) {
        is Plan.Correction, is Plan.MetadataMatch, is Plan.ManualReview -> directDecision(plan)
        is Plan.Model -> {
            val calibrated = LayaCalibration.decide(logitsFor(plan.built))
            modelDecision(
                plan = plan,
                probabilities = calibrated.probabilities,
                bestIndex = calibrated.bestIndex,
                confidence = calibrated.confidence,
            )
        }
    }

    /**
     * Backwards-compatible positional entry kept for callers that only assert the top
     * category (older tests). It carries no distribution: production inference must use
     * the full-probability overload so every evaluated row is preserved.
     */
    fun modelDecision(plan: Plan.Model, bestIndex: Int, confidence: Double): Decision {
        require(confidence in 0.0..1.0) { "confidence must be calibrated in [0, 1]" }
        require(bestIndex in 0..plan.categories.size) { "best index $bestIndex is out of range" }
        if (bestIndex == plan.categories.size) {
            return Decision.Unknown(ManualReason.MODEL_NO_FIT, plan.uncertainty)
        }
        val category = plan.categories[bestIndex]
        return Decision.Category(
            categoryId = category.id,
            snapshotName = category.name,
            source = SuggestionSource.MODEL,
            confidence = confidence,
            uncertainty = plan.uncertainty,
        )
    }

    /**
     * Maps one calibrated softmax over exactly the evaluated options plus the final
     * unknown slot. The ranked rows preserve the model-returned per-category shares;
     * subset scope stays in [RetrievalUncertainty] and is never presented as a
     * full-catalog distribution.
     */
    fun modelDecision(
        plan: Plan.Model,
        probabilities: List<Double>,
        bestIndex: Int,
        confidence: Double,
    ): Decision {
        require(confidence in 0.0..1.0) { "confidence must be calibrated in [0, 1]" }
        require(probabilities.size == plan.categories.size + 1) {
            "probabilities must cover every evaluated category plus unknown"
        }
        require(bestIndex in 0..plan.categories.size) { "best index $bestIndex is out of range" }
        for (probability in probabilities) {
            require(probability in 0.0..1.0) { "probability $probability is not calibrated in [0, 1]" }
        }
        val ranked = plan.categories.mapIndexed { index, category ->
            index to ScoredCategory(
                categoryId = category.id,
                snapshotName = category.name,
                probability = probabilities[index],
            )
        }.sortedWith(
            compareByDescending<Pair<Int, ScoredCategory>> { it.second.probability }
                .thenBy { it.first },
        ).map { it.second }
        val scores = CategoryScores(
            ranked = ranked,
            unknownProbability = probabilities[plan.categories.size],
            confidence = confidence,
        )
        if (bestIndex == plan.categories.size) {
            return Decision.Unknown(ManualReason.MODEL_NO_FIT, plan.uncertainty, scores)
        }
        val category = plan.categories[bestIndex]
        return Decision.Category(
            categoryId = category.id,
            snapshotName = category.name,
            source = SuggestionSource.MODEL,
            confidence = confidence,
            uncertainty = plan.uncertainty,
            scores = scores,
        )
    }

    /**
     * Resolves by stable id against fresh metadata; renamed, archived, missing, or
     * duplicate ids are stale. Model scores always resolve to the display distribution:
     * valid rows stay selectable, invalid rows stay visible with no selectable category,
     * and permanent deletion (S4.11) reuses this same invalidation path.
     */
    fun resolveCurrent(decision: Decision, currentCategories: List<CategoryEntity>): CurrentResolution =
        when (decision) {
            is Decision.Unknown -> CurrentResolution.ManualReview(
                decision.reason,
                decision.uncertainty,
                decision.scores,
                distribution = resolveDistribution(decision.scores, currentCategories),
            )
            is Decision.Category -> {
                var current: CategoryEntity? = null
                var matchCount = 0
                for (category in currentCategories) {
                    if (category.id == decision.categoryId) {
                        current = category
                        matchCount++
                    }
                }
                val resolved = current
                when {
                    matchCount > 1 -> CurrentResolution.StaleCategory(
                        decision.categoryId,
                        StaleReason.DUPLICATE_ID,
                    )
                    resolved == null -> CurrentResolution.StaleCategory(
                        decision.categoryId,
                        StaleReason.MISSING_OR_ARCHIVED,
                    )
                    resolved.archived -> CurrentResolution.StaleCategory(
                        decision.categoryId,
                        StaleReason.MISSING_OR_ARCHIVED,
                    )
                    resolved.name != decision.snapshotName -> CurrentResolution.StaleCategory(
                        decision.categoryId,
                        StaleReason.RENAMED,
                    )
                    else -> CurrentResolution.ActiveCategory(
                        category = resolved,
                        source = decision.source,
                        confidence = decision.confidence,
                        uncertainty = decision.uncertainty,
                        scores = decision.scores,
                        distribution = resolveDistribution(decision.scores, currentCategories),
                    )
                }
            }
        }

    private fun resolveDistribution(
        scores: CategoryScores?,
        currentCategories: List<CategoryEntity>,
    ): ScoredDistribution? {
        if (scores == null) return null
        val byId = HashMap<Long, MutableList<CategoryEntity>>(currentCategories.size * 2 + 1)
        for (category in currentCategories) {
            byId.getOrPut(category.id) { ArrayList(2) }.add(category)
        }
        val options = scores.ranked.map { scored ->
            val matches = byId[scored.categoryId].orEmpty()
            val current = matches.singleOrNull()?.takeUnless { it.archived }
                ?.takeIf { it.name == scored.snapshotName }
            ResolvedOption(
                categoryId = scored.categoryId,
                snapshotName = scored.snapshotName,
                probability = scored.probability,
                currentCategory = current,
            )
        }
        return ScoredDistribution(
            options = options,
            unknownProbability = scores.unknownProbability,
            confidence = scores.confidence,
        )
    }

    private fun buildSequence(
        tokenizer: LayaTokenizer,
        description: String,
        optionTexts: List<String>,
        maxLen: Int,
        headMaxLen: Int,
    ): LayaSequence.Built? {
        val outcome = LayaSequence.build(
            tokenizer = tokenizer,
            instructions = LayaRuntimeContract.INSTRUCTIONS,
            optionTexts = optionTexts,
            state = description,
            maxLen = maxLen,
            headMaxLen = headMaxLen,
        ) as? LayaSequence.Outcome.Ready ?: return null
        return outcome.built.takeIf { it.headTokenCount <= headMaxLen }
    }

    private fun renderCategoryOption(category: CategoryEntity): String {
        val plain = LayaSequence.renderOption(category.name, "")
        val collidesWithUnknown = plain == LayaSequence.renderUnknown() ||
            normalized(category.name) == LayaTokenizer.UNKNOWN_OPTION
        val description = if (collidesWithUnknown) "user-defined category" else ""
        return LayaSequence.renderOption(category.name, description)
    }

    private fun containsPhrase(textWords: List<String>, phraseWords: List<String>): Boolean {
        if (phraseWords.size > textWords.size) return false
        return (0..textWords.size - phraseWords.size).any { start ->
            phraseWords.indices.all { offset -> textWords[start + offset] == phraseWords[offset] }
        }
    }


    private fun normalized(text: String): String = words(text).joinToString(" ")

    private fun words(text: String): List<String> {
        val scrubbed = LayaSequence.scrub(text)
        val normalized = Normalizer.normalize(scrubbed, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val matcher = wordPattern.matcher(normalized)
        val result = ArrayList<String>()
        while (matcher.find()) result.add(matcher.group())
        return result
    }
}
