#!/usr/bin/env python3
"""进销存九屏的界面清单：从 .vue 里按模板顺序抽标题 / 页签 / 字段 / 按钮，
再回 zh-CN.ts 取中文。

**为什么要生成不要手写**：手写的清单第二天就与代码不一致，而它长得依然像真的。
这份随时重跑就能对差 —— 判据是「跑一遍，输出没变」。

    python3 scripts/gen-inv-ui-inventory.py            # 打印
    python3 scripts/gen-inv-ui-inventory.py --md       # 生成文档那几张表

分类靠**最近的那个开标签**，不靠往前扫一大段：扫 260 字符会把上一行的
sh-empty 算到这一行头上（第一版就把「点一行看那张单」判成了空态）。"""
import json, re, sys, io

PAGES = ["stock","stock-detail","stock-docs","purchase-edit","stock-check",
         "stock-out","transfer","stock-report","locations"]

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

LOC = load_locale("b-app/src/i18n/locale/zh-CN.ts")

def template(src):
    i = src.find("<template>"); j = src.rfind("</template>")
    return src[i:j] if i >= 0 else ""

def rows(page):
    src = open(f"b-app/src/pages/{page}/index.vue", encoding="utf-8").read()
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

TITLES = {"stock":"库存","stock-detail":"库存明细","stock-docs":"单据",
          "purchase-edit":"进货","stock-check":"盘点","stock-out":"报损",
          "transfer":"调拨","stock-report":"报表","locations":"库位"}

if __name__ == "__main__":
    md = "--md" in sys.argv
    for p in PAGES:
        if md:
            print(f"\n### {TITLES[p]} · `{p}`\n")
            print("| 类别 | 词条 | 中文 |")
            print("|---|---|---|")
            for kind, key, zh in rows(p):
                z = (zh or "").replace("|", "\\|")
                print(f"| {kind} | `{key}` | {z} |")
        else:
            print(f"\n=== {p} ===")
            for kind, key, zh in rows(p):
                print(f"  {kind:10} {key:34} {zh or ''}")
