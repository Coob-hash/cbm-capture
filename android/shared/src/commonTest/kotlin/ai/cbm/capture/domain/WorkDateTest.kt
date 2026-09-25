package ai.cbm.capture.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Audit 2026-09-24, finding 9: the form offers Send only for a date the server accepts. */
class WorkDateTest {

    @Test
    fun `real days are accepted`() {
        for (d in listOf("2026-09-22", "2026-01-31", "2028-02-29", "2000-02-29", "0001-01-01", "9999-12-31")) {
            assertTrue(WorkDate.isValid(d), d)
        }
    }

    @Test
    fun `days that do not exist are refused`() {
        for (d in listOf("2026-99-99", "2026-02-29", "1900-02-29", "2026-04-31", "2026-13-01", "2026-00-10",
            "2026-09-00", "0000-01-01")) {
            assertFalse(WorkDate.isValid(d), d)
        }
    }

    @Test
    fun `only the YYYY-MM-DD shape`() {
        for (d in listOf("", "22-09-2026", "2026-9-22", "2026/09/22", "20260922", " 2026-09-22", "2026-09-22T10:00")) {
            assertFalse(WorkDate.isValid(d), d)
        }
    }
}
