package com.shelfscan.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfscan.app.data.Book
import com.shelfscan.app.data.ReadingStatus
import com.shelfscan.app.ui.components.BookCover
import com.shelfscan.app.ui.components.EmptyState
import java.time.LocalDate

/** How a book in the grid or a shelf responds to taps: open it, or (in selection mode) pick it. */
private class BookTaps(val selecting: Boolean, val selected: Set<Long>, val open: (Long) -> Unit, val toggle: (Long) -> Unit) {
    fun tap(id: Long) = if (selecting) toggle(id) else open(id)
    fun longPress(id: Long) = toggle(id)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (Long) -> Unit,
    onScan: () -> Unit,
    vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.notices.collect { n ->
            val result = snackbar.showSnackbar(n.text, actionLabel = n.actionLabel, withDismissAction = n.actionLabel != null)
            if (result == SnackbarResult.ActionPerformed) n.action?.invoke()
        }
    }
    BackHandler(enabled = state.selecting) { vm.clearSelection() }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::exportLibrary)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importLibrary)
    }
    val taps = BookTaps(state.selecting, state.selected, onBookClick, vm::toggleSelected)

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!state.selecting) {
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    icon = { Icon(Icons.Filled.CameraAlt, null) },
                    text = { Text("Add books") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.selecting) {
                SelectionBar(
                    count = state.selected.size,
                    onClose = vm::clearSelection,
                    onMark = vm::markSelected,
                    onDelete = { confirmDelete = true },
                )
            } else {
                Header(
                    state,
                    onSort = vm::setSort,
                    onFilter = vm::setFilter,
                    onShare = { sharing = true },
                    onExport = { exporter.launch("shelfsight-library-${LocalDate.now()}.json") },
                    // Some file managers label .json files as plain text or generic binary.
                    onImport = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                )
            }

            if (state.scansToReview > 0 && !state.selecting) ScansReadyBanner(state.scansToReview, onScan)

            if (state.loaded && state.totalCount == 0) {
                EmptyState(
                    title = "Your shelf is empty",
                    body = "Photograph your books (covers or a whole row of spines), scan a barcode, or type a title.",
                    action = "Add books",
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
            if (state.options.filter != StatusFilter.All) {
                // A visible, one-tap way out of a filter, so a short list never looks like missing books.
                InputChip(
                    selected = true,
                    onClick = { vm.setFilter(StatusFilter.All) },
                    label = { Text("Showing: ${state.options.filter.label}") },
                    trailingIcon = { Icon(Icons.Filled.Close, "Show all books", Modifier.size(InputChipDefaults.IconSize)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            when {
                state.groups.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Search,
                    title = "No matches",
                    body = when {
                        state.query.isNotBlank() -> "Nothing on your shelf matches \"${state.query}\"."
                        else -> "No books marked ${state.options.filter.label}."
                    },
                )
                state.grouping == Grouping.All -> BookGrid(state.groups.first().books, state.reading, taps)
                else -> GroupedShelves(state.groups, state.reading, taps)
            }
        }
    }

    if (sharing) ShelfieDialog(loadBooks = vm::allBooks, onDismiss = { sharing = false })

    if (confirmDelete) {
        val n = state.selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove $n book${if (n == 1) "" else "s"}?") },
            text = { Text("They'll be removed from your library. You can undo this right after.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(
    state: LibraryState,
    onSort: (SortBy) -> Unit,
    onFilter: (StatusFilter) -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
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
        if (state.totalCount > 0) {
            IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Share my shelf") }
            SortFilterMenu(state.options, onSort, onFilter)
        }
        LibraryMenu(canExport = state.totalCount > 0, onExport = onExport, onImport = onImport)
    }
}

/** Replaces the header while books are selected. */
@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onMark: (String) -> Unit, onDelete: () -> Unit) {
    var marking by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel selection") }
            Text("$count selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { marking = true }) { Text("Mark as") }
                DropdownMenu(expanded = marking, onDismissRequest = { marking = false }) {
                    ReadingStatus.all.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(ReadingStatus.label(status)) },
                            onClick = { marking = false; onMark(status) },
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Remove selected") }
        }
    }
}

@Composable
private fun SortFilterMenu(options: ViewOptions, onSort: (SortBy) -> Unit, onFilter: (StatusFilter) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.Tune, "Sort and filter") }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            MenuLabel("Sort by")
            SortBy.entries.forEach { sort ->
                CheckedItem(sort.label, checked = options.sort == sort) { onSort(sort); menuOpen = false }
            }
            HorizontalDivider()
            MenuLabel("Show")
            StatusFilter.entries.forEach { filter ->
                CheckedItem(filter.label, checked = options.filter == filter) { onFilter(filter); menuOpen = false }
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun CheckedItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { if (checked) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp)) },
        onClick = onClick,
    )
}

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

/** Scans saved while offline have been read: send the user to review them. */
@Composable
private fun ScansReadyBanner(count: Int, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudDone, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                "$count saved scan${if (count == 1) " is" else "s are"} ready to review",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            Text("Review", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
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

@Composable
private fun ReadingRow(books: List<Book>, taps: BookTaps, sidePadding: Dp = 16.dp) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            "Currently reading", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = sidePadding, vertical = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = sidePadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { "reading_${it.id}" }) { book ->
                Column(Modifier.width(88.dp)) { GridBook(book, taps) }
            }
        }
    }
}

@Composable
private fun BookGrid(books: List<Book>, reading: List<Book>, taps: BookTaps) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (reading.isNotEmpty()) {
            // The grid already pads its sides, so the row adds none of its own.
            item(key = "reading", span = { GridItemSpan(maxLineSpan) }) { ReadingRow(reading, taps, sidePadding = 0.dp) }
            item(key = "all_header", span = { GridItemSpan(maxLineSpan) }) {
                Text("All books", style = MaterialTheme.typography.titleMedium)
            }
        }
        items(books, key = { it.id }) { book -> GridBook(book, taps) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBook(book: Book, taps: BookTaps) {
    val isSelected = book.id in taps.selected
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(
            onClick = { taps.tap(book.id) },
            onLongClick = { taps.longPress(book.id) },
            onLongClickLabel = "Select",
        ),
    ) {
        Box {
            BookCover(book.coverUrl, book.title, book.author, Modifier.fillMaxWidth())
            if (taps.selecting) SelectionMark(isSelected, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(book.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A filled check on selected covers, an empty ring on the others, so selection mode is obvious. */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    if (selected) {
        Icon(
            Icons.Filled.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(26.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
        )
    } else {
        Box(
            modifier.size(24.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

/** Each author/genre is a horizontal shelf with a sticky label. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupedShelves(groups: List<ShelfGroup>, reading: List<Book>, taps: BookTaps) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        if (reading.isNotEmpty()) item(key = "reading") { ReadingRow(reading, taps) }
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
                        Column(Modifier.width(100.dp)) { GridBook(book, taps) }
                    }
                }
            }
        }
    }
}
