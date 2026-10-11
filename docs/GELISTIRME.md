# OBD Köprü

Ucuz klasik Bluetooth (SPP) ELM327 adaptörüne bağlanıp aracın verilerini okuyan Android uygulaması.
Ek kurulum gerekmez, her şey tek APK'nın içinde.

```
[ELM327] ~~BT SPP~~> [OBD Köprü]  ── OBD motoru ──> ekran (ileride dashboard, sürüş kaydı)
                                  └─ ham TCP 127.0.0.1:35000 (isteğe bağlı, Torque/RealDash/geliştirme)
```

## Nasıl çalışır

- **OBD motoru:** BT bağlanınca kendiliğinden çalışır. Adaptörün çoklu PID ve hızlı mod desteğini test eder,
  en hızlı çalışan modu seçer. Sorun çıkarsa daha yavaş ama güvenli bir moda iner. Sorgu önceliği:
  - devir, hız, pedal: her istekte,
  - yük, MAP, MAF: ~300 ms'de bir,
  - sıcaklıklar ve yakıt seviyesi: ~2 sn'de bir,
  - akü voltajı: 2 sn'de bir.
- **Araç testi:** Uygulamadaki butonla çalışır. Araca yalnız **okuma** istekleri gönderir, arıza kodu silmez.
  Protokolü, desteklenen PID'leri, VIN'i, arıza kodlarını, istek sürelerini ve Volvo'ya özel motor
  değerlerini (DPF, turbo, yakıt basıncı) dener. Raporu JSON olarak kaydeder:
  `/sdcard/Android/data/com.nebi.obdbridge/files/probe/`. Yaklaşık 1 dakika sürer, bitince motor kaldığı yerden devam eder.
- **CAN keşif kaydı:** Yalnız ilk aşamada, hangi CAN mesajının devir, pedal, fren, yakıt gibi değerleri
  taşıdığını çözmek için kullanılır. Araç **dururken** (vites boşta, el freni çekili) yapılır. Kayıt:
  - araca bir şey yazmaz, hattı yalnızca dinler,
  - yaklaşık 3,5 dakika sürer,
  - 6 talimat adımından oluşur (rölanti, ~2500 devir, gaz, debriyaj, fren, direksiyon); her adımın başında bip sesi çalar ve talimat ekranda görünür.

  Ucuz adaptör hattın tamamını taşıyamadığı için kimlikler 16 dilime bölünüp sırayla dinlenir.
  Kayıt `/sdcard/Android/data/com.nebi.obdbridge/files/can/` klasörüne yazılır, laptopta
  `python3 tools/can_analyze.py <dosya>` ile çözülür.
- **Sürüş keşif kaydı:** Vites önerisi ve yakıt tüketimi gibi yalnız araç giderken anlamlı sinyaller için.
  - Yola çıkmadan başlatılır, normal sürülür, park edince **Kaydı bitir**'e basılır (en çok 30 dk).
  - Sürüş sırasında telefona dokunmak gerekmez.
  - Her dilimden önce OBD'den devir, hız ve yük okunur.
  - Kayıt `drive_*.txt` adıyla aynı klasöre yazılır.
  - Yakıt sinyalini kalibre etmek için: yola çıkmadan yol bilgisayarının ortalama tüketimini sıfırla,
    varınca paneldeki değeri not et.
- **Ham TCP köprüsü (isteğe bağlı, varsayılan açık):**
  - Yalnız `127.0.0.1:35000` dinlenir, dışarıdan erişilemez.
  - Bir istemci bağlanınca motor duraklar ve baytlar iki yönde de olduğu gibi aktarılır.
  - İstemci gidince motor ~0,3 sn içinde devam eder.
  - Aynı anda tek istemci bağlanabilir. Yeni bağlanan eskisini düşürür.
- **Kesintisizlik:**
  - Foreground service ve wake lock sayesinde ekran kapalıyken de çalışır.
  - BT koparsa 3 sn arayla yeniden bağlanır.
  - Adaptör art arda 3 kez cevap vermezse bağlantı yenilenir.
  - Bulunan protokol adaptöre göre saklanır, sonraki bağlantıda arama yapılmaz.

## 1. Derleme (Android Studio olmadan)

Aşağıdaki araçlar bu makinede `~/android-dev/` altına kuruldu (sudo gerekmez):

| Araç | Yol |
|---|---|
| JDK 17 (Temurin) | `~/android-dev/jdk17` |
| Android SDK | `~/android-dev/sdk` (cmdline-tools, platform-tools, platforms;android-34, build-tools;34.0.0) |

Sıfırdan başka bir makinede kurmak istersen:

```bash
mkdir -p ~/android-dev && cd ~/android-dev
# JDK 17
curl -L -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk17.tar.gz && mv jdk-17* jdk17
# Android command-line tools
curl -L -o cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p sdk/cmdline-tools && unzip -q cmdline.zip -d sdk/cmdline-tools && mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest

export JAVA_HOME=~/android-dev/jdk17
export ANDROID_HOME=~/android-dev/sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

Derleme (Gradle'ı ayrıca kurmana gerek yok, wrapper ilk çalıştırmada kendisi indirir):

```bash
cd ~/obd-bridge
export JAVA_HOME=~/android-dev/jdk17
echo "sdk.dir=$HOME/android-dev/sdk" > local.properties   # yoksa
./gradlew assembleDebug
```

Çıktı: `app/build/outputs/apk/debug/app-debug.apk`

## 2. APK'yı telefona yükleme

**adb ile:** Telefonda Geliştirici seçenekleri → USB hata ayıklama açık olmalı.

```bash
~/android-dev/sdk/platform-tools/adb devices
~/android-dev/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Dosya aktarımı ile:** APK'yı USB, Telegram ya da Drive gibi bir yolla telefona at ve dosya yöneticisinden aç.
Gerekirse "Bilinmeyen kaynaklardan yükleme" iznini o uygulamaya ver.

## 3. Kullanım

1. Adaptörü araca tak ve kontağı aç. Telefonda **Ayarlar → Bluetooth** ekranından adaptörle eşleş
   (genelde adı `OBDII` veya `V-LINK` olur, PIN `1234` ya da `0000`).
2. **OBD Köprü** uygulamasını aç ve Bluetooth ile bildirim izinlerini ver.
3. Listeden adaptörü seç ve **Başlat**'a bas. Durum sırasıyla şunları gösterir:
   1. `BT bağlanıyor`
   2. `BT bağlı · OBD hazırlanıyor`
   3. `Araç aranıyor`
   4. `Canlı`

   Altta canlı değerler, sorgu modu ve devir güncelleme hızı (Hz) görünür.
4. **Pil kısıtlamasını kaldır** butonuna bas ve izin ver. Buton bir kez izin verildikten sonra kaybolur.
5. İlk kullanımda **Araç testi**'ne bas. Kontak açık, mümkünse motor çalışır durumda olsun.
6. Durdurmak için uygulamadaki **Durdur** butonunu ya da bildirimdeki **Durdur** eylemini kullan.

Ham TCP köprüsünü kapatmak istersen, servis durmuşken ilgili kutunun işaretini kaldır.

Araç testi raporunu bilgisayara almak için:

```bash
adb pull /sdcard/Android/data/com.nebi.obdbridge/files/probe/ .
```

## 4. Olası sorunlar

| Belirti | Çözüm |
|---|---|
| Listede adaptör yok | Önce telefonun Bluetooth ayarlarından eşleştir (PIN `1234` veya `0000`), sonra uygulamaya geri dön |
| Sürekli `BT koptu, yeniden deneniyor` | Kontak açık mı, adaptörün ışığı yanıyor mu? Adaptör başka bir uygulamaya (Torque vb.) bağlı olabilir, o uygulamayı kapat. Telefonun BT'sini kapatıp aç |
| `Araç cevap vermiyor (kontak kapalı?)` | Kontağı aç. Motor 2 sn arayla tekrar dener |
| `Hata: 35000 portu kullanımda` | Başka bir uygulama 35000 portunu tutuyor. O uygulamayı kapat ya da ham TCP köprüsünü kapat |
| Ekran kapanınca veri kesiliyor | Pil kısıtlamasını kaldır. Xiaomi/Samsung/Huawei gibi telefonlarda ayrıca *Otomatik başlatma* ve *Pil → Kısıtlama yok* ayarlarını yap |
| Devir Hz'i düşük (< 5) | Adaptör çoklu PID ve hızlı modu desteklemiyor olabilir. Araç testi raporuna bakılır |

Logları görmek için: `adb logcat -s ObdBridge`

## 5. Geliştirme: adaptörsüz test

OBD motoru Android'e bağımlı değildir. Laptopta bir ELM327 emülatörü ve gerçek BT klonunu taklit eden
gecikme proxy'si ile test edilir:

```bash
pip install ELM327-emulator
elm -n 35000 -s car -d                      # emülatör (arka planda)
export JAVA_HOME=~/android-dev/jdk17
./gradlew testDebugUnitTest                 # testler proxy'yi kendileri başlatır
```

`tools/` klasöründe geliştirme araçları var:
- `latency_proxy.py`: gecikme, kötü klon ve kopma taklidi.
- `obd_test.py`: ham soketle 4 komutluk hızlı bağlantı testi.
- `obd_probe.py`: araç testinin Python sürümü.
- `can_analyze.py`: CAN keşif kaydını çözer (adıma özgü baytlar, devir adayları).
- `latency_proxy.py --can-sim`: sahte CAN hattı (filtre, taşma taklidi).

Telefondaki ham köprüye laptoptan bağlanmak için:

```bash
adb forward tcp:35000 tcp:35000
python3 tools/obd_test.py
```

## Dosyalar

```
app/src/main/java/com/nebi/obdbridge/MainActivity.kt   ekran, izinler
app/src/main/java/com/nebi/obdbridge/BridgeService.kt  foreground service, BT bağlantısı, ham TCP köprü
app/src/main/java/com/nebi/obdbridge/obd/              OBD motoru, ELM ayrıştırıcı, araç testi
app/src/test/...                                      ayrıştırıcı + motor testleri (emülatörle)
tools/                                                geliştirme araçları (Python)
dashboard/DESIGN.md, mockup.html, data/               tasarım, görsel taslak, Volvo DID ve DTC verisi
```
