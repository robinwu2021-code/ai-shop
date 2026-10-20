import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import assert from 'node:assert/strict';

test('V460 upgrades existing legacy roots without altering coupon facts and enforces fence metadata constraints', async () => {
    const name = `codex-source-fence-${randomUUID()}`;
    const docker = args => execFileSync('docker', args, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
    const sql = input => execFileSync('docker', ['exec', '-i', name, 'mysql', '-uroot', '-N', 'sourcefence'],
        { input, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
    const migration = file => readFileSync(new URL(`../../backend/shop-app/src/main/resources/db/migration/${file}`,
        import.meta.url), 'utf8');
    docker(['run', '--detach', '--name', name, '--network', 'none', '--tmpfs', '/var/lib/mysql',
        '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_DATABASE=sourcefence', 'mysql:9.7.2']);
    try {
        let ready = false;
        for (let n = 0; n < 60; n++) {
            try { sql('SELECT 1'); ready = true; break; }
            catch { await new Promise(resolve => setTimeout(resolve, 500)); }
        }
        assert.ok(ready, 'isolated MySQL must start');
        // Bootstrap only the original root definitions. The frozen V1 uses MariaDB's UCA1400 collation,
        // unavailable on this cached MySQL image; fixture-only normalization is explicit, not a V1 upgrade claim.
        function rootFixture(file, table) {
            const match = migration(file).match(new RegExp(`CREATE TABLE IF NOT EXISTS ${table}\\b[\\s\\S]*?\\) ENGINE=[^;]+;`));
            assert.ok(match, `Original root DDL must exist: ${table}`);
            return match[0].replaceAll('utf8mb4_uca1400_ai_ci', 'utf8mb4_0900_ai_ci');
        }
        sql(rootFixture('V1__baseline.sql', 'mkt_coupon'));
        sql(rootFixture('V231__pmt_coupon.sql', 'pmt_coupon'));
        sql(`INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,start_at,end_at,funder,entity_no,received_count,
            created_at,updated_at) VALUES('LC1','original','FULL_CUT',100,1,99999,'MERCHANT','M1',3,NOW(),NOW());
            INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,entity_no,received_count,
            created_at,updated_at) VALUES('PC1','original','CASH',100,'M1',3,NOW(),NOW())`);
        sql(migration('V513__legacy_coupon_source_fence.sql'));
        for (const [table, no] of [['mkt_coupon', 'LC1'], ['pmt_coupon', 'PC1']]) {
            assert.equal(sql(`SELECT CONCAT(migration_state,':',received_count,':',status) FROM ${table} WHERE coupon_no='${no}'`),
                'OPEN:3:ACTIVE');
            assert.throws(() => sql(`UPDATE ${table} SET migration_state='DRAINING'`), /Check constraint/);
            sql(`UPDATE ${table} SET migration_state='DRAINING',migration_batch_no='B1'`);
            assert.equal(sql(`SELECT COUNT(*) FROM ${table} WHERE migration_batch_no='b1'`), '0');
            assert.throws(() => sql(`UPDATE ${table} SET migration_state='FROZEN'`), /Check constraint/);
            sql(`UPDATE ${table} SET migration_state='FROZEN',migration_watermark=REPEAT('a',64),migration_frozen_revision=1`);
            assert.throws(() => sql(`UPDATE ${table} SET migration_frozen_revision=-1`), /Check constraint/);
            sql(`UPDATE ${table} SET migration_state='ABORT_DECIDED'`);
            assert.equal(sql(`SELECT received_count FROM ${table}`), '3');
            assert.equal(sql(`SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics
                WHERE table_schema='sourcefence' AND table_name='${table}' AND index_name='${table === 'mkt_coupon' ? 'ix_mkt' : 'ix_pmt'}_migration_batch'`),
            'tenant_no,migration_state,migration_batch_no,id');
        }
    } finally { docker(['rm', '--force', name]); }
});
