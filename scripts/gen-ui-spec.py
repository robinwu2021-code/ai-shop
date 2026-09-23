#!/usr/bin/env python3
"""三份界面规范：字体 / 版面 / 组件。**全部从产物生成，不手写。**

为什么不手写：这个仓库里有 170 份手写设计文档，而 2026-08-28 这一轮把界面从头理了一遍，
发现手写的规范无一例外地陈了 —— 它们描述的是「写文档那天」的样子，而代码一直在走。
一份说错了的规范比没有规范更糟：读的人会理直气壮地照它去对齐。

所以这三份的每一个数字都来自 `ui-lib.json`（由 `gen-ui-lib.py` 从 tokens.ts /
base.css / 组件源码 / 90 个页面扫出来），跑一次就同步一次。它挂在
`check-generated-docs` 上，陈了推不上去。

用法：python3 scripts/gen-ui-spec.py
"""
import json
import pathlib
import re
import collections

ROOT = pathlib.Path(__file__).resolve().parents[1]
LIB = json.loads((ROOT / "docs/technical/design/ui-lib.json").read_text(encoding="utf-8"))
BASE = (ROOT / "packages/ui/src/styles/base.css").read_text(encoding="utf-8")
GEN = (ROOT / "scripts/gen-ui-lib.py").read_text(encoding="utf-8")

STATUS = {'规范-字体.md': '> 状态：**生成物 · 长期有效** · 创建 2026-08-28\n> 上游：`packages/shared/src/design/tokens.ts` + `packages/ui/src/styles/base.css` → [`ui-lib.json`](./ui-lib.json) → **本文**\n> 定位：字号 / 字重 / 行高 / 密度 —— 「一个字长什么样」。版面见 [规范-版面](规范-版面.md)，件见 [规范-组件](规范-组件.md)。\n', '规范-版面.md': '> 状态：**生成物 · 长期有效** · 创建 2026-08-28\n> 上游：`tokens.ts` + `base.css` + 组件源码 → [`ui-lib.json`](./ui-lib.json) → **本文**\n> 定位：一屏东西怎么摆 —— 画布、圆角与间距档、页面框、行与列表、浮层层级、皮肤与明暗。\n', '规范-组件.md': '> 状态：**生成物 · 长期有效** · 创建 2026-08-28\n> 上游：`packages/ui/src/components/*.vue` + `base.css` + 90 个页面 → [`ui-lib.json`](./ui-lib.json) → **本文**\n> 定位：有哪些件、各自用在哪、调用点多少，以及「算不算自己画」的 18 条判据。\n', '规范-页面.md': '> 状态：**生成物 · 长期有效** · 创建 2026-09-08\n> 上游：`c-app/src/pages/**` + `b-app/src/pages/**`（98 页现扫）→ **本文**\n> 定位：**一页由什么搭起来、必须处理哪几种态** —— 骨架、加载/空/出错、贴底条。\n'}

HEAD = "> **本文件由 `scripts/gen-ui-spec.py` 生成，请勿手改。**\n> 数字来自 `ui-lib.json`（tokens.ts / base.css / 组件源码 / 90 个页面），跑一次同步一次。\n> 改规范改源头，然后 `python3 scripts/gen-ui-spec.py`。\n"


def usage(u):
    """调用点：B / C，再加「库内」——**组件里用组件**的那些。

    不并进 B/C：一次引用算两次是假账，只算进一列又是瞎归。
    但它必须出现在表里 —— `sh-tabbar` / `sh-confirm` / `sh-pick` / `sh-prompt`
    由 `sh-scaffold` 无条件挂在**每一页**上，而表里此前写着 `0 / 0`，
    读的人会当成死代码删掉。
    """
    b, c, lib = u.get("b-app", 0), u.get("c-app", 0), u.get("lib", 0)
    return f"{b} / {c}" + (f" · 库内 {lib}" if lib else "")


def criteria():
    """从 gen-ui-lib.py 的 ROLLED 表里抽判据 —— 它是「什么算自己画」的唯一定义"""
    blk = GEN[GEN.index("ROLLED = ["):]
    blk = blk[: blk.index("\n]")]
    out = []
    for m in re.finditer(r'\("(\w+)",\s*"([^"]+)",[^\n]*?(?:,\s*(None|"[^"]*"|\'[^\']*\'))?\)\s*,', blk, re.S):
        out.append((m.group(1), m.group(2)))
    return out


# ─────────────────────────────────────────────────────────── 字体

# ══════════════════════════════════════════════════════════════════════
# 闸门清单：**从测试文件里读，不手抄**
#
# 三份规范此前有一个共同的毛病：**读者分不出哪一行有闸门、哪一行只是约定**。
# 「只用这五档」和「间距落在 4rpx 网格上」在纸面上长得一样重，而前者曾经是
# 一句从来没成立过的话（39% 命中率），后者一直有断言守着。
#
# 手抄一份清单只会重演同一个问题（第 41 条断言不会自己出现在文档里），
# 所以这里直接解析 `describe(...)` / `it(...)`：**规范里的闸门一节，
# 就是测试文件的投影**。
# ══════════════════════════════════════════════════════════════════════

GATE_FILES = [
    ("packages/shared/tests/ui-package.test.ts", "ui-package"),
    ("packages/shared/tests/typography.test.ts", "typography"),
    ("packages/shared/tests/safe-area-fallback.test.ts", "safe-area"),
]
# 「这条断言归哪一份规范」——按 describe 的措辞分。落不进任何一档的进「其它」，
# 那一档非空就是提醒：要么这条规矩没归好类，要么这份表该多一档
GATE_DOMAIN = {
    "字体": ("字阶", "字号", "字重", "行高", "letter-spacing", "两端的字号"),
    "版面": ("间距", "块间缝", "浮层", "投影", "版面", "容器", "缝要露", "圆角", "安全区",
             "点按面积", "动效", "版心", "行尾箭头"),
    # 「断言 / 登记」这两个词把**关于规范自身的元规矩**归到这一份 ——
    # 《规范·组件》本来就装着「算不算自己画」的判据与「库件登记齐全」，是元规矩的家
    "组件": ("组件库", "公共件", "库件", "件不许", "小程序的块间缝", "传给组件", "两端独立",
             "断言", "登记"),
    # 页面级：骨架、四种态、贴底条 —— 眼下还没有断言落在这一档，
    # 所以《规范·页面》的闸门一节暂时是空的。**这本身是个信号**：
    # 那一份现在全是「约定」，一条也拦不住 push
    "页面": ("页面骨架", "空态", "加载", "出错", "四种态", "scaffold"),
}
# 空转守卫（「有东西可扫」那一类）不是规矩，是判据自己的体检 —— 不进规范
GATE_SKIP = ("有东西可扫", "有文件可扫", "空转", "读到了", "扫到了", "扫得到", "量到了",
             "有那几张表", "文档在", "扫描面", "有文件", "扫到了足够")


def gates():
    """→ {域: [(判据文件, describe, it)]}，外加落不进任何域的那些"""
    out = {k: [] for k in GATE_DOMAIN}
    out["其它"] = []
    for rel, tag in GATE_FILES:
        src = (ROOT / rel).read_text(encoding="utf-8")
        cur = ""
        for m in re.finditer(r'\b(describe|it)\("([^"]+)"', src):
            if m.group(1) == "describe":
                cur = m.group(2); continue
            title = m.group(2)
            if any(k in title for k in GATE_SKIP):
                continue
            # **先看这条规矩自己怎么说的，再看它住在哪个 describe 里。**
            # 反过来会归错：「圆角只用 token 五档」住在 `typography.test.ts` 的
            # 「字阶」块里（历史原因，那个文件先有），但它显然是版面规矩 ——
            # 按 describe 判就被「字阶」抢进了《规范·字体》。
            # 判据该跟着规矩的措辞走，不跟着它碰巧躺在哪个文件里走。
            dom = next((d for d, kws in GATE_DOMAIN.items() if any(k in title for k in kws)), None) \
                or next((d for d, kws in GATE_DOMAIN.items() if any(k in cur for k in kws)), "其它")
            out[dom].append((tag, cur, title))
    return out


def gate_section(domain: str) -> list[str]:
    g = gates()
    rows = g.get(domain, [])
    if not rows:
        return []
    L = [f"\n## 闸门：这一份里有 {len(rows)} 条是能自动判的\n"]
    L.append("**这一节是从测试文件里读出来的，不是手抄的。** 规范里的话如果没有对应的断言，")
    L.append("它就只是一个约定 —— 而这个仓库栽过：「间距只用这五档」写了很久，实际命中率 39%。")
    L.append("下表每一行都能让 push 失败。\n")
    L.append("| 判据 | 说的是什么 |")
    L.append("|---|---|")
    seen = set()
    for tag, desc, title in rows:
        key = (desc, title)
        if key in seen:
            continue
        seen.add(key)
        L.append(f"| `{tag}` · {desc} | {title} |")
    extra = g.get("其它", [])
    if extra and domain == "组件":
        L.append(f"\n还有 {len(extra)} 条没归进三份规范的任何一档 —— ")
        L.append("要么是这条规矩没归好类，要么是这张表该多一档：\n")
        for tag, desc, title in extra:
            L.append(f"- `{tag}` · {desc} → {title}")
    return L


# ─────────────────────────────────────────────────────────── 页面
def page_facts():
    """扫两端所有页面，量「一页由什么搭起来、缺哪几种态」。

    数字全部现算 —— 手抄的比例会陈（《规范·字体》里那句「C 端只有 1 个调用点」
    错了很久，就是因为它是写死的）。
    """
    import collections
    rows = []
    for app in ("c-app", "b-app"):
        for f in sorted((ROOT / app / "src/pages").rglob("index.vue")):
            src = f.read_text(encoding="utf-8")
            tpl = src.split("<style")[0]
            m = re.search(r"<sh-scaffold([^>]*)>", tpl)
            attrs = m.group(1) if m else ""
            conds = re.findall(r'<sh-empty[^>]*v-(?:if|else-if)="([^"]+)"', tpl)
            # 与断言同源：把守卫交给件（`:pending`）也算守好了
            handed = bool(re.search(r'<sh-empty[^>]*:pending=', tpl))
            rows.append({
                "app": app, "page": f.parent.name,
                "scaffold": "sh-scaffold" in tpl,
                "titleKey": "title-key" in attrs,
                "fetch": bool(re.search(r"\bapi\.\w+\(", src)),
                "catch": "catch" in src,
                "errState": bool(re.search(r"\b(failed|errMsg)\b", src)),
                "empty": bool(conds),
                # 空态的显示条件里有没有把「还没加载完」排除掉
                # 判据与 `ui-package.test.ts`「空态不许在还不知道时出现」**逐字同源** ——
                # 末尾那个 `(?!\s*\.)` 不能少：`b-app/delivery` 的 `!pending.length` 里
                # `pending` 是待处理列表不是加载标志，少了它这份文档会比闸门少数一页。
                "emptyGuarded": handed or any(
                    re.search(r"\b(loading|loaded|pending|inited|ready|firstLoad)\b(?!\s*\.)", c)
                    for c in conds),
                "bottomBar": ("sh-actionbar" in tpl or "sh-savebar" in tpl),
            })
    return rows


def pages() -> str:
    rows = page_facts()
    n = len(rows)
    def pct(k, base=None):
        b = base if base is not None else n
        c = sum(1 for r in rows if r[k])
        return c, b, (round(100 * c / b) if b else 0)
    L = ["# 界面规范 · 页面\n", HEAD]
    L.append("\n> 字怎么长见 [规范·字体](规范-字体.md)，东西怎么摆见 [规范·版面](规范-版面.md)，")
    L.append("> 有哪些件见 [规范·组件](规范-组件.md)。")
    L.append("> ⚠️ **本文的数字不来自 `ui-lib.json`，是每次生成时现扫两端 `pages/` 得到的** ——")
    L.append("> 上面那句通用页脚对这一份不成立。\n")

    L.append(f"\n## 骨架：{n} 页的实测\n")
    L.append("| 项 | 覆盖 | 规矩 |")
    L.append("|---|---:|---|")
    for k, rule in [
        ("scaffold", "**每页根元素必须是 `sh-scaffold`** —— 否则小程序端换肤 / RTL / 三语标题全不生效"),
        ("titleKey", "标题走 `title-key`（i18n 词条）。详情页的标题是动态的（商品名、店名），那几页例外"),
        ("bottomBar", "贴底操作用 `sh-actionbar` / `sh-savebar`，不自己写 `position: fixed`"),
    ]:
        c, b, p_ = pct(k)
        L.append(f"| {k} | {c}/{b}（{p_}%） | {rule} |")

    fetch = [r for r in rows if r["fetch"]]
    withEmpty = [r for r in rows if r["empty"]]
    guarded = [r for r in withEmpty if r["emptyGuarded"]]
    errs = [r for r in fetch if r["errState"]]
    L.append("\n## 一个拉数据的页面有四种态\n")
    L.append("**「还不知道」和「确定没有」是两回事，而现在它们长得一样。**\n")
    L.append("| 态 | 该显示什么 | 实测 |")
    L.append("|---|---|---|")
    L.append(f"| 加载中 | **不显示空态**（骨架，或什么都不显示） | 有空态的 {len(withEmpty)} 页里，"
             f"只有 **{len(guarded)}** 页把「加载中」从空态条件里排除了 |")
    L.append("| 有数据 | 内容 | — |")
    L.append(f"| 确定为空 | `sh-empty`，并给一句「接下来能做什么」 | 其余 "
             f"**{len(withEmpty) - len(guarded)}** 页会在数据到达前**先闪一下空态** |")
    L.append(f"| 出错 | **与「空」区分开** —— 空是「这儿本来就没有」，出错是「没取到，可以重试」 | "
             f"{len(fetch)} 页拉数据、{sum(1 for r in fetch if r['catch'])} 页 `catch` 了，"
             f"但只有 **{len(errs)}** 页有出错态 |")
    L.append("\n⚠️ **这不是理论问题。** 2026-09-08 在 `my-memberships` 上逐帧采样：")
    L.append("空态**可见约 175ms**（102ms → 277ms）才被数据顶掉 —— 而这是本地 mock，")
    L.append("真机弱网下这个窗口是 0.5~2 秒。用户看到的是「暂无会员」，然后它翻成一个列表。\n")
    L.append("\n写法：`v-if=\"loaded && !list.length\"`，不是 `v-if=\"!list.length\"`。\n")

    L.append("\n## 四个名字，三件事\n")
    L.append("表示这几种态的变量名眼下有四种（`loading` / `pending` / `loaded` / `failed`），")
    L.append("而它们表达的其实是**两个正交的问题**：「第一次加载完了没有」与「这次请求成不成功」。")
    L.append("`loading` 与 `loaded` 尤其容易互相顶替 —— 前者是瞬时的（含下拉刷新），")
    L.append("后者是一次性的（首屏到过没有）。**判空态该用后者**：刷新时不该把列表换成空态。\n")
    L += gate_section("页面")
    return "\n".join(L) + "\n"


def typography() -> str:
    t = LIB["tokens"]["type"]
    L = [f"# 界面规范 · 字体\n", HEAD]
    L.append("\n## 一句话\n")
    L.append("**字号只用下面这几档，字重只有三种，层级靠颜色与留白，不靠字号。**\n")
    L.append(
        "\n这条规矩的来历是数出来的：收编之前 C 端有 **33 个不同字号**（19–104rpx），"
        "而字重只出现过 500/600/700 —— 600 与 700 合计 97 处、500 仅 1 处，"
        "也就是**全站没有一处显式的常规字重**。首页 ≥12px 的文本里 76% 是粗体："
        "读者眼里没有轻重之分，等于全都不重要。\n"
    )
    L.append(f"\n## 字阶（{len(t)} 档）\n")
    L.append("| 类 | rpx | px | 字重 | 行高 | 用在哪 | 调用点 B/C |")
    L.append("|---|---:|---:|---:|---:|---|---:|")
    for x in t:
        blk = next((b for b in LIB["blocks"] if b["class"] == x["class"]), {})
        when = (blk.get("when") or "—").replace("\n", " ")
        L.append(
            f"| `{x['class']}` | {x['size']} | {x['px']} | {x['weight']} | {x['lineHeight']} | {when[:46]} | {usage(blk.get('usage', {}))} |"
        )
    L.append("\n## 四条硬规矩\n")
    L.append("1. **700 只给价格**，600 只给标题与按钮，其余一律 400。")
    L.append("2. **行高按用途分档，不按语言分档。** 中文要 1.5 以上才不挤，拉丁 1.4 就够；")
    L.append("   取中文的下限对英文也不难看，而按语言切换行高会让同一个列表在中英文下高度不同，")
    L.append("   横滑卡、等高栅格全要跟着变。")
    L.append("3. **字阶里不含 `letter-spacing`。** 负字距是拉丁字母的排版习惯，中文小字号下收紧会让笔画粘连，")
    L.append("   而同一个类要同时承载中 / 英 / 阿三种文字 —— 少一个轴，三种语言就少三种试错。")
    L.append("4. **同一行两端的字号，要么相同，要么至少差 4rpx。** 24 / 26 / 28 三档挤在 4rpx 里，")
    L.append("   同一行两端各取一档（如「标签 `.txt-body`(28) + 值 `.txt-sub`(26)」）只差 1px ——")
    L.append("   小到看不出是有意的，层次全靠颜色扛，看着就是没对齐。")
    L.append("   合规的两种写法都在用：**同档**（靠颜色/字重分，数据行的主流）")
    L.append("   或**差 ≥4rpx**（`body(28) → caption(24)`，导航行的写法）。")
    L.append("   来历：2026-09-07 有人一眼看出「我的」页的字跟别处不一样 —— 量下来全站 104 个")
    L.append("   两端对齐行用了 **22 种**「标签档 → 值档」组合，根本不存在「常见写法」，")
    L.append("   那一页只是碰巧用了差 1px 的那种。`ui-package.test.ts` 有断言守着。")
    dens = LIB["tokens"].get("density", {})
    if dens:
        L.append("\n## 密度变量：同一个类，两端两个值\n")
        L.append("C 端是顾客逛店（松一点显精致），B 端是店主一天扫几十次的作业台，**密度即效率**。\n")
        WHAT = {
            "--sh-pad-card": "卡片内边距",
            "--sh-pad-page": "页面左右/上内边距",
            "--sh-pad-empty": "空态上下留白",
            "--sh-gap-tabs": "筛选条间距",
            "--sh-fs-sub": "次要文字字号",
        }
        cvals, bvals = dens.get("c", {}), dens.get("b", {})
        L.append("| 变量 | C 端 | B 端 | 管什么 |")
        L.append("|---|---:|---:|---|")
        for k in cvals:
            L.append(f"| `{k}` | {cvals.get(k, '—')} | {bvals.get(k, '—')} | {WHAT.get(k, '—')} |")
        # ⚠️ 这里原本是**手写死的一段**：「字阶只在 B 端落了地，C 端 9 档合计只有 1 个调用点，
        #    `.sh-h1`/`.sh-h2` 还有 26 处」。2026-09-07 重核：那两个旧名**一处都没有了**，
        #    C 端 `.txt-*` 有 539 个调用点。写死的数字不会自己更新，改成算出来的。
        tot = {a: sum(b["usage"].get(a, 0) for b in LIB["blocks"] if b["class"].startswith(".txt-"))
               for a in ("b-app", "c-app")}
        L.append(f"\n字阶两端都落了地：**B 端 {tot['b-app']} 个调用点 · C 端 {tot['c-app']} 个**。")
        L.append("（这一行是算出来的。此前写死着「C 端只有 1 个调用点、旧名 `.sh-h1`/`.sh-h2` 还有 26 处」，")
        L.append("而那两个旧名早就一处不剩 —— 手写的数字不会自己更新。）")
    L.append("\n## 数字与 RTL\n")
    L.append("`.sh-num`：等宽数字，且在 RTL 下强制 LTR 方向 —— **金额、百分比、倒计时是 LTR 序列**，")
    L.append("跟着 RTL 走会把符号甩到另一端（`-25%` 变成 `25%-`）。\n")
    old = [b for b in LIB["blocks"] if b["group"] == "字阶（旧名）"]
    if old:
        L.append("\n## 还没退休的旧名\n")
        L.append("| 类 | 与哪一档同值 | 调用点 B/C |")
        L.append("|---|---|---:|")
        for b in old:
            L.append(f"| `{b['class']}` | {(b.get('when') or '—')[:40]} | {usage(b['usage'])} |")
    L += gate_section("字体")
    return "\n".join(L) + "\n"


# ─────────────────────────────────────────────────────────── 版面
def layout() -> str:
    tk = LIB["tokens"]
    L = [f"# 界面规范 · 版面\n", HEAD]
    L.append(f"\n## 画布\n\n基准 **{LIB['canvas']['base']}**，`1rpx = {LIB['canvas']['rpxToPx']}px`。")
    L.append("H5 与 App 都把 rpx 编译成 rem，运行时 `html font-size = 屏宽 / 23.4375`（375 → 16px）。")
    L.append("**两端产物里这套换算逐字节相同** —— 2026-08-28 对比过 `build:h5` 与 `build:app`。\n")
    for name, label in [("radius", "圆角"), ("spacing", "间距")]:
        L.append(f"\n## {label}（{len(tk[name])} 档）\n")
        L.append("| 档 | rpx | px |")
        L.append("|---|---:|---:|")
        for k, v in tk[name].items():
            L.append(f"| `{k}` | {v['rpx']} | {v['px']} |")
    L.append("\n**圆角只用这五档。** 差 4rpx 的两个圆角没人分得出，只会让人各写各的 —— ")
    L.append("`typography.test.ts` 有断言守着，越档推不上去。\n")
    L.append("\n⚠️ **间距那张表不是白名单，是一张 4rpx 网格。**")
    L.append("此前它被写成「五档 8/16/28/40/64」并配着一句「只用这几档」，")
    L.append("而全仓 1292 处间距取值里落在那五档上的只有 **39%** —— `20rpx` 用了 205 次，")
    L.append("而档位里的 `64rpx` 只有 2 次。按用量排下去就是「4 的倍数全都在用」。")
    L.append("闸门查的是**网格**，上表只是把网格上真正在用的成员列出来给人看。")
    L.append("加一个新数之前先问：它和邻居差的那 4rpx，有没有人分得出？\n")
    L.append("\n真正决定「间距该多大」的是**语义变量** —— `--sh-pad-page` / `--sh-pad-card` /")
    L.append("`--sh-pad-empty` / `--sh-gap-block` / `--sh-gap-tabs`，它们按端有不同的值（见《规范·字体》的密度表）。\n")
    L.append("\n## 页面框\n")
    L.append("所有页面走 `sh-scaffold`：它管标题、内边距（`--sh-pad-page`）、底部菜单占位、")
    L.append("宽屏收窄（>600px 收成 375 版心）与安全区。**`position: fixed` 的悬浮条不用各自处理宽屏** ——")
    L.append("scaffold 的 transform 让它们以应用框为包含块，漏改一处就会横跨整屏。\n")
    # ── 容器：**页面可以排版，不可以自己画容器** ──────────────────────────
    #    这一节此前不存在，于是 14 个页面在顶层各画了一份白卡/提示条 ——
    #    圆角 16/24/32、内边距十几种。规则本身有断言守着
    #   （ui-package.test.ts「版面：页面不自己画容器」），文档这里只是把它说出来。
    L.append("\n## 容器：四个，页面不自己画\n")
    L.append("一屏东西装在**四种盒子**里，全部由库件给。页面在 `sh-scaffold` 顶层自己写")
    L.append("`background: var(--sh-surface)` 或 `var(--sh-*-tint)` 而不挂容器类 —— 断言直接红。\n")
    L.append("| 件 | 什么时候用 | 别拿它当 |")
    L.append("|---|---|---|")
    for cls in [".sh-card", ".sh-block", ".sh-cells", ".sh-notice"]:
        b = next((x for x in LIB["blocks"] if x["class"] == cls), None)
        if b:
            L.append(f"| `{cls}` | {b.get('when') or '—'} | {b.get('avoid') or '—'} |")
    tones = [x for x in LIB["blocks"] if x["class"].startswith(".sh-notice--")]
    L.append(f"\n提示条有 **{len(tones) + 1} 档**（主色 / "
             + " / ".join(x["class"].split("--")[1] for x in tones)
             + "），底与字成对翻 —— 只改底色会得到一段读不清的字。\n")
    L.append("\n## 行与列表\n")
    L.append("| 件 | 什么时候用 | 别拿它当 |")
    L.append("|---|---|---|")
    # 整个 `行与列` 组都列出来 —— 手抄一份名单的话，新加的件不会自己出现在文档里，
    # 而「文档里没有」与「库里没有」在读的人眼里是同一件事
    for b in [x for x in LIB["blocks"] if x["group"] == "行与列"]:
        L.append(f"| `{b['class']}` | {b.get('when') or '—'} | {b.get('avoid') or '—'} |")
    L.append("\n间距**由项自己挂 `.sh-mt-* / .sh-mb-*`**，不是容器给 gap —— ")
    L.append("36 个列表里 35 个的容器还装着分组标题与说明，容器一改 gap，标题与第一项的距离也跟着变。\n")
    L.append("\n## 浮层层级\n")
    L.append("| 层 | z-index | 说明 |")
    L.append("|---|---:|---|")
    # ⚠️ 层级现在写成 `z-index: var(--sh-z-tabbar)`，不再是字面量 ——
    #    只认 `\d+` 的话这一列会**整列变成 `—`**，而文档照样生成、照样看着完整。
    #    所以先把 base.css 里的 `--sh-z-*` 读成一张表，再拿它解析组件里的引用。
    zmap = dict(re.findall(r"--sh-z-([a-z-]+):\s*(\d+)", (ROOT / "packages/ui/src/styles/base.css").read_text(encoding="utf-8")))
    for f, note in [("sh-tabbar", "底部菜单"), ("sh-actionbar", "悬浮内缩通栏"), ("sh-sheet", "底部弹层"), ("sh-dialog", "居中对话框")]:
        p = ROOT / f"packages/ui/src/components/{f}.vue"
        src = p.read_text(encoding="utf-8") if p.exists() else ""
        zs = {int(m) for m in re.findall(r"z-index:\s*(\d+)", src)}
        zs |= {int(zmap[k]) for k in re.findall(r"z-index:\s*var\(--sh-z-([a-z-]+)\)", src) if k in zmap}
        L.append(f"| `{f}` | {' / '.join(map(str, sorted(zs))) or '—'} | {note} |")
    L.append("\n对话框永远在最上面 —— 它是要人立刻回答的那一个。弹层叠弹层用 `sh-sheet` 的 `stacked`。\n")
    # 投影与 z-index 是「表达高度」的两种手段，放一起，好让人看出该用哪一种
    sh = {k: v for k, v in LIB["tokens"]["constants"].items() if k.startswith("--sh-shadow-")}
    if sh:
        L.append("\n### 投影（2 档）\n")
        L.append("| 档 | 值 | 用在哪 |")
        L.append("|---|---|---|")
        WHERE = {"--sh-shadow-up": "贴屏幕边缘的条（底部菜单）—— 分界主要靠 `--sh-hairline`，投影只补一点纵深",
                 "--sh-shadow-float": "真的浮在内容之上、可被拖动的（FAB、拖起来的行）"}
        for k, v in sh.items():
            L.append(f"| `{k}` | `{v}` | {WHERE.get(k, '—')} |")
        L.append("\n⚠️ **别拿 `--sh-scrim` 当投影色。** 它是**蒙层**色（45% 的黑，"
                 "活是压暗弹层背后的整屏）—— 当投影用会得到一条又黑又宽的灰带。"
                 "此前全仓 5 处投影全都抓了它，2026-09-06 用户直接报了「底部工具栏阴影太多」。"
                 "`ui-package.test.ts` 有两条断言守着。\n")
        L.append("\n深色态不另给一份：黑投影压在深色面上几乎看不见，而这是对的 ——"
                 "深色界面靠面色的明度差表达高度，不靠投影。\n")
    # 壳的几何（遮罩 / 面板 / 抓手条）也归这一节：它和 z-index 是同一个问题的两半 ——
    # 「浮层长什么样」与「谁压谁」。此前三个弹层各画一份，就是因为没有一处说过它长什么样
    shell = [x for x in LIB["blocks"] if x["group"] == "浮层"]
    if shell:
        L.append("\n浮层的**壳**由三个积木给，件只管自己的内容与层级：\n")
        L.append("| 件 | 什么时候用 | 别拿它当 |")
        L.append("|---|---|---|")
        for b in shell:
            L.append(f"| `{b['class']}` | {b.get('when') or '—'} | {b.get('avoid') or '—'} |")
        L.append("\n**三个件此前各画了一份**（`sh-sheet` / `sh-prompt` / `sh-theme-sheet`）——")
        L.append("遮罩逐字节相同，抓手条的下外边距却是 32 / 28 / 28 三个数。\n")
    L.append("\n## 深浅与皮肤\n")
    L.append(f"{len(tk['skins'])} 套皮肤 × 明暗两态。切换要**同时**翻两处：")
    L.append("H5/App 改 `<html data-skin data-theme>`，小程序改 `.sh-root.skin-*.mode-*`（`sh-scaffold` 统一注入）。\n")
    L.append("\n⚠️ **明暗归我们的 `mode` 管，不跟系统走。** uni 自带的 `--UI-*` 变量在 App 产物里")
    L.append("**只在 `@media (prefers-color-scheme: dark)` 里定义过** —— 系统浅色时它们根本没有定义，")
    L.append("用到它们的整条声明会被丢弃（内置件掉底色），系统深色时又跟 `--sh-*` 撞成两套色。")
    L.append("`base.css` 因此无条件补了一份浅色默认，位置在 uni 那段之后。\n")
    L += gate_section("版面")
    return "\n".join(L) + "\n"


# ─────────────────────────────────────────────────────────── 组件
def components() -> str:
    L = [f"# 界面规范 · 组件\n", HEAD]
    c = LIB["counts"]
    L.append(f"\n**{c['components']} 个组件 · {c['blocks']} 个积木 · 扫过 {c['pages']} 个页面。**\n")
    L.append("\n组件与积木的分工：**积木是一条 CSS 类**（没有行为，随便贴），")
    L.append("**组件有行为或结构**（插槽、事件、状态）。同一个东西不要两头都做。\n")
    # ── 按钮还是文字 ────────────────────────────────────────────────
    #    **这一节是硬编码的散文，不是从 ui-lib.json 算出来的。** 逐件说明（下面两张表的
    #    「什么时候用」）回答的是「这个件干什么」，而画一屏时真正卡住人的是反过来那句：
    #    「这个动作该长成什么样」。没有它，同一类动作在两页里会长成两种东西 ——
    #    2026-09-23 盘点：列表行尾的动作，b-app 有 20 页用文字、7 页用小按钮。
    L.append("\n## 按钮还是文字\n")
    L.append("**先问「点下去会发生什么」，再挑形态。** 四问，按顺序：\n")
    L.append("| 问 | 答「是」 | 形态 |")
    L.append("|---|---|---|")
    L.append("| 1. 点下去会改数据、或发起一段流程吗？ | 不会，只是换一屏 / 换一个筛选 | "
             "整行可点 + `sh-go` 的「›」；筛选用 `.sh-chip`；新增一条用 `sh-add` |")
    L.append("| 2. 它是这一屏的主线吗？ | 是 | 实心 `.sh-btn`，贴底 `sh-actionbar`；**一屏一个** |")
    L.append("| 3. 它属于某一行 / 某张卡吗？ | 推进流程（发货、同意退款、核销、切到这家） | "
             "`.sh-btn .sh-btn--sm` |")
    L.append("| | 管理这个对象（改名、停用、撤销、展开、全选） | `.sh-link`（弱一档 `--quiet`，要当心 `--warn`） |")
    L.append("| 4. 危险吗？ | 页面上 | `.sh-btn--danger`（描边 + 墨字） |")
    L.append("| | 二次确认弹层里的最终一击 | `.sh-btn--danger-solid` |")
    L.append("\n并排两个动作：主的实心，次的降 `--soft`；「取消」用 `--muted`。\n")
    L.append("\n**四条不要：**\n")
    L.append("- **主操作不要只有文字** —— 没有底色的主按钮，在一屏东西里找不着，商家会以为这页还没做完；")
    L.append("- **`.sh-chip` 不当按钮用** —— 它说的是「这是个状态 / 筛选项」，拿它发起写操作会让人以为点了只是筛一下；")
    L.append("- **同一页里同一类动作只有一种形态** —— 这一行「改名」是文字、那一行「改名」是小按钮，"
             "读的人会去猜两者有什么不同，而其实没有；")
    L.append("- **用不了就别摆一个灰的** —— 灰按钮看上去像坏了。要么整条不出现，要么就地写一句为什么"
             "（见《规范·页面》的退化路径）。\n")
    L.append("\n点按区一律 ≥ 88rpx（44px）：小按钮与文字动作自己不够大时挂 `.sh-hit`，它只撑点按区、不改外观。\n")
    L.append(f"\n## 组件（{c['components']}）\n")
    # ⚠️ **「什么时候用」这一列此前不存在。** `note` 一直算着（`COMP_NOTES` +
    #    首行注释兜底），但表里只有 scope / props / 调用点数 —— 于是这份「组件规范」
    #    从头到尾没有一句话说该挑哪个。三个底部弹层能并存，一半原因在这儿。
    L.append("| 组件 | 什么时候用 | 作用域 | props | 调用点 B/C（· 库内） |")
    L.append("|---|---|---|---|---:|")
    for x in sorted(LIB["components"],
                    key=lambda x: -(x["usage"].get("b-app", 0) + x["usage"].get("c-app", 0)
                                    + x["usage"].get("lib", 0))):
        props = ", ".join(p.split(":")[0] for p in x["props"][:4]) or "—"
        L.append(f"| `{x['name']}` | {x.get('note') or '—'} | {x['scope']} | {props} | {usage(x['usage'])} |")
    # ── 件的字号与间距足迹 ──────────────────────────────────────────
    #    清单此前只说「有哪些件、props 是什么、多少调用点」，**看不出一个件
    #    在字号与间距上占了什么位置**。问「sh-empty 的字多大」只能去开源码，
    #    而那正是规范该替人回答的问题。
    L.append("\n## 件的字号与间距足迹\n")
    L.append("**一个件用了字阶的哪几档、间距的哪几个数、又依赖哪些别的件。**")
    L.append("这一节回答的是「我要改字阶的某一档，会动到谁」——")
    L.append("此前只能全仓 grep，而 grep 分不清「件自己用的」与「调用点传进去的」。\n")
    L.append("| 组件 | 字阶档 | 间距(rpx) | 依赖 |")
    L.append("|---|---|---|---|")
    for x in sorted(LIB["components"], key=lambda x: x["name"]):
        u = x.get("uses") or {}
        tiers = " ".join(f"`{t}`" for t in u.get("tiers", [])) or "—"
        raw = u.get("rawFontSize") or []
        if raw:
            tiers += f" ⚠️自写 {'/'.join(map(str, raw))}rpx"
        sp = "/".join(map(str, u.get("spacing", []))) or "—"
        deps = " ".join(f"`{d}`" for d in u.get("deps", [])) or "—"
        L.append(f"| `{x['name']}` | {tiers} | {sp} | {deps} |")
    # 反查：改一档会动到谁。正查表（上面）扫一列也能得到，但那是九列 ×34 行，
    # 而「改这一档会动到哪些件」是实际会问的那一句
    L.append("\n### 反查：改一档，动到谁\n")
    L.append("| 字阶档 | 用到它的件 |")
    L.append("|---|---|")
    idx: dict[str, list[str]] = {}
    for x in LIB["components"]:
        for t in (x.get("uses") or {}).get("tiers", []):
            idx.setdefault(t, []).append(x["name"])
    for t in sorted(idx, key=lambda k: -len(idx[k])):
        names = " ".join(f"`{n}`" for n in sorted(idx[t]))
        L.append(f"| `.{t}` | {len(idx[t])} 个：{names} |")
    orphan = [b["class"] for b in LIB["blocks"]
              if b["class"].startswith(".txt-") and b["class"][1:] not in idx]
    if orphan:
        L.append(f"\n没有任何**件**用到的档：{' '.join(f'`{o}`' for o in orphan)} ——")
        L.append("它们只在页面里用。字阶是一个闭合的集合，不是使用清单，")
        L.append("所以「件里没人用」不是欠账（见《规范·字体》）。\n")
    L.append("\n⚠️ **「自写」那一栏本该是空的** —— 字号该由 `.txt-*` 带出来。")
    L.append("眼下只有 `sh-uploader` 有一处（48rpx，`sh-cover` 拿到 emoji 时按文字排的兜底字号，")
    L.append("且在字阶上）—— 看过是对的。**清单的作用就是让这种东西露出来被看一眼**，")
    L.append("而不是等它变成第二处、第三处。\n")
    L.append(f"\n## 积木（{c['blocks']}）\n")
    by = collections.defaultdict(list)
    for b in LIB["blocks"]:
        by[b["group"]].append(b)
    for g in sorted(by, key=lambda g: -len(by[g])):
        L.append(f"\n### {g}（{len(by[g])}）\n")
        L.append("| 类 | 声明 | 调用点 B/C |")
        L.append("|---|---|---:|")
        for b in sorted(by[g], key=lambda x: -(x["usage"].get("b-app", 0) + x["usage"].get("c-app", 0))):
            decl = "; ".join(f"{k}: {v}" for k, v in list(b["decl"].items())[:3])
            L.append(f"| `{b['class']}` | `{decl[:60]}` | {usage(b['usage'])} |")
    cs = criteria()
    L.append(f"\n## 「算不算自己画」的判据（{len(cs)} 条）\n")
    L.append("这些不是建议，是 `pre-push` 上的闸门（`check-handrolled-ui.mjs`）。")
    L.append("**每一条都判声明不判名字** —— 按名字归类在这个仓库里误命中过十三次。\n")
    L.append("| id | 报什么 |")
    L.append("|---|---|")
    for cid, label in cs:
        L.append(f"| `{cid}` | {label} |")
    gaps = LIB.get("gaps", [])
    L.append(f"\n## 形态缺口\n\n{'**当前 0 类** —— 页面里出现的形状，库里都有对应的件。' if not gaps else ''}")
    for g in gaps:
        L.append(f"- **{g['label']}**：{len(g.get('pages', []))} 页各造一份")
    L += gate_section("组件")
    return "\n".join(L) + "\n"


for path, body in [
    ("docs/technical/design/规范-字体.md", typography()),
    ("docs/technical/design/规范-版面.md", layout()),
    ("docs/technical/design/规范-组件.md", components()),
    ("docs/technical/design/规范-页面.md", pages()),
]:
    # 状态行按文档规范 §四：必须紧跟标题
    lines = body.split("\n")
    body = lines[0] + "\n\n" + STATUS[pathlib.Path(path).name] + "\n".join(lines[1:])
    (ROOT / path).write_text(body, encoding="utf-8")
    print(f"✅ {path}  {len(body.splitlines())} 行")
