package com.varun.filemanager.media

import kotlin.math.abs
import kotlin.math.hypot

enum class ObjKind { PERSON, VEHICLE }

/** A detected object. Coordinates are normalized to 0..1 (centre and size), so photos of any resolution compare. */
data class DetectedObject(val kind: ObjKind, val label: String, val cx: Float, val cy: Float, val w: Float, val h: Float) {
    /** e.g. "left, middle". For showing where a person or vehicle stands in the frame. */
    fun position(): String {
        val x = when { cx < 0.33f -> "left"; cx > 0.66f -> "right"; else -> "center" }
        val y = when { cy < 0.33f -> "top"; cy > 0.66f -> "bottom"; else -> "middle" }
        return "$x, $y"
    }
}

object SceneMatcher {
    /**
     * True when both photos contain the same number of people and vehicles and each object has a
     * counterpart of the same kind at a similar position and size. Greedy matching, nearest first.
     */
    fun sameScene(a: List<DetectedObject>, b: List<DetectedObject>, maxCentreDist: Float = 0.15f): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        for (k in ObjKind.values()) if (a.count { it.kind == k } != b.count { it.kind == k }) return false
        val free = b.toMutableList()
        for (o in a.sortedByDescending { it.w * it.h }) {
            val m = free.filter { it.kind == o.kind }
                .minByOrNull { hypot(it.cx - o.cx, it.cy - o.cy) } ?: return false
            if (hypot(m.cx - o.cx, m.cy - o.cy) > maxCentreDist) return false
            val sizeRatio = (m.w * m.h) / (o.w * o.h).coerceAtLeast(1e-6f)
            if (sizeRatio < 0.5f || sizeRatio > 2f) return false
            free.remove(m)
        }
        return true
    }

    fun describe(objs: List<DetectedObject>): String {
        val people = objs.filter { it.kind == ObjKind.PERSON }
        val vehicles = objs.filter { it.kind == ObjKind.VEHICLE }
        val parts = mutableListOf<String>()
        if (people.isNotEmpty()) parts += "${people.size} ${if (people.size == 1) "person" else "people"} (" + people.joinToString("; ") { it.position() } + ")"
        if (vehicles.isNotEmpty()) parts += "${vehicles.size} vehicle${if (vehicles.size == 1) "" else "s"}"
        return parts.joinToString(", ")
    }

    /** Small helper for tests and tuning: how far apart the closest same-kind objects are. */
    fun centreDistance(a: DetectedObject, b: DetectedObject) = abs(hypot(a.cx - b.cx, a.cy - b.cy))
}
