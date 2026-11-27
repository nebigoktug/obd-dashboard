# OBD Dashboard

Ucuz bir ELM327 OBD-II adaptörüyle telefonu ya da tableti araç göstergesine çeviren Android uygulaması.
Hız, devir, vites, turbo, motor sıcaklığı, tahmini yakıt tüketimi, navigasyon dönüşleri ve müzik kontrolü tek ekranda.

*English below: [English](#english)*

## Özellikler

- **Gösterge:** 30 görsel tema (Spor, OLED, Synthwave, Mermer, Türk Halısı, Nazar…). Güneşte otomatik gündüz teması. Her temada kart, yazı, vurgu ve uyarı hapı renkleri seçilebilir. **Kendi temanı ekle:** şablonu Gemini/ChatGPT gibi bir yapay zekâya kendi tarzında çizdirip çıkan resmi ekleyebilirsin (Ayarlar > Ekran).
- **Vites tahmini:** Manuel şanzımanda devir/hız oranından; oranları sürdükçe kendisi öğrenir.
- **Tahmini yakıt:** Yakıt debisi PID'i yoksa motor yükünden tahmin edilir; depo dolumuyla kalibre edilir.
- **Uyarılar:** Arıza lambası, motor sıcak, DPF temizlenmesi (dizel), düşük turbo, şarj sorunu, soğuk motorda yüksek devir.
- **Arıza kodları:** Okuma, Türkçe açıklama, paylaşma, onaylı silme. Kodun oluştuğu andaki değerler (freeze frame) ve motor beyninin kendi test sonuçları (Mode 06). Geliştiriciler için ham terminal (Ayarlar > Gelişmiş; yalnız okuma komutları).
- **Sürüş geçmişi:** Her sürüş otomatik kaydedilir (yalnız telefonda, 30 gün).
- **Navigasyon ve medya:** uygulamanın kendi navigasyonu (Yandex adres arama ve rota, dönüş kartı, Türkçe ve İngilizce sesli yönlendirme; dönüşe yaklaşınca devir göstergesinin yerinde yuvarlak harita), çalan müziğin kontrolü (izinsiz; şarkı adı için isteğe bağlı bildirim erişimi). Ayrı bir navigasyon uygulaması gerekmez.

Herhangi bir OBD-II araçta çalışır (Avrupa'da benzinli 2001+, dizel 2004+). Geliştirildiği araç: 2010 Volvo S40 1.6D.

> **1.3'te henüz gerçek araçta denenmeyenler:** Android Auto (geliştiricinin aracında yok; [open-headunit](https://github.com/andreknieriem/open-headunit) ile denendi), freeze frame, Mode 06 test sonuçları ve ham terminal (ELM327 emülatörü ve örnek cevaplarla denendi), telefon kilitliyken süren navigasyon (masada denendi, sürüşte değil). Sorun görürseniz bildirin; Mode 06 ekranındaki "Ham kaydı paylaş" ile gönderilen kayıt çok işe yarar.

- **Dil:** Türkçe ve İngilizce. Telefon Türkçeyse Türkçe, değilse İngilizce açılır; Ayarlar > Ekran > Dil'den değiştirilebilir. Kendi dil paketini de ekleyebilirsin ([docs/DIL.md](docs/DIL.md)).
- **Birimler:** km/h ya da mph, °C ya da °F, L/100 km, mpg ya da km/L, kPa/bar ya da psi (Ayarlar > Ekran > Birimler).

## Kurulum

1. [Sürümler](https://github.com/nebigoktug/obd-dashboard/releases/latest) sayfasından APK'yı indirin ve kurun
   (bilinmeyen kaynaklara izin vermeniz gerekir). İki dosya vardır:
   - `app-arm64-v8a-release.apk` — **çoğu telefon ve tablet** (2017 sonrası)
   - `app-armeabi-v7a-release.apk` — eski 32 bit telefonlar ve çoğu Android araç ekranı (teyp). Hangisi olduğunu bilmiyorsanız önce arm64'ü deneyin; "uygulama yüklenmedi" derse bunu kurun.
2. Adaptörü aracın OBD soketine takın, kontağı açın, telefonun Bluetooth ayarlarından eşleştirin (PIN genelde `1234` ya da `0000`).
3. Uygulamayı açın; kurulum sihirbazı adaptörü ve araç bilgilerini sorar.

**Adaptör:** Klasik Bluetooth (SPP) ELM327 en iyi denenmiş olanı. Bluetooth LE ve Wi-Fi adaptör desteği **deneyseldir**.

### Android Auto (deneysel)

Araç ekranında telefondaki göstergenin aynısı görünür (tema, kadranlar, alt şerit, navigasyon ve medya kartı, uyarılar).
Navigasyon telefon kilitliyken de sürer, Android Auto'ya sıradaki dönüş bildirilir. Ayarlar telefondan yapılır.

Uygulama Play Store'dan gelmediği için Android Auto onu ancak bir kez şu ayarı açınca listeler:
1. Telefonda Android Auto ayarlarını açın (Ayarlar > Bağlı cihazlar > Android Auto), en alttaki **Sürüm**'e 10 kez dokunun.
2. Sağ üstteki ⋮ menüsünden **Geliştirici ayarları** > **Bilinmeyen kaynaklar**'ı açın.
3. Telefonu araca bağlayın; OBD Dashboard araç ekranındaki uygulama listesinde görünür.

Gerçek bir araçta henüz denenmedi (geliştiricinin aracında Android Auto yok); [open-headunit](https://github.com/andreknieriem/open-headunit) ile denendi. Sorun görürseniz bildirin.

## Güvenlik ve gizlilik

- Sürüş sırasında uygulamayla uğraşmayın. Gösterilen değerler tahmindir; aracın kendi göstergeleri esastır.
- Uygulama araca yalnız okuma istekleri gönderir. Arıza kodu silme yalnız sizin onayınızla yapılır.
- Kayıtlar telefondan çıkmaz. İnternete giden istekler: hava durumu (yaklaşık konum, Open-Meteo), harita görüntüsü (OpenFreeMap), adres arama ve rota (Yandex MapKit, yalnız navigasyonda; konum ve hedef Yandex'e gider) ve günde bir güncelleme denetimi (GitHub). "Sorun bildir" paketi yalnız sizin seçtiğiniz kişiye gider; şasi no ve adaptör adresi gizlenir.

## Sorun bildirme

Ayarlar > Hakkında > **Sorun bildir** bağlantı özetini ve son sürüş kayıtlarını tek dosyada hazırlar.
Hata ve öneriler için [Issues](https://github.com/nebigoktug/obd-dashboard/issues) bölümünü kullanabilirsiniz.

## Derleme

JDK 17 ve Android SDK gerekir: `./gradlew assembleDebug`. İmza anahtarı depoda yoktur;
`~/.config/obd-dashboard/` bulunmazsa derleme varsayılan hata ayıklama anahtarını kullanır.
Geliştirme notları: [docs/GELISTIRME.md](docs/GELISTIRME.md).

## Lisans ve teşekkürler

Uygulama **GPL-3.0** lisanslıdır ([LICENSE](LICENSE)).

- Harita verisi: © [OpenStreetMap katkıcıları](https://www.openstreetmap.org/copyright) (ODbL), © [OpenMapTiles](https://openmaptiles.org), döşemeler [OpenFreeMap](https://openfreemap.org); harita motoru [MapLibre Native](https://maplibre.org) (BSD)
- Adres arama ve rota: [Yandex MapKit](https://yandex.com/dev/mapkit/) ([kullanım koşulları](https://yandex.com/legal/maps_termsofuse/))
- Hava durumu verisi: [Open-Meteo](https://open-meteo.com) (CC BY 4.0)
- Hava simgeleri: [Meteocons](https://github.com/basmilius/weather-icons), Bas Milius (MIT)
- Volvo motor beyni DID listesi (`dashboard/data/volvo_ecm_dids.json`): [OBDb/Volvo](https://github.com/OBDb/Volvo) (CC BY-SA 4.0)
- Tema görselleri ve uygulama simgesi yapay zekâ (Google Gemini) ile üretilip elle düzenlendi.
- Arıza kodu açıklamaları SAE J2012 genel kod başlıklarından Türkçeye derlendi.

Volvo ve diğer marka adları sahiplerinin ticari markalarıdır; bu proje hiçbir üreticiyle bağlantılı değildir.

## English

An Android dashboard / head-unit app that turns a phone or tablet into a car display using a cheap ELM327 OBD-II adapter.
Speed, RPM, gear, boost, coolant temperature, estimated fuel use, turn-by-turn navigation and music control on one screen.

**Language:** the app opens in English unless the phone is set to Turkish; change it in Settings > Display > Language.
You can add your own language pack: save the translation template in the app, translate it and load it back ([docs/DIL.md](docs/DIL.md#translating-for-users)).

**Units** are a separate setting (Settings > Display > Units): km/h or mph, °C or °F, L/100 km, mpg (US or imperial) or km/L, kPa/bar or psi.
The default follows the phone's region (miles in the US, UK, Liberia and Myanmar).

### Features

- **Dashboard:** 30 image themes, automatic day theme in sunlight, per-theme colors for cards, text, accent and warning pills. **Your own theme:** have an AI image tool (Gemini, ChatGPT…) redraw the template in your style and add the result (Settings > Display).
- **Gear estimate** for manual gearboxes from the RPM/speed ratio; learns your ratios as you drive.
- **Estimated fuel use** from engine load when there is no fuel-rate PID, calibrated with your fill-ups.
- **Warnings:** check-engine lamp, engine hot, DPF regeneration (diesel), low boost, charging problems, high RPM on a cold engine.
- **Fault codes:** read, descriptions, share, clear with confirmation. Values at the moment the code was set (freeze frame) and the engine computer's own test results (Mode 06). Raw terminal for developers (Settings > Advanced; read-only commands).
- **Trip history:** every drive is saved automatically (on the phone only, 30 days).
- **Navigation and media:** built-in navigation (Yandex address search and routing, turn card, voice guidance, a round map in place of the RPM dial near turns) and control of the music that is playing. No separate navigation app needed.

Works with any OBD-II car (in Europe: petrol 2001+, diesel 2004+). Developed on a 2010 Volvo S40 1.6D.

> **Not yet tested in a real car in 1.3:** Android Auto (the developer's car doesn't have it; tested with [open-headunit](https://github.com/andreknieriem/open-headunit)), freeze frame, Mode 06 test results and the raw terminal (tested with an ELM327 emulator and sample answers), navigation with the phone locked (tested on the desk, not while driving). Please report problems; the "Share raw log" button on the Mode 06 screen helps a lot.

### Install

1. Download the APK from [Releases](https://github.com/nebigoktug/obd-dashboard/releases/latest) and install it (allow unknown sources):
   - `app-arm64-v8a-release.apk` — **most phones and tablets** (2017 and later)
   - `app-armeabi-v7a-release.apk` — older 32-bit phones and most Android car head units. If unsure, try arm64 first; if it says "app not installed", use this one.
2. Plug the adapter into the car's OBD socket, turn the ignition on and pair it in the phone's Bluetooth settings (PIN is usually `1234` or `0000`).
3. Open the app; the setup wizard asks for the adapter and vehicle details.

**Adapter:** classic Bluetooth (SPP) ELM327 is the best tested. Bluetooth LE and Wi-Fi adapters are **experimental**.

#### Android Auto (experimental)

The car screen shows the same dashboard as the phone (theme, dials, bottom strip, navigation and media cards, warnings).
Navigation keeps running with the phone locked and the next turn is passed to Android Auto. Settings stay on the phone.

The app isn't from the Play Store, so Android Auto only lists it after a one-time setting:
1. On the phone open Android Auto settings (Settings > Connected devices > Android Auto) and tap **Version** at the bottom 10 times.
2. In the ⋮ menu at the top right open **Developer settings** and turn on **Unknown sources**.
3. Connect the phone to the car; OBD Dashboard appears in the car's app list.

Not yet tested in a real car (the developer's car has no Android Auto); tested with [open-headunit](https://github.com/andreknieriem/open-headunit). Please report problems.

### Safety and privacy

- Don't operate the app while driving. Values are estimates; the car's own instruments are what count.
- The app only sends read requests to the car. Fault codes are cleared only after you confirm.
- Your data stays on the phone. Network requests: weather (approximate location, Open-Meteo), map tiles (OpenFreeMap), address search and routing (Yandex MapKit, only while navigating; location and destination go to Yandex) and a daily update check (GitHub). The "Report a problem" package goes only to whoever you send it to; VIN and adapter address are masked.

### Issues and building

Use [Issues](https://github.com/nebigoktug/obd-dashboard/issues) for bugs and ideas; Settings > About > **Report a problem** prepares a file with the connection summary and recent drives.
Building needs JDK 17 and the Android SDK: `./gradlew assembleDebug`. Licensed under **GPL-3.0**; credits are listed above.
