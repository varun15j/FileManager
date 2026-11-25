package com.varun.filemanager.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore

/** Reads photos and videos from MediaStore and computes hashes/blur from small decoded copies. */
class MediaScanner(private val context: Context) {
    private var tagger: ObjectTagger? = null
    private val resolver: ContentResolver get() = context.contentResolver

    /** Analyzes newest items first and calls [onItem] after each one, so the UI can show results while the scan runs. */
    fun scan(
        onItem: (MediaItem) -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): List<MediaItem> {
        val raw = (query(false) + query(true)).sortedByDescending { it.dateTakenMs }
        tagger = ObjectTagger(context)
        val out = ArrayList<MediaItem>(raw.size)
        raw.forEachIndexed { i, item ->
            if (isCancelled()) return out
            onProgress(i, raw.size)
            val analyzed = runCatching { if (item.isVideo) analyzeVideo(item) else analyzePhoto(item) }.getOrNull()
            if (analyzed != null) { out += analyzed; onItem(analyzed) }
        }
        onProgress(raw.size, raw.size)
        tagger?.close(); tagger = null
        return out
    }

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
            add(MediaStore.MediaColumns.DATE_ADDED); add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT)
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
                list += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id).toString(),
                    path = c.getString(idx(MediaStore.MediaColumns.DATA)),
                    name = c.getString(idx(MediaStore.MediaColumns.DISPLAY_NAME)) ?: "",
                    sizeBytes = c.getLong(idx(MediaStore.MediaColumns.SIZE)),
                    dateTakenMs = taken,
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
        val objects = tagger?.detect(bmp) ?: emptyList()
        val gray = bmp.toGray(); bmp.recycle()
        return item.copy(hashes = longArrayOf(ImageMetrics.dHash(gray)), blur = ImageMetrics.blurScore(gray), objects = objects)
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

    private fun decodeSampled(uri: Uri, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    private fun Bitmap.toGray(): Gray {
        val px = IntArray(width * height)
        getPixels(px, 0, width, 0, 0, width, height)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()
        }
        return Gray(width, height, px)
    }

    companion object { val FRAME_POINTS = listOf(10, 30, 50, 70, 90) }
}
