package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The exact bytes handed to an endpoint: URL, headers and a JSON body. */
data class HostedHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

/** The endpoint's raw answer; parsing and classification belong to the adapter. */
data class HostedHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/** The only network dependency of the adapters, so a bounded smoke can observe the wire. */
interface HttpTransport {
    suspend fun post(request: HostedHttpRequest): HostedHttpResponse
}

/** A 2xx payload larger than the transport will buffer; the adapter reports it as invalid. */
class HostedResponseTooLargeException(message: String) : IOException(message)

/**
 * The production transport: one bounded HTTPS POST per call over [HttpURLConnection].
 *
 * Two deliberate properties protect the configured credential. Redirects are never followed,
 * because `HttpURLConnection` would replay the authorization header against the new location;
 * a 3xx answer is returned to the adapter and classified as [HostedProviderFailure.Redirected].
 * And nothing is logged: the URL, headers and body stay inside this class and the adapter, so a
 * credential cannot reach a log line, an exception message, or a retained report.
 */
class UrlConnectionHttpTransport(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : HttpTransport, HttpStreamTransport {
    init {
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
            throw HostedConfigurationException("transport timeouts must be positive")
        }
    }

    override suspend fun post(request: HostedHttpRequest): HostedHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                useCaches = false
                doOutput = true
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                connection.outputStream.use { stream ->
                    stream.write(request.body.toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                val stream: InputStream? =
                    if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { readBounded(it) }.orEmpty()
                HostedHttpResponse(
                    status = status,
                    headers = safeHeaders(connection),
                    body = body,
                )
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Streams one request as server-sent events.
     *
     * The connection is opened, the head is delivered as [HostedStreamFrame.Start], and the body
     * is read incrementally into [HostedSseDecoder]. A non-2xx answer is not an event stream:
     * its bounded body is delivered once and no events follow, so the adapter classifies it
     * exactly like a complete-body answer. The total decoded stream is bounded; exceeding the
     * bound fails closed with [HostedResponseTooLargeException] instead of truncating silently.
     */
    override fun postStream(request: HostedHttpRequest): Flow<HostedStreamFrame> = flow {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            useCaches = false
            doOutput = true
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            connection.outputStream.use { stream ->
                stream.write(request.body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val headers = safeHeaders(connection)
            if (status !in 200..299) {
                val errorBody = connection.errorStream?.use { readBounded(it) }.orEmpty()
                emit(HostedStreamFrame.Start(status, headers, errorBody))
                return@flow
            }
            emit(HostedStreamFrame.Start(status, headers, null))
            val decoder = HostedSseDecoder()
            var total = 0
            connection.inputStream.use { stream ->
                val reader = java.io.InputStreamReader(stream, Charsets.UTF_8)
                val buffer = CharArray(READ_CHUNK_CHARS)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_STREAM_BYTES) {
                        throw HostedResponseTooLargeException(
                            "streamed endpoint returned more than $MAX_STREAM_BYTES characters",
                        )
                    }
                    decoder.feed(String(buffer, 0, read)).forEach { event ->
                        emit(HostedStreamFrame.Event(event))
                    }
                }
            }
            decoder.finish().forEach { event -> emit(HostedStreamFrame.Event(event)) }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun safeHeaders(connection: HttpURLConnection): Map<String, String> =
        connection.headerFields
            .filterKeys { it != null }
            .mapKeys { (name, _) -> name.lowercase() }
            .mapValues { (_, values) -> values.firstOrNull().orEmpty() }

    private fun readBounded(stream: InputStream): String {
        val buffer = ByteArray(8_192)
        val collected = java.io.ByteArrayOutputStream()
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (collected.size() + read > MAX_RESPONSE_BYTES) {
                throw HostedResponseTooLargeException(
                    "endpoint returned more than $MAX_RESPONSE_BYTES bytes",
                )
            }
            collected.write(buffer, 0, read)
        }
        return collected.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 120_000
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_STREAM_BYTES = 8 * 1024 * 1024
        const val READ_CHUNK_CHARS = 4_096
    }
}
