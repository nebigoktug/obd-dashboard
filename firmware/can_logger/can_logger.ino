// Araç dinleme kaydı (salt dinleme, hatta hiçbir şey göndermez; ACK bile vermez).
// HS-CAN'ı dinler, hızı bulur (500 → 250 → 125 kbit/s), görülen mesaj kimliklerini sayar ve
// her açılışta yeni bir oturum dosyasına (/sN.txt) yazar. Eve dönünce bilgisayara takılıp
// seri porttan "d" gönderilince bütün oturumlar dökülür ("x": hepsini sil).
// Mavi ışık: hızlı yanıp sönme = mesaj geliyor, yavaş = mesaj yok (hız aranıyor).
#include "driver/twai.h"
#include <LittleFS.h>
#include <Preferences.h>
#include <map>

static const gpio_num_t CAN_TX = GPIO_NUM_5;
static const gpio_num_t CAN_RX = GPIO_NUM_4;
static const int LED = 2;
static const int KEEP_SESSIONS = 8;

struct Seen { uint32_t n; uint8_t dlc; uint8_t data[8]; uint32_t firstMs; };
static std::map<uint32_t, Seen> seen;
static uint32_t frames = 0, rxErrMax = 0, busErr = 0;
static int bitrate = 0;
static char path[16];
static File raw;
static uint32_t rawCount = 0;
static const uint32_t RAW_MAX = 2000;   // ilk 2000 çerçeve ham olarak da yazılır

static bool startCan(int kbps) {
  twai_stop(); twai_driver_uninstall();
  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT(CAN_TX, CAN_RX, TWAI_MODE_LISTEN_ONLY);
  g.rx_queue_len = 64;
  twai_timing_config_t t = kbps == 500 ? (twai_timing_config_t)TWAI_TIMING_CONFIG_500KBITS()
                         : kbps == 250 ? (twai_timing_config_t)TWAI_TIMING_CONFIG_250KBITS()
                                       : (twai_timing_config_t)TWAI_TIMING_CONFIG_125KBITS();
  twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
  if (twai_driver_install(&g, &t, &f) != ESP_OK || twai_start() != ESP_OK) return false;
  bitrate = kbps;
  return true;
}

static void dump() {
  File root = LittleFS.open("/");
  for (File f = root.openNextFile(); f; f = root.openNextFile()) {
    Serial.printf("\n##### %s (%u bayt)\n", f.name(), (unsigned)f.size());
    while (f.available()) Serial.write(f.read());
  }
  Serial.println("\n##### SON");
}

static void writeSummary() {
  File f = LittleFS.open(path, "w");
  if (!f) return;
  f.printf("oturum %s, sure %lus, hiz %d kbit/s, cerceve %lu, kimlik %u, rx_hata_max %lu, hata_olayi %lu\n",
           path, millis() / 1000, bitrate, (unsigned long)frames, (unsigned)seen.size(),
           (unsigned long)rxErrMax, (unsigned long)busErr);
  f.println("kimlik,adet,ilk_ms,dlc,son_veri");
  for (auto &kv : seen) {
    f.printf("%03lX,%lu,%lu,%u,", (unsigned long)kv.first, (unsigned long)kv.second.n,
             (unsigned long)kv.second.firstMs, kv.second.dlc);
    for (int i = 0; i < kv.second.dlc && i < 8; i++) f.printf("%02X", kv.second.data[i]);
    f.println();
  }
  f.close();
}

static void pruneOld(int cur) {
  for (int k = cur - KEEP_SESSIONS; k >= 0 && k > cur - 50; k--) {
    char p[16];
    snprintf(p, sizeof p, "/s%d.txt", k); LittleFS.remove(p);
    snprintf(p, sizeof p, "/r%d.csv", k); LittleFS.remove(p);
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(LED, OUTPUT);
  if (!LittleFS.begin(true)) Serial.println("HATA: dosya sistemi");
  Preferences pr; pr.begin("log", false);
  int n = pr.getInt("boot", 0) + 1; pr.putInt("boot", n); pr.end();
  snprintf(path, sizeof path, "/s%d.txt", n);
  char rp[16]; snprintf(rp, sizeof rp, "/r%d.csv", n);
  pruneOld(n);
  raw = LittleFS.open(rp, "w");
  if (raw) raw.println("ms,kimlik,dlc,veri");
  Serial.printf("\n== CAN dinleme kaydi, oturum %d ==\n'd' = kayitlari dok, 'x' = hepsini sil\n", n);
  startCan(500);
}

void loop() {
  static uint32_t lastFrameMs = 0, rateStart = millis(), lastSave = 0, lastLed = 0;
  static const int RATES[] = {500, 250, 125};
  static int rateIdx = 0;
  static bool locked = false;

  twai_message_t m;
  while (twai_receive(&m, pdMS_TO_TICKS(5)) == ESP_OK) {
    frames++; lastFrameMs = millis(); locked = true;
    Seen &s = seen[m.identifier];
    if (s.n == 0) s.firstMs = millis();
    s.n++; s.dlc = m.data_length_code; memcpy(s.data, m.data, 8);
    if (raw && rawCount < RAW_MAX) {
      raw.printf("%lu,%03lX,%u,", millis(), (unsigned long)m.identifier, m.data_length_code);
      for (int i = 0; i < m.data_length_code && i < 8; i++) raw.printf("%02X", m.data[i]);
      raw.println();
      if (++rawCount == RAW_MAX) raw.close();
    }
  }

  twai_status_info_t st;
  if (twai_get_status_info(&st) == ESP_OK) {
    if (st.rx_error_counter > rxErrMax) rxErrMax = st.rx_error_counter;
    busErr = st.bus_error_count;
  }

  // Hız arama: 4 sn mesaj yoksa sıradaki hız (dinleme kipinde yanlış hız araca zarar vermez)
  if (!locked && millis() - rateStart > 4000) {
    rateIdx = (rateIdx + 1) % 3;
    startCan(RATES[rateIdx]);
    rateStart = millis();
  }

  // Işık: mesaj geliyorsa hızlı, yoksa yavaş
  uint32_t period = (millis() - lastFrameMs < 500 && lastFrameMs) ? 100 : 1000;
  if (millis() - lastLed > period / 2) { lastLed = millis(); digitalWrite(LED, !digitalRead(LED)); }

  // Özet 10 sn'de bir dosyaya (kontak kapanınca son durum kalır)
  if (millis() - lastSave > 10000) {
    lastSave = millis();
    writeSummary();
    if (raw && rawCount < RAW_MAX) raw.flush();
    Serial.printf("hiz %d, cerceve %lu, kimlik %u, rx_hata_max %lu\n", bitrate, (unsigned long)frames,
                  (unsigned)seen.size(), (unsigned long)rxErrMax);
  }

  if (Serial.available()) {
    int c = Serial.read();
    if (c == 'd') dump();
    if (c == 'x') {
      File root = LittleFS.open("/");
      std::vector<String> names;
      for (File f = root.openNextFile(); f; f = root.openNextFile()) names.push_back(String("/") + f.name());
      root.close();
      if (raw) raw.close();
      for (auto &p : names) LittleFS.remove(p);
      Serial.println("silindi");
    }
  }
}
