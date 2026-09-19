#!/usr/bin/env python3
"""界面原型总览：从 prototypes/registry.json 生成 prototypes/index.html 与 docs/technical/design/原型清单.md。

用法：
  python3 scripts/gen-proto-index.py            重新生成
  python3 scripts/gen-proto-index.py --check    只校验（pre-push 闸门用；不一致就退出 1）

校验什么（每一条都是这个目录立起来之前真出过的事）：
  · 登记表里的 file 都在，反过来目录里的 .html 都登记过 —— 没登记的稿子等于不存在
  · screens[].id 在文件里确有 <figure id>；锚点写错时清单上的「原型」链接跳到页首，没人报错
  · 页面稿（kind=pages）不许有内联 <style>：样式只在 proto.css，否则「统一风格」第二周就散了
  · 生成物没陈：index.html 与 原型清单.md 是从登记表渲染的，改了登记表不重跑就推不上去
"""
import html
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
DIR = ROOT / "prototypes"
REG = DIR / "registry.json"
OUT_HTML = DIR / "index.html"
OUT_MD = ROOT / "docs/technical/design/原型清单.md"

CLIENT_LABEL = {"b-app": "商家 App", "c-app": "买家小程序", "ops-web": "运营端"}
KIND_LABEL = {"pages": "页面稿", "proposal": "方案稿", "canvas": "画布稿"}
STATUS_TONE = {"已落地": "ok", "在建": "warn", "参考": "", "作废": "dead"}


def load() -> list[dict]:
    reg = json.loads(REG.read_text(encoding="utf-8"))
    return reg["prototypes"]


def lint(items: list[dict]) -> list[str]:
    bad: list[str] = []
    seen_files = set()
    for p in items:
        slug = p["slug"]
        f = p.get("file")
        if f:
            path = DIR / f
            seen_files.add(f)
            if not path.exists():
                bad.append(f"{slug}: 登记的文件不存在 prototypes/{f}")
                continue
            text = path.read_text(encoding="utf-8")
            ids = set(re.findall(r'<figure[^>]*\bid="([^"]+)"', text))
            for s in p.get("screens", []):
                if s.get("id") and s["id"] not in ids:
                    bad.append(f"{slug}: 登记了锚点 {s['id']}，文件里没有 <figure id=\"{s['id']}\">")
            if p["kind"] == "pages" and "<style" in text:
                bad.append(f"{slug}: 页面稿不许有内联 <style>，样式只在 proto.css")
            if 'href="proto.css"' not in text:
                bad.append(f"{slug}: 没有接 proto.css")
        elif p["kind"] != "canvas":
            bad.append(f"{slug}: 非画布稿必须有本地文件（真源在仓库，artifact 只是副本）")
        if p["status"] == "作废" and not p.get("supersededBy"):
            bad.append(f"{slug}: 作废的稿子要写 supersededBy")
    for path in sorted(DIR.glob("*.html")):
        if path.name in ("index.html", "_template.html"):
            continue
        if path.name not in seen_files:
            bad.append(f"prototypes/{path.name} 没在 registry.json 登记")
    return bad


def a(href: str, text: str, cls: str = "") -> str:
    c = f' class="{cls}"' if cls else ""
    return f'<a{c} href="{html.escape(href)}">{html.escape(text)}</a>'


def render_html(items: list[dict]) -> str:
    live = [p for p in items if p["status"] != "作废"]
    dead = [p for p in items if p["status"] == "作废"]
    n_pages = sum(1 for p in live if p["kind"] in ("pages", "canvas"))
    n_screens = sum(len(p.get("screens", [])) for p in live if p["kind"] in ("pages", "canvas"))

    def card(p: dict) -> str:
        clients = " · ".join(CLIENT_LABEL.get(c, c) for c in p["clients"])
        links = []
        if p.get("file"):
            links.append(a(p["file"], "本地稿"))
        if p.get("artifact"):
            links.append(a(p["artifact"], "claude.ai"))
        for d in p.get("docs", []):
            links.append(a("../" + d["path"], d["label"]))
        chips = ""
        screens = p.get("screens", [])
        if screens:
            parts = []
            for s in screens:
                label = s["id"] or "·"
                t = f'{s["id"]} {s["title"]}' if s["id"] else s["title"]
                href = f'{p["file"]}#{s["id"]}' if p.get("file") and s["id"] else (p.get("artifact") or "#")
                cls = "scr" + (" scr--todo" if s.get("status") == "在建" else "")
                parts.append(f'<a class="{cls}" href="{html.escape(href)}" title="{html.escape(t)}">{html.escape(label)}</a>')
            chips = f'<div class="screens">{"".join(parts)}</div>'
        tone = STATUS_TONE.get(p["status"], "")
        sup = ""
        if p.get("supersededBy"):
            sup = f'<p class="why">已由 {a("#" + p["supersededBy"], p["supersededBy"])} 取代</p>'
        return f'''<article class="pcard" id="{html.escape(p["slug"])}">
  <div class="pcard__hd">
    <b>{html.escape(p["title"])}</b>
    <span class="chip {tone}">{html.escape(p["status"])}</span>
  </div>
  <div class="pcard__meta">{html.escape(KIND_LABEL[p["kind"]])} · {html.escape(clients)} · {html.escape(p["date"])}{" · " + str(len(screens)) + " 屏" if screens else ""}</div>
  <p class="pcard__sum">{html.escape(p.get("summary", ""))}</p>
  {chips}
  <div class="pcard__links">{" ".join(links)}</div>
  {sup}
</article>'''

    groups = [("页面稿：每一屏长什么样", [p for p in live if p["kind"] in ("pages", "canvas")]),
              ("方案稿：几种排法怎么选", [p for p in live if p["kind"] == "proposal"]),
              ("作废：被后来的稿子取代", dead)]
    sections = ""
    for i, (title, ps) in enumerate(groups, 1):
        if not ps:
            continue
        sections += f'''<section>
  <div class="sec-head"><span class="sec-num">{i:02d}</span><h2>{html.escape(title)}</h2></div>
  <div class="pgrid">
{"".join(card(p) for p in ps)}
  </div>
</section>
'''
    return f'''<!doctype html>
<html lang="zh-CN">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>界面原型总览</title>
<!-- 生成物：python3 scripts/gen-proto-index.py（读 prototypes/registry.json）。别手改。 -->
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wght@500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap">
<link rel="stylesheet" href="proto.css">

<div class="wrap">
<header class="top">
  <div class="eyebrow">ai-shop · 三端界面原型 · 唯一入口 · 由登记表生成</div>
  <h1>界面原型总览</h1>
  <p class="sub">{len(live)} 份有效稿子，其中页面稿 {n_pages} 份、共 {n_screens} 屏；{len(dead)} 份作废。
  真源在仓库 <code>prototypes/</code>，claude.ai 上的是发布副本；样式只有一份 <code>proto.css</code>，
  屏内用的就是产品的令牌。点屏号跳到那一屏；<a href="../docs/technical/design/ui-catalog.json">界面清单</a>反过来从页面跳到原型。</p>
</header>
{sections}
<footer>页面稿 = 逐屏画出来、每屏有锚点，界面清单能跳进来 · 方案稿 = 几种排法并列比较，落地后只留结论 · 画布稿 = claude.ai 设计画布，没有页内锚点<br>
加一份：prototypes/README.md · 重新生成：<code>python3 scripts/gen-proto-index.py</code></footer>
</div>
</html>
'''


def render_md(items: list[dict]) -> str:
    live = [p for p in items if p["status"] != "作废"]
    dead = [p for p in items if p["status"] == "作废"]
    lines = ["# 界面原型清单", "",
             "> **生成物**（`python3 scripts/gen-proto-index.py`，读 `prototypes/registry.json`），别手改。",
             "> 总览页 `prototypes/index.html`；真源在 `prototypes/`，claude.ai 上的是发布副本。",
             "> 状态：**生成 · 长期有效**", "",
             f"有效 {len(live)} 份，作废 {len(dead)} 份。", ""]

    def table(ps: list[dict]) -> list[str]:
        out = ["| 稿子 | 类型 | 端 | 状态 | 屏 | 日期 | 本地 | claude.ai | 相关文档 |", "|---|---|---|---|---|---|---|---|---|"]
        for p in ps:
            clients = " · ".join(CLIENT_LABEL.get(c, c) for c in p["clients"])
            screens = p.get("screens", [])
            scr = ", ".join(f'{s["id"]} {s["title"]}' if s["id"] else s["title"] for s in screens) or "—"
            if len(scr) > 160:
                scr = scr[:157] + "…"
            local = f'[{p["file"]}](../../../prototypes/{p["file"]})' if p.get("file") else "—"
            art = f'[链接]({p["artifact"]})' if p.get("artifact") else "—"
            docs = " · ".join(f'[{d["label"]}](../../../{d["path"]})' for d in p.get("docs", [])) or "—"
            status = p["status"] + (f'（→ {p["supersededBy"]}）' if p.get("supersededBy") else "")
            out.append(f'| **{p["title"]}** | {KIND_LABEL[p["kind"]]} | {clients} | {status} | {scr} | {p["date"]} | {local} | {art} | {docs} |')
        return out

    lines += ["## 一、页面稿", ""] + table([p for p in live if p["kind"] in ("pages", "canvas")]) + [""]
    lines += ["## 二、方案稿", ""] + table([p for p in live if p["kind"] == "proposal"]) + [""]
    if dead:
        lines += ["## 三、作废", ""] + table(dead) + [""]
    return "\n".join(lines) + "\n"


def main() -> None:
    check = "--check" in sys.argv
    items = load()
    bad = lint(items)
    if bad:
        print("✗ 原型登记表与目录不一致：", file=sys.stderr)
        for b in bad:
            print("   " + b, file=sys.stderr)
        sys.exit(1)
    fresh_html = render_html(items)
    fresh_md = render_md(items)
    if check:
        stale = [str(p.relative_to(ROOT)) for p, fresh in ((OUT_HTML, fresh_html), (OUT_MD, fresh_md))
                 if not p.exists() or p.read_text(encoding="utf-8") != fresh]
        if stale:
            print("✗ 原型总览陈了，重跑 python3 scripts/gen-proto-index.py：" + ", ".join(stale), file=sys.stderr)
            sys.exit(1)
        print(f"✓ 原型总览与登记表一致（{len(items)} 份）")
        return
    OUT_HTML.write_text(fresh_html, encoding="utf-8")
    OUT_MD.write_text(fresh_md, encoding="utf-8")
    print(f"✓ 已生成 {OUT_HTML.relative_to(ROOT)} 与 {OUT_MD.relative_to(ROOT)}（{len(items)} 份）")


if __name__ == "__main__":
    main()
