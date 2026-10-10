#!/usr/bin/env python3
"""Referans görselden (reference.jpg, 1376x768) "Görsel" temasının iki katmanını üretir:
  dash_lit.jpg  — ışıklı kadran bantları (değere kadar olan dilim bundan kesilir)
  dash_base.jpg — bantlar karartılmış taban
Değişen yazılar (üst çubuk, kutucuklar, kadran ortaları, devir çubuğu) silinip çevre dokusuyla doldurulur.
Çıktı 2400x1080 (telefon oranı). Gerekenler: pip install opencv-python-headless numpy
Koordinatlar DashImage.kt'deki sabitlerle aynı olmalı.

2026-10-10 (genel OBD aracı): kadran rakamları ve VOLVO yazısı da silinir; rakamlar uygulamada araca göre
(devir ölçeği) canlı çizilir, logo isteğe bağlı olarak dash_logo.png'den (beyaz, alfa) eklenir.
"""
import cv2
import numpy as np
import os
import sys

DIM = float(sys.argv[1]) if len(sys.argv) > 1 else 0.5   # sönük bant parlaklığı (0..1)

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable-nodpi")

img = cv2.imread(os.path.join(HERE, "reference.jpg"))
H, W = img.shape[:2]
# Gemini ile yazısız hale getirilmiş referans (2026-10-10, hizası ölçüldü: < 0,1 px). Varsa silme maskesine gerek yok;
# OpenCV inpaint kadran bandında iz bırakıyordu.
CLEAN = os.path.join(HERE, "reference_clean.png")
mask = np.zeros((H, W), np.uint8)
for x0, y0, x1, y1 in [
    (425, 110, 940, 158),    # üst çubuk
    (520, 212, 885, 305),    # üst kutucuklar
    (520, 398, 885, 518),    # alt kutucuklar (ikonlar dahil)
    (232, 342, 372, 478),    # hız ortası
    (1018, 342, 1158, 478),  # devir ortası
    (1003, 514, 1172, 540),  # devir alt çubuğu
    (600, 160, 780, 198),    # VOLVO yazısı (parlaması dahil)
]:
    cv2.rectangle(mask, (x0, y0), (x1, y1), 255, -1)

# Kadran rakamları: yarıçap ~155, başlangıç 135°, 270° tarama (hız 0..200/20, devir 0..5)
import math
LABEL_R = 155
for (cx, cy), n in (((302, 395), 11), ((1088, 395), 6)):
    for i in range(n):
        a = math.radians(135 + 270 * i / (n - 1))
        # Alt uçlardaki rakamlar biraz içeride (~147): iki yarıçapı da kapsayan kutu
        for r in (LABEL_R, 146):
            x, y = cx + r * math.cos(a), cy + r * math.sin(a)
            cv2.rectangle(mask, (int(x - 23), int(y - 13)), (int(x + 23), int(y + 13)), 255, -1)

# Logo: silmeden önce alfa maske olarak sakla (beyaz yazı, koyu zemin)
lx0, ly0, lx1, ly1 = 615, 165, 765, 192
crop = cv2.cvtColor(img[ly0:ly1, lx0:lx1], cv2.COLOR_BGR2GRAY).astype(np.float32)
bg = np.percentile(crop, 30)
alpha = np.clip((crop - bg) / max(1.0, crop.max() - bg) * 1.15, 0, 1)
SC = 2400 / 1328
logo = np.zeros((ly1 - ly0, lx1 - lx0, 4), np.uint8)
logo[..., :3] = 255
logo[..., 3] = (alpha * 255).astype(np.uint8)
logo = cv2.resize(logo, (int((lx1 - lx0) * SC), int((ly1 - ly0) * SC)), interpolation=cv2.INTER_LANCZOS4)
cv2.imwrite(os.path.join(OUT, "dash_logo.png"), logo)
print("logo konumu (2400x1080):", round((lx0 - 24) * SC), round((ly0 - 92) * SC), logo.shape[1], logo.shape[0])
if os.path.exists(CLEAN):
    clean = cv2.imread(CLEAN)
else:
    clean = cv2.inpaint(img, mask, 9, cv2.INPAINT_TELEA)
    blur = cv2.GaussianBlur(clean, (0, 0), 6)
    m3 = cv2.GaussianBlur(mask, (0, 0), 3).astype(np.float32)[..., None] / 255
    clean = (clean * (1 - m3) + blur * m3).astype(np.uint8)

# Sönük taban: kadran bant halkası (r 117..196) karartılır, doygunluk düşürülür
base = clean.astype(np.float32)
yy, xx = np.mgrid[0:H, 0:W]
ann = np.zeros((H, W), np.float32)
for cx, cy in ((302, 395), (1088, 395)):
    r = np.hypot(xx - cx, yy - cy)
    ann = np.maximum(ann, ((r >= 117) & (r <= 196)).astype(np.float32))
ann = cv2.GaussianBlur(ann, (0, 0), 1.2)[..., None]
# Değerin ötesi kapkara değil: aynı renkler, yarı parlaklık (referanstaki her zaman ışıklı bant görünümüne yakın)
gray = base.mean(axis=2, keepdims=True)
dim = (base * 0.85 + gray * 0.15) * DIM
base = base * (1 - ann) + dim * ann

x0, y0, x1, y1 = 24, 92, 1352, 690   # 1328x598 ≈ 2.22:1
for name, im in (("dash_lit.jpg", clean), ("dash_base.jpg", base.clip(0, 255).astype(np.uint8))):
    up = cv2.resize(im[y0:y1, x0:x1], (2400, 1080), interpolation=cv2.INTER_LANCZOS4)
    cv2.imwrite(os.path.join(OUT, name), up, [cv2.IMWRITE_JPEG_QUALITY, 93])
print("tamam")
