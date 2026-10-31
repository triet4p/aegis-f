package io.github.triet4p.aegisf.provider

import io.github.triet4p.aegisf.agent.AgentContextManager
import io.github.triet4p.aegisf.agent.MaskingRepairPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bounded masking repair lowers one fixed notice natively on all three protocols.
 *
 * Appending the notice must keep the already-sent prefix byte-identical, must not invent a
 * pending call (an unmatched tool-call continuation is the failure this guards against), and
 * must place the text where that protocol carries a user turn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostedNoticeLoweringTest {

    @Test
    fun `chat completions appends one native user message and keeps the prefix`() = runBlocking {
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"model":"deepseek-v4.1-flash","choices":[{"message":{"role":"assistant","content":"ok"},
                   "finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}""",
            ),
        )
        val adapter = HostedAdapters.create(ProviderFixtures.chatConfig(), transport)
        val conversation = adapter.appendUserNotice(
            adapter.begin("system instructions", "first request"),
            MaskingRepairPolicy.NOTICE,
        )
        assertTrue(conversation.pendingCallIds.isEmpty())
        ProviderFixtures.turn(adapter.generate(conversation, emptyList()))

        val messages = transport.requestJson().getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("system instructions", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("first request", messages.getJSONObject(1).getString("content"))
        assertEquals("user", messages.getJSONObject(2).getString("role"))
        assertEquals(MaskingRepairPolicy.NOTICE, messages.getJSONObject(2).getString("content"))
    }

    @Test
    fun `responses appends one native input_text item and keeps the prefix`() = runBlocking {
        val config = ProviderFixtures.responsesConfig()
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"model":"${config.model}","status":"completed","output":[
                  {"id":"msg_1","type":"message","status":"completed","role":"assistant",
                   "content":[{"type":"output_text","text":"ok"}]}]}""",
            ),
        )
        val adapter = HostedAdapters.create(config, transport)
        val conversation = adapter.appendUserNotice(
            adapter.begin("system instructions", "first request"),
            MaskingRepairPolicy.NOTICE,
        )
        assertTrue(conversation.pendingCallIds.isEmpty())
        ProviderFixtures.turn(adapter.generate(conversation, emptyList()))

        val request = transport.requestJson()
        assertEquals("system instructions", request.getString("instructions"))
        val input = request.getJSONArray("input")
        assertEquals(2, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("first request", input.getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).getString("text"))
        assertEquals("user", input.getJSONObject(1).getString("role"))
        val noticeContent = input.getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("input_text", noticeContent.getString("type"))
        assertEquals(MaskingRepairPolicy.NOTICE, noticeContent.getString("text"))
    }

    @Test
    fun `gemini appends one native user text part and keeps the prefix`() = runBlocking {
        val config = ProviderFixtures.geminiConfig()
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"modelVersion":"${config.model}","candidates":[{"content":{"role":"model",
                   "parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""",
            ),
        )
        val adapter = HostedAdapters.create(config, transport)
        val conversation = adapter.appendUserNotice(
            adapter.begin("system instructions", "first request"),
            MaskingRepairPolicy.NOTICE,
        )
        assertTrue(conversation.pendingCallIds.isEmpty())
        ProviderFixtures.turn(adapter.generate(conversation, emptyList()))

        val request = transport.requestJson()
        assertEquals(
            "system instructions",
            request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0)
                .getString("text"),
        )
        val contents = request.getJSONArray("contents")
        assertEquals(2, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("first request", contents.getJSONObject(0).getJSONArray("parts")
            .getJSONObject(0).getString("text"))
        assertEquals("user", contents.getJSONObject(1).getString("role"))
        assertEquals(
            MaskingRepairPolicy.NOTICE,
            contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"),
        )
    }

    @Test
    fun `chat completions places the inventory notice after the tool outputs`() = runBlocking {
        val config = ProviderFixtures.chatConfig()
        val notice = "issued_mask_token_inventory ($INVENTORY_VERSION): 0 token(s) issued in this run."
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"model":"${config.model}","choices":[{"message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call_1","type":"function",
                     "function":{"name":"probe_echo","arguments":"{\"value\":\"ALPHA-17\"}"}}]},
                   "finish_reason":"tool_calls"}]}""",
            ),
            ProviderFixtures.reply(
                """{"model":"${config.model}","choices":[{"message":{"role":"assistant","content":"ok"},
                   "finish_reason":"stop"}]}""",
            ),
        )
        val adapter = HostedAdapters.create(config, transport)
        val first = ProviderFixtures.turn(
            adapter.generate(adapter.begin("sys", "first"), listOf(ProviderFixtures.echoTool)),
        )
        val next = adapter.continueWithToolResults(
            requireNotNull(first.conversation),
            listOf(HostedToolResult("call_1", """{"stored":"kept"}""")),
            notice,
        )
        ProviderFixtures.turn(adapter.generate(next, listOf(ProviderFixtures.echoTool)))

        val messages = transport.requestJson().getJSONArray("messages")
        val tail = messages.getJSONObject(messages.length() - 1)
        assertEquals("user", tail.getString("role"))
        assertEquals(notice, tail.getString("content"))
        val answered = messages.getJSONObject(messages.length() - 2)
        assertEquals("tool", answered.getString("role"))
        assertEquals("call_1", answered.getString("tool_call_id"))
    }

    @Test
    fun `gemini places the inventory notice inside the tool-response turn`() = runBlocking {
        val config = ProviderFixtures.geminiConfig()
        val notice = "issued_mask_token_inventory ($INVENTORY_VERSION): 0 token(s) issued in this run."
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"modelVersion":"${config.model}","candidates":[{"content":{"role":"model","parts":[
                   {"functionCall":{"name":"probe_echo","args":{"value":"ALPHA-17"},"id":"call_1"}}]},
                   "finishReason":"STOP"}]}""",
            ),
            ProviderFixtures.reply(
                """{"modelVersion":"${config.model}","candidates":[{"content":{"role":"model","parts":[
                   {"text":"ok"}]},"finishReason":"STOP"}]}""",
            ),
        )
        val adapter = HostedAdapters.create(config, transport)
        val first = ProviderFixtures.turn(
            adapter.generate(adapter.begin("sys", "first"), listOf(ProviderFixtures.echoTool)),
        )
        val next = adapter.continueWithToolResults(
            requireNotNull(first.conversation),
            listOf(HostedToolResult("call_1", """{"stored":"kept"}""")),
            notice,
        )
        ProviderFixtures.turn(adapter.generate(next, listOf(ProviderFixtures.echoTool)))

        val contents = transport.requestJson().getJSONArray("contents")
        val toolTurn = contents.getJSONObject(contents.length() - 1)
        assertEquals("user", toolTurn.getString("role"))
        val parts = toolTurn.getJSONArray("parts")
        assertTrue(parts.getJSONObject(0).has("functionResponse"))
        // The tail rides in the same user content, so no extra turn alternation is created.
        assertEquals(notice, parts.getJSONObject(parts.length() - 1).getString("text"))
        assertEquals(contents.length(), 3)
    }

    @Test
    fun `responses places the inventory notice after the function outputs`() = runBlocking {
        val config = ProviderFixtures.responsesConfig()
        val notice = "issued_mask_token_inventory ($INVENTORY_VERSION): 0 token(s) issued in this run."
        val transport = FakeTransport(
            ProviderFixtures.reply(
                """{"model":"${config.model}","status":"completed","output":[
                  {"id":"fc_1","type":"function_call","status":"completed","name":"probe_echo",
                   "call_id":"call_1","arguments":"{\"value\":\"ALPHA-17\"}"}]}""",
            ),
            ProviderFixtures.reply(
                """{"model":"${config.model}","status":"completed","output":[
                  {"id":"msg_1","type":"message","status":"completed","role":"assistant",
                   "content":[{"type":"output_text","text":"ok"}]}]}""",
            ),
        )
        val adapter = HostedAdapters.create(config, transport)
        val first = ProviderFixtures.turn(
            adapter.generate(adapter.begin("sys", "first"), listOf(ProviderFixtures.echoTool)),
        )
        val next = adapter.continueWithToolResults(
            requireNotNull(first.conversation),
            listOf(HostedToolResult("call_1", """{"stored":"kept"}""")),
            notice,
        )
        ProviderFixtures.turn(adapter.generate(next, listOf(ProviderFixtures.echoTool)))

        val input = transport.requestJson().getJSONArray("input")
        val tail = input.getJSONObject(input.length() - 1)
        assertEquals("user", tail.getString("role"))
        assertEquals(notice, tail.getJSONArray("content").getJSONObject(0).getString("text"))
        val answered = input.getJSONObject(input.length() - 2)
        assertEquals("function_call_output", answered.getString("type"))
        assertEquals("call_1", answered.getString("call_id"))
    }

    @Test
    fun `the fixed notice carries no bracket-delimited shape or raw value`() {
        val notice = MaskingRepairPolicy.NOTICE
        assertTrue(notice.isNotBlank())
        assertTrue("notice must not contain the reserved delimiters", notice.none { it == '\u27E6' || it == '\u27E7' })
        assertTrue("notice must stay ASCII", notice.all { it.code in 0x20..0x7E })
        assertTrue("notice must name its version", notice.contains(MaskingRepairPolicy.VERSION))
    }

    private companion object {
        const val INVENTORY_VERSION = AgentContextManager.TOKEN_INVENTORY_VERSION
    }
}
