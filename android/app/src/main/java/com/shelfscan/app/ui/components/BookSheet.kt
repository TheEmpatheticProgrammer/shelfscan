package com.shelfscan.app.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.shelfscan.app.data.remote.BookDto

/**
 * Everything about a book you don't own, without leaving the screen: cover, why it was suggested
 * (`note`), the description, and a way to find it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSheet(
    book: BookDto,
    note: String,
    onDismiss: () -> Unit,
    /** Whether it's on Want to read; null hides the button. */
    saved: Boolean? = null,
    onToggleSaved: () -> Unit = {},
    /** An extra main action, e.g. "I have this book" on the Want to read list. */
    primaryAction: Pair<String, () -> Unit>? = null,
) {
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            Row {
                BookCover(book.coverUrl, book.title, book.author, Modifier.width(96.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(book.author, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                    val facts = listOfNotNull(
                        book.publishedDate?.take(4),
                        book.pageCount?.takeIf { it > 0 }?.let { "$it pages" },
                    ).joinToString(" · ")
                    if (facts.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // The "why" is the most useful line here; a plain year adds nothing over the facts above.
            if (note.isNotBlank() && note != yearOf(book)) {
                Spacer(Modifier.height(16.dp))
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
            book.description?.takeIf { it.isNotBlank() }?.let { desc ->
                var expanded by remember(book) { mutableStateOf(false) }
                val text = remember(desc) { HtmlCompat.fromHtml(desc, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }
                Spacer(Modifier.height(16.dp))
                Text(
                    text, style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
                )
                if (!expanded && text.length > 300) {
                    TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(0.dp)) { Text("Read more") }
                }
            }
            Spacer(Modifier.height(20.dp))
            primaryAction?.let { (label, action) ->
                Button(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(label) }
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (saved != null) {
                    FilledTonalButton(onClick = onToggleSaved, modifier = Modifier.weight(1f)) {
                        Icon(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (saved) "Saved" else "Want to read")
                    }
                }
                OutlinedButton(
                    onClick = { uri.openUri("https://www.google.com/search?tbm=bks&q=" + Uri.encode("${book.title} ${book.author}")) },
                    modifier = Modifier.weight(1f),
                ) { Text("Find this book") }
            }
        }
    }
}

fun yearOf(book: BookDto): String = book.publishedDate?.take(4)?.takeIf { it.length == 4 }.orEmpty()
