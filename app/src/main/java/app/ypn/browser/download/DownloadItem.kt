package app.ypn.browser.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

/** Which physical network carried a connection (for the split bar). */
enum class NetKind { WIFI, CELL }

/**
 * Progress of one byte range of a ranged download. [downloaded] counts
 * the bytes of this chunk already written into the .part file, so a
 * resume continues at `start + downloaded` instead of starting over.
 * Mutated by engine workers and read by the store, always under the
 * owning item's [DownloadItem.ioLock].
 */
class ChunkProgress(val index: Int, val start: Long, val endInclusive: Long) {
    @Volatile
    var downloaded: Long = 0

    val length: Long get() = endInclusive - start + 1
    val complete: Boolean get() = downloaded >= length
}

/**
 * One download. Identity and request data are fixed at enqueue time;
 * everything the UI reads is Compose snapshot state, updated by the
 * engine (from IO threads — snapshot state is safe for that). Plain
 * fields ([ranged], [chunks]) are engine/store bookkeeping only.
 */
class DownloadItem(
    val id: String,
    val url: String,
    fileName: String,
    val mimeType: String?,
    val userAgent: String?,
    val cookie: String?,
    val createdAt: Long
) {
    /** Final file name inside the downloads folder (collision-free). */
    var fileName by mutableStateOf(fileName)

    var status by mutableStateOf(DownloadStatus.QUEUED)

    /** Total size in bytes, or -1 while the server hasn't told us. */
    var totalBytes by mutableLongStateOf(-1L)
    var downloadedBytes by mutableLongStateOf(0L)

    /** Bytes actually written per network — the split bar's raw data. */
    var wifiBytes by mutableLongStateOf(0L)
    var cellBytes by mutableLongStateOf(0L)

    /** Smoothed live speeds (bytes/sec), maintained by the sampler. */
    var speedBps by mutableLongStateOf(0L)
    var wifiSpeedBps by mutableLongStateOf(0L)
    var cellSpeedBps by mutableLongStateOf(0L)

    /** True once both networks have carried bytes for this download. */
    var dualNetwork by mutableStateOf(false)

    var error by mutableStateOf<String?>(null)

    /** True when the server accepted Range requests (chunked mode). */
    var ranged: Boolean = false

    /** Chunk table for ranged downloads; guarded by [ioLock]. */
    val chunks = mutableListOf<ChunkProgress>()

    /** Guards [chunks], chunk counters and the byte counters. */
    val ioLock = Any()

    val partFileName: String get() = "$fileName.part"

    val isActive: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED

    /** 0f..1f, or 0f while the total is unknown. */
    val percent: Float
        get() = if (totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}
