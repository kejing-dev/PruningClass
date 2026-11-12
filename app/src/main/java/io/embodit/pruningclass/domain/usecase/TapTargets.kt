package io.embodit.pruningclass.domain.usecase

import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.domain.tree.Branch
import io.embodit.pruningclass.domain.tree.Species
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Size, spacing and paging of every tappable 3D target (QA round 4 q4-1, profile D17 as relaxed by the user on
 * 2026-11-11: edge >= 4 cm, >= 2 degrees at the real viewing distance, gaps >= 1.5 cm and >= 0.3 x the larger target).
 * Design `rules.judge.tapTargets`. Pure Kotlin, unit-tested (TapTargetsTest); PcScene only places what this returns.
 */
object TapTargets {
    /** The rule's floor, and the design's values (a margin above it). */
    const val RULE_EDGE = 0.04f
    const val RULE_DEG = 2f
    const val RULE_GAP = 0.015f
    const val RULE_GAP_RATIO = 0.3f

    const val MIN_EDGE = 0.045f
    const val DEG = 2.3f
    const val MIN_GAP = 0.02f
    const val GAP_RATIO = 0.38f

    /** Rings shown at once; with more, one "more" slot below and one above, a page step of [STEP]. */
    const val PAGE = 7
    const val STEP = 6

    /** Scissors ghost hit box, in ring edges, and its gap to the column. */
    const val SCISSORS_W = 1.6f
    const val SCISSORS_H = 2.6f
    const val SCISSORS_GAP = 0.8f

    private fun rad(deg: Float) = deg * (Math.PI.toFloat() / 180f)

    private fun deg(rad: Float) = rad * (180f / Math.PI.toFloat())

    /** Edge of a ring seen from [dist] metres. */
    fun edge(dist: Float): Float = max(MIN_EDGE, dist * tan(rad(DEG)))

    fun gap(edge: Float): Float = max(MIN_GAP, GAP_RATIO * edge)

    fun pitch(edge: Float): Float = edge + gap(edge)

    /** Angle a face of [size] spans from [dist] (the faces turn to the eye, so no foreshortening). */
    fun angleDeg(size: Float, dist: Float): Float = deg(2f * atan(size / 2f / dist))

    /** Slots in a column for [total] entries (the "more" slots included). */
    fun slots(total: Int): Int = if (total > PAGE) PAGE + 2 else total

    /**
     * One edge for the whole column: from the farthest slot (the ends are farther than the middle), so every ring keeps
     * the angle. [centreDist] = eye to the column's middle.
     */
    fun columnEdge(centreDist: Float, total: Int): Float {
        var u = edge(centreDist)
        repeat(4) {
            val half = (slots(total) - 1) * pitch(u) / 2f
            u = edge(sqrt(centreDist * centreDist + half * half))
        }
        return u
    }

    /**
     * Column slot centres, bottom up, from its middle [centre] along [up] (unit). The column lies in the plane that faces
     * the eye ([columnUp]): a plain vertical column seen from 30 degrees above shortens every gap by cos 30.
     */
    fun slotCentres(centre: Vec3, total: Int, edge: Float, up: Vec3 = Vec3.UP): List<Vec3> {
        val n = slots(total)
        val p = pitch(edge)
        return List(n) { centre + up * ((it - (n - 1) / 2f) * p) }
    }

    /** World up made square to the line from [centre] to [eye]. */
    fun columnUp(centre: Vec3, eye: Vec3): Vec3 {
        val v = (eye - centre).normalized()
        return Vec3.UP.rejectFrom(v).normalized()
    }

    // ---- paging -------------------------------------------------------------------------------

    fun paged(total: Int) = total > PAGE

    fun hasDown(start: Int) = start > 0

    fun hasUp(total: Int, start: Int) = paged(total) && start + PAGE < total

    fun pageUp(total: Int, start: Int): Int = min(start + STEP, max(0, total - PAGE))

    fun pageDown(start: Int): Int = max(0, start - STEP)

    /** The page start that keeps entry [focus] (index into the full list) shown; [start] when it already is. */
    fun follow(total: Int, start: Int, focus: Int?): Int {
        if (!paged(total)) return 0
        val s = start.coerceIn(0, total - PAGE)
        val f = focus ?: return s
        return when {
            f < s -> max(0, f - (PAGE - 2))
            f >= s + PAGE -> min(total - PAGE, f - 1)
            else -> s
        }
    }

    /** What each slot shows, bottom up: an index into the full list, or [MORE_DOWN] / [MORE_UP] / [EMPTY]. */
    fun page(total: Int, start: Int): List<Int> {
        if (!paged(total)) return List(total) { it }
        val s = start.coerceIn(0, total - PAGE)
        return listOf(if (hasDown(s)) MORE_DOWN else EMPTY) + List(PAGE) { s + it } + (if (hasUp(total, s)) MORE_UP else EMPTY)
    }

    const val MORE_DOWN = -2
    const val MORE_UP = -3
    const val EMPTY = -4

    // ---- scissors -----------------------------------------------------------------------------

    /** Scissors hit box centre: right of the column ([right] = unit, horizontal), level with the previewed ring. */
    fun scissorsCentre(ring: Vec3, right: Vec3, edge: Float): Vec3 = ring + right * (edge / 2f + SCISSORS_GAP * edge + SCISSORS_W * edge / 2f)

    // ---- branches: the crown is one target, the hit point picks -------------------------------

    /** Capsule radius of a branch's tap body (>= 2 degrees thick at the default distance of each tree). */
    fun branchRadius(woodRadius: Float, species: Species): Float = max(woodRadius + 0.015f, if (species == Species.ROSE) 0.025f else 0.05f)

    /** Branches whose (shown) centre line passes within this of the hit point are "close together" there. */
    fun pickRadius(dist: Float): Float = max(0.06f, dist * tan(rad(2.5f)))

    /**
     * Branches near [hit] (tree-local), nearest first. [shownTo] = how far along each branch is still there (null = gone).
     * [tapped] is always in (its body was hit). With [eye] (tree-local) "near" is as the eye sees it: a branch whose centre
     * line passes within the angle [radius] spans at the hit point, in front of the hit or behind it (a branch hidden
     * behind the tapped one is offered too, not silently passed over); without it, plain distance to the hit point.
     */
    fun candidates(branches: List<Branch>, shownTo: (Branch) -> Float?, hit: Vec3, radius: Float, tapped: Int?, eye: Vec3? = null): List<Int> {
        val toHit = eye?.let { hit - it }
        val hitDist = toHit?.length ?: 0f
        val limit = if (toHit != null && hitDist > 1e-4f) atan(radius / hitDist) else 0f
        val near = ArrayList<Pair<Int, Float>>()
        for (b in branches) {
            val end = shownTo(b) ?: continue
            var best = Float.MAX_VALUE
            var s = 0f
            while (true) {
                val q = b.pointAt(min(s, end))
                best = min(best, if (toHit != null && hitDist > 1e-4f) seenAngle(q - eye!!, toHit) else q.distanceTo(hit))
                if (s >= end) break
                s += 0.01f
            }
            val inside = if (toHit != null && hitDist > 1e-4f) best <= limit else best <= radius
            if (inside || b.id == tapped) near += b.id to best
        }
        return near.sortedBy { it.second }.map { it.first }
    }

    private fun seenAngle(a: Vec3, b: Vec3): Float {
        val la = a.length
        val lb = b.length
        if (la < 1e-5f || lb < 1e-5f) return 0f
        return acos((a.dot(b) / (la * lb)).coerceIn(-1f, 1f))
    }
}
