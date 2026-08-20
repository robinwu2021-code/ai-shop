#!/usr/bin/env python3
"""图标参数调整提案 —— 只出 brand/icon-proposal.html，**不碰任何产物**。

范围**刻意很窄**：规范 `brand/spec.html` §02 参数表里，只动 HX 档的两个数
（字高、弧线宽）。容器四态、三档层级、两个品牌实体、商标稿、禁止事项……
规范里全都有了，本页一律引用，不重复画一遍。

为什么不手画：import build.py 的几何函数出图，图上每一枚方章都是采纳后真会产出的东西。

用法：python3 brand/propose-icons.py && open brand/icon-proposal.html
"""
import pathlib

import build as B  # 同目录：几何真源

ROOT = pathlib.Path(__file__).resolve().parent

# ── 规范 §02 参数表（照抄，不是重新定义）───────────────────────────────
# 与 spec.html 的表逐行对应。共用参数：
BASE = dict(radius=0.275, stroke=0.267, arc_s=0.05, gap=0.04, h_ratio=0.80)

# 分档取值 —— 规范本来就是分档的，同一个数不通用：
#   H  字高 0.64 × 边长，**无弧线**（素材 h-square.svg / h-circle.svg 里就没有弧）
#   HX 字高 0.26 × 边长，弧线宽 0.50 × 边长
#   虹  弧线宽 0.40 × 边长（「单字符较宽，弧线相应收窄」）
SPEC_TIERS = {
    "H":  dict(glyph_h=0.64, arc_w=None),
    "HX": dict(glyph_h=0.26, arc_w=0.50),
    "虹": dict(glyph_h=0.46, arc_w=0.40),   # 汉字取 font-size 比例，与素材 hong-square.svg 一致
}

# ── 已定档（2026-08-20）：候选 A + 扁 0.65
ARC_FLAT = 0.65          # 竖半径 ÷ 横半径。1.00 = 规范原来的正半圆
BAR = 0.38               # H 横画中心高度 ÷ 字高（从顶算起）。旧版 G4 的比例，取回来
HX_TIER = dict(glyph_h=0.30, arc_w=0.44)

# 压扁是**母题级**的改动，不是 HX 一档的事 —— 凡是有弧的地方都跟着扁，
# 否则同一个品牌里会同时存在正半圆和扁圆两种「虹」，那就不是一套体系了。
# 落点三处：HX 方章、虹 方章、虹选字标。
FINAL_TIERS = {
    "H":    dict(glyph_h=0.64, arc_w=None, bar=True,  note="无弧线（素材里就没有）"),
    "HX":   dict(glyph_h=0.30, arc_w=0.44, bar=True,  note="候选 A；里面那个 H 同样高横"),
    "虹":   dict(glyph_h=0.46, arc_w=0.40, bar=False, note="汉字，无横画参数"),
    "虹选": dict(glyph_h=0.38, arc_w=0.38, bar=False,
                 note="双字方章：两字紧排，弧仍只压「虹」"),
    "虹选字标": dict(glyph_h=1.00, arc_w=1.00, bar=False,
                     note="横向字标：弧宽 = 1.00 × 字高，只压在「虹」上"),
}

# ── 第二轮：**把弧压扁**。这是改规范 §02「母题」那一条 ——
# 原文是「半圆弧，圆心角 180°」，压扁之后是扁圆弧（横半径不变、竖半径变小）。
# 所以它必须是一个**具名参数**，不是随手压一下：
#   arc_flat = 竖半径 ÷ 横半径。1.00 就是规范现行的正半圆。
# 横向占位不变（弧宽还是 0.44），省下来的全是纵向 —— 正是「占用空间更小」的意思。
FLATS = [
    ("规范 · 半圆", 1.00, "圆心角 180°"),
    ("扁 0.80", 0.80, "轻微"),
    ("扁 0.65", 0.65, "推荐"),
    ("扁 0.50", 0.50, "接近极限"),
]

# ── build.py 现状：GEO 只有一档，H 与 HX 共用。下面两条是它与规范的差
GAPS = [
    ("H 字高", "0.64 × 边长", "0.26（与 HX 共用 glyph_h）",
     "章内的 H 只有规范的四成大，小尺寸下先糊掉的就是它"),
    ("H 弧线", "无（素材里就没有）", "画了一道 0.50 × 边长的弧",
     "弧比 H 还宽 1.4 倍，读作「一个小 H 躲在雨棚下」"),
    ("虹 弧线宽", "0.40 × 边长", "未实现该档", "中文方章走不了生成器，只能手画"),
]


# ── 新增变量：**H 的横画高度**。
#
# 旧版（虹橙 · G4）把横画提到 38%，build.py 里注释写着「这是这版唯一的造型动作，
# 也是它区别于随便一个 H 的地方」。换代到弧线 + HX 之后这个动作丢了 —— 现在的
# _h_at() 把横画写死在正中（0.50）。本轮把它取回来，做成参数。
#
# ⚠️ 一个跑不掉的取舍：X 是两条角到角的对角线，**交点永远在正中**。
# H 的横一提，两者就不在同一条水平线上了。所以下面每一版都要 H 与 HX 并排看。
BAR_VARIANTS = [
    ("现行 · 居中", 0.50, "换代后的样子，横画在正中"),
    ("高横 0.44", 0.44, "折中：提一点，仍接近 X 的交点"),
    ("高横 0.38", 0.38, "旧版 G4 的比例，造型动作最明显"),
]


def h_path(m, L, R, bar):
    """H：两竖一横，横画高度可调。与 build.py 的 _h_at 同算法，只是 cy 不写死在正中。

    build.py 落地时把 `cy = (T + B) / 2` 改成 `cy = T + (B - T) * bar` 即可 ——
    旧版的 legacy_H() 本来就是这么写的，等于把那一行搬回新几何里。
    """
    T = m["top"] + m["arc_h"] + m["gap"]
    B_ = T + m["h"]
    w = m["w"]
    cy = T + (B_ - T) * bar
    t, b = cy - w / 2, cy + w / 2
    return (f"M{B.f(L)} {B.f(T)}L{B.f(L+w)} {B.f(T)}L{B.f(L+w)} {B.f(t)}L{B.f(R-w)} {B.f(t)}"
            f"L{B.f(R-w)} {B.f(T)}L{B.f(R)} {B.f(T)}L{B.f(R)} {B.f(B_)}L{B.f(R-w)} {B.f(B_)}"
            f"L{B.f(R-w)} {B.f(b)}L{B.f(L+w)} {B.f(b)}L{B.f(L+w)} {B.f(B_)}L{B.f(L)} {B.f(B_)}Z")


def geo_for(glyph_h, arc_w):
    g = dict(BASE)
    g["glyph_h"] = glyph_h
    # 无弧线档：把弧宽压到等于描边宽（半径 0），再由 no_arc 关掉描边
    g["arc_w"] = arc_w if arc_w is not None else BASE["arc_s"]
    return g


def with_geo(geo, fn, *a, **k):
    """临时换 GEO 跑 build.py 的函数，用完必须还原 —— 否则后面的图会拿到上一次的参数。"""
    old = B.GEO
    B.GEO = geo
    try:
        return fn(*a, **k)
    finally:
        B.GEO = old


def hx_metrics(S, flat=1.0, glyph_h=None, arc_w=None):
    """HX 档的度量。与 build.py 的 glyph_metrics 同一套算法，只多一个竖半径。

    `arc_flat` 落到实现上就是把 `A r r` 换成 `A rx ry` —— 一处改动，见 §04。
    """
    g = dict(BASE)
    gh = glyph_h if glyph_h is not None else HX_TIER["glyph_h"]
    aw_r = arc_w if arc_w is not None else HX_TIER["arc_w"]
    h = gh * S
    aw = g["arc_s"] * S
    rx = (aw_r * S - aw) / 2
    ry = flat * rx
    arc_h = ry + aw / 2                       # 弧的可视高度：竖半径 + 半个描边
    total = arc_h + g["gap"] * S + h
    return dict(h=h, w=g["stroke"] * h, gw=g["h_ratio"] * h, aw=aw, rx=rx, ry=ry,
                arc_h=arc_h, gap=g["gap"] * S, total=total, top=(S - total) / 2, cx=S / 2)


def hx_body(S, glyph_h=None, arc_w=None, glyph=None, arc=None, flat=1.0, bar=None):
    m = hx_metrics(S, flat, glyph_h, arc_w)
    glyph = glyph or B.PAPER
    bar = BAR if bar is None else bar
    cy = m["top"] + m["arc_h"]
    d = (f'M{B.f(m["cx"]-m["rx"])} {B.f(cy)}'
         f'A{B.f(m["rx"])} {B.f(m["ry"])} 0 0 1 {B.f(m["cx"]+m["rx"])} {B.f(cy)}')
    # H 用本地的可调横画版，X 仍取 build.py 的（角到角，无横画可调）
    gap_x = m["w"]
    total = m["gw"] * 2 + gap_x
    x0 = m["cx"] - total / 2
    paths = [h_path(m, x0, x0 + m["gw"], bar),
             B._x_at(m, x0 + m["gw"] + gap_x, x0 + total)]
    letters = "".join(f'<path d="{p}" fill="{glyph}"/>' for p in paths)
    return (f'<path d="{d}" fill="none" stroke="{arc or glyph}" '
            f'stroke-width="{B.f(m["aw"])}" stroke-linecap="butt"/>' + letters)


def h_body(S, glyph, bar=None):
    """H 单字符：字高 0.64、无弧线、垂直居中。

    build.py 目前没有这一档 —— 它把 H 塞进 HX 的度量里，于是既矮又被弧线压着。
    这几行就是它需要补的分支：不走 arc，自己算居中。
    """
    bar = BAR if bar is None else bar
    g = geo_for(SPEC_TIERS["H"]["glyph_h"], None)
    h = g["glyph_h"] * S
    m = dict(h=h, w=g["stroke"] * h, gw=g["h_ratio"] * h,
             arc_h=0.0, gap=0.0, top=(S - h) / 2, cx=S / 2, r=0.0, aw=0.0)
    d = h_path(m, m["cx"] - m["gw"] / 2, m["cx"] + m["gw"] / 2, bar)
    return f'<path d="{d}" fill="{glyph}"/>'


def cn_body(S, glyph, arc, char="虹", flat=None):
    """中文单字：弧线宽 0.40 × 边长（规范值），**弧同步压扁**。

    压扁是母题级的，虹档漏掉的话，小程序中文版的弧会是正半圆、
    App 图标的弧是扁圆 —— 同一个品牌两种「虹」，而这种不一致没人会去比对。
    汉字用 <text> 依赖系统字体；正式产物要转轮廓。
    """
    t = FINAL_TIERS["虹"]
    flat = ARC_FLAT if flat is None else flat
    aw = BASE["arc_s"] * S
    rx = (t["arc_w"] * S - aw) / 2
    ry = flat * rx
    cy = 0.30 * S
    return (f'<path d="M{B.f(S/2-rx)} {B.f(cy)}A{B.f(rx)} {B.f(ry)} 0 0 1 {B.f(S/2+rx)} {B.f(cy)}" '
            f'fill="none" stroke="{arc}" stroke-width="{B.f(aw)}"/>'
            f'<text x="{S/2}" y="{B.f(S*0.80)}" text-anchor="middle" '
            f'font-family="Noto Sans SC, Source Han Sans SC, PingFang SC, sans-serif" '
            f'font-weight="700" font-size="{B.f(t["glyph_h"]*S)}" fill="{glyph}">{char}</text>')


def cn2_body(S, glyph, arc, flat=None, span="虹"):
    """**虹选双字方章** —— 两个字紧排进一枚章里，排布照搬 HX 方章：弧 / 间距 / 字，整组居中。

    `span` 决定弧罩住谁，这是两个方案的**唯一**区别：
      "虹"   弧只压「虹」—— 规范 §04 的规则（弧是「虹」的字义符号，不是整词的装饰）
      "虹选" 弧横跨两个字 —— 与 HX 方章同构（弧宽取字组总宽的 0.70，
             和 HX 档 0.44÷0.633 是同一个比例），代价是弧不再特指「虹」

    汉字的 em 框比字形本身高（下方是用不到的降部），所以垂直居中要按**字形墨迹高**
    算，取 0.86 em；直接拿 em 框居中会整体偏上。
    """
    t = FINAL_TIERS["虹选"]
    flat = ARC_FLAT if flat is None else flat
    ch = t["glyph_h"] * S                 # 单字字号
    track = 0.02 * S                      # 紧排字距：正常 0.10em 排下来两个字会各自缩到看不清
    total_w = 2 * ch + track
    x0 = (S - total_w) / 2

    aw = BASE["arc_s"] * S
    if span == "虹选":
        arc_out = 0.70 * total_w          # 与 HX 档同比例
        cx = S / 2
    else:
        arc_out = 1.00 * ch               # 弧宽 = 单字宽，只罩「虹」
        cx = x0 + ch / 2
    rx = (arc_out - aw) / 2
    ry = flat * rx

    ink = 0.86 * ch                       # 汉字墨迹高（em 框的可见部分）
    arc_h = ry + aw / 2
    gap = BASE["gap"] * S
    top = (S - (arc_h + gap + ink)) / 2
    cy = top + arc_h
    baseline = top + arc_h + gap + ink

    return (f'<path d="M{B.f(cx-rx)} {B.f(cy)}A{B.f(rx)} {B.f(ry)} 0 0 1 {B.f(cx+rx)} {B.f(cy)}" '
            f'fill="none" stroke="{arc}" stroke-width="{B.f(aw)}"/>'
            f'<text x="{B.f(x0)}" y="{B.f(baseline)}" '
            f'font-family="Noto Sans SC, Source Han Sans SC, PingFang SC, sans-serif" '
            f'font-weight="700" font-size="{B.f(ch)}" letter-spacing="{B.f(track)}" '
            f'fill="{glyph}">虹选</text>')


def wordmark_svg(text, en, H=64, ink=None, arc=None, flat=None):
    """中文字标：弧宽 = 1.00 × 字高，左端与「虹」左边缘对齐，**只覆盖「虹」这一个字**。

    加「好物」之后弧不变宽 —— 它是「虹」的字义符号，不是整个词的装饰（规范 §04）。
    """
    flat = ARC_FLAT if flat is None else flat
    ink = ink or B.INK
    arc = arc or B.RED
    aw = 0.085 * H                      # 字标档的描边比章内略粗，与规范素材一致
    rx = (H - aw) / 2                   # 弧宽 = 1.00 × 字高
    ry = flat * rx
    pad = 0.02 * H
    arc_top = 0.0
    cy = arc_top + ry + aw / 2
    base = cy + 0.10 * H + H            # 汉字基线
    w = H * (len(text) * 1.10 + 1.2)
    return (f'<svg viewBox="0 0 {B.f(w)} {B.f(base + 0.34*H)}" width="{B.f(w)}" '
            f'height="{B.f(base + 0.34*H)}">'
            f'<path d="M{B.f(pad)} {B.f(cy)}A{B.f(rx)} {B.f(ry)} 0 0 1 {B.f(pad+2*rx)} {B.f(cy)}" '
            f'fill="none" stroke="{arc}" stroke-width="{B.f(aw)}"/>'
            f'<text x="0" y="{B.f(base)}" font-family="Noto Sans SC, Source Han Sans SC, '
            f'PingFang SC, sans-serif" font-weight="700" font-size="{B.f(H)}" '
            f'letter-spacing="{B.f(0.10*H)}" fill="{ink}">{text}</text>'
            f'<text x="{B.f(pad)}" y="{B.f(base + 0.30*H)}" font-family="Figtree, sans-serif" '
            f'font-weight="600" font-size="{B.f(0.26*H)}" letter-spacing="{B.f(0.30*0.26*H)}" '
            f'fill="{MUTED}">{en}</text></svg>')


def plate(S, body, bg, radius=None):
    if radius == "circle":
        shape = f'<circle cx="{S/2}" cy="{S/2}" r="{S/2}" fill="{bg}"/>'
    else:
        shape = (f'<rect width="{S}" height="{S}" '
                 f'rx="{B.f((radius if radius is not None else BASE["radius"])*S)}" fill="{bg}"/>')
    return f'<svg viewBox="0 0 {S} {S}" width="{S}" height="{S}">{shape}{body}</svg>'


def hx_plate(S, flat=1.0, bg=None, glyph=None, arc=None, radius=None,
             glyph_h=None, arc_w=None, bar=None):
    return plate(S, hx_body(S, glyph_h, arc_w, glyph or B.PAPER, arc, flat, bar),
                 bg or B.RED, radius)


def metrics(flat=1.0, glyph_h=None, arc_w=None):
    m = hx_metrics(1.0, flat, glyph_h, arc_w)   # 边长归一化为 1 → 直接读比例
    return dict(arc_h=m["arc_h"], total=m["total"], hx_w=m["gw"] * 2 + m["w"],
                ratio=m["h"] / m["arc_h"], open=(m["ry"] - m["aw"] / 2) / m["aw"])



# ─────────────────────────────────────────── 决策汇总 + 场景矩阵
REPO = ROOT.parent

# 本轮定下的全部参数，一处集中。每条都能追到「为什么」。
DECISIONS = [
    ("HX 字高", "0.30 × 边长", "候选 A。原 0.26 时字只比弧高一点点，远看是「一顶帽子下面有点东西」"),
    ("HX 弧线宽", "0.44 × 边长", "候选 A，比原 0.50 收窄"),
    ("arc_flat", f"{ARC_FLAT}", "竖半径 ÷ 横半径。横向不变、纵向省 31%；再扁到 0.50 就读成一条横线"),
    ("H 横画高度", f"{BAR}", "旧版 G4 的比例取回来。单独 H 与 HX 里的 H 用同一个值 —— 两处 H 不同就是两套字形"),
    ("H 字高", "0.64 × 边长", "规范 §02 原有值，build.py 一直没实现"),
    ("H 弧线", "无", "素材 h-square.svg / h-circle.svg 里本来就没有弧"),
    ("虹 弧线宽", "0.40 × 边长", "规范 §02 原有值，弧同步压扁"),
    ("虹选 双字方章", "字高 0.38、字距 0.02", "排布照搬 HX：弧 / 间距 / 字，整组按墨迹高居中"),
    ("虹选字标 弧宽", "1.00 × 字高", "只压「虹」，加「好物」后不变宽"),
]

# 两个仍未定的，摆在矩阵前面 —— 它们决定下面好几行的取值
PENDING = [
    ("B 端图标底色", "红底白 HX（现状） / 深板岩 + 亮红弧（规范 §4.5）",
     "两端都用红，桌面上只能靠名字区分；商家手机上两个 App 都装是常态"),
    ("虹选双字方章的弧", "甲：只压「虹」 / 乙：横跨两字（HX 布局）",
     "选乙要同时改写规范 §04 —— 否则方章与横向字标两种规则并存"),
]

# 场景矩阵：按端组织。每行 = 场景 / 档 / 容器与配色 / 尺寸 / 落点 / 备注
SCENARIOS = [
    ("C 端 · 虹选好物（消费者）", "红底 + 白 HX + 白弧", [
        ("App 图标 · iOS", "HX", "满幅方章", "1024²", "brand/ios/AppIcon-c.appiconset/icon-1024.png", "不得带 alpha"),
        ("App 图标 · Android 前景", "HX", "矢量前景", "108dp", "android-shell/app/src/consumer/res/drawable/ic_launcher_foreground.xml", "内容缩到安全圆 72"),
        ("App 图标 · Android 背景", "—", "纯色红", "—", "android-shell/app/src/consumer/res/values/ic_launcher_background.xml", "—"),
        ("App 图标 · 位图兜底", "HX", "满幅方章", "48–192 五档", "android-shell/app/src/consumer/res/mipmap-xxxhdpi/ic_launcher.png", "方形 + 圆形各一套"),
        ("Play 商店图", "HX", "满幅方章", "512²", "brand/store/play-c-512.png", "—"),
        ("启动页 · Android 12+", "HX", "居中 + 红底", "288dp 图层", "android-shell/app/src/main/res/values-v31/themes.xml", "缺则冷启动白屏"),
        ("启动页 · Android 旧版", "HX", "居中 + 红底", "—", "android-shell/app/src/main/res/drawable/splash.xml", "layer-list"),
        ("启动页 · iOS", "HX", "居中 + 红底", "—", "（Capacitor 工程内）", "storyboard"),
        ("通知栏图标", "H", "单色白、无弧", "76 / 48", "android-shell/app/src/main/res/drawable/ic_notification.xml", "16px 要能辨"),
        ("H5 favicon", "H", "圆章", "svg + 32", "c-app/public/favicon.svg", "—"),
        ("H5 触屏图标", "HX", "满幅方章", "180 / 192 / 512", "c-app/public/apple-touch-icon.png", "—"),
        ("小程序 · 方形", "HX", "满幅方章", "144²", "brand/store/mp-c-hx-144.png", "微信端自动裁圆"),
        ("小程序 · 中文版", "虹选", "双字方章", "144²", "brand/store/mp-c-cn-144.png", "甲/乙待定"),
    ]),
    ("B 端 · 虹选商家", "底色待定：红 or 深板岩 + 亮红弧", [
        ("App 图标 · iOS", "HX", "满幅方章", "1024²", "brand/ios/AppIcon-b.appiconset/icon-1024.png", "不得带 alpha"),
        ("App 图标 · Android 前景", "HX", "矢量前景", "108dp", "android-shell/app/src/merchant/res/drawable/ic_launcher_foreground.xml", "—"),
        ("App 图标 · Android 背景", "—", "纯色（待定）", "—", "android-shell/app/src/merchant/res/values/ic_launcher_background.xml", "见上方待定项 1"),
        ("App 图标 · 位图兜底", "HX", "满幅方章", "48–192 五档", "android-shell/app/src/merchant/res/mipmap-xxxhdpi/ic_launcher.png", "—"),
        ("Play 商店图", "HX", "满幅方章", "512²", "brand/store/play-b-512.png", "—"),
        ("启动页 · Android 12+", "HX", "居中 + 底色", "288dp 图层", "android-shell/app/src/main/res/values-v31/themes.xml", "与 C 端共用 main，需按 flavor 覆盖"),
        ("通知栏图标", "H", "单色白、无弧", "76 / 48", "android-shell/app/src/main/res/drawable/ic_notification.xml", "两端共用"),
        ("H5 favicon", "H", "圆章", "svg + 32", "b-app/public/favicon.svg", "—"),
        ("H5 触屏图标", "HX", "满幅方章", "180 / 192 / 512", "b-app/public/apple-touch-icon.png", "—"),
        ("小程序 · 方形", "HX", "满幅方章", "144²", "brand/store/mp-b-hx-144.png", "—"),
    ]),
    ("运营端 · ops-web", "沿用 B 端那副冷静的脸", [
        ("favicon", "H", "圆章", "svg + 32", "ops-web/public/favicon.svg", "内部工具，不进商店"),
        ("触屏图标", "HX", "满幅方章", "180 / 192 / 512", "ops-web/public/apple-touch-icon.png", "—"),
        ("左上角标识", "HX + 字标", "方章 + 虹选 · 好物", "组件", "ops-web/components/", "品牌红只出现在这里、登录页与页脚"),
    ]),
    ("Web 官网 · hxmall.top", "白底 + 红方章", [
        ("页眉标识", "HX + 字标", "方章 + 虹选 · 好物", "组件", "site/components/brand/logo.tsx", "已按新几何实现"),
        ("favicon", "H", "圆章", "svg + 32", "site/public/favicon.svg", "官网新建"),
        ("触屏图标", "HX", "满幅方章", "180 / 192 / 512", "site/public/apple-touch-icon.png", "—"),
        ("favicon.ico", "H", "圆章", "16 + 32 + 48", "site/public/favicon.ico", "老浏览器与聚合器只认 ico"),
        ("webmanifest", "—", "—", "—", "site/public/site.webmanifest", "安卓「添加到桌面」"),
        ("微信分享缩略图", "HX", "满幅方章", "300²", "site/public/share-300.png", "微信只取正方形"),
        ("OG 卡片", "字标", "横版 + 白底", "1200×630", "site/public/og.png", "被分享时的大图"),
    ]),
    ("母品牌与物料 · hxtech.top", "墨底 + 亮红弧", [
        ("虹选科技方章", "HX", "墨底 + 亮红弧", "矢量", "brand/logo/icon-tech.svg", "合同、资质、对公署名"),
        ("主色字标", "HX", "透明底", "矢量", "brand/logo/mark-red.svg", "—"),
        ("墨色字标", "HX", "透明底", "矢量", "brand/logo/mark-ink.svg", "单色稿"),
        ("反白字标", "HX", "透明底", "矢量", "brand/logo/mark-reverse.svg", "深底用"),
        ("旧版橙字标", "旧 H", "透明底", "矢量", "brand/logo/mark-orange.svg", "<b>换代后应删</b>"),
        ("虹选", "虹选字标", "横向字标", "矢量", "brand/logo/wordmark-hongxuan.svg", "hxmall 素材里有，未入库"),
        ("虹选 · 好物", "虹选字标", "横向字标", "矢量", "brand/logo/wordmark-hongxuan-haowu.svg", "同上"),
        ("虹选科技", "虹选字标", "横向字标", "矢量", "brand/logo/wordmark-hongxuan-tech.svg", "母品牌"),
        ("域名字标", "—", "文字", "矢量", "brand/logo/domain-hxmall.svg", "hxmall.top / hxtech.top"),
        ("商标申报稿", "H / HX / 虹选", "黑白 + 反白", "JPG ≥400px", "brand/trademark/", "文字须转轮廓"),
    ]),
]


def scan(rel):
    """去文件系统上查，别让清单和实际脱节。目录只要非空就算有。"""
    p = REPO / rel
    if p.is_dir():
        return "有" if any(p.iterdir()) else "缺"
    return "有" if p.exists() else "缺"


# ─────────────────────────────────────────── 页面
RED, INK, PAPER, BRIGHT = B.RED, B.INK, B.PAPER, B.RED_BRIGHT
MUTED, LINE, PANEL = "#63676E", "#E5E7EA", "#F5F6F8"

CSS = f"""
:root{{--red:{RED};--ink:{INK};--muted:{MUTED};--line:{LINE};--panel:{PANEL};--bright:{BRIGHT};
--sans:"Figtree","PingFang SC","Noto Sans SC",system-ui,sans-serif}}
*,*::before,*::after{{box-sizing:border-box}}
body{{margin:0;background:#fff;color:var(--ink);font-family:var(--sans);font-size:16px;line-height:1.65;-webkit-font-smoothing:antialiased}}
.wrap{{max-width:1000px;margin:0 auto;padding:0 clamp(20px,5vw,48px)}}
h1,h2{{margin:0;line-height:1.2;font-weight:700;letter-spacing:-.01em}}
p{{margin:0}}
code{{font-family:ui-monospace,Menlo,monospace;font-size:.9em;background:var(--panel);padding:1px 6px;border-radius:5px}}
a{{color:var(--red)}}
header{{background:var(--red);color:#fff;padding:clamp(36px,6vw,60px) 0 clamp(30px,5vw,48px)}}
header .k{{font-size:12px;letter-spacing:.22em;text-transform:uppercase;opacity:.82}}
header h1{{font-size:clamp(26px,4vw,42px);margin-top:12px}}
header p{{margin-top:14px;max-width:62ch;color:rgba(255,255,255,.88)}}
header code{{background:rgba(255,255,255,.18);color:#fff}}
section{{padding:clamp(36px,5vw,60px) 0;border-bottom:1px solid var(--line)}}
.k2{{font-size:11.5px;letter-spacing:.16em;text-transform:uppercase;color:var(--muted);margin-bottom:10px}}
h2{{font-size:clamp(20px,2.6vw,28px)}}
.lede{{color:var(--muted);max-width:66ch;margin-top:12px}}
.grid{{display:grid;gap:18px;margin-top:26px}}
.g3{{grid-template-columns:repeat(3,1fr)}}
@media(max-width:760px){{.g3{{grid-template-columns:1fr}}}}
.stage{{border:1px solid var(--line);border-radius:14px;background:var(--panel);display:grid;place-items:center;padding:24px;min-height:160px}}
.cap{{font-size:12px;color:var(--muted);text-align:center;margin-top:9px;line-height:1.5}}
.cap b{{color:var(--ink)}}
table{{width:100%;border-collapse:collapse;font-size:14px;background:#fff;border:1px solid var(--line);margin-top:22px}}
th{{text-align:left;font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:var(--muted);background:var(--panel);padding:10px 12px;border-bottom:1px solid var(--line);font-weight:600}}
td{{padding:10px 12px;border-bottom:1px solid var(--line);vertical-align:top}}
tbody tr:last-child td{{border-bottom:0}}
td.n{{font-variant-numeric:tabular-nums;text-align:right;white-space:nowrap}}
.chg{{color:var(--red);font-weight:700}}
.scroll{{overflow-x:auto}}
/* 矩阵表：落点那一列是长路径，不给最小宽度的话它会把「容器」挤成一个字一行。
   宽度不够时让整张表横向滚动，而不是把每一列都压扁。 */
table.matrix{{min-width:940px;table-layout:fixed}}
table.matrix td:nth-child(1),table.matrix td:nth-child(3){{white-space:nowrap}}
table.matrix col.c-use{{width:13%}}table.matrix col.c-tier{{width:6%}}
table.matrix col.c-box{{width:10%}}table.matrix col.c-size{{width:12%}}
table.matrix col.c-path{{width:34%}}table.matrix col.c-st{{width:6%}}
table.matrix col.c-note{{width:19%}}
table.matrix code{{word-break:break-all;font-size:12px;line-height:1.5}}
.row{{display:flex;align-items:flex-end;gap:24px;flex-wrap:wrap}}
.note{{background:var(--panel);border-left:3px solid var(--red);padding:14px 18px;margin-top:22px;font-size:14.5px}}
pre{{background:var(--panel);border-radius:12px;padding:16px 18px;overflow-x:auto;font-size:13.5px;margin-top:20px}}
pre code{{background:none;padding:0}}
footer{{padding:32px 0 52px;color:var(--muted);font-size:13px}}
"""


def stage(inner, cap):
    return f'<div><div class="stage">{inner}</div><div class="cap">{cap}</div></div>'


def build_page():
    P = [f"<style>{CSS}</style>"]
    F = ARC_FLAT

    P.append(f"""<header><div class="wrap">
      <div class="k">Icon system · 完整方案</div>
      <h1>图标体系：几何定档 + 需求矩阵</h1>
      <p>几何已定：HX 字高 <b>0.30</b>、弧线宽 <b>0.44</b>、<b>arc_flat {F}</b>、
      H 横画 <b>{BAR}</b>（旧版 G4 的高横，取回来）。
      压扁是母题级改动，<b>凡是有弧的地方都跟着扁</b> —— HX 方章、虹 方章、虹选字标三处，
      否则同一个品牌里会同时存在正半圆和扁圆两种「虹」。
      容器四态、禁止事项、色值见 <code style="background:rgba(255,255,255,.18);color:#fff">brand/spec.html</code>，本页不重复。</p>
    </div></header>""")

    # ── §1 定档后的四档
    tiers = "".join(
        f'<tr><td><b>{k}</b></td><td class="n">{v["glyph_h"]}</td>'
        f'<td class="n">{"—" if v["arc_w"] is None else v["arc_w"]}</td>'
        f'<td class="n">{"—" if v["arc_w"] is None else F}</td>'
        f'<td class="n">{BAR if v["bar"] else "—"}</td><td>{v["note"]}</td></tr>'
        for k, v in FINAL_TIERS.items())
    P.append(f"""<section><div class="wrap">
      <div class="k2">01 — Geometry locked</div>
      <h2>四档，一个弧形</h2>
      <p class="lede">规范 §02 本来就是分档的（H / HX / 虹 各给各的值），本轮只是把定下的
      字高、弧宽与扁平度填进去。共用参数不动：圆角 0.275、笔画 0.267、弧描边 0.05、
      字弧间距 0.04、H 宽高比 0.80、安全区 0.25。</p>
      <div class="scroll"><table>
        <thead><tr><th>档</th><th>字高 /边长</th><th>弧线宽 /边长</th><th>arc_flat</th><th>横画高度</th><th>说明</th></tr></thead>
        <tbody>{tiers}</tbody></table></div>
      <div class="grid g3">
        {stage(plate(112, h_body(112, PAPER), RED), "<b>H</b> · 字高 0.64、无弧线<br>头像 / favicon / 通知栏")}
        {stage(hx_plate(112, flat=F), "<b>HX</b> · 0.30 / 0.44 / 扁 " + str(F) + "<br>App 图标、站点主标")}
        {stage(plate(112, cn_body(112, PAPER, PAPER), RED), "<b>虹</b> · 单字方章<br>弧宽 0.40、同步压扁")}
      </div>
      <div class="grid g3" style="margin-top:18px">
        {stage(plate(112, cn2_body(112, PAPER, PAPER, span="虹"), RED),
               "<b>虹选 · 甲</b> · 弧只压「虹」<br>守规范 §04，弧仍是「虹」的字义符号")}
        {stage(plate(112, cn2_body(112, PAPER, PAPER, span="虹选"), RED),
               "<b>虹选 · 乙</b> · 弧横跨两字<br>与 HX 方章同构，弧宽 = 0.70 × 字组宽")}
        {stage(hx_plate(112, flat=F), "HX · 对照<br>弧宽 = 0.70 × 字组宽")}
      </div>
      <div class="grid g3" style="margin-top:18px">
        {stage(plate(112, cn2_body(112, PAPER, PAPER, span="虹选"), RED, radius="circle"),
               "乙 · 圆章<br>小程序端会自动裁圆")}
        {stage(plate(112, cn2_body(112, RED, RED, span="虹选"), PAPER),
               "乙 · 透明底红字<br>包装、单据、门头")}
        {stage(plate(112, cn2_body(112, PAPER, BRIGHT, span="虹选"), INK),
               "乙 · 墨底 + 亮红弧<br>母品牌物料")}
      </div>
      <div class="grid g3" style="margin-top:18px">
        {stage(wordmark_svg("虹选", "HX MALL", 48), "<b>虹选</b> · 核心字标")}
        {stage(wordmark_svg("虹选 · 好物", "HX MALL", 44), "<b>虹选 · 好物</b> · 加词后弧不变宽")}
        {stage(wordmark_svg("虹选科技", "HX TECH", 44), "<b>虹选科技</b> · 母品牌")}
      </div>
      <div class="note"><b>字标那道弧只压在「虹」上。</b>弧宽 = 1.00 × 字高，
      左端与「虹」左边缘对齐；加「好物」之后<b>不变宽</b> —— 它是「虹」的字义符号，
      不是整个词的装饰（规范 §04）。这条最容易被排版的人改错。</div>
    </div></section>""")

    # ── §2 H 高横（新增方案）
    cells = ""
    for n, bar, d in BAR_VARIANTS:
        pair = (f'<div style="display:flex;gap:14px;align-items:center">'
                f'{plate(96, h_body(96, PAPER, bar), RED)}'
                f'{hx_plate(96, flat=F, bar=bar)}</div>')
        tag = " ✓ 已定" if bar == BAR else ""
        cells += stage(pair, f"<b>{n}{tag}</b> · 横高 {bar}<br>{d}")
    P.append(f"""<section><div class="wrap">
      <div class="k2">02 — H bar</div>
      <h2>已定：H 横画提到 0.38</h2>
      <p class="lede">旧版（虹橙 · G4）把横画提到 <b>38%</b>，<code>build.py</code> 里注释写着
      「这是这版唯一的造型动作，也是它区别于随便一个 H 的地方」。换代到弧线 + HX 之后
      这个动作丢了 —— 现在 <code>_h_at()</code> 把横画写死在正中。本节把它取回来，做成参数
      <code>bar</code>（横画中心高度 ÷ 字高，从顶算起），并<b>定在旧版的 0.38</b>。
      <b>单独的 H 与 HX 里的那个 H 用同一个值</b> —— 规范 §02 的字形规则是「H、X 同源」，
      两处 H 长得不一样就等于有两套字形。下面三版留作对照。</p>
      <div class="grid g3">{cells}</div>
      <div class="note"><b>一个跑不掉的取舍：X 的交点永远在正中。</b>
      X 是两条角到角的对角线，交点位置是几何决定的，提不上去。H 的横一提，
      H 的横与 X 的交点就不在同一条水平线上 —— 上面每一格都是「单独 H + HX」并排，
      就是为了让这条错位看得见。<br><br>
      要不要为此把 X 也改成上小下大的不对称形，是另一个决定；
      规范 §02 现在写的是「X 按 H 的参数反推：同笔画宽、同字高、同切口角度」，
      没说交点位置，所以保持角到角<b>不算违反规范</b>。</div>
    </div></section>""")

    # ── §3 两端
    P.append(f"""<section><div class="wrap">
      <div class="k2">03 — Two apps</div>
      <h2>C 端与 B 端</h2>
      <p class="lede">同一套几何，靠底色区分。下面左二是规范 §4.5 的原方案（B 端深板岩 + 亮红弧），
      右一是 build.py 里 08-19 覆盖后的现状（B 端也用红）。<b>这一项仍待你定</b>，
      与本轮几何调整无关。</p>
      <div class="grid g3">
        {stage(hx_plate(112, flat=F), "<b>C 端</b> · 红底白 HX<br>消费端，暖")}
        {stage(hx_plate(112, flat=F, bg=B.PLATE_B, arc=BRIGHT),
               "<b>B 端 · 方案</b> · 深板岩 + 亮红弧<br>桌面上一眼分得开")}
        {stage(hx_plate(112, flat=F), "<b>B 端 · 现状</b> · 也是红底<br>与 C 端仅靠桌面名区分")}
      </div>
      <div class="grid g3" style="margin-top:18px">
        {stage(plate(112, hx_body(112, glyph=PAPER, arc=BRIGHT, flat=F), INK),
               "母品牌 HX TECH · 墨底 + 亮红弧<br>合同、资质、对公署名")}
        {stage(plate(112, hx_body(112, glyph=RED, arc=RED, flat=F), PAPER),
               "反白 · 深底物料用")}
        {stage(plate(112, hx_body(112, glyph="#000", arc="#000", flat=F), PAPER),
               "商标申报黑白稿 · 文字须转轮廓")}
      </div>
    </div></section>""")

    # ── §4 场景矩阵（本页重点）
    dec = "".join(f'<tr><td>{a}</td><td class="n"><b>{b}</b></td><td>{c}</td></tr>'
                  for a, b, c in DECISIONS)
    pend = "".join(f'<tr><td class="chg">{a}</td><td>{b}</td><td>{c}</td></tr>'
                   for a, b, c in PENDING)
    blocks = ""
    tot = have = 0
    for grp, tone, rows in SCENARIOS:
        trs = ""
        g_have = 0
        for name, tier, cont, size, path, note in rows:
            st = scan(path)
            tot += 1
            g_have += st == "有"
            cls = "" if st == "有" else ' class="chg"'
            trs += (f'<tr><td>{name}</td><td class="n">{tier}</td><td>{cont}</td>'
                    f'<td class="n">{size}</td><td><code>{path}</code></td>'
                    f'<td{cls}>{st}</td><td>{note}</td></tr>')
        have += g_have
        blocks += (f'<h3 style="margin-top:32px;font-size:16px">{grp} '
                   f'<span style="color:var(--muted);font-weight:400">· {g_have}/{len(rows)}</span></h3>'
                   f'<p class="cap" style="text-align:left;margin:4px 0 0">配色：{tone}</p>'
                   f'<div class="scroll"><table class="matrix">'
                   f'<colgroup><col class="c-use"><col class="c-tier"><col class="c-box">'
                   f'<col class="c-size"><col class="c-path"><col class="c-st"><col class="c-note"></colgroup>'
                   f'<thead><tr><th>场景</th><th>档</th><th>容器 / 配色</th>'
                   f'<th>尺寸</th><th>落点</th><th>现状</th><th>备注</th></tr></thead>'
                   f'<tbody>{trs}</tbody></table></div>')
    P.append(f"""<section><div class="wrap">
      <div class="k2">04 — Matrix</div>
      <h2>对照矩阵：{tot} 项，现有 {have}，缺 {tot - have}</h2>

      <h3 style="margin-top:26px;font-size:16px">本轮定下的参数</h3>
      <div class="scroll"><table>
        <thead><tr><th>参数</th><th>取值</th><th>依据</th></tr></thead>
        <tbody>{dec}</tbody></table></div>

      <h3 style="margin-top:30px;font-size:16px">仍未定的两项 —— 它们决定下面若干行的取值</h3>
      <div class="scroll"><table>
        <thead><tr><th>待定</th><th>选项</th><th>关键取舍</th></tr></thead>
        <tbody>{pend}</tbody></table></div>

      <p class="lede" style="margin-top:30px">下面按端组织。「现状」列不是手写的 ——
      每一行都去文件系统上查过，清单和实际脱节是这类表格最常见的死法。</p>
      {blocks}
      <div class="note"><b>缺的那些里，优先级最高的是启动页。</b>
      现在 <code>android-shell</code> 的 theme 还是 <code>Theme.AppCompat.NoActionBar</code>，
      没有任何 splash 资源 —— 冷启动就是白屏，这是用户第一眼看到的东西。<br><br>
      字标那四个 SVG（虹选 / 虹选 · 好物 / 虹选科技 / 域名）<b>不用重画</b>，
      hxmall 素材里已经有了，转轮廓后入库即可。</div>
    </div></section>""")

    # ── §5 采纳步骤
    P.append(f"""<section><div class="wrap">
      <div class="k2">05 — How to adopt</div>
      <h2>落地顺序</h2>
      <p class="lede">顺序不能反：先让实现追上规范的分档，再落数值，再同步规范文本，最后重跑。
      反过来做的话新字高只对 HX 生效，H 档还是又矮又带弧，产物「改了一半」很难查。</p>
      <pre><code>1. build.py：GEO 拆分档
   TIERS = {{
       "H":  dict(glyph_h=0.64, arc_w=None),   # 无弧线，垂直居中
       "HX": dict(glyph_h=0.30, arc_w=0.44),
       "虹": dict(glyph_h=0.46, arc_w=0.40),
   }}
   glyph_body() 按 letters 取档；H 档跳过弧线
   _h_at()：cy = (T+B)/2 → cy = T + (B-T) * 0.38  # 横画高度，见 §02
            旧版 legacy_H() 本来就是这么写的（BAR_RATIO=0.38），等于把那一行搬回新几何

2. arc_path()：A r r → A rx ry，ry = arc_flat × rx
   arc_h：r + aw/2 → ry + aw/2      # 管纵向占位，漏了整组居中就偏
   BASE 增加 arc_flat = {F}、bar = {BAR}

3. 补 §03 里缺的产出：启动页 4 项、小程序 3 项、通知图标 1 项、
   site/ favicon 一套、ico / webmanifest / 分享图 / OG、字标 SVG 入库

4. 同步 brand/spec.html §02 参数表与「母题」那一条
   「半圆弧，圆心角 180°」→ 扁圆弧 + arc_flat；它是真源，不同步就又出现两套值

5. python3 brand/build.py</code></pre>
      <div class="note"><b>本页仍未改任何产物。</b>C 端 / B 端的底色区分（§02）是独立待定项 ——
      定了它再一起重跑，避免图标连改两轮。规范全文见 <a href="spec.html">brand/spec.html</a>。</div>
    </div></section>""")

    P.append("""<footer><div class="wrap">
      图标体系方案 · 由 brand/propose-icons.py 生成，勿手改 · 规范真源 brand/spec.html
    </div></footer>""")

    return ('<!doctype html>\n<html lang="zh-CN"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>图标体系方案 · 虹选</title></head><body>'
            + "".join(P) + "</body></html>\n")


if __name__ == "__main__":
    out = ROOT / "icon-proposal.html"
    out.write_text(build_page(), encoding="utf-8")
    print(f"方案 → {out}")
    print(f"  几何：HX 字高 {HX_TIER['glyph_h']} · 弧宽 {HX_TIER['arc_w']} · arc_flat {ARC_FLAT}")
    tot = have = 0
    for grp, tone, rows in SCENARIOS:
        g = sum(scan(r[4]) == "有" for r in rows)
        tot += len(rows); have += g
        print(f"  {grp:<26} {g}/{len(rows)}")
    print(f"  ── 合计 {tot} 项，缺 {tot - have}")
