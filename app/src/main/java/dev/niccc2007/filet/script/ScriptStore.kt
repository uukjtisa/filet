package dev.niccc2007.filet.script

import android.content.Context
import dev.niccc2007.filet.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class Script(
    val id: String,
    val name: String,
    val source: String,
    val permissions: ScriptPermissions,
    val fingerprint: String,
    val modifiedAt: Long,
)

/**
 * Scripts on disk, plus the record of which exact versions the user approved.
 *
 * Approval is keyed on the **fingerprint of the source**, not on the script's name or path.
 * Editing a script - or an intent quietly replacing one - produces a different fingerprint
 * and therefore needs fresh consent. That is the whole defence against "approve once, run
 * anything forever", and it is why [ScriptPermissions.fingerprint] exists.
 */
class ScriptStore(
    context: Context,
    private val prefs: Prefs,
    private val files: dev.niccc2007.filet.data.AppFiles = dev.niccc2007.filet.data.AppFiles(context),
) {

    private val _scripts = MutableStateFlow(load())
    val scripts: StateFlow<List<Script>> = _scripts.asStateFlow()

    fun reload() { _scripts.value = load() }

    fun save(id: String?, source: String): Script {
        val (declaredName, perms) = ScriptPermissions.parse(source)
        val fileId = id ?: "s" + System.currentTimeMillis()
        files.write(files.scriptFile(fileId), source)
        val script = Script(
            id = fileId,
            name = declaredName.ifEmpty { fileId },
            source = source,
            permissions = perms,
            fingerprint = ScriptPermissions.fingerprint(source),
            modifiedAt = System.currentTimeMillis(),
        )
        _scripts.value = _scripts.value.filterNot { it.id == fileId } + script
        return script
    }

    fun delete(id: String) {
        files.delete(files.scriptFile(id))
        _scripts.value = _scripts.value.filterNot { it.id == id }
        revoke(id)
    }

    /** True when this exact source has already been approved with these exact permissions. */
    fun isApproved(script: Script): Boolean =
        prefs.getString(approvalKey(script.id)) == script.fingerprint

    fun approve(script: Script) = prefs.putString(approvalKey(script.id), script.fingerprint)

    fun revoke(id: String) = prefs.putString(approvalKey(id), null)

    private fun approvalKey(id: String) = "script.approved.$id"

    private fun load(): List<Script> =
        files.listLua().mapNotNull { f ->
            runCatching {
                val source = files.read(f)
                val (name, perms) = ScriptPermissions.parse(source)
                val id = files.nameWithoutExtension(f)
                Script(
                    id = id,
                    name = name.ifEmpty { id },
                    source = source,
                    permissions = perms,
                    fingerprint = ScriptPermissions.fingerprint(source),
                    modifiedAt = files.lastModified(f),
                )
            }.getOrNull()
        }.sortedBy { it.name.lowercase() }

    /**
     * The examples a new install starts with.
     *
     * Chosen to teach the shape rather than to be impressive: each one is short, declares its
     * permissions honestly, and does something a person actually wants.
     */
    fun seedExamples() {
        if (prefs.getBool(SEEDED, false)) return
        prefs.putBool(SEEDED, true)
        EXAMPLES.forEach { save(null, it) }
    }

    /** Re-add the bundled examples, skipping any still present under the same name. */
    fun restoreExamples() {
        val have = _scripts.value.mapTo(HashSet()) { it.name }
        EXAMPLES.forEach { source ->
            val name = ScriptPermissions.parse(source).first
            if (name !in have) save(null, source)
        }
    }

    private companion object {
        const val SEEDED = "scripts.seeded"

        val EXAMPLES = listOf(
            """
            -- @name    Sort downloads by type
            -- @read    local:///storage/emulated/0/Download
            -- @write   local:///storage/emulated/0/Download
            -- @timeout 30
            --
            -- Files land in Download as a heap. This puts each one in a folder named after
            -- its extension, and leaves folders alone.

            local root = "local:///storage/emulated/0/Download"
            local moved = 0

            for _, item in ipairs(fs.list(root)) do
              if not item.dir and item.ext ~= "" then
                local bucket = fs.join(root, item.ext)
                if not fs.exists(bucket) then fs.mkdir(bucket) end
                fs.move(item.path, bucket)
                moved = moved + 1
              end
            end

            print("moved " .. moved .. " file(s)")
            """.trimIndent(),

            """
            -- @name    Find big files
            -- @read    local:///storage/emulated/0
            -- @timeout 60
            --
            -- Walks your storage and prints anything over 100 MB, largest first.

            local found = {}

            fs.walk("local:///storage/emulated/0", 6, function(item)
              if not item.dir and item.size > 100 * 1024 * 1024 then
                table.insert(found, item)
              end
            end)

            table.sort(found, function(a, b) return a.size > b.size end)

            for i, item in ipairs(found) do
              if i > 40 then break end
              print(string.format("%6.1f MB  %s", item.size / 1048576, item.path))
            end

            if #found == 0 then print("nothing over 100 MB") end
            """.trimIndent(),

            """
            -- @name    Strip empty folders
            -- @read    local:///storage/emulated/0/Download
            -- @write   local:///storage/emulated/0/Download
            --
            -- Removes folders with nothing in them. One pass, so a folder that only
            -- contained empty folders needs a second run - deliberately, so nothing
            -- disappears in a cascade you did not watch.

            local root = "local:///storage/emulated/0/Download"
            local removed = 0

            for _, item in ipairs(fs.list(root)) do
              if item.dir and #fs.list(item.path) == 0 then
                fs.delete(item.path)
                removed = removed + 1
                print("removed " .. item.name)
              end
            end

            print(removed .. " empty folder(s) removed")
            """.trimIndent(),

            """
            -- @name    Date-stamp camera photos
            -- @read    local:///storage/emulated/0/DCIM/Camera
            -- @write   local:///storage/emulated/0/DCIM/Camera
            -- @timeout 60
            --
            -- Files arrive as IMG_0042.jpg and sort into nonsense once two cameras are
            -- involved. This renames each one to its own capture date, keeping the original
            -- name on the end so nothing becomes unrecognisable.

            local root = "local:///storage/emulated/0/DCIM/Camera"
            local renamed, skipped = 0, 0

            for _, item in ipairs(fs.list(root)) do
              if not item.dir and item.mtime > 0 then
                local stamp = os.date("%Y-%m-%d", item.mtime // 1000)
                -- Already stamped: leave it, so running twice is harmless.
                if not string.match(item.name, "^%d%d%d%d%-%d%d%-%d%d ") then
                  local ok = pcall(function()
                    fs.rename(item.path, stamp .. " " .. item.name)
                  end)
                  if ok then renamed = renamed + 1 else skipped = skipped + 1 end
                end
              end
            end

            print("renamed " .. renamed .. ", skipped " .. skipped)
            """.trimIndent(),

            """
            -- @name    Find duplicate names
            -- @read    local:///storage/emulated/0
            -- @timeout 120
            --
            -- Reports files whose NAME appears more than once anywhere under internal
            -- storage. Deletes nothing - it prints, and you decide.
            --
            -- Same name is not the same file. For byte-identical duplicates use the `dup:`
            -- search in the app, which hashes rather than guesses.

            local seen = {}
            local dupes = 0

            fs.walk("local:///storage/emulated/0", 6, function(item)
              if not item.dir then
                local list = seen[item.name]
                if list == nil then
                  seen[item.name] = { item.path }
                else
                  list[#list + 1] = item.path
                  if #list == 2 then dupes = dupes + 1 end
                end
              end
            end)

            for name, paths in pairs(seen) do
              if #paths > 1 then
                print(name .. "  x" .. #paths)
                for _, p in ipairs(paths) do print("   " .. p) end
              end
            end
            print(dupes .. " name(s) appear more than once")
            """.trimIndent(),

            """
            -- @name    Unpack an archive listing
            -- @read    local:///storage/emulated/0/Download
            -- @write   local:///storage/emulated/0/Download
            -- @timeout 60
            --
            -- Writes a .txt beside every zip in Download listing what is inside it, WITHOUT
            -- extracting anything. This is the whole point of the VFS: `zip:///...!/` is a
            -- directory as far as a script is concerned, so no unzip step exists.

            local root = "local:///storage/emulated/0/Download"
            local done = 0

            for _, item in ipairs(fs.list(root)) do
              if not item.dir and item.ext == "zip" then
                local inside = "zip://" .. string.sub(item.path, string.len("local://") + 1) .. "!/"
                local ok, entries = pcall(fs.list, inside)
                if ok then
                  local lines = {}
                  for _, e in ipairs(entries) do
                    lines[#lines + 1] = (e.dir and "[dir] " or "      ") .. e.name
                  end
                  fs.write(fs.join(root, item.name .. ".contents.txt"), table.concat(lines, "\n"))
                  done = done + 1
                else
                  print("could not read " .. item.name)
                end
              end
            end

            print("listed " .. done .. " archive(s)")
            """.trimIndent(),

            """
            -- @name    Tidy screenshots by month
            -- @read    local:///storage/emulated/0/Pictures/Screenshots
            -- @write   local:///storage/emulated/0/Pictures/Screenshots
            -- @timeout 60
            --
            -- Screenshots accumulate faster than anything else on a phone. This files them
            -- into YYYY-MM folders, which is the granularity you actually search at.

            local root = "local:///storage/emulated/0/Pictures/Screenshots"
            if not fs.exists(root) then
              print("no Screenshots folder on this device")
              return
            end

            local moved = 0
            for _, item in ipairs(fs.list(root)) do
              if not item.dir and item.mtime > 0 then
                local bucket = fs.join(root, os.date("%Y-%m", item.mtime // 1000))
                if not fs.exists(bucket) then fs.mkdir(bucket) end
                local ok = pcall(fs.move, item.path, bucket)
                if ok then moved = moved + 1 end
              end
            end

            print("filed " .. moved .. " screenshot(s)")
            """.trimIndent(),
        )
    }
}
