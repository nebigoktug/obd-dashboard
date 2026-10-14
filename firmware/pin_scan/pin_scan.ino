// Tanı: CTX teli hangi pinde? Her uygun pini sırayla LOW yapar, CRX (D4) LOW'a inerse bildirir.
const int PINS[] = {2, 5, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33};
void setup() { Serial.begin(115200); delay(500); Serial.println("\n== pin taramasi =="); pinMode(4, INPUT_PULLUP); }
void loop() {
  String hit = "";
  for (int p : PINS) {
    pinMode(p, OUTPUT); digitalWrite(p, LOW); delayMicroseconds(200);
    if (digitalRead(4) == LOW) hit += "D" + String(p) + " ";
    digitalWrite(p, HIGH); pinMode(p, INPUT);
    delayMicroseconds(200);
  }
  Serial.println(hit.length() ? "CRX'i dusuren pin: " + hit : "hicbir pin CRX'i dusurmedi");
  delay(1000);
}
