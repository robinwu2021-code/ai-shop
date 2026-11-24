# 网关接口清单 · C 端

> 由 `python3 scripts/gen-gateway-api-inventory.py` 生成，**请勿手改**。来源是后端源码里实际声明的入口。
> 总览、分类规则与验证清单见 [网关接口清单](网关接口清单.md)。

共 **221** 个入口：支付 22、零售 156、餐饮 29、服务业 12、平台与通用 2。

## 支付（22）

网关判权：网关放行，登录由业务层判 22。构建：默认构建 22。生产：已入网关 22。

### checkout（6）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/checkout/{operationNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCheckoutController |
| POST | `/mp/checkout/{operationNo}/confirmations` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCheckoutController |
| POST | `/mp/checkout/{operationNo}/orders` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCheckoutController |
| POST | `/mp/checkout/{operationNo}/payments` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCheckoutController |
| POST | `/mp/checkout/{operationNo}/payments/lookup` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCheckoutController |
| GET | `/mp/checkout/{operationNo}/payments/{paymentOperationNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCheckoutController |

### entitlements（6）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/entitlements` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementController |
| GET | `/mp/entitlements/{instanceNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementController |
| POST | `/mp/entitlements/{instanceNo}/activation-quotes` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementController |
| GET | `/mp/entitlements/{instanceNo}/allowances` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementController |
| POST | `/mp/entitlements/{instanceNo}/redeem-codes` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementController |
| GET | `/mp/entitlements/{instanceNo}/usage` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementController |

### invoice（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/invoice/apply` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpInvoiceController |
| GET | `/mp/invoice/mine` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpInvoiceController |
| GET | `/mp/invoice/order/{orderNo}` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpInvoiceController |

### stored-value（7）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/stored-value/consumptions` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoredValueController |
| GET | `/mp/stored-value/issuer-records` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoredValueController |
| GET | `/mp/stored-value/issuers` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoredValueController |
| GET | `/mp/stored-value/notification-capability` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoredValueNotificationController |
| GET | `/mp/stored-value/records/locate` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoredValueController |
| GET | `/mp/stored-value/store/{storeNo}/records` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoredValueController |
| GET | `/mp/stored-value/stores` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoredValueController |

## 零售（156）

网关判权：网关放行，登录由业务层判 156。构建：默认构建 156。生产：已入网关 150、未部署 6。

### after-sale（6）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/after-sale` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| GET | `/mp/after-sale/reasons` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| GET | `/mp/after-sale/{afterSaleNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/after-sale/{afterSaleNo}/cancel` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/after-sale/{afterSaleNo}/escalate` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/after-sale/{afterSaleNo}/ship` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |

### attribution（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/attribution/report` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |

### cart（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/cart` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/cart/add` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/cart/remove` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/cart/update` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |

### category（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/category/tree` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |

### community（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/community` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/community/nearby` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/community/regions` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/community/{communityNo}` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |

### config（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/config/bootstrap` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpConfigController |

### coupon（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/coupon` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCouponCenterController |
| POST | `/mp/coupon/best` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCouponCenterController |
| GET | `/mp/coupon/mine` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCouponCenterController |
| POST | `/mp/coupon/{couponNo}/receive` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCouponCenterController |

### entitlement-claims（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/entitlement-claims` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementController |
| GET | `/mp/entitlement-claims/{intentNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementController |

### entitlement-quotes（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/entitlement-quotes` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementQuoteController |

### entitlement-service-orders（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/entitlement-service-orders` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementServiceOrderController |
| GET | `/mp/entitlement-service-orders/{serviceOrderNo}` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementServiceOrderController |

### entitlement-usage（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/entitlement-usage` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementController |
| GET | `/mp/entitlement-usage/{usageNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementController |

### favorite（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/favorite/goods` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpFavoriteController |
| POST | `/mp/favorite/goods/{goodsNo}` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpFavoriteController |
| GET | `/mp/favorite/store` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpFavoriteController |
| POST | `/mp/favorite/store/{merchantNo}` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpFavoriteController |

### fission（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/fission` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpFissionController |

### goods（7）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/goods` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/goods/promoted` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/goods/{goodsNo}` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/goods/{goodsNo}/batch` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpBatchController |
| GET | `/mp/goods/{goodsNo}/group` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| GET | `/mp/goods/{goodsNo}/question` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpQuestionController |
| GET | `/mp/goods/{goodsNo}/sku-price` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |

### group-buy（9）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/group-buy` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| POST | `/mp/group-buy` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| GET | `/mp/group-buy/hosted` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| GET | `/mp/group-buy/mine` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| GET | `/mp/group-buy/{groupNo}` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| POST | `/mp/group-buy/{groupNo}/join` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| GET | `/mp/group-buy/{groupNo}/orders` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| POST | `/mp/group-buy/{groupNo}/receive` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| POST | `/mp/group-buy/{groupNo}/verify` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |

### group-request（8）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/group-request` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| POST | `/mp/group-request` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| GET | `/mp/group-request/{requestNo}` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| POST | `/mp/group-request/{requestNo}/choose` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| POST | `/mp/group-request/{requestNo}/confirm` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| POST | `/mp/group-request/{requestNo}/interest` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| GET | `/mp/group-request/{requestNo}/price-history` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |
| GET | `/mp/group-request/{requestNo}/quotes` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpGroupController |

### help（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/help/faq` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMessageController |

### invoice-carrier（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/invoice-carrier` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 未部署 | MpSalesInvoiceController |
| POST | `/mp/invoice-carrier` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 未部署 | MpSalesInvoiceController |
| POST | `/mp/invoice-carrier/{carrierNo}/delete` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 未部署 | MpSalesInvoiceController |

### location（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/location/resolve` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |

### member-code（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/member-code` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMemberCodeController |

### member-reach（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/member-reach/{reachNo}/opened` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMemberReachController |

### merchant（9）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/merchant` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/merchant/apply` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| POST | `/mp/merchant/apply` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| POST | `/mp/merchant/apply/{applyNo}` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/merchant/promoted` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/merchant/visited` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/merchant/{merchantNo}` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/merchant/{merchantNo}/acode` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/merchant/{merchantNo}/score` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |

### message（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/message` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMessageController |
| POST | `/mp/message/read-all` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMessageController |
| POST | `/mp/message/subscribe` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMessageController |
| GET | `/mp/message/unread-count` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMessageController |
| POST | `/mp/message/{messageNo}/read` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMessageController |

### my-coupons（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/my-coupons` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMyCouponController |

### my-memberships（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/my-memberships` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMyMemberController |
| PUT | `/mp/my-memberships/{entityNo}/reach` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMyMemberController |

### order（16）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/order` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/order` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/order/capability` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/order/points-quote` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/order/preview` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| GET | `/mp/order/{orderNo}` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/order/{orderNo}/after-sale` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/order/{orderNo}/cancel` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/order/{orderNo}/confirm-receipt` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/order/{orderNo}/pay` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| GET | `/mp/order/{orderNo}/pay-method` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| GET | `/mp/order/{orderNo}/pay-result` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| GET | `/mp/order/{orderNo}/points-earning` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | OrderPointsEarningController |
| GET | `/mp/order/{orderNo}/points-state` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |
| POST | `/mp/order/{orderNo}/reorder` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |
| GET | `/mp/order/{orderNo}/trace` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTradeController |

### orders（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/orders/{orderNo}/entitlement-authorizations` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpEntitlementAuthorizationController |

### pickup（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/pickup/{pickupNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |

### place（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/place/search` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |

### points（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/points/accounts` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpBuyerPointsController |
| GET | `/mp/points/entries` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpBuyerPointsController |

### push-token（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/push-token` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpPushTokenController |
| POST | `/mp/push-token/unregister` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpPushTokenController |

### question（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/question` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpQuestionController |

### regions（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/regions` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |

### review（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/review` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpReviewController |
| POST | `/mp/review` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpReviewController |
| POST | `/mp/review/{reviewNo}/like` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpReviewController |

### sales-invoice（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/sales-invoice` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 未部署 | MpSalesInvoiceController |
| PUT | `/mp/sales-invoice/choice` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 未部署 | MpSalesInvoiceController |
| GET | `/mp/sales-invoice/{invoiceNo}` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 未部署 | MpSalesInvoiceController |

### search（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/search/hot` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |
| GET | `/mp/search/suggest` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpCatalogController |

### store（9）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/store/by-code` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |
| GET | `/mp/store/mine` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |
| GET | `/mp/store/nearby` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |
| GET | `/mp/store/{no}` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |
| GET | `/mp/store/{no}/acode` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |
| POST | `/mp/store/{no}/enter` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |
| GET | `/mp/store/{no}/frequent` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |
| GET | `/mp/store/{no}/goods` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |
| POST | `/mp/store/{no}/rebuy` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |

### stores（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/stores/{storeNo}/appointment-slots` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpStoreController |

### ticket（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/ticket` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMessageController |
| POST | `/mp/ticket` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMessageController |
| GET | `/mp/ticket/{ticketNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpMessageController |

### topics（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/topics` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTopicController |
| GET | `/mp/topics/{topicNo}/goods` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTopicController |

### track（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/track` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTrackController |
| GET | `/mp/track/mine` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTrackController |
| GET | `/mp/track/mini-link` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpTrackController |

### user（22）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/user/active-address` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/active-address/{addressId}` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| GET | `/mp/user/address` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/address` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/address/{addressId}/archive` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/address/{addressId}/default` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/avatar` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpAvatarController |
| POST | `/mp/user/community` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/deregister` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/login` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/login/phone` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/logout` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/otp/send` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| GET | `/mp/user/password` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/password` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/phone/bind` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| GET | `/mp/user/phone/capable` | 不用登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/phone/wx` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| GET | `/mp/user/profile` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/profile` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/switch-to-merchant` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |
| POST | `/mp/user/token/refresh` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUserController |

### wx（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/wx/callback` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpWxCallbackController |
| POST | `/mp/wx/callback` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpWxCallbackController |

## 餐饮（29）

网关判权：网关放行，登录由业务层判 29。构建：插件 shop-industry-food 29。生产：未部署 29。

### x/food/batches（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/x/food/batches/{batchNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpOrderBatchController |
| POST | `/mp/x/food/batches/{batchNo}/settlements` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpSettlementController |

### x/food/carts（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/x/food/carts/{cartNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpCartController |
| GET | `/mp/x/food/carts/{cartNo}/delivery` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpDiningDeliveryController |
| PUT | `/mp/x/food/carts/{cartNo}/delivery` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpDiningDeliveryController |
| PUT | `/mp/x/food/carts/{cartNo}/lines/{sourceLineRef}` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpCartController |
| POST | `/mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpCartController |

### x/food/catalog（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/x/food/catalog` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpCatalogController |

### x/food/quotes（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/x/food/quotes/{quoteNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpDiningPricingController |
| POST | `/mp/x/food/quotes/{quoteNo}:confirm` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpDiningPricingController |

### x/food/returns（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/x/food/returns/{returnNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpDiningReturnController |
| POST | `/mp/x/food/returns/{returnNo}:cancel` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpDiningReturnController |

### x/food/sessions（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/x/food/sessions` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpDiningSessionController |
| POST | `/mp/x/food/sessions/{sessionNo}:end` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpDiningSessionController |

### x/food/settlements（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/x/food/settlements/{settlementNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpSettlementController |
| POST | `/mp/x/food/settlements/{settlementNo}:cancel` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpSettlementController |
| POST | `/mp/x/food/settlements/{settlementNo}:finalize` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpSettlementController |
| POST | `/mp/x/food/settlements/{settlementNo}:pay` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpSettlementController |

### x/food/stores（11）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/x/food/stores/{storeNo}/batches` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpOrderBatchController |
| POST | `/mp/x/food/stores/{storeNo}/carts` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpCartController |
| POST | `/mp/x/food/stores/{storeNo}/dining-device-sessions` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpDiningDeviceSessionController |
| POST | `/mp/x/food/stores/{storeNo}/offline-drafts/capture` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpOfflineDraftController |
| POST | `/mp/x/food/stores/{storeNo}/offline-drafts/challenge` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpOfflineDraftController |
| POST | `/mp/x/food/stores/{storeNo}/offline-drafts/enroll` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpOfflineDraftController |
| POST | `/mp/x/food/stores/{storeNo}/offline-drafts/lease` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpOfflineDraftController |
| POST | `/mp/x/food/stores/{storeNo}/offline-drafts/replay` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpOfflineDraftController |
| POST | `/mp/x/food/stores/{storeNo}/offline-drafts/state` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpOfflineDraftController |
| POST | `/mp/x/food/stores/{storeNo}/quotes` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpDiningPricingController |
| POST | `/mp/x/food/stores/{storeNo}/returns` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-food | 未部署 | MpDiningReturnController |

## 服务业（12）

网关判权：网关放行，登录由业务层判 12。构建：插件 shop-industry-beauty 12。生产：未部署 12。

### x/beauty/appointments（7）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/x/beauty/appointments` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpAppointmentController |
| POST | `/mp/x/beauty/appointments` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpAppointmentController |
| POST | `/mp/x/beauty/appointments/previews` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpAppointmentController |
| GET | `/mp/x/beauty/appointments/{appointmentNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpAppointmentController |
| POST | `/mp/x/beauty/appointments/{appointmentNo}/cancellations` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpAppointmentController |
| POST | `/mp/x/beauty/appointments/{appointmentNo}/changes` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpAppointmentController |
| POST | `/mp/x/beauty/appointments/{appointmentNo}/changes/previews` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpAppointmentController |

### x/beauty/arrivals（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/x/beauty/arrivals` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpReceptionController |

### x/beauty/availability（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/x/beauty/availability/queries` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpAvailabilityController |

### x/beauty/services（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/x/beauty/services/{skuNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpServiceController |

### x/beauty/stores（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/x/beauty/stores/{storeNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpStoreController |

### x/beauty/work-orders（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/mp/x/beauty/work-orders/{workOrderNo}` | 端上没有声明 | 网关放行，登录由业务层判 | 插件 shop-industry-beauty | 未部署 | MpWorkOrderController |

## 平台与通用（2）

网关判权：网关放行，登录由业务层判 2。构建：默认构建 2。生产：已入网关 2。

### risk（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/risk/appeal` | 端上没有声明 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpRiskController |

### upload（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/mp/upload/image` | 要登录 | 网关放行，登录由业务层判 | 默认构建 | 已入网关 | MpUploadController |
