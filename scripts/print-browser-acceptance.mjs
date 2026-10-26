#!/usr/bin/env node
/** Local-only merchant browser harness; no production credentials, hardware, or dependencies.
 * Build first: VITE_USE_MOCK=0 VITE_API_BASE='' npm run build:h5 -w ai-shop-b-app
 * Contract fixture: node scripts/print-browser-acceptance.mjs
 * Controlled command failures (fixture ONLY): FIXTURE_FAIL_COMMANDS=1 PORT=5285 node scripts/print-browser-acceptance.mjs
 * Real isolated backend: BACKEND_URL=http://127.0.0.1:PORT node scripts/print-browser-acceptance.mjs
 * Start at /acceptance/start?token=...&store=...&order=... (defaults are fixture-only).
 * Fixture responses prove UI/API-contract interaction ONLY, never backend correctness or output.
 */
import { createServer, request as httpRequest } from 'node:http';
import { readFile, appendFile, mkdir, stat } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { createHash } from 'node:crypto';
const port=Number(process.env.PORT||5284);
const root=resolve(process.env.H5_ROOT||'b-app/dist/build/h5');
const opsRoot=resolve(process.env.OPS_ROOT||'ops-web/out');
const multiRefunds=process.env.FIXTURE_MULTI_REFUNDS==='1';
const output=resolve(process.env.EVIDENCE_DIR||'/tmp/print-next-wave');
const failCommands=process.env.FIXTURE_FAIL_COMMANDS==='1';
const backend=process.env.BACKEND_URL?new URL(process.env.BACKEND_URL):null;
if(backend && !['127.0.0.1','localhost','[::1]'].includes(backend.hostname))throw Error('Only an isolated loopback backend is allowed');
const manifest=process.env.ACCEPTANCE_MANIFEST?JSON.parse(await readFile(process.env.ACCEPTANCE_MANIFEST,'utf8')):null;
if(manifest&&(!backend||manifest.backendUrl!==backend.origin))throw Error('Manifest/backend mismatch');
const frozen=value=>{const canonicalJson=JSON.stringify(value);return {canonicalJson,sha256:createHash('sha256').update(canonicalJson).digest('hex')};};
const page=records=>({records,total:records.length,page:1,size:100});
const scene={scene:'ORDER_PAID',title:'支付小票',sourceAvailable:true,variants:['STANDARD_58','STANDARD_80'],sourceTypes:['SUB_ORDER'],triggers:['ORDER_PAYMENT_CONFIRMED']};
const fact={tenantNo:'FIXTURE-TENANT',entityNo:'FIXTURE-ENTITY',storeNo:'FIXTURE-STORE',sourceType:'SUB_ORDER',factNo:'FIXTURE-FACT',bizRevision:'1',scene:'ORDER_PAID',snapshotHash:createHash('sha256').update('BROWSER_CONTRACT_FIXTURE_ORDER_PAID').digest('hex')};
const refundFacts=[1,2].map(n=>({...fact,sourceType:'REFUND',scene:'REFUND_RECEIPT',factNo:'FIXTURE-REFUND-'+n,bizRevision:String(n)}));
const printers=[],templates=[],routes=[],simulations=[],exports=[],policies=[];
const jobs=[{jobNo:'FIXTURE-UNKNOWN-JOB',requestNo:'FIXTURE-REQUEST',batchNo:'FIXTURE-BATCH',scene:'ORDER_PAID',printerNo:'LEGACY-DEVICE',status:'UNKNOWN',jobKind:'INITIAL',rootJobNo:'FIXTURE-UNKNOWN-JOB',revision:3,resolutionStatus:'OPEN',allowedActions:['QUERY','REPRINT','CONFIRM']}];
const incidents=[{incidentNo:'FIXTURE-INCIDENT',incidentType:'OUTPUT_UNKNOWN',targetNo:'FIXTURE-UNKNOWN-JOB',status:'OPEN',severity:'WARNING',revision:0}];
const log=[],intents=new Map(),versions=new Map();let seq=0;
const json=(res,data,code=0,status=200)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify({code,msg:code?'contract-fixture rejected request':'OK',data}));};
function operation(type,result,targetNo='FIXTURE-TARGET'){return {operationNo:'FIXTURE-OP-'+(++seq),type,status:'SUCCEEDED',targetType:'FIXTURE',targetNo,result:frozen(result)};}
function requireValue(value,message){if(!value)throw Error(message);}
function fixture(method,path,query,body,req){
 const write=method!=='GET';
 if(failCommands&&write&&['/biz/print/simulations','/biz/print/exports'].includes(path))throw Error('CONTROLLED_ACCEPTANCE_COMMAND_FAILURE');
 const token=req.headers.authorization||'';
 if(path==='/biz/context')return {perms:token.includes('denied')?['biz:print:read']:['*'],staffRoles:['OWNER'],categoryCodes:[],switches:{}};
 if(path==='/biz/merchant/profile')return {merchantNo:'FIXTURE-MERCHANT',entityNo:'FIXTURE-ENTITY',name:'浏览器合同验收店',status:'ACTIVE'};
 if(path==='/biz/store/list')return [{storeNo:fact.storeNo,entityNo:fact.entityNo,name:'浏览器合同验收店',status:'ACTIVE',isDefault:true}];
 if(path==='/biz/stores/my')return [];
 if(!path.startsWith('/biz/print/'))return null;
 if(token.includes('denied')&&(write||path.includes('/printers')||path.includes('/templates')||path.includes('/simulations')))throw Error('permission denied');
 if(write)requireValue(req.headers['idempotency-key']||path.endsWith('/preview')||path.endsWith('/download-grants'),'missing Idempotency-Key');
 if(path.endsWith('/scenes'))return [scene,...(multiRefunds?[{...scene,scene:'REFUND_RECEIPT',title:'退款凭证',sourceTypes:['REFUND']}]:[]),{...scene,scene:'CASHIER_RECEIPT',title:'收银凭证',sourceAvailable:false,sourceTypes:[],triggers:[]}];
 if(path.endsWith('/fields'))return {catalog:frozen({mandatoryPrintPaths:['orderNo']})};
 if(path.endsWith('/examples'))return {baseTemplate:frozen({layout:{},blocks:[{type:'keyValue',binding:'orderNo',label:'订单'},{type:'keyValue',binding:'total',label:'金额'}]})};
 if(path.endsWith('/scene-policies'))return policies;
 if(path.includes('/scene-policies/')&&write){policies.splice(0,policies.length,{policyNo:'FIXTURE-POLICY',scene:'ORDER_PAID',enabled:body.policy.enabled,revision:(body.expectedRevision||0)+1,policy:frozen(body.policy)});return operation('POLICY',{});}
 if(path.endsWith('/agents'))return page([]);
 if(path.endsWith('/printers')){
  if(!write)return page(printers);
  requireValue(body.connection.config.canonicalDeviceNo==='LEGACY-DEVICE','fixture requires adopting LEGACY-DEVICE');
  const printer={printerNo:'LEGACY-DEVICE',storeNo:body.storeNo,name:body.name,connectionType:body.connection.type,adapterCode:body.connection.adapterCode,paperWidthMm:body.paperWidthMm,status:'PAUSED',healthStatus:'UNKNOWN',revision:0};printers.push(printer);return operation('PRINTER_CREATE',{printerNo:printer.printerNo},printer.printerNo);
 }
 if(path.endsWith('/status-changes')){const p=printers.find(p=>path.includes(p.printerNo));requireValue(p&&p.revision===body.expectedRevision,'stale printer');p.status=body.status;p.revision++;return operation('PRINTER_STATUS',{},p.printerNo);}
 if(path.endsWith('/templates')){if(!write)return page(templates);const no='FIXTURE-TEMPLATE';templates.push({templateNo:no,scene:body.scene,variantCode:body.variantCode,name:body.name,scopeKey:'STORE:'+body.storeNo,currentVersionNo:null,revision:0});return operation('TEMPLATE_CREATE',{templateNo:no},no);}
 if(path.endsWith('/routes')){if(!write)return page(routes);const no='FIXTURE-ROUTE';routes.push({routeNo:no,scene:body.scene,currentVersionNo:null,revision:0});return operation('ROUTE_CREATE',{routeNo:no},no);}
 if(path.endsWith('/versions')){const template=path.includes('/templates/'),head=(template?templates:routes)[0];requireValue(head&&head.revision===body.expectedRevision,'stale configuration');const no='FIXTURE-VERSION-'+(++seq);versions.set(no,{head,revision:0,body});head.revision++;return operation('VERSION_CREATE',template?{templateVersionNo:no}:{routeVersionNo:no},no);}
 if(path.endsWith('/publish')){const version=versions.get(path.split('/').at(-2));requireValue(version&&version.head.revision===(body.expectedTemplateRevision??body.expectedRouteRevision),'stale publish');version.head.currentVersionNo=path.split('/').at(-2);version.head.revision++;return operation('PUBLISH',{});}
 if(path.endsWith('/preview'))return {document:{businessData:frozen({mandatoryNotice:'支付小票',rows:[{text:'预览，非真实出纸'},{text:'实付',right:'88.00'}]}),systemSlots:{warnings:['浏览器合同夹具']}}};
 if(path.endsWith('/summary'))return {generationState:'SKIPPED',expectedCount:0,confirmedCount:0,replacementResolvedCount:0,unknownCount:1,failedCount:0,sourceFacts:multiRefunds?refundFacts:[fact]};
 if(path.endsWith('/jobs'))return page(jobs);
 if(path.endsWith('/queries'))return operation('QUERY',{},jobs[0].jobNo);
 if(path.endsWith('/requests')){if(!write)return page([{requestNo:'FIXTURE-REQUEST',orderNo:'FIXTURE-ORDER',subOrderNo:null,scene:'ORDER_PAID',status:'BLOCKED',blockCode:'NO_ROUTE',revision:0}]);requireValue(body.sourceRef.factNo===fact.factNo&&body.expectedSourceRevision===fact.bizRevision,'invalid ORDER_PAID source');return operation('SUBMIT',{requestNo:'FIXTURE-REQUEST'},'FIXTURE-REQUEST');}
 if(path.endsWith('/simulations')){if(!write)return page(simulations);if(multiRefunds)requireValue(refundFacts.some(ref=>ref.factNo===body.source?.factRef?.factNo&&ref.bizRevision===body.source?.factRef?.bizRevision),'exact refund fact revision required');const no='FIXTURE-SIM-'+(++seq);simulations.unshift({simulationNo:no,scene:body.scene,status:'PASSED',fixtureCode:body.fixtureCode,revision:1,expectedResults:frozen({outcome:'UNKNOWN'}),actualResults:frozen({outcome:'UNKNOWN',physicalOutputConfirmed:false}),eventJournal:frozen([{event:'CONTRACT_FIXTURE_ONLY'}])});return operation('SIMULATION',{simulationNo:no},no);}
 if(path.includes('/simulations/'))return simulations.find(s=>path.endsWith(s.simulationNo));
 if(path.endsWith('/exports')){if(!write)return page(exports);const no='FIXTURE-EXPORT-'+(++seq);exports.unshift({exportNo:no,sourceType:body.source.type,sourceNo:body.source.no,status:'READY',fileSize:600,pageCount:1,revision:1,expiresAt:'2099-01-01T00:00:00Z'});return operation('EXPORT',{exportNo:no},no);}
 if(path.endsWith('/download-grants'))return {url:'/acceptance/fixture.pdf'};
 if(path.endsWith('/incidents'))return page(incidents);
 if(path.endsWith('/acknowledgements')){requireValue(body.expectedRevision===incidents[0].revision,'stale incident');incidents[0].status='ACKNOWLEDGED';incidents[0].revision++;return operation('ACKNOWLEDGE',{},incidents[0].incidentNo);}
 throw Error('unsupported fixture endpoint: '+method+' '+path);
}
function fixturePdf(){const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 226 320] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];const content='BT /F1 10 Tf 12 290 Td (CONTRACT FIXTURE ONLY) Tj 0 -20 Td (Not physical output evidence) Tj ET';objects.push(`<< /Length ${content.length} >>\nstream\n${content}\nendstream`);let pdf='%PDF-1.4\n',offsets=[0];objects.forEach((o,i)=>{offsets.push(Buffer.byteLength(pdf));pdf+=`${i+1} 0 obj\n${o}\nendobj\n`;});const start=Buffer.byteLength(pdf);pdf+='xref\n0 6\n0000000000 65535 f \n'+offsets.slice(1).map(o=>String(o).padStart(10,'0')+' 00000 n \n').join('')+`trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${start}\n%%EOF`;return pdf;}
await mkdir(output,{recursive:true});
createServer(async(req,res)=>{
 try{
  const url=new URL(req.url,'http://127.0.0.1:'+port),path=url.pathname;
  if(path==='/acceptance/start'){
   const identity=url.searchParams.get('identity');
   const profile=identity==='refund'?{token:manifest?.refundToken,store:manifest?.refundStoreNo,order:manifest?.refundOrderNo}:identity==='wallet'?{token:manifest?.walletToken,store:manifest?.storeNo,order:manifest?.rechargeNo}:identity==='recharge'?{token:manifest?.token,store:manifest?.storeNo,order:manifest?.rechargeNo}:null;
   const token=profile?.token||url.searchParams.get('token')||(backend?'':'fixture-owner'),store=profile?.store||url.searchParams.get('store')||(backend?'':'FIXTURE-STORE');
   requireValue(token&&store,'real backend needs token and store parameters');
   const target='/#/pages/print-center/index?orderNo='+encodeURIComponent(profile?.order||url.searchParams.get('order')||'FIXTURE-ORDER');
   res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store'});res.end(`<script>localStorage.setItem('shbr_token',${JSON.stringify(token).replaceAll('<','\\u003c')});localStorage.setItem('shbr_store_no',${JSON.stringify(store).replaceAll('<','\\u003c')});location.replace(${JSON.stringify(target)});</script>`);return;
  }
  if(path==='/acceptance/evidence'){json(res,{mode:backend?'REAL_ISOLATED_BACKEND':'BROWSER_CONTRACT_FIXTURE',requests:log});return;}
  if(path==='/acceptance/fixture.pdf'&&!backend){res.writeHead(200,{'Content-Type':'application/pdf','Cache-Control':'no-store'});res.end(fixturePdf());return;}
  if(path.startsWith('/biz/')||path.startsWith('/mp/')||path.startsWith('/ops/')||(backend&&path==='/private/print-artifacts')){
   if(backend){const proxy=httpRequest(new URL(req.url,backend),{method:req.method,headers:{...req.headers}},upstream=>{res.writeHead(upstream.statusCode,upstream.headers);upstream.pipe(res);});proxy.on('error',e=>json(res,{error:e.message},1,502));req.pipe(proxy);return;}
   const chunks=[];for await(const chunk of req)chunks.push(chunk);const body=chunks.length?JSON.parse(Buffer.concat(chunks).toString()):{};
   // Never record cloud secrets or tokens. This fixture records only the fields asserted by acceptance.
   const record={method:req.method,path,store:req.headers['x-store-no'],hasIdempotencyKey:!!req.headers['idempotency-key'],canonicalDeviceNo:body.connection?.config?.canonicalDeviceNo,source:body.sourceRef||body.source,expectedRevision:body.expectedRevision};log.push(record);await appendFile(resolve(output,'merchant-contract-requests.jsonl'),JSON.stringify(record)+'\n');
   const key=req.headers['idempotency-key'];const intent=JSON.stringify([req.method,path,body]);
   if(key&&intents.has(key)){const prev=intents.get(key);requireValue(prev.intent===intent,'idempotency key conflict');json(res,prev.value);return;}
   const value=fixture(req.method,path,url.searchParams,body,req);if(key)intents.set(key,{intent,value});json(res,value);return;
  }
  const isOps=path==='/ops-web'||path.startsWith('/ops-web/'),staticRoot=isOps?opsRoot:root;
  const relative=isOps?(path.slice('/ops-web'.length)||'/'):path;
  let file=resolve(staticRoot,'.'+decodeURIComponent(relative==='/'?'/index.html':relative));requireValue(file.startsWith(staticRoot+sep),'invalid static path');
  try{if((await stat(file)).isDirectory())file=resolve(file,'index.html');}catch(error){if(!extname(file))file+='.html';else throw error;}
  const bytes=await readFile(file);const type={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.woff2':'font/woff2'}[extname(file)]||'application/octet-stream';res.writeHead(200,{'Content-Type':type,'Cache-Control':'no-store'});res.end(bytes);
 }catch(e){json(res,{error:e.message},1,e.code==='ENOENT'?404:422);}
}).listen(port,'127.0.0.1',()=>console.log(`Merchant ${backend?'REAL isolated backend proxy':'CONTRACT FIXTURE ONLY'} at http://127.0.0.1:${port}/acceptance/start`));
