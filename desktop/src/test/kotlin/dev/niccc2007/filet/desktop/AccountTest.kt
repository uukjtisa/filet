package dev.niccc2007.filet.desktop

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTest {

    @Test fun `a generated password is ten characters from the code alphabet`() {
        val r = Random(7)
        repeat(500) {
            val p = Account.generate(random = r)
            assertEquals(10, p.length)
            assertTrue(p, p.all { it in Account.ALPHABET })
            assertTrue(p, p.all { it.isLetterOrDigit() && !it.isUpperCase() })
        }
    }

    @Test fun `the alphabet has no vowels and no look-alikes`() {
        for (c in "aeiou01lo") assertFalse("$c", c in Account.ALPHABET)
    }

    @Test fun `passwords differ between draws`() {
        val r = Random(1)
        assertEquals(200, (1..200).map { Account.generate(random = r) }.toSet().size)
    }

    @Test fun `validity rejects punctuation, uppercase and short values`() {
        assertTrue(Account.valid("bcd234fgh5"))
        assertFalse(Account.valid("bcd-234fgh"))
        assertFalse(Account.valid("BCD234FGH5"))
        assertFalse(Account.valid("bc2"))
        assertFalse(Account.valid(null))
    }

    @Test fun `a hand-set code replaces password plus tag for that share alone`() {
        val a = Account("bcd234fgh5")
        assertEquals("zzz999", a.codeFor(Share("D:/x", "X", false, "x", code = "zzz999")))
        assertEquals("bcd234fgh5x", a.codeFor(Share("D:/x", "X", false, "x")))
    }

    @Test fun `a code is refused for length, alphabet and collision, each with its own reason`() {
        val a = Account("bcd234fgh5")
        val docs = Share("D:/Docs", "Docs", false, "docs")
        val pics = Share("D:/Pics", "Pics", false, "pics", code = "kkk777")
        val all = listOf(docs, pics)
        assertTrue(a.refuseCode("kk7", docs, all)!!.contains("too short"))
        assertTrue(a.refuseCode("k".repeat(33), docs, all)!!.contains("too long"))
        val vowels = a.refuseCode("hello22", docs, all)!!
        assertTrue(vowels, vowels.contains("e") && vowels.contains("o") && vowels.contains("cannot be used"))
        assertTrue(a.refuseCode("kkk777", docs, all)!!.contains("Pics"))
        // Collides with another share's EFFECTIVE code, password + tag, not only hand-set ones.
        val dcs = Share("D:/Dcs", "Dcs", false, "dcs")
        assertTrue(a.refuseCode("bcd234fgh5dcs", pics, listOf(dcs, pics))!!.contains("Dcs"))
        // A share keeping its own code is not a collision with itself.
        assertEquals(null, a.refuseCode("kkk777", pics, all))
    }

    @Test fun `a share's code is the password followed by its tag`() {
        assertEquals(
            "bcd234fgh5photos",
            Account("bcd234fgh5").codeFor(Share("D:/Pictures", "Photos", false, "photos")),
        )
    }
}
