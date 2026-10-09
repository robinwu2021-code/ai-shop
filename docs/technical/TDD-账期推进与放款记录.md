# TDD-账期推进与放款记录

状态：**已确认**（用户 2026-10-09「以上按照建议」）
档位：2（新表 `stl_payout` · 新 Job · 跨三端 · 改财务操作粒度）
关联：[ADR-011 商家资金走自营供应商模式](ADR/ADR-011-商家资金走自营供应商模式.md)（本 TDD 是它 §3「出款走银行卡转账」的落地）·
[PRD-商家资金到账与对账](../requirements/PRD-商家资金到账与对账.md) §7（本次增补）·
[TDD-账期批次推进任务](TDD-账期批次推进任务.md)（三个待拍板参数在本 TDD §2.1 拍定，那份并入本篇）·
[TDD-供应商结算与双轨资金](TDD-供应商结算与双轨资金.md) §2.4
创建：2026-10-09 · 最后更新：2026-10-09

## §0 对账一 · 需求 → 设计

用户 2026-10-09：「计算从订单，流水（包含门店 id，门店名称），到每个门店的流水，门店日流水汇总，营业汇总，到提现记录。目前提现暂时只能运营端操作，因为微信无法转账，只能通过银行接口实现。」

「提现」按 ADR-011 / PRD §2 的拍板叫**放款**（供应商付款）。下表里 AC1–AC3 已有，本 TDD 做 AC4–AC9。

| AC | 需求（一句话） | 落点 | 新/旧 |
|---|---|---|---|
| AC1 | 订单 → 结算单，带门店快照 | `generateForOrder` · `stl_bill.store_no` | **已有** |
| AC2 | 门店流水 / 日汇总 / 营业汇总 | `/biz/settle/{bills,daily-flow,income}` · `/ops/settle-stats` | **已有** |
| AC3 | 出款对账（A 侧 + 银行流水 B 侧） | `PayoutReconAxis` · `bank-flows/import` | **已有** |
| AC4 | 售后期过 → 自动可结算 → 自动入批 → 到期自动截批 → 自查过了自动可放款 | 新 `SettleBatchJob`（每小时）+ 新步骤 `reconcileClosedBatches` | 新 |
| AC5 | **自营单也进批**。活钱走的是自营，批次链此前只收第三方 | `markSettleable` / `collectIntoBatches` 收 `PENDING_RECON` | 新 |
| AC6 | 放款以「批次 × 收款号」为一笔，有记录 | 新表 `stl_payout`；真正的 `release` | 新 |
| AC7 | 付款清单导出 / 凭证回填 / 银行流水匹配都挂在放款记录上 | `payout-list` · `/ops/payouts/{no}/paid` · `PayoutReconAxis` | 改 |
| AC8 | 商家端看得到「哪笔打了、凭证号、哪天」 | `MySettleBatch` 加 payout 摘要 | 新 |
| AC9 | `stl_withdraw` 退役：两个 tab 撤掉 | ops-web nav / 菜单种子迁移 | 退 |

**孤立项**：无。

## §1 现状与根因

### 1.1 两条线互不相接，而且活钱那条不在批次链里

```
批次链  markSettleable → collectIntoBatches → closeDueBatches → (无人推进) → decide
         只收 status=PENDING（第三方）                          ↑ 叫 release，实为「挂起处置通过」
应付链  PENDING_RECON → confirm → paid(逐张 settle_no 回填凭证) → 导清单 → 导银行流水 → PayoutReconAxis
         自营单，今天唯一在跑的出款路径
```

三个事实（2026-10-09 自己 grep 核实）：

1. `markSettleable` / `collectIntoBatches` 的查询条件是 `status = PENDING`。自营单生成时落 `PENDING_RECON`（`SettleServiceImpl:396`）。**所以自营单一张都没进过批次。**
2. 三步推进方法在主代码里**零调用**；`closeDueBatches` 把批次置 `COLLECTED` 后**没有任何代码把它推到 `RECONCILED`**。`decide()` 只接受 `BLOCKED / RECONCILING`。
3. `/ops/settle-batches/{no}/release` 调的是 `decide(pass=true)` → `RECONCILED`。**没有任何地方把批次置 `RELEASED`**，也没有任何地方从批次产生一笔付款。

### 1.2 应付链的粒度是错的

财务一笔网银转账 = 一个主体一个收款号一个账期。应付链让财务**逐张结算单回填同一个凭证号**，于是 `PayoutReconAxis` 的 `DUP_REF`（同号多单）在正常操作下就会触发 —— 它分不清「复制粘贴错了」和「本来就是同一笔转账」。

### 1.3 `stl_withdraw` 是僵尸

有表、有审批状态机、有「提现审批」「提现与税」两个 tab，B 端入口已撤，生产 0 行。PRD §6 原话「现在改代价最小」。

## §2 方案（= 实施计划，分三批提交）

### 2.1 拍定的参数（原 TDD-账期批次推进任务 §3）

| | 决策 | 定值 | 落点 |
|---|---|---|---|
| A | 冻结窗口 | **7 天**，`freeze_expire_at = 本批最早成交 + 7d` | `shop.settle.freeze-days: 7` |
| B | 时区 | `Asia/Shanghai` 全局一个值，不动 | 现状 |
| C | 频率 / 起始 | **每小时**；只推进 `accrued_at ≥ 起始日` 的单；**首轮 dry-run**：Job 默认 `dry-run: true`，只报「会推进多少」不写库，运营看过数再翻成 false | `shop.job.settle-batch.{cron,dry-run,start-date}` |

### 2.2 批次状态机补全

```
DRAFT ─close→ COLLECTED ─④reconcile→ RECONCILING ─自查全过→ RECONCILED ─⑤release→ RELEASED
                                                  └─自查有差异/风控拦→ BLOCKED ─decide→ RECONCILED
```

- **④ `reconcileClosedBatches()`**（新）：`COLLECTED` 的批次逐个过三道自查
  —— `checkBatchTotals` 合计对得上、本批无 PENDING 的单据差异、`fundRiskService` 不拦；
  `reconScope = SELF_ONLY` 全过即 `RECONCILED`；任一不过 → `BLOCKED` + 原因。
  `SCOPE_BOTH`（要通道对账）今天没有产生者，先按 SELF_ONLY 走，留日志。
- **⑤ `release(batchNo)`**（改）：只接受 `RECONCILED`。
  按 `pay_merchant_no` 分组生成 `stl_payout`（一组一笔），批次置 `RELEASED`，本批结算单置 `CONFIRMED`。
  **三道闸在这里**：缺生效收款账户 → `PAYOUT_ACCOUNT_MISSING`；
  任一自营单进项票未了结 → `INVOICE_REQUIRED`（带 settleNo 清单）。闸不过批次不动，运营补齐再放。
  第三方单（`PENDING`）的批次不生成 payout —— 它们走分账轨，`RELEASED` 后由 `executeSplit` 接（不在本 TDD）。
- 原 `release` 端点语义改名 **`approve`**（挂起处置通过）。`hold` 不变。

### 2.3 `stl_payout`

```sql
stl_payout
  payout_no         VARCHAR(32)  业务键 PO...
  batch_no          VARCHAR(32)  所属批次
  entity_no         VARCHAR(32)  收款主体
  pay_merchant_no   VARCHAR(32)  收款号（分组键）
  account_name / bank_name / bank_branch / account_no_masked   付款时快照（银行要户名，而账号会改）
  amount_minor      BIGINT       = 本组结算单 net 之和
  bill_count        INT
  currency          VARCHAR(8)
  status            VARCHAR(16)  PENDING → EXPORTED → PAID → MATCHED ；FAILED
  channel           VARCHAR(16)  MANUAL（一期全部）/ BANK_API（预留）
  payment_ref       VARCHAR(64)  凭证号（网银流水号）
  bank_flow_no      VARCHAR(64)  对上的银行流水
  exported_at / paid_at / paid_by / matched_at / fail_reason
  tenant_no, created_at, updated_at
stl_bill        + payout_no        这张单在哪笔付款里
stl_bank_flow   + matched_payout_no  （原 matched_settle_no 留着给存量）
```

状态只有一个方向：`PAID → MATCHED` 由 `PayoutReconAxis` 在银行流水勾上时写；`FAILED` 由运营标（打款被退回）并写原因，退回后批次回 `RECONCILED`、payout 作废、结算单回 `PENDING_RECON`。

### 2.4 契约变更

| 项 | 改什么 |
|---|---|
| 库表 | 新 `stl_payout`；`stl_bill.payout_no`、`stl_bank_flow.matched_payout_no` |
| 端点 /ops | 新 `GET /ops/payouts`、`POST /ops/payouts/{no}/paid`、`POST /ops/payouts/{no}/fail`；`POST /ops/settle-batches/{no}/approve`（原 release 语义）；`release` 语义变；`GET /ops/payables/payout-list` 改读 payout（路径不变） |
| 端点 /biz | `GET /biz/settle/batch` 的 `BatchVO` 加 `payoutStatus / paymentRef / paidAt` |
| 权限码 | 复用 `finance:settle:execute`（approve/release）、`finance:payout:execute`（paid/fail/payout-list）；不加新码 |
| 配置项 | `shop.settle.freeze-days`、`shop.job.settle-batch.{cron,dry-run,start-date}` |
| ErrorCode | 新 `PAYOUT_ACCOUNT_MISSING`、`BATCH_NOT_RELEASABLE`、`PAYOUT_NOT_PAYABLE` |
| i18n | ops-web 财务文案（放款 tab）、b-app `income.*` 三条 |
| 菜单 | 撤「提现审批」「提现与税」；「账期批次与放款」改为批次 + 放款两段 |

### 2.5 分批

| 批 | 内容 | 判据（§5） |
|---|---|---|
| **1** | 自营入批 + 冻结窗口 + ④reconcile + `SettleBatchJob`（dry-run/起始日） | AC4 AC5 |
| **2** | `stl_payout` + 真 release/approve + payout 端点 + payout-list 改读 + `PayoutReconAxis` 挂 payout | AC6 AC7 |
| **3** | ops-web 放款 tab + 撤提现 tab + 菜单迁移 + b-app 账期块 + 清单重跑 | AC8 AC9 |

### 2.6 明确不做

- **自动放款**：`release` 仍是运营点的。ADR-011 §5「不自动划转」。
- **银行 API**：`channel` 预留 `BANK_API`，一期全 `MANUAL`。
- **第三方分账轨的 executeSplit / confirmSplit**：批次 `RELEASED` 之后那一步，等 B7 口径。
- **删 `stl_withdraw` 表**：撤入口与菜单；表与 `WithdrawService` 留着（生产 0 行，删表另起迁移时再说）。
- **日汇总物化**：见 TDD-B 端每日流水补齐与按天明细 §8。
- **存量自营单一次性入批**：由起始日挡住。存量（起始日之前）继续走老的逐张 confirm/paid，两条路并存到存量清零。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC5 | `SettleBatchFlowTest#selfOperatedBillsEnterBatches` | ✅ | ✅ 改回只收 PENDING → 红 |
| AC4 | `SettleBatchFlowTest#reconcilePromotesCleanBatch` | ✅ | — |
| AC4 | `SettleBatchFlowTest#reconcileBlocksBatchWithOpenDiff` | ✅ | ✅ 跳过差异检查 → 红 |
| A | `SettleBatchFlowTest#freezeExpireIsEarliestAccrualPlusSevenDays` | ✅ | — |
| C | `SettleBatchJobFlowTest#dryRunReportsButWritesNothing` | ✅ | dry-run 仍写库 → 红 |
| C | `SettleBatchJobFlowTest#startDateTomorrowMovesNothing` + `SettleBatchFlowTest#startDateFencesOffLegacyBills` | ✅ | 去掉起始日谓词 → 红 |
| AC4 | `SettleBatchJobFlowTest#stepOrderIsFixedAndFailuresAreIsolated` | ✅ | 换成 collect→mark → 当轮少入批 → 红 |
| AC6 | `PayoutFlowTest#放款按收款号分组一组一笔_合计等于本组结算单` | 待填 | 不分组 → 红 |
| AC6 | `PayoutFlowTest#缺收款账户不放_批次不动` | 待填 | 去掉账户闸 → 红 |
| AC6 | `PayoutFlowTest#任一单票未了结不放_报出是哪几张` | 待填 | 去掉票闸 → 红 |
| AC6 | `PayoutFlowTest#只有RECONCILED能放_RELEASED再放是CONFLICT` | 待填 | — |
| AC7 | `PayoutFlowTest#回填凭证后结算单跟着PAID` | 待填 | 不级联 → 红 |
| AC7 | `PayoutBankReconFlowTest`（改）`#流水按payout勾_勾上即MATCHED` | 待填 | 不写 MATCHED → 红 |
| AC7 | `PayoutListFlowTest`（改）`#清单一行一笔payout_导出后置EXPORTED` | 待填 | — |
| AC9 | `packages/shared` nav / perm 守卫 | 待填 | — |

判据取「钱有没有按规则动」（状态、金额、分组），不取耗时。

## §6 对账二 · 设计 → 实现

### 批 1（2026-10-09）

```
backend/pay/pay-domain/.../pay/BillIdentity.java            恒等式补运费两项（顺带修的潜伏 bug，见下）
backend/pay/pay-domain/.../pay/SettleBatchService.java      +markSettleable(from) +collectIntoBatches(from) +reconcileClosedBatches +preview
backend/pay/pay-domain/.../pay/impl/SettleBatchServiceImpl.java  PUSHABLE 收 PENDING_RECON · 起始日谓词 · freeze_expire_at · ④自查
backend/shop-app/.../paybridge/SettleBatchJob.java           新：每小时四步，dry-run 默认开，起始日
backend/shop-app/src/main/resources/application.yml          shop.settle.freeze-days · shop.job.settle-batch.{cron,dry-run,start-date}
backend/shop-app/src/test/.../SettleBatchFlowTest.java       +6 条（20/20）
backend/shop-app/src/test/.../SettleBatchJobFlowTest.java    新 5 条
```

**设计外多修的一处**：`BillIdentity.gap` 漏了运费两项（落库公式含运费）。
凡是快递单都被门 1 判「不平」、永远进不了批。没人发现是因为推进链路从没跑过 ——
第一次跑起来那天会是几百条 `BILL_UNBALANCED`。`expressBillIsBalanced` 钉住。

**判据与消融**：`SettleBatchFlowTest` 20/20、`SettleBatchJobFlowTest` 5/5；
三处消融各红一条（恒等式去运费 / PUSHABLE 退回只收 PENDING / 自查跳过差异检查）。
全量 2579 条在 HEAD 干净副本上跑：1 红是我自己测试的种子污染（`preview` 数全库，
断言写死「候选 1」），改成相对断言。

**配置键与 §2.4 的一处偏差**：起始日放在 `shop.job.settle-batch.start-date`（Job 拥有它），
不是 `shop.settle.batch-start-date`。


## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-09 | 用户拍板 A/B/C 与放款粒度；状态直接为「已确认」，开始批 1 |
