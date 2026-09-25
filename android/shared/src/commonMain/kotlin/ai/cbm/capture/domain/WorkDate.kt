package ai.cbm.capture.domain

/**
 * The work date of a technician's report, as the template writes it: YYYY-MM-DD, and a day that
 * exists. The same rule as the server (Python's date.fromisoformat and PostgreSQL's date), so the
 * form offers "Send" only for a date the office will accept - 2026-99-99 and 2026-02-29 are not.
 */
object WorkDate {
    private val SHAPE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    fun isValid(text: String): Boolean {
        val parts = SHAPE.matchEntire(text)?.groupValues ?: return false
        val year = parts[1].toInt()
        val month = parts[2].toInt()
        val day = parts[3].toInt()
        if (year < 1 || month !in 1..12) return false
        return day in 1..daysIn(year, month)
    }

    private fun daysIn(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
}
