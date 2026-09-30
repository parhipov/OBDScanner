# Russian titles for trouble codes (tools/dtc/dtc_ru.json)

The app shows these on a phone, in a list and in a dialog. Short, technical, the way a Russian
auto electrician or a scan tool (Launch, Autel in Russian) would write it. Keep every number, letter
index (A/B/C), abbreviation and unit exactly as in the English. One line, no trailing period.

## Pattern

"<component> — <what is wrong>". The component goes first (nominative case), then an em dash with
spaces, then the fault. Keep it close to the English meaning; do not add or drop details.

    Mass or Volume Air Flow Sensor A Circuit Low      → Датчик MAF A — низкий сигнал
    Cylinder 3 Injector Circuit                       → Форсунка цил. 3 — цепь
    Camshaft Position Sensor B Circuit Bank 1         → Датчик положения распредвала B, банк 1 — цепь
    HO2S Heater Control Circuit Bank 1 Sensor 2       → Подогрев датчика O2 Б1Д2 — цепь управления
    System Too Lean Bank 1                            → Бедная смесь, банк 1
    Lost Communication With ECM/PCM "A"               → Нет связи с ECM/PCM "A"
    Random/Multiple Cylinder Misfire Detected         → Случайные/множественные пропуски зажигания

When there is no fault part (just a component name, e.g. "Brake Switch B"), translate the name alone.

## Fault words (use exactly these)

| English | Russian |
|---|---|
| Circuit | цепь |
| Control Circuit | цепь управления |
| Circuit Low / Low Input / Low Voltage | низкий сигнал (voltage explicitly: низкое напряжение) |
| Circuit High / High Input / High Voltage | высокий сигнал (voltage explicitly: высокое напряжение) |
| Circuit Range/Performance, Range/Performance | вне диапазона / характеристика |
| Performance | характеристика |
| Intermittent / Erratic | прерывистый сигнал / нестабильный сигнал |
| Open | обрыв |
| Short to Ground | замыкание на массу |
| Short to Battery / Short to B+ | замыкание на плюс |
| Stuck Open / Stuck Closed / Stuck On / Stuck Off | заклинил открытым / закрытым / включённым / выключенным |
| Slow Response | медленный отклик |
| No Activity Detected | нет активности |
| No Signal | нет сигнала |
| Malfunction | неисправность |
| Correlation | рассогласование |
| Incorrect / Invalid Data | неверные данные |
| Lost Communication With X | нет связи с X |
| Below Threshold / Above Threshold | ниже порога / выше порога |
| Too Low / Too High | слишком низкое / слишком высокое (agree in gender) |
| Not Learned / Not Programmed | не обучен / не запрограммирован |
| Internal Failure / Internal Fault | внутренняя неисправность |
| Detected | обнаружен(о) — often can be dropped: "Misfire Detected" → "пропуски зажигания" |

## Components and abbreviations

- Bank 1 Sensor 2 → Б1Д2 when it follows an O2/HO2S/A/F sensor; otherwise "банк 1", "банк 2".
- Keep as is: MAF, MAP, ECM, PCM, TCM, BCM, ABS, SRS, EGR, EVAP, VVT, TCC, CAN, LIN, HO2S→"датчик O2",
  A/F sensor→"датчик A/F", DPF, SCR, NOx, APP, TPS→"датчик дросселя", VSS→"датчик скорости", PTO, ISC.
- Throttle → дроссель; Throttle/Pedal Position Sensor → датчик положения дросселя/педали;
  Accelerator Pedal Position → положение педали газа.
- Crankshaft → коленвал; Camshaft → распредвал; Intake/Exhaust → впуска/выпуска.
- Engine Coolant Temperature → температура ОЖ; Intake Air Temperature → температура воздуха на впуске.
- Fuel Pump → топливный насос; Fuel Rail Pressure → давление в рампе; Fuel Trim → топливная коррекция.
- Injector → форсунка; Cylinder N → цил. N; Ignition Coil → катушка зажигания; Knock Sensor → датчик детонации.
- Catalyst → катализатор; Efficiency Below Threshold → эффективность ниже порога.
- Transmission → КПП (АКПП only when clearly automatic); Shift Solenoid → соленоид переключения;
  Torque Converter Clutch → муфта гидротрансформатора; Transmission Fluid Temperature → температура масла КПП.
- Turbocharger/Supercharger → турбина/нагнетатель; Boost → наддув; Wastegate → перепускной клапан (wastegate).
- Glow Plug → свеча накаливания; Particulate Filter → сажевый фильтр (DPF).
- Airbag / Deployment Loop → подушка безопасности / цепь срабатывания; Squib → пиропатрон;
  Pretensioner → преднатяжитель ремня; Seat Belt → ремень безопасности; Clock spring → шлейф руля.
- Wheel Speed Sensor → датчик скорости колеса; Left Front → переднего левого (ПЛ is too short here).
- Module / Control Module → блок / блок управления. Hybrid/EV: Drive Motor → тяговый электродвигатель,
  Battery Pack → высоковольтная батарея, Inverter → инвертор, Phase U → фаза U.

## Causes and symptoms (the same batches, mixed with titles)

Strings that read like a phrase rather than a code title (sentence case, often with a verb or
"or"): "Wiring break or unplugged connector", "Check engine light on", "Rail pressure sensor reading
drifted". They are shown as a bullet list under "Частые причины" / "Симптомы". Translate as a natural
short Russian phrase, sentence case, no trailing period, no dash pattern:

    Wiring break or unplugged connector        → Обрыв проводки или отключён разъём
    Regulator solenoid coil open               → Обрыв обмотки соленоида регулятора
    ECU output driver failed                   → Неисправен выходной каскад ЭБУ
    Connector pins corroded - high contact resistance → Окислились контакты разъёма — высокое переходное сопротивление
    Check engine light on                      → Горит Check Engine
    Multiple warning lights may illuminate     → Могут загореться несколько контрольных ламп
    Reduced engine power (limp mode)           → Мотор теряет мощность (аварийный режим)

ECU/ECM/PCM in causes → ЭБУ (keep ECM/PCM/TCM when they name a module in a title). Vacuum leak →
подсос воздуха. Clogged → засорён. Worn → изношен. Sticking → подклинивает. Leaking → течёт / негерметичен.

## Output

A JSON object: every English string of the batch exactly as given (the key) → its Russian title.
Nothing else in the file. Every input string must be present.
