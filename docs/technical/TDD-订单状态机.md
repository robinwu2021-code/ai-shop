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

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC1–2 | `StateMachineTest` | | 撤执行者校验 → 红 |
| AC3 | `PaymentMachineTest` / `OrderMachineTest` / `ShipmentMachineTest` / `AfterSaleMachineTest`（表内正例 + 全量反例枚举） | | 删一行迁移 → 红 |
| AC4 | `MachineInvariantsTest` | | 加一条 SYSTEM→REFUNDING → 红 |
| AC5 | `StateTransitionGateTest`（假存储：CAS 失败、流水、联动） | | 撤 CAS → 红 |
| AC6–10 | `M5AfterSaleFlowTest` / `AfterSaleTimeoutFlowTest` 改写 + 新用例 | | 恢复自动同意 → 红 |
| AC11 | `vue-tsc` + i18n 闸门 + c-app / b-app 单测 | | — |
| AC12 | `OrderStateMachineCompatTest`（新旧图逐边相等，差异白名单） | | — |

## §6 对账二 · 设计 → 实现（实现完再填）
