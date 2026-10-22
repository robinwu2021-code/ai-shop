const root='ai.neargo.shop.food';
const short=s=>s.replaceAll(root+'.dto.','').replaceAll(root+'.entity.','').replaceAll('ai.neargo.shop.common.','').replaceAll('ai.neargo.shop.print.dto.','').replaceAll('ai.neargo.shop.print.entity.','').replaceAll('java.util.','').replaceAll('java.time.','').replaceAll('java.math.','');
const param=(type,name,source='SERVICE_DERIVED')=>({type,name,source});
const must=(ok,why)=>{if(!ok)throw new Error(why);};
const camel=s=>s.replace(/_([a-z])/g,(_,c)=>c.toUpperCase());
const typeFor=f=>/bigint/.test(f.type)?'Long':/decimal/.test(f.type)?'java.math.BigDecimal':/^(int|tinyint)/.test(f.type)?'Integer':f.type==='boolean'?'Boolean':f.type==='date'?'java.time.LocalDate':f.type.startsWith('datetime')?'java.time.Instant':f.type==='json'?'java.util.Map<String,Object>':'String';
const signature=(result,name,args)=>result+' '+name+'('+args.map(p=>p.type+' '+p.name).join(', ')+')';
function customDefinition(t,p,name){
 const e=p.entityClass,entity=param(e,'row','SERVER_BUILT_ENTITY'),entityNo=param('String','entityNo','TRUSTED_SCOPE'),store=param('String','storeNo','TRUSTED_SCOPE');
 const scope=[entityNo,...(t.fields.some(f=>f.name==='store_no')?[store]:[])];
 const key=t.unique[0].filter(n=>!['entity_no','store_no','tenant_no'].includes(n)).map(n=>param(typeFor(t.fields.find(f=>f.name===n)),camel(n),'PATH_OR_VERIFIED_OBJECT'));
 const version=param('long','expectedVersion','REQ_VERSION_OR_LOCKED_ENTITY_VERSION'),qty=param('java.math.BigDecimal','qty','VALIDATED_QTY_OR_FROZEN_LINE');
 let args=[...scope,...key],result='int',access='WRITE',guard='更新影响行数必须为1；0行区分无权/不存在、版本冲突、状态或容量不足；不得静默成功。';
 const add=(...xs)=>{for(const x of xs)if(!args.some(p=>p.name===x.name))args.push(x);};
 if(name==='history'){
  args=[entityNo,param('String','cartNo'),param('String','sourceLineRef')];result=e;access='LOCK';
  guard='原逻辑归档行也读取；显式主体/车/源行与FOR UPDATE，源行身份不可重绑商品或SKU。';
 }
 else if(name==='retain'){
  args=[entityNo,param('String','cartNo'),param('String','sourceLineRef'),qty,param('String','seatNo'),
   param('String','courseNo'),param('String','selection'),param('int','deleted'),
   param('String','actorNo','AUTHENTICATED_ACTOR'),param('java.time.LocalDateTime','at','SERVER_CLOCK')];
  guard='保留原源行/addedBy，按已锁owner/cart CAS更新或复活墓碑；影响行数必须为1。';
 }
 else if(name.startsWith('insert')||name.startsWith('append')){args=[entity];guard='业务唯一键冲突只允许识别同键同参原结果，不吞其他SQL错误；不可变事实仅追加。';}
 else if(name==='selectScopedByKeyForUpdate'){result=e;access='LOCK';guard='必须在当前餐饮本地事务内调用，锁到的是已存在父/实体行；空结果不能充当稳定仲裁者。';}
 else if(name.startsWith('select')||name.startsWith('lock')){
  access=name.includes('ForUpdate')||name.startsWith('lock')?'LOCK':'READ';result=e;
  if(name==='selectForUpdateByStoreAndTableNo')args=[entityNo,store,param('String','tableNo')];
  if(name==='selectActiveByTableForUpdate'){args=[entityNo,param('String','tableNo')];result='java.util.List<'+e+'>';}
  if(name==='selectActiveByBatchForUpdate')args=[entityNo,param('String','batchNo')];
  if(name==='selectSelectedLinesForUpdate'){args=[entityNo,param('String','cartNo'),param('java.util.List<String>','sourceLineRefs')];result='java.util.List<'+e+'>';}
  if(name==='selectAllocationsByFoodLine'){args=[entityNo,param('String','foodLineNo')];result='java.util.List<'+e+'>';}
  if(name==='selectActiveReturnQuantityByFoodLine'){args=[entityNo,param('String','foodLineNo')];result='java.math.BigDecimal';}
  if(name==='selectEffectiveByConfigAndTime')args=[entityNo,param('String','configKind'),param('String','configNo'),param('java.time.Instant','at')];
  if(name==='selectActiveDrawerForUpdate')args=[entityNo,store,param('String','drawerNo'),param('String','currency')];
  if(name==='selectLeaseByStoreEpochForUpdate')args=[entityNo,store,param('String','edgeNo'),param('long','epoch')];
  if(name==='selectAcceptedThrough'){args=[entityNo,store,param('String','edgeNo'),param('long','epoch')];result='Long';}
  if(name==='selectValidByTokenHash')args=[param('byte[]','tokenHash','HASH_OF_BEARER_TOKEN'),param('java.time.Instant','now','SERVER_CLOCK')];
  if(name==='lockCartIfVersion')add(version);
  guard='显式主体与适用门店/父对象范围；被归档数据只在获准恢复用例读取；LOCK方法要求外层本地事务。';
  if(name==='selectValidByTokenHash')guard='仅允许桌码会话启动：按唯一哈希精确查未吊销且未过期令牌，限制速率和失败重试；不返回明文令牌。查验成功后从记录解析主体/门店再访问其他表，不是通用无主体查询入口。';
 }
 else if(name==='upsertProjectionIfSourceVersion'){args=[param(e,'row')];guard='只由shop-report使用，按投影唯一键和水位幂等更新，不改营业日签核事实。';}
 else if(name==='queryByMetricVersionAndWatermark'){args=[entityNo,store,param('java.time.LocalDate','from'),param('java.time.LocalDate','to'),param('int','metricVersion'),param('String','watermark')];result='java.util.List<'+e+'>';access='READ';}
 else{
  if(name==='updateCapacityIfVersion')add(param('int','capacity'));
  if(name==='updatePartySizeIfVersion')add(param('int','partySize'));
  if(['reserveQuotaIfCapacity','consumeQuotaIfReserved','releaseQuotaIfReserved','withdrawIfRemaining'].includes(name))add(qty);
  if(name==='advanceTaskQuantityIfVersion')add(param('String','action','SERVER_ACTION_WHITELIST'),qty);
  if(name==='changePendingStateIfVersion')add(param('String','targetState','SERVER_ACTION_WHITELIST'));
  if(name==='advanceClaimIfStatus'||name==='advancePlacementIfStatus')add(param('String','expectedStatus'),param('String','targetStatus'));
  if(name==='advanceOperationIfVersion')add(param('String','targetStatus'));
  if(name==='confirmIntentFromCoreIfStatus')add(param('String','expectedStatus'),param('String','coreStatus','VERIFIED_CORE_RESULT'),param('String','coreReference','VERIFIED_CORE_RESULT'));
  if(name==='endOccupancyIfActive')add(param('java.time.Instant','endedAt','SERVER_CLOCK'));
  if(name==='detachMembershipIfActive')add(param('java.time.Instant','detachedAt','SERVER_CLOCK'));
  if(name==='closeIfSettlementConfirmed')add(param('java.time.Instant','closedAt','SERVER_CLOCK'));
  if(name==='handoverIfVersion')add(param('String','countNo'),param('String','acceptedBy','AUTHENTICATED_ACTOR'));
  if(name==='replaceCurrentCloseIfVersion')add(param('String','currency'),param('String','closeNo'));
  if(name==='signIfVersionHashAndWatermark')add(param('String','snapshotHash'),param('String','watermark'),param('String','signedBy','AUTHENTICATED_ACTOR'),param('java.time.Instant','signedAt','SERVER_CLOCK'));
  if(name==='renewLeaseIfEpoch')add(param('long','epoch'),param('java.time.Instant','expiresAt','SERVER_CLOCK_AND_LEASE_POLICY'));
  add(version);
 }
 return {name,returnType:result,parameters:args,signature:signature(result,name,args),access,inherited:false,guard};
}
export function buildFoodMethodInventory(s,code,plan,codePlan){
 const tables=new Map(s.tables.map(t=>[t.name,t])),persist=new Map(code.persistence.map(p=>[p.table,p]));
 must(Object.keys(plan.apiFlows).length===code.endpoints.length,'Missing API flow or stale flow count');
 const mapperByTable=new Map();
 for(const p of code.persistence){
  const t=tables.get(p.table),e=p.entityClass,w='com.baomidou.mybatisplus.core.conditions.Wrapper<'+e+'>',page='com.baomidou.mybatisplus.core.metadata.IPage<'+e+'>';
  const base=p.owner==='shop-report'?[]:[
   ['selectOne',e,[param(w,'queryWrapper','SERVER_BUILT_SCOPED_WRAPPER')],'READ'],
   ['selectList','java.util.List<'+e+'>',[param(w,'queryWrapper','SERVER_BUILT_SCOPED_WRAPPER')],'READ'],
   ['selectPage',page,[param(page,'page','VALIDATED_PAGINATION'),param(w,'queryWrapper','SERVER_BUILT_SCOPED_WRAPPER')],'READ'],
   ['selectCount','Long',[param(w,'queryWrapper','SERVER_BUILT_SCOPED_WRAPPER')],'READ'],
   ['insert','int',[param(e,'row','SERVER_BUILT_ENTITY')],'WRITE'],
   ['update','int',[param(e,'changes','SERVER_BUILT_ENTITY'),param(w,'updateWrapper','SERVER_BUILT_SCOPE_VERSION_STATUS_WRAPPER')],'WRITE']
  ].map(([name,returnType,parameters,access])=>({name,returnType,parameters,signature:signature(returnType,name,parameters),access,inherited:true,guard:'继承BaseMapper不重复声明；Wrapper只能服务端构建，必须包含主体、门店或已验证父对象、版本/状态条件；禁止直接传HTTP Entity。'}));
  const customs=new Set(codePlan.tableOwners[p.table].customMapperMethods||[]);
  if(Object.values(plan.apiFlows).some(f=>f.customMethods.includes(p.table+'.selectScopedByKeyForUpdate')))customs.add('selectScopedByKeyForUpdate');
  mapperByTable.set(p.table,{table:p.table,className:p.mapperClass,sourceFile:p.mapperFile,entityClass:e,moduleId:p.primaryModuleId,scopeColumns:['entity_no',...(t.fields.some(f=>f.name==='store_no')?['store_no']:[])],methods:[...base,...[...customs].map(name=>customDefinition(t,p,name))]});
 }
 const endpoints=code.endpoints.map(e=>{
  const f=plan.apiFlows[e.apiId];must(f,'Missing API flow '+e.apiId);
  for(const t of [...f.readTables,...f.writeTables])must(tables.has(t),'Unknown/non-food table '+t+' in '+e.apiId);
  for(const port of f.portConcepts)must(codePlan.portCatalog[port],'Unknown port concept '+port);
  const custom=f.customMethods.map(ref=>{
   const at=ref.indexOf('.'),table=ref.slice(0,at),method=ref.slice(at+1),m=mapperByTable.get(table);must(m,'Unknown mapper table '+table);
   const def=m.methods.find(x=>x.name===method);must(def,'Unknown mapper method '+ref);
   must(f.readTables.includes(table)||f.writeTables.includes(table),'Custom mapper not declared in flow '+ref);
   return {table,mapperClass:m.className,method,access:def.access,signature:def.signature,guard:def.guard};
  });
  const paged=e.outputFields[0]?.name==='records';
  const reads=f.readTables.filter(t=>!(f.replaceReadTables||[]).includes(t)).map((table,i)=>{const m=mapperByTable.get(table);const method=paged&&i===0?'selectPage':i===0&&e.pathParameters.some(p=>p.name!=='storeNo')?'selectOne':'selectList';const def=m.methods.find(x=>x.name===method);must(def,'Missing inherited query '+table);return {table,mapperClass:m.className,method,access:'READ',signature:def.signature,guard:def.guard};});
  const writes=f.writeTables.flatMap(table=>{
   const m=mapperByTable.get(table),hasCustomWrite=custom.some(c=>c.table===table&&c.access==='WRITE');
   if(hasCustomWrite&&!f.baseWriteOverrides?.[table])return [];
   const append=['fnb_action_log','fnb_service_event','fnb_order_line','fnb_order_component','fnb_order_modifier','fnb_cash_entry','fnb_cash_payment_link','fnb_config_revision','fnb_close_balance','fnb_close_adjustment'].includes(table);
   const writeMethods=f.baseWriteOverrides?.[table]||[append||!f.readTables.includes(table)?'insert':'update'];
   return writeMethods.map(method=>{const def=m.methods.find(x=>x.name===method);must(def,'Missing local write '+table);return {table,mapperClass:m.className,method,access:'WRITE',signature:def.signature,guard:def.guard,branch:writeMethods.length>1?'CONDITIONAL_BRANCH; insert/update are selected by use-case state, never both blindly':method==='insert'?'INSERT_NEW_OR_IDEMPOTENT_REUSE':'UPDATE_EXISTING'};});
  });
  const path=e.pathParameters.map(p=>({...p,type:p.name==='localDate'?'java.time.LocalDate':'String',source:'VALIDATED_PATH'}));
  const httpArgs=path.map(p=>({...p,annotation:(p.type==='java.time.LocalDate'?'@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) ':'')+'@PathVariable("'+p.name+'")'}));
  if(e.requestClass)httpArgs.push({...param(e.requestClass,e.inputBinding==='QUERY'?'query':'req','PUBLIC_DTO'),annotation:e.inputBinding==='QUERY'?'@Valid @ModelAttribute':'@Valid @RequestBody'});
  if(e.idempotencyKeyRequired)httpArgs.push({...param('String','idempotencyKey','HEADER'),annotation:'@RequestHeader("Idempotency-Key")'});
  const serviceArgs=[param('String','entityNo','TRUSTED_AUTH_OR_STORE_DIRECTORY'),...(!path.some(p=>p.name==='storeNo')?[param('String','currentStoreNo','TRUSTED_CURRENT_STORE_OR_DIAGNOSTIC_SCOPE')]:[]),...path];
  if(e.path.startsWith('/mp/'))serviceArgs.push(param('String','authenticatedSessionNo','VERIFIED_SESSION; public catalog/session-create may be null'));
  if(e.path.startsWith('/internal/'))serviceArgs.push(param('String','authenticatedDeviceNo','VERIFIED_SERVICE_DEVICE'));
  if(e.primaryModuleId==='MOD-10')serviceArgs.push(param('java.util.Set<String>','authorizedStoreNos','TRUSTED_ALLOWED_STORE_SET'));
  if(e.idempotencyKeyRequired||e.apiId==='FOOD-API-079')serviceArgs.push(param('String','actorNo','AUTHENTICATED_ACTOR'));
  if(e.requestClass)serviceArgs.push(param(e.requestClass,e.inputBinding==='QUERY'?'query':'req','VALIDATED_PUBLIC_DTO'));
  if(e.idempotencyKeyRequired)serviceArgs.push(param('String','idempotencyKey','VALIDATED_HEADER'));
  if(plan.bootstrapApis?.[e.apiId]){
   const allowed=['req','query','idempotencyKey',...(e.apiId==='FOOD-API-023'?['authenticatedSessionNo']:[])];
   serviceArgs.splice(0,serviceArgs.length,...serviceArgs.filter(p=>allowed.includes(p.name)));
  }
  if(f.serviceParameters)serviceArgs.splice(0,serviceArgs.length,...f.serviceParameters);
  if(f.controllerParameters)httpArgs.splice(0,httpArgs.length,...f.controllerParameters);
  must(new Set(serviceArgs.map(p=>p.name)).size===serviceArgs.length,'Duplicate service parameter '+e.apiId);
  const controllerSignature=e.returnType+' '+e.method+'('+httpArgs.map(p=>(p.annotation||'')+' '+p.type+' '+p.name).join(', ')+')';
  return {apiId:e.apiId,moduleId:e.primaryModuleId,httpMethod:e.httpMethod,path:e.path,controllerClass:e.controllerClass,serviceClass:e.serviceClass,implClass:e.serviceImplClass,serviceMethod:e.serviceMethod,controllerParameters:httpArgs,controllerSignature,serviceParameters:serviceArgs,serviceSignature:signature(e.returnType,e.serviceMethod,serviceArgs),returnType:e.returnType,mapperCalls:[...reads,...custom,...writes],portCalls:f.portConcepts,transaction:f.transaction,note:f.note,
   transactionRule:f.transaction==='ORCHESTRATED'?'外部校验/调用在本地事务外；短事务保存意图及outbox、绑定结果；UNKNOWN查原键恢复，不把HTTP放进长数据库事务。':f.transaction==='LOCAL_ATOMIC'?'本域短事务；先锁已存在仲裁行，CAS及数量条件更新与事实/outbox同事务。':f.transaction==='READ_ONLY'||f.transaction==='NO_LOCAL_WRITE'?'只读本域；GET不补写业务数据；报价等外部调用遵守原幂等键。':'共享任务持有导出/投影写入，本域只调用合同和保存明确的运行引用。',implementationStatus:f.implementationStatus||'TARGET_NOT_IMPLEMENTED'};
 });
 const controllers=[...new Set(endpoints.map(e=>e.controllerClass))].map(className=>({className,methods:endpoints.filter(e=>e.controllerClass===className).map(e=>({apiId:e.apiId,signature:e.controllerSignature,delegate:e.serviceClass+'.'+e.serviceMethod}))}));
 const services=[...new Set(endpoints.map(e=>e.serviceClass))].map(className=>{
  const rows=endpoints.filter(e=>e.serviceClass===className),methods=[];
  for(const e of rows){const sig=e.serviceSignature;const prior=methods.find(m=>m.signature===sig);if(prior)prior.apiIds.push(e.apiId);else methods.push({name:e.serviceMethod,apiIds:[e.apiId],signature:sig,parameters:e.serviceParameters,returnType:e.returnType});}
  return {className,implClass:className.replace('.service.','.service.impl.')+'Impl',methods};
 });
 const internalServiceMethods=plan.internalServiceMethods.map(m=>{
  const owner=services.find(s=>s.className.endsWith('.'+m.class));must(owner,'Unknown internal service '+m.class);
  return {...m,className:owner.className,implClass:owner.implClass};
 });
 const auxiliaryClasses=code.modules.flatMap(module=>module.auxiliary.map(a=>{
  let signatures=plan.auxiliaryMethods[a.name]||[];
  if(a.package==='job')signatures=['String name()','ai.neargo.job.api.JobResult run(ai.neargo.job.api.JobInvocation invocation)'];
  const declarative=a.package==='config';
  must(signatures.length||declarative,'Missing auxiliary methods '+a.className);
  return {className:a.className,moduleId:module.moduleId,purpose:a.purpose,signatures,declarative,note:declarative?'配置类以条件/Import/Properties声明为主，不为了清单增加空业务方法；迁移Bean复用主DataSource且只能有一个执行者。':a.package==='job'?'严格实现当前JobHandler.name/run；业务失败返回JobResult.failed，不能用5xx代替。':'纯规则/组装不直接改核心表；事件处理先验证事实及幂等。'};
 }));
 return {status:'TARGET_NOT_IMPLEMENTED',date:plan.date,endpoints,controllers,services,mappers:[...mapperByTable.values()],internalServiceMethods,auxiliaryClasses,entityAccessorRule:'Fnb实体沿Lombok/BaseEntity访问器；不把身份、计价、支付或状态机放进Entity。',dtoAccessorRule:'record按字段生成fieldName()及标准equals/hashCode/toString；结构见dtoTypes，不另造同义业务方法。'};
}
export function renderFoodMethodInventory(v,code){
 let md=`# 餐饮系统Controller、Service与Mapper方法清单\n\n> 状态：目标与已实现后端方法对照 · ${v.date}。按${v.endpoints.length}个目标与实际API逐项登记${v.controllers.length}个Controller、${v.services.length}个Service及对应Impl、${v.mappers.length}个持久化接口/DAO；已实现范围以逐项状态及CAT-ALL验收记录为准，未实现目标不等于运行能力。\n> 由 \`node scripts/gen-food-code-inventory.mjs\` 统一生成；输入为[方法用例计划](./schema/food-method-plan.json)、[类清单](./schema/food-module-code-inventory.json)。[API参数](./餐饮系统-API设计.md) · [模块代码清单](./餐饮系统-按业务模块代码清单.md) · [方法机器清单](./schema/food-method-inventory.json)。\n\n## 一、签名及调用边界\n\nJava片段为方法声明，省略import、HTTP Mapping及权限注解（HTTP方法/路径在标题，完整类型在机器清单）；Controller类应按实际方法加对应Mapping与既有权限规则。\n\nController仅有路径、Body/Query和协议头；Service签名另加可信主体/当前门店/操作者或会话范围，不带HTTP注解。显式storeNo必须与已有BizContext获准目标匹配；非门店路径由对象归属或获准工单解析范围。entityNo不是客户端Body字段，顾客会话也不能自报替代认证。平台不得伪装商家身份，多店报表额外携带获准门店集合。公开目录与桌码建会话是启动例外：目录由StoreDirectory解析请求店，桌码由唯一哈希查验后得到主体门店，不要求调用方预先伪造BizContext。匿名主体未获权威合同支持时禁止下交易单。\n\n下列Service接口与ServiceImpl具有相同签名；Impl负责事务、对象归属、状态/金额/数量守卫、转换及Port编排。外层沿ApiResponseWrapper，不在Controller返回类型再包一层ApiResult。\n\nMapper的insert/select/update是继承BaseMapper，不重写空方法；Wrapper只能由Service构建并追加主体、门店或受控父引用，UPDATE必须带版本/状态。物理id仅内部使用，不能将外部业务号传selectById。查询分页返回IPage并由MybatisPages转换PageData。报告DAO使用既有JdbcClient技术栈。\n\n调用图列出一个用例的候选分支及恢复步骤，不表示一次调用必须全执行。create/upsert分支须按API语义新增或受控更新；已发布快照、成交源行与审计事实只追加。方法名不等于数据库已具备并发安全；LOCK必须命中稳定仲裁行，空查询不能锁出钱箱/租约唯一写者。\n\n## 二、按模块的Controller与Service方法\n`;
 md+='\n| 模块 | 业务 | 本模块API方法数 |\n|---|---|---|\n';
 for(const module of code.modules)md+=`| ${module.moduleId} | ${module.name} | ${v.endpoints.filter(e=>e.moduleId===module.moduleId).length} |\n`;
 for(const module of code.modules){
  md+=`\n### ${module.moduleId} ${module.name}\n`;
  for(const e of v.endpoints.filter(e=>e.moduleId===module.moduleId)){
   md+=`\n#### ${e.apiId} · ${e.httpMethod} ${e.path}\n\nController：\`${e.controllerClass}\`\n\n\`\`\`java\n${short(e.controllerSignature)};\n\`\`\`\n\nService / Impl：\`${e.serviceClass}\` / \`${e.implClass}\`\n\n\`\`\`java\n${short(e.serviceSignature)};\n\`\`\`\n\n持久化：${e.mapperCalls.map(c=>'`'+c.mapperClass.split('.').slice(-2).join('.')+'.'+c.method+'`（'+c.access+'）').join('；')||'无餐饮本地表写入/查询'}。\n\n跨域：${e.portCalls.map(p=>'`'+p+'`').join('、')||'无新增跨域调用'}。\n\n事务：**${e.transaction}**。${e.transactionRule}${e.note?' '+e.note:''}\n`;
  }
 }
 md+='\n## 三、逐Mapper / DAO的方法签名\n\n共享prn_/rpt_为待建设能力的目标方法，不能在food-core创建其副本。下列原子写方法返回int时必须处理0行冲突；不能把核心金额/库存/资金守卫伪装成餐饮表单条SQL。\n';
 for(const m of v.mappers){
  md+=`\n### ${m.table}\n\n\`${m.className}\`；文件：\`${m.sourceFile}\`。\n\n\`\`\`java\n${m.methods.map(f=>(f.inherited?'// inherited BaseMapper\n':'')+short(f.signature)+';').join('\n')}\n\`\`\`\n\n${m.methods.filter(f=>!f.inherited).map(f=>'- `'+f.name+'`：'+f.guard).join('\n')}\n`;
 }
 md+='\n## 四、Service内部门面与恢复方法\n\n这些方法由同域Service/事件/任务调用，不额外开放HTTP接口；ServiceImpl实现同一签名。内部参数来自已验证对象，不能让Controller直接接收Entity或任意snapshot。\n';
 for(const item of v.internalServiceMethods)md+=`\n- \`${item.className}\` / \`${item.implClass}\`：\n\n\`\`\`java\n${item.signature};\n\`\`\`\n\n${item.purpose}。\n`;
 md+='\n## 五、辅助类的方法\n';
 for(const item of v.auxiliaryClasses)md+=`\n### ${item.className}\n\n${item.purpose}。${item.note}\n\n${item.signatures.length?'\`\`\`java\n'+item.signatures.map(s=>s+';').join('\n')+'\n\`\`\`\n':'无新增业务方法；仅声明装配/属性职责。\n'}`;
 md+='\nEntity/DTO方法：实体沿Lombok及BaseEntity的getXxx/setXxx；record沿字段名访问器，不在数据对象上增加收退款/计价/授权方法。字段与完整类名见模块机器清单dtoTypes/persistence。\n';
 md+=`\n## 六、实现核对\n\n1. 按模块选择API用例后，再实现相应Controller、Service/Impl与Mapper；先桌台，再菜品、订单。\n2. 校验同名Service方法参数/返回不冲突、同一Mapper方法定义唯一、跨域只走Port；每个Service方法的请求字段和返回结构以API册为准。\n3. 核心能力缺口继续门控：源行、后付库存、应收拆合、部分退款、离线租约和真实设备均需合同测试。\n4. Entity沿Lombok/BaseEntity访问器，DTO record沿字段访问器；不额外生成一套领域实体/Repository。辅助Policy/Job及内部门面方法已在方法机器清单逐项列出，不改变现有HTTP目标。\n5. 已应用的V1冻结；方法设计不更新数据库。文档守卫通过不等于Java编译、SQL事务或业务测试通过。\n`;
 return md;
}
