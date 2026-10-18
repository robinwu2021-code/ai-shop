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
        assert.equal(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='entitlement_test'"), '38');
        assert.ok(sql("SHOW COLUMNS FROM ent_template LIKE 'draft_snapshot'"), 'template draft needs persisted editable rules');
        const migration = readFileSync(new URL(
            '../../backend/shop-app/src/main/resources/db/migration/V425__entitlement_catalog.sql', import.meta.url), 'utf8');
        sql('CREATE DATABASE entitlement_runtime_test');
        sql(migration, 'entitlement_runtime_test');
        sql(migration, 'entitlement_runtime_test');
        assert.equal(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='entitlement_runtime_test'"), '3');
        for (const table of ['ent_template', 'ent_template_version', 'pay_ent_command_dedup']) {
            const columns = database => sql(`SELECT column_name,column_type,is_nullable FROM information_schema.columns
                WHERE table_schema='${database}' AND table_name='${table}' ORDER BY ordinal_position`);
            assert.equal(columns('entitlement_runtime_test'), columns('entitlement_test'), table);
        }
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
