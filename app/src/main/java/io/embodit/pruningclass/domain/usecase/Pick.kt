package io.embodit.pruningclass.domain.usecase

import io.embodit.pruningclass.domain.model.Vec3
import kotlin.math.abs

/**
 * What a tap in the tree reaches (QA round 3 q3-1 "pick exactly", round 4 q4-1, headset feedback D52).
 * - D52 (2026-11-12): branch bodies always take taps in the pruning phase. With a branch selected (or the letter rings
 *   up) a tap on another branch picks again, no detour through the window.
 * - q3-1: other branches stand in front of the bud ladder. So a tap that lands on a branch body first looks along the
 *   line it came in on (eye / controller -> hit point): a ring face that line crosses wins, in front of the branch or
 *   behind it. Only a tap whose line crosses no ring goes to the branches (the crown pick, [TapTargets.candidates]).
 * Sizes and spacing: [TapTargets]. Pure Kotlin, unit-tested (PickTest).
 */
object Pick {
    /** What a crown tap leaves: the selected branch (or null) and the letter choice (or null). */
    data class AfterTap(val selected: Int?, val choice: List<Int>?)

    /**
     * A tap on the crown offering the branches [near] (nearest first), with [selected] currently selected (D52: may be
     * another branch): one selectable -> select it (a different branch = picked again); several -> the letters ask;
     * none -> nothing changes.
     */
    fun afterCrownTap(selected: Int?, choice: List<Int>?, near: List<Int>, selectable: (Int) -> Boolean): AfterTap {
        val n = near.filter(selectable)
        return when {
            n.size == 1 -> AfterTap(n[0], null)
            n.size > 1 -> AfterTap(null, n)
            else -> AfterTap(selected, choice)
        }
    }

    /** D52: branch bodies take taps whenever the tree is being pruned (selected or not). */
    fun branchesTappable(selected: Int?, choosing: Boolean = false): Boolean = true

    /**
     * The ring (index into [rings], centres) whose zone the line from [from] through [hit] crosses, nearest first, or
     * null. Rings face [from] (PcScene turns them to the eye). The zone is the ring plus half the gap on every side
     * (D52: the rings win over the branches with more than their drawn square; the zones of a column tile it, so a line
     * through the column is always the nearest ring; the eye that aims is not the head centre, ~3 cm parallax).
     */
    fun ringOnLine(from: Vec3, hit: Vec3, rings: List<Vec3>, edge: Float): Int? =
        faceOnLine(from, hit, rings, TapTargets.pitch(edge) / 2f, TapTargets.pitch(edge) / 2f)

    /** The scissors ghost wins over a branch the same way: its box plus half a gap. */
    fun scissorsOnLine(from: Vec3, hit: Vec3, at: Vec3, edge: Float): Boolean {
        val m = TapTargets.gap(edge) / 2f
        return faceOnLine(from, hit, listOf(at), TapTargets.SCISSORS_W * edge / 2f + m, TapTargets.SCISSORS_H * edge / 2f + m) != null
    }

    private fun faceOnLine(from: Vec3, hit: Vec3, rings: List<Vec3>, halfW: Float, halfH: Float): Int? {
        val toHit = hit - from
        if (toHit.length < 1e-5f) return null
        val d = toHit.normalized()
        var best: Int? = null
        var bestS = Float.MAX_VALUE
        for ((i, c) in rings.withIndex()) {
            val v = c - from
            if (v.length < 1e-5f) continue
            val n = v.normalized()
            val dn = d.dot(n)
            if (dn <= 1e-4f) continue
            val s = v.dot(n) / dn
            val off = from + d * s - c
            val across = Vec3.UP.cross(n).let { if (it.length < 1e-4f) Vec3.RIGHT else it.normalized() }
            val up = n.cross(across)
            if (abs(off.dot(across)) <= halfW && abs(off.dot(up)) <= halfH && s < bestS) {
                best = i
                bestS = s
            }
        }
        return best
    }
}
