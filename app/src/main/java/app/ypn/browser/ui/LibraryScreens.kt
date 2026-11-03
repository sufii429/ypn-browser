package app.ypn.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ypn.browser.browser.BrowserController
import app.ypn.browser.data.SavedPage
import app.ypn.browser.ui.theme.LocalYpnPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bookmarks list: tap to open, trash icon to delete (Phase 1). */
@Composable
fun BookmarksScreen(
    controller: BrowserController,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    LibraryScaffold(title = "Bookmarks", onBack = onBack, modifier = modifier) {
        if (controller.bookmarks.isEmpty()) {
            EmptyNote("No bookmarks yet. Star a page to save it here.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(controller.bookmarks, key = { it.url }) { page ->
                    PageRow(
                        page = page,
                        showTime = false,
                        onClick = { onOpen(page.url) },
                        onDelete = { controller.removeBookmark(page.url) }
                    )
                }
            }
        }
    }
}

/** History list: tap to open, Clear all at the top (Phase 1). */
@Composable
fun HistoryScreen(
    controller: BrowserController,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    LibraryScaffold(title = "History", onBack = onBack, modifier = modifier) {
        if (controller.history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { controller.clearHistory() }) {
                    Text(text = "Clear all", color = colors.primary, fontSize = 13.sp)
                }
            }
        }
        if (controller.history.isEmpty()) {
            EmptyNote("Nothing visited yet.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(controller.history, key = { it.url + it.time }) { page ->
                    PageRow(
                        page = page,
                        showTime = true,
                        onClick = { onOpen(page.url) },
                        onDelete = null
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.onSurface
                )
            }
            Text(
                text = title,
                color = colors.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        content()
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = LocalYpnPalette.current.muted, fontSize = 13.sp)
    }
}

@Composable
private fun PageRow(
    page: SavedPage,
    showTime: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = page.title.ifBlank { page.url },
                    color = colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (showTime) {
                        page.url + " · " + formatTime(page.time)
                    } else {
                        page.url
                    },
                    color = palette.muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = palette.muted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outline)
        )
    }
}

private fun formatTime(time: Long): String =
    SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(time))
