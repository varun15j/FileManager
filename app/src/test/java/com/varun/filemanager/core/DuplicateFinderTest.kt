package com.varun.filemanager.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DuplicateFinderTest {
    private fun tmp(): File = Files.createTempDirectory("dup").toFile()

    @Test fun findsExactDuplicatesOnly() {
        val d = tmp()
        File(d, "a.txt").writeText("hello world")
        File(d, "b.txt").writeText("hello world")
        File(d, "c.txt").writeText("hello w0rld") // same size, different content
        File(d, "sub").mkdir(); File(d, "sub/a_copy.txt").writeText("hello world")
        val groups = DuplicateFinder().find(listOf(d))
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].files.size)
        assertEquals(22L, groups[0].reclaimableBytes)
    }

    @Test fun differsInMiddleOfLargeFile() {
        val d = tmp()
        val a = ByteArray(300_000) { (it % 251).toByte() }
        val b = a.copyOf().also { it[150_000] = 99 }
        File(d, "a.bin").writeBytes(a); File(d, "b.bin").writeBytes(b)
        assertTrue(DuplicateFinder(partialBytes = 1024).find(listOf(d)).isEmpty())
    }

    @Test fun trashAndRestore() {
        val d = tmp(); val f = File(d, "x.txt").apply { writeText("x") }
        val trash = Trash(File(d, ".trash"))
        val e = trash.moveToTrash(f)
        assertFalse(f.exists())
        assertTrue(trash.restore(e).exists())
    }
}
