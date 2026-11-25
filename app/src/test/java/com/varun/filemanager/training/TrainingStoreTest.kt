package com.varun.filemanager.training

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TrainingStoreTest {
    private fun d(kind: String, action: String, items: Int = 3, overrides: Int = 0) =
        Decision(1L, kind, "g", action, items, items - 1, 1, overrides)

    @Test fun decisionRoundTrip() {
        val x = Decision(5L, "photo", "ab\"c", "approved", 4, 3, 1, 1)
        assertEquals(x, Decision.parse(x.toJson()))
    }

    @Test fun statsAccuracyAndOverrides() {
        val s = TrainingStats.from(listOf(d("photo", "approved", 4, 1), d("photo", "approved", 4, 0), d("photo", "rejected"), d("photo", "cancelled"), d("video", "rejected")))
        val p = s.byKind.getValue("photo")
        assertEquals(2.0 / 3.0, p.accuracy!!, 0.0001)
        assertEquals(1.0 / 8.0, p.overrideRate!!, 0.0001)
        assertEquals(0.0, s.byKind.getValue("video").accuracy!!, 0.0001)
        assertEquals(4, s.totalReviewed)
    }

    @Test fun storeReloadsAfterReopen() {
        val dir = Files.createTempDirectory("tr").toFile()
        TrainingStore(dir).apply { logDecision(d("photo", "approved")); logDecision(d("blur", "rejected")) }
        val reopened = TrainingStore(dir) // like a reinstall: new object, same folder
        assertEquals(2, reopened.decisions().size)
        assertEquals(1.0, reopened.stats().byKind.getValue("photo").accuracy!!, 0.0001)
    }

    @Test fun samplesCanBeSwitchedOff() {
        val dir = Files.createTempDirectory("tr").toFile()
        val s = TrainingStore(dir)
        s.saveSample("g1", 0, byteArrayOf(1)); assertTrue(s.hasSample("g1"))
        s.samplesEnabled = false
        s.saveSample("g2", 0, byteArrayOf(1)); assertFalse(s.hasSample("g2"))
        assertFalse(TrainingStore(dir).samplesEnabled)
    }

    @Test fun jsonNestedAndEscapes() {
        val j = MiniJson.obj("name" to "a\nb", "items" to listOf(mapOf("h" to 1L, "ok" to true)))
        assertEquals("{\"name\":\"a\\nb\",\"items\":[{\"h\":1,\"ok\":true}]}", j)
    }
}
