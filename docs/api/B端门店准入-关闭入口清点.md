# B 端门店准入表里「关闭」的入口清点

状态：2026-11-19 清点。属于 [TDD-权限统一到网关层-修复方案](../technical/design/TDD-权限统一到网关层-修复方案.md) 第 2 步之前的准备。
数据来源：v2 生产上的网关索引（765 条 B 端入口）逐条对照门店准入表（`CommerceRoutePolicy` + `ShopStoreMemberRoutes`），
再用测试店主在新模型的测试店里把其中全部 GET 实打了一遍核对标注。

## 结论

> 2026-11-19 更新：B 类里的第一组 35 条已补进准入表并上线，见 [补登记建议](B端门店准入-补登记建议.md)。下面是补之前的清点。

「接口资源」页上显示为 `DENY` 的 B 端入口共 **226** 条，分三类：

| 类 | 条数 | 含义 | 要做什么 |
|---|---:|---|---|
| A 标注有误，其实不经过门店准入 | 24 | 支付进件 `/biz/pay/v2/**` 走的是支付自己的认证链；登录前的几个动作在过滤器里先放行 | 已改标注（`NOT_APPLIED`），下次部署生效 |
| B 真的关着，而商户 App 在调用 | **119** | 新模型的门店里，App 上对应的页面会直接 403 | **逐条确认是不是漏登记**；是的话补进准入表 |
| C 真的关着，商户 App 里也没有调用 | 83 | 可能是有意不开放，也可能是给别的端用的或还没接入 | 逐组确认；有意关闭的在准入表旁边写明 |

「关着」的准确含义：准入表对没有登记的路由默认拒绝。**老模型的门店不受这张表约束**，所以现有商户感觉不到；
新模型的门店（现在走「快速开店」建出来的都是）访问这些入口，一律 HTTP 403、70006、`deniedBy: STORE_ACCESS`。
表里「所有门店都关」一列标「是」的 17 条例外：它们在 `/biz/stores/{门店}/…` 下，准入表把这个命名空间里没登记的路由对**任何**门店都关闭。

## 标注准不准

用测试店主（全部权限）在新模型的测试店里实打全部 372 个 B 端 GET（路径变量里的门店号用测试店，其余用不存在的编号）：

| 页面标注 | 实际 | 条数 |
|---|---|---:|
| 开放 | 过了准入 | 256 |
| 关闭 | 被准入拒绝 | 99 |
| 关闭 | 过了准入 | 14（`/biz/pay/v2/**` 13 条、`/biz/auth/session` 1 条） |
| 开放（要权益） | 被准入拒绝 | 3（`END_PROTECTED`、`LEGACY_PREVIEW`：要有业务端权益，测试店没有，符合预期） |

POST / PUT / DELETE 没有实打（标注万一错了，打过去会真的改数据），它们的分类只来自对照准入表。
`/biz/auth/session` 标注是关闭、实际过了，原因没有查清，仍按 C 类列着。

## B 类：真的关着，而商户 App 在调用（119 条）

按区域：`/biz/management` 48、`/biz/stored-value` 21、`/biz/stores` 15、`/biz/settle` 10、`/biz/regions` 5、`/biz/communities` 3、`/biz/cross-store` 2、`/biz/delivery` 2、`/biz/deposit` 2、`/biz/geo` 2、`/biz/activities` 1、`/biz/customers` 1、`/biz/fulfillment` 1、`/biz/member-code` 1、`/biz/merchant` 1、`/biz/message` 1、`/biz/notify` 1、`/biz/store-spec-dims` 1、`/biz/store` 1。

这一类最可能是漏登记：准入表的注释自己写着「没登记的老经营接口全部 70006 —— 新店主建完第一家店，商品、订单、主体、收款一个都打不开」，
并且已经为此补过两批（2026-11-07、11-08）。下面这些是还没补到的。
「端上调用处」是在 `b-app/src` 里按地址形状搜到的文件；搜到不等于新模型门店的页面一定会走到它，所以要逐条确认。

### /biz/management（48）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/management/brands/{brandNo}/stored-value/activities` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/checks` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/copies` | @managementAccess.authenticated() |  | endpoints.ts |
| PUT | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/draft` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/stop-issuance` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/submissions` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-context` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-presets` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-schedules` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/brands/{brandNo}/stored-value/issuer` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/brands/{brandNo}/stored-value/issuer` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/brands/{brandNo}/stored-value/issuer/activation` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/brands/{brandNo}/stored-value/store-breakdown` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/brands/{brandNo}/stored-value/summary` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/entities/{entityNo}/stored-value/financial-permissions` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/activities/{activityNo}/summary` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/financial-brand-changes/{changeNo}` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stored-value/financial-brand-changes/{changeNo}/resumptions` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/financial-capability` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/financial-resources` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/issuers/{issuerNo}/collection-routes` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stored-value/issuers/{issuerNo}/configuration-checks` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stored-value/issuers/{issuerNo}/configuration-versions` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/settlement-bills` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stored-value/settlement-bills/generations` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/actions` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/adjustments` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/dispute-resolutions` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/disputes` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/events` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/items` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/payment-resolutions` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/payments` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/payments` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/reconciliation-confirmations` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/settlement-entity-candidates` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stored-value/settlement-payments/{paymentNo}/receipt-confirmations` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stored-value/settlement-payments/{paymentNo}/voids` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stored-value/store-issuer-summary` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stores/{storeNo}/stored-value/financial-brand-change-original` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stores/{storeNo}/stored-value/financial-brand-changes` | @managementAccess.authenticated() |  | endpoints.ts |
| GET | `/biz/management/stores/{storeNo}/stored-value/issuer` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer/access-refresh` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer/prepare` | @managementAccess.authenticated() |  | endpoints.ts |

### /biz/stored-value（21）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/stored-value/account-directory` | pay:sv:account:read |  | endpoints.ts |
| GET | `/biz/stored-value/accounts/{accountRef}` | pay:sv:account:read |  | endpoints.ts |
| GET | `/biz/stored-value/brand-capability` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/stored-value/consumptions` | pay:sv:consume:create |  | endpoints.ts |
| GET | `/biz/stored-value/consumptions/orders/{orderNo}` | pay:sv:consume:create |  | endpoints.ts |
| POST | `/biz/stored-value/consumptions/orders/{orderNo}/cancel` | pay:sv:consume:create |  | endpoints.ts |
| POST | `/biz/stored-value/consumptions/preview` | pay:sv:consume:create |  | endpoints.ts |
| POST | `/biz/stored-value/consumptions/resolve` | pay:sv:consume:create |  | endpoints.ts |
| GET | `/biz/stored-value/context` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:account:read 或 pay:sv:recharge:create) |  | endpoints.ts |
| GET | `/biz/stored-value/customer-candidates` | (biz:customer 且 pay:sv:recharge:create) |  | endpoints.ts |
| POST | `/biz/stored-value/customer-selections` | (biz:customer 且 pay:sv:recharge:create) |  | endpoints.ts |
| GET | `/biz/stored-value/governance-capability` | @managementAccess.authenticated() |  | endpoints.ts |
| POST | `/biz/stored-value/plan-recharges` | pay:sv:recharge:create |  | endpoints.ts |
| GET | `/biz/stored-value/plan-recharges/{rechargeNo}` | (pay:sv:recharge:read 或 pay:sv:recharge:create) |  | endpoints.ts |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/cancel` | pay:sv:recharge:create |  | endpoints.ts |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/receive` | pay:sv:recharge:receive |  | endpoints.ts |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/submit` | pay:sv:recharge:create |  | endpoints.ts |
| GET | `/biz/stored-value/plans` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:recharge:create) |  | endpoints.ts |
| POST | `/biz/stored-value/plans` | pay:sv:plan:manage |  | endpoints.ts |
| GET | `/biz/stored-value/plans/{planNo}` | (pay:sv:plan:read 或 pay:sv:plan:manage 或 pay:sv:recharge:create) |  | endpoints.ts |
| PUT | `/biz/stored-value/plans/{planNo}/status` | pay:sv:plan:manage |  | endpoints.ts |

### /biz/stores（15）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/stores/{storeNo}/members` | biz:customer | 是 | endpoints.ts |
| GET | `/biz/stores/{storeNo}/stored-value/customer-candidates` | (biz:customer 且 (pay:sv:recharge:create 或 pay:sv:account:read)) | 是 | endpoints.ts |
| POST | `/biz/stores/{storeNo}/stored-value/customer-selections` | (biz:customer 且 (pay:sv:recharge:create 或 pay:sv:account:read)) | 是 | endpoints.ts |
| GET | `/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/accounts` | pay:sv:account:read | 是 | endpoints.ts |
| GET | `/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/records` | pay:sv:account:read | 是 | endpoints.ts |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/cancel-create` | pay:sv:recharge:create | 是 | endpoints.ts |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/confirm-offline` | (pay:sv:recharge:create 且 pay:sv:recharge:receive) | 是 | endpoints.ts |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/resolve` | pay:sv:recharge:create | 是 | endpoints.ts |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}` | (pay:sv:recharge:read 或 pay:sv:recharge:create) | 是 | endpoints.ts |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}/cancel` | pay:sv:recharge:create | 是 | endpoints.ts |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-records` | pay:sv:account:read | 是 | endpoints.ts |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-records/{recordNo}` | pay:sv:account:read | 是 | endpoints.ts |
| GET | `/biz/stores/{storeNo}/stored-value/issuers` | (pay:sv:account:read 或 pay:sv:recharge:create 或 pay:sv:plan:manage 或 pay:sv:plan:read) | 是 | endpoints.ts |
| GET | `/biz/stores/{storeNo}/stored-value/recharge-options` | pay:sv:recharge:create | 是 | endpoints.ts |
| GET | `/biz/stores/{storeNo}/stored-value/store-summary` | @managementAccess.authenticated() | 是 | endpoints.ts |

### /biz/settle（10）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/settle/batch` | biz:finance |  | endpoints.ts |
| GET | `/biz/settle/bills` | biz:finance |  | endpoints.ts |
| GET | `/biz/settle/daily-flow` | biz:finance |  | endpoints.ts |
| GET | `/biz/settle/income` | biz:finance |  | endpoints.ts |
| GET | `/biz/settle/invoice-pending` | biz:finance |  | endpoints.ts |
| GET | `/biz/settle/invoice-title` | biz:finance |  | endpoints.ts |
| GET | `/biz/settle/invoices` | biz:finance |  | endpoints.ts |
| POST | `/biz/settle/invoices` | biz:finance |  | endpoints.ts |
| GET | `/biz/settle/rate-card` | biz:finance |  | endpoints.ts |
| GET | `/biz/settle/statement` | biz:finance |  | endpoints.ts |

### /biz/regions（5）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/regions` | 登录即可 |  | endpoints.ts |
| POST | `/biz/regions/parse` | 登录即可 |  | endpoints.ts |
| GET | `/biz/regions/path` | 登录即可 |  | endpoints.ts、biz-region-picker.vue |
| GET | `/biz/regions/search` | 登录即可 |  | endpoints.ts |
| GET | `/biz/regions/villages` | 登录即可 |  | endpoints.ts |

### /biz/communities（3）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/communities/applies` | biz:store |  | endpoints.ts |
| POST | `/biz/communities/apply` | biz:store |  | endpoints.ts |
| POST | `/biz/communities/from-map` | biz:store |  | endpoints.ts |

### /biz/cross-store（2）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/cross-store/compare` | biz:customer |  | endpoints.ts |
| GET | `/biz/cross-store/overview` | biz:customer |  | endpoints.ts |

### /biz/delivery（2）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/delivery/rule` | biz:store |  | endpoints.ts、delivery |
| POST | `/biz/delivery/rule` | biz:store |  | endpoints.ts、delivery |

### /biz/deposit（2）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/deposit` | biz:finance |  | endpoints.ts |
| GET | `/biz/deposit/txns` | biz:finance |  | endpoints.ts |

### /biz/geo（2）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/geo/estates` | 登录即可 |  | endpoints.ts、biz-region-picker.vue |
| GET | `/biz/geo/estates/counts` | 登录即可 |  | endpoints.ts |

### /biz/activities（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| PUT | `/biz/activities/{activityNo}/status` | biz:campaign |  | endpoints.ts |

### /biz/customers（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/customers` | biz:customer |  | endpoints.ts |

### /biz/fulfillment（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/fulfillment/carriers` | biz:stock |  | endpoints.ts |

### /biz/member-code（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/member-code/resolve` | biz:customer |  | endpoints.ts |

### /biz/merchant（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/merchant/debt` | biz:finance |  | endpoints.ts |

### /biz/message（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/message/subscribe` | 登录即可 |  | endpoints.ts |

### /biz/notify（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/notify/wecom/test` | 登录即可 |  | endpoints.ts |

### /biz/store-spec-dims（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/store-spec-dims` | biz:goods |  | endpoints.ts |

### /biz/store（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/store/{storeNo}/default` | biz:store:admin |  | endpoints.ts |

## C 类：真的关着，商户 App 里也没有调用（83 条）

按区域：`/biz/stored-value` 11、`/biz/pay` 10、`/biz/pricing-configs` 9、`/biz/service-orders` 9、`/biz/entitlement-templates` 6、`/biz/management` 5、`/biz/entitlement-uses` 4、`/biz/pricing-manual-adjustments` 4、`/biz/pricing` 4、`/biz/checkout` 3、`/biz/pricing-tax-settings` 3、`/biz/entitlement-issue-batches` 2、`/biz/pricing-manual-policies` 2、`/biz/store-sales-catalog` 2、`/biz/stores` 2、`/biz/auth` 1、`/biz/card-product-bindings` 1、`/biz/card-product-presets` 1、`/biz/entitlement-usage` 1、`/biz/entitlements` 1、`/biz/geo` 1、`/biz/settle` 1。

这一类要逐组确认用途。从地址看大致是：定价与税（`pricing-*`）、服务单（`service-orders`）、权益模板与发放（`entitlement-*`）、收银（`checkout`）——
可能是 POS、收银台或还没接进商户 App 的功能在用，也可能是有意不对新模型门店开放。

### /biz/stored-value（11）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/stored-value/accounts` | pay:sv:account:read |  |  |
| POST | `/biz/stored-value/plan-recharges/cancel-create` | pay:sv:recharge:create |  |  |
| POST | `/biz/stored-value/plan-recharges/confirm-offline` | (pay:sv:recharge:create 且 pay:sv:recharge:receive) |  |  |
| POST | `/biz/stored-value/plan-recharges/resolve` | pay:sv:recharge:create |  |  |
| POST | `/biz/stored-value/recharges` | pay:sv:recharge:create |  |  |
| GET | `/biz/stored-value/recharges/{rechargeNo}` | pay:sv:recharge:read |  |  |
| POST | `/biz/stored-value/recharges/{rechargeNo}/cancel` | pay:sv:recharge:create |  |  |
| POST | `/biz/stored-value/recharges/{rechargeNo}/receive` | pay:sv:recharge:receive |  |  |
| POST | `/biz/stored-value/recharges/{rechargeNo}/submit` | pay:sv:recharge:create |  |  |
| GET | `/biz/stored-value/records` | pay:sv:account:read |  |  |
| GET | `/biz/stored-value/records/{recordNo}` | pay:sv:account:read |  |  |

### /biz/pay（10）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/pay/app-grant` | mch:pay:app-grant |  |  |
| POST | `/biz/pay/app-grant` | mch:pay:app-grant |  |  |
| DELETE | `/biz/pay/app-grant/{id}` | mch:pay:app-grant |  |  |
| POST | `/biz/pay/collection` | mch:pay:collect |  |  |
| GET | `/biz/pay/qr-code` | mch:pay:qr |  |  |
| POST | `/biz/pay/qr-code` | mch:pay:qr |  |  |
| PUT | `/biz/pay/qr-code/{qrCode}/disabled` | mch:pay:qr |  |  |
| GET | `/biz/pay/refund` | (mch:pay:refund 或 mch:pay:statement) |  |  |
| POST | `/biz/pay/refund` | mch:pay:refund |  |  |
| GET | `/biz/pay/statement` | mch:pay:statement |  |  |

### /biz/pricing-configs（9）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/pricing-configs/initialize` | biz:pricing:config:publish |  |  |
| GET | `/biz/pricing-configs/{no}/draft` | biz:pricing:config:read |  |  |
| PUT | `/biz/pricing-configs/{no}/draft` | biz:pricing:config:update |  |  |
| POST | `/biz/pricing-configs/{no}/preview` | biz:pricing:config:read |  |  |
| POST | `/biz/pricing-configs/{no}/publish` | biz:pricing:config:publish |  |  |
| POST | `/biz/pricing-configs/{no}/reset` | biz:pricing:config:update |  |  |
| GET | `/biz/pricing-configs/{no}/revisions/{revisionNo}` | biz:pricing:config:read |  |  |
| POST | `/biz/pricing-configs/{no}/rollback` | biz:pricing:config:publish |  |  |
| POST | `/biz/pricing-configs/{no}/upgrade` | biz:pricing:config:update |  |  |

### /biz/service-orders（9）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/service-orders/counter-quotes` | (trade:checkout:prepare 且 biz:order:view) |  |  |
| GET | `/biz/service-orders/counter-quotes/{operationNo}` | (trade:checkout:prepare 且 biz:order:view) |  |  |
| POST | `/biz/service-orders/counter-quotes/{operationNo}/acceptances` | (((trade:checkout:prepare 且 biz:order:view) 且 pay:cash:receive) 且 trade:manual-service:confirm) |  |  |
| POST | `/biz/service-orders/counter-quotes/{operationNo}/orders` | (((trade:checkout:prepare 且 biz:order:view) 且 pay:cash:receive) 且 trade:manual-service:confirm) |  |  |
| GET | `/biz/service-orders/counter-quotes/{operationNo}/state` | (trade:checkout:prepare 且 biz:order:view) |  |  |
| POST | `/biz/service-orders/manual-quotes` | trade:checkout:prepare |  |  |
| GET | `/biz/service-orders/{orderNo}/manual-admission` | biz:order:view |  |  |
| POST | `/biz/service-orders/{orderNo}/manual-receipts` | (pay:cash:receive 且 trade:manual-service:confirm) |  |  |
| GET | `/biz/service-orders/{orderNo}/manual-source` | biz:order:view |  |  |

### /biz/entitlement-templates（6）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/entitlement-templates` | pay:ent:template:read |  |  |
| POST | `/biz/entitlement-templates` | pay:ent:template:write |  |  |
| PUT | `/biz/entitlement-templates/{templateNo}/draft` | pay:ent:template:write |  |  |
| PUT | `/biz/entitlement-templates/{templateNo}/issue-policy` | pay:ent:issue:manage |  |  |
| POST | `/biz/entitlement-templates/{templateNo}/publish` | pay:ent:template:publish |  |  |
| PUT | `/biz/entitlement-templates/{templateNo}/status` | pay:ent:template:publish |  |  |

### /biz/management（5）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/management/spaces/{managementNo}/grant-commands/{action}` | @managementAccess.authenticated() |  |  |
| GET | `/biz/management/stored-value/issuers/{issuerNo}` | @managementAccess.authenticated() |  |  |
| POST | `/biz/management/stored-value/migration-manifests` | @managementAccess.authenticated() |  |  |
| POST | `/biz/management/stored-value/migration-manifests/{manifestNo}/map` | @managementAccess.authenticated() |  |  |
| POST | `/biz/management/stored-value/migration-manifests/{manifestNo}/pause` | @managementAccess.authenticated() |  |  |

### /biz/entitlement-uses（4）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/entitlement-uses` | pay:ent:use |  |  |
| POST | `/biz/entitlement-uses/preview` | pay:ent:use |  |  |
| GET | `/biz/entitlement-uses/{operationNo}` | pay:ent:read |  |  |
| POST | `/biz/entitlement-uses/{operationNo}/cancel` | pay:ent:use |  |  |

### /biz/pricing-manual-adjustments（4）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/pricing-manual-adjustments` | biz:campaign |  |  |
| GET | `/biz/pricing-manual-adjustments/{no}` | biz:campaign |  |  |
| POST | `/biz/pricing-manual-adjustments/{no}:approve` | biz:finance |  |  |
| POST | `/biz/pricing-manual-adjustments/{no}:quote` | biz:campaign |  |  |

### /biz/pricing（4）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/pricing/fee-rules/{ruleNo}` | biz:pricing:config:read |  |  |
| POST | `/biz/pricing/fee-rules/{ruleNo}` | biz:pricing:config:publish |  |  |
| GET | `/biz/pricing/fee-scopes/{scopeNo}/adjustments` | biz:pricing:config:read |  |  |
| POST | `/biz/pricing/fee-scopes/{scopeNo}/adjustments/{applicationNo}:apply` | biz:pricing:config:publish |  |  |

### /biz/checkout（3）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/checkout/{operationNo}/offline-receipts` | pay:cash:receive |  |  |
| POST | `/biz/checkout/{operationNo}/offline-receipts/lookup` | biz:order:view |  |  |
| GET | `/biz/checkout/{operationNo}/offline-receipts/{paymentOperationNo}` | biz:order:view |  |  |

### /biz/pricing-tax-settings（3）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/pricing-tax-settings` | biz:pricing:tax:read |  |  |
| PUT | `/biz/pricing-tax-settings` | biz:pricing:tax:update |  |  |
| POST | `/biz/pricing-tax-settings/reset` | biz:pricing:tax:update |  |  |

### /biz/entitlement-issue-batches（2）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/entitlement-issue-batches` | pay:ent:issue |  |  |
| GET | `/biz/entitlement-issue-batches/{batchNo}` | pay:ent:read |  |  |

### /biz/pricing-manual-policies（2）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}` | biz:finance |  |  |
| PUT | `/biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}` | biz:finance |  |  |

### /biz/store-sales-catalog（2）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/store-sales-catalog` | biz:goods |  |  |
| POST | `/biz/store-sales-catalog/versions` | biz:goods |  |  |

### /biz/stores（2）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/cash-refunds` | 登录即可 | 是 |  |
| POST | `/biz/stores/{storeNo}/mall/offline/upgrade-quotes` | 登录即可 | 是 |  |

### /biz/auth（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/auth/session` | 登录即可 |  |  |

### /biz/card-product-bindings（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/card-product-bindings` | biz:goods |  |  |

### /biz/card-product-presets（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/card-product-presets` | biz:goods |  |  |

### /biz/entitlement-usage（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/entitlement-usage` | pay:ent:read |  |  |

### /biz/entitlements（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/entitlements/{instanceNo}` | pay:ent:read |  |  |

### /biz/geo（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| POST | `/biz/geo/geocode` | 登录即可 |  |  |

### /biz/settle（1）

| 方法 | 地址 | 网关判权 | 所有门店都关 | 端上调用处 |
|---|---|---|---|---|
| GET | `/biz/settle/bills/{settleNo}` | biz:finance |  |  |

## A 类：标注有误，其实不经过门店准入（24 条）

`/biz/pay/v2/**` 22 条、登录前的动作 2 条。不逐条列。
其中 `/biz/pay/v2/**` 值得单独记一笔：它既不经过门店准入，网关上也只要求登录、入口上没有权限注解 —— 权限完全在支付进件那条链和服务里判，是修复方案第 3 步的第一批。

## 建议怎么处理

1. **B 类先过一遍**（119 条）。按区域确认；确认是漏登记的，补进 `ShopStoreMemberRoutes`，并在 `StoreScopedDailyRoutesFlowTest` 里加上（它就是盯这件事的）。
   这一步不依赖网关的改造，补一条好一条。
2. **C 类逐组定性**。有意关闭的，在准入表里用注释写明原因（现在「没登记」和「有意关」长得一样）。
3. 以上做完，再做修复方案第 2 步（准入并进网关）。那时「关闭」应当只剩有意为之的。

## 怎么重新生成

「接口资源」接口（`/ops/api-resources?endCode=biz`）每一行的 `admission` 字段就是这份清点的原始数据；
端上有没有调用，是在 `b-app/src` 里按地址形状搜出来的。本文件是一次性的清点结果，不是生成物。
