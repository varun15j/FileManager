package com.varun.filemanager.media

import org.junit.Assert.*
import org.junit.Test

class FaceRulesTest {
    private fun face(blink: Float = 0.05f, smile: Float = 0.1f, frown: Float = 0.05f, sharp: Double = 120.0, w: Float = 0.2f) =
        FaceInfo(0.5f, 0.5f, w, w, blink, smile, frown, sharp)

    @Test fun classifiesEyesSmileSadAndBlur() {
        assertTrue(FaceRules.eyesClosed(face(blink = 0.9f)))
        assertFalse(FaceRules.eyesClosed(face(blink = 0.1f)))
        assertTrue(FaceRules.smiling(face(smile = 0.7f)))
        assertTrue(FaceRules.sad(face(frown = 0.5f, smile = 0.05f)))
        assertFalse(FaceRules.sad(face(frown = 0.5f, smile = 0.6f))) // a smile cancels a frown reading
        assertTrue(FaceRules.blurry(face(sharp = 10.0)))
    }

    @Test fun scoreAndDescribe() {
        val good = listOf(face(smile = 0.8f))
        val bad = listOf(face(blink = 0.9f, sharp = 10.0))
        assertTrue(FaceRules.score(good) > FaceRules.score(bad))
        assertEquals(0, FaceRules.score(emptyList()))
        assertEquals(0, FaceRules.score(listOf(face(blink = 0.9f, w = 0.01f)))) // too small to judge
        assertEquals("1 face: eyes closed ×1, blurry face ×1", FaceRules.describe(bad))
        assertEquals("", FaceRules.describe(emptyList()))
    }

    @Test fun keeperPrefersOpenEyesOverResolution() {
        fun p(id: Long, w: Int, faces: List<FaceInfo>) = MediaItem(id, "u$id", null, "p$id", 1, id * 1000, w, w, false, hashes = longArrayOf(0), faces = faces)
        val blinking = p(1, 4000, listOf(face(blink = 0.9f)))
        val open = p(2, 2000, listOf(face(smile = 0.8f)))
        assertEquals(2L, SimilarGrouper().groupPhotos(listOf(blinking, open)).single().keeper.id)
    }

    @Test fun photoLevelChecksIgnoreTinyFaces() {
        val closed = FaceInfo(0.5f, 0.5f, 0.2f, 0.3f, 0.9f, 0f, 0f, 100.0)
        val tinyClosed = closed.copy(w = 0.02f)
        assertTrue(FaceRules.hasEyesClosed(listOf(closed)))
        assertFalse(FaceRules.hasEyesClosed(listOf(tinyClosed)))
        assertTrue(FaceRules.hasBlurryFace(listOf(closed.copy(blink = 0f, sharpness = 5.0))))
        assertTrue(FaceRules.hasSadMouth(listOf(closed.copy(blink = 0f, frown = 0.6f))))
        assertFalse(FaceRules.hasSadMouth(listOf(closed.copy(blink = 0f, frown = 0.6f, smile = 0.5f))))
    }
}
