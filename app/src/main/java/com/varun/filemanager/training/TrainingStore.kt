package com.varun.filemanager.training

import java.io.File

/** Tiny JSON writer and flat reader, so logs can be written and parsed without Android classes. */
object MiniJson {
    fun quote(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + value(it.value) }
        is Iterable<*> -> v.joinToString(",", "[", "]") { value(it) }
        is LongArray -> v.joinToString(",", "[", "]")
        else -> quote(v.toString())
    }

    fun obj(vararg pairs: Pair<String, Any?>) = value(mapOf(*pairs))

    private val field = Regex("\"(\\w+)\":(\"((?:[^\"\\\\]|\\\\.)*)\"|-?[0-9.]+|true|false|null)")

    /** Reads a flat object (string, number, boolean values only). Nested values are ignored. */
    fun parseFlat(line: String): Map<String, String> =
        field.findAll(line).associate { m ->
            m.groupValues[1] to (if (m.groupValues[2].startsWith("\"")) m.groupValues[3].replace("\\\"", "\"").replace("\\\\", "\\") else m.groupValues[2])
        }
}

/** One user decision about a group of similar items. Flat, so it is easy to parse back. */
data class Decision(
    val ts: Long,
    /** photo, video, blur or dup */
    val kind: String,
    val groupId: String,
    /** approved (user confirmed deletion), rejected (user said keep / not similar), cancelled (system dialog dismissed) */
    val action: String,
    val items: Int,
    val deleted: Int,
    val kept: Int,
    /** Items where the user disagreed with the pre-selection (unchecked a suggested delete, or checked a keeper). */
    val overrides: Int,
) {
    fun toJson() = MiniJson.obj(
        "ts" to ts, "kind" to kind, "groupId" to groupId, "action" to action,
        "items" to items, "deleted" to deleted, "kept" to kept, "overrides" to overrides,
    )

    companion object {
        fun parse(line: String): Decision? {
            val m = MiniJson.parseFlat(line)
            return runCatching {
                Decision(m.getValue("ts").toLong(), m.getValue("kind"), m.getValue("groupId"), m.getValue("action"),
                    m.getValue("items").toInt(), m.getValue("deleted").toInt(), m.getValue("kept").toInt(), m.getValue("overrides").toInt())
            }.getOrNull()
        }
    }
}

data class KindStats(val approved: Int, val rejected: Int, val cancelled: Int, val itemsApproved: Int, val overrides: Int) {
    /** Share of reviewed groups the user confirmed. Null until something has been reviewed. */
    val accuracy: Double? get() = (approved + rejected).takeIf { it > 0 }?.let { approved.toDouble() / it }
    /** Share of items in approved groups where the user changed the suggestion. */
    val overrideRate: Double? get() = itemsApproved.takeIf { it > 0 }?.let { overrides.toDouble() / it }
}

data class TrainingStats(val byKind: Map<String, KindStats>) {
    val totalReviewed: Int get() = byKind.values.sumOf { it.approved + it.rejected }

    companion object {
        fun from(decisions: List<Decision>) = TrainingStats(
            decisions.groupBy { it.kind }.mapValues { (_, l) ->
                KindStats(
                    approved = l.count { it.action == "approved" },
                    rejected = l.count { it.action == "rejected" },
                    cancelled = l.count { it.action == "cancelled" },
                    itemsApproved = l.filter { it.action == "approved" }.sumOf { it.items },
                    overrides = l.filter { it.action == "approved" }.sumOf { it.overrides },
                )
            }
        )
    }
}

/**
 * Training and feedback data in one folder (Documents/FileManagerTraining on the phone), so it survives
 * uninstalling the app and can be pulled to a PC. Files:
 * - decisions.jsonl: one flat line per user decision (read back for accuracy stats)
 * - groups.jsonl: what the analyzer found, per reviewed group (hashes, blur, objects, keeper)
 * - scans.jsonl: one line per scan (counts, time)
 * - settings.json: {"samples":true|false}
 * - samples/<groupId>/<n>.jpg: small thumbnails of reviewed items (can be switched off)
 */
class TrainingStore(val dir: File) {
    private val decisionsFile = File(dir, "decisions.jsonl")
    private val groupsFile = File(dir, "groups.jsonl")
    private val scansFile = File(dir, "scans.jsonl")
    private val settingsFile = File(dir, "settings.json")

    init {
        dir.mkdirs()
        File(dir, "README.txt").takeIf { !it.exists() }?.writeText(README)
    }

    var samplesEnabled: Boolean
        get() = !(settingsFile.exists() && settingsFile.readText().contains("\"samples\":false"))
        set(v) { settingsFile.writeText(MiniJson.obj("samples" to v)) }

    @Synchronized fun logDecision(d: Decision) = decisionsFile.appendText(d.toJson() + "\n")
    @Synchronized fun logGroup(json: String) = groupsFile.appendText(json + "\n")
    @Synchronized fun logScan(json: String) = scansFile.appendText(json + "\n")

    fun saveSample(groupId: String, index: Int, jpeg: ByteArray) {
        if (!samplesEnabled) return
        val d = File(dir, "samples/$groupId").apply { mkdirs() }
        File(d, "$index.jpg").writeBytes(jpeg)
    }

    fun hasSample(groupId: String) = File(dir, "samples/$groupId").isDirectory

    fun decisions(): List<Decision> =
        if (decisionsFile.exists()) decisionsFile.readLines().mapNotNull { Decision.parse(it) } else emptyList()

    fun stats() = TrainingStats.from(decisions())

    companion object {
        const val README = "FileManager training data. decisions.jsonl = user feedback (approved / rejected / cancelled) per group. " +
            "groups.jsonl = analyzer output for reviewed groups. samples/ = small thumbnails. Safe to copy to a PC; reinstalling the app reloads it.\n"
    }
}
