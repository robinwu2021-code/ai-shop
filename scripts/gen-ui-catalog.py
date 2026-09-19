#!/usr/bin/env python3
"""把三端的界面扫成一份清单（JSON + 可发布的 HTML）。

用法：
  python3 scripts/gen-ui-catalog.py            重新生成
  python3 scripts/gen-ui-catalog.py --check    只校验（pre-push 闸门用；不一致就退出 1）

来源都是**代码里已有的真源**，不手工维护第二份：
  · b-app / c-app  → `src/pages.json`（路由 + 导航栏标题 + tabBar）
  · ops-web        → `lib/nav.ts`（模块 → 子功能，带权限码与矩阵编号）
  · 原型 → prototypes/registry.json（每一屏挂的端与路由；路由还没建的列成「原型」行）

为什么不手工列：手工清单第二周就会漏。凡是加了一页而清单没变的，
都说明清单该重新生成 —— 所以它必须能一条命令跑出来。
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT_JSON = ROOT / "docs/technical/design/ui-catalog.json"
OUT_HTML = ROOT / "docs/technical/design/ui-catalog.page.html"  # 见文件末尾的说明

# 路由前缀 → 功能域。加了新页面而它落进「其它」，就该在这里补一行
DOMAINS = [
    ("工作台", ["home"]),
    ("订单与履约", ["orders", "order", "verify", "picking", "delivery", "after-sale",
                     "cart", "checkout", "pay", "order-confirm"]),
    ("商品", ["goods", "goods-list", "goods-edit", "goods-publish", "goods-detail", "search", "category", "my-specs", "store-categories"]),
    ("门店", ["stores", "store", "store-notice", "store-scope", "store-pick", "qualifications"]),
    ("会员与营销", ["customers", "marketing", "coupons", "member", "cards", "members",
                    "member-detail",
                    "member-tags", "member-settings", "segments", "coupon-edit", "coupon-issue", "member-card",
                    "coupon", "coupon-send", "coupon-issues", "coupon-code",
                    "platform-activities", "platform-activity-apply"]),
    ("团购与求团", ["groups", "quotes", "requests", "group", "request", "request-create", "group-host",
                    "group-open"]),
    ("钱", ["settle", "payment", "plan", "wallet", "points", "points-records",
            "income", "invoice"]),
    ("数据", ["stats", "cross-store"]),
    # 进销存（P-18）。**独立成一个域，不并进「商品」** —— 那边管的是
    # 「这件货长什么样、能不能卖」，这边管的是「现在有多少、是怎么变成这么多的」
    ("进销存", ["stock", "stock-detail", "stock-check", "purchase-edit", "stock-docs",
                "stock-out", "transfer", "stock-report", "locations", "suppliers"]),
    ("账号与设置", ["login", "me", "apply", "staff", "staff-detail", "role-detail",
                    "settings", "address", "address-pick", "profile", "legal"]),
    ("消息与评价", ["messages", "reviews", "notice", "review-write"]),
    ("店铺与逛", ["merchant", "merchants", "community"]),
]

# 标题由页面在运行时设（商品名、订单号…），pages.json 与 scaffold 上都取不到
FALLBACK_TITLES = {
    "b-app": {
        "pages/goods-edit/index": "编辑商品",
        # 同一页服务两种人：第一次开店（入驻申请）与已在营业的再加一张证照。
        # 清单里记前者 —— 那是这一页的主用途，也是从工作台点进来时的那个名字。
        "pages/apply/index": "入驻申请",
    },
    "c-app": {
        "pages/goods/index": "商品详情", "pages/search/index": "搜索",
        "pages/pay/index": "收银台", "pages/orders/index": "我的订单",
        "pages/order/index": "订单详情", "pages/after-sale/index": "申请售后",
        "pages/merchant/index": "商家主页", "pages/merchants/index": "附近商家",
        "pages/group/index": "团购详情", "pages/groups/index": "拼团",
        "pages/request/index": "求团详情", "pages/request-create/index": "发起求团",
        "pages/order-confirm/index": "确认订单", "pages/cards/index": "我的卡包",
        "pages/coupons/index": "我的券", "pages/points/index": "我的积分",
        "pages/messages/index": "消息", "pages/address/index": "收货地址",
        "pages/address-pick/index": "选择收货地址",
        "pages/store/index": "店铺主页", "pages/category/index": "分类",
        "pages/cart/index": "购物车", "pages/legal/index": "协议与条款",
    },
}


def domain_of(path: str) -> str:
    seg = path.split("/")[1] if path.startswith("pages/") else path.strip("/")
    for name, prefixes in DOMAINS:
        if seg in prefixes:
            return name
    return "其它"


def locale_map(app: str) -> dict[str, str]:
    """把 zh-CN.ts 拍平成 `a.b.c -> 文案`。只为取标题，不求全对。"""
    f = ROOT / app / "src/i18n/locale/zh-CN.ts"
    if not f.exists():
        return {}
    flat: dict[str, str] = {}
    stack: list[str] = []
    for raw in f.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("//") or line.startswith("*") or line.startswith("/*"):
            continue
        for k, v in re.findall(r'(\w+):\s*"((?:[^"\\]|\\.)*)"', line):
            flat[".".join(stack + [k])] = v
        m = re.match(r'(\w+):\s*\{\s*$', line)
        if m:
            stack.append(m.group(1))
        elif line.startswith("}") and stack:
            stack.pop()
    return flat


def scaffold_title(app: str, path: str, loc: dict[str, str]) -> tuple[str, str]:
    """页面标题多数写在 `<sh-scaffold title-key="x.y">` 上，pages.json 里是空的。

    返回 `(词条 key, 译文)`。**key 也要带回来** —— 两边对不上时要能说出
    该改哪一条，只说「不一致」的报错，人只会去把它跳过。
    """
    vue = ROOT / app / "src" / f"{path}.vue"
    if not vue.exists():
        return ("", "")
    m = re.search(r'title-key="([\w.]+)"', vue.read_text(encoding="utf-8"))
    return (m.group(1), loc.get(m.group(1), "")) if m else ("", "")


#: 标题两个真源对不上的地方。见 `read_uni` 里那段注释。
TITLE_DRIFT: list[str] = []


def read_uni(app: str) -> list[dict]:
    data = json.loads((ROOT / app / "src/pages.json").read_text(encoding="utf-8"))
    loc = locale_map(app)
    tabs = {t.get("pagePath") for t in data.get("tabBar", {}).get("list", [])}
    out = []
    for p in data.get("pages", []):
        path = p["path"]
        json_title = p.get("style", {}).get("navigationBarTitleText", "").strip()
        key, key_title = scaffold_title(app, path, loc)
        # 标题有**两个真源**：`pages.json` 的 navigationBarTitleText（切语言不会变，
        # 但小程序/App 的原生标题栏读它）与 `<sh-scaffold title-key>` 指的词条。
        #
        # 此前这里是 `json_title or key_title` —— pages.json 赢，词条只兜底。
        # 于是**改了词条没改 pages.json，清单安静地显示旧标题**，闸门照样绿。
        # 2026-08-28「单据 ↔ 按单查」那次就是这么来回了一趟。
        #
        # 现在两边都有值且不一致就报红，并把该改的两处都说出来 ——
        # 不选边（谁赢都会让另一处悄悄失效），而是逼人当场对齐。
        if json_title and key_title and json_title != key_title:
            TITLE_DRIFT.append(
                f"{app}/{path}\n"
                f"      pages.json  navigationBarTitleText = 「{json_title}」\n"
                f"      词条        {key} = 「{key_title}」")
        out.append({
            "app": app,
            "route": "/" + path,
            "title": (json_title
                      or key_title
                      or FALLBACK_TITLES.get(app, {}).get(path)
                      or path.split("/")[1]),
            "domain": domain_of(path),
            "tab": path in tabs,
            "status": "已实现",
            # proto 仍是锚点（JSON 形状不变，外部消费者不受影响）；
            # protoUrl 是新增的一列，只在指到非默认那份时才不同
            "proto": proto_of(app, path)[0],
            "protoUrl": proto_of(app, path)[1],
            "preview": DEV_ORIGIN[app] + "/" + path,
        })
    return out


def read_ops() -> list[dict]:
    """解析 nav.ts。只认对象字面量里的 href/label/group/matrix/ready/soon 六个键。"""
    src = (ROOT / "ops-web/lib/nav.ts").read_text(encoding="utf-8")
    out: list[dict] = []
    section = None
    for line in src.splitlines():
        s = line.strip()
        if s.startswith("//") or s.startswith("*") or s.startswith("/*"):
            continue
        # section 头：key: "x", label: "商家治理", ... href: "/merchants"
        m = re.match(r'key:\s*"([^"]+)",\s*label:\s*"([^"]+)"', s)
        if m:
            section = m.group(2)
            href = re.search(r'href:\s*"([^"]+)"', s)
            if "children" not in s and href:
                out.append({"app": "ops-web", "route": href.group(1), "title": section,
                            "domain": section, "tab": False, "status": "已实现",
                            "proto": None, "preview": DEV_ORIGIN["ops-web"] + href.group(1)})
            continue
        # 叶子
        if s.startswith("{ href:") or s.startswith("{href:"):
            href = re.search(r'href:\s*"([^"]+)"', s)
            label = re.search(r'label:\s*"([^"]+)"', s)
            if not (href and label):
                continue
            group = re.search(r'group:\s*"([^"]+)"', s)
            matrix = re.search(r'matrix:\s*"([^"]+)"', s)
            soon = "soon: true" in s
            out.append({
                "app": "ops-web",
                "route": href.group(1),
                "title": label.group(1),
                "domain": section or "其它",
                "group": group.group(1) if group else None,
                "matrix": matrix.group(1) if matrix else None,
                "tab": False,
                "status": "待建" if soon else "已实现",
                "proto": proto_of("ops-web", href.group(1).lstrip("/"))[0],
                "protoUrl": proto_of("ops-web", href.group(1).lstrip("/"))[1],
                "preview": DEV_ORIGIN["ops-web"] + href.group(1),
            })
    return out


# 原型稿。**登记表只有一份**：prototypes/registry.json（真源在仓库，claude.ai 上的是发布副本）。
# 此前这里手写了四张表（默认地址、按路由指到哪一份、锚点、还没落地的页面），
# 三份稿子先后覆盖同一批路由时，谁赢取决于几段 update 的先后 —— 没人说得清。
# 现在按登记表的顺序：同一路由被多份登记时排在前面的赢；作废的稿子不参与。
REGISTRY = json.loads((ROOT / "prototypes/registry.json").read_text(encoding="utf-8"))["prototypes"]

#: app → 路由 → (锚点, 原型地址)。锚点可以缺（画布式原型），地址不能缺。
PROTO_BY_ROUTE: dict = {}  # app → 路由 → (锚点 | None, 地址)
#: 登记了路由的屏：(app, 路由, 屏名)。路由还不存在的会在清单里列成「原型」行
PROTO_SCREENS: list = []
for _p in REGISTRY:
    if _p["status"] == "作废" or not _p.get("artifact"):
        continue
    for _s in _p.get("screens", []):
        if not (_s.get("client") and _s.get("route")):
            continue
        _route = _s["route"].lstrip("/")
        _mine = PROTO_BY_ROUTE.setdefault(_s["client"], {})
        if _route in _mine:
            continue
        _mine[_route] = (_s["id"] or None, _p["artifact"])
        # 清单里那一行叫页面的名字（pageTitle），没写就用屏名
        PROTO_SCREENS.append((_s["client"], _route, _s.get("pageTitle") or re.sub(r"^s\d+\s+", "", _s["title"])))


def proto_of(app, path):
    """→ (锚点, 原型地址)。没有原型时两个都是 None。

    **锚点可以缺，地址不能缺。** 早先两者绑死：没登记锚点就当成「没有原型」，
    于是原型是一张画布（各屏并排摆着、没有 `#sNN` 这种页内锚点）时无处登记 ——
    只能编一个跳不到任何地方的锚点，或者干脆不挂链接。
    编锚点是往数据里写一句假话；不挂链接是让清单说「这一页没有原型稿」，而它有。
    所以：只登记地址也算数，链接落到画布本身。
    """
    return PROTO_BY_ROUTE.get(app, {}).get(path, (None, None))


# 本机 dev server 端口（mock 模式）。点「预览」直接进那一页，不用自己拼路由
DEV_ORIGIN = {"b-app": "http://localhost:5175/#", "c-app": "http://localhost:5176/#",
              "ops-web": "http://localhost:3000"}


def main() -> None:
    check = "--check" in sys.argv
    rows = read_uni("b-app") + read_uni("c-app") + read_ops()
    # 登记了路由、页面还没建的屏：列成「原型」行。落地之后它自然从 pages.json / nav.ts 里出现，
    # 这里不用删任何东西 —— 判据是路由存不存在，不是人记不记得
    have = {(r["app"], r["route"]) for r in rows}
    rows += [{"app": a, "route": "/" + r, "title": t, "domain": domain_of(r), "tab": False, "status": "原型",
              "proto": proto_of(a, r)[0], "protoUrl": proto_of(a, r)[1], "preview": None}
             for a, r, t in PROTO_SCREENS if (a, "/" + r) not in have]

    apps = {"b-app": "商家 App", "c-app": "买家小程序", "ops-web": "运营端"}
    catalog: dict = {"apps": [], "total": len(rows)}
    for app, app_label in apps.items():
        mine = [r for r in rows if r["app"] == app]
        domains: dict[str, list] = {}
        for r in mine:
            domains.setdefault(r["domain"], []).append(r)
        catalog["apps"].append({
            "key": app, "label": app_label, "count": len(mine),
            "domains": [{"name": k, "pages": v} for k, v in domains.items()],
        })

    fresh = json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"

    # **两种模式都拦。** 生成一份已知是错的清单，与放它过闸门一样糟 ——
    # 前者更糟一点：它会把错的标题写进产物，下一个人拿它去对齐。
    if TITLE_DRIFT:
        print("✗ 页面标题有两个真源，而它们对不上：", file=sys.stderr)
        for d in TITLE_DRIFT:
            print(f"    {d}", file=sys.stderr)
        print("\n  两处都要改成同一个。改哪一边取决于你想要哪个名字 ——",
              file=sys.stderr)
        print("  但**只改一边不会报错**，只是原生标题栏与页内标题各说各的，",
              file=sys.stderr)
        print("  而清单会照着 pages.json 那一边写进产物。", file=sys.stderr)
        sys.exit(1)

    if check:
        old = OUT_JSON.read_text(encoding="utf-8") if OUT_JSON.exists() else ""
        if old != fresh:
            print("✗ 界面清单过期了：有页面加了/改了/删了，但 ui-catalog.json 没跟上。", file=sys.stderr)
            print("  跑一下：python3 scripts/gen-ui-catalog.py（然后把 JSON 一起提交）", file=sys.stderr)
            _diff(json.loads(old) if old else {"apps": []}, catalog)
            sys.exit(1)
        print(f"✓ 界面清单是最新的（{catalog['total']} 个界面）")
        return

    OUT_JSON.write_text(fresh, encoding="utf-8")
    OUT_HTML.write_text(render(catalog), encoding="utf-8")
    print(f"{OUT_JSON.relative_to(ROOT)}: {catalog['total']} 个界面")
    for a in catalog["apps"]:
        print(f"  {a['label']:<12} {a['count']:>3} 个 · {len(a['domains'])} 个域")


def _diff(old: dict, new: dict) -> None:
    """把差在哪儿直接说出来 —— 只说「不一致」的闸门，人只会去跳过它。"""
    def flat(c: dict) -> dict[str, str]:
        return {f"{a['key']}{p['route']}": p["title"]
                for a in c.get("apps", []) for d in a["domains"] for p in d["pages"]}
    o, n = flat(old), flat(new)
    for k in sorted(n.keys() - o.keys()):
        print(f"  + 新增 {k}（{n[k]}）", file=sys.stderr)
    for k in sorted(o.keys() - n.keys()):
        print(f"  - 删除 {k}（{o[k]}）", file=sys.stderr)
    for k in sorted(o.keys() & n.keys()):
        if o[k] != n[k]:
            print(f"  ~ 改名 {k}：{o[k]} → {n[k]}", file=sys.stderr)


def render(cat: dict) -> str:
    """生成可直接发布成 Artifact 的单页。样式与项目令牌一致，深浅色都画。"""
    from html import escape
    parts = []
    for app in cat["apps"]:
        secs = []
        for dom in app["domains"]:
            items = []
            for p in dom["pages"]:
                badge = {"已实现": "ok", "原型": "proto", "待建": "soon"}[p["status"]]
                extra = " · ".join(x for x in [p.get("group"), p.get("matrix")] if x)
                links = ""
                proto_href = p.get("protoUrl")
                if p.get("proto") or p.get("protoUrl"):
                    # 有锚点就跳那一屏，没有就落到原型本身（画布式原型没有页内锚点）
                    href = f'{proto_href}#{p["proto"]}' if p.get("proto") else proto_href
                    links += (f'<a class="lk proto-lk" href="{href}" '
                              f'target="_blank" rel="noopener">原型</a>')
                if p.get("preview"):
                    links += (f'<a class="lk" href="{escape(p["preview"])}" '
                              f'target="_blank" rel="noopener">预览</a>')
                items.append(
                    f'<li><span class="t">{escape(p["title"])}</span>'
                    f'<code>{escape(p["route"])}</code>'
                    f'{f"<em>{escape(extra)}</em>" if extra else ""}'
                    f'{links}'
                    f'<span class="b {badge}">{p["status"]}</span></li>')
            secs.append(f'<section><h3>{escape(dom["name"])}'
                        f'<span class="n">{len(dom["pages"])}</span></h3>'
                        f'<ul>{"".join(items)}</ul></section>')
        parts.append(f'<article><h2>{escape(app["label"])}'
                     f'<span class="n">{app["count"]}</span></h2>{"".join(secs)}</article>')
    return TEMPLATE.replace("{{BODY}}", "".join(parts)).replace("{{TOTAL}}", str(cat["total"]))


TEMPLATE = """<title>三端界面清单</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wght@500;600;700&family=IBM+Plex+Mono:wght@400&display=swap">
<style>
:root{--sheet:#F4F3F0;--ink:#16161A;--muted:#6C6B66;--rule:#DFDDD8;--card:#fff;
  --ok:#1B7F4B;--ok-bg:rgba(27,127,75,.1);--proto:#B31710;--proto-bg:rgba(225,37,27,.1);
  --soon:#8A6A2F;--soon-bg:rgba(190,150,60,.14)}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]){--sheet:#121214;--ink:#F2F1EE;
  --muted:#9C9A94;--rule:#2A2A2E;--card:#1D1D21;--ok:#4ED08A;--ok-bg:rgba(78,208,138,.12);
  --proto:#FF7A6E;--proto-bg:rgba(255,122,110,.12);--soon:#D9B45F;--soon-bg:rgba(217,180,95,.12)}}
:root[data-theme=dark]{--sheet:#121214;--ink:#F2F1EE;--muted:#9C9A94;--rule:#2A2A2E;--card:#1D1D21;
  --ok:#4ED08A;--ok-bg:rgba(78,208,138,.12);--proto:#FF7A6E;--proto-bg:rgba(255,122,110,.12);
  --soon:#D9B45F;--soon-bg:rgba(217,180,95,.12)}
*{box-sizing:border-box}
body{margin:0;background:var(--sheet);color:var(--ink);
  font-family:Archivo,"PingFang SC","Microsoft YaHei",sans-serif;-webkit-font-smoothing:antialiased}
.wrap{max-width:1100px;margin:0 auto;padding:52px 24px 90px}
header{border-bottom:2px solid var(--ink);padding-bottom:16px}
.eyebrow{font-family:"IBM Plex Mono",monospace;font-size:12px;letter-spacing:.14em;
  text-transform:uppercase;color:var(--muted)}
h1{font-size:clamp(26px,4vw,40px);margin:10px 0 6px;font-weight:700}
.sub{color:var(--muted);font-size:14.5px;line-height:1.65;max-width:64ch;margin:0}
article{margin-top:46px}
h2{font-size:20px;margin:0 0 6px;border-bottom:1px solid var(--rule);padding-bottom:8px;
  display:flex;align-items:baseline;gap:10px}
h3{font-size:14px;margin:22px 0 8px;color:var(--muted);font-weight:600;
  display:flex;align-items:baseline;gap:8px}
.n{font-family:"IBM Plex Mono",monospace;font-size:12px;color:var(--muted);font-weight:400}
ul{list-style:none;margin:0;padding:0;display:grid;gap:6px}
li{background:var(--card);border:1px solid var(--rule);border-radius:8px;padding:9px 12px;
  display:flex;align-items:center;gap:10px;flex-wrap:wrap;font-size:14px}
.t{font-weight:600;min-width:7em}
code{font-family:"IBM Plex Mono",monospace;font-size:12px;color:var(--muted)}
em{font-style:normal;font-family:"IBM Plex Mono",monospace;font-size:11.5px;color:var(--muted);
  opacity:.8}
.lk{font-size:11.5px;padding:2px 8px;border-radius:6px;text-decoration:none;
  border:1px solid var(--rule);color:var(--muted)}
.lk:hover{color:var(--ink);border-color:var(--ink)}
.lk.proto-lk{color:var(--proto);border-color:color-mix(in srgb,var(--proto) 40%,transparent)}
.lk:focus-visible{outline:2px solid var(--ink);outline-offset:2px}
.b{margin-left:auto;font-size:11.5px;padding:2px 9px;border-radius:999px;white-space:nowrap}
.b.ok{color:var(--ok);background:var(--ok-bg)}
.b.proto{color:var(--proto);background:var(--proto-bg)}
.b.soon{color:var(--soon);background:var(--soon-bg)}
footer{margin-top:70px;border-top:1px solid var(--rule);padding-top:16px;
  font-family:"IBM Plex Mono",monospace;font-size:12px;color:var(--muted);line-height:1.9}
</style>
<div class="wrap">
<header>
  <div class="eyebrow">ai-shop · 界面清单 · 由代码生成</div>
  <h1>三端界面清单</h1>
  <p class="sub">共 {{TOTAL}} 个界面。来源是代码本身：两个 App 读 <code>pages.json</code>，
  运营端读 <code>lib/nav.ts</code>；只有还没建页面的原型是手写的，落地后从脚本里删掉，
  它就会从真源里自然出现。<br>重新生成：<code>python3 scripts/gen-ui-catalog.py</code></p>
</header>
{{BODY}}
<footer>已实现 = 路由已存在　原型 = 只有设计稿　待建 = 导航里登记了但页面未建<br>
「原型」跳设计稿对应的那一屏；「预览」跳本机 dev server（b-app 5175 / c-app 5176 / ops-web 3000，需先启动）<br>
清单不手工维护：加了一页而清单没变，说明该重新跑一次生成器</footer>
</div>
"""

if __name__ == "__main__":
    main()

# 关于产物放哪：`docs/**/*.html` 整个被 gitignore（那是 md → html 的转换产物），
# 所以这里落成 `.page.html` 也同样进不了库 —— 它本来也不该进：
# HTML 是**发布用的**一次性产物，随时能从 ui-catalog.json 重新渲染。
# 进库的是脚本与 JSON，那两样才是真源。
