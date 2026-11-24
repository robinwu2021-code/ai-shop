# TDD-统一订单与开票资料（POS 建单 · 待补资料 · 付清开票）

状态：已确认（2026-11-24，用户：「按 B 开始 P1」）；P1、P2 已实现并发布到 v2（§7）；P3 未开始，设计移至 [TDD-手持收银机后端](./TDD-手持收银机后端.md) W1（含会员载具表的更正：实际为 `ivc_user_carrier`）
档位：2 · 依据：[PRD-统一订单与开票资料](../requirements/PRD-统一订单与开票资料.md) · 决策：[ADR-065](./ADR/ADR-065-门店零售单与订单行开票资料.md)
关联：[TDD-发票模块](./TDD-发票模块.md) §9 / §24，[TDD-多行业统一业务架构-零售餐饮美业](./TDD-多行业统一业务架构-零售餐饮美业.md)，[TDD-统一试算引擎-初始规则与结账税费](./TDD-统一试算引擎-初始规则与结账税费.md)
创建：2026-11-24 · 最后更新：2026-11-24（P2 实现）

用户已定（2026-11-24）：**一次做全**；混合税别整单优惠**按金额比例自动分摊**；`PAY_ORDER` / `OFFLINE_RECORD` 两种来源**先保留**；订金 / 尾款**付清后开一张**。

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 店员给散客建零售单：SKU 行 + 输入金额行 + 整单优惠，幂等（`Idempotency-Key` + `clientOrderNo`） | `POST /biz/store-orders`；`StoreOrderService#create` |
| AC2 | SKU 行的品名、课税别按后端商品资料冻结；成交价 ≠ 商品价时按改价权限判 | 读 `prd_sku` + `prd_sku_pricing_policy.tax_class` 冻结到 `ord_item`；权限码 `biz:order:reprice` |
| AC3 | 输入金额行品名 / 课税别可空 → 「待补」；可选门店常用品项带出 | `ord_item.invoice_name` / `tax_class` 允许 NULL；`ivc_store_item` |
| AC4 | 返回与详情都带 `invoiceReadiness {state, missing[], buyerSet}` | `InvoiceReadinessService`；`OrderVO.invoiceReadiness` |
| AC5 | `PUT …/invoice-data` 只能补空字段（行品名、课税别、买受人），留稽核，开票后锁死 | `StoreOrderService#fillInvoiceData` + `ord_invoice_data_fill` |
| AC6 | 课税别边界：自订行默认门店默认税别；零税率 / 免税需权限或只能从常用品项选 | `ivc_store_setting.default_tax_class`；权限码 `biz:invoice:tax-override` |
| AC7 | 现金记录（`orderNo`）、收款码 / 付款链接（`bizNo`）、刷卡挂订单；**收款加总 = 总额**时订单转已付款并发 `ORDER_PAID` | `StoreOrderSettlementService`：现金记录写入 / 作废 / 更正同一事务内判；收款码按支付单号补单（内嵌）或已验签收款推送（独立形态）挂单（§2「P2 契约」） |
| AC8 | 付清且 READY 且门店开票时机 `ON_PAID` → 自动开票 | 复用 `InvoiceAutoIssueConsumer`（`ORDER_PAID`）：门店零售单按 `ord_order.invoice_timing` → 门店设定判 |
| AC9 | 开票只送订单号；不是 READY 回 `26002` 并附 `missing` | `SubOrderInvoiceSourcePortImpl` 读新字段；`BizException` data 带 missing |
| AC10 | 混合税别的整单优惠按金额比例分摊到税别（最大余数法），分不了才回待补 | `SubOrderInvoiceSourcePortImpl` + `LineShares` 同口径分摊 |
| AC11 | 门店设定：默认课税别、常用品项（品名 + 课税别）、开票时机（付清自动开 / 手动 / 稍后） | `GET/PUT /biz/invoice-setup/store-settings`、`/store-items`（`ivc_store_setting` / `ivc_store_item`，`db/invoice` V15） |
| AC12 | 会员结账自动带会员载具 | 建单 `member.memberNo` → 买受人取会员默认载具（`ivc_buyer_preference`） |
| AC13 | 下单扣库存 | 复用库存端口（`StockPort` 预留 / 扣减），付款失效回补 |
| AC14 | 退款挂订单：开票后部分退款开折让、全额按规则作废或折让 | 复用发票折让 / 作废；POS 退款入口挂订单号 |
| AC15 | 离线现金单补传：同 `clientOrderNo` 幂等，补传后照常判断 | `clientOrderNo` 唯一键（门店内） |
| AC16 | 分次付款（现金 + 收款码）加总 = 总额才付清；订金 / 尾款付清后开一张 | AC7 同一聚合 |

**孤立项**：无设计缺口；`PAY_ORDER` / `OFFLINE_RECORD`（§24）保留但不在本方案路径上，挂不上 AC，按用户决定暂留。

## §1 现状与影响面（2026-11-24 读 `origin/main` adc154670）

- **没有「店员给散客建零售单」的路径**。现有写 `ord_order` 的有：C 端结账（`OrderServiceImpl#doCreate`）、美业柜台单（`CounterOrderPlacementServiceImpl`，caller 写死 `BEAUTY`、只有到店核销服务）、行业单（`IndustryOrderServiceImpl#placeForStore`，走统一试算报价 `ord_price_quote_line`，按 SKU 计价）、渠道单（`IndustryChannelOrderCommitter`，`CHANNEL`）。
- **库表约束挡路**：`ck_counter_order_party` / `ck_counter_sub_party`（V602 → V615）只允许 USER（须 `user_no`）、GUEST（仅美业线下）、CHANNEL（caller FOOD / RETAIL）。POS 散客单需要放开一种组合 —— **改正式库上热表的 CHECK，要临时授 ALTER**。
- **收款与订单没打通**：`pay_order.biz_no`、`ord_offline_record.order_no` 只记号码；没有任何路径因收款把 `ord_order` 置已付款（现金记录按决策 D3 明确不碰订单）。`markPaid` 要求「已确认的支付回执」，PYO 单号被显式跳过。
- **课税别**：来源是 `prd_sku_pricing_policy.tax_class`（门店 × SKU），只有行业报价会冻结进 `ord_price_quote_line`；`ord_item` 没有税别列。发票来源（`SubOrderInvoiceSourcePortImpl`）没有行业绑定就当应税；混合税别 + 整单优惠回 NOT_READY。
- **自动开票**只听 `ORDER_PAID`（只有 `markPaidSource` 发）。
- **会被改到的**：`ord_item`（加列）、`ord_order` / `ord_sub_order` 约束、`SubOrderInvoiceSourcePortImpl`、`OfflineRecordServiceImpl`（挂单后要触发结算判断）、收款成功事件消费、B 端订单 VO。
- **明确不受影响**：C 端结账、行业单、渠道单、美业柜台单的写入路径与约束原有组合（只新增一种组合，不改原有分支）。

## §2 方案

### 契约变更
- **端点（新增）**：
  - `POST /biz/store-orders`（头 `Idempotency-Key`，体 `clientOrderNo`…，见下）→ `StoreOrderView`
  - `GET /biz/store-orders/{orderNo}` → `StoreOrderView`（含 `invoiceReadiness`）
  - `PUT /biz/store-orders/{orderNo}/invoice-data`（头 `Idempotency-Key`）→ `StoreOrderView`
  - `POST /biz/store-orders/{orderNo}/cancel`（未付款可取消，回补库存）
  - `GET/PUT /biz/invoice-setup/store-settings`（默认课税别、开票时机）；`GET/POST/PUT/DELETE /biz/invoice-setup/store-items`（常用品项）
- **端点（行为变化）**：`POST /biz/payment/offline-records` 的 `orderNo` 指向本方案订单时参与付清判断；`POST /biz/payment/collection` 的 `bizNo` 同；`POST /biz/invoices` 不 READY 时 26002 的 `data.missing`。
- **库表 / 迁移**（`db/migration` 下一号起，另 `db/invoice` V15）：
  - `ord_item` 加 `line_kind`（`SKU`/`CUSTOM`/`ADJUSTMENT`）、`tax_class`、`invoice_name`、`invoice_data_source`（`GOODS`/`STORE_ITEM`/`FILLED`）
  - `ord_order` 加 `client_order_no` + 唯一键（`store_no, client_order_no`）、`channel`
  - 约束 `ck_counter_order_party` / `ck_counter_sub_party` / `ck_order_admission_kind` 新增一支：`customer_kind='WALK_IN'`、`admission_kind='STORE_SALE'`、`caller='POS'`、无 user/guest、entity/store/operation 必填
  - 新表 `ord_invoice_data_fill`（补资料稽核）、`ivc_store_setting`、`ivc_store_item`
- **权限码**：`biz:order:create`（建单）、`biz:order:reprice`（改价）、`biz:invoice:tax-override`（非常用品项选零税率 / 免税）；沿用 `biz:invoice:setup`。
- **i18n**：错误码文案（门店散客单状态、补资料冲突、已开票锁定、税别越权）。
- **配置项**：无新开关（随门店开票时机设置生效）。

### 关键数据结构（与 POS 需求同形，字段名以此为准）
```json
POST /biz/store-orders
{ "clientOrderNo": "POS-…", "channel": "POS", "currency": "TWD",
  "lines": [ {"kind":"SKU","skuNo":"…","quantity":1,"unitPriceMinor":2500},
             {"kind":"CUSTOM","quantity":1,"unitPriceMinor":1000,"name":null,"taxType":null,"storeItemNo":null} ],
  "adjustments": [ {"kind":"DISCOUNT","name":"整單 9 折","amountMinor":-350} ],
  "member": {"memberNo":"…"},
  "invoice": {"buyer": {"type":"CARRIER","carrierType":"3J0002","carrierId":"…"}, "timing": null} }
→ { "orderNo":"OD…", "subOrderNo":"OS…", "status":"AWAITING_PAYMENT", "totalMinor":3150, "paidMinor":0,
    "lines":[{"lineNo":1,"name":"…","taxType":"TAXABLE","amountMinor":2500,"frozen":true}, …],
    "invoiceReadiness": {"state":"NEEDS_INFO","missing":[{"lineNo":2,"fields":["name","taxType"]}],"buyerSet":true,"invoiceNo":null} }
```
`taxType` 对外用 `TAXABLE / ZERO_RATED / EXEMPT`（与发票明细一致），库内存商品侧 `STANDARD / ZERO_RATED / EXEMPT`。订单号沿用 `OD…` / `OS…`；POS 开票用子单号（`sourceType=SUB_ORDER`）。`PYO…` / `OFR…` 仍是收款单号，通过 `bizNo` / `orderNo` 挂订单，**不回填旧资料**。

### P2 契约（实现后补，2026-11-24）

**新端点**（发票模块 `BizInvoiceStoreSetupController`，门店取 `X-Store-No`；读 `biz:invoice:read`、写 `biz:invoice:setup`，不新增权限码）：

| 端点 | 体 / 返回 |
|---|---|
| `GET /biz/invoice-setup/store-settings` | `{ defaultTaxType: TAXABLE\|ZERO_RATED\|EXEMPT\|null, invoiceTiming: ON_PAID\|MANUAL\|LATER }`；没设过回 `{null, MANUAL}` |
| `PUT /biz/invoice-setup/store-settings` | 同上（`invoiceTiming` 必填；`defaultTaxType=null` 即清空默认） |
| `GET /biz/invoice-setup/store-items` | `[{storeItemNo, name, taxType, sort, enabled}]`，按 `sort`、建立先后，含停用的 |
| `POST /biz/invoice-setup/store-items` | `{name, taxType, sort?, enabled?}` → 一项（`IVS…`）；每店上限 **50**，超了 `26015` |
| `PUT /biz/invoice-setup/store-items/{storeItemNo}` | 整项覆盖；别店 / 不存在 `10404` |
| `DELETE /biz/invoice-setup/store-items/{storeItemNo}` | 删除，幂等；已建的订单行不受影响（建单时已复制品名与税别） |

**行为变化**：

- `POST /biz/store-orders`：自订行 `storeItemNo`（与 `name` / `taxType` 互斥，校验在请求体）→ 复制品项的品名与课税别，`invoice_data_source=STORE_ITEM`，
  零税率 / 免税**不需** `biz:invoice:tax-override`；不存在、别店或停用 → `20026 STORE_ORDER_STORE_ITEM_UNAVAILABLE`。
  自订行没填 `taxType`（也没选品项）→ 取门店 `defaultTaxType`（来源 `STORE_DEFAULT`，同样不需另授权；门店没设仍待补）。
  **SKU 行**的商品没设课税别（无 `prd_sku_pricing_policy.tax_class` 或 OUT_OF_SCOPE）→ 同样取门店 `defaultTaxType`（来源 `STORE_DEFAULT`，
  零税率 / 免税不需另授权；用户 2026-11-24「用门店默认税别」）；门店没设仍待补（NEEDS_INFO，可补）。商品有课税别的不变（`GOODS`）。
  `invoice.timing` 存 `ord_order.invoice_timing`（null = 跟门店设定），响应 `invoiceTiming` 回显。
- `PUT /biz/store-orders/{orderNo}/invoice-data`：行上可带 `storeItemNo`（与 `name` / `taxType` 互斥），只补空字段，零税率 / 免税不需另授权。
- `POST /biz/store-orders/{orderNo}/cancel`：已挂收款（`paidMinor > 0`，部分付款）也回 `20024`，先作废现金记录 / 退收款。
- `StoreSaleView.paidMinor` = 未作废现金记录 + 已成功收款码 / 付款链接（同币别加总）；付清后可大于 `totalMinor`（多收，POS 找零）。
  `status: AWAITING_PAYMENT → PAID`，准备度随之 `NOT_PAID → READY / NEEDS_INFO`。
- `POST /biz/payment/offline-records`（`orderNo` = 门店零售单子单号，**只对 `admission_kind='STORE_SALE'`**）：写入同一事务里锁子单、加总、够了就付清。
  单已付清 / 已取消 → `20024`（不再挂现金，挂上就作废不掉）；记录币别 ≠ 订单币别 → `20030 STORE_ORDER_CURRENCY_MISMATCH`。两种都整笔回滚、记录不落。
  其他订单号照决策 D3「只记、不改订单」。
- `POST /biz/payment/offline-records/{recordNo}/void`、`…/correct`：所挂门店零售单已付清 → `20029 STORE_ORDER_PAYMENT_LOCKED`（退款是 P3）；未付清放行并重算（更正成更大金额可就此付清）。
- `POST /biz/payment/collection`（`bizNo` = 子单号）：支付单成功后挂单。**事实来源**：内嵌形态 = 回调 / 三个对账器补单都经 `OrderServiceImpl#markPaid(PYO…)`，
  原先直接跳过，现在先经新 spi `PaidCollectionPort`（支付域实现，读 `pay_order`：`biz_type=BIZ_COLLECT`、已成功、`paid_minor`、门店号）取事实再挂；
  独立形态（near-pay，v2 现状）= 已验签收款推送 `PaymentCollectionFact` → `MERCHANT_COLLECTION_RECEIVED` → `StoreOrderCollectionConsumer`（`shop.payment-collections.enabled=true` 时装配）。
  金额一律取支付域确认的实收；同一支付单只挂一次（`ord_store_collection` 唯一键）；别店、币别不符、不是门店零售单 → 不挂（记日志）；付清 / 取消后才到的钱照记、不转状态。

**付清**（`StoreOrderSettlementServiceImpl#settle`，子单行锁 + 读已提交）：同币别加总 ≥ `pay_amount` →
`ord_order: WAIT_PAY → PAID`、`paid_at`、`pay_channel`（只有现金 `OFFLINE`；只有一种收款通道 = 该通道；混合 `MIXED`），
`ord_sub_order: WAIT_PAY → COMPLETED`（柜台当面交货，没有备货 / 发货），同一事务写 outbox `ORDER_PAID {orderNo, userNo:null, payAmount, payChannel}`
与 `SUB_ORDER_PAID {subOrderNo, orderNo, entityNo, storeNo, userNo:null, payAmount}`，载荷与 `OrderServiceImpl#markPaidSource` 同形。
不调结算单生成、不扣库存（P3）、不出核销码、不累加收款额度。

**自动开票**（`InvoiceAutoIssueConsumer`，`ORDER_PAID`）：门店零售单的时机 = `ord_order.invoice_timing` ?? 门店 `invoiceTiming`；`ON_PAID` 才开，
买受人用预存的（没有按一般消费者）；不 READY（26002）跳过留给手动开。**不看**主体的「自动开立」开关；其他订单照旧 `issueIfAutomatic`。

**投递在哪个进程**：`ORDER_PAID` 由 `OutboxEventBus` 在事务提交后交给 `OutboxPump`（`@Component`，无 profile，`shop.outbox.pump.enabled` 默认 true，
legacy 模式）或统一 outbox 的 `OutboxRunner`（`OutboxConfiguration` 无 profile）—— 两者都在 **api 进程**里跑，**不需要 worker**。
需要 worker 的只是发票上传（`InvoiceTransmissionJob`，`@Scheduled`），与自动开立无关。所以 v2（只跑 `api,ops`）上 `ON_PAID` 可用，不另做同步开票。

**库表**：`db/migration` **V695**（`ord_order.invoice_timing`、`ord_offline_record.idx_offline_record_order`、新表 `ord_store_collection`）；
`db/invoice` **V15**（`ivc_store_setting`、`ivc_store_item`）。归属：门店设定与常用品项属发票域（回答「这家店开票怎么带默认」，发票模块打包排除时一起不在），
收款挂单属订单域。

**错误码**：`20026` 改义为 `STORE_ORDER_STORE_ITEM_UNAVAILABLE`（P1 未发布，原「未开放」）；新增 `20029 STORE_ORDER_PAYMENT_LOCKED`、
`20030 STORE_ORDER_CURRENCY_MISMATCH`、`26015 INVOICE_STORE_ITEM_LIMIT`（带 `{0}` = 上限）。三语文案齐。

### 实施分期（同一 TDD，按期提交、每期可单独发布）
| 期 | 内容 | AC |
|---|---|---|
| P1 | 迁移（列、约束新支、`client_order_no`）+ 建单（SKU / 自订 / 优惠，冻结品名税别）+ 详情 + 准备度 + 补资料接口 + 发票来源读新列与比例分摊 + 26002 missing | AC1–6, 9, 10, 15 |
| P2 | 收款挂单与付清判断（现金记录、收款码 / 付款链接），发 `ORDER_PAID`；门店设定（默认税别、常用品项、开票时机）+ 自动开票 | AC7, 8, 11, 16 |
| P3 | 会员载具、下单扣库存与取消回补、退款挂订单（折让 / 作废） | AC12–14 |

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A. 建在行业单 + 统一试算报价上（`placeForStore`） | SKU 行的税别、促销、改价全由试算引擎冻结；发票来源已会读报价行税别 | 试算引擎按 SKU 计价，**自订行（无 SKU）进不去**，要改引擎；报价 / 分摊 / 冻结一整套流程对 POS 输入金额过重 | ❌ |
| **B. 新的门店零售单写入（`StoreOrderService`），直接落 `ord_order / ord_sub_order / ord_item`，订单行自带税别与开票品名** | 自订行天然支持；补资料只动订单行；与 C 端 / 行业单 / 美业互不影响；发票来源统一读 `ord_item` | 多一条建单路径；POS 改价 / 优惠由本服务算（不走促销引擎） | ✅ 采用，理由：POS 场景是「店员录入、按成交价收款」，不需要促销引擎；自订行是刚需 |
| C. 每种收款单各写一个发票来源（现 §24） | 改动最小 | 违反「开票资料只来自订单」；订单 / 库存 / 退款缺口仍在 | ❌（用户已改方向，§24 暂留） |

不可逆部分（新的 `customer_kind=WALK_IN` / `admission_kind=STORE_SALE` 组合、`ord_item` 税别列成为发票明细的权威来源）另开 ADR。

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 正式库改热表 CHECK 约束与加列 | 需临时授 ALTER；大表加列可能锁表 | MySQL 9.7 `ALGORITHM=INSTANT` 加可空列；约束改动单独一条迁移；授权后立即收回（同 §23） |
| 收款事件与订单付清并发（现金 + 收款码同时到） | 重复置已付 / 漏发 `ORDER_PAID` | 订单行 `FOR UPDATE` + 幂等置位，事件按订单号去重 |
| 付清判断改了现金记录「不碰订单」的决策 D3 | 现有门店的现金记录行为变化 | 只对本方案订单（`admission_kind=STORE_SALE`）生效，其他订单号仍旧不碰 |
| v2 上没有 worker | 自动开票（outbox 消费）、上传都不跑 | P2 已查明：outbox 投递泵 / 统一 outbox runner 都在 api 进程，自动开立不需要 worker；上传仍需 worker（发票模块 §23 后续） |

## §5 对账三 · 实现 → 需求（测试）

P1（AC1–6、AC9、AC10、AC15）。真库测试 `StoreOrderMysqlTest` 在本机 MySQL 9.7（`ai-shop-mysql97`，3308）建随机库：
从本机开发库 `ai_shop` 只读复制 `ord_order / ord_sub_order / ord_item / ord_industry_order_source / ord_price_quote_line /
prd_sku_pricing_policy / mch_role` 的现行结构（`SHOW CREATE TABLE`，保留 CHECK 原名），再执行 V692 / V693 / V694（V694 执行两次验幂等），跑完删库。
商品快照（`GoodsQueryPort`）与发票域（`InvoicePort`）用替身；课税别走真实 `GoodsPricingPolicyServiceImpl#taxClasses`，发票来源走真实 `SubOrderInvoiceSourcePortImpl`。

| AC | 测试方法 | 跑过 |
|---|---|---|
| AC1 建单（SKU + 自订 + 整单优惠）、落库口径 | `StoreOrderMysqlTest#ac1MixedOrderFreezesSkuDataAndListsExactlyWhatTheCustomLineMisses` | ✅ |
| AC2 SKU 冻结品名 / 税别；未设或 OUT_OF_SCOPE 按待补；改价要权限；别店 SKU 404；合计 < 0；常用品项未开放 | `StoreOrderMysqlTest#ac2SkuWithoutStoreTaxClassIsPendingAndRepriceNeedsPermission` | ✅ |
| AC3 自订行带齐品名 / 税别即冻结（ENTERED） | `StoreOrderMysqlTest#ac3CustomLineWithNameAndTaxTypeIsFrozenAtOnce` | ✅ |
| AC4 返回与详情同一份 `invoiceReadiness` | `#ac1…`（创建与 `get` 两处断言）、`#ac10…`（READY → ISSUED + invoiceNo） | ✅ |
| AC5 补资料：只补空的、逐项稽核、幂等、冻结、开票后锁死 | `StoreOrderMysqlTest#ac5FillCompletesTheOrderButUnpaidStaysNotPaidAndEveryFieldIsAudited`；锁死见 `#ac10…` | ✅ |
| AC6 零税率 / 免税要 `biz:invoice:tax-override`（建单与补资料两处） | `StoreOrderMysqlTest#ac6ZeroRatedOrExemptOnACustomLineNeedsTheOverridePermission` | ✅ |
| AC9 不 READY 时 26002 带 `data.missing`（与准备度同形） | `StoreOrderMysqlTest#ac9NotReadySourceCarriesTheSameMissingListIn26002`；`SubOrderInvoiceSourcePortImplTest#storeOrderWithPendingFieldsReportsWhatIsMissing` | ✅ |
| AC10 混合税别整单金额最大余数法分摊 | `StoreOrderMysqlTest#ac10PaidMixedTaxOrderSplitsTheDiscountByLargestRemainder`（2500 应税 + 1000 零税率 − 333 → −238 / −95）；`SubOrderInvoiceSourcePortImplTest#mixedTaxWholeOrderAmountsAreSplitByTaxTypeWithLargestRemainder`、`#storeOrderLinesUseFrozenInvoiceNameAndTaxClass` | ✅ |
| AC15 同 `clientOrderNo` 幂等（换键补传返回原单；同键换单号 10409） | `StoreOrderMysqlTest#ac15SameClientOrderNoReturnsTheOriginalEvenWithANewKey` | ✅ |
| 取消（仅未付款，可重复） | `StoreOrderMysqlTest#cancelOnlyUnpaidAndIsRepeatable` | ✅ |
| 买受人不合规整单回滚 | `StoreOrderMysqlTest#invalidBuyerRollsBackTheWholeOrder` | ✅ |
| 迁移：WALK_IN 只与 STORE_SALE + client_order_no 共存；V694 幂等 | `StoreOrderMysqlTest#migrationsBindWalkInToStoreSaleAndClientOrderNoAndSeedRoles` | ✅ |
| 路由准入 / 权限码 / 角色 | `StoreScopedDailyRoutesFlowTest#storeOrderRoutesPassAdmission`、`BizEndpointPermTest`、`MerchantRoleFlowTest`、`BizRoleSeedTest` | ✅ |

**红的一次**（2026-11-24）：先改 `SubOrderInvoiceSourcePortImplTest`（AC10 分摊、订单行开票资料、26002 missing），把
`SubOrderInvoiceSourcePortImpl` 换回 `HEAD` 版本跑：`Tests run: 7, Failures: 2, Errors: 1` —— 混合税别那条原实现抛 26002，
`storeOrderLinesUseFrozenInvoiceNameAndTaxClass` 实得 `[SKU 標題|20000|TAXABLE, null|10000|TAXABLE, 折扣|-350|TAXABLE]`。
换回新实现后 `Tests run: 7, Failures: 0`。`StoreOrderMysqlTest` 与服务同批写成，首跑即绿（按 Java 规范 §8.4 不回退实现再造失败）。

**真实输出**：

```
COMMERCE_MYSQL_URL=jdbc:mysql://127.0.0.1:3308/?… mvn -o -pl shop-app -am test \
  -Dtest='ArchitectureTest,BizEndpointPermTest,MerchantRoleFlowTest,SchemaParityTest,SchemaDriftTest,StoreScopedDailyRoutesFlowTest,BackendI18nParityTest,BizRoleSeedTest,InvoiceOptionalTest,*Invoice*,*Order*' \
  -Dsurefire.failIfNoSpecifiedTests=false
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.trade.StoreOrderMysqlTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.invoicebridge.port.SubOrderInvoiceSourcePortImplTest
Tests run: 27, Failures: 0, Errors: 0, Skipped: 1 -- in ai.neargo.shop.arch.ArchitectureTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.BizEndpointPermTest
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.commercebridge.StoreScopedDailyRoutesFlowTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.scenario.MerchantRoleFlowTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.BackendI18nParityTest
Tests run: 1 / 6 / 4 / 1 … SchemaParityTest / SchemaDriftTest / BizRoleSeedTest / InvoiceOptionalTest：0 失败
shop-core 146 · invoice-domain 59 · invoice-channel 58 · shop-app 514（Skipped 6）… 合计 843 条，Failures 0 Errors 0
BUILD SUCCESS
```

### P2（AC6 默认税别部分、AC7、AC8、AC11、AC16）

同一个 `StoreOrderMysqlTest`（本机 MySQL 9.7 随机库）再多复制 `ord_offline_record / ord_offline_record_event / sys_outbox` 三张表、多执行 V695；
现金记录走真实 `OfflineRecordServiceImpl`，事件走真实 `OutboxEventBus`（写 `sys_outbox`），自动开票走真实 `InvoiceAutoIssueConsumer`，
发票域替身的 `issue` 先经真实 `SubOrderInvoiceSourcePortImpl#load`（不 READY 抛 26002）；支付域「已成功支付单」用替身（`PaidCollectionPort`）。
门店设定与常用品项在 `InvoiceServiceMysqlTest`（发票域随机库，Flyway 跑 V1–V15）。

| AC | 测试方法 | 跑过 |
|---|---|---|
| AC7 现金足额 → PAID、`pay_channel=OFFLINE`、子单 COMPLETED、`ORDER_PAID` + `SUB_ORDER_PAID` 各一条、准备度 READY | `StoreOrderMysqlTest#ac7CashForTheFullTotalMarksPaidPublishesOrderPaidAndTurnsReady` | ✅ |
| AC16 现金 400 + 收款码 600：部分付款不转、不发事件、不能取消；够了转 PAID（`MIXED`） | `#ac16SplitCashAndCollectionSettlesOnlyWhenTheSumReachesTheTotal` | ✅ |
| 多收：1200 / 1000 照付清，`paidMinor=1200`；付清后再挂现金 20024，后到的收款码照记不再发事件 | `#overpaymentIsAcceptedAndRecordedAsPaidMinor` | ✅ |
| 作废未付清单的现金 → 重算；更正成足额 → 付清；已付清后作废 / 更正 → 20029 且记录不变 | `#voidingCashOnAnUnpaidOrderRecomputesButOnAPaidOrderIsRejected` | ✅ |
| 币别不同 20030、已取消单 20024，都不落记录 | `#cashInAnotherCurrencyOrOnACancelledOrderIsRejectedAndNothingIsRecorded` | ✅ |
| 收款码按 `bizNo` 挂：别店 / 币别不符 / 不是门店零售单不挂；支付单号补单按支付域事实挂、重复到达只挂一次 | `#collectionAttachesByBizNoOfTheSameStoreOnlyOnceAndViaThePaidPayOrder` | ✅ |
| 并发：现金 600 与收款码 600 同时到（4 轮）只转一次、一组事件；两笔足额现金同时到一笔成功、一笔 20024 不落库 | `#concurrentPaymentsSettleExactlyOnceAndPublishOneEvent` | ✅ |
| D3 回归：非门店零售单的现金记录不改子单状态、不发事件 | `BizOfflineRecordFlowTest#record_with_order_of_another_store_is_not_found`（新增两条断言） | ✅ |
| AC6 自订行没填税别取门店默认（零税率也不需另授权），来源 `STORE_DEFAULT` | `StoreOrderMysqlTest#ac6StoreDefaultTaxTypeFillsACustomLineWithoutOne` | ✅ |
| AC6 SKU 行商品没设课税别：门店默认 TAXABLE → 取它（`STORE_DEFAULT`）、不再缺；无门店默认 → 仍缺；门店默认零税率收银员也能建；有课税别的商品不变 | `StoreOrderMysqlTest#ac6SkuWithoutGoodsTaxClassTakesTheStoreDefaultOrStaysPending` | ✅ |
| AC11 常用品项带出品名 / 税别（零税率、免税不需另授权），停用的 20026；补资料也能选品项 | `#ac11StoreItemSuppliesNameAndTaxTypeEvenZeroRatedWithoutOverride` | ✅ |
| AC11 门店设定默认 `{null, MANUAL}`、清空默认税别写 NULL、门店隔离、CHECK | `InvoiceServiceMysqlTest#storeSettingsDefaultToManualAndSaveClearsTheDefaultTaxTypeExplicitly` | ✅ |
| AC11 常用品项按店、按 sort、可改可删（幂等）、上限 50 → 26015 | `InvoiceServiceMysqlTest#storeItemsArePerStoreCappedAndEditable` | ✅ |
| AC8 本单 ON_PAID → 付清后用预存载具开；门店 MANUAL 不开；本单 MANUAL 覆盖门店 ON_PAID；跟门店 ON_PAID 用一般消费者；资料不齐 26002 跳过 | `StoreOrderMysqlTest#ac8OnPaidIssuesWithTheRememberedBuyerAndManualDoesNot` | ✅ |
| 路由准入 / 权限码 | `StoreScopedDailyRoutesFlowTest#storeInvoiceSetupRoutesPassAdmission`、`BizEndpointPermTest` | ✅ |

**红的一次**（2026-11-24）：测试与实现同批写成后，把 `StoreOrderSettlementServiceImpl#settle` 第一行改成直接 `return`（付清判断未接）跑
`StoreOrderMysqlTest`：`Tests run: 21, Failures: 5, Errors: 2` —— `ac7…`、`ac16…`、`overpayment…`、`collectionAttaches…`、`concurrentPayments…`
断言失败（订单停在 AWAITING_PAYMENT / 没有事件），`ac8…` 取不到 `ORDER_PAID`（`NoSuchElementException`），`voiding…` 报乐观锁参数缺失
（测试库少装 `OptimisticLockerInnerInterceptor`，测试夹具缺陷，已补成与生产 `PrimaryPersistenceConfiguration` 一致）。
不依赖付清的 P2 用例（默认税别、常用品项、币别 / 已取消拒绝）同次即绿。还原 `settle` 后 `Tests run: 21, Failures: 0`。

**真实输出**（2026-11-24，`COMMERCE_MYSQL_URL=jdbc:mysql://127.0.0.1:3308/?…`）：

```
mvn -o -pl shop-app -am test -Dtest='ArchitectureTest,BizEndpointPermTest,MerchantRoleFlowTest,SchemaParityTest,SchemaDriftTest,
  StoreScopedDailyRoutesFlowTest,BackendI18nParityTest,BizRoleSeedTest,InvoiceOptionalTest,*Invoice*,*Order*,*OfflineRecord*'
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.trade.StoreOrderMysqlTest
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.scenario.BizOfflineRecordFlowTest
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.commercebridge.StoreScopedDailyRoutesFlowTest
Tests run: 27, Failures: 0, Errors: 0, Skipped: 1 -- in ai.neargo.shop.arch.ArchitectureTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.arch.BizEndpointPermTest
Tests run: 10 / 6 / 1 / 6 / 4 / 1 … MerchantRoleFlowTest / BackendI18nParityTest / SchemaParityTest / SchemaDriftTest / BizRoleSeedTest / InvoiceOptionalTest：0 失败
Tests run: 2 / 7 / 3 … InvoiceAutoIssueConsumerTest / SubOrderInvoiceSourcePortImplTest / OfflineRecordInvoiceSourcePortImplTest：0 失败
Tests run: 54, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.invoice.service.impl.InvoiceServiceMysqlTest
shop-core 146 · invoice-domain 61 · invoice-channel 58 · shop-app 555（Skipped 6）… 各模块合计 886 条，Failures 0 Errors 0
BUILD SUCCESS
```
追加「SKU 行回落门店默认税别」后重跑 `-Dtest=StoreOrderMysqlTest`：`Tests run: 22, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。

## §6 对账二 · 设计 → 实现

P1 共 6 个提交（`git diff --stat 95209a38f..HEAD`，不含本文）：

```
 b-app/src/i18n/locale/{ar,en,zh-CN}.ts                                   新权限码三语文案（biz-perm-label 守卫）
 backend/invoice/…/InvoicePortImpl.java · InvoiceService.java · InvoiceServiceImpl.java   rememberedBuyer
 backend/scripts/gen-test-schema.py                                       认 ALGORITHM/LOCK、带连字符的权限码
 backend/shop-app/…/invoicebridge/port/SubOrderInvoiceSourcePortImpl.java 读订单行开票资料、missing、比例分摊
 backend/shop-app/…/db/migration/V692 · V693 · V694                       列 / 约束新支 / 角色
 backend/shop-app/…/i18n/messages{,_en,_ar}.properties                    20024–20028
 backend/shop-app/src/test/…  BizEndpointPermTest · StoreScopedDailyRoutesFlowTest · SubOrderInvoiceSourcePortImplTest
                              MerchantRoleFlowTest · trade/StoreOrderMysqlTest（新）· schema-test.sql（生成）
 backend/shop-base-auth/…/BizPerms.java                                   biz:order:create / reprice、biz:invoice:tax-override
 backend/shop-base/…/ErrorCode.java · spi/invoice/InvoicePort.java · spi/product/GoodsPricingPolicyPort.java
 backend/shop-core/…/product/{port,service,service/impl}/GoodsPricingPolicy*  taxClasses
 backend/shop-core/…/trade/api/biz/BizStoreOrderController.java（新）
 backend/shop-core/…/trade/entity/OrdInvoiceDataFill.java（新）· OrdItem.java · OrdOrder.java · mapper/TradeMappers.java
 backend/shop-core/…/trade/policy/InvoiceReadinessPolicy.java（新）
 backend/shop-core/…/trade/service/StoreOrderService.java · service/impl/StoreOrderServiceImpl.java（新）
 backend/shop-gateway/…/commercebridge/ShopStoreMemberRoutes.java
 docs/api/store-order.openapi.json（新）· backend-ui-delivery-store-order.json（新）· 响应格式规范.md
 docs/api/网关接口清单-B端.md · reference/B端功能点-权限码-页面.md · reference/B端功能矩阵-按角色.md（生成）
 packages/shared/tests/data-scope-coverage.test.ts · scripts/lib/schema-lineage.mjs（守卫登记）
 44 files changed, 2455 insertions(+), 74 deletions(-)
```

| 差异 | 说明 |
|---|---|
| §2 没列、实际改了：`InvoicePort` / `InvoiceService` 加 `rememberedBuyer` | `buyerSet` 与「买受人只能补一次」要读预存买受人；发票域只经 spi 暴露 |
| §2 没列、实际改了：`GoodsPricingPolicyPort#taxClasses` | 现有 `resolve` 对缺行整批拒（PRICING_OWNER_UNSUPPORTED），且只认 `market='CN'` 的 SKU；P1 要逐行知道「哪行没设税别」 |
| §2 没列：`docs/api/store-order.openapi.json` + 分阶段交付登记 | `check-app-backend-orphan` 闸门：调用方是手持收银机（ai-shop-bapp），本仓 Vue b-app 不接 |
| §2 没列：`gen-test-schema.py` 两处小改 | `ALGORITHM=INSTANT` / `LOCK=NONE` 原先落到「不认识的 ALTER 动作」；权限码字母表不含 `-` |
| §2 列了、P1 没做：`ivc_store_setting` / `ivc_store_item`、`db/invoice` V15、`/biz/invoice-setup/store-*` | 属 P2（AC11） |

### 偏差说明

1. **迁移号**：V692（列、唯一键、`ord_invoice_data_fill`）、V693（四条 CHECK 各追加一支）、V694（内置角色权限码）。约束改动单独一条，与 §4 一致。
2. **整单优惠不落 `ord_item` 负数行**，落 `ord_order / ord_sub_order.discount_amount`（子单同时记 `discount_merchant`）。订单列表、
   `LineShares` 行实付分摊、退款与发票来源都按「商品 − 优惠 = 实付」读，负数行会让每个读者多一种情况。代价：优惠的名称（如「整單 9 折」）
   不落库，发票上统一显示「折扣」。`line_kind='ADJUSTMENT'` 保留未用。
3. **`ord_order.operation_no` 存 `SHA-256(Idempotency-Key)`**：V693 新支要求操作号必填；幂等以 `client_order_no` 唯一键为准（同号返回原单，不比对请求体），
   同一键换了 `clientOrderNo` 回 10409。
4. **自订行的 `goods_no / sku_no` 存空串**（两列 NOT NULL，不为此改热表）。
5. **`invoice_data_source` 多一个值 `ENTERED`**：建单时店员录入的自订行品名 / 税别；之后补填的为 `FILLED`，商品冻结的为 `GOODS`。`STORE_ITEM` 留给 P2。
6. **补资料不限于自订行**：SKU 行若商品没设课税别（或 OUT_OF_SCOPE）会是待补，只能补不能改；只限自订行的话这张单永远开不了票。
   已有值给相同值视为无操作，给不同值回 20027。
7. **对外 `taxType` 用 `TAXABLE / ZERO_RATED / EXEMPT`**（契约），发票明细内部仍是 `TAXABLE / ZERO / EXEMPT`，在发票来源里转换。
8. **改价 / 零税率免税越权用既有 70006 `BIZ_ROLE_FORBIDDEN`**（带权限说明），不另开错误码；端点上只判建单码，附加权限按行在服务里判。
9. **P2/P3 字段**：`member.memberNo`、`invoice.timing` 收下不处理；`storeItemNo` 非空回 20026（不静默忽略）。
10. **AC10 改变了旧订单的行为**：原先「混合税别 + 整单优惠 / 积分 / 称重」回 NOT_READY（TDD-发票模块 §4），现在对所有子单按税别比例分摊；
    `SubOrderInvoiceSourcePortImplTest` 原 `mixedTaxWithWholeOrderDiscountOrUnknownTaxClassIsNotInvoiced` 拆成分摊断言 + 未知税类仍 NOT_READY。
    明细金额全为 0 时分不了，仍回 NOT_READY。
11. **准备度优先级**：ISSUED > REFUNDED > NEEDS_INFO > NOT_PAID > READY —— 未付款但缺资料显示 NEEDS_INFO（与 PRD 示例一致），资料齐但未付显示 NOT_PAID。
12. **补资料权限**用 `biz:invoice:issue`（店员有）：它是开票前一步；取消用 `biz:order:create`。看单用 `biz:order:view`。

### P2（2026-11-24，未发布）

```
 backend/invoice/…/api/biz/BizInvoiceStoreSetupController.java（新）· dto/InvoiceDtos.java · entity/IvcStoreItem · IvcStoreSetting（新）
 backend/invoice/…/mapper/InvoiceMappers.java · port/InvoicePortImpl.java · service/InvoiceStoreSetupService + impl（新）
 backend/invoice/…/db/invoice/V15__invoice_store_settings.sql（新）· test InvoiceServiceMysqlTest
 backend/pay/pay-domain/…/acquiring/port/PaidCollectionPortImpl.java（新）
 backend/shop-app/…/invoicebridge/InvoiceAutoIssueConsumer.java · db/migration/V695__store_order_settlement.sql（新）· i18n ×3
 backend/shop-app/src/test/…  StoreOrderMysqlTest · InvoiceAutoIssueConsumerTest · BizOfflineRecordFlowTest · BizEndpointPermTest
                              StoreScopedDailyRoutesFlowTest · schema-test.sql（生成）
 backend/shop-base/…/BizKey.java · ErrorCode.java · spi/invoice/InvoicePort.java · spi/pay/PaidCollectionPort.java（新）
 backend/shop-core/…/trade/api/biz/BizStoreOrderController.java · entity/OrdOrder · OrdStoreCollection（新）
 backend/shop-core/…/trade/mapper/OfflineRecordMappers · TradeMappers · service/StoreOrderService · StoreOrderSettlementService（新）
 backend/shop-core/…/trade/service/impl/OfflineRecordServiceImpl · OrderServiceImpl · StoreOrderServiceImpl · StoreOrderSettlementServiceImpl（新）
 backend/shop-gateway/…/commercebridge/ShopStoreMemberRoutes.java · payclient/OrdinaryPaymentRouting.java
 backend/shop-gateway/…/paybridge/StoreOrderCollectionConsumer.java（新）
 docs/api/store-order.openapi.json · backend-ui-delivery-store-order.json · 响应格式规范.md · 网关接口清单-B端.md（生成）
 docs/technical/reference/B端功能点-权限码-页面.md · B端功能矩阵-按角色.md（生成）· packages/shared/tests/data-scope-coverage.test.ts
 47 files changed, 2020 insertions(+), 123 deletions(-)（不含本文）
```

| 差异 | 说明 |
|---|---|
| §2 写 `StoreOrderSettlement#apply`（订阅收款成功 / 现金记录事件），实际是 `StoreOrderSettlementService` 被直接调用 | 现金记录与付清同一事务（记录不落就不付清、付清失败记录也回滚），不经事件；Java 规范「Outbox 使用收缩」：能直调不引入投递 |
| §2 没列：spi `PaidCollectionPort` + 支付域实现 + `OrderServiceImpl#markPaid` 支付单号分支 | 内嵌形态下收款码成功唯一交到交易侧的点是 `markPaid(PYO…)`（回调与三个对账器共用），它原先直接跳过；金额要取支付域实收 |
| §2 没列：`StoreOrderCollectionConsumer`（shop-gateway） | v2 是独立形态（`SHOP_PAY_DEPLOYMENT=standalone`），回调落在 near-pay，ai-shop 只收已验签收款推送 |
| §2 没列：新表 `ord_store_collection` | 收款码金额是支付域的数，付清加总不能每次跨域查（事件线程里没有操作人，`receipts` 要求操作人）；挂单时落一行，唯一键即幂等 |
| §2 没列：`ord_offline_record.idx_offline_record_order` | 付清加总按子单号查现金记录，原表只有按 `biz_no` 的索引 |
| §2 写 `ivc_store_setting.default_tax_class`，实际列名 `default_tax_type`、值 TAXABLE / ZERO_RATED / EXEMPT | 与契约、发票明细同口径；订单行冻结时再转商品侧 `STANDARD…` |

### P2 偏差说明

1. **付清后子单落 COMPLETED**（不是 WAIT_FULFILL）：柜台当面交货。查过：发票来源与准备度的已付集合含 COMPLETED；资金巡检 I1（已付却无结算单自动补）只扫
   `LEGACY_FUNDED`；结算单生成不调。没走状态机写状态日志 / `SUB_ORDER_COMPLETED`（与 P1 取消同一写法：条件更新）。
2. **`ORDER_PAID` / `SUB_ORDER_PAID` 的 `userNo` 为空**（散客）。消费者核过：站内信（`MessageServiceImpl#pushTo`）对空收件人记日志跳过；C 端 App 推送（`NotificationConsumer#cPush`）对空收件人的行为**未核**；**`SUB_ORDER_PAID` 会给店员推「新订单，待备货」**——
   POS 当面成交的单其实不用备货，是噪音，未改（按要求与 `markPaidSource` 同形），列为待定。
3. **付清后不再接受新的现金记录**（20024），收款码照记：现金是店员手录，挂上就作废不掉（20029），宁可当场拒；收款码是真钱已到，不能拒。
4. **部分付款的单不能取消**（20024）：钱已挂在单上，先作废现金 / 退收款。
5. **自动开票不看主体「自动开立」开关**：门店零售单只按本单 / 门店开票时机；门店设定是更细的一层。其他订单行为不变。
6. **不另做同步开票**：`ORDER_PAID` 的投递（`OutboxPump` / 统一 outbox `OutboxRunner`）都在 api 进程，v2 只跑 `api,ops` 也会投递。
   前提：`SHOP_OUTBOX_PUMP` 未被关（默认 true）；关了就要开 `SHOP_OUTBOX_LEGACY_SCAN` 或 worker。v2 的 env 未在本仓可查，**未在 v2 实测**。
7. **独立形态的收款码挂单依赖 `shop.payment-collections.enabled=true`**（它又要求统一 outbox 模式）。v2 是否开着未查到；没开时收款码不会自动挂单，
   POS 只能靠现金记录付清 —— 发布前要确认，或另开「按 bizNo 拉 near-pay 收款」的补单入口（未做）。
8. **`20026` 改义**（`STORE_ORDER_STORE_ITEM_UNSUPPORTED` → `…_UNAVAILABLE`）：P1 未发布，复用号段不留死码。
9. **`invoice_data_source`**：品项 `STORE_ITEM`；默认税别 `STORE_DEFAULT`（只在品名、税别都没填时；填了品名、税别取默认的仍记 `ENTERED`）；补资料选品项仍记 `FILLED`（稽核行有值）。
10. **多收**：`paidMinor` 如实大于总额，不记找零金额（POS 自己算）；退多收的钱是 P3。
11. **收款码币别不符 / 别店 / 已取消后到账**：不报错（钱已收），只记日志或只记不转状态，需人工处理。
12. **SKU 行回落门店默认税别**（用户 2026-11-24「用门店默认税别」，P2 中途追加）：商品没设课税别或 OUT_OF_SCOPE 的 SKU 行取门店
    `defaultTaxType`，`invoice_data_source=STORE_DEFAULT`；门店没设仍待补。改变了 P1 偏差 6 的前提（「SKU 行没税别只能补」）——
    现在只有门店也没默认时才需补。此用例与实现同批写成，首跑即绿（`StoreOrderMysqlTest` 22 条，0 失败）。

### 闸门（2026-11-24，`bash .githooks/pre-push`，HEAD 干净副本）

- 界面清单、原型、契约缺口、孤儿端点（登记分阶段交付后）、SQL 方言、vue-tsc、c-app 单测、HEAD 编译：通过。
- 生成物闸门：只剩主干既有的 12 份过期（PR #43 遗留，未重跑）；本次影响的 `网关接口清单-B端.md`、`B端功能点-权限码-页面.md`、`B端功能矩阵-按角色.md` 已重跑。
  `docs/technical/README.md` 由 gen-doc-index 生成且在那 12 份里，本文的索引行随它一起补。
- packages/shared / ops-web 守卫：只剩主干既有的 7 + 2 条（与 95209a38f 逐条比对一致）。
- 后端全量 5390 条 / 1 红：`FunctionPointPermAlignmentTest`（`pay:fee-plan:approve`，V691 遗留），与本次无关。

### P2 闸门（2026-11-24，`bash .githooks/pre-push`，HEAD `63b5d3752`）

- 界面清单、原型、进销存清单、运营端清单、V2 文档、契约缺口、孤儿端点（store-settings / store-items 已登记分阶段交付）、结算状态文案、i18n 孤儿、RTL、Controller 内聚、进销存独立性：通过。
- 生成物闸门：只剩主干既有的 12 份（与 `d30a1e678` 逐条比对一致）；本次影响的 `网关接口清单-B端.md`、`B端功能点-权限码-页面.md`、`B端功能矩阵-按角色.md` 已重跑。
- packages/shared / ops-web 守卫：7 + 2 条，均为主干既有（`data-scope-coverage` 因 `ord_store_collection` 红过一次，已登记豁免理由）。
- `check-head-compiles.sh`（干净 HEAD，带 `COMMERCE_MYSQL_*`）：全量 5392 条 / 1 红 —— `FunctionPointPermAlignmentTest`（`pay:fee-plan:approve`，主干既有），与本次无关。
  末一个提交（SKU 行回落门店默认税别）只动 `StoreOrderServiceImpl` 与测试，未再跑全量，`StoreOrderMysqlTest` 22 条已绿。

## §7 上线记录（v2，`.80` `/data0/app/ai-shop-v2`，正式库 `ai_shop_ume`）

包一律在 main 上以 `-P opskit` 构建（与 v2 上 OpsKit 审批版本同依赖集），`run.sh` 切标签与包后 `./run.sh all restart`，每次中断约 90 秒。

| 时间（UTC） | 版本 | 内容 | 迁移 | 回退 |
|---|---|---|---|---|
| 2026-11-23 20:41 | `main-d30a1e678`（#48） | P1：建单、准备度、补开票资料、发票来源读订单行 | V692–V694（`ord_item` 4 列、`ord_order.client_order_no/channel` + 唯一键、`ord_invoice_data_fill`、四个约束加 WALK_IN 支、角色权限） | `cp releases/run-before-main-d30a1e678.sh run.sh && ./run.sh all restart` |
| 2026-11-24 01:49 | `main-96552f2df`（#49 + #50） | P2：收款挂单付清、门店开票设置、付清自动开票；券列表 / 会员名单修复 | V695（`ord_order.invoice_timing`、`ord_offline_record` 索引、`ord_store_collection`）；发票链 V15（`ivc_store_setting`、`ivc_store_item`） | `cp releases/run-before-main-96552f2df.sh run.sh && ./run.sh all restart` |
| 2026-11-24 02:21 | `main-49872def0`（#51） | 发票同步上传（TDD-发票模块 §25）；env 加 `SHOP_INVOICE_TRANSMISSION_SYNC=true`（备份 `ai-shop-all-v2.env.before-sync-upload-20261124`） | 无 | 还原 env 备份并 `cp releases/run-before-main-49872def0.sh run.sh && ./run.sh all restart` |

- 迁移所需的 CREATE / ALTER / INDEX：应用账号 `ai_shop_ume@192.168.3.80` 当时已带着（他人 OpsKit 上线时授予、未收回），本方案未另授权；是否收回待用户决定。
- V693 上线前已核对：正式库现有五条订单约束与本机一致，V693 应用后原文逐字保留、只追加 WALK_IN 一支。

**v2 实测（手持收银机会话，用户本人会话，门店 Oscar Retail QA 1121 `ST2611210W463ZZ12A`，STUB 通道）**
- P1：输入金额 / SKU 建单、`NEEDS_INFO` 待补、补资料、未付开票回 26002——均符合预期。
- P2：现金付清转 PAID、开票 ZZ-00000100（自订行 NT$100，未税 95 + 税 5）与 ZZ-00000101（SKU 行补税别后）、模拟打印 PDF 正确、作废；券列表 / 会员名单修复生效。
- 付清自动开票（门店 ON_PAID）：触发正常，`OD20261124000202` 因测试号段已停用回 26003 并按既定设计重试；门店已改回 `MANUAL`，重试将跳过。
- 未测：收款码 / 付款链接挂单（v2 已启用 `shop.payment-collections`，代码路径已测）、买方统编 / 载具 / 捐赠、鲸跃正式通道（待用户录入账号，见交接文档）。
