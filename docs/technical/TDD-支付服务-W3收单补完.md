# TDD-支付服务-W3收单补完

状态：实现中（E05、E04 处置、E09 恒等式① 已实现；E04 渠道关单已补代码与场景验收（2026-10-28，见资金闭环收尾）；E07 统一退款入口已补实现与回归（2026-10-29，见退款统一入口收尾）；E09 费用分录与恒等式② 未动，见 §6 偏差）
档位：1（新配置项 `shop.pay.recon.stale-scan.*`、`shop.pay.late-payment.auto-refund`；`ReconService` / `PayIntegrity` / `PayExceptionService` 接口；两个新作业）· 依据：[完成度复核与剩余开发计划](./支付服务-完成度复核与剩余开发计划.md) §3.3、D-10、D-13 · [I3 支付单退款流水](./TDD-支付服务-I3支付单退款流水.md) 偏差 6 / 9
创建：2026-10-26 · 分支 `codex/pay-i5-access`

> I3 留下、却不在任何 TDD 里的缺口（D-10），加上积分售后退款绕开退款单（D-13）。每行先写会变红的测试 → 修 → 消融。

## §0 对账一 · 需求 → 设计

| AC | 验收（计划 §3.3） | 落点 |
|---|---|---|
| E05 | 十万行待查时单次只取上限；渠道限速生效 | `ReconService#checkStalePayments(now, ScanLimits)`：按 id 游标分页（每页 200）、一轮上限、每渠道一轮份额；游标跨轮保留，扫到底从头 |
| E04 处置 | 关单后到账 → 原路退回且不重复 | `PayExceptionService#refund`：对那笔尝试本身发起退款执行（执行号 `PEX{id}`、来源 `PAYEXC:{id}`）；不开退款单；退款结果回写异常（成功 `RESOLVED`，失败回 `OPEN` 待人工）；`PayExceptionAutoRefundJob`（开关默认关） |
| E09 ① | 造不平账，巡检报出 | `PayIntegrity#ledgerMismatchedOrders`：已成功的支付单「已付 − 已退」≠ 收款账户借贷净额；`PayIntegrityJob` 每小时，任一项不为 0 记失败 |
| E04 渠道关单 | 关单同时关渠道 | **2026-10-28 已补代码**：支付关闭接渠道 CLOSE；无可信关闭证明保持原状态。见 [资金闭环收尾](./TDD-支付服务-资金闭环收尾.md) |
| E07 | 积分售后退款也产生 `pay_refund` 并过复核规则 | 已接入统一退款单、复核与发送；历史兼容和剩余边界见偏差 2 |
| E09 费用 / ② | 两条资金路径各一组费用分录；平台账户余额恒等式 | **未做**：费用金额来自 W6 对账单，出款流水尚无分录 |

## §1 现状（2026-10-26 核对）

- `checkStalePayments` 一次 `selectList` 全量拉回、逐笔调渠道查单。
- `PaymentOutcomeServiceImpl` 关单后到账 / 重复支付只记 `pay_exception`，没有任何处置；走退款单会因支付单可退余额为 0（已关）被拒。
- `PayIntegrity` 只在测试里被调用；`unbalancedVouchers` 把全部凭证与流水拉进内存分组。

## §2 方案

见 §0 落点。几处取舍：

- **E05 份额按「每轮」而不是令牌桶**：巡检本就按调度锁单跑，一轮内的份额等价于限速且可测；份额用完的行游标照样前移，扫到底从头时再轮到。
- **E04 退款不进账**：这笔钱进来时没有记流水（异常路径不过账），退回也不记 —— 两头都不碰；仍走退款执行器（先落库、抢发送权、不明只查不重发），执行号由异常 id 决定，重复点击落到同一行。
- **E09 用库内聚合**：顺带把 `unbalancedVouchers` 改成一条 SQL。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| E05 | `StaleScanPagingTest#capsEachRunAndResumesFromCursor`（一轮 150 条、游标停在 150；第二轮查询带 `id > 150`，扫到底游标归零）· `#perChannelBudget`（每渠道 20） | ✅ 2/2 | ① 每轮从 0 起 → 红（**第一版只断言返回值，mock 不看游标，消融不红**；改为截获第二轮的查询条件后才红）② 去掉渠道份额 → 红 ✅ |
| E04 处置 | `OpenApiFlowTest#latePaymentIsRefundedToTheCustomer`（建单 → 发起 → 关单 → 渠道到账：记 `LATE_PAYMENT`；原路退回落一行退款尝试 650、不开退款单、支付单仍 CLOSED；再点不重复） | ✅ | ③ 退款照常挂退款单 → `PAY_REFUND_EXCEEDS` 红 ✅ |
| E09 ① | `OpenApiFlowTest#ledgerMatchesEveryPaidOrder`（两张已付单都对得上；改坏一张的 `paid_minor`，只报它；凭证全平） | ✅ | ④ 恒等式条件改成永假 → 红 ✅ |
| 回归 | `ArchitectureTest` · 预算 · `TableHasProducerTest` · `MapperSmokeTest` · 退款与对账各套件 · `PayOrderBackfillFlowTest`（改成库内聚合后的 `unbalancedVouchers`）等 | ✅ 233 条（含上游模块） | — |

## §6 对账二 · 设计 → 实现

| AC | 文件 |
|---|---|
| E05 | `pay-domain` `service.ReconService`（`ScanLimits`）· `service.impl.ReconServiceImpl` · `StaleScanPagingTest` |
| E04 处置 | `acquiring.service.PayExceptionService(+Impl)` · `RefundServiceImpl#attachExecution`（`PAYEXC:` 不开退款单）· `RefundOutcomeServiceImpl`（`PEX` 执行号回写异常）· `job.PayExceptionAutoRefundJob` |
| E09 ① | `acquiring.backfill.PayIntegrity(+Impl)` · `mapper.ledger.LedgerMappers`（`countUnbalanced`、`ledgerMismatchedOrders`）· `job.PayIntegrityJob` |

### 偏差说明

1. **E04 渠道关单**：2026-10-28 补齐应用服务接线、冻结查询与支付宝/TEST CLOSE；不明保留、迟到下单及并发尝试保护，详见[资金闭环收尾](./TDD-支付服务-资金闭环收尾.md)。真实渠道联调和生产发布未在此执行。
2. **E07 已补统一入口**（2026-10-29）：保留积分退款的准备 → 授权现金 → 绑定证据 → 安全恢复，以及原现金退款号；新建现金流水挂统一退款单并过复核，发送委托统一执行器，成功同步现金证据与退款记账。历史缺执行号流水只回查；历史无支付单收款不补造统一余额。来源操作员贯通、线下凭证、复核规则管理仍待后续，详见 [退款统一入口收尾](./TDD-支付服务-退款统一入口收尾.md)。
3. **E09 费用分录与恒等式②**：费用金额来自渠道对账单（W6），出款还没有流水分录；没有数据来源的分录方法不先写。
