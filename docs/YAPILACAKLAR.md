# Yapılacaklar

1-3: OpenOBD (github.com/Foadsf/OpenOBD) ile karşılaştırmada eksik görülen teşhis özellikleri (2026-11-03).
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

## 4. Dil desteği — YAPILDI (v1.1, 2026-11-04)

Türkçe + İngilizce, kullanıcı dil paketi, sesli yönlendirme kalıpları, `dtc_en.tsv`, İngilizce README. Ayrıntı: [DIL.md](DIL.md).

Kalan:
- ~~Birimler~~ — yapıldı (v1.2): Ayarlar > Ekran > Birimler, `dash/Units.kt`.

---

5-9: r/OBD2'deki benzer uygulamalarla (OpenOBD, OBD Tally) karşılaştırmadan (2026-11-04).

## 5. Araç kimliği (Mode 09)

- `0902` VIN, `0904` kalibrasyon kimlikleri (CALID), `0906` CVN, `090A` ECU adı. Çok çerçeveli cevap (ISO-TP), ELM327 birleştirir.
- Arıza kodları ekranında "Araç bilgisi" bölümü; "Sorun bildir" paketine eklenmeli (hangi ECU yazılımı olduğunu bilmek hata ayıklamada işe yarar).
- VIN kişisel veri sayılabilir: paylaşımda son 6 haneyi gizleme seçeneği.

## 6. Desteklenen PID listesi

- `0100`, `0120`, `0140`… bit maskelerinden aracın desteklediği tüm Mode 01 PID'leri; her biri adı, birimi ve canlı değeriyle liste.
- Göstergelerde olmayan değerleri görmek ve "bu araç neyi veriyor" sorusuna cevap için. Sorgu yükü için yalnız ekran açıkken ve sırayla.

## 7. Gerçek DPF verisi (Volvo 1.6D)

- Şu an DPF temizlenmesi tahminle bulunuyor (`Insights.kt`: rölanti devri/yükü 20 sn yüksek). Motor beyni Mode 22 ile kurum yükü, DPF fark basıncı ya da egzoz sıcaklıkları veriyorsa gerçek değeri kullan.
- DID tarayıcısıyla ara (OBDb/Volvo listesi + tarama); bulunursa uyarı tahmin yerine bu değere dayansın, tahmin yedek kalsın.
- Gösterge: kurum yükü %, son temizlenmeden beri km.

## 8. Bulunan DID'den gösterge (kullanıcı PID profili)

- DID tarayıcısında bulunan bir değeri göstergeye ya da alt şeride ekleme: istek (`22xxxx` + gerekirse `ATSH`), bayt seçimi, formül (A*256+B)/x, birim, ad.
- Profil araç başına JSON olarak saklanır, dışa/içe aktarılabilir (başka araç sahipleriyle paylaşım).

## 9. Ayarlanabilir uyarı eşikleri

- Eşikler şu an kodda sabit ("S40 1.6D için ilk tahmin", `Insights.kt`). Başka araçlarda yanlış uyarı verebilir.
- Ayarlar'da: soğutma suyu, (varsa) yağ sıcaklığı, soğuk motorda devir sınırı, şarj voltajı aralığı, düşük turbo eşiği; varsayılana dön düğmesi.
