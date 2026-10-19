# TDD-订单状态机

状态：已确认（用户 2026-10-14「确认」：售后新状态一次到位、干预收窄为非资金、三端按钮读 allowedActions、先做步 1–3）
关联需求：docs/superpowers/specs/2026-10-14-订单状态机-完整方案.md（迁移表 §2–§5、不变式 §8、类设计 §10）；
docs/superpowers/specs/2026-10-14-退货退款逻辑.md v2（退款永不自动）
创建：2026-10-14 · 最后更新：2026-10-19

2026-10-19 修复接续：本次退款恢复、积分退款结算屏障及动态目标校验，以[订单状态机 Review 修复记录](./订单状态机Review修复-2026-10-19.md)为准。下文的阶段计划和历史验收记录保留其原有时间范围。

2026-10-19 整单退款结清接续：新增持久权益义务、积分与零现金终态守卫、库存独立恢复及三端进度。现行范围仅全商家子单退款，部分退款扩展已延期；实现/流程及验证索引见[订单退款结清 M1](./订单退款结清-M1流程与契约.md)。下文部分退款历史验收不代表当前公开入口已开放。

本轮接续：§2D 为 2026-10-14 两轮 review 后的下一步计划，状态为**计划草稿、未实施**。原有已确认规则与历史验证记录保留；§2D 的业务建议不自动视为已确认，未执行测试不计入 §5 的既有通过记录。

文档接续进度：用户已授权按计划修改文档，并要求依据实际代码制定开发计划。当前执行主线为 [TDD-订单重构修复与三端接线 §2.6](./TDD-订单重构修复与三端接线.md) 的7阶段、26批次：F01–F18修复任务，加B00/M01/M02与I01–I05基线、归属、接线及包迁移任务；全部代码批次未实施，业务修订仍待评审。

订单2.0现状核查（2026-10-14）：内核当前位于`ai.neargo.shop.trade.order`，构件已建、部分接线，尚未全链路替换旧服务。用户期望将来迁到`ai.neargo.shop.order`；本轮先检查、不搬代码。当前完成矩阵、222条定向测试证据及未来迁移边界见详细计划§1.3–§1.4。

档位：2（跨端、不可逆的规则改变：关掉自动退款；新增售后状态） · 本文覆盖完整方案 §13 的步 1–3

## §0 对账一 · 需求 → 设计

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | 引擎按（当前状态, 事件, 执行者）找迁移；找不到 → `ORDER_STATE_ILLEGAL`；状态对但执行者不对 → `FORBIDDEN`；守卫按序校验，第一个失败即返回它的错误码 | `statemachine/StateMachine` · `Transition` · `Guard` |
| AC2 | `allowed(状态, 执行者, 上下文)` 与 `fire` 共用执行者、守卫和动态目标校验；业务拒绝的事件不展示，目标不在声明集合等定义错误仍抛出 | `StateMachine#allowed` |
| AC3 | 四台机器的迁移与方案 §2–§5 逐行一致；表外组合一律拒 | `PaymentMachine` · `OrderMachine` · `ShipmentMachine` · `AfterSaleMachine` |
| AC4 | 不变式 I1–I4：SYSTEM 只能经 `TRADE_VOID` 进入退款；买家主动申请且未备货可经 `PREPARATION_NOT_STARTED_REFUND` 进入退款；定时器事件不进入退款；终态无出边；全部可达 | `statemachine/Invariants` |
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

### 5.5 行级申请与退款执行（后端第一段）

| AC | 需求 | 落点 |
|---|---|---|
| AC28 | 申请可带 `items[{itemId, qty}]`：按行 × 件数出方案；不传 = 整单剩余；老客户端只给金额 = 整单剩余按额分回；超剩余件数 → 20011；上限是剩余可退额（不再是实付） | `AfterSaleRequestPlanner` → `RefundPlanner` / `ApprovedAmountAllocator`；行存 `ord_after_sale_item` |
| AC29 | 售后期：完成后 7 天、质量类 15 天，过期 → 20012 | `AfterSaleWindowPolicy`（`RefundDomainBeans` 装配 7 / 15） |
| AC30 | 部分退款不改订单状态，进度 PARTIAL；账本说 FULL 才经门迁到 REFUNDED（O10） | `AfterSaleRefundExecutor` → `RefundLedger`；`settleRefundTerminal` 经 `TradeStatusWriter` |
| AC31 | 现金退款顺序：记账（条件增量，并发超退在动钱前 CONFLICT）→ 按额回退分账 → 原路退（键 = 售后单号，同一子单第二笔真的退出钱） | `AfterSaleRefundExecutor`；运营端「分账回退」不再先整单回退 |
| AC32 | 退货按本张售后的行 × 件数回补库存（此前整单补） | `AfterSaleServiceImpl#restoreStockIfReturned(as, plan)` |
| AC33 | 渠道不支持部分退（线下 / 0 元）→ 20013 | `RefundPlan#isPartial` + `ChannelRefundCapability` |

### 5.6 售后写路径经门

| AC | 需求 | 落点 |
|---|---|---|
| AC34 | 售后状态写入全部经转换门（CAS + 一行流水），取代 `setStatus + updateById + appendLog`：撤销 / 寄回 / 介入 / 同意（三种去向）/ 驳回 / 进退款中 / 退款落定 / 裁决 / 寄回超时关闭 | `TradeStatusWriter#afterSale` + `AfterSaleServiceImpl#transit`（同一动作的其余字段随后 `updateById` 并校验返回值，R5） |
| AC35 | 系统退款（交易不成立）建单即「退款中」（A3 TRADE_VOID），不再先建 APPLIED 再走「同意」（同意是商家的决定，I1） | `AfterSaleServiceImpl#systemRefund` |
| AC36 | 归集商家（平台是卖家）的售后在「平台介入」，商家在 B 端「同意」被拒（旧写法放行，等于绕过平台裁决） | 状态机 A4 只从 APPLIED 出发 |
| AC37 | 一张订单同时只有一张进行中售后，由库保证：申请时对子单 CAS 占位（V406 `open_after_sale_no`），进 CLOSED / REJECTED / REFUNDED 释放，被驳回后申请介入重新占位 | `AfterSaleSlots` + `AfterSaleSlotMapper`；实体故意不映射该列（防旧值写回） |

5.5 剩余（下一段）：`GET /mp/order/{no}/refundable`、VO 加 `refundProgress` / `refundedMinor` / `afterSaleDeadlineAt` / `items`、
抵扣积分按比例退（需积分域新增按额退回的接口；今天仍是整单退完才退）。

## §2D 订单重构收口 · 下一步实施计划

> **For agentic workers:** 实施时使用 `superpowers:executing-plans` 逐项执行；如用户选择代理执行，则使用 `superpowers:subagent-driven-development`。下面的复选框记录实际完成情况，不能仅因文档写完而勾选。

**Goal:** 先闭合订单方案，再修复两轮 review 暴露的资金、履约和接口问题，最后完成三端适配及可回滚发布。

**Architecture:** 保留 Payment → 门店 Order → Shipment，以及 Order → AfterSale → AfterSaleItem 的边界。退款决定、资金执行、商品履约和权益返还各自封装为对象；本地事务只保证本地原子性，外部资金操作由持久化执行记录、稳定幂等键、回执和回查恢复。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Flyway、数据库 CAS/条件更新、Outbox；C/B 端 Vue/uni-app，运营端 Next.js/TypeScript。

**Spec:** 本 TDD 与 [订单状态机完整方案](../superpowers/specs/2026-10-14-订单状态机-完整方案.md)、[退货退款逻辑](../superpowers/specs/2026-10-14-退货退款逻辑.md)、[第 5 步对象模型](../superpowers/specs/2026-10-14-第5步-对象模型.md)。拆单基准为 [ADR-031](./ADR/ADR-031-子单按门店拆与运费按商品门店计算.md)。

### 2D.1 范围与约束

- 档位：2（最终实施涉及资金语义、库表、接口与三端）；本轮仅写计划。新增不可逆业务选择在任务 0 中形成 ADR，不能套用旧确认记录。
- 一店一单；支付单可覆盖多笔订单；发货单和物流运单不是同一个资源。
- 售后退款由人的决定触发；系统仅对已确认的交易不成立原因自动原路退回。重试执行已批准决定，不重新做决定。
- 先抽象对象再编码；单类 ≤200 行、单方法 ≤50 行。旧大服务只保留编排，本轮不做无关的全仓拆分。
- 数据库为一致性真源；数据库加本地缓存，暂不上 Redis；部署仍单点，逻辑必须满足集群并发要求。
- 状态、流水、相关业务字段和本地 Outbox 同事务；CAS/条件更新影响 0 行必须处理。跨库和外部渠道不能假设共享本地事务。
- 已应用 Flyway 迁移冻结。每个契约任务自己交付增量迁移、实体和生成物；迁移号在实施当天检查占用后分配。
- 积分域重整仍为独立待确认项目。订单不得把真实已使用的权益交给空实现并当作返还成功；缺能力必须显式记录并提供恢复路径。
- 本计划不包含直接部署、上线数据修复或生产写操作；这些属于任务 9 的独立交付。

### 2D.2 优先级、依赖和对账一

| 任务 | 可独立验收的交付 | 前置 | 对应 review 问题 |
|---|---|---|---|
| 0 | 当前有效方案与决策表，消除旧指令冲突 | 无 | 文档版本、拆单、命名、资金/结清语义缺口 |
| 1 | 商品数量、金额调整、结清与零现金订单模型 | 0 的 D2/D3 | 少额退款耗尽件数、零元售后、积分补偿终态；方案金额/数量混用 |
| 2 | 可恢复的异步退款与正确回执认领 | 1；0 的 D4 | 未到账即已退款、退款回调方向错误、未知结果及双重退款风险 |
| 3 | 原始结算快照与跨分账阶段守恒 | 0 的 D5；1 的金额口径 | 分账前连续退款扣减不足、先退后分账口径 |
| 4 | 按资金路径恢复追偿和权益返还 | 2、3；0 的 D5/D6 | 归集已付款后的追偿、积分补偿与现金终态冲突 |
| 5 | 原子备货、完成与多包裹收敛 | 1；0 的 D3 | 备货无 CAS、自动收货缺事务；部分商品取消、并发收货、物流乱序 |
| 6 | 支付影响集合、权限及线下收款边界 | 0 的 D1 | 运营取消级联不完整、取消影响范围、跨门店当面收款 |
| 7 | 三端支付视图、结果和取消确认一致 | 6 | 子单显示金额与实际支付金额不一致、错误支付成功判据 |
| 8 | 可退额、动作合同与三端行级售后 | 1、2、5、6；0 的 D6 | 可退额/原因/截止缺输入、allowedActions、三端适配未完成 |
| 9 | 兼容矩阵、迁移演练、全链路验收和发布包 | 2–8 | 旧端新状态无入口、灰度启用和回滚边界 |

执行顺序：**0 → 1 → 2/3 → 4；0 → 6 → 7；1 → 5；随后 8 → 9**。分支之间存在同文件和契约依赖时串行实施，不按这张表机械并发。任务 0 完成后，每个实施任务仍须先完成自身精确字段、签名及 AC 的 TDD 对账；本节不是未定业务语义的编码授权。

### 2D.3 待统一的业务选择（任务 0 的交付）

| 编号 | 建议选择 | 必须明确的边界 | 未闭合时暂停的任务 |
|---|---|---|---|
| D1 | 线上支付按支付单；第一期当面付款限制单门店；取消整笔支付前展示全部影响集合并检查授权 | 存量跨店线下单不能由任一家店单独确认全款，需明确对账处理；不得通过绕过数据域代替授权 | 6、7 |
| D2 | 区分取消商品、退回商品、已交付免退回退款、金额调整；金额调整不消耗退件数量，也不回补库存 | 覆盖、关闭交易、实际退件、取消免履约件数分别记录；商家少退必须是买家接受的协议或平台裁决，不能单方核定 | 1、2 |
| D3 | 现金退款进度与商品售后结清分开；全部商品免履约后退款结清则订单 REFUNDED，交付与取消混合结清则 COMPLETED | 保留运费/约定损耗不再作为无条件可再次申请的欠退额；零现金商品也能结束；券与积分按各自政策返还 | 1、5、8 |
| D4 | 批准决定先持久化；预占、受理、到账分开；结果未知先回查原请求，再考虑补偿或替代渠道 | 同一个执行号下的金额不可被重试改写；成功回执幂等落实退账本；未知状态不允许另开线下退款 | 2、4 |
| D5 | 不可变原始结算快照做累计切分；剩余应付、实际付款、累计回退、待追回分开 | 归集已付供应商时买家退款与供应商追偿分开记录；追偿责任、垫付来源、权限和失败处置形成单独决策 | 3、4 |
| D6 | C/B/运营端共享资源与动作定义；原因和选行参与预览；新状态按客户端能力启用 | 积分补偿是否保留独立结案类型；新积分方案是否采用现金抵扣，尚未确认前不能混入订单现金退款口径 | 4、8、9 |

这些是供下一步方案收口使用的**建议**。任务 0 要写清选项、取舍及确认记录，再将确定结果回写对应方案；不能把本表页眉改成“全部已确认”来跳过决策。

### 2D.4 文件边界与拟定接口

下面列出的新文件和新签名均为计划，不表示已经存在。业务依赖结论变化时先修订本表，再实施。

| 文件/目录（仓库相对路径） | 职责与所属任务 |
|---|---|
| `docs/technical/TDD-订单状态机.md`；`docs/superpowers/specs/2026-10-14-订单状态机-完整方案.md`；`docs/superpowers/specs/2026-10-14-退货退款逻辑.md`；`docs/superpowers/specs/2026-10-14-第5步-对象模型.md` | 任务 0 统一当前有效规则；历史记录保留 |
| `docs/superpowers/specs/2026-10-12-订单服务-核心逻辑与方法实现.md`；`docs/superpowers/specs/2026-10-12-订单领域模型-草案.md`；`docs/superpowers/specs/2026-10-11-订单服务-详细设计.md` | 任务 0 修正矛盾指令，标注被替代章节及接替者 |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/model/` | 任务 1 扩展退款行；拟新增 `RefundPurpose.java`、`RefundClosure.java`，分别定义动作语义和结清结果 |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/RefundPlanner.java`；`RefundLedger.java`（同目录） | 任务 1/2 分开预占与实退；拟新增 `trade/order/refund/policy/OrderClosurePolicy.java` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/settle/SettlePort.java` | 任务 2 拟新增 `requestRefund(RefundRequest request): RefundDispatchResult`；旧字符串接口只用于兼容，不能作为新流程的成功判据 |
| `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentLedgerServiceImpl.java`；`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/impl/SettleServiceImpl.java` | 任务 2 回执方向、请求认领、执行结果及恢复；资金流水复用，不创建第二套资金总账 |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/AfterSaleRefundExecutor.java`；`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/AfterSaleServiceImpl.java` | 任务 2/4 编排执行记录、成功回执及权益任务，原大服务不内嵌新政策 |
| `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/settle/refund/`；`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/mapper/BillRefundMapper.java` | 任务 3 原始快照、累计切分、分账前后位置；拟新增 `SettlementRefundBasis.java` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java`；`MerchantOrderServiceImpl.java`（同目录）；`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/transition/StateTransitionGate.java` | 任务 5/6 自动收货事务、条件备货、支付影响集合；不删除原地动作流水 |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/receipt/OrderCompletion.java`；`ReceiptHandler.java`（同目录）；`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/ship/` | 任务 5 免履约数量、订单级串行点、发货及收货接线 |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/mp/MpTradeController.java`；`backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/OrderVO.java`；`AfterSaleVO.java`（同目录） | 任务 8 可退额和动作合同；若控制器变大，按资源拆分 |
| `c-app/src/pages/pay/index.vue`；`c-app/src/pages/order/index.vue`；`ops-web/app/orders/proxy-tab.tsx` | 任务 7 支付单金额/结果与整笔取消确认 |
| `c-app/src/pages/after-sale/index.vue`；`b-app/src/pages/after-sale/index.vue`；`ops-web/app/orders/`；三端 API/类型与 locale | 任务 8 选行件数、金额预览、动作、退款进度及提示 |
| `backend/shop-app/src/main/resources/db/migration/` | 各任务所需增量迁移随自身实现交付；任务 9 只做综合演练，不推迟基础数据结构 |

拟定交接合同：

- `RefundDispatchResult.status ∈ {ACCEPTED, SUCCEEDED, FAILED, UNKNOWN}`，含稳定执行号、资金流水号、错误分类及是否可重试；`ACCEPTED` 不代表到账。执行记录还需 `PREPARED`，让提交后发送可恢复。
- `RefundClosure` 包含 `cashProgress`、`goodsClosed`、`orderTarget`；现金进度和商品结清不能共用一个布尔值。权益是否返还由独立政策决定。
- `SettlementRefundBasis` 存原始商品实付、商家所得、佣金、服务费、补贴与运费归属快照；新累计政策消费原始快照和已退累计，不消费已扣减金额作为原始总额。
- GET refundable 只读可退事实和普通/质量双窗口；`AfterSalePreviewRequest` 在新增只读 POST after-sale-preview 中消费订单 ID、`reasonCode`、售后类型与 `items[{itemId,qty}]`，输出逐行金额、运费、有效窗口、渠道能力、版本。预览不预占，提交必须用同一规则重新校验。
- `ActionDescriptor` 定义所属资源、事件码、允许目标、金额/输入约束；事件到 HTTP 端点的映射放共享契约，按钮资格与提交校验分开。字段缺失表示旧合同，空集合表示当前确实无动作。

### 2D.5 Review Focus（每项在所属任务中有验收）

1. 请求超时但通道随后成功：不能重复退款，也不能提前启用线下兜底（任务 2）。
2. 所有商品已退回，但有合法保留运费/损耗；或现金为 0：商品与权益应正确结清（任务 1/4）。
3. 两线程确认最后两个包裹；已取消商品从未发货：订单必须收敛且不能漏结算（任务 5）。
4. 跨门店主单、受限运营数据域、从子单进入支付：金额与全部副作用必须一致且获授权（任务 6/7）。
5. 旧客户端读取协商或线下待确认状态，退款进行中回滚：必须仍有合法操作入口和恢复执行者（任务 8/9）。

### 2D.6 逐项任务

每个代码任务的标准周期：**失败测试 → 确认失败原因 → 最小实现 → 测试与相应守卫 → 独立 review → 精确文件提交**。代码实施时维护本 TDD §5/§6 的真实对账，不用计划中的期望输出冒充已运行结果。

命令约定：本节 `mvn` 命令在 `backend/` 执行，使用 JDK 21；本机可设置 `JAVA_HOME=/opt/homebrew/opt/openjdk@21` 并将其 `bin` 加入 `PATH`。`npm -w` 命令在仓库根执行，Node ≥22，依赖按锁文件准备。缺依赖、构建未启动或没有执行目标测试属于环境失败，不能算作新增行为的失败证据。前端类型检查使用 `npm run type-check -w ai-shop-c-app`、`npm run type-check -w ai-shop-b-app`、`npm run typecheck -w ai-shop-ops-web`。

#### 任务 0：统一文档真源及业务决策

Files：2D.4 的四份当前方案、三份历史草案、`docs/technical/README.md`；涉及不可逆决定时新增 ADR（编号先查占用）。

- [ ] 0.1 建立资源表：支付单、门店订单、发货单、物流运单、售后单；逐端点记录 ID 类型、金额与副作用粒度、权限覆盖集合及旧别名。
- [ ] 0.2 将 D1–D6 各整理为一页对象职责、数据/状态、协作者和反例；明确采用结论与仍需决定的业务条件，不先写实现。
- [ ] 0.3 回写已经确定的状态迁移、到账/账本顺序、数量/金额定义和累计算法；保留旧决定的日期与替代关系，修正按 `entityNo` 拆单残留。
- [ ] 0.4 同步页眉与索引，逐条检查两轮 review 都映射到 2D.2 的任务。未确认的积分重整保持独立状态。
- [ ] 0.5 验证 `git diff --check`、本节所有本地 Markdown 链接与文件路径；提交精确文档清单。退出条件：每个准备实施的任务，其前置 D 项有明确结论。

#### 任务 1：分开退款数量、金额和商品结清

Files：退款 model/policy/service（见 2D.4）、`ord_item`/售后行的增量数据合同。测试：现有 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/LineRefundFlowTest.java`；拟新增同目录 `RefundClosureFlowTest.java`。

Interfaces：生产 `RefundPurpose`、扩展后的退款行与 `RefundClosure`，后续任务 2/4/5/8 消费；金额调整不等于退件或取消履约。

- [ ] 1.1 添加 `moneyAdjustmentDoesNotConsumeQty`：3 件实付 3000，调整 1000，断言 `closedQty == 0`、余款 2000 仍可售后；添加 `returnedGoodsWithAgreedLossCloseOnce`、`zeroCashOrderCanReturnBenefits`，同时断言真实库存与返还权益。
- [ ] 1.2 运行：`mvn -o -B -pl shop-app -am -Dtest=LineRefundFlowTest,RefundClosureFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`；预期相关新增断言失败，记录具体差异。
- [ ] 1.3 按 D2/D3 实现覆盖数量、退件数量、免履约数量和现金额；约定损耗进入协议事实；零现金流程跳过现金通道而保留商品及权益处理。
- [ ] 1.4 同命令验证；消融“金额调整不耗件”的实现应使新增场景变红。同步迁移/实体/契约生成物及 §5/§6。
- [ ] 1.5 独立 review 后提交。退出条件：退件、金额调整、合法留存额和零现金四类场景有唯一结清规则。

#### 任务 2：闭合退款执行、回执和重试协议

Files：`SettlePort`、资金流水与退款编排（见 2D.4），`backend/shop-core/src/main/java/ai/neargo/shop/trade/job/RefundRetryJob.java`。测试：现有 `ChannelPayCallbackFlowTest.java`、`RefundReachesChannelTest.java`；拟新增同场景目录 `RefundExecutionRecoveryFlowTest.java`。

Interfaces：消费任务 1 的退款方案，生产 `requestRefund(RefundRequest): RefundDispatchResult` 与稳定执行记录。一次人的批准只产生一个不可变金额的执行请求。

- [ ] 2.1 添加 `refundCallbackClaimsRefundDirection`、`acceptedIsNotSettled`、`timeoutThenSuccessSettlesOnce`、`restartResumesApprovedDecision`：分别断言 REFUND 流水被认领、受理后售后仍 REFUNDING、成功仅记一次、重启后不丢决定。
- [ ] 2.2 运行：`mvn -o -B -pl shop-app -am -Dtest=ChannelPayCallbackFlowTest,RefundReachesChannelTest,RefundExecutionRecoveryFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`；预期新增恢复场景失败。
- [ ] 2.3 在本地事务提交执行记录及预占，再发送外部指令；回执/回查按执行号幂等归并，成功才转实退及终态。永久失败、可重试失败和结果未知分别处理；查询退款必须使用 REFUND 方向。
- [ ] 2.4 注入“回退已成功、请求超时、本地后续失败”及重复/乱序回执，同命令验证。无确定失败证据不得启用另一路退款；不同数据源的提交点逐项留证据。
- [ ] 2.5 独立 review 后提交。退出条件：拒绝/受理/成功/未知四种结果均有恢复路径，无需客服猜测资金状态。

#### 任务 3：结算快照与跨阶段累计守恒

Files：结算退款包、`BillRefundMapper`、结算实体与增量迁移（见 2D.4）。测试：现有 `DefaultSettlementRefundPolicyTest.java`、`SettlementPartialReverseTest.java`。

Interfaces：生产不可变 `SettlementRefundBasis`；策略按原始快照及退款前后累计算差，剩余应付与已付金额另记。

- [ ] 3.1 添加 `threePreSplitRefundsClearOriginalBasis`：商品实付 300、商家所得 270，三次各退 100，断言累计扣减 270、余款 0；添加 `refundThenSplitThenRefundConservesShares`，覆盖佣金、服务费、补贴和尾差。
- [ ] 3.2 运行：`mvn -o -B -pl shop-app -am -Dtest=DefaultSettlementRefundPolicyTest,SettlementPartialReverseTest -Dsurefire.failIfNoSpecifiedTests=false test`；预期新增分账前/跨阶段场景失败。
- [ ] 3.3 保存原始金额快照或经确认的等价可恢复来源；累计切分固定使用该基数；更新剩余/已付/已回退位置保持 CAS，存量快照无法可信还原的单进入人工核对而非猜值。
- [ ] 3.4 同命令验证三次分退、先退后分账、运费归属及重放；改回当前金额作原始基数应变红。同步迁移与 §5/§6。
- [ ] 3.5 独立 review 后提交。退出条件：每条资金路径的原始金额与全部后续位置守恒。

#### 任务 4：追偿、库存与权益的可靠收尾

Files：退款编排、结算退款包；复用现有 coupon/stock/points 的 SPI 和持久化事件。拟新增测试 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/RefundBenefitsRecoveryFlowTest.java`。

Interfaces：消费成功执行记录与任务 1 的结清事实，生产按售后执行号幂等的权益/库存处理记录及追偿结果；不另造积分总账。

- [ ] 4.1 添加 `aggregatedPaidSupplierCreatesReceivable`、`benefitFailureRetriesWithoutSecondCashRefund`、`compensationDoesNotRefundWholeOrder`；断言追偿与买家现金分别有状态、权益失败可恢复、补偿不误退整单券/抵扣积分。
- [ ] 4.2 运行：`mvn -o -B -pl shop-app -am -Dtest=RefundBenefitsRecoveryFlowTest,LineRefundFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`；预期新增跨域恢复断言失败。
- [ ] 4.3 按 D5 的责任及垫付决策实现归集追偿；成功现金回执后可靠记录权益/库存工作，每项各自幂等。真实退货或取消免履约才影响库存；补偿独立结案，不冒用现金退款终态。
- [ ] 4.4 同命令验证跨库失败、重复事件和重启；缺积分能力时保留可见未完成记录。新积分方案未确认时不得实现其扣现金或新户模型。
- [ ] 4.5 独立 review 后提交。退出条件：现金、追偿、库存、券和积分均能定位自身结果及恢复责任方。

#### 任务 5：原子履约与订单完成收敛

Files：转换门、订单/商家服务、收货及发货内核（见 2D.4）。测试：现有 `OrderAutoReceiptFlowTest.java`、`TradeStatusGateFlowTest.java`；拟新增同场景目录 `OrderCompletionRaceFlowTest.java`。

Interfaces：消费任务 1 的免履约数量；生产完整交付/取消闭合事实与 `completed_at`，供售后期、结算和动作计算使用。

- [ ] 5.1 添加 `prepareRaceWritesOneFactAndLog`、`autoReceiptLogFailureRollsBackCompletion`、`cancelledItemAndDeliveredItemCompleteOrder`、`lastTwoShipmentsReceivedConcurrentlyCompleteOrder`、`signedSnapshotWithoutPickupConverges`。
- [ ] 5.2 运行：`mvn -o -B -pl shop-app -am -Dtest=OrderAutoReceiptFlowTest,TradeStatusGateFlowTest,OrderCompletionRaceFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`；预期新增故障/并发场景失败。
- [ ] 5.3 备货条件更新同时校验首次、状态和售后占位；自动收货按订单独立事务写状态、流水、完成时刻。本地完成判断先取得订单级串行点，再用当前事实重算；不得用两次独立包裹 CAS 代替聚合协议。
- [ ] 5.4 接入发货/收货内核；可信物流快照补齐缺失迁移，事件绑定运单/发货单版本，作废旧单不得推进替代单。验证接口完成后真实运行 B/C 页面，不能只看纯函数测试。
- [ ] 5.5 同命令及相关页面检查通过、消融并发串行点后用例变红，再独立 review/提交。退出条件：所有已交付或取消免履约的订单最终必有正确终态。

#### 任务 6：支付影响范围、取消权限与线下收款

Files：`OrderServiceImpl`、`MerchantOrderServiceImpl`、对应支付/运营控制器。测试：`TradeStatusGateFlowTest.java`；拟新增同场景目录 `PaymentScopeFlowTest.java`。

Interfaces：生产完整支付影响集合，包含支付单、门店订单、金额和可操作权限；资源范围来自服务端，不信任客户端声明的商家身份。

- [ ] 6.1 添加 `restrictedOperatorCannotPartiallyCancelPayment`、`authorizedCancelClosesAllChildrenAndReleasesOnce`、`offlinePaymentRejectsMultiStoreBasket`；断言跨店集合完整、权限不足不写任何状态、资源只释放一次。
- [ ] 6.2 运行：`mvn -o -B -pl shop-app -am -Dtest=TradeStatusGateFlowTest,PaymentScopeFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`；预期新增受限数据域及线下场景失败。
- [ ] 6.3 按 D1 先检查整个影响集合的授权，再在事务内执行完整级联；只在授权后的限定支付单范围内豁免数据域。新线下单单门店约束必须在建单入口执行，存量异常单按任务 0 明确的处理规则处置。
- [ ] 6.4 同命令验证主/子单入口一致、取消撞付款及释放幂等，检查所有更新行数；同步 API 对账。
- [ ] 6.5 独立 review 后提交。退出条件：任何成功响应的支付/取消动作，其全部影响集合都已授权并一致完成。

#### 任务 7：三端支付视图与结果一致

Files：2D.4 的支付/订单页和运营取消页；拟新增 `c-app/tests/pay-page.test.ts`，复用 `c-app/tests/orders-page.test.ts`，拟新增 `ops-web/app/orders/proxy-cancel.test.ts`。

Interfaces：消费任务 6 的支付单视图与影响集合；三端跳转不得靠 `orderNo` 字符串形状猜资源粒度。

- [ ] 7.1 添加 `subOrderEntryShowsWholePaymentAmount`：A 店 10 元/B 店 20 元，断言收银台展示及实际请求均覆盖 30 元和两家店；添加 `cancelledAndOfflinePendingAreNotPaid`、`delayedCallbackRefreshesToPaid`、`cancelConfirmationShowsAllAffectedOrders`。
- [ ] 7.2 运行 `npm run test -w ai-shop-c-app -- tests/pay-page.test.ts tests/orders-page.test.ts` 与 `npm run test -w ai-shop-ops-web -- app/orders/proxy-cancel.test.ts`；预期新增金额/状态断言失败。
- [ ] 7.3 子单入口解析后加载支付单视图；明确已收款、待收款、取消及未知结果；使用有界回查、重进页面刷新和可恢复提示。确认取消前展示完整影响集合。
- [ ] 7.4 同测试验证，再分别运行 C 端 `vue-tsc` 与运营端类型检查；实际预览多门店、未收款、取消和回调延迟场景。
- [ ] 7.5 独立 review 后提交。退出条件：页面金额、支付通道金额、状态文案和操作影响一致。

#### 任务 8：可退额、动作合同与三端行级售后

Files：订单/售后 DTO 和控制器、三端售后页面及 API/types/locale（见 2D.4）。拟新增 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/RefundPreviewAndActionsFlowTest.java`、`c-app/tests/after-sale-preview.test.ts`；复用 `b-app/tests/after-sale-approve.test.ts`。

Interfaces：生产 2D.4 的 `AfterSalePreviewRequest`、结果和 `ActionDescriptor`；与任务 1/2 共用金额、截止与执行结果语义。

- [ ] 8.1 添加 `qualityReasonAtDayEightStillEligible`、`previewMatchesSubmittedPlan`、`interveneTargetsIntersectPermissionAndScope`、`oldContractMissingActionsDiffersFromEmptyActions`；前端测试选行/件数变化、核定上限与退款进度。
- [ ] 8.2 运行 `mvn -o -B -pl shop-app -am -Dtest=RefundPreviewAndActionsFlowTest -Dsurefire.failIfNoSpecifiedTests=false test`、C/B 端对应测试；预期新增合同场景失败。
- [ ] 8.3 接入带原因/类型/选行的预览；动作描述附在对应资源，显式目标/输入约束；提交重验权限与版本。C 端选行件数，B 端行明细与协商，运营端执行/退款进度沿同一合同展示。
- [ ] 8.4 生成 OpenAPI/三端契约，跑 API 与 enum/i18n 守卫；两端 `vue-tsc`、运营端类型检查及真实页面预览通过后更新界面清单。
- [ ] 8.5 独立 review 后提交。退出条件：相同事实在三端显示相同金额和阶段，按钮合法性与后端提交规则一致。

#### 任务 9：综合验收、兼容与发布准备

Files：上述 TDD/方案、现有部署与迁移说明；不直接进行生产写入或发布。

- [ ] 9.1 形成客户端能力 × 状态/动作矩阵；定义每个新状态的创建门槛、旧端处理与最低版本。无法完成接受/确认动作的客户端不得收到新创建的等待状态。
- [ ] 9.2 用代表性存量单演练增量迁移：已退款、进行中售后、零现金、多店及缺快照单；核对流水/库存/权益/结算守恒。不可可信还原的数据列出人工核对清单。
- [ ] 9.3 补齐本节 Review Focus 的全链路场景，并在故障点前后模拟重启。保持稳定执行号，不因回滚应用代码丢失退款续跑者。
- [ ] 9.4 在明确提交号的干净副本运行全量闸门、三端类型与测试、迁移/契约/生成物检查；记录实际输出及扫描范围，完成 §5/§6 对账。
- [ ] 9.5 输出发布顺序、兼容期、可回滚版本、必须前向修复的数据边界、监控/人工处理入口。独立 review 后才形成发布交付；生产部署与数据修复另行执行。

### 2D.7 验收证据与当前状态

任务 0 的文档内容已整理为待评审修订，包括资源与用途定义、恢复边界及历史文档替代关系；不等同于所有 D 项已获确认，提交步骤也尚未执行。任务 1–9 全部**未实施**。两轮 review 中的现有测试通过和隔离算法复现是问题定位证据，不能作为上述新增场景通过的证据。实施时每个任务登记：新增测试方法、失败输出、通过输出、消融结果、提交号、实际文件清单及偏差；任务 9 另登记整套闸门与兼容演练。

计划自检：两轮 review 的资金/数量/回执/结算问题映射到 1–4；事务/备货/完成/物流映射到 5；取消范围和线下权限映射到 6；收银台映射到 7；预览/动作/三端映射到 8；版本治理与上线回滚映射到 0/9。暂不交付 Redis、多实例部署、全仓重构或未确认积分域重整。

## §2E 详细代码修复计划入口

总体任务是范围与依赖概览。开发次序以 [TDD-订单重构修复与三端接线 §2.6](./TDD-订单重构修复与三端接线.md) 的7阶段、26批次为准；§2.5保留F01–F18详细修复清单，同一问题不重复执行两份清单。

| 总体任务 | 详细批次 |
|---|---|
| 1 数量、金额与商品结清 | F08 |
| 2 异步退款和回执 | F01、F09–F11 |
| 3 结算守恒 | F06–F07 |
| 4 权益、库存和追偿 | F12 |
| 5 履约与发货收敛 | F02–F03、F13–F14 |
| 6 支付影响及权限 | F04；线下单店门槛按 D1 合同落实 |
| 7 三端支付视图 | F05（运营取消确认依赖 F04） |
| 8 行级售后及动作 | F15–F17 |
| 9 兼容和发布 | F18 |
| 当前完成状态、归属与shop.order迁移 | B00、M01、M02；迁包前先完成I05收口 |
| 原清单未覆盖的真实查询/下单/总入口接线 | I01–I05；复用已建Handler，补足现有业务字段、金额及生产Store |

确定性修复、扩表捕获、新语义切读写、新端入口分别有验收。详细计划列明字段、方法、事务、错误分流、失败断言、迁移和回滚条件；本轮不勾选代码步骤，不改旧测试期待值，不修改生产迁移。

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
| AC28 / AC30 / AC31 | `LineRefundFlowTest#partialThenRest`（3 件退 1：状态不变、PARTIAL、结算单未 REVERSED；再退剩余：REFUNDED / FULL、两笔退款流水各挂各的售后单）+ `#overQtyRejected` | ✅ | 落定即 REFUNDED（旧行为）→ 红 ✅；退款键改回子单号 → 第二笔被吞、红 ✅ |
| AC29 | `LineRefundFlowTest#windowGuard`（8 天后非质量 20012、质量可申请） | ✅ | 去掉守卫 → 红 ✅。顺带修 `AfterSaleRules.isQualityReason(null)` NPE（`List.of().contains(null)`） |
| AC34 | `LineRefundFlowTest#approveAndRejectRace`（同意与驳回并发：恰一方成功，状态与退款流水一致）；M5 售后 40、M7 结算 33、售后整组 195 绿 | ✅ | 驳回换回 `setStatus + updateById`（加 300ms 延迟强制重叠）→ 两方都「成功」、钱退了状态却是驳回，红 ✅ |
| AC36 | `M7SettleFlowTest#refundReversesSplitFirst` / `#refundBeforeSplitNeedsNoReversal`、`OpsFinanceGovernFlowTest#queueExcludesReturnRefundWaitingForGoods`、`OpsDashboardRankingFlowTest#afterSaleRateReflectsRealTickets`：夹具改为直连商家（原来靠旧写法放行归集商家的「同意」） | ✅ | — |
| AC37 | `LineRefundFlowTest#concurrentApplyOccupiesOnce`（两次申请同时到达只建成一张；驳回释放、可再申请；新单进行中旧单再介入 → 10409） | ✅ | 去掉申请时占位（只留先查后插）→ 两张都建成，红 ✅ |

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
