package dev.niccc2007.filet

/**
 * Every notification id in Filet, in one place.
 *
 * ## Why this file exists
 *
 * Round 9. .
 *
 * `NearbyService` used `4201` and, from round 8, `UpdateNotifier` used `4_201`. The same
 * number, written two different ways - which is precisely why a grep for `4201` while adding
 * the updater found nothing and the clash shipped.
 *
 * A notification id is a **global key across the whole app**. Posting with an id that is
 * already taken replaces the other notification, and cancelling with it cancels somebody
 * else's. Nearby's is a FOREGROUND SERVICE notification, so the updater cancelling 4201 pulled
 * the notification out from under a running service; Android stops a foreground service whose
 * notification goes away, the service restarted, re-posted, and the loop is what he was
 * watching it flicker on a home screen.
 *
 * The lesson is not "be careful with numbers". It is that a global key was being declared
 * locally in whichever file happened to need one. They are declared here now, and
 * `tools/check-notifications.mjs` fails the build if two are equal or if a notification is
 * posted with a literal id that did not come from this file.
 */
object Notifications {

    /** Sharing over the network. A foreground service - see above for why that matters. */
    const val NEARBY = 4201

    /** Indexing. Also a foreground service. */
    const val INDEX = 4301

    /**
     * An update is available.
     *
     * Was 4201 and clashed with [NEARBY]. Moved rather than renumbering Nearby, because
     * Nearby's is the one a person may have already dismissed or long-pressed to configure, and
     * the id is what the system remembers that against.
     */
    const val UPDATE = 4401

    /** Every id above, for the checker and for a test to assert they are distinct. */
    val ALL: List<Pair<String, Int>> = listOf(
        "NEARBY" to NEARBY,
        "INDEX" to INDEX,
        "UPDATE" to UPDATE,
    )
}
