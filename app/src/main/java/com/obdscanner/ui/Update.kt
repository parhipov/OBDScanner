package com.obdscanner.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obdscanner.Updates
import com.obdscanner.screen.Support
import com.obdscanner.tr

/** On top of the first screen while GitHub has a newer release ([Updates]); a tap opens its page: what's new and the APK. */
@Composable
fun UpdateLine(version: String?) {
    if (version == null) return
    val ctx = LocalContext.current
    Card(
        onClick = {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updates.page(version))))
            } catch (_: ActivityNotFoundException) {
                toast(ctx, Support.NO_BROWSER)
            }
        },
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = Good.copy(alpha = 0.15f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Refresh, null, tint = Good)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(tr("Вышла версия $version", "Version $version is out"), style = MaterialTheme.typography.bodyLarge)
                Text(tr("Что нового и APK — на GitHub", "What's new and the APK — on GitHub"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Info → About: the [Updates] check on or off, and what it sends. */
@Composable
fun UpdateCheckLine(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = onChange)
        Text(tr("Проверять новые версии", "Check for new versions"), style = MaterialTheme.typography.bodyLarge)
    }
    Muted(tr("При запуске приложение узнаёт у GitHub номер последней версии. О телефоне и машине ничего не отправляется.",
        "On start the app asks GitHub for the latest version number. Nothing about the phone or the car is sent."))
}
