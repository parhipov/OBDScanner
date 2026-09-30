package com.obdscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.obd.Dtc
import com.obdscanner.obd.DtcAnatomy
import com.obdscanner.obd.DtcDb
import com.obdscanner.obd.DtcKind
import com.obdscanner.obd.DtcText
import com.obdscanner.tr

/**
 * What the app knows about one trouble code, for the dialog on a tap (like the main screen card help).
 * [ftb] — failure type byte (GM \$A9 symptom / UDS FTB), null when the protocol has none;
 * [state] — the status as text ("active, history"); [kind] — for Mode 03/07/0A codes.
 */
data class DtcShown(
    val code: String,
    val module: String,
    val ftb: Int? = null,
    val gmFtb: Boolean = false,
    val state: String? = null,
    val kind: DtcKind? = null,
    /** The code as listed, "B1517 03" or "P0301 (VAG 16685)". */
    val label: String = code,
)

@Composable
fun DtcHelpDialog(d: DtcShown, family: String?, onDismiss: () -> Unit) {
    val entry = DtcDb.find(d.code, family)
    val title = Dtc.describe(d.code, family)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Закрыть", "Close")) } },
        title = { Text(d.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (entry?.title?.untranslated == true) Muted(tr("Описание есть только на английском.", "Only a Russian description is available."))
                Muted(d.module)

                // Descriptions are English only (tools/dtc/SCHEMA.md): say so rather than surprise.
                entry?.desc?.let { dsc -> dsc.text?.let { Section(tr("Подробнее", "Details") + if (dsc.untranslated) tr(" (на английском)", "") else "", it) } }
                bullets(entry?.causes)?.let { Section(tr("Частые причины (сначала вероятные)", "Common causes (most likely first)"), it) }
                bullets(entry?.symptoms)?.let { Section(tr("Симптомы", "Symptoms"), it) }

                if (d.ftb != null) {
                    val meaning = DtcDb.failureType(d.ftb, d.gmFtb)?.text
                    Section(tr("Тип отказа %02X", "Failure type %02X").format(d.ftb),
                        meaning ?: if (d.ftb == 0) tr("Без уточнения.", "No further detail.") else tr("Нет в таблице типов отказа.", "Not in the failure type table."))
                }

                val state = listOfNotNull(d.kind?.let(::kindText), d.state?.let { tr("Состояние: $it.", "Status: $it.") })
                if (state.isNotEmpty()) Section(tr("Состояние", "Status"), state.joinToString("\n"))

                val anatomy = listOfNotNull(
                    DtcAnatomy.system(d.code)?.let { tr("Система: $it.", "System: $it.") },
                    DtcAnatomy.subsystem(d.code)?.let { tr("Подсистема: $it.", "Subsystem: $it.") },
                    DtcAnatomy.owner(d.code),
                )
                Section(tr("Что говорит сам код", "What the code itself says"), anatomy.joinToString("\n"))

                // A manufacturer code without this make's text: what other makes mean by it, clearly labelled.
                if (entry == null && !DtcAnatomy.isGeneric(d.code)) {
                    val others = DtcDb.others(d.code)
                    if (others.isNotEmpty()) Section(tr("У других марок этот код значит", "On other makes this code means"),
                        others.joinToString("\n") { "${it.set.uppercase()}: ${it.title.text}" })
                }

                entry?.takeIf { it.title.text == title }?.let { Muted(tr("Источник описания: ${it.source}", "Description source: ${it.source}")) }
            }
        },
    )
}

private fun bullets(list: List<DtcText>?): String? =
    list?.mapNotNull { it.text }?.takeIf { it.isNotEmpty() }?.joinToString("\n") { "• $it" }

private fun kindText(k: DtcKind) = when (k) {
    DtcKind.STORED -> tr("Сохранённая: неисправность подтверждена, по ней может гореть Check.",
        "Stored: the fault is confirmed; it may turn the Check Engine light on.")
    DtcKind.PENDING -> tr("Ожидающая: ЭБУ увидел неисправность один раз и ждёт подтверждения в следующей поездке. Check пока не горит.",
        "Pending: the ECU saw the fault once and waits for the next drive to confirm it. No Check Engine light yet.")
    DtcKind.PERMANENT -> tr("Постоянная: сбросом не стирается. ЭБУ удалит её сам, когда его тест пройдёт без ошибки.",
        "Permanent: a clear does not erase it. The ECU removes it itself once its test passes.")
}

@Composable
private fun Section(title: String, text: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
