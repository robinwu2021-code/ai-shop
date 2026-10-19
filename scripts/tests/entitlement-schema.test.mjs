import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';

// Uses an isolated, portless container; never connects to a configured shop database.
test('target schema enforces daily quota, tenant uniqueness, conditional holds and valid JSON', async () => {
    const name = `codex-entitlement-schema-${randomUUID()}`;
    const docker = args => execFileSync('docker', args, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
    docker(['run', '--detach', '--name', name, '--network', 'none', '--tmpfs', '/var/lib/mysql',
        '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_DATABASE=entitlement_test', 'mysql:9.7.2']);
    try {
        let ready = false;
        for (let attempt = 0; attempt < 60; attempt++) {
            try {
                docker(['exec', name, 'mysql', '-uroot', '-N', 'entitlement_test', '-e', 'SELECT 1']);
                ready = true;
                break;
            } catch {
                await new Promise(resolve => setTimeout(resolve, 500));
            }
        }
        assert.ok(ready, 'isolated database did not become ready');
        const sql = (value, database = 'entitlement_test') => execFileSync('docker', ['exec', '-i', name, 'mysql', '-uroot', '-N',
            database], { input: value, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
        for (const owner of ['core', 'pay']) {
            sql(readFileSync(new URL(`../../docs/technical/design/活动权益-${owner}-schema.sql`, import.meta.url), 'utf8'));
        }
        assert.equal(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='entitlement_test'"), '42');
        assert.ok(sql("SHOW COLUMNS FROM ent_template LIKE 'draft_snapshot'"), 'template draft needs persisted editable rules');
        sql(readFileSync(new URL('../../docs/technical/design/活动权益-support-schema.sql', import.meta.url), 'utf8'));
        assert.equal(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='entitlement_test'"), '48');
        const supportAudit = "created_at,updated_at,created_by,updated_by";
        const supportValues = "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'TEST','TEST'";
        const decision = (no, key, settled, status, type, revision = 1, tenant = 'MAIN') =>
            `INSERT INTO ord_ent_source_decision(tenant_no,decision_no,purpose,source_key,source_snapshot,`
            + `settled_source_key,source_revision,decision_type,status,user_no,entity_no,store_no,currency,amount_minor,`
            + `snapshot_hash,occurred_at,${supportAudit}) VALUES ('${tenant}','${no}','USE','${key}','{}',`
            + `${settled === null ? 'NULL' : "'" + settled + "'"},${revision},'${type}','${status}','u1','m1','s1',`
            + `'CNY',100,'${'a'.repeat(64)}',UTC_TIMESTAMP(3),${supportValues})`;
        sql(decision('cancel1', '1'.repeat(64), null, 'CANCELLED', 'USE_CANCEL'));
        sql(decision('success2', '2'.repeat(64), 'f'.repeat(64), 'COMMITTED', 'USE_COMMIT', 2));
        assert.throws(() => sql(decision('success3', '3'.repeat(64), 'f'.repeat(64), 'COMMITTED', 'USE_COMMIT', 3)),
            /Duplicate entry/, 'one successful settlement across revisions');
        sql(decision('other-tenant', '2'.repeat(64), 'f'.repeat(64), 'COMMITTED', 'USE_COMMIT', 2, 'OTHER'));
        assert.throws(() => sql(decision('bad-cancel', '4'.repeat(64), null, 'CANCELLED', 'USE_COMMIT')),
            /Check constraint/, 'a cancellation cannot assert a commit decision');

        const migration = readFileSync(new URL(
            '../../backend/shop-app/src/main/resources/db/migration/V425__entitlement_catalog.sql', import.meta.url), 'utf8');
        sql('CREATE DATABASE entitlement_runtime_test');
        sql(migration, 'entitlement_runtime_test');
        sql(migration, 'entitlement_runtime_test');
        const memberMigration = readFileSync(new URL(
            '../../backend/shop-app/src/main/resources/db/migration/V426__member_identity_ticket.sql', import.meta.url), 'utf8');
        sql(memberMigration, 'entitlement_runtime_test');
        sql(memberMigration, 'entitlement_runtime_test');
        assert.equal(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='entitlement_runtime_test'"), '4');
        for (const table of ['ent_template', 'ent_template_version', 'pay_ent_command_dedup', 'mbr_identity_ticket']) {
            const columns = database => sql(`SELECT column_name,column_type,is_nullable FROM information_schema.columns
                WHERE table_schema='${database}' AND table_name='${table}' ORDER BY ordinal_position`);
            assert.equal(columns('entitlement_runtime_test'), columns('entitlement_test'), table);
        }
        const ticket = (hash, tenant = 'MAIN', purpose = 'MEMBER_CODE', scope = 'NULL,NULL,NULL,NULL',
            expiry = 'DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 60 SECOND)') =>
            `INSERT INTO mbr_identity_ticket(tenant_no,ticket_hash,purpose,user_no,entity_no,store_no,actor_no,`
            + `source_ticket_hash,issued_at,expires_at,created_at,updated_at,created_by,updated_by) VALUES `
            + `('${tenant}',REPEAT('${hash}',64),'${purpose}','u1',${scope},UTC_TIMESTAMP(3),${expiry},`
            + `UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'u1','u1')`;
        sql(ticket('a'), 'entitlement_runtime_test');
        assert.throws(() => sql(ticket('a'), 'entitlement_runtime_test'), /Duplicate entry/);
        sql(ticket('a', 'OTHER'), 'entitlement_runtime_test');
        assert.throws(() => sql(ticket('b', 'MAIN', 'CUSTOMER_REFERENCE'), 'entitlement_runtime_test'),
            /Check constraint/, 'customer refs require all bound scope columns');
        sql(ticket('b', 'MAIN', 'CUSTOMER_REFERENCE', "'m1','s1','actor1',REPEAT('a',64)"), 'entitlement_runtime_test');
        assert.throws(() => sql(ticket('c', 'MAIN', 'MEMBER_CODE', "'m1',NULL,NULL,NULL"),
            'entitlement_runtime_test'), /Check constraint/, 'unified codes must not carry a merchant scope');
        assert.throws(() => sql(ticket('c', 'MAIN', 'LOGIN_TOKEN'), 'entitlement_runtime_test'), /Check constraint/);
        assert.throws(() => sql(ticket('c', 'MAIN', 'MEMBER_CODE', 'NULL,NULL,NULL,NULL',
            'DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 SECOND)'), 'entitlement_runtime_test'), /Check constraint/);
        const dedup = key => `INSERT INTO pay_ent_command_dedup
            (tenant_no,operation_kind,request_key,payload_hash,resource_no,result_status,
             created_at,updated_at,created_by,updated_by)
            VALUES ('MAIN','KEY_CASE','${key}',REPEAT('a',64),'r','PENDING',
             UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'SYSTEM','SYSTEM')`;
        sql(dedup('KeyA'), 'entitlement_runtime_test');
        sql(dedup('keya'), 'entitlement_runtime_test');
        assert.equal(sql("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='KEY_CASE'",
            'entitlement_runtime_test'), '2');
        const bucket = tenant => `INSERT INTO ent_period_bucket
            (tenant_no,instance_no,period_key,service_day,limit_units,created_at,updated_at,created_by,updated_by)
            VALUES ('${tenant}','instance1','period1','2026-10-18',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'SYSTEM','SYSTEM')`;
        sql(bucket('MAIN'));
        assert.throws(() => sql(bucket('MAIN')), error => /1062/.test(error.stderr.toString()));
        sql(bucket('OTHER'));
        const hold = `UPDATE ent_period_bucket SET reserved_units=reserved_units+1,version=version+1
            WHERE tenant_no='MAIN' AND instance_no='instance1' AND period_key='period1'
            AND service_day='2026-10-18' AND reserved_units+used_units<limit_units; SELECT ROW_COUNT();`;
        assert.equal(sql(hold), '1');
        assert.equal(sql(hold), '0');
        assert.throws(() => sql("UPDATE ent_period_bucket SET used_units=1 WHERE tenant_no='MAIN'"),
            error => /3819/.test(error.stderr.toString()));
        sql(`INSERT INTO pmt_recharge_tier
            (tenant_no,tier_no,version_no,pay_amount_minor,bonus_amount_minor,redemption_scope,
             created_at,updated_at,created_by,updated_by)
            VALUES ('MAIN','tier1','v1',10000,1000,'{}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'SYSTEM','SYSTEM')`);
        assert.throws(() => sql("UPDATE pmt_recharge_tier SET redemption_scope='invalid'"),
            error => /3140/.test(error.stderr.toString()));
    } finally {
        docker(['rm', '--force', name]);
    }
});
