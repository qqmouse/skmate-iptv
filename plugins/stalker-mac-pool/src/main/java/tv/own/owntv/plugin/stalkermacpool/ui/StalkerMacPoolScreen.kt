package tv.own.owntv.plugin.stalkermacpool.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.plugin.stalkermacpool.R
import tv.own.owntv.plugin.stalkermacpool.data.StalkerMacPoolStore
import tv.own.owntv.plugin.stalkermacpool.model.ImportMode
import tv.own.owntv.plugin.stalkermacpool.model.ServerSortMode
import tv.own.owntv.plugin.stalkermacpool.model.ServerSpeedStatus
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacEntry
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacPoolHostCallbacks
import tv.own.owntv.plugin.stalkermacpool.model.StalkerServerGroup
import tv.own.owntv.plugin.stalkermacpool.network.StalkerSpeedTester
import tv.own.owntv.plugin.stalkermacpool.parser.StalkerJsonImporter
import tv.own.owntv.plugin.stalkermacpool.playback.StalkerMacSwitcher
import java.io.File

@Composable
fun StalkerMacPoolScreen(
    onBack: () -> Unit,
    onPickLocalJson: ((File) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    entryFocus: FocusRequester? = null,
    autoFocusOnStart: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store: StalkerMacPoolStore = koinInject()
    val speedTester: StalkerSpeedTester = koinInject()
    val switcher: StalkerMacSwitcher = koinInject()
    val hostCallbacks: StalkerMacPoolHostCallbacks = koinInject()

    LaunchedEffect(store) { store.ensureLoaded() }

    val snapshot by store.snapshot.collectAsStateWithLifecycle()
    val progress by speedTester.progress.collectAsStateWithLifecycle()
    val activeSources by remember(switcher) { switcher.observeActiveStalkerSources() }
        .collectAsStateWithLifecycle(emptyMap())

    val sortedServers = remember(snapshot) { store.sortedServers(snapshot) }
    var selectedPortalKey by remember { mutableStateOf<String?>(null) }
    val selectedServer = remember(sortedServers, selectedPortalKey) {
        sortedServers.firstOrNull { it.portalKey == selectedPortalKey } ?: sortedServers.firstOrNull()
    }

    LaunchedEffect(sortedServers) {
        if (selectedPortalKey == null && sortedServers.isNotEmpty()) {
            selectedPortalKey = sortedServers.first().portalKey
        } else if (selectedPortalKey != null && sortedServers.none { it.portalKey == selectedPortalKey }) {
            selectedPortalKey = sortedServers.firstOrNull()?.portalKey
        }
    }

    val primaryToolbarFocus = entryFocus ?: remember { FocusRequester() }
    LaunchedEffect(autoFocusOnStart) {
        if (autoFocusOnStart) {
            runCatching { primaryToolbarFocus.requestFocus() }
        }
    }

    var showLanDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var pendingLocalFile by remember { mutableStateOf<File?>(null) }

    fun performLocalImport(file: File, mode: ImportMode) {
        scope.launch {
            runCatching { store.importFile(file, mode) }
                .onSuccess { s ->
                    hostCallbacks.showToast(
                        context.getString(
                            R.string.smp_import_result,
                            s.addedCount,
                            s.updatedCount,
                            s.serverCount,
                            s.uniqueRecords,
                        ),
                    )
                }
                .onFailure { e ->
                    hostCallbacks.showToast(
                        context.getString(R.string.smp_import_failed, e.message ?: ""),
                    )
                }
        }
    }

    val autoLabel = stringResource(
        if (snapshot.settings.autoSwitchOnConflict) R.string.smp_state_on else R.string.smp_state_off,
    )
    val sortLabel = formatSortModeLabel(context, snapshot.settings.sortMode)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header + summary bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.smp_menu_title),
                style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                color = SmpColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.smp_summary_bar,
                    sortedServers.size,
                    snapshot.totalMacCount,
                    autoLabel,
                ),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = SmpColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SmpButton(
                text = stringResource(R.string.smp_common_close),
                onClick = onBack,
            )
        }

        // Toolbar actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmpButton(
                text = stringResource(R.string.smp_toolbar_import_local),
                primary = true,
                modifier = Modifier.focusRequester(primaryToolbarFocus),
                onClick = {
                    onPickLocalJson { file ->
                        if (snapshot.servers.isEmpty()) {
                            performLocalImport(file, ImportMode.REPLACE)
                        } else {
                            pendingLocalFile = file
                        }
                    }
                },
            )
            SmpButton(
                text = stringResource(R.string.smp_toolbar_import_lan),
                onClick = { showLanDialog = true },
            )
            if (sortedServers.isNotEmpty()) {
                if (progress.running) {
                    SmpButton(
                        text = stringResource(R.string.smp_toolbar_speed_stop),
                        danger = true,
                        onClick = { speedTester.cancel() },
                    )
                } else {
                    SmpButton(
                        text = stringResource(R.string.smp_toolbar_speed_all),
                        onClick = { speedTester.testAll() },
                    )
                    SmpButton(
                        text = stringResource(R.string.smp_toolbar_speed_untested),
                        onClick = { speedTester.testUntested() },
                    )
                }
                SmpButton(
                    text = stringResource(R.string.smp_toolbar_sort, sortLabel),
                    onClick = {
                        val allModes = ServerSortMode.entries
                        val next = allModes[(allModes.indexOf(snapshot.settings.sortMode) + 1) % allModes.size]
                        scope.launch {
                            store.updateSettings { it.copy(sortMode = next) }
                        }
                    },
                )
            }
            SmpButton(
                text = stringResource(R.string.smp_toolbar_auto_switch, autoLabel),
                active = snapshot.settings.autoSwitchOnConflict,
                onClick = {
                    scope.launch {
                        store.updateSettings { it.copy(autoSwitchOnConflict = !it.autoSwitchOnConflict) }
                    }
                },
            )
            if (sortedServers.isNotEmpty() && !progress.running) {
                SmpButton(
                    text = stringResource(R.string.smp_toolbar_clear),
                    danger = true,
                    onClick = { showClearConfirm = true },
                )
            }
        }

        // Sequential speed-test progress bar
        if (progress.running && progress.totalCount > 0) {
            val fraction = (progress.currentIndex.toFloat() / progress.totalCount.toFloat()).coerceIn(0f, 1f)
            val progressText = when (progress.phase) {
                StalkerSpeedTester.Phase.STAGE1_PING -> stringResource(
                    R.string.smp_progress_ping,
                    progress.currentIndex,
                    progress.totalCount,
                    progress.currentHost,
                    progress.aliveCount,
                )
                StalkerSpeedTester.Phase.STAGE2_STREAM -> stringResource(
                    R.string.smp_progress_stream,
                    progress.currentIndex,
                    progress.totalCount,
                    progress.currentHost,
                )
                StalkerSpeedTester.Phase.IDLE -> stringResource(R.string.smp_progress_idle)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SmpColors.PanelBg)
                    .border(1.dp, SmpColors.Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = progressText,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = SmpColors.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(SmpColors.CardBg),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(SmpColors.Accent),
                    )
                }
            }
        }

        // Main content: Empty state or Master-Detail split
        if (sortedServers.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SmpColors.PanelBg)
                    .border(1.dp, SmpColors.Border, RoundedCornerShape(16.dp))
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.smp_empty_title),
                        style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        color = SmpColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.smp_empty_body),
                        style = TextStyle(fontSize = 15.sp),
                        color = SmpColors.Muted,
                    )
                }
            }
        } else {
            val nowMs = System.currentTimeMillis()
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Left pane: Server list sorted in real time
                LazyColumn(
                    modifier = Modifier
                        .weight(0.44f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SmpColors.PanelBg)
                        .border(1.dp, SmpColors.Border, RoundedCornerShape(14.dp))
                        .focusGroup(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sortedServers, key = { it.portalKey }) { group ->
                        ServerGroupRow(
                            group = group,
                            selected = selectedServer?.portalKey == group.portalKey,
                            isActiveSource = activeSources.containsKey(group.portalKey),
                            speedLabel = formatServerSpeedSummary(context, group),
                            nowMs = nowMs,
                            onSelect = { selectedPortalKey = group.portalKey },
                        )
                    }
                }

                // Right pane: Selected server details & MAC list
                Box(
                    modifier = Modifier
                        .weight(0.56f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SmpColors.PanelBg)
                        .border(1.dp, SmpColors.Border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    selectedServer?.let { server ->
                        val activeSource = activeSources[server.portalKey]
                        ServerDetailPane(
                            server = server,
                            activeSource = activeSource,
                            speedLabel = formatServerSpeedSummary(context, server),
                            speedTestRunning = progress.running,
                            nowMs = nowMs,
                            onTestSingle = { speedTester.testSingle(server.portalKey) },
                            onActivateSource = { mac ->
                                scope.launch { switcher.activateServerAsSource(server.portalKey, mac) }
                            },
                            onDeleteServer = {
                                scope.launch { store.deleteServer(server.portalKey) }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingLocalFile?.let { file ->
        ImportModeChoiceDialog(
            onMerge = {
                pendingLocalFile = null
                performLocalImport(file, ImportMode.MERGE)
            },
            onReplace = {
                pendingLocalFile = null
                performLocalImport(file, ImportMode.REPLACE)
            },
            onDismiss = { pendingLocalFile = null },
        )
    }

    if (showClearConfirm) {
        ClearPoolConfirmDialog(
            onConfirm = {
                showClearConfirm = false
                scope.launch { store.clearAll() }
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    if (showLanDialog) {
        LanUploadDialog(
            store = store,
            onImported = { s ->
                hostCallbacks.showToast(
                    context.getString(
                        R.string.smp_import_result,
                        s.addedCount,
                        s.updatedCount,
                        s.serverCount,
                        s.uniqueRecords,
                    ),
                )
            },
            onDismiss = { showLanDialog = false },
        )
    }
}

@Composable
private fun ServerGroupRow(
    group: StalkerServerGroup,
    selected: Boolean,
    isActiveSource: Boolean,
    speedLabel: String,
    nowMs: Long,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> SmpColors.FocusBg
        selected -> SmpColors.CardSelectedBg
        else -> SmpColors.CardBg
    }
    val titleColor = if (focused) SmpColors.FocusInk else SmpColors.Text
    val subColor = if (focused) SmpColors.FocusInk.copy(alpha = 0.72f) else SmpColors.Muted
    val borderColor = when {
        focused -> SmpColors.Accent
        selected -> SmpColors.Accent.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    val speedChipColors = when {
        group.downloadKbps != null -> SmpColors.Ok to SmpColors.OkSoft
        group.speedStatus == ServerSpeedStatus.UNREACHABLE -> SmpColors.Danger to SmpColors.DangerSoft
        group.speedStatus == ServerSpeedStatus.TESTING_PING ||
            group.speedStatus == ServerSpeedStatus.TESTING_STREAM -> SmpColors.Warn to SmpColors.WarnSoft
        else -> SmpColors.Muted to SmpColors.PanelBg
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onSelect()
            }
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = group.displayHost,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isActiveSource) {
                SmpChip(
                    text = stringResource(R.string.smp_badge_active_source),
                    textColor = SmpColors.AccentDark,
                    bgColor = SmpColors.Accent,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmpChip(
                text = speedLabel,
                textColor = if (focused) SmpColors.FocusInk else speedChipColors.first,
                bgColor = if (focused) SmpColors.FocusInk.copy(alpha = 0.1f) else speedChipColors.second,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.smp_server_mac_ratio, group.healthyMacCount(nowMs), group.totalMacs),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.smp_server_max_ch, group.maxChannels),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ServerDetailPane(
    server: StalkerServerGroup,
    activeSource: SourceEntity?,
    speedLabel: String,
    speedTestRunning: Boolean,
    nowMs: Long,
    onTestSingle: () -> Unit,
    onActivateSource: (preferredMac: String?) -> Unit,
    onDeleteServer: () -> Unit,
) {
    val activeMac = activeSource?.mac?.let { StalkerJsonImporter.canonicalizeMac(it) ?: it }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = server.displayHost,
                    style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold),
                    color = SmpColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${server.portalKey} · $speedLabel",
                    style = TextStyle(fontSize = 13.sp),
                    color = SmpColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (activeSource != null) {
                SmpChip(
                    text = stringResource(R.string.smp_badge_active_source),
                    textColor = SmpColors.AccentDark,
                    bgColor = SmpColors.Accent,
                )
            }
        }

        server.lastSpeedError?.takeIf { it.isNotBlank() }?.let { err ->
            Text(
                text = err,
                style = TextStyle(fontSize = 12.sp),
                color = SmpColors.Warn,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmpButton(
                text = stringResource(R.string.smp_action_activate_source),
                primary = true,
                onClick = { onActivateSource(null) },
            )
            SmpButton(
                text = stringResource(R.string.smp_action_test_server),
                enabled = !speedTestRunning,
                onClick = onTestSingle,
            )
            SmpButton(
                text = stringResource(R.string.smp_action_delete_server),
                danger = true,
                enabled = !speedTestRunning,
                onClick = onDeleteServer,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(server.macs, key = { it.mac }) { entry ->
                MacEntryRow(
                    entry = entry,
                    isActiveMac = entry.mac == activeMac,
                    nowMs = nowMs,
                    onClick = { onActivateSource(entry.mac) },
                )
            }
        }
    }
}

@Composable
private fun MacEntryRow(
    entry: StalkerMacEntry,
    isActiveMac: Boolean,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val badge = macBadgeOf(context, entry, nowMs)
    val bg = when {
        focused -> SmpColors.FocusBg
        isActiveMac -> SmpColors.CardSelectedBg
        else -> SmpColors.CardBg
    }
    val textColor = if (focused) SmpColors.FocusInk else SmpColors.Text
    val subColor = if (focused) SmpColors.FocusInk.copy(alpha = 0.72f) else SmpColors.Muted
    val connText = entry.maxConnections?.toString() ?: stringResource(R.string.smp_unknown)
    val expText = when {
        entry.isUnlimited -> stringResource(R.string.smp_unlimited)
        !entry.expireDateRaw.isNullOrBlank() -> entry.expireDateRaw
        else -> stringResource(R.string.smp_unknown)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                1.dp,
                if (focused) SmpColors.Accent else SmpColors.Border,
                RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.mac,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActiveMac) {
                    SmpChip(
                        text = stringResource(R.string.smp_badge_active_mac),
                        textColor = SmpColors.AccentDark,
                        bgColor = SmpColors.Accent,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.smp_mac_meta_ch, entry.channels),
                    style = TextStyle(fontSize = 12.sp),
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.smp_mac_meta_conn, connText),
                    style = TextStyle(fontSize = 12.sp),
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.smp_mac_meta_exp, expText),
                    style = TextStyle(fontSize = 12.sp),
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        SmpChip(
            text = badge.label,
            textColor = if (focused) SmpColors.FocusInk else badge.textColor,
            bgColor = if (focused) SmpColors.FocusInk.copy(alpha = 0.1f) else badge.bgColor,
        )
    }
}

@Composable
private fun ImportModeChoiceDialog(
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit,
) {
    val mergeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { mergeFocus.requestFocus() } }

    SmpModalDialog(onDismiss = onDismiss, maxWidthDp = 560) {
        BackHandler { onDismiss() }
        Text(
            text = stringResource(R.string.smp_import_mode_title),
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            color = SmpColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.smp_import_mode_body),
            style = TextStyle(fontSize = 14.sp),
            color = SmpColors.Muted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            SmpButton(text = stringResource(R.string.smp_common_cancel), onClick = onDismiss)
            SmpButton(text = stringResource(R.string.smp_import_mode_replace), danger = true, onClick = onReplace)
            SmpButton(
                text = stringResource(R.string.smp_import_mode_merge),
                primary = true,
                onClick = onMerge,
                modifier = Modifier.focusRequester(mergeFocus),
            )
        }
    }
}

@Composable
private fun ClearPoolConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

    SmpModalDialog(onDismiss = onDismiss, maxWidthDp = 540) {
        BackHandler { onDismiss() }
        Text(
            text = stringResource(R.string.smp_clear_confirm_title),
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            color = SmpColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.smp_clear_confirm_body),
            style = TextStyle(fontSize = 14.sp),
            color = SmpColors.Muted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            SmpButton(
                text = stringResource(R.string.smp_common_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocus),
            )
            SmpButton(
                text = stringResource(R.string.smp_common_confirm),
                danger = true,
                onClick = onConfirm,
            )
        }
    }
}
