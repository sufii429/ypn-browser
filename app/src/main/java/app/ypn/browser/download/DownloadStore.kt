package app.ypn.browser.download

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Download metadata + per-chunk progress, persisted as one JSON array
 * in the app's existing "ypn-browser" SharedPreferences file (the same
 * lightweight pattern as BrowserStore — no database). Chunk counters
 * let a paused download resume exactly where it stopped, even across
 * an app restart.
 */
class DownloadStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ypn-browser", Context.MODE_PRIVATE)

    var acceleratorEnabled: Boolean
        get() = prefs.getBoolean(KEY_ACCELERATOR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ACCELERATOR, value).apply()
        }

    fun loadDownloads(): List<DownloadItem> {
        val raw = prefs.getString(KEY_DOWNLOADS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val url = o.optString("url")
                val name = o.optString("name")
                if (url.isEmpty() || name.isEmpty()) return@mapNotNull null
                val item = DownloadItem(
                    id = o.optString("id").ifEmpty { "dl-${o.optLong("created")}-$i" },
                    url = url,
                    fileName = name,
                    mimeType = o.optString("mime").ifEmpty { null },
                    userAgent = o.optString("ua").ifEmpty { null },
                    cookie = o.optString("cookie").ifEmpty { null },
                    createdAt = o.optLong("created"),
                    referer = o.optString("referer").ifEmpty { null }
                )
                item.totalBytes = o.optLong("total", -1L)
                item.downloadedBytes = o.optLong("downloaded", 0L)
                item.wifiBytes = o.optLong("wifi", 0L)
                item.cellBytes = o.optLong("cell", 0L)
                item.dualNetwork = o.optBoolean("dual", false)
                item.ranged = o.optBoolean("ranged", false)
                item.error = o.optString("error").ifEmpty { null }
                // A download that was mid-flight when the process died
                // comes back PAUSED with its progress intact — the user
                // (not a zombie service) decides when it continues.
                item.status = when (o.optString("status")) {
                    "COMPLETED" -> DownloadStatus.COMPLETED
                    "FAILED" -> DownloadStatus.FAILED
                    else -> DownloadStatus.PAUSED
                }
                if (item.ranged) {
                    val chunks = o.optJSONArray("chunks")
                    if (chunks != null) {
                        synchronized(item.ioLock) {
                            for (c in 0 until chunks.length()) {
                                val co = chunks.getJSONObject(c)
                                item.chunks += ChunkProgress(
                                    index = co.optInt("i"),
                                    start = co.optLong("s"),
                                    endInclusive = co.optLong("e")
                                ).also { it.downloaded = co.optLong("d", 0L) }
                            }
                        }
                    }
                }
                item
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveDownloads(items: List<DownloadItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            val o = JSONObject()
                .put("id", item.id)
                .put("url", item.url)
                .put("name", item.fileName)
                .put("mime", item.mimeType ?: "")
                .put("ua", item.userAgent ?: "")
                .put("cookie", item.cookie ?: "")
                .put("referer", item.referer ?: "")
                .put("created", item.createdAt)
                .put("total", item.totalBytes)
                .put("downloaded", item.downloadedBytes)
                .put("wifi", item.wifiBytes)
                .put("cell", item.cellBytes)
                .put("dual", item.dualNetwork)
                .put("ranged", item.ranged)
                .put("error", item.error ?: "")
                .put("status", item.status.name)
            if (item.ranged) {
                val chunks = JSONArray()
                synchronized(item.ioLock) {
                    item.chunks.forEach { chunk ->
                        chunks.put(
                            JSONObject()
                                .put("i", chunk.index)
                                .put("s", chunk.start)
                                .put("e", chunk.endInclusive)
                                .put("d", chunk.downloaded)
                        )
                    }
                }
                o.put("chunks", chunks)
            }
            arr.put(o)
        }
        prefs.edit().putString(KEY_DOWNLOADS, arr.toString()).apply()
    }

    private companion object {
        const val KEY_DOWNLOADS = "downloads"
        const val KEY_ACCELERATOR = "download_accelerator"
    }
}
