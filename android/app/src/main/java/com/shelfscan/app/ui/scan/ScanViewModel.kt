package com.shelfscan.app.ui.scan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelfscan.app.ShelfScanApp
import com.shelfscan.app.data.BookRepository
import com.shelfscan.app.data.PendingScan
import com.shelfscan.app.data.matchKey
import com.shelfscan.app.data.remote.BookDto
import com.shelfscan.app.util.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

const val MAX_PHOTOS = 6
private const val LOW_CONFIDENCE = 0.6

data class Candidate(
    val id: Int,
    val book: BookDto,
    val selected: Boolean,
    val alreadyOwned: Boolean,
) {
    val lowConfidence get() = book.confidence < LOW_CONFIDENCE
}

sealed interface ScanStep {
    data object Pick : ScanStep
    data object Identifying : ScanStep
    data object LookingUp : ScanStep // a scanned barcode's ISBN
    data class Queued(val photoCount: Int) : ScanStep // server unreachable: saved for later
    data class Review(val candidates: List<Candidate>, val notes: String?) : ScanStep
    data class Done(val added: Int) : ScanStep
}

data class ScanState(
    val photos: List<Uri> = emptyList(),
    val step: ScanStep = ScanStep.Pick,
    val error: String? = null,
    /** Set while reviewing a scan from the offline queue, so it's removed once saved. */
    val reviewingPendingId: String? = null,
)

class ScanViewModel(private val repo: BookRepository) : ViewModel() {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    fun addPhotos(uris: List<Uri>) = _state.update {
        it.copy(photos = (it.photos + uris).distinct().take(MAX_PHOTOS), error = null)
    }

    fun removePhoto(uri: Uri) = _state.update { it.copy(photos = it.photos - uri) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Scans saved while offline: ready ones can be reviewed here. */
    val pending: StateFlow<List<PendingScan>> = repo.pendingScans

    init {
        // Opening Add books is a good moment to try the queue again, rather than waiting for the worker's backoff.
        viewModelScope.launch {
            repo.refreshPending()
            if (repo.pendingScans.value.any { it.state == PendingScan.QUEUED }) runCatching { repo.processPendingScans() }
        }
    }

    fun identify() {
        val photos = _state.value.photos
        if (photos.isEmpty()) return
        _state.update { it.copy(step = ScanStep.Identifying, error = null) }
        viewModelScope.launch {
            val bytes = try {
                repo.preparePhotos(photos)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(step = ScanStep.Pick, error = "Couldn't read those photos. Try taking them again.") }
                return@launch
            }
            try {
                val (books, notes) = repo.identify(bytes)
                showReview(books, notes, pendingId = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Can't reach the server (or it timed out): keep the photos and read them later instead of failing.
                repo.queueScan(bytes)
                _state.update { ScanState(step = ScanStep.Queued(bytes.size)) }
            } catch (e: Exception) {
                _state.update { it.copy(step = ScanStep.Pick, error = e.friendlyMessage()) }
            }
        }
    }

    fun reviewPending(scan: PendingScan) = viewModelScope.launch { showReview(scan.books, scan.notes, pendingId = scan.id) }

    fun discardPending(scan: PendingScan) = viewModelScope.launch { repo.discardPending(scan.id) }

    private suspend fun showReview(books: List<BookDto>, notes: String?, pendingId: String?) {
        val owned = repo.ownedKeys()
        val candidates = books.mapIndexed { i, b ->
            val isOwned = b.matchKey in owned
            Candidate(i, b, selected = !isOwned && b.confidence >= LOW_CONFIDENCE, alreadyOwned = isOwned)
        }
        _state.update { it.copy(step = ScanStep.Review(candidates, notes), reviewingPendingId = pendingId) }
    }

    /** A barcode was scanned: find the book and show it for review, like a one-book photo scan. */
    fun lookupBarcode(raw: String) {
        val isbn = raw.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase()
        if (!looksLikeIsbn(isbn)) {
            _state.update { it.copy(error = "That barcode isn't a book's ISBN. Try the one on the back cover, usually above the price.") }
            return
        }
        _state.update { it.copy(step = ScanStep.LookingUp, error = null) }
        viewModelScope.launch {
            runCatching { repo.lookupIsbn(isbn) to repo.ownedKeys() }
                .onSuccess { (book, owned) ->
                    if (book == null) {
                        _state.update { it.copy(step = ScanStep.Pick, error = "No book found for ISBN $isbn. Try a photo of the cover instead.") }
                    } else {
                        val isOwned = book.matchKey in owned
                        val candidate = Candidate(0, book, selected = !isOwned, alreadyOwned = isOwned)
                        _state.update { it.copy(step = ScanStep.Review(listOf(candidate), notes = null)) }
                    }
                }
                .onFailure { e -> _state.update { it.copy(step = ScanStep.Pick, error = e.friendlyMessage()) } }
        }
    }

    fun showError(message: String) = _state.update { it.copy(error = message) }

    fun toggle(id: Int) = updateCandidates { list ->
        list.map { if (it.id == id && !it.alreadyOwned) it.copy(selected = !it.selected) else it }
    }

    fun edit(id: Int, title: String, author: String, genres: List<String>) = updateCandidates { list ->
        list.map {
            if (it.id != id) it
            // Edited by the user, so it's trusted now. Drop the old cover/ISBN in case they no longer match.
            else it.copy(
                book = it.book.copy(
                    title = title, author = author, genres = genres, confidence = 1.0,
                    coverUrl = if (title == it.book.title) it.book.coverUrl else null,
                    isbn = if (title == it.book.title) it.book.isbn else null,
                ),
                selected = true,
            )
        }
    }

    fun save() {
        val review = _state.value.step as? ScanStep.Review ?: return
        val chosen = review.candidates.filter { it.selected }.map { it.book }
        viewModelScope.launch {
            val added = repo.add(chosen)
            _state.value.reviewingPendingId?.let { repo.discardPending(it) } // reviewed: done with it
            _state.update { it.copy(step = ScanStep.Done(added), reviewingPendingId = null) }
        }
    }

    fun reset() { _state.value = ScanState() }

    private fun updateCandidates(f: (List<Candidate>) -> List<Candidate>) = _state.update { s ->
        val review = s.step as? ScanStep.Review ?: return@update s
        s.copy(step = review.copy(candidates = f(review.candidates)))
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ScanViewModel((this[APPLICATION_KEY] as ShelfScanApp).repository) }
        }
    }
}

/** Book barcodes are EAN-13 starting 978 or 979; older ones may carry a 10-character ISBN. */
internal fun looksLikeIsbn(code: String): Boolean = when (code.length) {
    13 -> code.all { it.isDigit() } && (code.startsWith("978") || code.startsWith("979"))
    10 -> code.dropLast(1).all { it.isDigit() } && (code.last().isDigit() || code.last() == 'X')
    else -> false
}
