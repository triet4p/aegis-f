package io.github.triet4p.aegisf.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
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

        assertTrue(instructions.contains("skill_id: draft-search"))
        assertTrue(instructions.contains("skill_id: link-event-draft"))
        assertTrue(instructions.contains("fresh application observation shows the intended proposal"))
        assertTrue(instructions.contains("untrusted data"))
        assertFalse(instructions.contains("skill_id: event-reading"))
        assertFalse(instructions.contains("skill_id: draft-review"))
    }

    @Test
    fun `selected skills expose only their required tools and a stable cache identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = WorkflowSkillCatalog.load(context.assets::open)
        val extraction = catalog.select(setOf(WorkflowIntent.INTERPRET_EVENTS)).single()

        assertEquals(WorkflowSkillId.EVENT_READING, extraction.id)
        assertEquals(
            setOf("observe", "select_source", "make_time_anchor", "record_event", "ask_user"),
            catalog.toolsFor(setOf(WorkflowIntent.INTERPRET_EVENTS)),
        )
        assertEquals(
            setOf("observe", "replay_operation"),
            catalog.toolsFor(setOf(WorkflowIntent.REPLAY_OPERATION)),
        )
        assertEquals(
            setOf(
                "observe",
                "select_source",
                "make_time_anchor",
                "record_event",
                "ask_user",
                "list_drafts",
                "inspect_link",
                "propose_link",
                "attach_link",
            ),
            catalog.toolsFor(
                setOf(WorkflowIntent.INTERPRET_EVENTS, WorkflowIntent.LINK_EVENT_TO_DRAFT),
            ),
        )
    }

    @Test
    fun `draft review skill chooses a current category through the ordinary update`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = WorkflowSkillCatalog.load(context.assets::open)
        val review = catalog.select(setOf(WorkflowIntent.DRAFT_CATEGORY_REVIEW)).single()

        assertEquals(WorkflowSkillId.DRAFT_REVIEW, review.id)
        assertEquals(1, review.version)
        assertEquals(
            setOf("list_drafts", "observe", "update_draft_review", "ask_user"),
            review.tools.toSet(),
        )
        assertTrue(review.prerequisites.any { it.contains("category_choices") })
        assertTrue(review.toolOrder.any { it.contains("update_draft_review") })
        assertTrue(review.errorRecovery.getValue("stale_observation").contains("fresh observation"))
        assertTrue(review.examples.any { it.getString("expected").contains("remains pending") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty trusted intent selection cannot expand to all skills`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkflowSkillCatalog.load(context.assets::open).instructionsFor(emptySet())
    }
}
