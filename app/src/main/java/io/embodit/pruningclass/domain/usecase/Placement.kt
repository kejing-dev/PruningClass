package io.embodit.pruningclass.domain.usecase

import io.embodit.pruningclass.domain.model.Quat4
import io.embodit.pruningclass.domain.model.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** A pose that only turns about the vertical: position + yaw (0 = facing -Z, positive = turned left). */
data class Upright(val position: Vec3, val yaw: Float) {
    /** Rotation for a panel / plate whose +Z faces back along -forward (towards the viewer). */
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)
}

/**
 * Every place the app puts content (design `rules.placement`, `rules.explainDock`; profile D9 / D21; the 2D pages are in
 * the main system window, which the system places):
 * only the head's position and horizontal heading count, pitch and roll never do (`placeUpright`).
 */
object Placement {
    /** Horizontal heading of a head [forward]: 0 looks along -Z, positive turns left. */
    fun yawOf(forward: Vec3): Float {
        val f = Vec3(forward.x, 0f, forward.z)
        if (f.length < 1e-3f) return 0f
        return atan2(-f.x, -f.z)
    }

    fun dirOfYaw(yaw: Float): Vec3 = Vec3(-sin(yaw), 0f, -cos(yaw))

    /**
     * Content [distance] ahead of the head, turned [offsetDeg] (positive = left), [belowDeg] below the eye line, upright and
     * facing the viewer. The head's pitch and roll do not enter.
     */
    fun placeUpright(head: Vec3, headForward: Vec3, distance: Float, belowDeg: Float, offsetDeg: Float = 0f): Upright {
        val yaw = yawOf(headForward) + rad(offsetDeg)
        val drop = tan(rad(belowDeg)) * distance
        val p = head + dirOfYaw(yaw) * distance - Vec3(0f, drop, 0f)
        return Upright(p, yaw)
    }

    /** The tree on the floor [distance] ahead, turned [offsetDeg] (positive = left), its front (+Z of the tree) towards the viewer. */
    fun treeAhead(head: Vec3, headForward: Vec3, distance: Float, floorY: Float, offsetDeg: Float = 0f): Upright {
        val yaw = yawOf(headForward) + rad(offsetDeg)
        val p = head + dirOfYaw(yaw) * distance
        return Upright(Vec3(p.x, floorY, p.z), yaw)
    }

    /**
     * Where the tree goes when a lesson starts or is brought back: right of the view, beside the main window, never
     * between the user and it. The system opens the window straight ahead and no API places it next to Stage content
     * (factory native-window-move §4), so the trunk goes [Clearance.treeRightDeg] right: window half-angle + 10 degrees
     * (D41) + the half-angle of the tree's [reach] (next year's growth included) at [distance].
     */
    fun treeBesideWindow(head: Vec3, headForward: Vec3, distance: Float, floorY: Float, reach: Float): Upright =
        treeAhead(head, headForward, distance, floorY, offsetDeg = -Clearance.treeRightDeg(reach, distance))

    /**
     * Explain card (design `rules.explainDock`, D41): beside the tree on its right (away from the window), never in front
     * of it: its left edge [Clearance.PLATE_GAP_DEG] right of the tree's right edge as seen from [head] ([treeRightDeg],
     * degrees right of the head -> trunk line: `Clearance.rightEdgeDeg` over what is shown now); at the crown's front
     * distance (0.6 - 1.2 m, [reach]), at the cut's height + 6 degrees. Scale keeps the angular size of a 0.75-scale card at
     * 0.9 m. [cardWidthM] is the card's width at scale 1.
     */
    fun dockExplain(head: Vec3, cut: Vec3, tree: Vec3, reach: Float, treeRightDeg: Float, cardWidthM: Float): Pair<Upright, Float> {
        val toTree = Vec3(tree.x - head.x, 0f, tree.z - head.z)
        val dT = toTree.length.coerceAtLeast(reach + 0.05f)
        val dist = (dT - reach).coerceIn(0.6f, 1.2f)
        val scale = 0.75f * dist / 0.9f
        val cardHalf = deg(atan2(cardWidthM * scale / 2f, dist)) + Clearance.parallaxDeg(dist)
        val rel = treeRightDeg + Clearance.PLATE_GAP_DEG + cardHalf
        // right = negative yaw
        val yaw = yawOf(toTree) - rad(rel)
        val toCut = cut - head
        val horiz = Vec3(toCut.x, 0f, toCut.z).length.coerceAtLeast(0.05f)
        val pitch = (atan2(toCut.y, horiz) + rad(6f)).coerceIn(rad(-35f), rad(25f))
        val p = head + dirOfYaw(yaw) * (dist * cos(pitch)) + Vec3(0f, dist * sin(pitch), 0f)
        return Upright(p, yaw) to scale
    }

    /**
     * The "open panel" chip: [distance] ahead, [belowDeg] below the eyes, [offsetDeg] left of the view; when that would
     * put it over the tree (trunk at [tree], [reach]) it moves to the tree's left edge (the window side), D41.
     */
    fun chipBesideTree(head: Vec3, headForward: Vec3, distance: Float, belowDeg: Float, offsetDeg: Float, chipWidthM: Float, tree: Vec3?, reach: Float): Upright {
        val want = placeUpright(head, headForward, distance, belowDeg, offsetDeg)
        if (tree == null) return want
        val toTree = Vec3(tree.x - head.x, 0f, tree.z - head.z)
        val dT = toTree.length.coerceAtLeast(reach + 0.05f)
        val treeHalf = Clearance.treeHalfDeg(reach, dT)
        val chipHalf = deg(atan2(chipWidthM / 2f, distance)) + Clearance.parallaxDeg(distance)
        val treeYaw = yawOf(toTree)
        val off = deg(angle(want.yaw - treeYaw))
        val need = treeHalf + Clearance.PLATE_GAP_DEG + chipHalf
        if (kotlin.math.abs(off) >= need) return want
        val yaw = treeYaw + rad(need)
        val drop = kotlin.math.tan(rad(belowDeg)) * distance
        return Upright(head + dirOfYaw(yaw) * distance - Vec3(0f, drop, 0f), yaw)
    }

    fun angle(a: Float): Float {
        var d = a
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }

    fun rad(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()

    fun deg(r: Float): Float = Math.toDegrees(r.toDouble()).toFloat()
}

/**
 * Re-planting as an explicit state machine (profile D1 "placing must end"): LOCKED -> [replant] -> PLACING (the ghost
 * follows the ray) -> [drop on a valid spot] -> LOCKED; [cancel] -> LOCKED. Only PLACING moves the tree; in LOCKED a ray,
 * a trigger or a drag never changes its place (the handle drag is a separate, explicit move).
 */
class PlaceMachine {
    enum class Mode { LOCKED, PLACING }

    var mode = Mode.LOCKED
        private set
    var position: Vec3 = Vec3.ZERO
        private set
    var ghost: Vec3? = null
        private set

    fun replant() {
        mode = Mode.PLACING
        ghost = null
    }

    /** The ray's floor point while placing; ignored when locked. */
    fun aim(at: Vec3?) {
        if (mode == Mode.PLACING) ghost = at
    }

    /** Trigger / pinch: drops the tree at the ghost when the spot is allowed. Returns true when it was placed. */
    fun drop(allowed: Boolean): Boolean {
        val g = ghost
        if (mode != Mode.PLACING || g == null || !allowed) return false
        position = g
        mode = Mode.LOCKED
        ghost = null
        return true
    }

    fun cancel() {
        mode = Mode.LOCKED
        ghost = null
    }

    /** Explicit moves (auto place, the handle drag): only these change the locked place. */
    fun set(p: Vec3) {
        position = p
    }
}
