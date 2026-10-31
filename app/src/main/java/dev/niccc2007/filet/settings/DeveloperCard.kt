package dev.niccc2007.filet.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.BuildConfig
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The things only somebody building Filet needs, and only in a build that is being built.
 *
 * ## Why it is not shipped
 *
 * Everything here is destructive on purpose: it crashes the app, or it kills it outright.
 * Those are the right tools for proving that the crash reporter catches a real crash and that
 * nothing is left running afterwards, and they are obviously wrong in a release - a row
 * labelled "Crash Filet now" in a file manager is not a feature, it is a trap.
 *
 * `BuildConfig.DEBUG` rather than a preference, so the guard is the compiler's rather than
 * somebody's memory: in a release build this composable's body is unreachable and the whole
 * card goes with it.
 *
 * ## Why a real crash and not a simulated one
 *
 * The crash reporter is installed as an uncaught-exception handler in another process. A
 * simulated crash - showing the crash screen directly, or calling the reporter by hand - would
 * exercise the screen and prove nothing about the handler, the process split, or whether a
 * report actually survives the death of the process that produced it. Those are the parts that
 * have been wrong before, so the test has to be an uncaught throw on the main thread, which is
 * what a real crash is.
 */
@Composable
fun DeveloperCard(vm: BrowserViewModel) {
    if (!BuildConfig.DEBUG) return
    val colors = Filet.colors
    val context = LocalContext.current

    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(
            "Debug builds only. Both buttons end the app.",
            fontSize = 10.5.sp,
            color = colors.fg3,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            dev.niccc2007.filet.ui.tabs.HoldHint("Shows the crash screen and its report option.") {
            SmallButton("Crash now") {
                // Posted to the main thread's queue rather than thrown inside the click.
                //
                // Compose catches what a composable throws and can turn it into its own error
                // rather than letting it reach the uncaught handler - which would test the
                // wrong thing entirely. Handed to the looper, it is an ordinary uncaught
                // exception on the main thread, which is exactly what a real crash is.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    throw IllegalStateException("Test crash from the Developer tab")
                }
            }
            }
            dev.niccc2007.filet.ui.tabs.HoldHint("Also stops playback, sharing and running jobs.") {
                SmallButton("Shut down") { vm.shutDownEverything() }
            }
        }
    }
}
