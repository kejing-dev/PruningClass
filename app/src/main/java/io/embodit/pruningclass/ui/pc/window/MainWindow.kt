package io.embodit.pruningclass.ui.pc.window

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.pruningclass.platform.PcLog
import kotlin.system.exitProcess

/*
 * Main window template (factory prompts/snippets/MainWindowTemplate.kt; taste D8 "one window", D21 moving is the
 * system's, D26 exit in settings). Adapted in two places for this app: the selected page is controlled by the caller
 * (the lesson state machine decides which page shows, a tab press is an event), and the settings page is the app's own
 * (bilingual, with the exit row and its inline confirmation last, as in the template).
 *  - All 2D content is in this one system window; pages switch with the native SpatialUI TabBar only.
 *  - Moving, closing and minimising are the system window controls under the window; no drag code here.
 */

/** One page of the main window. [icon]: a Material Symbols vector (taste D23). */
class MainWindowPage(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int,
    val content: @Composable () -> Unit,
)

/** The app's single main window: [pages] (at most 7, the TabBar limit), [current] shown, [onSelect] for a tab press. */
@Composable
fun MainWindow(pages: List<MainWindowPage>, current: String, onSelect: (String) -> Unit) {
    require(pages.size in 1..7) { "TabBar holds at most 7 items" }
    val page = pages.firstOrNull { it.id == current } ?: pages.first()
    // the window is sized for the tallest page (settings, result): pages fill it, no scrolling
    Box(Modifier.fillMaxSize()) { page.content() }
    // system TabBar: its own small window outside this one (emulator 6.1.0: an icon capsule above the window)
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        pages.forEach { p ->
            item(
                selected = page.id == p.id,
                onClick = {
                    PcLog.i("PAGE ${p.id}")
                    onSelect(p.id)
                },
                mainContent = { Icon(painter = painterResource(p.icon), contentDescription = p.label) },
                supportContent = { Text(p.label) },
            )
        }
    }
}

/** D26 exit: release, close the Stage, remove every task, then end the process (template order, theremin v0.1.8). */
suspend fun exitApp(context: Context, navigator: SpatialNavigator, onExit: () -> Unit) {
    PcLog.i("QUIT release + closeStage + finishAndRemoveTask + exitProcess")
    runCatching(onExit).onFailure { PcLog.e("QUIT release failed", it) }
    runCatching { navigator.closeStage() }.onFailure { PcLog.i("QUIT closeStage: $it") }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }.onFailure { PcLog.e("QUIT finishAndRemoveTask failed", it) }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
}
