package io.github.triet4p.aegisf.provider

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostedProviderSettingsStoreInstrumentedTest {
    /**
     * The production store keeps the isolated app's persistent provider profiles in one private
     * preferences file. A whole-suite run once reached this test without its class filter and its
     * `deleteAll()` wiped that persistent configuration, so this test points the same production
     * store and Keystore path at a per-test preferences file instead. It owns and deletes only
     * that file; the real provider settings are never read, written or cleared here.
     */
    @Test
    fun saveReplaceSelectAndDeleteKeepCredentialOutOfPreferences() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            0,
            base.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
        val testPreferences = "s99_isolated_provider_settings_${UUID.randomUUID()}"
        val context = IsolatedPreferencesContext(base, testPreferences)
        val store = HostedProviderSettingsStore(context)
        try {
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
                .copy(id = first.id, maxOutputTokens = MIN_EXPLICIT_OUTPUT_TOKENS)
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
        } finally {
            // Cleanup is limited to this test's preferences file and its own Keystore aliases.
            store.deleteAll()
            base.deleteSharedPreferences(testPreferences)
        }
    }

    /** Redirects every preferences lookup to one per-test file and keeps the app context local. */
    private class IsolatedPreferencesContext(
        base: Context,
        private val preferencesName: String,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(preferencesName, mode)
    }
}
