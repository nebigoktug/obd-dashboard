// Masa testi (araçsız): ESP32 TWAI + SN65HVD230.
// TWAI "NO_ACK" kipinde kendi gönderdiği çerçeveyi alıcı (SN65) üzerinden geri okur. Geri gelirse
// CTX→D5, CRX→D4 kabloları, 3V3/GND ve SN65 lehimleri doğrudur. Seri port 115200.
#include "driver/twai.h"

static const gpio_num_t CAN_TX = GPIO_NUM_5;   // SN65 CTX
static const gpio_num_t CAN_RX = GPIO_NUM_4;   // SN65 CRX

static uint32_t sent = 0, echoed = 0;

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n== ESP32 CAN masa testi ==");
  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT(CAN_TX, CAN_RX, TWAI_MODE_NO_ACK);
  twai_timing_config_t t = TWAI_TIMING_CONFIG_500KBITS();
  twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
  if (twai_driver_install(&g, &t, &f) != ESP_OK) { Serial.println("HATA: surucu kurulamadi"); return; }
  if (twai_start() != ESP_OK) { Serial.println("HATA: TWAI baslamadi"); return; }
  Serial.println("TWAI basladi (500 kbit/s, NO_ACK, kendi kendine alma)");
}

void loop() {
  twai_message_t m = {};
  m.identifier = 0x123;
  m.data_length_code = 4;
  m.self = 1;                       // kendi çerçevesini de al
  m.data[0] = 0xCA; m.data[1] = 0xFE; m.data[2] = sent >> 8; m.data[3] = sent;
  if (twai_transmit(&m, pdMS_TO_TICKS(100)) == ESP_OK) sent++;

  twai_message_t r;
  bool got = false;
  while (twai_receive(&r, pdMS_TO_TICKS(200)) == ESP_OK) {
    if (r.identifier == 0x123 && r.data[0] == 0xCA && r.data[1] == 0xFE) { echoed++; got = true; }
  }
  twai_status_info_t s;
  twai_get_status_info(&s);
  Serial.printf("%s gonderilen=%lu geri_gelen=%lu tx_hata=%lu rx_hata=%lu durum=%d\n",
                got ? "TAMAM" : "YOK  ", (unsigned long)sent, (unsigned long)echoed,
                (unsigned long)s.tx_error_counter, (unsigned long)s.rx_error_counter, (int)s.state);
  if (s.state == TWAI_STATE_BUS_OFF) { twai_initiate_recovery(); }
  if (s.state == TWAI_STATE_STOPPED) { twai_start(); }
  delay(800);
}
