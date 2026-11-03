package app.ypn.browser.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Holds live handles to the phone's physical Wi-Fi and cellular
 * networks while accelerated downloads run.
 *
 * The cellular handle is the interesting one: while Wi-Fi is the
 * default network, the only way to still send traffic over mobile data
 * is [ConnectivityManager.requestNetwork] with TRANSPORT_CELLULAR —
 * which also powers the cellular radio up if needed. Connections
 * opened through a [Network] handle (Network.openConnection) are bound
 * to that physical network; in particular they do NOT pass through the
 * system VPN, which counts the *underlying* network as a different one.
 * That is why accelerated downloads travel outside the SSTP tunnel —
 * by design, exactly as the v1 plan states.
 *
 * Reference counted by the download controller: handles are requested
 * when the first accelerated download starts and released when the
 * last one stops, so the cellular request doesn't linger.
 */
class NetworkPool(context: Context) {

    private val cm: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    var wifiNetwork: Network? = null
        private set

    @Volatile
    var cellNetwork: Network? = null
        private set

    private var refs = 0
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var cellCallback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun acquire() {
        if (++refs > 1) return
        wifiCallback = request(
            NetworkCapabilities.TRANSPORT_WIFI,
            onAvailable = { wifiNetwork = it },
            onLost = { if (wifiNetwork == it) wifiNetwork = null }
        )
        cellCallback = request(
            NetworkCapabilities.TRANSPORT_CELLULAR,
            onAvailable = { cellNetwork = it },
            onLost = { if (cellNetwork == it) cellNetwork = null }
        )
    }

    @Synchronized
    fun release() {
        if (--refs > 0) return
        refs = 0
        listOfNotNull(wifiCallback, cellCallback).forEach { callback ->
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                // Already unregistered — nothing to do.
            }
        }
        wifiCallback = null
        cellCallback = null
        wifiNetwork = null
        cellNetwork = null
    }

    private fun request(
        transport: Int,
        onAvailable: (Network) -> Unit,
        onLost: (Network) -> Unit
    ): ConnectivityManager.NetworkCallback? {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable(network)
            override fun onLost(network: Network) = onLost(network)
        }
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        return try {
            cm.requestNetwork(networkRequest, callback)
            callback
        } catch (e: Exception) {
            // No such network / radio policy — degradation is handled by
            // the engine (workers fall back to whatever network exists).
            null
        }
    }

    /** The handle a worker with role [kind] should open connections on. */
    fun networkFor(kind: NetKind): Network? = when (kind) {
        NetKind.WIFI -> wifiNetwork
        // The cellular role falls back to Wi-Fi when the cellular
        // request was denied or the radio never came up.
        NetKind.CELL -> cellNetwork ?: wifiNetwork
    }

    /**
     * Which bucket bytes carried by [network] belong to. Compared
     * against the live cellular handle first; otherwise the network's
     * own transports decide — so bytes on the plain default connection
     * are also counted truthfully (Wi-Fi default → WIFI, cellular
     * default → CELL).
     */
    fun kindOf(network: Network?): NetKind {
        val effective = network ?: cm.activeNetwork
        if (effective != null && effective == cellNetwork) return NetKind.CELL
        val caps = effective?.let {
            try {
                cm.getNetworkCapabilities(it)
            } catch (e: Exception) {
                null
            }
        }
        return if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            NetKind.CELL
        } else {
            NetKind.WIFI
        }
    }
}
