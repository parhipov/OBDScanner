package com.obdscanner.screen

import com.obdscanner.tr
import com.obdscanner.util.JSONObject

/**
 * The Licenses screen: what the app and the browser page are made of and under which terms — the list is
 * core/data/licenses/licenses.json, the texts are the files next to it. Each row opens its license ([document]).
 */
object Licenses {
    class Item(
        val id: String,
        val group: String,
        val name: String,
        val what: String,
        val license: String,
        val copyright: String,
        val url: String,
        val changes: String?,
        /** The license text, a file in licenses/ ("apache-2.0.txt"); null — nothing to show but the notice. */
        val text: String?,
        val where: Set<String>,
    )

    /** One license as its dialog shows it: [lines] (license, copyright, link, changes) over the text of [text]. */
    class Document(val title: String, val lines: List<String>, val text: String?)

    private var items: List<Item> = emptyList()

    val loaded: Boolean get() = items.isNotEmpty()

    fun load(json: String) {
        val o = JSONObject(json)
        val a = o.getJSONArray("items")
        fun text(j: JSONObject?) = j?.let { tr(it.optString("ru"), it.optString("en")) }
        items = List(a.length()) { i ->
            val j = a.getJSONObject(i)
            val w = j.optJSONArray("in")
            Item(
                id = j.getString("id"),
                group = j.getString("group"),
                name = j.getString("name"),
                what = text(j.optJSONObject("what")).orEmpty(),
                license = j.getString("license"),
                copyright = j.getString("copyright"),
                url = j.getString("url"),
                changes = text(j.optJSONObject("changes")),
                text = j.optString("text").ifEmpty { null },
                where = if (w == null) emptySet() else List(w.length()) { w.optString(it) }.toSet(),
            )
        }
    }

    /** [web] — the browser page's list: its own libraries instead of Android's. */
    fun build(web: Boolean): List<Block> {
        val b = Blocks()
        b.note(if (web) tr("Страница построена на ядре приложения OBD Scanner (MIT) и использует данные и библиотеки других авторов. Нажмите на строку, чтобы прочитать лицензию.",
            "The page is built on the core of the OBD Scanner app (MIT) and uses data and libraries by other authors. Tap a line to read its license.")
        else tr("OBD Scanner — свободная программа под лицензией MIT. В неё входят данные и библиотеки других авторов. Нажмите на строку, чтобы прочитать лицензию.",
            "OBD Scanner is free software under the MIT license. It includes data and libraries by other authors. Tap a line to read its license."))
        val here = if (web) "web" else "app"
        for ((group, title) in GROUPS) {
            val list = items.filter { it.group == group && here in it.where }
            if (list.isEmpty()) continue
            b.title(title)
            for (i in list) b.row(i.name, i.license, sub = "${i.what} · ${i.copyright}", doc = i.id, key = "lic:${i.id}")
        }
        return b.build()
    }

    fun document(id: String): Document? = items.firstOrNull { it.id == id }?.let { i ->
        Document(i.name, listOfNotNull(i.license, i.copyright, i.url, i.changes), i.text)
    }

    private val GROUPS = listOf(
        "app" to tr("Приложение", "The app"),
        "data" to tr("Данные", "Data"),
        "libs" to tr("Библиотеки", "Libraries"),
    )

    val TITLE = tr("Лицензии", "Licenses")
}
