package com.obdscanner.screen

import com.obdscanner.session.Store
import com.obdscanner.tr

/**
 * The terms OBD Scanner opens with, in the app and on the page alike: nothing else is shown until they are accepted
 * ([accept]), then never again until the text changes. The acceptance is kept in the front's [Store] (the app's
 * settings, the page's localStorage) as [VERSION]: raise it with any change of the text, put the change's date into
 * [EDITION], and everyone is asked again. The app's own version doesn't matter: an update with the same text asks nothing.
 * Info → About opens the same text later.
 */
object Terms {
    const val VERSION = 1
    private const val KEY = "terms"

    /** Shown under the title: which text the reader accepts. */
    val EDITION = tr("Редакция $VERSION от 5 октября 2026", "Version $VERSION of 5 October 2026")

    fun accepted(store: Store): Boolean = store.getInt(KEY, 0) >= VERSION

    fun accept(store: Store) = store.putInt(KEY, VERSION)

    val TITLE = tr("Отказ от ответственности", "Disclaimer")
    val LEAD = tr("OBD Scanner — бесплатная программа для чтения данных автомобиля через адаптер ELM327. Она предоставляется «как есть», вы пользуетесь ею на свой риск. Нажимая «Принимаю», вы подтверждаете, что прочитали условия ниже и согласны с ними.",
        "OBD Scanner is a free program that reads a car's data through an ELM327 adapter. It is provided \"as is\" and you use it at your own risk. By pressing \"I accept\" you confirm that you have read the terms below and agree to them.")
    val ACCEPT = tr("Принимаю", "I accept")
    val DECLINE = tr("Не согласны — закройте OBD Scanner.", "If you do not agree, close OBD Scanner.")
    /** Followed by the project's mailbox. */
    val QUESTIONS = tr("Вопросы: ", "Questions: ")

    /** Heading and text, numbered by the fronts. */
    val SECTIONS: List<Pair<String, String>> = listOf(
        tr("Только для справки", "For reference only") to tr(
            "OBD Scanner показывает то, что сообщают блоки управления автомобиля, и подсказки к этим данным. Он не заменяет диагностику на специальном оборудовании и осмотр специалистом. Решения о ремонте и эксплуатации автомобиля принимаете вы.",
            "OBD Scanner shows what the car's control modules report, with hints on those values. It does not replace diagnostics on dedicated equipment or an inspection by a mechanic. Decisions about repairs and about driving the car are yours."),
        tr("Без гарантий", "No warranty") to tr(
            "OBD Scanner, его данные и расчёты предоставляются «как есть», без каких-либо гарантий, в том числе точности, полноты, бесперебойной работы и пригодности для конкретной цели. Расшифровки параметров, описания кодов ошибок, нормы, подсказки и сведения о моделях собраны из открытых источников и могут быть неточными, устаревшими или не подходить к вашему автомобилю.",
            "OBD Scanner, its data and its calculations are provided \"as is\", without warranty of any kind, including accuracy, completeness, uninterrupted operation and fitness for a particular purpose. Parameter decoding, trouble code descriptions, normal ranges, hints and model data come from open sources and may be inaccurate, out of date or not apply to your car."),
        tr("Запросы к автомобилю", "Requests to the car") to tr(
            "OBD Scanner отправляет запросы блокам управления через адаптер. Сброс ошибок стирает коды, стоп-кадр и результаты самодиагностики. Неисправный или некачественный адаптер, а в редких случаях и сами запросы, могут вызвать ошибки в блоках, сбои в работе систем автомобиля или разряд аккумулятора. Не оставляйте адаптер в разъёме надолго при заглушённом двигателе.",
            "OBD Scanner sends requests to the control modules through the adapter. Clearing codes erases the codes, the freeze frame and the self-test results. A faulty or poor-quality adapter, and in rare cases the requests themselves, can cause module faults, malfunctions of the car's systems or a flat battery. Do not leave the adapter plugged in for long with the engine off."),
        tr("Безопасность", "Safety") to tr(
            "Не смотрите на экран и не трогайте телефон за рулём: телефоном пользуется пассажир или водитель на стоянке. Положите телефон так, чтобы он не мешал управлению и не мог попасть под педали. Проверки в движении проводите только по правилам дорожного движения и там, где это безопасно.",
            "Do not look at the screen or touch the phone while driving: the phone is for a passenger, or for the driver when parked. Put the phone where it does not get in the way of driving and cannot slide under the pedals. Run tests on the move only within the traffic rules and where it is safe."),
        tr("Ответственность", "Liability") to tr(
            "В пределах, допустимых законом, автор не отвечает за любой прямой или косвенный ущерб, связанный с использованием OBD Scanner или невозможностью его использовать: повреждение автомобиля, адаптера или телефона, расходы на ремонт, штрафы, потерю гарантии, данных или времени.",
            "To the extent permitted by law, the author is not liable for any direct or indirect damage arising from the use of OBD Scanner or the inability to use it, including damage to the car, the adapter or the phone, repair costs, fines, and loss of warranty, data or time."),
        tr("Данные", "Data") to tr(
            "Данные автомобиля и датчиков телефона обрабатываются на вашем устройстве и никуда не отправляются. Записи сессий, выбранная модель и настройки подключения остаются на устройстве. Лог сессии содержит VIN и данные автомобиля и попадает к автору, только если вы сами его отправите.",
            "The car's and the phone sensors' data is processed on your device and is not sent anywhere. Session recordings, the chosen model and the connection settings stay on the device. A session log holds the VIN and the car's data and reaches the author only if you send it yourself."),
        tr("Товарные знаки", "Trademarks") to tr(
            "Марки и модели автомобилей и адаптеров названы только для указания совместимости. OBD Scanner не связан с их производителями и не одобрен ими; товарные знаки принадлежат их владельцам.",
            "Car and adapter makes and models are named only to indicate compatibility. OBD Scanner is not affiliated with or endorsed by their manufacturers; trademarks belong to their owners."),
        tr("Лицензии", "Licenses") to tr(
            "Библиотеки и данные, из которых собран OBD Scanner, распространяются по своим лицензиям; их список — на вкладке «Инфо», кнопка «Лицензии».",
            "The libraries and data OBD Scanner is built from come under their own licenses, listed on the Info tab under Licenses."),
        tr("Поддержка проекта", "Supporting the project") to tr(
            "Поддержка — добровольный подарок, а не оплата: все функции доступны без неё, взамен ничего не предоставляется.",
            "Support is a voluntary gift, not a payment: every feature works without it, and nothing is given in return."),
        tr("Изменения", "Changes") to tr(
            "Условия могут меняться. После изменения OBD Scanner попросит принять их снова.",
            "These terms may change. After a change OBD Scanner asks to accept them again."),
    )
}
