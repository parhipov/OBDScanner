package com.obdscanner.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.obdscanner.ConnState
import com.obdscanner.ObdManager
import com.obdscanner.R
import com.obdscanner.obd.Reading
import com.obdscanner.screen.CarScreen
import com.obdscanner.screen.Level
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A page of the car's screen: [CarScreen.PAGE] tiles, each a value drawn big in the tile's picture (the host's grid
 * has a title, a line of text and a picture per cell, nothing else), the title and unit fixed from the session's
 * [CarSession.slots]. «Дальше» pushes the next page; the first page has «Что показывать» with the hint about the
 * phone. Without a link: the message and «Подключиться» to the adapter used last time. Values are redrawn at most once
 * a second.
 */
class ValuesScreen(ctx: CarContext, private val m: ObdManager, private val session: CarSession, private val page: Int) : Screen(ctx) {
    init {
        lifecycleScope.launch { m.readings.collect { invalidate(); delay(1000) } }
        lifecycleScope.launch { m.conn.collect { invalidate() } }
    }

    /** A template the host refuses would throw here and take the whole app down (the recording with it): a message instead. */
    override fun onGetTemplate(): Template = try {
        template()
    } catch (e: Exception) {
        MessageTemplate.Builder(e.message ?: e.javaClass.simpleName).setTitle(APP).setHeaderAction(Action.APP_ICON).build()
    }

    private fun template(): Template {
        val r = m.readings.value
        val slots = session.slots ?: CarScreen.slots(r, m.vehicle.value, m.fuelWatch.value, m.carPins.value, m.pins.value, m.hidden.value)
            .takeIf { it.isNotEmpty() }?.also { session.slots = it }
        if (slots == null) return waiting()
        val pages = CarScreen.pages(slots)
        val shown = pages.getOrElse(page) { pages.last() }
        val list = ItemList.Builder()
        for (slot in shown) list.addItem(item(slot, r))
        val strip = ActionStrip.Builder()
        if (page + 1 < pages.size) strip.addAction(Action.Builder().setTitle(CarScreen.NEXT)
            .setOnClickListener { screenManager.push(ValuesScreen(carContext, m, session, page + 1)) }.build())
        // The host allows one titled action in the strip: the hint goes behind the car icon.
        if (page == 0) strip.addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car)).build())
            .setOnClickListener { screenManager.push(HintScreen(carContext)) }.build())
        return GridTemplate.Builder()
            .setTitle(if (pages.size > 1) CarScreen.PAGE_OF.format(page + 1, pages.size) else APP)
            .setHeaderAction(if (page == 0) Action.APP_ICON else Action.BACK)
            .setSingleList(list.build())
            .setActionStrip(strip.build())
            .build()
    }

    /** No live values yet: a loading grid while the adapter connects, else the message with «Подключиться». */
    private fun waiting(): Template = when (val c = m.conn.value) {
        is ConnState.Connected, is ConnState.Connecting -> GridTemplate.Builder().setTitle(APP).setHeaderAction(Action.APP_ICON).setLoading(true).build()
        else -> {
            val text = if (c is ConnState.Failed) c.message else CarScreen.NO_LINK
            val b = MessageTemplate.Builder(text).setTitle(APP).setHeaderAction(Action.APP_ICON)
            if (m.lastDevice != null) b.addAction(Action.Builder().setTitle(CarScreen.CONNECT).setOnClickListener { if (!m.connectLast()) invalidate() }.build())
            b.build()
        }
    }

    /** A tile: the fixed title and unit, the value in the picture (a dash when the reading is not live now). */
    private fun item(slot: CarScreen.Slot, r: Map<String, Reading>): GridItem {
        val tile = CarScreen.tile(slot, r)
        return GridItem.Builder()
            .setTitle(slot.label)
            .setText(slot.unit.ifEmpty { " " })
            .setImage(picture(tile?.reading?.display() ?: "—", tile?.level), GridItem.IMAGE_TYPE_LARGE)
            .build()
    }

    /** The value as a picture: big bold text in the level's colour, scaled to fit. */
    private fun picture(text: String, level: Level?): CarIcon {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (level) { Level.GOOD -> 0xFF4CAF50.toInt(); Level.WARN -> 0xFFFFB300.toInt(); Level.BAD -> 0xFFE53935.toInt(); else -> 0xFFFFFFFF.toInt() }
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 128f
        }
        val width = paint.measureText(text)
        if (width > size - 16) paint.textSize = paint.textSize * (size - 16) / width
        Canvas(bmp).drawText(text, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    private companion object {
        const val APP = "OBD Scanner"
    }
}

/** «Что показывать»: how the tiles are chosen on the phone. */
class HintScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder(CarScreen.CHOOSE).setTitle(CarScreen.TITLE).setHeaderAction(Action.BACK).build()
}
