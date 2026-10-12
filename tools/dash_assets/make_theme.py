#!/usr/bin/env python3
"""Gemini ile üretilmiş yazısız tema resimlerinden (1376x768, referansla aynı çerçeve) iki katman üretir:
  theme_<ad>_lit.jpg  — halkalar ışıklı (resmin kendisi)
  theme_<ad>_base.jpg — halka bandı sönük (koyu temada karartılır, gündüz temasında zemine doğru açılır)
Bant yarıçapları referans koordinatında (merkezler 302,391 ve 1087,391); ölçüm: radyal parlaklık profili (2026-10-10).
Kotlin tarafındaki ImageTheme.band değerleri bunların 2400 ölçeğindeki karşılığıdır (× 2400/1328).
Kullanım: python make_theme.py [ad ...]   (ad verilmezse hepsini üretir)
          python make_theme.py --thumbs   (tema seçicisinin küçük önizlemeleri)   Gerekenler: opencv-python-headless numpy
"""
import cv2
import numpy as np
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable-nodpi")

# ad: (bant iç, bant dış, sönük kip, sönük oranı[, alt boşluğu kes])
# Alt boşluğu kes: Gemini halkayı tam daire çizdiyse 270° yayın dışında kalan alt dilim karartılır
THEMES = {
    "sport": (114, 192, "dark", 0.45),
    "oled": (120, 192, "dark", 0.30),
    "synthwave": (112, 192, "dark", 0.40),
    "classic": (116, 207, "dark", 0.45),
    "glacier": (110, 210, "dark", 0.40),
    "day": (172, 204, "light", 0.75),
    "cyberpunk": (108, 226, "dark", 0.35, True),
    "volcanic": (114, 198, "dark", 0.35),
    "biolum": (110, 210, "dark", 0.35, True),
    "retro": (126, 194, "dark", 0.35),
    "quantum": (118, 214, "dark", 0.35, True),
    "steam": (118, 210, "dark", 0.40, True),
    "marble": (160, 210, "light", 0.70, True),
    "matrix": (166, 198, "dark", 0.25),
    "aurora": (118, 214, "dark", 0.30),
    "crystal": (116, 214, "dark", 0.35, True),
    "cosmic": (110, 214, "dark", 0.35, True),
    "pixel": (124, 206, "dark", 0.40),
    "molten": (120, 194, "dark", 0.30, True),
    "holo": (110, 210, "dark", 0.35, True),
    "graffiti": (112, 216, "dark", 0.40, True),
    "carbon": (168, 204, "dark", 0.25, True),
}
CENTERS = ((302, 391), (1087, 391))
CROP = (24, 92, 1352, 690)  # referansla aynı: 1328x598 ≈ 2,22:1 → 2400x1080


def make(name, r0, r1, mode, amount, cut_gap=False):
    img = cv2.imread(os.path.join(HERE, "themes", name + ".png"))
    img = cv2.resize(img, (1376, 768), interpolation=cv2.INTER_AREA) if img.shape[:2] != (768, 1376) else img
    H, W = img.shape[:2]
    yy, xx = np.mgrid[0:H, 0:W]
    ann = np.zeros((H, W), np.float32)
    gap = np.zeros((H, W), np.float32)
    for cx, cy in CENTERS:
        r = np.hypot(xx - cx, yy - cy)
        # Yalnız 270°'lik bant (135° → 405°, ekran açısı); alttaki açıklık zemin, karartılmaz
        a = (np.degrees(np.arctan2(yy - cy, xx - cx)) - 135) % 360
        ann = np.maximum(ann, ((r >= r0) & (r <= r1) & (a <= 271)).astype(np.float32))
        gap = np.maximum(gap, ((r >= r0 - 6) & (r <= r1 + 8) & (a > 274) & (a < 356)).astype(np.float32))
    ann = cv2.GaussianBlur(ann, (0, 0), 1.2)[..., None]
    if cut_gap:
        gap = cv2.GaussianBlur(gap, (0, 0), 4)[..., None]
        f0 = img.astype(np.float32)
        # Koyu temada boşluk karartılır; açık temada zemine (açık gri) doğru açılır
        fill = f0 * 0.15 if mode == "dark" else f0 * 0.2 + np.array([228, 230, 232], np.float32) * 0.8
        img = (f0 * (1 - gap) + fill * gap).clip(0, 255).astype(np.uint8)
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


if __name__ == "__main__":
    import sys
    only = [a for a in sys.argv[1:] if not a.startswith("--")]
    for n, a in THEMES.items():
        if (not only and "--thumbs" not in sys.argv) or n in only:
            make(n, *a)


def thumb(name, r0, r1, prefix=None):
    """Tema seçicisi için küçük önizleme: sönük taban + %60 ışıklı dilim (2400 ölçeğinde bant)"""
    pre = prefix or f"theme_{name}"
    base = cv2.imread(os.path.join(OUT, pre + "_base.jpg"))
    lit = cv2.imread(os.path.join(OUT, pre + "_lit.jpg"))
    H, W = base.shape[:2]
    yy, xx = np.mgrid[0:H, 0:W]
    s = 2400 / 1328
    m = np.zeros((H, W), bool)
    for (cx, cy), fr in zip(CENTERS, (0.6, 0.45)):
        cx = (cx - CROP[0]) * s; cy = (cy - CROP[1]) * s
        r = np.hypot(xx - cx, yy - cy)
        a = (np.degrees(np.arctan2(yy - cy, xx - cx)) - 135) % 360
        m |= (r >= r0 * s) & (r <= r1 * s) & (a <= 270 * fr)
    out = np.where(m[..., None], lit, base)
    small = cv2.resize(out, (480, 216), interpolation=cv2.INTER_AREA)
    cv2.imwrite(os.path.join(OUT, f"theme_{name}_thumb.jpg"), small, [cv2.IMWRITE_JPEG_QUALITY, 85])


def thumbs():
    thumb("image", 117, 196, prefix="dash")   # referans tema: ImageTheme 211/355 (2400) = 117/196
    for n, a in THEMES.items():
        thumb(n, a[0], a[1])


if __name__ == "__main__" and "--thumbs" in __import__("sys").argv:
    thumbs()
