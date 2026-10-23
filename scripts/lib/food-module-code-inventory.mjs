const parse=value=>(value?value.split(';'):[]).map(part=>{const [name,type,...label]=part.split(':');return {name:name.replace(/\?$/,''),type,required:!name.endsWith('?'),label:label.join(':')};});
const shape=fields=>JSON.stringify(fields.map(({name,type,required})=>({name,type,required})));
const upper=s=>s[0].toUpperCase()+s.slice(1);
const domainRoot='ai.neargo.shop.food';
const project='backend-plugins/shop-industry-food/';
const must=(ok,why)=>{if(!ok)throw new Error(why);};
const sourceFile=(owner,cls)=>{
 if(cls.startsWith('ai.neargo.shop.printing.'))return 'backend/shop-core/src/main/java/'+cls.replaceAll('.','/')+'.java';
 const contract=cls.match(/^ai\.neargo\.shop\.food\.dto\.([A-Za-z0-9]+Contracts)\./);
 if(contract)return project+'food-api/src/main/java/ai/neargo/shop/food/dto/'+contract[1]+'.java';
 if(/^ai\.neargo\.shop\.food\.api\.(biz|mp|ops)\./.test(cls))return project+'food-web/src/main/java/'+cls.replaceAll('.','/')+'.java';
 if(cls.startsWith(domainRoot+'.dto.')||(cls.startsWith(domainRoot+'.service.')&&!cls.startsWith(domainRoot+'.service.impl.')))return project+'food-api/src/main/java/'+cls.replaceAll('.','/')+'.java';
 if(owner==='shared-print')return 'backend/shop-core/src/main/java/'+cls.replaceAll('.','/')+'.java';
 if(owner==='shop-report')return 'backend/shop-report/src/main/java/'+cls.replaceAll('.','/')+'.java';
 return project+'food-core/src/main/java/'+cls.replaceAll('.','/')+'.java';
};
export function buildFoodModuleInventory(s,old,plan,baselines){
 const moduleIds=new Set(s.businessModules.map(m=>m.id)),apis=new Map(s.apis.map(a=>[a.id,a]));
 must(Object.keys(plan.apiOwners).length===s.apis.length,'API owner count mismatch');
 must(Object.keys(plan.tableOwners).length===s.tables.length,'Table owner count mismatch');
 for(const a of s.apis)must(moduleIds.has(plan.apiOwners[a.id]),'Invalid or missing API owner '+a.id);
 for(const t of s.tables)must(moduleIds.has(plan.tableOwners[t.name]?.moduleId),'Invalid or missing table owner '+t.name);
 const logicalType=n=>plan.dtoTypeOverrides?.[n]|| (n==='Money'||['MeasurementPolicy','SelectionRules'].includes(n)?domainRoot+'.dto.'+n:n.endsWith('Input')?domainRoot+'.dto.'+n.slice(0,-5)+'Req':n==='Selection'?domainRoot+'.dto.SelectionReq':domainRoot+'.dto.'+n+'VO');
 const records=new Map(), responseShapes=new Map();
 const register=(cls,fields,kind)=>{
  const prior=records.get(cls);must(!prior||shape(prior.fields)===shape(fields),'Conflicting DTO class '+cls);
  if(!prior)records.set(cls,{className:cls,kind,fields,sourceFile:sourceFile(cls.startsWith('ai.neargo.shop.printing.')?'shared-print':'food-core',cls)});
  return cls;
 };
 for(const [name,input] of Object.entries(s.dtos))if(name!=='Page')register(logicalType(name),parse(input),'SHARED_CONTRACT_RECORD');
 const type=t=>{if(t.endsWith('[]'))return 'java.util.List<'+type(t.slice(0,-2))+'>';return ({string:'String',int32:'Integer',int64:'Long',decimal:'java.math.BigDecimal',boolean:'Boolean',datetime:'java.time.Instant',date:'java.time.LocalDate',object:'java.util.Map<String, Object>'}[t]||logicalType(t));};
 const methods=old.methods.map(m=>{
  const a=apis.get(m.apiId);must(a,'Unknown API '+m.apiId);const override=plan.methodOverrides[m.apiId]||{};
  const method=(override.method||m.method).replaceAll('Batche','Batch');
  const service=override.service||m.service||m.serviceClass.split('.').at(-1);
  const root=domainRoot;
  const serviceRoot=a.domain==='H'?'ai.neargo.shop.printing':domainRoot;
  const input=parse(a.input),output=parse(a.output);
  let req=plan.requestAliases[a.id]||upper(method)+(a.method==='GET'?'Query':'Req');
  const mp=a.path.startsWith('/mp/');
  const counterpart=old.methods.find(b=>!b.path.startsWith('/mp/')&&b.method.replaceAll('Batche','Batch')===method&&shape(parse(apis.get(b.apiId)?.input))===shape(input));
  if(mp&&!counterpart)req='Customer'+req;
  let requestClass=null;
  if(input.length&&!override.scalarQuery){
   let cls=override.requestClass||root+'.dto.'+req;
   if(records.has(cls)&&shape(records.get(cls).fields)!==shape(input)){
    req=m.controller.replace(/^(Biz|Mp|Ops|Internal)/,'').replace(/Controller$/,'')+req;cls=root+'.dto.'+req;
   }
   requestClass=register(cls,input,override.requestKind||(a.method==='GET'?'QUERY_RECORD':'REQUEST_RECORD'));
  }
  const page=output.length===4&&output.map(f=>f.name).join(',')==='records,total,page,size'&&output[0].type.endsWith('[]');
  let returnType;
  if(override.returnType){
   returnType=override.returnType;
   if(returnType.startsWith('ai.')&&!returnType.includes('<'))register(returnType,output,'RESPONSE_RECORD');
  }else if(page)returnType='ai.neargo.shop.common.PageData<'+type(output[0].type.slice(0,-2))+'>';
  else{
   const exact=Object.entries(s.dtos).find(([name,fields])=>name!=='Page'&&shape(parse(fields))===shape(output));
   if(exact)returnType=logicalType(exact[0]);
   else if(responseShapes.has(root+shape(output)))returnType=responseShapes.get(root+shape(output));
   else{
    const concept=m.controller.replace(/^(Biz|Mp|Ops|Internal)/,'').replace(/Controller$/,'');
    const wrapperNames={Operation:'OperationResultVO',ConfigRevision:'ConfigPublicationVO',Check:'CheckResultVO',Table:'TableResultVO',TableArea:'TableAreaResultVO',BatchDetail:'BatchDetailResultVO',Cart:'CheckCartResultVO',Quote:'DiningQuoteResultVO',Batch:'OrderBatchResultVO',Dish:'DishResultVO',Menu:'MenuResultVO',Settlement:'DiningCheckoutResultVO',Config:'ConfigResultVO',Close:'BusinessCloseResultVO'};
    const wrapper=output.length===1?wrapperNames[output[0].type]:null;
    let cls=root+'.dto.'+(wrapper||concept+'ResultVO');
    if(records.has(cls)&&shape(records.get(cls).fields)!==shape(output)){
      const stem=method.toLowerCase().endsWith(concept.toLowerCase())?upper(method):upper(method)+concept;
      cls=root+'.dto.'+(mp?'Customer':'')+stem+'ResultVO';
    }
    returnType=register(cls,output,'RESPONSE_RECORD');responseShapes.set(root+shape(output),returnType);
   }
  }
  return {...m,primaryModuleId:plan.apiOwners[a.id],method,service,serviceClass:serviceRoot+'.service.'+service,serviceImplClass:serviceRoot+'.service.impl.'+service+'Impl',serviceMethod:override.serviceMethod||(mp?'customer'+upper(method):method),
   request:requestClass?.split('.').at(-1)||null,requestClass,response:returnType,returnType,inputFields:input,outputFields:output,
   pathParameters:[...a.path.matchAll(/\{([^}]+)\}/g)].map(v=>({name:v[1],type:'String'})),
   inputBinding:a.method==='GET'?'QUERY':'BODY',idempotencyKeyRequired:a.method!=='GET',
   controllerFile:sourceFile(a.domain==='H'?'shared-print':'food-core',m.controllerClass),serviceFile:sourceFile(a.domain==='H'?'shared-print':'food-core',serviceRoot+'.service.'+service),serviceImplFile:sourceFile(a.domain==='H'?'shared-print':'food-core',serviceRoot+'.service.impl.'+service+'Impl'),
   targetPublicRouteStatus:a.domain==='H'?'PLANNED_SHARED_B_ENTRY_NOT_REGISTERED':null,
   targetDtoStatus:a.domain==='H'?'PLANNED_TRANSPORT_CONTRACT':null,
   dispatchServiceMethods:override.dispatchServiceMethods||[],contractStatus:a.implementation||'TARGET_NOT_IMPLEMENTED',
   actualImplementations:(s.nonPaymentImplementation?.implementedApis||[]).filter(actual=>actual.targetApiId===a.id).map(actual=>({method:actual.method,path:actual.path,controller:actual.controller,responseType:actual.responseType,sourceFile:actual.sourceFile,controllerClass:domainRoot+'.api.biz.'+actual.controller,delegate:actual.controller==='BizPrintJobController'?(actual.handler==='enqueue'?'ai.neargo.shop.food.service.FoodPrintService.enqueue -> ai.neargo.shop.spi.printing.PrintPort.enqueue':'ai.neargo.shop.spi.printing.PrintPort.'+actual.handler):null,mappingStatus:actual.mappingStatus,status:actual.status}))};
 });
 const endpoints=new Map(methods.map(e=>[e.apiId,e]));
 must(endpoints.size===s.apis.length,'API inventory missing or duplicate');
 const persistence=old.persistence.map(p=>{
  const owner=plan.tableOwners[p.table];
  const writeClass=p.owner==='shop-report'?p.mapperClass:(p.owner==='shared-print'?p.entityClass.split('.entity.')[0]:domainRoot)+'.service.'+owner.writeService;
  return {...p,primaryModuleId:owner.moduleId,writeOwnerClass:writeClass,writeOwnerKind:p.owner==='shop-report'?'REPORT_DAO':'SERVICE',
   entityFile:sourceFile(p.owner,p.entityClass),mapperFile:sourceFile(p.owner,p.mapperClass.split('.').slice(0,-1).join('.')+(p.owner==='shop-report'?'.'+p.mapperClass.split('.').at(-1):'')),
   customMapperMethods:owner.customMapperMethods||[],schemaStatus:s.tables.find(t=>t.name===p.table)?.deployment==='PLANNED'?'PLANNED_NOT_CREATED':s.tables.find(t=>t.name===p.table)?.deployment?.startsWith('MIGRATION_')?'MIGRATION_AVAILABLE_NOT_APPLIED':p.table.startsWith('fnb_')?'CREATED_LOCAL':s.tables.find(t=>t.name===p.table)?.sharedMigration?'SHARED_MIGRATION_AVAILABLE_NOT_APPLIED':'SHARED_NOT_CREATED',implementationStatus:p.table==='fnb_queue_ticket'||s.tables.find(t=>t.name===p.table)?.sharedMigration?'CODED_PENDING_VERIFICATION':'TARGET_NOT_IMPLEMENTED'};
 });
 // Nested mapper interfaces share the collection source file, not independent Java files.
 for(const p of persistence)if(p.owner!=='shop-report')p.mapperFile=sourceFile(p.owner,p.mapperClass.split('.').slice(0,-1).join('.'));
 const classOwners={};
 for(const p of persistence){
  classOwners[p.entityClass]=p.primaryModuleId;classOwners[p.mapperClass]=p.primaryModuleId;
  if(p.writeOwnerKind==='SERVICE')classOwners[p.writeOwnerClass]=p.primaryModuleId;
 }
 for(const e of methods)if(!classOwners[e.serviceClass])classOwners[e.serviceClass]=e.primaryModuleId;
 for(const c of new Set(methods.map(e=>e.controllerClass))){
  const rows=methods.filter(e=>e.controllerClass===c),votes=new Map();
  rows.forEach(e=>votes.set(e.primaryModuleId,(votes.get(e.primaryModuleId)||0)+1));
  classOwners[c]=c.endsWith('.BizDiningActionLogController')?'MOD-13':[...votes].sort((a,b)=>b[1]-a[1])[0][0];
 }
 for(const e of methods)classOwners[e.serviceImplClass]=classOwners[e.serviceClass];
 const modules=s.businessModules.map(m=>{
  const owned=methods.filter(e=>e.primaryModuleId===m.id),ownedTables=persistence.filter(p=>p.primaryModuleId===m.id),details=plan.moduleDetails[m.id];
  const serviceClasses=[...new Set([...owned.map(e=>e.serviceClass),...ownedTables.filter(p=>p.writeOwnerKind==='SERVICE').map(p=>p.writeOwnerClass)])];
  return {moduleId:m.id,name:m.name,phase:details.phase,requirements:m.requirements,ownedApiIds:owned.map(e=>e.apiId),ownedTables:ownedTables.map(p=>p.table),
   referencedApiIds:m.apis.filter(id=>!owned.some(e=>e.apiId===id)),referencedTables:m.tables.filter(n=>!ownedTables.some(p=>p.table===n)),
   controllerClasses:[...new Set(owned.map(e=>e.controllerClass))],serviceClasses,serviceImplClasses:serviceClasses.map(n=>n.replace('.service.','.service.impl.')+'Impl'),
   ownedControllerClasses:[...new Set(methods.map(e=>e.controllerClass))].filter(c=>classOwners[c]===m.id),
   referencedControllerClasses:[...new Set(owned.map(e=>e.controllerClass))].filter(c=>classOwners[c]!==m.id),
   ownedServiceClasses:serviceClasses.filter(c=>classOwners[c]===m.id),referencedServiceClasses:serviceClasses.filter(c=>classOwners[c]!==m.id),
   dtoClasses:[...new Set(owned.flatMap(e=>[e.requestClass,e.returnType]).filter(Boolean))],
   auxiliary:details.auxiliary.map(a=>({...a,className:domainRoot+'.'+a.package+'.'+a.name,sourceFile:sourceFile('food-core',domainRoot+'.'+a.package+'.'+a.name)})),
   ports:(details.portConcepts||[]).map(name=>({concept:name,...plan.portCatalog[name]})),
   tests:details.tests.map(n=>({className:domainRoot+'.'+(n.includes('Architecture')?'architecture':n.includes('Contract')?'contract':'scenario')+'.'+n,sourceFile:project+'food-core/src/test/java/'+domainRoot.replaceAll('.','/')+'/'+(n.includes('Architecture')?'architecture':n.includes('Contract')?'contract':'scenario')+'/'+n+'.java'})),
   gaps:s.requirementAlignment.filter(r=>r.moduleId===m.id&&r.status!=='DEFINED').map(r=>({requirementId:r.requirementId,status:r.status,note:r.note}))};
 });
 const compare=b=>{
  const previous=new Map(b.methods.map(m=>[m.apiId,m]));
  const keys=['controllerClass','method','serviceClass','request','response'];
  return methods.map(m=>{const prior=previous.get(m.apiId);return {apiId:m.apiId,moduleId:m.primaryModuleId,status:prior?keys.every(k=>(prior[k]??null)===(m[k]??null))?'UNCHANGED':'REVISED':'ADDED',changes:prior?keys.filter(k=>(prior[k]??null)!==(m[k]??null)).map(k=>({field:k,before:typeof prior[k]==='string'?prior[k].replace(/ai\.neargo\.shop\.print\./g,'ai.neargo.shop.printing.'):prior[k]??null,after:m[k]??null})):[]};}).concat(b.methods.filter(m=>!endpoints.has(m.apiId)).map(m=>({apiId:m.apiId,status:'REMOVED',changes:[]})));
 };
 const tablesBefore=new Map(baselines.committedBaseline.persistence.map(p=>[p.table,p]));
 const tableDiffs=persistence.map(p=>({table:p.table,moduleId:p.primaryModuleId,status:tablesBefore.has(p.table)?'UNCHANGED':'ADDED',classNamesUnchanged:!tablesBefore.has(p.table)||['entityClass','mapperClass'].every(k=>tablesBefore.get(p.table)[k]===p[k])}));
 const used=(t)=>{const base=t.replace(/\[\]/g,'');return Object.hasOwn(s.dtos,base)&&base!=='Page'?logicalType(base):null;};
 const usedRecords=new Set();
 const visit=cls=>{if(!cls||usedRecords.has(cls)||!records.has(cls))return;usedRecords.add(cls);records.get(cls).fields.forEach(f=>visit(used(f.type)));};
 methods.forEach(e=>{visit(e.requestClass);visit(e.returnType);if(e.returnType.startsWith('ai.neargo.shop.common.PageData<'))e.outputFields.forEach(f=>visit(used(f.type)));});
 const dtoTypes=[...records.values()].filter(r=>usedRecords.has(r.className)).map(r=>({...r,owner:r.className.startsWith('ai.neargo.shop.printing.')?'shared-print':'food-api',implementationStatus:r.kind==='REQUEST_RECORD'||r.kind==='QUERY_RECORD'||r.kind==='RESPONSE_RECORD'?'PLANNED_TRANSPORT_CONTRACT':'TARGET_CONTRACT',fields:r.fields.map(f=>({...f,javaType:type(f.type)}))}));
 return {status:'TARGET_NOT_IMPLEMENTED',date:plan.date,deploymentArchitecture:plan.deploymentArchitecture,modules,endpoints:methods,persistence,dtoTypes,classOwners,
  diffs:{committed:compare(baselines.committedBaseline),previous:compare(baselines.preModuleBaseline)},tableDiffs,
  rules:['餐饮三端Controller归food-web，由shop-app统一装配；独立food-server仅/internal；接口DTO归food-api、业务实现归food-core','五个Maven模块含新增food-web；13个业务模块仅为交付清单，不按业务域再拆工程','ServiceImpl直接本域Mapper；共享打印/报表实体不复制进food','Req/Query由同字段合同复用，差异合同分型；Service可直接使用DTO，不强制重复Command','scope/actor从既有BizContext或顾客会话/设备/工单鉴权解析，不由请求自报','数据库同商品订单共用ai_shop，独立fnb历史；冻结V1不改']};
}

export function renderFoodModuleInventory(v){
 const short=x=>x.replaceAll('ai.neargo.shop.food.','').replaceAll('ai.neargo.shop.printing.','printing.').replaceAll('ai.neargo.shop.common.','common.');
 const count=status=>v.diffs.committed.filter(r=>r.status===status).length;
 let md=`# 餐饮系统按业务模块代码清单\n\n> 状态：目标与已实现后端对照 · ${v.date}。依据${v.endpoints.length}个目标与实际后端API、历史V1的73张本机已建fnb表及6张共享候选表；V4至V7新增表只标记迁移可用，不表示已应用到该经营库。当前已有五个Java模块和部分后端管理实现，未实现目标仍单列。\n> 由 \`node scripts/gen-food-code-inventory.mjs\` 生成。真源：[代码归属计划](./schema/food-code-plan.json)、[设计源](./schema/food-system-design.json)及[原清单快照](./schema/food-code-baselines.json)。\n> [平台Java规范](../../../.claude/skills/ai-shop-dev-standards/references/java.md) · [原结构清单（已同步）](./餐饮系统-代码结构与对象清单.md) · [API输入输出](./餐饮系统-API设计.md) · [数据库字段](./餐饮系统-数据库设计.md) · [已建表清单](./餐饮系统-数据库表清单.md)。\n\n> 方法实施入口：[Controller-Service-Mapper方法清单](./餐饮系统-Controller-Service-Mapper方法清单.md)，逐API给出签名、Mapper/Port调用、事务及内部恢复方法。\n\n## 一、先看模块与主责\n\n每个API、每张表仅有一个主责模块；关联模块只引用，不复制Controller/Entity。模块01→02→03为开发主线，模块13提供当前切片必需的配置/权限/审计与装配支撑。顾客、员工、设备、平台复用业务服务；表已创建不等于类已实现。\n\n| 模块 | 业务 | 主责API | 主责表 | 使用Controller数 | 使用Service |\n|---|---|---|---|---|---|\n`;
 for(const m of v.modules)md+=`| ${m.moduleId} | ${m.name} | ${m.ownedApiIds.length} | ${m.ownedTables.length} | ${m.controllerClasses.length} | ${m.serviceClasses.map(short).join('、')} |\n`;
 md+=`\n## 二、统一文件位置和类型规则\n\n- 业务主包：\`backend-plugins/shop-industry-food/food-core/src/main/java/ai/neargo/shop/food/\`。三端Controller保留api/biz、mp、ops包名，文件属于餐饮项目food-web；shop-app只引入装配，不持有餐饮Controller源码；独立food-server不对前端开放。Service接口与dto文件位于food-api且包名不变；service/impl、内部入口、entity及持久化位于food-core；嵌套Mapper接口位于唯一mapper/FoodMappers.java。各模块不再维护另一套包名。\n- Controller完整类名在模块表中给出；Service实现把\`.service.XxxService\`替换为\`.service.impl.XxxServiceImpl\`。Entity和Mapper列出完整类名；机器清单含所有精确文件路径、API参数字段与返回结构。\n- 简单入参可直接给Service使用，不机械新增Req→Command→DomainDTO。路径参数单独绑定；GET的Query与POST/PUT的Body分开。Idempotency-Key等头沿API全局合同。\n- 先按认证解析主体/门店/actor与顾客会话/设备/工单范围，再调用Service；这些身份不是请求DTO字段。顾客入口使用customer前缀Service方法区分会话作用域，不与员工方法仅靠返回类型重载。最终Service参数含获准范围与对应DTO，实施不得让Service依赖Controller嵌套类。\n- 输出与API的data字段完全对应：\`{table:Table}\`使用TableResultVO包装TableVO；\`{check:Check}\`使用CheckResultVO。分页直接PageData<T>；取消结账等操作响应不能误用支付动作VO。同名不同字段Req必须分型，同字段可复用。\n- datetime契约以Instant表示UTC时刻、date以LocalDate表示日期，持久化仍沿BaseEntity/LocalDateTime与现有数据源时区；实施必须验证现有Jackson与时区转换，不修改全项目时间语义。金额沿int64最小单位和币种、数量BigDecimal；不新建商品/资金真源。\n- 历史V1的73张表为CREATED_LOCAL；后续迁移新增表为MIGRATION_AVAILABLE_NOT_APPLIED，待实现表为PLANNED_NOT_CREATED；共享prn_为shop-core/printing唯一owner，V556标SHARED_MIGRATION_AVAILABLE_NOT_APPLIED；rpt_仍按原共享owner与其迁移证据分别验收。禁止在food-core复制打印持久化；report DAO沿JdbcClient。\n\n## 三、逐业务模块代码清单\n`;
 for(const m of v.modules){
  const es=v.endpoints.filter(e=>e.primaryModuleId===m.moduleId),ps=v.persistence.filter(p=>p.primaryModuleId===m.moduleId);
  md+=`\n### ${m.moduleId} ${m.name}\n\n**需求：** ${m.requirements.join('、')}。**交付标记：** ${m.phase}。\n\n**主责Controller（完整类名）：**\n\n${m.ownedControllerClasses.map(c=>'- `'+c+'`').join('\n')||'复用其他入口，无新增主责Controller'}\n\n**主责Service / Impl：**\n\n${m.ownedServiceClasses.map(c=>'- `'+c+'` → `'+c.replace('.service.','.service.impl.')+'Impl`').join('\n')}\n\n**复用类：** ${[...m.referencedControllerClasses,...m.referencedServiceClasses].map(c=>'`'+c+'`').join('、')||'无额外复用类'}。这些类只在主责模块建立一份。\n\n**API→Controller方法→Service→参数/响应：** 方法字段与必填性见API册；下列为目标合同类或Java类型，不代表已存在的源码。原/biz/print/jobs共享B目标尚未注册；实际FOOD打印入口在food-web，enqueue经FoodPrintService冻结文档后调用PrintPort，其余查询/重试/补打直接调用PrintPort，共享持久化与执行owner为shop-core/printing。\n\n| API | Controller方法 | Service方法 | Req / Query | data返回类型 |\n|---|---|---|---|---|\n`;
  for(const e of es)md+=`| ${e.apiId} | ${e.controller}.${e.method} | ${e.service}.${e.serviceMethod} | ${e.requestClass?short(e.requestClass):'无Body/Query；路径参数独立绑定'} | ${short(e.returnType)} |\n`;
  md+='\n**Entity / Mapper与主责写入：**\n\n| 表及状态 | Entity / Row | Mapper / DAO | 主责写入类 |\n|---|---|---|---|\n';
  for(const p of ps)md+=`| ${p.table} · ${p.schemaStatus} | ${p.entityClass} | ${p.mapperClass} | ${p.writeOwnerClass} |\n`;
  md+=`\n**定制Mapper方法（目标，需真实SQL与并发测试）：** ${ps.filter(p=>p.customMapperMethods.length).map(p=>'`'+p.mapperClass+'`：'+p.customMapperMethods.map(n=>'`'+n+'`').join('、')).join('；')||'普通配置沿BaseMapper，不为每个CRUD新增转发方法'}。\n\n**跨域SPI与适配：** ${m.ports.map(p=>'`'+p.interfaceClass+'`（'+p.status+'）').join('、')}。完整本地/远程实现名见机器清单ports；EXISTS也须核对签名，TARGET不表示接口已存在。\n\n**必要辅助对象：**\n\n${m.auxiliary.map(a=>'- `'+a.className+'`：'+a.purpose+'。').join('\n')}\n\n**测试清单（待实施）：** ${m.tests.map(t=>'`'+t.className+'`').join('、')}。\n\n**协作引用：** ${m.referencedApiIds.join('、')||'无额外跨模块API引用'}；表 ${m.referencedTables.map(n=>'`'+n+'`').join('、')||'无额外跨模块表引用'}。跨模块读取不改变表主责。\n`;
  if(m.gaps.length)md+=`\n**未决合同/缺口：** ${m.gaps.map(g=>g.requirementId+'（'+g.status+'）').join('、')}；具体残余工作见API册第八节，清单不将其计为已完成。\n`;
 }
 md+=`\n## 四、DTO字段字典与精确文件清单\n\n机器清单[food-module-code-inventory.json](./schema/food-module-code-inventory.json)的dtoTypes逐类列出完整包名、文件、字段、协议类型、Java类型和必填性；endpoints逐项列出路径参数、Body/Query、幂等头要求及Service/Controller文件。DTO仅承载协议，金额权威/版本/选择规则由Service及核心重新核验。\n\nDTO复用按实际字段结构比较，不按类名相似或端点编号复用；Money等稳定值对象可共享，顾客/员工有不同字段时分型。空输入不创建空Req。\n\n## 五、与之前代码清单的对齐\n\n历史快照字节保留；以下旧打印包名仅在显示中规范为printing，并不改写历史实现证据。对比两份冻结快照：提交8d093b207的91API/73表初版，以及业务模块编排前141API/79表的修订版。接口按稳定API编号比较，不因方法改名新增接口；当前API随真实路由补录，表数量与冻结迁移保持独立对账。\n\n与初版相比：新增${count('ADDED')}个API映射，保留或修订${v.diffs.committed.length-count('ADDED')}个；新增${v.tableDiffs.filter(t=>t.status==='ADDED').length}张候选表映射，旧表Entity/Mapper类名保持。原Repository和八模块方案继续作废，不恢复。\n\n| API | 当前主责 | 相对初版 | 相对模块编排前 | 本轮修订字段 |\n|---|---|---|---|---|\n`;
 for(const row of v.diffs.previous){const orig=v.diffs.committed.find(r=>r.apiId===row.apiId);md+=`| ${row.apiId} | ${row.moduleId} | ${orig.status} | ${row.status} | ${row.changes.map(c=>c.field+': '+(c.before||'∅')+' → '+(c.after||'∅')).join('；')||'主责与文件路径补齐'} |\n`;}
 md+=`\n本轮服务主责修订：共享配置发布历史/回滚归ConfigRevisionService；跨模块审计归DiningAuditService；同桌新开台归CheckService，不能由TableService返回TableVO冒充Check。getBatche/cancelBatche/createSyncBatche等统一Batch；Request全类名冲突、Query分页返回和操作结果逐项校正。\n\n## 六、进入开发前的检查\n\n先实现MOD-01基础切片：桌区/桌台配置与查询、桌码、开台人数、容量占用、清台和审计；转并拼桌、预约资源、订金等在合同就绪后增量交付。表结构已建，本轮仅对齐类清单，禁止改冻结V1；后续schema变更必须增量迁移。\n\n单元和架构测试必须覆盖新增独立reactor；本清单生成/静态守卫通过不代表Java编译、事务SQL、资金支付或业务验收通过。\n`;
 return md;
}
