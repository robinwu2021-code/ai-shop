# TDD-商户App资金

状态：已确认（2026-11-08：用户按「已拍板」一节各条的建议全部接受；未实现，§5 测试未写）
ADR：[ADR-052](./ADR/ADR-052-台湾直连拨款模式下商户资金视图的数据归属.md)
档位：2 · 依据：跨服务（资金数据的真源在独立支付服务 near-pay，要它新增查询；渠道拨款与渠道账单在 near-pay 是待建表族）+ 跨端（商户 App 契约）。**需要另开 ADR**（本文不写，见 §3 末）。
关联需求：
- 商户 App 交接文档《商户 App 功能矩阵与 API 交接文档》第 8 模块「资金」、§3 通用约定、附录 B `/biz/funds/` 行（ai-shop-bapp 仓库 `docs/implementation/功能矩阵与API交接文档.md`（main `31a56c1`））；原型 v11 F01–F07；App 代码 ai-shop-bapp `apps/merchant_app/lib/features/funds/funds_pages.dart`
- [PRD-商家资金到账与对账](../requirements/PRD-商家资金到账与对账.md) §3.3 AC-6（对账要写明覆盖范围）、§4 与 §7（不给商家做「向平台提现」）
- [TDD-B端收款订单与商城接口对齐](./TDD-B端收款订单与商城接口对齐.md)（`/biz/payment/orders*` 的契约与口径，本文复用）
- 评审：ai-shop-bapp `docs/implementation/TDD评审-商户App.md`「最需要先定的」#5、#6 与「第三组 · 一、TDD-商户App资金」（2026-11-08，对照 App `58a3995`）；本轮修订逐条见 §2.0

创建：2026-11-08 · 最后更新：2026-11-08 · 对照代码：`codex/bapp-handoff-tdd`（基于 `origin/codex/ai-shop-codex`，HEAD `fc070b2d4`）；near-pay 对照本机 `/Users/workingowl/work/near-pay`（`develop/s1`，`993cf6b49`）

---

## 一句话（L1）

台湾模式下渠道直接拨款给商户，平台**不经手资金**。商户 App「我的 › 资金」只做**看**：本期收了多少、渠道扣了多少手续费、拨了多少、还有多少没拨、和渠道对不对得上。
ai-shop 在这里只当 BFF（认人、判权、定门店、转发）；**数字的真源在支付服务**。没有提现，没有余额。

## 结构（L2）

| 页面（原型） | App 调用 | 结论 | 数据真源 |
|---|---|---|---|
| F01 资金总览 | `GET /biz/funds/overview` | **新增**（大部分可由已有收款汇总的同一套查询算出） | 支付服务：`pay_order` / `pay_refund` / `stl_payment` / `pay_merchant` |
| F02 每日流水 | `GET /biz/funds/daily` | **新增**（已有 `/biz/payment/statement` 只给单日，不够用） | 同上 |
| F02 点某天 → 当天明细 | `GET /biz/payment/orders?from=to=当天` | **复用**，后端不动 | 同上 |
| F03/F04 拨款记录与详情 | `GET /biz/funds/payouts` | **新增**；一期只能给「预计」行 | 预计行：同上；实拨行：**两边都还没有**（near-pay 渠道账单待建） |
| F04 拨款期间的交易 | `GET /biz/payment/orders?from&to` | **复用**，后端不动 | 同上 |
| F05 对账 | `GET /biz/funds/reconcile` | **新增**；一期只有平台自查，必须标覆盖范围 | 支付服务 `stl_recon_diff`；渠道账单侧待建 |
| F06 收款账户 | `GET /biz/funds/accounts` + 已有 `GET /biz/merchant/pay-service`（开通申请状态） | **新增**（评审后改）：交接文档说的「F06 已有」指 `/biz/merchant/payment`，那是 ai-shop 自己的微信 / 支付宝进件表，台湾店走 pay-service 开通，账户在支付服务，见 §1.2 | 支付服务 `pay_merchant`（经 `pay_merchant_binding` 到门店）|
| F07 渠道费率与周期 | `GET /biz/funds/rates` | **新增**；本市场未开通的渠道也列一行 | 支付服务 `sys_pay_channel_rate` + 渠道 / 收款商户的账期 |

调用链（独立部署形态，166 与生产）：App → shop-app `BizFundsController`（`biz:finance`、门店上下文）→ `BizFundsAppService`（远程实现）→ near-pay `/internal/pay/v1/biz/stores/{storeNo}/funds/*` → near-pay pay 域查询。
内嵌形态（本仓库测试、`shop.pay.deployment=embedded`）走本仓库 `backend/pay` 的同名查询。两处要实现同一份查询（见已拍板 D2）。

---

## §0 对账一 · 需求 → 设计

需求按交接文档第 8 模块的 9 行功能矩阵逐条整理成 AC，再补 4 条横向 AC（权限、门店登记、覆盖范围、合并范围）。

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| AC1（8-01） | Given 店主选了一家门店 When 打开资金总览 Then 看到所选期间（默认本月）的收款笔数与金额、退款、手续费（标是否估算）、已拨款、待拨款、对账差异数、**数据的计算时间**，以及按渠道的收款 / 待拨 / 账期 / **本期用到的支付方式** | 新 `GET /biz/funds/overview`；pay 域新 `MerchantFundsQueryService#overview` |
| AC2（8-02） | Given 一个月份**或一段自定义区间（本月 / 上月 / 自訂）** When 看每日流水（本店或整个主体）Then 每个有发生额的业务日一行：收款笔数、收款金额、退款笔数、退款金额、手续费（标估算）；日期按门店时区切 | 新 `GET /biz/funds/daily`；`MerchantFundsQueryService#daily`；`scope=ENTITY` 由 shop-app 按门店扇出后相加 |
| AC3（8-03） | Given 每日流水某一天 When 点进去 Then 列出当天收款明细并可进收款详情 | **复用** `GET /biz/payment/orders?from=D&to=D`（`BizPaymentController.java:137-151`），后端不改 |
| AC4（8-04） | Given 拨款记录页 When 按渠道或状态筛选 Then 每条拨款一行，状态 `EXPECTED / PAID / MISMATCH`；渠道没回报的只出「预计」，并且明确标出是预计 | 新 `GET /biz/funds/payouts`；`MerchantFundsQueryService#payouts`；一期只有 `source=ESTIMATED` 行（AC4 的 PAID / MISMATCH 依赖已拍板 D3） |
| AC5（8-05） | Given 一条拨款 When 看详情 Then 有收款总额、退款、手续费、实拨、账户掩码、费率、笔数、期间起止 | 同 AC4 的行字段（不另开详情接口，与 App 现状一致） |
| AC6（8-06） | Given 一条拨款 When 看期间内的交易 Then 列出该期间收款 | **复用** `GET /biz/payment/orders?from=periodFrom&to=periodTo`，后端不改 |
| AC7（8-07） | Given 对账页与一个期间 When 打开 Then 有平台笔数、渠道笔数、差异列表（每条带发生时间与交易号）；**看不到的那一类要写明看不到**（没有渠道账单时不能显示「没有差异」） | 新 `GET /biz/funds/reconcile`；响应带 `coverage`；差异来自 `stl_recon_diff` 按本店支付单过滤 |
| AC8（8-08） | Given 台湾门店的收款账户页 When 打开 Then 按渠道列出收款账户掩码、状态、账期（只读），以及开通申请状态 | 新 `GET /biz/funds/accounts`（读支付服务 `pay_merchant`）；开通申请状态**复用** `GET /biz/merchant/pay-service`（`BizPayServiceController.java:47-60`）。**不再用** `GET /biz/merchant/payment`（评审后改，理由见 §1.2） |
| AC9（8-09） | Given 费率页 When 打开 Then 本市场每个渠道 × 支付方式一行（**未开通的渠道也列，标未开通**）：费率（万分比）、最低手续费、账期、生效时间；**与总览里估算手续费用的是同一个费率** | 新 `GET /biz/funds/rates`；`MerchantFundsQueryService#rates`，读 `PayChannelRateService#effective` |
| AC10（横向） | Given 店长、店员或其他没有 `biz:finance` 的身份 When 调任何 `/biz/funds/*` Then 70006（无 nextAction）；店主可调 | `@PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")`，与 F06 同一个码 |
| AC11（横向） | Given 新模型门店 When 店主调 `/biz/funds/*` Then 不被 70006 `CONTACT_ADMIN` 拒 | `ShopStoreMemberRoutes.ALL` 加一行 |
| AC12（横向） | Given `scope=ENTITY` When 调用者不是该主体的店主 Then 10403；是店主时只汇总该主体名下、他可见的门店；门店超过 50 家时回**专用错误码**（不是通用 10400），App 据此提示改看单店 | `BizFundsController#daily` 判 `BizContext.owner()`（`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizContext.java:22,35`），门店集合取 `BizContext.storeNos()` ∩ 主体门店；新码 `FUNDS_ENTITY_TOO_MANY_STORES`（§2.1） |
| AC13（横向） | Given 金额 When 任一 `/biz/funds/*` 返回 Then 一律整数最小单位 `xxxMinor` + 顶层 `currency`，按门店 `currencyScale` 解释（TWD = 2，NT$480 = `48000`）；日期、费率、状态一律结构化，不回展示文字 | §2.2 |
| AC14（横向） | Given 支付服务连不上（独立形态）When 调任一 `/biz/funds/*` Then 固定回 10505 `RESOURCE_DEPENDENCY_UNAVAILABLE`，不是 10500 | `BizFundsController` 捕获 `ServiceCallException` 转 10505，与 `BizPayServiceController.java:57-58` 同法（§2.2） |

**孤立项**
- 没落点的 AC：
  - AC4 / AC5 里的 **`PAID` 与 `MISMATCH`**、AC7 里的**渠道笔数与渠道侧差异**：要渠道回报拨款与账单。ai-shop 和 near-pay 都没有这份数据（见 §1「后端没有」）。一期这两块只能给「预计」与「平台自查」，并在响应里标出来。是否等它、谁来建，见已拍板 D3。
  - AC1 的「差异数」一期只计平台自查差异。
- 挂不上 AC 的设计：
  - `GET /biz/funds/payouts` 的游标分页：App 现在按不分页的数组读。分页挂在 AC4 上（拨款按天累积，一年几百行），算对 App 的契约变更，见 §2 差异表。
  - ~~F06 补 `settleCycle`~~：评审后撤回。`PaymentApplymentVO` 是大陆微信 / 支付宝进件档，台湾账户不在那张表（§1.2），给它补字段对资金页没有用。

---

## §1 现状与影响面

### 1.1 资金数据到底在哪

| 数据 | 真源 | 出处 |
|---|---|---|
| 收款、退款、渠道手续费（已对账的实收值）、预计入账日、结算账户掩码 | **支付服务** pay 域：`pay_order`、`pay_refund`、`stl_payment.channel_fee_minor`、`pay_merchant.settle_cycle / settle_account_masked`、`pay_ledger_voucher`（`SETTLEMENT` 凭证判「已结算」） | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/acquiring/service/impl/MerchantPaymentQueryServiceImpl.java:160-196`（汇总）、`:446-468`（已结算判定、预计入账日） |
| 166 / 生产用的是哪一份 | **独立服务 near-pay**：`shop.pay.deployment=standalone` 时 shop-app 走 `RemoteBizPaymentAppService` → `/internal/pay/v1/biz/stores/{storeNo}/**` | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/impl/RemoteBizPaymentAppService.java:45`；`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/client/PayBizInternalApi.java:33`；166 走 near-pay 的证据见 TDD-B端收款订单与商城接口对齐「补记 · 2026-11-06」；near-pay 仓库有同名端点 `near-pay/backend/pay/pay-svc/src/main/java/ai/neargo/shop/pay/svc/InternalPayBizEndpoint.java` |
| 本仓库 `backend/pay` | 内嵌形态（默认）与测试用；与 near-pay 是同包名的两份代码 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/impl/BizPaymentAppServiceImpl.java:27`（`matchIfMissing = true`） |
| 渠道费率 | 支付服务 `sys_pay_channel_rate`（市场 × 渠道 × 支付方式 × 主体形态，分版本） | `backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/master/PayChannelRateService.java`（`effective(market, payChannel, payMethod, legalForm, at)`）；near-pay 同名类在 `near-pay/backend/pay/pay-channel/.../master/PayChannelRateService.java` |
| 收款账户（F06，台湾） | **支付服务** `pay_merchant`（`settle_account_masked`、`settle_cycle`、`status`），经 `pay_merchant_binding`（每个门店 × 渠道最多一条 `ACTIVE`）挂到门店；开通由 `/biz/merchant/pay-service/apply` 在 near-pay 建平台商户 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/merchant/entity/PayMerchant.java:29,62,74,77`；`backend/pay/pay-domain/src/main/resources/db/pay/V10__pay_merchant_tables.sql:142,153`（`active_key = store_id\|pay_channel` 唯一）；`backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPayServiceController.java:22-26`（类注释） |
| 收款进件（大陆，**不是** F06） | **ai-shop** `mch_payment_merchant`：每通道一条的微信 / 支付宝进件档 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/entity/MchPaymentMerchant.java:9-25`；进件网关只有 `WechatApplymentGateway`、`AlipayApplymentGateway`（另有开发用 `StubApplymentGateway`），没有蓝新 / LINE Pay |
| 自营供应商出款卡（**不是** F06） | **ai-shop** `/biz/payout-account`：ADR-011 自营供应商模式下平台按账期把货款打到这张卡 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/api/biz/BizPayoutAccountController.java:18-25,40` |
| 渠道拨款记录、渠道账单 | **后端没有**（两边都没有）。near-pay 的设计里是待建表：`pay_channel_bill`、`pay_channel_bill_line`、`pay_reconciliation_run`、`pay_settlement_rule_revision`，状态「拟新增，待实现」，批次「S1 台湾首期：渠道账单与对账」 | `near-pay/docs/database/design-v2/00-数据库表清单.md:237-243`；`near-pay/docs/database/design-v2/09-清分结算出款与渠道对账.md`；运营端 `/pay-ops/v2/channel-bills` 状态「设计」、两家账单格式待联调：`near-pay/docs/api/handbook/07-S1台湾接口切片.md:28,37` |
| 渠道账单下载能力 | **后端没有**真实实现：本仓库 `StatementCapability` 无实现类；near-pay 只有 mockpay 实现 | `backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/capability/StatementCapability.java`；`near-pay/backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/mockpay/MockpayChannel.java` |
| 对账差异 | 支付服务 `stl_recon_diff`，有 `source = SELF_CHECK / CHANNEL_BILL` 区分；**没有门店列**，要经 `payment_no` 回到 `pay_order.store_id` 才能按店过滤 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entity/StlReconDiff.java:23-40,48-64` |

**结论**：除 F06 外，资金页的每一个数字都在支付服务里；ai-shop 不应建资金表，也不应自己算。

### 1.2 能直接复用 / 映射的已有接口

| App 建议接口或用途 | 映射到已有接口 | 能不能直接用 | 出处 |
|---|---|---|---|
| F06 收款账户（开通状态） | `GET /biz/merchant/pay-service`（`biz:finance`） | **能，但只够一半**：回 `status / merchantRef / linkStatus / profileStatus / reviewReason`，是「申请开通」的审核状态，**没有按渠道的账户掩码与账期**。门店准入走 foundation 通道（`CommerceRoutePolicy.java:129`） | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizPayServiceController.java:47-60`；`backend/shop-app/src/main/java/ai/neargo/shop/payclient/PaySourceLinkApi.java:38-41` |
| F06 收款账户（账户本身） | `GET /biz/merchant/payment?entityNo=`（`biz:finance`） | **不能**（评审后改；初稿写「能」是错的）：它读 ai-shop `mch_payment_merchant`，台湾店走 pay-service 开通，这张表里没有蓝新 / LINE Pay 的行；一行都没有时回一条 `NONE` 占位（`MerchantPaymentServiceImpl.java:86-104`），App 会显示「未开通」 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizMerchantController.java:733-740` |
| F06 收款账户（另一个同名接口） | `GET /biz/payout-account` | **不能**：自营供应商的出款银行卡（平台打款给供应商），台湾模式平台不打款 | `BizPayoutAccountController.java:18-25,40` |
| F02 / F04 明细 | `GET /biz/payment/orders?from&to`（`mch:pay:order:view`） | **能**，App 已经这么做 | `BizPaymentController.java:137-151` |
| F01 的收款 / 退款 / 手续费 / 预计入账 | `GET /biz/payment/orders/summary?from&to`（`mch:pay:statement`） | **部分能**：总数都对；但 `byChannel` 没有手续费与待拨，`expectedSettlement` 只取最后一笔的收款商户、只给一个日期，权限码也不是 `biz:finance`。新接口内部复用同一套查询，不让 App 拼两次 | `MerchantPaymentQueryServiceImpl.java:160-196`；`MerchantPaymentQueryService.java:64-69` |
| F02 每日流水 | `GET /biz/payment/statement?day=` | **不能**：只给单日、明细上限 1000 条、不分页；一个月要调 31 次。交接文档说「结构待查」—— 结构是 `DailyStatement{bizDate, currency, paidCount, paidMinor, refundCount, refundMinor, items[]}`，没有手续费 | `BizPaymentController.java:129-135`；`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/acquiring/service/PayStatementService.java:15-22`；`PayStatementServiceImpl.java:34`（`MAX_ITEMS = 1000`） |
| F07 费率 | `GET /biz/settle/rate-card` | **不能**：那是平台佣金（`merchantOwnedRate / platformRate`），不是渠道费率 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/dto/RateCardVO.java` |
| F03 拨款 | `/biz/settle/bills`、`/biz/settle/batch`、`stl_payout` | **不能**：大陆商城的平台抽佣结算与平台放款（批次 × 收款号的网银转账），台湾模式里平台不放款。交接文档已判「不是同一件事」，代码印证 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/pay/BizSettleController.java:53-119`；`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entity/StlPayout.java` 类注释 |
| 余额 / 提现 | `/biz/deposit*`（保证金）、`/biz/merchant/debt`（欠款）、`stl_withdraw` | **不复用、不做**：保证金和欠款是大陆入驻模型；提现已按 PRD §7 退役 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/api/biz/BizDepositController.java:33-41`；`BizMerchantController.java:763-772`；PRD-商家资金到账与对账 §7 |
| 报表 | `/biz/report/daily|monthly` | **不能**：是经营报表（`biz:customer`），口径是订单不是渠道收款 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizReportController.java:47-82` |

### 1.3 会被改到的

- ~~`BizMerchantController#payments` 的返回 `PaymentApplymentVO`（补 `settleCycle`）~~：评审后撤回，本文不再动它（§1.2）。
- `ErrorCode` 与三语 `messages*.properties`：加一个码（AC12）。
- `PayBizInternalApi` / `InternalPayBizEndpoint`（本仓库与 near-pay 各一份）：只加方法，不改已有。

### 1.4 明确不受影响

- `/biz/payment/*` 全部已有端点与口径（只被复用，不改）。
- `/biz/settle/*`、`/biz/deposit*`、`/biz/merchant/debt`、`stl_withdraw`。
- 运营端支付页、对账任务 `ReconScanJob` 本身（只读它的产物）。
- 权限码表与角色模板（不新增码）。
- `GET /biz/merchant/payment`、`GET /biz/merchant/pay-service`、`/biz/payout-account`（只被引用，不改）。

### 1.5 发现的不一致（顺手记下，影响本设计）

1. **手续费估算两套来源**：收款汇总用配置 `shop.pay.fee.estimate-rate-bps`（默认 0，`MerchantPaymentQueryServiceImpl.java:89,470-472`），而费率表 `sys_pay_channel_rate` 有按市场的分版本费率却没人用来估算。如果 F07 显示费率表、F01 用配置估算，两页对不上。本设计让两者都改用费率表、配置只兜底（已拍板 D5）。
2. **账期两套词表**：`pay_merchant.settle_cycle` 是 `T1 / T2 / WEEKLY`（`MerchantPaymentQueryServiceImpl.java:459-468`）；`mch_payment_merchant.settle_cycle` 注释是 `T+1 / T+N / WEEKLY / SEMI_MONTHLY / MONTHLY`（`MchPaymentMerchant.java:150-162`）。资金接口统一回支付服务的值（F06 改读支付服务后也是）；支付服务内部渠道默认账期又是另一种写法（`PayChannelMasterService.java:47-48` 注释举例 `T+1`）。App 暂按「码 → 文字」两套都翻；词表要合一（已拍板 D6）。
3. **F06 读错了接口**（评审后改写本条）。App 的 F06 读 `/biz/merchant/payment`（ai-shop-bapp `features/merchant/merchant_api.dart:247`），还读了后端没有的 `settleCycle`、`enabledStores`（同文件 `:87,90`；`PaymentApplymentVO.java:37-42` 无这两个字段）。但更根本的是：台湾店的账户根本不在这张表，补字段也读不到。F06 改读新 `/biz/funds/accounts` + 已有 `/biz/merchant/pay-service`（§2.3⑥）。
4. **支付服务不可用时回什么码，初稿写错了**。初稿写「沿用收款接口的远程错误映射」，但 `RemoteBizPaymentAppService#unwrap`（`RemoteBizPaymentAppService.java:191-197`）只翻译支付服务**回了**的错误码；连不上时 `ServiceCalls` 抛 `ServiceCallException`（`backend/svc-client/src/main/java/ai/neargo/svc/client/ServiceCalls.java:27-39`），没人接，落到 `GlobalExceptionHandler` 的兜底 10500（`backend/shop-base-auth/src/main/java/ai/neargo/shop/common/GlobalExceptionHandler.java:234-241`）。资金接口改为与 `BizPayServiceController` 一样接住转 10505（AC14）。
5. **期间上限「93 天」的实际含义是 `to − from ≤ 93`**：`checkRange` 写的是 `from.plusDays(MAX_DAYS).isBefore(to)` 才拒（`MerchantPaymentQueryServiceImpl.java:60,483-487`），所以含两端最多 94 个日历日。初稿与外部记账草案都没说清是哪种数法，§2.2 写死。

---

## §2 方案

### 2.0 按商户 App 评审的修订（2026-11-08）

评审：ai-shop-bapp `docs/implementation/TDD评审-商户App.md`「第三组 · 一」。技术问题逐条对过后端代码，属实的直接改进正文；产品问题不在这里定，进「已拍板」。

**已改**

| # | 评审意见 | 核对结果 | 改了什么（章节） |
|---|---|---|---|
| R1 | overview 没有更新时间（F01「更新於 09:40」） | 属实 | ① 加 `generatedAt`（UTC 毫秒，本次计算时刻）。一期数字是现算的，不是批量快照，所以就是「现在」；一期 B 若改快照，含义不变（数据截至时刻） |
| R2 | overview `channels[]` 没有支付方式（F01「信用卡、感應收款」） | 属实；支付单上有 `pay_method`（`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/acquiring/entity/PayOrder.java:71`） | ① `channels[]` 加 `payMethods[]`：期间内该渠道实际有收款的方式码，按金额倒序 |
| R3 | daily 只收 `month`，原型 F02 有「自訂」 | 属实 | ② 加 `from / to`，与 `month` 二选一；跨度上限同 §2.2 |
| R4 | 跨度上限本文 93、外部记账 92，同一 Tab 两套 | 属实，且本文的「93」本身数法不清（§1.5-5） | §2.2 统一为**取后端已有规则**：`to − from ≤ 93`（即含两端最多 94 个日历日），与 `/biz/payment/orders*` 同一个 `checkRange`；外部记账请对齐到同一条（本文不改那份） |
| R5 | rates 只回已开通渠道，F07 要列「未開通」 | 属实；本市场渠道清单后端有：`PayChannelMasterService#enabled(market)`（`PayChannelMasterService.java:36`），台湾为 `NEWEBPAY`、`LINEPAY`（`backend/pay/pay-domain/src/main/resources/db/pay/V12__pay_channel_market_explicit.sql:51-52`；注意本仓库种子里这两个渠道 `enabled=0`，同文件 `:40-43`，生产以 near-pay 主数据为准） | ⑤ 本市场全部启用渠道都出行，加 `opened`；未开通时 `rateBps` 仍按费率表给（表里有就是公开牌价），`settleCycle` 取渠道默认。**`JKOPAY` 不在 ai-shop 的渠道主数据里**，后端不回，App 的「即将推出」继续本地补 |
| R6 | TWD 的 `currencyScale` 是 0 还是 2 | 后端已定为 **2**：`sys_market` 台湾行 `currency_scale = 2`（`backend/shop-app/src/main/resources/db/migration/V294__sys_market.sql:53`），门店的 `currencyScale` 就取这里（`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/StoreVO.java:54-60`）；商城金额模型同样固定 2 位、含 TWD（`backend/commerce/commerce-api/src/main/java/ai/neargo/commerce/api/common/MoneyPolicy.java:3`）；支付域按币种默认小数位存，TWD 为 2（`backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/invoke/interceptor/ValidationInterceptor.java:45` 注明 TWD 最小单位要是 100 的倍数才能发蓝新）。精度 0 只出现在**发给渠道**的那一步（`backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/entity/PayChannelProductMarket.java:33`） | §2.2 写明 `TWD = 2`，NT$480 = `48000`；外部记账请取同值 |
| R7 | F06 应读 `/biz/merchant/pay-service`，`/biz/payout-account` 是大陆出款账户 | **大体属实，需补一块**：`/biz/payout-account` 确是 ADR-011 自营供应商出款卡；初稿用的 `/biz/merchant/payment` 是大陆微信 / 支付宝进件档，台湾店没有行；`/biz/merchant/pay-service` 是台湾开通入口，但只回审核状态，**没有按渠道的账户掩码与账期**（§1.2） | §1.1、§1.2、§1.5-3 改写；撤回初稿 ⑥（给 `PaymentApplymentVO` 补 `settleCycle`）；新增 ⑥ `GET /biz/funds/accounts`（读支付服务 `pay_merchant`），F06 = 它 + `/biz/merchant/pay-service` |
| R8 | 对账差异只有 `bizDate`，F05 要「10/24 13:02」和交易号 | 属实；`stl_recon_diff` 有 `channel_txn_no`、`payment_no`，无发生时刻（`StlReconDiff.java:57-62`） | ④ 加 `occurredAt?`：经 `payment_no` 取我方支付成功时刻；`CHANNEL_ONLY`（我方没这笔）一期为 `null`，一期 B 取渠道账单行时间。交易号**不另造尾号字段**：已回完整 `channelTxnNo` / `payOrderNo`，尾号由 App 截 |
| R9 | `ENTITY` 超 50 店回 10400，App 只能显示通用错误 | 属实；仓库惯例是「下一步动作不同就不复用 BAD_REQUEST」（`backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java:102-164` 多处注释） | §2.1 新增错误码 `FUNDS_ENTITY_TOO_MANY_STORES`（三语）；② 错误行改 |
| R10 | 支付服务不可用 / 内嵌 10505 时 App 显示什么，要固定 code | 属实，且初稿写的「沿用收款接口映射」实际会落 10500（§1.5-4） | §2.2：一律 10505。生产只有独立形态，10505 即「暂时取不到」；内嵌形态（D2 选 B 时）只在本仓库测试出现，不给商户看到，App 不必区分「尚未開放」 |
| R11 | `nextPayoutDate` 未知时 `null` 还是不回 | 技术约定，未定 | §2.2：可空字段**一律回 `null`，不省略** |
| R12 | payouts `status` 多值写法 | 技术约定；已有 `/biz/payment/orders` 的 `status`、`channel` 是 `List<String>`（`BizPaymentController.java:142-143`），Spring 对重复参数与逗号分隔都能绑 | §2.2：与 orders 同，App 统一发重复参数 `status=EXPECTED&status=PAID` |
| R13 | payouts 要不要 `from / to` | 采纳：只能倒序翻页，看去年某月要翻很多页 | ③ 加 `from? / to?`，按 `expectedDate` 过滤，跨度同 §2.2 |
| R14 | F04 交易明细无法按收款商户号过滤 | 核对后**一期实际不会混**：门店 × 渠道同时最多一条 `ACTIVE` 绑定（`V10__pay_merchant_tables.sql:142,153`），同一预计日同渠道只会有两个收款商户的情况仅出现在「当天换绑」 | ③ 口径里写明 F04 用 `periodFrom / periodTo + channel` 调 `/biz/payment/orders`（`channel` 已支持）；换绑当天的少数混行接受，不给 orders 加收款商户过滤 |
| R15 | `payoutNo` 一期 `E-…`、一期 B 换渠道号，跨期会断 | 属实 | ③ 一期 B 的渠道回报行带 `estimateNo`（被它取代的那条预计行编号），App 可据此把缓存或深链映射过去；同时说明 `E-…` 不保证跨期存活 |
| R16 | reconcile `from / to` 不传时默认本月还是上月 | 技术约定 | ④ 与其他资金接口一致：默认本月 1 日到今天；原型「對帳 · 10 月」由 App 显式传上月起止 |
| R17 | D8：App 入口写死 `if (owner)`，应改按 `can('biz:finance')`；后端确认口径 | 属实：`@perm.canBiz` 判的是权限码，不是角色（`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/PermChecker.java:98-110`），把 `biz:finance` 授给自定义角色的人能调 `/biz/funds/*`；只有 `scope=ENTITY` 另要求 `owner` | 已拍板 D8 写入此口径；App 侧照评审改 |

**没照改的**

| 评审意见 | 不改的理由 |
|---|---|
| 「F06 以 `/biz/merchant/pay-service` 为准」 | 只照改了一半：它是开通状态的真源，但不回账户掩码与账期，单靠它 F06 出不来。所以加了 `/biz/funds/accounts`，而不是让 App 只读 pay-service（R7） |
| 交易号回「可显示的尾号」 | 不另造字段：完整号已在响应里，尾号截几位是展示问题；多一个派生字段就多一处可能与原号不一致（R8） |
| 「未开通渠道 `rateBps:null`」 | 改为仍回费率表里的值、另加 `opened:false`：费率表是按市场的公开牌价，与开没开通无关；回 `null` 反而让店主看不到「开了要付多少」。表里查不到才是 `null`（R5） |
| 跨度「统一成 92 或 93」 | 两个都不取字面值，取后端 `checkRange` 已在执行的规则并写清数法（R4）；另起一个数会让资金页跳转到 `/biz/payment/orders` 时出现「这边能查、那边 10400」 |

### 2.1 契约变更

- **端点**：新增 6 个 `GET /biz/funds/{overview|daily|payouts|reconcile|rates|accounts}`。~~修改 `GET /biz/merchant/payment`（加 `settleCycle`）~~ 评审后撤回（§2.0 R7）。内部接口新增 near-pay `/internal/pay/v1/biz/stores/{storeNo}/funds/{overview|daily|payouts|reconcile|rates|accounts}`（本仓库 `pay-svc` 镜像一份）。
- **库表 / 迁移（ai-shop）**：推荐方案**无**。如果已拍板 D1 选了「ai-shop 自己存渠道拨款」，则要 `V6xx 待定__funds_channel_payout.sql`（新表 `fnd_channel_payout`、`fnd_channel_payout_line`），见 §3 方案 B。
- **库表（near-pay，由 near-pay 侧实现与编号）**：一期 A 无；一期 B 要落 near-pay 已设计的 `pay_channel_bill`、`pay_channel_bill_line`、`pay_reconciliation_run`，另需一张「渠道拨款」表（near-pay 设计里没有，建议名 `pay_channel_payout`，由 near-pay TDD 定），以及 `stl_recon_diff` 加 `store_id`（或在差异生成时冗余）。
- **权限码**：不新增。全部 `/biz/funds/*` 用 `biz:finance`（`BizPerms.FINANCE`，`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java:99`），与 F06 同页同码。默认只有店主（`OWNER` = `*`）有；店长、店员没有（`BizPerms.java:192-203`）。
- **i18n**：新增 1 个错误码 `FUNDS_ENTITY_TOO_MANY_STORES`（`err.pay.funds_entity_too_many_stores`，编号实现时在 `ErrorCode` 支付段 `PAY_*`（700xx）末号后顺延），三语 `messages.properties` / `messages_en.properties` / `messages_ar.properties` 各加一条，文案意思「本主体门店太多，请改看单店」。其余沿用已有：参数不合法 `BAD_REQUEST(10400)`、合并范围越权 `FORBIDDEN(10403)`、未登记 / 无权 70006、支付服务不可用 `RESOURCE_DEPENDENCY_UNAVAILABLE(10505)`（`ErrorCode.java` 中 `10505, "err.resource.dependency_unavailable"`）。
- **配置项**：不新增。`shop.pay.fee.estimate-rate-bps` 语义改为「费率表查不到时的兜底」（已拍板 D5）。
- **门店准入登记**：`ShopStoreMemberRoutes.ALL` 加
  `HostRoute.of("GET", "/biz/funds/(?:overview|daily|payouts|reconcile|rates|accounts)")`。
  不加的后果：新模型门店全部 70006 `CONTACT_ADMIN`（类注释 `ShopStoreMemberRoutes.java:7-12` 记的就是这个坑）。F06 的另一半 `/biz/merchant/pay-service` 不用登记（foundation 通道已放行，`CommerceRoutePolicy.java:129`）。

### 2.2 通用约定（五个新接口共用）

- 头：`Authorization`、`X-Store-No`、`X-Entity-No`（与交接文档 §3 一致）；门店范围取 `BizContext` 当前门店。
- 金额：`long` 最小单位，字段名 `xxxMinor`；币种放顶层 `currency`（按门店市场，台湾 `TWD`），App 按门店 `currencyScale` 换算。**不用** `{currency, minor}` 对象。
  - **TWD 的 `currencyScale` = 2**（评审「最需要先定的」#6）：NT$480 回 `48000`。出处：`sys_market` 台湾行（`V294__sys_market.sql:53`）、门店 `currencyScale` 取自 `sys_market`（`StoreVO.java:54-60`）、商城金额模型固定 2 位（`MoneyPolicy.java:3`）、支付域按币种默认小数位（`ValidationInterceptor.java:45-52`）。台湾实际只收整元，所以 TWD 的 `xxxMinor` 正常都是 100 的倍数；精度 0 只在发给蓝新 / LINE Pay 的那一步换算（`PayChannelProductMarket.java:33`），不出现在任何 `/biz/*` 响应里。
  - 防住什么：App 若按 0 位解释，NT$480 会显示成 NT$48,000；反过来按 0 位**写入**（外部记账）会存成 NT$4.80。
- 日期：`yyyy-MM-dd`，按门店时区的业务日（台湾 `Asia/Taipei`），与 `/biz/payment/orders` 同一口径（`BizDates`）。时间戳为 UTC 毫秒。
- 期间：`from`、`to` 含两端；不传 = 门店时区当月 1 日到今天。**跨度上限：`to − from ≤ 93` 天**，即含两端最多 94 个日历日，超出 10400。
  - 取这个而不是 92 或「含两端 93」：它就是 `/biz/payment/orders*` 正在执行的规则（`MAX_DAYS = 93`，`checkRange` 在 `from.plusDays(93).isBefore(to)` 时才拒，`MerchantPaymentQueryServiceImpl.java:60,483-487`）。资金接口直接调同一个 `checkRange`，F02 / F04 跳到收款明细时两边永远一致。任何连续 3 个整月（最长 92 天）都在范围内。
  - App 只写一条校验：`to.difference(from).inDays <= 93`。外部记账草案（现写「≤ 92 天」）请对齐到这条。
- 费率：万分比整数 `xxxBps`（38 = 0.38%）。
- 枚举一律大写码；App 翻文字。
- 多值参数（`status`、`channel`）：与 `/biz/payment/orders` 相同（`BizPaymentController.java:142-143`，`List<String>`），App 发重复参数 `status=EXPECTED&status=PAID`；逗号分隔服务端也能绑，但不作为契约。
- 可空字段（标 `?` 的）**一律回 `null`，不省略**：App 用同一种判空，`null` 就是「未知 / 不适用」。
- 支付服务不可用：一律 **10505** `RESOURCE_DEPENDENCY_UNAVAILABLE`。`BizFundsController` 接住 `ServiceCallException` 转 10505（与 `BizPayServiceController.java:57-58` 同法），不能像 `/biz/payment/*` 那样落到兜底 10500（§1.5-4）。App 统一显示「暫時無法取得，稍後再試」。
- 渠道只回码 `channel`（如 `NEWEBPAY`、`LINEPAY`），不回名称：App 已有 `channelName()`（ai-shop-bapp `features/merchant/merchant_pages.dart`），名称本地化不进资金接口。

### 2.3 接口逐个

#### ① `GET /biz/funds/overview`（AC1）

| 项 | 内容 |
|---|---|
| 权限 | `biz:finance`；登记 `ShopStoreMemberRoutes`：是 |
| 请求 | query `from?`、`to?`（见 2.2） |
| 响应 | `{ currency, from, to, generatedAt, collected:{count, amountMinor}, refunded:{count, amountMinor}, netMinor, feeMinor, feeEstimated, paidOutMinor, pendingMinor, payoutSource, diffCount, reconcileCoverage, channels:[{ channel, payMethods[], count, collectedMinor, refundedMinor, feeMinor, feeEstimated, paidOutMinor, pendingMinor, settleCycle?, nextPayoutDate? }] }` |
| 口径 | `collected / refunded / feeMinor / feeEstimated` 与 `/biz/payment/orders/summary` 同一查询（按成功时间落在期间内）。`pendingMinor = netMinor − feeMinor − paidOutMinor`。`payoutSource`：`CHANNEL_REPORTED`（有渠道拨款数据）或 `ESTIMATED`（一期：`paidOutMinor` 恒为 0，所有净额都算待拨）。`diffCount` = 本店期间内 `stl_recon_diff.status=PENDING` 条数。`reconcileCoverage`：`CHANNEL_BILL` / `SELF_CHECK_ONLY`。`generatedAt`：UTC 毫秒，本次计算时刻（R1）。`channels[]` = 本店 `ACTIVE` 绑定的渠道 ∪ 期间内有收款的渠道；`payMethods[]` = 期间内该渠道有收款的 `pay_order.pay_method` 原码，按金额倒序，没有收款为空数组（R2）。`settleCycle` 取本店该渠道收款商户的账期，查不到为 `null`；`nextPayoutDate` 为 `null` 表示没有待拨或账期未知（R11）。**`paidOutMinor` / `pendingMinor` 在一期的显示口径已拍板 D10**，本行写的是初稿口径 |
| 错误 | 10400 期间不合法；70006 无权或未登记；支付服务不可用沿用收款接口映射 |

与 App 建议的差异：
| App 建议 | 本文 | 理由 |
|---|---|---|
| `collected{currency,minor}` 等对象，minor 为字符串 | `xxxMinor` 整数 + 顶层 `currency` | 与同模块已在用的 `/biz/payment/orders/summary` 一致；交接文档 §8 待决也说要统一 |
| `count` 顶层 | 并入 `collected.count`，另加 `refunded`、`netMinor` | 与收款汇总同形，App 两处共用解析 |
| `paidOut` / `pending` 只有数字 | 另给 `payoutSource` | 一期「已拨款」没有数据来源，不标的话 0 会被读成「渠道一分没拨」 |
| 无期间参数（「本期」未定义） | `from` / `to`，默认本月 | 「本期」在不同渠道账期下没有统一含义 |
| `channels[].name`、`cycle`（如 `T+3`） | `channel` 码、`settleCycle` 码、`nextPayoutDate` | 名称由 App 本地化；账期给码，下次拨款日由后端按账期算好 |
| 无更新时间（原型有「更新於 09:40」） | `generatedAt` | 评审 R1 |
| 渠道行无支付方式（原型副标「信用卡、感應收款」） | `channels[].payMethods[]` | 评审 R2；App 不必再调 rates 拼 |
| 无 `reconcileCoverage` | 有 | PRD-商家资金到账与对账 AC-6：查不了的那一类要写明 |

#### ② `GET /biz/funds/daily`（AC2、AC12）

| 项 | 内容 |
|---|---|
| 权限 | `biz:finance`；`scope=ENTITY` 另要求 `BizContext.owner()`；登记：是 |
| 请求 | query `month?`（`yyyy-MM`）**或** `from? / to?`（自定义区间，含两端，跨度同 §2.2），两者都不传 = 当月；同时传 `month` 与 `from/to` 回 10400。`scope?`（`STORE` 默认 / `ENTITY`） |
| 响应 | `{ currency, from, to, month?, scope, storeCount, days:[{ date, count, collectedMinor, refundCount, refundedMinor, feeMinor, feeEstimated }] }`，只列有发生额的日子，按日期倒序；`month` 只在按月查时有值，`from / to` 总是回实际生效的起止 |
| 口径 | 收款按成功时间、退款按退款成功时间各自落到门店业务日（只有退款的日子也出一行）。`ENTITY`：当前主体（`X-Entity-No`）名下、`BizContext.storeNos()` 可见的门店逐店调支付服务后按日相加；`storeCount` 是参与相加的门店数 |
| 错误 | 10400 月份 / 日期格式错、晚于今天、跨度超限、`month` 与 `from/to` 同传；10403 `ENTITY` 但不是店主；`FUNDS_ENTITY_TOO_MANY_STORES` `ENTITY` 下门店超过 50 家（R9）；70006 同上；10505 支付服务不可用 |

与 App 建议的差异：
| App 建议 | 本文 | 理由 |
|---|---|---|
| 返回裸数组 | 对象包一层（`currency`、`scope`、`storeCount`、`days[]`） | 币种与范围要随数据回来，否则合并范围下 App 无从判断算了几家店 |
| `day`（展示文字）+ `date` | 只给 `date` | 展示交给 App |
| `amount{…}` | `collectedMinor`，另加 `refundedMinor` | 只有退款笔数没有退款金额，当天净额算不出来 |
| 只有月份（原型 F02 有「自訂」） | `month` 或 `from/to` | 评审 R3 |
| `scope: STORE|MERCHANT` | `STORE|ENTITY` | 账户与账期是「主体 × 渠道」的（`MchPaymentMerchant.java:150-162`），跨主体相加没有对账意义；一个账号可有多个主体（`GET /biz/entities`）。见已拍板 D7 |

#### ③ `GET /biz/funds/payouts`（AC4、AC5）

| 项 | 内容 |
|---|---|
| 权限 | `biz:finance`；登记：是 |
| 请求 | query `status?`（`EXPECTED|PAID|MISMATCH`，可多值，写法见 §2.2）、`channel?`、`from? / to?`（按 `expectedDate` 过滤，含两端，跨度同 §2.2；不传 = 不限期间）、`cursor?`、`size?`（≤50，默认 20） |
| 响应 | `{ currency, payoutSource, items:[{ payoutNo, estimateNo?, channel, status, source, periodFrom, periodTo, expectedDate, paidDate?, count, grossMinor, refundedMinor, feeMinor, feeEstimated, feeRateBps?, netMinor, reportedMinor?, accountMasked }], nextCursor? }`，按 `expectedDate` 倒序 |
| 口径 | **一期（`source=ESTIMATED`）**：按（渠道 × 收款商户 × 预计拨款日）把本店成功收款分组，预计拨款日 = 业务日 + 账期（与收款详情 `expectedDate` 同一函数，`MerchantPaymentQueryServiceImpl.java:459-468`）；`status` 恒为 `EXPECTED`；`payoutNo` 为确定性编号 `E-{channel}-{payMerchantNo 末 6 位}-{yyyyMMdd}`，同一组每次算出来相同。**一期 B（`source=CHANNEL_REPORTED`）**：取渠道回报的拨款行，`reportedMinor` 是渠道实拨，`netMinor ≠ reportedMinor` 时 `status=MISMATCH`；该行的 `estimateNo` = 它取代的那条预计行的 `E-…` 编号（没有对应预计行为 `null`），预计行随之不再返回。App 不应把 `E-…` 当长期标识存，深链与缓存遇到失效的 `E-…` 时按 `estimateNo` 找回（R15）。**F04「查看这期的每一笔交易」**：App 用 `periodFrom / periodTo + channel` 调 `/biz/payment/orders`（`channel` 已支持，`BizPaymentController.java:142`）。门店 × 渠道同时只有一条 `ACTIVE` 收款商户绑定（`V10__pay_merchant_tables.sql:142,153`），所以只有「期间内换绑」时会混入另一个收款商户的单；一期接受，不给 orders 加收款商户过滤（R14）。**预计行的分组粒度与原型 F03 不一致，已拍板 D11** |
| 错误 | 10400 状态值、日期或游标不合法、跨度超限；70006 同上；10505 |

与 App 建议的差异：
| App 建议 | 本文 | 理由 |
|---|---|---|
| 不分页的数组 | 游标分页 | 预计行按天累积，一年数百行；与 `/biz/payment/orders` 同一种游标 |
| `date` 展示文字（「預計 11/10」）、`period` 文字 | `expectedDate`、`paidDate`、`periodFrom`、`periodTo` | 交接文档 §8 待决已建议结构化 |
| `feeRate` 文字（`2.30%`） | `feeRateBps` | 同上 |
| `channelName` | 只给 `channel` | 名称本地化在 App |
| `amount` | `netMinor`（应拨）与 `reportedMinor`（实拨）分开 | MISMATCH 就是这两个数不等，合成一个字段就看不出差在哪 |
| 无 `source` | 有 | 区分「预计」与「渠道回报」，交接文档 8-04「渠道没回传的只显示预计」 |
| — | 不新增详情接口 | 与 App 现状一致：详情就是列表行（8-05） |
| 无期间参数 | `from / to` | 评审 R13：只能倒序翻页，看去年某月要翻很多页 |

#### ④ `GET /biz/funds/reconcile`（AC7）

| 项 | 内容 |
|---|---|
| 权限 | `biz:finance`；登记：是 |
| 请求 | query `from?`、`to?`（不传 = 本月 1 日到今天，与其他资金接口一致；原型「對帳 · 10 月」由 App 显式传上月起止，R16） |
| 响应 | `{ from, to, coverage, platformCount, channelCount?, byChannel:[{ channel, coverage, platformCount, channelCount? }], diffs:[{ diffNo, channel, diffType, source, status, bizDate, occurredAt?, payOrderNo?, channelTxnNo?, platformAmountMinor?, channelAmountMinor? }], currency }` |
| 口径 | `platformCount` = 期间内本店成功收款笔数。`channelCount` 只在有已校验的渠道账单时给，否则 `null`，`coverage=SELF_CHECK_ONLY`。`diffs` 来自 `stl_recon_diff`，只取 `payment_no` 属于本店支付单的；`diffType` 原样回 `CHANNEL_ONLY / PLATFORM_ONLY / AMOUNT_DIFF / REFUND_ABNORMAL / REFUND_LATE_SUCCESS / REFUND_CLOSED`，`status` 回 `PENDING / RESOLVED / IGNORED`（`StlReconDiff.java:23-40`）。`occurredAt`：UTC 毫秒，经 `payment_no` 取我方支付成功时刻；`CHANNEL_ONLY`（我方没这笔）一期为 `null`，一期 B 取渠道账单行时间（R8）。交易号尾号由 App 从 `channelTxnNo`（缺则 `payOrderNo`）截取，后端不另回。只读，App 不处理差异 |
| 错误 | 10400；70006；10505 |

与 App 建议的差异：
| App 建议 | 本文 | 理由 |
|---|---|---|
| `diffs[{title, detail, status}]` 都是展示文字 | 码 + 金额 + 单号 | 交接文档 §8 待决；文字由 App 按码出，三语一致 |
| `channelCount` 恒有值 | 没有渠道账单时 `null` + `coverage` | 不能用「0」或「等于平台笔数」冒充「对上了」（PRD AC-6） |
| 无期间参数 | `from` / `to` | 对账天然按账期 |
| 差异行显示「10/24 13:02」「交易號 …8842」 | `occurredAt` + 完整单号 | 评审 R8 |

#### ⑤ `GET /biz/funds/rates`（AC9）

| 项 | 内容 |
|---|---|
| 权限 | `biz:finance`；登记：是 |
| 请求 | 无 |
| 响应 | `{ items:[{ channel, opened, payMethod, rateBps?, minFeeMinor?, settleCycle?, effectiveFrom?, rateNo?, source }], currency }`；`payMethod` 为 `*` 表示该渠道全部方式 |
| 口径 | 渠道 = 门店市场的全部启用渠道（`PayChannelMasterService#enabled(market)`，`PayChannelMasterService.java:36`；台湾为 `NEWEBPAY`、`LINEPAY`），`opened` = 本店有该渠道 `ACTIVE` 绑定（与 `collect-status.channels` 同源，`MerchantPaymentQueryServiceImpl.java:238-246`）。未开通渠道也出行（R5）：费率照查费率表，查不到时 `rateBps`、`minFeeMinor`、`rateNo`、`effectiveFrom` 为 `null`、`source=UNAVAILABLE`；`settleCycle` 取渠道默认。渠道主数据里没有的（如 `JKOPAY`）后端不回，App 本地补「即将推出」。已开通渠道费率取 `PayChannelRateService#effective(门店市场, channel, payMethod, 主体法律形态, now)`；查不到时 `source=DEFAULT_ESTIMATE`、`rateBps` = 配置兜底值；`settleCycle` 取收款商户账期，没有则取渠道默认（`PayChannelMasterService#settleCycle`，`backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/master/PayChannelMasterService.java:48`） |
| 错误 | 70006；10505 |

与 App 建议的差异：`[{name, rate, cycle}]`（文字）→ 结构化码与万分比；按「渠道 × 支付方式」而不是只按渠道（信用卡与 TWQR 费率不同）；加 `source`（`CHANNEL_RATE` / `DEFAULT_ESTIMATE` / `UNAVAILABLE`），让 App 能标「暂按估算费率」；加 `opened`，F07 的「未開通」行由后端给（评审 R5）。

#### ⑥ `GET /biz/funds/accounts`（AC8，评审后新增；取代初稿「给 `/biz/merchant/payment` 补 `settleCycle`」）

| 项 | 内容 |
|---|---|
| 权限 | `biz:finance`；登记 `ShopStoreMemberRoutes`：是 |
| 请求 | 无 |
| 响应 | `{ items:[{ channel, payMerchantNo, status, settleAccountType?, settleAccountMasked?, settleCycle?, activatedAt? }] }` |
| 口径 | 本店每条 `ACTIVE` 的 `pay_merchant_binding` 一行，字段取所绑 `pay_merchant`（`PayMerchant.java:20,29,62,71,74,77,92`；`activatedAt` 转 UTC 毫秒）；只读，不回明文账号（`pay_merchant` 本就只存掩码）。开通申请状态不在这里，App 另调已有 `GET /biz/merchant/pay-service`（`status / reviewReason`）：一行都没有且 pay-service 为 `PENDING / NEED_MORE / REJECTED` 时显示申请进度，为 `NONE` 时显示开通入口 |
| 错误 | 70006；10505 |

与 App 现状的差异：
| App 现状 | 本文 | 理由 |
|---|---|---|
| 读 `GET /biz/merchant/payment?entityNo=`，固定列 `NEWEBPAY / LINEPAY / JKOPAY` | 读 `/biz/funds/accounts` + `/biz/merchant/pay-service`；未开通渠道的「未開通」行用 rates 的 `opened=false` 补 | `/biz/merchant/payment` 读的是大陆微信 / 支付宝进件档，台湾店没有行，只会回一条 `NONE` 占位（§1.2）；`/biz/payout-account` 是自营供应商出款卡，也不是它 |
| 读 `settleCycle`、`enabledStores` | `settleCycle` 在这里给；`enabledStores` 不给 | 账户按门店绑定查，本身就是「本店」的；跨店共用同一收款商户的展示属于进件模块 |

### 2.4 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/pay/BizFundsController.java` | 6 个端点；只做判权、取门店、`scope=ENTITY` 的门店集合与扇出；接住 `ServiceCallException` 转 10505 |
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java`；`backend/shop-app/src/main/resources/i18n/messages.properties`、`messages_en.properties`、`messages_ar.properties` | 加 `FUNDS_ENTITY_TOO_MANY_STORES` 及三语文案 |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/BizFundsAppService.java` + `impl/BizFundsAppServiceImpl.java`（内嵌）+ `impl/RemoteBizFundsAppService.java`（独立） | 与 `BizPaymentAppService` 同一对条件装配（`shop.pay.deployment`） |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` | 加一行 `HostRoute`（§2.1） |
| 新增 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/acquiring/service/MerchantFundsQueryService.java` + `impl/MerchantFundsQueryServiceImpl.java` | overview / daily / payouts / reconcile / rates / accounts；收款汇总部分与期间校验 `checkRange` 抽公共方法与 `MerchantPaymentQueryServiceImpl` 共用，**不复制 SQL、不另写跨度上限** |
| 修改 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/acquiring/service/impl/MerchantPaymentQueryServiceImpl.java` | 抽出「期间收款 / 退款 / 手续费」公共查询；手续费估算改为先查费率表、再用配置兜底（已拍板 D5 定了才动） |
| 修改 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/biz/PayBizFacade.java` | 暴露 6 个查询 |
| 修改 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/client/PayBizInternalApi.java` | 加 6 个 `@GetExchange(PREFIX + "/funds/…")` |
| 修改 | `backend/pay/pay-svc/src/main/java/ai/neargo/shop/pay/svc/InternalPayBizEndpoint.java` | 镜像端点 |
| ~~修改~~ | ~~`PaymentApplymentVO.java`、`MerchantPaymentServiceImpl.java` 加 `settleCycle`~~ | 评审后撤回（§2.0 R7） |
| 修改 | `docs/api/openapi-b.yaml` | 登记 6 个新端点的契约 |
| 修改 | `known-app-backend-orphan.txt` | 只在闸门判 6 个新端点「端上无出口」时才加（出口在 Flutter 仓库）；先跑 `scripts/check-app-backend-orphan.py` 再定 |
| 新增 / 修改 | 测试，见 §5 | |
| **near-pay 仓库**（不在本仓库提交） | `pay-domain` 同名 `MerchantFundsQueryService`、`PayBizInternalApi`、`InternalPayBizEndpoint`；一期 B 的渠道账单与拨款表与任务 | 由 near-pay 侧 TDD 承接；本文只定 `/internal/pay/v1/biz/stores/{storeNo}/funds/*` 的形状 |

### 2.5 关键接口（会被别人调到的）

```
// pay-domain
interface MerchantFundsQueryService {
  Overview  overview (PayCaller caller, Long storeId, LocalDate from, LocalDate to);
  Daily     daily    (PayCaller caller, Long storeId, LocalDate from, LocalDate to);   // month 由 BFF 换成起止
  PayoutPage payouts (PayCaller caller, Long storeId, PayoutFilter filter);   // filter: statuses, channel, from, to, cursor, size
  Reconcile reconcile(PayCaller caller, Long storeId, LocalDate from, LocalDate to);
  Rates     rates    (PayCaller caller, Long storeId);
  Accounts  accounts (PayCaller caller, Long storeId);
}

// 内部 HTTP（独立形态），PREFIX = /internal/pay/v1/biz/stores/{storeNo}
GET  PREFIX/funds/overview?operatorNo&from&to
GET  PREFIX/funds/daily?operatorNo&from&to
GET  PREFIX/funds/payouts?operatorNo&status&channel&from&to&cursor&size
GET  PREFIX/funds/reconcile?operatorNo&from&to
GET  PREFIX/funds/rates?operatorNo
GET  PREFIX/funds/accounts?operatorNo
```

`scope=ENTITY` 不下沉到支付服务：支付服务只认门店（`PayBizInternalApi` 全部按 `{storeNo}`），「哪些门店属于这个主体、这个人看得见哪些」是 ai-shop 的知识（[TDD-支付服务-接入守卫模块pay-guard](./TDD-支付服务-接入守卫模块pay-guard.md) G9：支付只记、不认人）。

### 2.6 分期

| 期 | 内容 | 前提 |
|---|---|---|
| 一期 A | ①②④⑤⑥ 全部；③ 只出 `ESTIMATED` 行；④ 只有 `SELF_CHECK_ONLY`；① 的已拨 / 待拨显示口径按 D10 拍板结果 | D10、D11 拍板（影响 ①③ 的字段）；near-pay 侧要同步实现查询 |
| 一期 B | ③ 出 `CHANNEL_REPORTED` 行与 `PAID / MISMATCH`；④ 出 `channelCount` 与 `CHANNEL_BILL` 差异；① `paidOutMinor` 有真值 | 蓝新、LINE Pay 回传拨款 / 对账文件（交接文档后端清单 3-3）；near-pay 建渠道账单与拨款表（已拍板 D3） |

App 侧：一期 A 上线即可从 `contracts.dart` 删 `^/biz/funds/`（附录 B）；页面靠 `payoutSource` / `coverage` 显示「预计」「仅平台自查」；F06 改读 `/biz/funds/accounts` + `/biz/merchant/pay-service`。

---

## §3 选型

| 问题 | 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| 资金数据从哪取 | **A. 支付服务（near-pay）为真源，ai-shop 只转发** | 收款、退款、手续费、账期本来就在那；渠道账单在 near-pay 设计里已有位置（FI09）；一份口径 | near-pay 要排期；两仓库同构代码要各写一次（已拍板 D2） | ✅ 采用 |
| | B. ai-shop 自建 `fnd_channel_payout` 等表，自己拉渠道账单 | 不等 near-pay | 违反 ADR-021（支付域独立为服务与独立库）与 ADR-043（进件与收款商户归支付域）；渠道凭据在 near-pay，ai-shop 拉不到账单；两边对账结果会分叉 | ❌ |
| | C. App 自己拼：调 summary 与 31 次 statement | 后端零改动 | 一月 31 次请求；手续费、拨款、对账仍没有；口径在端上散开 | ❌ |
| 一期没有渠道拨款数据时怎么显示 | **A. 按账期估算「预计」行，并带 `source` / `coverage` 标识** | 页面能用；不冒充实拨 | 估算可能与渠道实拨有出入 | ✅ 采用 |
| | B. 一期不上 F03–F05 | 不会误导 | 资金页三屏空着；App 已做好 | ❌（但若产品要求「没真数就不显示」，退回此方案，见已拍板 D3） |
| 权限码 | **A. 复用 `biz:finance`** | 与 F06 同页同码；无迁移 | 店长看不到资金（要看就得给他 `biz:finance`，同时也给了进件权限） | ✅ 采用 |
| | B. 新码 `mch:pay:funds` | 能单独授权店长 | 要迁移、i18n、角色模板改动；交接文档只说「店主、有资金权限者」 | ❌（已拍板 D8 若要给店长，再改为 B） |
| 提现 | **A. 不做** | 台湾模式平台不经手资金，没有可提的余额；PRD §2、§7 已定提现退役 | — | ✅ 采用（已拍板 D4 确认） |

**需要 ADR**（本文不写，建议编号顺延到 ADR-052）：「台湾直连拨款模式下商户资金视图的数据归属」——定死三件事：① 资金视图的真源是支付服务，ai-shop 不存资金数字；② 平台不提供余额与提现；③ 没有渠道回报时只显示「预计」，并强制带覆盖范围标识。理由：这三条一旦让 App 和运营形成习惯就很难收回（尤其是「余额」一旦出现，就会被要求「提现」）。

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 渠道不回传拨款 / 账单，或格式迟迟定不下来 | 「已拨款」永远是 0，「对账」永远只有自查 | `payoutSource` / `coverage` 让页面诚实；一期 B 单独排期，不阻塞一期 A |
| 估算费率与真实费率不符（配置默认 0） | 总览手续费显示 0、待拨偏高 | 已拍板 D5：估算改读费率表；上线前运营在 `sys_pay_channel_rate` 配台湾费率；费率页标 `DEFAULT_ESTIMATE` |
| near-pay 与本仓库 `backend/pay` 两份代码不同步 | 166 上与本地测试结果不同 | 已拍板 D2；`PayBizInternalApiRoundTripTest` 只证明本仓库那份；near-pay 侧要有同名测试 |
| `scope=ENTITY` 扇出 | 门店多时慢（每店一次远程调用） | 一期限主体内门店 ≤ 50（超出回 `FUNDS_ENTITY_TOO_MANY_STORES`，App 提示改按店看）；以后再给支付服务加多门店查询 |
| `stl_recon_diff` 没有门店列 | 按店过滤要反查 `pay_order`，差异多时慢 | 期间上限 `to − from ≤ 93`；一期 B 在 near-pay 给差异表加 `store_id` |
| 「预计拨款日」与渠道真实拨款日不一致（节假日、渠道自定周期） | 商户按预计日去银行查，查不到 | 预计行明确标「预计」；文案由 App 写「以渠道入账为准」 |
| 账期词表不一致（§1.5-2） | 同一个资金 Tab 里账期写法不同：收款商户账期 `T1/T2/WEEKLY`（`MerchantPaymentQueryServiceImpl.java:459-468`），渠道默认账期注释举例 `T+1`（`PayChannelMasterService.java:47-48`） | 已拍板 D6；定之前 App 两套都翻 |
| TWD 精度被端上误解（按 0 位） | 金额差 100 倍，且不报错 | §2.2 写死 `currencyScale = 2`；AC13 往返测试用 NT$480 = 48000 断言 |

---

## §5 对账三 · 实现 → 需求（测试，计划；**均未跑**）

| AC | 测试方法（计划） | 跑过 | 消融验证（计划） |
|---|---|---|---|
| AC1 | `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BizFundsFlowTest#overviewMatchesPaymentSummaryForSamePeriod` | 未跑 | 让 overview 少算退款 → 与 summary 不等，变红 |
| AC1 | `BizFundsFlowTest#overviewWithoutChannelPayoutsIsEstimatedAndAllPending`（方法名与断言按 D10 拍板结果改） | 未跑 | 把 `payoutSource` 写死 `CHANNEL_REPORTED` → 红 |
| AC1 | `BizFundsFlowTest#overviewCarriesGeneratedAtAndPayMethodsPerChannel` | 未跑 | `payMethods` 改从费率表取（而不是期间收款）→ 没收过的方式出现，红 |
| AC2 | `BizFundsFlowTest#dailyGroupsByStoreBusinessDayIncludingRefundOnlyDays` | 未跑 | 退款改按收款日归日 → 红 |
| AC2 | `MerchantFundsQueryServiceImplTest#dailySplitsAtTaipeiMidnightNotUtc` | 未跑 | 用 UTC 切日 → 红 |
| AC2 | `BizFundsFlowTest#dailyAcceptsCustomRangeAndRejectsMonthPlusRange` | 未跑 | 去掉二选一校验 → 同传不报 10400，红 |
| AC2、AC4、AC7 | `BizFundsFlowTest#rangeLimitMatchesPaymentOrders`：`to − from = 93` 通过、`= 94` 回 10400，且同一对起止在 `/biz/payment/orders` 结果相同 | 未跑 | 资金接口另写上限 92 → 与 orders 不一致，红 |
| AC3、AC6 | 无新测试：复用 `BizPaymentOrdersFlowTest#filtersAndCursor`（已有，`backend/shop-app/src/test/java/ai/neargo/shop/scenario/BizPaymentOrdersFlowTest.java:168`） | — | — |
| AC4、AC5 | `BizFundsFlowTest#payoutsEstimatedRowsGroupByChannelMerchantAndExpectedDate` | 未跑 | 分组去掉收款商户 → 两个收款号的钱并成一行，红 |
| AC4 | `BizFundsFlowTest#payoutsCursorDoesNotRepeatOrSkipAcrossPages` | 未跑 | — |
| AC4 | `MerchantFundsQueryServiceImplTest#estimatedPayoutNoIsStableAcrossCalls` | 未跑 | 编号里加时间戳 → 红 |
| AC4 | `BizFundsFlowTest#payoutsFilterByExpectedDateRangeAndRepeatedStatus` | 未跑 | `from/to` 改按业务日过滤 → 红 |
| AC4（一期 B） | `MerchantFundsQueryServiceImplTest#reportedAmountDifferentFromNetIsMismatch` | 未跑 | — |
| AC4（一期 B） | `MerchantFundsQueryServiceImplTest#reportedRowCarriesEstimateNoOfReplacedRow` | 未跑 | 不回 `estimateNo` → 红 |
| AC7 | `BizFundsFlowTest#reconcileShowsOnlyThisStoresDiffsAndSelfCheckCoverage` | 未跑 | 去掉按本店支付单过滤 → 看到别家差异，红 |
| AC7 | `MerchantFundsQueryServiceImplTest#channelCountIsNullWithoutValidatedBill` | 未跑 | 无账单时回 0 → 红 |
| AC7 | `MerchantFundsQueryServiceImplTest#diffOccurredAtFromPaymentAndNullForChannelOnly` | 未跑 | `occurredAt` 取差异生成时间（`created_at`）→ 红 |
| AC8 | `BizFundsFlowTest#accountsListActiveBindingsWithMaskedAccountOnly`（替代初稿的 `MerchantPaymentFlowTest#paymentRowsCarrySettleCycle`） | 未跑 | 改读 `mch_payment_merchant` → 台湾店零行，红；响应里出现明文账号 → 红 |
| AC9 | `BizFundsFlowTest#ratesUseMarketChannelRateAndFallBackToEstimate` | 未跑 | 跳过费率表直接用配置 → 红 |
| AC9 | `MerchantFundsQueryServiceImplTest#overviewFeeEstimateUsesSameRateAsRatesEndpoint` | 未跑 | 两处各取各的 → 红 |
| AC9 | `BizFundsFlowTest#ratesListUnopenedMarketChannelsWithOpenedFalse` | 未跑 | 渠道改回只取已开通 → 少一行，红 |
| AC10 | `BizFundsFlowTest#managerAndClerkGet70006OwnerGetsData` | 未跑 | 去掉 `@PreAuthorize` → 红 |
| AC10 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest`（已有，加 5 条映射） | 未跑 | — |
| AC11 | `backend/shop-app/src/test/java/ai/neargo/shop/commercebridge/StoreScopedDailyRoutesFlowTest#selectedStoreOpensFundsRoutes`（已有类，新方法） | 未跑 | 删掉 `ShopStoreMemberRoutes` 那一行 → 70006 `CONTACT_ADMIN`，红 |
| AC12 | `BizFundsFlowTest#entityScopeSumsVisibleStoresAndRejectsNonOwner` | 未跑 | 去掉 owner 判断 → 店员拿到全主体数据，红 |
| AC12 | `BizFundsFlowTest#entityScopeOver50StoresGetsDedicatedCode` | 未跑 | 改回 10400 → 红 |
| AC10、D8 | `BizFundsFlowTest#customRoleWithFinanceCanReadButNotEntityScope` | 未跑 | `@PreAuthorize` 改判 owner → 自定义角色被拒，红 |
| AC13 | `backend/pay/pay-svc/src/test/java/ai/neargo/shop/pay/svc/PayBizInternalApiRoundTripTest#fundsQueriesRoundTripWithMinorAmounts`（已有类，新方法；台湾门店 NT$480 断言 `collectedMinor = 48000`） | 未跑 | 按 0 位换算 → 480，红 |
| AC14 | `BizFundsFlowTest#payServiceUnreachableIs10505Not10500` | 未跑 | 去掉 `ServiceCallException` 捕获 → 10500，红 |

near-pay 侧要有与 `MerchantFundsQueryServiceImplTest` 同名的测试（不在本仓库）。

## §6 对账二 · 设计 → 实现

未实现，留空。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-08 | 草稿；待「待决」拍板 |
| 2026-11-08 | 按商户 App 评审（TDD评审-商户App.md）修订：概览加 `generatedAt` 与渠道 `payMethods`；每日流水加自定义区间；跨度上限统一为 `to − from ≤ 93`；TWD `currencyScale = 2` 写明；费率列未开通渠道；F06 改读 `/biz/funds/accounts` + `/biz/merchant/pay-service`（撤回给 `/biz/merchant/payment` 补字段）；对账差异加 `occurredAt`；payouts 加期间与 `estimateNo`；新增错误码 `FUNDS_ENTITY_TOO_MANY_STORES`；支付服务不可用固定 10505。产品问题并入「已拍板」（新增 D10、D11）。仍为草稿 |
| 2026-11-08 | 用户按「已拍板」各条建议全部接受，状态改为已确认；ADR 见文首 |

---

## 已拍板

> 2026-11-08 用户按建议全部接受：下列各条一律按「建议」执行；「选项」与「后果」保留作决策记录。

本节原名「待决」。2026-11-08 按商户 App 评审并入两条产品问题（D10、D11），D8 补了后端口径；其余为初稿各条，补写了每个选项的后果。技术问题已在 §2.0 直接改，不在这里。

| # | 问题 | 选项 | 各自后果 | 建议 |
|---|---|---|---|---|
| D1 | **资金数据从哪个服务取？** | A. 支付服务 near-pay 为真源，ai-shop 转发（§3 方案 A）<br>B. ai-shop 自建资金表<br>C. App 自己拼 | A：要 near-pay 排期，两仓库同构代码各写一次（见 D2）<br>B：违反 ADR-021、ADR-043；渠道凭据在 near-pay，ai-shop 拉不到账单；两边对账结果会分叉<br>C：一月 31 次请求；手续费、拨款、对账仍没有；口径散在端上 | **A** |
| D2 | 查询代码写在哪一份？本仓库 `backend/pay`（内嵌与测试）和 near-pay（166 / 生产）是同构的两份 | A. 两边都写<br>B. 只写 near-pay，ai-shop 内嵌实现回 10505<br>C. 先写本仓库，near-pay 照搬 | A：工作量翻倍，但本仓库测试能真实覆盖<br>B：本仓库 `BizFundsFlowTest` 只能测到 10505，要改成打 near-pay 的契约测试；商户在生产看不到 10505（生产只有独立形态）<br>C：near-pay 晚一拍，166 上一段时间是空接口 | **A**；若已决定本仓库 `backend/pay` 冻结只留测试，改 B |
| D3 | **一期是否等渠道拨款 / 账单？** 谁来建渠道账单与拨款表？ | A. 一期 A 先上（只有预计与自查），一期 B 等渠道<br>B. 等渠道回传后整体上<br>C. 一期不上 F03–F05 | A：页面能用，但「已拨」「渠道笔数」一期没有真数，全靠 `payoutSource` / `coverage` 标明（见 D10）<br>B：资金页上线时间取决于蓝新、LINE Pay 是否提供拨款 / 对账文件，目前未知<br>C：资金页三屏空着，App 已做好的页面闲置 | **A**；渠道账单与拨款表由 near-pay 建（S1 台湾首期已排 FI09）；先问蓝新、LINE Pay 是否提供文件（交接文档清单 3-3） |
| D4 | **提现是否做？** | A. 不做<br>B. 做 | A：与 PRD-商家资金到账与对账 §2、§7 一致；App 本来无提现页<br>B：台湾模式平台没有商户余额，「向平台提现」即二清，合规上不成立 | **A，且是这个模式下不做**；若产品指「渠道侧提领」，那是商户在渠道后台的操作，App 只给说明或外链 |
| D5 | 手续费估算用哪个费率？ | A. 费率表 `sys_pay_channel_rate`（按市场），查不到再用配置<br>B. 维持配置 `shop.pay.fee.estimate-rate-bps`（默认 0） | A：F01 手续费与 F07 费率一致；已有收款汇总与详情里 `feeEstimated=true` 的数字会变，要通知 App<br>B：配置默认 0，F01 手续费显示 0、待拨偏高；F07 显示的费率与 F01 算出来的对不上 | **A**；上线前运营在 `sys_pay_channel_rate` 配台湾费率 |
| D6 | 账期码统一成哪套？（评审也问「码全集何时定」） | A. 以支付服务为准，扩成 `T1…Tn / WEEKLY / SEMI_MONTHLY / MONTHLY`<br>B. 以 ai-shop `T+1 / T+N / WEEKLY / SEMI_MONTHLY / MONTHLY` 为准 | A：资金接口不用改；ai-shop 进件档要做一次映射；渠道默认账期（`PayChannelMasterService#settleCycle` 注释举例 `T+1`）也要改<br>B：支付服务 `pay_merchant.settle_cycle` 与 `expectedDate` 函数（`MerchantPaymentQueryServiceImpl.java:459-468`）要改；`T+N` 不带天数，算不出预计拨款日<br>不定：App 两套都翻，直到统一 | **A**；全集要等蓝新、LINE Pay 的实际拨款周期确认后定 |
| D7 | 「整个商户」的范围：主体还是账号下全部主体？ | A. 当前主体（`ENTITY`）<br>B. 账号下全部主体 | A：App 要把 `MERCHANT` 改 `ENTITY`；多主体店主要切主体分别看<br>B：账户与账期按「主体 × 渠道」，跨主体相加的数字没有对账意义，币种也可能不同 | **A** |
| D8 | 店长要不要看资金？ | A. 只看权限码 `biz:finance`（默认只有店主；店主可把它授给自定义角色）<br>B. 新码 `mch:pay:funds` 默认给店长 | A：后端口径已核对：`/biz/funds/*` 判的是权限码不是角色（`PermChecker.java:98-110`），持 `biz:finance` 的自定义角色能看单店数据，但 `scope=ENTITY` 仍只给店主；App 入口要从 `if (owner)` 改为 `can('biz:finance')`，「全部门店」chip 仍只给店主。缺点：给店长 `biz:finance` 会同时给他进件权限<br>B：多一个迁移 `V6xx 待定`、三语权限名、角色模板 | **A**，与交接文档「店主、有资金权限者」一致 |
| D9 | 一期 A 的「预计拨款」行要不要上？ | A. 上，标「预计」<br>B. 不上，等真数 | A：F03 有内容，但「预计」可能被商户当承诺；预计日遇节假日 / 渠道自定周期会不准<br>B：F03 显示「渠道尚未回报」空态，直到一期 B | **A**；若法务或产品担心被当承诺，退回 B |
| D10 | **一期总览的「已拨款 / 待拨款」怎么显示？**（评审「最需要先定的」#5）初稿：`paidOutMinor` 恒 0，`pendingMinor` = 本期全部净额 − 手续费 | A. 维持初稿，靠 `payoutSource=ESTIMATED` 让 App 把「已拨」显示成「渠道未回报」<br>B. 按预计拨款日拆：`pendingMinor` 只算预计日 ≥ 今天的；已过预计日的另给 `estimatedPaidOutMinor`，App 显示「预计已拨（未经渠道确认）」；`paidOutMinor` 仍只放渠道回报值<br>C. 一期不显示「已拨 / 待拨」两格，只给收款、手续费与 `nextPayoutDate` | A：「待拨」= 本月全部净额，T+1 的店月底看到的「待拨」几乎是整月收入，店主会以为渠道一分没拨<br>B：多一个字段 `estimatedPaidOutMinor`（overview 顶层与 `channels[]`）；渠道真实延迟时会显示「预计已拨」而钱没到，文案要写「以渠道入账为准」；一期 B 有真数后该字段变 0 或退场<br>C：最不会误导，但 F01 少两格，原型要改，店主看不到「还有多少没到」 | **B**。它与 F03 的预计行同一套预计日，数字能对上；并在 F01 标「预计」 |
| D11 | **预计拨款行的粒度与原型 F03 不一致**：后端按（渠道 × 收款商户 × 预计拨款日）分组，T+1 / T+3 日结时一天一行；原型 F03 是「11/04–11/06 預計 11/10」的期间行 | A. 后端维持按预计拨款日分组，原型 F03 改画（WEEKLY 等多日一拨的账期自然就是期间行）<br>B. 后端按自然周 / 账期把多个预计日合成一行<br>C. 后端按日，App 端按周折叠显示 | A：一行 = 渠道的一笔拨款，一期 B 与渠道实拨一一对应；日结的店一个月约 30 行（已有游标分页与 `from/to`）<br>B：一行对应多笔实际拨款，一期 B 对账时要拆；「预计日」变成区间，`expectedDate` 语义变<br>C：契约不动，App 多做一层分组，详情页要能展开多笔 | **A**，必要时叠加 C |
