package com.shelfscan.app.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfscan.app.data.Book
import com.shelfscan.app.ui.components.BookCover
import com.shelfscan.app.ui.components.EmptyState
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (Long) -> Unit,
    onScan: () -> Unit,
    vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::exportLibrary)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importLibrary)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Filled.CameraAlt, null) },
                text = { Text("Add books") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("My Library", style = MaterialTheme.typography.headlineLarge)
                    if (state.totalCount > 0) {
                        Text(
                            "${state.totalCount} books · ${state.authorCount} authors",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LibraryMenu(
                    canExport = state.totalCount > 0,
                    onExport = { exporter.launch("shelfscan-library-${LocalDate.now()}.json") },
                    // Some file managers label .json files as plain text or generic binary.
                    onImport = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                )
            }

            if (state.loaded && state.totalCount == 0) {
                EmptyState(
                    title = "Your shelf is empty",
                    body = "Photograph your books — covers or a whole row of spines — and ShelfScan will catalog them for you.",
                    action = "Scan my books",
                    onAction = onScan,
                )
                return@Column
            }

            SearchBar(state.query, vm::setQuery)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Grouping.entries.forEachIndexed { i, g ->
                    SegmentedButton(
                        selected = state.grouping == g,
                        onClick = { vm.setGrouping(g) },
                        shape = SegmentedButtonDefaults.itemShape(i, Grouping.entries.size),
                    ) { Text(g.label) }
                }
            }

            when {
                state.groups.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Search,
                    title = "No matches",
                    body = "Nothing on your shelf matches \"${state.query}\".",
                )
                state.grouping == Grouping.All -> BookGrid(state.groups.first().books, readingRow(state), onBookClick)
                else -> GroupedShelves(state.groups, readingRow(state), onBookClick)
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search title, author, genre") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear search") }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/** The "Currently reading" row: only when browsing (not searching) and something is being read. */
private fun readingRow(state: LibraryState): List<Book> = if (state.query.isBlank()) state.reading else emptyList()

@Composable
private fun LibraryMenu(canExport: Boolean, onExport: () -> Unit, onImport: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Library options") }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Export library") },
                leadingIcon = { Icon(Icons.Outlined.FileUpload, null) },
                enabled = canExport,
                onClick = { menuOpen = false; onExport() },
            )
            DropdownMenuItem(
                text = { Text("Import library") },
                leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                onClick = { menuOpen = false; onImport() },
            )
        }
    }
}

@Composable
private fun ReadingRow(books: List<Book>, onBookClick: (Long) -> Unit, sidePadding: Dp = 16.dp) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            "Currently reading", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = sidePadding, vertical = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = sidePadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { "reading_${it.id}" }) { book ->
                Column(Modifier.width(88.dp)) { GridBook(book) { onBookClick(book.id) } }
            }
        }
    }
}

@Composable
private fun BookGrid(books: List<Book>, reading: List<Book>, onBookClick: (Long) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (reading.isNotEmpty()) {
            // The grid already pads its sides, so the row adds none of its own.
            item(key = "reading", span = { GridItemSpan(maxLineSpan) }) { ReadingRow(reading, onBookClick, sidePadding = 0.dp) }
            item(key = "all_header", span = { GridItemSpan(maxLineSpan) }) {
                Text("All books", style = MaterialTheme.typography.titleMedium)
            }
        }
        items(books, key = { it.id }) { book -> GridBook(book) { onBookClick(book.id) } }
    }
}

@Composable
private fun GridBook(book: Book, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        BookCover(book.coverUrl, book.title, book.author, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(book.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Each author/genre is a horizontal shelf with a sticky label. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupedShelves(groups: List<ShelfGroup>, reading: List<Book>, onBookClick: (Long) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        if (reading.isNotEmpty()) item(key = "reading") { ReadingRow(reading, onBookClick) }
        groups.forEach { group ->
            stickyHeader(key = "h_${group.name}") {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                        Text(group.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${group.books.size}", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            item(key = "r_${group.name}") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    items(group.books, key = { it.id }) { book ->
                        Column(Modifier.width(100.dp)) { GridBook(book) { onBookClick(book.id) } }
                    }
                }
            }
        }
    }
}
