package io.embodit.pruningclass.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.pruningclass.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PcGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
