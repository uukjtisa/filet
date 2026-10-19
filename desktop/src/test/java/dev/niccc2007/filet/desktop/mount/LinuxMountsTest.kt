package dev.niccc2007.filet.desktop.mount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinuxMountsTest {
    private val t = DavTarget("10.0.0.5", 8321, false, "/a/", "xyz9")

    @Test
    fun `davfs entries in proc mounts are listed with octal escapes undone`() {
        val proc = """
            /dev/sda1 / ext4 rw 0 0
            http://10.0.0.5:8321/a/xyz9/ /media/filet/My\040Phone fuse rw 0 0
            http://10.0.0.5:8321/a/xyz9/ /media/filet/My\040Phone davfs rw 0 0
            http://nas/dav/ /mnt/nas davfs rw 0 0
        """.trimIndent()
        assertEquals(listOf(t to "/media/filet/My Phone"), LinuxMounts.davfs(proc))
    }

    @Test
    fun `fstab line needs the url, davfs and the user option`() {
        val fstab = """
            # comment
            http://10.0.0.5:8321/a/xyz9/ /media/filet/a davfs noauto 0 0
            http://10.0.0.5:8321/a/xyz9  /media/filet/b davfs user,noauto 0 0
        """.trimIndent()
        assertEquals("/media/filet/b", LinuxMounts.fstabLine(fstab, t.url))
        assertNull(LinuxMounts.fstabLine("http://10.0.0.5:8321/a/xyz9/ /m davfs noauto 0 0", t.url))
    }

    @Test
    fun `suggested fstab line is one davfs user line`() =
        assertEquals("http://10.0.0.5:8321/a/xyz9/  /media/filet/MyPhone  davfs  user,noauto,rw  0  0",
            LinuxMounts.suggestedFstab(t.url, "My Phone!"))

    @Test
    fun `gvfs directory names read back to targets`() {
        assertEquals(t, LinuxMounts.parseGvfsName("dav:host=10.0.0.5,port=8321,ssl=false,prefix=%2Fa%2Fxyz9"))
        assertNull(LinuxMounts.parseGvfsName("smb-share:server=nas,share=media"))
        assertNull(LinuxMounts.parseGvfsName("dav:host=10.0.0.5,port=8321,ssl=false,prefix=%2Fother"))
    }

    @Test
    fun `parseUrl accepts only filet share urls`() {
        assertEquals(t, LinuxMounts.parseUrl(t.url))
        assertEquals(t, LinuxMounts.parseUrl("http://10.0.0.5:8321/a/xyz9"))
        assertNull(LinuxMounts.parseUrl("http://10.0.0.5:8321/b/xyz9/"))
        assertNull(LinuxMounts.parseUrl("http://10.0.0.5/a/xyz9/"))
    }

    @Test
    fun `platform comes from os name`() {
        assertEquals(Platform.WINDOWS, Platform.of("Windows 11"))
        assertEquals(Platform.MAC, Platform.of("Mac OS X"))
        assertEquals(Platform.LINUX, Platform.of("Linux"))
        assertEquals(Platform.OTHER, Platform.of("FreeBSD"))
        assertEquals(Platform.OTHER, Mounters.forThisOs(Platform.OTHER).platform)
    }
}
