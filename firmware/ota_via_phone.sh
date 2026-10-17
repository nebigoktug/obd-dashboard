#!/bin/sh
# Kutuya Wi-Fi'dan yazılım yükler: bilgisayar → adb → telefon → kutunun yönetim sayfası (/update).
# Kutu telefonun erişim noktasına bağlı olmalı. Şifre secrets.h'den okunur, ekrana yazılmaz;
# telefona konan geçici istek dosyası iş bitince silinir.
#   ./ota_via_phone.sh <adb-cihazı> <kutu-ip> <bin>
# Kutunun IP'si: telefonda "ip neigh" ya da yönetim sayfası. bin: arduino-cli compile --output-dir çıktısı.
set -e
dev="$1"; ip="$2"
bin="$3"
[ -n "$dev" ] && [ -n "$ip" ] && [ -f "$bin" ] || { echo "kullanim: $0 <adb-cihazi> <kutu-ip> <bin>" >&2; exit 1; }
adb="${ADB:-$HOME/android-dev/sdk/platform-tools/adb}"
dir="$(dirname "$0")"
tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
python3 - "$dir/elm_bridge/secrets.h" "$bin" "$tmp" <<'EOF'
import base64, re, sys
s = open(sys.argv[1]).read()
ap = re.search(r'#define AP_PASS "(.*)"', s).group(1).replace('\\"', '"').replace('\\\\', '\\')
auth = base64.b64encode(('admin:' + ap).encode()).decode()
data = open(sys.argv[2], 'rb').read()
b = '----obdota7f3'
body = ('--%s\r\nContent-Disposition: form-data; name="fw"; filename="fw.bin"\r\n'
        'Content-Type: application/octet-stream\r\n\r\n' % b).encode() + data + ('\r\n--%s--\r\n' % b).encode()
hdr = ('POST /update HTTP/1.0\r\nHost: obd-esp32\r\nAuthorization: Basic %s\r\n'
       'Content-Type: multipart/form-data; boundary=%s\r\nContent-Length: %d\r\n\r\n' % (auth, b, len(body)))
open(sys.argv[3], 'wb').write(hdr.encode() + body)
EOF
"$adb" -s "$dev" push "$tmp" /data/local/tmp/ota.req >/dev/null
# nc dosya bitince bağlantıyı kapatırsa zayıf Wi-Fi'da aktarım yarıda kalıyor: cevap gelene kadar açık tut
"$adb" -s "$dev" shell "(cat /data/local/tmp/ota.req; sleep 120) | nc -w 130 $ip 80 | head -1 & p=\$!; while kill -0 \$p 2>/dev/null; do sleep 1; done; rm -f /data/local/tmp/ota.req"
