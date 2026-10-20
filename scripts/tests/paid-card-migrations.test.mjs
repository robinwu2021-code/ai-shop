import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {test} from 'node:test';
import assert from 'node:assert/strict';

// Applies only the paid-card increments in an isolated portless database, never a configured shop database.
test('V450/V451 enforce capacity ownership and preserve pending historical deliveries', async () => {
    const name=`codex-card-migrations-${randomUUID()}`;
    const docker=args => execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']});
    const sql=value => execFileSync('docker',['exec','-i',name,'mysql','-uroot','-N','cards'],
        {input:value,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
    const migration=file => readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/'+file,
        import.meta.url),'utf8');
    docker(['run','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
        '--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=cards','mysql:9.7.2']);
    try {
        let ready=false;
        for(let n=0;n<60;n++) {
            try { sql('SELECT 1'); ready=true; break; }
            catch { await new Promise(resolve => setTimeout(resolve,500)); }
        }
        assert.ok(ready,'isolated MySQL must start');
        sql(migration('V503__paid_card_capacity_reservation.sql'));
        sql(`INSERT INTO ent_sale_reservation(tenant_no,reservation_no,source_key,source_snapshot,command_snapshot,
            payload_hash,user_no,entity_no,currency,status,created_at,updated_at,created_by,updated_by)
            VALUES ('MAIN','SR1',REPEAT('a',64),'{}','{}',REPEAT('b',64),'U1','M1','CNY','RESERVING',
            UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP','OP')`);
        assert.throws(() => sql("UPDATE ent_sale_reservation SET status='RESERVED'"),/Check constraint/);
        sql("UPDATE ent_sale_reservation SET template_no='T1',definition_snapshot='{}',status='RESERVED'");
        assert.throws(() => sql("UPDATE ent_sale_reservation SET status='TRANSFERRED'"),/Check constraint/);
        sql("UPDATE ent_sale_reservation SET status='TRANSFERRED',grant_no='G1'");
        assert.throws(() => sql("UPDATE ent_sale_reservation SET status='RELEASED'"),/Check constraint/);
        sql("UPDATE ent_sale_reservation SET status='RELEASED',source_decision_no='C1'");
        assert.throws(() => sql("UPDATE ent_sale_reservation SET command_snapshot='broken-json'"),/Invalid JSON/);
        assert.throws(() => sql(`INSERT INTO ent_sale_reservation(tenant_no,reservation_no,source_key,source_snapshot,
            command_snapshot,payload_hash,user_no,entity_no,currency,status,created_at,updated_at,created_by,updated_by)
            SELECT tenant_no,'SR2',source_key,source_snapshot,command_snapshot,payload_hash,user_no,entity_no,
            currency,'RESERVING',created_at,updated_at,created_by,updated_by FROM ent_sale_reservation`),/Duplicate entry/);
        sql(migration('V481__card_sale_entitlement_snapshot.sql'));
        sql(`INSERT INTO ord_card_sale_snapshot(tenant_no,snapshot_no,source_key,order_no,sub_order_no,order_line_no,
            unit_index,entitlement_index,user_no,entity_no,store_no,goods_no,sku_no,template_version_no,
            unit_price_minor,currency,rule_snapshot,snapshot_hash,created_at,updated_at,created_by,updated_by)
            VALUES ('MAIN','CS1',REPEAT('c',64),'O1','SUB1','OL1',1,1,'U1','M1','S1','G1','SKU1','V1',1000,
            'CNY','{}',REPEAT('d',64),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'OP','OP')`);
        sql(migration('V504__card_sale_delivery_recovery.sql'));
        assert.equal(sql("SELECT delivery_status FROM ord_card_sale_snapshot"),'PENDING');
        assert.throws(() => sql("UPDATE ord_card_sale_snapshot SET delivery_status='UNKNOWN'"),/Check constraint/);
        sql("UPDATE ord_card_sale_snapshot SET delivery_status='ACKNOWLEDGED'");
        assert.equal(sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='cards' "
            + "AND table_name='ord_card_sale_snapshot' AND index_name='ix_card_sale_delivery'"),'3');
    } finally { docker(['rm','--force',name]); }
});
