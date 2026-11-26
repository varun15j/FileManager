package com.varun.filemanager.training

import org.json.JSONObject
import java.io.File

/** A custom category the user taught the app from sample photos. */
data class Category(
    val id: String,
    val label: String,
    val description: String,
    val createdMs: Long,
    val centroid: FloatArray,
    val threshold: Float,
    /** Embeddings of photos the user said are not this category. */
    val negatives: List<FloatArray>,
    val sampleCount: Int,
    /** What most samples have in common (from the analyzer), shown to the user. */
    val traits: List<String>,
)

/**
 * Custom categories in Documents/FileManagerTraining/categories/<id>/ : category.json (label, description,
 * learned average look, cut-off, "not this" examples) and samples/<n>.jpg (small copies of the sample photos),
 * so the categories survive uninstalling and can be pulled to a PC for training.
 */
class CategoryStore(dir: File) {
    private val root = File(dir, "categories")

    fun hasAny(): Boolean = root.listFiles { f -> File(f, FILE).isFile }?.isNotEmpty() == true

    fun list(): List<Category> =
        root.listFiles { f -> File(f, FILE).isFile }.orEmpty().mapNotNull { runCatching { read(File(it, FILE)) }.getOrNull() }.sortedBy { it.createdMs }

    fun newId(label: String): String {
        val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifBlank { "category" }
        var id = slug; var n = 2
        while (File(root, id).exists()) id = "$slug-${n++}"
        return id
    }

    @Synchronized fun save(c: Category) {
        File(root, c.id).mkdirs()
        File(root, "${c.id}/$FILE").writeText(JSONObject().apply {
            put("id", c.id); put("label", c.label); put("description", c.description); put("createdMs", c.createdMs)
            put("centroid", pack(c.centroid)); put("threshold", c.threshold.toDouble())
            put("negatives", c.negatives.joinToString(";") { pack(it) })
            put("sampleCount", c.sampleCount); put("traits", c.traits.joinToString("|"))
        }.toString())
    }

    fun saveSample(id: String, index: Int, jpeg: ByteArray) {
        val d = File(root, "$id/samples").apply { mkdirs() }
        File(d, "$index.jpg").writeBytes(jpeg)
    }

    @Synchronized fun addNegative(id: String, embedding: FloatArray) {
        val c = list().firstOrNull { it.id == id } ?: return
        save(c.copy(negatives = (c.negatives + listOf(embedding)).takeLast(MAX_NEGATIVES)))
    }

    @Synchronized fun setThreshold(id: String, threshold: Float) {
        val c = list().firstOrNull { it.id == id } ?: return
        save(c.copy(threshold = threshold))
    }

    fun delete(id: String) { File(root, id).deleteRecursively() }

    private fun read(f: File): Category {
        val j = JSONObject(f.readText())
        return Category(
            id = j.getString("id"), label = j.getString("label"), description = j.optString("description"), createdMs = j.optLong("createdMs"),
            centroid = unpack(j.getString("centroid")), threshold = j.getDouble("threshold").toFloat(),
            negatives = j.optString("negatives").split(';').filter { it.isNotBlank() }.map { unpack(it) },
            sampleCount = j.optInt("sampleCount"), traits = j.optString("traits").split('|').filter { it.isNotBlank() },
        )
    }

    private fun pack(v: FloatArray) = v.joinToString(",") { "%.4f".format(java.util.Locale.US, it) }
    private fun unpack(s: String) = s.split(',').map { it.toFloat() }.toFloatArray()

    private companion object { const val FILE = "category.json"; const val MAX_NEGATIVES = 50 }
}
