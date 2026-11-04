# Dil desteği / Languages

Uygulamanın kaynak dili Türkçe. Yerleşik diller: **Türkçe**, **English**. Kullanıcı kendi dil paketini ekleyebilir.
Seçim: Ayarlar > Ekran > Dil. Varsayılan: telefon Türkçeyse Türkçe, değilse İngilizce. Dil değişince uygulama yeniden başlar.

## Nasıl çalışır (geliştirici)

- Metinler kodda Türkçe yazılır ve `t()` ile sarılır: `t("Bağlantı")`, `t("%d dolum", n)`. Anahtar Türkçe metnin kendisidir (`I18n.kt`).
- Değişkenli metinde Kotlin şablonu (`"$n dolum"`) kullanılmaz, anahtar sabit kalmalı: biçim parametresi (`%s`, `%d`, `%.1f`).
  Sözcük sırası dile göre değişebilecekse sıra numaralı yazılır: `t("%1$s sonra %2$s", mesafe, manevra)`.
- Yerel değişkenin adı `t` ise (ör. yolculuk) `L.t(...)` yazılır.
- Kimlik olarak karşılaştırılan değerler (veri kaynağı adı "Canlı"/"Demo", ölçüm durumu "hazır") **çevrilmez**.
- Tarih biçimi ve büyük harf `L.locale` ile (Türkçe i/İ yalnız Türkçede). TTS, Yandex ve arıza kodu tablosu da seçilen dili kullanır.
- Bilerek Türkçe kalanlar: geliştirici araçları (`MainActivity`, `VehicleProbe`, `CanSniffer`, `DidScanner`), geliştiriciye giden rapor (`Report.kt`, `ObdEngine.diag`) ve günlükler.

### Araç: `tools/i18n.py`

```sh
python tools/i18n.py check en      # eksik / artık anahtarlar, biçim parametresi uyuşmazlığı
python tools/i18n.py sync en       # eksikleri boş ekler, artıkları siler, kod sırasına dizer
python tools/i18n.py template      # lang/template.json (tüm metinler, çevirisi boş)
python tools/i18n.py untranslated  # t() ile sarılmamış Türkçe metinler (gözden kaçanlar)
```

Kodda bir Türkçe metni değiştirince `check en` eskisini "artık", yenisini "eksik" gösterir: İngilizcesini güncelle, `sync en` çalıştır.

### Dosyalar

| Dosya | İçerik |
|---|---|
| `app/src/main/assets/lang/en.json` | İngilizce paket |
| `app/src/main/assets/dtc_tr.tsv`, `dtc_en.tsv` | Arıza kodu açıklamaları (SAE J2012) |
| `lang/template.json` | Boş şablon (araçla üretilir) |

## Paket biçimi / Pack format

```json
{
 "_meta": {"code": "de", "name": "Deutsch", "author": "..."},
 "strings": {"Bağlantı": "Verbindung", "%d dolum": "%d Tankfüllungen"},
 "dtc": {"P0171": "System zu mager (Bank 1)"}
}
```

- `code`: ISO 639-1 language code. Dates, upper case, text-to-speech and Yandex map/search language follow it.
- `strings`: keys are the Turkish originals; **do not change keys**. Empty or missing values are shown in English
  (in Turkish if `code` is `tr`).
- Keep placeholders as they are: `%s`, `%d`, `%.1f`, `%%`; `%1$s` / `%2$s` can be reordered.
- `\n` is a line break. Keep a leading/trailing space if the original has one (`" · %d kod"`).
- `dtc` (fault code descriptions) is optional; without it the English table is used.
- Voice guidance is built from the `strings` entries too (`"%1$s sonra %2$s"` → `"In %1$s, %2$s"`), so translate those for spoken turns.

## Translating (for users)

1. In the app: Settings > Display > **Your own language pack** > **Save translation template**. It saves a JSON file with
   every text: keys are Turkish, values are English. Replace the English values with your language.
2. Set `_meta.code` (e.g. `de`) and `_meta.name` (e.g. `Deutsch`).
3. Back in the app: **Add language pack**, choose the file. The app shows how many texts are translated and restarts in that language.

After an app update new texts show in English until you add them: save the template again and copy over the new entries.
