package app.ypn.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ypn.browser.ui.theme.LocalYpnPalette
import app.ypn.browser.ui.theme.YpnThemeId

/**
 * Phase 0 static home screen — a Compose port of the approved design
 * mockups ("browser-app-design-mockups"). Every value on it (VPN state,
 * server, download numbers) is a placeholder sample from the mockups;
 * nothing here is wired to a browser engine, VPN, or download yet.
 * Theme switching re-skins the whole screen via YpnBrowserTheme.
 */
@Composable
fun HomeScreen(
    themeId: YpnThemeId,
    onThemeChange: (YpnThemeId) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        AppHeader()
        AddressBar()
        VpnCard()
        DownloadCard()
        QuickTiles()
        ThemePicker(themeId = themeId, onThemeChange = onThemeChange)
        Text(
            text = "Phase 0 preview — VPN, browsing and downloads are placeholders.",
            color = LocalYpnPalette.current.muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp)
        )
    }
}

@Composable
private fun AppHeader() {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildAnnotatedString {
                append("YPN ")
                withStyle(SpanStyle(color = colors.primary)) { append("Browser") }
            },
            color = colors.onBackground,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(palette.surface2)
                .border(1.dp, colors.outline, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = "VPN",
                tint = colors.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AddressBar() {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(999.dp))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "kitaabcha.blogspot.com",
            color = colors.onSurface,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .border(1.5.dp, LocalYpnPalette.current.muted, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "3", color = colors.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VpnCard() {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(palette.data.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(palette.data)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "VPN ON",
                    color = colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.weight(1f))
                Text(text = "🇯🇵", fontSize = 16.sp)
            }
            Text(
                text = "Japan · Tokyo",
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 7.dp)
            )
            Text(
                text = buildAnnotatedString {
                    append("VPN Gate server · ")
                    withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)) {
                        append("TCP 443")
                    }
                    append(" · connected 12 min")
                },
                color = palette.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            OutlinedButton(
                onClick = { /* Phase 2: disconnect the SSTP tunnel */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 11.dp),
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, colors.primary)
            ) {
                Text(text = "Disconnect", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DownloadCard() {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "tiny-troopers-2.apk",
                        color = colors.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "412 MB of 634 MB · 65%",
                        color = palette.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = "Pause",
                        tint = palette.onAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // Split bar: Wi-Fi share (38%) and mobile-data share (27%) of the
            // file so far, the rest still to go — the mockups' signature bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 11.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.surface2)
            ) {
                Box(Modifier.weight(38f).fillMaxHeight().background(palette.wifi))
                Box(Modifier.weight(27f).fillMaxHeight().background(palette.data))
                Box(Modifier.weight(35f).fillMaxHeight())
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LegendItem(color = palette.wifi, label = "Wi-Fi · 38%")
                LegendItem(color = palette.data, label = "Mobile Data · 27%")
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 9.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.surface2)
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = palette.wifi)) { append("Wi-Fi 18 Mbps") }
                        append(" + ")
                        withStyle(SpanStyle(color = palette.data)) { append("Data 12 Mbps") }
                        append(" = ")
                        withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.ExtraBold)) {
                            append("30 Mbps")
                        }
                    },
                    color = colors.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, color = LocalYpnPalette.current.muted, fontSize = 11.sp)
    }
}

@Composable
private fun QuickTiles() {
    val colors = MaterialTheme.colorScheme
    val tiles = listOf(
        "▶" to "YouTube",
        "M" to "mrjobs.top",
        "K" to "Kitaabcha",
        "W" to "Wikipedia"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        tiles.forEach { (glyph, label) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.outline, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = glyph,
                        color = colors.primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    text = label,
                    color = LocalYpnPalette.current.muted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun ThemePicker(themeId: YpnThemeId, onThemeChange: (YpnThemeId) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
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
}
