package org.fossify.messages.filtering

import org.fossify.messages.helpers.LockerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ciphertext chunking that keeps a locked photo MMS readable:
 * one row must never approach SQLite's 2MB CursorWindow, however big
 * the photo behind it is (the 1.10.12 failure on Ben's phone).
 */
class LockerChunkingTest {

    @Test
    fun emptyCiphertextIsOneEmptyChunk() {
        val chunks = LockerManager.chunkCiphertext(7L, false, "")
        assertEquals(1, chunks.size)
        assertEquals("", LockerManager.joinCiphertext(chunks.map { it.chunk }))
    }

    @Test
    fun shortCiphertextIsOneChunk() {
        val chunks = LockerManager.chunkCiphertext(7L, true, "abc123")
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals(7L, chunks[0].id)
        assertTrue(chunks[0].isMMS)
        assertEquals("abc123", LockerManager.joinCiphertext(chunks.map { it.chunk }))
    }

    @Test
    fun exactMultipleSplitsEvenly() {
        val original = "x".repeat(LockerManager.CIPHER_CHUNK_SIZE * 2)
        val chunks = LockerManager.chunkCiphertext(9L, false, original)
        assertEquals(2, chunks.size)
        assertEquals(listOf(0, 1), chunks.map { it.chunkIndex })
        assertTrue(chunks.all { it.chunk.length == LockerManager.CIPHER_CHUNK_SIZE })
        assertEquals(original, LockerManager.joinCiphertext(chunks.map { it.chunk }))
    }

    @Test
    fun largeCiphertextRoundTrips() {
        // A 3MB ciphertext: bigger than the CursorWindow, the shape
        // that stranded Ben's thread.
        val original = "A".repeat(3_000_000)
        val chunks = LockerManager.chunkCiphertext(11L, false, original)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.chunk.length <= LockerManager.CIPHER_CHUNK_SIZE })
        assertEquals((0 until chunks.size).toList(), chunks.map { it.chunkIndex })
        assertTrue(chunks.all { it.id == 11L && !it.isMMS })
        assertEquals(original, LockerManager.joinCiphertext(chunks.map { it.chunk }))
    }

    @Test
    fun chunkSizeStaysWellBelowTheCursorWindow() {
        assertTrue(LockerManager.CIPHER_CHUNK_SIZE < 2 * 1024 * 1024)
    }
}
