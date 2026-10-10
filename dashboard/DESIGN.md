# OBD Dashboard — Tasarım Belgesi

Durum: taslak · 2026-10-07 · Görsel: `mockup.html` (tarayıcıda aç, yatay)

Birinci öncelik: veri **kesintisiz** ve **gecikmesiz** aksın. Bütün kararlar buna göre verildi.

## 0. Alınan kararlar (2026-10-07)

| Konu | Karar | Sonuç |
|---|---|---|
| Mimari | **Native, köprüye gömülü** (§1 seçenek b) | Kotlin `ObdEngine` + Canvas göstergeler, tek APK |
| Amaç | Motor sağlığı, performans, yakıt ekonomisi, sürüş kaydı (hepsi) | Sürüş ekranında üç blok: sağlık, performans, ekonomi. Detay ekranında tüm PID'ler |
| Araç | **2010 Volvo S40 1.6 dizel** (PSA DV6 motor) | Protokol CAN 11/500 bekleniyor (`ATSP6` ile başla). Dizel uyarlamaları aşağıda |
| Yerleşim | **Yatay + dikey**, telefonun yönüne göre otomatik | Göstergeler yerleşimden bağımsız bileşenler. İki ayrı layout |
| Başlatma | **Elle** (Başlat butonu) | Otomatik tetik yok. Uygulama açılınca son cihaz seçili gelir |
| Kayıt | **Her sürüşte otomatik** | Bağlantı kurulunca yeni oturum dosyası açılır, 30 günden eski kayıtlar silinir |
| Arıza kodları | **Oku + onaylı sil** | Mode 03/07 okuma. Mode 04 yalnız hız = 0 iken ve çift onayla |
| Tema | **Otomatik (ışık sensörü)** + elle seçim | Gece OLED siyah, gündüz yüksek kontrast |
| Vites önerisi | Aracın **kendi** yükselt/düşür önerisi (ECM hesaplıyor, panele CAN'dan gidiyor) | Kendi önerimizi uydurmak yerine aracınkini göstereceğiz; sürüş keşif kaydıyla çözülecek |
| Şanzıman | **Manuel** | ECU vites bildirmez. **Vites tahmini** = devir / hız oranı; oranlar sürüşte otomatik öğrenilir (kümeleme), debriyaj basılıyken ya da oran kararsızken "–". Vites ışığı devir eşiğine göre çalışır |

### Gerçek ölçüm (2026-10-08, `probe-reports/`)
| | Sonuç | Tasarıma etkisi |
|---|---|---|
| Adaptör | "ELM327 v2.1" klonu | Kısa cevaplara eski tampon baytları ekliyor. Çok çerçeveli cevaba cevap sayısı eki verilince kilitleniyor. İkisi için de motorda koruma var |
| Protokol | **ISO 15765-4 CAN 29-bit / 500k** (#7) | Motor ECU adresi `18DA10F1`, fonksiyonel adres `18DB33F1` |
| İstek süresi | ~170 ms (medyan), ek ile ek olmadan aynı | Devir **~4–6 Hz** gerçekçi. 3 PID'lik çoklu istek ~200 ms |
| Desteklenen PID | 04, 05, 0B, 0C, 0D, 0F, 1C, 21, 23 | **Pedal, MAF, atmosfer basıncı, yakıt seviyesi, yakıt debisi yok** |
| Arıza kodu | P0341, U300B kayıtlı, MIL kapalı | Arıza kodu ekranı ilk gün işe yarayacak |
| VIN | Ters gönderiliyor | Ayrıştırıcı düzeltiyor |

Sonuçlar:
- Performans bloğunda pedal yerine **motor yükü** (04) gösterilecek.
- **Turbo basıncı** = MAP − rölantideki MAP (atmosfer PID'i yok). Rölantide MAP 105 kPa okundu.
- **Yakıt tüketimi standart PID'lerden hesaplanamıyor** (MAF ve 5E yok). Seçenekler:
  1. Volvo/PSA'ya özel değer: Araç testi 29-bit adresle tekrar denenecek.
  2. Yük × devir ile kaba bir tahmin, depo doluşlarıyla kalibre edilerek.
  3. Ekonomi bloğunu kaldırmak.

  Karar kullanıcıda.

### Dizel / Volvo S40 uyarlamaları
- Devir kırmızı bölgesi **4500 rpm**'de başlar (sarı 4000). Bu değer ayarlanabilir.
- Gaz kelebeği (`11`) yerine **gaz pedalı konumu** (`49`/`5A`) gösterilir. Hangisi destekleniyorsa o kullanılır.
- **Turbo basıncı ≈ MAP (`0B`) − atmosfer basıncı (`33`)**. Bu dolaylı bir hesap; `33` desteklenmiyorsa
  kontak açılıp motor çalışmadan önce okunan MAP değeri referans alınır.
- **Yakıt tüketimi:** dizel değişken ve fakir karışımla çalıştığı için MAF'tan tüketim hesabı güvenilmez.
  Kullanılacak kaynaklar, öncelik sırasıyla:
  1. Yakıt debisi (`5E`) destekleniyorsa onu kullan. 2010 model için düşük ihtimal.
  2. Yoksa **kalibre edilen tahmin** kullan: kaba bir MAF tahmini, depo doldurulunca girilen litre ve km ile
     düzeltme katsayısına çevrilir. Ekranda "tahmini" etiketi gösterilir.
- Yakıt seviyesi (`2F`) Volvo'da desteklenmeyebilir. Aşama 0'da kontrol edilecek.
- DPF, enjektör düzeltmesi ve gerçek turbo basıncı üreticiye özel mode 22 PID'leri gerektirir.
  Bunlar kapsam dışı, ileride ayrı bir aşamada ele alınacak.

---

## 1. Mimari

### Mevcut durum
```
ELM327 (BT SPP) ──RFCOMM── BridgeService ──TCP 127.0.0.1:35000 (tek istemci)── python-OBD (Termux)
```
Köprü ham bayt taşıyor, OBD bilmiyor. Tek istemci var: yeni bağlanan eskisini düşürüyor. Yani dashboard ile logger
**ayrı ayrı bağlanamaz**. Biri bağlantının tek sahibi olmalı, veriyi diğerlerine o dağıtmalı.

### Seçenekler

| | (a) Termux Python + WebSocket + tarayıcı/WebView | (b) Native: toplayıcı köprü uygulamasının içinde | (c1) Hazır uygulama (RealDash/Torque → :35000) | (c2) Native toplayıcı + HTML UI (WebView) |
|---|---|---|---|---|
| Gecikme zinciri | BT → TCP → Python → WS → JS | BT → Kotlin → View (aynı süreç) | BT → TCP → uygulama | BT → Kotlin → JS köprüsü |
| Ek gecikme (BT hariç) | ~1–3 ms (python-OBD sorgu başına ~0,9 ms ölçüldü) + WS | < 1 ms | bilinmiyor | ~1–2 ms |
| Süreç ömrü | **Zayıf.** Termux + tarayıcı + köprü = 3 süreç. Android pil yönetimi Termux'u ya da arka plandaki tarayıcıyı öldürebilir. Kontak açılınca otomatik başlatmak zor (Termux:Boot, wake-lock, `termux-wake-lock`). | **Güçlü.** Ön plan servisi, wake lock ve BT yeniden bağlanma zaten var. Tek süreç. | Uygulamaya bağlı | Güçlü (b ile aynı) |
| Tek istemci kısıtı | Python toplayıcı tek sahip olur, WS ile dağıtır. Çözülür. | Toplayıcı BT soketinin doğrudan sahibi olur. TCP'ye hiç gerek kalmaz. | **Çözülmez.** RealDash bağlıyken logger bağlanamaz. | (b) ile aynı |
| Geliştirme hızı | En hızlı (python-OBD hazır, UI = mockup) | Orta (ELM ayrıştırıcı + Canvas göstergeler, ~1500 satır Kotlin) | Kod yok | Orta |
| UI akıcılığı | İyi (Canvas/SVG 60 fps), WebView/Chrome sürümüne bağlı | En iyi (Choreographer, donanım hızlandırmalı Canvas) | İyi | İyi |
| Kurulum | Termux, pip, betik, tarayıcı | Tek APK | Ücretli/kapalı | Tek APK |

### Öneri: (b) Native toplayıcı, köprü uygulamasıyla birleşik

```
                    ┌──────────────────── OBD Köprü uygulaması (tek süreç, ön plan servisi) ───────────────────┐
ELM327 ──BT SPP──►  │ BtLink (mevcut btLoop)  ──►  ObdEngine (sorgu zamanlayıcı + ELM ayrıştırıcı)               │
                    │                                   │  publish(Sample)                                     │
                    │              ┌────────────────────┼─────────────────────┬──────────────────────┐        │
                    │              ▼                    ▼                     ▼                      ▼        │
                    │      DashboardActivity       CsvLogger           JSON akışı (opsiyonel)   Ham TCP :35000  │
                    │      (Canvas, 60 fps)      (ayrı thread)        127.0.0.1:35001, çok      (uzman modu:    │
                    │                                                  okuyucu, salt-okunur     bağlanınca     │
                    │                                                  → Termux/HTML)           Engine durur)  │
                    └───────────────────────────────────────────────────────────────────────────────────────────┘
```

Gerekçe:
1. **Tek sahip.** `ObdEngine`, BT soketini doğrudan kullanır. TCP katmanı ve tek istemci yarışı ortadan kalkar.
   UI, CSV ve dış okuyucular aynı `Sample` akışını dinler. Hiçbiri adaptöre ayrıca sorgu atmaz.
2. **Süreç ömrü zaten çözülmüş.** Ön plan servisi, `PARTIAL_WAKE_LOCK`, pil muafiyeti ve 3 sn'lik BT yeniden
   bağlanma döngüsü hazır. Termux'a bağımlılık kalmaz.
3. **Gecikme.** Sorgu cevabından ekrana kadar < 1 ms + bir sonraki vsync. Darboğaz yalnızca BT ve ECU olur.
4. **Ham köprü korunur.** Torque, RealDash ya da python-OBD ile deneme yapmak için :35000 kalır. Ham istemci
   bağlandığında `ObdEngine` sorgulamayı durdurur ve UI'da "Harici uygulama bağlı" yazar. İstemci kopunca
   Engine kaldığı yerden devam eder. Böylece iki taraf aynı adaptöre aynı anda yazıp birbirinin cevabını bozamaz.
5. **JSON akışı** (`:35001`, çok istemcili, salt okunur) Termux betiklerine ve HTML tabanlı ekranlara kapıyı
   açık tutar. Bu yüzden (a) ve (c2) sonradan da eklenebilir; Engine yazmak gerekmez.

**UI teknolojisi:** düz `View` + özel `Canvas` çizimi (`GaugeView`, `BarGaugeView`, `StatusBar`). Bu, mevcut
"Compose yok" çizgisine uyar. Göstergeler basit şekiller (yay, çubuk, metin). Choreographer ile 60 fps
çizim sorunsuzdur. Mockup'taki HTML tasarımı birebir referans olarak kullanılır.

> Alternatif yol: Kotlin yerine önce **(a)** ile bir prototip yapmak. Zamanlayıcı algoritması Python'da hızlı
> denenir ve gerçek adaptörde ölçülür (Aşama 0). Ama kalıcı çözüm olarak (a) önerilmez, çünkü 3 süreçli
> zincir arabada "bazen açılmıyor / arka planda ölmüş" sorunlarına çok açıktır.

---

## 2. Düşük gecikmeli sorgulama stratejisi

### 2.1 Başlatma dizisi
```
ATZ            (sıfırla; ~1 sn bekle. Klonlarda ATWS daha hızlı)
ATE0           yankı kapalı        → cevapta komut tekrarı yok, daha az bayt
ATL0           satır besleme yok
ATS0           boşluk yok          → "410C1AF8" (11 yerine 8 bayt)
ATH0           başlık yok (yalnız teşhis sırasında ATH1)
ATAT1          adaptif zamanlama (varsayılan). ATAT2 agresif, denenir; NO DATA artarsa AT1'e dön
ATSP<n>        önceki oturumda bulunan protokol kayıtlıysa doğrudan (ör. ATSP6). Yoksa ATSP0 + 0100 ve ATDPN ile öğren
ATSH7E0        (CAN) fonksiyonel 7DF yerine doğrudan motor ECU'suna sor → tek cevap, başka ECU beklenmez
ATCRA7E8       (CAN, opsiyonel) yalnızca motor ECU cevaplarını kabul et
ATST19         cevap gelmezse bekleme üst sınırı ≈ 100 ms (varsayılan 0x32 ≈ 200 ms). Yalnız "NO DATA" durumunu kısaltır
0100/0120/0140 desteklenen PID bit maskeleri → istek listesi buna göre süzülür
```
Protokolü kaydetmek önemli: `ATSP0` araması ilk bağlantıda 1–5 sn sürer. Kayıtlı `ATSP6` ile bu süre
yeniden bağlanmada ~0'a iner.

### 2.2 Cevap sayısı ("fast mode")
`010C` gönderilince ELM, başka ECU da cevap verir diye zaman aşımı kadar (adaptif, tipik 20–100 ms) bekler.
`010C1` yazılırsa ilk cevaptan sonra beklemeden döner. Ucuz klonlarda **en büyük kazanç budur**.
Kullanım: v1.3+ ELM'lerde destekleniyor. Klonda `?` dönerse özellik kapatılır. (python-OBD `fast=True`
bunu yalnızca aynı komut art arda tekrarlanırsa yapıyor, karışık sorguda faydası az.)

### 2.3 Tek istekte çoklu PID (CAN, mode 01, en fazla 6 PID)
`010C0D11` tek istekte RPM, hız ve gaz kelebeğini ister. Cevap ISO-TP çok çerçeveli gelebilir:
```
008
0: 410C189F0D33
1: 1172
```
Ayrıştırıcı çerçeveleri birleştirip `41` sonrası `[PID][veri]` çiftlerini PID tablosundaki uzunluklarla okur.
Riskler:
- Bazı ECU'lar çoklu PID'i desteklemez ya da yalnız bir kısmını cevaplar.
- Bazı klonlar çok çerçeveli cevapta bozulur (`BUFFER FULL`, kesik satır).
- Cevap sayısı ekiyle birleşimi (`010C0D1`) tutarsızdır. Emülatör `?` döndürdü (aşağıya bakın).

**Strateji:** bağlantı başında 3 aşamalı yetenek testi yapılır ve sonuç adaptör MAC'ine göre kaydedilir:
1. `010C0D` iki PID'i de doğru döndürüyor mu?
2. 4 PID'lik istek çok çerçeveli olarak doğru ayrışıyor mu?
3. `010C1` çalışıyor mu?

Sonuca göre üç moddan biri seçilir: `MULTI` (çoklu PID), `SINGLE_FAST` (tek PID + "1"), `SINGLE` (yalın).
Çalışırken art arda 3 ayrıştırma hatası olursa bir alt moda düşülür.

### 2.4 Öncelik katmanları (zamanlayıcı)
Her PID'in bir hedef periyodu var. Zamanlayıcı her turda "gecikme oranı" (`(now - lastTs) / period`) en
yüksek olanları seçer. `MULTI` modda 6 PID'e kadar tek isteğe paketler. Sabit sıra yerine bu yöntem, yeni
PID eklenince de katmanların kendiliğinden dengelenmesini sağlar.

| Katman | PID'ler | Hedef periyot | Not |
|---|---|---|---|
| A — anlık | RPM `0C`, hız `0D`, gaz `11` | mümkün olan en sık | Her istekte en az biri |
| B — sürüş | motor yükü `04`, MAP `0B` / MAF `10`, ateşleme avansı `0E` | 250–500 ms | |
| C — yavaş | soğutucu `05`, emme havası `0F`, yakıt seviyesi `2F`, ortam `46` | 2 s | Termal değerler yavaş değişir |
| D — adaptör | akü voltajı `ATRV` | 2 s | ECU trafiği yok, kontak kapalıyken de çalışır |
| E — nadir | DTC sayısı `0101`, desteklenen PID'ler | bağlanınca + 60 s | |

### 2.5 Gerçekçi yenileme hızları (ucuz v1.5 BT klonu, CAN aracı) — tahmin
Bir isteğin süresi = BT SPP gidiş-dönüş (Android'de 15–40 ms) + ECU cevabı (5–25 ms) + ELM bekleme.

| Mod | İstek süresi | İstek/sn | RPM güncelleme | Tüm 10 PID'in turu |
|---|---|---|---|---|
| `SINGLE` (varsayılan ST, ek yok) | 80–200 ms | 5–12 | 2–5 Hz | 1–2 s |
| `SINGLE_FAST` (`010C1`, ATAT1) | 35–70 ms | 14–28 | **6–12 Hz** | 0,5–0,8 s |
| `MULTI` (3–6 PID/istek) | 45–90 ms | 11–22 | **10–20 Hz** | 0,2–0,4 s |
| Mevcut `obd_log.py` | 5 sıralı sorgu + 1 sn uyku | | 1 Hz | 1 s |

Beklenen: **RPM ve hız için 8–15 Hz, sıcaklıklar için 0,5 Hz.** UI 60 fps interpolasyonla bu hızı akıcı gösterir
(bkz. §3.4). K-Line/ISO 9141 araçlarda istekler arası 55 ms zorunlu boşluk yüzünden hız 3–6 Hz'e düşer ve
çoklu PID olmaz.

### 2.6 Ölçüm (emülatör, 127.0.0.1:35000, CAN 11/500 "A6")
Ham soket, TCP_NODELAY, ATE0/L0/S0/H0, 100'er istek:

| Komut | Medyan | p95 | Cevap |
|---|---|---|---|
| `010C` | 0,74 ms | 1,42 ms | `410C1287` |
| `010C1` | 0,72 ms | 1,41 ms | aynı (emülatör zaten beklemiyor) |
| `010C0D` (2 PID) | 1,21 ms | 3,34 ms | `410C23820D1D` tek çerçeve |
| `010C0D11` (3 PID) | 1,55 ms | 1,77 ms | 2 çerçeve ISO-TP (`008 / 0: … / 1: …`) |
| `010C0D110405` (5 PID) | 2,07 ms | 2,33 ms | 2 çerçeve |
| `010C0D1` (çoklu + sayı) | — | — | **`?`**: emülatör desteklemiyor |
| python-OBD `query(RPM)` | 1,60 ms | 2,05 ms | python-OBD'nin sorgu başına ek yükü ≈ 0,9 ms |
| Öncelikli döngü A:C = 3:1, 3 sn | 1216 istek/sn | | A PID'leri 304 Hz, C 101 Hz |

Çıkarımlar: (1) Ayrıştırıcı ve ISO-TP birleştirme emülatörle doğrulanabilir. (2) Mutlak süreler gerçek
adaptörden ~50–100 kat hızlı, zamanlama sonuçları için **kullanılamaz**. Test için `ObdEngine`'in önüne
yapay gecikme ekleyen bir TCP proxy (istek başına 40 ± 15 ms, %1 `NO DATA`, ara sıra bağlantı kopması)
konmalıdır. (3) Yazılım tarafının ek yükü (Python dahil) BT'nin yanında ihmal edilebilir.
Gecikmenin tamamı adaptörde ve araçtadır.

### 2.7 Kopma, toparlanma ve "veri bayat"
- **İstek bekçisi:** `>` istemi 1 s içinde gelmezse adaptöre tek karakter (`\r`) gönderilir. ELM işi
  `STOPPED` ile keser. 3 ardışık zaman aşımında BT soketi kapatılır ve mevcut yeniden bağlanma döngüsü devreye girer.
- **Hata kodları:** `NO DATA` (PID'in bu turda cevabı yok, sayaç artar; 5'te bir PID 10 sn askıya alınır),
  `CAN ERROR` / `BUS INIT…ERROR` / `UNABLE TO CONNECT` (kontak kapalı olabilir; ATRV ile kontrol edilir,
  2 sn arayla `0100` denenir), `BUFFER FULL` (çoklu PID'den düşülür), `?` (yetenek kapatılır).
- **Durum makinesi:** `BT_YOK → BT_BAĞLANIYOR → ADAPTÖR_HAZIR → ECU_ARANIYOR → CANLI ↔ KONTAK_KAPALI`, ayrıca `HARİCİ_İSTEMCİ`.
  Her geçiş UI'a `status` mesajı olarak gider.
- **Bayatlık PID başınadır:** `age = now - ts`. `age > max(3 × beklenen periyot, 500 ms)` olunca değer
  **bayat** sayılır (gösterge soluklaşır, ibre yerinde kalır, "⟳" rozeti çıkar). `age > 5 s` ise değer
  `—` ile gösterilir. Bayat bir RPM'i asla sıfıra düşürme: sürücü bunu motor durdu sanar.
- **Bağlantı kopunca** tüm göstergeler aynı anda soluklaşır, üst çubuk kırmızıya döner ve
  "Bağlantı koptu · yeniden deneniyor (3)" yazar. Son değerler görünmeye devam eder.

---

## 3. UI tasarımı

Mockup: `mockup.html`. Yatay telefon (20:9, ~ 800×360 dp). Kolun uzanabileceği mesafeden okunabilir olmalı.

### 3.1 Yerleşim — "Sürüş" ekranı
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ ● Canlı · 14 Hz · CAN    Sürüş | Detay          ⏺ KAYIT 12:04     ☾  14:32   │  ← durum çubuğu (32 dp)
├───────────────────────────┬───────────────────────┬──────────────────────────┤
│      ╭───────────╮        │                       │  SOĞUTUCU   ▮▮▮▮▮▮▯▯ 92°C │
│    ╭─  RPM yayı   ─╮      │        87             │  EMME HAVASI ▮▮▮▯▯▯ 34°C  │
│   │    3 450       │      │       km/h            │  GAZ         ▮▮▮▮▯▯ 42%   │
│   │   ×1000 rpm    │      │                       │  AKÜ         14.2 V       │
│    ╰───────────────╯      │  ─── yük çubuğu ───   │  MOTOR YÜKÜ  ▮▮▮▮▯▯ 55%   │
└───────────────────────────┴───────────────────────┴──────────────────────────┘
```
- **Sol: devir yayı.** 240° yay, kırmızı bölgeli. Ortada büyük rakam. Kırmızı bölgede yay kenarı yanıp söner
  (vites ışığı).
- **Orta: hız.** Ekranın en büyük rakamı (≈ 140 px yükseklik), tabular rakamlar, sabit genişlik
  (rakamlar değişince yer oynamaz).
- **Sağ: durum çubukları.** Etiket, değer ve eşik renkli yatay çubuk. Bir bakışta "her şey normal mi?"
  sorusunu cevaplar.
- **Detay ekranı:** tüm PID'ler için ızgara kartları. Her kartta son 60 sn'lik mini grafik, değer, birim,
  güncelleme hızı (Hz) ve yaş (ms) bulunur. Teşhis ve meraklı kullanım içindir, sürüş sırasında değil.

### 3.2 Gece / gündüz
- **Gece (varsayılan):** saf siyah arka plan (OLED, yansıma yok). Rakamlar kırık beyaz (#E8EAED).
  Vurgu rengi buz mavisi, düşük doygunluk. Kırmızı yalnız uyarı için kullanılır. Parlaklık ayrıca kısılabilir.
- **Gündüz:** açık gri zemin, siyah rakamlar, kalın ağırlık. Güneş altında kontrast ≥ 7:1.
- **Oto:** ortam ışığı sensörü (`Sensor.TYPE_LIGHT`, < 50 lux → gece) ve histerezis (geçişten sonra 30 sn kilit).
  Sensör yoksa gün doğumu/batımı saati kullanılır.

### 3.3 Eşikler ve renkler
| Gösterge | Mavi (soğuk) | Normal | Sarı | Kırmızı |
|---|---|---|---|---|
| Soğutucu °C | < 60 ("Motor ısınıyor") | 60–104 | 105–112 | > 112 (yanıp söner + tam ekran uyarı şeridi) |
| Devir rpm | — | < 5500 | 5500–6000 | > 6000 (ayarlanabilir kırmızı bölge) |
| Emme havası °C | — | < 55 | 55–70 | > 70 |
| Akü V (motor çalışırken) | — | 13,2–14,8 | 12,4–13,2 veya 14,8–15,2 | < 12,4 veya > 15,2 |
| Akü V (kontak açık, motor durmuş) | — | ≥ 12,2 | 11,8–12,2 | < 11,8 |

Renk tek başına anlam taşımaz: sarı ve kırmızıda değerin yanında ikon ve kısa metin de görünür
("SICAK", "YÜKSEK"). Renk körü sürücü de uyarıyı anlar.

### 3.4 Akıcılık
- **Değer interpolasyonu:** çizim her vsync'te yapılır. Gösterilen değer hedefe kritik sönümlü yay ile yaklaşır:
  `shown += (target - shown) * (1 - exp(-dt/τ))`. RPM için τ ≈ 70 ms, hız için ≈ 120 ms, sıcaklık için ≈ 400 ms.
  τ veri periyodunun altında tutulur, yoksa yumuşatma gecikme ekler.
- **Rakamlar:** ibre ve çubuk sürekli akar, rakam ise 10 Hz'de güncellenir (RPM 50'ye yuvarlanır). Hızlı
  titreyen rakam okunamaz.
- **Animasyon bütçesi:** yalnız değişen göstergeler yeniden çizilir. Karmaşık gölge ve bulanıklık kullanılmaz.

### 3.5 Durum çubuğu
Sol taraf: renkli nokta ve durum metni (`Canlı`, `Bağlanıyor`, `Kontak kapalı`, `Bağlantı koptu`,
`Harici uygulama bağlı`), gerçek ölçülen RPM Hz'i ve protokol. Orta: sayfa sekmeleri. Sağ: kayıt
göstergesi (⏺ ve süre; dokununca durur veya başlar), tema düğmesi ve saat.
Dokunma hedefleri ≥ 48 dp. Sürüş sırasında başka etkileşim gerekmez.

---

## 4. Veri modeli ve protokol

İç model (Kotlin):
```kotlin
data class Sample(val key: String, val pid: String, val value: Double, val ts: Long /*epoch ms*/, val reqMs: Int)
data class LinkStatus(val state: State, val proto: String?, val mode: QueryMode, val hz: Float, val retry: Int, val msg: String?)
```
Dağıtım: Engine thread'i `Sample` listesini kilitsiz bir "son değerler" tablosuna (`AtomicReferenceArray`) yazar
ve abonelere bildirir. UI her vsync'te tablodan okur; kuyruk tutmaz, böylece UI hiç geride kalmaz.
CSV yazarı ayrı thread'de, sınırlı bir kuyruktan (taşarsa en eskiyi atar) okur.

### Dış JSON akışı (`127.0.0.1:35001`, WebSocket veya satır başına bir JSON — NDJSON)
Bağlanınca bir kez `meta` mesajı gelir:
```json
{"type":"meta","v":1,"session":"2026-10-07T14:32:01Z",
 "pids":[{"key":"rpm","pid":"010C","unit":"rpm","min":0,"max":8000,"period_ms":0,
          "warn":5500,"crit":6000},
         {"key":"coolant","pid":"0105","unit":"°C","min":-40,"max":130,"period_ms":2000,
          "cold":60,"warn":105,"crit":112}]}
```
Her cevaptan sonra `data` mesajı gelir. Çoklu PID cevabı tek mesaj olur:
```json
{"type":"data","seq":18234,"ts":1791384721123,
 "v":[{"key":"rpm","value":3450.25,"ts":1791384721121},
      {"key":"speed","value":87,"ts":1791384721121}]}
```
Durum değiştiğinde ve 1 sn'de bir `status` mesajı gelir:
```json
{"type":"status","ts":1791384721500,"state":"LIVE","proto":"ISO 15765-4 CAN 11/500",
 "mode":"MULTI","hz":{"rpm":13.8,"coolant":0.5},"retry":0,"logging":true,"msg":null}
```
Kurallar:
- `unit` ve sınırlar yalnız `meta`'da gönderilir. `data` mesajı küçük tutulur.
- `age` alanı gönderilmez. Alıcı, `now - ts` olarak hesaplar (aynı cihaz, aynı saat). Böylece mesaj
  kuyrukta beklese bile yaş doğru kalır. Uzak alıcılar için `status.ts` ile saat farkı düzeltilir.
- `value: null`: PID bu turda `NO DATA` döndü. Alıcı son değeri tutar ama bayat sayar.
- Ham değerler SI biriminde ve yuvarlanmadan gönderilir. Görüntü biçimlendirmesi UI'nın işidir.
- `seq` boşluğu, alıcının mesaj kaybını fark etmesini sağlar.

CSV: geniş biçim (`ts, rpm, speed, …`) yerine **uzun biçim** (`ts_ms,key,value`). Farklı hızlardaki PID'ler boş
hücre üretmez. Dosya her oturumda yeniden açılır: `obd_YYYYMMDD_HHMMSS.csv`, 64 KB'de bir `flush`.

---

## 5. Aşamalı plan

**Durum (2026-10-07):**
- Aşama 0: ölçüm uygulamanın içinde ("Araç testi" butonu), arabada çalıştırılmayı bekliyor. Termux artık gerekmiyor.
- Aşama 1: motor yazıldı ve `BridgeService`'e bağlandı. Emülatör + gecikme proxy'si testleri geçiyor:
  - normal klon taklidinde devir ~26 Hz,
  - en kötü klonda (çoklu PID ve cevap sayısı eki yok) devir 2,7 Hz, ama yavaş PID'ler aç kalmıyor,
  - harici istemciden dönüş ~0,3 sn,
  - ölü adaptör 3 zaman aşımında algılanıyor.

  Gerçek adaptörde henüz denenmedi. Henüz yapılmayanlar: Volvo mode 22 DID'leri ve arıza kodu ekranı.

| Aşama | İçerik | Bitti sayılması için |
|---|---|---|
| **0 — Ölçüm** (½ gün) | Uygulamadaki "Araç testi": gerçek klonda `010C` / `010C1` / `010C0D` / `010C0D11` süreleri ve yetenek testi | Gerçek tablo §2.5'in yerine yazılır, mod seçimi kesinleşir |
| **1 — Engine** | `ObdEngine` (ELM ayrıştırıcı, ISO-TP birleştirme, yetenek testi, öncelik zamanlayıcı, bekçi), `BridgeService` içinde. Ham TCP modunda Engine durur. Birim testleri emülatör ve gecikme proxy'si ile yapılır | Emülatörde 1 saat kesintisiz, gecikme proxy'siyle ≥ 10 Hz RPM |
| **2 — MVP Dashboard** | `DashboardActivity`: Sürüş ekranı (devir, hız, soğutucu, gaz, akü), gece teması, durum çubuğu, bayat göstergesi, ekran açık tutma (`FLAG_KEEP_SCREEN_ON`), yatay kilit | Arabada 30 dk sürüş: donma yok, kopma sonrası ≤ 5 sn'de geri gelir |
| **3 — Kayıt** | `CsvLogger` (uzun biçim), kayıt düğmesi, oturum dosyaları, eski formata çevirici | 1 saatlik kayıt, örnek kaybı < %0,5 |
| **4 — Cila** | Gündüz/oto tema, Detay ekranı + mini grafikler, eşik ayarları, uyarı şeridi, protokol/yetenek önbelleği, kontak kapalı modu | |
| **5 — Dış akış** | `:35001` JSON/WebSocket, Termux betikleri ve HTML ekranı buradan beslenir | `mockup.html` gerçek veriyle çalışır |

### Riskler
| Risk | Etki | Önlem |
|---|---|---|
| Klon çoklu PID'i ve "1" ekini bozuk işliyor | Hız 2–3 kat düşer | Yetenek testi + otomatik alt moda düşme. Hedef en kötü durumda 5 Hz |
| Android, servisi veya BT'yi uyutuyor (Doze, OEM pil yöneticileri — Xiaomi/Samsung) | Akış kesilir | Ön plan servisi + wake lock + pil muafiyeti mevcut. OEM listesi için kullanıcıya rehber. Ekran açık dashboard'da risk düşük |
| BT SPP gecikmesinde sıçramalar (Wi-Fi 2,4 GHz ile çakışma) | Ara sıra 200–500 ms boşluk | İnterpolasyon kısa boşlukları gizler. Bayatlık eşiği periyoda göre ayarlanır |
| K-Line (eski araç) | 3–6 Hz, çoklu PID yok | `SINGLE` mod. UI τ büyütülür |
| Ham TCP istemcisi ile Engine çakışması | Bozuk cevaplar | Ham istemci bağlıyken Engine durur (§1). Tek sahiplik kuralı |
| Emülatör gerçekçi değil | Yanlış güven | Gecikme ve hata enjekte eden proxy. Aşama 0'da gerçek ölçüm |
| Sürücü dikkati | Güvenlik | Sürüşte yalnız tek ekran. Uyarılar büyük ve az. Ayarlar yalnızca araç dururken (hız = 0) açılır |
