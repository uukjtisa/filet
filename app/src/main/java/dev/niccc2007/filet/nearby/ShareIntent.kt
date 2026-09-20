package dev.niccc2007.filet.nearby

/**
 * Whether a start that has just finished is still the thing being asked for.
 *
 * Bug identified here: starting is asynchronous and stopping is not. Starting creates folders,
 * reads preferences and binds a socket on a background coroutine; Stop runs straight away on
 * the caller's thread. Press Stop about a second after Start and the stop completes *first* -
 * it sets the server's running flag false and closes a socket that has not been assigned yet -
 * and then the start finishes and sets the flag back to true.
 *
 * The server is then running while the screen, which was told about the stop, shows Start. And
 * the start path answers `AlreadyRunning` by returning without a word, so every later press of
 * Start does nothing at all. Nothing throws, nothing is logged, and the only way out is to
 * force-stop the app. That is the whole fault: not a socket, not a thread, an ordering.
 *
 * The fix is to give the user's last instruction authority over an older one that is still
 * finishing. Stopping moves a token; a start remembers the token it began under and checks it
 * before publishing anything.
 *
 * Only stopping moves the token, deliberately. If starting moved it too, two quick presses of
 * Start would make the first press treat the second as a contradiction and tear down the
 * server the second one had just brought up - the same class of bug, one level along.
 */
fun startStillWanted(tokenAtLaunch: Long, tokenNow: Long): Boolean = tokenAtLaunch == tokenNow
