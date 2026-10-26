package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkflowSkillCatalogTest {
    @Test
    fun `trusted workflow intent loads only matching versioned instructions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = WorkflowSkillCatalog.load(context.assets::open)

        val instructions = catalog.instructionsFor(
            setOf(WorkflowIntent.SEARCH_DRAFTS, WorkflowIntent.LINK_EVENT_TO_DRAFT),
        )

        assertTrue(instructions.contains("skill_id: s8-draft-search"))
        assertTrue(instructions.contains("skill_id: s8-propose-attach"))
        assertTrue(instructions.contains("limit 1–500"))
        assertTrue(instructions.contains("fresh application observation shows the exact observed"))
        assertTrue(instructions.contains("untrusted data"))
        assertFalse(instructions.contains("skill_id: s8-event-extract"))
        assertFalse(instructions.contains("skill_id: s8-laya-review"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty trusted intent selection cannot expand to all skills`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkflowSkillCatalog.load(context.assets::open).instructionsFor(emptySet())
    }
}
