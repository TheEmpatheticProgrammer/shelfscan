package com.shelfscan.app.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfscan.app.data.Book
import com.shelfscan.app.ui.components.BookCover
import com.shelfscan.app.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (Long) -> Unit,
    onScan: () -> Unit,
    vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Filled.CameraAlt, null) },
                text = { Text("Add books") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("My Library", style = MaterialTheme.typography.headlineLarge)
                if (state.totalCount > 0) {
                    Text(
                        "${state.totalCount} books · ${state.authorCount} authors",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                state.grouping == Grouping.All -> BookGrid(state.groups.first().books, onBookClick)
                else -> GroupedShelves(state.groups, onBookClick)
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

@Composable
private fun BookGrid(books: List<Book>, onBookClick: (Long) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
private fun GroupedShelves(groups: List<ShelfGroup>, onBookClick: (Long) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
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
