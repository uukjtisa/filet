package dev.niccc2007.filet.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** One selection while a pick is live: the pick's. */
class PickRouteTest {

    @Test fun `with no pick, selecting is the pane's as always`() {
        assertEquals(PickRoute.PANE, PickRoute.of(pickLive = false, isDir = false))
        assertEquals(PickRoute.PANE, PickRoute.of(pickLive = false, isDir = true))
    }

    @Test fun `a held file during a pick goes into the pick - the reported bug`() {
        assertEquals(PickRoute.PICK, PickRoute.of(pickLive = true, isDir = false))
    }

    @Test fun `a folder during a pick starts no second selection`() {
        assertEquals(PickRoute.NOTHING, PickRoute.of(pickLive = true, isDir = true))
    }
}
