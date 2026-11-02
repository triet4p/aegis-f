package io.github.triet4p.aegisf.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The incremental Server-Sent Events decoder used by the streamed provider transport.
 *
 * Every test feeds the same logical stream through different fragmentation points, because the
 * decoder's job is exactly to be insensitive to how the transport's reads split the body.
 */
class HostedSseDecoderTest {

    @Test
    fun `events are decoded identically at every split point`() {
        val stream = buildString {
            append(": keep-alive comment\n\n")
            append("event: response.output_text.delta\n")
            append("data: {\"a\":1}\n")
            append("\n")
            append("data: first line\n")
            append("data: second line\n")
            append("\n")
            append("data: [DONE]\n\n")
        }
        val expected = listOf(
            HostedSseEvent("response.output_text.delta", "{\"a\":1}"),
            HostedSseEvent(null, "first line\nsecond line"),
            HostedSseEvent(null, "[DONE]"),
        )
        assertEquals(expected, decode(stream, fragment = stream.length))
        for (split in 1 until stream.length) {
            assertEquals(
                "split at $split",
                expected,
                HostedSseDecoder().let { decoder ->
                    decoder.feed(stream.substring(0, split)) + decoder.feed(stream.substring(split)) + decoder.finish()
                },
            )
        }
        // One character at a time.
        assertEquals(expected, decode(stream, fragment = 1))
    }

    @Test
    fun `CRLF CR and line ending boundaries are one terminator`() {
        val stream = "data: one\r\n\r\ndata: two\r\rdata: three\n\n"
        assertEquals(
            listOf(
                HostedSseEvent(null, "one"),
                HostedSseEvent(null, "two"),
                HostedSseEvent(null, "three"),
            ),
            decode(stream, fragment = 3),
        )
    }

    @Test
    fun `an unterminated final event is emitted only by finish`() {
        val decoder = HostedSseDecoder()
        assertEquals(emptyList<HostedSseEvent>(), decoder.feed("data: last"))
        assertEquals(listOf(HostedSseEvent(null, "last")), decoder.finish())
        // A second finish never repeats it.
        assertEquals(emptyList<HostedSseEvent>(), decoder.finish())
    }

    @Test
    fun `comments and unknown fields never produce an event`() {
        val decoder = HostedSseDecoder()
        assertEquals(emptyList<HostedSseEvent>(), decoder.feed(": comment\nid: 7\nretry: 10\n\n"))
        assertEquals(emptyList<HostedSseEvent>(), decoder.finish())
    }

    @Test
    fun `an oversized event fails closed instead of growing without bound`() {
        val decoder = HostedSseDecoder(maxEventBytes = 1_024)
        val oversized = "data: " + "x".repeat(2_048) + "\n"
        try {
            decoder.feed(oversized)
            fail("an oversized event must be refused")
        } catch (expected: HostedResponseTooLargeException) {
            assertEquals(true, expected.message.orEmpty().isNotBlank())
        }
    }

    private fun decode(stream: String, fragment: Int): List<HostedSseEvent> {
        val decoder = HostedSseDecoder()
        val events = mutableListOf<HostedSseEvent>()
        var index = 0
        while (index < stream.length) {
            val end = minOf(stream.length, index + fragment)
            events += decoder.feed(stream.substring(index, end))
            index = end
        }
        events += decoder.finish()
        return events
    }
}
