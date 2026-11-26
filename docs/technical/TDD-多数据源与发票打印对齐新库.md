# TDD-多数据源与发票打印对齐新库

状态：已确认（2026-11-26 用户：「可以，按建议」）· 提交一、提交二（发票）已实现 · 打印另行提交，未做
关联需求：[PRD-发票开立与打印](../requirements/PRD-发票开立与打印.md) §7 · [ADR-066](ADR/ADR-066-业务模块按配置选数据源.md)
设计依据：数据库设计方案（工作树 `ai-shop-slim`，分支 `docs/db-split-plan`，未提交）的
`02-命名与字段规范.md`、`库/12-平台服务库.md`、`schema/ddl/12-n_service.sql`
创建：2026-11-26 · 最后更新：2026-11-26

## 一句话

分两次提交：第一次让“每个业务模块在配置里选自己的数据源，不配就是主库”，不改任何表；
第二次把发票、打印的代码切到平台服务库 `n_service` 的新表。勘查后发现第二次**不是改名**：
现有代码用 22 张发票表、27 张打印表，新库只给了 13 张和 10 张，差出来的表的去向要先定，见 §2.4、§2.5 和 §8。

## §0 对账一 · 需求 → 设计

验收标准见 PRD §7。

| AC | 需求（一句话） | 落点 |
|---|---|---|
| M1 | 不写任何新配置时，启动与全部行为和现在一样 | `ModuleDataSourceRegistrar`：未绑定的模块句柄是主库 bean 的别名 |
| M2 | 给模块绑一个具名数据源后，它的 Mapper 与事务都落在那个库 | 具名数据源的三件套 + 模块 `@MapperScan(sqlSessionFactoryRef)` + 全局扫描排除 |
| M3 | 绑到同一个名字的两个模块可以同事务；不同名字的不能 | 同名共用一个 `DataSourceTransactionManager` |
| M4 | 模块里的事务没点名自己的句柄就过不了闸门 | `ModuleTxManagerConventionTest`（读源码） |
| M5 | 新数据源上的数据域、分页、乐观锁、逻辑删除、公共字段填充与主库一致 | 工厂构造复用 `PrimaryPersistenceConfiguration` 的同一段装配 |
| S1 | 发票绑到 `n_service` 后，PRD AC1–AC43 中已实现的条目行为不变 | 发票 mapper → domain → service → controller → api 逐层对齐（§2.4） |
| S2 | 打印绑到 `n_service` 后，现有打印验收行为不变 | 打印同上（§2.5） |
| S3 | 发票、打印不在自己的事务里写别的库，也不靠别的库的行锁 | §2.3 跨库清单逐处改写 |
| S4 | 新表的公共字段、状态取值（≤10 字符）符合规范 | 实体基类 `ServiceRow` + 状态换值表（§2.4、§2.5） |

**孤立项**
- 没落点的 AC：无。
- 挂不上 AC 的设计：无。§8 的待定项定下来之前，S1、S2 的落点只到“表的去向”这一层，定了之后补到类。

## §1 现状与影响面

### 1.1 多数据源现在怎么接的

| 模块 | 写法 | 位置 |
|---|---|---|
| 主库 | 手写 `@Primary` 的数据源、工厂、模板、事务管理器；拦截器顺序：数据域 → 分页 → 乐观锁 | `shop-store-mybatis/.../PrimaryPersistenceConfiguration.java` |
| 支付、密钥 | 同一个库，单独的连接池、事务管理器、工厂；复用平台的拦截器 | `shop-app/.../config/PayDataSourceConfig.java`、`SecretDataSourceConfig.java` |
| 库存、任务、报表 | 独立库，各一份手写配置；开了开关不配地址就启动失败 | `InventoryDataSourceConfig`、`JobStoreConfig`、`ReportDataSourceConfig` |
| 网关三端 | 配了用自己的库，不配回主库（仓库里唯一一处） | `shop-gateway/.../GatewayEndRuntimes.java` |
| 发票、打印 | 没有自己的数据源。Mapper 被全局扫描（`ai.neargo.shop`）绑在主库工厂上，事务用主库的 | `shop-app/.../config/MybatisPlusConfig.java:34` |

可直接复用：支付那一套的“工厂复用平台拦截器、迁移返回凭证而不是 `Flyway`”，网关那一套的“不配回主库”。

### 1.2 发票、打印现在的形状

| | 发票 | 打印 |
|---|---|---|
| 代码 | `backend/invoice/invoice-domain`（91 个类）、`invoice-channel`（不碰库） | `backend/shop-core` 的 `ai.neargo.shop.printing`（99 个类）＋ `trade/order/printing`（9 个）；`backend/print/*` 不碰库 |
| 表 | 22 张 `ivc_*`，建在主库，迁移目录 `db/invoice` V1–V19 | 25 张 `prn_*` ＋ `ord_print_fact`、`biz_print_fact`，建在主库主迁移 V567–V696 |
| 访问方式 | MyBatis-Plus，字符串列名约 500 处；7 张通道表没有实体，`JdbcTemplate` 手写约 26 条 | MyBatis-Plus，字符串列名约 449 处；手写片段约 86 处（行锁、子查询里写死表名） |
| 对外 | 8 个 B 端、1 个 C 端、2 个运营端、1 个公开端的 Controller | 11 个 B 端、设备端 10 个端点、运营端、内部端（餐饮插件走它） |

### 1.3 会被改到的

- 提交一：`shop-store-mybatis`（新增注册器）、`shop-app` 的 `MybatisPlusConfig`（全局扫描多排除两个包）、
  发票与打印里所有 `@Transactional` 与 `@Qualifier("transactionManager")`（只换名字，不配时仍是主库）。
- 提交二：发票、打印的实体、Mapper、Service、DTO；`shop-app` 的 `invoicebridge`、`shop-gateway` 的 `printbridge`；
  测试用的三份建表源（`schema-test.sql`、`print-acceptance-schema.sql`、`gen-test-schema.py`）。

### 1.4 明确不受影响的

- 库存、任务、报表、支付、密钥、网关三端的数据源配置。
- 订单、收款、餐饮插件的表与代码（它们只经接口调发票、打印）。
- 192.168.3.61 上的库：本次只读，不改。
- 线上：不迁数据、不部署、不发布。

## §2 方案

### 2.1 契约变更

- 端点：提交一没有。提交二原则上不变；会变的只有状态取值（§2.4、§2.5 的换值表）和 §8 里要用户定的几处。
- 库表：提交一没有。提交二改用 `n_service` 的 `ivc_*`、`prn_*`；需要补的索引与字段见 §2.7，**都只是建议，未执行**。
- 权限码、i18n：没有。
- 配置项（提交一新增）：

```yaml
shop:
  datasources:              # 具名数据源；整段不写 = 只有主库
    service:
      url: jdbc:mysql://127.0.0.1:3306/n_service?...
      username: ${SHOP_SERVICE_DB_USER}
      password: ${SHOP_SERVICE_DB_PASS}   # 不带默认值，漏配启动失败
      max-pool-size: 8
  modules:                  # 模块绑定；不写 = 主库
    invoice: { datasource: service }
    print:   { datasource: service }
```

绑定到一个没定义的名字、或定义了名字却没写地址：启动失败并指出是哪一项。

### 2.2 模块设计 · 提交一

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-store-mybatis/.../common/config/ModuleDataSourceRegistrar.java` | 读上面两段配置；为每个具名数据源注册 `<名>DataSource`（池名 `<名>-pool`）、`<名>SqlSessionFactory`、`<名>TxManager`；为每个登记的模块注册三个别名 |
| 新增 | `shop-store-mybatis/.../common/config/ModuleDataSources.java` | 登记有哪些模块（本次 `invoice`、`print`）与句柄命名规则；一处定义，守卫测试也读它 |
| 修改 | `shop-store-mybatis/.../PrimaryPersistenceConfiguration.java` | 把“建工厂”那一段抽成可复用的方法，新工厂用同一段（拦截器、填充器、逻辑删除、驼峰、方言） |
| 修改 | `shop-app/.../config/MybatisPlusConfig.java` | 全局扫描排除发票与打印的 Mapper 包 |
| 新增 | `shop-app/.../config/InvoiceModuleConfig.java`、`PrintModuleConfig.java` | 各自的 `@MapperScan(..., sqlSessionFactoryRef = "<模块>SqlSessionFactory")` |
| 修改 | 发票、打印里全部事务声明 | `@Transactional` → 点名 `invoiceTxManager` / `printTxManager`；`@Qualifier("transactionManager")` 同样换名 |
| 修改 | `invoice/.../InvoiceChannelServiceImpl.java` | `JdbcTemplate` 改为由 `invoiceDataSource` 构造 |
| 新增 | `shop-app/src/test/.../arch/ModuleTxManagerConventionTest.java` | 读源码：模块包里的事务必须点名本模块句柄 |
| 新增 | `shop-store-mybatis/src/test/.../ModuleDataSourceRegistrarTest.java` | M1–M3、M5 |

打印模块的包范围：`ai.neargo.shop.printing..`。`trade/order/printing` 里的订单打印事实属于订单，留在主库；
其中 `biz_print_fact` 在新设计里归打印（`prn_print_fact`），提交二再搬。

### 2.3 事务边界：原来同事务、切库后不同事务的地方

提交一不配绑定，这些地方行为不变；提交二绑定之前必须逐处改完。

| # | 现在 | 位置 | 切库后的写法 | 失败后怎么恢复 |
|---|---|---|---|---|
| T1 | 开票、折让在发票事务里往 `sys_outbox` 写“打印事实已就绪” | `InvoiceServiceImpl` :295、:543 | 发票与打印同库同事务管理器，改成事务里直接调打印的入口（`PrintFacade.onFact`），不经事件 | 同一个事务，一起成败 |
| T2 | 开票取数时对订单行 `FOR UPDATE`，和号段锁同事务 | `SubOrderInvoiceSourcePortImpl` :89 | 取数只读不加锁；同一来源只有一张有效发票靠 `uk_ivc_invoice_active_source`；申请版本上已冻结来源金额与指纹，开立时比对 | 订单在取数之后变了：指纹对不上，拒绝并提示重新提交 |
| T3 | 补开票资料在订单事务里顺手写买方偏好 | `StoreOrderServiceImpl` :376 | 两个事务。偏好是“以最后一次为准”的可覆盖数据，先写偏好再提交订单 | 订单回滚而偏好已写：下次补资料覆盖，无害 |
| T4 | 现金退款在订单事务里读“这单有没有有效发票” | `StoreOrderSettlementServiceImpl` :117 | 跨库只读，不变 | 读到的是已提交的发票；读完之后才开出的票由开立侧的指纹比对拦住 |
| T5 | 打印回执、告警在打印事务里往 `sys_outbox` 写“任务完成”“批次已解决”“告警” | `PrintReceiptServiceImpl` :264、:369；`PrintIncidentServiceImpl` :81、:108；`PrintServiceImpl#publish` | 打印事务提交之后再发。接收方是通知桥，丢一条的后果是少一条提醒 | 不补发；状态以打印表为准，页面查询不受影响。**要用户确认可以接受**（§8-Q6） |
| T6 | 订单、售后在自己的事务里写 `biz_print_fact` 并发事件 | `BusinessPrintFactServiceImpl` | 打印事实归打印后：业务提交之后发事件，打印侧收到事件时自己取数冻结 | 事件丢了：现有对账任务 `PrintReconcileJob` 按来源扫描补齐（提交二里要验证它覆盖这条路） |
| T7 | 发票在事务里调密钥库封存凭据 | `InvoiceSetupServiceImpl` :149 等 | 不变。密钥库今天就是独立的事务管理器，已经不是一起提交 | 现状如此 |

### 2.4 发票：22 张旧表的去向

**甲 · 新库里有对应的表（13 张）**。这些按 mapper → domain → service → controller → api 依次对齐。

| 新表 | 来自 | 要改的 |
|---|---|---|
| `ivc_regime_profile` | 同名 ＋ `ivc_channel_route`（当前账户、路由版本、环境并进来） | 路由的读写改到这张表；`provider_secret_ref`、`qr_key_ref` 仍是整数引用（§2.7-F3） |
| `ivc_channel_account` | 同名 ＋ `ivc_channel_account_revision`（只留当前版） | 手写 SQL 改为实体与 Mapper；审核单号 `review_no` |
| `ivc_number_range`、`ivc_store_item`、`ivc_buyer_preference`、`ivc_user_carrier` | 同名 | 字段与长度 |
| `ivc_invoice_application`、`_revision` | 同名 | 驳回原因、操作人直接在申请上 |
| `ivc_invoice`、`ivc_invoice_line`、`ivc_allowance` | 同名 | 字段与长度 |
| `ivc_transmission`、`ivc_transmission_attempt` | 同名 | 状态 `NEEDS_REVIEW` 超长，要换值 |

公共字段：新增实体基类 `ServiceRow`（`id`、`createdAt`、`createdBy`、`updatedAt`、`updatedBy`），
`updated_at` 交给数据库自动更新，代码不再写它。

状态换值（超过 10 个字符的）：

| 字段 | 现在 | 建议 | 影响 |
|---|---|---|---|
| `ivc_transmission.status` | `NEEDS_REVIEW` | `REVIEW` | B 端、运营端接口的取值；App 的 `upload-backlog` 用的是计数字段 `needsReview`，不受影响 |

**乙 · 新库里没有、去向在别处的表（9 张）**

| 旧表 | 设计给的去向 | 那边的代码现状 | 本次建议 |
|---|---|---|---|
| `ivc_channel_application`、`_revision`、`_event` | 审核与工单服务，业务表只存 `review_no` | 仓库里有 `spi.approval.ApprovalPort` 与 `shop-opskit` 的实现，发票没接 | **Q1** |
| `ivc_channel_account_revision`（历史版）、`ivc_channel_route_event`、`ivc_invoice_application_event` | 统一审计 `sys_audit_log`（基础能力库） | 新的统一审计没有代码；B 端 `/events`、`/timeline`、App 的 I07 在读这些历史 | **Q2** |
| `ivc_invoice_delivery` | 通知的 `notify_message`、`notify_delivery` | 通知模块还没对齐新库 | 留在主库原表不动，等通知模块对齐 |
| `ivc_store_setting` | **设计里没有这张表** | B 端 `store-settings`、App 的 I01 在用 | **Q3** |

另外，申请命令的防重现在靠 `ivc_invoice_application_event` 上的唯一键（同一命令键只记一次）。
按“防重是应用层的事”，改成靠申请上的 `current_revision` 比对（接口本来就要求带 `expectedRevision`）加业务唯一键。

### 2.4.1 提交二（发票）的实施清单

用户确认后的落地做法。发票切换后**必须**绑定到新结构的库，不再能在旧表上运行。

| # | 做什么 | 位置 |
|---|---|---|
| 1 | Mapper 分两处：`invoice.mapper` 绑发票句柄（13 张 ＋ `ivc_store_setting`）；新包 `invoice.legacy` 绑主库（申请事件、邮件投递、通道申请三张、账户历史版、路由事件） | `ModuleMapperScanConfig`、`invoice/legacy/*` |
| 2 | 历史记录（申请事件、路由事件、账户历史版）改为发票事务提交之后写主库；写失败只记日志，不影响已提交的业务 | `invoice/legacy/InvoiceHistory` |
| 3 | 申请命令的重放：先查事件（主库）；事件缺失时按业务状态兜底——同来源同买方的重复提交回原申请，已开立的重复开立回原发票 | `InvoiceApplicationServiceImpl` |
| 4 | 路由并进 `ivc_regime_profile`（`active_account_no`、`route_version`、`environment`）；一行只有一个环境的路由 | `InvoiceChannelServiceImpl` |
| 5 | 通道账户只留当前版：`sec_key_id` 存服务商凭据引用，`qr_sec_key_id`（补充字段）存 QR 金钥引用，`review_no` 存开通申请号，卖方名称取自税制行，核验时间取账户的更新时间 | 同上 |
| 6 | 通道申请的提交、补件、审核只碰主库三张表，用主库事务；核验并绑定分三段：读申请 → 发票库事务里建或更新账户 → 主库事务里记历史版、置为已绑定 | 同上 |
| 7 | 开票、折让的“打印事实已就绪”事件改为发票事务提交后再发（打印还在主库，T1 的“同事务直调”等打印切库后再做） | `InvoiceServiceImpl` |
| 8 | 邮件投递整段用主库事务，跨库只读发票 | `InvoiceDeliveryServiceImpl` |
| 9 | 上传任务状态 `NEEDS_REVIEW` → `REVIEW`，后端、运营端、商户端、共享契约、接口文档一起改 | 各端 |
| 10 | 编号长度：通道申请号、账户号由 35 位收到 32 位 | `InvoiceChannelServiceImpl#number` |
| 11 | 实体补 `createdBy`、`updatedBy` | `invoice/entity/*` |
| 12 | 测试：`InvoiceServiceMysqlTest` 用两个库（主库跑旧迁移，发票库用设计的建表语句 ＋ 补充脚本）；shop-app 的 H2 测试给发票单独一个内存库 | 测试资源 |
| 13 | 默认配置把发票绑到 `service`，地址与口令不带默认值，漏配启动失败 | `application.yml` |

补充脚本 `n_service-ivc-supplement.sql`（放测试资源，同时就是给 61 的修改建议，**未在 61 执行**）：
F1 里发票三处唯一索引；`ivc_channel_account.qr_sec_key_id`；新表 `ivc_store_setting`。

### 2.5 打印：27 张旧表的去向

**甲 · 新库里有对应的表（10 张）**

| 新表 | 来自 | 难点 |
|---|---|---|
| `prn_printer` | `prn_printer` ＋ `prn_device` ＋ `prn_printer_runtime` | `prn_device` 现在同时是门店串行锁、设备在途闸门、旧协议主表；每个打印命令都先锁它。并表后锁的对象和顺序要重排 |
| `prn_template_catalog` | 头表 ＋ `prn_template_version` ＋ 餐饮旧表 `prn_template` | 从“头 + 版本”改成“每行一版”；“当前版”从指针改成按状态与生效时间取 |
| `prn_route_catalog`、`prn_route_rule` | 头表 ＋ `prn_route_version` ＋ 餐饮旧表 `prn_route` | 规则从一段 JSON 拆成行 |
| `prn_scene_policy` | 同名 ＋ `prn_policy_version` | 历史版本没有了；`/scene-policies/{scene}/versions` 接口失去来源 |
| `prn_print_fact` | `biz_print_fact`（不含 `ord_print_fact`） | 归属从订单侧搬到打印侧，见 T6 |
| `prn_request` | 同名 ＋ `prn_simulation_run` | 模拟的认领与租约字段新表没有 |
| `prn_batch` | 同名 | 小 |
| `prn_job` | 同名 | 状态 `DISPATCHING` → `SENDING`（设计已定）；去重键新表没有 |
| `prn_attempt` | 同名 ＋ `prn_receipt` | 回执原来是独立收件箱，有自己的去重键与状态；新表没有 `dispatch_key`，而设备端接口用它寻址 |

状态换值（超过 10 个字符的）：

| 字段 | 现在 | 建议 |
|---|---|---|
| `prn_job.status` | `DISPATCHING` | `SENDING`（设计已定） |
| `prn_attempt.status`、`reported_status` | `NOT_ACCEPTED` | `REFUSED` |

设备端代理按这些值上报，代理程序要一起发版，或在设备端接口上保留旧值的入口翻译。

**乙 · 新库里没有、去向在别处的表（9 张）**

| 旧表 | 设计给的去向 | 现状 | 本次建议 |
|---|---|---|---|
| `prn_agent` | 平台基础库的门店设备 `mch_store_device` | 平台基础库没有代码；设备端 10 个接口靠它认证 | **Q4** |
| `prn_credential_version`（存密文） | 密钥库 | `secret` 模块的 `SecretVault` 可用，发票已经在用 | 提交二里改走 `SecretVault`，`credential_ref` 存引用 |
| `prn_operation` | 无（防重归应用层） | 每个写命令的幂等台账，也是 `GET /biz/print/operations/{no}` 的数据来源 | **Q5** |
| `prn_action_log`、`prn_store_stream` | 统一审计 | B 端 `/action-logs`、`/changes` 在读 | 同 **Q2** |
| `prn_incident` | 风控的 `risk_event` | 风控模块没对齐 | 留在主库原表，等风控对齐 |
| `prn_reconcile_cursor` | 任务库的游标 | 任务模块没对齐 | 留在主库原表 |
| `prn_export_task` | `file_export`（同在平台服务库） | 表已在新库 | 提交二里一并切过去 |

### 2.6 新库的结构由谁管

`n_service` 的表由设计方案的建表语句建，本次**不给它加 Flyway**：新库还在定稿过程中，
现在加版本管理只会把每次设计调整变成一次迁移。测试用同一份建表语句在本机 MySQL 建库。
设计定稿后再给各新库统一接入。

### 2.7 字段草案里发现的问题（只记录，未改任何库）

**F1 · 代码依赖唯一性，而新库里降成了普通索引**

| 表 | 索引 | 代码怎么依赖 |
|---|---|---|
| `ivc_invoice_application` | `uk_ivc_application_source`、`uk_ivc_application_no` | 占位写入用 `ON DUPLICATE KEY`；不是唯一键就会一单多申请 |
| `ivc_regime_profile` | `uk_ivc_regime_profile` | 一个主体一种税制一行 |
| `prn_request` | `uk_prn_request_trigger`、`uk_prn_request_no` | 同一触发只生成一次请求，是并发下的最后防线 |
| `prn_printer` | `uk_prn_printer_no`、`uk_prn_physical` | 同一台物理打印机不重复登记 |
| `prn_attempt` | `uk_prn_attempt_no`、`_index`、`_sequence`、`_provider` | 回执按服务商引用找到唯一一次投递 |
| `prn_print_fact` | 编号、来源键、小票意图键 | 同一业务终态只冻结一份 |
| `prn_template_catalog`、`prn_route_catalog`、`prn_scene_policy` | 编号与范围键 | 每行一版之后，范围键要带上版本号才能唯一 |
| `prn_route_rule` | 没有任何索引，也没有租户号 | 至少要 `(route_no, version_no, rule_seq)` 唯一 |

建议：上表全部恢复或补成唯一索引。**需要改 61 上已建的表，等用户授权。**

**F2 · 缺字段**

| 表 | 缺 | 谁在用 |
|---|---|---|
| `prn_printer`、`prn_request`、`prn_batch`、模板、路由、策略 | 乐观锁 `version` | 接口契约里的 `expectedRevision`（App 的重试、补印都带） |
| `prn_attempt` | `dispatch_key` | 设备端接口 `/dispatches/{dispatchKey}` |
| `prn_printer` | 租约令牌摘要、围栏序号 | 投递权。设计的待确认事项里已记“和技术件不入库冲突”，没有结论 |
| `prn_request` | 模拟的认领与租约 | 模拟执行器 |
| `ivc_channel_account` | QR 金钥引用 | 证明联左码加密 |

**F3 · 类型或长度**

| 项 | 问题 |
|---|---|
| 密钥引用 | 规范是“密钥编号 `varchar(32)`”；`ivc_regime_profile.provider_secret_ref`、`qr_key_ref` 仍是 `bigint`，`ivc_channel_account.sec_key_id` 是 `varchar(32)`，同一库两种写法。现有 `SecretVault` 返回的是整数 |
| 主体号、门店号、订单号 64 → 32 | 主迁移 V585 专门把打印表的这三列放宽到 64，说明有超过 32 的值。切之前要查现有数据的最大长度 |
| `ivc_invoice.source_no`、`carrier_id` 64 → 32 | 会员载具号是登录账号，可能超过 32 |
| `prn_printer.physical_key` 192 → 128 | 要查现有最大长度 |

**F4 · 落地发票时新发现的（2026-11-26）**

| 项 | 问题 | 本次怎么处理 |
|---|---|---|
| 非空列丢了默认值 | 旧表有默认值、新表是非空无默认的 15 列：`ivc_transmission` 的 `attempts`、`attempt_sequence`、`deadline_level`；`ivc_invoice` 的 `zero_tax_sales_minor`、`free_tax_sales_minor`、`allowed_minor`、`revoke_alerted`；`ivc_allowance.status`；`ivc_number_range.allocation_owner`；`ivc_store_item` 的 `sort`、`enabled`；`ivc_user_carrier.is_default`；`ivc_regime_profile` 的 `auto_issue`、`range_low_water`、`auto_fetch_ranges`（后三个新表可空） | 初值写在实体上，由代码给出。建议设计把默认值加回去 |
| CHECK 约束全部没有了 | 号段上下界、发票金额等式（已折让 ≤ 总计）、折让金额、快照是合法 JSON、门店设置的取值 | 代码里本来就有对应的条件更新与校验，没有新增；库这一层的兜底没了 |
| `created_at` 只到秒 | 旧表是毫秒；MySQL 写入时四舍五入 | 业务计时用的是各自的毫秒列，不受影响；测试里按创建时间算时长的一处改成取整 |
| 编号长度 | 通道申请号、账户号原来 35 位，新列 32 位 | 生成规则改成 32 位 |
| 路由只有一行 | 路由并进税制行后一行只能有一个环境；旧表每个环境一行 | 一个库只服务一个环境时没有区别；同一个库里同时放测试与正式路由不再支持 |
| 路由的更新时间 | 取的是税制行的更新时间，改卖方名称也会变 | 记录，未处理 |

### 2.8 线上数据怎么过来（本次不做）

1. 结构：甲类表在新库已建；F1、F2 的补充经用户授权后执行。
2. 存量：按表写一次性搬迁脚本（旧库读、新库写），状态按换值表换，毫秒整数时间换成 `datetime`；
   并表的（如打印机三合一）按业务编号对齐后合并。
3. 切换：停写窗口内搬完、核对行数与金额合计，再把模块绑定指到新库；回退就是解除绑定。
4. 乙类表在各自归属的模块对齐之前留在主库，不搬。
5. 部署前置：每个环境要有 `n_service`（含补充脚本），并配 `SHOP_SERVICE_DB_URL`、`SHOP_SERVICE_DB_USER`、
   `SHOP_SERVICE_DB_PASS`；漏配启动失败。e2e 的配置（`application-e2e.yml`）这次没有补，跑 e2e 前要加。

## §3 选型

多数据源的选型见 ADR-066。提交二的范围有三种切法：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A | 发票、打印的甲类表切到新库；乙类表留主库原表，两边之间改成“各自提交” | 不动 61；符合“一个对象一个归属库” | 乙类里和甲类同事务的几处要逐处改写（§2.3、§2.4、§2.5） |
| B | 乙类表也先建进 `n_service`，整个模块同库同事务 | 行为最稳，改动最少 | 要在 61 上建设计之外的表，等于把清单外的表加回去 |
| C | 先做统一审计、防重、门店设备这些公共能力，再回来切 | 一步到位 | 发票、打印被别的模块卡住，“先跑通做法”的目的落空 |

建议 A。其中发票的乙类都是历史记录性质，改成“业务提交后再记”风险可控；
打印的 `prn_operation`、`prn_device` 锁是命令主干，A 方案下等于重排打印中心的并发模型，工作量是发票的数倍。

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 新工厂漏装数据域拦截器 | 跨商户读到别人的发票 | M5 的测试对新工厂逐项断言；`MapperSmokeTest` 在绑定状态下再跑一遍 |
| 事务没点名，落到主库事务管理器 | 写新库的语句不在事务里，出错不回滚 | `ModuleTxManagerConventionTest` |
| T2 去掉订单行锁 | 开票与退款并发时开出金额过期的票 | 开立时比对来源指纹；测试覆盖“取数后订单变了” |
| 打印并表改锁顺序 | 死锁或重复出纸 | 先在 TDD 里画出新旧锁顺序再动；`PrintDatabaseAcceptanceTest` 用 MySQL 跑 |
| 状态换值 | App、代理程序认不得新值 | 换值表随接口文档发出；设备端入口保留旧值翻译 |
| 设计还会变 | 代码跟着返工 | 提交二只切甲类；§8 的问题先定再写 |

## §5 对账三 · 实现 → 需求（实现后填）

| AC | 测试方法 | 跑过 |
|---|---|---|
| M1 | `ModuleDataSourceRegistrarTest#unbound_module_handles_alias_primary` | ✅ |
| M2 | `ModuleDataSourceRegistrarTest#bound_module_reads_and_writes_its_own_database` | ✅ |
| M3 | `ModuleDataSourceRegistrarTest#modules_on_same_name_share_transaction` | ✅ |
| M4 | `ModuleTxManagerConventionTest#module_transactions_name_their_own_manager` | ✅ |
| M5 | `ModuleDataSourceRegistrarTest#bound_factory_keeps_interceptors_and_fill` | ✅ |
| S1 | `InvoiceServiceMysqlTest`（主库与发票库两个库，64 个用例，其中 60 个是切换前就有的） | ✅ |
| S2 | 打印未切，另行提交 | — |
| S3 | `InvoiceServiceMysqlTest#issue_does_not_touch_primary_database`、`#print_fact_event_is_not_published_when_issue_rolls_back`、`#application_replay_falls_back_to_business_state_when_history_is_lost`、`#channel_route_lives_on_the_regime_profile_and_history_stays_in_primary` | ✅ |
| S4 | `ServiceSchemaAlignmentTest#every_entity_field_is_a_column_and_every_common_field_is_mapped`、`#status_values_fit_ten_characters` | ✅ |

提交一的输出（2026-11-26，本机 MySQL 9.7.4 + H2；`mvn -o -pl shop-app -am test -Dtest=…`，BUILD SUCCESS）：

```
common.config.ModuleDataSourceRegistrarTest        tests=5  err=0 skip=0 fail=0
common.config.PrimaryPersistenceConfigurationTest  tests=3  err=0 skip=0 fail=0
arch.ModuleTxManagerConventionTest                 tests=2  err=0 skip=0 fail=0
arch.MapperSmokeTest                               tests=2  err=0 skip=0 fail=0
arch.ArchitectureTest                              tests=27 err=0 skip=1 fail=0
arch.ConditionalBeanWiringTest / InventoryDisabledContextTest / InvoiceOptionalTest /
arch.PayTxManagerConventionTest / SchemaParityTest / CommerceMapperScanIsolationTest   全绿
invoice.service.impl.InvoiceServiceMysqlTest       tests=60 err=0 skip=0 fail=0   （真 MySQL）
print.channel.PrintDatabaseAcceptanceTest          tests=13 err=0 skip=0 fail=0
printing.PrintDeliveryTest / UnifiedPrintDeviceGateTest / PrintingPortConfigurationTest 全绿
scenario.PrintEnabledAssemblyTest / BusinessPrintSourceFlowTest / PrintOrderSoftwareFlowTest /
printbridge.PrintRecoveryConfigurationTest         全绿
```

M1「行为不变」的证据是上面发票、打印的既有测试在未绑定状态下全部通过；没有跑 shop-app 全量。
M4 是读源码的守卫：改之前发票、打印里有 60 多处未点名的事务，逐处改完后变绿。

提交二（发票）的输出（2026-11-26，本机 MySQL 9.7.4 两个库 + H2 两个内存库，BUILD SUCCESS）：

```
invoice.service.impl.InvoiceServiceMysqlTest       tests=64 err=0 skip=0 fail=0   （主库 + 发票库）
invoice.ServiceSchemaAlignmentTest                 tests=2  err=0 skip=0 fail=0
invoice.InvoiceBoundaryTest                        tests=1  err=0 skip=0 fail=0
arch.ModuleTxManagerConventionTest / MapperSmokeTest / ArchitectureTest / SchemaParityTest /
infra.SchemaDriftTest / arch.BizRoleSeedTest / NoSecretDefaultInConfigTest / InvoiceOptionalTest /
arch.ConditionalBeanWiringTest / InventoryDisabledContextTest / MpEndpointAuthTest / BackendI18nParityTest   全绿
invoicebridge.* 四个（15 条）/ scenario.InvoiceCapableAxisFlowTest                                         全绿
打印（未切库，回归）：PrintDatabaseAcceptanceTest 13、PrintEnabledAssemblyTest、BusinessPrintSourceFlowTest、
PrintOrderSoftwareFlowTest、PrintRecoveryConfigurationTest                                                全绿
```

没跑成的：`trade.StoreOrderMysqlTest`（23 条）要本机有一份带 V691 结构的模板库，前置条件不满足，
在装配阶段中止。它里面的发票是替身，不走真实的发票代码。
没跑的：shop-app 全量、e2e、前端的类型检查（工作树里没有装前端依赖，`vue-tsc` 与 `tsc` 只报缺模块）。

闸门：`check-generated-docs`、`check-v2-docs`、`gen-ui-catalog --check`、`check-i18n-orphan`、
`check-app-orphan`、`check-ops-contract`、`check-app-contract`、`gen-service-test-schema --check` 通过。
`check-sql-portability` 红 21 个文件，都是存量，新加的建表文件不在其中。
`check-shared-guards` 红 1 条：`ops-web/lib/platform-nav.test.ts` 的导航翻译，与本次改动的文件无关，没有处理。

## §6 对账二 · 设计 → 实现

### 提交一

与 §2.2 的差异：

| 差异 | 说明 |
|---|---|
| `InvoiceModuleConfig`、`PrintModuleConfig` 合成了一个 `ModuleMapperScanConfig` | 两个类各只有一行扫描注解；合在一起并由 `MybatisPlusConfig` 引入，全局扫描在哪里生效它就在哪里生效 |
| 多改了两个测试的装配（`InvoiceServiceMysqlTest`、`PrintDatabaseAcceptanceTest`） | 它们自己搭上下文，要引入注册器才有模块句柄 |
| 多改了 `application.yml`（只加注释样例）与三份生成物 | 配置项要有出处；常量清单与文档索引由生成器重跑 |
| `PrintingConfiguration` 里登记事件事务的那一处仍用主库事务管理器 | 事件表在主库；守卫里登记为有意的例外 |

### 提交二（发票）

与 §2.4.1 实施清单的差异：

| 差异 | 说明 |
|---|---|
| 通道账户没有改成实体与 Mapper，仍是手写 SQL | 语句从 26 条减到发票库 8 条、主库 12 条，改成实体不减少风险；留到通道申请接审核服务时一起做 |
| 新增 `backend/scripts/gen-service-test-schema.py` 与产物 `schema-service-test.sql` | 现有的 `gen-test-schema.py` 认不出设计建表语句的写法（0 张表）；另写了一个只做这件事的小生成器，带 `--check` |
| 建表语句副本去掉了表尾的 `ENGINE / CHARSET / COLLATE` | SQL 可移植性闸门不许新文件写死排序规则；库默认就是同一个 |
| 代码仍显式写 `updated_at` | 上传任务的认领靠应用时钟比较 `updated_at`，交给数据库自动更新会混进数据库时钟；列的类型按规范 |
| 公共字段填充只在插入时填创建人、更新人 | 平台的更新填充用的是本机时区的时间，会顶掉上面那条里的应用时钟 |
| 邮件投递的三个写方法是两层事务 | 外层发票事务只为拿住发票行锁，里层主库事务写投递表；清单里只写了「用主库事务」 |
| 申请命令重放的兜底多做了两处 | 驳回、取消在已是目标状态时回原申请；清单只写了提交与开立 |

行为上的变化（接口不变，语义变了）：

- 同一把防重键被并发用在两个不同来源上时，原来第二个整笔回滚；现在两边的业务各自成立，只是第二条事件记不进去。
- 已开立的申请再开立（版本号对）回原发票，原来回「状态不对」。
- 「核验并绑定」中途失败可能留下已换凭据但未绑定的账户，重做即可（§2.4.1 第 6 条）。
- 开票取数时的订单行锁不再跨到开票事务里（T2）；直接开票（不经申请）没有来源指纹比对，与退款并发时仍可能开出金额过期的票。**这一条没有新增保护，是遗留风险。**

### 偏差说明

- 具名数据源的名字不能和模块同名（如把数据源叫 `invoice`）：句柄名会撞。注册器启动时拒绝，设计里原先没写。
- 模块工厂会和主库工厂一样加载 `mybatis-plus.mapper-locations` 下的 XML；发票、打印没有 XML，无影响。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-26 | 设计稿写完，等用户确认 §8 |
| 2026-11-26 | 用户确认按建议：Q0 取 A；Q1、Q2、Q4 先留主库原表；Q3 补 `ivc_store_setting`；Q5 打印并发模型另出设计；Q6 接受；Q8 提交二先做发票，打印单独提交。Q7（改 61 上的表）没有单独授权，本次不动 61 |
| 2026-11-26 | 提交二（发票）已实现。闸门与扫描面见下一行；`n_service` 的补充脚本没有在 61 执行 |
| 2026-11-26 | 提交一已实现。闸门：`check-v2-docs`、`check-controller-cohesion`、`check-inventory-independence`、`check-generated-docs` 通过；`check-sql-portability` 红 21 个文件，都是存量 SQL，本次没有改任何 SQL。扫描面：`backend/` 下受影响模块的指定测试类，没有跑全量 |

## §8 待确认

| # | 问题 | 建议 |
|---|---|---|
| Q0 | 提交二的范围：A、B、C 哪一种（§3） | A |
| Q1 | 发票通道开通申请的三张审批表：这次就接审核与工单服务，还是先留主库原表 | 先留原表；接审核服务单独做 |
| Q2 | 申请事件、路由事件、账户历史版、打印操作日志这类历史记录：统一审计没有代码，B 端与 App 又在读 | 先留主库原表，改成业务提交后再记；统一审计做好后一起搬 |
| Q3 | `ivc_store_setting`（门店的默认税别、开票时机）设计里没有表 | 在 `n_service` 补一张 `ivc_store_setting`（需授权改 61） |
| Q4 | 打印代理 `prn_agent` 的去向是平台基础库的门店设备，那边没有代码 | 先留主库原表 |
| Q5 | 打印命令台账 `prn_operation`：设计里不建，但它是幂等依据也是接口数据来源 | 打印的并发模型单独出一份设计再动；或同意它作为业务表留下 |
| Q6 | 打印完成、告警的提醒改为提交后再发，极端情况下会少一条提醒 | 接受 |
| Q7 | F1 的唯一索引、F2 的字段要改 61 上已建的表 | 请授权，或由设计那边统一改 |
| Q8 | 打印是否和发票一起放在提交二 | 分开：提交二做发票，打印单独一次提交 |
