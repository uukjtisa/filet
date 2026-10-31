package dev.niccc2007.filet.vfs.provider

import dev.niccc2007.filet.vfs.Capability
import java.io.File

/**
 * Changes to an archive, worked out before a byte of it is rewritten.
 *
 * Every write into an archive - a paste, a drop, a new folder, a delete, a rename, an edited
 * member saved back - is one or more [EditOp]s against the archive's member list, and applying
 * them is a fold over names. Pure, so what an edit WILL do can be stated in a test; the rewrite
 * itself ([ArchiveEditor.rewrite]) only carries out the list this produces.
 *
 * Names are member paths as [normaliseMember] spells them: forward separators, no leading or
 * trailing one.
 */
internal sealed interface EditOp {
    /** New or replaced bytes for one file, waiting in [spool] (a file path), [size] long. */
    data class Put(val name: String, val spool: String, val size: Long) : EditOp

    data class Mkdir(val name: String) : EditOp

    /** A file, or a folder and everything under it. */
    data class Delete(val name: String) : EditOp

    /** A rename or a move within the archive: [from] and everything under it, to [to]. */
    data class Rename(val from: String, val to: String) : EditOp
}

/** One entry of the archive as it will be written. */
internal sealed interface Outcome {
    val name: String

    /** An existing member, copied across as it is, under [name] - its old name or a new one. */
    data class Keep(val from: String, override val name: String, val isDir: Boolean) : Outcome

    /** A file whose bytes come from a spool. */
    data class Fresh(override val name: String, val spool: String, val size: Long) : Outcome

    /** A folder that was not there before. */
    data class Dir(override val name: String) : Outcome
}

/** Why an edit cannot be applied, in a form the provider can turn into its own exceptions. */
internal class PlanException(val why: Why, message: String) : Exception(message) {
    enum class Why { NOT_FOUND, EXISTS, REFUSED }
}

internal object ArchivePlan {

    /**
     * [members] with [ops] applied in order, as the entries to write.
     *
     * Order is kept: an entry stays where it was, renamed in place, and anything new goes at the
     * end. Some formats care - an EPUB's first entry has to stay first.
     */
    fun apply(members: List<Member>, ops: List<EditOp>): List<Outcome> =
        State(members).also { s -> ops.forEach(s::add) }.outcomes

    /**
     * A plan built one edit at a time, so a batch can check each edit as it is made - the
     * fortieth file of a paste is refused the moment its name collides, not after the other
     * thirty-nine were spooled.
     */
    class State(members: List<Member>) {
        private val out = LinkedHashMap<String, Outcome>()

        init {
            for (m in members) if (m.name.isNotEmpty() && m.name !in out) out[m.name] = Outcome.Keep(m.name, m.name, m.isDir)
        }

        val outcomes: List<Outcome> get() = out.values.toList()

        /** Apply [op], or throw [PlanException] and leave the plan as it was. */
        fun add(op: EditOp) = edit(out, op)
    }

    private fun edit(out: LinkedHashMap<String, Outcome>, op: EditOp) {
        when (op) {
            is EditOp.Put -> {
                val name = clean(op.name)
                if (isFolder(out, name)) throw PlanException(PlanException.Why.EXISTS, "A folder named ${leaf(name)} is already there")
                underFile(out, name)?.let { throw PlanException(PlanException.Why.REFUSED, "${leaf(it)} is a file, not a folder") }
                // Replaced where it stood rather than moved to the end.
                out[name] = Outcome.Fresh(name, op.spool, op.size)
            }
            is EditOp.Mkdir -> {
                val name = clean(op.name)
                if (exists(out, name)) throw PlanException(PlanException.Why.EXISTS, "${leaf(name)} is already there")
                underFile(out, name)?.let { throw PlanException(PlanException.Why.REFUSED, "${leaf(it)} is a file, not a folder") }
                out[name] = Outcome.Dir(name)
            }
            is EditOp.Delete -> {
                val name = clean(op.name)
                if (!exists(out, name)) throw PlanException(PlanException.Why.NOT_FOUND, "${leaf(name)} is not in the archive")
                out.keys.filter { it == name || it.startsWith("$name/") }.forEach { out.remove(it) }
            }
            is EditOp.Rename -> {
                val from = clean(op.from)
                val to = clean(op.to)
                if (from == to) return
                if (!exists(out, from)) throw PlanException(PlanException.Why.NOT_FOUND, "${leaf(from)} is not in the archive")
                if (exists(out, to)) throw PlanException(PlanException.Why.EXISTS, "${leaf(to)} is already there")
                if (to.startsWith("$from/")) throw PlanException(PlanException.Why.REFUSED, "A folder cannot go inside itself")
                val moved = LinkedHashMap<String, Outcome>()
                for ((k, v) in out) {
                    val nk = when {
                        k == from -> to
                        k.startsWith("$from/") -> to + k.substring(from.length)
                        else -> k
                    }
                    moved[nk] = when (v) {
                        is Outcome.Keep -> v.copy(name = nk)
                        is Outcome.Fresh -> v.copy(name = nk)
                        is Outcome.Dir -> v.copy(name = nk)
                    }
                }
                out.clear()
                out.putAll(moved)
            }
        }
    }

    /**
     * The member list the archive will have, for listing it while edits are still waiting to be
     * written. A batch of forty files pasted in has to see the first thirty-nine, or the
     * fortieth's name check is against an archive that is not the one being built.
     */
    fun view(members: List<Member>, outcomes: List<Outcome>, now: Long): List<Member> {
        val byName = members.associateBy { it.name }
        return outcomes.map { o ->
            when (o) {
                is Outcome.Keep -> byName[o.from]?.copy(name = o.name) ?: Member(o.name, o.isDir, -1L, now)
                is Outcome.Fresh -> Member(o.name, isDir = false, size = o.size, mtime = now)
                is Outcome.Dir -> Member(o.name, isDir = true, size = -1L, mtime = now)
            }
        }
    }

    /** True when any entry is [name], or [name] is a folder some entry sits under. */
    private fun exists(out: Map<String, Outcome>, name: String) =
        name in out || out.keys.any { it.startsWith("$name/") }

    private fun isFolder(out: Map<String, Outcome>, name: String): Boolean {
        val o = out[name]
        if (o is Outcome.Dir || o is Outcome.Keep && o.isDir) return true
        return o == null && out.keys.any { it.startsWith("$name/") }
    }

    /** The first ancestor of [name] that is a FILE, which a path cannot go through. */
    private fun underFile(out: Map<String, Outcome>, name: String): String? {
        var at = name.substringBeforeLast('/', "")
        while (at.isNotEmpty()) {
            val o = out[at]
            if (o is Outcome.Fresh || o is Outcome.Keep && !o.isDir) return at
            at = at.substringBeforeLast('/', "")
        }
        return null
    }

    private fun clean(name: String) = normaliseMember(name)

    private fun leaf(name: String) = name.substringAfterLast('/')
}

/**
 * What each archive format can have written into it, by name.
 *
 * - The zip family (zip, jar, apk, epub, cbz...) is patched: untouched members are copied
 *   across as their stored bytes.
 * - 7z and the tar family are rewritten whole. 7z is solid and a compressed tar is one stream,
 *   so there is no member to leave alone; a plain tar has no index to patch.
 * - A single compressed file (gz, bz2, xz) holds one file, so it can be replaced and nothing else.
 * - RAR is read and never written.
 */
internal object ArchiveWrites {

    enum class Kind { ZIP, TAR, SEVEN_ZIP, STREAM, NONE }

    const val STAGED =
        "This archive was opened from a copy of a file on another device. Download it to change what is inside."

    /**
     * [STAGED] when [host] lies under one of [under]: a copy of an archive on a share, staged so
     * its reader can seek. Changing it would change the copy and leave the real one as it was.
     * One check for the browser's writes and the editor's save alike.
     */
    fun stagedRefusal(host: File, under: List<String>): String? {
        val abs = host.absolutePath
        return STAGED.takeIf { under.any { dir -> abs.startsWith(dir.trimEnd('/', '\\') + File.separator) } }
    }

    fun kindOf(format: ArchiveFormat?): Kind = when (format?.kind) {
        ArchiveKind.ZIP -> Kind.ZIP
        ArchiveKind.TAR -> Kind.TAR
        ArchiveKind.SEVEN_ZIP -> Kind.SEVEN_ZIP
        ArchiveKind.STREAM -> Kind.STREAM
        else -> Kind.NONE
    }

    fun capabilities(kind: Kind): Set<Capability> = when (kind) {
        Kind.NONE -> setOf(Capability.READ)
        // Replace only: there is no second file to add, and nothing to rename or delete that
        // would leave a valid file behind.
        Kind.STREAM -> setOf(Capability.READ, Capability.WRITE)
        else -> setOf(Capability.READ, Capability.WRITE, Capability.RENAME, Capability.DELETE, Capability.CREATE_DIR)
    }

    /** Why [name] cannot be written at all, naming the format; null when its format can be. */
    fun formatRefusal(name: String): String? {
        val format = Archives.of(name) ?: return "$name is not an archive Filet knows how to write."
        return when (kindOf(format)) {
            Kind.NONE -> "Filet reads ${format.label} and cannot write it, so nothing can be changed inside it. Extract it to change what is in it."
            else -> null
        }
    }

    /**
     * Why [op] cannot go into an archive of [kind] holding [members], or null when it can.
     * Only a single compressed file says no here; every other refusal is a fact about names,
     * and [ArchivePlan.apply] gives those.
     */
    fun opRefusal(kind: Kind, label: String, members: List<Member>, op: EditOp): String? {
        if (kind == Kind.NONE) return "Filet reads $label and cannot write it."
        if (kind != Kind.STREAM) return null
        val only = members.singleOrNull()?.name
        return if (op is EditOp.Put && op.name == only) null
        else "A $label file holds exactly one file, so Filet can replace it but not add, remove or rename anything in it."
    }
}
