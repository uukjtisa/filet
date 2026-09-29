package dev.niccc2007.filet.vfs.provider.net

/**
 * What a discovered host MEANS to the remotes already saved.
 *
 * Discovery hands over an address, a port and - from a host new enough to send one - a device
 * id. Three different questions follow from one sighting, and they had been answered in three
 * different places:
 *
 * 1. **Is this a device I have saved, at an address the entry does not know?** Then learn it,
 *    and changing network costs nobody anything.
 * 2. **Is this a device I have saved but cannot yet recognise?** An entry typed by hand, or one
 *    saved before ids existed, has no id at all - so it can never match one, and it can never
 *    learn. If the sighting is at an address that entry already holds, the id is bound to it.
 *    This is the step that was missing, and without it learning cannot start.
 * 3. **Is this share already saved?** Then it is not something to add, and offering it as new is
 *    how a device ends up with four entries for one tablet.
 *
 * All three are pure decisions over data, so they live here with a test rather than inside a
 * screen.
 *
 * ## Why an address is allowed to bind an id, when an id is not allowed to be guessed
 *
 * An address is where credentials get sent, so [learningsFor] will not add one on anything
 * weaker than a matching id. Binding runs the other way: the address was **typed by the person
 * who owns both devices**, so a host announcing itself there is that entry's device, and the
 * only thing being written is the name Filet will recognise it by next time. Nothing is sent
 * anywhere and no credential moves.
 *
 * The one case that is refused: an entry that already carries a DIFFERENT id, seen at one of its
 * addresses. That is an address reused by another device, and overwriting the id would hand one
 * device's entry to another. Nothing is written and the sighting is treated as a new device.
 *
 * ## Port is the share
 *
 * One HTTP server listens on one port, so two shares on one device are two ports. That makes
 * the port the right thing to compare for "is this already saved", and the wrong thing to
 * compare for "is this the same device" - a phone hosting two shares is still one phone.
 */
object Sightings {

    /** The fields of a saved remote that a sighting can be compared against. */
    data class Saved(
        val id: String,
        val deviceId: String,
        val host: String,
        val altHosts: List<String>,
        val port: Int,
    )

    /** A host as discovery announced it. */
    data class Seen(
        val deviceId: String,
        val host: String,
        val port: Int,
    )

    /** Something worth writing to a saved remote. */
    sealed interface Learning {
        val connectionId: String

        /** This device is reachable at an address the entry did not have. */
        data class Address(override val connectionId: String, val host: String) : Learning

        /** The entry now knows which device it points at, so it can follow that device around. */
        data class DeviceId(override val connectionId: String, val deviceId: String) : Learning
    }

    /**
     * Everything [seen] teaches the saved remotes - usually nothing, because discovery
     * re-announces constantly by design.
     *
     * At most one learning per entry: an entry either recognises the device and may learn an
     * address, or does not recognise it and may bind the id. Both at once is impossible, since
     * binding only happens at an address the entry already holds.
     */
    fun learningsFor(saved: List<Saved>, seen: Seen): List<Learning> {
        if (seen.host.isBlank() || seen.deviceId.isBlank()) return emptyList()
        val out = ArrayList<Learning>()
        for (entry in saved) {
            val knowsAddress = addresses(entry).any { it.equals(seen.host.trim(), ignoreCase = true) }
            when {
                entry.deviceId.equals(seen.deviceId, ignoreCase = true) ->
                    if (!knowsAddress) out += Learning.Address(entry.id, seen.host.trim())

                entry.deviceId.isBlank() && knowsAddress ->
                    out += Learning.DeviceId(entry.id, seen.deviceId)

                // An entry with a different id, at one of its own addresses: refused above.
                else -> Unit
            }
        }
        return out
    }

    /**
     * The saved remote this sighting already IS, or null when it is genuinely something new.
     *
     * Used to decide whether a discovered host is offered as an *Add*. A row that offers to add
     * a share which is three rows further down the same screen is the duplication complaint in
     * one picture.
     */
    fun savedFor(saved: List<Saved>, seen: Seen): String? = saved.firstOrNull { entry ->
        if (entry.port != seen.port) return@firstOrNull false
        val sameDevice = seen.deviceId.isNotBlank() &&
            entry.deviceId.equals(seen.deviceId, ignoreCase = true)
        val sameAddress = addresses(entry).any { it.equals(seen.host.trim(), ignoreCase = true) }
        sameDevice || sameAddress
    }?.id

    private fun addresses(entry: Saved): List<String> =
        Endpoints.ordered(entry.host, entry.altHosts, null)
}
