package ai.cbm.capture.data.capture

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit 2026-09-24, follow-up: a tap waits for the next rendered frame. With no frame coming - the
 * session paused or detached, the camera lost - it used to wait for ever, and the screen with it.
 */
class ArCameraControllerTest {

    @Test
    fun `a capture with no frame to come fails instead of waiting for ever`() = runTest {
        val controller = ArCameraController()   // no session attached: no frame will ever be drawn
        val result = controller.capture(0.5f, 0.5f)
        assertTrue("expected a failure, got $result", result.isFailure)
    }
}
