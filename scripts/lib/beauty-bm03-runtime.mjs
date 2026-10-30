import assert from 'node:assert/strict';
const storeNo='ST-BM03',items=[{skuNo:'BM03-SKU',qty:1}],biz='/biz/x/beauty/appointments',mp='/mp/x/beauty/appointments';
const iso=ms=>new Date(ms).toISOString();
const tomorrow=()=>Date.UTC(new Date().getUTCFullYear(),new Date().getUTCMonth(),new Date().getUTCDate()+1);
const count=(sql,table)=>Number(sql('SELECT COUNT(*) FROM '+table));
export async function setupBusiness({request,url,token,state,privateOnly=false}){
 const now=iso(Date.now()-60000);
 await request(url,'/biz/x/beauty/store-policies/versions',{token,key:'BM03-POLICY',body:{expectedRevision:0,validFrom:now,policy:{bookingAheadDays:30,cancelRule:{freeCancelBeforeMinutes:0,allowCustomerCancel:true},allowedModes:['PREPAID_SINGLE']}}});
 await request(url,'/biz/x/beauty/service-definitions/versions',{token,key:'BM03-DEF',body:{skuNo:'BM03-SKU',expectedRevision:0,duration:{minutes:60,bufferBeforeMin:0,bufferAfterMin:0},requirements:[{roleCode:'TECH',resourceType:'STAFF',skillGoodsNo:'BM03-G',qty:1,sequenceNo:0,offsetMin:0,durationMin:60,selectionRule:'ANY_QUALIFIED'}],notes:[]}});
 if(privateOnly)return state;
 await request(url,'/biz/store-sales-catalog/versions',{token,key:'BM03-CATALOG',body:{expectedRevision:0,validFrom:now,timezone:'UTC',groups:[{key:'SERVICE',title:'服务',position:0,visible:true}],entries:[{skuNo:'BM03-SKU',groupKey:'SERVICE',position:0,visible:true,salesMode:'UNRESTRICTED',windows:[]}]}});
 const person=await request(url,'/biz/personnel',{token,key:'BM03-PERSON',body:{displayName:'BM03技师',assignment:{expectedRevision:0,storeNo,validFrom:iso(Date.now()-86400000),validTo:iso(Date.now()+60*86400000),publicVisible:true,sortOrder:0,positions:['TECHNICIAN'],skills:[{goodsNo:'BM03-G',skuNo:'BM03-SKU'}]}}});
 const resources=await request(url,'/biz/resources?type=STAFF',{token});
 const resource=resources.records.find(r=>r.resourceNo===person.resourceNo);
 await request(url,'/biz/schedule-rules',{token,key:'BM03-RULE',body:{resourceNo:person.resourceNo,expectedRevision:resource.revision,timezone:'UTC',weekdayMask:127,startMinute:0,endMinute:1440,endDayOffset:0,validFrom:iso(Date.now()-86400000).slice(0,10),validTo:iso(Date.now()+40*86400000).slice(0,10),status:'ACTIVE',reason:'BM03 module gate'}});
 const role=await request(url,'/biz/roles',{token,body:{name:'预约管理',perms:['biz:booking:view','biz:booking:manage']}});
 const managers=[];
 for(const account of ['MC-M03','MC-M04']){
  const staff=(await request(url,'/biz/staff',{token})).find(s=>s.mchAccountNo===account);assert(staff);
  await request(url,'/biz/staff/'+staff.mchAccountNo+'/store',{token,body:{storeNo,role:role.roleCode,granted:true}});
  managers.push(staff.mchAccountNo);
 }
 return {...person,managers,role:role.roleCode};
}
async function preview({request,url,token,startAt,consumer=false,state}){
 return request(url,(consumer?mp:biz)+'/previews',{token,body:{storeNo,items,startAt,...(consumer?{requestedPersonnelNo:state?.personnelNo}: {})}});
}
async function create(ctx,startAt,key,consumer=false){const p=await preview({...ctx,startAt,consumer});
 const body={previewNo:p.previewNo,sourceRefs:[]};const value=await ctx.request(ctx.url,consumer?mp:biz,{token:ctx.token,body,key});return {value,body,preview:p};}
export async function lifecycle(ctx){
 const {request,url,token,login,sql,state,label}=ctx;
 const start=tomorrow()+10*3600000;
 const availability={storeNo,items,from:iso(start),to:iso(start+15*60000)};
 const a=await request(url,'/biz/x/beauty/availability/queries',{token,body:{...availability,requestedPersonnelNo:state.personnelNo}});
 assert(a.candidates.length>0);
 const pub=await request(url,'/mp/x/beauty/availability/queries',{body:availability});assert(pub.candidates.length>0);
 const consumer=await login(url,'19700000003',true),stranger=await login(url,'19700000006',true);
 const c=await create({...ctx,token:consumer},iso(start+6*3600000),label+'-C',true);
 assert.equal(c.value.status,'CONFIRMED');assert.equal(c.value.operationState,'SUCCEEDED');
 const holds=(ctx.ownerSql??sql)("SELECT h.hold_no,h.status FROM mch_booking_hold h JOIN mch_booking_owner o ON o.owner_no=h.owner_no WHERE o.source_no='"+c.value.appointmentNo+"'");
 assert(holds.endsWith('\tCONFIRMED'),'actual owner hold confirms real reservation');
 const eventsBefore=count(sql,'sys_outbox');
 assert.deepEqual(await request(url,mp,{token:consumer,body:c.body,key:label+'-C'}),c.value);
 assert.equal(count(sql,'sys_outbox'),eventsBefore,'stable replay must not emit duplicate source events');
 const mine=await request(url,mp,{token:consumer});assert(mine.records.some(r=>r.appointmentNo===c.value.appointmentNo));
 await request(url,mp+'/'+c.value.appointmentNo,{token:consumer});
 await request(url,mp+'/'+c.value.appointmentNo,{token:stranger,errorCode:10403});
 await request(url,mp+'/'+c.value.appointmentNo+'/cancellations',{token:consumer,key:label+'-C-cancel',body:{expectedRevision:c.value.revision,reason:'customer cancellation'}});
 const b=await create(ctx,iso(start),label+'-B');assert.equal(b.value.status,'CONFIRMED');
 const path=biz+'/'+b.value.appointmentNo;
 const list=await request(url,biz,{token});assert(list.records.some(r=>r.appointmentNo===b.value.appointmentNo));
 const before=await request(url,path,{token});assert.equal(before.appointment.status,'CONFIRMED');
 const overlap=await request(url,path+'/changes/previews',{token,body:{expectedRevision:b.value.revision,items,startAt:iso(start+15*60000),assignments:[]}});
 const changed=await request(url,path+'/changes',{token,key:label+'-overlap',body:{previewNo:overlap.previewNo,expectedRevision:b.value.revision,reason:'overlapping replacement'}});
 assert.equal(changed.operationState,'SUCCEEDED');
 const preserved=await request(url,path,{token});assert.equal(Date.parse(preserved.appointment.startAt),start+15*60000);
 const conflictPreview=await request(url,path+'/changes/previews',{token,body:{expectedRevision:changed.revision,items,startAt:iso(start+2*3600000),assignments:[]}});
 const blocker=await create(ctx,iso(start+2*3600000),label+'-blocker');assert.equal(blocker.value.status,'CONFIRMED');
 const rejected=await request(url,path+'/changes',{token,key:label+'-conflict',body:{previewNo:conflictPreview.previewNo,expectedRevision:changed.revision,reason:'capacity conflict'}});
 assert.equal(rejected.operationState,'REJECTED');
 const retained=await request(url,path,{token});assert.equal(retained.appointment.startAt,preserved.appointment.startAt);
 assert.deepEqual(retained.assignments,preserved.assignments);
 await request(url,path+'/cancellations',{token,key:label+'-cancel',body:{expectedRevision:retained.appointment.revision,reason:'module cancellation'}});
 await request(url,biz+'/'+blocker.value.appointmentNo+'/cancellations',{token,key:label+'-blocker-cancel',body:{expectedRevision:blocker.value.revision,reason:'module cancellation'}});
 const terminal=await request(url,path,{token});assert.equal(terminal.appointment.status,'CANCELLED');
 const noShowStart=Math.ceil((Date.now()+45000)/300000)*300000;
 let noShow;
 if(label==='standalone')noShow=(await create(ctx,iso(noShowStart),label+'-no-show')).value;
 const facts={confirmedOwnerHold:holds,consumer:c.value,merchant:b.value,changed,rejected,retained:retained.appointment,terminal:terminal.appointment,noShow,noShowStart};
 if(noShow){state.noShow=noShow;state.noShowStart=noShowStart;}
 console.log(label+': availability, B/C confirmed, replay, list/detail, overlap, conflict preserve-old, cancel passed.');
 return facts;
}
export async function recoveryCases(ctx){
 const {request,url,token,login,sql,ownerSql,jobSql,state,platformProxy,restartServer,startWorker,stop,pause}=ctx;
 const m1=await login(url,'19700000004'),m2=await login(url,'19700000005');
 const start=tomorrow()+20*3600000;
 platformProxy.dropOnce(e=>e.path==='/internal/resource-booking/accept');
 const pending=await create({...ctx,token:m1},iso(start),'lost-accept');
 assert.equal(pending.value.operationState,'PENDING');
 const dropped=platformProxy.events.find(e=>e.dropped&&e.path.endsWith('/accept'));
 assert(dropped?.response?.binding?.commandNo,'real owner acceptance persisted before response loss');
 assert.equal(sql("SELECT COUNT(*) FROM bty_appointment_operation WHERE receipt IS NULL AND state='PENDING'"),'1');
 await restartServer();
 await request(url,'/biz/staff/'+state.managers[0]+'/store',{token,body:{storeNo,role:state.role,granted:false}});
 await request(url,biz,{token:m1,body:pending.body,key:'lost-accept',errorCode:10403});
 const recovered=await request(url,'/biz/x/beauty/operations/'+pending.value.operationNo+'/recoveries',{token:m2,method:'POST'});
 assert.equal(recovered.state,'SUCCEEDED');
 const same=platformProxy.events.filter(e=>e.path.endsWith('/accept')&&e.body.request.key===dropped.body.request.key);
 assert(same.length>=2);assert(same.every(e=>e.response.binding.commandNo===dropped.response.binding.commandNo));
 const recoveredRoot=await request(url,biz+'/'+pending.value.appointmentNo,{token});
 assert.equal(recoveredRoot.appointment.status,'CONFIRMED');
 await request(url,biz+'/'+pending.value.appointmentNo+'/cancellations',{token,key:'recovered-cancel',body:{expectedRevision:recoveredRoot.appointment.revision,reason:'cleanup'}});
 // Execute CONFIRM on the actual owner, discard its response, then hide only follow-up queries.
 let confirmNo;
 platformProxy.dropOnce(e=>{if(e.path==='/internal/resource-booking/execute'&&e.response?.binding?.action==='CONFIRM'){
  confirmNo=e.response.binding.commandNo;platformProxy.block(q=>q.path==='/internal/resource-booking/query'&&q.body.commandNo===confirmNo);return true;}return false;});
 const scheduled=await create(ctx,iso(start+2*3600000),'lost-confirm');
 assert.equal(scheduled.value.operationState,'PENDING');assert(confirmNo);
 assert.equal(ownerSql("SELECT status FROM mch_resource_command WHERE command_no='"+confirmNo+"'"),'SUCCEEDED');
 platformProxy.block(null);await restartServer();
 let worker=await startWorker('job-worker');
 for(let i=0;i<150;i++){if(sql("SELECT state FROM bty_appointment_operation WHERE operation_no='"+scheduled.value.operationNo+"'")==='SUCCEEDED')break;await pause(1000);}
 assert.equal(sql("SELECT state FROM bty_appointment_operation WHERE operation_no='"+scheduled.value.operationNo+"'"),'SUCCEEDED');
 assert.equal(jobSql("SELECT COUNT(*) FROM job_definition WHERE handler_name='beauty-appointment-recovery' AND target='BEAUTY'"),'1');
 assert.equal(jobSql('SELECT COUNT(*) FROM job_definition'),'1','worker targets only beauty service declaration');
 const runs=Number(jobSql("SELECT run_count FROM job_run WHERE job_name='beauty-appointment-recovery'"));assert(runs>0);
 await stop(worker);worker=await startWorker('job-worker-restarted');
 for(let i=0;i<90;i++){if(Number(jobSql("SELECT run_count FROM job_run WHERE job_name='beauty-appointment-recovery'"))>runs)break;await pause(1000);}
 assert(Number(jobSql("SELECT run_count FROM job_run WHERE job_name='beauty-appointment-recovery'"))>runs);
 await stop(worker);
 const jobEvidence=jobSql('SELECT job_name,last_status,run_count FROM job_run');
 const jobRecords=jobSql('SELECT run_id,job_name,trigger_type,status,http_status,worker_instance FROM job_log ORDER BY id');
 assert(jobRecords.includes('CRON\tSUCCESS\t200\tbm03-module-gate'));
 assert(Number(sql("SELECT COUNT(*) FROM sys_outbox WHERE event_type='BEAUTY_APPOINTMENT_OPERATION_COMPLETED' AND status='ARCHIVED'"))>0);
 while(Date.now()<=state.noShowStart)await pause(Math.min(1000,state.noShowStart-Date.now()+20));
 const noShow=await request(url,biz+'/'+state.noShow.appointmentNo+'/no-shows',{token,key:'actual-no-show',body:{expectedRevision:state.noShow.revision,reason:'customer did not arrive'}});
 assert.equal((await request(url,biz+'/'+state.noShow.appointmentNo,{token})).appointment.status,'NO_SHOW');
 platformProxy.block(e=>e.path==='/internal/resource-booking/execute');
 const outagePending=await create(ctx,iso(start+3*3600000),'source-outage');
 assert.equal(outagePending.value.operationState,'PENDING');
 const outageRequest=platformProxy.events.filter(e=>e.blocked&&e.path.endsWith('/execute')).at(-1);
 platformProxy.block(null);assert(outageRequest);
 console.log('Lost acceptance/restart/revocation/another manager and committed CONFIRM/scheduler/restart/no-show passed.');
 return {pending:pending.value,recovered,scheduled:scheduled.value,confirmNo,jobEvidence,jobRecords,noShow,
  outagePending:outagePending.value,outageRequest:{path:outageRequest.path,rawBody:outageRequest.rawBody}};
}
