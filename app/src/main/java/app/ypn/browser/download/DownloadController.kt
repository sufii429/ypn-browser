package app.ypn.browser.download

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    fun setAccelerationEnabled(enabled: Boolean) {
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
            suggestedName = URLUtil.guessFileName(url, contentDisposition, mimeType),
            mimeType = mimeType,
            userAgent = userAgent,
            cookie = cookie
        )
    }

    /** From the Downloads screen's paste-a-URL box. False = not a URL. */
    fun addManualDownload(raw: String): Boolean {
        val url = normalizeDownloadUrl(raw) ?: return false
        enqueue(
            url = url,
            suggestedName = URLUtil.guessFileName(url, null, null),
            mimeType = null,
            userAgent = null,
            cookie = null
        )
        return true
    }

    private fun enqueue(
        url: String,
        suggestedName: String,
        mimeType: String?,
        userAgent: String?,
        cookie: String?
    ) {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = uniqueName(sanitizeFileName(suggestedName, mimeType)),
            mimeType = mimeType,
            userAgent = userAgent,
            cookie = cookie,
            createdAt = System.currentTimeMillis()
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
        item.status = DownloadStatus.DOWNLOADING
        session.poolAcquired = accelerationEnabled
        if (session.poolAcquired) pool.acquire()
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
                    if (item.totalBytes < 0) probe(item)
                    when {
                        item.totalBytes == 0L -> {
                            // An empty file has nothing to transfer.
                            partFile(item).outputStream().use { }
                            completed = true
                        }
                        item.ranged && item.totalBytes > 0 -> {
                            prepareChunks(item)
                            val workers = workerRoles().map { role ->
                                launch { chunkWorker(session, role) }
                            }
                            workers.forEach { it.join() }
                            completed = !session.stopped && session.failReason == null &&
                                synchronized(item.ioLock) { item.chunks.all { it.complete } }
                        }
                        else -> completed = singleStream(session)
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
        while (!session.stopped) {
            val chunk = claimChunk(session) ?: return
            var attempts = 0
            while (!chunk.complete && !session.stopped) {
                val before = chunk.downloaded
                try {
                    streamChunkRange(session, chunk, part, role)
                } catch (e: IOException) {
                    // Counted as a no-progress attempt below.
                }
                if (!chunk.complete && !session.stopped) {
                    if (chunk.downloaded == before) {
                        attempts++
                        if (attempts >= MAX_CHUNK_ATTEMPTS) {
                            session.fail(
                                "Connection kept failing (${item.fileName}). " +
                                    "Resume to try again."
                            )
                            return
                        }
                        delay(RETRY_DELAY_MS)
                    } else {
                        attempts = 0
                    }
                }
            }
            if (chunk.complete) persist()
        }
    }

    private fun claimChunk(session: RunSession): ChunkProgress? {
        val item = session.item
        synchronized(session.claimed) {
            synchronized(item.ioLock) {
                val next = item.chunks.firstOrNull { chunk ->
                    !chunk.complete && chunk.index !in session.claimed
                } ?: return null
                session.claimed += next.index
                return next
            }
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
        val (conn, network) = openFor(item, role)
        session.connections += conn
        try {
            conn.setRequestProperty("Range", "bytes=$from-${chunk.endInclusive}")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code on a range request")
            }
            val kind = pool.kindOf(network)
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(from)
                conn.inputStream.use { input ->
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
                            if (kind == NetKind.CELL) {
                                item.cellBytes += read
                            } else {
                                item.wifiBytes += read
                            }
                        }
                    }
                }
            }
        } finally {
            session.connections -= conn
            conn.disconnect()
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
        val (conn, network) = openFor(item, role)
        session.connections += conn
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val length = conn.contentLengthLong
            if (length > 0) item.totalBytes = length
            val kind = pool.kindOf(network)
            var written = 0L
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(0)
                conn.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (session.stopped) break
                        val read = input.read(buffer)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        written += read
                        synchronized(item.ioLock) {
                            item.downloadedBytes += read
                            if (kind == NetKind.CELL) {
                                item.cellBytes += read
                            } else {
                                item.wifiBytes += read
                            }
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
        } finally {
            session.connections -= conn
            conn.disconnect()
        }
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
     * Open a connection for [item]. With a [role], the connection is
     * bound to that role's network handle when one is available (see
     * [NetworkPool]); the returned network is the one actually used
     * (null = the system default route), which is what bytes get
     * counted under.
     */
    private fun openFor(item: DownloadItem, role: NetKind?): Pair<HttpURLConnection, Network?> {
        val network = role?.let { pool.networkFor(it) }
        val url = URL(item.url)
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
        private const val RETRY_DELAY_MS = 1_000L
        private const val SAMPLE_MS = 500L
    }
}

/** Per-run engine state for one download. Lives only in memory. */
private class RunSession(val item: DownloadItem) {
    @Volatile
    var stopped = false

    @Volatile
    var failReason: String? = null

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
