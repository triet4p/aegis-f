package io.github.triet4p.aegisf.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The descriptor-import policy of a complete-state restore.
 *
 * The backup carries non-secret descriptors; it never carries a credential or an endpoint path.
 * These tests pin what merging those descriptors does to the device's saved profiles.
 */
class HostedProfileImportTest {

    @Test
    fun `existing profile keeps its credential and endpoint while adopting the descriptor`() {
        val local = localProfile("same-id", credential = "local-secret")
        val merged = mergeImportedDescriptors(
            local = listOf(local),
            activeProfileId = "same-id",
            snapshot = HostedProviderExportSnapshot(
                activeProfileId = "same-id",
                profiles = listOf(descriptor("same-id", model = "backup-model")),
            ),
        )

        val profile = merged.profiles.single()
        assertEquals("local-secret", profile.credential)
        assertEquals("https://local.example/v1/chat/completions", profile.endpoint)
        assertEquals("backup-model", profile.model)
        assertEquals(65_536, profile.maxOutputTokens)
        assertEquals(0, merged.result.withoutCredential)
        assertEquals(1, merged.result.imported)
        assertEquals("same-id", merged.activeProfileId)
    }

    @Test
    fun `new descriptor is added without a credential and only the endpoint origin`() {
        val merged = mergeImportedDescriptors(
            local = emptyList(),
            activeProfileId = null,
            snapshot = HostedProviderExportSnapshot(
                activeProfileId = "backup-only",
                profiles = listOf(descriptor("backup-only", model = "backup-model")),
            ),
        )

        val profile = merged.profiles.single()
        assertEquals("backup-only", profile.id)
        assertEquals("https://backup.example:8443", profile.endpoint)
        assertEquals("", profile.credential)
        assertTrue(!profile.hasCredential)
        assertEquals(1, merged.result.withoutCredential)
        // An imported descriptor cannot run until its key is established locally.
        assertNull(merged.activeProfileId)
    }

    @Test
    fun `active selection never follows a credential-less profile`() {
        val merged = mergeImportedDescriptors(
            local = listOf(localProfile("local-only", credential = "local-secret")),
            activeProfileId = "local-only",
            snapshot = HostedProviderExportSnapshot(
                activeProfileId = "backup-only",
                profiles = listOf(descriptor("backup-only", model = "backup-model")),
            ),
        )

        assertEquals("local-only", merged.activeProfileId)
        assertEquals(2, merged.profiles.size)
        assertEquals(1, merged.result.withoutCredential)
        // Profiles the backup does not mention are not destroyed on restore.
        assertTrue(merged.profiles.any { it.id == "local-only" && it.credential == "local-secret" })
    }

    @Test
    fun `descriptor validation accepts an origin-only profile and refuses an insecure one`() {
        SavedHostedProvider(
            id = "gemini",
            name = "Gemini",
            protocol = HostedProtocol.GEMINI_GENERATE_CONTENT,
            endpoint = "https://generativelanguage.googleapis.com",
            model = "gemini-fixture",
            credential = "",
            maxOutputTokens = 32_768,
            reasoningEffort = null,
            temperature = null,
            parallelToolCalls = null,
            capabilities = HostedProviderCapabilities(
                supportsToolCalls = true,
                supportsParallelToolCalls = false,
                supportsReasoningBudget = true,
                supportsTemperature = true,
                maxOutputTokensLimit = 65_536,
            ),
        ).validateDescriptor()

        assertThrows(HostedConfigurationException::class.java) {
            SavedHostedProvider(
                id = "insecure",
                name = "Insecure",
                protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "http://plain.example",
                model = "fixture-model",
                credential = "",
                maxOutputTokens = null,
                reasoningEffort = null,
                temperature = null,
                parallelToolCalls = null,
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = false,
                    supportsTemperature = true,
                ),
            ).validateDescriptor()
        }
    }

    @Test
    fun `rollback restores previous descriptors and removes ids the import added`() {
        val previous = HostedProviderExportSnapshot(
            activeProfileId = "same-id",
            profiles = listOf(descriptor("same-id", model = "previous-model")),
        )
        val imported = HostedProviderExportSnapshot(
            activeProfileId = null,
            profiles = listOf(
                descriptor("same-id", model = "imported-model"),
                descriptor("added-id", model = "added-model"),
            ),
        )
        val afterImport = listOf(
            localProfile("same-id", credential = "local-secret").copy(model = "imported-model"),
            SavedHostedProvider(
                id = "added-id",
                name = "Added",
                protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
                endpoint = "https://backup.example:8443",
                model = "added-model",
                credential = "",
                maxOutputTokens = null,
                reasoningEffort = null,
                temperature = null,
                parallelToolCalls = null,
                capabilities = HostedProviderCapabilities(
                    supportsToolCalls = true,
                    supportsParallelToolCalls = false,
                    supportsReasoningBudget = false,
                    supportsTemperature = true,
                ),
            ),
            localProfile("untouched", credential = "other-secret"),
        )

        val reverted = revertedImportProfiles(afterImport, previous, imported)

        assertEquals(2, reverted.size)
        val restored = reverted.single { it.id == "same-id" }
        assertEquals("previous-model", restored.model)
        assertEquals("Backup same-id", restored.name)
        assertEquals("local-secret", restored.credential)
        assertEquals("https://local.example/v1/chat/completions", restored.endpoint)
        assertTrue(reverted.none { it.id == "added-id" })
        assertEquals(
            "other-secret",
            reverted.single { it.id == "untouched" }.credential,
        )
    }

    private fun localProfile(id: String, credential: String) = SavedHostedProvider(
        id = id,
        name = "Local $id",
        protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        endpoint = "https://local.example/v1/chat/completions",
        model = "local-model",
        credential = credential,
        maxOutputTokens = 40_000,
        reasoningEffort = null,
        temperature = 0.1,
        parallelToolCalls = null,
        capabilities = HostedProviderCapabilities(
            supportsToolCalls = true,
            supportsParallelToolCalls = false,
            supportsReasoningBudget = false,
            supportsTemperature = true,
            maxOutputTokensLimit = 100_000,
        ),
    )

    private fun descriptor(id: String, model: String) = HostedProviderExportProfile(
        id = id,
        name = "Backup $id",
        protocol = HostedProtocol.OPENAI_CHAT_COMPLETIONS,
        endpointOrigin = "https://backup.example:8443",
        model = model,
        maxOutputTokens = 65_536,
        reasoningEffort = null,
        temperature = 0.3,
        parallelToolCalls = null,
        capabilities = HostedProviderCapabilities(
            supportsToolCalls = true,
            supportsParallelToolCalls = false,
            supportsReasoningBudget = false,
            supportsTemperature = true,
            maxOutputTokensLimit = 200_000,
        ),
    )
}
