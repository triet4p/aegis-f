package io.github.triet4p.aegisf.provider

import java.net.URI

/**
 * The S8.11 provider identity for one run's stable cache key.
 *
 * Value-free routing material only: protocol, endpoint host (no path, no query, no
 * credential), model id, and the frozen generation budget. No prompt text, no raw
 * values, no mappings, no keys, and no credential ever enter this object. Any stored
 * prefix cache is provider-owned; this identity only attributes the local run and
 * isolates one provider/model/config from another — it never creates an explicit
 * cache resource and never assumes one caching mechanism across providers.
 */
data class HostedProviderIdentity(
    val protocol: HostedProtocol,
    val endpointHost: String,
    val model: String,
    val budget: HostedGenerationBudget,
)

/**
 * The canonical generation-budget material for one run's cache identity: the frozen
 * per-model budget fields, nulls explicit. A budget change is a different cache
 * identity, never a silent substitution.
 */
fun HostedGenerationBudget.canonicalBudgetString(): String = buildString {
    append("maxOutputTokens=").append(maxOutputTokens)
    append(";reasoningEffort=").append(reasoningEffort?.wireName ?: "-")
    append(";temperature=").append(temperature?.toString() ?: "-")
    append(";parallelToolCalls=").append(parallelToolCalls?.toString() ?: "-")
}

/**
 * Parses the host-only identity of one validated provider endpoint: lowercase host,
 * no path, no query, no credential. A validated config never carries user info, so
 * there is nothing secret to strip — the require only guards host-construction bugs.
 */
fun HostedProviderConfig.endpointHost(): String {
    val host = try {
        URI(endpoint.replace(HostedProviderConfig.MODEL_PLACEHOLDER, "model")).host
    } catch (_: Exception) {
        null
    }
    require(!host.isNullOrBlank()) { "provider endpoint must name a host" }
    return host.lowercase()
}

/** The value-free provider identity of one validated configuration. */
fun HostedProviderConfig.identity(): HostedProviderIdentity = HostedProviderIdentity(
    protocol = protocol,
    endpointHost = endpointHost(),
    model = model,
    budget = budget,
)
