#!/usr/bin/env python3
"""Publish an entity's cumulative-order rule or run its explicit membership review."""
import argparse
import json
from datetime import datetime, timezone

import pymysql


def tiers(value):
    levels = json.loads(value)
    if not isinstance(levels, list) or not levels:
        raise argparse.ArgumentTypeError("supply at least one tier")
    previous, codes = -1, set()
    for level in levels:
        if not isinstance(level, dict):
            raise argparse.ArgumentTypeError("each tier requires code and minOrders")
        code, minimum = level.get("code"), level.get("minOrders")
        if (not isinstance(code, str) or not code.strip() or len(code) > 32 or code in codes
                or type(minimum) is not int or minimum <= previous):
            raise argparse.ArgumentTypeError("use unique codes and strictly increasing order thresholds")
        previous = minimum
        codes.add(code)
    if levels[0]["minOrders"] != 0:
        raise argparse.ArgumentTypeError("the first tier must start at zero")
    return levels


def publish(cur, args, now):
    start = args.effective_from
    cur.execute("""SELECT rule_no, version_no, effective_from, effective_to, levels_json, status
                   FROM mbr_level_rule WHERE tenant_no=%s AND entity_no=%s
                   AND scope_type='ENTITY' AND scope_no=%s AND deleted=0
                   ORDER BY effective_from DESC FOR UPDATE""", (args.tenant, args.entity, args.entity))
    versions = cur.fetchall()
    for rule, version, effective, _, levels, status in versions:
        if version == args.version:
            if (rule, effective, json.loads(levels), status) == (args.rule, start, args.levels, 'ACTIVE'):
                return 'already published'
            raise ValueError('version already has another definition')
    if versions:
        rule, version, effective, end, _, status = versions[0]
        if (status != 'ACTIVE' or rule != args.rule or version != args.previous or end is not None
                or effective >= start):
            raise ValueError('previous open version and rule must match the supplied predecessor')
        cur.execute("""UPDATE mbr_level_rule SET effective_to=%s, version=version+1,
                       updated_at=%s, updated_by=%s WHERE tenant_no=%s AND version_no=%s""",
                    (start, now, args.actor, args.tenant, version))
    elif args.previous:
        raise ValueError('no predecessor exists')
    if start < now:
        raise ValueError('effective time must be in the future')
    cur.execute("""INSERT INTO mbr_level_rule
        (tenant_no,entity_no,scope_type,scope_no,rule_no,version_no,status,effective_from,basis,
         levels_json,benefits_json,downgrade_policy,published_by,published_at,
         created_by,created_at,updated_by,updated_at)
        VALUES (%s,%s,'ENTITY',%s,%s,%s,'ACTIVE',%s,'ORDER_COUNT',%s,'{}','PERIODIC',
                %s,%s,%s,%s,%s,%s)""",
                (args.tenant, args.entity, args.entity, args.rule, args.version, start,
                 json.dumps(args.levels, ensure_ascii=False), args.actor, now, args.actor, now,
                 args.actor, now))
    return 'published'


def backfill(cur, args, now):
    cur.execute("""SELECT rule_no,version_no,levels_json,basis,stat_period_days,downgrade_policy
                   FROM mbr_level_rule WHERE tenant_no=%s AND entity_no=%s AND scope_type='ENTITY'
                   AND scope_no=%s AND status='ACTIVE' AND deleted=0 AND effective_from<=%s
                   AND (effective_to IS NULL OR effective_to>%s) FOR UPDATE""",
                (args.tenant, args.entity, args.entity, now, now))
    rules = cur.fetchall()
    if not rules:
        return 'no effective rule'
    if len(rules) != 1 or rules[0][3:] != ('ORDER_COUNT', None, 'PERIODIC'):
        raise ValueError('requires one effective cumulative-order rule for periodic review')
    rule, version, levels = rules[0][:3]
    # The entity lock serializes version changes.
    # The order cache may change concurrently; review all bands from a single statement.
    cases, params = [], []
    for level in reversed(tiers(levels)):
        cases.append('WHEN m.order_count >= %s THEN %s')
        params.extend([level['minOrders'], level['code']])
    grade = 'CASE ' + ' '.join(cases) + ' END'
    cur.execute(f"""UPDATE mbr_member m LEFT JOIN mbr_level_rule old
                    ON old.tenant_no=m.tenant_no AND old.version_no=m.level_rule_version_no
                    AND old.entity_no=m.entity_no AND old.scope_type='ENTITY' AND old.scope_no=m.entity_no
                    SET m.grade_code={grade}, m.level_rule_version_no=%s,
                        m.updated_at=%s, m.updated_by=%s, m.version=m.version+1
                    WHERE m.tenant_no=%s AND m.entity_no=%s AND m.deleted=0 AND m.status='ACTIVE'
                    AND m.order_count>=0 AND ((m.grade_code IS NULL AND m.level_rule_version_no IS NULL)
                         OR (m.grade_code IS NOT NULL AND old.rule_no=%s))
                    AND NOT (m.grade_code <=> {grade} AND m.level_rule_version_no <=> %s)""",
                (*params, version, now, args.actor, args.tenant, args.entity, rule, *params, version))
    return f'{version}: updated {cur.rowcount}'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('database', 'tenant', 'entity', 'actor', 'defaults-file'):
        parser.add_argument('--' + option, required=True)
    modes = parser.add_subparsers(dest='action', required=True)
    pub = modes.add_parser('publish')
    for option in ('rule', 'version'):
        pub.add_argument('--' + option, required=True)
    pub.add_argument('--previous')
    pub.add_argument('--effective-from', required=True, type=utc_millis)
    pub.add_argument('--levels', required=True, type=tiers)
    modes.add_parser('backfill')
    args = parser.parse_args()
    with pymysql.connect(read_default_file=args.defaults_file, database=args.database,
                         charset='utf8mb4') as conn, conn.cursor() as cur:
        cur.execute("SELECT id FROM mch_entity WHERE tenant_no=%s AND entity_no=%s AND deleted=0 FOR UPDATE",
                    (args.tenant, args.entity))
        if not cur.fetchone():
            raise ValueError('entity does not exist')
        cur.execute('SELECT UTC_TIMESTAMP(3)')
        now = cur.fetchone()[0]
        result = publish(cur, args, now) if args.action == 'publish' else backfill(cur, args, now)
        conn.commit()
        print(result)


def utc_millis(value):
    stamp = datetime.fromisoformat(value)
    if stamp.tzinfo is None or stamp.microsecond % 1000:
        raise argparse.ArgumentTypeError('supply a timezone and at most millisecond precision')
    return stamp.astimezone(timezone.utc).replace(tzinfo=None)


if __name__ == '__main__':
    main()
