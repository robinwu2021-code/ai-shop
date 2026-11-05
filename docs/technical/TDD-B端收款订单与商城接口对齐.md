# TDD-B端收款、业务订单与商城接口对齐

状态：P1 已实现（2026-11-05）；P2–P4 草稿
档位：2（跨端；新表族：门店用量余额；新权限码）
关联需求与原型：
- 门店订单原型 v6：`ai-shop-bapp-mall/docs/prototypes/core-pages/v6-store-orders.html`（发布版：https://claude.ai/artifact/TZaqctJv2df6VCVj3fcqeN）
- 商家商城原型 v6：`ai-shop-bapp-mall/docs/prototypes/core-pages/v6-merchant-mall.html`（发布版：https://claude.ai/artifact/Pke7cuKsQrvrY1TQTBNEsg）
- PRD：`ai-shop-bapp-mall/docs/requirements/PRD-台湾一期注册开店Plan套餐商城与支付开通-V3.md` §4
- 本期不做：`门店收款到账通知与语音播报需求.md`（只记需求）

分工：本 TDD 定服务端契约并由本会话实现；Flutter 商户端由「AI Shop 开发方案制定」会话按原型和本契约开发。

创建：2026-11-05

---

## §0 对账一 · 需求 → 设计

原型屏号即 AC 编号的来源。门店底部 5 个 Tab「首页 / 收款 / 订单 / 商城 / 我的」已定。

### A. 收款（支付订单）

| AC | 需求（一句话） | 落点 |
|---|---|---|
| A1 | 收款列表只放经过支付渠道的钱：收款码、POS 刷卡、业务订单线上支付；现金、储值不进（Q01） | 新 `GET /biz/payment/orders`，数据源 `pay_order`（排除无渠道的记录） |
| A2 | 按日期分组；快捷日期与自定义区间；按来源 / 渠道 / 状态 / 金额区间筛选；按金额、交易号、付款人搜索（Q01、Q02） | 同上，查询参数见 §2 |
| A3 | 时段汇总：收款、退款、净收款；按渠道的笔数与金额；手续费（预估）；预计入账与日期（Q03） | 新 `GET /biz/payment/orders/summary` |
| A4 | 门店未开通收款时给开通入口（Q04） | 新 `GET /biz/payment/collect-status` → `{enabled, channels}`（门店上下文里没有支付就绪字段，实现时发现） |
| A5 | 本店收款码：查看、保存、打印（Q05） | 复用 `GET/POST /biz/payment/qr-code`（返回 url，客户端画码） |
| A6 | 顾客说「我付了」：按「金额 + 最近 10 分钟 / 1 小时 / 今天」或「交易号后 6 位」查找（Q06、Q07） | 新 `GET /biz/payment/orders/lookup` |
| A7 | 支付详情：金额与入账（手续费、预计入账、结算状态与账户）、付款（渠道、付款人脱敏 / 卡号末四码、发卡行、授权码）、单号（支付订单号、渠道交易号）、来源（收款码 / 终端 / 收银员 / 业务订单号）、退款记录、操作记录（Q08–Q10） | 新 `GET /biz/payment/orders/{payOrderNo}` |
| A8 | 失败写原因并说明未扣款；待确认不显示成败，可「立即查询结果」（Q11、Q12） | 详情 `failure{code,message}`；复用现有渠道查单，新 `POST /biz/payment/orders/{payOrderNo}/query` |
| A9 | 退款：可退金额、全额 / 部分、原因；超过门槛需店长确认（Q13） | 复用 `POST /biz/payment/refund`；门槛走现有 `pay_refund_review_rule`（见 §4） |

### B. 订单（业务订单）

| AC | 需求 | 落点 |
|---|---|---|
| B1 | 零售商品订单列表：只列本店零售订单，按状态筛选，付款与履约两个标签（R01） | `GET /biz/order` 加 `caller=RETAIL` 过滤 |
| B2 | 零售订单完整详情：顾客（会员、电话、地址、备注）、商品明细、金额明细（折扣、运费、应付、实付、币种）、付款列表（方式、金额、支付订单号，可跳收款详情）、履约时间线、发票、订单号与来源、操作记录（R02） | 扩展 `OrderVO`（§2 字段表） |
| B3 | 发货、售后、同意退款（R03–R05） | 复用 `/biz/order/{no}/ship*`、`/biz/after-sale/*` |
| B4 | 餐饮点餐列表与桌台订单完整详情（含逐道菜状态、退菜划线、服务费、分开付、操作记录）（F01–F07） | **分期**：插件有 check / batch / kitchen / settlement，但没有带金额的汇总视图，也没有 B 端结账、现金、分开付接口（§1） |
| B5 | 美业预约列表与完整服务单（执行人、加项、储值抵扣、业绩）（B01–B05） | **分期**：插件声明 `OUT_OF_SERVICE`，业绩与储值抵扣未实现（§1） |

### C. 商城

| AC | 需求 | 落点 |
|---|---|---|
| C1 | 商城首页与软件 / 硬件 / 套餐分类（S01–S04） | 复用 `GET /biz/stores/{storeNo}/mall/products`；新增返回 `categoryCode`、`media`、`specAttributes` |
| C2 | 硬件详情含规格；有规格的商品先选规格（H01、H02） | `mall_sku_version.spec_attributes`（新列）；同一商品的不同规格仍是不同 SKU |
| C3 | 确认订单：数量、收货地址（默认门店地址）、银行转账（H03、K02） | 复用 `/mall/offline/quotes` → `/mall/orders` → `/offline/orders/{no}/instructions` → `/claims` |
| C4 | 套餐组件逐项标「软件 / 硬件 / 用量」及交付方式（K01、K03） | `ComponentKind` 增加 `USAGE`；SKU 组件带 `usageCode`、`unitCount` |
| C5 | 采购订单列表与详情：付款、软件生效、硬件包裹、用量入账分块，各自状态（O01、O02） | 复用 `GET /mall/orders[/{no}]`；`MallOrderDetailView` 增加逐组件交付状态 |
| C6 | 包裹物流（O03） | 复用 shipments（承运商、运单号、数量）；**不接物流轨迹**，只显示运单号与状态 |
| C7 | 按组件申请退款（O04） | 复用 `/refund-previews`、`/refunds`；扩展支持硬件组件与数量（人工审核） |
| C8 | 「我的 › 用量」：AI token、短信余额与入账 / 消耗明细（O05） | 新表 `mch_usage_account`、`mch_usage_ledger`；新 `GET /biz/stores/{storeNo}/usage` |

**孤立项**
- 没落点的 AC：B4、B5 只有分期说明，本期不给接口（需你确认）。LINE Pay、蓝新信用卡在 pay 里是「已建渠道、未接」，街口支付完全没有：A 部分接口按渠道无关设计，真实渠道接入不在本 TDD。
- 挂不上 AC 的设计：无。

---

## §1 现状与影响面

### 收款（pay）

- **有**：
  - 表：`pay_order`（`store_id`、`biz_type`、`source_type`、`operator_no`、金额 / 已付 / 已退、状态、`payer_masked`），`stl_payment`（逐次渠道尝试：`out_trade_no`、`trade_no`、`channel_fee_minor`），`pay_refund`，`pay_qr_code`（店码，`biz_type='CHECKOUT_QR'`、`biz_no=qr_code`），`pay_terminal`，`pay_merchant.settle_cycle`。
  - 商户端接口：
    - `/biz/payment/statement`（单日，上限 1000 条，不分页）
    - `/biz/payment/refund`（最近 50 条）
    - `POST /biz/payment/refund`
    - `/biz/payment/qr-code`
  - 运营端接口：`/ops/payments/orders` 与详情 `OrderDetail{order, attempts, refunds, vouchers, notifies, exceptions}` 已有现成形状，可复用其查询服务 `PayOpsQueryService`。
- **缺**：
  - 商户端分页筛选列表、商户端详情、汇总、查找。
  - 卡号末四码、发卡行、授权码无处存。
  - 二维码、POS、聚合码付款时 `payer_masked` 为空（只有开放平台路径会写）。
  - 手续费只在对账后才有实收值，预计入账日期没有存。
  - `mch:pay:*` 默认只给 OWNER。

### 零售订单（shop-core trade）

- **有**：`BizOrderController` 全套（列表、详情、发货、改履约、确认线下收款、轨迹）；`BizAfterSaleController`；`ord_status_log`；`ord_invoice_request`。
- **缺**：
  - `OrderVO` 不出 `remark`、付款列表、`feeMinor`。
  - 时间线固定为空。
  - 币种写死 `CNY`，实付取的是应付。
  - 列表不按 `caller` 过滤，餐饮、美业的核心订单会混进零售列表。

### 餐饮（`backend-plugins/shop-industry-food`）

- 有 check / batch / kitchen task / settlement / 退菜 / 加菜（新 batch）。
- 但 check 和 batch 列表都不带金额，金额只在 batch 详情和 settlement 里。
- B 端不能结账，现金、分开付、储值都没有接口（`fnb_cash_*` 表没有 controller）。
- 外送平台单在 commerce-integration 插件里（`STORE_ADMIN`）。

### 美业（`backend-plugins/shop-industry-beauty`）

- README 声明 `OUT_OF_SERVICE`。
- 预约列表不带顾客和金额，没有工单列表，业绩 / 提成未实现。
- 储值不能在工单结账里抵扣。

### 商城（`backend/commerce/*`）

- 方案、硬件、套餐、报价、下单、银行转账申报、运营核款、收货地址、包裹（人工登记运单号）、退款（仅数字组件）全有。
- `codex/mall-hardware-without-inventory`（worktree `ai-shop-mallhw`）在此基础上加了：
  - b140d7c32：硬件不接库存（untracked）
  - 3cc3dec27：申报备注与凭证给运营看
  - 3c5743c66：uni-app 商城页面
- **缺**：规格属性、逐组件交付状态、用量组件与门店用量余额、硬件退款。

### 明确不受影响

- C 端结账 H5（`/checkout/v1/*`）。
- 运营端支付页面（只复用其查询服务，不改接口）。
- 渠道回调。
- 结算模块 `/biz/settle/*`。
- 餐饮和美业插件代码（本期不改）。

---

## §2 方案

### 分期

| 期 | 内容 | 依赖 |
|---|---|---|
| **P1** | 收款：A1–A9 | 无；先做（用户要求「订单首先要展示支付订单」） |
| **P2** | 零售订单：B1–B3 | 无 |
| **P3** | 商城：C1–C8 | 是否并入 `codex/mall-hardware-without-inventory` 的后端提交：P3 开工前再问（2026-11-05 用户决定） |
| P4 | 餐饮、美业订单视图 | 插件补齐 B 端结账与汇总视图；另立 TDD |

### 契约变更

**端点**

P1：
- 新增 `GET /biz/payment/orders`
- 新增 `GET /biz/payment/orders/summary`
- 新增 `GET /biz/payment/orders/lookup`
- 新增 `GET /biz/payment/orders/{payOrderNo}`
- 新增 `POST /biz/payment/orders/{payOrderNo}/query`

P2：
- 修改 `GET /biz/order`：加参数 `caller`
- 修改 `GET /biz/order/{subOrderNo}`：返回扩字段

P3：
- 修改 `GET /biz/stores/{storeNo}/mall/products`：扩字段
- 修改 `GET /biz/stores/{storeNo}/mall/orders/{no}`：逐组件状态
- 修改 `POST /mall/refund-previews|refunds`：支持硬件组件
- 新增 `GET /biz/stores/{storeNo}/usage`

**库表**（版本号实现时按当时最新取）

- shop-app：`stl_payment` 加 `card_brand`、`card_last4`、`issuer_name`、`auth_code`、`payer_masked`（均可空）。
- shop-app：`pay_order` 加索引 `(store_id, succeeded_at)`。
- commerce：`mall_sku_version` 加 `spec_attributes JSON`。
- commerce：`mall_sku_component` 允许 `kind='USAGE'`，并加 `usage_code`。
- commerce：新表 `mch_usage_account(store_no, usage_code, balance_units, expires_at…)`、`mch_usage_ledger(entry_no, store_no, usage_code, delta_units, source_type, source_ref, created_at)`。

**权限码**

- 新增 `mch:pay:order:view`（看收款列表和详情），默认给 OWNER、MANAGER、CLERK（已定：店员可看列表与详情）。
- 汇总里的手续费和预计入账另需 `mch:pay:statement`。
- 退款仍是 `mch:pay:refund`。
- CLERK 不给 `mch:pay:statement`、`mch:pay:refund`：看不到汇总与手续费，不能退款（已定）。

**i18n**

- 后端新增错误码文案（三语）：`PAY_ORDER_NOT_FOUND`、`PAY_LOOKUP_RANGE_INVALID`、`USAGE_CODE_UNKNOWN`。

**配置**

- `shop.pay.fee.estimate-rate-bps`：未对账前用于估算手续费的费率，按渠道配置。

### P1 关键接口

```
GET /biz/payment/orders
  头：X-Store-No
  参数：from, to（门店时区日期，默认今天）
        source[]=QR_CODE|POS|ORDER|COLLECT
        channel[]
        status[]=SUCCEEDED|PARTIALLY_REFUNDED|REFUNDED|FAILED|PENDING
        amountMin, amountMax（最小单位）
        q（金额 / 支付订单号 / 交易号片段 / 付款人）
        cursor, size（≤50）
  返回：{ items: PaymentRow[], nextCursor }

PaymentRow {
  payOrderNo, source, channel, method, currency,
  amountMinor, refundedMinor,
  status,                // 视图状态，见下
  payerMasked?, cardLast4?, terminalLabel?,
  bizRef?: { kind, no }, // 仅 source=ORDER
  succeededAt?, createdAt
}
```

- **视图状态映射**：
  - `SUCCEEDED` → SUCCEEDED
  - `PARTIALLY_REFUNDED` → PARTIALLY_REFUNDED
  - `REFUNDED` → REFUNDED
  - `FAILED`、`CLOSED` 且曾经发起过支付 → FAILED
  - `AWAITING_PAYMENT`、`AWAITING_CONFIRM`、`AUTHORIZED` → PENDING
  - 从未有过渠道尝试的 `CREATED`、`AWAITING_PAYMENT` 不进列表（顾客只打开了页面，没有付）。
- **来源映射**：
  - `biz_type=CHECKOUT_QR` → QR_CODE
  - `source_type=TERMINAL` 或 `biz_type=IN_STORE` → POS
  - `BIZ_COLLECT` → COLLECT
  - `MALL_ORDER`、`FOOD_CHECK` 及其他业务单 → ORDER
  - `PARTNER` 不进本列表（开放平台第三方的单）。

```
GET /biz/payment/orders/summary?from&to
  → { currency,
      collected: {count, amountMinor},
      refunded: {count, amountMinor},
      netMinor,
      byChannel: [{channel, count, amountMinor}],
      feeMinor, feeEstimated: bool,
      expectedSettlement: {amountMinor, date} }

GET /biz/payment/orders/lookup?amountMinor&within=10m|1h|today
GET /biz/payment/orders/lookup?tradeNoSuffix=192811   （≥6 位，只查今天和昨天）
  → { matches: PaymentRow[], pendingNearby: PaymentRow[] }

GET /biz/payment/orders/{payOrderNo}
  → { row: PaymentRow,
      amounts: { paidMinor, refundedMinor, refundableMinor, feeMinor, feeEstimated, expectedNetMinor },
      settlement: { status: PENDING|SETTLED, expectedDate?, accountMasked? },
      payer: { channel, method, payerMasked?, cardBrand?, cardLast4?, issuer?, authCode? },
      refs: { payOrderNo, channelTradeNo? },
      source: { kind, qrLabel?, tableRef?, terminalSn?, cashierName?, bizRef? },
      failure?: { code, message },
      refunds: [{ payRefundNo, amountMinor, reason, requestedBy, status, createdAt, succeededAt? }],
      timeline: [{ at, text }] }
```

- 手续费：已对账用 `stl_payment.channel_fee_minor`，`feeEstimated=false`；否则按配置费率估算，`feeEstimated=true`。
- 预计入账日：`succeeded_at` 的门店业务日加上 `pay_merchant.settle_cycle`（T1 / T2 / WEEKLY）。

### 模块设计（P1）

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `pay/pay-domain/.../acquiring/service/MerchantPaymentQueryService.java` + impl | 列表、汇总、查找、详情；查询条件在 SQL 里，按门店隔离 |
| 修改 | `pay/pay-domain/.../pay/biz/PayBizFacade.java` | 暴露上面的四个查询 |
| 修改 | `shop-app/.../portal/biz/pay/BizPaymentController.java` | 5 个新端点（shop-app 是商户端统一入口） |
| 修改 | `pay/pay-svc/.../InternalPayBizEndpoint.java` | 独立部署的镜像端点，与 shop-app 一致 |
| 修改 | 渠道成功回写（`PaymentOutcomeServiceImpl`） | 渠道回执里有卡号末四码、发卡行、授权码、付款人时写入 `stl_payment` 新列 |
| 新增 | shop-app 迁移 | `stl_payment` 加列，`pay_order` 加索引 |
| 修改 | `BizPerms.java` 及默认角色授权 | `mch:pay:order:view` |
| 修改 | `docs/api/openapi-b.yaml`、b-app `endpoints.ts`（若闸门要求） | 契约登记 |

---

## §3 选型

| 问题 | 方案 | 结论 |
|---|---|---|
| 收款列表的数据源 | A. `pay_order` + 最近一次成功的 `stl_payment` | ✅ 一笔顾客付款对应一行，状态和金额都已经在 `pay_order` 上 |
| | B. `pay_ledger_entry` 记账流水 | ❌ 流水是记账视角，一笔会拆成多条分录，失败和待确认的单也不在里面 |
| 分页 | A. 游标（`succeeded_at` / `created_at` + id） | ✅ 新付款不断插到最前面时，翻页不会重复也不会漏 |
| | B. 页码 | ❌ |
| 用量余额放哪 | A. commerce 模块新表 | ✅ 交付发生在 commerce 履约里，入账和购买放在同一个事务 |
| | B. 放进 entitlement | ❌ entitlement 管的是消费者权益计数，不是门店资产 |

不可逆的部分是用量余额表族，实现 P3 前另开 ADR。

---

## §4 风险与待定

| 风险 / 待定 | 影响 | 缓解 / 建议 |
|---|---|---|
| ① LINE Pay、蓝新未接，街口支付没有 | 台湾门店的真实收款进不来，列表只能用测试渠道验证 | 接口与渠道无关；真实渠道接入另立任务（pay 项目） |
| ② 卡号末四码、授权码、付款人依赖渠道回执 | 老数据和不回传这些字段的渠道显示为空 | 字段全部可空，客户端缺省只显示渠道名 |
| ③ CLERK 能否看收款列表 | 权限码默认授权 | **已定**：店员可看列表和详情，不看汇总与手续费，不退款 |
| ④ 退款需店长确认的门槛（原型 NT$1,000） | 现有门槛规则是「运营双人复核」，不是「店长确认」 | P1 只做退款时提示超限；B 端店长确认流程另议 |
| ⑤ 商城依赖 `codex/mall-hardware-without-inventory` | P3 必须基于它 | 已定：先不并，P3 开工前再问 |
| ⑥ 餐饮、美业 | 原型 F、B 部分本期没有接口 | P4 另立 TDD；Flutter 端先用假数据 |
| ⑦ 列表性能 | 单店每天几千笔时 | `(store_id, succeeded_at)` 索引 + 游标分页；`q` 搜索限定在所选日期区间内 |

---

## §5 对账三 · 实现 → 需求（测试）

P1 测试：`backend/shop-app/src/test/java/ai/neargo/shop/scenario/BizPaymentOrdersFlowTest.java`。
它与 `BizPayFlowTest` 用同一套开店方式（入驻审核 → 店主令牌 → TEST 收款商户），付款走真实收银台加 `SettlePort.settlePayment` 入账。

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| A1 | `#listShowsPaidOnlyAndIsStoreScoped`（付了的进、只开单没付的不进、别家看不到、未登录 401） | ✅ | 去掉 `visible()` 里「至少有一次渠道尝试」的条件 → 变红 ✅ |
| A2 | `#filtersAndCursor`（来源、状态、金额区间；翻页期间新进一笔，两页合起来不重不漏） | ✅ | |
| A3 | `#summary` | ✅ | |
| A6 | `#lookup`（金额 + 10 分钟、交易号后 6 位、少于 6 位拒绝） | ✅ | |
| A7 | `#detail`（金额、渠道交易号、来源、结算账户与预计日、退款记录、时间线；别家的单查不到） | ✅ | |
| A8 | `#queryRefreshesPending` | ✅ | |
| A4 | `#collectStatus`（绑定 = 已开通并列渠道；解绑 = 未开通） | ✅ | |
| 权限 | `BizEndpointPermTest` 新增 5 条映射；`everyBizEndpointHasADecision` 里本次端点不再出现 | ✅ | |
| 回归 | `BizPayFlowTest` 7/7；pay-svc `PayOpsInternalApiRoundTripTest` 3/3 | ✅ | |

```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.scenario.BizPaymentOrdersFlowTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.scenario.BizPayFlowTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.pay.svc.PayOpsInternalApiRoundTripTest
消融：Tests run: 1, Failures: 1 -- BizPaymentOrdersFlowTest#listShowsPaidOnlyAndIsStoreScoped
```

**存量红灯，与本次无关，未改**（改动前同样红）：
- `ArchitectureTest` 5 项：库存与餐饮合并带进来的 Port 包位置、全限定名、定时任务锁、Service 命名、portal 依赖 JsonMapper。
- `BizEndpointPermTest#everyBizEndpointHasADecision` 剩 15 个端点：餐饮库存投影、美业排班与资源、服务单报价与手工收款、门店销售目录。本次只补了本会话早先加的 `/biz/auth/email-otp/send`。
- `check-app-backend-orphan` 本来就有别人的新增，见下文闸门说明。

## §6 对账二 · 设计 → 实现

| 动作 | 路径 |
|---|---|
| 新增 | `pay/pay-domain/.../acquiring/service/MerchantPaymentQueryService.java`、`impl/MerchantPaymentQueryServiceImpl.java` |
| 修改 | `pay/pay-domain/.../pay/biz/PayBizFacade.java`、`pay/pay-domain/.../pay/client/PayBizInternalApi.java` |
| 修改 | `pay/pay-svc/.../svc/InternalPayBizEndpoint.java` |
| 修改 | `shop-app/.../payclient/BizPaymentAppService.java`、`impl/BizPaymentAppServiceImpl.java`、`impl/RemoteBizPaymentAppService.java` |
| 修改 | `shop-app/.../portal/biz/pay/BizPaymentController.java`、`shop-base-auth/.../auth/BizPerms.java` |
| 新增 / 修改 | 测试 `BizPaymentOrdersFlowTest`；`BizEndpointPermTest` 加映射；`known-app-backend-orphan.txt` 加 6 行（出口在 Flutter） |

**偏差说明**

1. **没有加库表列**。`stl_payment` 的卡号末四码、发卡行、授权码、付款人这几列先不加：现有渠道没有一个会写它们，LINE Pay 和蓝新都还没接。详情里 `payer.cardBrand / cardLast4 / issuer / authCode` 先固定返回空，等真实渠道接入时和回写一起加。`pay_order(store_id, created_at)` 索引已经存在，不需要另加 `(store_id, succeeded_at)`。
2. **没有新增错误码**。参数不合法用 `BAD_REQUEST`，不是本店的单用 `NOT_FOUND`，所以也不需要三语 i18n。
3. **时间口径**。列表按创建时间分日、排序、出游标（单子一创建就有位置，翻页稳定）；汇总按成功时间落在区间内统计，与原有收款日流水的口径一致。
4. **收银员**。来源里只给 `operatorNo`，名字由端上用现有员工接口换。
5. **时间线给事件码，不给文案**：`CREATED / PAID / FAILED / CLOSED / REFUND_REQUESTED / REFUND_SUCCEEDED`，端上按码出文案。
6. **独立部署形态的列表走 POST**：`/internal/pay/v1/biz/stores/{storeNo}/payment-orders/search`，请求体带条件。多值条件原样传，不依赖查询串里的重复参数。
7. **结算状态的判定**。只有这张单进过 `SETTLEMENT` 凭证才算「已结算」，否则是「待结算」。预计入账日 = 成功业务日 + `pay_merchant.settle_cycle`（T1 / T2 / WEEKLY，没配按 T1）。
9. **应 Flutter 端字段清单补的三项**（2026-11-05）：`GET /biz/payment/collect-status`；详情 `refs.channelMerchantNo`（子商户号优先）；`amounts.feeRateBps`。Flutter 端要的「查询次数 / 下次查询时间」「刷卡方式（感应 / 插卡）」「收银员姓名」服务端目前没有记录，暂不提供。
8. **手续费估算费率**。`shop.pay.fee.estimate-rate-bps` 默认 0，未对账的单手续费显示 0 并标「预估」。台湾渠道的实际费率定下来后，在配置里设。
