package com.cbm.app.domain

enum class Role { REPORTER, TECHNICIAN, FM }
enum class MembershipStatus { PENDING, ACTIVE }

data class Site(val id: String, val name: String, val code: String)
data class Membership(val id: String, val site: Site, val role: Role, val status: MembershipStatus, val technicianName: String? = null)
data class Session(
    val token: String,
    val email: String,
    val displayName: String,
    val memberships: List<Membership>,
    val active: Membership,
    val expiresAtMillis: Long,
    val newDevice: Boolean,
)

/** Plain-language reporter status codes returned by cbm_app.reporter_reports() (R-4). */
enum class ReporterStatus { QUEUED, UPLOADING, RECEIVED, ANALYSING, PHOTO_NEEDED, OFFICE_NOTIFIED, AWAITING_FM, NOT_SCHEDULED, IN_PROGRESS, FIXED, ALREADY_REPORTED }

data class ReportItem(
    val id: String,
    val title: String,
    val location: String?,
    val status: ReporterStatus,
    val attemptsLeft: Int?,
    val updatedLabel: String,
    val note: String? = null,
)

enum class Trade(val label: String) { PLUMBING("Plumbing"), ELECTRICAL("Electrical"), HVAC("HVAC"), CARPENTRY("Carpentry"), GENERAL("General") }

enum class OfferState { OPEN, EXPIRED, WITHDRAWN, WON_BY_OTHER, ACCEPTED, DECLINED }

data class Offer(
    val id: String, val ticketId: Int, val asset: String, val location: String, val issue: String,
    val severity: Int, val skill: Trade, val slot: String, val expiresAtMillis: Long,
    val photoId: String?, val state: OfferState = OfferState.OPEN,
)

enum class JobStage { ASSIGNED, REWORK, AWAITING_FM, CLOSED }

data class TechJob(val ticketId: Int, val asset: String, val location: String, val stage: JobStage, val dueLabel: String?, val reworkReason: String?, val updatedLabel: String)

data class TechSummary(val period: String, val completed: Int, val awaitingFm: Int, val rework: Int, val avgDays: Double, val recent: List<TechJob>)

data class TechBundle(val skills: Set<Trade>, val offers: List<Offer>, val toReport: List<TechJob>, val recent: List<TechJob>)

enum class TicketStatus(val label: String) {
    PENDING_AUTHORIZATION("Awaiting authorization"),
    LOCALIZED("Localized"),
    DISPATCHING("Dispatching"),
    ASSIGNED("Assigned"),
    REWORK("Rework"),
    PENDING_APPROVAL("Completion approval"),
    ESCALATED("Escalated"),
    CLOSED("Closed"),
    REJECTED("Rejected"),
}

enum class Decision(val label: String, val needsReason: Boolean) {
    AUTHORIZE("Authorize", false),
    REJECT("Reject", true),
    APPROVE("Approve completion", false),
    REWORK("Send back", true),
}

enum class DecisionOutcome { APPLIED, ALREADY_DECIDED, STALE, BLOCKED }
data class DecisionResult(val outcome: DecisionOutcome, val message: String)

enum class FmFilter { AUTHORIZE, APPROVE, ESCALATED, OVERDUE }

data class QueueItem(
    val ticketId: Int, val asset: String, val location: String, val severity: Int,
    val ageLabel: String, val ageDays: Int, val status: TicketStatus, val photoId: String?,
    val technician: String?, val techFirstJob: Boolean,
    val cycle: Int, val revision: Int, val summary: String,
)

data class FmKpis(val toAuthorize: Int, val toApprove: Int, val escalated: Int, val overdue30: Int, val openTotal: Int)
data class WorkloadEntry(val name: String, val openJobs: Int, val firstJob: Boolean)
data class FmBoard(val kpis: FmKpis, val queue: List<QueueItem>, val openByStatus: List<Pair<TicketStatus, Int>>, val workload: List<WorkloadEntry>)

data class TicketEvent(val at: String, val actor: String, val text: String)
data class TicketDetail(
    val ticketId: Int, val asset: String, val location: String, val issue: String, val severity: Int,
    val status: TicketStatus, val beforePhotoId: String?, val afterPhotoId: String?,
    val techReport: String?, val aiAssessment: String?, val history: List<TicketEvent>,
)

enum class Outcome(val label: String) { COMPLETED("I declare the work completed"), PARTIAL("Partially completed"), NOT_COMPLETED("Not completed") }

data class TechReport(
    val ticketId: Int, val technicianName: String, val technicianEmail: String,
    val asset: String, val location: String, val reportedIssue: String,
    val workDate: String = "", val findings: String = "", val workPerformed: String = "",
    val materials: String = "", val checks: String = "", val checkResult: String = "",
    val outcome: Outcome? = null, val remainingIssues: String = "",
    val declaration: Boolean = false, val afterPhotoCaption: String = "",
)

enum class ProposalState { PENDING, CONFIRMED, CANCELLED, BLOCKED }
data class Proposal(val id: String, val action: Decision, val ticketId: Int, val state: ProposalState = ProposalState.PENDING, val outcome: String? = null)
data class ChatMsg(val id: String, val fromAgent: Boolean, val text: String, val proposal: Proposal? = null)

data class Notice(val id: String, val title: String, val body: String, val at: String, val read: Boolean = false)
