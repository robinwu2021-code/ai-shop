# TDD-餐饮柜台后付结账

状态：已实现（第一段，2026-11-13；本机 8091 与 POS 真机联调通过；未合并、未推送，迁移待 root 执行，上线前按「上线清单」核对目标环境）
档位：2（跨核心订单、餐饮、收款记账三个域；改核心订单状态；重开被延期的资金专项）
关联决策：[ADR-059 餐饮后付单人工实收与资金专项第一段](./ADR/ADR-059-餐饮后付单人工实收与资金专项第一段.md)
关联需求：`docs/requirements/餐饮系统-完整需求文档.md` §5.4、§十；`docs/requirements/餐饮系统-需求矩阵.md` FNB-F01、FNB-F02、FNB-F03（整桌一次结账，原列 P3，本期提前）、FNB-C08（清台）
关联设计：`TDD-餐饮订单结账-完整开发计划.md`（下称 CKO）§2.3 把「支付及资金专项」摘除延期（`:527`、`:578`），本 TDD 只恢复其中最小一段，其余仍按 CKO 延期；`TDD-业务单收款汇总.md`（D5：收款带结算单号 `bizNo`）；ADR-050（美业人工实收先例）
创建：2026-11-12

> **L1 定位**：让一张员工后付的堂食账单能在柜台收银、收齐后关单、释放桌台 —— 这是今天整条链路上唯一断掉的一环。
> 第一段只做：整桌一张结算单 → 现金或外部刷卡机收款（POS 用已有的外部记账接口记账）→ 收齐确认 → 核心后付单推到已付（人工实收） → 关单 → 释放桌台。
> 扫码、储值、让价、服务费、现金交班、并台、待清台、离线回放去重放后面几段（§2「分期」）。

## 产品决定（2026-11-12 已拍板）

| # | 问题 | 决定 |
|---|---|---|
| D1 | 资金专项第一段做到哪 | 只做最小闭环（本 TDD） |
| D2 | 收齐后核心后付单怎么处理 | 推到 PAID，来源标人工实收（实现：来源记在实收表 `ord_postpay_receipt.provenance='POSTPAY_RECEIPT'`，订单行沿用线下确认的 `offline_confirmed_by/at`，见 §6 偏差 1） |
| D3 | 收款粒度 | 整桌一次收：一张结算单覆盖这张台账所有未付的后付批次 |
| D4 | 刷卡 | 外部刷卡机 + 外部记账（`EXTERNAL_POS`），与现金同一条路 |

## 本 TDD 采用的默认规则（未单独拍板，确认 TDD 时一并确认）

| # | 规则 | 理由 |
|---|---|---|
| R1 | 收款记账的 `bizNo` = 结算单号 | 沿 D5 原文；一桌多次结算（取消后重建）各自独立，不会混在一起 |
| R2 | 少收不能确认，明确告诉差多少 | 需求「只有原权威已收清且无未知份额才 SETTLED」 |
| R3 | 多收只允许来自现金，多出的部分算找零；刷卡多收拒绝 | 刷卡金额应与应收一致；现金找零是柜台常态 |
| R4 | 菜已全部上齐（或作废）时确认即释放桌台；没上齐只关单，桌台保持使用中，上齐后服务员手动释放 | 不让「先买单后上菜」的门店卡住，也不在上菜前把桌台放出去 |
| R5 | 结算单不设自动过期；冻结期间账单不能加菜、退菜，需先取消结算单 | 柜台结账可能拖很久，自动过期会让收银员确认时撞失败 |
| R6 | 写操作权限用已有的 `pay:cash:receive`（收现金），读用已有的 `biz:order:view` | 不新增权限码；谁能在柜台收钱就谁能结账 |

## §0 对账一 · 需求 → 设计

| AC | Given / When / Then | 落点 |
|---|---|---|
| AC1 | Given 台账 OPEN、至少一个已受理的后付批次、没有在途批次与未决退餐；When 收银员创建结算单；Then 冻结账单，结算单 FROZEN，每张未付核心单一行，应收 = 各单当前有效应收之和；同一台账同时只能有一张未结束的结算单（同一请求重放返回同一张） | `POST /biz/x/food/checks/{checkNo}/counter-settlements` → `CounterSettlementService#create` |
| AC2 | Given 已用外部记账（CASH / EXTERNAL_POS，`bizNo` = 结算单号）记了款；When 确认收齐（带记账号）；Then 核心在**一个事务**里把这桌所有后付单推到 PAID 并写实收记录；结算单 SETTLED、台账 CLOSED、解冻；菜已上齐则释放桌台为空闲 | `POST /biz/x/food/counter-settlements/{settlementNo}:finalize` → 核心 `PostpayReceiptService#confirm` |
| AC3 | 记账号不存在、属于别家门店、`bizNo` 不符、已作废、币种不符 → 拒绝；合计少于应收 → 拒绝并给出差额；多收只能来自现金（找零），否则拒绝；同一笔记账不能用于两张结算单 | 核心 `PostpayReceiptService#confirm` 的核验 |
| AC4 | 同一请求重放返回同一结果；核心已成功、餐饮收尾失败时，重试确认会补完收尾，不重复推 PAID | 核心按 `(门店, 结算单号)` 唯一；餐饮按结算单状态续做 |
| AC5 | 确认前可取消：解冻、结算单 CANCELLED；已确认的不能取消 | `POST /biz/x/food/counter-settlements/{settlementNo}:cancel` |
| AC6 | 冻结后核心单的应收被改（例如退菜）→ 版本对不上 → 确认被拒，需取消重建 | 结算行 `core_version` 与核心当前版本比对 |
| AC7 | 已结清的台账在日结里不再记应收异常（AR）与支付未知（PAY_UNKNOWN） | `BusinessCloseServiceImpl`：柜台结算单 SETTLED 的批次不记 AR、该结算单不记 PAY_UNKNOWN（核心 PAID 后应收并不归零，见 §6 偏差 5） |
| AC8 | 释放服务后桌台回到空闲（修复：目前只改台账、桌台仍显示使用中） | `CheckCollaborationServiceImpl#releaseService` 同步桌台 |
| AC9 | 已关单但菜没上齐的台账，上齐后可以释放服务；关单本身不受「菜没上齐」限制 | `releaseService` 允许 CLOSED 台账（解冻后） |

## §1 现状与影响面

依据 2026-11-12 的差距分析（基线 `codex/food-next01@e3c320087`），关键事实：

- **核心后付单无法变已付**：员工每轮下单建一张 `POSTPAY / WAIT_PAY` 核心单（`DiningOrderServiceImpl.java:481-512`）；行业收款只接先付（`IndustryCheckoutServiceImpl.java:259-263`），商家线下确认只接 `WAIT_OFFLINE_PAY`（`MerchantOrderServiceImpl.java:397`），美业人工实收只认 `MANUAL_OFFLINE` 柜台单（`ManualServiceAdmissionServiceImpl.java:729`）。
- **外部记账与账单脱钩**：`POST /biz/payment/offline-records` 按 `bizNo` 记款，不改任何订单（`OfflineRecordServiceImpl.java:359`）。
- **Biz 侧没有结账写入口**：只有 `GET /biz/x/food/settlements/{no}`；已有的结算写路径只服务 H5 自提先付单批次（`DiningCheckoutServiceImpl.java:584-589`）。
- **没有关单**：`fnb_check.status` 只写过 OPEN、VOIDED。
- **释放服务不同步桌台**：`releaseService` 不改 `fnb_table.turnover_state`（`FoodBusinessOperationsMapper.java:309-317`）。
- **日结会被后付卡住**：后付批次只要核心应收 > 0 就记 AR；非 CANCELLED 的结算单都记 PAY_UNKNOWN（`BusinessCloseServiceImpl.java:982-1006`）。

**可复用**：冻结与解冻（`CheckCollaborationServiceImpl#freeze/release`）、应收读取（`#previewReceivable`）、释放服务（`#releaseService`）、结算单与结算行两张表、外部记账（核心）、美业人工实收的 PAID 守卫写法（`ManualAdmissionMapper#confirmParent`）。

**会改到的**：核心 trade（新服务、新表、新内部接口、行业订单 SPI 新方法）；餐饮 food-core（新服务、`releaseService`、日结口径、`fnb_settlement` 加一列）；food-web（新控制器）；food-channel（远程调用核心）；宿主（内部端点登记、权限守卫测试）。

**明确不受影响**：顾客 H5 先付结算路径（新增的结算来源列把两者分开，H5 的恢复任务不会扫到柜台结算单）；平台外部记账接口本身；支付域。

## §2 方案

### 核心合同：后付人工实收确认（ADR-059）

```java
// shop-base spi：餐饮经它调核心（嵌入部署本地调用；独立部署走内部 HTTP）
interface PostpayReceiptPort {
    /** 一次确认整桌：所有根在核心一个事务里推到 PAID；按 (storeNo, bizNo) 幂等。 */
    PostpayReceipt confirm(ConfirmPostpayReceipt command);
}
record ConfirmPostpayReceipt(String entityNo, String storeNo, String bizNo, String currency,
        List<Root> roots,                 // 每张后付核心单：orderNo、应收、确认时的核心版本
        List<String> offlineRecordNos,    // 外部记账号（CASH / EXTERNAL_POS）
        String actorNo, String requestKey) {}
record PostpayReceipt(String receiptNo, long dueMinor, long receivedMinor, long changeMinor,
        List<String> paidOrderNos, boolean replayed) {}
```

核心在**一个事务**里：

1. 按 `(storeNo, bizNo)` 查已有实收：有就原样返回（`replayed=true`），请求内容不同则拒绝。
2. 锁住各根核心单，逐张核对：属于这个门店、`orderMode=POSTPAY`、状态 `WAIT_PAY`、币种一致、应收与版本与命令一致（AC6）、没有线上支付请求与支付决议。
3. 读外部记账：存在、同门店、`bizNo` 一致、未作废、币种一致、没有被别的实收用过（AC3）；合计 ≥ 应收；多出部分不超过现金记账合计（找零，R3）。
4. 写实收记录 `ord_postpay_receipt`（一张结算一行）与根明细 `ord_postpay_receipt_root`；每根推到 PAID（条件更新：`status='WAIT_PAY' AND version=…`，任一根没更新到就整笔回滚）；写状态日志；发事件 `POSTPAY_RECEIPT_RECORDED`（只带实收号、结算号、门店）。
5. **不发**通用的「订单已支付」事件：钱没有经过平台，不进平台结算与分账（同 ADR-050）。

### 餐饮编排

| 步骤 | 做什么 | 失败时 |
|---|---|---|
| 创建 | 校验台账 OPEN、无在途批次与未决退餐 → 冻结（复用 `freeze`）→ 读应收（复用 `previewReceivable`）→ 写结算单（来源 COUNTER、FROZEN、`due_minor` = 合计）与每根一行（`core_version` = 读到的核心版本）→ 返回结算单号、应收、币种、明细 | 任一步失败整笔回滚，不留冻结 |
| 确认 | 结算单 FROZEN 或 COLLECTING → 置 COLLECTING → 调核心确认 → 成功后：结算单 SETTLED、`core_cashier_ref` = 实收号；台账 CLOSED、`closed_at`；解冻；满足条件则 `releaseService` | 核心拒绝：结算单保持 COLLECTING，返回原因，可再次确认或取消；核心成功、餐饮收尾失败：再次确认时核心返回 `replayed`，餐饮补完收尾 |
| 取消 | 只允许 FROZEN 或 COLLECTING 且核心未确认 → CANCELLED、解冻 | 核心已确认（查实收号存在）→ 拒绝 |

### 契约变更

- **端点**（餐饮 Biz，门店取当前登录门店；实现与设计稿的路径差异见 §6 偏差 2）：

| 端点 | 权限 | 返回 |
|---|---|---|
| `POST /biz/x/food/checks/{checkNo}/counter-settlements`，头 `Idempotency-Key` | `pay:cash:receive` | 结算单（号、应收、币种、明细、状态） |
| `POST /biz/x/food/counter-settlements/{settlementNo}:finalize`，请求体 `{offlineRecordNos:[…]}`；按结算单号幂等 | `pay:cash:receive` | 结算单（含实收号、实收、找零）、台账状态、桌台是否已释放 |
| `POST /biz/x/food/counter-settlements/{settlementNo}:cancel` | `pay:cash:receive` | 结算单 |
| `GET /biz/x/food/counter-settlements/{settlementNo}` | `biz:order:view` | 结算单 |

- **内部端点**（均验内部令牌；业务错误码随请求摘要放在响应头 `X-Internal-Error-Code`，调用方核对摘要后才采信，收银台因此分得清「还差钱」「账单变了」）：
  - 核心：`POST /internal/trade/postpay-receipts/confirm`（food-server → 宿主）；
  - 餐饮：`POST /internal/food/counter-settlements/{create|finalize|cancel|get}`（宿主里的 food-web → food-server，拆分部署时）。
- **库表**：
  - 平台迁移 `V674__postpay_receipt.sql`（统一开发分支合并时由 V665 顺延为 V674，见下）：`ord_postpay_receipt`（实收号、门店、结算号、币种、应收、实收、找零、外部记账号集合与哈希、操作人、请求键、来源 `provenance='POSTPAY_RECEIPT'`（CHECK 约束）；唯一键 `(tenant, entity, store, biz_no)`、`(tenant, entity, store, request_key_hash)`）；`ord_order` 不改表结构（§6 偏差 1）；`ord_postpay_receipt_root`（实收号、核心单号、应收、核心版本；唯一键 `order_no`）；`ord_postpay_receipt_record`（实收号、外部记账号；唯一键 `record_no`，保证一笔记账只用一次）。
  - 餐饮迁移 `V40__counter_settlement.sql`：`fnb_settlement.kind`（为空 = 既有 H5 结算单，`COUNTER` = 柜台）；H5 的恢复任务（`DiningTransactionRecoveryServiceImpl` 两处 CHECKOUT_PENDING 查询）排除 `COUNTER`。
- **错误码与文案**（三语）：20024 少收（带差额）、20025 多收不是现金、20026 记账号无效（不存在 / 别家 / 作废 / 币种 / 结算号不符 / 已被使用）、20027 核心单或账单已变更需重建、20028 台账有在途批次或未决退餐、20029 核心单有菜在别的台账上、20030 已确认不可取消；「结算单已存在未结束」沿用已有 `CONFLICT`。内部响应把这 7 个码映射为 409（`InternalServiceResponses`）。
- **权限码**：无新增（R6）。
- **配置**：无。
- **事件**：核心 `POSTPAY_RECEIPT_RECORDED`（只归档：`application.yml` 的 archive-only 名单）。餐饮关单**本期不发** `FOOD_CHECK_CHANGED`（§6 偏差 4）。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-base/.../spi/trade/PostpayReceiptPort.java` | 合同与记录 |
| 新增 | `backend/shop-core/.../trade/service/PostpayReceiptService.java`、`impl/PostpayReceiptServiceImpl.java`、`mapper/PostpayReceiptMapper.java`、实体 | 核心确认 |
| 新增 | `backend/shop-app/.../db/migration/V674__postpay_receipt.sql` | 三张表 |
| 新增 | `backend/shop-app/.../portal/internal/InternalPostpayReceiptController.java`、本地端口实现 | 嵌入与独立两种部署 |
| 新增 | `F/food-core/.../service/CounterSettlementService.java`、`impl/CounterSettlementServiceImpl.java` | 餐饮编排 |
| 新增 | `F/food-web/.../api/biz/BizCounterSettlementController.java` | 三个端点 |
| 新增 | `F/food-channel/.../RemotePostpayReceiptPort.java` | 独立部署时调核心内部端点 |
| 新增 | `F/food-core/.../db/industry/food/V40__counter_settlement.sql` | 结算来源列 |
| 修改 | `CheckCollaborationServiceImpl#releaseService`、`FoodBusinessOperationsMapper` | 同步桌台空闲；允许 CLOSED 台账 |
| 修改 | `DiningCheckoutServiceImpl` 恢复任务 | 只扫 H5 结算单 |
| 修改 | `BusinessCloseServiceImpl` | PAY_UNKNOWN 不计已结清 |
| 修改 | `ErrorCode` 与三语文案、权限守卫测试、内部端点登记、`docs/api` 契约 | — |

### 分期（本 TDD 只实施第一段）

| 段 | 内容 |
|---|---|
| **一（本期）** | 上述最小闭环 |
| 二 | 扫码付款（付款链接带结算单号）、储值、人工让价与服务费进账单、员工先付下单 |
| 三 | 现金交班：开班、非销售现金、盘点、接班；现金实收挂班次 |
| 四 | 并台、待清台与清台完成、离线回放去重 `clientRef`、操作员头 |

### 实施计划

| 步 | 内容 | 完成标准 |
|---|---|---|
| 1 | 核心：表、SPI、确认服务、内部端点 | AC2–AC4、AC6 的核心侧测试；多根一个事务；回滚测试 |
| 2 | 餐饮：迁移、编排、控制器、远程端口 | AC1、AC2、AC5 端到端（嵌入部署） |
| 3 | 释放桌台与日结口径 | AC7–AC9 |
| 4 | 守卫、契约文档、全量回归、消融 | 闸门与既有失败一致 |

## §3 选型

| 问题 | 选择 | 不选的方案与原因 |
|---|---|---|
| 谁核验外部记账 | 核心 | 记账本来归核心；放在餐饮要新增「读记账」端口，独立部署还要多一次跨进程调用 |
| 多根怎么推已付 | 核心一个事务推完 | 逐根调用会出现「一半已付」，恢复复杂 |
| 复用美业人工实收表 | 不复用，新建 | 美业那张表绑着库存处置阶段与 `MANUAL_OFFLINE` 来源，语义不同 |
| 收齐判定 | 核心按记账号逐条核对 | 平台汇总读口需要调用方传应收、且在宿主，餐饮可独立部署 |

## §4 风险

| 风险 | 缓解 |
|---|---|
| 核心单推 PAID 影响下游（结算、报表、积分） | 不发通用「已支付」事件；已逐个核对 PAID 的读者（§6 偏差 6）：资金巡检与出账会把它当线上收款补结算单 —— **已修**；报表日结不重算、后付单无法走售后退款 —— 记为后续 |
| 结算期间有人退菜 | 冻结期间账单不可变；核心版本比对兜底（AC6） |
| 迁移编号与推送分支冲突 | 原 V665 与主线 V665 冲突；2026-11-14 统一开发分支把餐饮线平台迁移整体顺延到 V668–V674，本表为 V674 |
| 独立部署下核心不可达 | 结算单停在 COLLECTING，重试确认；核心幂等 |

## §5 对账三 · 实现 → 需求（测试）

核心测试 `PostpayReceiptServiceTest`（SpringBootTest + H2，真实映射器与守卫 SQL）；餐饮端到端 `CounterSettlementPrimaryDatabaseTest`（本机 MySQL 一次性库，跑全部餐饮迁移含 V40，`FOOD_TEST_DISPOSABLE=true`；核心两个端口用内存替身）。

| AC | 测试 | 结果 |
|---|---|---|
| AC1 | `CounterSettlementPrimaryDatabaseTest#wholeTableFreezesConfirmsClosesAndReleasesTable`（冻结、重放同一张、冻结后不可改、同台账第二张被拒）；`#refusesInFlightBatchSplitLinesAndCancelAfterCorePaid`（在途批次、拆到别的台账）；`#refusesWhenBillChangesWhileReadingCore` | 通过 |
| AC2 | `PostpayReceiptServiceTest#whole_table_is_paid_in_one_transaction_with_cash_and_external_card`、`#if_any_order_cannot_be_paid_the_whole_table_is_rolled_back`；餐饮 `#wholeTableFreezesConfirmsClosesAndReleasesTable` | 通过 |
| AC3 | `#underpaid_is_rejected_with_the_difference_and_changes_nothing`、`#cash_may_exceed_as_change_but_card_may_not`、`#invalid_records_are_rejected`（5 种）、`#a_record_already_used_by_another_receipt_cannot_be_reused`、`#another_store_or_a_prepaid_order_is_forbidden` | 通过 |
| AC4 | `#replay_returns_the_same_receipt_and_different_content_conflicts`；餐饮重放确认不重复推核心 | 通过 |
| AC5 | `#underpaidKeepsBillFrozenAndCancelUnfreezes`；已确认 / 核心已 PAID 不可取消 | 通过 |
| AC6 | `#changed_bill_is_rejected` | 通过 |
| AC7 | `BusinessCloseEvidenceTest#counterSettledPostpayIsNeitherReceivableNorUnknownPayment`（对照：COLLECTING 仍记，非 COUNTER 的 SETTLED 仍记） | 通过 |
| AC8 | `#wholeTableFreezesConfirmsClosesAndReleasesTable`（桌台 IDLE、占用结束） | 通过 |
| AC9 | `#closesButKeepsTableWhenServiceCannotBeReleasedThenReleasesLater` | 通过 |
| R6 | `BizCounterSettlementPermTest` | 通过 |
| 资金隔离 | `PostpayReceiptServiceTest#receipted_orders_never_become_platform_settlement_sources`（对照：同形状普通已付单仍出账） | 通过 |
| 拆分部署 | `RemoteCounterSettlementServiceTest`（业务码只在摘要对上时采信） | 通过 |
| 控制器装配 | `FoodControllerRegistrationTest`（food-web 每个控制器都在 config 里登记；§6 偏差 9） | 通过 |
| 核心读已结清后付单 | `AcceptedPostpayPaymentStateTest`（未付 / 实收覆盖的 PAID 合法，其余冲突；§6 偏差 11） | 通过 |
| 读结算单带实收与找零 | `#wholeTableFreezesConfirmsClosesAndReleasesTable` 结账后 `get` 返回 receivedMinor / changeMinor；`#underpaidKeepsBillFrozenAndCancelUnfreezes` 确认前为空（消融：不落库 → 红） | 通过 |
| 真实环境 | 本机 8091：POS 真机开台 → 下单 → 柜台结账（现金、外部刷卡）→ 推进厨房任务后放台 → 日结预览 issues 为空 | 通过（签核待营业日结束后用第二账号验证） |

真实输出（2026-11-12）：

```
PostpayReceiptServiceTest           Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
CounterSettlementPrimaryDatabaseTest Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BusinessCloseEvidenceTest           Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
DiningCheckoutServiceTest           Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
DiningTransactionRecoveryServiceTest Tests run: 7, Failures: 0, Errors: 0, Skipped: 1
FundInvariantFlowTest               Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
BizCounterSettlementPermTest / RemoteCounterSettlementServiceTest  各 1，全过
```

**消融**（改回实现 → 对应测试变红 → 还原后转绿；还原后 `touch` 源文件，避免增量编译沿用改坏的类）：

| # | 改回 | 变红的测试 |
|---|---|---|
| A1 | 确认后不关单 | `wholeTable…`、`closesButKeepsTable…` |
| A2 | 释放服务时桌台不回空闲 | `wholeTable…`、`closesButKeepsTable…` |
| A3 | 日结仍记柜台结清的 PAY_UNKNOWN | `counterSettledPostpay…` |
| A4 | 释放服务只认 OPEN 台账 | `wholeTable…`、`closesButKeepsTable…` |
| A5 | 冻结前不做本地复核 | `refusesWhenBillChangesWhileReadingCore` |
| A6 | 取消不查核心状态 | `refusesInFlightBatch…AndCancelAfterCorePaid` |
| C1 | 刷卡多收不拒 | `cash_may_exceed_as_change_but_card_may_not` |
| C2 | 某张单推不动也不回滚 | `if_any_order_cannot_be_paid_the_whole_table_is_rolled_back` |
| C3 | 出账不排除后付实收 | `receipted_orders_never_become_platform_settlement_sources` |

**回归对照**（基线 `e3c320087` 干净 worktree，同一命令）：

- food-core + food-web 全量：基线 749 跑 / 5 失败；本分支 755 跑 / 同样 5 个失败（`FoodConditionalWiringTest` ×4、`FoodPersistenceWiringTest` ×1），无新增。中途发现 `DiningCheckoutServiceTest` 自建的 `fnb_settlement` 缺 `kind` 列（11 个用例红），已补夹具。
- `ArchitectureTest`（5 失败）、`BizEndpointPermTest`（1 失败）、`BackendI18nParityTest`（全过）：报告与基线逐行相同，失败项都不涉及本次文件。`ManualServiceFinancialIsolationIT` 基线与本分支同为 9 个失败。
- `FoodPrimaryDatabaseTest`（一次性 MySQL）：基线 14 失败（校验器写死版本 37、库里已到 V39）；本分支把版本断言改为 40 后剩 1 个 `explicitPublishMigratesOnlyFood`（表数写死 126、实际 143，V40 不建表，与本次无关，基线同样失败）。
- 文件闸门：`check-app-backend-orphan` / `check-controller-cohesion` / `check-sql-portability` 与基线输出完全相同（均为既有红）；`check-generated-docs` 在基线同样卡在 b-app `gen-openapi`（补货端点命名）跑不下去。受本次影响的两份生成物（`数据库表清单.md`、`后端分层清单.md`）已在本 worktree 单独重生成，其中含既有漂移。

## §6 对账二 · 设计 → 实现

**改动清单**（49 个文件）与 §2 模块设计的差异：

| 设计 | 实现 |
|---|---|
| `InternalPostpayReceiptController` | `InternalPostpayReceiptEndpoint`（宿主惯例命名） |
| `RemotePostpayReceiptPort` | `food-channel/.../CorePostpayReceiptPortImpl`（food-channel 惯例），在 `FoodPricingClientConfiguration` 装配 |
| 修改 `DiningCheckoutServiceImpl` 恢复任务 | 恢复查询实际在 `DiningTransactionRecoveryServiceImpl`，改在那里 |
| — | 新增：`CounterSettlementInternalApi`、`InternalCounterSettlementController`（food-server）、`RemoteCounterSettlementService`（food-web 拆分部署）、`CheckBusinessGuardService#assertReleasable` |
| — | 修改：`InternalServiceResponses`（7 个新码 → 409）、`SettleSourcePortImpl`（偏差 6）、`FoodSchemaValidator` 版本 40、生成物两份 |

**偏差说明**

1. **来源不写在 `ord_order` 上**。原设计给订单加不可变来源 `MANUAL_DECLARATION`；但 `ord_order.receipt_provenance` 有 CHECK 约束，改约束需要 `DROP CONSTRAINT`，测试库生成器处理不了，且动核心大表风险高。改为：来源只记在新表 `ord_postpay_receipt.provenance='POSTPAY_RECEIPT'`，订单行沿用线下确认先例 `offline_confirmed_by/at`；推 PAID 的守卫要求 `receipt_provenance IS NULL`、无支付通道、无线上支付请求与决议。ADR-059 已同步改写。
2. **端点路径**。设计稿的 `GET /biz/x/food/settlements/{no}` 已被 H5 结算单占用，柜台结算单改用独立资源 `counter-settlements`；门店取登录态当前门店（与同目录其他控制器一致），不在路径里带 `storeNo`。读接口是新的，不扩展旧接口。
3. **冻结方式**。不复用 `freeze`（`fnb_check_freeze`）：结算行本身就是冻结 —— `assertMutable` 已经把有 FROZEN/COLLECTING 结算行的台账视为不可改。读应收要调核心，不能在持有餐饮锁时调用，所以冻结前用本地指纹（台账版本 + 当前核心绑定 + 菜行）复核「读应收前后账单没变」，变了返回 20027。
4. **关单不发 `FOOD_CHECK_CHANGED`**。现有事件没有 CLOSE 动作的消费者，本期只改状态；KDS / 推送需要时再加。
5. **AC7 落点**。设计以为核心单 PAID 后 AR 会自然消失；实际日结的 AR 只看核心有效应收是否 > 0，与支付状态无关。改为按「COUNTER 结算单 SETTLED」排除 AR 与 PAY_UNKNOWN；其他结算单仍记（资金完整性仍为未知）。
6. **资金隔离（实现中发现）**。核对 PAID 读者时发现：资金巡检 `FundInvariantReconciler`（每小时）按 `LEGACY_FUNDED + paid_at` 找「已付却没结算单」的子单并补出账，出账 `settleSourcesOf` 只排除美业 `MANUAL_OFFLINE`，且把无支付通道视为线上 —— 会给每张后付实收单补一张带佣金、待分账的结算单。已在两处按 `ord_postpay_receipt_root` 排除，并加对照测试与消融。仍未处理（后续）：报表纠偏只认 `ORDER_PAID`，已滚完的日报不会因实收重算；后付单没有在线支付号，售后退款会被 `RefundFinancialOwnerServiceImpl` 拒绝（安全但不可用）。
7. **拆分部署的错误码**。`InternalServiceResponses.invoke` 不回业务码，少收 / 账单变了在独立部署下会退化成 500。两跳都改用 `invokeBound`（业务码 + 请求摘要），调用方核对摘要后才采信；少收的差额参数不跨服务，独立部署显示「—」。
9. **控制器漏登记（本机起服务时发现）**。餐饮 Web 控制器不走包扫描，靠 `FoodWebAutoConfiguration` 逐个 `@Bean` 登记；`BizCounterSettlementController` 漏了，单元测试全绿但运行时四个端点 404。已补登记，并加守卫 `FoodControllerRegistrationTest`（food-web 下每个 `@RestController` 必须在 config 里 `@Bean`/`@Import`，条件注解里的 `.class` 不算；消融：去掉登记 → 红）。本机 8091 冒烟：四个端点返回业务码 10404，外部记账带 `bizNo` 记账与按 `bizNo` 查询正常。
10. **核心确认在事务里读核心应收（本机端到端时发现）**。`IndustryOrderService#getForStore` 规定不能在事务里调用，`PostpayReceiptServiceImpl` 却在加锁事务里调用，真实环境确认收齐一律返回 40190；单元测试把它 mock 掉了所以没发现。改为两段：事务外读应收与版本并记下订单行版本，事务内加锁后只比对行版本（账单已被结算单冻结，推 PAID 也带版本守卫）。测试替身改为「在事务里调用即失败」，测试辅助方法不再自带事务；消融（读放回事务内）→ 10 例全红。本机 8091 从开台到确认收齐一次跑通：应收 12180（含 5% 服务费 580），现金 20000，找零 7820，核心单 PAID、台账 CLOSED。
11. **核心读已结清后付单报冲突（POS 联调日结时发现）**。`IndustryOrderServiceImpl#result` 断言已受理后付单必须 WAIT_PAY 且无付款时间，后付人工实收推到 PAID 后，所有经 `getForStore` 读这张单的地方（日结、取消校验）都报 10409，一张结过账的桌就让当天日结做不了。§6 偏差 6 的 PAID 读者核对只扫了直接 SQL，漏了这条断言。改为：WAIT_PAY 无付款时间，或 PAID 有付款时间且被 `ord_postpay_receipt_root` 覆盖，两者都合法（`acceptedPostpayPaymentState`，单测覆盖四种组合；消融退回只认 WAIT_PAY → 红）。本机 8091：5 张柜台结算单（含 POS 真机两张）后日结预览成功，应收 41370，issues 为空（AC7 实证）。`IndustryOrderServiceTest` 的 13 个错误在未改动的 HEAD 上同样存在（夹具未注入 ownershipWrites），与本次无关。
12. **读结算单补实收与找零（联调后补）**。原先只有确认收齐那次响应带实收与找零，POS 重启后无法再显示找零。V40 加 `received_minor` / `change_minor`（V40 未上过正式环境，合在同一版），确认时落库，重放确认时补写空值。
8. **餐饮 API 清单未重生成**。`docs/api/food/接口清单.*` 钉在 2026-10-30 的基线、不在闸门里，本期不重生成。

## 上线清单

> 依据：2026-11-13 本机测试环境（独立库 `ai_shop_cs`、端口 8091）从空店灌数据到 POS 真机结账、日结预览全程跑通时，逐个撞出来的前置条件。仓库里没有餐饮的部署配置（这些开关只出现在测试与校验脚本里），**上线前必须逐项对照目标环境核实**。

### 1. 数据库迁移（root 先执行；166 应用账号无 DDL 权限）

| 迁移 | 内容 |
|---|---|
| 平台 `V674__postpay_receipt.sql` | `ord_postpay_receipt` / `_root` / `_record` 三张新表；不改 `ord_order` |
| 餐饮 `V40__counter_settlement.sql` | `fnb_settlement` 加 `kind`、`received_minor`、`change_minor` 三列（V40 未在任何正式环境执行过，故三列合在一版） |

- 合并时与 PR #14 的冲突点：`FoodSchemaValidator` 的期望版本行（两边都改），取较大版本并核对。
- 平台迁移编号：2026-11-14 统一开发分支合并时由 V665 顺延为 V674（餐饮线 V649/V651/V654/V657/V659/V660/V665 依次改为 V668–V674）。
- 本机 MySQL 开着 binlog 时，V668（原 V649）的触发器需要 SUPER（或 `log_bin_trust_function_creators`）；正式环境由 root 执行则无此问题。

### 2. 应用配置（嵌入部署：餐饮跑在平台进程里）

| 配置 | 值 | 不配的症状 |
|---|---|---|
| `shop.food.enabled` | true | 餐饮整体不装配 |
| `shop.food.migration.mode` | validate（迁移已由 root 执行）/ migrate | — |
| `shop.food.menu-publication-enabled` | true | 菜单 `:publish` 返回 10404，可售目录为空 |
| `shop.food.station-publication-enabled` | true | 出品站发布 10404；员工下单因冻结出品路由失败 |
| `shop.outbox.sources.platform.mode` | UNIFIED | 开台即 10500「Food requires a registered unified PLATFORM outbox source」 |
| `shop.outbox.sources.inventory.mode` | UNIFIED（启用进销存时） | 同上类 |
| 库存权威（二选一，见下） | — | 员工下单 40190 |
| JVM 默认时区 | UTC（`-Duser.timezone=UTC` 或 `TZ=UTC`） | 审计字段 `created_at` / `updated_at`（`LocalDateTime.now()`）与业务时刻（UTC）差几个小时；见 [TDD-餐饮门店基本信息与时间约定](./TDD-餐饮门店基本信息与时间约定.md) |

**库存权威**：核心行业下单要求 `shop.inventory.stock-authority=INVENTORY`，或餐饮本地库存 `shop.food.local-stock.enabled=true`（餐饮单的库存归属为 FOOD_LOCAL）。前端注释称线上 stock-authority 目前为 **DUAL**：若属实，不开餐饮本地库存则所有员工下单都会 40190。
- 选餐饮本地库存：每个 SKU 要在餐饮库存建档（`POST /biz/x/food/stores/{s}/stock-items`）并期初；**基本单位必须与菜品计量单位一致**（菜品 `EA` ↔ 库存 `EA`/`PIECE`，`COUNT` 不认）。
- 选 INVENTORY：可售量与预留走进销存，需要进销存里有商品与库存。

拆分部署（food-server）：`food-server/application.yml` 已是 standalone + PLATFORM unified；另需宿主与 food-server 共用 `shop.services.internal-token`，以及上面两个发布开关、库存权威。

### 3. 运营侧前置（每个环境一次）

- 餐饮行业定价模板已发布（`/ops/pricing-templates/seed-import` → preview → publish），门店再 `POST /biz/pricing-configs/initialize`；门店税设置 `PUT /biz/pricing-tax-settings`。缺任一项：报价 40190。
- 商品审核：`goods.audit` 开着时，新菜品要经运营审核通过才可售（或按环境策略关闭该开关）。

### 4. 每家门店的数据

营业时间覆盖营业时段（`mch_store.open_hours`）；门店经营类目包含菜品所在类目（否则建商品 70068）；营业策略（`POST …/policies`，effectiveFrom ≤ 现在）；桌台区域与桌台；商品与 SKU（DINE_IN 履约）提交上架、SKU 定价策略；餐饮菜品档；菜单三段定义（COMPOSITION / CONTEXT 含 POS 与 WAITER 的 DINE_IN / SCHEDULE）并发布；出品站 + 每个 SKU 的出品路由并发布；（本地库存时）库存建档与期初；（可选）服务费规则与门店绑定。本机可复跑的样例：`~/.local/var/ai-shop-cs/seed.py`。

### 5. 上线后验证

按顺序跑一遍：开台 → 购物车写行 → 报价（`selection` 不传或传空选择，`pointsToUse` 为 null）→ `:confirm` → 员工下单（带 buyerNo 或 partyNo 之一）→ 柜台结算单 → 外部记账（bizNo = 结算单号）→ `:finalize` → 核心单 PAID、台账 CLOSED → 推进厨房任务后 `releaseService` 放台 → 开营业日 → 日结预览 issues 为空。本机脚本：`~/.local/var/ai-shop-cs/e2e.py`、`release.py`。

## §7 确认与完成

| 日期 | 事项 |
|---|---|
| 2026-11-12 | 草稿：产品决定 D1–D4 已拍板，默认规则 R1–R6 待确认 |
| 2026-11-12 | 用户「按草稿开始」，R1–R6 随之确认；第一段实现完成，偏差见 §6 |
