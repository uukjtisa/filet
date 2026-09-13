package dev.niccc2007.filet.script

import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform

data class ScriptResult(
    val ok: Boolean,
    val output: String,
    val error: String? = null,
    val elapsedMs: Long = 0,
)

/**
 * Lua on LuaJ, bound to the VFS.
 *
 * > Scripts bind to the VFS, not to `java.io.File` (PLAN.md L4).
 *
 * Do that and a script works over SMB, inside an archive and through root for free. Bind it
 * to raw `File` and we have rebuilt the thing this project exists to replace.
 *
 * Three things are deliberately **removed** from the standard globals: `io`, `os.execute` and
 * `package.loadlib`. Each is a complete bypass of [GuardedVfs], and leaving any of them in
 * would make the permission model decorative.
 */
class ScriptEngine(private val vfs: Vfs) {

    suspend fun run(
        source: String,
        permissions: ScriptPermissions,
        args: List<String> = emptyList(),
    ): ScriptResult = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        val guarded = GuardedVfs(vfs, permissions)
        val started = System.currentTimeMillis()

        val deadline = started + permissions.timeoutMs

        // Two timeouts, because they catch different things. The deadline hook stops a
        // runaway Lua loop - `withTimeoutOrNull` alone cannot, since a tight `while true do
        // end` never suspends and never checks cancellation. The coroutine timeout still
        // covers the blocking IO a script can start inside one call.
        val result = withTimeoutOrNull(permissions.timeoutMs + 2_000) {
            runCatching {
                val globals = sandbox(guarded, out, args, deadline)
                val chunk = globals.load(source, "filet-script")
                chunk.call()
            }
        }

        val elapsed = System.currentTimeMillis() - started
        val timedOut = result == null ||
            (result.exceptionOrNull()?.message?.contains(TIMEOUT_MARKER) == true)
        when {
            timedOut -> ScriptResult(
                ok = false,
                output = out.toString(),
                error = "Stopped after ${permissions.timeoutMs / 1000}s. The script did not finish on its own.",
                elapsedMs = elapsed,
            )
            result!!.isFailure -> ScriptResult(
                ok = false,
                output = out.toString(),
                error = readable(result.exceptionOrNull()!!),
                elapsedMs = elapsed,
            )
            else -> ScriptResult(true, out.toString(), null, elapsed)
        }
    }

    private fun readable(t: Throwable): String = when (t) {
        is ScriptDenied -> "Denied: the script tried to ${t.what} ${t.path.path}, which it was not granted."
        is LuaError -> t.message ?: "Lua error"
        is SecurityException -> t.message ?: "Denied"
        else -> t.message ?: t::class.simpleName ?: "failed"
    }

    private fun sandbox(
        guarded: GuardedVfs,
        out: StringBuilder,
        args: List<String>,
        deadline: Long,
    ): Globals {
        val g: Globals = JsePlatform.standardGlobals()

        // The instruction hook is what makes the time limit real. Installing DebugLib routes
        // every VM instruction through onInstruction; the `debug` table itself is then hidden,
        // because `debug.sethook` would let a script remove its own leash.
        g.load(Deadline(deadline))
        g.set("debug", LuaValue.NIL)

        // Remove every route to the filesystem that bypasses GuardedVfs. Without this the
        // permission model is theatre: `io.open` alone would defeat all of it.
        g.set("io", LuaValue.NIL)
        g.set("require", LuaValue.NIL)
        g.set("dofile", LuaValue.NIL)
        g.set("loadfile", LuaValue.NIL)
        g.get("os").let { os ->
            if (!os.isnil()) {
                os.set("execute", LuaValue.NIL)
                os.set("remove", LuaValue.NIL)
                os.set("rename", LuaValue.NIL)
                os.set("tmpname", LuaValue.NIL)
                os.set("exit", LuaValue.NIL)
                os.set("getenv", LuaValue.NIL)
            }
        }
        g.get("package").let { p -> if (!p.isnil()) p.set("loadlib", LuaValue.NIL) }
        // luajava is LuaJ's reflection bridge: with it, a script can reach any Java class in
        // the process, including the unguarded Vfs. It is the single largest hole here.
        g.set("luajava", LuaValue.NIL)

        g.set("print", object : VarArgFunction() {
            override fun invoke(varargs: Varargs): Varargs {
                for (i in 1..varargs.narg()) {
                    if (i > 1) out.append('\t')
                    out.append(varargs.arg(i).tojstring())
                }
                out.append('\n')
                if (out.length > OUTPUT_CAP) out.setLength(OUTPUT_CAP)
                return LuaValue.NONE
            }
        })

        val argTable = LuaTable()
        args.forEachIndexed { i, a -> argTable.set(i + 1, LuaValue.valueOf(a)) }
        g.set("args", argTable)

        g.set("fs", fsLibrary(guarded))
        return g
    }

    /**
     * The `fs` table: the only door a script has to storage.
     *
     * Every entry goes through [GuardedVfs], so the permission check cannot be skipped by
     * finding a different function. Paths are VPath strings (`local:///storage/...`), which
     * also means a script written against internal storage runs unchanged against an SMB
     * share once the user grants it.
     */
    private fun fsLibrary(vfs: GuardedVfs): LuaTable {
        val t = LuaTable()

        t.set("list", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val path = vpath(arg)
                val rows = runBlocking { vfs.list(path) }
                val out = LuaTable()
                rows.forEachIndexed { i, n ->
                    val row = LuaTable()
                    row.set("name", LuaValue.valueOf(n.name))
                    row.set("path", LuaValue.valueOf(n.path.toString()))
                    row.set("dir", LuaValue.valueOf(n.isDir))
                    row.set("size", LuaValue.valueOf(n.size.toDouble()))
                    row.set("mtime", LuaValue.valueOf(n.mtime.toDouble()))
                    row.set("ext", LuaValue.valueOf(n.extension))
                    out.set(i + 1, row)
                }
                return out
            }
        })

        t.set("exists", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue =
                LuaValue.valueOf(runBlocking { vfs.stat(vpath(arg)) } != null)
        })

        t.set("isdir", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue =
                LuaValue.valueOf(runBlocking { vfs.stat(vpath(arg)) }?.isDir == true)
        })

        t.set("size", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue =
                LuaValue.valueOf((runBlocking { vfs.stat(vpath(arg)) }?.size ?: -1L).toDouble())
        })

        t.set("read", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val bytes = runBlocking { vfs.openRead(vpath(arg)).use { it.readBytes() } }
                // A text read is capped: a script asking for a 3 GB file as a string is a bug
                // that would take the whole process down with an OOM.
                if (bytes.size > READ_CAP) throw LuaError("file is too large to read as text")
                return LuaValue.valueOf(String(bytes, Charsets.UTF_8))
            }
        })

        t.set("write", object : TwoArgFunction() {
            override fun call(path: LuaValue, text: LuaValue): LuaValue {
                runBlocking {
                    vfs.openWrite(vpath(path)).use { it.write(text.tojstring().toByteArray(Charsets.UTF_8)) }
                }
                return LuaValue.TRUE
            }
        })

        t.set("append", object : TwoArgFunction() {
            override fun call(path: LuaValue, text: LuaValue): LuaValue {
                runBlocking {
                    vfs.openWrite(vpath(path), append = true)
                        .use { it.write(text.tojstring().toByteArray(Charsets.UTF_8)) }
                }
                return LuaValue.TRUE
            }
        })

        t.set("mkdir", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                runBlocking { vfs.create(vpath(arg), isDir = true) }
                return LuaValue.TRUE
            }
        })

        t.set("delete", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                runBlocking { vfs.delete(vpath(arg)) }
                return LuaValue.TRUE
            }
        })

        t.set("rename", object : TwoArgFunction() {
            override fun call(path: LuaValue, name: LuaValue): LuaValue {
                runBlocking { vfs.rename(vpath(path), name.tojstring()) }
                return LuaValue.TRUE
            }
        })

        t.set("copy", object : TwoArgFunction() {
            override fun call(from: LuaValue, into: LuaValue): LuaValue {
                runBlocking { vfs.copy(vpath(from), vpath(into)) }
                return LuaValue.TRUE
            }
        })

        t.set("move", object : TwoArgFunction() {
            override fun call(from: LuaValue, into: LuaValue): LuaValue {
                runBlocking { vfs.move(vpath(from), vpath(into)) }
                return LuaValue.TRUE
            }
        })

        t.set("join", object : TwoArgFunction() {
            override fun call(base: LuaValue, child: LuaValue): LuaValue =
                LuaValue.valueOf(vpath(base).child(child.tojstring()).toString())
        })

        t.set("parent", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue =
                vpath(arg).parent?.let { LuaValue.valueOf(it.toString()) } ?: LuaValue.NIL
        })

        t.set("name", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.valueOf(vpath(arg).name)
        })

        t.set("ext", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val n = vpath(arg).name
                val dot = n.lastIndexOf('.')
                return LuaValue.valueOf(if (dot <= 0) "" else n.substring(dot + 1).lowercase())
            }
        })

        t.set("walk", object : ThreeArgFunction() {
            override fun call(root: LuaValue, maxDepth: LuaValue, fn: LuaValue): LuaValue {
                val limit = if (maxDepth.isnil()) 8 else maxDepth.toint()
                var visited = 0
                fun recurse(p: VPath, depth: Int) {
                    if (depth > limit || visited > WALK_CAP) return
                    val rows = runCatching { runBlocking { vfs.list(p) } }.getOrNull() ?: return
                    for (n in rows) {
                        visited++
                        if (visited > WALK_CAP) return
                        val row = LuaTable()
                        row.set("name", LuaValue.valueOf(n.name))
                        row.set("path", LuaValue.valueOf(n.path.toString()))
                        row.set("dir", LuaValue.valueOf(n.isDir))
                        row.set("size", LuaValue.valueOf(n.size.toDouble()))
                        fn.call(row)
                        if (n.isDir) recurse(n.path, depth + 1)
                    }
                }
                recurse(vpath(root), 0)
                return LuaValue.valueOf(visited)
            }
        })

        return t
    }

    private fun vpath(v: LuaValue): VPath {
        val raw = v.tojstring()
        return runCatching { VPath.parse(raw) }.getOrElse {
            throw LuaError("not a Filet path: $raw (expected something like local:///storage/emulated/0/Download)")
        }
    }

    /**
     * Stops a script that will not stop itself.
     *
     * The clock is only read every 1024 instructions: checking on every one would dominate
     * the runtime of an ordinary script for no benefit, and 1024 instructions is far below
     * human-perceptible time.
     */
    private class Deadline(private val until: Long) : org.luaj.vm2.lib.DebugLib() {
        private var counter = 0
        override fun onInstruction(pc: Int, v: Varargs, top: Int) {
            super.onInstruction(pc, v, top)
            if ((++counter and 0x3FF) == 0 && System.currentTimeMillis() > until) {
                throw LuaError(TIMEOUT_MARKER)
            }
        }
    }

    private companion object {
        const val TIMEOUT_MARKER = "filet:script-timeout"
        const val OUTPUT_CAP = 256 * 1024
        const val READ_CAP = 8 * 1024 * 1024
        const val WALK_CAP = 50_000
    }
}
