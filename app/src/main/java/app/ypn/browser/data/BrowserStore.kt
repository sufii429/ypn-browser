package app.ypn.browser.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One saved page: a bookmark or a history entry. */
data class SavedPage(val title: String, val url: String, val time: Long)

/**
 * Bookmarks, history and the desktop-mode flag, persisted in the same
 * SharedPreferences file the theme choice already uses ("ypn-browser").
 * Lists are stored as small JSON arrays — no database, no DataStore,
 * same lightweight pattern as Phase 0.
 */
class BrowserStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ypn-browser", Context.MODE_PRIVATE)

    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DESKTOP, value).apply()
        }

    fun loadBookmarks(): List<SavedPage> = loadList(KEY_BOOKMARKS)

    fun saveBookmarks(items: List<SavedPage>) = saveList(KEY_BOOKMARKS, items)

    fun loadHistory(): List<SavedPage> = loadList(KEY_HISTORY)

    fun saveHistory(items: List<SavedPage>) = saveList(KEY_HISTORY, items)

    private fun loadList(key: String): List<SavedPage> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedPage(
                    title = o.optString("title"),
                    url = o.optString("url"),
                    time = o.optLong("time")
                )
            }.filter { it.url.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveList(key: String, items: List<SavedPage>) {
        val arr = JSONArray()
        items.forEach { page ->
            arr.put(
                JSONObject()
                    .put("title", page.title)
                    .put("url", page.url)
                    .put("time", page.time)
            )
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private companion object {
        const val KEY_BOOKMARKS = "bookmarks"
        const val KEY_HISTORY = "history"
        const val KEY_DESKTOP = "desktop_mode"
    }
}
