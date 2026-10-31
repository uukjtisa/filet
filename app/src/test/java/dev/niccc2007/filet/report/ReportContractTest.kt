package dev.niccc2007.filet.report

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The request the app builds is byte for byte the one the server's contract test files.
 *
 * `report-api/test/contract/app-request.json` is sent through the real handler by
 * `report-api/test/contract.test.mjs`. This builds the same draft here and compares. Change the
 * request and one of the two fails until both sides agree - which is the whole point, because
 * the two drifted apart once with both suites green.
 */
class ReportContractTest {

    private fun golden(): String {
        val candidates = listOf(
            File("../report-api/test/contract/app-request.json"),
            File("report-api/test/contract/app-request.json"),
        )
        return candidates.first { it.isFile }.readText().trimEnd('\n')
    }

    @Test
    fun `the app sends exactly what the server's contract test accepts`() {
        val draft = ReportForm.Draft(
            kind = ReportForm.Kind.BUG,
            title = "Filet closes when I open About",
            description = "Tapping Report a problem closes the app.",
            steps = "1. Open About\n2. Tap Report a problem",
            device = "Phone: HUAWEI NCO-LX1\nAndroid: 12 (API 31)",
            log = ReportForm.Log("run 1", "line one\nline two", 17),
            snapshot = "temp 41C",
        )
        val built = ReportForm.requestJson(draft, installId = "inst-0001", submissionId = "sub-00001") { "" }
        assertEquals(golden(), built)
    }
}
