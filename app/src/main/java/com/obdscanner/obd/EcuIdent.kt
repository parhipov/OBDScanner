package com.obdscanner.obd

import com.obdscanner.tr

/**
 * Identification of a non-GM ECU: UDS \$22 F1xx (ISO 14229, VW names in brackets) and KWP2000
 * \$1A (ISO 14230-3 identification options). Read only. Whatever service the ECU rejects (NRC 11)
 * is skipped, so a UDS ECU costs one \$1A request and vice versa.
 */
object EcuIdent {
    class Item(val service: String, val did: Int, val name: String)

    val ALL = listOf(
        Item("22", 0xF187, tr("Номер детали (VW)", "Part number (VW)")),
        Item("22", 0xF189, tr("Версия ПО", "Software version")),
        Item("22", 0xF191, tr("Номер железа", "Hardware number")),
        Item("22", 0xF1A3, tr("Версия железа", "Hardware version")),
        Item("22", 0xF197, tr("Название системы", "System name")),
        Item("22", 0xF18C, tr("Серийный номер", "Serial number")),
        Item("22", 0xF190, "VIN"),
        Item("22", 0xF199, tr("Дата программирования", "Programming date")),
        Item("22", 0xF18A, tr("Поставщик", "Supplier")),
        Item("22", 0xF19E, tr("ODX-файл (ASAM)", "ODX file (ASAM)")),
        Item("22", 0xF1A2, tr("Версия ODX-файла", "ODX file version")),
        Item("22", 0xF1AA, tr("Имя блока в диагностике (VW)", "Workshop system name (VW)")),
        Item("22", 0x0600, tr("Кодирование (VW)", "Coding (VW)")),
        Item("1A", 0x87, tr("Номер детали производителя", "Manufacturer part number")),
        Item("1A", 0x88, tr("Номер ПО", "Software number")),
        Item("1A", 0x89, tr("Версия ПО", "Software version")),
        Item("1A", 0x8A, tr("Поставщик", "Supplier")),
        Item("1A", 0x8C, tr("Серийный номер", "Serial number")),
        Item("1A", 0x90, "VIN"),
        Item("1A", 0x91, tr("Номер железа", "Hardware number")),
        Item("1A", 0x92, tr("Номер железа поставщика", "Supplier hardware number")),
        Item("1A", 0x94, tr("Номер ПО поставщика", "Supplier software number")),
        Item("1A", 0x97, tr("Название системы", "System name")),
        Item("1A", 0x99, tr("Дата программирования", "Programming date")),
        Item("1A", 0x9B, tr("Идентификация VAG (номер, ПО, кодирование)", "VAG identification (part no., SW, coding)")),
        Item("1A", 0x86, tr("Идентификация производителя", "Manufacturer identification")),
    )
}

/**
 * Any make without its own address list: the standard OBD ids (7E0–7E7) plus the 11-bit ids where
 * Toyota and Hyundai/Kia are said to keep ABS, airbags, dash and climate (all answer on +8).
 * Unconfirmed guesses — the probe is only a tester present / VIN read, so a wrong one costs ~1 s.
 */
object ObdModules {
    val candidates: List<Pair<Int, Int>> =
        ((0x7E0..0x7E7) + listOf(0x7A0, 0x7A1, 0x7B0, 0x7C0, 0x7C4, 0x7C6, 0x7D0, 0x7D1, 0x7D2)).map { it to it + 8 }

    /** Tester present (UDS and KWP on CAN), then the UDS VIN, then the KWP VIN. */
    val PROBES = listOf("3E00", "22F190", "1A90")

    /** Renault-platform modules answer the KWP identification 21 80 and don't need 3E (pyren never sends it). */
    val PROBES_RENAULT = listOf("2180", "22F190", "3E00")

    /**
     * Extra modules per family, (request, reply). Renault platform (also Nissan and Lada Vesta/XRAY/Largus):
     * body and chassis modules answer on +0x20 (pyren / ddt4all address tables); Haval/GWM on +0x40;
     * the rest on +8 — addresses seen answering on real cars in openpilot's FW queries (opendbc, MIT) and OVMS.
     */
    private val EXTRA: Map<String, List<Pair<Int, Int>>> = run {
        val renault = listOf(0x740, 0x742, 0x743, 0x744, 0x745, 0x748, 0x752, 0x758, 0x79B, 0x707).map { it to it + 0x20 }
        mapOf(
            "renault" to renault,
            "nissan" to renault,
            "lada" to renault,
            "toyota" to listOf(0x700, 0x701, 0x780, 0x791).map { it to it + 8 },
            "hyundai" to listOf(0x7D4, 0x7B3, 0x7B1, 0x7B7, 0x730, 0x7C5, 0x794, 0x770).map { it to it + 8 },
            "ford" to listOf(0x706, 0x726, 0x730, 0x732, 0x760, 0x764).map { it to it + 8 },
            "mazda" to listOf(0x706, 0x730, 0x732, 0x760, 0x764).map { it to it + 8 },
            "subaru" to listOf(0x7A2, 0x7A3, 0x746, 0x787).map { it to it + 8 },
            "china" to listOf(0x763, 0x782, 0x787, 0x78B).map { it to it + 0x40 } +
                listOf(0x710, 0x724, 0x740, 0x745, 0x750, 0x760, 0x781, 0x784, 0x785).map { it to it + 8 },
        )
    }

    /** The shared list, the family's extra modules and every module the family's database requests talk to. */
    fun candidates(family: String?, fromDb: List<Pair<Int, Int>>): List<Pair<Int, Int>> =
        (candidates + EXTRA[family].orEmpty() + fromDb).distinctBy { it.first }

    fun probes(family: String?) = if (family in setOf("renault", "nissan", "lada")) PROBES_RENAULT else PROBES

    fun name(req: Int, family: String?): String = if (family in setOf("renault", "nissan", "lada")) when (req) {
        0x740 -> "ABS / ESP (740)"
        0x742 -> tr("Электроусилитель руля (742)", "Power steering (742)")
        0x743 -> tr("Приборная панель (743)", "Instrument cluster (743)")
        0x744 -> tr("Климат (744)", "Climate (744)")
        0x745 -> tr("Кузовной блок UCH/BCM (745)", "Body module UCH/BCM (745)")
        0x748 -> tr("Полный привод 4WD (748)", "4WD (748)")
        0x752 -> tr("Подушки безопасности (752)", "Airbags (752)")
        0x758 -> tr("Давление в шинах TPMS (758)", "Tyre pressure TPMS (758)")
        0x79B -> tr("Батарея электромобиля (79B)", "EV battery (79B)")
        else -> name(req)
    } else if (family == "ford") when (req) {
        // Seen on a Ford 2017 (Vsevolozhsk): 720 answers the odometer, 760 the four wheel speeds.
        0x720 -> tr("Приборная панель (720)", "Instrument cluster (720)")
        0x760 -> "ABS (760)"
        else -> name(req)
    } else name(req)

    /**
     * K-line module by its ISO 14230 / SAE J2178 physical address: 10–17 engine, 18–1F transmission
     * (Hyundai Coupe 2003: engine 11, automatic 18). Others — just the address.
     */
    fun klineName(addr: Int): String = when (addr) {
        in 0x10..0x17 -> tr("Двигатель (%02X)", "Engine (%02X)").format(addr)
        in 0x18..0x1F -> tr("КПП (%02X)", "Transmission (%02X)").format(addr)
        else -> tr("ЭБУ %02X", "ECU %02X").format(addr)
    }

    fun name(req: Int): String = when (req) {
        in 0x01..0xEF -> klineName(req)
        0x7E0 -> tr("Двигатель (7E0)", "Engine (7E0)")
        0x7E1 -> tr("КПП (7E1)", "Transmission (7E1)")
        0x7B0 -> tr("ЭБУ 7B0 (у Toyota ABS/VSC)", "ECU 7B0 (ABS/VSC on a Toyota)")
        0x7C0 -> tr("ЭБУ 7C0 (у Toyota приборка)", "ECU 7C0 (cluster on a Toyota)")
        0x7C4 -> tr("ЭБУ 7C4 (у Toyota климат)", "ECU 7C4 (A/C on a Toyota)")
        0x7D1 -> tr("ЭБУ 7D1 (у Hyundai/Kia ABS)", "ECU 7D1 (ABS on a Hyundai/Kia)")
        0x7D2 -> tr("ЭБУ 7D2 (у Hyundai/Kia подушки)", "ECU 7D2 (airbags on a Hyundai/Kia)")
        else -> tr("ЭБУ %03X", "ECU %03X").format(req)
    }
}
