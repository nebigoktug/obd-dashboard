#!/usr/bin/env python3
"""Araç testinin Python sürümü (uygulamadaki 'Araç testi' ile aynı işi yapar; ham köprü üzerinden).

Aşama 0 ölçümü: adaptörün ve aracın neyi desteklediğini, ne kadar hızlı cevap verdiğini çıkarır.

Araca yalnız OKUMA istekleri gönderir (mode 01, 03, 07, 09, 22). Arıza kodu silme (04) YOK.
Kullanım:  python obd_probe.py            (kontak açık, tercihen motor çalışıyor)
           python obd_probe.py --host 127.0.0.1 --port 35000 --reps 30
Sonuç: ekrana özet + JSON rapor (Termux'ta ~/storage/downloads varsa oraya, yoksa bulunduğun klasöre).
"""
import argparse
import json
import os
import socket
import statistics
import sys
import time
from datetime import datetime

ERRORS = ("NO DATA", "?", "CAN ERROR", "UNABLE TO CONNECT", "BUS INIT", "BUS ERROR",
          "STOPPED", "BUFFER FULL", "ERROR", "FB ERROR", "DATA ERROR", "LV RESET")

# Mode 01 PID -> (cevap bayt sayısı, ad, çözücü)
PIDS = {
    0x04: (1, "Motor yükü %", lambda b: b[0] * 100 / 255),
    0x05: (1, "Soğutucu °C", lambda b: b[0] - 40),
    0x0B: (1, "MAP kPa", lambda b: b[0]),
    0x0C: (2, "Devir rpm", lambda b: (b[0] * 256 + b[1]) / 4),
    0x0D: (1, "Hız km/h", lambda b: b[0]),
    0x0F: (1, "Emme havası °C", lambda b: b[0] - 40),
    0x10: (2, "MAF g/s", lambda b: (b[0] * 256 + b[1]) / 100),
    0x11: (1, "Gaz kelebeği %", lambda b: b[0] * 100 / 255),
    0x1F: (2, "Motor çalışma s", lambda b: b[0] * 256 + b[1]),
    0x21: (2, "MIL açık km", lambda b: b[0] * 256 + b[1]),
    0x23: (2, "Yakıt rail basıncı kPa", lambda b: (b[0] * 256 + b[1]) * 10),
    0x2F: (1, "Yakıt seviyesi %", lambda b: b[0] * 100 / 255),
    0x31: (2, "Kod silinmeden beri km", lambda b: b[0] * 256 + b[1]),
    0x33: (1, "Atmosfer basıncı kPa", lambda b: b[0]),
    0x42: (2, "Modül voltajı V", lambda b: (b[0] * 256 + b[1]) / 1000),
    0x46: (1, "Ortam sıcaklığı °C", lambda b: b[0] - 40),
    0x49: (1, "Gaz pedalı D %", lambda b: b[0] * 100 / 255),
    0x4A: (1, "Gaz pedalı E %", lambda b: b[0] * 100 / 255),
    0x5A: (1, "Göreli pedal %", lambda b: b[0] * 100 / 255),
    0x5C: (1, "Motor yağı °C", lambda b: b[0] - 40),
    0x5E: (2, "Yakıt debisi L/h", lambda b: (b[0] * 256 + b[1]) / 20),
}

# OBDb/Volvo'dan (CC BY-SA 4.0) dizel için ilginç motor ECM DID'leri. S40 1.6D'de çalışıp çalışmadığı bilinmiyor.
VOLVO_DIDS = {
    "033E": "Turbo sonrası basınç", "0341": "Turbo hava sıcaklığı", "D916": "Hava akışı", "D91F": "İstenen hava kütlesi",
    "D941": "Turbo basıncı", "D96C": "EGR doluluk", "D99C": "Yakıt sıcaklığı", "D9A0": "Kızdırma bujileri",
    "D9DC": "Yağ sıcaklığı", "D9E4": "İstenen turbo basıncı", "D9EE": "Yakıt basınç valfi", "D9F2": "DPF is miktarı",
    "D9F3": "DPF fark basıncı", "D9F9": "DPF sıcaklığı", "D9FF": "Nominal yakıt basıncı", "DA40": "Vites",
    "DAC8": "DPF basıncı", "DC00": "Turbo kontrol valfi", "DC01": "EGR valf komutu", "DC10": "Wastegate",
    "DD01": "Kilometre sayacı", "EF4E": "Yağ basıncı", "F423": "Yakıt basıncı", "F42F": "Yakıt seviyesi", "F449": "Gaz pedalı",
}


class Elm:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.log = []  # (komut, cevap, ms) — rapora ham olarak girer

    def cmd(self, c, timeout=5.0):
        """Komutu gönderir, '>' istemine kadar okur. (temiz metin, ms) döner."""
        self.sock.sendall(c.encode() + b"\r")
        t0 = time.perf_counter()
        buf = b""
        self.sock.settimeout(timeout)
        try:
            while b">" not in buf:
                chunk = self.sock.recv(512)
                if not chunk:
                    raise ConnectionError("köprü bağlantıyı kapattı (BT bağlı mı?)")
                buf += chunk
        except socket.timeout:
            buf += b"<TIMEOUT>"
        ms = (time.perf_counter() - t0) * 1000
        text = buf.decode(errors="replace").replace(">", "").replace("\r", "\n").strip()
        if len(self.log) < 2000:
            self.log.append((c, text, round(ms, 1)))
        return text, ms


def is_error(text):
    up = text.upper()
    return "<TIMEOUT>" in up or any(line.strip() in ERRORS or line.strip().startswith(ERRORS[2:])
                                    for line in up.splitlines() if line.strip())


def frames(text):
    """ELM cevabını (ATS0, ATH0) bayt dizilerine çevirir. Çok çerçeveli CAN cevabını birleştirir.
    Her ECU cevabı ayrı eleman olur (çok çerçevelide tek eleman)."""
    lines = [l.strip().replace(" ", "") for l in text.splitlines()]
    lines = [l for l in lines if l and not l.upper().startswith("SEARCHING")]
    if any(":" in l for l in lines):
        total, data = None, ""
        for l in lines:
            if ":" in l:
                data += l.split(":", 1)[1]
            elif len(l) == 3:
                total = int(l, 16)
        b = bytes.fromhex(data[: len(data) // 2 * 2])
        return [b[:total] if total else b]
    out = []
    for l in lines:
        try:
            out.append(bytes.fromhex(l))
        except ValueError:
            pass
    return out


def parse_mode01(resp, pids):
    """'41 PID veri PID veri...' cevabını {pid: bayt} sözlüğüne ayırır (çoklu PID dahil)."""
    res = {}
    for b in resp:
        if not b or b[0] != 0x41:
            continue
        i = 1
        while i < len(b):
            pid = b[i]
            if pid == 0x41 and pid not in pids:  # bazı ECU'lar çoklu cevapta servis baytını tekrarlıyor
                i += 1
                continue
            n = PIDS.get(pid, (4,))[0] if pid not in (0x00, 0x20, 0x40, 0x60, 0x80, 0xA0) else 4
            if pid in pids:
                res[pid] = b[i + 1: i + 1 + n]
            i += 1 + n
    return res


def timing(elm, command, reps, expect):
    """Komutu reps kez gönderir, süre istatistiği ve doğru cevap oranını döner."""
    times, ok, sample = [], 0, None
    for _ in range(reps):
        text, ms = elm.cmd(command, timeout=3)
        times.append(ms)
        sample = text
        if text.strip() == "?":
            return {"cmd": command, "supported": False, "sample": text}
        if not is_error(text) and set(expect) <= set(parse_mode01(frames(text), expect)):
            ok += 1
    times.sort()
    return {"cmd": command, "supported": ok > 0, "ok": ok, "reps": reps,
            "median_ms": round(statistics.median(times), 1),
            "p95_ms": round(times[int(len(times) * 0.95) - 1], 1),
            "min_ms": round(times[0], 1), "sample": sample}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=35000)
    ap.add_argument("--reps", type=int, default=30)
    ap.add_argument("--out", default=None)
    a = ap.parse_args()

    try:
        elm = Elm(a.host, a.port)
    except OSError as e:
        sys.exit(f"Bağlanılamadı {a.host}:{a.port}: {e}")
    rep = {"time": datetime.now().isoformat(timespec="seconds")}

    def step(title):
        print(f"\n== {title}", flush=True)

    step("Adaptör")
    elm.cmd("ATZ", timeout=5)
    time.sleep(0.5)
    for c in ("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"):
        elm.cmd(c)
    for c in ("ATI", "AT@1", "ATRV"):
        rep[c] = elm.cmd(c)[0]
        print(f"{c:5} {rep[c]}")

    step("Protokol (ilk istek protokol arar, birkaç saniye sürebilir)")
    text, ms = elm.cmd("0100", timeout=15)
    rep["first_0100"] = {"text": text, "ms": round(ms)}
    if is_error(text):
        print(f"0100 başarısız: {text!r}. Kontak açık mı?")
        rep["fatal"] = text
        return finish(elm, rep, a)
    rep["protocol"] = elm.cmd("ATDP")[0]
    rep["protocol_num"] = elm.cmd("ATDPN")[0].lstrip("A")
    print(f"{rep['protocol']} (#{rep['protocol_num']}), arama {ms:.0f} ms")
    is_can = rep["protocol_num"] in ("6", "7", "8", "9")

    step("Desteklenen PID'ler")
    supported = set()
    for base in range(0x00, 0xE0, 0x20):
        text, _ = elm.cmd(f"01{base:02X}")
        got = parse_mode01(frames(text), {base}).get(base)
        if not got or len(got) < 4:
            break
        bits = int.from_bytes(got[:4], "big")
        supported |= {base + i + 1 for i in range(32) if bits & (1 << (31 - i))}
        if not bits & 1:
            break
    rep["supported_pids"] = sorted(f"{p:02X}" for p in supported)
    print(" ".join(rep["supported_pids"]))

    step("Anlık değerler")
    values = {}
    for pid in sorted(supported & set(PIDS)):
        text, _ = elm.cmd(f"01{pid:02X}")
        b = parse_mode01(frames(text), {pid}).get(pid)
        if b is not None and len(b) >= PIDS[pid][0]:
            values[f"{pid:02X}"] = round(PIDS[pid][2](b), 2)
            print(f"  {pid:02X} {PIDS[pid][1]:24} {values[f'{pid:02X}']}")
    rep["values"] = values

    step("Araç bilgisi ve arıza kodları (yalnız okuma)")
    for c in ("0902", "0101", "03", "07"):
        rep[f"raw_{c}"] = elm.cmd(c, timeout=5)[0]
        print(f"{c:5} {rep[f'raw_{c}']!r}")

    step(f"Hız ölçümü ({a.reps} tekrar)")
    fast = [p for p in (0x0C, 0x0D, 0x49, 0x11, 0x04, 0x0B) if p in supported] or [0x0C]
    tests = [("010C", [0x0C]), ("010C1", [0x0C])]
    for n in sorted({2, 3, min(6, len(fast))}):
        if len(fast) >= n >= 2:
            ids = fast[:n]
            body = "01" + "".join(f"{p:02X}" for p in ids)
            tests += [(body, ids), (body + "1", ids)]
    results = []
    for command, ids in tests:
        r = timing(elm, command, a.reps, set(ids))
        r["npid"] = len(ids)
        results.append(r)
        print(f"  {command:16} " + (f"medyan {r['median_ms']:6.1f} ms  p95 {r['p95_ms']:6.1f}  doğru {r['ok']}/{r['reps']}"
                                     if "median_ms" in r else "desteklenmiyor (?)"))
    # Agresif adaptif zamanlama
    elm.cmd("ATAT2")
    r = timing(elm, "010C1", a.reps, {0x0C}); r["cmd"] = "ATAT2 010C1"; results.append(r)
    print(f"  {'ATAT2 + 010C1':16} medyan {r.get('median_ms', 0):6.1f} ms  doğru {r.get('ok', 0)}/{a.reps}")
    elm.cmd("ATAT1")
    if is_can:  # fonksiyonel (7DF) yerine doğrudan motor ECU'su
        elm.cmd("ATSH7E0")
        r = timing(elm, "010C1", a.reps, {0x0C}); r["cmd"] = "ATSH7E0 010C1"; results.append(r)
        print(f"  {'ATSH7E0 + 010C1':16} medyan {r.get('median_ms', 0):6.1f} ms  doğru {r.get('ok', 0)}/{a.reps}")
    rep["timing"] = results

    if is_can:
        step("Volvo motor ECM DID'leri (mode 22, 7E0)")
        elm.cmd("ATSH7E0")
        elm.cmd("ATST32")  # cevapsız DID'de bekleme ~200 ms ile sınırlı
        dids = {}
        for did, name in VOLVO_DIDS.items():
            text, ms = elm.cmd("22" + did, timeout=3)
            fr = frames(text)
            ok = bool(fr) and fr[0][:3] == bytes.fromhex("62" + did)
            dids[did] = {"name": name, "ok": ok, "raw": text, "ms": round(ms)}
            print(f"  {did} {name:24} {'VAR  ' + fr[0][3:].hex().upper() if ok else '-    ' + text[:20]!s}")
        rep["volvo_dids"] = dids
        elm.cmd("ATD")  # varsayılanlara dön

    return finish(elm, rep, a)


def finish(elm, rep, a):
    rep["log"] = elm.log
    best = [r for r in rep.get("timing", []) if r.get("ok")]
    if best:
        b = min(best, key=lambda r: r["median_ms"] / r.get("npid", 1))
        rep["summary"] = f"en verimli: {b['cmd']} ({b['median_ms']} ms, PID başına {b['median_ms'] / b.get('npid', 1):.1f} ms)"
        print(f"\nÖzet: {rep['summary']}")
    out = a.out
    if not out:
        d = os.path.expanduser("~/storage/downloads")
        name = f"obd_probe_{datetime.now():%Y%m%d_%H%M%S}.json"
        out = os.path.join(d if os.path.isdir(d) else ".", name)
    with open(out, "w") as f:
        json.dump(rep, f, ensure_ascii=False, indent=1)
    print(f"Rapor: {out}")
    elm.sock.close()


if __name__ == "__main__":
    main()
