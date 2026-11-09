package io.embodit.pruningclass.domain

import io.embodit.pruningclass.domain.model.Quat4
import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.domain.tree.Judge
import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.NextYear
import io.embodit.pruningclass.domain.tree.Species
import io.embodit.pruningclass.domain.tree.Tree
import io.embodit.pruningclass.domain.tree.TreeGen
import io.embodit.pruningclass.domain.usecase.Clearance
import io.embodit.pruningclass.domain.usecase.Placement
import io.embodit.pruningclass.domain.usecase.Upright
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Factory D41 (2026-11-04 "别遮挡 3D 的"): from where the user stands, the main window (TabBar capsule above, system
 * controls below: both within its width) keeps >= 10 degrees of horizontal angle from every part of the tree, this year
 * and next year, whichever way the tree is turned; the explain card and the "open panel" chip never cover the tree.
 * The tree is checked point by point (branch centre lines, next year's shoots / blossoms / labels after every answer
 * set), each point widened by the foliage margin.
 */
class ClearanceTest {
    private val head = Vec3(0.3f, 1.62f, -0.4f)

    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()

    private fun forward(yawDeg: Float, pitchDeg: Float): Vec3 =
        (Quat4.axisAngle(Vec3.UP, Placement.rad(yawDeg)) * Quat4.axisAngle(Vec3.RIGHT, Placement.rad(pitchDeg))).rotate(Vec3.AHEAD)

    /** The scene's placing distances (PcScene.distances: rose 1.10, apple 2.60). */
    private fun dist(t: Tree) = if (t.species == Species.ROSE) 1.10f else 2.60f

    /** Every point of the tree in tree space: branches and next year after every answer set. */
    private fun points(t: Tree): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (b in t.branches) out += b.points
        for (cuts in listOf(emptyList(), Judge.model(t), Judge.sloppy(t), Judge.half(t), Judge.major(t), Judge.all(t), Judge.over25(t))) {
            val ny = NextYear(t, cuts)
            for (s in ny.shoots) out += s.points
            out += ny.blossoms.map { it.pos }
            out += ny.labels.map { it.pos }
        }
        // the rose's stand / pot corners
        if (t.species == Species.ROSE) for (sx in listOf(-1f, 1f)) for (sz in listOf(-1f, 1f)) out += Vec3(0.2f * sx, 0f, 0.2f * sz)
        return out
    }

    /** Horizontal angular interval (degrees, relative to [centreYaw], + = left) of the tree placed at [p] turned by [spinDeg]. */
    private fun treeSpan(pts: List<Vec3>, p: Upright, spinDeg: Float, from: Vec3, centreYaw: Float): Pair<Float, Float> {
        val q = Quat4.axisAngle(Vec3.UP, p.yaw + Placement.rad(spinDeg))
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (l in pts) {
            val w = p.position + q.rotate(l)
            val h = Vec3(w.x - from.x, 0f, w.z - from.z)
            val az = deg(Placement.angle(Placement.yawOf(h) - centreYaw))
            val pad = deg(asin((0.12f / h.length.coerceAtLeast(0.2f)).coerceAtMost(0.99f)))
            lo = minOf(lo, az - pad)
            hi = maxOf(hi, az + pad)
        }
        return lo to hi
    }

    private val windowHalfRaw = deg(atan(Clearance.WINDOW_W_DP * Clearance.WINDOW_M_PER_DP / 2f / Clearance.WINDOW_DIST))

    @Test
    fun windowIsAboutThirtyFiveDegreesWide() {
        // measured on the v0.1.3 emulator shots: right edge 17.5 degrees off the view, left edge 16.5
        assertTrue("window half-angle $windowHalfRaw", windowHalfRaw in 17f..18f)
    }

    @Test
    fun treeKeepsTenDegreesOffTheWindowInEveryPoseAndYear() {
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val reach = maxOf(Clearance.reach(t), 0.30f)
            val pts = points(t)
            for (yaw in listOf(-150f, -40f, 0f, 65f, 170f)) {
                for (pitch in listOf(-30f, 0f, 30f)) {
                    val f = forward(yaw, pitch)
                    val p = Placement.treeBesideWindow(head, f, dist(t), 0.4f, reach)
                    val view = Placement.rad(yaw)
                    val trunk = deg(Placement.angle(Placement.yawOf(p.position - head) - view))
                    assertTrue("$l: trunk at the user's side, not behind ($trunk)", trunk in -80f..-55f)
                    for (spin in 0 until 360 step 30) {
                        val (lo, hi) = treeSpan(pts, p, spin.toFloat(), head, view)
                        // tree right of the view (negative): its left edge (hi) must stay 10 deg right of the window's right edge
                        val gap = -hi - windowHalfRaw
                        assertTrue("$l yaw $yaw pitch $pitch spin $spin: gap $gap deg (tree $lo..$hi)", gap >= Clearance.CLEAR_DEG)
                    }
                }
            }
        }
    }

    @Test
    fun explainCardBesideTheCrownNeverOverIt() {
        val cardW = 400f * 0.0008f
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val reach = maxOf(Clearance.reach(t), 0.30f)
            val pts = points(t)
            val f = forward(20f, 0f)
            val p = Placement.treeBesideWindow(head, f, dist(t), 0.4f, reach)
            val view = Placement.rad(20f)
            // pruning shows this year's branches, the result page next year's growth too: the card goes beside what is shown
            for (grown in listOf(false, true)) {
                val shown = Clearance.points(t, nextYear = grown)
                val drawn = if (grown) pts else shown
                for (spin in 0 until 360 step 45) {
                    val yaw = p.yaw + Placement.rad(spin.toFloat())
                    val edge = Clearance.rightEdgeDeg(head, p.position, yaw, shown)
                    val (lo, _) = treeSpan(drawn, p, spin.toFloat(), head, view)
                    for (b in t.selectable) {
                        val cut = p.position + p.rotation.rotate(b.pointAt(b.length * 0.5f))
                        val (card, scale) = Placement.dockExplain(head, cut, p.position, reach, edge, cardW)
                        val to = Vec3(card.position.x - head.x, 0f, card.position.z - head.z)
                        val az = deg(Placement.angle(Placement.yawOf(to) - view))
                        val half = deg(atan(cardW * scale / 2f / to.length))
                        assertTrue("$l grown=$grown branch ${b.id} spin $spin: card left edge ${az + half} vs tree right edge $lo", az + half <= lo)
                        assertTrue("$l: card right of the window (${az + half})", az + half < -windowHalfRaw - Clearance.CLEAR_DEG)
                        assertTrue("$l: card readable distance ${to.length}", to.length in 0.55f..1.3f)
                        assertTrue("$l: card upright", abs(card.rotation.rotate(Vec3.UP).y - 1f) < 1e-3f)
                        // close enough to the tree to be seen while looking at it (a headset sees about +/-50 degrees; the apple crown alone spans +/-36 at 2 m)
                        val trunkAz = deg(Placement.angle(Placement.yawOf(p.position - head) - view))
                        assertTrue("$l grown=$grown spin $spin: card ${trunkAz - az} deg right of the trunk line", trunkAz - az < (if (grown) 60f else 55f))
                    }
                }
            }
        }
    }

    @Test
    fun chipNeverOverTheTree() {
        val chipW = 240f * 0.0008f * 0.75f
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val reach = maxOf(Clearance.reach(t), 0.30f)
            val pts = points(t)
            val p = Placement.treeBesideWindow(head, Vec3.AHEAD, dist(t), 0.4f, reach)
            val treeYaw = deg(Placement.yawOf(p.position - head))
            // the user may close the window while looking anywhere: at the window, at the tree, between, beyond
            for (lookDeg in listOf(0f, -20f, treeYaw, treeYaw - 25f, treeYaw + 15f, 60f)) {
                val f = forward(lookDeg, 0f)
                val chip = Placement.chipBesideTree(head, f, 0.8f, 15f, 20f, chipW, p.position, reach)
                val to = Vec3(chip.position.x - head.x, 0f, chip.position.z - head.z)
                val az = deg(Placement.yawOf(to))
                val half = deg(atan(chipW / 2f / to.length))
                val (lo, hi) = treeSpan(pts, p, 0f, head, 0f)
                assertTrue("$l look $lookDeg: chip $az +/- $half over tree $lo..$hi", az - half >= hi || az + half <= lo)
            }
            // no tree yet: 20 degrees left of the view as before
            val free = Placement.chipBesideTree(head, Vec3.AHEAD, 0.8f, 15f, 20f, chipW, null, reach)
            assertTrue(abs(deg(Placement.yawOf(free.position - head)) - 20f) < 0.5f)
        }
    }

    @Test
    fun reachCoversNextYear() {
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val r = Clearance.reach(t)
            assertTrue("$l reach $r vs crown ${t.crownRadius}", r >= t.crownRadius)
            println("D41 $l reach=${"%.2f".format(r)} crown=${"%.2f".format(t.crownRadius)} right=${Clearance.treeRightDeg(maxOf(r, 0.3f), dist(t))} windowHalf=${"%.1f".format(Clearance.windowHalfDeg)}")
        }
    }

    /**
     * QA v0.1.3: a window created again (the chip after its x, [Bring here]) opens straight ahead of wherever the head
     * points then. `windowGapDeg` must never claim more room than the tree's points leave, and when it reports less than
     * 10 degrees, placing the tree again from that heading clears it.
     */
    @Test
    fun aWindowOpenedLaterIsCheckedAndTheTreeMovesBesideIt() {
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val reach = maxOf(Clearance.reach(t), 0.30f)
            val pts = points(t)
            val p = Placement.treeBesideWindow(head, Vec3.AHEAD, dist(t), 0.4f, reach)
            val trunk = deg(Placement.yawOf(p.position - head))
            var moved = 0
            for (openDeg in listOf(0f, 15f, -10f, trunk + 30f, trunk + 10f, trunk, trunk - 20f, -60f)) {
                val yaw = Placement.rad(openDeg)
                val gap = Clearance.windowGapDeg(head, yaw, p.position, reach)
                // point by point, the real gap on the tree's nearer side
                var real = Float.MAX_VALUE
                for (spin in 0 until 360 step 45) {
                    val (lo, hi) = treeSpan(pts, p, spin.toFloat(), head, yaw)
                    val g = if (hi < 0f) -hi - windowHalfRaw else if (lo > 0f) lo - windowHalfRaw else -(minOf(hi, -lo) + windowHalfRaw)
                    real = minOf(real, g)
                }
                assertTrue("$l open $openDeg: reported gap $gap > real $real", gap <= real + 0.5f)
                if (gap < Clearance.CLEAR_DEG) {
                    moved++
                    val q = Placement.treeBesideWindow(head, forward(openDeg, 0f), dist(t), 0.4f, reach)
                    val again = Clearance.windowGapDeg(head, yaw, q.position, reach)
                    assertTrue("$l open $openDeg: after moving the tree gap $again", again >= Clearance.CLEAR_DEG)
                }
            }
            assertTrue("$l: the window opened at the tree moves it", moved >= 3)
        }
    }
}
