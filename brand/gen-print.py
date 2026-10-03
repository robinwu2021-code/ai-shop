#!/usr/bin/env python3
"""印刷物料 —— 名片（可改名）。输出到 brand/dist/print/cards/。

**为什么是「模板 + 数据表」而不是发一个可编辑的 AI 文件**

发可编辑文件，改名的人会顺手把字号、间距、logo 位置也一起动了 —— 十个人十张名片，
版式各不相同，而这种漂移没人会去比对。这里把「会变的」和「不许变的」分开：

  会变的  →  brand/print/people.csv        姓名 / 职务 / 手机 / 邮箱 / 主体
  不许变的 →  本文件里的版式常量             尺寸、出血、安全区、字号、标识位置

改一行 CSV 重跑，所有人的名片一次重出，版式必然一致。

用法：
    python3 brand/gen-print.py                 # 读默认 CSV
    python3 brand/gen-print.py path/to.csv     # 读别的表

⚠️ **交印刷厂前必须把文字转轮廓**（Illustrator「创建轮廓」）——
这里的汉字是 <text>，依赖装机字体；换台机器字体不同，字形就变了，屏幕上还看不出来。
"""
import csv
import pathlib
import sys

import build as B

ROOT = pathlib.Path(__file__).resolve().parent
OUT = ROOT / "dist" / "print" / "cards"
CSV_DEFAULT = ROOT / "print" / "people.csv"

# ── 版式常量（单位 mm）。**这些不随人变** ────────────────────────────────
# 国内标准名片 90×54mm。出血 3mm 是印厂通例：裁切有 ±1mm 误差，
# 不留出血就会在成品边缘露出一条白边，而那条白边在屏幕预览里根本看不到。
W, H = 90.0, 54.0
BLEED = 3.0
SAFE = 5.0                      # 安全区：裁切线内 5mm，重要内容不许越界
CW, CH = W + 2 * BLEED, H + 2 * BLEED   # 含出血画布 96×60

MARK = 11.0                     # 方章边长
NAME_PT = 6.2                   # 姓名字号（mm 高，约 17.6pt）
TITLE_PT = 3.0
LINE_PT = 2.8
CJK = "Noto Sans SC, Source Han Sans SC, PingFang SC, sans-serif"
LAT = "Figtree, Helvetica, Arial, sans-serif"

# 300dpi 位图：1mm = 11.811px
DPI_SCALE = 300 / 25.4


def f(v):
    return B.f(v)


def theme_of(p):
    """按主体取配色。母品牌**不复用**子业务的红方章 —— 合同抬头与商城 App 长得一样，
    对公场合就分不出是哪一个主体。判据取 site，因为 CSV 里那一列是必填的。"""
    tech = "hxtech" in (p.get("site") or "") or "科技" in (p.get("brand") or "")
    if tech:
        return dict(plate=B.INK, glyph=B.PAPER, arc=B.RED_BRIGHT, back=B.INK)
    return dict(plate=B.RED, glyph=B.PAPER, arc=B.PAPER, back=B.RED)


def _canvas(body, bg="#FFFFFF"):
    """含出血的画布。裁切线与安全区只画在预览稿上，正式稿不带。"""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {f(CW)} {f(CH)}" '
            f'width="{f(CW)}mm" height="{f(CH)}mm">'
            f'<rect width="{f(CW)}" height="{f(CH)}" fill="{bg}"/>{body}</svg>\n')


def _guides():
    """裁切线 + 安全区，仅预览稿用。印厂拿到的正式稿不能带这两条线。"""
    return (f'<rect x="{f(BLEED)}" y="{f(BLEED)}" width="{f(W)}" height="{f(H)}" '
            f'fill="none" stroke="#00A0FF" stroke-width="0.15" stroke-dasharray="1 1"/>'
            f'<rect x="{f(BLEED+SAFE)}" y="{f(BLEED+SAFE)}" width="{f(W-2*SAFE)}" '
            f'height="{f(H-2*SAFE)}" fill="none" stroke="#FF00A0" stroke-width="0.15" '
            f'stroke-dasharray="0.6 0.9"/>')


def front(p, guides=False):
    """正面：白底。左上标识 + 字标，中部姓名与职务，底部联系方式。"""
    x = BLEED + SAFE
    top = BLEED + SAFE
    t = theme_of(p)
    rule = t["plate"]
    mark = B.icon_svg(t["plate"], t["glyph"], t["arc"])
    # 把 64 单位的方章缩到 MARK mm
    inner = mark.replace('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">', "").replace("</svg>\n", "")
    body = (f'<g transform="translate({f(x)} {f(top)}) scale({f(MARK/64)})">{inner}</g>'
            # 字标：中文 + 英文副行，与方章顶对齐
            f'<text x="{f(x + MARK + 3)}" y="{f(top + 4.4)}" font-family="{CJK}" '
            f'font-weight="700" font-size="4.4" letter-spacing="0.44" fill="{B.INK}">'
            f'{p["brand"]}</text>'
            f'<text x="{f(x + MARK + 3.1)}" y="{f(top + 8.4)}" font-family="{LAT}" '
            f'font-weight="600" font-size="2.1" letter-spacing="0.63" fill="#63676E">'
            f'{p["brand_en"]}</text>'
            # 姓名 + 职务
            f'<text x="{f(x)}" y="{f(BLEED + 32.5)}" font-family="{CJK}" font-weight="700" '
            f'font-size="{f(NAME_PT)}" letter-spacing="0.3" fill="{B.INK}">{p["name"]}</text>'
            f'<text x="{f(x)}" y="{f(BLEED + 37.8)}" font-family="{CJK}" font-weight="500" '
            f'font-size="{f(TITLE_PT)}" letter-spacing="0.24" fill="#63676E">{p["title"]}</text>'
            # 一道主色短线，把姓名与联系方式分开。
            # y 取 39.6 不是随手定的：第一行联系方式基线在 43.0、字号 2.8，
            # 墨迹顶端约 40.7 —— 线放在 40.8 会直接压在电话号码上（实测过）
            f'<rect x="{f(x)}" y="{f(BLEED + 39.6)}" width="9" height="0.5" fill="{rule}"/>')
    # 三行基线止于 51.6，安全区底是 BLEED+H-SAFE=52。这个数是量出来的不是估的 ——
    # 而掉出去的那 3mm 正好是裁切误差范围，印出来可能被切掉半行
    lines = [v for v in (p.get("phone"), p.get("email"), p.get("site")) if v]
    for i, line in enumerate(lines):   # 不要用 t —— 它是上面的主题字典
        body += (f'<text x="{f(x)}" y="{f(BLEED + 43.0 + i * 2.8)}" font-family="{LAT}" '
                 f'font-weight="500" font-size="{f(LINE_PT)}" fill="{B.INK}">{line}</text>')
    if guides:
        body += _guides()
    return _canvas(body)


def back(p, guides=False):
    """背面：主色满幅 + 反白方章 + 字标 + 域名。**满幅色必须铺到出血边**。"""
    cx, cy = CW / 2, CH / 2
    m = 15.0
    t = theme_of(p)
    # 反白方章：白底 + 主体色字形，与正面同源
    mark = B.icon_svg(B.PAPER, t["plate"], t["arc"] if t["plate"] == B.INK else t["plate"])
    inner = mark.replace('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">', "").replace("</svg>\n", "")
    body = (f'<g transform="translate({f(cx - m/2)} {f(cy - m/2 - 6)}) scale({f(m/64)})">{inner}</g>'
            f'<text x="{f(cx)}" y="{f(cy + 12)}" text-anchor="middle" font-family="{CJK}" '
            f'font-weight="700" font-size="4.2" letter-spacing="0.42" fill="#FFFFFF">'
            f'{p["brand"]}</text>'
            f'<text x="{f(cx)}" y="{f(cy + 17)}" text-anchor="middle" font-family="{LAT}" '
            f'font-weight="600" font-size="2.2" letter-spacing="0.66" '
            f'fill="rgba(255,255,255,.72)">{p["site"]}</text>')
    if guides:
        body += _guides()
    return _canvas(body, bg=t["back"])


def slug(s):
    return "".join(ch for ch in s if ch.isalnum() or ch in "-_") or "card"


def main(path):
    OUT.mkdir(parents=True, exist_ok=True)
    rows = list(csv.DictReader(path.open(encoding="utf-8-sig")))
    if not rows:
        sys.exit(f"{path} 里没有数据行")
    n = 0
    for p in rows:
        p = {k: (v or "").strip() for k, v in p.items()}
        p.setdefault("brand", "虹选 · 好物")
        p.setdefault("brand_en", "HX MALL")
        p.setdefault("site", "hxmall.top")
        base = slug(p.get("slug") or p["name"])
        for side, fn in (("front", front), ("back", back)):
            # 正式稿（无辅助线）+ 预览稿（带裁切线与安全区）
            B.write(OUT / f"{base}-{side}.svg", fn(p))
            B.write(OUT / f"{base}-{side}-preview.svg", fn(p, guides=True))
            B.render_png_wh(fn(p), round(CW * DPI_SCALE), round(CH * DPI_SCALE),
                            OUT / f"{base}-{side}-300dpi.png", bg="FFFFFFFF")
            n += 3
    B.write(OUT / "README.md",
            "# 名片\\n\\n> 由 `brand/gen-print.py` 从 `brand/print/people.csv` 生成，勿手改单张。\\n\\n"
            f"尺寸 {W:.0f}×{H:.0f}mm，出血 {BLEED:.0f}mm（画布 {CW:.0f}×{CH:.0f}mm），"
            f"安全区裁切线内 {SAFE:.0f}mm。\\n\\n"
            "| 文件 | 用途 |\\n|---|---|\\n"
            "| `*-front.svg` / `*-back.svg` | **交印厂的正式稿**，无辅助线 |\\n"
            "| `*-preview.svg` | 预览：蓝色虚线=裁切线，粉色虚线=安全区。**不要交印厂** |\\n"
            "| `*-300dpi.png` | 校对用位图 |\\n\\n"
            "## 改名字怎么改\\n\\n"
            "改 `brand/print/people.csv` 的一行，重跑 `python3 brand/gen-print.py`。\\n"
            "**不要直接编辑单张 SVG** —— 下次重跑会覆盖，而且十个人各改各的，版式必然漂移。\\n\\n"
            "## 交印厂前必做\\n\\n"
            "1. **文字转轮廓**（Illustrator「创建轮廓」）—— 汉字依赖装机字体，"
            "换台机器字形就变了，屏幕上看不出来\\n"
            "2. 用正式稿（不带 `-preview`），辅助线不能印上去\\n"
            "3. 背面满幅红要确认铺到出血边 —— 裁切有 ±1mm 误差，不铺满会露白边\\n"
            "4. 主色印刷参考 C0 M91 Y92 K0，正式打样前以实际纸张为准\\n")
    return n + 1


if __name__ == "__main__":
    src = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else CSV_DEFAULT
    if not src.exists():
        sys.exit(f"找不到数据表 {src} —— 先照 brand/print/people.csv 的表头建一份")
    print(f"名片 → {OUT}\n  {main(src)} 个文件（每人：正/反 × 正式稿 + 预览稿 + 300dpi）")
