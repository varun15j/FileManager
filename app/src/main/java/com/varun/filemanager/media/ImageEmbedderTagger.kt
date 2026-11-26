package com.varun.filemanager.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder.ImageEmbedderOptions

/**
 * One vector per photo from MediaPipe's Image Embedder (MobileNet V3 small, Apache-2.0, 4 MB, bundled).
 * Used to learn custom categories from sample photos. CPU only, nothing leaves the phone.
 */
class ImageEmbedderTagger(context: Context) : AutoCloseable {
    private val embedder: ImageEmbedder? = try {
        ImageEmbedder.createFromOptions(
            context,
            ImageEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE)
                .setL2Normalize(true)
                .setQuantize(false)
                .build()
        )
    } catch (e: Throwable) {
        Log.w("ImageEmbedder", "Image embedding unavailable", e); null
    }

    val available: Boolean get() = embedder != null

    fun embed(input: Bitmap): FloatArray? {
        val e = embedder ?: return null
        val bmp = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
        return runCatching {
            e.embed(BitmapImageBuilder(bmp).build()).embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
        }.getOrNull()
    }

    override fun close() { embedder?.close() }

    companion object { const val MODEL = "mobilenet_v3_small.tflite" }
}
