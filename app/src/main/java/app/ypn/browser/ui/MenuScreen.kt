package app.ypn.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ypn.browser.browser.BrowserController
import app.ypn.browser.download.DownloadController
import app.ypn.browser.ui.theme.LocalYpnPalette
import app.ypn.browser.ui.theme.YpnThemeId
import app.ypn.browser.ui.theme.ypnThemeAccent

private enum class MenuPage { ROOT, BOOKMARKS, HISTORY }

/**
 * The Menu — since Phase 4 the app's single settings home: Bookmarks,
 * History, the global Desktop mode toggle, the dual-network
 * acceleration toggle (Phase 3), and the theme picker, which lives
 * ONLY here now (it used to also sit on the Home screen). Since v1.1
 * the root page is arranged Pawxy-style: small caps group labels over
 * rounded cards (Appearance / Downloads / Browsing / Library), and
 * the Downloads group carries a Downloads row with the live
 * active-downloads badge.
 */
@Composable
fun MenuScreen(
    controller: BrowserController,
    downloads: DownloadController,
    themeId: YpnThemeId,
    onThemeChange: (YpnThemeId) -> Unit,
    onOpenUrl: (String) -> Unit,
    onShowDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    var page by remember { mutableStateOf(MenuPage.ROOT) }

    when (page) {
        MenuPage.BOOKMARKS -> BookmarksScreen(
            controller = controller,
            onOpen = onOpenUrl,
            onBack = { page = MenuPage.ROOT },
            modifier = modifier
        )
        MenuPage.HISTORY -> HistoryScreen(
            controller = controller,
            onOpen = onOpenUrl,
            onBack = { page = MenuPage.ROOT },
            modifier = modifier
        )
        MenuPage.ROOT -> MenuRoot(
            controller = controller,
            downloads = downloads,
            themeId = themeId,
            onThemeChange = onThemeChange,
            onShowBookmarks = { page = MenuPage.BOOKMARKS },
            onShowHistory = { page = MenuPage.HISTORY },
            onShowDownloads = onShowDownloads,
            modifier = modifier
        )
    }
}

@Composable
private fun MenuRoot(
    controller: BrowserController,
    downloads: DownloadController,
    themeId: YpnThemeId,
    onThemeChange: (YpnThemeId) -> Unit,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    onShowDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        Text(
            text = "Menu",
            color = colors.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp)
        )

        MenuGroup(label = "APPEARANCE") {
            ThemeSwatchRow(themeId = themeId, onThemeChange = onThemeChange)
        }

        MenuGroup(label = "DOWNLOADS") {

        // Dual-network acceleration — downloads pull over Wi-Fi and
        // mobile data at the same time (Phase 3's signature feature).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            // Top-aligned (v1.1.1): with centre alignment the icon
            // floated midway down the title + two wrapped captions.
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Dual-network acceleration",
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Downloads use Wi-Fi and mobile data together — " +
                        "faster, but mobile data is consumed",
                    color = palette.muted,
                    fontSize = 11.sp
                )
                Text(
                    text = "Takes effect on running downloads after Pause → Resume",
                    color = palette.muted,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = downloads.accelerationEnabled,
                onCheckedChange = { downloads.applyAccelerationEnabled(it) }
            )
        }
        GroupDivider()
        MenuRow(
            icon = Icons.Filled.Download,
            label = "Downloads",
            onClick = onShowDownloads,
            badgeCount = downloads.activeCount,
            horizontalPadding = 14.dp
        )
        }

        MenuGroup(label = "BROWSING") {
        // Desktop mode — global, persisted, applies to every open tab.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.DesktopWindows,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Desktop mode",
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Desktop user-agent and wide page view",
                    color = palette.muted,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = controller.desktopMode,
                onCheckedChange = { controller.applyDesktopMode(it) }
            )
        }
        }

        MenuGroup(label = "LIBRARY") {
            MenuRow(
                icon = Icons.Filled.Bookmark,
                label = "Bookmarks",
                onClick = onShowBookmarks,
                horizontalPadding = 14.dp
            )
            GroupDivider()
            MenuRow(
                icon = Icons.Filled.History,
                label = "History",
                onClick = onShowHistory,
                horizontalPadding = 14.dp
            )
        }

        Text(
            text = "YPN Browser — settings and themes live here, nowhere else.",
            color = palette.muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp)
        )
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    badgeCount: Int = 0,
    horizontalPadding: Dp = 16.dp
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = colors.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (badgeCount > 0) {
            Badge(
                containerColor = palette.data,
                contentColor = palette.onAccent
            ) { Text(badgeCount.toString()) }
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = palette.muted,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * One Pawxy-style settings group (v1.1): a small caps muted label
 * over a single rounded surface card holding the group's rows.
 */
@Composable
private fun MenuGroup(label: String, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = label,
        color = LocalYpnPalette.current.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.1.sp,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
    ) {
        Column { content() }
    }
}

/** Hairline between two rows inside a [MenuGroup] card. */
@Composable
private fun GroupDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

/**
 * The APPEARANCE group's theme picker, as drawn in the approved
 * v1.1 mockup (v1.1.1): a "Theme" row naming the current theme, and
 * beneath it one circular swatch per theme, filled with that theme's
 * accent colour (from the palette definitions, via [ypnThemeAccent]).
 * Since v1.2 the selected swatch wears the approved v1.2 mockup's
 * treatment — a thin outline, a white ring clear of the swatch, and
 * a white ✓ — because the old primary-coloured ring sat on the very
 * colour it was meant to stand out from and vanished.
 */
@Composable
private fun ThemeSwatchRow(themeId: YpnThemeId, onThemeChange: (YpnThemeId) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Theme",
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = themeId.label,
                color = palette.muted,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            YpnThemeId.entries.forEach { entry ->
                val selected = entry == themeId
                // Every swatch sits in the same 31dp slot so the row
                // never shifts when the selection moves. Unselected:
                // the plain 26dp circle, as before. Selected (v1.2):
                // a slightly smaller circle wrapped in a thin
                // outline and a 2dp white ring with a gap on each
                // side, and a white ✓ in the middle — a different
                // colour from the theme itself, visible on all four.
                Box(
                    modifier = Modifier.size(31.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(31.dp)
                                .border(
                                    1.dp,
                                    colors.onSurface.copy(alpha = 0.45f),
                                    CircleShape
                                )
                        )
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .border(2.dp, Color.White, CircleShape)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(if (selected) 23.dp else 26.dp)
                            .clip(CircleShape)
                            .background(ypnThemeAccent(entry))
                            .clickable { onThemeChange(entry) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Text(
                                text = "✓",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The theme picker — since Phase 4 this is its only home in the app
 * (the Menu). One row per theme, the current one checked.
 */
@Composable
fun ThemePicker(themeId: YpnThemeId, onThemeChange: (YpnThemeId) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = "Theme",
        color = colors.onBackground,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
    ) {
        ThemeRows(themeId = themeId, onThemeChange = onThemeChange)
    }
}

/**
 * The theme rows themselves (one per theme, the current one
 * checked) — shared by [ThemePicker]'s own card and, since v1.1, by
 * the Menu's APPEARANCE group card.
 */
@Composable
private fun ThemeRows(themeId: YpnThemeId, onThemeChange: (YpnThemeId) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Column {
            YpnThemeId.entries.forEachIndexed { index, entry ->
                val selected = entry == themeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onThemeChange(entry) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (selected) colors.primary else palette.surface2),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = entry.letter,
                            color = if (selected) palette.onAccent else colors.onSurface,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = entry.label,
                        color = colors.onSurface,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(Modifier.weight(1f))
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = colors.primary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                if (index < YpnThemeId.entries.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.outline)
                    )
                }
            }
    }
}
