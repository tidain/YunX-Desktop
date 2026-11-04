package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread

class ElasticAllocatorTest {
    @Test
    fun freshDownloadCoversEntireRegion() {
        val allocator = ElasticAllocator(1_000_000, 700_000, 4, 100_000)
        assertCoverage(700_000, 1_000_000, drain(allocator))
    }

    @Test
    fun resumeSkipsCompletedIslandsAndClipsBlocksAtTheirBoundaries() {
        val completed = listOf(900_000L..999_999L, 750_000L..799_999L)
        val allocator = ElasticAllocator(1_000_000, 700_000, 4, 100_000, completed)
        assertCoverage(700_000, 1_000_000, completed + drain(allocator))
    }

    @Test
    fun fullyCompletedRegionDoesNotAllocateMoreWork() {
        val allocator = ElasticAllocator(1_000_000, 700_000, 4, 100_000,
            listOf(700_000L..799_999L, 800_000L..999_999L))
        assertNull(allocator.take())
    }

    @Test
    fun repeatedResumeWithChangedSpeedAndWorkersKeepsExactCoverage() {
        val first = ElasticAllocator(10_000_000, 7_000_000, 4, 1_000_000)
        val allocated = drain(first)
        val completed = allocated.filterIndexed { index, _ -> index % 2 == 1 }
        val resumed = ElasticAllocator(10_000_000, 7_000_000, 8, 1_000_000, completed)
        resumed.recentSpeedBps = 20_000_000
        val second = drain(resumed)
        val completedAgain = completed + second.filterIndexed { index, _ -> index % 2 == 0 }
        val resumedAgain = ElasticAllocator(10_000_000, 7_000_000, 2, 1_000_000, completedAgain)
        assertCoverage(7_000_000, 10_000_000, completedAgain + drain(resumedAgain))
    }

    @Test
    fun concurrentWorkersAllocateEachMissingByteOnce() {
        val completed = listOf(8_000_000L..8_999_999L)
        val allocator = ElasticAllocator(10_000_000, 7_000_000, 8, 1_000_000, completed)
        val ranges = Collections.synchronizedList(mutableListOf<LongRange>())
        val workers = List(8) { thread { ranges.addAll(drain(allocator)) } }
        workers.forEach { it.join() }
        assertCoverage(7_000_000, 10_000_000, completed + ranges)
    }

    private fun drain(allocator: ElasticAllocator): List<LongRange> = buildList {
        while (true) add(allocator.take() ?: break)
    }

    private fun assertCoverage(start: Long, endExclusive: Long, ranges: List<LongRange>) {
        var cursor = start
        for (range in ranges.sortedBy { it.first }) {
            assertEquals("Gap or overlap before $range", cursor, range.first)
            cursor = range.last + 1
        }
        assertEquals(endExclusive, cursor)
    }
}
