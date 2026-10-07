package com.obdscanner.screen

import com.obdscanner.tr

/**
 * Supporting the project: a line on top of the first screen opens [TEXT] with [URL] — the CloudTips donation page
 * (paid by SBP from any Russian bank's app, by T-Pay or by card). Nothing is given in return — it stays a gift.
 * Empty [URL] — the line is hidden. The page's dialog shows this link's QR, art/support-qr.png: make it anew when the
 * link changes (`segno.make(url, error='m').save('art/support-qr.png', scale=8, border=4)`).
 */
object Support {
    const val URL = "https://pay.cloudtips.ru/p/b487c73c"

    val LABEL = tr("Поддержать проект", "Support the project")
    val TEXT = tr("OBD Scanner бесплатный и без рекламы. Если приложение пригодилось, можно перевести донат по СБП из приложения любого банка или картой.",
        "OBD Scanner is free and has no ads. If it has been useful, you can send a donation through SBP from any Russian bank's app or by card.")
    val OPEN = tr("Открыть", "Open")
    val COPY = tr("Скопировать ссылку", "Copy link")
    val COPIED = tr("Ссылка скопирована", "Link copied")
    val NO_BROWSER = tr("Нет приложения, чтобы открыть ссылку", "No app to open the link")
}
