#!/usr/bin/env python3
"""旧库调用清单：按运行时的选库规则，算出每段代码现在访问的是旧库还是新库。

目标状态（用户 2026-11-27）：业务不再访问旧库。旧库指
  - 主库 spring.datasource（线上 ai_shop_ume）以及复用它地址的支付池、密钥池；
  - 进销存、报表、任务各自的独立旧库（线上 ai_shop_ume_inv、*_pilot 等）。
新库是 .61 上按瘦身设计建的 n_* 库，表名快照在 docs/technical/design/legacy-db/new-db-tables.json。

选库规则与运行时一致（ModuleMapperScanConfig、MybatisPlusConfig、PayDataSourceConfig、
各插件的 *PersistenceConfiguration）：
  句柄标记 PlatformTableMapper / TradeTableMapper / IndustryTableMapper / CommonTableMapper /
  PayMerchantTableMapper（及 *SqlMapper）→ 对应句柄；发票、打印、会员营销按包名；
  支付包其余 → 支付池；其余 BaseMapper / @Mapper → 主库。
句柄是否已绑新库读 application.yml 的 shop.modules.<模块>.datasource；没绑就仍落在主库（或支付池）。

用法：
  python3 scripts/gen-legacy-db-usage.py           # 生成 docs/technical/design/旧库调用清单.md
  python3 scripts/gen-legacy-db-usage.py --check   # 只校验产物是否最新
"""
from __future__ import annotations

import collections
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs/technical/design/旧库调用清单.md"
SNAPSHOT = ROOT / "docs/technical/design/legacy-db/new-db-tables.json"
APP_YML = ROOT / "backend/shop-app/src/main/resources/application.yml"

HANDLE_TARGET = {
    "platform": "n_platform", "trade": "n_trade", "industry": "n_industry", "common": "n_common",
    "invoice": "n_service", "print": "n_service", "payMerchant": "n_pay_merchant", "memberMarketing": "n_member",
}
HANDLE_UNBOUND = {"payMerchant": "支付池", "memberMarketing": "支付池"}  # ModuleDataSources.unboundTarget
OWN_POOLS = {  # 不走句柄、自建连接池的模块：(去向, 建议新库)
    "inventory": ("独立旧库：进销存（shop.inventory.datasource）", "n_inventory"),
    "secret": ("旧库：密钥池（复用 spring.datasource）", "n_secret"),
    "job": ("独立旧库：任务（job.store.datasource）", "n_job"),
    "report": ("独立旧库：报表（shop.report.datasource）", "n_report"),
    "pay": ("旧库：支付池（复用 spring.datasource）", "n_pay"),
}
MARKERS = {
    "PlatformTableMapper": "platform", "PlatformSqlMapper": "platform",
    "TradeTableMapper": "trade", "TradeSqlMapper": "trade",
    "IndustryTableMapper": "industry", "CommonTableMapper": "common",
    "PayMerchantTableMapper": "payMerchant",
}
SQL_WORDS = {"select", "where", "set", "values", "dual", "information_schema", "the", "a", "and", "or", "on",
             "lateral", "json_table", "table", "if", "case", "when"}


def sources():
    files = subprocess.run(["git", "ls-files", "backend/*.java", "backend-plugins/*.java"], cwd=ROOT,
                           capture_output=True, text=True, check=True).stdout.split()
    return [f for f in files if "/src/main/java/" in f]


def module_of(path: str) -> str:
    return path.split("/src/main/java/")[0]


def body_after(text: str, start: int) -> str:
    """从 start 处的 '{' 起按括号配平取接口体。SQL 里的 #{...}、${...} 本身配平，不影响。"""
    depth, i = 0, text.index("{", start)
    j = i
    while j < len(text):
        c = text[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
        j += 1
    return text[i:]


def sql_tables(body: str) -> set[str]:
    found = set()
    for m in re.finditer(r"(?i)\b(?:from|join|into|update)\s+`?([a-z][a-z0-9_]*)`?", body):
        t = m.group(1).lower()
        if "_" in t and t not in SQL_WORDS:
            found.add(t)
    return found


def bound_modules() -> dict[str, str]:
    text = APP_YML.read_text(encoding="utf-8")
    block = re.search(r"\n  modules:\n((?:    .*\n|\s*\n)+)", text)
    out = {}
    if block:
        for m in re.finditer(r"\n    ([\w-]+):\n      datasource: (\w+)", "\n" + block.group(1)):
            out[m.group(1)] = m.group(2)
    return out


def classify(pkg: str, markers: set[str], bound: dict[str, str]):
    """返回 (去向说明, 是否旧库, 句柄或池, 建议新库)。"""
    def handle(h):
        if h in bound:
            return (f"新库：{HANDLE_TARGET[h]}（句柄 {h}，已绑定）", False, h, HANDLE_TARGET[h])
        fallback = HANDLE_UNBOUND.get(h, "主库")
        return (f"旧库：{fallback}（句柄 {h} 未绑定）", True, h, HANDLE_TARGET[h])

    if pkg.startswith("ai.neargo.inventory"):
        return (*OWN_POOLS["inventory"][:1], True, "inventory", OWN_POOLS["inventory"][1])
    if pkg.startswith("ai.neargo.secret"):
        return (OWN_POOLS["secret"][0], True, "secret", OWN_POOLS["secret"][1])
    if pkg.startswith("ai.neargo.job"):
        return (OWN_POOLS["job"][0], True, "job", OWN_POOLS["job"][1])
    if pkg.startswith("ai.neargo.shop.report"):
        return (OWN_POOLS["report"][0], True, "report", OWN_POOLS["report"][1])
    if pkg.startswith("ai.neargo.shop.pay.mapper"):
        if "PayMerchantTableMapper" in markers:
            return handle("payMerchant")
        if re.match(r"ai\.neargo\.shop\.pay\.mapper\.(entitlement|storedvalue)\b", pkg):
            return handle("memberMarketing")
        return (OWN_POOLS["pay"][0], True, "pay", OWN_POOLS["pay"][1])
    if pkg.startswith("ai.neargo.shop.invoice.legacy"):
        return ("旧库：主库（发票留在主库的表）", True, "primary", None)
    if pkg.startswith("ai.neargo.shop.invoice.mapper"):
        return handle("invoice")
    if pkg.startswith("ai.neargo.shop.printing"):
        return handle("print")
    for marker, h in MARKERS.items():
        if marker in markers:
            return handle(h)
    if pkg.startswith("ai.neargo.shop.retail.commerce"):
        return handle("trade")
    return ("旧库：主库", True, "primary", None)


def business(pkg: str) -> str:
    parts = pkg.split(".")
    if parts[:3] == ["ai", "neargo", "shop"] and len(parts) > 3:
        return "shop." + parts[3]
    return ".".join(parts[2:3]) if len(parts) > 2 else pkg


def main():
    check = "--check" in sys.argv
    snap = json.loads(SNAPSHOT.read_text(encoding="utf-8"))["databases"]
    table_home = collections.defaultdict(list)
    for db, tables in snap.items():
        for t in tables:
            table_home[t].append(db)
    bound = bound_modules()

    files = sources()
    texts = {f: (ROOT / f).read_text(encoding="utf-8", errors="replace") for f in files}
    entity_table = {}
    for f, t in texts.items():
        for m in re.finditer(r'@TableName\(\s*(?:value\s*=\s*)?"([a-zA-Z0-9_]+)"[^)]*\)\s*(?:@\w+(?:\([^)]*\))?\s*)*'
                             r'public\s+(?:final\s+)?(?:class|record)\s+(\w+)', t):
            entity_table.setdefault(m.group(2), m.group(1).lower())

    mappers = []
    iface = re.compile(r"\binterface\s+(\w+)\s*(?:<[^{]*?>)?\s*extends\s+([^{]+)\{")
    for f, t in texts.items():
        pkg_m = re.search(r"^package\s+([\w.]+);", t, re.M)
        if not pkg_m:
            continue
        pkg = pkg_m.group(1)
        for m in iface.finditer(t):
            ext = m.group(2)
            names = set(re.findall(r"\b([A-Z]\w*)\s*<", ext)) | set(re.findall(r"\b([A-Z]\w*)\b", ext))
            if not names & (set(MARKERS) | {"BaseMapper"}):
                continue
            generic = re.search(r"(?:BaseMapper|\w+TableMapper)\s*<\s*([\w.]+)", ext)
            tables = set()
            if generic:
                entity = generic.group(1).split(".")[-1]
                if entity in entity_table:
                    tables.add(entity_table[entity])
            tables |= sql_tables(body_after(t, m.start()))
            where, legacy, pool, target = classify(pkg, names, bound)
            mappers.append(dict(file=f, module=module_of(f), pkg=pkg, name=m.group(1), where=where,
                                legacy=legacy, pool=pool, target=target, tables=sorted(tables)))
        # 不继承 BaseMapper、只打 @Mapper 的接口（全局第二个扫描收，走主库）
        for m in re.finditer(r"@Mapper\s*(?:@\w+(?:\([^)]*\))?\s*)*public\s+interface\s+(\w+)\s*\{", t):
            if any(x["file"] == f and x["name"] == m.group(1) for x in mappers):
                continue
            where, legacy, pool, target = ("旧库：主库（@Mapper）", True, "primary", None)
            mappers.append(dict(file=f, module=module_of(f), pkg=pkg, name=m.group(1), where=where, legacy=legacy,
                                pool=pool, target=target, tables=sorted(sql_tables(body_after(t, m.start())))))

    def suggest(mp):
        if mp["target"]:
            return mp["target"]
        votes = collections.Counter(db for tb in mp["tables"] for db in table_home.get(tb, []))
        return votes.most_common(1)[0][0] + "（按表名推断）" if votes else "未定（新库里没有同名表）"

    def missing(mp):
        db = (mp["target"] or "").split("（")[0]
        if not db:
            return []
        have = set(snap.get(db, []))
        return [tb for tb in mp["tables"] if tb not in have]

    # 直接用 JDBC 访问数据源的代码（不经 Mapper）
    jdbc = []
    jdbc_types = re.compile(r"\b(JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient|DataSource)\s+\w+\s*[,)=;]")
    for f, t in texts.items():
        if not jdbc_types.search(t) or "/config/" in f and "DataSource" in Path(f).name:
            continue
        quals = set(re.findall(r'@Qualifier\(\s*(?:ModuleDataSources\.)?"?(\w+)"?\s*\)', t))
        handles = sorted(q for q in quals if q not in ("dataSource", "jdbcTemplate", "transactionManager"))
        primary = not handles or "dataSource" in quals or "jdbcTemplate" in quals
        jdbc.append(dict(file=f, module=module_of(f), quals=handles, primary=primary))

    legacy_refs = subprocess.run(
        ["git", "grep", "-l", "-E",
         r"ai_shop_ume|SPRING_DATASOURCE_(URL|USERNAME|PASSWORD)|SHOP_DB_(USER|PASS)|spring\.datasource\.url",
         "--", ":!docs/", ":!*.md", ":!**/src/test/**", ":!scripts/gen-legacy-db-usage.py"],
        cwd=ROOT, capture_output=True, text=True).stdout.split()

    out = render(mappers, jdbc, legacy_refs, bound, suggest, missing)
    if check:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != out:
            print(f"✗ {OUT.relative_to(ROOT)} 不是最新：跑一次 python3 scripts/gen-legacy-db-usage.py")
            sys.exit(1)
        print(f"✓ {OUT.relative_to(ROOT)} 是最新的")
        return
    OUT.write_text(out, encoding="utf-8")
    legacy = sum(1 for m in mappers if m["legacy"])
    print(f"{OUT.relative_to(ROOT)}: Mapper {len(mappers)} 个，仍访问旧库 {legacy} 个；"
          f"直连 JDBC {sum(1 for j in jdbc if j['primary'])} 处；配置与脚本里的旧库地址 {len(legacy_refs)} 个文件")


def render(mappers, jdbc, legacy_refs, bound, suggest, missing):
    L = []
    add = L.append
    add("# 旧库调用清单")
    add("")
    add("> 由 `scripts/gen-legacy-db-usage.py` 生成，不要手改。迁完一个模块（绑新库、改表）后重跑。")
    add("")
    add("目标：业务不再访问旧库（用户 2026-11-27）。旧库 = 主库 `spring.datasource`（线上 `ai_shop_ume`）及复用它地址的"
        "支付池、密钥池，加上进销存、任务、报表各自的独立旧库。新库 = .61 上的 `n_*`，表名快照见 "
        "`docs/technical/design/legacy-db/new-db-tables.json`。")
    add("")
    add("当前已绑新库的模块（`shop.modules.*.datasource`）：" +
        ("、".join(f"`{k}` → `{v}`" for k, v in sorted(bound.items())) or "无"))
    add("")
    add("选库规则与运行时一致：句柄标记（`PlatformTableMapper` 等）→ 句柄；句柄没绑新库就落回主库（支付拆出来的两个句柄落回支付池）；"
        "发票、打印、会员营销按包名；支付包其余 → 支付池；其余 Mapper → 主库。")
    add("")
    # 1 总览
    add("## 1 按业务模块汇总")
    add("")
    add("| 业务模块（包） | Maven 模块 | Mapper | 仍访问旧库 | 其中已归句柄未绑定 | 已在新库 | 涉及表 | 建议目标库 |")
    add("|---|---|---|---|---|---|---|---|")
    groups = collections.defaultdict(list)
    for m in mappers:
        groups[(business_label(m["pkg"]), m["module"])].append(m)
    for (biz, mod), ms in sorted(groups.items(), key=lambda kv: (-sum(x["legacy"] for x in kv[1]), kv[0])):
        legacy = [x for x in ms if x["legacy"]]
        handled = [x for x in legacy if x["pool"] in HANDLE_TARGET]
        tables = sorted({t for x in ms for t in x["tables"]})
        targets = collections.Counter(suggest(x).split("（")[0] for x in legacy)
        tgt = "、".join(f"{k}×{v}" for k, v in targets.most_common()) if targets else "—"
        add(f"| `{biz}` | `{mod}` | {len(ms)} | {len(legacy)} | {len(handled)} | {len(ms) - len(legacy)} | {len(tables)} | {tgt} |")
    total_legacy = sum(1 for m in mappers if m["legacy"])
    add(f"| **合计** | | **{len(mappers)}** | **{total_legacy}** | "
        f"**{sum(1 for m in mappers if m['legacy'] and m['pool'] in HANDLE_TARGET)}** | "
        f"**{len(mappers) - total_legacy}** | | |")
    add("")
    # 2 去向汇总
    add("## 2 按去向汇总")
    add("")
    add("| 去向 | Mapper 数 |")
    add("|---|---|")
    for where, n in collections.Counter(m["where"] for m in mappers).most_common():
        add(f"| {where} | {n} |")
    add("")
    # 3 JDBC
    add("## 3 不经 Mapper、直接用 JDBC 的代码")
    add("")
    add("注入了 `JdbcTemplate` / `NamedParameterJdbcTemplate` / `JdbcClient` / `DataSource` 的类。没有点名句柄的视为主库（需逐个复核）。")
    add("")
    add("| 文件 | Maven 模块 | 点名的数据源 | 判断 |")
    add("|---|---|---|---|")
    for j in sorted(jdbc, key=lambda x: (not x["primary"], x["file"])):
        add(f"| `{j['file'].split('/java/')[-1]}` | `{j['module']}` | {'、'.join(j['quals']) or '—'} | "
            f"{'旧库：主库（待复核）' if j['primary'] else '按点名的数据源'} |")
    add("")
    # 4 配置与脚本
    add("## 4 配置与脚本里写着旧库地址的文件")
    add("")
    add("匹配 `ai_shop_ume`、`SPRING_DATASOURCE_*`、`SHOP_DB_*`、`spring.datasource.url`（不含文档与测试）。")
    add("")
    for f in legacy_refs:
        add(f"- `{f}`")
    add("")
    # 5 明细
    add("## 5 明细（仍访问旧库的 Mapper）")
    add("")
    for (biz, mod), ms in sorted(groups.items()):
        legacy = [x for x in ms if x["legacy"]]
        if not legacy:
            continue
        add(f"### `{biz}` · `{mod}`")
        add("")
        add("| Mapper | 现在去向 | 建议目标库 | 用到的表 | 目标库里缺的表 |")
        add("|---|---|---|---|---|")
        for x in sorted(legacy, key=lambda y: (y["pkg"], y["name"])):
            miss = missing(x)
            add(f"| `{x['name']}` | {x['where']} | {suggest(x)} | {', '.join(x['tables']) or '—'} | "
                f"{', '.join(miss) if miss else ('—' if x['target'] else '')} |")
        add("")
    return "\n".join(L) + "\n"


def business_label(pkg: str) -> str:
    parts = pkg.split(".")
    if parts[:3] == ["ai", "neargo", "shop"] and len(parts) > 3:
        return "shop." + parts[3]
    if parts[:2] == ["ai", "neargo"] and len(parts) > 2:
        return parts[2]
    return pkg


if __name__ == "__main__":
    main()
