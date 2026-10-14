# TDD-订单状态机

状态：已确认（用户 2026-10-14「确认」：售后新状态一次到位、干预收窄为非资金、三端按钮读 allowedActions、先做步 1–3）
关联需求：docs/superpowers/specs/2026-10-14-订单状态机-完整方案.md（迁移表 §2–§5、不变式 §8、类设计 §10）；
docs/superpowers/specs/2026-10-14-退货退款逻辑.md v2（退款永不自动）
创建：2026-10-14 · 最后更新：2026-10-14

档位：2（跨端、不可逆的规则改变：关掉自动退款；新增售后状态） · 本文覆盖完整方案 §13 的步 1–3

## §0 对账一 · 需求 → 设计

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | 引擎按（当前状态, 事件, 执行者）找迁移；找不到 → `ORDER_STATE_ILLEGAL`；状态对但执行者不对 → `FORBIDDEN`；守卫按序校验，第一个失败即返回它的错误码 | `statemachine/StateMachine` · `Transition` · `Guard` |
| AC2 | `allowed(状态, 执行者, 上下文)` 返回守卫全部通过的事件集合 | `StateMachine#allowed` |
| AC3 | 四台机器的迁移与方案 §2–§5 逐行一致；表外组合一律拒 | `PaymentMachine` · `OrderMachine` · `ShipmentMachine` · `AfterSaleMachine` |
| AC4 | 不变式 I1–I4：SYSTEM 只能经 `TRADE_VOID` 进入退款；定时器事件不进入退款；终态无出边；全部可达 | `statemachine/Invariants` |
| AC5 | 状态写入经转换门：CAS 写库，0 行 → `CONFLICT`，不覆盖；每次迁移写一行流水（聚合、单号、事件、从、到、执行者、理由） | `transition/StateTransitionGate` · `StatusStore` · `TransitionLog`；`V402__status_log_aggregate.sql` |
| AC6 | 商家超时未处理售后 → **不再自动同意**，只提醒商家并开放买家「申请平台介入」 | `AfterSaleServiceImpl#autoApprove` → 改为 `remindOverdue` |
| AC7 | 退货寄回后商家超时未确认 → **不再自动退款**，只提醒 | `AfterSaleServiceImpl`（confirmHours 分支） |
| AC8 | 在途仅退款签收后 → **不再自动退款**，提醒商家一键确认（`CONFIRM_REFUND`） | `AfterSaleServiceImpl#settleOnReceipt` + 新端点 `POST /biz/after-sale/{no}/confirm-refund` |
| AC9 | 售后「退款中」拆义：退货类同意 → `WAIT_RETURN`；买家填运单 → `RETURNING`；商家确认收货 → `REFUNDING`；存量按运单号有无迁移 | `AfterSaleServiceImpl` · `V403__after_sale_split_return_states.sql` |
| AC10 | 买家可对「商家超时 / 退货待确认超时 / 签收后待确认超时」的售后申请平台介入 | `AfterSaleServiceImpl#escalate` 入口条件 + `OrderStateMachine.AFTER_SALE` 图 |
| AC11 | 三端认得 `WAIT_RETURN` / `RETURNING`（文案 + B 端按钮），不露键名 | c-app / b-app / ops-web 词条与售后卡 |
| AC12 | 老 `OrderStateMachine` 的三张图由新机器导出，老调用方零改动 | `OrderStateMachine` |

**孤立项**：NEGOTIATING / WAIT_OFFLINE_REFUND / OFFLINE_REFUNDED 在机器定义里一次到位（AC3），但写路径与三端交互在步 5 / 步 7 接（本 TDD 不接线，不下发）。

## §1 现状与影响面

- 现有：`OrderStateMachine`（三张 from→to 图，无事件/执行者/守卫）；15 处直写状态；`AfterSaleTimeoutJob` 三条自动路径；`OrderTransitions`（2.0 内核写入门雏形）。
- 会被改到的（步 3）：售后超时作业、签收结算、退货流程状态、三端售后卡。
- 不受影响：下单、支付、发货写路径（步 4–6 才接门）；订单对外 7 值投影。

## §2 方案（实施计划）

### 契约变更
- 库表：`V402` `ord_status_log` 加 `aggregate_type`(VARCHAR16, 默认 'ORDER') / `aggregate_no`(VARCHAR64) / `event`(VARCHAR32) / `from_status`(VARCHAR24)；`V403` 存量售后 `REFUNDING` 按类型与运单号迁到 `WAIT_RETURN` / `RETURNING`。
- 端点：新增 `POST /biz/after-sale/{no}/confirm-refund`（AC8）。`/biz/after-sale/{no}/receive` 改为只认 `RETURNING`。
- 售后状态值：新增 `WAIT_RETURN` / `RETURNING`（步 3 起下发）；`NEGOTIATING` / `WAIT_OFFLINE_REFUND` / `OFFLINE_REFUNDED` 只在机器定义里。
- i18n：三端 `WAIT_RETURN` / `RETURNING` 售后状态与提示；B 端 `confirmRefund` 按钮；ops-web 售后徽章。
- 配置：售后规则页「商家响应时限」语义改为「超时提醒」，字段不变。

### 模块设计（单类 ≤200 行、单方法 ≤50 行）

| 步 | 动作 | 路径（`backend/shop-core/.../trade/order/` 下） |
|---|---|---|
| 1 | 新增 | `statemachine/{Actor, Guard, TargetResolver, Transition, Fired, StateMachine, StateMachineBuilder, Invariants}` |
| 1 | 新增 | `machine/payment/{PaymentStatus, PaymentEvent, PaymentContext, PaymentMachine}` |
| 1 | 新增 | `machine/order/{OrderStatus, OrderEvent, OrderContext, OrderMachine}` |
| 1 | 新增 | `machine/shipment/{ShipmentStatus, ShipmentEvent, ShipmentContext, ShipmentMachine}` |
| 1 | 新增 | `machine/aftersale/{AfterSaleStatus, AfterSaleEvent, AfterSaleContext, AfterSaleMachine, AfterSaleTargets, AfterSaleGuards}` |
| 2 | 新增 | `transition/{StateTransitionGate, Command, Aggregate, StatusStore, TransitionLog}` + `transition/impl/{MybatisStatusStore, StatusLogStore}`；`V402` |
| 3 | 修改 | `trade/service/impl/AfterSaleServiceImpl`、`trade/job/AfterSaleTimeoutJob`、`trade/service/OrderStateMachine`、`trade/api/biz/BizAfterSaleController`；`V403`；三端售后卡与词条 |

## §2B 步 4：支付单 / 订单写路径改走门

| AC | 需求 | 落点 |
|---|---|---|
| AC13 | 超时关单与付款回调并发：只有一方成功；关单输了**不释放库存 / 券 / 积分** | `OrderServiceImpl#closeOne` → 门 `PAY_TIMEOUT` |
| AC14 | 付款回调 / 线下确认收款 / 0 元单 → 支付单 PAID，其下订单同事务转待履约（服务类直接履约中） | `markPaid` → 门 `PAY_SUCCEEDED` / `OFFLINE_CONFIRMED` + 每张订单 `PAY_CONFIRMED` |
| AC15 | 买家取消、**运营代客取消**走同一条路：支付单 CANCELLED + 订单 CANCELLED + 释放；代客取消此前只改子单状态、不释放、支付单仍可付 | `cancel` / 新 `cancelByPlatform`；`OpsOrderController#proxyCancel` |
| AC16 | 确认收货 / 自动确认收货 / 自送送达 / 自提到店 / 核销 / 开始备货 经门；有进行中售后不许备货 | `confirmReceipt` / `autoConfirmReceipt` / `MerchantOrderServiceImpl#delivered,#prepare` / `FulfillmentQueryPortImpl` |
| AC17 | 人工干预只能做非资金迁移（往前推到履约中 / 已完成）；运营端迁移下拉同步收窄 | `MerchantOrderServiceImpl#intervene` → 门 `INTERVENE`；ops-web `ORDER_TRANSITIONS` |
| AC18 | 经门的迁移，状态流水只记一行（门记），不再手写重复的 `appendStatusLog` | 各转换点 |

模块：新增 `trade/order/transition/TradeStatusWriter`（Spring 组件：建门、按实体执行迁移，成功后把实体的 `status` / `version` 同步到库里的新值 ——
实体有 `@Version`，不同步的话后续 `updateById` 会被乐观锁静默拒掉，写 0 行且不报错）。
不在本步：商家发货（`MerchantOrderServiceImpl#ship`）随发货单层在步 6 接；售后写路径在步 5。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC1–2 | `StateMachineTest`（10） | ✅ | — |
| AC3 | `MachineTablesTest`（四台机器与文档迁移表全集相等）+ `MachineBehaviorTest`（10，分流与守卫） | ✅ | 给 APPROVE 加 SYSTEM → 表测试与 I1 同时红 ✅ |
| AC4 | `MachineInvariantsTest`（I1 退款永不自动 / I3 终态 / I4 可达与不卡死） | ✅ | 同上 ✅ |
| AC5 | `StateTransitionGateTest`（6，假存储）+ `StateTransitionStoreTest`（4，H2 真库：两线程 CAS 恰一方成功、V402 列、四表 SQL、列宽） | ✅ | 去掉 `AND status = #{from}` → 并发用例红 ✅ |
| AC6 | `AfterSaleTimeoutFlowTest#overdueOnlyReminds`（超时只提醒、24h 内不重复、到点可申请介入）+ `#grayStageNotAutoApproved` | ✅ | `remindOverdue` 改回 `doRefund` → 两条红 ✅ |
| AC7 | `AfterSaleTimeoutFlowTest#returnFlowNeverAutoRefunds` | ✅ | — |
| AC8 | `M5AfterSaleFlowTest#signed_reminds_then_merchant_confirms`（签收不退、`/confirm-refund` 才退） | ✅ | `settleOnReceipt` 改回 `doRefund` → 红 ✅ |
| AC9 | `AfterSaleTimeoutFlowTest#closeWhenBuyerNeverShips` / `#returnFlowNeverAutoRefunds`（WAIT_RETURN → RETURNING）；V403 | ✅ | — |
| AC10 | `AfterSaleTimeoutFlowTest#overdueOnlyReminds`（canEscalate 前 false 后 true、escalate 成功）；`M5#rejectThenEscalate` | ✅ | — |
| AC11 | c-app `order-page.test.ts`（WAIT_RETURN 给填单号、REFUNDING 不给；canEscalate 决定介入按钮）491 绿；b-app 187 绿；ops-web 722 绿；三端 `vue-tsc` / `tsc` 通过 | ✅ | — |
| AC12 | `OrderStateMachineTest`（售后图由新机器导出；ARBITRATING→REFUNDED 直达边按设计去掉，用例改为 →REFUNDING） | ✅ | — |

### 步 4（§2B）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC13 | `CloseRaceReleaseTest`（独立库 + spy：关单拿旧数据、付款已落库 → **券从未释放**、状态仍 PAID） | ✅ | `closeOne` 换回旧写法 → 红（release 被调）✅。注：只断言状态是假绿 —— 旧写法被 `@Version` 静默拒写，状态本来就不会变 |
| AC14 | `M3TradeFlowTest` / `OrderCloseRuleFlowTest` / `Pay*Test` 等既有支付链路全绿 | ✅ | — |
| AC15 | `TradeStatusGateFlowTest#proxyCancelCancelsPayment` | ✅ | 实现前即红（原代客取消根本没有测试覆盖） |
| AC16 | `TradeStatusGateFlowTest#prepareBlockedByOpenAfterSale`（CONFLICT、备货时间未写）；`BizOrderFulfillFlowTest` 送达 / 核销 | ✅ | — |
| AC17 | `TradeStatusGateFlowTest#interveneNonMonetaryOnly`（到 REFUNDED → 20004；到 COMPLETED 成功）；ops-web 722 绿 | ✅ | — |
| AC18 | `TradeStatusGateFlowTest#prepareBlockedByOpenAfterSale`（付款成功只记一行流水） | ✅ | — |

相关后端整组 855 绿。步 4 偏差：O7「商家标记送达」补上从 FULFILLING 出发（自送单「配送中」后送达，既有用例覆盖；方案 §3 与 MachineTablesTest 同步改）。
全量闸门第一轮又撞出两处，均为**老行为本来允许、新机器漏了**：
- O8「买家确认收货」补上从 WAIT_FULFILL 出发 —— 微信购物订单的「确认收货」组件不等我们标发货（`M4FulfillmentFlowTest#confirmReceipt`、`ReviewStoreAttributionFlowTest`）。
- `markPaid` 跳过已不是待付款的子单 —— 对账修复会重放 markPaid，老写法靠「from == to 一律放行」吞掉，状态机没有这条边（`M7SettleFlowTest#invariantI8RepairsOrderStuckInWaitPay`）。

## §6 对账二 · 设计 → 实现

| 提交 | 内容 |
|---|---|
| `538197842` | 步 1：`statemachine/` 8 类 + `machine/{payment,order,shipment,aftersale}` 16 类 + 3 份测试；ErrorCode 20012/20013 四处登记 |
| `d9f782626` | 步 2：`transition/` 门 + 接口 + `impl/` 两个实现 + `StatusCasMapper` + V402 + `OrdStatusLog` 4 字段 + `MybatisPlusConfig` 注解扫描登记 |
| `a0aa3889a` | 步 3：售后服务 / 接口 / 实体 / VO / 作业 / 控制器 / 状态机兼容图 / 两处未闭环集合 + V403 + 三端 |

| 差异 | 说明 |
|---|---|
| §2 写「`transition/` 含 `CascadeRules`」 | **未做**：同事务联动要在步 4（支付单写路径接门）才有第一个用户；先放空接口会变成没人调的死代码 |
| 新增 `StatusCasMapper`（§2 未列） | 不拼表名：四张表各一对显式 SQL；它不继承 BaseMapper，需登记进注解扫描 |
| `MybatisPlusConfig` 改动（§2 未列） | 同上；顺带确认 `ShipmentMapper`（V401）早已被全局 BaseMapper 扫描收进，注释里「待纳入」已过时 |
| 售后写路径仍是 `setStatus + updateById` | 步 3 只用新状态机**判**合法性；改走门（CAS）在步 5，与行级退款一起 |
| 提醒去重按流水标签计数 | 一次合跑中出现过一次重复提醒，单跑与复跑均未复现；原因未定位（怀疑并发扫描），记为待查 |
