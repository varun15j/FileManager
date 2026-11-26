package com.varun.filemanager.media

import org.junit.Assert.*
import org.junit.Test

class MediaFilterTest {
    private fun ok(path: String?, w: Int = 4000, h: Int = 3000, video: Boolean = false) = MediaFilter.isCleanupCandidate(path, w, h, video)

    @Test fun cameraGalleryScreenshotsAndChatMediaAreIncluded() {
        assertTrue(ok("/storage/emulated/0/DCIM/Camera/IMG_1.jpg"))
        assertTrue(ok("/storage/emulated/0/Pictures/Screenshots/Screenshot_1.png", 1080, 2400))
        assertTrue(ok("/storage/emulated/0/Download/photo.jpg"))
        assertTrue(ok("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/a.jpg"))
        assertTrue(ok("/storage/emulated/0/DCIM/Camera/VID_1.mp4", 1920, 1080, video = true))
    }

    @Test fun appSystemAndMusicFoldersAreLeftAlone() {
        assertFalse(ok("/storage/emulated/0/Android/data/com.some.app/files/pic.jpg"))
        assertFalse(ok("/storage/emulated/0/Android/obb/com.some.game/x.jpg"))
        assertFalse(ok("/storage/emulated/0/Android/media/com.android.systemui/icon.png"))
        assertFalse(ok("/storage/emulated/0/Music/Album/cover.jpg"))
        assertFalse(ok("/storage/emulated/0/DCIM/.thumbnails/1.jpg"))
        assertFalse(ok("/storage/emulated/0/.hidden/pic.jpg"))
        assertFalse(ok("/system/media/bootanimation.png"))
        assertFalse(ok("/storage/emulated/0/Documents/FileManagerTraining/samples/a/0.jpg"))
        assertFalse(ok(null))
    }

    @Test fun iconSizedPicturesAreSkippedButSmallVideosAreNot() {
        assertFalse(ok("/storage/emulated/0/Pictures/x/logo.png", 192, 192))
        assertFalse(ok("/storage/emulated/0/Pictures/appicons/a.png", 1024, 1024))
        assertTrue(ok("/storage/emulated/0/Movies/clip.mp4", 240, 144, video = true))
    }
}
