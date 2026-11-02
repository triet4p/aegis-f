package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.triet4p.aegisf.privacy.IssuedValueShape
import io.github.triet4p.aegisf.privacy.SensitiveVault
import io.github.triet4p.aegisf.provider.HostedProtocol
import java.io.File
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Local-only comparison of raw-body masking with JSON-leaf diagnostics. */
@RunWith(AndroidJUnit4::class)
class S813ResponseSerializationDiagnosticInstrumentedTest {
    @Test
    fun actualVaultMappingsRemainFailClosedAcrossJsonSerializationBoundaries() {
        val args = InstrumentationRegistry.getArguments()
        val attemptId = requireNotNull(args.getString("S813_ATTEMPT_ID"))
        require(attemptId == ATTEMPT_ID)

        val testContext = InstrumentationRegistry.getInstrumentation().context
        val runtimeProjection = readAssetObject(testContext, "s8_agent_runtime_fixture_v3.json")
        assertTrue(runtimeProjection.getString("projection_id") == "S8.13-runtime-fixture-v3")
        val draftOverride = runtimeProjection.getJSONObject("draft_overrides_by_case")
            .getJSONObject(CASE_ID)
            .getJSONObject(DRAFT_ID)
        val template = draftOverride.getString("description_template")
        val repetitions = draftOverride.getInt("repetitions")
        assertTrue(repetitions == 128)
        val projectedDescription = buildString {
            for (index in 1..repetitions) {
                append(String.format(Locale.ROOT, template, index))
            }
        }
        val vault = SensitiveVault("s813-response-serialization-${UUID.randomUUID()}")
        val maskedDescription = vault.maskText(projectedDescription)
        val accountToken = SensitiveVault.TOKEN_PATTERN.findAll(maskedDescription)
            .map { it.value }
            .first { token -> vault.rehydrateValue(token).let { it.length >= 4 && it.all(Char::isDigit) } }
        val mappedAccountSurface = vault.rehydrateValue(accountToken)
        assertTrue(vault.scanLeakShapes(mappedAccountSurface).contains(IssuedValueShape.NUMERIC))
        assertTrue(vault.scanLeakShapes(accountToken).isEmpty())
        val bareDigitMatches = Regex("\\b\\d{4,}\\b").findAll(projectedDescription)
            .filter { it.value == mappedAccountSurface }
            .toList()
        val accountHintDigitRanges = Regex(
            "(?i)(?:\\baccount(?:\\s+ending)?|\\bending)\\s*:?\\s*#?\\s*(\\d{4,})",
        ).findAll(projectedDescription)
            .mapNotNull { it.groups[1]?.range }
            .filter { range -> projectedDescription.substring(range) == mappedAccountSurface }
            .toList()
        val detectionRuleName = if (accountHintDigitRanges.isNotEmpty()) {
            "ACCOUNT_HINT_PATTERN"
        } else {
            "BARE_DIGITS_PATTERN"
        }
        val mappedDigitRanges = if (accountHintDigitRanges.isNotEmpty()) {
            accountHintDigitRanges
        } else {
            bareDigitMatches.map { it.range }
        }
        assertTrue(mappedDigitRanges.isNotEmpty())


        val contentBody = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put(
                "message", JSONObject().put("content", mappedAccountSurface),
            )))
            .toString()
        val metadataBody = JSONObject().put("id", mappedAccountSurface).toString()
        assertTrue(mappedAccountSurface.length == 4)
        val metadataNumericLiteral = if (mappedAccountSurface.first() == '0') {
            "1$mappedAccountSurface"
        } else {
            mappedAccountSurface
        }
        val actualNumericMetadataBody = JSONObject()
            .put(
                "usage",
                JSONObject().put("total_tokens", metadataNumericLiteral.toLong()),
            )
            .toString()
        val actualNumericMetadataCase = safeCase(
            "actual_vault_numeric_metadata",
            actualNumericMetadataBody,
            vault,
        )
        val numberBoundaryContentBody = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put(
                "message",
                JSONObject().put("content", "value $metadataNumericLiteral"),
            )))
            .toString()
        val numberBoundaryContentCase = safeCase(
            "model_content_number_boundary",
            numberBoundaryContentBody,
            vault,
        )
        val tokenBody = JSONObject().put("id", accountToken).toString()
        val escapedDigits = mappedAccountSurface.map { digit ->
            "\\u%04x".format(Locale.ROOT, digit.code)
        }.joinToString("")
        val escapedDigitsBody = "{\"id\":\"$escapedDigits\"}"
        val duplicateKeyBody = "{\"id\":\"$mappedAccountSurface\",\"id\":\"safe\"}"
        val duplicateLastValueWins = runCatching {
            JSONObject(duplicateKeyBody).getString("id") == "safe"
        }.getOrDefault(false)

        val cases = JSONArray()
            .put(safeCase("serialized_model_content", contentBody, vault))
            .put(safeCase("serialized_provider_metadata", metadataBody, vault))
            .put(actualNumericMetadataCase)
            .put(numberBoundaryContentCase)
            .put(safeCase("escaped_digit_string", escapedDigitsBody, vault))
            .put(safeCase("duplicate_json_key", duplicateKeyBody, vault))
            .put(safeCase("masked_token_only", tokenBody, vault))
        var unicodeEscapeCanReproduce = false
        if (mappedAccountSurface.length == 4) {
            val unicodeEscapeBody = "{\"id\":\"\\u$mappedAccountSurface\"}"
            val unicodeDiagnostics = safeInboundResponseDiagnostics(
                HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                vault,
                unicodeEscapeBody,
            )
            cases.put(safeCase("unicode_escape_collision", unicodeEscapeBody, vault))
            unicodeEscapeCanReproduce = vault.scanLeakShapes(unicodeEscapeBody)
                .contains(IssuedValueShape.NUMERIC) &&
                "response_body.unclassified_json" in unicodeDiagnostics.locations
        }
        val numericVault = SensitiveVault("s813-number-${UUID.randomUUID()}")
        val numericToken = SensitiveVault.TOKEN_PATTERN.findAll(
            numericVault.maskText("account ending 7319"),
        ).map { it.value }.first { numericVault.rehydrateValue(it) == "7319" }
        val numericBody = JSONObject()
            .put("usage", JSONObject().put("total_tokens", 7319))
            .toString()
        val numericPrimitiveCase = safeCase("numeric_primitive", numericBody, numericVault)
        cases.put(numericPrimitiveCase)

        val amountVault = SensitiveVault("s813-amount-normalization-${UUID.randomUUID()}")
        amountVault.maskText("transaction 28,000 VND")
        val amountGroupingNormalization = amountVault.scanLeakShapes("28,000")
            .contains(IssuedValueShape.NUMERIC) &&
            amountVault.scanLeakShapes("28000").contains(IssuedValueShape.NUMERIC)

        fun hasField(case: JSONObject, location: String, category: String): Boolean =
            case.getJSONArray("fields").let { fields ->
                (0 until fields.length()).any { index ->
                    val field = fields.getJSONObject(index)
                    field.getString("location") == location &&
                        field.getString("category") == category
                }
            }
        assertTrue(hasField(cases.getJSONObject(0), "response_body.choices[].message.content", "model_content"))
        assertTrue(hasField(cases.getJSONObject(1), "response_body.id", "provider_metadata"))
        assertTrue(hasField(actualNumericMetadataCase, "response_body.usage.total_tokens", "provider_metadata"))
        assertTrue(hasField(numberBoundaryContentCase, "response_body.choices[].message.content", "model_content"))
        assertTrue(actualNumericMetadataCase.getJSONArray("whole_body_shapes").toString().contains("numeric"))
        assertTrue(numberBoundaryContentCase.getJSONArray("whole_body_shapes").toString().contains("numeric"))
        assertTrue(hasField(cases.getJSONObject(4), "response_body.id", "provider_metadata"))
        assertTrue(duplicateLastValueWins)
        assertTrue(cases.getJSONObject(5).getJSONArray("locations").toString().contains("unclassified_json"))
        assertTrue(cases.getJSONObject(6).getJSONArray("whole_body_shapes").length() == 0)
        assertTrue(cases.getJSONObject(6).getJSONArray("diagnosed_shapes").length() == 0)
        assertTrue(numericVault.scanLeakShapes(numericToken).isEmpty())
        assertTrue(hasField(numericPrimitiveCase, "response_body.usage.total_tokens", "provider_metadata"))
        assertTrue(amountGroupingNormalization)
        if (mappedAccountSurface.length == 4) {
            assertTrue(unicodeEscapeCanReproduce)
        }

        val summary = JSONObject()
            .put("task_id", "S8.13")
            .put("attempt_id", attemptId)
            .put("diagnostic_only", true)
            .put("hosted_provider_calls", 0)
            .put("actual_vault_source", "oversize_projected_draft_description")
            .put("actual_vault_account_mapping_present", true)
            .put("account_mapping_provenance", JSONObject()
                .put("rule_name", detectionRuleName)
                .put("length_category", "four_digits")
                .put("source_kind", "oversize_projected_draft_description"))
            .put("amount_grouping_normalization_proven", amountGroupingNormalization)
            .put("json_duplicate_key_last_value_wins", duplicateLastValueWins)
            .put("unicode_escape_collision_reproduced", unicodeEscapeCanReproduce)
            .put("raw_values_or_tokens_persisted", false)
            .put("cases", cases)
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val output = File(appContext.filesDir, "$attemptId/response-serialization-diagnostic-v2.json")
        check(!output.exists())
        output.parentFile?.mkdirs()
        output.writeText(summary.toString(2))
        assertTrue(cases.length() >= 5)
    }

    private fun readAssetObject(context: Context, name: String): JSONObject =
        context.assets.open(name).use { input ->
            JSONObject(input.readBytes().toString(Charsets.UTF_8))
        }

    private fun safeCase(name: String, body: String, vault: SensitiveVault): JSONObject {
        val wholeBodyShapes = vault.scanLeakShapes(body).map { it.wire }.sorted()
        val diagnostics = safeInboundResponseDiagnostics(
            HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            vault,
            body,
        )
        return JSONObject()
            .put("scenario", name)
            .put("whole_body_shapes", JSONArray(wholeBodyShapes))
            .put("diagnosed_shapes", JSONArray(diagnostics.shapes))
            .put("locations", JSONArray(diagnostics.locations))
            .put("categories", JSONArray(diagnostics.categories))
            .put("fields", JSONArray().also { fields ->
                diagnostics.fields.forEach { field ->
                    fields.put(JSONObject()
                        .put("shape", field.shape)
                        .put("location", field.location)
                        .put("category", field.category))
                }
            })
    }

    private companion object {
        const val ATTEMPT_ID = "S8.13-replan-a12"
        const val CASE_ID = "a8-laya-02"
        const val DRAFT_ID = "431"
    }
}
