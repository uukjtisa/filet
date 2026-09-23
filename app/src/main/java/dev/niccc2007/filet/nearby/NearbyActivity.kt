package dev.niccc2007.filet.nearby

/**
 * What a connected browser is doing right now.
 *
 * The screen used to carry one boolean - sharing, or not - drawn as a green light. That light
 * was being asked to answer a question it could not: "is somebody pulling a file off my phone
 * at this moment". The server only ever recorded activity *after* a transfer finished, so the
 * moment that actually matters was the one moment nothing on screen changed.
 */
enum class ClientActivity {
    /** Connected, reading the listing, not moving bytes. */
    BROWSING,

    /** Pulling a file, a folder zip or a multi-file zip off this phone. */
    DOWNLOADING,

    /** Pushing a file onto this phone. */
    UPLOADING,
}

/**
 * What the sharing light says, for a whole set of connected clients.
 *
 * A pure function with tests because the precedence in it is a real decision and every part of
 * it can be got wrong in a way that is invisible until the one moment it matters:
 *
 *  - **Transfers outrank idleness.** One browser downloading while four sit idle is a phone
 *    that is busy, and averaging that to "mostly idle" is how the light ends up lying.
 *  - **Uploads outrank downloads.** Something is being written to this device's storage, which
 *    is the one direction worth interrupting; a download can be left alone.
 *  - **Connected-and-idle is not the same as nobody there.** The light has to separate "shared
 *    and nobody has come" from "somebody is looking", because those call for different
 *    reactions from the person holding the phone.
 */
object NearbyActivity {

    /** The card's own light, strongest claim first. */
    enum class Light {
        /** Not sharing at all. */
        OFF,

        /** Sharing, nobody connected. */
        WAITING,

        /** Somebody is connected and reading, but no bytes are moving. */
        WATCHED,

        /** At least one download in flight. */
        SENDING,

        /** At least one upload in flight. Outranks a download. */
        RECEIVING,
    }

    /** What the light shows for [clients], given whether the server is [running]. */
    fun lightFor(running: Boolean, clients: List<NearbyClient>): Light = when {
        !running -> Light.OFF
        clients.any { it.activity == ClientActivity.UPLOADING } -> Light.RECEIVING
        clients.any { it.activity == ClientActivity.DOWNLOADING } -> Light.SENDING
        clients.isNotEmpty() -> Light.WATCHED
        else -> Light.WAITING
    }

    /**
     * The sentence under the heading.
     *
     * Counts the files being offered and the devices connected, and names the transfer in
     * flight when there is one - so the card says what is happening without needing the list
     * below it to be read.
     */
    fun summary(running: Boolean, shared: Int, clients: List<NearbyClient>): String {
        if (!running) return "Nothing is being shared"
        val files = "$shared file" + if (shared == 1) "" else "s"
        val devices = when (clients.size) {
            0 -> "nobody connected yet"
            1 -> "1 device connected"
            else -> "${clients.size} devices connected"
        }
        return "$files · $devices"
    }

    /**
     * The extra line naming a transfer, or null when nothing is moving.
     *
     * Separate from [summary] because it is the part that appears and disappears, and a line
     * that changes length on every state change makes the card jump.
     */
    fun transferNote(clients: List<NearbyClient>): String? {
        val up = clients.count { it.activity == ClientActivity.UPLOADING }
        val down = clients.count { it.activity == ClientActivity.DOWNLOADING }
        return when {
            up > 0 && down > 0 -> "$up upload${s(up)} and $down download${s(down)} in progress"
            up > 0 -> "$up upload${s(up)} in progress"
            down > 0 -> "$down download${s(down)} in progress"
            else -> null
        }
    }

    private fun s(n: Int) = if (n == 1) "" else "s"
}
