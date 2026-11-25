# TDD-商户App柜台报价状态

状态：已实现（2026-11-16；原生 MySQL 用例已写未跑，见 §5）
档位：1 · 依据：ai-shop-bapp `docs/implementation/App-API对齐清单-2026-11-16.md` P1 第 8 项 · 产出：本文

## L1 定位

商户 App 的服务业收银台（`service_counter_pages.dart`）重新打开一张柜台报价时，要知道它停在哪一步：还能不能确认、确认过没有、开单没有。App 读 `GET /biz/service-orders/counter-quotes/{no}/state`，后端此前没有这条，App 只能把柜台按钮全部关掉。

## §0 对账一 · 需求 → 设计

| AC | 内容 | 落点 |
|---|---|---|
| AC1 | Given 刚生成的报价 When 读状态 Then `canAccept=true`、`confirmation=null`、`result.phase=QUOTED` | `CheckoutServiceImpl#readCounterState` |
| AC2 | Given 已确认 When 读状态 Then `canAccept=false`、`confirmation` 为当次确认、`phase=CONFIRMED` | 同上，读 `ord_checkout_confirmation`（不加锁） |
| AC3 | Given 已开单 When 读状态 Then `phase=CREATED`、`result.orderNo` 为订单号 | 同上，读 `ord_checkout_operation.order_no` |
| AC4 | 权限与读报价相同（`CHECKOUT_PREPARE` + `ORDER_VIEW`，`requireCounterReader` + 门店范围） | `BizManualServiceAdmissionController#readCounterState` |

## §1 现状与影响面

`BizManualServiceAdmissionController` 只有建报价、读报价、确认、开单四条。确认号与订单号都已记在 `ord_checkout_operation`（`confirmation_no`、`order_no`），只缺一个读口。不改任何写路径。

## §2 方案

- 端点：`GET /biz/service-orders/counter-quotes/{operationNo}/state` → `{canAccept, confirmation: CounterConfirmation|null, result: {phase: QUOTED|CONFIRMED|CREATED, orderNo|null}}`。形状与 App mock（`mock_service_workflow.dart`）一致，App 只需删掉 `contracts.dart` 里那条拦截。
- `canAccept` = 未确认、未开单、报价状态 `OPEN`。是否由当初那位收银员确认仍由 `acceptances` 端点判（命名空间校验），这里不重复。
- 改动：`ManualServiceAdmissionPort` 加 `CounterQuoteState` / `CounterResult` 与缺省方法 `readCounterState`；`CheckoutService`、`ManualServiceAdmissionService` 及实现、`ManualServiceAdmissionPortImpl` 各加一个转发；`BizEndpointPermTest` 登记权限；`docs/api/service-counter-state.openapi.json` 与外部 Flutter 消费方登记。

## §5 对账三 · 实现 → 需求

| AC | 测试 | 状态 |
|---|---|---|
| AC1–AC3 | `ManualCounterPurchaseIT#counterStateFollowsQuoteAcceptanceAndOrder` | 已写；需原生 MySQL（`COMMERCE_MYSQL_*`），本机缺测试库凭据，未运行 |
| AC4 | `BizEndpointPermTest` | 已通过 |

## 2026-11-25 · 共用订单读取柜台现金

- 原生 Android 共用订单在渠道流水返回 70082 时仍读取现金记录。柜台服务单以已入账现金声明为准，列表与详情均显示主订单号和实收金额。
- `GET /biz/order/service-cash-receipts` 在当前主体与门店内查询 `BEAUTY / CASH / ADMITTED` 记录，沿用订单查看权限及配送员金额限制。支持按主订单号查询，列表按稳定游标翻页。
- 详情展示收款编号、币种和原始实收。线上支付权限和现金写路径保持原约束。
- 验证通过：后端订单权限测试 12 项、原生 MySQL 柜台收款测试 1 项、Android 订单模块测试 40 项。80 环境使用 Oscar 的美业测试门店在原生 Android 搜索 `OD20261125000001`，详情显示已付款、现金实收 `TWD 100.00` 及原收款编号。回归时渠道流水仍返回 70082。
