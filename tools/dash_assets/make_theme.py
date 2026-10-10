#!/usr/bin/env python3
"""Gemini ile üretilmiş yazısız tema resimlerinden (1376x768, referansla aynı çerçeve) iki katman üretir:
  theme_<ad>_lit.jpg  — halkalar ışıklı (resmin kendisi)
  theme_<ad>_base.jpg — halka bandı sönük (koyu temada karartılır, gündüz temasında zemine doğru açılır)
Bant yarıçapları referans koordinatında (merkezler 302,391 ve 1087,391); ölçüm: radyal parlaklık profili (2026-10-10).
Kotlin tarafındaki ImageTheme.band değerleri bunların 2400 ölçeğindeki karşılığıdır (× 2400/1328).
Kullanım: python make_theme.py   (hepsini üretir)   Gerekenler: opencv-python-headless numpy
"""
import cv2
import numpy as np
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable-nodpi")

# ad: (bant iç, bant dış, sönük kip, sönük oranı)
THEMES = {
    "sport": (114, 192, "dark", 0.45),
    "oled": (120, 192, "dark", 0.30),
    "synthwave": (112, 192, "dark", 0.40),
    "classic": (116, 207, "dark", 0.45),
    "glacier": (110, 210, "dark", 0.40),
    "day": (172, 204, "light", 0.75),
}
CENTERS = ((302, 391), (1087, 391))
CROP = (24, 92, 1352, 690)  # referansla aynı: 1328x598 ≈ 2,22:1 → 2400x1080


def make(name, r0, r1, mode, amount):
    img = cv2.imread(os.path.join(HERE, "themes", name + ".png"))
    H, W = img.shape[:2]
    yy, xx = np.mgrid[0:H, 0:W]
    ann = np.zeros((H, W), np.float32)
    for cx, cy in CENTERS:
        r = np.hypot(xx - cx, yy - cy)
        # Yalnız 270°'lik bant (135° → 405°, ekran açısı); alttaki açıklık zemin, karartılmaz
        a = (np.degrees(np.arctan2(yy - cy, xx - cx)) - 135) % 360
        ann = np.maximum(ann, ((r >= r0) & (r <= r1) & (a <= 271)).astype(np.float32))
    ann = cv2.GaussianBlur(ann, (0, 0), 1.2)[..., None]
    f = img.astype(np.float32)
    gray = f.mean(axis=2, keepdims=True)
    if mode == "dark":
        dim = (f * 0.85 + gray * 0.15) * amount
    else:  # gündüz: koyu bant zemin rengine doğru açılır (değerin ötesi soluk)
        panel = np.array([236, 236, 236], np.float32)
        dim = f * (1 - amount) + panel * amount
    base = f * (1 - ann) + dim * ann
    x0, y0, x1, y1 = CROP
    for suffix, im in (("lit", img), ("base", base.clip(0, 255).astype(np.uint8))):
        up = cv2.resize(im[y0:y1, x0:x1], (2400, 1080), interpolation=cv2.INTER_LANCZOS4)
        cv2.imwrite(os.path.join(OUT, f"theme_{name}_{suffix}.jpg"), up, [cv2.IMWRITE_JPEG_QUALITY, 90])
    print(name, "bant (2400):", round(r0 * 2400 / 1328), round(r1 * 2400 / 1328))


for n, a in THEMES.items():
    make(n, *a)
