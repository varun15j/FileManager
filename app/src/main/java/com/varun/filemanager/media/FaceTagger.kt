package com.varun.filemanager.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions

/**
 * On-device faces with expression scores (eye blink, smile, frown) from MediaPipe's Face Landmarker
 * (model file face_landmarker.task, Apache-2.0). CPU only. Nothing leaves the phone.
 */
class FaceTagger(context: Context) : AutoCloseable {
    private val landmarker: FaceLandmarker? = try {
        FaceLandmarker.createFromOptions(
            context,
            FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(6)
                .setMinFaceDetectionConfidence(0.5f)
                .setOutputFaceBlendshapes(true)
                .build()
        )
    } catch (e: Throwable) {
        Log.w("FaceTagger", "Face detection unavailable", e); null
    }

    val available: Boolean get() = landmarker != null

    fun detect(input: Bitmap): List<FaceInfo> {
        val lm = landmarker ?: return emptyList()
        val bmp = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
        val result = runCatching { lm.detect(BitmapImageBuilder(bmp).build()) }.getOrNull() ?: return emptyList()
        val landmarks = result.faceLandmarks()
        val shapes = result.faceBlendshapes().orElse(emptyList())
        return landmarks.mapIndexedNotNull { i, pts ->
            if (pts.isEmpty()) return@mapIndexedNotNull null
            val minX = pts.minOf { it.x() }.coerceIn(0f, 1f); val maxX = pts.maxOf { it.x() }.coerceIn(0f, 1f)
            val minY = pts.minOf { it.y() }.coerceIn(0f, 1f); val maxY = pts.maxOf { it.y() }.coerceIn(0f, 1f)
            val w = maxX - minX; val h = maxY - minY
            if (w <= 0f || h <= 0f) return@mapIndexedNotNull null
            val scores = shapes.getOrNull(i)?.associate { it.categoryName() to it.score() } ?: emptyMap()
            fun avg(a: String, b: String) = ((scores[a] ?: 0f) + (scores[b] ?: 0f)) / 2f
            FaceInfo(
                cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f, w = w, h = h,
                blink = avg("eyeBlinkLeft", "eyeBlinkRight"),
                smile = avg("mouthSmileLeft", "mouthSmileRight"),
                frown = avg("mouthFrownLeft", "mouthFrownRight"),
                sharpness = sharpnessOf(bmp, minX, minY, maxX, maxY),
            )
        }
    }

    private fun sharpnessOf(bmp: Bitmap, minX: Float, minY: Float, maxX: Float, maxY: Float): Double = runCatching {
        val l = (minX * bmp.width).toInt().coerceIn(0, bmp.width - 2)
        val t = (minY * bmp.height).toInt().coerceIn(0, bmp.height - 2)
        val r = (maxX * bmp.width).toInt().coerceIn(l + 1, bmp.width)
        val b = (maxY * bmp.height).toInt().coerceIn(t + 1, bmp.height)
        val crop = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
        val scale = 96f / maxOf(crop.width, crop.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt().coerceAtLeast(3), (crop.height * scale).toInt().coerceAtLeast(3), true) else crop
        ImageMetrics.blurScore(small.toGray())
    }.getOrDefault(0.0)

    override fun close() { landmarker?.close() }

    companion object { const val MODEL = "face_landmarker.task" }
}
