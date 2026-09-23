package com.cbm.app.data

import com.cbm.app.domain.*

/**
 * The phone talks only to the CBM App API (PRD §9.1). This interface mirrors
 * the /v1 endpoints of PRD §9.2; FakeCbmApi stands in until the HTTPS client
 * is wired in. Every state change goes through the workflows' guarded
 * functions server-side — the app never writes ticket state directly.
 */
interface CbmApi {
    // /v1/auth/*
    suspend fun login(email: String, password: String, siteCode: String): Session
    suspend fun signUp(email: String, password: String, role: Role, siteCode: String): Session
    suspend fun loginGoogle(idToken: String, role: Role?, siteCode: String): Session
    suspend fun selectMembership(token: String, membershipId: String): Session
    suspend fun me(token: String): Session
    suspend fun logout(token: String)

    // Reporter: /v1/reports, /v1/captures
    suspend fun myReports(token: String): List<ReportItem>
    suspend fun submitCapture(token: String, reportId: String, description: String?, replacementOf: String?): ReportItem

    // Technician: /v1/technician/*
    suspend fun techBundle(token: String): TechBundle
    suspend fun respondToOffer(token: String, offerId: String, accept: Boolean): Offer
    suspend fun setSkills(token: String, skills: Set<Trade>): Set<Trade>
    suspend fun techSummary(token: String, period: String): TechSummary
    suspend fun reportPrefill(token: String, ticketId: Int): TechReport
    suspend fun submitTechReport(token: String, report: TechReport)

    // Facility manager: /v1/fm/*
    suspend fun fmBoard(token: String): FmBoard
    suspend fun fmTicket(token: String, ticketId: Int): TicketDetail
    suspend fun fmDecide(token: String, ticketId: Int, action: Decision, reason: String?, cycle: Int, revision: Int): DecisionResult
    suspend fun fmChat(token: String, text: String): ChatMsg
    suspend fun fmConfirmProposal(token: String, proposal: Proposal): DecisionResult

    // /v1/notifications
    suspend fun notifications(token: String): List<Notice>
}

class ApiException(message: String) : Exception(message)
