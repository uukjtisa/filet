package dev.niccc2007.filet.pick

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * A caller, so the picker can be proved from the outside.
 *
 * Gate P10 asks whether another app actually receives the file, and nothing inside Filet can
 * answer that: `setResult` looks identical from in here whether or not the caller gets
 * anything. The only honest proof is a second app asking, so this is one - the smallest that
 * can exist, in the **debug source set only**, so it is never part of a release.
 *
 * It fires the real `ACTION_GET_CONTENT`, takes the result, and then does the thing that
 * actually matters: **opens the returned URI and reads bytes from it.** A URI that comes back
 * and cannot be opened is the failure this whole round is about, and it is invisible until
 * somebody tries.
 *
 * Everything it learns goes to logcat under `FiletPickProbe`, so a harness can read it:
 *
 *     adb shell am start -n dev.niccc2007.filet.debug/dev.niccc2007.filet.pick.PickProbeActivity
 *     adb shell am start -n …/…PickProbeActivity --ez multiple true --es type "image/&#42;"
 */
class PickProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        val type = intent.getStringExtra("type") ?: "*/*"
        val multiple = intent.getBooleanExtra("multiple", false)
        val ask = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(type)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        Log.i(TAG, "asking for type=$type multiple=$multiple")
        // Straight at Filet rather than through a chooser: the harness is testing Filet's
        // answer, and a chooser would only add a tap for somebody to get wrong.
        ask.setPackage(packageName)
        startActivityForResult(ask, REQUEST)
    }

    @Deprecated("startActivityForResult is what a real caller uses, which is the point")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return

        if (resultCode != RESULT_OK) {
            Log.i(TAG, "RESULT: cancelled (code=$resultCode)")
            finish()
            return
        }

        // Each slot reported SEPARATELY before they are merged, because Nic's report - picked
        // several, one arrived - has three different causes that the merged view cannot tell
        // apart. If the clip holds four and the data slot holds one, Filet answered correctly
        // and the app that asked reads only `getData()`; if the clip is empty, Filet is wrong.
        val inData = data?.data
        val clip = data?.clipData
        val inClip = buildList {
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) }
        }
        Log.i(TAG, "SLOTS: data=${if (inData == null) 0 else 1} clip=${inClip.size}")

        // A naive caller is the common one and is worth being able to imitate exactly: plenty
        // of upload flows call `getData()` and never look at the clip.
        val uris = when (intent.getStringExtra("read")) {
            "data" -> listOfNotNull(inData)
            "clip" -> inClip
            else -> (listOfNotNull(inData) + inClip).distinct()
        }

        Log.i(TAG, "RESULT: ok, ${uris.size} uri(s), clip=${clip?.itemCount ?: 0}")
        for (u in uris) Log.i(TAG, "URI: $u")
        for (u in uris) Log.i(TAG, "READ: " + read(u))
        finish()
    }

    /**
     * Open it and read the first few bytes.
     *
     * The assertion that matters. A URI that arrives but cannot be opened - no grant, a
     * provider that does not serve it, a file that was never materialised - is exactly the
     * failure this round exists to remove, and it looks perfect until this line runs.
     */
    private fun read(uri: Uri): String = runCatching {
        contentResolver.openInputStream(uri).use { input ->
            val buf = ByteArray(64)
            val n = input?.read(buf) ?: -1
            if (n <= 0) "opened but empty" else "$n bytes: " + String(buf, 0, n).replace("\n", "\\n")
        }
    }.getOrElse { "FAILED to open: ${it.javaClass.simpleName}: ${it.message}" }

    private companion object {
        const val TAG = "FiletPickProbe"
        const val REQUEST = 7301
    }
}
