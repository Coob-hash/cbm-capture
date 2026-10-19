package ai.cbm.capture.ui.fm

import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals

/** The assistant's answers: its **bold** is shown bold, and nothing else it wrote is lost. */
class ChatTextTest {

    @Test
    fun boldIsShownBoldWithoutTheStars() {
        val text = withBold("Ticket **42** is **ASSIGNED** to Mario.")
        assertEquals("Ticket 42 is ASSIGNED to Mario.", text.text)
        val bold = text.spanStyles.filter { it.item.fontWeight == FontWeight.SemiBold }.map { text.text.substring(it.start, it.end) }
        assertEquals(listOf("42", "ASSIGNED"), bold)
    }

    @Test
    fun anUnpairedMarkIsLeftAsWritten() {
        assertEquals("2 ** 3 is 8", withBold("2 ** 3 is 8").text)
        assertEquals("a b **c", withBold("**a** b **c").text)
    }

    @Test
    fun plainTextIsUnchanged() {
        val text = withBold("- #41, 12 days\n- #44, 3 days")
        assertEquals("- #41, 12 days\n- #44, 3 days", text.text)
        assertEquals(0, text.spanStyles.size)
    }
}
