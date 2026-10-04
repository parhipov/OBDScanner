package com.obdscanner.screen

import com.obdscanner.tr

/**
 * Supporting the project: a line on top of the first screen opens [TEXT] with [URL] — the T-Bank top-up link of
 * a separate account (paid by card or SBP from any Russian bank). Nothing is given in return — it stays a gift.
 * Empty [URL] — the line is hidden. The page's dialog shows this link's QR, art/support-qr.png: make it anew when the
 * link changes (`segno.make(url, error='m').save('art/support-qr.png', scale=8, border=4)`).
 */
object Support {
    const val URL = "https://www.tbank-online.com/rm/r_SSFkWiZytV.myhsNccmgn/gBETJ59930"

    val LABEL = tr("Поддержать проект", "Support the project")
    val TEXT = tr("OBD Scanner бесплатный и без рекламы. Если приложение пригодилось, можно перевести любую сумму через СБП из приложения любого банка.",
        "OBD Scanner is free and has no ads. If it has been useful, you can send any amount through SBP (Russian banks only).")
    val OPEN = tr("Открыть", "Open")
    val COPY = tr("Скопировать ссылку", "Copy link")
    val COPIED = tr("Ссылка скопирована", "Link copied")
    val NO_BROWSER = tr("Нет приложения, чтобы открыть ссылку", "No app to open the link")
}
