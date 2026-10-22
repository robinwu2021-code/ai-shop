import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {buildFoodModuleInventory,renderFoodModuleInventory} from './lib/food-module-code-inventory.mjs';
import {buildFoodMethodInventory,renderFoodMethodInventory} from './lib/food-method-inventory.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const s=JSON.parse(fs.readFileSync(path.join(root,'docs/technical/design/schema/food-system-design.json'),'utf8'));
const codePlan=JSON.parse(fs.readFileSync(path.join(root,'docs/technical/design/schema/food-code-plan.json'),'utf8'));
const baselines=JSON.parse(fs.readFileSync(path.join(root,'docs/technical/design/schema/food-code-baselines.json'),'utf8'));
const cap=x=>x.split(/[_-]/).filter(Boolean).map(x=>x[0].toUpperCase()+x.slice(1)).join('');
const singular=x=>({dishes:'dish',statuses:'status',entries:'entry',policies:'policy',batches:'batch','sync-batches':'sync-batch'}[x]|| (x.endsWith('ies')?x.slice(0,-3)+'y':x.endsWith('s')?x.slice(0,-1):x));
const configs={
 A:['operation','StoreOperationService',['StoreOperationPolicy','ServiceCalendarPolicy']],
 B:['catalog','MenuService',['DishProfile','Menu','BundleDefinition','ModifierDefinition','OptionGroup']],
 C:['dining','DiningService',['DiningCheck','TableOccupancy','Reservation','GuestSession','SeatAssignment']],
 D:['ordering','DiningOrderService',['CheckCart','OrderBatch','FoodLine','CoreOrderBinding','CoreLineAllocation']],
 E:['kitchen','KitchenService',['KitchenTicket','KitchenTask','Course','ServiceEvent']],
 F:['checkout','DiningCheckoutService',['Settlement','SettlementScope','ChargeIntent']],
 G:['returns','DiningReturnService',['FoodReturn','ReturnLine','ReturnDecision']],
 H:['print','PrintApplicationService',['PrintJob','PrintAttempt','PrintDocument']],
 I:['closing','BusinessCloseService',['BusinessDay','CashShift','CashEntry','CashCount','CloseRun','CloseException']],
 J:['report','FoodReportService',['MetricDefinition','FoodReportQuery','FoodReportResult']],
 K:['integration','SyncBatchService',['ExternalMapping','SyncBatch','EdgeLease','IntegrationException']],
 L:['format','DiningEntitlementService',['DiningEntitlement','BanquetPlan','BottleCustody','RoomChargeIntent']],
};
const typeFor=a=>{
 let action=a.path.split(':')[1];let parts=a.path.split('/').filter(x=>x&&!x.startsWith('{')&&!['biz','mp','ops','internal','x','food'].includes(x));
 const last=parts.at(-1)?.split(':')[0]||'operation';
 if(a.method==='GET'&&!action&&!a.path.split('/').at(-1).startsWith('{')&&last.endsWith('s'))return 'list'+cap(last);
 if(a.path.endsWith('/definition'))return 'save'+(a.path.includes('/bundles/')?'Bundle':'Menu')+'Definition';
 if(!action&&a.method==='POST'&&last==='quotes')return 'quote';
 if(!action&&a.method==='POST'&&last==='batches')return 'place';
 if(!action&&a.method==='POST'&&last==='checks')return 'openCheck';
 const stem=action?cap(action)[0].toLowerCase()+cap(action).slice(1)+cap(singular(last)):({GET:'get',POST:'create',PUT:'save',DELETE:'archive'}[a.method])+cap(singular(last));
 return stem;
};
const resources={policy:['StoreOperationPolicy','StoreOperationService'],policies:['StoreOperationPolicy','StoreOperationService'],calendar:['ServiceCalendar','StoreOperationService'],menus:['Menu','MenuService'],'option-groups':['OptionGroup','OptionGroupService'],bundles:['Bundle','BundleService'],stations:['Station','StationService'],dishes:['Dish','DishService'],modifiers:['Modifier','ModifierService'],'sku-routes':['KitchenRoute','KitchenRouteService'],'sales-restrictions':['SalesRestriction','SalesRestrictionService'],catalog:['MenuCatalog','MenuService'],tables:['Table','TableService'],sessions:['DiningSession','DiningSessionService'],reservations:['Reservation','ReservationService'],checks:['Check','CheckService'],carts:['CheckCart','CheckCartService'],quotes:['DiningQuote','DiningOrderService'],batches:['OrderBatch','DiningOrderService'],tasks:['KitchenTask','KitchenTaskService'],courses:['Course','CourseService'],settlements:['DiningCheckout','DiningCheckoutService'],adjustments:['DiningAdjustment','DiningCheckoutService'],returns:['DiningReturn','DiningReturnService'],jobs:['PrintJob','PrintService'],'cash-shifts':['CashShift','CashShiftService'],entries:['CashEntry','CashShiftService'],closes:['BusinessClose','BusinessCloseService'],exceptions:['CloseException','BusinessCloseService'],reports:['DiningReport','DiningReportService'],'report-exports':['DiningReportExport','DiningReportService'],'report-rebuilds':['DiningReportRebuild','DiningReportService'],capabilities:['DiningCapability','DiningCapabilityService'],mappings:['ExternalMapping','ExternalMappingService'],'integration-exceptions':['IntegrationException','IntegrationExceptionService'],'sync-batches':['SyncBatch','SyncBatchService'],entitlements:['DiningEntitlement','DiningEntitlementService'],'banquet-plans':['BanquetPlan','BanquetPlanService'],custodies:['BottleCustody','BottleCustodyService'],'room-charges':['RoomCharge','RoomChargeService']};
Object.assign(resources, {'table-areas':['TableArea','TableService'],'table-operations':['TableOperation','CheckService'],seats:['CheckSeat','CheckService'],actions:['DiningActionLog','CheckService'],'config-revisions':['ConfigRevision','MenuService'],operations:['OrderBatchOperation','DiningOrderService']});
Object.assign(resources, {'menu-channel-bindings':['MenuChannelBinding','MenuChannelBindingService'],syncs:['CatalogSync','CatalogSyncService'],'catalog-syncs':['CatalogSync','CatalogSyncService'],recipes:['Recipe','RecipeService'],'production-consumptions':['ProductionConsumption','ProductionConsumptionService']});
const seen=new Set();const methods=s.apis.map(a=>{
 const [pkg]=configs[a.domain];const realm=a.path.startsWith('/mp')?'Mp':a.path.startsWith('/ops')?'Ops':a.path.startsWith('/internal')?'Internal':'Biz';
 const segments=a.path.split('/').map(x=>x.split(':')[0]);let resource=segments.filter(x=>resources[x]).at(-1);
 if(resource==='adjustments'&&a.domain==='I')resource='closes';
 if(resource==='entries'&&a.domain==='L')resource='custodies';
 const [concept,defaultSvc]=resources[resource]||['DiningOperation','DiningOperationService'];const svc=codePlan.methodOverrides[a.id]?.service||defaultSvc;
 const controller=codePlan.methodOverrides[a.id]?.controller||realm+concept+'Controller';let method=codePlan.methodOverrides[a.id]?.method||typeFor(a);if(seen.has(controller+'.'+method))throw Error('Duplicate method '+controller+'.'+method);seen.add(controller+'.'+method);
 const name=method[0].toUpperCase()+method.slice(1);const owner=a.domain==='H'?'ai.neargo.shop.print':'ai.neargo.shop.food';
 return {...a,httpMethod:a.method,pkg,svc,controller,controllerPackage:owner+'.api.'+realm.toLowerCase(),method,request:name+(a.method==='GET'?'Query':'Req'),vo:concept+'VO'};
});
const persistence=s.tables.map(t=>{
 const report=t.name.startsWith('rpt_'),print=t.name.startsWith('prn_');
 const owner=print?'shared-print':report?'shop-report':'food-core';const pkg=print?'ai.neargo.shop.print':report?'ai.neargo.shop.report':'ai.neargo.shop.food';
 const entity=report?'DailyFoodMetricRow':cap(t.name);const mapper=report?'ReportFoodMetricDailyDao':(print?'PrintMappers.':'FoodMappers.')+cap(t.name.replace(/^(fnb|prn|rpt)_/,''))+'Mapper';
 return {table:t.name,entity,entityClass:pkg+(report?'.dto.':'.entity.')+entity,mapper,mapperClass:pkg+(report?'.dao.':'.mapper.')+mapper,owner};
});
const moduleInventory=buildFoodModuleInventory(s,{methods:methods.map(a=>({apiId:a.id,controller:a.controller,controllerPackage:a.controllerPackage,controllerClass:a.controllerPackage+'.'+a.controller,method:a.method,service:a.svc,serviceClass:(a.domain==='H'?'ai.neargo.shop.print':'ai.neargo.shop.food')+'.service.'+a.svc,request:a.input?a.request:null,response:a.vo,httpMethod:a.httpMethod,path:a.path})),persistence},codePlan,baselines);
for(const a of methods){const item=moduleInventory.endpoints.find(e=>e.apiId===a.id);a.request=item.requestClass;a.vo=item.returnType;}
let md=`# 餐饮系统代码结构与对象清单

> 状态：待评审 · ${s.reviewDate||"2026-10-19"} · 本轮修订为core零售同款组织方式；原Repository及8模块方案作废，不作为并行选项。
> 由 \`node scripts/gen-food-code-inventory.mjs\` 生成，勿手改；真源：[设计源](./schema/food-system-design.json)及生成器中的类映射。
> 统一Java规范：[平台Java规范](../../../.claude/skills/ai-shop-dev-standards/references/java.md) §七；包与类名以该规范为准。
> 当前开发入口：[按业务模块代码清单](./餐饮系统-按业务模块代码清单.md)，按模块→API→类/DTO→表对账；本册保留统一包结构与全量索引。
> 上游：[底座兼容](./餐饮系统-项目底座兼容对齐.md) · [领域对象](./餐饮系统-领域对象与设计总览.md) · [数据库](./餐饮系统-数据库设计.md) · [API输入输出](./餐饮系统-API设计.md)。

## 一、结论与现有代码依据

采用与shop-core/product、当前trade入口相同的api、dto、service、service.impl、entity、mapper组织。ServiceImpl直接调用本域Mapper，跨域依赖Port；不要求Repository、持久化聚合转换层或每接口三套同义DTO。领域对象清单表达业务概念，不意味着每个概念都生成DDD聚合类。

| 现有依据 | 餐饮对齐规则 |
|---|---|
| product/api/biz、mp、ops | 餐饮api按端组织，厨房/服务员/收银共用biz权限分流 |
| GoodsService与GoodsServiceImpl | service接口加service.impl实现；直接注入本域Mapper |
| ProductMappers.GoodsMapper extends BaseMapper<PrdGoods> | FoodMappers.DishMapper等集合嵌套Mapper；Entity仍Fnb前缀 |
| OrderService.CreateOrderCommand等record | 简单入参放Service嵌套Command或Controller内Req；复杂共享DTO才独立文件 |
| GoodsVO、OrderVO及BaseEntity | 持久化Entity和响应VO分离；不返回实体；沿公共字段与序列化 |
| product/port及shop-base/spi | 跨域Port优先复用既有契约，本地或远程替换实现 |
| trade/order下状态机、policy、assembler | 复杂业务按需细分；不照搬迁移中的trade全部目录 |

保留独立构建及双部署需求，与统一代码风格不冲突。独立项目不能复制账号主体门店营业时间，也不能直接写零售核心表。

## 二、项目与包结构

\`\`\`text
backend-plugins/shop-industry-food/
  pom.xml                       独立reactor；沿组织父POM及版本管理
  food-api/                     Service接口、dto及餐饮专有spi；原包名不变
  food-core/                    service/impl、entity、mapper、policy、job、config；仅内部入口
  food-web/                     餐饮三端Controller与接入装配；可复用jar，不是独立进程
  food-channel/                 既有SPI协议客户端；无业务规则
  food-server/                  独立业务运行；只暴露/internal/food/**，不签发用户token
backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/
  mp/                           C端；原CONSUMER realm
  biz/                          B端；原MERCHANT realm和BizContext
  ops/                          平台运营端；原OPERATOR及数据域
backend/shop-app/.../industrybridge/food/port/
  *PortImpl.java                 只补缺失的核心桥接，既有提供方直接复用
\`\`\`

Java 21、Spring Boot 4.0.x、MyBatis-Plus及Jackson版本沿backend父工程管理；不另钉版本。独立reactor不加入shop-parent.modules，CI解析共享构件的发布版本，不依赖个人本机m2。

| 模块 | 依赖与职责 | 禁止事项 |
|---|---|---|
| food-api | Service接口、dto及餐饮专有spi；包名沿原service/dto/spi，通用Port复用底座 | Mapper、业务实现、核心实体 |
| food-core | food-api、shop-base、shop-base-auth、shop-store-mybatis等公共底座；业务实现、内部入口及持久化；不含三端入口 | shop-core业务实现/Mapper、shop-app反向依赖 |
| food-channel | food-api及已有契约、svc-client；跨域HTTP Interface适配；food-web中的foodclient负责本域Service远程代理 | 餐饮事务、计价、资金确认和制作规则 |
| food-server | food-core、必要协议适配与内部服务密钥；仅内部HTTP | 三端用户鉴权、核心/支付/库存Mapper直连 |
| food-web | 三端Controller、请求校验、权限与接入装配；依赖food-api/现有认证契约 | Mapper、餐饮业务规则、身份主数据副本 |
| 宿主shop-app | 引入food-web并复用既有鉴权；按部署装配本地业务或远程适配，不持有餐饮Controller源码 | 把核心依赖反向传进餐饮业务jar |

原food-domain/application/infrastructure/web/starter八模块分层不再采用。自动装配类及imports资源留food-core/config，独立进程使用food-server；领域只分包，未来有真实部署或数据边界再拆模块。food-api不是重做通用industry-spi。三端Controller源码归food-web，运行入口统一由平台挂载；独立餐饮不复制用户鉴权；支付与库存经权威Port联通独立系统，缺合同必须明确阻塞。

## 三、标准调用链与对象职责

\`\`\`text
宿主BizOrderBatchController（既有鉴权/门店范围）
  -> DiningOrderService.place（food-api中既有完整可信参数签名）
    -> 本地实现或远程Service适配；独立形态经受控内部接口
    -> DiningOrderServiceImpl
      -> OrderPlacementPolicy / OrderBatchStateMachine（必要时）
      -> FoodMappers.OrderBatchMapper
      -> 订单SPI -> OrderPlacementPortImpl 或 RemoteOrderPlacementPortImpl
  -> OrderBatchVO -> 现有响应包装 -> {code,msg,data}
\`\`\`

| 类型 | 位置 | 规则 |
|---|---|---|
| Controller | 三端api.biz/mp/ops在food-web，由shop-app挂载；内部api.internal在food-core/独立服务 | 认证、参数校验、权限及身份门店上下文；不得调用Mapper |
| Service接口/实现 | service / service.impl | 用例入口；Impl直接调用本域Mapper，在现有事务边界内编排 |
| Entity | entity | BaseEntity及@TableName，id不进URL响应；不作为Request/VO |
| Mapper | mapper | BaseMapper嵌套接口；容量、数量、版本竞争用条件SQL，不放业务流程 |
| Req/VO | dto或Controller内record | 复用同语义输入输出，不机械创建Req→Command→DomainDTO三套对象 |
| Command | 简单命令嵌套Service record | 复杂或跨服务共享才单独文件；不要求每个API均有Command类 |
| Policy/StateMachine | policy/statemachine | 复杂状态及不变量集中，普通CRUD不用先造领域聚合 |
| Assembler | assembler，可选 | 必要的复杂快照/VO转换；简单映射在Impl中完成 |
| Port/Client | 既有spi或food-api；协议客户端在food-channel/client；Port实现在提供方.port | 本地远程同语义；使用真实已接线入口 |

food-api中的service契约与food-core中的service/impl、entity/mapper不是另一套基础商品订单。跨域数据通过Port读取，本域多表允许在Service协调；禁止新增Repository再包一层BaseMapper。公共错误沿BizException/ErrorCode，分页沿PageData，权限沿BizContext和既有注解。

## 四、分域Service与辅助对象清单

`;
for(const d of s.domains){const [pkg,,objects]=configs[d.code]; const svc=[...new Set(methods.filter(a=>a.domain===d.code).map(a=>a.svc))].join("、");md+=`### ${d.code} ${d.name}

需求：${d.requirements.join('、')}。

| 类型 | 建议清单 | 说明 |
|---|---|---|
| Service | ${svc}（各有对应Impl） | ${d.code==='H'?'共享打印能力，food只提供内容适配':d.code==='J'?'餐饮报表编排，投影存储归shop-report':'位于service/service.impl，直接本域Mapper'} |
| 业务概念 | ${objects.join('、')} | 概念不是强制Java类；状态复杂时才拆Policy/StateMachine |
| Mapper集合 | ${d.code==='H'?'PrintMappers':d.code==='J'?'FoodMappers/报表DAO':'FoodMappers'} | ${d.code==='H'?'共享打印模块':d.code==='J'?'fnb配置留food，rpt映射归shop-report':'food-core/mapper'} |
| 核心规则 | ${d.invariant} | 用Service及必要的辅助类落实，不增加强制Repository |

`;}
md+=`## 五、Controller方法与输入输出清单

以下${s.apis.length}个目标端点对应按业务资源拆分的Controller，均为规划；Controller名按端区分，不能按每个页面复制Service。方法入出参字段查对应API编号；表中Req/VO名字是便于检索的建议，可复用Service嵌套Command、已有公共VO，不能按行机械生成${s.apis.length}套同义DTO。无额外输入不建空Req。

| API | Controller | 方法 | 入参建议 | Service | 输出建议 | 业务 |
|---|---|---|---|---|---|---|
`;
for(const a of methods)md+=`| ${a.id} | ${a.controllerPackage}.${a.controller} | ${a.method}(...) | ${a.input?a.request:'仅路径参数'} | ${a.svc} | ${a.vo} | ${a.name} |\n`;
md+=`\n顾客端白名单已逐项编号并映射对应Mp Controller；顾客会话、台账、购物车、订单、结账、退餐均复用同一业务Service并限制本人/明确获准范围。不能将整个biz资源自动开放给mp。桌台合并/拆分仅改变服务归属；收款拆合留在DiningCheckoutService。新增动作按统一lowerCamelCase命名，dishes使用单数Dish。\n\n## 六、Entity与Mapper逐表清单

表与字段沿数据库设计；${s.tables.length}张是候选映射，不是全部新建。Mapper内接口名不带Fnb持久化前缀，沿GoodsMapper/PrdGoods风格；同集合名字唯一。Mapper不是一行一文件。

| 表 | Entity | Mapper集合及嵌套接口 | 所属模块 |
|---|---|---|---|
`;

for(const p of persistence)md+=`| ${p.table} | ${p.entityClass} | ${p.mapperClass} | ${p.owner} |\n`;
md+=`\n共享打印类名为该能力的候选名，实施前与共享模块统一；rpt_沿JdbcClient/DAO和Row record，不生成MyBatis Mapper。共享prn_五表归共享打印能力，rpt_一表归shop-report；food-core只管理${s.tables.filter(t=>t.name.startsWith("fnb_")).length}张餐饮候选表。基础商品、SKU、账号、主体、门店、资金、订单不生成餐饮Entity/Mapper副本。

### Controller及Service完整包名

Controller完整类名见上一节。Service统一为ai.neargo.shop.food.service.XxxService、ai.neargo.shop.food.service.impl.XxxServiceImpl；打印例外归ai.neargo.shop.print.service/impl。Req/VO统一ai.neargo.shop.food.dto或所属Controller内record；不得新建dto.request/response/application DTO平行层。每个类的文件位于对应模块src/main/java加包路径，嵌套Mapper只占集合类文件。机器清单同步保存controllerClass、serviceClass、serviceImplClass、entityClass、mapperClass。

## 七、定制Mapper方法及Service事务边界

| Service | Mapper方法建议 | 并发/恢复要求 |
|---|---|---|
| CheckServiceImpl | selectForUpdateByStoreAndTableNo、selectActiveByTableForUpdate、endOccupancyIfActive、advanceOperationIfVersion | 显式主体门店；先占新再放旧；共享资源仍由资源Port仲裁 |
| DiningOrderServiceImpl | insertBatchIfAbsent、bindCoreResultIfVersion、insertAllocationIfAbsent、archiveSelectedSubmittedLines | 同请求哈希幂等；核心调用遵守原事务；本域结果可恢复 |
| KitchenTaskServiceImpl | insertDispatchIfAbsent、advanceTaskQuantityIfVersion、appendEventIfAbsent | 不因补打重复派工；数量容量条件更新 |
| DiningCheckoutServiceImpl | insertFrozenScope、insertIntentIfAbsent、confirmIntentFromCoreIfStatus、finalizeIfCoreConfirmed | 跨单应收范围由核心权威仲裁；不直接markPaid |
| DiningReturnServiceImpl | selectActiveReturnQuantityByFoodLine、selectScopedByKeyForUpdate、BaseMapper.update（厨房/财务状态条件更新） | 并发防超退；厨房与退款分轨 |
| BusinessCloseServiceImpl | signIfVersionHashAndWatermark、replaceCurrentCloseIfVersion、BaseMapper.insert（新关账版本） | 现金不二次记销售流水；签核只追加；关账不等于统计重算 |
| SyncBatchServiceImpl | selectLeaseByStoreEpochForUpdate、insertSyncBatchIfAbsent、selectAcceptedThrough、renewLeaseIfEpoch | 不重复认领资金库存和制作；旧租约不获得新写权限 |
| DiningEntitlementServiceImpl | withdrawIfRemaining、BaseMapper.update（权益版本条件更新） | 余量不负；收费/PMS结果由外围权威确认 |

这些方法定义在相应Mapper集合中；SQL注解或XML按复杂度选择，不要求空XML。更新返回0须正确区分版本冲突、无权/不存在及容量不足。事务放ServiceImpl；跨核心、餐饮、打印和支付数据库不假定同一个本地事务。

## 八、跨域Port及适配器

| Port契约命名位置 | 本地实现完整名 | 远程实现完整名 | 用途 |
|---|---|---|---|
`;
const ports=[['StoreDirectory','user','复用现有StoreDirectoryPort/MerchantQueryPort，不另造门店主数据'],['GoodsQuery','product','复用现有GoodsQueryPort，选配合同缺口单独补齐'],['OrderPlacement','trade','当前建单门面与结果恢复，拟补最小SPI'],['InventoryCommitment','inventory','后付承诺，不重复库存锁定'],['DiningFulfillment','trade','堂食交付合同，其他履约优先复用'],['Cashier','trade','跨单应收范围及分配，目标合同'],['PaymentQuery','pay','可信付款查询，已有优先复用'],['OrderAdjustment','trade','应收调整，目标合同'],['AfterSale','trade','当前全单售后，部分退款门控'],['Resource','user','共享资源原语，已有能力先核对'],['Approval','approval','公共审批能力，目标合同'],['DiningReport','report','餐饮指标及共享报表'],['Reconciliation','pay','资金核对证据'],['MemberAsset','member','会员资产，优先现有端口'],['AccountsReceivable','pay','应收挂账，目标合同'],['Print','print','共享打印目标合同'],['RoomAccount','food','PMS房账外部合同'],['ExternalRestaurant','food','外部餐饮协议']];
for(const[n,owner,use]of ports)md+=`| ai.neargo.shop.spi.${owner}.${n}Port | ${n==='StoreDirectory'?'ai.neargo.shop.merchant.port.StoreDirectoryPortImpl（既有唯一实现）':'ai.neargo.shop.industrybridge.food.port.'+n+'PortImpl'} | ai.neargo.shop.food.port.Remote${n}PortImpl | ${use} |\n`;
md+=`\n协议客户端复用svc-client的HTTP Interface/RestClient，本地桥接仅宿主依赖核心实现；表中除明确复用者外均为待评审目标接口；18类适配概念不意味着要再建18套已有SPI；身份在平台接入层沿既有BizContext解析，不生成BusinessIdentityPort。输入输出见API设计第七节。food-core不持有核心Mapper，food-channel不包含营业规则。

## 九、其他对象与资源

| 类型 | 清单 | 说明 |
|---|---|---|
| Policy/状态机 | FoodCheckStateMachine、OrderBatchStateMachine、KitchenTaskPolicy、SettlementPolicy、FoodReturnPolicy、BusinessClosePolicy | 按复杂度选用，集中状态与数量守卫；不复制零售主状态机 |
| 事件 | FoodBatchAccepted、KitchenTaskReady、FoodLineServed、FoodReturnApproved、SettlementFinalized、BusinessCloseSigned | 带业务号、主体门店、版本、发生时刻；复用公共outbox |
| 消费者 | CoreSubOrderPaidConsumer、CoreOrderCancelledConsumer、CoreAfterSaleResultConsumer、CoreFulfillmentResultConsumer、KitchenDispatchConsumer、FoodPrintConsumer | 只适配真实存在的核心事件；绑定晚于事件可恢复，去重 |
| 恢复任务 | FoodPlacementRecoveryJob、FoodChargeRecoveryJob、FoodReturnRecoveryJob、FoodRoomChargeRecoveryJob | 查同业务号恢复，不换键重复调用 |
| 营业任务 | FoodUnsettledCheckJob、FoodSalesRestrictionExpiryJob、FoodBusinessDayRolloverJob、FoodClosePrecheckJob、FoodReportWatermarkJob、FoodSyncRecoveryJob | 沿现有JobHandler框架；日切不等于签核；恢复沽清不回填库存 |
| 配置 | FoodAutoConfiguration、FoodProperties、FoodPersistenceConfiguration、FoodPortConfiguration | 位于food-core/config；local/remote互斥，注册Mapper扫描及数据范围 |
| 启动 | FoodApplication | food-server内部启动，沿服务密钥、内部协议与日志；无三端用户认证 |
| 宿主配置 | LocalFoodBridgeConfiguration | shop-app装配本地桥接，不反向依赖 |
| 常量权限 | FoodCapabilities、FoodActions、FoodPermissions、FoodErrorCode映射 | 沿现有常量/权限种子/ErrorCode注册，不造另一套RBAC或异常包 |

\`\`\`text
food-core/src/main/resources/
  db/industry/food/      仅fnb_表，独立fnb_flyway_history
  mapper/food/          复杂SQL才加XML
  META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
food-server/src/main/resources/
  application.yml       沿公共数据源、认证、时区与日志约定；不写秘密
food-core/src/test/java/.../
  service/              用例守卫及真实持久化竞态
  contract/             本地远程同合同
  architecture/         web禁Mapper、跨域禁核心实体、依赖环检查
  scenario/             中西餐、先后付、跨日、打印、恢复同步
\`\`\`

## 十、与前版的替换关系及验证范围

本版取代food-domain/application/infrastructure/web/starter分层和全部Repository/RepositoryImpl清单。当前设计含${s.tables.length}张候选表、${s.apis.length}个目标API及109项需求追踪；目标合同不表示已实现，缺口见API册第八节；需要复杂policy/state machine时按core现有方式局部引入。历史八模块草案不再作为可选推荐。

后续按主体切店/realm权限、幂等和未知恢复、同SKU多口味、资源容量、分批出品、退餐并发、后付库存、现金归班、签核更正、打印补打及同步去重设计业务验证。文档检查不代替Java编译或业务测试。${s.databaseInitialization?"本机73张餐饮业务表已初始化，执行记录见TDD-餐饮系统-数据库初始化；详细方法以《餐饮系统-Controller-Service-Mapper方法清单》为准；五个餐饮Java模块及部分管理端点已有实现；目标方法清单不能替代逐接口编译、原生数据库与宿主验收；订单报价、称重、耗用及Keeta仍按独立依赖记录。":"本轮未创建Java工程、源类或迁移。"}
`;
fs.writeFileSync(path.join(root,'docs/technical/design/餐饮系统-代码结构与对象清单.md'),md);
fs.writeFileSync(path.join(root,'docs/technical/design/schema/food-code-inventory.json'),JSON.stringify({status:'PROPOSED',style:'platform-java-standard',source:'food-system-design.json',packageRoot:'ai.neargo.shop.food',modules:['food-api','food-core','food-web','food-channel','food-server'],controllers:[...new Set(methods.map(a=>a.controller))],methods:moduleInventory.endpoints,persistence:moduleInventory.persistence,businessModuleIndex:moduleInventory.modules.map(m=>({moduleId:m.moduleId,name:m.name,ownedApiIds:m.ownedApiIds,ownedTables:m.ownedTables}))},null,2)+'\n');
fs.writeFileSync(path.join(root,'docs/technical/design/schema/food-module-code-inventory.json'),JSON.stringify(moduleInventory,null,2)+'\n');
fs.writeFileSync(path.join(root,'docs/technical/design/餐饮系统-按业务模块代码清单.md'),renderFoodModuleInventory(moduleInventory));

const methodPlan=JSON.parse(fs.readFileSync(path.join(root,'docs/technical/design/schema/food-method-plan.json'),'utf8'));
const methodInventory=buildFoodMethodInventory(s,moduleInventory,methodPlan,codePlan);
fs.writeFileSync(path.join(root,'docs/technical/design/schema/food-method-inventory.json'),JSON.stringify(methodInventory,null,2)+'\n');
fs.writeFileSync(path.join(root,'docs/technical/design/餐饮系统-Controller-Service-Mapper方法清单.md'),renderFoodMethodInventory(methodInventory,moduleInventory));
console.log(`Generated CORE-aligned inventory: 5 modules, ${new Set(methods.map(a=>a.controller)).size} controllers, ${methods.length} API methods, ${persistence.length} table mappings`);
