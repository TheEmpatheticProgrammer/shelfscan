package com.shelfscan.app.ui.discover

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelfscan.app.data.WishlistItem
import com.shelfscan.app.data.againstLibrary
import com.shelfscan.app.data.isGap
import com.shelfscan.app.data.missingVolumes
import com.shelfscan.app.data.remote.SeriesGapDto
import com.shelfscan.app.data.summary
import com.shelfscan.app.data.volumeNote
import com.shelfscan.app.data.wantToReadReason
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
    val ownedKeys by vm.ownedKeys.collectAsStateWithLifecycle()
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
            TAB_SERIES -> vm.loadSeries()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Discover", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            if (!state.libraryEmpty && tab != TAB_SAVED) {
                IconButton(onClick = {
                    when (tab) {
                        TAB_FOR_YOU -> vm.loadForYou(force = true)
                        TAB_SERIES -> vm.loadSeries(force = true)
                        else -> vm.loadUpcoming(force = true)
                    }
                }) {
                    Icon(Icons.Filled.Refresh, "Refresh")
                }
            }
        }
        // Scrollable: four tabs don't fit a narrow phone at a readable size.
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            Tab(tab == TAB_FOR_YOU, { tab = TAB_FOR_YOU }, text = { Text("For you") }, icon = { Icon(Icons.Filled.AutoAwesome, null) })
            Tab(tab == TAB_COMING, { tab = TAB_COMING }, text = { Text("Coming soon") }, icon = { Icon(Icons.Filled.EventAvailable, null) })
            Tab(tab == TAB_SERIES, { tab = TAB_SERIES }, text = { Text("Series") }, icon = { Icon(Icons.Filled.CollectionsBookmark, null) })
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
            OfflineNote(state.forYouOfflineSince, onRetry = { vm.loadForYou(force = true) })
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
        } else if (tab == TAB_SERIES) {
            OfflineNote(state.seriesOfflineSince, onRetry = { vm.loadSeries(force = true) })
            LoadContent(state.series, onRetry = { vm.loadSeries(force = true) }) { found ->
                val series = found.mapNotNull { it.againstLibrary(ownedKeys) }
                if (series.isEmpty()) EmptyState(
                    icon = Icons.Filled.CollectionsBookmark,
                    title = "No gaps in your series",
                    body = "When you own some books of a series but not the rest, the missing ones show up here.",
                )
                else SeriesList(series, savedKeys, onOpen = { selected = it }, onSaveMissing = vm::saveMissing)
            }
        } else {
            OfflineNote(state.upcomingOfflineSince, onRetry = { vm.loadUpcoming(force = true) })
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

/** A quiet line saying these are saved results, with a way to try the server again. */
@Composable
private fun OfflineNote(since: Long?, onRetry: () -> Unit) {
    if (since == null) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                "Offline \u00b7 saved ${DateUtils.getRelativeTimeSpanString(since)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

private const val TAB_FOR_YOU = 0
private const val TAB_COMING = 1
private const val TAB_SERIES = 2
private const val TAB_SAVED = 3

/** Each series: which volumes you own, what's missing, then a row per missing volume. */
@Composable
private fun SeriesList(
    series: List<SeriesGapDto>,
    savedKeys: Set<String>,
    onOpen: (Pick) -> Unit,
    onSaveMissing: (SeriesGapDto) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        series.forEachIndexed { s, gap ->
            item(key = "series|${gap.name}") {
                SeriesHeader(gap, savedKeys, first = s == 0, onSaveMissing = { onSaveMissing(gap) })
            }
            val missing = gap.missingVolumes
            itemsIndexed(missing, key = { _, v -> "series|${gap.name}|${v.number}" }) { i, v ->
                val book = v.book ?: return@itemsIndexed
                // The row says where it sits in the series; the sheet keeps that as the Want to read reason.
                PickRow(Pick(book, gap.volumeNote(v)), onClick = { onOpen(Pick(book, gap.wantToReadReason(v))) })
                if (i < missing.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SeriesHeader(gap: SeriesGapDto, savedKeys: Set<String>, first: Boolean, onSaveMissing: () -> Unit) {
    val unsaved = gap.missingVolumes.count { v -> v.book?.matchKey !in savedKeys }
    Column(Modifier.fillMaxWidth().padding(top = if (first) 12.dp else 28.dp, bottom = 4.dp)) {
        Text(gap.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(gap.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        VolumeStrip(gap)
        Spacer(Modifier.height(8.dp))
        Text(gap.summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (unsaved > 0) {
            FilledTonalButton(onClick = onSaveMissing) {
                Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (unsaved == 1) "Add to Want to read" else "Add $unsaved to Want to read")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Icon(Icons.Outlined.BookmarkAdded, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("On your Want to read list", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** One square per volume: filled for the ones you own, outlined for the ones you're missing (brass for a hole). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeStrip(gap: SeriesGapDto) {
    val shape = RoundedCornerShape(6.dp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        gap.volumes.forEach { v ->
            val missingColor = if (gap.isGap(v)) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
            Box(
                Modifier.size(32.dp).clip(shape).then(
                    if (v.owned) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.border(1.5.dp, missingColor, shape),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    v.number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (v.owned) MaterialTheme.colorScheme.onPrimary else missingColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

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
