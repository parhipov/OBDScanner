#!/usr/bin/env python3
"""Build the app's trouble code database (core/data/dtc/) from open sources.

Usage:
    python tools/dtc/dtc_import.py [--refresh] [--untranslated N]

    --refresh         re-download the sources (otherwise tools/dtc/cache/ is used; it is git-ignored)
    --untranslated N  also write the English strings without a Russian translation (titles, causes,
                      symptoms) to tools/dtc/cache/untranslated_<k>.json, N per file (translation batches)
    --merge           first add the translated batches tools/dtc/cache/ru_<k>.json to dtc_ru.json
                      (checked: known English keys only, non-empty, Cyrillic), then build as usual

Sources
-------
- OBDex (github.com/foerbsnavi/OBDex), data CC0-1.0: every generic SAE code (P0, P2, P3, B0, C0, U0,
  U3) with an English title, description, common causes (with likelihood) and symptoms, written by
  the project in its own words. data/generic/<family>xxx_enriched.yaml.
- Wal33D dtc-database (github.com/Wal33D/dtc-database), MIT: manufacturer tables. Only the per-make
  tables are used, grouped into the car database families (tools/cars/SCHEMA.md), and only codes in
  manufacturer ranges (a make's copy of P0171 would hide the fuller OBDex text). Not used: its GENERIC
  table (Ford P1 texts marked generic), OTHER (an unlabelled Ford set) and AUDI (the title field holds
  causes).

Russian comes from tools/dtc/dtc_ru.json ({English -> Russian}: titles, causes, symptoms), translated
for this app by the rules in tools/dtc/TRANSLATION.md; descriptions stay English. Causes and symptoms
repeat across thousands of codes, so a set stores each once in `texts` and codes refer to them by
index. Output format: tools/dtc/SCHEMA.md.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CACHE = HERE / "cache"
RU_FILE = HERE / "dtc_ru.json"
OUT_DIR = ROOT / "core" / "data" / "dtc"

OBDEX = "https://raw.githubusercontent.com/foerbsnavi/OBDex/main/data/generic/{}xxx_enriched.yaml"
OBDEX_SETS = ["P0", "P2", "P3", "B0", "C0", "U0", "U3"]
WAL33D = "https://raw.githubusercontent.com/Wal33D/dtc-database/main/data/dtc_codes.db"

SRC_OBDEX = "OBDex (CC0) github.com/foerbsnavi/OBDex"
SRC_WAL33D = "Wal33D dtc-database (MIT, © Wal33D) github.com/Wal33D/dtc-database"

# Wal33D manufacturer -> car database family. Makes without a family in the app are left out.
FAMILY = {
    "GM": "gm", "CADILLAC": "gm", "CHEVY": "gm", "BUICK": "gm", "GMC": "gm", "PONTIAC": "gm",
    "SATURN": "gm", "OLDSMOBILE": "gm", "GEO": "gm",
    "VOLKSWAGEN": "vag",
    "FORD": "ford", "LINCOLN": "ford", "MERCURY": "ford",
    "TOYOTA": "toyota", "LEXUS": "toyota",
    "KIA": "hyundai",
    "MAZDA": "mazda", "BMW": "bmw", "MERCEDES": "mercedes",
    "HONDA": "honda", "ACURA": "honda",
    "NISSAN": "nissan", "INFINITI": "nissan",
    "SUBARU": "subaru", "MITSUBISHI": "mitsubishi", "SUZUKI": "suzuki",
}
LIKELIHOOD = {"high": 0, "medium": 1, "low": 2}


def fetch(url: str, path: Path, refresh: bool) -> Path:
    if refresh or not path.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        print(f"download {url}")
        with urllib.request.urlopen(url, timeout=120) as r:
            path.write_bytes(r.read())
    return path


def clean(s: str | None) -> str | None:
    if not s:
        return None
    # Broken encoding in the sources: "Fork pin worn � neutral travel widened" was a dash.
    s = " ".join(s.split()).replace("?s ", "'s ").replace("�", "—")
    return s or None


def obdex(refresh: bool) -> dict[str, dict]:
    """code -> {"en": title, "d": description, "c": [causes], "s": [symptoms]}"""
    out = {}
    for fam in OBDEX_SETS:
        path = fetch(OBDEX.format(fam), CACHE / f"{fam}xxx_enriched.yaml", refresh)
        for e in yaml.safe_load(path.read_text(encoding="utf-8")):
            code = e["code"].upper()
            causes = sorted(e.get("common_causes") or [], key=lambda c: LIKELIHOOD.get(c.get("likelihood"), 3))
            out[code] = {
                "en": clean(e["title"]["en"]),
                "d": clean((e.get("description") or {}).get("en")),
                "c": [clean(c["label"]["en"]) for c in causes if c.get("label", {}).get("en")],
                "s": [clean(s["en"]) for s in e.get("symptoms") or [] if s.get("en")],
            }
    return out


def wal33d(refresh: bool) -> dict[str, dict[str, str]]:
    """family -> code -> title (the most common text among the family's makes)."""
    db = sqlite3.connect(fetch(WAL33D, CACHE / "dtc_codes.db", refresh))
    texts: dict[str, dict[str, Counter]] = defaultdict(lambda: defaultdict(Counter))
    for code, make, text in db.execute(
            "select code, manufacturer, description from dtc_definitions where is_generic = 0 and locale = 'en'"):
        fam = FAMILY.get(make)
        t = clean(text)
        if fam and t and not generic_range(code.upper()):
            texts[fam][code.upper()][t] += 1
    return {fam: {code: c.most_common(1)[0][0] for code, c in codes.items()} for fam, codes in texts.items()}


def generic_range(code: str) -> bool:
    """SAE-defined ranges (same rule as DtcAnatomy.isGeneric in the app)."""
    if code[0] == "P":
        return code[1] in "02" or code[1] == "3" and code[2] >= "4"
    return code[1] in "03"


def dump(out: dict) -> str:
    """One line per code and per shared text: small and diff-friendly."""
    def one(v):
        return json.dumps(v, ensure_ascii=False, separators=(",", ":"))
    head = {k: v for k, v in out.items() if k not in ("codes", "texts")}
    body = json.dumps(head, ensure_ascii=False)[:-1]
    if "texts" in out:
        body += ', "texts": [\n' + ",\n".join("  " + one(x) for x in out["texts"]) + "\n]"
    lines = [f"  {json.dumps(k)}: {one(v)}" for k, v in sorted(out["codes"].items())]
    return body + ', "codes": {\n' + ",\n".join(lines) + "\n}}\n"


class Texts:
    """A set's shared phrase table: each distinct English string once, codes keep its index."""
    def __init__(self, ru: dict[str, str]):
        self.ru, self.items, self.index = ru, [], {}

    def id(self, en: str) -> int:
        if en not in self.index:
            self.index[en] = len(self.items)
            self.items.append(text(en, self.ru))
        return self.index[en]


def text(en: str | None, ru: dict[str, str]) -> dict:
    t = {"en": en}
    if en and ru.get(en):
        t["ru"] = ru[en]
    return t


def merge(ru: dict[str, str]) -> dict[str, str]:
    """Adds the translated batches (cache/ru_*.json) to [ru]; a value that isn't Russian is reported and skipped."""
    wanted = set()
    for part in CACHE.glob("untranslated_*.json"):
        wanted |= {clean(x) for x in json.loads(part.read_text(encoding="utf-8"))}
    added, bad = 0, []
    # ru_01.json… only, not a translator's unfinished ru_01.part03.json.
    for part in sorted(CACHE.glob("ru_[0-9][0-9].json")):
        for en, tr in json.loads(part.read_text(encoding="utf-8")).items():
            en, tr = clean(en), " ".join((tr or "").split())
            if en not in wanted and en not in ru:
                bad.append(f"{part.name}: unknown key {en!r}")
            elif not tr or not any("а" <= ch.lower() <= "я" or ch in "ёЁ" for ch in tr):
                bad.append(f"{part.name}: {en!r} -> {tr!r}")
            else:
                added += en not in ru
                ru[en] = tr
    print(f"merged {added} new translations" + (f", {len(bad)} skipped:" if bad else ""))
    for b in bad[:40]:
        print("  " + b)
    RU_FILE.write_text(json.dumps(dict(sorted(ru.items())), ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    return ru


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--refresh", action="store_true")
    ap.add_argument("--untranslated", type=int, default=0, metavar="N")
    ap.add_argument("--merge", action="store_true")
    args = ap.parse_args()
    ru = json.loads(RU_FILE.read_text(encoding="utf-8")) if RU_FILE.exists() else {}
    if args.merge:
        ru = merge(ru)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    generic = obdex(args.refresh)
    shared = Texts(ru)
    codes = {}
    for code, e in generic.items():
        c = text(e["en"], ru)
        if e["d"]:
            c["d"] = {"en": e["d"]}
        if e["c"]:
            c["c"] = [shared.id(x) for x in e["c"]]
        if e["s"]:
            c["s"] = [shared.id(x) for x in e["s"]]
        codes[code] = c
    out = {"set": "generic", "source": SRC_OBDEX, "texts": shared.items, "codes": codes}
    (OUT_DIR / "generic.json").write_text(dump(out), encoding="utf-8", newline="\n")
    titles = {e["en"] for e in generic.values() if e["en"]}
    print(f"generic    {len(codes):6d} codes, {len(shared.items)} shared causes/symptoms")

    for fam, table in sorted(wal33d(args.refresh).items()):
        out = {"set": fam, "source": SRC_WAL33D, "codes": {code: text(t, ru) for code, t in table.items()}}
        (OUT_DIR / f"{fam}.json").write_text(dump(out), encoding="utf-8", newline="\n")
        titles |= set(table.values())
        print(f"{fam:<10} {len(table):6d} codes")

    every = titles | set(shared.index)
    missing = sorted(x for x in every if x not in ru)
    unused = len(set(ru) - every)
    print(f"\n{len(every)} strings ({len(titles)} titles, {len(shared.index)} causes/symptoms): "
          f"{len(every) - len(missing)} in Russian, {len(missing)} not" + (f"; {unused} unused in {RU_FILE.name}" if unused else ""))
    for old in CACHE.glob("untranslated_*.json"):
        old.unlink()
    if args.untranslated and missing:
        n = args.untranslated
        for k in range(0, len(missing), n):
            part = CACHE / f"untranslated_{k // n + 1:02d}.json"
            part.write_text(json.dumps(missing[k:k + n], ensure_ascii=False, indent=0), encoding="utf-8")
        print(f"batches of {n}: {CACHE}/untranslated_*.json")


if __name__ == "__main__":
    sys.exit(main())
