# CAN kayıtları: Volvo S40 (P1, 2010, 1.6D, manuel klima)

Kendi ESP32 kutumuzla OBD soketinden **yalnız dinleyerek** alınan ham kayıtlar. Çözülen sinyaller [docs/can-sinyalleri.md](../docs/can-sinyalleri.md) içinde. İncelemek için [tools/can_tool.py](../tools/can_tool.py) kullanılabilir.

## Biçim

Satır başına bir çerçeve: `<ms> <kimlik> <veri hex> [m]`.
- **ms:** kutunun açılışından beri geçen süre.
- **kimlik:** 29 bit.
- **m:** satır sonunda varsa konfor hattı (MS-CAN, 125 kbit/s, pin 3/11), yoksa motor hattı (HS-CAN, 500 kbit/s, pin 6/14).
- `#` ile başlayan satırlar açıklama. Bunlardan biri kutunun 5 saniyede bir yazdığı düşen çerçeve sayacı (`# <ms> dusen N kuyruk M`, son testte var).
- 18DAF110 satırları, uygulamanın standart OBD sorgularına motor beyninin verdiği cevaplar.

**Gizlilik:** 1BE0493C ve 1093F7FC çoklu mesajları şase numarasını (VIN) taşıyor. b0 = 10/11/12 çerçevelerindeki harf ve rakamlar `X` (0x58) ile değiştirildi. Başka bir değişiklik yok.

## Dosyalar

| Dosya | Ne | Saat eşleme (ms = duvar saati) |
|---|---|---|
| 2026-10-15_1_surus.txt.gz | İlk sürüş, soğuk motor, ~20 dk (9 bağlantı parçası, `# ---` ile ayrılmış). Eski yazılım: ~%30 çerçeve kaybı, 1–1.5 sn boşluklar | 75600 = 08:24:28 |
| 2026-10-15_2_buton-testi.txt.gz | Park, rölanti, ~70 adımlı buton testi; 19:39:28'de kesildi (klima kısmı yok). ~%30 kayıp | 60300 = 19:35:11 (kapı açıldı) |
| 2026-10-15_3_tekrar-testi.txt.gz | Park, eksiklerin tekrarı; 20:11:26–52 arası boşluklar. ~%30 kayıp | 221286 = 20:09:12 (sol sinyal) |
| 2026-10-15_4_son-test.txt.gz | Park, yeni yazılım: kayıp yalnız 21:26:20–26 (telefon Wi-Fi takılması) | 18267 = 21:25:46 (sol sinyal) |

Kullanıcı dokunuş saatleri tuşa basıştan birkaç saniye sapabilir; en güvenilir eşleme yukarıdaki işaretlerdir.

## Test adımları (kullanıcının dokunuş saatleri)

**Buton testi (2):**
- 19:35:03 sol sinyal · 19:35:07 sağ sinyal · 19:35:11 kapı aç · 19:35:17 kapı kapat · 19:35:26 el freni indir · 19:35:30 el freni kaldır
- 19:35:37 selektör (düğme 0) · 19:35:40 park lambası · 19:35:45 farlar · 19:35:52 selektör · 19:35:59 uzun aç · 19:36:02 uzun kapat · 19:36:06 düğme 0
- 19:36:12 ön sis · 19:36:20 arka sis · 19:36:27 gösterge ışığı tekerleği · 19:36:35 yakıt kapağı
- 19:36:43 yol bilgisayarı tekerleği ileri · 19:36:46 geri · 19:36:57 READ
- 19:37:02 ses + · 19:37:05 ses − · 19:37:07 ileri · 19:37:10 geri (her biri 3 kez)
- 19:37:16 CRUISE · 19:37:18 + · 19:37:20 − · 19:37:22 0 · 19:37:24 dönüş
- 19:37:33 VOLUME bas · 19:37:36 VOLUME bas · 19:37:43 VOLUME sağa 3 · 19:37:46 sola 3 · 19:37:51 TUNING sağa 3 · 19:37:54 sola 3 · 19:37:57 TUNING bas · 19:38:01 EXIT
- 19:38:11 AM/FM · 19:38:14 MODE · 19:38:16 ⏏ · 19:38:20 1 · 19:38:22 2 · 19:38:26 3 · 19:38:29 0 · 19:38:33 AUTO · 19:38:36 SCAN
- 19:38:38 PHONE · 19:38:41 MENU · 19:38:47 ▲ · 19:38:49 ▼ · 19:38:52 ◀ · 19:38:54 ▶ · 19:38:58 ENTER · 19:39:01 EXIT
- 19:39:08 iç hava · 19:39:16 ön cam · 19:39:20 arka cam (bundan sonrası kayıtta yok)

**Tekrar testi (3):**
- 20:09:12 sol sinyal · 20:09:19 sağ sinyal · 20:09:28 selektör (düğme 0) · 20:09:32 farlar · 20:09:39 uzun aç · 20:09:45 uzun kapat · 20:09:48 selektör · 20:09:51 düğme 0
- 20:09:58 ses + · 20:10:01 ses − · 20:10:08 ileri · 20:10:10 geri · 20:10:17 ses + basılı 2 sn · 20:10:24 ◀ · 20:10:28 EXIT
- 20:10:36 iç hava · 20:10:41 ön cam · 20:10:44 arka cam · 20:10:56 AC
- 20:11:07 hava yüze · 20:11:13 gövdeye · 20:11:20 ayağa
- 20:11:28–33 sürücü koltuk 3 basış · 20:11:37–40 yolcu koltuk 3 basış
- 20:11:50 fan +2 · 20:11:56 fan −2 · 20:12:02 sıcaklık +3 · 20:12:05 sıcaklık −3 · 20:12:11 sol sinyal

**Son test (4):**
- 21:25:46 sol sinyal
- 21:25:59 / 26:04 / 26:08 sürücü koltuk 1./2./3. basış · 21:26:11 / 26:15 / 26:19 yolcu koltuk
- 21:26:25 iç hava aç · 21:26:30 kapat · 21:26:36 AC 1. basış · 21:26:41 2. basış (AC önce OFF sonra ON)
- 21:26:52 park lambası · 21:27:02 düğme 0 · 21:27:09 farlar · 21:27:16 ön sis aç · 21:27:21 kapat · 21:27:27 arka sis aç · 21:27:31 kapat · 21:27:38 düğme 0 · 21:27:44 yakıt kapağı
- 21:27:53 ses + · 21:27:58 ses − · 21:28:04 ileri · 21:28:09 geri · 21:28:13 sol sinyal
- Gösterge 21:25: dış 11 °C, yakıt ~1/3, km 277441
