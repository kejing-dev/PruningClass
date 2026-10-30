package io.embodit.pruningclass.ui.pc.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.pruningclass.ui.pc.PcEvent
import io.embodit.pruningclass.ui.pc.PcUiState
import io.embodit.pruningclass.ui.pc.debug.LocalProbePanel
import io.embodit.pruningclass.ui.pc.scene.PcScene
import io.embodit.pruningclass.ui.pc.scene.PanelSpec

/**
 * The Stage content: one full-size SpatialView that carries the 3D scene and the panel pool. Every
 * attachment panel is declared here once, with a fixed size, whether it is showing or not; the scene
 * parents each panel entity under its holder and shows or hides the holder.
 *
 * Window model: single panel with popup (design): one standing panel whose content changes (control, welcome, picker,
 * rules, result, card, settings: each its own pooled panel, shown alone at the same place), the explain card as the popup
 * and the blocked dialog. No WindowContainer, no planar window.
 */
@Composable
internal fun PcStage(state: PcUiState, scene: PcScene, onEvent: (PcEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                // tap and drag live in separate pointerInput blocks (two spatial recognizers must not share one)
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { _, value -> scene.onSpatialDrag("start", value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", value) },
                    ) { value ->
                        scene.onSpatialDrag("move", value)
                    }
                },
        attachments = {
            AttachmentPanel(PanelSpec.CONTROL) { PoolPanel(PanelSpec.CONTROL, scene, refresh = listOf(state.settings.language, state.phase, state.cuts.size, state.selected, state.preview, state.hints, state.inside, state.moving, state.turning, state.floorNotice)) { inView -> ControlPanel(state, inView, onEvent) } }
            AttachmentPanel(PanelSpec.WELCOME) { PoolPanel(PanelSpec.WELCOME, scene, refresh = state.settings.language) { inView -> WelcomePage(inView, onEvent) } }
            AttachmentPanel(PanelSpec.PICKER) { PoolPanel(PanelSpec.PICKER, scene, refresh = listOf(state.settings.language, state.records)) { inView -> PickerPage(state, inView, onEvent) } }
            AttachmentPanel(PanelSpec.RULES) { PoolPanel(PanelSpec.RULES, scene, refresh = listOf(state.settings.language, state.lesson)) { inView -> RulesPage(state, inView, onEvent) } }
            AttachmentPanel(PanelSpec.RESULT) { PoolPanel(PanelSpec.RESULT, scene, refresh = listOf(state.settings.language, state.score, state.view)) { inView -> ResultPage(state, inView, onEvent) } }
            AttachmentPanel(PanelSpec.CARD) { PoolPanel(PanelSpec.CARD, scene, refresh = listOf(state.settings.language, state.phase, state.cardSpecies)) { inView -> CardPage(state, inView, onEvent) } }
            AttachmentPanel(PanelSpec.SETTINGS) { PoolPanel(PanelSpec.SETTINGS, scene, refresh = listOf(state.settings.language, state.confirm, state.settings, state.virtualFloor)) { inView -> SettingsPage(state, inView, onEvent) } }
            AttachmentPanel(PanelSpec.EXPLAIN) { PoolPanel(PanelSpec.EXPLAIN, scene, refresh = listOf(state.settings.language, state.explain?.serial)) { inView -> ExplainCard(state, inView, onEvent) } }
            AttachmentPanel(PanelSpec.DIALOG) { PoolPanel(PanelSpec.DIALOG, scene, refresh = state.blockReason) { inView -> BlockedDialog(reason = if (inView) state.blockReason else null, onEvent = onEvent) } }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel. [content] gets `inView`: the panel has been moved into view. A pooled panel must draw
 * its content only then (what it draws while parked out of sight stays broken on the emulator). On the idle
 * emulator a panel can keep a half-recorded first frame (glass cut off, black below): the content is composed
 * afresh 1.5, 4 and 8 s after the panel comes into view or [refresh] changes (factory finding on bianzhong).
 */
@Composable
private fun PoolPanel(id: String, scene: PcScene, refresh: Any? = null, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    var gen by remember { mutableIntStateOf(0) }
    LaunchedEffect(inView, refresh) {
        if (!inView) return@LaunchedEffect
        for (wait in REFRESH_STEPS_MS) {
            delay(wait)
            gen++
        }
    }
    CompositionLocalProvider(LocalProbePanel provides id) { key(gen) { content(inView) } }
}

// rebuilt 1.5 / 4 / 8 / 12 / 20 s after a panel opens or its content changes: the idle emulator can keep a half-drawn frame
// (black below the first rows) until the next rebuild
private val REFRESH_STEPS_MS = longArrayOf(1_500L, 2_500L, 4_000L, 4_000L, 8_000L)
