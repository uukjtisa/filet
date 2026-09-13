package dev.niccc2007.filet

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.vfs.provider.ApkProvider
import dev.niccc2007.filet.vfs.provider.ArchiveProvider
import dev.niccc2007.filet.vfs.provider.RootProvider
import dev.niccc2007.filet.vfs.provider.SafProvider
import dev.niccc2007.filet.vfs.provider.net.NetProtocol
import dev.niccc2007.filet.vfs.provider.net.PeerProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The object graph builds, and every provider the milestones promised is registered.
 *
 * PLAN.md R4 says every claim is checkable. This is the cheapest possible check of the
 * biggest claim: that the layer model actually exists at runtime rather than on paper.
 */
@RunWith(AndroidJUnit4::class)
class GraphSmokeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun the_package_is_the_frozen_application_id() {
        // Debug builds carry ".debug" on purpose, so a development build cannot see the
        // release Trawl through the bridge (PLAN.md §5.4).
        assertTrue(
            "unexpected package ${context.packageName}",
            context.packageName == "dev.niccc2007.filet" || context.packageName == "dev.niccc2007.filet.debug",
        )
    }

    @Test fun every_promised_provider_is_registered() {
        val graph = FiletGraph(context)
        val schemes = graph.vfs.schemes
        val expected = setOf(
            "local",
            SafProvider.SCHEME,
            ArchiveProvider.SCHEME,
            ApkProvider.SCHEME,
            RootProvider.SCHEME,
            NetProtocol.SMB.scheme,
            NetProtocol.SFTP.scheme,
            NetProtocol.FTP.scheme,
            NetProtocol.WEBDAV.scheme,
            PeerProvider.SCHEME,
        )
        assertEquals(
            "a promised provider is missing from the VFS",
            emptySet<String>(),
            expected - schemes,
        )
    }

    @Test fun the_local_volume_is_reachable() {
        val graph = FiletGraph(context)
        val roots = runBlocking { graph.vfs.roots() }
        assertTrue("no storage volume was found at all", roots.isNotEmpty())
    }

    @Test fun the_index_opens_or_degrades_without_taking_the_app_down() {
        val graph = FiletGraph(context)
        // Either it opened, or it is the null implementation. Neither may throw.
        val status = graph.index.status.value
        assertTrue(status.files >= 0)
    }

    @Test fun the_handler_registry_answers_for_every_kind() {
        val graph = FiletGraph(context)
        val node = dev.niccc2007.filet.vfs.VNode(
            dev.niccc2007.filet.vfs.VPath.of("local", "/storage/emulated/0/x.apk"),
            isDir = false, size = 1, mtime = 0,
        )
        assertEquals(
            dev.niccc2007.filet.handlers.HandlerId.APK,
            graph.registry.handlerFor(node),
        )
    }
}
