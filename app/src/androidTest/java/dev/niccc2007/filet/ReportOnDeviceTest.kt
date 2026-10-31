package dev.niccc2007.filet

import android.content.pm.PackageManager
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.niccc2007.filet.report.ReportActivity
import dev.niccc2007.filet.report.ReportForm
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Report a problem" opens, and survives the backend not being there.
 *
 * The form shipped undeclared in the manifest, so the first tap on it threw
 * ActivityNotFoundException and took the app down. These are the checks that would have caught
 * it, plus the next layer out: a report feature that crashes when its server is missing is the
 * same bug.
 */
@RunWith(AndroidJUnit4::class)
class ReportOnDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule val compose = createAndroidComposeRule<ReportActivity>()

    @Test fun both_ways_in_resolve_to_a_declared_activity() {
        val pm = context.packageManager
        assertNotNull("About's form is not declared", pm.resolveActivity(ReportActivity.intent(context), PackageManager.MATCH_DEFAULT_ONLY))
        val crash = pm.resolveActivity(ReportActivity.forCrash(context, "x"), PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull("the crash screen's form is not declared", crash)
        assertTrue(
            "the crash screen's form must run in the crash process: ${crash!!.activityInfo.processName}",
            crash.activityInfo.processName.endsWith(":crash"),
        )
    }

    @Test fun the_form_opens() {
        compose.onNodeWithText("Report a problem").assertExists()
    }

    @Test fun sending_with_no_server_says_so_and_offers_github() {
        // Only meaningful while this build has no endpoint; with one, the network decides.
        if (ReportForm.ENDPOINT.isNotBlank()) return
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("The app crashed opening the report form")
        fields[1].performTextInput("Tapping Report a problem in About closed the app.")
        compose.onNodeWithText("Send").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Open on GitHub instead")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasText("not switched on", substring = true)).onFirst().assertExists()
    }
}
