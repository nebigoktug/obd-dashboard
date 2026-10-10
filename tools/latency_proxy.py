#!/usr/bin/env python3
"""ELM327 emülatörünün önüne konan, gerçek bir BT klonunu taklit eden proxy.

- Her isteğe BT + ECU gecikmesi ekler (--delay ± --jitter ms).
- Mode 01 isteğinde cevap sayısı eki yoksa ELM'in "başka ECU var mı" beklemesini ekler (--wait ms).
- Cevap sayısı ekini emülatöre iletmeden siler (emülatör çoklu PID + eki desteklemiyor).
- --garbage / --hang-multiframe-count: gerçek "ELM327 v2.1" klonunun 2026-10-08 raporundaki kusurları.
- --no-count / --no-multi ile o özellikleri desteklemeyen kötü bir klonu taklit eder.
- --nodata olasılıkla NO DATA döndürür, --hang-after N istekten sonra cevap vermeyi keser (BT kopması).
Kullanım: python3 latency_proxy.py --listen 35010 --upstream 35000
"""
import argparse
import random
import select
import socket
import threading
import time


def read_prompt(sock):
    buf = b""
    while b">" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("upstream kapandı")
        buf += chunk
    return buf


def strip_count(cmd):
    """'010C1' → ('010C', True). Mode 01'de PID'lerden sonra tek hane fazlaysa o cevap sayısıdır."""
    c = cmd.strip().upper().replace(" ", "")
    if c.startswith("01") and len(c) > 2 and (len(c) - 2) % 2 == 1:
        return c[:-1], True
    return c, False


PID_LEN = {0x0C: 2, 0x10: 2, 0x23: 2, 0x1F: 2, 0x21: 2, 0x31: 2, 0x42: 2, 0x5E: 2}


def multiframe(cmd):
    """Mode 01 isteğinin cevabı 7 baytı aşıyor mu (ISO-TP çok çerçeveli)?"""
    pids = [int(cmd[i:i + 2], 16) for i in range(2, len(cmd), 2)]
    return 1 + sum(1 + PID_LEN.get(p, 1) for p in pids) > 7


def pad_garbage(resp, stale):
    """v2.1 klonu taklidi: tek çerçeveli kısa cevabı önceki mesajdan kalan baytlarla 7 bayta tamamlar."""
    lines = resp.split(b"\r")
    out = []
    for l in lines:
        t = l.strip()
        if t and all(c in b"0123456789ABCDEF" for c in t) and len(t) % 2 == 0 and len(t) < 14 and t[:1] == b"4":
            t = t + stale[len(t):14]
        out.append(t if t else l)
    return b"\r".join(out), stale


# --can-sim: sahte 29-bit CAN hattı (64 kimlik, ~1900 mesaj/sn), adaptör kapasitesi --can-cap mesaj/sn
CAN_IDS_29 = [(0x00800000 + k * 0x111, random.Random(k).uniform(10, 50)) for k in range(64)]
CAN_IDS_11 = [(0x100 + k * 0x0B, random.Random(k).uniform(10, 50)) for k in range(64)]


def can_monitor(client, st, a):
    """ATMA taklidi: filtreye uyan mesajları kapasite kadar akıtır; birikme taşarsa BUFFER FULL.
    İstemciden herhangi bir bayt gelince STOPPED ile biter."""
    if a.can_silent:  # S40 (2026-10-09): sokette yayın yok, klon ~0,7 sn sonra kendisi NO DATA der
        time.sleep(0.7)
        client.sendall(b"SEARCHING...\rNO DATA\r\r>")
        return
    cf, cm = st.get("cf", 0), st.get("cm", 0)
    ids = CAN_IDS_29 if a.can_29bit else CAN_IDS_11
    fmt = b"%08X%016X\r" if a.can_29bit else b"%03X%016X\r"
    passing = [(i, r) for i, r in ids if (i & cm) == (cf & cm)]
    rate = sum(r for _, r in passing)
    backlog, sent, t0, last = 0.0, 0, time.time(), time.time()
    rnd = random.Random()
    while True:
        r, _, _ = select.select([client], [], [], 0.01)
        if r:
            client.recv(64)  # kesme karakteri
            client.sendall(b"\rSTOPPED\r\r>")
            return
        now = time.time()
        dt, last = now - last, now
        backlog += rate * dt
        n = int(min(backlog, a.can_cap * dt + 1))
        if backlog - n > 64:  # klonun küçük tamponu
            client.sendall(b"\rBUFFER FULL\r\r>")
            return
        backlog -= n
        out = []
        for _ in range(n):
            i, _r = rnd.choices(passing, weights=[x[1] for x in passing])[0] if passing else (None, 0)
            if i is None:
                break
            out.append(fmt % (i, rnd.getrandbits(64)))
        if out:
            client.sendall(b"".join(out))
            sent += len(out)


UDS_DIDS = {0xD123: b"\x01\x2C", 0x1310: b"\x5A"}   # --uds-sim: bilinen sahte DID'ler
KWP_LIDS = {0x01: b"\xAA\xBB"}


UDS_STATE = {"session": 0x01}
SESSION_LIDS = {0xB1: b"\x12\x34"}   # yalnız genişletilmiş oturumda (S40: 21 B1 -> 7F 21 22)


def uds_sim(cmd):
    """S40 benzeri ECU (2026-10-10): 0x22 tek DID; çoklu DID'e hiç cevap yok (NO DATA).
    Bilinmeyen numaraya 7F 22 31. 0x21 yerel kimlik; B1 yalnız 10 03 oturumunda."""
    c = cmd.upper().replace(" ", "")
    if c.startswith("22") and len(c) in (10, 14):
        return b"NO DATA\r\r>"
    if c.startswith("22") and len(c) == 6:
        d = int(c[2:], 16)
        return (b"62%04X" % d + UDS_DIDS[d].hex().upper().encode() + b"\r\r>") if d in UDS_DIDS else b"7F2231\r\r>"
    if c.startswith("10") and len(c) == 4:
        sub = int(c[2:], 16)
        if sub in (0x01, 0x03):
            UDS_STATE["session"] = sub
            return b"50%02X\r\r>" % sub
        return b"7F1012\r\r>"
    if c.startswith("21") and len(c) == 4:
        lid = int(c[2:], 16)
        if lid in KWP_LIDS:
            return b"61%02X" % lid + KWP_LIDS[lid].hex().upper().encode() + b"\r\r>"
        if lid in SESSION_LIDS:
            if UDS_STATE["session"] != 0x03:
                return b"7F2122\r\r>"
            return b"61%02X" % lid + SESSION_LIDS[lid].hex().upper().encode() + b"\r\r>"
        return b"7F2131\r\r>"
    return None


def can_sim_cmd(cmd, st):
    """Proxy'nin kendisi cevaplayacağı AT komutları; None ise emülatöre iletilir."""
    c = cmd.upper().replace(" ", "")
    if c.startswith("ATCF"):
        st["cf"] = int(c[4:], 16); return b"OK\r\r>"
    if c.startswith("ATCM"):
        st["cm"] = int(c[4:], 16); return b"OK\r\r>"
    if c == "ATD":
        st.clear()
    return None


def handle(client, a, counter):
    up = socket.create_connection(("127.0.0.1", a.upstream))
    up.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    pending = b""
    stale = b"F00B39F00B39F0"  # klonun tampon artığı (gerçek rapordan)
    can_state = {}
    try:
        while True:
            data = client.recv(4096)
            if not data:
                return
            pending += data
            while b"\r" in pending:
                line, pending = pending.split(b"\r", 1)
                cmd = line.decode(errors="replace").strip()
                counter[0] += 1
                if a.hang_after and counter[0] > a.hang_after:
                    continue  # adaptör "öldü": hiç cevap yok
                if a.uds_sim:
                    r = uds_sim(cmd)
                    if r is not None:
                        time.sleep(a.delay / 1000)
                        client.sendall(r)
                        continue
                if a.can_sim:
                    if cmd.upper().replace(" ", "") == "ATMA":
                        can_monitor(client, can_state, a)
                        pending = b""
                        continue
                    r = can_sim_cmd(cmd, can_state)
                    if r is not None:
                        time.sleep(a.delay / 1000)
                        client.sendall(r)
                        continue
                fwd, counted = strip_count(cmd)
                multi = fwd.startswith("01") and len(fwd) > 4
                if a.hang_multiframe_count and counted and multiframe(fwd):
                    continue  # v2.1 klonu: çok çerçeveli cevaba sayı eki verilince hiç cevap yok
                if (counted and a.no_count) or (multi and a.no_multi):
                    time.sleep(a.delay / 1000)
                    client.sendall(b"?\r\r>")  # kötü klon: komutu tanımıyor
                    continue
                up.sendall(fwd.encode() + b"\r")
                resp = read_prompt(up)
                delay = a.delay + random.uniform(-a.jitter, a.jitter)
                if fwd.startswith("01") and not counted:
                    delay += a.wait
                if fwd.startswith("01") and random.random() < a.nodata:
                    resp = b"NO DATA\r\r>"
                if a.garbage:
                    resp, stale = pad_garbage(resp, stale)
                time.sleep(max(0.0, delay) / 1000)
                client.sendall(resp)
    except (ConnectionError, OSError):
        pass
    finally:
        client.close()
        up.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", type=int, default=35010)
    ap.add_argument("--upstream", type=int, default=35000)
    ap.add_argument("--delay", type=float, default=30)
    ap.add_argument("--jitter", type=float, default=15)
    ap.add_argument("--wait", type=float, default=50)
    ap.add_argument("--nodata", type=float, default=0.005)
    ap.add_argument("--hang-after", type=int, default=0)
    ap.add_argument("--no-count", action="store_true", help="cevap sayısı eki ('010C1') desteklenmesin")
    ap.add_argument("--no-multi", action="store_true", help="tek istekte çoklu PID desteklenmesin")
    ap.add_argument("--uds-sim", action="store_true", help="0x22/0x21 isteklerine sahte ECU cevabı")
    ap.add_argument("--can-sim", action="store_true", help="ATMA/ATCF/ATCM için sahte CAN hattı")
    ap.add_argument("--can-silent", action="store_true", help="ATMA hiç mesaj görmesin (ağ geçidi arkasındaki hat)")
    ap.add_argument("--can-29bit", action="store_true", help="sahte hatta 29-bit kimlik (varsayılan 11-bit)")
    ap.add_argument("--can-cap", type=float, default=150, help="adaptörün aktarabildiği mesaj/sn")
    ap.add_argument("--garbage", action="store_true", help="kısa cevaplara eski tampon baytları ekle (v2.1 klonu)")
    ap.add_argument("--hang-multiframe-count", action="store_true",
                    help="çok çerçeveli cevaba sayı eki verilince cevap verme (v2.1 klonu)")
    a = ap.parse_args()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", a.listen))
    srv.listen(4)
    print(f"proxy :{a.listen} -> :{a.upstream}  gecikme {a.delay}±{a.jitter} ms, bekleme {a.wait} ms", flush=True)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c, a, [0]), daemon=True).start()


if __name__ == "__main__":
    main()
