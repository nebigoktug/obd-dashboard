# Volvo S40 (P1, 2010, 1.6D) CAN sinyalleri

Kaynak: kendi ESP32 kutumuzla OBD soketinden **yalnız dinleyerek** alınan kayıtlar.
- **e (motor hattı):** HS-CAN, 500 kbit/s, OBD pin 6/14.
- **m (konfor hattı):** MS-CAN, 125 kbit/s, OBD pin 3/11.

Tüm kimlikler 29 bit. Bayt numaraları 0'dan başlar (b0 = ilk bayt). "Darbe", tuşa basılınca kısa süre değişip geri dönen bit demek.

Doğruluk etiketleri:
- **KESİN:** kullanıcının yaptığı işlemle saat saat eşleşti.
- **KUVVETLİ:** tek kanıt var ama tutarlı.
- **ADAY:** daha doğrulanmadı.

Referanslar: github.com/johnbutol/CCM-busmaster, github.com/Alfaa123/Volvo-CAN-Gauge (Codes.txt). Kimlikler bizim kayıtlarla birebir aynı.

## Motor hattı (e)

| Kimlik | Bayt | Anlam | Durum |
|---|---|---|---|
| 19E00006 | b4 | Hararet, °C = X − 60 (OBD 0105 ile r = 0.9999) | KESİN |
| 19000026 | b6 | Sinyal kolu: 85 boşta, A5 sol, E5 sağ (0x08 sayaç biti) | KESİN |
| 19000026 | b5 | Far kolu: 0x04 hafif çekili (selektör), 0x08 sonuna kadar (uzun aç/kapa), 0x10 kol serbest; 0x02 ~1 Hz değişen, bilinmiyor | KESİN |
| 19000026 | b7 (b1 = tersi) | Hız sabitleyici: 04 CRUISE, 40 +, 80 −, 20 "0", 10 dönüş | KESİN |
| 1AE0092C | b5 | Ön cam buz çözmede D1 → A0 (A/C isteği?) | ADAY |
| 18DAF110 | — | OBD cevapları (motor beyni) | — |

## Konfor hattı (m)

### Gösterge, gövde

| Kimlik | Bayt | Anlam | Durum |
|---|---|---|---|
| 16801028 | b3 | Hararet, °C = X − 60 | KESİN |
| 13F041BA | b2–b3 | Saat: gece yarısından beri dakika = ((b2 & 7) << 8) \| b3 | KESİN |
| 13F041BA | b1 | 0x20 = sürücü kapısı açık (DE kapalı, FE açık) | KESİN |
| 065373BA | b3 | bit0 = 0 iken sürücü kapısı açık | KUVVETLİ |
| 0C505226 | b4–b6 | Kilometre sayacı, büyük endian, km | KESİN |
| 021204B8 | b1 | Sinyal lambaları: 0x08 sol, 0x10 sağ, 0x20 yanıyor | KESİN |
| 1760E008 | b2 | 0x08 = el freni çekili (8C çekili, 84 indirilmiş) | KESİN |
| 09C050B8 | b0 | Gösterge aydınlatma seviyesi (tekerlek), 4F normal | KESİN |
| 03C3F7FC | b5 | 0x40 darbe = READ tuşu (sol kol, yol bilgisayarı) | KESİN |
| 19F010FC | b2 | Hız sabitleyici tuşlarının kopyası (40 / 20 / 10) | KUVVETLİ |
| 19F010FC | b7 | Dış sıcaklık? (= 1C810066 b5); 0x2D iken gösterge 11 °C → X/2 − 11 civarı | ADAY |
| 1EF00066 / 1AD10068 | b3 | Yakıt seviyesi? (gösterge ~1/3 iken 0x2C) | ADAY |

### Klima (manuel, 12404002)

| Bayt | Anlam | Durum |
|---|---|---|
| b0 = b1 | Sıcaklık düğmesi konumu; tık başına ±2 (0x10 orta) | KESİN |
| b4 alt dört bit | Sıcaklık düğmesi konumu (8..B); 0x20 darbe = fan düğmesi | KESİN |
| b3 alt beş bit | Fan seviyesi; ön cam buz çözmede 0x1F; 0x20 darbe = sıcaklık düğmesi | KESİN |
| b7 | Hava yönü: 0x10 yüz, 0x08 gövde, 0x04 ayak (birleşebilir); 0x20 tuş darbesi | KESİN |

| Kimlik | Bayt | Anlam | Durum |
|---|---|---|---|
| 1C810066 / 16801028 | b0 0x40 / b1 bit0 | Arka cam ve ayna rezistansı açık (12–20 dk sonra kendiliğinden kapanır) | KESİN |
| 1C810066 | b6 | İç hava açık = C8 | KESİN |
| 1C810066 | b2 | A/C? (D1 / A0) | ADAY |
| 12404002 | b4 | 0x80 / 0x40: A/C ON / OFF? (OFF→ON basışında 48 → 88) | ADAY |
| 04A0409E | b2 | 0x06 deseni (1E→1A→1C→18): klima panelinde HERHANGİ bir tuş (koltuk ısıtma, AC, iç hava); hangi tuş olduğu yok | KESİN |

### Orta panel tuşları (05704000)

b0'ın üst iki biti 50 ms'lik sayaç (00, 40, 80, C0). Tuşlar iki bitlik desenle görünür: basınca 11 → 01 → 10 → 00.

| Tuş | Bayt / bit | Durum |
|---|---|---|
| VOLUME bas (aç/kapa) | b4 0xC0 | KESİN |
| VOLUME çevir | b1 0xC0 / 0x40 sağ, 0x80 sol | KESİN |
| TUNING çevir | b1 0x10, yön b2'de (0x80 sağ, 0x01 sol) | KUVVETLİ |
| TUNING bas (SOUND) | b3 0x0C | KESİN |
| EXIT | b4 0x03 | KESİN |
| PHONE | b4 0x30 | KESİN |
| MENU | b4 0x0C | KESİN |
| AM/FM | b5 0x03 | KESİN |
| MODE | b5 0x0C | KESİN |
| ⏏ | b5 0x30 | KESİN |
| ENTER | b5 0xC0 | KESİN |
| Yön ▲ ▼ ◀ ▶ | b7 0x01 / 0x02 / 0x04 / 0x08 | KESİN |

### Rakam tuşları (04A0409E)

b6 0x04 = tuş olayı. b5: 02 "1", 04 "2", 08 "3", 01 "0". b4: 04 AUTO, 08 SCAN. Diğer rakamlar denenmedi.

### Çözülemeyenler

- **Direksiyon ses ±, ileri/geri:** dört tuşta da yalnız 08E2300E b3 0x40 darbesi var. Tuşu ayıran bayt bulunamadı.
- **Koltuk ısıtma:** durumu hatta yayınlanmıyor (test3, kayıpsız). Basışta yalnız 04A0409E b2 "panel tuşu" darbesi var.
- **Işık düğmesi (0 / park / far), ön ve arka sis, yakıt kapağı:** test3'te kayıpsız kayıtta da sinyal yok; bu hatlarda yayınlanmıyor.
- Ortak desen: direksiyon ses tuşları ve klima panelinin bir kısmı yalnız "bir tuşa basıldı" darbesi bırakıyor; tuşun kimliği hatta yok.
