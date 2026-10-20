import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {test} from 'node:test';
import assert from 'node:assert/strict';

// SQL query smoke only: use the generated source-column shape, not a claim of full native schema migration.
test('native inventory SQL includes tombstones and counts orphan history through its missing root', async () => {
    const name='codex-source-inventory-'+randomUUID();
    const docker=args => execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const query=input => execFileSync('docker',['exec','-i',name,'mysql','-uroot','--batch','--raw','--skip-column-names',
        'sourceinventory'],{input,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    const schema=readFileSync(new URL('../../backend/shop-app/src/test/resources/schema-test.sql',import.meta.url),'utf8');
    const mapper=readFileSync(new URL('../../backend/shop-app/src/main/java/ai/neargo/shop/paybridge/mapper/LegacyCouponFenceMapper.java',
        import.meta.url),'utf8');
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=sourceinventory','mysql:9.7.2']);
    try {
        let ready=false;
        for(let n=0;n<60;n++) {
            try { query('SELECT 1'); ready=true; break; }
            catch { await new Promise(resolve => setTimeout(resolve,500)); }
        }
        assert.ok(ready);
        for(const table of ['mkt_coupon','mkt_user_coupon','mkt_coupon_issue','pmt_coupon','pmt_user_coupon',
            'pmt_coupon_issue','pmt_apply']) {
            const ddl=schema.match(new RegExp('CREATE TABLE IF NOT EXISTS '+table+'\\b[\\s\\S]*?;'));
            assert.ok(ddl,table); query(ddl[0]);
        }
        query("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,entity_no,deleted,archived_at,created_at,updated_at) "
            +"VALUES('PC1','inventory','CASH',100,'M1',1,NOW(),NOW(),NOW());"
            +"INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,expire_at,deleted,created_at,updated_at) "
            +"VALUES('PU1','PC1','U1','UNUSED',1,1800000000000,1,NOW(),NOW()),"
            +"('ORPHAN','MISSING','U1','UNUSED',1,1800000000000,1,NOW(),NOW());"
            +"INSERT INTO pmt_coupon_issue(issue_no,coupon_no,issue_mode,issued_at,deleted,created_at,updated_at) "
            +"VALUES('PI1','PC1','TARGETED',1,1,NOW(),NOW());"
            +"INSERT INTO pmt_apply(apply_no,promo_type,promo_no,user_no,applied_at,deleted,created_at,updated_at) "
            +"VALUES('PA1','COUPON','PU1','U1',1,1,NOW(),NOW()),"
            +"('PA2','COUPON','ORPHAN','U1',1,0,NOW(),NOW()),"
            +"('PA3','COUPON','MISSING-INSTANCE','U1',1,0,NOW(),NOW());"
            +"INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,start_at,end_at,deleted,created_at,updated_at) "
            +"VALUES('MC1','legacy','FULL_CUT',100,1,1800000000000,1,NOW(),NOW());"
            +"INSERT INTO mkt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,deleted,created_at,updated_at) "
            +"VALUES('MU1','MC1','U1','USED',1,1,NOW(),NOW());"
            +"INSERT INTO mkt_coupon_issue(issue_no,coupon_no,coupon_name,target,deleted,created_at,updated_at) "
            +"VALUES('MI1','MC1','legacy','SINGLE_USER',1,NOW(),NOW())");
        const bindings=s => s.replaceAll('#{tenant}',"'MAIN'").replaceAll('#{afterId}','0').replaceAll('#{limit}','100');
        const textBlock=signature=>{
            const end=mapper.indexOf(signature);
            assert.ok(end>=0,signature);
            const start=mapper.lastIndexOf('@Select("""',end);
            assert.ok(start>=0,signature);
            const parts=mapper.slice(start,end).split('"""');
            assert.equal(parts.length,3,signature);
            return parts[1];
        };
        const queries=new Map(['inventoryLegacy','inventoryPromo'].map(method=>
            [method,bindings(textBlock('List<LegacyCouponInventoryRow> '+method))]));
        assert.equal(queries.size,2);
        const promo=query(queries.get('inventoryPromo')).split('\t');
        assert.equal(promo[1],'PC1');
        assert.deepEqual(promo.slice(6,12),['1','1','1','1','1','1']);
        const legacy=query(queries.get('inventoryLegacy')).split('\t');
        assert.deepEqual(legacy.slice(6,12),['1','1','1','1','NULL','NULL']);
        const at=mapper.indexOf('long orphanPromoHistory');
        const annotation=mapper.slice(mapper.lastIndexOf('@Select(',at),at);
        const orphan=[...annotation.matchAll(/"([^"]*)"/g)].map(m => m[1]).join('');
        assert.equal(query(bindings(orphan)),'2');
        const origins=new Map(['promoOrigin','legacyOrigin'].map(method=>
            [method,textBlock('LegacyCouponOriginRow '+method)]));
        assert.equal(origins.size,2);
        const originSql=(method,no)=>bindings(origins.get(method)).replaceAll('#{user}',"'U1'").replaceAll('#{no}',"'"+no+"'");
        const owned=query(originSql('promoOrigin','PU1')).split('\t');
        assert.deepEqual(owned.slice(7),['1','1','1']);
        const orphanOrigin=query(originSql('promoOrigin','ORPHAN')).split('\t');
        assert.equal(orphanOrigin[1],'MISSING');
        assert.deepEqual(orphanOrigin.slice(7),['0','1','NULL']);
        assert.equal(query(originSql('promoOrigin','PU1').replaceAll("'MAIN'","'OTHER'")),'');
        assert.deepEqual(query(originSql('legacyOrigin','MU1')).split('\t').slice(7),['1','1','1']);

        query("INSERT INTO mkt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,deleted,created_at,updated_at)"
            +" VALUES('PU1','MC1','U1','UNUSED',1,1,NOW(),NOW());"
            +"INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,start_at,end_at,deleted,created_at,updated_at)"
            +" VALUES('PC1','shadow','FULL_CUT',100,1,1800000000000,1,NOW(),NOW())");
        for(const [method,no,expected] of [['peerPromoTemplates','PC1','1'],['peerLegacyTemplates','PC1','1'],
            ['peerPromoInstances','MC1','1'],['peerLegacyInstances','PC1','1']]) {
            const index=mapper.indexOf('long '+method);
            const annotation=mapper.slice(mapper.lastIndexOf('@Select(',index),index);
            const sql=[...annotation.matchAll(/"([^"]*)"/g)].map(m=>m[1]).join('');
            assert.equal(query(bindings(sql).replaceAll('#{no}',"'"+no+"'")),expected,method);
        }
    } finally { docker(['rm','--force',name]); }
});
