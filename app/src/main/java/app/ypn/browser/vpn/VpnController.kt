package app.ypn.browser.vpn

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.service.ACTION_VPN_CONNECT
import kittoku.osc.service.ACTION_VPN_DISCONNECT
import kittoku.osc.service.SstpVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** One VPN Gate relay server: one row of the public API CSV. */
data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val pingMs: Long?,
    val speedBps: Long,
    val countryLong: String,
    val countryShort: String,
    val sessions: Int
) {
    /** MS-SSTP endpoint of this relay (VPN Gate's DDNS name), on TCP 443. */
    val sstpHost: String get() = "$hostName.opengw.net"

    val flagEmoji: String get() = flagEmojiOf(countryShort)
}

/** Regional-indicator flag for an ISO country code ("JP" -> 🇯🇵). */
fun flagEmojiOf(countryShort: String): String {
    val code = countryShort.trim().uppercase()
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🌐"
    return String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) +
        String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
}

enum class VpnPhase { OFF, CONNECTING, ON, FAILED }

/**
 * Phase 2 VPN: drives the embedded Open SSTP Client core (package
 * `kittoku.osc`, MIT) exactly the way OSC's own UI does — the live
 * profile is written into the default SharedPreferences with OSC's own
 * accessors, then [SstpVpnService] is started with its connect /
 * disconnect intent actions.
 *
 * Connection state is observed through OSC's real status hooks, also in
 * the default preferences: `ROOT_STATE` (service alive) and
 * `HOME_STATUS` (the summary text OSC's NetworkObserver publishes once
 * the tunnel is up; empty while negotiating). There is no other public
 * state channel in OSC — its notifications are fire-and-forget.
 *
 * Failover is layered on top: OSC itself retries the same server
 * (RECONNECTION_* settings below). If the service then dies on its own
 * (ROOT_STATE flips false without the user asking), this controller
 * waits a beat and starts the next server from the sorted list, until
 * the attempt cap is hit.
 */
class VpnController(context: Context) {

    private val appContext = context.applicationContext
    private val oscPrefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(appContext)
    private val storePrefs: SharedPreferences =
        appContext.getSharedPreferences("ypn-browser", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var phase by mutableStateOf(VpnPhase.OFF)
        private set
    var currentServer by mutableStateOf<VpnServer?>(null)
        private set
    var statusText by mutableStateOf("")
        private set
    var assignedIp by mutableStateOf<String?>(null)
        private set
    var servers by mutableStateOf<List<VpnServer>>(emptyList())
        private set
    var refreshing by mutableStateOf(false)
        private set

    private var userRequestedStop = true
    private var failoverArmed = false
    private var wasConnected = false
    private var queue: List<VpnServer> = emptyList()
    private var attemptIndex = 0
    private var pendingTarget: VpnServer? = null
    private var watchdogJob: Job? = null
    private var failoverJob: Job? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            OscPrefKey.ROOT_STATE.name ->
                if (getBooleanPrefValue(OscPrefKey.ROOT_STATE, oscPrefs)) {
                    refreshDerivedState()
                } else {
                    onServiceStopped()
                }
            OscPrefKey.HOME_STATUS.name -> refreshDerivedState()
        }
    }

    init {
        servers = loadCachedServers()
        // If a tunnel is already up (the service outlives this UI), show
        // its server. Failover stays disarmed for such inherited
        // sessions: if it drops, we just go OFF.
        val host = getStringPrefValue(OscPrefKey.HOME_HOSTNAME, oscPrefs)
        if (host.isNotEmpty()) {
            currentServer = servers.firstOrNull { it.sstpHost == host }
                ?: displayServerFor(host)
        }
        oscPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        refreshDerivedState()
    }

    /**
     * Begin connecting to [server]. Returns the Android VPN-consent
     * intent when the user still has to approve it (the caller launches
     * it and calls [beginConnect] on success); when consent is already
     * granted, the connection starts immediately and this returns null.
     */
    fun requestConnect(server: VpnServer): Intent? {
        pendingTarget = server
        failoverJob?.cancel()
        val prepareIntent = VpnService.prepare(appContext)
        if (prepareIntent == null) {
            beginConnect()
        }
        return prepareIntent
    }

    /** Consent is in place: build the attempt queue and dial. */
    fun beginConnect() {
        val target = pendingTarget ?: return
        pendingTarget = null
        userRequestedStop = false
        failoverArmed = true
        wasConnected = false
        queue = listOf(target) + servers.filter { it.sstpHost != target.sstpHost }
        attemptIndex = 0
        startAttempt()
    }

    /** User pressed Disconnect / Cancel. Never triggers failover. */
    fun disconnect() {
        userRequestedStop = true
        failoverArmed = false
        pendingTarget = null
        watchdogJob?.cancel()
        failoverJob?.cancel()
        appContext.startService(
            Intent(appContext, SstpVpnService::class.java).setAction(ACTION_VPN_DISCONNECT)
        )
        // If the service was not running, no ROOT_STATE change will
        // arrive to settle the UI — settle right away.
        if (!getBooleanPrefValue(OscPrefKey.ROOT_STATE, oscPrefs)) {
            phase = VpnPhase.OFF
            statusText = ""
            assignedIp = null
            wasConnected = false
        }
    }

    private fun startAttempt() {
        val server = queue.getOrNull(attemptIndex)
        if (server == null) {
            phase = VpnPhase.FAILED
            statusText =
                "No VPN Gate server could be reached. Check your connection and try again."
            failoverArmed = false
            return
        }
        currentServer = server
        storePrefs.edit().putString(KEY_LAST_HOST, server.sstpHost).apply()
        writeProfile(server)
        phase = VpnPhase.CONNECTING
        statusText = if (attemptIndex == 0) {
            "Connecting to ${server.sstpHost}…"
        } else {
            "Trying ${server.sstpHost}…"
        }
        try {
            appContext.startForegroundService(
                Intent(appContext, SstpVpnService::class.java).setAction(ACTION_VPN_CONNECT)
            )
        } catch (e: Exception) {
            // Android 12+ refuses foreground-service starts while the app
            // is backgrounded. Surface it instead of hanging forever.
            phase = VpnPhase.FAILED
            statusText =
                "Android refused to start the VPN service. Open YPN Browser and tap Connect again."
            failoverArmed = false
            return
        }
        armWatchdog()
    }

    /** Write the live OSC profile for [server] (VPN Gate: vpn / vpn). */
    private fun writeProfile(server: VpnServer) {
        setStringPrefValue(server.sstpHost, OscPrefKey.HOME_HOSTNAME, oscPrefs)
        setStringPrefValue("vpn", OscPrefKey.HOME_USERNAME, oscPrefs)
        setStringPrefValue("vpn", OscPrefKey.HOME_PASSWORD, oscPrefs)
        setIntPrefValue(SSTP_PORT, OscPrefKey.SSL_PORT, oscPrefs)
        // OSC's own retry layer: same server again before we fail over.
        setBooleanPrefValue(true, OscPrefKey.RECONNECTION_ENABLED, oscPrefs)
        setIntPrefValue(2, OscPrefKey.RECONNECTION_COUNT, oscPrefs)
        setIntPrefValue(5, OscPrefKey.RECONNECTION_INTERVAL, oscPrefs)
        // Some volunteer servers push no DNS at all — the tunnel comes up
        // but every hostname fails with ERR_NAME_NOT_RESOLVED (Mazeen's
        // 2026-11-03 test: first Japan server and the Romania server).
        // Pin our own resolver; queries still travel inside the tunnel.
        setBooleanPrefValue(true, OscPrefKey.DNS_DO_USE_CUSTOM_SERVER, oscPrefs)
        setStringPrefValue("1.1.1.1", OscPrefKey.DNS_CUSTOM_ADDRESS, oscPrefs)
    }

    /**
     * OSC's negotiation has no overall deadline visible to us; if an
     * attempt is still not up after the timeout, force the service down
     * so the ROOT_STATE flip advances the failover queue.
     */
    private fun armWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!userRequestedStop && failoverArmed && phase == VpnPhase.CONNECTING) {
                appContext.startService(
                    Intent(appContext, SstpVpnService::class.java)
                        .setAction(ACTION_VPN_DISCONNECT)
                )
            }
        }
    }

    /** Derive CONNECTING / ON from OSC's ROOT_STATE + HOME_STATUS. */
    private fun refreshDerivedState() {
        if (!getBooleanPrefValue(OscPrefKey.ROOT_STATE, oscPrefs)) return
        val summary = getStringPrefValue(OscPrefKey.HOME_STATUS, oscPrefs)
        if (summary.isNotBlank()) {
            wasConnected = true
            assignedIp = parseAssignedIp(summary)
            phase = VpnPhase.ON
            statusText = assignedIp?.let { "Tunnel up · your VPN address is $it" }
                ?: "Tunnel up"
            watchdogJob?.cancel()
        } else if (failoverArmed || phase == VpnPhase.ON || phase == VpnPhase.CONNECTING) {
            phase = VpnPhase.CONNECTING
            if (wasConnected) {
                statusText = "Connection interrupted — reconnecting…"
            }
        }
    }

    /** ROOT_STATE flipped to false: the service is gone. */
    private fun onServiceStopped() {
        watchdogJob?.cancel()
        assignedIp = null
        if (userRequestedStop || !failoverArmed) {
            phase = VpnPhase.OFF
            statusText = ""
            wasConnected = false
            return
        }
        // The tunnel died on its own: walk down the server list.
        attemptIndex++
        if (attemptIndex < queue.size && attemptIndex < MAX_ATTEMPTS) {
            phase = VpnPhase.CONNECTING
            statusText = "Connection lost. Trying another server…"
            failoverJob?.cancel()
            failoverJob = scope.launch {
                delay(FAILOVER_DELAY_MS)
                if (!userRequestedStop && failoverArmed) startAttempt()
            }
        } else {
            phase = VpnPhase.FAILED
            statusText =
                "No VPN Gate server could be reached. Check your connection and try again."
            failoverArmed = false
            wasConnected = false
        }
    }

    /** Pull the "[Assigned IP Address]" entry out of OSC's status text. */
    private fun parseAssignedIp(summary: String): String? {
        val lines = summary.lines()
        val idx = lines.indexOfFirst { it.trim() == "[Assigned IP Address]" }
        if (idx < 0) return null
        return lines.drop(idx + 1).firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun displayServerFor(sstpHost: String): VpnServer = VpnServer(
        hostName = sstpHost.removeSuffix(".opengw.net"),
        ip = "", score = 0, pingMs = null, speedBps = 0,
        countryLong = "", countryShort = "", sessions = 0
    )

    // ---- VPN Gate server list -------------------------------------------

    /** Refresh the server list from the VPN Gate public API. */
    fun refreshServers() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val fetched = withContext(Dispatchers.IO) { fetchServerList() }
            if (fetched != null) {
                servers = fetched
                cacheServers(fetched)
                // Keep the current server pointing at its fresh row.
                currentServer?.let { current ->
                    servers.firstOrNull { it.sstpHost == current.sstpHost }
                        ?.let { currentServer = it }
                }
            }
            refreshing = false
        }
    }

    private fun fetchServerList(): List<VpnServer>? {
        for (url in listOf(API_URL_HTTPS, API_URL_HTTP)) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "YPN Browser")
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    return parseCsv(reader.readText())
                }
            } catch (e: Exception) {
                // Fall through to the plain-http mirror.
            }
        }
        return null
    }

    private fun parseCsv(body: String): List<VpnServer> {
        val parsed = mutableListOf<VpnServer>()
        for (line in body.lineSequence()) {
            if (line.isBlank() || line.startsWith("*") || line.startsWith("#")) continue
            // The columns read here (HostName..NumVpnSessions) all sit
            // before the free-text Operator/Message columns, so a plain
            // comma split is safe for these indices. The CSV has no
            // MS-SSTP flag column: every relay's SSTP name is derived
            // as "<HostName>.opengw.net" on TCP 443 instead.
            val c = line.split(",")
            if (c.size < 8) continue
            val host = c[0].trim()
            if (host.isEmpty()) continue
            parsed += VpnServer(
                hostName = host,
                ip = c[1].trim(),
                score = c[2].trim().toLongOrNull() ?: 0L,
                pingMs = c[3].trim().toLongOrNull(),
                speedBps = c[4].trim().toLongOrNull() ?: 0L,
                countryLong = c[5].trim(),
                countryShort = c[6].trim(),
                sessions = c[7].trim().toIntOrNull() ?: 0
            )
        }
        return parsed.sortedByDescending { it.score }
    }

    private fun cacheServers(list: List<VpnServer>) {
        val raw = list.joinToString("\n") { s ->
            listOf(
                s.hostName, s.ip, s.score.toString(), s.pingMs?.toString() ?: "",
                s.speedBps.toString(), s.countryLong, s.countryShort,
                s.sessions.toString()
            ).joinToString("\t")
        }
        storePrefs.edit().putString(KEY_SERVERS_CACHE, raw).apply()
    }

    private fun loadCachedServers(): List<VpnServer> {
        val raw = storePrefs.getString(KEY_SERVERS_CACHE, null) ?: return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val c = line.split("\t")
            if (c.size < 8 || c[0].isEmpty()) {
                null
            } else {
                VpnServer(
                    hostName = c[0],
                    ip = c[1],
                    score = c[2].toLongOrNull() ?: 0L,
                    pingMs = c[3].toLongOrNull(),
                    speedBps = c[4].toLongOrNull() ?: 0L,
                    countryLong = c[5],
                    countryShort = c[6],
                    sessions = c[7].toIntOrNull() ?: 0
                )
            }
        }.toList()
    }

    private companion object {
        const val API_URL_HTTPS = "https://www.vpngate.net/api/iphone/"
        const val API_URL_HTTP = "http://www.vpngate.net/api/iphone/"
        const val SSTP_PORT = 443
        const val MAX_ATTEMPTS = 6
        const val CONNECT_TIMEOUT_MS = 90_000L
        const val FAILOVER_DELAY_MS = 2_000L
        const val KEY_SERVERS_CACHE = "vpn_gate_servers"
        const val KEY_LAST_HOST = "vpn_last_host"
    }
}
