#!/usr/bin/env python3
"""由 Flyway 迁移生成 H2 测试用 schema。

生产走 `db/migration/V*.sql`（MySQL 方言），单测走 H2（快、无外部依赖）。
两份 DDL 手工维护必然漂移，所以这里**按顺序重放迁移**再输出一份合并后的建表脚本：

  CREATE TABLE      → 收进表定义
  ALTER ... RENAME  → 改列名
  ALTER ... ADD     → 加列
  CREATE INDEX      → 丢弃（H2 建表内联不需要；唯一索引除外，转成 CONSTRAINT）

之前这个脚本只认 CREATE TABLE，V6 引入 ALTER 之后就会静默漏掉改名与新列 ——
测试库和生产库结构不一致，而 SchemaDriftTest 又只比对列集合，可能刚好放过。
所以重放是必须的，不是优化。

用法：python3 backend/scripts/gen-test-schema.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION_DIR = ROOT / "backend/shop-app/src/main/resources/db/migration"
OUT = ROOT / "backend/shop-app/src/test/resources/schema-test.sql"

HEADER = """-- 【自动生成，勿手改】由 backend/scripts/gen-test-schema.py 重放 db/migration/V*.sql 得到。
-- 生产是 MySQL 方言；这份是 H2 等价物（去列注释与普通索引，UNIQUE 转 CONSTRAINT）。
-- 与源文件的漂移由 SchemaDriftTest 拦截。

"""


def main():
    tables = {}   # name -> list[str] 列/约束定义，保持顺序
    order = []

    # **按版本号数字排序**，不是字典序。字典序把 V15 排在 V2 前面，
    # 于是 V15 的 ALTER 在建表之前重放 —— alter_table() 里 tables.get() 拿到 None
    # 就直接 return，**不报错、不提示**，产出一份缺列的 schema。
    # 缺的列只有等某个测试恰好用到它才会暴露，而多数测试用不到。
    for f in sorted(MIGRATION_DIR.glob("V*.sql"),
                    key=lambda p: int(re.match(r"V(\d+)", p.name).group(1))):
        replay(f.read_text(), tables, order)

    out = [HEADER]
    for name in order:
        out.append(f"CREATE TABLE IF NOT EXISTS {name}\n(\n"
                   + ",\n".join(tables[name]) + "\n);\n")
    OUT.write_text("\n".join(out))
    print(f"wrote {OUT.relative_to(ROOT)}: {len(order)} tables")
    return 0


def replay(sql, tables, order):
    # 逐语句切分（本项目的迁移脚本里没有存储过程，分号切分是安全的）
    for stmt in [s.strip() for s in sql.split(";") if s.strip()]:
        stmt = re.sub(r"--[^\n]*", "", stmt).strip()
        if not stmt:
            continue
        low = stmt.lower()
        if low.startswith("create table"):
            create_table(stmt, tables, order)
        elif low.startswith("alter table"):
            alter_table(stmt, tables)
        elif low.startswith("create unique index"):
            create_unique_index(stmt, tables)
        # 普通 CREATE INDEX：H2 测试不需要，丢弃


def create_table(stmt, tables, order):
    m = re.match(r"CREATE TABLE(?: IF NOT EXISTS)?\s+(\w+)\s*\((.*)\)", stmt, re.S | re.I)
    if not m:
        return
    name, body = m.group(1), m.group(2)
    cols = []
    # 先合并续行：列定义可能换行写（如 COMMENT 单独一行），逐行处理会把它当成独立列
    merged = []
    for raw in body.splitlines():
        line = raw.strip()
        if not line or line.startswith("--"):
            continue
        if merged and (line.upper().startswith("COMMENT") or not merged[-1].endswith(",")):
            merged[-1] = merged[-1].rstrip(",") + " " + line
        else:
            merged.append(line)

    for line in merged:
        line = re.sub(r"\s+COMMENT\s+'[^']*'", "", line.rstrip(",")).strip()
        if not line:
            continue
        if re.match(r"^(KEY|INDEX)\s", line, re.I):
            continue
        u = re.match(r"^UNIQUE KEY\s+(\w+)\s*\((.*)\)$", line, re.I)
        if u:
            line = f"CONSTRAINT {u.group(1)} UNIQUE ({u.group(2)})"
        cols.append("    " + line)
    # ENGINE=... 尾巴会粘在最后一行上，切掉
    if cols:
        cols[-1] = re.sub(r"\)?\s*ENGINE\s*=.*$", "", cols[-1], flags=re.I).rstrip()
    tables[name] = cols
    if name not in order:
        order.append(name)


def alter_table(stmt, tables):
    m = re.match(r"ALTER TABLE\s+(\w+)\s+(.*)", stmt, re.S | re.I)
    if not m:
        return
    table, action = m.group(1), m.group(2).strip()
    cols = tables.get(table)
    if cols is None:
        # 静默 return 是这个脚本此前最坏的行为：迁移顺序一错，整批 ALTER 全被丢掉，
        # 而产出的 schema 看上去完全正常。宁可炸。
        raise SystemExit(f"✗ ALTER 的目标表 {table} 还不存在 —— 迁移重放顺序错了？\n  {stmt[:80]}")

    rename = re.match(r"RENAME COLUMN\s+(\w+)\s+TO\s+(\w+)", action, re.I)
    if rename:
        old, new = rename.group(1), rename.group(2)
        for i, c in enumerate(cols):
            if re.match(rf"^\s*{old}\s", c):
                cols[i] = re.sub(rf"^(\s*){old}(\s)", rf"\g<1>{new}\g<2>", c)
        return

    add = re.match(r"ADD COLUMN\s+(.*)", action, re.I | re.S)
    if add:
        col = re.sub(r"\s+COMMENT\s+'[^']*'", "", add.group(1).strip())
        # 插在最后一个业务列之后（约束行都在末尾）
        idx = next((i for i, c in enumerate(cols)
                    if re.match(r"^\s*CONSTRAINT\s", c, re.I)), len(cols))
        cols.insert(idx, "    " + col)


def create_unique_index(stmt, tables):
    m = re.match(r"CREATE UNIQUE INDEX\s+(\w+)\s+ON\s+(\w+)\s*\((.*)\)", stmt, re.I | re.S)
    if not m:
        return
    name, table, cols_expr = m.group(1), m.group(2), m.group(3)
    if table in tables:
        tables[table].append(f"    CONSTRAINT {name} UNIQUE ({cols_expr.strip()})")


if __name__ == "__main__":
    sys.exit(main())
