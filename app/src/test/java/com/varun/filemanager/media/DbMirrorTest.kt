package com.varun.filemanager.media

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DbMirrorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun copyAtomicCreatesFoldersReplacesAndLeavesNoTempFile() {
        val src = tmp.newFile("a.db").apply { writeText("one") }
        val dst = File(tmp.root, "Documents/FileManagerTraining/db/analysis.db")
        DbMirror.copyAtomic(src, dst)
        assertEquals("one", dst.readText())
        src.writeText("two")
        DbMirror.copyAtomic(src, dst)
        assertEquals("two", dst.readText())
        assertFalse(File(dst.path + ".tmp").exists())
    }

    @Test fun deleteDbRemovesSideFiles() {
        val db = tmp.newFile("x.db")
        val side = listOf("-wal", "-shm", "-journal").map { File(db.path + it).apply { writeText("s") } }
        DbMirror.deleteDb(db)
        assertFalse(db.exists())
        side.forEach { assertFalse(it.exists()) }
    }
}
