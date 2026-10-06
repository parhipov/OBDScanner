#!/usr/bin/env python3
"""Import manufacturer parameters from the OBDb project into the app's car database.

Usage:
    python tools/cars/obdb_import.py [--refresh] [--stats] [--with-dbg] [family ...]

    --refresh   re-list the OBDb repositories and re-download every default.json
                (otherwise the cache in tools/cars/cache/ is used; it is git-ignored)
    --stats     also print the skip reasons for signals
    --with-dbg  also import the `dbg` commands of make-wide repos and the "variant N" probes
                (unverified candidates: make-wide repos are whole ODX/PID-hunting dumps)
    family ...  only these families (gm vag toyota hyundai renault nissan ford mazda bmw
                mercedes honda mitsubishi subaru suzuki
                china); default: all

Source: https://github.com/OBDb -- one repository per vehicle (`<Make>-<Model>`, e.g.
`Toyota-RAV4`) plus make-wide repositories (`Toyota`, `Mercedes-Benz`), each with
`signalsets/v3/default.json`. Licensed CC BY-SA 4.0, so the output keeps the attribution in
`source` and every signal links its repository in `src`.

Output: core/data/cars/obdb/<family>.json, format in tools/cars/SCHEMA.md
({"family", "source", "commands": [...]}, parameters only). Russian names come from
tools/cars/obdb_ru.json ({English name -> Russian}); names missing there are listed at the end.

Network: the repo listing uses the GitHub API (unauthenticated = 60 requests/hour; set
GITHUB_TOKEN to raise it), ~8 requests per refresh. Files come from raw.githubusercontent.com,
which is not rate-limited.

OBDb signalset format (schema: github.com/OBDb/.schemas/signals.json), as used here
----------------------------------------------------------------------------------
command:
  hdr     request CAN id, hex. 3 chars = 11-bit; 4 chars = 29-bit (with `pri`) -> skipped.
  rax     reply id ("receive address"). Absent -> the usual hdr + 8.
  eax/tst extended (ISO-TP) addressing: target / tester address byte -> skipped (ELM327 needs
          CEA / special setup the app does not do).
  cmd     {"22": "1940"} (UDS ReadDataByIdentifier, 2-byte DID) or {"21": "01"} (KWP local
          id, 1 byte) or {"01": "0C"} (standard PID; not imported, the app has them).
  freq    poll period in seconds (0.25 ... 3600).
  filter  model years the command applies to: {"from": Y1, "to": Y2, "years": [...]}, each
          part inclusive and the parts OR-ed (to < from means "up to `to` or from `from`").
  dbg     true = not yet confirmed by OBDb response tests. In model repos these are mostly
          real, named parameters (Haval-Jolion oil temp 7E0 22 1602 is one) -> imported with a
          note; make-wide repos (`Volkswagen`, `Chevrolet`, ...) consist only of dbg candidates
          (ODX dumps), and "..., variant N" signals probe one DID on every module -> both
          skipped unless --with-dbg.
  dbgfilter  years still being verified; informational -> ignored.
  din/dout   diagnostic session to enter/leave -> skipped (we stay in the default session).
  fcm1    ELM327 flow-control mode 1 hint -> ignored for 11-bit (auto flow control works).
  proto   "15765-4-11bit" or none -> ok; "9141-2", "14230", "15765-4-29bit" -> skipped.
signal:
  id, name (English only), description, path ("Engine.Oil", ...), hidden, suggestedMetric
  fmt:
    bix   first bit, counted from the MSB of the first DATA byte, i.e. after the positive
          response echo (`62 DD DD` for 22, `61 ID` for 21, `41 PID` for 01). Default 0.
    len   number of bits, big-endian (bits run on across bytes, MSB first).
    blsb  bytes are little-endian -> our `le` for whole-byte fields; skipped when not byte-aligned.
    sign  two's complement signed raw.
    mul, div, add   value = raw * mul / div + add (defaults 1, 1, 0).
    min, max, nullmin, nullmax, omin, omax, oval   display/validity hints -> ignored.
    unit  OBDb unit name (celsius, kilopascal, percent, ...), see UNITS below.
    map   {"<raw int>": "<text>"} (or {"description": ...}/{"value": ...}) -> our map.
  Confirmed on a known answer: OBDb Chevrolet-Traverse / Chevrolet-Camaro have
  `7E2 22 1940 {len 8, add -40, celsius}` with no bix, and the app's GM set (gm.json, checked on a
  CTS) reads 22 1940 as A - 40 where A is the first byte after "62 19 40". Likewise
  SAEJ1979 `01 0C {len 16, div 4}` = (256A+B)/4 with A right after "41 0C". So OBDb bix ==
  our bix, no offset.

What is imported: 11-bit CAN, services 21/22, default session, commands of model repos (dbg ones
with a note); each signal whose bits, scaling and unit we can represent (not hidden, <= 32 bits,
big-endian). Identical requests (same hdr/rsp/svc/did) across models of one family are merged:
models are united (a make-wide repo = no `models`), years widened to the hull, signals
deduplicated by (bix, len, mul, div, add, signed) keeping the first id/name and uniting src.
A dbg signal whose bits overlap a non-dbg one of another model in the same request is dropped, and
with it that model's other dbg signals in the request: its ECU lays the answer out differently
(Kia Cadenza "misfire counters" on the bytes of Hyundai Elantra's rpm and idle target in 7E0 21 01),
and the app shows every value of an answering request on any car of the make.
Only signalsets/v3/default.json is read, not the per-year override files (e.g. 2012-2020.json).
Roles: from `suggestedMetric` where it matches, else from the English name (NAME_ROLES); a role
makes the signal group "main", everything else is "other" (conf is always "?": one source).
`cyl` also comes from the English name (cyl_of): "cylinder 7" / "…, V8" — the app drops such a value
on an engine with another cylinder count.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CACHE = HERE / "cache"
RAW_CACHE = CACHE / "raw"
LISTING = CACHE / "repos.json"
RU_FILE = HERE / "obdb_ru.json"
OUT_DIR = ROOT / "core" / "data" / "cars" / "obdb"

ORG = "OBDb"
SOURCE = "OBDb (CC BY-SA 4.0) https://github.com/OBDb, converted by tools/cars/obdb_import.py"

# Repo-name make prefix -> our family. A repo is `<Make>` (make-wide) or `<Make>-<Model>`.
FAMILIES: dict[str, list[str]] = {
    "gm": ["Chevrolet", "Cadillac", "GMC", "Buick", "Holden", "Pontiac", "Saturn", "Hummer",
           "Opel", "Vauxhall", "VauxhallOpel"],
    "vag": ["Volkswagen", "VW", "Audi", "Skoda", "Seat", "SEAT", "Cupra"],
    "toyota": ["Toyota", "Lexus", "Scion"],
    "hyundai": ["Hyundai", "Kia", "Genesis"],
    "renault": ["Renault", "Dacia"],
    "nissan": ["Nissan", "NissanInfiniti", "Infiniti", "INFINITI", "Datsun"],
    "ford": ["Ford", "Lincoln", "Mercury"],
    "mazda": ["Mazda"],
    "bmw": ["BMW", "MINI", "Mini"],
    "mercedes": ["Mercedes-Benz"],
    "honda": ["Honda", "Acura"],
    "mitsubishi": ["Mitsubishi"],
    "subaru": ["Subaru"],
    "suzuki": ["Suzuki", "Maruti", "Maruti-Suzuki"],
    "china": ["Haval", "Great-Wall", "GreatWall", "GWM", "Chery", "Omoda", "Exeed", "Jaecoo", "Geely",
              "Changan", "Tank", "Jetour", "BYD", "MG", "SAIC", "Maxus"],
}

# Opel/Vauxhall models built on the PSA (Stellantis) platform: not GM diagnostics.
EXCLUDE_REPOS = {"VauxhallOpel-Corsa-e", "Vauxhall-Mokka-e", "VauxhallOpel-Grandland-X",
                 "VauxhallOpel-Crossland", "VauxhallOpel-Mokka"}

# OBDb unit -> (our unit, factor, offset): value_ours = value_obdb * factor + offset.
# Units outside SCHEMA.md's core list keep a plain metric symbol.
UNITS: dict[str, tuple[str, float, float]] = {
    "celsius": ("°C", 1, 0),
    "fahrenheit": ("°C", 5 / 9, -32 * 5 / 9),
    "kelvin": ("°C", 1, -273.15),
    "kilopascal": ("kPa", 1, 0),
    "psi": ("kPa", 6.894757, 0),
    "bars": ("bar", 1, 0),
    "volts": ("V", 1, 0),
    "millivolts": ("V", 0.001, 0),
    "kilovolts": ("V", 1000, 0),
    "amps": ("A", 1, 0),
    "milliamps": ("A", 0.001, 0),
    "kiloamps": ("A", 1000, 0),
    "percent": ("%", 1, 0),
    "rpm": ("rpm", 1, 0),
    "kilometersPerHour": ("km/h", 1, 0),
    "milesPerHour": ("km/h", 1.609344, 0),
    "kilometers": ("km", 1, 0),
    "miles": ("km", 1.609344, 0),
    "meters": ("m", 1, 0),
    "feet": ("m", 0.3048, 0),
    "yards": ("m", 0.9144, 0),
    "centimeters": ("cm", 1, 0),
    "millimeters": ("mm", 1, 0),
    "inches": ("mm", 25.4, 0),
    "liters": ("L", 1, 0),
    "gallons": ("L", 3.785411784, 0),
    "litersPerHour": ("L/h", 1, 0),
    "gallonsPerHour": ("L/h", 3.785411784, 0),
    "milliseconds": ("ms", 1, 0),
    "seconds": ("s", 1, 0),
    "minutes": ("min", 1, 0),
    "hours": ("h", 1, 0),
    "degrees": ("°", 1, 0),
    "radians": ("°", 180 / math.pi, 0),
    "newtonMeters": ("Nm", 1, 0),
    "poundFoot": ("Nm", 1.3558179483, 0),
    "inchPound": ("Nm", 0.1129848290, 0),
    "watts": ("W", 1, 0),
    "milliwatts": ("W", 0.001, 0),
    "kilowatts": ("kW", 1, 0),
    "kilowattHours": ("kWh", 1, 0),
    "ampereHours": ("Ah", 1, 0),
    "milliampereHours": ("Ah", 0.001, 0),
    "kiloampereHours": ("Ah", 1000, 0),
    "gramsPerSecond": ("g/s", 1, 0),
    "kilogramsPerHour": ("kg/h", 1, 0),
    "milligramsPerStroke": ("mg/str", 1, 0),
    "gramsPerLiter": ("g/L", 1, 0),
    "ohms": ("Ω", 1, 0),
    "kiloohms": ("kΩ", 1, 0),
    "megaohms": ("MΩ", 1, 0),
    "milliohms": ("mΩ", 1, 0),
    "hertz": ("Hz", 1, 0),
    "kilohertz": ("kHz", 1, 0),
    "metersPerSecond": ("m/s", 1, 0),
    "metersPerSecondSquared": ("m/s²", 1, 0),
    "gravity": ("g", 1, 0),
    "joules": ("J", 1, 0),
    "kilojoules": ("kJ", 1, 0),
    "kilowattHoursPer100Kilometers": ("kWh/100km", 1, 0),
    "scalar": ("", 1, 0),
    "normal": ("", 1, 0),
    "unknown": ("", 1, 0),
    "framesPerSecond": ("fps", 1, 0),
}
# Two-state units become a map. The name lists the texts of raw 0 and 1 in order: `offon`
# (A/C clutch, VTEC: 1 = engaged) is 0 Off / 1 On, `noyes` 0 No / 1 Yes; the rare `onoff` and
# `yesno` are the reverse.
BOOL_UNITS = {
    "offon": ({"en": "Off", "ru": "Выкл"}, {"en": "On", "ru": "Вкл"}),
    "onoff": ({"en": "On", "ru": "Вкл"}, {"en": "Off", "ru": "Выкл"}),
    "noyes": ({"en": "No", "ru": "Нет"}, {"en": "Yes", "ru": "Да"}),
    "yesno": ({"en": "Yes", "ru": "Да"}, {"en": "No", "ru": "Нет"}),
}
# Not representable as a number: ascii, hex, and US-only consumption units.

RATE_FAST, RATE_MEDIUM = 1.0, 5.0  # freq (s) <= 1 fast, <= 5 medium, else slow
YEAR_MIN, YEAR_MAX = 1980, 2099    # stand-ins for an open end of a year filter

# OBDb suggestedMetric -> our role (fuelTankLevel only when in liters, see role_of()).
METRIC_ROLES = {
    "engineOilTemperature": "oil_temp",
    "transmissionFluidTemperature": "atf_temp",
    "odometer": "odometer",
    "stateOfCharge": "hv_soc",
    "stateOfHealth": "hv_soh",
}
# A name matching this is a target/limit/model value, not the measured one: no role by name.
NOT_ACTUAL = (r"(target|desired|commanded|setpoint|specified|requested|limit|limitation|model|"
              r"modeled|estimated|maximum|minimum|max|min|threshold|offset|adaptation|correction)")
# (regex on the lowercase English name, required unit or None, role). First match wins.
NAME_ROLES: list[tuple[str, str | None, str]] = [
    (r"\bcvt\b.*\b(fluid|oil)\b.*temp", "°C", "cvt_temp"),
    (r"\b(transmission|trans\.?|atf|gearbox)\b.*\b(fluid|oil)\b.*temp|\batf\b.*temp", "°C", "atf_temp"),
    (r"\bclutch\b.*\btemp", "°C", "clutch_temp"),
    (r"^(engine )?oil temp|\bengine oil temp", "°C", "oil_temp"),
    (r"^(engine )?oil pressure|\bengine oil pressure", None, "oil_pressure"),
    (r"\boil life\b", "%", "oil_life"),
    (r"^(engine )?oil level|\bengine oil level", None, "oil_level"),
    (r"\bcvt\b.*(deterioration|wear|degradation)", None, "cvt_wear"),
    (r"^(current|actual|engaged) gear$|^gear( position)?$|^transmission (current|actual) gear$", None, "gear"),
    (r"torque converter.*slip", "rpm", "tc_slip"),
    (r"\binput shaft speed|\bturbine speed", "rpm", "input_rpm"),
    (r"\boutput shaft speed", "rpm", "output_rpm"),
    (r"\bfuel (tank )?level\b", "L", "fuel_level_l"),
    (r"^odometer\b", "km", "odometer"),
    (r"\b(12 ?v|auxiliary|starter) battery state of charge", "%", "battery_soc"),
    (r"\b(12 ?v|auxiliary|starter) battery temp", "°C", "battery_temp"),
    (r"\bknock retard", None, "knock_retard"),
    (r"\bboost pressure\b", None, "boost"),
    (r"\bsoot\b", None, "dpf_soot"),
    (r"\b(distance|km|kilometers|miles) (to|until) (next )?(service|oil change)|\bservice distance", "km", "service_km"),
    (r"\b(days|time) (to|until) (next )?(service|oil change)", None, "service_days"),
]


# --------------------------------------------------------------------------- network / cache

def _get(url: str, api: bool = False) -> bytes | None:
    """GET a URL; None on 404. The token is sent only to api.github.com."""
    headers = {"User-Agent": "obdscanner-obdb-import"}
    if api:
        headers["Accept"] = "application/vnd.github+json"
        if os.environ.get("GITHUB_TOKEN"):
            headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code == 403 and api:
                sys.exit(f"GitHub API refused ({e.code}): rate limit? Set GITHUB_TOKEN or retry later.")
            if attempt == 2:
                raise
        except urllib.error.URLError:
            if attempt == 2:
                raise
        time.sleep(2 * (attempt + 1))
    return None


def list_repos(refresh: bool) -> list[str]:
    """All repository names of the org (cached in cache/repos.json)."""
    if LISTING.exists() and not refresh:
        return json.loads(LISTING.read_text(encoding="utf-8"))["repos"]
    names: list[str] = []
    page = 1
    while True:
        body = _get(f"https://api.github.com/orgs/{ORG}/repos?per_page=100&page={page}&type=public", api=True)
        batch = json.loads(body or b"[]")
        names += [r["name"] for r in batch if not r.get("archived")]
        if len(batch) < 100:
            break
        page += 1
    CACHE.mkdir(parents=True, exist_ok=True)
    LISTING.write_text(json.dumps({"fetched": time.strftime("%Y-%m-%d"), "repos": sorted(names)},
                                  indent=1), encoding="utf-8")
    return sorted(names)


def signalset(repo: str, refresh: bool) -> dict | None:
    """signalsets/v3/default.json of a repo, or None if it has none (cached, 404 remembered)."""
    RAW_CACHE.mkdir(parents=True, exist_ok=True)
    path, missing = RAW_CACHE / f"{repo}.json", RAW_CACHE / f"{repo}.missing"
    if not refresh:
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
        if missing.exists():
            return None
    body = _get(f"https://raw.githubusercontent.com/{ORG}/{repo}/main/signalsets/v3/default.json")
    if body is None:
        missing.touch()
        path.unlink(missing_ok=True)
        return None
    path.write_bytes(body)
    missing.unlink(missing_ok=True)
    return json.loads(body)


# --------------------------------------------------------------------------- repo -> family/model

def classify(repo: str) -> tuple[str, str | None] | None:
    """(family, model or None for a make-wide repo), or None if not one of our makes."""
    if repo in EXCLUDE_REPOS:
        return None
    best = None
    for fam, makes in FAMILIES.items():
        for make in makes:
            if repo == make or repo.startswith(make + "-"):
                if best is None or len(make) > len(best[1]):
                    best = (fam, make)
    if best is None:
        return None
    fam, make = best
    rest = repo[len(make) + 1:]
    return fam, (model_name(rest, make) if rest else None)


def model_name(rest: str, make: str) -> str:
    """Repo suffix -> model name. Hyphens stay after a short letter prefix and before Class/tron/
    Trail ('CR-V', 'CX-5', 'F-150', 'e-Golf', 'X-Trail', 'S-Class'), else become spaces
    ('Silverado 1500', 'Santa Fe', 'Bolt EV', 'CR-V Hybrid', '2 Series'); Lexus: 'GX 460'."""
    parts = rest.split("-")
    out = parts[0]
    for prev, part in zip(parts, parts[1:]):
        low = part.lower()
        if low in ("class", "tron", "trail"):
            hyphen = True
        elif low in ("hybrid", "hev", "phev", "ev", "plugin"):
            hyphen = False
        elif low == "series":
            hyphen = prev.isalpha()
        else:  # CR-V, CX-90, F-150, T-Cross, e-Golf; but GR Corolla, FJ Cruiser
            hyphen = (len(prev) <= 2 and not prev.isdigit() and make != "Lexus"
                      and (len(part) <= 3 or part[0].isdigit() or len(prev) == 1 or prev.islower()))
        out += ("-" if hyphen else " ") + part
    return out


# --------------------------------------------------------------------------- conversion

class Skips:
    """Counts of skipped commands/signals by reason, for the summary."""

    def __init__(self):
        self.commands, self.signals = Counter(), Counter()


def years_of(flt: dict | None) -> tuple[list[int] | None, str | None]:
    """OBDb filter -> ([from, to] or None, note). Parts are OR-ed; a gap is widened to the hull."""
    if not flt:
        return None, None
    ranges = []
    if "from" in flt and "to" in flt and flt["from"] <= flt["to"]:
        ranges.append((flt["from"], flt["to"]))
    else:
        if "to" in flt:
            ranges.append((YEAR_MIN, flt["to"]))
        if "from" in flt:
            ranges.append((flt["from"], YEAR_MAX))
    ranges += [(y, y) for y in flt.get("years", [])]
    if not ranges:
        return None, None
    years = sorted({y for a, b in ranges for y in range(max(a, YEAR_MIN), min(b, YEAR_MAX) + 1)})
    if not years:  # typo in the source (e.g. "from": 20232)
        return None, f"OBDb filter {json.dumps(flt, sort_keys=True)} ignored"
    lo, hi = years[0], years[-1]
    if lo == YEAR_MIN and hi == YEAR_MAX:
        return None, None
    note = None if len(years) == hi - lo + 1 else f"OBDb filter {json.dumps(flt, sort_keys=True)}"
    return [lo, hi], note


def rate_of(freq: float) -> str:
    return "fast" if freq <= RATE_FAST else "medium" if freq <= RATE_MEDIUM else "slow"


def num(x: float) -> float | int:
    """Tidy a float for JSON: integers as int, others to 10 significant digits."""
    if abs(x - round(x)) < 1e-9:
        return int(round(x))
    return float(f"{x:.10g}")


def decimals(step: float) -> int:
    """Digits after the point that show one raw step (0 for >= 1, capped at 3)."""
    step = abs(step)
    if step == 0 or step >= 1:
        return 0
    return min(3, math.ceil(-math.log10(step) - 1e-9))


def clean(text: str) -> str:
    """Drop zero-width characters and repeated spaces that some OBDb names carry."""
    return re.sub(r"\s+", " ", re.sub(r"[​-‍﻿]", "", text)).strip()


def is_code(text: str) -> bool:
    """Short language-neutral text (P, D1, 3, N/A) that needs no translation."""
    return len(text) <= 4 and not re.search(r"[a-z]", text)


def ru_of(text: str, ru: dict) -> str:
    return text if is_code(text) else ru.get(text, "")


def map_text(v) -> str | None:
    """Text of one map entry: a string, or {"value": "P", "description": "Park"} -> the short
    value when it is a code (P, D1, 3), else the description."""
    if isinstance(v, str):
        return clean(v)
    if isinstance(v, dict):
        value = v.get("value")
        if isinstance(value, str) and is_code(clean(value)):
            return clean(value)
        for k in ("description", "value", "name", "en"):
            if isinstance(v.get(k), str):
                return clean(v[k])
    if isinstance(v, (int, float)):
        return str(v)
    return None


def role_of(sig: dict, name: str, unit: str) -> str | None:
    metric = sig.get("suggestedMetric")
    if metric == "fuelTankLevel" and unit == "L":
        return "fuel_level_l"
    if metric in METRIC_ROLES:
        role = METRIC_ROLES[metric]
        if role == "odometer" and unit != "km":
            return None
        return role
    low = name.lower()
    if re.search(NOT_ACTUAL, low):
        return None
    for rx, need_unit, role in NAME_ROLES:
        if (need_unit is None or need_unit == unit) and re.search(rx, low):
            return role
    return None


# Cylinder counts the app knows of (CarDb.MAX_CYL): the upper end of "cylinder N and more".
MAX_CYL = 16


def cyl_of(name: str) -> list[int] | None:
    """Engines a value exists on, by its English name: "cylinder 7" -> [7, 16] (an engine with 7
    or more cylinders), "…, V8" -> [8, 8]. Cylinders 1–2 are on every engine: no limit."""
    m = re.search(r"\bcyl(?:inder)?\.?\s*#?\s*(\d+)\b", name, re.I)
    if m and 3 <= int(m.group(1)) <= MAX_CYL:
        return [int(m.group(1)), MAX_CYL]
    # A separate token only: "Battery block vol -V10" (Toyota hybrid) is not an engine layout.
    m = re.search(r"(?:^|[\s,(])V(6|8|10|12)(?:$|[\s,)])", name)
    if m:
        return [int(m.group(1))] * 2
    return None


def convert_signal(sig: dict, repo: str, ru: dict, skips: Skips) -> dict | None:
    fmt = sig.get("fmt") or {}
    name = clean(sig.get("name") or "")
    if not name or "len" not in fmt:
        skips.signals["no name/len"] += 1
        return None
    if sig.get("hidden"):
        skips.signals["hidden"] += 1
        return None
    bix, blen = int(fmt.get("bix", 0)), int(fmt["len"])
    if not 1 <= blen <= 32:
        skips.signals["len > 32 bits"] += 1
        return None
    # blsb: little-endian bytes. One byte or less — the order doesn't matter; whole bytes — our `le`.
    le = bool(fmt.get("blsb")) and not (blen <= 8 and bix // 8 == (bix + blen - 1) // 8)
    if le and (bix % 8 or blen % 8):
        skips.signals["little-endian, not whole bytes"] += 1
        return None
    mul, div, add = float(fmt.get("mul", 1)), float(fmt.get("div", 1)), float(fmt.get("add", 0))
    if div == 0:
        skips.signals["div 0"] += 1
        return None
    signed = bool(fmt.get("sign", False))

    unit_name = fmt.get("unit")
    out_map = None
    if "map" in fmt:
        out_map = {}
        for raw, v in fmt["map"].items():
            text = map_text(v)
            if not text or not re.fullmatch(r"-?\d+", str(raw)):  # empty texts exist in OBDb
                continue
            out_map[str(int(raw))] = {"en": text, "ru": ru_of(text, ru)}
        if not out_map:
            skips.signals["unreadable map"] += 1
            return None
        unit, k, off = "", 1.0, 0.0
    elif unit_name in BOOL_UNITS:
        off_txt, on_txt = BOOL_UNITS[unit_name]
        out_map = {"0": dict(off_txt), "1": dict(on_txt)}
        unit, k, off = "", 1.0, 0.0
    elif unit_name in UNITS:
        unit, k, off = UNITS[unit_name]
    else:
        skips.signals[f"unit {unit_name}"] += 1
        return None

    # value_ours = (raw*mul/div + add) * k + off
    mul, add = mul * k, add * k + off
    out_fmt = {"bix": bix, "len": blen, "mul": num(mul), "div": num(div), "add": num(add), "signed": signed}
    # Defaults (mul 1, div 1, add 0, unsigned) are left out to keep the asset small.
    out_fmt = {k: v for k, v in out_fmt.items()
               if (k, v) not in (("mul", 1), ("div", 1), ("add", 0), ("signed", False))}
    if le:
        out_fmt["le"] = True
    if out_map:
        out_fmt["map"] = out_map

    role = role_of(sig, name, unit)
    out = {
        "id": sig["id"],
        "name": {"en": name, "ru": ru_of(name, ru)},
        "unit": unit,
        "dec": 0 if out_map else decimals(mul / div),
        "group": "main" if role else "other",
        "fmt": out_fmt,
        "conf": "?",
        "src": [f"https://github.com/{ORG}/{repo}"],
    }
    if role:
        out["role"] = role
    cyl = cyl_of(name)
    if cyl:
        out["cyl"] = cyl
    return out


# Families whose modules outside 7E0-7E7 answer on request + 0x20 (Renault/Nissan platform: 743 -> 763,
# 748 -> 768, 79B -> 7BB; pyren/ddt4all address tables, CanZE). OBDb leaves `rax` out for many of them,
# and request + 8 is another module's *request* id there, so the command would never get an answer.
REPLY_OFFSET = {"renault": 0x20, "nissan": 0x20}


def convert_command(cmd: dict, repo: str, make_wide: bool, ru: dict, skips: Skips,
                    with_dbg: bool = False, family: str = "") -> dict | None:
    """One OBDb command -> our command (or None, reason counted)."""
    hdr, rax = cmd.get("hdr", ""), cmd.get("rax")
    svc_did = cmd.get("cmd") or {}
    reason = None
    if len(hdr) != 3:
        reason = "29-bit header"
    elif "eax" in cmd or "tst" in cmd:
        reason = "extended addressing (eax/tst)"
    elif cmd.get("proto") not in (None, "15765-4-11bit"):
        reason = f"protocol {cmd['proto']}"
    elif "din" in cmd:
        reason = "needs a diagnostic session (din)"
    elif cmd.get("dbg") and make_wide and not with_dbg:
        reason = "dbg in make-wide repo"
    elif cmd.get("dbg") and not with_dbg and any(
            re.search(r"variant", s.get("name", ""), re.I) for s in cmd.get("signals", [])):
        reason = "dbg variant probe"
    elif not ("22" in svc_did or "21" in svc_did):
        reason = f"service {'/'.join(svc_did) or '?'}"
    elif rax is not None and len(rax) != 3:
        reason = "non-11-bit reply id"
    if reason:
        skips.commands[reason] += 1
        return None
    svc = "22" if "22" in svc_did else "21"
    did = svc_did[svc].upper()
    req = int(hdr, 16)
    offset = REPLY_OFFSET.get(family, 8) if not 0x7E0 <= req <= 0x7E7 else 8
    rsp = (rax or f"{req + offset:03X}").upper()
    # Same rules as the app's loader (CarDb.parseCommand): diagnostic ids 7xx only (an id
    # outside 0x700-0x7FF may be normal bus traffic on another car), never the 7DF broadcast,
    # DID length matching the service.
    reason = None
    if not all(re.fullmatch(r"7[0-9A-F]{2}", x) for x in (hdr.upper(), rsp)):
        reason = "id outside 700-7FF"
    elif hdr.upper() == "7DF":
        reason = "functional broadcast 7DF"
    elif not re.fullmatch(r"[0-9A-F]{%d}" % (4 if svc == "22" else 2), did):
        reason = "bad DID length"
    if reason:
        skips.commands[reason] += 1
        return None
    signals =[s for s in (convert_signal(sig, repo, ru, skips) for sig in cmd.get("signals", [])) if s]
    if not signals:
        skips.commands["no usable signals"] += 1
        return None
    years, note = years_of(cmd.get("filter"))
    out = {"hdr": hdr.upper(), "rsp": rsp, "svc": svc, "did": did,
           "rate": rate_of(float(cmd.get("freq", 5))), "signals": signals}
    if years:
        out["years"] = years
    notes = [n for n in (note, "OBDb dbg: not confirmed by response tests" if cmd.get("dbg") else None) if n]
    for s in signals:
        if notes:
            s["note"] = "; ".join(notes)
        s["_dbg"] = bool(cmd.get("dbg"))  # for merge(), not written out
    return out


def sig_key(s: dict) -> tuple:
    f = s["fmt"]
    return f["bix"], f["len"], f.get("mul", 1), f.get("div", 1), f.get("add", 0), f.get("signed", False), f.get("le", False)


def foreign_dbg(signals: list[dict]) -> list[dict]:
    """dbg signals of a model whose layout of this answer clashes with another model's non-dbg one."""
    def overlap(a: dict, b: dict) -> bool:
        fa, fb = a["fmt"], b["fmt"]
        return fa["bix"] < fb["bix"] + fb["len"] and fb["bix"] < fa["bix"] + fa["len"]

    sure = [s for s in signals if not s["_dbg"]]
    bad: set[str] = set()
    for d in signals:
        if d["_dbg"] and any(overlap(d, s) and not set(d["src"]) & set(s["src"]) for s in sure):
            bad |= set(d["src"])
    return [s for s in signals if s["_dbg"] and set(s["src"]) & bad]


def merge(commands: list[tuple[dict, str | None]], skips: Skips) -> list[dict]:
    """Merge identical requests across the repos of one family (see module docstring)."""
    merged: dict[tuple, dict] = {}
    meta: dict[tuple, dict] = {}
    for cmd, model in commands:
        key = (cmd["hdr"], cmd["rsp"], cmd["svc"], cmd["did"])
        if key not in merged:
            merged[key] = {k: v for k, v in cmd.items() if k not in ("signals", "years")}
            merged[key]["signals"] = []
            meta[key] = {"models": set(), "all_models": False, "years": [], "any_year": False,
                         "rates": [], "sigs": {}}
        m = meta[key]
        if model is None:
            m["all_models"] = True
        else:
            m["models"].add(model)
        if "years" in cmd:
            m["years"].append(cmd["years"])
        else:
            m["any_year"] = True
        m["rates"].append(cmd["rate"])
        for s in cmd["signals"]:
            k = sig_key(s)
            if k in m["sigs"]:
                old = m["sigs"][k]
                old["src"] += [u for u in s["src"] if u not in old["src"]]
                old["_dbg"] = old["_dbg"] and s["_dbg"]
            else:
                m["sigs"][k] = s
                merged[key]["signals"].append(s)
    order = {"fast": 0, "medium": 1, "slow": 2}
    result = []
    for key, c in merged.items():
        m = meta[key]
        c["rate"] = min(m["rates"], key=order.get)
        if not m["all_models"]:
            c["models"] = sorted(m["models"])
        if not m["any_year"] and m["years"]:
            lo, hi = min(y[0] for y in m["years"]), max(y[1] for y in m["years"])
            if lo > YEAR_MIN or hi < YEAR_MAX:
                c["years"] = [lo, hi]
        drop = foreign_dbg(c["signals"])
        skips.signals["dbg, another model's layout"] += len(drop)
        c["signals"] = [s for s in c["signals"] if not any(s is d for d in drop)]
        for s in c["signals"]:
            del s["_dbg"]
        if not c["signals"]:
            skips.commands["no usable signals"] += 1
            continue
        c["signals"].sort(key=lambda s: (s["fmt"]["bix"], s["fmt"]["len"]))
        # Fixed key order for readable diffs.
        result.append({k: c[k] for k in ("hdr", "rsp", "svc", "did", "rate", "models", "years", "signals") if k in c})
    result.sort(key=lambda c: (c["hdr"], c["svc"], c["did"]))
    return result


def dump(out: dict) -> str:
    """JSON with one line per command header and per signal: small and diff-friendly."""
    def one(o) -> str:
        return json.dumps(o, ensure_ascii=False, separators=(", ", ": "))

    cmds = []
    for c in out["commands"]:
        head = one({k: v for k, v in c.items() if k != "signals"})[:-1]
        sigs = ",\n".join("   " + one(s) for s in c["signals"])
        cmds.append(f'  {head}, "signals": [\n{sigs}\n  ]}}')
    return ("{\n"
            f' "family": {one(out["family"])},\n'
            f' "source": {one(out["source"])},\n'
            ' "commands": [\n' + ",\n".join(cmds) + "\n ]\n}\n")


def all_names(cmds: list[dict]) -> set[str]:
    names = set()
    for c in cmds:
        for s in c["signals"]:
            names.add(s["name"]["en"])
            for v in s["fmt"].get("map", {}).values():
                names.add(v["en"])
    return names


# --------------------------------------------------------------------------- main

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--refresh", action="store_true", help="re-download listing and files")
    ap.add_argument("--with-dbg", action="store_true",
                    help="also import dbg candidates of make-wide repos and variant probes")
    ap.add_argument("--stats", action="store_true", help="print skip reasons for signals too")
    ap.add_argument("families", nargs="*", help="families to import (default: all)")
    args = ap.parse_args()
    wanted = args.families or list(FAMILIES)
    for f in wanted:
        if f not in FAMILIES:
            sys.exit(f"unknown family {f}; known: {' '.join(FAMILIES)}")

    ru = json.loads(RU_FILE.read_text(encoding="utf-8")) if RU_FILE.exists() else {}
    repos = list_repos(args.refresh)
    by_family: dict[str, list[tuple[str, str | None]]] = defaultdict(list)
    for repo in repos:
        c = classify(repo)
        if c and c[0] in wanted:
            by_family[c[0]].append((repo, c[1]))

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    untranslated: set[str] = set()
    print(f"{'family':<11}{'repos':>6}{'w/data':>7}{'cmds':>6}{'signals':>8}  skipped commands")
    for fam in wanted:
        skips = Skips()
        converted: list[tuple[dict, str | None]] = []
        with_data = 0
        for repo, model in by_family.get(fam, []):
            data = signalset(repo, args.refresh)
            if not data:
                continue
            with_data += 1
            for cmd in data.get("commands", []):
                c = convert_command(cmd, repo, model is None, ru, skips, args.with_dbg, fam)
                if c:
                    converted.append((c, model))
        cmds = merge(converted, skips)
        nsig = sum(len(c["signals"]) for c in cmds)
        names = all_names(cmds)
        untranslated |= {n for n in names if not ru_of(n, ru)}
        out = {"family": fam, "source": SOURCE, "commands": cmds}
        (OUT_DIR / f"{fam}.json").write_text(dump(out), encoding="utf-8", newline="\n")
        why = ", ".join(f"{r} {n}" for r, n in skips.commands.most_common())
        print(f"{fam:<11}{len(by_family.get(fam, [])):>6}{with_data:>7}{len(cmds):>6}{nsig:>8}  {why or '-'}")
        if args.stats and skips.signals:
            print(" " * 11 + "skipped signals: " + ", ".join(f"{r} {n}" for r, n in skips.signals.most_common()))
    print(f"\nOutput: {OUT_DIR}")
    if untranslated:
        print(f"{len(untranslated)} names lack a Russian translation in {RU_FILE.name}")
        (CACHE / "untranslated.txt").write_text("\n".join(sorted(untranslated)) + "\n", encoding="utf-8")
        print(f"  list: {CACHE / 'untranslated.txt'}")
    else:
        print("All names translated.")


if __name__ == "__main__":
    main()
