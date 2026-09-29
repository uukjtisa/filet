package dev.niccc2007.filet.vfs.provider.net

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where a connection is actually made, which is the fault behind "the extra address does nothing".
 *
 * Rotation to the next saved address used to live in the helper that BUILDS an
 * `HttpURLConnection`, on the reasonable-sounding assumption that a connection that cannot be
 * made throws there. It does not. This proves both halves of that, so the fix cannot be quietly
 * undone by someone moving the retry back:
 *
 *  1. Building the connection object does no network at all, so a dead address gets past it.
 *  2. The failure arrives at the RESPONSE, which is in the caller - where rotation now sits.
 *
 * Port 1 on loopback is used because nothing listens there and the refusal is immediate: no
 * external host, no timeout, no network needed to run the test.
 */
class WhereConnectHappensTest {

    private val nothingListening = "http://127.0.0.1:1/filet"

    @Test
    fun `opening the connection object touches no network`() {
        // If this threw, the old rotation would have worked and there would have been no bug.
        val http = URL(nothingListening).openConnection() as HttpURLConnection
        assertNotNull(http)
        http.disconnect()
    }

    @Test
    fun `the failure arrives at the response, which is where the retry has to be`() {
        val http = URL(nothingListening).openConnection() as HttpURLConnection
        http.connectTimeout = 1_500
        http.readTimeout = 1_500
        val thrown = runCatching { http.responseCode }.exceptionOrNull()
        assertTrue("expected a connect failure, got $thrown", thrown is IOException)
        // And it has to be recognised as the ADDRESS being wrong, or the retry sees an ordinary
        // error and reports it instead of trying the next address.
        assertTrue(NetFailure.isAddressFailure(thrown))
        http.disconnect()
    }
}
