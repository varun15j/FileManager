package com.varun.filemanager.media

import kotlin.math.abs

data class MediaItem(
    val id: Long,
    val uri: String,
    val path: String?,
    val name: String,
    val sizeBytes: Long,
    val dateTakenMs: Long,
    val width: Int,
    val height: Int,
    val isVideo: Boolean,
    val durationMs: Long = 0,
    /** Photos: one dHash. Videos: one dHash per sampled frame (same sample points for every video). */
    val hashes: LongArray = LongArray(0),
    val blur: Double = Double.NaN,
    /** People and vehicles found by the on-device detector (empty if not run or none found). */
    val objects: List<DetectedObject> = emptyList(),
    /** Last-modified time from MediaStore; with the size it tells us a cached result is stale. */
    val modifiedMs: Long = 0,
    /** Faces with expression scores (empty if face detection was off or none found). */
    val faces: List<FaceInfo> = emptyList(),
    /** Which analysis produced this result; older results are re-analyzed when a newer analysis adds data. */
    val analysisVersion: Int = 0,
    /** Quantized image embedding (see [Embeddings]); only computed once the user has a custom category. */
    val embedding: ByteArray? = null,
) {
    val pixels: Long get() = width.toLong() * height
}

data class SimilarGroup(val items: List<MediaItem>, val keeper: MediaItem) {
    /** Stable for the same set of items, used to name log entries and sample folders. */
    val id: String get() = items.map { it.uri }.sorted().joinToString("|").hashCode().toUInt().toString(16)
    val reclaimableBytes: Long get() = items.filter { it !== keeper }.sumOf { it.sizeBytes }
}

class SimilarGrouper(
    private val maxHamming: Int = 10,
    /** Photos only count as similar if taken within this window. Null disables the time check. */
    private val photoWindowMs: Long? = 10 * 60_000L,
    private val maxVideoDurationDiffRatio: Double = 0.03,
    /** Looser hash limit that is accepted only when both photos show the same people/vehicles in the same places. */
    private val sceneMaxHamming: Int = 20,
) {
    fun groupPhotos(items: List<MediaItem>): List<SimilarGroup> {
        val photos = items.filter { !it.isVideo && it.hashes.isNotEmpty() }.sortedBy { it.dateTakenMs }
        val uf = UnionFind(photos.size)
        for (i in photos.indices) {
            for (j in i + 1 until photos.size) {
                if (photoWindowMs != null && photos[j].dateTakenMs - photos[i].dateTakenMs > photoWindowMs) break
                val d = ImageMetrics.hamming(photos[i].hashes[0], photos[j].hashes[0])
                if (d <= maxHamming || (d <= sceneMaxHamming && SceneMatcher.sameScene(photos[i].objects, photos[j].objects))) uf.union(i, j)
            }
        }
        return toGroups(photos, uf)
    }

    /** Videos are similar if durations are close and the sampled frames match on average. */
    fun groupVideos(items: List<MediaItem>): List<SimilarGroup> {
        val vids = items.filter { it.isVideo && it.hashes.isNotEmpty() }
        val uf = UnionFind(vids.size)
        for (i in vids.indices) for (j in i + 1 until vids.size) {
            val a = vids[i]; val b = vids[j]
            if (a.hashes.size != b.hashes.size) continue
            val longer = maxOf(a.durationMs, b.durationMs).coerceAtLeast(1)
            if (abs(a.durationMs - b.durationMs).toDouble() / longer > maxVideoDurationDiffRatio) continue
            val avg = a.hashes.indices.sumOf { ImageMetrics.hamming(a.hashes[it], b.hashes[it]) }.toDouble() / a.hashes.size
            if (avg <= maxHamming) uf.union(i, j)
        }
        return toGroups(vids, uf)
    }

    private fun toGroups(list: List<MediaItem>, uf: UnionFind): List<SimilarGroup> =
        list.indices.groupBy { uf.find(it) }.values
            .filter { it.size > 1 }
            .map { idx -> idx.map { list[it] }.let { SimilarGroup(it, bestToKeep(it)) } }
            .sortedByDescending { it.reclaimableBytes }

    /** Best faces first (eyes open, sharp, not frowning), then highest resolution, sharpest, largest. Only a suggestion; the user decides. */
    fun bestToKeep(items: List<MediaItem>): MediaItem =
        items.maxWithOrNull(
            compareBy<MediaItem>({ FaceRules.score(it.faces) }, { it.pixels }, { if (it.blur.isNaN()) 0.0 else it.blur }, { it.sizeBytes })
        )!!

    fun blurry(items: List<MediaItem>, threshold: Double = 60.0): List<MediaItem> =
        items.filter { !it.isVideo && !it.blur.isNaN() && it.blur < threshold }.sortedBy { it.blur }

    private class UnionFind(n: Int) {
        private val p = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (p[r] != r) { p[r] = p[p[r]]; r = p[r] }; return r }
        fun union(a: Int, b: Int) { p[find(a)] = find(b) }
    }
}
