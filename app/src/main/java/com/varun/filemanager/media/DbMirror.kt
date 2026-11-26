package com.varun.filemanager.media

import java.io.File

/**
 * File-level helpers for keeping a copy of the scan database in Documents/FileManagerTraining/db, so the saved
 * results survive an uninstall. Kept free of Android classes so it can be unit tested.
 */
object DbMirror {
    const val DB_NAME = "analysis.db"

    /** Copies [src] to [dst] through a temporary file, so [dst] is never left half written. */
    fun copyAtomic(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        src.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) { tmp.delete(); error("could not replace ${dst.path}") } }
    }

    /** Removes a database file together with SQLite's side files. */
    fun deleteDb(db: File) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(db.path + suffix).delete()
    }
}
