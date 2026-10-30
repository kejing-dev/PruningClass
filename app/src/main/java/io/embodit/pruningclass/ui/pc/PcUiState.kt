package io.embodit.pruningclass.ui.pc

import io.embodit.pruningclass.domain.model.BlockReason
import io.embodit.pruningclass.domain.model.LanguagePref
import io.embodit.pruningclass.domain.model.LessonRecord
import io.embodit.pruningclass.domain.model.Settings
import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.domain.model.Volume
import io.embodit.pruningclass.domain.tree.Cut
import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.Reason
import io.embodit.pruningclass.domain.tree.Score
import io.embodit.pruningclass.domain.tree.Species

/** Base states of the design (`stateModel.base`). S07 / S08 / S09 are PRUNING with a selection / preview / explain. */
enum class Phase(val id: String) {
    STARTING("S00"),
    WELCOME("S01"),
    PICKER("S02"),
    FINDING("S03"),
    PLACING("S04"),
    RULES("S05"),
    PRUNING("S06"),
    RESULT("S11"),
    GRADUATED("S13"),
    RULE_CARD("S14"),
    SETTINGS("S15"),
    BLOCKED("S16"),
    RELOCATING("S20");

    /** In a lesson with the tree standing (S05 - S13 and the overlays on top). */
    val inLesson: Boolean
        get() = this == RULES || this == PRUNING || this == RESULT || this == GRADUATED
}

/** Result page view segment (design `rules.nextYear.views`). */
enum class View { NOW, YOURS, MODEL }

/** Inline confirm inside the settings panel (S15b). */
enum class Confirm { QUIT, RESET }

/** The explain card's content (design `copy.explain`). */
data class Explain(val reason: Reason?, val branch: Int, val wrong: Boolean, val canRejoin: Boolean, val serial: Int, val hintRule: String? = null)

data class PcUiState(
    val phase: Phase = Phase.STARTING,
    val lesson: LessonId = LessonId.R1,
    val settings: Settings = Settings(),
    val records: Map<LessonId, LessonRecord> = emptyMap(),
    val graduated: Set<Species> = emptySet(),
    val cuts: List<Cut> = emptyList(),
    /** Bumped whenever the tree is (re)placed in front of the user (the scene re-runs auto placement). */
    val placeSerial: Int = 0,
    val treeSerial: Int = 0,
    val floorReady: Boolean = false,
    val virtualFloor: Boolean = false,
    val floorNotice: String? = null,
    val selected: Int? = null,
    val preview: Int? = null,
    val explain: Explain? = null,
    val hints: Boolean = false,
    val hintSerial: Int = 0,
    val score: Score? = null,
    val view: View = View.NOW,
    val cardSpecies: Species = Species.ROSE,
    /** Where S14 / S15 return to. */
    val back: Phase = Phase.PICKER,
    val confirm: Confirm? = null,
    val turning: Boolean = false,
    val moving: Boolean = false,
    val inside: Boolean = false,
    val removedPct: Float = 0f,
    val focusBranch: Int? = null,
    val focusSerial: Int = 0,
    val rotateSerial: Int = 0,
    val rotateBy: Float = 0f,
    val recallSerial: Int = 0,
    val blockReason: BlockReason? = null,
    // ---- debug holds (QA hooks; the same events as the user's input)
    val debugHold: Boolean = false,
    val testTexture: Boolean = false,
    val debugNoFloor: Boolean = false,
    val debugInside: Boolean = false,
) {
    val english: Boolean
        get() = settings.language == LanguagePref.EN

    val exam: Boolean
        get() = lesson.exam

    /** QA name base[+overlays][:lesson] (design `stateModel.naming`). */
    val qaName: String
        get() {
            val base =
                when {
                    phase == Phase.PRUNING && explain != null -> "S09"
                    phase == Phase.PRUNING && preview != null -> "S08"
                    phase == Phase.PRUNING && selected != null -> "S07"
                    phase == Phase.RESULT && view != View.NOW -> "S12"
                    phase == Phase.SETTINGS && confirm != null -> "S15b"
                    else -> phase.id
                }
            val sb = StringBuilder(base)
            if (hints && phase == Phase.PRUNING) sb.append("+S10")
            if (english) sb.append("+S17")
            if (turning) sb.append("+S18")
            if (moving) sb.append("+S19")
            if (inside) sb.append("+S21")
            if (phase.inLesson || phase == Phase.PLACING || phase == Phase.FINDING || phase == Phase.RELOCATING || (phase == Phase.SETTINGS && back.inLesson)) sb.append(":$lesson")
            return sb.toString()
        }
}

/** Panel, scene and debug inputs. Every change of the model goes through [PcViewModel.onEvent]. */
sealed interface PcEvent {
    // ---- panels
    data object WelcomeGo : PcEvent
    data class LessonPicked(val lesson: LessonId) : PcEvent
    data object StartPruning : PcEvent
    data object Undo : PcEvent
    data object Done : PcEvent
    data object HintToggle : PcEvent
    data class Turn(val deg: Float) : PcEvent
    data class ViewSelected(val view: View) : PcEvent
    data object Again : PcEvent
    data object NextLesson : PcEvent
    data class ReviewRow(val branch: Int) : PcEvent
    data object Rejoin : PcEvent
    data object GotIt : PcEvent
    data object OpenRuleCard : PcEvent
    data class CardSpecies(val species: Species) : PcEvent
    data object Close : PcEvent
    data object SettingsToggle : PcEvent
    data class Sound(val on: Boolean) : PcEvent
    data class VolumeSet(val v: Volume) : PcEvent
    data class BudZoom(val on: Boolean) : PcEvent
    data class FloorStep(val steps: Int) : PcEvent
    data class Seated(val on: Boolean) : PcEvent
    data class Language(val v: LanguagePref) : PcEvent
    data object Recall : PcEvent
    data object Replant : PcEvent
    data object ResetLesson : PcEvent
    data object AskResetAll : PcEvent
    data object AskQuit : PcEvent
    data object ConfirmYes : PcEvent
    data object ConfirmNo : PcEvent
    data object RetryBlocked : PcEvent
    data object QuitBlocked : PcEvent

    // ---- scene (the user's 3D input and the scene's milestones)
    data object PanelsReady : PcEvent
    data class HeadSample(val position: Vec3, val yaw: Float) : PcEvent
    data class FloorFound(val y: Float, val virtual: Boolean) : PcEvent
    data object Placed : PcEvent
    data class BranchTapped(val branch: Int) : PcEvent
    data class BudTapped(val branch: Int, val bud: Int) : PcEvent
    data object PreviewTapped : PcEvent
    data object TappedElsewhere : PcEvent
    data class BudCursor(val bud: Int) : PcEvent
    data object Trigger : PcEvent
    data class Turning(val on: Boolean) : PcEvent
    data class Moving(val on: Boolean) : PcEvent
    data class Inside(val on: Boolean) : PcEvent
    data object Replanted : PcEvent
    /** S20 main button [Cancel]: back to the state before, the tree stays where it was. */
    data object CancelReplant : PcEvent
    data class BlockChanged(val reason: BlockReason?) : PcEvent
    data object ExplainTimeout : PcEvent
    data object WentBackground : PcEvent
    data object CameForeground : PcEvent

    // ---- debug-only switches (state jumps go through the events above)
    data class DebugState(val id: String) : PcEvent
    data class DebugAutocut(val kind: String) : PcEvent
    data class DebugExplain(val reason: Reason) : PcEvent
    data class DebugNoFloor(val on: Boolean) : PcEvent
    data object DebugFloorArrive : PcEvent
    data class DebugBlocked(val on: Boolean) : PcEvent
    data class DebugInside(val on: Boolean) : PcEvent
    data class DebugTestTexture(val on: Boolean) : PcEvent
    data object DebugRelease : PcEvent
    data object DebugClear : PcEvent
}

/** One-shot effects for the screen. */
sealed interface PcEffect {
    data object QuitApp : PcEffect
}
