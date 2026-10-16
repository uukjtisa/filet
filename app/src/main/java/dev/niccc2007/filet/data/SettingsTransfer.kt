package dev.niccc2007.filet.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Carrying a Filet setup to another phone.
 *
 * ## Why this is not "dump the preferences and read them back"
 *
 * Most of what is in the preference store must NOT travel, and the reasons are different
 * enough that a denylist written once and forgotten would be wrong within a release:
 *
 *  - **Device identity.** The WebDAV code and the beacon identify *this* phone to other
 *    Filets. Two phones claiming one identity is not a cosmetic fault; it is two machines
 *    answering to the same name on the network.
 *  - **Session state.** Open tabs, recents, the last update check, every `firstSeen` flag.
 *    Restoring these would reopen somebody else's tabs and re-show, or re-suppress, first-run
 *    help on a phone that has never run the app.
 *  - **Grants that cannot be given.** A SAF `content://` path is a permission token issued to
 *    one install on one device. It cannot be transferred, and a tracked folder restored from
 *    one is an entry that will never resolve - which is exactly how dead rows get into a
 *    tracked list and stay there.
 *  - **Secrets.** Remote passwords are stored encrypted under a key held in the Android
 *    keystore, which never leaves the phone. Copying the stored string across transfers
 *    ciphertext nobody can read. Transferring a usable password means writing the PLAINTEXT
 *    into the file, which is a decision for the person exporting and not a default.
 *
 * So the transfer is an allowlist by section, with everything else refused and the refusals
 * explained. [Section] is the whole contract.
 *
 * ## Why every value is a string with a type letter on the front
 *
 * `SharedPreferences` is typed per key, and reading a key with the wrong getter throws. JSON
 * does not distinguish an Int from a Long, so a value exported as `18080` and read back as an
 * `Integer` would be written with `putInt` - and the next `getLong` on it throws
 * `ClassCastException` at some unrelated moment later, which is about the worst shape a bug
 * can take.
 *
 * So a value travels as `"i:18080"`, `"b:true"`, `"s:dark"`. It stays readable and diffable,
 * the type survives the trip, and nothing here has to keep a table of which key is which type
 * - the value carries its own.
 *
 * ## Why unknown keys are kept on import rather than refused
 *
 * A file written by a newer Filet will carry keys this build has never heard of. Refusing the
 * whole import over one is the behaviour that makes people stop using the feature; the keys
 * are simply not applied, and the count is reported. The reverse - an older file missing keys
 * - needs nothing: an absent preference is its default.
 */
object SettingsTransfer {

    /** Bumped only when the SHAPE changes, not when a key is added to a section. */
    const val FORMAT = 1

    /** What the file calls itself, so a wrong file is refused before anything is applied. */
    const val MAGIC = "filet.settings"

    /**
     * One thing you can choose to take with you.
     *
     * Each section is a group somebody would want independently: a new phone usually wants the
     * look and the openers, often wants the tracked folders, and may or may not want the
     * network drives and the shares.
     */
    enum class Section(
        val id: String,
        val label: String,
        /** What it is, in the words the export screen uses. */
        val about: String,
        /** Ticked when the chooser opens. */
        val onByDefault: Boolean,
        val keys: List<String>,
    ) {
        LOOK(
            "look", "Appearance and layout",
            "Theme, accent, density, tab size, the bottom bar and the selection bar.",
            true,
            listOf(
                "ui.theme", "ui.accent", "browse.viewStep", "browse.tabSize",
                "browse.bottomBar", "browse.selectionBar", "browse.selectionStyle",
                "browse.navWhileSelecting", "browse.split", "browse.restoreTabs",
                "entry.subtitle", "entry.measure", "entry.dateColumn", "entry.dateStyle",
                "entry.dateOrder", "entry.dateSeparator", "entry.relativeDates",
            ),
        ),

        BEHAVIOUR(
            "behaviour", "Sorting and behaviour",
            "Sort order, hidden files, drag between panes, the index, and how media plays.",
            true,
            listOf(
                "sort.key", "sort.desc", "sort.foldersFirst", "browse.hidden",
                "drag.behaviour", "index.enabled",
                "video.keepsPlaying", "play.uninterrupted", "play.face", "play.pitch",
                "play.glow", "play.glowLevel", "play.glowStrength", "play.glowSpill",
                "play.discTurnMs",
                "panes.swipe", "panes.swipeAt",
                "home.folds", "drawer.storage", "drawer.recent", "drawer.places",
                "view.tree", "play.scratch", "play.pulse",
                "play.pulseSense", "play.pulseDepth", "play.pulseLead",
                "home.hideStorage", "home.hideTermux", "home.folder",
                "update.notificationsOff", "update.notesFraction",
            ),
        ),

        OPENERS(
            "openers", "Default openers",
            "Which viewer opens which extension, including the ones you added yourself.",
            true,
            listOf("handlers.v1", "handlers.external.v1", "openers.customExtensions"),
        ),

        TRACKED(
            "tracked", "Tracked folders",
            "The folders Home watches. Folders granted through the system picker cannot come " +
                "with you - Android ties that permission to one install.",
            true,
            listOf("tracked.folders"),
        ),

        BOOKMARKS(
            "bookmarks", "Bookmarks",
            "Your saved places. Ones on a network drive need that drive to come too.",
            true,
            listOf("bookmarks"),
        ),

        REMOTES(
            "remotes", "Network drives",
            "Saved connections: addresses, ports, shares. Passwords are left out unless you " +
                "ask for them.",
            true,
            // Not a preference key: the connections live in their own store and are written
            // into the file as a section of their own. Listed here so the chooser can offer it.
            emptyList(),
        ),

        SHARING(
            "sharing", "What this phone shares",
            "The folders you host over the network, and the port. Off by default: another " +
                "phone does not have these folders, and it should not start sharing on its own.",
            false,
            listOf("dav.port", "dav.shares", "nearby.shares", "nearby.uploads", "nearby.idle"),
        );

        companion object {
            fun of(id: String): Section? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * Keys that are never exported, with the reason, because a denylist without reasons rots.
     *
     * This is belt and braces over the allowlist above - nothing reaches a file unless a
     * section names it - but it is also the documentation of what was deliberately left out,
     * which is the thing somebody will come looking for.
     */
    val NEVER: Map<String, String> = mapOf(
        "dav.code" to "identifies this phone to other Filets; two phones must not share one",
        "nearby.pin" to "this phone's pairing pin",
        "nearby.grants" to "permissions granted to specific other devices",
        "shortcuts.v1" to "home screen shortcuts belong to this phone's launcher",
        "tabs.v1" to "the tabs open right now",
        "recents" to "files opened on this phone",
        "media.sweptAt" to "when this install last swept media",
        "scripts.seeded" to "a one-time setup flag",
        "tracked.seeded" to "a one-time setup flag",
        "tracked.seededCommon" to "a one-time setup flag",
        "tracked.prunedRemotes" to "a one-time repair flag",
        "home.firstSeen" to "first-run help this phone has already seen",
        "update.lastCheckedAt" to "when this install last looked for an update",
        "update.silencedUntil" to "an update reminder silenced on this phone",
        "update.silencedVersion" to "an update reminder silenced on this phone",
        "update.skippedVersion" to "an update skipped on this phone",
    )

    /** Every key any section would carry, for an assertion that the two lists cannot overlap. */
    val EXPORTED: Set<String> = Section.entries.flatMap { it.keys }.toSet()

    /**
     * A preference value as it travels: one letter, a colon, then the text of the value.
     *
     * Null for a type no section exports - a string set, most likely - rather than guessing.
     * Nothing silently becomes a string, because a string set written back as a string is a
     * `ClassCastException` on the next read of that key.
     */
    fun encode(value: Any): String? = when (value) {
        is String -> "s:" + value
        is Boolean -> "b:" + value
        is Int -> "i:" + value
        is Long -> "l:" + value
        is Float -> "f:" + value
        is Double -> "f:" + value.toFloat()
        else -> null
    }

    /** The inverse. Null for anything malformed, which is then simply not applied. */
    fun decode(text: String): Any? {
        if (text.length < 2 || text[1] != ':') return null
        val body = text.substring(2)
        return when (text[0]) {
            's' -> body
            'b' -> body.toBooleanStrictOrNull()
            'i' -> body.toIntOrNull()
            'l' -> body.toLongOrNull()
            'f' -> body.toFloatOrNull()
            else -> null
        }
    }

    /**
     * A remote, as it travels.
     *
     * The stored form cannot: a password there is ciphertext under a keystore key that never
     * leaves the phone, so copying it across moves something unreadable. The plaintext is
     * carried only when [withSecrets] is asked for, and [secretsKept] records whether it was,
     * so the import can say what it is restoring.
     */
    data class RemoteLine(val json: JSONObject, val hadSecret: Boolean)

    private val SECRET_FIELDS = listOf("password", "privateKey", "keyPassphrase")

    /**
     * Strip the secrets out of one remote, reporting whether there were any.
     *
     * Emptied rather than removed, so the shape of the object is the one the reader expects
     * and a missing field never has to mean two things.
     */
    fun redact(remote: JSONObject): RemoteLine {
        val had = SECRET_FIELDS.any { remote.optString(it).isNotEmpty() }
        val out = JSONObject(remote.toString())
        SECRET_FIELDS.forEach { out.put(it, "") }
        return RemoteLine(out, had)
    }

    /**
     * A path that cannot mean anything on another phone.
     *
     * A `content://` path is a SAF grant - a token issued to one install. The export keeps it
     * (dropping entries silently is worse than carrying a dud) but counts them, so both ends
     * can say "4 of these were granted folders and will need granting again".
     */
    fun isPortable(path: String): Boolean =
        !path.startsWith("content:") && !path.contains("%3A%2F%2F")

    /** How many entries in a stored JSON array of `{path: ...}` will not travel. */
    fun unportableCount(storedJson: String?): Int {
        val raw = storedJson ?: return 0
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).count { i ->
                val e = arr.get(i)
                val p = if (e is JSONObject) e.optString("path") else e.toString()
                !isPortable(p)
            }
        }.getOrDefault(0)
    }

    /**
     * Write the file.
     *
     * @param values every preference this build holds, by key. Only the keys the chosen
     *   sections name are read; anything else is ignored rather than filtered, so adding a
     *   preference cannot accidentally start exporting it.
     * @param remotes the saved connections, already decrypted by their own store.
     */
    fun build(
        appVersion: String,
        exportedAt: Long,
        sections: Set<Section>,
        values: Map<String, Any?>,
        remotes: List<JSONObject>,
        withSecrets: Boolean,
    ): String {
        val body = JSONObject()
        var secretsCarried = false

        for (section in sections) {
            if (section == Section.REMOTES) {
                val arr = JSONArray()
                for (r in remotes) {
                    if (withSecrets) {
                        arr.put(JSONObject(r.toString()))
                        if (redact(r).hadSecret) secretsCarried = true
                    } else {
                        arr.put(redact(r).json)
                    }
                }
                body.put(section.id, arr)
                continue
            }
            val part = JSONObject()
            for (key in section.keys) {
                if (key in NEVER) continue
                val v = values[key] ?: continue
                part.put(key, encode(v) ?: continue)
            }
            body.put(section.id, part)
        }

        return JSONObject()
            .put("format", MAGIC)
            .put("version", FORMAT)
            .put("app", appVersion)
            .put("exportedAt", exportedAt)
            .put("containsPasswords", secretsCarried)
            .put("sections", body)
            .toString(2)
    }

    /** What a file turned out to be. A refusal is a result, so it is in the type. */
    sealed interface Reading {
        data class Refused(val why: String) : Reading
        data class Ok(
            val app: String,
            val exportedAt: Long,
            val containsPasswords: Boolean,
            /** Section to how many entries it would restore. Only sections actually present. */
            val counts: Map<Section, Int>,
            /** Keys this build does not recognise, which will not be applied. */
            val unknownKeys: List<String>,
            private val body: JSONObject,
        ) : Reading {
            /** The preference values for one section, ready to write. */
            fun valuesOf(section: Section): Map<String, Any> {
                val part = body.optJSONObject(section.id) ?: return emptyMap()
                return section.keys.mapNotNull { k ->
                    val raw = part.optString(k, "")
                    if (!part.has(k)) return@mapNotNull null
                    val v = decode(raw) ?: return@mapNotNull null
                    k to v
                }.toMap()
            }

            /** The remotes, as stored objects ready to hand back to the connection store. */
            fun remotes(): List<JSONObject> {
                val arr = body.optJSONArray(Section.REMOTES.id) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            }
        }
    }

    /**
     * Read a file without applying any of it.
     *
     * Deliberately separate from applying: the import screen shows what is in the file and
     * which parts to take BEFORE anything is written, because an import that silently
     * replaces a setup is the kind of thing somebody only notices a week later.
     */
    fun read(text: String): Reading {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return Reading.Refused("This is not a Filet settings file.")
        if (root.optString("format") != MAGIC) {
            return Reading.Refused("This is not a Filet settings file.")
        }
        val version = root.optInt("version", 0)
        if (version > FORMAT) {
            return Reading.Refused(
                "This file was written by a newer Filet (format $version, this one reads $FORMAT). " +
                    "Update Filet and open it again.",
            )
        }
        val body = root.optJSONObject("sections")
            ?: return Reading.Refused("The file has no settings in it.")

        val counts = LinkedHashMap<Section, Int>()
        val unknown = ArrayList<String>()
        for (key in body.keys()) {
            val section = Section.of(key)
            if (section == null) {
                unknown.add(key)
                continue
            }
            if (section == Section.REMOTES) {
                counts[section] = body.optJSONArray(key)?.length() ?: 0
                continue
            }
            val part = body.optJSONObject(key) ?: continue
            var known = 0
            for (k in part.keys()) {
                // A key in the right section but written in a form this build cannot read is
                // counted as unknown rather than as restored, so the number on the import
                // screen is the number that will actually be applied.
                if (k in section.keys && decode(part.optString(k, "")) != null) known++
                else unknown.add(k)
            }
            counts[section] = known
        }

        return Reading.Ok(
            app = root.optString("app", "unknown"),
            exportedAt = root.optLong("exportedAt", 0L),
            containsPasswords = root.optBoolean("containsPasswords", false),
            counts = counts,
            unknownKeys = unknown,
            body = body,
        )
    }

    /** The name an export gets, which has to sort by date and say what it is. */
    fun fileNameFor(stamp: String): String = "filet-settings-$stamp.json"
}
