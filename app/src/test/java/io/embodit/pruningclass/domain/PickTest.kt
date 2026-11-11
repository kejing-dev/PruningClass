package io.embodit.pruningclass.domain

import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.TreeGen
import io.embodit.pruningclass.domain.usecase.Pick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA round 3 q3-1 (profile D17): with a branch selected only its bud ladder takes taps; rings never overlap. */
class PickTest {
    @Test
    fun branchesTakeTapsOnlyWhileNothingIsSelected() {
        assertTrue(Pick.branchesTappable(null))
        for (l in LessonId.entries) for (b in TreeGen.generate(l).selectable) assertFalse("$l branch ${b.id}", Pick.branchesTappable(b.id))
    }

    @Test
    fun neighbouringRingsNeverShareAHit() {
        assertTrue("ring hit ${Pick.RING_HIT} vs spacing ${Pick.RING_GAP}", Pick.ringsSeparate())
    }

    /** The branches QA swept (R2 11 / 18) and, per apple lesson, the selectable branch (>= 6 buds) with most wood around it. */
    @Test
    fun theDensestBranchesExist() {
        val r2 = TreeGen.generate(LessonId.R2)
        assertTrue(r2[11] != null && r2[18] != null && r2[11]!!.selectable && r2[18]!!.selectable)
        for (l in listOf(LessonId.A1, LessonId.A2, LessonId.A3)) {
            val t = TreeGen.generate(l)
            val densest = t.selectable.filter { it.buds.size >= 6 }.maxBy { b ->
                val mid = b.pointAt(b.length * 0.5f)
                t.branches.filter { it.id != b.id }.sumOf { o -> o.points.count { (it - mid).length < 0.25f } }
            }
            println("PICK densest $l branch=${densest.id} buds=${densest.buds.size}")
        }
    }
}
