#!/usr/bin/env python3
"""虹选品牌资产生成器 —— 单一真源。

改这里的参数，`python3 brand/build.py` 一跑，各端产物全部重生成。
矢量、位图、Android VectorDrawable、iOS AppIcon 集合都从同一组几何参数派生，
所以不会出现「某一端的图标和别处不一样」这种事。

依赖：headless Chrome（渲 PNG）。macOS 默认路径已内置，可用 CHROME 环境变量覆盖。
"""
import json, os, pathlib, shutil, subprocess, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parent
REPO = ROOT.parent

# ─────────────────────────────────────────── 品牌参数（唯一真源）
# 色值与对比度取自 brand/spec.html §01（实测值，非估算）
RED      = "#E1251B"   # 主色 · 标识 / 主按钮底 / C 端图标底。压白字 4.69 ✓ AA
RED_DEEP = "#B31710"   # 深红 · 浅底上的红色文字、hover。白底 6.89 ✓
RED_BRIGHT = "#FF5A4D" # 亮红 · 深色模式主色。压深底 5.66 ✓
INK      = "#17181A"   # 墨 · 正文 / 深底 / 单色稿。白底 17.77 ✓
PAPER    = "#FFFFFF"
# B 端图标底板。**2026-08-20 由深板岩 #242B33 改为墨**：
# spec 的禁止事项写着「底色只用主色 / 墨 / 反白」，深板岩三样都不是；
# 而合规的三种里红已被 C 端占用（两端曾因此撞成同一张图），反白在浅色壁纸上丢边界。
PLATE_B  = INK

# ── 几何（spec §02；2026-08-20 定档）
# 全部按「边长 S」的比例定义 —— 与尺寸无关，任何画布等比推出。
#
# **分档不是可选项**：规范 §02 对 H / HX / 虹 各给各的值，早先这里只有一档，
# H 被塞进 HX 的度量里 —— 章内的 H 只有规范的四成大，还被一道比它还宽的弧压着。
GEO = dict(
    radius   = 0.275,  # 方章圆角 / 边长（App 图标另按平台遮罩，源不预切）
    stroke   = 0.267,  # 字形笔画宽 / 字高（H、X 通用，直角切口）
    arc_s    = 0.05,   # 弧线描边宽 / 边长（**不等于字形笔画宽** ——
                       # 取字形笔画宽的话，描边比弧线半径还宽，弧线会糊成一顶实心帽子）
    gap      = 0.04,   # 弧线与字符的间距 / 边长
    h_ratio  = 0.80,   # H 宽 / H 高（沿用既有比例，spec 未另行规定）
    arc_flat = 0.65,   # **竖半径 ÷ 横半径**。1.00 = 正半圆（规范原文）。
                       # 压扁只省纵向、不动横向；再扁到 0.50，弧内净空不足两个描边宽，
                       # 小尺寸下读成一条横线，「虹」的字义就没了。
    bar      = 0.38,   # **H 横画中心高度 ÷ 字高**（从顶算起）。旧版 G4 的造型动作，
                       # 换代时丢过一轮，2026-08-20 取回。单独 H 与 HX 里的 H 同值 ——
                       # 两处 H 长得不一样就是两套字形。
)

# 分档取值。`arc_w=None` 表示**该档没有弧线** —— H 档就是这样，
# 素材 h-square.svg / h-circle.svg 里本来就只有一个 H。
TIERS = {
    "H":    dict(glyph_h=0.64, arc_w=None),
    "HX":   dict(glyph_h=0.30, arc_w=0.44),
    "CN1":  dict(glyph_h=0.46, arc_w=0.40),   # 「虹」单字方章
    "CN2":  dict(glyph_h=0.38, arc_w=0.38),   # 「虹选」双字方章，弧只压「虹」
}

CJK = "Noto Sans SC, Source Han Sans SC, PingFang SC, sans-serif"
CJK_INK = 0.86   # 汉字墨迹高 / em。em 框下方是用不到的降部，按 em 居中会整体偏上


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s or "0"


def glyph_metrics(S, tier="HX"):
    """由边长与档位推出这一档的全部几何。**只有这一个函数知道比例**，其余都拿它的结果。"""
    g, t = GEO, TIERS[tier]
    h  = t["glyph_h"] * S              # 字高
    w  = g["stroke"] * h               # 笔画宽（等宽、直角切口）
    gw = g["h_ratio"] * h              # H 字宽
    aw = g["arc_s"] * S                # 弧线描边宽
    if t["arc_w"] is None:             # 无弧线档：整组就是字本身
        return dict(h=h, w=w, gw=gw, aw=0.0, rx=0.0, ry=0.0, arc_h=0.0, gap=0.0,
                    top=(S - h) / 2, cx=S / 2)
    # 弧线是**描边**画的：外宽 = 中线直径 + 描边宽，故中线半径这样反推
    rx = (t["arc_w"] * S - aw) / 2
    ry = g["arc_flat"] * rx            # 压扁：只动竖半径
    gap = g["gap"] * S
    arc_h = ry + aw / 2                # 弧的可视高度
    total = arc_h + gap + h
    return dict(h=h, w=w, gw=gw, aw=aw, rx=rx, ry=ry, arc_h=arc_h, gap=gap,
                top=(S - total) / 2, cx=S / 2)


def arc_path(m, cx=None):
    """母题：一道弧（原为半圆，2026-08-20 起可压扁）。取「虹」的字义。

    `A r r` → `A rx ry` 是压扁的全部改动；纵向占位由 glyph_metrics 的 arc_h 跟着变，
    **两处必须一起改** —— 只改路径不改 arc_h，整组的垂直居中就偏了。
    """
    cx = m["cx"] if cx is None else cx
    cy = m["top"] + m["arc_h"]         # 圆心落在弧线底缘
    return (f'M{f(cx - m["rx"])} {f(cy)}'
            f'A{f(m["rx"])} {f(m["ry"])} 0 0 1 {f(cx + m["rx"])} {f(cy)}')


def _h_at(m, L, R):
    """H：两竖一横，笔画等宽、直角切口。横画居中。"""
    T = m["top"] + m["arc_h"] + m["gap"]
    B = T + m["h"]
    w = m["w"]
    cy = T + (B - T) * GEO["bar"]      # 高横：旧版 G4 的造型动作
    t, b = cy - w / 2, cy + w / 2
    return (f"M{f(L)} {f(T)}L{f(L+w)} {f(T)}L{f(L+w)} {f(t)}L{f(R-w)} {f(t)}"
            f"L{f(R-w)} {f(T)}L{f(R)} {f(T)}L{f(R)} {f(B)}L{f(R-w)} {f(B)}"
            f"L{f(R-w)} {f(b)}L{f(L+w)} {f(b)}L{f(L+w)} {f(B)}L{f(L)} {f(B)}Z")


def _x_at(m, L, R):
    """X：**按 H 的参数反推** —— 同一字高、同一笔画宽，两道对角线。

    笔画宽沿**垂直于笔画方向**量（不是水平量）：水平量的话斜杠会比竖笔细一圈，
    而 H 与 X 并排时那点差别一眼就看得出来。
    """
    import math
    T = m["top"] + m["arc_h"] + m["gap"]
    B = T + m["h"]
    half = m["w"] / 2

    def bar(p0, p1):
        (x0, y0), (x1, y1) = p0, p1
        dx, dy = x1 - x0, y1 - y0
        ln = math.hypot(dx, dy)
        nx, ny = -dy / ln * half, dx / ln * half
        pts = [(x0 + nx, y0 + ny), (x1 + nx, y1 + ny), (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)]
        return "M" + "L".join(f"{f(x)} {f(y)}" for x, y in pts) + "Z"

    return bar((L, T), (R, B)) + bar((R, T), (L, B))


def svg(vb, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}">'
            f'{body}</svg>\n')


def hx_paths(m):
    """HX 并排：H 在左、X 在右，同字高同笔画宽，中间留一个笔画宽的字距。"""
    gap_x = m["w"]
    total = m["gw"] * 2 + gap_x
    x0 = m["cx"] - total / 2
    return [
        _h_at(m, x0, x0 + m["gw"]),
        _x_at(m, x0 + m["gw"] + gap_x, x0 + total),
    ]


def glyph_body(S, color, arc_color=None, letters="HX"):
    """弧线 + 字形。弧线用描边（它是一道线，不是面），可与字形异色。

    **H 档不画弧** —— 素材里就没有，规范表里也没有「弧线宽（H）」这一行。
    """
    tier = "HX" if letters == "HX" else "H"
    m = glyph_metrics(S, tier)
    if letters == "HX":
        paths = hx_paths(m)
    elif letters == "X":
        paths = [_x_at(m, m["cx"] - m["gw"] / 2, m["cx"] + m["gw"] / 2)]
    else:
        paths = [_h_at(m, m["cx"] - m["gw"] / 2, m["cx"] + m["gw"] / 2)]
    body = "".join(f'<path d="{d}" fill="{color}"/>' for d in paths)
    if TIERS[tier]["arc_w"] is None:
        return body
    return (f'<path d="{arc_path(m)}" fill="none" stroke="{arc_color or color}" '
            f'stroke-width="{f(m["aw"])}" stroke-linecap="butt"/>' + body)


def cn_body(S, color, arc_color=None, text="虹", span="虹"):
    """中文方章。单字走 CN1 档，双字走 CN2 档。

    `span` 决定**弧罩住谁**，这是双字章两个方案的唯一区别：

      "虹"   弧只压第一个字 —— 守规范 §04（弧是「虹」的字义符号，不是整词的装饰）。
             代价：方章与横向字标同一套规则，排版不会混用。
      "虹选" 弧横跨两字 —— 与 HX 方章同构（弧宽取字组总宽的 0.70，和 HX 档
             0.44 ÷ 0.633 是同一个比例）。代价：弧不再特指「虹」，
             **选它就得同时改写规范 §04**，否则方章与字标两种规则并存。

    汉字用 <text>，依赖系统字体。**交印刷厂或商标申报前必须转轮廓** ——
    换台机器字体不同，字形就变了，而这种变化在屏幕上看不出来。
    """
    two = len(text) > 1
    g, t = GEO, TIERS["CN2" if two else "CN1"]
    ch = t["glyph_h"] * S
    track = 0.02 * S if two else 0.0        # 紧排：按 0.10em 排，144px 下两字各自糊掉
    total_w = (2 * ch + track) if two else ch
    x0 = (S - total_w) / 2
    aw = g["arc_s"] * S
    if two and span == "虹选":
        arc_out = 0.70 * total_w            # 与 HX 档同比例
        cx = S / 2
    else:
        arc_out = t["arc_w"] * S
        cx = x0 + ch / 2                    # 弧心对准第一个字（「虹」）
    rx = (arc_out - aw) / 2
    ry = g["arc_flat"] * rx
    ink = CJK_INK * ch
    arc_h = ry + aw / 2
    gap = g["gap"] * S
    top = (S - (arc_h + gap + ink)) / 2
    cy = top + arc_h
    baseline = top + arc_h + gap + ink
    ls = f' letter-spacing="{f(track)}"' if two else ""
    return (f'<path d="M{f(cx-rx)} {f(cy)}A{f(rx)} {f(ry)} 0 0 1 {f(cx+rx)} {f(cy)}" '
            f'fill="none" stroke="{arc_color or color}" stroke-width="{f(aw)}"/>'
            f'<text x="{f(x0)}" y="{f(baseline)}" font-family="{CJK}" font-weight="700" '
            f'font-size="{f(ch)}"{ls} fill="{color}">{text}</text>')


def wordmark_body(H, text, en, ink, arc):
    """横向中文字标：弧宽 = 1.00 × 字高，左端与「虹」左边缘对齐，只覆盖「虹」。

    加「好物」之后弧**不变宽** —— 这条最容易被排版的人改错。
    """
    aw = 0.085 * H                          # 字标档描边比章内略粗，与素材一致
    rx = (H - aw) / 2
    ry = GEO["arc_flat"] * rx
    cy = ry + aw / 2
    base = cy + 0.10 * H + H
    return (aw, base, f'<path d="M0 {f(cy)}A{f(rx)} {f(ry)} 0 0 1 {f(2*rx)} {f(cy)}" '
            f'fill="none" stroke="{arc}" stroke-width="{f(aw)}"/>'
            f'<text x="0" y="{f(base)}" font-family="{CJK}" font-weight="700" '
            f'font-size="{f(H)}" letter-spacing="{f(0.10*H)}" fill="{ink}">{text}</text>'
            f'<text x="0" y="{f(base + 0.30*H)}" font-family="Figtree, sans-serif" '
            f'font-weight="600" font-size="{f(0.26*H)}" letter-spacing="{f(0.078*H)}" '
            f'fill="#63676E">{en}</text>')


def wordmark_svg(text, en, ink=None, arc=None, H=64):
    ink = ink or INK
    arc = arc or RED
    aw, base, body = wordmark_body(H, text, en, ink, arc)
    w = H * (len(text) * 1.12 + 0.6)
    return svg(f"0 0 {f(w)} {f(base + 0.36*H)}", body)


def mark_svg(fill):
    """字标：透明底，只有弧线与字形"""
    return svg("0 0 64 64", glyph_body(64, fill))


def cn_icon_svg(plate, glyph, arc=None, text="虹", span="虹"):
    """中文满幅方章（小程序中文版、包装）"""
    return svg("0 0 64 64", f'<rect width="64" height="64" fill="{plate}"/>'
                            + cn_body(64, glyph, arc, text, span))


def icon_svg(plate, glyph, arc=None):
    """满幅方章：底色出血到边，**不预切圆角**（iOS/Android 各自套遮罩，预切会二次圆角）"""
    return svg("0 0 64 64",
               f'<rect width="64" height="64" fill="{plate}"/>' + glyph_body(64, glyph, arc))


def adaptive_fg_svg(glyph, arc=None):
    """Android 自适应前景：内容缩到安全圆内（圆形遮罩会削角）。
       108 画布的安全直径是 72 —— 按 72 的画布排版，再整体居中到 108。"""
    inner = 72
    off = (108 - inner) / 2
    return svg("0 0 108 108",
               f'<g transform="translate({f(off)} {f(off)})">{glyph_body(inner, glyph, arc)}</g>')



# 旧版几何（虹橙 · G4 高横 H）已于 2026-08-20 删除：C 端与 B 端一并换代到
# 红 + 扁弧 + HX，legacy_* 再无引用。高横这个造型动作没丢 —— 它变成了 GEO["bar"]。


APPS = {
    "c": dict(name="虹选好物", label="虹选 · 好物", plate=RED, glyph=PAPER, arc=PAPER,
              flavor="consumer", pkg="ai.neargo.shop.c"),
    # B 端：**红底 + 中文字标「虹选」**（2026-08-20 拍板）。
    #
    # 走过的两版都记在这里，免得再绕：
    #   ① 红底 + HX —— 与 C 端**字节完全相同**，桌面上只能靠名字分，
    #      而桌面名在文件夹里、在搜索结果里都会被截断。
    #   ② 墨底 + HX —— 分得开了，但商家端第一眼不是品牌色。
    # 现在这版两头都要：底色仍是主色红（品牌一致 + 深浅壁纸上边界都最稳，
    # 实测压黑 4.48、压白 4.69），靠**字形**与 C 端区分 —— 一个 HX，一个「虹选」。
    #
    # 为什么是「虹选」两字而不是「虹选商家」四字：图标在桌面上是 48dp，
    # 四个汉字每个不到 10dp，笔画糊成一团 —— 而「商家」这层信息桌面名里已经有了
    # （app_name = 虹选商家）。方章走 spec §02 已有的 CN2 档，不新造几何。
    "b": dict(name="虹选商家", label="虹选 · 商家", plate=RED, glyph=PAPER, arc=PAPER,
              cn="虹选", flavor="merchant", pkg="ai.neargo.shop.b"),
}

# ─────────────────────────────────────────── PNG 渲染（headless Chrome）
CHROME = os.environ.get(
    "CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")


def render_png_wh(svg_text, w, h, out: pathlib.Path, bg="00000000"):
    """任意宽高渲染。OG 卡片是 1200×630，**不是正方形** ——
    早先复用了只出正方形的 render_png，结果 og.png 出成 1200×1200，
    而分享出去只会被裁掉一块，没人会想到去量它。"""
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        page = pathlib.Path(td) / "p.html"
        page.write_text(
            "<style>html,body{margin:0;padding:0;background:transparent}"
            f"svg{{display:block;width:{w}px;height:{h}px}}</style>{svg_text}",
            encoding="utf-8")
        subprocess.run(
            [CHROME, "--headless", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
             f"--default-background-color={bg}",
             f"--window-size={w},{h}", f"--screenshot={out}",
             "--virtual-time-budget=2000", f"file://{page}"],
            check=True, capture_output=True)


def render_png(svg_text, size, out: pathlib.Path):
    """把 SVG 按精确像素渲成 PNG。用 HTML 包一层是为了拿到确定的画布尺寸 ——
    直接喂 SVG 给 Chrome，无 intrinsic size 时缩放行为不确定。"""
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        page = pathlib.Path(td) / "p.html"
        page.write_text(
            "<style>html,body{margin:0;padding:0;background:transparent}"
            f"svg{{display:block;width:{size}px;height:{size}px}}</style>{svg_text}",
            encoding="utf-8")
        subprocess.run(
            [CHROME, "--headless", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
             "--default-background-color=00000000",
             f"--window-size={size},{size}", f"--screenshot={out}",
             "--virtual-time-budget=2000", f"file://{page}"],
            check=True, capture_output=True)


# ─────────────────────────────────────────── 产物
def write(p: pathlib.Path, text):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")
    return p



def app_icon_svg(a):
    # 带 `cn` 的走中文方章（B 端「虹选」），否则走 HX 方章（C 端）
    if a.get("cn"):
        return cn_icon_svg(a["plate"], a["glyph"], a["arc"], text=a["cn"], span="虹")
    return icon_svg(a["plate"], a["glyph"], a["arc"])


def app_adaptive_fg_svg(a):
    return adaptive_fg_svg(a["glyph"], a["arc"])


def app_android_fg_paths(a):
    return android_fg_paths(a["glyph"], a["arc"])


def build_vectors():
    d = ROOT / "logo"
    files = {
        "mark-red.svg":      mark_svg(RED),
        "mark-ink.svg":      mark_svg(INK),
        "mark-reverse.svg":  mark_svg(PAPER),
    }
    for k, a in APPS.items():
        files[f"icon-{k}.svg"]        = app_icon_svg(a)
        files[f"adaptive-fg-{k}.svg"] = app_adaptive_fg_svg(a)
        files[f"adaptive-bg-{k}.svg"] = svg("0 0 108 108",
                                            f'<rect width="108" height="108" fill="{a["plate"]}"/>')
    # 母品牌：同参数，只换配色 —— 墨底 + 亮红弧，出现在合同/资质/公函，要稳不要抢眼
    files["icon-tech.svg"] = icon_svg(INK, PAPER, RED_BRIGHT)
    # 横向字标。**含文字，交印刷厂或商标申报前必须转轮廓**
    for n, (t, en) in {
        "wordmark-hongxuan": ("虹选", "HX MALL"),
        "wordmark-hongxuan-haowu": ("虹选 · 好物", "HX MALL"),
        "wordmark-hongxuan-tech": ("虹选科技", "HX TECH"),
    }.items():
        files[f"{n}.svg"] = wordmark_svg(t, en)
    # 域名字标：全小写，后缀取主色
    for dom in ("hxmall", "hxtech"):
        files[f"domain-{dom}.svg"] = svg(
            "0 0 420 60",
            f'<text x="0" y="46" font-family="Figtree, sans-serif" font-weight="700" '
            f'font-size="48" fill="{INK}">{dom}<tspan fill="{RED}">.top</tspan></text>')
    # 中文方章（小程序中文版、包装）
    files["cn1-red.svg"] = cn_icon_svg(RED, PAPER)
    # 双字方章两版并存到**定案为止** —— 甲守规范，乙与 HX 同构。
    # 选定后删掉另一个，别让两版一直躺在交付物里（迟早有人拿错）。
    files["cn2-a-red.svg"] = cn_icon_svg(RED, PAPER, text="虹选", span="虹")
    files["cn2-b-red.svg"] = cn_icon_svg(RED, PAPER, text="虹选", span="虹选")
    files["cn2-a-ink.svg"] = cn_icon_svg(INK, PAPER, RED_BRIGHT, text="虹选", span="虹")
    files["cn2-b-ink.svg"] = cn_icon_svg(INK, PAPER, RED_BRIGHT, text="虹选", span="虹选")
    for n, c in files.items():
        write(d / n, c)
    return len(files)


def build_tokens():
    tok = {
        "brand": {
            "red":         {"value": RED,        "note": "主色 · 压白字 4.69 AA"},
            "redDeep":     {"value": RED_DEEP,   "note": "深红 · 浅底红字 / 白底 6.89 AA"},
            "redBright":   {"value": RED_BRIGHT, "note": "亮红 · 深色模式主色 / B 端图标 4.65 AA"},
            "ink":         {"value": INK,      "note": "墨"},
            "paper":       {"value": PAPER,    "note": "纸"},
            "plateMerchant": {"value": PLATE_B, "note": "B 端图标底板 · 墨（原深板岩 #242B33，见 spec §01）"},
        }
    }
    write(ROOT / "tokens.json", json.dumps(tok, ensure_ascii=False, indent=2) + "\n")
    css = ("/* 虹选品牌色 · 由 brand/build.py 生成，勿手改 */\n:root{\n"
           f"  --hx-red:{RED};\n  --hx-red-deep:{RED_DEEP};\n  --hx-red-bright:{RED_BRIGHT};\n"
           f"  --hx-ink:{INK};\n  --hx-paper:{PAPER};\n"
           f"  --hx-plate-merchant:{PLATE_B};\n}}\n")
    write(ROOT / "tokens.css", css)
    ts = ("// 虹选品牌色 · 由 brand/build.py 生成，勿手改\n"
          "export const brand = {\n"
          f"  red: {RED!r},\n  redDeep: {RED_DEEP!r},\n  redBright: {RED_BRIGHT!r},\n"
          f"  ink: {INK!r},\n  paper: {PAPER!r},\n"
          f"  plateMerchant: {PLATE_B!r},\n"
          "} as const;\n")
    write(ROOT / "tokens.ts", ts.replace("'", '"'))



def android_fg_paths(glyph, arc):
    """Android 自适应前景的 VectorDrawable 路径。

    **与 adaptive_fg_svg 用同一个 glyph_metrics** —— 两条产物链各算一遍的话，
    迟早出现「矢量图标和位图图标不一样」，而那正是这套管线要消灭的问题。
    圆形遮罩会削角，所以内容排在 72 的安全区里再整体居中到 108。
    """
    inner = 72
    off = (108 - inner) / 2
    m = glyph_metrics(inner, "HX")
    letters = "".join(
        f'        <path android:fillColor="{glyph}" android:pathData="{d}" />\n'
        for d in hx_paths(m))
    return (f'    <group android:translateX="{f(off)}" android:translateY="{f(off)}">\n'
            f'        <path android:strokeColor="{arc or glyph}" '
            f'android:strokeWidth="{f(m["aw"])}" android:fillColor="#00000000" '
            f'android:pathData="{arc_path(m)}" />\n'
            f'{letters}'
            f'    </group>\n')


def build_android():
    """Android：v26+ 走自适应（矢量，永不失真），旧版用位图兜底。"""
    DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    n = 0
    for k, a in APPS.items():
        res = REPO / "android-shell/app/src" / a["flavor"] / "res"
        # 旧版位图
        for dens, px in DENS.items():
            render_png(app_icon_svg(a), px,
                       res / f"mipmap-{dens}" / "ic_launcher.png")
            render_png(app_icon_svg(a), px,
                       res / f"mipmap-{dens}" / "ic_launcher_round.png")
            n += 2
        # 自适应：前景用 VectorDrawable，缩放到任何密度都不糊
        write(res / "drawable" / "ic_launcher_foreground.xml",
              '<?xml version="1.0" encoding="utf-8"?>\n'
              '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
              '    android:width="108dp" android:height="108dp"\n'
              '    android:viewportWidth="108" android:viewportHeight="108">\n'
              + app_android_fg_paths(a)
              + '</vector>\n')
        write(res / "values" / "ic_launcher_background.xml",
              '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
              f'    <color name="ic_launcher_background">{a["plate"]}</color>\n</resources>\n')
        for fn in ("ic_launcher.xml", "ic_launcher_round.xml"):
            write(res / "mipmap-anydpi-v26" / fn,
                  '<?xml version="1.0" encoding="utf-8"?>\n'
                  '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                  '    <background android:drawable="@color/ic_launcher_background" />\n'
                  '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                  '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
                  '</adaptive-icon>\n')
        n += 4
        # Play 商店图
        # 走分派器而不是直接 icon_svg：直接调会跳过 APPS 里的 `cn`，
        # 于是 B 端在 Play 商店那张图上仍是 HX，而桌面上是「虹选」—— 同一个 app 两张脸
        render_png(app_icon_svg(a), 512, ROOT / "store" / f"play-{k}-512.png")
        n += 1
    return n


def build_ios():
    """iOS：Xcode 14+ 接受单尺寸 1024 图集。1024 不得带透明通道，否则 App Store 拒。"""
    n = 0
    for k, a in APPS.items():
        d = ROOT / "ios" / f"AppIcon-{k}.appiconset"
        render_png(app_icon_svg(a), 1024, d / "icon-1024.png")   # 同上：必须走分派器
        write(d / "Contents.json", json.dumps({
            "images": [{"filename": "icon-1024.png", "idiom": "universal",
                        "platform": "ios", "size": "1024x1024"}],
            "info": {"author": "brand/build.py", "version": 1},
        }, indent=2) + "\n")
        n += 2
    return n


def build_web():
    """三个 Web 端各一套。SVG favicon 现代浏览器优先，PNG 兜底。"""
    targets = {
        "c-app/public":   "c",
        "b-app/public":   "b",
        "ops-web/public": "b",   # 运营端是内部工具，用 B 端那副冷静的脸
    }
    n = 0
    for rel, k in targets.items():
        a = APPS[k]
        out = REPO / rel
        write(out / "favicon.svg", icon_svg(a["plate"], a["glyph"]))
        for px, name in ((32, "favicon-32.png"), (180, "apple-touch-icon.png"),
                         (192, "icon-192.png"), (512, "icon-512.png")):
            render_png(app_icon_svg(a), px, out / name)
            n += 1
        n += 1
    return n


def build_splash():
    """启动页。**缺了它就是冷启动白屏** —— 用户第一眼看到的东西。

    ⚠️ 不用 androidx `core-splashscreen`：这个仓库没有那个依赖，
    继承 `Theme.SplashScreen` 会直接编译不过（resource style/Theme.SplashScreen not found）。
    改用**平台属性** `android:windowSplashScreenBackground` / `...AnimatedIcon` ——
    它们是 API 31+ 自带的，compileSdk 34 下能解析，不引入任何新依赖。
    31 以下退回一张 layer-list 当 windowBackground。

    两端共用 src/main 的主题，底色 `@color/splash_background` 按 flavor 各给各的。
    """
    res = REPO / "android-shell/app/src/main/res"
    n = 0
    write(res / "values" / "themes.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          '    <!-- 31 以下：一张静态图当窗口背景，避免冷启动白屏 -->\n'
          '    <style name="Theme.Shell.Splash" parent="Theme.AppCompat.NoActionBar">\n'
          '        <item name="android:windowBackground">@drawable/splash</item>\n'
          '    </style>\n</resources>\n')
    write(res / "values-v31" / "themes.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          '    <!-- 31+：交给系统的 SplashScreen，图标复用自适应前景（矢量，任何密度不糊）。\n'
          '         用平台属性而非 androidx 库 —— 本仓库没有 core-splashscreen 依赖。 -->\n'
          '    <style name="Theme.Shell.Splash" parent="Theme.AppCompat.NoActionBar">\n'
          '        <item name="android:windowSplashScreenBackground">@color/splash_background</item>\n'
          '        <item name="android:windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>\n'
          '    </style>\n</resources>\n')
    write(res / "drawable" / "splash.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n'
          '<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n'
          '    <item android:drawable="@color/splash_background" />\n'
          '    <item android:gravity="center">\n'
          '        <bitmap android:src="@mipmap/ic_launcher" android:gravity="center" />\n'
          '    </item>\n</layer-list>\n')
    n += 3
    # 通知栏：**去弧线、仅 H 单字符**，白色剪影（系统会重新着色，带颜色的图标会被压成白块）
    m = glyph_metrics(24, "H")
    write(res / "drawable" / "ic_notification.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n'
          '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    android:width="24dp" android:height="24dp"\n'
          '    android:viewportWidth="24" android:viewportHeight="24">\n'
          f'    <path android:fillColor="#FFFFFFFF" android:pathData="'
          f'{_h_at(m, m["cx"] - m["gw"] / 2, m["cx"] + m["gw"] / 2)}" />\n'
          '</vector>\n')
    n += 1
    for k, a in APPS.items():
        write(REPO / "android-shell/app/src" / a["flavor"] / "res/values/splash.xml",
              '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
              f'    <color name="splash_background">{a["plate"]}</color>\n</resources>\n')
        n += 1
    return n


def build_miniprogram():
    """小程序：方形 HX + 中文双字版。微信端会自动裁圆，源**不预切圆角**。

    出 144 / 512 / 1024 三档：144 是微信头像的最低要求，但上传大图由平台自己压
    比我们先缩再传清楚 —— 缩过一次的图再被平台缩一次，笔画边缘会糊。
    """
    SIZES = (144, 512, 1024)
    n = 0
    for k, a in APPS.items():
        for px in SIZES:
            render_png(icon_svg(a["plate"], a["glyph"], a["arc"]), px,
                       ROOT / "store" / f"mp-{k}-hx-{px}.png")
            n += 1
    # 中文版只出 C 端（面向消费者；商家侧没有中文图标的场景）。
    # 甲 = 弧只压「虹」（守规范 §04）；乙 = 弧横跨两字（与 HX 方章同构）。
    # 两版并存到定案为止 —— 定了就删掉没选的那个，别让人拿错。
    for tag, span in (("a", "虹"), ("b", "虹选")):
        for px in SIZES:
            render_png(cn_icon_svg(RED, PAPER, text="虹选", span=span), px,
                       ROOT / "store" / f"mp-c-cn-{tag}-{px}.png")
            n += 1
    return n


SITES = {
    # 子业务官网：白底 + 红方章
    "hxmall": dict(out="site/public", plate=RED, glyph=PAPER, arc=PAPER,
                   name="虹选 · 好物", short="虹选好物", wm=("虹选 · 好物", "HX MALL")),
    # 母品牌官网：墨底 + 亮红弧。出现在合同、资质、公函，要稳不要抢眼。
    # 它还没有工程目录，所以直接落在 brand/dist/site-hxtech/
    "hxtech": dict(out="dist/site-hxtech", plate=INK, glyph=PAPER, arc=RED_BRIGHT,
                   name="虹选科技", short="虹选科技", wm=("虹选科技", "HX TECH")),
}


def build_one_site(key):
    """一个官网的整套图标。

    两个域名各出一套 —— 母品牌**不能复用**子业务的红方章，否则合同抬头与商城 App
    长得一样，对公场合分不出是哪一个主体。
    """
    c = SITES[key]
    out = ROOT / c["out"] if c["out"].startswith("dist/") else REPO / c["out"]
    src = icon_svg(c["plate"], c["glyph"], c["arc"])
    n = 0
    write(out / "favicon.svg", src)
    n += 1
    for px, name in ((16, "favicon-16.png"), (32, "favicon-32.png"), (48, "favicon-48.png"),
                     (180, "apple-touch-icon.png"), (192, "icon-192.png"),
                     (512, "icon-512.png"), (300, "share-300.png")):
        render_png(src, px, out / name)
        n += 1
    build_ico([out / "favicon-16.png", out / "favicon-32.png", out / "favicon-48.png"],
              out / "favicon.ico")
    n += 1
    # OG 卡片：白底 + 横向字标。1200×630，**不是正方形**
    _, _, wm = wordmark_body(110, c["wm"][0], c["wm"][1], INK,
                             RED if key == "hxmall" else RED_BRIGHT)
    write(out / "og.svg", svg("0 0 1200 630",
          f'<rect width="1200" height="630" fill="{PAPER}"/>'
          f'<g transform="translate(120,215)">{wm}</g>'))
    render_png_wh((out / "og.svg").read_text(), 1200, 630, out / "og.png", bg="FFFFFFFF")
    n += 2
    write(out / "site.webmanifest", json.dumps({
        "name": c["name"], "short_name": c["short"],
        "icons": [{"src": "/icon-192.png", "sizes": "192x192", "type": "image/png"},
                  {"src": "/icon-512.png", "sizes": "512x512", "type": "image/png"}],
        "theme_color": c["plate"], "background_color": PAPER, "display": "standalone",
    }, ensure_ascii=False, indent=2) + "\n")
    return n + 1


def build_site():
    return sum(build_one_site(k) for k in SITES)


def build_dist():
    """**按端分发目录** `brand/dist/<端>/` —— 各端直接从固定路径取，不用满仓库找。

    它是**生成的镜像**，不是第二处真源：内容与各 build_* 的产出同源，改参数重跑就全部刷新。
    **不要往 dist/ 里手放文件** —— 主流程开头会整个清掉，而「上次明明放进去了」最难查。

    小程序、印刷、母品牌官网三组只有 dist 一个家（仓库里没有对应工程目录），其余是镜像。
    """
    import shutil
    dist = ROOT / "dist"

    def put(end, src: pathlib.Path, name=None):
        if not src.exists():
            return 0
        dst = dist / end / (name or src.name)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        return 1

    n = 0
    plan = {
        "c-app": [
            (ROOT / "logo/icon-c.svg", "icon.svg"),
            (ROOT / "ios/AppIcon-c.appiconset/icon-1024.png", "app-icon-1024.png"),
            (ROOT / "store/play-c-512.png", "play-512.png"),
            (ROOT / "logo/adaptive-fg-c.svg", None), (ROOT / "logo/adaptive-bg-c.svg", None),
        ] + [(REPO / f"c-app/public/{f}", None) for f in
             ("favicon.svg", "favicon-32.png", "apple-touch-icon.png", "icon-192.png", "icon-512.png")],
        "b-app": [
            (ROOT / "logo/icon-b.svg", "icon.svg"),
            (ROOT / "ios/AppIcon-b.appiconset/icon-1024.png", "app-icon-1024.png"),
            (ROOT / "store/play-b-512.png", "play-512.png"),
            (ROOT / "logo/adaptive-fg-b.svg", None), (ROOT / "logo/adaptive-bg-b.svg", None),
        ] + [(REPO / f"b-app/public/{f}", None) for f in
             ("favicon.svg", "favicon-32.png", "apple-touch-icon.png", "icon-192.png", "icon-512.png")],
        "mini-program": [(ROOT / "store/mp-c-hx-144.png", None), (ROOT / "store/mp-b-hx-144.png", None),
                         (ROOT / "store/mp-c-cn-144.png", None), (ROOT / "logo/cn2-red.svg", None)],
        "ops-web": [(REPO / f"ops-web/public/{f}", None) for f in
                    ("favicon.svg", "favicon-32.png", "apple-touch-icon.png", "icon-512.png")],
        "site-hxmall": [(REPO / f"site/public/{f}", None) for f in
                        ("favicon.svg", "favicon.ico", "apple-touch-icon.png", "icon-192.png",
                         "icon-512.png", "share-300.png", "og.png", "site.webmanifest")],
        "print": [(ROOT / f"logo/{f}", None) for f in
                  ("mark-red.svg", "mark-ink.svg", "mark-reverse.svg", "icon-c.svg", "icon-tech.svg",
                   "wordmark-hongxuan.svg", "wordmark-hongxuan-haowu.svg",
                   "wordmark-hongxuan-tech.svg", "domain-hxmall.svg", "domain-hxtech.svg")]
                 + [(ROOT / "tokens.css", None)],
        "trademark": [(f, None) for f in sorted((ROOT / "trademark").glob("*"))],
    }
    for end, items in plan.items():
        for src, name in items:
            n += put(end, src, name)

    notes = {
        "c-app": "C 端（虹选好物）。`icon.svg` 是矢量母版；Android 位图由 build.py 直接写进 "
                 "`android-shell/app/src/consumer/res/`，这里只放矢量与 H5 那套。",
        "b-app": "B 端（虹选商家）。同 C 端。⚠️ 底色与 C 端相同，两端图标目前只靠桌面名区分。",
        "mini-program": "小程序。方形源**不预切圆角** —— 微信端自动裁圆，预切会二次圆角。"
                        "中文版用「虹选」双字方章。",
        "ops-web": "运营端。内部工具，不进商店，只要 favicon 一套。",
        "site-hxmall": "**子业务官网 hxmall.top** · 白底 + 红方章。含 `favicon.ico`"
                       "（老浏览器与聚合器只认它）、`share-300.png`（微信只取正方形）、"
                       "`og.png` 1200×630（分享大图）。",
        "site-hxtech": "**母品牌官网 hxtech.top** · 墨底 + 亮红弧。母品牌不复用子业务的红方章 —— "
                       "否则合同抬头与商城 App 长得一样，对公场合分不出是哪一个主体。"
                       "这套没有工程目录，dist 是它唯一的家。",
        "print": "印刷。**矢量优先**；含中文的字标交印刷厂前必须转轮廓 —— "
                 "换台机器字体不同，字形就变了，而这种变化在屏幕上看不出来。名片见 `cards/`。",
        "trademark": "商标申报稿。纯黑实心 / 白底 / 无渐变无阴影无描边。"
                     "提交前转轮廓、另存 JPG（≥400px），PNG 带 alpha 会被退回。",
    }
    for end, note in notes.items():
        d = dist / end
        if not d.exists():
            continue
        files = "\n".join(f"- `{f.name}`" for f in sorted(d.iterdir())
                          if f.name != "README.md" and f.is_file())
        write(d / "README.md",
              f"# {end}\n\n> 由 `brand/build.py` 生成，**不要往这里手放文件** —— 下次重跑会被清掉。"
              f"\n\n{note}\n\n{files}\n")
        n += 1

    write(dist / "README.md",
          "# brand/dist —— 按端分发\n\n"
          "> 由 `brand/build.py` 的 `build_dist()` 生成。**这是镜像，不是真源。**\n"
          "> 真源是 `brand/build.py` 的参数；改参数重跑，这里全部刷新。\n"
          "> 往这里手放文件下次会被删掉 —— 需要新增产物请加进生成器。\n\n"
          "| 目录 | 谁用 |\n|---|---|\n"
          "| `c-app/` | C 端 App 与 H5 |\n"
          "| `b-app/` | B 端 App 与 H5 |\n"
          "| `mini-program/` | 微信小程序（两端 + 中文版）|\n"
          "| `ops-web/` | 运营端 |\n"
          "| `site-hxmall/` | **子业务官网 hxmall.top**（白底 + 红方章）|\n"
          "| `site-hxtech/` | **母品牌官网 hxtech.top**（墨底 + 亮红弧）|\n"
          "| `print/` | 印刷物料、名片 |\n"
          "| `trademark/` | 商标申报 |\n\n"
          "名片是**模板 + 数据表**：改 `brand/print/people.csv` 后跑 "
          "`python3 brand/gen-print.py`，每个人一套正反面。\n")
    return n + 1


def build_ico(png_paths, out: pathlib.Path):
    """把几张 PNG 打成一个 .ico。

    为什么还要 ico：老浏览器、部分聚合器与 RSS 阅读器只认 `/favicon.ico`，
    它们不会去读 <link rel="icon">。ICO 从 Vista 起允许**直接内嵌 PNG 数据**，
    所以不需要位图编码器 —— 拼一个头就行。
    """
    import struct
    imgs = [pathlib.Path(x).read_bytes() for x in png_paths]
    sizes = [16, 32, 48][:len(imgs)]
    header = struct.pack("<HHH", 0, 1, len(imgs))
    offset = 6 + 16 * len(imgs)
    entries, blob = b"", b""
    for px, data in zip(sizes, imgs):
        entries += struct.pack("<BBBBHHII", px if px < 256 else 0, px if px < 256 else 0,
                               0, 0, 1, 32, len(data), offset)
        blob += data
        offset += len(data)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(header + entries + blob)
    return out


def build_trademark():
    """商标申报稿：**纯黑实心、白底、无渐变无阴影无描边**，边界清晰。

    Slogan 与英文副行不进申报稿。含文字的单元（虹选）这里仍是 <text>，
    **提交前必须在 Illustrator 里转轮廓** —— 注册的是图形本身，字体不同就是另一个图形。
    申报要求 JPG 且边长 ≥400px；本函数出 SVG + 512px PNG，JPG 由设计侧最后转（PNG 带 alpha，
    直接改扩展名会被退回）。
    """
    d = ROOT / "trademark"
    BK, WH = "#000000", "#FFFFFF"
    units = {
        "hx":    lambda fg, bg: svg("0 0 64 64", f'<rect width="64" height="64" fill="{bg}"/>'
                                                 + glyph_body(64, fg)),
        "h":     lambda fg, bg: svg("0 0 64 64", f'<rect width="64" height="64" fill="{bg}"/>'
                                                 + glyph_body(64, fg, letters="H")),
        "cn2a":  lambda fg, bg: svg("0 0 64 64", f'<rect width="64" height="64" fill="{bg}"/>'
                                                 + cn_body(64, fg, text="虹选", span="虹")),
        "cn2b":  lambda fg, bg: svg("0 0 64 64", f'<rect width="64" height="64" fill="{bg}"/>'
                                                 + cn_body(64, fg, text="虹选", span="虹选")),
    }
    n = 0
    for name, fn in units.items():
        for tag, fg, bg in (("bw", BK, WH), ("inv", WH, BK)):
            src = fn(fg, bg)
            write(d / f"{name}-{tag}.svg", src)
            render_png(src, 512, d / f"{name}-{tag}-512.png")
            n += 2
    write(d / "README.md",
          "# 商标申报稿\n\n> 由 brand/build.py 生成，勿手改。\n\n"
          "纯黑实心 / 白底 / 无渐变无阴影无描边。Slogan 与英文副行不进申报稿。\n\n"
          "**提交前两件事**：\n"
          "1. 含文字的单元（`cn2-*`）在 Illustrator 里**转轮廓** —— "
          "注册的是图形本身，换个字体就是另一个图形\n"
          "2. PNG 带 alpha 通道，**另存为 JPG**（边长 ≥400px）再提交，改扩展名会被退回\n")
    return n + 1


if __name__ == "__main__":
    # 先清 dist，再让各 build_* 往里写 —— 顺序反了会删掉 build_site 刚写进去的 hxtech。
    #
    # ⚠️ **`print/cards/` 要留着**：名片是 gen-print.py 写进去的，不归本脚本管。
    # 整个 rmtree 会把它删掉，而症状是「总览页上名片那一组是空的」——
    # 看起来像生成器写漏了，实际是两个脚本的执行顺序耦合。踩过一次。
    import shutil as _sh
    _dist = ROOT / "dist"
    for _d in sorted(_dist.iterdir()) if _dist.exists() else []:
        if _d.is_dir() and _d.name == "print":
            for _x in _d.iterdir():
                if _x.name != "cards":
                    (_sh.rmtree(_x, ignore_errors=True) if _x.is_dir() else _x.unlink())
        elif _d.is_dir():
            _sh.rmtree(_d, ignore_errors=True)
        else:
            _d.unlink()
    if not pathlib.Path(CHROME).exists():
        sys.exit(f"找不到 Chrome：{CHROME}\n用 CHROME=/path/to/chrome 指定")
    print(f"矢量      {build_vectors()} 个")
    build_tokens(); print("色板      tokens.json / .css / .ts")
    print(f"Android   {build_android()} 个产物")
    print(f"iOS       {build_ios()} 个产物")
    print(f"Web       {build_web()} 个产物")
    print(f"官网      {build_site()} 个产物（hxmall + hxtech 两套）")
    print(f"启动页    {build_splash()} 个产物")
    print(f"小程序    {build_miniprogram()} 个产物")
    print(f"商标稿    {build_trademark()} 个产物")
    print(f"分发目录  {build_dist()} 个文件 → brand/dist/<端>/")
    print("\n完成。产物均由参数派生，勿手改单个文件 —— 改 brand/build.py 顶部参数后重跑。")
