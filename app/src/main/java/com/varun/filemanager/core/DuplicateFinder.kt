package com.varun.filemanager.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** A set of byte-identical files. */
data class DuplicateGroup(val sizeBytes: Long, val hash: String, val files: List<File>) {
    /** Space freed if every file except one is removed. */
    val reclaimableBytes: Long get() = sizeBytes * (files.size - 1)
}

/**
 * Exact duplicate detection: group by size, then by a hash of the first/last
 * chunk, then confirm with a full SHA-256. Pure JVM so it is unit-testable.
 * It never deletes anything; it only reports groups.
 */
class DuplicateFinder(
    private val partialBytes: Int = 64 * 1024,
    private val minSize: Long = 1,
) {
    fun find(
        roots: List<File>,
        onProgress: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<DuplicateGroup> {
        val bySize = HashMap<Long, MutableList<File>>()
        roots.forEach { root ->
            root.walkTopDown()
                .onEnter { !isCancelled() && !it.name.startsWith(".") }
                .filter { it.isFile && it.length() >= minSize }
                .forEach { bySize.getOrPut(it.length()) { mutableListOf() }.add(it) }
        }
        val result = mutableListOf<DuplicateGroup>()
        for ((size, sameSize) in bySize) {
            if (sameSize.size < 2 || isCancelled()) continue
            val byPartial = sameSize.groupBy { partialHash(it) }
            for (candidates in byPartial.values) {
                if (candidates.size < 2) continue
                onProgress("Hashing ${candidates.size} files of $size bytes")
                candidates.groupBy { fullHash(it) }.forEach { (hash, files) ->
                    if (files.size > 1) result += DuplicateGroup(size, hash, files.sortedBy { it.path })
                }
            }
        }
        return result.sortedByDescending { it.reclaimableBytes }
    }

    private fun partialHash(f: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        java.io.RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(partialBytes)
            var n = raf.read(buf)
            if (n > 0) md.update(buf, 0, n)
            if (raf.length() > partialBytes) {
                raf.seek(maxOf(0, raf.length() - partialBytes))
                n = raf.read(buf)
                if (n > 0) md.update(buf, 0, n)
            }
        }
        md.digest().toHex()
    }.getOrElse { "unreadable:${f.path}" }

    private fun fullHash(f: File): String = runCatching {
        f.inputStream().use { sha256(it) }
    }.getOrElse { "unreadable:${f.path}" }

    companion object {
        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            return md.digest().toHex()
        }
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}

/**
 * Picks which copy to keep: shortest path, then oldest. Later milestones add
 * resolution/sharpness for photos. The result is only a suggestion; the user
 * confirms before anything is removed.
 */
fun DuplicateGroup.suggestedKeeper(): File =
    files.minWith(compareBy<File>({ it.path.length }, { it.lastModified() }))
