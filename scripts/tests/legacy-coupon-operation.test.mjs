import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {test} from 'node:test';
import assert from 'node:assert/strict';

test('native legacy operation distinguishes a no-hold abort and requires every financial sequence', async()=>{
    const name='codex-legacy-coupon-op-'+randomUUID();
    const docker=args=>execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=input=>execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','legacyop'],
        {input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=legacyop','mysql:9.7.2']);
    try {
        let ready=false;
        for(let i=0;i<60;i++) {
            try {sql('SELECT 1');ready=true;break;}
            catch {await new Promise(resolve=>setTimeout(resolve,500));}
        }
        assert.ok(ready);
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V518__legacy_coupon_pay_operation.sql',
            import.meta.url),'utf8'));
        const insert=no=>"INSERT INTO ent_legacy_coupon_operation(tenant_no,operation_no,checkout_no,origin_system,origin_no,"
            +"instance_no,user_no,entity_no,currency,owner_batch_no,owner_plan_hash,economic_hash,command_hash,source_version,"
            +"source_snapshot,context_snapshot,status,ever_held,created_at,updated_at,created_by,updated_by)"
            +" VALUES('MAIN','"+no+"','C"+no+"','PMT','OLD','I1','U1','M1','CNY','B1',REPEAT('a',64),REPEAT('b',64),"
            +"REPEAT('c',64),1,'{}','{}','PREPARING',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'T','T')";
        sql(insert('A1')); sql(insert('H1'));
        assert.throws(()=>sql(insert('A1')),/Duplicate entry/);
        sql("UPDATE ent_legacy_coupon_operation SET status='RELEASED',abort_decision_no='A',abort_snapshot='{}',"
            +"released_at=UTC_TIMESTAMP(3) WHERE operation_no='A1'");
        assert.equal(sql("SELECT COUNT(*) FROM ent_legacy_coupon_operation WHERE operation_no='A1' AND discount_minor IS NULL"
            +" AND allocation_snapshot IS NULL AND hold_asset_seq IS NULL"),'1');
        assert.throws(()=>sql("UPDATE ent_legacy_coupon_operation SET discount_minor=0 WHERE operation_no='A1'"),/Check constraint/);
        const held="ever_held=1,status='HELD',discount_minor=100,allocation_snapshot='[]',rule_snapshot='{}',"
            +"hold_asset_seq=2,held_at=UTC_TIMESTAMP(3)";
        assert.throws(()=>sql("UPDATE ent_legacy_coupon_operation SET "+held.replace('hold_asset_seq=2','hold_asset_seq=NULL')
            +" WHERE operation_no='H1'"),/Check constraint/);
        sql("UPDATE ent_legacy_coupon_operation SET "+held+" WHERE operation_no='H1'");
        const commit="status='COMMITTED',commit_asset_seq=3,commit_decision_no='D',source_order_no='O',commit_snapshot='{}',"
            +"committed_at=UTC_TIMESTAMP(3),source_committed_at=UTC_TIMESTAMP(3)";
        assert.throws(()=>sql("UPDATE ent_legacy_coupon_operation SET "+commit.replace('commit_asset_seq=3','commit_asset_seq=NULL')
            +" WHERE operation_no='H1'"),/Check constraint/);
        sql("UPDATE ent_legacy_coupon_operation SET "+commit+" WHERE operation_no='H1'");
        const reverse="status='REVERSED',reverse_asset_seq=4,reverse_decision_no='R',reverse_snapshot='{}',"
            +"reversed_at=UTC_TIMESTAMP(3),source_reversed_at=UTC_TIMESTAMP(3)";
        assert.throws(()=>sql("UPDATE ent_legacy_coupon_operation SET "+reverse.replace('reverse_asset_seq=4','reverse_asset_seq=NULL')
            +" WHERE operation_no='H1'"),/Check constraint/);
        sql("UPDATE ent_legacy_coupon_operation SET "+reverse+" WHERE operation_no='H1'");
        assert.equal(sql("SELECT COUNT(*) FROM ent_legacy_coupon_operation WHERE status='REVERSED'"),'1');
        assert.throws(()=>sql("UPDATE ent_legacy_coupon_operation SET source_version=2 WHERE operation_no='H1'"),/Check constraint/);
        assert.throws(()=>sql("UPDATE ent_legacy_coupon_operation SET source_snapshot='not-json' WHERE operation_no='H1'"),/Invalid JSON/);
    } finally {docker(['rm','--force',name]);}
});


test('native core checkout journal rejects terminal states without original decisions and holds', async()=>{
    const name='codex-legacy-checkout-'+randomUUID();
    const docker=args=>execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=input=>execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','legacycheckout'],
        {input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=legacycheckout','mysql:9.7.2']);
    try {
        let ready=false;
        for(let i=0;i<60;i++) {
            try {sql('SELECT 1');ready=true;break;}
            catch {await new Promise(resolve=>setTimeout(resolve,500));}
        }
        assert.ok(ready);
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V519__legacy_coupon_core_checkout.sql',import.meta.url),'utf8'));
        sql(`INSERT INTO ord_legacy_coupon_checkout(tenant_no,checkout_no,operation_no,user_no,entity_no,command_hash,
            stage_hash,economic_hash,fact_snapshot,status,created_at,updated_at,created_by,updated_by)
            VALUES('MAIN','C1','O1','U1','M1',REPEAT('a',64),REPEAT('b',64),REPEAT('c',64),'{}','OPEN',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'U1','U1')`);
        assert.throws(()=>sql("UPDATE ord_legacy_coupon_checkout SET status='COMMITTED',order_no='SO1',decision_snapshot='{}' WHERE checkout_no='C1'"),/Check constraint/);
        sql("UPDATE ord_legacy_coupon_checkout SET status='COMMITTED',order_no='SO1',decision_snapshot='{}',held_snapshot='{}' WHERE checkout_no='C1'");
        assert.throws(()=>sql("UPDATE ord_legacy_coupon_checkout SET status='ABORTED' WHERE checkout_no='C1'"),/Check constraint/);
        assert.throws(()=>sql("UPDATE ord_legacy_coupon_checkout SET deleted=1 WHERE checkout_no='C1'"),/Check constraint/);
        assert.throws(()=>sql("UPDATE ord_legacy_coupon_checkout SET fact_snapshot='invalid' WHERE checkout_no='C1'"),/Invalid JSON/);
        assert.equal(sql('SELECT status FROM ord_legacy_coupon_checkout'),'COMMITTED');
    } finally {docker(['rm','--force',name]);}
});

test('native upgrade keeps unknown delivery pending and binds acknowledgments to current source decisions', async()=>{
    const name='codex-legacy-ack-'+randomUUID();
    const docker=args=>execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=input=>execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','legacyack'],
        {input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=legacyack','mysql:9.7.2']);
    try {
        let ready=false;
        for(let i=0;i<60;i++) {
            try {sql('SELECT 1');ready=true;break;}
            catch {await new Promise(resolve=>setTimeout(resolve,500));}
        }
        assert.ok(ready);
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V519__legacy_coupon_core_checkout.sql',import.meta.url),'utf8'));
        const insert=no=>`INSERT INTO ord_legacy_coupon_checkout(tenant_no,checkout_no,operation_no,user_no,entity_no,command_hash,
            stage_hash,economic_hash,fact_snapshot,status,created_at,updated_at,created_by,updated_by)
            VALUES('MAIN','${no}','OP-${no}','U1','M1',REPEAT('a',64),REPEAT('b',64),REPEAT('c',64),'{}','OPEN',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'U1','U1')`;
        sql(insert('C1'));
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V520__legacy_coupon_delivery_recovery.sql',import.meta.url),'utf8'));
        assert.equal(sql("SELECT CONCAT(pay_state,':',recovery_due_at IS NULL,':',pay_decision_no IS NULL) FROM ord_legacy_coupon_checkout WHERE checkout_no='C1'"),'PENDING:1:1');
        assert.throws(()=>sql("UPDATE ord_legacy_coupon_checkout SET pay_state='COMMITTED',pay_decision_no='D1',pay_acked_at=UTC_TIMESTAMP(3) WHERE checkout_no='C1'"),/Check constraint/);
        sql("UPDATE ord_legacy_coupon_checkout SET status='COMMITTED',order_no='SO1',held_snapshot='{}',decision_snapshot='{}' WHERE checkout_no='C1'");
        sql("UPDATE ord_legacy_coupon_checkout SET pay_state='COMMITTED',pay_decision_no='D1',pay_acked_at=UTC_TIMESTAMP(3) WHERE checkout_no='C1'");
        assert.throws(()=>sql("UPDATE ord_legacy_coupon_checkout SET recovery_due_at=UTC_TIMESTAMP(3) WHERE checkout_no='C1'"),/Check constraint/);
        assert.throws(()=>sql("UPDATE ord_legacy_coupon_checkout SET reversal_snapshot='{}' WHERE checkout_no='C1'"),/Check constraint/);
        sql("UPDATE ord_legacy_coupon_checkout SET reversal_snapshot='{}',pay_state='PENDING',pay_decision_no=NULL,pay_acked_at=NULL,recovery_due_at=UTC_TIMESTAMP(3) WHERE checkout_no='C1'");
        sql("UPDATE ord_legacy_coupon_checkout SET pay_state='REVERSED',pay_decision_no='R1',pay_acked_at=UTC_TIMESTAMP(3),recovery_due_at=NULL WHERE checkout_no='C1'");
        sql(insert('Q1'));
        sql("UPDATE ord_legacy_coupon_checkout SET pay_state='NONE' WHERE checkout_no='Q1'");
        assert.throws(()=>sql("UPDATE ord_legacy_coupon_checkout SET held_snapshot='{}' WHERE checkout_no='Q1'"),/Check constraint/);
        assert.equal(sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='legacyack' AND index_name='ix_legacy_checkout_recovery'"),'4');
    } finally {docker(['rm','--force',name]);}
});

test('native usage upgrade keeps standard store guarantees and records real legacy operations', async()=>{
    const name='codex-legacy-usage-'+randomUUID();
    const docker=args=>execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=input=>execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','legacyusage'],
        {input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=legacyusage','mysql:9.7.2']);
    try {
        let ready=false;
        for(let i=0;i<60;i++) {
            try {sql('SELECT 1');ready=true;break;}
            catch {await new Promise(resolve=>setTimeout(resolve,500));}
        }
        assert.ok(ready);
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V489__entitlement_use_and_daily_quota.sql',import.meta.url),'utf8'));
        const insert=`INSERT INTO ent_usage(tenant_no,usage_no,operation_no,user_no,entity_no,store_no,store_name,asset_kind,title,units,amount_minor,currency,items_snapshot,source_order_no,operator_no,status,occurred_at,created_at,updated_at,created_by,updated_by)
            VALUES('MAIN','U1','OP1','BUYER','M1','S1','Store','ORDER_COUPON','Coupon',1,100,'CNY','[]','SO1','T','COMMITTED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'T','T')`;
        sql(insert);
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V521__legacy_coupon_usage_facts.sql',import.meta.url),'utf8'));
        assert.equal(sql("SELECT protocol FROM ent_usage WHERE usage_no='U1'"),'STANDARD');
        assert.throws(()=>sql("UPDATE ent_usage SET store_no=NULL WHERE usage_no='U1'"),/Check constraint/);
        sql("UPDATE ent_usage SET protocol='LEGACY',legacy_instance_no='I1',store_no=NULL,store_name=NULL WHERE usage_no='U1'");
        for(const change of ["amount_minor=0","units=NULL","currency=NULL","source_order_no=NULL","asset_kind='STORED_VALUE'","legacy_instance_no=NULL"])
            assert.throws(()=>sql("UPDATE ent_usage SET "+change+" WHERE usage_no='U1'"),/Check constraint/);
        assert.equal(sql("SELECT CONCAT(operation_no,':',amount_minor) FROM ent_usage WHERE usage_no='U1'"),'OP1:100');
    } finally {docker(['rm','--force',name]);}
});
