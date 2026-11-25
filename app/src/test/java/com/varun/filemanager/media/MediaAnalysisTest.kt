package com.varun.filemanager.media

import org.junit.Assert.*
import org.junit.Test

class MediaAnalysisTest {
    private fun gradient(w: Int, h: Int, shift: Int = 0) =
        Gray(w, h, IntArray(w * h) { ((it % w) * 255 / w + shift).coerceIn(0, 255) })

    private fun checker(n: Int) = Gray(n, n, IntArray(n * n) { if ((it % n + it / n) % 2 == 0) 0 else 255 })

    private fun photo(id: Long, t: Long, hash: Long, w: Int = 1000, h: Int = 1000, size: Long = 1000, blur: Double = 100.0) =
        MediaItem(id, "u$id", null, "p$id", size, t, w, h, false, hashes = longArrayOf(hash), blur = blur)

    @Test fun sameImageDifferentSizeHashesClose() {
        val a = ImageMetrics.dHash(gradient(400, 300))
        val b = ImageMetrics.dHash(gradient(200, 150))
        assertTrue(ImageMetrics.hamming(a, b) <= 4)
    }

    @Test fun sharpHasHigherBlurScoreThanFlat() {
        val sharp = ImageMetrics.blurScore(checker(64))
        val flat = ImageMetrics.blurScore(Gray(64, 64, IntArray(64 * 64) { 128 }))
        assertTrue(sharp > flat)
        assertEquals(0.0, flat, 0.0001)
    }

    @Test fun groupsNearbyTimeAndHashOnly() {
        val g = SimilarGrouper()
        val items = listOf(
            photo(1, 0, 0L), photo(2, 60_000, 0b111L),          // similar, 1 min apart
            photo(3, 3_600_000, 0L),                             // same hash but an hour later
            photo(4, 30_000, -1L),                               // far hash
        )
        val groups = g.groupPhotos(items)
        assertEquals(1, groups.size)
        assertEquals(setOf(1L, 2L), groups[0].items.map { it.id }.toSet())
    }

    @Test fun keeperIsHighestResolutionThenSharpest() {
        val g = SimilarGrouper()
        val small = photo(1, 0, 0, w = 500, h = 500, size = 9000)
        val big = photo(2, 1000, 0, w = 2000, h = 2000, size = 100)
        val group = g.groupPhotos(listOf(small, big)).single()
        assertEquals(2L, group.keeper.id)
        assertEquals(9000L, group.reclaimableBytes)
    }

    @Test fun similarVideosByDurationAndFrames() {
        fun v(id: Long, dur: Long, vararg h: Long) = MediaItem(id, "v$id", null, "v$id", 10, 0, 1920, 1080, true, dur, h)
        val groups = SimilarGrouper().groupVideos(listOf(
            v(1, 10_000, 0, 0, 0), v(2, 10_100, 1, 0, 3), v(3, 10_000, -1L, -1L, -1L), v(4, 50_000, 0, 0, 0)
        ))
        assertEquals(1, groups.size)
        assertEquals(setOf(1L, 2L), groups[0].items.map { it.id }.toSet())
    }

    @Test fun blurryListSortedWorstFirst() {
        val l = SimilarGrouper().blurry(listOf(photo(1, 0, 0, blur = 10.0), photo(2, 0, 0, blur = 5.0), photo(3, 0, 0, blur = 500.0)))
        assertEquals(listOf(2L, 1L), l.map { it.id })
    }
}
