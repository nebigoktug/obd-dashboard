#!/bin/sh
# ~/.config/obd-dashboard/esp32-wifi.txt → secrets.h (depoya girmez). Satırlar: SSID, şifre, OBD-ESP32 ağ şifresi.
set -e
src="$HOME/.config/obd-dashboard/esp32-wifi.txt"
dir="$(dirname "$0")"
grep -v '^#' "$src" | sed '/^[[:space:]]*$/d' > "$dir/.wifi.tmp"
n=$(wc -l < "$dir/.wifi.tmp")
[ "$n" -ge 3 ] || { rm -f "$dir/.wifi.tmp"; echo "esp32-wifi.txt: 3 satir gerekli (SSID, sifre, AP sifresi)" >&2; exit 1; }
esc() { sed -n "$1p" "$dir/.wifi.tmp" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
ap=$(esc 3)
[ ${#ap} -ge 8 ] || { rm -f "$dir/.wifi.tmp"; echo "AP sifresi en az 8 karakter olmali" >&2; exit 1; }
umask 077
printf '#pragma once\n#define HOTSPOT_SSID "%s"\n#define HOTSPOT_PASS "%s"\n#define AP_PASS "%s"\n' "$(esc 1)" "$(esc 2)" "$ap" > "$dir/secrets.h"
rm -f "$dir/.wifi.tmp"
echo "secrets.h hazir"
