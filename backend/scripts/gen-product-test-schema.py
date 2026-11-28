#!/usr/bin/env python3
"""商品领域的 H2 测试建表脚本：按新库设计建商品与评价、收藏的表（TDD-商品领域迁到门店交易库 §2.6 第 2 步）。

来源：docs/technical/design/schema/ddl/05-n_trade.sql 里全部 prd_* 表，07-n_member.sql 里的评价与收藏表。
产物：backend/shop-app/src/test/resources/schema-product-test.sql，h2db profile 在 schema-test.sql **之前**执行，
同一个内存库（shop）：商品与交易在测试里仍是一个库、一个事务，和部署时两个句柄绑同一个数据源名一致。

为什么在前面、为什么不 DROP：H2 内存库在同一个 JVM 里跨 Spring 上下文存活，建表脚本每起一个上下文重放一次。
先按新定义 CREATE IF NOT EXISTS，schema-test.sql 里同名的旧建表语句就自然跳过；若改成后执行再 DROP 重建，
第二个上下文会把第一个上下文造好的演示数据删掉（DevSeeder 看到别的表已有数据不再重造），一片 10404。

分批迁移期间的过渡规则（迁完一批收紧一批）：
  - 新设计里的表一律按新定义建；与旧测试库同名的，因为先执行，旧定义被跳过。
  - 同名表里旧定义多出来、商品代码还在用的列（TRANSITIONAL）先临时补上，文件头逐条列出；代码改完就从这里删。
  - 只在旧库有、新设计没有的商品表（改名或并入前的旧表）不在这里动，仍由 schema-test.sql 建；
    商品代码不再用它们时，ProductDatabaseIsolationTest 的 R2 基线会清零，届时再从测试库去掉。

漂移测试（SchemaDriftTest、SchemaParityTest）比对的是 schema-test.sql 与迁移，不读这份，所以不受影响。

用法：python3 backend/scripts/gen-product-test-schema.py          # 生成
      python3 backend/scripts/gen-product-test-schema.py --check  # 只比对，不一致返回 1
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
TRADE_DDL = ROOT / "docs/technical/design/schema/ddl/05-n_trade.sql"
MEMBER_DDL = ROOT / "docs/technical/design/schema/ddl/07-n_member.sql"
OLD = ROOT / "backend/shop-app/src/test/resources/schema-test.sql"
OUT = ROOT / "backend/shop-app/src/test/resources/schema-product-test.sql"
MEMBER_TABLES = ["rvw_review", "rvw_review_like", "rvw_appeal", "mbr_favorite"]

# 过渡列：同名表里新设计去掉了、商品代码还在用的列。表 -> 列（迁完对应批次就删掉这一项）
TRANSITIONAL = {
}

TABLE = re.compile(r"CREATE TABLE `(\w+)` \((.*?)\n\)[^;]*;", re.S)
OLD_TABLE = re.compile(r"CREATE TABLE IF NOT EXISTS (\w+)\s*\((.*?)\n\);", re.S)


def design_tables(path, names=None):
    out = {}
    for m in TABLE.finditer(path.read_text(encoding="utf-8")):
        name = m.group(1)
        if (names is None and name.startswith("prd_")) or (names is not None and name in names):
            out[name] = [l.rstrip(",") for l in m.group(2).strip("\n").split("\n")]
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
    line = re.sub(r"\bjson\b", "TEXT", line)           # H2 的 JSON 列不接受带引号的字符串写入；与主库测试脚本同一取法
    return line.replace("`", "")


def render():
    tables = design_tables(TRADE_DDL)
    tables.update(design_tables(MEMBER_DDL, set(MEMBER_TABLES)))
    old = old_columns()
    out = ["-- 【自动生成，勿手改】由 backend/scripts/gen-product-test-schema.py 生成。",
           "-- 商品领域的表按新库设计（05-n_trade.sql、07-n_member.sql）建，在 schema-test.sql 之前执行（同名旧表随之跳过）。",
           "-- 过渡列（新设计已去掉、商品代码还在用，迁完对应批次即删）：" +
           ("；".join(f"{t}.{c}" for t, cs in TRANSITIONAL.items() for c in cs) or "无"), ""]
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
            body.insert(keys[0] if keys else len(body), "  " + old[name][col])
        out.append(f"CREATE TABLE IF NOT EXISTS {name} (")
        out.append(",\n".join(body))
        out.append(");")
        out.append("")
    return "\n".join(out)


def main():
    text = render()
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"✗ {OUT.relative_to(ROOT)} 不是最新：跑一次 python3 backend/scripts/gen-product-test-schema.py")
            sys.exit(1)
        print(f"✓ {OUT.relative_to(ROOT)} 是最新的")
        return
    OUT.write_text(text, encoding="utf-8")
    print(f"{OUT.relative_to(ROOT)}: {text.count('CREATE TABLE')} 张表")


if __name__ == "__main__":
    main()
