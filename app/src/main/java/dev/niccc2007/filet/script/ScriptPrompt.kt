package dev.niccc2007.filet.script

/**
 * A briefing an AI can actually write a working Filet script from.
 *
 * The user copies this, appends what they want in plain words, and pastes the whole thing
 * into whatever assistant they use. It has to be complete, because the model has no other way
 * to learn this API: nothing about it is on the internet.
 *
 * So it states the *whole* surface, the parts that differ from stock Lua, and - most
 * importantly - the permission header, since a script that forgets it is refused before its
 * first line runs and the model would have no idea why.
 */
object ScriptPrompt {

    val TEXT: String = """
        You are writing a Lua script for **Filet**, an Android file manager. Read this whole
        brief before writing anything, then write ONE script that does what I ask at the end.

        ## How a Filet script is shaped

        Every script starts with a permission header of `--` comments. Filet reads it, shows
        it to me as plain English, and I approve it before the script runs. A path the header
        does not grant is refused at the storage layer, so the header is not documentation -
        it is the sandbox.

            -- @name    Sort downloads by type
            -- @read    local:///storage/emulated/0/Download
            -- @write   local:///storage/emulated/0/Download
            -- @network false
            -- @timeout 30

        * `@name` shows in the script list. One short line.
        * `@read` / `@write` may each appear several times, one path per line. `@write` also
          covers create, delete and rename inside that subtree. Reading is NOT implied by
          writing - grant both if the script needs both.
        * `@timeout` is seconds. The script is stopped when it expires, mid-loop if need be.
        * Grant the narrowest paths that do the job. `local:///storage/emulated/0` is the
          whole of internal storage and is almost never the right answer.

        ## Addressing

        Paths are `scheme:///path`, never bare. That is the point of the API: the same script
        works everywhere Filet can reach.

        * `local:///storage/emulated/0/Download` — internal storage
        * `zip:///storage/emulated/0/a.zip!/inner/file.txt` — inside an archive (read-only)
        * `apk:///storage/emulated/0/app.apk!/classes.dex` — inside an APK
        * `smb:///<connectionId>/share/file` and `dav:`, `sftp:`, `ftp:` — network shares
        * `saf:///…` — a folder granted through the system picker

        ## The `fs` table — this is the whole API

            fs.list(path)            -> array of { name, path, dir, size, mtime, ext }
            fs.exists(path)          -> boolean
            fs.isdir(path)           -> boolean
            fs.size(path)            -> number of bytes, -1 if unknown
            fs.read(path)            -> the file's contents as a string
            fs.write(path, text)     -> replaces the file, creating it if needed
            fs.append(path, text)
            fs.mkdir(path)
            fs.delete(path)          -> recursive for a folder
            fs.rename(path, newName) -> name only, not a path
            fs.copy(from, intoDir)
            fs.move(from, intoDir)
            fs.join(dir, name)       -> a path, with the separator handled
            fs.parent(path)
            fs.name(path)            -> the last segment
            fs.ext(path)             -> lowercase extension, no dot, "" if none
            fs.walk(root, depth, fn) -> calls fn(entry) for everything under root

        Other globals: `args` (an array of strings the caller passed) and `print(...)`, whose
        output I see after the run.

        ## What is NOT available, and do not try to work around it

        `io`, `os.execute`, `os.remove`, `os.rename`, `os.exit`, `os.getenv`, `require`,
        `dofile`, `loadfile`, `package.loadlib`, `luajava` and `debug` are all removed. They
        are removed because each one is a way out of the sandbox. Use `fs` for everything that
        touches storage. `os.time`, `os.date` and `os.clock` are still there. The rest of
        standard Lua - `string`, `table`, `math`, `pcall` - works normally.

        ## House rules

        * Never delete anything I did not clearly ask you to delete. When in doubt, move it to
          a folder and `print` what you did.
        * `print` a one-line summary at the end - how many files, what happened. A script that
          runs silently is a script I cannot trust.
        * Wrap anything that can fail per-file in `pcall` so one bad file does not abandon the
          rest, and print what was skipped.
        * Do not write a loop that cannot finish. The timeout will stop it, but that is a
          failure, not a design.
        * Output ONLY the script, header included, with no surrounding prose or code fences.

        ## What I want

    """.trimIndent() + "\n\n"
}
