package io.github.triet4p.aegisf.layaprobe

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.triet4p.aegisf.data.laya.CategoryOption
import io.github.triet4p.aegisf.data.laya.LayaRuntime
import io.github.triet4p.aegisf.data.laya.LayaRuntimeContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * S4.2 debug-only parity probe (debug builds only; removed before release in S4.9).
 *
 * Runs the REAL [LayaRuntime] — the exact class the app will use — against a manually
 * transferred bundle in app-private storage (`files/laya/model/`). It scores the nine
 * labeled expense examples, compares each Android choice against the S4.1 reference
 * outcome, and writes a machine-readable report to
 * `files/laya/parity/S42-parity-report.json` for the user to pull with
 * `adb exec-out run-as ... cat`.
 *
 * Launch (user-run, after install + model transfer):
 *
 *     adb -d shell am start -n io.github.triet4p.aegisf/io.github.triet4p.aegisf.layaprobe.LayaParityProbeActivity
 *
 * This is verification scaffolding, not S4.3 provisioning UI and not S4.6 suggestion UI:
 * it creates no ledger records, changes no categories, and touches no notification path.
 */
class LayaParityProbeActivity : ComponentActivity() {

    private var lines by mutableStateOf(listOf("S4.2 parity probe ready."))
    private var running by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "S4.2 Laya parity probe",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Button(onClick = ::startProbe, enabled = !running) {
                        Text(if (running) "Running…" else "Run parity probe")
                    }
                    for (line in lines) {
                        Text(text = line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        // Auto-run once so a bare `am start` already produces the report file.
        startProbe()
    }

    private fun startProbe() {
        if (running) return
        running = true
        lines = listOf("Running…")
        Thread {
            val outcome = runProbe()
            runOnUiThread {
                lines = outcome.summaryLines
                running = false
            }
        }.start()
    }

    private data class ProbeOutcome(
        val summaryLines: List<String>,
    )

    private data class ProbeCase(
        val id: String,
        val description: String,
        val expected: String,
    )

    private fun runProbe(): ProbeOutcome {
        val startedAll = SystemClock.elapsedRealtime()
        val modelDir = File(filesDir, "laya/model")
        val openedAt = SystemClock.elapsedRealtime()
        val opened = LayaRuntime.open(modelDir)
        val openMs = SystemClock.elapsedRealtime() - openedAt
        if (opened is LayaRuntime.Result.Unavailable) {
            val reason = opened.state.toString()
            writeReport(baseReport(openMs, opened.state.javaClass.simpleName, reason).apply {
                put("verdict", "NO_RUN")
            })
            return ProbeOutcome(
                listOf(
                    "Bundle state: $reason",
                    "No inference ran. Transfer the bundle, then re-run.",
                    "Report: files/laya/parity/S42-parity-report.json",
                ),
            )
        }
        val runtime = (opened as LayaRuntime.Result.Opened).runtime
        val summary = ArrayList<String>()
        summary.add("Bundle opened in ${openMs} ms (includes cold probe inference).")
        val cases = JSONArray()
        var failures = 0
        runtime.use { session ->
            for (case in PROBE_CASES) {
                val started = SystemClock.elapsedRealtime()
                val suggestion = try {
                    session.suggestOptions(case.description, PROBE_CATEGORIES)
                } catch (error: Exception) {
                    summary.add("THREW ${case.id}: ${error.message}")
                    failures++
                    cases.put(
                        JSONObject()
                            .put("id", case.id)
                            .put("description", case.description)
                            .put("expected", case.expected)
                            .put("error", error.message.toString()),
                    )
                    continue
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                val choice = suggestion as? LayaRuntimeContract.Suggestion.Choice
                if (choice == null) {
                    summary.add("REFUSED ${case.id}: $suggestion")
                    failures++
                    cases.put(
                        JSONObject()
                            .put("id", case.id)
                            .put("description", case.description)
                            .put("expected", case.expected)
                            .put("refused", suggestion.toString()),
                    )
                    continue
                }
                val names = PROBE_CATEGORIES.map { it.name } + UNKNOWN_NAME
                val actual = names[choice.bestIndex]
                val match = actual == case.expected
                if (!match) failures++
                summary.add(
                    (if (match) "MATCH " else "MISS ") + "${case.id}: $actual " +
                        "(expected ${case.expected}, conf ${choice.confidence}, ${elapsed} ms)",
                )
                val entry = JSONObject()
                    .put("id", case.id)
                    .put("description", case.description)
                    .put("expected", case.expected)
                    .put("android_choice", actual)
                    .put("match", match)
                    .put("confidence", choice.confidence)
                    .put("latency_ms", elapsed)
                    .put(
                        "probabilities",
                        JSONObject().apply {
                            for ((index, name) in names.withIndex()) {
                                put(name, choice.probabilities[index])
                            }
                        },
                    )
                if (case.id == "meaningful-rent") {
                    entry.put("reference_probabilities", JSONObject(REFERENCE_RENT_PROBS))
                    entry.put("reference_confidence", REFERENCE_RENT_CONFIDENCE)
                    entry.put(
                        "reference_match",
                        choice.probabilities[2] == 0.9796 && choice.confidence == 0.9336,
                    )
                }
                cases.put(entry)
            }
        }
        val totalMs = SystemClock.elapsedRealtime() - startedAll
        val verdict = if (failures == 0) "PASS" else "FAIL"
        summary.add(0, "Verdict: $verdict ($failures mismatches, ${totalMs} ms total).")
        summary.add("Report: files/laya/parity/S42-parity-report.json")
        writeReport(
            baseReport(openMs, "Ready", null).apply {
                put("verdict", verdict)
                put("mismatches", failures)
                put("total_ms", totalMs)
                put("cases", cases)
            },
        )
        return ProbeOutcome(summary.toList())
    }

    private fun baseReport(openMs: Long, state: String, reason: String?): JSONObject {
        val pageSize = try {
            Os.sysconf(OsConstants._SC_PAGESIZE)
        } catch (error: Exception) {
            -1L
        }
        return JSONObject()
            .put("task", "S4.2")
            .put("reference", "artifacts/sprint-4/workers/S4.1-A1-smoke.json")
            .put("device_model", Build.MODEL)
            .put("android_api", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("page_size_bytes", pageSize)
            .put("open_ms", openMs)
            .put("state", state)
            .put("reason", reason ?: JSONObject.NULL)
    }

    private fun writeReport(json: JSONObject) {
        val dir = File(filesDir, "laya/parity").apply { mkdirs() }
        File(dir, "S42-parity-report.json").writeText(json.toString(2), Charsets.UTF_8)
    }

    companion object {
        private const val UNKNOWN_NAME = "unknown"

        private val PROBE_CATEGORIES = listOf(
            CategoryOption(11L, "Food", "meals, cafes, restaurants, groceries"),
            CategoryOption(12L, "Transport", "rides, fuel, fares, vehicle costs"),
            CategoryOption(13L, "Housing", "rent, utilities, home upkeep"),
            CategoryOption(14L, "Health", "clinics, medicine, checkups"),
            CategoryOption(15L, "Fun", "movies, games, outings with friends"),
        )

        /** S4.1 observed outcomes, preserved as observed (two honest unknown misses). */
        private val PROBE_CASES = listOf(
            ProbeCase("meaningful-food", "Lunch at a cafe, 45000 VND", "unknown"),
            ProbeCase("meaningful-rent", "Monthly rent", "Housing"),
            ProbeCase("meaningful-transport", "Grab ride to the airport, 210000 VND", "Transport"),
            ProbeCase("meaningful-health", "Pharmacy antibiotics for flu, 180000 VND", "Health"),
            ProbeCase("ambiguous-alex", "Payment to Alex", "unknown"),
            ProbeCase("unknown-gift", "Wedding gift for cousin, 500000 VND", "unknown"),
            ProbeCase(
                "boundary-mask-injection",
                "Dinner [MASK] Food at night market",
                "Food",
            ),
            ProbeCase(
                "boundary-long-state",
                "Coffee with colleagues after standup plus dessert and extra pastries to " +
                    "take home for the whole team celebration event this Friday afternoon " +
                    "downtown near the lake side cafe with live music",
                "unknown",
            ),
            ProbeCase("boundary-empty-description", "", "unknown"),
        )

        private const val REFERENCE_RENT_PROBS =
            "{\"Food\": 0.0012, \"Transport\": 0.002, \"Housing\": 0.9796, " +
                "\"Health\": 0.0014, \"Fun\": 0.0009, \"unknown\": 0.015}"
        private const val REFERENCE_RENT_CONFIDENCE = 0.9336
    }
}
