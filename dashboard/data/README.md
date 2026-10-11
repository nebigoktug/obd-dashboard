# Veri dosyaları ve kaynakları

| Dosya | Kaynak | Lisans |
|---|---|---|
| `volvo_ecm_dids.json` | [OBDb/Volvo](https://github.com/OBDb/Volvo) `signalsets/v3/default.json`, yalnız motor ECM'i (`hdr 7E0`, mode 22). Alan biçimi değiştirilmeden çıkarıldı | CC BY-SA 4.0 — dağıtılırsa atıf ve aynı lisans gerekir |
| `app/src/main/assets/dtc_tr.tsv` | Kendi derlemesi: SAE J2012 genel kod başlıkları, Türkçe (269 P/U kodu). Gershy13/dtc-lookup (MIT) ile karşılaştırıldı | — |

`volvo_ecm_dids.json` daha yeni Volvo'lar için derlenmiştir. 2010 S40 1.6D'nin (ECM büyük ihtimalle PSA/Bosch EDC16C34 tabanlı)
bunlara cevap verip vermediği bilinmiyor. Uygulamadaki "Araç testi" hepsini dener ve sonucu raporlar.

Not (2026-10-11): eski `dtc_codes.csv` (mytrile/obd-trouble-codes) silindi. Kodlar kaymıştı (ör. P0300, P0420 yanlış açıklama).
