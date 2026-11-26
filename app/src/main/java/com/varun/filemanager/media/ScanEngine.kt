package com.varun.filemanager.media

import android.content.Context

/**
 * One scan, used by both the Analyze button and the nightly worker.
 * 1. List media, plan against saved results (newest first).
 * 2. Hand back all still-valid saved results at once so the UI shows groups immediately.
 * 3. Decode only new or changed items, saving each batch to SQLite as it goes (so a stopped scan resumes).
 * 4. Drop saved rows for files that are gone, write a report, and copy the database to Documents/FileManagerTraining/db
 *    (restored automatically after an uninstall or when the database is empty).
 */
class ScanEngine(context: Context, private val db: AnalysisDb = AnalysisDb(context.applicationContext), val scanner: MediaScanner = MediaScanner(context.applicationContext)) {

    /** Saved results that still match the files on the phone, with no decoding. Shown the moment the app opens. */
    fun cached(): List<MediaItem> {
        db.restoreIfEmpty()
        return ScanPlanner.plan(scanner.queryAll(), db.loadAll(), requireFaces = false).reuse
    }

    fun run(
        source: String,
        thresholds: Thresholds = Thresholds(),
        /** Look for faces (eyes, smile, face sharpness). Saved photos without face data are shown at once and get faces afterwards. */
        detectFaces: Boolean = true,
        /** Called once with every saved, still-valid result. */
        onCached: (List<MediaItem>) -> Unit = {},
        /** Called after each newly analyzed (or upgraded) item. */
        onItem: (MediaItem) -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): List<MediaItem> {
        val started = System.currentTimeMillis()
        db.restoreIfEmpty()
        val plan = ScanPlanner.plan(scanner.queryAll(), db.loadAll(), detectFaces)
        db.save(plan.rekeyed)
        val all = LinkedHashMap<String, MediaItem>()
        plan.reuse.forEach { all[it.uri] = it }
        onCached(plan.reuse)
        // New files first, then saved photos that still need face data.
        val work = plan.analyze + plan.upgrade
        var done = 0
        val total = work.size
        onProgress(0, total)
        val batch = ArrayList<MediaItem>()
        var lastBackup = System.currentTimeMillis()
        scanner.openTagger(detectFaces)
        try {
            for (raw in work) {
                if (isCancelled()) break
                scanner.analyze(raw)?.let { a -> all[a.uri] = a; batch += a; onItem(a) }
                done++
                if (batch.size >= 20) {
                    db.save(batch); batch.clear()
                    if (System.currentTimeMillis() - lastBackup > BACKUP_EVERY_MS) { db.backup(); lastBackup = System.currentTimeMillis() }
                }
                onProgress(done, total)
            }
        } finally {
            db.save(batch)
            scanner.closeTagger()
        }
        val complete = done == total
        if (complete) db.delete(plan.removed)
        val items = all.values.toList()
        val g = SimilarGrouper(thresholds.maxHamming, thresholds.windowMinutes * 60_000L, sceneMaxHamming = thresholds.sceneHamming)
        db.addReport(ScanReport(
            started, System.currentTimeMillis(), source, if (complete) "complete" else "interrupted",
            total = plan.reuse.size + plan.analyze.size, reused = plan.reuse.size - plan.upgrade.size, analyzed = done, removed = if (complete) plan.removed.size else 0,
            photoGroups = g.groupPhotos(items).size, videoGroups = g.groupVideos(items).size, blurry = g.blurry(items, thresholds.blurThreshold).size,
        ))
        db.backup()
        return items
    }

    fun reports(limit: Int = 10) = db.reports(limit)

    private companion object { const val BACKUP_EVERY_MS = 3 * 60_000L }
}
