import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {test} from 'node:test';
import assert from 'node:assert/strict';

test('native alias recognition preserves exact member uniqueness and cannot become a transfer decision', async () => {
    const name='codex-source-alias-'+randomUUID();
    const docker=args=>execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=input=>execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','sourcealias'],
        {input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=sourcealias','mysql:9.7.2']);
    try {
        let ready=false;
        for(let i=0;i<60;i++) {
            try {sql('SELECT 1');ready=true;break;}
            catch {await new Promise(resolve=>setTimeout(resolve,500));}
        }
        assert.ok(ready);
        sql(readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V517__entitlement_migration_alias.sql',
            import.meta.url),'utf8'));
        const insert=(no,pmt,mkt,key)=>"INSERT INTO ent_migration_alias(tenant_no,alias_no,entity_no,migration_batch_no,"
            +"pmt_stage_no,mkt_stage_no,canonical_stage_no,pmt_content_hash,mkt_content_hash,comparison_hash,"
            +"matched_instance_count,compared_at,request_key,command_hash,reason,status,created_at,updated_at,created_by,updated_by)"
            +" VALUES('MAIN','"+no+"','M1','B1','"+pmt+"','"+mkt+"','"+pmt+"',REPEAT('a',64),REPEAT('b',64),"
            +"REPEAT('c',64),1,UTC_TIMESTAMP(3),'"+key+"',REPEAT('d',64),'offline recognition','RECOGNIZED',"
            +"UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP','OP')";
        sql(insert('MA1','P1','M1','KEY'));
        for(const row of [insert('MA2','P1','M2','K2'),insert('MA3','P2','M1','K3'),insert('MA4','P4','M4','KEY')]) {
            assert.throws(()=>sql(row),/Duplicate entry/);
        }
        // Source and command identities are opaque and case-sensitive.
        sql(insert('MA5','p1','m1','key'));
        for(const mutation of ["status='READY'","status='CUTOVER'","canonical_stage_no='M1'",
            "mkt_stage_no='P1'","matched_instance_count=-1","comparison_hash='short'","reason=' '"]) {
            assert.throws(()=>sql("UPDATE ent_migration_alias SET "+mutation+" WHERE alias_no='MA1'"),/Check constraint/);
        }
        assert.equal(sql("SELECT COUNT(*) FROM ent_migration_alias WHERE status='RECOGNIZED'"),'2');
    } finally {docker(['rm','--force',name]);}
});
