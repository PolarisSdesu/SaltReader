#!/usr/bin/env python3
"""Generate the two original sample comics bundled with Salt Reader.

The pages are drawn from scratch (no third-party art), using the same paper /
ink / vermilion palette as the app. They exist only to make the first launch
feel alive and to exercise the reader with real CBZ files.
"""
from __future__ import annotations

import io
import json
import math
import random
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1080, 1640
PAPER = (247, 241, 230)
PAPER_DEEP = (238, 228, 212)
INK = (29, 25, 21)
INK_SOFT = (62, 54, 46)
VERMILION = (162, 59, 49)
MIST = (215, 203, 185)

SONG = "/System/Library/Fonts/Supplemental/Songti.ttc"
SANS = "/System/Library/Fonts/Hiragino Sans GB.ttc"

fonts: dict[tuple[str, int, int], ImageFont.FreeTypeFont] = {}
def font(kind: str, size: int, idx: int | None = None) -> ImageFont.FreeTypeFont:
    key = (kind, size, idx or 0)
    if key not in fonts:
        path = SONG if kind == "serif" else SANS
        fonts[key] = ImageFont.truetype(path, size, index=idx or 0)
    return fonts[key]


def paper() -> Image.Image:
    random.seed(7)
    img = Image.new("RGB", (W, H), PAPER)
    px = img.load()
    for _ in range(14000):
        x, y = random.randrange(W), random.randrange(H)
        r, g, b = px[x, y]
        d = random.randint(-5, 5)
        px[x, y] = (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)))
    draw = ImageDraw.Draw(img)
    for _ in range(55):
        x, y = random.randrange(W), random.randrange(H)
        draw.line((x, y, x + random.randint(20, 180), y + random.randint(-2, 2)), fill=(216, 204, 186), width=1)
    return img


def panel(draw: ImageDraw.ImageDraw, box, width: int = 5) -> None:
    draw.rectangle(box, outline=INK, width=width)


def caption(draw: ImageDraw.ImageDraw, xy, text: str, size: int = 31, fg=INK, bg=None, max_width=620, pad=14):
    x, y = xy
    lines = wrap(text, font("sans", size), max_width)
    lh = size + 12
    if bg:
        w = max(text_width(line, font("sans", size)) for line in lines) + pad * 2
        draw.rectangle((x - pad, y - pad, x + w, y + lh * len(lines) + pad // 2), fill=bg)
    for line in lines:
        draw.text((x, y), line, font=font("sans", size), fill=fg)
        y += lh
    return y


def wrap(text: str, f: ImageFont.FreeTypeFont, max_width: int) -> list[str]:
    lines, current = [], ""
    for ch in text:
        candidate = current + ch
        if text_width(candidate, f) <= max_width or not current:
            current = candidate
        else:
            lines.append(current)
            current = ch
    if current:
        lines.append(current)
    return lines


def text_width(text: str, f: ImageFont.FreeTypeFont) -> int:
    box = f.getbbox(text)
    return box[2] - box[0]


def bubble(draw: ImageDraw.ImageDraw, box, text: str, tail: str = "bl", size: int = 30) -> None:
    x1, y1, x2, y2 = box
    draw.rounded_rectangle(box, radius=38, fill=(255, 252, 245), outline=INK, width=4)
    if tail == "bl":
        draw.polygon([(x1 + 42, y2 - 2), (x1 + 26, y2 + 40), (x1 + 82, y2 - 2)], fill=(255, 252, 245), outline=INK)
        draw.line([(x1 + 42, y2 - 2), (x1 + 26, y2 + 40), (x1 + 82, y2 - 2)], fill=INK, width=4)
    max_w = (x2 - x1) - 48
    f = font("sans", size)
    lines = wrap(text, f, max_w)
    total_h = len(lines) * (size + 10)
    ty = y1 + (y2 - y1 - total_h) / 2
    for line in lines:
        tw = text_width(line, f)
        draw.text((x1 + (x2 - x1 - tw) / 2, ty), line, font=f, fill=INK)
        ty += size + 10


def sea(draw: ImageDraw.ImageDraw, box, horizon=0.45, dense=1.0, dark=False):
    x1, y1, x2, y2 = box
    h = y1 + int((y2 - y1) * horizon)
    draw.rectangle(box, fill=(240, 233, 221) if not dark else (239, 232, 220))
    draw.rectangle((x1, h, x2, y2), fill=(249, 244, 235) if not dark else (236, 228, 215))
    draw.line((x1, h, x2, h), fill=INK, width=4)
    count = int(14 * dense)
    for i in range(count):
        t = i / max(1, count - 1)
        y = h + int((y2 - h) * (t ** 1.5) * 0.9) + 8
        amp = 5 + 8 * t
        step = 24
        pts = []
        for x in range(x1 - 10, x2 + 10, step):
            pts.append((x, y + math.sin((x - x1) / 70 + i) * amp))
        draw.line(pts, fill=INK_SOFT if i % 3 else INK, width=2 if i % 3 else 3)
    for i in range(26):
        x = x1 + random.randint(0, max(1, x2 - x1))
        y = random.randint(h + 8, y2 - 8)
        draw.line((x, y, x + random.randint(6, 24), y), fill=(120, 106, 92), width=1)


def moon(draw: ImageDraw.ImageDraw, center, radius: int, accent=False):
    cx, cy = center
    color = VERMILION if accent else (226, 213, 191)
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=color, outline=INK, width=3)
    if not accent:
        random.seed(11)
        for _ in range(110):
            a = random.random() * math.tau
            r = random.random() * radius * 0.86
            x, y = cx + math.cos(a) * r, cy + math.sin(a) * r
            draw.ellipse((x - 2, y - 2, x + 2, y + 2), fill=(160, 148, 130))
    else:
        draw.ellipse((cx - radius * 0.6, cy - radius * 0.55, cx + radius * 0.35, cy + radius * 0.3), fill=(190, 85, 68))


def hatching(draw, box, gap=10, width=1, color=(86, 76, 65)):
    x1, y1, x2, y2 = box
    for x in range(x1 - (y2 - y1), x2, gap):
        draw.line((x, y2, x + (y2 - y1), y1), fill=color, width=width)


def halftone(draw, box, steps=26, r_max=6):
    x1, y1, x2, y2 = box
    for i in range(steps):
        t = i / max(1, steps - 1)
        r = 1 + (r_max - 1) * (1 - t)
        yy = y1 + (y2 - y1) * t
        for x in range(x1, x2, 18):
            draw.ellipse((x - r, yy - r, x + r, yy + r), fill=INK)


def title_vertical(draw, x, y, text, size=96, color=INK, gap=6):
    f = font("serif", size, 1)
    for ch in text:
        draw.text((x, y), ch, font=f, fill=color)
        y += size + gap


def page_number(draw, n: int, total: int, label="SALT READER · SAMPLE"):
    draw.text((84, H - 72), label, font=font("sans", 20), fill=(116, 103, 89))
    text = f"{n:02d} / {total:02d}"
    tw = text_width(text, font("sans", 22))
    draw.text((W - 84 - tw, H - 72), text, font=font("sans", 22), fill=(116, 103, 89))


def sign(draw, x, y, text, size=22):
    f = font("sans", size)
    tw = text_width(text, f)
    draw.rectangle((x, y, x + tw + 24, y + size + 20), fill=INK)
    draw.text((x + 12, y + 8), text, font=f, fill=PAPER)


def make_tide_pages():
    pages = []
    total = 7
    # Cover
    img = paper()
    d = ImageDraw.Draw(img)
    d.rectangle((34, 34, W - 34, H - 34), outline=INK, width=2)
    d.ellipse((540, 130, 930, 520), fill=VERMILION)
    d.ellipse((585, 175, 885, 475), fill=(230, 178, 158))
    sea(d, (60, 930, W - 60, 1390), horizon=0.1, dense=1.4)
    # small print shop
    d.rectangle((150, 760, 360, 930), fill=PAPER_DEEP, outline=INK, width=5)
    d.polygon([(130, 765), (255, 690), (380, 765)], fill=INK)
    d.rectangle((190, 830, 245, 890), fill=INK)
    d.rectangle((280, 830, 330, 890), fill=VERMILION, outline=INK, width=3)
    d.line((255, 690, 255, 600), fill=INK, width=4)
    d.polygon([(255, 600), (340, 620), (255, 640)], fill=VERMILION)
    title_vertical(d, 690, 210, "潮汐印刷所", size=118, color=PAPER)
    title_vertical(d, 555, 930, "第一话", size=38, color=INK)
    f = font("serif", 46, 1)
    d.text((120, 1080), "退潮后的第三个小时，", font=f, fill=INK)
    d.text((120, 1140), "印刷所才会开门。", font=f, fill=INK)
    d.text((120, 1260), "盐野工作室", font=font("sans", 28), fill=INK_SOFT)
    d.text((120, 1312), "SALT READER ORIGINAL", font=font("sans", 19), fill=(130, 116, 101))
    page_number(d, 1, total)
    pages.append(img)

    # p2 establishing
    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (72, 110, W - 72, 840))
    sea(d, (78, 116, W - 78, 834), horizon=0.55, dense=1.8)
    d.ellipse((760, 170, 910, 320), fill=(233, 219, 197), outline=INK, width=3)
    d.rectangle((150, 560, 340, 760), fill=PAPER_DEEP, outline=INK, width=6)
    d.polygon([(130, 565), (245, 485), (360, 565)], fill=INK)
    d.rectangle((195, 650, 245, 710), fill=VERMILION)
    d.line((245, 485, 245, 390), fill=INK, width=4)
    d.text((118, 900), "一", font=font("serif", 40, 1), fill=VERMILION)
    caption(d, (155, 898), "潮水退到最远的地方，露出一排旧木桩。", size=33, max_width=760)
    panel(d, (72, 1040, W - 72, 1500))
    for i in range(9):
        x = 140 + i * 90
        d.rectangle((x, 1120 - i * 12, x + 22, 1480), fill=PAPER_DEEP, outline=INK, width=3)
    d.line((72, 1230, W - 72, 1200), fill=INK_SOFT, width=6)
    d.text((690, 1110), "「今天也涨潮了。」", font=font("serif", 36, 1), fill=INK)
    page_number(d, 2, total)
    pages.append(img)

    # p3 interior
    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (60, 100, 535, 660)); panel(d, (555, 100, W - 60, 660))
    d.rectangle((70, 110, 525, 650), fill=(243, 235, 222))
    d.rectangle((110, 320, 430, 560), fill=PAPER_DEEP, outline=INK, width=5)
    for i in range(3):
        d.rectangle((145 + i * 90, 350, 205 + i * 90, 420), fill=INK)
    d.ellipse((330, 180, 470, 320), fill=(225, 212, 193), outline=INK, width=4)
    d.line((400, 185, 400, 145), fill=INK, width=4)
    d.line((400, 145, 500, 145), fill=INK, width=4)
    d.ellipse((490, 135, 510, 155), fill=VERMILION)
    d.text((90, 575), "印刷间", font=font("sans", 24), fill=INK_SOFT)
    d.rectangle((565, 110, W - 70, 650), fill=(240, 232, 219))
    d.ellipse((650, 190, 800, 340), fill=(229, 215, 195), outline=INK, width=4)
    d.rectangle((690, 400, 960, 600), fill=PAPER_DEEP, outline=INK, width=5)
    d.line((720, 440, 930, 440), fill=INK, width=3)
    d.line((720, 470, 900, 470), fill=INK, width=3)
    caption(d, (95, 720), "阿盐把一摞信纸摊在印台上，纸边还带着咸味。", size=32, max_width=880)
    bubble(d, (140, 850, 625, 1010), "又是没寄出的信？")
    bubble(d, (470, 1080, 945, 1260), "嗯。地址被海水泡掉了。", tail="br")
    panel(d, (60, 1350, W - 60, 1520))
    halftone(d, (70, 1360, W - 70, 1512), steps=18, r_max=4)
    d.text((100, 1390), "潮汐印刷所", font=font("serif", 54, 1), fill=PAPER)
    page_number(d, 3, total)
    pages.append(img)

    # p4 letter
    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (140, 110, 940, 950))
    d.rectangle((160, 130, 920, 930), fill=(251, 247, 239))
    d.polygon([(160, 130), (540, 500), (920, 130)], outline=INK, width=4, fill=(243, 234, 219))
    d.line((160, 130, 540, 500), fill=INK, width=4)
    d.line((920, 130, 540, 500), fill=INK, width=4)
    d.ellipse((430, 400, 650, 620), fill=VERMILION)
    d.text((455, 430), "盐", font=font("serif", 116, 1), fill=PAPER)
    for i in range(5):
        d.line((250, 690 + i * 36, 820, 690 + i * 36), fill=(174, 160, 143), width=2)
    d.line((300, 700, 760, 700), fill=VERMILION, width=8)
    caption(d, (120, 1020), "信里只写了一句话：海会替你把话带到。", size=34, max_width=880)
    bubble(d, (560, 1160, 980, 1310), "那海知道地址吗？", tail="br")
    panel(d, (100, 1360, 980, 1520))
    hatching(d, (106, 1366, 974, 1514), gap=16, width=1)
    d.text((160, 1390), "海水退去时，纸上的字迹开始发亮。", font=font("serif", 38, 1), fill=PAPER)
    page_number(d, 4, total)
    pages.append(img)

    # p5 lighthouse
    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (60, 90, W - 60, 860))
    d.rectangle((66, 96, W - 66, 854), fill=(240, 232, 218))
    d.ellipse((760, 160, 910, 310), fill=(236, 224, 205), outline=INK, width=3)
    sea(d, (66, 500, W - 66, 854), horizon=0.0, dense=2.0)
    d.polygon([(130, 820), (235, 300), (330, 820)], fill=PAPER_DEEP, outline=INK, width=4)
    d.rectangle((205, 260, 265, 320), fill=VERMILION, outline=INK, width=3)
    d.polygon([(195, 260), (235, 200), (275, 260)], fill=INK)
    d.line((235, 300, 235, 210), fill=INK, width=2)
    for i in range(3):
        d.arc((240, 180, 900, 700), start=200, end=340, fill=(222, 196, 168), width=5)
    caption(d, (680, 640), "灯塔的光每十二秒转一次。", size=28, max_width=300, bg=(247, 241, 230))
    bubble(d, (240, 910, 780, 1070), "她说，潮水会替人把信送到该去的地方。")
    panel(d, (60, 1140, 500, 1510)); panel(d, (520, 1140, W - 60, 1510))
    d.rectangle((70, 1150, 490, 1500), fill=(245, 239, 229))
    d.ellipse((190, 1250, 380, 1440), fill=(227, 213, 193), outline=INK, width=4)
    d.rectangle((260, 1210, 310, 1280), fill=INK)
    d.rectangle((530, 1150, W - 70, 1500), fill=(245, 239, 229))
    d.rectangle((600, 1280, 930, 1450), fill=PAPER_DEEP, outline=INK, width=4)
    d.line((650, 1320, 880, 1320), fill=INK, width=2)
    d.line((650, 1360, 880, 1360), fill=INK, width=2)
    d.rectangle((850, 1380, 900, 1430), fill=VERMILION)
    page_number(d, 5, total)
    pages.append(img)

    # p6 press
    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (70, 100, 1010, 780))
    d.rectangle((80, 110, 1000, 770), fill=(243, 235, 222))
    d.rectangle((150, 300, 760, 690), fill=PAPER_DEEP, outline=INK, width=6)
    d.ellipse((190, 350, 350, 510), fill=(224, 211, 192), outline=INK, width=5)
    d.ellipse((290, 350, 450, 510), fill=(224, 211, 192), outline=INK, width=5)
    d.ellipse((220, 390, 280, 450), fill=INK)
    d.rectangle((500, 340, 710, 660), fill=(235, 225, 210), outline=INK, width=4)
    d.line((520, 390, 690, 390), fill=INK, width=3)
    d.line((520, 440, 690, 440), fill=INK, width=3)
    d.rectangle((300, 610, 690, 670), fill=INK)
    d.text((780, 220), "今", font=font("serif", 100, 1), fill=VERMILION)
    d.text((780, 330), "日", font=font("serif", 100, 1), fill=INK)
    d.text((780, 440), "开", font=font("serif", 100, 1), fill=INK)
    d.text((780, 550), "印", font=font("serif", 100, 1), fill=INK)
    caption(d, (110, 840), "印刷机咬住纸，吐出一行行潮湿的字。", size=33, max_width=840)
    bubble(d, (480, 980, 980, 1150), "那就印两份吧。一份寄给海。", tail="br")
    panel(d, (80, 1200, 1000, 1510))
    d.rectangle((90, 1210, 990, 1500), fill=(244, 237, 226))
    for i in range(12):
        x = 130 + i * 70
        d.rectangle((x, 1260, x + 40, 1460), fill=(250, 246, 238), outline=INK, width=2)
        d.line((x + 8, 1300, x + 32, 1300), fill=VERMILION, width=3)
    d.text((120, 1518), "", font=font("sans", 1), fill=INK)
    page_number(d, 6, total)
    pages.append(img)

    # p7 end
    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (70, 80, W - 70, 780))
    sea(d, (76, 86, W - 76, 774), horizon=0.35, dense=1.2)
    d.ellipse((680, 130, 930, 380), fill=(231, 217, 196), outline=INK, width=4)
    d.polygon([(120, 774), (250, 600), (380, 774)], fill=PAPER_DEEP, outline=INK, width=4)
    d.rectangle((180, 700, 320, 774), fill=INK)
    d.rectangle((230, 720, 290, 770), fill=PAPER)
    for i in range(7):
        d.line((120 + i * 130, 774, 160 + i * 130, 690), fill=INK, width=5)
    caption(d, (100, 860), "有些信，寄到就好，", size=42, max_width=780)
    caption(d, (100, 925), "不必等到回音。", size=42, max_width=780)
    d.line((100, 1015, 560, 1015), fill=VERMILION, width=5)
    d.text((100, 1050), "—— 第一话 · 完", font=font("serif", 32, 1), fill=INK_SOFT)
    d.ellipse((780, 1100, 960, 1280), fill=(237, 224, 204), outline=INK, width=3)
    d.text((805, 1145), "盐", font=font("serif", 80, 1), fill=VERMILION)
    d.text((90, 1360), "下一话 · 退潮之后", font=font("serif", 28, 1), fill=(120, 105, 90))
    page_number(d, 7, total)
    pages.append(img)
    return pages


def make_moon_pages():
    pages = []
    total = 7
    img = paper(); d = ImageDraw.Draw(img)
    d.rectangle((34, 34, W - 34, H - 34), outline=INK, width=2)
    moon(d, (740, 330), 230)
    sea(d, (50, 900, W - 50, 1420), horizon=0.05, dense=1.2)
    for i in range(40):
        x = random.randint(70, W - 70); y = random.randint(930, 1400)
        d.ellipse((x, y, x + 4, y + 4), fill=PAPER)
    title_vertical(d, 250, 560, "盐与月光", size=124, color=INK)
    title_vertical(d, 130, 620, "第一话", size=36, color=VERMILION)
    d.text((120, 120), "SALT READER ORIGINAL", font=font("sans", 19), fill=(130, 116, 101))
    d.text((120, 1270), "白砾", font=font("sans", 30), fill=INK)
    d.text((120, 1320), "失眠的夜里，月亮把海面晒成盐。", font=font("serif", 30, 1), fill=INK_SOFT)
    page_number(d, 1, total)
    pages.append(img)

    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (60, 100, W - 60, 900))
    d.rectangle((66, 106, W - 66, 894), fill=(34, 31, 28))
    moon(d, (760, 300), 180, accent=False)
    d.ellipse((120, 490, 220, 590), fill=(24, 21, 18))
    for i in range(26):
        y = 600 + i * 11
        d.line((70, y, W - 70, y + random.randint(-6, 6)), fill=(132, 118, 102), width=2 if i % 4 else 4)
    for i in range(70):
        x = random.randint(80, W - 80); y = random.randint(610, 880)
        d.ellipse((x, y, x + 3, y + 3), fill=(211, 192, 166))
    caption(d, (100, 960), "凌晨两点，海面像一张被揉皱的锡纸。", size=34, max_width=880, fg=INK)
    panel(d, (60, 1100, 520, 1500)); panel(d, (540, 1100, W - 60, 1500))
    d.rectangle((70, 1110, 510, 1490), fill=(245, 238, 227))
    d.rectangle((110, 1190, 470, 1460), fill=(229, 216, 197), outline=INK, width=4)
    d.line((290, 1190, 290, 1460), fill=INK, width=4)
    d.line((110, 1320, 470, 1320), fill=INK, width=4)
    d.ellipse((355, 1380, 465, 1490), fill=(247, 241, 230), outline=INK, width=4)
    d.rectangle((550, 1110, W - 70, 1490), fill=(245, 238, 227))
    d.ellipse((680, 1190, 930, 1440), fill=(232, 218, 198), outline=INK, width=4)
    d.text((790, 1250), "盐", font=font("serif", 72, 1), fill=VERMILION)
    page_number(d, 2, total)
    pages.append(img)

    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (70, 100, W - 70, 700))
    d.rectangle((80, 110, W - 80, 690), fill=(34, 31, 28))
    d.rectangle((680, 170, 920, 560), fill=(210, 197, 178), outline=INK, width=4)
    d.line((800, 190, 800, 540), fill=INK, width=4)
    d.line((700, 360, 900, 360), fill=INK, width=4)
    moon(d, (800, 270), 70)
    d.rectangle((140, 260, 560, 660), fill=(24, 21, 18), outline=INK, width=3)
    d.ellipse((300, 360, 430, 500), fill=(222, 206, 186))
    d.rectangle((320, 500, 410, 660), fill=(222, 206, 186))
    caption(d, (100, 760), "她数到第一千三百只羊，窗外还是没有天亮。", size=33, max_width=880)
    bubble(d, (180, 900, 650, 1050), "睡不着。")
    panel(d, (70, 1140, W - 70, 1510))
    d.rectangle((80, 1150, W - 80, 1500), fill=(243, 235, 223))
    d.rectangle((170, 1270, 890, 1460), fill=(236, 225, 210), outline=INK, width=5)
    d.ellipse((430, 1190, 620, 1390), fill=(249, 245, 237), outline=INK, width=4)
    d.rectangle((470, 1240, 580, 1350), fill=(222, 208, 188), outline=INK, width=3)
    page_number(d, 3, total)
    pages.append(img)

    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (80, 100, 1000, 780))
    d.rectangle((90, 110, 990, 770), fill=(243, 235, 223))
    d.rectangle((160, 500, 900, 730), fill=(232, 219, 201), outline=INK, width=5)
    d.rectangle((400, 300, 620, 530), fill=(249, 245, 237), outline=INK, width=4)
    d.ellipse((440, 330, 580, 470), fill=(224, 210, 191))
    d.rectangle((470, 390, 550, 530), fill=(224, 210, 191))
    d.text((590, 590), "盐", font=font("serif", 68, 1), fill=VERMILION)
    d.text((260, 590), "盐", font=font("serif", 68, 1), fill=VERMILION)
    d.text((420, 610), "盐", font=font("sans", 28), fill=INK_SOFT)
    caption(d, (110, 850), "厨房的盐罐见底了。她把最后一点倒在掌心，像捧着一小片海。", size=32, max_width=860)
    bubble(d, (500, 1020, 980, 1170), "海也会失眠吗？", tail="br")
    panel(d, (80, 1250, 1000, 1510))
    hatching(d, (86, 1256, 994, 1504), gap=14, width=1)
    d.text((120, 1300), "盐粒在月光里，亮得像没说完的话。", font=font("serif", 36, 1), fill=PAPER)
    page_number(d, 4, total)
    pages.append(img)

    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (60, 90, W - 60, 790))
    sea(d, (66, 96, W - 66, 784), horizon=0.18, dense=2.2)
    moon(d, (780, 220), 150, accent=True)
    for i in range(16):
        y = 380 + i * 24
        d.arc((100 + i * 8, y, 980 - i * 8, y + 90), start=190, end=350, fill=INK, width=3)
    d.polygon([(380, 784), (470, 620), (560, 784)], fill=(28, 24, 21))
    d.line((470, 620, 470, 500), fill=INK, width=3)
    d.polygon([(470, 500), (590, 530), (470, 555)], fill=(235, 222, 202))
    caption(d, (100, 850), "她沿着海堤走。潮水退去后，沙上留下一层薄薄的盐。", size=32, max_width=880)
    panel(d, (60, 1030, 520, 1500)); panel(d, (540, 1030, W - 60, 1500))
    d.rectangle((70, 1040, 510, 1490), fill=(243, 235, 223))
    d.ellipse((150, 1170, 420, 1440), fill=(231, 217, 197), outline=INK, width=4)
    for i in range(30):
        x = random.randint(120, 460); y = random.randint(1180, 1470)
        d.ellipse((x, y, x + 4, y + 4), fill=PAPER)
    d.rectangle((550, 1040, W - 70, 1490), fill=(243, 235, 223))
    d.rectangle((600, 1120, 950, 1430), fill=(251, 247, 239), outline=INK, width=4)
    d.line((640, 1180, 910, 1180), fill=INK, width=3)
    d.line((640, 1220, 880, 1220), fill=INK, width=3)
    d.line((640, 1260, 900, 1260), fill=INK, width=3)
    d.rectangle((840, 1340, 910, 1410), fill=VERMILION)
    page_number(d, 5, total)
    pages.append(img)

    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (70, 100, W - 70, 840))
    d.rectangle((80, 110, W - 80, 830), fill=(34, 31, 28))
    d.ellipse((720, 180, 960, 420), fill=(231, 219, 202), outline=INK, width=3)
    d.ellipse((100, 520, 240, 660), fill=(24, 21, 18))
    d.rectangle((340, 600, 420, 830), fill=(24, 21, 18))
    d.line((380, 600, 380, 450), fill=(221, 207, 188), width=2)
    for i in range(9):
        d.arc((430 + i * 22, 560 + i * 18, 1070 - i * 20, 900 - i * 10), start=200, end=340, fill=(210, 195, 174), width=2)
    caption(d, (100, 910), "凌晨四点，她终于听见海在呼吸。", size=36, max_width=860)
    bubble(d, (170, 1050, 850, 1210), "原来海也会失眠。")
    panel(d, (70, 1280, W - 70, 1510))
    for i in range(8):
        x = 120 + i * 110
        d.ellipse((x, 1330, x + 70, 1400), fill=(247, 241, 230), outline=INK, width=3)
    d.text((150, 1430), "她把盐撒进海里，像在还一个愿。", font=font("serif", 34, 1), fill=INK_SOFT)
    page_number(d, 6, total)
    pages.append(img)

    img = paper(); d = ImageDraw.Draw(img)
    panel(d, (70, 80, W - 70, 760))
    sea(d, (76, 86, W - 76, 754), horizon=0.28, dense=1.1)
    d.ellipse((700, 140, 930, 370), fill=(233, 220, 199), outline=INK, width=3)
    for i in range(12):
        x = 150 + i * 60
        d.ellipse((x, 700, x + 12, 712), fill=PAPER)
    caption(d, (100, 840), "天亮之前，盐回到了海里。", size=44, max_width=760)
    caption(d, (100, 910), "她也睡着了。", size=44, max_width=760)
    d.line((100, 1000, 720, 1000), fill=VERMILION, width=5)
    d.text((100, 1040), "—— 第一话 · 完", font=font("serif", 32, 1), fill=INK_SOFT)
    moon(d, (760, 1210), 130, accent=True)
    d.text((100, 1370), "下一话 · 盐花", font=font("serif", 28, 1), fill=(120, 105, 90))
    page_number(d, 7, total)
    pages.append(img)
    return pages


def save_cbz(path: Path, name: str, title: str, author: str, pages: list[Image.Image], accent: int):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for i, img in enumerate(pages, start=1):
            buf = io.BytesIO()
            img.save(buf, "JPEG", quality=88, optimize=True, progressive=True)
            z.writestr(f"{i:02d}.jpg", buf.getvalue())
    print(f"{path.name}: {len(pages)} pages, {path.stat().st_size/1024/1024:.2f} MiB")


def main():
    out = Path(__file__).resolve().parent.parent / "app/src/main/assets/samples"
    out.mkdir(parents=True, exist_ok=True)
    save_cbz(out / "tide_press.cbz", "tide_press", "潮汐印刷所", "盐野工作室", make_tide_pages(), 0xFF7A3E2F)
    save_cbz(out / "salt_and_moon.cbz", "salt_and_moon", "盐与月光", "白砾", make_moon_pages(), 0xFF3E5268)
    extra = []
    mirrored = out / "majo_029.cbz"
    if mirrored.exists():
        extra.append(
            {
                "asset": mirrored.name,
                "title": "你好，身為魔女的我，被心上人委託製作迷情藥",
                "author": "六つ花えいこ / vient / 釜田",
                "description": "話 029 · 由本地 EPUB 转换的示例章节，阅读方向从右到左。",
                "accent": 0xFF5F4358,
                "direction": "RTL",
            }
        )
    manifest = extra + [
        {
            "asset": "tide_press.cbz",
            "title": "潮汐印刷所",
            "author": "盐野工作室",
            "description": "退潮后的第三个小时，印刷所才会开门。替人印下没寄出的信。",
            "accent": 0xFF7A3E2F,
        },
        {
            "asset": "salt_and_moon.cbz",
            "title": "盐与月光",
            "author": "白砾",
            "description": "失眠的夜里，月亮把海面晒成盐。",
            "accent": 0xFF3E5268,
        },
    ]
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print("manifest written")


if __name__ == "__main__":
    main()
