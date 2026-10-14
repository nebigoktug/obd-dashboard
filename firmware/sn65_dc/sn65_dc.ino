// SN65 doğru akım testi (araçsız): CTX (D5) elle sürülür, CRX (D4) okunur. Çekme direnci değiştirilerek
// CRX telinin gerçekten sürüldüğü (kopuk değil) anlaşılır. CTX=0 iken CRX=0 olmalı (hat baskın), CTX=1 iken 1.
void setup() { Serial.begin(115200); pinMode(5, OUTPUT); digitalWrite(5, HIGH); }
static void probe() {
  for (int pull = 0; pull < 2; pull++) {
    pinMode(4, pull ? INPUT_PULLUP : INPUT_PULLDOWN);
    for (int tx = 1; tx >= 0; tx--) {
      digitalWrite(5, tx); delayMicroseconds(200);
      int hi = 0, flips = 0, last = digitalRead(4);
      for (int i = 0; i < 2000; i++) { int v = digitalRead(4); hi += v; if (v != last) flips++; last = v; delayMicroseconds(5); }
      Serial.printf("cekme %s, CTX=%d -> CRX 1 orani %d/2000, degisim %d\n", pull ? "yukari" : "asagi", tx, hi, flips);
    }
    digitalWrite(5, HIGH);
  }
  Serial.println("--");
}
// CTX'i 5 ms baskın tut, CRX'in ne zaman düşüp ne zaman geri kalktığını ölç
static void timing() {
  pinMode(4, INPUT_PULLDOWN);
  for (int k = 0; k < 3; k++) {
    digitalWrite(5, HIGH); delay(5);
    uint32_t t0 = micros(); digitalWrite(5, LOW);
    long fall = -1, rise = -1;
    while (micros() - t0 < 5000) {
      int v = digitalRead(4);
      if (fall < 0 && !v) fall = micros() - t0;
      if (fall >= 0 && rise < 0 && v) rise = micros() - t0;
    }
    digitalWrite(5, HIGH);
    Serial.printf("baskin: CRX dustu %ld us, geri kalkti %ld us (-1 = hic)\n", fall, rise);
  }
}
// 50 us baskından sonra CTX bırakılınca CRX ne kadar sürede çekinik (1) olur? Sonlandırma direnci yoksa yavaş olabilir
static void release() {
  pinMode(4, INPUT_PULLDOWN);
  for (int k = 0; k < 3; k++) {
    digitalWrite(5, HIGH); delay(5);
    digitalWrite(5, LOW); delayMicroseconds(50);
    uint32_t t0 = micros(); digitalWrite(5, HIGH);
    long rise = -1;
    while (micros() - t0 < 3000) { if (digitalRead(4)) { rise = micros() - t0; break; } }
    Serial.printf("birakinca CRX kalkti %ld us\n", rise);
  }
}
void loop() { if (Serial.available()) { char c = Serial.read(); if (c == 't') probe(); if (c == 'z') timing(); if (c == 'r') release(); } }
