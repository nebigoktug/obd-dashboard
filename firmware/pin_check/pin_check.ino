// Tanı: SN65 bağlantısını CAN sürücüsü olmadan yoklar.
// D4 (CRX) çekme direnciyle okunur: SN65 çalışıyorsa hep sürer (çekme yönünden bağımsız).
// D5'i (CTX) LOW yapınca hat baskın olur, D4 LOW'a inmeli; HIGH yapınca D4 HIGH'a dönmeli.
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n== pin tanisi ==");
}

void loop() {
  pinMode(5, OUTPUT); digitalWrite(5, HIGH);
  delay(5);
  pinMode(4, INPUT_PULLUP);   delay(5); int up = digitalRead(4);
  pinMode(4, INPUT_PULLDOWN); delay(5); int down = digitalRead(4);
  digitalWrite(5, LOW); delay(5);
  pinMode(4, INPUT_PULLUP);   delay(5); int domUp = digitalRead(4);
  pinMode(4, INPUT_PULLDOWN); delay(5); int domDown = digitalRead(4);
  digitalWrite(5, HIGH);
  Serial.printf("CTX=HIGH: CRX(pullup)=%d CRX(pulldown)=%d | CTX=LOW: CRX(pullup)=%d CRX(pulldown)=%d\n", up, down, domUp, domDown);
  delay(1000);
}
