package com.varun.filemanager.core

import java.io.File

/** Basic file operations. Deletion is always a move to [Trash] first. */
object FileOps {
    fun list(dir: File, showHidden: Boolean = false): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))

    fun copy(src: File, destDir: File): File {
        val dest = uniqueIn(destDir, src.name)
        src.copyRecursively(dest, overwrite = false)
        return dest
    }

    fun move(src: File, destDir: File): File {
        val dest = uniqueIn(destDir, src.name)
        if (!src.renameTo(dest)) {
            src.copyRecursively(dest, overwrite = false)
            src.deleteRecursively()
        }
        return dest
    }

    fun rename(src: File, newName: String): File {
        require(newName.isNotBlank() && '/' !in newName) { "Invalid name" }
        val dest = File(src.parentFile, newName)
        require(!dest.exists()) { "Already exists" }
        check(src.renameTo(dest)) { "Rename failed" }
        return dest
    }

    fun search(root: File, query: String, limit: Int = 500): List<File> =
        root.walkTopDown().filter { it.name.contains(query, ignoreCase = true) }.take(limit).toList()

    fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }
}

/** Reversible deletion: files move to a trash folder; [Entry] remembers where they came from. */
class Trash(private val dir: File) {
    data class Entry(val trashed: File, val original: File)

    init { dir.mkdirs() }

    fun moveToTrash(file: File): Entry {
        val dest = FileOps.uniqueIn(dir, "${System.currentTimeMillis()}_${file.name}")
        if (!file.renameTo(dest)) {
            file.copyRecursively(dest, overwrite = false)
            file.deleteRecursively()
        }
        return Entry(dest, file)
    }

    /** Puts the file back; if the original name is taken, a numbered name is used. */
    fun restore(entry: Entry): File {
        val parent = entry.original.parentFile ?: error("No parent")
        parent.mkdirs()
        val target = FileOps.uniqueIn(parent, entry.original.name)
        if (!entry.trashed.renameTo(target)) {
            entry.trashed.copyRecursively(target, overwrite = false)
            entry.trashed.deleteRecursively()
        }
        return target
    }

    fun empty() { dir.listFiles()?.forEach { it.deleteRecursively() } }
}
