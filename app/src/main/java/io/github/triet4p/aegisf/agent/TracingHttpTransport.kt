package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.provider.HostedConfigurationException
import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HostedStreamFrame
import io.github.triet4p.aegisf.provider.HttpStreamTransport
import io.github.triet4p.aegisf.provider.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * The production transport decorator that records every provider exchange of a bound run.
 *
 * It wraps the app's real transport without changing it: the exact [HostedHttpRequest] the
 * adapter built is recorded, then posted, then the exact [HostedHttpResponse] is recorded. This
 * is deliberately the only wire seam, so all three protocols are traced identically and the
 * adapters keep owning their encodings.
 *
 * A cancelled in-flight call and a failed one are recorded explicitly as interrupted/unavailable
 * instead of leaving a request row that looks answered. The recorder never fails the exchange:
 * tracing cannot change whether a provider call succeeds, and it can never cause a tool call or a
 * business effect to run twice.
 *
 * When the wrapped transport also implements [HttpStreamTransport], [postStream] records the
 * same request, every decoded server-sent event in wire order, and the stream's terminal state.
 * A non-2xx answer is recorded as the ordinary complete response body because it is not a
 * stream. The canonical assembled body of a completed stream is recorded separately by the
 * executor from the adapter's own assembly.
 */
class TracingHttpTransport(
    private val inner: HttpTransport,
    private val recorder: AgentTraceRecorder,
) : HttpTransport, HttpStreamTransport {

    override suspend fun post(request: HostedHttpRequest): HostedHttpResponse {
        val turn = recorder.providerRequest(request)
        return try {
            val response = inner.post(request)
            recorder.providerResponse(turn, response)
            response
        } catch (cancelled: CancellationException) {
            recorder.providerUnavailable(turn, cancelled, interrupted = true)
            throw cancelled
        } catch (failure: Exception) {
            recorder.providerUnavailable(turn, failure)
            throw failure
        }
    }

    override fun postStream(request: HostedHttpRequest): Flow<HostedStreamFrame> = flow {
        val streaming = inner as? HttpStreamTransport
            ?: throw HostedConfigurationException("the configured transport has no streaming capability")
        val turn = recorder.providerRequest(request)
        var status: Int? = null
        var chunks = 0
        try {
            streaming.postStream(request).collect { frame ->
                when (frame) {
                    is HostedStreamFrame.Start -> {
                        status = frame.status
                        // A non-2xx answer is not an event stream: record its bounded body as the
                        // complete provider response, exactly like the complete-body path.
                        frame.errorBody?.let { body ->
                            recorder.providerResponse(
                                turn,
                                HostedHttpResponse(
                                    status = frame.status,
                                    headers = frame.headers,
                                    body = body,
                                ),
                            )
                        }
                        emit(frame)
                    }

                    is HostedStreamFrame.Event -> {
                        recorder.providerStreamChunk(turn, chunks, frame.event)
                        chunks += 1
                        emit(frame)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            recorder.providerStreamEnd(turn, status, chunks, interrupted = true)
            throw cancelled
        } catch (failure: Exception) {
            recorder.providerStreamEnd(
                turn,
                status,
                chunks,
                interrupted = false,
                failure = failure,
            )
            throw failure
        }
        recorder.providerStreamEnd(turn, status, chunks, interrupted = false)
    }
}
