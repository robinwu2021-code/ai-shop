#!/usr/bin/env python3
"""Rehearse V671 against actual permission DDL/seed in a disposable native MySQL 9 schema."""
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
ALIASES = json.loads((ROOT / 'ops-web/lib/platform-menu-aliases.json').read_text())
ALIAS_CODES = {alias['pointCode'] for alias in ALIASES}
CLIENT = os.environ.get('MYSQL9_CLIENT', '/opt/homebrew/opt/mysql@9.7/bin/mysql')
CONFIG = Path.home() / '.config/mysql9/client.cnf'
SCHEMA = 'platform_menu_v671_' + uuid.uuid4().hex


def mysql(sql, schema=None):
    command = [CLIENT, '--defaults-extra-file=' + str(CONFIG), '--batch', '--raw', '--skip-column-names']
    if schema:
        command.append(schema)
    result = subprocess.run(command, input=sql, text=True, capture_output=True, check=True)
    return result.stdout.rstrip('\n')


def snapshot(table, where='1=1'):
    return mysql(f'SELECT * FROM {table} WHERE {where} ORDER BY id;', SCHEMA)


def quoted(value):
    return "'" + value.replace("'", "''") + "'"


version = mysql('SELECT VERSION();')
assert version.startswith('9.'), 'Native MySQL 9 is required'
mysql(f'CREATE DATABASE {SCHEMA} CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;')
try:
    ddl = (ROOT / 'backend/shop-app/src/test/resources/schema-test.sql').read_text()
    tables = ['sys_function', 'sys_function_point', 'sys_role', 'sys_role_point']
    for table in tables:
        statement = re.search(r'CREATE TABLE IF NOT EXISTS ' + table + r'\s*\(.*?\n\);', ddl, re.S)
        assert statement, table
        mysql(statement.group(), SCHEMA)
    generated = subprocess.check_output(['node', 'ops-web/scripts/gen-perm-seed.mjs'], cwd=ROOT, text=True)
    old_seed = '\n'.join(line for line in generated.splitlines()
                         if any(line.startswith(f'INSERT INTO {table} (') for table in tables)
                         and not any(quoted(code) in line for code in ALIAS_CODES))
    mysql(old_seed, SCHEMA)
    roles = mysql("SELECT role_code FROM sys_role WHERE end_code='OPS' ORDER BY role_code;", SCHEMA).splitlines()
    assert len(roles) == 12, roles
    assert int(mysql("SELECT COUNT(*) FROM sys_function_point WHERE point_type='MENU';", SCHEMA)) == 155
    # Non-built-in, point-specific grants ensure no static role or inferred permission broadening.
    mysql("INSERT INTO sys_role(role_code,name,end_code,created_at,updated_at) VALUES('CUSTOM_STORES','Custom stores','OPS',NOW(),NOW()),('CUSTOM_ACTION','Custom action','OPS',NOW(),NOW());"
          "INSERT INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at) VALUES('CUSTOM_STORES','OPS_MERCHANT__TAB_STORES','OPS',NOW(),NOW()),('CUSTOM_ACTION','ACT__DASHBOARD_OVERVIEW_READ','OPS',NOW(),NOW());", SCHEMA)
    before = {table: snapshot(table) for table in tables}
    old_points = int(mysql('SELECT COUNT(*) FROM sys_function_point;', SCHEMA))
    migration = (ROOT / 'backend/shop-app/src/main/resources/db/migration/V671__ops_platform_primary_navigation.sql').read_text()
    original_filter = 'point_code NOT IN (' + ','.join(map(quoted, ALIAS_CODES)) + ')'
    for _ in range(2):
        mysql(migration, SCHEMA)
        assert snapshot('sys_function') == before['sys_function']
        assert snapshot('sys_role') == before['sys_role']
        assert snapshot('sys_function_point', original_filter) == before['sys_function_point']
        assert snapshot('sys_role_point', original_filter) == before['sys_role_point']
        assert int(mysql('SELECT COUNT(*) FROM sys_function_point;', SCHEMA)) == old_points + 10
        for alias in ALIASES:
            roles_for_alias = set(mysql('SELECT role_code FROM sys_role_point WHERE end_code=\'OPS\' AND point_code=' + quoted(alias['pointCode']) + ';', SCHEMA).splitlines())
            expected = {'SUPER_ADMIN'}
            if alias['sourcePoint']:
                expected.update(mysql('SELECT role_code FROM sys_role_point WHERE end_code=\'OPS\' AND point_code=' + quoted(alias['sourcePoint']) + ';', SCHEMA).splitlines())
            assert roles_for_alias == expected, (alias['href'], roles_for_alias, expected)
        if _ == 0:
            first = {table: snapshot(table) for table in tables}
        else:
            assert first == {table: snapshot(table) for table in tables}, 'Second application must be idempotent'
    print(json.dumps({'mysql': version, 'builtinRoles': roles, 'customRoles': 2, 'legacyMenuPoints': 155,
                      'addedAliases': 10, 'applications': 2, 'originalPointsAndGrantsUnchanged': True,
                      'exactSourceGrantParity': True, 'idempotent': True, 'disposableSchemaDropped': True}, ensure_ascii=False, indent=2))
finally:
    mysql(f'DROP DATABASE {SCHEMA};')
