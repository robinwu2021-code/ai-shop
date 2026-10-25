# B 端功能矩阵 · 按角色

> **本文是生成的**：`node scripts/gen-biz-role-matrix.mjs`。改了 `BizPerms` 或
> `BizEndpointPermTest.REQUIRED` 之后重跑一次，不要手改这份产物。

> 三份来源：权限点取自 `BizPerms`，角色→权限取自 `BizPerms.ROLE_PERMS`，
> 端点→权限取自 `BizEndpointPermTest.REQUIRED` —— 最后那份是唯一**被守卫强制对过账**的
> 清单（每个 `/biz` 端点都必须在里面有个说法，漏登记就红），所以比任何手写文档都可信。

统计：**6 个角色 × 42 个权限点 × 380 个受控端点**。

## 一、角色 × 权限

`OWNER` 是 `*` —— **不是「拥有全部权限点」，是「不走这张表」**。新增权限点时 OWNER 自动有，
其余角色需要显式加：老板不该因为上了个新功能就被锁在外面。

| 权限点 | 含义 | 端点数 | OWNER | MANAGER | CLERK | PICKER | COURIER | CS |
|---|---|---|---|---|---|---|---|---|
| `STOCK` | 改库存（含门店库存） | 50 | ✅ | ✅ | ✅ | ✅ | — | — |
| `GOODS` | 建/改商品、上下架、规格模板、识图 | 38 | ✅ | ✅ | — | — | — | — |
| `CAMPAIGN` | 营销活动、开团、报价 | 35 | ✅ | ✅ | — | — | — | — |
| `FINANCE` | 结算账单、费率卡、收款进件、积分开关 | 32 | ✅ | — | — | — | — | — |
| `CUSTOMER` | 顾客列表（含累计消费额）、经营数据 | 28 | ✅ | ✅ | — | — | — | — |
| `STORE_ADMIN` | 建店、改名、停用、设默认店、挂收款号 | 24 | ✅ | — | — | — | — | — |
| `STORE` | 门店经营面：装修、配送规则、店铺码、分享物料 | 23 | ✅ | ✅ | — | — | — | — |
| `PRINT_READ` | 角色 → 权限码 | 15 | ✅ | ✅ | ✅ | — | — | — |
| `PRINT_CONFIG` | 角色 → 权限码 | 15 | ✅ | ✅ | — | — | — | — |
| `PRINT_RESOLVE` | 角色 → 权限码 | 14 | ✅ | ✅ | — | — | — | — |
| `SHIP` | 发货、标记自送送达 | 10 | ✅ | ✅ | ✅ | — | ✅ | — |
| `SV_RECHARGE_CREATE` | 查看主体级权益目录，不授予变更能力 | 9 | ✅ | — | — | — | — | — |
| `AFTERSALE` | 售后同意/驳回/收货 | 8 | ✅ | ✅ | — | — | — | ✅ |
| `VERIFY` | 核销、批量核销、按码搜索 | 7 | ✅ | ✅ | ✅ | — | — | — |
| `ORDER_VIEW` | 订单列表与详情、工作台待办 | 7 | ✅ | ✅ | ✅ | — | ✅ | ✅ |
| `SV_ACCOUNT_READ` | 查看主体级权益目录，不授予变更能力 | 7 | ✅ | — | — | — | — | — |
| `PRINT_DEVICE` | 角色 → 权限码 | 6 | ✅ | ✅ | — | — | — | — |
| `SV_CONSUME_CREATE` | 查看主体级权益目录，不授予变更能力 | 5 | ✅ | — | — | — | — | — |
| `RECEIVE` | 到货登记、分拣单、短少上报 | 4 | ✅ | ✅ | ✅ | ✅ | — | — |
| `ENT_READ` | 查看主体级权益目录，不授予变更能力 | 4 | ✅ | — | — | — | — | — |
| `PRICING_CONFIG_READ` | Shared pricing configuration: read, draf | 3 | ✅ | — | — | — | — | — |
| `PRICING_CONFIG_PUBLISH` | Shared pricing configuration: read, draf | 3 | ✅ | — | — | — | — | — |
| `REVIEW` | 评价回复、差评申诉 | 3 | ✅ | ✅ | — | — | — | ✅ |
| `ENT_USE` | 查看主体级权益目录，不授予变更能力 | 3 | ✅ | — | — | — | — | — |
| `PRINT_EXPORT` | 角色 → 权限码 | 3 | ✅ | ✅ | ✅ | — | — | — |
| `PRICING_TAX_UPDATE` | 修改全主体所有门店的税配置，仅主体属主，不可按门店委派 | 2 | ✅ | — | — | — | — | — |
| `PRICING_CONFIG_UPDATE` | Shared pricing configuration: read, draf | 2 | ✅ | — | — | — | — | — |
| `SV_PLAN_READ` | 查看主体级权益目录，不授予变更能力 | 2 | ✅ | — | — | — | — | — |
| `SV_PLAN_MANAGE` | 查看主体级权益目录，不授予变更能力 | 2 | ✅ | — | — | — | — | — |
| `SV_RECHARGE_RECEIVE` | 查看主体级权益目录，不授予变更能力 | 2 | ✅ | — | — | — | — | — |
| `SV_RECHARGE_READ` | 查看主体级权益目录，不授予变更能力 | 2 | ✅ | — | — | — | — | — |
| `ENT_TEMPLATE_WRITE` | 编辑权益草稿，不能发布 | 2 | ✅ | — | — | — | — | — |
| `ENT_TEMPLATE_PUBLISH` | 发布与停发影响全主体，需单独授权 | 2 | ✅ | — | — | — | — | — |
| `PRINT_REPRINT` | 角色 → 权限码 | 2 | ✅ | ✅ | ✅ | — | — | — |
| `PRINT_SIMULATE` | 角色 → 权限码 | 2 | ✅ | ✅ | ✅ | — | — | — |
| `ENT_ISSUE` | 查看主体级权益目录，不授予变更能力 | 1 | ✅ | — | — | — | — | — |
| `ENT_ISSUE_MANAGE` | 调整发行容量，不直接发放实例 | 1 | ✅ | — | — | — | — | — |
| `PRINT_SUBMIT` | 角色 → 权限码 | 1 | ✅ | ✅ | ✅ | — | — | — |
| `PRINT_EXTRA` | 角色 → 权限码 | 1 | ✅ | ✅ | — | — | — | — |
| `PRICING_TAX_READ` | 查看当前授权商家的有效税配置 | 0 | ✅ | — | — | — | — | — |
| `REPORT_EXPORT` | 改库存（含门店库存） | 0 | ✅ | — | — | — | — | — |
| `ENT_TEMPLATE_READ` | 查看主体级权益目录，不授予变更能力 | 0 | ✅ | — | — | — | — | — |

**只有 OWNER 能碰的 22 项**：`FINANCE`、`STORE_ADMIN`、`SV_RECHARGE_CREATE`、`SV_ACCOUNT_READ`、`SV_CONSUME_CREATE`、`ENT_READ`、`PRICING_CONFIG_READ`、`PRICING_CONFIG_PUBLISH`、`ENT_USE`、`PRICING_TAX_UPDATE`、`PRICING_CONFIG_UPDATE`、`SV_PLAN_READ`、`SV_PLAN_MANAGE`、`SV_RECHARGE_RECEIVE`、`SV_RECHARGE_READ`、`ENT_TEMPLATE_WRITE`、`ENT_TEMPLATE_PUBLISH`、`ENT_ISSUE`、`ENT_ISSUE_MANAGE`、`PRICING_TAX_READ`、`REPORT_EXPORT`、`ENT_TEMPLATE_READ`
—— 它们是「能把钱和人改掉」的那几组，连店长都不下放。

## 二、每个权限点覆盖的端点

### `STOCK`　（OWNER、MANAGER、CLERK、PICKER）

- `/biz/fulfillment/carriers`
- `/biz/goods`
- `/biz/goods/inv-mode`
- `/biz/goods/{goodsNo}`
- `/biz/goods/{goodsNo}/skus/{skuNo}/pricing-policy`
- `/biz/goods/{goodsNo}/stock`
- `/biz/goods/{goodsNo}/store-stock`
- `/biz/inventory/adjust`
- `/biz/inventory/balances`
- `/biz/inventory/category-setting`
- `/biz/inventory/counts`
- `/biz/inventory/counts/{no}`
- `/biz/inventory/counts/{no}/lines`
- `/biz/inventory/counts/{no}/post`
- `/biz/inventory/counts/{no}/void`
- `/biz/inventory/cross-store`
- `/biz/inventory/documents`
- `/biz/inventory/inbounds`
- `/biz/inventory/inbounds/{no}`
- `/biz/inventory/inbounds/{no}/post`
- `/biz/inventory/inbounds/{no}/void`
- `/biz/inventory/item-by-sku`
- `/biz/inventory/items/by-barcode`
- `/biz/inventory/items/{itemId}`
- `/biz/inventory/ledger`
- `/biz/inventory/locations`
- `/biz/inventory/outbounds`
- `/biz/inventory/outbounds/{no}/post`
- `/biz/inventory/outbounds/{no}/void`
- `/biz/inventory/pickable`
- `/biz/inventory/safety-stock`
- `/biz/inventory/summary`
- `/biz/inventory/suppliers`
- `/biz/inventory/suppliers/{no}`
- `/biz/inventory/suppliers/{no}/active`
- `/biz/inventory/transfers`
- `/biz/inventory/transfers/{no}`
- `/biz/inventory/transfers/{no}/receive`
- `/biz/inventory/transfers/{no}/ship`
- `/biz/inventory/transfers/{no}/void`
- `/biz/store/{storeNo}/offline-sale`
- `/biz/store/{storeNo}/offline-sale/{docNo}/revoke`
- `/biz/store/{storeNo}/sell-rules`
- `/biz/store/{storeNo}/stock-alignment`
- `/biz/store/{storeNo}/stock-alignment/batch/{batchNo}`
- `/biz/store/{storeNo}/stock-overview`
- `/biz/store/{storeNo}/stock-overview/batch`
- `/biz/store/{storeNo}/stock-reconcile/records`
- `/biz/store/{storeNo}/stock-reconcile/{operationNo}`
- `/biz/store/{storeNo}/stock-sync`

### `GOODS`　（OWNER、MANAGER）

- `/biz/card-product-bindings`
- `/biz/card-product-presets`
- `/biz/goods/describe`
- `/biz/goods/parse-text`
- `/biz/goods/recognize`
- `/biz/goods/save`
- `/biz/goods/zip-import`
- `/biz/goods/zip-plan`
- `/biz/goods/{goodsNo}/draft`
- `/biz/goods/{goodsNo}/draft/discard`
- `/biz/goods/{goodsNo}/inv-mode`
- `/biz/goods/{goodsNo}/pay-mode`
- `/biz/goods/{goodsNo}/presale`
- `/biz/goods/{goodsNo}/publish`
- `/biz/goods/{goodsNo}/publish-preview`
- `/biz/goods/{goodsNo}/revisions`
- `/biz/goods/{goodsNo}/revisions/{revisionNo}`
- `/biz/goods/{goodsNo}/revisions/{revisionNo}/fork`
- `/biz/goods/{goodsNo}/store-price`
- `/biz/goods/{goodsNo}/submit`
- `/biz/goods/{goodsNo}/toggle`
- `/biz/inventory/category-setting/{categoryNo}`
- `/biz/my-spec-dims`
- `/biz/my-spec-dims/{dimNo}/archive`
- `/biz/my-spec-dims/{dimNo}/rename`
- `/biz/pickable-props`
- `/biz/sku-identity/barcode`
- `/biz/sku-identity/export`
- `/biz/sku-identity/import`
- `/biz/sku-identity/import/plan`
- `/biz/spec-dims`
- `/biz/spec-dims/{dimNo}/values`
- `/biz/spec-override/{categoryNo}`
- `/biz/spec-props`
- `/biz/spec-templates`
- `/biz/spec-values`
- `/biz/spu-std`
- `/biz/store-spec-dims`

### `CAMPAIGN`　（OWNER、MANAGER）

- `/biz/activities`
- `/biz/activities/{activityNo}`
- `/biz/activities/{activityNo}/publish`
- `/biz/activities/{activityNo}/status`
- `/biz/activity-conflicts`
- `/biz/activity-presets`
- `/biz/campaign`
- `/biz/campaign/{campaignNo}/toggle`
- `/biz/coupon-issues`
- `/biz/coupons`
- `/biz/coupons/{couponNo}`
- `/biz/coupons/{couponNo}/issue`
- `/biz/coupons/{couponNo}/status`
- `/biz/group-request/pool`
- `/biz/group-request/{requestNo}/quote`
- `/biz/group/pickups`
- `/biz/group/{groupNo}`
- `/biz/group/{groupNo}/dissolve`
- `/biz/groups`
- `/biz/marketing-summary`
- `/biz/marketing/summary`
- `/biz/member-reach/send`
- `/biz/period`
- `/biz/period/{periodNo}`
- `/biz/period/{periodNo}/cutoff`
- `/biz/period/{periodNo}/decision`
- `/biz/period/{periodNo}/purchase-lines`
- `/biz/platform-activity`
- `/biz/platform-activity/{activityNo}`
- `/biz/platform-activity/{activityNo}/enrollment`
- `/biz/platform-activity/{activityNo}/withdraw`
- `/biz/pricing-manual-adjustments`
- `/biz/pricing-manual-adjustments/{no}`
- `/biz/pricing-manual-adjustments/{no}:quote`
- `/biz/quote/{quoteNo}/revise`

### `FINANCE`　（OWNER）

- `/biz/deposit`
- `/biz/deposit/txns`
- `/biz/merchant/debt`
- `/biz/merchant/pay-channel`
- `/biz/merchant/payment`
- `/biz/merchant/payment/store/{storeNo}`
- `/biz/merchant/payment/{payChannel}/refresh`
- `/biz/payout-account`
- `/biz/points/store-account`
- `/biz/points/store-agreement`
- `/biz/points/store-agreement/sign`
- `/biz/points/store-agreement/terminate`
- `/biz/points/store-entries`
- `/biz/points/store-statement`
- `/biz/points/store-statement-confirm`
- `/biz/points/store-statement-dispute`
- `/biz/points/store-statement-lines`
- `/biz/points/store-statements`
- `/biz/points/store-terms`
- `/biz/points/store-types`
- `/biz/pricing-manual-adjustments/{no}:approve`
- `/biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}`
- `/biz/settle/batch`
- `/biz/settle/bills`
- `/biz/settle/bills/{settleNo}`
- `/biz/settle/daily-flow`
- `/biz/settle/income`
- `/biz/settle/invoice-pending`
- `/biz/settle/invoice-title`
- `/biz/settle/invoices`
- `/biz/settle/rate-card`
- `/biz/settle/statement`

### `CUSTOMER`　（OWNER、MANAGER）

- `/biz/cross-store/compare`
- `/biz/cross-store/overview`
- `/biz/customers`
- `/biz/dashboard/stats`
- `/biz/inventory/export`
- `/biz/inventory/report/monthly`
- `/biz/inventory/report/ranking`
- `/biz/member-code/resolve`
- `/biz/member-reach/plan`
- `/biz/member-reach/task`
- `/biz/member-reach/task/{taskNo}`
- `/biz/member-segments`
- `/biz/member-segments/preview`
- `/biz/member-segments/{segmentNo}`
- `/biz/member-segments/{segmentNo}/remove`
- `/biz/member-tags`
- `/biz/member-tags/{tagNo}`
- `/biz/member-tags/{tagNo}/merge`
- `/biz/member-tags/{tagNo}/usage`
- `/biz/members`
- `/biz/members/audience-preview`
- `/biz/members/stats`
- `/biz/members/tags`
- `/biz/members/tags/batch`
- `/biz/members/{memberNo}`
- `/biz/report/daily`
- `/biz/report/goods`
- `/biz/report/monthly`

### `STORE_ADMIN`　（OWNER）

- `/biz/entities`
- `/biz/entity/{entityNo}`
- `/biz/inventory/locations/{id}/source`
- `/biz/member-settings`
- `/biz/plan`
- `/biz/plan/trial`
- `/biz/role-perms`
- `/biz/role/{roleCode}`
- `/biz/role/{roleCode}/delete`
- `/biz/roles`
- `/biz/staff`
- `/biz/staff/logs`
- `/biz/staff/{mchAccountNo}/status`
- `/biz/staff/{mchAccountNo}/store`
- `/biz/store/create`
- `/biz/store/{storeNo}/default`
- `/biz/store/{storeNo}/payment`
- `/biz/store/{storeNo}/rename`
- `/biz/store/{storeNo}/slug`
- `/biz/store/{storeNo}/status`
- `/biz/store/{storeNo}/stock-alignment/batch/{batchNo}/confirm`
- `/biz/store/{storeNo}/stock-alignment/confirm`
- `/biz/store/{storeNo}/stock-alignment/preview`
- `/biz/store/{storeNo}/stock-reconcile`

### `STORE`　（OWNER、MANAGER）

- `/biz/appointment-slots/{slotNo}/close`
- `/biz/communities/applies`
- `/biz/communities/apply`
- `/biz/communities/from-map`
- `/biz/delivery/rule`
- `/biz/pickup-points`
- `/biz/pickup-points/candidates`
- `/biz/qualifications`
- `/biz/qualifications/recognize`
- `/biz/qualifications/save`
- `/biz/store`
- `/biz/store/announcement`
- `/biz/store/announcement/recent/remove`
- `/biz/store/poster`
- `/biz/store/qrcode`
- `/biz/store/scope-preview`
- `/biz/store/share-kit`
- `/biz/store/{storeNo}/categories`
- `/biz/store/{storeNo}/pay-setting`
- `/biz/store/{storeNo}/ship-setting`
- `/biz/stores/{storeNo}/appointment-slots`
- `/biz/stores/{storeNo}/fulfillment`
- `/biz/stores/{storeNo}/fulfillment/{channel}/impact`

### `PRINT_READ`　（OWNER、MANAGER、CLERK）

- `/biz/print/adapters`
- `/biz/print/batches/{batchNo}`
- `/biz/print/changes`
- `/biz/print/health`
- `/biz/print/jobs`
- `/biz/print/jobs/{jobNo}`
- `/biz/print/jobs/{jobNo}/attempts`
- `/biz/print/jobs/{jobNo}/document`
- `/biz/print/operations/{operationNo}`
- `/biz/print/orders/{orderNo}/summary`
- `/biz/print/printers`
- `/biz/print/printers/{printerNo}`
- `/biz/print/requests`
- `/biz/print/requests/{requestNo}`
- `/biz/print/scenes`

### `PRINT_CONFIG`　（OWNER、MANAGER）

- `/biz/print/route-versions/{versionNo}/dry-run`
- `/biz/print/route-versions/{versionNo}/publish`
- `/biz/print/routes`
- `/biz/print/routes/{routeNo}/versions`
- `/biz/print/scene-policies`
- `/biz/print/scene-policies/{scene}`
- `/biz/print/scene-policies/{scene}/versions`
- `/biz/print/scenes/{scene}/examples`
- `/biz/print/scenes/{scene}/fields`
- `/biz/print/template-resolutions/preview`
- `/biz/print/template-versions/{versionNo}`
- `/biz/print/template-versions/{versionNo}/preview`
- `/biz/print/template-versions/{versionNo}/publish`
- `/biz/print/templates`
- `/biz/print/templates/{templateNo}/versions`

### `PRINT_RESOLVE`　（OWNER、MANAGER）

- `/biz/print/action-logs`
- `/biz/print/agents/{agentNo}/credential-rotations`
- `/biz/print/agents/{agentNo}/revocations`
- `/biz/print/incidents`
- `/biz/print/incidents/{incidentNo}/acknowledgements`
- `/biz/print/jobs/{jobNo}/manual-confirmations`
- `/biz/print/jobs/{jobNo}/receipts`
- `/biz/print/jobs/{jobNo}/result-queries`
- `/biz/print/jobs/{jobNo}/retries`
- `/biz/print/jobs/{jobNo}/stale-releases`
- `/biz/print/printers/{printerNo}/connection-changes`
- `/biz/print/printers/{printerNo}/credential-rotations`
- `/biz/print/printers/{printerNo}/uncertainty-resolutions`
- `/biz/print/requests/{requestNo}/generation-retries`

### `SHIP`　（OWNER、MANAGER、CLERK、COURIER）

- `/biz/order/{subOrderNo}/delivered`
- `/biz/order/{subOrderNo}/express`
- `/biz/order/{subOrderNo}/express/cancel`
- `/biz/order/{subOrderNo}/express/quotes`
- `/biz/order/{subOrderNo}/fulfillment-change`
- `/biz/order/{subOrderNo}/fulfillment-options`
- `/biz/order/{subOrderNo}/prepare`
- `/biz/order/{subOrderNo}/ship`
- `/biz/order/{subOrderNo}/ship/precheck`
- `/biz/order/{subOrderNo}/waybill`

### `SV_RECHARGE_CREATE`　（OWNER）

- `/biz/stored-value/plan-recharges`
- `/biz/stored-value/plan-recharges/cancel-create`
- `/biz/stored-value/plan-recharges/confirm-offline`
- `/biz/stored-value/plan-recharges/resolve`
- `/biz/stored-value/plan-recharges/{rechargeNo}/cancel`
- `/biz/stored-value/plan-recharges/{rechargeNo}/submit`
- `/biz/stored-value/recharges`
- `/biz/stored-value/recharges/{rechargeNo}/cancel`
- `/biz/stored-value/recharges/{rechargeNo}/submit`

### `AFTERSALE`　（OWNER、MANAGER、CS）

- `/biz/after-sale`
- `/biz/after-sale/by-order/{subOrderNo}`
- `/biz/after-sale/worklist`
- `/biz/after-sale/{afterSaleNo}`
- `/biz/after-sale/{afterSaleNo}/approve`
- `/biz/after-sale/{afterSaleNo}/confirm-refund`
- `/biz/after-sale/{afterSaleNo}/receive`
- `/biz/after-sale/{afterSaleNo}/reject`

### `VERIFY`　（OWNER、MANAGER、CLERK）

- `/biz/coupon-redeem`
- `/biz/coupon-redeem/{code}`
- `/biz/pickup/orders`
- `/biz/pickup/overview`
- `/biz/pickup/verify`
- `/biz/pickup/verify/batch`
- `/biz/pickup/verify/search`

### `ORDER_VIEW`　（OWNER、MANAGER、CLERK、COURIER、CS）

- `/biz/order`
- `/biz/order/worklist`
- `/biz/order/{orderNo}/points-earning`
- `/biz/order/{subOrderNo}`
- `/biz/order/{subOrderNo}/inventory-trace`
- `/biz/order/{subOrderNo}/logistics-updates`
- `/biz/order/{subOrderNo}/trace`

### `SV_ACCOUNT_READ`　（OWNER）

- `/biz/stored-value/account-directory`
- `/biz/stored-value/accounts`
- `/biz/stored-value/accounts/{accountRef}`
- `/biz/stored-value/records`
- `/biz/stored-value/records/{recordNo}`
- `/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/accounts`
- `/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/records`

### `PRINT_DEVICE`　（OWNER、MANAGER）

- `/biz/print/agents`
- `/biz/print/agents/{agentNo}`
- `/biz/print/agents/{agentNo}/enrollments`
- `/biz/print/printers/{printerNo}/probes`
- `/biz/print/printers/{printerNo}/status-changes`
- `/biz/print/printers/{printerNo}/test-jobs`

### `SV_CONSUME_CREATE`　（OWNER）

- `/biz/stored-value/consumptions`
- `/biz/stored-value/consumptions/orders/{orderNo}`
- `/biz/stored-value/consumptions/orders/{orderNo}/cancel`
- `/biz/stored-value/consumptions/preview`
- `/biz/stored-value/consumptions/resolve`

### `RECEIVE`　（OWNER、MANAGER、CLERK、PICKER）

- `/biz/order/{subOrderNo}/confirm-offline-pay`
- `/biz/pickup/arrived`
- `/biz/pickup/picking`
- `/biz/pickup/{orderNo}/report`

### `ENT_READ`　（OWNER）

- `/biz/entitlement-issue-batches/{batchNo}`
- `/biz/entitlement-usage`
- `/biz/entitlement-uses/{operationNo}`
- `/biz/entitlements/{instanceNo}`

### `PRICING_CONFIG_READ`　（OWNER）

- `/biz/pricing-configs/{no}/draft`
- `/biz/pricing-configs/{no}/preview`
- `/biz/pricing-configs/{no}/revisions/{revisionNo}`

### `PRICING_CONFIG_PUBLISH`　（OWNER）

- `/biz/pricing-configs/initialize`
- `/biz/pricing-configs/{no}/publish`
- `/biz/pricing-configs/{no}/rollback`

### `REVIEW`　（OWNER、MANAGER、CS）

- `/biz/review`
- `/biz/review/{reviewNo}/appeal`
- `/biz/review/{reviewNo}/reply`

### `ENT_USE`　（OWNER）

- `/biz/entitlement-uses`
- `/biz/entitlement-uses/preview`
- `/biz/entitlement-uses/{operationNo}/cancel`

### `PRINT_EXPORT`　（OWNER、MANAGER、CLERK）

- `/biz/print/exports`
- `/biz/print/exports/{exportNo}`
- `/biz/print/exports/{exportNo}/download-grants`

### `PRICING_TAX_UPDATE`　（OWNER）

- `/biz/pricing-tax-settings`
- `/biz/pricing-tax-settings/reset`

### `PRICING_CONFIG_UPDATE`　（OWNER）

- `/biz/pricing-configs/{no}/reset`
- `/biz/pricing-configs/{no}/upgrade`

### `SV_PLAN_READ`　（OWNER）

- `/biz/stored-value/context`
- `/biz/stored-value/plans/{planNo}`

### `SV_PLAN_MANAGE`　（OWNER）

- `/biz/stored-value/plans`
- `/biz/stored-value/plans/{planNo}/status`

### `SV_RECHARGE_RECEIVE`　（OWNER）

- `/biz/stored-value/plan-recharges/{rechargeNo}/receive`
- `/biz/stored-value/recharges/{rechargeNo}/receive`

### `SV_RECHARGE_READ`　（OWNER）

- `/biz/stored-value/plan-recharges/{rechargeNo}`
- `/biz/stored-value/recharges/{rechargeNo}`

### `ENT_TEMPLATE_WRITE`　（OWNER）

- `/biz/entitlement-templates`
- `/biz/entitlement-templates/{templateNo}/draft`

### `ENT_TEMPLATE_PUBLISH`　（OWNER）

- `/biz/entitlement-templates/{templateNo}/publish`
- `/biz/entitlement-templates/{templateNo}/status`

### `PRINT_REPRINT`　（OWNER、MANAGER、CLERK）

- `/biz/print/jobs/{jobNo}/reprints`
- `/biz/print/reprints`

### `PRINT_SIMULATE`　（OWNER、MANAGER、CLERK）

- `/biz/print/simulations`
- `/biz/print/simulations/{simulationNo}`

### `ENT_ISSUE`　（OWNER）

- `/biz/entitlement-issue-batches`

### `ENT_ISSUE_MANAGE`　（OWNER）

- `/biz/entitlement-templates/{templateNo}/issue-policy`

### `PRINT_SUBMIT`　（OWNER、MANAGER、CLERK）

- `/biz/print/jobs/{jobNo}/cancellations`

### `PRINT_EXTRA`　（OWNER、MANAGER）

- `/biz/print/jobs/{jobNo}/extra-copies`

> **空角色 = 零权限**，不是「零权限 = 全放行」——`BizPerms.can` 对空集合直接返回 false。

> ⚠️ **这里只有 6 个平台预置角色**。商家还能建自定义角色（V71 `mch_role`，
> 权限点在 `BizPerms.assignableCodes()` 里挑，**不含 `biz:store:admin`**）——
> 它们按主体存库，不在这份生成物里。判「某个人能做什么」要看他持有的角色，不是这张表。

