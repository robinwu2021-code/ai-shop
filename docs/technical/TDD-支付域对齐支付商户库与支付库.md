# TDD-支付域对齐支付商户库与支付库

状态：第一步已实现（支付开通与进件接到支付商户句柄，不改表）· 收款与退款、结算的拆分未做
关联：[ADR-066](ADR/ADR-066-业务模块按配置选数据源.md) · [ADR-021 支付域独立为服务与独立库](ADR/ADR-021-支付域独立为服务与独立库.md) · [TDD-登录与账号对齐平台基础库](TDD-登录与账号对齐平台基础库.md)
范围：业务模块“支付开通与进件”“收款与退款”
创建：2026-11-26 · 最后更新：2026-11-26

## 一句话

支付域现在是一个整体：一个连接池、一个事务管理器、一个包下 121 个 Mapper。新设计把它拆到三个库：
支付商户库、支付库、账务结算库（另有一部分去会员营销库与基础能力库）。本次先把边界最干净的一块——
支付商户与进件——接到单独的模块句柄 `payMerchant` 上；没绑定时它仍是支付域现有的那一组，行为不变。
收款与退款留在现有的支付句柄上，它和结算之间的同事务写是下一步要先定的事。

## 验收标准

- Y1：Given 没有给支付商户模块绑定数据源 When 跑进件、支付商户、收款的现有测试 Then 全部通过，支付商户的写仍在支付域的事务里。
- Y2：Given 没有绑定 When 取 `payMerchantTxManager` Then 它就是 `payTxManager`，不是主库的事务管理器。
- Y3：Given 独立的支付进程 When 启动 Then 能取到 `payMerchantTxManager`，它就是那个进程唯一的事务管理器。

## §0 对账一 · 需求 → 设计

| AC | 落点 |
|---|---|
| Y1 | `PayMerchantTableMapper` 标记 ＋ `PayDataSourceConfig` 的两个扫描；三个服务改取 `payMerchantTxManager` |
| Y2 | `ModuleDataSources#unboundTarget`：从支付域分出来的模块未绑定时落到支付域的那一组 |
| Y3 | `pay-svc` 的 `PayTransactionConfig` 多一个 bean 名 |

## §1 现状

### 1.1 支付域的 121 个 Mapper 各去哪个库

| Mapper 子包 | 数量 | 目标库 | 能不能整包切 |
|---|---:|---|---|
| `merchant`、`onboarding` | 7 | 支付商户库（其中商户状态日志去统一审计） | 能 |
| `acquiring`、`checkout`、`access` | 9 | 支付库 | 能 |
| `ledger` | 2 | 账务结算库 | 能，但有一条联了支付订单 |
| `terminal` | 3 | 终端在支付商户库，店长授权码与批次在支付库 | 不能 |
| 根包（结算、渠道） | 35 | 支付库、账务结算库、支付商户库、基础能力库都有 | 不能，最乱 |
| `points` | 7 | 账务结算库为主，另有基础能力库、任务库 | 不能 |
| `storedvalue`、`entitlement` | 53 | 会员营销库为主 | 属于卡券与储值模块 |
| `notify`、`openapp` | 5 | 基础能力库 | 属于公共能力 |

### 1.2 同一个事务里写两个目标库的地方

支付开通与进件这一块**没有**：进件通过时建商户号、建产品，都在支付商户库内；只有一条状态日志去统一审计。

收款与退款这一块有，而且是核心路径：

| 场景 | 位置 | 同事务写了什么 |
|---|---|---|
| 收款成功记账 | `PaymentLedgerServiceImpl` :620 → `PaymentOutcomeServiceImpl` :54 | 交易流水、支付订单（支付库）＋ 记账凭证与分录（账务结算库）＋ 业务通知（基础能力库） |
| 退款成功记账 | `RefundPaymentStore` → `RefundOutcomeServiceImpl` :49 | 流水、订单、退款单（支付库）＋ 记账（账务结算库） |
| 分账确认、回退、准备、结果 | `SettleServiceImpl` :463、:538；`SplitPreparationServiceImpl`；`SplitResultServiceImpl` | 结算明细（账务结算库）＋ 分账记录与意图（支付库） |
| 现金退款回退分账 | `CashRefundSplitServiceImpl` | 回退记录（支付库）＋ 结算明细与退款明细（账务结算库） |
| 一个 Mapper 跨两库 | `SplitIntentMapper` | 结算明细与分账意图 |

收款与记账拆成两个事务后，一边提交一边回滚不会有任何报错。这是把支付库和账务结算库分开之前必须先定的：
谁是事实来源、另一边怎么补（记账按流水重放，还是先记账后确认）。本次没有动。

### 1.3 支付商户还有一部分代码不在支付域里

`shop-merchant` 里的收款商户、出款账户、准入、保证金，以及 `shop-gateway` 里的收款来源，现在都走主库。
它们在新设计里并入支付商户库与账务结算库。本次没有动；在商户域的守卫名单里已标为“别的库”。

### 1.4 两种部署形态

`shop.pay.deployment` 不配或配 `embedded` 时，支付代码在本进程里直接读写支付表；配 `standalone` 时改为调远端的支付服务。
仓库里没有配置把它设成 `standalone`，线上实际是哪一种没有核实。模块句柄只对进程内这一种形态有意义。

## §2 方案

### 2.1 契约变更

没有。新增模块名 `payMerchant`，可以写 `shop.modules.payMerchant.datasource=<名字>`。

### 2.2 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-store-mybatis/.../ModuleDataSources.java`、`ModuleDataSourceRegistrar.java` | 增加模块 `payMerchant`；“未绑定时指向谁”改成按模块取，支付域分出来的模块指向 `payDataSource / paySqlSessionFactory / payTxManager` |
| 新增 | `shop-store-mybatis/.../config/PayMerchantTableMapper.java` | 标记：表在支付商户库 |
| 修改 | `pay-domain` 的 `mapper/merchant/MerchantMappers.java`、`mapper/onboarding/OnboardingMappers.java` | 6 个 Mapper 加标记（商户状态日志不加，它的去向是统一审计） |
| 修改 | `shop-app/.../config/PayDataSourceConfig.java` | 支付的整包扫描按标记排除；新增按标记收取的扫描，绑到支付商户句柄 |
| 修改 | `PayMerchantBackfillImpl`、`PayMerchantBindingServiceImpl`、`OnboardingServiceImpl` | 事务管理器改取 `payMerchantTxManager` |
| 修改 | `pay-svc/.../PayTransactionConfig.java` | 独立进程里同一个事务管理器多一个名字 |

没有接的：终端 `pay_terminal`（它和店长授权码、批次在同一个 Mapper 文件里，对应的服务没有自己的事务声明）、
费率、`shop-merchant` 与 `shop-gateway` 里的支付商户代码。

## §3 选型

| 问题 | 方案 | 结论 |
|---|---|---|
| 未绑定时句柄指向谁 | 统一指向主库 | ❌ 支付的写会并进平台事务，支付与平台的事务隔离静默失效 |
| | 按模块指定 | ✅ 支付域分出来的模块指向支付域现有的那一组 |
| 先切哪一块 | 收款与退款 | ❌ 和记账同事务，要先定一致性做法 |
| | 支付商户与进件 | ✅ 内部没有跨库事务 |

## §4 风险

| 风险 | 缓解 |
|---|---|
| 进件服务里除了支付商户的 Mapper 还用了别的支付 Mapper | 未绑定时是同一个事务管理器，没有区别；绑定前要逐个核对它在事务里碰的表 |
| 独立支付进程缺句柄名 | 已加；`PayApplicationBootTest` 覆盖启动 |
| 自己注册 `payTxManager` 的测试缺新名字 | 跑了支付相关测试确认，见 §5 |

## §5 对账三 · 实现 → 需求

| AC | 测试 | 跑过 |
|---|---|---|
| Y1 | 进件、支付商户、收款相关的现有测试（见下） | ✅ |
| Y2 | `ModuleDataSourceRegistrarTest#unbound_module_handles_alias_primary`（断言支付商户模块未绑定时指向支付域的那一组） | ✅ |
| Y3 | `PayApplicationBootTest`（独立支付进程启动，8 条） | ✅ |

2026-11-26，`mvn -o -pl shop-app,pay/pay-svc -am test -Dtest=…`，BUILD SUCCESS，45 个测试类全部 0 失败 0 错误，包括：
`MapperSmokeTest`、`ArchitectureTest`、`PayTxManagerConventionTest`、`PayMapperPackageTest`、`PayStandaloneAssemblyTest`、
`PayApplicationBootTest`、`PayMigrationTest`、进件与支付商户的 `*Onboarding*`、`*PayMerchant*`、`*PayBiz*`、`*Terminal*` 各组、
`BizPayFlowTest`、`PayoutFlowTest`、`M7SettleFlowTest`、`RefundCapacityDatabaseTest`、`PointsPostingDatabaseTest`，以及发票的 `InvoiceServiceMysqlTest`（64 条）。

过程中 `MapperSmokeTest` 抓到两个问题，都已修：标记接口自己被全局扫描注册成了 Mapper；注册器被两个配置各触发一次、重复登记数据源。

没跑：支付域自己的全量、真 MySQL 的支付测试、shop-app 全量。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-26 | 第一步实现 |
