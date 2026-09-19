package ai.cbm.capture.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppDomainTest {

    // The codes cbm_app.reporter_reports() returns (backend/migrations/001_app_schema.sql).
    private val serverCodes = listOf("RECEIVED", "ANALYSING", "PHOTO_NEEDED", "OFFICE_NOTIFIED", "AWAITING_FM",
        "NOT_SCHEDULED", "IN_PROGRESS", "FIXED", "ALREADY_REPORTED")

    @Test
    fun `every status the server sends has its own label`() {
        for (code in serverCodes) {
            val status = ReporterStatus.fromCode(code)
            assertEquals(code, status.code, "unmapped: $code")
        }
        assertEquals(serverCodes.size, serverCodes.map { ReporterStatus.fromCode(it).label }.toSet().size)
    }

    @Test
    fun `an unknown or missing code is shown, not dropped`() {
        assertEquals(ReporterStatus.UNKNOWN, ReporterStatus.fromCode("SOMETHING_NEW"))
        assertEquals(ReporterStatus.UNKNOWN, ReporterStatus.fromCode(null))
        assertEquals(ReporterStatus.UNKNOWN, ReporterStatus.fromCode("UNKNOWN"))
    }

    @Test
    fun `only a requested replacement photo asks the reporter to act`() {
        assertEquals(listOf(ReporterStatus.PHOTO_NEEDED), ReporterStatus.entries.filter { it.needsAction })
    }

    @Test
    fun `site code from a typed code or a join link`() {
        assertEquals("ExampleSite0001", SiteCode.parse("  ExampleSite0001 "))
        assertEquals("ExampleSite0001", SiteCode.parse("cbmapp://join?site=ExampleSite0001"))
        assertEquals("abc_def-123", SiteCode.parse("cbmapp://join?x=1&site=abc_def-123"))
        assertEquals("cbmapp://join?site=abc_def-123", SiteCode.link("abc_def-123"))
    }

    @Test
    fun `site code rejects anything else`() {
        assertNull(SiteCode.parse("short"))
        assertNull(SiteCode.parse("has spaces in it"))
        assertNull(SiteCode.parse("https://evil.example/join?site=ExampleSite0001"))
        assertNull(SiteCode.parse("cbmapp://join?site=../../etc"))
        assertNull(SiteCode.parse("cbmapp://join?other=ExampleSite0001"))
        assertTrue(SiteCode.isValid("12345678"))
    }
}
