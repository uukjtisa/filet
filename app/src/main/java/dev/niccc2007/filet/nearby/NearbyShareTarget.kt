package dev.niccc2007.filet.nearby

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nearby, in another app's share sheet: the file joins the Share page and that app stays on
 * screen. Nothing of Filet shows but a short message.
 *
 * A file with a real path on this phone is shared where it is. One that only arrives as content
 * (a photo from a messaging app) is copied into Filet's own received store first, because the
 * permission to read it ends when this closes and the share has to outlive that.
 */
class NearbyShareTarget : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = urisOf(intent)
        if (uris.isEmpty()) { finish(); return }
        val app = applicationContext
        scope.launch {
            val nodes = withContext(Dispatchers.IO) { uris.mapNotNull { node(it) } }
            val nearby = FiletApp.graphOf(app).nearby
            nodes.forEach { nearby.shared.share(it) }
            if (nodes.isNotEmpty() && !nearby.isRunning()) nearby.start()
            Toast.makeText(
                app,
                when (nodes.size) {
                    0 -> "Nothing usable was shared."
                    1 -> "${nodes.first().name} is on Filet's Share page"
                    else -> "${nodes.size} files are on Filet's Share page"
                },
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    private suspend fun node(uri: Uri): VNode? {
        val graph = FiletApp.graphOf(applicationContext)
        val path = when (uri.scheme) {
            "file" -> uri.path?.let { VPath.of("local", it) }
            "content" -> dev.niccc2007.filet.vfs.provider.SafProvider.pathOfDocumentUri(uri) ?: runCatching {
                val file = graph.files.inboxCache(nameOf(uri))
                contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                graph.files.vpath(file)
            }.getOrNull()
            else -> null
        } ?: return null
        return runCatching { graph.vfs.stat(path) }.getOrNull()
    }

    private fun nameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "shared")

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        @Suppress("DEPRECATION")
        fun urisOf(intent: Intent?): List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
    }
}
