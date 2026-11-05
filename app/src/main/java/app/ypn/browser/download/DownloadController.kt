package app.ypn.browser.download

import android.content.Context
import android.content.Intent
import android.net.Network
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3 download manager + dual-network accelerator.
 *
 * Process-wide singleton: the Compose shell (YpnApp) and the
 * foreground [DownloadService] share this one instance, mirroring how
 * YpnApp holds the VpnController. Transfers run on this controller's
 * IO scope; the service is only a keep-alive + notification shell.
 *
 * Engine, per download:
 *  1. Probe — HEAD first, then a one-byte Range GET. If the server
 *     answers ranges and the total size is known, the file is split
 *     into ~2 MB chunks fetched by parallel workers into ONE
 *     preallocated .part file (each worker seeks to its chunk's
 *     offset). Per-chunk byte counters make pause/resume exact, even
 *     across an app restart.
 *  2. Otherwise a single plain stream. Honest limit: pause keeps the
 *     partial bytes, but resume restarts that stream from byte 0 —
 *     without Range support there is no way to continue mid-file.
 *
 * Accelerator: with the setting on, chunk workers come in Wi-Fi and
 * cellular roles (2 + 2). A role opens its connections through the
 * matching [NetworkPool] handle, so its bytes physically travel over
 * that network — and outside the VPN tunnel, by design. Bytes are
 * counted under the network that actually carried them. If only one
 * network exists, every worker ends up on it and the download simply
 * runs single-network.
 */
class DownloadController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val store = DownloadStore(appContext)
    private val pool = NetworkPool(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessions = ConcurrentHashMap<String, RunSession>()
    private val persistMutex = Mutex()

    val items = mutableStateListOf<DownloadItem>()

    var accelerationEnabled by mutableStateOf(true)
        private set

    /**
     * Which physical network paths the accelerator's pool can
     * currently see (a live handle exists). Maintained by [start] and
     * the sampler while downloads run; the Downloads screen shows
     * them so a one-sided split is explainable at a glance.
     */
    var wifiPathUp by mutableStateOf(false)
        private set

    var cellPathUp by mutableStateOf(false)
        private set

    /** The pool's last requestNetwork error, if any — shown on the Paths line. */
    val poolNote: String?
        get() = pool.lastRequestError

    /**
     * The last failure each network path produced in the current
     * run: an exception's name + short message, or "HTTP <code>"
     * when the server itself refused the path (e.g. a host that
     * bound a download to one IP answering the second network with
     * an error). Both handles can be "ready" while one path still
     * carries nothing — these say why. A path's error clears the
     * moment that path writes bytes again; both reset in [start].
     */
    var wifiPathError: String? by mutableStateOf(null)
        private set

    var cellPathError: String? by mutableStateOf(null)
        private set

    /** Wall-clock time of each path's last recorded failure. */
    private var wifiPathErrorAtMs = 0L
    private var cellPathErrorAtMs = 0L

    /** Note a failure of [kind]'s path for the Downloads screen. */
    private fun recordPathError(kind: NetKind, error: String) {
        val text = error.trim().take(MAX_PATH_ERROR_CHARS)
        when (kind) {
            NetKind.WIFI -> {
                wifiPathError = text
                wifiPathErrorAtMs = System.currentTimeMillis()
            }
            NetKind.CELL -> {
                cellPathError = text
                cellPathErrorAtMs = System.currentTimeMillis()
            }
        }
    }

    /** A write on [kind]'s path proves it works — clear its error. */
    private fun clearPathError(kind: NetKind) {
        when (kind) {
            NetKind.WIFI -> wifiPathError = null
            NetKind.CELL -> cellPathError = null
        }
    }

    /** Compact failure label: exception type + short message. */
    private fun describeError(e: Exception): String {
        val message = e.message?.takeIf { it.isNotBlank() }?.trim()
        return if (message == null) {
            e.javaClass.simpleName
        } else {
            "${e.javaClass.simpleName}: $message"
        }
    }

    init {
        accelerationEnabled = store.acceleratorEnabled
        val dir = downloadsDir()
        val loaded = store.loadDownloads()
        for (item in loaded) {
            when (item.status) {
                DownloadStatus.COMPLETED ->
                    if (!File(dir, item.fileName).exists()) {
                        item.status = DownloadStatus.FAILED
                        item.error = "The downloaded file was deleted outside YPN."
                    }
                DownloadStatus.PAUSED -> {
                    // Verify the .part file still matches the saved
                    // progress; if it vanished or shrank, the saved
                    // counters would describe bytes that aren't there.
                    val part = File(dir, item.partFileName)
                    val intact = part.exists() &&
                        (!item.ranged || part.length() == item.totalBytes)
                    if (!intact) {
                        synchronized(item.ioLock) {
                            item.chunks.forEach { it.downloaded = 0 }
                        }
                        item.downloadedBytes = 0
                        item.wifiBytes = 0
                        item.cellBytes = 0
                    }
                }
                else -> Unit
            }
        }
        items.addAll(loaded)
    }

    // ---- Derived state the UI reads ------------------------------------

    fun hasActiveDownloads(): Boolean = items.any { it.isActive }

    val activeCount: Int get() = items.count { it.isActive }

    /** The download the home card mirrors: running first, then paused. */
    val topActive: DownloadItem?
        get() = items.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
            ?: items.firstOrNull { it.status == DownloadStatus.QUEUED }
            ?: items.firstOrNull { it.status == DownloadStatus.PAUSED }

    fun downloadsDir(): File {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun partFile(item: DownloadItem): File = File(downloadsDir(), item.partFileName)

    // ---- Settings --------------------------------------------------------

    // Named apply*, not set*: a `fun setAccelerationEnabled` would clash on
    // the JVM with the setter of `var accelerationEnabled` above (the same
    // platform-declaration clash that broke the Phase 1 build).
    fun applyAccelerationEnabled(enabled: Boolean) {
        accelerationEnabled = enabled
        store.acceleratorEnabled = enabled
    }

    // ---- Enqueue ---------------------------------------------------------

    /** From the WebView download listener (Phase 1's old toast spot). */
    fun enqueueFromBrowser(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        cookie: String?
    ) {
        enqueue(
            url = url,
            suggestedName = deriveFileName(url, contentDisposition),
            mimeType = mimeType,
            userAgent = userAgent,
            cookie = cookie,
            referer = null
        )
    }

    /** From the Downloads screen's paste-a-URL box. False = not a URL. */
    fun addManualDownload(raw: String): Boolean {
        val url = normalizeDownloadUrl(raw) ?: return false
        enqueue(
            url = url,
            suggestedName = deriveFileName(url, null),
            mimeType = null,
            userAgent = null,
            cookie = null,
            referer = null
        )
        return true
    }

    /**
     * v1.3: enqueue a file the user picked out of a web page — a
     * video the fetcher sniffed, or a link from the long-press menu.
     * These downloads carry the page as [referer] (many file hosts
     * refuse referer-less requests) and the browser's own
     * [userAgent]; [cookie] is the session cookie for the file's
     * host when the caller could read one, so logged-in links keep
     * working. The name comes from the URL path via the same
     * [deriveFileName] rule as every other download.
     */
    fun enqueueMedia(
        url: String,
        referer: String?,
        userAgent: String?,
        cookie: String? = null
    ) {
        enqueue(
            url = url,
            suggestedName = deriveFileName(url, null),
            mimeType = null,
            userAgent = userAgent,
            cookie = cookie,
            referer = referer
        )
    }

    /**
     * v1.3.2: enqueue one HLS variant the user picked in the media
     * sheet. [url] is the variant's media playlist; the engine joins
     * its segments into a single MPEG-TS file under [suggestedName]
     * (the caller names it .ts). Carries the same referer / UA /
     * cookie trio as [enqueueMedia] — segment requests go out with
     * exactly those headers.
     */
    fun enqueueHls(
        url: String,
        suggestedName: String,
        referer: String?,
        userAgent: String?,
        cookie: String? = null
    ) {
        enqueue(
            url = url,
            suggestedName = suggestedName,
            mimeType = null,
            userAgent = userAgent,
            cookie = cookie,
            referer = referer,
            hls = true
        )
    }

    private fun enqueue(
        url: String,
        suggestedName: String,
        mimeType: String?,
        userAgent: String?,
        cookie: String?,
        referer: String?,
        hls: Boolean = false
    ) {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = uniqueName(sanitizeFileName(suggestedName, mimeType)),
            mimeType = mimeType,
            userAgent = userAgent,
            cookie = cookie,
            createdAt = System.currentTimeMillis(),
            referer = referer,
            hls = hls
        )
        items.add(0, item)
        persist()
        start(item)
    }

    private fun normalizeDownloadUrl(raw: String): String? {
        val input = raw.trim()
        if (input.isEmpty()) return null
        if (input.startsWith("http://") || input.startsWith("https://")) return input
        // Bare hosts only — a search phrase is not a download.
        val hostPart = input.substringBefore('/').substringBefore('?')
        val labels = hostPart.split('.')
        val looksLikeHost = !input.contains(' ') && labels.size >= 2 &&
            labels.all { it.isNotEmpty() } && labels.last().length >= 2
        return if (looksLikeHost) "https://$input" else null
    }

    /**
     * A download's name from the two places that actually know it:
     * the Content-Disposition header, then the URL path. Deliberately
     * NOT URLUtil.guessFileName — that helper replaces the name's
     * extension with one derived from the server's Content-Type, so
     * a generic host answering application/octet-stream renames a
     * real "movie.mkv" to "movie.bin". Here a name that already
     * carries an extension keeps it, whatever the MIME type claims;
     * [sanitizeFileName] only appends a MIME extension when the
     * resulting name has no dot at all. Blank when neither source
     * yields a name (sanitizeFileName turns that into "download").
     */
    private fun deriveFileName(url: String, contentDisposition: String?): String {
        val headerName = contentDisposition?.let { disposition ->
            // RFC 5987 form first: filename*=UTF-8''movie%20name.mkv —
            // the (percent-encoded) value follows the second quote.
            val star = Regex(
                """filename\*\s*=\s*[^']*'[^']*'([^;]+)""",
                RegexOption.IGNORE_CASE
            ).find(disposition)?.groupValues?.get(1)
            val raw = star ?: Regex(
                """filename\s*=\s*"?([^";]+)"?""",
                RegexOption.IGNORE_CASE
            ).find(disposition)?.groupValues?.get(1)
            raw?.trim()?.takeIf { it.isNotBlank() }?.let { decodeFileName(it) }
        }
        if (!headerName.isNullOrBlank()) return headerName
        val segment = url.substringBefore('?').substringBefore('#')
            .substringAfterLast('/').trim()
        return if (segment.isBlank()) "" else decodeFileName(segment)
    }

    /** Percent-decoding for file names; a malformed escape keeps the raw text. */
    private fun decodeFileName(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }

    private fun sanitizeFileName(raw: String?, mimeType: String?): String {
        var name = (raw ?: "").trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
        if (name.isBlank()) name = "download"
        if (!name.contains('.')) {
            val ext = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (!ext.isNullOrBlank()) name = "$name.$ext"
        }
        return name
    }

    private fun uniqueName(name: String): String {
        val dir = downloadsDir()
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        fun taken(candidate: String): Boolean =
            File(dir, candidate).exists() || File(dir, "$candidate.part").exists() ||
                items.any { it.fileName == candidate }
        var candidate = name
        var n = 1
        while (taken(candidate)) {
            candidate = "$base ($n)$ext"
            n++
        }
        return candidate
    }

    // ---- User actions ----------------------------------------------------

    fun pause(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        val session = sessions[item.id]
        if (session != null) {
            // The engine's finally flips DOWNLOADING -> PAUSED.
            session.stop()
        } else if (item.isActive) {
            item.status = DownloadStatus.PAUSED
            persist()
        }
    }

    fun resume(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.status == DownloadStatus.COMPLETED) return
        start(item)
    }

    /**
     * Rename a download that is not running (paused, failed or
     * completed only — never while a session is live). The on-disk
     * files move first: the .part file, then the completed file, so
     * if the second move fails the first is rolled back and nothing
     * is left half-renamed. [DownloadItem.fileName] — and with it
     * the persisted record — changes only once every file that
     * exists carries the new name. This is how a movie saved as
     * ".bin" becomes ".mkv" again without re-downloading.
     */
    fun rename(id: String, rawNewName: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.status != DownloadStatus.PAUSED &&
            item.status != DownloadStatus.FAILED &&
            item.status != DownloadStatus.COMPLETED
        ) return
        val sanitized = sanitizeFileName(rawNewName, item.mimeType)
        if (sanitized == item.fileName) return
        val target = uniqueName(sanitized)
        if (target == item.fileName) return
        val dir = downloadsDir()
        val part = File(dir, item.partFileName)
        val newPart = File(dir, "$target.part")
        var partMoved = false
        if (part.exists()) {
            if (!part.renameTo(newPart)) return
            partMoved = true
        }
        val completed = File(dir, item.fileName)
        if (completed.exists() && !completed.renameTo(File(dir, target))) {
            if (partMoved) newPart.renameTo(part)
            return
        }
        item.fileName = target
        persist()
    }

    /** Stop an in-flight download and throw its partial file away. */
    fun cancelDownload(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        sessions[item.id]?.stop()
        partFile(item).delete()
        items.remove(item)
        persist()
    }

    /** Remove a finished/paused/failed download and its file(s). */
    fun deleteDownload(id: String) {
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.isActive) {
            cancelDownload(id)
            return
        }
        val dir = downloadsDir()
        File(dir, item.partFileName).delete()
        File(dir, item.fileName).delete()
        items.remove(item)
        persist()
    }

    fun openDownload(context: Context, item: DownloadItem) {
        val file = File(downloadsDir(), item.fileName)
        if (!file.exists()) {
            Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, resolveMime(item, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveMime(item: DownloadItem, file: File): String {
        val fromItem = item.mimeType?.takeIf {
            it.isNotBlank() && it != "application/octet-stream"
        }
        if (fromItem != null) return fromItem
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    }

    // ---- Engine ----------------------------------------------------------

    private fun start(item: DownloadItem) {
        if (sessions.containsKey(item.id)) return
        val session = RunSession(item)
        sessions[item.id] = session
        item.error = null
        wifiPathError = null
        cellPathError = null
        wifiPathErrorAtMs = 0L
        cellPathErrorAtMs = 0L
        item.status = DownloadStatus.DOWNLOADING
        session.poolAcquired = accelerationEnabled
        if (session.poolAcquired) pool.acquire()
        refreshPathState()
        DownloadService.ensureStarted(appContext)
        session.job = scope.launch(Dispatchers.IO) { runEngine(session) }
        persist()
    }

    private suspend fun runEngine(session: RunSession) {
        val item = session.item
        var failure: String? = null
        var completed = false
        try {
            coroutineScope {
                val sampler = launch { samplerLoop(session) }
                try {
                    if (item.hls) {
                        // v1.3.2: a playlist URL is not a file — the
                        // HTTP probe must never run on it. The HLS
                        // path fetches the playlist itself and joins
                        // its segments instead.
                        completed = runHlsDownload(session)
                    } else {
                    if (item.totalBytes < 0) probe(item)
                    when {
                        item.totalBytes == 0L -> {
                            // An empty file has nothing to transfer.
                            partFile(item).outputStream().use { }
                            completed = true
                        }
                        item.ranged && item.totalBytes > 0 -> {
                            prepareChunks(item)
                            // The stall window measures chunk transfer
                            // only — probe/setup time never counts.
                            session.lastProgressAtMs = System.currentTimeMillis()
                            val workers = workerRoles().map { role ->
                                launch { chunkWorker(session, role) }
                            }
                            workers.forEach { it.join() }
                            completed = !session.stopped && session.failReason == null &&
                                synchronized(item.ioLock) { item.chunks.all { it.complete } }
                        }
                        else -> completed = singleStream(session)
                    }
                    }
                } finally {
                    sampler.cancel()
                }
            }
        } catch (e: CancellationException) {
            // Pause / cancel: state settles in the finally block.
        } catch (e: Exception) {
            failure = e.message ?: "Download failed"
        } finally {
            sessions.remove(item.id, session)
            if (session.poolAcquired) pool.release()
            if (sessions.isEmpty()) {
                // The last run is over, so its pool release (above, or
                // in the session that held it) has dropped the network
                // handles — the path flags must not keep showing the
                // stale "ready" state of a finished run.
                wifiPathUp = false
                cellPathUp = false
            }
            item.speedBps = 0
            item.wifiSpeedBps = 0
            item.cellSpeedBps = 0
            if (failure == null) failure = session.failReason
            when {
                completed -> finalizeDownload(item)
                failure != null -> {
                    item.status = DownloadStatus.FAILED
                    item.error = failure
                }
                item.status == DownloadStatus.DOWNLOADING ->
                    item.status = DownloadStatus.PAUSED
            }
            persist()
        }
    }

    /** HEAD, then a one-byte Range GET, to learn size + range support. */
    private fun probe(item: DownloadItem) {
        val head = openFor(item, null).first
        try {
            head.requestMethod = "HEAD"
            val code = head.responseCode
            val total = head.contentLengthLong
            val ranges = head.getHeaderField("Accept-Ranges")
                ?.contains("bytes", ignoreCase = true) == true
            if (code in 200..299 && total > 0 && ranges) {
                item.totalBytes = total
                item.ranged = true
                return
            }
            if (code in 200..299 && total == 0L) {
                item.totalBytes = 0
                return
            }
        } catch (e: IOException) {
            // Some servers refuse HEAD — the GET probe below decides.
        } finally {
            head.disconnect()
        }

        val conn = openFor(item, null).first
        try {
            conn.setRequestProperty("Range", "bytes=0-0")
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val total = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                    if (total != null && total > 0) {
                        item.totalBytes = total
                        item.ranged = true
                    }
                }
                HttpURLConnection.HTTP_OK -> {
                    val total = conn.contentLengthLong
                    if (total > 0) item.totalBytes = total
                    item.ranged = false
                }
                else -> throw IOException("Server answered HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseContentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/')?.trim()?.toLongOrNull()

    /** Build the chunk table and (re)allocate the .part file. */
    private fun prepareChunks(item: DownloadItem) {
        val part = partFile(item)
        synchronized(item.ioLock) {
            if (item.chunks.isEmpty()) {
                val total = item.totalBytes
                val chunkSize = if (total / CHUNK_BYTES > MAX_CHUNKS) {
                    (total + MAX_CHUNKS - 1) / MAX_CHUNKS
                } else {
                    CHUNK_BYTES
                }
                var index = 0
                var start = 0L
                while (start < total) {
                    val end = minOf(start + chunkSize, total) - 1
                    item.chunks += ChunkProgress(index++, start, end)
                    start = end + 1
                }
            }
        }
        if (!part.exists() || part.length() != item.totalBytes) {
            // The bytes on disk can't back the saved counters (fresh
            // download, or the .part was deleted while paused): start
            // the chunk table over and preallocate the full file.
            synchronized(item.ioLock) {
                item.chunks.forEach { it.downloaded = 0 }
            }
            item.downloadedBytes = 0
            item.wifiBytes = 0
            item.cellBytes = 0
            RandomAccessFile(part, "rw").use { it.setLength(item.totalBytes) }
        }
    }

    private fun workerRoles(): List<NetKind?> =
        if (accelerationEnabled) {
            listOf(NetKind.WIFI, NetKind.CELL, NetKind.WIFI, NetKind.CELL)
        } else {
            listOf(null, null, null, null)
        }

    private suspend fun chunkWorker(session: RunSession, role: NetKind?) {
        val item = session.item
        val part = partFile(item)
        // The chunk this worker most recently handed back as stuck. It
        // is avoided on the next claim so a worker idling on the
        // healthy network gets first pick of it — otherwise this
        // worker could keep re-claiming the very chunk its own path
        // cannot move while the healthy path sits idle.
        var avoidIndex = -1
        // The role this worker currently streams under. Starts as the
        // assigned role; a worker whose path proves dead (see below)
        // permanently swaps to the other network for the rest of the
        // session. Role-less workers (accelerator off) never swap.
        var currentRole = role
        // Consecutive chunk sequences this worker handed back without
        // writing a single byte on them. Any byte written resets it.
        var zeroByteFailures = 0
        while (!session.stopped) {
            val chunk = claimChunk(session, avoidIndex)
            if (chunk == null) {
                // Nothing claimable: either every chunk is done (this
                // worker leaves), or the unfinished ones are all
                // claimed by other workers — wait and look again,
                // because a stuck peer hands its chunk back and then
                // there is work again. (fail() sets stopped, so a
                // failed session also ends the wait.)
                if (synchronized(item.ioLock) { item.chunks.all { it.complete } }) return
                delay(CLAIM_WAIT_MS)
                continue
            }
            avoidIndex = -1
            val claimedAt = chunk.downloaded
            var handedBack = false
            var attempts = 0
            while (!chunk.complete && !session.stopped) {
                val before = chunk.downloaded
                try {
                    streamChunkRange(session, chunk, part, currentRole)
                } catch (e: IOException) {
                    // Counted as a no-progress attempt below.
                }
                if (!chunk.complete && !session.stopped) {
                    if (chunk.downloaded == before) {
                        attempts++
                        if (attempts >= MAX_CHUNK_ATTEMPTS) {
                            // This worker's path can't move the chunk
                            // right now (the classic case: a Wi-Fi
                            // network whose backhaul is dead, so its
                            // connections hang or reset). Do NOT fail
                            // the download — hand the chunk back so a
                            // worker on the working network can take
                            // it, wait, and look for another chunk.
                            // Only the sampler may fail the run, and
                            // only when NO path makes progress.
                            unclaimChunk(session, chunk)
                            avoidIndex = chunk.index
                            handedBack = true
                            delay(RETRY_DELAY_MS)
                            break
                        }
                        delay(RETRY_DELAY_MS)
                    } else {
                        attempts = 0
                    }
                }
            }
            if (!session.stopped) {
                if (chunk.downloaded > claimedAt) {
                    // This worker wrote bytes on the chunk — its path
                    // works, so the dead-path evidence resets.
                    zeroByteFailures = 0
                } else if (handedBack && currentRole != null) {
                    // A whole chunk sequence without one byte: this
                    // path is producing nothing. Once that repeats
                    // while the OTHER path demonstrably carries
                    // bytes, stop wasting this worker on the dead
                    // path — it joins the healthy one for the rest
                    // of the session.
                    zeroByteFailures++
                    if (zeroByteFailures >= ROLE_SWAP_FAILURES) {
                        val otherKind = if (currentRole == NetKind.WIFI) {
                            NetKind.CELL
                        } else {
                            NetKind.WIFI
                        }
                        val otherBytes = synchronized(item.ioLock) {
                            if (otherKind == NetKind.CELL) {
                                item.cellBytes
                            } else {
                                item.wifiBytes
                            }
                        }
                        if (otherBytes > 0) {
                            currentRole = otherKind
                            zeroByteFailures = 0
                        }
                    }
                }
            }
            if (chunk.complete) persist()
        }
    }

    private fun claimChunk(session: RunSession, avoidIndex: Int = -1): ChunkProgress? {
        val item = session.item
        synchronized(session.claimed) {
            synchronized(item.ioLock) {
                val next = item.chunks.firstOrNull { chunk ->
                    !chunk.complete && chunk.index !in session.claimed &&
                        chunk.index != avoidIndex
                } ?: return null
                session.claimed += next.index
                return next
            }
        }
    }

    /** Hand a claimed chunk back so another worker can pick it up. */
    private fun unclaimChunk(session: RunSession, chunk: ChunkProgress) {
        synchronized(session.claimed) {
            session.claimed -= chunk.index
        }
    }

    /** Stream the still-missing tail of [chunk] into the .part file. */
    private fun streamChunkRange(
        session: RunSession,
        chunk: ChunkProgress,
        part: File,
        role: NetKind?
    ) {
        val item = session.item
        val from = chunk.start + chunk.downloaded
        if (from > chunk.endInclusive) return
        var conn: HttpURLConnection? = null
        // The network the bytes end up counted under, once known —
        // the fallback attribution for failures after connect.
        var countedKind: NetKind? = null
        var failureRecorded = false
        try {
            val opened = openFor(item, role)
            conn = opened.first
            val connection = opened.first
            val network = opened.second
            session.connections += connection
            connection.setRequestProperty("Range", "bytes=$from-${chunk.endInclusive}")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                // Record BEFORE the throw, so the Paths line keeps
                // the server's own answer (e.g. a host bound to one
                // IP refusing the second network) instead of nothing.
                recordPathError(role ?: pool.kindOf(network), "HTTP $code")
                failureRecorded = true
                throw IOException("HTTP $code on a range request")
            }
            val kind = pool.kindOf(network)
            countedKind = kind
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(from)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var pos = from
                    while (pos <= chunk.endInclusive) {
                        if (session.stopped) break
                        val want = minOf(
                            buffer.size.toLong(),
                            chunk.endInclusive - pos + 1
                        ).toInt()
                        val read = input.read(buffer, 0, want)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        pos += read
                        synchronized(item.ioLock) {
                            chunk.downloaded += read
                            item.downloadedBytes += read
                            session.lastProgressAtMs = System.currentTimeMillis()
                            if (kind == NetKind.CELL) {
                                item.cellBytes += read
                            } else {
                                item.wifiBytes += read
                            }
                            clearPathError(kind)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // A connection that throws while opening or streaming:
            // keep the reason against the worker's path. A failure
            // caused by our own stop (pause/fail disconnects live
            // connections) is not a path failure, so it is skipped.
            if (!failureRecorded && !session.stopped) {
                recordPathError(
                    role ?: countedKind ?: pool.kindOf(null),
                    describeError(e)
                )
            }
            throw e
        } finally {
            conn?.let {
                session.connections -= it
                it.disconnect()
            }
        }
    }

    /**
     * Fallback for servers without usable ranges: one plain stream.
     * Pause keeps the partial file, but resume restarts this stream
     * from byte 0 (there is no mid-file continuation without ranges),
     * and the byte counters restart with it so the split stays true.
     */
    private fun singleStream(session: RunSession): Boolean {
        val item = session.item
        val part = partFile(item)
        synchronized(item.ioLock) {
            item.downloadedBytes = 0
            item.wifiBytes = 0
            item.cellBytes = 0
        }
        val role = if (accelerationEnabled) NetKind.WIFI else null
        var conn: HttpURLConnection? = null
        var network: Network? = null
        var countedKind: NetKind? = null
        var failureRecorded = false
        try {
            val opened = openFor(item, role)
            conn = opened.first
            val connection = opened.first
            network = opened.second
            session.connections += connection
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // Record before the throw, as in streamChunkRange.
                recordPathError(role ?: pool.kindOf(network), "HTTP $code")
                failureRecorded = true
                throw IOException("HTTP $code")
            }
            val length = connection.contentLengthLong
            if (length > 0) item.totalBytes = length
            val kind = pool.kindOf(network)
            countedKind = kind
            var written = 0L
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(0)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (session.stopped) break
                        val read = input.read(buffer)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        written += read
                        synchronized(item.ioLock) {
                            item.downloadedBytes += read
                            session.lastProgressAtMs = System.currentTimeMillis()
                            if (kind == NetKind.CELL) {
                                item.cellBytes += read
                            } else {
                                item.wifiBytes += read
                            }
                            clearPathError(kind)
                        }
                    }
                }
            }
            if (session.stopped) return false
            if (item.totalBytes > 0 && written < item.totalBytes) {
                throw IOException("Connection closed early")
            }
            if (item.totalBytes < 0) item.totalBytes = written
            return true
        } catch (e: IOException) {
            // Same per-path failure reporting as the chunked engine;
            // our own stop (pause/fail) is not a path failure.
            if (!failureRecorded && !session.stopped) {
                recordPathError(
                    role ?: countedKind ?: pool.kindOf(network),
                    describeError(e)
                )
            }
            throw e
        } finally {
            conn?.let {
                session.connections -= it
                it.disconnect()
            }
        }
    }

    /**
     * GET an HLS item's playlist as text (capped, like the media
     * sheet's own fetch) with the item's full header set, via
     * [openForUrl]. The connection joins the session's live set, so
     * a pause cuts a slow playlist read short like any other
     * transfer read.
     */
    private fun fetchHlsPlaylistText(session: RunSession): String {
        val item = session.item
        val conn = openForUrl(item, item.url, null).first
        session.connections += conn
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("Playlist answered HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { reader ->
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
            session.connections -= conn
            conn.disconnect()
        }
    }

    /**
     * v1.3.2's HLS transfer: fetch the item's media playlist, then
     * append its segments one by one into the .part file — the
     * concatenation IS an MPEG-TS stream, so [finalizeDownload]
     * renames the finished .part to the final (.ts) name as usual.
     * Single-path on purpose (role null): segment order matters, so
     * the parallel chunk engine cannot apply to a playlist.
     *
     * Refusals, all honest: a body that is not a playlist; a MASTER
     * playlist (its variants are separate downloads — the sheet has
     * the user pick one first); an encrypted stream (#EXT-X-KEY
     * with a METHOD other than NONE — joining those bytes would
     * produce an unplayable file); and a playlist with no segments.
     *
     * Resume rule: [DownloadItem.hlsSegmentsDone] counts the
     * segments fully written, and an HLS item's .part length always
     * equals [DownloadItem.downloadedBytes] — a segment interrupted
     * mid-write is rolled back out (below) to keep that true. When
     * the counter sits inside the freshly fetched playlist and the
     * length still agrees, the run appends from that segment;
     * anything else restarts the file from segment 0. The counter
     * is persisted after every segment, so even an app restart
     * resumes mid-playlist.
     *
     * Returns true when every segment is in; false when the run was
     * stopped (pause — runEngine's finally settles PAUSED).
     */
    private suspend fun runHlsDownload(session: RunSession): Boolean {
        val item = session.item
        val playlist = fetchHlsPlaylistText(session)
        if (!playlist.contains("#EXTM3U")) {
            throw IOException("Not an HLS playlist")
        }
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            throw IOException("Pick a quality from the list first")
        }
        val keyLine = playlist.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("#EXT-X-KEY") }
        if (keyLine != null) {
            val method = Regex("""METHOD=([^,]+)""").find(keyLine)
                ?.groupValues?.get(1)?.trim()
            if (method != null && method != "NONE") {
                throw IOException("Encrypted stream — cannot download")
            }
        }
        val segments = playlist.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { segmentUri ->
                try {
                    URL(URL(item.url), segmentUri).toString()
                } catch (e: Exception) {
                    segmentUri
                }
            }
        if (segments.isEmpty()) throw IOException("No segments in playlist")
        synchronized(item.ioLock) { item.hlsSegmentCount = segments.size }

        val part = partFile(item)
        var startIndex = 0
        synchronized(item.ioLock) {
            val done = item.hlsSegmentsDone
            if (done in 1 until segments.size && part.length() == item.downloadedBytes) {
                startIndex = done
            } else {
                item.hlsSegmentsDone = 0
                item.downloadedBytes = 0
                item.wifiBytes = 0
                item.cellBytes = 0
            }
        }

        RandomAccessFile(part, "rw").use { raf ->
            if (startIndex == 0) {
                raf.setLength(0)
            } else {
                raf.seek(part.length())
            }
            for (index in startIndex until segments.size) {
                if (session.stopped || !currentCoroutineContext().isActive) return false
                val segmentStart = raf.filePointer
                var segmentBytes = 0L
                var countedCell = false
                var conn: HttpURLConnection? = null
                try {
                    val opened = openForUrl(item, segments[index], null)
                    conn = opened.first
                    session.connections += conn
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IOException("Segment answered HTTP $code")
                    }
                    countedCell = pool.kindOf(opened.second) == NetKind.CELL
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (session.stopped) break
                            val read = input.read(buffer)
                            if (read < 0) break
                            raf.write(buffer, 0, read)
                            segmentBytes += read
                            synchronized(item.ioLock) {
                                item.downloadedBytes += read
                                if (countedCell) {
                                    item.cellBytes += read
                                } else {
                                    item.wifiBytes += read
                                }
                                session.lastProgressAtMs = System.currentTimeMillis()
                            }
                        }
                    }
                } catch (e: IOException) {
                    // Roll the partial segment back out (see the
                    // resume rule above), then let the failure stand.
                    raf.setLength(segmentStart)
                    synchronized(item.ioLock) {
                        item.downloadedBytes -= segmentBytes
                        if (countedCell) {
                            item.cellBytes -= segmentBytes
                        } else {
                            item.wifiBytes -= segmentBytes
                        }
                    }
                    throw e
                } finally {
                    conn?.let {
                        session.connections -= it
                        it.disconnect()
                    }
                }
                if (session.stopped || !currentCoroutineContext().isActive) {
                    // Stopped mid-segment: the partial bytes go back
                    // out so the next run resumes at this segment.
                    raf.setLength(segmentStart)
                    synchronized(item.ioLock) {
                        item.downloadedBytes -= segmentBytes
                        if (countedCell) {
                            item.cellBytes -= segmentBytes
                        } else {
                            item.wifiBytes -= segmentBytes
                        }
                    }
                    return false
                }
                synchronized(item.ioLock) { item.hlsSegmentsDone = index + 1 }
                persist()
            }
        }
        synchronized(item.ioLock) { item.totalBytes = item.downloadedBytes }
        return true
    }

    /** Rename .part to the final name (collision-safe) and settle. */
    private fun finalizeDownload(item: DownloadItem) {
        val dir = downloadsDir()
        val part = File(dir, item.partFileName)
        var target = File(dir, item.fileName)
        if (target.exists()) {
            val unique = uniqueName(item.fileName)
            item.fileName = unique
            target = File(dir, unique)
        }
        if (part.exists()) {
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } else {
            target.outputStream().use { }
        }
        item.status = DownloadStatus.COMPLETED
        item.error = null
    }

    /**
     * Mirror the pool's live handles into Compose state for the UI.
     * Reseeds first: the pool's requestNetwork callbacks are not
     * guaranteed to deliver on every phone, so each refresh also
     * re-discovers handles from the live network list — a missing
     * handle shows up within one sampler tick (~1 s).
     */
    private fun refreshPathState() {
        pool.reseed()
        wifiPathUp = pool.wifiNetwork != null
        cellPathUp = pool.cellNetwork != null
    }

    /** ~2 Hz speed sampling (EMA-smoothed) + periodic persistence. */
    private suspend fun samplerLoop(session: RunSession) {
        val item = session.item
        var lastTotal = item.downloadedBytes
        var lastWifi = item.wifiBytes
        var lastCell = item.cellBytes
        var lastTime = System.nanoTime()
        var ticks = 0
        while (!session.stopped) {
            delay(SAMPLE_MS)
            refreshPathState()
            // Global stall detection for chunked downloads. A single
            // dead network path must never fail a run (its workers
            // hand their chunks back; the healthy path picks them
            // up) — but if NO path has written a byte for a long
            // stretch, the run is genuinely stuck, so fail it once
            // and stop cleanly instead of spinning forever.
            if (item.ranged && item.totalBytes > 0 &&
                System.currentTimeMillis() - session.lastProgressAtMs > STALL_LIMIT_MS
            ) {
                session.fail(
                    "No network path is making progress — download stalled. " +
                        "Resume to try again."
                )
            }
            val now = System.nanoTime()
            val dtSec = (now - lastTime) / 1_000_000_000.0
            if (dtSec <= 0) continue
            val total = item.downloadedBytes
            val wifi = item.wifiBytes
            val cell = item.cellBytes
            item.speedBps = ema(item.speedBps, ((total - lastTotal) / dtSec).toLong())
            item.wifiSpeedBps = ema(item.wifiSpeedBps, ((wifi - lastWifi) / dtSec).toLong())
            item.cellSpeedBps = ema(item.cellSpeedBps, ((cell - lastCell) / dtSec).toLong())
            item.dualNetwork = wifi > 0 && cell > 0
            lastTotal = total
            lastWifi = wifi
            lastCell = cell
            lastTime = now
            if (++ticks % 8 == 0) persist()
        }
    }

    private fun ema(previous: Long, instant: Long): Long =
        if (previous == 0L) instant else (instant * 6 + previous * 4) / 10

    /**
     * Open a connection for [item]'s own URL. With a [role], the
     * connection is bound to that role's network handle when one is
     * available (see [NetworkPool]); the returned network is the one
     * actually used (null = the system default route), which is what
     * bytes get counted under.
     */
    private fun openFor(item: DownloadItem, role: NetKind?): Pair<HttpURLConnection, Network?> =
        openForUrl(item, item.url, role)

    /**
     * [openFor] against an explicit URL: same timeouts, same
     * redirects, same UA / cookie / referer headers — only the
     * target differs. The HLS path (v1.3.2) uses it for the playlist
     * and for each segment, so a segment request is header-identical
     * to a request for the item's own URL.
     */
    private fun openForUrl(
        item: DownloadItem,
        urlString: String,
        role: NetKind?
    ): Pair<HttpURLConnection, Network?> {
        val network = role?.let { pool.networkFor(it) }
        val url = URL(urlString)
        val conn = (if (network != null) {
            network.openConnection(url)
        } else {
            url.openConnection()
        }) as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", item.userAgent ?: DEFAULT_UA)
        item.cookie?.takeIf { it.isNotBlank() }?.let {
            conn.setRequestProperty("Cookie", it)
        }
        // v1.3: page-enqueued downloads (fetcher / link menu) carry
        // the linking page as Referer — on this connection factory,
        // so the probe, the chunk workers and the single-stream
        // fallback all send it.
        item.referer?.takeIf { it.isNotBlank() }?.let {
            conn.setRequestProperty("Referer", it)
        }
        return conn to network
    }

    /**
     * All persistence funnels through the main-thread scope so the
     * store's JSON pass never races list edits or another save.
     */
    private fun persist() {
        scope.launch {
            persistMutex.withLock {
                store.saveDownloads(items)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: DownloadController? = null

        fun get(context: Context): DownloadController =
            instance ?: synchronized(this) {
                instance ?: DownloadController(context.applicationContext)
                    .also { instance = it }
            }

        private const val FILE_PROVIDER_AUTHORITY = "app.ypn.browser.fileprovider"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val CHUNK_BYTES = 2L * 1024 * 1024
        private const val MAX_CHUNKS = 128L
        private const val MAX_CHUNK_ATTEMPTS = 3
        private const val ROLE_SWAP_FAILURES = 3
        private const val MAX_PATH_ERROR_CHARS = 60
        private const val RETRY_DELAY_MS = 1_000L
        private const val CLAIM_WAIT_MS = 1_000L
        private const val STALL_LIMIT_MS = 120_000L
        private const val SAMPLE_MS = 500L
        private const val MAX_PLAYLIST_CHARS = 1_000_000
    }
}

/** Per-run engine state for one download. Lives only in memory. */
private class RunSession(val item: DownloadItem) {
    @Volatile
    var stopped = false

    @Volatile
    var failReason: String? = null

    /**
     * Wall-clock time of the last byte any worker wrote. Set at
     * session creation, reset when chunked transfer begins, and
     * updated inside the write loops' bookkeeping; the sampler fails
     * the run if this goes stale for too long in chunked mode.
     */
    @Volatile
    var lastProgressAtMs: Long = System.currentTimeMillis()

    var poolAcquired = false
    var job: Job? = null

    /** Chunk indices handed out in this run; guarded by itself. */
    val claimed = mutableSetOf<Int>()

    /** Live connections, so pause/fail can cut blocked reads short. */
    val connections: MutableSet<HttpURLConnection> =
        Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    fun fail(reason: String) {
        if (failReason == null) failReason = reason
        stop()
    }

    fun stop() {
        stopped = true
        synchronized(connections) {
            connections.forEach { conn ->
                try {
                    conn.disconnect()
                } catch (e: Exception) {
                    // Already gone — nothing to cut.
                }
            }
        }
        job?.cancel()
    }
}
