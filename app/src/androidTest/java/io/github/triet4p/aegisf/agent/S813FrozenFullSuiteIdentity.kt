package io.github.triet4p.aegisf.agent

import android.content.Context
import android.os.Bundle
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Freeze-bound identity contract for the S8.13 v3 qualification harness.
 *
 * Every accepted device launch (complete 24-case suite, approved one-case diagnostic, explicit
 * reproduction subset) must name the attempt and its own `-freeze-N` snapshot, carry the frozen
 * batch and candidate, and load the hosted credential only from the one-shot process-memory socket.
 * The complete suite additionally re-derives the frozen fixture, scorer, case order and installed
 * APK bytes on the device and requires the host-supplied identity digest to match. Nothing here
 * reads a credential or writes evidence; callers do that only after these checks pass.
 */

internal const val S813_FROZEN_FULL_SUITE_IDENTITY_CONTRACT = "aegis-s813-frozen-full-suite-identity/v1"
internal const val S813_QUALIFICATION_BATCH_ID = "S8.13-v3-interface-qualification-batch-21"
internal const val S813_QUALIFICATION_FIXTURE_ID = "S8.13-v3-interface-qualification-fixture-01"
internal const val S813_QUALIFICATION_CANDIDATE = "candidate-b"
internal const val S813_QUALIFICATION_MAX_OUTPUT_TOKENS = 384_000
internal const val S813_QUALIFICATION_CASE_COUNT = 24

/** Pre-v3 attempt identities whose frozen snapshots are retained evidence, never launch inputs. */
private val s813RetiredLaunchAttempts = setOf("S8.13-replan-a7", "S8.13-replan-a22", "S8.13-replan-a9")

internal data class S813QualificationLaunch(
    val attemptId: String,
    val batchId: String,
    val snapshotId: String,
    val profile: String,
) {
    fun runDirectory(pass: String): String = "$attemptId/$snapshotId/$profile/pass-$pass"

    fun debugRelativeRoot(pass: String): String = "$attemptId/debug/$snapshotId/pass-$pass"
}

internal enum class S813QualificationMode { FULL_SUITE, DIAGNOSTIC, REPRODUCTION }

/**
 * A complete repetition must not smuggle a case subset through `S813_CASE_IDS`, and a partial run
 * must declare its own pass. Selects the launch mode and rejects the mixed shapes.
 */
internal fun s813QualificationMode(pass: String, rawCaseIds: String?): S813QualificationMode = when (pass) {
    "reproduction" -> S813QualificationMode.REPRODUCTION
    "diagnostic" -> S813QualificationMode.DIAGNOSTIC
    "1", "2", "3" -> {
        require(rawCaseIds == null) {
            "a complete qualification repetition must not select diagnostic case IDs"
        }
        S813QualificationMode.FULL_SUITE
    }
    else -> error("unsupported S8.13 qualification pass: $pass")
}

/** Requires a fresh attempt identity whose snapshot is its own explicit `-freeze-N` record. */
internal fun s813FreezeBoundQualificationLaunch(
    attemptId: String?,
    batchId: String?,
    snapshotId: String?,
    profile: String,
    expectedProfile: String,
    expectedBatchId: String,
): S813QualificationLaunch {
    val attempt = s813ValidatedPathComponent(attemptId)
    val batch = s813ValidatedPathComponent(batchId)
    val snapshot = s813ValidatedPathComponent(snapshotId)
    require(profile == expectedProfile) {
        "the freeze-bound S8.13 qualification requires the $expectedProfile candidate"
    }
    require(batch == expectedBatchId) {
        "the freeze-bound S8.13 qualification requires batch $expectedBatchId"
    }
    require(attempt.startsWith("S8.13-")) { "the qualification attempt identity must belong to S8.13" }
    require(attempt !in s813RetiredLaunchAttempts) {
        "retired pre-v3 qualification identity cannot launch a new run"
    }
    val freezePrefix = "$attempt-freeze-"
    require(snapshot.startsWith(freezePrefix)) {
        "the snapshot must be the explicit freeze of attempt $attempt"
    }
    val freezeNumber = snapshot.removePrefix(freezePrefix)
    require(freezeNumber.isNotEmpty() && freezeNumber.all(Char::isDigit)) {
        "the snapshot must carry its explicit -freeze-N number"
    }
    return S813QualificationLaunch(attempt, batch, snapshot, profile)
}

/** Requires the TEST_ONLY capture root to be the exact pass-scoped path of this launch. */
internal fun s813RequirePassScopedDebugRoot(
    context: Context,
    launch: S813QualificationLaunch,
    pass: String,
    debugRoot: String?,
): String {
    val root = requireNotNull(debugRoot?.takeIf { it.isNotBlank() }) {
        "a complete qualification repetition requires the TEST_ONLY raw capture root"
    }
    val expected = File(context.filesDir, launch.debugRelativeRoot(pass)).canonicalFile
    require(File(root).canonicalFile == expected) {
        "the TEST_ONLY capture root does not match the freeze-bound pass identity"
    }
    return root
}

/** The frozen 16 dev + 8 heldout case order, validated against the fixture's declared split. */
internal fun s813FrozenCaseOrder(qualificationFixture: JSONObject): List<String> {
    val counts = qualificationFixture.getJSONObject("suite_counts")
    require(
        counts.getInt("dev") == 16 &&
            counts.getInt("heldout") == 8 &&
            counts.getInt("total") == S813_QUALIFICATION_CASE_COUNT,
    ) { "the frozen qualification fixture does not declare the 16/8/24 case split" }
    val cases = qualificationFixture.getJSONArray("cases")
    val caseIds = (0 until cases.length()).map { cases.getJSONObject(it).getString("id") }
    require(caseIds.size == S813_QUALIFICATION_CASE_COUNT && caseIds.distinct().size == caseIds.size) {
        "the frozen qualification case order must contain 24 unique cases"
    }
    return caseIds
}

/** SHA-256 of the installed artifact of [packageName], read from the package manager's APK path. */
internal fun s813InstalledPackageApkSha256(context: Context, packageName: String): String {
    val applicationInfo = if (packageName == context.packageName) {
        context.applicationInfo
    } else {
        context.packageManager.getApplicationInfo(packageName, 0)
    }
    val apk = File(requireNotNull(applicationInfo.sourceDir) { "installed package artifact is unavailable" })
    require(apk.isFile) { "installed package artifact is not a file" }
    val digest = MessageDigest.getInstance("SHA-256")
    apk.inputStream().buffered().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun s813InstrumentationArguments(args: Bundle): Map<String, String?> =
    args.keySet().associateWith { key -> args.getString(key) }

internal fun s813RequiredArgument(args: Map<String, String?>, name: String): String =
    requireNotNull(args[name]?.takeIf { it.isNotBlank() }) { "$name is required" }

internal fun s813RequiredIntArgument(args: Map<String, String?>, name: String): Int =
    requireNotNull(s813RequiredArgument(args, name).toIntOrNull()) { "$name must be an integer" }

internal fun s813RequiredCredentialPort(args: Map<String, String?>): Int {
    val port = s813RequiredIntArgument(args, "S813_CREDENTIAL_PORT")
    require(port in 1..65535) { "the one-shot credential port must be a valid TCP port" }
    return port
}

/** Reads the one-shot process-memory credential; the host serves it on a loopback reverse socket. */
internal fun s813ReadOneShotCredential(port: Int): String {
    Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 10_000)
        val value = socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readLine() }
        requireNotNull(value) { "credential channel returned no value" }
        require(value.isNotBlank()) { "credential channel returned an empty value" }
        return value
    }
}

internal fun s813Sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

internal fun s813Sha256Hex(value: String): String = s813Sha256Hex(value.toByteArray(Charsets.UTF_8))

/**
 * The single frozen identity of one complete repetition. Each value is either re-derived on the
 * device (fixture/scoring/case order/APK digests) or is a path component of this launch, and the
 * host must supply every binding argument plus the digest of exactly this record.
 */
internal data class S813FrozenFullSuiteBinding(
    val launch: S813QualificationLaunch,
    val pass: String,
    val fixtureId: String,
    val fixtureSha256: String,
    val scoringSha256: String,
    val caseIds: List<String>,
    val appApkSha256: String,
    val testApkSha256: String,
    val maxOutputTokens: Int,
) {
    val caseOrderSha256: String get() = s813Sha256Hex(JSONArray(caseIds).toString())

    fun canonicalLines(): List<String> = listOf(
        "contract=$S813_FROZEN_FULL_SUITE_IDENTITY_CONTRACT",
        "attempt_id=${launch.attemptId}",
        "batch_id=${launch.batchId}",
        "snapshot_id=${launch.snapshotId}",
        "candidate=${launch.profile}",
        "pass=$pass",
        "fixture_id=$fixtureId",
        "fixture_sha256=$fixtureSha256",
        "scoring_sha256=$scoringSha256",
        "case_order_sha256=$caseOrderSha256",
        "case_count=${caseIds.size}",
        "app_apk_sha256=$appApkSha256",
        "test_apk_sha256=$testApkSha256",
        "max_output_tokens=$maxOutputTokens",
        "max_turns_per_case=10",
        "max_tool_calls_per_case=12",
        "max_context_characters=160000",
        "max_wall_clock_millis=900000",
        "max_repeated_identical_calls=3",
        "max_capture_observations=4",
        "debug_root_relative=files/${launch.debugRelativeRoot(pass)}",
    )

    val digest: String get() = s813Sha256Hex(canonicalLines().joinToString("\n"))

    fun toJson(): JSONObject = JSONObject()
        .put("contract", S813_FROZEN_FULL_SUITE_IDENTITY_CONTRACT)
        .put("attempt_id", launch.attemptId)
        .put("batch_id", launch.batchId)
        .put("snapshot_id", launch.snapshotId)
        .put("candidate", launch.profile)
        .put("pass", pass)
        .put("fixture_id", fixtureId)
        .put("fixture_sha256", fixtureSha256)
        .put("scoring_sha256", scoringSha256)
        .put("case_order_sha256", caseOrderSha256)
        .put("case_count", caseIds.size)
        .put("app_apk_sha256", appApkSha256)
        .put("test_apk_sha256", testApkSha256)
        .put("max_output_tokens", maxOutputTokens)
        .put("max_turns_per_case", 10)
        .put("max_tool_calls_per_case", 12)
        .put("max_context_characters", 160000)
        .put("max_wall_clock_millis", 900000)
        .put("max_repeated_identical_calls", 3)
        .put("max_capture_observations", 4)
        .put("debug_root_relative", "files/${launch.debugRelativeRoot(pass)}")
        .put("digest", digest)
}

/**
 * Validates the complete-suite launch against the frozen fixture, scorer, case order and installed
 * APKs, then requires the host's binding arguments to equal those device-derived values and to
 * carry the digest of exactly this record. Any partial, stale or mixed identity fails here, before
 * the credential channel is read and before any evidence path is created.
 */
internal fun s813FrozenFullSuiteBinding(
    launch: S813QualificationLaunch,
    pass: String,
    fixtureId: String,
    fixtureSha256: String,
    scoringSha256: String,
    caseIds: List<String>,
    appApkSha256: String,
    testApkSha256: String,
    maxOutputTokens: Int,
    args: Map<String, String?>,
): S813FrozenFullSuiteBinding {
    require(fixtureId == S813_QUALIFICATION_FIXTURE_ID) {
        "the frozen qualification fixture identity is not the accepted fixture"
    }
    require(pass in setOf("1", "2", "3")) {
        "a complete qualification repetition requires repetition 1, 2, or 3"
    }
    require(maxOutputTokens == S813_QUALIFICATION_MAX_OUTPUT_TOKENS) {
        "a complete qualification repetition requires the frozen per-turn output budget"
    }
    require(caseIds.size == S813_QUALIFICATION_CASE_COUNT && caseIds.distinct().size == caseIds.size) {
        "a complete qualification repetition requires the frozen 24-case order"
    }
    val binding = S813FrozenFullSuiteBinding(
        launch = launch,
        pass = pass,
        fixtureId = fixtureId,
        fixtureSha256 = fixtureSha256,
        scoringSha256 = scoringSha256,
        caseIds = caseIds,
        appApkSha256 = appApkSha256,
        testApkSha256 = testApkSha256,
        maxOutputTokens = maxOutputTokens,
    )
    listOf(
        "S813_FIXTURE_ID" to binding.fixtureId,
        "S813_FIXTURE_SHA256" to binding.fixtureSha256,
        "S813_SCORING_SHA256" to binding.scoringSha256,
        "S813_CASE_ORDER_SHA256" to binding.caseOrderSha256,
        "S813_APP_APK_SHA256" to binding.appApkSha256,
        "S813_TEST_APK_SHA256" to binding.testApkSha256,
        "S813_FROZEN_IDENTITY_SHA256" to binding.digest,
    ).forEach { (name, expected) ->
        require(s813RequiredArgument(args, name) == expected) {
            "$name does not match the freeze-bound complete-suite identity"
        }
    }
    return binding
}
