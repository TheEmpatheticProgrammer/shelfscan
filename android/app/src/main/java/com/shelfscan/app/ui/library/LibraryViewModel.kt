package com.shelfscan.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfscan.app.ShelfScanApp
import com.shelfscan.app.data.Book
import com.shelfscan.app.data.BookRepository
import com.shelfscan.app.data.PendingScan
import com.shelfscan.app.data.ReadingStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Grouping(val label: String) { All("All"), Author("Author"), Genre("Genre") }

enum class SortBy(val label: String) { Recent("Recently added"), Title("Title"), Author("Author") }

enum class StatusFilter(val label: String, val status: String?) {
    All("All books", null),
    Unread("Unread", ReadingStatus.UNREAD),
    Reading("Reading", ReadingStatus.READING),
    Read("Read", ReadingStatus.READ),
}

data class ShelfGroup(val name: String, val books: List<Book>)

/** How the library is being looked at: grouping, order and which books. */
data class ViewOptions(
    val grouping: Grouping = Grouping.All,
    val sort: SortBy = SortBy.Recent,
    val filter: StatusFilter = StatusFilter.All,
)

data class LibraryState(
    val loaded: Boolean = false,
    val totalCount: Int = 0,
    val authorCount: Int = 0,
    val query: String = "",
    val options: ViewOptions = ViewOptions(),
    val groups: List<ShelfGroup> = emptyList(),
    /** Books marked Reading, shown in their own row at the top (only when browsing everything). */
    val reading: List<Book> = emptyList(),
    /** Ids of books picked with a long press; non-empty means selection mode. */
    val selected: Set<Long> = emptySet(),
    /** Offline scans that have been read and are waiting for review. */
    val scansToReview: Int = 0,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
    val grouping: Grouping get() = options.grouping
}

/** A snackbar message, optionally with an action such as Undo. */
data class Notice(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

class LibraryViewModel(private val repo: BookRepository, initialQuery: String = "") : ViewModel() {
    private val query = MutableStateFlow(initialQuery)
    private val options = MutableStateFlow(ViewOptions())
    private val selected = MutableStateFlow<Set<Long>>(emptySet())

    val state: StateFlow<LibraryState> = combine(repo.books, query, options, selected, repo.pendingScans) { books, q, opts, sel, pending ->
        val visible = books
            .filter { b -> opts.filter.status == null || b.readingStatus == opts.filter.status }
            .filter { b -> q.isBlank() || b.title.contains(q, true) || b.author.contains(q, true) || b.genres.any { it.contains(q, true) } }
            .sortedWith(comparator(opts.sort))
        val ids = books.map { it.id }.toSet()
        LibraryState(
            loaded = true,
            totalCount = books.size,
            authorCount = books.flatMap { it.authors }.toSet().size,
            query = q,
            options = opts,
            groups = group(visible, opts),
            reading = if (q.isBlank() && opts.filter == StatusFilter.All) {
                books.filter { it.readingStatus == ReadingStatus.READING }.sortedByDescending { it.addedAt }
            } else emptyList(),
            selected = sel intersect ids, // a deleted book can't stay selected
            scansToReview = pending.count { it.state == PendingScan.READY },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 4)
    val notices: SharedFlow<Notice> = _notices

    init {
        viewModelScope.launch {
            // Swap small covers on books scanned earlier for large ones (once), then look again for books
            // still without a cover (each at most every few days). Both quietly retry next launch if offline.
            runCatching { repo.upgradeCoversOnce() }
            runCatching { repo.retryMissingCovers() }
        }
        // Get book pages ready for anything not yet prefetched (also backfills books added before this existed).
        repo.prefetchDetails(afterAdding = false)
        viewModelScope.launch {
            // Opening the app is a good moment to send any scans saved while offline.
            repo.refreshPending()
            if (repo.pendingScans.value.any { it.state == PendingScan.QUEUED }) runCatching { repo.processPendingScans() }
        }
    }

    fun setQuery(q: String) { query.value = q }
    fun setGrouping(g: Grouping) = options.update { it.copy(grouping = g) }
    fun setSort(s: SortBy) = options.update { it.copy(sort = s) }
    fun setFilter(f: StatusFilter) = options.update { it.copy(filter = f) }

    // --- Selection ---

    fun toggleSelected(id: Long) = selected.update { if (id in it) it - id else it + id }
    fun clearSelection() { selected.value = emptySet() }

    fun markSelected(status: String) = viewModelScope.launch {
        val ids = selected.value
        repo.setStatus(ids, status)
        clearSelection()
        _notices.emit(Notice("Marked ${count(ids.size)} as ${ReadingStatus.label(status).lowercase()}"))
    }

    fun deleteSelected() = viewModelScope.launch {
        val removed = repo.delete(selected.value)
        clearSelection()
        _notices.emit(Notice("Removed ${count(removed.size)}", "Undo") { viewModelScope.launch { repo.restore(removed) } })
    }

    // --- Backup ---

    fun exportLibrary(uri: Uri) = viewModelScope.launch {
        _notices.emit(
            Notice(
                runCatching { repo.exportTo(uri) }.fold(
                    { n -> "Exported ${count(n)}" },
                    { e -> "Export failed: ${e.message ?: "unknown error"}" },
                )
            )
        )
    }

    fun importLibrary(uri: Uri) = viewModelScope.launch {
        _notices.emit(
            Notice(
                runCatching { repo.importFrom(uri) }.fold(
                    { r ->
                        listOfNotNull(
                            "${count(r.books)} added",
                            r.wantToRead.takeIf { it > 0 }?.let { "$it to Want to read" },
                            r.skipped.takeIf { it > 0 }?.let { "$it already here" },
                        ).joinToString(" · ")
                    },
                    { e -> "Import failed: ${e.message ?: "unknown error"}" },
                )
            )
        )
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    (this[APPLICATION_KEY] as ShelfScanApp).repository,
                    createSavedStateHandle().get<String>("q").orEmpty(),
                )
            }
        }

        private fun count(n: Int) = "$n book${if (n == 1) "" else "s"}"

        /** Titles sort without a leading "The", "A" or "An", as a bookshelf would. */
        fun sortTitle(title: String): String = title.trim().lowercase().removePrefix("the ").removePrefix("a ").removePrefix("an ")

        private val PARTICLES = setOf("le", "la", "de", "du", "di", "da", "del", "della", "van", "von", "der", "den", "st.", "st")
        private val SUFFIXES = setOf("jr.", "jr", "sr.", "sr", "ii", "iii", "iv")

        /** Shelving name: "Ursula K. Le Guin" -> "le guin", "Martin Luther King Jr." -> "king". */
        fun lastName(name: String): String {
            val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .dropLastWhile { it.lowercase().trimEnd(',') in SUFFIXES }
            if (parts.isEmpty()) return name.lowercase()
            val last = parts.last().lowercase()
            val before = parts.getOrNull(parts.size - 2)?.lowercase()
            return if (parts.size >= 3 && before in PARTICLES) "$before $last" else last
        }

        fun comparator(sort: SortBy): Comparator<Book> = when (sort) {
            SortBy.Recent -> compareByDescending { it.addedAt }
            SortBy.Title -> compareBy { sortTitle(it.title) }
            SortBy.Author -> compareBy<Book> { lastName(it.authors.first()) }.thenBy { sortTitle(it.title) }
        }

        /** Books arrive already filtered and sorted; grouping keeps that order inside each shelf. */
        fun group(books: List<Book>, opts: ViewOptions): List<ShelfGroup> = when (opts.grouping) {
            Grouping.All -> if (books.isEmpty()) emptyList() else listOf(ShelfGroup("All", books))
            // Co-authored books appear under each author.
            Grouping.Author -> books.flatMap { b -> b.authors.map { it to b } }
                .groupBy({ it.first }, { it.second })
                .map { (name, list) -> ShelfGroup(name, list) }
                .sortedBy { lastName(it.name) }
            Grouping.Genre -> books.groupBy { it.primaryGenre }
                .map { (name, list) -> ShelfGroup(name, list) }
                .sortedWith(compareByDescending<ShelfGroup> { it.books.size }.thenBy { it.name })
        }
    }
}
