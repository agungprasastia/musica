package com.music.musica.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class NetworkType {
    WIFI,
    CELLULAR,
    OFFLINE,
}

class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val activeNetworks = ConcurrentHashMap<Network, NetworkType>()

    private val _networkType = MutableStateFlow(currentType())
    val networkType: StateFlow<NetworkType> = _networkType.asStateFlow()

    val isOnline: Boolean
        get() = _networkType.value != NetworkType.OFFLINE

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            val type = caps?.let(::classify) ?: NetworkType.WIFI
            if (type != NetworkType.OFFLINE) {
                activeNetworks[network] = type
            }
            recompute()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val type = classify(capabilities)
            if (type == NetworkType.OFFLINE) {
                activeNetworks.remove(network)
            } else {
                activeNetworks[network] = type
            }
            recompute()
        }

        override fun onLost(network: Network) {
            activeNetworks.remove(network)
            recompute()
        }
    }

    init {
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
    }

    private fun recompute() {
        val values = activeNetworks.values
        _networkType.value = when {
            values.any { it == NetworkType.WIFI } -> NetworkType.WIFI
            values.any { it == NetworkType.CELLULAR } -> NetworkType.CELLULAR
            else -> NetworkType.OFFLINE
        }
    }

    private fun currentType(): NetworkType {
        val active = connectivityManager.activeNetwork ?: return NetworkType.OFFLINE
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return NetworkType.OFFLINE
        return classify(caps)
    }

    private fun classify(caps: NetworkCapabilities): NetworkType {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkType.OFFLINE
        }
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            else -> NetworkType.WIFI
        }
    }
}
