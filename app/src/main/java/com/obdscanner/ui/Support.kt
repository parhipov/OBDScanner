package com.obdscanner.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.obdscanner.screen.Support

private val Heart = Color(0xFFFF6B81)

/** The line on top of the first screen ([Support]); a tap opens the dialog with the bank's link. Hidden without a link. */
@Composable
fun SupportLine() {
    if (Support.URL.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Card(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = Heart.copy(alpha = 0.12f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, null, tint = Heart)
            Text(Support.LABEL, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
    if (open) SupportDialog { open = false }
}

@Composable
private fun SupportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Favorite, null, tint = Heart) },
        title = { Text(Support.LABEL) },
        text = {
            Column {
                Text(Support.TEXT, style = MaterialTheme.typography.bodyMedium)
                Text(Support.URL, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Support.URL)))
                } catch (_: ActivityNotFoundException) {
                    toast(ctx, Support.NO_BROWSER)
                }
            }) { Text(Support.OPEN) }
        },
        dismissButton = {
            TextButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(Support.LABEL, Support.URL))
                // Android 13+ shows its own "copied" note.
                if (Build.VERSION.SDK_INT < 33) toast(ctx, Support.COPIED)
            }) { Text(Support.COPY) }
        },
    )
}
