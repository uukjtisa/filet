package dev.niccc2007.filet.handlers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The list of every app that can open something, built once instead of on every sheet.
 *
 * Bug identified: "open in another app" took seconds to appear. Tapping "show all" ran
 * `allLaunchable` and `declaredTypes` inside `remember { }`, which executes **during
 * composition on the main thread**. Between them that is one `queryIntentActivities` for every
 * probed type, plus a label load per installed app - a hundred and fifty inter-process calls
 * on a well-stocked phone, every time the sheet was opened, for an answer that changes only
 * when an app is installed or removed.
 *
 * So it is computed off the main thread and kept. Nothing here is a heuristic: the answer is
 * genuinely stable between package changes, and the TTL exists only to catch a change that
 * arrived while Filet was not listening.
 */
object OpenerCatalog {

    /** Everything the picker needs, as one value so the two halves cannot disagree. */
    data class Catalog(
        val launchable: List<ExternalApp> = emptyList(),
        val declaredTypes: Map<String, List<String>> = emptyMap(),
        val builtAt: Long = 0,
    )

    /**
     * How long a built catalogue is trusted without rechecking.
     *
     * Ten minutes, and it is a backstop rather than the mechanism: [invalidate] is called when
     * a package is added or removed, which is the only thing that actually changes the answer.
     * Without a TTL a missed broadcast would leave a stale list until the process died.
     */
    const val TTL_MS = 10 * 60 * 1000L

    private val lock = Mutex()
    private var cached = Catalog()
    private var invalidatedAt = 0L

    /**
     * Whether a cached catalogue has to be rebuilt.
     *
     * @param builtAt when the cache was filled, 0 when it never has been.
     * @param invalidatedAt when a package change was last seen.
     */
    fun stale(builtAt: Long, invalidatedAt: Long, now: Long): Boolean = when {
        builtAt == 0L -> true
        invalidatedAt > builtAt -> true
        now - builtAt >= TTL_MS -> true
        // A clock that has gone backwards - a manual time change, or a timezone shift on some
        // devices - would otherwise keep a stale catalogue alive indefinitely.
        now < builtAt -> true
        else -> false
    }

    /** Called when a package is installed or removed. The next read rebuilds. */
    fun invalidate(now: Long = System.currentTimeMillis()) {
        invalidatedAt = now
    }

    /**
     * The catalogue, built if it has to be.
     *
     * Suspends rather than blocking, and the build runs on [Dispatchers.Default]. The mutex
     * means a second sheet opened while the first is still building waits for that one rather
     * than starting its own - opening two pickers quickly used to do the whole thing twice.
     */
    suspend fun get(context: Context, exclude: List<ExternalApp> = emptyList()): Catalog =
        lock.withLock {
            val now = System.currentTimeMillis()
            if (!stale(cached.builtAt, invalidatedAt, now)) return@withLock cached
            val built = withContext(Dispatchers.Default) {
                Catalog(
                    launchable = ExternalApps.allLaunchable(context, exclude),
                    declaredTypes = ExternalApps.declaredTypes(context),
                    builtAt = System.currentTimeMillis(),
                )
            }
            cached = built
            built
        }

    /** For tests and for a deliberate refresh. */
    fun reset() {
        cached = Catalog()
        invalidatedAt = 0
    }
}
