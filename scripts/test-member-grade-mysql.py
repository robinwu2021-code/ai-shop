"""Exercise the grade operator against a disposable schema on local MySQL 9."""
import argparse
import json
import runpy
import re
import subprocess
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pymysql

root = Path(__file__).resolve().parents[1]
defaults = str(Path.home() / '.config/mysql9/client.cnf')
schema = 'grade_it_' + uuid.uuid4().hex
ddl = (root / 'backend/shop-app/src/test/resources/schema-test.sql').read_text()


def run(*args):
    command = [sys.executable, str(root / 'scripts/member-grade.py'), '--database', schema,
               '--tenant', 'MAIN', '--entity', 'E', '--actor', 'TEST', '--defaults-file', defaults]
    return subprocess.check_output(command + list(args), text=True).strip()


def publish(version, start, threshold, *previous):
    return run('publish', '--rule', 'R', '--version', version, '--effective-from', start.isoformat(),
               '--levels', json.dumps([{'code': 'BASE', 'minOrders': 0},
                                       {'code': 'GOLD', 'minOrders': threshold}]), *previous)


def review_at(cursor, start):
    # Freeze the review instant at the exact inclusive boundary of the published version.
    backfill = runpy.run_path(str(root / 'scripts/member-grade.py'))['backfill']
    return backfill(cursor, argparse.Namespace(tenant='MAIN', entity='E', actor='TEST'),
                    start.replace(tzinfo=None))


with pymysql.connect(read_default_file=defaults, autocommit=True) as db, db.cursor() as cur:
    cur.execute('SELECT VERSION(), @@port')
    version, port = cur.fetchone()
    assert version.startswith('9.') and port == 3308
    cur.execute('CREATE DATABASE ' + schema)
    try:
        cur.execute('USE ' + schema)
        for table in ('mbr_member', 'mch_entity'):
            cur.execute(re.search(r'CREATE TABLE IF NOT EXISTS ' + table + r'\s*\(.*?\n\);',
                                  ddl, re.S).group())
        migration = root / 'backend/shop-app/src/main/resources/db/migration/V707__member_grade_rules.sql'
        cur.execute(migration.read_text().split('ALTER TABLE mbr_member')[0])
        cur.execute("""INSERT INTO mch_entity(entity_no,name,created_at,updated_at)
                       VALUES ('E','E',NOW(),NOW())""")
        for number, entity, count, status in [('A', 'E', 3, 'ACTIVE'), ('B', 'E', 1, 'ACTIVE'),
                                               ('C', 'OTHER', 8, 'ACTIVE'), ('D', 'E', 8, 'BLOCKED')]:
            cur.execute("""INSERT INTO mbr_member(member_no,entity_no,person_no,source,joined_at,
                           created_at,updated_at,status,order_count,level)
                           VALUES (%s,%s,%s,'ORDER',1,NOW(),NOW(),%s,%s,'REGULAR')""",
                        (number, entity, number, status, count))
        t1 = datetime.now(timezone.utc).replace(microsecond=0) + timedelta(days=1)
        t2 = t1 + timedelta(days=1)
        assert publish('V1', t1, 2) == 'published'
        assert run('backfill') == 'no effective rule'
        assert publish('V2', t2, 4, '--previous', 'V1') == 'published'
        assert publish('V2', t2, 4, '--previous', 'V1') == 'already published'
        assert review_at(cur, t1) == 'V1: updated 2'
        cur.execute("SELECT grade_code,level_rule_version_no FROM mbr_member WHERE member_no='A'")
        assert cur.fetchone() == ('GOLD', 'V1')
        assert review_at(cur, t1) == 'V1: updated 0'
        assert review_at(cur, t2) == 'V2: updated 2'
        assert review_at(cur, t2) == 'V2: updated 0'
        cur.execute('SELECT grade_code,level_rule_version_no,level,version FROM mbr_member ORDER BY member_no')
        assert cur.fetchall() == (('BASE', 'V2', 'REGULAR', 2), ('BASE', 'V2', 'REGULAR', 2),
                                  (None, None, 'REGULAR', 0), (None, None, 'REGULAR', 0))
        print(f'MySQL {version}: V1 updates 2/0, V2 updates 2/0, other entity and blocked member unchanged')
    finally:
        cur.execute('DROP DATABASE ' + schema)
