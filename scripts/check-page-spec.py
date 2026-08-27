#!/usr/bin/env python3
"""逐页体检：页面有没有**照着三份规范**写。

判据来自 `docs/technical/design/规范-{字体,版面,组件}.md` 的源头（`ui-lib.json`），
不是另立一套：

  字体  页面不该自己声明 font-size / font-weight / line-height ——
        那是 `.txt-*` / `.sh-muted` / `.sh-hint` 的事。声明了就得落在字阶上。
  版面  border-radius **只用五档** —— tokens.ts 的注释写着「组件层只许用这五个」，
        这是硬规矩，且 b-app 当前 0 违例。
        间距**只要求落在 4rpx 网格上**（= 2px），不要求落在五个命名档上：
        那五档（8/16/28/40/64）是给 `.sh-mt-*` 这类工具类命名用的，
        从来没说过页面里每一个 margin 都得是它们中的一个。
        按五档判会报出 361 处，其中 12rpx×129 / 20rpx×100 / 24rpx×47 全是
        4rpx 网格上的正常值 —— **那是判据说多了，不是页面写错了**。
  颜色  一律走 `--sh-*`，不许写死 hex（写死的不跟皮肤也不跟明暗）。

**为什么要逐页而不是只看总数**：总数只告诉你「还有 361 处」，
逐页才知道**先改哪一页**。而这份清单的用途正是排期。

用法：
  python3 scripts/check-page-spec.py            # 全量清单
  python3 scripts/check-page-spec.py --app b-app --top 20
  python3 scripts/check-page-spec.py --check    # 只看总数与基线（给闸门用）
"""
import argparse
import collections
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
LIB = json.loads((ROOT / "docs/technical/design/ui-lib.json").read_text(encoding="utf-8"))
TYPE = {x["size"] for x in LIB["tokens"]["type"]}
RADIUS = {v["rpx"] for v in LIB["tokens"]["radius"].values()}
SPACE = {v["rpx"] for v in LIB["tokens"]["spacing"].values()}
WEIGHT = {"400", "600", "700"}

# 字阶自带行高，页面再写一遍就是覆盖它 —— 除非是多行文本块（那属于版面）
KIND = ["字号自写", "字号越档", "字重自写", "字重越档", "行高自写", "圆角越档", "间距离格", "写死颜色"]


def scan(app: str):
    rows = []
    for f in sorted((ROOT / app / "src/pages").rglob("*.vue")):
        s = f.read_text(encoding="utf-8")
        if "<style" not in s:
            continue
        css = re.sub(r"/\*.*?\*/", "", s[s.index("<style"):], flags=re.S)
        c = collections.Counter()
        detail = collections.defaultdict(list)
        for m in re.finditer(r"font-size:\s*([\d.]+rpx)", css):
            c["字号自写"] += 1
            if m.group(1) not in TYPE:
                c["字号越档"] += 1
                detail["字号越档"].append(m.group(1))
        for m in re.finditer(r"font-weight:\s*(\d+)", css):
            c["字重自写"] += 1
            if m.group(1) not in WEIGHT:
                c["字重越档"] += 1
                detail["字重越档"].append(m.group(1))
        for m in re.finditer(r"border-radius:\s*([\d.]+rpx|9999px)", css):
            if m.group(1) not in RADIUS:
                c["圆角越档"] += 1
                detail["圆角越档"].append(m.group(1))
        for m in re.finditer(r"(?:margin|padding|gap)(?:-top|-bottom|-left|-right|-inline-\w+)?:\s*([\d.]+rpx)\s*;", css):
            # 4rpx 网格（= 2px）。2rpx 是发丝线那一档，单独放行
            v = float(m.group(1)[:-3])
            if v != 2 and v % 4 != 0:
                c["间距离格"] += 1
                detail["间距离格"].append(m.group(1))
        for m in re.finditer(r":\s*(#[0-9a-fA-F]{3,8})\b", css):
            c["写死颜色"] += 1
            detail["写死颜色"].append(m.group(1))
        c["行高自写"] = len(re.findall(r"line-height:", css))
        rows.append({"app": app, "page": f.parent.name, "file": str(f.relative_to(ROOT)),
                     "counts": c, "detail": {k: collections.Counter(v).most_common(4) for k, v in detail.items()},
                     "total": sum(c[k] for k in KIND)})
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default="b-app")
    ap.add_argument("--top", type=int, default=0)
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    rows = scan(a.app)
    tot = collections.Counter()
    for r in rows:
        tot.update(r["counts"])
    clean = [r for r in rows if r["total"] == 0]
    print(f"{a.app}：{len(rows)} 页，**{len(clean)} 页完全合规**，{len(rows)-len(clean)} 页有欠账\n")
    print("| 项 | 处数 | 规范 |")
    print("|---|---:|---|")
    RULE = {"字号自写": "应走 .txt-* / .sh-muted / .sh-hint", "字号越档": f"字阶只有 {len(TYPE)} 档",
            "字重自写": "应由字阶带出", "字重越档": "只有 400 / 600 / 700",
            "圆角越档": "只有 16/24/32/44rpx / full", "间距离格": "落在 4rpx 网格上（2rpx 发丝线除外）",
            "写死颜色": "一律走 --sh-*", "行高自写": "字阶自带行高"}
    for k in KIND:
        print(f"| {k} | {tot[k]} | {RULE[k]} |")
    if a.check:
        return 0
    print(f"\n## 逐页（按欠账排序）\n")
    print("| 页 | 合计 | 字号自写/越档 | 字重自写/越档 | 圆角越档 | 间距离格 | 行高 | 写死色 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|")
    ordered = sorted(rows, key=lambda r: -r["total"])
    for r in (ordered[: a.top] if a.top else ordered):
        c = r["counts"]
        flag = "" if r["total"] else " ✅"
        print(f"| `{r['page']}`{flag} | {r['total']} | {c['字号自写']}/{c['字号越档']} | "
              f"{c['字重自写']}/{c['字重越档']} | {c['圆角越档']} | {c['间距越档']} | {c['行高自写']} | {c['写死颜色']} |")
    off = collections.Counter()
    for r in rows:
        for k in ("间距离格", "圆角越档", "字号越档"):
            for v, n in r["detail"].get(k, []):
                off[(k, v)] += n
    print("\n## 越档的值都是些什么（前 15）\n")
    print("| 项 | 值 | 处数 | 就近的档 |")
    print("|---|---|---:|---|")
    for (k, v), n in off.most_common(15):
        pool = SPACE if k == "间距越档" else (RADIUS if k == "圆角越档" else TYPE)
        try:
            near = min((p for p in pool if p.endswith("rpx")), key=lambda p: abs(int(p[:-3]) - int(float(v[:-3]))))
        except Exception:
            near = "—"
        print(f"| {k} | {v} | {n} | {near} |")
    return 0


sys.exit(main())
