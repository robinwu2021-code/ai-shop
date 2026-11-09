# TDD · 业务单收款汇总

状态：已实现 · 档位：1（新增读端点；付款链接与外部记账请求体各加可选 `bizNo`；`ord_offline_record` 加一列 `biz_no`（V641，**带 DDL**））
关联：[B端门店功能矩阵-执行方案](./B端门店功能矩阵-执行方案-2026-11-09.md) D5 / W1.3；[餐饮对照表](./B端餐饮-App接口与插件接口对照.md) 缺口 1；[服务业对照表](./B端服务业-App接口与插件接口对照.md) 缺口 2
创建：2026-11-09

## §0 需求与落点

定案 D5：首期不做付款编排引擎。业务侧（餐饮结算单、美业工单账单）算应收；顾客可以拆开付（扫码一部分、现金一部分、储值扣一部分），后端给一个「已收 / 还差」汇总读口，App 据此判断是否收齐。

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| AC1 | Given 同一业务单号下有付款链接已付 300、外部记账现金 200，When 读汇总（应收 500），Then 已收 500、还差 0 | 汇总服务 |
| AC2 | Given 其中付款链接退款 100，Then 已收 400、还差 100 | 同上（付款链接取 `paid_minor - refunded_minor`） |
| AC3 | 外部记账作废后不计入；更正后按更正后的金额计 | 同上 |
| AC4 | 储值扣款按来源单号计入；冲回后不计入 | 同上 |
| AC5 | 待付、已关闭、已过期的付款链接不计入已收，但在明细里列出状态，App 可提示「还有一张链接没付」 | 同上 |
| AC6 | 币种与应收不一致的收款不计入，单列为异常 | 同上 |
| AC7 | 只能读本店的业务单；权限 `mch:pay:order:view` | 控制器 |
| AC8 | 生成付款链接、记外部账时可带业务单号；不带时行为不变（外部记账旧幂等键照常重放） | 收款域 `collect`；`OfflineRecordService#record` |

## §1 现状（2026-11-09 读代码）

| 收款方式 | 能否按业务单关联 | 依据 |
|---|---|---|
| 付款链接（`POST /biz/payment/collection`） | **不能**：请求体只有 `amountMinor / subject / expireMinutes`；`pay_order.biz_no` 写的是随机的 `BZC…` | `CheckoutServiceImpl#collect` |
| 静态收款码 | **不能**：顾客自己输金额，没有业务单 | `QrCode` 只有门店、桌号、限额 |
| 外部记账（`/biz/payment/offline-records`） | **不能直接用**：已有的 `orderNo` 必须是本店商城子单（已拍板 D3），餐饮结算单、美业工单号会被拒 → 加独立的 `biz_no` 列 | `OfflineRecordServiceImpl#requireOwnOrder` |
| 储值扣款 | 能：`SourceRef(sourceSystem, sourceType, sourceNo)` | `StoredValueRedemptionService#finish` |

- `pay_order` 已有 `biz_type / biz_no`（索引 `idx_pay_order_1`）和 `paid_minor / refunded_minor`，**不需要改表**。
- 付款链接在收款服务（near-pay）里；宿主经 `RemoteBizPaymentAppService` 远程调用，所以收款服务要跟着发版。

## §2 方案（建议）

### 契约

```
POST /biz/payment/collection
{"amountMinor":300, "subject":"…", "expireMinutes":15, "bizNo":"FST…"}   ← 新增可选 bizNo（≤64）

POST /biz/payment/offline-records
{"amountMinor":200, "currency":"TWD", "method":"CASH", "bizNo":"FST…"}   ← 新增可选 bizNo

GET /biz/stores/{storeNo}/receivables/{bizNo}?dueMinor=500&currency=TWD      权限 mch:pay:order:view
→ {bizNo, currency, dueMinor, receivedMinor, outstandingMinor, overpaidMinor,
   lines:[{kind: PAY_LINK|OFFLINE|STORED_VALUE, refNo, status, currency, amountMinor, countedMinor, at}],
   anomalies:[币种不符、未计入的行]}
   （W1.4 起：可选查询参数 relatedBizNo；明细行多一个 bizNo 字段，见 TDD-美业订金线下记账）
```

- 付款链接带了 `bizNo` 时，`pay_order.biz_type` 仍是 `BIZ_COLLECT`，`biz_no` 写业务单号（`out_order_no` 仍是随机号，幂等不变）；不带时照旧写随机号。同一业务单可以开多张链接。
- 外部记账请求体加可选 `bizNo`，存新列 `ord_offline_record.biz_no`；与 `orderNo` 分开，不校验来源单据、不改其状态。请求摘要只在带了 `bizNo` 时追加，旧键重放不受影响。
- 储值沿用 `ent_usage.source_order_no`（`asset_kind=STORED_VALUE`），只计 `COMMITTED`；储值消费目前没有冲回流程，`REVERSED` 等状态按不计处理。
- 三处统一叫 `bizNo`，字符集 `[A-Za-z0-9_.:-]{1,64}`，门店内精确匹配。
- 收款服务新增内部读口 `GET /internal/pay/v1/biz/stores/{storeNo}/order-receipts?bizNo=&operatorNo=`（付款链接 + 储值原始行，各最多 50 条）；加减在宿主做。
- 应收 `dueMinor` 由 App 从结算单 / 工单账单读出后传入，读口只做加减，不落库、不改任何单据状态。

### 模块

| 动作 | 路径 |
|---|---|
| 修改 | 收款域 `CheckoutService#collect`（重载带 `bizNo`）、`PayBizFacade#collect`、`PayBizInternalApi.CollectReq`、`InternalPayBizEndpoint`；宿主 `BizPaymentController` / `BizPaymentAppService` 两个实现 |
| 新增 | 收款域 `MerchantCollectLinkQueryService#ofBizNo`、`PayBizFacade#receipts`、内部读口 `order-receipts` |
| 新增 | 迁移 `V641__offline_record_biz_no.sql`；`OfflineRecordService#ofBizNo`；`RecordBody.bizNo` |
| 新增 | 宿主 `ReceivableSummaryService` + `ReceivableSummaryServiceImpl` + `BizReceivableController` |
| 修改 | `ShopStoreMemberRoutes`、`BizEndpointPermTest` |

### 部署

- **V641 带 DDL**（`ALTER TABLE ord_offline_record ADD COLUMN biz_no …` + 建索引）：166 应用库账号无改表权限，部署前由 root 执行或临时授权。
- 宿主与收款服务（near-pay）**一起发**：只升宿主时，收款服务会忽略 `bizNo`，汇总读口调 `order-receipts` 会失败。

## §4 待决

2026-11-09 用户确认按建议执行。

| # | 问题 | 定案 |
|---|---|---|
| D14 | 静态收款码收的钱没有业务单号，算不算 | 首期不算；拆单收款一律用付款链接（链接可以当场出码给顾客扫） |
| D15 | 应收由谁给 | 首期 App 传入（只读、不落库）；餐饮、美业插件以后可以直接调汇总服务自己判断「收齐」 |
| D16 | 收款服务要发版 | W1.3 与 near-pay 一起上 166；收款服务负责人确认 `collect` 加字段 |
| D17（实施中发现） | 外部记账的 `orderNo` 只能填商城子单 | 加独立列 `biz_no`（V641）；W1.4 订金关联预约号也用这一列 |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 |
|---|---|
| AC1、AC5、AC8 | `BizReceivableFlowTest#splitPaymentAddsUpAndRefundReopensTheGap`（扫码 300 + 现金 200 = 500；待付链接列出不计；不带单号的链接仍写 `BZC…`） |
| AC2 | 同上（退 100 后已收 400、还差 100） |
| AC3 | `BizReceivableFlowTest#offlineVoidAndCorrectionAreHonoured`（作废不计；更正 200→250 后多收 50） |
| AC4、AC6 | `BizReceivableFlowTest#storedValueCountsCommittedOnlyAndForeignCurrencyIsAnAnomaly` |
| AC7 | `BizReceivableFlowTest#scopedAndValidated`；`BizEndpointPermTest`；`StoreScopedDailyRoutesFlowTest#foodPluginRoutesPassAdmission` |
| 跨服务 | `PayBizInternalApiRoundTripTest#bizReceiptsCrossTheBoundary`、`#storeAndOperatorCrossTheBoundary`（`bizNo` 过边界） |
| 回归 | `BizOfflineRecordFlowTest`（30）、`BizPaymentLinkFlowTest`（7）、`BizPayFlowTest`（7）、`SchemaGeneratorTest`、`SchemaDriftTest` |
