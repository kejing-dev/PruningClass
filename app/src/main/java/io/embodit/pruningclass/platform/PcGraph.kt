package io.embodit.pruningclass.platform

import android.content.Context
import io.embodit.pruningclass.data.Store
import io.embodit.pruningclass.platform.audio.Sfx
import io.embodit.pruningclass.platform.input.ControllerInput
import io.embodit.pruningclass.platform.sense.PlaneTracker
import io.embodit.pruningclass.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The scene starts and stops them (every start has a stop: see
 * `PcScene.attach` / `detach` / `onBackground`; plane detection only while a tree is being placed, design D24); the
 * ViewModel only sees state.
 */
object PcGraph {
    val head = HeadTracker()
    val controller = ControllerInput()
    val planes = PlaneTracker()
    val sfx = Sfx()

    @Volatile var appContext: Context? = null

    @Volatile private var store: Store? = null

    fun store(context: Context): Store = store ?: synchronized(this) { store ?: Store(context).also { store = it } }
}
