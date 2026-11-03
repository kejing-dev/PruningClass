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
     * Where the tree goes when a lesson starts or is brought back: [TREE_RIGHT_DEG] right of the view, beside the main
     * window, not between the user and it. The system opens the window straight ahead and no API places it next to Stage
     * content (factory native-window-move §4: "the 3D content keeps out of the window"). 45 degrees clears the window
     * (about +/-9 degrees at 1.75 m) and the apple's crown (1.05 m at 2.00 m: +/-31 degrees).
     */
    fun treeBesideWindow(head: Vec3, headForward: Vec3, distance: Float, floorY: Float): Upright =
        treeAhead(head, headForward, distance, floorY, offsetDeg = -TREE_RIGHT_DEG)

    const val TREE_RIGHT_DEG = 45f

    /**
     * Explain card (design `rules.explainDock`): along the user -> cut direction turned 14 degrees right and 6 up, at the cut
     * distance - 0.2 m, and never inside the crown ([crownRadius] around the trunk, 0.1 m clear: no branch in front of
     * it); its azimuth relative to the user -> tree line clamped to -15..+45 degrees (+ = right here). Scale keeps the
     * angular size of a 0.75-scale card at 0.9 m.
     */
    fun dockExplain(head: Vec3, cut: Vec3, tree: Vec3, crownRadius: Float = 0f): Pair<Upright, Float> {
        val toCut = cut - head
        val crownFront = Vec3(tree.x - head.x, 0f, tree.z - head.z).length - crownRadius - 0.10f
        val dist = minOf(toCut.length - 0.20f, if (crownRadius > 0f) crownFront else Float.MAX_VALUE).coerceAtLeast(0.45f)
        val cutYaw = yawOf(toCut)
        val treeYaw = yawOf(tree - head)
        // right = negative yaw
        var rel = Math.toDegrees(angle(cutYaw - treeYaw).toDouble()).toFloat() - 14f
        // at most 15 degrees left of the tree line: the main window stands 45 degrees left of it (design said 35)
        rel = rel.coerceIn(-45f, 15f)
        val yaw = treeYaw + rad(rel)
        val horiz = Vec3(toCut.x, 0f, toCut.z).length.coerceAtLeast(0.05f)
        val pitch = atan2(toCut.y, horiz) + rad(6f)
        val p = head + dirOfYaw(yaw) * (dist * cos(pitch)) + Vec3(0f, dist * sin(pitch), 0f)
        return Upright(p, yaw) to (0.75f * dist / 0.9f)
    }

    fun angle(a: Float): Float {
        var d = a
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        return d
    }

    fun rad(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()
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
