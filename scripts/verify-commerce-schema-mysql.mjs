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
 assert.equal(sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()'), '6');
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
 console.log('PASS: six-table native migration, global connection identity, immutable journal actor and JSON snapshot');
} finally { docker(['rm', '--force', name]); }
