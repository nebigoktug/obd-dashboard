#!/usr/bin/env python3
"""Dil paketleri: koddaki t("...") metinlerini toplar.

  python tools/i18n.py template            # lang/template.json: tüm metinler, çevirileri boş (kullanıcı paketi şablonu)
  python tools/i18n.py check [en]          # pakette eksik ve artık (kodda olmayan) anahtarlar
  python tools/i18n.py sync en             # pakete eksikleri boş ekler, artıkları siler (sırayı koddaki sıraya göre dizer)
  python tools/i18n.py untranslated        # t() ile sarılmamış Türkçe karakterli metinler (gözden kaçanlar)

Anahtar, Türkçe metnin kendisidir (çalışma anındaki hali: kaçış dizileri çözülmüş). Paket biçimi I18n.kt'de.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"
LANG = ROOT / "app/src/main/assets/lang"
TEMPLATE = ROOT / "lang/template.json"

# t("...") / L.t("...", ...): düz dizgi ya da "+" ile birleşmiş düz dizgiler (şablon $değişken içeremez; %1$s olur)
STR = r'"(?:[^"\\$]|\\.|\$(?![{\w]))*"'
CALL = re.compile(r'(?:(?<![\w.])|(?<=\bL\.))t\(\s*(' + STR + r'(?:\s*\+\s*' + STR + r')*)\s*[,)]')
TEMPLATE_IN_CALL = re.compile(r'(?:(?<![\w.])|(?<=\bL\.))t\(\s*"((?:[^"\\]|\\.)*?(?<!%\d)\$[{\w](?:[^"\\]|\\.)*)"')
TR_CHARS = re.compile(r"[çğışöüÇĞİŞÖÜ]")
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
# Bilerek Türkçe kalan dosyalar: geliştirici araçları ve geliştiriciye giden rapor
SKIP = {"MainActivity.kt", "VehicleProbe.kt", "CanSniffer.kt", "DidScanner.kt", "Report.kt", "I18n.kt"}
# Çevrilmeyen kimlikler (karşılaştırmada kullanılır) ve geliştiriciye dönük metinler
IDS = {"Canlı", "Bağlı değil", "hazır", "ölçülüyor", "Demo", "OBD köprü · 127.0.0.1:$PORT", "API 33 öncesi", "eski arayüz",
       "yalnız okuma", "Yandex başlatılamadı", "ayrıştırılamayan cevap: ${text.take(40)}", "Mod düşürüldü: $why"}

ESC = {"n": "\n", "t": "\t", "\\": "\\", '"': '"', "'": "'", "$": "$", "r": "\r", "b": "\b"}


def unescape(s):
    return re.sub(r"\\u([0-9a-fA-F]{4})|\\(.)", lambda m: chr(int(m.group(1), 16)) if m.group(1) else ESC.get(m.group(2), m.group(2)), s)


def sources():
    return sorted(p for p in SRC.rglob("*.kt"))


def collect():
    keys, where, bad = [], {}, []
    for p in sources():
        text = p.read_text(encoding="utf-8")
        for m in CALL.finditer(text):
            k = unescape("".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))))
            if k not in where:
                keys.append(k)
                where[k] = f"{p.relative_to(SRC)}:{text.count(chr(10), 0, m.start()) + 1}"
        for m in TEMPLATE_IN_CALL.finditer(text):
            if "$" in m.group(1).replace("\\$", ""):
                bad.append(f"{p.relative_to(SRC)}:{text.count(chr(10), 0, m.start()) + 1}: {m.group(1)[:70]}")
    return keys, where, bad


def load(code):
    return json.loads((LANG / f"{code}.json").read_text(encoding="utf-8"))


def save(path, pack):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(pack, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "check"
    keys, where, bad = collect()
    for b in bad:
        print("UYARI: t() içinde $şablon (anahtar sabit olmalı, %s kullan):", b)
    if cmd == "template":
        save(TEMPLATE, {"_meta": {"code": "xx", "name": "Dilin adı (kendi dilinde)", "author": ""},
                        "strings": {k: "" for k in keys}})
        print(f"{TEMPLATE.relative_to(ROOT)}: {len(keys)} metin")
    elif cmd in ("check", "sync"):
        code = sys.argv[2] if len(sys.argv) > 2 else "en"
        pack = load(code)
        s = pack["strings"]
        missing = [k for k in keys if not s.get(k)]
        extra = [k for k in s if k not in where]
        for k in missing:
            print(f"eksik  {where[k]}: {k!r}")
        for k in extra:
            print(f"artık  {k!r}")
        # Biçim parametreleri çeviride de aynı olmalı
        fmt = re.compile(r"%(?:\d+\$)?[-+ 0,(#]*\d*(?:\.\d+)?[sdfxXc%]")
        for k, v in s.items():
            if v and k in where and sorted(fmt.findall(k)) != sorted(fmt.findall(v)):
                print(f"biçim  {where[k]}: {k!r} -> {v!r}")
        print(f"{code}: {len(keys) - len(missing)}/{len(keys)} çevrili, {len(extra)} artık")
        if cmd == "sync":
            pack["strings"] = {k: s.get(k, "") for k in keys}
            save(LANG / f"{code}.json", pack)
    elif cmd == "untranslated":
        for p in sources():
            if p.name in SKIP:
                continue
            for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
                if re.match(r"\s*(\*|/\*\*?|//)", line) or p.name == "Guidance.kt" and "Regex(" in line:
                    continue
                code = line
                if re.search(r"Log\.[dwie]\(", code):
                    continue
                for m in LITERAL.finditer(code):
                    before = code[:m.start()]
                    if re.search(r'(?:(?<![\w.])|(?<=\bL\.))t\(\s*$', before) or re.search(r'"\s*\+\s*$', before) or not before.strip():   # "+" ile bölünmüş metnin devamı
                        continue
                    if TR_CHARS.search(m.group(1)) and m.group(1) not in IDS:
                        print(f"{p.relative_to(SRC)}:{i}: {m.group(1)[:90]}")
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
