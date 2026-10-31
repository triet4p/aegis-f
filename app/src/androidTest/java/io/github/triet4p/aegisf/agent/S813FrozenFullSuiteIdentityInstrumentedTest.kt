package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEST-ONLY device proof for the freeze-bound complete-suite launch contract.
 *
 * It runs on the real phone with the frozen fixture/scorer assets and the installed isolated APKs,
 * reads a synthetic non-secret credential over a real one-shot loopback socket, and makes no hosted
 * provider call. It proves, before any run or debug evidence path exists, that:
 * - the accepted fresh `attempt`/`<attempt>-freeze-N` identity with the frozen batch and candidate,
 *   fixture and scorer digests, 24-case order, installed app/test APK bytes and identity digest is
 *   accepted, and that the one-shot socket delivers exactly the expected credential;
 * - every partial, stale, mixed or legacy binding is rejected first, so the credential channel is
 *   still untouched and the pass-scoped run/debug roots are never created.
 */
@RunWith(AndroidJUnit4::class)
class S813FrozenFullSuiteIdentityInstrumentedTest {

    @Test
    fun completeSuiteIdentityLoadsOneShotSocketCredentialAndRejectsWrongBindings() {
        val args = s813InstrumentationArguments(InstrumentationRegistry.getArguments())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
        val profile = s813RequiredArgument(args, "S813_PROFILE")
        val pass = s813RequiredArgument(args, "S813_PASS")
        require(pass in setOf("1", "2", "3")) {
            "the complete-suite identity smoke requires repetition 1, 2, or 3"
        }
        val launch = s813FreezeBoundQualificationLaunch(
            attemptId = args["S813_ATTEMPT_ID"],
            batchId = args["S813_BATCH_ID"],
            snapshotId = args["S813_SNAPSHOT_ID"],
            profile = profile,
            expectedProfile = S813_QUALIFICATION_CANDIDATE,
            expectedBatchId = S813_QUALIFICATION_BATCH_ID,
        )

        fun launchFor(
            attemptId: String,
            batchId: String,
            snapshotId: String,
            candidate: String = profile,
        ) = s813FreezeBoundQualificationLaunch(
            attemptId = attemptId,
            batchId = batchId,
            snapshotId = snapshotId,
            profile = candidate,
            expectedProfile = S813_QUALIFICATION_CANDIDATE,
            expectedBatchId = S813_QUALIFICATION_BATCH_ID,
        )

        val fixtureBytes = instrumentationContext.assets
            .open("s8_agent_v3_qualification_fixture.json").use { it.readBytes() }
        val scoringBytes = instrumentationContext.assets
            .open("s8_agent_v3_qualification_scoring.json").use { it.readBytes() }
        val fixture = JSONObject(String(fixtureBytes, Charsets.UTF_8))
        val scoring = JSONObject(String(scoringBytes, Charsets.UTF_8))
        require(scoring.getString("fixture_id") == fixture.getString("fixture_id")) {
            "the frozen scorer is not bound to the frozen qualification fixture"
        }
        val caseIds = s813FrozenCaseOrder(fixture)
        val fixtureSha256 = s813Sha256Hex(fixtureBytes)
        val scoringSha256 = s813Sha256Hex(scoringBytes)
        val appApkSha256 = s813InstalledPackageApkSha256(context, context.packageName)
        val testApkSha256 = s813InstalledPackageApkSha256(
            instrumentationContext,
            instrumentationContext.packageName,
        )
        val maxOutputTokens = s813RequiredIntArgument(args, "S813_MAX_OUTPUT_TOKENS")

        fun bindingFor(
            bindingArgs: Map<String, String?>,
            tokens: Int = maxOutputTokens,
        ) = s813FrozenFullSuiteBinding(
            launch = launch,
            pass = pass,
            fixtureId = fixture.getString("fixture_id"),
            fixtureSha256 = fixtureSha256,
            scoringSha256 = scoringSha256,
            caseIds = caseIds,
            appApkSha256 = appApkSha256,
            testApkSha256 = testApkSha256,
            maxOutputTokens = tokens,
            args = bindingArgs,
        )

        fun mutated(name: String, value: String): Map<String, String?> =
            args.toMutableMap().also { it[name] = value }

        fun corruptDigest(hex: String): String = hex.dropLast(1) + if (hex.last() == '0') "1" else "0"

        val rejections = linkedMapOf<String, Boolean>()
        fun expectRejected(name: String, block: () -> Unit) {
            rejections[name] = runCatching(block).isFailure
        }

        // Launch identity shapes.
        expectRejected("legacy_a7_identity") {
            launchFor("S8.13-replan-a7", S813_QUALIFICATION_BATCH_ID, "S8.13-runtime-fixture-v3-batch-03-freeze-02")
        }
        expectRejected("legacy_a22_migration_identity") {
            launchFor("S8.13-replan-a22", S813_QUALIFICATION_BATCH_ID, "S8.13-v3-interface-a22-freeze-29")
        }
        expectRejected("wrong_batch") {
            launchFor(launch.attemptId, "S8.13-runtime-fixture-v3-batch-03", launch.snapshotId)
        }
        expectRejected("wrong_candidate") {
            launchFor(launch.attemptId, launch.batchId, launch.snapshotId, "candidate-a")
        }
        expectRejected("snapshot_not_bound_to_attempt") {
            launchFor(launch.attemptId, launch.batchId, "S8.13-runtime-fixture-v3-batch-03-freeze-02")
        }
        expectRejected("snapshot_without_freeze_number") {
            launchFor(launch.attemptId, launch.batchId, "${launch.attemptId}-freeze-x")
        }

        // Run mode shapes: a complete repetition cannot select a case subset.
        expectRejected("complete_pass_with_case_ids") {
            s813QualificationMode(pass, "a8-laya-02")
        }
        expectRejected("unknown_pass") { s813QualificationMode("4", null) }
        val diagnosticMode = s813QualificationMode("diagnostic", "a8-laya-02")
        val reproductionMode = s813QualificationMode("reproduction", "a8-laya-02")

        // Frozen binding arguments and device-derived identity.
        val accepted = bindingFor(args)
        expectRejected("wrong_fixture_id") {
            bindingFor(mutated("S813_FIXTURE_ID", "S8.13-unfrozen-fixture"))
        }
        expectRejected("wrong_fixture_sha256") {
            bindingFor(mutated("S813_FIXTURE_SHA256", corruptDigest(fixtureSha256)))
        }
        expectRejected("wrong_scoring_sha256") {
            bindingFor(mutated("S813_SCORING_SHA256", corruptDigest(scoringSha256)))
        }
        expectRejected("wrong_case_order_sha256") {
            bindingFor(mutated("S813_CASE_ORDER_SHA256", corruptDigest(accepted.caseOrderSha256)))
        }
        expectRejected("wrong_case_order") {
            val reordered = listOf(caseIds.last()) + caseIds.dropLast(1)
            s813FrozenFullSuiteBinding(
                launch = launch,
                pass = pass,
                fixtureId = fixture.getString("fixture_id"),
                fixtureSha256 = fixtureSha256,
                scoringSha256 = scoringSha256,
                caseIds = reordered,
                appApkSha256 = appApkSha256,
                testApkSha256 = testApkSha256,
                maxOutputTokens = maxOutputTokens,
                args = args,
            )
        }
        expectRejected("wrong_app_apk_sha256") {
            bindingFor(mutated("S813_APP_APK_SHA256", corruptDigest(appApkSha256)))
        }
        expectRejected("wrong_test_apk_sha256") {
            bindingFor(mutated("S813_TEST_APK_SHA256", corruptDigest(testApkSha256)))
        }
        expectRejected("wrong_identity_digest") {
            bindingFor(mutated("S813_FROZEN_IDENTITY_SHA256", corruptDigest(accepted.digest)))
        }
        expectRejected("wrong_per_turn_output_budget") { bindingFor(args, tokens = 8192) }
        expectRejected("missing_identity_digest") { bindingFor(args - "S813_FROZEN_IDENTITY_SHA256") }

        // Path bindings: the accepted pass-scoped roots must be addressable and still absent.
        val debugRoot = s813RequirePassScopedDebugRoot(context, launch, pass, args["S813_DEBUG_ROOT"])
        expectRejected("wrong_debug_root") {
            s813RequirePassScopedDebugRoot(
                context,
                launch,
                pass,
                File(context.filesDir, "${launch.attemptId}/debug/${launch.snapshotId}/diagnostic-a8-laya-02")
                    .absolutePath,
            )
        }
        expectRejected("missing_debug_root") {
            s813RequirePassScopedDebugRoot(context, launch, pass, null)
        }
        val expectedRunDirectory = launch.runDirectory(pass)
        val legacyRunDirectory = "${launch.attemptId}/${launch.profile}/pass-$pass"
        val legacyRunDirectoryRejected = runCatching {
            s813CreateRunDirectory(context.filesDir, legacyRunDirectory)
        }.isFailure && !File(context.filesDir, legacyRunDirectory).exists()

        // Socket port and transport failures are contained before any evidence path is written.
        expectRejected("missing_credential_port") { s813RequiredCredentialPort(emptyMap()) }
        expectRejected("non_numeric_credential_port") {
            s813RequiredCredentialPort(mapOf("S813_CREDENTIAL_PORT" to "s813-port"))
        }
        expectRejected("out_of_range_credential_port") {
            s813RequiredCredentialPort(mapOf("S813_CREDENTIAL_PORT" to "70000"))
        }
        val closedPort = ServerSocket(0).use { it.localPort }
        val closedPortRejected = runCatching { s813ReadOneShotCredential(closedPort) }.isFailure

        val runDirectoryAbsent = !File(context.filesDir, expectedRunDirectory).exists()
        val debugRootAbsent = !File(debugRoot).exists()
        val rejectedNames = rejections.filterValues { rejected -> !rejected }.keys
        require(rejectedNames.isEmpty() && legacyRunDirectoryRejected && closedPortRejected) {
            "the wrong-binding contract was not rejected before effect: $rejectedNames"
        }
        require(runDirectoryAbsent && debugRootAbsent) {
            "a rejected launch created run or debug evidence before the credential channel"
        }

        // Every rejection above ran first: this one-shot read can only succeed if nothing consumed it.
        val expectedCredentialSha256 = s813RequiredArgument(args, "S813_TEST_ONLY_CREDENTIAL_SHA256")
        val credential = s813ReadOneShotCredential(s813RequiredCredentialPort(args))
        require(s813Sha256Hex(credential) == expectedCredentialSha256) {
            "the one-shot socket did not deliver the expected TEST_ONLY credential"
        }

        val summaryPath = "${launch.attemptId}/debug/${launch.snapshotId}/preflight-identity-smoke"
        val summaryDirectory = s813CreateRunDirectory(context.filesDir, summaryPath)
        val negativeRecords = JSONArray()
        rejections.forEach { (name, rejected) ->
            negativeRecords.put(JSONObject().put("name", name).put("rejected_before_effect", rejected))
        }
        val record = JSONObject()
            .put("TEST_ONLY", true)
            .put("identity_contract", S813_FROZEN_FULL_SUITE_IDENTITY_CONTRACT)
            .put("attempt_id", launch.attemptId)
            .put("batch_id", launch.batchId)
            .put("snapshot_id", launch.snapshotId)
            .put("profile", launch.profile)
            .put("pass", pass)
            .put("measurement_scope", "full_fixture")
            .put("case_count", caseIds.size)
            .put("frozen_identity", accepted.toJson())
            .put("accepted_identity_verified", true)
            .put("run_directory_relative", expectedRunDirectory)
            .put("debug_root_relative", "files/${launch.debugRelativeRoot(pass)}")
            .put("run_directory_absent", runDirectoryAbsent)
            .put("debug_root_absent", debugRootAbsent)
            .put("legacy_run_directory_rejected", legacyRunDirectoryRejected)
            .put("legacy_identity_rejected", true)
            .put("diagnostic_mode_identified", diagnosticMode == S813QualificationMode.DIAGNOSTIC)
            .put("reproduction_mode_identified", reproductionMode == S813QualificationMode.REPRODUCTION)
            .put("negative_cases", negativeRecords)
            .put("negative_case_count", rejections.size)
            .put("closed_port_rejected_before_effect", closedPortRejected)
            .put("credential_transport", "one_shot_socket")
            .put("credential_loaded_over_socket", true)
            .put("credential_sha256_matches_expected", true)
            .put("credential_recorded", false)
            .put("credential_material_recorded", false)
            .put("hosted_provider_calls", 0)
            .put("endpoint_attempted", false)
            .put("generated_at", java.time.Instant.now().toString())
        File(summaryDirectory, "summary.json").writeText(record.toString(2))
        assertTrue(
            "the accepted identity must bind every wrong-binding rejection before the credential load",
            rejections.values.all { it },
        )
    }
}
