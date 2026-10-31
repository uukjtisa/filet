package dev.niccc2007.filet.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a pass may conclude. The defect: a folder pass registered the folder as a volume and then
 * swept every other row in the database. See [CrawlScope].
 */
class CrawlScopeTest {

    private val phone = 1L to "local:///storage/emulated/0"
    private val card = 2L to "local:///storage/1A2B-3C4D"
    private val nas = 3L to "smb:///n1a2b3c"

    @Test
    fun `a folder inside a held volume is a subtree of it`() {
        assertEquals(1L, CrawlScope.subtreeOf(listOf(phone, card), "local:///storage/emulated/0/Download"))
    }

    @Test
    fun `a volume's own root is the volume, not a folder in it`() {
        assertNull(CrawlScope.subtreeOf(listOf(phone, card), "local:///storage/emulated/0"))
    }

    @Test
    fun `a root nothing owns is a new volume`() {
        assertNull(CrawlScope.subtreeOf(listOf(phone), "local:///storage/1A2B-3C4D"))
        assertNull(CrawlScope.subtreeOf(emptyList(), "local:///storage/emulated/0/Download"))
    }

    @Test
    fun `a sibling with a longer name is not inside`() {
        // VolumeOwner's separator rule, which a bare prefix test gets wrong.
        assertNull(CrawlScope.subtreeOf(listOf(phone), "local:///storage/emulated/01"))
    }

    @Test
    fun `the folder volumes the defect registered are strays`() {
        val stray = 9L to "local:///storage/emulated/0/Download"
        assertEquals(listOf(9L), CrawlScope.strays(listOf(phone, card, nas, stray), setOf(phone.second, card.second)))
    }

    @Test
    fun `a real mount inside another is not a stray when the pass was given it`() {
        val inner = 5L to "local:///storage/emulated/0/mnt"
        assertTrue(CrawlScope.strays(listOf(phone, inner), setOf(phone.second, inner.second)).isEmpty())
    }

    @Test
    fun `shares are never strays`() {
        val nested = 6L to "smb:///n1a2b3c/sub"
        assertTrue(CrawlScope.strays(listOf(nas, nested), setOf(phone.second)).isEmpty())
    }

    @Test
    fun `a stored complete generation is believed`() {
        assertEquals(7L, CrawlScope.completeGen("7", metaGen = 12, rootGen = 3, lastRunAt = 1))
    }

    @Test
    fun `before the key existed, a root at the last complete generation was in that pass`() {
        assertEquals(12L, CrawlScope.completeGen(null, metaGen = 12, rootGen = 12, lastRunAt = 1))
        assertEquals(0L, CrawlScope.completeGen(null, metaGen = 12, rootGen = 11, lastRunAt = 1))
        // Never completed at all: nothing to believe.
        assertEquals(0L, CrawlScope.completeGen(null, metaGen = 0, rootGen = 0, lastRunAt = 0))
    }

    @Test
    fun `a share's unfinished pass is continued under its own generation`() {
        // The phone has completed twice since; the share's frontier is still its own.
        assertEquals(8L, CrawlScope.resumeGen(storedGen = 8, storedRows = 40, volumeCompleteGen = 5, fresh = 11))
    }

    @Test
    fun `a frontier the share has since completed past is not resumed`() {
        assertEquals(11L, CrawlScope.resumeGen(storedGen = 8, storedRows = 40, volumeCompleteGen = 9, fresh = 11))
        assertEquals(11L, CrawlScope.resumeGen(storedGen = 8, storedRows = 0, volumeCompleteGen = 5, fresh = 11))
        assertEquals(11L, CrawlScope.resumeGen(storedGen = null, storedRows = 0, volumeCompleteGen = 5, fresh = 11))
    }

    @Test
    fun `a local folder that cannot be read is carried, never fatal`() {
        assertEquals(CrawlScope.Unreadable.CARRY, CrawlScope.onUnreadable(remote = false, isRoot = true, consecutiveFailures = 50))
    }

    @Test
    fun `a share that stops answering ends the pass rather than completing it unread`() {
        assertEquals(CrawlScope.Unreadable.STOP, CrawlScope.onUnreadable(remote = true, isRoot = true, consecutiveFailures = 1))
        assertEquals(CrawlScope.Unreadable.CARRY, CrawlScope.onUnreadable(remote = true, isRoot = false, consecutiveFailures = 1))
        assertEquals(
            CrawlScope.Unreadable.STOP,
            CrawlScope.onUnreadable(remote = true, isRoot = false, consecutiveFailures = CrawlScope.SHARE_FAILURES_BEFORE_STOP),
        )
    }

    @Test
    fun `a folder pass may only delete under its folder`() {
        val sql = CrawlScope.sweepSql(volumeIds = emptyList(), subtreeRootIds = listOf(42L))!!
        assertTrue(sql, sql.contains("SELECT 42 UNION ALL"))
        assertTrue(sql, !sql.contains("vol_id"))
    }

    @Test
    fun `a volume pass deletes within its volumes and nothing else`() {
        assertEquals("(vol_id IN (1,2))", CrawlScope.sweepSql(listOf(1L, 2L, 1L), emptyList()))
        assertNull(CrawlScope.sweepSql(emptyList(), emptyList()))
    }
}
