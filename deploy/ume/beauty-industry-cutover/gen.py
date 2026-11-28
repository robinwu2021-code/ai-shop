#!/usr/bin/env python3
"""从美业测试建表脚本（db/n_industry V1、V2）生成 .61 行业库的美业变更脚本与期望结构（TDD-美业域迁移新库 B-6）。

做法同餐饮（deploy/ume/food-industry-cutover/gen.py）：在本机 MySQL 新建一次性库，依次执行美业的 V1（设计 DDL 原样）、
V2（触发器），再逐表 SHOW CREATE TABLE。产物与美业测试测过的结构逐字一致：
    02-create-beauty-tables.sql   美业在行业库的表按定稿结构建（已有的先删，只在为空时执行）
    03-alter-shared-table.sql     与餐饮共用的指派表 ind_assignment：补美业的两列、对象类型注释加 TASK，不动数据
    04-triggers.sql               美业表的触发器（设计 DDL 不收触发器）
    expected-structure.tsv        各表的列（名、类型、可空、默认）与唯一键，以及触发器；run.sh 执行后据此核对

用法：python3 deploy/ume/beauty-industry-cutover/gen.py [--check]
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
SQL = ROOT / 'backend-plugins/shop-industry-beauty/beauty-core/src/test/resources/db/n_industry'
TABLES_JAVA = ROOT / ('backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/'
                      'BeautyIndustryTables.java')
MYSQL = os.environ.get('MYSQL', str(Path.home() / '.local/opt/mysql/bin/mysql'))
ARGS = shlex.split(os.environ.get('MYSQL_ARGS', '-uroot -h127.0.0.1'))
# 与餐饮共用的表：美业只补列、改注释，不重建（餐饮的变更脚本建过它）
SHARED = 'ind_assignment'
SHARED_ADDED = ['employment_assignment_no', 'planned_assignment_no']


def beauty_tables():
    text = TABLES_JAVA.read_text(encoding='utf-8')
    body = text[text.index('NAMES ='):text.index(';', text.index('NAMES ='))]
    return [t for t in re.findall(r'"([a-z_0-9]+)"', body) if t.startswith('bty_')]


def mysql(*extra, stdin=None):
    return subprocess.run([MYSQL, *ARGS, '--batch', '--raw', '--skip-column-names', *extra],
                          input=stdin, capture_output=True, text=True, check=True).stdout


def build():
    db = 'beauty_cutover_gen_' + uuid.uuid4().hex[:8]
    mysql('-e', 'CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci' % db)
    try:
        for f in ('V1__industry_design.sql', 'V2__beauty_supplement.sql'):
            mysql(db, stdin=(SQL / f).read_text(encoding='utf-8'))
        tables = beauty_tables()
        creates = {}
        for t in tables + [SHARED]:
            out = mysql(db, '-e', 'SHOW CREATE TABLE `%s`' % t)
            create = out.split('\t', 1)[1].rstrip('\n').replace('\\n', '\n')
            creates[t] = re.sub(r' AUTO_INCREMENT=\d+', '', create)
        wanted = set(tables + [SHARED])
        expected = []
        cols = mysql('-e', "SELECT table_name, column_name, column_type, is_nullable, IFNULL(column_default,'<null>') "
                           "FROM information_schema.columns WHERE table_schema='%s' ORDER BY table_name, ordinal_position" % db)
        keys = mysql('-e', "SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                           "FROM information_schema.statistics WHERE table_schema='%s' AND non_unique=0 "
                           "GROUP BY table_name, index_name ORDER BY table_name, index_name" % db)
        triggers = mysql('-e', "SELECT event_object_table, trigger_name FROM information_schema.triggers "
                               "WHERE trigger_schema='%s' ORDER BY event_object_table, trigger_name" % db)
        for line in cols.splitlines():
            if line.split('\t')[0] in wanted:
                expected.append('column\t' + line)
        for line in keys.splitlines():
            if line.split('\t')[0] in wanted:
                expected.append('unique\t' + line)
        for line in triggers.splitlines():
            expected.append('trigger\t' + line)
        return tables, creates, expected
    finally:
        mysql('-e', 'DROP DATABASE `%s`' % db)


def render_tables(tables, creates):
    out = ['-- .61 行业库 n_industry 的美业表（TDD-美业域迁移新库 B-6）。由 gen.py 从美业测试的 db/n_industry V1、V2 生成，不要手改。',
           '-- 只在这些表都为空时执行（run.sh 先核对行数并备份）：已有的先删，按定稿结构建 %d 张。' % len(tables),
           '-- 与餐饮共用的 %s 不在这里，见 03-alter-shared-table.sql；触发器见 04-triggers.sql。' % SHARED, '']
    for t in tables:
        out += ['DROP TABLE IF EXISTS `%s`;' % t, creates[t] + ';', '']
    return '\n'.join(out)


def render_shared(creates):
    lines = {re.match(r'\s*`(\w+)`', l).group(1): l.strip().rstrip(',')
             for l in creates[SHARED].split('\n') if l.strip().startswith('`')}
    names = list(lines)
    parts = ['MODIFY COLUMN ' + lines['target_type']]
    for c in SHARED_ADDED:
        parts.append('ADD COLUMN %s AFTER `%s`' % (lines[c], names[names.index(c) - 1]))
    return ('-- 与餐饮共用的指派表：美业服务参与（B-4）补的两列与对象类型注释（TDD-美业域迁移新库 B-6）。由 gen.py 生成，不要手改。\n'
            '-- run.sh 只在两列都还没有时执行；数据保留。\n'
            'ALTER TABLE `%s`\n  ' % SHARED + ',\n  '.join(parts) + ';\n')


def render_triggers(tables):
    v2 = (SQL / 'V2__beauty_supplement.sql').read_text(encoding='utf-8')
    names = re.findall(r'CREATE TRIGGER `?(\w+)`?', v2)
    body = v2[v2.index('DELIMITER'):]
    return ('-- 美业表的触发器（TDD-美业域迁移新库 B-6）：取美业测试 db/n_industry V2 原文。由 gen.py 生成，不要手改。\n'
            '-- 先删同名触发器再建，可重复执行。含 DELIMITER 块：用 mysql 客户端执行。\n'
            + ''.join('DROP TRIGGER IF EXISTS `%s`;\n' % n for n in names) + '\n' + body.rstrip('\n') + '\n')


def main():
    tables, creates, expected = build()
    targets = {HERE / '02-create-beauty-tables.sql': render_tables(tables, creates),
               HERE / '03-alter-shared-table.sql': render_shared(creates),
               HERE / '04-triggers.sql': render_triggers(tables),
               HERE / 'expected-structure.tsv': '\n'.join(expected) + '\n',
               HERE / 'tables.txt': '\n'.join(tables) + '\n'}
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
