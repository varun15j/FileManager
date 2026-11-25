package com.varun.filemanager.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.ObjectDetectorOptions

/**
 * On-device people and vehicle detector: COCO EfficientDet-Lite0 (Apache-2.0), run through
 * MediaPipe Tasks. CPU only, because GPU delegates crash on some devices. The model file
 * comes from the ADAS_cam project's assets.
 */
class ObjectTagger(context: Context) : AutoCloseable {
    private val detector: ObjectDetector? = try {
        ObjectDetector.createFromOptions(
            context,
            ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(20)
                .setScoreThreshold(0.4f)
                .setCategoryAllowlist(KINDS.keys.toList())
                .build()
        )
    } catch (e: Throwable) {
        Log.w("ObjectTagger", "Detector unavailable, similarity falls back to hashes only", e); null
    }

    val available: Boolean get() = detector != null

    fun detect(input: Bitmap): List<DetectedObject> {
        val d = detector ?: return emptyList()
        val bmp = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        return d.detect(BitmapImageBuilder(bmp).build()).detections().mapNotNull { det ->
            val cat = det.categories().firstOrNull() ?: return@mapNotNull null
            val kind = KINDS[cat.categoryName()] ?: return@mapNotNull null
            val r = det.boundingBox()
            DetectedObject(kind, cat.categoryName(), (r.left + r.right) / 2f / w, (r.top + r.bottom) / 2f / h, r.width() / w, r.height() / h)
        }
    }

    override fun close() { detector?.close() }

    companion object {
        const val MODEL = "efficientdet_lite0.tflite"
        val KINDS = mapOf(
            "person" to ObjKind.PERSON,
            "car" to ObjKind.VEHICLE, "truck" to ObjKind.VEHICLE, "bus" to ObjKind.VEHICLE,
            "motorcycle" to ObjKind.VEHICLE, "bicycle" to ObjKind.VEHICLE,
        )
    }
}
