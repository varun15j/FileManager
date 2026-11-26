package com.varun.filemanager.media

import org.junit.Assert.*
import org.junit.Test

class EmbeddingsTest {
    private fun v(vararg x: Float) = x

    @Test fun normalizeQuantizeAndDotWork() {
        val n = Embeddings.normalize(v(3f, 4f))
        assertEquals(0.6f, n[0], 1e-6f); assertEquals(0.8f, n[1], 1e-6f)
        val q = Embeddings.quantize(v(3f, 4f))
        assertEquals(1f, Embeddings.dotQ(q, n), 0.02f)
        val back = Embeddings.dequantize(q)
        assertEquals(1f, Embeddings.dot(back, n), 0.01f)
    }

    @Test fun categoryMatchesLookalikesAndRejectsOthers() {
        val samples = listOf(v(1f, 0.1f, 0f), v(1f, -0.1f, 0f), v(0.9f, 0f, 0.1f))
        val model = CategoryModel.build(samples)
        assertTrue(model.matches(Embeddings.quantize(v(1f, 0.05f, 0f))))
        assertFalse(model.matches(Embeddings.quantize(v(0f, 1f, 0f))))
        // every sample matches its own category
        samples.forEach { assertTrue(model.matches(Embeddings.quantize(it))) }
    }

    @Test fun notThisExamplesPushCloseLookalikesOut() {
        val base = CategoryModel.build(listOf(v(1f, 0f, 0f), v(1f, 0.2f, 0f)))
        val lookalike = Embeddings.quantize(v(1f, 0.6f, 0f))
        val model = CategoryModel(base.centroid, 0.5f)
        assertTrue(model.matches(lookalike))
        val withNegative = CategoryModel(base.centroid, 0.5f, listOf(Embeddings.normalize(v(1f, 0.6f, 0f))))
        assertFalse(withNegative.matches(lookalike))
    }

    @Test fun singleSampleUsesDefaultThreshold() {
        assertEquals(CategoryModel.SINGLE_SAMPLE_THRESHOLD, CategoryModel.build(listOf(v(1f, 2f))).threshold, 0f)
    }

    @Test fun commonTraitsOnlyListWhatMostSamplesShare() {
        fun item(i: Long, faces: List<FaceInfo>) = MediaItem(i, "u$i", null, "n$i", 1, 1, 100, 200, false, faces = faces)
        val sad = FaceInfo(0.5f, 0.5f, 0.2f, 0.2f, 0.05f, 0f, 0.6f, 100.0)
        val happy = sad.copy(frown = 0f, smile = 0.9f)
        val items = listOf(item(1, listOf(sad)), item(2, listOf(sad)), item(3, listOf(happy)), item(4, emptyList()))
        val t = CommonTraits.of(items)
        assertTrue(t.contains("portrait shape in 4 of 4"))
        assertFalse(t.any { it.startsWith("smiling") })
        assertFalse(t.any { it.startsWith("sad mouth") }) // 2 of 4 is below the 60% share
        assertTrue(CommonTraits.of(emptyList()).isEmpty())
    }
}
