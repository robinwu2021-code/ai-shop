#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const arg=process.argv.indexOf('--source');
const source=arg>=0?process.argv[arg+1]:'docs/technical/design/schema/commerce-integration-design.json';
const d=JSON.parse(fs.readFileSync(path.resolve(root,source),'utf8'));
const check=process.argv.includes('--check');
const files=new Map();
const assert=(value,message)=>{if(!value)throw new Error(message);};
const unique=(values,label)=>assert(new Set(values).size===values.length,`Duplicate ${label}`);
unique(d.tables.map(t=>t.name),'table');unique(d.apis.map(a=>a.id),'API id');unique(d.apis.map(a=>a.verb+' '+a.path),'route');
const tables=new Set(d.tables.map(t=>t.name));const acs=new Set(d.acceptance.map(a=>a[0]));
for(const t of d.tables){assert(t.fields.length>0&&t.keys,`Incomplete table ${t.name}`);unique(t.fields.map(f=>f.name),t.name+' field');for(const ref of t.relations.split(',').filter(Boolean))assert(tables.has('cim_'+ref),`Unknown relation ${ref}`);}
for(const a of d.apis){assert(a.status==='PLANNED'&&acs.has(a.ac)&&a.controller&&a.request&&a.response,`Incomplete API ${a.id}`);for(const t of a.tables)assert(tables.has(t),`Unknown table ${t}`);assert(!a.path.includes('{id}'),`Physical id route ${a.id}`);}
assert(d.status==='DRAFT_NOT_IMPLEMENTED','Design must not claim implementation');
for(const p of d.ports)assert(p.package==='ai.neargo.shop.spi.commerce'&&acs.has(p.ac),`Invalid port ${p.name}`);
if(d.implementation){
 assert(d.implementation.tables.every(t=>tables.has(t)), 'Unknown implemented table');
 assert(d.implementation.apiIds.every(id=>d.apis.some(a=>a.id===id)), 'Unknown implemented API');
 unique(d.implementation.apiIds, 'implemented API');
}
if(process.argv.includes('--validate')){console.log(`Validated ${d.tables.length} tables, ${d.apis.length} APIs, ${d.ports.length} ports`);process.exit(0);}
const header=(title)=>`# ${title}\n\n> 状态：全量候选设计；已编码子集见下方实施进度，候选数不代表完成数。由 \`node scripts/gen-commerce-integration-design.mjs\` 生成，勿手改。真源：[commerce-integration-design.json](./schema/commerce-integration-design.json)，${d.date}。\n\n`;
const table=(heads,rows)=>`| ${heads.join(' | ')} |\n| ${heads.map(()=> '---').join(' | ')} |\n${rows.map(r=>'| '+r.map(x=>String(x??'').replaceAll('|','\\|').replaceAll('\n','<br>')).join(' | ')+' |').join('\n')}\n\n`;
const base='docs/technical/design/';
const progress=()=>d.implementation ? '## 当前实施进度\n\n'+table(['项','实际范围'],[
 ['状态',d.implementation.status],['已编码迁移表',d.implementation.tables.join(', ')],
 ['已编码API子集',d.implementation.apiIds.join(', ')],['限制',d.implementation.limitations],
 ['验证',d.implementation.tests]]) : '';

// Actual source inventory is deliberately separate from the unchanged planned contract rows.
const coded=d.implementation?.codedInventory;
const sourceLink=source=>`[${source}](../../../${source})`;
const codedRoutes=()=>!coded?'':'## 当前源码实际路由（未作为验收通过证据）\n\n'
 + '下表逐项取自本轮源码；候选编号、候选数量和源码路由集合分别保留。参数是实际 Java 声明，方法/路径相同的读写仍分别列出。权限码不代表账号自动获授权；账户/门店范围仍由原 scope owner 核验。\n\n'
 + table(['方法 / 实际路径','Controller / 方法','实际参数声明 / 返回','原权限 / 范围依据','源码状态'],coded.routes.map(a=>[`${a.verb} \`${a.path}\``,`${a.controller}#${a.method}`,`${a.signature} → ${a.response}`,`BizPerms.${a.permission}；${a.scopeCalls.join('；')}`,a.status]))
 + '### 原运营/回调实际源码路由\n\n'
 + table(['方法 / 实际路径','方法声明 / 返回','原权限或验签要求'],(coded.operationalRoutes??[]).map(c=>[`${c.verb} ${c.path}`,`${c.signature} → ${c.response}`,c.permission]))
 + '### 原设备 MP 草稿路由（非 B 权限码，不授予离线订单能力）\n\n'
 + table(['实际方法 / 路径','实际声明 / 返回','原身份与启用条件'],(coded.consumerRoutes??[]).map(c=>[`${c.verb} ${c.path}`,`${c.signature} → ${c.response}`,`${c.permission}；${c.activation}`]))
 + '### 实际输入及返回 record 声明\n\n'
 + table(['原契约 / record','原字段声明','源码'],(coded.requestRecords??[]).map(c=>[`${c.container}.${c.name}`,c.declaration,sourceLink(c.source)]))
 + '### 私有传输及原接口签名\n\n'
 + table(['原接口','实际声明 / 路由','源码'],coded.interfaces.map(c=>[c.name,c.declarations.join('；'),sourceLink(c.source)]))
 + table(['内部提供方','实际路由声明','原鉴权依据'],coded.privateControllers.map(c=>[sourceLink(c.source),c.routes.join('；'),c.securityEvidence.join('；')]))
 + table(['原范围证据','职责'],(coded.scopeEvidenceSources??[]).map(x=>[sourceLink(x.source),x.role]))
 + coded.scopeAndAudit.map(x=>x+'\n\n').join('');
const codedDatabase=()=>!coded?'':'## 本轮实际增量迁移源码（执行待验证）\n\n'
 + 'CORE/FOOD/CIM 均沿各自原迁移历史与表前缀；源码存在不等于数据库已执行。FOOD V31 原 owner 源码已交接，编译、安全注册和集中测试待完成，不计作已验收闭环。下列字段/键按实际迁移源码记录，和后文全量候选表字段分开。\n\n'
 + coded.migrations.map(m=>`### ${m.owner} V${m.version} · ${m.status}\n\n${sourceLink(m.source)}\n\n`
   + table(['实际表','字段 / 类型','实际键'],m.tables.map(t=>[t.name,t.fields.map(f=>`${f.name} ${f.definition}`).join('；'),t.keys.join('；')]))
   + (m.alterations.length?'实际 ALTER：'+m.alterations.join('；')+'\n\n':'')).join('');
const codedClasses=()=>!coded?'':'## 本轮实际类与装配条件（非测试结果）\n\n'
 + table(['实际完整类名','类型','源码'],coded.classes.map(c=>[c.name,c.kind,sourceLink(c.source)]))
 + table(['装配源码','原条件','启用口径'],coded.featureConditions.map(c=>[sourceLink(c.source),c.condition,c.default]))
 + '原现金、独立资金/退款、已发料退回和 PMS 财务 owner 边界见 API 实际范围；外部 ACK 不关闭本地资金义务。离线 V31 源码已交接，编译和集中测试待完成。\n\n';

let db=header('统一销售渠道接入 数据库设计');
db+=progress();
db+=codedDatabase();
db+='## L1 定位\n\n渠道只拥有授权、同步、事件、接入关联和证据。商品、订单、库存、支付、仓库包裹与权益余额归原owner；餐饮/零售分别提供来源Port。当前同业务库、cim_前缀及独立迁移历史，未来可迁到独立库。\n\n';
db+=`## L2 表族与关系\n\n候选共${d.tables.length}张表，按业务闭环逐批实施，不能把候选表数当建表进度。\n\n![表族关系](./diagrams/commerce-integration-table-groups.svg)\n\n图为真源表族和逻辑引用的投影，不是跨业务物理外键。最关键的两条关系是稳定连接贯穿订单去重，以及每次外部动作关联原快照和原业务结果。\n\n`;
db+=table(['表','阶段','职责','Entity'],d.tables.map(t=>[t.name,t.phase,t.purpose,t.entity]));
db+='## L3 公共字段与逐表字段\n\n'+table(['公共字段','类型','规则'],d.common.fields)+d.common.types+'\n\n';
db+='租户/主体/门店身份沿原体系；主体级表不伪造store_no。所有内部资源查询都验证可信entity/store/账号数据范围。模块内逻辑引用按关联双方scope验证，跨域不建物理外键；不让外部调用者传主体冒充授权。\n\n';
for(const t of d.tables){db+=`### ${t.name} · ${t.purpose}\n\n阶段：${t.phase}。Entity：\`ai.neargo.shop.commerce.entity.${t.entity}\`。除下表外包含公共字段。\n\n`+table(['字段','类型与说明'],t.fields.map(f=>[f.name,f.definition]))+`键：${t.keys}。\n\n逻辑关联：${t.relations||'本域原请求事实'}。\n\n约束：${t.rules}\n\n`;}
db+='## L4 事务与迁移边界\n\n'+d.common.lease+'\n\n原有fnb_menu_channel_binding继续归餐饮，新增connection引用通过增量迁移实施；旧fnb_external_mapping若已有运行映射，须先盘点和迁移证明，禁止双写；fnb_sync_batch是边缘离线恢复，不挪作渠道任务。\n\n迁移候选资源路径commerce-core/src/main/resources/db/commerce，历史表cim_flyway_history；首版V1实际编号及初始化流程在编码前核号，全量设计不是可执行迁移；实际V1/V2/V3/V4增量迁移及业务表数见实施进度，尚未在生产执行。禁止修改原已发布Flyway。业务表默认不物理分区；仅事件/尝试长期量大后按实际保留与索引评估，避免预先引入分区与唯一键冲突。\n\n快递包裹、退货包裹、成本估值和券消费若原owner缺合同，先补原域，不在此复制。statement/line仅结算对账事实，不是报表聚合表或资金账。生产report库、日报和经营指标最后实施。\n\n[总览](../TDD-统一销售渠道接入.md) · [API](./统一销售渠道接入-API清单.md) · [代码](./统一销售渠道接入-代码清单.md)\n';
files.set(base+'统一销售渠道接入-数据库设计.md',db);
let api=header('统一销售渠道接入 API清单');
api+=progress();
api+=codedRoutes();
api+='## L1 定位\n\n一套B端API服务零售、餐饮，C端点餐/购物继续沿原业务API，不另造渠道C端下单入口。OPS只做基础配置和授权支持，回调不使用商家登录认证。下列为完整目标路由，实际已编码子集见实施进度；不是运行中OpenAPI发现结果。\n\n';
api+=`## L2 分类与公共合同\n\n公开/回调API共${d.apis.length}项；内部业务Port及typed网关合同共${d.ports.length}组，内部方法不算公开API。\n\n`;
api+=table(['分类','数量'],[...new Set(d.apis.map(a=>a.role))].map(role=>[role,d.apis.filter(a=>a.role===role).length]));
api+=`B端返回沿原ApiResult包装，列表为PageData<T>，Controller直接返回VO；内部typed与Webhook/授权响应须按原wrapper机制排除，不套业务JSON。${d.common.pagination}\n\n`;
api+='B端scope由BizContext、当前门店头及原StoreDirectoryPort校验；账户管理为主体级管理员能力，不把当前门店角色当账号全部权限。OPS显式entityNo/storeNo仅作目标，仍按运营数据域交叉核验。普通GET无外部网络副作用；refresh/recover均POST且返回原动作。\n\n所有写动作需要Idempotency-Key，同键异hash或不兼容操作者冲突。对象修改/状态动作需要version；创建不传version。Req缺失/非法字段BAD_REQUEST，跨域资源按平台统一策略拒绝，旧版本CONFLICT，未获能力明确拒绝；不补假成功。所有枚举错误码需登记原ErrorCode与三语文案，本文不预造数值码。\n\n候选权限组仍作为设计分类；已编码路由沿实际 @PreAuthorize/BizPerms（STORE_ADMIN、AFTERSALE、VERIFY 等）及原作用域校验，上方源码清单逐项登记。不新增第二套 RBAC，不因候选组名给员工授权。\n\n';
api+='### 候选平台与外部协议\n\n'+table(['平台','市场','业务产品','接口合同状态'],d.platforms);
api+='外部平台接口不包含在本系统候选公开/回调路由计数中。淘宝/抖店、餐饮POS、商超Partner API和生活服务分别按获准协议注册Gateway，不能因同品牌共用未经核验的字段或能力。具体外部HTTP路径及报文字段在适配器合同测试中冻结，未获取的协议不编造端点。\n\n';
api+='## L3 对外路由与输入输出\n\n';
for(const role of ['B','OPS','CALLBACK']){api+=`### ${role}\n\n`+table(['编号 / 方法路径','输入 / Req','输出','Service方法 / 表 / AC'],d.apis.filter(a=>a.role===role).map(a=>[`${a.id} ${a.verb} \`${a.path}\``,`${a.request}：${a.params}`,a.response,`${a.service}.${a.method}；${a.tables.join(', ')}；${a.ac}；${a.phase}`]));}
api+='### 原有API复用\n\n餐饮餐牌绑定继续复用/biz/x/food/stores/{storeNo}/menu-channel-bindings及单绑定读写，新增连接校验不新增第二份餐牌定义。原订单、支付、库存、配送、权益接口不复制为commerce CRUD；渠道只公开接入对象与受控业务动作。\n\n### 内部合同及传输\n\n'+table(['Port / 所有者','方法及返回','约束'],d.ports.map(p=>[p.package+'.'+p.name+'；'+p.owner,p.methods,p.purpose]));
api+='内部合同在commerce-api中保持唯一spi包，宿主提供本地或typed远程实现；内部路由按现有/Internal*Controller协议在提供方登记，不额外复制同义Service。拆分前为每组方法补齐实际内部path/请求schema/服务身份/签名及wrapper排除，不能据此宣称已存在内部端点。\n\n';
api+='### 关键DTO字段\n\n'+table(['DTO','字段与校验'],[
 ['ChannelContext（仅服务端）','tenantNo/entityNo/允许的storeNos/actorNo/realm；不接受公共JSON自报'],
 ['ChannelCatalogSnapshot','schemaVersion/snapshotNo/scopeNo/connectionRevisionRef/sourceManifest/items/hash；item含salesOfferRef/sourceOwner/sourceRef/sourceRevision/分类/SKU/选项/组合/价格及税来源/图片/时段；货币单位沿MoneyMinor，库存另取事实'],
 ['PrepareChannelOrderCommand','稳定connection/remoteOrderIdentity、原快照/hash、externalLines及parentRefs、精确quantity/unit、原价格优惠税费用证据、mappingRevisionRefs、履约计划、资金证据引用、原operationNo；内部认证且原owner核验'],
 ['ImportPreparation','importRef/sourceOrderRef/coreOrderRefs、状态/版本、原数量与库存承诺证明、履约准入依据；未就绪不返回可制作'],
 ['ConfirmChannelAcceptanceCommand','原importRef/expectedVersion/platformAcceptanceEvidenceRef/原operationNo；证据必须来自已验签/可信查询并由接入owner复核'],
 ['SyncRunVO','runNo/type/trigger/status/snapshotHash/desiredSnapshot/lastConfirmedSnapshot/remoteConsistency、startedAt/finishedAt/错误与可操作项；SUBMITTED不是SUCCEEDED'],
 ['ChannelOrderVO','channelOrderNo/externalOrderRef/sourceOwner/导入及平台及履约及资金证据分状态/核心业务引用/版本；金额来源明确，不返回core物理自增id'],
 ['OperationVO','operationNo/type/status/原业务引用/版本/可刷新可恢复标志/脱敏错误/平台证据引用；UNKNOWN不等于失败'],
 ['RawChannelWebhook','原始字节、不变必要签名头、登记endpointNo；大小限制/签名/平台时间戳语义按获准协议，不先解析再重序列化验签'],
 ['ChannelAcknowledgement','平台要求的HTTP状态/headers/body，只有原事件或重复事件已可靠保存后ACK；落库失败按协议非成功响应'],
 ['StatementVO / StatementLineVO','账号级摘要需主体财务权限；门店只读已归属原行，未归属和跨店行不泄漏；缺金额/分配返回unknown而非0']]);
api+='## L4 同步及状态口径\n\n'+d.common.remoteBarrier+'\n\n'+d.common.routing+'\n\n'+d.common.statistics+'\n\n目录快照过期或sourceHash漂移须重新预览；明确快照不变但状态修复走原operation recover。批量目标首版调用多个单范围命令，避免新增一个无边界all-stores接口。全量菜单部分失败后的补发依旧按完整范围，不把失败项单独全量提交。\n\nINSTANT模式可接/拒单；MARKETPLACE按原付款及仓库履约准入，不强行接单；LOCAL_LIFE按券核销授权，不套订单接单API。资金操作/券核销/账单及真实渠道各自有独立能力门禁；经营报表未在此重建。\n\n[总览](../TDD-统一销售渠道接入.md) · [数据库](./统一销售渠道接入-数据库设计.md) · [代码](./统一销售渠道接入-代码清单.md)\n';
files.set(base+'统一销售渠道接入-API清单.md',api);
let code=header('统一销售渠道接入 代码清单');
code+=progress();
code+=codedClasses();
code+='## L1 定位\n\n以下是目标文件与方法清单，不是已落地源码扫描。遵循[平台Java规范](../../../.claude/skills/ai-shop-dev-standards/references/java.md)的包/命名/Port及BaseEntity规则；实现按闭环增量建类，不先生成全域空壳。\n\n';
code+='## L2 模块与依赖\n\n'+table(['模块','包/依赖','职责'],[
 ['commerce-api','ai.neargo.shop.commerce.service/dto；ai.neargo.shop.spi.commerce；仅原轻量基础合同','Service、Req/VO、唯一跨域Port及事件'],
 ['commerce-core','ai.neargo.shop.commerce.service.impl/entity/mapper/policy/statemachine/event/job/config；api+原store基础','本域持久化和编排；不依赖channel/web或FOOD/零售业务实现'],
 ['commerce-web','ai.neargo.shop.commerce.api.biz/ops/callback/internal；api+原auth；不依赖core','公开Controller及本地/远程Service选择，原Profile和权限'],
 ['commerce-channel','ai.neargo.shop.commerce.channel.base/client/adapter/port；api+svc-client','平台网关和原业务的typed远程Port实现；不依赖core/web'],
 ['commerce-server','ai.neargo.shop.commerce.server；core+channel+web；公共BFF在独立形态关闭','独立启动，Internal*Controller和服务身份鉴权；不重复公开B/OPS入口']]);
code+='集成形态：宿主web+core+channel；独立形态：宿主web和远程Service客户端，独立server运行core+channel+web的内部合同，公共BFF继续关闭。与food现有装配分工一致。服务端callback在宿主保留原URL，通过内部typed传输原始字节；两层分别核验服务身份及平台签名，不能让内部转发绕过验签。两形态只激活一个写者及一个Port实现。\n\n';
code+='## L3 Controller Service Mapper对账\n\n';
const controllers=[...new Set(d.apis.map(a=>a.controller))];
code+=table(['模块及完整类名','Service方法','API编号'],controllers.map(c=>{const aa=d.apis.filter(a=>a.controller===c);const role=aa[0].role;const pkg=role==='B'?'biz':role==='OPS'?'ops':'callback';return ['commerce-web / ai.neargo.shop.commerce.api.'+pkg+'.'+c,[...new Set(aa.map(a=>a.service+'.'+a.method))].join(', '),aa.map(a=>a.id).join(', ')];}));
const services=[...new Set(d.apis.map(a=>a.service))];
for(const s of services){const aa=d.apis.filter(a=>a.service===s);code+=`### ${s}\n\n接口：\`commerce-api/src/main/java/ai/neargo/shop/commerce/service/${s}.java\`。实现：\`commerce-core/src/main/java/ai/neargo/shop/commerce/service/impl/${s}Impl.java\`。\n\n`;code+=table(['方法','Req / Query','返回','规则'],aa.map(a=>[a.method,a.request,a.response,`${a.id}；${a.ac}；${a.permissionGroup}`]));code+='业务签名约定：写方法为`Return method(ChannelContext scope, Req req, String key)`，读为`Return method(ChannelContext scope, Query query)`；路径业务号归入对应typed Req/Query。Webhook为`ChannelAcknowledgement receive(RawChannelWebhook request)`，授权回调按协议处理；二者不冒用商家scope。内部恢复使用持久化原意图及原授权，不能以当前SYSTEM用户伪造首次同意。\n\n';}
code+='### 持久化对象\n\n实体路径均为commerce-core/src/main/java/ai/neargo/shop/commerce/entity；Mapper为`ai.neargo.shop.commerce.mapper.CommerceMappers`内嵌套接口，继承BaseMapper<对应实体>，不增加Repository转发层。\n\n';
code+=table(['完整Entity类名','表','嵌套Mapper'],d.tables.map(t=>['ai.neargo.shop.commerce.entity.'+t.entity,t.name,'CommerceMappers.'+t.entity.slice(3)+'Mapper']));
code+='关键Mapper方法（均带可信scope）：Connection.selectByChannelStoreKey/lockScoped；CatalogScope.lockScope/advanceGeneration/confirmSnapshotWithGeneration；RequestJournal.insertOriginal/selectByKey；SyncRun.claimDue/updateWithFence；InboundEvent.insertOriginal/claimPending/completeWithFence；InboundReceipt.insertReceipt；Operation.insertOriginal/claimDue/updateOutcomeWithFence；OperationAttempt.insertAttempt/finishOriginalAttempt；OrderLink.selectByExternalIdentity/bindOriginalImport；ResourceMappingRevision.appendRevision/selectHistorical；StatementLine.selectAuthorizedStoreLines。\n\n### Req VO与查询对象\n\n请求和响应均在commerce-api的`ai.neargo.shop.commerce.dto`；不将Entity作为响应。以下为实际API引用的唯一类型名，PageData沿原合同。\n\n';
const reqs=[...new Set(d.apis.map(a=>a.request))].sort();const vos=[...new Set(d.apis.map(a=>a.response.replace(/^PageData</,'').replace(/>$/,'')))].sort();
code+=table(['类型','完整包名/模块'],[...reqs.map(x=>[x,'commerce-api / '+(d.implementation?.dtoTypes?.[x]||'ai.neargo.shop.commerce.dto.'+x)]),...vos.filter(x=>!reqs.includes(x)).map(x=>[x,'commerce-api / '+(d.implementation?.dtoTypes?.[x]||'ai.neargo.shop.commerce.dto.'+x)])] );
const portTypes=[...new Set(d.ports.flatMap(p=>[...p.methods.matchAll(/\b[A-Z][A-Za-z0-9]*\b/g)].map(m=>m[0])))].filter(x=>!reqs.includes(x)&&!vos.includes(x)).sort();
code+='本批映射Req/VO实际为`ai.neargo.shop.commerce.dto.MappingContracts`中的嵌套record，typed资源身份位于`ChannelResourceIdentityGateway`。目录Req/VO实际为`ai.neargo.shop.commerce.dto.CatalogContracts`中的嵌套record；查询筛选直接绑定RequestParam，候选Query类未单独落地。目录内部数据合同在`ChannelCatalogSourcePort`与`ChannelCatalogGateway`的嵌套record中，不存在同名dto副本。以下其余全量目标类型仍为候选。\n\n';
code+='内部合同数据类型同在commerce-api的dto（凭据handle为spi受控类型，不参与JSON或公共响应）：'+portTypes.map(x=>'`'+x+'`').join('、')+'。\n\n';
code+='### 跨域Port与提供方\n\n'+table(['唯一接口','提供方/方法'],d.ports.map(p=>[p.package+'.'+p.name,p.owner+'；'+p.methods]));
code+='接口放commerce-api而非把commerce反向依赖塞入shop-base。餐饮实现放`ai.neargo.shop.food.port`，零售来源/订单实现归相应product/trade的`.port`；确需跨域只读组合放宿主`ai.neargo.shop.commercebridge.port`并只调用Port。实现可命名FoodChannelCatalogSourcePortImpl、RetailChannelCatalogSourcePortImpl、FoodChannelOrderIngestPortImpl、RetailChannelOrderIngestPortImpl；远程实现放commerce-channel的`.port`，保持同一SPI。所有本地/远程实现按sourceOwner和部署形态注册，不按客户端输入选择。\n\n### 平台网关及辅助类\n\n外部网关SPI在`ai.neargo.shop.spi.commerce`：CommerceGateway仅标识/能力；CatalogGateway、AvailabilityGateway、InventoryGateway、OrderGateway、FulfillmentGateway、AfterSaleGateway、RedemptionGateway、StatementGateway分别提供typed操作，不能用Object+action字符串代替类型安全。平台实现继承统一基类并只声明实际支持接口。\n\n';
code+=table(['模块/完整类名','职责'],[
 ['commerce-channel / ai.neargo.shop.commerce.channel.base.AbstractCommerceGateway','final执行模板；鉴权/能力/调用/结果归一顺序不可跳过'],
 ['commerce-channel / ai.neargo.shop.commerce.channel.base.CommerceGatewayExecutor','传输执行与证据，不持事务跨网络，不擅自重试'],
 ['commerce-channel / ai.neargo.shop.commerce.channel.CommerceGatewayRegistry','按profile查唯一已核验网关及能力接口；缺失明确拒绝'],
 ['commerce-channel / ai.neargo.shop.commerce.channel.client.ChannelHttpClient','原始HTTP及响应，限流范围按平台应用/账号而非每店各限'],
 ['commerce-channel / ai.neargo.shop.commerce.channel.adapter.{provider}.{Provider}Gateway','UberEats/Foodpanda/Keeta/Talabat/Deliveroo/NoonFood/CareemFood/SmilesFood/Taobao/DouyinEcommerce/DouyinLocalLife候选，获准再实现'],
 ['commerce-channel / ai.neargo.shop.commerce.channel.adapter.{provider}.{Provider}AuthStrategy','凭据、签名、刷新及账号范围锁'],
 ['commerce-channel / ai.neargo.shop.commerce.channel.adapter.{provider}.{Provider}WebhookVerifier','原始字节验签及平台ACK编码'],
 ['commerce-channel / ai.neargo.shop.commerce.channel.adapter.{provider}.{Provider}CatalogAssembler','完整目录及平台特定属性转换'],
 ['commerce-channel / ai.neargo.shop.commerce.channel.adapter.{provider}.{Provider}OrderAssembler','原始平台快照到统一订单；保留原行与金额语义'],
 ['commerce-core / ai.neargo.shop.commerce.policy.ChannelRecoveryPolicy','查询/原键重放/等待/人工，非幂等UNKNOWN不盲重发'],
 ['commerce-core / ai.neargo.shop.commerce.policy.ChannelRoutingPolicy','可信连接路由及首版混合行业拒绝'],
 ['commerce-core / ai.neargo.shop.commerce.statemachine.SyncRunStateMachine','任务与远端一致性分别迁移'],
 ['commerce-core / ai.neargo.shop.commerce.statemachine.ChannelOrderStateMachine','导入、接单、履约、资金证据分状态，乱序不覆盖'],
 ['commerce-core / ai.neargo.shop.commerce.event.ChannelCatalogSourceChangedConsumer','消费原来源版本，合并未发意图'],
 ['commerce-core / ai.neargo.shop.commerce.event.ChannelFulfillmentChangedConsumer','消费已提交履约事实，原动作身份去重'],
 ['commerce-core / ai.neargo.shop.commerce.event.ChannelOperationRequestedConsumer','原Outbox唤醒，不另建发送队列账'],
 ['commerce-web / ai.neargo.shop.commerce.job.CommerceRecoveryJob','唯一常规定时入口：漏同步检查/到期状态/UNKNOWN/导入恢复；任务平台锁和DBfence'],
 ['commerce-core / ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration','关闭/集成/独立装配；Mapper/迁移本域隔离'],
 ['commerce-web / ai.neargo.shop.commerce.config.CommerceWebAutoConfiguration','B/OPS/callback及远程Service选择'],
 ['commerce-server / ai.neargo.shop.commerce.server.CommerceApplication','独立宿主启动，沿父POM，无新框架版本']]);
code+='## L4 实施检查及验收清单\n\n下列是全量目标类清单，已编码子集见实施进度；未列入实际范围的类仍PLANNED，不把方法清单当已编译结果。新工程必须扩展架构扫描面：Controller不依赖Mapper/core、core不依赖web/channel及行业实现、唯一spi、无环、默认关闭和独立模式唯一写者。\n\n'+table(['AC','计划测试方法','执行状态'],d.acceptance.map(a=>[a[0],a[2],'全量验收尚未完成；子集证据见TDD']));
code+='测试源码随整批实现完成，之后统一编译默认/集成/独立形态，再集中专项；失败仅复测相关范围。本文生成合同检查不是Java测试或真实供应商验收。\n\n[总览](../TDD-统一销售渠道接入.md) · [数据库](./统一销售渠道接入-数据库设计.md) · [API](./统一销售渠道接入-API清单.md)\n';
files.set(base+'统一销售渠道接入-代码清单.md',code);
// Compact relationship overview derived from the table-group source.
const esc=s=>s.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');
const nodes=d.diagram.nodes;const positions=new Map(nodes.map((n,i)=>[n[0],{x:95+(i%2)*285,y:30+Math.floor(i/2)*180}]));
let svg='<svg xmlns="http://www.w3.org/2000/svg" width="760" height="760" viewBox="0 0 760 760"><title>统一销售渠道接入表族逻辑关系</title><desc>根据commerce-integration-design.json生成的八个表族和逻辑引用；不表示跨域物理外键。</desc><defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0 0L8 4L0 8" fill="#64748b"/></marker></defs><rect width="760" height="760" fill="white"/>';
for(const [a,b] of d.diagram.edges){const p=positions.get(a),q=positions.get(b);const dx=q.x-p.x,dy=q.y-p.y;const scale=Math.max(Math.abs(dx)/125,Math.abs(dy)/70);const ox=dx/scale,oy=dy/scale;svg+=`<path d="M${p.x+125+ox} ${p.y+70+oy} L${q.x+125-ox} ${q.y+70-oy}" fill="none" stroke="#64748b" stroke-width="1.5" marker-end="url(#arrow)"/>`;}
for(const [id,label,tt] of nodes){const p=positions.get(id);assert(tt.every(t=>tables.has(t)),`Unknown diagram table ${id}`);svg+=`<g><rect x="${p.x}" y="${p.y}" width="250" height="140" rx="8" fill="#f8fafc" stroke="#334155"/><text x="${p.x+12}" y="${p.y+25}" font-family="Arial,PingFang SC,sans-serif" font-size="15" fill="#0f172a">${esc(label)} · ${tt.length}表</text>`;tt.forEach((t,j)=>{svg+=`<text x="${p.x+12}" y="${p.y+47+j*15}" font-family="monospace" font-size="10" fill="#334155">${esc(t)}</text>`;});svg+='</g>';}
svg+='</svg>\n';files.set(base+'diagrams/commerce-integration-table-groups.svg',svg);
let stale=[];for(const [name,body] of files){const target=path.join(root,name);if(check){if(!fs.existsSync(target)||fs.readFileSync(target,'utf8')!==body)stale.push(name);}else{fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,body);}}
if(stale.length)throw new Error('Stale generated design: '+stale.join(', '));
console.log(`${check?'Checked':'Generated'} ${d.tables.length} candidate tables, ${d.apis.length} planned APIs, ${d.ports.length} ports, ${files.size} artifacts`);
