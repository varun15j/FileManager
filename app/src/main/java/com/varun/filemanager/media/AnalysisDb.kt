package com.varun.filemanager.media

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import java.io.File

/** One finished or interrupted scan, kept so the user (and later training) can see what happened. */
data class ScanReport(
    val startedAt: Long, val finishedAt: Long,
    /** manual or background */
    val source: String,
    /** complete or interrupted */
    val status: String,
    val total: Int, val reused: Int, val analyzed: Int, val removed: Int,
    val photoGroups: Int, val videoGroups: Int, val blurry: Int,
)

/**
 * SQLite store of analysis results, one row per photo/video, so a scan only has to decode new or changed
 * files. Rows are written as each item is analyzed, so a scan that was stopped carries on where it left off.
 */
class AnalysisDb(private val appContext: Context, private val mirror: File? = defaultMirror()) : SQLiteOpenHelper(appContext, DbMirror.DB_NAME, null, 2) {
    private val localFile: File get() = appContext.getDatabasePath(DbMirror.DB_NAME)

    init { if (!localFile.exists()) restoreFromMirror() }

    /**
     * Copies the saved database back from Documents (after an uninstall or a cleared app). Returns true if a
     * valid copy was restored. Needs "All files access"; without it the mirror simply is not there to read.
     */
    @Synchronized fun restoreFromMirror(): Boolean {
        val m = mirror?.takeIf { it.isFile && it.length() > 0 } ?: return false
        close()
        return try {
            DbMirror.deleteDb(localFile)
            localFile.parentFile?.mkdirs()
            DbMirror.copyAtomic(m, localFile)
            itemCount() // opens the copy: fails if it is damaged or from a newer app
            true
        } catch (e: Exception) {
            close(); DbMirror.deleteDb(localFile); false
        }
    }

    /** If this database is empty but a backup holds results, bring the backup back. */
    @Synchronized fun restoreIfEmpty(): Boolean = mirror != null && itemCount() == 0 && restoreFromMirror()

    @Synchronized fun itemCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM items", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Writes a consistent copy to Documents. Never replaces a backup with an empty database. */
    @Synchronized fun backup(): Boolean {
        val m = mirror ?: return false
        return try {
            if (itemCount() == 0) return false
            writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            DbMirror.copyAtomic(localFile, m)
            true
        } catch (e: Exception) { false }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE items(
            uri TEXT PRIMARY KEY, id INTEGER, video INTEGER, path TEXT, name TEXT, size INTEGER,
            taken INTEGER, modified INTEGER, w INTEGER, h INTEGER, duration INTEGER,
            hashes TEXT, blur REAL, objects TEXT, analyzed_at INTEGER, faces TEXT, av INTEGER DEFAULT 0)""")
        db.execSQL("CREATE INDEX items_taken ON items(taken DESC)")
        db.execSQL("""CREATE TABLE reports(
            id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER, finished INTEGER, source TEXT, status TEXT,
            total INTEGER, reused INTEGER, analyzed INTEGER, removed INTEGER,
            photo_groups INTEGER, video_groups INTEGER, blurry INTEGER)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("ALTER TABLE items ADD COLUMN faces TEXT")
            db.execSQL("ALTER TABLE items ADD COLUMN av INTEGER DEFAULT 0")
        }
    }

    /** All saved results, keyed by uri, newest first. */
    fun loadAll(): Map<String, MediaItem> {
        val out = LinkedHashMap<String, MediaItem>()
        readableDatabase.rawQuery("SELECT uri,id,video,path,name,size,taken,modified,w,h,duration,hashes,blur,objects,faces,av FROM items ORDER BY taken DESC", null).use { c ->
            while (c.moveToNext()) {
                val uri = c.getString(0)
                out[uri] = MediaItem(
                    id = c.getLong(1), uri = uri, path = c.getString(3), name = c.getString(4) ?: "", sizeBytes = c.getLong(5),
                    dateTakenMs = c.getLong(6), width = c.getInt(8), height = c.getInt(9), isVideo = c.getInt(2) == 1,
                    durationMs = c.getLong(10), hashes = ItemCodec.parseHashes(c.getString(11)),
                    blur = if (c.isNull(12)) Double.NaN else c.getDouble(12), objects = ItemCodec.parseObjects(c.getString(13)),
                    modifiedMs = c.getLong(7), faces = ItemCodec.parseFaces(c.getString(14)), analysisVersion = c.getInt(15),
                )
            }
        }
        return out
    }

    @Synchronized fun save(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (i in items) db.insertWithOnConflict("items", null, ContentValues().apply {
                put("uri", i.uri); put("id", i.id); put("video", if (i.isVideo) 1 else 0); put("path", i.path); put("name", i.name)
                put("size", i.sizeBytes); put("taken", i.dateTakenMs); put("modified", i.modifiedMs); put("w", i.width); put("h", i.height)
                put("duration", i.durationMs); put("hashes", ItemCodec.hashes(i.hashes))
                if (i.blur.isNaN()) putNull("blur") else put("blur", i.blur)
                put("objects", ItemCodec.objects(i.objects)); put("analyzed_at", now)
                put("faces", ItemCodec.faces(i.faces)); put("av", i.analysisVersion)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun delete(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try { uris.forEach { db.delete("items", "uri=?", arrayOf(it)) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    @Synchronized fun addReport(r: ScanReport) {
        writableDatabase.insert("reports", null, ContentValues().apply {
            put("started", r.startedAt); put("finished", r.finishedAt); put("source", r.source); put("status", r.status)
            put("total", r.total); put("reused", r.reused); put("analyzed", r.analyzed); put("removed", r.removed)
            put("photo_groups", r.photoGroups); put("video_groups", r.videoGroups); put("blurry", r.blurry)
        })
        // Keep the table small: only the latest 60 reports.
        writableDatabase.execSQL("DELETE FROM reports WHERE id NOT IN (SELECT id FROM reports ORDER BY id DESC LIMIT 60)")
    }

    fun reports(limit: Int = 10): List<ScanReport> {
        val out = ArrayList<ScanReport>()
        readableDatabase.rawQuery("SELECT started,finished,source,status,total,reused,analyzed,removed,photo_groups,video_groups,blurry FROM reports ORDER BY id DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out += ScanReport(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9), c.getInt(10))
        }
        return out
    }

    companion object {
        /** Documents/FileManagerTraining/db/analysis.db, next to the training data. */
        fun defaultMirror(): File =
            File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "FileManagerTraining"), "db/${DbMirror.DB_NAME}")
    }
}
