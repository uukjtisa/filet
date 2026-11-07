package dev.niccc2007.filet.webdav

/**
 * The one rule for an access code, on the phone and on the PC.
 *
 * The code is both the password and part of the address (`/a/<code>/`), typed by hand on one
 * device and checked on the other. The phone accepted any letters and digits while the PC
 * accepted only its own readable alphabet, so a code pinned on the phone could not be typed into
 * the PC's mount box. Both now ask this.
 *
 * - **Typed by hand:** ASCII letters and digits, [MIN] to [MAX] long, case kept. Any length from
 *   one, because the phone has always taken any non-blank code, and a minimum here would refuse
 *   a code somebody already pinned and quietly move that share to a new address. ASCII only,
 *   because the code goes into a UNC path and a URL, where anything else gets mangled.
 * - **Made by Filet:** [READABLE] only - no vowels, so it cannot spell anything, and none of the
 *   characters that look alike across a room.
 */
object AccessCode {
    const val MIN = 1
    const val MAX = 64

    /** What a generated code is drawn from. */
    const val READABLE = "23456789bcdfghjkmnpqrstvwxz"

    fun isCodeChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    fun acceptable(code: String?): Boolean = code != null && code.length in MIN..MAX && code.all(::isCodeChar)

    /** A code as typed, spaces dropped, or null when it cannot be one. Case is kept: the check is exact. */
    fun clean(typed: String): String? = typed.filterNot { it.isWhitespace() }.takeIf(::acceptable)

    /** Only what a code may hold, at most [MAX] of it: for a field that filters as you type. */
    fun typing(raw: String): String = raw.filter(::isCodeChar).take(MAX)

    /** Why [code] cannot be one, in a sentence a person can act on, or null when it can. */
    fun problem(code: String): String? {
        if (code.length < MIN) return "Type a code."
        if (code.length > MAX) return "That code is too long. Use at most $MAX characters."
        val bad = code.filterNot(::isCodeChar).toSet()
        if (bad.isNotEmpty()) return "These characters cannot be used: ${bad.joinToString(" ")}. A code is letters A-Z and digits only."
        return null
    }

    /** A fresh code of [length] from [READABLE]. */
    fun generate(length: Int, random: java.util.Random): String =
        (1..length).map { READABLE[random.nextInt(READABLE.length)] }.joinToString("")
}
