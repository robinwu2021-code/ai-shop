#!/usr/bin/env python3
"""把三端的界面扫成一份清单（JSON + 可发布的 HTML）。

用法：
  python3 scripts/gen-ui-catalog.py            重新生成
  python3 scripts/gen-ui-catalog.py --check    只校验（pre-push 闸门用；不一致就退出 1）

来源都是**代码里已有的真源**，不手工维护第二份：
  · b-app / c-app  → `src/pages.json`（路由 + 导航栏标题 + tabBar）
  · ops-web        → `lib/nav.ts`（模块 → 子功能，带权限码与矩阵编号）
  · 原型（还没有页面的）→ 本文件末尾的 PROTOTYPES，落地后从这里删掉

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
    ("商品", ["goods", "goods-list", "goods-edit", "goods-detail", "search", "category", "my-specs", "store-categories"]),
    ("门店", ["stores", "store", "store-notice", "store-scope", "store-pick", "qualifications"]),
    ("会员与营销", ["customers", "marketing", "coupons", "member", "cards", "members",
                    "member-detail",
                    "member-tags", "member-settings", "segments", "coupon-edit", "coupon-issue", "member-card"]),
    ("团购与求团", ["groups", "quotes", "requests", "group", "request", "request-create", "group-host"]),
    ("钱", ["settle", "payment", "plan", "wallet", "points", "invoice"]),
    ("数据", ["stats", "cross-store"]),
    ("账号与设置", ["login", "me", "apply", "staff", "staff-detail", "role-detail",
                    "settings", "address", "profile", "legal"]),
    ("消息与评价", ["messages", "reviews", "notice", "review-write"]),
    ("店铺与逛", ["merchant", "merchants", "community"]),
]

# 标题由页面在运行时设（商品名、订单号…），pages.json 与 scaffold 上都取不到
FALLBACK_TITLES = {
    "b-app": {"pages/goods-edit/index": "编辑商品"},
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


def scaffold_title(app: str, path: str, loc: dict[str, str]) -> str:
    """页面标题多数写在 `<sh-scaffold title-key="x.y">` 上，pages.json 里是空的。"""
    vue = ROOT / app / "src" / f"{path}.vue"
    if not vue.exists():
        return ""
    m = re.search(r'title-key="([\w.]+)"', vue.read_text(encoding="utf-8"))
    return loc.get(m.group(1), "") if m else ""


def read_uni(app: str) -> list[dict]:
    data = json.loads((ROOT / app / "src/pages.json").read_text(encoding="utf-8"))
    loc = locale_map(app)
    tabs = {t.get("pagePath") for t in data.get("tabBar", {}).get("list", [])}
    out = []
    for p in data.get("pages", []):
        path = p["path"]
        out.append({
            "app": app,
            "route": "/" + path,
            "title": (p.get("style", {}).get("navigationBarTitleText", "").strip()
                      or scaffold_title(app, path, loc)
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
                "proto": None,
                "preview": DEV_ORIGIN["ops-web"] + href.group(1),
            })
    return out


# 原型稿（Artifact）。清单里每一条能点进去看那一屏长什么样。
PROTO_URL = "https://claude.ai/code/artifact/459462f5-e7f7-485a-85b0-096ba9918b15"

# **原型不止一份**。会员与营销那批在上面那个 artifact 里，后来的各自成篇 ——
# 一份文档塞进所有域，读的人要先滚过八屏无关的才看到自己那一屏。
# 这里按路由指到各自的那一份；没登记的仍走 PROTO_URL。
PROTO_URL_BY_ROUTE = {
    "b-app": {
        "pages/goods-edit/index": "https://claude.ai/code/artifact/9eb1a32a-a74b-40d2-b6c3-1a4cb394f02e",
        "pages/my-specs/index": "https://claude.ai/code/artifact/9eb1a32a-a74b-40d2-b6c3-1a4cb394f02e",
        # 商家资金全链路（订单 / 积分 / 资金八屏）
        "pages/orders/index": "https://claude.ai/code/artifact/feceebc7-49da-4b4b-bdf4-4411ae384c24",
        "pages/order/index": "https://claude.ai/code/artifact/feceebc7-49da-4b4b-bdf4-4411ae384c24",
        "pages/schedule/index": "https://claude.ai/code/artifact/feceebc7-49da-4b4b-bdf4-4411ae384c24",
        "pages/points/index": "https://claude.ai/code/artifact/feceebc7-49da-4b4b-bdf4-4411ae384c24",
        "pages/points-records/index": "https://claude.ai/code/artifact/feceebc7-49da-4b4b-bdf4-4411ae384c24",
        "pages/income/index": "https://claude.ai/code/artifact/feceebc7-49da-4b4b-bdf4-4411ae384c24",
        "pages/payment/index": "https://claude.ai/code/artifact/feceebc7-49da-4b4b-bdf4-4411ae384c24",
        "pages/settle/index": "https://claude.ai/code/artifact/feceebc7-49da-4b4b-bdf4-4411ae384c24",
    },
}


def proto_of(app, path):
    """→ (锚点, 原型地址)。没有原型时两个都是 None。"""
    anchor = PROTO_ANCHORS.get(app, {}).get(path)
    if not anchor:
        return None, None
    return anchor, PROTO_URL_BY_ROUTE.get(app, {}).get(path, PROTO_URL)

# 路由 → 原型里的锚点。已经有页面的也可以挂 —— 它们同样有原型稿
PROTO_ANCHORS = {
    "b-app": {
        "pages/me/index": "s01",
        # 会员页落地了：路由沿用 pages/customers（它是「我的客户」的升级版）
        "pages/customers/index": "s02", "pages/members/filter": "s03",
        "pages/member-detail/index": "s04", "pages/members/add": "s05",
        "pages/member-tags/index": "s06", "pages/member-settings/index": "s07",
        "pages/marketing/index": "s08", "pages/marketing/new": "s09",
        "pages/marketing/audience": "s10", "pages/coupons/index": "s11",
        "pages/coupon-edit/index": "s12", "pages/coupon-issue/index": "s13",
        "pages/verify/index": "s14",
        # 规格原型（另一份 artifact，见 PROTO_URL_BY_ROUTE）
        "pages/goods-edit/index": "s19",
        "pages/my-specs/index": "s23",
        # 商家资金全链路八屏（另一份 artifact）
        "pages/orders/index": "s01", "pages/order/index": "s02",
        "pages/schedule/index": "s03",
        "pages/points/index": "s04", "pages/points-records/index": "s05",
        "pages/income/index": "s06", "pages/payment/index": "s07",
        "pages/settle/index": "s08",
    },
    "c-app": {
        "pages/store/index": "s15", "pages/member-card/index": "s16",
        "pages/coupons/index": "s17",
    },
}

# 本机 dev server 端口（mock 模式）。点「预览」直接进那一页，不用自己拼路由
DEV_ORIGIN = {"b-app": "http://localhost:5175/#", "c-app": "http://localhost:5176/#",
              "ops-web": "http://localhost:3000"}


# 还没有页面、只有设计的：落地之后从这里删掉，它就会从 nav/pages.json 里自然出现
PROTOTYPES = [
    ("b-app", "/pages/members/add", "手工录入会员", "会员与营销"),
    ("b-app", "/pages/member-tags/index", "标签与合并", "会员与营销"),
    ("b-app", "/pages/member-settings/index", "会员口径设置", "会员与营销"),
    ("b-app", "/pages/segments/index", "人群", "会员与营销"),
    ("b-app", "/pages/coupons/index", "券列表", "会员与营销"),
    ("b-app", "/pages/coupon-edit/index", "新建券", "会员与营销"),
    ("b-app", "/pages/coupon-issue/index", "发放结果", "会员与营销"),
    ("c-app", "/pages/member-card/index", "我的会员卡", "会员与营销"),
    ("c-app", "/pages/coupons/index", "券包（含次卡出示）", "会员与营销"),
    ("ops-web", "/members", "会员总览（跨商家）", "会员与营销"),
    ("ops-web", "/members?tab=person", "人档与合并", "会员与营销"),
    ("ops-web", "/members?tab=reach", "触达监控", "会员与营销"),
    # 商家资金全链路：接口都在、B 端没有出口的那几页
    ("b-app", "/pages/points/index", "积分成本总览", "钱"),
    ("b-app", "/pages/points-records/index", "发分明细", "钱"),
    ("b-app", "/pages/income/index", "我的收入（到账进度）", "钱"),
]


def main() -> None:
    check = "--check" in sys.argv
    rows = read_uni("b-app") + read_uni("c-app") + read_ops()
    rows += [{"app": a, "route": r, "title": t, "domain": d, "tab": False, "status": "原型",
              "proto": proto_of(a, r.lstrip("/"))[0],
              "protoUrl": proto_of(a, r.lstrip("/"))[1],
              "preview": None}
             for a, r, t, d in PROTOTYPES]

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
                proto_href = p.get("protoUrl") or PROTO_URL
                if p.get("proto"):
                    links += (f'<a class="lk proto-lk" href="{proto_href}#{p["proto"]}" '
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
