# TDD-支付服务-I3支付单退款流水

状态：实施中
档位：2 · 依据：[开发计划 · 支付服务](./开发计划-支付服务.md) §5 I3 · [完整设计方案](./TDD-支付服务-完整设计方案.md) · [代码清单](./支付服务-代码清单-开放API刷卡POS内部调用.md) §2.1–§2.4、§5.2–§5.3 · [台湾阿联酋通道与开放API](./TDD-支付服务-台湾阿联酋通道与开放API.md) §2.8.2 能力矩阵 · [代码 Review](./支付服务-代码Review-2026-10-24.md) R18 / R20 / R21 · [数据库表结构说明 v4](./TDD-支付服务-数据库表结构说明.md)
创建：2026-10-25 · 最后更新：2026-10-25

把「收一笔钱」从「订单号 + 一串通道尝试」改为**支付单**：调用方（商城、餐饮，I5 起第三方）要收的一笔钱是一张 `pay_order`，
通道尝试 `stl_payment` 挂在它下面；成功 / 失败只有一个入口把单子推到终态，并同事务记一张借贷平衡的流水凭证；
退款是 `pay_refund`，占用可退余额、按复核规则决定要不要两人复核，执行沿用现有「先落库 → 抢发送权 → 不明只查不重发」。
内部系统经 `PaymentPort` 调支付（内嵌直调，独立形态经 pay-svc 三个内部端点）。**每片先写会变红的测试，修复后变绿，再撤修复做一次消融。**

## 切片

| 片 | 内容 | 提交 |
|---|---|---|
| S1 | 迁移 7–9（`db/pay` V7–V9）：`pay_app`（种子 MALL / FOOD）、支付单 / 退款 / 复核规则 / 两人复核 / 支付异常 / 收银会话、流水两表；`stl_payment` 加 8 列 2 索引、`stl_bill` 加 2 列；9 个实体与 Mapper | — |
| S2 | 调用方与能力矩阵：`PayCaller`、`PayAction`、`CapabilityMatrix`、`MatrixCallerPolicy`、`InternalCallers`（内部调用方的唯一构造点） | — |
| S3 | 支付单：`PaymentService`（建单幂等 + 请求摘要、查询、关单）、`PayOrderStateMachine`；`spi.pay.PaymentPort` + `PaymentPortImpl`；商城与餐饮下单先建支付单，`stl_payment` 挂 `pay_order_id` | — |
| S4 | 结果唯一入口 `PaymentOutcomeService`：回调 / 补查 / 掉单巡检 / 刷新全部经它；关单后到账建 `pay_exception(LATE_PAYMENT)` 不静默成功（R18）；应付不被实收覆盖（R20） | — |
| S5 | 流水：`FundsPathStrategy`（DIRECT / AGGREGATED）+ `LedgerService`（幂等、借贷平衡、只增不改） | — |
| S6 | 退款单：`RefundService`（商家操作员、可退余额、占用、复核规则、两人复核）+ `RefundOutcomeService`；旧 `refund` 系列转 `RefundExecutionService`、用真实售后单号（R21） | — |
| S7 | 关单与事件：订单超时关单调 `PaymentPort#closePayment`（关前必查单，R18）；`PaymentSucceededEvent` / `PaymentClosedEvent` 经 Outbox | — |
| S8 | 独立形态：pay-svc `/internal/pay/v1/payments` · `/refunds` + `InternalCallerFilter`（按调用方授权、常量时间）；`RemotePaymentPortImpl`；内嵌 / 独立一致性 | — |
| S9 | 回填 B6（存量 `stl_payment` → `pay_order`）、B7（流水期初）与 Q5 / Q7 校验 SQL | — |

## §0 对账一 · 需求 → 设计

| AC | 验收（来自开发计划 I3 测试与完成标准 · 代码清单 §7） | 片 |
|---|---|---|
| AC1 | 迁移 7–9 在 H2 与 MySQL 9.7 上都能执行；新表实体与表逐列对齐；种子 MALL / FOOD / CN 复核规则（DRAFT） | S1 |
| AC2 | 能力矩阵逐格（含禁止格）；门店范围外一律 `PAY_FORBIDDEN`；**内部系统不能以系统身份退款**、退款无操作员被拒（`CallerPolicyMatrixTest` · `PaymentPortImplTest#refund_without_operator_rejected`） | S2 |
| AC3 | 同一调用方单号再建返回同一张支付单；同号不同内容 → `PAY_ORDER_CONFLICT`；商城与餐饮新单都有 `pay_order` 且尝试挂上 `pay_order_id`（完成标准 Q5 = 0） | S3 |
| AC4 | 回调重复只入账一次；关单后到账建 `LATE_PAYMENT` 异常、支付单不变成功；应付金额不被实收覆盖（`PaymentOutcomeServiceTest#notify_twice_books_once` · `#late_payment_creates_exception`） | S4 |
| AC5 | 每笔支付成功 / 退款成功一张借贷平衡的凭证；重复事件不重复记；流水只增不改（完成标准 Q7 = 0） | S5 |
| AC6 | 退款收敛：同一子单第二次售后退款不再撞幂等（`AS-{subOrderNo}`）；超出可退余额被拒；在途占用计入可退余额 | S6 |
| AC7 | 复核规则：市场无 ACTIVE 规则 → 全部复核；命中门槛 → 复核；两人复核申请人 / 第一人 / 第二人互不相同 | S6 |
| AC8 | 订单超时关单同时关掉支付单与未终态尝试；关前查到已付不关（R18） | S7 |
| AC9 | 独立形态：FOOD 调不了商城的退款；令牌常量时间比较；内嵌与独立对同一输入结果一致（`PaymentPortLocalRemoteParityTest`） | S8 |
| AC10 | `SettlePortImpl` 现有用例全绿（「首次 `out_trade_no` = 订单号」保持）；全量回归不新增失败 | 全部 |

## §1 现状与影响面

- **收款**：商城 `OrderServiceImpl#pay` → `SettlePort#initPayment` → `OrdinaryPaymentInitiationServiceImpl`；餐饮 `IndustryCheckoutServiceImpl` → `OrdinaryPaymentPort#open / initiate / refresh`。两条都落到 `PaymentLedgerService#open`（`stl_payment`），商户单号首次 = 订单号、重试 `-2`、`-3`。**没有「支付单」**：一笔钱的身份是订单号。
- **结果**：渠道回调（`ChannelPayCallbackController` / stub 回调）、补查（`PaymentFollowUpResolver`）、掉单巡检（`PaymentReconReconciler`，直调 `ledger.settle`）、餐饮刷新（`OrdinaryPaymentPortImpl#refresh`）各自调 `PaymentLedgerService#settle`，之后各自 `markPaid`。`settle` 只认「已 SUCCESS 就幂等」：**已 CLOSED 的尝试会被静默改成 SUCCESS**（R18）；且用通道金额覆盖应付金额（R20）。
- **关单**：订单超时关单（`OrderAutoCloseJob` → `closeExpiredOrders`）**一行支付域都不碰**；`PaymentLedgerService#close` 生产无调用方（R18）。
- **退款**：普通售后经 `RefundExecutionService`（执行号 `RF-{售后单号}`）；积分售后经 `SettleServiceImpl#prepareRefund / dispatchRefund`；三参 `refund` 合成 `AS-{subOrderNo}`（R21，生产无调用方，测试仍用）。
- **门店**：商城订单头没有门店（在子单上）；SPI 里没有 `storeNo → mch_store.id`。新表一律按 `mch_store.id` 关联核心系统 —— 需要一个门店身份查询口（见 §2 S3）。
- **影响面**：`pay-domain`（新 `access` / `acquiring` / `approval` / `funds` / `ledger` 包，`PaymentLedgerServiceImpl` 改造）、`shop-base`（`spi.pay.PaymentPort` 与命令 / 事件、`MerchantQueryPort` 加一个方法）、`shop-merchant`（实现那个方法）、`shop-core`（下单先建支付单、超时关单关支付单）、`shop-app`（`SettlePortImpl`、消费者、远程实现）、`pay-svc`（两个内部端点 + 过滤器）。

## §2 方案

### S1 · 迁移 7–9 与实体
- `db/pay` V7 `pay_app`（+ 种子 MALL / FOOD，`owner_type=INTERNAL`）· V8 支付单、退款单、复核规则（+ CN DRAFT 种子：未生效 = 全部复核）、两人复核、支付异常、收银会话，`stl_payment` 加 `pay_order_id` 等 8 列（`sub_merchant_id` 已在 V2）+ 2 索引，`stl_bill` 加 `market`、`pay_order_id` · V9 流水凭证与分录。DDL 逐字取自 `pay-service-review.sql`。
- 实体：`openapp.entity.PayApp` · `acquiring.entity.{PayOrder, PayRefund, PayRefundReviewRule, PayException, PayCheckoutSession}` · `approval.entity.PayApproval` · `ledger.entity.{PayLedgerVoucher, PayLedgerEntry}`；`StlPayment` / `StlBill` 补新列。
- Mapper：`mapper.acquiring.AcquiringMappers`（`PayOrderMapper#lockById` / `PayRefundMapper#lockById` 用 `FOR UPDATE`）· `mapper.ledger.LedgerMappers` · `mapper.openapp.OpenAppMappers`。

### S2 · 调用方与能力矩阵
- `access.PayCaller`（类型、callerId、门店范围、操作员、店长授权人、顾客）· `access.PayAction` · 复用 I2 的 `channel.api.CallerType`（六类接入端，不另立一份）。
- `access.policy.CapabilityMatrix`：唯一定义处，逐格照设计 v4 §2.8.2；`MatrixCallerPolicy`：矩阵 ∩ 门店范围（运营端除外）∩ 退款必须有操作员。
- `acquiring.port.InternalCallers`：内部调用方的唯一构造点。`system(app, store)` = INTERNAL（不能退款）；`merchantOperator(app, store, operator)` = BIZ（商家在业务系统里点的退款，操作员必填）。app id 按 `pay_app.app_no` 取。
- 错误码 70082–70088（`PAY_FORBIDDEN` 等 7 个）+ 三语文案。

### S3 · 支付单
- `spi.pay.PaymentPort`（`createPayment` · `queryPayment`；关单与退款随 S6 / S7 加）· `InternalCreatePaymentCmd` · `PaymentHandle`。
- `MerchantQueryPort#payStoreOf(storeNo) → PayStore(storeId, 名称, 主体, 市场)`：**由调用方（交易域）解析门店身份交给支付**，支付不反向依赖商户域。
- `acquiring.statemachine`：`PayOrderStatus` · `PayOrderEvent` · `PayOrderStateMachine`（迁移表；CLOSED → SUCCEEDED 不存在）。
- `acquiring.service.PaymentService` / `impl.PaymentServiceImpl`：第一行 `callerPolicy.check`；`(pay_app_id, out_order_no)` 幂等 + `request_sha256`（app、业务类型、单号、门店、市场、币种、金额；标题与截止时间不算）；撞唯一键回读再比；`attachAttempt` 在开尝试的同一事务里写 `stl_payment.pay_order_id` / `market`，支付单 CREATED → AWAITING_PAYMENT。
- `acquiring.port.PaymentPortImpl`（bizType → 内部 app → `InternalCallers.system`）；装配在 shop-app `payclient.OrdinaryPaymentRouting`（embedded 装本地实现，支付域不读形态开关 —— `PayHasNoControllerTest` 守）；`shop.pay.order.enabled` 默认 true，关掉返回 null、调用方照旧。
- 接入：商城 `OrderServiceImpl#pay` 在 `initPayment` 前建单（门店 = 首个子单的门店）；餐饮 `IndustryCheckoutServiceImpl` 在 `open` 前建单（门店 = 账单门店）；`PaymentLedgerServiceImpl#open` 新行落库后 `attachAttempt`。
- 单号前缀 `BizKey.PAY_ORDER = PYO`（另预留 `PYR` 退款单、`LVC` 凭证）。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC2 | `CallerPolicyMatrixTest`（69 条：6 类 × 11 动作逐格，期望值在测试里另抄一份；`internalSystemCannotRefund`；`refundWithoutOperatorRejected`；`storeScope`） | ✅ | 给 INTERNAL 加退款 + 去掉操作员判断 → 3 条红 ✅ |
| AC3 | `PayOrderFlowTest`（3 条：`createIsIdempotent` · `sameKeyDifferentContentRejected` · `mallPaymentHasPayOrder` 真下单去支付：门店 id、应付、AWAITING_PAYMENT、尝试挂 `pay_order_id`）· `IndustryCheckoutServiceTest#firstPaymentCreatesFoodPayOrderBeforeOpeningTheAttempt`（FOOD_CHECK、门店 id 42，先建单后开尝试） | ✅ | ① 商城不建单 + 摘要不含金额 → 两条红 ② 尝试不挂支付单 → `mallPaymentHasPayOrder` 红 ③ 餐饮不建单 → 餐饮那条红 ✅ |
| AC1 | `MapperSmokeTest`（新 9 个 mapper 各查自己的表）· `SchemaDriftTest` · `PayMigrationTest` · 本机 MySQL 9.7 临时库执行 V7–V9（种子落库、CHECK 生效） | ✅ | — |

## §6 对账二 · 设计 → 实现

（实现后填）

### 偏差说明

0. **I2 收尾的启动校验在切片测试里装不起来**：`PayDataSourceConfig#channelProductCoverageCheck` 硬依赖渠道注册表，只装了部分支付配置的切片测试（`RefundBackingTest` 等 6 个类）上下文起不来 —— I2 收尾时只复跑了被点名的那 65 条，没再跑全量。S3 的受影响回归抓到，改为 `ObjectProvider`，没有注册表就跳过。
