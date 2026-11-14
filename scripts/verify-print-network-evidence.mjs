#!/usr/bin/env node
/** Validate real browser proxy records against the isolated backend; never records credentials. */
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {verifyPrintNetworkRecords} from './lib/print-acceptance.mjs';
const arg=name=>process.argv.find(x=>x.startsWith('--'+name+'='))?.slice(name.length+3);
assert(arg('manifest')&&arg('requests')&&arg('output'),'--manifest, --requests and --output required');
const manifest=JSON.parse(await readFile(arg('manifest'),'utf8'));
assert.equal(manifest.mode,'REAL_ISOLATED_H2_HTTP');assert.match(manifest.backendUrl,/^http:\/\/127\.0\.0\.1:[0-9]+$/);
const records=(await readFile(arg('requests'),'utf8')).trim().split('\n').map(x=>JSON.parse(x));
const checks=verifyPrintNetworkRecords(records);
async function api(path,ops=false){
 const response=await fetch(manifest.backendUrl+path,{headers:{Authorization:'Bearer '+(ops?manifest.operatorToken:manifest.walletToken),'X-Store-No':manifest.storeNo},signal:AbortSignal.timeout(15000)});
 assert.equal(response.status,200);const value=await response.json();assert.equal(value.code,0);return value.data;
}
const simulations=await api('/biz/print/simulations?storeNo='+encodeURIComponent(manifest.storeNo)+'&page=1&size=100');
const exports=await api('/biz/print/exports?storeNo='+encodeURIComponent(manifest.storeNo)+'&page=1&size=100');
for(const check of checks){
 const ops=check.path.startsWith('/ops/');
 const query=ops?'?'+new URLSearchParams({tenantNo:'MAIN',entityNo:manifest.refundEntityNo,storeNo:manifest.refundStoreNo}):'';
 const operation=await api((ops?'/ops':'/biz')+'/print/operations/'+encodeURIComponent(check.operationNo)+query,ops);
 assert.equal(operation.operationNo,check.operationNo);assert.equal(operation.targetNo,check.targetNo);assert.equal(operation.status,'SUCCEEDED');
 if(!ops){
  const simulation=check.path.endsWith('simulations'),field=simulation?'simulationNo':'exportNo';
  const rows=(simulation?simulations:exports).records.filter(row=>row[field]===check.targetNo);
  assert.equal(rows.length,1,'Expected exactly one recovered task');assert.equal(rows[0].status,simulation?'PASSED':'READY');
  check.finalStatus=rows[0].status;
 }else check.finalStatus=operation.status;
}
const result={mode:manifest.mode,status:'PASS',injection:'Loopback proxy socket loss, exercised by real Chrome UI clicks',checks,limitations:['No OS-level Wi-Fi change or browser process restart.','No physical device, vendor or production database.']};
await writeFile(resolve(arg('output')),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
