package com.varun.filemanager.media

import org.junit.Assert.*
import org.junit.Test

class ThresholdsTest {
    @Test fun valuesAreClampedAndSceneNeverStricterThanNormal() {
        val t = Thresholds(maxHamming = 99, sceneHamming = 3, windowMinutes = 0, blurThreshold = 1000.0).coerced()
        assertEquals(24, t.maxHamming)
        assertEquals(24, t.sceneHamming)
        assertEquals(1, t.windowMinutes)
        assertEquals(300.0, t.blurThreshold, 0.0)
    }

    @Test fun looserSettingGroupsMorePhotos() {
        fun p(id: Long, h: Long) = MediaItem(id, "u$id", null, "p$id", 1, id * 1000, 100, 100, false, hashes = longArrayOf(h))
        val items = listOf(p(1, 0L), p(2, 0xFFFL)) // 12 bits apart
        assertEquals(0, SimilarGrouper(maxHamming = 10).groupPhotos(items).size)
        assertEquals(1, SimilarGrouper(maxHamming = 14).groupPhotos(items).size)
    }
}
