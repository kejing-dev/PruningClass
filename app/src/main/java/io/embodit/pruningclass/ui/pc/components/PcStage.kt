package io.embodit.pruningclass.ui.pc.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.pruningclass.R
import io.embodit.pruningclass.ui.pc.PcEvent
import io.embodit.pruningclass.ui.pc.PcShared
import io.embodit.pruningclass.ui.pc.PcUiState
import io.embodit.pruningclass.ui.pc.debug.LocalProbePanel
import io.embodit.pruningclass.ui.pc.scene.PanelSpec
import io.embodit.pruningclass.ui.pc.scene.PcScene
import kotlinx.coroutines.delay

/**
 * The Stage content: one full-size SpatialView that carries the 3D scene and the two small Stage panels: the explain
 * card beside a cut and the "open panel" chip (shown while the main window is closed with its ×). Every other 2D page
 * lives in the main system window (`PcWindow`, rework 2026-11-02: one native window, no self-built panel drag).
 */
@Composable
internal fun PcStage(state: PcUiState, scene: PcScene, onEvent: (PcEvent) -> Unit, onOpenWindow: () -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                // tap and drag live in separate pointerInput blocks (two spatial recognizers must not share one);
                // the drag moves the tree by its floor ring (D21: the 3D object's own handle), never a panel
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
            AttachmentPanel(PanelSpec.EXPLAIN) { PoolPanel(PanelSpec.EXPLAIN, scene, refresh = listOf(state.settings.language, state.explain?.serial)) { inView -> ExplainCard(state, inView, onEvent) } }
            AttachmentPanel(PanelSpec.OPEN) { PoolPanel(PanelSpec.OPEN, scene, refresh = listOf(state.settings.language, PcShared.levelHint)) { inView -> OpenWindowChip(inView, onOpenWindow) } }
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

/**
 * "Open panel": the way back to the main window after its × (factory native-window-move §3.3, QA "× → open again").
 * While an open waits for a level head the same slot says "look straight ahead" instead (native-window-move §7 item 9).
 */
@Composable
private fun OpenWindowChip(inView: Boolean, onOpen: () -> Unit) {
    val (w, h) = PanelSpec.size(PanelSpec.OPEN)
    if (PcShared.levelHint) {
        Glass(w.dp, h.dp, corner = 24.dp, padding = 12.dp, spacing = 0.dp) {
            if (!inView) return@Glass
            val text = rs(R.string.look_level)
            Text(
                text = text,
                color = PicoTheme.colorScheme.labelPrimary,
                style = PcType.body,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
                onTextLayout = overflowLog(text),
            )
        }
        return
    }
    Box(Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (!inView) return@Box
        WordButton("open_window", rs(R.string.action_open_panel), onOpen, primary = true, minWidth = (w - 16).dp, iconRes = R.drawable.ic_menu_book)
    }
}
