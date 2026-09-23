package ai.cbm.capture.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the facility manager decides and what the technician works on.
 *
 * These are the App API's own shapes (GET /v1/fm/queue, GET /v1/technician/jobs and the two POSTs
 * next to them), in the API's snake_case. Fields the screens do not draw are simply left out; the
 * client ignores unknown keys.
 */

// ---- Shared pieces of a ticket ------------------------------------------------------------------

@Serializable
data class TicketLocation(
    val asset: String? = null,
    val storey: String? = null,
    @SerialName("ifc_class") val ifcClass: String? = null,
    @SerialName("map_code") val mapCode: String? = null
) {
    /** "Radiator R-3 · Level 1", skipping whatever the workflows could not identify. */
    val line: String get() = listOfNotNull(asset, storey).joinToString(" · ").ifEmpty { "Location not identified" }

    /** The same without the asset, for a card whose title is already the asset. */
    val detail: String get() = listOfNotNull(storey, mapCode).joinToString(" · ").ifEmpty { "Location not identified" }
}

@Serializable
data class TicketPhoto(
    @SerialName("capture_id") val captureId: String? = null,
    @SerialName("before_url") val beforeUrl: String? = null,
    @SerialName("after_url") val afterUrl: String? = null
)

@Serializable
data class TicketWork(
    @SerialName("scheduled_date") val scheduledDate: String? = null,
    @SerialName("scheduled_slot") val scheduledSlot: String? = null,
    @SerialName("report_text") val reportText: String? = null,
    @SerialName("rework_reason") val reworkReason: String? = null,
    @SerialName("closed_at") val closedAt: String? = null
) {
    val slotLine: String? get() = scheduledDate?.let { d -> listOfNotNull(d, scheduledSlot).joinToString(" · ") }
}

// ---- The facility manager's queue ----------------------------------------------------------------

@Serializable
data class TicketReporter(
    val name: String? = null,
    val email: String? = null,
    @SerialName("from_app") val fromApp: Boolean = false
) {
    val label: String get() = name ?: email ?: "Unknown"
}

@Serializable
data class CardTechnician(
    val id: Int,
    val name: String,
    @SerialName("jobs_completed") val jobsCompleted: Int = 0,
    @SerialName("first_job") val firstJob: Boolean = false,
    val rating: Double? = null
)

/**
 * What the workflows say may be decided now, and against which state. The app echoes
 * [approvalId] and [expectedUpdatedAt] back when it decides, so a ticket that moved on meanwhile
 * is refused instead of decided blind.
 */
@Serializable
data class ActionContext(
    @SerialName("approval_id") val approvalId: String? = null,
    @SerialName("expected_updated_at") val expectedUpdatedAt: String? = null,
    @SerialName("allowed_actions") val allowedActions: List<String> = emptyList(),
    @SerialName("completion_decision") val completionDecision: String? = null
) {
    val canAuthorize: Boolean get() = "approve_intervention" in allowedActions
    val canApproveCompletion: Boolean get() = "approve_completion" in allowedActions
    val canRequestRework: Boolean get() = "request_rework" in allowedActions
    val decidable: Boolean get() = approvalId != null && expectedUpdatedAt != null && allowedActions.isNotEmpty()
}

@Serializable
data class FmCard(
    @SerialName("ticket_id") val ticketId: Int,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    val severity: Int? = null,
    val category: String? = null,
    @SerialName("required_skill") val requiredSkill: String? = null,
    val description: String? = null,
    val location: TicketLocation = TicketLocation(),
    val reporter: TicketReporter = TicketReporter(),
    val photo: TicketPhoto = TicketPhoto(),
    val technician: CardTechnician? = null,
    val work: TicketWork = TicketWork(),
    val action: ActionContext = ActionContext()
) {
    val title: String get() = location.asset ?: category?.replaceFirstChar { it.uppercase() } ?: "Ticket #$ticketId"
}

@Serializable
data class FmCounts(
    @SerialName("awaiting_authorization") val awaitingAuthorization: Int = 0,
    @SerialName("awaiting_approval") val awaitingApproval: Int = 0,
    @SerialName("in_progress") val inProgress: Int = 0,
    val open: Int = 0,
    @SerialName("closed_7d") val closed7d: Int = 0,
    @SerialName("rejected_7d") val rejected7d: Int = 0
)

@Serializable
data class FmQueueResponse(
    @SerialName("site_id") val siteId: String,
    val authorizations: List<FmCard> = emptyList(),
    val completions: List<FmCard> = emptyList(),
    val counts: FmCounts = FmCounts()
)

/** The four decisions, and whether the FM has to say why. */
enum class FmAction(val wire: String, val label: String, val needsReason: Boolean) {
    AUTHORIZE("approve_intervention", "Authorize", false),
    REJECT("reject_intervention", "Reject", true),
    APPROVE("approve_completion", "Approve", false),
    REWORK("request_rework", "Send back", true)
}

@Serializable
data class DecisionRequest(
    @SerialName("ticket_id") val ticketId: Int,
    val action: String,
    val reason: String? = null,
    @SerialName("approval_id") val approvalId: String,
    @SerialName("expected_updated_at") val expectedUpdatedAt: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class DecisionResponse(
    val outcome: String,
    @SerialName("ticket_id") val ticketId: Int,
    @SerialName("ticket_status") val ticketStatus: String? = null,
    /** true: recorded, and WF2's review loop is carrying it out (about a minute). */
    val settling: Boolean = false,
    val card: FmCard? = null
)

// ---- The technician's jobs -------------------------------------------------------------------

@Serializable
data class TechnicianProfile(
    @SerialName("technician_id") val technicianId: Int? = null,
    val name: String? = null,
    val skills: List<String> = emptyList(),
    val zone: String? = null,
    val rating: Double? = null,
    @SerialName("jobs_completed") val jobsCompleted: Int = 0,
    val available: Boolean = true,
    @SerialName("needs_skills") val needsSkills: Boolean = false
)

@Serializable
data class OfferInfo(
    val id: String,
    val date: String? = null,
    val slot: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
)

@Serializable
data class JobCard(
    @SerialName("ticket_id") val ticketId: Int,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    val severity: Int? = null,
    val category: String? = null,
    @SerialName("required_skill") val requiredSkill: String? = null,
    val description: String? = null,
    val location: TicketLocation = TicketLocation(),
    val photo: TicketPhoto = TicketPhoto(),
    val work: TicketWork = TicketWork(),
    val offer: OfferInfo? = null,
    @SerialName("report_needed") val reportNeeded: Boolean = false,
    /** TO_DO · REWORK · WITH_FM · SENT */
    @SerialName("report_state") val reportState: String? = null
) {
    val title: String get() = location.asset ?: category?.replaceFirstChar { it.uppercase() } ?: "Ticket #$ticketId"
}

@Serializable
data class TechnicianJobsResponse(
    val me: TechnicianProfile = TechnicianProfile(),
    @SerialName("skill_catalog") val skillCatalog: List<String> = emptyList(),
    val offers: List<JobCard> = emptyList(),
    val current: List<JobCard> = emptyList(),
    val completed: List<JobCard> = emptyList()
)

@Serializable
data class OfferAnswerRequest(
    @SerialName("ticket_id") val ticketId: Int,
    @SerialName("offer_id") val offerId: String,
    val decision: String
)

@Serializable
data class OfferAnswerResponse(@SerialName("ticket_id") val ticketId: Int, val decision: String)

@Serializable
data class SkillsRequest(val skills: List<String>)

@Serializable
data class SkillsResponse(val skills: List<String> = emptyList())

@Serializable
data class ReportSentResponse(
    @SerialName("report_id") val reportId: String,
    val status: String
)

@Serializable
data class ReportLinkResponse(
    @SerialName("ticket_id") val ticketId: Int,
    val url: String,
    @SerialName("expires_at") val expiresAt: String? = null
)

/** The trades triage asks for; the words dispatch matches against (Q17). */
fun skillLabel(skill: String): String = when (skill) {
    "plumbing" -> "Plumbing"
    "electrical" -> "Electrical"
    "hvac" -> "Heating and cooling"
    "carpentry" -> "Carpentry"
    "general" -> "General maintenance"
    else -> skill.replaceFirstChar { it.uppercase() }
}
