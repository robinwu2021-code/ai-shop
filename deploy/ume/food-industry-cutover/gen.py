#!/usr/bin/env python3
"""从测试建表脚本（db/n_industry V1、V2）生成 .61 行业库的变更脚本与期望结构（TDD-餐饮域迁移新库 P1-5）。

做法：在本机 MySQL 新建一次性库，依次执行 V1、V2（与 FoodIndustryDatabaseTest 相同），再逐表 SHOW CREATE TABLE。
产物与代码测过的结构逐字一致：
    02-rebuild-food-tables.sql   退役表删除 + 餐饮 27 张表按定稿结构重建
    expected-structure.tsv       每张表的列（名、类型、可空、默认）与唯一键，run.sh 执行后据此核对

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
    out = ['-- .61 行业库 n_industry 的餐饮表变更（TDD-餐饮域迁移新库 P1-5）。由 gen.py 从 db/n_industry V1、V2 生成，不要手改。',
           '-- 只在这些表都为空时执行（run.sh 先核对行数并备份）：退役表删除，餐饮 %d 张表按定稿结构重建。' % len(tables),
           '-- 美业表（bty_*）与提成规则 ind_commission_rule 不在本批，不动。', '']
    out.append('-- 一、退役')
    for t in RETIRED:
        out.append('DROP TABLE IF EXISTS `%s`;' % t)
    out += ['', '-- 二、重建']
    for t, c in creates:
        out += ['DROP TABLE IF EXISTS `%s`;' % t, c + ';', '']
    return '\n'.join(out)


def main():
    tables, creates, expected = build()
    targets = {HERE / '02-rebuild-food-tables.sql': render(tables, creates),
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
