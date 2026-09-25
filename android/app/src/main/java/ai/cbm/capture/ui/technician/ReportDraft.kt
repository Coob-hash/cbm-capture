package ai.cbm.capture.ui.technician

import kotlinx.serialization.Serializable

/**
 * What the technician has written on the report form, and the photo attached to it: the part of
 * [ReportFormState] that is theirs.
 *
 * It is kept in the screen's saved state, which Android keeps when it reclaims the app in the
 * background - as it may while the camera app is in front. Without it the form came back empty and
 * the photo just taken was dropped.
 */
@Serializable
data class ReportDraft(
    val workDate: String,
    val findings: String,
    val workPerformed: String,
    val materials: String,
    val checks: String,
    val checkResult: String?,
    val outcome: String?,
    val remainingIssues: String,
    val declaration: Boolean,
    val photoPath: String?,
    val photoUri: String?,
    val photoCaption: String
) {
    /**
     * The form as it was. [photoExists] says whether the attached photo is still on disk: if it is
     * gone, the form comes back without it rather than with a photo that cannot be sent.
     */
    fun applyTo(state: ReportFormState, photoExists: Boolean): ReportFormState {
        val keepPhoto = photoPath != null && photoUri != null && photoExists
        return state.copy(
            workDate = workDate, findings = findings, workPerformed = workPerformed, materials = materials,
            checks = checks, checkResult = checkResult, outcome = outcome, remainingIssues = remainingIssues,
            declaration = declaration,
            photoUri = if (keepPhoto) photoUri else null,
            photoCaption = if (keepPhoto) photoCaption else ""
        )
    }

    companion object {
        fun of(state: ReportFormState, photoPath: String?) = ReportDraft(
            workDate = state.workDate, findings = state.findings, workPerformed = state.workPerformed,
            materials = state.materials, checks = state.checks, checkResult = state.checkResult,
            outcome = state.outcome, remainingIssues = state.remainingIssues, declaration = state.declaration,
            photoPath = photoPath.takeIf { state.photoUri != null }, photoUri = state.photoUri,
            photoCaption = state.photoCaption
        )
    }
}
