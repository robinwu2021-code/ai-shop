# 门店商业订阅模块开发计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付可整体移植的Plan、平台商城商品／订单、门店权益、认证接入与到期提醒模块。

**Architecture:** `backend/commerce`包含JDK契约／内核、独立持久化和Spring接入四个模块。宿主通过commercebridge提供身份、门店、支付、库存、通知、审计及Job，不反向耦合商业内核。首期嵌入现有应用，共享本地数据源事务；服务化不在本期。

**Tech Stack:** 当前项目Java21、Spring Boot4.0.x、MyBatis/JDBC、MySQL、Flyway、现有Maven与测试体系；B端Vue／uni-app、运营端现有Next.js与组件2.0。实际依赖小版本在T01从当前有效POM核对锁定，不升级框架。

**Spec:** [门店商业订阅整体设计](../specs/2026-11-03-commerce-portable-design.md)。细则另读 [原业务设计](../specs/2026-11-03-industry-plan-mall-design.md)、[数据库设计](../../technical/design/门店行业Plan商城-数据库设计.md)、[API清单](../../api/门店行业Plan商城-API清单.md)。

状态：**未执行计划**。下面命令是实施时的验证入口，不代表本轮已运行或已通过产品测试。不存在的目标文件均标为Create；正式迁移及实现需要独立开发变更。

## Global Constraints

- 源码根目录 `backend/commerce/`、Java根包 `ai.neargo.commerce`；api只依赖JDK，core只依赖api及JDK。
- commerce不得依赖shop-base、shop-core、shop-merchant、shop-base-auth、pay-domain或宿主ThreadLocal／实体／异常；宿主依赖公开API和接入层。
- 每类型FREE／PRO／CHAIN恰三档；商业权益只控制整个业务端，端内岗位权限不替代。
- 同门店同code所有有限来源时长累计；Plan固定周期，独立端包保留已用历史和未用量，禁止重排到过去；FREE永久不入有限队列。
- 一单一家门店；套餐最多一个基础Plan组件；升级仅一个新Plan周期；赠送0折价基数，硬件不使用订阅抵扣。
- 支付、库存、通知外部调用不在持有商业数据库锁的事务中等待。未知结果查原意图，不盲建新单。
- 全部数字来源、排期、抵扣和outbox成组本地原子交付；所有数字订单共用门店占用。
- 账号token不保存Plan或当前门店；受保护请求逐次核验时间／权威版本；关键写事务再次复核。商业过期不返回身份401。
- 提醒默认D7=168h、D3=72h、D1=24h、D0=实际到期；15分钟扫描，D0外推送补发窗口24h，均为版本化配置默认。
- 提醒自动投递最多5次（含首次），重试间隔1／5／15／60分钟；租约120秒、调用超时10秒；UNKNOWN先查原回执，不能盲重发。
- 新数据边界33项候选表，原真实资金、库存、账号和旧主体合同真源不迁入新模块；自有Flyway位置和commerce_schema_history独立。
- 本地库内id自增、外部业务号独立生成；金额为最小货币单位整数，时间为UTC毫秒，区间 `[start,end)`。

## Review Focus

1. 无权X-Store-No被宿主回落默认店：必须拒绝新写及端签发，而不是成功落到另一店（T05）。
2. 商业撤销与已缓存账号撤销不是同一种一致性：关键写复核真实账号／成员状态，不以轮询缓存宣称立即失权（T05）。
3. 续费推迟连续区间终点、无关权益版本变化、提醒发送与续费并发：不误报、不重复，同步投递不承诺可撤回（T10）。
4. 硬件混合套餐、未来周期和抵扣退款：金额不复制，硬件不吃余额，旧周期与恢复余额不能双补（T06/T08/T09）。
5. 从宿主剥离后仍残留父POM、BaseEntity、异常、SpringContext或跨库JOIN依赖：独立构建与第二宿主契约测试必须抓到（T01/T02/T13）。

## 目录与主要文件责任

| 位置 | 文件／子包 | 责任 |
|---|---|---|
| `backend/commerce/` | `pom.xml`及四子模块POM | 自包含构建、版本与依赖方向 |
| `commerce-api/.../api/common/` | `ActorContext.java`、`StoreRef.java`、`Money.java`、`CommerceError.java` | 中立上下文、金额、错误；不暴露宿主类型 |
| `commerce-api/.../api/` | `catalog/CatalogFacade.java`、`mall/MallFacade.java`、`fulfillment/FulfillmentFacade.java`、`subscription/SubscriptionFacade.java`、`access/EndAccessFacade.java`、`reminder/ReminderFacade.java` | 六个公开门面及对应功能包中的命令／结果record |
| `commerce-api/.../api/port/` | StoreDirectory、ActorAuthorization、PlatformPayment、HardwareInventory、Notification、Audit、IdGenerator、EndContextCodec、CommerceEventPersistence各Port | 可替换宿主边界 |
| `commerce-core/.../` | 对应catalog／quote／order／fulfillment／subscription／access／conversion／credit／refund／reminder／migration包 | 规则、用例、仓储接口；按功能维护 |
| `commerce-core/.../spi/` | `CommerceUnitOfWork.java`、`CommerceEventStore.java` | 本地原子性与可靠事件内部合同 |
| `commerce-persistence/.../` | 各功能`*Record.java`、`*Mapper.java`、`*Repository.java`、`JdbcCommerceUnitOfWork.java` | 自有表与行锁，8个通用字段自己定义 |
| `commerce-persistence/src/main/resources/db/commerce/migration/` | `V1__commerce_catalog.sql`等按T02分批 | 独立历史表，不占宿主V序列 |
| `commerce-spring-boot-starter/.../` | `CommerceAutoConfiguration.java`、web／security／job子包 | 框架装配、端证明、HTTP、Job门面 |
| `backend/shop-app/.../commercebridge/` | `ShopCommerce*Adapter.java`、`CommerceHostConfiguration.java` | 宿主模型和真实服务的唯一适配入口 |

上表中的 `...`：模块源码根下 `src/main/java/ai/neargo/commerce/`；宿主为 `src/main/java/ai/neargo/shop/`。每任务再给出精确目标包与测试名。DTO按各功能单独文件定义，不创建包含所有业务类型的巨型公共类。

## 顺序与里程碑

| 里程碑 | 任务 | 可验收结果 | 前置 |
|---|---|---|---|
| M1 可移植基础 | T01–T02 | 独立构建、独立迁移与事务 | 无 |
| M2 定义、来源与准入 | T03–T05 | 发布三档／SKU、FREE、累计排期、严格门店端认证 | M1 |
| M3 商业闭环 | T06–T09 | 报价订单、平台支付、数字／硬件交付、升级与退款 | M2 |
| M4 提醒与客户端接入 | T10–T11 | 持久化到期提醒、B／运营端状态闭环 | M3；T10纯规则可在M2后先写 |
| M5 存量与移植验收 | T12–T13 | 旧合同保护、灰度切换、第二宿主证明 | M3/M4 |

本计划不承诺未经团队排期的日期或工时；先按里程碑验收，不因前端原型可点就跳过资金／权限检查。

---

### T01：独立模块骨架与公开契约

**Files:** Create `backend/commerce/pom.xml`及四子模块POM；Create api的 `common/{ActorContext,StoreRef,Money,CommerceError}.java`、`port/*.java`与六个门面（含FulfillmentFacade）；Modify `backend/pom.xml`增加聚合模块及commerce版本管理；Test `commerce-api/src/test/java/ai/neargo/commerce/api/PortableApiContractTest.java`、`commerce-core/src/test/java/ai/neargo/commerce/architecture/CoreDependencyTest.java`。

**Interfaces:** 公开约定：`ActorContext(tenantNo, subjectKind, principalNo, accountSessionFingerprint)`、`StoreRef(tenantNo,storeNo)`、`Money(currency,minor)`。命令参数中actor仅来自可信接入层，不从客户端请求体反序列化。门面及Port签名在对应功能任务补齐，以api测试保持前后一致。

- [ ] 先写失败测试：api不得引用Spring／MyBatis／shop包；core不得引用这些类型及Servlet／SecurityContext。注入两个不同宿主的StoreDirectory／ActorAuthorization测试替身都能调用契约，不出现宿主DTO。
- [ ] 核对现有有效依赖版本，建立自包含commerce-parent；api运行时依赖树只包含JDK，core只有api。测试依赖不算生产依赖。
- [ ] 验证红→绿：`mvn -f backend/commerce/pom.xml -pl commerce-api,commerce-core -am test`；预期上述约束测试全过，无宿主业务模块出现在运行时依赖树。
- [ ] 评审并提交本任务；不在该提交混入业务实现或部署。

### T02：自有数据、迁移与事务适配

**Files:** Create `commerce-core/.../spi/CommerceUnitOfWork.java`；Create `commerce-persistence/src/main/java/ai/neargo/commerce/persistence/JdbcCommerceUnitOfWork.java`及按功能划分的records／repositories；Create接入层 `CommerceMigrationInitializer.java`，明确两个迁移初始化顺序；Create迁移 `V1__commerce_catalog.sql`、`V2__commerce_mall_trade.sql`、`V3__commerce_subscription_credit.sql`、`V4__commerce_reliability_notice.sql`；Test `CommerceSchemaMysqlTest.java`、`CommerceTransactionTest.java`、`CommerceMigrationIsolationTest.java`于persistence同名测试包。

**Interfaces:** `CommerceUnitOfWork.required(Supplier<T>)`；Repository所有读写带tenant，写用例通过UoW。权威版本读取与账户锁接口分别为 `readEntitlementVersion(StoreRef)`、`lockSubscriptionAccount(StoreRef)`，后者锁定查询直接返回最新AccountSnapshot，不能锁后用普通SELECT读旧快照。事件复用通过api的CommerceEventPersistencePort；实现必须加入同一主库事务。

- [ ] 先写失败测试：33项模型字段／唯一键按实际复用选型落地；来源重复、转换重复、事件重复被约束；事务中任何一步失败时余额、来源、排期及outbox一起回滚。
- [ ] 迁移启动测试覆盖宿主已有历史→commerce独立V1→后续宿主新V号仍正常执行；不修改宿主表，不跨库创建宿主门店外键。
- [ ] 实现自有持久化与迁移初始化；若选择复用库存／inbox／outbox，记录adapter及同事务条件，不能同时创建第二权威账。
- [ ] 验证：`mvn -f backend/commerce/pom.xml -pl commerce-persistence -am test`；原生MySQL集成环境使用专用测试库。未提供MySQL时该验收记未完成，不能以H2通过替代。
- [ ] 用 `python3 scripts/gen-industry-plan-mall-docs.py --check` 核对正式实现与设计变更；评审后提交。

### T03：经营类型、三档Plan和商城商品发布

**Files:** Create core的 `catalog/business/BusinessTypeService.java`、`catalog/end/BusinessEndService.java`、`catalog/plan/IndustryPublicationService.java`、`catalog/product/SkuPublicationService.java`；Create api/catalog相应命令与结果；Test `IndustryPublicationTest.java`、`SkuPublicationTest.java`、`CatalogWritePermissionTest.java`。

**Interfaces:** `CatalogFacade.publishIndustry(ActorContext, IndustryPublicationCommand)`→`PublicationResult`；命令含typeCode、三档版本号、候选SKU版本号及expectedCatalogVersion。`publishSku`仅引用已发布Plan，行业包内允许互相匹配的草稿同时发布。

- [ ] 先写失败测试：三档恰好FREE／PRO／CHAIN；FREE必要端、端验收、SYSTEM不可售、同code去重、高档超集；首次草稿发布无循环；失败全部回滚，已购版本不变。
- [ ] 测试类型名称／排序编辑与enabled发布权限分别校验；新建enabled=true不能绕过；套餐最多一个Plan组件，赠送分摊为0、天数／件数单位明确。
- [ ] 实现草稿、版本、发布与停售，保留正在锁价的原订单合同。
- [ ] 验证：`mvn -f backend/commerce/pom.xml -pl commerce-core -am -Dtest=IndustryPublicationTest,SkuPublicationTest,CatalogWritePermissionTest -Dsurefire.failIfNoSpecifiedTests=false test`；预期全过，再运行所属模块全部测试；评审提交。

### T04：门店初始化、来源和累计排期

**Files:** Create core的 `subscription/StoreProvisioningService.java`、`subscription/SubscriptionService.java`、`access/EndScheduleService.java`、`access/CoverageCalculator.java`；Modify宿主 `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/MerchantPortImpl.java` 的quickStart／建店接点，`backend/shop-merchant/pom.xml`仅增加commerce-api契约依赖；Create宿主门店字段迁移（实施时选择未占用V号）；Test `StoreProvisioningTest.java`、`EndScheduleTest.java`、`CoverageCalculatorTest.java`、宿主 `CommerceStoreCreationFlowTest.java`。

**Interfaces:** `SubscriptionFacade.initializeFree(actor, InitializeStoreCommand)`；`EndScheduleService.rebuild(StoreRef, Set<String> codes, String operationNo, Instant effectiveAt)`；`CoverageCalculator.evaluate(List<AccessSegment>, Instant)`返回CoverageView，含currentCoverage、mostRecentEndedCoverage、nextBoundaryAt及nextStartAt，D0不依赖当前一定有覆盖。

- [ ] 先写失败测试：建店和FREE同事务、重复建店不重发、原占位店类型不一致拒绝。POS原到12月1日＋30天到12月31日；后续Plan再续30天合计到次年1月30日。
- [ ] 测试端包30天用10后插入30天Plan，未来仍50天；每来源时长守恒、切点前不改、不回填过去；FREE永久不影响有限尾部；真实空档不能被最大end隐藏。
- [ ] 实现来源、分片和排期历史，宿主仅依赖api初始化。嵌入模式使用同一事务管理器；未来不同库模式不得沿用“建店与远程FREE原子”宣传。
- [ ] 初始化由可信宿主建店能力调用，当前事务能读到刚插入门店／owner；不能用另一个fresh连接要求未提交成员已可见，也不能把该能力开放成任意店号的赠权接口。
- [ ] 验证core相关测试及宿主场景：`mvn -f backend/pom.xml -pl shop-app -am -Dtest=CommerceStoreCreationFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`；评审提交。

### T05：严格门店认证、端上下文和持续准入

**Files:** Create api/access的 `AccessCheck.java`、`AccessDecision.java`、`EndContext.java`和port的 `EndContextCodecPort.java`；Create core/access的 `EndAccessService.java`；Create starter/security的 `CommerceRoutePolicy.java`、`CommerceEndContextService.java`、`CommerceAccessInterceptor.java`；Create宿主bridge的 `ShopCommerceActorAdapter.java`、`ShopCommerceStoreAdapter.java`、`ShopCommerceEndContextAdapter.java`；Create `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/AccountSessionVerifier.java`、`AccountSessionVerification.java`及`store/DbAccountSessionVerifier.java`；Modify `backend/shop-app/src/main/java/ai/neargo/shop/config/SecurityConfig.java`与`DbSessionConfig.java`、`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizContextFilter.java`及`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/BizIdentityResolverImpl.java`的严格选择接点；Test `EndAccessTest.java`、`EndContextBindingTest.java`、`FreshAccountSessionVerifierTest.java`、`CommerceAccessSecurityFlowTest.java`。

**Interfaces:** `EndAccessFacade.check(AccessCheck)`→`AccessDecision`；`requireForWrite(AccessCheck)`在实际写事务取得账户锁并现读状态。`issueEndContext`绑定当前账号session指纹、店、端、客户端、权益版本和边界；测试注入Clock，服务端时间不能来自客户端。

宿主 `AccountSessionVerifier.verifyFresh(String bearer, Instant now)`→`Optional<AccountSessionVerification>`，返回LoginUser、sessionFingerprint、expiresAt、verifiedAt，仅在宿主可见。DB实现直接查SessionDao.findByHash及未缓存IdentityLoader，不调用DbTokenStore.get；bridge转换为api中立类型。账号有效期沿原会话Clock转换，不能使用店铺显示时区。签发和关键写依赖fresh能力；DB生产适配缺失时拒绝启用付费写，memory限开发／测试，其他模式须另验等价实现。

- [ ] 先写失败测试：请求店≠解析店／路径店／证明店均拒绝；不回默认店；同user换账号session不能重放旧证明；签名／keyId／算法／aud错误拒绝；跨店迟到请求不改变当前店。
- [ ] 测试 `[start,end)` 精确边界、5分钟上下文与nextBoundary截断、无关版本变化需刷新、撤销后新请求不靠旧缓存继续放行、EXPIRED不变成身份401。
- [ ] 测试已买但岗位无权仍拒绝；免费BIZ_APP证明不能打POS专用路由；未登记保护路由默认拒绝；登录／开店／商城／续费／限定历史处理无循环门禁。
- [ ] 测试ops走既有operator身份和独立权限，不接受商家端证明；支付回调／Job使用可信内部身份，不要求买家在线；请求体伪造SYSTEM无效。
- [ ] 测试关键写与商业权益撤销共用账户锁，锁定查询直接读新版本；宿主账号／成员／治理变更以fresh复核通过为接纳点，复核前已提交的失效拒绝，之后才发生的变更允许此前合法接纳短事务完成，不宣称跨宿主所有写者共同锁到提交。
- [ ] 在账号缓存仍命中时，DB fresh必须发现已撤销会话／停用身份并返回真实expiresAt；缺fresh实现拒绝启用。测试长事务已有快照也不读出旧账号／成员状态，raw bearer不进入commerce DTO／日志。
- [ ] 实现并验证core测试和 `mvn -f backend/pom.xml -pl shop-app -am -Dtest=CommerceAccessSecurityFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`；评审提交。

### T06：报价、订单和金额／占用

**Files:** Create core的 `quote/CommerceQuoteService.java`、`order/MallOrderService.java`、`credit/CreditReservationService.java`；Create api/mall的命令／视图；Test `CommerceQuoteTest.java`、`MallOrderConcurrencyTest.java`、`ComponentAllocationTest.java`。

**Interfaces:** `MallFacade.quote(actor, QuoteCommand)`→`QuoteView`；`placeOrder(actor, PlaceOrderCommand)`只接受quoteNo和必要收货信息，金额从报价取得；quote保存策略与权益／余额版本。一个数字订单一门店占用，所有数字组件完成前不释放。

- [ ] 先写失败测试：同幂等键同参数同单、异参数冲突；过期／变更报价拒绝；两张数字订单不能并发结算；纯硬件单不占数字排期。
- [ ] 测试订单＝行＝组件＝周期金额合计，尾差固定；多周期不复制金额；混合硬件抵扣份额为0；历史抵扣批次按预留顺序消费。
- [ ] 实现订单状态和报价快照；外部库存预占以原组件号幂等引用，经可靠事件处理并可查询，结果未知时不推进收款或假定释放。支付前确认可售／库存合同。
- [ ] 验证所属core测试全部通过，并覆盖两个独立数据库事务／进程争同一门店的MySQL用例；评审提交。

### T07：平台收款、事实入站与硬件协调

**Files:** Create core的 `order/PaymentCoordinationService.java`、`shipping/HardwareShipmentService.java`；Create宿主bridge的 `ShopCommercePaymentAdapter.java`、`ShopCommerceInventoryAdapter.java`；Create starter web/biz商城订单与支付接入；Test `PlatformPaymentContractTest.java`、`CommercePaymentRecoveryTest.java`、`HardwareShipmentTest.java`。

**Interfaces:** `PlatformPaymentPort.create/query/close/refund`按orderNo／refundNo稳定幂等；`FulfillmentFacade.confirmPayment(VerifiedPaymentFact)`仅由可信入站adapter调用。库存按orderComponentNo查询／补偿，未知事实不伪造为成功或失败。

- [ ] 先写失败测试：平台收款主体错误／币种金额错误拒绝；前端paid=true不发权益；重复回调／查单只接受一份成功事实；多次收款异常进入退款，不二次交付。
- [ ] 测试支付未知占用不释放；确认关单后迟到矛盾付款转原路退款；有效截止前已付且仍占用可补交付。
- [ ] 测试硬件分包不能超发、库存结果未知可恢复、数字开通不依赖硬件签收。外部调用在本地事务之外，结果通过原意图号恢复。
- [ ] 验证core契约与宿主 `CommercePaymentRecoveryFlowTest`（Create于shop-app测试包）；评审提交。

### T08：数字交付、升级与抵扣转换

**Files:** Create core的 `fulfillment/DigitalFulfillmentService.java`、`conversion/PlanConversionService.java`、`credit/CreditLedgerService.java`；Test `DigitalFulfillmentIdempotencyTest.java`、`PlanConversionTest.java`、persistence的 `ConversionAtomicityMysqlTest.java`。

**Interfaces:** `DigitalFulfillmentService.fulfillOrder(String orderNo)`按原订单全部数字任务执行；`PlanConversionService.apply(String orderNo,String reservationNo)`只转换原报价锁定旧Plan来源。新的周期／端包排期使用T04接口。

- [ ] 先写失败测试：多组件／多周期中任一步失败全组回滚；重放无新增时长；仅数字组全部完成才释放占用。
- [ ] 测试365元／365天用165天折200元、升699元现金499元；未来周期按剩余全值；赠送0；可抵800升699留101余额；多次升级价值谱系可追溯。
- [ ] 测试新升级只有一周期、同源不能转换两次；独立端包余量只重排不抵扣；新Plan价值不含硬件现金。
- [ ] 实现账户→余额→来源→批次→任务的固定锁序，转换、账本、来源、排期、版本和outbox同事务；跑core与原生MySQL原子性／并发测试；评审提交。

### T09：退款、来源冻结与审计

**Files:** Create core的 `refund/CommerceRefundService.java`、`subscription/GrantRevocationService.java`；Create宿主 `ShopCommerceAuditAdapter.java`；Test `CommerceRefundTest.java`、`RefundConversionLineageTest.java`、`SourceRevocationTest.java`。

**Interfaces:** `previewRefund(actor, command)`→依赖版本／份额／现金／抵扣／来源影响；`approveRefund`及外部结果`applyRefundFact`持续同一refundNo。赠送／撤销先核验占用和版本，记录原因和操作号。

- [ ] 先写失败测试：原单可退上限、来源已使用／已转换分支、先冻结后协调、外部未知只查原退款、不重复恢复余额。
- [ ] 测试升级退款只退本单现金并恢复合法抵扣，不同时恢复旧Plan；已转换后继进入核算，不自动套现；撤销不自动意味着退款成功。
- [ ] 测试普通运营赠送基数0、同code累计、不能通过赠送不同档制造并行Plan；跨店调整拒绝，历史事实不能删除隐藏。
- [ ] 实现后验证core和宿主审计字段（操作者、原因、前后版本、关联号、结果）完整；评审提交。

### T10：门店业务端到期提醒

**Files:** Create core/reminder的 `ExpiryReminderService.java`、`ExpiryReminderPolicy.java`、`ReminderDeliveryService.java`；Create api/reminder的 `ReminderScanCommand.java`、`ScanRun.java`、`NoticeView.java`；Create persistence/reminder的 `CommerceExpiryNoticeRecord.java`及repository，对应T02创建的 `commerce_expiry_notice`（20业务字段＋8通用字段）；Create宿主 `ShopCommerceNotificationAdapter.java`与`ShopCommerceReminderJobHandler.java`；Create starter/job的 `CommerceReminderJob.java`（中立runner，不直接引用宿主Job API）；Modify宿主 `backend/shop-core/src/main/java/ai/neargo/shop/message/NotifyScene.java`及对应通知种子／守卫；Test `ExpiryReminderPolicyTest.java`、`ReminderDedupTest.java`、`ReminderDeliveryRecoveryTest.java`。

**Interfaces:** `ReminderScanCommand(ScanRun run, String afterCursor, int limit)`；`ScanRun(scanNo, scanNow, tenantNo, shardNo, shardCount, upperAccountId)`在api/reminder定义。`ReminderFacade.scan`分页生成候选并返回nextCursor／hasMore／数量；Job在本轮循环至upperAccountId。每轮固定scanNow，默认页100／上限500；每轮重置游标，崩溃从分片起点重扫，唯一notice不重复。单批提交成功后才前移，无仅按updated_at筛选，也不把内存cursor当持久水位。

`ReminderFacade.deliver(String noticeNo)`处理原收件人通道。`CommerceNotificationPort.send(NotificationCommand)`→`DeliveryOutcome`，状态为SENT／RETRYABLE／UNKNOWN／PERMANENT_FAILURE，携带providerMessageNo或错误码。Job默认10分钟上限／11分钟互斥租约；扫不完记失败／积压，配置分片保证每轮完整，不永久跳过尾部。

- [ ] 先写失败测试：Plan到12月1日但POS续到12月31日，只对后者生成端到期提醒；永久FREE无提醒；真实空档D0拒绝访问且未来nextStart不被合并；从未生成旧候选、首次扫描在到期后15分钟时，依mostRecentEndedCoverage正确生成D0。
- [ ] 测试D7／D3／D1／D0精确时点、15分钟迟扫、停机错多档只补一档、D0超过24h不外推送；阈值与窗口均从versioned policy获取。
- [ ] 测试多页固定scanNow／upperId、跨轮自然D7→D3、分页中新增账户、批失败／重启从头重扫及整轮超时；已持久候选继续独立投递，不因扫描游标丢失而丢消息。
- [ ] 测试同覆盖终点无关权益版本变化不重复；发送资格复核前已提交的续费使旧候选作废；收件人失去采购权不继续投递；资格复核后与外部接纳之间再续费存在在途消息窗口，不能假称绝对撤回。
- [ ] 测试每目标每通道幂等、站内SENT＋PUSH失败只重试PUSH、120秒租约崩溃恢复、10秒超时UNKNOWN查原回执；可重试失败最多5次并按1／5／15／60分钟，超限进入DEAD；候选与outbox同事务。
- [ ] 接入现有Job API和通知场景×受众×通道种子守卫；默认只配正式支持的站内／推送，短信关闭。验证core、持久层与 `CommerceExpiryReminderFlowTest` 宿主集成测试；评审提交。

### T11：HTTP合同与B／运营端接入

**Files:** Create starter/web的biz／ops控制器、配置与错误映射，`CommerceAutoConfiguration.java`、`CommerceProperties.java`及 `commerce-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；Modify `backend/shop-app/pom.xml`增加starter依赖并创建宿主CommerceHostConfiguration；Modify `b-app/src/api/{contract,endpoints}.ts`及门店store、登录／切店／home／plan／messages相关页；Modify `ops-web/lib/api/`与订阅商城视图；Modify `docs/api/门店行业Plan商城-API清单.md`、共享类型／枚举／权限真源。新增页面沿两端原型包，避免复制第二版UI。

**Interfaces:** 账号上下文与门店context／end-access返回serverNow、nextBoundaryAt、entitlementVersion和授权可见的临期摘要；端会话端点按T05签发；运营提醒查询／原notice重试按新增API合同。

- [ ] 先写失败测试：冷启动加载失败不当无店；切店generation丢弃旧响应；商业过期不清token；店员只见联系老板，采购金额和提醒列表不越权。
- [ ] 测试B端前台唤醒／时间边界刷新、提醒深链重新校验门店而不复用旧报价；运营通知记录查看／重试独立判权，UNKNOWN没有强制群发按钮。
- [ ] 按原型包实现新旧路由、权限、金额和状态。新增到期提示是P08/P11/P13/P18及消息的状态增强；运营提醒记录及详情作为原35画面之外的明确增量，不宣称原型基线已经覆盖。
- [ ] 验证各自既有type-check／测试／lint／构建命令及浏览器关键路径，遇框架代码修改先读各目录AGENTS和当前框架文档。将共享类型、OpenAPI与权限种子同步，评审提交。

### T12：旧合同兼容、模型切换和提醒去误报

**Files:** Create core/migration的 `LegacySubscriptionMigrationService.java`；Create宿主 `ShopCommerceLegacyPlanAdapter.java`；Modify旧 `BizPlanController`、`OpsMerchantPlanController`、`MerchantPlanServiceImpl`对应兼容接点；Test `LegacyPlanCutoverTest.java`、`MigrationValueAllocationTest.java`、`MigrationReminderSuppressionTest.java`。

**Interfaces:** `preview/validate/cutover`按migrationBatchNo＋旧合同＋store幂等；宿主commerce_model是权威切换开关，旧访问保护与新折价基数分开。

- [ ] 先写失败测试：一份旧合同跨店分摊总额不超历史值；无付款证据基数0且访问承诺保护；LIFE_SERVICE不自动当BEAUTY；旧宽限不被新规则吞掉。
- [ ] 测试切换后原旧写入口不能再续同一份权益；迁移已有新付款的门店不能简单回退旧表丢单；回滚保留新账和事件。
- [ ] 测试迁移形成同code连续区间时不重复D0，处于未确认映射的店不误发新模型到期催购；逐店部分失败重试不重发。
- [ ] 预发影子对照门店准入、连续到期、金额、端包余量和提醒候选；生成差异清单，全部解释后按批灰度；评审提交。

### T13：整体可移植验收与交付

**Files:** Create `backend/commerce/README.md`、`docs/technical/门店商业订阅-接入与运维.md`；Create `commerce-core/src/test/java/ai/neargo/commerce/portable/SecondHostContractTest.java`；Create宿主 `CommerceEndToEndFlowTest.java`、架构 `CommerceBoundaryTest.java`。

**Interfaces:** 独立宿主仅实现api Ports，不链接shop-domain实体；交付清单包含四模块、迁移、配置、路由策略、事件版本、fixtures与外部门店映射规则。

- [ ] 先写失败测试：第二宿主可运行FREE→购买→累计→升级→到期拒绝→提醒；不需要宿主父POM、Mapper、异常或静态Context。权限适配缺失时默认拒绝付费路由，不能装配成放行。
- [ ] 核对数据迁出包含全部来源、时间片、余额谱系、原订单、inbox/outbox幂等和提醒历史，不能只复制当前到期日。导入时校验tenant／外部门店映射与币种。
- [ ] 验证 `mvn -f backend/commerce/pom.xml verify`、宿主目标场景测试、原生MySQL并发测试、前端要求及schema文档守卫；随后运行仓库要求的全量检查，不跳过失败。
- [ ] 输出本地构建／集成／视觉证据和未完成项，核实真实环境的会话存储、提醒通道、平台收款账户及feature flag；在预发确认后才能另行安排生产启用。
- [ ] 完成代码／数据／权限／资金／提醒与可移植性复核后提交交付版本；部署和真实提醒不由本计划文档自动执行。

## 计划自检与交付门槛

- [ ] 每项整体设计要求能映射到T01–T13；原业务、原型、数据和接口真源同步，无“某份仍按32表”的现行口径。
- [ ] api DTO、Facade、Port和测试名跨任务保持一致；金额、时间、actor来源和交易状态只有一套定义。
- [ ] 将Review Focus五项落实到对应失败测试，并在真实事务／跨副本条件下验证，而不是仅有mock证明。
- [ ] 没有实际通过的模块、真实渠道、MySQL或UI情景明确写未验证，不把计划勾选当完成证据。
- [ ] 设计评审后再选择并执行实施方式；本轮仅交付可审阅设计及开发计划，不自行进入产品开发。
