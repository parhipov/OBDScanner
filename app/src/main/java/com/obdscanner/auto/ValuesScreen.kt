package com.obdscanner.auto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
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
 * The car's screen: pages of [CarScreen.PAGE] tiles. A tile is the host's grid cell (a title, a line of text and a
 * picture, nothing else): the title fixed from the session's [CarSession.slots], under it the value's span over the
 * drive, and the picture a card in the level's colour with the value drawn big, the unit under it and a bar showing
 * where the value sits in the span. Two to four pages are tabs of one screen (hosts of Car API 6 and up); one page or
 * more than four is the plain grid, «Дальше» pushing the next page. A tap on a tile opens the hint about the phone (the
 * first plain page has it behind the car icon too). Without a link: the message and «Подключиться» to the adapter used
 * last time. Values are redrawn at most once a second.
 */
class ValuesScreen(ctx: CarContext, private val m: ObdManager, private val session: CarSession, private val page: Int) : Screen(ctx) {
    /** The tab shown, in tab mode; a tab switch is a refresh of this screen, not a new one. */
    private var tab = 0

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
        val slots = session.slots ?: CarScreen.slots(r, m.vehicle.value, m.fuelWatch.value, m.carPins.value, m.pins.value, m.hidden.value, m.safetyWatch.value)
            .takeIf { it.isNotEmpty() }?.also { session.slots = it; logHost() }
        if (slots == null) return waiting()
        val pages = CarScreen.pages(slots)
        return if (page == 0 && pages.size in 2..CarScreen.TABS && api >= 6) tabs(pages, r) else plain(pages, r)
    }

    /** Two to four pages as tabs: the page's number for a title, its digit for an icon. */
    private fun tabs(pages: List<List<CarScreen.Slot>>, r: Map<String, Reading>): Template {
        val b = TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) { tab = tabContentId.toInt(); invalidate() }
        }).setHeaderAction(Action.APP_ICON)
        for (i in pages.indices) b.addTab(Tab.Builder().setTitle(CarScreen.TAB.format(i + 1)).setIcon(digit(i + 1)).setContentId(i.toString()).build())
        val shown = tab.coerceIn(pages.indices)
        return b.setTabContents(TabContents.Builder(grid(pages[shown], r).build()).build()).setActiveTabContentId(shown.toString()).build()
    }

    /** The plain grid: one page, or the pages beyond what tabs hold, «Дальше» pushing the next. */
    private fun plain(pages: List<List<CarScreen.Slot>>, r: Map<String, Reading>): Template {
        val shown = pages.getOrElse(page) { pages.last() }
        val strip = ActionStrip.Builder()
        if (page + 1 < pages.size) strip.addAction(Action.Builder().setTitle(CarScreen.NEXT)
            .setOnClickListener { screenManager.push(ValuesScreen(carContext, m, session, page + 1)) }.build())
        // The host allows one titled action in the strip: the hint goes behind the car icon.
        if (page == 0) strip.addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car)).build())
            .setOnClickListener { screenManager.push(HintScreen(carContext)) }.build())
        val b = grid(shown, r)
            .setTitle(if (pages.size > 1) CarScreen.PAGE_OF.format(page + 1, pages.size) else APP)
            .setHeaderAction(if (page == 0) Action.APP_ICON else Action.BACK)
        // The last page beyond the first has no actions, and an empty strip is refused (a CT5 owner read the refusal on the screen).
        if (page == 0 || page + 1 < pages.size) b.setActionStrip(strip.build())
        return b.build()
    }

    /** A page's tiles; large cells where the host knows them (Car API 8), the default size elsewhere. */
    private fun grid(slots: List<CarScreen.Slot>, r: Map<String, Reading>): GridTemplate.Builder {
        val list = ItemList.Builder()
        for (slot in slots) list.addItem(item(slot, r))
        val b = GridTemplate.Builder().setSingleList(list.build())
        if (api >= 8) b.setItemSize(GridTemplate.ITEM_SIZE_LARGE)
        return b
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

    /** A tile: the fixed title, the drive's span under it, the value card as the picture; a tap opens the hint. */
    private fun item(slot: CarScreen.Slot, r: Map<String, Reading>): GridItem {
        val tile = CarScreen.tile(slot, r, m.limits())
        val reading = tile?.reading
        return GridItem.Builder()
            .setTitle(slot.label)
            .setText(CarScreen.range(reading))
            .setImage(card(reading?.display() ?: "—", slot.unit, tile?.level, CarScreen.position(reading)), GridItem.IMAGE_TYPE_LARGE)
            .setOnClickListener { screenManager.push(HintScreen(carContext)) }
            .build()
    }

    /**
     * The value as a picture: a rounded card in the level's colour (a calm dark green when the level is none, as the
     * CT5's owner asked: nothing to catch the eye until a threshold is crossed), the value in white
     * condensed bold scaled to the card's width, the unit under it, and along the bottom a bar with the value's place in
     * the drive's span (the track alone until the value has moved). Readable on the host's day and night themes alike.
     */
    private fun card(text: String, unit: String, level: Level?, position: Double?): CarIcon {
        val s = SIZE.toFloat()
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (level) { Level.GOOD -> GOOD; Level.WARN -> WARN; Level.BAD -> BAD; else -> CALM }
        }
        c.drawRoundRect(RectF(INSET, INSET, s - INSET, s - INSET), RADIUS, RADIUS, fill)
        val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE
            textAlign = Paint.Align.CENTER
            typeface = CONDENSED
            textSize = s * 0.52f
        }
        val room = s - 2 * (INSET + PAD)
        val width = digits.measureText(text)
        if (width > room) digits.textSize *= room / width
        val hasUnit = unit.isNotBlank()
        val middle = if (hasUnit) s * 0.42f else s * 0.5f
        c.drawText(text, s / 2, middle - (digits.descent() + digits.ascent()) / 2, digits)
        if (hasUnit) {
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = WHITE_DIM
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT
                textSize = s * 0.14f
            }
            val w = small.measureText(unit)
            if (w > room) small.textSize *= room / w
            c.drawText(unit, s / 2, s * 0.76f - (small.descent() + small.ascent()) / 2, small)
        }
        val bar = RectF(INSET + PAD, s * 0.875f, s - INSET - PAD, s * 0.875f + BAR)
        c.drawRoundRect(bar, BAR / 2, BAR / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE_TRACK })
        if (position != null) {
            val at = bar.left + (bar.width() * position).toFloat()
            c.drawRoundRect(RectF(bar.left, bar.top, maxOf(at, bar.left + BAR), bar.bottom), BAR / 2, BAR / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE })
        }
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    /** A tab's icon: the page's number, white on nothing, for the host to tint as it does its icons. */
    private fun digit(n: Int): CarIcon {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = size * 0.8f
        }
        Canvas(bmp).drawText(n.toString(), size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    private val api get() = carContext.carAppApiLevel

    /** Once per session, into raw.log: what the head unit's host can do, so the next drive tells us what it showed. */
    private fun logHost() {
        val limit = try { carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID) } catch (e: Exception) { null }
        m.session?.note("Android Auto: host Car API $api, grid limit ${limit ?: "?"}")
    }

    private companion object {
        const val APP = "OBD Scanner"
        /** The card's bitmap; the host fits it into 128×128 dp, 3× keeps the digits crisp. */
        const val SIZE = 384
        const val INSET = 6f
        const val PAD = 22f
        const val RADIUS = 56f
        const val BAR = 10f
        const val GOOD = 0xFF2E7D32.toInt()
        const val WARN = 0xFFEF6C00.toInt()
        const val BAD = 0xFFC62828.toInt()
        /** No rule crossed: dark green, darker than [GOOD] so a checked-and-fine value still reads as such. */
        const val CALM = 0xFF1B4D2B.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val WHITE_DIM = 0xCCFFFFFF.toInt()
        const val WHITE_TRACK = 0x40FFFFFF.toInt()
        val CONDENSED: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
}

/** «Что показывать»: how the tiles are chosen on the phone. */
class HintScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder(CarScreen.CHOOSE).setTitle(CarScreen.TITLE).setHeaderAction(Action.BACK).build()
}
