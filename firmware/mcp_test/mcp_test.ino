// MCP2515 masa testi (araçsız): kablolamayı adım adım dener, hangi telin sorunlu olduğunu söyler.
// Bağlantı (seviye dönüştürücü üzerinden): SCK D18, SI D23, SO D19, CS D27; INT kullanılmıyor.
// 1) Sıfırla + CANSTAT oku (yapılandırma kipi 0x80 beklenir) → SO/SCK/CS
// 2) Bir kayda yaz, geri oku → SI
// 3) Döngü (loopback) kipi: kendi gönderdiği çerçeveyi alır → kartın içi + 8 MHz kristal
// Hatta hiçbir şey çıkmaz (loopback kipinde TX ucu pasif). Seri port 115200.
#include <SPI.h>

static const int PIN_CS = 27;
static SPISettings spi(500000, MSBFIRST, SPI_MODE0);   // BSS138 dönüştürücüler yavaş

static uint8_t rd(uint8_t a) {
  SPI.beginTransaction(spi); digitalWrite(PIN_CS, LOW);
  SPI.transfer(0x03); SPI.transfer(a); uint8_t v = SPI.transfer(0);
  digitalWrite(PIN_CS, HIGH); SPI.endTransaction();
  return v;
}
static void wr(uint8_t a, uint8_t v) {
  SPI.beginTransaction(spi); digitalWrite(PIN_CS, LOW);
  SPI.transfer(0x02); SPI.transfer(a); SPI.transfer(v);
  digitalWrite(PIN_CS, HIGH); SPI.endTransaction();
}
static void cmd(uint8_t c) {
  SPI.beginTransaction(spi); digitalWrite(PIN_CS, LOW); SPI.transfer(c);
  digitalWrite(PIN_CS, HIGH); SPI.endTransaction();
}

static bool setMode(uint8_t mode) {     // CANCTRL üst 3 bit
  wr(0x0F, mode);
  for (int i = 0; i < 50; i++) { if ((rd(0x0E) & 0xE0) == mode) return true; delay(2); }
  return false;
}

static void runTest() {
  Serial.println("\n== MCP2515 masa testi ==");
  cmd(0xC0); delay(10);                       // RESET
  uint8_t st = rd(0x0E);
  Serial.printf("1) CANSTAT = 0x%02X ", st);
  if (st == 0xFF) { Serial.println("HATA: hep 1 → SO (D19) teli, MCP2515 VCC ya da dönüştürücü HV/LV beslemesi"); return; }
  if (st == 0x00) { Serial.println("HATA: hep 0 → SCK (D18), CS (D27) ya da MCP2515 GND"); return; }
  if ((st & 0xE0) != 0x80) { Serial.println("HATA: beklenen 0x80 → CS (D27) ya da SCK teli gevşek"); return; }
  Serial.println("TAMAM (okuma çalışıyor)");

  wr(0x2A, 0x5A); uint8_t a = rd(0x2A);       // CNF1'e yaz-oku
  wr(0x2A, 0xA5); uint8_t b = rd(0x2A);
  Serial.printf("2) yaz/oku 5A→%02X A5→%02X ", a, b);
  if (a != 0x5A || b != 0xA5) { Serial.println("HATA: SI (D23) teli ya da dönüştürücü 2. kanal"); return; }
  Serial.println("TAMAM (yazma çalışıyor)");

  // 8 MHz kristal, 125 kbit/s: CNF1=0x01 CNF2=0xB1 CNF3=0x05
  wr(0x2A, 0x01); wr(0x29, 0xB1); wr(0x28, 0x05);
  wr(0x60, 0x60); wr(0x70, 0x60);             // RXB0/RXB1: filtre yok
  wr(0x2C, 0x00);                             // CANINTF temizle
  if (!setMode(0x40)) { Serial.println("3) HATA: döngü kipine geçmedi → kristal ya da kart arızası"); return; }
  // TXB0: standart kimlik 0x123, 4 bayt CA FE 12 34
  wr(0x31, 0x24); wr(0x32, 0x60); wr(0x35, 4);
  wr(0x36, 0xCA); wr(0x37, 0xFE); wr(0x38, 0x12); wr(0x39, 0x34);
  cmd(0x81);                                  // RTS TXB0
  uint32_t t0 = millis();
  while (!(rd(0x2C) & 0x01) && millis() - t0 < 200) delay(1);
  uint8_t f = rd(0x2C);
  if (!(f & 0x01)) { Serial.printf("3) HATA: çerçeve geri gelmedi (CANINTF=0x%02X, TXB0CTRL=0x%02X) → kristal/kart\n", f, rd(0x30)); return; }
  uint16_t id = (rd(0x61) << 3) | (rd(0x62) >> 5);
  uint8_t d0 = rd(0x66), d1 = rd(0x67), d2 = rd(0x68), d3 = rd(0x69);
  wr(0x2C, 0x00);
  Serial.printf("3) döngü: kimlik %03X veri %02X%02X%02X%02X ", id, d0, d1, d2, d3);
  if (id == 0x123 && d0 == 0xCA && d1 == 0xFE && d2 == 0x12 && d3 == 0x34) Serial.println("TAMAM");
  else { Serial.println("HATA: veri bozuk → SPI hızı/dönüştürücü"); return; }
  setMode(0x80);
  Serial.println("\nSONUC: MCP2515 kablolaması doğru. Araca hazır.");
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_CS, OUTPUT); digitalWrite(PIN_CS, HIGH);
  SPI.begin(18, 19, 23, PIN_CS);
  delay(500);
  runTest();
  Serial.println("\n('t' = testi tekrarla)");
}

void loop() {
  if (Serial.available() && Serial.read() == 't') runTest();
}
