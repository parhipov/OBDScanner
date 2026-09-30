package com.obdscanner.obd

import com.obdscanner.car.CarDb
import com.obdscanner.tr

/**
 * Diagnostic family of the make, by the VIN's manufacturer code (WMI) from the car database
 * (assets/cars/<id>.json `brands`). GM and VAG have their own module steps; the rest get the standard
 * OBD addresses with UDS/KWP identification and DTCs, plus the family's manufacturer parameters.
 */
enum class Make(val id: String, val title: String) {
    GM("gm", "GM"),
    VAG("vag", "Volkswagen (VAG)"),
    TOYOTA("toyota", "Toyota / Lexus"),
    HYUNDAI("hyundai", "Hyundai / Kia"),
    LADA("lada", "Lada"),
    RENAULT("renault", "Renault"),
    NISSAN("nissan", "Nissan"),
    FORD("ford", "Ford"),
    MAZDA("mazda", "Mazda"),
    BMW("bmw", "BMW / Mini"),
    MERCEDES("mercedes", "Mercedes-Benz"),
    HONDA("honda", "Honda"),
    MITSUBISHI("mitsubishi", "Mitsubishi"),
    SUBARU("subaru", "Subaru"),
    SUZUKI("suzuki", "Suzuki"),
    CHINA("china", "Chery / Haval / Geely / Changan"),
    OTHER("", tr("не определена", "unknown"));

    companion object {
        fun of(id: String?): Make = entries.firstOrNull { it.id == id && it != OTHER } ?: OTHER

        fun fromVin(vin: String?): Make = of(CarDb.brandOf(vin)?.family)
    }
}
