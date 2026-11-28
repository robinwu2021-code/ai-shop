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
    "product": "n_trade", "invoice": "n_service", "print": "n_service", "payMerchant": "n_pay_merchant", "memberMarketing": "n_member",
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
    "ProductTableMapper": "product", "MemberMarketingTableMapper": "memberMarketing",
}
# 只统计 ai-shop 自己的业务代码（用户 2026-11-27：暂时只处理 ai-shop 项目）。
# 支付域归支付服务、进销存是独立服务、密钥走平台统一密钥接口、任务调度是公共能力，不在这份清单里。
OUT_OF_SCOPE = {"backend/pay/": "支付域（归支付服务）", "backend/inventory/": "进销存（独立服务）",
                "backend/secret/": "密钥（平台统一密钥接口）", "backend/job/": "任务调度（公共能力）"}
BIZ_NAMES = {
    "shop.trade": "交易：订单、售后、结算", "shop.product": "商品、规格、类目、上架与定价",
    "shop.merchant": "商户、门店、员工与权限", "shop.merchant.booking": "门店预约：资源、排班、占用",
    "shop.user": "C 端账号与收货地址", "shop.member": "会员", "shop.marketing": "营销：拼团、裂变、卡券、归因",
    "shop.promotion": "活动与促销", "shop.fulfillment": "履约：自提、配送", "shop.community": "社区与自提点",
    "shop.platform": "平台运营：设置、地区、行业、审计、员工", "shop.message": "消息通知与推送",
    "shop.content": "内容：素材、帖子、问答、榜单", "shop.risk": "风控", "shop.link": "短链",
    "shop.media": "媒体文件", "shop.archive": "归档", "shop.idem": "请求防重", "shop.event": "事件发件箱",
    "shop.job": "任务运行记录", "shop.printing": "打印", "shop.invoice": "发票", "shop.logistics": "物流",
    "shop.invbridge": "库存对接", "shop.paybridge": "支付对接", "shop.food": "餐饮插件", "shop.beauty": "美业插件",
    "shop.commerce": "商城集成（平台商城）", "shop.retail": "零售渠道订单", "shop.report": "报表",
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

    all_files = sources()
    texts_all = {f: (ROOT / f).read_text(encoding="utf-8", errors="replace") for f in all_files}
    texts = {f: t for f, t in texts_all.items() if not any(f.startswith(p) for p in OUT_OF_SCOPE)}
    entity_table = {}
    for f, t in texts_all.items():
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
            if not names & (set(MARKERS) | {"BaseMapper"}) or m.group(1) in MARKERS:
                continue  # 句柄标记接口本身不是业务 Mapper
            generic = re.search(r"(?:BaseMapper|\w+TableMapper)\s*<\s*([\w.]+)", ext)
            tables = set()
            if generic:
                entity = generic.group(1).split(".")[-1]
                if entity in entity_table:
                    tables.add(entity_table[entity])
            tables |= sql_tables(body_after(t, m.start()))
            where, legacy, pool, target = classify(pkg, names, bound)
            mappers.append(dict(file=f, module=module_of(f), pkg=pkg, name=m.group(1), where=where,
                                legacy=legacy, pool=pool, target=target, tables=sorted(tables),
                                outer=None if Path(f).stem == m.group(1) else Path(f).stem))
        # 不继承 BaseMapper、只打 @Mapper 的接口（全局第二个扫描收，走主库）
        for m in re.finditer(r"@Mapper\s*(?:@\w+(?:\([^)]*\))?\s*)*public\s+interface\s+(\w+)\s*\{", t):
            if any(x["file"] == f and x["name"] == m.group(1) for x in mappers):
                continue
            where, legacy, pool, target = ("旧库：主库（@Mapper）", True, "primary", None)
            mappers.append(dict(file=f, module=module_of(f), pkg=pkg, name=m.group(1), where=where, legacy=legacy,
                                pool=pool, target=target, tables=sorted(sql_tables(body_after(t, m.start()))),
                                outer=None if Path(f).stem == m.group(1) else Path(f).stem))

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

    consumers = find_consumers(mappers, texts)

    legacy_refs = subprocess.run(
        ["git", "grep", "-l", "-E",
         r"ai_shop_ume|SPRING_DATASOURCE_(URL|USERNAME|PASSWORD)|SHOP_DB_(USER|PASS)|spring\.datasource\.url",
         "--", ":!docs/", ":!*.md", ":!**/src/test/**", ":!scripts/gen-legacy-db-usage.py",
         ":!backend/shop-store-mybatis/src/main/java/ai/neargo/shop/common/config/ModuleDataSources.java"],
        cwd=ROOT, capture_output=True, text=True).stdout.split()

    out = render(mappers, jdbc, legacy_refs, bound, suggest, missing, snap, consumers)
    out += render_consumers(mappers, consumers, suggest)
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


def render(mappers, jdbc, legacy_refs, bound, suggest, missing, snap, consumers):
    L = []
    add = L.append
    legacy_refs = [f for f in legacy_refs if not any(f.startswith(p) for p in OUT_OF_SCOPE)]
    add("# 旧库调用清单")
    add("")
    add("> 状态：按当前代码生成的旧库迁移盘点。")
    add("")
    add("> 由 `scripts/gen-legacy-db-usage.py` 生成，不要手改。迁完一个模块（绑新库、改表）后重跑。")
    add("")
    add("范围：只统计 ai-shop 自己的业务代码（用户 2026-11-27）。不含 " +
        "、".join(f"`{p.rstrip('/')}`（{why}）" for p, why in OUT_OF_SCOPE.items()) + "。")
    add("")
    render_modules(L, mappers, consumers, suggest, snap)
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


def source_of(m) -> str:
    """mapper 现在实际连到的物理数据源（句柄没绑定时算它落回的那个池）。"""
    w = m["where"]
    if w.startswith("新库："):
        return w.split("（")[0].replace("新库：", "新库 ")
    for key, label in (("支付池", "旧库 · 支付池"), ("密钥池", "旧库 · 密钥池"), ("进销存", "旧库 · 进销存独立库"),
                       ("任务", "旧库 · 任务独立库"), ("报表", "旧库 · 报表独立库")):
        if key in w:
            return label
    return "旧库 · 主库"


def find_consumers(mappers, texts):
    """每个类依赖了哪些 mapper：按 import（含嵌套类 Outer.Name）与同包引用判断。"""
    by_fqn, by_outer, by_pkg = {}, collections.defaultdict(list), collections.defaultdict(list)
    for i, m in enumerate(mappers):
        if m["outer"]:
            by_fqn[f"{m['pkg']}.{m['outer']}.{m['name']}"] = i
            by_outer[f"{m['pkg']}.{m['outer']}"].append(i)
        else:
            by_fqn[f"{m['pkg']}.{m['name']}"] = i
        by_pkg[m["pkg"]].append(i)
    mapper_files = {m["file"] for m in mappers}
    result = {}
    for f, t in texts.items():
        if f in mapper_files:
            continue
        pkg_m = re.search(r"^package\s+([\w.]+);", t, re.M)
        if not pkg_m:
            continue
        pkg = pkg_m.group(1)
        body = re.sub(r"^import\s.*$", "", t, flags=re.M)
        used = set()
        for imp, star in re.findall(r"^import\s+(?:static\s+)?([\w.]+?)(\.\*)?;", t, re.M):
            if not star and imp in by_fqn:
                m = mappers[by_fqn[imp]]
                if re.search(r"\b" + m["name"] + r"\b", body):
                    used.add(by_fqn[imp])
            for i in by_outer.get(imp, []):
                name = mappers[i]["name"]
                if re.search(r"\b" + mappers[i]["outer"] + r"\." + name + r"\b", body) or \
                        (star and re.search(r"\b" + name + r"\b", body)):
                    used.add(i)
        for i in by_pkg.get(pkg, []):
            m = mappers[i]
            token = (m["outer"] + r"\." + m["name"]) if m["outer"] else m["name"]
            if re.search(r"\b" + token + r"\b", body):
                used.add(i)
        if used:
            result[f] = sorted(used)
    return result


def render_consumers(mappers, consumers, suggest):
    L = []
    add = L.append
    by_source = collections.defaultdict(set)
    for f, idx in consumers.items():
        for i in idx:
            by_source[source_of(mappers[i])].add(f)
    mapper_count = collections.Counter(source_of(m) for m in mappers)

    def cls(f):
        return Path(f).stem

    add("## 6 按数据源：依赖它的 Mapper 与服务")
    add("")
    add("「服务」指引用了这些 Mapper 的类（Service、Port、Controller、Job、Coordinator 等），按 import 与同包引用判断。"
        "句柄没绑定的 Mapper 计在它落回的那个池。")
    add("")
    add("| 数据源（现在实际连到） | Mapper | 依赖它的类 |")
    add("|---|---|---|")
    for src in sorted(mapper_count, key=lambda s: (s.startswith("新库"), -mapper_count[s])):
        add(f"| {src} | {mapper_count[src]} | {len(by_source.get(src, ()))} |")
    add("")
    for src in sorted(mapper_count, key=lambda s: (s.startswith("新库"), -mapper_count[s])):
        files = sorted(by_source.get(src, ()), key=lambda f: (module_of(f), cls(f)))
        add(f"### {src}（{len(files)} 个类）")
        add("")
        add("| 类 | Maven 模块 | 用到的 Mapper |")
        add("|---|---|---|")
        for f in files:
            names = sorted({mappers[i]["name"] for i in consumers[f] if source_of(mappers[i]) == src})
            shown = "、".join(f"`{n}`" for n in names[:8]) + (f" 等 {len(names)} 个" if len(names) > 8 else "")
            add(f"| `{cls(f)}` | `{module_of(f)}` | {shown} |")
        add("")

    add("## 7 依赖多个数据源的服务")
    add("")
    add("同一个类里用到的 Mapper 分属不同的库：现在就跨库的（多为主库 + 支付池），和迁移后会跨库的"
        "（各 Mapper 的目标新库不同）。后者在各模块绑新库后，原来同一个事务里的写会被拆开，要先拆服务或改成跨库一致性。")
    add("")
    add("| 类 | Maven 模块 | 现在连到的数据源 | 迁移后的目标库 |")
    add("|---|---|---|---|")
    rows = []
    for f, idx in consumers.items():
        now = sorted({source_of(mappers[i]) for i in idx})
        later = sorted({suggest(mappers[i]).split("（")[0] for i in idx} - {"未定"})
        if len(now) > 1 or len(later) > 1:
            rows.append((-(len(later) + len(now)), module_of(f), cls(f), now, later))
    for _, mod, c, now, later in sorted(rows):
        add(f"| `{c}` | `{mod}` | {'、'.join(now)} | {'、'.join(later) or '—'} |")
    add("")
    add(f"共 {len(rows)} 个类。")
    add("")
    return "\n".join(L)


def render_modules(L, mappers, consumers, suggest, snap):
    """0 节：依赖旧库的业务模块清单（迁移按这张表排期）。"""
    add = L.append
    users = collections.defaultdict(set)
    cross = collections.defaultdict(set)
    for f, idx in consumers.items():
        targets = {suggest(mappers[i]).split("（")[0] for i in idx} - {"未定"}
        for i in idx:
            if mappers[i]["legacy"]:
                biz = business_label(mappers[i]["pkg"])
                users[biz].add(f)
                if len(targets) > 1:
                    cross[biz].add(f)
    groups = collections.defaultdict(list)
    for m in mappers:
        groups[business_label(m["pkg"])].append(m)
    add("## 0 依赖旧库的业务模块")
    add("")
    add("「目标库表」看这个模块仍在旧库的 Mapper 用到的表：在建议的新库里已有几张、缺几张；"
        "「未定」是新库里找不到同名表（按瘦身设计改了名或合并了），要先对表设计。"
        "「跨库服务」是用到的 Mapper 迁移后会落在不同新库的类。")
    add("")
    add("| # | 业务模块 | 包 | Maven 模块 | 旧库 Mapper | 依赖的服务类 | 跨库服务 | 建议目标库 | 目标库表 已有 / 缺 / 未定 |")
    add("|---|---|---|---|---|---|---|---|---|")
    rows = []
    for biz, ms in groups.items():
        legacy = [x for x in ms if x["legacy"]]
        if not legacy:
            continue
        have = miss = undecided = 0
        seen = set()
        for x in legacy:
            db = suggest(x).split("（")[0]
            for tb in x["tables"]:
                if (db, tb) in seen:
                    continue
                seen.add((db, tb))
                if db == "未定":
                    undecided += 1
                elif tb in snap.get(db, []):
                    have += 1
                else:
                    miss += 1
        targets = collections.Counter(suggest(x).split("（")[0] for x in legacy)
        mods = sorted({x["module"] for x in ms})
        rows.append((-len(legacy), biz, BIZ_NAMES.get(biz, ""), mods, len(legacy), len(users[biz]), len(cross[biz]),
                     "、".join(k for k, _ in targets.most_common() if k != "未定") or "未定", have, miss, undecided))
    for n, (_, biz, name, mods, nl, nu, nc, tgt, have, miss, und) in enumerate(sorted(rows), 1):
        add(f"| {n} | {name or biz} | `{biz}` | {'<br>'.join(f'`{m}`' for m in mods)} | {nl} | {nu} | {nc} | {tgt} | "
            f"{have} / {miss} / {und} |")
    add(f"| | **合计** | | | **{sum(r[4] for r in rows)}** | | | | |")
    add("")


def business_label(pkg: str) -> str:
    parts = pkg.split(".")
    if pkg.startswith("ai.neargo.shop.merchant.booking"):
        return "shop.merchant.booking"
    if parts[:3] == ["ai", "neargo", "shop"] and len(parts) > 3:
        return "shop." + parts[3]
    if parts[:2] == ["ai", "neargo"] and len(parts) > 2:
        return parts[2]
    return pkg


if __name__ == "__main__":
    main()
