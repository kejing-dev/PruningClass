package io.embodit.pruningclass.domain.usecase

/**
 * Controller rays per controller (profile D41 corrected 2026-11-13, "double ray"): in Full Space the system draws one ray
 * per controller and the app draws none; a mode with a tool in the hand would show no ray at all. Pruning Class has no
 * hand-held tool (the scissors are a 3D target that is tapped, not held). Unit-tested for every phase (RayPolicyTest).
 */
object RayPolicy {
    /** Rays the app itself draws for one controller in a phase named [phase] (any). */
    @Suppress("UNUSED_PARAMETER")
    fun appRays(phase: String): Int = 0

    /** Whether the phase puts a tool in the user's hand (then no ray may show). None in this app. */
    @Suppress("UNUSED_PARAMETER")
    fun toolInHand(phase: String): Boolean = false

    /** Rays the user sees for one controller: the system's one (not with a tool in hand) + the app's. */
    fun seenRays(phase: String): Int = (if (toolInHand(phase)) 0 else 1) + appRays(phase)
}
