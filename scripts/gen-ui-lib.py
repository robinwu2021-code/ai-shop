#!/usr/bin/env python3
"""把 UI 标准库扫成一份清单（JSON）+ 一页可发布的原型（HTML）。

用法：
  python3 scripts/gen-ui-lib.py            重新生成
  python3 scripts/gen-ui-lib.py --check    只校验（清单与真源不一致就退出 1）

**为什么是生成的，不是手画的。**
《B端UI规范-画原型的十二条》§四 记着一次真实返工：原型自造了一套样式
（6px/8px/22px 的圆角、到处 1px 描边、9.5px 的字），十二条里踩了九条。
根因不是画的人不认真 —— 是**原型与规范之间没有机械联系**，
全靠人一条条比对，而人会漏。

所以这份原型里的每一个色块、每一个圆角、每一条声明，
都是从下面这些真源里**读出来再渲染**的，不存在「照着规范画」这一步：

  packages/shared/src/design/tokens.ts   圆角五档 / 间距五档 / 皮肤色板
  packages/shared/src/design/icons.ts    图标（内联 SVG）
  packages/ui/src/styles/base.css        皮肤变量 + 全部公共积木的真实声明
  b-app/src/App.vue                      B 端密度覆盖 + .field
  packages/ui/src/components/*.vue       跨端组件（props + scoped 样式）
  b-app/src/components/**/*.vue          B 端组件
  b-app|c-app/src/**/*.vue               用量统计

**唯一手写的部分**是每个条目的「什么时候用」与组件的样例标记（见 USAGE_NOTES
与 SAMPLES）—— 那两样表达的是意图，代码里读不出来。样式一律不手写：
样例只写结构与类名，长什么样由真源的 CSS 决定。

渲染口径：页面按 375pt 排，**1rpx = 0.5px**，所以本脚本把真源里的 rpx
一律折半成 px 后注入。这与 base.css 里 `@media (min-width: 601px)` 那段
「>600px 固定按 375 渲染」是同一个数。
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT_JSON = ROOT / "docs/technical/design/ui-lib.json"
OUT_HTML = ROOT / "docs/technical/design/ui-lib.page.html"

BASE_CSS = ROOT / "packages/ui/src/styles/base.css"
TOKENS_TS = ROOT / "packages/shared/src/design/tokens.ts"
ICONS_TS = ROOT / "packages/shared/src/design/icons.ts"
B_APP_VUE = ROOT / "b-app/src/App.vue"
UI_COMPONENTS = ROOT / "packages/ui/src/components"
B_COMPONENTS = ROOT / "b-app/src/components"

# 原型渲染用的皮肤：brand（虹选红）= B 端默认。深浅两档都画。
PROTO_SKIN = "brand"


# ══════════════════════════════════════════════════════════════════════
# 手写的那一部分：意图。代码里读不出「什么时候用它、什么时候不要用」
# ══════════════════════════════════════════════════════════════════════

# 积木 → (分组, 一句话用途, 什么时候**不要**用)
BLOCK_NOTES = {
    ".sh-card": ("容器", "一条内容一张卡：列表行、表单分区、统计块", "块内还要分小节时用 .sh-block"),
    ".sh-block": ("容器", "标题与内容同属一个白块 —— 灰缝只剩块与块之间那一道", "只有内容没有标题时用 .sh-card"),
    ".sh-block__head": ("容器", "块内标题行（横向留白 26rpx，列表行仍通铺到边）", "—"),
    ".sh-chip": ("标签", "状态、分类、筛选项。tint 色块，不描边", "可点的主操作用 .sh-btn"),
    ".sh-chip--primary": ("标签", "选中态 / 与主色相关的状态", "—"),
    ".sh-chip--warning": ("标签", "要留意但还不算错（待审、将过期）", "—"),
    ".sh-chip--danger": ("标签", "已经出错或被拒", "危险**操作**用 .sh-btn--danger"),
    ".sh-link": ("按钮", "文字动作：列表行尾的「改名 / 停用」、卡里的「展开 / 去管理」", "它不是按钮 —— 要底色就用 .sh-btn--soft"),
    ".sh-link--quiet": ("按钮", "压成灰：「收起」这类不该抢眼的", "—"),
    ".sh-link--warn": ("按钮", "要当心的文字动作（申诉、撤回）", "真危险的操作用 .sh-btn--danger"),
    ".sh-btn": ("按钮", "一屏一个主操作，实心胶囊", "并排两个主按钮时次要那个降成 --soft"),
    ".sh-btn--sm": ("按钮", "小一号：**放在一行文字动作里当主操作**（stores 的「切到这家」）", "整屏的主操作用默认尺寸"),
    ".sh-btn--soft": ("按钮", "次要操作：tint 底 + 主色字", "—"),
    ".sh-btn--muted": ("按钮", "不强调的操作（取消、稍后）", "—"),
    ".sh-btn--danger": ("按钮", "危险操作：**描边 + 墨字**，靠形态与主按钮区分", "红实心留给二次确认"),
    ".sh-btn--danger-solid": ("按钮", "二次确认弹层里的最终一击", "页面上不要用 —— 会与主按钮撞色"),
    ".txt-hero": ("字阶", "整页只有一个的大数（详情页主价、评分）", "—"),
    ".txt-display": ("字阶", "区块级大数", "—"),
    ".txt-price": ("字阶", "列表里的价格：与标题同字号，靠字重顶出来", "非价格不要用 700"),
    ".txt-title": ("字阶", "区块标题", "—"),
    ".txt-strong": ("字阶", "需要比正文重一档的行", "—"),
    ".txt-body": ("字阶", "正文", "—"),
    ".txt-sub": ("字阶", "次要信息", "B 端次要文字走 .sh-muted（字号跟密度变量）"),
    ".txt-caption": ("字阶", "最弱一档：单位、脚注", "低于这一档就读不清了，不要再小"),
    ".sh-h1": ("字阶（旧名）", "页面级标题。与 .txt-display 同值", "新代码优先 .txt-*"),
    ".sh-h2": ("字阶（旧名）", "区块标题。与 .txt-title 同值", "新代码优先 .txt-*"),
    ".sh-muted": ("字阶", "次要文字。**字号走密度变量**，B 端自动降到 24rpx", "—"),
    ".sh-num": ("数字", "金额、结存、差异、百分比 —— 等宽且 RTL 下强制 LTR", "非数字不要加"),
    ".field": ("表单", "字段之间的间距（B 端专有）", "—"),
    ".field__label": ("表单", "字段标签", "—"),
    ".field__input": ("表单", "单行输入", "—"),
    ".field__area": ("表单", "多行输入", "—"),
    ".field__hint": ("表单", "规则说明，最弱一档", "报错不要用它 —— 那要有颜色"),
    ".sh-ph": ("表单", "占位文字色。**必须显式给**：三端默认占位色各不相同", "要配 placeholder-style 一起用"),
}

# 组件 → (一句话用途, 存在的理由 / 关键约束)
COMP_NOTES = {
    "sh-scaffold": ("页面外壳", "**每页根元素必须是它** —— 否则小程序端换肤 / RTL / 三语标题全不生效"),
    "sh-tabbar": ("自定义底部菜单", "原生 tabBar 字号锁死、不吃 CSS 变量、不吃 i18n，三件事自定义之后自然解决"),
    "sh-empty": ("空态", "抽出来不是因为重复，是**观感会漂**：曾在 27 个页面各写一份，padding 在 60/80/100rpx 之间随手取"),
    "sh-icon": ("单色图标", "内联 SVG 转 CSS mask，颜色吃 var(--sh-*) —— 换肤零成本。**不用 emoji**：各系统字形不一且是彩色的"),
    "sh-tabs": ("筛选条", "抽之前两端有两套实现（chip 横排 / 方块），同一个产品里两种筛选条"),
    "sh-cover": ("商品封面", "`cover` 字段二义：种子是 emoji、商家上传后是 COS URL。14 处渲染点都没分流过"),
    "sh-sheet": ("底部弹层", "不用 uni.showModal（字不归我们管）、不做页内展开（会把下文顶走）"),
    "sh-rating": ("评分", "底层灰星 + 上层主色星按百分比裁切 —— 半星图标只能表达 0.5 粒度，4.3 会被抹成 4.5"),
    "sh-theme-sheet": ("外观面板", "9 套皮肤 × 明暗 × 语言，选中即时全局生效"),
    "sh-add": ("＋ 加一项按钮", "收编自 goods-edit 与 my-specs 里**逐字节相同**的 `.btn-add`。`active` 是展开态：同一个按钮管开合"),
    "sh-section": ("卡内标题行", "收编自 goods-edit 与 sku-identity **逐字节相同**的 `.sec`（8 处调用点）。右侧动作直接进插槽，**不套壳** —— `.sec` 是 space-between，多一层就把「三个孩子摊开」变成「两组左右分」"),
    "sh-savebar": ("底部未保存条", "收编自 store 与 store-scope 里**逐字节相同**的 `.savebar`。自带流内占位 —— 收编前两页都被它盖住了最后一段内容"),
    "biz-region-picker": ("经营范围选择器", "省市区 / 小区 / 村三级 + 提报。全项目最大的单文件组件"),
    "biz-pickup-sheet": ("自提点选择弹层", "自带 mask 与 panel，**没走 sh-sheet**"),
    "biz-time-range": ("时段输入", "不让人手敲 —— 手敲的结果是全角横线、少个冒号、写成「6点半到9点」"),
    "biz-store-tag": ("当前门店胶囊", "可点 = 门店管理入口；readonly = 只说清这屏属于哪家店"),
    "app-overlay": ("应用常驻层", "B 端是空壳，但**不能删** —— sh-scaffold 无条件渲染它"),
}

# 组件样例的**结构**（类名一律取自组件自己的 template；样式不在这里，由真源 CSS 决定）
SAMPLES = {
    "sh-empty": '<div class="sh-card empty"><span class="sh-muted">还没有待处理的订单</span></div>',
    "sh-tabs": ('<div class="tabs">'
                '<span class="sh-chip tabs__chip sh-chip--primary">全部</span>'
                '<span class="sh-chip tabs__chip">待接单</span>'
                '<span class="sh-chip tabs__chip">待取货</span>'
                '<span class="sh-chip tabs__chip">已完成</span></div>'),
    "sh-rating": ('<div class="rating"><div class="rating__stars" style="font-size:13px">'
                  '<span class="rating__bg">★★★★★</span>'
                  '<span class="rating__fg" style="width:86%">★★★★★</span></div>'
                  '<span class="rating__value sh-num" style="font-size:13px">4.3</span></div>'),
    "sh-sheet": ('<div class="sheet-demo"><div class="sheet__panel">'
                 '<div class="sheet__grip"></div>'
                 '<div class="sheet__head"><span class="sheet__title">选择规格</span>'
                 '<span class="sheet__close">✕</span></div>'
                 '<span class="sheet__hint">规格一旦被商品引用就不能删</span>'
                 '<div class="sh-card" style="margin-top:10px">500g / 袋</div></div></div>'),
    "biz-store-tag": ('<div class="tag">{{icon:store:9:var(--sh-primary-text)}}'
                      '<span class="tag__name">城南店</span>'
                      '<span class="tag__switch">切换</span></div>'
                      '<div class="tag tag--flat">{{icon:store:9:var(--sh-sub)}}'
                      '<span class="tag__name">城南店</span></div>'),
    "sh-add": ('<div class="add">{{icon:plus:12:var(--sh-primary)}}'
               '<span class="add__t">加参数</span></div>'
               '<div class="add add--on">{{icon:close:12:var(--sh-sub)}}'
               '<span class="add__t">收起</span></div>'
               '<div class="add add--sm">{{icon:plus:10:var(--sh-primary)}}'
               '<span class="add__t">加值</span></div>'),
    "sh-section": ('<div class="sec"><span class="sh-h2">商品参数</span>'
                   '<div class="add">{{icon:plus:12:var(--sh-primary)}}'
                   '<span class="add__t">加参数</span></div></div>'
                   '<div class="sec" style="margin-top:10px">'
                   '<span class="sh-h2">库存</span></div>'),
    "sh-savebar": ('<div class="bar bar--demo"><span class="bar__t">有未保存的修改</span>'
                   '<span class="sh-btn sh-btn--muted bar__discard">放弃</span>'
                   '<span class="sh-btn bar__save">保存</span></div>'),
    "biz-time-range": ('<div class="tr"><div class="tr__box">08:00</div>'
                       '<span class="tr__sep">–</span>'
                       '<div class="tr__box is-empty">结束时间</div></div>'),
}

# tabbar 的样例要按真实 TABS 渲染（b-app/src/shared/nav.ts），图标取自 icons.ts
B_TABS = [("home", "homeFilled", "工作台", True), ("grid", "gridFilled", "订单", False),
          ("cart", "cartFilled", "商品", False), ("user", "userFilled", "我的", False)]


# ══════════════════════════════════════════════════════════════════════
# 读真源
# ══════════════════════════════════════════════════════════════════════

def strip_comments(css: str) -> str:
    return re.sub(r"/\*.*?\*/", "", css, flags=re.S)


def rpx2px(text: str) -> str:
    """1rpx = 0.5px（375pt 画布）。9999px 这类已经是 px 的原样保留。"""
    return re.sub(r"(\d+(?:\.\d+)?)rpx", lambda m: f"{float(m.group(1)) / 2:g}px", text)


def rules_of(css: str) -> list[tuple[str, str]]:
    """把 CSS 切成 [(选择器, 声明体)]。只处理平铺规则，够用 —— base.css 没有嵌套。"""
    out = []
    for m in re.finditer(r"([^{}]+)\{([^{}]*)\}", css):
        sel = " ".join(m.group(1).split())
        body = m.group(2).strip()
        if sel and body:
            out.append((sel, body))
    return out


def decls_of(body: str) -> dict[str, str]:
    d = {}
    for line in body.split(";"):
        if ":" in line:
            k, v = line.split(":", 1)
            d[k.strip()] = " ".join(v.split())
    return d


def read_tokens() -> dict:
    src = TOKENS_TS.read_text(encoding="utf-8")

    def scale(name: str) -> dict[str, str]:
        m = re.search(rf"export const {name} = \{{(.*?)\}} as const;", src, re.S)
        assert m, f"tokens.ts 里找不到 {name}"
        return dict(re.findall(r"(\w+):\s*\"([^\"]+)\"", m.group(1)))

    skins = re.findall(r'\{ id: "(\w+)", color: "(#[0-9A-Fa-f]{6})" \}', src)
    return {"radius": scale("radius"), "spacing": scale("spacing"),
            "skins": [{"id": i, "color": c} for i, c in skins]}


def read_icons() -> dict[str, str]:
    """icons.ts 是 wrap()/wrapFilled() 包出来的，这里照它的两个模板还原成完整 SVG。"""
    src = ICONS_TS.read_text(encoding="utf-8")
    wrap = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" '
            'stroke="#000" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">{}</svg>')
    wrapf = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="#000">{}</svg>'
    body = src[src.index("export const ICONS"):]
    out = {}
    # 长图标是多段字符串**用 + 拼**的（grip / sliders），所以段与段之间既可能是逗号也可能是加号。
    # 早先的表达式只认逗号 —— 那两个图标被**静默丢掉**：不报错，只是格子空着。
    for m in re.finditer(r"(\w+):\s*(wrap|wrapFilled)\(\s*((?:'[^']*'\s*[+,]?\s*)+)\)", body):
        paths = "".join(re.findall(r"'([^']*)'", m.group(3)))
        out[m.group(1)] = (wrap if m.group(2) == "wrap" else wrapf).format(paths)
    # 少一个就是少一个图标，而症状只是「那一格是空的」—— 让它当场喊出来
    declared = set(re.findall(r"^  (\w+):\s*wrap", body, re.M))
    missing = declared - set(out)
    assert not missing, f"icons.ts 里这几个没解析出来（写法变了？）：{sorted(missing)}"
    return out


def read_base() -> dict:
    """base.css → 皮肤变量（brand 明/暗）、与主题无关的常量、全部公共积木的声明。"""
    css = strip_comments(BASE_CSS.read_text(encoding="utf-8"))
    consts, light, dark, blocks = {}, {}, {}, {}
    for sel, body in rules_of(css):
        d = decls_of(body)
        if sel.startswith(":root,") and "--sh-tabbar-h" in d:
            consts = d
        elif f'[data-skin="{PROTO_SKIN}"][data-theme="light"]' in sel or (
                sel.startswith(":root,") and "--sh-primary" in d):
            light = d
        elif f'[data-skin="{PROTO_SKIN}"][data-theme="dark"]' in sel:
            dark = d
        # 只收「单一类名」的积木：.sh-root / .sh-root.is-rtl 是外壳状态，不是可复用的件
        elif (re.match(r"^\.(sh-|txt-|field)", sel) and " " not in sel
              and "," not in sel and sel.count(".") == 1 and sel != ".sh-root"):
            blocks[sel] = d
    assert consts and light and dark, "base.css 的皮肤/常量段没解析出来"
    return {"consts": consts, "light": light, "dark": dark, "blocks": blocks}


def read_density() -> dict:
    """密度：C 端默认值写在 base.css 的 var() 兜底里，B 端覆盖写在 b-app/App.vue。"""
    base = strip_comments(BASE_CSS.read_text(encoding="utf-8"))
    scaffold = strip_comments((UI_COMPONENTS / "sh-scaffold.vue").read_text(encoding="utf-8"))
    empty = strip_comments((UI_COMPONENTS / "sh-empty.vue").read_text(encoding="utf-8"))
    tabs = strip_comments((UI_COMPONENTS / "sh-tabs.vue").read_text(encoding="utf-8"))
    hay = base + scaffold + empty + tabs
    defaults = {}
    for var in ("--sh-pad-card", "--sh-pad-page", "--sh-pad-empty", "--sh-gap-tabs", "--sh-fs-sub"):
        m = re.search(rf"var\({var},\s*([0-9]+rpx)\)", hay)
        defaults[var] = m.group(1) if m else None
    b = strip_comments(B_APP_VUE.read_text(encoding="utf-8"))
    overrides = {}
    for sel, body in rules_of(b):
        if "--sh-pad-card" in body:
            overrides = {k: v for k, v in decls_of(body).items() if k.startswith("--sh-")}
    field = next((decls_of(b_) for s_, b_ in rules_of(b) if s_ == ".field"), {})
    return {"c": defaults, "b": overrides, "field": field}


def scoped_css(vue: str) -> str:
    return "\n".join(re.findall(r"<style[^>]*scoped[^>]*>(.*?)</style>", vue, re.S))


def props_of(vue: str) -> list[str]:
    m = re.search(r"defineProps<\{(.*?)\}>", vue, re.S)
    if not m:
        return []
    body = strip_comments(re.sub(r"//[^\n]*", "", m.group(1)))
    return [f"{n}{'?' if q else ''}: {t.strip()}"
            for n, q, t in re.findall(r"(\w+)(\??):\s*([^;\n]+)", body)]


def usage(name: str, roots: list[str]) -> int:
    n = 0
    for r in roots:
        for f in (ROOT / r).rglob("*.vue"):
            n += len(re.findall(rf"<{name}[\s>]", f.read_text(encoding="utf-8")))
    return n


def class_usage(cls: str, roots: list[str]) -> int:
    n = 0
    pat = re.compile(r"[\"' ]" + re.escape(cls.lstrip(".")) + r"[\"' ]")
    for r in roots:
        for f in (ROOT / r).rglob("*.vue"):
            tpl = re.search(r"<template>(.*)</template>", f.read_text(encoding="utf-8"), re.S)
            if tpl:
                n += len(pat.findall(tpl.group(1)))
    return n


def read_components() -> list[dict]:
    out = []
    files = sorted(UI_COMPONENTS.glob("sh-*.vue")) + sorted(B_COMPONENTS.rglob("*.vue"))
    for f in files:
        src = f.read_text(encoding="utf-8")
        name = f.stem
        out.append({
            "name": name,
            "file": str(f.relative_to(ROOT)),
            "scope": "跨端" if "packages/ui" in str(f) else "B 端",
            "lines": len(src.splitlines()),
            "props": props_of(src),
            "usage": {"b-app": usage(name, ["b-app/src"]), "c-app": usage(name, ["c-app/src"])},
            "note": COMP_NOTES.get(name, ("—", "—"))[0],
            "why": COMP_NOTES.get(name, ("—", "—"))[1],
            "css": scoped_css(src),
        })
    return out



# ══════════════════════════════════════════════════════════════════════
# 页面 × 组件库：每一页用了库里的什么、又自己造了什么
#
# **判据是正则，会有误判**，所以判据本身也写进清单（`rule` 字段）——
# 读的人能自己核，不必信这份扫描。宁可判得保守：拿不准的不算「自造」。
#
# lib=None 表示**库里没有这个件**，那一行就是缺口，不是页面的错。
# ══════════════════════════════════════════════════════════════════════

# (id, 名称, 在模板/脚本里找, 在样式里找, 若这个库件已被使用则不算自造, 对应库件)
ROLLED = [
    ("tabs",    "分栏切换",       None, r"^\s*\.tabs?\b",                       "sh-tabs",  "sh-tabs"),
    ("empty",   "空态",           None, r"^\s*\.empty\b",                       "sh-empty", "sh-empty"),
    ("sheet",   "弹层 / 遮罩",     None, r"^\s*\.(mask|dlg|modal|popup)\b",      "sh-sheet", "sh-sheet"),
    ("sysmodal","系统弹框",       r"showModal\(|showActionSheet\(", None,        None,       "sh-sheet"),
    ("arrow",   "文字当箭头",      r"[›»]\s*</text>", None,                       None,       "sh-icon(chevronRight)"),
    ("segment", "选中态自画",      None, r"(--on|--off|is-on)\s*[,{]",             None,       ".sh-chip--primary"),
    ("blockdup","白块自画",        None, r"background:\s*var\(--sh-surface\)[^}]*border-radius", None, ".sh-block / .sh-card"),
    # ↓ 库里没有的：这几行是缺口
    # 卡内标题行判**声明**不判名字：`groups` / `plan` 里也有个 `.sec`，
    # 但那是 `<text class="sh-h2 sec">` —— 只有标题、只有 margin，没有右侧动作。
    # 按名字归成一类，会把「两种形态」误读成「一种被画了两遍」（与 addbtn/candchip 同一课）。
    ("section", "卡内标题行", None,
     r"\.(?:sec|cat__head|grp__head)\b[^{}]*\{[^}]*justify-content:\s*space-between", None, None),
    # 只有标题、靠 margin 分段的那一种。**它不缺组件，缺的是间距档** ——
    # 各页写的是 24rpx / 40rpx 8rpx 16rpx / 28rpx 0，差别是真实的版面决定，
    # 收成组件只会多一个 props 去表达「这里松一点」。
    ("sechead", "分段标题（只有标题）", r'class="sh-h2 (?:sec|grp)\b', r"^\s*\.(sec__h|grp)\b",
     None, ".sh-h2 + 间距档"),
    ("stat",    "统计数字格",      None, r"^\s*\.(trio|quad|nums|stat|kpi)\b",   None,       None),
    ("listrow", "列表行",         None, r"^\s*\.(row|item)\b",                  None,       None),
    ("kv",      "键值行",         None, r"^\s*\.(kv|rule|prob|field__head)\b",  None,       None),
    ("addbtn",  "＋ 加一项按钮",   None, r"^\s*\.btn-add\b",                     None,       None),
    # 虚线药丸是**另一件事**，goods-edit 的注释里把两者的分工写死了：
    # 虚线＝候选（点一下当场加进来），浅底按钮＝入口（点一下开弹层再填）。
    # 归成一类会把「已经有两种形状且是故意的」误读成「一种形状被画了两遍」。
    ("candchip","候选标签（虚线药丸）", None, r"border:\s*2rpx dashed var\(--sh-primary\)", None, None),
    # 判据要认「标签上的那个 ✕」，不能只认类名 —— role-detail 的 `.del` 是一个
    # 危险按钮（`sh-btn sh-btn--danger del`），按类名会被误判成可删标签。
    ("chipdel", "可删标签",
     r'class="[^"]*(?:val__x|__x|\bdel\b)[^"]*"[^>]*>\s*[✕×]', None,           None,       None),
    ("savebar", "底部固定条",      None, r"position:\s*fixed[^}]*bottom:\s*0",    None,       None),
    ("search",  "搜索框",         None, r"^\s*\.search\b",                      None,       None),
    ("uploader","图片上传格",      r"pickImages\(|chooseImages\(", None,          None,       None),
    ("fab",     "悬浮新建按钮",    None, r"^\s*\.fab\b",                         None,       None),
]

B_PAGES = ROOT / "b-app/src/pages"


def read_pages(comps: list[dict], blocks: list[dict]) -> list[dict]:
    comp_names = [c["name"] for c in comps]
    lib_classes = [b["class"].lstrip(".") for b in blocks]
    out = []
    for f in sorted(B_PAGES.rglob("*.vue")):
        src = f.read_text(encoding="utf-8")
        m = re.search(r"<template>(.*)</template>", src, re.S)
        tpl = re.sub(r"<!--.*?-->", "", m.group(1), flags=re.S) if m else ""
        css = strip_comments("\n".join(re.findall(r"<style[^>]*>(.*?)</style>", src, re.S)))
        used = [c for c in comp_names if re.search(rf"<{c}[\s>]", tpl)]
        hits = sum(len(re.findall(r"[\"' ]" + re.escape(c) + r"[\"' ]", tpl)) for c in lib_classes)
        rolled = []
        for rid, label, tp, cp, skip_if, lib in ROLLED:
            if skip_if and skip_if in used:
                continue
            if (tp and re.search(tp, src)) or (cp and re.search(cp, css, re.M)):
                rolled.append({"id": rid, "label": label, "lib": lib,
                               "rule": tp or cp, "gap": lib is None})
        out.append({
            "page": str(f.relative_to(B_PAGES).parent).replace("\\", "/"),
            "file": str(f.relative_to(ROOT)),
            "components": used,
            "libClassHits": hits,
            "localSelectors": len(re.findall(r"\{", css)),
            "localCssLines": len([l for l in css.split("\n") if l.strip()]),
            "rolled": rolled,
        })
    return out


def gaps_of(pages: list[dict]) -> list[dict]:
    """把自造形态按「多少页在重复造」排序。库里没有的排前面 —— 那才是缺口。"""
    agg: dict[str, dict] = {}
    for p in pages:
        for r in p["rolled"]:
            a = agg.setdefault(r["id"], {"id": r["id"], "label": r["label"], "lib": r["lib"],
                                         "gap": r["gap"], "rule": r["rule"], "pages": []})
            a["pages"].append(p["page"])
    return sorted(agg.values(), key=lambda a: (not a["gap"], -len(a["pages"])))


# ══════════════════════════════════════════════════════════════════════
# 组装清单
# ══════════════════════════════════════════════════════════════════════

TYPE_CLASSES = [".txt-hero", ".txt-display", ".txt-price", ".txt-title",
                ".txt-strong", ".txt-body", ".txt-sub", ".txt-caption"]


def build() -> dict:
    tok, base, dens = read_tokens(), read_base(), read_density()
    blocks = []
    for cls, decl in base["blocks"].items():
        group, when, avoid = BLOCK_NOTES.get(cls, ("其它", "—", "—"))
        blocks.append({
            "class": cls, "group": group, "when": when, "avoid": avoid,
            "decl": decl, "px": {k: rpx2px(v) for k, v in decl.items()},
            "usage": {"b-app": class_usage(cls, ["b-app/src"]),
                      "c-app": class_usage(cls, ["c-app/src"])},
        })
    # .field 只定义在 b-app/App.vue，base.css 里没有
    if dens["field"]:
        blocks.append({
            "class": ".field", "group": "表单", "when": BLOCK_NOTES[".field"][1],
            "avoid": BLOCK_NOTES[".field"][2], "decl": dens["field"],
            "px": {k: rpx2px(v) for k, v in dens["field"].items()},
            "usage": {"b-app": class_usage(".field", ["b-app/src"]),
                      "c-app": class_usage(".field", ["c-app/src"])},
        })
    comps = read_components()
    pages = read_pages(comps, blocks)
    gaps = gaps_of(pages)
    return {
        "generatedFrom": ["packages/shared/src/design/tokens.ts",
                          "packages/shared/src/design/icons.ts",
                          "packages/ui/src/styles/base.css",
                          "b-app/src/App.vue",
                          "packages/ui/src/components/*.vue",
                          "b-app/src/components/**/*.vue"],
        "canvas": {"base": "375pt", "rpxToPx": 0.5, "skin": PROTO_SKIN},
        "tokens": {
            "radius": {k: {"rpx": v, "px": rpx2px(v)} for k, v in tok["radius"].items()},
            "spacing": {k: {"rpx": v, "px": rpx2px(v)} for k, v in tok["spacing"].items()},
            "skins": tok["skins"],
            "semantic": {k: v for k, v in base["consts"].items()
                         if k.startswith(("--sh-success", "--sh-warning", "--sh-danger"))},
            "constants": {k: v for k, v in base["consts"].items()
                          if k in ("--sh-tabbar-h", "--sh-app-max", "--sh-scrim")},
            "skinVars": {"light": base["light"], "dark": base["dark"]},
            "type": [{"class": c, "size": base["blocks"][c]["font-size"],
                      "px": rpx2px(base["blocks"][c]["font-size"]),
                      "weight": base["blocks"][c]["font-weight"],
                      "lineHeight": base["blocks"][c]["line-height"]}
                     for c in TYPE_CLASSES if c in base["blocks"]],
            "density": dens,
        },
        "blocks": sorted(blocks, key=lambda b: (b["group"], b["class"])),
        "components": [{k: v for k, v in c.items() if k != "css"} for c in comps],
        "pages": pages,
        "gaps": gaps,
        "counts": {"tokens": len(tok["radius"]) + len(tok["spacing"]),
                   "blocks": len(blocks), "components": len(comps),
                   "pages": len(pages),
                   "gapKinds": len([g for g in gaps if g["gap"]])},
    }


# ══════════════════════════════════════════════════════════════════════
# 渲染原型页
# ══════════════════════════════════════════════════════════════════════

def proto_css(base: dict, comps: list[dict], dens: dict) -> str:
    """把真源的 CSS 折半成 px 并收进 .up 作用域 —— 原型里的每一条都来自 base.css。"""
    out = []

    def block(sel: str, decls: dict) -> str:
        body = ";".join(f"{k}:{rpx2px(v)}" for k, v in decls.items())
        return f"{sel}{{{body}}}"

    out.append(block(".up", {**base["consts"], **base["light"],
                             **{k: v for k, v in dens["c"].items() if v}}))
    out.append(block(".up.dark", base["dark"]))
    out.append(block(".up.dens-b", dens["b"]))
    for sel, decls in base["blocks"].items():
        out.append(block(f".up {sel}", decls))
    if dens["field"]:
        out.append(block(".up .field", dens["field"]))
    # 组件自己的 scoped 样式（scoped 属性在原型里没有意义，去掉即可）
    for c in comps:
        for sel, body in rules_of(strip_comments(c["css"])):
            if sel.startswith("@") or "{" in sel:
                continue
            scoped = ",".join(f".up {s.strip()}" for s in sel.split(","))
            out.append(f"{scoped}{{{rpx2px(body)}}}")
    return "\n".join(out)


def icon_html(icons: dict, name: str, size: float, color: str) -> str:
    """与 sh-icon 同一条路子：内联 SVG 转 data-URI 作 CSS mask，颜色由 background-color 给。

    ⚠️ url() 里用**单引号**：这段要落进 style="…" 属性里，双引号会当场把属性截断，
    症状是 mask-image 计算成 `url("")` —— 图标位置空着，不报任何错。
    """
    from urllib.parse import quote
    url = f"url('data:image/svg+xml;utf8,{quote(icons[name], safe='')}')"
    return (f'<i class="ic" style="width:{size}px;height:{size}px;background-color:{color};'
            f'-webkit-mask-image:{url};mask-image:{url}"></i>')


def expand(html: str, icons: dict) -> str:
    """样例里的 {{icon:name:size:color}} 占位换成真图标。"""
    return re.sub(r"\{\{icon:(\w+):([\d.]+):([^}]+)\}\}",
                  lambda m: icon_html(icons, m.group(1), float(m.group(2)), m.group(3)), html)


def md(text: str) -> str:
    """条目里的 **粗** 与 `码` 是写给人看的记法，渲染时统一转标签 —— 别让 ** 漏到页面上。"""
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    return re.sub(r"`([^`]+)`", r"<code>\1</code>", text)


def swatch_rows(d: dict) -> str:
    rows = []
    for k, v in d.items():
        rows.append(f'<div class="sw"><span class="chipc" style="background:{v}"></span>'
                    f'<code>{k}</code><code class="v">{v}</code></div>')
    return "".join(rows)


def phone(inner: str, dark: bool = False, dens_b: bool = True, pad: bool = True) -> str:
    cls = "up" + (" dark" if dark else "") + (" dens-b" if dens_b else " dens-c")
    padcls = " is-padded" if pad else ""
    return (f'<div class="{cls}"><div class="sh-scaffold{padcls}">{inner}</div></div>')


def render(cat: dict, base: dict, comps: list[dict], dens: dict, icons: dict) -> str:
    from html import escape
    tok = cat["tokens"]
    P = []

    def sec(title, hint, body, anchor):
        P.append(f'<section id="{anchor}"><h2>{escape(title)}</h2>'
                 f'<p class="hint">{md(hint)}</p>{body}</section>')

    # ── 令牌：色 ─────────────────────────────────────────────
    sec("色", "皮肤 = 主色 + 前景 + 中性面，按明暗分别取值。下面是 <code>brand</code>（虹选红，B 端默认）"
        "两档的**全部** <code>--sh-*</code>，取自 base.css 生成段。语义三色不随皮肤变。",
        f'<div class="cols"><div><h3>浅色</h3>{swatch_rows(tok["skinVars"]["light"])}</div>'
        f'<div><h3>深色</h3>{swatch_rows(tok["skinVars"]["dark"])}</div>'
        f'<div><h3>语义色（不随皮肤变）</h3>{swatch_rows(tok["semantic"])}</div></div>'
        f'<h3 style="margin-top:22px">九套皮肤</h3><div class="skins">' +
        "".join(f'<span class="sk"><i style="background:{s["color"]}"></i>{s["id"]}</span>'
                for s in tok["skins"]) + "</div>", "color")

    # ── 令牌：圆角 / 间距 ────────────────────────────────────
    rad = "".join(
        f'<div class="tk"><div class="rbox" style="border-radius:{v["px"]}"></div>'
        f'<code>{k}</code><code class="v">{v["rpx"]} = {v["px"]}</code></div>'
        for k, v in tok["radius"].items())
    spa = "".join(
        f'<div class="tk"><div class="sbar" style="width:{v["px"]}"></div>'
        f'<code>{k}</code><code class="v">{v["rpx"]} = {v["px"]}</code></div>'
        for k, v in tok["spacing"].items())
    sec("圆角五档 · 间距五档",
        "组件层<b>只许用这五个</b>。差 4rpx 的两个圆角没人分得出，只会让人各写各的 ——"
        "真源 <code>tokens.ts</code>，<code>uno.config.ts</code> 同源，规范测试拦截。",
        f'<div class="cols2"><div><h3>圆角</h3><div class="tks">{rad}</div></div>'
        f'<div><h3>间距</h3><div class="tks">{spa}</div></div></div>', "radius")

    # ── 令牌：字阶 ───────────────────────────────────────────
    rows = "".join(
        f'<tr><td><code>{t["class"]}</code></td>'
        f'<td class="samp"><span class="up"><span class="{t["class"].lstrip(".")}">'
        f'虹选商家 Aa 128</span></span></td>'
        f'<td><code>{t["size"]}</code> = <code>{t["px"]}</code></td>'
        f'<td><code>{t["weight"]}</code></td><td><code>{t["lineHeight"]}</code></td></tr>'
        for t in tok["type"])
    sec("字阶七档 · 字重三种",
        "<b>700 只给价格，600 只给标题与按钮，其余一律 400。</b>"
        "层级改由颜色与留白承担。最小 24rpx = 12px —— 再小中文笔画糊。"
        "字阶里<b>不含 letter-spacing</b>：同一个类要同时承载中/英/阿三种文字。",
        f'<div class="scroll"><table class="ts"><thead><tr><th>类名</th><th>样张</th>'
        f'<th>字号</th><th>字重</th><th>行高</th></tr></thead>'
        f'<tbody>{rows}</tbody></table></div>', "type")

    # ── 令牌：密度 ───────────────────────────────────────────
    drows = "".join(
        f'<tr><td><code>{k}</code></td><td><code>{dens["c"].get(k) or "—"}</code></td>'
        f'<td><code>{v}</code></td></tr>' for k, v in dens["b"].items())
    sec("密度：同一套原语，两种松紧",
        "C 端是顾客逛店（松一点显精致），B 端是店主一天扫几十次的<b>作业台</b>，密度即效率。"
        "靠五个变量分开，<b>不复制样式</b>。",
        f'<div class="scroll"><table class="ts"><thead><tr><th>变量</th><th>C 端</th>'
        f'<th>B 端</th></tr></thead><tbody>{drows}</tbody></table></div>'
        f'<div class="pair"><figure>{phone(sample_density(), dens_b=False)}'
        f'<figcaption>C 端密度</figcaption></figure>'
        f'<figure>{phone(sample_density())}<figcaption>B 端密度（本项对照）</figcaption></figure></div>',
        "density")

    # ── 积木 ────────────────────────────────────────────────
    groups: dict[str, list] = {}
    for b in cat["blocks"]:
        groups.setdefault(b["group"], []).append(b)
    body = []
    for g, items in groups.items():
        cards = []
        for b in items:
            decl = "".join(f'<div><code>{k}</code>: <code class="v">{v}</code>'
                           + (f' <span class="px">→ {b["px"][k]}</span>'
                              if b["px"][k] != v else "") + "</div>"
                           for k, v in b["decl"].items())
            demo = BLOCK_DEMOS.get(b["class"], "")
            use = f'B {b["usage"]["b-app"]} · C {b["usage"]["c-app"]}'
            warn = ' <span class="zero">未被引用</span>' if b["usage"]["b-app"] + b["usage"]["c-app"] == 0 else ""
            cards.append(
                f'<div class="bk"><div class="bk__demo"><div class="up dens-b">{demo}</div></div>'
                f'<div class="bk__meta"><div class="bk__h"><code class="cn">{b["class"]}</code>'
                f'<span class="use">{use}{warn}</span></div>'
                f'<p class="when">{md(b["when"])}</p>'
                f'<p class="avoid">不要用在：{md(b["avoid"])}</p>'
                f'<div class="decl">{decl}</div></div></div>')
        body.append(f'<h3 class="grp">{escape(g)}<span class="n">{len(items)}</span></h3>'
                    f'<div class="bks">{"".join(cards)}</div>')
    sec("积木：公共类",
        "画原型时<b>直接用这些类名</b> —— 原型用真类名，落地就是搬；自己起名字等于让前端再翻译一遍。"
        "每条右侧是它在 base.css 里的<b>真实声明</b>（rpx 原值 → 375pt 下的 px）。",
        "".join(body), "blocks")

    # ── 组件 ────────────────────────────────────────────────
    cards = []
    for c in comps:
        d = SAMPLES.get(c["name"])
        if c["name"] == "sh-tabbar":
            d = tabbar_sample(icons)
        elif c["name"] == "sh-icon":
            d = icon_sample(icons)
        elif c["name"] == "sh-cover":
            d = ('<div class="cover" style="width:48px;height:48px;border-radius:8px;'
                 'background:var(--sh-faint);font-size:30px">'
                 '<span class="cover__emoji">🍚</span></div>')
        elif c["name"] == "sh-scaffold":
            d = '<div class="sh-denied"><span class="sh-denied__t">这页不归你管</span>' \
                '<span class="sh-denied__d">让店主给你加个角色</span></div>'
        props = "".join(f"<li><code>{escape(p)}</code></li>" for p in c["props"]) or "<li>—</li>"
        demo = (f'<div class="c__demo">{phone(expand(d, icons)) if d else ""}</div>'
                if d else '<div class="c__demo c__demo--none">整屏/整层组件<br>见页面内实例</div>')
        cards.append(
            f'<div class="cp">{demo}'
            f'<div class="c__meta"><div class="c__h"><code class="cn">&lt;{c["name"]}&gt;</code>'
            f'<span class="tag2">{c["scope"]}</span>'
            f'<span class="use">B {c["usage"]["b-app"]} · C {c["usage"]["c-app"]} · {c["lines"]} 行</span></div>'
            f'<p class="when">{md(c["note"])}</p><p class="avoid">{md(c["why"])}</p>'
            f'<ul class="props">{props}</ul>'
            f'<code class="path">{c["file"]}</code></div></div>')
    sec("组件", "easycom 自动注册：<code>sh-*</code> 来自 <code>@ai-shop/ui</code>（两端共用），"
        "<code>biz-*</code> 是 B 端业务件。样例里的样式全部来自组件自己的 "
        "<code>&lt;style scoped&gt;</code>，只有结构是写的。",
        "".join(cards), "components")

    # ── 页面 × 组件库 ───────────────────────────────────────
    pages, gaps = cat["pages"], cat["gaps"]
    zero = [p for p in pages if not p["rolled"]]
    inst = sum(len(p["rolled"]) for p in pages)
    grow_cells = []
    for g in gaps:
        who = '<span class="gapb">库里没有</span>' if g["gap"] else f'<code>{escape(str(g["lib"]))}</code>'
        grow_cells.append(f'<tr><td><code>{escape(g["label"])}</code></td>'
                          f'<td class="cnt">{len(g["pages"])}</td><td>{who}</td>'
                          f'<td class="pl">{escape("、".join(g["pages"]))}</td></tr>')
    grows = "".join(grow_cells)
    CLEAN = '<span class="clean">全部走库件</span>'
    prows = []
    for pg in sorted(pages, key=lambda x: (-len(x["rolled"]), -x["localSelectors"])):
        used = "".join(f'<span class="ok">{c.replace("sh-","").replace("biz-","")}</span>'
                       for c in pg["components"] if c != "sh-scaffold")
        roll = "".join(f'<span class="{"gap" if r["gap"] else "dup"}">{r["label"]}</span>'
                       for r in pg["rolled"])
        prows.append(
            f'<tr><td><code>{pg["page"]}</code></td>'
            f'<td class="cnt">{pg["localSelectors"]}</td>'
            f'<td class="cnt">{pg["libClassHits"]}</td>'
            f'<td class="pills">{used or "—"}</td>'
            f'<td class="pills">{roll or CLEAN}</td></tr>')
    sec("页面 × 组件库",
        f"B 端 <b>{len(pages)} 个页面</b>逐页扫过：用了库里的什么、又自己造了什么。"
        f"<b>只有 {len(zero)} 页完全没有自造形态</b>，其余合计 <b>{inst} 处</b>。"
        "<b>判据是正则，会有误判</b> —— 判据本身写在 <code>ui-lib.json</code> 的 "
        "<code>rule</code> 字段里，可以自己核。"
        "<span class='gap'>红</span>＝库里没有这个件（是<b>库的缺口</b>，不是页面的错）；"
        "<span class='dup'>黄</span>＝库里有，但这一页没用。",
        f'<h3>缺什么 · 谁在重复造</h3><div class="scroll"><table class="ts">'
        f'<thead><tr><th>形态</th><th>页数</th><th>库里对应</th><th>哪些页</th></tr></thead>'
        f'<tbody>{grows}</tbody></table></div>'
        f'<h3 style="margin-top:26px">逐页</h3><div class="scroll"><table class="ts pgt">'
        f'<thead><tr><th>页面</th><th>本页选择器</th><th>库类命中</th><th>用了库件</th>'
        f'<th>自己造的</th></tr></thead><tbody>{"".join(prows)}</tbody></table></div>',
        "pages")

    # ── 一屏合成 ────────────────────────────────────────────
    sec("合起来是这样", "同一屏、同一套令牌，浅色与深色只差根节点上的一个属性 —— 零重载换肤。",
        f'<div class="pair"><figure>{phone(full_screen(icons))}<figcaption>浅色</figcaption></figure>'
        f'<figure>{phone(full_screen(icons), dark=True)}<figcaption>深色</figcaption></figure></div>',
        "screen")

    nav = "".join(f'<a href="#{a}">{t}</a>' for a, t in
                  [("color", "色"), ("radius", "圆角/间距"), ("type", "字阶"), ("density", "密度"),
                   ("blocks", "积木"), ("components", "组件"), ("screen", "合成")])
    return (TEMPLATE
            .replace("{{CSS}}", proto_css(base, comps, dens))
            .replace("{{NAV}}", nav)
            .replace("{{BODY}}", "".join(P))
            .replace("{{NB}}", str(cat["counts"]["blocks"]))
            .replace("{{NC}}", str(cat["counts"]["components"]))
            .replace("{{NP}}", str(cat["counts"]["pages"])))


# ── 样例（只写结构，不写样式） ────────────────────────────────

BLOCK_DEMOS = {
    ".sh-card": '<div class="sh-card">一张卡</div>',
    ".sh-block": '<div class="sh-block"><div class="sh-block__head"><span class="sh-h2">今日</span>'
                 '</div><div style="padding:0 13px">块内内容通铺到边</div></div>',
    ".sh-block__head": '<div class="sh-block"><div class="sh-block__head">'
                       '<span class="sh-h2">标题</span><span class="sh-muted">副标</span></div></div>',
    ".sh-chip": '<span class="sh-chip">默认</span>',
    ".sh-chip--primary": '<span class="sh-chip sh-chip--primary">进行中</span>',
    ".sh-chip--warning": '<span class="sh-chip sh-chip--warning">待审核</span>',
    ".sh-chip--danger": '<span class="sh-chip sh-chip--danger">已拒绝</span>',
    ".sh-link": '<span class="sh-link">改名</span>',
    ".sh-link--quiet": '<span class="sh-link sh-link--quiet">收起</span>',
    ".sh-link--warn": '<span class="sh-link sh-link--warn">申诉</span>',
    ".sh-btn": '<div class="sh-btn">确认接单</div>',
    ".sh-btn--sm": '<span class="sh-btn sh-btn--soft sh-btn--sm">切到这家</span>'
                   '<span class="sh-link" style="margin-inline-start:10px">改名</span>',
    ".sh-btn--soft": '<div class="sh-btn sh-btn--soft">再来一单</div>',
    ".sh-btn--muted": '<div class="sh-btn sh-btn--muted">稍后再说</div>',
    ".sh-btn--danger": '<div class="sh-btn sh-btn--danger">停用员工</div>',
    ".sh-btn--danger-solid": '<div class="sh-btn sh-btn--danger-solid">确认停用</div>',
    ".txt-hero": '<span class="txt-hero">¥128.00</span>',
    ".txt-display": '<span class="txt-display">1,284</span>',
    ".txt-price": '<span class="txt-price">¥28.50</span>',
    ".txt-title": '<span class="txt-title">今日经营</span>',
    ".txt-strong": '<span class="txt-strong">城南店</span>',
    ".txt-body": '<span class="txt-body">五常大米 5kg 装</span>',
    ".txt-sub": '<span class="txt-sub">08-26 14:20 下单</span>',
    ".txt-caption": '<span class="txt-caption">含运费，不含服务费</span>',
    ".sh-h1": '<span class="sh-h1">工作台</span>',
    ".sh-h2": '<span class="sh-h2">待处理</span>',
    ".sh-muted": '<span class="sh-muted">共 12 笔 · 已结 8 笔</span>',
    ".sh-num": '<span class="sh-num">¥1,284.50</span>',
    ".sh-ph": '<div class="field__input"><span class="sh-ph">11 位手机号</span></div>',
    ".field": '<div class="field"><span class="field__label">店名</span>'
              '<div class="field__input">城南店</div></div>'
              '<div class="field"><span class="field__label">电话</span>'
              '<div class="field__input">028-8888 0000</div></div>',
    ".field__label": '<span class="field__label">门店名称</span>',
    ".field__input": '<div class="field__input">城南店</div>',
    ".field__area": '<div class="field__area">今日鲜货到店，欢迎自提。</div>',
    ".field__hint": '<span class="field__hint">停业后 C 端搜不到本店，已下单的不受影响</span>',
}


def sample_density() -> str:
    return ('<span class="sh-h2">待处理</span>'
            '<div class="sh-card" style="margin-top:8px">'
            '<div class="txt-strong">#20260826-0031</div>'
            '<div class="sh-muted">城南店 · 14:20 自提</div></div>'
            '<div class="sh-card" style="margin-top:8px">'
            '<div class="txt-strong">#20260826-0032</div>'
            '<div class="sh-muted">城南店 · 14:38 配送</div></div>')


def tabbar_sample(icons: dict) -> str:
    badge = '<span class="tabbar__badge sh-num">3</span>'
    items = []
    for ic, ion, label, on in B_TABS:
        mark = badge if ic == "user" else ""
        cls = "tabbar__item is-on" if on else "tabbar__item"
        items.append(f'<div class="{cls}"><div class="tabbar__icon-wrap">'
                     f'{icon_html(icons, ion if on else ic, 23, "currentColor")}{mark}</div>'
                     f'<span class="tabbar__label">{label}</span></div>')
    return f'<div class="tabbar tabbar--demo">{"".join(items)}</div>'


def icon_sample(icons: dict) -> str:
    names = ["home", "grid", "cart", "user", "store", "plus", "search",
             "pin", "sliders", "close", "chevronRight", "share", "grip"]
    return ('<div class="icons">' + "".join(
        f'<span class="ico"><span class="ico__b">'
        f'{icon_html(icons, n, 22, "var(--sh-ink)")}</span><code>{n}</code></span>'
        for n in names if n in icons) + "</div>")


def full_screen(icons: dict) -> str:
    return (
        '<div class="tag">' + icon_html(icons, "store", 9, "var(--sh-primary-text)") +
        '<span class="tag__name">城南店</span><span class="tag__switch">切换</span></div>'
        '<span class="sh-h1">工作台</span>'
        '<div class="tabs" style="margin-top:8px">'
        '<span class="sh-chip tabs__chip sh-chip--primary">全部</span>'
        '<span class="sh-chip tabs__chip">待接单</span>'
        '<span class="sh-chip tabs__chip">待取货</span></div>'
        '<div class="sh-card">'
        '<div style="display:flex;align-items:baseline;gap:6px">'
        '<span class="txt-title">#0031</span>'
        '<span class="sh-chip sh-chip--warning">待接单</span>'
        '<span class="txt-price sh-num" style="margin-inline-start:auto">¥128.00</span></div>'
        '<div class="sh-muted">五常大米 5kg × 1 · 14:20 自提</div></div>'
        '<div class="sh-card" style="margin-top:8px">'
        '<div style="display:flex;align-items:baseline;gap:6px">'
        '<span class="txt-title">#0032</span>'
        '<span class="sh-chip sh-chip--primary">备货中</span>'
        '<span class="txt-price sh-num" style="margin-inline-start:auto">¥46.80</span></div>'
        '<div class="sh-muted">土鸡蛋 20 枚 × 1 · 14:38 配送</div></div>'
        '<div class="sh-card empty" style="margin-top:8px">'
        '<span class="sh-muted">今天没有待处理的售后</span></div>'
        '<div class="sh-btn" style="margin-top:12px">全部接单</div>'
        '<div class="sh-btn sh-btn--danger" style="margin-top:8px">停止接单</div>')


TEMPLATE = """<title>UI 标准库</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wght@500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
:root{--sheet:#F4F3F0;--ink:#16161A;--muted:#6C6B66;--rule:#DFDDD8;--card:#fff;--accent:#B31710}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]){--sheet:#121214;--ink:#F2F1EE;
  --muted:#9C9A94;--rule:#2A2A2E;--card:#1D1D21;--accent:#FF7A6E}}
:root[data-theme=dark]{--sheet:#121214;--ink:#F2F1EE;--muted:#9C9A94;--rule:#2A2A2E;
  --card:#1D1D21;--accent:#FF7A6E}
*{box-sizing:border-box}
body{margin:0;background:var(--sheet);color:var(--ink);
  font-family:Archivo,"PingFang SC","Microsoft YaHei",sans-serif;-webkit-font-smoothing:antialiased}
.wrap{max-width:1180px;margin:0 auto;padding:52px 24px 100px}
header{border-bottom:2px solid var(--ink);padding-bottom:16px}
.eyebrow{font-family:"IBM Plex Mono",monospace;font-size:12px;letter-spacing:.14em;
  text-transform:uppercase;color:var(--muted)}
h1{font-size:clamp(26px,4vw,40px);margin:10px 0 6px;font-weight:700;text-wrap:balance}
.sub{color:var(--muted);font-size:14.5px;line-height:1.7;max-width:70ch;margin:0}
nav{position:sticky;top:0;z-index:5;background:var(--sheet);border-bottom:1px solid var(--rule);
  padding:10px 0;margin:26px 0 0;display:flex;gap:6px;flex-wrap:wrap}
nav a{font-size:12.5px;padding:4px 11px;border-radius:999px;text-decoration:none;
  color:var(--muted);border:1px solid var(--rule)}
nav a:hover{color:var(--ink);border-color:var(--ink)}
nav a:focus-visible{outline:2px solid var(--ink);outline-offset:2px}
section{margin-top:44px;scroll-margin-top:60px}
h2{font-size:21px;margin:0 0 6px;border-bottom:1px solid var(--rule);padding-bottom:8px;
  text-wrap:balance}
h3{font-size:13px;margin:20px 0 10px;color:var(--muted);font-weight:600;letter-spacing:.02em}
h3.grp{font-size:14px;color:var(--ink);display:flex;align-items:baseline;gap:8px;margin-top:28px}
.hint{color:var(--muted);font-size:13.5px;line-height:1.7;margin:0 0 16px;max-width:76ch}
code{font-family:"IBM Plex Mono",monospace;font-size:12px}
code.v{color:var(--muted)}
.n{font-family:"IBM Plex Mono",monospace;font-size:11.5px;color:var(--muted);font-weight:400}
.cols{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:26px}
.cols2{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:26px}
.sw{display:flex;align-items:center;gap:9px;padding:3px 0;font-size:12px}
.chipc{width:22px;height:22px;border-radius:6px;flex:none;border:1px solid var(--rule)}
.sw code:first-of-type{flex:1}
.skins{display:flex;flex-wrap:wrap;gap:8px}
.sk{display:inline-flex;align-items:center;gap:6px;font-size:12px;padding:4px 10px;
  border:1px solid var(--rule);border-radius:999px}
.sk i{width:12px;height:12px;border-radius:999px}
.tks{display:flex;flex-direction:column;gap:10px}
.tk{display:flex;align-items:center;gap:12px;font-size:12px}
.rbox{width:52px;height:34px;background:var(--accent);opacity:.85;flex:none}
.sbar{height:16px;background:var(--accent);opacity:.85;flex:none;border-radius:3px}
.tk code:first-of-type{min-width:3em}
/* 宽内容各自横向滚动，页面本体永远不横滚 */
.scroll{overflow-x:auto}
table.ts{width:100%;min-width:440px;border-collapse:collapse;font-size:13px;
  font-variant-numeric:tabular-nums}
table.ts th{text-align:left;font-size:11.5px;color:var(--muted);font-weight:600;
  padding:6px 10px;border-bottom:1px solid var(--rule)}
table.ts td{padding:8px 10px;border-bottom:1px solid var(--rule);vertical-align:middle}
td.samp{width:46%}
td.cnt{font-family:"IBM Plex Mono",monospace;font-size:12px;text-align:right;width:5em}
td.pl{font-size:11.5px;color:var(--muted);line-height:1.7}
/* 形态名与页面名不折行：折了之后「＋ 加一项按钮」会断成三行，扫不动 */
table.ts td:first-child{white-space:nowrap}
td.pills{line-height:2}
.pgt code{font-size:11.5px}
.ok,.gap,.dup,.clean,.gapb{display:inline-block;font-size:11px;padding:1px 7px;border-radius:999px;
  margin:0 4px 3px 0;white-space:nowrap}
.ok{background:var(--sheet);color:var(--muted);border:1px solid var(--rule)}
/* 两类不能只靠色相分 —— 一屏几百枚 pill，红与琥珀在小尺寸下几乎是同一个颜色。
   照设计语言自己那条：**靠形态分**（实底 vs 虚线描边），颜色只作辅助。 */
.gap,.gapb{background:rgba(225,37,27,.12);color:var(--accent)}
.dup{background:transparent;border:1px dashed #B08A3C;color:#8A6A2F}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]) .dup{color:#D9B45F;
  border-color:#8A6A2F}}
:root[data-theme=dark] .dup{color:#D9B45F;border-color:#8A6A2F}
.clean{background:rgba(27,127,75,.12);color:#1B7F4B}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]) .clean{color:#4ED08A}}
:root[data-theme=dark] .clean{color:#4ED08A}
.pair{display:flex;gap:22px;flex-wrap:wrap;margin-top:16px}
figure{margin:0}
figcaption{font-size:12px;color:var(--muted);margin-top:8px;text-align:center}
.bks{display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:12px}
.bk,.cp{background:var(--card);border:1px solid var(--rule);border-radius:10px;overflow:hidden;
  display:flex;flex-direction:column}
.bk__demo{padding:14px;border-bottom:1px solid var(--rule);display:flex;align-items:center;
  justify-content:center;min-height:74px}
/* 积木样张坐在**应用自己的底色**上，不跟着本页主题走 ——
   这一格展示的是 app 的观感，不是文档的观感；透明会让 .sh-muted 在深色文档里读不清 */
.bk__demo>.up{width:100%;background:var(--sh-bg);border-radius:8px;padding:10px}
.bk__meta,.c__meta{padding:11px 13px 13px}
.bk__h,.c__h{display:flex;align-items:baseline;gap:8px;flex-wrap:wrap}
code.cn{font-size:12.5px;font-weight:500;color:var(--accent)}
.use{margin-left:auto;font-family:"IBM Plex Mono",monospace;font-size:11px;color:var(--muted)}
.zero{color:#fff;background:var(--accent);border-radius:4px;padding:0 5px;font-size:10px}
.tag2{font-size:10.5px;padding:1px 7px;border-radius:999px;border:1px solid var(--rule);
  color:var(--muted)}
p.when{margin:7px 0 3px;font-size:12.5px;line-height:1.6}
p.avoid{margin:0;font-size:11.5px;line-height:1.6;color:var(--muted)}
.decl{margin-top:9px;padding-top:8px;border-top:1px dashed var(--rule);line-height:1.75}
.decl .px{font-family:"IBM Plex Mono",monospace;font-size:11px;color:var(--accent);opacity:.75}
.cp{display:grid;grid-template-columns:auto 1fr;align-items:start}
/* 样张固定 375 宽（那是画布本身，不能缩），窄屏下让**它自己**横向滚，页面本体不动 */
.c__demo{padding:14px;border-right:1px solid var(--rule);overflow-x:auto}
.c__demo--none{color:var(--muted);font-size:12px;width:180px;display:flex;align-items:center;
  line-height:1.6}
@media (max-width:760px){
  .cp{grid-template-columns:1fr}
  .c__demo{border-right:none;border-bottom:1px solid var(--rule)}
  .c__demo--none{width:auto}
}
ul.props{list-style:none;margin:9px 0 0;padding:0;display:flex;flex-wrap:wrap;gap:5px}
ul.props li{background:var(--sheet);border-radius:5px;padding:2px 7px}
code.path{display:block;margin-top:9px;font-size:10.5px;color:var(--muted);opacity:.75}
footer{margin-top:80px;border-top:1px solid var(--rule);padding-top:16px;
  font-family:"IBM Plex Mono",monospace;font-size:12px;color:var(--muted);line-height:1.9}

/* ── 以下全部来自真源，rpx 已折半成 px（375pt 画布）。不要手改这一段 ── */
{{CSS}}
/* ── 原型页自己的修正：必须排在真源 CSS 之后 ──
   组件的 scoped 样式带着 fixed / 100vh，那是给真机整屏用的；
   收进这一页的 375 画布里要解掉，选择器还得比 `.up .xxx` 更具体，否则压不过。 */
.up{width:375px;font-size:14px;line-height:1.55;background:var(--sh-bg);color:var(--sh-ink);
  font-family:Inter,-apple-system,"PingFang SC","Noto Sans SC",sans-serif;border-radius:10px;
  overflow:hidden}
.up .sh-scaffold.sh-scaffold{min-height:0;padding-bottom:0}
.up .ic{display:inline-block;flex-shrink:0;-webkit-mask-repeat:no-repeat;mask-repeat:no-repeat;
  -webkit-mask-position:center;mask-position:center;-webkit-mask-size:contain;mask-size:contain}
.up .tabbar.tabbar--demo{position:static;max-width:none;padding-bottom:7px}
.up .bar.bar--demo{position:static;padding-bottom:10px}
.up .add{display:inline-flex;margin:0 6px 6px 0}
.up .sheet-demo{position:relative;height:210px;background:var(--sh-scrim);border-radius:8px;
  overflow:hidden}
.up .sheet-demo .sheet__panel{position:absolute;left:0;right:0;bottom:0;padding-bottom:16px}
.up .icons{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}
.up .ico{display:flex;flex-direction:column;align-items:center;gap:4px}
.up .ico code{font-size:9px;color:var(--sh-sub)}
.up .ico__b{display:flex;align-items:center;justify-content:center;width:38px;height:38px;
  border-radius:12px;background:var(--sh-faint)}
</style>
<div class="wrap">
<header>
  <div class="eyebrow">ai-shop · UI 标准库 · 由真源生成</div>
  <h1>UI 标准库清单与原型</h1>
  <p class="sub">令牌 · {{NB}} 个公共积木 · {{NC}} 个组件 · B 端 {{NP}} 页逐页覆盖。
  <b>这一页里的每一个色块、每一个圆角、每一条声明，都是从代码里读出来再渲染的</b> ——
  不存在「照着规范画」这一步，所以它不会与规范不一致。<br>
  画布按 <b>375pt</b>（<code>1rpx = 0.5px</code>），皮肤 <code>brand</code>（虹选红，B 端默认）。<br>
  重新生成：<code>python3 scripts/gen-ui-lib.py</code></p>
</header>
<nav>{{NAV}}</nav>
{{BODY}}
<footer>真源：tokens.ts（圆角/间距/皮肤）· icons.ts（图标）· base.css（皮肤变量 + 积木）·
b-app/App.vue（B 端密度）· packages/ui 与 b-app 的组件<br>
唯一手写的是每条的「什么时候用」与样例的<b>结构</b>；<b>样式一行都没写</b> ——
样例长什么样由真源的 CSS 决定<br>
用量 B/C = 该类名或组件在两端模板里出现的次数。标「未被引用」的是清单里有、代码里没人用的
</footer>
</div>
"""


def main() -> None:
    check = "--check" in sys.argv
    cat = build()
    fresh = json.dumps(cat, ensure_ascii=False, indent=2) + "\n"

    if check:
        old = OUT_JSON.read_text(encoding="utf-8") if OUT_JSON.exists() else ""
        if old != fresh:
            print("✗ UI 标准库清单过期了：令牌/积木/组件动过，但 ui-lib.json 没跟上。", file=sys.stderr)
            print("  跑一下：python3 scripts/gen-ui-lib.py（然后把 JSON 一起提交）", file=sys.stderr)
            _diff(json.loads(old) if old else {}, cat)
            sys.exit(1)
        print(f"✓ UI 标准库清单是最新的（{cat['counts']['blocks']} 个积木 / "
              f"{cat['counts']['components']} 个组件）")
        return

    base, dens, comps, icons = read_base(), read_density(), read_components(), read_icons()
    OUT_JSON.write_text(fresh, encoding="utf-8")
    OUT_HTML.write_text(render(cat, base, comps, dens, icons), encoding="utf-8")
    print(f"{OUT_JSON.relative_to(ROOT)}: {cat['counts']['blocks']} 个积木 · "
          f"{cat['counts']['components']} 个组件 · "
          f"{len(cat['tokens']['radius'])} 档圆角 / {len(cat['tokens']['spacing'])} 档间距 / "
          f"{len(cat['tokens']['type'])} 档字号")
    unused = [b["class"] for b in cat["blocks"]
              if b["usage"]["b-app"] + b["usage"]["c-app"] == 0]
    if unused:
        print(f"  ⚠ 清单里有、代码里没人用：{', '.join(unused)}")
    print(f"  B 端 {cat['counts']['pages']} 页扫过：{cat['counts']['gapKinds']} 类形态库里没有")
    for g in cat["gaps"]:
        if g["gap"]:
            print(f"    缺 {g['label']:<12} {len(g['pages']):>2} 页各造一份")
    print(f"{OUT_HTML.relative_to(ROOT)}: 原型页（可直接发布成 Artifact）")


def _diff(old: dict, new: dict) -> None:
    """只说「不一致」的闸门，人只会去跳过它 —— 差在哪儿要直接说出来。"""
    def flat(c: dict) -> dict[str, str]:
        out = {}
        for b in c.get("blocks", []):
            out[b["class"]] = json.dumps(b["decl"], ensure_ascii=False, sort_keys=True)
        for k, v in c.get("tokens", {}).get("radius", {}).items():
            out[f"radius.{k}"] = v["rpx"]
        for k, v in c.get("tokens", {}).get("spacing", {}).items():
            out[f"spacing.{k}"] = v["rpx"]
        for comp in c.get("components", []):
            out[f"<{comp['name']}>"] = ",".join(comp["props"])
        return out
    o, n = flat(old), flat(new)
    for k in sorted(n.keys() - o.keys()):
        print(f"  + 新增 {k}", file=sys.stderr)
    for k in sorted(o.keys() - n.keys()):
        print(f"  - 删除 {k}", file=sys.stderr)
    for k in sorted(o.keys() & n.keys()):
        if o[k] != n[k]:
            print(f"  ~ 改了 {k}：{o[k]} → {n[k]}", file=sys.stderr)


if __name__ == "__main__":
    main()

# 产物放哪：JSON 入库（它是清单本身，也是 --check 的判据），
# `.page.html` 与 ui-catalog 同理进不了库（`docs/**/*.html` 被 gitignore）——
# HTML 是发布用的一次性产物，随时能从真源重新渲染。
