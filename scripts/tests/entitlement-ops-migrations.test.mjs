import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {test} from 'node:test';
import assert from 'node:assert/strict';

// No ports, no network and no configured business database: this exercises only the real incremental SQL.
test('V452–V461 enforce exception audit, reconciliation, freeze permission and exact ownership constraints', async () => {
    const name=`codex-ent-ops-${randomUUID()}`;
    const docker=args => execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=value => execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','entops'],
        {input:value,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    const migration=file => readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/'+file,
        import.meta.url),'utf8');
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=entops','mysql:9.7.2']);
    try {
        let ready=false;
        for(let n=0;n<60;n++) {
            try { sql('SELECT 1'); ready=true; break; }
            catch { await new Promise(resolve => setTimeout(resolve,500)); }
        }
        assert.ok(ready,'isolated MySQL must start');
        sql(migration('V487__entitlement_grants_and_ledger.sql'));
        sql(migration('V512__entitlement_lifecycle_scan.sql'));
        assert.equal(sql("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
            + "WHERE table_schema='entops' AND table_name='ent_instance' AND index_name='ix_instance_expiry'"),
            'tenant_no,status,effective_until,id');
        sql(migration('V505__pay_entitlement_exception.sql'));
        sql(`INSERT INTO pay_ent_exception(tenant_no,exception_no,entity_no,kind,biz_no,status,evidence,
            created_at,updated_at,created_by,updated_by) VALUES('MAIN','EX1','M1','GRANT_DELIVERY','G1','OPEN','{}',
            UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP','OP')`);
        assert.throws(() => sql("UPDATE pay_ent_exception SET status='UNKNOWN'"),/Check constraint/);
        assert.throws(() => sql("UPDATE pay_ent_exception SET evidence='invalid'"),/Invalid JSON/);
        assert.throws(() => sql(`INSERT INTO pay_ent_exception(tenant_no,exception_no,entity_no,kind,biz_no,status,
            evidence,created_at,updated_at,created_by,updated_by) SELECT tenant_no,'EX2',entity_no,kind,biz_no,status,
            evidence,created_at,updated_at,created_by,updated_by FROM pay_ent_exception`),/Duplicate entry/);
        sql(migration('V506__entitlement_exception_action.sql'));
        sql(`INSERT INTO pay_ent_exception_action(tenant_no,action_no,exception_no,request_key,payload_hash,reason,
            entity_no,actor_no,status,created_at,updated_at,created_by,updated_by)
            VALUES('MAIN','EA1','EX1','KEY',REPEAT('a',64),'verified','M1','OP','PENDING',
            UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP','OP')`);
        assert.throws(() => sql("UPDATE pay_ent_exception_action SET status='DONE'"),/Check constraint/);
        sql(migration('V507__exception_action_kind.sql'));
        assert.equal(sql('SELECT operation_kind FROM pay_ent_exception_action'),'RETRY_GRANT');
        assert.throws(() => sql("UPDATE pay_ent_exception_action SET operation_kind='ADJUST_BALANCE'"),/Check constraint/);
        sql("UPDATE pay_ent_exception_action SET status='DONE',response_snapshot='{}',operation_kind='QUERY_SOURCE'");
        sql(migration('V514__exception_event_recovery_actions.sql'));
        sql("UPDATE pay_ent_exception_action SET operation_kind='RETRY_EVENT'");
        sql("UPDATE pay_ent_exception_action SET operation_kind='QUERY_EVENT'");
        assert.throws(() => sql("UPDATE pay_ent_exception_action SET operation_kind='ADJUST_BALANCE'"),/Check constraint/);
        assert.equal(sql("SELECT operation_kind FROM pay_ent_exception_action"),'QUERY_EVENT');
        // Bin collation must keep differently cased durable request keys distinct.
        sql(`INSERT INTO pay_ent_exception_action(tenant_no,action_no,exception_no,request_key,payload_hash,reason,
            entity_no,actor_no,status,created_at,updated_at,created_by,updated_by)
            SELECT tenant_no,'EA2',exception_no,'key',payload_hash,reason,entity_no,actor_no,'PENDING',
            created_at,updated_at,created_by,updated_by FROM pay_ent_exception_action WHERE action_no='EA1'`);
        const iam=migration('V62__perm_config.sql');
        for(const table of ['sys_function_point','sys_role_point']) {
            const ddl=iam.match(new RegExp(`CREATE TABLE IF NOT EXISTS ${table}\\s*\\([\\s\\S]*?;`));
            assert.ok(ddl,`missing original IAM table ${table}`); sql(ddl[0]);
        }
        const permissions=migration('V508__entitlement_ops_permissions.sql');
        sql(permissions); sql(permissions);
        assert.equal(sql("SELECT COUNT(*) FROM sys_function_point WHERE point_code LIKE 'ACT__PAY_ENT_%'"),'3');
        assert.equal(sql("SELECT COUNT(*) FROM sys_role_point WHERE point_code LIKE 'ACT__PAY_ENT_%'"),'3');
        assert.equal(sql("SELECT COUNT(*) FROM sys_role_point WHERE role_code<>'SUPER_ADMIN'"),'0');
        const freezePermission=migration('V510__entitlement_freeze_permission.sql');
        sql(freezePermission); sql(freezePermission);
        assert.equal(sql("SELECT COUNT(*) FROM sys_function_point WHERE perm_code='ops:pay:ent:freeze'"),'1');
        assert.equal(sql("SELECT COUNT(*) FROM sys_role_point WHERE point_code='ACT__PAY_ENT_FREEZE'"),'1');
        assert.equal(sql("SELECT role_code FROM sys_role_point WHERE point_code='ACT__PAY_ENT_FREEZE'"),'SUPER_ADMIN');
        sql(migration('V509__entitlement_reconciliation.sql'));
        const run=(no,key,axis,currency) => `INSERT INTO pay_ent_recon_run(tenant_no,recon_no,request_key,
            payload_hash,entity_no,axis,currency,biz_date,market_zone,status,started_at,created_at,updated_at,
            created_by,updated_by) VALUES('MAIN','${no}','${key}',REPEAT('b',64),'M1','${axis}',${currency},
            '2026-10-19','Asia/Shanghai','RUNNING',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP','OP')`;
        assert.throws(() => sql(run('RR0','K0','MONEY','NULL')),/Check constraint/);
        sql(run('RR1','K1','MONEY',"'CNY'"));
        sql(run('RR2','k1','RIGHTS','NULL'));
        assert.throws(() => sql(run('RR3','K1','RIGHTS','NULL')),/Duplicate entry/);
        assert.throws(() => sql("UPDATE pay_ent_recon_run SET checked_count=1,diff_count=2 WHERE recon_no='RR1'"),
            /Check constraint/);
        sql("UPDATE pay_ent_recon_run SET checked_count=1,diff_count=1,status='COMPLETED' WHERE recon_no='RR1'");
        sql(`INSERT INTO pay_ent_recon_diff(tenant_no,diff_no,recon_no,entity_no,resource_no,diff_kind,
            expected_snapshot,actual_snapshot,status,created_at,updated_at,created_by,updated_by)
            VALUES('MAIN','RD1','RR1','M1','A1','MONEY_MISMATCH','{}','{}','OPEN',
            UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP','OP')`);
        assert.throws(() => sql("UPDATE pay_ent_recon_diff SET actual_snapshot='broken'"),/Invalid JSON/);
        assert.throws(() => sql(`INSERT INTO pay_ent_recon_diff(tenant_no,diff_no,recon_no,entity_no,resource_no,
            diff_kind,expected_snapshot,actual_snapshot,status,created_at,updated_at,created_by,updated_by)
            SELECT tenant_no,'RD2',recon_no,entity_no,resource_no,diff_kind,expected_snapshot,actual_snapshot,status,
            created_at,updated_at,created_by,updated_by FROM pay_ent_recon_diff`),/Duplicate entry/);
        sql(migration('V511__entitlement_owner_registry.sql'));
        const owner=(system,kind,no) => `INSERT INTO ent_owner_registry(tenant_no,source_system,source_kind,source_no,
            owner_system,migration_batch_no,status,created_at,updated_at,created_by,updated_by)
            VALUES('MAIN','${system}','${kind}','${no}','CORE_LEGACY','B1','PREPARING',
            UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP','OP')`;
        sql(owner('MKT_LEGACY','INSTANCE','SameNo'));
        sql(owner('PMT','INSTANCE','SameNo'));
        sql(owner('MKT_LEGACY','TEMPLATE','SameNo'));
        sql(owner('MKT_LEGACY','INSTANCE','SAMENO'));
        assert.throws(() => sql(owner('MKT_LEGACY','INSTANCE','SameNo')),/Duplicate entry/);
        assert.throws(() => sql("UPDATE ent_owner_registry SET owner_system='PAY' WHERE source_system='PMT'"),
            /Check constraint/);
        assert.throws(() => sql("UPDATE ent_owner_registry SET status='VERIFIED' WHERE source_system='PMT'"),
            /Check constraint/);
        sql("UPDATE ent_owner_registry SET target_no='EI1',cutover_watermark='WM',status='VERIFIED' "
            + "WHERE source_system='PMT'");
        sql("UPDATE ent_owner_registry SET owner_system='PAY',status='CUTOVER' WHERE source_system='PMT'");
        assert.equal(sql("SELECT owner_system FROM ent_owner_registry WHERE source_system='PMT'"),'PAY');
    } finally { docker(['rm','--force',name]); }
});
