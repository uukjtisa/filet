package dev.niccc2007.filet.pick

/**
 * What the pick card says, for any operation, in either direction.
 *
 * ## Why the wording is a function and not a string per caller
 *
 * There were two vocabularies. A pick Filet started carried a free-text `title` that each of
 * eleven callers wrote in its own voice - "Extract archive.zip to", "Choose a picture for
 * song.mp3", "Share this folder" - while a pick another app started had no title at all, only a
 * phrase describing the TYPES it would accept ("an image", "one of 3 file types"). So the same
 * card had to read two different ways and neither of them named the operation and its subject
 * in the same shape.
 *
 * One sentence, built from four facts: what is wanted, what the operation is, what it is being
 * done to, and who asked. Every caller supplies the facts; none of them writes the sentence.
 * That is what makes a new operation read like the existing ones instead of like whatever its
 * author typed.
 *
 * Pure, and tested, because the wording IS the feature here - a picker whose sentence does not
 * say what pressing the button will do is the thing being fixed.
 */
data class PickSentence(
    /** "Choose a folder to extract Photos.zip into". */
    val headline: String,
    /** What to do next, when nothing is chosen yet. */
    val hint: String,
    /** The button: "Extract into this", "Use these 3", "Use this file". */
    val confirm: String,
) {
    companion object {

        /**
         * @param want one file, several, or the folder being stood in.
         * @param verb what the operation does, in the imperative: "extract", "save", "share".
         *   Blank for a plain choose-a-file.
         * @param subject what the operation is being done to or with: an archive's name, a
         *   song's name. Blank when there is nothing to name.
         * @param asker the app that asked, when Filet is somebody else's picker. Null when
         *   Filet asked itself - the two are mutually exclusive by construction.
         * @param whatQualifies the kinds of file that can be taken: "an image", "a zip".
         */
        fun of(
            want: PickSpec.Want,
            verb: String,
            subject: String,
            asker: String?,
            whatQualifies: String,
        ): PickSentence {
            val kind = whatQualifies.ifBlank { "a file" }
            return when (want) {
                PickSpec.Want.FOLDER -> PickSentence(
                    headline = buildString {
                        append("Choose a folder")
                        if (verb.isNotBlank()) {
                            append(" to ").append(verb)
                            if (subject.isNotBlank()) append(' ').append(subject)
                        } else if (subject.isNotBlank()) {
                            append(" for ").append(subject)
                        }
                    },
                    hint = "Open the folder you want, then confirm",
                    confirm = if (verb.isNotBlank()) {
                        verb.replaceFirstChar { it.uppercase() } + " into this"
                    } else {
                        "Use this folder"
                    },
                )

                PickSpec.Want.SAVE -> PickSentence(
                    headline = buildString {
                        append("Choose where to save")
                        if (subject.isNotBlank()) append(' ').append(subject)
                    },
                    hint = "Open a folder and name the file",
                    confirm = "Save here",
                )

                PickSpec.Want.FILE -> PickSentence(
                    headline = headlineForFiles(one = true, kind = kind, verb = verb, subject = subject, asker = asker),
                    hint = "Tap $kind",
                    confirm = "Use this one",
                )

                PickSpec.Want.FILES -> PickSentence(
                    headline = headlineForFiles(one = false, kind = kind, verb = verb, subject = subject, asker = asker),
                    hint = "Hold a file to select — one or more",
                    confirm = "Use these",
                )
            }
        }

        /**
         * The file cases, which differ only in who the sentence is about.
         *
         * An outside app is named rather than described: "Choose a file to submit —
         * Instagram" tells somebody why their browser has changed, and "Choose an image" does
         * not. The app's own operations name the thing they will act on instead, for the same
         * reason.
         */
        private fun headlineForFiles(
            one: Boolean,
            kind: String,
            verb: String,
            subject: String,
            asker: String?,
        ): String = buildString {
            append(if (one) "Choose " else "Choose files")
            if (one) append(kind)
            when {
                asker != null -> append(" to submit — ").append(asker)
                verb.isNotBlank() -> {
                    append(" to ").append(verb)
                    if (subject.isNotBlank()) append(' ').append(subject)
                }
                subject.isNotBlank() -> append(" for ").append(subject)
            }
        }
    }
}
