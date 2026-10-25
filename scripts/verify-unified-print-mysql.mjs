// Real-database compatibility check: never connects to application or production databases.
import assert from 'node:assert/strict';
import {createHash, randomUUID} from 'node:crypto';
import {execFileSync, spawnSync} from 'node:child_process';
import {readFileSync, writeFileSync} from 'node:fs';

const migration = name => readFileSync(new URL('../backend/shop-app/src/main/resources/db/migration/' + name,
    import.meta.url), 'utf8');
const frozen = new Map([
    ['V567__shared_print_delivery.sql', '55aaabadf0ed1d931def347b3f5e39687de69fb2befe775db1d948a65cb4852e'],
    ['V579__print_device_revocation_epoch.sql', 'c0501af9dce01cb33d5e791d9597c10761b541538aac78c861f26aef8e713938'],
]);
for (const [name, expected] of frozen) {
    assert.equal(createHash('sha256').update(migration(name)).digest('hex'), expected,
        `Applied migration changed: ${name}`);
}
const additive = ['V582__unified_print_capabilities.sql', 'V583__order_print_fact.sql', 'V584__print_permissions.sql'];
const name = 'ai-shop-print-unified-' + randomUUID();
const docker = args => execFileSync('docker', args, {encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe']}).trim();
function sql(database, input, expectedSuccess = true) {
    const result = spawnSync('docker', ['exec', '-i', name, 'mysql', '-uroot', '--batch',
        '--skip-column-names', ...(database ? [database] : [])], {input, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024});
    if (expectedSuccess) assert.equal(result.status, 0, result.stderr);
    else assert.notEqual(result.status, 0, 'Expected database rejection');
    return result.stdout.trim();
}
// These are focused permission-migration fixtures, not replacements for the application schema.
const roles = `
CREATE TABLE mch_role (id BIGINT PRIMARY KEY,entity_no VARCHAR(32),builtin TINYINT,
 role_code VARCHAR(32),deleted TINYINT,perms JSON,updated_at DATETIME,version BIGINT DEFAULT 0);
INSERT INTO mch_role VALUES(1,'*',1,'MANAGER',0,'["biz:order:view"]',NOW(),0),
 (2,'*',1,'CLERK',0,'["biz:order:view"]',NOW(),0),(3,'CUSTOM',0,'MANAGER',0,'["custom:owned"]',NOW(),0);
CREATE TABLE sys_function_point(point_code VARCHAR(64) PRIMARY KEY,function_code VARCHAR(64),name VARCHAR(128),
 group_name VARCHAR(64),href VARCHAR(512),ui_perm_code VARCHAR(64),perm_code VARCHAR(64),
 backend_status VARCHAR(32),ui_ready TINYINT,matrix_code VARCHAR(64),point_type VARCHAR(32),sort INT,
 created_at DATETIME,updated_at DATETIME);
CREATE TABLE sys_role_point(role_code VARCHAR(64),point_code VARCHAR(64),end_code VARCHAR(16),
 created_at DATETIME,updated_at DATETIME,PRIMARY KEY(role_code,point_code,end_code));
`;
function seedLegacy(database) {
    sql(database, `INSERT INTO prn_device(tenant_no,entity_no,store_no,device_no,vendor,connection_type,
 credential_ref,bridge_actor_no,status,capabilities,created_at,updated_at,authorization_version)
 VALUES('MAIN','E1','S1','D1','TEST','LOCAL','C1','BRIDGE','ACTIVE','{}',NOW(),NOW(),7);
INSERT INTO prn_template(tenant_no,entity_no,store_no,template_no,template_code,revision,scene,content,
 status,created_at,updated_at) VALUES('MAIN','E1','S1','T1','OLD',1,'ORDER','{{document}}','PUBLISHED',NOW(),NOW());
INSERT INTO prn_route(tenant_no,entity_no,store_no,route_no,scene,device_no,template_no,copies,
 match_rule,created_at,updated_at) VALUES('MAIN','E1','S1','R1','ORDER','D1','T1',1,'{}',NOW(),NOW());`);
    for (const [index, status] of [[1, 'UNKNOWN'], [2, 'SENT'], [3, 'SUCCESS']]) {
        sql(database, `INSERT INTO prn_job(tenant_no,entity_no,store_no,job_no,source_event_no,scene,
 device_no,part_no,copy_index,template_no,document_snapshot,rendered_content,status,original_job_no,
 dedup_key,request_hash,event_root_key,requested_by,lease_until,current_attempt_no,lease_token,
 attempt_count,created_at,updated_at,version)
 VALUES('MAIN','E1','S1','J${index}','EV${index}','ORDER','D1','P1',1,'T1',
 '{"schemaVersion":1,"scene":"ORDER","sourceRef":"OLD-${index}","title":"Frozen receipt","currency":"CNY","lines":[{"label":"Legacy item","quantity":"1","amountMinor":100,"note":null}],"notice":"OLD"}',
 'Frozen original receipt ${index}','${status}',${index === 3 ? "'J1'" : 'NULL'},
 SHA2('DEDUP${index}',256),SHA2('REQUEST${index}',256),SHA2('EVENT${index}',256),'STAFF',
 '2099-01-01 00:00:00.000','A${index}','opaque-old-token-${index}',1,NOW(),NOW(),${index});
INSERT INTO prn_attempt(tenant_no,entity_no,store_no,attempt_no,job_no,sequence_no,status,
 started_at,lease_token,bridge_actor_no,created_at,updated_at)
 VALUES('MAIN','E1','S1','A${index}','J${index}',1,'${status}',NOW(),'opaque-old-token-${index}',
 'BRIDGE',NOW(),NOW());`);
    }
}
function legacySnapshot(database) {
    return sql(database, `SELECT job_no,status,original_job_no,document_snapshot,rendered_content,
 dedup_key,request_hash,event_root_key,lease_token,lease_until,current_attempt_no,attempt_count,version
 FROM prn_job ORDER BY id;
SELECT attempt_no,job_no,status,sequence_no,lease_token,bridge_actor_no,started_at,finished_at
 FROM prn_attempt ORDER BY id;
SELECT device_no,bridge_actor_no,status,credential_ref,authorization_version FROM prn_device;`);
}
const result = {status: 'RUNNING', database: 'MySQL 9.7.2', migrations: [...frozen.keys(), ...additive],
    checks: [], limits: ['Focused printing migrations with minimal role fixtures; not full application Flyway acceptance.',
        'No printer, provider, application database or production connection.']};
docker(['run', '--pull', 'never', '--detach', '--name', name, '--network', 'none', '--tmpfs',
    '/var/lib/mysql', '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', 'mysql:9.7.2']);
try {
    let ready = false;
    for (let i = 0; i < 90; i++) {
        try {
            if (docker(['exec', name, 'cat', '/proc/1/comm']) !== 'mysqld') throw new Error('Initializing');
            docker(['exec', name, 'mysql', '-uroot', '-e', 'SELECT 1']); ready = true; break;
        }
        catch { await new Promise(resolve => setTimeout(resolve, 500)); }
    }
    assert(ready, 'Disposable MySQL did not become ready');
    sql(null, 'CREATE DATABASE print_empty; CREATE DATABASE print_upgrade;');
    for (const database of ['print_empty', 'print_upgrade']) {
        sql(database, roles);
        for (const file of frozen.keys()) sql(database, migration(file));
        if (database === 'print_upgrade') seedLegacy(database);
        const before = legacySnapshot(database);
        for (const file of additive) sql(database, migration(file));
        assert.equal(legacySnapshot(database), before, 'Migration changed legacy identity/status/snapshot/lease/version');
        assert.equal(sql(database, `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
 AND table_name IN ('prn_device','prn_template','prn_route','prn_job','prn_attempt');`), '5');
        assert.equal(sql(database, "SELECT JSON_CONTAINS(perms,JSON_QUOTE('biz:print:read')) FROM mch_role WHERE id=1;"), '1');
        assert.equal(sql(database, "SELECT CAST(perms AS CHAR) FROM mch_role WHERE id=3;"), '["custom:owned"]');
        const permissionSnapshot = sql(database, 'SELECT id,CAST(perms AS CHAR) FROM mch_role ORDER BY id;');
        sql(database, migration(additive[2]));
        assert.equal(sql(database, 'SELECT id,CAST(perms AS CHAR) FROM mch_role ORDER BY id;'), permissionSnapshot,
            'Permission append is not idempotent');
        result.checks.push(database === 'print_empty' ? 'empty-printing-install' : 'legacy-upgrade-preserves-frozen-data-and-leases');
        if (database === 'print_upgrade') {
            assert.equal(sql(database, 'SELECT active_job_no,active_attempt_no FROM prn_device WHERE device_no=\'D1\';'), 'J1\tA1');
            assert.equal(sql(database, 'SELECT COUNT(*) FROM prn_job WHERE device_no=\'D1\' AND status IN (\'SENT\',\'UNKNOWN\');'), '2');
            assert.equal(sql(database, 'SELECT COUNT(*) FROM prn_attempt WHERE authorization_epoch=7;'), '3');
            assert.equal(sql(database, 'SELECT COUNT(*) FROM prn_job WHERE rich_document_snapshot IS NOT NULL;'), '0');
            result.checks.push('legacy-uncertainty-gate-and-revocation-epoch-backfill');
        }
    }
    result.checks.push('applied-migration-hashes-frozen', 'permission-append-idempotent', 'custom-role-unchanged');
    result.status = 'PASS';
    const output = process.argv.find(arg => arg.startsWith('--output='))?.slice('--output='.length);
    if (output) writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify(result));
} finally {
    docker(['rm', '--force', name]);
}
