// ESP32 Wi-Fi ELM327 köprüsü: uygulama bunu sıradan bir Wi-Fi ELM327 sanır.
//
// Wi-Fi: önce telefonun erişim noktasına bağlanır (internet telefonda kalır); aynı anda kendi ağını da açar
// (OBD-ESP32, 192.168.0.10 — Wi-Fi ELM327 klonlarının standart adresi). mDNS: obd-esp32.local,
// servisler _elm327._tcp (35000) ve _canlog._tcp (35001).
//
// Port 35000: ELM327 komutları (uygulamanın kullandığı AT komutları, OBD istekleri ISO-TP ile, ATMA).
// Port 35001: ham kayıt akışı — hattaki her çerçeve bir satır: "ms kimlik veri" (ondalık ms, onaltılık).
// Konfor hattı (MCP2515, OBD pin 3/11) satırlarının sonunda " m" etiketi var; motor hattı satırları etiketsiz.
//
// GÜVENLİK: TWAI dinleme kipinde başlar (hatta hiçbir şey göndermez, ACK bile vermez). Yalnız uygulama bir
// OBD isteği gönderdiğinde ve hat hızı kesin bilindiğinde normal kipe geçer. Yanlış hızda asla gönderilmez.
// MCP2515 (konfor hattı) her zaman dinleme kipinde kalır: bu yazılım o hatta hiçbir şey göndermez.
//
// Wi-Fi bilgileri secrets.h'de (depoda yok): make_secrets.sh ~/.config/obd-dashboard/esp32-wifi.txt'den üretir.
#include "driver/twai.h"
#include <WiFi.h>
#include <ESPmDNS.h>
#include <SPI.h>
#include "secrets.h"

static const gpio_num_t CAN_TX = GPIO_NUM_5;
static const gpio_num_t CAN_RX = GPIO_NUM_4;
static const int LED = 2;
static const char *ID_TEXT = "ELM327 v1.5";
static const char *DESC_TEXT = "OBD Dashboard ESP32";

// ---------- CAN ----------
struct Frame { uint32_t ms; uint32_t id; uint8_t ext; uint8_t dlc; uint8_t d[8]; uint8_t bus; };   // bus 0 motor, 1 konfor
static QueueHandle_t rawQ;        // ham akış (35001)
static QueueHandle_t elmQ;        // istek cevapları ve ATMA
static volatile int elmListen = 0;        // elmQ'ya: 0 hiçbir şey, 1 yalnız cevap adayları (istek beklerken), 2 hepsi (ATMA)
static volatile bool reqExt = false;
static bool isResponse(const struct Frame &f, bool ext);
static volatile int bitrate = 0;          // kilitlenen hız (kbit/s), 0 = henüz yok
static volatile bool seenExt = false, seenStd = false;
static volatile bool normalMode = false;
static volatile uint32_t rawDropped = 0, lastFrameMs = 0;
static SemaphoreHandle_t canLock;

static bool installCan(int kbps, bool normal) {
  twai_stop(); twai_driver_uninstall();
  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT(CAN_TX, CAN_RX, normal ? TWAI_MODE_NORMAL : TWAI_MODE_LISTEN_ONLY);
  g.rx_queue_len = 128; g.tx_queue_len = 8;
  twai_timing_config_t t = kbps == 500 ? (twai_timing_config_t)TWAI_TIMING_CONFIG_500KBITS()
                         : kbps == 250 ? (twai_timing_config_t)TWAI_TIMING_CONFIG_250KBITS()
                                       : (twai_timing_config_t)TWAI_TIMING_CONFIG_125KBITS();
  twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
  if (twai_driver_install(&g, &t, &f) != ESP_OK || twai_start() != ESP_OK) return false;
  normalMode = normal;
  return true;
}

/** Hız arama (dinleme kipinde, araca zarar vermez) ve alım. Ayrı görevde döner. */
static void canTask(void *) {
  static const int RATES[] = {500, 250, 125};
  int idx = 0;
  xSemaphoreTake(canLock, portMAX_DELAY);
  installCan(RATES[idx], false);
  xSemaphoreGive(canLock);
  uint32_t tryStart = millis();
  for (;;) {
    twai_message_t m;
    xSemaphoreTake(canLock, portMAX_DELAY);
    esp_err_t r = twai_receive(&m, pdMS_TO_TICKS(2));
    xSemaphoreGive(canLock);
    if (r == ESP_OK) {
      if (!bitrate) bitrate = RATES[idx];
      Frame f; f.ms = millis(); f.id = m.identifier; f.ext = m.extd; f.dlc = m.data_length_code; f.bus = 0;
      memcpy(f.d, m.data, 8);
      if (m.extd) seenExt = true; else seenStd = true;
      lastFrameMs = f.ms;
      if (xQueueSend(rawQ, &f, 0) != pdTRUE) rawDropped++;
      if (elmListen == 2 || (elmListen == 1 && isResponse(f, reqExt))) xQueueSend(elmQ, &f, 0);
    } else {
      // Kilitlenmeden önce 4 sn mesaj yoksa sıradaki hız. Kilitlendikten sonra hız değişmez (araç kapanınca da).
      if (!bitrate && millis() - tryStart > 4000) {
        idx = (idx + 1) % 3;
        xSemaphoreTake(canLock, portMAX_DELAY);
        installCan(RATES[idx], false);
        xSemaphoreGive(canLock);
        tryStart = millis();
      }
      // Hat hatası sonrası kurtarma
      twai_status_info_t st;
      if (twai_get_status_info(&st) == ESP_OK && st.state == TWAI_STATE_BUS_OFF) twai_initiate_recovery();
      if (twai_get_status_info(&st) == ESP_OK && st.state == TWAI_STATE_STOPPED) twai_start();
      vTaskDelay(1);
    }
  }
}

/** Göndermeden önce: hız biliniyorsa normal kipe geç. Bilinmiyorsa false (gönderilmez). */
static bool ensureNormal() {
  if (normalMode) return true;
  if (!bitrate) return false;
  xSemaphoreTake(canLock, portMAX_DELAY);
  bool ok = installCan(bitrate, true);
  xSemaphoreGive(canLock);
  return ok;
}

// ---------- Konfor hattı: MCP2515 (SPI, seviye dönüştürücü üzerinden; INT bağlı değil, yoklama) ----------
static const int MCP_CS = 27;
static SPISettings mcpSpi(500000, MSBFIRST, SPI_MODE0);   // BSS138 dönüştürücüler yavaş, masa testi bu hızda geçti
static volatile int msBitrate = 0;        // konfor hattı hızı, 0 = henüz yok
static volatile bool mcpOk = false;
static volatile uint32_t msFrames = 0, msOverflow = 0;

static uint8_t mcpRd(uint8_t a) {
  SPI.beginTransaction(mcpSpi); digitalWrite(MCP_CS, LOW);
  SPI.transfer(0x03); SPI.transfer(a); uint8_t v = SPI.transfer(0);
  digitalWrite(MCP_CS, HIGH); SPI.endTransaction();
  return v;
}
static void mcpWr(uint8_t a, uint8_t v) {
  SPI.beginTransaction(mcpSpi); digitalWrite(MCP_CS, LOW);
  SPI.transfer(0x02); SPI.transfer(a); SPI.transfer(v);
  digitalWrite(MCP_CS, HIGH); SPI.endTransaction();
}
static uint8_t mcpCmd(uint8_t c) {        // tek baytlık komut; READ STATUS (0xA0) için cevabı döndürür
  SPI.beginTransaction(mcpSpi); digitalWrite(MCP_CS, LOW);
  SPI.transfer(c); uint8_t v = SPI.transfer(0);
  digitalWrite(MCP_CS, HIGH); SPI.endTransaction();
  return v;
}
static bool mcpMode(uint8_t mode) {
  mcpWr(0x0F, mode);
  for (int i = 0; i < 50; i++) { if ((mcpRd(0x0E) & 0xE0) == mode) return true; vTaskDelay(1); }
  return false;
}
/** 8 MHz kristal. Yapılandırma kipinde ayarla, sonra DİNLEME kipine geç (0x60: ACK yok, gönderim yok). */
static bool mcpSetup(int kbps) {
  if (!mcpMode(0x80)) return false;
  uint8_t c1 = kbps == 125 ? 0x01 : 0x00, c2 = kbps == 500 ? 0x90 : 0xB1, c3 = kbps == 500 ? 0x02 : 0x05;
  mcpWr(0x2A, c1); mcpWr(0x29, c2); mcpWr(0x28, c3);
  mcpWr(0x60, 0x64);                      // RXB0: filtre yok, dolunca RXB1'e taşsın
  mcpWr(0x70, 0x60);                      // RXB1: filtre yok
  mcpWr(0x2B, 0x00); mcpWr(0x2C, 0x00); mcpWr(0x2D, 0x00);   // kesme yok, bayraklar ve taşma temiz
  return mcpMode(0x60);
}
static void mcpReadBuf(uint8_t instr) {    // 0x90 RXB0, 0x94 RXB1; CS kalkınca bayrak kendiliğinden silinir
  uint8_t r[13];
  SPI.beginTransaction(mcpSpi); digitalWrite(MCP_CS, LOW);
  SPI.transfer(instr);
  for (int i = 0; i < 13; i++) r[i] = SPI.transfer(0);
  digitalWrite(MCP_CS, HIGH); SPI.endTransaction();
  Frame f; f.ms = millis(); f.bus = 1;
  f.ext = (r[1] & 0x08) ? 1 : 0;
  f.id = f.ext ? ((uint32_t)r[0] << 21) | ((uint32_t)(r[1] & 0xE0) << 13) | ((uint32_t)(r[1] & 0x03) << 16) | (r[2] << 8) | r[3]
               : ((uint32_t)r[0] << 3) | (r[1] >> 5);
  f.dlc = r[4] & 0x0F; if (f.dlc > 8) f.dlc = 8;
  memcpy(f.d, r + 5, 8);
  msFrames++;
  if (xQueueSend(rawQ, &f, 0) != pdTRUE) rawDropped++;
}

/** Konfor hattı: hız arama (125 → 250 → 500, dinleme kipinde) ve alım. Kart yoksa görev biter. */
static void mcpTask(void *) {
  pinMode(MCP_CS, OUTPUT); digitalWrite(MCP_CS, HIGH);
  SPI.begin(18, 19, 23, MCP_CS);
  mcpCmd(0xC0); vTaskDelay(pdMS_TO_TICKS(10));   // RESET
  if ((mcpRd(0x0E) & 0xE0) != 0x80) { Serial.println("MCP2515 yok, konfor hattı kapalı"); vTaskDelete(nullptr); }
  static const int RATES[] = {125, 250, 500};
  int idx = 0;
  mcpOk = mcpSetup(RATES[idx]);
  uint32_t tryStart = millis();
  for (;;) {
    uint8_t st = mcpCmd(0xA0);
    if (st & 0x03) {
      if (!msBitrate) msBitrate = RATES[idx];
      if (st & 0x01) mcpReadBuf(0x90);
      if (st & 0x02) mcpReadBuf(0x94);
      continue;
    }
    // 1 ms uyku: 125 kbit/s'de en fazla ~1 çerçeve gelir, iki alım tamponu yeter
    uint8_t ef = mcpRd(0x2D);
    if (ef & 0xC0) { msOverflow++; mcpWr(0x2D, 0x00); }
    if (!msBitrate && millis() - tryStart > 4000) {
      idx = (idx + 1) % 3;
      mcpOk = mcpSetup(RATES[idx]);
      tryStart = millis();
    }
    vTaskDelay(1);
  }
}

#ifdef SIM
static void simEcu(uint32_t id, bool ext, const uint8_t *d, uint8_t dlc);
#endif
static bool canSend(uint32_t id, bool ext, const uint8_t *d, uint8_t dlc) {
#ifdef SIM
  simEcu(id, ext, d, dlc); return true;
#endif
  twai_message_t m = {};
  m.identifier = id; m.extd = ext; m.data_length_code = dlc;
  memcpy(m.data, d, dlc);
  xSemaphoreTake(canLock, portMAX_DELAY);
  esp_err_t r = twai_transmit(&m, pdMS_TO_TICKS(50));
  xSemaphoreGive(canLock);
  return r == ESP_OK;
}

// ---------- Ham akış (35001) ----------
static WiFiServer rawServer(35001), elmServer(35000);
static WiFiClient rawClient, elmClient;

/** Ham akış kendi görevinde: Wi-Fi takılınca yazma saniyelerce bloklanabilir (ilk sürüşte ELM cevapları bu yüzden
 *  gecikti, uygulama bağlantıyı kopardı). Takılmada çerçeveler kuyrukta bekler, ELM tarafı etkilenmez. */
static void pumpRaw();
static void rawTask(void *) {
  rawServer.begin();
  for (;;) { pumpRaw(); vTaskDelay(pdMS_TO_TICKS(5)); }
}

static void pumpRaw() {
  if (rawServer.hasClient()) {
    WiFiClient c = rawServer.accept();
    if (rawClient) rawClient.stop();
    rawClient = c; rawClient.setNoDelay(false);
    xQueueReset(rawQ);
    rawClient.printf("# obd-esp32 ham akis, hiz %d kbit/s, konfor %d kbit/s (m)\n", bitrate, msBitrate);
  }
  if (!rawClient || !rawClient.connected()) { xQueueReset(rawQ); return; }
  static char buf[1400];
  int n = 0;
  Frame f;
  while (n < (int)sizeof buf - 40 && xQueueReceive(rawQ, &f, 0) == pdTRUE) {
    n += snprintf(buf + n, sizeof buf - n, f.ext ? "%lu %08lX " : "%lu %03lX ", (unsigned long)f.ms, (unsigned long)f.id);
    for (int i = 0; i < f.dlc && i < 8; i++) n += snprintf(buf + n, sizeof buf - n, "%02X", f.d[i]);
    if (f.bus) { buf[n++] = ' '; buf[n++] = 'm'; }
    buf[n++] = '\n';
  }
  if (n) rawClient.write((const uint8_t *)buf, n);
}

// ---------- ELM327 ----------
static struct {
  bool echo, lf, spaces, headers, caf;
  int proto;                 // 0 = otomatik
  uint32_t txId; bool txExt; bool txSet;
  uint8_t prio;              // ATCP (29-bit üst 5 bit)
  bool craSet; uint32_t craId;
  bool cfSet; uint32_t cf, cm;
  uint32_t timeoutMs;
} cfg;

static void elmDefaults() {
  cfg.echo = true; cfg.lf = true; cfg.spaces = true; cfg.headers = false; cfg.caf = true;
  cfg.proto = 0; cfg.txSet = false; cfg.prio = 0x18; cfg.craSet = false; cfg.cfSet = false; cfg.cf = 0; cfg.cm = 0;
  cfg.timeoutMs = 200;
}

static String out;           // gönderilecek cevap
static void line(const String &s) { out += s; out += cfg.lf ? "\r\n" : "\r"; }
#ifdef SIM
static Stream &io = Serial;               // masa denemesi: ELM konuşması seri porttan
static bool ioUp() { return true; }
#else
static Stream &io = elmClient;
static bool ioUp() { return elmClient && elmClient.connected(); }
#endif
static void flushOut() { if (ioUp()) io.write((const uint8_t *)out.c_str(), out.length()); out = ""; }
static void prompt() { out += ">"; flushOut(); }

static String hexBytes(const uint8_t *d, int n) {
  String s; char t[4];
  for (int i = 0; i < n; i++) { snprintf(t, sizeof t, cfg.spaces && i ? " %02X" : "%02X", d[i]); s += t; }
  return s;
}
static String idText(const Frame &f) {
  char t[12];
  if (!f.ext) snprintf(t, sizeof t, "%03lX", (unsigned long)f.id);
  else if (cfg.spaces) snprintf(t, sizeof t, "%02lX %02lX %02lX %02lX", (unsigned long)(f.id >> 24), (unsigned long)(f.id >> 16 & 0xFF),
                                (unsigned long)(f.id >> 8 & 0xFF), (unsigned long)(f.id & 0xFF));
  else snprintf(t, sizeof t, "%08lX", (unsigned long)f.id);
  return String(t);
}

/** Etkin protokolün 29-bit olup olmadığı (otomatikte hatta görülene göre) */
static bool proto29() {
  if (cfg.proto == 7 || cfg.proto == 9) return true;
  if (cfg.proto == 6 || cfg.proto == 8) return false;
  return seenExt && !seenStd ? true : seenExt;   // ikisi de varsa 29-bit (S40: hepsi 29-bit)
}
static int protoNum() {
  if (cfg.proto) return cfg.proto;
  bool e = proto29();
  return bitrate == 250 ? (e ? 9 : 8) : (e ? 7 : 6);
}

static uint32_t requestId(bool &ext) {
  if (cfg.txSet) { ext = cfg.txExt; return cfg.txId; }
  ext = proto29();
  return ext ? 0x18DB33F1 : 0x7DF;
}

/** Bu çerçeve isteğimizin cevabı mı */
static bool isResponse(const Frame &f, bool ext) {
  if (cfg.craSet) return f.id == cfg.craId;
  if (cfg.cfSet) return (f.id & cfg.cm) == (cfg.cf & cfg.cm);
  if (!cfg.caf) return f.ext == ext;     // ham kipte süzgeç yoksa her şey (ELM gibi)
  if (ext) return f.ext && (f.id & 0xFFFFFF00) == 0x18DAF100;
  return !f.ext && f.id >= 0x7E8 && f.id <= 0x7EF;
}

static bool parseHex(const String &s, uint8_t *d, int &n) {
  n = 0;
  if (s.length() % 2) return false;
  for (unsigned i = 0; i < s.length(); i += 2) {
    char *e; char t[3] = {s[i], s[i + 1], 0};
    long v = strtol(t, &e, 16);
    if (*e) return false;
    d[n++] = v;
  }
  return true;
}

// ISO-TP birleştirme: kaynak başına (aynı anda birkaç ECU cevap verebilir)
struct Assembly { uint32_t id; uint16_t total; uint16_t got; uint8_t buf[512]; };
static Assembly asmb[4];
static int nAsm = 0;

static void sendFlowControl(const Frame &f) {
  uint8_t fc[8] = {0x30, 0x00, 0x00, 0, 0, 0, 0, 0};
  uint32_t id;
  if (f.ext) id = (f.id & 0xFFFF0000) | ((f.id & 0xFF) << 8) | ((f.id >> 8) & 0xFF);   // 18DAF1xx → 18DAxxF1
  else id = f.id - 8;                                                                     // 7E8 → 7E0
  canSend(id, f.ext, fc, 8);
}

/** OBD isteği: gönder, cevapları topla, ELM biçiminde yaz */
static void obdRequest(String cmd) {
  int expect = 0;
  if (cmd.length() % 2 == 1) {                 // tek uzunluk: son hane beklenen cevap sayısı
    expect = strtol(cmd.substring(cmd.length() - 1).c_str(), nullptr, 16);
    cmd.remove(cmd.length() - 1);
  }
  uint8_t req[8]; int n;
  if (!parseHex(cmd, req, n) || n == 0 || n > (cfg.caf ? 7 : 8)) { line("?"); return; }

  if (!bitrate) {
    if (cfg.proto == 0) line("SEARCHING...");
    uint32_t t0 = millis();
    while (!bitrate && millis() - t0 < 5000) delay(20);
    if (!bitrate) { line("UNABLE TO CONNECT"); return; }
  }
  if (!ensureNormal()) { line("CAN ERROR"); return; }

  bool ext; uint32_t id = requestId(ext);
  uint8_t frame[8] = {0};
  uint8_t dlc;
  if (cfg.caf) { frame[0] = n; memcpy(frame + 1, req, n); dlc = 8; }
  else { memcpy(frame, req, n); dlc = n; }

  xQueueReset(elmQ);
  reqExt = ext;
  elmListen = 1;
  if (!canSend(id, ext, frame, dlc)) { elmListen = 0; line("CAN ERROR"); return; }

  nAsm = 0;
  int frames = 0;
  bool any = false;
  uint32_t deadline = millis() + cfg.timeoutMs;
  while ((int32_t)(millis() - deadline) < 0) {
    Frame f;
    if (xQueueReceive(elmQ, &f, pdMS_TO_TICKS(5)) != pdTRUE) continue;
    if (!isResponse(f, ext)) continue;
    uint8_t pci = f.d[0] >> 4;
    if (cfg.caf && pci == 3) continue;          // akış denetimi (bize değil)
    any = true; frames++;
    deadline = millis() + cfg.timeoutMs;
    if (cfg.headers || !cfg.caf) {
      // Başlık açık ya da ham kip: her çerçeve kendi satırında
      int len = !cfg.caf ? f.dlc : pci == 0 ? min(8, (f.d[0] & 0x0F) + 1) : 8;
      line(cfg.headers ? idText(f) + (cfg.spaces ? " " : "") + hexBytes(f.d, len) : hexBytes(f.d, len));
      if (cfg.caf && pci == 1) sendFlowControl(f);
    } else if (pci == 0) {
      line(hexBytes(f.d + 1, min(7, (int)(f.d[0] & 0x0F))));
    } else if (pci == 1) {
      sendFlowControl(f);
      Assembly *a = nullptr;
      for (int i = 0; i < nAsm; i++) if (asmb[i].id == f.id) a = &asmb[i];
      if (!a && nAsm < 4) a = &asmb[nAsm++];
      if (a) {
        a->id = f.id; a->total = ((f.d[0] & 0x0F) << 8) | f.d[1]; a->got = 0;
        char t[8]; snprintf(t, sizeof t, "%03X", a->total); line(t);
        line(String("0:") + (cfg.spaces ? " " : "") + hexBytes(f.d + 2, 6));
        a->got = 6;
      }
    } else if (pci == 2) {
      for (int i = 0; i < nAsm; i++) if (asmb[i].id == f.id) {
        char t[4]; snprintf(t, sizeof t, "%X:", f.d[0] & 0x0F);
        line(String(t) + (cfg.spaces ? " " : "") + hexBytes(f.d + 1, 7));
        asmb[i].got += 7;
      }
    }
    // Beklenen sayıya ulaşınca dur; çok çerçeveli cevapta tümü gelince
    bool pending = false;
    for (int i = 0; i < nAsm; i++) if (asmb[i].got < asmb[i].total) pending = true;
    if (expect && frames >= expect && !pending) break;
  }
  elmListen = 0;
  if (!any) line("NO DATA");
}

/** ATMA: hattaki her çerçeveyi yaz, istemciden herhangi bir bayt gelince dur */
static void monitorAll() {
  xQueueReset(elmQ);
  elmListen = 2;
  while (ioUp()) {
    if (io.available()) { while (io.available()) io.read(); break; }
    Frame f;
    int k = 0;
    while (k < 40 && xQueueReceive(elmQ, &f, pdMS_TO_TICKS(k ? 0 : 5)) == pdTRUE) {
      line(cfg.headers ? idText(f) + (cfg.spaces ? " " : "") + hexBytes(f.d, f.dlc) : hexBytes(f.d, f.dlc));
      k++;
    }
    if (out.length()) flushOut();
  }
  elmListen = 0;
  out = "";
  line("");
  line("STOPPED");
}

static void atCommand(String c) {      // c: "AT" sonrası, boşluksuz, büyük harf
  auto ok = [] { line("OK"); };
  if (c == "Z") { elmDefaults(); line(""); line(ID_TEXT); return; }
  if (c == "WS") { line(""); line(ID_TEXT); return; }
  if (c == "D") { elmDefaults(); ok(); return; }
  if (c == "I") { line(ID_TEXT); return; }
  if (c == "@1") { line(DESC_TEXT); return; }
  if (c == "E0" || c == "E1") { cfg.echo = c[1] == '1'; ok(); return; }
  if (c == "L0" || c == "L1") { cfg.lf = c[1] == '1'; ok(); return; }
  if (c == "S0" || c == "S1") { cfg.spaces = c[1] == '1'; ok(); return; }
  if (c == "H0" || c == "H1") { cfg.headers = c[1] == '1'; ok(); return; }
  if (c == "CAF0" || c == "CAF1") { cfg.caf = c[3] == '1'; ok(); return; }
  if (c == "RV") { line("?"); return; }        // akü gerilimi ölçülmüyor (OBD pin 16 bağlı değil)
  if (c == "DPN") { char t[4]; snprintf(t, sizeof t, cfg.proto ? "%X" : "A%X", protoNum()); line(t); return; }
  if (c == "DP") {
    int p = protoNum();
    String s = String(cfg.proto ? "" : "AUTO, ") + "ISO 15765-4 (CAN " + (p == 7 || p == 9 ? "29" : "11") + "/" + (p <= 7 ? "500" : "250") + ")";
    line(s); return;
  }
  if (c.startsWith("SP") || c.startsWith("TP")) {
    String a = c.substring(2); if (a.startsWith("A")) a = a.substring(1);
    int p = strtol(a.c_str(), nullptr, 16);
    if (p != 0 && (p < 6 || p > 9)) { line("?"); return; }   // yalnız CAN protokolleri
    cfg.proto = p; ok(); return;
  }
  if (c.startsWith("SH")) {
    String h = c.substring(2);
    uint32_t v = strtoul(h.c_str(), nullptr, 16);
    if (h.length() == 3) { cfg.txId = v; cfg.txExt = false; }
    else if (h.length() == 6) { cfg.txId = ((uint32_t)cfg.prio << 24) | v; cfg.txExt = true; }
    else if (h.length() == 8) { cfg.txId = v & 0x1FFFFFFF; cfg.txExt = true; }
    else { line("?"); return; }
    cfg.txSet = true; ok(); return;
  }
  if (c.startsWith("CP")) {
    cfg.prio = strtoul(c.substring(2).c_str(), nullptr, 16) & 0x1F;
    if (cfg.txSet && cfg.txExt) cfg.txId = ((uint32_t)cfg.prio << 24) | (cfg.txId & 0xFFFFFF);
    ok(); return;
  }
  if (c.startsWith("CRA")) {
    String h = c.substring(3);
    if (h.isEmpty()) cfg.craSet = false;
    else { cfg.craId = strtoul(h.c_str(), nullptr, 16); cfg.craSet = true; }
    ok(); return;
  }
  if (c.startsWith("CF")) { cfg.cf = strtoul(c.substring(2).c_str(), nullptr, 16); cfg.cfSet = true; if (!cfg.cm) cfg.cm = 0x1FFFFFFF; ok(); return; }
  if (c.startsWith("CM")) { cfg.cm = strtoul(c.substring(2).c_str(), nullptr, 16); cfg.cfSet = true; ok(); return; }
  if (c.startsWith("ST")) { uint32_t v = strtoul(c.substring(2).c_str(), nullptr, 16); cfg.timeoutMs = v ? v * 4 : 200; ok(); return; }
  if (c == "MA") { monitorAll(); return; }
  // Etkisiz ama kabul edilenler: uyarlamalı zamanlama, akış denetimi ayarları, protokol kapatma, bellek, vb.
  if (c.startsWith("AT") || c.startsWith("FC") || c == "PC" || c.startsWith("M") || c.startsWith("AR") ||
      c.startsWith("CFC") || c.startsWith("D") || c.startsWith("V") || c.startsWith("R")) { ok(); return; }
  line("?");
}

static String inLine, lastCmd;

static void handleCommand(String raw) {
  if (cfg.echo) out += raw + "\r";
  String c; for (char ch : raw) if (ch != ' ' && ch != '\n') c += (char)toupper(ch);
  if (c.isEmpty()) c = lastCmd; else lastCmd = c;
  if (c.isEmpty()) { prompt(); return; }
  if (c.startsWith("AT")) atCommand(c.substring(2));
  else obdRequest(c);
  out += cfg.lf ? "\r\n" : "\r";
  prompt();
}

static void pumpElm() {
  if (elmServer.hasClient()) {
    WiFiClient c = elmServer.accept();
    if (elmClient) elmClient.stop();
    elmClient = c; elmClient.setNoDelay(true);
    elmDefaults(); inLine = ""; lastCmd = "";
  }
  if (!ioUp()) return;
  while (io.available()) {
    char ch = io.read();
    if (ch == '\r') { handleCommand(inLine); inLine = ""; }
    else if (ch != '\n' && inLine.length() < 64) inLine += ch;
  }
}


#ifdef SIM
// ---------- Masa simülasyonu: sahte motor beyni (18DAF110) ----------
// Mode 01: 00 (destek maskesi), 05 su, 0C devir, 0D hız (çoklu PID dahil); 09 02: VIN (çok çerçeveli, FC bekler).
static uint8_t simPend[64]; static int simPendLen = 0, simPendPos = 0, simSeq = 1;
static void simPush(const uint8_t *d) {
  Frame f; f.ms = millis(); f.id = 0x18DAF110; f.ext = 1; f.dlc = 8; f.bus = 0; memcpy(f.d, d, 8);
  if (elmListen == 2 || (elmListen == 1 && isResponse(f, reqExt))) xQueueSend(elmQ, &f, 0);
}
static void simEcu(uint32_t id, bool ext, const uint8_t *d, uint8_t dlc) {
  if (!ext || !(id == 0x18DB33F1 || id == 0x18DA10F1)) return;
  if (d[0] == 0x30 && simPendLen) {           // akış denetimi geldi: kalan çerçeveler
    while (simPendPos < simPendLen) {
      uint8_t f[8] = {(uint8_t)(0x20 | (simSeq++ & 0x0F)), 0, 0, 0, 0, 0, 0, 0};
      for (int i = 0; i < 7 && simPendPos < simPendLen; i++) f[1 + i] = simPend[simPendPos++];
      simPush(f);
    }
    simPendLen = 0; return;
  }
  uint8_t n = d[0] & 0x0F; if ((d[0] >> 4) != 0 || n < 1) return;
  uint8_t r[64]; int k = 0;
  if (d[1] == 0x01) {
    r[k++] = 0x41;
    for (int i = 2; i <= n; i++) {
      uint8_t pid = d[i];
      if (pid == 0x00) { uint8_t v[] = {0x00, 0x08, 0x18, 0x00, 0x00}; memcpy(r + k, v, 5); k += 5; }
      else if (pid == 0x05) { r[k++] = 0x05; r[k++] = 40 + 87; }
      else if (pid == 0x0C) { r[k++] = 0x0C; r[k++] = 0x0C; r[k++] = 0x80; }   // 800 rpm
      else if (pid == 0x0D) { r[k++] = 0x0D; r[k++] = 37; }
    }
    if (k == 1) return;                         // desteklenmeyen: susar
  } else if (d[1] == 0x09 && d[2] == 0x02) {
    const char *vin = "SIMULASYON123456X";
    r[k++] = 0x49; r[k++] = 0x02; r[k++] = 0x01;
    for (int i = 0; i < 17; i++) r[k++] = vin[i];
  } else return;
  if (k <= 7) { uint8_t f[8] = {(uint8_t)k}; memcpy(f + 1, r, k); simPush(f); return; }
  uint8_t f[8] = {(uint8_t)(0x10 | (k >> 8)), (uint8_t)k}; memcpy(f + 2, r, 6); simPush(f);
  memcpy(simPend, r + 6, k - 6); simPendLen = k - 6; simPendPos = 0; simSeq = 1;
}
#endif

// ---------- Wi-Fi ----------
static void startWifi() {
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAPConfig(IPAddress(192, 168, 0, 10), IPAddress(192, 168, 0, 10), IPAddress(255, 255, 255, 0));
  WiFi.softAP("OBD-ESP32", AP_PASS);
  WiFi.setSleep(false);                   // gecikme için güç tasarrufu kapalı
  WiFi.begin(HOTSPOT_SSID, HOTSPOT_PASS);
  MDNS.begin("obd-esp32");
  MDNS.addService("elm327", "tcp", 35000);
  MDNS.addService("canlog", "tcp", 35001);
  elmServer.begin();
}

void setup() {
  Serial.begin(115200);
  pinMode(LED, OUTPUT);
  rawQ = xQueueCreate(3072, sizeof(Frame));   // ~3 sn: telefonun erişim noktasındaki 1–1.5 sn Wi-Fi takılmalarında çerçeve düşmesin
  elmQ = xQueueCreate(256, sizeof(Frame));
  canLock = xSemaphoreCreateMutex();
  elmDefaults();
  startWifi();
#ifdef SIM
  bitrate = 500; seenExt = true; normalMode = true;
#else
  xTaskCreatePinnedToCore(canTask, "can", 4096, nullptr, 5, nullptr, 1);
  xTaskCreatePinnedToCore(mcpTask, "mcp", 4096, nullptr, 4, nullptr, 1);
#endif
  xTaskCreatePinnedToCore(rawTask, "raw", 6144, nullptr, 1, nullptr, 0);
#ifndef SIM
  Serial.println("\n== obd-esp32 ELM327 köprüsü ==");
#endif
}

void loop() {
  pumpElm();

  // Erişim noktası kopunca yeniden dene; ama kendi ağımıza bağlı biri varsa tarama yapma (onu bozar)
  static uint32_t lastTry = 0, lastLog = 0, lastLed = 0;
  if (WiFi.status() != WL_CONNECTED && WiFi.softAPgetStationNum() == 0 && millis() - lastTry > 15000) {
    lastTry = millis(); WiFi.disconnect(); WiFi.begin(HOTSPOT_SSID, HOTSPOT_PASS);
  }
  // Işık: mesaj geliyorsa hızlı; istemci bağlıysa sürekli yanık kısa söner
  uint32_t period = millis() - lastFrameMs < 500 && lastFrameMs ? 100 : 1000;
  if (millis() - lastLed > period / 2) { lastLed = millis(); digitalWrite(LED, !digitalRead(LED)); }
#ifdef SIM
  lastLog = millis();
#endif
  if (millis() - lastLog > 10000) {
    lastLog = millis();
    Serial.printf("hiz %d, kip %s, konfor %d (%s, %lu cerceve, tasma %lu), sta %s %s, ap istemci %d, elm %d, ham %d, dusen %lu\n", bitrate,
                  normalMode ? "normal" : "dinleme", msBitrate, mcpOk ? "dinleme" : "yok", (unsigned long)msFrames,
                  (unsigned long)msOverflow, WiFi.status() == WL_CONNECTED ? "bagli" : "yok",
                  WiFi.localIP().toString().c_str(), WiFi.softAPgetStationNum(), (int)elmClient.connected(),
                  (int)rawClient.connected(), (unsigned long)rawDropped);
  }
  delay(1);
}
