package com.shelfscan.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfscan.app.ShelfScanApp
import com.shelfscan.app.data.Book
import com.shelfscan.app.data.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Grouping(val label: String) { All("All"), Author("Author"), Genre("Genre") }

data class ShelfGroup(val name: String, val books: List<Book>)

data class LibraryState(
    val loaded: Boolean = false,
    val totalCount: Int = 0,
    val authorCount: Int = 0,
    val query: String = "",
    val grouping: Grouping = Grouping.All,
    val groups: List<ShelfGroup> = emptyList(),
)

class LibraryViewModel(private val repo: BookRepository, initialQuery: String = "") : ViewModel() {
    private val query = MutableStateFlow(initialQuery)
    private val grouping = MutableStateFlow(Grouping.All)

    val state: StateFlow<LibraryState> = combine(repo.books, query, grouping) { books, q, g ->
        val filtered = if (q.isBlank()) books else books.filter { b ->
            b.title.contains(q, true) || b.author.contains(q, true) || b.genres.any { it.contains(q, true) }
        }
        LibraryState(
            loaded = true,
            totalCount = books.size,
            authorCount = books.flatMap { it.authors }.toSet().size,
            query = q,
            grouping = g,
            groups = group(filtered, g),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    init {
        // Swap small covers on books scanned earlier for large ones (runs once; retried next launch if offline).
        viewModelScope.launch { runCatching { repo.upgradeCoversOnce() } }
    }

    fun setQuery(q: String) { query.value = q }
    fun setGrouping(g: Grouping) { grouping.value = g }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    (this[APPLICATION_KEY] as ShelfScanApp).repository,
                    createSavedStateHandle().get<String>("q").orEmpty(),
                )
            }
        }

        fun group(books: List<Book>, g: Grouping): List<ShelfGroup> = when (g) {
            Grouping.All -> if (books.isEmpty()) emptyList() else listOf(ShelfGroup("All", books))
            // Co-authored books appear under each author.
            Grouping.Author -> books.flatMap { b -> b.authors.map { it to b } }
                .groupBy({ it.first }, { it.second })
                .map { (name, list) -> ShelfGroup(name, list.sortedBy { it.title }) }
                .sortedBy { lastName(it.name) }
            Grouping.Genre -> books.groupBy { it.primaryGenre }
                .map { (name, list) -> ShelfGroup(name, list.sortedBy { it.title }) }
                .sortedWith(compareByDescending<ShelfGroup> { it.books.size }.thenBy { it.name })
        }

        private fun lastName(name: String) = name.substringAfterLast(' ').lowercase()
    }
}
