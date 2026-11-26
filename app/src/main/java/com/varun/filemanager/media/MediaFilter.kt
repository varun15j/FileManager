package com.varun.filemanager.media

/**
 * Which photos and videos the cleanup looks at: camera shots, gallery and downloaded pictures, screenshots,
 * chat-app media. The Documents folder (which also holds this app's own training data and test data), app folders, system folders and icon-like pictures (app icons, album art, UI resources)
 * are left alone, so they can never be offered for deletion. Pure, so it can be tested.
 */
object MediaFilter {
    /** Smaller than this on either side is an icon, thumbnail or UI resource, not a photo. */
    const val MIN_PHOTO_SIDE = 320

    private val skipFolders = listOf(
        "/android/data/", "/android/obb/", "/android/media/com.android.", "/android/media/com.google.", "/android/media/com.oneplus.", "/android/media/com.oplus.",
        "/music/", "/ringtones/", "/notifications/", "/alarms/", "/podcasts/", "/audiobooks/",
        "/documents/", "/.thumbnails/", "/.trash", "/.cache/", "/albumart", "/.albumthumbs", "/albumthumbs", "/lost.dir/",
    )
    private val systemRoots = listOf("/system/", "/data/", "/vendor/", "/product/", "/apex/", "/oem/", "/odm/")
    private val iconWords = listOf("/icon", "/emoji", "/ic_launcher", "/appicon", "/app_icon", "/cache/", "/thumbs/")

    fun isCleanupCandidate(path: String?, width: Int, height: Int, isVideo: Boolean): Boolean {
        val p = (path ?: return false).replace('\\', '/').lowercase()
        if (systemRoots.any { p.startsWith(it) }) return false
        if (skipFolders.any { p.contains(it) }) return false
        // Hidden folders (".something") are app-private by convention.
        if (p.split('/').dropLast(1).any { it.startsWith(".") && it.length > 1 }) return false
        if (iconWords.any { p.contains(it) }) return false
        if (!isVideo && width > 0 && height > 0 && minOf(width, height) < MIN_PHOTO_SIDE) return false
        return true
    }
}
