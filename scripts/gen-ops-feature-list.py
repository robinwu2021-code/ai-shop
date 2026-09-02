#!/usr/bin/env python3
"""运营端功能清单：主菜单 → 子菜单 → 权限码 → 可见角色。

**三个真源，一个都不能少**：

  · `ops-web/lib/nav.ts`            菜单树与每个叶子要什么权限码
  · `backend/…/auth/Perms.java`     角色 → 权限码（ROLE_PERMS）
  · `ops-web/lib/permissions.ts`    角色的中文名

三者任何一处改了，这份清单就该重跑。**它与
`运营端-角色×端点矩阵.md` 不重复**：那一份是 371 个后端端点 × 角色，
给权限码改造用；这一份是**界面维度** —— 运营打开侧边栏看得到什么。
两者的差别在一次真实的事故里体现过：端点可达不等于页面可见，
菜单少一行的后果是「有权限但找不到入口」，而端点矩阵对此一无所知。

用法：
    python3 scripts/gen-ops-feature-list.py            # 重新生成
    python3 scripts/gen-ops-feature-list.py --check    # 只校验（pre-push 用）
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
NAV = ROOT / "ops-web/lib/nav.ts"
PERMS = ROOT / "backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/Perms.java"
ROLE_LABELS = ROOT / "ops-web/lib/permissions.ts"
DOC = ROOT / "docs/technical/reference/运营端-功能清单.md"


def perm_constants(src):
    """常量名 → 权限码字符串。"""
    return dict(re.findall(r'public static final String (\w+)\s*=\s*"([^"]+)"', src))


def role_perms(src, consts):
    """角色 → 它持有的权限码集合。`*` 原样保留（通配）。"""
    i = src.index("ROLE_PERMS = Map.ofEntries(")
    body = src[i:]
    out = {}
    for m in re.finditer(r'Map\.entry\("(\w+)",\s*List\.of\((.*?)\)\)', body, re.S):
        role, items = m.group(1), m.group(2)
        items = re.sub(r'//[^\n]*', '', items)          # 行注释里有常量名，先去掉
        codes = set()
        if '"*"' in items:
            codes.add("*")
        for name in re.findall(r'\b([A-Z][A-Z0-9_]{3,})\b', items):
            if name in consts:
                codes.add(consts[name])
        out[role] = codes
    return out


# 后端角色码 → 中文名。
#
# **前后端的角色码有三个对不上**：前端 `ops-web/lib/permissions.ts` 用
# MERCHANT_BD / PRODUCT_OPS / CS，后端 `Perms.ROLE_PERMS` 用 BD / GOODS_OPS /
# SUPPORT。而前端那张表的注释写着「逐字镜像后端」—— 名字都不一样，
# 这句话早就不成立了。
#
# 线上不受影响：真登录时判权用的是后端下发的 `staff.perms`，不查这两张表。
# 受影响的是**开发期**：mock 登录与导航守卫断言用前端那份，
# 于是「开发机上看到的角色」与线上不是同一批。
#
# 这里以**后端为准**（它是判权真源），中文名从前端那份按别名取过来。
ROLE_CN = {
    "SUPER_ADMIN": "超级管理员",
    "BD": "商家运营",            # 前端叫 MERCHANT_BD
    "GOODS_OPS": "商品运营",     # 前端叫 PRODUCT_OPS
    "SUPPORT": "客服",           # 前端叫 CS
    "CAMPAIGN_OPS": "活动运营",
    "COMMUNITY_OPS": "社区运营",
    "AUDITOR": "审核员",
    "FINANCE": "财务",
    "RISK": "风控",
    "ANALYST": "数据分析",
    "TECH_OPS": "技术运维",
}


def nav_tree(src):
    """[(模块名, [叶子…])]，叶子是 dict。**保持 nav.ts 的原始顺序** ——
    侧边栏就是按这个顺序排的，重排会让清单与看到的对不上。"""
    mods = []
    # 先切出每个顶级模块的整块（从 key: 到下一个 key: 之前），再在块内找 children。
    # **不要求 children 紧跟 key**：`community` 那个模块中间隔着三行注释，
    # 而 `dashboard` 压根没有 children（它是单页）—— 两种都漏掉过。
    blocks = list(re.finditer(r'key:\s*"(\w+)",\s*label:\s*"([^"]+)"', src))
    for i, m in enumerate(blocks):
        _key, label = m.group(1), m.group(2)
        end = blocks[i + 1].start() if i + 1 < len(blocks) else len(src)
        block = src[m.start():end]
        ch = re.search(r'children:\s*\[(.*)', block, re.S)
        body = ch.group(1) if ch else ""
        leaves = []
        for lf in re.finditer(r'\{\s*href:\s*"([^"]+)"\s*,\s*label:\s*"([^"]+)"([^}]*)\}', body):
            href, name, rest = lf.group(1), lf.group(2), lf.group(3)
            perm = (re.search(r'perm:\s*"([^"]*)"', rest) or [None, ""])[1]
            group = (re.search(r'group:\s*"([^"]*)"', rest) or [None, ""])[1]
            matrix = (re.search(r'matrix:\s*"([^"]*)"', rest) or [None, ""])[1]
            ready = "ready: false" not in rest
            leaves.append({"href": href, "label": name, "perm": perm,
                           "group": group, "matrix": matrix, "ready": ready})
        if not leaves:
            # 单页模块（经营看板）：它自己就是那一个功能，
            # 漏掉它的后果是「数据分析」这个角色显示成 0 个可见页面 —— 而看板正是它的主场
            head = block[:ch.start()] if ch else block
            perm = (re.search(r'perm:\s*"([^"]*)"', head) or [None, ""])[1]
            href = (re.search(r'href:\s*"([^"]*)"', head) or [None, ""])[1]
            matrix = (re.search(r'matrix:\s*"([^"]*)"', head) or [None, ""])[1]
            leaves = [{"href": href, "label": label, "perm": perm,
                       "group": "", "matrix": matrix, "ready": True}]
        mods.append((label, leaves))
    return mods


def visible_roles(perm, rp):
    """能看到这个叶子的角色（不含通配角色 —— 它看得到全部，逐行重复没有信息量）。"""
    if not perm:
        return ["（无需授权）"]
    return sorted(r for r, codes in rp.items()
                  if "*" not in codes and perm in codes)


def build():
    consts = perm_constants(PERMS.read_text(encoding="utf-8"))
    rp = role_perms(PERMS.read_text(encoding="utf-8"), consts)
    mods = nav_tree(NAV.read_text(encoding="utf-8"))

    wildcard = sorted(r for r, c in rp.items() if "*" in c)
    total = sum(len(v) for _, v in mods)

    def cn(role):
        return ROLE_CN.get(role, role)

    out = []
    out.append("# 运营端 · 功能清单（主菜单 → 子菜单 → 角色）")
    out.append("")
    out.append("> 状态：**生成物**，随代码走。")
    out.append("> **勿手改** —— 由 `python3 scripts/gen-ops-feature-list.py` 生成。")
    out.append("> 真源：`ops-web/lib/nav.ts`（菜单与权限码）× "
               "`Perms.ROLE_PERMS`（角色→权限码，后端是判权权威）。")
    out.append("")
    out.append("**与[角色×端点矩阵](./运营端-角色×端点矩阵.md)不重复**："
               "那一份是后端端点维度，给权限码改造用；"
               "这一份是**界面维度** —— 运营打开侧边栏看得到什么。"
               "端点可达不等于页面可见：菜单少一行的后果是「有权限但找不到入口」，"
               "而端点矩阵对此一无所知。")
    out.append("")
    out.append(f"共 **{len(mods)}** 个主菜单、**{total}** 个子菜单。")
    out.append("")
    out.append("> ⚠️ **前后端的角色码有三个对不上**：前端 `ops-web/lib/permissions.ts` 用 "
               "`MERCHANT_BD` / `PRODUCT_OPS` / `CS`，后端 `Perms.ROLE_PERMS` 用 "
               "`BD` / `GOODS_OPS` / `SUPPORT`，而前端那张表的注释写着「逐字镜像后端」。"
               "线上不受影响（真登录判权用后端下发的 `staff.perms`），"
               "受影响的是**开发期**：mock 登录与导航守卫断言用的是前端那份，"
               "于是开发机上看到的角色与线上不是同一批。本表以**后端为准**。")
    out.append(f"通配角色（看得到全部）：{'、'.join(cn(r) for r in wildcard)} —— "
               "下表的「可见角色」一列**不重复列它**。")
    out.append("")

    # ── 汇总：每个角色能看到几个页面 ──
    out.append("## 一、每个角色看得到多少页面")
    out.append("")
    out.append("| 角色 | 可见子菜单 | 占比 |")
    out.append("|---|--:|--:|")
    for role in sorted(rp):
        if "*" in rp[role]:
            out.append(f"| `{role}` {cn(role)} | {total} | 100% |")
            continue
        n = sum(1 for _, leaves in mods for lf in leaves
                if lf["perm"] and lf["perm"] in rp[role] or not lf["perm"])
        out.append(f"| `{role}` {cn(role)} | {n} | {round(n * 100 / total)}% |")
    out.append("")
    out.append("> 每个角色都至少看得到无需授权的那几页。**占比低不等于权限不足** —— "
               "角色是按岗位切的，商品运营看不到结算是设计，不是缺口。")
    out.append("")

    # ── 只有超管看得到的 ──
    only_admin = [(mod, lf) for mod, leaves in mods for lf in leaves
                  if lf["perm"] and not visible_roles(lf["perm"], rp)]
    if only_admin:
        out.append("## 二、只有超管看得到的页面")
        out.append("")
        out.append(f"有 **{len(only_admin)}** 个子菜单，它的权限码**没有分配给任何岗位角色**。")
        out.append("")
        out.append("其中一部分是有意的（改钱、改环境的高危动作本来就该只给超管），"
                   "但另一部分更像是**漏分配** —— 判据是问一句"
                   "「这件事日常该谁做」：如果答案是某个岗位而不是超管，那就是漏了。"
                   "漏分配的症状很温和：那个岗位的人登录后<b>看不到这个菜单</b>，"
                   "而他不会知道自己少了什么。")
        out.append("")
        out.append("| 主菜单 | 子菜单 | 权限码 |")
        out.append("|---|---|---|")
        for mod, lf in only_admin:
            out.append(f"| {mod} | {lf['label']} | `{lf['perm']}` |")
        out.append("")

    # ── 逐模块清单 ──
    out.append("## 三、逐主菜单")
    out.append("")
    for label, leaves in mods:
        out.append(f"### {label}")
        out.append("")
        out.append("| 分组 | 子菜单 | 权限码 | 可见角色 | 矩阵 | 状态 |")
        out.append("|---|---|---|---|---|---|")
        for lf in leaves:
            roles = visible_roles(lf["perm"], rp)
            rtxt = "、".join(cn(r) for r in roles) if roles else "**仅超管**"
            perm = f"`{lf['perm']}`" if lf["perm"] else "—"
            out.append(f"| {lf['group'] or '—'} | {lf['label']} | {perm} | {rtxt} "
                       f"| {lf['matrix'] or '—'} | {'' if lf['ready'] else '⬜ 分期屏蔽'} |")
        out.append("")

    return "\n".join(out) + "\n"


def main():
    text = build()
    if "--check" in sys.argv:
        if not DOC.exists():
            print(f"✗ 找不到 {DOC} —— 清单被移动或改名了？闸门不能因此放行")
            return 1
        if DOC.read_text(encoding="utf-8") != text:
            print("✗ 运营端功能清单与代码对不上（菜单、权限码或角色映射改过了）")
            print("  重新生成：python3 scripts/gen-ops-feature-list.py")
            return 1
        print("✓ 运营端功能清单是最新的")
        return 0
    DOC.parent.mkdir(parents=True, exist_ok=True)
    DOC.write_text(text, encoding="utf-8")
    print(f"✓ {DOC}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
