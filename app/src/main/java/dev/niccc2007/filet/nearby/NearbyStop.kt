package dev.niccc2007.filet.nearby

/**
 * Who is tearing sharing down, and therefore what is still left to do.
 *
 * Bug identified here: stopping sharing was two functions that each called the other.
 * `NearbyManager.stop()` finished by telling the foreground service to stop, and the service's
 * stop branch began by telling the manager to stop - so one press of Stop set off a ping-pong
 * of service intents that never terminated. Every lap re-posted the ongoing notification, and
 * every lap called `server.stop()`.
 *
 * The visible damage was not the loop itself but what it did to the *next* start. A start
 * binds the socket and publishes a running state, and then the next `ACTION_STOP` still in the
 * queue pulls it straight back down. Sharing appears to come on and go off again, and no
 * amount of pressing Start helps, because each press feeds the same queue. Nothing was logged
 * and nothing threw: every individual call did exactly what it was written to do.
 *
 * So teardown stops being symmetrical. There is one place that stops the server, and only a
 * stop that came from the screen also has a service left to stop - the other two origins *are*
 * the service, and it stops itself directly rather than asking to be stopped.
 */
enum class StopOrigin {
    /** The Stop sharing button in the app. The service is still up and has to be told. */
    SCREEN,

    /** The Stop action on the notification, handled inside the service. */
    SERVICE,

    /** The service's own idle watch, or its own failure to show a notification. */
    IDLE,
}

/**
 * Whether stopping from [origin] should send the foreground service a stop intent.
 *
 * True for exactly one origin. Anything reached from inside the service must answer false, or
 * the service asks itself to stop and the cycle is back - which is why this is a function with
 * a test rather than a condition written inline at three call sites.
 */
fun tellsTheService(origin: StopOrigin): Boolean = when (origin) {
    StopOrigin.SCREEN -> true
    StopOrigin.SERVICE, StopOrigin.IDLE -> false
}

/** The origins that are already running inside the service. */
val INSIDE_THE_SERVICE = setOf(StopOrigin.SERVICE, StopOrigin.IDLE)
