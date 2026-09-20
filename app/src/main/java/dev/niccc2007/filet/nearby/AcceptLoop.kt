package dev.niccc2007.filet.nearby

/**
 * Whether a server's accept loop should keep going.
 *
 * Bug identified, and it is the stop-then-start crash loop.
 *
 * The loop was written `while (running) { accept() ?: continue }` - a shared flag for the
 * condition, and `continue` when a connection failed. Stopping sets the flag false and closes
 * the socket, so ordinarily the loop notices and ends.
 *
 * Starting again immediately does not wait for it. `start` sees the flag already false, binds a
 * NEW socket, and sets the flag back to true - and the OLD loop, which has not run its check
 * yet, now sees a true flag while holding a socket that was closed underneath it. `accept()`
 * throws the instant it is called, `continue` sends it straight back round, and the thread
 * spins as fast as the CPU allows on a dead socket. Two of them if it happens twice.
 *
 * That is why it needed exact timing to reproduce, why the phone got hot, and why the app
 * could not be closed: a runaway thread in the pool, not the indexing it was blamed on.
 *
 * Two changes, and both are needed:
 *
 *  - A loop belongs to the socket it was started for, identified by a **generation** that
 *    increments on every start. A loop from a previous generation stops, whatever the flag says.
 *  - A failed accept **breaks** rather than continuing. Without a read timeout set, a throw
 *    from `accept()` means the socket is gone, and going back round cannot help.
 */
object AcceptLoop {

    /**
     * @param running the server's own flag.
     * @param myGeneration the generation this loop was started for.
     * @param currentGeneration the generation the server is on now.
     * @param socketClosed whether this loop's own socket has been closed.
     */
    fun keepGoing(
        running: Boolean,
        myGeneration: Long,
        currentGeneration: Long,
        socketClosed: Boolean,
    ): Boolean = running && myGeneration == currentGeneration && !socketClosed

    /**
     * Whether a failed `accept()` should end the loop.
     *
     * Always. The socket carries no timeout, so a throw is not a quiet period - it is the
     * socket being closed or broken, and retrying it is the spin.
     */
    fun stopOnAcceptFailure(): Boolean = true
}
