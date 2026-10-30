package io.embodit.pruningclass.domain

import io.embodit.pruningclass.domain.model.Quat4
import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.domain.usecase.PlaceMachine
import io.embodit.pruningclass.domain.usecase.Placement
import kotlin.math.acos
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Factory placement rules: yaw-only `placeUpright` (2026-10-30), four-way placement (2026-10-28), a placing mode with an end (2026-10-29). */
class PlacementTest {
    private val head = Vec3(0.3f, 1.62f, -0.4f)

    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()

    private fun headForward(yawDeg: Float, pitchDeg: Float, rollDeg: Float): Vec3 {
        val q = Quat4.axisAngle(Vec3.UP, Placement.rad(yawDeg)) * Quat4.axisAngle(Vec3.RIGHT, Placement.rad(pitchDeg)) * Quat4.axisAngle(Vec3(0f, 0f, 1f), Placement.rad(rollDeg))
        return q.rotate(Vec3.AHEAD)
    }

    @Test
    fun panelsStayUprightWhateverTheHeadDoes() {
        for ((pitch, roll) in listOf(-30f to 0f, 30f to 0f, 0f to 20f, -30f to 20f)) {
            val f = headForward(25f, pitch, roll)
            val p = Placement.placeUpright(head, f, 0.9f, 12f)
            val up = p.rotation.rotate(Vec3.UP)
            val tilt = deg(acos(up.dot(Vec3.UP).coerceIn(-1f, 1f)))
            assertTrue("pitch $pitch roll $roll: up tilted $tilt", tilt < 1f)
            val to = p.position - head
            val dist = to.length
            val below = -deg(atan2(to.y, Vec3(to.x, 0f, to.z).length))
            assertTrue("distance $dist", dist in 0.8f..1.2f)
            assertTrue("below eye line $below", below in 10f..20f)
            assertEquals("heading follows the head yaw only", 25f, deg(p.yaw), 0.5f)
        }
    }

    @Test
    fun treeAndPanelFaceTheUserFromFourSides() {
        val mark = Vec3(1f, 0f, -2f)
        for (side in listOf(0f, 90f, 180f, 270f)) {
            val a = Placement.rad(side)
            // user 2 m from the mark, looking at it
            val user = mark + Vec3(-kotlin.math.sin(a), 0f, -kotlin.math.cos(a)) * -2f + Vec3(0f, 1.6f, 0f)
            val fwd = (mark - user).let { Vec3(it.x, 0f, it.z).normalized() }
            val tree = Placement.treeAhead(user, fwd, 2f, 0f)
            // the tree's front (+Z) points at the user
            val front = tree.rotation.rotate(Vec3(0f, 0f, 1f))
            val toUser = Vec3(user.x - tree.position.x, 0f, user.z - tree.position.z).normalized()
            val err = deg(acos(front.dot(toUser).coerceIn(-1f, 1f)))
            assertTrue("side $side: tree front off by $err", err < 10f)
            val panel = Placement.dockPanel(user, tree.position)
            val pf = panel.rotation.rotate(Vec3(0f, 0f, 1f))
            val toUserP = Vec3(user.x - panel.position.x, 0f, user.z - panel.position.z).normalized()
            assertTrue("side $side: panel faces the user", deg(acos(pf.dot(toUserP).coerceIn(-1f, 1f))) < 10f)
            // panel 50 degrees left of the user -> tree line
            val rel = deg(Placement.angle(Placement.yawOf(panel.position - user) - Placement.yawOf(tree.position - user)))
            assertEquals("side $side: panel 50 left", 50f, rel, 1f)
            assertTrue("scale never negative", panel.rotation.w * panel.rotation.w <= 1.0001f)
        }
    }

    @Test
    fun placingHasAnEnd() {
        val m = PlaceMachine()
        m.set(Vec3(0f, 0f, -2f))
        // locked: aiming and dropping do not move the tree
        m.aim(Vec3(5f, 0f, 5f))
        assertFalse(m.drop(true))
        assertEquals(Vec3(0f, 0f, -2f), m.position)
        // replant -> placing -> drop -> locked
        m.replant()
        assertEquals(PlaceMachine.Mode.PLACING, m.mode)
        m.aim(Vec3(1f, 0f, -3f))
        assertFalse("a refused spot keeps placing", m.drop(false))
        assertEquals(PlaceMachine.Mode.PLACING, m.mode)
        assertTrue(m.drop(true))
        assertEquals(PlaceMachine.Mode.LOCKED, m.mode)
        assertEquals(Vec3(1f, 0f, -3f), m.position)
        // locked again: input does not move it
        m.aim(Vec3(9f, 0f, 9f))
        assertFalse(m.drop(true))
        assertEquals(Vec3(1f, 0f, -3f), m.position)
        // move -> placing again; cancel -> locked, unchanged
        m.replant()
        m.aim(Vec3(2f, 0f, 2f))
        m.cancel()
        assertEquals(PlaceMachine.Mode.LOCKED, m.mode)
        assertEquals(Vec3(1f, 0f, -3f), m.position)
    }

    @Test
    fun explainCardBesideTheCut() {
        val user = Vec3(0f, 1.6f, 0f)
        val tree = Vec3(0f, 0f, -1.1f)
        val cut = Vec3(0.1f, 0.85f, -1.05f)
        val (p, scale) = Placement.dockExplain(user, cut, tree)
        val cutDir = (cut - user).normalized()
        val cardDir = (p.position - user).normalized()
        val off = deg(acos(cutDir.dot(cardDir).coerceIn(-1f, 1f)))
        assertTrue("card about 15 degrees from the cut ($off)", off in 10f..20f)
        assertTrue("card in front of the cut", (p.position - user).length < (cut - user).length)
        assertTrue("scale ${scale}", scale in 0.3f..1.2f)
        // with the crown known the card stays in front of it (no cane can pass in front of the card)
        val (q, s2) = Placement.dockExplain(user, cut, tree, crownRadius = 0.5f)
        val horiz = Vec3(q.position.x - user.x, 0f, q.position.z - user.z).length
        assertTrue("card in front of the crown ($horiz)", horiz <= 1.1f - 0.5f - 0.1f + 0.01f)
        assertTrue("scale keeps the angular size (${s2})", s2 < scale)
    }
}
