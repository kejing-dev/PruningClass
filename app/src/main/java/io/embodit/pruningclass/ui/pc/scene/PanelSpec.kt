package io.embodit.pruningclass.ui.pc.scene

/**
 * Ids and dp sizes of the Stage's attachment panels; the Compose side declares exactly these, once. Every other 2D page
 * is in the main system window (rework 2026-11-02): only the explain card beside a cut and the "open panel" chip stay on
 * the Stage, both placed by the scene and never dragged.
 */
object PanelSpec {
    const val EXPLAIN = "explainCard"
    const val OPEN = "openWindowChip"

    val sizes: Map<String, Pair<Float, Float>> =
        mapOf(
            EXPLAIN to (400f to 220f),
            OPEN to (240f to 72f),
        )

    /** Shown at 0.75: 1 dp = 0.6 mm (design `container.notes`). */
    const val SCALE = 0.75f

    val pool: List<String> = sizes.keys.toList()

    fun size(id: String): Pair<Float, Float> = sizes.getValue(id)

    val M_PER_DP: Float
        get() = PanelRig.DP_M * SCALE
}
