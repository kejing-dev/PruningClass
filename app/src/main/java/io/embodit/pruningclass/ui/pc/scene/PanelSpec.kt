package io.embodit.pruningclass.ui.pc.scene

/**
 * Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once.
 * The design's "one control panel that changes content" is several pooled panels of fixed heights shown one at a time
 * at the same place (a pooled panel draws black where its glass does not reach: factory finding), top edges aligned.
 */
object PanelSpec {
    const val CONTROL = "controlPanel"
    const val WELCOME = "welcomePage"
    const val PICKER = "coursePicker"
    const val RULES = "rulesPage"
    const val RESULT = "resultPage"
    const val CARD = "cardPage"
    const val SETTINGS = "settingsPage"
    const val EXPLAIN = "explainCard"
    const val DIALOG = "blockedDialog"

    val sizes: Map<String, Pair<Float, Float>> =
        mapOf(
            CONTROL to (520f to 364f),
            WELCOME to (520f to 360f),
            PICKER to (520f to 532f),
            RULES to (520f to 420f),
            RESULT to (520f to 740f), // design 660: five review rows of 48 dp (factory minimum)
            CARD to (520f to 600f),
            SETTINGS to (520f to 704f), // design 660: 13 rows of 52 dp (48 dp controls + 4 dp gap)
            EXPLAIN to (400f to 220f),
            DIALOG to (360f to 200f),
        )

    /** Shown at 0.75: 1 dp = 0.6 mm (design `container.notes`). */
    const val SCALE = 0.75f

    val pool: List<String> = sizes.keys.toList()

    /** Panels that stand at the docking place beside the tree (the others stand in front of the user). */
    val docked = setOf(CONTROL, RESULT, SETTINGS)

    fun size(id: String): Pair<Float, Float> = sizes.getValue(id)

    val M_PER_DP: Float
        get() = PanelRig.DP_M * SCALE
}
