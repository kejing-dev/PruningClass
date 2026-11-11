package io.embodit.pruningclass.domain.usecase

/**
 * What can be tapped in the tree (QA round 3 q3-1, profile D17 "pick exactly"). While a branch is selected its bud
 * ladder is the only target: every branch body stops taking taps (the selected one too: a curved cane can pass in
 * front of its own rings), so the controller ray and the system's eye-hand pinch both reach the rings. The way out is
 * explicit: [换一根枝] in the window or B / Y. Pure Kotlin, unit-tested (PickTest).
 */
object Pick {
    /** Ring diameter per metre of distance from the head (about 1.8 degrees). */
    const val RING_DIA_PER_M = 0.0314f

    /** Ring centres are this many diameters apart. */
    const val RING_GAP = 1.25f

    /** A ring's hit box, in diameters (PcScene's flat box: half size 1.2 x the ring's radius). */
    const val RING_HIT = 1.2f

    /** Branch bodies take taps only while no branch is selected. */
    fun branchesTappable(selected: Int?): Boolean = selected == null

    /** Neighbouring rings' hit boxes never overlap (a tap on one ring is never read as the next). */
    fun ringsSeparate(): Boolean = RING_HIT < RING_GAP
}
