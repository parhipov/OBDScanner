# Trouble code database (`app/src/main/assets/dtc/`)

Built by `tools/dtc/dtc_import.py` (sources, licences and what is left out: its docstring). Read by
`DtcDb.kt` in the app; plain JSON, so scripts can use it too.

## Files

- `generic.json` — every generic SAE code (P0, P2, P3 from P34, B0, C0, U0, U3), from OBDex (CC0).
- `<family>.json` — one make family's own codes (`gm`, `vag`, `ford`, `toyota`, `hyundai`, …: the car
  database family ids, tools/cars/SCHEMA.md), manufacturer ranges only, from Wal33D dtc-database (MIT).
- `ftb.json` — hand-written failure type byte tables (not generated, the importer doesn't touch it).

## Code set

```json
{"set": "generic", "source": "OBDex (CC0) github.com/foerbsnavi/OBDex",
 "texts": [
  {"en": "Wiring break or unplugged connector", "ru": "Обрыв проводки или отключён разъём"},
  …
 ],
 "codes": {
  "P0171": {"en": "System Too Lean (Bank 1)", "ru": "Бедная смесь, банк 1",
            "d": {"en": "Long-term fuel trim on bank 1 is at the upper limit …"},
            "c": [327, 12, 5], "s": [0, 41]},
  …
 }}
```

- `set` — `generic` or the family id; `source` — attribution shown in the app under the description.
- A code: `en` / `ru` title (either may be missing — the app shows the other one), `d` — description,
  `c` — causes, most likely first, `s` — symptoms. `c` and `s` are indexes into `texts`: the same
  phrase ("Check engine light on") is stored once for thousands of codes.
- Russian comes from `tools/dtc/dtc_ru.json` (`{English: Russian}` for titles, causes and symptoms;
  rules in `tools/dtc/TRANSLATION.md`). Descriptions are English only.

## Lookup (the app, `DtcDb.find`)

1. The car's family set (a make's own meaning wins, also for codes it redefines).
2. `generic` — only for SAE ranges: a P1/B1/C1/U1 code means something different on every make.
3. Nothing: the app explains what the code itself says (system, subsystem, generic or manufacturer)
   and lists what other makes mean by it, labelled as such.

For the title the hand-written translations in `Dtc.kt` come before `generic` (they are older and
reviewed); a make's text comes before both.

## Failure type (`ftb.json`)

```json
{"ftb": {"gm": {"03": {"en": "Voltage below threshold", "ru": "Напряжение ниже порога"}},
         "gm_cat": {"5": {"en": "Algorithm-based failure …", "ru": "…"}},
         "j2012": {"16": {"en": "Circuit voltage below threshold", "ru": "…"}},
         "j2012_cat": {"1": {"en": "General electrical failure", "ru": "…"}}}}
```

`gm` — the byte after a GM `$A9` code ("B1517 03"), `j2012` — the UDS `$19` failure type byte
(SAE J2012-DA / ISO 14229-1 Annex D). Keys are hex. `*_cat` — by the high digit, used when the exact
value is not in the table.
