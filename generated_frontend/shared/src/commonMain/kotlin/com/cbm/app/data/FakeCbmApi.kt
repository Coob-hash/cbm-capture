package com.cbm.app.data

import com.cbm.app.domain.*
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Demo implementation of the App API with realistic site data (Maddaloni
 * Office / ROOM-POC). Replace with an HTTPS client over /v1/* in production;
 * every method maps 1:1 to an endpoint (PRD §9.2).
 */
class FakeCbmApi : CbmApi {

    private val site = Site("ROOM-POC", "Maddaloni Office", "ROOM-POC")
    private fun now() = Clock.System.now().toEpochMilliseconds()
    private fun rid() = (100000 + Random.nextInt(899999)).toString()

    private data class Account(val email: String, val name: String, val memberships: MutableList<Membership>)
    private data class LiveSession(val account: Account, var membershipId: String)

    private val accounts = mutableMapOf<String, Account>()
    private val sessions = mutableMapOf<String, LiveSession>()
    private var membershipSeq = 0
    private var msgSeq = 0

    init {
        fun acc(email: String, name: String, vararg roles: Pair<Role, MembershipStatus>) {
            accounts[email] = Account(email, name, roles.map { (r, s) ->
                Membership("mem-${++membershipSeq}", site, r, s, if (r == Role.TECHNICIAN) name else null)
            }.toMutableList())
        }
        acc("user@cbm.site", "M. Rossi", Role.REPORTER to MembershipStatus.ACTIVE)
        acc("tech@cbm.site", "R. Bianchi", Role.TECHNICIAN to MembershipStatus.ACTIVE)
        acc("fm@cbm.site", "L. Ferri", Role.FM to MembershipStatus.ACTIVE)
        acc("multi@cbm.site", "A. Greco", Role.REPORTER to MembershipStatus.ACTIVE, Role.TECHNICIAN to MembershipStatus.ACTIVE)
    }

    private fun openSession(account: Account, membershipId: String? = null): Session {
        val token = "cbm-$rid$rid"
        val chosen = account.memberships.firstOrNull { it.id == membershipId } ?: account.memberships.first()
        sessions[token] = LiveSession(account, chosen.id)
        return Session(token, account.email, account.name, account.memberships.toList(), chosen, now() + 3_600_000, newDevice = false)
    }

    private fun auth(token: String): LiveSession = sessions[token] ?: throw ApiException("Session expired — sign in again.")
    private fun roleOf(token: String): Role {
        val live = auth(token)
        return live.account.memberships.first { it.id == live.membershipId }.role
    }

    override suspend fun login(email: String, password: String, siteCode: String): Session {
        delay(600)
        if (password.length < 4) throw ApiException("Invalid credentials or unknown account.")
        val account = accounts[email.trim().lowercase()] ?: throw ApiException("Invalid credentials or unknown account.")
        return openSession(account)
    }

    override suspend fun signUp(email: String, password: String, role: Role, siteCode: String): Session {
        delay(700)
        val key = email.trim().lowercase()
        if (!key.contains("@")) throw ApiException("Enter a valid email address.")
        if (password.length < 8) throw ApiException("Password must be at least 8 characters.")
        if (accounts.containsKey(key)) throw ApiException("An account with this email already exists.")
        val status = if (role == Role.FM) MembershipStatus.PENDING else MembershipStatus.ACTIVE
        val name = key.substringBefore("@").replaceFirstChar { it.uppercase() }
        val account = Account(key, name, mutableListOf(Membership("mem-${++membershipSeq}", site, role, status, if (role == Role.TECHNICIAN) name else null)))
        accounts[key] = account
        return openSession(account)
    }

    override suspend fun loginGoogle(idToken: String, role: Role?, siteCode: String): Session {
        delay(600)
        val email = "google.user@cbm.site"
        val account = accounts.getOrPut(email) {
            Account(email, "G. Verdi", mutableListOf(Membership("mem-${++membershipSeq}", site, role ?: Role.REPORTER, if (role == Role.FM) MembershipStatus.PENDING else MembershipStatus.ACTIVE)))
        }
        return openSession(account)
    }

    override suspend fun selectMembership(token: String, membershipId: String): Session {
        delay(250)
        val live = auth(token)
        val m = live.account.memberships.firstOrNull { it.id == membershipId } ?: throw ApiException("Unknown membership.")
        live.membershipId = m.id
        return Session(token, live.account.email, live.account.name, live.account.memberships.toList(), m, now() + 3_600_000, false)
    }

    override suspend fun me(token: String): Session {
        delay(250)
        val live = auth(token)
        val m = live.account.memberships.first { it.id == live.membershipId }
        return Session(token, live.account.email, live.account.name, live.account.memberships.toList(), m, now() + 3_600_000, false)
    }

    override suspend fun logout(token: String) {
        delay(150)
        sessions.remove(token)
    }

    // ---------------- Reporter ----------------

    private val reports = mutableListOf(
        ReportItem("r-101", "Door, 1st floor", "Office wing", ReporterStatus.IN_PROGRESS, null, "TODAY 08:41"),
        ReportItem("r-102", "Radiator, room 3", "Room 3", ReporterStatus.PHOTO_NEEDED, 2, "YESTERDAY 17:02", "The photo could not be located automatically."),
        ReportItem("r-103", "Stairwell light, GF", "Stair A", ReporterStatus.FIXED, null, "12 SEP 16:20"),
        ReportItem("r-104", "Window handle, 2F", "Meeting room", ReporterStatus.NOT_SCHEDULED, null, "10 SEP 09:14", "Reason shared by FM: planned with the winter works."),
    )

    override suspend fun myReports(token: String): List<ReportItem> {
        auth(token); delay(400)
        return reports.toList()
    }

    override suspend fun submitCapture(token: String, reportId: String, description: String?, replacementOf: String?): ReportItem {
        auth(token); delay(900)
        val idx = reports.indexOfFirst { it.id == (replacementOf ?: reportId) }
        return if (idx >= 0) {
            val updated = reports[idx].copy(status = ReporterStatus.ANALYSING, attemptsLeft = null, updatedLabel = "JUST NOW", note = null)
            reports[idx] = updated
            updated
        } else {
            val item = ReportItem(reportId, description?.take(42)?.ifBlank { "Photo report" } ?: "Photo report", null, ReporterStatus.ANALYSING, null, "JUST NOW")
            reports.add(0, item)
            item
        }
    }

    // ---------------- Technician ----------------

    private var skills = mutableSetOf<Trade>()
    private val offers = mutableListOf(
        Offer("of-57", 57, "Radiator, room 3", "Room 3 · 1st floor", "Leak at the valve; dripping steadily onto the floor.", 3, Trade.PLUMBING, "Tue 09:00–12:00", now() + 3 * 3_600_000 + 12 * 60_000, "cap-57"),
        Offer("of-61", 61, "AHU filter, roof", "Roof plant room", "Filter pressure drop above limit; replace panel filter.", 2, Trade.HVAC, "Wed 08:00–10:00", now() - 3_600_000, null, OfferState.EXPIRED),
    )
    private val toReport = mutableListOf(
        TechJob(42, "Door handle, 1F", "Office wing · 1st floor", JobStage.ASSIGNED, "due Tue", null, "TODAY 07:58"),
        TechJob(38, "Window frame, 2F", "Meeting room · 2nd floor", JobStage.REWORK, null, "Seal still leaking on the left corner.", "YESTERDAY 15:40"),
    )
    private val recentJobs = mutableListOf(
        TechJob(40, "Corridor light, GF", "Stair B · ground floor", JobStage.AWAITING_FM, null, null, "11 SEP 18:03"),
        TechJob(31, "Sink trap, kitchen", "Kitchen · ground floor", JobStage.CLOSED, null, null, "12 SEP 11:26"),
    )

    override suspend fun techBundle(token: String): TechBundle {
        auth(token); delay(400)
        return TechBundle(skills.toSet(), offers.toList(), toReport.toList(), recentJobs.toList())
    }

    override suspend fun respondToOffer(token: String, offerId: String, accept: Boolean): Offer {
        auth(token); delay(500)
        val i = offers.indexOfFirst { it.id == offerId }
        if (i < 0) throw ApiException("Unknown offer.")
        val offer = offers[i]
        if (offer.state != OfferState.OPEN) return offer
        val updated = offer.copy(state = if (accept) OfferState.ACCEPTED else OfferState.DECLINED)
        offers[i] = updated
        if (accept) toReport.add(0, TechJob(offer.ticketId, offer.asset, offer.location, JobStage.ASSIGNED, "slot ${offer.slot}", null, "JUST NOW"))
        return updated
    }

    override suspend fun setSkills(token: String, skills: Set<Trade>): Set<Trade> {
        auth(token); delay(400)
        this.skills.clear(); this.skills.addAll(skills)
        return this.skills.toSet()
    }

    override suspend fun techSummary(token: String, period: String): TechSummary {
        auth(token); delay(300)
        return when (period) {
            "Last month" -> TechSummary(period, 11, 0, 2, 3.1, recentJobs.toList())
            "90 days" -> TechSummary(period, 21, 2, 3, 2.8, recentJobs.toList())
            else -> TechSummary(period, 7, 2, 1, 2.4, recentJobs.toList())
        }
    }

    override suspend fun reportPrefill(token: String, ticketId: Int): TechReport {
        auth(token); delay(300)
        val job = toReport.firstOrNull { it.ticketId == ticketId } ?: throw ApiException("Job not found or not yours.")
        val issue = when (ticketId) {
            42 -> "Handle loose, door does not latch."
            38 -> "Water ingress at the frame corner during rain."
            else -> "See ticket."
        }
        return TechReport(ticketId, "R. Bianchi", "tech@cbm.site", job.asset, job.location, issue)
    }

    override suspend fun submitTechReport(token: String, report: TechReport) {
        auth(token); delay(1000)
        val i = toReport.indexOfFirst { it.ticketId == report.ticketId }
        if (i >= 0) {
            val job = toReport.removeAt(i)
            recentJobs.add(0, job.copy(stage = JobStage.AWAITING_FM, updatedLabel = "JUST NOW"))
        }
    }

    // ---------------- Facility manager ----------------

    private val fmQueue = mutableListOf(
        QueueItem(57, "Radiator leak", "Room 3 · 1st floor", 3, "2h", 0, TicketStatus.PENDING_AUTHORIZATION, "cap-57", "R. Bianchi", false, 1, 3, "Leak at the valve; vision confirms active dripping. Proposed: Tue 09:00–12:00."),
        QueueItem(42, "Door handle, 1F", "Office wing · 1st floor", 2, "1d", 1, TicketStatus.PENDING_APPROVAL, "cap-42", "R. Bianchi", false, 1, 5, "Technician report in: handle replaced, latch realigned. Declares work completed."),
        QueueItem(33, "Window, stairwell", "Stair A · 2nd floor", 4, "6d", 6, TicketStatus.ESCALATED, "cap-33", null, false, 1, 2, "Two offers expired without acceptance. Dispatch escalated."),
        QueueItem(51, "Roof drain", "Roof", 2, "34d", 34, TicketStatus.PENDING_AUTHORIZATION, null, "L. Conti", true, 1, 1, "Partial blockage reported before autumn rains."),
        QueueItem(40, "Corridor light, GF", "Stair B · ground floor", 1, "3d", 3, TicketStatus.PENDING_APPROVAL, "cap-40", "L. Conti", true, 1, 4, "Driver replaced; 20 min burn-in test passed."),
    )
    private val decided = mutableSetOf<String>()

    override suspend fun fmBoard(token: String): FmBoard {
        auth(token); delay(450)
        val k = FmKpis(
            toAuthorize = fmQueue.count { it.status == TicketStatus.PENDING_AUTHORIZATION },
            toApprove = fmQueue.count { it.status == TicketStatus.PENDING_APPROVAL },
            escalated = fmQueue.count { it.status == TicketStatus.ESCALATED },
            overdue30 = 5,
            openTotal = 14,
        )
        return FmBoard(
            k, fmQueue.toList(),
            listOf(
                TicketStatus.PENDING_AUTHORIZATION to k.toAuthorize,
                TicketStatus.DISPATCHING to 2,
                TicketStatus.ASSIGNED to 4,
                TicketStatus.REWORK to 1,
                TicketStatus.PENDING_APPROVAL to k.toApprove,
                TicketStatus.ESCALATED to k.escalated,
            ),
            listOf(WorkloadEntry("R. Bianchi", 3, false), WorkloadEntry("L. Conti", 2, true), WorkloadEntry("A. Greco", 1, false)),
        )
    }

    private fun applyDecision(item: QueueItem, action: Decision): DecisionResult {
        val key = "${item.ticketId}:${item.cycle}:${item.revision}"
        if (key in decided) return DecisionResult(DecisionOutcome.ALREADY_DECIDED, "Ticket #${item.ticketId} was already decided — first decision wins (app, email or chat).")
        decided.add(key)
        fmQueue.remove(item)
        val msg = when (action) {
            Decision.AUTHORIZE -> "Authorization recorded for #${item.ticketId} — WF1 dispatch started."
            Decision.REJECT -> "Rejection recorded for #${item.ticketId} with your reason."
            Decision.APPROVE -> "Completion approved for #${item.ticketId} — WF2 closes the ticket and notifies the technician."
            Decision.REWORK -> "Rework requested for #${item.ticketId} — the technician is notified with your reason."
        }
        return DecisionResult(DecisionOutcome.APPLIED, msg)
    }

    override suspend fun fmDecide(token: String, ticketId: Int, action: Decision, reason: String?, cycle: Int, revision: Int): DecisionResult {
        auth(token); delay(450)
        val item = fmQueue.firstOrNull { it.ticketId == ticketId }
            ?: return DecisionResult(DecisionOutcome.STALE, "Ticket #$ticketId has moved on — the queue was reloaded.")
        if (item.cycle != cycle || item.revision != revision)
            return DecisionResult(DecisionOutcome.STALE, "Ticket #$ticketId has moved on — the queue was reloaded.")
        return applyDecision(item, action)
    }

    override suspend fun fmConfirmProposal(token: String, proposal: Proposal): DecisionResult {
        auth(token); delay(500)
        val item = fmQueue.firstOrNull { it.ticketId == proposal.ticketId }
            ?: return DecisionResult(DecisionOutcome.BLOCKED, "Ticket #${proposal.ticketId} has moved on — the queue was reloaded.")
        return applyDecision(item, proposal.action)
    }

    override suspend fun fmTicket(token: String, ticketId: Int): TicketDetail {
        auth(token); delay(350)
        val item = fmQueue.firstOrNull { it.ticketId == ticketId }
        return TicketDetail(
            ticketId = ticketId,
            asset = item?.asset ?: "Ticket #$ticketId",
            location = item?.location ?: "—",
            issue = item?.summary ?: "No details available.",
            severity = item?.severity ?: 1,
            status = item?.status ?: TicketStatus.CLOSED,
            beforePhotoId = item?.photoId,
            afterPhotoId = if (ticketId == 42 || ticketId == 40) "cap-$ticketId-after" else null,
            techReport = when (ticketId) {
                42 -> "Handle replaced (spindle 8 mm), latch realigned, 20 open/close cycles checked. Materials: 1× handle set. Outcome: COMPLETED."
                40 -> "LED driver replaced; 20-minute burn-in test passed. Outcome: COMPLETED."
                else -> null
            },
            aiAssessment = when (ticketId) {
                42 -> "After-photo consistent with a replaced handle; no anomalies detected."
                57 -> "Vision confirms active dripping at the valve; floor wet ~0.5 m²."
                else -> null
            },
            history = listOf(
                TicketEvent("20 SEP 08:12", "REPORTER", "Photo report received (app capture)."),
                TicketEvent("20 SEP 08:13", "WF1", "Identified asset; severity ${item?.severity ?: 1}."),
                TicketEvent("20 SEP 08:14", "WF1", "Ticket created — ${item?.status?.name ?: "CLOSED"}."),
            ),
        )
    }

    override suspend fun fmChat(token: String, text: String): ChatMsg {
        auth(token); delay(700)
        val lower = text.lowercase()
        val number = Regex("#?(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val wantsAction = listOf("approve", "authorize", "authorise", "reject", "send back").any { it in lower }
        if (wantsAction && number != null) {
            val item = fmQueue.firstOrNull { it.ticketId == number }
                ?: return ChatMsg("m${++msgSeq}", true, "Ticket #$number is not waiting on a decision — it may have been decided already. The queue above was reloaded.")
            val action = when {
                "reject" in lower -> Decision.REJECT
                "send back" in lower -> Decision.REWORK
                item.status == TicketStatus.PENDING_APPROVAL -> Decision.APPROVE
                else -> Decision.AUTHORIZE
            }
            return ChatMsg("m${++msgSeq}", true, "${action.label} intervention #$number? ${item.asset} — ${item.summary}", Proposal("p${++msgSeq}", action, number))
        }
        if (number != null) {
            val item = fmQueue.firstOrNull { it.ticketId == number }
            return if (item != null) ChatMsg("m${++msgSeq}", true, "#$number · ${item.asset}, ${item.location}. Severity ${item.severity}. ${item.summary}")
            else ChatMsg("m${++msgSeq}", true, "I can't see ticket #$number in this site's queue.")
        }
        if ("how many" in lower || "status" in lower || "open" in lower) {
            val counts = listOf(
                "awaiting authorization" to fmQueue.count { it.status == TicketStatus.PENDING_AUTHORIZATION },
                "dispatching" to 2,
                "assigned" to 4,
                "rework" to 1,
                "completion approval" to fmQueue.count { it.status == TicketStatus.PENDING_APPROVAL },
                "escalated" to fmQueue.count { it.status == TicketStatus.ESCALATED },
            )
            return ChatMsg("m${++msgSeq}", true, "Open tickets: " + counts.joinToString(", ") { "${it.first}: ${it.second}" } + ". Same numbers as the dashboard above.")
        }
        return ChatMsg("m${++msgSeq}", true, "I can look up tickets, summarise the building or prepare a decision. Try: \"What's wrong with #57?\" or \"Approve #42\".")
    }

    // ---------------- Notifications ----------------

    override suspend fun notifications(token: String): List<Notice> {
        val r = roleOf(token); delay(250)
        return when (r) {
            Role.REPORTER -> listOf(
                Notice("n1", "Another photo needed", "Radiator, room 3 — the photo could not be located automatically.", "TODAY 07:58"),
                Notice("n2", "Report accepted for work", "Door, 1st floor is being fixed.", "YESTERDAY 16:40", read = true),
                Notice("n3", "Fixed ✓", "Stairwell light, GF — marked fixed.", "12 SEP 16:20", read = true),
            )
            Role.TECHNICIAN -> listOf(
                Notice("n1", "New offer — #57", "Radiator leak, room 3 · SEV 3 · plumbing · Tue 09:00–12:00.", "08:02"),
                Notice("n2", "Offer expiring soon", "#57 expires in about 3 hours.", "08:30"),
                Notice("n3", "Rework requested — #38", "FM: \"Seal still leaking on the left corner.\"", "YESTERDAY 15:40", read = true),
            )
            Role.FM -> listOf(
                Notice("n1", "New intervention to authorize", "#57 Radiator leak · SEV 3 · room 3.", "08:14"),
                Notice("n2", "Completion report ready", "#42 Door handle, 1F — technician declared completed.", "08:56"),
                Notice("n3", "Ticket escalated", "#33 Window, stairwell — two offers expired.", "YESTERDAY 18:02", read = true),
            )
        }
    }
}
