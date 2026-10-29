import assert from 'node:assert/strict';
import {execFileSync,spawnSync} from 'node:child_process';
import {mkdirSync,readFileSync,writeFileSync,readdirSync} from 'node:fs';
import {join} from 'node:path';
const entity='M-BM01',store='ST-M-BM01',branch='ST-BM02-B';
const query=no=>'/biz/schedules?resourceNos='+no+'&from=2026-10-22T00:00:00Z&to=2026-10-24T00:00:00Z';
const rule=(resourceNo,expectedRevision,startMinute=600,endMinute=660)=>({resourceNo,expectedRevision,timezone:'UTC',
 weekdayMask:127,startMinute,endMinute,endDayOffset:0,validFrom:'2026-10-22',validTo:'2026-10-23',status:'ACTIVE',reason:'模块部署排班验证'});
export async function embedded({request,sql,url,token}){
 const assignment={expectedRevision:0,storeNo:store,validFrom:'2026-01-01T00:00:00Z',validTo:'2028-01-01T00:00:00Z',
 publicVisible:true,sortOrder:0,positions:['TECHNICIAN'],skills:[{goodsNo:'BM01-G',skuNo:'BM01-SKU'}]};
 const body={displayName:'BM02技师',assignment};
 const person=await request(url,'/biz/personnel',{token,body,key:'BM02-PERSON'});
 assert.deepEqual(await request(url,'/biz/personnel',{token,body,key:'BM02-PERSON'}),person);
 assert.equal(person.revision,1);assert(person.resourceNo);
 assert.equal(sql("SELECT COUNT(*) FROM mch_personnel_account"),'0','No login created for a business person');
 await request(url,'/biz/personnel',{expected:401});
 await request(url,'/biz/resources',{token,headers:{'X-Store-No':'FOREIGN'},errorCode:10403});
 const physicalBody={type:'ROOM',name:'BM02护理房',capacity:2,status:'ACTIVE',expectedRevision:0};
 const room=await request(url,'/biz/resources',{token,body:physicalBody,key:'BM02-ROOM'});
 assert.deepEqual(await request(url,'/biz/resources',{token,body:physicalBody,key:'BM02-ROOM'}),room);
 const staffDirectory=await request(url,'/biz/resources?type=STAFF',{token});
 const staffRevision=staffDirectory.records.find(r=>r.resourceNo===person.resourceNo).revision;
 const saved=await request(url,'/biz/schedule-rules',{token,body:rule(person.resourceNo,staffRevision),key:'BM02-STAFF-RULE'});
 assert.equal(saved.resourceRevision,staffRevision+1);
 assert.deepEqual(await request(url,'/biz/schedule-rules',{token,body:rule(person.resourceNo,staffRevision),key:'BM02-STAFF-RULE'}),saved);
 await request(url,'/biz/schedule-rules',{token,body:rule(room.resourceNo,1),key:'BM02-ROOM-RULE'});
 assert.equal((await request(url,query(person.resourceNo),{token})).windows.length,2);
 const before=sql('SELECT COUNT(*) FROM mch_schedule_rule');
 await request(url,'/biz/schedule-rules',{token,body:rule(person.resourceNo,staffRevision),key:'BM02-STALE',errorCode:10409});
 assert.equal(sql('SELECT COUNT(*) FROM mch_schedule_rule'),before);
 assert.equal(sql("SELECT COUNT(*) FROM sys_idempotent WHERE idem_key='BM02-STALE'"),'0');
 sql(`INSERT INTO mch_resource_bucket(bucket_no,entity_no,resource_no,start_at,end_at,capacity,held_qty,confirmed_qty,capacity_revision,created_at,updated_at)
 VALUES('BM02-BUCKET','${entity}','${room.resourceNo}','2026-10-22 10:00:00','2026-10-22 10:05:00',2,1,0,2,NOW(),NOW());`);
 const change={...physicalBody,resourceNo:room.resourceNo,expectedRevision:2,status:'SUSPENDED'};
 await request(url,'/biz/resources',{token,body:change,key:'BM02-HELD',errorCode:10409});
 await request(url,'/biz/schedule-rules',{token,body:rule(room.resourceNo,2),key:'BM02-HELD-RULE',errorCode:10409});
 sql("UPDATE mch_resource_bucket SET held_qty=0 WHERE bucket_no='BM02-BUCKET'");
 await request(url,'/biz/resources',{token,body:{...change,status:'ACTIVE',capacity:3},key:'BM02-CAPACITY',errorCode:10409});
 assert.equal((await request(url,'/biz/resources',{token,body:change,key:'BM02-SUSPEND-ROOM'})).revision,3);
 await request(url,'/biz/resources',{token,body:{...change,expectedRevision:3,status:'ACTIVE'},key:'BM02-RESTORE-ROOM'});
 sql(`INSERT INTO mch_store(store_no,entity_no,name,is_default,status,created_at,updated_at)
 VALUES('${branch}','${entity}','BM02分店',0,'ACTIVE',NOW(),NOW());`);
 await request(url,`/biz/personnel/${person.personnelNo}/assignments`,{token,
 headers:{'X-Store-No':branch},body:{...assignment,storeNo:branch,expectedRevision:1,positions:['WAITER'],skills:[]},key:'BM02-BRANCH'});
 assert.equal(sql(`SELECT COUNT(*) FROM mch_resource WHERE personnel_no='${person.personnelNo}'`),'1');
 await request(url,'/biz/schedule-rules',{token,headers:{'X-Store-No':branch},body:rule(person.resourceNo,saved.resourceRevision),key:'BM02-CROSS',errorCode:10409});
 await request(url,'/biz/schedule-rules',{token,headers:{'X-Store-No':branch},body:rule(person.resourceNo,saved.resourceRevision,660,720),key:'BM02-ADJACENT'});
 const staff=await request(url,'/biz/staff',{token,body:{loginPhone:'19700000002',displayName:'BM02成员'}});
 assert(staff.mchAccountNo);
 await request(url,`/biz/personnel/${person.personnelNo}/account-links`,{token,
 body:{expectedRevision:2,accountNo:staff.mchAccountNo},key:'BM02-LINK'});
 await request(url,`/biz/personnel/${person.personnelNo}/status-changes`,{token,
 body:{expectedRevision:3,status:'SUSPENDED',reason:'模块部署暂停'},key:'BM02-SUSPEND-PERSON'});
 assert.equal(sql(`SELECT status FROM mch_account WHERE mch_account_no='${staff.mchAccountNo}'`),'DISABLED');
 await request(url,`/biz/personnel/${person.personnelNo}/status-changes`,{token,
 body:{expectedRevision:4,status:'ACTIVE',reason:'模块部署恢复'},key:'BM02-RESTORE-PERSON'});
 assert.equal(sql(`SELECT status FROM mch_account WHERE mch_account_no='${staff.mchAccountNo}'`),'DISABLED');
 const actual=await request(url,query(person.resourceNo),{token});assert.equal(actual.windows.length,2);
 return {personnelNo:person.personnelNo,resourceNo:person.resourceNo,roomNo:room.resourceNo,
  entityNo:entity,storeNo:store,staffNo:staff.mchAccountNo,resourceRevision:actual.resourceRevisions[person.resourceNo]};
}
export async function restart({request,url,token,state}){
 const people=await request(url,'/biz/personnel?skill=BM01-G',{token});
 assert(people.records.some(p=>p.personnelNo===state.personnelNo));
 assert.equal((await request(url,query(state.resourceNo),{token})).windows.length,2);
}
export function probe({root,dir,dbPort,secret,platform,state,sql}){
 const extracted=join(dir,'server-extracted');mkdirSync(extracted,{recursive:true});
 const jar=join(root,'backend-plugins/shop-industry-beauty/beauty-server/target/beauty-server-0.1.0-SNAPSHOT.jar');
 execFileSync(process.env.JAVA_HOME+'/bin/jar',['-xf',jar],{cwd:extracted});
 const libs=join(extracted,'BOOT-INF/lib');
 const cp=[join(extracted,'BOOT-INF/classes'),...readdirSync(libs).filter(n=>n.endsWith('.jar')).map(n=>join(libs,n))].join(':');
 const fixture=join(dir,'probe');mkdirSync(fixture,{recursive:true});
 execFileSync(process.env.JAVA_HOME+'/bin/javac',['-cp',cp,'-d',fixture,join(root,'scripts/fixtures/BeautyMerchantFactsProbe.java')]);
 for(const mode of ['live','conflict','outage']){
  const failure=mode==='outage',conflict=mode==='conflict';
  const label='merchant-facts-'+mode;const output=join(dir,label+'.json');
  const args=['-Xmx512m','-cp',fixture+':'+cp,'BeautyMerchantFactsProbe','--server.address=127.0.0.1','--server.port=0',
  '--spring.datasource.url=jdbc:mysql://127.0.0.1:'+dbPort+'/beauty_bm01?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC',
  '--spring.datasource.username=root','--spring.datasource.password=','--spring.flyway.enabled=false',
  '--shop.beauty.enabled=true','--shop.beauty.deployment=standalone','--shop.beauty.migration.mode=migrate',
  '--shop.beauty.publication.archive-only=true','--shop.services.internal-token='+secret,
  '--shop.services.targets.PLATFORM='+(failure?'http://127.0.0.1:1':platform),'--bm02.probe.entity='+state.entityNo,
  '--bm02.probe.store='+state.storeNo,'--bm02.probe.personnel='+state.personnelNo,'--bm02.probe.resource='+state.resourceNo,
  '--bm02.probe.failure='+failure,'--bm02.probe.conflict='+conflict,'--bm02.probe.output='+output];
  if(conflict)sql(`UPDATE mch_schedule_rule SET start_minute=630 WHERE resource_no='${state.resourceNo}' AND store_no='${branch}' AND status='ACTIVE'`);
  let result;
  try{result=spawnSync(process.env.JAVA_HOME+'/bin/java',args,{cwd:root,encoding:'utf8',timeout:180000,maxBuffer:20*1024*1024});}
  finally{if(conflict)sql(`UPDATE mch_schedule_rule SET start_minute=660 WHERE resource_no='${state.resourceNo}' AND store_no='${branch}' AND status='ACTIVE'`);}
  writeFileSync(join(dir,label+'.log'),(result.stdout??'')+(result.stderr??''));
  assert.equal(result.status,0,label+' failed; inspect '+dir+'/'+label+'.log');
  const evidence=JSON.parse(readFileSync(output,'utf8'));
  if(failure)assert.equal(evidence.failedClosed,3);else if(conflict)assert.equal(evidence.ownerConflict,true);
  else assert.equal(evidence.resources[0].capacity,1);
 }
}
export async function defaultRetail({request,url,token,state}){
 assert((await request(url,'/biz/resources',{token})).records.some(r=>r.resourceNo===state.resourceNo));
 assert.equal((await request(url,query(state.resourceNo),{token})).windows.length,2);
}
