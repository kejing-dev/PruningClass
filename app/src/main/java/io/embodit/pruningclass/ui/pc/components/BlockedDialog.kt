package io.embodit.pruningclass.ui.pc.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.pruningclass.R
import io.embodit.pruningclass.domain.model.BlockReason
import io.embodit.pruningclass.ui.pc.PcEvent

/**
 * "Not available now" (S16; the lesson tab of the main window): title, reason, [Retry] [Quit]. Self-built: in a Stage
 * the SpatialUI `AlertDialog` composes nothing (Spatial Ruler finding).
 */
@Composable
internal fun BlockedDialog(reason: BlockReason?, onEvent: (PcEvent) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        if (reason == null) return@Box
        val ink = PicoTheme.colorScheme.labelPrimary
        // the main window is the glass: the page is the dialog's rows
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painter = painterResource(R.drawable.ic_info), contentDescription = null, modifier = Modifier.size(28.dp), tint = ink)
                val title = rs(R.string.dialog_title)
                Text(text = title, color = ink, style = PcType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = overflowLog(title))
            }
            Spacer(modifier = Modifier.height(8.dp))
            val body = if (reason == BlockReason.NO_HEAD_POSE || reason == BlockReason.DEVICE_NOT_SUPPORTED) rs(R.string.dialog_no_head) else rs(R.string.dialog_not_full_space)
            Text(
                text = body,
                color = PicoTheme.colorScheme.labelSecondary,
                style = PcType.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = overflowLog(body),
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                WordButton("dialog_exit", rs(R.string.action_quit_short), { onEvent(PcEvent.QuitBlocked) }, minWidth = 120.dp, iconRes = R.drawable.ic_logout)
                WordButton("dialog_retry", rs(R.string.action_retry), { onEvent(PcEvent.RetryBlocked) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_replay)
            }
        }
    }
}
