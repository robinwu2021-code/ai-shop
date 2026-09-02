#!/usr/bin/env python3
"""进销存十屏的界面清单：从 .vue 里按模板顺序抽标题 / 页签 / 字段 / 按钮，
再回 zh-CN.ts 取中文。

**为什么要生成不要手写**：手写的清单第二天就与代码不一致，而它长得依然像真的。
这份随时重跑就能对差 —— 判据是「跑一遍，输出没变」。

**但「随时能重跑」不等于有人跑。** 2026-08-28 到 09-02 之间没人跑过，
于是清单一直在说旧话：漏了整整一屏（供应商），外加「货品→商品」、
调拨发货三项、进货的供应商选择器。**没有闸门的生成器等于手写的**，
只是多了一份「它是生成的所以准」的错觉。`--check` 就是补这一道。

    python3 scripts/gen-inv-ui-inventory.py            # 打印
    python3 scripts/gen-inv-ui-inventory.py --md       # 生成文档那几张表
    python3 scripts/gen-inv-ui-inventory.py --check    # 只校验（pre-push 会跑）

分类靠**最近的那个开标签**，不靠往前扫一大段：扫 260 字符会把上一行的
sh-empty 算到这一行头上（第一版就把「点一行看那张单」判成了空态）。"""
import json, re, sys, io, pathlib

# 路径一律从仓库根算起，**不吃当前工作目录**。
# pre-push 的闸门是这么跑的：`python3 $GATE_WT/scripts/xxx.py`，
# 进程的 cwd 却是各人的工作区 —— cwd 相对路径会让闸门读工作区、判 HEAD 的脚本，
# 于是「判的是推出去的那份」这条规矩当场失效，而且不报错。
ROOT = pathlib.Path(__file__).resolve().parents[1]

# **加页面时这里也要加**：这份列表是写死的，漏一页的症状是清单少一节而
# 「跑一遍输出没变」照样成立 —— 闸门绿着，清单却不完整。suppliers 就这么漏过一轮。
PAGES = ["stock","stock-cross","stock-detail","stock-docs","purchase-edit","stock-check",
         "stock-out","transfer","stock-report","suppliers","locations"]

# 进销存页面的路径前缀。**只用来发现漏登记的新页**（见 check_scope）——
# 不用它直接生成 PAGES：清单的顺序是有意排的（枢纽在前、配置在后），
# 而按前缀扫出来的顺序是文件系统的。
INV_PREFIXES = ("stock", "purchase-edit", "transfer", "suppliers", "locations")

# ── 词条表：把 zh-CN.ts 当近似 JSON 读 ──
def load_locale(path):
    src = open(path, encoding="utf-8").read()
    src = re.sub(r'//[^\n]*', '', src)                      # 行注释
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)         # 块注释
    out, stack, key = {}, [out := {}], None
    # 用一个极简状态机：只要 key: "value" 与嵌套花括号
    path_stack = []
    cur = out
    stk = [out]
    for m in re.finditer(r'([A-Za-z_$][\w$]*)\s*:\s*(\{|"((?:[^"\\]|\\.)*)")|(\})', src):
        name, opener, val, closer = m.group(1), m.group(2), m.group(3), m.group(4)
        if closer:
            if len(stk) > 1: stk.pop()
            continue
        if opener == "{":
            d = {}
            stk[-1][name] = d
            stk.append(d)
        else:
            # **不要 unicode_escape** —— 文件本来就是 UTF-8，那个解码会把中文拆成乱码。
            # 只还原 JS 字符串里真正的转义。
            stk[-1][name] = (val.replace('\\"', '"').replace("\\\\", "\\")
                                .replace("\\n", "\n"))
    return out

def get(d, dotted):
    cur = d
    for p in dotted.split("."):
        if not isinstance(cur, dict) or p not in cur: return None
        cur = cur[p]
    return cur if isinstance(cur, str) else None

LOC = load_locale(ROOT / "b-app/src/i18n/locale/zh-CN.ts")

def template(src):
    i = src.find("<template>"); j = src.rfind("</template>")
    return src[i:j] if i >= 0 else ""

def rows(page):
    src = open(ROOT / f"b-app/src/pages/{page}/index.vue", encoding="utf-8").read()
    tpl = template(src)
    out = []
    tk = re.search(r'title-key="([^"]+)"', src)
    out.append(("标题", tk.group(1) if tk else "—", get(LOC, tk.group(1)) if tk else "—"))

    # 页签 / 筛选：TABS 或 FILTERS 常量里的 t("…")
    for const in re.finditer(r'const (TABS|FILTERS|REASONS|ENTRIES|entries)\s*=(.*?)\n(?:const |function |onShow|onLoad)', src, re.S):
        for k in re.findall(r't\("([^"]+)"\)', const.group(2)):
            out.append(("页签/筛选", k, get(LOC, k)))
        for k in re.findall(r'"(\w+)"\s*,?\s*\]?\s*as const', const.group(2)):
            pass

    # 模板里按出现顺序取所有 $t / t(
    seen = set()
    for m in re.finditer(r'\$?t\(\s*[`"\']([^`"\']+)[`"\']', tpl):
        k = m.group(1)
        if "${" in k or k in seen: continue
        seen.add(k)
        # 判类型：这一行是不是输入 / 按钮
        # 只看**最近的那个开标签**，不看前面一大段 —— 看 260 字符会把
        # 上一行的 sh-empty 算到这一行头上（第一版就把「点一行看那张单」判成了空态）
        lt = tpl.rfind("<", 0, m.start())
        gt = tpl.find(">", lt)
        tag = tpl[lt:gt + 1] if lt >= 0 and gt > lt else ""
        before = tpl[max(0, m.start() - 40):m.start()]
        if "placeholder" in before:
            kind = "输入框占位"
        elif "sh-btn" in tag or "sh-add" in tag:
            kind = "按钮"
        elif "field__label" in tag:
            kind = "字段名"
        elif "sh-empty" in tag:
            kind = "空态"
        elif "sh-hint" in tag:
            kind = "说明"
        elif "sh-link" in tag or "sh-go" in tag:
            kind = "链接"
        elif "sh-section" in tag or "txt-title" in tag:
            kind = "分区标题"
        elif "sh-chip" in tag:
            kind = "选项"
        elif "sh-kv" in tag or "label=" in tag:
            kind = "字段名"
        else:
            kind = "文案"
        out.append((kind, k, get(LOC, k)))

    for m in re.finditer(r'<input\b[^>]*>', tpl, re.S):
        tag = " ".join(m.group(0).split())
        ml = re.search(r'maxlength="?(\d+)', tag)
        ty = re.search(r'type="(\w+)"', tag)
        out.append(("输入框", f'type={ty.group(1) if ty else "text"} maxlength={ml.group(1) if ml else "—"}', ""))
    return out

TITLES = {"stock":"库存","stock-cross":"跨店库存","stock-detail":"库存明细","stock-docs":"单据",
          "purchase-edit":"进货","stock-check":"盘点","stock-out":"报损",
          "transfer":"调拨","stock-report":"报表","suppliers":"供应商",
          "locations":"库位"}

DOC = ROOT / "docs/technical/design/进销存-界面清单.md"


def check_scope():
    """**PAGES 有没有漏掉新页。**

    这个列表是手写的，而新页是随时加的 —— 漏登记的症状是清单少一节，
    而 `--check` 说「最新的」：闸门扫不到的东西，它当然不会报。
    2026-09-02 就漏过一次（新增的 stock-cross），上一次是 suppliers。
    """
    import json
    pages = json.load(open(ROOT / "b-app/src/pages.json", encoding="utf-8"))["pages"]
    found = set()
    for e in pages:
        path = e.get("path", "")
        if not path.startswith("pages/"):
            continue
        name = path[len("pages/"):].rsplit("/", 1)[0]
        if name.startswith(INV_PREFIXES):
            found.add(name)
    missing = sorted(found - set(PAGES))
    stale = sorted(set(PAGES) - found)
    return missing, stale


def section(p):
    """一屏的 markdown 小节。--md 与 --check 共用它 —— 两边各拼一次的话，
    改了格式就会变成「生成器改了、闸门还按老格式比」，而那种红看不出真因。"""
    out = [f"### {TITLES[p]} · `{p}`", "", "| 类别 | 词条 | 中文 |", "|---|---|---|"]
    for kind, key, zh in rows(p):
        z = (zh or "").replace("|", "\\|")
        out.append(f"| {kind} | `{key}` | {z} |")
    return "\n".join(out)


def check():
    """文档里那几张表必须与现在生成出来的一字不差。

    **逐屏比，不整段比**：整段比只能说「不一样」，而读的人要知道是哪一屏动了。
    另外先断言文档确实含有本域的小节 —— 文档被改名或小节被删光时，
    「一处都没比到」不该判成绿。"""
    try:
        doc = open(DOC, encoding="utf-8").read()
    except FileNotFoundError:
        print(f"✗ 找不到 {DOC} —— 清单被移动或改名了？闸门不能因此放行")
        return 1

    # **先查扫描面**：PAGES 漏了一页时，下面逐屏比的结果照样是「全对」
    missing, gone = check_scope()
    if missing:
        print(f"✗ 这些进销存页面不在 PAGES 里，清单根本没扫到它们：{'、'.join(missing)}")
        print("  加进 scripts/gen-inv-ui-inventory.py 的 PAGES 与 TITLES，再重新生成")
        return 1
    if gone:
        print(f"✗ PAGES 里这些页面已经不存在了：{'、'.join(gone)}")
        return 1

    stale = [p for p in PAGES if section(p) not in doc]
    if not stale:
        print(f"✓ 进销存界面清单是最新的（{len(PAGES)} 屏）")
        return 0

    print(f"✗ 界面清单与代码对不上：{len(stale)} / {len(PAGES)} 屏")
    for p in stale:
        head = f"### {TITLES[p]} · `{p}`"
        print(f"  · {TITLES[p]}（{p}）{'—— 文档里根本没有这一屏' if head not in doc else ''}")
    print("  重新生成：python3 scripts/gen-inv-ui-inventory.py --md，"
          "把输出替换掉《界面清单》的「逐项」那一节")
    return 1


if __name__ == "__main__":
    if "--check" in sys.argv:
        sys.exit(check())
    md = "--md" in sys.argv
    for p in PAGES:
        if md:
            print()
            print(section(p))
        else:
            print(f"\n=== {p} ===")
            for kind, key, zh in rows(p):
                print(f"  {kind:10} {key:34} {zh or ''}")
