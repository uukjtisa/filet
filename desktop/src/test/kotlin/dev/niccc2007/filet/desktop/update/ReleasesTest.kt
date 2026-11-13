package dev.niccc2007.filet.desktop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {

    private val sha = "a".repeat(64)

    private fun release(
        tag: String,
        msi: String? = "Filet-Desktop-Network-Tool-${tag.substringAfterLast('v')}.msi",
        digest: String? = "sha256:$sha",
        draft: Boolean = false,
        pre: Boolean = false,
        extra: String = "",
    ): String {
        val assets = buildList {
            if (msi != null) add(
                """{"name":"$msi","size":61000000,"browser_download_url":"https://github.com/uukjtisa/filet/releases/download/$tag/x.msi"""" +
                    (digest?.let { ""","digest":"$it"""" } ?: "") + "}",
            )
            add("""{"name":"Filet-Desktop-Network-Tool-setup.exe","size":62000000,"browser_download_url":"https://example/x.exe","digest":"sha256:$sha"}""")
        }
        return """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"html_url":"https://github.com/uukjtisa/filet/releases/tag/$tag","body":"notes $tag"$extra,"assets":[${assets.joinToString(",")}]}"""
    }

    private fun list(vararg r: String) = "[" + r.joinToString(",") + "]"

    @Test
    fun `the newest desktop release is offered, and the app's higher-numbered tags never are`() {
        val json = list(
            release("v0.9.0"),
            release("filet-desktop-network-tool-v0.1.2"),
            release("filet-desktop-network-tool-v0.1.10"),
            release("filet-desktop-network-tool-v0.1.9"),
        )
        val o = Releases.newest(json, "0.1.1")!!
        assertEquals("0.1.10", o.version)
        assertEquals("filet-desktop-network-tool-v0.1.10", o.tag)
        assertEquals(sha, o.installer.sha256)
        assertTrue(o.installer.name.endsWith(".msi"))
        assertEquals("https://github.com/uukjtisa/filet/releases/tag/filet-desktop-network-tool-v0.1.10", o.page)
    }

    @Test
    fun `nothing is offered when this copy is the newest, or newer`() {
        val json = list(release("filet-desktop-network-tool-v0.1.1"), release("filet-desktop-network-tool-v0.1.0"))
        assertNull(Releases.newest(json, "0.1.1"))
        assertNull(Releases.newest(json, "0.2.0"))
    }

    @Test
    fun `drafts, pre-releases and suffixed tags are never offered`() {
        val json = list(
            release("filet-desktop-network-tool-v0.3.0", draft = true),
            release("filet-desktop-network-tool-v0.2.0", pre = true),
            release("filet-desktop-network-tool-v0.2.1-rc1"),
            release("filet-desktop-network-tool-v0.1.2"),
        )
        assertEquals("0.1.2", Releases.newest(json, "0.1.1")!!.version)
    }

    @Test
    fun `a release without an MSI, or whose MSI has no SHA-256, is skipped rather than offered`() {
        val json = list(
            release("filet-desktop-network-tool-v0.4.0", msi = null),
            release("filet-desktop-network-tool-v0.3.0", digest = null),
            release("filet-desktop-network-tool-v0.2.5", digest = "sha256:notahexdigest"),
            release("filet-desktop-network-tool-v0.2.0"),
        )
        assertEquals("0.2.0", Releases.newest(json, "0.1.1")!!.version)
        assertNull(Releases.newest(list(release("filet-desktop-network-tool-v0.4.0", digest = null)), "0.1.1"))
    }

    @Test
    fun `versions compare by number, part by part`() {
        assertEquals(1, Releases.compare("0.1.10", "0.1.9"))
        assertEquals(0, Releases.compare("0.2", "0.2.0"))
        assertEquals(-1, Releases.compare("v0.1.1", "0.1.2"))
        assertNull(Releases.compare("dev", "0.1.0"))
        assertEquals("1.2.3", Releases.versionOf("filet-desktop-network-tool-v1.2.3"))
        assertNull(Releases.versionOf("v1.2.3"))
        assertNull(Releases.versionOf("xfilet-desktop-network-tool-v1.2.3"))
    }

    @Test
    fun `an answer that is not a release list offers nothing`() {
        assertNull(Releases.newest("""{"message":"API rate limit exceeded"}""", "0.1.1"))
        assertNull(Releases.newest("not json", "0.1.1"))
        assertNull(Releases.newest(list(release("filet-desktop-network-tool-v0.2.0")), "dev"))
    }
}
