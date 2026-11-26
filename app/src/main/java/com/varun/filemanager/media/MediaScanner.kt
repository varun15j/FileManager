package com.varun.filemanager.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore

/** Reads photos and videos from MediaStore and computes hashes/blur from small decoded copies. */
class MediaScanner(private val context: Context) {
    private var tagger: ObjectTagger? = null
    private var faceTagger: FaceTagger? = null
    private var embedder: ImageEmbedderTagger? = null
    private val resolver: ContentResolver get() = context.contentResolver

    /** All photos and videos MediaStore knows about, newest first. Cheap: no decoding. */
    fun queryAll(): List<MediaItem> = (query(false) + query(true)).sortedByDescending { it.dateTakenMs }

    /** Decodes and analyzes one item (hashes, blur, people/vehicles). Null if it cannot be read. */
    fun analyze(item: MediaItem): MediaItem? =
        runCatching { if (item.isVideo) analyzeVideo(item) else analyzePhoto(item) }.getOrNull()

    fun openTagger(faces: Boolean = true, embeddings: Boolean = false) {
        if (tagger == null) tagger = ObjectTagger(context)
        if (faces && faceTagger == null) faceTagger = FaceTagger(context)
        if (embeddings && embedder == null) embedder = ImageEmbedderTagger(context)
    }
    fun closeTagger() { tagger?.close(); tagger = null; faceTagger?.close(); faceTagger = null; embedder?.close(); embedder = null }

    /** For a sample photo the user picked: its embedding and a 512 px JPEG copy to keep in the training folder. */
    fun sampleFor(uri: String): Pair<FloatArray, ByteArray>? = runCatching {
        val bmp = decodeSampled(Uri.parse(uri), 512) ?: return null
        val emb = embedder?.embed(bmp)
        val jpeg = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        bmp.recycle()
        emb?.let { it to jpeg }
    }.getOrNull()

    /** Small JPEG (about 256 px) for training samples. Null if the item cannot be decoded. */
    fun thumbnailJpeg(item: MediaItem): ByteArray? = runCatching {
        val bmp = if (item.isVideo) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, Uri.parse(item.uri))
                r.getFrameAtTime(item.durationMs * 300, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { Bitmap.createScaledBitmap(it, 256, (256f * it.height / it.width).toInt().coerceAtLeast(1), true) }
            } finally { r.release() }
        } else decodeSampled(Uri.parse(item.uri), 256)
        bmp?.let { b -> java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray() }
    }.getOrNull()

    private fun query(video: Boolean): List<MediaItem> {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = buildList {
            add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME); add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED); add(MediaStore.MediaColumns.DATE_MODIFIED); add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.DATA)
            if (video) add(MediaStore.MediaColumns.DURATION)
            if (android.os.Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.DATE_TAKEN)
        }.toTypedArray()
        val list = mutableListOf<MediaItem>()
        resolver.query(base, cols, null, null, null)?.use { c ->
            fun idx(n: String) = c.getColumnIndex(n)
            while (c.moveToNext()) {
                val id = c.getLong(idx(MediaStore.MediaColumns._ID))
                val taken = idx(MediaStore.MediaColumns.DATE_TAKEN).let { if (it >= 0 && !c.isNull(it)) c.getLong(it) else 0L }
                    .takeIf { it > 0 } ?: (c.getLong(idx(MediaStore.MediaColumns.DATE_ADDED)) * 1000)
                val path = c.getString(idx(MediaStore.MediaColumns.DATA))
                if (!MediaFilter.isCleanupCandidate(path, c.getInt(idx(MediaStore.MediaColumns.WIDTH)), c.getInt(idx(MediaStore.MediaColumns.HEIGHT)), video)) continue
                list += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id).toString(),
                    path = path,
                    name = c.getString(idx(MediaStore.MediaColumns.DISPLAY_NAME)) ?: "",
                    sizeBytes = c.getLong(idx(MediaStore.MediaColumns.SIZE)),
                    dateTakenMs = taken,
                    modifiedMs = c.getLong(idx(MediaStore.MediaColumns.DATE_MODIFIED)) * 1000,
                    width = c.getInt(idx(MediaStore.MediaColumns.WIDTH)),
                    height = c.getInt(idx(MediaStore.MediaColumns.HEIGHT)),
                    isVideo = video,
                    durationMs = if (video) c.getLong(idx(MediaStore.MediaColumns.DURATION)) else 0,
                )
            }
        }
        return list
    }

    private fun analyzePhoto(item: MediaItem): MediaItem? {
        val bmp = decodeSampled(Uri.parse(item.uri), 256) ?: return null
        val fresh = embedder?.takeIf { it.available }?.embed(bmp)?.let { Embeddings.quantize(it) }
        // Already analyzed with the current analysis (only the embedding is missing): keep hashes, blur, objects and faces.
        if (item.hashes.isNotEmpty() && item.analysisVersion >= ScanPlanner.ANALYSIS_VERSION) {
            bmp.recycle()
            return item.copy(embedding = fresh ?: item.embedding)
        }
        // Analyzed again: an embedding from an older analysis may come from a sideways copy, so only keep a fresh one.
        val embedding = fresh
        val objects = tagger?.detect(bmp) ?: emptyList()
        // Faces are small in a 256 px picture and were almost never found, so they get a larger copy.
        val faces = faceTagger?.takeIf { it.available }?.let { ft ->
            decodeSampled(Uri.parse(item.uri), FACE_DECODE_PX)?.let { big -> ft.detect(big).also { big.recycle() } }
        } ?: emptyList()
        val gray = bmp.toGray(); bmp.recycle()
        return item.copy(hashes = longArrayOf(ImageMetrics.dHash(gray)), blur = ImageMetrics.blurScore(gray), objects = objects,
            faces = faces, analysisVersion = if (faceTagger?.available == true) ScanPlanner.ANALYSIS_VERSION else 1, embedding = embedding)
    }

    private fun analyzeVideo(item: MediaItem): MediaItem? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(item.uri))
            val dur = item.durationMs.takeIf { it > 0 } ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val hashes = FRAME_POINTS.map { p ->
                val frame = r.getFrameAtTime(dur * 1000 * p / 100, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
                val small = Bitmap.createScaledBitmap(frame, 64, 64, true)
                if (small !== frame) frame.recycle()
                ImageMetrics.dHash(small.toGray()).also { small.recycle() }
            }
            return item.copy(durationMs = dur, hashes = hashes.toLongArray())
        } finally { r.release() }
    }

    /** Decodes a smaller copy and turns it upright using the photo's EXIF orientation (HEIC and JPEG). */
    private fun decodeSampled(uri: Uri, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        return upright(bmp, exifOrientation(uri))
    }

    private fun exifOrientation(uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    private fun upright(bmp: Bitmap, exif: Int): Bitmap {
        val turn = Orientation.turnFor(exif)
        if (turn.isIdentity) return bmp
        val m = Matrix().apply { postRotate(turn.degrees.toFloat()); if (turn.mirror) postScale(-1f, 1f) }
        val out = runCatching { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }.getOrNull() ?: return bmp
        if (out !== bmp) bmp.recycle()
        return out
    }

    companion object {
        val FRAME_POINTS = listOf(10, 30, 50, 70, 90)
        /** Face detection decodes the photo at 1024 to 2047 px on its long side (a power-of-two sample size). */
        const val FACE_DECODE_PX = 1024
    }
}
