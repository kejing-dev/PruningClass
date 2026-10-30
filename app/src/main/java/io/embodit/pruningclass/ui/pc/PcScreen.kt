package io.embodit.pruningclass.ui.pc

import android.app.Activity
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import kotlin.system.exitProcess

/**
 * The one screen of the app, living in a Mixed Stage. The ViewModel holds the state, the [PcScene] turns state into
 * entities and reports the user's 3D input and its milestones, and [PcStage] declares the panels.
 */
@Composable
fun PcScreen() {
    val context = LocalContext.current
    val viewModel: PcViewModel = viewModel(factory = PcViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

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
                PcEffect.QuitApp -> {
                    // design D26: leave the task, release tracking / audio, end the process
                    PcLog.i("QUIT finishAndRemoveTask")
                    scene.detach()
                    runCatching { navigator.closeStage() }
                    runCatching { (context as? Activity)?.finishAndRemoveTask() }
                    exitProcess(0)
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
        PcStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
