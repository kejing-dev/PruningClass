package io.embodit.pruningclass.ui.pc

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.pruningclass.platform.PcLog
import io.embodit.pruningclass.ui.pc.components.LocalPcResources
import io.embodit.pruningclass.ui.pc.components.PcStage
import io.embodit.pruningclass.ui.pc.components.localizedResources
import io.embodit.pruningclass.ui.pc.components.stringByName
import io.embodit.pruningclass.ui.pc.debug.PcProbe
import io.embodit.pruningclass.ui.pc.scene.PcFrameSystem
import io.embodit.pruningclass.ui.pc.scene.PcScene
import io.embodit.pruningclass.ui.pc.window.exitApp

/**
 * The Stage side of the app (Mixed Stage). The shared ViewModel holds the state, the [PcScene] turns state into entities
 * and reports the user's 3D input and its milestones, [PcStage] declares the two Stage panels; every page is in the
 * main system window (`PcWindow`), which this screen opens, recalls and closes.
 */
@Composable
fun PcScreen() {
    val context = LocalContext.current
    // shared with the main window (two containers, one process: factory native-window-move §3.4)
    val viewModel = remember(context) { PcShared.viewModel(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    // the main window opens with the Stage; its × closes it and the Stage's chip opens it again
    fun openWindow() {
        if (PcShared.windowMinimised && navigator.restoreWindowContainer(PcShared.WINDOW_ID, PcShared.WINDOW_TAG)) {
            PcLog.i("WINDOW restore (was minimised)")
            return
        }
        navigator.openWindowContainer(PcShared.WINDOW_ID, PcShared.WINDOW_TAG)
    }
    fun closeWindow() = navigator.closeWindowContainer(PcShared.WINDOW_ID, PcShared.WINDOW_TAG)
    LaunchedEffect(Unit) {
        // the ViewModel outlives a Stage (process level): a new Stage starts from S00 like a cold start
        viewModel.stageStarted()
        PcLog.i("WINDOW open (Stage started)")
        openWindow()
    }
    // [Bring here] (settings): the window cannot be moved by code; closed and opened again it comes back in front of
    // the head. Open only after ON_DESTROY: an open right after a close is swallowed (native-window-move §3.5)
    var recallSeen by remember { mutableIntStateOf(state.recallSerial) }
    LaunchedEffect(state.recallSerial) {
        if (state.recallSerial == recallSeen) return@LaunchedEffect
        recallSeen = state.recallSerial
        if (PcShared.windowOpen) {
            PcShared.recallPending = true
            PcLog.i("WINDOW recall: close, open after ON_DESTROY")
            closeWindow()
        } else {
            openWindow()
        }
    }
    // pending until the window is back (it takes 3 - 6 s to appear): the "open panel" chip stays hidden meanwhile
    var recallOpened by remember { mutableStateOf(false) }
    LaunchedEffect(PcShared.window) {
        if (!PcShared.recallPending) return@LaunchedEffect
        if (PcShared.window == "ON_DESTROY" && !recallOpened) {
            recallOpened = true
            PcLog.i("WINDOW recall: open")
            openWindow()
        } else if (PcShared.windowOpen && recallOpened) {
            recallOpened = false
            PcShared.recallPending = false
        }
    }

    remember(context) {
        PcProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { PcScene(viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<PcFrameSystem>()
        PcProbe.host =
            object : PcProbe.Host {
                override fun dispatch(event: PcEvent) = viewModel.onEvent(event)

                override fun uiState(): PcUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpLines().joinToString("\n")

                override fun scene(): PcProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = PcProbe.click(name)

                override fun window(open: Boolean) = if (open) openWindow() else closeWindow()
            }
        PcLog.i("STAGE enter")
        onDispose {
            PcProbe.host = null
            unregisterSystem<PcFrameSystem>()
            scene.detach()
            PcLog.i("STAGE disposed")
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            viewModel.onEvent(PcEvent.WentBackground)
                            scene.onBackground()
                        }
                        Lifecycle.Event.ON_START -> {
                            scene.onForeground()
                            viewModel.onEvent(PcEvent.CameForeground)
                        }
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                // design D26 / template exit: release tracking / audio and the window, close the Stage, remove the tasks, end
                PcEffect.QuitApp ->
                    exitApp(context, navigator) {
                        scene.detach()
                        closeWindow()
                    }
            }
        }
    }

    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    remember(resources) {
        scene.words = { key -> stringByName(resources, key) }
        true
    }
    CompositionLocalProvider(LocalPcResources provides resources) {
        PcStage(state = state, scene = scene, onEvent = viewModel::onEvent, onOpenWindow = ::openWindow)
    }
}
