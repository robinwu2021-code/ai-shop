import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {test} from 'node:test';
import assert from 'node:assert/strict';

test('V462/V463 evidence stage preserves legacy rows and enforces exact origin/request identity and cannot become import-ready', async () => {
    const name='codex-evidence-stage-'+randomUUID();
    const docker=args => execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=input => execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','evidence'],
        {input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=evidence','mysql:9.7.2']);
    try {
        let ready=false;
        for(let i=0;i<60;i++) {
            try { sql('SELECT 1'); ready=true; break; }
            catch { await new Promise(resolve => setTimeout(resolve,500)); }
        }
        assert.ok(ready);
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V462__entitlement_migration_evidence_stage.sql',
            import.meta.url),'utf8'));
        const insert=(no,key,batch,system='PMT') => "INSERT INTO ent_migration_stage(tenant_no,stage_no,source_system,"
            +"source_template_no,migration_batch_no,entity_no,request_key,command_hash,reason,source_schema_version,"
            +"frozen_revision,watermark,content_hash,crypto_key_id,payload_cipher,payload_bytes,instance_count,"
            +"history_count,status,created_at,updated_at,created_by,updated_by) VALUES('MAIN','"+no+"','"+system+"',"
            +"'T1','"+batch+"','M1','"+key+"',REPEAT('a',64),'verified rehearsal',1,2,REPEAT('b',64),"
            +"REPEAT('c',64),'TEST','v1.TEST.nonce.cipher',10,1,0,'STAGED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP','OP')";
        sql(insert('MS1','KEY','B1'));
        sql(insert('MS2','key','B2'));
        sql(insert('MS3','THIRD','B1','MKT_LEGACY'));
        assert.throws(() => sql(insert('MS4','KEY','B4')),/Duplicate entry/);
        assert.throws(() => sql(insert('MS5','FIFTH','B1')),/Duplicate entry/);
        for(const mutation of ["status='READY'","source_system='PAY'","source_schema_version=2","frozen_revision=-1",
            "payload_bytes=8388609","payload_bytes=0","instance_count=-1","history_count=-1","reason=' '",
            "crypto_key_id=' '","content_hash='short'"]) {
            assert.throws(() => sql("UPDATE ent_migration_stage SET "+mutation+" WHERE stage_no='MS1'"),/Check constraint/);
        }
        assert.equal(sql('SELECT COUNT(*) FROM ent_migration_stage'),'3');
        assert.equal(sql("SELECT status FROM ent_migration_stage WHERE stage_no='MS1'"),'STAGED');
        const original=sql("SELECT CONCAT(payload_cipher,':',command_hash,':',content_hash) FROM ent_migration_stage WHERE stage_no='MS1'");
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V463__entitlement_manifest_stage_metadata.sql',
            import.meta.url),'utf8'));
        assert.equal(sql("SELECT CONCAT(payload_cipher,':',command_hash,':',content_hash) FROM ent_migration_stage WHERE stage_no='MS1'"),original);
        assert.equal(sql("SELECT CONCAT(manifest_version,':',history_available) FROM ent_migration_stage WHERE stage_no='MS1'"),'1:1');
        assert.equal(sql("SELECT CONCAT(manifest_version,':',history_available) FROM ent_migration_stage WHERE stage_no='MS3'"),'1:0');
        // A rolling old writer can still omit the new metadata on a V1 row.
        sql(insert('MS6','SIXTH','B6','MKT_LEGACY'));
        assert.equal(sql("SELECT COUNT(*) FROM ent_migration_stage WHERE stage_no='MS6' AND history_available IS NULL"),'1');
        sql("UPDATE ent_migration_stage SET manifest_version=2 WHERE stage_no='MS1'");
        for(const change of ["history_available=NULL","history_available=0","manifest_version=3"]) {
            assert.throws(() => sql("UPDATE ent_migration_stage SET "+change+" WHERE stage_no='MS1'"),/Check constraint/);
        }
        sql("UPDATE ent_migration_stage SET manifest_version=2 WHERE stage_no='MS3'");
    } finally { docker(['rm','--force',name]); }
});

test('native fixed target plan cannot masquerade as source commitment or imported assets',async()=>{
    const name='codex-target-plan-'+randomUUID();
    const docker=args=>execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=input=>execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','targetplan'],
        {input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=targetplan','mysql:9.7.2']);
    try {
        let ready=false;
        for(let i=0;i<60;i++) {
            try {sql('SELECT 1');ready=true;break;}
            catch {await new Promise(resolve=>setTimeout(resolve,500));}
        }
        assert.ok(ready);
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V469__legacy_coupon_target_plan.sql',import.meta.url),'utf8'));
        sql(`INSERT INTO ent_migration_plan(tenant_no,plan_no,stage_no,source_system,source_template_no,migration_batch_no,
            entity_no,request_key,command_hash,reason,plan_hash,source_bundle_hash,crypto_key_id,payload_cipher,payload_bytes,
            asset_count,archival_count,version_count,assessed_at,status,created_at,updated_at,created_by,updated_by)
            VALUES('MAIN','P1','S1','PMT','T1','B1','M1','K1',REPEAT('a',64),'plan',REPEAT('b',64),REPEAT('c',64),'TEST',
            'opaque-cipher',1,1,0,2,UTC_TIMESTAMP(3),'PLANNED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'T','T')`);
        for(const change of ["status='IMPORTED'","status='COMMIT_DECIDED'","deleted=1","payload_bytes=0","asset_count=-1","version_count=0"])
            assert.throws(()=>sql('UPDATE ent_migration_plan SET '+change+" WHERE plan_no='P1'"),/Check constraint/);
        assert.throws(()=>sql("UPDATE ent_migration_plan SET source_system='MKT_LEGACY',alias_no='A1' WHERE plan_no='P1'"),/Check constraint/);
        assert.equal(sql("SELECT status FROM ent_migration_plan WHERE plan_no='P1'"),'PLANNED');
    } finally {docker(['rm','--force',name]);}
});

test('native core source decision and binding remain immutable permanent evidence',async()=>{
    const name='codex-source-decision-'+randomUUID();
    const docker=args=>execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=input=>execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','sourcedecision'],
        {input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=sourcedecision','mysql:9.7.2']);
    try {
        let ready=false;
        for(let i=0;i<60;i++) {
            try {sql('SELECT 1');ready=true;break;}
            catch {await new Promise(resolve=>setTimeout(resolve,500));}
        }
        assert.ok(ready);
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V470__legacy_coupon_source_commit.sql',import.meta.url),'utf8'));
        sql(`INSERT INTO pmt_migration_decision(tenant_no,decision_no,plan_no,entity_no,migration_batch_no,plan_hash,source_bundle_hash,
            plan_snapshot,command_hash,request_key,reason,actor_no,decided_at,status,created_at,updated_at,created_by,updated_by)
            VALUES('MAIN','D1','P1','M1','B1',REPEAT('a',64),REPEAT('b',64),'{}',REPEAT('c',64),'K1','commit','OP1',
            UTC_TIMESTAMP(3),'COMMITTED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP1','OP1')`);
        sql("INSERT INTO pmt_migration_source(tenant_no,source_system,source_template_no,decision_no,created_at,updated_at,created_by,updated_by) VALUES('MAIN','PMT','T1','D1',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP1','OP1')");
        for(const change of ["status='ABORTED'","version=1","deleted=1"])
            assert.throws(()=>sql('UPDATE pmt_migration_decision SET '+change),/Check constraint/);
        assert.throws(()=>sql("UPDATE pmt_migration_source SET source_system='PAY'"),/Check constraint/);
        assert.throws(()=>sql("INSERT INTO pmt_migration_source(tenant_no,source_system,source_template_no,decision_no,created_at,updated_at,created_by,updated_by) VALUES('MAIN','PMT','T1','D2',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP1','OP1')"),/Duplicate entry/);
        assert.equal(sql("SELECT decision_no FROM pmt_migration_source WHERE source_template_no='T1'"),'D1');
    } finally {docker(['rm','--force',name]);}
});

test('native pay import keeps one receipt and archival mappings cannot create phantom assets',async()=>{
    const name='codex-target-import-'+randomUUID();
    const docker=args=>execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=input=>execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','targetimport'],
        {input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=targetimport','mysql:9.7.2']);
    try {
        let ready=false;
        for(let i=0;i<60;i++) {
            try {sql('SELECT 1');ready=true;break;}
            catch {await new Promise(resolve=>setTimeout(resolve,500));}
        }
        assert.ok(ready);
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V471__legacy_coupon_target_import.sql',import.meta.url),'utf8'));
        sql(`INSERT INTO ent_migration_import(tenant_no,import_no,plan_no,entity_no,source_decision_no,plan_hash,source_bundle_hash,
            proof_snapshot,asset_count,archival_count,imported_at,status,created_at,updated_at,created_by,updated_by)
            VALUES('MAIN','I1','P1','M1','D1',REPEAT('a',64),REPEAT('b',64),'[]',1,1,UTC_TIMESTAMP(3),'IMPORTED',
            UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'T','T')`);
        for(const change of ["status='PREPARED'","deleted=1","version=1","asset_count=-1"])
            assert.throws(()=>sql('UPDATE ent_migration_import SET '+change),/Check constraint/);
        sql("INSERT INTO ent_migration_asset(tenant_no,import_no,source_system,source_instance_no,archival_only,total_units,baseline_remaining_units,created_at,updated_at,created_by,updated_by) VALUES('MAIN','I1','PMT','OLD1',1,2,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'T','T')");
        assert.throws(()=>sql("UPDATE ent_migration_asset SET instance_no='PHANTOM'"),/Check constraint/);
        assert.throws(()=>sql("UPDATE ent_migration_asset SET baseline_remaining_units=3"),/Check constraint/);
        sql("UPDATE ent_migration_asset SET archival_only=0,instance_no='EI1',baseline_no='BASE1'");
        assert.throws(()=>sql("INSERT INTO ent_migration_asset(tenant_no,import_no,source_system,source_instance_no,archival_only,total_units,baseline_remaining_units,created_at,updated_at,created_by,updated_by) VALUES('MAIN','I2','PMT','OLD1',1,2,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'T','T')"),/Duplicate entry/);
        assert.equal(sql('SELECT COUNT(*) FROM ent_migration_asset'),'1');
    } finally {docker(['rm','--force',name]);}
});

test('native history upgrade namespaces real operation IDs and preserves known-zero/unknown facts',async()=>{
    const name='codex-history-usage-'+randomUUID();
    const docker=args=>execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=input=>execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','historyusage'],
        {input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=historyusage','mysql:9.7.2']);
    try {
        let ready=false;
        for(let i=0;i<60;i++) {try {sql('SELECT 1');ready=true;break;}catch {await new Promise(resolve=>setTimeout(resolve,500));}}
        assert.ok(ready);
        for(const file of ['V436__entitlement_use_and_daily_quota.sql','V468__legacy_coupon_usage_facts.sql','V471__legacy_coupon_target_import.sql','V472__legacy_coupon_historical_usage.sql'])
            sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/'+file,import.meta.url),'utf8'));
        const insert=(no,protocol,extra)=>`INSERT INTO ent_usage(tenant_no,usage_no,operation_no,protocol,migration_import_no,user_no,entity_no,
            store_no,store_name,asset_kind,title,units,amount_minor,currency,items_snapshot,operator_no,status,occurred_at,created_at,updated_at,created_by,updated_by)
            VALUES('MAIN','${no}','SAME','${protocol}',${extra},'U1','M1','S1','Store','ORDER_COUPON','Coupon',1,0,'CNY','[]','OP1','COMMITTED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'T','T')`;
        sql(insert('NATIVE','STANDARD','NULL'));sql(insert('HISTORY','MIGRATED',"'MI1'"));
        sql("UPDATE ent_usage SET store_no=NULL,store_name=NULL,operator_no=NULL WHERE usage_no='HISTORY'");
        assert.throws(()=>sql("UPDATE ent_usage SET operator_no=NULL WHERE usage_no='NATIVE'"),/Check constraint/);
        assert.throws(()=>sql("UPDATE ent_usage SET amount_minor=NULL WHERE usage_no='HISTORY'"),/Check constraint/);
        assert.throws(()=>sql("UPDATE ent_usage SET migration_import_no=NULL WHERE usage_no='HISTORY'"),/Check constraint/);
        assert.throws(()=>sql(insert('DUP','MIGRATED',"'MI1'")),/Duplicate entry/);
        assert.equal(sql('SELECT COUNT(*) FROM ent_usage'),'2');
    } finally {docker(['rm','--force',name]);}
});
