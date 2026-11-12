package io.embodit.pruningclass.domain

import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.TreeGen
import io.embodit.pruningclass.domain.usecase.Pick
import io.embodit.pruningclass.domain.usecase.TapTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headset feedback D52 (2026-11-12) with QA round 3 q3-1 kept: with a branch selected, a tap on another branch picks
 * again; a tap whose line crosses a ring is the ring, even where a branch body stands in front of it or behind it.
 */
class PickTest {
    private val eye = Vec3(0f, 1.65f, 0f)

    @Test
    fun branchesTakeTapsWhileABranchIsSelected() {
        for (l in LessonId.entries) for (b in TreeGen.generate(l).selectable) assertTrue("$l branch ${b.id}", Pick.branchesTappable(b.id))
        assertTrue(Pick.branchesTappable(null, choosing = true))
    }

    /** D52: selected branch 11, a tap on branch 18 alone -> 18 is selected (no detour through the window). */
    @Test
    fun aTapOnAnotherBranchWhileSelectedPicksIt() {
        val t = TreeGen.generate(LessonId.R2)
        val ids = t.selectable.map { it.id }
        val a = ids[0]
        val b = ids[1]
        val sel = { id: Int -> id in ids }
        assertEquals(Pick.AfterTap(b, null), Pick.afterCrownTap(a, null, listOf(b), sel))
        // close together -> the letters ask, the old selection is left
        assertEquals(Pick.AfterTap(null, listOf(b, a)), Pick.afterCrownTap(a, null, listOf(b, a), sel))
        // from the letters, a tap on one branch alone picks it
        assertEquals(Pick.AfterTap(a, null), Pick.afterCrownTap(null, listOf(a, b), listOf(a), sel))
        // a cut-away / unselectable branch changes nothing
        assertEquals(Pick.AfterTap(a, null), Pick.afterCrownTap(a, null, listOf(-1), sel))
    }

    /** q3-1 kept: a branch body in front of a ring (or behind it) on the line of the tap -> the ring wins. */
    @Test
    fun aRingOnTheLineWinsOverABranch() {
        val u = TapTargets.MIN_EDGE
        val centre = eye + Vec3(0.55f, -0.25f, -0.65f).normalized() * 0.9f
        val rings = TapTargets.slotCentres(centre, 7, u, TapTargets.columnUp(centre, eye))
        for ((k, r) in rings.withIndex()) {
            val dir = (r - eye).normalized()
            // a branch 15 cm behind the ring and one 10 cm in front of it, on the same line
            assertEquals(k, Pick.ringOnLine(eye, eye + dir * (0.9f + 0.15f), rings, u))
            assertEquals(k, Pick.ringOnLine(eye, eye + dir * (0.9f - 0.10f), rings, u))
            // off-centre inside the face (0.8 of the half edge) still the ring
            val side = Vec3.UP.cross(dir).normalized()
            assertEquals(k, Pick.ringOnLine(eye, r + side * (u * 0.4f), rings, u))
        }
        // the zones tile the column: a line through the gap between two rings is one of them (D52: rings win with more
        // than their drawn square); 3 cm of parallax (left eye vs head) at a branch 25 cm in front still lands on the ring
        val gapMid = (rings[2] + rings[3]) * 0.5f
        assertTrue(Pick.ringOnLine(eye, gapMid, rings, u) in listOf(2, 3))
        for ((k, r) in rings.withIndex()) {
            val leftEye = eye + Vec3(-0.032f, 0f, 0f)
            val dir = (r - leftEye).normalized()
            val branchHit = leftEye + dir * ((r - leftEye).length - 0.25f)
            assertEquals("parallax ring $k", k, Pick.ringOnLine(eye, branchHit, rings, u))
        }
        // beside the column -> no ring (the branch is picked)
        val beside = rings[3] + Vec3.UP.cross((rings[3] - eye).normalized()).normalized() * (u * 1.5f)
        assertNull(Pick.ringOnLine(eye, beside, rings, u))
        // the scissors beat a branch in front of them too
        val toUser = Vec3(eye.x - rings[3].x, 0f, eye.z - rings[3].z).normalized()
        val sc = TapTargets.scissorsCentre(rings[3], Vec3.UP.cross(toUser).normalized(), u)
        assertTrue(Pick.scissorsOnLine(eye, eye + (sc - eye) * 0.8f, sc, u))
        assertTrue(!Pick.scissorsOnLine(eye, rings[3], sc, u))
    }

    @Test
    fun theDensestBranchesExist() {
        for ((l, b) in listOf(LessonId.R2 to 11, LessonId.R2 to 18, LessonId.R2 to 12, LessonId.A1 to 138, LessonId.A2 to 122, LessonId.A3 to 69)) {
            assertTrue("$l branch $b", TreeGen.generate(l)[b] != null)
        }
    }
}
