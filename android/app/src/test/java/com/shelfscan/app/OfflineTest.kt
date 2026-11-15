package com.shelfscan.app

import com.shelfscan.app.data.OfflineStore
import com.shelfscan.app.data.PendingScan
import com.shelfscan.app.data.PendingScanStore
import com.shelfscan.app.data.remote.BookDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun offlineCopyRoundTripsAndOverwrites() = runBlocking {
        val store = OfflineStore(tmp.newFolder("offline"))
        val strings = ListSerializer(String.serializer())
        assertNull(store.load("recs:", strings))
        store.save("recs:", strings, listOf("Dune"))
        store.save("recs:", strings, listOf("Hyperion", "Emma"))
        val saved = store.load("recs:", strings)!!
        assertEquals(listOf("Hyperion", "Emma"), saved.data)
        assertTrue(saved.savedAt > 0)
        assertNull(store.load("recs:Brandon Sanderson|", strings)) // keys don't collide
    }

    @Test
    fun corruptCopyIsIgnored() = runBlocking {
        val dir = tmp.newFolder("offline")
        val store = OfflineStore(dir)
        store.save("k", String.serializer(), "ok")
        dir.listFiles()!!.single().writeText("{not json")
        assertNull(store.load("k", String.serializer()))
    }

    @Test
    fun scanQueueKeepsPhotosAndState() = runBlocking {
        val root = tmp.newFolder("pending")
        val store = PendingScanStore(root)
        val photos = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        val scan = store.enqueue(photos)
        assertEquals(PendingScan.QUEUED, store.scans.value.single().state)
        store.photos(scan.id).zip(photos).forEach { (a, b) -> assertArrayEquals(b, a) }

        store.update(scan.id) { it.copy(state = PendingScan.READY, books = listOf(BookDto(title = "Dune", author = "Frank Herbert"))) }
        // A fresh store (an app restart) reads the same state from disk.
        val reopened = PendingScanStore(root).apply { refresh() }
        assertEquals("Dune", reopened.scans.value.single().books.single().title)

        reopened.remove(scan.id)
        assertTrue(reopened.scans.value.isEmpty())
        assertTrue(!File(root, scan.id).exists())
    }
}
