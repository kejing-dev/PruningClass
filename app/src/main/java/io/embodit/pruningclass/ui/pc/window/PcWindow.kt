package io.embodit.pruningclass.ui.pc.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.embodit.pruningclass.R
import io.embodit.pruningclass.ui.pc.PcEvent
import io.embodit.pruningclass.ui.pc.PcPages
import io.embodit.pruningclass.ui.pc.PcShared
import io.embodit.pruningclass.ui.pc.PcUiState
import io.embodit.pruningclass.ui.pc.Phase
import io.embodit.pruningclass.ui.pc.components.BlockedDialog
import io.embodit.pruningclass.ui.pc.components.CardPage
import io.embodit.pruningclass.ui.pc.components.ControlPanel
import io.embodit.pruningclass.ui.pc.components.LocalInWindow
import io.embodit.pruningclass.ui.pc.components.LocalPcResources
import io.embodit.pruningclass.ui.pc.components.PickerPage
import io.embodit.pruningclass.ui.pc.components.ResultPage
import io.embodit.pruningclass.ui.pc.components.RulesPage
import io.embodit.pruningclass.ui.pc.components.SettingsPage
import io.embodit.pruningclass.ui.pc.components.WelcomePage
import io.embodit.pruningclass.ui.pc.components.localizedResources
import io.embodit.pruningclass.ui.pc.components.rs
import io.embodit.pruningclass.ui.pc.debug.LocalProbePanel

/**
 * Content of the main system window (Planar WindowContainer, `Main.kt`): every 2D page of the app in one window,
 * three TabBar pages: the lesson (welcome, picker, rules, control, result, graduation, blocked: by phase), the rule card
 * and settings. Moving / closing is the system window's (rework 2026-11-02, factory native-window-move).
 */
@Composable
fun PcWindow() {
    val context = LocalContext.current
    val viewModel = remember(context) { PcShared.viewModel(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // the Stage shows its "open panel" chip while this window is closed, and recalls it by close + reopen
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> PcShared.windowEvent(e.name) }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            PcShared.windowEvent("ON_DESTROY")
        }
    }

    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    val onEvent = viewModel::onEvent
    CompositionLocalProvider(LocalPcResources provides resources, LocalInWindow provides true, LocalProbePanel provides "window") {
        MainWindow(
            pages =
                listOf(
                    MainWindowPage(PcPages.LESSON, rs(R.string.tab_lesson), R.drawable.ic_park) { LessonPage(state, onEvent) },
                    MainWindowPage(PcPages.CARD, rs(R.string.action_rule_card), R.drawable.ic_menu_book) { CardPage(state, true, onEvent) },
                    MainWindowPage(PcPages.SETTINGS, rs(R.string.action_settings), R.drawable.ic_settings) { SettingsPage(state, true, onEvent) },
                ),
            current = PcPages.of(state.phase),
            onSelect = { page -> onEvent(PcEvent.TabSelected(page)) },
        )
    }
}

/** The lesson tab: what the lesson flow shows in this phase (the design's one panel that changes content). */
@Composable
private fun LessonPage(state: PcUiState, onEvent: (PcEvent) -> Unit) {
    when (state.phase) {
        Phase.WELCOME -> WelcomePage(true, onEvent)
        Phase.PICKER -> PickerPage(state, true, onEvent)
        Phase.RULES -> RulesPage(state, true, onEvent)
        Phase.RESULT -> ResultPage(state, true, onEvent)
        Phase.GRADUATED -> CardPage(state, true, onEvent)
        Phase.BLOCKED -> BlockedDialog(reason = state.blockReason, onEvent = onEvent)
        // starting, finding the floor, placing, pruning, relocating (settings / rule card are their own tabs)
        else -> ControlPanel(state, true, onEvent)
    }
}
