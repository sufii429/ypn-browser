package app.ypn.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ypn.browser.ui.theme.LocalYpnPalette
import app.ypn.browser.vpn.VpnController
import app.ypn.browser.vpn.VpnPhase
import app.ypn.browser.vpn.VpnServer

/**
 * v1.1 VPN quick access, Pawxy-style: the "VPN" chip that sits at the
 * right end of the Home search bar and of the browsing address bar,
 * and the bottom sheet it opens. The sheet carries the tunnel status
 * (the same wording as the VPN screen's status card, via
 * [vpnStatusHeadline] / [vpnStatusDetail]), ONE primary action button
 * mirroring that card's button (quick-connect / disconnect / cancel),
 * the "Worked before" servers, then the full server list — both
 * rendered with the VPN screen's own [ServerRow]. The full VpnScreen
 * remains available from the Home VPN card, unchanged.
 */
@Composable
fun VpnChip(
    vpn: VpnController,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    val background = when (vpn.phase) {
        VpnPhase.ON -> colors.primary
        VpnPhase.CONNECTING -> colors.primaryContainer
        VpnPhase.FAILED -> colors.errorContainer
        VpnPhase.OFF -> palette.surface2
    }
    val foreground = when (vpn.phase) {
        VpnPhase.ON -> palette.onAccent
        VpnPhase.CONNECTING -> colors.primary
        VpnPhase.FAILED -> colors.onErrorContainer
        VpnPhase.OFF -> palette.muted
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (vpn.phase == VpnPhase.CONNECTING) "VPN…" else "VPN",
            color = foreground,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnSheet(
    vpn: VpnController,
    onConnectRequest: (VpnServer) -> Unit,
    onDismissRequest: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState()

    // Same refresh the VPN screen performs when it opens.
    LaunchedEffect(Unit) { vpn.refreshServers() }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.onSurface
    ) {
        VpnSheetContent(vpn = vpn, onConnectRequest = onConnectRequest)
    }
}

@Composable
private fun VpnSheetContent(
    vpn: VpnController,
    onConnectRequest: (VpnServer) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "YPN VPN",
                color = colors.onSurface,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            // The one primary action — the VPN screen status card's
            // button logic (labels, enabled rule, quick-connect flow),
            // drawn as the mockup's filled pill.
            when (vpn.phase) {
                VpnPhase.ON, VpnPhase.CONNECTING -> Button(
                    onClick = { vpn.disconnect() },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = palette.onAccent
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (vpn.phase == VpnPhase.ON) "Disconnect" else "Cancel",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                VpnPhase.OFF, VpnPhase.FAILED -> Button(
                    onClick = {
                        val target = vpn.quickConnectTarget()
                        if (target != null) onConnectRequest(target)
                    },
                    enabled = vpn.servers.isNotEmpty() || vpn.currentServer != null,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = palette.onAccent
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (vpn.phase == VpnPhase.FAILED) "Try again" else "Connect",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text(
            text = vpnStatusHeadline(vpn),
            color = if (vpn.phase == VpnPhase.ON) palette.data else colors.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
        Text(
            text = vpnStatusDetail(vpn),
            color = palette.muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp)
        )
        Text(
            text = "Free · VPN Gate · MS-SSTP",
            color = palette.muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp)
        )

        if (vpn.servers.isEmpty()) {
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
                        "No servers loaded yet. Check your connection, then reopen."
                    },
                    color = palette.muted,
                    fontSize = 12.sp
                )
            }
        } else {
            val worked = vpn.workedServers
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .padding(top = 6.dp)
            ) {
                if (worked.isNotEmpty()) {
                    item(key = "sheet-worked-header") {
                        SheetSectionHeader("WORKED BEFORE")
                    }
                    items(worked, key = { "sheet-worked-" + it.sstpHost }) { server ->
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
                item(key = "sheet-all-header") {
                    SheetSectionHeader("ALL SERVERS")
                }
                items(vpn.servers, key = { "sheet-" + it.sstpHost }) { server ->
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
private fun SheetSectionHeader(text: String) {
    Text(
        text = text,
        color = LocalYpnPalette.current.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.1.sp,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
    )
}
