package app.ypn.browser.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * One Speed Dial tile on the Home screen: the name shown under the
 * tile, the URL it opens, the glyph drawn on it, and its glyph
 * colour. [colorIndex] < 0 means "the theme accent" (the original
 * four tiles); >= 0 indexes the Home screen's custom-tile colours,
 * picked from the name when the tile is saved.
 */
data class SpeedDialTile(
    val name: String,
    val url: String,
    val glyph: String = "",
    val colorIndex: Int = -1
)

/**
 * The Home Speed Dial list (v1.2 — Mazeen's own tiles, replacing the
 * four hard-coded quick tiles), persisted as a small JSON array in
 * the same "ypn-browser" SharedPreferences file everything else
 * uses — the BrowserStore / DownloadStore pattern, no database.
 *
 * [loadTiles] returns null while the key has never been written, so
 * the caller can tell "first run" apart from "the user removed
 * every tile": first run seeds [SEED_TILES] and saves them, after
 * which an empty saved list stays empty.
 */
class SpeedDialStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ypn-browser", Context.MODE_PRIVATE)

    fun loadTiles(): List<SpeedDialTile>? {
        val raw = prefs.getString(KEY_TILES, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val url = o.optString("url")
                if (name.isEmpty() || url.isEmpty()) {
                    null
                } else {
                    SpeedDialTile(
                        name = name,
                        url = url,
                        glyph = o.optString("glyph"),
                        colorIndex = o.optInt("color", -1)
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveTiles(tiles: List<SpeedDialTile>) {
        val arr = JSONArray()
        tiles.forEach { tile ->
            arr.put(
                JSONObject()
                    .put("name", tile.name)
                    .put("url", tile.url)
                    .put("glyph", tile.glyph)
                    .put("color", tile.colorIndex)
            )
        }
        prefs.edit().putString(KEY_TILES, arr.toString()).apply()
    }

    companion object {
        private const val KEY_TILES = "speed_dial_tiles"

        /**
         * The original Phase 1 quick tiles, kept as the first-run
         * seed so v1.2 opens with exactly the Home screen v1.1.2
         * had: same glyphs (▶ for YouTube, first letters for the
         * rest) and the theme accent as their glyph colour.
         */
        val SEED_TILES: List<SpeedDialTile> = listOf(
            SpeedDialTile("YouTube", "https://www.youtube.com", "▶", -1),
            SpeedDialTile("mrjobs.top", "https://mrjobs.top", "M", -1),
            SpeedDialTile("Kitaabcha", "https://kitaabcha.blogspot.com", "K", -1),
            SpeedDialTile("Wikipedia", "https://www.wikipedia.org", "W", -1)
        )
    }
}
