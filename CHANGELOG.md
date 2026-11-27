# Sürüm notları

## 1.3 (hazırlanıyor)

- Android Auto (deneysel): araç ekranında telefondaki gösterge (tema, kadranlar, şerit, navigasyon ve medya kartı, uyarı hapı). Play dışı kurulumda Android Auto'nun geliştirici ayarında "Bilinmeyen kaynaklar" gerekir (README). Gerçek araçta henüz denenmedi.
- Navigasyon telefon kilitliyken ve ekran kapalıyken de sürer (ayrı servis; bildirimde sıradaki dönüş ve Rotayı bitir). Uygulama yeniden başlarsa rotaya devam eder.
- Yeni uyarı: Motor sıcak (105 °C üstü sarı, 112 °C kırmızı).
- *Android Auto (experimental), navigation keeps running with the phone locked, new engine-hot warning.*

## 1.2

- Birim ayarı, dilden bağımsız (Ayarlar > Ekran > Birimler): km/h–km ya da mph–mil, °C ya da °F, L/100 km, mpg (ABD), mpg (İngiliz) ya da km/L, kPa/bar ya da psi. Gösterge ölçeği, alt şerit, yolculuk özeti, geçmiş, navigasyon mesafeleri ve sesli yönlendirme seçilen birimde. Varsayılan telefonun bölgesinden (ABD, İngiltere, Liberya, Myanmar'da mil). Kayıtlar metrik saklanmaya devam eder.
- Mil seçiliyken Performans kartında 0–60 mph ve 40–60 mph süreleri (km/h rekorlarından ayrı).
- *Units setting, independent of language (Settings > Display > Units): mph/miles, °F, mpg (US or imperial) or km/L, psi. Defaults from the phone's region.*

## 1.1

- Dil desteği: Türkçe ve İngilizce (Ayarlar > Ekran > Dil; telefon Türkçeyse Türkçe, değilse İngilizce açılır). Sesli yönlendirme, arıza kodu açıklamaları, tarih biçimleri, harita ve adres arama da seçilen dilde.
- Kendi dil paketin: çeviri şablonunu kaydet, çevir, uygulamaya ekle ([docs/DIL.md](docs/DIL.md)).
- *Language support: Turkish and English (Settings > Display > Language; English unless the phone is set to Turkish), plus user language packs.*

## 1.0 (ilk yayın)

- Araç göstergesi: hız, devir, vites tahmini, turbo, motor yükü, akü, motor sıcaklığı.
- 30 görsel tema; güneşte otomatik gündüz teması. Kendi teması: şablon resmi yapay zekâya çizdirilir, uygulama tek resimden temayı üretir. Her temada gösterge rakamları, kart, vurgu ve uyarı hapı renkleri seçilebilir. Üst ortada isteğe bağlı ekran yazısı (ör. araç modeli).
- Tahmini yakıt tüketimi (yakıt debisi PID'i yoksa motor yükünden), depo dolumuyla kalibrasyon.
- Uyarılar: arıza lambası, DPF temizlenmesi (dizel), düşük turbo, şarj sorunu, soğuk motorda yüksek devir.
- Arıza kodları: kayıtlı / bekleyen / kalıcı kodlar, Türkçe açıklama, paylaşma, onaylı silme.
- Sürüş geçmişi: otomatik kayıt, haftalık özet, yolculuk ayrıntısı.
- Kendi navigasyonu: adres arama, Ev/İş hızlı hedefleri, dönüş kartı ve varış saati, Türkçe sesli yönlendirme, dönüşe yaklaşınca yuvarlak harita (Yandex MapKit yalnız navigasyonda; diğer zamanlarda OpenFreeMap). İsteğe bağlı kendi Yandex anahtarı. İzinsiz medya kontrolü.
- Sorun bildir: bağlantı özeti ve son sürüşler tek dosyada (şasi no ve adaptör adresi gizli).
- Güncelleme denetimi (GitHub, günde bir; kapatılabilir).
- Deneysel: Bluetooth LE ve Wi-Fi adaptörler.
- İki APK: arm64-v8a (çoğu telefon) ve armeabi-v7a (eski telefonlar, araç ekranları).
