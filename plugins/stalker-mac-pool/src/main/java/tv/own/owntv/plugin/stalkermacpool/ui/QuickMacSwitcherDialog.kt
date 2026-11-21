package tv.own.owntv.plugin.stalkermacpool.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.own.owntv.plugin.stalkermacpool.R
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacEntry
import tv.own.owntv.plugin.stalkermacpool.playback.StalkerMacRotationPolicy
import tv.own.owntv.plugin.stalkermacpool.playback.StalkerMacSwitcher

@Composable
fun QuickMacSwitcherDialog(
    match: StalkerMacSwitcher.SourcePoolMatch,
    autoSwitchEnabled: Boolean,
    switching: Boolean,
    onSwitchNext: () -> Unit,
    onSelectMac: (String) -> Unit,
    onToggleAutoSwitch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val nextFocus = remember { FocusRequester() }
    val nowMs = remember(match) { System.currentTimeMillis() }

    val orderedMacs = remember(match) {
        val current = match.server.macs.firstOrNull { it.mac == match.activeMac }
        val rankedAlternates = StalkerMacRotationPolicy.rankCandidates(
            macs = match.server.macs,
            currentMac = match.activeMac,
            nowMs = nowMs,
            includeFallbackInvalid = true,
        )
        listOfNotNull(current) + rankedAlternates
    }

    LaunchedEffect(Unit) {
        runCatching { nextFocus.requestFocus() }
    }

    val autoText = stringResource(if (autoSwitchEnabled) R.string.smp_state_on else R.string.smp_state_off)

    SmpModalDialog(onDismiss = onDismiss, maxWidthDp = 760) {
        BackHandler { onDismiss() }
        Text(
            text = stringResource(R.string.smp_picker_title),
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            color = SmpColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.smp_picker_subtitle,
                match.server.displayHost,
                match.activeMac ?: stringResource(R.string.smp_unknown),
            ),
            style = TextStyle(fontSize = 14.sp),
            color = SmpColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmpButton(
                text = if (switching) {
                    stringResource(R.string.smp_switching_mac)
                } else {
                    stringResource(R.string.smp_picker_next_btn, match.healthyAlternateCount)
                },
                primary = true,
                enabled = !switching && match.alternateCount > 0,
                onClick = onSwitchNext,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(nextFocus),
            )
            SmpButton(
                text = stringResource(R.string.smp_picker_auto_toggle, autoText),
                active = autoSwitchEnabled,
                onClick = onToggleAutoSwitch,
            )
            SmpButton(
                text = stringResource(R.string.smp_common_close),
                onClick = onDismiss,
            )
        }

        if (match.alternateCount == 0 && orderedMacs.isEmpty()) {
            Text(
                text = stringResource(R.string.smp_picker_empty),
                style = TextStyle(fontSize = 14.sp),
                color = SmpColors.Muted,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(orderedMacs, key = { it.mac }) { entry ->
                    QuickMacPickerRow(
                        entry = entry,
                        isCurrent = entry.mac == match.activeMac,
                        nowMs = nowMs,
                        enabled = !switching,
                        onSelect = { onSelectMac(entry.mac) },
                        statusBadge = macBadgeOf(context, entry, nowMs),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickMacPickerRow(
    entry: StalkerMacEntry,
    isCurrent: Boolean,
    nowMs: Long,
    enabled: Boolean,
    statusBadge: MacBadgeInfo,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> SmpColors.FocusBg
        isCurrent -> SmpColors.CardSelectedBg
        else -> SmpColors.CardBg
    }
    val fg = if (focused) SmpColors.FocusInk else SmpColors.Text
    val sub = if (focused) SmpColors.FocusInk.copy(alpha = 0.72f) else SmpColors.Muted
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
            .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.mac,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrent) {
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
                    color = sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.smp_mac_meta_conn, connText),
                    style = TextStyle(fontSize = 12.sp),
                    color = sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.smp_mac_meta_exp, expText),
                    style = TextStyle(fontSize = 12.sp),
                    color = sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        SmpChip(
            text = statusBadge.label,
            textColor = if (focused) SmpColors.FocusInk else statusBadge.textColor,
            bgColor = if (focused) SmpColors.FocusInk.copy(alpha = 0.1f) else statusBadge.bgColor,
        )
    }
}
