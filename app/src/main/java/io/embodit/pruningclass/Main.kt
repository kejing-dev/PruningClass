package io.embodit.pruningclass

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.pruningclass.ui.pc.PcScreen
import io.embodit.pruningclass.ui.pc.PcShared
import io.embodit.pruningclass.ui.pc.window.PcWindow
import io.embodit.pruningclass.ui.theme.PruningTheme

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0) holds the tree and the
 * 3D marks; one Planar system window holds every 2D page (rework 2026-11-02, factory native-window-move: the window's
 * own controls move / close / minimise it). The Stage opens the window when it starts.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            PruningTheme {
                PcScreen()
            }
        }
        // sized for the tallest page (settings 13 rows, the result page with five review rows)
        WindowContainer(PcShared.WINDOW_ID, form = Form.Planar, defaultSize = WindowContainerSize(width = 600.dp, height = 780.dp)) {
            PruningTheme {
                PcWindow()
            }
        }
    }
