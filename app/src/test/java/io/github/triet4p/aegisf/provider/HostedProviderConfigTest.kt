package io.github.triet4p.aegisf.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The configured identity is explicit or the configuration does not exist.
 *
 * These pin the refusals a settings surface and the runtime depend on: no plaintext endpoint, no
 * ambiguous model substitution, no credential kind the protocol cannot carry, no requested
 * capability the model did not declare, and no credential leaking through diagnostics.
 */
class HostedProviderConfigTest {

    @Test
    fun `a gemini configuration substitutes only the one declared model placeholder`() {
        val config = ProviderFixtures.geminiConfig()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
            config.endpointForModel(),
        )
        assertTrue(config.endpointForModel().contains(config.model))
    }

    @Test
    fun `a plaintext endpoint is refused`() {
        expectRefusal {
            HostedProviderConfig(
                protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "http://example.test/v1/chat/completions",
                model = "some-model",
                authentication = HostedAuthentication.Bearer(ProviderFixtures.TOKEN),
                budget = HostedGenerationBudget(maxOutputTokens = 1_024),
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = false,
                    supportsTemperature = false,
                ),
            )
        }
    }

    @Test
    fun `a gemini endpoint without its model placeholder is refused`() {
        expectRefusal {
            HostedProviderConfig(
                protocol = HostedProtocol.GEMINI_GENERATE_CONTENT,
                endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
                model = "gemini-3.5-flash-lite",
                authentication = HostedAuthentication.GoogleApiKey(ProviderFixtures.TOKEN),
                budget = HostedGenerationBudget(maxOutputTokens = 1_024),
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = false,
                    supportsTemperature = false,
                ),
            )
        }
    }

    @Test
    fun `an OpenAI-style endpoint must not hide a model placeholder`() {
        expectRefusal {
            HostedProviderConfig(
                protocol = HostedProtocol.OPENAI_RESPONSES,
                endpoint = "https://example.test/v1/models/{model}/responses",
                model = "some-model",
                authentication = HostedAuthentication.Bearer(ProviderFixtures.TOKEN),
                budget = HostedGenerationBudget(maxOutputTokens = 1_024),
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = false,
                    supportsTemperature = false,
                ),
            )
        }
    }

    @Test
    fun `the protocol rejects a credential kind it cannot carry`() {
        expectRefusal {
            HostedProviderConfig(
                protocol = HostedProtocol.GEMINI_GENERATE_CONTENT,
                endpoint = ProviderFixtures.geminiConfig().endpoint,
                model = "gemini-3.5-flash-lite",
                authentication = HostedAuthentication.Bearer(ProviderFixtures.TOKEN),
                budget = HostedGenerationBudget(maxOutputTokens = 1_024),
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = false,
                    supportsTemperature = false,
                ),
            )
        }
    }

    @Test
    fun `a budget above the discovered model limit is refused instead of clipped`() {
        expectRefusal {
            ProviderFixtures.geminiConfig(
                budget = HostedGenerationBudget(maxOutputTokens = 65_537),
            )
        }
    }

    @Test
    fun `an undeclared reasoning budget is a configuration error not a silent omission`() {
        expectRefusal {
            ProviderFixtures.chatConfig(
                budget = HostedGenerationBudget(
                    maxOutputTokens = 8_192,
                    reasoningEffort = HostedReasoningEffort.MEDIUM,
                ),
            )
        }
    }

    @Test
    fun `an undeclared temperature is a configuration error`() {
        expectRefusal {
            ProviderFixtures.responsesConfig(
                budget = HostedGenerationBudget(maxOutputTokens = 4_096, temperature = 0.2),
            )
        }
    }

    @Test
    fun `an undeclared parallel-tool setting is a configuration error`() {
        expectRefusal {
            ProviderFixtures.chatConfig(
                budget = HostedGenerationBudget(maxOutputTokens = 8_192, parallelToolCalls = true),
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = false,
                    supportsTemperature = true,
                ),
            )
        }
    }

    @Test
    fun `an extra header cannot replace the adapter's own credential header`() {
        expectRefusal {
            ProviderFixtures.chatConfig(
                clientHeaders = mapOf("Authorization" to "Bearer something-else"),
            )
        }
    }

    @Test
    fun `an extra header cannot inject a line break`() {
        expectRefusal {
            ProviderFixtures.chatConfig(
                clientHeaders = mapOf("x-opencode-session" to "value\r\ninjected: yes"),
            )
        }
    }

    @Test
    fun `a model id that would change the request path is refused`() {
        expectRefusal {
            HostedProviderConfig(
                protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://example.test/v1/chat/completions",
                model = "vendor/model",
                authentication = HostedAuthentication.Bearer(ProviderFixtures.TOKEN),
                budget = HostedGenerationBudget(maxOutputTokens = 1_024),
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = false,
                    supportsTemperature = false,
                ),
            )
        }
    }

    @Test
    fun `credentials never appear in configuration or authentication diagnostics`() {
        val token = "super-secret-test-token-value"
        val config = HostedProviderConfig(
            protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = "https://example.test/v1/chat/completions",
            model = "some-model",
            authentication = HostedAuthentication.Bearer(token),
            budget = HostedGenerationBudget(maxOutputTokens = 1_024),
            capabilities = HostedProviderCapabilities(
                supportsToolCalls = true,
                supportsParallelToolCalls = false,
                supportsReasoningBudget = false,
                supportsTemperature = false,
            ),
        )
        assertFalse(config.toString().contains(token))
        assertFalse(config.authentication.toString().contains(token))
        assertEquals("[redacted-credential]", config.authentication.redact(token))
    }

    private fun expectRefusal(block: () -> Unit) {
        try {
            block()
            fail("expected a HostedConfigurationException")
        } catch (expected: HostedConfigurationException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }
}
