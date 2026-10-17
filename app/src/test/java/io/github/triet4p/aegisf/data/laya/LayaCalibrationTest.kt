package io.github.triet4p.aegisf.data.laya

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The S4.2 output-interpretation boundary: temperature buckets, softmax, and the Jev
 * confidence must reproduce the reference decisions on the real golden logits from the
 * pinned bundle — including the two honest misses (food/long-state -> unknown), which are
 * preserved as observed rather than tuned away. Raw logits are never probabilities.
 *
 * Fixture: `app/src/test/resources/laya/calibration_goldens.json` (example id ->
 * `{logits, temperature, probabilities, confidence}`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayaCalibrationTest {

    private data class Golden(
        val logits: List<Double>,
        val temperature: Double,
        val probabilities: Map<String, Double>,
        val confidence: Double,
    )

    private val optionNames = listOf("Food", "Transport", "Housing", "Health", "Fun", "unknown")

    private fun goldens(): Map<String, Golden> {
        val fixture = File("src/test/resources/laya/calibration_goldens.json").takeIf { it.isFile }
            ?: File("app/src/test/resources/laya/calibration_goldens.json")
        val root = JSONObject(fixture.readText(Charsets.UTF_8))
        return root.keys().asSequence().associateWith { id ->
            val entry = root.getJSONObject(id)
            val logits = entry.getJSONArray("logits")
            val probabilities = entry.getJSONObject("probabilities")
            Golden(
                logits = (0 until logits.length()).map { i -> logits.getDouble(i) },
                temperature = entry.getDouble("temperature"),
                probabilities = probabilities.keys().asSequence().associateWith {
                    probabilities.getDouble(it)
                },
                confidence = entry.getDouble("confidence"),
            )
        }
    }

    @Test
    fun `temperatures follow the pinned per-cardinality buckets`() {
        assertEquals(1.9063563346862793, LayaCalibration.temperatureFor(2), 0.0)
        assertEquals(1.7601518630981445, LayaCalibration.temperatureFor(5), 0.0)
        assertEquals(1.0000158548355103, LayaCalibration.temperatureFor(6), 0.0)
        assertEquals(1.0000158548355103, LayaCalibration.temperatureFor(10), 0.0)
        assertEquals(0.10058280825614929, LayaCalibration.temperatureFor(11), 0.0)
        assertEquals(0.10058280825614929, LayaCalibration.temperatureFor(60), 0.0)
    }

    @Test
    fun `decisions match the reference on all nine golden logit rows`() {
        val goldens = goldens()
        assertEquals(9, goldens.size)
        for ((id, golden) in goldens) {
            assertEquals("$id temperature", golden.temperature, LayaCalibration.temperatureFor(6), 0.0)
            val decision = LayaCalibration.decide(golden.logits)
            val names = optionNames
            for ((index, name) in names.withIndex()) {
                assertEquals(
                    "$id probability $name",
                    golden.probabilities.getValue(name),
                    decision.probabilities[index],
                    1e-9,
                )
            }
            assertEquals("$id confidence", golden.confidence, decision.confidence, 1e-9)
            val expectedBest = names.indexOfFirst { it == expectedChoice(golden.probabilities) }
            assertEquals("$id best", expectedBest, decision.bestIndex)
        }
    }

    @Test
    fun `honest misses stay unknown instead of being tuned away`() {
        val goldens = goldens()
        // S4.1 observed: the lunch phrase and the long state both fall into `unknown`.
        for (id in listOf("meaningful-food", "boundary-long-state")) {
            val decision = LayaCalibration.decide(goldens.getValue(id).logits)
            assertEquals(id, optionNames.size - 1, decision.bestIndex)
        }
        // And the canonical rent phrase stays Housing.
        val rent = LayaCalibration.decide(goldens.getValue("meaningful-rent").logits)
        assertEquals("meaningful-rent", optionNames.indexOf("Housing"), rent.bestIndex)
    }

    private fun expectedChoice(probabilities: Map<String, Double>): String =
        optionNames.maxBy { probabilities.getValue(it) }
}
