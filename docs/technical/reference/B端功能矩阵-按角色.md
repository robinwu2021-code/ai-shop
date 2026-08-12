# B 端功能矩阵 · 按角色

> **本文是生成的**：`node scripts/gen-biz-role-matrix.mjs`。改了 `BizPerms` 或
> `BizEndpointPermTest.REQUIRED` 之后重跑一次，不要手改这份产物。

> 三份来源：权限点取自 `BizPerms`，角色→权限取自 `BizPerms.ROLE_PERMS`，
> 端点→权限取自 `BizEndpointPermTest.REQUIRED` —— 最后那份是唯一**被守卫强制对过账**的
> 清单（每个 `/biz` 端点都必须在里面有个说法，漏登记就红），所以比任何手写文档都可信。

统计：**6 个角色 × 13 个权限点 × 62 个受控端点**。

## 一、角色 × 权限

`OWNER` 是 `*` —— **不是「拥有全部权限点」，是「不走这张表」**。新增权限点时 OWNER 自动有，
其余角色需要显式加：老板不该因为上了个新功能就被锁在外面。

| 权限点 | 含义 | 端点数 | OWNER | MANAGER | CLERK | PICKER | COURIER | CS |
|---|---|---|---|---|---|---|---|---|
| `FINANCE` | 结算账单、费率卡、收款进件、积分开关 | 14 | ✅ | — | — | — | — | — |
| `STORE_ADMIN` | 建店、改名、停用、设默认店、挂收款号 | 8 | ✅ | — | — | — | — | — |
| `CAMPAIGN` | 营销活动、开团、报价 | 6 | ✅ | ✅ | — | — | — | — |
| `STORE` | 门店经营面：装修、配送规则、店铺码、分享物料 | 6 | ✅ | ✅ | — | — | — | — |
| `VERIFY` | 核销、批量核销、按码搜索 | 5 | ✅ | ✅ | ✅ | — | — | — |
| `STOCK` | 改库存（含门店库存） | 4 | ✅ | ✅ | ✅ | ✅ | — | — |
| `AFTERSALE` | 售后同意/驳回/收货 | 4 | ✅ | ✅ | — | — | — | ✅ |
| `RECEIVE` | 到货登记、分拣单、短少上报 | 3 | ✅ | ✅ | ✅ | ✅ | — | — |
| `GOODS` | 建/改商品、上下架、规格模板、识图 | 3 | ✅ | ✅ | — | — | — | — |
| `REVIEW` | 评价回复、差评申诉 | 3 | ✅ | ✅ | — | — | — | ✅ |
| `SHIP` | 发货、标记自送送达 | 2 | ✅ | ✅ | ✅ | — | ✅ | — |
| `ORDER_VIEW` | 订单列表与详情、工作台待办 | 2 | ✅ | ✅ | ✅ | — | ✅ | ✅ |
| `CUSTOMER` | 顾客列表（含累计消费额）、经营数据 | 2 | ✅ | ✅ | — | — | — | — |

**只有 OWNER 能碰的 2 项**：`FINANCE`、`STORE_ADMIN`
—— 它们是「能把钱和人改掉」的那几组，连店长都不下放。

## 二、每个权限点覆盖的端点

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
- `/biz/settle/invoice-title`
- `/biz/settle/invoices`
- `/biz/settle/rate-card`
- `/biz/settle/statement`

### `STORE_ADMIN`　（OWNER）

- `/biz/staff`
- `/biz/staff/{mchAccountNo}/status`
- `/biz/staff/{mchAccountNo}/store`
- `/biz/store/create`
- `/biz/store/{storeNo}/default`
- `/biz/store/{storeNo}/payment`
- `/biz/store/{storeNo}/rename`
- `/biz/store/{storeNo}/status`

### `CAMPAIGN`　（OWNER、MANAGER）

- `/biz/campaign`
- `/biz/campaign/{campaignNo}/toggle`
- `/biz/group-request/pool`
- `/biz/group-request/{requestNo}/quote`
- `/biz/groups`
- `/biz/quote/{quoteNo}/revise`

### `STORE`　（OWNER、MANAGER）

- `/biz/communities/applies`
- `/biz/communities/apply`
- `/biz/delivery/rule`
- `/biz/store`
- `/biz/store/qrcode`
- `/biz/store/share-kit`

### `VERIFY`　（OWNER、MANAGER、CLERK）

- `/biz/pickup/orders`
- `/biz/pickup/overview`
- `/biz/pickup/verify`
- `/biz/pickup/verify/batch`
- `/biz/pickup/verify/search`

### `STOCK`　（OWNER、MANAGER、CLERK、PICKER）

- `/biz/goods`
- `/biz/goods/{goodsNo}`
- `/biz/goods/{goodsNo}/stock`
- `/biz/goods/{goodsNo}/store-stock`

### `AFTERSALE`　（OWNER、MANAGER、CS）

- `/biz/after-sale`
- `/biz/after-sale/{afterSaleNo}/approve`
- `/biz/after-sale/{afterSaleNo}/receive`
- `/biz/after-sale/{afterSaleNo}/reject`

### `RECEIVE`　（OWNER、MANAGER、CLERK、PICKER）

- `/biz/pickup/arrived`
- `/biz/pickup/picking`
- `/biz/pickup/{orderNo}/report`

### `GOODS`　（OWNER、MANAGER）

- `/biz/goods/recognize`
- `/biz/goods/save`
- `/biz/goods/{goodsNo}/toggle`

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

### `CUSTOMER`　（OWNER、MANAGER）

- `/biz/customers`
- `/biz/dashboard/stats`

> **空角色 = 零权限**，不是「零权限 = 全放行」——`BizPerms.can` 对空集合直接返回 false。

