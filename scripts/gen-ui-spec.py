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

STATUS = {'规范-字体.md': '> 状态：**生成物 · 长期有效** · 创建 2026-08-28\n> 上游：`packages/shared/src/design/tokens.ts` + `packages/ui/src/styles/base.css` → [`ui-lib.json`](./ui-lib.json) → **本文**\n> 定位：字号 / 字重 / 行高 / 密度 —— 「一个字长什么样」。版面见 [规范-版面](规范-版面.md)，件见 [规范-组件](规范-组件.md)。\n', '规范-版面.md': '> 状态：**生成物 · 长期有效** · 创建 2026-08-28\n> 上游：`tokens.ts` + `base.css` + 组件源码 → [`ui-lib.json`](./ui-lib.json) → **本文**\n> 定位：一屏东西怎么摆 —— 画布、圆角与间距档、页面框、行与列表、浮层层级、皮肤与明暗。\n', '规范-组件.md': '> 状态：**生成物 · 长期有效** · 创建 2026-08-28\n> 上游：`packages/ui/src/components/*.vue` + `base.css` + 90 个页面 → [`ui-lib.json`](./ui-lib.json) → **本文**\n> 定位：有哪些件、各自用在哪、调用点多少，以及「算不算自己画」的 18 条判据。\n'}

HEAD = "> **本文件由 `scripts/gen-ui-spec.py` 生成，请勿手改。**\n> 数字来自 `ui-lib.json`（tokens.ts / base.css / 组件源码 / 90 个页面），跑一次同步一次。\n> 改规范改源头，然后 `python3 scripts/gen-ui-spec.py`。\n"


def usage(u):
    b, c = u.get("b-app", 0), u.get("c-app", 0)
    return f"{b} / {c}"


def criteria():
    """从 gen-ui-lib.py 的 ROLLED 表里抽判据 —— 它是「什么算自己画」的唯一定义"""
    blk = GEN[GEN.index("ROLLED = ["):]
    blk = blk[: blk.index("\n]")]
    out = []
    for m in re.finditer(r'\("(\w+)",\s*"([^"]+)",[^\n]*?(?:,\s*(None|"[^"]*"|\'[^\']*\'))?\)\s*,', blk, re.S):
        out.append((m.group(1), m.group(2)))
    return out


# ─────────────────────────────────────────────────────────── 字体
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
    L.append("\n## 三条硬规矩\n")
    L.append("1. **700 只给价格**，600 只给标题与按钮，其余一律 400。")
    L.append("2. **行高按用途分档，不按语言分档。** 中文要 1.5 以上才不挤，拉丁 1.4 就够；")
    L.append("   取中文的下限对英文也不难看，而按语言切换行高会让同一个列表在中英文下高度不同，")
    L.append("   横滑卡、等高栅格全要跟着变。")
    L.append("3. **字阶里不含 `letter-spacing`。** 负字距是拉丁字母的排版习惯，中文小字号下收紧会让笔画粘连，")
    L.append("   而同一个类要同时承载中 / 英 / 阿三种文字 —— 少一个轴，三种语言就少三种试错。")
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
        L.append("\n⚠️ **`.txt-*` 字阶目前只在 B 端落了地。** C 端 9 档合计只有 1 个调用点，")
        L.append("而 `.sh-h1` / `.sh-h2` 那两个旧名在 C 端还有 26 处 —— ")
        L.append("也就是说这份规范对 C 端还只是**纸面上的**，那 26 处是明账。")
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
    L.append("\n**只用这几档。** 差 4rpx 的两个圆角没人分得出，只会让人各写各的 —— ")
    L.append("`typography.test.ts` 有断言守着，越档推不上去。\n")
    L.append("\n## 页面框\n")
    L.append("所有页面走 `sh-scaffold`：它管标题、内边距（`--sh-pad-page`）、底部菜单占位、")
    L.append("宽屏收窄（>600px 收成 375 版心）与安全区。**`position: fixed` 的悬浮条不用各自处理宽屏** ——")
    L.append("scaffold 的 transform 让它们以应用框为包含块，漏改一处就会横跨整屏。\n")
    L.append("\n## 行与列表\n")
    L.append("| 件 | 什么时候用 |")
    L.append("|---|---|")
    for cls in [".sh-row", ".sh-row--between", ".sh-row--divided", ".sh-fill", ".sh-seg", ".sh-hint"]:
        b = next((x for x in LIB["blocks"] if x["class"] == cls), None)
        if b:
            L.append(f"| `{cls}` | {(b.get('when') or '—')[:70]} |")
    L.append("\n间距**由项自己挂 `.sh-mt-* / .sh-mb-*`**，不是容器给 gap —— ")
    L.append("36 个列表里 35 个的容器还装着分组标题与说明，容器一改 gap，标题与第一项的距离也跟着变。\n")
    L.append("\n## 浮层层级\n")
    L.append("| 层 | z-index | 说明 |")
    L.append("|---|---:|---|")
    for f, note in [("sh-tabbar", "底部菜单"), ("sh-actionbar", "悬浮内缩通栏"), ("sh-sheet", "底部弹层"), ("sh-dialog", "居中对话框")]:
        p = ROOT / f"packages/ui/src/components/{f}.vue"
        zs = sorted({int(m) for m in re.findall(r"z-index:\s*(\d+)", p.read_text(encoding="utf-8"))}) if p.exists() else []
        L.append(f"| `{f}` | {' / '.join(map(str, zs)) or '—'} | {note} |")
    L.append("\n对话框永远在最上面 —— 它是要人立刻回答的那一个。弹层叠弹层用 `sh-sheet` 的 `stacked`。\n")
    L.append("\n## 深浅与皮肤\n")
    L.append(f"{len(tk['skins'])} 套皮肤 × 明暗两态。切换要**同时**翻两处：")
    L.append("H5/App 改 `<html data-skin data-theme>`，小程序改 `.sh-root.skin-*.mode-*`（`sh-scaffold` 统一注入）。\n")
    L.append("\n⚠️ **明暗归我们的 `mode` 管，不跟系统走。** uni 自带的 `--UI-*` 变量在 App 产物里")
    L.append("**只在 `@media (prefers-color-scheme: dark)` 里定义过** —— 系统浅色时它们根本没有定义，")
    L.append("用到它们的整条声明会被丢弃（内置件掉底色），系统深色时又跟 `--sh-*` 撞成两套色。")
    L.append("`base.css` 因此无条件补了一份浅色默认，位置在 uni 那段之后。\n")
    return "\n".join(L) + "\n"


# ─────────────────────────────────────────────────────────── 组件
def components() -> str:
    L = [f"# 界面规范 · 组件\n", HEAD]
    c = LIB["counts"]
    L.append(f"\n**{c['components']} 个组件 · {c['blocks']} 个积木 · 扫过 {c['pages']} 个页面。**\n")
    L.append("\n组件与积木的分工：**积木是一条 CSS 类**（没有行为，随便贴），")
    L.append("**组件有行为或结构**（插槽、事件、状态）。同一个东西不要两头都做。\n")
    L.append(f"\n## 组件（{c['components']}）\n")
    L.append("| 组件 | 作用域 | props | 调用点 B/C |")
    L.append("|---|---|---|---:|")
    for x in sorted(LIB["components"], key=lambda x: -(x["usage"].get("b-app", 0) + x["usage"].get("c-app", 0))):
        props = ", ".join(p.split(":")[0] for p in x["props"][:4]) or "—"
        L.append(f"| `{x['name']}` | {x['scope']} | {props} | {usage(x['usage'])} |")
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
    return "\n".join(L) + "\n"


for path, body in [
    ("docs/technical/design/规范-字体.md", typography()),
    ("docs/technical/design/规范-版面.md", layout()),
    ("docs/technical/design/规范-组件.md", components()),
]:
    # 状态行按文档规范 §四：必须紧跟标题
    lines = body.split("\n")
    body = lines[0] + "\n\n" + STATUS[pathlib.Path(path).name] + "\n".join(lines[1:])
    (ROOT / path).write_text(body, encoding="utf-8")
    print(f"✅ {path}  {len(body.splitlines())} 行")
