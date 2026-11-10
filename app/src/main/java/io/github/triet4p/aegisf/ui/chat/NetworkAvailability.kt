package io.github.triet4p.aegisf.ui.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the device currently has a validated internet path.
 *
 * The Chat destination needs this only to tell the truth before a request: without a network
 * there is nothing for a hosted provider to answer. It reads the system's own capability
 * statement and never probes an endpoint, sends a request or treats an unavailable network as a
 * provider failure.
 */
@Composable
internal fun rememberNetworkAvailable(): Boolean {
    val context = LocalContext.current
    var available by remember(context) { mutableStateOf(hasValidatedInternet(context)) }
    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            available = false
            return@DisposableEffect onDispose { }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available = hasValidatedInternet(context)
            }

            override fun onLost(network: Network) {
                available = hasValidatedInternet(context)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                available = hasValidatedInternet(context)
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }
    return available
}

/** Reads the active network's validated-internet capability without contacting anything. */
internal fun hasValidatedInternet(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val validated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } else {
        true
    }
    return internet && validated
}
