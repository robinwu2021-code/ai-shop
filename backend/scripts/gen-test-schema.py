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
    seeds = []    # INSERT 种子数据，原样保留

    # **按版本号数字排序**，不是字典序。字典序把 V15 排在 V2 前面，
    # 于是 V15 的 ALTER 在建表之前重放 —— alter_table() 里 tables.get() 拿到 None
    # 就直接 return，**不报错、不提示**，产出一份缺列的 schema。
    # 缺的列只有等某个测试恰好用到它才会暴露，而多数测试用不到。
    for f in sorted(MIGRATION_DIR.glob("V*.sql"),
                    key=lambda p: int(re.match(r"V(\d+)", p.name).group(1))):
        replay(f.read_text(), tables, order, seeds)

    out = [HEADER]
    for name in order:
        out.append(f"CREATE TABLE IF NOT EXISTS {name}\n(\n"
                   + ",\n".join(tables[name]) + "\n);\n")
    if seeds:
        out.append("-- 种子数据\n" + "\n".join(seeds) + "\n")
    OUT.write_text("\n".join(out))
    print(f"wrote {OUT.relative_to(ROOT)}: {len(order)} tables, {len(seeds)} seeds")
    return 0


def replay(sql, tables, order, seeds):
    # 逐语句切分（本项目的迁移脚本里没有存储过程，分号切分是安全的）
    for stmt in [s.strip() for s in sql.split(";") if s.strip()]:
        stmt = re.sub(r"--[^\n]*", "", stmt).strip()
        if not stmt:
            continue
        low = stmt.lower()
        if low.startswith("create table"):
            create_table(stmt, tables, order)
        elif low.startswith("alter table"):
            alter_table(stmt, tables, order)
        elif low.startswith("create unique index"):
            create_unique_index(stmt, tables)
        elif low.startswith("drop table"):
            drop_table(stmt, tables, order)
        elif low.startswith("update "):
            # 数据修正语句：作用于生产存量数据，H2 测试库是空的，重放没有意义
            pass
        elif low.startswith("insert into"):
            # 用正则而不是 `" select " in low`：回填语句里 SELECT 常常另起一行，
            # 而 low 是原样文本 —— 子串判断会漏掉带换行的写法，然后把回填当种子抄进测试库
            if re.search(r"\bselect\b", low):
                # **INSERT ... SELECT 是数据回填，不是种子。**
                #
                # 回填读的是**中间态的表结构** —— V42 的回填从 usr_merchant.address
                # 取值，而同一个迁移随后就把那一列删了。重放到最终 schema 上必然报
                # 「列不存在」，而报错指向的是一个毫不相干的 Controller（上下文起不来）。
                #
                # 何况它的目的是搬运存量数据，而 H2 测试库本来就是空的 —— 搬无可搬。
                pass
            else:
                # 种子数据：H2 建表脚本里保留，测试要用到（如端×品类可售规则的 25 行）
                seeds.append(stmt + ";")
        elif low.startswith("create index") or low.startswith("drop index"):
            # 普通索引 H2 测试用不上；DROP INDEX 同理（约束由 CREATE UNIQUE INDEX 重建）
            pass
        else:
            # **不认识的语句必须炸，不能静默跳过。**
            # 这个脚本此前只认 CREATE TABLE 与部分 ALTER，DROP TABLE / DROP COLUMN
            # 全被丢弃 —— 产出的 schema 看着正常，实际多了一张已删的表、
            # 少删了两列。缺的东西只有等某个测试恰好用到才会暴露。
            raise SystemExit(f"✗ 不认识的语句，请在本脚本里实现：\n  {stmt[:120]}")


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


def alter_table(stmt, tables, order):
    m = re.match(r"ALTER TABLE\s+(\w+)\s+(.*)", stmt, re.S | re.I)
    if not m:
        return
    table, action = m.group(1), m.group(2).strip()

    # 表改名：整表挪到新名下。不认它的话，后续针对新表名的 ALTER 全部报
    # 「目标表还不存在」，而真正的原因在几十行之前
    ren_tbl = re.match(r"RENAME TO\s+(\w+)", action, re.I)
    if ren_tbl:
        new_name = ren_tbl.group(1)
        if table not in tables:
            raise SystemExit(f"✗ RENAME TO：源表 {table} 不存在\n  {stmt[:80]}")
        tables[new_name] = tables.pop(table)
        order[order.index(table)] = new_name
        return

    cols = tables.get(table)
    if cols is None:
        # 静默 return 是这个脚本此前最坏的行为：迁移顺序一错，整批 ALTER 全被丢掉，
        # 而产出的 schema 看上去完全正常。宁可炸。
        raise SystemExit(f"✗ ALTER 的目标表 {table} 还不存在 —— 迁移重放顺序错了？\n  {stmt[:80]}")

    rename = re.match(r"RENAME COLUMN\s+(\w+)\s+TO\s+(\w+)", action, re.I)
    if rename:
        old, new = rename.group(1), rename.group(2)
        hit = False
        for i, c in enumerate(cols):
            if re.match(rf"^\s*{old}\s", c):
                cols[i] = re.sub(rf"^(\s*){old}(\s)", rf"\g<1>{new}\g<2>", c)
                hit = True
                continue
            # 约束里的列引用也要改名。漏掉这一步的后果不是"少改一处"，而是产出一份
            # **建不起来的 schema**：UNIQUE (merchant_no, channel) 指向一个已经不存在的列，
            # H2 在建表那一刻就报错，整个 Spring 上下文起不来 —— 而错误信息指向的是
            # 一个毫不相干的 Controller，很难看出根因在生成器上。
            if re.match(r"^\s*(UNIQUE|KEY|CONSTRAINT|PRIMARY|INDEX)\b", c, re.I):
                cols[i] = re.sub(rf"(?<![\w]){old}(?![\w])", new, c)
        if not hit:
            raise SystemExit(f"✗ RENAME COLUMN {table}.{old}：这一列本来就不存在")
        return

    # MODIFY COLUMN：改类型与可空性。**不重放的话测试库停在建表当天的定义** ——
    # V46 把 user_no 从 NOT NULL 改成可空，而测试库仍是 NOT NULL，
    # 于是「员工不必有 C 端账号」这条在真库成立、在测试里插不进去。
    # 两边结构分叉是最难查的一类差异：代码没错，只有测试环境炸。
    mod = re.match(r"MODIFY COLUMN\s+(\w+)\s+(.+)", action, re.I | re.S)
    if mod:
        col, rest = mod.group(1), mod.group(2).strip()
        # 只取类型与 NULL/NOT NULL / DEFAULT，丢掉 COMMENT（H2 不需要）
        rest = re.sub(r"\s*COMMENT\s+'(?:[^']|'')*'", "", rest, flags=re.I).strip().rstrip(";")
        for i, c in enumerate(cols):
            if re.match(rf"^\s*{col}\s", c):
                cols[i] = f"    {col} {rest}"
                break
        else:
            raise SystemExit(f"✗ MODIFY COLUMN {table}.{col}：这一列本来就不存在")
        return

    drop = re.match(r"DROP COLUMN\s+(\w+)", action, re.I)
    if drop:
        col = drop.group(1)
        before = len(cols)
        cols[:] = [c for c in cols if not re.match(rf"^\s*{col}\s", c)]
        if len(cols) == before:
            raise SystemExit(f"✗ DROP COLUMN {table}.{col}：这一列本来就不存在")
        return

    add = re.match(r"ADD COLUMN\s+(.*)", action, re.I | re.S)
    if add:
        col = re.sub(r"\s+COMMENT\s+'[^']*'", "", add.group(1).strip())
        # 插在最后一个业务列之后（约束行都在末尾）
        idx = next((i for i, c in enumerate(cols)
                    if re.match(r"^\s*CONSTRAINT\s", c, re.I)), len(cols))
        cols.insert(idx, "    " + col)


def drop_table(stmt, tables, order):
    m = re.match(r"DROP TABLE(?: IF EXISTS)?\s+(\w+)", stmt, re.I)
    if not m:
        return
    name = m.group(1)
    tables.pop(name, None)
    if name in order:
        order.remove(name)


def create_unique_index(stmt, tables):
    m = re.match(r"CREATE UNIQUE INDEX\s+(\w+)\s+ON\s+(\w+)\s*\((.*)\)", stmt, re.I | re.S)
    if not m:
        return
    name, table, cols_expr = m.group(1), m.group(2), m.group(3)
    if table in tables:
        tables[table].append(f"    CONSTRAINT {name} UNIQUE ({cols_expr.strip()})")


if __name__ == "__main__":
    sys.exit(main())
