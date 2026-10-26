package io.github.triet4p.aegisf.provider

import kotlinx.coroutines.Dispatchers
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
) : HttpTransport {
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
                    headers = connection.headerFields
                        .filterKeys { it != null }
                        .mapKeys { (name, _) -> name.lowercase() }
                        .mapValues { (_, values) -> values.firstOrNull().orEmpty() },
                    body = body,
                )
            } finally {
                connection.disconnect()
            }
        }

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
    }
}
