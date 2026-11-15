package com.shelfscan.app.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfscan.app.data.Book
import com.shelfscan.app.data.remote.RecommendationDto
import com.shelfscan.app.ui.components.BookCover
import com.shelfscan.app.ui.components.BookSheet
import com.shelfscan.app.ui.components.EditBookDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onBack: () -> Unit,
    onGenreClick: (String) -> Unit,
    onMoreByAuthor: (String) -> Unit,
    vm: BookDetailViewModel = viewModel(factory = BookDetailViewModel.Factory),
) {
    val book by vm.book.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<RecommendationDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { editing = true }, enabled = book != null) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = true }, enabled = book != null) { Icon(Icons.Filled.Delete, "Remove from library") }
                },
            )
        },
    ) { padding ->
        val b = book ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Header(b, onMoreByAuthor, Modifier.padding(horizontal = 20.dp))
            if (b.genres.isNotEmpty()) GenreChips(b.genres, onGenreClick, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
            About(b, extras.synopsis, Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp))
            Similar(
                extras, onRetry = vm::retrySimilar, onOpen = { sheetFor = it },
                modifier = Modifier.padding(top = 28.dp),
            )
            b.isbn?.let {
                Text(
                    "ISBN $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 20.dp, top = 28.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        if (editing) {
            EditBookDialog(
                title = b.title, author = b.author, genres = b.genres,
                onDismiss = { editing = false },
                onSave = { t, a, g -> vm.save(t, a, g); editing = false },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Remove this book?") },
                text = { Text("\"${b.title}\" will be removed from your library.") },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; vm.delete(onDone = onBack) }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
    }

    sheetFor?.let { rec -> BookSheet(rec.book, rec.reason, onDismiss = { sheetFor = null }) }
}

@Composable
private fun Header(b: Book, onMoreByAuthor: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier) {
        BookCover(b.coverUrl, b.title, b.author, Modifier.width(132.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(b.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(b.author, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
            val facts = listOfNotNull(
                b.publishedDate?.take(4)?.takeIf { it.length == 4 },
                b.pageCount?.takeIf { it > 0 }?.let { "$it pages" },
            ).joinToString(" · ")
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val author = b.authors.first()
            if (!author.equals("Unknown", ignoreCase = true)) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onMoreByAuthor(author) }, contentPadding = PaddingValues(0.dp)) {
                    Text("More by $author", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChips(genres: List<String>, onClick: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        genres.forEach { g -> AssistChip(onClick = { onClick(g) }, label = { Text(g) }) }
    }
}

/** The synopsis first; the full description is one tap away for anyone who wants it. */
@Composable
private fun About(b: Book, state: Part, modifier: Modifier = Modifier) {
    val synopsis = b.synopsis?.takeIf { it.isNotBlank() }
    val full = remember(b.description) {
        b.description?.takeIf { it.isNotBlank() }
            ?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }
    }
    val loading = synopsis == null && state == Part.Loading
    if (synopsis == null && full == null && !loading) return // nothing known about this book: skip the section

    var expanded by rememberSaveable(b.matchKey) { mutableStateOf(false) }
    Column(modifier.animateContentSize()) {
        Text("About", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        when {
            synopsis != null -> Text(synopsis, style = MaterialTheme.typography.bodyLarge)
            loading -> TextPlaceholder(lines = 3)
            // No synopsis could be made: fall back to the start of the full description.
            else -> Text(full!!, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        // Only offer the full text when it adds something beyond what's already shown.
        if (full != null && synopsis != null && full != synopsis) {
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Text(full, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "Show less" else "Full description")
            }
        }
    }
}

@Composable
private fun Similar(extras: Extras, onRetry: () -> Unit, onOpen: (RecommendationDto) -> Unit, modifier: Modifier = Modifier) {
    // Nothing similar found: leave the section out rather than show an empty shelf.
    if (extras.similarState == Part.Ready && extras.similar.isEmpty()) return
    Column(modifier) {
        Text("Similar books", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        when (extras.similarState) {
            Part.Failed -> TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Couldn't load similar books. Try again")
            }
            Part.Loading -> LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                userScrollEnabled = false,
            ) {
                items(4) { CoverPlaceholder() }
            }
            Part.Ready -> LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(extras.similar, key = { i, r -> "$i|${r.book.title}|${r.book.author}" }) { _, rec ->
                    Column(Modifier.width(SIMILAR_WIDTH).clip(RoundedCornerShape(8.dp)).clickable { onOpen(rec) }) {
                        BookCover(rec.book.coverUrl, rec.book.title, rec.book.author, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(rec.book.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            rec.book.author, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private val SIMILAR_WIDTH = 104.dp

@Composable
private fun CoverPlaceholder() {
    Column(Modifier.width(SIMILAR_WIDTH)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.height(8.dp))
        PlaceholderBar(0.9f)
        Spacer(Modifier.height(4.dp))
        PlaceholderBar(0.6f)
    }
}

@Composable
private fun TextPlaceholder(lines: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) { i -> PlaceholderBar(if (i == lines - 1) 0.6f else 1f, height = 14) }
    }
}

@Composable
private fun PlaceholderBar(fraction: Float, height: Int = 10) {
    Box(
        Modifier.fillMaxWidth(fraction).height(height.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
