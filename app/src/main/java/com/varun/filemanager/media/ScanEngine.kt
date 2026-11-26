package com.varun.filemanager.media

import android.content.Context

/**
 * One scan, used by both the Analyze button and the nightly worker.
 * 1. List media, plan against saved results (newest first).
 * 2. Hand back all still-valid saved results at once so the UI shows groups immediately.
 * 3. Decode only new or changed items, saving each batch to SQLite as it goes (so a stopped scan resumes).
 * 4. Drop saved rows for files that are gone, and write a report.
 */
class ScanEngine(context: Context, private val db: AnalysisDb = AnalysisDb(context.applicationContext), val scanner: MediaScanner = MediaScanner(context.applicationContext)) {

    fun run(
        source: String,
        thresholds: Thresholds = Thresholds(),
        /** Look for faces (eyes, smile, face sharpness). Photos saved without face data are analyzed again when this is on. */
        detectFaces: Boolean = true,
        /** Called once with every saved, still-valid result. */
        onCached: (List<MediaItem>) -> Unit = {},
        /** Called after each newly analyzed item. */
        onItem: (MediaItem) -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): List<MediaItem> {
        val started = System.currentTimeMillis()
        val plan = ScanPlanner.plan(scanner.queryAll(), db.loadAll(), detectFaces)
        val all = ArrayList<MediaItem>(plan.reuse)
        onCached(plan.reuse)
        var done = 0
        val total = plan.analyze.size
        onProgress(0, total)
        val batch = ArrayList<MediaItem>()
        scanner.openTagger(detectFaces)
        try {
            for (raw in plan.analyze) {
                if (isCancelled()) break
                scanner.analyze(raw)?.let { a -> all += a; batch += a; onItem(a) }
                done++
                if (batch.size >= 20) { db.save(batch); batch.clear() }
                onProgress(done, total)
            }
        } finally {
            db.save(batch)
            scanner.closeTagger()
        }
        val complete = done == total
        if (complete) db.delete(plan.removed)
        val g = SimilarGrouper(thresholds.maxHamming, thresholds.windowMinutes * 60_000L, sceneMaxHamming = thresholds.sceneHamming)
        db.addReport(ScanReport(
            started, System.currentTimeMillis(), source, if (complete) "complete" else "interrupted",
            total = plan.reuse.size + plan.analyze.size, reused = plan.reuse.size, analyzed = done, removed = if (complete) plan.removed.size else 0,
            photoGroups = g.groupPhotos(all).size, videoGroups = g.groupVideos(all).size, blurry = g.blurry(all, thresholds.blurThreshold).size,
        ))
        return all
    }

    fun reports(limit: Int = 10) = db.reports(limit)
}
