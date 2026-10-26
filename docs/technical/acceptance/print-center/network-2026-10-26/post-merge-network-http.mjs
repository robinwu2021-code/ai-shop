import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,writeFile,appendFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
const {createPrintAcceptanceProxy,verifyPrintNetworkRecords}=await import(pathToFileURL(resolve(process.env.PRINT_REPO,'scripts/lib/print-acceptance.mjs')));
const m=JSON.parse(await readFile(process.env.PRINT_MANIFEST,'utf8'));
assert.equal(m.mode,'REAL_ISOLATED_H2_HTTP');assert.match(m.backendUrl,/^http:\/\/127\.0\.0\.1:[0-9]+$/);
const output=resolve(process.env.EVIDENCE_DIR);await mkdir(output,{recursive:true});
const log=resolve(output,'post-merge-http-requests.jsonl');await writeFile(log,'');const records=[];
const proxy=createPrintAcceptanceProxy({backend:new URL(m.backendUrl),enabled:true,onRecord:async r=>{records.push(r);await appendFile(log,JSON.stringify(r)+'\n');}});
const server=createServer((req,res)=>proxy.forward(req,res).catch(()=>res.destroy()));
await new Promise(done=>server.listen(0,'127.0.0.1',done));const origin='http://127.0.0.1:'+server.address().port;
async function api(path,{ops=false,body,key,via=m.backendUrl}={}){
 const r=await fetch(via+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+(ops?m.operatorToken:m.walletToken),'X-Store-No':m.storeNo,...(body?{'Content-Type':'application/json','Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(15000)});
 assert.equal(r.status,200);const data=await r.json();assert.equal(data.code,0);return data.data;
}
const operations=[];
async function recover(path,mode,body,ops=false){
 const key=randomUUID();proxy.arm({path,mode});await assert.rejects(api(path,{ops,body,key,via:origin}));
 const result=await api(path,{ops,body,key,via:origin});operations.push({path,ops,...result});return result;
}
async function ready(path,status){for(let i=0;i<80;i++){const r=await api(path);if(r.status===status)return r;await new Promise(done=>setTimeout(done,250));}throw Error('Task not ready: '+path);}
try{
 const summary=await api('/biz/print/orders/'+m.rechargeNo+'/summary?storeNo='+m.storeNo);
 const fact=summary.sourceFacts.find(f=>f.scene==='RECHARGE_RECEIPT');assert(fact);
 const simulation={storeNo:m.storeNo,scene:fact.scene,variantCode:'STANDARD_80',fixtureCode:'SUCCESS',source:{type:'FACT',factRef:fact},templateSelection:{mode:'SCENE_DEFAULT',variantCode:'STANDARD_80'},outputOptions:{pdfEnabled:false,pdfProfile:'ORIGINAL_ROLL'}};
 const first=await recover('/biz/print/simulations','DROP_REQUEST',simulation);
 const second=await recover('/biz/print/simulations','DROP_RESPONSE',simulation);
 await ready('/biz/print/simulations/'+first.targetNo,'PASSED');const sim=await ready('/biz/print/simulations/'+second.targetNo,'PASSED');
 const target={tenantNo:'MAIN',entityNo:m.refundEntityNo,storeNo:m.refundStoreNo};
 const reconcile={...target,sourceKind:'REFUND',from:new Date(Date.now()-3600000).toISOString(),to:new Date().toISOString(),dryRun:true,reason:'post-merge isolated HTTP network acceptance'};
 await recover('/ops/print/reconciliations','DROP_REQUEST',reconcile,true);
 await recover('/ops/print/reconciliations','DROP_RESPONSE',reconcile,true);
 const exported=await recover('/biz/print/exports','DROP_RESPONSE',{storeNo:m.storeNo,source:{type:'SIMULATION',no:second.targetNo,expectedRevision:sim.revision},profile:'ORIGINAL_ROLL'});
 await ready('/biz/print/exports/'+exported.targetNo,'READY');
 const checks=verifyPrintNetworkRecords(records);
 for(const operation of operations){
  const query=operation.ops?'?'+new URLSearchParams(target):'';
  const found=await api((operation.ops?'/ops':'/biz')+'/print/operations/'+operation.operationNo+query,{ops:operation.ops});
  assert.equal(found.operationNo,operation.operationNo);assert.equal(found.targetNo,operation.targetNo);assert.equal(found.status,'SUCCEEDED');
 }
 const result={mode:m.mode,client:'NODE_HTTP_POST_MERGE',status:'PASS',checks,simulationStatuses:['PASSED','PASSED'],exportStatus:'READY',limitations:['Scripted HTTP only; native Chrome recovery evidence was collected earlier and is labeled separately.','No physical printer, vendor or production database.']};
 await writeFile(resolve(output,'post-merge-http-network-results.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({status:result.status,client:result.client,checks:checks.length}));
}finally{server.closeAllConnections();server.close();}
