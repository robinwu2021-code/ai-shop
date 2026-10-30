// BM03 real-artifact gate. SQL seeds owner/auth prerequisites and verifies facts; booking actions use HTTP.
import {spawn,execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync,createWriteStream} from 'node:fs';
import {randomUUID,randomBytes,createHash} from 'node:crypto';
import {createServer} from 'node:net';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {startFaultProxy} from './lib/beauty-bm03-proxy.mjs';
import {setupBusiness,lifecycle,recoveryCases} from './lib/beauty-bm03-runtime.mjs';
const root=fileURLToPath(new URL('../',import.meta.url)).replace(/\/$/,'');
const dir=process.env.BEAUTY_BM03_EVIDENCE_DIR??'/tmp/beauty-bm03-deployment-'+randomUUID();mkdirSync(dir,{recursive:true});
const artifactsDir=process.env.BEAUTY_BM03_ARTIFACT_DIR??'/tmp/beauty-bm03-artifacts';
const java='/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java';
const name='codex-beauty-bm03-'+randomUUID(),processes=[],proxies=[],calls=[];
const secret=randomBytes(24).toString('hex'),jobSecret=randomBytes(24).toString('hex');
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const docker=(args,input)=>execFileSync('docker',args,{input,encoding:'utf8',maxBuffer:30*1024*1024,stdio:['pipe','pipe','pipe']}).trim();
let dbPort;const sql=(text,db='platform')=>docker(['exec','-i',name,'mysql','--default-character-set=utf8mb4','-uroot','-N','-B',db],text);
const jdbc=db=>'jdbc:mysql://127.0.0.1:'+dbPort+'/'+db+'?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC';
async function freePort(){const s=createServer();await new Promise(r=>s.listen(0,'127.0.0.1',r));const p=s.address().port;await new Promise(r=>s.close(r));return p;}
async function start(label,artifact,{db='platform',port=0,extra=[],worker=false}={}){
 const log=createWriteStream(dir+'/'+label+'.log');
 const args=['-Xmx640m','-jar',artifactsDir+'/'+artifact+'.jar',...(!worker?[
 '--server.address=127.0.0.1','--server.port='+port,'--spring.profiles.active=api',
 '--spring.datasource.url='+jdbc(db),'--spring.datasource.username=root','--spring.datasource.password=',
 '--spring.flyway.enabled=false','--shop.beauty.enabled=true','--shop.beauty.migration.mode=migrate',
 '--shop.beauty.publication.archive-only=true','--shop.catalog.publication.archive-only=true',
 '--shop.personnel.publication.archive-only=true','--shop.resource.publication.archive-only=true',
 '--shop.schedule.publication.archive-only=true','--shop.services.internal-token='+secret,
 '--shop.seed.enabled=false','--shop.inventory.enabled=false','--shop.auth.token-store=memory','--shop.pay.deployment=standalone']:[]),...extra];
 const child=spawn(java,args,{cwd:root,stdio:['ignore','pipe','pipe']});let output='';
 const p={child,done:new Promise(r=>child.on('exit',r))};processes.push(p);
 for(const stream of [child.stdout,child.stderr])stream.on('data',b=>{log.write(b);output=(output+b).slice(-160000);});
 for(let i=0;i<1200;i++){
  if(child.exitCode!==null)throw Error(label+' exited: '+dir+'/'+label+'.log');
  const m=output.match(/Tomcat started on port ([0-9]+)/);
  if((m||worker)&&/Started (ShopApplication|BeautyApplication|JobApplication)/.test(output))return {...p,url:m?'http://127.0.0.1:'+m[1]:null};
  await pause(100);
 }
 throw Error(label+' startup timeout: '+dir+'/'+label+'.log');
}
async function stop(p){if(p.child.exitCode===null){p.child.kill('SIGTERM');await Promise.race([p.done,pause(12000)]);if(p.child.exitCode===null)p.child.kill('SIGKILL');}await p.done;}
async function request(url,path,{token,body,key,method,expected=200,errorCode=0,headers={}}={}){
 const response=await fetch(url+path,{method:method??(body!==undefined?'POST':'GET'),headers:{...(body!==undefined?{'Content-Type':'application/json'}:{}),...(token?{Authorization:'Bearer '+token}:{}),...(key?{'Idempotency-Key':key}:{}),...headers},body:body!==undefined?JSON.stringify(body):undefined,signal:AbortSignal.timeout(30000)});
 const raw=await response.text();let value;try{value=JSON.parse(raw);}catch{value=raw;}
 calls.push({path,method:method??(body!==undefined?'POST':'GET'),status:response.status,code:value?.code,result:path.includes('login')?'authenticated':value});
 assert.equal(response.status,expected,path+' status '+raw.slice(0,700));
 if(expected===200&&Object.hasOwn(value,'code'))assert.equal(value.code,errorCode,path+' result '+JSON.stringify(value));
 return Object.hasOwn(value,'data')?value.data:value;
}
const login=async(url,phone,consumer=false)=>(await request(url,consumer?'/mp/user/login':'/biz/auth/login',{body:{grantType:'PASSWORD',principal:phone,credential:'admin123',agreed:true}})).token;
const result={status:'RUNNING',artifacts:{},scope:'BM03 only; native platform fixture, not full historical migration replay; archived events are not delivered notifications',cases:[]};
try{
 for(const artifact of ['embedded','standalone','beauty-server','retail','job-worker']){
  const path=artifactsDir+'/'+artifact+'.jar',content=execFileSync('unzip',['-Z1',path],{encoding:'utf8',maxBuffer:8e6});
  result.artifacts[artifact]={sha256:createHash('sha256').update(readFileSync(path)).digest('hex'),path};
  writeFileSync(dir+'/'+artifact+'-contents.txt',content);
  if(artifact==='standalone'){assert(content.includes('/beauty-channel-'));assert(content.includes('/beauty-web-'));assert(!content.includes('/beauty-core-'));}
  if(artifact==='beauty-server'){assert(content.includes('/beauty-core-'));assert(!content.includes('/beauty-web-'));assert(!content.includes('/shop-app-'));}
  if(artifact==='retail')assert(!content.includes('/beauty-'));
 }
 docker(['run','--pull','never','--detach','--name',name,'--publish','127.0.0.1::3306','--tmpfs','/var/lib/mysql','--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_ROOT_HOST=%','mysql:9.7.2']);
 let ready=false;for(let i=0;i<200;i++){try{docker(['exec',name,'mysql','--protocol=TCP','-h127.0.0.1','-uroot','-e','SELECT 1']);ready=true;break;}catch{await pause(200);}}
 assert(ready);dbPort=docker(['port',name,'3306/tcp']).split(':')[1];
 docker(['exec',name,'mysql','-uroot','-e','CREATE DATABASE platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE beauty_private CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE scheduler CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;']);
 const fixture=readFileSync(root+'/backend/shop-app/src/test/resources/schema-test.sql','utf8').replace(/\b([A-Za-z_]\w*) IS JSON\b/g,'JSON_VALID($1)');
 sql(fixture);sql('DROP TABLE prd_store_catalog_version; DROP TABLE prd_store_catalog;');
 sql(readFileSync(root+'/backend/shop-app/src/main/resources/db/migration/V596__shared_store_sales_catalog.sql','utf8'));
 const infra=['sys_idempotent','sys_outbox','sys_outbox_stream','sys_outbox_delivery','sys_outbox_operation','shedlock'];
 for(const table of infra){const pattern=new RegExp('CREATE TABLE IF NOT EXISTS '+table+'\\s*\\([\\s\\S]*?\\n\\);');const ddl=fixture.match(pattern)?.[0];assert(ddl,'infrastructure fixture '+table);sql(ddl.replace(' IF NOT EXISTS',''),'beauty_private');}
 const cryptoEntry=execFileSync('unzip',['-Z1',artifactsDir+'/retail.jar'],{encoding:'utf8'}).split('\n').find(s=>s.includes('/spring-security-crypto-'));
 const cryptoJar=dir+'/security-crypto.jar';writeFileSync(cryptoJar,execFileSync('unzip',['-p',artifactsDir+'/retail.jar',cryptoEntry]));
 const hashes=execFileSync(java,['--class-path',cryptoJar,root+'/scripts/fixtures/BeautyPasswordHashes.java'],{encoding:'utf8'}).trim().split('\n');
 sql(`INSERT INTO mch_entity(entity_no,name,owner_user_no,status,created_at,updated_at) VALUES('M-BM03','BM03','U-BM03','ACTIVE',NOW(),NOW());
 INSERT INTO mch_store(store_no,entity_no,name,is_default,status,created_at,updated_at) VALUES('ST-BM03','M-BM03','BM03',1,'ACTIVE',NOW(),NOW());
 INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,created_at,updated_at) VALUES('MC-BM03','M-BM03','U-BM03',1,1,'ACTIVE',NOW(),NOW());
 INSERT INTO prd_goods(goods_no,entity_no,title,cover,type,duration_min,on_sale,audit_status,store_no,created_at,updated_at) VALUES('BM03-G','M-BM03','护理项目','cover','SERVICE',60,1,'APPROVED','ST-BM03',NOW(),NOW());
 INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,created_at,updated_at) VALUES('BM03-SKU','BM03-G','M-BM03','CN',9900,NOW(),NOW());
 INSERT INTO prd_store_goods(store_no,goods_no,entity_no,on_sale) VALUES('ST-BM03','BM03-G','M-BM03',1);`);
 for(const [user,phone] of [['U-BM03','19700000001'],['U-C03','19700000003'],['U-M03','19700000004'],['U-M04','19700000005'],['U-C04','19700000006']]){const hash=hashes.shift();sql(`INSERT INTO usr_account(user_no,nickname,phone,status,created_at,updated_at) VALUES('${user}','BM03','${phone}','NORMAL',NOW(),NOW()); INSERT INTO usr_identity(user_no,identity_type,identity_value,verified_at,created_at,updated_at) VALUES('${user}','PHONE','${phone}',NOW(),NOW(),NOW()),('${user}','PASSWORD','${hash}',NOW(),NOW(),NOW());`);}
 sql(`INSERT INTO mch_account(mch_account_no,entity_no,user_no,login_phone,display_name,is_owner,is_primary,status,created_at,updated_at) VALUES
 ('MC-M03','M-BM03','U-M03','19700000004','BM03 manager one',0,1,'ACTIVE',NOW(),NOW()),
 ('MC-M04','M-BM03','U-M04','19700000005','BM03 manager two',0,1,'ACTIVE',NOW(),NOW());`);
 const host=await start('embedded','embedded',{extra:['--shop.beauty.deployment=embedded']});
 const token=await login(host.url,'19700000001');
 const setup=await setupBusiness({request,url:host.url,token,sql});
 result.embedded=await lifecycle({request,url:host.url,token,login,sql,state:setup,label:'embedded'});
 await stop(host);console.log('Embedded lifecycle passed; process stopped before standalone owner launch.');
 const hostPort=await freePort(),serverPort=await freePort();
 const platformProxy=await startFaultProxy('http://127.0.0.1:'+hostPort),sourceProxy=await startFaultProxy('http://127.0.0.1:'+serverPort);proxies.push(platformProxy,sourceProxy);
 const serverOptions={db:'beauty_private',port:serverPort,extra:['--shop.beauty.deployment=standalone','--shop.services.targets.PLATFORM='+platformProxy.url,'--shop.job.enabled=true','--shop.job.internal-token='+jobSecret]};
 let server=await start('standalone-server','beauty-server',serverOptions);
 const standalone=await start('standalone-host','standalone',{port:hostPort,extra:['--shop.beauty.deployment=standalone','--shop.services.targets.BEAUTY='+sourceProxy.url]});
 const btoken=await login(standalone.url,'19700000001');
 await setupBusiness({request,url:standalone.url,token:btoken,sql,state:setup,privateOnly:true});
 result.standalone=await lifecycle({request,url:standalone.url,token:btoken,login,sql:t=>sql(t,'beauty_private'),ownerSql:sql,state:setup,label:'standalone'});
 await request(server.url,'/internal/job/declarations',{expected:401});
 await request(server.url,'/internal/job/declarations',{headers:{'X-Job-Token':secret},expected:401});
 result.jobDeclaration=await request(server.url,'/internal/job/declarations',{headers:{'X-Job-Token':jobSecret}});
 assert.equal(result.jobDeclaration.length,1);
 let restarts=0;
 const restartServer=async()=>{await stop(server);server=await start('standalone-server-restarted-'+(++restarts),'beauty-server',serverOptions);};
 const workerConfig=dir+'/worker.properties';writeFileSync(workerConfig,'spring.main.web-application-type=none\n');
 const startWorker=label=>start(label,'job-worker',{worker:true,extra:['--spring.config.location='+workerConfig,'--shop.job.enabled=true','--shop.job.datasource.url='+jdbc('scheduler'),'--shop.job.datasource.username=root','--shop.job.datasource.password=','--shop.job.worker.targets.BEAUTY='+server.url,'--shop.job.worker.token='+jobSecret,'--shop.job.worker.start-disabled=false','--shop.job.worker.poll-interval=2s','--shop.job.worker.instance=bm03-module-gate']});
 result.recovery=await recoveryCases({request,url:standalone.url,token:btoken,login,sql:t=>sql(t,'beauty_private'),ownerSql:sql,jobSql:t=>sql(t,'scheduler'),state:setup,platformProxy,sourceProxy,restartServer,startWorker,stop,pause,secret});
 assert(sourceProxy.events.some(e=>e.path.includes('/appointment-source/')&&e.status===200),'actual reverse source HTTP calls');
 result.remoteCalls={owner:platformProxy.events.length,source:sourceProxy.events.filter(e=>e.path.includes('/appointment-source/')).length};
 result.beautyTables=sql('SHOW TABLES','beauty_private').split('\n');
 assert.equal(result.beautyTables.filter(t=>t.startsWith('bty_')&&t!=='bty_flyway_history').length,23);
 assert(result.beautyTables.every(t=>t.startsWith('bty_')||infra.includes(t)));
 result.history=sql('SELECT version FROM bty_flyway_history WHERE success=1 AND version IS NOT NULL','beauty_private');
 assert.equal(result.history,'0\n1\n2\n3\n4');
 result.readiness=(await fetch(server.url+'/actuator/health/readiness')).status;assert.equal(result.readiness,503);
 await stop(server);
 const command=result.recovery.outageRequest;
 assert(command);const failure=await fetch(standalone.url+command.path,{method:'POST',headers:{'Content-Type':'application/json','X-Internal-Token':secret},body:command.rawBody});
 const failed=await failure.text();assert(failure.status>=400||JSON.parse(failed).code!==0,'stopped source must fail closed at owner');
 result.sourceOutage={status:failure.status,body:failed};
 result.sourceOutage.command=sql("SELECT status FROM mch_resource_command WHERE command_no='"+JSON.parse(command.rawBody).commandNo+"'");
 assert.notEqual(result.sourceOutage.command,'SUCCEEDED');
 result.sourceDecisionCount=sql('SELECT COUNT(*) FROM bty_appointment_resource_decision','beauty_private');
 const retail=await start('default-retail','retail',{extra:['--shop.beauty.enabled=false']});
 assert.equal((await fetch(retail.url+'/mp/x/beauty/stores/ST-BM03')).status,404);
 const retailToken=await login(retail.url,'19700000001');
 assert((await request(retail.url,'/biz/resources',{token:retailToken})).records.some(r=>r.resourceNo===setup.resourceNo));
 await request(retail.url,'/biz/store-sales-catalog',{token:retailToken});
 result.status='BM03_RUNTIME_CASES_PASSED';console.log('BM03 gate passed. Evidence: '+dir);
}catch(error){result.status='FAILED';result.error=String(error.stack??error);throw error;}
finally{
 writeFileSync(dir+'/result.json',JSON.stringify(result,null,2));writeFileSync(dir+'/http-results.json',JSON.stringify(calls,null,2));
 for(let i=0;i<proxies.length;i++)writeFileSync(dir+'/proxy-'+i+'.json',JSON.stringify(proxies[i].events,null,2));
 for(const p of processes.reverse())await stop(p);
 for(const p of proxies)await p.close();
 try{writeFileSync(dir+'/database.log',docker(['logs',name]));docker(['rm','--force',name]);}catch{}
 console.log('Evidence: '+dir);
}
