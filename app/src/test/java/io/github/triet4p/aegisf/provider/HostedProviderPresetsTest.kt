package io.github.triet4p.aegisf.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedProviderPresetsTest {
    @Test
    fun `every published preset constructs a valid configuration with no forced output cap`() {
        HostedProviderPresets.all.forEach { preset ->
            val config = preset.profile("Synthetic test profile", "synthetic-test-credential").validatedConfig()
            assertEquals(preset.protocol, config.protocol)
            assertTrue(preset.endpoint.startsWith("https://"))
            assertNull("a preset must not impose an output ceiling", preset.maxOutputTokens)
            assertNull(
                "the serialized budget must leave the selected model's own limit in force",
                config.budget.maxOutputTokens,
            )
        }
    }

    @Test
    fun `published preset identifiers are unique`() {
        assertEquals(
            HostedProviderPresets.all.size,
            HostedProviderPresets.all.map { it.id }.toSet().size,
        )
    }

    @Test
    fun `OpenCode Go preset targets the verified Go Chat Completions route`() {
        val preset = HostedProviderPresets.byId("opencode-go-deepseek")
        assertEquals(HostedProtocol.OPENAI_CHAT_COMPLETIONS, preset.protocol)
        assertEquals("https://opencode.ai/zen/go/v1/chat/completions", preset.endpoint)
        assertEquals("deepseek-v4.1-flash", preset.model)
    }

    @Test
    fun `session metadata is scoped to first-party OpenCode requests`() {
        val opencode = HostedProviderPresets.byId("opencode-go-deepseek")
            .profile("Synthetic test profile", "synthetic-test-credential")
            .validatedConfig("chat-session-test")
        assertEquals(
            mapOf(
                "x-opencode-session" to "aegis-f-chat-session-test",
                "User-Agent" to "aegis-f-android/1.0",
            ),
            opencode.clientHeaders,
        )
        assertTrue(
            HostedProviderPresets.byId("opencode-go-deepseek")
                .profile("Synthetic test profile", "synthetic-test-credential")
                .validatedConfig()
                .clientHeaders
                .isEmpty(),
        )
        assertTrue(
            HostedProviderPresets.byId("google-gemini-flash-lite")
                .profile("Synthetic test profile", "synthetic-test-credential")
                .validatedConfig("chat-session-test")
                .clientHeaders
                .isEmpty(),
        )
    }

    @Test
    fun `OpenCode Go Responses preset does not use the Console inference route`() {
        val preset = HostedProviderPresets.byId("opencode-go-responses")
        assertEquals(HostedProtocol.OPENAI_RESPONSES, preset.protocol)
        assertEquals("https://opencode.ai/zen/go/v1/responses", preset.endpoint)
        assertEquals("gpt-6-luna", preset.model)
    }
}
