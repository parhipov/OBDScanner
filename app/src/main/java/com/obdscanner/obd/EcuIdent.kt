package com.obdscanner.obd

import com.obdscanner.car.CarFamily
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
 * The standard place to look for modules: the OBD ids (7E0–7E7) plus the 11-bit ids where Toyota and
 * Hyundai/Kia are said to keep ABS, airbags, dash and climate (all answer on +8). Unconfirmed guesses — the
 * probe is only a tester present / VIN read, so a wrong one costs ~1 s. A make adds its own or replaces the
 * list in its database file (`modules`, tools/cars/SCHEMA.md).
 */
object ObdModules {
    val candidates: List<Pair<Int, Int>> =
        ((0x7E0..0x7E7) + listOf(0x7A0, 0x7A1, 0x7B0, 0x7C0, 0x7C4, 0x7C6, 0x7D0, 0x7D1, 0x7D2)).map { it to it + 8 }

    /** Tester present (UDS and KWP on CAN), then the UDS VIN, then the KWP VIN. */
    val PROBES = listOf("3E00", "22F190", "1A90")

    /** Where to look for modules, what to ask them and what to call them (module search, report, scan screen). */
    class Addressing(val tag: String, val candidates: List<Pair<Int, Int>>, val probes: List<String>, val name: (Int) -> String)

    /**
     * The make's own list ([com.obdscanner.car.ModuleSearch.replace]), or the standard one plus the make's
     * extras and every module the make's database requests talk to; [blocks] — the car's named blocks.
     */
    fun addressing(family: CarFamily?, tag: String, blocks: List<Pair<Int, Int>> = emptyList()): Addressing {
        val m = family?.modules
        if (m != null && m.replace) {
            return Addressing(tag, (m.addresses + blocks).distinctBy { it.first }, m.probes ?: PROBES) { m.names[it] ?: "%03X".format(it) }
        }
        val fromDb = family?.commands.orEmpty().map { it.req to it.resp }.filter { it.first !in 0x7E0..0x7E7 }
        return Addressing(tag, (candidates + m?.addresses.orEmpty() + fromDb + blocks).distinctBy { it.first }, m?.probes ?: PROBES) {
            m?.names?.get(it) ?: name(it)
        }
    }

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
