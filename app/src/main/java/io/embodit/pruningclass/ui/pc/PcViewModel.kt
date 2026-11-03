package io.embodit.pruningclass.ui.pc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.embodit.pruningclass.data.Store
import io.embodit.pruningclass.domain.model.BlockReason
import io.embodit.pruningclass.domain.model.Settings
import io.embodit.pruningclass.domain.model.Vec3
import io.embodit.pruningclass.domain.tree.Cut
import io.embodit.pruningclass.domain.tree.Judge
import io.embodit.pruningclass.domain.tree.Label
import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.NextYear
import io.embodit.pruningclass.domain.tree.Reason
import io.embodit.pruningclass.domain.tree.Species
import io.embodit.pruningclass.domain.tree.Tree
import io.embodit.pruningclass.domain.tree.TreeGen
import io.embodit.pruningclass.platform.PcGraph
import io.embodit.pruningclass.platform.PcLog
import io.embodit.pruningclass.platform.audio.Sfx
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One generated tree per lesson, shared by the ViewModel and the scene (the generator is deterministic). */
object TreeCache {
    private val trees = HashMap<LessonId, Tree>()

    @Synchronized fun get(l: LessonId): Tree = trees.getOrPut(l) { TreeGen.generate(l) }
}

/**
 * The state machine of the app (design `states`, `interactions`, `rules.judge`). Panel controls, the user's 3D input that
 * the scene reports (branch / bud taps, the stick, drags) and the scene's milestones (floor found, tree placed) all arrive
 * through [onEvent]; debug commands use the same events (factory rule 2026-10-29). Main thread only.
 */
class PcViewModel(private val store: Store) : ViewModel() {

    private val _state: MutableStateFlow<PcUiState>
    val state: StateFlow<PcUiState>
    private val _effects = MutableSharedFlow<PcEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<PcEffect> = _effects

    private var placeReturn: Phase? = null
    private var explainSerial = 0
    private var noticeSerial = 0
    private var cardFromSettings = false
    private var hintTurned = false
    private var lastLogged = ""
    var headPosition: Vec3? = null
        private set

    init {
        _state = MutableStateFlow(PcUiState(settings = store.settings(), records = records(), graduated = Species.entries.filter { store.graduated(it) }.toSet()))
        state = _state.asStateFlow()
        PcGraph.sfx.muted = !_state.value.settings.sound
        PcGraph.sfx.gain = _state.value.settings.volume.gain
        PcLog.i("SETTINGS ${_state.value.settings}")
    }

    private fun records() = LessonId.entries.associateWith { store.record(it) }

    /**
     * A new Stage composition (launch, relaunch after the system closed the Stage). The ViewModel lives for the process
     * (shared with the window), so a surviving one goes back to S00 with the saved settings and records, as a cold start.
     */
    fun stageStarted() {
        if (_state.value.phase == Phase.STARTING) return
        PcLog.i("STAGE restart: state back to S00 (was ${_state.value.qaName})")
        placeReturn = null
        cardFromSettings = false
        hintTurned = false
        set { PcUiState(settings = store.settings(), records = records(), graduated = Species.entries.filter { store.graduated(it) }.toSet()) }
    }

    val tree: Tree
        get() = TreeCache.get(_state.value.lesson)

    fun onEvent(e: PcEvent) {
        when (e) {
            is PcEvent.HeadSample -> headPosition = e.position
            is PcEvent.Turning -> if (_state.value.turning != e.on) set { it.copy(turning = e.on) }
            is PcEvent.Moving -> if (_state.value.moving != e.on) set { it.copy(moving = e.on) }
            is PcEvent.Inside -> if (_state.value.inside != e.on && !_state.value.debugInside) set { it.copy(inside = e.on) }
            else -> {
                PcLog.i("EVENT $e")
                handle(e)
            }
        }
        logState()
    }

    private fun handle(e: PcEvent) {
        val s = _state.value
        when (e) {
            // ------------------------------------------------------------ start
            PcEvent.PanelsReady -> if (s.phase == Phase.STARTING) set { it.copy(phase = if (store.welcomed) Phase.PICKER else Phase.WELCOME) }
            is PcEvent.BlockChanged -> e.reason?.let { r -> if (s.phase != Phase.BLOCKED) set { it.copy(phase = Phase.BLOCKED, blockReason = r, selected = null, preview = null, explain = null) } }
            PcEvent.RetryBlocked -> if (s.phase == Phase.BLOCKED) set { it.copy(phase = Phase.STARTING, blockReason = null) }
            PcEvent.QuitBlocked -> quit()
            PcEvent.WelcomeGo -> if (s.phase == Phase.WELCOME) {
                store.welcomed = true
                set { it.copy(phase = Phase.PICKER) }
            }

            // ------------------------------------------------------------ lesson start, floor, placement
            is PcEvent.LessonPicked -> if (s.phase == Phase.PICKER) startLesson(e.lesson)
            is PcEvent.FloorFound -> {
                val notice = if (e.virtual) "virtual" else if (s.virtualFloor) "real" else null
                set { it.copy(floorReady = true, virtualFloor = e.virtual, floorNotice = notice) }
                if (s.phase == Phase.FINDING) set { it.copy(phase = Phase.PLACING, placeSerial = it.placeSerial + 1) }
                // the hint row shows "virtual floor for now" / "on the real floor" for a few seconds (design S03, placement.floor)
                if (notice != null) {
                    val serial = ++noticeSerial
                    viewModelScope.launch {
                        delay(NOTICE_MS)
                        if (serial == noticeSerial) set { it.copy(floorNotice = null) }
                    }
                }
            }
            PcEvent.Placed -> if (s.phase == Phase.PLACING) {
                val back = placeReturn
                placeReturn = null
                set { it.copy(phase = back ?: Phase.RULES) }
            }
            PcEvent.StartPruning -> if (s.phase == Phase.RULES) set { it.copy(phase = Phase.PRUNING) }

            // ------------------------------------------------------------ cutting
            // tapping the selected branch again steps back like the back key: the preview first, then the selection (there is
            // no room shell to catch a tap on empty space: factory D24 hard gate)
            is PcEvent.BranchTapped ->
                if (s.phase == Phase.PRUNING && tree[e.branch]?.selectable == true) {
                    if (s.selected == e.branch) handle(PcEvent.TappedElsewhere) else set { it.copy(selected = e.branch, preview = null) }
                }
            is PcEvent.BudTapped -> {
                if (s.phase != Phase.PRUNING) return
                if (s.selected == e.branch && s.preview == e.bud) cut(Cut(e.branch, e.bud)) else set { it.copy(selected = e.branch, preview = e.bud) }
            }
            PcEvent.PreviewTapped -> {
                val b = s.selected
                val k = s.preview
                if (s.phase == Phase.PRUNING && b != null && k != null) cut(Cut(b, k))
            }
            PcEvent.TappedElsewhere ->
                when {
                    s.phase == Phase.PRUNING && s.preview != null -> set { it.copy(preview = null) }
                    s.phase == Phase.PRUNING && s.selected != null -> set { it.copy(selected = null) }
                }
            PcEvent.Undo -> if (s.phase == Phase.PRUNING && s.cuts.isNotEmpty()) setCuts(s.cuts.dropLast(1), clearExplain = true)
            PcEvent.Rejoin -> if (s.phase == Phase.PRUNING && s.cuts.isNotEmpty()) setCuts(s.cuts.dropLast(1), clearExplain = true)
            PcEvent.GotIt, PcEvent.ExplainTimeout -> if (s.explain != null) set { it.copy(explain = null) }
            PcEvent.HintToggle -> if (s.phase == Phase.PRUNING && !s.exam) {
                val on = !s.hints
                // design rules.hint: the tree turns to the nearest target the first time hints go on in a lesson
                val turn = on && !hintTurned
                if (turn) hintTurned = true
                set { it.copy(hints = on, hintSerial = if (turn) it.hintSerial + 1 else it.hintSerial) }
                if (on) nearestMust()?.let { b -> set { it.copy(explain = Explain(null, b, wrong = false, canRejoin = false, serial = ++explainSerial, hintRule = ruleOf(b))) } }
            }
            is PcEvent.Turn -> if (s.phase.inLesson) set { it.copy(rotateSerial = it.rotateSerial + 1, rotateBy = e.deg) }
            PcEvent.Done -> if (s.phase == Phase.PRUNING) done()

            // ------------------------------------------------------------ result
            is PcEvent.ViewSelected -> if (s.phase == Phase.RESULT && s.score != null) {
                set { it.copy(view = e.view) }
                if (e.view != View.NOW) logNext()
            }
            is PcEvent.ReviewRow -> if (s.phase == Phase.RESULT) {
                val reason = s.score?.wrongs?.firstOrNull { it.branch == e.branch }?.reason ?: s.score?.targets?.firstOrNull { e.branch in it.ids }?.reason
                set { it.copy(focusBranch = e.branch, focusSerial = it.focusSerial + 1, explain = Explain(reason, e.branch, wrong = s.score?.wrongs?.any { w -> w.branch == e.branch } == true, canRejoin = false, serial = ++explainSerial, hintRule = if (reason == null) ruleOf(e.branch) else null)) }
            }
            PcEvent.Again -> if (s.phase == Phase.RESULT) {
                setCuts(emptyList(), clearExplain = true)
                set { it.copy(score = null, view = View.NOW, hints = false, phase = Phase.PLACING, placeSerial = it.placeSerial + 1) }
            }
            PcEvent.NextLesson -> if (s.phase == Phase.RESULT) set { it.copy(phase = Phase.PICKER, score = null, view = View.NOW, explain = null) }
            PcEvent.Close ->
                when (s.phase) {
                    Phase.GRADUATED -> set { it.copy(phase = Phase.RESULT) }
                    // design S14 "关闭 → 前态": back to the settings page when it was opened from there
                    Phase.RULE_CARD -> set { it.copy(phase = if (cardFromSettings) Phase.SETTINGS else it.back) }
                    else -> Unit
                }
            // the rule card is a tab of the main window: it opens from the picker, the settings page and during a lesson
            PcEvent.OpenRuleCard -> if (s.phase in setOf(Phase.WELCOME, Phase.PICKER, Phase.RULES, Phase.SETTINGS, Phase.PRUNING, Phase.RESULT)) {
                cardFromSettings = s.phase == Phase.SETTINGS
                // from the settings page `back` stays the settings page's own way back
                set { it.copy(phase = Phase.RULE_CARD, back = if (cardFromSettings) s.back else s.phase, cardSpecies = s.lesson.species, confirm = null) }
            }
            is PcEvent.CardSpecies -> set { it.copy(cardSpecies = e.species) }
            is PcEvent.TabSelected ->
                when (e.page) {
                    PcPages.LESSON -> {
                        if (s.phase == Phase.RULE_CARD) handle(PcEvent.Close)
                        if (_state.value.phase == Phase.SETTINGS) handle(PcEvent.SettingsToggle)
                    }
                    PcPages.CARD -> if (s.phase != Phase.RULE_CARD) handle(PcEvent.OpenRuleCard)
                    PcPages.SETTINGS -> {
                        if (s.phase == Phase.RULE_CARD) handle(PcEvent.Close)
                        if (_state.value.phase != Phase.SETTINGS) handle(PcEvent.SettingsToggle)
                    }
                }

            // ------------------------------------------------------------ settings
            PcEvent.SettingsToggle ->
                when (s.phase) {
                    Phase.SETTINGS -> set { it.copy(phase = it.back, confirm = null) }
                    Phase.STARTING, Phase.BLOCKED, Phase.FINDING, Phase.PLACING, Phase.RELOCATING -> Unit
                    else -> set { it.copy(phase = Phase.SETTINGS, back = s.phase, selected = null, preview = null, explain = null) }
                }
            is PcEvent.Sound -> setSettings(s.settings.copy(sound = e.on))
            is PcEvent.VolumeSet -> setSettings(s.settings.copy(volume = e.v))
            is PcEvent.BudZoom -> {
                setSettings(s.settings.copy(budZoom = e.on))
                set { it.copy(treeSerial = it.treeSerial + 1) }
            }
            is PcEvent.FloorStep -> setSettings(s.settings.copy(floorOffset = (s.settings.floorOffset + e.steps * 0.05f).coerceIn(-0.30f, 0.30f)))
            is PcEvent.Seated -> setSettings(s.settings.copy(seated = e.on))
            is PcEvent.Language -> setSettings(s.settings.copy(language = e.v))
            PcEvent.Recall -> if (s.phase == Phase.SETTINGS && s.back.inLesson) {
                placeReturn = s.back
                set { it.copy(phase = Phase.PLACING, placeSerial = it.placeSerial + 1, recallSerial = it.recallSerial + 1) }
            }
            PcEvent.Replant -> if (s.phase == Phase.SETTINGS && s.back.inLesson) set { it.copy(phase = Phase.RELOCATING) }
            PcEvent.Replanted -> if (s.phase == Phase.RELOCATING) set { it.copy(phase = it.back) }
            PcEvent.CancelReplant -> if (s.phase == Phase.RELOCATING) set { it.copy(phase = it.back) }
            PcEvent.ResetLesson -> if (s.phase == Phase.SETTINGS && s.back.inLesson) {
                setCuts(emptyList(), clearExplain = true)
                set { it.copy(score = null, view = View.NOW, hints = false, phase = Phase.PLACING, placeSerial = it.placeSerial + 1) }
            }
            PcEvent.AskResetAll -> if (s.phase == Phase.SETTINGS) set { it.copy(confirm = Confirm.RESET) }
            PcEvent.AskQuit -> if (s.phase == Phase.SETTINGS) set { it.copy(confirm = Confirm.QUIT) }
            PcEvent.ConfirmNo -> set { it.copy(confirm = null) }
            PcEvent.ConfirmYes ->
                when (s.confirm) {
                    Confirm.QUIT -> quit()
                    Confirm.RESET -> {
                        store.resetProgress()
                        set { it.copy(confirm = null, phase = Phase.PICKER, records = records(), graduated = emptySet(), cuts = emptyList(), score = null, explain = null) }
                    }
                    null -> Unit
                }

            // ------------------------------------------------------------ lifecycle
            PcEvent.WentBackground -> {
                // temporary states are cancelled (design `stateModel.background`)
                set { it.copy(preview = null, moving = false, turning = false, confirm = null, phase = if (it.phase == Phase.RELOCATING) it.back else it.phase) }
                store.saveCuts(s.lesson, s.cuts)
            }
            PcEvent.CameForeground -> if (s.phase.inLesson) {
                placeReturn = s.phase
                set { it.copy(phase = Phase.PLACING, placeSerial = it.placeSerial + 1) }
            }

            // ------------------------------------------------------------ debug switches
            is PcEvent.DebugState -> DebugJump.jump(this, e.id)
            is PcEvent.DebugAutocut -> autocut(e.kind)
            is PcEvent.DebugExplain -> {
                val b = tree.branches.firstOrNull { it.label == Label.MUST } ?: return
                val wrong = e.reason in setOf(Reason.KEEP_BLOOM, Reason.KEEP_SPUR, Reason.MAJOR, Reason.SECOND_OF_PAIR)
                set { it.copy(explain = Explain(e.reason, b.id, wrong, canRejoin = wrong, serial = ++explainSerial), selected = null, preview = null) }
            }
            is PcEvent.DebugNoFloor -> set { it.copy(debugNoFloor = e.on, floorReady = if (e.on) false else it.floorReady) }
            PcEvent.DebugFloorArrive -> set { it.copy(debugNoFloor = false) }
            is PcEvent.DebugBlocked -> if (e.on) handle(PcEvent.BlockChanged(BlockReason.NOT_IN_FULL_SPACE)) else handle(PcEvent.RetryBlocked)
            is PcEvent.DebugInside -> set { it.copy(debugInside = e.on, inside = e.on) }
            is PcEvent.DebugTestTexture -> set { it.copy(testTexture = e.on) }
            PcEvent.DebugRelease -> set { it.copy(debugHold = false, debugInside = false, inside = false) }
            PcEvent.DebugClear -> {
                store.clearAll()
                set { PcUiState(phase = Phase.STARTING) }
            }
            else -> Unit
        }
    }

    // =========================================================================================
    // transitions
    // =========================================================================================

    fun startLesson(l: LessonId) {
        hintTurned = false
        placeReturn = null
        set { it.copy(lesson = l, cuts = emptyList(), score = null, view = View.NOW, selected = null, preview = null, explain = null, hints = false, removedPct = 0f) }
        store.saveCuts(l, emptyList())
        if (_state.value.floorReady && !_state.value.debugNoFloor) set { it.copy(phase = Phase.PLACING, placeSerial = it.placeSerial + 1) }
        else set { it.copy(phase = Phase.FINDING, floorReady = false) }
    }

    private fun cut(c: Cut) {
        val s = _state.value
        val cuts = s.cuts + c
        PcGraph.sfx.play(Sfx.Kind.SNIP)
        setCuts(cuts, clearExplain = true)
        set { it.copy(selected = null, preview = null) }
        val t = tree
        val b = t[c.branch]
        PcLog.i("CUT branch=${c.branch} bud=${c.bud} label=${b?.label} defect=${b?.defect} acceptable=${b?.acceptable}")
        if (s.exam) return
        val (credit, reason) = Judge.feedback(t, cuts, c)
        PcLog.i("FEEDBACK branch=${c.branch} credit=$credit reason=$reason")
        when {
            credit == 1f && reason == null -> PcGraph.sfx.play(Sfx.Kind.RIGHT)
            credit != null && (credit < 1f || reason != null) -> {
                val wrong = credit == 0f
                if (wrong) PcGraph.sfx.play(Sfx.Kind.WRONG)
                set { it.copy(explain = Explain(reason, c.branch, wrong, canRejoin = true, serial = ++explainSerial)) }
            }
        }
    }

    private fun setCuts(cuts: List<Cut>, clearExplain: Boolean) {
        val removed = Judge.removedPct(tree, Judge.status(tree, cuts))
        set { it.copy(cuts = cuts, removedPct = removed, explain = if (clearExplain) null else it.explain) }
        store.saveCuts(_state.value.lesson, cuts)
    }

    private fun done() {
        val s = _state.value
        val score = Judge.score(tree, s.cuts)
        store.saveResult(s.lesson, score.percent, score.pass)
        PcLog.i(
            String.format(
                Locale.US,
                "PC-SCORE lesson=%s credit=%.1f must=%d wrong=%d major=%d strong=%d removed=%.1f%% pass=%s",
                s.lesson, score.credit, score.must, score.wrong, score.major, score.strongKept, score.removedPct, score.pass,
            )
        )
        val graduateNow = s.exam && score.pass && !store.graduated(s.lesson.species)
        if (graduateNow) store.graduate(s.lesson.species)
        set {
            it.copy(
                phase = if (graduateNow) Phase.GRADUATED else Phase.RESULT,
                score = score, view = View.NOW, selected = null, preview = null, explain = null, hints = false,
                records = records(), graduated = Species.entries.filter { sp -> store.graduated(sp) }.toSet(),
            )
        }
    }

    private fun quit() {
        PcLog.i("QUIT requested")
        _effects.tryEmit(PcEffect.QuitApp)
    }

    private fun setSettings(v: Settings) {
        store.save(v)
        PcGraph.sfx.muted = !v.sound
        PcGraph.sfx.gain = v.volume.gain
        set { it.copy(settings = v) }
    }

    /** The uncut MUST branch nearest the front for the hint (the scene turns the tree to it). */
    private fun nearestMust(): Int? {
        val st = Judge.status(tree, _state.value.cuts)
        return tree.branches.filter { it.label == Label.MUST && st[it.id] == io.embodit.pruningclass.domain.tree.Status.Intact }.maxByOrNull { it.points.last().z }?.id
    }

    private fun ruleOf(branch: Int): String? = tree[branch]?.let { RuleOf.of(tree.species, it.defect) }

    /** Debug autocut sets (design `qaHooks`): the same cut path as the user's taps, without per-cut feedback. */
    fun autocut(kind: String) {
        val t = tree
        val cuts =
            when (kind) {
                "perfect" -> Judge.model(t)
                "sloppy" -> Judge.sloppy(t)
                "half" -> Judge.half(t)
                "major" -> Judge.major(t)
                "all" -> Judge.all(t)
                "over25" -> Judge.over25(t)
                else -> emptyList()
            }
        PcLog.i("AUTOCUT $kind cuts=${cuts.size}")
        setCuts(cuts, clearExplain = true)
        set { it.copy(selected = null, preview = null) }
    }

    private fun logNext() {
        val s = _state.value
        val yours = NextYear(tree, s.cuts).counts
        val model = NextYear(tree, Judge.model(tree)).counts
        PcLog.i("PC-NEXT lesson=${s.lesson} yours $yours model $model")
    }

    internal fun set(f: (PcUiState) -> PcUiState) = _state.update(f)

    internal val current: PcUiState
        get() = _state.value

    internal fun dispatch(e: PcEvent) = handle(e)

    private fun logState() {
        val s = _state.value
        val line = "PC-STATE ${s.qaName} cuts=${s.cuts.size} sel=${s.selected} prev=${s.preview} removed=${"%.1f".format(Locale.US, s.removedPct)}${if (s.debugHold) " hold" else ""}"
        if (line != lastLogged) {
            lastLogged = line
            PcLog.i(line)
        }
    }

    fun dumpLines(): List<String> =
        tree.branches.filter { it.selectable }.map { b ->
            "PC-TREE ${b.id} ${b.label} ${b.defect} oneOf=${b.oneOf} acceptable=${b.acceptable} allowBase=${b.allowBase} needOut=${b.needOutward} healthyBelow=${b.healthyBelow} model=${b.modelCut} buds=${b.buds.size} dia=${"%.1f".format(Locale.US, b.diameterMm)}"
        }

    private companion object {
        /** How long the floor notice stays in the hint row. */
        const val NOTICE_MS = 3_000L
    }

}

/** The rule id an explanation / hint quotes: by the reason when there is one (a wrong cut on healthy wood), else by the defect. */
object RuleOf {
    fun of(species: Species, d: io.embodit.pruningclass.domain.tree.Defect, reason: Reason? = null): String {
        val rose = species == Species.ROSE
        when (reason) {
            // keep strong canes / spurs, never take a scaffold, at most 20 %
            Reason.KEEP_BLOOM, Reason.KEEP_SPUR, Reason.MAJOR, Reason.OVER_PRUNE -> return if (rose) "R-4" else "A-5"
            Reason.INWARD_BUD -> return if (rose) "R-3" else "A-4"
            Reason.SECOND_OF_PAIR -> return if (rose) "R-2" else "A-2"
            else -> Unit
        }
        return if (rose) {
            when (d) {
                io.embodit.pruningclass.domain.tree.Defect.THIN -> "R-5"
                io.embodit.pruningclass.domain.tree.Defect.CROSSING, io.embodit.pruningclass.domain.tree.Defect.INWARD -> "R-2"
                io.embodit.pruningclass.domain.tree.Defect.REDUCE -> "R-4"
                else -> "R-1"
            }
        } else {
            when (d) {
                io.embodit.pruningclass.domain.tree.Defect.WATERSHOOT -> "A-3"
                io.embodit.pruningclass.domain.tree.Defect.CROSSING, io.embodit.pruningclass.domain.tree.Defect.INWARD -> "A-2"
                io.embodit.pruningclass.domain.tree.Defect.LEADER -> "A-4"
                io.embodit.pruningclass.domain.tree.Defect.SPUR -> "A-5"
                else -> "A-1"
            }
        }
    }
}

/**
 * Debug state jumps (design `qaHooks` `state=<Sxx>[:R1..A3]`): each jump replays the user's own events (pick the lesson,
 * floor, placed, start, select, preview, cut, done ...) so the same transitions run (factory rule 2026-10-29). The tree's
 * placement still comes from the scene. Held states stay until `release`.
 */
internal object DebugJump {
    fun jump(vm: PcViewModel, raw: String) {
        val id = raw.substringBefore(':').uppercase(Locale.US)
        val arg = raw.substringAfter(':', "")
        val lesson = LessonId.of(arg) ?: vm.current.lesson
        // leaving S16 the user's way: [Retry]
        if (vm.current.phase == Phase.BLOCKED && id != "S16") vm.dispatch(PcEvent.RetryBlocked)
        // leaving S20: [Cancel]
        if (vm.current.phase == Phase.RELOCATING && id != "S20") vm.dispatch(PcEvent.CancelReplant)
        fun toPicker() {
            // step back the user's way until nothing changes (rule card -> settings -> lesson ...)
            repeat(3) {
                when (vm.current.phase) {
                    Phase.STARTING -> vm.dispatch(PcEvent.PanelsReady)
                    Phase.WELCOME -> vm.dispatch(PcEvent.WelcomeGo)
                    Phase.SETTINGS -> vm.dispatch(PcEvent.SettingsToggle)
                    Phase.RULE_CARD, Phase.GRADUATED -> vm.dispatch(PcEvent.Close)
                    else -> Unit
                }
            }
            if (vm.current.phase == Phase.WELCOME) vm.dispatch(PcEvent.WelcomeGo)
            if (vm.current.phase.inLesson || vm.current.phase == Phase.FINDING || vm.current.phase == Phase.PLACING) vm.set { it.copy(phase = Phase.PICKER, score = null, explain = null) }
        }
        fun toPruning() {
            if (vm.current.phase != Phase.PRUNING || vm.current.lesson != lesson) {
                toPicker()
                vm.dispatch(PcEvent.LessonPicked(lesson))
                if (vm.current.phase == Phase.FINDING) vm.dispatch(PcEvent.FloorFound(1.65f - 1.60f, virtual = true))
                vm.dispatch(PcEvent.Placed)
                vm.dispatch(PcEvent.StartPruning)
            }
            vm.set { it.copy(selected = null, preview = null, explain = null) }
        }
        fun firstMust() = vm.tree.branches.first { it.label == Label.MUST && it.selectable && (it.acceptable != null || it.modelCut != null) }
        when (id) {
            "S00" -> vm.set { it.copy(phase = Phase.STARTING) }
            "S01" -> vm.set { it.copy(phase = Phase.WELCOME) }
            "S02" -> toPicker()
            "S03" -> {
                toPicker()
                vm.dispatch(PcEvent.DebugNoFloor(true))
                vm.dispatch(PcEvent.LessonPicked(lesson))
            }
            "S04" -> {
                toPicker()
                vm.dispatch(PcEvent.LessonPicked(lesson))
            }
            "S05" -> {
                toPicker()
                vm.dispatch(PcEvent.LessonPicked(lesson))
                if (vm.current.phase == Phase.FINDING) vm.dispatch(PcEvent.FloorFound(0.05f, virtual = true))
                vm.dispatch(PcEvent.Placed)
            }
            "S06" -> toPruning()
            "S07" -> {
                toPruning()
                vm.dispatch(PcEvent.BranchTapped(firstMust().id))
            }
            "S08" -> {
                toPruning()
                val b = firstMust()
                vm.dispatch(PcEvent.BranchTapped(b.id))
                vm.dispatch(PcEvent.BudTapped(b.id, b.modelCut ?: 0))
            }
            "S09" -> {
                toPruning()
                vm.dispatch(PcEvent.DebugExplain(if (vm.tree.species == Species.ROSE) Reason.KEEP_BLOOM else Reason.KEEP_SPUR))
            }
            "S10" -> {
                toPruning()
                if (!vm.current.hints) vm.dispatch(PcEvent.HintToggle)
            }
            "S11" -> {
                toPruning()
                vm.autocut("sloppy")
                vm.dispatch(PcEvent.Done)
                if (vm.current.phase == Phase.GRADUATED) vm.dispatch(PcEvent.Close)
            }
            "S12" -> {
                jump(vm, "S11:$lesson")
                vm.dispatch(PcEvent.ViewSelected(View.YOURS))
            }
            "S13" -> {
                toPruning()
                vm.autocut("perfect")
                vm.dispatch(PcEvent.Done)
                if (vm.current.phase != Phase.GRADUATED) vm.set { it.copy(phase = Phase.GRADUATED) }
            }
            "S14" -> {
                toPicker()
                vm.dispatch(PcEvent.OpenRuleCard)
            }
            "S15" -> {
                if (vm.current.phase == Phase.SETTINGS) {
                    if (vm.current.confirm != null) vm.dispatch(PcEvent.ConfirmNo)
                    return
                }
                if (!vm.current.phase.inLesson) toPruning()
                vm.dispatch(PcEvent.SettingsToggle)
            }
            "S15B" -> {
                jump(vm, "S15")
                vm.dispatch(if (arg.equals("reset", true)) PcEvent.AskResetAll else PcEvent.AskQuit)
            }
            "S16" -> vm.dispatch(PcEvent.DebugBlocked(true))
            "S20" -> {
                jump(vm, "S15")
                vm.dispatch(PcEvent.Replant)
            }
            "S21" -> {
                toPruning()
                vm.dispatch(PcEvent.DebugInside(true))
            }
            else -> PcLog.i("CMD state $raw unknown")
        }
        vm.set { it.copy(debugHold = true) }
    }
}
