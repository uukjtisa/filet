package dev.niccc2007.filet.desktop

import dev.niccc2007.filet.webdav.AccessCode
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
        // The phone's rule, from the one place both read it: any letters and digits, case kept.
        // Only a code this tool makes keeps to the readable alphabet.
        AccessCode.problem(code)?.let { return it }
        val clash = all.firstOrNull { it.path != share.path && codeFor(it) == code }
        if (clash != null) return "The share \"${clash.name}\" already uses that code. Pick a different one."
        return null
    }


    companion object {
        /** What a generated password is drawn from: [AccessCode.READABLE], the phone's own. */
        const val ALPHABET = AccessCode.READABLE

        /** 27^10 is about 2e14, against a code that is only ever tried over a LAN round trip. */
        const val LENGTH = 10

        /** The bounds of a password this tool made. A code typed by hand follows [AccessCode]. */
        const val MIN_CODE = 6
        const val MAX_CODE = 32

        private val secure = SecureRandom()

        /** A fresh password. [random] is a parameter so a test can pin the sequence. */
        fun generate(length: Int = LENGTH, random: Random = secure): String =
            (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

        /** Whether [p] is a password this tool would have made, so a hand-edited file is not trusted blind. */
        fun valid(p: String?): Boolean = p != null && p.length in MIN_CODE..MAX_CODE && p.all { it in ALPHABET }
    }
}
