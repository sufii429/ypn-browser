package app.ypn.browser.ui

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ypn.browser.download.DownloadController
import app.ypn.browser.download.DownloadItem
import app.ypn.browser.download.DownloadStatus
import app.ypn.browser.download.formatBytes
import app.ypn.browser.download.formatSpeed
import app.ypn.browser.ui.theme.LocalYpnPalette

/**
 * Phase 3 Downloads screen: paste-a-URL box on top, then every
 * download (active first) with live progress, the measured Wi-Fi /
 * mobile-data split, and Pause / Resume / Cancel / Open / Delete.
 * Since v1.4 (redesign) an All / Active / Completed chip row filters
 * the list — presentation only; the store and engine are untouched.
 */
@Composable
fun DownloadsScreen(
    downloads: DownloadController,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val sorted = downloads.items.sortedWith(
        compareBy<DownloadItem> { statusRank(it.status) }.thenByDescending { it.createdAt }
    )
    // The one rename dialog, keyed by download id so only one shows.
    var renameItemId by remember { mutableStateOf<String?>(null) }
    // v1.4: the chip filter. "Active" is everything not finished —
    // downloading, waiting, paused and failed all still need the
    // user or the network, which is what the word promises.
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    val visible = sorted.filter { item ->
        when (filter) {
            DownloadFilter.ALL -> true
            DownloadFilter.ACTIVE -> item.status != DownloadStatus.COMPLETED
            DownloadFilter.COMPLETED -> item.status == DownloadStatus.COMPLETED
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 12.dp)
    ) {
        Text(
            text = "Downloads",
            color = colors.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
        )
        AddUrlRow(downloads = downloads)
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DownloadFilter.entries.forEach { entry ->
                FilterChip(
                    selected = filter == entry,
                    onClick = { filter = entry },
                    label = { Text(entry.label) }
                )
            }
        }
        if (downloads.accelerationEnabled && downloads.hasActiveDownloads()) {
            PathStatusLine(downloads = downloads)
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        sorted.isEmpty() ->
                            "No downloads yet.\nFiles you download while browsing, and URLs you paste above, appear here."
                        filter == DownloadFilter.ACTIVE ->
                            "Nothing is downloading right now."
                        else ->
                            "No completed downloads yet."
                    },
                    color = LocalYpnPalette.current.muted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp)
            ) {
                items(visible, key = { it.id }) { item ->
                    DownloadRow(
                        item = item,
                        downloads = downloads,
                        onRename = { renameItemId = item.id }
                    )
                }
            }
        }
    }

    val renameItem = renameItemId?.let { id ->
        downloads.items.firstOrNull { it.id == id }
    }
    if (renameItem != null) {
        RenameDialog(
            item = renameItem,
            onConfirm = { newName ->
                downloads.rename(renameItem.id, newName)
                renameItemId = null
            },
            onDismiss = { renameItemId = null }
        )
    }
}

/** v1.4: the Downloads screen's chip filter (see [DownloadsScreen]). */
private enum class DownloadFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    COMPLETED("Completed")
}

private fun statusRank(status: DownloadStatus): Int = when (status) {
    DownloadStatus.DOWNLOADING -> 0
    DownloadStatus.QUEUED -> 1
    DownloadStatus.PAUSED -> 2
    DownloadStatus.FAILED -> 3
    DownloadStatus.COMPLETED -> 4
}

@Composable
private fun AddUrlRow(downloads: DownloadController) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Paste a download URL") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                if (downloads.addManualDownload(text)) {
                    text = ""
                    Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        "That doesn't look like a downloadable URL",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        ) {
            Text("Add")
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    downloads: DownloadController,
    onRename: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(
                text = item.fileName,
                color = colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusLine(item),
                color = if (item.status == DownloadStatus.FAILED) colors.error else palette.muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (item.status != DownloadStatus.COMPLETED) {
                if (item.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { item.percent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 9.dp),
                        color = colors.primary,
                        trackColor = palette.surface2
                    )
                } else if (item.isActive) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 9.dp),
                        color = colors.primary,
                        trackColor = palette.surface2
                    )
                }
            }

            if (item.wifiBytes > 0 || item.cellBytes > 0) {
                SplitBar(item = item)
            }

            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                when (item.status) {
                    DownloadStatus.DOWNLOADING -> {
                        TextButton(onClick = { downloads.pause(item.id) }) { Text("Pause") }
                        TextButton(onClick = { downloads.cancelDownload(item.id) }) {
                            Text("Cancel")
                        }
                    }
                    DownloadStatus.QUEUED -> {
                        TextButton(onClick = { downloads.cancelDownload(item.id) }) {
                            Text("Cancel")
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        TextButton(onClick = { downloads.resume(item.id) }) { Text("Resume") }
                        TextButton(onClick = onRename) { Text("Rename") }
                        TextButton(onClick = { downloads.deleteDownload(item.id) }) {
                            Text("Delete")
                        }
                    }
                    DownloadStatus.FAILED -> {
                        TextButton(onClick = { downloads.resume(item.id) }) { Text("Retry") }
                        TextButton(onClick = onRename) { Text("Rename") }
                        TextButton(onClick = { downloads.deleteDownload(item.id) }) {
                            Text("Delete")
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        TextButton(onClick = { downloads.openDownload(context, item) }) {
                            Text("Open")
                        }
                        TextButton(onClick = onRename) { Text("Rename") }
                        TextButton(onClick = { downloads.deleteDownload(item.id) }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Rename prompt for a paused / failed / completed download,
 * prefilled with the current file name. The controller sanitizes
 * the text and de-collides it against existing files; this dialog
 * only collects it.
 */
@Composable
private fun RenameDialog(
    item: DownloadItem,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(item.id) { mutableStateOf(item.fileName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename download") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun statusLine(item: DownloadItem): String {
    val downloaded = formatBytes(item.downloadedBytes)
    val total = formatBytes(item.totalBytes)
    return when (item.status) {
        DownloadStatus.QUEUED -> "Waiting…"
        DownloadStatus.DOWNLOADING -> buildString {
            if (item.totalBytes > 0) {
                append("$downloaded of $total · ${(item.percent * 100).toInt()}%")
            } else {
                append("$downloaded downloaded")
            }
            if (item.speedBps > 0) {
                append(" · ${formatSpeed(item.speedBps)}")
                if (item.dualNetwork) {
                    append(" (Wi-Fi ${formatSpeed(item.wifiSpeedBps)}")
                    append(" · Data ${formatSpeed(item.cellSpeedBps)})")
                }
            }
        }
        DownloadStatus.PAUSED ->
            if (item.totalBytes > 0) "Paused · $downloaded of $total saved"
            else "Paused · $downloaded saved"
        DownloadStatus.FAILED -> item.error ?: "Download failed"
        DownloadStatus.COMPLETED -> "Done · $total"
    }
}

/**
 * The signature split bar: how much of the file each network carried,
 * from the engine's measured per-network counters. Percentages are
 * shares of the whole file (or of what's downloaded, while the total
 * is still unknown) — the mockups' semantics.
 */
@Composable
private fun SplitBar(item: DownloadItem) {
    val palette = LocalYpnPalette.current
    val basis = if (item.totalBytes > 0) {
        item.totalBytes.toFloat()
    } else {
        maxOf(item.downloadedBytes, 1L).toFloat()
    }
    val wifiShare = item.wifiBytes / basis
    val cellShare = item.cellBytes / basis
    val rest = (1f - wifiShare - cellShare).coerceAtLeast(0f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(palette.surface2)
    ) {
        if (wifiShare > 0f) {
            Box(Modifier.weight(wifiShare).fillMaxHeight().background(palette.wifi))
        }
        if (cellShare > 0f) {
            Box(Modifier.weight(cellShare).fillMaxHeight().background(palette.data))
        }
        if (rest > 0f) {
            Box(Modifier.weight(rest).fillMaxHeight())
        }
    }
    Row(modifier = Modifier.padding(top = 6.dp)) {
        if (item.wifiBytes > 0) {
            SplitLegend(color = palette.wifi, label = "Wi-Fi · ${(wifiShare * 100).toInt()}%")
        }
        if (item.wifiBytes > 0 && item.cellBytes > 0) {
            Spacer(Modifier.width(12.dp))
        }
        if (item.cellBytes > 0) {
            SplitLegend(color = palette.data, label = "Mobile Data · ${(cellShare * 100).toInt()}%")
        }
        Spacer(Modifier.weight(1f))
        if (!item.dualNetwork && item.downloadedBytes > 0) {
            Text(
                text = if (item.cellBytes > 0 && item.wifiBytes == 0L) {
                    "Single network · Mobile data"
                } else {
                    "Single network · Wi-Fi"
                },
                color = palette.muted,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * One slim line naming the physical network paths the accelerator
 * can currently see, so a one-sided split (e.g. every byte arriving
 * over mobile data) has its explanation on screen: the missing path
 * shows as "not found", and a path that exists but keeps failing
 * shows its last failure ("Wi-Fi error: …" / "Data error: …").
 */
@Composable
private fun PathStatusLine(downloads: DownloadController) {
    val wifi = if (downloads.wifiPathUp) "Wi-Fi: ready" else "Wi-Fi: not found"
    val cell = if (downloads.cellPathUp) "Mobile data: ready" else "Mobile data: not found"
    val note = downloads.poolNote
    val wifiError = downloads.wifiPathError
    val cellError = downloads.cellPathError
    Text(
        text = buildString {
            append("Paths — $wifi · $cell")
            if (note != null) append(" · $note")
            if (wifiError != null) append(" · Wi-Fi error: $wifiError")
            if (cellError != null) append(" · Data error: $cellError")
        },
        color = LocalYpnPalette.current.muted,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun SplitLegend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .height(8.dp)
                .width(8.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, color = LocalYpnPalette.current.muted, fontSize = 11.sp)
    }
}
