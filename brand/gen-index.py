#!/usr/bin/env python3
"""品牌交付物总览 —— 生成 brand/index.html。

它是**目录页**，不是第二份规范：每一项只给缩略图、一句用途、以及指向真正那份文件的链接。
清单从文件系统扫出来，不手写 —— 手写的清单会和实际脱节，而这种脱节没人会发现，
直到有人照着清单去找一个不存在的文件。

用法：python3 brand/gen-index.py && open brand/index.html
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
REPO = ROOT.parent

TOK = json.loads((ROOT / "tokens.json").read_text())["brand"]
RED, INK, MUTED = TOK["red"]["value"], TOK["ink"]["value"], "#63676E"
LINE, PANEL = "#E5E7EA", "#F5F6F8"

# ── 版本记录。**保留作废的那一版** —— 不写下来，半年后有人会重新提虹橙。
VERSIONS = [
    ("v0.9", "2026-08-13", "虹橙 G4", "作废",
     "主色 #BF4D1C，高横 H，无中文字标、无母子品牌、无申报单元。"
     "它只是一套图标，不是一套体系。"),
    ("v1.0", "2026-08-19", "红 + 弧线母题", "被 v1.1 取代",
     "接管 hxmall 体系：主色 #E1251B、一道弧线（取「虹」）、H/X 同源自绘字形、"
     "四级字符层级、母子品牌、五个商标申报单元。缺陷：build.py 只实现了一档几何。"),
    ("v1.1", "2026-08-20", "分档 + 扁弧 + 高横", "当前",
     "几何按规范分档落地（H / HX / 虹 / 虹选）；弧压扁 arc_flat 0.65；"
     "H 横画回到 0.38（与原始素材一致）；新增虹选双字方章；"
     "补齐启动页、通知图标、小程序、官网图标、商标申报稿。"),
]

# ── 交付物分组。(标题, 说明, 类型, [(路径, 说明, 展示尺寸)])
#    类型 formal = 可直接用；compare = 比选与过程稿，**不要拿去投产**
def logos():
    order = ["icon-c.svg", "icon-b.svg", "icon-tech.svg", "cn1-red.svg", "cn2-red.svg",
             "mark-red.svg", "mark-ink.svg", "mark-reverse.svg",
             "adaptive-fg-c.svg", "adaptive-bg-c.svg", "adaptive-fg-b.svg", "adaptive-bg-b.svg"]
    note = {
        "icon-c.svg": "C 端满幅方章", "icon-b.svg": "B 端满幅方章",
        "icon-tech.svg": "母品牌 · 墨底 + 亮红弧", "cn1-red.svg": "「虹」单字方章",
        "cn2-a-red.svg": "虹选双字 · <b>甲</b>：弧只压「虹」",
        "cn2-b-red.svg": "虹选双字 · <b>乙</b>：弧横跨两字",
        "cn2-a-ink.svg": "甲 · 母品牌配色", "cn2-b-ink.svg": "乙 · 母品牌配色",
        "mark-red.svg": "主色字标（透明底）",
        "mark-ink.svg": "墨色 · 单色稿", "mark-reverse.svg": "反白 · 深底用",
        "adaptive-fg-c.svg": "Android 前景 C", "adaptive-bg-c.svg": "Android 背景 C",
        "adaptive-fg-b.svg": "Android 前景 B", "adaptive-bg-b.svg": "Android 背景 B",
    }
    return [(f"logo/{n}", note.get(n, ""), 88) for n in order if (ROOT / "logo" / n).exists()]


def wordmarks():
    n = {"wordmark-hongxuan.svg": "虹选 · 核心字标",
         "wordmark-hongxuan-haowu.svg": "虹选 · 好物 · 对外全称",
         "wordmark-hongxuan-tech.svg": "虹选科技 · 母品牌",
         "domain-hxmall.svg": "域名字标", "domain-hxtech.svg": "母品牌域名"}
    return [(f"logo/{k}", v, 200) for k, v in n.items() if (ROOT / "logo" / k).exists()]


def globs(rel, pat, note="", w=72):
    d = ROOT / rel if (ROOT / rel).exists() else REPO / rel
    return [(str(p.relative_to(ROOT)) if str(p).startswith(str(ROOT))
             else "../" + str(p.relative_to(REPO)), note or p.name, w)
            for p in sorted(d.glob(pat))]


GROUPS = [
    ("标识矢量", "brand/logo/ · 全部由 build.py 派生，<b>不要手改单个文件</b>", "formal", logos()),
    ("字标与域名", "含中文，交印刷厂或商标申报前<b>必须转轮廓</b>", "formal", wordmarks()),
    ("App 图标 · 位图", "iOS 1024 不得带 alpha 通道，带了 App Store 直接拒", "formal",
     [("ios/AppIcon-c.appiconset/icon-1024.png", "iOS · C 端 1024", 96),
      ("ios/AppIcon-b.appiconset/icon-1024.png", "iOS · B 端 1024", 96),
      ("../android-shell/app/src/consumer/res/mipmap-xxxhdpi/ic_launcher.png", "Android C · 192", 72),
      ("../android-shell/app/src/merchant/res/mipmap-xxxhdpi/ic_launcher.png", "Android B · 192", 72),
      ("store/play-c-512.png", "Play · C 端 512", 72),
      ("store/play-b-512.png", "Play · B 端 512", 72)]),
    ("小程序", "微信端自动裁圆，源不预切圆角", "formal",
     [("store/mp-c-hx-144.png", "C 端 144", 64),
      ("store/mp-b-hx-144.png", "B 端 144", 64),
      ("store/mp-c-cn-144.png", "C 端中文版 144", 64)]),
    ("官网 site/public/", "唯一对外的公开入口。缺分享图 = 发链接没有缩略图", "formal",
     [("../site/public/favicon.svg", "favicon.svg", 56),
      ("../site/public/apple-touch-icon.png", "触屏 180", 56),
      ("../site/public/icon-512.png", "PWA 512", 56),
      ("../site/public/share-300.png", "微信分享 300²", 56),
      ("../site/public/og.png", "OG 卡片 1200×630", 200)]),
    ("商标申报稿", "纯黑实心 / 白底 / 无渐变无阴影无描边。提交前转轮廓、另存 JPG", "formal",
     globs("trademark", "*-512.png", w=68)),
    ("母品牌官网 hxtech.top", "墨底 + 亮红弧。母品牌不复用子业务的红方章 —— "
                             "否则合同抬头与商城 App 长得一样，对公场合分不出主体", "formal",
     [("dist/site-hxtech/favicon.svg", "favicon.svg", 56),
      ("dist/site-hxtech/apple-touch-icon.png", "触屏 180", 56),
      ("dist/site-hxtech/share-300.png", "微信分享 300²", 56),
      ("dist/site-hxtech/og.png", "OG 卡片 1200×630", 200)]),
    ("名片", "<b>模板 + 数据表</b>：改 <code>brand/print/people.csv</code> 重跑 "
             "<code>gen-print.py</code>。90×54mm，出血 3mm，安全区 5mm。"
             "预览稿带裁切线与安全区，<b>不要交印厂</b>", "formal",
     [("dist/print/cards/sample-mall-front.svg", "hxmall 正面", 190),
      ("dist/print/cards/sample-mall-back.svg", "hxmall 背面", 190),
      ("dist/print/cards/sample-tech-front.svg", "hxtech 正面", 190),
      ("dist/print/cards/sample-tech-back.svg", "hxtech 背面（墨底）", 190),
      ("dist/print/cards/sample-mall-front-preview.svg", "预览稿 · 带辅助线", 190)]),
]


DIST_NOTE = {
    "c-app": "C 端 App 与 H5",
    "b-app": "B 端 App 与 H5",
    "mini-program": "微信小程序（两端 + 中文版）",
    "ops-web": "运营端",
    "site-hxmall": "<b>子业务官网 hxmall.top</b> · 白底 + 红方章",
    "site-hxtech": "<b>母品牌官网 hxtech.top</b> · 墨底 + 亮红弧",
    "print": "印刷物料、名片（<code>cards/</code>）",
    "trademark": "商标申报",
}


def read(p):
    q = ROOT / p if not p.startswith("..") else REPO / p[3:]
    return q.exists()


CSS = f"""
:root{{--red:{RED};--ink:{INK};--muted:{MUTED};--line:{LINE};--panel:{PANEL};
--sans:"Figtree","PingFang SC","Noto Sans SC",system-ui,sans-serif}}
*,*::before,*::after{{box-sizing:border-box}}
body{{margin:0;background:#fff;color:var(--ink);font-family:var(--sans);font-size:16px;line-height:1.65;-webkit-font-smoothing:antialiased}}
.wrap{{max-width:1080px;margin:0 auto;padding:0 clamp(20px,5vw,48px)}}
h1,h2,h3{{margin:0;line-height:1.2;font-weight:700;letter-spacing:-.01em}}
p{{margin:0}}
a{{color:var(--red);text-decoration:none}}a:hover{{text-decoration:underline}}
code{{font-family:ui-monospace,Menlo,monospace;font-size:.88em;background:var(--panel);padding:1px 6px;border-radius:5px}}
header{{background:var(--red);color:#fff;padding:clamp(38px,6vw,64px) 0 clamp(30px,5vw,48px)}}
header .k{{font-size:12px;letter-spacing:.22em;text-transform:uppercase;opacity:.82}}
header h1{{font-size:clamp(28px,4.2vw,44px);margin-top:12px}}
header p{{margin-top:14px;max-width:60ch;color:rgba(255,255,255,.88)}}
header code{{background:rgba(255,255,255,.18);color:#fff}}
nav.toc{{position:sticky;top:0;z-index:5;background:rgba(255,255,255,.93);backdrop-filter:blur(10px);border-bottom:1px solid var(--line)}}
nav.toc .wrap{{display:flex;gap:20px;overflow-x:auto;padding-top:13px;padding-bottom:13px;font-size:14px}}
nav.toc a{{color:var(--muted);white-space:nowrap}}nav.toc a:hover{{color:var(--red);text-decoration:none}}
section{{padding:clamp(34px,4.5vw,56px) 0;border-bottom:1px solid var(--line)}}
.k2{{font-size:11.5px;letter-spacing:.16em;text-transform:uppercase;color:var(--muted);margin-bottom:9px}}
h2{{font-size:clamp(19px,2.4vw,26px)}}
.lede{{color:var(--muted);max-width:66ch;margin-top:10px;font-size:15px}}
table{{width:100%;border-collapse:collapse;font-size:14px;background:#fff;border:1px solid var(--line);margin-top:20px}}
th{{text-align:left;font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:var(--muted);background:var(--panel);padding:10px 12px;border-bottom:1px solid var(--line);font-weight:600}}
td{{padding:10px 12px;border-bottom:1px solid var(--line);vertical-align:top}}
tbody tr:last-child td{{border-bottom:0}}
.badge{{display:inline-block;font-size:11px;font-weight:700;padding:2px 8px;border-radius:999px;white-space:nowrap}}
.cur{{background:#e7f7ef;color:#05663a}}.old{{background:var(--panel);color:var(--muted)}}
.dead{{background:#fdeceb;color:#b31710}}
.sheet{{display:flex;flex-wrap:wrap;gap:20px;margin-top:22px;align-items:flex-end}}
figure{{margin:0;text-align:center}}
figure img{{display:block;background:var(--panel);border:1px solid var(--line);border-radius:10px}}
/* 反白件是白图形透明底，压在浅面板上等于隐形 —— 缩略图看起来像坏文件。给它深底。 */
figure.onDark img{{background:var(--ink);border-color:var(--ink)}}
figcaption{{font-size:11.5px;color:var(--muted);margin-top:7px;max-width:150px}}
.grp{{margin-top:30px}}
.grp h3{{font-size:15px}}
.grp .note{{font-size:13.5px;color:var(--muted);margin-top:5px}}
.cmp{{background:var(--panel);border-left:3px solid var(--red);padding:16px 20px;margin-top:22px;font-size:14.5px}}
.cards{{display:grid;grid-template-columns:repeat(2,1fr);gap:16px;margin-top:22px}}
@media(max-width:700px){{.cards{{grid-template-columns:1fr}}}}
.card{{border:1px solid var(--line);border-radius:12px;padding:18px 20px}}
.card h3{{font-size:15px}}.card p{{font-size:13.5px;color:var(--muted);margin-top:7px}}
footer{{padding:30px 0 52px;color:var(--muted);font-size:13px}}
"""


def sheet(items):
    out = ""
    for path, note, w in items:
        if not read(path):
            continue
        # 反白件是白图形透明底，压在浅面板上等于隐形 —— 缩略图看起来像坏文件
        dark = "onDark" if any(k in path for k in ("mark-reverse", "adaptive-fg", "-inv-")) else ""
        out += (f'<figure class="{dark}"><a href="{path}" target="_blank" rel="noopener">'
                f'<img src="{path}" width="{w}" loading="lazy"></a>'
                f'<figcaption>{note}<br><a href="{path}" target="_blank" rel="noopener">'
                f'<code>{path.replace("../", "")}</code></a></figcaption></figure>')
    return out


def build():
    P = [f"<style>{CSS}</style>"]
    cur = VERSIONS[-1][0]

    P.append(f"""<header><div class="wrap">
      <div class="k">Brand deliverables · 交付物总览</div>
      <h1>虹选 · 好物 / HX MALL 品牌交付物 {cur}</h1>
      <p>本页是<b>目录</b>，不是第二份规范 —— 每项只给缩略图、用途与链接，
      细节回到各自那份文件。清单从文件系统扫出来，不手写。
      真源是 <code>brand/build.py</code>，<b>勿手改任何单个产物</b>。</p>
    </div></header>""")

    P.append("""<nav class="toc"><div class="wrap">
      <a href="#start">从哪儿开始</a><a href="#ver">版本记录</a>
      <a href="#formal">正式交付物</a><a href="#ends">各端方案</a><a href="#dist">按端分发</a><a href="#compare">比选与过程稿</a>
      <a href="#pending">待定</a><a href="#upstream">上游素材</a>
    </div></nav>""")

    # L1：三行入口
    P.append("""<section id="start"><div class="wrap">
      <div class="k2">L1 — Start here</div>
      <h2>三个入口，按你要做什么选</h2>
      <div class="cards">
        <div class="card"><h3>要用标识 → <a href="spec.html">brand/spec.html</a></h3>
          <p>视觉规范全文：色值与对比度、六条几何规则、参数表、三档字符、各端制版、禁止事项。</p></div>
        <div class="card"><h3>要改参数 → <a href="build.py">brand/build.py</a></h3>
          <p>唯一真源。改顶部 <code>GEO</code> / <code>TIERS</code> 后重跑，全端产物一次重生成。</p></div>
        <div class="card"><h3>要看为什么 → <a href="icon-proposal.html">icon-proposal.html</a></h3>
          <p>几何定档的三轮比选：字高与弧宽、弧的扁平度、H 横画高度，每轮都有真实像素对照。</p></div>
        <div class="card"><h3>要全局方案 → <a href="../docs/technical/design/品牌方案-总纲.md">品牌方案-总纲</a></h3>
          <p>定位、色形字、各端落点、商标申报、待定项与落地清单。</p></div>
      </div>
    </div></section>""")

    # L2：版本
    rows = ""
    for v, date, name, status, desc in reversed(VERSIONS):
        cls = "cur" if status == "当前" else ("dead" if status == "作废" else "old")
        rows += (f'<tr><td><b>{v}</b></td><td>{date}</td><td>{name}</td>'
                 f'<td><span class="badge {cls}">{status}</span></td><td>{desc}</td></tr>')
    P.append(f"""<section id="ver"><div class="wrap">
      <div class="k2">L2 — Versions</div>
      <h2>版本记录</h2>
      <p class="lede"><b>作废的那一版也留着。</b>不写下来，半年后会有人重新提虹橙，
      而没人记得当初为什么否掉。</p>
      <table><thead><tr><th>版本</th><th>日期</th><th>要点</th><th>状态</th><th>变了什么</th></tr></thead>
      <tbody>{rows}</tbody></table>
    </div></section>""")

    # L3：正式交付物
    body, total = "", 0
    for title, note, kind, items in GROUPS:
        have = [i for i in items if read(i[0])]
        total += len(have)
        body += (f'<div class="grp"><h3>{title} '
                 f'<span style="color:var(--muted);font-weight:400">· {len(have)} 项</span></h3>'
                 f'<p class="note">{note}</p><div class="sheet">{sheet(items)}</div></div>')
    P.append(f"""<section id="formal"><div class="wrap">
      <div class="k2">L3 — Formal</div>
      <h2>正式交付物 · {total} 项</h2>
      <p class="lede">下面这些可以直接用。色板另见
      <code>tokens.json</code> / <code>tokens.css</code> / <code>tokens.ts</code>；
      启动页与通知图标是 Android XML，不出缩略图，落在
      <code>android-shell/app/src/main/res/</code>。</p>
      {body}
    </div></section>""")

    # L3：各端方案
    ENDS = [
        ("C 端 · 虹选好物", "红底 + 白 HX + 白弧", "HX / H / 虹选",
         "dist/c-app", [("logo/icon-c.svg", "App 图标", 84),
                        ("../c-app/public/favicon.svg", "favicon", 56),
                        ("store/mp-c-hx-144.png", "小程序方形", 56),
                        ("store/mp-c-cn-a-144.png", "小程序中文 · 甲", 56),
                        ("store/mp-c-cn-b-144.png", "小程序中文 · 乙", 56)]),
        ("B 端 · 虹选商家", "底色<b>待定</b>（现状同 C 端红底）", "HX / H",
         "dist/b-app", [("logo/icon-b.svg", "App 图标", 84),
                        ("../b-app/public/favicon.svg", "favicon", 56),
                        ("store/mp-b-hx-144.png", "小程序方形", 56),
                        ("store/play-b-512.png", "Play 商店图", 56)]),
        ("运营端 · ops-web", "沿用 B 端那副冷静的脸", "HX / H",
         "dist/ops-web", [("../ops-web/public/favicon.svg", "favicon", 56),
                          ("../ops-web/public/apple-touch-icon.png", "触屏图标", 56),
                          ("../ops-web/public/icon-512.png", "PWA 512", 56)]),
        ("官网 · hxmall.top", "白底 + 红方章", "HX + 横向字标",
         "dist/site-hxmall", [("../site/public/favicon.svg", "favicon", 56),
                              ("../site/public/share-300.png", "微信分享 300²", 56),
                              ("../site/public/og.png", "OG 1200×630", 170)]),
        ("母品牌 · hxtech.top", "<b>墨底 + 亮红弧</b>：不复用子业务的红方章", "HX + 虹选科技字标",
         "dist/site-hxtech", [("logo/icon-tech.svg", "母品牌方章", 84),
                              ("dist/site-hxtech/share-300.png", "微信分享 300²", 56),
                              ("dist/site-hxtech/og.png", "OG 1200×630", 170)]),
        ("印刷 · 名片", "按主体切配色：hxmall 红底 / hxtech 墨底", "HX + 字标",
         "dist/print", [("dist/print/cards/sample-mall-front.svg", "hxmall 正面", 160),
                        ("dist/print/cards/sample-mall-back.svg", "hxmall 背面", 160),
                        ("dist/print/cards/sample-tech-back.svg", "hxtech 背面", 160)]),
    ]
    cards_html = ""
    for title, tone, tiers, dd, items in ENDS:
        cards_html += (f'<div class="grp"><h3>{title}</h3>'
                       f'<p class="note">配色：{tone} · 用档：{tiers} · '
                       f'取素材 → <a href="{dd}/" target="_blank"><code>brand/{dd}/</code></a></p>'
                       f'<div class="sheet">{sheet(items)}</div></div>')
    P.append(f"""<section id="ends"><div class="wrap">
      <div class="k2">L3 — Per endpoint</div>
      <h2>各端方案</h2>
      <p class="lede">同一套几何，各端只在<b>配色</b>与<b>用哪一档字符</b>上不同。
      缩略图可点，直接打开文件；每端右上给出它的分发目录。</p>
      {cards_html}
      <div class="cmp"><b>C 端与 B 端目前几乎一样</b>，只靠桌面名区分（虹选好物 / 虹选商家）——
      这是待定项 1 的直接后果，不是疏漏。规范 §4.5 给 B 端定的是深板岩 + 亮红弧，
      桌面上一眼分得开。</div>
    </div></section>""")

    # L3：按端分发
    ends = [(d.name, len([f for f in d.iterdir() if f.is_file() and f.name != "README.md"]))
            for d in sorted((ROOT / "dist").iterdir()) if d.is_dir()]
    rows_d = "".join(f'<tr><td><code>brand/dist/{n}/</code></td><td class="n">{c}</td>'
                     f'<td>{DIST_NOTE.get(n, "")}</td></tr>' for n, c in ends)
    P.append(f"""<section id="dist"><div class="wrap">
      <div class="k2">L3 — Distribution</div>
      <h2>按端分发：各端从固定路径取</h2>
      <p class="lede"><code>brand/dist/&lt;端&gt;/</code> 每个目录带一份 README，说明里面是什么、
      有哪些坑。<b>它是生成的镜像，不是第二处真源</b> —— 改参数重跑就全部刷新，
      往里手放文件下次会被清掉。</p>
      <table><thead><tr><th>目录</th><th>文件数</th><th>谁用</th></tr></thead>
      <tbody>{rows_d}</tbody></table>
      <div class="cmp"><b>小程序、印刷、母品牌官网三组只有 dist 一个家</b> ——
      仓库里没有对应的工程目录，不像 c-app / b-app / ops-web / site 那样有
      <code>public/</code> 可放。</div>
    </div></section>""")

    # L3：比选
    P.append("""<section id="compare"><div class="wrap">
      <div class="k2">L3 — Alternatives</div>
      <h2>比选与过程稿</h2>
      <p class="lede"><b>这些不要拿去投产。</b>留着是为了让「为什么是这个」可追 ——
      只写结论不写被否掉的选项，半年后同一个方案会被重新提一遍。</p>
      <table><thead><tr><th>文件</th><th>是什么</th><th>结论</th></tr></thead><tbody>
        <tr><td><a href="icon-proposal.html">icon-proposal.html</a></td>
            <td>几何三轮比选 + 43 项场景矩阵</td>
            <td>字高 0.30 / 弧宽 0.44 / 扁 0.65 / 横画 0.38</td></tr>
        <tr><td><a href="../site/design/home.html">site/design/home.html</a></td>
            <td>官网首页静态设计稿（08-19，面向<b>顾客</b>的七屏）</td>
            <td><b>已过时</b> —— 官网 08-20 改版为面向<b>商家</b>的十屏。
                留作版式与组件的来源参考，<b>内容与线上不一致，不要照着它做</b></td></tr>
        <tr><td><a href="../docs/technical/design/品牌工程与官网方案.md">品牌工程与官网方案.md</a></td>
            <td>v0.9 时期的方案</td>
            <td>§3 品牌色与 §4.2 Logo <b>已作废</b>；命名署名、资质待办仍有效</td></tr>
        <tr><td><a href="../docs/technical/design/视觉设计方案-全项目.md">视觉设计方案-全项目.md</a></td>
            <td>四套并存体系的收口决策</td>
            <td>确立 hxmall 红为唯一真源</td></tr>
      </tbody></table>
      <div class="cmp"><b>三轮比选的结论各是什么：</b>
      ① 字高 0.26→0.30、弧宽 0.50→0.44 —— 原比例下字只比弧高一点点，远看是「一顶帽子下面有点东西」；
      ② 弧压扁到 0.65 —— 横向不变、纵向省 31%，再扁到 0.50 就读成一条横线；
      ③ H 横画 0.38 —— <b>这不是新设计</b>，原始素材量出来就是 0.380，是实现把它写死在正中才走样。</div>
    </div></section>""")

    # L4：待定
    P.append("""<section id="pending"><div class="wrap">
      <div class="k2">L4 — Open</div>
      <h2>三个待定项</h2>
      <p class="lede">当前产物按「最不推翻既有决定」的默认值出的，各是一个常量，随时可翻。</p>
      <div class="sheet">
        <figure><img src="logo/icon-c.svg" width="96"><figcaption>C 端</figcaption></figure>
        <figure><img src="logo/icon-b.svg" width="96"><figcaption>B 端 · 现状（红底）</figcaption></figure>
        <figure><a href="logo/cn2-a-red.svg" target="_blank"><img src="logo/cn2-a-red.svg" width="96"></a>
          <figcaption><b>甲</b> · 弧只压「虹」（当前默认）</figcaption></figure>
        <figure><a href="logo/cn2-b-red.svg" target="_blank"><img src="logo/cn2-b-red.svg" width="96"></a>
          <figcaption><b>乙</b> · 弧横跨两字（HX 布局）</figcaption></figure>
      </div>
      <table><thead><tr><th>待定</th><th>当前取值</th><th>翻过去要改什么</th><th>关键取舍</th></tr></thead><tbody>
        <tr><td><b>B 端图标底色</b></td><td>主色红（08-19 拍板）</td>
            <td><code>APPS["b"]</code> 的 <code>plate</code> → <code>PLATE_B</code>、
                <code>arc</code> → <code>RED_BRIGHT</code>，重跑</td>
            <td>两端现在几乎一样，只靠桌面名区分；商家手机上两个 App 都装是常态</td></tr>
        <tr><td><b>虹选双字章的弧</b></td><td>甲 · 只压「虹」</td>
            <td><code>cn_body()</code> 的弧心与弧宽改按整组算</td>
            <td>选乙要同时改写规范 §04，否则方章与横向字标两种规则并存</td></tr>
        <tr><td><b>H 宽高比</b></td><td>0.80</td>
            <td><code>GEO["h_ratio"]</code></td>
            <td>原始素材量出来是 <b>0.977</b>（近正方），生成的 H 比素材窄两成。
                改它会明显改变观感</td></tr>
      </tbody></table>
    </div></section>""")

    P.append("""<section id="upstream"><div class="wrap">
      <div class="k2">L4 — Upstream</div>
      <h2>上游素材（未入库）</h2>
      <p class="lede"><code>/Users/robin/project/hxmall</code> —— 标识体系的来源：
      官网离线全站 HTML、两份 PPT（介绍 / 标识体系）、21 个原始 SVG。
      本仓库的几何参数是从这些素材反推的，<b>它们不在版本控制里</b>，
      机器换了就找不回来 —— 建议归档进仓库或对象存储。</p>
    </div></section>""")

    P.append("""<footer><div class="wrap">
      品牌交付物总览 · 由 brand/gen-index.py 生成，勿手改 · 真源 brand/build.py
    </div></footer>""")

    return ('<!doctype html>\n<html lang="zh-CN"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>品牌交付物总览 · 虹选</title></head><body>'
            + "".join(P) + "</body></html>\n")


if __name__ == "__main__":
    out = ROOT / "index.html"
    out.write_text(build(), encoding="utf-8")
    n = sum(len([i for i in items if read(i[0])]) for _, _, _, items in GROUPS)
    print(f"总览 → {out}\n  正式交付物 {n} 项 · 当前版本 {VERSIONS[-1][0]}")
