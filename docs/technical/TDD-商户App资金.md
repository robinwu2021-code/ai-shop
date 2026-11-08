# TDD-商户App资金

状态：草稿（2026-11-08，待产品 / 用户拍板「待决」各条后再定稿；未实现、测试未跑）
档位：2 · 依据：跨服务（资金数据的真源在独立支付服务 near-pay，要它新增查询；渠道拨款与渠道账单在 near-pay 是待建表族）+ 跨端（商户 App 契约）。**需要另开 ADR**（本文不写，见 §3 末）。
关联需求：
- 商户 App 交接文档《商户 App 功能矩阵与 API 交接文档》第 8 模块「资金」、§3 通用约定、附录 B `/biz/funds/` 行（ai-shop 副本 `docs/交接-商户App功能矩阵与API.md`，复制自 ai-shop-bapp `main` `31a56c1`）；原型 v11 F01–F07；App 代码 ai-shop-bapp `apps/merchant_app/lib/features/funds/funds_pages.dart`
- [PRD-商家资金到账与对账](../requirements/PRD-商家资金到账与对账.md) §3.3 AC-6（对账要写明覆盖范围）、§4 与 §7（不给商家做「向平台提现」）
- [TDD-B端收款订单与商城接口对齐](./TDD-B端收款订单与商城接口对齐.md)（`/biz/payment/orders*` 的契约与口径，本文复用）

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
| F06 收款账户 | `GET /biz/merchant/payment?entityNo=` | **复用**（这就是交接文档说的「F06 后端已有」）；建议补一个字段 `settleCycle` | ai-shop `mch_payment_merchant` |
| F07 渠道费率与周期 | `GET /biz/funds/rates` | **新增** | 支付服务 `sys_pay_channel_rate` + 渠道 / 收款商户的账期 |

调用链（独立部署形态，166 与生产）：App → shop-app `BizFundsController`（`biz:finance`、门店上下文）→ `BizFundsAppService`（远程实现）→ near-pay `/internal/pay/v1/biz/stores/{storeNo}/funds/*` → near-pay pay 域查询。
内嵌形态（本仓库测试、`shop.pay.deployment=embedded`）走本仓库 `backend/pay` 的同名查询。两处要实现同一份查询（见待决 D2）。

---

## §0 对账一 · 需求 → 设计

需求按交接文档第 8 模块的 9 行功能矩阵逐条整理成 AC，再补 4 条横向 AC（权限、门店登记、覆盖范围、合并范围）。

| AC | 需求（Given / When / Then） | 落点 |
|---|---|---|
| AC1（8-01） | Given 店主选了一家门店 When 打开资金总览 Then 看到所选期间（默认本月）的收款笔数与金额、退款、手续费（标是否估算）、已拨款、待拨款、对账差异数，以及按渠道的收款 / 待拨 / 账期 | 新 `GET /biz/funds/overview`；pay 域新 `MerchantFundsQueryService#overview` |
| AC2（8-02） | Given 一个月份 When 看每日流水（本店或整个主体）Then 每个有发生额的业务日一行：收款笔数、收款金额、退款笔数、退款金额、手续费（标估算）；日期按门店时区切 | 新 `GET /biz/funds/daily`；`MerchantFundsQueryService#daily`；`scope=ENTITY` 由 shop-app 按门店扇出后相加 |
| AC3（8-03） | Given 每日流水某一天 When 点进去 Then 列出当天收款明细并可进收款详情 | **复用** `GET /biz/payment/orders?from=D&to=D`（`BizPaymentController.java:137-151`），后端不改 |
| AC4（8-04） | Given 拨款记录页 When 按渠道或状态筛选 Then 每条拨款一行，状态 `EXPECTED / PAID / MISMATCH`；渠道没回报的只出「预计」，并且明确标出是预计 | 新 `GET /biz/funds/payouts`；`MerchantFundsQueryService#payouts`；一期只有 `source=ESTIMATED` 行（AC4 的 PAID / MISMATCH 依赖待决 D3） |
| AC5（8-05） | Given 一条拨款 When 看详情 Then 有收款总额、退款、手续费、实拨、账户掩码、费率、笔数、期间起止 | 同 AC4 的行字段（不另开详情接口，与 App 现状一致） |
| AC6（8-06） | Given 一条拨款 When 看期间内的交易 Then 列出该期间收款 | **复用** `GET /biz/payment/orders?from=periodFrom&to=periodTo`，后端不改 |
| AC7（8-07） | Given 对账页 When 打开 Then 有平台笔数、渠道笔数、差异列表；**看不到的那一类要写明看不到**（没有渠道账单时不能显示「没有差异」） | 新 `GET /biz/funds/reconcile`；响应带 `coverage`；差异来自 `stl_recon_diff` 按本店支付单过滤 |
| AC8（8-08） | Given 收款账户页 When 打开 Then 按渠道列出收款账户掩码、状态、账期（只读） | **复用** `GET /biz/merchant/payment`（`BizMerchantController.java:733-740`）；建议给 `PaymentApplymentVO` 补 `settleCycle`（App 已在读，后端没回） |
| AC9（8-09） | Given 费率页 When 打开 Then 每个渠道 × 支付方式一行：费率（万分比）、最低手续费、账期、生效时间；**与总览里估算手续费用的是同一个费率** | 新 `GET /biz/funds/rates`；`MerchantFundsQueryService#rates`，读 `PayChannelRateService#effective` |
| AC10（横向） | Given 店长、店员或其他没有 `biz:finance` 的身份 When 调任何 `/biz/funds/*` Then 70006（无 nextAction）；店主可调 | `@PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")`，与 F06 同一个码 |
| AC11（横向） | Given 新模型门店 When 店主调 `/biz/funds/*` Then 不被 70006 `CONTACT_ADMIN` 拒 | `ShopStoreMemberRoutes.ALL` 加一行 |
| AC12（横向） | Given `scope=ENTITY` When 调用者不是该主体的店主 Then 10403；是店主时只汇总该主体名下、他可见的门店 | `BizFundsController#daily` 判 `BizContext.owner()`，门店集合取 `BizContext.storeNos()` ∩ 主体门店 |
| AC13（横向） | Given 金额 When 任一 `/biz/funds/*` 返回 Then 一律整数最小单位 `xxxMinor` + 顶层 `currency`；日期、费率、状态一律结构化，不回展示文字 | §2 契约 |

**孤立项**
- 没落点的 AC：
  - AC4 / AC5 里的 **`PAID` 与 `MISMATCH`**、AC7 里的**渠道笔数与渠道侧差异**：要渠道回报拨款与账单。ai-shop 和 near-pay 都没有这份数据（见 §1「后端没有」）。一期这两块只能给「预计」与「平台自查」，并在响应里标出来。是否等它、谁来建，见待决 D3。
  - AC1 的「差异数」一期只计平台自查差异。
- 挂不上 AC 的设计：
  - `GET /biz/funds/payouts` 的游标分页：App 现在按不分页的数组读。分页挂在 AC4 上（拨款按天累积，一年几百行），算对 App 的契约变更，见 §2 差异表。
  - F06 补 `settleCycle`：挂 AC8；严格说是进件模块（第 4 模块）的字段，放在这里是因为资金页是它唯一的读者。

---

## §1 现状与影响面

### 1.1 资金数据到底在哪

| 数据 | 真源 | 出处 |
|---|---|---|
| 收款、退款、渠道手续费（已对账的实收值）、预计入账日、结算账户掩码 | **支付服务** pay 域：`pay_order`、`pay_refund`、`stl_payment.channel_fee_minor`、`pay_merchant.settle_cycle / settle_account_masked`、`pay_ledger_voucher`（`SETTLEMENT` 凭证判「已结算」） | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/acquiring/service/impl/MerchantPaymentQueryServiceImpl.java:160-196`（汇总）、`:446-468`（已结算判定、预计入账日） |
| 166 / 生产用的是哪一份 | **独立服务 near-pay**：`shop.pay.deployment=standalone` 时 shop-app 走 `RemoteBizPaymentAppService` → `/internal/pay/v1/biz/stores/{storeNo}/**` | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/impl/RemoteBizPaymentAppService.java:45`；`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/client/PayBizInternalApi.java:33`；166 走 near-pay 的证据见 TDD-B端收款订单与商城接口对齐「补记 · 2026-11-06」；near-pay 仓库有同名端点 `near-pay/backend/pay/pay-svc/src/main/java/ai/neargo/shop/pay/svc/InternalPayBizEndpoint.java` |
| 本仓库 `backend/pay` | 内嵌形态（默认）与测试用；与 near-pay 是同包名的两份代码 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/impl/BizPaymentAppServiceImpl.java:27`（`matchIfMissing = true`） |
| 渠道费率 | 支付服务 `sys_pay_channel_rate`（市场 × 渠道 × 支付方式 × 主体形态，分版本） | `backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/master/PayChannelRateService.java`（`effective(market, payChannel, payMethod, legalForm, at)`）；near-pay 同名类在 `near-pay/backend/pay/pay-channel/.../master/PayChannelRateService.java` |
| 收款账户（F06） | **ai-shop** `mch_payment_merchant`（shop-merchant） | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/entity/MchPaymentMerchant.java:22`；读口 `MerchantPaymentServiceImpl` |
| 渠道拨款记录、渠道账单 | **后端没有**（两边都没有）。near-pay 的设计里是待建表：`pay_channel_bill`、`pay_channel_bill_line`、`pay_reconciliation_run`、`pay_settlement_rule_revision`，状态「拟新增，待实现」，批次「S1 台湾首期：渠道账单与对账」 | `near-pay/docs/database/design-v2/00-数据库表清单.md:237-243`；`near-pay/docs/database/design-v2/09-清分结算出款与渠道对账.md`；运营端 `/pay-ops/v2/channel-bills` 状态「设计」、两家账单格式待联调：`near-pay/docs/api/handbook/07-S1台湾接口切片.md:28,37` |
| 渠道账单下载能力 | **后端没有**真实实现：本仓库 `StatementCapability` 无实现类；near-pay 只有 mockpay 实现 | `backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/capability/StatementCapability.java`；`near-pay/backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/mockpay/MockpayChannel.java` |
| 对账差异 | 支付服务 `stl_recon_diff`，有 `source = SELF_CHECK / CHANNEL_BILL` 区分；**没有门店列**，要经 `payment_no` 回到 `pay_order.store_id` 才能按店过滤 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entity/StlReconDiff.java:23-40,48-64` |

**结论**：除 F06 外，资金页的每一个数字都在支付服务里；ai-shop 不应建资金表，也不应自己算。

### 1.2 能直接复用 / 映射的已有接口

| App 建议接口或用途 | 映射到已有接口 | 能不能直接用 | 出处 |
|---|---|---|---|
| F06 收款账户 | `GET /biz/merchant/payment?entityNo=`（`biz:finance`） | **能**。门店准入走 foundation 通道，不需要登记 `ShopStoreMemberRoutes` | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizMerchantController.java:733-740`；`backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java:125` |
| F02 / F04 明细 | `GET /biz/payment/orders?from&to`（`mch:pay:order:view`） | **能**，App 已经这么做 | `BizPaymentController.java:137-151` |
| F01 的收款 / 退款 / 手续费 / 预计入账 | `GET /biz/payment/orders/summary?from&to`（`mch:pay:statement`） | **部分能**：总数都对；但 `byChannel` 没有手续费与待拨，`expectedSettlement` 只取最后一笔的收款商户、只给一个日期，权限码也不是 `biz:finance`。新接口内部复用同一套查询，不让 App 拼两次 | `MerchantPaymentQueryServiceImpl.java:160-196`；`MerchantPaymentQueryService.java:64-69` |
| F02 每日流水 | `GET /biz/payment/statement?day=` | **不能**：只给单日、明细上限 1000 条、不分页；一个月要调 31 次。交接文档说「结构待查」—— 结构是 `DailyStatement{bizDate, currency, paidCount, paidMinor, refundCount, refundMinor, items[]}`，没有手续费 | `BizPaymentController.java:129-135`；`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/acquiring/service/PayStatementService.java:15-22`；`PayStatementServiceImpl.java:34`（`MAX_ITEMS = 1000`） |
| F07 费率 | `GET /biz/settle/rate-card` | **不能**：那是平台佣金（`merchantOwnedRate / platformRate`），不是渠道费率 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/dto/RateCardVO.java` |
| F03 拨款 | `/biz/settle/bills`、`/biz/settle/batch`、`stl_payout` | **不能**：大陆商城的平台抽佣结算与平台放款（批次 × 收款号的网银转账），台湾模式里平台不放款。交接文档已判「不是同一件事」，代码印证 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/pay/BizSettleController.java:53-119`；`backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entity/StlPayout.java` 类注释 |
| 余额 / 提现 | `/biz/deposit*`（保证金）、`/biz/merchant/debt`（欠款）、`stl_withdraw` | **不复用、不做**：保证金和欠款是大陆入驻模型；提现已按 PRD §7 退役 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/api/biz/BizDepositController.java:33-41`；`BizMerchantController.java:763-772`；PRD-商家资金到账与对账 §7 |
| 报表 | `/biz/report/daily|monthly` | **不能**：是经营报表（`biz:customer`），口径是订单不是渠道收款 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizReportController.java:47-82` |

### 1.3 会被改到的

- `BizMerchantController#payments` 的返回 `PaymentApplymentVO`（补 `settleCycle`）：调用方有 b-app、商户 App、`MerchantPaymentFlowTest`、`MultiEntityIdentityFlowTest`。加字段不破坏旧读者。
- `PayBizInternalApi` / `InternalPayBizEndpoint`（本仓库与 near-pay 各一份）：只加方法，不改已有。

### 1.4 明确不受影响

- `/biz/payment/*` 全部已有端点与口径（只被复用，不改）。
- `/biz/settle/*`、`/biz/deposit*`、`/biz/merchant/debt`、`stl_withdraw`。
- 运营端支付页、对账任务 `ReconScanJob` 本身（只读它的产物）。
- 权限码表与角色模板（不新增码）。

### 1.5 发现的不一致（顺手记下，影响本设计）

1. **手续费估算两套来源**：收款汇总用配置 `shop.pay.fee.estimate-rate-bps`（默认 0，`MerchantPaymentQueryServiceImpl.java:89,470-472`），而费率表 `sys_pay_channel_rate` 有按市场的分版本费率却没人用来估算。如果 F07 显示费率表、F01 用配置估算，两页对不上。本设计让两者都改用费率表、配置只兜底（待决 D5）。
2. **账期两套词表**：`pay_merchant.settle_cycle` 是 `T1 / T2 / WEEKLY`（`MerchantPaymentQueryServiceImpl.java:459-468`）；`mch_payment_merchant.settle_cycle` 注释是 `T+1 / T+N / WEEKLY / SEMI_MONTHLY / MONTHLY`（`MchPaymentMerchant.java:150-162`）。资金接口统一回支付服务的值，F06 补字段时照原样回 ai-shop 的值，App 两边都按「码 → 文字」翻；词表要合一（待决 D6）。
3. **F06 的 `settleCycle`、`enabledStores`** App 在读（ai-shop-bapp `features/merchant/merchant_api.dart:87,90`），`PaymentApplymentVO` 没有（`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/PaymentApplymentVO.java:37-42`）。交接文档把它标「已有」，实际是「接口已有、这两个字段后端没有」。本文只补 `settleCycle`；`enabledStores` 属于进件模块，不在本文。

---

## §2 方案

### 2.1 契约变更

- **端点**：新增 5 个 `GET /biz/funds/{overview|daily|payouts|reconcile|rates}`；修改 1 个 `GET /biz/merchant/payment`（加字段 `settleCycle`）。内部接口新增 near-pay `/internal/pay/v1/biz/stores/{storeNo}/funds/{overview|daily|payouts|reconcile|rates}`（本仓库 `pay-svc` 镜像一份）。
- **库表 / 迁移（ai-shop）**：推荐方案**无**。如果待决 D1 选了「ai-shop 自己存渠道拨款」，则要 `V6xx 待定__funds_channel_payout.sql`（新表 `fnd_channel_payout`、`fnd_channel_payout_line`），见 §3 方案 B。
- **库表（near-pay，由 near-pay 侧实现与编号）**：一期 A 无；一期 B 要落 near-pay 已设计的 `pay_channel_bill`、`pay_channel_bill_line`、`pay_reconciliation_run`，另需一张「渠道拨款」表（near-pay 设计里没有，建议名 `pay_channel_payout`，由 near-pay TDD 定），以及 `stl_recon_diff` 加 `store_id`（或在差异生成时冗余）。
- **权限码**：不新增。全部 `/biz/funds/*` 用 `biz:finance`（`BizPerms.FINANCE`，`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java:99`），与 F06 同页同码。默认只有店主（`OWNER` = `*`）有；店长、店员没有（`BizPerms.java:192-203`）。
- **i18n**：不新增错误码。参数不合法 `BAD_REQUEST(10400)`、合并范围越权 `FORBIDDEN(10403)`、未登记 / 无权 70006（框架已有）、支付服务不可用沿用收款接口的远程错误映射（`RemoteBizPaymentAppService`）。
- **配置项**：不新增。`shop.pay.fee.estimate-rate-bps` 语义改为「费率表查不到时的兜底」（待决 D5）。
- **门店准入登记**：`ShopStoreMemberRoutes.ALL` 加
  `HostRoute.of("GET", "/biz/funds/(?:overview|daily|payouts|reconcile|rates)")`。
  不加的后果：新模型门店全部 70006 `CONTACT_ADMIN`（类注释 `ShopStoreMemberRoutes.java:7-12` 记的就是这个坑）。F06 不用登记（foundation 通道已放行）。

### 2.2 通用约定（五个新接口共用）

- 头：`Authorization`、`X-Store-No`、`X-Entity-No`（与交接文档 §3 一致）；门店范围取 `BizContext` 当前门店。
- 金额：`long` 最小单位，字段名 `xxxMinor`；币种放顶层 `currency`（按门店市场，台湾 `TWD`），App 按门店 `currencyScale` 换算。**不用** `{currency, minor}` 对象。
- 日期：`yyyy-MM-dd`，按门店时区的业务日（台湾 `Asia/Taipei`），与 `/biz/payment/orders` 同一口径（`BizDates`）。时间戳为 UTC 毫秒。
- 期间：`from`、`to` 含两端；不传 = 门店时区当月 1 日到今天；跨度上限 93 天（与收款汇总 `MAX_DAYS` 一致，`MerchantPaymentQueryServiceImpl.java:60`），超出 10400。
- 费率：万分比整数 `xxxBps`（38 = 0.38%）。
- 枚举一律大写码；App 翻文字。
- 渠道只回码 `channel`（如 `NEWEBPAY`、`LINEPAY`），不回名称：App 已有 `channelName()`（ai-shop-bapp `features/merchant/merchant_pages.dart`），名称本地化不进资金接口。

### 2.3 接口逐个

#### ① `GET /biz/funds/overview`（AC1）

| 项 | 内容 |
|---|---|
| 权限 | `biz:finance`；登记 `ShopStoreMemberRoutes`：是 |
| 请求 | query `from?`、`to?`（见 2.2） |
| 响应 | `{ currency, from, to, collected:{count, amountMinor}, refunded:{count, amountMinor}, netMinor, feeMinor, feeEstimated, paidOutMinor, pendingMinor, payoutSource, diffCount, reconcileCoverage, channels:[{ channel, count, collectedMinor, refundedMinor, feeMinor, feeEstimated, paidOutMinor, pendingMinor, settleCycle, nextPayoutDate }] }` |
| 口径 | `collected / refunded / feeMinor / feeEstimated` 与 `/biz/payment/orders/summary` 同一查询（按成功时间落在期间内）。`pendingMinor = netMinor − feeMinor − paidOutMinor`。`payoutSource`：`CHANNEL_REPORTED`（有渠道拨款数据）或 `ESTIMATED`（一期：`paidOutMinor` 恒为 0，所有净额都算待拨）。`diffCount` = 本店期间内 `stl_recon_diff.status=PENDING` 条数。`reconcileCoverage`：`CHANNEL_BILL` / `SELF_CHECK_ONLY` |
| 错误 | 10400 期间不合法；70006 无权或未登记；支付服务不可用沿用收款接口映射 |

与 App 建议的差异：
| App 建议 | 本文 | 理由 |
|---|---|---|
| `collected{currency,minor}` 等对象，minor 为字符串 | `xxxMinor` 整数 + 顶层 `currency` | 与同模块已在用的 `/biz/payment/orders/summary` 一致；交接文档 §8 待决也说要统一 |
| `count` 顶层 | 并入 `collected.count`，另加 `refunded`、`netMinor` | 与收款汇总同形，App 两处共用解析 |
| `paidOut` / `pending` 只有数字 | 另给 `payoutSource` | 一期「已拨款」没有数据来源，不标的话 0 会被读成「渠道一分没拨」 |
| 无期间参数（「本期」未定义） | `from` / `to`，默认本月 | 「本期」在不同渠道账期下没有统一含义 |
| `channels[].name`、`cycle`（如 `T+3`） | `channel` 码、`settleCycle` 码、`nextPayoutDate` | 名称由 App 本地化；账期给码，下次拨款日由后端按账期算好 |
| 无 `reconcileCoverage` | 有 | PRD-商家资金到账与对账 AC-6：查不了的那一类要写明 |

#### ② `GET /biz/funds/daily`（AC2、AC12）

| 项 | 内容 |
|---|---|
| 权限 | `biz:finance`；`scope=ENTITY` 另要求 `BizContext.owner()`；登记：是 |
| 请求 | query `month?`（`yyyy-MM`，默认当月）、`scope?`（`STORE` 默认 / `ENTITY`） |
| 响应 | `{ currency, month, scope, storeCount, days:[{ date, count, collectedMinor, refundCount, refundedMinor, feeMinor, feeEstimated }] }`，只列有发生额的日子，按日期倒序 |
| 口径 | 收款按成功时间、退款按退款成功时间各自落到门店业务日（只有退款的日子也出一行）。`ENTITY`：当前主体（`X-Entity-No`）名下、`BizContext.storeNos()` 可见的门店逐店调支付服务后按日相加；`storeCount` 是参与相加的门店数 |
| 错误 | 10400 月份格式错或晚于本月；10403 `ENTITY` 但不是店主；70006 同上 |

与 App 建议的差异：
| App 建议 | 本文 | 理由 |
|---|---|---|
| 返回裸数组 | 对象包一层（`currency`、`scope`、`storeCount`、`days[]`） | 币种与范围要随数据回来，否则合并范围下 App 无从判断算了几家店 |
| `day`（展示文字）+ `date` | 只给 `date` | 展示交给 App |
| `amount{…}` | `collectedMinor`，另加 `refundedMinor` | 只有退款笔数没有退款金额，当天净额算不出来 |
| `scope: STORE|MERCHANT` | `STORE|ENTITY` | 账户与账期是「主体 × 渠道」的（`MchPaymentMerchant.java:150-162`），跨主体相加没有对账意义；一个账号可有多个主体（`GET /biz/entities`）。见待决 D7 |

#### ③ `GET /biz/funds/payouts`（AC4、AC5）

| 项 | 内容 |
|---|---|
| 权限 | `biz:finance`；登记：是 |
| 请求 | query `status?`（`EXPECTED|PAID|MISMATCH`，可多值）、`channel?`、`cursor?`、`size?`（≤50，默认 20） |
| 响应 | `{ currency, payoutSource, items:[{ payoutNo, channel, status, source, periodFrom, periodTo, expectedDate, paidDate?, count, grossMinor, refundedMinor, feeMinor, feeEstimated, feeRateBps?, netMinor, reportedMinor?, accountMasked }], nextCursor }`，按 `expectedDate` 倒序 |
| 口径 | **一期（`source=ESTIMATED`）**：按（渠道 × 收款商户 × 预计拨款日）把本店成功收款分组，预计拨款日 = 业务日 + 账期（与收款详情 `expectedDate` 同一函数，`MerchantPaymentQueryServiceImpl.java:459-468`）；`status` 恒为 `EXPECTED`；`payoutNo` 为确定性编号 `E-{channel}-{payMerchantNo 末 6 位}-{yyyyMMdd}`，同一组每次算出来相同。**一期 B（`source=CHANNEL_REPORTED`）**：取渠道回报的拨款行，`reportedMinor` 是渠道实拨，`netMinor ≠ reportedMinor` 时 `status=MISMATCH` |
| 错误 | 10400 状态值或游标不合法；70006 同上 |

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

#### ④ `GET /biz/funds/reconcile`（AC7）

| 项 | 内容 |
|---|---|
| 权限 | `biz:finance`；登记：是 |
| 请求 | query `from?`、`to?` |
| 响应 | `{ from, to, coverage, platformCount, channelCount?, byChannel:[{ channel, coverage, platformCount, channelCount? }], diffs:[{ diffNo, channel, diffType, source, status, bizDate, payOrderNo?, channelTxnNo?, platformAmountMinor?, channelAmountMinor? }], currency }` |
| 口径 | `platformCount` = 期间内本店成功收款笔数。`channelCount` 只在有已校验的渠道账单时给，否则 `null`，`coverage=SELF_CHECK_ONLY`。`diffs` 来自 `stl_recon_diff`，只取 `payment_no` 属于本店支付单的；`diffType` 原样回 `CHANNEL_ONLY / PLATFORM_ONLY / AMOUNT_DIFF / REFUND_ABNORMAL / REFUND_LATE_SUCCESS / REFUND_CLOSED`，`status` 回 `PENDING / RESOLVED / IGNORED`（`StlReconDiff.java:23-40`）。只读，App 不处理差异 |
| 错误 | 10400；70006 |

与 App 建议的差异：
| App 建议 | 本文 | 理由 |
|---|---|---|
| `diffs[{title, detail, status}]` 都是展示文字 | 码 + 金额 + 单号 | 交接文档 §8 待决；文字由 App 按码出，三语一致 |
| `channelCount` 恒有值 | 没有渠道账单时 `null` + `coverage` | 不能用「0」或「等于平台笔数」冒充「对上了」（PRD AC-6） |
| 无期间参数 | `from` / `to` | 对账天然按账期 |

#### ⑤ `GET /biz/funds/rates`（AC9）

| 项 | 内容 |
|---|---|
| 权限 | `biz:finance`；登记：是 |
| 请求 | 无 |
| 响应 | `{ items:[{ channel, payMethod, rateBps, minFeeMinor, settleCycle, effectiveFrom, rateNo, source }], currency }`；`payMethod` 为 `*` 表示该渠道全部方式 |
| 口径 | 渠道 = 本店已开通（`collect-status.channels`）的渠道；费率取 `PayChannelRateService#effective(门店市场, channel, payMethod, 主体法律形态, now)`；查不到时 `source=DEFAULT_ESTIMATE`、`rateBps` = 配置兜底值；`settleCycle` 取收款商户账期，没有则取渠道默认（`PayChannelMasterService#settleCycle`，`backend/pay/pay-channel/src/main/java/ai/neargo/shop/pay/channel/master/PayChannelMasterService.java:48`） |
| 错误 | 70006 |

与 App 建议的差异：`[{name, rate, cycle}]`（文字）→ 结构化码与万分比；按「渠道 × 支付方式」而不是只按渠道（信用卡与 TWQR 费率不同）；加 `source`，让 App 能标「暂按估算费率」。

#### ⑥ `GET /biz/merchant/payment`（AC8，已有，补一个字段）

- 不改路径、权限（`biz:finance`）与准入（foundation 通道）。
- `PaymentApplymentVO` 末尾加 `String settleCycle`，取 `mch_payment_merchant.settle_cycle`，原值透传。
- 与 App 的差异：无（App 已按 `settleCycle` 读，ai-shop-bapp `merchant_api.dart:87`）。

### 2.4 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/pay/BizFundsController.java` | 5 个端点；只做判权、取门店、`scope=ENTITY` 的门店集合与扇出 |
| 新增 | `backend/shop-app/src/main/java/ai/neargo/shop/payclient/BizFundsAppService.java` + `impl/BizFundsAppServiceImpl.java`（内嵌）+ `impl/RemoteBizFundsAppService.java`（独立） | 与 `BizPaymentAppService` 同一对条件装配（`shop.pay.deployment`） |
| 修改 | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` | 加一行 `HostRoute`（§2.1） |
| 新增 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/acquiring/service/MerchantFundsQueryService.java` + `impl/MerchantFundsQueryServiceImpl.java` | overview / daily / payouts / reconcile / rates；收款汇总部分抽公共方法与 `MerchantPaymentQueryServiceImpl` 共用，**不复制 SQL** |
| 修改 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/acquiring/service/impl/MerchantPaymentQueryServiceImpl.java` | 抽出「期间收款 / 退款 / 手续费」公共查询；手续费估算改为先查费率表、再用配置兜底（待决 D5 定了才动） |
| 修改 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/biz/PayBizFacade.java` | 暴露 5 个查询 |
| 修改 | `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/client/PayBizInternalApi.java` | 加 5 个 `@GetExchange(PREFIX + "/funds/…")` |
| 修改 | `backend/pay/pay-svc/src/main/java/ai/neargo/shop/pay/svc/InternalPayBizEndpoint.java` | 镜像端点 |
| 修改 | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/PaymentApplymentVO.java`、`service/impl/MerchantPaymentServiceImpl.java`（`:143`、`:538` 两处构造） | 加 `settleCycle` |
| 修改 | `docs/api/openapi-b.yaml` | 登记 6 个端点的契约 |
| 修改 | `known-app-backend-orphan.txt` | 只在闸门判 5 个新端点「端上无出口」时才加（出口在 Flutter 仓库）；先跑 `scripts/check-app-backend-orphan.py` 再定 |
| 新增 / 修改 | 测试，见 §5 | |
| **near-pay 仓库**（不在本仓库提交） | `pay-domain` 同名 `MerchantFundsQueryService`、`PayBizInternalApi`、`InternalPayBizEndpoint`；一期 B 的渠道账单与拨款表与任务 | 由 near-pay 侧 TDD 承接；本文只定 `/internal/pay/v1/biz/stores/{storeNo}/funds/*` 的形状 |

### 2.5 关键接口（会被别人调到的）

```
// pay-domain
interface MerchantFundsQueryService {
  Overview  overview (PayCaller caller, Long storeId, LocalDate from, LocalDate to);
  Daily     daily    (PayCaller caller, Long storeId, YearMonth month);
  PayoutPage payouts (PayCaller caller, Long storeId, PayoutFilter filter);   // filter: statuses, channel, cursor, size
  Reconcile reconcile(PayCaller caller, Long storeId, LocalDate from, LocalDate to);
  Rates     rates    (PayCaller caller, Long storeId);
}

// 内部 HTTP（独立形态），PREFIX = /internal/pay/v1/biz/stores/{storeNo}
GET  PREFIX/funds/overview?operatorNo&from&to
GET  PREFIX/funds/daily?operatorNo&month
GET  PREFIX/funds/payouts?operatorNo&status&channel&cursor&size
GET  PREFIX/funds/reconcile?operatorNo&from&to
GET  PREFIX/funds/rates?operatorNo
```

`scope=ENTITY` 不下沉到支付服务：支付服务只认门店（`PayBizInternalApi` 全部按 `{storeNo}`），「哪些门店属于这个主体、这个人看得见哪些」是 ai-shop 的知识（ADR-045 G9：支付只记、不认人）。

### 2.6 分期

| 期 | 内容 | 前提 |
|---|---|---|
| 一期 A | ①②④⑤⑥ 全部；③ 只出 `ESTIMATED` 行；④ 只有 `SELF_CHECK_ONLY` | 无，可立即做（near-pay 侧要同步实现查询） |
| 一期 B | ③ 出 `CHANNEL_REPORTED` 行与 `PAID / MISMATCH`；④ 出 `channelCount` 与 `CHANNEL_BILL` 差异；① `paidOutMinor` 有真值 | 蓝新、LINE Pay 回传拨款 / 对账文件（交接文档后端清单 3-3）；near-pay 建渠道账单与拨款表（待决 D3） |

App 侧：一期 A 上线即可从 `contracts.dart` 删 `^/biz/funds/`（附录 B）；页面靠 `payoutSource` / `coverage` 显示「预计」「仅平台自查」。

---

## §3 选型

| 问题 | 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| 资金数据从哪取 | **A. 支付服务（near-pay）为真源，ai-shop 只转发** | 收款、退款、手续费、账期本来就在那；渠道账单在 near-pay 设计里已有位置（FI09）；一份口径 | near-pay 要排期；两仓库同构代码要各写一次（待决 D2） | ✅ 采用 |
| | B. ai-shop 自建 `fnd_channel_payout` 等表，自己拉渠道账单 | 不等 near-pay | 违反 ADR-021（支付域独立为服务与独立库）与 ADR-043（进件与收款商户归支付域）；渠道凭据在 near-pay，ai-shop 拉不到账单；两边对账结果会分叉 | ❌ |
| | C. App 自己拼：调 summary 与 31 次 statement | 后端零改动 | 一月 31 次请求；手续费、拨款、对账仍没有；口径在端上散开 | ❌ |
| 一期没有渠道拨款数据时怎么显示 | **A. 按账期估算「预计」行，并带 `source` / `coverage` 标识** | 页面能用；不冒充实拨 | 估算可能与渠道实拨有出入 | ✅ 采用 |
| | B. 一期不上 F03–F05 | 不会误导 | 资金页三屏空着；App 已做好 | ❌（但若产品要求「没真数就不显示」，退回此方案，见待决 D3） |
| 权限码 | **A. 复用 `biz:finance`** | 与 F06 同页同码；无迁移 | 店长看不到资金（要看就得给他 `biz:finance`，同时也给了进件权限） | ✅ 采用 |
| | B. 新码 `mch:pay:funds` | 能单独授权店长 | 要迁移、i18n、角色模板改动；交接文档只说「店主、有资金权限者」 | ❌（待决 D8 若要给店长，再改为 B） |
| 提现 | **A. 不做** | 台湾模式平台不经手资金，没有可提的余额；PRD §2、§7 已定提现退役 | — | ✅ 采用（待决 D4 确认） |

**需要 ADR**（本文不写，建议编号顺延到 ADR-052）：「台湾直连拨款模式下商户资金视图的数据归属」——定死三件事：① 资金视图的真源是支付服务，ai-shop 不存资金数字；② 平台不提供余额与提现；③ 没有渠道回报时只显示「预计」，并强制带覆盖范围标识。理由：这三条一旦让 App 和运营形成习惯就很难收回（尤其是「余额」一旦出现，就会被要求「提现」）。

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 渠道不回传拨款 / 账单，或格式迟迟定不下来 | 「已拨款」永远是 0，「对账」永远只有自查 | `payoutSource` / `coverage` 让页面诚实；一期 B 单独排期，不阻塞一期 A |
| 估算费率与真实费率不符（配置默认 0） | 总览手续费显示 0、待拨偏高 | 待决 D5：估算改读费率表；上线前运营在 `sys_pay_channel_rate` 配台湾费率；费率页标 `DEFAULT_ESTIMATE` |
| near-pay 与本仓库 `backend/pay` 两份代码不同步 | 166 上与本地测试结果不同 | 待决 D2；`PayBizInternalApiRoundTripTest` 只证明本仓库那份；near-pay 侧要有同名测试 |
| `scope=ENTITY` 扇出 | 门店多时慢（每店一次远程调用） | 一期限主体内门店 ≤ 50（超出 10400 并提示改按店看）；以后再给支付服务加多门店查询 |
| `stl_recon_diff` 没有门店列 | 按店过滤要反查 `pay_order`，差异多时慢 | 期间上限 93 天；一期 B 在 near-pay 给差异表加 `store_id` |
| 「预计拨款日」与渠道真实拨款日不一致（节假日、渠道自定周期） | 商户按预计日去银行查，查不到 | 预计行明确标「预计」；文案由 App 写「以渠道入账为准」 |
| 账期词表不一致（§1.5-2） | F06 与 F01/F07 显示的账期写法不同 | 待决 D6 |

---

## §5 对账三 · 实现 → 需求（测试，计划；**均未跑**）

| AC | 测试方法（计划） | 跑过 | 消融验证（计划） |
|---|---|---|---|
| AC1 | `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BizFundsFlowTest#overviewMatchesPaymentSummaryForSamePeriod` | 未跑 | 让 overview 少算退款 → 与 summary 不等，变红 |
| AC1 | `BizFundsFlowTest#overviewWithoutChannelPayoutsIsEstimatedAndAllPending` | 未跑 | 把 `payoutSource` 写死 `CHANNEL_REPORTED` → 红 |
| AC2 | `BizFundsFlowTest#dailyGroupsByStoreBusinessDayIncludingRefundOnlyDays` | 未跑 | 退款改按收款日归日 → 红 |
| AC2 | `MerchantFundsQueryServiceImplTest#dailySplitsAtTaipeiMidnightNotUtc` | 未跑 | 用 UTC 切日 → 红 |
| AC3、AC6 | 无新测试：复用 `BizPaymentOrdersFlowTest#filtersAndCursor`（已有，`backend/shop-app/src/test/java/ai/neargo/shop/scenario/BizPaymentOrdersFlowTest.java:168`） | — | — |
| AC4、AC5 | `BizFundsFlowTest#payoutsEstimatedRowsGroupByChannelMerchantAndExpectedDate` | 未跑 | 分组去掉收款商户 → 两个收款号的钱并成一行，红 |
| AC4 | `BizFundsFlowTest#payoutsCursorDoesNotRepeatOrSkipAcrossPages` | 未跑 | — |
| AC4 | `MerchantFundsQueryServiceImplTest#estimatedPayoutNoIsStableAcrossCalls` | 未跑 | 编号里加时间戳 → 红 |
| AC4（一期 B） | `MerchantFundsQueryServiceImplTest#reportedAmountDifferentFromNetIsMismatch` | 未跑 | — |
| AC7 | `BizFundsFlowTest#reconcileShowsOnlyThisStoresDiffsAndSelfCheckCoverage` | 未跑 | 去掉按本店支付单过滤 → 看到别家差异，红 |
| AC7 | `MerchantFundsQueryServiceImplTest#channelCountIsNullWithoutValidatedBill` | 未跑 | 无账单时回 0 → 红 |
| AC8 | `backend/shop-app/src/test/java/ai/neargo/shop/scenario/MerchantPaymentFlowTest#paymentRowsCarrySettleCycle`（已有类，新方法） | 未跑 | — |
| AC9 | `BizFundsFlowTest#ratesUseMarketChannelRateAndFallBackToEstimate` | 未跑 | 跳过费率表直接用配置 → 红 |
| AC9 | `MerchantFundsQueryServiceImplTest#overviewFeeEstimateUsesSameRateAsRatesEndpoint` | 未跑 | 两处各取各的 → 红 |
| AC10 | `BizFundsFlowTest#managerAndClerkGet70006OwnerGetsData` | 未跑 | 去掉 `@PreAuthorize` → 红 |
| AC10 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest`（已有，加 5 条映射） | 未跑 | — |
| AC11 | `backend/shop-app/src/test/java/ai/neargo/shop/commercebridge/StoreScopedDailyRoutesFlowTest#selectedStoreOpensFundsRoutes`（已有类，新方法） | 未跑 | 删掉 `ShopStoreMemberRoutes` 那一行 → 70006 `CONTACT_ADMIN`，红 |
| AC12 | `BizFundsFlowTest#entityScopeSumsVisibleStoresAndRejectsNonOwner` | 未跑 | 去掉 owner 判断 → 店员拿到全主体数据，红 |
| AC13 | `backend/pay/pay-svc/src/test/java/ai/neargo/shop/pay/svc/PayBizInternalApiRoundTripTest#fundsQueriesRoundTripWithMinorAmounts`（已有类，新方法） | 未跑 | — |

near-pay 侧要有与 `MerchantFundsQueryServiceImplTest` 同名的测试（不在本仓库）。

## §6 对账二 · 设计 → 实现

未实现，留空。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-08 | 草稿；待「待决」拍板 |

---

## 待决（需要产品 / 用户拍板）

| # | 问题 | 选项 | 建议 |
|---|---|---|---|
| D1 | **资金数据从哪个服务取？** | A. 支付服务 near-pay 为真源，ai-shop 转发（§3 方案 A）/ B. ai-shop 自建资金表 / C. App 自己拼 | **A**。收款、手续费、账期已在支付服务；渠道账单在 near-pay 设计里已有位置（FI09）；B 违反 ADR-021、ADR-043，且 ai-shop 拿不到渠道凭据 |
| D2 | 查询代码写在哪一份？本仓库 `backend/pay`（内嵌与测试）和 near-pay（166 / 生产）是同构的两份 | A. 两边都写 / B. 只写 near-pay，ai-shop 内嵌实现返回 10505（依赖不可用）/ C. 先写本仓库，near-pay 照搬 | **A**，近期两份代码仍要对齐；若已决定本仓库 `backend/pay` 冻结只留测试，改 B，并把 `BizFundsFlowTest` 改成打 near-pay 的契约测试 |
| D3 | **一期是否等渠道拨款 / 账单？** 谁来建渠道账单与拨款表？ | A. 一期 A 先上（只有预计与自查），一期 B 等渠道 / B. 等渠道回传后整体上 / C. 一期不上 F03–F05 | **A**。前提是页面明确标「预计」「仅平台自查」（契约里的 `payoutSource` / `coverage` 就是为此）。渠道账单与拨款表由 near-pay 建（near-pay S1 台湾首期已排了 FI09）；要先问蓝新、LINE Pay 是否提供拨款 / 对账文件（交接文档清单 3-3） |
| D4 | **提现是否在一期做？** | A. 不做 / B. 做 | **A，不做，且不是「一期不做」而是这个模式下不做**：台湾模式渠道直接拨款给商户，平台没有商户余额；PRD-商家资金到账与对账 §2 判定「商家向平台提现」即二清，§7 已退役 `stl_withdraw` 入口；App 也没有提现页（`funds_pages.dart` 注释写明不含保证金、平台发票、欠款）。若产品指的是「渠道侧提领」，那是商户在渠道后台的操作，App 只能给说明或外链 |
| D5 | 手续费估算用哪个费率？ | A. 费率表 `sys_pay_channel_rate`（按台湾市场），查不到再用配置 / B. 维持配置 `shop.pay.fee.estimate-rate-bps`（默认 0） | **A**。否则 F07 显示的费率与 F01 算出的手续费对不上；改动会影响已有的收款汇总与详情（`feeEstimated=true` 的那部分数字会变），要通知 App |
| D6 | 账期码统一成哪套？ | 支付服务 `T1/T2/WEEKLY` vs ai-shop `T+1/T+N/WEEKLY/SEMI_MONTHLY/MONTHLY` | 以支付服务为准并扩成 `T1…Tn / WEEKLY / SEMI_MONTHLY / MONTHLY`；F06 补字段时做一次映射。需要知道蓝新、LINE Pay 的实际拨款周期才能定全集 |
| D7 | 「整个商户」的范围：主体还是账号下全部主体？ | A. 当前主体（`ENTITY`）/ B. 账号下全部主体 | **A**。账户与账期按「主体 × 渠道」，跨主体相加没有对账意义；App 要把 `MERCHANT` 改成 `ENTITY` |
| D8 | 店长要不要看资金？ | A. 只店主（`biz:finance`）/ B. 新码 `mch:pay:funds` 给店长 | **A**，与交接文档「店主、有资金权限者」一致；需要时店主可把 `biz:finance` 授给自定义角色。若产品要店长默认可看，改 B（多一个迁移 `V6xx 待定`、三语权限名、角色模板） |
| D9 | 一期 A 的「预计拨款」要不要上？ | A. 上，标「预计」/ B. 不上，等真数 | **A**；若法务或产品担心「预计」被商户当承诺，退回 B，F03 显示「渠道尚未回报」空态 |
