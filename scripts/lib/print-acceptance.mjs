/** Test harness only. Never imported by application code. */
import assert from 'node:assert/strict';
import {request} from 'node:http';
import {createHash} from 'node:crypto';

export function assertPrintFailure(response,kind){
 assert(['permission','idempotency'].includes(kind),'Unknown failure contract');
 assert.equal(response.status,kind==='permission'?200:409,'Unexpected HTTP status');
 assert.equal(response.code,kind==='permission'?70006:82002,'Unexpected business code');
 if(kind==='idempotency')assert.equal(response.data?.key,'print.idempotencyConflict');
 return {httpStatus:response.status,code:response.code,...(kind==='idempotency'?{key:response.data.key}:{})};
}

const commands=new Set(['/biz/print/simulations','/biz/print/exports','/ops/print/reconciliations']);
const hash=value=>createHash('sha256').update(value).digest('hex');
export function verifyPrintNetworkRecords(records){
 const required=[['/biz/print/simulations','DROP_REQUEST'],['/biz/print/simulations','DROP_RESPONSE'],['/ops/print/reconciliations','DROP_REQUEST'],['/ops/print/reconciliations','DROP_RESPONSE'],['/biz/print/exports','DROP_RESPONSE']];
 assert.equal(records.length,required.length*2,'Unexpected or incomplete network evidence');
 return required.map(([path,mode])=>{
  const matches=records.map((r,i)=>r.path===path&&r.outcome===mode?i:-1).filter(i=>i>=0);
  assert.equal(matches.length,1,'Missing or repeated fault case: '+path+' '+mode);
  const first=records[matches[0]],replay=records[matches[0]+1];
  assert(replay,'Missing recovery request');
  assert.equal(first.method,'POST');assert.equal(replay.method,'POST');assert.equal(replay.path,path);
  assert.equal(replay.outcome,'DELIVERED');assert.equal(replay.forwarded,true);
  for(const field of ['keyHash','bodyHash']){assert.match(first[field],/^[a-f0-9]{64}$/);assert.equal(replay[field],first[field],'Retry changed '+field);}
  assert.equal(replay.httpStatus,200);assert.equal(replay.code,0);assert(replay.operationNo&&replay.targetNo);
  if(mode==='DROP_REQUEST'){assert.equal(first.forwarded,false);assert.equal(first.operationNo,undefined);assert.equal(first.httpStatus,undefined);}
  else{assert.equal(first.forwarded,true);assert.equal(first.httpStatus,200);assert.equal(first.code,0);assert.equal(replay.operationNo,first.operationNo);assert.equal(replay.targetNo,first.targetNo);}
  return {path,mode,status:'PASS',operationNo:replay.operationNo,targetNo:replay.targetNo,keyHash:replay.keyHash,bodyHash:replay.bodyHash};
 });
}

export function createPrintAcceptanceProxy({backend,enabled=false,onRecord=()=>{}}){
 assert.equal(backend.protocol,'http:');
 assert(['127.0.0.1','localhost','[::1]'].includes(backend.hostname),'Loopback backend required');
 assert(!backend.username&&!backend.password&&backend.pathname==='/'&&!backend.search&&!backend.hash);
 let fault=null;
 return {
  arm(value){
   assert(enabled,'Fault injection is disabled');
   assert(commands.has(value?.path),'Unsupported fault endpoint');
   assert(['DROP_REQUEST','DROP_RESPONSE'].includes(value.mode),'Unsupported fault mode');
   assert(!fault,'An unconsumed fault is already armed');
   fault={path:value.path,mode:value.mode};
  },
  async forward(req,res){
   const path=new URL(req.url,backend).pathname;
   // Prefixing the fixed origin also prevents absolute-form request targets escaping loopback.
   const target=new URL(backend.origin+req.url);
   assert.equal(target.origin,backend.origin);
   const traced=enabled&&req.method==='POST'&&commands.has(path);
   if(!traced){
    const upstream=request(target,{method:req.method,headers:{...req.headers}},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res);});
    upstream.on('error',()=>res.destroy());res.on('close',()=>upstream.destroy());req.pipe(upstream);return;
   }
   const active=fault?.path===path?fault:null;if(active)fault=null;
   const chunks=[];let size=0;
   for await(const chunk of req){size+=chunk.length;assert(size<=1024*1024,'Acceptance command too large');chunks.push(chunk);}
   const body=Buffer.concat(chunks);
   const record={method:req.method,path,bodyHash:hash(body),keyHash:hash(String(req.headers['idempotency-key']||'')),forwarded:active?.mode!=='DROP_REQUEST',outcome:active?.mode||'DELIVERED'};
   if(active?.mode==='DROP_REQUEST'){await onRecord(record);res.destroy();return;}
   await new Promise((resolve,reject)=>{
    const upstream=request(target,{method:req.method,headers:{...req.headers}},async response=>{
     try{
      const bytes=[];for await(const chunk of response)bytes.push(chunk);const value=Buffer.concat(bytes);
      let data;try{data=JSON.parse(value);}catch{}
      await onRecord({...record,httpStatus:response.statusCode,code:data?.code,operationNo:data?.data?.operationNo,targetNo:data?.data?.targetNo});
      if(active?.mode==='DROP_RESPONSE')res.destroy();else{res.writeHead(response.statusCode,response.headers);res.end(value);}
      resolve();
     }catch(error){reject(error);}
    });
    upstream.on('error',reject);upstream.end(body);
   });
  }
 };
}
