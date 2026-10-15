#!/usr/bin/env python3
"""ESP32 ham CAN kayıtlarını incelemek için küçük araç (bilgisayarda ya da Termux'ta, yalnız standart kütüphane).

Kayıt satırı: "<ms> <kimlik> <veri hex> [m]"  (m = konfor hattı, yoksa motor hattı). '#' ile başlayan satırlar açıklama.
Girdi .txt ya da .txt.gz olabilir. Saatler: --ref MS=SS:DD:ss verilirse duvar saatine çevrilir (ör. sinyal başlangıcı).

  can_tool.py ids     kayit.gz                      kimlik başına çerçeve sayısı, aralık, hat
  can_tool.py events  kayit.gz [--max 80] [--ref ...] [--skip ID[:bN],...]
                                                    seyrek değişen baytlar (tuş/durum olayları), zaman sırasıyla
  can_tool.py trace   kayit.gz ID [--byte N] [--from S --to S]
                                                    bir kimliğin değişen çerçeveleri
  can_tool.py window  kayit.gz A-B,C-D,... BAZ_A-BAZ_B
                                                    pencerelerde görülüp baz aralığında görülmeyen bayt değerleri (ms)
  can_tool.py loss    kayit.gz                      düzenli kimliklerde kaçan çerçeve oranı + "# dusen" satırları
"""
import argparse, collections, gzip, sys


def read(path):
    op = gzip.open if path.endswith('.gz') else open
    with op(path, 'rt', errors='replace') as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            if line.startswith('#'):
                yield ('#', line)
                continue
            p = line.split()
            if len(p) < 3:
                continue
            try:
                ms = int(p[0])
            except ValueError:
                continue
            yield (ms, p[1], p[2], 'm' if len(p) > 3 and p[3] == 'm' else 'e')


def frames(path):
    return (r for r in read(path) if r[0] != '#')


def walltime(ref):
    if not ref:
        return lambda ms: '%.2f' % (ms / 1000)
    a, b = ref.split('=')
    h, m, s = map(int, b.split(':'))
    base = h * 3600 + m * 60 + s - int(a) / 1000

    def f(ms):
        t = base + ms / 1000
        return '%02d:%02d:%04.1f' % (t // 3600 % 24, t % 3600 // 60, t % 60)
    return f


def cmd_ids(a):
    st = {}
    for ms, i, d, bus in frames(a.file):
        s = st.setdefault(i, [0, ms, ms, bus, None])
        s[0] += 1; s[2] = ms; s[4] = d
    for i, (n, t0, t1, bus, d) in sorted(st.items(), key=lambda kv: -kv[1][0]):
        per = (t1 - t0) / (n - 1) if n > 1 else 0
        print('%s %-9s n=%-7d ~%5.0f ms  son %s' % (bus, i, n, per, d))


def parse_skip(s):
    out = set()
    for x in (s or '').split(','):
        if x:
            out.add(tuple(x.split(':b')) if ':b' in x else (x, None))
    return out


def cmd_events(a):
    wall = walltime(a.ref)
    skip = parse_skip(a.skip)
    last = {}
    chg = collections.defaultdict(list)
    for ms, i, d, bus in frames(a.file):
        bs = [d[k:k + 2] for k in range(0, len(d), 2)]
        if i in last:
            for k, (x, y) in enumerate(zip(last[i], bs)):
                if x != y:
                    chg[(i, k, bus)].append((ms, x, y))
        last[i] = bs
    ev = []
    for (i, k, bus), l in chg.items():
        if len(l) > a.max or (i, None) in skip or (i, str(k)) in skip:
            continue
        ev += [(ms, i, k, bus, x, y) for ms, x, y in l]
    for ms, i, k, bus, x, y in sorted(ev):
        print('%s %8d %s %-9s b%d %s -> %s' % (wall(ms), ms, bus, i, k, x, y))


def cmd_trace(a):
    wall = walltime(a.ref)
    p = None
    for ms, i, d, bus in frames(a.file):
        if i != a.id or (a.frm is not None and ms < a.frm * 1000) or (a.to is not None and ms > a.to * 1000):
            continue
        v = d[a.byte * 2:a.byte * 2 + 2] if a.byte is not None else d
        if v != p:
            print(wall(ms), ms, d)
            p = v


def cmd_window(a):
    wins = [tuple(map(int, w.split('-'))) for w in a.wins.split(',')]
    base = tuple(map(int, a.base.split('-')))
    inw = collections.defaultdict(lambda: collections.defaultdict(set))
    bas = collections.defaultdict(set)
    for ms, i, d, bus in frames(a.file):
        for k in range(0, len(d), 2):
            v = d[k:k + 2]
            key = (i, k // 2)
            if base[0] <= ms <= base[1]:
                bas[key].add(v)
            for wi, (x, y) in enumerate(wins):
                if x <= ms <= y:
                    inw[key][wi].add(v)
    for key in sorted(inw):
        new = [sorted(inw[key][w] - bas[key]) for w in range(len(wins))]
        if any(new):
            print('%-9s b%d %s' % (key[0], key[1], new))


def cmd_loss(a):
    times = collections.defaultdict(list)
    for r in read(a.file):
        if r[0] == '#':
            if 'dusen' in r[1]:
                print(r[1])
            continue
        times[r[1]].append(r[0])
    rows = []
    for i, t in times.items():
        if len(t) < 50:
            continue
        d = sorted(t[k + 1] - t[k] for k in range(len(t) - 1))
        period = d[len(d) // 10] or 1          # alt %10'luk: gerçek periyoda yakın
        exp = (t[-1] - t[0]) / period + 1
        rows.append((min(1.0, len(t) / exp), i, period, len(t)))
    for got, i, period, n in sorted(rows)[:15]:
        print('%-9s ~%4d ms  n=%-6d gelen %%%3.0f' % (i, period, n, got * 100))
    if rows:
        print('ortalama gelen %%%.0f (%d kimlik)' % (100 * sum(r[0] for r in rows) / len(rows), len(rows)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest='cmd', required=True)
    p = sp.add_parser('ids'); p.add_argument('file'); p.set_defaults(fn=cmd_ids)
    p = sp.add_parser('events'); p.add_argument('file'); p.add_argument('--max', type=int, default=80)
    p.add_argument('--ref'); p.add_argument('--skip'); p.set_defaults(fn=cmd_events)
    p = sp.add_parser('trace'); p.add_argument('file'); p.add_argument('id'); p.add_argument('--byte', type=int)
    p.add_argument('--from', dest='frm', type=float); p.add_argument('--to', type=float); p.add_argument('--ref')
    p.set_defaults(fn=cmd_trace)
    p = sp.add_parser('window'); p.add_argument('file'); p.add_argument('wins'); p.add_argument('base')
    p.set_defaults(fn=cmd_window)
    p = sp.add_parser('loss'); p.add_argument('file'); p.set_defaults(fn=cmd_loss)
    a = ap.parse_args()
    try:
        a.fn(a)
    except BrokenPipeError:
        sys.exit(0)


if __name__ == '__main__':
    main()
