package tv.own.owntv.plugin.stalkermacpool.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.player.ErrorInfo
import tv.own.owntv.player.PlaybackFailure
import tv.own.owntv.plugin.stalkermacpool.data.StalkerMacPoolStore
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacPoolDefaults

@Stable
class StalkerMacHudHandle(
    val match: StalkerMacSwitcher.SourcePoolMatch?,
    val autoSwitchEnabled: Boolean,
    val switching: Boolean,
    val showPicker: Boolean,
    val canSwitch: Boolean,
    val remainingCount: Int,
    val openPicker: () -> Unit,
    val closePicker: () -> Unit,
    val switchNext: () -> Unit,
    val switchTo: (String) -> Unit,
    val toggleAutoSwitch: () -> Unit,
)

@Composable
fun rememberStalkerMacHudHandle(
    error: PlaybackFailure?,
    errorInfo: ErrorInfo?,
): StalkerMacHudHandle {
    val holder: StalkerPlaybackSessionHolder = koinInject()
    val switcher: StalkerMacSwitcher = koinInject()
    val store: StalkerMacPoolStore = koinInject()
    val scope = rememberCoroutineScope()

    LaunchedEffect(store) { store.ensureLoaded() }
    LaunchedEffect(error) {
        if (error == null) holder.resetAutoSwitchStreak()
    }

    val activeCtx by holder.active.collectAsStateWithLifecycle()
    val snapshot by store.snapshot.collectAsStateWithLifecycle()
    val match by remember(activeCtx?.sourceId, switcher) {
        activeCtx?.sourceId?.let { switcher.observeSourcePoolStatus(it) } ?: flowOf(null)
    }.collectAsStateWithLifecycle(null)

    var switching by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    val canAutoSwitch =
        match != null &&
            (match?.healthyAlternateCount ?: 0) > 0 &&
            snapshot.settings.autoSwitchOnConflict &&
            StalkerMacRotationPolicy.isConflictForAutoSwitch(error, errorInfo)

    LaunchedEffect(error, errorInfo, canAutoSwitch, activeCtx) {
        if (error == null || !canAutoSwitch) return@LaunchedEffect
        val ctx = activeCtx ?: return@LaunchedEffect
        if (holder.currentAutoSwitchStreak() >= StalkerMacPoolDefaults.AUTO_SWITCH_MAX_STREAK) {
            return@LaunchedEffect
        }
        delay(StalkerMacPoolDefaults.AUTO_SWITCH_DELAY_MS)
        holder.incrementAutoSwitchStreak()
        switching = true
        try {
            switcher.switchMacForPlayback(
                context = ctx,
                targetMac = null,
                failure = error,
                errorInfo = errorInfo,
            )
        } finally {
            switching = false
        }
    }

    val canSwitch = activeCtx != null && match != null && (match?.alternateCount ?: 0) > 0
    val remaining = match?.healthyAlternateCount ?: 0

    return StalkerMacHudHandle(
        match = match,
        autoSwitchEnabled = snapshot.settings.autoSwitchOnConflict,
        switching = switching,
        showPicker = showPicker,
        canSwitch = canSwitch,
        remainingCount = remaining,
        openPicker = { showPicker = true },
        closePicker = { showPicker = false },
        switchNext = {
            val ctx = activeCtx ?: return@StalkerMacHudHandle
            if (switching) return@StalkerMacHudHandle
            showPicker = false
            scope.launch {
                switching = true
                try {
                    switcher.switchMacForPlayback(
                        context = ctx,
                        targetMac = null,
                        failure = error,
                        errorInfo = errorInfo,
                    )
                } finally {
                    switching = false
                }
            }
        },
        switchTo = { mac ->
            val ctx = activeCtx ?: return@StalkerMacHudHandle
            if (switching) return@StalkerMacHudHandle
            showPicker = false
            scope.launch {
                switching = true
                try {
                    switcher.switchMacForPlayback(
                        context = ctx,
                        targetMac = mac,
                        failure = null,
                        errorInfo = null,
                    )
                } finally {
                    switching = false
                }
            }
        },
        toggleAutoSwitch = {
            scope.launch {
                store.updateSettings { it.copy(autoSwitchOnConflict = !it.autoSwitchOnConflict) }
            }
        },
    )
}
