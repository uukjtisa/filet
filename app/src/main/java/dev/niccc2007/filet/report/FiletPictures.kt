package dev.niccc2007.filet.report

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import dev.niccc2007.filet.pick.PickActivity
import dev.niccc2007.filet.pick.PickRequest

/**
 * The report form's "Add pictures", answered by Filet's own picker.
 *
 * Sent to `PickActivity` exactly as another app's `ACTION_GET_CONTENT` would be - images,
 * several allowed, openable - so the report gets the same browser, thumbnails, dimming and
 * confirm as every other pick, and there is still only one picker. The two extras name the
 * operation, so the card reads "Choose files to attach to your report" instead of naming Filet
 * as an outside app.
 *
 * Consent is unchanged by the switch: an explicit pick and confirm in the picker, then the
 * pictures sit in the form where each can be removed, and nothing is uploaded until Send.
 */
object FiletPictures : ActivityResultContract<Unit, List<Uri>>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_GET_CONTENT)
            .setClass(context, PickActivity::class.java)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .putExtra(PickRequest.EXTRA_VERB, VERB)
            .putExtra(PickRequest.EXTRA_SUBJECT, SUBJECT)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        val fromClip = if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        // The picker sets both for a multiple pick (see `PickAnswer.primary`): the clip is the
        // whole answer, and `data` alone is the single-file case.
        return fromClip.ifEmpty { listOfNotNull(intent.data) }
    }

    const val VERB = "attach"
    const val SUBJECT = "to your report"
}
