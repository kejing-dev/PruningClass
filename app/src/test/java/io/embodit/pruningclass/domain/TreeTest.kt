package io.embodit.pruningclass.domain

import io.embodit.pruningclass.domain.tree.Cut
import io.embodit.pruningclass.domain.tree.Defect
import io.embodit.pruningclass.domain.tree.Facing
import io.embodit.pruningclass.domain.tree.Judge
import io.embodit.pruningclass.domain.tree.Label
import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.NextYear
import io.embodit.pruningclass.domain.tree.Species
import io.embodit.pruningclass.domain.tree.TreeGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generator and judge against the design's acceptance numbers (`rules.generator.seeds`, `verification`). */
class TreeTest {
    @Test
    fun mustCutsMatchTheDesign() {
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val sel = t.selectable.size
            println("$l must=${t.mustCount} branches=${t.branches.size} selectable=$sel crown=${"%.2f".format(t.crownRadius)} height=${"%.2f".format(t.height)} buds=${t.branches.sumOf { it.buds.size }}")
            assertEquals("$l must cuts", l.mustCuts, t.mustCount)
            if (l.species == Species.ROSE) {
                val canes = t.branches.count { it.parent == null }
                assertTrue("$l canes $canes", if (l == LessonId.R2) canes in 8..9 else canes in 10..11)
            } else {
                assertEquals(4, t.branches.count { it.label == Label.MAJOR && it.parent != null })
                assertTrue("$l selectable $sel <= 90", sel <= 90)
            }
        }
    }

    @Test
    fun modelPassesSloppyPassesNoneAllFail() {
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val model = Judge.score(t, Judge.model(t))
            val sloppy = Judge.score(t, Judge.sloppy(t))
            val none = Judge.score(t, emptyList())
            val all = Judge.score(t, Judge.all(t))
            val half = Judge.score(t, Judge.half(t))
            val major = Judge.score(t, Judge.major(t))
            println(
                "$l model=${model.percent}% w${model.wrong} m${model.major} strong${model.strongKept} removed=${"%.1f".format(model.removedPct)} pass=${model.pass} | " +
                    "sloppy=${sloppy.percent}% pass=${sloppy.pass} | none=${none.percent}% | all=${all.percent}% w${all.wrong} m${all.major} | half=${half.percent}% | major m${major.major}",
            )
            assertEquals("$l model 100%", 100, model.percent)
            assertTrue("$l model passes", model.pass)
            assertTrue("$l sloppy >= 80%", sloppy.ratio >= 0.8f)
            assertTrue("$l sloppy passes", sloppy.pass)
            assertFalse("$l none fails", none.pass)
            assertFalse("$l all fails", all.pass)
            assertFalse("$l half fails", half.pass)
            assertFalse("$l major fails", major.pass)
            if (l.species == Species.APPLE && l != LessonId.A1) assertTrue("$l model removal 10-15 % (${model.removedPct})", model.removedPct in 10f..15f)
            if (l.species == Species.APPLE) {
                val over = Judge.score(t, Judge.over25(t))
                assertTrue("$l over25 removes > 20 %", over.removedPct > 20f)
                assertFalse("$l over25 fails", over.pass)
                // design acceptance "A3 剪掉 25% → 没通过：去除量 25%（要 ≤ 20%）": the removal is the reason, not wrong cuts
                assertEquals("$l over25 reason (wrong=${over.wrong})", "removed", over.failReason)
                if (l == LessonId.A3) assertTrue("$l over25 has no more than one wrong cut (${over.wrong})", over.wrong <= 1)
            }
        }
    }

    @Test
    fun budRangesHaveOutwardBuds() {
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            for (b in t.branches.filter { it.acceptable != null }) {
                val outward = b.buds.count { it.index in b.acceptable!! && it.facing != Facing.IN }
                assertTrue("$l branch ${b.id} ${b.defect} range ${b.acceptable} has an outward / side bud", outward >= 1)
                b.modelCut?.let { m -> if (m > 0) assertTrue("$l model cut ${b.id} in range", m in b.acceptable!!) }
            }
        }
    }

    @Test
    fun nextYearShowsTheDifference() {
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val model = NextYear(t, Judge.model(t)).counts
            val none = NextYear(t, emptyList()).counts
            val all = NextYear(t, Judge.all(t)).counts
            println("$l next model=$model none=$none all=$all")
            fun worse(a: Map<String, Float>): Boolean =
                a.any { (k, v) -> if (k == "bloom") v < (model[k] ?: 0f) else v > (model[k] ?: 0f) }
            assertTrue("$l none worse in one count", worse(none))
            assertTrue("$l all worse in one count", worse(all))
        }
    }

    @Test
    fun trunkAtTheOrigin() {
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val base = if (l.species == Species.APPLE) t.branches.first().points.first() else t.branches.filter { it.parent == null }.map { it.points.first() }.let { p -> p.reduce { a, b -> a + b } / p.size.toFloat() }
            assertTrue("$l base centre ${base}", kotlin.math.hypot(base.x, base.z) < 0.01f)
        }
    }

    @Test
    fun crossingPairSecondCutIsTheWrongOne() {
        for (l in LessonId.entries) {
            val t = TreeGen.generate(l)
            val pair = t.branches.filter { it.defect == Defect.CROSSING && it.selectable }
            if (pair.size != 2) continue
            for (b in pair) assertTrue("$l crossing ${b.id} has outward buds below the crossing", b.acceptable != null)
            for ((first, second) in listOf(pair[0] to pair[1], pair[1] to pair[0])) {
                val cuts = listOf(Cut(first.id, 0), Cut(second.id, 0))
                val score = Judge.score(t, cuts)
                val target = score.targets.first { first.id in it.ids }
                assertEquals("$l pair: the first cut is the answer", first.id, target.hit)
                assertTrue("$l pair: the second cut is wrong", score.wrongs.any { it.branch == second.id })
                assertEquals("$l pair: live feedback on the second cut is wrong", 0f, Judge.feedback(t, cuts, cuts[1]).first)
            }
        }
    }
}
