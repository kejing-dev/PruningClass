package io.embodit.pruningclass.ui.pc

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.pruningclass.platform.PcGraph
import io.embodit.pruningclass.platform.PcLog

/**
 * What the Stage and the main window share. They are two compositions in one process (factory snippet
 * native-window-move §3.4): the ViewModel lives here, not in either container's `viewModel()`, so closing the window
 * with its × keeps the lesson.
 */
object PcShared {
    /** Main window container id and its fixed tag (one instance, reused). */
    const val WINDOW_ID = "PruningClassWindow"
    const val WINDOW_TAG = "main"

    @Volatile private var vm: PcViewModel? = null

    fun viewModel(context: Context): PcViewModel =
        vm ?: synchronized(this) { vm ?: PcViewModel(PcGraph.store(context.applicationContext)).also { vm = it } }

    /** Last lifecycle event the main window reported (`never` until it first opens). */
    var window by mutableStateOf("never")
        private set

    fun windowEvent(name: String) {
        if (window != name) PcLog.i("WINDOW $name")
        window = name
    }

    /** The window is on screen (not closed with its ×, not minimised). */
    val windowOpen: Boolean
        get() = window == "ON_CREATE" || window == "ON_START" || window == "ON_RESUME" || window == "ON_PAUSE"

    /** [Bring here] closed the window and waits for its ON_DESTROY to open it again (the chip stays hidden meanwhile). */
    var recallPending by mutableStateOf(false)

    /** Minimised (ON_STOP, not destroyed): the chip restores it instead of opening a new one (native-window-move §2). */
    val windowMinimised: Boolean
        get() = window == "ON_STOP"

    /** Closed with its × or minimised (not "not opened yet": the window takes 3 - 6 s to appear at start). */
    val windowClosed: Boolean
        get() = window == "ON_DESTROY" || window == "ON_STOP"
}
