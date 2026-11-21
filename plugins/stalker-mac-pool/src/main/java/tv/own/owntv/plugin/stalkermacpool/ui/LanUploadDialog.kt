package tv.own.owntv.plugin.stalkermacpool.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.plugin.stalkermacpool.R
import tv.own.owntv.plugin.stalkermacpool.data.StalkerMacPoolStore
import tv.own.owntv.plugin.stalkermacpool.model.ImportSummary
import tv.own.owntv.plugin.stalkermacpool.network.LanJsonUploadServer

@Composable
internal fun LanUploadDialog(
    store: StalkerMacPoolStore,
    onImported: (ImportSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var startError by remember { mutableStateOf<String?>(null) }
    var lastSummary by remember { mutableStateOf<ImportSummary?>(null) }

    DisposableEffect(store) {
        val server = LanJsonUploadServer()
        val url = server.start(
            onJsonUploaded = { jsonText, mode ->
                val summary = store.importJson(jsonText, mode)
                withContext(Dispatchers.Main) {
                    lastSummary = summary
                    onImported(summary)
                }
                summary
            },
            onError = { msg -> startError = msg },
        )
        serverUrl = url
        qrBitmap = url?.let { LanJsonUploadServer.generateQrBitmap(it, 300) }
        onDispose { server.stop() }
    }

    LaunchedEffect(Unit) {
        runCatching { closeFocus.requestFocus() }
    }

    SmpModalDialog(onDismiss = onDismiss, maxWidthDp = 700) {
        BackHandler { onDismiss() }
        Text(
            text = stringResource(R.string.smp_lan_title),
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            color = SmpColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.smp_lan_subtitle),
            style = TextStyle(fontSize = 15.sp),
            color = SmpColors.Muted,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val bmp = qrBitmap
            if (bmp != null) {
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.smp_lan_title),
                        modifier = Modifier.size(176.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    startError != null -> Text(
                        text = stringResource(R.string.smp_lan_failed, startError.orEmpty()),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        color = SmpColors.Danger,
                    )
                    serverUrl != null -> {
                        Text(
                            text = serverUrl.orEmpty(),
                            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                            color = SmpColors.Accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.smp_lan_hint),
                            style = TextStyle(fontSize = 14.sp),
                            color = SmpColors.Muted,
                        )
                    }
                    else -> Text(
                        text = stringResource(R.string.smp_lan_starting),
                        style = TextStyle(fontSize = 15.sp),
                        color = SmpColors.Muted,
                    )
                }
                lastSummary?.let { s ->
                    Text(
                        text = stringResource(R.string.smp_lan_uploaded, s.serverCount, s.uniqueRecords),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        color = SmpColors.Ok,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Spacer(Modifier.width(8.dp))
            SmpButton(
                text = stringResource(R.string.smp_common_close),
                onClick = onDismiss,
                primary = true,
                modifier = Modifier.focusRequester(closeFocus),
            )
        }
    }
}
