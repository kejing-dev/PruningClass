package io.embodit.pruningclass.domain.usecase

import io.embodit.pruningclass.domain.tree.Wood

/**
 * How a branch looks while one is selected (headset feedback 2026-11-16, profile "highlight never covers the own
 * colour": "选中一个枝后，不要整个都高亮白色，这样就看不清要减的颜色了，有tooltip 就已经高亮了").
 * - The selected branch keeps its own wood (dead / diseased / canker colours) or the hint orange; it is only lifted a
 *   little ([LIFT], emissive, <= 20 %). The ladder and the tooltip already say which branch is selected.
 * - The other branches fade to [FADE], except a branch that carries a mark (hint orange, or bad wood): its colour is
 *   what the lesson is about and must not be washed out.
 * Pure Kotlin, unit-tested (HighlightTest).
 */
object Highlight {
    enum class Look { OWN, HINT }

    /** Extra emissive on the selected branch, as a share of its own colour (design: at most 20 %). */
    const val LIFT = 0.15f
    const val FADE = 0.7f

    val markWoods = setOf(Wood.DEAD, Wood.DISEASED, Wood.CANKER)

    /** The material family: [hinted] = the hint / review orange applies to this branch. Selection never changes it. */
    fun look(hinted: Boolean): Look = if (hinted) Look.HINT else Look.OWN

    fun lifted(id: Int, selected: Int?, pruning: Boolean): Boolean = pruning && selected == id

    fun opacity(id: Int, selected: Int?, pruning: Boolean, hinted: Boolean, woods: Collection<Wood>): Float =
        when {
            !pruning || selected == null || selected == id -> 1f
            hinted || woods.any { it in markWoods } -> 1f
            else -> FADE
        }

    /** The colour seen with the lift: [rgb] plus [LIFT] of itself, clamped (same hue, <= 20 % brighter). */
    fun liftRgb(rgb: FloatArray): FloatArray = FloatArray(3) { (rgb[it] * (1f + LIFT)).coerceAtMost(1f) }
}
