# TDD · 美业订金线下记账

状态：已实现（订金规则待产品确认 D6，见 §4） · 档位：1（汇总读口加可选查询参数 `relatedBizNo`、明细行加 `bizNo` 字段；不改表、不改美业插件）
关联：[B端门店功能矩阵-执行方案](./B端门店功能矩阵-执行方案-2026-11-09.md) D6 / W1.4；[TDD-业务单收款汇总](./TDD-业务单收款汇总.md)；[服务业对照表](./B端服务业-App接口与插件接口对照.md) 缺口 4
创建：2026-11-09

## §0 需求与落点

定案 D6：首期不做线上订金。店员收订金时，用外部记账（或付款链接）记一笔，单号填**预约号**；结账时抵扣；取消预约时由店员线下处理退或不退。

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | Given 预约 BAP 下记了订金 100，工单 BWO 扫码付了 200，When 读 BWO 汇总并带 `relatedBizNo=BAP`（应收 300），Then 已收 300、还差 0 | `ReceivableSummaryServiceImpl#summarize` |
| AC2 | 不带 `relatedBizNo` 时只算工单本身（与 W1.3 行为一致） | 同上 |
| AC3 | 明细每行标出记在哪张单名下（`bizNo`），App 据此显示「已付订金」 | `ReceivableSummaryService.Line#bizNo` |
| AC4 | 取消预约前读预约号的汇总，已收大于 0 时 App 提示「订金需线下处理」 | 复用 `GET /biz/stores/{storeNo}/receivables/{预约号}` |
| AC5 | `relatedBizNo` 与 `bizNo` 相同或格式不合法时 10400 | 同上 |

## §1 现状与影响面

- W1.3 已让外部记账、付款链接带 `bizNo`，汇总读口按单号加总；预约号与工单号是两个号。
- 美业工单账单（`GET /biz/x/beauty/work-orders/{no}/bill`）是插件的多来源投影，**不改**：让插件读宿主的外部记账要跨插件边界，属于美业负责人的设计。抵扣体现在汇总读口的「已收」，App 结账页显示「账单金额 − 已收 = 还差」。
- 工单与预约的对应：`bty_work_order.appointment_no`，App 从工单详情读到预约号后传入。散客（walk-in）没有预约，不传即可。

## §2 方案

### 契约

```
GET /biz/stores/{storeNo}/receivables/{workOrderNo}?dueMinor=30000&currency=TWD&relatedBizNo={appointmentNo}
→ {…, receivedMinor, outstandingMinor,
   lines:[{bizNo, kind, refNo, status, currency, amountMinor, countedMinor, at}], anomalies:[…]}
```

- 明细新增字段 `bizNo`；W1.3 的调用方不传 `relatedBizNo` 时每行 `bizNo` 都等于路径里的单号。
- 记订金：`POST /biz/payment/offline-records`，`bizNo` 填预约号，`note` 写「订金」。

### 模块

| 动作 | 路径 |
|---|---|
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/ReceivableSummaryService.java`（`relatedBizNo`、`Line.bizNo`） |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/impl/ReceivableSummaryServiceImpl.java` |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/pay/BizReceivableController.java` |

## §4 待决

| # | 问题 | 建议 |
|---|---|---|
| D6（产品确认中） | 订金金额、24 小时前取消可退、爽约不退 | 规则由店员线下执行，后端只记账与汇总，规则改了不用改代码 |
| D18（实施中发现） | 美业账单要不要自己扣订金 | 首期不改插件，抵扣走汇总读口；以后插件要把「收齐」做成结账前置条件时，直接调宿主汇总服务，由美业负责人定 |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 |
|---|---|
| AC1、AC3、AC5 | `BizReceivableFlowTest#depositRecordedOnTheAppointmentCountsTowardTheWorkOrder` |
| AC2 | 同上（不带 `relatedBizNo` 时还差 100） |
| AC4 | 同上（按预约号读到已收 100） |
