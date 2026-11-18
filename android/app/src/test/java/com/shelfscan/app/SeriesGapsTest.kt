package com.shelfscan.app

import com.shelfscan.app.data.againstLibrary
import com.shelfscan.app.data.matchKeyOf
import com.shelfscan.app.data.missingVolumes
import com.shelfscan.app.data.numberList
import com.shelfscan.app.data.remote.BookDto
import com.shelfscan.app.data.remote.SeriesGapDto
import com.shelfscan.app.data.remote.SeriesVolumeDto
import com.shelfscan.app.data.summary
import com.shelfscan.app.data.volumeNote
import com.shelfscan.app.data.wantToReadReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesGapsTest {
    private val author = "Brandon Sanderson"

    private fun owned(n: Int, title: String = "Book $n") = SeriesVolumeDto(n, title, owned = true)
    private fun missing(n: Int, title: String = "Book $n") = SeriesVolumeDto(n, title, owned = false, book = BookDto(title, author))

    private val stormlight = SeriesGapDto(
        "The Stormlight Archive", author,
        listOf(owned(1), owned(2), missing(3, "Oathbringer"), owned(4), missing(5, "Wind and Truth")),
    )

    @Test
    fun holeAndLaterVolume() =
        assertEquals("You own 1, 2 and 4. #3 is missing. #5 is out too.", stormlight.summary())

    @Test
    fun onlyLaterVolumes() {
        val s = SeriesGapDto("The Expanse", "James S. A. Corey", listOf(owned(1), owned(2), missing(3), missing(4)))
        assertEquals("You own 1 and 2. #3 and #4 are out.", s.summary())
    }

    @Test
    fun severalHoles() {
        val s = SeriesGapDto("S", author, listOf(missing(1), missing(2), owned(3), missing(4), owned(5)))
        assertEquals("You own 3 and 5. #1, #2 and #4 are missing.", s.summary())
    }

    @Test
    fun runsCollapse() {
        assertEquals("1–3, 5 and 6", numberList(listOf(6, 1, 2, 3, 5)))
        assertEquals("#1–4", numberList(listOf(1, 2, 3, 4), hash = true))
        assertEquals("7", numberList(listOf(7, 7)))
        assertEquals("", numberList(emptyList()))
    }

    @Test
    fun notesSayWhereTheVolumeSits() {
        val (three, five) = stormlight.missingVolumes
        assertEquals("#3 · missing from your set", stormlight.volumeNote(three))
        assertEquals("#5 · next in the series", stormlight.volumeNote(five))
        assertEquals("#3 in The Stormlight Archive", stormlight.wantToReadReason(three))
        val s = SeriesGapDto("S", author, listOf(owned(1), owned(2), missing(3), missing(4)))
        assertEquals("#4 · also out", s.volumeNote(s.missingVolumes[1]))
    }

    @Test
    fun addingAMissingVolumeUpdatesTheSeries() {
        val keys = setOf(matchKeyOf("Oathbringer", author))
        val updated = stormlight.againstLibrary(keys)!!
        assertEquals("You own 1–4. #5 is out.", updated.summary())
        assertNull(updated.againstLibrary(keys + matchKeyOf("Wind and Truth", author)))   // set complete: dropped
    }

    @Test
    fun volumesWithoutACatalogRecordArentShown() {
        val s = SeriesGapDto("S", author, listOf(owned(1), owned(2), SeriesVolumeDto(3, "Unchecked", owned = false)))
        assertNull(s.againstLibrary(emptySet()))
    }
}
