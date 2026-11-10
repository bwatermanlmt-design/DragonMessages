package org.fossify.messages.filtering

import org.fossify.messages.helpers.LockerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.10.19: the per-message crypto fans out over a small pool, so
 * the order guarantee is the whole contract — results in input
 * order, whatever order the workers finish in — and the pool has
 * to actually run things side by side, or the change is decoration.
 */
class LockerParallelTest {

    @Test
    fun parallelMapKeepsInputOrder() {
        // Each item sleeps a different amount, longest first, so a
        // naive completion-order collection would scramble the list.
        val input = (1..8).toList()
        val output = LockerManager.parallelMap(input) { n ->
            Thread.sleep((8 - n) * 10L)
            n * 10
        }
        assertEquals(listOf(10, 20, 30, 40, 50, 60, 70, 80), output)
    }

    @Test
    fun parallelMapActuallyRunsInParallel() {
        val threads = ConcurrentHashMap.newKeySet<String>()
        val maxConcurrent = AtomicInteger(0)
        val current = AtomicInteger(0)
        LockerManager.parallelMap((1..8).toList()) {
            threads.add(Thread.currentThread().name)
            val now = current.incrementAndGet()
            maxConcurrent.updateAndGet { max -> maxOf(max, now) }
            Thread.sleep(100)
            current.decrementAndGet()
        }
        assertTrue(threads.size > 1)
        assertTrue(maxConcurrent.get() > 1)
    }

    @Test
    fun parallelMapSingleItemRunsInline() {
        assertEquals(listOf(42), LockerManager.parallelMap(listOf(21)) { it * 2 })
    }

    @Test
    fun parallelMapEmptyList() {
        assertEquals(emptyList<Int>(), LockerManager.parallelMap(emptyList<Int>()) { it * 2 })
    }

    @Test
    fun parallelMapPropagatesFailure() {
        try {
            LockerManager.parallelMap(listOf(1, 2, 3)) {
                if (it == 2) throw IllegalStateException("boom")
                it
            }
            throw AssertionError("expected failure")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("boom") == true || e.cause?.message?.contains("boom") == true)
        }
    }
}
