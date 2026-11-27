# TDD-零售域对齐门店交易库

状态：已确认 Q1、Q2、Q3 · 第一段：交易句柄与 Mapper 归属已实现；商品包、商户域资源预约与门店分类、交易包、履约与求购报价的事务点名已实现；零售、渠道（主流程）、美业、餐饮插件已接；资源占用结构校验缺陷已修；其余子模块未做
档位：2 · 依据：数据库设计方案 `库/05-门店交易库.md`、`库/06-行业库.md` · 产出：本篇 + [对照表](零售域-旧表新表对照.md) + [ADR-068](ADR/ADR-068-门店交易库按耦合簇换表与行业插件公用部分并回.md)
关联需求（行为基线，本次不改需求）：[PRD-商品规格与发布](../requirements/PRD-商品规格与发布.md) · [PRD-品牌商品库与门店商品](../requirements/PRD-品牌商品库与门店商品.md) · [PRD-统一订单与开票资料](../requirements/PRD-统一订单与开票资料.md) · [PRD-统一销售渠道接入](../requirements/PRD-统一销售渠道接入.md) · [PRD-支付方式与预约预定](../requirements/PRD-支付方式与预约预定.md)
关联设计：[ADR-066 业务模块按配置选数据源](ADR/ADR-066-业务模块按配置选数据源.md) · [TDD-多数据源与发票打印对齐新库](TDD-多数据源与发票打印对齐新库.md) · [TDD-商户门店与员工权限对齐平台基础库](TDD-商户门店与员工权限对齐平台基础库.md) · [TDD-多行业统一业务架构-零售餐饮美业](TDD-多行业统一业务架构-零售餐饮美业.md)
设计方案位置：工作树 `ai-shop-slim` 的 `docs/technical/design/数据库设计/`、`schema/ddl/05-n_trade.sql`、`06-n_industry.sql`（未合入本仓库）
勘查底稿：会话笔记 `retail-survey-A-商品与目录.md`、`B-订单与售后.md`、`C-履约预约资源.md`、`D-渠道与插件.md`、`E-装配与跨域.md`（带类名与行号，本篇只引结论）
创建：2026-11-26 · 最后更新：2026-11-26

## 一句话

零售域对应门店交易库 `n_trade`（74 张表），现有代码里对应的旧表有 **224 张**，另有 22 张归行业库 `n_industry`，合计 246 张；
它们分散在 `shop-core` 的 trade / product / fulfillment / community 包、四个插件（餐饮、美业、零售、渠道）、`shop-merchant` 的资源预约、`shop-gateway` 的三个桥。
**这不是改名**：104 张要多表合一或行转列，50 张新库没有对应要先留原表，新库建表语句把代码依赖的唯一约束系统性降成了普通索引。
`n_trade` 内部高度耦合（下单一个事务里写目录锁量、订单、预约时段、渠道订单），和平台基础库一样**不能逐表换库**。
所以分两段：**第一段逐子模块「接句柄」**（不改表、每步可推送）；**第二段「换表」按耦合簇**，独立小簇先切，交易核心簇整体切。第二段开工前，设计里的字段缺陷必须先修。

## §0 对账一 · 需求 → 设计

本次是重构，验收是「对齐新库后行为不变」，外加切库带来的一致性要求。

| AC | 需求（一句话） | 落点 |
|---|---|---|
| R1 | 不绑定 `trade` 时，启动与全部行为和现在一样 | `ModuleDataSources` 增 `trade` 句柄；未绑定时是主库别名（§2.2 第一段） |
| R2 | 零售域每个类的事务、Mapper、手写 SQL 都落在 `trade` 句柄上，漏一处闸门报出文件与行 | 标记接口 `TradeTableMapper` + `ModuleTxManagerConventionTest` 增 trade 名单（§2.2） |
| R3 | 绑到 `n_trade` 后，商品、目录可售、收银下单、订单售后、履约、渠道、预约的现有验收行为不变 | §2.3 子模块逐个对齐 mapper → domain → service → controller → api；对照表逐表状态 |
| R4 | 交易模块不在自己的事务里写别的库，也不靠别的库的行锁；每个原同事务点有先后与恢复办法 | §2.4 事务边界 T1–T16 |
| R5 | 对外端点与 JSON 不变；状态取值若改短，三端与契约同步改 | §2.1、§8-Q3 |
| R6 | 防重复（下单、核销、渠道导入、资源占用、退款义务）在新库仍由唯一约束兜底 | §2.6 字段缺陷先修（D1–D3） |
| R7 | 行业插件里公用的商品、订单、预约代码并回交易模块，插件只留行业库 14 张表的代码 | §2.5 |
| R8 | 网关只认证与透传：网关里直接写零售表、跨库同事务的代码退回业务模块 | §2.7 |

**孤立项**
- 没落点的 AC：无。但 R3 中「先吃后付由到店单汇总成一张支付单」是**行为变化**，不在本篇范围，见 §2.8 与 §8-Q5。
- 挂不上 AC 的设计：§2.6 的字段缺陷清单是给设计方案的输入，不对应本仓库的 AC。

## §1 现状与影响面

### 1.1 规模

| 项 | 数 | 来源 |
|---|---:|---|
| 落 `n_trade` 的旧表 / 新表 | 224 / 74（6 张新表是新建） | 设计「原表、并入」两列 |
| 落 `n_industry` 的旧表 / 新表 | 22 / 14 | 同上 |
| 主代码里引用到的旧表 | 241（零引用 5 张） | 按表名匹配 `src/main` |
| 设计没有去向、代码在用的交易相关表 | 约 40 | 对照表 §3 |
| 按状态：切（先修 DDL）/ 先并再切 / 先留原表 / 不再需要 / 留行业库 / 待定 | 66 / 104 / 50 / 4 / 21 / 1 | 对照表 §1 |

引用旧表的模块（按引用到的表数）：`shop-core` 98、餐饮插件 86、`shop-app` 29（配置、测试装配）、美业插件 24、渠道插件 18、`shop-merchant` 17、`shop-gateway` 13、`shop-base` 12、零售插件 6。

### 1.2 代码在哪

| 区域 | 代码 | 说明 |
|---|---|---|
| 商品 | `shop-core` product 包（约 47 张表的实体与 `ProductMappers`）；platform 包里的类目；餐饮的菜品、选配、套餐；美业的服务定义 | 只有 `OpsCategoryPolicyController` 在接口层直接用 Mapper |
| 门店目录与可售 | product 包 `StoreSalesCatalogServiceImpl`、`MerchantGoodsServiceImpl`；餐饮菜单 `fnb_menu*`；`shop-merchant` 门店分类 | 一份「门店在卖什么」散在三个模块 |
| 收银、下单、订单、售后 | `shop-core` trade 包（602 个文件，`OrderServiceImpl`、`IndustryOrderServiceImpl`、`AfterSaleServiceImpl`、计价 CKO 系列）；餐饮账单、批次、结账、退菜；美业工单 | trade 下约 79 个 Mapper 接口 |
| 履约、执行、寄存 | trade 的 `IndustryFulfillmentServiceImpl`、服务执行；fulfillment 包；餐饮厨房任务、寄存；美业服务任务 | fulfillment 包同时管物流表（设计归平台服务库） |
| 渠道 | `shop-commerce-integration`（`cim_*` 38 张）、`shop-retail-commerce`、trade 里的 `ord_channel_*`、餐饮 `fnb_channel_*` | `backend/shop-channel` 是外部网关封装，不碰表，不在本次范围 |
| 预约与资源 | `shop-merchant` 资源、班表、占用（约 22 个文件）；美业预约（约 25）；餐饮订位、占台（约 27） | |
| 社区团购、询价 | `shop-core` community / marketing.group（约 19 个文件） | 最独立 |

### 1.3 订单两层的现状

- **零售**：一次结账建 1 张支付单 `ord_order` + N 张商家订单 `ord_sub_order`，同一事务；先写支付单再写商家订单（`OrderServiceImpl` :2505、:2638）。
- **餐饮**：每一批点菜各建 1 张支付单 + 1 张商家订单；先吃后付的支付单是没有截止时间的「待支付」空壳（`IndustryOrderServiceImpl` :784–796）。批次挂到店单靠 `fnb_order_batch.check_no`，批次与核心订单的关系在餐饮自己的 `fnb_core_order_binding` 里。**到店单与支付单之间没有直接关联。**
- 旧表 `ord_sub_order.order_no` 是 `NOT NULL`，代码里没有「商家订单先于支付单」的路径。新 DDL 允许为空（字段稿：先吃后付未结账时为空，结清时由到店单反查生成支付单并回填）。
- 退款链路已经是「短事务 + 提交后调外部 + 义务行重试」的形状（`ord_refund_execution` + `ord_refund_obligation`），天然适合切库。
- 渠道订单已经是「渠道单先、商家订单后」：`cim_order_link` 先落，恢复循环再建订单（`ChannelOrderServiceImpl`、`IndustryChannelOrderCommitter`）。同一件事有四套表（`cim_order_link`、`fnb_channel_*`、`retail_channel_*`、`ord_channel_import`）。

### 1.4 为什么不能逐表换库

`n_trade` 内部的同事务与联表（摘要，详见勘查 B、C、A）：

| 同一个事务或同一条 SQL 里 | 涉及的子模块 |
|---|---|
| 下单：订单 + 下单锁量 `prd_stock_lock` + 门店目录行锁 `prd_store_catalog` + 预约时段 `mch_appointment_slot.tryBook` | 目录可售、收银下单、订单、预约 |
| 履约：`IndustryFulfillmentServiceImpl` 同事务读写订单；`StoreOrderMapper:44` 联履约表；`StatusCasMapper:97` 的 EXISTS 子查询 | 订单、履约 |
| 渠道：恢复循环在同一事务里改渠道单并建商家订单 | 渠道、订单 |
| 餐饮：开单、加菜、结账同时写账单、批次、结算、占台 | 到店单、订单、预约资源 |
| 美业：工单、服务任务、服务步骤三表联表 | 到店单、履约、行业库 |

而**商品主档**（类目、规格、商品、SKU、套餐、标准品）、**社区团购与询价**、**顾客寄存**只被订单「读」，自己的写事务不碰别的子模块——它们可以单独换库。唯一的前提：SKU 上现在还带着主体档库存（`prd_sku.stock/locked_stock`），下单会写它；新 `prd_sku` 没有这两列，要先把这档库存挪到可售侧（§2.3 S1 前置）。

### 1.5 明确不受影响的

- 库存库、支付库、积分（支付库）的代码与数据源配置；发票、打印已切的部分。
- 平台基础库四个模块（登录与账号、商户与门店、门店设置与设备、员工与权限）——它们自己的换表另有 TDD。
- `backend/shop-channel`（外部网关封装，不碰表）。
- 192.168.3.61：本次只读。线上：不迁数据、不部署。

## §2 方案

### 2.1 契约变更

| 项 | 第一段（接句柄） | 第二段（换表） |
|---|---|---|
| 端点 / JSON | 无 | 端点不变。**状态取值改短（用户 2026-11-26 定）**：库里与对外 JSON 一律用 ≤10 字符的短值，订单、售后、履约、预约、渠道单的状态与类型都在内；B 端、C 端、运营端与后端同一批改，见 §2.9 |
| 库表 | 无 | 改用 `n_trade` 的 74 张、`n_industry` 的 14 张；50 张先留主库原表；设计要补的约束与列见 §2.6 |
| 权限码、i18n | 无 | 无 |
| 配置 | 新增模块句柄 `trade`、`industry`，不写绑定 = 主库 | `shop.datasources.trade`、`shop.datasources.industry`，`shop.modules.trade: { datasource: trade }` 等 |
| SPI | 无 | 签名不变；要保的隐含约定：商品快照的形状与摘要、事件载荷、`StockPort` 的 `lifecycleVersion`、对外字段名（如 `menuNo`） |

### 2.2 两段走

**第一段：接句柄（不改表，每个子模块一个提交，可推送）**

照平台基础库的做法：

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-store-mybatis/.../ModuleDataSources.java` | 登记 `trade`、`industry` |
| 新增 | `shop-store-mybatis/.../config/TradeTableMapper.java`、`TradeSqlMapper.java`、`IndustryTableMapper.java` | 标记接口。trade、product、fulfillment 包里混着打印事实、开票资料、物流、评价、积分等别库的表，不能整包绑定 |
| 新增 | `TradeJdbcConfiguration`（`tradeJdbcTemplate`） | 手写 SQL 用 |
| 修改 | `shop-app/.../MybatisPlusConfig.java`、`ModuleMapperScanConfig.java` | 全局扫描按标记排除，trade 扫描按标记收取；`shop-merchant` 里 9 个资源、班表、预约、门店分类 Mapper 加标记 |
| 修改 | 零售域所有事务声明 | 四个包裸 `@Transactional` 约 207 处、写死 `"transactionManager"` 约 70 处、`TransactionTemplate` 86 行；插件另计。点名 `tradeTxManager`。**写死主库名字的那 70 处最危险**：绑定后它们仍指向主库，编译与测试都不报 |
| 修改 | 餐饮、美业的 outbox 配置 | 现在强制「业务事务管理器 = 主库」，表迁走后启动失败；改为校验与本模块句柄一致 |
| 修改 | `shop-app/.../arch/ModuleTxManagerConventionTest.java` | 增 trade、industry 与「有意用主库」的名单 |
| 修改 | 13 个自搭上下文的 IT（其中 9 个测的是交易表） | 引入 `ModuleDataSourceRegistrar` |
| 修改 | `DataScopeRegistration`（按表名登记数据域）、`FoodSchemaValidator`（启动期按旧列名校验） | 第一段只登记，第二段随换表改名 |

第一段的检验方式：把 `trade` 绑到**同一个主库地址的另一个具名数据源**（同库不同池）。这时表都还在，但事务真的分开了——原来靠「同一个事务」的地方会在测试里暴露（读不到未提交的行、锁等待）。这是第二段之前找全事务拆分点的办法。

**第二段：换表（按耦合簇）**

| 簇 | 子模块 | 方式 |
|---|---|---|
| 甲（独立，可各自切） | S8 社区团购与询价（**试点**）、S5 里的顾客寄存、S1 商品主档 | 各自绑 `n_trade` 上线；只被别人读，读走 SPI |
| 乙（交易核心，一起切） | S2 门店目录与可售、S3 收银与下单、S4 订单与售后、S5 履约与执行、S6 渠道、S7 预约与资源，以及行业插件并回的部分 | **在专用分支上**逐子模块对齐新表结构，每个子模块一个提交、对着 `n_trade` 测试库跑测试；全部完成后**一次绑定**合回 v2（用户 2026-11-26 定，§3） |
| 丙（行业库） | 餐饮桌台、厨房、堂食服务，美业服务步骤 | `industry` 句柄绑 `n_industry`；与乙同时或之后 |

### 2.3 子模块拆分与顺序

按用户给的顺序排；规模是 `src/main` 文件数的估计（勘查脚本计数，含插件外围的 API、DTO、控制器）。

| 序 | 子模块 | 新表 | 代码包（mapper → domain → service → controller → api） | 规模 | 事务要拆的地方 | 对外契约 |
|---|---|---:|---|---|---|---|
| S0 | 底座 | — | 第一段全部；`trade`/`industry` 句柄；outbox 定案（§2.4 T4）；**设计缺陷先修**（§2.6） | 约 10 处装配 + 约 110 个文件的事务声明 | — | 无 |
| S1 | 商品 | 13 | `shop-core` product 的类目、规格库、商品主档（`ProductMappers`、`CategoryServiceImpl`、`SpecLibraryServiceImpl`、`MerchantGoodsServiceImpl`、`GoodsQueryPortImpl`）；platform 包的类目；餐饮 `DishServiceImpl`、`OptionGroupServiceImpl`、`BundleServiceImpl`、`ModifierServiceImpl`；美业 `ServiceDefinitionServiceImpl` | 类目约 18、规格约 25、主档约 73；餐饮美业并回另计 | 商品写事务里发 outbox（T4）；评分重算跨三库（T15）。前置：主体档库存移出 `prd_sku` | 不变 |
| S2 | 门店目录与可售 | 9 | `StoreSalesCatalogServiceImpl`、库存同步与可售（`StockSyncServiceImpl`、`StoreStockReader`、锁量）；餐饮菜单 `MenuServiceImpl`、`MenuCompositionSource`、限量 `SalesQuotaClaimServiceImpl`；`shop-merchant` 门店分类 | 库存与可售约 46、目录约 27 | 结账事务同时锁门店目录与平台库门店（T2）；网关 `InventoryDeliveryGuard` 把事件表与锁量表联查（T5） | 不变；**语义变化**：旧 `prd_store_catalog` 是每店一个指针，新表每目录每阶段一行；门店价与门店库存合表后「有行就走门店档」的判据要重写 |
| S3 | 收银与下单 | 10 | 购物车、地址、营业日、排队、离线草稿；计价（CKO 系列、`PricingFeeRuleMapper`）；下单 `OrderServiceImpl#createCoreFor/doCreate`、`IndustryOrderServiceImpl`、`CheckoutServiceImpl`；餐饮 `CheckCartServiceImpl`（1221 行） | 约 100（计价第二批） | **最大一处**：下单事务内券核销、活动限量（T6、T7） | 不变 |
| S4 | 订单与售后 | 14 | 订单读写、到店单、收款记录、售后与退款（`AfterSaleServiceImpl`、`RefundObligationServiceImpl`、`RefundExecutionStore`）；餐饮账单、结账、退菜（`DiningOrderServiceImpl` 1742 行、`DiningCheckoutServiceImpl`、`BusinessReturnServiceImpl`）；美业工单 | 订单读写约 110、售后约 43、餐饮并回约 60 | 支付回调后确认库存与积分（T8）；取消释放（T9）；售后回补（T10） | 不变；状态取值见 §2.6 D4 |
| S5 | 履约与执行 | 7 | `IndustryFulfillmentServiceImpl`、服务执行；fulfillment 包（物流表另归）；餐饮厨房任务、寄存；美业服务任务、提交、围栏 | 履约约 18、指派与执行约 34（最难）、寄存 3 | 核销与发货里写物流、通知（T11） | 不变 |
| S6 | 渠道 | 5 | `shop-commerce-integration` 全部、`shop-retail-commerce`、trade 的 `ord_channel_*`、餐饮 `fnb_channel_*` | 约 340（25 张技术表要重写成公共能力）＋零售 33 | 渠道单与商家订单同事务（簇内，不拆）；回调防重、恢复循环的抢占字段（T12） | 不变 |
| S7 | 预约与资源 | 10 | `shop-merchant` 资源、班表、占用；美业预约协调；餐饮订位、占台、日历 | 资源班表约 22、餐饮订位约 27、美业预约约 25 | 资源服务锁平台库的主体、门店、人员（T13）；餐饮订位锁行业库桌台（T14） | 不变 |
| S8 | 社区团购与询价 | 6 | community、marketing.group（`GroupMappers`） | 约 19 | 无 | 不变 |
| — | 行业库 | 14 | 餐饮桌台、厨房、KDS、权益、宴会；美业服务步骤；两行业的门店策略与配置修订 | 留插件 | 权益直接改到店单（T14）；菜单配置修订与菜单联表 | 不变 |

建议的实际推进：S0 → **S8 试点**（验证换表全流程：建表、迁数、绑定、回退）→ S1 → S2…S7 按簇乙方式推进。顺序依据：S8 零耦合；S1 只被读；S2 起进入交易核心。

### 2.4 事务边界：原来同事务、切库后不同事务的地方

| # | 现在 | 位置 | 切库后的写法 | 失败后怎么恢复 |
|---|---|---|---|---|
| T1 | 下单同时预留库存 | `OrderServiceImpl` :2501 | 已经不是同一本地事务：先落 `ord_create_intent` 再锁库存，租约扫描回滚。保持；`ord_create_intent` 要进 `n_trade`（设计里没有，§8-Q4） | 现有恢复任务 |
| T2 | 结账事务同时锁门店目录与平台库门店卡，并硬绑主库数据源 | `StoreSalesCatalogServiceImpl` :112–135 | 门店状态改为读取，不加跨库锁；目录锁留在交易库 | 门店在结账后被停用：下单后校验，按取消处理 |
| T3 | 号码、幂等写基础能力库 | `OrderServiceImpl` :1664、:2331 | 号码已在事务外预留，保持；幂等改「先认领、后提交」，或以 `ord_create_intent` 作为持久幂等 | 已有 |
| T4 | 订单、商品、餐饮菜单的事件写主库 `sys_outbox`，与业务同事务 | `OutboxEventBus` 各调用点 | **不用事件，提交后直接调用**（§2.10，用户 2026-11-26）：交易提交后用 `AfterCommit` 调下游接口；交易库不建事件来源 | 关键下游按订单状态扫描补做（§2.10）；可丢的只记日志 |
| T5 | 网关把事件表与锁量表联查 | `InventoryDeliveryGuard` :25 | 退回库存模块，改两次查询 | — |
| T6 | 下单事务内核销券 | `OrderServiceImpl` :2759；`CheckoutQuoteGuard` :83 | 改为「预占—确认」：下单前预占，订单提交后确认，失败释放 | 预占超时自动释放 + 对账 |
| T7 | 下单事务内扣活动限量 | `OrderServiceImpl` :2772–2776；柜台活动围栏 `CounterCampaignFence` | 同 T6；柜台活动围栏靠「持锁直到调用方写完」，跨库后失效，要单独设计 | 同 T6 |
| T8 | 支付回调里确认库存、记积分 | `OrderServiceImpl#markPaid` :3410–3536 | 回执已在事务外读；事务只改本库；确认库存改为提交后调用、各自重试。积分暂不接（§2.11） | 幂等重试 |
| T9 | 取消、关单时释放库存与券 | `OrderServiceImpl` :3985、:4104 | 关支付已是提交后；释放库存与券同样改提交后 + 重试 | 释放本身幂等 |
| T10 | 售后回补库存、退券 | `AfterSaleServiceImpl` :831、:1025、:1065 | 全部走退款义务行（已有 STOCK、COUPON 两类） | 义务行重试 |
| T11 | 履约、核销里写物流、通知 | fulfillment 包 `LogisticsServiceImpl` :258 等 | 物流表归平台服务库：改调物流接口；通知走事件 | 物流接口幂等 |
| T12 | 渠道自带事件、幂等、请求流水 | `cim_operation`、`cim_request_journal`、`cim_inbound_event` | 改公共事件；但公共幂等只有 24 小时，渠道请求流水是永久且重放返回首次结果——**能力缺口**（§8-Q6） | — |
| T13 | 资源服务锁平台库的主体、门店、人员 | `BookingOwnerMapper.lockScope`、`lockScopedPersonnel` | 改为读取校验，不跨库加锁；仲裁锁留在交易库的占用根行 | 人员在占用后停用：执行时再校验 |
| T14 | 行业库与交易库互写：餐饮订位锁桌台；权益直接改到店单；厨房任务联出品单；菜单配置修订联菜单 | `FoodMappers` :222、:272–323；`ServiceEntitlementMapper` :184 | 交易为主、行业跟随：行业侧收事件更新；联表改两次查询 | 事件重放 |
| T15 | 商品评分重算跨三库写 | `ReviewServiceImpl#recomputeRating` | 评价归会员营销库：评价侧提交后发事件，商品侧更新冗余评分 | 定时重算兜底 |
| T16 | 开票取数对订单行加锁 | `SubOrderInvoiceSourcePortImpl` :92 | 已由发票 TDD 定案（只读 + 来源指纹） | 已有 |

### 2.5 行业插件：并回交易模块与留行业库

| 插件 | 并回交易模块 | 留行业库 | 规模 |
|---|---|---|---|
| 餐饮 | 50 个实体（菜品、选配、套餐、菜单、限量、购物车、批次、订单行、账单、座位、排队、订位、营业日、退菜、收款意图、分摊、厨房任务、寄存、占台、日历、渠道来源）；food-core 约 60 个类，其中 **44 个同时碰交易表与行业表，要拆类** | 8 个实体：桌台区域、桌台、工位、厨房票、KDS 订阅与事件、服务权益、宴会与道次、门店策略与配置修订 | 约 190 个主文件（含 API、DTO、控制器） |
| 美业 | 买卖部分：预约三表、预约资源意图三表、服务定义、版本、需求（指定技师记在预约明细）＋相应的类 | **服务执行整段**（用户 2026-11-27 定，见[建议稿 A6](零售域-第二段建议方案与表清单.md)）：工单（并上下文、到店认领与核验）、服务任务、服务步骤、任务与订单行绑定（并来源观察与分配围栏）、服务提交（围栏改唯一约束）、服务参与（并入 `ind_assignment`）；门店策略与版本 | 约 47 |
| 零售插件 | 全部（渠道选品、渠道单来源、导入守卫） | — | 约 33 |

- **依赖方向不变**：现在是「插件 → spi ← shop-core」，没有插件依赖 shop-core。并回后行业插件仍只依赖 spi。
- **双份实现**有 8 处（购物车、商家订单、售后、目录、渠道订单、`ServiceExecutionFenceCoordinator` 同名类、库存、预约）：并回时收成一份，不是把两份都搬过去。美业的服务任务与交易的履约单元按 A6 **不收成一份**：美业保留自己的执行模型，交易只记到订单行交付了几份。
- **美业拆事务**：第一段把工单、工单上下文、服务任务、服务参与 4 个 Mapper 接到了交易句柄，`AppointmentPersistence` 用一个交易事务同时管预约与工单；换表前拆成预约（交易）、工单（行业）两个持久化对象，跨库点按建议稿 A6 的表处理。
- **闸门会立即生效**：`ArchitectureTest` 只扫 `shop-app` 的 classpath，插件现在基本没被守过；并回后域间依赖（基线为零）、顶层包登记（commerce、retail 未登记）、Port 只在 spi 三条规则会马上挡。并回要连同这些一起清。
- **插件私有迁移**：餐饮、美业各有私有 Flyway 与严格校验器，美业迁移已冻结。改插件表要走新迁移。
- 「不再建」的餐饮库存表（15 张，27 个文件，外围约 70 个文件）代码仍在用，随库存退役一起处理，不在本篇。

### 2.6 字段草案里的缺陷（只记录，未改设计，未碰 61）

方法与全量清单见[对照表](零售域-旧表新表对照.md) §4；下面是归纳与分区勘查补充的条目。

| # | 缺陷 | 规模与例子 | 后果 |
|---|---|---|---|
| D1 | **唯一约束被降成普通索引** | `05-n_trade.sql` 里 70 个以 `uk_` 命名的索引写成了 `KEY`（真 `UNIQUE` 只有 42 个，74 张表里 42 张没有任何唯一约束）。代码依赖的旧唯一约束 76 条被降级、48 条无处安放。例：`ord_order.order_no`、`ord_sub_order.sub_order_no/verify_code`、`ord_after_sale.after_sale_no`、`prd_goods.goods_no`、`chn_order(channel_store_key, external_order_key)`、`rsv_resource_bucket(resource_no, start_at)` | 重复下单、重复导入、重复占用不再被数据库挡住 |
| D2 | **防重列被删** | 49 条唯一约束里的防重列（请求键、操作号、内容摘要、命令号）在新表不存在，集中在报价分摊 8、收款 7、商家订单 5、资源占用 5、售后 4、执行 4 | 设计约定「防重由应用层做、不建表」；但应用层防重在并发下必须靠唯一约束兜底。不建防重**表**可以，防重**列 + 唯一约束**要留 |
| D3 | **整表无键或缺列** | `ord_assignment` 无任何索引也无逻辑删除；`ord_visit_attendee`、`ord_split_share`、`prd_spec_usage`、`prd_sku_barcode` 只有主键；`ord_refund_obligation` 缺 `input_hash`（`RefundObligationMapper` :34 的插入会直接失败）；`prd_stock_lock` 缺来源摘要、生命周期版本、确认操作号三列和「已分配」状态；`prd_sku` 没有主体档库存两列；`rsv_booking` 并 6 张表缺 50 多列；渠道恢复循环的抢占与到期列（fence、lease、next_attempt_at）全删 | 代码无处可写；恢复循环无法抢占 |
| D4 | **状态列 `varchar(10)` 装不下** | 代码里的取值：`AWAITING_RECEIPT`、`WAIT_OFFLINE_PAY`、`ARBITRATING`、`RETURN_REFUND`、`NOT_REQUIRED`、`PLATFORM_CONFIRMED`、`WAITING_CANCELLATION`、`HANDOVER_COMPLETE`、`STARTED_SUSPENDED`、`PARTIAL_LOCAL_HANDOVER_RECORDED`（31 字符）等；DDL 注释里的枚举与代码状态机也对不上（如执行单元 `BOUND/RESERVED/PERFORMED/VOIDED` vs 注释 `PENDING/DOING/DONE`） | 写入失败或截断；若换短值，售后类型与状态原样进 JSON，会改三端契约 |
| D5 | **编号与人员列收短** | 378 处 64→32（创建人、更新人各 42 张表，主体号、订单号、门店号、SKU 号等）；`prd_spec_value_usage.value_no` 旧值最长约 68；`prd_sku.merchant_sku_code` 64→32；支付流水号会截断；B 端 `clientOrderNo` 允许 64。已有两处证据：打印表迁移 V585 曾放宽到 64；建库会话迁数据时又加长了 26 列 | 迁数据失败或截断 |
| D6 | **乐观锁与默认值** | `version` 从 `bigint` 变 `int`（实体是 `Long`）；5 张表无 `version`，其中 `fnb_kds_event` 并入的实体继承带 `@Version` 的基类；金额、计数列丢了 `NOT NULL DEFAULT 0`（`refunded_minor + x` 恒为空、`sold_count` 为空时预售抢不到）；旧表 204 个 CHECK 约束全无；`created_at` 毫秒降到秒 | 乐观锁失效；累加恒为空 |
| D7 | **字符集** | 旧表 `utf8mb4_bin`（区分大小写），新库 `0900_ai_ci`（不区分）；部分旧库列是 `unicode_520_ci` | 编号、外部单号按大小写区分的唯一性会被合并 |
| D8 | **并入去向与语义** | 门店目录少了冻结内容与快照摘要两列（结账对账要用）；SKU 定价策略并入后丢了门店维度；资源占用的仲裁根行 `mch_booking_owner` 并进明细表后锁语义要重写；履约进度「累计数量」与履约单元「一份一行」语义不同；美业来源冻结账本并入后缺 13–27 列 | 合并不可行或要重写，建议设计保留独立表（对照表里标「先留原表」） |
| D9 | **文档对不上** | 行业库文字说「17 张」，表里 14 张；履约单旧表有「一张商家订单一条」的唯一键，设计已明确去掉（分批发货） | — |

**这些缺陷要在 S0 由设计方案的拥有者修正生成器并重新生成 DDL**；本篇不改设计文档，也不碰 61。修完之前，第二段的任何子模块都做不下去。

### 2.7 网关

- portal 基本不碰零售表。直接用 trade、product 实体与 Mapper 的是 `paybridge`、`invbridge`、`logisticsbridge`，约 45 个文件：Mapper 读写随 `trade` 句柄走，**不改业务代码**。
- 必须处理：网关内 `JdbcTemplate` 直连约 8 处、网关内 4 个 `@Mapper`、约 25–30 处事务要点名。
- **要退回业务模块**（不能只换数据源，因为它们在网关里写交易表或跨库同事务）：`payment_collection_*` 的直写、`ShipmentSourcePortImpl`、`LegacyCouponSourceFenceServiceImpl`（同一事务跨交易库与营销库）、`StockAlignmentService`、`StockReconcileService`、`InventoryDeliveryGuard`。按「网关与业务脱耦」，退回后网关只调业务模块的接口。
- 包外另有 23 个类直接 import 订单 Mapper 或实体，3 处手写 SQL 读订单表（`BrandPromotionCostServiceImpl`、`StoreRelinkHostFactsPortImpl` 动态拼表名、`PayOrderBackfillJob`），随 S4 改为接口。

### 2.8 订单两层与先吃后付

- 设计的三者关系：支付单 `ord_order` 是一次付款；商家订单 `ord_sub_order` 是各商家的履约与售后；到店单 `ord_visit` 是一次到店的容器，下面可以有多张商家订单。
- **本篇只做「对齐」**：餐饮仍是「一批一张支付单 + 一张商家订单」，商家订单的 `order_no` 照旧填；`ord_sub_order` 补 `visit_no`，替代 `fnb_order_batch.check_no` 与 `fnb_core_order_binding` 的关联。
- **「先吃后付时商家订单先于支付单、结清时由到店单汇总生成一张支付单」是行为变化**：结账端点含义、餐饮结算的按根结算（`<order_no>:FULL`）、收银端都要改。建议另起 TDD，由用户拍板（§8-Q5）。新库允许 `order_no` 为空，所以对齐时不必先做它。
- 订金（订位、宴会、预约）在还没有订单时收：`ord_payment` 要能挂在预约单或到店单上（DDL 注释写了，代码要适配）。

### 2.9 状态取值改短

用户 2026-11-26 定：状态取值改成 ≤10 字符的短值，不放宽列宽。

**换值总表（初版 2026-11-27）**：[零售域-状态短值换值表](零售域-状态短值换值表.md)——80 个状态列、57 个超长取值的短值（另 1 个待核），同义合并 5 组，三端涉及 9 个值；每个子模块切换前仍要跑「库里不存在超长旧值」检查，新发现的按该表第 2 节规则补。
**状态值只从枚举或静态常量取**（用户 2026-11-27 定）：每列一个定义处，Java 与注解 SQL 不写状态字面量，换短值时先把字面量收口到常量再改值；加源码守卫（换值表 §6）。

- **换值表要先补全**。设计里的换值表（`字段设计的待确认事项.md` §5.1）只有 29 个取值，是按字段说明里列出的取值做的；零售域代码里实际在用的长取值远不止这些，勘查已见到的有 `AWAITING_RECEIPT`、`AWAITING_CUSTOMER`、`AWAITING_SEAL`、`WAIT_OFFLINE_PAY`、`WAIT_OFFLINE_REFUND`、`ARBITRATING`、`RETURN_REFUND`、`NOT_REQUIRED`、`PLATFORM_CONFIRMED`、`WAITING_CANCELLATION`、`HANDOVER_COMPLETE`、`STARTED_SUSPENDED`、`SUSPEND_REQUESTED`、`PROOF_VALIDATED`、`PARTIAL_LOCAL_HANDOVER_RECORDED` 等。S0 里先按表逐个状态列从代码（常量、枚举、状态机、SQL 字面量、前端常量与 i18n 键）全量扫出取值，补成一张「旧值 → 短值」总表，交设计方案拥有者并入换值表。
- **短值要唯一、可读**：同一列里不能撞值；跨表同义的统一写法（设计已把 `AWAITING_PAYMENT`、`PENDING_PAY` 合成 `TO_PAY`）。
- **改在哪一层**：后端常量与状态机、Mapper 里的 SQL 字面量、对外 DTO、三端的状态常量、筛选项、文案键（i18n 键名若用状态值拼，要一起改并过 i18n 闸门）、报表与对账里按状态值统计的地方。
- **什么时候改**：随所在子模块在分支上一起改，切换时一起上线；三端不能早于后端发版。迁移数据时旧值按总表换值。
- **对外契约**：这是契约变更（档位 1 以上），每个子模块的提交要写明改了哪些取值，三端各自跑 `vue-tsc` 与契约闸门。

### 2.10 不用事件，提交后直接调用

用户 2026-11-26 定（与 [TDD-Outbox收缩与直接调用](TDD-Outbox收缩与直接调用.md)「非必要不用 Outbox、同进程直接调用」一致）：交易模块不再写事件表，交易库不建事件来源。

做法：
1. **在订单事务提交之后调下游接口**（`AfterCommit`），不在事务里调；跨库后事务里调用等于订单回滚而对方已做。
2. **关键下游按订单状态补做**：订单表本身就是待办清单，定时任务扫「状态已到、下游还没做」的订单再调一次；下游按「订单号 + 动作」防重复。
3. **可以丢的接受偶发丢失**：失败只记日志。

| 现在的事件 | 下游 | 分类 | 补做方式 |
|---|---|---|---|
| 付款 | 卡券售卖发卡 `CardSaleEventConsumer` | 关键 | 扫「已付款、含卡商品、未发卡」的订单 |
| 付款 | 自动开票 `InvoiceAutoIssueConsumer` | 关键 | 扫「已付款、需自动开票、无有效发票」的订单（发票侧已按来源防重） |
| 退款完成 | 退回旧券 `LegacyCouponRefundCompletedConsumer` | 关键 | 改走退款义务行（kind=COUPON，已有重试） |
| 退款完成 | 分账回退 `RefundSplitBackPortImpl` | 关键 | 同上，走退款义务行 |
| 商品变更 | 库存投影（invbridge）、渠道商品同步（commerce） | 关键 | 已有库存对账任务；渠道同步按商品修订号补 |
| 下单 | 风控、裂变归因 | 可丢 | 只记日志 |
| 付款、发货、送达、完成、售后 | 商家来单通知、买家通知、打印提醒、会员事件、物流快照 | 可丢 | 只记日志；打印事实另按发票 TDD 的对账任务补 |
| — | 报表更正 | 可丢 | 已有报表更正任务 |

随之要改：`OutboxEventRoutes`（订单流按根订单分组）、上表各接收方从「收事件」改成「被调用」；餐饮插件发事件的地方并回时一起改。

### 2.11 积分暂不接

用户 2026-11-26 定：零售域重构这一轮**不接积分**。

- 交易库这边不搬、不改积分代码：`trade/order/points/**`（约 25 个类）与 `ord_points_*`（binding、placement、refund_binding、refund_execution、command、earn_rule、integration_policy、recovery_cursor、refund_retry、source_counter、source_fact、work_cursor）全部**先留原表、原样不动**，对照表里已标「先留原表」。
- 在分支上对齐后的下单、付款、售后**不调积分接口**：不做积分抵扣、不发积分、不退积分。报价分摊里并入的积分抵扣行不出现。
- 这是**行为差异**：切换上线前要么接回积分，要么确认这段时间不提供积分（三端隐藏积分抵扣入口）。在 §8-Q11 记为切换前必须定的事。
- 接回时另起 TDD；积分账归支付库，接法按「提交后直接调用 + 状态扫描补做」。

## §3 选型

**交易核心簇（乙）怎么推进**

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| 甲 | 代码在分支上逐子模块改成新表结构、对着 `n_trade` 测试库跑测试，全部完成后一次绑定 | 主库不动；与发票的做法一致 | 分支在完成前不能合回 v2（代码已是新表、v2 的运行库还是旧表），交易核心不能「每模块做完就推送」到 v2；最后一次切换风险集中 | ✅ **用户 2026-11-26 选定** |
| 乙 | **主库就地演进**：每个子模块用迁移把主库里的旧表改成新表的形状（改名、合表、加列），代码随之改，始终可运行可推送；全部到位后把 `trade` 绑到 `n_trade`，做一次整库数据搬迁 | 每步可运行、可推送、可回退；同名表天然不冲突；换库那一步只剩「搬数据 + 拆事务」，而事务拆分已在第一段用「同库不同池」验过 | 主库要跑大量改形迁移（正式库带 DDL 的迁移要人工执行）；插件私有迁移要解冻 | ❌ 未采纳（用户选甲） |
| 丙 | 过渡期 `n_trade` 与主库在同一实例，跨库名联表与同事务 | 改动最少 | 违反「库之间不联表、不共事务」，ADR-066 明确否掉的路数；拆库永远拆不干净 | ❌ |

**行业插件公用部分的去向**

| 方案 | 结论 |
|---|---|
| 留在插件里，只把数据源绑到 `n_trade` | ❌ 一个库由三处代码写，「一个库一个归属模块」落空；8 处双份实现继续分叉 |
| 并回交易模块，插件只留行业库 | ✅ 设计方案的本意；依赖方向不变 |

不可逆的部分见 [ADR-068](ADR/ADR-068-门店交易库按耦合簇换表与行业插件公用部分并回.md)。

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 设计缺陷不修就开工 | 重复下单、重复导入、写入失败 | S0 阻塞：D1–D6 修完、重新生成 DDL 才进第二段 |
| 写死 `"transactionManager"` 的约 70 处绑定后仍指向主库 | 订单写进交易库、事务却开在主库：静默不回滚 | 守卫把字面量主库名也算违规；「同库不同池」模式下跑全量测试 |
| 券、活动限量改两阶段 | 预占泄漏、超卖 | 预占带过期；对账任务；先在第一段的同库不同池下验证 |
| 不用事件（用户已定） | 进程在提交后、调用前退出，关键下游漏做 | §2.10：关键下游各有按订单状态的补做任务，下游按订单号防重复 |
| 积分暂不接（用户已定） | 分支上的下单、售后与现网行为不同 | §2.11：积分代码与表原样不动；切换前定接回还是隐藏入口（§8-Q11） |
| 状态改短（用户已定） | 漏改一处：三端显示与筛选错、按状态统计的报表与对账漏数、SQL 字面量查不到行 | §2.9：先全量扫出取值做总表；后端、三端、报表同批改；数据迁移按总表换值；每个子模块加一条「库里不存在旧值」的检查 |
| 并回餐饮 44 个混用类 | 拆类出错、行为漂移 | 先补场景测试（`food-embedded-test` 已有一批），逐类拆 |
| 测试基建 | 13 个 IT 要专用测试库；`StoreOrderMysqlTest` 依赖旧库模板；171 个测试夹具文件写死表名 | S0 先建 `n_trade` 测试库脚本（设计 DDL + 补充脚本），夹具随子模块改 |
| 交易核心分支长期不合回（方案甲） | 与 v2 上并行的改动冲突越积越多；最后一次切换问题集中 | 第一段（接句柄）仍逐步推到 v2，缩小分支差异；分支每完成一个子模块就合入一次 v2 的最新代码；切换前在 `n_trade` 测试库上跑全量，并演练一次数据搬迁与回退 |
| 网关发货重传锁单与写发货上传在同一事务（`ShipmentSourcePortImpl.retryUpload`、`lockedIdentityOf`） | 订单换到交易库、发货上传留主库后成了跨库事务 | 随物流一起拆：锁单只用来对版本，改成先读版本、写发货上传时带版本号比对 |
| 旧券结账闸门按主库数据源判「结账事务已开」（`LegacyCouponSourceFenceServiceImpl`） | 换表后结账事务开在交易库，带旧券的结账一律失败 | 切换前旧券迁移必须完成、闸门下线；没完成就不切 |
| 分支上的测试只能对着 `n_trade` 测试库跑 | 测试库与 61 上的库、设计 DDL 不一致 | 测试库只由修好后的设计 DDL + 补充脚本生成，与 61 用同一份来源 |

## §5 对账三 · 实现 → 需求（实现后填）

| AC | 计划的测试 | 跑过 |
|---|---|---|
| R1 | 零售域现有测试在未绑定状态全绿；`ModuleDataSourceRegistrarTest` 增 trade | |
| R2 | `ModuleTxManagerConventionTest#module_transactions_name_their_own_manager`（含 trade 名单、字面量主库名） | |
| R3 | 各子模块现有测试在「绑定 `n_trade` 测试库」状态全绿；`MapperSmokeTest` 每个 Mapper 查到自己的表 | |
| R4 | 「同库不同池」模式下全量测试；T6、T7 的预占—确认各一条失败恢复测试 | |
| R5 | 契约闸门（pre-push）通过；若换状态值，三端 `vue-tsc` 与 c-app 单测 | |
| R6 | 每个恢复的唯一约束一条并发重复写入测试（下单、渠道导入、资源占用、退款义务） | |
| R7 | `ArchitectureTest` 扫描范围含并回后的包，全绿 | |
| R8 | 网关不再有零售表的直写（读源码的守卫） | |

## §6 对账二 · 设计 → 实现

### 第一段 · 提交一：交易句柄与 Mapper 归属（不改表、不改事务）

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-store-mybatis/.../ModuleDataSources.java` | 登记 `trade` 句柄（`tradeDataSource`、`tradeSqlSessionFactory`、`tradeTxManager`） |
| 新增 | `shop-store-mybatis/.../TradeTableMapper.java`、`TradeSqlMapper.java` | 标记接口 |
| 修改 | `shop-store-mybatis/.../PlatformJdbcConfiguration.java` | 增 `tradeJdbcTemplate` |
| 修改 | `shop-app/.../MybatisPlusConfig.java`、`ModuleMapperScanConfig.java` | 全局扫描按标记排除；交易扫描按标记收取（trade、product、fulfillment、community、marketing.group、promotion.mapper、merchant.mapper 七个包） |
| 修改 | 17 个 Mapper 文件 | 71 个 Mapper 改继承 `TradeTableMapper`：product 25、trade 27、merchant 11、fulfillment 3、marketing.group 3、promotion 1、community 1 |
| 重新生成 | `docs/technical/README.md`、`reference/glossary.json`、`reference/后端分层清单.md`、`reference/静态常量清单.md` | 生成器产物 |

判据：Mapper 的实体表在[对照表](零售域-旧表新表对照.md)里状态为「切」或「先并再切」才打标记。19 个交易相关的 Mapper 有意**不打**：
17 个表是「先留原表」（计价头与版本、收款记录、渠道售后与履约证据、手工受理、运费模板、缺货上报、占用仲裁根行等）；
`AddressMapper`（`usr_address`）设计归 `n_trade` 的 `ord_address`，但上一步已随 C 端账号整包接到平台句柄，S3 时再定归属。
积分 `ord_points_*` 一律不动（§2.11）。

验证（2026-11-26，本机，H2）：`mvn -o -pl shop-app -am test -Dtest=ArchitectureTest,ModuleTxManagerConventionTest,ModuleDataSourceRegistrarTest,MapperSmokeTest` →
31 个测试，0 失败、1 跳过（`ArchitectureTest` 里原有的跳过项）；`MapperSmokeTest` 在完整上下文里每个 Mapper 能查到自己的表。`node scripts/check-generated-docs.mjs` → 28 个生成器产物都是最新的。

**没做 / 没验证**：
- 事务没有点名 `tradeTxManager`——按子模块逐个做（下一个提交从商品开始），守卫随之加 trade 名单。
- 注解 SQL 的 Mapper（`StatusCasMapper`、`RefundObligationMapper` 等）都同时碰「先留原表」的表（积分、运单、退款执行），本次没有一个打 `TradeSqlMapper`；扫描已就位，随订单与售后拆开后再打。
- 绑定状态（`trade` 指向另一个数据源）没有端到端测试；13 个 `Resource*IT`、`Schedule*IT` 需要专用测试库，本机没配，没跑。
- 餐饮、美业、渠道插件的 Mapper 由各自的条件配置注册，本次没动，随 §2.5 并回时接。

### 第一段 · 提交二：商品包的事务点名交易句柄

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-core` product 包 22 个类 | 85 处事务点名 `tradeTxManager`：裸 `@Transactional`、写死 `"transactionManager"` 的注解与 `@Qualifier`（`TransactionTemplate` 用） |
| 修改 | `shop-app/.../arch/ModuleTxManagerConventionTest.java` | 守卫加入 product 包与名单 `PRODUCT_NOT_TRADE`；句柄常量规则加 trade |

逐类分档（按类里用到的 Mapper 的表）：

| 档 | 类 | 处理 |
|---|---|---|
| 只碰交易库 | `OpsCategoryPolicyController`、`CatalogPriceSourcePortImpl`、`DishOfferQueryPortImpl`、`GoodsCatalogQueryPortImpl`、`RetailProductSourcePortImpl`、`SpecValueCatalogQueryPortImpl`、`StockPortImpl`、`CardProductBindingServiceImpl`、`CategoryServiceImpl`、`GoodsPricingPolicyServiceImpl`、`InvManagedServiceImpl`、`MerchantGoodsServiceImpl`、`PlatformProductServiceImpl`、`SkuIdentityServiceImpl`、`SpecLibraryServiceImpl`、`SpuStdServiceImpl`、`StockDispositionServiceImpl`、`StoreServiceImpl`（只经交易端口） | 点名 trade |
| 以交易库为主，顺带写「先留原表」 | `CardProductBindingWriter`（`prd_ent_binding_command`）、`ExecutionStockRetentionServiceImpl`（`prd_execution_retention`、`prd_stock_restore`）、`OriginalStockRestoreServiceImpl`（`prd_stock_restore`）、`StockSyncServiceImpl`（`prd_store_stock_sync`、`prd_stock_sync_log`） | 点名 trade；**换库前要拆**：这几张表设计要取消但代码在用，要么设计补进 `n_trade`，要么改成各自提交 |
| 别的库 | `ReviewServiceImpl`、`GoodsFavoriteServiceImpl`、`TopicServiceImpl`（会员营销库） | 留主库，写进守卫名单 |
| 换库前要重写 | `StoreSalesCatalogServiceImpl`：结账时在调用方事务里锁门店目录，断言调用方事务持有主库数据源，并在同一事务里登记目录发布事件 | 留主库，写进守卫名单；随 S2 按 §2.4 T2、T4 重写 |

`MerchantGoodsServiceImpl`、`InvManagedServiceImpl`、`SkuIdentityServiceImpl` 在事务里发事件（主库 `sys_outbox`）：未绑定时与业务同一事务，行为不变；绑定前按 §2.10 改成提交后直接调用。

验证（2026-11-26，本机，H2）：引用商品包的 shop-app 测试加守卫，`mvn -o -pl shop-app -am test -Dtest=<54 个类>,ModuleTxManagerConventionTest,MapperSmokeTest,ModuleDataSourceRegistrarTest` →
本机能跑的 36 个测试类 242 个测试，0 失败、41 跳过（原生 MySQL 前提不满足时自行跳过）。另 6 个类失败，**都是本机没有原生 MySQL 环境**，与本次改动无关：
`StoreOrderMysqlTest`（缺 `COMMERCE_MYSQL_URL`）、`ManualCounterRecoveryIT`（缺 `MANUAL_INVENTORY_MYSQL_URL`）、`ManualStockDispositionIT`（缺 `CHECKOUT_NATIVE_MYSQL_URL`）、`ManualCounterDiscountIT`（断言库是 MySQL）、`StoreSalesCatalogIT`（缺测试库地址）、`ManualServiceFinancialIsolationIT`（H2 没有 `json_valid`）。没有在原生 MySQL 上验证。

### 第一段 · 提交三：商户域里的资源预约、班表、门店分类点名交易句柄

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-merchant` 8 个类 | `ResourceServiceImpl`、`ResourceCommandServiceImpl`、`ResourceBookingServiceImpl`、`ResourceAdmissionServiceImpl`、`ResourceSourceAuthorizationServiceImpl`、`ScheduleServiceImpl`、`ResourceOccupationSchemaVerifier`、`StoreCategoryServiceImpl`：事务管理器与数据源一起点名 `tradeTxManager`、`tradeDataSource`（这几个类用数据源判断「调用方事务是否持有本库连接」、构造事件发布器、校验表结构，三者必须同一个库） |
| 修改 | `ModuleTxManagerConventionTest` | 商户域新增名单 `MERCHANT_TRADE`：这 8 个类必须点名交易句柄，不再算「留主库」 |

留在主库：`AppointmentSlotServiceImpl`（`mch_appointment_slot` 设计里没有去向，在下单事务里占用；随订单收敛到 `rsv_resource_bucket`）。

换库前要拆（已在 §2.4 T13、T4）：资源、班表服务在同一事务里对平台基础库的人员、门店加锁（`lockScope`、`lockScopedPersonnel`）；
同一事务里还写 `mch_booking_owner`、`mch_resource_command`、`mch_schedule_change_log`、`mch_staff_log`（前两张「先留原表」，后两张设计没有去向或归统一审计）；
资源、班表、预约的事件经主库事件表登记，绑定后发布器的「事务与事件表同库」校验会直接报错——这是有意让它在绑定时响亮失败，按 §2.10 改成提交后直接调用之后才能绑定。

验证（2026-11-26，本机，H2）：`ModuleTxManagerConventionTest` 2/2、`MapperSmokeTest` 2/2、`ArchitectureTest` 27（1 跳过）、`SchemaGeneratorTest` 6/6、`ResourceDemandNormalizerTest` 15/15、`ResourceSourceContractTest` 16/16 通过。
10 个资源、班表场景测试在本机 MySQL 9.7.4 上跑（root 空口令，各测试自建随机库；系统属性 `*.test.admin-url=jdbc:mysql://127.0.0.1:3306/mysql?...`、`*.test.root=<工作树根>`）：
202 个测试，55 通过、147 失败。**同一命令在 v2 原始代码（0ac15d771）和上一提交（c765aacea）上跑，失败的是完全相同的 147 个用例**（逐个比对用例名），所以失败与本次改动无关，是 v2 上已有的测试夹具欠账：

| 原因 | 次数 | 涉及 |
|---|---:|---|
| 夹具只装了 V592、V595，没装资源占用的前向结构（V607 起），`ResourceOccupationSchemaVerifier` 判为 ABSENT，抛 `RESOURCE_DEPENDENCY_UNAVAILABLE` | 122+3 | `ResourceAdmissionPersistenceIT`、`ResourceBookingExecutionIT`、`ResourceCommandPersistenceIT`、`ResourceSourceAuthorizationIT`、`ResourceRecoveryFactsIT` |
| 共用的 `schema-test.sql` 里有 H2 专用的 `CREATE ALIAS ... SHA2`，在 MySQL 上语法错误 | 22 | `ResourceMaintenanceIT`、`ScheduleMaintenanceIT` |
| 其他断言 | 1 | `ActualResourceExecutionIT` |

全部通过的：`ResourceFactsPersistenceIT` 19/19、`ScheduleWindowsPersistenceIT` 23/23。结论：这一步没有引入新的失败。

### 第一段 · 提交四：资源占用结构校验在 MySQL 上永远判「损坏」，及场景测试夹具

上面 147 个失败查下来，**大头不是夹具，是业务代码的缺陷**：

| # | 问题 | 位置 | 改法 |
|---|---|---|---|
| F1 | MySQL（8.0.16 起，含 9.7）存 CHECK 约束时会改写原文：引号转义成 `\'`、`MOD(x,5)` 变 `x % 5`、`TIMESTAMPADD(MINUTE,5,a)` 变 `a + interval 5 minute`、嵌套 `(a OR b) OR c` 拍平。`ResourceOccupationSchemaVerifier` 拿 `information_schema` 的文本去比描述文件里的原文，分词器不认 `\`、`%`、`*`，比不上就吞掉异常判 `CORRUPT` —— **在 MySQL 上资源占用的结构校验永远不通过**，资源命令一律报 `RESOURCE_DEPENDENCY_UNAVAILABLE` | `shop-merchant/.../policy/ResourceOccupationSchemaVerifier.java` | 比对前还原一层转义（`renderedCheck`）；分词认 `* / %`；规范化时 `%` 记作 `mod`、`+ interval n unit` 记作 `timestampadd`、同类 AND/OR 拍平。只影响 MySQL 分支，H2 分支不走这段。新增单测 `ResourceOccupationSchemaVerifierCheckClauseTest`（含「真正不同的约束仍判不同」） |
| F2 | 维护类场景测试把 H2 夹具 `schema-test.sql` 灌进 MySQL，夹具里有 H2 专用写法：`CREATE ALIAS`、`IS JSON [OBJECT]`、`H2_JSON_LENGTH`、`IN` 改写成的 `CASE … THEN TRUE`，以及 V655 一条读自身目标表的 DELETE | `shop-app/src/test/.../infra/ResourceOccupationNativeFixture.java` 新增 `forMysql`；`ResourceMaintenanceIT`、`ScheduleMaintenanceIT`、`PersonnelMaintenanceIT` 改用它 | 按 `gen-test-schema.py` 的改写逐条还原；V655 那条只动运营菜单授权，这几个测试用不到，加载时跳过 |
| F3 | 测试本身过时：`ActualResourceExecutionIT` 查 Flyway 历史的 `607`（占用迁移已改号为 V621，V607 现在是别的迁移）；`ResourceBookingExecutionIT` 一条 INSERT 漏了 `created_by`/`updated_by`，在报唯一键冲突之前先报「无默认值」 | 两个测试文件 | 改成 `621`；INSERT 补两列 |

**线上影响（未核实）**：生产是 MySQL 9.7.2，改写规则与 9.7.4 相同的话，线上的资源占用结构校验也永远判「损坏」。线上是否装了 V621、美业预约是否走这条校验，本次没有核实。

验证（2026-11-26，本机 MySQL 9.7.4）：10 个资源、班表场景测试 **202 个里 201 通过**（改前 55）；资源相关单测与契约测试、守卫、Mapper 冒烟、`H2FixtureProjectionTest` 全过。剩下的：

| 测试 | 情况 |
|---|---|
| `ResourceBookingExecutionIT#candidateCapacityUsesEffectiveServiceTimeInsteadOfLookback` | 断言「容量查询为空」，实际返回资源 B 在回看窗口里的时段。是业务口径问题（容量该不该从人员生效时间算起、是否适用于非人员资源），没改，待定 |
| `PersonnelMaintenanceIT` 13 个里 2 个 | 测试里手搭的 `MerchantStaffServiceImpl` 缺 `StoreOwnershipWritePort`（实现类后来加了 setter 注入）；属平台基础库员工那一块的测试，没改 |

本机 MySQL 留下约 1380 个测试自建的随机库（`resource_*`、`booking_*`、`schedule_*`、`personnel_*`、`beauty_*`），这些测试结束时不删自己的库，没有清理。

### 第一段 · 提交五：交易包（收银与下单、订单与售后、履约、渠道入单、计价）点名交易句柄

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-core` trade 包 66 个文件 | 事务点名 `tradeTxManager`：裸 `@Transactional`、`@Transactional("transactionManager")`、`value = "transactionManager"`、`TransactionTemplate` 的 `@Qualifier`；用数据源的 6 处（`CheckoutQuoteGuard`、`PricingFeeRuleMapper`、`IndustryOrderAmendmentMapper`、`FoodStockBridgeJournal`、`ServiceExecutionSchemaVerifier` 等）同时改用 `tradeDataSource` |
| 修改 | `ModuleTxManagerConventionTest` | 守卫加入 trade 包与路径名单 `TRADE_NOT_TRADE` |

留在主库（守卫名单）：`order/points/**`（积分这一轮不接，§2.11）、`order/printing/**`（打印事实归平台服务库）、`PointsRefundResourceServiceImpl`、`InvoiceRequestServiceImpl`（开票申请归发票）、`SeqSegmentSource`（`sys_sequence`，基础能力库）。

分档依据（脚本按类里用到的 Mapper 归属统计，85 个带事务或数据源的类）：只碰已标记交易表的 17 个；以交易表为主、顺带碰「设计里没去向的交易表」的 37 个——这些表是 `ord_create_intent`、`ord_checkout_operation`/`confirmation`/`payment_request`、`ord_status_log`、`ord_offline_record`、`ord_payment_resolution`、`ord_store_collection`、`ord_express_pickup`、`ord_channel_*` 账本、`ord_service_execution_action`、`ord_legacy_coupon_checkout` 等，它们本身是交易的一部分（§8-Q4 建议补进 `n_trade`），所以这些类按交易处理；
没有 Mapper、只经端口或手写 SQL 的 19 个，按读写的表判；积分、打印、开票、序号段归别处。

验证（2026-11-26，本机，H2）：守卫 2/2、`ArchitectureTest` 27（1 跳过）、`MapperSmokeTest` 2/2 通过。引用 trade 包的 shop-app 测试 217 个类、1500 个测试：114 个失败、215 个跳过。
**同一命令在上一提交（5c6acb203）上跑，失败的是完全相同的 114 个用例**（逐个比对用例名）。它们全部来自 8 个只能连原生 MySQL 的测试类，本机没配：
`StoreOrderMysqlTest`（缺 `COMMERCE_MYSQL_URL`）、`ManualCounterRecoveryIT`（缺 `MANUAL_INVENTORY_MYSQL_URL`）、`ManualCounterIdentityIT`、`ManualCounterDiscountIT`、`ManualCounterPurchaseIT`、`ManualServiceAdmissionIT`、`ManualCounterPrivacyIT`（断言库是 MySQL）、`ManualServiceFinancialIsolationIT`（H2 没有 `json_valid`）。
这 8 个恰好覆盖柜台收银与手工服务单的事务路径，**这一步的这几条路径没有经过原生 MySQL 验证**。

换库前要拆（在 §2.4 已列的之外）：积分类在订单事务里以 `MANDATORY` 加入（`OrderPointsSourceOrdersImpl` 等），绑定后它们开在主库，会报「没有事务」——积分接回时一起处理；打印事实同样以 `MANDATORY` 加入订单事务（T6）。

### 第一段 · 提交六：履约、自提点、求购报价点名交易句柄（按类或按方法）

| 文件 | 处理 | 依据 |
|---|---|---|
| `fulfillment/service/impl/DispatchServiceImpl`、`PickupServiceImpl`、`fulfillment/port/GroupPickupPortImpl` | 整类点名 trade | 到货批次、核销、团购自提都是交易表（缺货上报设计并入售后） |
| `fulfillment/service/impl/LogisticsServiceImpl` | 只有 `saveFreightTemplate`、`archiveFreightTemplate` 点名 trade | 运费模板并入计价定义；运单、轨迹、承运商归平台服务库，其余方法留主库 |
| `community/service/impl/CommunityAdminServiceImpl` | 只有 `setPickupStatus`、`decidePickup` 点名 trade | 只碰自提点；小区、地址库归平台服务库，入驻申请归审核服务 |
| `marketing/group/impl/GroupServiceImpl` | `createRequest`、`toggleInterest`、`choose`、`quote`、`revise`、`opsRevisePrice` 点名 trade | 只碰求购、报价；拼团与团成员归会员营销库，`confirmRequest`、`markBreach`、`auditGroup` 两边都写，留主库，**换库前要拆** |
| `ModuleTxManagerConventionTest` | 守卫加入 fulfillment 包（物流类除外） | 按方法分的三个类不在守卫里 |

`PeriodServiceImpl`（团购周期）没有事务注解，经 `PeriodMapper` 已随交易句柄。

验证（2026-11-26，本机，H2）：引用这几个包的 46 个测试类加守卫、架构、Mapper 冒烟，445 个测试 0 失败、15 跳过；上一提交上同样 0 失败。

### 第一段 · 提交七：零售插件（shop-retail-commerce）接交易句柄

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `RetailCommerceAutoConfiguration` | 渠道选品的手写 SQL 与事务：数据源、事务管理器点名 `tradeDataSource`、`tradeTxManager`（原来数据源按类型注入、拿到的是主库） |
| 修改 | `RetailChannelOrderAutoConfiguration` | 渠道单来源、导入守卫、渠道方的 Mapper 扫描改绑 `tradeSqlSessionFactory`；渠道入单服务的事务管理器点名 trade |
| 修改 | `RetailChannelMappers` | 3 个 Mapper 改继承 `TradeTableMapper` |

为什么要打标记：`shop-app` 的全局扫描排除名单里没有 `ai.neargo.shop.retail`，插件在类路径上时这 3 个 Mapper 会先被全局扫描注册到主库工厂，插件自己的扫描就不生效了。打标记后全局扫描排除它们，只由插件的条件配置注册——和餐饮、美业、渠道插件「只由自身启用条件注册」的做法一致。
行为差异：插件在类路径上但没启用时，这 3 个 Mapper 不再被注册；仓库里只有插件自己用它们（已查）。

验证（2026-11-26，本机）：插件自带 11 个测试类 54 个测试全过（`-P commerce-embedded,retail-commerce-embedded`）；带插件、`shop.retail.commerce.enabled=true` 启动完整上下文跑 `MapperSmokeTest` 通过。
**没验证**：渠道入单那组配置还要求渠道集成启用（`shop.commerce.enabled`），而渠道集成要它自己的迁移历史，本机没有；所以「插件 Mapper 实际落在交易工厂上」没有在完整上下文里看到。仓库里没有带零售插件启动整个应用的测试。

### 第一段 · 提交八：渠道插件（shop-commerce-integration）主流程接交易句柄

用户 2026-11-26：先解决主流程（平台订单导入）；**商品发布不应在任务库**；**密钥部分统一调用平台 API**。

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `commerce-core/.../mapper/CommerceMappers.java`、`AvailabilityPublicationMapper.java` | 11 个 Mapper 改继承 `TradeTableMapper`：账号、门店连接、资源映射、同步范围（含目录来源、同步策略）、渠道订单、订单行关联、订单修订，以及商品发布、目录快照 |
| 修改 | `commerce-core/.../config/CommerceCoreAutoConfiguration.java` | Mapper 扫描一分为二：带交易标记的绑 `tradeSqlSessionFactory`，其余仍绑主库；命令执行器 `CommerceCommands` 与目录授权服务的事务管理器点名 trade |
| 新增 | `commerce-core/src/test/.../scenario/CommerceModuleHandles.java` | 测试上下文引入模块句柄注册器；3 处组装上下文的地方加上它 |

留在主库：请求流水（`cim_request_journal`）、对外操作与尝试、回调收件与回执、授权/连接/映射修订、来源通知与撤回、同步运行、对账、证书句柄、平台与平台产品；统计服务（跨交易表和技术表联查）；表结构校验与迁移（`CommerceSchemaValidator` 仍对主库）。

**换库前要拆**：`CommerceCommands` 在同一事务里先写请求流水、再写业务表——请求流水留主库，绑定后两者不再同事务（§8-Q6 的幂等能力缺口）。统计服务的联查跨两个库。

**设计修正（只记录，未改设计文档）**：
- 商品发布 `cim_availability_publication`、目录快照 `cim_catalog_snapshot` 设计里归任务库（`job_log`），用户定**不进任务库**，随渠道留在门店交易库。设计里要补表（或并进 `chn_catalog_scope`）。
- 证书句柄 `cim_certificate_handle` 与平台授权凭据：统一调用平台的密钥 API，渠道不再存密钥相关的表或句柄。现在 `SecretVaultChannelCertificateAdapter` 是进程内 `SecretVault.seal`，做到授权与证书那块时改成调平台 API。

验证（2026-11-26，本机，H2，`-P commerce-embedded,retail-commerce-embedded`）：渠道插件 4 个模块 300 个测试，改动后失败 4 个，改动前失败 3 个（相同的 3 个：`CommerceArchitectureTest` 一个、`SourceNoticeRecoveryTest` 一个、`InternalChannelCatalogAuthorityApiTest` 一个）。
多出的 `SourceNoticeRecoveryTest#reenablingFollowsOriginalHeadAfterDisabledNotificationsAndKeepsAnEventOnlyTimer` 单独跑时改动前后各 3 次全过，是整套跑时依时间的不稳定用例。没有在完整应用里验证（渠道插件要自己的迁移历史，本机没有）。

### 第一段 · 提交九：美业、餐饮插件接交易句柄

做法与渠道插件相同：交易表的 Mapper 打 `TradeTableMapper`，插件持久化配置里再加一个交易句柄上的模板和一条扫描；原模板只收其余的 Mapper。新模板沿用原模板的启动校验——工厂、数据源、事务管理器必须是同一个库。

| 插件 | Mapper | 事务与数据源 | 留在主库 |
|---|---|---|---|
| 美业 | 30 个里 13 个打交易标记（预约、预约内容与修订、预约当前指针与准备、指派、参与、工单与上下文、服务任务、服务定义与版本、服务需求） | 预约持久化、服务目录、服务定义、工单计费、服务执行结构校验点名 trade | 门店策略与版本（行业库）、服务步骤（待定）、来源观察、库存意图校验、事件发布器、设计里没去处的表 |
| 餐饮 | 89 个里 47 个打交易标记（7 个 Mapper 文件） | 23 个文件：配置里各业务 bean 与服务类的事务管理器、数据源点名 trade，裸 `@Transactional` 与 `"transactionManager"` 字面量同样改 | 库存同步与物料（库存退役）、KDS 与工位运行（行业库）、服务权益（行业库）、桌台、用餐会话、工位、门店经营策略、生产耗用、持久化与表结构校验、事件发布器与服务端出箱 |

分档依据：按每个类用到的 Mapper 与手写 SQL 的表统计。餐饮有 25 个类同时碰交易表与行业表（账单、点单、菜单、厨房任务、订位等），以交易为主的接到 trade，**换库前要拆**；多数手写 SQL 走同一个 `FoodBusinessOperationsMapper`（同时读写两边的表），它的数据源随交易。
被测试纠正的一处：`StoreOperationServiceImpl` 起初归了交易，`StoreOperationServiceTest` 报「没有 `tradeTxManager`」后核对——它主要管门店经营策略（行业库），营业日历只是顺带，整类改回主库。

测试基建：美业的 3 个装配测试（`BeautyPersistenceWiringTest`、`BookingServerWiringTest`、`BeautyBm04AssemblyTest`）引入新建的 `BeautyModuleHandles`（模块句柄注册器），和渠道插件一样。
美业是单独的 Maven 工程，从本地仓库读 `shop-store-mybatis`；为不覆盖共用的 `~/.m2`，验证时把本分支的基础模块装进 scratchpad 里的临时仓库（`-Dmaven.repo.local=<临时> -Dmaven.repo.local.tail=~/.m2/repository`）。

验证（2026-11-26，本机，H2；每项都在上一提交上用同一命令对照，失败用例逐个比对）：
- 美业插件 5 个模块 269 个测试：失败 1 个，与改动前相同。
- 餐饮插件 5 个模块（`-P food-embedded,food-verify`）约 1650 个测试：0 失败，与改动前相同。
- `shop-app` 带餐饮、渠道、零售插件（`-P food-embedded,food-verify,commerce-embedded,food-commerce-embedded,retail-commerce-embedded`，`shop.food.enabled=true`）跑 `food-embedded-test` 加守卫、架构、Mapper 冒烟：失败 52 个，与改动前完全相同——都是原生 MySQL 测试与「缺餐饮迁移历史」（`fnb_flyway_history`），外加 `ArchitectureTest` 原有的 4 个。
- **没验证**：带美业或餐饮启动整个应用。两个插件都要自己的迁移历史，而它们的迁移脚本只能在 MySQL 上跑；守卫测试没有覆盖插件目录（配置类里按 bean 分了两边，整文件规则套不上）。

### 第一段 · 提交十：渠道插件其余部分（售后、履约、核销、对账、证书）

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `commerce-core/.../mapper/AfterSaleMappers.java`、`CommerceMappers.java`、`RedemptionItemMapper.java` | 4 个 Mapper 改继承 `TradeTableMapper`：售后修订、售后行关联（并进商家订单与订单行）、履约行关联（并进订单行）、核销明细（`ord_payment_redemption_item`） |

这几块的写入都经 `CommerceCommands`，事务已在提交八改成交易句柄，这里只补 Mapper 归属。
留在主库：售后关联、履约关联、核销关联与预览与冲正（「先留原表」：并入收款记录、履约单时缺列太多）、对账单与明细（账务结算库）、证书句柄。

**证书与「统一调用平台 API」**：渠道的证书加解密已经走平台密钥接口 `SecretVault`（`secret-api`，与发票、支付同一个），该接口按设计是进程内的库，刻意不包成远程接口，免得明文走网络。
渠道自己还剩一张证书句柄表 `cim_certificate_handle`（连接 → 密文引用 + 状态）。设计把它并进密钥库 `sec_secret_blob`，但现有平台接口只有封存和解开，没有「按归属查引用」。**用户 2026-11-27 定：密钥统一通过平台密钥接口处理（§8-Q12）**——句柄表并进密钥库，平台接口补「按归属查引用、作废」；渠道不再自留句柄表。改平台接口属契约变更，随第二段换表做，本提交未动代码。

验证（2026-11-26，本机，H2）：渠道插件 4 个模块，失败 2 个，都在改渠道之前的基线 3 个之内（基线里那个依时间的不稳定用例这次通过）。

### 第一段 · 提交十一：网关里碰交易表的几处

逐处核了 §2.7 列的「要退回业务模块」清单，看它到底碰了哪个库、有没有跨库同事务：

| 网关代码 | 碰的表 | 结论 |
|---|---|---|
| `InventoryDeliveryGuard` | 事件表 `sys_outbox` 联查锁量表 `prd_stock_lock`（T5） | **本提交改**：拆成两次查询，锁量经商品模块的 `StockLockMapper` 查 |
| `ExecutionStockMirrorEvidenceServiceImpl` 用的 `ExecutionRetentionMirrorMapper` | 同一个网关 Mapper 里既查 `sys_outbox` 又查 `prd_stock_lock` | **本提交改**：锁量那条移到 `StockLockMapper.fulfillmentOriginals`，网关 Mapper 只剩事件表 |
| `ShipmentSourcePortImpl` | 读订单（交易）+ 运单与发货上传（先留，归物流）；`retryUpload` 在一个事务里锁商家订单再写发货上传 | 不改：锁单用的 `StatusCasMapper` 本来就还没归交易库（提交一说明），现在同库；换表前要随物流一起拆，记 §4 |
| `LegacyCouponSourceFenceServiceImpl` | 营销券表 + 订单，结账事务里按主库数据源判「事务已开」 | 不改：迁移期代码（对照表「迁移期先留」），换表前旧券迁移须完成并下线，否则结账带旧券会失败闭合，记 §4 |
| `StockAlignmentService`、`StockReconcileService` | `prd_stock_alignment_*`、`prd_stock_sync_operation` | 不改：不归交易库（对照表 §3 补列），各自不开跨库事务 |
| `payment_collection_*` 五个类 | 收款记录与代收来源 + 平台门店 | 不改：表「先留原表」，随 `ord_payment` 并入再退回 |

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-core/.../product/mapper/ProductMappers.java` | `StockLockMapper` 加 `fulfillmentOriginals`、`lockNosHoldingSku` 两个只读查询 |
| 修改 | `shop-gateway/.../invbridge/InventoryDeliveryGuard.java` | 先查未送达的库存镜像事件，再按事件的聚合号问锁量表；判定规则不变 |
| 修改 | `shop-gateway/.../invbridge/impl/ExecutionStockMirrorEvidenceServiceImpl.java`、`mapper/ExecutionRetentionMirrorMapper.java` | 履约归属改从 `StockLockMapper` 取 |
| 删除 | `shop-gateway/.../invbridge/entity/ExecutionRetentionMirrorOwnership.java` | 改用 `PrdStockLock` 后无人用 |
| 修改 | `shop-app/src/test/.../invbridge/InventoryDeliveryGuardTest.java` | 锁量经 Mapper 取，断言不变 |
| 修改 | `docs/technical/零售域-旧表新表对照.md` | §3 补列漏掉的三张库存对账表 |

验证（2026-11-27，本机，H2）：`InventoryDeliveryGuardTest`、`ExecutionStockRetentionExhaustionTest`、`InventorySharedSourceTest`、`ArchitectureTest`、`ModuleTxManagerConventionTest`、`MapperSmokeTest` 共 35 个，0 失败、1 跳过（`ArchitectureTest` 原有）。
**没验证**：履约归属那条查询的真实路径在 `DualExecutionStockRetentionIT`（28 个），它要 MySQL，本机整类跳过。

### 第二段准备 · 提交十二：行业库句柄

照交易句柄的做法建 `industry` 句柄（ADR-066），不改表。未绑定时 `industryDataSource`、`industrySqlSessionFactory`、`industryTxManager` 都是主库的别名，行为不变。

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-store-mybatis/.../ModuleDataSources.java` | 加 `INDUSTRY` 与三个句柄名，进 `MODULES`（注册器与守卫自动覆盖） |
| 新增 | `shop-store-mybatis/.../IndustryTableMapper.java` | 标记接口 |
| 修改 | `shop-app/.../MybatisPlusConfig.java` | 全局扫描按标记排除 |
| 修改 | `food-core/.../FoodPersistenceConfiguration.java`、`beauty-core/.../BeautyPersistenceConfiguration.java` | 各加一个行业扫描和行业模板（同样校验工厂、数据源、事务管理器是同一个库）；主库扫描同时排除两种标记 |
| 修改 | 餐饮 `FoodMappers`、`BottleCustodyMappers` | 10 个 Mapper 改继承 `IndustryTableMapper`：门店策略、桌台、区域、工位、上菜道次、出品单、服务事件（原在主库）；排队、寄存、寄存流水（原在交易句柄，按方案移到行业库）。`ConfigRevisionMapper` 改用行业模板 |
| 修改 | 餐饮 `FoodKdsAutoConfiguration`、`BottleCustodyAutoConfiguration` | 厨显、工位运行态两个 JDBC 类改用行业数据源；厨显、工位运行态、寄存三处事务改点名 `industryTxManager`（它们只写行业库的表） |
| 修改 | 美业 `BeautyMappers`、`StorePolicyServiceImpl` | 门店策略与版本两个 Mapper 改继承 `IndustryTableMapper`，门店策略服务的事务点名行业句柄 |

**没改的**：
- 美业服务执行（工单、任务、步骤、绑定、提交、参与）：随「美业拆事务」一起改，要先把 `AppointmentPersistence` 拆开。
- 餐饮人头权益、宴会、服务交接与责任：在 `ServiceEntitlementMapper`、`FoodBusinessOperationsMapper` 这两个 JDBC 类里，同一个类又写交易表，要先拆类。

**跨库写的点（行业库句柄绑定之前要拆开）**：这批行业库的表大多和交易表在同一个事务里写，现在同库，所以没问题；绑定以后就是跨库写。

| 服务 | 同一个事务里 | 拆法 |
|---|---|---|
| `QueueServiceImpl` | 叫号入座：改排队号的同时开到店单（`CheckService.openQueuedCheck`） | 先开到店单（交易），再改排队号（行业），中途失败按排队号补做。建议稿 C1 原写「不和到店单同写」，有误，已更正 |
| `CheckServiceImpl`、`CheckCartServiceImpl`、`ReservationServiceImpl`、`TableServiceImpl` | 开台、订位：到店单、占台、座位（交易）+ 桌台状态（行业） | 桌台状态改成读交易的占台推出，或提交后同步 |
| `KitchenTaskServiceImpl`、`KitchenReturnServiceImpl`、`ProductionConsumptionServiceImpl`、`BusinessCloseServiceImpl` | 厨房任务、订单批次（交易）+ 出品单、服务事件（行业） | 出品单与服务事件改成提交后写，或随厨房任务留交易库（要回设计改出品单去向） |
| `ConfigRevisionServiceImpl`、`MenuWithdrawalSourceServiceImpl` | 菜单（交易）+ 配置修订（行业） | 先发布菜单，再写修订 |
| `StationServiceImpl`、`StoreOperationServiceImpl`、`SalesRestrictionServiceImpl` 等 | 工位（行业）+ 菜品工位（交易）、营业日历（交易）；门店策略多数是只读 | 只读的不用改；写的按先后两步 |

验证（2026-11-27，本机，H2）：
- 后端 `ArchitectureTest`、`ModuleTxManagerConventionTest`、`ModuleDataSourceRegistrarTest`、`MapperSmokeTest`：0 失败（`ArchitectureTest` 原有 1 个跳过）。
- 餐饮插件（`food-embedded,food-verify`）：71 个，1 个错误。是 `FoodStandaloneLoggingTest` 等日志滚动时超时，单独重跑通过；改动前的基线是 0 失败。
- 美业插件：5 个模块，只有 `BeautyBm04AssemblyTest` 1 个错误，和基线相同（夹具读主迁移文件，路径不存在）。
- **没验证**：行业句柄绑到另一个数据源的情形；带餐饮或美业插件起整个应用（插件迁移只能在 MySQL 上跑）。

### 第二段准备 · 提交十三：美业服务执行接行业句柄（拆事务第一步）

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `beauty-core/.../mapper/BeautyMappers.java` | 12 个服务执行的 Mapper 改继承 `IndustryTableMapper`：工单、工单上下文、服务任务、服务参与（原在交易句柄），服务步骤、服务提交、任务绑定、分配围栏、来源观察、执行围栏、到店认领、到店核验（原在主库）。预约时的派工 `AppointmentAssignmentMapper` 留在交易句柄：它是预约的一部分，跟预约一起建；方案里的「指定技师」本来就记在预约明细上 |
| 修改 | `beauty-core/.../service/impl/AppointmentPersistence.java`、`BeautyAppointmentConfiguration` | 加一个行业事务 `industryTx`（同样是新开事务、读已提交），配置里交易、行业两个事务管理器分开给；原构造器保留（测试用，两边共用一个管理器） |
| 修改 | 美业 5 个服务类 | 只写行业表的 14 处事务从 `db.tx` 改成 `db.industryTx`：到店认领、到店核验、服务提交（4 处）、来源绑定、服务执行（7 处） |
| 修改 | `BeautySourceConfiguration` | 工单结账 `WorkOrderBillServiceImpl`、来源观察 `ServiceSourceObservation` 的事务改点名行业句柄（它们写的是提交、绑定、观察；结账经 `CheckoutPort` 调交易，接口自己开事务） |

分类方法：按每个事务体（含同一个类里调用到的方法）写了哪些 Mapper 来判；跨类调用没有展开，所以「只写行业表」是静态判断。行业句柄未绑定时这些改动不改变行为。

验证（2026-11-27，本机，H2）：美业插件 5 个模块，只有 `BeautyBm04AssemblyTest` 1 个错误，和基线相同。

### 第二段准备 · 提交十四：美业开工单拆事务（拆事务第二步）

用户 2026-11-27 定：给美业预约加工单号并拆开；**接待操作账本 `bty_reception_operation` 放行业库**（与工单同库，开单的最后一步与「操作成功」同一个事务）。

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `beauty-core/.../db/industry/beauty/V9_1__beauty_appointment_work_order_no.sql` | 预约加 `work_order_no` 与 `(tenant_no, entity_no, work_order_no)` 唯一约束，并把已有的一预约一工单回填。编号 9.1 排在可选扩展 V10–V11 之前：编成 V12 会让没装扩展的库无法单独补上它 |
| 修改 | `BeautySchemaValidator` | V9.1 进必需的基础迁移；核对脚本全文与摘要，往预约的表契约里加这一列与唯一约束；显式迁移时允许乱序（已装 V10–V11 的库要在它们之后补 V9.1） |
| 修改 | `BeautyMappers` | 预约侧 4 条改看新列：`markWorkOrderOpened` 同时写工单号、`countWorkOrders`、资源围栏 `acquire`、资源意图 `authorize`；执行侧 3 条去掉对预约明细的联查；新增 `releaseWorkOrderClaim`（撤销开单标记）、`countForAppointmentItems`（孤儿任务预检）；`affectedTaskNos` 从预约 Mapper 挪到任务 Mapper；接待操作 Mapper 改继承 `IndustryTableMapper` |
| 修改 | `WorkOrderCoordinator` | 开工单改三步：①行业事务在接待操作上预留工单号并预检孤儿任务；②交易事务锁预约、校验、在预约上记这个号（已记过就跳过校验；走入单越过开始时间则撤销标记、接待操作记成需重新排期）；③行业事务建工单、任务、上下文、开单提交并把接待操作记成成功。任一步失败可重跑 |
| 修改 | `AppointmentPersistence`、`WorkOrderQuery` | 查工单详情时按预约明细核对任务归属（读出来再过滤，不跨库联查） |
| 修改 | 测试 | 6 个集成测试的上下文补上模块句柄（此前美业集成测试全部起不来，是提交九漏的）；迁移测试的「基础版本」改成 9.1；两条用例按新约定改：已有工单的预约要带工单号；外部记账失败时预约上的开单标记已提交、工单回滚，重试补建同一个号 |

验证（2026-11-27，本机 MySQL 9.7.4，美业 22 个集成测试类 + 单元测试）：
- 单元测试只有已知的 `BeautyBm04AssemblyTest` 1 个错误。
- 集成测试：拆事务引出的 7 个失败全部修好；剩下的失败和改动前的基线逐个相同，共 5 个类：
  - `ServiceSubmissionIT` 2 个（Bean 无默认构造器）；
  - `WorkOrderScopeIT` 1 个（旧上下文 NOT_FOUND）；
  - `ServiceSourceObservationIT`、`ManualExecutionSourceIT`（读不到主迁移文件）；
  - `ResourceIntentMigrationIT` 1 个（表数断言还是 28）；
  - `BeautyPolicyPublicationIT` 1 个（数据源无法解包）。
- 并发用例 `concurrentSameEndpointKeyReplaysOneCommittedSubmission` 在第一次全量里失败一次，再跑通过，属不稳定用例。

**还没拆的**：
- 接待的其他流程：签到、改约、走入登记（`ReceptionCoordinator`、`ReceptionPreparation`、`ReceptionRecovery`、`AppointmentPreparation`）仍在一个交易事务里同时写预约和接待操作。行业句柄绑定前要按开工单的做法拆：先在行业库记操作，再改预约，最后在行业库收尾。
- 接待操作的 SQL `selectOpenedForWorkOrder` 联查工单与上下文，现在同在行业库，没问题。
- 执行围栏与耗材预占 `ServiceExecutionStockIntentMapper.observe` 联查行业表与库存意图表（库存库），随库存收口再拆。
- V9.1 带一条回填 `UPDATE`。美业以往的迁移刻意不改写数据，这一条是例外：回填只是把一预约一工单的已知事实抄到预约上，没有它，已开单的预约上护栏就失效了。

### 偏差说明

- `tradeJdbcTemplate` 放进了已有的 `PlatformJdbcConfiguration`，而不是 §2.2 写的新类 `TradeJdbcConfiguration`：主库那个 `JdbcTemplate` 的 `@Primary` 声明在这个类里，模块的模板放在一处更不容易漏。类名以后可改成 `ModuleJdbcConfiguration`。
- §2.2 写的 `IndustryTableMapper` 与 `industry` 句柄没有建：行业库的代码都在插件里，随插件那一步一起加，免得先放一个没人用的句柄。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-26 | 勘查与设计完成，待确认 §8 |
| 2026-11-26 | 用户定 Q1：交易核心在分支上全部改完再一次切换 |
| 2026-11-26 | 用户定 Q3：状态取值改短 |
| 2026-11-26 | 用户定 Q2：交易不用事件，提交后直接调用；积分暂不接 |
| 2026-11-26 | 第一段提交一实现（交易句柄与 71 个 Mapper 的归属）并提交到 retail-align |
| 2026-11-26 | 第一段提交二实现（商品包 22 个类的事务点名交易句柄） |
| 2026-11-26 | 第一段提交三实现（商户域资源预约、班表、门店分类 8 个类点名交易句柄） |
| 2026-11-26 | 修资源占用结构校验在 MySQL 上永远判损坏的缺陷与场景测试夹具，资源、班表场景测试 201/202 通过 |
| 2026-11-26 | 第一段提交五实现（交易包 66 个文件点名交易句柄） |
| 2026-11-26 | 第一段提交六实现（履约、自提点、求购报价），与提交五一起提交 |
| 2026-11-26 | 第一段提交七实现（零售插件接交易句柄） |
| 2026-11-26 | 用户定：商品发布不进任务库；密钥统一调平台 API |
| 2026-11-26 | 第一段提交八实现（渠道插件主流程） |
| 2026-11-26 | 第一段提交九实现（美业、餐饮插件），与七、八一起提交 |
| 2026-11-26 | 第一段提交十实现（渠道插件其余部分），未提交 |
| 2026-11-27 | 用户定 Q12：密钥统一通过平台密钥接口处理，证书句柄表并进密钥库（随第二段） |
| 2026-11-27 | 第一段提交十一实现（网关碰交易表的几处：库存投递守卫拆查询，其余逐处定不改的理由），与提交十一起提交 |
| 2026-11-27 | 用户定：美业服务执行整段留行业库；按建议稿定 Q4–Q11，ADR-068 采纳（第 2 条按行业改写） |
| 2026-11-27 | 用户定：账务结算库只管支付收款；提成规则移到行业库 `ind_commission_rule`；交易库 74 → 67、行业库 14 → 24 |
| 2026-11-27 | 状态短值换值表初版；用户定状态值只从枚举与静态常量取 |
| 2026-11-27 | 第二段准备提交十二：行业库句柄，餐饮 10 个、美业 2 个 Mapper 与 4 处只写行业表的事务接上；跨库写的点记表 |
| 2026-11-27 | 第二段准备提交十三：美业服务执行 12 个 Mapper 与 16 处事务接行业句柄 |
| 2026-11-27 | 用户定：给美业预约加工单号并拆开开工单；接待操作账本放行业库。提交十四实现（V9.1、开单三步、8 条跨库 SQL） |

## §8 待确认

2026-11-27：Q4–Q11 按[建议稿](零售域-第二段建议方案与表清单.md)全部定下；表数量随之调整为 `n_trade` 67 张、`n_industry` 24 张（建议稿 B、C 节），设计方案拥有者据此改生成器。

| # | 问题 | 建议 |
|---|---|---|
| Q1 | 交易核心簇怎么推进：甲（分支上改完一次绑定）还是乙（主库就地演进、最后整库搬） | **已定：甲**（用户 2026-11-26：「在分支上全部改完」） |
| Q2 | 订单、商品的事件放哪 | **已定：不用事件**，提交后直接调用，关键下游按订单状态补做（用户 2026-11-26，§2.10） |
| Q3 | 状态取值：放宽状态列宽度（不换值），还是按设计换短值并同步改三端 | **已定：改短**（用户 2026-11-26），做法见 §2.9 |
| Q4 | 设计里没有去向的表放哪：下单意图 `ord_create_intent`、结账操作、状态日志、餐饮订单绑定与结算表头、资源命令账本、美业命令账本 | **已定（2026-11-27，按建议稿 A3）**：下单意图不建（订单行「创建中」+ 库存预占带过期）；命令账本与纯请求键走公共幂等；状态与动作日志进统一审计；结账确认并入报价单；报价修订不建表；库存桥随收口删除 |
| Q5 | 先吃后付「商家订单先于支付单、到店单汇总成一张支付单」要不要做 | **已定（2026-11-27）：这次不做**，另起 TDD；对齐只填 `ord_sub_order.visit_no` |
| Q6 | 渠道请求流水要「永久、重放返回首次结果」，公共幂等只有 24 小时：补公共能力，还是渠道保留自己的流水 | **已定（2026-11-27）：补公共能力**，保留期按调用方配置；渠道不留自己的流水表 |
| Q7 | 美业服务步骤 `bty_service_step` 留行业库还是并回交易库 | **已定（2026-11-27）：留行业库，美业服务执行整段留行业库**（工单、任务、步骤、绑定、提交、参与），见建议稿 A6；ADR-068 第 2 条随之改写 |
| Q8 | 设计缺陷（§2.6）由谁修：建库会话 / 设计方案拥有者 | **已定（2026-11-27）：设计方案拥有者修生成器**；改单是[建议稿](零售域-第二段建议方案与表清单.md) B 节与附录一 |
| Q9 | 试点：先切社区团购与询价（S8），验证建表、迁数、绑定、回退全流程 | **已定（2026-11-27）：同意** |
| Q10 | 券与活动限量改「预占—确认」两阶段，接受吗 | **已定（2026-11-27）：接受**；预占行在营销库，交易分摊行已有所需列 |
| Q12 | 渠道证书句柄表并进密钥库：给平台密钥接口加「按归属查引用」，还是保留渠道自己的句柄表（加解密已经走平台接口） | **已定（2026-11-27）：密钥统一通过平台密钥接口处理**。句柄表并进 `sec_secret_blob`（归属用 `owner_type`=渠道连接、`owner_id`），平台接口补「按归属查引用」与「作废」；跨店校验由归属参与附加认证数据兜底。随第二段做 |
| Q11 | 积分暂不接（用户 2026-11-26 定）。切换上线时：先接回积分，还是这段时间不提供积分、三端隐藏入口 | **已定（2026-11-27）：切换前接回**，走支付库积分接口 |
