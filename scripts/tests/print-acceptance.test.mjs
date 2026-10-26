import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {assertPrintFailure, createPrintAcceptanceProxy, verifyPrintNetworkRecords} from '../lib/print-acceptance.mjs';

test('network evidence cannot pass with a new retry key, new operation or missing case',()=>{
 const cases=[['/biz/print/simulations','DROP_REQUEST'],['/biz/print/simulations','DROP_RESPONSE'],['/ops/print/reconciliations','DROP_REQUEST'],['/ops/print/reconciliations','DROP_RESPONSE'],['/biz/print/exports','DROP_RESPONSE']];
 const records=cases.flatMap(([path,mode],i)=>{
  const result={method:'POST',path,bodyHash:'a'.repeat(64),keyHash:String(i).repeat(64),forwarded:true,httpStatus:200,code:0,operationNo:'PO'+i,targetNo:'TARGET'+i};
  return [{...result,outcome:mode,forwarded:mode!=='DROP_REQUEST',...(mode==='DROP_REQUEST'?{operationNo:undefined,targetNo:undefined,httpStatus:undefined,code:undefined}:{})}, {...result,outcome:'DELIVERED'}];
 });
 assert.equal(verifyPrintNetworkRecords(records).length,5);
 for(const [index,field,value] of [[1,'keyHash','b'.repeat(64)],[3,'operationNo','NEW'],[3,'targetNo','NEW'],[3,'code',1],[0,'forwarded',true]]){
  const copy=structuredClone(records);copy[index][field]=value;assert.throws(()=>verifyPrintNetworkRecords(copy));
 }
 assert.throws(()=>verifyPrintNetworkRecords(records.slice(2)));
});

test('negative acceptance rejects server errors, missing codes and wrong conflict keys',()=>{
 assert.doesNotThrow(()=>assertPrintFailure({status:200,code:70006},'permission'));
 assert.doesNotThrow(()=>assertPrintFailure({status:409,code:82002,data:{key:'print.idempotencyConflict'}},'idempotency'));
 for(const response of [{status:500,code:70006},{status:200},{status:403,code:10403},{status:200,code:0}])assert.throws(()=>assertPrintFailure(response,'permission'));
 for(const response of [{status:500,code:82002},{status:409},{status:409,code:82002,data:{key:'print.revisionConflict'}}])assert.throws(()=>assertPrintFailure(response,'idempotency'));
 assert.throws(()=>assertPrintFailure({status:200,code:70006},'unknown'));
});

const listen=server=>new Promise(resolve=>server.listen(0,'127.0.0.1',()=>resolve('http://127.0.0.1:'+server.address().port)));
test('explicit proxy distinguishes lost request and committed lost response, and redacts secrets',async t=>{
 const receipts=new Map();let calls=0;const records=[];
 const backend=createServer(async(req,res)=>{for await(const _ of req){} calls++;const key=req.headers['idempotency-key'];if(!receipts.has(key))receipts.set(key,{code:0,data:{operationNo:'PO-'+receipts.size,targetNo:'SM-'+receipts.size}});res.setHeader('Content-Type','application/json');res.end(JSON.stringify(receipts.get(key)));});
 const backendUrl=await listen(backend);t.after(()=>{backend.closeAllConnections();backend.close();});
 const proxy=createPrintAcceptanceProxy({backend:new URL(backendUrl),enabled:true,onRecord:record=>records.push(record)});
 const server=createServer((req,res)=>{proxy.forward(req,res).catch(()=>res.destroy());});const url=await listen(server);t.after(()=>{server.closeAllConnections();server.close();});
 const path='/biz/print/simulations',body=JSON.stringify({storeNo:'S1',scene:'ORDER_PAID'});
 const send=key=>fetch(url+path,{method:'POST',headers:{'Idempotency-Key':key,Authorization:'Bearer PRIVATE_TOKEN','Content-Type':'application/json'},body});
 proxy.arm({mode:'DROP_REQUEST',path});await assert.rejects(send('SECRET_KEY_A'));assert.equal(calls,0);
 const first=await (await send('SECRET_KEY_A')).json();assert.equal(calls,1);
 proxy.arm({mode:'DROP_RESPONSE',path});await assert.rejects(send('SECRET_KEY_B'));assert.equal(calls,2);
 const replay=await (await send('SECRET_KEY_B')).json();assert.equal(calls,3);assert.equal(receipts.size,2);
 assert.equal(first.data.operationNo,'PO-0');assert.equal(replay.data.operationNo,'PO-1');
 assert.equal(records.length,4);assert.equal(records[0].forwarded,false);assert.equal(records[2].outcome,'DROP_RESPONSE');assert.equal(records[2].operationNo,replay.data.operationNo);assert.equal(records[2].keyHash,records[3].keyHash);assert.equal(records[0].bodyHash,records[1].bodyHash);
 assert.doesNotMatch(JSON.stringify(records),/PRIVATE_TOKEN|SECRET_KEY|Bearer/);
 assert.throws(()=>proxy.arm({mode:'DROP_RESPONSE',path:'/biz/print/jobs'}));
 assert.throws(()=>proxy.arm({mode:'INVALID',path}));
 assert.throws(()=>createPrintAcceptanceProxy({backend:new URL('https://example.com'),enabled:true}));
 assert.throws(()=>createPrintAcceptanceProxy({backend:new URL(backendUrl)}).arm({mode:'DROP_REQUEST',path}));
});
