package io.embodit.pruningclass.domain

import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.Species
import io.embodit.pruningclass.domain.tree.TreeGen
import io.embodit.pruningclass.domain.usecase.TapTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * QA round 4 q4-1 (profile D17 as relaxed on 2026-11-11): every tappable target >= 4 cm, >= 2 degrees at the real
 * viewing distance, gaps >= 1.5 cm and >= 0.3 x the larger target. Measured as PcScene places them: rings face the
 * eye, the column lies in the plane facing the eye, gaps taken square to the line of sight (what the eye sees).
 */
class TapTargetsTest {
    private val eye = Vec3(0f, 1.65f, 0f)

    private fun centreAt(dist: Float, elevDeg: Float, yawDeg: Float = 0f): Vec3 {
        val e = Math.toRadians(elevDeg.toDouble()).toFloat()
        val y = Math.toRadians(yawDeg.toDouble()).toFloat()
        return eye + Vec3(sin(y) * cos(e), sin(e), -cos(y) * cos(e)) * dist
    }

    /** Gap between two square faces of edge [u] at [a] and [b] as the eye sees it (square to the line of sight). */
    private fun seenGap(a: Vec3, b: Vec3, u: Float): Float {
        val mid = (a + b) * 0.5f
        val v = (eye - mid).normalized()
        return (b - a).rejectFrom(v).length - u
    }

    @Test
    fun ringsMeetTheFloorAtEveryDistanceAndHeight() {
        var worst = Float.MAX_VALUE
        var d = 0.6f
        while (d <= 3.41f) {
            for (elev in listOf(-45f, -30f, -15f, 0f, 15f, 30f, 45f)) for (total in listOf(1, 2, 5, 7, 8, 14, 17, 30)) {
                val c = centreAt(d, elev)
                val u = TapTargets.columnEdge(c.distanceTo(eye), total)
                val slots = TapTargets.slotCentres(c, total, u, TapTargets.columnUp(c, eye))
                assertTrue("edge $u at $d m", u >= TapTargets.RULE_EDGE)
                for (s in slots) {
                    val a = TapTargets.angleDeg(u, s.distanceTo(eye))
                    worst = minOf(worst, a)
                    assertTrue("ring ${"%.3f".format(u)} m at ${"%.2f".format(s.distanceTo(eye))} m spans $a deg (elev $elev, total $total)", a >= TapTargets.RULE_DEG)
                }
                for (i in 1 until slots.size) {
                    val g = seenGap(slots[i - 1], slots[i], u)
                    assertTrue("gap $g at $d m elev $elev total $total", g >= TapTargets.RULE_GAP && g >= TapTargets.RULE_GAP_RATIO * u)
                }
            }
            d += 0.1f
        }
        println("TAPTARGETS rings: smallest angle ${"%.2f".format(worst)} deg over 0.6 - 3.4 m, elevation +-45")
    }

    @Test
    fun scissorsAreBigAndApartFromTheRings() {
        var d = 0.6f
        while (d <= 3.41f) {
            val u = TapTargets.edge(d)
            val w = TapTargets.SCISSORS_W * u
            val h = TapTargets.SCISSORS_H * u
            assertTrue(minOf(w, h) >= TapTargets.RULE_EDGE)
            assertTrue(TapTargets.angleDeg(minOf(w, h), d + 0.1f) >= TapTargets.RULE_DEG)
            val ring = centreAt(d, -20f)
            val toUser = Vec3(eye.x - ring.x, 0f, eye.z - ring.z).normalized()
            val right = Vec3.UP.cross(toUser).normalized()
            val sc = TapTargets.scissorsCentre(ring, right, u)
            val gap = (sc - ring).length - u / 2f - w / 2f
            assertTrue("scissors gap $gap at $d m", gap >= TapTargets.RULE_GAP && gap >= TapTargets.RULE_GAP_RATIO * max(u, h) - 1e-4f)
            d += 0.1f
        }
    }

    /** The tree's default places: the crown target's bodies span >= 2 degrees where each tree stands. */
    @Test
    fun branchBodiesAreThickEnoughWhereTheTreesStand() {
        for ((species, dist) in listOf(Species.ROSE to 1.43f, Species.APPLE to 2.85f)) {
            val r = TapTargets.branchRadius(0.003f, species)
            assertTrue("$species body ${2 * r} m", 2 * r >= TapTargets.RULE_EDGE)
            assertTrue("$species body ${2 * r} m at $dist m: ${TapTargets.angleDeg(2 * r, dist)}", TapTargets.angleDeg(2 * r, dist) >= TapTargets.RULE_DEG)
        }
    }

    @Test
    fun pagesReachEveryEntry() {
        for (total in 1..40) {
            // following any entry shows it
            for (f in 0 until total) {
                for (start in 0 until max(1, total)) {
                    val s = TapTargets.follow(total, start, f)
                    val shown = TapTargets.page(total, s).filter { it >= 0 }
                    assertTrue("total $total start $start focus $f -> $s shows $shown", f in shown)
                    assertTrue(shown.size <= TapTargets.PAGE)
                }
            }
            // "more" up from the first page and down again walks every entry
            var s = 0
            val seen = HashSet<Int>()
            repeat(20) {
                seen += TapTargets.page(total, s).filter { it >= 0 }
                s = TapTargets.pageUp(total, s)
            }
            assertEquals("total $total", total, seen.size)
            assertEquals(0, (0 until 20).fold(s) { acc, _ -> TapTargets.pageDown(acc) })
            assertEquals(TapTargets.slots(total), TapTargets.page(total, 0).size)
        }
    }

    /** The crown picks by the hit point: a tap on a branch always offers that branch; crossings ask with letters. */
    @Test
    fun theCrownOffersTheTappedBranchAndAsksWhenClose() {
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val dist = if (l.species == Species.ROSE) 1.1f else 2.6f
            val r = TapTargets.pickRadius(dist)
            var direct = 0
            var asked = 0
            var most = 0
            for (b in t.selectable) for (f in listOf(0.2f, 0.5f, 0.8f, 1f)) {
                val hit = b.pointAt(b.length * f)
                val near = TapTargets.candidates(t.selectable, { it.length }, hit, r, b.id)
                assertTrue("$l branch ${b.id} at $f: $near", b.id in near)
                if (near.size == 1) direct++ else asked++
                most = max(most, near.size)
            }
            println("TAPTARGETS crown $l: radius ${"%.3f".format(r)} m, direct $direct, letters $asked, most close together $most")
        }
        // as the eye sees it (PcScene passes the eye): the tapped branch is always offered, and a branch passing right
        // behind (or in front of) the hit point along the line of sight is offered too, however far apart in depth
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val dist = if (l.species == Species.ROSE) 1.1f else 2.6f
            val eyeL = Vec3(0f, if (l.species == Species.ROSE) 1.25f else 1.65f, dist)
            var hidden = 0
            var asked = 0
            var direct = 0
            for (b in t.selectable) for (f in listOf(0.2f, 0.5f, 0.8f, 1f)) {
                val hit = b.pointAt(b.length * f)
                val r = TapTargets.pickRadius(hit.distanceTo(eyeL))
                val near = TapTargets.candidates(t.selectable, { it.length }, hit, r, b.id, eye = eyeL)
                assertTrue("$l branch ${b.id} at $f: $near", b.id in near)
                if (near.size == 1) direct++ else asked++
                val ray = (hit - eyeL).normalized()
                for (o in t.selectable) {
                    if (o.id == b.id) continue
                    var s = 0f
                    while (s <= o.length) {
                        val q = o.pointAt(s)
                        val v = q - eyeL
                        if (v.rejectFrom(ray).length < 0.004f * v.length && kotlin.math.abs(v.length - (hit - eyeL).length) > 0.1f) {
                            assertTrue("$l: branch ${o.id} sits on the line of sight through branch ${b.id} at $f but is not offered: $near", o.id in near)
                            hidden++
                            break
                        }
                        s += 0.01f
                    }
                }
            }
            println("TAPTARGETS crown seen $l: direct $direct, letters $asked, branches on the line of sight offered $hidden")
        }
        // a cut branch only counts up to its cut
        val t = TreeGen.generate(LessonId.R2)
        val b = t.selectable.first()
        val tip = b.pointAt(b.length)
        assertTrue(b.id !in TapTargets.candidates(listOf(b), { it.length * 0.3f }, tip, 0.06f, null))
        assertTrue(b.id !in TapTargets.candidates(listOf(b), { null }, tip, 0.06f, null))
    }

    /**
     * Measured on the emulator (v0.1.6, `rings` debug line + head pose, build/v016/rings): the WorldSpace ring centres
     * PcScene placed. Every ring >= 4 cm and >= 2 degrees from the eye, seen gaps >= 1.5 cm and >= 0.3 x edge, and the
     * spacing is what TapTargets says (pitch for that edge).
     */
    @Test
    fun measuredColumnsOnTheEmulatorMeetTheFloor() {
        val head = Vec3(0f, 1.65f, 0f)
        val measured = listOf(
            // R2 branch 11, page 1 (head looking 63 deg right, 18 deg down)
            0.045f to listOf(Vec3(0.8193f, 1.1559f, -0.1211f), Vec3(0.8404f, 1.2173f, -0.1243f), Vec3(0.8615f, 1.2787f, -0.1274f), Vec3(0.8826f, 1.3401f, -0.1305f), Vec3(0.9037f, 1.4015f, -0.1336f), Vec3(0.9248f, 1.4629f, -0.1367f), Vec3(0.9459f, 1.5243f, -0.1399f), Vec3(0.9670f, 1.5857f, -0.1430f)),
            // A1 branch 138, page 1
            0.080f to listOf(Vec3(1.7677f, 1.2972f, -0.8489f), Vec3(1.7688f, 1.4077f, -0.8494f), Vec3(1.7699f, 1.5181f, -0.8499f), Vec3(1.7710f, 1.6285f, -0.8504f), Vec3(1.7721f, 1.7389f, -0.8509f), Vec3(1.7732f, 1.8494f, -0.8515f), Vec3(1.7743f, 1.9598f, -0.8520f)),
            // A2 branch 122, page 2
            0.087f to listOf(Vec3(1.8193f, 0.9579f, -0.9537f), Vec3(1.8301f, 1.0774f, -0.9594f), Vec3(1.8409f, 1.1970f, -0.9650f), Vec3(1.8517f, 1.3165f, -0.9707f), Vec3(1.8624f, 1.4360f, -0.9763f), Vec3(1.8732f, 1.5555f, -0.9820f), Vec3(1.8840f, 1.6751f, -0.9876f), Vec3(1.8947f, 1.7946f, -0.9933f)),
        )
        for ((u, rings) in measured) {
            assertTrue(u >= TapTargets.RULE_EDGE)
            val mid = (rings.first() + rings.last()) * 0.5f
            // the edge PcScene used is the one TapTargets gives for that column (1 mm rounding in the log)
            assertEquals(TapTargets.columnEdge(mid.distanceTo(head), if (rings.size > TapTargets.PAGE) 14 else rings.size), u, 0.0015f)
            for (r in rings) assertTrue("ring at ${r.distanceTo(head)} m: ${TapTargets.angleDeg(u, r.distanceTo(head))} deg", TapTargets.angleDeg(u, r.distanceTo(head)) >= TapTargets.RULE_DEG)
            for (i in 1 until rings.size) {
                assertEquals(TapTargets.pitch(u), rings[i].distanceTo(rings[i - 1]), 0.0015f)
                val a = rings[i - 1]
                val b = rings[i]
                val v = (head - (a + b) * 0.5f).normalized()
                val g = (b - a).rejectFrom(v).length - u
                assertTrue("seen gap $g for edge $u", g >= TapTargets.RULE_GAP && g >= TapTargets.RULE_GAP_RATIO * u)
            }
        }
    }

    /** The numbers ui-audit quotes: the design's default places. */
    @Test
    fun printTheDefaultPlaces() {
        for ((what, d) in listOf("rose ladder near" to 0.8f, "rose ladder far" to 1.05f, "system volume" to 1.75f, "rose pushed back" to 1.9f, "apple near" to 2.4f, "apple far" to 2.9f)) {
            val u = TapTargets.columnEdge(d, 14)
            println(
                "TAPTARGETS $what ${d} m: edge ${"%.1f".format(u * 100)} cm, ${"%.2f".format(TapTargets.angleDeg(u, d))} deg, gap ${"%.1f".format(TapTargets.gap(u) * 100)} cm, " +
                    "scissors ${"%.1f".format(TapTargets.SCISSORS_W * u * 100)} x ${"%.1f".format(TapTargets.SCISSORS_H * u * 100)} cm gap ${"%.1f".format(TapTargets.SCISSORS_GAP * u * 100)} cm, " +
                    "column ${TapTargets.slots(14)} slots ${"%.0f".format((TapTargets.slots(14) * TapTargets.pitch(u)) * 100)} cm",
            )
        }
    }
}
