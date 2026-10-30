package io.embodit.pruningclass.ui.pc.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import io.embodit.pruningclass.ui.pc.PcEvent
import io.embodit.pruningclass.ui.pc.PcUiState
import java.util.concurrent.ConcurrentHashMap

/**
 * Seams for QA's adb harness. Nothing here is product UI and nothing here does anything by itself:
 * the hooks are only driven by the debug-build receiver (`src/debug`). In a release build no receiver
 * exists, [PcProbe.enabled] stays false and the bounds modifiers are no-ops.
 */
object PcProbe {
    @Volatile var enabled: Boolean = false

    data class ControlBounds(val cx: Float, val cy: Float, val w: Float, val h: Float)

    private val controls = ConcurrentHashMap<String, ConcurrentHashMap<String, ControlBounds>>()
    private val panelSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    fun report(panel: String, name: String, bounds: ControlBounds, panelWidth: Int, panelHeight: Int) {
        controls.getOrPut(panel) { ConcurrentHashMap() }[name] = bounds
        panelSizes[panel] = panelWidth to panelHeight
    }

    fun forget(panel: String, name: String) {
        controls[panel]?.remove(name)
    }

    fun controlsOf(panel: String): Map<String, ControlBounds> = controls[panel]?.toSortedMap() ?: emptyMap()

    fun sizeOf(panel: String): Pair<Int, Int>? = panelSizes[panel]

    /** What the screen exposes to the debug receiver while it is composed. */
    interface Host {
        fun dispatch(event: PcEvent)

        fun uiState(): PcUiState

        fun dumpJson(): String

        fun scene(): SceneProbe?

        /** Presses a panel control by its stable name; false when no such control is composed. */
        fun pressControl(name: String): Boolean
    }

    interface SceneProbe {
        fun poseLine(): String

        fun panelLines(): List<String>

        fun treeLine(): String

        /** Debug `rotate=<deg>`: turns the tree (positive = right, clockwise seen from above). */
        fun rotate(deg: Float)

        /** Debug `dist=<m>`: tree distance from the head (clamped to the design limits). */
        fun distance(m: Float)

        /** Debug `pose`: moves the tree onto the head (the emulator camera cannot be put inside the crown by a command). */
        fun moveTreeOntoHead()

        /** Debug `dockfront=1`: docked panels 0.6 m straight ahead instead of 0.9 m, 50 degrees left (panel close-ups only). */
        fun dockFront(on: Boolean)
    }

    @Volatile var host: Host? = null

    /** Panel controls by name (registered by `Modifier.probe`): the debug command `ui <name>` presses one. */
    private val clicks = ConcurrentHashMap<String, () -> Unit>()

    fun registerClick(name: String, onClick: () -> Unit) {
        clicks[name] = onClick
    }

    fun unregisterClick(name: String) {
        clicks.remove(name)
    }

    fun click(name: String): Boolean {
        val c = clicks[name] ?: return false
        c()
        return true
    }
}

/** Id of the attachment panel whose content is being composed. */
val LocalProbePanel = compositionLocalOf { "" }

/** Registers the on-panel bounds of a control under a stable [name]. A no-op unless the app is debuggable. */
@Composable
fun Modifier.probe(name: String, onClick: (() -> Unit)? = null): Modifier {
    if (!PcProbe.enabled) return this
    val panel = LocalProbePanel.current
    DisposableEffect(panel, name, onClick) {
        if (onClick != null) PcProbe.registerClick(name, onClick)
        onDispose {
            PcProbe.forget(panel, name)
            if (onClick != null) PcProbe.unregisterClick(name)
        }
    }
    return this.onGloballyPositioned { c ->
        if (c.isAttached) {
            val root = c.findRootCoordinates()
            val topLeft = root.localPositionOf(c, Offset.Zero)
            val bottomRight = root.localPositionOf(c, Offset(c.size.width.toFloat(), c.size.height.toFloat()))
            PcProbe.report(
                panel,
                name,
                PcProbe.ControlBounds((topLeft.x + bottomRight.x) / 2f, (topLeft.y + bottomRight.y) / 2f, bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                root.size.width,
                root.size.height,
            )
        }
    }
}
