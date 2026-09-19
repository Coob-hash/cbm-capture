package ai.cbm.capture.domain

/**
 * The reporter-facing meaning of a report status code (PRD § 5, R-4).
 *
 * The database decides the code (cbm_app.reporter_reports); the app only turns it into words. An
 * unknown code - a newer server - is shown as "Being handled" rather than hidden or crashing.
 */
enum class ReporterStatus(val code: String, val label: String, val needsAction: Boolean = false, val done: Boolean = false) {
    RECEIVED("RECEIVED", "Received"),
    ANALYSING("ANALYSING", "Received — being analysed"),
    PHOTO_NEEDED("PHOTO_NEEDED", "Please take another photo", needsAction = true),
    OFFICE_NOTIFIED("OFFICE_NOTIFIED", "Could not be located automatically — the office has been told"),
    AWAITING_FM("AWAITING_FM", "Waiting for the facility manager"),
    NOT_SCHEDULED("NOT_SCHEDULED", "Not scheduled", done = true),
    IN_PROGRESS("IN_PROGRESS", "Being fixed"),
    FIXED("FIXED", "Fixed", done = true),
    ALREADY_REPORTED("ALREADY_REPORTED", "Already reported — being handled"),
    UNKNOWN("UNKNOWN", "Being handled");

    companion object {
        fun fromCode(code: String?): ReporterStatus = entries.firstOrNull { it.code == code && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * The site code that joins an account to a building. It arrives from the site's QR poster as a link
 * (cbmapp://join?site=CODE, opened by the phone's camera app) or is typed from the poster.
 */
object SiteCode {
    private val CODE = Regex("^[A-Za-z0-9_-]{8,64}$")
    const val SCHEME = "cbmapp"

    fun isValid(code: String): Boolean = CODE.matches(code)

    /** Accepts a bare code or a join link; returns the code, or null if neither. */
    fun parse(input: String): String? {
        val text = input.trim()
        if (isValid(text)) return text
        val prefix = "$SCHEME://join?"
        if (!text.startsWith(prefix, ignoreCase = true)) return null
        val code = text.substring(prefix.length).split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "site" }
            ?.get(1)
        return code?.takeIf { isValid(it) }
    }

    fun link(code: String): String = "$SCHEME://join?site=$code"
}
