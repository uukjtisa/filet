package dev.niccc2007.filet.media

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue's ORDERING, which is the part that can be wrong without anything looking wrong.
 *
 * A loader that serves the right rows eventually and the wrong rows first is indistinguishable
 * from a slow loader, which is how the original arrival-order pool survived for so long: every
 * row did get its picture, just not until after several hundred rows nobody was looking at.
 *
 * One worker throughout, because concurrency would make the expected order a race. The queue's
 * concurrency ceiling is a separate property and is asserted on its own below.
 */
class LoadQueueTest {

    /** A queue whose single worker is held until the test lets it go. */
    private class Harness(workers: Int = 1, urgentCap: Int = 48, aheadCap: Int = 24) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = LoadQueue(workers, urgentCap, aheadCap, scope)
        val order: MutableList<String> = Collections.synchronizedList(ArrayList())
        val ran = java.util.concurrent.atomic.AtomicInteger()

        /**
         * Submits and returns once the task is ON the queue, awaiting it in the background.
         *
         * `UNDISPATCHED` is what makes the order in these tests mean anything. Launched
         * normally, the coroutine body - and therefore the enqueue inside it - runs whenever the
         * dispatcher gets round to it, so the order tasks were SUBMITTED in bore no relation to
         * the order `submit` was CALLED in and the expected orderings below were a coin toss.
         * Started undispatched, the body runs on this thread up to its first suspension point,
         * which is the `await` after the task is queued.
         */
        fun submit(key: String, urgent: Boolean = true, body: () -> Unit = {}) =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val work = {
                    order.add(key)
                    ran.incrementAndGet()
                    body()
                }
                if (urgent) queue.join(key, work) else queue.ahead(key, work)
            }

        /** Waits for the queue to drain, so a test never passes by finishing early. */
        fun drain() = runBlocking {
            withTimeout(5_000) {
                while (queue.depth() != Triple(0, 0, 0)) Thread.sleep(2)
            }
        }
    }

    @Test fun `the newest request is served first`() {
        val h = Harness()
        val hold = CompletableDeferred<Unit>()
        // Occupies the only worker, so everything below queues up behind it.
        h.submit("blocker") { runBlocking { hold.await() } }
        while (h.queue.depth().third == 0) Thread.sleep(1)

        h.submit("a")
        h.submit("b")
        h.submit("c")

        hold.complete(Unit)
        h.drain()

        // LIFO: the row asked for most recently is the one under the finger now. Arrival order
        // would give a, b, c - and after a fling that means serving several hundred rows that
        // have already gone past before reaching the one on screen.
        assertEquals(listOf("blocker", "c", "b", "a"), h.order.toList())
    }

    @Test fun `a pre-fetch waits for everything on screen`() {
        val h = Harness()
        val hold = CompletableDeferred<Unit>()
        h.submit("blocker") { runBlocking { hold.await() } }
        while (h.queue.depth().third == 0) Thread.sleep(1)

        // Queued BEFORE the urgent pair, and still served after both: the tier decides, not the
        // arrival time. Without this split, prefetching would enqueue newer requests than the
        // visible rows and LIFO would faithfully serve the rows nobody is looking at first.
        h.submit("ahead1", urgent = false)
        h.submit("ahead2", urgent = false)
        h.submit("seen1")
        h.submit("seen2")

        hold.complete(Unit)
        h.drain()

        assertEquals(listOf("blocker", "seen2", "seen1", "ahead1", "ahead2"), h.order.toList())
    }

    @Test fun `one file is read once however many rows ask for it`() {
        val h = Harness()
        val hold = CompletableDeferred<Unit>()
        h.submit("blocker") { runBlocking { hold.await() } }
        while (h.queue.depth().third == 0) Thread.sleep(1)

        // The same key five times. A list recomposes constantly while it scrolls, so this is
        // the ordinary case rather than an odd one.
        repeat(5) { h.submit("same") }
        assertEquals(1, h.queue.depth().first)

        hold.complete(Unit)
        h.drain()
        assertEquals(listOf("blocker", "same"), h.order.toList())
    }

    @Test fun `a row that arrives on screen is promoted rather than queued twice`() {
        val h = Harness()
        val hold = CompletableDeferred<Unit>()
        h.submit("blocker") { runBlocking { hold.await() } }
        while (h.queue.depth().third == 0) Thread.sleep(1)

        h.submit("row", urgent = false)
        h.submit("filler")
        // The pre-fetched row is now on screen. It must move tier, not gain a second task.
        h.submit("row", urgent = true)
        assertEquals(0, h.queue.depth().second)

        hold.complete(Unit)
        h.drain()
        // Promoted to the newest position, so it goes before the filler that was urgent first.
        assertEquals(listOf("blocker", "row", "filler"), h.order.toList())
        assertEquals(2, h.order.count { it != "blocker" })
    }

    @Test fun `over the cap the oldest requests are dropped, not the newest`() {
        val h = Harness(urgentCap = 3)
        val hold = CompletableDeferred<Unit>()
        h.submit("blocker") { runBlocking { hold.await() } }
        while (h.queue.depth().third == 0) Thread.sleep(1)

        for (i in 1..6) h.submit("r$i")
        assertEquals(3, h.queue.depth().first)

        hold.complete(Unit)
        h.drain()

        // Under LIFO the oldest end is the furthest from the viewport, so that is the end that
        // gets dropped. The three most recent survive, newest served first.
        assertEquals(listOf("blocker", "r6", "r5", "r4"), h.order.toList())
    }

    @Test fun `a dropped request completes rather than leaving its caller waiting`() {
        val h = Harness(urgentCap = 1)
        val hold = CompletableDeferred<Unit>()
        h.submit("blocker") { runBlocking { hold.await() } }
        while (h.queue.depth().third == 0) Thread.sleep(1)

        // `first` is dropped when `second` arrives. Its caller must be released: a row awaiting
        // a task that will never run would wait for the life of the screen.
        val waiting = h.submit("first")
        h.submit("second")

        hold.complete(Unit)
        h.drain()
        runBlocking { withTimeout(5_000) { waiting.join() } }
        assertTrue(waiting.isCompleted)
    }

    @Test fun `never more than the given number of workers run at once`() {
        val h = Harness(workers = 2)
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val now = java.util.concurrent.atomic.AtomicInteger()
        repeat(20) { i ->
            h.submit("w$i") {
                val at = now.incrementAndGet()
                peak.updateAndGet { p -> maxOf(p, at) }
                Thread.sleep(5)
                now.decrementAndGet()
            }
        }
        h.drain()
        assertEquals(20, h.ran.get())
        // The whole reason the ceiling exists: a header parse and a bitmap decode are both CPU,
        // and the main thread needs a core of its own in order to draw the rows they are for.
        assertTrue("peaked at " + peak.get(), peak.get() <= 2)
    }
}
