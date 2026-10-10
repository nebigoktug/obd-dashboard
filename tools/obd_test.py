#!/usr/bin/env python3
"""Köprü bağlantı testi: ham soketle ELM327'ye birkaç komut gönderir."""
import socket
import sys
import time

HOST, PORT = "127.0.0.1", 35000
COMMANDS = ["ATZ", "ATE0", "ATSP0", "010C"]


def send(sock, cmd, timeout=5.0):
    """Komutu \\r ile gönderir, ELM'in '>' istemine kadar okur."""
    sock.sendall(cmd.encode() + b"\r")
    buf = b""
    end = time.time() + timeout
    while b">" not in buf and time.time() < end:
        try:
            chunk = sock.recv(256)
        except socket.timeout:
            continue
        if not chunk:
            raise ConnectionError("köprü bağlantıyı kapattı (BT bağlı mı?)")
        buf += chunk
    return buf


def main():
    try:
        sock = socket.create_connection((HOST, PORT), timeout=3)
    except OSError as e:
        sys.exit(f"Bağlanılamadı {HOST}:{PORT}: {e}\nUygulamada 'Başlat'a bastın mı?")
    sock.settimeout(0.5)
    with sock:
        for cmd in COMMANDS:
            t0 = time.time()
            raw = send(sock, cmd)
            ms = (time.time() - t0) * 1000
            text = raw.decode(errors="replace").replace("\r", " | ").strip()
            print(f"{cmd:6} -> {text}   ({ms:.0f} ms)")
            if cmd == "ATZ":
                time.sleep(0.5)  # reset sonrası adaptöre biraz zaman tanı


if __name__ == "__main__":
    main()
