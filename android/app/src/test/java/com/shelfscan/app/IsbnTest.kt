package com.shelfscan.app

import com.shelfscan.app.ui.scan.looksLikeIsbn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsbnTest {
    @Test
    fun bookBarcodesAreAccepted() {
        assertTrue(looksLikeIsbn("9780765326355")) // EAN-13, 978 prefix
        assertTrue(looksLikeIsbn("9791032305690")) // 979 prefix
        assertTrue(looksLikeIsbn("0765326353"))    // ISBN-10
        assertTrue(looksLikeIsbn("080442957X"))    // ISBN-10 with X check digit
    }

    @Test
    fun otherBarcodesAreRejected() {
        assertFalse(looksLikeIsbn("5012345678900")) // a grocery EAN-13
        assertFalse(looksLikeIsbn("9771234567003")) // a magazine (ISSN) barcode
        assertFalse(looksLikeIsbn("12345"))
        assertFalse(looksLikeIsbn("X765326353"))
    }
}
