package com.shelfscan.app

import com.shelfscan.app.data.Book
import com.shelfscan.app.ui.library.Grouping
import com.shelfscan.app.ui.library.LibraryViewModel
import com.shelfscan.app.ui.library.SortBy
import com.shelfscan.app.ui.library.ViewOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortTest {
    private val books = listOf(
        Book(id = 1, title = "The Way of Kings", author = "Brandon Sanderson", addedAt = 3),
        Book(id = 2, title = "Dune", author = "Frank Herbert", addedAt = 1),
        Book(id = 3, title = "A Wizard of Earthsea", author = "Ursula K. Le Guin", addedAt = 2),
        Book(id = 4, title = "Elantris", author = "Brandon Sanderson", addedAt = 4),
    )

    private fun sorted(sort: SortBy) = books.sortedWith(LibraryViewModel.comparator(sort)).map { it.id }

    @Test
    fun recentFirst() = assertEquals(listOf(4L, 1L, 3L, 2L), sorted(SortBy.Recent))

    @Test
    fun titleIgnoresLeadingArticles() =
        // Dune, Elantris, Way of Kings, Wizard of Earthsea
        assertEquals(listOf(2L, 4L, 1L, 3L), sorted(SortBy.Title))

    @Test
    fun authorByLastNameThenTitle() =
        // Herbert; Le Guin ("guin"); Sanderson: Elantris, Way of Kings
        assertEquals(listOf(2L, 3L, 4L, 1L), sorted(SortBy.Author))

    @Test
    fun lastNamesShelveLikeALibrary() {
        assertEquals("le guin", LibraryViewModel.lastName("Ursula K. Le Guin"))
        assertEquals("king", LibraryViewModel.lastName("Martin Luther King Jr."))
        assertEquals("herbert", LibraryViewModel.lastName("Frank Herbert"))
        assertEquals("tolkien", LibraryViewModel.lastName("J.R.R. Tolkien"))
        assertEquals("plato", LibraryViewModel.lastName("Plato"))
    }

    @Test
    fun groupsKeepTheChosenOrder() {
        val ordered = books.sortedWith(LibraryViewModel.comparator(SortBy.Title))
        val shelves = LibraryViewModel.group(ordered, ViewOptions(grouping = Grouping.Author))
        val sanderson = shelves.single { it.name == "Brandon Sanderson" }
        assertEquals(listOf("Elantris", "The Way of Kings"), sanderson.books.map { it.title })
    }
}
