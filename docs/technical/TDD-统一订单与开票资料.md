# TDD-统一订单与开票资料（POS 建单 · 待补资料 · 付清开票）

状态：已确认（2026-11-24，用户：「按 B 开始 P1」）；P1 实现中
档位：2 · 依据：[PRD-统一订单与开票资料](../requirements/PRD-统一订单与开票资料.md) · 决策：[ADR-065](./ADR/ADR-065-门店零售单与订单行开票资料.md)
关联：[TDD-发票模块](./TDD-发票模块.md) §9 / §24，[TDD-多行业统一业务架构-零售餐饮美业](./TDD-多行业统一业务架构-零售餐饮美业.md)，[TDD-统一试算引擎-初始规则与结账税费](./TDD-统一试算引擎-初始规则与结账税费.md)
创建：2026-11-24 · 最后更新：2026-11-24

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
| AC7 | 现金记录（`orderNo`）、收款码 / 付款链接（`bizNo`）、刷卡挂订单；**收款加总 = 总额**时订单转已付款并发 `ORDER_PAID` | `StoreOrderSettlement#apply`（订阅收款成功 / 现金记录事件） |
| AC8 | 付清且 READY 且门店开票时机 `ON_PAID` → 自动开票 | 复用 `InvoiceAutoIssueConsumer`（`ORDER_PAID`）+ 开票时机设置 |
| AC9 | 开票只送订单号；不是 READY 回 `26002` 并附 `missing` | `SubOrderInvoiceSourcePortImpl` 读新字段；`BizException` data 带 missing |
| AC10 | 混合税别的整单优惠按金额比例分摊到税别（最大余数法），分不了才回待补 | `SubOrderInvoiceSourcePortImpl` + `LineShares` 同口径分摊 |
| AC11 | 门店设定：默认课税别、常用品项（品名 + 课税别）、开票时机（付清自动开 / 手动 / 稍后） | `GET/PUT /biz/invoice-setup/store-settings`、`/store-items` |
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
| v2 上没有 worker | 自动开票（outbox 消费）、上传都不跑 | P2 前确定 v2 worker 方案（见发票模块 §23 后续） |

## §5 对账三 · 实现 → 需求（测试）

（实现后填：每条 AC 一个测试方法，贴真实输出。）

## §6 对账二 · 设计 → 实现

（实现后填 `git show --stat` 与偏差说明。）
