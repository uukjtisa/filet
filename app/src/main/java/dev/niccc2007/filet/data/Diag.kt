package dev.niccc2007.filet.data

import android.content.Context
import java.io.File

/**
 * A log that survives a phone which will not give you logcat.
 *
 * Huawei and Honor devices drop application logging unless it is switched on through a hidden
 * dialer menu, so `adb logcat` on one of them returns nothing at all for this app - which is
 * how a round of debugging produced zero lines from code that was demonstrably running. This
 * writes to the app's own files directory instead, where `adb shell run-as` can read it back
 * whatever the vendor has decided about logs.
 *
 * Debug builds only, by the call sites. It is a tool for finding a fault on a real phone, not
 * a feature: nothing in the app reads it, and it is capped so it cannot grow.
 */
object Diag {

    private var file: File? = null

    fun start(context: Context) {
        if (!dev.niccc2007.filet.BuildConfig.DEBUG) return
        val f = File(context.filesDir, "diag.log")
        runCatching { if (f.exists() && f.length() > CAP) f.delete() }
        file = f
    }

    fun log(tag: String, line: String) {
        val f = file ?: return
        runCatching {
            f.appendText("${System.currentTimeMillis() % 1_000_000} $tag $line\n")
            if (f.length() > CAP) f.writeText("(trimmed)\n")
        }
    }

    private const val CAP = 400_000L
}
