package dev.niccc2007.filet.shortcuts

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.MainActivity
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.launch

/**
 * Resolves a pinned shortcut's stable file ID to wherever the file is *now*.
 *
 * This activity is the entire reason the shortcut rule works. The launcher hands back an ID;
 * this looks up the current path, and only then starts the app. Move the file, the shortcut
 * follows. Delete it, the shortcut is disabled with a message instead of silently doing
 * nothing when tapped.
 *
 * Translucent and `noHistory`, so it never appears in recents or flashes a blank screen.
 */
class ShortcutRouterActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = FiletApp.graphOf(this)
        val fileId = intent.getLongExtra(Shortcuts.EXTRA_FILE_ID, -1L)
        val fallback = intent.getStringExtra(Shortcuts.EXTRA_FALLBACK_PATH)

        // A non-file shortcut: a script to run, or an app action to perform. Handled before
        // the id lookup, because neither has a path to resolve.
        val wanted = intent.getStringExtra(Shortcuts.EXTRA_ACTION)
        if (wanted != null) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(EXTRA_ACTION, wanted)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
            finish()
            return
        }

        lifecycleScope.launch {
            val resolved: VPath? = when {
                fileId > 0 -> graph.index.pathFor(fileId)
                else -> null
            } ?: fallback?.let { runCatching { VPath.parse(it) }.getOrNull() }

            if (resolved == null) {
                fail(fileId, "Filet no longer knows where that file is.")
                return@launch
            }

            // Resolved is only a claim until the filesystem agrees - the same
            // verify-before-display rule the search results obey.
            val node = runCatching { graph.vfs.stat(resolved) }.getOrNull()
            if (node == null) {
                fail(fileId, "That file has been deleted.")
                return@launch
            }

            val open = Intent(this@ShortcutRouterActivity, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_TARGET, resolved.toString())
                intent.getStringExtra(Shortcuts.EXTRA_HANDLER)?.let { putExtra(EXTRA_HANDLER, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(open)
            finish()
        }
    }

    private fun fail(fileId: Long, message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (fileId > 0) Shortcuts.disable(this, fileId, message)
        finish()
    }

    companion object {
        const val EXTRA_TARGET = "filet.target"
        const val EXTRA_ACTION = "filet.actionTarget"
        const val EXTRA_HANDLER = "filet.handlerOverride"
    }
}
