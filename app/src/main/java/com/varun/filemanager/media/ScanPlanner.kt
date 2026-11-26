package com.varun.filemanager.media

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** What a scan has to do, given what MediaStore lists now and what is already saved. Pure, so it can be tested. */
data class ScanPlan(
    /** Saved results that are still valid: shown immediately, no decoding. */
    val reuse: List<MediaItem>,
    /** New or changed items, newest first. Only these are decoded. */
    val analyze: List<MediaItem>,
    /** URIs saved earlier that no longer exist in MediaStore. */
    val removed: Set<String>,
    /** Saved rows whose file is the same but whose uri changed (for example after a MediaStore rebuild). Save these again under the new uri. */
    val rekeyed: List<MediaItem> = emptyList(),
    /** Saved photos that are valid but lack newer data (faces). They are part of [reuse], so they show at once; decode them after the new files. */
    val upgrade: List<MediaItem> = emptyList(),
)

object ScanPlanner {
    /** 3 = faces found on a ~1024 px copy (2 used 256 px and found almost none). Older photos are analyzed again once, after new files. */
    const val ANALYSIS_VERSION = 3

    private fun key(i: MediaItem) = "${i.path}|${i.sizeBytes}|${i.modifiedMs}"

    fun plan(current: List<MediaItem>, saved: Map<String, MediaItem>, requireFaces: Boolean = true, requireEmbedding: Boolean = false): ScanPlan {
        val needed = if (requireFaces) ANALYSIS_VERSION else 0
        val reuse = ArrayList<MediaItem>()
        val todo = ArrayList<MediaItem>()
        val upgrade = ArrayList<MediaItem>()
        val rekeyed = ArrayList<MediaItem>()
        // Fallback match by path + size + modified time, so results still apply if MediaStore ids changed.
        val byPath by lazy { saved.values.filter { !it.path.isNullOrBlank() }.associateBy { key(it) } }
        for (raw in current) {
            val s = saved[raw.uri] ?: raw.path?.takeIf { it.isNotBlank() }?.let { byPath[key(raw)] }
            // A saved result is valid if the file is unchanged and it was analyzed (has hashes).
            if (s != null && s.hashes.isNotEmpty() && s.sizeBytes == raw.sizeBytes && s.modifiedMs == raw.modifiedMs) {
                val kept = raw.copy(hashes = s.hashes, blur = s.blur, objects = s.objects, faces = s.faces, analysisVersion = s.analysisVersion, embedding = s.embedding, durationMs = if (raw.isVideo) s.durationMs else raw.durationMs)
                reuse += kept
                if (s.uri != raw.uri) rekeyed += kept
                // Old results are shown straight away; face data is added afterwards.
                if (!raw.isVideo && (s.analysisVersion < needed || (requireEmbedding && s.embedding == null))) upgrade += kept
            } else todo += raw
        }
        val live = current.mapTo(HashSet()) { it.uri }
        return ScanPlan(
            reuse, todo.sortedByDescending { it.dateTakenMs }, saved.keys.filterTo(HashSet()) { it !in live },
            rekeyed, upgrade.sortedByDescending { it.dateTakenMs },
        )
    }
}

/** Flat text forms for storing hashes, detected objects and faces in SQLite columns. */
object ItemCodec {
    fun faces(f: List<FaceInfo>): String = f.joinToString(";") { "${it.cx}|${it.cy}|${it.w}|${it.h}|${it.blink}|${it.smile}|${it.frown}|${it.sharpness}" }
    fun parseFaces(s: String?): List<FaceInfo> =
        if (s.isNullOrBlank()) emptyList() else s.split(';').mapNotNull { part ->
            val f = part.split('|')
            runCatching { FaceInfo(f[0].toFloat(), f[1].toFloat(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f[5].toFloat(), f[6].toFloat(), f[7].toDouble()) }.getOrNull()
        }

    fun hashes(h: LongArray): String = h.joinToString(",")
    fun parseHashes(s: String?): LongArray = if (s.isNullOrBlank()) LongArray(0) else s.split(',').map { it.toLong() }.toLongArray()

    fun objects(o: List<DetectedObject>): String = o.joinToString(";") { "${it.kind.name}|${it.label}|${it.cx}|${it.cy}|${it.w}|${it.h}" }
    fun parseObjects(s: String?): List<DetectedObject> =
        if (s.isNullOrBlank()) emptyList() else s.split(';').mapNotNull { part ->
            val f = part.split('|')
            runCatching { DetectedObject(ObjKind.valueOf(f[0]), f[1], f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f[5].toFloat()) }.getOrNull()
        }
}

object NightWindow {
    /** Milliseconds from [nowMs] until the next [hour]:00 in [zone]. Used to start the daily background scan at night. */
    fun delayUntil(hour: Int, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli() - nowMs
    }
}
