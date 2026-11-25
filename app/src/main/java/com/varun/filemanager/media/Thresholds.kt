package com.varun.filemanager.media

/** Tunable analysis limits. Defaults are first guesses; the Settings screen changes them. */
data class Thresholds(
    /** Photos at most this many bits apart (of 64) in their dHash are treated as similar. */
    val maxHamming: Int = 10,
    /** Looser limit used only when both photos show the same people/vehicles in the same places. */
    val sceneHamming: Int = 20,
    /** Photos must be taken within this many minutes of each other. */
    val windowMinutes: Int = 10,
    /** Photos with sharpness below this are listed as blurry. */
    val blurThreshold: Double = 60.0,
) {
    fun coerced() = copy(
        maxHamming = maxHamming.coerceIn(0, 24),
        sceneHamming = sceneHamming.coerceIn(0, 32).coerceAtLeast(maxHamming.coerceIn(0, 24)),
        windowMinutes = windowMinutes.coerceIn(1, 24 * 60),
        blurThreshold = blurThreshold.coerceIn(5.0, 300.0),
    )
}
