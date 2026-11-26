package com.varun.filemanager.media

import android.graphics.Bitmap

/** Luminance copy of a bitmap, for hashing and sharpness. */
fun Bitmap.toGray(): Gray {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    for (i in px.indices) {
        val c = px[i]
        px[i] = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()
    }
    return Gray(width, height, px)
}
