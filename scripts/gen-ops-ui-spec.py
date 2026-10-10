#!/usr/bin/env python3
"""运营端界面规范（`docs/technical/design/规范-运营端.md`）。**全部从产物生成，不手写。**

为什么再加一份：`gen-ui-spec.py` 出的三份（字体 / 版面 / 组件）上游是
`packages/shared` + `packages/ui` + b-app/c-app 的 90 个页面 —— **那是端上的规范**。
运营端有自己的一套 token（`ops-web/app/globals.css`）、自己的组件库
（`ops-web/components/`）和自己的闸门（`ops-web/lib/design-tokens.test.ts`），
在 `docs/technical/design/` 里此前一份都没有。

于是运营端的规范散在五处：组件清单在 `components/README.md`、token 的来由写在
`globals.css` 的注释里、判据落在测试文件里、状态表在 `ops-web/README.md`、
历史决策在 `docs/technical/design/ops/TDD-ops-组件库优化.md`。
散不是问题，**对不上才是**：README 说「31 个组件」而实际是另一个数，
globals.css 说「组件层正在接入中」而没人知道接到了几成。

这份只放**能数出来的那部分**，判断与来由仍留在 `components/README.md`
（那里适合写「为什么这么定」，这里适合写「现在是什么样」）。

用法：python3 scripts/gen-ops-ui-spec.py
"""
import collections
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
OPS = ROOT / "ops-web"
OUT = ROOT / "docs/technical/design/规范-运营端.md"

CSS = (OPS / "app/globals.css").read_text(encoding="utf-8")
GUARD = (OPS / "lib/design-tokens.test.ts").read_text(encoding="utf-8")


def page_files():
    """页面层的**全部** .tsx —— 不是只有 page.tsx。

    这个区分是这份规范存在的一半理由：闸门此前只扫 page.tsx，而 63% 的页面代码
    住在 `*-tab.tsx` 里，于是「闸门全绿」与「规则被遵守」是两件事。
    """
    return sorted(p for p in (OPS / "app").rglob("*.tsx") if "/dev/" not in str(p))


def component_files():
    return sorted(p for p in (OPS / "components").rglob("*.tsx"))


def rel(p):
    return str(p.relative_to(OPS))


def strip_comments(src):
    """把注释行换成空行（保留行数，行号仍可用）。

    为什么要有它：用量列是「有没有现成件可用」的判断依据，而**注释里提到的标签
    照样会被 `<Name\b` 数进去**。2026-09-09 实测：给 health-tab 写了一句
    「不写成 EmptyState 标签 : DataTable 标签」的解释注释，规范里 `EmptyState`
    的页面用量就从 0 变成 1 —— 而 0 与 1 在这一列上的含义天差地别
    （「没人用，可以删」对「有人在用」）。

    与 `lib/design-tokens.test.ts` 的 `commentLines` 同一套判据，逐行判，
    不做块级正则替换 —— 那会被字符串里的 `/*` 带偏。
    """
    out, in_block = [], False
    for line in src.split("\n"):
        if in_block:
            out.append("")
            if "*/" in line:
                in_block = False
            continue
        if re.match(r"^\s*//", line):
            out.append("")
            continue
        start = line.find("{/*") if line.find("{/*") >= 0 else line.find("/*")
        if start >= 0:
            out.append(line[:start])
            if line.find("*/", start) < 0:
                in_block = True
            continue
        out.append(line)
    return "\n".join(out)


PAGES = {p: strip_comments(p.read_text(encoding="utf-8")) for p in page_files()}
COMPS = {p: strip_comments(p.read_text(encoding="utf-8")) for p in component_files()}


def comment_lines(src):
    out, blk = set(), False
    for i, l in enumerate(src.split("\n")):
        if blk:
            out.add(i)
            if "*/" in l:
                blk = False
            continue
        if re.match(r"^\s*//", l):
            out.add(i)
            continue
        s = l.find("{/*") if l.find("{/*") >= 0 else l.find("/*")
        if s >= 0:
            out.add(i)
            if l.find("*/", s) < 0:
                blk = True
    return out


# ── 组件清单 ────────────────────────────────────────────────────────────────
def exported_components(src):
    """`export function Xxx(` / `export const Xxx = ` 里的**组件**。

    只认 PascalCase：`export const ARCHIVE_LABEL_KEY`、`PAGE_SIZES`、`ERR_RING`
    也以大写开头，但它们是常量不是件 —— 混进清单会让「零调用点的件」那一节多出
    四个假名字，而读的人无从判断哪几个才是真的没人用。
    """
    names = re.findall(r"^export (?:function|const) ([A-Z][a-z]\w*)", src, re.M)
    return sorted(set(names))


def layer(path):
    r = rel(path)
    if r.startswith("components/ui/"):
        return "原语 / 组合件"
    if r.startswith("components/layout/"):
        return "外壳"
    return "业务件"


def usage(name):
    """页面层的调用点数：`<Name` 出现多少次（不含组件库自身）。"""
    return sum(len(re.findall(r"<%s\b" % name, s)) for s in PAGES.values())


def lib_usage(name):
    return sum(len(re.findall(r"<%s\b" % name, s)) for s in COMPS.values())


# ── 字阶 ────────────────────────────────────────────────────────────────────
# 允许缩进：七档已收进 `@layer components`（裸写会压掉调用点的 font-* / leading-*）。
# 此前正则要求类名顶行，改成缩进后**匹配到 0 条，整张七档表静默从规范里消失** ——
# 而产物前后一致，check-generated-docs 照样绿：陈旧闸门不看内容。
TIERS = re.findall(r"^\s*\.(txt-[a-z]+)\s*\{([^}]*)\}", CSS, re.M)
# 解析不出来就当场炸，不要生成一张空表。「少了一整块」比「数字不对」更难被发现：
# 读的人只会以为规范里本来就没有这一节。
if len(TIERS) < 7:
    raise SystemExit(f"字阶只解析出 {len(TIERS)} 档（应为 7）—— globals.css 的写法变了，先修这里的正则")


def tier_row(cls, body):
    size = re.search(r"font-size:\s*([^;]+);", body)
    weight = re.search(r"font-weight:\s*([^;]+);", body)
    lh = re.search(r"line-height:\s*([^;]+);", body)
    n_page = sum(len(re.findall(r"\b%s\b" % cls, s)) for s in PAGES.values())
    n_lib = sum(len(re.findall(r"\b%s\b" % cls, s)) for s in COMPS.values())
    return (cls, size.group(1).strip() if size else "—",
            weight.group(1).strip() if weight else "—",
            lh.group(1).strip() if lh else "—", n_page, n_lib)


OFF_SCALE = re.compile(r"\btext-(?:\[\d+px\]|xs|sm|base|lg|xl|2xl|3xl)(?![\w-])")


def off_scale(files):
    hits = []
    for p, src in files.items():
        cm = comment_lines(src)
        for i, l in enumerate(src.split("\n")):
            if i in cm:
                continue
            for m in OFF_SCALE.finditer(l):
                hits.append((rel(p), i + 1, m.group(0)))
    return hits


# ── 圆角 / 密度 / z 轴 ──────────────────────────────────────────────────────
def var_table(pattern):
    return re.findall(pattern, CSS, re.M)


RADII = var_table(r"^\s*--(r-[a-z]+):\s*([^;]+);\s*(?:/\*\s*(.*?)\s*\*/)?")
ZS = var_table(r"^\s*--(z-[a-z-]+):\s*([^;]+);\s*(?:/\*\s*(.*?)\s*\*/)?")


# ── 页面骨架符合度 ──────────────────────────────────────────────────────────
DOMAINS = sorted({p.parent.name for p in PAGES if p.name == "page.tsx"
                  and p.parent != OPS / "app"})

SKELETON = [
    ("TabHeader", r"<TabHeader\b"),
    ("HelpNote", r"<HelpNote\b"),
    ("Toolbar", r"<Toolbar\b"),
    ("PagedTable", r"<PagedTable\b"),
    ("DataTable", r"<DataTable\b"),
    ("Drawer", r"<Drawer\b"),
    ("ConfigCard", r"<ConfigCard\b"),
    ("ReadOnlyNotice", r"<ReadOnlyNotice\b"),
]


def domain_sources(d):
    return [s for p, s in PAGES.items() if p.parent.name == d or f"/app/{d}/" in str(p)]


# ── 闸门（从测试文件生成，不手抄）────────────────────────────────────────────
GATES = re.findall(r'^\s{2}it\("(.+?)",', GUARD, re.M)


def md():
    L = []
    w = L.append
    w("# 界面规范 · 运营端")
    w("")
    w("> 状态：**生成物 · 长期有效** · 创建 2026-09-08")
    w("> 上游：`ops-web/app/globals.css` + `ops-web/components/**` + `ops-web/app/**`"
      " + `ops-web/lib/design-tokens.test.ts` → **本文**")
    w("> 定位：运营端（PC Web）自己的 token、组件与闸门。"
      "端上（B/C）的三份见 [规范-字体](规范-字体.md) / [规范-版面](规范-版面.md) / "
      "[规范-组件](规范-组件.md) —— **两套 token 不通用**，别互相引。")
    w("")
    w("> **本文件由 `scripts/gen-ops-ui-spec.py` 生成，请勿手改。**")
    w("> 每个数字都是跑的时候数出来的。改规范改源头，然后 `python3 scripts/gen-ops-ui-spec.py`。")
    w("> 「为什么这么定」写在 [`ops-web/components/README.md`](../../../ops-web/components/README.md)，"
      "这里只答「现在是什么样」。")
    w("")

    # 一句话
    n_pages = len(PAGES)
    n_entry = len([p for p in PAGES if p.name == "page.tsx"])
    loc_entry = sum(len(s.split("\n")) for p, s in PAGES.items() if p.name == "page.tsx")
    loc_rest = sum(len(s.split("\n")) for p, s in PAGES.items() if p.name != "page.tsx")
    w("## 一句话")
    w("")
    w("**字号只走七档、圆角只走五档、颜色只走 token；列表、页头、配置卡各只有一份实现。**")
    w("")
    w(f"页面层共 {n_pages} 个 .tsx（{n_entry} 个路由入口 {loc_entry} 行，"
      f"其余 {n_pages - n_entry} 个 {loc_rest} 行）。")
    w("")
    w(f"> ⚠️ **{loc_rest * 100 // (loc_entry + loc_rest)}% 的页面代码不在 `page.tsx` 里。**"
      " 任何只扫 `page.tsx` 的检查，结论的边界就到那 "
      f"{loc_entry * 100 // (loc_entry + loc_rest)}% 为止 —— "
      "`design-tokens.test.ts` 曾经就是这样，常年全绿而射程外积着 29 个文件的违规。")
    w("")

    # 字阶
    w("## 字阶（七档）")
    w("")
    w("| 类 | 字号 | 字重 | 行高 | 页面调用点 | 库内调用点 |")
    w("|---|---:|---:|---:|---:|---:|")
    for cls, body in TIERS:
        c, sz, wt, lh, np_, nl = tier_row(cls, body)
        w(f"| `.{c}` | {sz} | {wt} | {lh} | {np_} | {nl} |")
    w("")
    page_off = off_scale(PAGES)
    comp_off = off_scale(COMPS)
    w(f"**绕开档位的写法**（`text-xs` / `text-[13px]` 这类）：页面层 **{len(page_off)}** 处、"
      f"组件层 **{len(comp_off)}** 处。")
    w("")
    if page_off:
        w("页面层的（闸门盯着，基线 0）：")
        w("")
        for r, ln, cls in page_off[:40]:
            w(f"- `{r}:{ln}` — `{cls}`")
        w("")
    else:
        w("页面层是 **0**，由 `design-tokens.test.ts`「页面层不绕开字阶写字号」一条守着。")
        w("")
    if comp_off:
        by_file = collections.Counter(r for r, _, _ in comp_off)
        w("组件层的**还没收**（已知欠账，不在闸门里）—— 这一层的字号多半是控件自身的"
          "形态（按钮、徽标、表头），收进七档要连同形态一起定，属于另一件事：")
        w("")
        w("| 文件 | 处数 |")
        w("|---|---:|")
        for f, n in sorted(by_file.items(), key=lambda x: -x[1]):
            w(f"| `{f}` | {n} |")
        w("")

    # 圆角 / z 轴
    w("## 圆角（五档）与层级")
    w("")
    w("| 变量 | 值 | 用在哪 |")
    w("|---|---:|---|")
    for name, val, note in RADII:
        w(f"| `--{name}` | {val.strip()} | {note or '—'} |")
    w("")
    w("裸 `rounded`（Tailwind 默认 4px）与 `rounded-md/lg/xl` 都是**第六档**，"
      "闸门按违规处理 —— md/lg 现在别名到 `--r-field`，看着没差别，"
      "但它让五档的边界随时可以再被推开一次。")
    w("")
    if ZS:
        w("| 层级变量 | 值 | 说明 |")
        w("|---|---:|---|")
        for name, val, note in ZS:
            w(f"| `--{name}` | {val.strip()} | {note or '—'} |")
        w("")

    # 组件清单
    w("## 组件清单与调用点")
    w("")
    w("三层的判据是**依赖方向**（下层不许知道上层）；详见 "
      "[`components/README.md`](../../../ops-web/components/README.md)。")
    w("")
    w("| 组件 | 层 | 文件 | 页面调用点 | 库内 |")
    w("|---|---|---|---:|---:|")
    rows = []
    for p, src in COMPS.items():
        for name in exported_components(src):
            rows.append((name, layer(p), rel(p), usage(name), lib_usage(name)))
    for name, lay, f, n, nl in sorted(rows, key=lambda r: (-r[3], r[0])):
        w(f"| `{name}` | {lay} | `{f}` | {n} | {nl} |")
    w("")
    zero = [r for r in rows if r[3] == 0 and r[4] == 0]
    if zero:
        w(f"**页面与库内都没有调用点的 {len(zero)} 个**："
          + "、".join(f"`{r[0]}`" for r in zero))
        w("")
        w("> 零调用点不等于该删 —— `components/README.md` 有一节写明哪些是"
          "「对应矩阵里已排期的需求」而刻意留着的。但它**必须能指到一条需求**，"
          "指不到就是死代码。")
        w("")

    # 页面骨架
    w("## 页面骨架符合度")
    w("")
    w("按业务域统计各件的调用点数（含该域下全部 `*-tab.tsx`）。"
      "空白不一定是缺陷 —— 配置型页面没有列表、没有分页是正常的；"
      "**要看的是同一列里的异类**。")
    w("")
    w("| 业务域 | " + " | ".join(n for n, _ in SKELETON) + " |")
    w("|---|" + "---:|" * len(SKELETON))
    for d in DOMAINS:
        srcs = domain_sources(d)
        cells = []
        for _, pat in SKELETON:
            n = sum(len(re.findall(pat, s)) for s in srcs)
            cells.append(str(n) if n else "")
        w(f"| `{d}` | " + " | ".join(cells) + " |")
    w("")
    w("`PagedTable` 与 `DataTable` 的分工：**分页列表一律走 `PagedTable`**"
      "（rows/loading/error/onRetry/total 由 `query` 接出，`onSize` 必填），"
      "不分页的配置表继续用 `DataTable`。"
      "多 tab 共用一个分页器的页面（`total` 绑 `activeList`）不适用 `PagedTable`，"
      "因为表在 tab 条件里、分页器在外面。")
    w("")

    # 闸门
    w("## 闸门（`ops-web/lib/design-tokens.test.ts`，共 %d 条）" % len(GATES))
    w("")
    w("这一节**从测试文件生成**：写在文档里的原则会陈，跑得起来的断言不会。"
      "整套 `ops-web` 的 vitest 挂在 `pre-push` 的 `check-shared-guards` 上，"
      "基线为空 —— 任何一条红都拦推送。")
    w("")
    for g in GATES:
        w(f"- {g}")
    w("")
    w("> 闸门的扫描面就是它结论的边界。这一组现在扫 `ops-web/app/` 下**全部** .tsx"
      "（`/dev/` 除外），不是只扫 `page.tsx`。")
    w("")

    return "\n".join(L) + "\n"


if __name__ == "__main__":
    OUT.write_text(md(), encoding="utf-8")
    print(f"✓ {OUT.relative_to(ROOT)}")
