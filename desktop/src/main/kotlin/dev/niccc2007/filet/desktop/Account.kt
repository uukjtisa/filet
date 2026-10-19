package dev.niccc2007.filet.desktop

import java.security.SecureRandom
import java.util.Random

/**
 * The one credential this machine hosts under. One for every share: a share is a folder, not a
 * tenant.
 *
 * ## It is the code in the path, not a sign-in
 *
 * SPEC.md §1 describes basic auth. `:core-dav`'s server has none and never had: its credential is
 * the access code in `/a/<code>/`, compared in constant time, because Explorer's credential prompt
 * is a poor place for a per-session secret and refusing without one makes the drive unmappable.
 * The phone asks for exactly that code when it adds a discovered host. So the account is a
 * password with no user name - a user field would be a value shown in the window that nothing
 * ever checks.
 *
 * The server selects a share BY its code, so codes must differ per share. Each share's code is
 * [password] followed by that share's tag ([codeFor]): one secret to read across a room, and
 * rotating it revokes every share at once, which is what one account per machine has to mean.
 */
data class Account(val password: String) {

    /** The full access code [share] answers on: its own when one was set, else password + tag. */
    fun codeFor(share: Share): String = share.code.ifBlank { password + share.tag }

    /**
     * Why [code] cannot be [share]'s access code, in a sentence a person can act on, or null.
     *
     * Codes select the share on the server, so two shares on one code means one of them is
     * unreachable with no error anywhere - hence the collision check against every other share's
     * EFFECTIVE code, not only the hand-set ones.
     */
    fun refuseCode(code: String, share: Share, all: List<Share>): String? {
        if (code.length < MIN_CODE) return "That code is too short. Use at least $MIN_CODE characters."
        if (code.length > MAX_CODE) return "That code is too long. Use at most $MAX_CODE characters."
        val bad = code.filter { it !in ALPHABET }.toSet()
        if (bad.isNotEmpty()) {
            return "These characters cannot be used: ${bad.joinToString(" ")}. A code uses only " +
                "the digits 2-9 and the letters $LETTERS - no vowels, and no 0, 1, l or o, so it " +
                "cannot be misread when read aloud or off a screen."
        }
        val clash = all.firstOrNull { it.path != share.path && codeFor(it) == code }
        if (clash != null) return "The share \"${clash.name}\" already uses that code. Pick a different one."
        return null
    }


    companion object {
        /**
         * The phone's own code alphabet, copied rather than shared because `WebDavServer` keeps
         * it private: lowercase, no vowels so it cannot spell anything, no `0 1 l o i` so it
         * survives being read off a screen. Lowercase also because a phone keyboard capitalises
         * the first letter of a box and the comparison is exact. SPEC.md asks for alphanumeric
         * only - a dashed password broke a basic-auth comparison once - and this is a subset.
         */
        const val ALPHABET = "23456789bcdfghjkmnpqrstvwxz"

        /** 27^10 is about 2e14, against a code that is only ever tried over a LAN round trip. */
        const val LENGTH = 10

        const val MIN_CODE = 6
        const val MAX_CODE = 32
        private val LETTERS = ALPHABET.filter { it.isLetter() }

        private val secure = SecureRandom()

        /** A fresh password. [random] is a parameter so a test can pin the sequence. */
        fun generate(length: Int = LENGTH, random: Random = secure): String =
            (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

        /** Whether [p] is a password this tool would have made, so a hand-edited file is not trusted blind. */
        fun valid(p: String?): Boolean = p != null && p.length in MIN_CODE..MAX_CODE && p.all { it in ALPHABET }
    }
}
