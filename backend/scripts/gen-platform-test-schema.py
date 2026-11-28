#!/usr/bin/env python3
"""平台基础库的 H2 测试建表脚本：已换到新表的平台表按新库设计建（TDD-平台基础库换表 §2 第 2 步）。

来源：backend/shop-app/src/test/resources/db/n_platform/V1__platform_design.sql（新库设计，01-n_platform.sql 的测试副本）
      加 V2__platform_supplement.sql（设计之外、代码需要的补充）。
产物：backend/shop-app/src/test/resources/schema-platform-test.sql，h2db profile 在 schema-test.sql **之前**执行。
做法与商品领域相同（见 gen-product-test-schema.py 的说明）：先按新定义 CREATE IF NOT EXISTS，同名旧建表随之跳过；
改了名的表，旧表仍由 schema-test.sql 建，等代码不再用它再从测试库去掉。

第 2 步是逐项换的：每换完一项，把它的新表加进 TABLES，平台代码同时改成新结构，整套测试就在新表上验证这一项。
全部换完时 TABLES 就是全部平台表，那时平台句柄才绑到新库（整体一次合入 v2）。

V2 只认这几种语句：DROP INDEX（普通索引在 H2 不建，忽略）、ADD UNIQUE KEY（并进建表）、ALTER COLUMN … SET DEFAULT（改列默认值）、\nMODIFY COLUMN（整列换定义）、CREATE TABLE（设计里没有、要补的表）。
别的写法直接报错，免得补充悄悄没生效。

用法：python3 backend/scripts/gen-platform-test-schema.py          # 生成
      python3 backend/scripts/gen-platform-test-schema.py --check  # 只比对，不一致返回 1
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DESIGN = ROOT / "backend/shop-app/src/test/resources/db/n_platform/V1__platform_design.sql"
SUPPLEMENT = ROOT / "backend/shop-app/src/test/resources/db/n_platform/V2__platform_supplement.sql"
OUT = ROOT / "backend/shop-app/src/test/resources/schema-platform-test.sql"
# 挂在旧表上的过渡触发器：旧表由 schema-test.sql 建，所以单独一个文件、在它之后执行
OUT_AFTER = ROOT / "backend/shop-app/src/test/resources/schema-platform-test-after.sql"
TRIGGERS_AFTER = [
    'CREATE TRIGGER IF NOT EXISTS mch_store_role_to_grant AFTER INSERT, UPDATE, DELETE ON mch_store_role'
    ' FOR EACH ROW CALL "ai.neargo.shop.support.StoreRoleToGrantTrigger";',
]

# 已换到新结构的平台表（按换表顺序追加）。表名取自新库设计
TABLES = [
    "mch_store_fulfillment",  # F 履约方式（原 mch_fulfillment_channel）
    "usr_session",            # H C 端会话（并进了小程序会话的 openid，原 usr_mp_session）
    "auth_ticket",            # I 一次性票据（原 usr_login_ticket）
    "mch_entity",             # I PIN 策略并进主体（pos_pin_enabled）；A 评分、类目、两列都已换完
    "usr_login_log", "mch_session", "mch_login_log", "ops_session", "ops_login_log",  # I 会话与登录日志（同名，新增列先不用）
    "mch_role", "mch_role_point",  # B 角色权限码拆成一码一行
    "mch_category_license",        # A 类目授权码拆成一码一行（原 mch_entity.category_codes）
    "mch_store",                   # C 门店推荐位删掉（Q3）
    "mch_personnel",               # D 账号关联并进人员（原 mch_personnel_account，Q4）
    "mch_store_service_area",      # E 四张服务范围表并一张（channel 空串 = 基础覆盖面）
    "mch_service_area_cell",       # E 多边形网格（V2 补表，带 channel）
    "mch_grant", "mch_group",      # G 门店角色与管理授权并成授权表；管理空间改集团
    "mch_brand",                   # G 品牌（建品牌的幂等改走统一防重，去掉 create_key / hash）
]

# 过渡列：同名表里新设计去掉了、平台代码还在用的列（列定义取自旧测试库）。换完对应那一项就从这里删
TRANSITIONAL = {
    # B：代码已不读写；只给旧种子与旧测试写入用，由下面的触发器同步成 mch_role_point 的行
    "mch_role": ["perms"],
}

# 过渡列里新代码不再写的：去掉旧定义的 NOT NULL
NULLABLE = {("mch_role", "perms")}

# 过渡触发器：旧种子、旧测试还按过渡列写，同步到新结构（类在 shop-app 测试代码里，说明见类注释）
TRIGGERS = [
    'CREATE TRIGGER IF NOT EXISTS mch_role_perms_to_points AFTER INSERT, UPDATE, DELETE ON mch_role'
    ' FOR EACH ROW CALL "ai.neargo.shop.support.RolePermsToPointsTrigger";',
]

OLD = ROOT / "backend/shop-app/src/test/resources/schema-test.sql"
OLD_TABLE = re.compile(r"CREATE TABLE IF NOT EXISTS (\w+)\s*\((.*?)\n\);", re.S)
TABLE = re.compile(r"CREATE TABLE `(\w+)` \((.*?)\n\)[^;]*;", re.S)
DROP_INDEX = re.compile(r"ALTER TABLE `(\w+)` DROP INDEX `\w+`;")
ADD_UNIQUE = re.compile(r"ALTER TABLE `(\w+)` ADD UNIQUE KEY `(\w+)` (\(.*\));")
SET_DEFAULT = re.compile(r"ALTER TABLE `(\w+)` ALTER COLUMN `(\w+)` SET DEFAULT (.+);")
MODIFY = re.compile(r"ALTER TABLE `(\w+)` MODIFY COLUMN `(\w+)` (.+);")


def design_tables():
    out = {}
    # 设计里没有、V2 补的表（如服务范围网格）也按建表语句收进来
    for text in (DESIGN.read_text(encoding="utf-8"), SUPPLEMENT.read_text(encoding="utf-8")):
        for m in TABLE.finditer(text):
            out[m.group(1)] = [l.rstrip(",") for l in m.group(2).strip("\n").split("\n")]
    return out


def old_columns():
    out = {}
    for m in OLD_TABLE.finditer(OLD.read_text(encoding="utf-8")):
        cols = {}
        for line in m.group(2).split("\n"):
            c = re.match(r"\s*`?(\w+)`?\s+[A-Za-z]", line)
            if c and c.group(1).upper() not in ("PRIMARY", "UNIQUE", "KEY", "CONSTRAINT", "INDEX"):
                cols[c.group(1).lower()] = line.strip().rstrip(",")
        out[m.group(1)] = cols
    return out


def apply_supplement(tables):
    text = TABLE.sub("", SUPPLEMENT.read_text(encoding="utf-8"))   # 建表语句在 design_tables 里收过了
    for raw in text.split("\n"):
        stmt = raw.strip()
        if not stmt or stmt.startswith("--"):
            continue
        if DROP_INDEX.fullmatch(stmt):
            continue
        m = ADD_UNIQUE.fullmatch(stmt)
        if m:
            if m.group(1) in tables:
                tables[m.group(1)].append(f"  UNIQUE KEY `{m.group(2)}` {m.group(3)}")
            continue
        m = MODIFY.fullmatch(stmt)
        if m:
            table, col, definition = m.groups()
            if table not in tables:
                continue
            lines = tables[table]
            idx = [i for i, l in enumerate(lines) if re.match(rf"\s*`{col}` ", l)]
            if len(idx) != 1:
                raise SystemExit(f"V2：{table}.{col} 在设计里找不到")
            lines[idx[0]] = f"  `{col}` {definition}"
            continue
        m = SET_DEFAULT.fullmatch(stmt)
        if m:
            table, col, value = m.groups()
            if table not in tables:
                continue
            lines = tables[table]
            idx = [i for i, l in enumerate(lines) if re.match(rf"\s*`{col}` ", l)]
            if len(idx) != 1:
                raise SystemExit(f"V2：{table}.{col} 在设计里找不到")
            line = re.sub(r" DEFAULT (?:'(?:[^']|'')*'|\S+)", "", lines[idx[0]])
            if re.search(r" (NOT )?NULL\b", line):
                line = re.sub(r"( NOT NULL| NULL)\b", rf"\1 DEFAULT {value}", line, count=1)
            else:   # 原来写的是 DEFAULT NULL：去掉之后没有可挂的位置，挂在类型后面
                line = re.sub(rf"^(\s*`{col}` \S+)", rf"\1 DEFAULT {value}", line, count=1)
            if f"DEFAULT {value}" not in line:
                raise SystemExit(f"V2：{table}.{col} 的默认值没挂上：{line}")
            lines[idx[0]] = line
            continue
        raise SystemExit(f"V2 里有不认识的语句，生成器要先支持它：{stmt}")


# 测试库把换过的平台表里 varchar(32) 的业务编号列（*_no）放宽到 64：测试数据惯用 36 位 UUID 或「前缀 + UUID」当编号，
# 而生产编号都由 BizKey 生成，最长 20 位（前缀 ≤4 + 日期 6 + 随机 10），远不到 32。BizKeyLengthTest 守着这一条。
WIDEN_NO_TO = 64


def h2_line(table, line):
    if re.match(r"\s*KEY `", line) or re.match(r"\s*CONSTRAINT `\w+` CHECK ", line):
        return None                                     # 普通索引不建；CHECK 留给真 MySQL 的测试
    unique = re.match(r"\s*UNIQUE KEY `(\w+)` (\(.*\))", line)
    if unique:                                          # H2 的约束名全库唯一，加表名前缀
        return f"  CONSTRAINT {table}__{unique.group(1)} UNIQUE {unique.group(2).replace('`', '')}"
    line = re.sub(r" COMMENT '(?:[^']|'')*'", "", line)
    line = re.sub(r" COLLATE \w+", "", line)
    line = re.sub(r" ON UPDATE CURRENT_TIMESTAMP(\(\d\))?", "", line)
    line = line.replace(" unsigned", "")
    line = re.sub(r"\bjson\b", "TEXT", line)
    line = re.sub(r"\bpolygon\b", "GEOMETRY", line)   # 空间列：代码不读写（可达靠网格），H2 用通用几何类型           # 与主库测试脚本同一取法
    line = re.sub(r"^(\s*`\w+_no`) varchar\(32\)", rf"\1 varchar({WIDEN_NO_TO})", line)
    return line.replace("`", "")


def render():
    design = design_tables()
    missing = [t for t in TABLES if t not in design]
    if missing:
        raise SystemExit(f"新库设计里没有这些表：{missing}")
    tables = {t: list(design[t]) for t in TABLES}
    apply_supplement(tables)
    out = ["-- 【自动生成，勿手改】由 backend/scripts/gen-platform-test-schema.py 生成。",
           "-- 已换到新结构的平台表按新库设计（db/n_platform V1 + V2）建，在 schema-test.sql 之前执行（同名旧表随之跳过）。",
           "-- 过渡列（新设计已去掉、平台代码还在用，换完对应那一项即删）：" +
           ("；".join(f"{t}.{c}" for t, cs in TRANSITIONAL.items() for c in cs) or "无"),
           ""]
    old = old_columns()
    for name, lines in tables.items():
        body = [l for l in (h2_line(name, x) for x in lines) if l and l.strip()]
        new_cols = {re.match(r"\s*(\w+)", l).group(1).lower() for l in body
                    if re.match(r"\s*\w+\s+[A-Za-z]", l) and not l.strip().upper().startswith(("PRIMARY", "CONSTRAINT"))}
        for col in TRANSITIONAL.get(name, []):
            if col in new_cols:
                raise SystemExit(f"{name}.{col} 已在新设计里，从 TRANSITIONAL 去掉")
            if col not in old.get(name, {}):
                raise SystemExit(f"{name}.{col} 在旧测试库里也没有，TRANSITIONAL 写错了")
            keys = [i for i, l in enumerate(body) if l.strip().upper().startswith(("PRIMARY", "CONSTRAINT"))]
            line = old[name][col]
            if (name, col) in NULLABLE:   # 新代码不再写这一列：旧定义的 NOT NULL 去掉
                line = re.sub(r"\s+NOT NULL", "", line)
            body.insert(keys[0] if keys else len(body), "  " + line)
        out.append(f"CREATE TABLE IF NOT EXISTS {name} (")
        out.append(",\n".join(body))
        out.append(");")
        out.append("")
    out.extend(TRIGGERS)
    out.append("")
    return "\n".join(out)


def render_after():
    return "\n".join(["-- 【自动生成，勿手改】由 backend/scripts/gen-platform-test-schema.py 生成。",
                      "-- 挂在旧表上的过渡触发器（旧测试还按旧表写，同步到新结构），在 schema-test.sql 之后执行。", ""]
                     + TRIGGERS_AFTER + [""])


def main():
    outputs = {OUT: render(), OUT_AFTER: render_after()}
    if "--check" in sys.argv:
        stale = [p for p, text in outputs.items() if not p.exists() or p.read_text(encoding="utf-8") != text]
        for p in stale:
            print(f"✗ {p.relative_to(ROOT)} 不是最新：跑一次 python3 backend/scripts/gen-platform-test-schema.py")
        if stale:
            sys.exit(1)
        print("✓ 平台测试建表脚本是最新的")
        return
    for p, text in outputs.items():
        p.write_text(text, encoding="utf-8")
        print(f"{p.relative_to(ROOT)}: {text.count('CREATE TABLE')} 张表，{text.count('CREATE TRIGGER')} 个触发器")


if __name__ == "__main__":
    main()
