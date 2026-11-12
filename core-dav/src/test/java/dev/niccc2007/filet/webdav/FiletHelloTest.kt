package dev.niccc2007.filet.webdav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FiletHelloTest {

    private val me = FiletHello.Identity("dev-1", "NCO-LX1", "phone")

    @Test
    fun `the answer has the agreed shape`() {
        val docs = DavShare(1, "Documents", customRoot = "/storage/emulated/0/Documents", writable = true)
        val o = JSONObject(FiletHello.json(me, 11111, listOf(docs to false)))
        assertEquals(setOf("v", "id", "name", "kind", "port", "shares"), o.keySet())
        assertEquals(1, o.getInt("v"))
        assertEquals("dev-1", o.getString("id"))
        assertEquals("NCO-LX1", o.getString("name"))
        assertEquals("phone", o.getString("kind"))
        assertEquals(11111, o.getInt("port"))
        val s = o.getJSONArray("shares").getJSONObject(0)
        assertEquals(setOf("label", "scope", "w"), s.keySet())
        assertEquals("Documents", s.getString("label"))
        assertEquals("Documents", s.getString("scope"))
        // The session's write flag, not the stored one.
        assertFalse(s.getBoolean("w"))
    }

    @Test
    fun `no code and no path anywhere, even when the share carries both`() {
        val secret = DavShare(2, "Private", customRoot = "D:/Taxes/Private", code = "Secret9", writable = true)
        val text = FiletHello.json(me, 8322, listOf(secret to true))
        assertFalse(text, text.contains("Secret9"))
        assertFalse(text, text.contains("\"code\""))
        assertFalse(text, text.contains("D:/Taxes"))
        assertFalse(text, text.contains("\"path\""))
    }

    @Test
    fun `only this name is answered, any other well-known name is a 404, and the rest is not touched`() {
        assertEquals(FiletHello.Route.HELLO, FiletHello.route("/.well-known/filet"))
        assertEquals(FiletHello.Route.HELLO, FiletHello.route("/.well-known/filet/"))
        assertEquals(FiletHello.Route.HELLO, FiletHello.route("/.well-known/filet?x=1"))
        assertEquals(FiletHello.Route.NOT_FOUND, FiletHello.route("/.well-known/security.txt"))
        assertEquals(FiletHello.Route.NOT_FOUND, FiletHello.route("/.well-known/filetx"))
        assertEquals(FiletHello.Route.NOT_FOUND, FiletHello.route("/.well-known"))
        assertNull(FiletHello.route("/a/tres3/"))
        assertNull(FiletHello.route("/"))
    }

    @Test
    fun `a name with quotes and unicode survives the trip`() {
        val o = JSONObject(FiletHello.json(me.copy(name = "Living room \"Nova\" ünî"), 1, emptyList()))
        assertEquals("Living room \"Nova\" ünî", o.getString("name"))
        assertEquals(0, o.getJSONArray("shares").length())
    }
}
