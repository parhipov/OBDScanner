package com.obdscanner.obd

import com.obdscanner.tr

/**
 * Fuel consumption in L/h from whatever the car gives, best source first. Fuel-aware: a diesel runs
 * lean, so a MAF-based figure with petrol's 14.7 would be several times too high — diesels use only
 * the ECU's own rate or the injected quantity.
 */
object FuelRate {
    class Result(val lph: Double, val how: String)

    /** Stoichiometric air/fuel ratio (by mass) and density, g/L. */
    private fun afr(fuel: String?) = if (fuel == "lpg") 15.5 else 14.7
    private fun density(fuel: String?) = when (fuel) {
        "diesel" -> 832.0
        "lpg" -> 540.0
        else -> 745.0
    }

    /** Volumetric efficiency assumed for the speed-density estimate (naturally aspirated, part load). */
    const val VE = 0.85

    /**
     * [ecuLph] PID 5E, [fuelGs] PID 9D (engine), [mgStroke] PID A2, [maf] PID 10 g/s, [lambda] PID 44,
     * [map] kPa, [iat] °C, [rpm]; [liters]/[cyl] of the engine from the car database.
     */
    fun compute(
        fuel: String?, ecuLph: Double?, fuelGs: Double?, mgStroke: Double?, maf: Double?, lambda: Double,
        map: Double?, iat: Double?, rpm: Double?, liters: Double?, cyl: Int?,
    ): Result? {
        val rho = density(fuel)
        ecuLph?.let { return Result(it, "") }
        fuelGs?.let { return Result(it * 3600 / rho, " (PID 9D)") }
        if (fuel == "diesel") {
            // mg per stroke per cylinder; each cylinder fires every second revolution.
            if (mgStroke != null && cyl != null && rpm != null) return Result(mgStroke * cyl * rpm / 2 * 60 / 1000 / rho, tr(" (по цикловой подаче)", " (from injected quantity)"))
            return null
        }
        maf?.let { return Result(it / (afr(fuel) * lambda) * 3600 / rho, tr(" (по MAF)", " (from MAF)")) }
        // No MAF: air mass from the ideal gas law — "virtual MAF" (Torque, OBD Fusion). 4-stroke: one intake per 2 revolutions.
        if (map != null && iat != null && rpm != null && liters != null) {
            val air = rpm / 120.0 * map / (iat + 273.15) * VE * liters * 28.97 / 8.314
            return Result(air / (afr(fuel) * lambda) * 3600 / rho, tr(" (по MAP, оценка)", " (from MAP, estimate)"))
        }
        return null
    }
}
