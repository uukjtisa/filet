package dev.niccc2007.filet.vfs

/**
 * Whether an action is allowed, decided before it is attempted.
 *
 * ## Why this exists
 *
 * Every write used to find out whether it was permitted by failing. Three different shapes of
 * refusal reached the surface as three different sentences - a local read-only volume, an
 * archive that cannot be written into, and a share whose host has writing switched off - and
 * two of them arrived as whatever string the backend happened to put in an exception. A
 * refusal is not an error; it is an answer, and it should read like one wherever it came from.
 *
 * ## The two halves
 *
 * [check] asks the question up front from what the backend DECLARES, which costs nothing and
 * catches the cases that are knowable without touching the network. [fromFailure] catches the
 * rest: a remote can accept a connection and still refuse the individual write, and no
 * declaration can know that in advance. Both produce the same [Denial], so a caller has one
 * thing to show and the reader sees one kind of message.
 *
 * Kept pure and in L0 because it is a decision that can be wrong, and a decision that can be
 * wrong gets a test.
 */
enum class FileAction {
    READ,
    LIST,
    WRITE,
    CREATE_FILE,
    CREATE_DIR,
    RENAME,
    DELETE,
    ;

    /** The word for what was being attempted, for a sentence that names it. */
    val verb: String
        get() = when (this) {
            READ -> "read"
            LIST -> "open"
            WRITE -> "write to"
            CREATE_FILE -> "create a file in"
            CREATE_DIR -> "create a folder in"
            RENAME -> "rename"
            DELETE -> "delete"
        }
}

/** Why an action was refused. */
enum class DenyReason {
    /** The backend never claimed it could do this - an archive, an APK, a read-only mount. */
    NOT_SUPPORTED_HERE,

    /** The backend claims it, but this place is read-only. */
    READ_ONLY,

    /** A host accepted the connection and refused the operation. */
    REFUSED_BY_HOST,

    /** Credentials were rejected. Distinct from a refusal: the answer might change. */
    NOT_SIGNED_IN,
}

/**
 * A refused action, with the words to say so.
 *
 * [headline] is a sentence, not a label, because it is the first line of a dialogue. [detail]
 * says what would have to change, and is empty when nothing the reader can do would change it.
 */
data class Denial(
    val action: FileAction,
    val path: VPath,
    val reason: DenyReason,
    val remote: Boolean,
    /** The host's own words, when it gave any. Shown verbatim and never parsed. */
    val hostSaid: String? = null,
) {
    val headline: String
        get() = when (reason) {
            DenyReason.NOT_SUPPORTED_HERE ->
                "You cannot ${action.verb} anything here."
            DenyReason.READ_ONLY ->
                if (remote) "This share is read-only." else "This place is read-only."
            DenyReason.REFUSED_BY_HOST ->
                if (remote) "The host refused." else "Permission denied."
            DenyReason.NOT_SIGNED_IN ->
                "Those credentials were not accepted."
        }

    val detail: String
        get() = when (reason) {
            DenyReason.NOT_SUPPORTED_HERE ->
                "This kind of place can only be read. Copy what you need somewhere writable first."
            DenyReason.READ_ONLY ->
                if (remote) {
                    "Whoever is hosting it has writing switched off. On their device that is " +
                        "Allow changes, on the share they are hosting."
                } else {
                    "Filet has no write access to this volume."
                }
            DenyReason.REFUSED_BY_HOST ->
                if (remote) {
                    "It allowed the connection and then refused this operation, so the refusal " +
                        "is about this file or folder rather than the share."
                } else {
                    "The system refused this operation on this file."
                }
            DenyReason.NOT_SIGNED_IN ->
                "Open the connection in Remotes and check the user name and access code."
        }

    /** One line, for a place with no room for a dialogue. */
    val short: String
        get() = when (reason) {
            DenyReason.NOT_SUPPORTED_HERE -> "read-only place"
            DenyReason.READ_ONLY -> if (remote) "share is read-only" else "read-only"
            DenyReason.REFUSED_BY_HOST -> "refused"
            DenyReason.NOT_SIGNED_IN -> "not signed in"
        }
}

object ActionGate {

    /** The capability a backend must declare before [action] is worth attempting. */
    fun required(action: FileAction): Capability = when (action) {
        FileAction.READ, FileAction.LIST -> Capability.READ
        FileAction.WRITE, FileAction.CREATE_FILE -> Capability.WRITE
        FileAction.CREATE_DIR -> Capability.CREATE_DIR
        FileAction.RENAME -> Capability.RENAME
        FileAction.DELETE -> Capability.DELETE
    }

    /**
     * Ask before acting.
     *
     * @param declared what the backend behind [path] says it can do.
     * @param remote whether [path] lives on another device, which only changes the wording -
     *   a refusal is a refusal either way, and the reader is told which kind it is because the
     *   thing they would have to go and change is in a different place.
     * @return null when the action may be attempted.
     */
    fun check(
        action: FileAction,
        path: VPath,
        declared: Set<Capability>,
        remote: Boolean,
    ): Denial? {
        val need = required(action)
        if (need in declared) return null
        // A backend that declares READ and nothing else is read-only by design rather than by
        // policy, and saying "read-only" about an APK invites somebody to go looking for the
        // switch that turns it off.
        val reason =
            if (declared == setOf(Capability.READ)) DenyReason.NOT_SUPPORTED_HERE
            else DenyReason.READ_ONLY
        return Denial(action, path, reason, remote)
    }

    /**
     * Read a refusal out of a failure that has already happened.
     *
     * Only [VfsException.AccessDenied] becomes a [Denial]. Everything else is a fault rather
     * than an answer, and dressing a broken connection up as a permission problem sends the
     * reader to change a setting that was never the cause.
     */
    fun fromFailure(
        action: FileAction,
        path: VPath,
        failure: Throwable,
        remote: Boolean,
        hostSaid: String? = null,
    ): Denial? = when (failure) {
        is VfsException.AccessDenied -> Denial(
            action = action,
            path = path,
            reason = if (failure.unauthenticated) DenyReason.NOT_SIGNED_IN else DenyReason.REFUSED_BY_HOST,
            remote = remote,
            hostSaid = hostSaid,
        )

        else -> null
    }
}
