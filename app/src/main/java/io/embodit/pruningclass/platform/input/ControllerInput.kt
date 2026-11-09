package io.embodit.pruningclass.platform.input

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.pruningclass.domain.model.PointerRay
import io.embodit.pruningclass.domain.model.Quat4
import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.platform.PcLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** What the controller delivers in one frame, Stage space. */
data class ControllerSample(
    val ray: PointerRay,
    /** Trigger 0..1 and the device's own pressed flag. */
    val trigger: Float,
    val pressed: Boolean,
    /** Thumbstick x (-1 left .. 1 right) and y (-1 back .. 1 forward) of the aiming controller. */
    val stickX: Float,
    val stickY: Float,
    val right: Boolean,
)

/**
 * Controller ray and buttons: pose from `ControllerTrackingProvider.latestData` (read on the main
 * thread), trigger and thumbstick from `ControllerActionListener`. That listener runs on the data
 * source's thread: it only copies the values into atomics, which the frame system reads.
 *
 * The ray leaves the controller pose along its -Z. With two controllers the one whose trigger was
 * touched last aims (the right one at first). Same approach as the Spatial Ruler's
 * `ControllerPointerSource`; the thumbstick (both axes) is added for the factory's default interaction rule.
 */
class ControllerInput {
    private var provider: ControllerTrackingProvider? = null
    private val rightValue = AtomicInteger(0)
    private val leftValue = AtomicInteger(0)
    private val rightPressed = AtomicBoolean(false)
    private val leftPressed = AtomicBoolean(false)
    private val rightStick = AtomicInteger(0)
    private val leftStick = AtomicInteger(0)
    private val rightStickY = AtomicInteger(0)
    private val leftStickY = AtomicInteger(0)
    private var useRight = true
    private var lastSupport: DataProvider.SupportState? = null
    private val faceDown = AtomicBoolean(false)
    private val facePresses = AtomicInteger(0)
    private val backDown = AtomicBoolean(false)
    private val backPresses = AtomicInteger(0)

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { action: ControllerActionData ->
            // A (right) or X (left): counted on the press edge, read by the frame system (design I6)
            val face = runCatching { action.right.aButtonPressed || action.left.xButtonPressed }.getOrDefault(false)
            if (face && !faceDown.getAndSet(true)) facePresses.incrementAndGet()
            if (!face) faceDown.set(false)
            // B (right) or Y (left): the "back" key of the design (no system back key reaches the app)
            val back = runCatching { action.right.bButtonPressed || action.left.yButtonPressed }.getOrDefault(false)
            if (back && !backDown.getAndSet(true)) backPresses.incrementAndGet()
            if (!back) backDown.set(false)
            rightValue.set(java.lang.Float.floatToIntBits(action.right.triggerValue))
            rightPressed.set(action.right.triggerPressed)
            leftValue.set(java.lang.Float.floatToIntBits(action.left.triggerValue))
            leftPressed.set(action.left.triggerPressed)
            rightStick.set(java.lang.Float.floatToIntBits(runCatching { action.right.thumbstickValue.x }.getOrDefault(0f)))
            leftStick.set(java.lang.Float.floatToIntBits(runCatching { action.left.thumbstickValue.x }.getOrDefault(0f)))
            rightStickY.set(java.lang.Float.floatToIntBits(runCatching { action.right.thumbstickValue.y }.getOrDefault(0f)))
            leftStickY.set(java.lang.Float.floatToIntBits(runCatching { action.left.thumbstickValue.y }.getOrDefault(0f)))
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        PcLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
    }

    /** Number of A / X presses since the last call. */
    fun takeFacePresses(): Int = facePresses.getAndSet(0)

    /** B / Y presses since the last call (counted on the press edge). */
    fun takeBackPresses(): Int = backPresses.getAndSet(0)

    /** Once per frame on the main thread. Null when no controller delivers a pose. */
    fun sample(): ControllerSample? {
        val p = provider ?: return null
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) {
            lastSupport = support
            PcLog.i("TRACK controller support=$support")
        }
        if (support != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val rightV = java.lang.Float.intBitsToFloat(rightValue.get())
        val leftV = java.lang.Float.intBitsToFloat(leftValue.get())
        val rightDown = rightPressed.get()
        val leftDown = leftPressed.get()
        if (useRight && (leftV > SWITCH_AT || leftDown) && rightV < REST_BELOW && !rightDown) useRight = false
        if (!useRight && (rightV > SWITCH_AT || rightDown) && leftV < REST_BELOW && !leftDown) useRight = true
        val right = data.right
        val left = data.left
        val pickRight = if (useRight) right != null else left == null
        val pose = (if (pickRight) right else left) ?: return null
        val position = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val rotation = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)
        if (!position.isFinite()) return null
        if (stale(stamp(data), position, rotation)) return null
        val forward = rotation.rotate(Vec3.AHEAD)
        if (!forward.isFinite() || forward.length < 0.5f) return null
        val stick = java.lang.Float.intBitsToFloat((if (pickRight) rightStick else leftStick).get())
        return ControllerSample(
            ray = PointerRay(position, forward.normalized()),
            trigger = if (pickRight) rightV else leftV,
            pressed = if (pickRight) rightDown else leftDown,
            stickX = stick,
            stickY = java.lang.Float.intBitsToFloat((if (pickRight) rightStickY else leftStickY).get()),
            right = pickRight,
        )
    }

    // A controller that stops tracking may keep its last pose: a pose whose data time stamp and value have not moved for
    // STALE_MS is treated as lost (only once the time stamp has been seen to move, so a source without one never hides)
    private var lastStamp = Long.MIN_VALUE
    private var stampMoved = false
    private var lastPos = Vec3(0f, 0f, 0f)
    private var lastRot = Quat4(0f, 0f, 0f, 1f)
    private var stillSinceMs = 0L
    private var wasStale = false

    private fun stale(stamp: Long?, position: Vec3, rotation: Quat4): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val samePose = position.distanceTo(lastPos) < 1e-5f && abs(rotation.w - lastRot.w) < 1e-6f && abs(rotation.x - lastRot.x) < 1e-6f
        val sameStamp = stamp == null || stamp == lastStamp
        if (stamp != null && lastStamp != Long.MIN_VALUE && stamp != lastStamp) stampMoved = true
        if (!(samePose && sameStamp)) stillSinceMs = now
        if (stamp != null) lastStamp = stamp
        lastPos = position
        lastRot = rotation
        val isStale = stampMoved && samePose && sameStamp && now - stillSinceMs > STALE_MS
        if (isStale != wasStale) PcLog.i("TRACK controller ${if (isStale) "lost (pose frozen ${now - stillSinceMs} ms)" else "tracked again"}")
        wasStale = isStale
        return isStale
    }

    /** The data's own time stamp (internal in the SDK: read by reflection, null when it cannot be read). */
    private fun stamp(data: Any): Long? = runCatching { stampGetter?.invoke(data) as? Long }.getOrNull()

    private val stampGetter: java.lang.reflect.Method? by lazy {
        runCatching { ControllerTrackingData::class.java.methods.firstOrNull { it.name.startsWith("getTimestamp") && it.parameterCount == 0 } }.getOrNull()
    }

    companion object {
        private const val STALE_MS = 2_000L
        private const val SWITCH_AT = 0.15f
        private const val REST_BELOW = 0.05f
    }
}
