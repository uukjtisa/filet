package dev.niccc2007.filet.playback

/**
 * How the full player draws itself.
 *
 * ## Why this is a choice and not a redesign
 *
 * The player was a flat background with the artwork in a rounded box, everything centred. It
 * worked and it looked like a preview pane, which is what it used to be. The replacement
 * takes its colour from the artwork, which is what every music app does and is the one trick
 * that can never be off-palette, because it IS the palette of the thing playing.
 *
 * Keeping several is a real cost - every face is a layout that has to survive the next change
 * to the transport, the queue and the video case. The recommendation was two, one plain and
 * one not; the decision was to keep the set. The cost is stated here so it is not rediscovered
 * the next time one of them silently stops matching the others.
 */
enum class PlayerFace(val id: String, val label: String, val about: String) {
    /** Flat, centred, no artwork bleed. What the player looked like before. */
    CLASSIC(
        "classic", "Classic",
        "Flat background, artwork in a box, everything centred.",
    ),

    /**
     * The artwork becomes the light in the room.
     *
     * Three layers rather than one blurred copy: a dark track blurred once is a dark
     * rectangle and produces no colour at all. See `AmbientBackdrop`.
     */
    AMBIENT(
        "ambient", "Ambient",
        "The artwork lights the screen behind itself. Bigger art, scrolling title.",
    );

    companion object {
        val DEFAULT = AMBIENT

        fun of(stored: String?): PlayerFace = entries.firstOrNull { it.id == stored } ?: DEFAULT
    }
}
