package app.ypn.browser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
import app.ypn.browser.data.SpeedDialStore
import app.ypn.browser.data.SpeedDialTile
import app.ypn.browser.download.DownloadController
import app.ypn.browser.download.DownloadStatus
import app.ypn.browser.download.formatBytes
import app.ypn.browser.download.formatMbps
import app.ypn.browser.ui.theme.LocalYpnPalette
import app.ypn.browser.vpn.VpnController
import app.ypn.browser.vpn.VpnPhase
import app.ypn.browser.vpn.VpnServer

/**
 * Home screen — a Compose port of the approved design mockups
 * ("browser-app-design-mockups"). Since Phase 1 the address bar and
 * quick tiles really browse (via [onNavigate]) and the tab count is
 * live; since Phase 2 the VPN card shows the real SSTP tunnel state
 * (via [vpn]) — tapping it opens the VPN screen ([onVpnOpen]), its
 * button connects / disconnects (via [onVpnConnectRequest]); since
 * Phase 3 the download card mirrors the top active download from
 * [downloads] with its measured per-network data. Phase 4 slimmed
 * this screen to the approved map: address bar, the compact VPN
 * card, the download card ONLY while a download is active, and the
 * quick tiles — the theme picker lives in the Menu now. v1.1 added
 * the Pawxy-style VPN chip at the search bar's right end (it opens
 * the VPN bottom sheet, see VpnSheet.kt) and restyled the quick
 * tiles as bigger Speed Dial tiles. v1.2 ("Mazedaar") paints a soft
 * two-colour gradient in the active theme's network colours behind
 * this screen only (the VPN card, download card and tiles turn
 * slightly translucent so it breathes through), and the fixed quick
 * tiles became Mazeen's own editable, persisted Speed Dial — see
 * [SpeedDial] and SpeedDialStore.
 */
@Composable
fun HomeScreen(
    tabCount: Int,
    onNavigate: (String) -> Unit,
    vpn: VpnController,
    onVpnOpen: () -> Unit,
    onVpnConnectRequest: (VpnServer) -> Unit,
    onVpnSheetOpen: () -> Unit,
    downloads: DownloadController,
    onDownloadsOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    // v1.2: Home — and only Home — sits on a living background: the
    // theme background, washed with the palette's Wi-Fi colour from
    // the top-right and its data colour from the bottom-left, both
    // faded out by mid-screen. The cards and tiles on this screen
    // are slightly translucent so the glow breathes through them.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .drawBehind {
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to palette.wifi.copy(alpha = 0.18f),
                            0.55f to palette.wifi.copy(alpha = 0f),
                            1f to palette.wifi.copy(alpha = 0f)
                        ),
                        start = Offset(size.width, 0f),
                        end = Offset(0f, size.height)
                    )
                )
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to palette.data.copy(alpha = 0.12f),
                            0.55f to palette.data.copy(alpha = 0f),
                            1f to palette.data.copy(alpha = 0f)
                        ),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, 0f)
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            AppHeader()
            AddressBar(
                tabCount = tabCount,
                onNavigate = onNavigate,
                vpn = vpn,
                onVpnSheetOpen = onVpnSheetOpen
            )
            VpnCard(vpn = vpn, onOpen = onVpnOpen, onConnectRequest = onVpnConnectRequest)
            DownloadCard(downloads = downloads, onOpenDownloads = onDownloadsOpen)
            // v1.4 (redesign): the tiles get their name on screen —
            // an unlabelled grid of letters is the one thing a
            // first-time user cannot be expected to decode — plus
            // the one gesture that is not visible anywhere (hold to
            // edit) is written down next to it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Speed Dial",
                    color = colors.onBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Hold a tile to edit it",
                    color = palette.muted,
                    fontSize = 11.sp
                )
            }
            SpeedDial(onNavigate = onNavigate)
            Text(
                text = "YPN · Your Personal Network — two networks, one fast download.",
                color = palette.muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp)
            )
        }
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
private fun AddressBar(
    tabCount: Int,
    onNavigate: (String) -> Unit,
    vpn: VpnController,
    onVpnSheetOpen: () -> Unit
) {
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
        Spacer(Modifier.width(7.dp))
        VpnChip(vpn = vpn, onClick = onVpnSheetOpen)
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
    // Phase 4 compact card: one slim server/status line under the
    // header row — the connected server when ON, the live status text
    // while connecting or after a failure, and (since v1.4) a plain
    // explanation of the card when OFF.
    val serverLine: String? = when (phase) {
        VpnPhase.ON -> server?.let { s ->
            buildString {
                append(s.countryLong.ifBlank { s.sstpHost })
                append(" · TCP 443")
                vpn.assignedIp?.let { append(" · $it") }
            }
        }
        VpnPhase.CONNECTING -> vpn.statusText.ifBlank { "Connecting…" }
        VpnPhase.FAILED -> vpn.statusText.ifBlank { "Connection failed — tap Connect to retry" }
        // v1.4 (redesign): a first-time user should not have to
        // guess what this card is for — OFF now carries the same
        // one-line explanation the other phases get.
        VpnPhase.OFF -> "Free VPN Gate servers · tap Connect"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .clickable { onOpen() },
        shape = RoundedCornerShape(18.dp),
        // v1.2 glass: slightly translucent so Home's gradient
        // breathes through the card.
        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.85f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
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
                if (phase == VpnPhase.ON) {
                    Text(text = server?.flagEmoji ?: "🌐", fontSize = 15.sp)
                }
            }
            if (serverLine != null) {
                Text(
                    text = serverLine,
                    color = palette.muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
            when (phase) {
                VpnPhase.ON, VpnPhase.CONNECTING -> OutlinedButton(
                    onClick = { vpn.disconnect() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 9.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, colors.primary),
                    contentPadding = PaddingValues(vertical = 5.dp)
                ) {
                    Text(
                        text = if (phase == VpnPhase.ON) "Disconnect" else "Cancel",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                VpnPhase.OFF, VpnPhase.FAILED -> OutlinedButton(
                    onClick = {
                        // Quick Connect prefers a remembered working
                        // server, then the best score (Phase 4).
                        val target = vpn.quickConnectTarget()
                        if (target != null) onConnectRequest(target) else onOpen()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 9.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, colors.primary),
                    contentPadding = PaddingValues(vertical = 5.dp)
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
    // Phase 4 map: with nothing in flight there is NO download card on
    // Home at all — Downloads is one tap away in the bottom navigation.
    val item = downloads.topActive ?: return

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
        // v1.2 glass: slightly translucent so Home's gradient
        // breathes through the card.
        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.85f)),
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

/**
 * The Home Speed Dial (v1.2): Mazeen's own tiles, no longer a fixed
 * list. The list lives in [SpeedDialStore] on the phone (seeded with
 * [SpeedDialStore.SEED_TILES] on first run, saved on every change),
 * drawn in rows of four with exactly the old quick tiles' look — an
 * 84dp translucent surface box, the glyph at 24sp, the name beneath —
 * plus a dashed "+ Add" cell after the last tile. Tapping a tile
 * browses; holding it opens an Open / Edit / Remove menu; the editor
 * is a bottom sheet in the VpnSheet pattern.
 */
@Composable
private fun SpeedDial(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { SpeedDialStore(context.applicationContext) }
    var tiles by remember {
        mutableStateOf(
            // First run: seed with the original four and save them,
            // so removing tiles later never brings them back.
            store.loadTiles() ?: SpeedDialStore.SEED_TILES.also { store.saveTiles(it) }
        )
    }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editorOpen by remember { mutableStateOf(false) }

    fun persist(next: List<SpeedDialTile>) {
        tiles = next
        store.saveTiles(next)
    }

    // Cells are tile indices; -1 is the "+ Add" cell that always
    // follows the last tile.
    val cells = tiles.indices.toList() + listOf(-1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 14.dp)
    ) {
        cells.chunked(4).forEachIndexed { rowIndex, rowCells ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (rowIndex > 0) Modifier.padding(top = 12.dp) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                rowCells.forEach { cellIndex ->
                    if (cellIndex < 0) {
                        SpeedDialAddCell(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                editingIndex = null
                                editorOpen = true
                            }
                        )
                    } else {
                        SpeedDialCell(
                            tile = tiles[cellIndex],
                            menuExpanded = menuIndex == cellIndex,
                            onClick = { onNavigate(tiles[cellIndex].url) },
                            onLongClick = { menuIndex = cellIndex },
                            onMenuDismiss = { menuIndex = null },
                            onOpen = {
                                menuIndex = null
                                onNavigate(tiles[cellIndex].url)
                            },
                            onEdit = {
                                menuIndex = null
                                editingIndex = cellIndex
                                editorOpen = true
                            },
                            onRemove = {
                                menuIndex = null
                                persist(tiles.filterIndexed { i, _ -> i != cellIndex })
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Pad a short last row so its cells keep the same
                // width as the full rows above them.
                repeat(4 - rowCells.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    if (editorOpen) {
        SpeedDialEditorSheet(
            initial = editingIndex?.let { tiles.getOrNull(it) },
            onDismiss = { editorOpen = false },
            onSave = { saved ->
                val at = editingIndex
                persist(
                    if (at == null || at !in tiles.indices) {
                        tiles + saved
                    } else {
                        tiles.mapIndexed { i, tile -> if (i == at) saved else tile }
                    }
                )
                editorOpen = false
            }
        )
    }
}

/** One Speed Dial tile: the old quick-tile look, plus its hold menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedDialCell(
    tile: SpeedDialTile,
    menuExpanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuDismiss: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.85f))
                    .border(1.dp, colors.outline, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tile.glyph.ifBlank {
                        tile.name.firstOrNull()?.uppercase() ?: "?"
                    },
                    color = speedDialGlyphColor(tile, colors.primary),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Text(
                text = tile.name,
                color = palette.muted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
        // v1.3: the menu wears the theme — until now it fell back to
        // the stock Material surface (a dark plum on every theme).
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = onMenuDismiss,
            containerColor = colors.surface
        ) {
            DropdownMenuItem(
                text = { Text("Open", color = colors.onSurface) },
                onClick = onOpen
            )
            DropdownMenuItem(
                text = { Text("Edit", color = colors.onSurface) },
                onClick = onEdit
            )
            DropdownMenuItem(
                text = { Text("Remove", color = colors.onSurface) },
                onClick = onRemove
            )
        }
    }
}

/** The dashed "+ Add" cell that follows the last tile (v1.2 mockup). */
@Composable
private fun SpeedDialAddCell(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalYpnPalette.current
    val dashColor = palette.muted.copy(alpha = 0.65f)
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .drawBehind {
                    // Compose has no dashed border; draw the rounded
                    // rect with a dash path effect, inset by half the
                    // stroke so it is not clipped by the box edge.
                    val strokeWidth = 1.5.dp.toPx()
                    val inset = strokeWidth / 2f
                    drawRoundRect(
                        color = dashColor,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                        style = Stroke(
                            width = strokeWidth,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(10.dp.toPx(), 7.dp.toPx())
                            )
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                color = palette.muted,
                fontSize = 26.sp,
                fontWeight = FontWeight.Light
            )
        }
        Text(
            text = "Add",
            color = palette.muted,
            fontSize = 10.5.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

/**
 * The tile editor sheet (v1.2), hosted like VpnSheet: a name field,
 * an address field, a live preview of the tile's letter disc, and
 * Save. A scheme-less address gets https:// in front on save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedDialEditorSheet(
    initial: SpeedDialTile?,
    onDismiss: () -> Unit,
    onSave: (SpeedDialTile) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    val sheetState = rememberModalBottomSheetState()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var address by remember { mutableStateOf(initial?.url ?: "") }

    val trimmedName = name.trim()
    // The colour the saved tile will wear: the theme accent for the
    // seeded tiles (colorIndex < 0), else the colour hashed from the
    // name being typed — the preview below shows it live.
    val previewColorIndex = when {
        initial != null && initial.colorIndex < 0 -> -1
        trimmedName.isEmpty() -> 0
        else -> speedDialColorIndex(trimmedName)
    }
    val previewColor = if (previewColorIndex < 0) {
        colors.primary
    } else {
        SPEED_DIAL_COLORS[previewColorIndex]
    }
    val previewGlyph = when {
        initial?.glyph == "▶" -> "▶"
        trimmedName.isNotEmpty() -> trimmedName.first().uppercase()
        else -> "?"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp)
        ) {
            Text(
                text = if (initial == null) "Add to Speed Dial" else "Edit tile",
                color = colors.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
            Row(
                modifier = Modifier.padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(previewColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = previewGlyph,
                        color = if (previewColorIndex < 0) {
                            palette.onAccent
                        } else {
                            Color(0xFF14212E)
                        },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "The tile takes the first letter of the name, " +
                        "in a colour of its own.",
                    color = palette.muted,
                    fontSize = 12.sp
                )
            }
            Button(
                onClick = {
                    onSave(
                        SpeedDialTile(
                            name = trimmedName,
                            url = normalizeSpeedDialUrl(address),
                            glyph = if (initial?.glyph == "▶") {
                                "▶"
                            } else {
                                trimmedName.firstOrNull()?.uppercase() ?: ""
                            },
                            colorIndex = if (initial != null && initial.colorIndex < 0) {
                                -1
                            } else {
                                speedDialColorIndex(trimmedName)
                            }
                        )
                    )
                },
                enabled = trimmedName.isNotEmpty() && address.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = palette.onAccent
                )
            ) {
                Text(text = "Save tile", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Glyph colours for Mazeen's own tiles — the mockups' pastel set. */
private val SPEED_DIAL_COLORS = listOf(
    Color(0xFFFBBF24), // amber
    Color(0xFFF87171), // coral
    Color(0xFF34D399), // green
    Color(0xFF38BDF8), // sky
    Color(0xFFA78BFA), // violet
    Color(0xFFF472B6)  // pink
)

/** A tile's colour slot, hashed deterministically from its name. */
private fun speedDialColorIndex(name: String): Int {
    val size = SPEED_DIAL_COLORS.size
    val hash = name.trim().lowercase().hashCode()
    return ((hash % size) + size) % size
}

/** The colour a stored tile's glyph is drawn in. */
private fun speedDialGlyphColor(tile: SpeedDialTile, accent: Color): Color {
    if (tile.colorIndex < 0) return accent
    val size = SPEED_DIAL_COLORS.size
    return SPEED_DIAL_COLORS[((tile.colorIndex % size) + size) % size]
}

/**
 * Address normalization for the tile editor: a full URL (anything
 * with a scheme) passes through untouched; anything else gets
 * https:// in front — dotted or not, it's his browser.
 */
private fun normalizeSpeedDialUrl(raw: String): String {
    val input = raw.trim()
    return if (input.contains("://")) input else "https://$input"
}
