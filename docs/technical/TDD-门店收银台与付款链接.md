# TDD-门店收银台与付款链接

状态：草稿（待确认）
档位：1（新增端点、权限码默认授权变化；不新建表）
关联原型：`ai-shop-bapp-mall/docs/prototypes/core-pages/v6-payment-link.html`（https://claude.ai/artifact/EcywEDvv3CScKCQTPFFp2p）
关联：TDD-B端收款订单与商城接口对齐（P1「收款」Tab）
创建：2026-11-05

## §0 需求 → 设计

| AC | 需求（原型屏） | 落点 |
|---|---|---|
| L1 | 收银台输入金额、说明、有效期，生成一次性付款链接与二维码（L01、L02） | 复用 `POST /biz/payment/collection`；响应补 `subject`、`amountMinor`、`currency`、`storeName`，端上画卡片不必再查一次 |
| L2 | 等待付款，付完自动变成已收款（L02、L04） | 复用 `GET /biz/payment/orders/{payOrderNo}`（P1），端上轮询 |
| L3 | 链接记录：待付款 / 已付款 / 已过期 / 已作废，带生成人与剩余时间（L05） | 新 `GET /biz/payment/links` |
| L4 | 待付款的链接可以再次分享（L05 → L02） | 新 `POST /biz/payment/links/{payOrderNo}/reissue`：给同一张支付单换发新令牌，旧令牌作废 |
| L5 | 作废链接；已付款的不能作废（L06） | 新 `POST /biz/payment/links/{payOrderNo}/void`：先查单再关单，复用 `PaymentService.close` |
| L6 | 顾客端：倒计时、成功页显示交易号后 6 位、失效页（L07–L09） | checkout-h5 视图与 `GET /checkout/v1/sessions/{token}` 补 `expireAt`、`subject`、`tradeNoSuffix`；作废或过期返回「已失效」 |
| L7 | 谁能用收银台 | 权限码 `mch:pay:collect` 默认授权见 §2 |

孤立项：无。

## §1 现状

- **生成**：`CheckoutServiceImpl.collect`，`biz_type=BIZ_COLLECT`，有效期 1–1440 分钟（默认 15 分钟），返回 `{payOrderNo, token, expireAt, checkoutUrl}`。
- **令牌只存哈希**（`pay_checkout_session.token_sha256`），原文只在生成那一次响应里出现。所以链接记录里拿不回原链接，「再次分享」只能换发新令牌（L4）。
- **权限**：`mch:pay:collect` 默认只有店主（OWNER `*`）有。
- **关单**：`PaymentService.close(caller, payOrderNo, reason)`，关单前先查单，查到已付就变成 SUCCEEDED，不会把已付的单关掉。
- **收款列表**：P1 `/biz/payment/orders` 只列有过渠道尝试的单，未付、过期的链接不在里面，所以 L3 要单独的接口。
- **不受影响**：静态收款码（`/biz/payment/qr-code`）、收款列表与详情（P1）、渠道回调。

## §2 方案

**端点**（shop-app `BizPaymentController` → `BizPaymentAppService` 内嵌和远程两种实现 → `PayBizFacade`，与 P1 同一套分层）

```
POST /biz/payment/collection            （已有，响应补字段）
  → {payOrderNo, token, expireAt, checkoutUrl, amountMinor, currency, subject, storeName}

GET  /biz/payment/links?status=PENDING|PAID|EXPIRED|VOIDED&cursor&size
  → {items:[{payOrderNo, amountMinor, currency, subject, status, createdAt, expireAt,
             paidAt?, channel?, operatorNo, voidedBy?}], nextCursor}
  · 只列本店 biz_type=BIZ_COLLECT 的单，按创建时间倒序，游标分页
  · status 按支付单与会话推出：SUCCEEDED / 部分退款 / 已退款 → PAID；
    CLOSED 且有作废记录 → VOIDED；会话已过期且未付 → EXPIRED；其余 → PENDING

POST /biz/payment/links/{payOrderNo}/reissue
  → 同生成的响应（新 token，旧会话作废；有效期从现在起按原时长，最长不超过原上限）
  · 只允许 PENDING

POST /biz/payment/links/{payOrderNo}/void   {reason?}
  → 链接行（status=VOIDED；若关单前查到已付，返回 PAID 并提示不能作废）
```

**库表**：不新增表。作废人和作废原因记在关单原因上（`pay_order.closed_at` 加上现有的状态日志或审计）；会话作废用 `pay_checkout_session.status`。

**权限码**（待定 ①）
- 生成、再次分享、作废：`mch:pay:collect`。建议默认给店长；店员不默认给，由店主在角色里勾选授权。
- 链接记录：`mch:pay:order:view`，店长和店员都有，与「收款」Tab 一致。

**配置**：`shop.pay.collect.max-expire-minutes`，默认 1440（待定 ②）。

**顾客端**（checkout-h5 与 `CheckoutController`）：会话视图补 `expireAt`、`subject`；付款结果补 `tradeNoSuffix`（渠道交易号后 6 位）；作废或过期的令牌返回明确的「已失效」码，不再是通用 404。

| 动作 | 路径 |
|---|---|
| 修改 | `pay-domain/.../checkout/service/CheckoutService` + impl：`reissue`、`voidCollect`；`collect` 的返回补字段 |
| 新增 | `pay-domain/.../acquiring/service/MerchantCollectLinkQueryService` + impl（链接列表） |
| 修改 | `PayBizFacade`、`PayBizInternalApi`、`InternalPayBizEndpoint` |
| 修改 | `shop-app` `BizPaymentAppService`（两种实现）、`BizPaymentController`、`BizPerms`（默认授权）、`ShopStoreMemberRoutes`（登记 `/biz/payment/**` 已覆盖，无需改） |
| 修改 | `pay-portal` `CheckoutController` 视图；`checkout-h5` 的 pay 视图与失效页 |
| 测试 | 新 `BizPaymentLinkFlowTest`；`BizEndpointPermTest` 映射；checkout-h5 视图测试 |

## §3 选型

| 问题 | 方案 | 结论 |
|---|---|---|
| 链接记录里的「再次分享」 | A. 换发新令牌，旧令牌作废 | ✅ 令牌原文不落库的规矩不变 |
| | B. 加密保存令牌原文 | ❌ 多一份可被拿去付款的凭据 |
| 作废 | A. 复用关单（先查单） | ✅ 不会误关已付的单 |
| | B. 只作废会话 | ❌ 支付单留在待付，对账会挂着 |

## §4 风险与待定

| 项 | 建议 |
|---|---|
| ① 谁能用收银台 | 店长默认可用；店员不默认，由店主在角色里授权 |
| ② 有效期上限 | 维持 24 小时；订金类长链接以后按需调高配置 |
| ③ 能否重复付 | 一次一用：付成功后链接即失效 |
| ④ 顾客看到的内容 | 只显示店名、金额、收款说明、倒计时；不显示操作人等内部信息 |
| 作废与付款同时发生 | 关单前先查单，查到已付就按已收款处理，告诉店员这一笔要走退款 |
| 台湾真实渠道未接 | 本机只能用 TEST_PAY 验证，与 P1 相同 |

## §5 测试（计划）

| AC | 测试 |
|---|---|
| L1 | `BizPaymentLinkFlowTest#collectReturnsCardFields` |
| L3 | `#linksListPendingPaidExpiredVoided`（四种状态各一条，游标分页，只看本店） |
| L4 | `#reissueRevokesOldTokenAndKeepsSameOrder`（旧令牌失效、新令牌可付，支付单号不变） |
| L5 | `#voidClosesPendingAndRefusesPaid` |
| L6 | `CheckoutFlowTest#expiredOrVoidedTokenSaysInvalid`、`#resultCarriesTradeNoSuffix` |
| L7 | `BizEndpointPermTest` 映射；`#managerCanCollectClerkCannotByDefault` |

消融：去掉列表里「只列 BIZ_COLLECT」的条件 → `#linksListPendingPaidExpiredVoided` 应变红。
