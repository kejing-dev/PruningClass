package io.embodit.pruningclass.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design package `palette`). This is the only file with colour literals. Four `ColorScheme`
 * roles are fixed here and injected into `PicoTheme` by [PruningTheme]; the others inherit SpatialUI Vibrant. The brand
 * tokens are for the 3D content (wood, buds, flowers, marks) and the lesson-card drawings only.
 */
object PcColors {
    // ---- ColorScheme roles with a fixed design value
    val LabelPrimaryLight = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error: wrong cut
    val Alert = Color(0xFFFF8A1F) // design-style: fixed-figma-color alert: off target, hints, removal over 20 %
    val Passable = Color(0xFF7CF2A3) // design-style: fixed-figma-color passable: right cut
    val Interaction = Color(0xFFFFD24A) // design-style: fixed-figma-color interaction: preview, ring, selection

    // ---- brand tokens (design `palette.brandTokens`) and the mock-up's wood colours
    val Bark = Color(0xFF7A6554) // design-style: fixed-figma-color bark (apple)
    val Cane = Color(0xFF4D6A2C) // design-style: fixed-figma-color cane: rose, strong (mock-up greens)
    val CaneWeak = Color(0xFF58682E) // design-style: fixed-figma-color caneWeak
    val Young = Color(0xFF7A3E2A) // design-style: fixed-figma-color young: watershoots, suckers
    val Deadwood = Color(0xFF6B4A2F) // design-style: fixed-figma-color deadwood
    val Diseased = Color(0xFF3A2033) // design-style: fixed-figma-color diseased: black spot
    val Canker = Color(0xFF2B1D16) // design-style: fixed-figma-color canker
    val CutFace = Color(0xFFE8D3A8) // design-style: fixed-figma-color cutFace
    val BudRose = Color(0xFF9C2F4A) // design-style: fixed-figma-color budRose
    val BudApple = Color(0xFF7A5534) // design-style: fixed-figma-color budApple
    val Leaf = Color(0xFF5E8C3A) // design-style: fixed-figma-color leaf
    val BloomRose = Color(0xFFE85A7A) // design-style: fixed-figma-color bloomRose
    val BloomApple = Color(0xFFF6E7EC) // design-style: fixed-figma-color bloomApple
    val Pot = Color(0xFFB5653A) // design-style: fixed-figma-color pot
    val Stand = Color(0xFF6E5238) // design-style: fixed-figma-color stand
    val Soil = Color(0xFF2F2219) // design-style: fixed-figma-color soil
    val Sprout = Color(0xFF8A9A2B) // design-style: fixed-figma-color sprout: next year's shoots, app icon ground
    val Charcoal = Color(0xFF2B2622) // design-style: fixed-figma-color charcoal: plates
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color white

    /** Ink on yellow / white buttons: charcoal (10.4 : 1 on #FFD24A). */
    val Ink = Charcoal

    /**
     * Ink on the error red (danger buttons): near black. Charcoal is 5.0 : 1 on #FF5F52 on paper but rendered lighter on
     * the emulator glass (measured 4.2 : 1); this is 6.7 : 1 on paper.
     */
    val InkOnError = Color(0xFF000000) // design-style: fixed-figma-color inkOnError: text on danger buttons
}
