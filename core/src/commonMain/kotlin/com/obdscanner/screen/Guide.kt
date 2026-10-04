package com.obdscanner.screen

import com.obdscanner.VehicleInfo
import com.obdscanner.car.CarChoice
import com.obdscanner.car.CarDb
import com.obdscanner.obd.Make
import com.obdscanner.tr

/**
 * "How to test": which parts of the guide this car gets. The texts are the app's resources (guide.xml in res/values
 * and res/values-ru: string-arrays — a title, then the lines — and strings), named here; the page reads the same files.
 */
class GuideView(
    /** The short instruction, shown collapsed to its first [COLLAPSED] lines. */
    val steps: String,
    val obdTitle: String,
    /** The picture of the socket's place ("obd_loc_console"), null — none known. */
    val obdPicture: String?,
    val obdPlace: String?,
    /** Arguments of guide_obd_common (make, models with that place, models) when only the make's usual place is known. */
    val obdCommon: List<String>?,
    /** guide_obd_note under a picture; without one, guide_obd_pick or guide_obd_unknown. */
    val obdNote: String,
    val detailsTitle: String,
    /** Sections (string-arrays) in order: the general steps, the make's own. */
    val sections: List<String>,
    /** guide_features with the family's title, and its items. */
    val features: Pair<String, List<String>>?,
    val tail: List<String>,
) {
    companion object {
        const val COLLAPSED = 3
        val SHOW_ALL = tr("Показать всё ▾", "Show all ▾")
        val COLLAPSE = tr("Свернуть ▴", "Collapse ▴")
    }
}

object Guide {
    /** The make's own section of the guide, for the families that have one. */
    private val SECTIONS = mapOf(
        Make.GM to "guide_gm",
        Make.VAG to "guide_vag",
        Make.TOYOTA to "guide_toyota",
        Make.LADA to "guide_lada",
        Make.HYUNDAI to "guide_hyundai",
    )

    fun build(v: VehicleInfo, picked: CarChoice?): GuideView {
        val car = v.car ?: picked?.model
        val brand = v.brand ?: picked?.brand
        val make = if (v.make != Make.OTHER) v.make else Make.of(car?.family ?: picked?.family)
        val family = CarDb.family(make.id)
        // Without a model: the make's most common socket location.
        val common = if (car?.obd == null && brand != null) family?.commonObd(brand) else null
        val loc = car?.obd ?: common?.first
        val picture = CarText.obdPicture(loc)
        return GuideView(
            steps = "guide_steps",
            obdTitle = "guide_obd_title",
            obdPicture = picture,
            obdPlace = if (picture != null) CarText.obdPlace(loc) else null,
            obdCommon = if (picture != null && car?.obd == null && common != null) listOf(brand!!, "${common.second}", "${common.third}") else null,
            obdNote = when {
                picture != null -> "guide_obd_note"
                car == null && brand == null -> "guide_obd_pick"
                else -> "guide_obd_unknown"
            },
            detailsTitle = "guide_details",
            sections = listOfNotNull("guide_more_steps", SECTIONS[make]),
            features = family?.takeIf { it.features.isNotEmpty() }?.let { it.title to it.features },
            tail = listOf("guide_safety", "guide_files"),
        )
    }
}
