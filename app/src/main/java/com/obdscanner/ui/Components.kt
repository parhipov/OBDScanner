package com.obdscanner.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.obdscanner.obd.Reading
import com.obdscanner.screen.Level
import com.obdscanner.screen.rowSub
import com.obdscanner.screen.tileRange
import com.obdscanner.screen.trimLevel
import com.obdscanner.tr
import java.io.File
import kotlin.math.abs

val Good = Color(0xFF4CAF50)
val Warn = Color(0xFFFFB300)
val Bad = Color(0xFFE53935)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7AB8FF),
            secondary = Color(0xFFB0C4DE),
            background = Color(0xFF0E1116),
            surface = Color(0xFF0E1116),
            surfaceVariant = Color(0xFF1B2029),
        ),
        content = content,
    )
}

/** Big dashboard tile. [note] replaces the min/max line (e.g. to mark a sample value). */
@Composable
fun ValueTile(label: String, r: Reading?, modifier: Modifier = Modifier, color: Color? = null, container: Color? = null,
              note: String? = null, onClick: () -> Unit = {}) {
    Card(
        onClick = onClick,
        modifier = modifier.padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = container ?: MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    r?.display() ?: "—",
                    fontSize = if ((r?.display()?.length ?: 1) > 8) 18.sp else 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                if (r?.value != null && r.unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(r.unit, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
            // Always reserve the min/max line so tiles in a grid row keep the same height.
            val range = note ?: r?.tileRange() ?: " "
            Text(range, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp))
}

/** Compact "name ........ value unit" row. */
@Composable
fun ValueRow(name: String, value: String, unit: String = "", sub: String? = null, color: Color? = null) {
    // A long text value next to the name squeezes the name column to nothing: stack them instead.
    if (value.length > 16) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(value + if (unit.isNotEmpty()) " $unit" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = color ?: MaterialTheme.colorScheme.onSurface)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface, fontFamily = if (value.length > 12 && value.all { it.isLetterOrDigit() && it.code < 128 || it == ' ' }) FontFamily.Monospace else null)
        if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ReadingRow(r: Reading, color: Color? = null) {
    ValueRow(r.name, r.display(), if (r.value != null) r.unit else "", r.rowSub(), color)
}

/** Horizontal bar centered at zero, for fuel trims (±25%). */
@Composable
fun TrimBar(value: Double?, limit: Double = 25.0) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(10.dp).clip(RoundedCornerShape(5.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (value != null) {
            val frac = (abs(value) / limit).coerceIn(0.0, 1.0).toFloat() / 2f
            val c = trimColor(value) ?: Good
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                if (value < 0) {
                    Spacer(Modifier.weight(0.5f - frac + 0.0001f))
                    Box(Modifier.weight(frac + 0.0001f).fillMaxHeight().background(c))
                    Spacer(Modifier.weight(0.5f))
                } else {
                    Spacer(Modifier.weight(0.5f))
                    Box(Modifier.weight(frac + 0.0001f).fillMaxHeight().background(c))
                    Spacer(Modifier.weight(0.5f - frac + 0.0001f))
                }
            }
        }
        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
    }
}

fun trimColor(v: Double?): Color? = when (trimLevel(v)) {
    Level.BAD -> Bad
    Level.WARN -> Warn
    Level.GOOD -> Good
    else -> null
}

@Composable
fun Hint(text: String, color: Color = Warn) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
    ) {
        Text(text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
fun Gap() = Spacer(Modifier.height(8.dp))

val spaced = Arrangement.spacedBy(8.dp)

/** The project's mailbox for sessions: mail apps put it into "To", messengers ignore it. */
const val SESSION_EMAIL = "obdscanner@internet.ru"

fun shareFile(context: Context, file: File) = shareFiles(context, listOf(file))

/** Session zips in one letter: one attachment as before, several as SEND_MULTIPLE. */
fun shareFiles(context: Context, files: List<File>) {
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
    val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/zip"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SESSION_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "OBD Scanner ${com.obdscanner.BuildConfig.VERSION_NAME}: ${files.joinToString(", ") { it.nameWithoutExtension }}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val title = if (files.size == 1) tr("Отправить сессию", "Send session") else tr("Отправить сессии", "Send sessions")
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
