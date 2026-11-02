package io.github.triet4p.aegisf.agent

import io.github.triet4p.aegisf.provider.HostedHttpRequest
import io.github.triet4p.aegisf.provider.HostedHttpResponse
import io.github.triet4p.aegisf.provider.HttpTransport
import kotlinx.coroutines.CancellationException

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
 */
class TracingHttpTransport(
    private val inner: HttpTransport,
    private val recorder: AgentTraceRecorder,
) : HttpTransport {

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
}
