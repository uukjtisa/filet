package dev.niccc2007.filet.script

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.LocalProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The script sandbox, tested from the attacker's side.
 *
 * These are the tests that matter most in the project: a script runner reachable by intent is
 * remote code execution, and every one of these cases is a way a real script would try to get
 * out of its declared permissions.
 */
class ScriptSandboxTest {

    private lateinit var tmp: File
    private lateinit var allowed: File
    private lateinit var secret: File
    private lateinit var vfs: Vfs
    private lateinit var engine: ScriptEngine

    private fun vpath(f: File) = VPath.of("local", f.absolutePath.replace(File.separatorChar, '/'))

    @Before fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "filet-script-" + System.nanoTime())
        allowed = File(tmp, "allowed").apply { mkdirs() }
        secret = File(tmp, "secret").apply { mkdirs() }
        File(allowed, "note.txt").writeText("hello")
        File(secret, "passwords.txt").writeText("hunter2")
        vfs = Vfs(listOf(LocalProvider(listOf(tmp))))
        engine = ScriptEngine(vfs)
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun perms(read: List<File> = listOf(allowed), write: List<File> = emptyList()) =
        ScriptPermissions(
            read = read.map { vpath(it) },
            write = write.map { vpath(it) },
            timeoutMs = 5_000,
        )

    // ── the sandbox holds ──

    @Test fun a_script_can_read_what_it_was_granted() = runTest {
        val r = engine.run(
            "print(fs.read('${vpath(File(allowed, "note.txt"))}'))",
            perms(),
        )
        assertTrue(r.error ?: "", r.ok)
        assertEquals("hello", r.output.trim())
    }

    @Test fun a_script_cannot_read_outside_its_grant() = runTest {
        val r = engine.run(
            "print(fs.read('${vpath(File(secret, "passwords.txt"))}'))",
            perms(),
        )
        assertFalse(r.ok)
        assertTrue(r.error ?: "", r.error!!.contains("Denied"))
        assertFalse(r.output.contains("hunter2"))
    }

    @Test fun a_read_grant_does_not_imply_a_write_grant() = runTest {
        val r = engine.run(
            "fs.write('${vpath(File(allowed, "new.txt"))}', 'x')",
            perms(read = listOf(allowed), write = emptyList()),
        )
        assertFalse(r.ok)
        assertFalse(File(allowed, "new.txt").exists())
    }

    @Test fun a_write_grant_works_where_it_is_given() = runTest {
        val r = engine.run(
            "fs.write('${vpath(File(allowed, "new.txt"))}', 'written')",
            perms(write = listOf(allowed)),
        )
        assertTrue(r.error ?: "", r.ok)
        assertEquals("written", File(allowed, "new.txt").readText())
    }

    /**
     * A move removes the source, so it is a write on BOTH sides. Checking only the
     * destination would let a read-only grant delete the file it was allowed to read.
     */
    @Test fun a_move_out_of_a_read_only_grant_is_refused() = runTest {
        val r = engine.run(
            "fs.move('${vpath(File(allowed, "note.txt"))}', '${vpath(secret)}')",
            perms(read = listOf(allowed), write = listOf(secret)),
        )
        assertFalse(r.ok)
        assertTrue(File(allowed, "note.txt").exists())
    }

    @Test fun listing_a_forbidden_directory_is_refused() = runTest {
        val r = engine.run("print(#fs.list('${vpath(secret)}'))", perms())
        assertFalse(r.ok)
    }

    @Test fun a_traversal_in_a_path_cannot_escape() = runTest {
        // VPath normalises "..", so the script is asking for the secret folder by its real
        // name by the time the guard sees it - and the guard refuses it.
        val sneaky = vpath(allowed).path + "/../secret/passwords.txt"
        val r = engine.run("print(fs.read('local://$sneaky'))", perms())
        assertFalse(r.ok)
        assertFalse(r.output.contains("hunter2"))
    }

    // ── the escape hatches are closed ──

    @Test fun io_is_removed() = runTest {
        val r = engine.run("local f = io.open('${vpath(File(secret, "passwords.txt"))}')", perms())
        assertFalse("io.open must not exist: it bypasses the guard entirely", r.ok)
    }

    @Test fun os_execute_is_removed() = runTest {
        val r = engine.run("os.execute('id')", perms())
        assertFalse(r.ok)
    }

    @Test fun luajava_reflection_is_removed() = runTest {
        val r = engine.run("local c = luajava.bindClass('java.io.File')", perms())
        assertFalse("luajava reaches any class in the process, including the raw Vfs", r.ok)
    }

    @Test fun loadfile_is_removed() = runTest {
        val r = engine.run("loadfile('/etc/hosts')", perms())
        assertFalse(r.ok)
    }

    @Test fun a_runaway_loop_is_stopped_by_the_timeout() = runTest {
        val r = engine.run(
            "while true do end",
            ScriptPermissions(read = listOf(vpath(allowed)), timeoutMs = 1_000),
        )
        assertFalse(r.ok)
        assertTrue(r.error ?: "", r.error!!.contains("Stopped"))
    }

    // ── the declaration header ──

    @Test fun the_permission_header_parses() {
        val (name, p) = ScriptPermissions.parse(
            """
            -- @name    Tidy up
            -- @read    local:///a
            -- @write   local:///b
            -- @network true
            -- @timeout 45

            print("hi")
            """.trimIndent()
        )
        assertEquals("Tidy up", name)
        assertEquals(listOf(VPath.of("local", "/a")), p.read)
        assertEquals(listOf(VPath.of("local", "/b")), p.write)
        assertTrue(p.network)
        assertEquals(45_000L, p.timeoutMs)
    }

    @Test fun a_script_with_no_header_gets_nothing() {
        val (_, p) = ScriptPermissions.parse("print('hi')")
        assertTrue(p.isEmpty)
    }

    @Test fun the_header_stops_at_the_first_statement() {
        val (_, p) = ScriptPermissions.parse(
            "-- @read local:///a\nprint('hi')\n-- @write local:///b\n"
        )
        assertEquals(1, p.read.size)
        assertTrue("a declaration after real code must not count", p.write.isEmpty())
    }

    /** Approval is bound to the exact source, so editing a script asks again. */
    @Test fun the_fingerprint_changes_with_the_source() {
        val a = ScriptPermissions.fingerprint("print('a')")
        val b = ScriptPermissions.fingerprint("print('b')")
        assertEquals(a, ScriptPermissions.fingerprint("print('a')"))
        assertTrue(a != b)
    }

    @Test fun the_description_names_every_grant_in_plain_words() {
        val lines = ScriptPermissions(
            read = listOf(VPath.of("local", "/a")),
            write = listOf(VPath.of("local", "/b")),
            network = true,
        ).describe()
        assertTrue(lines.any { it.contains("/a") })
        assertTrue(lines.any { it.contains("/b") })
        assertTrue(lines.any { it.contains("network") })
    }
}
