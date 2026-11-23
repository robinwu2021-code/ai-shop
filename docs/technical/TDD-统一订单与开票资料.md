# TDD-统一订单与开票资料（POS 建单 · 待补资料 · 付清开票）

状态：已确认（2026-11-24，用户：「按 B 开始 P1」）；P1 已实现（未发布）
档位：2 · 依据：[PRD-统一订单与开票资料](../requirements/PRD-统一订单与开票资料.md) · 决策：[ADR-065](./ADR/ADR-065-门店零售单与订单行开票资料.md)
关联：[TDD-发票模块](./TDD-发票模块.md) §9 / §24，[TDD-多行业统一业务架构-零售餐饮美业](./TDD-多行业统一业务架构-零售餐饮美业.md)，[TDD-统一试算引擎-初始规则与结账税费](./TDD-统一试算引擎-初始规则与结账税费.md)
创建：2026-11-24 · 最后更新：2026-11-24（P1 实现）

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

### 闸门（2026-11-24，`bash .githooks/pre-push`，HEAD 干净副本）

- 界面清单、原型、契约缺口、孤儿端点（登记分阶段交付后）、SQL 方言、vue-tsc、c-app 单测、HEAD 编译：通过。
- 生成物闸门：只剩主干既有的 12 份过期（PR #43 遗留，未重跑）；本次影响的 `网关接口清单-B端.md`、`B端功能点-权限码-页面.md`、`B端功能矩阵-按角色.md` 已重跑。
  `docs/technical/README.md` 由 gen-doc-index 生成且在那 12 份里，本文的索引行随它一起补。
- packages/shared / ops-web 守卫：只剩主干既有的 7 + 2 条（与 95209a38f 逐条比对一致）。
- 后端全量 5390 条 / 1 红：`FunctionPointPermAlignmentTest`（`pay:fee-plan:approve`，V691 遗留），与本次无关。
