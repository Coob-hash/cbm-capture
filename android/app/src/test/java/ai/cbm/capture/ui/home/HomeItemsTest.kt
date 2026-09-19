package ai.cbm.capture.ui.home

import ai.cbm.capture.app.homeFor
import ai.cbm.capture.data.local.OutboxStatus
import ai.cbm.capture.data.session.Session
import ai.cbm.capture.domain.model.IntrinsicsSource
import ai.cbm.capture.domain.model.Membership
import ai.cbm.capture.domain.model.ReportSummary
import ai.cbm.capture.domain.repository.ReportItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class HomeItemsTest {

    private fun local(capture: String, report: String, status: OutboxStatus, error: String? = null) = ReportItem(
        captureId = capture, reportId = report, createdAt = 0, summary = "Door handle", status = status, attemptCount = 0,
        lastError = error, serverStatus = null, thumbnailPath = null, intrinsicsSource = IntrinsicsSource.ARCORE, intrinsicsTrusted = true)

    private fun server(report: String, status: String, left: Int = 3, at: String = "2026-09-19T10:00:00+00:00") =
        ReportSummary(reportId = report, description = null, createdAt = at, attemptsLeft = left, status = status)

    @Test
    fun `photos on the phone come first, then the server's reports newest first`() {
        val items = buildHomeItems(
            listOf(local("c1", "r1", OutboxStatus.QUEUED)),
            listOf(server("r2", "FIXED", at = "2026-09-18T10:00:00+00:00"), server("r3", "ANALYSING")),
            ZoneOffset.UTC
        )
        assertEquals(listOf("local-c1", "server-r3", "server-r2"), items.map { it.key })
        assertEquals("Saved on this phone — waiting to send", items[0].status)
        assertEquals("Report of 19 Sep 2026, 10:00", items[1].title)
        assertEquals(true, items[2].done)
    }

    @Test
    fun `a delivered photo disappears into its report once the server lists it`() {
        val delivered = local("c1", "r1", OutboxStatus.DELIVERED)
        assertEquals(listOf("local-c1"), buildHomeItems(listOf(delivered), emptyList()).map { it.key })
        assertEquals(listOf("server-r1"), buildHomeItems(listOf(delivered), listOf(server("r1", "RECEIVED"))).map { it.key })
    }

    @Test
    fun `another photo is offered only while the server asks and none is already queued`() {
        val asks = buildHomeItems(emptyList(), listOf(server("r1", "PHOTO_NEEDED", left = 2)))
        assertEquals("r1", asks.single().photoNeededFor)
        assertEquals("2 more photos possible", asks.single().detail)
        val queued = buildHomeItems(listOf(local("c2", "r1", OutboxStatus.QUEUED)), listOf(server("r1", "PHOTO_NEEDED", left = 2)))
        assertNull(queued.last().photoNeededFor)
        assertNull(buildHomeItems(emptyList(), listOf(server("r1", "PHOTO_NEEDED", left = 0))).single().photoNeededFor)
    }

    @Test
    fun `a refused photo can be retried or discarded, with the reason shown`() {
        val item = buildHomeItems(listOf(local("c1", "r1", OutboxStatus.REJECTED, "The photo was damaged.")), emptyList()).single()
        assertEquals("c1", item.rejectedCapture)
        assertEquals("The photo was damaged.", item.detail)
    }

    private fun session(vararg memberships: Membership, bound: String? = memberships.singleOrNull()?.id) =
        Session(token = "t", expiresAtEpochSecond = 4_000_000_000, accountId = "a", email = "e", membershipId = bound, memberships = memberships.toList())

    private fun m(id: String, role: String, status: String = "ACTIVE") = Membership(id, "ROOM-POC", "Maddaloni Office", role, status)

    @Test
    fun `each session lands on its role's home`() {
        assertEquals("home", homeFor(session(m("1", "USER"))))
        assertEquals("waiting", homeFor(session(m("1", "USER", "PENDING"))))
        assertEquals("waiting", homeFor(session(m("1", "TECHNICIAN", "PENDING"))))
        assertEquals("waiting", homeFor(session(m("1", "FM"))))
        assertEquals("role", homeFor(session(m("1", "USER"), m("2", "TECHNICIAN"), bound = null)))
        assertEquals("home", homeFor(session(m("1", "USER"), m("2", "TECHNICIAN"), bound = "1")))
    }
}
