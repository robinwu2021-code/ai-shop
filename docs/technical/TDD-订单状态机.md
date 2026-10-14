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

## §2C 步 5：售后写路径 + 行级退款（方案：specs/2026-10-14-第5步-售后写路径与行级退款-方案.md）

**已拍板（2026-10-14「按照建议执行」）**：分账按「商家应收 × 退款比例」回退、部分回退保持 SPLIT，并排查 10-11 以来已发生的部分退款；
完成时间加列 `completed_at` 并回填；支付宝若不支持按额多次回退，该通道部分退款只走积分补偿 / 线下退款登记；
已发货运费仅 DAMAGED / QUALITY / WRONG_ITEM / MISSING 退；顺序 5.4 → 5.2/5.3 → 5.5/5.6 → 5.7，5.1 独立。

### 5.4 行级数据（只写不读）

| AC | 需求 | 落点 |
|---|---|---|
| AC19 | 下单时每行落「分摊后实付」快照，Σ = 子单实付 − 运费（最大余数法，零头不丢） | `trade/order/service/refund/LineShares` + `OrderServiceImpl` 建行处 |
| AC20 | 存量行不在迁移里回填快照（MySQL 1093 不许 UPDATE 子查询引用同表、H2 语法又不同）：读时用**同一算法**现算 | `LineShares.of(sub, items)`：快照齐全用快照，否则现算 |
| AC21 | 子单完成时刻落 `completed_at`（售后期起算点）；存量从状态流水回填 | V404 + `TradeStatusWriter`（迁到 COMPLETED 时写） |
| AC22 | 存量已退款子单标 `refund_progress = FULL`，`refunded_minor` = 其售后实退额之和 | V404 |

契约：V404（`ord_item` +3 列、`ord_sub_order` +3 列、新表 `ord_after_sale_item`）；`refund_progress` 取值域 5.7 下发前登记 DISMISSED。

### 5.2 / 5.3 退款单号按售后单 · 分账按额回退（结算口径 A）

| AC | 需求 | 落点 |
|---|---|---|
| AC23 | 退款幂等键 = 售后单号（同一子单第二张售后能真的退出钱）；旧签名保留并委托新签名 | `SettlePort.refund(sub, afterSaleNo, amount, reason)` |
| AC24 | 口径 A：商家所得 / 佣金 / 服务费 / 补贴按「累计已退货款 ÷ 商品实付」切分，分几次退完 Σ 恰等于总额；运费商家自寄由商家回退、平台代寄平台承担 | `pay/settle/refund/DefaultSettlementRefundPolicy` → `BillRefundShare` |
| AC25 | 分账后：按额发回退（`REV-售后单号`）、补贴收回（`SUBR-售后单号`）；中途保持原状态，货款退完才转 REVERSED；通道失败 → 返回 false、转人工、不记账 | `PostSplitReverser` |
| AC26 | 分账前：只扣减结算单各项金额，状态不变（余款照常分账）；退完才 REVERSED | `PreSplitAdjuster` |
| AC27 | 同一售后单重放只落一次（R3）；以「已退货款累计 + 状态」为 CAS 前值，并发写 0 行即回滚（R1/R2） | `stl_bill_refund`（`uk(after_sale_no)`）+ `BillRefundMapper` |

契约：V405（`stl_bill` +`refunded_goods_minor`/`reversed_minor`；新表 `stl_bill_refund`）；`SettlePort` +`reverseSplit(SplitReversal)` +`refund(…, afterSaleNo, …)`。
调用方（`AfterSaleServiceImpl.doRefund`、运营端「分账回退」）在 5.5 切到新签名，此前行为不变。

**存量排查（拍板项「10-11 以来的部分退款」，2026-10-14 生产只读查询）**：`ord_after_sale` 全库 3 行，唯一 REFUNDED 的一张是整单全退（100 / 100 分，结算单 REVERSED 正确），另两张在 ARBITRATING、结算单 PENDING_RECON 未动。**没有发生过部分退款，无需补回商家。**

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

### 步 5（§2C）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC19–22 | `LineSharesTest`（5）+ `LineRefundDataFlowTest`（2，快照 Σ、完成时刻、V404 回填语句 H2 原文执行） | ✅ | 去掉快照写入 → 红 ✅ |
| AC24 | `DefaultSettlementRefundPolicyTest`（6：三次分退 Σ 相等、1/3、运费两种模式、一次全退 = 旧整单回退额、0 元单、超退拒绝） | ✅ | 累计切分改逐笔下取整 → Σ 差 2 分红 ✅ |
| AC25 | `SettlementPartialReverseTest#postSplitThreeInstallments` / `#channelFailureStopsRefund` | ✅ | 回退额改回整单 net → 红 ✅ |
| AC26 | `SettlementPartialReverseTest#preSplitPartialKeepsSettleable` | ✅ | 部分退也标 REVERSED（旧行为）→ 红 ✅ |
| AC27 | 同上（同一售后单重放） | ✅ | 去掉按售后单判重 → 唯一键撞出 DuplicateKey 红 ✅（唯一键是第二道兜底） |

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
| 5.4 / 5.3 提交 | `fe84deaa3`（V404 + 下单快照 + 完成时刻）、`a0a2235a8`（退款领域对象）、`2de5746a8`（RefundLedger）；5.2/5.3 见本节下一提交 |
| `BillRefundShare` 比对象模型多 6 个字段 | 对象模型只列了商家回退 / 补贴 / 运费 / 是否最后一笔；分账前要把基数、佣金、服务费、积分服务费、积分成本一起扣，份额必须带全 |
| `SplitReversal` 多一个 `goodsPaidMinor` | 对象模型签名是四参；结算单上没有平台优惠这一项，反推不出商品实付，由调用方（手里有子单）传入 |
| 新表 `stl_bill_refund`（对象模型未列） | 分账前的退款不发通道指令，没有 `requestNo` 可以判重；R3 要一个按售后单号唯一的落点，同时是「部分退款回退了多少」的唯一出处 |
| 抽出 `SplitInstructionSender` | `SettleServiceImpl#callProvider` 的金额写死在里面，按额回退要换金额；抽出后原方法只算金额再委托（行为不变） |
| 遗留：分账指令金额口径 | `callProvider` 分账 / 回退都用 `netMinor`，而 `splitAmountMinor` 注释写「分账指令以它为准（= 平台应收）」—— 今天底下是桩网关，口径未生效；**接真分账通道前要先定**。按额回退沿用 `netMinor` 口径，一次全退的金额与旧整单回退逐分相同 |
