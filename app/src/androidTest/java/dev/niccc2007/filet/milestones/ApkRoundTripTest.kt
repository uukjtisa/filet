package dev.niccc2007.filet.milestones

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.niccc2007.filet.apk.ApkTools
import dev.niccc2007.filet.apk.SigningKeys
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.jobs.JobLedger
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M6's exit criterion, end to end.
 *
 * > An APK is decompiled, one smali line edited, rebuilt, re-signed and installed on device
 * > (PLAN.md §8, M6).
 *
 * The fixture is `tools/fixture` - an 8 KB app whose only job is to record one string
 * constant. It deliberately has **no launcher entry**: it is started by component name, and
 * an 8 KB dummy that finishes immediately looks like a crashing app if it lands in someone's
 * app drawer.
 * Small on purpose: the pipeline is what is under test, not baksmali's throughput, and an
 * 8 KB APK makes the whole round trip run in about a second.
 *
 * This test takes it as far as a **signed, installable APK containing the edited code**. The
 * installing-and-running half is the gate's other clause and is driven from the host, because
 * an instrumented test cannot install a package and then read another process's log without
 * permissions no file manager should ever hold. The rebuilt APK is left at a known path for
 * exactly that purpose.
 */
@RunWith(AndroidJUnit4::class)
class ApkRoundTripTest {

    private lateinit var tmp: File
    private lateinit var vfs: Vfs
    private lateinit var tools: ApkTools
    private lateinit var keys: SigningKeys
    private lateinit var apk: VPath
    private lateinit var outDir: File

    private fun vpath(f: File) = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        tmp = File(context.cacheDir, "apk-roundtrip-" + System.nanoTime()).apply { mkdirs() }

        val target = File(tmp, "fixture.apk")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.apk").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        apk = vpath(target)

        outDir = Gates.dir(context)
        vfs = Vfs(listOf(LocalProvider(listOf(tmp, outDir))))
        tools = ApkTools(context, vfs, JobLedger())
        keys = SigningKeys(context, Prefs(context))
    }

    @After fun tearDown() {
        if (::tmp.isInitialized) tmp.deleteRecursively()
    }

    @Test fun the_fixture_inspects_as_the_app_it_is() {
        val info = runBlocking { tools.inspect(apk) }
        assertEquals("dev.niccc2007.fixture", info.packageName)
        assertTrue("dex entries: ${info.dexEntries}", info.dexEntries.contains("classes.dex"))
        assertTrue("classes: ${info.classCount}", info.classCount >= 2)
        assertNotNull("an APK that installs is a signed APK", info.signerSha256)
    }

    /** The whole gate, in one test, in the order a person would do it. */
    @Test fun decompile_edit_one_smali_line_rebuild_sign() {
        val work = File(tmp, "work")

        // 1. decompile
        runBlocking { tools.decompile(apk, work) }
        val smali = File(work, "smali/dev/niccc2007/fixture/Fixture.smali")
        assertTrue("expected a smali tree at ${work.absolutePath}: ${work.list()?.toList()}", smali.isFile)

        // 2. edit exactly one line
        val before = smali.readText()
        assertTrue("the constant must survive into smali", before.contains("\"ORIGINAL\""))
        val after = before.replace("\"ORIGINAL\"", "\"PATCHED-BY-FILET\"")
        assertEquals(
            "exactly one line should differ",
            1,
            before.lines().zip(after.lines()).count { (a, b) -> a != b },
        )
        smali.writeText(after)

        // 3. rebuild + 4. re-sign, with a key generated on this device
        val alias = "filet-test-" + System.nanoTime()
        val key = keys.generate(alias, "Filet Test")
        // minSdk deliberately NOT passed: reading it from the APK is the behaviour that
        // matters, and it is what decides the dex format version.
        val out = runBlocking { tools.rebuildAndSign(apk, work, keys, key) }
        runCatching { keys.deleteAndroidKey(alias) }

        val signed = File(out.path)
        assertTrue("no output APK at ${out.path}", signed.isFile)
        assertTrue("the rebuilt APK is suspiciously empty: ${signed.length()}", signed.length() > 2_000)

        // The edit is in the shipped dex, and the result is signed - checked by re-reading the
        // output through the same inspector a user would use, not by trusting the writer.
        val info = runBlocking { tools.inspect(out) }
        assertEquals("dev.niccc2007.fixture", info.packageName)
        assertNotNull("rebuilt APK must carry a signature", info.signerSha256)
        assertTrue(
            "the signer must be the new key, not the fixture's original",
            info.signerSubject?.contains("Filet Test") == true,
        )
        // Read the dex ENTRY, inflated. Scanning the APK's raw bytes finds nothing, because
        // classes.dex is deflate-compressed inside the zip - a false negative that looks
        // exactly like a broken rebuild.
        val dexBytes = java.util.zip.ZipFile(signed).use { zip ->
            zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() }
        }
        val dexText = dexBytes.toString(Charsets.ISO_8859_1)
        assertTrue("the edited constant is missing from the rebuilt dex", dexText.contains("PATCHED-BY-FILET"))
        assertTrue("the old constant is still in the rebuilt dex", !dexText.contains("ORIGINAL"))

        // The dex FORMAT VERSION, which is the difference between an APK that runs and one
        // that installs and then dies. smali writes the newest format its api level allows,
        // so assembling at the tool's reading level produced DEX 041 - unloadable on anything
        // below API 35, which is to say on the phone this test runs on.
        val version = dexBytes.copyOfRange(4, 7).toString(Charsets.US_ASCII)
        assertTrue(
            "rebuilt dex is version $version; this device is API ${android.os.Build.VERSION.SDK_INT}",
            version.toInt() <= dexVersionFor(android.os.Build.VERSION.SDK_INT),
        )

        // And ART itself agrees: loading the dex is the only check that cannot be argued with.
        //
        // It has to be READ-ONLY first, and it has to be on a filesystem where that MEANS
        // something. ART refuses to open a dex out of a file the calling app can still write
        // ("Writable dex file ... is not allowed") because a writable dex is a code-injection
        // hole. Two traps, both hit here:
        //
        //  1. The APK we just built lives in our own cache dir and is writable by definition.
        //  2. `setReadOnly()` on the shared volume is a no-op. `Gates.dir()` is under
        //     /storage/emulated/0, which is a FUSE mount with synthetic permissions - chmod
        //     returns success and changes nothing, so the load failed again in exactly the
        //     same way and looked like the first fix not having been applied.
        //
        // So the sealed copy goes in app-private storage, which is real ext4.
        val sealed = File(tmp, "fixture-verify.apk")
        sealed.delete()
        signed.copyTo(sealed)
        check(sealed.setReadOnly() && !sealed.canWrite()) {
            "could not make ${sealed.name} read-only; ART will refuse to load it"
        }
        val loader = dalvik.system.PathClassLoader(sealed.absolutePath, null, javaClass.classLoader)
        val loaded = loader.loadClass("dev.niccc2007.fixture.Fixture")
        assertEquals(
            "PATCHED-BY-FILET",
            loaded.getMethod("tag").invoke(null),
        )

        // Leave it where the host can pick it up and actually install it.
        val handoff = File(outDir, "fixture-patched.apk")
        handoff.delete()
        signed.copyTo(handoff)   // never overwrite=true: see Gates.write
        android.util.Log.i("FiletApkGate", "patched APK at ${handoff.absolutePath} (${handoff.length()} bytes)")
    }

    /** Re-signing without editing must also produce something installable. */
    @Test fun an_untouched_apk_can_be_re_signed() {
        val alias = "filet-test-" + System.nanoTime()
        val key = keys.generate(alias, "Filet Resign")
        val out = runBlocking { tools.signOnly(apk, keys, key, minSdk = 26) }
        runCatching { keys.deleteAndroidKey(alias) }
        val info = runBlocking { tools.inspect(out) }
        assertTrue(info.signerSubject?.contains("Filet Resign") == true)
    }

    /** The newest dex format a given Android release can load. */
    private fun dexVersionFor(sdk: Int): Int = when {
        sdk >= 35 -> 41
        sdk >= 28 -> 39
        sdk >= 26 -> 38
        else -> 37
    }


}
