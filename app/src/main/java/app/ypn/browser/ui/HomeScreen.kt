package app.ypn.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ypn.browser.download.DownloadController
import app.ypn.browser.download.DownloadStatus
import app.ypn.browser.download.formatBytes
import app.ypn.browser.download.formatMbps
import app.ypn.browser.ui.theme.LocalYpnPalette
import app.ypn.browser.ui.theme.YpnThemeId
import app.ypn.browser.vpn.VpnController
import app.ypn.browser.vpn.VpnPhase
import app.ypn.browser.vpn.VpnServer

/**
 * Home screen — a Compose port of the approved design mockups
 * ("browser-app-design-mockups"). Since Phase 1 the address bar and
 * quick tiles really browse (via [onNavigate]) and the tab count is
 * live; since Phase 2 the VPN card shows the real SSTP tunnel state
 * (via [vpn]) — tapping it opens the VPN screen ([onVpnOpen]), its
 * button connects / disconnects (via [onVpnConnectRequest]). Since
 * Phase 3 the download card mirrors the top active download from
 * [downloads] with its measured per-network data, and collapses to a
 * slim "No active downloads" card otherwise. Theme switching
 * re-skins the whole screen via YpnBrowserTheme.
 */
@Composable
fun HomeScreen(
    themeId: YpnThemeId,
    onThemeChange: (YpnThemeId) -> Unit,
    tabCount: Int,
    onNavigate: (String) -> Unit,
    vpn: VpnController,
    onVpnOpen: () -> Unit,
    onVpnConnectRequest: (VpnServer) -> Unit,
    downloads: DownloadController,
    onDownloadsOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        AppHeader()
        AddressBar(tabCount = tabCount, onNavigate = onNavigate)
        VpnCard(vpn = vpn, onOpen = onVpnOpen, onConnectRequest = onVpnConnectRequest)
        DownloadCard(downloads = downloads, onOpenDownloads = onDownloadsOpen)
        QuickTiles(onNavigate = onNavigate)
        ThemePicker(themeId = themeId, onThemeChange = onThemeChange)
        Text(
            text = "Phase 3 — browsing, the built-in VPN and downloads with dual-network acceleration are live.",
            color = LocalYpnPalette.current.muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp)
        )
    }
}

@Composable
private fun AppHeader() {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildAnnotatedString {
                append("YPN ")
                withStyle(SpanStyle(color = colors.primary)) { append("Browser") }
            },
            color = colors.onBackground,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(palette.surface2)
                .border(1.dp, colors.outline, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = "VPN",
                tint = colors.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AddressBar(tabCount: Int, onNavigate: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(999.dp))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(7.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(color = colors.onSurface, fontSize = 13.sp),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    if (text.isNotBlank()) {
                        onNavigate(text)
                        text = ""
                        focusManager.clearFocus()
                    }
                }
            ),
            decorationBox = { innerField ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = "Search or type a URL",
                            color = palette.muted,
                            fontSize = 13.sp
                        )
                    }
                    innerField()
                }
            }
        )
        Spacer(Modifier.width(7.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .border(1.5.dp, palette.muted, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = tabCount.toString(),
                color = colors.onSurface,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun VpnCard(
    vpn: VpnController,
    onOpen: () -> Unit,
    onConnectRequest: (VpnServer) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    val phase = vpn.phase
    val server = vpn.currentServer

    val dotColor = when (phase) {
        VpnPhase.ON -> palette.data
        VpnPhase.CONNECTING -> colors.primary
        VpnPhase.FAILED -> colors.error
        VpnPhase.OFF -> palette.muted
    }
    val title = when (phase) {
        VpnPhase.ON -> "VPN ON"
        VpnPhase.CONNECTING -> "VPN CONNECTING"
        VpnPhase.FAILED -> "VPN FAILED"
        VpnPhase.OFF -> "VPN OFF"
    }
    val headline = when {
        phase == VpnPhase.OFF -> "Not connected"
        server != null -> server.countryLong.ifBlank { server.sstpHost }
        else -> "VPN Gate"
    }
    val subline = when (phase) {
        VpnPhase.ON -> buildAnnotatedString {
            append("VPN Gate server · ")
            withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)) {
                append("TCP 443")
            }
            vpn.assignedIp?.let { append(" · $it") }
        }
        VpnPhase.CONNECTING -> buildAnnotatedString {
            append(vpn.statusText.ifBlank { "Connecting…" })
        }
        VpnPhase.FAILED -> buildAnnotatedString {
            append(vpn.statusText.ifBlank { "Connection failed — tap to retry" })
        }
        VpnPhase.OFF -> buildAnnotatedString {
            append("VPN Gate server · ")
            withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)) {
                append("TCP 443")
            }
            append(" · MS-SSTP")
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .clickable { onOpen() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    color = colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.weight(1f))
                Text(text = server?.flagEmoji ?: "🌐", fontSize = 16.sp)
            }
            Text(
                text = headline,
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 7.dp)
            )
            Text(
                text = subline,
                color = palette.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            when (phase) {
                VpnPhase.ON, VpnPhase.CONNECTING -> OutlinedButton(
                    onClick = { vpn.disconnect() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, colors.primary)
                ) {
                    Text(
                        text = if (phase == VpnPhase.ON) "Disconnect" else "Cancel",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                VpnPhase.OFF, VpnPhase.FAILED -> OutlinedButton(
                    onClick = {
                        val target = vpn.currentServer ?: vpn.servers.firstOrNull()
                        if (target != null) onConnectRequest(target) else onOpen()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, colors.primary)
                ) {
                    Text(text = "Connect", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    downloads: DownloadController,
    onOpenDownloads: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    val item = downloads.topActive

    if (item == null) {
        // Nothing in flight: a slim card that taps through to Downloads.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                .clickable { onOpenDownloads() },
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "No active downloads",
                    color = colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Downloads",
                    color = palette.muted,
                    fontSize = 11.sp
                )
            }
        }
        return
    }

    // The mockup layout, fed with the top download's measured data.
    val downloading = item.status == DownloadStatus.DOWNLOADING
    val basis = if (item.totalBytes > 0) {
        item.totalBytes.toFloat()
    } else {
        maxOf(item.downloadedBytes, 1L).toFloat()
    }
    val wifiShare = item.wifiBytes / basis
    val cellShare = item.cellBytes / basis
    val restShare = (1f - wifiShare - cellShare).coerceAtLeast(0f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .clickable { onOpenDownloads() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        color = colors.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (item.totalBytes > 0) {
                            "${formatBytes(item.downloadedBytes)} of " +
                                "${formatBytes(item.totalBytes)} · ${(item.percent * 100).toInt()}%"
                        } else {
                            "${formatBytes(item.downloadedBytes)} downloaded"
                        },
                        color = palette.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.primary)
                        .clickable {
                            if (downloading) {
                                downloads.pause(item.id)
                            } else {
                                downloads.resume(item.id)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (downloading) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (downloading) "Pause" else "Resume",
                        tint = palette.onAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Split bar: each network's measured share of the file so
            // far, the rest still to go — the mockups' signature bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 11.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.surface2)
            ) {
                if (wifiShare > 0f) {
                    Box(Modifier.weight(wifiShare).fillMaxHeight().background(palette.wifi))
                }
                if (cellShare > 0f) {
                    Box(Modifier.weight(cellShare).fillMaxHeight().background(palette.data))
                }
                if (restShare > 0f) {
                    Box(Modifier.weight(restShare).fillMaxHeight())
                }
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (item.wifiBytes > 0) {
                    LegendItem(
                        color = palette.wifi,
                        label = "Wi-Fi · ${(wifiShare * 100).toInt()}%"
                    )
                }
                if (item.cellBytes > 0) {
                    LegendItem(
                        color = palette.data,
                        label = "Mobile Data · ${(cellShare * 100).toInt()}%"
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 9.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.surface2)
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        downloading && item.wifiSpeedBps > 0 && item.cellSpeedBps > 0 ->
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = palette.wifi)) {
                                    append("Wi-Fi ${formatMbps(item.wifiSpeedBps)}")
                                }
                                append(" + ")
                                withStyle(SpanStyle(color = palette.data)) {
                                    append("Data ${formatMbps(item.cellSpeedBps)}")
                                }
                                append(" = ")
                                withStyle(
                                    SpanStyle(
                                        color = colors.primary,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                ) {
                                    append(formatMbps(item.speedBps))
                                }
                            }
                        downloading && item.cellSpeedBps > 0 ->
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = palette.data)) {
                                    append("Data ${formatMbps(item.cellSpeedBps)}")
                                }
                            }
                        downloading ->
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = palette.wifi)) {
                                    append("Wi-Fi ${formatMbps(item.wifiSpeedBps)}")
                                }
                            }
                        item.status == DownloadStatus.QUEUED ->
                            buildAnnotatedString { append("Queued") }
                        else -> buildAnnotatedString { append("Paused") }
                    },
                    color = colors.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, color = LocalYpnPalette.current.muted, fontSize = 11.sp)
    }
}

@Composable
private fun QuickTiles(onNavigate: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tiles = listOf(
        Triple("▶", "YouTube", "https://www.youtube.com"),
        Triple("M", "mrjobs.top", "https://mrjobs.top"),
        Triple("K", "Kitaabcha", "https://kitaabcha.blogspot.com"),
        Triple("W", "Wikipedia", "https://www.wikipedia.org")
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        tiles.forEach { (glyph, label, url) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onNavigate(url) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.outline, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = glyph,
                        color = colors.primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    text = label,
                    color = LocalYpnPalette.current.muted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

@Composable
fun ThemePicker(themeId: YpnThemeId, onThemeChange: (YpnThemeId) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Text(
        text = "Theme",
        color = colors.onBackground,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
    ) {
        Column {
            YpnThemeId.entries.forEachIndexed { index, entry ->
                val selected = entry == themeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onThemeChange(entry) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (selected) colors.primary else palette.surface2),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = entry.letter,
                            color = if (selected) palette.onAccent else colors.onSurface,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = entry.label,
                        color = colors.onSurface,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(Modifier.weight(1f))
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = colors.primary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                if (index < YpnThemeId.entries.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.outline)
                    )
                }
            }
        }
    }
}
