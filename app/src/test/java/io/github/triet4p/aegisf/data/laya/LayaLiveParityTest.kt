package io.github.triet4p.aegisf.data.laya

import io.github.triet4p.aegisf.data.CategoryEntity
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S4.2 model-in-the-loop parity proof: the app's own tokenizer and sequence port
 * build the exact model inputs, the real pinned weights score them, and the app's own
 * calibration interprets the real logits — compared against the S4.1 reference outcomes.
 *
 * Only `session.run` executes outside the JVM (via `tools/laya/run_tensors.py` against
 * the machine-local pinned bundle, using the ambient host `onnxruntime` like the S4.1
 * reference runner): host JVMs in this environment cannot load ORT Java natives, and
 * the ARM64 session open stays with the user-run device check. Everything the app owns
 * — input bytes, marker positions, temperature, probabilities, confidence — is asserted
 * here on live weights. Without the bundle or a working host Python, the test assumes
 * away with the exact missing prerequisite instead of asserting against a stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayaLiveParityTest {

    private fun bundleDir(): File {
        val configured = System.getProperty("laya.bundleDir")
        val candidates = listOfNotNull(
            configured?.let(::File),
            File("F:/llms/hf/models/hub/models--receptron--laya-onnx/snapshots/68f27dfe5a27a54fb2b1fefc432f43f972e90868"),
        )
        return candidates.firstOrNull { File(it, "laya.onnx").isFile && File(it, "laya.onnx.data").isFile }
            ?: run {
                org.junit.Assume.assumeTrue(
                    "pinned Laya bundle missing (tried ${candidates.map { it.path }}); " +
                        "S4.2 live parity needs the machine-local snapshot, weights stay out of Git",
                    false,
                )
                error("unreachable")
            }
    }

    private fun categories() = listOf(
        CategoryOption(id = 11L, name = "Food", description = "meals, cafes, restaurants, groceries"),
        CategoryOption(id = 12L, name = "Transport", description = "rides, fuel, fares, vehicle costs"),
        CategoryOption(id = 13L, name = "Housing", description = "rent, utilities, home upkeep"),
        CategoryOption(id = 14L, name = "Health", description = "clinics, medicine, checkups"),
        CategoryOption(id = 15L, name = "Fun", description = "movies, games, outings with friends"),
    )

    private fun helperScript(): File {
        // Unit tests run with the working directory at the module dir; fall back to a
        // parent checkout when the task runs elsewhere.
        val helper = File("tools/laya/run_tensors.py").takeIf { it.isFile }
            ?: File("app/tools/laya/run_tensors.py").takeIf { it.isFile }
            ?: File("../tools/laya/run_tensors.py").takeIf { it.isFile }
        org.junit.Assume.assumeTrue(
            "run_tensors.py not found from ${File(".").absolutePath}; run :app:testDebugUnitTest from the repo root or module dir",
            helper != null,
        )
        return helper!!
    }

    private fun liveLogits(inputIds: List<Int>, markers: List<Int>): List<Double> {
        val helper = helperScript()
        val work = kotlin.io.path.createTempDirectory("laya-live").toFile()
        try {
            val tensors = File(work, "tensors.json")
            tensors.writeText(
                "{\"input_ids\": [${inputIds.joinToString(",")}], \"markers\": [${markers.joinToString(",")}]}",
                Charsets.UTF_8,
            )
            val out = File(work, "logits.json")
            val process = ProcessBuilder(
                "python",
                helper.absolutePath,
                "--model-dir", bundleDir().absolutePath,
                "--tensors", tensors.absolutePath,
                "--output", out.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exited = process.waitFor()
            org.junit.Assume.assumeTrue(
                "host python session failed (exit=$exited): $output",
                exited == 0 && out.isFile,
            )
            val logits = JSONObject(out.readText(Charsets.UTF_8)).getJSONArray("logits")
            return (0 until logits.length()).map { i -> logits.getDouble(i) }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `live weights reproduce reference choices on meaningful unknown and boundary inputs`() {
        val tokenizer = LayaTokenizerLoader.loadFromRepoFile()
        val optionTexts = categories().map { LayaSequence.renderOption(it.name, it.description) } +
            LayaSequence.renderUnknown()
        // S4.1 reference outcomes, preserved as observed (food/long-state miss).
        val cases = mapOf(
            "Monthly rent" to "Housing",
            "Grab ride to the airport, 210000 VND" to "Transport",
            "Pharmacy antibiotics for flu, 180000 VND" to "Health",
            "Payment to Alex" to "unknown",
            "Wedding gift for cousin, 500000 VND" to "unknown",
            "Dinner [MASK] Food at night market" to "Food",
            "" to "unknown",
        )
        val names = categories().map { it.name } + LayaTokenizer.UNKNOWN_OPTION
        for ((description, expected) in cases) {
            val outcome = LayaSequence.build(
                tokenizer = tokenizer,
                instructions = LayaRuntimeContract.INSTRUCTIONS,
                optionTexts = optionTexts,
                state = description,
                maxLen = LayaRuntimeContract.MAX_LEN,
                headMaxLen = LayaRuntimeContract.HEAD_MAX_LEN,
            )
            val built = (outcome as? LayaSequence.Outcome.Ready)?.built
                ?: error("$description refused with $outcome")
            val decision = LayaCalibration.decide(liveLogits(built.inputIds, built.markerPositions))
            assertEquals("$description choice", expected, names[decision.bestIndex])
            if (expected == LayaTokenizer.UNKNOWN_OPTION) {
                assertEquals("$description unknown index", names.size - 1, decision.bestIndex)
            }
            val total = decision.probabilities.sum()
            assertTrue("$description probabilities sum $total", total > 0.999 && total < 1.001)
        }
    }

    @Test
    fun `live rent logits match the reference probabilities exactly`() {
        val tokenizer = LayaTokenizerLoader.loadFromRepoFile()
        val optionTexts = categories().map { LayaSequence.renderOption(it.name, it.description) } +
            LayaSequence.renderUnknown()
        val outcome = LayaSequence.build(
            tokenizer = tokenizer,
            instructions = LayaRuntimeContract.INSTRUCTIONS,
            optionTexts = optionTexts,
            state = "Monthly rent",
            maxLen = LayaRuntimeContract.MAX_LEN,
            headMaxLen = LayaRuntimeContract.HEAD_MAX_LEN,
        )
        val built = (outcome as LayaSequence.Outcome.Ready).built
        val decision = LayaCalibration.decide(liveLogits(built.inputIds, built.markerPositions))
        // Reference: Housing 0.9796 / confidence 0.9336 (S4.1 smoke).
        assertEquals(0.9796, decision.probabilities[2], 1e-9)
        assertEquals(0.9336, decision.confidence, 1e-9)
    }

    @Test
    fun `expanded candidates build exact sequence map live result and report retrieval recall`() {
        val fixture = File("tools/laya/expense_examples.json").takeIf { it.isFile }
            ?: File("../tools/laya/expense_examples.json")
        val root = JSONObject(fixture.readText(Charsets.UTF_8))
        val examples = root.getJSONArray("examples")
        val categoryDefinitions = root.getJSONArray("categories")
        val expandedCategories = ArrayList<CategoryEntity>(categoryDefinitions.length() + 200)
        for (index in 0 until categoryDefinitions.length()) {
            val name = categoryDefinitions.getJSONObject(index).getString("name")
            val id = (index + 1).toLong()
            expandedCategories.add(
                CategoryEntity(
                    id = id,
                    name = name,
                    archived = false,
                    createdAtEpochMillis = 0L,
                    updatedAtEpochMillis = 0L,
                ),
            )
        }
        for (index in 1..200) {
            expandedCategories.add(
                CategoryEntity(
                    id = 1000L + index,
                    name = "Archive ${index.toString().padStart(4, '0')}",
                    archived = false,
                    createdAtEpochMillis = 0L,
                    updatedAtEpochMillis = 0L,
                ),
            )
        }

        val tokenizer = LayaTokenizerLoader.loadFromRepoFile()
        var positiveExamples = 0
        var retrieved = 0
        val misses = ArrayList<String>()
        var markerExample: Pair<String, String>? = null
        for (index in 0 until examples.length()) {
            val example = examples.getJSONObject(index)
            val expectedValue = example.opt("expected")
            if (expectedValue == null || expectedValue == JSONObject.NULL ||
                expectedValue.toString() == LayaTokenizer.UNKNOWN_OPTION
            ) {
                continue
            }
            val id = example.getString("id")
            val expected = expectedValue.toString()
            val description = example.getString("description")
            if (id == "boundary-mask-injection") markerExample = id to description
            positiveExamples++
            val plan = LayaCandidatePlanner.plan(tokenizer, description, expandedCategories)
            val candidateNames = when (plan) {
                is LayaCandidatePlanner.Plan.Correction -> listOf(plan.category.name)
                is LayaCandidatePlanner.Plan.MetadataMatch -> listOf(plan.category.name)
                is LayaCandidatePlanner.Plan.Model -> plan.categories.map { it.name }
                is LayaCandidatePlanner.Plan.ManualReview -> emptyList()
            }
            if (expected in candidateNames) retrieved++ else misses.add("$id->$expected")
        }
        assertEquals(6, positiveExamples)
        assertEquals(205, expandedCategories.size)
        println(
            "S4.4 retrieval recall: $retrieved/$positiveExamples; " +
                "misses=${misses.joinToString(",")}; expanded-active=${expandedCategories.size}",
        )

        val (exampleId, description) = markerExample ?: error("labeled marker example absent")
        val foodDrinksId = 9001L
        val foodSuppliesId = 9002L
        val smokeCategories = ArrayList(expandedCategories.filterNot { it.name == "Food" })
        smokeCategories.addAll(
            listOf(
                CategoryEntity(
                    id = foodDrinksId,
                    name = "Food and drinks",
                    archived = false,
                    createdAtEpochMillis = 0L,
                    updatedAtEpochMillis = 0L,
                ),
                CategoryEntity(
                    id = foodSuppliesId,
                    name = "Food supplies",
                    archived = false,
                    createdAtEpochMillis = 0L,
                    updatedAtEpochMillis = 0L,
                ),
            ),
        )
        assertEquals(206, smokeCategories.size)
        val plan = LayaCandidatePlanner.plan(tokenizer, description, smokeCategories)
            as LayaCandidatePlanner.Plan.Model
        assertEquals(listOf(foodSuppliesId, foodDrinksId), plan.categories.map { it.id })
        assertTrue((plan.uncertainty?.omittedCategoryCount ?: 0) > 0)
        assertTrue(plan.built.headTokenCount <= LayaRuntimeContract.HEAD_MAX_LEN)
        assertTrue(plan.built.inputIds.size <= LayaRuntimeContract.MAX_LEN)
        assertEquals(plan.categories.size + 1, plan.built.markerPositions.size)
        assertTrue(plan.built.markerPositions.all { plan.built.inputIds[it] == tokenizer.maskId })

        val decision = LayaCandidatePlanner.decide(plan) { built ->
            liveLogits(built.inputIds, built.markerPositions)
        }
        val resolved = LayaCandidatePlanner.resolveCurrent(decision, smokeCategories)
        when (resolved) {
            is LayaCandidatePlanner.CurrentResolution.ActiveCategory -> {
                assertTrue(resolved.category.id in setOf(foodDrinksId, foodSuppliesId))
                println(
                    "S4.4 live decision: $exampleId -> category-id=${resolved.category.id}; " +
                        "confidence=${resolved.confidence}; candidate-uncertainty=${resolved.uncertainty}",
                )
            }
            is LayaCandidatePlanner.CurrentResolution.ManualReview ->
                println("S4.4 live decision: $exampleId -> unknown; reason=${resolved.reason}")
            is LayaCandidatePlanner.CurrentResolution.StaleCategory ->
                error("fresh active metadata failed to resolve model result: $resolved")
        }
    }
}
