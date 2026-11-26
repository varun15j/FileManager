package com.varun.filemanager.media

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ScanPlannerTest {
    private fun item(id: Long, size: Long = 100, mod: Long = 5, taken: Long = id, hashes: LongArray = longArrayOf(1), av: Int = ScanPlanner.ANALYSIS_VERSION) =
        MediaItem(id, "u$id", null, "n$id", size, taken, 10, 10, false, hashes = hashes, modifiedMs = mod, analysisVersion = av)

    @Test fun reusesUnchangedAnalyzesNewAndChangedDropsRemoved() {
        val saved = mapOf("u1" to item(1), "u2" to item(2), "u3" to item(3), "u4" to item(4, hashes = LongArray(0)))
        val current = listOf(
            item(1, hashes = LongArray(0)),          // unchanged: reuse the saved hashes
            item(2, size = 999, hashes = LongArray(0)), // size changed: analyze again
            item(4, hashes = LongArray(0)),          // saved row has no hashes: analyze
            item(5, taken = 50, hashes = LongArray(0)), // new
        )
        val plan = ScanPlanner.plan(current, saved)
        assertEquals(listOf("u1"), plan.reuse.map { it.uri })
        assertArrayEquals(longArrayOf(1), plan.reuse[0].hashes)
        assertEquals(setOf("u2", "u4", "u5"), plan.analyze.map { it.uri }.toSet())
        assertEquals("u5", plan.analyze.first().uri) // newest first
        assertEquals(setOf("u3"), plan.removed)
    }

    @Test fun photosWithoutFaceDataAreShownAtOnceAndUpgradedLater() {
        val saved = mapOf("u1" to item(1, av = 0))
        val current = listOf(item(1, hashes = LongArray(0)))
        val withFaces = ScanPlanner.plan(current, saved, requireFaces = true)
        assertEquals(listOf("u1"), withFaces.reuse.map { it.uri })   // visible immediately
        assertEquals(listOf("u1"), withFaces.upgrade.map { it.uri }) // faces added in the background
        assertTrue(withFaces.analyze.isEmpty())
        val noFaces = ScanPlanner.plan(current, saved, requireFaces = false)
        assertEquals(listOf("u1"), noFaces.reuse.map { it.uri })
        assertTrue(noFaces.upgrade.isEmpty())
    }

    @Test fun savedResultsAreFoundByPathWhenTheUriChanged() {
        fun at(id: Long, path: String) = item(id).copy(path = path)
        val saved = mapOf("old1" to at(1, "/a/1.jpg").copy(uri = "old1"))
        val current = listOf(at(9, "/a/1.jpg").copy(hashes = LongArray(0)))
        val plan = ScanPlanner.plan(current, saved)
        assertEquals(listOf("u9"), plan.reuse.map { it.uri })
        assertArrayEquals(longArrayOf(1), plan.reuse[0].hashes)
        assertEquals(listOf("u9"), plan.rekeyed.map { it.uri })
        assertTrue(plan.analyze.isEmpty())
        assertEquals(setOf("old1"), plan.removed)
    }

    @Test fun faceCodecRoundTrip() {
        val f = listOf(FaceInfo(0.5f, 0.4f, 0.2f, 0.3f, 0.8f, 0.1f, 0.35f, 22.5))
        assertEquals(f, ItemCodec.parseFaces(ItemCodec.faces(f)))
        assertEquals(0, ItemCodec.parseFaces("").size)
    }

    @Test fun codecRoundTrip() {
        val objs = listOf(DetectedObject(ObjKind.PERSON, "person", 0.25f, 0.5f, 0.1f, 0.3f))
        assertEquals(objs, ItemCodec.parseObjects(ItemCodec.objects(objs)))
        assertArrayEquals(longArrayOf(-5, 0, Long.MAX_VALUE), ItemCodec.parseHashes(ItemCodec.hashes(longArrayOf(-5, 0, Long.MAX_VALUE))))
        assertEquals(0, ItemCodec.parseObjects("").size)
        assertEquals(0, ItemCodec.parseHashes(null).size)
    }

    @Test fun nightDelayIsNextTwoAm() {
        val zone = ZoneId.of("UTC")
        val at23 = ZonedDateTime.of(2026, 11, 25, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(3 * 3600_000L, NightWindow.delayUntil(2, at23, zone))
        val at01 = ZonedDateTime.of(2026, 11, 25, 1, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(3600_000L, NightWindow.delayUntil(2, at01, zone))
        val at02 = ZonedDateTime.of(2026, 11, 25, 2, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(24 * 3600_000L, NightWindow.delayUntil(2, at02, zone))
    }
}
