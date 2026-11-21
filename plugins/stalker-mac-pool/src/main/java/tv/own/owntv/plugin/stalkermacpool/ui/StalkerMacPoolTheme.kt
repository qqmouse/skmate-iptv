package tv.own.owntv.plugin.stalkermacpool.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import tv.own.owntv.plugin.stalkermacpool.R
import tv.own.owntv.plugin.stalkermacpool.model.ServerSortMode
import tv.own.owntv.plugin.stalkermacpool.model.ServerSpeedStatus
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacEntry
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacStatus
import tv.own.owntv.plugin.stalkermacpool.model.StalkerServerGroup
import java.util.Locale

internal object SmpColors {
    val Scrim = Color(0xCC06090D)
    val DialogBg = Color(0xFF101720)
    val PanelBg = Color(0xCC121922)
    val CardBg = Color(0xFF18222E)
    val CardSelectedBg = Color(0xFF1D2D38)
    val Border = Color(0x339FB4C8)
    val Accent = Color(0xFF2DD4BF)
    val AccentDark = Color(0xFF07221E)
    val AccentSoft = Color(0x332DD4BF)
    val Text = Color(0xFFF3F7FA)
    val Muted = Color(0xFF9CB0C4)
    val Warn = Color(0xFFF59E0B)
    val WarnSoft = Color(0x33F59E0B)
    val Danger = Color(0xFFF87171)
    val DangerSoft = Color(0x33F87171)
    val Ok = Color(0xFF34D399)
    val OkSoft = Color(0x3334D399)
    val FocusBg = Color(0xFFFFFFFF)
    val FocusInk = Color(0xFF0B0F14)
}

@Composable
internal fun SmpButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> SmpColors.FocusBg
        primary -> SmpColors.AccentSoft
        danger -> SmpColors.DangerSoft
        active -> SmpColors.OkSoft
        else -> SmpColors.CardBg
    }
    val fg = when {
        focused -> SmpColors.FocusInk
        !enabled -> SmpColors.Muted.copy(alpha = 0.5f)
        primary -> SmpColors.Accent
        danger -> SmpColors.Danger
        active -> SmpColors.Ok
        else -> SmpColors.Text
    }
    val borderColor = when {
        focused -> SmpColors.Accent
        primary -> SmpColors.Accent.copy(alpha = 0.45f)
        danger -> SmpColors.Danger.copy(alpha = 0.4f)
        else -> SmpColors.Border
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.focusable(false)
                },
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SmpChip(
    text: String,
    textColor: Color,
    bgColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SmpModalDialog(
    onDismiss: () -> Unit,
    maxWidthDp: Int = 680,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SmpColors.Scrim)
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 380.dp, max = maxWidthDp.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(SmpColors.DialogBg)
                    .border(1.dp, SmpColors.Border, RoundedCornerShape(18.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

internal fun formatThroughput(kbps: Double): String = if (kbps >= 1024.0) {
    String.format(Locale.US, "%.2f MB/s", kbps / 1024.0)
} else {
    String.format(Locale.US, "%.0f KB/s", kbps)
}

internal fun formatServerSpeedSummary(context: Context, group: StalkerServerGroup): String = when (group.speedStatus) {
    ServerSpeedStatus.UNTESTED -> context.getString(R.string.smp_speed_untested)
    ServerSpeedStatus.TESTING_PING -> context.getString(R.string.smp_speed_pinging)
    ServerSpeedStatus.TESTING_STREAM -> context.getString(R.string.smp_speed_streaming)
    ServerSpeedStatus.UNREACHABLE -> context.getString(R.string.smp_speed_unreachable)
    ServerSpeedStatus.OK -> {
        val dl = group.downloadKbps
        val ping = group.pingMs
        when {
            dl != null && ping != null -> context.getString(R.string.smp_speed_full, formatThroughput(dl), ping)
            dl != null -> formatThroughput(dl)
            ping != null -> context.getString(R.string.smp_speed_ping_only, ping)
            else -> context.getString(R.string.smp_speed_untested)
        }
    }
}

internal fun formatSortModeLabel(context: Context, mode: ServerSortMode): String = when (mode) {
    ServerSortMode.SPEED_DESC -> context.getString(R.string.smp_sort_speed)
    ServerSortMode.PING_ASC -> context.getString(R.string.smp_sort_ping)
    ServerSortMode.MAC_COUNT_DESC -> context.getString(R.string.smp_sort_mac_count)
    ServerSortMode.CHANNELS_DESC -> context.getString(R.string.smp_sort_channels)
}

internal data class MacBadgeInfo(
    val label: String,
    val textColor: Color,
    val bgColor: Color,
)

internal fun macBadgeOf(context: Context, entry: StalkerMacEntry, nowMs: Long): MacBadgeInfo = when {
    entry.isExpired || entry.status == StalkerMacStatus.EXPIRED -> MacBadgeInfo(
        context.getString(R.string.smp_mac_status_expired),
        SmpColors.Danger,
        SmpColors.DangerSoft,
    )
    entry.status == StalkerMacStatus.AUTH_FAILED -> MacBadgeInfo(
        context.getString(R.string.smp_mac_status_failed),
        SmpColors.Danger,
        SmpColors.DangerSoft,
    )
    entry.isInCooldown(nowMs) -> {
        val minsLeft = (((entry.cooldownUntilMs - nowMs) + 59_999L) / 60_000L).coerceAtLeast(1L)
        MacBadgeInfo(
            context.getString(R.string.smp_mac_status_cooldown, minsLeft),
            SmpColors.Warn,
            SmpColors.WarnSoft,
        )
    }
    entry.status == StalkerMacStatus.OK -> MacBadgeInfo(
        context.getString(R.string.smp_mac_status_ok),
        SmpColors.Ok,
        SmpColors.OkSoft,
    )
    else -> MacBadgeInfo(
        context.getString(R.string.smp_mac_status_untested),
        SmpColors.Muted,
        SmpColors.CardBg,
    )
}
