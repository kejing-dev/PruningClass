package io.embodit.pruningclass.domain.tree

import io.embodit.pruningclass.domain.model.Vec3
import kotlin.math.min

/** A new shoot of next year: polyline, base radius, whether it ends in a flower (rose) and what grows on it. */
data class Shoot(val points: List<Vec3>, val r0: Float, val bloom: Boolean, val leaves: Boolean = true, val inward: Boolean = false)

/** A 3D label of the next-year view (design `nextYear.labels`). */
data class NyLabel(val pos: Vec3, val key: String)

/** Apple blossom cluster at a spur; [half] when shaded. */
data class Blossom(val pos: Vec3, val half: Boolean)

/**
 * "Next year" (design `rules.nextYear`): not a growth simulation but a lookup that turns the buds left after the cuts into
 * pre-made modules. [counts] are the comparison lines of the result page:
 * rose = strong flowering canes, dead / diseased left, crowded centre, crossing rubs;
 * apple = flowering spurs (shaded ones count half), crowded centre, watershoots.
 */
class NextYear(val tree: Tree, val cuts: List<Cut>) {
    val shoots = ArrayList<Shoot>()
    val blossoms = ArrayList<Blossom>()
    val labels = ArrayList<NyLabel>()
    val counts = LinkedHashMap<String, Float>()

    /** Branches still there next year (removed ones and their descendants are gone). */
    val kept: Set<Int>

    init {
        val st = Judge.status(tree, cuts)
        kept = tree.branches.filter { st[it.id] !is Status.Gone && (st[it.id] as? Status.Cut)?.bud != 0 }.map { it.id }.toSet()
        if (tree.species == Species.ROSE) rose(st) else apple(st)
    }

    private fun shootFrom(p: Vec3, dir: Vec3, len: Float, bend: Vec3 = Vec3(0f, 0.03f, 0f)): List<Vec3> =
        listOf(p, p + dir * (len * 0.4f) + bend * 0.5f, p + dir * (len * 0.8f) + bend, p + dir * len + bend * 1.2f)

    private fun rose(st: Map<Int, Status>) {
        var bloom = 0f
        var dead = 0f
        var centre = 0f
        var cross = 0f
        for (b in tree.branches.filter { it.parent == null }) {
            val s = st.getValue(b.id)
            if (s is Status.Gone || (s as? Status.Cut)?.bud == 0) continue
            val k = (s as? Status.Cut)?.bud
            val badLeft = b.defect == Defect.DEAD || b.defect == Defect.THIN || (b.healthyBelow != null && (k == null || k >= b.healthyBelow))
            if (badLeft) {
                dead++
                labels += NyLabel(b.pointAt(b.length * 0.7f), "deadLeft")
                if (b.defect == Defect.DEAD || b.defect == Defect.THIN) continue
            }
            if (b.diameterMm < 7f) continue
            if (k != null && k >= 1 && !badLeft) {
                val bud = b.buds.first { it.index == k }
                val inward = bud.facing == Facing.IN
                val dir = (bud.dir * 0.6f + Vec3(0f, 1f, 0f)).normalized()
                val len = if (b.diameterMm > 10f) 0.50f else 0.38f
                shoots += Shoot(shootFrom(bud.pos, dir, len), 0.0055f, bloom = true, inward = inward)
                if (inward) {
                    centre++
                    labels += NyLabel(bud.pos + dir * (len * 0.6f), "inward")
                } else bloom++
            } else if (k == null) {
                // not shortened: only the top two or three buds give weak shoots, the lower cane stays bare
                for (bud in b.buds.takeLast(3)) shoots += Shoot(shootFrom(bud.pos, (bud.dir + Vec3(0f, 0.8f, 0f)).normalized(), 0.15f), 0.0028f, bloom = false)
                if (b.defect == Defect.INWARD) {
                    centre++
                    labels += NyLabel(b.pointAt(b.length * 0.6f), "inward")
                }
            }
        }
        val pair = tree.branches.filter { it.defect == Defect.CROSSING }
        if (pair.size == 2 && pair.all { it.id in kept }) {
            cross++
            val a = pair[0].pointAt(pair[0].length * 0.35f)
            labels += NyLabel(a, "crossRub")
        }
        counts["bloom"] = bloom
        counts["dead"] = dead
        counts["centre"] = centre
        counts["cross"] = cross
    }

    private fun apple(st: Map<Int, Status>) {
        val shading = tree.branches.filter { it.id in kept && it.defect in setOf(Defect.CROSSING, Defect.INWARD, Defect.WATERSHOOT) }
        var flowers = 0f
        for (b in tree.branches.filter { it.defect == Defect.SPUR && it.id in kept }) {
            val tip = b.points.last()
            val shaded = shading.any { s -> s.points.any { it.distanceTo(tip) < 0.3f } }
            blossoms += Blossom(tip, shaded)
            flowers += if (shaded) 0.5f else 1f
        }
        var centre = 0f
        for (b in tree.branches.filter { it.id in kept && it.defect == Defect.INWARD }) {
            centre++
            labels += NyLabel(b.points.last(), "centre")
        }
        val pair = tree.branches.filter { it.defect == Defect.CROSSING }
        if (pair.size == 2 && pair.all { it.id in kept }) {
            centre++
            labels += NyLabel(pair[0].points.last(), "crossRub")
        }
        // extensions: kept branch tips + 0.20 m; shortened leaders 1 - 2 new shoots from the cut bud
        for (b in tree.branches.filter { it.id in kept && it.selectable && it.defect != Defect.SPUR && it.defect != Defect.MAIN && it.defect != Defect.TRUNK }) {
            val k = (st[b.id] as? Status.Cut)?.bud
            if (k != null && k >= 1) {
                val bud = b.buds.first { it.index == k }
                shoots += Shoot(shootFrom(bud.pos, (bud.dir + b.tangentAt(bud.s)).normalized(), 0.38f), 0.007f, bloom = false)
                shoots += Shoot(shootFrom(bud.pos, (bud.dir * -0.3f + b.tangentAt(bud.s) + Vec3(0f, 0.6f, 0f)).normalized(), 0.30f), 0.006f, bloom = false)
                if (bud.facing == Facing.IN) {
                    centre++
                    labels += NyLabel(bud.pos, "centre")
                }
            } else if (k == null) {
                shoots += Shoot(shootFrom(b.points.last(), b.tangentAt(b.length), 0.20f), 0.004f, bloom = false)
            }
        }
        // watershoots: the old ones left + new ones after more than 20 % removed (2 per 5 % over, at most 12)
        val oldWater = tree.branches.count { it.id in kept && it.defect == Defect.WATERSHOOT }.toFloat()
        val pct = Judge.removedPct(tree, st)
        val extra = if (pct > 20f) min(12, ((pct - 20f) / 5f).toInt().coerceAtLeast(0) * 2 + 2) else 0
        val mains = tree.branches.filter { it.defect == Defect.MAIN }
        for (i in 0 until extra) {
            val m = mains[i % mains.size]
            val p = m.pointAt(m.length * (0.35f + 0.12f * (i / mains.size)))
            shoots += Shoot(listOf(p, p + Vec3(0.01f, 0.25f, 0f), p + Vec3(-0.01f, 0.5f, 0.01f), p + Vec3(0f, 0.65f, 0f)), 0.008f, bloom = false)
        }
        if (extra > 0) labels += NyLabel(mains[0].pointAt(mains[0].length * 0.4f) + Vec3(0f, 0.7f, 0f), "overPrune")
        counts["bloom"] = flowers
        counts["centre"] = centre
        counts["water"] = oldWater + extra
    }

    companion object {
        /** Comparison keys per species, in display order; "higher is better" for bloom only. */
        fun keys(species: Species): List<String> = if (species == Species.ROSE) listOf("bloom", "dead", "centre", "cross") else listOf("bloom", "centre", "water")
    }
}
