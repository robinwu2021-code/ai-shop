import {spawn,spawnSync,execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync,mkdirSync,createWriteStream} from 'node:fs';
import {randomUUID,randomBytes,createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url)).replace(/\/$/,'');
const dir=process.env.BEAUTY_BM01_EVIDENCE_DIR ?? '/tmp/beauty-bm01-deployment-'+randomUUID();mkdirSync(dir,{recursive:true});
const name='codex-beauty-bm01-'+randomUUID();const processes=[];
const docker=(args,input)=>execFileSync('docker',args,{input,encoding:'utf8',maxBuffer:20*1024*1024,stdio:['pipe','pipe','pipe']}).trim();
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const secret=randomBytes(24).toString('hex');
let dbPort;
const sql=text=>docker(['exec','-i',name,'mysql','--default-character-set=utf8mb4','-uroot','-N','-B','beauty_bm01'],text);
async function startJar(label,jar,extra=[]){
 const file=createWriteStream(dir+'/'+label+'.log');
 const java=process.env.JAVA_HOME+'/bin/java';
 const args=['-Xmx768m','-jar',(jar.startsWith('/')?jar:root+'/'+jar),'--server.address=127.0.0.1','--server.port=0',
 '--spring.datasource.url=jdbc:mysql://127.0.0.1:'+dbPort+'/beauty_bm01?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC',
 '--spring.datasource.username=root','--spring.datasource.password=','--spring.flyway.enabled=false',
 '--shop.beauty.enabled='+!extra.includes('--shop.beauty.enabled=false'),'--shop.beauty.migration.mode=migrate',
 '--shop.beauty.publication.archive-only=true','--shop.catalog.publication.archive-only=true','--shop.services.internal-token='+secret,...extra.filter(arg=>arg!=='--shop.beauty.enabled=false')];
 const child=spawn(java,args,{cwd:root,stdio:['ignore','pipe','pipe']});let output='';
 const result={child,done:new Promise(resolve=>child.on('exit',resolve))};processes.push(result);
 child.stdout.on('data',b=>{file.write(b);output=(output+b).slice(-120000);});
 child.stderr.on('data',b=>{file.write(b);output=(output+b).slice(-120000);});
 for(let i=0;i<1200;i++){
  if(child.exitCode!==null)throw Error(label+' exited before ready; inspect '+dir+'/'+label+'.log');
  const m=output.match(/Tomcat started on port ([0-9]+)/);
  if(m&&/Started (ShopApplication|BeautyApplication)/.test(output))return {url:'http://127.0.0.1:'+m[1],...result};
  await pause(100);
 }
 throw Error(label+' startup deadline; inspect '+dir+'/'+label+'.log');
}
async function stop(p){if(p.child.exitCode===null){p.child.kill('SIGTERM');await Promise.race([p.done,pause(10000)]);if(p.child.exitCode===null)p.child.kill('SIGKILL');}await p.done;}
async function request(url,path,{token,body,key,expected=200,errorCode=0,headers={}}={}){
 const response=await fetch(url+path,{method:body?'POST':'GET',headers:{...(body?{'Content-Type':'application/json'}:{}),...(token?{Authorization:'Bearer '+token}:{}),...(key?{'Idempotency-Key':key}:{}),...headers},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(15000)});
 const text=await response.text();assert.equal(response.status,expected,path+' status; body='+text.slice(0,500));
 let value;try{value=JSON.parse(text);}catch{return text;}if(expected===200&&Object.hasOwn(value,'code'))assert.equal(value.code,errorCode,path+' business result: '+value.msg);return Object.hasOwn(value,'data')?value.data:value;
}
try{
 docker(['run','--pull','never','--detach','--name',name,'--publish','127.0.0.1::3306','--tmpfs','/var/lib/mysql','--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_ROOT_HOST=%','mysql:9.7.2']);
 let ready=false;for(let i=0;i<200;i++){try{docker(['exec',name,'mysql','--protocol=TCP','-h127.0.0.1','-uroot','-e','SELECT 1']);ready=true;break;}catch{await pause(200);}}
 assert(ready,'own disposable database not ready');dbPort=docker(['port',name,'3306/tcp']).split(':')[1];
 docker(['exec',name,'mysql','-uroot','-e','CREATE DATABASE beauty_bm01 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci']);
 const original=readFileSync(root+'/backend/shop-app/src/test/resources/schema-test.sql','utf8');
 // Explicit fixture for unchanged platform tables. This is NOT a production migration replay or rollout proof.
 const native=original.replace(/\b([A-Za-z_]\w*) IS JSON\b/g,'JSON_VALID($1)');
 sql(native);
 sql('DROP TABLE prd_store_catalog_version; DROP TABLE prd_store_catalog;');
 sql(readFileSync(root+'/backend/shop-app/src/main/resources/db/migration/V536__shared_store_sales_catalog.sql','utf8'));
 console.log('Native platform fixture loaded; historical platform migration replay NOT claimed.');
 const host=await startJar('embedded',process.env.BEAUTY_EMBEDDED_JAR ?? 'backend/shop-app/target/shop-app-0.1.0-SNAPSHOT.jar',
 ['--spring.profiles.active=api','--shop.beauty.deployment=embedded','--shop.seed.enabled=false',
 '--shop.inventory.enabled=false','--shop.auth.token-store=memory','--shop.pay.deployment=standalone']);
 console.log('Embedded real artifact started.');
 const hash=readFileSync(root+'/deploy/tencent/mysql97/migrate/seed-accounts-test.sql','utf8').match(/VALUES \('ST-ADMIN', 'admin', '([^']+)'/)[1];
 sql(`INSERT INTO mch_entity(entity_no,name,owner_user_no,status,created_at,updated_at) VALUES('M-BM01','美业验证门店','U-BM01','ACTIVE',NOW(),NOW());
 INSERT INTO mch_store(store_no,entity_no,name,is_default,status,created_at,updated_at) VALUES('ST-M-BM01','M-BM01','美业验证门店',1,'ACTIVE',NOW(),NOW());
 INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,created_at,updated_at) VALUES('MC-BM01','M-BM01','U-BM01',1,1,'ACTIVE',NOW(),NOW());
 INSERT INTO usr_account(user_no,nickname,phone,status,created_at,updated_at) VALUES('U-BM01','美业验证','19700000001','NORMAL',NOW(),NOW());
 INSERT INTO usr_identity(user_no,identity_type,identity_value,verified_at,created_at,updated_at) VALUES('U-BM01','PHONE','19700000001',NOW(),NOW(),NOW()),('U-BM01','PASSWORD','${hash}',NOW(),NOW(),NOW());
 INSERT INTO prd_goods(goods_no,entity_no,title,cover,type,duration_min,on_sale,audit_status,store_no,created_at,updated_at) VALUES('BM01-G','M-BM01','护理项目','cover','SERVICE',60,1,'APPROVED','ST-M-BM01',NOW(),NOW());
 INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,created_at,updated_at) VALUES('BM01-SKU','BM01-G','M-BM01','CN',9900,NOW(),NOW());
 INSERT INTO prd_store_goods(store_no,goods_no,entity_no,on_sale) VALUES('ST-M-BM01','BM01-G','M-BM01',1);`);
 const auth=await request(host.url,'/biz/auth/login',{body:{grantType:'PASSWORD',principal:'19700000001',credential:'admin123',agreed:true}});
 const token=auth.token;assert(token,'real merchant authentication must issue token');
 const policy={expectedRevision:0,validFrom:'2026-10-20T00:00:00Z',policy:{bookingAheadDays:30,cancelRule:{freeCancelBeforeMinutes:60,allowCustomerCancel:true},allowedModes:['PREPAID_SINGLE']}};
 const first=await request(host.url,'/biz/x/beauty/store-policies/versions',{token,body:policy,key:'BM01-POLICY'});
 assert.deepEqual(await request(host.url,'/biz/x/beauty/store-policies/versions',{token,body:policy,key:'BM01-POLICY'}),first);
 const definition={skuNo:'BM01-SKU',expectedRevision:0,duration:{minutes:60,bufferBeforeMin:5,bufferAfterMin:10},requirements:[{roleCode:'TECH',resourceType:'STAFF',skillGoodsNo:'BM01-G',qty:1,sequenceNo:0,offsetMin:0,durationMin:60,selectionRule:'ANY_QUALIFIED'}],notes:['请提前到店']};
 await request(host.url,'/biz/x/beauty/service-definitions/versions',{token,body:definition,key:'BM01-DEF'});
 const view=await request(host.url,'/mp/x/beauty/services/BM01-SKU?storeNo=ST-M-BM01');
 assert.equal(view.title,'护理项目');assert.equal(view.priceMinor,9900);assert.deepEqual(view.notes,['请提前到店']);
 assert(!('entityNo'in view)&&!('requirements'in view));
 await request(host.url,'/biz/x/beauty/service-definitions/versions',{body:definition,key:'ANON',expected:401});
 console.log('Real authenticated publish and anonymous service lookup passed.');
 const catalog={expectedRevision:0,validFrom:'2026-10-20T00:00:00Z',timezone:'Asia/Shanghai',
 groups:[{key:'FACE',title:'面部护理',position:0,visible:true}],entries:[{skuNo:'BM01-SKU',groupKey:'FACE',position:0,
 visible:true,salesMode:'WINDOWED',windows:[{daysMask:'1111111',startMin:0,endMin:1}]}]};
 const published=await request(host.url,'/biz/store-sales-catalog/versions',{token,body:catalog,key:'BM01-CATALOG'});
 assert.equal(published.effectiveRevision,1);
 assert.deepEqual(await request(host.url,'/biz/store-sales-catalog/versions',{token,body:catalog,key:'BM01-CATALOG'}),published);
 const windowed=await request(host.url,'/mp/x/beauty/services/BM01-SKU?storeNo=ST-M-BM01');
 assert.equal(windowed.sales.salesMode,'WINDOWED');assert.equal(windowed.sales.windows[0].endMin,1);
 const nowLocal=new Date(Date.now()+8*3600*1000);assert.equal(windowed.sales.saleableNow,nowLocal.getUTCHours()===0&&nowLocal.getUTCMinutes()===0);
 const publicStore=await request(host.url,'/mp/x/beauty/stores/ST-M-BM01');
 assert.deepEqual(publicStore.catalog.groups.map(g=>g.key),['FACE']);assert(!('entries' in publicStore.catalog));
 await request(host.url,'/biz/store-sales-catalog/versions',{body:catalog,key:'ANON-CATALOG',expected:401});
 await request(host.url,'/biz/store-sales-catalog',{token,headers:{'X-Store-No':'FOREIGN'},errorCode:10403});
 const before=sql('SELECT COUNT(*) FROM prd_store_catalog_version');
 await request(host.url,'/biz/store-sales-catalog/versions',{token,body:{...catalog,expectedRevision:0},key:'STALE',errorCode:10409});
 assert.equal(sql('SELECT COUNT(*) FROM prd_store_catalog_version'),before);
 assert.equal(sql("SELECT COUNT(*) FROM sys_idempotent WHERE idem_key='STALE'"),'0');
 const hidden={...catalog,expectedRevision:1,groups:[{...catalog.groups[0],visible:false}]};
 await request(host.url,'/biz/store-sales-catalog/versions',{token,body:hidden,key:'HIDDEN'});
 await request(host.url,'/mp/x/beauty/services/BM01-SKU?storeNo=ST-M-BM01',{errorCode:10404});
 assert.equal((await request(host.url,'/mp/x/beauty/stores/ST-M-BM01')).catalog.groups.length,0);
 // Restore by publishing a new immutable version; the old row is never updated in place.
 const restored=await request(host.url,'/biz/store-sales-catalog/versions',{token,body:{...catalog,expectedRevision:2},key:'RESTORE'});
 assert.equal(restored.effectiveRevision,3);
 const future={...catalog,expectedRevision:3,validFrom:'2099-01-01T00:00:00Z',groups:[{...catalog.groups[0],title:'未来'}]};
 await request(host.url,'/biz/store-sales-catalog/versions',{token,body:future,key:'FUTURE'});
 assert.equal((await request(host.url,'/biz/store-sales-catalog?at=2026-10-21T00:00:00Z',{token})).effectiveRevision,3);
 assert.equal((await request(host.url,'/biz/store-sales-catalog?at=2099-01-02T00:00:00Z',{token})).effectiveRevision,4);
 await request(host.url,'/biz/goods/BM01-G/store-price',
 {token,body:{skuNo:'BM01-SKU',price:12900,originPrice:null}});
 assert.equal(sql("SELECT price FROM prd_sku WHERE sku_no='BM01-SKU'"),'9900');
 assert.equal(sql("SELECT price FROM prd_store_price WHERE store_no='ST-M-BM01' AND sku_no='BM01-SKU'"),'12900');
 assert.equal((await request(host.url,'/mp/x/beauty/services/BM01-SKU?storeNo=ST-M-BM01')).priceMinor,12900);
 sql("UPDATE prd_store_goods SET on_sale=0 WHERE goods_no='BM01-G'");
 assert.deepEqual(await request(host.url,'/biz/store-sales-catalog/versions',{token,body:catalog,key:'BM01-CATALOG'}),published);
 await request(host.url,'/biz/store-sales-catalog/versions',
 {token,body:{...catalog,expectedRevision:4},key:'NEW-OFFSHELF',errorCode:10400});
 sql("UPDATE prd_store_goods SET on_sale=1 WHERE goods_no='BM01-G'");
 assert.equal(sql("SELECT COUNT(*) FROM sys_outbox WHERE event_type='PRODUCT_STORE_CATALOG_VERSION_PUBLISHED' AND status='ARCHIVED'"),'4');
 console.log('Actual catalog visibility, windows, owner quote, history, replay, scope and rollback passed.');
 const history=sql('SELECT version FROM bty_flyway_history WHERE success=1 AND version IS NOT NULL');
 await stop(host);
 const restarted=await startJar('embedded-restarted',process.env.BEAUTY_EMBEDDED_JAR ?? 'backend/shop-app/target/shop-app-0.1.0-SNAPSHOT.jar',
 ['--spring.profiles.active=api','--shop.beauty.deployment=embedded','--shop.seed.enabled=false',
 '--shop.inventory.enabled=false','--shop.auth.token-store=memory','--shop.pay.deployment=standalone']);
 assert.equal((await request(restarted.url,'/mp/x/beauty/services/BM01-SKU?storeNo=ST-M-BM01')).priceMinor,12900);
 assert.equal(sql('SELECT version FROM bty_flyway_history WHERE success=1 AND version IS NOT NULL'),history);
 const server=await startJar('standalone-service','backend-plugins/shop-industry-beauty/beauty-server/target/beauty-server-0.1.0-SNAPSHOT.jar',
 ['--shop.beauty.deployment=standalone','--shop.services.targets.PLATFORM='+restarted.url]);
 await request(server.url,'/internal/beauty/catalog/public-service',{body:{skuNo:'BM01-SKU',query:{storeNo:'ST-M-BM01'}},expected:401});
 const remote=await request(server.url,'/internal/beauty/catalog/public-service',
 {body:{skuNo:'BM01-SKU',query:{storeNo:'ST-M-BM01'}},headers:{'X-Internal-Token':secret}});
 assert.equal(remote.priceMinor,12900);
 assert(process.env.BEAUTY_STANDALONE_HOST_JAR,'Build and supply the real standalone host artifact');
 const standalone=await startJar('standalone-host',process.env.BEAUTY_STANDALONE_HOST_JAR,
 ['--spring.profiles.active=api','--shop.beauty.deployment=standalone','--shop.services.targets.BEAUTY='+server.url,
 '--shop.seed.enabled=false','--shop.inventory.enabled=false','--shop.auth.token-store=memory','--shop.pay.deployment=standalone']);
 assert.equal((await request(standalone.url,'/mp/x/beauty/services/BM01-SKU?storeNo=ST-M-BM01')).priceMinor,12900);
 const remoteAuth=await request(standalone.url,'/biz/auth/login',{body:{grantType:'PASSWORD',principal:'19700000001',credential:'admin123',agreed:true}});
 const policy2={...policy,expectedRevision:1,policy:{...policy.policy,bookingAheadDays:40}};
 await request(standalone.url,'/biz/x/beauty/store-policies/versions',{token:remoteAuth.token,body:policy2,key:'REMOTE-POLICY'});
 assert.equal((await request(standalone.url,'/mp/x/beauty/stores/ST-M-BM01')).policy.bookingAheadDays,40);
 const serviceReadiness=await fetch(server.url+'/actuator/health/readiness');
 assert.equal(serviceReadiness.status,503,'standalone overall business readiness remains gated');
 await stop(server);
 // Existing data remains, but a failed remote owner must never turn into a success/default catalog.
 const outage=await fetch(standalone.url+'/mp/x/beauty/stores/ST-M-BM01');
 const failure=await outage.json();assert(outage.status>=400||failure.code!==0,'remote outage must fail closed');
 const readiness=await fetch(restarted.url+'/actuator/health/readiness');
 const readinessStatus=readiness.status;assert([200,503].includes(readinessStatus));
 const live=await fetch(restarted.url+'/actuator/health/liveness');assert.equal(live.status,200);
 console.log('Embedded restart, real standalone host-service-owner calls, permission and owner outage passed.');
 assert(process.env.BEAUTY_DEFAULT_RETAIL_JAR,'Supply default retail artifact for impact regression');
 const retail=await startJar('default-retail',process.env.BEAUTY_DEFAULT_RETAIL_JAR,
 ['--spring.profiles.active=api','--shop.beauty.enabled=false','--shop.seed.enabled=false',
 '--shop.inventory.enabled=false','--shop.auth.token-store=memory','--shop.pay.deployment=standalone']);
 const absent=await fetch(retail.url+'/mp/x/beauty/stores/ST-M-BM01');
 assert.equal(absent.status,404,'default retail artifact must not expose beauty routes');
 const retailAuth=await request(retail.url,'/biz/auth/login',{body:{grantType:'PASSWORD',principal:'19700000001',credential:'admin123',agreed:true}});
 assert.equal((await request(retail.url,'/biz/store-sales-catalog',{token:retailAuth.token})).effectiveRevision,3);
 console.log('Default retail actual artifact started without beauty and reused the same product-owned catalog.');
 const artifacts={};for(const [key,path] of Object.entries({embedded:process.env.BEAUTY_EMBEDDED_JAR,
 standaloneHost:process.env.BEAUTY_STANDALONE_HOST_JAR,defaultRetail:process.env.BEAUTY_DEFAULT_RETAIL_JAR,beautyServer:root+'/backend-plugins/shop-industry-beauty/beauty-server/target/beauty-server-0.1.0-SNAPSHOT.jar'})) {
 artifacts[key]=createHash('sha256').update(readFileSync(path)).digest('hex');}
 writeFileSync(dir+'/result.json',JSON.stringify({status:'BM01_RUNTIME_CASES_PASSED',dialect:'MySQL 9.7.2',
 platformSchema:'explicit platform fixture; historical Flyway replay NOT represented',migration:'actual V536 on platform fixture',
 beautyHistory:history,artifacts,readinessStatus,standaloneReadinessStatus:serviceReadiness.status,scope:'BM-01 only; appointment/resource authority still gated'},null,2));
 console.log('Evidence: '+dir);


}finally{
 for(const p of processes.reverse())await stop(p);
 try{const dbLog=spawnSync('docker',['logs',name],{encoding:'utf8'});writeFileSync(dir+'/database.log',(dbLog.stdout??'')+(dbLog.stderr??''));writeFileSync(dir+'/database-state.json',docker(['inspect','--format','{{json .State}}',name]));}catch{}
 try{docker(['rm','--force',name]);}catch{}
}
