package io.github.triet4p.aegisf.provider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostedProviderSettingsStoreInstrumentedTest {
    @Test
    fun saveReplaceSelectAndDeleteKeepCredentialOutOfPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            0,
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
        val store = HostedProviderSettingsStore(context)
        store.deleteAll()
        val preset = HostedProviderPresets.byId("opencode-go-deepseek")
        val firstCredential = "synthetic-only-credential-01"
        val first = preset.profile("Synthetic profile", firstCredential)
            .copy(id = "synthetic-profile")
        assertFalse(first.toString().contains(firstCredential))

        store.save(listOf(first))
        val preferences = context.getSharedPreferences("hosted_provider_settings", 0)
        val firstAlias = requireNotNull(preferences.getString("encryption_key_alias", null))
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val preferenceValues = preferences.all.values.joinToString("|")
        assertTrue(keyStore.containsAlias(firstAlias))
        assertFalse(preferenceValues.contains(firstCredential))
        assertEquals(first, store.load().single())

        val replacementCredential = "synthetic-only-credential-02"
        val replacement = preset.profile("Synthetic profile", replacementCredential)
            .copy(id = first.id, maxOutputTokens = 4096)
        store.save(listOf(replacement))
        val replacementAlias = requireNotNull(preferences.getString("encryption_key_alias", null))
        assertNotEquals(firstAlias, replacementAlias)
        assertFalse(keyStore.containsAlias(firstAlias))
        store.activate(replacement.id)
        assertEquals(replacement, store.active())

        val otherCredential = "synthetic-only-credential-03"
        val other = preset.profile("Second synthetic profile", otherCredential)
            .copy(id = "synthetic-profile-other")
        store.save(listOf(replacement, other))
        val multiProfileAlias = requireNotNull(preferences.getString("encryption_key_alias", null))
        assertFalse(keyStore.containsAlias(replacementAlias))
        val multiProfileValues = preferences.all.values.joinToString("|")
        assertFalse(multiProfileValues.contains(replacementCredential))
        assertFalse(multiProfileValues.contains(otherCredential))

        store.activate(replacement.id)
        assertEquals(replacement, store.active())
        store.delete(replacement.id)
        assertEquals(listOf(other), store.load())
        assertNull(store.active())
        assertFalse(keyStore.containsAlias(multiProfileAlias))

        val finalAlias = requireNotNull(preferences.getString("encryption_key_alias", null))
        assertTrue(keyStore.containsAlias(finalAlias))
        store.delete(other.id)
        assertTrue(store.load().isEmpty())
        assertNull(store.active())
        assertFalse(keyStore.containsAlias(finalAlias))
        assertTrue(preferences.all.isEmpty())
    }
}
