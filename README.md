# OBD Dashboard

Ucuz bir ELM327 OBD-II adaptörüyle telefonu ya da tableti araç göstergesine çeviren Android uygulaması.
Hız, devir, vites, turbo, motor sıcaklığı, tahmini yakıt tüketimi, navigasyon dönüşleri ve müzik kontrolü tek ekranda.

*An Android head-unit / dashboard app for cheap ELM327 OBD-II adapters. UI is Turkish.*

## Özellikler

- **Gösterge:** 6 görsel tema (Spor, OLED, Synthwave, Klasik, Buzul, Gündüz). Güneşte otomatik gündüz teması.
- **Vites tahmini:** Manuel şanzımanda devir/hız oranından; oranları sürdükçe kendisi öğrenir.
- **Tahmini yakıt:** Yakıt debisi PID'i yoksa motor yükünden tahmin edilir; depo dolumuyla kalibre edilir.
- **Uyarılar:** DPF temizlenmesi (dizel), düşük turbo, şarj sorunu, soğuk motorda yüksek devir.
- **Arıza kodları:** Okuma, Türkçe açıklama, paylaşma, onaylı silme.
- **Sürüş geçmişi:** Her sürüş otomatik kaydedilir (yalnız telefonda, 30 gün).
- **Navigasyon ve medya:** Yandex Navigator ve Google Haritalar dönüş okları (Waze açılır ama ok göstermez), çalan müziğin kontrolü.

Herhangi bir OBD-II araçta çalışır (Avrupa'da benzinli 2001+, dizel 2004+). Geliştirildiği araç: 2010 Volvo S40 1.6D.

## Kurulum

1. [Sürümler](https://github.com/nebigoktug/obd-dashboard/releases/latest) sayfasından son APK'yı indirin ve kurun
   (bilinmeyen kaynaklara izin vermeniz gerekir).
2. Adaptörü aracın OBD soketine takın, kontağı açın, telefonun Bluetooth ayarlarından eşleştirin (PIN genelde `1234` ya da `0000`).
3. Uygulamayı açın; kurulum sihirbazı adaptörü ve araç bilgilerini sorar.

**Adaptör:** Klasik Bluetooth (SPP) ELM327 en iyi denenmiş olanı. Bluetooth LE ve Wi-Fi adaptör desteği **deneyseldir**.

## Güvenlik ve gizlilik

- Sürüş sırasında uygulamayla uğraşmayın. Gösterilen değerler tahmindir; aracın kendi göstergeleri esastır.
- Uygulama araca yalnız okuma istekleri gönderir. Arıza kodu silme yalnız sizin onayınızla yapılır.
- Kayıtlar telefondan çıkmaz. İnternete giden istekler: hava durumu (yaklaşık konum, Open-Meteo) ve günde bir
  güncelleme denetimi (GitHub). "Sorun bildir" paketi yalnız sizin seçtiğiniz kişiye gider; şasi no ve adaptör adresi gizlenir.

## Sorun bildirme

Ayarlar > Kayıtlar > **Sorun bildir** bağlantı özetini ve son sürüş kayıtlarını tek dosyada hazırlar.
Hata ve öneriler için [Issues](https://github.com/nebigoktug/obd-dashboard/issues) bölümünü kullanabilirsiniz.

## Derleme

JDK 17 ve Android SDK gerekir: `./gradlew assembleDebug`. İmza anahtarı depoda yoktur;
`~/.config/obd-dashboard/` bulunmazsa derleme varsayılan hata ayıklama anahtarını kullanır.
Geliştirme notları: [docs/GELISTIRME.md](docs/GELISTIRME.md).

## Lisans ve teşekkürler

Uygulama **GPL-3.0** lisanslıdır ([LICENSE](LICENSE)).

- Hava durumu verisi: [Open-Meteo](https://open-meteo.com) (CC BY 4.0)
- Hava simgeleri: [Meteocons](https://github.com/basmilius/weather-icons), Bas Milius (MIT)
- Volvo motor beyni DID listesi (`dashboard/data/volvo_ecm_dids.json`): [OBDb/Volvo](https://github.com/OBDb/Volvo) (CC BY-SA 4.0)
- Tema görselleri ve uygulama simgesi yapay zekâ (Google Gemini) ile üretilip elle düzenlendi.
- Arıza kodu açıklamaları SAE J2012 genel kod başlıklarından Türkçeye derlendi.

Volvo ve diğer marka adları sahiplerinin ticari markalarıdır; bu proje hiçbir üreticiyle bağlantılı değildir.
