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
  can_tool.py live    [--raw] [--save dosya]        canlı: uygulamanın 127.0.0.1:35002 kopyasından bilinen sinyalleri yazar
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


# ---------- canlı izleme ----------

PANEL = [  # 05704000: (bayt, maske, ad)
    (4, 0xC0, 'VOLUME bas'), (1, 0xC0, 'VOLUME çevir'), (1, 0x10, 'TUNING çevir'), (3, 0x0C, 'TUNING bas'),
    (4, 0x03, 'EXIT'), (4, 0x30, 'PHONE'), (4, 0x0C, 'MENU'), (5, 0x03, 'AM/FM'), (5, 0x0C, 'MODE'),
    (5, 0x30, 'Çıkar'), (5, 0xC0, 'ENTER'), (7, 0x01, 'yön ▲'), (7, 0x02, 'yön ▼'), (7, 0x04, 'yön ◀'), (7, 0x08, 'yön ▶')]
KEYPAD = [(5, 0x02, '1'), (5, 0x04, '2'), (5, 0x08, '3'), (5, 0x01, '0'), (4, 0x04, 'AUTO'), (4, 0x08, 'SCAN')]
CRUISE = {0x04: 'CRUISE', 0x40: '+', 0x80: '−', 0x20: '0', 0x10: 'dönüş'}


def decode(i, b):
    """Bilinen sinyaller: {ad: değer}. b = bayt listesi (int)."""
    r = {}
    n = len(b)
    if i == '19000026' and n >= 8:
        r['sinyal kolu'] = {0xA5: 'sol', 0xE5: 'sağ'}.get(b[6] & 0xF7, 'boşta')
        r['far kolu'] = 'sonuna kadar (uzun)' if b[5] & 0x08 else 'selektör' if b[5] & 0x04 else 'serbest'
        r['hız sabitleyici tuşu'] = ' '.join(v for k, v in CRUISE.items() if b[7] & k) or '-'
    elif i == '13F041BA' and n >= 2:
        r['sürücü kapısı'] = 'açık' if b[1] & 0x20 else 'kapalı'
    elif i == '1760E008' and n >= 3:
        r['el freni'] = 'çekili' if b[2] & 0x08 else 'indirilmiş'
    elif i == '1C810066' and n >= 1:
        r['arka cam rezistansı'] = 'açık' if b[0] & 0x40 else 'kapalı'
    elif i == '12404002' and n >= 8:
        r['klima sıcaklık'] = b[0]
        r['fan'] = b[3] & 0x1F
        r['hava yönü'] = '+'.join(v for k, v in ((0x10, 'yüz'), (0x08, 'gövde'), (0x04, 'ayak')) if b[7] & k) or '-'
    elif i == '05704000' and n >= 8:
        r['panel tuşu'] = ' '.join(nm for k, m, nm in PANEL if b[k] & m) or '-'
    elif i == '04A0409E' and n >= 7:
        r['rakam tuşu'] = ' '.join(nm for k, m, nm in KEYPAD if b[6] & 0x04 and b[k] & m) or '-'
    elif i == '03C3F7FC' and n >= 6:
        r['READ'] = 'basılı' if b[5] & 0x40 else '-'
    elif i == '08E2300E' and n >= 4:
        r['direksiyon ses tuşu'] = 'basılı' if b[3] & 0x40 else '-'
    elif i == '09C050B8' and n >= 1:
        r['gösterge ışığı'] = b[0]
    elif i == '19E00006' and n >= 5:
        r['hararet °C'] = b[4] - 60
    return r


def live_lines(a):
    if a.file:
        yield from (l.rstrip('\n') for l in (gzip.open(a.file, 'rt') if a.file.endswith('.gz') else open(a.file)))
        return
    import socket
    s = socket.create_connection((a.host, a.port), timeout=10)
    s.settimeout(None)
    buf = b''
    while True:
        d = s.recv(65536)
        if not d:
            return
        buf += d
        *lines, buf = buf.split(b'\n')
        for l in lines:
            yield l.decode('ascii', 'replace')


def cmd_live(a):
    import time
    state, last, recent = {}, {}, collections.defaultdict(collections.deque)
    known = {'19000026', '13F041BA', '1760E008', '1C810066', '12404002', '05704000', '04A0409E', '03C3F7FC',
             '08E2300E', '09C050B8', '19E00006'}
    save = open(a.save, 'a') if a.save else None
    for line in live_lines(a):
        if save:
            save.write(line + '\n')
        p = line.split()
        if len(p) < 3 or line.startswith('#'):
            if 'dusen' in line:
                print(time.strftime('%H:%M:%S'), line, flush=True)
            continue
        try:
            ms = int(p[0]); b = [int(p[2][k:k + 2], 16) for k in range(0, len(p[2]), 2)]
        except ValueError:
            continue
        i = p[1]
        t = time.strftime('%H:%M:%S') if not a.file else '%.2f' % (ms / 1000)
        for k, v in decode(i, b).items():
            if state.get(k) != v:
                if k in state:
                    print(t, k, ':', state[k], '→', v, flush=True)
                state[k] = v
        if a.raw and i not in known and i != '18DAF110':
            prev = last.get(i)
            if prev:
                for k, (x, y) in enumerate(zip(prev, b)):
                    if x == y:
                        continue
                    dq = recent[(i, k)]
                    dq.append(ms)
                    while dq and ms - dq[0] > 20000:
                        dq.popleft()
                    if len(dq) <= 2:                   # 20 sn'de 2'den az değişen bayt: olay olabilir
                        print(t, 'yeni?', i, 'b%d' % k, '%02X → %02X' % (x, y), flush=True)
        last[i] = b


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
    p = sp.add_parser('live'); p.add_argument('--host', default='127.0.0.1'); p.add_argument('--port', type=int, default=35002)
    p.add_argument('--file', help='canlı yerine kayıttan oynat (deneme için)')
    p.add_argument('--raw', action='store_true', help='bilinmeyen kimliklerde seyrek değişen baytları da yaz')
    p.add_argument('--save', help='gelen satırları bu dosyaya da ekle'); p.set_defaults(fn=cmd_live)
    a = ap.parse_args()
    try:
        a.fn(a)
    except BrokenPipeError:
        sys.exit(0)


if __name__ == '__main__':
    main()
