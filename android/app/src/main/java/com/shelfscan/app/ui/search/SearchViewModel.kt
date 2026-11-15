package com.shelfscan.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfscan.app.ShelfScanApp
import com.shelfscan.app.data.BookRepository
import com.shelfscan.app.data.remote.BookDto
import com.shelfscan.app.ui.discover.Load
import com.shelfscan.app.util.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val MIN_QUERY = 3

@OptIn(FlowPreview::class)
class SearchViewModel(private val repo: BookRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    private val retryTick = MutableStateFlow(0) // bumping it re-runs the same query
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<Load<List<BookDto>>>(Load.Idle)
    val results: StateFlow<Load<List<BookDto>>> = _results

    val ownedKeys: StateFlow<Set<String>> = repo.books.map { books -> books.map { it.matchKey }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val savedKeys: StateFlow<Set<String>> = repo.wishlistKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    init {
        // Search as you type, but only once typing pauses, and drop answers to older queries.
        viewModelScope.launch {
            combine(_query.map { it.trim() }.debounce(400), retryTick) { q, tick -> q to tick }
                .distinctUntilChanged()
                .collectLatest { (q, _) ->
                    if (q.length < MIN_QUERY) {
                        _results.value = Load.Idle
                        return@collectLatest
                    }
                    _results.value = Load.Loading
                    _results.value = try {
                        Load.Ready(repo.search(q))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Load.Failed(e.friendlyMessage())
                    }
                }
        }
    }

    fun setQuery(q: String) { _query.value = q }

    fun retry() { retryTick.value++ }

    fun add(book: BookDto) = viewModelScope.launch {
        val added = repo.add(listOf(book)) > 0
        _messages.emit(if (added) "Added “${book.title}”" else "“${book.title}” is already in your library")
    }

    fun toggleSaved(book: BookDto) = viewModelScope.launch { repo.toggleWantToRead(book, reason = null) }

    companion object {
        val Factory = viewModelFactory {
            initializer { SearchViewModel((this[APPLICATION_KEY] as ShelfScanApp).repository) }
        }
    }
}
