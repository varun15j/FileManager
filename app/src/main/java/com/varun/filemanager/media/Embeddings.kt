package com.varun.filemanager.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Image embeddings: one vector per photo that says what it looks like. Similar photos have a high dot product. */
object Embeddings {
    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }

    /** One signed byte per value (needs an L2-normalized vector, so every value is within -1..1). 1 KB per photo. */
    fun quantize(v: FloatArray): ByteArray {
        val u = normalize(v)
        return ByteArray(u.size) { (u[it] * 127f).toInt().coerceIn(-127, 127).toByte() }
    }

    fun dequantize(q: ByteArray): FloatArray = normalize(FloatArray(q.size) { q[it] / 127f })

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until min(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    /** Dot product of a quantized vector with a normal one, without copying. */
    fun dotQ(q: ByteArray, c: FloatArray): Float {
        var s = 0f
        for (i in 0 until min(q.size, c.size)) s += q[i] * c[i]
        return s / 127f
    }

    fun mean(vs: List<FloatArray>): FloatArray {
        val out = FloatArray(vs.first().size)
        for (v in vs) for (i in out.indices) out[i] += v[i]
        return FloatArray(out.size) { out[it] / vs.size }
    }
}

/**
 * What the user's sample photos have in common, as the average look of the samples plus a similarity cut-off.
 * A photo matches if it is close enough to the average and not closer to a "not this" example.
 */
class CategoryModel(val centroid: FloatArray, val threshold: Float, val negatives: List<FloatArray> = emptyList()) {
    fun score(q: ByteArray): Float = Embeddings.dotQ(q, centroid)

    fun matches(q: ByteArray): Boolean {
        val s = score(q)
        return s >= threshold && negatives.none { Embeddings.dotQ(q, it) > s }
    }

    companion object {
        const val SINGLE_SAMPLE_THRESHOLD = 0.75f
        const val MIN_THRESHOLD = 0.45f
        const val MAX_THRESHOLD = 0.9f

        /** The cut-off sits a little below the least typical sample, so every sample matches. */
        fun build(samples: List<FloatArray>): CategoryModel {
            require(samples.isNotEmpty())
            val unit = samples.map { Embeddings.normalize(it) }
            val centroid = Embeddings.normalize(Embeddings.mean(unit))
            val threshold = if (unit.size == 1) SINGLE_SAMPLE_THRESHOLD
            else (unit.minOf { Embeddings.dot(it, centroid) } - 0.04f).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
            return CategoryModel(centroid, threshold)
        }
    }
}

/** Plain-language traits shared by most of a set of photos, from what the analyzer already knows about them. */
object CommonTraits {
    /** A trait is "common" if at least this share of the photos has it. */
    const val SHARE = 0.6

    fun of(items: List<MediaItem>): List<String> {
        val n = items.size
        if (n == 0) return emptyList()
        val out = ArrayList<String>()
        fun add(label: String, count: Int) { if (count > 0 && count >= n * SHARE) out += "$label in $count of $n" }
        fun judgedFace(i: MediaItem, test: (FaceInfo) -> Boolean) = i.faces.any { it.w >= FaceRules.MIN_FACE_WIDTH && test(it) }
        add("faces", items.count { i -> judgedFace(i) { true } })
        add("eyes closed", items.count { FaceRules.hasEyesClosed(it.faces) })
        add("smiling", items.count { i -> judgedFace(i) { FaceRules.smiling(it) } })
        add("sad mouth", items.count { FaceRules.hasSadMouth(it.faces) })
        add("blurry face", items.count { FaceRules.hasBlurryFace(it.faces) })
        add("a person", items.count { i -> i.objects.any { it.kind == ObjKind.PERSON } })
        add("portrait shape", items.count { it.height > it.width })
        add("landscape shape", items.count { it.width > it.height })
        return out
    }
}
