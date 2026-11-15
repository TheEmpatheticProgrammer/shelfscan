package com.shelfscan.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfscan.app.ShelfScanApp
import com.shelfscan.app.data.Book
import com.shelfscan.app.data.BookRepository
import com.shelfscan.app.data.remote.BookDto
import com.shelfscan.app.data.remote.RecommendationDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Loading state for one part of the page, so each part can appear as soon as it's ready. */
enum class Part { Loading, Ready, Failed }

/** Covers to choose from in the "Change cover" sheet. */
data class CoverChoices(val state: Part = Part.Loading, val urls: List<String> = emptyList())

data class Extras(
    val synopsis: Part = Part.Loading,
    val similar: List<RecommendationDto> = emptyList(),
    val similarState: Part = Part.Loading,
)

class BookDetailViewModel(private val repo: BookRepository, bookId: Long) : ViewModel() {
    val book: StateFlow<Book?> = repo.book(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _extras = MutableStateFlow(Extras())
    val extras: StateFlow<Extras> = _extras.asStateFlow()

    private val _covers = MutableStateFlow(CoverChoices())
    val covers: StateFlow<CoverChoices> = _covers.asStateFlow()

    val savedKeys: StateFlow<Set<String>> =
        repo.wishlistKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setStatus(status: String) {
        val b = book.value ?: return
        viewModelScope.launch { repo.setStatus(b, status) }
    }

    fun toggleSaved(book: BookDto, reason: String) = viewModelScope.launch { repo.toggleWantToRead(book, reason) }

    fun loadCoverChoices() {
        val b = book.value ?: return
        _covers.value = CoverChoices()
        viewModelScope.launch {
            _covers.value = try {
                CoverChoices(Part.Ready, repo.coverCandidates(b))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CoverChoices(Part.Failed)
            }
        }
    }

    fun chooseCover(url: String) {
        val b = book.value ?: return
        viewModelScope.launch { repo.setCover(b, url) }
    }

    init {
        // Load once per book identity; an edit to the title or author makes it a new book and reloads.
        viewModelScope.launch {
            book.filterNotNull().distinctUntilChangedBy { it.matchKey }.collect { b ->
                _extras.value = Extras()
                launch { loadSynopsis(b) }
                launch { loadSimilar(b) }
            }
        }
    }

    fun retrySimilar() {
        val b = book.value ?: return
        viewModelScope.launch { loadSimilar(b) }
    }

    private suspend fun loadSynopsis(b: Book) {
        val outcome = try {
            repo.loadDetails(b)
            Part.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Part.Failed
        }
        _extras.update { it.copy(synopsis = outcome) }
    }

    private suspend fun loadSimilar(b: Book) {
        _extras.update { it.copy(similarState = Part.Loading) }
        try {
            val recs = repo.similarTo(b)
            _extras.update { it.copy(similar = recs, similarState = Part.Ready) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _extras.update { it.copy(similarState = Part.Failed) }
        }
    }

    fun save(title: String, author: String, genres: List<String>) {
        val b = book.value ?: return
        viewModelScope.launch { repo.update(b.copy(title = title, author = author, genres = genres)) }
    }

    fun delete(onDone: () -> Unit) {
        val b = book.value ?: return
        viewModelScope.launch { repo.delete(b); onDone() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ShelfScanApp
                BookDetailViewModel(app.repository, createSavedStateHandle().get<Long>("id") ?: -1L)
            }
        }
    }
}
