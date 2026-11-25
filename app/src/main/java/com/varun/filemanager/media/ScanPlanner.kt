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
)

object ScanPlanner {
    fun plan(current: List<MediaItem>, saved: Map<String, MediaItem>): ScanPlan {
        val reuse = ArrayList<MediaItem>()
        val todo = ArrayList<MediaItem>()
        for (raw in current) {
            val s = saved[raw.uri]
            // A saved result is valid if the file is unchanged and it was analyzed (has hashes).
            if (s != null && s.hashes.isNotEmpty() && s.sizeBytes == raw.sizeBytes && s.modifiedMs == raw.modifiedMs) {
                reuse += raw.copy(hashes = s.hashes, blur = s.blur, objects = s.objects, durationMs = if (raw.isVideo) s.durationMs else raw.durationMs)
            } else todo += raw
        }
        val live = current.mapTo(HashSet()) { it.uri }
        return ScanPlan(reuse, todo.sortedByDescending { it.dateTakenMs }, saved.keys.filterTo(HashSet()) { it !in live })
    }
}

/** Flat text forms for storing hashes and detected objects in SQLite columns. */
object ItemCodec {
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
