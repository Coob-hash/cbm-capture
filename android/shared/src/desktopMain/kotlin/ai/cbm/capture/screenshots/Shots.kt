package ai.cbm.capture.screenshots

import ai.cbm.capture.domain.model.ActionContext
import ai.cbm.capture.domain.model.CardTechnician
import ai.cbm.capture.domain.model.FmCard
import ai.cbm.capture.domain.model.FmCounts
import ai.cbm.capture.domain.model.JobCard
import ai.cbm.capture.domain.model.Membership
import ai.cbm.capture.domain.model.OfferInfo
import ai.cbm.capture.domain.model.TechnicianProfile
import ai.cbm.capture.domain.model.TicketLocation
import ai.cbm.capture.domain.model.TicketReporter
import ai.cbm.capture.domain.model.TicketWork
import ai.cbm.capture.ui.auth.AuthFormState
import ai.cbm.capture.ui.auth.ChooseRoleScreen
import ai.cbm.capture.ui.auth.LoginScreen
import ai.cbm.capture.ui.auth.SignUpScreen
import ai.cbm.capture.ui.auth.WaitingScreen
import ai.cbm.capture.ui.fm.FmHomeScreen
import ai.cbm.capture.ui.fm.FmUiState
import ai.cbm.capture.ui.reports.HomeItem
import ai.cbm.capture.ui.reports.ReporterHomeScreen
import ai.cbm.capture.ui.reports.ReporterHomeState
import ai.cbm.capture.ui.technician.ReportFormActions
import ai.cbm.capture.ui.technician.ReportFormScreen
import ai.cbm.capture.ui.technician.ReportFormState
import ai.cbm.capture.ui.technician.TechUiState
import ai.cbm.capture.ui.technician.TechnicianHomeScreen
import ai.cbm.capture.ui.theme.CbmCaptureTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Draws every screen to a PNG, at phone size, with sample data in the shape the App API returns.
 *
 * This is a review tool: it renders the same composition Android draws, so the look can be checked
 * without an emulator or a phone. It never talks to a server.
 */
private const val W = 390
private const val H = 844
private const val SCALE = 3f
private val hourFromNow = System.currentTimeMillis() + 57 * 60_000

@OptIn(ExperimentalComposeUiApi::class)
private fun shoot(dir: File, name: String, role: String?, content: @Composable () -> Unit) {
    val scene = ImageComposeScene((W * SCALE).toInt(), (H * SCALE).toInt(), Density(SCALE)) {
        CbmCaptureTheme(role = role, darkTheme = false) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
    try {
        scene.render(0L)
        Thread.sleep(300)                       // the countdowns and the blinking tag settle
        val image = scene.render(600_000_000L)
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("wrote $name.png")
    } finally {
        scene.close()
    }
}

// ---- Sample data, in the API's own shapes ------------------------------------------------------

private fun authorization() = FmCard(
    ticketId = 57,
    status = "PENDING_AUTHORIZATION",
    createdAt = "2026-09-21T08:14:00Z",
    updatedAt = "2026-09-21T08:14:00Z",
    severity = 3,
    category = "plumbing",
    requiredSkill = "plumbing",
    description = "Water under the radiator, the floor has been wet since this morning.",
    location = TicketLocation(asset = "Radiator R-3", storey = "Level 1"),
    reporter = TicketReporter(name = "Giulia De Santis", email = "giulia@example.invalid", fromApp = true),
    action = ActionContext(
        approvalId = "9a1d0c2e-1111-4a3b-8c4d-5e6f70819293",
        expectedUpdatedAt = "2026-09-21T08:14:00Z",
        allowedActions = listOf("approve_intervention", "reject_intervention")
    )
)

private fun completion() = FmCard(
    ticketId = 42,
    status = "PENDING_APPROVAL",
    createdAt = "2026-09-18T09:02:00Z",
    updatedAt = "2026-09-21T07:40:00Z",
    severity = 2,
    category = "carpentry",
    requiredSkill = "carpentry",
    description = "The handle of the meeting room door came off.",
    location = TicketLocation(asset = "Door D-12", storey = "Level 1"),
    technician = CardTechnician(id = 4, name = "Tina Tech", jobsCompleted = 0, firstJob = true),
    work = TicketWork(reportText = "Handle replaced, hinges adjusted and lock tested. 40 min."),
    action = ActionContext(
        approvalId = "44e5f6a7-2222-4b5c-9d0e-1f2a3b4c5d6e",
        expectedUpdatedAt = "2026-09-21T07:40:00Z",
        allowedActions = listOf("approve_completion", "request_rework")
    )
)

private fun offer() = JobCard(
    ticketId = 57,
    status = "DISPATCHING",
    createdAt = "2026-09-21T08:14:00Z",
    severity = 3,
    category = "plumbing",
    requiredSkill = "plumbing",
    description = "Water under the radiator, the floor has been wet since this morning.",
    location = TicketLocation(asset = "Radiator R-3", storey = "Level 1"),
    offer = OfferInfo(id = "offer-57", date = "2026-10-01", slot = "14:00-16:00", expiresAt = isoInHours(3))
)

private fun assigned() = JobCard(
    ticketId = 38,
    status = "REWORK",
    createdAt = "2026-09-15T10:00:00Z",
    severity = 2,
    category = "carpentry",
    description = "Window frame lets water in.",
    location = TicketLocation(asset = "Window W-7", storey = "Level 2"),
    work = TicketWork(
        scheduledDate = "2026-09-22",
        scheduledSlot = "08:00-10:00",
        reworkReason = "The seal is still leaking on the left corner."
    ),
    reportNeeded = true,
    reportState = "REWORK"
)

private fun withFm() = JobCard(
    ticketId = 40,
    status = "PENDING_APPROVAL",
    createdAt = "2026-09-19T17:20:00Z",
    severity = 1,
    category = "electrical",
    description = "Corridor light out.",
    location = TicketLocation(asset = "Lamp L-4", storey = "Ground floor"),
    reportNeeded = false,
    reportState = "WITH_FM"
)

private fun closed() = JobCard(
    ticketId = 31,
    status = "CLOSED",
    createdAt = "2026-09-10T09:00:00Z",
    category = "carpentry",
    location = TicketLocation(asset = "Door closer", storey = "Entrance"),
    work = TicketWork(closedAt = "2026-09-12T11:26:00Z")
)

private fun isoInHours(h: Int): String =
    java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusHours(h.toLong()).toString()

private fun membership(id: String, role: String, status: String = "ACTIVE") =
    Membership(id = id, siteId = "ROOM-POC", siteName = "Maddaloni Office", role = role, status = status)

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val dir = File(args.firstOrNull() ?: "shots").absoluteFile
    dir.mkdirs()

    shoot(dir, "01-login", null) {
        LoginScreen(AuthFormState(email = "giulia@example.invalid", password = "••••••••"), {}, {}, {}, {})
    }
    shoot(dir, "02-signup", null) {
        SignUpScreen(
            AuthFormState(siteCode = "ROOM-POC", email = "tina@example.invalid", password = "••••••••"),
            siteLabel = "Maddaloni Office · ROOM-POC", {}, {}, {}, {}, {}
        )
    }
    shoot(dir, "03-reporter-home", "USER") {
        ReporterHomeScreen(
            state = ReporterHomeState(
                siteName = "Maddaloni Office",
                siteCode = "ROOM-POC",
                expiresAtMillis = hourFromNow,
                items = listOf(
                    HomeItem("1", "Water under the radiator, room 3", "Waiting for the facility manager", "21 Sep 2026, 08:14"),
                    HomeItem("2", "Door handle came off", "Being repaired", "18 Sep 2026, 09:02"),
                    HomeItem("3", "Photo of the window frame", "Not accepted", "The photo could not be located", rejectedCapture = "c1"),
                    HomeItem("4", "Tap dripping, toilets", "Fixed", "9 Sep 2026, 16:20", done = true)
                )
            ),
            onOpenReport = {}, onTakeAnotherPhoto = {}, onRetryUpload = {}, onDiscardUpload = {},
            onRefresh = {}, onSettings = {}, onLogout = {}
        )
    }
    shoot(dir, "04-fm-home", "FM") {
        FmHomeScreen(
            state = FmUiState(
                siteName = "Maddaloni Office",
                siteCode = "ROOM-POC",
                expiresAtMillis = hourFromNow,
                counts = FmCounts(awaitingAuthorization = 1, awaitingApproval = 1, inProgress = 6, open = 8, closed7d = 4),
                authorizations = listOf(authorization()),
                completions = listOf(completion()),
                loading = false
            ),
            onRefresh = {}, onDecide = { _, _, _ -> }, onProfile = {}
        )
    }
    shoot(dir, "05-technician-work", "TECHNICIAN") {
        TechnicianHomeScreen(
            state = technicianState(),
            onRefresh = {}, onAnswerOffer = { _, _ -> }, onSaveSkills = {}, onOpenReport = {}, onProfile = {}
        )
    }
    shoot(dir, "06-technician-skills", "TECHNICIAN") {
        TechnicianHomeScreen(
            state = technicianState().copy(
                me = TechnicianProfile(technicianId = 4, name = "Tina Tech", skills = emptyList(), needsSkills = true),
                loading = false
            ),
            onRefresh = {}, onAnswerOffer = { _, _ -> }, onSaveSkills = {}, onOpenReport = {}, onProfile = {}
        )
    }
    shoot(dir, "07-technician-report", "TECHNICIAN") {
        ReportFormScreen(
            state = ReportFormState(
                ticketId = 38, siteCode = "ROOM-POC", expiresAtMillis = hourFromNow,
                asset = "Window W-7", location = "Level 2", reportedIssue = "Window frame lets water in.",
                technician = "Tina Tech", reworkReason = "The seal is still leaking on the left corner.",
                workDate = "2026-09-22",
                findings = "The seal was perished along the lower edge.",
                workPerformed = "Replaced the seal, refitted the frame and checked that the sash closes flush.",
                materials = "1 x seal, 4 m",
                checks = "Poured water along the sill and watched for ten minutes.",
                checkResult = "PASSED", outcome = "COMPLETED", remainingIssues = "None.", declaration = true
            ),
            actions = ReportFormActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        )
    }
    shoot(dir, "08-choose-role", null) {
        ChooseRoleScreen(
            memberships = listOf(membership("1", "USER"), membership("2", "TECHNICIAN")),
            busy = false, error = null, onPick = {}, onLogout = {}
        )
    }
    shoot(dir, "09-waiting", "FM") {
        WaitingScreen(
            title = "Waiting for approval",
            message = "Your facility manager account for Maddaloni Office must be approved by an " +
                "administrator. Tap Check again later.",
            busy = false, onRefresh = {}, onLogout = {}
        )
    }
    println("screens written to $dir")
}

private fun technicianState() = TechUiState(
    siteCode = "ROOM-POC",
    expiresAtMillis = hourFromNow,
    me = TechnicianProfile(
        technicianId = 4, name = "Tina Tech",
        skills = listOf("plumbing", "carpentry"), jobsCompleted = 7, needsSkills = false
    ),
    catalog = listOf("plumbing", "electrical", "hvac", "carpentry", "general"),
    offers = listOf(offer()),
    current = listOf(assigned(), withFm()),
    completed = listOf(closed()),
    loading = false
)
