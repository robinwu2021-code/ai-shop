#!/usr/bin/env python3
"""把平台服务库里发票用到的表转成 H2（MODE=MySQL）能反复执行的建表脚本。

来源：invoice-domain 测试资源里的 db/n_service/V1（设计的建表语句）与 V2（补充脚本）。
产物：shop-app/src/test/resources/schema-service-test.sql，由 h2db profile 里发票数据源的
JDBC 地址用 INIT=RUNSCRIPT 加载。H2 内存库会跨 Spring 上下文存活、脚本会被每个新连接重放，
所以产物必须幂等：一律 CREATE TABLE IF NOT EXISTS，V2 的 ALTER 在这里先合进表定义。

用法：python3 backend/scripts/gen-service-test-schema.py          # 生成
      python3 backend/scripts/gen-service-test-schema.py --check  # 只比对，不一致返回 1
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "backend/invoice/invoice-domain/src/test/resources/db/n_service"
OUT = ROOT / "backend/shop-app/src/test/resources/schema-service-test.sql"

TABLE = re.compile(r"CREATE TABLE `(\w+)` \((.*?)\n\) COMMENT=[^\n]*;", re.S)


def tables(sql):
    return {m.group(1): [line.rstrip(",") for line in m.group(2).strip("\n").split("\n")] for m in TABLE.finditer(sql)}


def apply_supplement(defs, sql):
    for name, lines in tables(sql).items():
        defs[name] = lines
    for m in re.finditer(r"ALTER TABLE `(\w+)` (.*?);", sql, re.S):
        table, action = m.group(1), m.group(2).strip()
        lines = defs[table]
        drop = re.match(r"DROP INDEX `(\w+)`$", action)
        add_key = re.match(r"ADD (UNIQUE KEY `\w+` \(.*\))$", action)
        add_col = re.match(r"ADD COLUMN (`(\w+)` .*?) AFTER `(\w+)`$", action)
        if drop:
            defs[table] = [l for l in lines if not re.match(r"\s*(UNIQUE )?KEY `%s` " % drop.group(1), l)]
        elif add_key:
            lines.append("  " + add_key.group(1))
        elif add_col:
            at = next(i for i, l in enumerate(lines) if l.strip().startswith("`%s` " % add_col.group(3)))
            lines.insert(at + 1, "  " + add_col.group(1))
        else:
            raise SystemExit(f"补充脚本里有本生成器不认识的写法：ALTER TABLE {table} {action}")
    return defs


def h2_line(line):
    line = re.sub(r" COMMENT '(?:[^']|'')*'", "", line)
    line = line.replace(" unsigned", "")
    line = re.sub(r"\bjson\b", "TEXT", line)   # H2 的 JSON 列不接受带引号的字符串写入；与主库测试脚本同一取法
    line = re.sub(r"^(\s*)CONSTRAINT `\w+` CHECK .*$", "", line)   # CHECK 留给真 MySQL 的测试
    return line


def render(defs):
    out = ["-- 【自动生成，勿手改】由 backend/scripts/gen-service-test-schema.py 生成。",
           "-- 平台服务库里发票用到的表，H2（MODE=MySQL）版；每个新连接都会重放，必须幂等。", ""]
    for name, lines in defs.items():
        body = [h2_line(l) for l in lines]
        out.append(f"CREATE TABLE IF NOT EXISTS `{name}` (")
        out.append(",\n".join(l for l in body if l.strip()))
        out.append(");\n")
    return "\n".join(out)


def main():
    defs = tables((SRC / "V1__ivc_design.sql").read_text())
    defs = apply_supplement(defs, (SRC / "V2__ivc_supplement.sql").read_text())
    text = render(defs)
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text() != text:
            print(f"✗ {OUT.relative_to(ROOT)} 与 db/n_service 对不上：重跑 backend/scripts/gen-service-test-schema.py")
            return 1
        print(f"✓ {OUT.relative_to(ROOT)} 是最新的（{len(defs)} 张表）")
        return 0
    OUT.write_text(text)
    print(f"wrote {OUT.relative_to(ROOT)}: {len(defs)} tables")
    return 0


if __name__ == "__main__":
    sys.exit(main())
