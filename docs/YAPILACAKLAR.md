# Yapılacaklar

OpenOBD (github.com/Foadsf/OpenOBD) ile karşılaştırmada eksik görülen üç teşhis özelliği (2026-11-03).
Hepsi standart OBD-II, düz ELM327 ile yapılabilir. Uygulamanın "araca yalnız okuma" ilkesi korunmalı.

## 1. Freeze frame (Mode 02)

Arıza lambası yandığı andaki motor değerleri. Arıza kodları ekranına "O anki değerler" bölümü olarak.

- `0202` (kare 0): kareyi tetikleyen arıza kodu. `00 00` ise kayıtlı kare yok.
- `02xx00` biçiminde PID'ler: Mode 01 ile aynı PID numaraları ve aynı çözümleme (`ElmParser.mode01` yeniden kullanılabilir; cevap öneki `42`, PID'den sonra kare numarası baytı var).
- Desteklenen PID'leri `020000`, `022000`… ile sor; yoksa Mode 01'de desteklenenleri tek tek dene.
- Gösterilecekler: devir, hız, motor sıcaklığı, yük, yakıt düzeltmeleri, emme basıncı (hangisi varsa).
- "Sorun bildir" paketine ve arıza kodu paylaşımına da eklenebilir.
- Not: kodlar silinince (`04`) freeze frame de silinir; silme onayında bunu söyle.

## 2. Mode 06 test sonuçları

Motor beyninin kendi teşhis testlerinin ham değerleri ve sınırları. Arıza lambası yanmadan önce eğilimi gösterebilir.

- CAN araçlarda: `0600` desteklenen izleyici (OBDMID) listesi, ardından `06xx` her izleyici için.
- Cevap kayıtları: OBDMID, TID, birim/ölçek kimliği (UASID), değer, en az, en çok. Ölçek tablosu SAE J1979 ek E.
- Ekranda: test adı, değer, sınırlar ve "geçti / sınıra yakın / kaldı" çubuğu.
- Volvo S40 1.6D (dizel): benzinli katalizör ve ateşleme kaçırma testlerinin bir kısmı olmayabilir; önce aracın neleri desteklediğini görmek için ham listeyi kaydet.
- Değerleri sürüş geçmişi gibi zamanla saklayıp eğilim göstermek ikinci adım.

## 3. Ham terminal (geliştirici ekranı)

Ayarlar > Hakkında altında gizli ya da "Geliştirici" başlıklı bir ekran: komut yaz, ham cevabı gör.

- Mode 22/21 araştırması, adaptör sorunları ve CAN dinleyici denemeleri için.
- Komut geçmişi, cevabı kopyala/paylaş, isteğe bağlı oturum kaydı (.txt).
- `ObdEngine.request` üzerinden; canlı gösterge akışıyla çakışmaması için terminal açıkken sorgu döngüsü duraklatılmalı.
- **Güvenlik:** yazma ve sıfırlama komutları engellenmeli ya da ayrıca onay istemeli: `04`/`14` (kod silme), `2E` (veri yazma), `31` (rutin çalıştırma), `10` (oturum değiştirme), `11` (ECU sıfırlama), `3B`, `AT` ile adaptör ayarlarını kalıcı değiştiren komutlar (`ATPP`).
