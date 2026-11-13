# TDD-餐饮POS服务端缺口第二批

状态：已实现（2026-11-13，本机 8091 实测通过；未推送）
档位：1（新增端点、事件载荷加字段、既有接口加可选参数；不新建表、不加权限码）
关联：POS《后端能力需求清单》B14 / B15 / B16 / B17；[TDD-餐饮柜台后付结账](./TDD-餐饮柜台后付结账.md) §6 偏差 6 的遗留项；[TDD-餐饮门店基本信息与时间约定](./TDD-餐饮门店基本信息与时间约定.md)
创建：2026-11-13

> **L1 定位**：用户「先完成剩余可以完成的任务」——不等产品决定、能独立做完的 POS 服务端缺口，加上柜台结账联调中发现的两处遗留。

## §0 对账一 · 需求 → 设计

| AC | Given / When / Then | 落点 |
|---|---|---|
| AC1（B14） | Given 终端账号；When `POST /biz/x/food/stores/{s}/cash-shifts` 一次提交整张交班单（钱箱、班次人、备用金、开交班时刻、实盘、POS 应有、接班人、差异审批、非销售现金明细、挂班的外部记账号）；Then 写入既有四张表，返回班次号、差额（实盘 − 应有）、非销售进出合计、条数；同一 Idempotency-Key 重放返回同一张，内容不同 10409 | `CashShiftServiceImpl#submit` |
| AC2（B14） | 结构不对 → 10400：流水类型与方向不配套（FLOAT_IN 只能 IN、EXPENSE / DEPOSIT 只能 OUT、TRANSFER 均可）、金额 ≤ 0、交班早于开班或晚于现在 2 分钟、班长超过 3 天、同一记账号重复、**实盘 ≠ 应有却没带差异审批** | `#validate` |
| AC3（B15） | 一笔外部记账只能挂一个班：已挂过的再挂 → 10409，整张交班不写入 | `fnb_cash_payment_link.core_payment_ref` 唯一 + 事务 |
| AC4（B14） | `GET /biz/x/food/stores/{s}/cash-shifts/{shiftNo}` 读回；别家门店 10404 | `#get` |
| AC5（B16） | 离线终端用同一个 Idempotency-Key 回放，**无论隔多久**都返回首次结果、不重复执行；保留期承诺 ≥ 7 天（覆盖 POS 72 小时离线窗口） | `IdempotencyService`（记录从不清理、重放不看 `expire_at`） |
| AC6（B17） | 厨房任务带 `sourceLineRef`（下单购物车行号）；任务列表可按 `batchNo` 查（此时 `stationNo` 可省，二者至少一个） | `TaskVO`、`BizKitchenTaskController` |
| AC7（遗留） | 已被柜台后付实收引用的外部记账不能作废、不能更正（10409） | `OfflineRecordServiceImpl#lockForChange` |
| AC8（遗留） | 后付实收发生后，报表开启时对应订单所在营业日标记重算；报表关闭时事件仍只归档 | `ReportCorrectionConsumer` + `POSTPAY_RECEIPT_RECORDED.orderNos` |

## §1 现状与影响面

- 交班四张表（`fnb_cash_shift` / `fnb_cash_entry` / `fnb_cash_payment_link` / `fnb_cash_count`）V1 就有，没有任何代码引用；本 TDD 不改表。
- `sys_idempotent` 写了 24 小时的 `expire_at`，但全仓库没有清理任务，`replay` 也不读它——实际是永久保留。
- `TaskVO` 构建时已经读了整行 `fnb_order_line`（取座位、道次），带出 `source_line_ref` 零额外查询；服务层 `queue(…, batchNo)` 早已支持按批次，只是 Biz 没开放。
- 外部记账的作废 / 更正不查 `ord_postpay_receipt_record`；`ReportCorrectionConsumer` 不认后付实收事件，且该事件原先不带订单号。

## §2 方案

### 交班（B14 / B15）

`POST /biz/x/food/stores/{storeNo}/cash-shifts`，头 `Idempotency-Key`，权限 `pay:cash:receive`：

```json
{"drawerNo":"POS-1","ownerNo":"SF-M0001","currency":"CNY","openingMinor":20000,
 "openedAt":"2026-11-13T02:00:00Z","closedAt":"2026-11-13T18:00:00Z",
 "countedMinor":31680,"expectedMinor":31680,"acceptedBy":"SF261113HQE391WR55","approvalRef":null,
 "entries":[{"kind":"EXPENSE","direction":"OUT","amountMinor":500,"voucherRef":"V0","approvalRef":null}],
 "paymentRefs":[{"recordNo":"OFR…","kind":"PAY","occurredAt":"2026-11-13T17:00:00Z"}]}
```

- **POS 是权威**：`expectedMinor` 由 POS 算（备用金 + 现金收款 − 找零 ± 非销售现金），服务端只存并重算差额；外部记账号只校验格式与唯一，**不回查记账存在与金额**（外部记账在核心，餐饮读不到；对账在日结与报表侧做）。
- 状态直接写 `HANDED_OVER`（单次提交，没有开班 / 盘点中间态）。
- 时刻按 UTC 存取（不依赖 JVM 时区）。
- 拆分部署：`CashShiftInternalApi` + `InternalCashShiftController`（food-server）+ `RemoteCashShiftService`（food-web）。

### 其余

- B16：`IdempotencyService.RETENTION_DAYS = 7`，注释写明「当前不清理、重放不看 expire_at；将来加清理不得短于 7 天」。
- B17：`TaskVO` 末尾加 `sourceLineRef`（保留旧构造器）；`GET …/kitchen/tasks` 加可选 `batchNo`。
- AC7：`lockForChange` 在行锁之后查 `usedRecords`（实收确认锁同一行，结果即最终事实）。
- AC8：`PostpayReceiptPort.Recorded` 加 `orderNos`；`ReportCorrectionConsumer` 支持该事件并经 `OutboxRouteContribution` 接管 `PLATFORM:POSTPAY_RECEIPT_RECORDED` 的只归档路由（统一 outbox 不允许同一路由既只归档又有消费者）。

### 模块设计

| 动作 | 路径 |
|---|---|
| 新增 | `food-api/.../dto/CashShiftContracts.java`、`service/CashShiftService.java`、`spi/CashShiftInternalApi.java` |
| 新增 | `food-core/.../service/impl/CashShiftServiceImpl.java`（JdbcTemplate） |
| 新增 | `food-web/.../api/biz/BizCashShiftController.java`、`foodclient/RemoteCashShiftService.java` |
| 新增 | `food-server/.../api/InternalCashShiftController.java` |
| 修改 | `FoodBusinessOperationsConfiguration`、`FoodWebAutoConfiguration`、`FoodClientConfiguration`（装配） |
| 修改 | `IdempotencyService`、`KitchenContracts.TaskVO`、`KitchenTaskServiceImpl#view`、`BizKitchenTaskController` |
| 修改 | `OfflineRecordServiceImpl`、`PostpayReceiptPort.Recorded`、`PostpayReceiptServiceImpl`、`ReportCorrectionConsumer` |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 结果 |
|---|---|---|
| AC1、AC4 | `CashShiftPrimaryDatabaseTest#wholeShiftIsStoredOnceAndReplaysByKey`（真实 MySQL + 全部餐饮迁移） | 通过 |
| AC2 | `#differenceNeedsApprovalAndMalformedShiftsAreRejected` | 通过；消融（去掉差异审批校验）→ 红 |
| AC3 | `#aRecordCanBelongToOnlyOneShiftAndTheSecondSubmitLeavesNothing` | 通过 |
| AC5 | `IdempotencyTest#replayAfterExpireAtStillReturnsFirstResult` | 通过；消融（保留 1 天）→ 红 |
| AC6 | `KitchenQueueApiTest#batchQueryReturnsTasksWithCartLineRefAndOneFilterIsRequired` | 通过；消融（忽略 batchNo）→ 红 |
| AC7 | `BizOfflineRecordFlowTest#record_used_by_postpay_receipt_cannot_be_voided_or_corrected` | 通过；消融 → 红 |
| AC8 | `ReportCorrectionConsumerTest#postpayReceiptMarksEveryCoveredOrderDayAndTakesOverTheArchiveRoute` | 通过；消融 → 红 |
| 装配 | `FoodControllerRegistrationTest`（新控制器已登记） | 通过 |

本机 8091（2026-11-13）：按批次查任务返回 `sourceLineRef` L1 / L2；作废已结账的记账 10409；交班提交成功（差额 0、非销售支出 500、挂 1 笔记账），同一记账挂第二个班 10409，读回一致。

## §6 对账二 · 设计 → 实现

与 §2 一致。说明：交班不回查外部记账（见 §2），列为后续（需要核心开一个按记账号读的端口）；`CashShiftVO` 的时刻按 UTC 解释。
