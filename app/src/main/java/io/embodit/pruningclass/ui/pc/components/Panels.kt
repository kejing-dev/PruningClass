package io.embodit.pruningclass.ui.pc.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.pruningclass.R
import io.embodit.pruningclass.domain.model.LanguagePref
import io.embodit.pruningclass.domain.model.Volume
import io.embodit.pruningclass.domain.tree.LessonId
import io.embodit.pruningclass.domain.tree.NextYear
import io.embodit.pruningclass.domain.tree.Reason
import io.embodit.pruningclass.domain.tree.Rules
import io.embodit.pruningclass.domain.tree.Species
import io.embodit.pruningclass.domain.tree.Judge
import io.embodit.pruningclass.ui.pc.Confirm
import io.embodit.pruningclass.ui.pc.PcEvent
import io.embodit.pruningclass.ui.pc.PcUiState
import io.embodit.pruningclass.ui.pc.Phase
import io.embodit.pruningclass.ui.pc.TreeCache
import io.embodit.pruningclass.ui.pc.View
import io.embodit.pruningclass.ui.pc.debug.probe
import io.embodit.pruningclass.ui.pc.scene.PanelSpec
import io.embodit.pruningclass.ui.theme.PcColors
import kotlin.math.roundToInt

private val Gap = 8.dp

@Composable
private fun Label(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, maxLines: Int = 1) =
    Text(text = text, color = if (secondary) PicoTheme.colorScheme.labelSecondary else PicoTheme.colorScheme.labelPrimary, style = PcType.label, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier, onTextLayout = overflowLog(text))

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) =
    Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = PcType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier, onTextLayout = overflowLog(text))

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = PicoTheme.colorScheme.labelSecondary) =
    Text(text = text, color = color, style = PcType.hint, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier, onTextLayout = overflowLog(text))

@Composable
internal fun lessonName(l: LessonId): String = rsn("lesson_$l")

/** Sound switch (D15) of the first row. Settings is the window's last TabBar page (pages switch only by the TabBar). */
@Composable
private fun SoundAndSettings(state: PcUiState, onEvent: (PcEvent) -> Unit) {
    Icon(painter = painterResource(R.drawable.ic_volume_up), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
    Switch(checked = state.settings.sound, onCheckedChange = { onEvent(PcEvent.Sound(it)) }, modifier = Modifier.probe("sound") { onEvent(PcEvent.Sound(!state.settings.sound)) })
}

// =============================================================================================
// control panel (design `panels.controlPanel`): the lesson tab of the main window while pruning
// =============================================================================================

@Composable
internal fun ControlPanel(state: PcUiState, filled: Boolean, onEvent: (PcEvent) -> Unit) {
    WindowPage(spacing = Gap) {
        if (!filled) return@WindowPage
        val busy = state.phase == Phase.STARTING || state.phase == Phase.FINDING || state.phase == Phase.PLACING
        val pruning = state.phase == Phase.PRUNING
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            Title(if (state.phase == Phase.STARTING) rs(R.string.title_app) else lessonName(state.lesson), Modifier.weight(1f))
            SoundAndSettings(state, onEvent)
        }
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(28.dp))
            } else {
                val t = TreeCache.get(state.lesson)
                val done = Judge.score(t, state.cuts).targets.count { it.credit > 0f }
                ValueChip(if (state.exam) rs(R.string.chip_cut_exam, state.cuts.size) else rs(R.string.chip_cut, done, t.mustCount - done))
                if (state.lesson.species == Species.APPLE) {
                    val pct = state.removedPct.roundToInt()
                    Chipish(rs(R.string.chip_removed, pct), alert = state.removedPct > 20f)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            WordButton("undo", rs(R.string.action_undo), { onEvent(PcEvent.Undo) }, enabled = pruning && state.cuts.isNotEmpty(), minWidth = 112.dp, iconRes = R.drawable.ic_undo)
            if (state.phase == Phase.RELOCATING) {
                // design S20: the main button becomes [Cancel]
                MainButton("replant_cancel", rs(R.string.action_cancel), R.drawable.ic_close, true, Modifier.weight(1f)) { onEvent(PcEvent.CancelReplant) }
            } else {
                MainButton("done", rs(R.string.action_done), R.drawable.ic_check, pruning, Modifier.weight(1f)) { onEvent(PcEvent.Done) }
            }
            WordButton("hint", if (state.hints) rs(R.string.action_hint_on) else rs(R.string.action_hint_off), { onEvent(PcEvent.HintToggle) }, enabled = pruning && !state.exam, minWidth = 132.dp, iconRes = R.drawable.ic_lightbulb)
        }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            val turn = state.phase.inLesson
            WordButton("turn_left", rs(R.string.action_turn_left), { onEvent(PcEvent.Turn(-45f)) }, enabled = turn, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_rotate_left)
            // while a branch is selected the other branches cannot be tapped (only its bud ladder): this is the way out
            WordButton("pick_another", rs(R.string.action_pick_another), { onEvent(PcEvent.PickAnother) }, enabled = pruning && state.selected != null, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_park)
            WordButton("turn_right", rs(R.string.action_turn_right), { onEvent(PcEvent.Turn(45f)) }, enabled = turn, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_rotate_right)
        }
        ViewSegments(state, onEvent, enabled = false)
        HintRow(state)
        // the window is sized for the tallest page: the rest of the lesson tab is this lesson's rules
        if (state.phase != Phase.STARTING) {
            FillCard(Modifier.weight(1f)) {
                Label(rs(R.string.rules_this_lesson), secondary = true)
                LessonRules(state)
            }
        }
    }
}

/** The rules of this lesson (all five in an exam), with their numbers (rules page, control page). */
@Composable
private fun LessonRules(state: PcUiState) {
    for (r in state.lesson.rules.take(if (state.exam) 5 else 3)) RuleRow(r)
}

@Composable
private fun RuleRow(r: String, source: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = r, color = PicoTheme.colorScheme.labelPrimary, style = PcType.title)
        Column(Modifier.weight(1f)) {
            val text = rsn("rule_" + r.replace("-", ""))
            Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = PcType.title, maxLines = 4, overflow = TextOverflow.Ellipsis, onTextLayout = overflowLog(text))
            if (source) Text(text = rs(R.string.rule_source), color = PicoTheme.colorScheme.labelSecondary, style = PcType.chip)
        }
    }
}

/**
 * The block that fills the rest of a page (factory D44 "空白 ≤ 窗高 25%, 内部空带 ≤ 20%"): the window has one size for every
 * page (a system window cannot be resized at run time, pico-cli-feedback), so the page's main content stretches into a
 * tinted card and spreads evenly inside it instead of leaving the bottom of the window empty.
 */
@Composable
private fun FillCard(modifier: Modifier, arrangement: Arrangement.Vertical = Arrangement.SpaceEvenly, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = arrangement,
        content = content,
    )
}

@Composable
private fun Chipish(text: String, alert: Boolean) {
    Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (alert) PicoTheme.colorScheme.alert else PicoTheme.colorScheme.fillTertiary).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = if (alert) PcColors.Ink else PicoTheme.colorScheme.labelPrimary, style = PcType.chip, maxLines = 1, onTextLayout = overflowLog(text))
    }
}

@Composable
private fun ViewSegments(state: PcUiState, onEvent: (PcEvent) -> Unit, enabled: Boolean) {
    Segments(
        items = listOf("view_now" to rs(R.string.view_now), "view_yours" to rs(R.string.view_yours), "view_model" to rs(R.string.view_model)),
        selected = state.view.ordinal,
        onSelect = { onEvent(PcEvent.ViewSelected(View.entries[it])) },
        width = 488.dp,
        height = 48.dp,
        enabled = enabled,
    )
}

@Composable
private fun HintRow(state: PcUiState) {
    val text =
        when {
            state.phase == Phase.STARTING -> rs(R.string.hint_s00)
            state.phase == Phase.FINDING -> rs(R.string.hint_s03)
            state.floorNotice == "virtual" -> rs(R.string.hint_s03_timeout)
            state.floorNotice == "real" -> rs(R.string.hint_real_floor)
            state.phase == Phase.RELOCATING -> rs(R.string.hint_s20)
            state.inside -> rs(R.string.hint_s21)
            state.moving -> rs(R.string.hint_s19)
            state.preview != null -> rs(R.string.hint_s08)
            state.selected != null -> rs(R.string.hint_s07) + "\n" + rs(R.string.hint_s07_cursor)
            state.hints -> rs(R.string.hint_s10)
            state.exam && state.phase == Phase.PRUNING -> rs(R.string.exam_note) + " · " + rs(R.string.hint_s06)
            else -> rs(R.string.hint_s06)
        }
    Hint(text, Modifier.fillMaxWidth().height(40.dp), color = if (state.turning) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary)
}

// =============================================================================================
// welcome, picker, rules, card
// =============================================================================================

@Composable
internal fun WelcomePage(filled: Boolean, onEvent: (PcEvent) -> Unit) {
    WindowPage(spacing = Gap) {
        if (!filled) return@WindowPage
        Title(rs(R.string.title_app))
        Label(rs(R.string.welcome_line), maxLines = 2)
        FillCard(Modifier.weight(1f)) {
            for ((icon, text) in listOf(R.drawable.ic_rotate_right to R.string.welcome_1, R.drawable.ic_content_cut to R.string.welcome_2, R.drawable.ic_eco to R.string.welcome_3)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(56.dp), tint = PicoTheme.colorScheme.labelPrimary)
                    val t = rs(text)
                    Text(text = t, color = PicoTheme.colorScheme.labelPrimary, style = PcType.title, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), onTextLayout = overflowLog(t))
                }
            }
        }
        MainButton("welcome_go", rs(R.string.action_pick), R.drawable.ic_arrow_forward, true, Modifier.fillMaxWidth()) { onEvent(PcEvent.WelcomeGo) }
    }
}

@Composable
internal fun PickerPage(state: PcUiState, filled: Boolean, onEvent: (PcEvent) -> Unit) {
    WindowPage(spacing = Gap) {
        if (!filled) return@WindowPage
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            Title(rs(R.string.picker_title), Modifier.weight(1f))
            SoundAndSettings(state, onEvent)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            for (sp in Species.entries) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Gap)) {
                    Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = rsn("species_${sp.name.lowercase()}"), color = PicoTheme.colorScheme.labelPrimary, style = PcType.chip)
                        if (sp in state.graduated) Icon(painter = painterResource(R.drawable.ic_workspace_premium), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
                    }
                    for (l in LessonId.of(sp)) Box(Modifier.fillMaxWidth().weight(1f)) { LessonCard(state, l, onEvent) }
                }
            }
        }
        // the rule card is the window's second TabBar page (pages switch only by the TabBar)
    }
}

@Composable
private fun LessonCard(state: PcUiState, l: LessonId, onEvent: (PcEvent) -> Unit) {
    val rec = state.records[l]
    val onClick = { onEvent(PcEvent.LessonPicked(l)) }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxSize().probe("card_$l", onClick),
        size = ButtonDefaults.Regular.copy(height = 112.dp, minWidth = 200.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
        contentPadding = PaddingValues(12.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "${l.number}", color = PicoTheme.colorScheme.labelPrimary, style = PcType.title)
                if (l.exam) Chipish(rs(R.string.chip_exam), alert = false)
            }
            val name = rsn("card_$l")
            Text(text = name, color = PicoTheme.colorScheme.labelPrimary, style = PcType.title, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = overflowLog(name))
            // the rules this lesson teaches, by number (the card is a third of the page tall)
            val rules = l.rules.take(if (l.exam) 5 else 3).joinToString("  ")
            Text(text = rules, color = PicoTheme.colorScheme.labelSecondary, style = PcType.chip, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = overflowLog(rules))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rec?.passed == true) Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp), tint = PicoTheme.colorScheme.labelPrimary)
                val last = if (rec == null || rec.last < 0) rs(R.string.chip_new) else rs(R.string.chip_last, rec.last)
                Text(text = last, color = PicoTheme.colorScheme.labelSecondary, style = PcType.chip, maxLines = 1, onTextLayout = overflowLog(last))
            }
        }
    }
}

@Composable
internal fun RulesPage(state: PcUiState, filled: Boolean, onEvent: (PcEvent) -> Unit) {
    WindowPage(spacing = Gap) {
        if (!filled) return@WindowPage
        Title(lessonName(state.lesson))
        // each rule with its source line: the card is most of the window tall (D44 inner band <= 20 %)
        FillCard(Modifier.weight(1f)) { for (r in state.lesson.rules.take(if (state.exam) 5 else 3)) RuleRow(r, source = true) }
        Label(if (state.exam) rs(R.string.exam_note) else rs(R.string.rules_count, state.lesson.mustCuts), secondary = true)
        MainButton("start", rs(R.string.action_start), R.drawable.ic_content_cut, state.phase == Phase.RULES, Modifier.fillMaxWidth()) { onEvent(PcEvent.StartPruning) }
    }
}

/** Graduation (S13) and the rule card (S14) share one panel. */
@Composable
internal fun CardPage(state: PcUiState, filled: Boolean, onEvent: (PcEvent) -> Unit) {
    WindowPage(spacing = Gap) {
        if (!filled) return@WindowPage
        val grad = state.phase == Phase.GRADUATED
        val sp = if (grad) state.lesson.species else state.cardSpecies
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            if (grad) Icon(painter = painterResource(R.drawable.ic_workspace_premium), contentDescription = null, modifier = Modifier.size(28.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Title(if (grad) rs(R.string.graduate_title, rsn("species_${sp.name.lowercase()}")) else rs(R.string.card_title), Modifier.weight(1f))
        }
        if (grad) {
            val recs = LessonId.of(sp).map { state.records[it]?.best?.takeIf { b -> b >= 0 }?.let { b -> "$b%" } ?: "—" }
            Label(rs(R.string.graduate_scores, recs[0], recs[1], recs[2]))
        } else {
            // gate:allow tabs-segment in-page option (rose / apple card on the rule-card page), not a page switch: pages switch only by the native TabBar
            Segments(listOf("card_rose" to rsn("species_rose"), "card_apple" to rsn("species_apple")), sp.ordinal, { onEvent(PcEvent.CardSpecies(Species.entries[it])) }, width = 260.dp, height = 48.dp)
        }
        FillCard(Modifier.weight(1f)) { for (r in Rules.of(sp)) RuleRow(r, source = true) }
        // graduation: [Close] goes on to the result; the rule card itself is a TabBar page (left by another tab)
        if (grad) WordButton("card_close", rs(R.string.action_close), { onEvent(PcEvent.Close) }, minWidth = 140.dp, iconRes = R.drawable.ic_close)
    }
}

// =============================================================================================
// result + next year (design `panels.resultPage` 520 x 660; built 740 for five 48 dp review rows)
// =============================================================================================

@Composable
internal fun ResultPage(state: PcUiState, filled: Boolean, onEvent: (PcEvent) -> Unit) {
    WindowPage(spacing = Gap) {
        if (!filled) return@WindowPage
        val score = state.score ?: return@WindowPage
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            Title(lessonName(state.lesson), Modifier.weight(1f))
            SoundAndSettings(state, onEvent)
        }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val c = if (score.credit % 1f == 0f) score.credit.toInt().toString() else "%.1f".format(score.credit)
            Text(text = rs(R.string.result_big, c, score.must), color = PicoTheme.colorScheme.labelPrimary, style = PcType.countdown, maxLines = 1)
            Text(text = "${score.percent}%", color = PicoTheme.colorScheme.labelSecondary, style = PcType.title)
        }
        Column(Modifier.height(96.dp)) {
            Label(rs(R.string.result_wrong, score.wrong, score.major), Modifier.height(32.dp))
            Label(rs(R.string.result_off, score.targets.count { it.credit == 0.5f }), Modifier.height(32.dp))
            Label(if (state.lesson.species == Species.APPLE) rs(R.string.result_removed, score.removedPct.roundToInt()) else rs(R.string.result_strong, score.strongKept), Modifier.height(32.dp))
        }
        val verdict =
            when (score.failReason) {
                null -> if (state.exam) rs(R.string.result_pass) else rs(R.string.result_practice_pass)
                "major" -> rs(R.string.result_fail_major)
                "ratio" -> rs(R.string.result_fail_ratio, score.percent)
                "wrong" -> rs(R.string.result_fail_wrong, score.wrong)
                "strong" -> rs(R.string.result_fail_strong, score.strongKept)
                else -> rs(R.string.result_fail_removed, score.removedPct.roundToInt())
            }
        Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painter = painterResource(if (score.pass) R.drawable.ic_check else R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Title(verdict)
        }
        ViewSegments(state, onEvent, enabled = true)
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = if (state.view == View.NOW) Arrangement.Center else Arrangement.SpaceEvenly) {
            if (state.view == View.NOW) ReviewRows(state, onEvent) else NextYearRows(state)
        }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
            WordButton("again", rs(R.string.action_again), { onEvent(PcEvent.Again) }, modifier = Modifier.weight(1f), iconRes = R.drawable.ic_replay)
            MainButton("next_lesson", rs(R.string.action_next_lesson), R.drawable.ic_arrow_forward, true, Modifier.weight(1f)) { onEvent(PcEvent.NextLesson) }
        }
    }
}

/** What was missed / off / wrong, wrong cuts first; a row turns the tree to that branch (design I14). Five rows. */
@Composable
private fun ReviewRows(state: PcUiState, onEvent: (PcEvent) -> Unit) {
    val score = state.score ?: return
    val t = TreeCache.get(state.lesson)
    val rows = ArrayList<Triple<Int, String, Boolean>>()
    for (w in score.wrongs) rows += Triple(w.branch, rs(R.string.row_wrong, rsn("defect_${t[w.branch]?.defect}")), true)
    for (r in score.targets.filter { it.credit < 1f }) {
        val id = r.hit ?: r.ids.first()
        rows += Triple(id, if (r.credit == 0f) rs(R.string.row_missed, rsn("defect_${t[id]?.defect}")) else rs(R.string.row_half, rsn("defect_${t[id]?.defect}")), false)
    }
    if (rows.isEmpty()) {
        // nothing to review: the tick, and the rules this lesson practised (the block is a third of the window tall)
        FillCard(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(48.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Title(rs(R.string.row_none))
            }
            LessonRules(state)
        }
        return
    }
    for ((id, text, wrong) in rows.take(5)) {
        val onClick = { onEvent(PcEvent.ReviewRow(id)) }
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(bottom = 4.dp).probe("row_$id", onClick),
            size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 200.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
        ) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(if (wrong) PicoTheme.colorScheme.error else PicoTheme.colorScheme.alert))
            Spacer(Modifier.width(8.dp))
            Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = PcType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), onTextLayout = overflowLog(text))
        }
    }
    Hint(rs(R.string.hint_result), Modifier.height(36.dp))
}

/** Next year: your counts against the model's (design `rules.nextYear.counts`). */
@Composable
private fun NextYearRows(state: PcUiState) {
    val t = TreeCache.get(state.lesson)
    val yours = NextYear(t, state.cuts).counts
    val model = NextYear(t, Judge.model(t)).counts
    Label(rs(R.string.count_header), Modifier.height(36.dp), secondary = true)
    for (k in NextYear.keys(t.species)) {
        val name = when (k) {
            "bloom" -> if (t.species == Species.ROSE) rs(R.string.count_bloom) else rs(R.string.count_spurs)
            "dead" -> rs(R.string.count_dead)
            "centre" -> rs(R.string.count_centre)
            "cross" -> rs(R.string.count_cross)
            else -> rs(R.string.count_water)
        }
        fun fmt(v: Float?) = v?.let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) } ?: "0"
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(name, Modifier.weight(1f))
            Text(text = "${fmt(yours[k])} · ${fmt(model[k])}", color = PicoTheme.colorScheme.labelPrimary, style = PcType.title)
        }
    }
}

// =============================================================================================
// settings (design `panels.settings`): the last TabBar page, 12 rows + inline confirm, [Quit app] last (D26)
// =============================================================================================

/**
 * Volume (factory loudness rule 2026-11-05): a slider with three detents (low / mid / high, it snaps to the nearest) and
 * the percent; [声音] above is the mute. 48 dp tall (SpatialUI's default 32 dp misses gaze pinches), >= 100 dp wide.
 */
@Composable
private fun VolumeSlider(v: Volume, sound: Boolean, onEvent: (PcEvent) -> Unit) {
    Row(Modifier.width(220.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Slider(
            modifier = Modifier.width(132.dp).height(48.dp).probe("vol_slider"),
            value = v.ordinal / 2f,
            onValueChange = { x ->
                val snapped = Volume.entries[(x * 2f).roundToInt().coerceIn(0, 2)]
                if (snapped != v) onEvent(PcEvent.VolumeSet(snapped))
            },
            valueRange = 0f..1f,
            enabled = sound,
        )
        for (d in Volume.entries) Box(Modifier.size(0.dp).probe("vol_${d.name.lowercase()}") { onEvent(PcEvent.VolumeSet(d)) })
        val word = rs(when (v) { Volume.LOW -> R.string.vol_low; Volume.MID -> R.string.vol_mid; Volume.HIGH -> R.string.vol_high })
        val text = if (sound) "$word ${(v.gain * 100).roundToInt()}%" else rs(R.string.off)
        Text(text = text, color = if (sound) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary, style = PcType.figure, maxLines = 1, onTextLayout = overflowLog(text))
    }
}

@Composable
internal fun SettingsPage(state: PcUiState, filled: Boolean, onEvent: (PcEvent) -> Unit) {
    WindowPage(padding = 12.dp, spacing = 0.dp) {
        if (!filled) return@WindowPage
        val s = state.settings
        val inLesson = state.back.inLesson
        val zh = rs(R.string.lang_code) == "zh"
        @Composable
        fun row(icon: Int, label: String, content: @Composable () -> Unit) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Label(label, Modifier.weight(1f))
                content()
            }
        }
        // the TabBar shows icons only: the page names itself (factory native-window-move §3.6)
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) { Title(rs(R.string.action_settings)) }
        // gate:allow tabs-segment in-page option (one setting row's options), not a page switch: pages switch only by the native TabBar
        row(R.drawable.ic_zoom_in, rs(R.string.set_bud_zoom)) { Segments(listOf("zoom_on" to rs(R.string.on), "zoom_off" to rs(R.string.off)), if (s.budZoom) 0 else 1, { onEvent(PcEvent.BudZoom(it == 0)) }, width = 220.dp) }
        row(R.drawable.ic_height, rs(R.string.set_floor)) {
            Row(Modifier.width(220.dp), verticalAlignment = Alignment.CenterVertically) {
                Key("floor_down", R.drawable.ic_remove, "-", enabled = state.virtualFloor) { onEvent(PcEvent.FloorStep(-1)) }
                Text(text = if (state.virtualFloor) rs(R.string.floor_virtual, (s.floorOffset * 100).roundToInt()) else rs(R.string.floor_real), color = PicoTheme.colorScheme.labelPrimary, style = PcType.figure, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
                Key("floor_up", R.drawable.ic_add, "+", enabled = state.virtualFloor) { onEvent(PcEvent.FloorStep(1)) }
            }
        }
        // gate:allow tabs-segment in-page option (one setting row's options), not a page switch: pages switch only by the native TabBar
        row(R.drawable.ic_chair, rs(R.string.set_seated)) { Segments(listOf("stand" to rs(R.string.set_stand), "sit" to rs(R.string.set_sit)), if (s.seated) 1 else 0, { onEvent(PcEvent.Seated(it == 1)) }, width = 220.dp) }
        row(R.drawable.ic_my_location, rs(R.string.action_bring_here)) { WordButton("set_recall", rs(R.string.action_bring_here), { onEvent(PcEvent.Recall) }, enabled = inLesson, minWidth = 220.dp, height = 48.dp) }
        row(R.drawable.ic_park, rs(R.string.action_replant)) { WordButton("set_replant", rs(R.string.action_replant), { onEvent(PcEvent.Replant) }, enabled = inLesson, minWidth = 220.dp, height = 48.dp) }
        // gate:allow tabs-segment in-page option (one setting row's options), not a page switch: pages switch only by the native TabBar
        row(R.drawable.ic_volume_up, rs(R.string.set_sound)) { Segments(listOf("sound_on" to rs(R.string.on), "sound_off" to rs(R.string.off)), if (s.sound) 0 else 1, { onEvent(PcEvent.Sound(it == 0)) }, width = 220.dp) }
        row(R.drawable.ic_volume_up, rs(R.string.set_volume)) { VolumeSlider(s.volume, s.sound, onEvent) }
        val langSel = when (s.language) { LanguagePref.ZH -> 0; LanguagePref.EN -> 1; LanguagePref.SYSTEM -> if (zh) 0 else 1 }
        // gate:allow tabs-segment in-page option (one setting row's options), not a page switch: pages switch only by the native TabBar
        row(R.drawable.ic_language, rs(R.string.set_language)) { Segments(listOf("lang_zh" to rs(R.string.lang_zh), "lang_en" to rs(R.string.lang_en)), langSel, { onEvent(PcEvent.Language(if (it == 0) LanguagePref.ZH else LanguagePref.EN)) }, width = 220.dp) }
        row(R.drawable.ic_restart_alt, rs(R.string.action_reset_lesson)) { WordButton("set_reset_lesson", rs(R.string.action_reset_lesson), { onEvent(PcEvent.ResetLesson) }, enabled = inLesson, minWidth = 220.dp, height = 48.dp) }
        row(R.drawable.ic_restart_alt, rs(R.string.action_reset_all)) { WordButton("set_reset_all", rs(R.string.action_reset_all), { onEvent(PcEvent.AskResetAll) }, danger = true, minWidth = 220.dp, height = 48.dp) }
        if (state.confirm == null) {
            row(R.drawable.ic_info, rs(R.string.set_about)) { Label(rs(R.string.about_version), Modifier.width(220.dp), secondary = true) }
            row(R.drawable.ic_logout, rs(R.string.action_quit)) { WordButton("set_quit", rs(R.string.action_quit), { onEvent(PcEvent.AskQuit) }, danger = true, minWidth = 220.dp, height = 48.dp, iconRes = R.drawable.ic_logout) }
        } else {
            // design S15b: the bottom of the page turns into the question + [Cancel] [Quit / Clear]
            val quit = state.confirm == Confirm.QUIT
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Title(if (quit) rs(R.string.confirm_quit) else rs(R.string.confirm_reset))
            }
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap, Alignment.End)) {
                WordButton("confirm_no", rs(R.string.action_cancel), { onEvent(PcEvent.ConfirmNo) }, minWidth = 140.dp, iconRes = R.drawable.ic_close)
                WordButton("confirm_yes", if (quit) rs(R.string.action_quit_short) else rs(R.string.action_clear), { onEvent(PcEvent.ConfirmYes) }, danger = true, minWidth = 140.dp, iconRes = if (quit) R.drawable.ic_logout else R.drawable.ic_restart_alt)
            }
        }
    }
}

// =============================================================================================
// explain card (design `panels.explainCard`, 400 x 220)
// =============================================================================================

@Composable
internal fun ExplainCard(state: PcUiState, filled: Boolean, onEvent: (PcEvent) -> Unit) {
    val (w, h) = PanelSpec.size(PanelSpec.EXPLAIN)
    Glass(w.dp, h.dp, corner = 24.dp, spacing = Gap) {
        if (!filled) return@Glass
        val e = state.explain ?: return@Glass
        val t = TreeCache.get(state.lesson)
        val b = t[e.branch]
        val strong = (b?.diameterMm ?: 0f) > 10f
        val leader = b?.defect == io.embodit.pruningclass.domain.tree.Defect.LEADER
        val title =
            when (e.reason) {
                null -> if (e.hintRule != null) rs(R.string.explain_hint, e.hintRule) else rs(R.string.explain_ok)
                Reason.KEEP_BLOOM -> rs(R.string.explain_keepBloom)
                Reason.KEEP_SPUR -> rs(R.string.explain_keepSpur)
                Reason.MAJOR -> rs(R.string.explain_major)
                Reason.INWARD_BUD -> rs(R.string.explain_inwardBud)
                Reason.OVER_PRUNE -> rs(R.string.explain_overPrune)
                Reason.TOO_MANY -> if (leader) rs(R.string.explain_tooManyLeader) else if (strong) rs(R.string.explain_tooMany) else rs(R.string.explain_tooManyWeak)
                Reason.TOO_LOW -> if (leader) rs(R.string.explain_tooLowLeader) else if (strong) rs(R.string.explain_tooLow) else rs(R.string.explain_tooLowWeak)
                Reason.IN_DEAD -> rs(R.string.explain_inDead)
                Reason.SECOND_OF_PAIR -> rs(R.string.explain_secondOfPair)
                Reason.PARTIAL -> rs(R.string.explain_partial)
            }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(if (e.wrong) PicoTheme.colorScheme.error else PicoTheme.colorScheme.alert))
            Title(title, Modifier.weight(1f))
        }
        val rule = b?.let { io.embodit.pruningclass.ui.pc.RuleOf.of(t.species, it.defect, e.reason) }
        if (rule != null) Label("$rule · " + rsn("rule_" + rule.replace("-", "")), maxLines = 3)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
            if (e.canRejoin) WordButton("rejoin", rs(R.string.action_rejoin), { onEvent(PcEvent.Rejoin) }, minWidth = 140.dp, iconRes = R.drawable.ic_undo)
            WordButton("got_it", rs(R.string.action_got_it), { onEvent(PcEvent.GotIt) }, primary = true, minWidth = 140.dp, iconRes = R.drawable.ic_check)
        }
        Spacer(Modifier.height(4.dp))
    }
}
