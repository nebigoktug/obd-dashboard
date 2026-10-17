// Yönetim sayfası: modem arayüzü gibi tek sayfa. Durumu /api/status'tan 2 sn'de bir okur.
#pragma once
static const char PANEL_HTML[] PROGMEM = R"HTML(<!doctype html>
<html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>OBD-ESP32 Yönetim</title>
<style>
:root{--bg:#f2f4f7;--card:#fff;--fg:#1d2430;--mut:#6b7686;--line:#e3e7ee;--acc:#1f6feb;--ok:#1a7f37;--bad:#cf222e;--warn:#9a6700}
@media (prefers-color-scheme:dark){:root{--bg:#0f1318;--card:#171c23;--fg:#e6edf3;--mut:#8b949e;--line:#2a313b;--acc:#4493f8;--ok:#3fb950;--bad:#f85149;--warn:#d29922}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
header{background:var(--card);border-bottom:1px solid var(--line);padding:14px 16px;display:flex;align-items:center;gap:10px}
header b{font-size:17px}header span{color:var(--mut);font-size:13px;margin-left:auto}
nav{display:flex;gap:4px;padding:10px 16px 0;overflow-x:auto}
nav button{border:0;background:none;color:var(--mut);padding:8px 12px;border-radius:8px 8px 0 0;font:inherit;cursor:pointer;white-space:nowrap}
nav button.on{background:var(--card);color:var(--fg);font-weight:600}
main{padding:0 16px 24px;max-width:860px}
section{display:none;background:var(--card);border-radius:0 10px 10px 10px;padding:16px}section.on{display:block}
h3{margin:18px 0 8px;font-size:14px;color:var(--mut);text-transform:uppercase;letter-spacing:.04em}h3:first-child{margin-top:0}
table{width:100%;border-collapse:collapse}td{padding:7px 0;border-bottom:1px solid var(--line);vertical-align:top}
td:first-child{color:var(--mut);width:46%}td:last-child{font-variant-numeric:tabular-nums}
.dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:6px;background:var(--mut)}
.g{background:var(--ok)}.r{background:var(--bad)}.y{background:var(--warn)}
label{display:block;margin:12px 0 4px;color:var(--mut);font-size:13px}
input{width:100%;padding:9px 10px;border:1px solid var(--line);border-radius:8px;background:var(--bg);color:var(--fg);font:inherit}
.btn{margin-top:14px;padding:9px 16px;border:0;border-radius:8px;background:var(--acc);color:#fff;font:inherit;cursor:pointer}
.btn.sec{background:var(--line);color:var(--fg)}.btn.red{background:var(--bad)}
.note{color:var(--mut);font-size:13px;margin-top:8px}.msg{margin-top:10px;font-size:14px}
progress{width:100%;height:10px;margin-top:12px}
</style></head><body>
<header><b>OBD-ESP32</b><span id="hv">…</span></header>
<nav><button class="on" data-t="durum">Durum</button><button data-t="wifi">Wi-Fi</button><button data-t="yazilim">Yazılım</button><button data-t="sistem">Sistem</button></nav>
<main>
<section id="durum" class="on">
<h3>Araç hatları</h3><table>
<tr><td>Motor hattı (pin 6/14)</td><td id="hs">…</td></tr>
<tr><td>Konfor hattı (pin 3/11)</td><td id="ms">…</td></tr>
</table>
<h3>Uygulama</h3><table>
<tr><td>ELM bağlantısı (35000)</td><td id="elm">…</td></tr>
<tr><td>Ham kayıt akışı (35001)</td><td id="raw">…</td></tr>
<tr><td>Düşen çerçeve / kuyruk</td><td id="drop">…</td></tr>
</table>
<h3>Ağ</h3><table>
<tr><td>Telefon ağı</td><td id="sta">…</td></tr>
<tr><td>Kutunun ağı (OBD-ESP32)</td><td id="ap">…</td></tr>
</table>
<h3>Kutu</h3><table>
<tr><td>Çalışma süresi</td><td id="up">…</td></tr>
<tr><td>Son açılma nedeni</td><td id="rs">…</td></tr>
<tr><td>Boş bellek (en düşük)</td><td id="heap">…</td></tr>
</table>
<div class="note">2 saniyede bir yenilenir. Kutunun besleme voltajı ölçülmüyor (OBD pin 16 bağlı değil).</div>
</section>
<section id="wifi">
<h3>Telefon ağı (internet için)</h3>
<label>Ağ adı</label><input id="ssid" maxlength="32" autocomplete="off">
<label>Şifre (boş bırakılırsa değişmez)</label><input id="pass" type="password" maxlength="63" autocomplete="new-password">
<h3>Kutunun kendi ağı</h3>
<label>OBD-ESP32 ağ şifresi (boş bırakılırsa değişmez, en az 8 karakter). Bu sayfanın şifresi de budur.</label>
<input id="appass" type="password" maxlength="63" autocomplete="new-password">
<button class="btn" id="wsave">Kaydet</button>
<div class="msg" id="wmsg"></div>
<div class="note">Kayıt yeniden başlatınca geçerli olur. Yanlış girersen kutu telefona bağlanamaz; o zaman telefonla OBD-ESP32 ağına bağlanıp 192.168.0.10 adresinden düzeltebilirsin.</div>
<button class="btn sec" id="wreset">Fabrika Wi-Fi ayarlarına dön</button>
</section>
<section id="yazilim">
<h3>Kurulu yazılım</h3><table>
<tr><td>Sürüm</td><td id="ver">…</td></tr>
<tr><td>Boyut / boş yer</td><td id="size">…</td></tr>
</table>
<h3>Yazılım güncelle</h3>
<input type="file" id="fw" accept=".bin">
<button class="btn" id="up">Yükle</button>
<progress id="pg" max="100" value="0" hidden></progress>
<div class="msg" id="umsg"></div>
<div class="note">Araç dururken yükle. Yükleme sırasında uygulamanın bağlantısı birkaç saniye takılabilir. Dosya bozuksa kutu eski yazılımla açılmaya devam eder.</div>
</section>
<section id="sistem">
<h3>Konfor hattında gönderme</h3><table>
<tr><td>Durum</td><td id="mss">…</td></tr>
<tr><td>Gönderilen çerçeve</td><td id="msn">…</td></tr>
</table>
<div class="note">Kapalıyken kutu konfor hattını yalnız dinler (hatta hiçbir şey göndermez). Açınca uygulama o hattaki modüllere istek gönderebilir. Yalnız araç dururken aç; 30 dakika sonra ve her yeniden başlatmada kendiliğinden kapanır.</div>
<button class="btn" id="mson">Aç (30 dk)</button> <button class="btn sec" id="msoff">Kapat</button>
<div class="msg" id="msmsg"></div>
<h3>Yeniden başlat</h3>
<div class="note">Kutu yaklaşık 5 saniyede geri gelir, uygulama kendiliğinden yeniden bağlanır.</div>
<button class="btn red" id="rb">Yeniden başlat</button>
<div class="msg" id="smsg"></div>
</section>
</main>
<script>
const $=id=>document.getElementById(id);
document.querySelectorAll('nav button').forEach(b=>b.onclick=()=>{
  document.querySelectorAll('nav button,section').forEach(e=>e.classList.remove('on'));
  b.classList.add('on');$(b.dataset.t).classList.add('on');});
const dot=c=>'<span class="dot '+c+'"></span>';
const dur=s=>{const d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60);return (d?d+' g ':'')+(h?h+' sa ':'')+m+' dk '+(s%60)+' sn'};
const kb=b=>(b/1024).toFixed(0)+' KB';
const set=(id,html)=>{$(id).innerHTML=html};
let filled=false;
async function tick(){
  try{
    const r=await fetch('/api/status');if(!r.ok)throw 0;const s=await r.json();
    $('hv').textContent='sürüm '+s.ver;
    set('hs',s.hsRate?dot(s.hsFps>0?'g':'y')+s.hsRate+' kbit/s, '+s.hsMode+', '+s.hsFps+' çerçeve/sn':dot('y')+'hız aranıyor (araç kapalı olabilir)');
    set('mss',s.msSend?dot('y')+'AÇIK · '+Math.ceil(s.msSendLeft/60)+' dk kaldı':dot('g')+'kapalı (yalnız dinleme)');
    set('msn',s.msSent);
    set('ms',!s.msOk?dot('r')+'MCP2515 yok':s.msRate?dot(s.msFps>0?'g':'y')+s.msRate+' kbit/s, '+(s.msSend?'GÖNDERME AÇIK':'dinleme')+', '+s.msFps+' çerçeve/sn'+(s.msOverflow?' · taşma '+s.msOverflow:''):dot('y')+'hız aranıyor');
    set('elm',s.elm?dot('g')+'bağlı':dot('')+'bağlı değil');
    set('raw',s.raw?dot('g')+'bağlı':dot('')+'bağlı değil');
    set('drop',(s.dropped?dot('y'):dot('g'))+s.dropped+' / '+s.queue);
    set('sta',s.sta?dot('g')+s.ssid+' · '+s.ip+' · '+s.rssi+' dBm':dot('r')+(s.ssid||'-')+' bağlı değil');
    set('ap',s.apClients+' cihaz bağlı · 192.168.0.10');
    set('up',dur(s.up));set('rs',s.reset);set('heap',kb(s.heap)+' ('+kb(s.minHeap)+')');
    set('ver',s.ver);set('size',kb(s.sketch)+' / '+kb(s.free));
    if(!filled){$('ssid').value=s.ssid;filled=true}
  }catch(e){$('hv').textContent='bağlantı yok'}
}
tick();setInterval(tick,2000);
async function post(url,body,out){
  $(out).textContent='…';
  try{const r=await fetch(url,{method:'POST',body});$(out).textContent=await r.text()}catch(e){$(out).textContent='Gönderilemedi'}
}
$('wsave').onclick=()=>{
  const ap=$('appass').value;
  if(ap&&ap.length<8){$('wmsg').textContent='Kutu ağı şifresi en az 8 karakter olmalı';return}
  const f=new URLSearchParams({ssid:$('ssid').value,pass:$('pass').value,ap});
  post('/api/wifi',f,'wmsg');$('pass').value='';$('appass').value='';
};
$('wreset').onclick=()=>{if(confirm('Wi-Fi ayarları kutunun ilk yüklendiği haline dönsün mü?'))post('/api/wifi-reset',null,'wmsg')};
$('mson').onclick=()=>{if(confirm('Konfor hattında gönderme açılsın mı? Araç duruyor olmalı.'))post('/api/ms-send',new URLSearchParams({on:'1'}),'msmsg')};
$('msoff').onclick=()=>post('/api/ms-send',new URLSearchParams({on:'0'}),'msmsg');
$('rb').onclick=()=>{if(confirm('Kutu yeniden başlatılsın mı?'))post('/api/restart',null,'smsg')};
$('up').onclick=()=>{
  const f=$('fw').files[0];if(!f){$('umsg').textContent='Önce .bin dosyasını seç';return}
  const fd=new FormData();fd.append('fw',f,f.name);
  const x=new XMLHttpRequest();x.open('POST','/update');
  $('pg').hidden=false;$('pg').value=0;$('umsg').textContent='Yükleniyor…';
  x.upload.onprogress=e=>{if(e.lengthComputable)$('pg').value=e.loaded*100/e.total};
  x.onload=()=>{$('umsg').textContent=x.responseText;if(x.status==200)setTimeout(()=>location.reload(),9000)};
  x.onerror=()=>{$('umsg').textContent='Bağlantı koptu'};
  x.send(fd);
};
</script></body></html>)HTML";
