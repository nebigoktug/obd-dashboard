# Araç ekranı: dashboard + navigasyon + multimedya: tasarım kararları

Durum: kararlar alındı · 2026-10-10 · Önceki OBD/CAN keşif işleri arka planda (tarama ve blok kaydı sürüyor).
Kullanıcının bundan sonra kullanacağı uygulama bu. `DESIGN.md` §0'daki OBD kararları geçerli, burada değişenler yazılı.

## 1. Kullanıcı kararları

| Konu | Karar | Not |
|---|---|---|
| Cihaz | **Redmi Note 13 Pro, araç tutucusunda**, günlük telefon | Uygulama her binişte açılıp kapanır. Arama ve bildirimler aynı cihazda |
| Yön | **Yalnız yatay** | Dikey yerleşim bırakılır (eski dikey kod kaldırılabilir) |
| Başlatma | **Elle** (değişmedi) | Otomatik açılma yok |
| Navigasyon | **Yandex Navigator + bizim kabuk** | Harita ve rota Yandex'te. Bizde dönüş kartı (NaviListener, çalışıyor) ve Yandex'e geçiş |
| Yandex açıkken | **Hiçbir şey gösterilmez** | Overlay yok, "diğer uygulamaların üzerinde" izni gerekmez. Geri tuşuyla bize dönülür |
| Multimedya | **Var olan uygulamaları kumanda et** | MediaSession (Spotify, YT Music, radyo…). Kendi çalarımız yok |
| Ses çıkışı | **AUX kablo** | Ses yönlendirme işi yok. Arama sesi de AUX'tan |
| Görsel dil | **Referans resmi aynen** | `dash_base.jpg` / `dash_lit.jpg` katmanları korunur |
| Ana ekran | **Üç sütun**, referansın kendi düzeniyle | Sol: hız halkası · orta: nav + medya · sağ: devir halkası/vites |
| OBD yokken | **GPS hızı** | Adaptör yokken hız halkası GPS hızını gösterir ("GPS" etiketiyle); devir/vites/kutucuklar "–". OBD gelince OBD hızına geçer |
| Ekstralar | Saat + hava, hızlı hedefler (Ev/İş), yolculuk özeti | Arama kartı yapıldı, sonra **kaldırıldı** (2026-10-10): telefon uygulaması aramayı zaten kendi ekranında gösteriyor |

## 2. Ana ekran yerleşimi (yatay, referans koordinatları 2400×1080)

```
        Hava 25°C   Sürüş  Detay  Ayarlar   21:34   ▂▄▆
  ◜ 0..200 ◝   ┌───────────────────┐   ◜ 0..5 ◝
 ◜          ◝  │ ↱  350 m          │  ◜        ◝
 │    74    │  │ Atatürk Cd.       │  │   3    │
 │   km/h   │  ├───────────────────┤  │2270 rpm│
 ◟          ◞  │ ▓ Şarkı · Sanatçı │  ◟  ▬▬    ◞
  ◟        ◞   │ ⏮   ⏯   ⏭   ───○─ │   ◟      ◞
               └───────────────────┘
   turbo 0,06 bar · yük %5 · akü 14,0 V · motor 88 °C
```

- **Halkalar ve üst çubuk:** resimdeki gibi kalır (`DashImage`, `DashImageGeom`).
  Üst çubuk zaten hava ve saati içeriyor. Hava artık gerçek veri (bkz. §4).
- **Orta sütun:** referanstaki dört kutucuğun alanı. Üstte **nav kartı**, altta **medya kartı**.
  Kartlar referansın renklerinde çizilir: koyu cam zemin, ince açık kenar, beyaz/gri yazı, turuncu vurgu
  (devir halkasının ucundaki renk).
- **Alt şerit:** yol çiziminin olduğu bant. Dört değer tek satıra küçülür: turbo · yük · akü · motor sıcaklığı.
  Uyarı durumunda (sıcaklık, akü) değer turuncu/kırmızı yanar.
- VOLVO yazısı ve araç/yol çizimi orta kartların arkasında kalır ya da kartlar yüzünden görünmez.
  Bu kabul edildi (resim "aynen", sadece orta alan kullanılıyor).

### Orta sütun durumları (öncelik sırası)
1. **Gelen/süren arama:** orta sütunun tamamı arama kartı olur: arayan, süre, büyük Aç / Kapat.
2. **Aktif rota (Yandex bildirimi var):** nav kartında ok, mesafe, sokak. Dokununca Yandex açılır.
3. **Rota yok:** nav kartının yerinde **Ev** / **İş** hızlı hedef düğmeleri + "Yandex'i aç".
4. **Medya kartı** her durumda altta. Hiçbir şey çalmıyorsa son çalan uygulama gösterilir, ⏯ ile devam ettirilir.

## 3. Sekmeler
- **Sürüş:** yukarıdaki ana ekran.
- **Detay:**
  - **yolculuk özeti** (bu sürüşün süresi, km = hız integrali, ortalama hız; ileride yakıt),
  - tüm canlı değerler,
  - arıza kodları (oku + onaylı sil, DESIGN.md §0).
- **Ayarlar:** adaptör seçimi, Başlat/Durdur, Ev/İş adresleri, tema parlaklığı, izinler, OBD araçları
  (araç testi, tarama, blok kaydı).
  Bugünkü ana ekran (MainActivity) buraya taşınır; uygulama doğrudan dashboard'la açılır.

## 4. Teknik kararlar (varsayılanlar, kullanıcıya sorulmadı)

| Parça | Yol | İzin |
|---|---|---|
| Nav kartı | Mevcut `NaviListener` (Yandex bildirimi: başlık = mesafe, metin = sokak, ok = `primaryIconTinted`) | Bildirim erişimi (var) |
| Yandex'e geçiş | `getLaunchIntentForPackage("ru.yandex.yandexnavi")` | — |
| Ev/İş | `yandexnavi://build_route_on_map?lat_to=..&lon_to=..` intent'i. Koordinatlar Ayarlar'da girilir (haritadan seçim yok) | — |
| Medya | `MediaSessionManager.getActiveSessions(NaviListener bileşeni)`: başlık, sanatçı, kapak, konum, play/pause/next/prev | Bildirim erişimi yeterli |
| Arama kartı | `TelephonyManager` arama durumu + numaradan rehber adı; aç/kapat `TelecomManager.acceptRingingCall/endCall` | READ_PHONE_STATE, READ_CONTACTS, ANSWER_PHONE_CALLS (çalışma anında istenir) |
| Hava | Open-Meteo (anahtarsız, ücretsiz), telefonun son konumuyla 30 dk'da bir | Konum (kaba) |
| Saat | Sistem saati | — |
| Yolculuk | OBD hızı integrali + süre. Bağlantı kesilince oturum biter, Detay'da son yolculuk kalır | — |
| Ekran | Dashboard önde açıkken ekran açık kalır (FLAG_KEEP_SCREEN_ON), tam ekran, sistem çubukları gizli | — |
| Dokunma | Sürüş halindeyken yalnız büyük hedefler (≥ 64 dp): kartlar ve medya düğmeleri | — |

## 5. Kapsam dışı
- Kendi haritamız veya rota hesabı, overlay balon, otomatik başlatma, dikey yerleşim, kendi müzik çalarımız.
- Android Auto / Yandex'in araç modu: Yandex zaten kendi ekranını çiziyor, ona karışmıyoruz.

## 6. Uygulama sırası
1. **İskelet:** uygulama dashboard'la açılsın. MainActivity → Ayarlar sekmesine taşınsın. Dikey kaldırılsın.
2. **Orta sütun:** kutucuklar alt şeride, nav kartı + medya kartı (MediaSession).
3. **Ev/İş + Yandex'e geçiş.**
4. **Arama kartı.**
5. **Hava + yolculuk özeti** (Detay).
6. Telefonda gerçek kullanımla ince ayar (ekran görüntüsü kullanıcı açınca alınır, dokunulmaz).
