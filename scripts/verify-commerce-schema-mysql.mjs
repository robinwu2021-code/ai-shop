// Only a disposable, network-isolated MySQL container. Never application credentials.
import {execFileSync, spawnSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const name = 'codex-commerce-schema-' + randomUUID();
const docker = args => execFileSync('docker', args, {encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe']}).trim();
const sql = (input, expected = 0) => {
 const result = spawnSync('docker', ['exec', '-i', name, 'mysql', '-uroot', 'commerce_verify',
  '--batch', '--skip-column-names'], {input, encoding: 'utf8'});
 if (expected === 0) assert.equal(result.status, 0, result.stderr);
 else { assert.notEqual(result.status, 0); assert.match(result.stderr, /1062/); }
 return result.stdout.trim();
};
docker(['run', '--pull', 'never', '--detach', '--name', name, '--network', 'none', '--tmpfs', '/var/lib/mysql',
 '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_DATABASE=commerce_verify', 'mysql:9.7.2']);
try {
 let ready = false;
 for (let i = 0; i < 90; i++) {
  try { docker(['exec', name, 'mysql', '-uroot', 'commerce_verify', '-e', 'SELECT 1']); ready = true; break; }
  catch { await new Promise(resolve => setTimeout(resolve, 500)); }
 }
 assert.ok(ready, 'Disposable MySQL failed to start');
 sql(readFileSync(new URL('../backend-plugins/shop-commerce-integration/commerce-core/src/main/resources/db/commerce/V1__commerce_draft_resources.sql', import.meta.url), 'utf8'));
 sql(readFileSync(new URL('../backend-plugins/shop-commerce-integration/commerce-core/src/main/resources/db/commerce/V2__commerce_authorization_operations.sql', import.meta.url), 'utf8'));
 sql(readFileSync(new URL('../backend-plugins/shop-commerce-integration/commerce-core/src/main/resources/db/commerce/V3__commerce_catalog_synchronization.sql', import.meta.url), 'utf8'));
 sql(readFileSync(new URL('../backend-plugins/shop-commerce-integration/commerce-core/src/main/resources/db/commerce/V4__commerce_resource_mappings.sql', import.meta.url), 'utf8'));
 assert.equal(sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()'), '17');
 const connection = (no, entity, store, identity) => `INSERT INTO cim_store_connection(connection_no,entity_no,
 store_no,profile_no,account_no,external_store_ref,channel_store_key,active_revision_no,status,identity_receipt_ref,identity_verified_at,identity_valid_until)
 VALUES ('${no}','${entity}','${store}','PP','ACCOUNT','REMOTE','${identity}','REV','DRAFT','TRUSTED_TEST','2026-10-26 00:00:00','2026-10-26 01:00:00')`;
 sql(connection('C1', 'E1', 'S1', 'STABLE_REMOTE'));
 sql(connection('C2', 'E2', 'S2', 'STABLE_REMOTE'), 1);
 assert.equal(sql('SELECT COUNT(*) FROM cim_store_connection'), '1');
 const journal = (no, actor) => `INSERT INTO cim_request_journal(request_no,entity_no,endpoint_key,key_hash,
 actor_no,request_hash,status,scope_kind,scope_key) VALUES
 ('${no}','E','CREATE_ACCOUNT','KEY','${actor}','HASH','PENDING','ENTITY','SCOPE')
 ON DUPLICATE KEY UPDATE request_no=request_no`;
 sql(journal('R1', 'ACTOR1')); sql(journal('R2', 'ACTOR2'));
 assert.equal(sql("SELECT CONCAT(request_no,':',actor_no) FROM cim_request_journal"), 'R1:ACTOR1');
 sql(`UPDATE cim_request_journal SET status='SUCCEEDED',result_snapshot='{"accountNo":"ACC"}' WHERE request_no='R1'`);
 assert.equal(sql('SELECT result_snapshot FROM cim_request_journal'), '{"accountNo":"ACC"}');
 const operation = (no, scope, semantic, state) => `INSERT INTO cim_operation(operation_no,entity_no,
 destination,operation_type,business_ref,business_revision,semantic_key,request_hash,request_ref,status,
 current_attempt_no,fence,account_no,profile_no,target_scope_kind,target_scope_key,callback_state_hash)
 VALUES ('${no}','E','PROVIDER','AUTHORIZE','ACCOUNT','1','${semantic}','HASH','REF','SENDING',1,1,
 'ACCOUNT','PP','ACCOUNT','${scope}',${state === null ? 'NULL' : "'" + state + "'"})`;
 sql(operation('O1','SCOPE1','SAME','STATE1'));
 sql(operation('O2','SCOPE1','SAME','STATE2'), 1);
 sql(operation('O3','SCOPE2','SAME','STATE1'), 1);
 sql(operation('O4','SCOPE2','SAME','STATE2'));
 sql(operation('O5','SCOPE1','REVOKE1',null)); sql(operation('O6','SCOPE1','REVOKE2',null));
 const revision = (no, account) => `INSERT INTO cim_authorization_revision(authorization_no,entity_no,
 account_no,revision_no,credential_ref,granted_scopes,capability_hash,authorized_at,status,evidence_ref)
 VALUES ('${no}','E','${account}',1,'vault:test-reference','["ORDER"]','HASH','2026-10-26 00:00:00','GRANTED','RECEIPT')`;
 sql(revision('AR1','A1')); sql(revision('AR2','A1'), 1); sql(revision('AR3','A2'));
 const scope = no => `INSERT INTO cim_catalog_scope(scope_no,entity_no,store_no,connection_no,remote_scope_key,name,remote_consistency,generation,status) VALUES ('${no}','E','S','C1','STORE','Menu','UNKNOWN',0,'ACTIVE')`;
 sql(scope('SC1'));sql(scope('SC2'),1);
 const source = no => `INSERT INTO cim_catalog_source(source_no,entity_no,scope_no,source_owner,source_ref,selection_revision,priority,status) VALUES ('${no}','E','SC1','FOOD','MENU','R1',0,'ACTIVE')`;
 sql(source('CS1'));sql(source('CS2'),1);
 const policy = no => `INSERT INTO cim_sync_policy(policy_no,entity_no,store_no,scope_no,enabled,on_publish,on_availability,scheduled_check,debounce_seconds,recovery_profile) VALUES ('${no}','E','S','SC1',false,true,false,true,30,'QUERY_ONLY')`;
 sql(policy('SP1'));sql(policy('SP2'),1);
 const snapshot = no => `INSERT INTO cim_catalog_snapshot(snapshot_no,entity_no,scope_no,schema_version,source_manifest,payload_ref,payload_json,content_hash,item_count,currency,captured_at,expires_at,scope_version,connection_revision_no) VALUES ('${no}','E','SC1',1,'[]','DB:${no}','{}','SAME_HASH',0,'TWD','2026-10-26 00:00:00','2026-10-26 01:00:00',0,'REV')`;
 sql(snapshot('SN1'));sql(snapshot('SN2'));
 sql(`INSERT INTO cim_sync_run(run_no,entity_no,store_no,connection_no,scope_no,run_type,trigger_type,snapshot_no,request_hash,status) VALUES ('RUN','E','S','C1','SC1','CATALOG_REPLACE','MANUAL','SN1','HASH','QUEUED')`);
 const item = (no,attempt,type) => `INSERT INTO cim_sync_run_item(item_no,entity_no,run_no,attempt_no,object_type,object_key_hash,result) VALUES ('${no}','E','RUN',${attempt},'${type}','KEY','SUCCEEDED')`;
 sql(item('I1',1,'OFFER'));sql(item('I2',1,'OFFER'),1);sql(item('I3',2,'OFFER'));sql(item('I4',1,'SKU'));
 assert.equal(sql('SELECT COUNT(*) FROM cim_sync_run_item'),'3');
 const mapping=(no,identity)=>`INSERT INTO cim_resource_mapping(mapping_no,entity_no,store_no,connection_no,object_type,external_ref,external_identity_hash,external_identity_json,latest_revision_no) VALUES ('${no}','E','S','C1','SKU','REMOTE','${identity}','{}','MR1')`;
 sql(mapping('M1','PARENT_A'));sql(mapping('M2','PARENT_A'),1);sql(mapping('M3','PARENT_B'));
 const mappingRevision=(no,revision)=>`INSERT INTO cim_resource_mapping_revision(revision_no,entity_no,mapping_no,revision,source_owner,source_ref,source_revision_ref,sales_offer_ref,mapping_snapshot,content_hash,mapping_origin,validation_status,connection_revision_no,reason) VALUES ('${no}','E','M1',${revision},'FOOD','SKU','R1','OFFER','{}','HASH','IMPORTED','DRAFT','CR','TEST')`;
 sql(mappingRevision('MR1',1));sql(mappingRevision('MR2',1),1);sql(mappingRevision('MR3',2));
 console.log('PASS: seventeen-table V1+V2+V3+V4 migration, stable store identity, journal replay, operation scope/state uniqueness grant revisions, STORE/source/policy constraints, repeated snapshot content and attempt-scoped item uniqueness, parent-scoped mapping identity and revision uniqueness');
} finally { docker(['rm', '--force', name]); }
