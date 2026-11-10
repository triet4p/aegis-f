package io.github.triet4p.aegisf.ui

import io.github.triet4p.aegisf.agent.WorkflowRunOutcome
import io.github.triet4p.aegisf.agent.WorkflowRunState
import io.github.triet4p.aegisf.agent.WorkflowRunStep
import io.github.triet4p.aegisf.agent.WorkflowVerifiedEffect
import io.github.triet4p.aegisf.data.ContextLinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S9.6 visible-scope reference resolution: follow-ups resolve only against the cards the
 * settled outcome actually shows. Qualified references bind exactly; bare or ambiguous
 * references ask instead of mutating an arbitrary draft.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatOutcomeReferenceTest {

    @Test
    fun `a qualified event reference resolves to the visible event card`() {
        val visible = visibleRefsOf(completed(eventReading(11, 21, 0)))
        val resolved = resolveFollowUpReference("please fix event #11", visible)
        assertEquals(ChatVisibleRef(ChatVisibleRefKind.EVENT, 11, "event #11"), resolved)
    }

    @Test
    fun `a qualified reference to a row outside the visible scope resolves to nothing`() {
        val visible = visibleRefsOf(completed(eventReading(11, 21, 0)))
        assertNull(resolveFollowUpReference("attach draft #99 instead", visible))
    }

    @Test
    fun `a bare pronoun with several visible cards stays ambiguous`() {
        val refs = listOf(
            ChatVisibleRef(ChatVisibleRefKind.EVENT, 11, "event #11"),
            ChatVisibleRef(ChatVisibleRefKind.DRAFT, 12, "draft #12"),
        )
        assertNull(resolveFollowUpReference("attach that one please", refs))
    }

    @Test
    fun `a bare pronoun with one visible card resolves to it`() {
        val visible = visibleRefsOf(completed(linkState(31, 11, 12)))
        val refs = visible.filter { it.kind == ChatVisibleRefKind.DRAFT }
        assertEquals(
            ChatVisibleRef(ChatVisibleRefKind.DRAFT, 12, "draft #12"),
            resolveFollowUpReference("attach it", refs),
        )
    }

    @Test
    fun `a link effect exposes both its link and draft as visible cards`() {
        val visible = visibleRefsOf(completed(linkState(31, 11, 12)))
        assertEquals(
            listOf(
                ChatVisibleRef(ChatVisibleRefKind.LINK, 31, "link #31"),
                ChatVisibleRef(ChatVisibleRefKind.DRAFT, 12, "draft #12"),
            ),
            visible,
        )
    }

    @Test
    fun `an incomplete outcome exposes no follow-up scope`() {
        val outcome = WorkflowRunOutcome.Incomplete(
            reason = io.github.triet4p.aegisf.agent.WorkflowRunStopReason.TOOL_FAILURE,
            detail = "scoped observation failed",
            answer = null,
            turns = 1,
            toolCalls = 0,
            trace = listOf(WorkflowRunStep(WorkflowRunState.INCOMPLETE)),
        )
        assertEquals(emptyList<ChatVisibleRef>(), visibleRefsOf(outcome))
    }

    @Test
    fun `a Vietnamese kind reference resolves to the same visible card`() {
        val visible = visibleRefsOf(completed(eventReading(11, 21, 0), linkState(31, 11, 12)))
        assertEquals(
            ChatVisibleRef(ChatVisibleRefKind.EVENT, 11, "event #11"),
            resolveFollowUpReference("sửa sự kiện #11", visible),
        )
        assertEquals(
            ChatVisibleRef(ChatVisibleRefKind.DRAFT, 12, "draft #12"),
            resolveFollowUpReference("bản nháp #12 thì sao", visible),
        )
        assertEquals(
            ChatVisibleRef(ChatVisibleRefKind.LINK, 31, "link #31"),
            resolveFollowUpReference("liên kết 31", visible),
        )
    }

    @Test
    fun `a Vietnamese pronoun with one visible card resolves to it`() {
        val visible = visibleRefsOf(completed(eventReading(11, 21, 0)))
        assertEquals(visible.single(), resolveFollowUpReference("ghi nó đi", visible))
    }

    @Test
    fun `a qualified reference naming two visible ids stays ambiguous`() {
        val visible = visibleRefsOf(completed(eventReading(11, 21, 0), linkState(31, 12, 11)))
        assertNull(resolveFollowUpReference("fix event #11 and draft #11", visible))
    }

    private fun eventReading(eventId: Long, captureId: Long, revision: Int) =
        WorkflowVerifiedEffect.EventReading(eventId, captureId, revision)

    private fun linkState(linkId: Long, eventId: Long, draftId: Long) =
        WorkflowVerifiedEffect.LinkState(
            linkId = linkId,
            eventId = eventId,
            draftId = draftId,
            state = ContextLinkState.PROPOSED,
            revision = 0,
        )

    private fun completed(vararg effects: WorkflowVerifiedEffect) =
        WorkflowRunOutcome.Completed(
            answer = "done",
            verifiedEffects = effects.toList(),
            turns = 1,
            toolCalls = 1,
            trace = listOf(WorkflowRunStep(WorkflowRunState.COMPLETED)),
        )
}
