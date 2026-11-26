package com.varun.filemanager.training

import java.io.File

/** One folder of sample photos inside Documents/FileManagerTraining/dataset. The folder name is the category label. */
data class DatasetFolder(val name: String, val files: List<File>) {
    /** Changes whenever a photo is added, removed or replaced. */
    val signature: String get() = DatasetFolders.signature(files)
    val label: String get() = DatasetFolders.label(name)
    /** Optional description.txt in the folder, saved with the category. */
    val description: String get() = runCatching { File(files.firstOrNull()?.parentFile, "description.txt").takeIf { it.isFile }?.readText()?.trim() }.getOrNull().orEmpty()
}

object DatasetFolders {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    /** Every non-hidden sub-folder of [root] that holds at least one picture, sorted by name. */
    fun scan(root: File): List<DatasetFolder> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name }.mapNotNull { dir ->
            val images = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.extension.lowercase() in IMAGE_EXT }.orEmpty().sortedBy { it.name }
            if (images.isEmpty()) null else DatasetFolder(dir.name, images)
        }

    fun signature(files: List<File>): String =
        files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }.hashCode().toUInt().toString(16)

    fun label(folderName: String): String =
        folderName.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }

    /** Stable category id for a folder, so learning it again updates the same category. */
    fun categoryId(folderName: String): String =
        "ds-" + folderName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifBlank { "folder" }

    fun source(folderName: String) = "dataset:$folderName"
}
