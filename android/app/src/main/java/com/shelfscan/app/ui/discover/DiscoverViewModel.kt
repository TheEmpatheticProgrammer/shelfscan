package com.shelfscan.app.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfscan.app.ShelfScanApp
import com.shelfscan.app.data.Book
import com.shelfscan.app.data.BookRepository
import com.shelfscan.app.data.WishlistItem
import com.shelfscan.app.data.remote.BookDto
import com.shelfscan.app.data.remote.RecommendationDto
import com.shelfscan.app.util.friendlyMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** What "For you" is about: everything, one author, or one genre. */
sealed interface Focus {
    val label: String
    data object All : Focus { override val label = "All" }
    data class Author(val name: String) : Focus {
        override val label: String
            get() = name
    }
    data class Genre(val name: String) : Focus {
        override val label: String
            get() = name
    }
}

data class DiscoverState(
    val libraryEmpty: Boolean = false,
    val focusOptions: List<Focus> = listOf(Focus.All),
    val focus: Focus = Focus.All,
    val forYou: Load<List<RecommendationDto>> = Load.Idle,
    val upcoming: Load<List<BookDto>> = Load.Idle,
    /** Set when the server couldn't be reached and a saved copy is shown: when that copy was received. */
    val forYouOfflineSince: Long? = null,
    val upcomingOfflineSince: Long? = null,
)

class DiscoverViewModel(private val repo: BookRepository) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    val wantToRead: StateFlow<List<WishlistItem>> =
        repo.wishlist.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val savedKeys: StateFlow<Set<String>> =
        repo.wishlistKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleSaved(book: BookDto, reason: String) = viewModelScope.launch { repo.toggleWantToRead(book, reason) }

    fun moveToLibrary(item: WishlistItem) = viewModelScope.launch { repo.moveToLibrary(item) }

    // Results per focus, reused until the shelf changes, so flipping between chips is instant.
    private val recsCache = mutableMapOf<Focus, Pair<Set<String>, List<RecommendationDto>>>()
    private var recsJob: Job? = null
    private var upcomingKey: Set<String>? = null

    init {
        viewModelScope.launch {
            repo.books.collect { books ->
                _state.update {
                    it.copy(libraryEmpty = books.isEmpty(), focusOptions = focusOptions(books, it.focus))
                }
            }
        }
    }

    fun setFocus(focus: Focus) {
        _state.update { s ->
            val options = if (focus in s.focusOptions) s.focusOptions else s.focusOptions + focus
            s.copy(focus = focus, focusOptions = options)
        }
        loadForYou()
    }

    fun loadForYou(force: Boolean = false) {
        val focus = _state.value.focus
        recsJob?.cancel() // a newer chip wins; don't let an older answer land on top of it
        recsJob = viewModelScope.launch {
            val key = shelfKey() ?: return@launch
            val cached = recsCache[focus]
            if (!force && cached != null && cached.first == key) {
                _state.update { it.copy(forYou = Load.Ready(cached.second), forYouOfflineSince = null) }
                return@launch
            }
            _state.update { it.copy(forYou = Load.Loading) }
            runCatching {
                when (focus) {
                    Focus.All -> repo.recommendations()
                    is Focus.Author -> repo.recommendations(author = focus.name)
                    is Focus.Genre -> repo.recommendations(genre = focus.name)
                }
            }
                .onSuccess { fetched ->
                    // Only fresh answers are reused; an offline copy is shown but retried on the next visit.
                    if (!fetched.isOffline) recsCache[focus] = key to fetched.data
                    _state.update {
                        if (it.focus == focus) it.copy(forYou = Load.Ready(fetched.data), forYouOfflineSince = fetched.offlineSince) else it
                    }
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { if (it.focus == focus) it.copy(forYou = Load.Failed(e.friendlyMessage())) else it }
                }
        }
    }

    fun loadUpcoming(force: Boolean = false) = viewModelScope.launch {
        val key = shelfKey() ?: return@launch
        if (!force && key == upcomingKey && _state.value.upcoming is Load.Ready) return@launch
        _state.update { it.copy(upcoming = Load.Loading) }
        runCatching { repo.upcoming() }
            .onSuccess { fetched ->
                if (!fetched.isOffline) upcomingKey = key
                _state.update { it.copy(upcoming = Load.Ready(fetched.data), upcomingOfflineSince = fetched.offlineSince) }
            }
            .onFailure { e -> _state.update { it.copy(upcoming = Load.Failed(e.friendlyMessage())) } }
    }

    private suspend fun shelfKey(): Set<String>? {
        val books = repo.books.first()
        return if (books.isEmpty()) null else books.map { it.matchKey }.toSet()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { DiscoverViewModel((this[APPLICATION_KEY] as ShelfScanApp).repository) }
        }

        /** "All", then the shelf's biggest genres, then its most-owned authors. */
        fun focusOptions(books: List<Book>, current: Focus): List<Focus> {
            val genres = books.map { it.primaryGenre }
                .filter { it != "Uncategorized" }
                .groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(4).map { Focus.Genre(it.key) }
            val authors = books.flatMap { it.authors }
                .filter { !it.equals("Unknown", ignoreCase = true) }
                .groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(6).map { Focus.Author(it.key) }
            val options = listOf(Focus.All) + genres + authors
            // Keep a focus picked from elsewhere (e.g. a book's page) visible even if it isn't a top one.
            return if (current in options) options else options + current
        }
    }
}
