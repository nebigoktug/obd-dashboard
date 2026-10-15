# Bilgisayarsız teşhis: Termux + Claude Code

Amaç: araçta, telefondan CAN kayıtlarını incelemek, sinyal çözmek ve kutuyla konuşmak. Uygulama derlemek için bilgisayar hâlâ gerekli; bu kılavuz yalnız teşhis içindir.

## Kurulum (bir kez)

1. Termux'u **F-Droid**'den kur. Play Store sürümü eski.
2. Termux'ta şunları çalıştır:
   ```sh
   pkg update && pkg upgrade
   pkg install python git nodejs-lts ripgrep netcat-openbsd
   termux-setup-storage          # "Tüm dosyalara erişim" izni ver
   npm install -g @anthropic-ai/claude-code
   ```
   Claude Code kendi arama aracında sorun çıkarırsa: `export USE_BUILTIN_RIPGREP=0` (`~/.bashrc`'ye ekle).
3. Depoyu telefona al:
   - GitHub'dan: `git clone https://github.com/nebigoktug/obd-dashboard`
   - ya da bilgisayardan gelen paketten: `git clone ~/storage/downloads/obd-bridge.bundle obd-bridge`
4. `cd obd-bridge && claude`

## Kayıtlar nerede?

Uygulama, kapanan her CAN kaydının kopyasını **İndirilenler/OBD-CAN/** klasörüne yazar. Termux'ta bu klasörün yolu `~/storage/downloads/OBD-CAN/can_<tarih>.txt.gz`.

Uygulamanın kendi klasörü (files/can) yer dolunca en eski kayıtları siler; İndirilenler'deki kopyalara dokunmaz. Bu kopyaları sen silersin.

## İnceleme aracı

```sh
python tools/can_tool.py ids    ~/storage/downloads/OBD-CAN/can_X.txt.gz
python tools/can_tool.py events can_X.txt.gz --ref 221286=20:09:12 --max 40
python tools/can_tool.py trace  can_X.txt.gz 12404002 --byte 7
python tools/can_tool.py window can_X.txt.gz 257400-258700,267100-268500 250000-256000
python tools/can_tool.py loss   can_X.txt.gz
```

- `--ref MS=SS:DD:ss`: kayıttaki bir anı (ör. sinyal başlangıcı) duvar saatine bağlar. Test listesindeki saatlerle karşılaştırmak için kullanılır.
- Bilinen sinyaller: [can-sinyalleri.md](can-sinyalleri.md).

## Canlı izleme

Uygulama ESP32'ye bağlıyken ham akışın bir kopyasını telefonun içinde **127.0.0.1:35002**'de yayınlar. Uygulamanın kaydı bundan etkilenmez.

```sh
python tools/can_tool.py live                 # bilinen sinyaller: sinyal, far kolu, kapı, el freni, klima, tuşlar...
python tools/can_tool.py live --raw           # + bilinmeyen kimliklerde seyrek değişen baytlar ("yeni?")
python tools/can_tool.py live --raw --save ~/canli.txt
```

Claude Code'da izleme arka planda başlatılır, kullanıcı bir tuşa basınca son satırlara bakılır.
Ya da çıktı bir dosyaya yazılır (`> ~/canli.log`) ve Claude o dosyayı izler.
Denemek için araçsız: `python tools/can_tool.py live --file ~/storage/downloads/OBD-CAN/can_X.txt.gz`.

## Kutuyla doğrudan konuşma

Telefon erişim noktasıyken kutu `obd-esp32` adıyla görünür. Ad çözülmezse erişim noktası istemci listesindeki IP'yi kullan.

- **Ham akış:** `nc obd-esp32 35001 > canli.txt`
  - Kutu tek ham istemci kabul eder. Termux bağlanınca uygulamanın kaydı kesilir.
- **ELM327:** `nc obd-esp32 35000`, sonra `ATZ`, `0105` ve benzeri komutlar yazılır.
  - Uygulama bağlıyken bu kapı dolu olur.

## Güvenlik kuralları

- Konfor hattına (MCP2515) hiçbir şey gönderilmez; kart hep dinleme kipinde kalır.
- Motor hattına yalnız okuma sorguları gönderilir: OBD mode 01/03 ve Volvo okuma (A6/AE).
  - **Hata kodu silme (04, AF) yok.**
  - Volvo sorgusu göndermeden önce kullanıcıya göster.
- Kayıtlarda VIN olabilir. Depoya ya da internete kayıt yükleme.
