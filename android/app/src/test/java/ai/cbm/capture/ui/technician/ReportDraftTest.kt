package ai.cbm.capture.ui.technician

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Second audit 2026-09-24, finding 1: the report form survives Android reclaiming the app. */
class ReportDraftTest {

    private val json = Json

    private val written = ReportFormState(
        ticketId = 12, asset = "Window W-7", workDate = "2026-09-22",
        findings = "Audit-draft-before-camera", workPerformed = "Replaced the seal and refitted the frame.",
        materials = "1 x seal", checks = "Water along the sill", checkResult = "PASSED", outcome = "COMPLETED",
        remainingIssues = "None.", declaration = true,
        photoUri = "content://ai.cbm.capture.photos/report-photos/after-1.jpg", photoCaption = "The new seal"
    )

    /** What the saved state holds is a string: the round trip is the one a recreated app makes. */
    private fun roundTrip(state: ReportFormState, photoPath: String?): ReportDraft =
        json.decodeFromString(ReportDraft.serializer(),
            json.encodeToString(ReportDraft.serializer(), ReportDraft.of(state, photoPath)))

    @Test
    fun `the answers and the photo come back as they were`() {
        val fresh = ReportFormState(ticketId = 12, asset = "Window W-7", workDate = "2026-09-24")
        val restored = roundTrip(written, "/cache/report-photos/after-1.jpg").applyTo(fresh, photoExists = true)
        assertEquals(written, restored)
    }

    @Test
    fun `the job's own details are not taken from the draft`() {
        val reloaded = ReportFormState(ticketId = 12, asset = "Window W-7 (renamed)", reportedIssue = "Leak")
        val restored = roundTrip(written, "/cache/report-photos/after-1.jpg").applyTo(reloaded, photoExists = true)
        assertEquals("Window W-7 (renamed)", restored.asset)
        assertEquals("Leak", restored.reportedIssue)
        assertEquals("Audit-draft-before-camera", restored.findings)
    }

    @Test
    fun `a photo no longer on the phone is not offered`() {
        val restored = roundTrip(written, "/cache/report-photos/after-1.jpg")
            .applyTo(ReportFormState(ticketId = 12), photoExists = false)
        assertNull(restored.photoUri)
        assertEquals("", restored.photoCaption)
        assertEquals("Audit-draft-before-camera", restored.findings)
    }

    @Test
    fun `no photo, no file`() {
        val draft = ReportDraft.of(written.copy(photoUri = null, photoCaption = ""), "/cache/report-photos/stale.jpg")
        assertNull(draft.photoPath)
        assertNull(draft.applyTo(ReportFormState(), photoExists = true).photoUri)
    }
}
