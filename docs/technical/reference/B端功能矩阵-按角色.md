# B 端功能矩阵 · 按角色

> **本文是生成的**：`node scripts/gen-biz-role-matrix.mjs`。改了 `BizPerms` 或
> `BizEndpointPermTest.REQUIRED` 之后重跑一次，不要手改这份产物。

> 三份来源：权限点取自 `BizPerms`，角色→权限取自 `BizPerms.ROLE_PERMS`，
> 端点→权限取自 `BizEndpointPermTest.REQUIRED` —— 最后那份是唯一**被守卫强制对过账**的
> 清单（每个 `/biz` 端点都必须在里面有个说法，漏登记就红），所以比任何手写文档都可信。

统计：**6 个角色 × 13 个权限点 × 157 个受控端点**。

## 一、角色 × 权限

`OWNER` 是 `*` —— **不是「拥有全部权限点」，是「不走这张表」**。新增权限点时 OWNER 自动有，
其余角色需要显式加：老板不该因为上了个新功能就被锁在外面。

| 权限点 | 含义 | 端点数 | OWNER | MANAGER | CLERK | PICKER | COURIER | CS |
|---|---|---|---|---|---|---|---|---|
| `STOCK` | 改库存（含门店库存） | 26 | ✅ | ✅ | ✅ | ✅ | — | — |
| `GOODS` | 建/改商品、上下架、规格模板、识图 | 22 | ✅ | ✅ | — | — | — | — |
| `STORE` | 门店经营面：装修、配送规则、店铺码、分享物料 | 19 | ✅ | ✅ | — | — | — | — |
| `STORE_ADMIN` | 建店、改名、停用、设默认店、挂收款号 | 19 | ✅ | — | — | — | — | — |
| `CUSTOMER` | 顾客列表（含累计消费额）、经营数据 | 18 | ✅ | ✅ | — | — | — | — |
| `CAMPAIGN` | 营销活动、开团、报价 | 16 | ✅ | ✅ | — | — | — | — |
| `FINANCE` | 结算账单、费率卡、收款进件、积分开关 | 15 | ✅ | — | — | — | — | — |
| `VERIFY` | 核销、批量核销、按码搜索 | 7 | ✅ | ✅ | ✅ | — | — | — |
| `RECEIVE` | 到货登记、分拣单、短少上报 | 4 | ✅ | ✅ | ✅ | ✅ | — | — |
| `AFTERSALE` | 售后同意/驳回/收货 | 4 | ✅ | ✅ | — | — | — | ✅ |
| `REVIEW` | 评价回复、差评申诉 | 3 | ✅ | ✅ | — | — | — | ✅ |
| `SHIP` | 发货、标记自送送达 | 2 | ✅ | ✅ | ✅ | — | ✅ | — |
| `ORDER_VIEW` | 订单列表与详情、工作台待办 | 2 | ✅ | ✅ | ✅ | — | ✅ | ✅ |

**只有 OWNER 能碰的 2 项**：`STORE_ADMIN`、`FINANCE`
—— 它们是「能把钱和人改掉」的那几组，连店长都不下放。

## 二、每个权限点覆盖的端点

### `STOCK`　（OWNER、MANAGER、CLERK、PICKER）

- `/biz/goods`
- `/biz/goods/{goodsNo}`
- `/biz/goods/{goodsNo}/stock`
- `/biz/goods/{goodsNo}/store-stock`
- `/biz/inventory/adjust`
- `/biz/inventory/balances`
- `/biz/inventory/counts`
- `/biz/inventory/counts/{no}`
- `/biz/inventory/counts/{no}/lines`
- `/biz/inventory/counts/{no}/post`
- `/biz/inventory/documents`
- `/biz/inventory/inbounds`
- `/biz/inventory/inbounds/{no}`
- `/biz/inventory/inbounds/{no}/post`
- `/biz/inventory/inbounds/{no}/void`
- `/biz/inventory/items/{itemId}`
- `/biz/inventory/ledger`
- `/biz/inventory/locations`
- `/biz/inventory/outbounds`
- `/biz/inventory/outbounds/{no}/post`
- `/biz/inventory/outbounds/{no}/void`
- `/biz/inventory/summary`
- `/biz/inventory/transfers`
- `/biz/inventory/transfers/{no}`
- `/biz/inventory/transfers/{no}/receive`
- `/biz/inventory/transfers/{no}/ship`

### `GOODS`　（OWNER、MANAGER）

- `/biz/goods/describe`
- `/biz/goods/recognize`
- `/biz/goods/save`
- `/biz/goods/{goodsNo}/presale`
- `/biz/goods/{goodsNo}/store-price`
- `/biz/goods/{goodsNo}/submit`
- `/biz/goods/{goodsNo}/toggle`
- `/biz/my-spec-dims`
- `/biz/my-spec-dims/{dimNo}/archive`
- `/biz/my-spec-dims/{dimNo}/rename`
- `/biz/pickable-props`
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
- `/biz/store/share-kit`
- `/biz/store/{storeNo}/categories`
- `/biz/stores/{storeNo}/appointment-slots`
- `/biz/stores/{storeNo}/fulfillment`

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
- `/biz/store/{storeNo}/status`

### `CUSTOMER`　（OWNER、MANAGER）

- `/biz/cross-store/compare`
- `/biz/cross-store/overview`
- `/biz/customers`
- `/biz/dashboard/stats`
- `/biz/inventory/export`
- `/biz/inventory/report/monthly`
- `/biz/inventory/report/ranking`
- `/biz/member-reach/plan`
- `/biz/member-segments`
- `/biz/member-segments/preview`
- `/biz/member-segments/{segmentNo}/remove`
- `/biz/member-tags`
- `/biz/member-tags/{tagNo}`
- `/biz/member-tags/{tagNo}/merge`
- `/biz/members`
- `/biz/members/stats`
- `/biz/members/tags`
- `/biz/members/{memberNo}`

### `CAMPAIGN`　（OWNER、MANAGER）

- `/biz/activities`
- `/biz/activities/{activityNo}`
- `/biz/activities/{activityNo}/status`
- `/biz/activity-conflicts`
- `/biz/campaign`
- `/biz/campaign/{campaignNo}/toggle`
- `/biz/coupon-issues`
- `/biz/coupons`
- `/biz/coupons/{couponNo}`
- `/biz/coupons/{couponNo}/issue`
- `/biz/coupons/{couponNo}/status`
- `/biz/group-request/pool`
- `/biz/group-request/{requestNo}/quote`
- `/biz/groups`
- `/biz/member-reach/send`
- `/biz/quote/{quoteNo}/revise`

### `FINANCE`　（OWNER）

- `/biz/deposit`
- `/biz/deposit/txns`
- `/biz/merchant/payment`
- `/biz/merchant/payment/store/{storeNo}`
- `/biz/merchant/payment/{payChannel}/refresh`
- `/biz/points/account`
- `/biz/points/records`
- `/biz/points/toggle`
- `/biz/settle/bills`
- `/biz/settle/bills/{settleNo}`
- `/biz/settle/income`
- `/biz/settle/invoice-title`
- `/biz/settle/invoices`
- `/biz/settle/rate-card`
- `/biz/settle/statement`

### `VERIFY`　（OWNER、MANAGER、CLERK）

- `/biz/coupon-redeem`
- `/biz/coupon-redeem/{code}`
- `/biz/pickup/orders`
- `/biz/pickup/overview`
- `/biz/pickup/verify`
- `/biz/pickup/verify/batch`
- `/biz/pickup/verify/search`

### `RECEIVE`　（OWNER、MANAGER、CLERK、PICKER）

- `/biz/order/{subOrderNo}/confirm-offline-pay`
- `/biz/pickup/arrived`
- `/biz/pickup/picking`
- `/biz/pickup/{orderNo}/report`

### `AFTERSALE`　（OWNER、MANAGER、CS）

- `/biz/after-sale`
- `/biz/after-sale/{afterSaleNo}/approve`
- `/biz/after-sale/{afterSaleNo}/receive`
- `/biz/after-sale/{afterSaleNo}/reject`

### `REVIEW`　（OWNER、MANAGER、CS）

- `/biz/review`
- `/biz/review/{reviewNo}/appeal`
- `/biz/review/{reviewNo}/reply`

### `SHIP`　（OWNER、MANAGER、CLERK、COURIER）

- `/biz/order/{subOrderNo}/delivered`
- `/biz/order/{subOrderNo}/ship`

### `ORDER_VIEW`　（OWNER、MANAGER、CLERK、COURIER、CS）

- `/biz/order`
- `/biz/order/{subOrderNo}`

> **空角色 = 零权限**，不是「零权限 = 全放行」——`BizPerms.can` 对空集合直接返回 false。

> ⚠️ **这里只有 6 个平台预置角色**。商家还能建自定义角色（V71 `mch_role`，
> 权限点在 `BizPerms.assignableCodes()` 里挑，**不含 `biz:store:admin`**）——
> 它们按主体存库，不在这份生成物里。判「某个人能做什么」要看他持有的角色，不是这张表。

