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
  python3 scripts/check-page-spec.py --app b-app   # 单端清单
  python3 scripts/check-page-spec.py --app b-app --top 20
  python3 scripts/check-page-spec.py --check       # 两端一起对基线（给闸门用）
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

# 与「文字排版」无关的选择器：emoji 当占位图时，字号就是**图的尺寸**，
# 行高是像素盒高（`line-height: 76`）。豁免口径**照抄 typography.test.ts** ——
# 那份的注释写着「把它们并进字阶，等于让『图多大』去迁就『字多大』」，
# 两处判据用两套豁免，迟早对不上。
NON_TEXT = re.compile(
    r"(?:cover|icon|emoji|avatar|logo|badge__n|sign|grip|dot|__img\b|fly__|__ph\b"
    # 2026-08-28 补两类：`imgs__i` 是评价配图（emoji 占位，宽高钉死 110rpx），
    # `dimgs__wait` 是上传中的省略号，字号取 40 是**为了跟旁边的 sh-icon 一样大**
    # —— 两者的 font-size 撑的都是图，不是字
    # `star` / `fav` 是评分与收藏的 emoji（库里的 sh-rating 只做展示，
    # 打分要交互，页面自己画是对的）；`\bimg\b` 是 150rpx 的方形图占位
    r"|imgs__|__wait\b|star|fav\b|\bimg\b)", re.I)

# 与 UnoCSS 工具类**同名**的页面类名。收编到 `.sh-row` / `.sh-wrap` / `.sh-center`
# 之后，横排是靠**全局**的库件给的（权重 0-1-0），而页面自己的 scoped 规则是
# 0-2-0 —— 原先页面写 `display: flex` 时，它稳压 UnoCSS 的 `.inline{display:inline}`；
# 收编后 flex 来自库件，与 UnoCSS 打平，**后加载的赢**，于是横排静默失效。
#
# 2026-08-28 goods-edit 的 `.inline`（3 处）与 home 的 `.grid`（1 处）就是这样断的：
# 类名、CSS、模板三边看着都对，vue-tsc 与自造件闸门全绿，**只有在浏览器里量
# computed display 才看得见**。已改名成 `.fieldrow` / `.tiles`。
#
# 名单只列 UnoCSS 里**改 display / position 的单词工具类** —— 那些才会与库件抢同
# 一个属性。`row` / `hero` 这类不是工具类，不必回避（当时一并查过，无全局同名规则）。
UNO_COLLIDE = {"inline", "block", "flex", "grid", "table", "contents", "hidden",
               "static", "fixed", "absolute", "relative", "sticky", "flow-root",
               "inline-block", "inline-flex", "inline-grid", "inline-table"}

KIND = ["字号自写", "字号越档", "字重自写", "字重越档", "行高自写", "圆角越档", "间距离格", "写死颜色", "撞工具类"]


def scan(app: str):
    """扫 `src/pages` **和** `src/components`。

    2026-09-06 加的 components：这道闸此前只看 pages/，报「两端 94 页全部合规」——
    而首页那一屏上每一行都不是页面画的，是 `biz-goods-card` 画的。
    扫描面一漏，**合规率就是在量一个不完整的东西**：components 下当场量出 123 处
    （字号自写 75 / 字重自写 20 / 行高自写 14 / 字号越档 6 / 间距离格 8），
    最大的一个文件一个人就占 35 处。

    与 `typography.test.ts` 的分工：那一份**一直**在扫 components（所以「字号在不在
    字阶上」是干净的），漏的是这一份特有的几条 —— 「字号该不该自己写」「行高该不该
    自己写」「颜色写没写死」。两把尺量的不是同一个东西，缺的那把在这儿。
    """
    rows = []
    roots = [(ROOT / app / "src/pages", "page"), (ROOT / app / "src/components", "comp")]
    # 组件库自己也要受自己的规矩管。**2026-09-06 之前它不在任何一道逐页闸门里** ——
    # 26 个件里 17 个带账，共 55 处，其中「34rpx/600 的标题」被各写了五遍
    #（sh-dialog / sh-sheet / sh-prompt / sh-scaffold / sh-theme-sheet），
    # 而那正是 `.txt-title` 那一档。定义字阶的地方在 base.css，不在这些 .vue 里 ——
    # 组件消费字阶，和页面一样。挂在 b-app 名下扫一次就够（库是两端共用的）。
    if app == "b-app":
        roots.append((ROOT / "packages/ui/src/components", "lib"))
    for base, kind in roots:
        if not base.exists():
            continue
        rows += _scan_dir(app, base, kind)
    return rows


def _scan_dir(app: str, base: pathlib.Path, kind: str):
    rows = []
    for f in sorted(base.rglob("*.vue")):
        s = f.read_text(encoding="utf-8")
        if "<style" not in s:
            continue
        css = re.sub(r"/\*.*?\*/", "", s[s.index("<style"):], flags=re.S)
        c = collections.Counter()
        detail = collections.defaultdict(list)
        for sel, body in [(m.group(1), m.group(2)) for m in re.finditer(r"([^{}]+)\{([^}]*)\}", css)]:
            if NON_TEXT.search(sel):
                css = css.replace(sel + "{" + body + "}", "")
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
        # 角写法（`border-top-right-radius` 那一族）也要判：
        # 2026-09-06 之前只认 `border-radius:`，于是 sh-uploader 的 8rpx 越档一直没人报。
        for m in re.finditer(r"border(?:-(?:top|bottom|start|end)-(?:left|right|start|end))?-radius:\s*([\d.]+rpx|9999px)", css):
            if m.group(1) not in RADIUS:
                c["圆角越档"] += 1
                detail["圆角越档"].append(m.group(1))
        for m in re.finditer(r"(?:margin|padding|gap)(?:-top|-bottom|-left|-right|-inline-\w+)?:\s*([\d.]+rpx)\s*;", css):
            # 4rpx 网格（= 2px）。2rpx 是发丝线那一档，单独放行
            v = float(m.group(1)[:-3])
            if v != 2 and v % 4 != 0:
                c["间距离格"] += 1
                detail["间距离格"].append(m.group(1))
        # 颜色一律走 token，**只有一个语义例外**：`--sh-danger` 是固定的语义红
        # （不随皮肤变），白字压它的取舍写在 base.css 的 `.sh-btn--danger-solid` 上。
        # 判「同一条规则里有没有 danger 底」，不靠注释里的魔法标记 ——
        # 上一版用标记，而扫描前先去了注释，标记自己被吃掉了。
        for rm in re.finditer(r"\{([^}]*)\}", css):
            rb = rm.group(1)
            # 白字压在**不随皮肤变**的那两层上：语义红（`.sh-btn--danger-solid`）
            # 与遮罩色（`--sh-scrim`，图上的角标与删除叉）。这两处白就是白，
            # 换成 `--sh-on-primary` 反而会跟着皮肤走 —— 深色皮肤下白字变暗字，压在暗底上。
            danger_bg = "var(--sh-danger)" in rb or "var(--sh-scrim)" in rb
            for m in re.finditer(r":\s*(#[0-9a-fA-F]{3,8})\b", rb):
                if danger_bg and m.group(1).lower() in ("#fff", "#ffffff"):
                    continue
                c["写死颜色"] += 1
                detail["写死颜色"].append(m.group(1))
        c["行高自写"] = len(re.findall(r"line-height:", css))
        # 撞 UnoCSS 工具类：只在**该元素同时挂着库件**时才算欠账 ——
        # 页面自己写全 display 的老写法压得住工具类，不算错；断的是收编过的那些。
        tpl = s[:s.index("<style")]
        for m in re.finditer(r'class="([^"]*\bsh-(?:row|wrap|center)\b[^"]*)"', tpl):
            for cls in m.group(1).split():
                if cls in UNO_COLLIDE:
                    c["撞工具类"] += 1
                    detail["撞工具类"].append(cls)
        name = f.parent.name if kind == "page" else f.stem
        rows.append({"app": app, "page": name, "file": str(f.relative_to(ROOT)),
                     "counts": c, "detail": {k: collections.Counter(v).most_common(4) for k, v in detail.items()},
                     "total": sum(c[k] for k in KIND)})
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default="both")
    ap.add_argument("--top", type=int, default=0)
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    rows = scan("b-app") + scan("c-app") if a.app == "both" else scan(a.app)
    # 扫描面自检：**「找出违规」型的判据，少扫就等于全绿**。这道闸 2026-09-06 之前
    # 只扫 pages/，于是「两端 94 页全部合规」这句话是真的、也是没用的 —— 首页那一屏
    # 上每一行都是 components/ 画的，那里当时有 123 处欠账。所以两边都要真的扫到。
    seen = {r["file"].split("/src/")[0] + "/" + r["file"].split("/src/")[1].split("/")[0]
            for r in rows}
    need = {"b-app/pages", "b-app/components", "c-app/pages", "c-app/components",
            "packages/ui/components"}
    if a.app == "both" and not need <= seen:
        print(f"✗ 扫描面不全，缺：{sorted(need - seen)}")
        print("  「找出违规」型的判据少扫一处就等于那一处全绿，而全绿看上去和真干净一模一样。")
        return 1
    if len(rows) < 60:
        print(f"✗ 只扫到 {len(rows)} 个文件，判据多半没走到目录 —— 空转的闸门恒绿")
        return 1
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
            "写死颜色": "一律走 --sh-*", "行高自写": "字阶自带行高",
            "撞工具类": "库件靠全局类给横排，与 UnoCSS 同名会被后加载的压掉"}
    for k in KIND:
        print(f"| {k} | {tot[k]} | {RULE[k]} |")
    if a.check:
        base = {l.strip() for l in (ROOT / "known-page-spec-debt.txt").read_text(encoding="utf-8").splitlines()
                if l.strip() and not l.startswith("#")}
        dirty = {f"{r['app']}/{r['page']}" for r in rows if r["total"]}
        new, fixed = sorted(dirty - base), sorted(base - dirty)
        if new:
            print("\n✗ 这些页面不照规范写（清单里没有）：\n  " + "\n  ".join(new))
            print("  判据见本文件顶部；修法：字号/字重/行高交给字阶，间距上 4rpx 网格。")
            return 1
        if fixed:
            print("\n✗ 这些页面已经合规了，把它们从 known-page-spec-debt.txt 里删掉：\n  " + "\n  ".join(fixed))
            print("  留着的话，那一页将来又写歪也不会有人发现 —— 清单只准变短。")
            return 1
        print(f"\n✓ 与清单一致（欠账 {len(base)} 页）")
        return 0
    print(f"\n## 逐页（按欠账排序）\n")
    print("| 页 | 合计 | 字号自写/越档 | 字重自写/越档 | 圆角越档 | 间距离格 | 行高 | 写死色 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|")
    ordered = sorted(rows, key=lambda r: -r["total"])
    for r in (ordered[: a.top] if a.top else ordered):
        c = r["counts"]
        flag = "" if r["total"] else " ✅"
        print(f"| `{r['page']}`{flag} | {r['total']} | {c['字号自写']}/{c['字号越档']} | "
              f"{c['字重自写']}/{c['字重越档']} | {c['圆角越档']} | {c['间距离格']} | {c['行高自写']} | {c['写死颜色']} |")
    off = collections.Counter()
    for r in rows:
        for k in ("间距离格", "圆角越档", "字号越档"):
            for v, n in r["detail"].get(k, []):
                off[(k, v)] += n
    print("\n## 越档的值都是些什么（前 15）\n")
    print("| 项 | 值 | 处数 | 就近的档 |")
    print("|---|---|---:|---|")
    for (k, v), n in off.most_common(15):
        pool = SPACE if k == "间距离格" else (RADIUS if k == "圆角越档" else TYPE)
        try:
            near = min((p for p in pool if p.endswith("rpx")), key=lambda p: abs(int(p[:-3]) - int(float(v[:-3]))))
        except Exception:
            near = "—"
        print(f"| {k} | {v} | {n} | {near} |")
    return 0


sys.exit(main())
