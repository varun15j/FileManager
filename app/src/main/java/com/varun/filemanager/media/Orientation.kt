package com.varun.filemanager.media

/**
 * EXIF orientation of a photo. Cameras store pixels in the sensor's direction and note how to turn them in
 * the EXIF tag; a decoder that ignores the tag hands every analyzer a sideways photo. Pure, so it can be tested.
 */
object Orientation {
    /** How to turn the stored pixels upright: clockwise [degrees] (-90, 0, 90 or 180), then mirror left-right if [mirror]. */
    data class Turn(val degrees: Int, val mirror: Boolean) { val isIdentity get() = degrees == 0 && !mirror }

    /** [exif] is the value of the EXIF orientation tag (1 to 8; anything else means "upright"). */
    fun turnFor(exif: Int): Turn = when (exif) {
        2 -> Turn(0, true)
        3 -> Turn(180, false)
        4 -> Turn(180, true)
        5 -> Turn(90, true)
        6 -> Turn(90, false)
        7 -> Turn(-90, true)
        8 -> Turn(-90, false)
        else -> Turn(0, false)
    }

    /** Width and height after the turn. */
    fun sizeAfter(turn: Turn, width: Int, height: Int): Pair<Int, Int> =
        if (turn.degrees == 90 || turn.degrees == -90) height to width else width to height
}
