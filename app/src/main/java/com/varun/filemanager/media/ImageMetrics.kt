package com.varun.filemanager.media

import kotlin.math.max

/** Grayscale image, luminance 0..255, row-major. Platform-free so it can be unit-tested. */
class Gray(val w: Int, val h: Int, val px: IntArray) {
    init { require(px.size == w * h) }
    operator fun get(x: Int, y: Int) = px[y * w + x]
}

object ImageMetrics {
    /** Area-average resize. */
    fun resize(g: Gray, nw: Int, nh: Int): Gray {
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val y0 = y * g.h / nh
            val y1 = max(y0 + 1, (y + 1) * g.h / nh)
            for (x in 0 until nw) {
                val x0 = x * g.w / nw
                val x1 = max(x0 + 1, (x + 1) * g.w / nw)
                var sum = 0L; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) { sum += g[xx, yy]; n++ }
                out[y * nw + x] = (sum / n).toInt()
            }
        }
        return Gray(nw, nh, out)
    }

    /** 64-bit difference hash: resize to 9x8, one bit per "left pixel brighter than right pixel". */
    fun dHash(g: Gray): Long {
        val s = resize(g, 9, 8)
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            hash = hash shl 1
            if (s[x, y] > s[x + 1, y]) hash = hash or 1L
        }
        return hash
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Variance of the Laplacian. Low = blurry. Compute on an image whose longest side is about 256 px. */
    fun blurScore(g: Gray): Double {
        if (g.w < 3 || g.h < 3) return 0.0
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (y in 1 until g.h - 1) for (x in 1 until g.w - 1) {
            val l = (4 * g[x, y] - g[x - 1, y] - g[x + 1, y] - g[x, y - 1] - g[x, y + 1]).toDouble()
            sum += l; sumSq += l * l; n++
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}
