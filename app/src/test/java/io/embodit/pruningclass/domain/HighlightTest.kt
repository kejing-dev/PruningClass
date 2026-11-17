package io.embodit.pruningclass.domain

import io.embodit.pruningclass.domain.tree.Label
import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.TreeGen
import io.embodit.pruningclass.domain.tree.Wood
import io.embodit.pruningclass.domain.usecase.Highlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Headset feedback 2026-11-16: the selected branch keeps its own colour; marks on the other branches are not faded. */
class HighlightTest {
    // PcColors.Deadwood / Diseased / Canker / Alert
    private val marks = listOf(0x6B4A2F, 0x3A2033, 0x2B1D16, 0xFF8A1F).map { c -> floatArrayOf((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f) }

    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    @Test
    fun selectionKeepsTheBranchsOwnLook() {
        assertEquals(Highlight.Look.OWN, Highlight.look(hinted = false))
        assertEquals(Highlight.Look.HINT, Highlight.look(hinted = true))
        assertTrue(Highlight.lifted(3, selected = 3, pruning = true))
        assertTrue(!Highlight.lifted(4, selected = 3, pruning = true))
        assertTrue(!Highlight.lifted(3, selected = 3, pruning = false))
    }

    @Test
    fun liftIsAtMostTwentyPercentAndKeepsTheHue() {
        assertTrue(Highlight.LIFT <= 0.20f)
        for (c in marks) {
            val l = Highlight.liftRgb(c)
            assertTrue("brighter", luma(l) >= luma(c))
            assertTrue("<= 20 %", luma(l) <= luma(c) * 1.20f + 1e-4f)
            // still far from white: the weakest channel stays low, so the colour reads (white = all channels 1)
            assertTrue("not white", l.min() < 0.5f)
            // same channel ratios (hue) where nothing clamps
            if (l.all { it < 1f }) for (i in 0..2) for (j in 0..2) if (c[j] > 0f) assertEquals(c[i] / c[j], l[i] / l[j], 1e-3f)
        }
    }

    @Test
    fun marksOnOtherBranchesAreNotFaded() {
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val sel = t.selectable.first().id
            for (b in t.selectable) {
                if (b.id == sel) {
                    assertEquals(1f, Highlight.opacity(b.id, sel, true, hinted = false, woods = b.pieces.map { it.wood }))
                    continue
                }
                val bad = b.pieces.any { it.wood in Highlight.markWoods }
                val o = Highlight.opacity(b.id, sel, true, hinted = false, woods = b.pieces.map { it.wood })
                assertEquals("$l branch ${b.id}", if (bad) 1f else Highlight.FADE, o)
                // a hinted branch (uncut MUST, orange) is never faded
                if (b.label == Label.MUST) assertEquals(1f, Highlight.opacity(b.id, sel, true, hinted = true, woods = b.pieces.map { it.wood }))
            }
        }
        // nothing selected / not pruning: nothing fades
        assertEquals(1f, Highlight.opacity(1, null, true, false, listOf(Wood.CANE)))
        assertEquals(1f, Highlight.opacity(1, 2, false, false, listOf(Wood.CANE)))
        assertTrue(abs(Highlight.FADE - 0.7f) < 1e-6f)
    }
}
