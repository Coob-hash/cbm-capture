package ai.cbm.capture.data.capture

/**
 * The display geometry ARCore needs before it can draw the camera: the display's rotation and the
 * size of the surface the preview is drawn on.
 *
 * ARCore does not find these out by itself. Until they are given, the texture coordinates it
 * computes for the preview are meaningless and the camera shows as noise. They used to be given
 * only from the view's update (before layout, so with no size) and on a tap: the first AR session
 * of the app showed noise until the first photo (28 Sep 2026). Now the surface's size arrives from
 * the renderer itself, the rotation from the view, and the GL thread hands both to the session
 * before the frame that needs them - the order ARCore's own samples use.
 *
 * Any thread may record; [takeChange] is for the GL thread.
 */
class DisplayGeometry {

    data class Value(val rotation: Int, val width: Int, val height: Int)

    private var rotation = 0
    private var width = 0
    private var height = 0
    private var dirty = true

    /** From the view: its rotation, and its size once it has one (a zero size is ignored). */
    @Synchronized
    fun set(rotation: Int, width: Int, height: Int) {
        if (rotation != this.rotation) { this.rotation = rotation; dirty = true }
        if (width > 0 && height > 0) setSizeLocked(width, height)
    }

    /** From the renderer: the surface was created or resized. */
    @Synchronized
    fun setSize(width: Int, height: Int) {
        if (width > 0 && height > 0) setSizeLocked(width, height)
    }

    /** A new session knows nothing yet: give it the geometry on its first frame. */
    @Synchronized
    fun invalidate() {
        dirty = true
    }

    /** The geometry to give the session now, once per change; null while unchanged or no size is known. */
    @Synchronized
    fun takeChange(): Value? {
        if (!dirty || width <= 0 || height <= 0) return null
        dirty = false
        return Value(rotation, width, height)
    }

    private fun setSizeLocked(width: Int, height: Int) {
        if (width != this.width || height != this.height) {
            this.width = width
            this.height = height
            dirty = true
        }
    }
}
