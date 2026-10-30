package io.embodit.pruningclass.domain.tree

import io.embodit.pruningclass.domain.model.Vec3
import kotlin.math.acos
import kotlin.math.sqrt

enum class Species { ROSE, APPLE }

/** Judge label of a branch (design `rules.generator.labels`). */
enum class Label { MUST, OK, KEEP, MAJOR }

/** What is wrong with (or special about) a branch: drives its look, the explain text and the next-year table. */
enum class Defect { HEALTHY, DEAD, TIP_DEAD, BROKEN, DISEASED, CANKER, THIN, CROSSING, INWARD, WATERSHOOT, SUCKER, LEADER, REDUCE, SPUR, MAIN, TRUNK }

/** Bud direction relative to the tree's vertical axis (design `rules.bud`). */
enum class Facing { OUT, SIDE, IN }

/** Look of a stretch of wood. */
enum class Wood { CANE, CANE_WEAK, BARK, YOUNG, DEAD, DISEASED, CANKER }

/** One bud. [index] counts from the branch base (1, 2, 3, ...); [s] = arc length from the base, metres. */
data class Bud(val index: Int, val s: Float, val pos: Vec3, val dir: Vec3, val facing: Facing, val fat: Boolean = false)

/** A stretch of the branch drawn with one material, from arc length [from] to [to]. */
data class Piece(val from: Float, val to: Float, val wood: Wood)

/** A cut: keep buds 1..[bud] and cut 5 mm above bud [bud]; [bud] = 0 removes the branch at its base. */
data class Cut(val branch: Int, val bud: Int)

/**
 * One branch of a generated tree, tree-local metres (origin = trunk / pot foot on the floor, +Y up, the tree's front
 * towards +Z). Polyline [points] with cumulative arc length [arc]; radius tapers linearly r0 -> r1.
 * Selectable branches are what the user clicks; the others ([selectable] false) are drawn with their parent and fall with it.
 */
class Branch(
    val id: Int,
    val parent: Int?,
    /** Arc length on the parent where this branch starts. */
    val parentS: Float,
    val points: List<Vec3>,
    val r0: Float,
    val r1: Float,
    val buds: List<Bud>,
    val pieces: List<Piece>,
    val label: Label,
    val defect: Defect,
    val selectable: Boolean = true,
    val oneOf: Int? = null,
    /** Bud indices whose cut earns full credit (already widened to the next outward / side bud, design D27). */
    val acceptable: IntRange? = null,
    val allowBase: Boolean = false,
    val needOutward: Boolean = false,
    /** Dead / diseased: buds from this index up are in the bad wood. */
    val healthyBelow: Int? = null,
    /** Bud index the model answer cuts at (0 = base); null when the model answer leaves the branch. */
    val modelCut: Int? = null,
) {
    val arc: FloatArray =
        FloatArray(points.size).also { a -> for (i in 1 until points.size) a[i] = a[i - 1] + points[i].distanceTo(points[i - 1]) }

    val length: Float
        get() = arc.last()

    /** Base diameter in mm. */
    val diameterMm: Float
        get() = r0 * 2000f

    fun radiusAt(s: Float): Float = r0 + (r1 - r0) * (s / length).coerceIn(0f, 1f)

    fun pointAt(s: Float): Vec3 {
        if (s <= 0f) return points.first()
        for (i in 1 until points.size) {
            if (arc[i] >= s) {
                val t = (s - arc[i - 1]) / (arc[i] - arc[i - 1]).coerceAtLeast(1e-6f)
                return points[i - 1].lerp(points[i], t)
            }
        }
        return points.last()
    }

    fun tangentAt(s: Float): Vec3 {
        for (i in 1 until points.size) if (arc[i] >= s) return (points[i] - points[i - 1]).normalized()
        return (points.last() - points[points.size - 2]).normalized()
    }

    /** Arc length where a cut above [bud] falls (5 mm above it); 0 for a base cut. */
    fun cutS(bud: Int): Float = if (bud <= 0) 0f else (buds.firstOrNull { it.index == bud }?.s ?: 0f) + CUT_ABOVE_M

    /** Volume of wood from [from] to the tip (frustum sum), m³. */
    fun volumeFrom(from: Float): Float {
        var v = 0f
        for (i in 1 until points.size) {
            val a = maxOf(arc[i - 1], from)
            val b = arc[i]
            if (b <= a) continue
            val ra = radiusAt(a)
            val rb = radiusAt(b)
            v += (Math.PI.toFloat() / 3f) * (b - a) * (ra * ra + ra * rb + rb * rb)
        }
        return v
    }

    companion object {
        const val CUT_ABOVE_M = 0.005f
    }
}

/** A generated lesson tree. [crownRadius] / [height] for placement, clearance and the inside-the-crown test. */
class Tree(val lesson: LessonId, val branches: List<Branch>, val crownRadius: Float, val height: Float, val baseY: Float) {
    val species: Species
        get() = lesson.species

    private val byId = branches.associateBy { it.id }

    operator fun get(id: Int): Branch? = byId[id]

    val selectable: List<Branch> = branches.filter { it.selectable }

    fun children(id: Int): List<Branch> = branches.filter { it.parent == id }

    /** True when [id] descends from [ancestor] (or is it). */
    fun descends(id: Int, ancestor: Int): Boolean {
        var b = byId[id]
        while (b != null) {
            if (b.id == ancestor) return true
            b = b.parent?.let { byId[it] }
        }
        return false
    }

    /** Branches whose MUST target counts once per oneOf group. */
    val targets: List<List<Branch>> =
        branches.filter { it.label == Label.MUST }.groupBy { it.oneOf ?: -it.id }.values.toList()

    val mustCount: Int
        get() = targets.size
}

/** Bud facing by the design rule: horizontal radial (trunk axis -> bud) vs the bud's horizontal direction. */
fun facingOf(budPos: Vec3, budDir: Vec3, tipDir: Vec3): Facing {
    val radial = Vec3(budPos.x, 0f, budPos.z)
    var h = Vec3(budDir.x, 0f, budDir.z)
    if (h.length < 0.3f) h = Vec3(tipDir.x, 0f, tipDir.z)
    if (radial.length < 1e-3f || h.length < 1e-4f) return Facing.SIDE
    val c = (radial.normalized().dot(h.normalized())).coerceIn(-1f, 1f)
    val deg = Math.toDegrees(acos(c).toDouble())
    return when {
        deg <= 80.0 -> Facing.OUT
        deg >= 120.0 -> Facing.IN
        else -> Facing.SIDE
    }
}

internal fun len(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
