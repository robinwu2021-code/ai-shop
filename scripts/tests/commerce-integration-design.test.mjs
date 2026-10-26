import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const generator=path.join(root,'scripts/gen-commerce-integration-design.mjs');
const design=JSON.parse(fs.readFileSync(path.join(root,'docs/technical/design/schema/commerce-integration-design.json'),'utf8'));
const getTable=name=>design.tables.find(t=>t.name===name);
function validateMutation(change){
 const temp=fs.mkdtempSync(path.join(os.tmpdir(),'commerce-design-contract-'));
 try{const copy=structuredClone(design);change(copy);const source=path.join(temp,'design.json');fs.writeFileSync(source,JSON.stringify(copy));return spawnSync(process.execPath,[generator,'--validate','--source',source],{cwd:root,encoding:'utf8'});}
 finally{fs.rmSync(temp,{recursive:true,force:true});}
}
test('all generated design artifacts match the single planned source',()=>{
 const result=spawnSync(process.execPath,[generator,'--check'],{cwd:root,encoding:'utf8'});
 assert.equal(result.status,0,result.stdout+result.stderr);
 assert.equal(design.status,'DRAFT_NOT_IMPLEMENTED');
 assert.ok(design.apis.every(a=>a.status==='PLANNED'));
});
test('absolute temporary sources are validated before contract negative cases',()=>{
 const result=validateMutation(()=>{});assert.equal(result.status,0,result.stdout+result.stderr);
});
test('duplicate public routes fail design validation',()=>{
 const r=validateMutation(d=>{d.apis[1].verb=d.apis[0].verb;d.apis[1].path=d.apis[0].path;});
 assert.notEqual(r.status,0);assert.match(r.stderr,/Duplicate route/);
});
test('API references cannot silently point at a missing persistence table',()=>{
 const r=validateMutation(d=>d.apis[0].tables.push('cim_missing'));
 assert.notEqual(r.status,0);assert.match(r.stderr,/Unknown table cim_missing/);
});
test('unknown acceptance references fail the design contract',()=>{
 const r=validateMutation(d=>{d.apis[0].ac='AC_MISSING';});
 assert.notEqual(r.status,0);assert.match(r.stderr,/Incomplete API/);
});
test('duplicate physical field declarations fail validation',()=>{
 const r=validateMutation(d=>d.tables[0].fields.push(d.tables[0].fields[0]));
 assert.notEqual(r.status,0);assert.match(r.stderr,/Duplicate/);
});
test('account authorization has a scope before any store connection exists',()=>{
 const operation=getTable('cim_operation');
 assert.match(operation.fields.find(f=>f.name==='connection_no').definition,/NULL/);
 assert.ok(operation.fields.some(f=>f.name==='account_no'));
 assert.match(operation.keys,/U\(target_scope_key,destination,semantic_key\)/);
 assert.match(getTable('cim_request_journal').keys,/scope_kind,scope_key/);
});
test('full replacement barrier includes remote pending subtasks and no lease-only unlock',()=>{
 const scope=getTable('cim_catalog_scope');
 assert.ok(scope.fields.some(f=>f.name==='active_operation_no'));
 assert.ok(scope.fields.some(f=>f.name==='remote_pending_evidence'));
 assert.match(scope.rules,/SUBMITTED\/UNKNOWN/);
 assert.match(scope.rules,/不清activeOperation/);
 assert.match(scope.rules,/子任务/);
});
test('historical mapping verification and source owner contracts remain explicit',()=>{
 assert.ok(design.ports.some(p=>p.name==='ChannelSourceValidationPort'));
 assert.ok(getTable('cim_resource_mapping_revision').fields.some(f=>f.name==='validation_receipt_ref'));
 assert.match(design.common.routing,/混合行业目录和订单均关闭/);
});

test('coded subset references real migration, routes and source files without claiming full completion',()=>{
 const actual=design.implementation;
 assert.equal(actual.tables.length,17);
 assert.equal(actual.apiIds.length,29);
 const modules=path.join(root,actual.moduleRoot);
 assert.ok(actual.migrations.every(migration=>fs.existsSync(path.join(modules,migration))));
 for(const id of actual.apiIds){
  const api=design.apis.find(a=>a.id===id);
  const controller=path.join(modules,'commerce-web/src/main/java/ai/neargo/shop/commerce/api',api.role==='CALLBACK'?'callback':'biz',api.controller+'.java');
  assert.ok(fs.readFileSync(controller,'utf8').includes(api.path),id);
 }
 assert.match(actual.limitations,/未开放/);
});
