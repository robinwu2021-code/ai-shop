#!/usr/bin/env node
/** Real, explicitly enabled loopback test server only. Never log the private manifest or bearer tokens. */
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {assertPrintFailure} from './lib/print-acceptance.mjs';
const manifestPath=process.argv.find(x=>x.startsWith('--manifest='))?.slice(11);
assert(manifestPath,'--manifest=<private test server ready.json> is required');
const m=JSON.parse(await readFile(manifestPath,'utf8'));
assert.equal(m.mode,'REAL_ISOLATED_H2_HTTP');
assert.match(m.backendUrl,/^http:\/\/127\.0\.0\.1:[0-9]+$/);
const evidence=[];
async function api(path,{token=m.token,store=m.storeNo,body,key}={}) {
 const response=await fetch(m.backendUrl+path,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+token,'X-Store-No':store,...(body===undefined?{}:{'Content-Type':'application/json'}),...(key?{'Idempotency-Key':key}:{})},body:body===undefined?undefined:JSON.stringify(body)});
 const value=await response.json(); return {...value,status:response.status};
}
const summary=(order,token,store)=>api('/biz/print/orders/'+encodeURIComponent(order)+'/summary?storeNo='+encodeURIComponent(store),{token,store});
const wallet=await summary(m.rechargeNo,m.walletToken,m.storeNo); assert.equal(wallet.code,0);assert.equal(wallet.data.sourceFacts.length,1);assert.equal(wallet.data.sourceFacts[0].sourceType,'RECHARGE');
const other=await summary(m.rechargeNo,m.otherToken,m.otherStoreNo);assert.equal(other.code,0);assert.deepEqual(other.data.sourceFacts,[]);
evidence.push({check:'wallet-only-source-summary-and-cross-store-isolation',status:'PASS'});
for (const [name,token,store,order,scene] of [['recharge',m.walletToken,m.storeNo,m.rechargeNo,'RECHARGE_RECEIPT'],['refund',m.refundToken,m.refundStoreNo,m.refundOrderNo,'REFUND_RECEIPT']]) {
 const source=(await summary(order,token,store)).data.sourceFacts.find(f=>f.scene===scene);assert(source);
 const body={storeNo:store,scene,variantCode:'STANDARD_58',fixtureCode:'SUCCESS',source:{type:'FACT',factRef:source},templateSelection:{mode:'SCENE_DEFAULT',variantCode:'STANDARD_58'},outputOptions:{pdfEnabled:false,pdfProfile:'ORIGINAL_ROLL'}};
 const key=randomUUID(); const first=await api('/biz/print/simulations',{token,store,body,key});assert.equal(first.code,0);
 const replay=await api('/biz/print/simulations',{token,store,body,key});assert.equal(replay.code,0);assert.equal(first.data.operationNo,replay.data.operationNo);
 let simulation;for(let i=0;i<80;i++){simulation=await api('/biz/print/simulations/'+first.data.targetNo,{token,store});if(simulation.data?.status==='PASSED')break;await new Promise(r=>setTimeout(r,250));}assert.equal(simulation.data.status,'PASSED');
 const exported=await api('/biz/print/exports',{token,store,body:{storeNo:store,source:{type:'SIMULATION',no:first.data.targetNo,expectedRevision:simulation.data.revision},profile:'ORIGINAL_ROLL'},key:randomUUID()});assert.equal(exported.code,0,JSON.stringify(exported));
 let file;for(let i=0;i<80;i++){file=await api('/biz/print/exports/'+exported.data.targetNo,{token,store});if(file.data?.status==='READY')break;await new Promise(r=>setTimeout(r,250));}assert.equal(file.data.status,'READY');
 const denied=await api('/biz/print/exports/'+exported.data.targetNo,{token:m.deniedToken,store:m.storeNo});const denial=assertPrintFailure(denied,'permission');
 const grant=await api('/biz/print/exports/'+exported.data.targetNo+'/download-grants',{token,store,body:{}});assert.equal(grant.code,0);
 const url=new URL(grant.data.url,m.backendUrl);assert.equal(url.origin,m.backendUrl,'Only local artifact download allowed');
 const pdf=await fetch(url);assert.equal(pdf.status,200);assert.match(pdf.headers.get('content-type'),/application\/pdf/);
 const bytes=Buffer.from(await pdf.arrayBuffer());assert.equal(bytes.subarray(0,5).toString(),'%PDF-');
 const output=resolve(process.env.EVIDENCE_DIR||'/tmp/print-release-20261026');await mkdir(output,{recursive:true});await writeFile(resolve(output,name+'-real.pdf'),bytes);
 evidence.push({check:name+'-real-source-simulation-pdf-and-denial',status:'PASS',factNo:source.factNo,sourceRevision:source.bizRevision,simulationNo:first.data.targetNo,exportNo:exported.data.targetNo,pdfBytes:bytes.length,denial});
}
const target={tenantNo:'MAIN',entityNo:m.refundEntityNo,storeNo:m.refundStoreNo};
const query=new URLSearchParams(target).toString();
for(const resource of ['health','incidents']){const r=await api('/ops/print/'+resource+'?'+query+'&page=1&size=20',{token:m.operatorToken});assert.equal(r.code,0);}
const key=randomUUID(),body={...target,sourceKind:'REFUND',from:new Date(Date.now()-3600000).toISOString(),to:new Date().toISOString(),dryRun:true,reason:'isolated acceptance response replay'};
const first=await api('/ops/print/reconciliations',{token:m.operatorToken,body,key});assert.equal(first.code,0);
const replay=await api('/ops/print/reconciliations',{token:m.operatorToken,body,key});assert.equal(replay.code,0);assert.equal(first.data.operationNo,replay.data.operationNo);
const read=await api('/ops/print/operations/'+first.data.operationNo+'?'+query,{token:m.operatorToken});assert.equal(read.code,0);assert.equal(read.data.operationNo,first.data.operationNo);
const conflict=await api('/ops/print/reconciliations',{token:m.operatorToken,body:{...body,dryRun:false},key});const conflictContract=assertPrintFailure(conflict,'idempotency');
evidence.push({check:'ops-health-incidents-dry-run-replay-query-conflict',status:'PASS',operationNo:first.data.operationNo,conflict:conflictContract});
const result={mode:m.mode,status:'PASS',checks:evidence,partialRefund:m.partialRefundResult,limitations:['No physical printer, cloud vendor or production database.','Multiple same-scene refunds are tested separately with a labeled contract fixture.']};
await writeFile(resolve(process.env.EVIDENCE_DIR||'/tmp/print-release-20261026','real-http-results.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
