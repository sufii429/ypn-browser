package app.ypn.browser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ypn.browser.ui.theme.LocalYpnPalette
import app.ypn.browser.vpn.VpnController
import app.ypn.browser.vpn.VpnPhase
import app.ypn.browser.vpn.VpnServer
import java.util.Locale

/**
 * Phase 2 VPN screen: live status of the embedded SSTP tunnel plus the
 * VPN Gate relay list (best first, by VPN Gate score). Tapping a server
 * connects to it via [onConnectRequest] (the shell owns the Android
 * VPN-consent launcher). Since Phase 4, servers that have connected
 * successfully before are listed first under "Worked before" and carry
 * a "✓ worked" marker in the full list.
 */
@Composable
fun VpnScreen(
    vpn: VpnController,
    onConnectRequest: (VpnServer) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current

    LaunchedEffect(Unit) { vpn.refreshServers() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.onBackground
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "VPN Gate",
                    color = colors.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Free MS-SSTP servers · TCP 443",
                    color = palette.muted,
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = { vpn.refreshServers() }) {
                if (vpn.refreshing) {
                    CircularProgressIndicator(
                        color = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh server list",
                        tint = colors.primary
                    )
                }
            }
        }

        VpnStatusCard(vpn = vpn, onConnectRequest = onConnectRequest)

        Text(
            text = "Volunteer-run servers; the vpn / vpn login is built in. " +
                "VPN Gate's stated logging policy is about two weeks — fine " +
                "for casual browsing and unblocking, not for banking or " +
                "other sensitive accounts.",
            color = palette.muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
        )

        if (vpn.servers.isEmpty()) {
            Text(
                text = "Servers · best first",
                color = colors.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (vpn.refreshing) {
                        "Loading servers…"
                    } else {
                        "No servers loaded yet. Check your connection, then tap refresh."
                    },
                    color = palette.muted,
                    fontSize = 12.sp
                )
            }
        } else {
            val worked = vpn.workedServers
            LazyColumn(Modifier.fillMaxSize()) {
                if (worked.isNotEmpty()) {
                    item(key = "worked-header") {
                        ServerSectionHeader("Worked before")
                    }
                    items(worked, key = { "worked-" + it.sstpHost }) { server ->
                        ServerRow(
                            server = server,
                            workedHere = true,
                            connectedHere = vpn.phase == VpnPhase.ON &&
                                vpn.currentServer?.sstpHost == server.sstpHost,
                            onClick = { onConnectRequest(server) }
                        )
                        ServerDivider()
                    }
                }
                item(key = "all-header") {
                    ServerSectionHeader("Servers · best first")
                }
                items(vpn.servers, key = { it.sstpHost }) { server ->
                    ServerRow(
                        server = server,
                        workedHere = server.sstpHost in vpn.workedHosts,
                        connectedHere = vpn.phase == VpnPhase.ON &&
                            vpn.currentServer?.sstpHost == server.sstpHost,
                        onClick = { onConnectRequest(server) }
                    )
                    ServerDivider()
                }
            }
        }
    }
}

@Composable
private fun ServerSectionHeader(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
fun ServerDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

/**
 * The status wording for the tunnel, shared by the VPN screen's
 * status card and the v1.1 VPN bottom sheet so both always agree.
 */
fun vpnStatusHeadline(vpn: VpnController): String = when (vpn.phase) {
    VpnPhase.ON -> "Connected"
    VpnPhase.CONNECTING -> "Connecting…"
    VpnPhase.FAILED -> "Connection failed"
    VpnPhase.OFF -> "Not connected"
}

fun vpnStatusDetail(vpn: VpnController): String = when (vpn.phase) {
    VpnPhase.ON -> listOfNotNull(
        vpn.currentServer?.let { "${it.flagEmoji} ${it.sstpHost}" },
        vpn.statusText.ifBlank { null }
    ).joinToString(" · ")
    VpnPhase.CONNECTING -> vpn.statusText.ifBlank { "Negotiating the SSTP tunnel…" }
    VpnPhase.FAILED -> vpn.statusText
        .ifBlank { "No server could be reached." }
    VpnPhase.OFF -> "Tap Connect for the best server, or pick one from the list."
}

@Composable
private fun VpnStatusCard(
    vpn: VpnController,
    onConnectRequest: (VpnServer) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current

    val headline = vpnStatusHeadline(vpn)
    val detail = vpnStatusDetail(vpn)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = headline,
                color = if (vpn.phase == VpnPhase.ON) palette.data else colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = detail,
                color = palette.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
            when (vpn.phase) {
                VpnPhase.ON, VpnPhase.CONNECTING -> OutlinedButton(
                    onClick = { vpn.disconnect() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
                    border = BorderStroke(1.5.dp, colors.primary)
                ) {
                    Text(
                        text = if (vpn.phase == VpnPhase.ON) "Disconnect" else "Cancel",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                VpnPhase.OFF, VpnPhase.FAILED -> OutlinedButton(
                    onClick = {
                        val target = vpn.quickConnectTarget()
                        if (target != null) onConnectRequest(target)
                    },
                    enabled = vpn.servers.isNotEmpty() || vpn.currentServer != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
                    border = BorderStroke(1.5.dp, colors.primary)
                ) {
                    Text(
                        text = if (vpn.phase == VpnPhase.FAILED) "Try again" else "Connect",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ServerRow(
    server: VpnServer,
    workedHere: Boolean,
    connectedHere: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = server.flagEmoji, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = server.countryLong.ifBlank { server.sstpHost },
                color = colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = server.sstpHost,
                color = palette.muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${formatPing(server.pingMs)} · ${formatSpeed(server.speedBps)}",
                color = colors.onSurface,
                fontSize = 11.sp
            )
            Text(
                text = "${server.sessions} sessions",
                color = palette.muted,
                fontSize = 10.sp
            )
            if (workedHere) {
                Text(
                    text = "✓ worked",
                    color = palette.data,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (connectedHere) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "ON",
                color = palette.data,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

private fun formatPing(pingMs: Long?): String =
    pingMs?.let { "$it ms" } ?: "—"

private fun formatSpeed(speedBps: Long): String {
    if (speedBps <= 0) return "—"
    val mbps = speedBps / 1_000_000.0
    return if (mbps >= 100) {
        "${mbps.toInt()} Mbps"
    } else {
        String.format(Locale.US, "%.1f Mbps", mbps)
    }
}
