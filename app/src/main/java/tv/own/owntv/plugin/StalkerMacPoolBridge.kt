package tv.own.owntv.plugin

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.koin.dsl.module
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.player.ErrorInfo
import tv.own.owntv.player.PlaybackFailure
import tv.own.owntv.plugin.stalkermacpool.di.stalkerMacPoolModule
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacPoolDefaults
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacPoolHostCallbacks
import tv.own.owntv.plugin.stalkermacpool.playback.StalkerMacHudHandle
import tv.own.owntv.plugin.stalkermacpool.playback.rememberStalkerMacHudHandle as rememberPluginHudHandle
import tv.own.owntv.plugin.stalkermacpool.ui.QuickMacSwitcherDialog
import tv.own.owntv.plugin.stalkermacpool.ui.StalkerMacPoolScreen
import tv.own.owntv.ui.components.BrowseMode
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.StorageBrowser
import tv.own.owntv.ui.components.TvNotices
import tv.own.owntv.ui.stage.ErrorButton
import tv.own.owntv.ui.theme.stageText
import java.io.File
import tv.own.owntv.plugin.stalkermacpool.R as PluginR

private const val SPINNER_DP = 24
private const val SPINNER_GAP_DP = 10
private const val ROW_GAP_DP = 12
private const val STATUS_TEXT_SP = 22
private const val STATUS_FONT_WEIGHT = 500

val stalkerMacPoolBridgeModule = module {
    includes(stalkerMacPoolModule)
    single<StalkerMacPoolHostCallbacks> {
        val mainHandler = Handler(Looper.getMainLooper())
        val syncScheduler: CatalogSyncScheduler = get()
        object : StalkerMacPoolHostCallbacks {
            override fun showToast(message: String) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    TvNotices.show(message)
                } else {
                    mainHandler.post { TvNotices.show(message) }
                }
            }

            override fun enqueueSourceSync(source: SourceEntity) {
                syncScheduler.enqueueSync(
                    sourceId = source.id,
                    reason = StalkerMacPoolDefaults.SYNC_REASON,
                    contentTypes = SyncContentTypes.enabledOf(source),
                    baseItemCount = 0,
                    forcePrune = false,
                )
            }
        }
    }
}

@Composable
fun StalkerMacPoolHostScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
    autoFocusOnStart: Boolean = false,
) {
    var pickCallback by remember { mutableStateOf<((File) -> Unit)?>(null) }

    StalkerMacPoolScreen(
        onBack = onBack,
        onPickLocalJson = { callback -> pickCallback = callback },
        entryFocus = entryFocus,
        autoFocusOnStart = autoFocusOnStart,
        modifier = modifier,
    )

    pickCallback?.let { cb ->
        StorageBrowser(
            title = stringResource(PluginR.string.smp_pick_json_title),
            mode = BrowseMode.FILE,
            fileExtensions = StalkerMacPoolDefaults.JSON_EXTENSIONS,
            onPick = { file ->
                pickCallback = null
                cb(file)
            },
            onDismiss = { pickCallback = null },
        )
    }
}

@Composable
fun rememberStalkerMacHudHandle(
    error: PlaybackFailure?,
    errorInfo: ErrorInfo?,
): StalkerMacHudHandle = rememberPluginHudHandle(error, errorInfo)

@Composable
fun StalkerMacErrorButtons(
    handle: StalkerMacHudHandle,
    primaryFocus: FocusRequester,
    onRetry: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (handle.switching) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OwnTVSpinner(sizeDp = SPINNER_DP)
                Spacer(Modifier.width(SPINNER_GAP_DP.dp))
                Text(
                    text = stringResource(PluginR.string.smp_switching_mac),
                    style = stageText(STATUS_TEXT_SP, STATUS_FONT_WEIGHT),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(ROW_GAP_DP.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp)) {
            ErrorButton(
                text = stringResource(PluginR.string.smp_error_switch_mac, handle.remainingCount),
                onClick = { if (!handle.switching) handle.switchNext() },
                modifier = Modifier.focusRequester(primaryFocus),
            )
            ErrorButton(
                text = stringResource(PluginR.string.smp_error_pick_mac),
                onClick = { if (!handle.switching) handle.openPicker() },
            )
            ErrorButton(
                text = stringResource(R.string.common_retry),
                onClick = onRetry,
            )
        }
    }
}

@Composable
fun StalkerMacQuickPickerHost(handle: StalkerMacHudHandle) {
    val match = handle.match ?: return
    if (!handle.showPicker) return
    QuickMacSwitcherDialog(
        match = match,
        autoSwitchEnabled = handle.autoSwitchEnabled,
        switching = handle.switching,
        onSwitchNext = handle.switchNext,
        onSelectMac = handle.switchTo,
        onToggleAutoSwitch = handle.toggleAutoSwitch,
        onDismiss = handle.closePicker,
    )
}
