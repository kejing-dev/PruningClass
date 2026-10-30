package io.embodit.pruningclass.domain.tree

import io.embodit.pruningclass.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The lesson trees (design `rules.generator`), ported from the mock-up's `trees.js` look (rose: pot on a 0.40 m stand,
 * canes leaning out, green young wood, purple-red buds; apple: open-centre trunk 0.6 m, four scaffolds with laterals,
 * spurs and upright watershoots) and extended with the defect list of each lesson. Every branch is labelled when it is
 * grown, so right and wrong come from the construction, not from recognition. Pure Kotlin; deterministic per lesson.
 */
object TreeGen {
    fun generate(lesson: LessonId): Tree = if (lesson.species == Species.ROSE) Rose(lesson).build() else Apple(lesson).build()

    /** Crossing pairs: the crossing point is looked for above this arc length (all canes start together). */
    private const val CROSS_MIN_S = 0.15f

    // ---------------------------------------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------------------------------------

    /** mulberry32, as in the mock-up. */
    internal class Rng(seed: Int) {
        private var a = seed

        fun next(): Float {
            a += 0x6D2B79F5
            var t = a
            t = (t xor (t ushr 15)) * (1 or t)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
        }

        fun range(a: Float, b: Float) = a + (b - a) * next()
    }

    internal val UP = Vec3(0f, 1f, 0f)

    /** Uniform Catmull-Rom through [ctrl], [n] + 1 points. */
    internal fun spline(ctrl: List<Vec3>, n: Int = 24): List<Vec3> {
        val p = ArrayList<Vec3>()
        val c = listOf(ctrl.first() * 2f - ctrl[1]) + ctrl + listOf(ctrl.last() * 2f - ctrl[ctrl.size - 2])
        val segs = ctrl.size - 1
        for (i in 0..n) {
            val u = i.toFloat() / n * segs
            val k = minOf(u.toInt(), segs - 1)
            val t = u - k
            val p0 = c[k]
            val p1 = c[k + 1]
            val p2 = c[k + 2]
            val p3 = c[k + 3]
            val t2 = t * t
            val t3 = t2 * t
            p += (p1 * 2f + (p2 - p0) * t + (p0 * 2f - p1 * 5f + p2 * 4f - p3) * t2 + (p1 * 3f - p0 - p2 * 3f + p3) * t3) * 0.5f
        }
        return p
    }

    /** Rotates [v] about the unit [axis] by [a] radians (Rodrigues). */
    internal fun rotate(v: Vec3, axis: Vec3, a: Float): Vec3 {
        val c = cos(a)
        val s = sin(a)
        return v * c + axis.cross(v) * s + axis * (axis.dot(v) * (1f - c))
    }

    /** Buds along [pts] at the arc lengths [at]; 2/5 phyllotaxis (144 degrees) from [phase]. */
    internal fun buds(pts: List<Vec3>, at: List<Float>, phase: Float, fatTip: Boolean = false): List<Bud> {
        val arc = FloatArray(pts.size).also { a -> for (i in 1 until pts.size) a[i] = a[i - 1] + pts[i].distanceTo(pts[i - 1]) }
        fun point(s: Float): Pair<Vec3, Vec3> {
            for (i in 1 until pts.size) if (arc[i] >= s) {
                val t = (s - arc[i - 1]) / (arc[i] - arc[i - 1]).coerceAtLeast(1e-6f)
                return pts[i - 1].lerp(pts[i], t) to (pts[i] - pts[i - 1]).normalized()
            }
            return pts.last() to (pts.last() - pts[pts.size - 2]).normalized()
        }
        val tipDir = (pts.last() - pts[pts.size - 2]).normalized()
        return at.mapIndexed { k, s ->
            val (p, tan) = point(s)
            val ref = if (abs(tan.y) > 0.9f) Vec3(1f, 0f, 0f) else UP
            val side = rotate(tan.cross(ref).normalized(), tan, phase + k * 2.513f)
            val dir = (side + tan * 0.9f).normalized()
            Bud(k + 1, s, p, dir, facingOf(p, dir, tipDir), fat = fatTip && k == at.size - 1)
        }
    }

    /** Widens [lo]..[hi] to the next outward / side bud on each side (inward ones skipped), design D27. */
    internal fun widen(buds: List<Bud>, lo: Int, hi: Int, cap: Int = buds.size): IntRange {
        var a = lo
        for (b in buds.filter { it.index < lo && it.facing != Facing.IN }.sortedByDescending { it.index }) {
            a = b.index
            break
        }
        var z = hi
        for (b in buds.filter { it.index > hi && it.facing != Facing.IN }.sortedBy { it.index }) {
            z = b.index
            break
        }
        return a.coerceAtLeast(1)..minOf(z, cap)
    }

    /** The bud in [range] the model answer cuts at: outward first, nearest to [prefer]. */
    internal fun modelBud(buds: List<Bud>, range: IntRange, prefer: Int): Int =
        buds.filter { it.index in range && it.facing == Facing.OUT }.minByOrNull { abs(it.index - prefer) }?.index
            ?: buds.filter { it.index in range && it.facing == Facing.SIDE }.minByOrNull { abs(it.index - prefer) }?.index
            ?: prefer

    // ---------------------------------------------------------------------------------------------
    // rose: hybrid tea in a pot on a 0.40 m stand (graft at 0.70 m)
    // ---------------------------------------------------------------------------------------------

    private class Rose(val lesson: LessonId) {
        val r = Rng(lesson.seed)
        val out = ArrayList<Branch>()
        var nextId = 1
        val baseY = 0.70f

        enum class Kind { TIP_DEAD, DEAD, BROKEN, DISEASED, THIN, CROSS_A, CROSS_B, INWARD, REDUCE_S, REDUCE_W, HEALTHY_S, HEALTHY_W }

        fun plan(): List<Kind> =
            when (lesson) {
                LessonId.R1 -> listOf(Kind.TIP_DEAD, Kind.HEALTHY_S, Kind.THIN, Kind.DEAD, Kind.HEALTHY_S, Kind.DISEASED, Kind.TIP_DEAD, Kind.HEALTHY_S, Kind.BROKEN, Kind.THIN)
                LessonId.R2 -> listOf(Kind.REDUCE_S, Kind.CROSS_A, Kind.CROSS_B, Kind.REDUCE_W, Kind.INWARD, Kind.REDUCE_S, Kind.HEALTHY_S, Kind.REDUCE_W)
                else -> listOf(Kind.REDUCE_S, Kind.DEAD, Kind.CROSS_A, Kind.CROSS_B, Kind.REDUCE_W, Kind.THIN, Kind.REDUCE_S, Kind.INWARD, Kind.TIP_DEAD, Kind.REDUCE_S, Kind.HEALTHY_S)
            }

        fun build(): Tree {
            val kinds = plan()
            val n = kinds.size
            // the front (+Z) gets the most defects: slot 0 sits at the front
            val crossAz = FloatArray(1)
            for ((i, kind) in kinds.withIndex()) {
                val az = (i.toFloat() / n * 2f * PI.toFloat()) + r.range(-0.12f, 0.12f)
                cane(kind, az, crossAz)
            }
            val reach = out.maxOf { b -> b.points.maxOf { Vec3(it.x, 0f, it.z).length } }
            val top = out.maxOf { b -> b.points.maxOf { it.y } }
            return Tree(lesson, out, reach.coerceAtLeast(0.25f), top, baseY)
        }

        fun budStops(len: Float): List<Float> {
            val at = ArrayList<Float>()
            var s = 0.03f
            while (s < len - 0.02f) {
                at += s
                s += if (s < 0.20f) r.range(0.021f, 0.029f) else r.range(0.04f, 0.075f)
            }
            return at
        }

        fun cane(kind: Kind, az0: Float, crossAz: FloatArray) {
            var az = az0
            val strong = kind in setOf(Kind.TIP_DEAD, Kind.REDUCE_S, Kind.HEALTHY_S, Kind.CROSS_A, Kind.CROSS_B, Kind.BROKEN, Kind.DISEASED, Kind.DEAD)
            val thin = kind == Kind.THIN
            val rad = when {
                thin -> r.range(0.0026f, 0.0032f) // < 7 mm
                strong -> r.range(0.0056f, 0.0068f) // > 10 mm
                else -> r.range(0.0039f, 0.0047f) // 7 - 10 mm
            }
            val len = when {
                thin -> r.range(0.36f, 0.46f)
                strong -> r.range(0.55f, 0.72f)
                else -> r.range(0.45f, 0.58f)
            }
            var outw = Vec3(sin(az), 0f, cos(az))
            var tilt = r.range(0.30f, 0.48f)
            var dir = outw * sin(tilt) + UP * cos(tilt)
            when (kind) {
                Kind.INWARD -> {
                    // starts at the rim and grows across the middle
                    dir = (outw * -0.55f + UP).normalized()
                }
                Kind.CROSS_A -> crossAz[0] = az
                Kind.CROSS_B -> {
                    // leans over towards cane A so the two rub at about a third of their length
                    val toA = Vec3(sin(crossAz[0]), 0f, cos(crossAz[0]))
                    dir = (toA * 0.55f + outw * 0.05f + UP).normalized()
                    tilt = 0f
                }
                else -> Unit
            }
            val side = outw.cross(UP) * r.range(-0.04f, 0.04f)
            val p0 = Vec3(outw.x * 0.02f, baseY, outw.z * 0.02f)
            val ctrl =
                if (kind == Kind.BROKEN) {
                    // snapped at 60 %: the top hangs down
                    val brk = p0 + dir * (len * 0.6f) + outw * 0.05f
                    listOf(p0, p0 + dir * (len * 0.3f) + outw * 0.03f + side, brk, brk + outw * 0.08f + Vec3(0f, -0.10f, 0f), brk + outw * 0.12f + Vec3(0f, -0.20f, 0f))
                } else {
                    listOf(p0, p0 + dir * (len * 0.25f) + outw * 0.03f + side, p0 + dir * (len * 0.6f) + outw * 0.07f + side * 1.5f, p0 + dir * len + outw * r.range(0.06f, 0.11f) + Vec3(0f, -0.03f, 0f))
                }
            val pts = spline(ctrl, 28)
            val total = FloatArray(pts.size).also { a -> for (i in 1 until pts.size) a[i] = a[i - 1] + pts[i].distanceTo(pts[i - 1]) }.last()
            val stops = budStops(total)
            // phase: for canes that are shortened, at least two outward / side buds in the target range (design constraint)
            val (lo, hi) =
                when (kind) {
                    Kind.REDUCE_S, Kind.HEALTHY_S, Kind.CROSS_A, Kind.CROSS_B, Kind.TIP_DEAD, Kind.DISEASED, Kind.BROKEN -> 4 to 6
                    else -> 2 to 4
                }
            var bs = buds(pts, stops, 0f)
            for (k in 0 until 10) {
                val cand = buds(pts, stops, k * 0.628f + r.range(0f, 0.2f))
                if (cand.count { it.index in lo..hi && it.facing != Facing.IN } >= 2) {
                    bs = cand
                    break
                }
            }
            val id = nextId++
            var label = Label.OK
            var defect = Defect.HEALTHY
            var pieces = listOf(Piece(0f, total, if (strong) Wood.CANE else Wood.CANE_WEAK))
            var acceptable: IntRange? = null
            var allowBase = false
            var needOutward = false
            var healthyBelow: Int? = null
            var oneOf: Int? = null
            var model: Int? = null
            when (kind) {
                Kind.HEALTHY_S, Kind.HEALTHY_W -> Unit
                Kind.DEAD -> {
                    label = Label.MUST
                    defect = Defect.DEAD
                    pieces = listOf(Piece(0f, total, Wood.DEAD))
                    allowBase = true
                    model = 0
                }
                Kind.TIP_DEAD, Kind.DISEASED, Kind.BROKEN -> {
                    label = Label.MUST
                    defect = when (kind) { Kind.TIP_DEAD -> Defect.TIP_DEAD; Kind.DISEASED -> Defect.DISEASED; else -> Defect.BROKEN }
                    val badFrom = total * (if (kind == Kind.BROKEN) 0.56f else r.range(0.48f, 0.58f))
                    pieces = listOf(Piece(0f, badFrom, Wood.CANE), Piece(badFrom, total, if (kind == Kind.DISEASED) Wood.DISEASED else Wood.DEAD))
                    val h = (bs.firstOrNull { it.s >= badFrom - 0.01f }?.index ?: bs.size + 1)
                    healthyBelow = h
                    acceptable = 1 until h
                    model = modelBud(bs, 1 until h, h - 1)
                }
                Kind.THIN -> {
                    label = Label.MUST
                    defect = Defect.THIN
                    allowBase = true
                    model = 0
                }
                Kind.CROSS_A, Kind.CROSS_B -> {
                    label = Label.MUST
                    defect = Defect.CROSSING
                    oneOf = 1
                    allowBase = true
                    if (kind == Kind.CROSS_B) model = 0
                }
                Kind.INWARD -> {
                    label = Label.MUST
                    defect = Defect.INWARD
                    allowBase = true
                    model = 0
                }
                Kind.REDUCE_S, Kind.REDUCE_W -> {
                    label = Label.MUST
                    defect = Defect.REDUCE
                    needOutward = true
                    acceptable = widen(bs, lo, hi)
                    model = modelBud(bs, lo..hi, (lo + hi) / 2)
                }
            }
            out +=
                Branch(
                    id, null, 0f, pts, rad, rad * (if (kind == Kind.DEAD) 0.6f else 0.5f), bs, pieces, label, defect,
                    oneOf = oneOf, acceptable = acceptable, allowBase = allowBase, needOutward = needOutward, healthyBelow = healthyBelow, modelCut = model,
                )
            // two side shoots near the top of strong canes (last year's flower stems), drawn with the cane
            if (strong && kind != Kind.DEAD && kind != Kind.BROKEN) {
                for (k in 0 until 2) {
                    val s = total * (0.62f + k * 0.15f)
                    val b = out.last()
                    val p = b.pointAt(s)
                    val tan = b.tangentAt(s)
                    val sd = (rotate(outw, UP, (if (k % 2 == 0) -1f else 1f) * r.range(0.8f, 1.3f)) * 0.8f + tan * 0.7f).normalized()
                    val sl = r.range(0.10f, 0.18f)
                    val sp = spline(listOf(p, p + sd * (sl * 0.5f) + Vec3(0f, 0.01f, 0f), p + sd * sl + Vec3(0f, 0.02f, 0f)), 6)
                    val wood = if (pieces.last().wood != Wood.CANE && s >= pieces.last().from) pieces.last().wood else Wood.CANE
                    out += Branch(nextId++, id, s, sp, rad * 0.42f, rad * 0.25f, buds(sp, listOf(sl * 0.4f, sl * 0.75f), k.toFloat()), listOf(Piece(0f, sl * 2f, wood)), Label.OK, Defect.HEALTHY, selectable = false)
                }
            }
            // crossing members: buds below the rubbing point (outward / side) or the whole cane
            if (kind == Kind.CROSS_B) fixCrossing()
        }

        /** Acceptable buds of the crossing pair: outward / side buds below the point where the two canes come closest. */
        fun fixCrossing() {
            val a = out.last { it.defect == Defect.CROSSING && it.selectable && it.modelCut == null }
            val b = out.last { it.defect == Defect.CROSSING && it.selectable && it.modelCut == 0 }
            var best = Float.MAX_VALUE
            var sa = 0f
            var sb = 0f
            // canes all leave the pot together: look for the crossing above their first 0.15 m
            for ((i, p) in a.points.withIndex()) for ((j, q) in b.points.withIndex()) {
                if (a.arc[i] < CROSS_MIN_S || b.arc[j] < CROSS_MIN_S) continue
                val d = p.distanceTo(q)
                if (d < best) {
                    best = d
                    sa = a.arc[i]
                    sb = b.arc[j]
                }
            }
            for ((br, s) in listOf(a to sa, b to sb)) {
                val ok = br.buds.filter { it.s < s - 0.02f && it.facing != Facing.IN }.map { it.index }
                val range = if (ok.isEmpty()) null else ok.min()..ok.max()
                val i = out.indexOf(br)
                out[i] = Branch(br.id, br.parent, br.parentS, br.points, br.r0, br.r1, br.buds, br.pieces, br.label, br.defect, oneOf = br.oneOf, acceptable = range, allowBase = true, needOutward = true, modelCut = br.modelCut)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // apple: open-centre dwarf tree, trunk 0.6 m, four scaffolds
    // ---------------------------------------------------------------------------------------------

    private class Apple(val lesson: LessonId) {
        val r = Rng(lesson.seed)
        val out = ArrayList<Branch>()
        var nextId = 1
        val leadersMust = lesson != LessonId.A1

        fun build(): Tree {
            val trunk = Branch(nextId++, null, 0f, spline(listOf(Vec3(0f, 0f, 0f), Vec3(0.008f, 0.2f, 0f), Vec3(-0.006f, 0.45f, 0.005f), Vec3(0f, 0.68f, 0f)), 10), 0.072f, 0.046f, emptyList(), listOf(Piece(0f, 1f, Wood.BARK)), Label.MAJOR, Defect.TRUNK)
            out += trunk
            val lateralsOf = HashMap<Int, MutableList<Branch>>()
            val mains = ArrayList<Branch>()
            for (i in 0 until 4) {
                val az = i / 4f * 2f * PI.toFloat() + 0.35f + r.range(-0.15f, 0.15f)
                val (body, leader) = scaffold(trunk, az, i)
                mains += body
                lateralsOf[body.id] = ArrayList()
                for (k in 0 until 5) lateralsOf[body.id]!! += lateral(body, k, az)
                out += leader
            }
            // defects by lesson
            val laterals = lateralsOf.values.flatten()
            fun replace(b: Branch, nb: Branch) {
                out[out.indexOf(b)] = nb
            }
            fun wholeOff(b: Branch, defect: Defect, wood: Wood?) =
                Branch(b.id, b.parent, b.parentS, b.points, b.r0, b.r1, b.buds, if (wood == null) b.pieces else listOf(Piece(0f, b.length, wood)), Label.MUST, defect, allowBase = true, modelCut = 0)
            var used = 0
            fun nextLateral(): Branch {
                // spread over the four scaffolds: 0, 5, 10, 15, 1, 6, ...
                val idx = (used % 4) * 5 + (used / 4 + 1) % 5
                used++
                return laterals[idx]
            }
            when (lesson) {
                LessonId.A1 -> {
                    nextLateral().let { replace(it, wholeOff(it, Defect.DEAD, Wood.DEAD)) }
                    nextLateral().let { replace(it, wholeOff(it, Defect.DEAD, Wood.DEAD)) }
                    nextLateral().let { b ->
                        val brk = b.length * 0.5f
                        replace(b, Branch(b.id, b.parent, b.parentS, b.points, b.r0, b.r1, b.buds, listOf(Piece(0f, brk, Wood.BARK), Piece(brk, b.length, Wood.DEAD)), Label.MUST, Defect.BROKEN, allowBase = true, modelCut = 0))
                    }
                    nextLateral().let { b ->
                        replace(b, Branch(b.id, b.parent, b.parentS, b.points, b.r0, b.r1, b.buds, listOf(Piece(0f, b.length * 0.3f, Wood.BARK), Piece(b.length * 0.3f, b.length * 0.5f, Wood.CANKER), Piece(b.length * 0.5f, b.length, Wood.BARK)), Label.MUST, Defect.CANKER, allowBase = true, modelCut = 0))
                    }
                    repeat(2) { sucker(trunk, it) }
                    watershoot(mains[0], 0.45f)
                    watershoot(mains[2], 0.55f)
                }
                LessonId.A2 -> {
                    crossingPair(mains[0], mains[1])
                    inward(mains[2])
                }
                else -> {
                    watershoot(mains[1], 0.45f)
                    watershoot(mains[3], 0.55f)
                    crossingPair(mains[2], mains[3])
                    nextLateral().let { replace(it, wholeOff(it, Defect.DEAD, Wood.DEAD)) }
                    inward(mains[0])
                }
            }
            val reach = out.maxOf { b -> b.points.maxOf { Vec3(it.x, 0f, it.z).length } }
            val top = out.maxOf { b -> b.points.maxOf { it.y } }
            return Tree(lesson, out, reach, top, 0f)
        }

        /** Scaffold body (major) + its extension leader (a separate branch, design `labels`). */
        fun scaffold(trunk: Branch, az: Float, i: Int): Pair<Branch, Branch> {
            val outw = Vec3(sin(az), 0f, cos(az))
            val p0 = Vec3(outw.x * 0.035f, 0.58f + i * 0.025f, outw.z * 0.035f)
            val ang = r.range(0.80f, 0.95f)
            val dir = outw * sin(ang) + UP * cos(ang)
            val len = r.range(1.15f, 1.28f)
            val ctrl = listOf(p0, p0 + dir * (len * 0.3f), p0 + dir * (len * 0.6f) + Vec3(0f, 0.05f, 0f), p0 + dir * (len * 0.85f) + Vec3(0f, 0.15f, 0f), p0 + dir * len + Vec3(0f, 0.3f, 0f))
            val all = spline(ctrl, 40)
            val arc = FloatArray(all.size).also { a -> for (k in 1 until all.size) a[k] = a[k - 1] + all[k].distanceTo(all[k - 1]) }
            val total = arc.last()
            val leaderLen = r.range(0.48f, 0.56f)
            val split = all.indices.first { arc[it] >= total - leaderLen }
            val bodyPts = all.subList(0, split + 1)
            val leadPts = all.subList(split, all.size)
            val rSplit = 0.016f
            val body = Branch(nextId++, trunk.id, 0.58f + i * 0.025f, bodyPts, 0.030f, rSplit, emptyList(), listOf(Piece(0f, 3f, Wood.BARK)), Label.MAJOR, Defect.MAIN)
            out += body
            // leader buds every 5 - 7 cm; keep 25 - 40 % of the length removed in range (at least two outward / side buds)
            val llen = FloatArray(leadPts.size).also { a -> for (k in 1 until leadPts.size) a[k] = a[k - 1] + leadPts[k].distanceTo(leadPts[k - 1]) }.last()
            val stops = ArrayList<Float>()
            var s = 0.04f
            while (s < llen - 0.015f) {
                stops += s
                s += r.range(0.05f, 0.07f)
            }
            fun inWindow(b: Bud, lo: Float, hi: Float): Boolean {
                val removed = (llen - b.s) / llen
                return removed in lo..hi
            }
            var bs = buds(leadPts, stops, 0f)
            for (k in 0 until 10) {
                val cand = buds(leadPts, stops, k * 0.628f)
                if (cand.count { inWindow(it, 0.25f, 0.40f) && it.facing != Facing.IN } >= 2) {
                    bs = cand
                    break
                }
            }
            val window = bs.filter { inWindow(it, 0.25f, 0.40f) }.map { it.index }
            val lo = window.minOrNull() ?: (bs.size * 2 / 3)
            val hi = window.maxOrNull() ?: lo
            val capLow = bs.filter { (llen - it.s) / llen <= 0.5f }.minOfOrNull { it.index } ?: 1
            val wide = widen(bs, lo, hi)
            val acc = maxOf(wide.first, capLow)..wide.last
            val model = modelBud(bs, lo..hi, (lo + hi + 1) / 2)
            val leader =
                Branch(
                    nextId++, body.id, body.length, leadPts, rSplit, 0.008f, bs, listOf(Piece(0f, 3f, Wood.BARK)),
                    if (leadersMust) Label.MUST else Label.OK, Defect.LEADER, acceptable = if (leadersMust) acc else null, needOutward = leadersMust, modelCut = if (leadersMust) model else null,
                )
            return body to leader
        }

        /** A side branch on a scaffold with two twigs and fruiting spurs; two spurs per tree are selectable (keep). */
        fun lateral(body: Branch, k: Int, az: Float): Branch {
            val t = 0.22f + k * 0.15f + r.range(-0.03f, 0.03f)
            val s0 = body.length * t
            val p = body.pointAt(s0)
            val tan = body.tangentAt(s0)
            val sd = rotate(tan.cross(UP).normalized(), tan, (if (k % 2 == 0) 1f else -1f) * r.range(0.5f, 1.1f))
            val ld = (sd * 0.8f + tan * 0.6f + Vec3(0f, 0.15f, 0f)).normalized()
            val ll = r.range(0.28f, 0.50f)
            val pts = spline(listOf(p, p + ld * (ll * 0.45f) + Vec3(0f, -0.015f, 0f), p + ld * ll + Vec3(0f, 0.02f, 0f)), 12)
            val total = FloatArray(pts.size).also { a -> for (i in 1 until pts.size) a[i] = a[i - 1] + pts[i].distanceTo(pts[i - 1]) }.last()
            val stops = ArrayList<Float>()
            var s = 0.05f
            while (s < total - 0.02f) {
                stops += s
                s += r.range(0.045f, 0.065f)
            }
            val lat = Branch(nextId++, body.id, s0, pts, 0.011f, 0.004f, buds(pts, stops, k.toFloat()), listOf(Piece(0f, 3f, Wood.BARK)), Label.OK, Defect.HEALTHY)
            out += lat
            // two twigs (drawn with the lateral)
            for (q in 0 until 2) {
                val tq = (0.4f + q * 0.3f) * total
                val pq = lat.pointAt(tq)
                val tq2 = lat.tangentAt(tq)
                val dq = (tq2.cross(UP).normalized() * (if (q == 0) -1f else 1f) + tq2 + Vec3(0f, 0.35f, 0f)).normalized()
                val lq = r.range(0.12f, 0.24f)
                val tp = spline(listOf(pq, pq + dq * (lq * 0.5f), pq + dq * lq), 6)
                out += Branch(nextId++, lat.id, tq, tp, 0.0045f, 0.002f, buds(tp, listOf(lq * 0.4f, lq * 0.8f), q.toFloat()), listOf(Piece(0f, 3f, Wood.BARK)), Label.OK, Defect.HEALTHY, selectable = false)
            }
            // fruiting spurs: short stubs with a fat bud; the first of each lateral is selectable (keep) on two laterals per scaffold
            var sp = 0.2f * total
            var n = 0
            while (sp < 0.9f * total) {
                val pp = lat.pointAt(sp)
                val d = Vec3(r.range(-0.5f, 0.5f), 0.9f, r.range(-0.5f, 0.5f)).normalized()
                val sl = r.range(0.03f, 0.05f)
                val spPts = listOf(pp, pp + d * (sl * 0.5f), pp + d * sl)
                val selectable = n == 0 && k % 2 == 0
                out += Branch(nextId++, lat.id, sp, spPts, 0.006f, 0.0045f, buds(spPts, listOf(sl - 0.002f), 0f, fatTip = true), listOf(Piece(0f, 3f, Wood.BARK)), if (selectable) Label.KEEP else Label.OK, Defect.SPUR, selectable = selectable)
                sp += r.range(0.16f, 0.24f) * total
                n++
            }
            return lat
        }

        fun youngShoot(parent: Branch, s0: Float, dir: Vec3, len: Float, r0: Float, defect: Defect): Branch {
            val p = parent.pointAt(s0)
            val pts = spline(listOf(p, p + dir * (len * 0.5f) + Vec3(0.01f, 0f, 0f), p + dir * len + Vec3(-0.01f, 0f, 0.01f)), 10)
            val stops = ArrayList<Float>()
            var s = 0.05f
            while (s < len - 0.02f) {
                stops += s
                s += r.range(0.045f, 0.06f)
            }
            return Branch(nextId++, parent.id, s0, pts, r0, r0 * 0.4f, buds(pts, stops, s0 * 10f), listOf(Piece(0f, 3f, Wood.YOUNG)), Label.MUST, defect, allowBase = true, modelCut = 0)
        }

        fun watershoot(body: Branch, t: Float) {
            out += youngShoot(body, body.length * t, UP, r.range(0.60f, 0.85f), 0.010f, Defect.WATERSHOOT)
        }

        fun sucker(trunk: Branch, k: Int) {
            val az = 1.3f + k * 2.4f
            val outw = Vec3(sin(az), 0f, cos(az))
            out += youngShoot(trunk, 0.04f + k * 0.03f, (UP + outw * 0.25f).normalized(), r.range(0.32f, 0.45f), 0.0075f, Defect.SUCKER).let { b ->
                Branch(b.id, b.parent, b.parentS, b.points.map { it + outw * 0.07f }, b.r0, b.r1, b.buds.map { it.copy(pos = it.pos + outw * 0.07f) }, b.pieces, b.label, b.defect, allowBase = true, modelCut = 0)
            }
        }

        /** A strong side branch from a scaffold growing up into the open centre (remove whole). */
        fun inward(body: Branch) {
            val s0 = body.length * 0.35f
            val p = body.pointAt(s0)
            val toCentre = Vec3(-p.x, 0f, -p.z).normalized()
            val shoot = youngShoot(body, s0, (toCentre * 0.8f + UP * 0.7f).normalized(), 0.80f, 0.024f, Defect.INWARD)
            out += Branch(shoot.id, shoot.parent, shoot.parentS, shoot.points, shoot.r0, 0.008f, shoot.buds, listOf(Piece(0f, 3f, Wood.BARK)), Label.MUST, Defect.INWARD, allowBase = true, modelCut = 0)
        }

        /** Two branches from neighbouring scaffolds that cross in the centre (one of the pair goes). */
        fun crossingPair(a: Branch, b: Branch) {
            val pa = a.pointAt(a.length * 0.45f)
            val pb = b.pointAt(b.length * 0.45f)
            val mid = (pa + pb) * 0.5f
            val centre = Vec3(mid.x * 0.3f, mid.y + 0.25f, mid.z * 0.3f)
            val group = 2
            for ((k, pair) in listOf(a to pb, b to pa).withIndex()) {
                val (body, other) = pair
                val s0 = body.length * 0.45f
                val p = body.pointAt(s0)
                val target = centre + (other - p) * 0.25f
                val len = p.distanceTo(target) * 1.6f
                val dir = (target - p).normalized()
                val pts = spline(listOf(p, p + dir * (len * 0.5f) + Vec3(0f, 0.02f, 0f), p + dir * len + Vec3(0f, 0.05f, 0f)), 12)
                val stops = ArrayList<Float>()
                var s = 0.05f
                while (s < len - 0.02f) {
                    stops += s
                    s += r.range(0.05f, 0.065f)
                }
                out += Branch(nextId++, body.id, s0, pts, 0.017f, 0.006f, buds(pts, stops, k.toFloat()), listOf(Piece(0f, 3f, Wood.BARK)), Label.MUST, Defect.CROSSING, oneOf = group, allowBase = true, modelCut = if (k == 1) 0 else null)
            }
            // acceptable: outward / side buds below the point where they come closest
            val pair = out.takeLast(2)
            var best = Float.MAX_VALUE
            var sa = 0f
            var sb = 0f
            for ((i, p) in pair[0].points.withIndex()) for ((j, q) in pair[1].points.withIndex()) {
                if (pair[0].arc[i] < CROSS_MIN_S || pair[1].arc[j] < CROSS_MIN_S) continue
                val d = p.distanceTo(q)
                if (d < best) {
                    best = d
                    sa = pair[0].arc[i]
                    sb = pair[1].arc[j]
                }
            }
            for ((br, s) in listOf(pair[0] to sa, pair[1] to sb)) {
                val ok = br.buds.filter { it.s < s - 0.02f && it.facing != Facing.IN }.map { it.index }
                out[out.indexOf(br)] = Branch(br.id, br.parent, br.parentS, br.points, br.r0, br.r1, br.buds, br.pieces, br.label, br.defect, oneOf = br.oneOf, acceptable = if (ok.isEmpty()) null else ok.min()..ok.max(), allowBase = true, needOutward = true, modelCut = br.modelCut)
            }
        }
    }
}
