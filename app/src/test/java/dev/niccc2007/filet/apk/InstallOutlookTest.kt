package dev.niccc2007.filet.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallOutlookTest {

    private val key = "AA:BB:CC"
    private val other = "11:22:33"

    private fun facts(
        signed: Boolean = true,
        apk: Long = 2,
        apkSha: String? = key,
        installed: Long? = 1,
        installedSha: String? = key,
    ) = InstallFacts(signed, apk, apkSha, installed, installedSha)

    @Test
    fun `nothing installed is a clean first install`() {
        val o = InstallOutlooks.of(facts(installed = null, installedSha = null))
        assertEquals(Outlook.FRESH, o.outlook)
        assertEquals(false, o.severe)
    }

    @Test
    fun `a higher version with the same key is an update`() {
        assertEquals(Outlook.UPDATE, InstallOutlooks.of(facts(apk = 5, installed = 4)).outlook)
    }

    @Test
    fun `the same version code is a reinstall`() {
        assertEquals(Outlook.REINSTALL, InstallOutlooks.of(facts(apk = 4, installed = 4)).outlook)
    }

    @Test
    fun `a lower version is a downgrade and is severe`() {
        val o = InstallOutlooks.of(facts(apk = 3, installed = 9))
        assertEquals(Outlook.DOWNGRADE, o.outlook)
        assertTrue(o.severe)
    }

    @Test
    fun `a signature clash beats the version comparison whichever way it runs`() {
        // The one that costs data. Reporting a clash as an ordinary update is the worst
        // possible bug in this file, so it is checked before the versions are compared at all.
        for (apk in listOf(1L, 4L, 99L)) {
            val o = InstallOutlooks.of(facts(apk = apk, installed = 4, installedSha = other))
            assertEquals("apk=$apk", Outlook.SIGNATURE_CLASH, o.outlook)
            assertTrue("apk=$apk", o.severe)
        }
    }

    @Test
    fun `an unsigned package is refused before anything else is considered`() {
        // Even against an installed copy with a matching everything else: nothing unsigned
        // installs, so no other outcome can be reached.
        val o = InstallOutlooks.of(facts(signed = false, installed = null, installedSha = null))
        assertEquals(Outlook.UNSIGNED, o.outlook)
        assertEquals(
            Outlook.UNSIGNED,
            InstallOutlooks.of(facts(signed = false, apk = 9, installed = 1)).outlook,
        )
    }

    @Test
    fun `case never turns a matching key into a clash`() {
        // The two sources format the digest differently. A case mismatch reading as a key
        // mismatch would tell somebody to uninstall an app for no reason.
        val o = InstallOutlooks.of(facts(apkSha = "aa:bb:cc", installedSha = "AA:BB:CC"))
        assertNotEquals(Outlook.SIGNATURE_CLASH, o.outlook)
        assertEquals(Outlook.UPDATE, o.outlook)
    }

    @Test
    fun `a missing certificate says so rather than guessing`() {
        // Silence here would read as "the keys match", which is the reassuring answer and the
        // one there is no evidence for.
        for (f in listOf(
            facts(apkSha = null),
            facts(installedSha = null),
        )) {
            val o = InstallOutlooks.of(f)
            assertEquals(Outlook.UNKNOWN_STATE, o.outlook)
            assertTrue("an unknown must not read as safe", o.severe)
        }
    }

    @Test
    fun `a package that is simply absent says nothing`() {
        // "Not installed" and "not visible to Filet" arrive as the same answer from
        // PackageManager. Announcing the first one meant announcing the second by mistake, for
        // most of the apps on a phone. Not-installed is also what a reader already assumes.
        assertEquals(false, InstallOutlooks.of(facts(installed = null, installedSha = null)).worthShowing)
    }

    @Test
    fun `everything that is not absence is worth saying`() {
        val cases = listOf(
            facts(apk = 5, installed = 4),
            facts(apk = 4, installed = 4),
            facts(apk = 3, installed = 9),
            facts(installedSha = other),
            facts(signed = false),
            facts(apkSha = null),
        )
        for (f in cases) assertTrue("${InstallOutlooks.of(f).outlook}", InstallOutlooks.of(f).worthShowing)
    }

    @Test
    fun `every outcome has words and they differ`() {
        val seen = mutableSetOf<String>()
        val cases = listOf(
            facts(installed = null, installedSha = null),
            facts(apk = 5, installed = 4),
            facts(apk = 4, installed = 4),
            facts(apk = 3, installed = 9),
            facts(installedSha = other),
            facts(signed = false),
            facts(apkSha = null),
        )
        for (f in cases) {
            val o = InstallOutlooks.of(f)
            assertTrue(o.headline.isNotBlank())
            assertTrue(o.detail.isNotBlank())
            assertTrue("${o.outlook} repeats another headline", seen.add(o.headline))
        }
        // Every enum value is reachable from a real combination of facts.
        assertEquals(Outlook.entries.size, cases.size)
    }
}
