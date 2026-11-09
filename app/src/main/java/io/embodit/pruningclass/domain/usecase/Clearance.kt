package io.embodit.pruningclass.domain.usecase

import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.domain.tree.Cut
import io.embodit.pruningclass.domain.tree.Judge
import io.embodit.pruningclass.domain.tree.NextYear
import io.embodit.pruningclass.domain.tree.Tree
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.max

/**
 * Factory D41 (2026-11-04, "别遮挡 3D 的"): seen from where the user stands, the main window (with its TabBar capsule
 * above and the system controls below) and every 2D plate keep at least [CLEAR_DEG] of horizontal angle away from the
 * tree, in every phase (next year's growth and the explain card included). The system gives no window pose, so the
 * window is the one the system opens: straight ahead of the head at [WINDOW_DIST], [WINDOW_W_DP] wide, drawn at
 * [WINDOW_M_PER_DP]: measured on this app's emulator shots (2026-11-04: right edge 17.5 degrees off the view, left edge
 * 16.5; sign-hands v0.1.5 measured 0.00173, 2.2 x what `dpToLength` says). Pure Kotlin, unit-tested (ClearanceTest).
 */
object Clearance {
    const val WINDOW_W_DP = 600f
    const val WINDOW_DIST = 1.75f
    const val WINDOW_M_PER_DP = 0.00184f
    const val CLEAR_DEG = 10f

    /** Both eyes see a little round an edge: half the eye distance at the nearer object. */
    private const val HALF_IPD = 0.032f

    /** Leaves, petals and caps stick out past the branch centre lines. */
    private const val FOLIAGE_M = 0.12f

    /** Gap between the tree and a plate that belongs to it (the explain card, the chip). */
    const val PLATE_GAP_DEG = 3f

    /** Half the window's horizontal angle at its opening distance, eye parallax included (about 17.5 degrees). */
    val windowHalfDeg: Float = Placement.deg(atan(WINDOW_W_DP * WINDOW_M_PER_DP / 2f / WINDOW_DIST)) + parallaxDeg(WINDOW_DIST)

    fun parallaxDeg(dist: Float): Float = Placement.deg(atan(HALF_IPD / dist.coerceAtLeast(0.3f)))

    /**
     * Largest horizontal distance of any part of the tree from its trunk, this year or next year after any answer set
     * (none, model, sloppy, half, major, all, over 25 %), plus foliage.
     */
    fun reach(tree: Tree): Float {
        var r = 0f
        for (p in points(tree, nextYear = true)) r = max(r, horiz(p))
        return r + FOLIAGE_M
    }

    /** Tree-space points: branch centre lines, plus next year's shoots / blossoms / labels after every answer set. */
    fun points(tree: Tree, nextYear: Boolean): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (b in tree.branches) out += b.points
        if (!nextYear) return out
        val sets: List<List<Cut>> =
            listOf(emptyList(), Judge.model(tree), Judge.sloppy(tree), Judge.half(tree), Judge.major(tree), Judge.all(tree), Judge.over25(tree))
        for (cuts in sets) {
            val ny = NextYear(tree, cuts)
            for (s in ny.shoots) out += s.points
            out += ny.blossoms.map { it.pos }
            out += ny.labels.map { it.pos }
        }
        return out
    }

    /**
     * How far right of the head -> trunk line (degrees) the tree reaches as seen from [head]: the tree at [treePos] turned by
     * [yaw] (radians, tree space -> world), every point widened by [FOLIAGE_M] and the eyes' parallax.
     */
    fun rightEdgeDeg(head: Vec3, treePos: Vec3, yaw: Float, pts: List<Vec3>): Float {
        val q = io.embodit.pruningclass.domain.model.Quat4.axisAngle(Vec3.UP, yaw)
        val trunkYaw = Placement.yawOf(treePos - head)
        var edge = -90f
        for (l in pts) {
            val w = treePos + q.rotate(l)
            val h = Vec3(w.x - head.x, 0f, w.z - head.z)
            val d = h.length.coerceAtLeast(0.2f)
            val right = -Placement.deg(Placement.angle(Placement.yawOf(h) - trunkYaw))
            edge = max(edge, right + Placement.deg(asin((FOLIAGE_M / d).coerceAtMost(0.99f))) + parallaxDeg(d))
        }
        return edge
    }

    /**
     * Degrees between the window (opened straight ahead of [head] at heading [windowYaw], radians) and the nearest edge of
     * a tree of [reach] at [treePos], both seen from [head]; negative = they overlap. D41 wants >= [CLEAR_DEG].
     */
    fun windowGapDeg(head: Vec3, windowYaw: Float, treePos: Vec3, reach: Float): Float {
        val to = Vec3(treePos.x - head.x, 0f, treePos.z - head.z)
        val rel = kotlin.math.abs(Placement.deg(Placement.angle(Placement.yawOf(to) - windowYaw)))
        return rel - treeHalfDeg(reach, to.length.coerceAtLeast(reach + 0.05f)) - windowHalfDeg
    }

    /** Half the horizontal angle a tree of [reach] subtends at [dist] from its trunk, parallax included. */
    fun treeHalfDeg(reach: Float, dist: Float): Float = Placement.deg(asin((reach / dist).coerceAtMost(0.99f))) + parallaxDeg(dist - reach)

    /** How far right of the view the trunk goes so the crown stays [CLEAR_DEG] off the window (rounded up to 1 degree). */
    fun treeRightDeg(reach: Float, dist: Float): Float = kotlin.math.ceil(windowHalfDeg + CLEAR_DEG + treeHalfDeg(reach, dist))

    private fun horiz(p: Vec3): Float = kotlin.math.sqrt(p.x * p.x + p.z * p.z)
}
