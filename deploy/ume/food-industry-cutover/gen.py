#!/usr/bin/env python3
"""从测试建表脚本（db/n_industry V1、V2）生成 .61 行业库的变更脚本与期望结构（TDD-餐饮域迁移新库 P1-5）。

做法：在本机 MySQL 新建一次性库，依次执行 V1、V2（与 FoodIndustryDatabaseTest 相同），再逐表 SHOW CREATE TABLE。
产物与代码测过的结构逐字一致：
    02-rebuild-food-tables.sql   退役表删除 + 餐饮独有的 25 张表按定稿结构重建（只在为空时执行）
    03-alter-shared-tables.sql   与美业共用、.61 上已有美业数据的 2 张表：逐列改成定稿定义，不动数据
    expected-structure.tsv       每张表的列（名、类型、可空、默认）与唯一键，run.sh 执行后据此核对；缺的唯一键由 run.sh 补

用法：python3 deploy/ume/food-industry-cutover/gen.py [--check]
连接：MYSQL（默认 ~/.local/opt/mysql/bin/mysql）、MYSQL_ARGS（默认 -uroot -h127.0.0.1）。
"""
import os
import re
import shlex
import subprocess
import sys
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SQL = ROOT / 'backend-plugins/shop-industry-food/food-core/src/test/resources/db/n_industry'
TABLES_JAVA = ROOT / 'backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/config/FoodIndustryTables.java'
MYSQL = os.environ.get('MYSQL', str(Path.home() / '.local/opt/mysql/bin/mysql'))
ARGS = shlex.split(os.environ.get('MYSQL_ARGS', '-uroot -h127.0.0.1'))
# 行业库里退役的表（.61 上由 01-industry-kds.sql 或设计稿建过）：合并 ⑥ 与设备出口 D-2、D-3
RETIRED = ['fnb_kds_event', 'fnb_kds_cursor', 'fnb_station_runtime']
# 与美业共用、.61 上美业已在写的表（2026-11-28 现场各有 1 行美业门店策略）：只改列定义、补唯一键，不重建
SHARED = ['ind_store_policy', 'ind_config_revision']


def food_tables():
    text = TABLES_JAVA.read_text(encoding='utf-8')
    body = text[text.index('NAMES ='):text.index(';', text.index('NAMES ='))]
    return re.findall(r'"([a-z_0-9]+)"', body)


def mysql(*extra, stdin=None):
    return subprocess.run([MYSQL, *ARGS, '--batch', '--raw', '--skip-column-names', *extra],
                          input=stdin, capture_output=True, text=True, check=True).stdout


def build():
    db = 'food_cutover_gen_' + uuid.uuid4().hex[:8]
    mysql('-e', 'CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci' % db)
    try:
        for f in ('V1__industry_design.sql', 'V2__industry_supplement.sql'):
            mysql(db, stdin=(SQL / f).read_text(encoding='utf-8'))
        tables = food_tables()
        creates, expected = [], []
        for t in tables:
            out = mysql(db, '-e', 'SHOW CREATE TABLE `%s`' % t)
            create = out.split('\t', 1)[1].rstrip('\n').replace('\\n', '\n')
            creates.append((t, re.sub(r' AUTO_INCREMENT=\d+', '', create)))
        cols = mysql('-e', "SELECT table_name, column_name, column_type, is_nullable, IFNULL(column_default,'<null>') "
                           "FROM information_schema.columns WHERE table_schema='%s' ORDER BY table_name, ordinal_position" % db)
        keys = mysql('-e', "SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                           "FROM information_schema.statistics WHERE table_schema='%s' AND non_unique=0 "
                           "GROUP BY table_name, index_name ORDER BY table_name, index_name" % db)
        wanted = set(tables)
        for line in cols.splitlines():
            if line.split('\t')[0] in wanted:
                expected.append('column\t' + line)
        for line in keys.splitlines():
            if line.split('\t')[0] in wanted:
                expected.append('unique\t' + line)
        return tables, creates, expected
    finally:
        mysql('-e', 'DROP DATABASE `%s`' % db)


def render(tables, creates):
    creates = [(t, c) for t, c in creates if t not in SHARED]
    out = ['-- .61 行业库 n_industry 的餐饮表变更（TDD-餐饮域迁移新库 P1-5）。由 gen.py 从 db/n_industry V1、V2 生成，不要手改。',
           '-- 只在这些表都为空时执行（run.sh 先核对行数并备份）：退役表删除，餐饮独有的 %d 张表按定稿结构重建。' % len(creates),
           '-- 与美业共用的 %s 不在这里，见 03-alter-shared-tables.sql。' % '、'.join(SHARED),
           '-- 美业表（bty_*）与提成规则 ind_commission_rule 不在本批，不动。', '']
    out.append('-- 一、退役')
    for t in RETIRED:
        out.append('DROP TABLE IF EXISTS `%s`;' % t)
    out += ['', '-- 二、重建']
    for t, c in creates:
        out += ['DROP TABLE IF EXISTS `%s`;' % t, c + ';', '']
    return '\n'.join(out)


def render_shared(creates):
    out = ['-- 与美业共用的表：逐列改成定稿定义（TDD-餐饮域迁移新库 P1-5）。由 gen.py 生成，不要手改。',
           '-- 列集合须与定稿相同（MODIFY 不加列也不删列，少列即报错停止）；数据保留。缺的唯一键由 run.sh 按 expected-structure.tsv 补。', '']
    for t, c in creates:
        if t not in SHARED:
            continue
        cols = [l.strip().rstrip(',') for l in c.split('\n') if l.strip().startswith('`')]
        out.append('ALTER TABLE `%s`\n  ' % t + ',\n  '.join('MODIFY COLUMN ' + x for x in cols) + ';\n')
    return '\n'.join(out)


def main():
    tables, creates, expected = build()
    targets = {HERE / '02-rebuild-food-tables.sql': render(tables, creates),
               HERE / '03-alter-shared-tables.sql': render_shared(creates),
               HERE / 'shared.txt': '\n'.join(SHARED) + '\n',
               HERE / 'expected-structure.tsv': '\n'.join(expected) + '\n',
               HERE / 'tables.txt': '\n'.join(tables) + '\n',
               HERE / 'retired.txt': '\n'.join(RETIRED) + '\n'}
    if '--check' in sys.argv:
        stale = [p.name for p, s in targets.items() if not p.exists() or p.read_text(encoding='utf-8') != s]
        if stale:
            raise SystemExit('stale: ' + ', '.join(stale))
        print('ok: %d tables' % len(tables))
        return
    for p, s in targets.items():
        p.write_text(s, encoding='utf-8')
    print('%d tables, %d expected rows' % (len(tables), len(expected)))


if __name__ == '__main__':
    main()
