package com.shelfscan.app.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfscan.app.data.WishlistItem
import com.shelfscan.app.data.matchKey
import com.shelfscan.app.data.remote.BookDto
import com.shelfscan.app.data.toDto
import com.shelfscan.app.data.remote.RecommendationDto
import com.shelfscan.app.ui.components.BookCover
import com.shelfscan.app.ui.components.BookSheet
import com.shelfscan.app.ui.components.EmptyState
import com.shelfscan.app.ui.components.yearOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One row in a list: the book, plus one quiet line under the author (a reason, a year or a date). */
private data class Pick(val book: BookDto, val note: String, val wish: WishlistItem? = null)

private data class Section(val title: String?, val picks: List<Pick>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onScan: () -> Unit,
    initialAuthor: String? = null,
    vm: DiscoverViewModel = viewModel(factory = DiscoverViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val wantToRead by vm.wantToRead.collectAsStateWithLifecycle()
    val savedKeys by vm.savedKeys.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openedFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Pick?>(null) }

    // Arriving from "More by …" on a book's page: open on that author, once.
    LaunchedEffect(initialAuthor) {
        if (!initialAuthor.isNullOrBlank() && openedFrom != initialAuthor) {
            openedFrom = initialAuthor
            tab = 0
            vm.setFocus(Focus.Author(initialAuthor))
        }
    }
    LaunchedEffect(tab) {
        when (tab) {
            TAB_FOR_YOU -> vm.loadForYou()
            TAB_COMING -> vm.loadUpcoming()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Discover", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            if (!state.libraryEmpty && tab != TAB_SAVED) {
                IconButton(onClick = { if (tab == TAB_FOR_YOU) vm.loadForYou(force = true) else vm.loadUpcoming(force = true) }) {
                    Icon(Icons.Filled.Refresh, "Refresh")
                }
            }
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == TAB_FOR_YOU, { tab = TAB_FOR_YOU }, text = { Text("For you") }, icon = { Icon(Icons.Filled.AutoAwesome, null) })
            Tab(tab == TAB_COMING, { tab = TAB_COMING }, text = { Text("Coming soon") }, icon = { Icon(Icons.Filled.EventAvailable, null) })
            Tab(tab == TAB_SAVED, { tab = TAB_SAVED }, text = { Text("Want to read") }, icon = { Icon(Icons.Filled.Bookmark, null) })
        }

        if (tab == TAB_SAVED) {
            if (wantToRead.isEmpty()) EmptyState(
                icon = Icons.Outlined.BookmarkBorder,
                title = "Nothing saved yet",
                body = "Tap Want to read on any suggestion to keep it here.",
            )
            else PickList(
                listOf(Section(null, wantToRead.map { Pick(it.toDto(), it.reason.orEmpty(), wish = it) })),
                onClick = { selected = it },
            )
            return@Column
        }

        if (state.libraryEmpty) {
            EmptyState(
                title = "Nothing to go on yet",
                body = "Add a few books to your library and ShelfScan will suggest what to read next and flag new releases from your authors.",
                action = "Scan my books",
                onAction = onScan,
            )
            return@Column
        }

        if (tab == TAB_FOR_YOU) {
            FocusChips(state.focusOptions, state.focus, onSelect = vm::setFocus)
            LoadContent(state.forYou, onRetry = { vm.loadForYou(force = true) }) { recs ->
                val sections = sectionsFor(state.focus, recs)
                if (sections.all { it.picks.isEmpty() }) EmptyState(
                    title = "No suggestions yet",
                    body = when (val f = state.focus) {
                        is Focus.Author -> "Couldn't find more for ${f.name} right now. Try refreshing."
                        is Focus.Genre -> "Couldn't find ${f.name} picks right now. Try refreshing."
                        Focus.All -> "Try refreshing in a moment."
                    },
                )
                else PickList(sections, onClick = { selected = it })
            }
        } else {
            LoadContent(state.upcoming, onRetry = { vm.loadUpcoming(force = true) }) { books ->
                if (books.isEmpty()) EmptyState(
                    icon = Icons.Filled.EventAvailable,
                    title = "No announced releases",
                    body = "None of the authors in your library have new books listed yet. Check back later.",
                )
                else PickList(
                    listOf(Section("New from authors you own", books.map { Pick(it, releaseLabel(it.publishedDate)) })),
                    onClick = { selected = it },
                )
            }
        }
    }

    selected?.let { pick ->
        BookSheet(
            pick.book, pick.note,
            onDismiss = { selected = null },
            saved = pick.book.matchKey in savedKeys,
            onToggleSaved = { vm.toggleSaved(pick.book, pick.note) },
            primaryAction = pick.wish?.let { wish -> "I have this book" to { vm.moveToLibrary(wish); selected = null } },
        )
    }
}

private const val TAB_FOR_YOU = 0
private const val TAB_COMING = 1
private const val TAB_SAVED = 2

private fun sectionsFor(focus: Focus, recs: List<RecommendationDto>): List<Section> {
    val (byAuthor, similar) = recs.partition { it.section == RecommendationDto.SECTION_BY_AUTHOR }
    val similarPicks = similar.map { Pick(it.book, it.reason) }
    return when (focus) {
        is Focus.Author -> listOf(
            Section("More by ${focus.name}", byAuthor.map { Pick(it.book, yearOf(it.book)) }),
            Section("If you like ${focus.name}", similarPicks),
        ).filter { it.picks.isNotEmpty() }
        is Focus.Genre -> listOf(Section("${focus.name} picks for you", similarPicks))
        Focus.All -> listOf(Section(null, similarPicks))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusChips(options: List<Focus>, selected: Focus, onSelect: (Focus) -> Unit) {
    if (options.size <= 1) return
    val listState = rememberLazyListState()
    // Keep the active chip in view, e.g. when arriving from a book's page with an author at the end.
    LaunchedEffect(selected) {
        val i = options.indexOf(selected)
        if (i > 0) listState.animateScrollToItem(i)
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options, key = { "${it::class.simpleName}:${it.label}" }) { focus ->
            FilterChip(
                selected = focus == selected,
                onClick = { onSelect(focus) },
                label = { Text(focus.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = if (focus is Focus.Author) {
                    { Icon(Icons.Filled.Person, null, Modifier.size(FilterChipDefaults.IconSize)) }
                } else null,
            )
        }
    }
}

@Composable
private fun <T> LoadContent(load: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (load) {
        Load.Idle, Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> EmptyState(icon = Icons.Outlined.CloudOff, title = "Couldn't load", body = load.message, action = "Retry", onAction = onRetry)
        is Load.Ready -> content(load.data)
    }
}

@Composable
private fun PickList(sections: List<Section>, onClick: (Pick) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        sections.forEachIndexed { s, section ->
            section.title?.let { title ->
                item(key = "h$s") {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = if (s == 0) 4.dp else 24.dp, bottom = 4.dp),
                    )
                }
            }
            itemsIndexed(section.picks, key = { i, p -> "$s|$i|${p.book.title}|${p.book.author}" }) { i, pick ->
                PickRow(pick, onClick = { onClick(pick) })
                if (i < section.picks.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PickRow(pick: Pick, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BookCover(pick.book.coverUrl, pick.book.title, pick.book.author, Modifier.width(56.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(pick.book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                pick.book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (pick.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    pick.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun releaseLabel(date: String?): String {
    if (date.isNullOrBlank()) return "Release date TBA"
    return runCatching {
        when (date.length) {
            10 -> "Out " + LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
            7 -> "Out " + LocalDate.parse("$date-01").format(DateTimeFormatter.ofPattern("MMMM yyyy"))
            else -> "Expected $date"
        }
    }.getOrDefault("Expected $date")
}
