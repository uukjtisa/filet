package dev.niccc2007.filet.report

import android.content.Context
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Sends one report. One POST, one answer.
 *
 * Holds no credential of any kind. The request carries the person's own words, what they chose
 * to attach, and two random ids; the server holds the GitHub token and creates the issue. See
 * [ReportForm.ENDPOINT] for why that is the only arrangement that is safe in a public APK.
 */
object ReportClient {

    /**
     * A random id for this install, made the first time a report is sent.
     *
     * Its own small preferences file, not the app's: this screen also runs in the crash process,
     * where the app's object graph must not be built. Random rather than derived from anything on
     * the phone, so it says nothing about the device - it only lets the server count how many
     * reports one install has sent.
     */
    fun installId(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(K_INSTALL, null)?.let { return it }
        val made = UUID.randomUUID().toString()
        sp.edit().putString(K_INSTALL, made).apply()
        return made
    }

    suspend fun send(context: Context, draft: ReportForm.Draft, submissionId: String): ReportForm.Outcome =
        withContext(Dispatchers.IO) {
            if (ReportForm.ENDPOINT.isBlank()) {
                return@withContext ReportForm.Outcome.Failed(
                    "Reporting from inside Filet is not switched on in this build yet. Your report " +
                        "is kept here; Open on GitHub sends it there instead.",
                    retryable = false,
                )
            }
            // A report is somebody's words and their phone's details: never over plain HTTP to
            // the internet. Loopback is the one exception, for a test server reached through
            // `adb reverse`, where the bytes never leave the cable.
            if (!ReportForm.endpointIsSafe(ReportForm.ENDPOINT)) {
                return@withContext ReportForm.Outcome.Failed(
                    "This build's report address is not HTTPS, so nothing was sent.",
                    retryable = false,
                )
            }
            val body = ReportForm.requestJson(draft, installId(context), submissionId) {
                Base64.encodeToString(it, Base64.NO_WRAP)
            }.toByteArray(Charsets.UTF_8)
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(ReportForm.ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    // Long, because a report with pictures is several megabytes on a phone
                    // connection, and timing out after the server filed it is a duplicate issue.
                    readTimeout = 60_000
                    setFixedLengthStreamingMode(body.size)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("User-Agent", "Filet/" + dev.niccc2007.filet.BuildConfig.VERSION_NAME + " Android/" + Build.VERSION.SDK_INT)
                }
                conn.outputStream.use { it.write(body) }
                val status = conn.responseCode
                val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    .orEmpty()
                val json = runCatching { org.json.JSONObject(text) }.getOrNull()
                val err = json?.optJSONObject("error")
                // The fields are read here and the meaning is decided in ReportForm.outcomeOf,
                // which has a test for every row of the server's table.
                ReportForm.outcomeOf(
                    httpStatus = status,
                    issueUrl = json?.optJSONObject("issue")?.optString("url")?.ifEmpty { null },
                    code = err?.optString("code")?.ifEmpty { null },
                    serverMessage = err?.optString("message")?.ifEmpty { null },
                    retryAfter = err?.optLong("retryAfter", -1L)?.takeIf { it > 0 }
                        ?: conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull(),
                )
            } catch (_: java.io.IOException) {
                ReportForm.failureText(null, 0, null)
            } finally {
                conn?.disconnect()
            }
        }

    private const val PREFS = "filet.report"
    private const val K_INSTALL = "installId"
}
