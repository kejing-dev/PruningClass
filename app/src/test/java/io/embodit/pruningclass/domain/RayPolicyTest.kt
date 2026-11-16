package io.embodit.pruningclass.domain

import io.embodit.pruningclass.domain.usecase.DragTotal
import io.embodit.pruningclass.domain.usecase.RayPolicy
import io.embodit.pruningclass.ui.pc.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

/** D41 corrected (double ray): one ray per controller in every phase, none drawn by the app. D53: drags add up. */
class RayPolicyTest {
    @Test
    fun oneRayPerControllerInEveryPhase() {
        for (p in Phase.entries) {
            assertEquals("${p.name}: the app draws no ray", 0, RayPolicy.appRays(p.name))
            assertEquals("${p.name}: the user sees one ray per controller", 1, RayPolicy.seenRays(p.name))
        }
    }

    /** drag-delta: 60 events of 2 mm each add up to 12 cm (not the last step). */
    @Test
    fun dragStepsAddUp() {
        val t = DragTotal()
        var last = 0f to 0f
        repeat(60) { last = t.add(0.002f, -0.001f) }
        assertEquals(0.12f, last.first, 1e-4f)
        assertEquals(-0.06f, last.second, 1e-4f)
    }
}
