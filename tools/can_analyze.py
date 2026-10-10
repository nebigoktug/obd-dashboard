#!/usr/bin/env python3
"""CAN keşif kaydını (uygulamanın files/can/can_*.txt) çözmeye yardım eder.

1) Kimlik özeti: kaç mesaj, yaklaşık yayın sıklığı (Hz), uzunluk.
2) Adıma özgü baytlar: yalnız bir talimat adımında (gaz, fren, direksiyon...) değişen baytlar/bitler.
   Ör. sadece "fren" adımında değişen bit → büyük ihtimalle fren sinyali.
3) Devir adayları: 16-bit değerlerin adım ortalamaları ile OBD'den okunan devrin (REF) örtüşmesi.
Kullanım: python3 can_analyze.py can_20261008_090000.txt [--top 15]
"""
import argparse
import math
import statistics
from collections import defaultdict


def load(path):
    frames = []        # (t, step, id, bytes)
    steps = {}         # step -> ad
    refs = defaultdict(list)  # step -> [rpm]
    drive_refs = []           # sürüş modu: (t, rpm, speed, load)
    listen = defaultdict(float)  # dilim -> dinlenen süre (ms)
    meta = []
    step, last_slice_end = -1, None
    for line in open(path, encoding="utf-8"):
        p = line.rstrip("\n").split(" ")
        tag = p[0]
        if tag == "STEP":
            step = int(p[2]); steps[step] = " ".join(p[3:]); last_slice_end = int(p[1])
        elif tag == "SLICE":
            t, sl = int(p[1]), int(p[2])
            if last_slice_end is not None:
                listen[sl] += t - last_slice_end
            last_slice_end = t
        elif tag.startswith("REF"):
            kv = dict(x.split("=", 1) for x in p[3:] if "=" in x)
            if kv.get("rpm") not in (None, "null"):
                refs[int(p[2])].append(float(kv["rpm"]))
                if tag == "REF" and kv.get("speed") not in (None, "null") and kv.get("load") not in (None, "null"):
                    drive_refs.append((int(p[1]), float(kv["rpm"]), float(kv["speed"]), float(kv["load"])))
        elif tag == "F":
            sl = int(p[2])
            if sl < 0 or len(p) < 5:
                continue  # patlama örnekleri adımsız
            try:
                frames.append((int(p[1]), step, p[3], bytes.fromhex(p[4])))
            except ValueError:
                pass
        elif tag in ("BURST", "#", "FATAL"):
            meta.append(line.strip())
    return frames, steps, refs, listen, meta, drive_refs


def drive_analysis(by_id, drive_refs, top):
    """Sürüş kaydı: her mesajı kendinden hemen önceki OBD referansıyla eşleyip korelasyon arar."""
    import bisect
    ts = [r[0] for r in drive_refs]

    def ref_at(t):
        i = bisect.bisect_right(ts, t) - 1
        return drive_refs[i] if i >= 0 and t - ts[i] < 3000 else None

    targets = {"devir": lambda r: r[1], "hız": lambda r: r[2], "yakıt vekili (yük×devir)": lambda r: r[3] * r[1]}
    found = {k: [] for k in targets}
    low_card = []
    for cid, fs in by_id.items():
        pairs = [(f[3], ref_at(f[0])) for f in fs]
        pairs = [(d, r) for d, r in pairs if r]
        if len(pairs) < 20:
            continue
        n = max(len(d) for d, _ in pairs)
        signals = {}
        for bi in range(n):
            signals[f"bayt {bi}"] = [d[bi] if bi < len(d) else None for d, _ in pairs]
            if bi + 1 < n:
                signals[f"bayt {bi}-{bi + 1} BE"] = [d[bi] * 256 + d[bi + 1] if bi + 1 < len(d) else None for d, _ in pairs]
                signals[f"bayt {bi}-{bi + 1} LE"] = [d[bi + 1] * 256 + d[bi] if bi + 1 < len(d) else None for d, _ in pairs]
        for name, vals in signals.items():
            xy = [(v, r) for v, (_, r) in zip(vals, pairs) if v is not None]
            xs = [v for v, _ in xy]
            if len(set(xs)) < 2:
                continue
            for tname, f in targets.items():
                ys = [f(r) for _, r in xy]
                if statistics.pstdev(ys) == 0:
                    continue
                found[tname].append((statistics.correlation(xs, ys), cid, name))
            # Az değerli ve ara sıra değişen sinyaller: vites önerisi oku gibi
            k = len(set(xs))
            changes = sum(1 for x0, x1 in zip(xs, xs[1:]) if x0 != x1)
            if " " in name and "-" not in name and 2 <= k <= 6 and 2 <= changes <= len(xs) // 5:
                by_val = defaultdict(list)
                for v, r in xy:
                    by_val[v].append(r[1])
                desc = ", ".join(f"{v}: ort {statistics.mean(rs):.0f} rpm (n={len(rs)})" for v, rs in sorted(by_val.items()))
                low_card.append((cid, name, changes, desc))
    for tname, lst in found.items():
        print(f"\n== Sürüş: '{tname}' ile en iyi örtüşenler")
        for r, cid, name in sorted(lst, key=lambda x: -abs(x[0]))[:top]:
            print(f"  r={r:+.3f}  {cid} {name}")
    print("\n== Sürüş: az değerli, ara sıra değişen sinyaller (vites önerisi adayları; değer başına ortalama devir)")
    for cid, name, ch, desc in sorted(low_card, key=lambda x: x[2])[: top * 2]:
        print(f"  {cid} {name}: {ch} değişim | {desc}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--top", type=int, default=15)
    a = ap.parse_args()
    frames, steps, refs, listen, meta, drive_refs = load(a.file)
    for m in meta:
        print(m)
    if not frames:
        print("Mesaj yok.")
        return

    by_id = defaultdict(list)
    for f in frames:
        by_id[f[2]].append(f)
    is29 = len(next(iter(by_id))) == 8

    print(f"\n== {len(by_id)} kimlik, {len(frames)} mesaj")
    print("kimlik       adet   ~Hz  uzunluk")
    for cid, fs in sorted(by_id.items(), key=lambda kv: -len(kv[1])):
        sl = int(cid[-1], 16)
        hz = len(fs) / (listen[sl] / 1000) if listen.get(sl) else float("nan")
        print(f"{cid:10} {len(fs):6} {hz:6.1f}  {sorted({len(f[3]) for f in fs})}")

    # Adıma özgü baytlar: bir adımda değişkenlik yüksek, diğerlerinde ~sabit
    print("\n== Yalnız bir adımda değişen baytlar (aday sinyaller)")
    cands = []
    for cid, fs in by_id.items():
        n = max(len(f[3]) for f in fs)
        for bi in range(n):
            per = defaultdict(list)
            for _, st, _, d in fs:
                if bi < len(d):
                    per[st].append(d[bi])
            if len(per) < 2:
                continue
            distinct = {st: len(set(v)) for st, v in per.items()}
            active = [st for st, k in distinct.items() if k > 1]
            if len(active) == 1:
                st = active[0]
                changed_bits = 0
                for v in per[st]:
                    changed_bits |= v ^ per[st][0]
                cands.append((cid, bi, st, distinct[st], changed_bits))
    for cid, bi, st, k, bits in sorted(cands, key=lambda c: (c[2], c[0], c[1])):
        print(f"  adım {st} ({steps.get(st, '?')[:28]:28}) {cid} bayt {bi}: {k} farklı değer, değişen bitler {bits:08b}")

    # Devir adayları: adım ortalaması vs REF rpm ortalaması (Pearson)
    ref_mean = {st: statistics.mean(v) for st, v in refs.items() if v}
    if len(ref_mean) >= 3:
        print("\n== Devir adayları (16-bit, adım ortalamaları OBD devriyle karşılaştırıldı)")
        scored = []
        for cid, fs in by_id.items():
            n = max(len(f[3]) for f in fs)
            for bi in range(n - 1):
                for endian in ("BE", "LE"):
                    per = defaultdict(list)
                    for _, st, _, d in fs:
                        if bi + 1 < len(d):
                            v = d[bi] * 256 + d[bi + 1] if endian == "BE" else d[bi + 1] * 256 + d[bi]
                            per[st].append(v)
                    common = [st for st in ref_mean if per.get(st)]
                    if len(common) < 3:
                        continue
                    xs = [statistics.mean(per[st]) for st in common]
                    ys = [ref_mean[st] for st in common]
                    if statistics.pstdev(xs) == 0 or statistics.pstdev(ys) == 0:
                        continue
                    r = statistics.correlation(xs, ys)
                    scale = statistics.mean(ys) / statistics.mean(xs) if statistics.mean(xs) else math.nan
                    scored.append((r, cid, bi, endian, scale))
        for r, cid, bi, e, scale in sorted(scored, reverse=True)[: a.top]:
            print(f"  r={r:+.3f}  {cid} bayt {bi}-{bi + 1} {e}  ölçek ≈ {scale:.4g} (rpm/ham)")
    if len(drive_refs) >= 20:
        drive_analysis(by_id, drive_refs, a.top)
    print(f"\nadımlar: {steps}\nOBD devir (adım): { {k: round(v) for k, v in ref_mean.items()} }")


if __name__ == "__main__":
    main()
