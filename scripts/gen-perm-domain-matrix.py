#!/usr/bin/env python3
"""三端权限矩阵 · 按需求的业务域归位。**生成的，不要手改。**

四份按端切开的矩阵已经有了（运营端角色×端点、B 端功能点×码×页面、
B 端按角色、C 端登录态×页面）。它们各自都对，但**没有一份能横着读**：
「售后这个域，三端各由谁把着」在今天要翻三份文档，且三份的分类法互不相同。

这份把三端的判权按 [需求矩阵](../../requirements/需求矩阵-三端.md) 的 L2 业务域
摆在一起，为的是能问出两类此前问不出的问题：

  ① **某个域在某一端一个码都没有** —— 而需求把那一格标了 P0
  ② **某个域借着别的域的码在跑** —— 授权界面上看不出来，
     授出去的比看起来的多（进销存今天就是这样，见产物 §4）

用法：
    python3 scripts/gen-perm-domain-matrix.py            # 重新生成
    python3 scripts/gen-perm-domain-matrix.py --check    # 只校验

### 口径（这份产物最容易被误读的地方）

- **「码 → 端点」是代码权威的**：扫 `@perm.can` / `@perm.canBiz` 的实参。
- **「角色 → 码」在运营端不是**：`Perms.ROLE_PERMS` 自己的注释写着它是
  **回落表**，判权主路径是 `RolePermResolver` 读库
  （`sys_role_point → sys_function_point.perm_code`）。所以本文的角色列
  回答的是「代码里默认给谁」，不是「线上库里配给了谁」。B 端没有这个问题 ——
  `BizPerms.ROLE_PERMS` 就是生效的那份。
- **C 端没有权限码**，这是设计不是缺口（`DataScopeSpec` 恒为 `SELF`，
  在 SQL 层防 IDOR）。所以 C 端那一列问的是另一个问题：**要不要登录**。
"""
import json
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/technical/reference/三端权限矩阵-按业务域.md"

# 权限码命名空间 → 需求矩阵的 L2 业务域。
# 绝大多数是一眼对上的；下面几条是判断，写出来是为了能被反驳：
#   · dashboard/risk 都归 16 数据与风控 —— 看板与风控在需求里本来就是一格
#   · biz:customer 归 16 —— 需求 §3 域 16 那格写的就是「经营数据 + 客户复购」
#   · biz:stock 归 18 进销存而不是 3 商品 —— 它管的是库存不是商品资料
DOMAINS = [
    ("1",  "账号与权限",   ["iam"],                    ["*none*"]),
    ("2",  "社区与网点",   ["community"],              ["*none*"]),
    ("3",  "商品与类目",   ["product"],                ["biz:goods"]),
    ("4",  "交易",         ["order"],                  ["biz:order:view"]),
    ("5",  "履约与核销",   ["fulfillment"],            ["biz:receive", "biz:verify", "biz:ship"]),
    ("6",  "售后与退款",   ["aftersale"],              ["biz:aftersale"]),
    ("7",  "营销与优惠",   ["marketing"],              ["biz:campaign"]),
    ("8",  "团购与求团",   ["group"],                  ["*none*"]),
    ("9",  "增长与裂变",   ["growth"],                 ["*none*"]),
    ("10", "门店主页",     ["store"],                  ["biz:store"]),
    ("11", "商家入驻与经营", ["merchant"],             ["biz:store:admin"]),
    ("12", "结算与资金",   ["finance"],                ["biz:finance"]),
    ("13", "评价与信用",   ["review"],                 ["biz:review"]),
    ("14", "消息与客服",   ["message", "member"],      ["*none*"]),
    ("15", "内容与素材",   ["content"],                ["*none*"]),
    ("16", "数据与风控",   ["risk", "dashboard"],      ["biz:customer"]),
    ("17", "系统与配置",   ["system"],                 ["*none*"]),
    ("18", "进销存",       ["inventory"],              ["biz:stock"]),
]

# 需求矩阵 §3 的一期重心那一列：哪些格子是 P0。
# 手抄自 docs/requirements/需求矩阵-三端.md §3（那张表全是 P0，只有几格是 — 或 P1）。
REQ_P0 = {  # 域 → (C, B, 平台)
    "1": (1, 1, 1), "2": (1, 1, 1), "3": (1, 1, 1), "4": (1, 1, 1), "5": (1, 1, 1),
    "6": (1, 1, 1), "7": (1, 1, 1), "8": (1, 1, 1), "9": (1, 1, 1), "10": (1, 1, 1),
    "11": (1, 1, 1), "12": (0, 1, 1), "13": (1, 1, 1), "14": (1, 1, 1),
    "15": (0, 1, 1), "16": (0, 1, 1), "17": (1, 1, 1), "18": (0, 1, 1),
}

BASE = ROOT / "backend/shop-base/src/main/java/ai/neargo/shop/auth"


def literals(path: Path) -> dict[str, str]:
    return dict(re.findall(r'String\s+([A-Z_0-9]+)\s*=\s*"([^"]+)"', path.read_text(encoding="utf-8")))


def role_perms(path: Path, lit: dict[str, str]) -> dict[str, set[str]]:
    """`Map.entry("ROLE", List.of(A, B, …))` 与 `ROLE, List.of(…)` 两种写法都认。"""
    src = path.read_text(encoding="utf-8")
    start = src.index("ROLE_PERMS")
    body = src[start:src.index("\n    }", start) if "\n    }" in src[start:] else len(src)]
    out: dict[str, set[str]] = {}
    for role, items in re.findall(r'(?:Map\.entry\(\s*)?"?([A-Z_]+)"?\s*,\s*List\.of\(([^)]*)\)', body):
        codes = set()
        for tok in re.findall(r'"([^"]+)"|\b([A-Z_0-9]{3,})\b', items):
            codes.add(tok[0] or lit.get(tok[1], ""))
        out[role] = {c for c in codes if c}
    return out


def endpoints_by_code(ops_lit, biz_lit):
    """端点 → 码。扫 @perm.can/@perm.canBiz 的实参；映射注解上下都要看
    （运营端把 @PreAuthorize 写在 @XxxMapping **下面** —— 只往上找会漏掉一大半，
    这个错 2026-08-29 犯过一次，得出「284 个端点裸奔」的结论）。"""
    by_code = defaultdict(list)
    bare = []
    for f in (ROOT / "backend").rglob("*.java"):
        if "/test/" in str(f) or "/target/" in str(f):
            continue
        src = f.read_text(encoding="utf-8", errors="ignore")
        if "Mapping(" not in src:
            continue
        cls = re.search(r'@RequestMapping\(\s*"([^"]+)"\s*\)', src)
        base = cls.group(1) if cls else ""
        lines = src.split("\n")
        for i, line in enumerate(lines):
            m = re.search(r'@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"', line)
            if not m:
                continue
            path = m.group(2) if m.group(2).startswith("/") else base.rstrip("/") + m.group(2)
            if not path.startswith(("/ops", "/biz")):
                continue
            lit = ops_lit if path.startswith("/ops") else biz_lit
            codes = set()
            for j in range(max(0, i - 5), min(len(lines), i + 6)):
                if "@perm.can" not in lines[j]:
                    continue
                for name in re.findall(r"Perms\.([A-Z_0-9]+)", lines[j]):
                    codes.add(lit.get(name, name))
                for raw in re.findall(r"@perm\.can\w*\('([^']+)'", lines[j]):
                    # `@perm.can('" + Perms.X + "')` 会把拼接片段也捞出来 —— 那不是码
                    if "Perms." not in raw and "+" not in raw:
                        codes.add(raw)
            if codes:
                for c in codes:
                    by_code[c].append(f"{m.group(1).upper()} {path}")
            else:
                bare.append(f"{m.group(1).upper()} {path}")
    return by_code, bare


def c_end_auth():
    """C 端没有码，问的是要不要登录。"""
    src = (ROOT / "c-app/src/api/endpoints.ts").read_text(encoding="utf-8")
    ent = re.findall(r'(\w+):\s*\{\s*method:\s*"(\w+)",\s*path:\s*"([^"]+)",\s*auth:\s*(true|false)', src)
    return ent



# `/ops/<段>` 被两个命名空间的码分着守 —— 逐条的结论。
# 三条是刻意的职责分离（理由在代码注释里，这里只摘一句）；一条不是。
SPLIT_VERDICT = {
    "payments": "刻意：读写分离（`order:read` 看得到关单时长，`order:modify` 才改得动 —— "
                "「调短了会把正在付款的人关掉」）",
    "plan-defs": "刻意：BD 能给某家商家授予套餐（`merchant:read` 读档位填下拉），"
                 "但改「套餐是什么」要 `system:param:update` —— 后者影响这一档之后的所有订阅",
    "stores": "合理：`/ops/stores` 是商家治理（`merchant:*`），"
              "`/ops/stores/audits` 是店招公告人审，属门店主页域（`store:page:audit`）",
}


def split_segments(by_code):
    """路径二级段 → {命名空间: 端点集合}，只留被两个以上命名空间守着的。"""
    seg = defaultdict(lambda: defaultdict(set))
    for code, eps in by_code.items():
        if ":" not in code:
            continue
        for e in eps:
            parts = e.split(" ", 1)[1].split("/")
            if len(parts) > 2 and parts[1] == "ops":
                seg[parts[2]][code.split(":")[0]].add(e)
    return {k: v for k, v in seg.items() if len(v) > 1}


def main(quiet: bool = False) -> int:
    ops_lit = literals(BASE / "Perms.java")
    biz_lit = literals(BASE / "BizPerms.java")
    biz_codes = {v for k, v in biz_lit.items() if ":" in v}
    by_code, bare = endpoints_by_code(ops_lit, biz_lit)
    ops_roles = role_perms(BASE / "Perms.java", ops_lit)
    biz_roles = role_perms(BASE / "BizPerms.java", biz_lit)
    cend = c_end_auth()

    ops_codes = {v for v in ops_lit.values() if ":" in v}
    claimed = {ns for d in DOMAINS for ns in d[2]}
    orphan_ns = sorted({c.split(":")[0] for c in ops_codes} - claimed)

    L = ["# 三端权限矩阵 · 按业务域",
         "",
         "> **本文是生成的**：`python3 scripts/gen-perm-domain-matrix.py`。不要手改。",
         "> 按 [需求矩阵-三端](../../requirements/需求矩阵-三端.md) 的 L2 业务域，",
         "> 把三端的判权摆在一起 —— 已有的四份矩阵各自按端切，横着读不了。",
         "",
         "## 0. 三端的判权机制各不相同，比较前先看这一格",
         "",
         "| 端 | 机制 | 码数 | 通配 | 角色→码 的真源 |",
         "|---|---|--:|---|---|",
         f"| 运营 | `@perm.can` | {len(ops_codes)} | 支持 `*` 与 `merchant:*` | "
         "**库**（`sys_role_point`）· 代码里 `Perms.ROLE_PERMS` 只是回落表 |",
         f"| B | `@perm.canBiz`，权限由 `BizContext` 每请求现算 | {len(biz_codes)} | "
         "**刻意不支持** | 代码（`BizPerms.ROLE_PERMS`）|",
         "| C | 无权限码，`DataScopeSpec` 恒为 `SELF` 在 SQL 层防 IDOR | 0 | — | — |",
         "",
         "> ⚠️ **运营端那一列的角色是「代码里默认给谁」，不是「线上配给了谁」。**",
         "> 两者可以不同，且不同了没有任何东西会说。要线上真值得查库。",
         "",
         "## 1. 业务域 × 端",
         "",
         "`码/端点` = 这个域有几个权限码、它们一共守着几个端点。",
         "`需求` 列取自需求矩阵 §3（●=该端标了 P0）。",
         "",
         "| L2 | 业务域 | 需求 C/B/平台 | 运营端 码/端点 | B 端 码/端点 | C 端 端点(游客可用) |",
         "|:--:|---|:--:|--:|--:|--:|"]

    cend_dom = classify_c_endpoints(cend)
    misalign = []
    for num, name, nss, bcodes in DOMAINS:
        ocs = sorted(c for c in ops_codes if c.split(":")[0] in nss)
        oeps = sum(len(by_code.get(c, [])) for c in ocs)
        bcs = [c for c in bcodes if c in biz_codes]
        beps = sum(len(by_code.get(c, [])) for c in bcs)
        ce = cend_dom.get(num, [])
        guest = sum(1 for e in ce if e[3] == "false")
        req = REQ_P0.get(num, (0, 0, 0))
        mark = "".join("●" if x else "○" for x in req)
        L.append(f"| {num} | {name} | {mark} | {len(ocs)}/{oeps} | "
                 f"{len(bcs)}/{beps} | {len(ce)}({guest}) |")
        if req[2] and not ocs:
            misalign.append((num, name, "平台端", "需求标 P0，但运营端这个域**一个权限码都没有**"))
        if req[1] and not bcs:
            misalign.append((num, name, "B 端", "需求标 P0，但 B 端这个域**没有专属权限码**"))

    ops_eps = {e for c, v in by_code.items() if c in ops_codes for e in v}
    biz_eps = {e for c, v in by_code.items() if c in biz_codes for e in v}
    L += ["", f"合计：运营端 {len(ops_codes)} 码 / **{len(ops_eps)}** 个受控端点 · "
              f"B 端 {len(biz_codes)} 码 / **{len(biz_eps)}** 个 · "
              f"C 端 {len(cend)} 端点（{sum(1 for e in cend if e[3] == 'false')} 个游客可用）",
          ""]

    L += ["## 2. 逐域展开（运营端）", ""]
    for num, name, nss, _ in DOMAINS:
        ocs = sorted(c for c in ops_codes if c.split(":")[0] in nss)
        if not ocs:
            continue
        L += [f"### {num} {name}", "",
              "| 权限码 | 端点数 | 代码里默认给哪些角色 |", "|---|--:|---|"]
        for c in ocs:
            who = sorted(r for r, ps in ops_roles.items() if c in ps or "*" in ps)
            L.append(f"| `{c}` | {len(by_code.get(c, []))} | {', '.join(who) or '**无**'} |")
        L.append("")

    L += ["## 3. B 端：6 角色 × 13 码", "",
          "| 权限码 | 端点数 | 角色 |", "|---|--:|---|"]
    for c in sorted(biz_codes):
        who = sorted(r for r, ps in biz_roles.items() if c in ps or "*" in ps)
        L.append(f"| `{c}` | {len(by_code.get(c, []))} | {', '.join(who)} |")

    L += ["", "## 4. 错位", ""]
    if orphan_ns:
        L += ["### 4.1 没有归到任何业务域的命名空间", "",
              "生成器的 `DOMAINS` 表里没有它们 —— 要么是新加的域，要么归错了：", "",
              *[f"- `{ns}:*`" for ns in orphan_ns], ""]

    L += ["### 4.2 同一组端点被两个域的码分着守", "",
          "判据：同一个路径二级段（`/ops/<段>`），守它的码来自两个不同的命名空间。",
          "**这个判据不能当闸门** —— 下面每一组都是刻意的，理由就写在代码注释里。",
          "它的价值在于：当某一组**不是**刻意的时候，会在这里露出来。", ""]
    L += ["| 路径段 | 分给了 | 结论 |", "|---|---|---|"]
    for seg, by_ns in sorted(split_segments(by_code).items()):
        L.append(f"| `/ops/{seg}` | " +
                 " · ".join(f"`{ns}:*` {len(v)} 个" for ns, v in sorted(by_ns.items())) +
                 f" | {SPLIT_VERDICT.get(seg, '**未判定** —— 新出现的，需要看一眼')} |")
    L += ["",
          "> **它已经露出过一次。** 2026-08-29 这张表上还有第四行 `/ops/inventory`：",
          "> 域 18 当时在运营端没有自己的命名空间，7 个端点整体寄在别人名下 ——",
          "> 只读那几个挂 `product:sku:read`，**发放与吊销开放对接凭证**挂",
          "> `merchant:mode:update`。于是授予商品运营看 SKU 的权，就一并授出了",
          "> 全平台库存台账；授予商家运营改经营模式的权，就一并授出了",
          "> **给外部系统开 API 钥匙**的权力 —— 而 BD 恰好持有它，",
          "> 于是 BD 事实上能发钥匙，却看不见那一页。",
          "> 已由 `V272__inventory_perm_namespace.sql` 立成 `inventory:*` 三个码。", ""]

    ops_gap = [m for m in misalign if m[2] == "平台端"]
    L += ["### 4.3 需求标了 P0、而运营端没有对应权限码的域", ""]
    if ops_gap:
        L += ["| L2 | 业务域 | 说明 |", "|:--:|---|---|"]
        for num, name, _, why in ops_gap:
            L.append(f"| {num} | {name} | {why} |")
    else:
        L.append("无。")
    L += ["",
          "> B 端不在这张表里：它只有 13 个码，**本来就不按 17 个业务域切**",
          "> （码的轴是「店里谁干什么活」—— 到货登记、核销、发货、看订单…）。",
          "> 拿 17 域去量它会得出七条「缺码」，那是分类法不匹配，不是缺口。", ""]

    L += ["### 4.4 没有权限码的端点", "",
          f"共 {len(bare)} 个（`/ops` {sum(1 for b in bare if ' /ops' in b)} · "
          f"`/biz` {sum(1 for b in bare if ' /biz' in b)}）。**这不等于缺口** —— "
          "两边都已逐条登记并有守卫：`/biz` 那批在 `BizEndpointPermTest.REQUIRED`"
          "（`PUBLIC` 与「要角色不要码」两张表），`/ops` 那批在 "
          "`OpsEndpointPermTest.ANY_OPERATOR`（登录/找回密码/验证码/`me`/改自己密码/"
          "菜单/自己的站内信/SSE）。漏登记会直接红。",
          "",
          f"对得上：{len(ops_eps)} + {sum(1 for b in bare if ' /ops' in b)} = "
          f"{len(ops_eps) + sum(1 for b in bare if ' /ops' in b)} 个 `/ops` 端点，"
          f"{len(biz_eps)} + {sum(1 for b in bare if ' /biz' in b)} = "
          f"{len(biz_eps) + sum(1 for b in bare if ' /biz' in b)} 个 `/biz` 端点 —— "
          "**这两条等式本身就是对照量**：扫描器要是整体失效，两边都会对不上。", ""]

    OUT.write_text("\n".join(L) + "\n", encoding="utf-8")
    if quiet:
        return 0
    print(f"wrote {OUT.relative_to(ROOT)}")
    print(f"  运营端 {len(ops_codes)} 码 · B 端 {len(biz_codes)} 码 · C 端 {len(cend)} 端点")
    print(f"  错位 {len(misalign)} 格 · 未归域命名空间 {len(orphan_ns)} 个 · 无码端点 {len(bare)}")
    return 0


def classify_c_endpoints(ent):
    """C 端端点按路径二级段归到业务域。只为数个数，归不上的落在 '—'。"""
    seg2dom = {
        "user": "1", "auth": "1", "community": "2", "goods": "3", "category": "3",
        "search": "3", "cart": "4", "order": "4", "pay": "4", "invoice": "4",
        "pickup": "5", "fulfillment": "5", "after-sale": "6", "coupon": "7",
        "my-coupons": "7", "points": "7", "card": "7", "activity": "7",
        "group-buy": "8", "group-request": "8", "attribution": "9", "share": "9",
        "store": "10", "stores": "10", "merchant": "11", "review": "13",
        "message": "14", "my-memberships": "14", "config": "17",
    }
    out = defaultdict(list)
    for e in ent:
        parts = e[2].split("/")
        out[seg2dom.get(parts[2] if len(parts) > 2 else "", "—")].append(e)
    return out


if __name__ == "__main__":
    if "--check" in sys.argv:
        # **只校验，不留痕**：pre-push 跑它的时候工作区必须原样回来 ——
        # 一道会改工作区的闸门，第二次跑就已经不是在校验了。
        before = OUT.read_text(encoding="utf-8") if OUT.exists() else None
        main(quiet=True)
        after = OUT.read_text(encoding="utf-8")
        if before is None:
            OUT.unlink()
            print("✗ 三端权限矩阵还没生成过 —— 跑一次生成器并提交产物", file=sys.stderr)
            sys.exit(1)
        OUT.write_text(before, encoding="utf-8")
        if before != after:
            print("✗ 三端权限矩阵与代码不一致 —— 重跑生成器并提交产物", file=sys.stderr)
            sys.exit(1)
        print("✓ 三端权限矩阵是最新的")
        sys.exit(0)
    sys.exit(main())
