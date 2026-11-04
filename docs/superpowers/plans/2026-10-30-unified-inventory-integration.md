# Unified Inventory Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保留零售、美业、餐饮库存权威，建立支持直连与服务发现扩展的统一库存SDK，补齐独立服务合同并解除商城远程模式的内嵌依赖。

**Architecture:** 复用svc-client作为基础调用层；inventory-api定义中立业务实体/值对象和命令回执，inventory-client封装类型化业务Service。商城行业SPI通过统一适配层接入，不把业务库存所有者和物理调用位置混为一体。

**Tech Stack:** Java 21、Spring Boot 4.0.7、现有Spring HTTP Interface/RestClient、MyBatis、Flyway、MySQL 9；现有Maven多模块与JUnit。当前不新增Nacos SDK，不安装注册中心。

**Spec:** [已批准设计](../specs/2026-10-30-unified-inventory-integration-design.md)。用户于本轮明确进入实施计划；本轮交付为方案，不是生产代码或上线。

**Contracts:** [API、Java模型与数据库调整明细](./2026-10-30-unified-inventory-api-model-schema.md)。实施前同时阅读，两份文档的类型、API编号和迁移保持一致。

## Global Constraints

- 保留现有库存权威，只统一接入与调用层；餐饮本地库存主账不迁移、不重算。
- 基线 `origin/codex/ai-shop-codex@92342744b`；执行前fetch并审查差异。不要把当前脏工作区混进源码包。
- 基础层不含库存/行业业务，库存服务不得依赖shop-base/shop-base-auth或import ai.neargo.shop.*。
- 保留现有API和构造器兼容；新增公开DTO仅在inventory-api，客户端不依赖inventory-domain。
- DIRECT/DISCOVERY模式显式选择；发现失败不回落直连，远端失败不回落内嵌。
- 写操作不盲目重试，原业务指令、来源证明及历史哈希必须保持；网络未知不等于业务未执行。
- 不改已执行迁移；库存最高V25、平台最高V621仅为规划时基线，落地前重新分配空闲版本。
- 运行账户仅本库权限，管理员令牌不进入商城运行时；不自动翻转任何真实业主或移动库存数据。
- 当前服务器库名是neargo_inv，不使用带test的部署数据库名；自动化临时验证库使用本次拥有的随机标识并清理，不冒充生产账。

## Review Focus

1. 请求已提交但回包丢失、随即发生实例切换：原指令查回且不重复出库（Task 2/6/13）。
2. FLIPPING与旧路由并发、历史原单从旁路恢复：事务内拒绝非写入方且保留原账身份（Task 3/6）。
3. 商品上下架/退休/停用事件乱序且跨market：完整来源快照不被旧载荷覆盖（Task 4）。
4. 美业只提供actor字符串、证据过期或委托作用域不匹配：不能创建留存或放行扣料（Task 7/8）。
5. 小数物料、旧整数manifest、缺币种和镜像generation交错：不取整、不改历史摘要、不把镜像变实存（Task 2/9/12）。

---

## 文件组织与任务依赖

为减少路径歧义，后文使用以下**路径简写**，不是Shell变量：

| 简写 | 仓库根目录下的完整相对路径 |
|---|---|
| SC | backend/svc-client/src/main/java/ai/neargo/svc/client |
| IA | backend/inventory/inventory-api/src/main/java/ai/neargo/inventory/api |
| IC | backend/inventory/inventory-client/src/main/java/ai/neargo/inventory/client |
| ID | backend/inventory/inventory-domain/src/main/java/ai/neargo/inventory/domain |
| IS | backend/inventory/inventory-svc/src/main/java/ai/neargo/inventory/svc |
| IG | backend/inventory/inventory-guard/src/main/java/ai/neargo/inventory/guard |
| SI | backend/shop-inventory/src/main/java/ai/neargo/shop/inventory |
| BR | backend/shop-app/src/main/java/ai/neargo/shop/invbridge |
| SB | backend/shop-base/src/main/java/ai/neargo/shop |
| PC | backend/shop-core/src/main/java/ai/neargo/shop/product |

测试路径采用对应模块的 `src/test/java` 与相同包层级。每一任务只提交明确列出的业务文件及必要测试/生成描述，不能 `git add .` 带入别人的改动。先建立隔离工作区，再执行。

原生测试的公共入口在Task3创建：`scripts/verify-inventory-integration-mysql.sh --tests <逗号分隔类名>`。它先验证三项INVENTORY_NATIVE凭据和MySQL 9版本，再设置INVENTORY_INTEGRATION_NATIVE=true，执行 `mvn -f backend/pom.xml -pl shop-app -am -Dtest=<指定类> -Dsurefire.failIfNoSpecifiedTests=false test`；创建/清理仅本次随机库，XML报告中指定类缺席或skipped>0即非零退出。后续Task4–12的原生RED/GREEN步骤均用此命令；普通单元/HTTP合同测试沿用各任务Maven命令。新增原生测试不默认连接服务器生产库，不把条件跳过当成功。

依赖：1→2→3；3→4/5；5→6；2→7→8；2/3→9；4/6/8/9→10→11→12→13。这里不要求并行，任务共享接口时优先顺序执行。

## Task 1: 提取可替换的基础寻址，兼容原调用者

**Files:** Create SC/ServiceEndpointResolver.java、SC/ResolutionMode.java、SC/ServiceEndpointResolvers.java；Modify SC/ServiceClientSpec.java、SC/ServiceClients.java、SC/CallOutcome.java、SB/svc/ServiceLocator.java、SB/svc/ConfigServiceLocator.java、SB/svc/ServiceName.java；Test backend/svc-client/.../ServiceEndpointResolversTest.java、现有ServiceClientsTest.java、shop-base/.../ServiceLocatorTest.java。

**Interfaces:** Consumes existing ServiceClientSpec.baseUrl；Produces `ServiceEndpointResolver.resolve(String): Optional<URI>`、`ServiceEndpointResolvers.direct(Map<String,URI>)`、`ServiceEndpointResolvers.select(ResolutionMode,ServiceEndpointResolver direct,ServiceEndpointResolver discovery)`；ServiceName新增INVENTORY。传输API按需增加重载，旧构造器仍编译。

- [ ] 1. 写失败测试 `directNormalizesLogicalNameAndPreservesEncodedQuery`、`discoveryResolvesEveryCallWithoutDirectFallback`、`missingDiscoveryProviderDiffersFromEmptyInstanceSet`；断言两次发现不同地址，但owner/path/query不变；空发现不会触发直连服务器。
- [ ] 2. 运行 `mvn -f backend/pom.xml -pl svc-client,shop-base -am -Dtest=ServiceEndpointResolversTest,ServiceClientsTest,ServiceLocatorTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认新增能力缺失导致失败，而非依赖未编译。
- [ ] 3. 实现上述SPI与兼容桥接；URI验证规则按附件§2.1；发现实现由宿主提供，SC不添加Nacos依赖。
- [ ] 4. 重跑同命令；新增/原有测试均通过，旧ServiceClientSpec调用点编译。
- [ ] 5. 仅暂存本任务文件，提交 `feat(svc): add pluggable service endpoint resolution`。

## Task 2: 建立库存业务模型、共享协议执行器与统一SDK

**Files:** Create IA/model下附件§1.1各公开类型、IA/original/OriginalStockContracts.java、IA/retention/ExecutionRetentionContracts.java、IA/catalog/CatalogContracts.java、IA/stock/StockContracts.java、IA/owner/OwnerContracts.java、IA/returns/ReturnContracts.java、IA/demand/DemandPlanContracts.java、IA/production/ProductionContracts.java；Create IC/InventoryServices.java、IC/DefaultInventoryServices.java、IC/InventoryClientTransport.java、IC/InventoryCallExecutor.java、IC/InventoryCallOptions.java及附件§2.2列出的每个业务Service接口；Modify IC/InventoryReservationClient.java、IC/InventoryConsole.java、IC/InventoryProjection.java、现有Inventory*Api.java；Move公开预留DTO到 IA/reservation/ReservationContracts.java，保留旧DTO兼容转换。

**Interfaces:** Consumes Task1 resolver；Produces附件全部值对象和类型化Service接口；`DefaultInventoryServices.create(InventoryCallOptions): InventoryServices`；`InventoryCallExecutor.invoke(String capability,Supplier<T>): T`。Options字段为resolver、凭证Supplier、connect/readTimeout、breakerThreshold/openDuration；默认沿用500ms/2000ms/5次/10秒，宿主可覆盖。

- [ ] 1. 写 `InventoryServicesCompatibilityTest`、`InventoryContractParityTest`、`InventoryUnknownOutcomeTest`：旧SDK与新SDK请求兼容；金样manifest摘要逐字相同；0.125000准确，0.0000001拒绝；旧整数协议拒绝1.5；回包丢失不自动再POST；查commands的404仍是未知执行结果。
- [ ] 2. 运行 `mvn -f backend/pom.xml -pl inventory/inventory-svc -am -Dtest=InventoryServicesCompatibilityTest,InventoryContractParityTest,InventoryUnknownOutcomeTest -Dsurefire.failIfNoSpecifiedTests=false test`，应因新类型/行为未实现而失败。
- [ ] 3. 按附件实现模型不变量、SDK及兼容委派；SourceStock保留原哈希规则；按能力拆分接口，不把HTTP传输、JSON或通用Map暴露给行业代码。尚未部署的服务能力明确不可用，不返回假成功。
- [ ] 4. 重跑测试并运行 `node scripts/check-inventory-independence.mjs`；检查API/client无domain或shop-base依赖。
- [ ] 5. 提交 `refactor(inventory): introduce typed services and shared invocation core`。

## Task 3: 路由元数据与事务内单写保护

**Files:** Create IS/api/SystemOwnerController.java、IS/api/ServiceCapabilitiesController.java、ID/service/OwnerRouteService.java、ID/service/impl/OwnerRouteServiceImpl.java、scripts/verify-inventory-integration-mysql.sh；Modify IG/InventoryAuthFilter.java、ID/entity/InvOwner.java、ID/service/impl/WriteAuthorityFlipServiceImpl.java、ID/service/impl/OwnerWriteGuard.java、ID/service/impl/ServicePlatformServiceImpl.java；Create V26迁移；Test inventory-svc/.../OwnerRouteContractTest.java、inventory-domain/.../OwnerWriteFenceNativeMysqlTest.java。

**Interfaces:** Produces A01–A03；`OwnerRouteService.find(String systemCode,String ownerRef): Optional<OwnerRoute>`；`findAll(String systemCode,List<String> ownerRefs): List<OwnerRoute>`；写闸提供事务内 `require(String ownerId,Writer writer,Long expectedRevision): void`，并确保本事务持有owner锁。

- [ ] 1. 写测试：SHOP凭证不能读SHAREHUB路由；EMBEDDED/FLIPPING路由只可读元信息，不能据此写；旧revision被拒；同一原单先拿到路由后发生flip也不能旁路落账。原生测试设置双连接屏障，不用sleep猜并发。
- [ ] 2. 运行Task1形式的Maven定向命令，测试类换为OwnerRouteContractTest/OwnerWriteFenceNativeMysqlTest，确认失败。
- [ ] 3. 实现V26与受限路由口。写操作先按ownerId有序锁owner，再沿用原head/line锁序；flip以排他锁变更authority及revision，不能只依赖固定2秒等待。对账覆盖余额、流水、预留分配与留存，不仅在手量。
- [ ] 4. H2合同测试和原生MySQL并发测试均通过；旧无revision请求仍实时检查单写；所有写入口包括直接过账、claim/留存均列入覆盖清单。
- [ ] 5. 提交 `feat(inventory): expose scoped owner routes and fence writer transitions`。

## Task 4: 主数据统一快照发布与远程同步

**Files:** Create PC/service/ProductInventorySnapshotService.java、PC/service/impl/ProductInventorySnapshotServiceImpl.java、PC/entity/ProductInventoryProjectionState.java、PC/mapper/ProductInventoryProjectionStateMapper.java；Modify PC/service/impl/MerchantGoodsServiceImpl.java、SkuIdentityServiceImpl.java、InvManagedServiceImpl.java、SB/spi/product/ProductEvents.java；Create ID/entity/InvSourceMasterRevision.java、ID/mapper/SourceMasterRevisionMapper.java、ID/service/MaterialSnapshotService.java、ID/service/impl/MaterialSnapshotServiceImpl.java、IS/api/MaterialSnapshotController.java；Modify BR/InventoryItemProjectionConsumer.java、InventoryInvModeConsumer.java、InventoryOnSaleConsumer.java、InventorySkuRetiredConsumer.java；Create库存V27、平台V622（以实际空闲版本为准）。

**Interfaces:** Produces A04/A05、`ProductInventorySnapshotService.snapshot(String tenantNo,String entityNo,String skuNo): MaterialSnapshotCommand`；原商品事务内推进完整revision并发事件，远程调用在该事务提交后；原事件消费者作为兼容翻译。

- [ ] 1. 写 `MaterialSnapshotNativeMysqlTest`、`ProductInventorySnapshotTest`：新建SKU可建远端物料；revision10后9不覆盖；10同hash重放、异hash409；两个market共用一个SKU投影流；旧上下架事件不复活已退休物料；有流水改单位被拒。
- [ ] 2. 对shop-app reactor运行上述类，确认RED；先构建相关模块，不使用过期本地snapshot依赖绕过编译。
- [ ] 3. 实现附件§4两张表及全量快照事件；版本记录/物料更新/command在inv同一事务。SKU停用的在途校验、真实人工确认仍留原商品业务，发布器不取代这些规则。
- [ ] 4. 同键并发、乱序、交易回滚时不发布、老事件升级和多market测试全部通过；对已有源快照只重放，不分配随机新业务指令。
- [ ] 5. 提交 `feat(inventory): synchronize versioned product master snapshots`。

## Task 5: 将原单处置规则迁入独立库存合同

**Files:** Create ID/service/OriginalStockDispositionService.java、ID/service/impl/OriginalStockDispositionServiceImpl.java、IS/api/OriginalStockController.java、SI/integration/OriginalStockContractMapper.java；Modify SI/service/impl/ReservationDispositionServiceImpl.java、BR/port/OriginalStockDispositionPort.java；Test inventory-svc/.../OriginalStockContractNativeMysqlTest.java、shop-inventory/.../OriginalStockContractMapperTest.java。

**Interfaces:** Produces A08–A12及附件InventoryOriginalService；兼容商城 `StockDispositionPort` 的reserveOriginal/observeReservation/releaseOriginal/confirmOriginal/observeOriginal。Native规则落inventory-domain，商城SPI只映射。

- [ ] 1. 测试完整原单reserve→confirm→query；缺行/换来源门店/改fulfillment/manifest不一致、相同SKU的新映射、错误lifecycle均不能完成；UNKNOWN不变APPLIED；历史摘要金样不变。
- [ ] 2. 运行OriginalStockContractNativeMysqlTest和Mapper测试，确认新远程合同缺失的RED。
- [ ] 3. 提取现有处置规则并使用Task3写闸；服务端从凭证约束owner，不能凭正文original.entityNo跨账。对于DUAL保持平台镜像编排，库存只返回自身真实证据。
- [ ] 4. 复跑并核对旧本地回归、跨店隔离、HTTP响应类型和错误映射。
- [ ] 5. 提交 `feat(inventory): provide source-bound original disposition APIs`。

## Task 6: 退货收货与冻结原单回补统一入口

**Files:** Create IS/api/ReturnReceiptController.java、IS/api/OriginalRestoreController.java、ID/service/InventoryReturnApplicationService.java、ID/service/impl/InventoryReturnApplicationServiceImpl.java；Modify ID/service/impl/OriginalReservationRestoreServiceImpl.java、ReturnReceiptServiceImpl.java、InboundServiceImpl.java、StockPostingServiceImpl.java；Modify BR/port/InventoryStockPort.java、BR/port/OriginalStockReturnPortImpl.java、BR/OriginalInventoryRestoreAdapter.java、BR/OriginalMirrorRestore.java；Create SI/integration/InventoryReturnContractMapper.java。

**Interfaces:** Produces A13–A16；方法签名以附件InventoryReturnService为准；复用purposeNo/receiptNo与原claim，不能再造“退款加库存”通用接口。

- [ ] 1. 写 `InventoryReturnNativeMysqlTest`：切SVC后的原单退款和售后收货真实成功；售后/退款交错不能超过原出库；不同质量不能重放成同请求；重复claim无重复ledger；原单当前映射变化不影响旧来源；FLIPPING时本地恢复被拒。
- [ ] 2. 写 `InventoryReturnLostResponseTest`：服务提交后丢响应，原号查回返回同一回执；首次GET/POST查询没记录不能认定回补成功。运行两类确认RED。
- [ ] 3. 实现原单来源绑定、事务内单写保护和查回；删除SVC分支的“永久CONFLICT”占位，改为真实远程调用。保留未迁移业主显式过渡适配，但远端故障禁止回退。
- [ ] 4. 原生并发累计上限、真实质量去向、原冻结所有者及DUAL边界测试通过；网络调用不在平台/库存写事务内等待。
- [ ] 5. 提交 `fix(inventory): route source-bound returns through standalone service`。

## Task 7: 美业来源/授权证据回查合同

**Files:** Create IA/evidence/ExecutionEvidenceContracts.java、ID/spi/ExecutionEvidencePort.java、IS/evidence/PlatformExecutionEvidenceClient.java、IS/config/ExecutionEvidenceClientConfiguration.java；Create backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalInventoryExecutionEvidenceController.java、BR/ExecutionEvidenceSnapshotMapper.java；复用SB/spi/trade/ServiceExecutionEvidencePort及既有意图/镜像/封存来源。

**Interfaces:** Produces A24；`ExecutionEvidencePort.query(ExecutionEvidenceQuery): ExecutionEvidenceSnapshot`；query含完整原引用、组件、委托及动作目的。返回附件§3.2的完整证明，不是布尔授权结果。IS通过resolver逻辑名PLATFORM与独立服务token调用；库存domain只依赖中立Port。

- [ ] 1. 写 `ExecutionEvidenceDelegationTest`、`ExecutionEvidenceTransportTest`：仅actor字段不能授权；错误realm/tenant/subjectKind、自行指定其他人、当前授权撤销、缺已接受意图都拒绝；缺PLATFORM适配时能力不可用；所有远程回查发生在inv事务外。
- [ ] 2. 运行测试确认RED，验证既有HUMAN SELF校验实际被调用，而非mock布尔true。
- [ ] 3. 将现有证据查询的字段一一映射；复用BoundDelegatedCalls/DelegatedIdentityContext现有受限委托语义，别在库存模块引入shop-auth依赖。来源状态变化时按旧保守规则重查/保持PENDING；不声称快照提供跨服务原子事务。
- [ ] 4. 新合同测试与原授权测试通过；库存独立性守卫通过。
- [ ] 5. 提交 `feat(inventory): add verified execution evidence integration`。

## Task 8: 美业执行留存远程化

**Files:** Create ID/service/ExecutionRetentionService.java、ID/service/impl/ExecutionRetentionServiceImpl.java、IS/api/ExecutionRetentionController.java、SI/integration/ExecutionRetentionContractMapper.java；Modify SI/service/impl/ReservationExecutionRetentionServiceImpl.java、SI/port/InventoryExecutionStockRetentionPortImpl.java、BR/ExecutionStockRetentionCoordinator.java、BR/ExecutionStockRetentionRecoveryJob.java、BR/port/ExecutionStockRetentionPortImpl.java。

**Interfaces:** Produces A17–A21；保留现有5种组件动作及完整ComponentReceipt/Proof合同；源授权取Task7，数量与留存事务使用Task3，错误码在宿主边界翻译。

- [ ] 1. 写 `ExecutionRetentionRemoteNativeMysqlTest`：准备/留存/实际耗用确认/无生效释放/已接受继续的金样结果与原实现相同；不存在的journal不能当实际消耗；DUAL镜像证明缺失保持PENDING；超时与重复请求不多扣。
- [ ] 2. 运行原生测试RED；用现有NativeInventoryExecutionStockRetentionIT作为行为对照，不能把其条件跳过计为通过。
- [ ] 3. 迁移库存原生规则，保留平台HOST聚合、当前人授权与原执行编排；保留V17触发器、schema verifier与哈希。不会在库存服务创建美业预约、资源或权益实体。
- [ ] 4. 原始authority/intent/component/hash逐项比对通过，跨商家/改component/过期授权不能创建留存。
- [ ] 5. 提交 `feat(inventory): support remote execution stock retention`。

## Task 9: 物料、需求计划与生产扣料的中立服务

**Files:** Create ID/service/DemandPlanService.java、ID/service/ProductionOperationService.java、ID/service/impl/DemandPlanServiceImpl.java、ID/service/impl/ProductionOperationServiceImpl.java；Create IS/api/DemandPlanController.java、ProductionOperationController.java；Modify SI/port/InventoryDemandPlanPortImpl.java、InventoryProductionPortImpl.java、MaterialCatalogQueryPortImpl.java，新增SI/integration/InventoryDemandContractMapper.java、InventoryProductionContractMapper.java；原InternalMaterialCatalogController及行业内部API保留兼容。

**Interfaces:** Produces A05/A22/A23；业务方法集合和DTO字段完全对应附件，明确issue=delta、issueTo=累计目标、reverse必须带originalOperationRef。不发明新的餐饮扣库存来源。

- [ ] 1. 写 `DemandPlanRemoteNativeMysqlTest`、`ProductionRemoteNativeMysqlTest`：0.125000物料精确守恒；取消先到保留墓碑；同leaf的累计目标重放不多扣；已撤销操作不能再次反冲；单位/definitionHash/recipeRevision不一致拒绝；query不产生日志单据。
- [ ] 2. Maven inventory-svc/shop-app reactor定向测试，确认RED。
- [ ] 3. 把仅属库存的SQL/规则移动到ID，平台/行业SPI仅作映射；保留原价估值未知、币种与批次语义。来源Recipe/菜单仍在原行业，不向库存搬食谱业务。
- [ ] 4. 原生测试与既有需求/生产行为测试通过；餐饮本地主账数量保持不变。
- [ ] 5. 提交 `feat(inventory): expose precise demand and production services`。

## Task 10: 库存后台、桥接任务与事件回写收敛

**Files:** Create IA/operations/InventoryOperationsContracts.java、IA/events/InventoryEventContracts.java、IC/InventoryOperationsService.java、InventoryEventService.java、IS/api/OfflineSaleController.java、InventoryOperationsController.java及ID对应应用Service；Modify现有IS/api/Console*Controller.java、EventController.java，SI/api/biz/*.java、SI/api/ops/*.java、SI/api/open/OpenInventoryController.java、SI/route/InventoryConsoleRoute.java；Modify BR/OfflineSaleService.java、StockAlignmentService.java、StockSyncAppService.java、StockReconcileService.java、InventoryWritebackService.java、InventoryOverviewQuery.java、InventoryHealthService.java、InventoryJobHandlers.java、PlatformInventoryEventSink.java。

**Interfaces:** SDK补充 `operations()`、`events()`，路径和方法见附件补充A25–A29。后台48端点旧路径/权限保持，由类型化Service取代任意URI转发；事件使用既有GET /events与POST /event-acks。

- [ ] 1. 写 `InventoryConsoleTypedRoutingTest`、`InventoryOperationsRemoteTest`、`InventoryEventWritebackRecoveryTest`：只管部分门店的员工不能跨库位；线下卖出/撤回保留原单；盘点同步不会重复调整；服务事件落平台持久记录后再ACK，平台提交后ACK前崩溃可重拉且不重复回写。
- [ ] 2. 跑相关模块定向测试RED，库存原生单据断言查ledger，不能只mock客户端调用次数。
- [ ] 3. 类型化封装已有后台/事件API；将线下销售等库存原生单据事务放服务端；平台商品配额/发布/对账展示仍留平台。复用inv_event_subscription及平台已有事件去重/Outbox，发现缺唯一保护先补真实约束再接入，不新增第二套库存事件账。
- [ ] 4. 建立 `docs/technical/design/schema/inventory-integration-call-sites.json`，逐项列出所有BR/SI本地依赖的迁移目标或显式过渡理由；每项有相应测试或门禁。服务模式不得加载未迁移的本地任务。
- [ ] 5. 提交 `refactor(inventory): unify console operations and event writeback`。

## Task 11: 配置、Bean边界与纯远程模式

**Files:** Create SI/integration/InventoryIntegrationProperties.java、InventoryIntegrationConfiguration.java、InventoryContractMappers.java，Create SI/embed/EmbeddedInventoryBridgeConfiguration.java；Modify BR/InventoryReservationConfiguration.java、BR/port/InventoryStockPort.java、SI/config/ConditionalOnInventory.java、SI/embed/InventoryEmbeddingConfiguration.java、backend/shop-app/src/main/resources/application.yml、shop-app pom与shop-inventory pom；Test shop-app/.../InventoryServiceOnlyContextTest.java、InventoryIntegrationConfigurationTest.java。

**Interfaces:** `shop.inventory.enabled`保留功能门控；新增 `shop.inventory.integration.mode=EMBEDDED|TRANSITION|SERVICE`、`shop.inventory.service.resolution-mode=DIRECT|DISCOVERY`；缺省TRANSITION兼容现有部署，尚无新配置时保持原装配；SERVICE时显式禁用库存domain自动装配并不创建invDataSource/Flyway/Mapper。新增配置Properties以类型化校验承载，旧base-url/app-key/app-secret继续读取。

- [ ] 1. 测试SERVICE完全缺库存DB配置、没有本地域Bean仍启动；EMBEDDED/TRANSITION既有行为不变；新旧寻址冲突/无发现适配器/缺凭证明确失败；功能开启但服务未就绪不能假装可下单。
- [ ] 2. 定向启动测试RED；若既有H2主库schema不兼容，使用有明确fixture覆盖的最小上下文，并另安排原生全上下文验收，不能排除失败后声称全集成通过。
- [ ] 3. 将所有local Mapper/领域Service构造依赖收进EMBEDDED/TRANSITION专用配置；服务Bean只依赖SDK和平台自身SPI。物理写入方解析不再使用商城本地InventoryAclService。
- [ ] 4. 编译实际各集成profile并跑纯服务与过渡上下文；架构规则阻止SERVICE Bean依赖inventory-domain，排除规则仅限明确的过渡包，不整模块豁免。
- [ ] 5. 提交 `refactor(inventory): separate remote integration from embedded wiring`。

## Task 12: 餐饮镜像与三行业接入验证

**Files:** Modify SI/service/impl/FoodStockProjectionServiceImpl.java、SI/service/FoodStockProjectionQueryService.java、backend-plugins/shop-industry-food/food-channel/src/main/java/ai/neargo/shop/food/channel/config/FoodInventoryClientConfiguration.java及同模块channel/port下已有CoreInventoryDemandPlanPortImpl.java、CoreInventoryProductionPortImpl.java、CoreFoodStockProjectionPortImpl.java；复用IA/projection/StockProjectionContracts.java与现有StockProjectionController.java；Create shop-app/.../UnifiedInventoryIndustryFlowTest.java、inventory-svc/.../UnifiedInventoryIndustryNativeMysqlTest.java；更新行业接口对应文档。

**Interfaces:** 餐饮镜像仅调用 `InventoryServices.projections()`，旧InventoryProjection兼容；美业使用Task8，零售使用Task4/5/6，原行业SPI保持。

- [ ] 1. 测试餐饮generation启停、旧代次晚到、原event重复、源版本乱序、receipt查回；对比执行前后餐饮本地余额；断言镜像写入不增加inv_ledger或改变inv_stock_balance。
- [ ] 2. 三行业同一实例下设置相同外部编号但不同凭证系统/业主，测试隔离；既有SHOP内不把RETAIL/BEAUTY/FOOD随意新增成三个库存凭证系统，使用原SHOP与业主/来源身份。
- [ ] 3. 用统一SDK替换分散客户端装配，保留独立行业进程→PLATFORM既有认证/来源编排；不为“统一入口”绕开当前权限，也不要求每个行业持跨商家凭证。
- [ ] 4. 三行业原生验证无跳过，旧API兼容与SOURCE/hash金样通过；运行 `node scripts/check-inventory-independence.mjs`。
- [ ] 5. 提交 `test(inventory): verify unified retail beauty and food integration`。

## Task 13: 构件、自动验证与受控发布方案

**Files:** Modify backend/inventory/inventory-svc/pom.xml、scripts/deploy-backend.sh、deploy/tencent/systemd/neargo-inventory@.service、backend/inventory/deploy/inventory.env.example、scripts/verify-inventory-integration-mysql.sh（Task3已建）；Create scripts/deploy-inventory.sh、scripts/tests/inventory-artifact.test.mjs、docs/technical/独立库存统一接入-部署与回滚.md。

**Interfaces:** `scripts/verify-inventory-integration-mysql.sh`读取 `INVENTORY_NATIVE_URL/USER/PASSWORD`，仅在其拥有的随机验证库执行，缺变量直接非零退出；设定 `INVENTORY_INTEGRATION_NATIVE=true` 以确保测试未跳过。脚本映射既有投影测试所需环境名，不在文档/日志写真实密钥。`scripts/deploy-inventory.sh`支持REF、DRY、明确实例参数和回滚。

- [ ] 1. 写构件检查：唯一*-exec.jar的Start-Class必须为InventoryApplication，普通依赖jar不能含BOOT-INF重包结构；库存服务仍能作为端到端测试依赖。现状重复repackage应使测试RED。
- [ ] 2. 修复Maven execution id，避免父子两个repackage执行；发布脚本核对SHA/摘要、锁、版本化包、原子链接、环境文件、权限、健康与失败回退；临时包清理不误删其他部署。
- [ ] 3. 执行：`mvn -f backend/pom.xml -pl inventory/inventory-svc -am clean verify`，`node --test scripts/tests/inventory-artifact.test.mjs`，`scripts/verify-inventory-integration-mysql.sh`；输出真实通过/失败/跳过数，原生必测项跳过即失败。直连两进程端到端还须证明“提交后丢响应→换实例→同号查回”。
- [ ] 4. 编写按服务端→宿主→隔离场景→业主对账的发布操作；云端当前空neargo_inv不能直接接管ai_shop_inv的历史。先部署新API，再启用新SDK相关能力；没有相关来源/凭证的能力明确不可用。记录实际Nacos联调未实施。
- [ ] 5. 只在用户授权的目标执行部署；不得因测试完成自动全量flip。提交 `build(inventory): verify standalone artifacts and controlled rollout`。

## 验收矩阵与交付证据

| 目标 | 拥有任务 | 必需证据 |
|---|---|---|
| 当前API兼容、未来寻址替换 | 1、2、13 | 原客户端合同 + 两地址发现替身 + 无fallback |
| 合理业务实体/无重复行业余额 | 2、5、8、9 | 模型验证、依赖守卫、原哈希金样、DTO不含Entity |
| 库存权威不变 | 5、6、8、9、12 | 原冻结来源、原单回补、美业证明、本地餐饮余额对比 |
| 单写与未知结果恢复 | 3、6、13 | 真MySQL并发 + 响应丢失查回 + 不重复流水 |
| 主数据乱序与兼容事件 | 4 | 多market、旧新事件、源version/hash冲突 |
| 纯服务商城与48后台入口 | 10、11 | 无invDB上下文 + 权限/范围测试 + 调用点清单 |
| 新增表与迁移 | 3、4、13 | V26/V27/V622顺延后真实版本、失败恢复、约束验证 |
| 可审计交付 | 13 | 构件入口、SHA/摘要、测试计数、云端健康与回滚记录 |

实施前的方案检查已确认：本轮不编写产品代码、不执行SQL、不改云端配置；上述“通过”均为实施后的验收要求，不是当前验证结论。推荐当前会话逐任务实施，接口高度共享，按依赖顺序能减少并行冲突；执行方法由用户选择。
