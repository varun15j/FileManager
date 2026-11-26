package com.varun.filemanager.media

/** One detected face. Position and size are normalized to 0..1; the other values come from MediaPipe's face blendshapes. */
data class FaceInfo(
    val cx: Float, val cy: Float, val w: Float, val h: Float,
    /** Average of the two eye-blink scores: 0 = wide open, 1 = shut. */
    val blink: Float,
    /** Average of the two mouth-smile scores. */
    val smile: Float,
    /** Average of the two mouth-frown scores. */
    val frown: Float,
    /** Laplacian sharpness of the face crop (about 96 px); low = blurry face. */
    val sharpness: Double,
)

/** What a face looks like, and how good a photo is because of its faces. Thresholds are first guesses to tune from feedback. */
object FaceRules {
    const val EYES_CLOSED = 0.5f
    const val SMILING = 0.4f
    const val SAD_FROWN = 0.3f
    const val SAD_MAX_SMILE = 0.2f
    const val BLURRY_FACE = 40.0
    /** Faces narrower than this share of the photo are too small to judge. */
    const val MIN_FACE_WIDTH = 0.05f

    private fun judged(faces: List<FaceInfo>) = faces.filter { it.w >= MIN_FACE_WIDTH }

    fun eyesClosed(f: FaceInfo) = f.blink >= EYES_CLOSED
    fun smiling(f: FaceInfo) = f.smile >= SMILING
    /** A frowning mouth. MediaPipe has no "sad" label, so this is a frown with no smile, not a mood reading. */
    fun sad(f: FaceInfo) = f.frown >= SAD_FROWN && f.smile < SAD_MAX_SMILE
    fun blurry(f: FaceInfo) = f.sharpness < BLURRY_FACE

    /** Higher is better. Closed eyes and a blurry face cost the most; a smile adds a little. No faces = 0. */
    fun score(faces: List<FaceInfo>): Int = judged(faces).sumOf { f ->
        var s = 0
        if (eyesClosed(f)) s -= 3
        if (blurry(f)) s -= 2
        if (sad(f)) s -= 1
        if (smiling(f) && !eyesClosed(f)) s += 1
        s
    }

    fun describe(faces: List<FaceInfo>): String {
        val j = judged(faces)
        if (j.isEmpty()) return ""
        val parts = mutableListOf<String>()
        j.count { eyesClosed(it) }.takeIf { it > 0 }?.let { parts += "eyes closed ×$it" }
        j.count { blurry(it) }.takeIf { it > 0 }?.let { parts += "blurry face ×$it" }
        j.count { sad(it) }.takeIf { it > 0 }?.let { parts += "sad mouth ×$it" }
        j.count { smiling(it) }.takeIf { it > 0 }?.let { parts += "smiling ×$it" }
        return "${j.size} ${if (j.size == 1) "face" else "faces"}" + if (parts.isEmpty()) "" else ": " + parts.joinToString(", ")
    }
}
