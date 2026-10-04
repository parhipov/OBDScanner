package com.obdscanner.screen

import com.obdscanner.obd.Dtc
import com.obdscanner.obd.DtcAnatomy
import com.obdscanner.obd.DtcDb
import com.obdscanner.obd.DtcKind
import com.obdscanner.obd.DtcText
import com.obdscanner.tr
import com.obdscanner.util.format

/**
 * What the app knows about one trouble code, for the dialog on a tap (like the main screen card help):
 * [label] as the dialog title, [title] — what the code means, then [sections] (heading → text).
 */
class DtcHelpView(
    val label: String,
    val title: String,
    /** "Only an English description is available" and the like, under the title. */
    val untranslated: String?,
    val module: String,
    val sections: List<Pair<String, String>>,
    /** Where the description comes from, at the bottom. */
    val source: String?,
) {
    companion object {
        val CLOSE = tr("Закрыть", "Close")
    }
}

object DtcHelp {
    fun build(d: DtcRef, family: String?): DtcHelpView {
        val entry = DtcDb.find(d.code, family)
        val title = Dtc.describe(d.code, family)
        val sections = mutableListOf<Pair<String, String>>()
        // Descriptions are English only (tools/dtc/SCHEMA.md): say so rather than surprise.
        entry?.desc?.let { dsc -> dsc.text?.let { sections += (tr("Подробнее", "Details") + if (dsc.untranslated) tr(" (на английском)", "") else "") to it } }
        bullets(entry?.causes)?.let { sections += tr("Частые причины", "Common causes") to it }
        bullets(entry?.symptoms)?.let { sections += tr("Симптомы", "Symptoms") to it }

        if (d.ftb != null) {
            val meaning = DtcDb.failureType(d.ftb, d.gmFtb)?.text
            sections += tr("Тип отказа %02X", "Failure type %02X").format(d.ftb) to
                (meaning ?: if (d.ftb == 0) tr("Без уточнения.", "No further detail.") else tr("Нет в таблице типов отказа.", "Not in the failure type table."))
        }

        val state = listOfNotNull(d.kind?.let(::kindText), d.state?.let { tr("Состояние: $it.", "Status: $it.") })
        if (state.isNotEmpty()) sections += tr("Состояние", "Status") to state.joinToString("\n")

        val anatomy = listOfNotNull(
            DtcAnatomy.system(d.code)?.let { tr("Система: $it.", "System: $it.") },
            DtcAnatomy.subsystem(d.code)?.let { tr("Подсистема: $it.", "Subsystem: $it.") },
            DtcAnatomy.owner(d.code),
        )
        sections += tr("Что видно по номеру", "What the number tells") to anatomy.joinToString("\n")

        // A manufacturer code without this make's text: what other makes mean by it, clearly labelled.
        if (entry == null && !DtcAnatomy.isGeneric(d.code)) {
            val others = DtcDb.others(d.code)
            if (others.isNotEmpty()) sections += tr("У других марок этот код значит", "On other makes this code means") to
                others.joinToString("\n") { "${it.set.uppercase()}: ${it.title.text}" }
        }

        return DtcHelpView(
            label = d.label,
            title = title,
            untranslated = if (entry?.title?.untranslated == true) tr("Описание есть только на английском.", "Only a Russian description is available.") else null,
            module = d.module,
            sections = sections,
            source = entry?.takeIf { it.title.text == title }?.let { tr("Источник описания: ${it.source}", "Description source: ${it.source}") },
        )
    }

    private fun bullets(list: List<DtcText>?): String? =
        list?.mapNotNull { it.text }?.takeIf { it.isNotEmpty() }?.joinToString("\n") { "• $it" }

    private fun kindText(k: DtcKind) = when (k) {
        DtcKind.STORED -> tr("Сохранённая: неисправность повторилась, из-за неё может гореть Check.",
            "Stored: the fault came back; it can turn the Check Engine light on.")
        DtcKind.PENDING -> tr("Ожидающая: блок заметил неисправность один раз. Повторится в следующей поездке — станет сохранённой. Check пока не горит.",
            "Pending: the ECU saw the fault once. If it happens again on the next drive, it becomes stored. No Check Engine light yet.")
        DtcKind.PERMANENT -> tr("Постоянная: сбросом не стирается. Блок удалит её сам, когда тест пройдёт без ошибки.",
            "Permanent: clearing doesn't erase it. The ECU removes it by itself once the test passes.")
    }
}
