#!/usr/bin/env python3
"""美业服务定义搬到商品域（TDD-美业域迁移新库 §2.8 P6）：旧库 bty_service_definition* / bty_service_requirement
→ 门店交易库 prd_service_definition / prd_fulfillment_requirement，并把各处引用的旧版本号（BTDV…）换成新版本号。

用法：
    python3 scripts/migrate-service-definitions.py --src ai_shop_ume --trade n_trade_v2 \\
        --mysql "mysql -uroot -h127.0.0.1" --plan                 # 只读：打印要写的行与要改的引用
    python3 scripts/migrate-service-definitions.py ... --rewrite ai_shop_ume   # 执行（一个事务）

规则：
  - 旧定义按门店 + SKU 一个头、每次发布一个版本；新表按商品一组行、版本按商品自增。同一商品下各 SKU 的旧版本按发布
    时间排成一条序列，每个旧版本对应新的一版：第一个发布的 SKU 的内容是默认行（sku_no=''），其余 SKU 内容与默认不同
    的写覆盖行（时长、缓冲、步骤、注意事项、要求都完整写上）。
  - 要求编号沿用旧的（BTR…）：预约分派、任务冻结里记的就是它。同一编号会出现在带过来的各版里（revision_no 为空，
    唯一键不冲突）。
  - 新行的 source_ref 记 'MIGRATED:<旧版本号>'，published_by / published_at 取旧版本的创建人与创建时间（--source-tz
    给出旧时间所在时区，默认 UTC，正式库与应用都在 UTC）。
  - 默认行的时长镜像到 prd_goods.duration_min（定向更新，不动 version）。
  - 引用改写：交易库与 --rewrite 给出的库里，表名以 ord_appointment 或 bty_ 开头的表（旧定义三张与迁移历史除外）：列名 definition_version_no 按
    等值改；其余文本 / JSON 列把 "旧版本号"（带引号）替换成 "新版本号"。旧版本号是随机编码，带引号整体替换不会误伤。
  - 一个事务；检查不过（目标已有这些商品的定义、SKU 找不到商品、改写后仍有旧版本号）整批回滚。不要用 --force。

--mysql 是能连到目标实例的 mysql 批处理命令（各库须在同一实例）；.61 上用 root，经跳板机时把整条 ssh … mysql 写进去，
并在 mysql 后面写 {args}（查询要追加的参数放在那里，否则会落到 ssh 的命令行末尾）。
"""
import argparse
import json
import re
import subprocess
import sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone

NAME = re.compile(r'^[A-Za-z0-9_]+$')
# 不改写的表：迁移历史，以及旧定义三张本身（它们是搬迁的来源，原样留着，随美业私有 Flyway 一起退役）
SKIPPED = {'bty_flyway_history', 'bty_service_definition', 'bty_service_definition_version', 'bty_service_requirement'}
TZ = re.compile(r'^[+-]\d{2}:\d{2}$|^UTC$')


def query(cmd, sql):
    """执行只读查询，返回行（各列为字符串，NULL 为 None）。"""
    flags = '--batch --raw -N'
    line = cmd.replace('{args}', flags) if '{args}' in cmd else cmd + ' ' + flags
    proc = subprocess.run(['bash', '-c', line], input=sql, text=True, capture_output=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit('查询失败')
    rows = []
    for line in proc.stdout.splitlines():
        if not line or line.startswith('**') or 'post-quantum' in line or 'store now' in line \
                or 'openssh.com' in line:
            continue
        rows.append([None if v == 'NULL' else v for v in line.split('\t')])
    return rows


def run(cmd, sql):
    proc = subprocess.run(['bash', '-c', cmd.replace('{args}', '')], input=sql, text=True, capture_output=True)
    sys.stdout.write(proc.stdout)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit('mysql 返回 %d：整批已回滚（事务内）' % proc.returncode)


def q(value):
    if value is None:
        return 'NULL'
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace('\\', '\\\\').replace("'", "''") + "'"


def utc(text, offset):
    moment = datetime.strptime(text[:23], '%Y-%m-%d %H:%M:%S.%f' if '.' in text else '%Y-%m-%d %H:%M:%S')
    if offset != 'UTC':
        sign = 1 if offset[0] == '+' else -1
        hours, minutes = int(offset[1:3]), int(offset[4:6])
        moment = moment - sign * timedelta(hours=hours, minutes=minutes)
    return moment.strftime('%Y-%m-%d %H:%M:%S.%f')[:23]


def load(args):
    src, trade = args.src, args.trade
    heads = {r[0]: r for r in query(args.mysql, f"""
        SELECT definition_no, entity_no, store_no, sku_no, status FROM {src}.bty_service_definition
        WHERE deleted = 0""")}
    versions = query(args.mysql, f"""
        SELECT definition_version_no, definition_no, revision_index, duration_min_snapshot, buffer_before_min,
               buffer_after_min, CAST(steps_template AS CHAR), CAST(notes AS CHAR), schema_version, snapshot_hash,
               DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f'), created_by
        FROM {src}.bty_service_definition_version WHERE deleted = 0 ORDER BY created_at, revision_index, id""")
    requirements = defaultdict(list)
    for r in query(args.mysql, f"""
            SELECT definition_version_no, requirement_no, role_code, resource_type, skill_goods_no, qty, sequence_no,
                   offset_min, duration_min, selection_rule
            FROM {src}.bty_service_requirement WHERE deleted = 0 ORDER BY sequence_no, requirement_no"""):
        requirements[r[0]].append(r[1:])
    goods_of = {}
    for sku, entity, goods in query(args.mysql, f"""
            SELECT DISTINCT sku_no, entity_no, goods_no FROM {trade}.prd_sku WHERE deleted = 0"""):
        goods_of[(entity, sku)] = goods
    return heads, versions, requirements, goods_of


def compose(heads, versions, requirements, goods_of, offset):
    """按商品重放旧版本：返回 (要写的定义行, 要求行, 旧版本号 → 新版本号, 找不到商品的 SKU, 默认时长)。"""
    by_goods = defaultdict(list)
    missing = set()
    for v in versions:
        head = heads.get(v[1])
        if head is None:
            missing.add('（无头）' + v[0])
            continue
        goods = goods_of.get((head[1], head[3]))
        if goods is None:
            missing.add(head[3])
            continue
        by_goods[(head[1], head[2], goods)].append((head, v))
    rows, reqs, mapping, durations = [], [], {}, {}
    for (entity, store, goods), events in sorted(by_goods.items()):
        state = {}
        default_sku = events[0][0][3]
        for number, (head, v) in enumerate(events, start=1):
            state[head[3]] = (head, v)
            mapping[v[0]] = (goods, number)
            base_head, base = state[default_sku]

            def content(version):
                return tuple(version[3:8]) + (tuple(tuple(r[1:]) for r in requirements.get(version[0], [])),)

            def emit(sku, row_head, row_version):
                rows.append(dict(entity=entity, store=store, goods=goods, sku=sku, version=number,
                                 duration=int(row_version[3]), before=int(row_version[4]),
                                 after=int(row_version[5]), steps=row_version[6], notes=row_version[7],
                                 schema=int(row_version[8]), hash=row_version[9], status=row_head[4],
                                 ref='MIGRATED:' + v[0], by=v[11], at=utc(v[10], offset)))
                for r in requirements.get(row_version[0], []):
                    reqs.append(dict(entity=entity, store=store, goods=goods, sku=sku or None, version=number,
                                     no=r[0], role=r[1], resource=r[2], skill=r[3], qty=int(r[4]),
                                     seq=int(r[5]), offset=int(r[6]), duration=int(r[7]), rule=r[8]))

            emit('', base_head, base)
            for sku, (row_head, row_version) in sorted(state.items()):
                if sku != default_sku and content(row_version) != content(base):
                    emit(sku, row_head, row_version)
            durations[(entity, goods)] = int(base[3])
    return rows, reqs, mapping, missing, durations


def references(args, schemas, mapping):
    """要改写的引用：(库, 表, 列, 方式, 命中行数)。"""
    if not mapping:
        return []
    found = []
    tokens = list(mapping)
    for schema in schemas:
        columns = query(args.mysql, f"""
            SELECT table_name, column_name, data_type FROM information_schema.columns
            WHERE table_schema = {q(schema)} AND (table_name LIKE 'ord\\_appointment%' OR table_name LIKE 'bty\\_%')
              AND data_type IN ('json', 'varchar', 'text', 'mediumtext', 'longtext')
            ORDER BY table_name, ordinal_position""")
        for table, column, kind in columns:
            if table in SKIPPED:
                continue
            exact = column == 'definition_version_no'
            cond = ' OR '.join(
                (f'`{column}` = {q(t)}' if exact else f'CAST(`{column}` AS CHAR) LIKE {q("%" + t + "%")}')
                for t in tokens)
            hits = query(args.mysql, f'SELECT COUNT(*) FROM `{schema}`.`{table}` WHERE {cond}')[0][0]
            if int(hits) > 0:
                found.append((schema, table, column, 'exact' if exact else 'text', int(hits)))
    return found


def migration_sql(args, rows, reqs, mapping, refs, durations):
    t = args.trade
    out = ["-- 美业服务定义搬到商品域（TDD-美业域迁移新库 §2.8 P6）。一个事务；检查不过整批回滚。",
           f"USE `{t}`;",
           "CREATE TEMPORARY TABLE svc_migrate_guard (item VARCHAR(64) NOT NULL, n BIGINT NOT NULL, "
           "CONSTRAINT svc_migrate_guard_zero CHECK (n = 0));",
           "START TRANSACTION;"]
    goods = sorted({r['goods'] for r in rows})
    if goods:
        out.append("INSERT INTO svc_migrate_guard SELECT 'target_has_definitions', COUNT(*) FROM "
                   f"{t}.prd_service_definition WHERE goods_no IN ({', '.join(q(g) for g in goods)});")
    for r in rows:
        out.append(
            f"INSERT INTO {t}.prd_service_definition (tenant_no, entity_no, store_no, goods_no, sku_no, "
            "definition_version, duration_min, buffer_before_min, buffer_after_min, steps, notes, schema_version, "
            "snapshot_hash, status, source_ref, published_by, published_at, created_by, updated_by) VALUES ('MAIN', "
            + ', '.join(q(x) for x in (r['entity'], r['store'], r['goods'], r['sku'], r['version'], r['duration'],
                                       r['before'], r['after'], r['steps'], r['notes'], r['schema'], r['hash'],
                                       r['status'], r['ref'], r['by'], r['at'], 'MIGRATION', 'MIGRATION')) + ');')
    for r in reqs:
        out.append(
            f"INSERT INTO {t}.prd_fulfillment_requirement (tenant_no, entity_no, requirement_no, goods_no, sku_no, "
            "store_no, definition_version, requirement_type, role_code, resource_type, skill_goods_no, qty, sequence_no, "
            "offset_min, duration_min, selection_rule, created_by, updated_by) VALUES ('MAIN', "
            + ', '.join(q(x) for x in (r['entity'], r['no'], r['goods'], r['sku'], r['store'], r['version'],
                                       'ROLE' if r['role'] else 'RESOURCE', r['role'], r['resource'], r['skill'],
                                       r['qty'], r['seq'], r['offset'], r['duration'], r['rule'], 'MIGRATION',
                                       'MIGRATION')) + ');')
    for (entity, g), duration in sorted(durations.items()):
        out.append(f"UPDATE {t}.prd_goods SET duration_min = {duration} WHERE entity_no = {q(entity)} "
                   f"AND goods_no = {q(g)};")
    for schema, table, column, kind, _ in refs:
        for old, (_, new) in sorted(mapping.items()):
            if kind == 'exact':
                out.append(f"UPDATE `{schema}`.`{table}` SET `{column}` = {q(str(new))} WHERE `{column}` = {q(old)};")
            else:
                out.append(f"UPDATE `{schema}`.`{table}` SET `{column}` = REPLACE(`{column}`, {q(chr(34) + old + chr(34))}, "
                           f"{q(chr(34) + str(new) + chr(34))}) WHERE CAST(`{column}` AS CHAR) LIKE {q('%' + old + '%')};")
    # 改写后不应再有旧版本号
    for schema, table, column, kind, _ in refs:
        cond = ' OR '.join(f"CAST(`{column}` AS CHAR) LIKE {q('%' + old + '%')}" for old in mapping)
        out.append(f"INSERT INTO svc_migrate_guard SELECT {q('left:' + table + '.' + column)}, COUNT(*) "
                   f"FROM `{schema}`.`{table}` WHERE {cond};")
    out.append(f"INSERT INTO svc_migrate_guard SELECT 'definition_rows', {len(rows)} - (SELECT COUNT(*) FROM "
               f"{t}.prd_service_definition WHERE source_ref LIKE 'MIGRATED:%');")
    out.append("COMMIT;")
    return '\n'.join(out) + '\n'


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--src', required=True, help='旧库名（美业定义所在），如 ai_shop_ume')
    p.add_argument('--trade', required=True, help='门店交易库名，如 n_trade_v2')
    p.add_argument('--rewrite', action='append', default=[],
                   help='除交易库外还要改写引用的库（美业行业表所在），可多次给出')
    p.add_argument('--mysql', default='mysql', help='mysql 批处理命令')
    p.add_argument('--source-tz', default='UTC', help='旧库时间所在时区：UTC 或 +08:00 这样的偏移')
    g = p.add_mutually_exclusive_group()
    g.add_argument('--plan', action='store_true', help='只读：打印将写的行、版本对照与要改的引用')
    g.add_argument('--print', action='store_true', help='只打印将执行的 SQL')
    args = p.parse_args()
    for n in [args.src, args.trade] + args.rewrite:
        if not NAME.match(n):
            raise SystemExit('库名只能是字母数字下划线：' + n)
    if not TZ.match(args.source_tz):
        raise SystemExit('--source-tz 只收 UTC 或 ±HH:MM')
    heads, versions, requirements, goods_of = load(args)
    rows, reqs, mapping, missing, durations = compose(heads, versions, requirements, goods_of, args.source_tz)
    if missing:
        raise SystemExit('这些 SKU 在交易库找不到商品，先搬商品：' + ', '.join(sorted(missing)))
    refs = references(args, [args.trade] + args.rewrite, mapping)
    sql = migration_sql(args, rows, reqs, mapping, refs, durations)
    if args.print:
        print(sql)
        return
    if args.plan or not args.print:
        print('旧版本 %d 个 → 定义行 %d、要求行 %d' % (len(versions), len(rows), len(reqs)))
        print('版本对照：')
        for old, (goods, new) in sorted(mapping.items(), key=lambda x: (x[1], x[0])):
            print('  %s → 商品 %s 第 %d 版' % (old, goods, new))
        print('定义行：')
        for r in rows:
            print('  %s v%d sku=%r 时长 %d(+%d/%d) 状态 %s 发布 %s' % (r['goods'], r['version'], r['sku'],
                  r['duration'], r['before'], r['after'], r['status'], r['at']))
        print('要改写的引用：')
        for schema, table, column, kind, hits in refs:
            print('  %s.%s.%s（%s）%d 行' % (schema, table, column, '等值' if kind == 'exact' else '文本替换', hits))
    if args.plan:
        return
    run(args.mysql, sql)
    print('已提交。')


if __name__ == '__main__':
    main()
