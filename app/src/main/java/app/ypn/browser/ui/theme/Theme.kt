package app.ypn.browser.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The four approved design-mockup themes (artifact "browser-app-design-mockups").
 * All hex values below are taken verbatim from the mockups' per-theme CSS
 * variable blocks (--bg / --surface / --surface2 / --ink / --muted / --accent /
 * --wifi / --data / --btn-ink / --badge-ink). The mockups' --line colour is a
 * translucent rgba(); for Material3's solid `outline` slot it is pre-blended
 * over the theme background (values noted per theme).
 */
enum class YpnThemeId(val id: String, val label: String, val letter: String) {
    MIDNIGHT_CYAN("midnight", "Midnight Cyan", "A"),
    EMBER("ember", "Ember", "B"),
    ROYAL_PURPLE("purple", "Royal Purple", "C"),
    DAYLIGHT("daylight", "Daylight", "D");

    companion object {
        fun fromId(id: String?): YpnThemeId = entries.firstOrNull { it.id == id } ?: MIDNIGHT_CYAN
    }
}

/**
 * Colours the mockups use that have no Material3 slot: the split-bar's
 * Wi-Fi / mobile-data pair, the second surface level, and muted text.
 */
data class YpnPalette(
    val wifi: Color,
    val data: Color,
    val surface2: Color,
    val muted: Color,
    val onAccent: Color
)

val LocalYpnPalette = staticCompositionLocalOf {
    YpnPalette(
        wifi = Color(0xFF22D3EE),
        data = Color(0xFF34D399),
        surface2 = Color(0xFF1A2C4B),
        muted = Color(0xFF8BA3C0),
        onAccent = Color(0xFF06222B)
    )
}

// A — Midnight Cyan: bg #0B1220, surface #14233D, surface2 #1A2C4B,
// ink #E8F0FA, muted #8BA3C0, accent/wifi #22D3EE, data #34D399,
// btn-ink #06222B. Line rgba(139,163,192,.22) over bg ≈ #273243.
private val MidnightCyanColors = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF06222B),
    primaryContainer = Color(0xFF1A2C4B),
    onPrimaryContainer = Color(0xFFE8F0FA),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF06222B),
    secondaryContainer = Color(0xFF1A2C4B),
    onSecondaryContainer = Color(0xFFE8F0FA),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE8F0FA),
    surface = Color(0xFF14233D),
    surfaceTint = Color(0xFF14233D),
    onSurface = Color(0xFFE8F0FA),
    surfaceVariant = Color(0xFF1A2C4B),
    onSurfaceVariant = Color(0xFF8BA3C0),
    outline = Color(0xFF273243),
    error = Color(0xFFF0A3A3)
)
private val MidnightCyanPalette = YpnPalette(
    wifi = Color(0xFF22D3EE),
    data = Color(0xFF34D399),
    surface2 = Color(0xFF1A2C4B),
    muted = Color(0xFF8BA3C0),
    onAccent = Color(0xFF06222B)
)

// B — Ember: bg #161110, surface #251B16, surface2 #2F221B,
// ink #F7EFE7, muted #B9A795, accent/wifi #FB923C, data #FACC15,
// btn-ink #2B1503. Line rgba(185,167,149,.22) over bg ≈ #3A322D.
private val EmberColors = darkColorScheme(
    primary = Color(0xFFFB923C),
    onPrimary = Color(0xFF2B1503),
    primaryContainer = Color(0xFF2F221B),
    onPrimaryContainer = Color(0xFFF7EFE7),
    secondary = Color(0xFFFACC15),
    onSecondary = Color(0xFF2B1503),
    secondaryContainer = Color(0xFF2F221B),
    onSecondaryContainer = Color(0xFFF7EFE7),
    background = Color(0xFF161110),
    onBackground = Color(0xFFF7EFE7),
    surface = Color(0xFF251B16),
    surfaceTint = Color(0xFF251B16),
    onSurface = Color(0xFFF7EFE7),
    surfaceVariant = Color(0xFF2F221B),
    onSurfaceVariant = Color(0xFFB9A795),
    outline = Color(0xFF3A322D),
    error = Color(0xFFF0A3A3)
)
private val EmberPalette = YpnPalette(
    wifi = Color(0xFFFB923C),
    data = Color(0xFFFACC15),
    surface2 = Color(0xFF2F221B),
    muted = Color(0xFFB9A795),
    onAccent = Color(0xFF2B1503)
)

// C — Royal Purple: bg #150A2E, surface #23134A, surface2 #2C1A5C,
// ink #F0EAFD, muted #AB9FD2, accent/wifi #A78BFA, data #F472B6,
// btn-ink #21103F, badge-ink #2E0A1E. Line rgba(171,159,210,.24) over bg ≈ #392E55.
private val RoyalPurpleColors = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF21103F),
    primaryContainer = Color(0xFF2C1A5C),
    onPrimaryContainer = Color(0xFFF0EAFD),
    secondary = Color(0xFFF472B6),
    onSecondary = Color(0xFF2E0A1E),
    secondaryContainer = Color(0xFF2C1A5C),
    onSecondaryContainer = Color(0xFFF0EAFD),
    background = Color(0xFF150A2E),
    onBackground = Color(0xFFF0EAFD),
    surface = Color(0xFF23134A),
    surfaceTint = Color(0xFF23134A),
    onSurface = Color(0xFFF0EAFD),
    surfaceVariant = Color(0xFF2C1A5C),
    onSurfaceVariant = Color(0xFFAB9FD2),
    outline = Color(0xFF392E55),
    error = Color(0xFFF0A3A3)
)
private val RoyalPurplePalette = YpnPalette(
    wifi = Color(0xFFA78BFA),
    data = Color(0xFFF472B6),
    surface2 = Color(0xFF2C1A5C),
    muted = Color(0xFFAB9FD2),
    onAccent = Color(0xFF21103F)
)

// D — Daylight: bg #F2F4F7, surface #FFFFFF, surface2 #E8EDF3,
// ink #16202E, muted #5D6D7F, accent/wifi #2563EB, data #0D9488,
// btn-ink #FFFFFF. Line rgba(93,109,127,.25) over bg ≈ #CDD2D9.
private val DaylightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8EDF3),
    onPrimaryContainer = Color(0xFF16202E),
    secondary = Color(0xFF0D9488),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8EDF3),
    onSecondaryContainer = Color(0xFF16202E),
    background = Color(0xFFF2F4F7),
    onBackground = Color(0xFF16202E),
    surface = Color(0xFFFFFFFF),
    surfaceTint = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16202E),
    surfaceVariant = Color(0xFFE8EDF3),
    onSurfaceVariant = Color(0xFF5D6D7F),
    outline = Color(0xFFCDD2D9),
    error = Color(0xFF9F4747)
)
private val DaylightPalette = YpnPalette(
    wifi = Color(0xFF2563EB),
    data = Color(0xFF0D9488),
    surface2 = Color(0xFFE8EDF3),
    muted = Color(0xFF5D6D7F),
    onAccent = Color(0xFFFFFFFF)
)

@Composable
fun YpnBrowserTheme(themeId: YpnThemeId = YpnThemeId.MIDNIGHT_CYAN, content: @Composable () -> Unit) {
    val colors = when (themeId) {
        YpnThemeId.MIDNIGHT_CYAN -> MidnightCyanColors
        YpnThemeId.EMBER -> EmberColors
        YpnThemeId.ROYAL_PURPLE -> RoyalPurpleColors
        YpnThemeId.DAYLIGHT -> DaylightColors
    }
    val palette = when (themeId) {
        YpnThemeId.MIDNIGHT_CYAN -> MidnightCyanPalette
        YpnThemeId.EMBER -> EmberPalette
        YpnThemeId.ROYAL_PURPLE -> RoyalPurplePalette
        YpnThemeId.DAYLIGHT -> DaylightPalette
    }
    CompositionLocalProvider(LocalYpnPalette provides palette) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
