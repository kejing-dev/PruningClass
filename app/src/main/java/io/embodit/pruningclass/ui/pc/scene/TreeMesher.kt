package io.embodit.pruningclass.ui.pc.scene

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.domain.tree.Branch
import io.embodit.pruningclass.domain.tree.Bud
import io.embodit.pruningclass.domain.tree.Shoot
import io.embodit.pruningclass.domain.tree.Species
import io.embodit.pruningclass.domain.tree.Tree
import io.embodit.pruningclass.domain.tree.Wood
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Geometry of the generated trees (design `rules.generator.shape`): swept 8-sided tubes along each branch's polyline,
 * radius tapering linearly, uv = (around, along x 3) for the bark streaks; six-sided bud cones (4 mm x 6 mm, x2 with bud
 * zoom); flat end caps where a branch is cut. All in tree-local metres. Building the lists is plain CPU work; only
 * [Geo.mesh] touches the engine (one MeshResource each, the caller paces them).
 */
internal class Geo {
    val pos = ArrayList<Vector3>()
    val nor = ArrayList<Vector3>()
    val uv = ArrayList<Vector2>()
    val idx = ArrayList<Int>()

    val empty: Boolean
        get() = idx.isEmpty()

    fun add(p: Vec3, n: Vec3, u: Float, v: Float): Int {
        pos += Vector3(p.x, p.y, p.z)
        nor += Vector3(n.x, n.y, n.z)
        uv += Vector2(u, v)
        return pos.size - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        idx += a
        idx += b
        idx += c
    }

    fun mesh(name: String): MeshResource {
        var mnx = Float.MAX_VALUE
        var mny = Float.MAX_VALUE
        var mnz = Float.MAX_VALUE
        var mxx = -Float.MAX_VALUE
        var mxy = -Float.MAX_VALUE
        var mxz = -Float.MAX_VALUE
        for (p in pos) {
            mnx = min(mnx, p.x); mny = min(mny, p.y); mnz = min(mnz, p.z)
            mxx = max(mxx, p.x); mxy = max(mxy, p.y); mxz = max(mxz, p.z)
        }
        val c = Vector3((mnx + mxx) / 2f, (mny + mxy) / 2f, (mnz + mxz) / 2f)
        val h = Vector3(max(0.001f, (mxx - mnx) / 2f), max(0.001f, (mxy - mny) / 2f), max(0.001f, (mxz - mnz) / 2f))
        return MeshResource.createWithMeshModel(model = MeshModel(positions = pos, triangleIndices = idx, normals = nor, uv0 = uv), bounds = BoundingBox(center = c, halfExtent = h), name = name)
    }
}

internal object TreeMesher {
    private const val SIDES = 8
    private val UP = Vec3(0f, 1f, 0f)

    /** A swept tube along [pts] (with arc lengths [arc]) from [s0] to [s1], radius [r] at arc length s; capped at the cut end. */
    fun tube(g: Geo, pts: List<Vec3>, arc: FloatArray, s0: Float, s1: Float, r: (Float) -> Float, capEnd: Boolean) {
        if (s1 - s0 < 1e-4f) return
        // sample points: s0, the polyline points inside, s1
        val samples = ArrayList<Pair<Float, Vec3>>()
        fun at(s: Float): Vec3 {
            for (i in 1 until pts.size) if (arc[i] >= s) {
                val t = (s - arc[i - 1]) / (arc[i] - arc[i - 1]).coerceAtLeast(1e-6f)
                return pts[i - 1].lerp(pts[i], t)
            }
            return pts.last()
        }
        samples += s0 to at(s0)
        for (i in pts.indices) if (arc[i] > s0 + 1e-4f && arc[i] < s1 - 1e-4f) samples += arc[i] to pts[i]
        samples += s1 to at(s1)
        // parallel-transport frame
        var t0 = (samples[1].second - samples[0].second).normalized()
        var n = (if (abs(t0.y) > 0.9f) Vec3(1f, 0f, 0f) else UP).cross(t0).normalized()
        val rings = ArrayList<Int>()
        for ((k, sample) in samples.withIndex()) {
            val (s, p) = sample
            val t = when {
                k == 0 -> t0
                k == samples.size - 1 -> (p - samples[k - 1].second).normalized()
                else -> (samples[k + 1].second - samples[k - 1].second).normalized()
            }
            n = (n - t * n.dot(t)).let { if (it.length < 1e-4f) (if (abs(t.y) > 0.9f) Vec3(1f, 0f, 0f) else UP).cross(t) else it }.normalized()
            val b = t.cross(n).normalized()
            t0 = t
            val rr = r(s)
            val first = g.pos.size
            for (j in 0..SIDES) {
                val a = j.toFloat() / SIDES * 2f * PI.toFloat()
                val d = n * cos(a) + b * sin(a)
                g.add(p + d * rr, d, j.toFloat() / SIDES, s * 3f)
            }
            rings += first
        }
        for (k in 0 until rings.size - 1) {
            val a = rings[k]
            val c = rings[k + 1]
            for (j in 0 until SIDES) {
                g.tri(a + j, c + j, a + j + 1)
                g.tri(a + j + 1, c + j, c + j + 1)
            }
        }
        if (capEnd) {
            val (s, p) = samples.last()
            val t = (p - samples[samples.size - 2].second).normalized()
            val centre = g.add(p, t, 0.5f, s * 3f)
            val last = rings.last()
            val first = g.pos.size
            for (j in 0..SIDES) {
                val q = g.pos[last + j]
                g.add(Vec3(q.x, q.y, q.z), t, 0.5f, s * 3f)
            }
            for (j in 0 until SIDES) g.tri(centre, first + j, first + j + 1)
        }
    }

    /** One bud cone at [b] (radius [r], length [l]). */
    fun bud(g: Geo, b: Bud, r: Float, l: Float) {
        val d = b.dir
        val n = (if (abs(d.y) > 0.9f) Vec3(1f, 0f, 0f) else UP).cross(d).normalized()
        val m = d.cross(n).normalized()
        val base = g.pos.size
        val tip = g.add(b.pos + d * l, d, 0.5f, 0f)
        for (j in 0..6) {
            val a = j / 6f * 2f * PI.toFloat()
            val o = n * cos(a) + m * sin(a)
            g.add(b.pos + o * r, (o + d * 0.4f).normalized(), j / 6f, 1f)
        }
        for (j in 0 until 6) g.tri(tip, base + 1 + j, base + 2 + j)
    }

    /** Wood geometry of selectable branch [b] up to [cutS] (null = whole), with its non-selectable descendants below the cut, per wood. */
    fun branch(tree: Tree, b: Branch, cutS: Float?): Map<Wood, Geo> {
        val out = HashMap<Wood, Geo>()
        fun add(br: Branch, limit: Float?, inheritWood: Wood?) {
            val end = limit?.coerceAtMost(br.length) ?: br.length
            for (p in br.pieces) {
                val a = p.from.coerceAtMost(br.length)
                val z = minOf(p.to, end)
                if (z <= a) continue
                val wood = inheritWood?.takeIf { p.wood == Wood.CANE || p.wood == Wood.BARK } ?: p.wood
                tube(out.getOrPut(wood) { Geo() }, br.points, br.arc, a, z, br::radiusAt, capEnd = limit != null && z >= end - 1e-4f)
            }
            for (c in tree.children(br.id)) if (!c.selectable && (limit == null || c.parentS < end)) add(c, null, if (inheritWood != null) inheritWood else woodAt(br, c.parentS).takeIf { it == Wood.DEAD || it == Wood.DISEASED })
        }
        add(b, cutS, null)
        return out
    }

    private fun woodAt(b: Branch, s: Float): Wood = b.pieces.lastOrNull { s >= it.from }?.wood ?: b.pieces.first().wood

    /** The part removed by cutting [b] at [cutS]: wood above the cut plus every descendant attached above it, one geometry. */
    fun removed(tree: Tree, b: Branch, cutS: Float): Geo {
        val g = Geo()
        fun whole(br: Branch, from: Float) {
            tube(g, br.points, br.arc, from, br.length, br::radiusAt, capEnd = false)
            for (bd in br.buds) if (bd.s >= from) bud(g, bd, 0.002f, 0.006f)
            for (c in tree.children(br.id)) if (c.parentS >= from) whole(c, 0f)
        }
        whole(b, cutS)
        return g
    }

    /** All buds of the tree still on the wood ([visibleTo] = arc length kept per branch, null = whole branch, absent = gone). */
    fun buds(tree: Tree, visibleTo: Map<Int, Float?>, zoom: Boolean): Geo {
        val g = Geo()
        val k = if (zoom) 2f else 1f
        for (b in tree.branches) {
            if (!visibleTo.containsKey(b.id)) continue
            val limit = visibleTo[b.id]
            for (bd in b.buds) {
                if (limit != null && bd.s > limit) continue
                val (r, l) =
                    when {
                        bd.fat -> 0.0035f to 0.008f
                        tree.species == Species.ROSE -> 0.002f to 0.006f
                        else -> 0.0024f to 0.006f
                    }
                bud(g, bd, r * k, l * k)
            }
        }
        return g
    }

    /** Next-year shoots (young wood), their leaves and the flowers (design `rules.nextYear`). */
    fun nextYear(shoots: List<Shoot>, blossoms: List<Pair<Vec3, Boolean>>, species: Species): Triple<Geo, Geo, Geo> {
        val wood = Geo()
        val leaves = Geo()
        val flowers = Geo()
        for (s in shoots) {
            val pts = s.points
            val arc = FloatArray(pts.size).also { a -> for (i in 1 until pts.size) a[i] = a[i - 1] + pts[i].distanceTo(pts[i - 1]) }
            val len = arc.last()
            tube(wood, pts, arc, 0f, len, { x -> s.r0 * (1f - 0.55f * x / len) }, capEnd = false)
            if (s.leaves) {
                var x = 0.05f
                var k = 0
                while (x < len) {
                    val p = point(pts, arc, x)
                    val t = dir(pts, arc, x)
                    val side = (if (abs(t.y) > 0.9f) Vec3(1f, 0f, 0f) else UP).cross(t).normalized().let { v -> if (k % 2 == 0) v else -v }
                    leaf(leaves, p, (side + t * 0.6f + UP * 0.2f).normalized(), if (species == Species.ROSE) 0.045f else 0.06f)
                    x += 0.055f
                    k++
                }
            }
            if (s.bloom && species == Species.ROSE) bloom(flowers, pts.last(), 0.045f)
        }
        for ((p, half) in blossoms) {
            val n = if (half) 2 else 5
            for (i in 0 until n) {
                val a = i / 5f * 2f * PI.toFloat()
                bloom(flowers, p + Vec3(cos(a) * 0.025f, 0.01f, sin(a) * 0.025f), 0.016f)
            }
        }
        return Triple(wood, leaves, flowers)
    }

    private fun point(pts: List<Vec3>, arc: FloatArray, s: Float): Vec3 {
        for (i in 1 until pts.size) if (arc[i] >= s) return pts[i - 1].lerp(pts[i], (s - arc[i - 1]) / (arc[i] - arc[i - 1]).coerceAtLeast(1e-6f))
        return pts.last()
    }

    private fun dir(pts: List<Vec3>, arc: FloatArray, s: Float): Vec3 {
        for (i in 1 until pts.size) if (arc[i] >= s) return (pts[i] - pts[i - 1]).normalized()
        return (pts.last() - pts[pts.size - 2]).normalized()
    }

    /** A leaf: a pointed quad from [p] along [d], [len] long, two-sided by winding both ways. */
    private fun leaf(g: Geo, p: Vec3, d: Vec3, len: Float) {
        val w = (if (abs(d.y) > 0.9f) Vec3(1f, 0f, 0f) else UP).cross(d).normalized() * (len * 0.28f)
        val n = d.cross(w).normalized()
        val a = g.add(p, n, 0.5f, 0f)
        val b = g.add(p + d * (len * 0.45f) + w, n, 1f, 0.45f)
        val c = g.add(p + d * len, n, 0.5f, 1f)
        val e = g.add(p + d * (len * 0.45f) - w, n, 0f, 0.45f)
        g.tri(a, b, c)
        g.tri(a, c, e)
        g.tri(a, c, b)
        g.tri(a, e, c)
    }

    /** A flower: five petal discs around a point, facing up and out. */
    private fun bloom(g: Geo, p: Vec3, r: Float) {
        for (i in 0 until 5) {
            val a = i / 5f * 2f * PI.toFloat()
            val c = p + Vec3(cos(a) * r * 0.55f, 0f, sin(a) * r * 0.55f)
            val n = (Vec3(cos(a), 1.6f, sin(a))).normalized()
            val u = (if (abs(n.y) > 0.9f) Vec3(1f, 0f, 0f) else UP).cross(n).normalized()
            val v = n.cross(u).normalized()
            val centre = g.add(c, n, 0.5f, 0.5f)
            val first = g.pos.size
            for (j in 0..8) {
                val t = j / 8f * 2f * PI.toFloat()
                g.add(c + u * (cos(t) * r * 0.55f) + v * (sin(t) * r * 0.55f), n, 0.5f, 0.5f)
            }
            for (j in 0 until 8) {
                g.tri(centre, first + j, first + j + 1)
                g.tri(centre, first + j + 1, first + j)
            }
        }
    }
}
