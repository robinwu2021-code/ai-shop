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

### S4 · 结果唯一入口
- 两层分开：**通道尝试照记事实**（钱确实到了 → `stl_payment` SUCCESS，交易域据回执给用户看「支付异常、等待处理」，这一层沿用）；**支付单是严格的那一层**，只有结果入口能把它推进 SUCCEEDED。
- `acquiring.service.PaymentOutcomeService` / `impl.PaymentOutcomeServiceImpl#apply(ChannelOutcome)`：锁支付单 → CLOSED 记 `LATE_PAYMENT`（R18）· 已成功记 `DUPLICATE_PAYMENT` · 实收 ≠ 应收记 `AMOUNT_MISMATCH` · 否则 SUCCEEDED + `paid_minor` + `succeeded_at`。异常按 `uk (kind, stl_payment_id)` 只记一次。
- 接入点只有一个：`PaymentLedgerServiceImpl#settle`（回调、补查、掉单巡检、餐饮刷新、免单都经它）在尝试**第一次**转 SUCCESS 的同一事务里调；重推走「已 SUCCESS」幂等分支，不再进来 —— 「只入账一次」由此保证。
- R20：**应收以支付单 `pay_order.amount_minor` 为准**（建单后不改）；尝试行 `amount_minor` / `currency` 仍记渠道事实（实收），另在 `channel_amount_minor` / `channel_currency` 显式留一份。不改尝试行口径的理由见偏差 5。
- 状态机兜底：CLOSED → SUCCEEDED 不在迁移表里，即使绕过入口的判断也会抛 `PAY_ORDER_STATE`。

### S5 · 流水
- `ledger.LedgerAccount`（含平台外备查的 `MERCHANT_CHANNEL` / `MERCHANT_SALES`，见偏差 3）· `ledger.LedgerVoucher`（构造即校验：借贷平衡、金额为正、至少两行、平台外备查账不与 `PLATFORM_*` 同票 —— AC18）。
- `ledger.service.LedgerService` / `impl.LedgerServiceImpl`：`post`（`uk idem_key` 幂等，撞键回读）· `reverse`（追加反向凭证，`reversal_of_id` 指回原凭证）；只 insert。
- `funds.FundsPathStrategy` + `Direct…`（借 MERCHANT_CHANNEL / 贷 MERCHANT_SALES）· `Aggregated…`（借 PLATFORM_CHANNEL / 贷 MERCHANT_PAYABLE）；退款方向相反。`FundsPaths` 按支付单冻结的 `funds_path` 选；`BizDates` 按市场时区切业务日。
- 接入：`PaymentOutcomeServiceImpl` 在支付单进 SUCCEEDED 的同一事务里记 `PAY_SUCCEEDED`（`shop.pay.ledger.enabled` 默认 true）；退款成功凭证随 S6。
- 守卫 `LedgerAppendOnlyTest`：扫描 pay-domain，流水 mapper 上调 update / delete 即红（带判据自测）。

### S6 · 退款单与复核
- `spi.pay.PaymentPort#requestRefund(InternalRefundCmd)` → `RefundHandle`：**必须带商家操作员**，`PaymentPortImpl` 用 `InternalCallers.merchantOperator` 造 BIZ 身份（没有操作员在构造点就拒）。
- `acquiring.service.RefundService` / `impl.RefundServiceImpl`：`request`（权限 → 原支付单成功尝试 → 建退款单 → 交现有执行器）· `attachExecution`（现有售后路径按执行号进来时补建退款单）· `dispatchAllowed` / `dispatched` · `review`。建单共用 `open`：锁支付单 → `(pay_app_id, out_refund_no)` 幂等（金额必须一致）→ 可退余额 = 实收 − 已退 − 在途占用 → 占用 → 复核判定。
- `acquiring.policy.RefundReviewPolicy`（纯函数）：最具体的 ACTIVE 规则（门店级 > 市场级）；**无 ACTIVE 规则 → 复核（NO_ACTIVE_RULE）**；门槛为「或」：单笔 / 当日累计含本笔 / 比例 / 超 N 天 / 强制。`shop.pay.refund.review` 默认 false（种子只有 DRAFT 规则，开了即全部复核 —— 运营确认规则后再开）。
- `approval.service.ApprovalService` / impl：`open`（`open_key` 一对象一张进行中）· `decide`（PENDING_FIRST → PENDING_SECOND → APPROVED；任一步驳回 REJECTED；申请人 / 第一人 / 第二人互不相同，服务层与表 CHECK 两处）。
- `acquiring.service.RefundOutcomeService` / impl：`succeeded`（退款单 SUCCEEDED、支付单已退 + / 占用 −、PARTIALLY_REFUNDED / REFUNDED、记 `REFUND_SUCCEEDED` 凭证）· `failed`（退款单 FAILED、释放占用）。只对挂着、未终态的退款单生效。
- 接入现有执行器（不改它的「先落库 → 抢发送权 → 不明只查不重发」）：`RefundPaymentStore#prepare` 落行后 `attachExecution`（超额整笔回滚）；`RefundExecutionServiceImpl#request` 在抢发送权前问 `dispatchAllowed`（待复核返回 UNKNOWN `REFUND_REVIEW_PENDING`、可重试）、抢到后 `dispatched`；`confirmCurrent` / `completeZero` / `result` 在行第一次进终态时调结果入口。复核驳回经 `RefundPaymentStore#reject` 把执行行判失败（售后侧据此知道没退成）。
- R21：删掉 `SettlePort` / `SettleService` 的三参 `refund`（合成 `AS-{子单号}`）—— 生产无调用方，测试改用真实售后单号；编译器保证不会再有人用回去。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC2 | `CallerPolicyMatrixTest`（69 条：6 类 × 11 动作逐格，期望值在测试里另抄一份；`internalSystemCannotRefund`；`refundWithoutOperatorRejected`；`storeScope`） | ✅ | 给 INTERNAL 加退款 + 去掉操作员判断 → 3 条红 ✅ |
| AC3 | `PayOrderFlowTest`（3 条：`createIsIdempotent` · `sameKeyDifferentContentRejected` · `mallPaymentHasPayOrder` 真下单去支付：门店 id、应付、AWAITING_PAYMENT、尝试挂 `pay_order_id`）· `IndustryCheckoutServiceTest#firstPaymentCreatesFoodPayOrderBeforeOpeningTheAttempt`（FOOD_CHECK、门店 id 42，先建单后开尝试） | ✅ | ① 商城不建单 + 摘要不含金额 → 两条红 ② 尝试不挂支付单 → `mallPaymentHasPayOrder` 红 ③ 餐饮不建单 → 餐饮那条红 ✅ |
| AC4 | `PaymentOutcomeFlowTest`（3 条：`notifyTwiceBooksOnce` · `latePaymentCreatesException` · `amountMismatchKeepsRequestedAmountOnPayOrder`）· `OrderPaymentLifecycleWiringFlowTest` 8 条 · `OrderPaymentRecoveryWiringFlowTest` 6 条 · `PointsPaymentSnapshotTest` 3 条原样全绿 | ✅ | ① 关单后照常成功 → `latePayment…` 红（被状态机兜成 `PAY_ORDER_STATE`）② 不比金额 → `amountMismatch…` 红 ✅ |
| AC5 | `LedgerVoucherTest`（4 条：不平、混账、非正金额 / 单行、平衡）· `LedgerFlowTest`（4 条：AGGREGATED 回执两次一张凭证且分录正确、业务日按上海；DIRECT 只记备查账；Q7 = 0；同一幂等键记两次一张 + 冲正追加反向凭证）· `LedgerAppendOnlyTest`（2 条） | ✅ | ① 成功不记账 → 两条红 ② 去掉幂等 + DIRECT 记进平台账户 → 幂等那条撞唯一键、DIRECT 那条被凭证自身的混账校验拦下 ✅ |
| AC6 | `RefundOrderFlowTest`（4 条：`refundReservesThenBooks` 占用 → 确认 → 已退 / 占用归零 / 部分退款 / 反向凭证；`inFlightReservationCountsAgainstRefundable`；`refundWithoutOperatorRejected`；`afterSaleRefundAlsoHasRefundOrderAndFailureReleases` 现有售后路径同样有退款单）· `RefundReachesChannelTest#secondAfterSaleOnSameSubOrderRefundsAgain`（R21：先用三参签名跑出红 —— 第二笔被当成重复；删签名后全部改真实售后单号，5 条绿） | ✅ | ① 不查可退余额 → `inFlight…` 红 ② 成功不结退款单 → `refundReservesThenBooks` 红 ✅ |
| AC7 | `RefundReviewPolicyTest`（4 条：无 ACTIVE 规则复核、门槛为或、门店级优先 + 强制、无关规则不算）· `RefundReviewFlowTest`（2 条，`shop.pay.refund.review=true`：待复核不发渠道 → 申请人自审被拒 → A 通过 → A 再审被拒 → B 通过 → 再调即执行；驳回 → 退款单 REJECTED、执行行 FAILED、占用释放） | ✅ | ③ 不拦待复核 → 「复核期间退款不发渠道」红 ④ 去掉同一人判定 → 被表的 CHECK 拦下（第二道） ✅ |
| AC1 | `MapperSmokeTest`（新 9 个 mapper 各查自己的表）· `SchemaDriftTest` · `PayMigrationTest` · 本机 MySQL 9.7 临时库执行 V7–V9（种子落库、CHECK 生效） | ✅ | — |

## §6 对账二 · 设计 → 实现

（实现后填）

### 偏差说明

1. （撤回）S4 起初让尝试行 `amount_minor` 保持应收、实收只进 `channel_*`，并改了两条现有断言。受影响回归随即抓到真回归：`PointsPaymentSnapshotPortImpl` 按 `amount_minor` 核实收，金额不符被放过（`PointsPaymentSnapshotTest#mismatched_verified_cash…` 红）—— 全仓读「成功收款」的地方都按实收口径读这一列。已撤回，见偏差 5。
2. **币种不进支付单的成功判据**：测试 / stub 网关查单恒报 CNY（同 I2 偏差 9），比了会把 KWD 等单全部误判；渠道币种照记 `channel_currency`，判据随 I8 各渠道查单带真实币种后补上。
3. **流水补一个账户 `MERCHANT_SALES`**：DIRECT 收款只记平台外备查账，而设计的账户表里没有与 `MERCHANT_CHANNEL` 配对的对方科目；拿任何 `PLATFORM_*` 配都违反 AC18。补「商户销售（平台外备查）」作对方科目，表结构说明的账户值域随之补一项。
4. **资金路径在建单时冻结**：设计是「选定方式时冻结路由快照」，而选方式（`PaymentService#start` + 收款路由）随 I4 才有；I3 由调用方按主体 `mch_entity.funds_mode` 带进来冻结进 `pay_order.funds_path`，记流水按它分录。未冻结的存量单不记（不猜），交给回填 B7。
5. **R20 由支付单解决，尝试行口径不变**：R20 的害处是「应收被实收覆盖后再也看不出来」。支付单 `amount_minor` 建单后不改，应收从此有处可查；尝试行 `amount_minor` 继续表示实收（结算、对账、积分核验、退款上限都按这个口径读），渠道事实另写 `channel_*`。真把尝试行改成应收要逐个改这些读方，留到 I6 对账结算一并做。
6. **现有售后路径的退款单没有操作员**：普通售后经 `RefundRequest(执行号…)` 进支付，这条记录里没有商家操作员；退款单 `requested_by` 记 `AFTER_SALE:{售后单号}` 以便追溯。「必须有操作员」只在新入口 `PaymentPort#requestRefund` 上强制；售后改走新入口、把审批人一路带进来随 I5 商户端退款一起做。积分售后（`SettleServiceImpl#prepareRefund / dispatchRefund`）不经这个执行器，I3 不挂退款单。
7. **复核开关默认关**：种子只有一条 DRAFT 规则，开关一开即「全部复核」—— 上线即卡住所有售后退款。运营按两人规则把 CN 规则生效后再开（`shop.pay.refund.review=true`）。
8. **受影响回归抓到的三处（S6 一并改）**：① 免单成功也去记凭证，零金额分录被凭证自身拒绝 → 只在金额 > 0 时记（S5 提交时只跑了新测试，没跑受影响回归）；② 零元退款去建退款单，被 `amount_minor > 0` 拒 → 零元退款不建；③ 可选依赖用 `@Lazy` 注入时，切片测试里拿到的是代理、一调就抛 → 收款账本、退款执行器、退款存储的挂钩一律改 `ObjectProvider`，调用时取不到就跳过。
0. **I2 收尾的启动校验在切片测试里装不起来**：`PayDataSourceConfig#channelProductCoverageCheck` 硬依赖渠道注册表，只装了部分支付配置的切片测试（`RefundBackingTest` 等 6 个类）上下文起不来 —— I2 收尾时只复跑了被点名的那 65 条，没再跑全量。S3 的受影响回归抓到，改为 `ObjectProvider`，没有注册表就跳过。
