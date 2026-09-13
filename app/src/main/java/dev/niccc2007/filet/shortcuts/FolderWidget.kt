package dev.niccc2007.filet.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.MainActivity
import dev.niccc2007.filet.R
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.runBlocking

/**
 * A folder on the home screen.
 *
 * Lists a directory in a scrollable widget and opens an entry straight into Filet. The
 * folder is addressed by path rather than by file ID, unlike a pinned shortcut: a widget is
 * configured against a *location* the user chose, and if they move that folder they mean to
 * reconfigure the widget.
 */
class FolderWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val target = readTarget(context, id)
            val views = RemoteViews(context.packageName, R.layout.widget_folder).apply {
                setTextViewText(R.id.widget_title, target?.name?.ifEmpty { "Filet" } ?: "Filet")

                val serviceIntent = Intent(context, FolderWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    putExtra(EXTRA_PATH, target?.toString())
                    // The data URI makes each widget's adapter distinct; without it every
                    // widget shares one factory and they all show the same folder.
                    data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                setRemoteAdapter(R.id.widget_list, serviceIntent)
                setEmptyView(R.id.widget_list, R.id.widget_empty)

                val open = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(ShortcutRouterActivity.EXTRA_TARGET, target?.toString())
                }
                setOnClickPendingIntent(
                    R.id.widget_title,
                    PendingIntent.getActivity(
                        context, id, open,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                setPendingIntentTemplate(
                    R.id.widget_list,
                    PendingIntent.getActivity(
                        context, id + 10_000,
                        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    ),
                )
            }
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }
    }

    companion object {
        const val EXTRA_PATH = "filet.widget.path"
        private const val PREF = "filet.widgets"

        fun setTarget(context: Context, widgetId: Int, path: VPath) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString("w$widgetId", path.toString()).apply()
        }

        fun readTarget(context: Context, widgetId: Int): VPath? {
            val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("w$widgetId", null)
                ?: FiletApp.graphOf(context).vfsRootOrNull()?.toString()
                ?: return null
            return runCatching { VPath.parse(raw) }.getOrNull()
        }

        /** Nudge every folder widget to re-read. Called after an operation changes a folder. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, FolderWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }
    }
}

private fun dev.niccc2007.filet.FiletGraph.vfsRootOrNull(): VPath? =
    runCatching { runBlocking { vfs.roots().firstOrNull()?.path } }.getOrNull()

class FolderWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        return FolderFactory(applicationContext, id)
    }
}

/**
 * The widget's list adapter.
 *
 * `onDataSetChanged` runs on a binder thread with a generous timeout, so a blocking VFS read
 * is correct here - this is one of the few places where `runBlocking` is the right call
 * rather than a shortcut.
 */
private class FolderFactory(private val context: Context, private val widgetId: Int) :
    RemoteViewsService.RemoteViewsFactory {

    private var rows: List<VNode> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val graph = FiletApp.graphOf(context)
        val target = FolderWidgetProvider.readTarget(context, widgetId) ?: return
        rows = runCatching {
            runBlocking { graph.vfs.list(target) }
                .filterNot { it.hidden }
                .sortedWith(compareByDescending<VNode> { it.isDir }.thenBy { it.name.lowercase() })
                .take(60)
        }.getOrElse { emptyList() }
    }

    override fun onDestroy() { rows = emptyList() }
    override fun getCount(): Int = rows.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val node = rows[position]
        return RemoteViews(context.packageName, R.layout.widget_row).apply {
            setTextViewText(R.id.row_name, node.name)
            setTextViewText(R.id.row_meta, if (node.isDir) "folder" else humanSize(node.size))
            setOnClickFillInIntent(
                R.id.row_root,
                Intent().putExtra(ShortcutRouterActivity.EXTRA_TARGET, node.path.toString()),
            )
        }
    }
}
