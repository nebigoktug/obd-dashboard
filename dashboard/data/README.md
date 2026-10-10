# Veri dosyaları ve kaynakları

| Dosya | Kaynak | Lisans |
|---|---|---|
| `volvo_ecm_dids.json` | [OBDb/Volvo](https://github.com/OBDb/Volvo) `signalsets/v3/default.json`, yalnız motor ECM'i (`hdr 7E0`, mode 22). Alan biçimi değiştirilmeden çıkarıldı | CC BY-SA 4.0 — dağıtılırsa atıf ve aynı lisans gerekir |
| `dtc_codes.csv` | [mytrile/obd-trouble-codes](https://github.com/mytrile/obd-trouble-codes) (3071 genel SAE kodu, İngilizce açıklama) | MIT |

`volvo_ecm_dids.json` daha yeni Volvo'lar için derlenmiştir. 2010 S40 1.6D'nin (ECM büyük ihtimalle PSA/Bosch EDC16C34 tabanlı)
bunlara cevap verip vermediği bilinmiyor. Uygulamadaki "Araç testi" hepsini dener ve sonucu raporlar.
