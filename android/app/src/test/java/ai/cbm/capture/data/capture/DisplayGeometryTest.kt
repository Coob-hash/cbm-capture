package ai.cbm.capture.data.capture

import ai.cbm.capture.data.capture.DisplayGeometry.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 28 Sep 2026: the first AR session of the app drew the camera as noise until the first photo.
 * ARCore had no display geometry until a tap gave it one. The session now gets it before the first
 * frame that is drawn, and again after every change - never waiting for a tap.
 */
class DisplayGeometryTest {

    @Test
    fun `the surface's size alone is enough for the first frame`() {
        val g = DisplayGeometry()
        assertNull("no size known yet: nothing to give", g.takeChange())
        g.setSize(1080, 2270)
        assertEquals(Value(0, 1080, 2270), g.takeChange())
        assertNull("given once", g.takeChange())
    }

    @Test
    fun `the view before layout has no size and changes nothing`() {
        val g = DisplayGeometry()
        g.set(0, 0, 0)
        assertNull(g.takeChange())
        g.set(0, 1080, 2270)
        assertEquals(Value(0, 1080, 2270), g.takeChange())
    }

    @Test
    fun `a new session gets the geometry again, though nothing changed`() {
        val g = DisplayGeometry()
        g.setSize(1080, 2270)
        g.takeChange()
        g.invalidate()
        assertEquals(Value(0, 1080, 2270), g.takeChange())
    }

    @Test
    fun `a rotation or a new size is passed on, the same values are not`() {
        val g = DisplayGeometry()
        g.setSize(1080, 2270)
        g.takeChange()
        g.set(0, 1080, 2270)
        assertNull("the tap repeats what the session has", g.takeChange())
        g.set(1, 1080, 2270)
        assertEquals(Value(1, 1080, 2270), g.takeChange())
        g.setSize(2270, 1080)
        assertEquals(Value(1, 2270, 1080), g.takeChange())
    }

    @Test
    fun `a rotation known before the size waits for the size`() {
        val g = DisplayGeometry()
        g.set(3, 0, 0)
        assertNull(g.takeChange())
        g.setSize(1080, 2270)
        assertEquals(Value(3, 1080, 2270), g.takeChange())
    }
}
