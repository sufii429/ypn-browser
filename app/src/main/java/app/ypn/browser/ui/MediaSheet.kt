package app.ypn.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ypn.browser.browser.BrowserTab
import app.ypn.browser.browser.LinkMenuRequest
import app.ypn.browser.browser.MediaItem
import app.ypn.browser.browser.MediaKind
import app.ypn.browser.download.formatBytes
import app.ypn.browser.ui.theme.LocalYpnPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/**
 * v1.3's two browser sheets, hosted by BrowserScreen like VpnSheet
 * is hosted by the shell — same surface, same shape, theme colours
 * throughout:
 *
 * [LinkMenuSheet] is the link long-press menu (the controller raises
 * it from the WebView's hit test): the held link's URL over rows for
 * new tab / background tab / copy / download.
 *
 * [MediaSheet] is the video fetcher's "Videos on this page": the
 * streams the active tab's current page pulled, each with a quality
 * read from its URL when one is written there, a lazily probed size,
 * and a Download button that hands the file to the download engine
 * (with the page as referer). HLS playlists open into the quality
 * list inside them — informational in this version, no HLS
 * downloading.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkMenuSheet(
    request: LinkMenuRequest,
    onDismissRequest: () -> Unit,
    onOpenInNewTab: () -> Unit,
    onOpenInBackgroundTab: () -> Unit,
    onDownloadLink: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
        ) {
            Text(
                text = "Link",
                color = colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                text = request.url,
                color = palette.muted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, top = 3.dp)
            )
            HorizontalDivider(
                color = colors.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
            LinkMenuRow(
                icon = Icons.Filled.OpenInNew,
                label = "Open in new tab",
                onClick = onOpenInNewTab
            )
            LinkMenuRow(
                icon = Icons.Filled.Layers,
                label = "Open in background tab",
                onClick = onOpenInBackgroundTab
            )
            LinkMenuRow(
                icon = Icons.Filled.ContentCopy,
                label = "Copy link",
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("link", request.url))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    onDismissRequest()
                }
            )
            LinkMenuRow(
                icon = Icons.Filled.Download,
                label = "Download link",
                onClick = onDownloadLink
            )
        }
    }
}

@Composable
private fun LinkMenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(13.dp))
        Text(
            text = label,
            color = colors.onSurface,
            fontSize = 14.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSheet(
    tab: BrowserTab,
    userAgent: String?,
    onDismissRequest: () -> Unit,
    onDownload: (MediaItem) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // Probed sizes by URL: absent while probing, -1 when the server
    // would not say, otherwise the byte count.
    val sizes = remember { mutableStateMapOf<String, Long>() }
    // The HLS playlist currently opened into its quality list.
    var openedHls by remember { mutableStateOf<MediaItem?>(null) }
    var variants by remember { mutableStateOf<List<HlsVariant>?>(null) }
    var hlsFailed by remember { mutableStateOf(false) }

    // Reading the tab's snapshot list here keeps the sheet live: a
    // stream the page pulls while the sheet is open appears in it.
    val mediaItems = tab.media

    // Probe sizes lazily, off the main thread, as file rows appear.
    LaunchedEffect(tab.id, mediaItems.size) {
        val toProbe = mediaItems.filter {
            it.kind == MediaKind.VIDEO_FILE && !sizes.containsKey(it.url)
        }
        for (item in toProbe) {
            val size = withContext(Dispatchers.IO) {
                probeMediaSize(item.url, item.pageUrl, userAgent)
            }
            sizes[item.url] = size ?: -1L
        }
    }

    fun openHls(item: MediaItem) {
        openedHls = item
        variants = null
        hlsFailed = false
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                fetchHlsVariants(item.url, item.pageUrl, userAgent)
            }
            if (openedHls?.url == item.url) {
                if (parsed == null) hlsFailed = true else variants = parsed
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            val hls = openedHls
            if (hls == null) {
                Text(
                    text = "Videos on this page",
                    color = colors.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Text(
                    text = "Found while the video plays — the list grows " +
                        "as more qualities load.",
                    color = palette.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, top = 3.dp)
                )
                if (mediaItems.isEmpty()) {
                    Text(
                        text = "No videos found yet — they appear here " +
                            "while a video plays.",
                        color = palette.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .padding(top = 6.dp)
                    ) {
                        items(mediaItems, key = { it.url }) { item ->
                            when (item.kind) {
                                MediaKind.VIDEO_FILE -> MediaFileRow(
                                    item = item,
                                    sizeBytes = sizes[item.url],
                                    sizeProbed = sizes.containsKey(item.url),
                                    onDownload = { onDownload(item) }
                                )
                                MediaKind.HLS -> MediaHlsRow(
                                    onOpen = { openHls(item) }
                                )
                            }
                            HorizontalDivider(
                                color = colors.outline,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "Downloads use your dual-network engine — " +
                        "pause and resume included.",
                    color = palette.muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, top = 10.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { openedHls = null }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to videos",
                            tint = colors.onSurface
                        )
                    }
                    Column {
                        Text(
                            text = "Stream qualities",
                            color = colors.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = mediaDisplayName(hls.url),
                            color = palette.muted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                when {
                    hlsFailed -> Text(
                        text = "Could not read the playlist",
                        color = palette.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
                    )
                    variants == null -> Text(
                        text = "Reading the playlist…",
                        color = palette.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
                    )
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .padding(top = 6.dp)
                    ) {
                        items(variants!!, key = { it.url }) { variant ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = variant.quality ?: "Variant",
                                        color = colors.onSurface,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = variant.bandwidth?.let {
                                            String.format(
                                                Locale.US,
                                                "≈ %.1f Mbps · HLS variant",
                                                it / 1_000_000.0
                                            )
                                        } ?: "HLS variant",
                                        color = palette.muted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 1.dp)
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = colors.outline,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One direct-video row: quality/size on the left, Download on the right. */
@Composable
private fun MediaFileRow(
    item: MediaItem,
    sizeBytes: Long?,
    sizeProbed: Boolean,
    onDownload: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    val quality = remember(item.url) { mediaQualityLabel(item.url) }
    val sizeText = when {
        !sizeProbed -> "…"
        sizeBytes == null || sizeBytes < 0 -> "Size unknown"
        else -> formatBytes(sizeBytes)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = quality ?: mediaDisplayName(item.url),
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${mediaFormatLabel(item.url)} · $sizeText",
                color = palette.muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = onDownload,
            shape = RoundedCornerShape(9.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = palette.onAccent
            )
        ) {
            Text(text = "Download", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** One HLS row: the playlist itself, opening into its quality list. */
@Composable
private fun MediaHlsRow(onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Auto · HLS",
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Stream playlist · qualities inside",
                color = palette.muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        OutlinedButton(
            onClick = onOpen,
            shape = RoundedCornerShape(9.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
            border = BorderStroke(1.dp, colors.primary)
        ) {
            Text(text = "Open", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- Labels derived from the URL ----------

/** Resolution tokens the fetcher will claim — written in the URL or not at all. */
private val QUALITY_TOKENS = listOf(2160, 1440, 1080, 720, 480, 360, 240)

/**
 * A quality label ("720p") read ONLY from the URL: a "720p"-style
 * token, or a separator-wrapped number ("_720_", "-720."). No token,
 * no claim — the row then shows the file name instead.
 */
private fun mediaQualityLabel(url: String): String? {
    val text = url.lowercase()
    for (q in QUALITY_TOKENS) {
        if (text.contains("${q}p")) return "${q}p"
    }
    for (q in QUALITY_TOKENS) {
        if (Regex("""[_\-./]$q[_\-.]""").containsMatchIn(text)) return "${q}p"
    }
    return null
}

/** "MP4" / "WEBM" / "MKV" from the URL path's extension. */
private fun mediaFormatLabel(url: String): String {
    val path = url.substringBefore('?').substringBefore('#')
    return path.substringAfterLast('.', "").uppercase().ifBlank { "VIDEO" }
}

/** The URL path's last segment, decoded — a row's fallback title. */
private fun mediaDisplayName(url: String): String {
    val segment = url.substringBefore('?').substringBefore('#')
        .substringAfterLast('/').trim()
    if (segment.isBlank()) return "Video"
    return try {
        URLDecoder.decode(segment, "UTF-8")
    } catch (e: Exception) {
        segment
    }
}

// ---------- Network helpers (blocking; call off the main thread) ----------

/**
 * One connection for a media probe/fetch: the browser's own UA (when
 * known) plus the page as Referer — the two headers file hosts most
 * often demand. Mirrors what the download engine sends.
 */
private fun openMediaConnection(
    url: String,
    referer: String,
    userAgent: String?
): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = MEDIA_TIMEOUT_MS
    conn.readTimeout = MEDIA_TIMEOUT_MS
    conn.instanceFollowRedirects = true
    userAgent?.takeIf { it.isNotBlank() }?.let {
        conn.setRequestProperty("User-Agent", it)
    }
    if (referer.isNotBlank()) {
        conn.setRequestProperty("Referer", referer)
    }
    return conn
}

/**
 * A media file's size, the download engine's probing approach in
 * miniature: HEAD first (Content-Length), then a one-byte Range GET
 * whose Content-Range carries the total. Null when neither answers.
 */
private fun probeMediaSize(url: String, referer: String, userAgent: String?): Long? {
    try {
        val head = openMediaConnection(url, referer, userAgent)
        try {
            head.requestMethod = "HEAD"
            val code = head.responseCode
            val total = head.contentLengthLong
            if (code in 200..299 && total > 0) return total
        } finally {
            head.disconnect()
        }
    } catch (e: Exception) {
        // HEAD refused or unsupported — the range probe decides.
    }
    return try {
        val conn = openMediaConnection(url, referer, userAgent)
        try {
            conn.setRequestProperty("Range", "bytes=0-0")
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL ->
                    conn.getHeaderField("Content-Range")
                        ?.substringAfterLast('/')?.trim()?.toLongOrNull()
                        ?.takeIf { it > 0 }
                HttpURLConnection.HTTP_OK ->
                    conn.contentLengthLong.takeIf { it > 0 }
                else -> null
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}

/** One variant stream inside an HLS master playlist. */
private class HlsVariant(
    val quality: String?,
    val bandwidth: Long?,
    val url: String
)

/**
 * Fetch + parse an HLS master playlist into its variant list:
 * each #EXT-X-STREAM-INF's RESOLUTION (→ "720p") and BANDWIDTH
 * (→ bits/sec), with the variant URI resolved against the playlist
 * URL. Null when the fetch fails, the body is not a playlist, or it
 * carries no variants (a media playlist has none to list).
 */
private fun fetchHlsVariants(
    playlistUrl: String,
    referer: String,
    userAgent: String?
): List<HlsVariant>? {
    val text = try {
        val conn = openMediaConnection(playlistUrl, referer, userAgent)
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                while (total < MAX_PLAYLIST_CHARS) {
                    val read = reader.read(buf)
                    if (read < 0) break
                    sb.append(buf, 0, read)
                    total += read
                }
                sb.toString()
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        return null
    }
    if (!text.contains("#EXTM3U")) return null
    val variants = mutableListOf<HlsVariant>()
    var pendingAttrs: String? = null
    for (line in text.lines()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("#EXT-X-STREAM-INF:") ->
                pendingAttrs = trimmed.substringAfter(':')
            trimmed.isEmpty() || trimmed.startsWith("#") -> Unit
            pendingAttrs != null -> {
                val attrs = pendingAttrs!!
                pendingAttrs = null
                val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""").find(attrs)
                val quality = resolution?.let { "${it.groupValues[2]}p" }
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(attrs)
                    ?.groupValues?.get(1)?.toLongOrNull()
                val resolved = try {
                    URL(URL(playlistUrl), trimmed).toString()
                } catch (e: Exception) {
                    trimmed
                }
                variants += HlsVariant(quality, bandwidth, resolved)
            }
        }
    }
    return variants.ifEmpty { null }
}

private const val MEDIA_TIMEOUT_MS = 10_000
private const val MAX_PLAYLIST_CHARS = 1_000_000
