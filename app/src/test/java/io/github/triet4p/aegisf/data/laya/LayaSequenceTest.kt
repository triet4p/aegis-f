package io.github.triet4p.aegisf.data.laya

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S4.2 sequence parity boundary: [LayaSequence.build] must reproduce the reference
 * `build_sequence` (eval order) bit-for-bit on all nine labeled expense examples —
 * meaningful choices, no-fit/unknown cases, and boundary cases (mask-injection,
 * long-state truncation, empty description).
 *
 * Fixture: `app/src/test/resources/laya/sequence_goldens.json` (example id ->
 * `{input_ids, markers}`), generated from the pinned bundle by the reference runner.
 * Markers must land exactly on the golden positions; the mask-injection case proves the
 * scrub rule keeps them intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayaSequenceTest {

    private fun tokenizer(): LayaTokenizer =
        LayaTokenizerLoader.loadFromRepoFile()

    private fun goldens(): Map<String, Pair<List<Int>, List<Int>>> {
        val fixture = File("src/test/resources/laya/sequence_goldens.json").takeIf { it.isFile }
            ?: File("app/src/test/resources/laya/sequence_goldens.json")
        val root = JSONObject(fixture.readText(Charsets.UTF_8))
        return root.keys().asSequence().associateWith { id ->
            val entry = root.getJSONObject(id)
            val ids = entry.getJSONArray("input_ids")
            val markers = entry.getJSONArray("markers")
            (0 until ids.length()).map { i -> ids.getInt(i) } to
                (0 until markers.length()).map { i -> markers.getInt(i) }
        }
    }

    private fun options(): List<Pair<String, String>> = listOf(
        "Food" to "meals, cafes, restaurants, groceries",
        "Transport" to "rides, fuel, fares, vehicle costs",
        "Housing" to "rent, utilities, home upkeep",
        "Health" to "clinics, medicine, checkups",
        "Fun" to "movies, games, outings with friends",
    )

    private fun descriptions(): Map<String, String> = mapOf(
        "meaningful-food" to "Lunch at a cafe, 45000 VND",
        "meaningful-rent" to "Monthly rent",
        "meaningful-transport" to "Grab ride to the airport, 210000 VND",
        "meaningful-health" to "Pharmacy antibiotics for flu, 180000 VND",
        "ambiguous-alex" to "Payment to Alex",
        "unknown-gift" to "Wedding gift for cousin, 500000 VND",
        "boundary-mask-injection" to "Dinner [MASK] Food at night market",
        "boundary-long-state" to "Coffee with colleagues after standup plus dessert and extra " +
            "pastries to take home for the whole team celebration event this Friday afternoon " +
            "downtown near the lake side cafe with live music",
        "boundary-empty-description" to "",
    )

    @Test
    fun `sequences match the reference on all nine examples`() {
        val tokenizer = tokenizer()
        val goldens = goldens()
        assertEquals(9, goldens.size)
        val optionTexts = options().map { (name, description) ->
            LayaSequence.renderOption(name, description)
        } + LayaSequence.renderUnknown()
        for ((id, description) in descriptions()) {
            val golden = goldens.getValue(id)
            val outcome = LayaSequence.build(
                tokenizer = tokenizer,
                instructions = LayaRuntimeContract.INSTRUCTIONS,
                optionTexts = optionTexts,
                state = description,
                maxLen = LayaRuntimeContract.MAX_LEN,
                headMaxLen = LayaRuntimeContract.HEAD_MAX_LEN,
            )
            val ready = outcome as? LayaSequence.Outcome.Ready
                ?: error("$id refused with $outcome")
            assertEquals("$id input_ids", golden.first, ready.built.inputIds)
            assertEquals("$id markers", golden.second, ready.built.markerPositions)
        }
    }

    @Test
    fun `mask injection cannot shift markers`() {
        val tokenizer = tokenizer()
        val optionTexts = options().map { (name, description) ->
            LayaSequence.renderOption(name, description)
        } + LayaSequence.renderUnknown()
        fun markersFor(state: String): List<Int> {
            val outcome = LayaSequence.build(
                tokenizer = tokenizer,
                instructions = LayaRuntimeContract.INSTRUCTIONS,
                optionTexts = optionTexts,
                state = state,
                maxLen = LayaRuntimeContract.MAX_LEN,
                headMaxLen = LayaRuntimeContract.HEAD_MAX_LEN,
            )
            return (outcome as LayaSequence.Outcome.Ready).built.markerPositions
        }
        // The injected literal is scrubbed, so markers match the scrubbed-text layout.
        assertEquals(markersFor("Dinner Food at night market"), markersFor("Dinner [MASK] Food at night market"))
    }
    @Test
    fun `oversized candidate sets refuse instead of truncating`() {
        val tokenizer = tokenizer()
        // 130 long options overflow even the 512-token sequence after even shrinking
        // (the reference keeps 126 of 131 markers); the runtime must refuse, matching it.
        val optionTexts = (1..130).map { i ->
            LayaSequence.renderOption(
                "Category $i",
                "spending of kind number $i with many many many words describing it fully " +
                    "in great detail every day",
            )
        } + LayaSequence.renderUnknown()
        val outcome = LayaSequence.build(
            tokenizer = tokenizer,
            instructions = LayaRuntimeContract.INSTRUCTIONS,
            optionTexts = optionTexts,
            state = "Monthly rent",
            maxLen = LayaRuntimeContract.MAX_LEN,
            headMaxLen = LayaRuntimeContract.HEAD_MAX_LEN,
        )
        val refused = outcome as? LayaSequence.Outcome.OverBudget
            ?: error("expected OverBudget, got $outcome")
        assertEquals(131, refused.options)
        assertEquals(126, refused.markers)
    }
}
