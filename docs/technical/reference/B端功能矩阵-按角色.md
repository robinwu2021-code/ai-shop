# B 端功能矩阵 · 按角色

> ⚠️ **本文当前是脚本一次性产出的，还没有生成器 —— 改了权限它不会自己更新。**
> 三份来源都在代码里（见下），下一步应固化成 `scripts/gen-biz-role-matrix.mjs`，
> 与 `gen-tri-end-matrix.mjs` 同类。在那之前，**改 `BizPerms` 或
> `BizEndpointPermTest.REQUIRED` 之后要回来对一遍这份文档**，
> 否则它会变成一份长得像权威、实际已过期的东西 —— 而这正是本项目反复踩过的坑。

> **生成依据**：权限点取自 `BizPerms`，角色→权限取自 `BizPerms.ROLE_PERMS`，
> 端点→权限取自 `BizEndpointPermTest.REQUIRED`（那是唯一一份**被守卫强制对过账**的清单：
> 每个 `/biz` 端点都必须在里面有个说法，漏登记就红）。

## 一、角色 × 权限

`OWNER` 是 `*` —— **不是「拥有全部 13 项」，是「不走这张表」**。
新增权限点时 OWNER 自动有，其余角色需要显式加，这是有意的：老板不该因为上了个新功能就被锁在外面。

| 权限点 | 含义 | 端点数 | OWNER | MANAGER | CLERK | PICKER | COURIER | CS |
|---|---|---|---|---|---|---|---|---|
| `FINANCE` | 资金与结算 | 14 | ✅ | — | — | — | — | — |
| `STORE_ADMIN` | 门店治理（员工/角色/进件） | 8 | ✅ | — | — | — | — | — |
| `CAMPAIGN` | 营销活动 | 6 | ✅ | ✅ | — | — | — | — |
| `STORE` | 门店信息 | 6 | ✅ | ✅ | — | — | — | — |
| `VERIFY` | 核销 | 5 | ✅ | ✅ | ✅ | — | — | — |
| `STOCK` | 改库存 | 4 | ✅ | ✅ | ✅ | ✅ | — | — |
| `AFTERSALE` | 售后处理 | 4 | ✅ | ✅ | — | — | — | ✅ |
| `RECEIVE` | 到货登记 | 3 | ✅ | ✅ | ✅ | ✅ | — | — |
| `GOODS` | 商品管理 | 3 | ✅ | ✅ | — | — | — | — |
| `REVIEW` | 回评价 | 3 | ✅ | ✅ | — | — | — | ✅ |
| `SHIP` | 发货 | 2 | ✅ | ✅ | ✅ | — | ✅ | — |
| `ORDER_VIEW` | 看订单 | 2 | ✅ | ✅ | ✅ | — | ✅ | ✅ |
| `CUSTOMER` | 客户资料 | 2 | ✅ | ✅ | — | — | — | — |

**两个只有 OWNER 有的权限**：`STORE_ADMIN`（员工/角色/进件）与 `FINANCE`（资金与结算）——
它们是**能把钱和人改掉**的两组，不下放给店长。

## 二、每个权限点覆盖的端点

### `FINANCE` · 资金与结算　（OWNER）

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

### `STORE_ADMIN` · 门店治理（员工/角色/进件）　（OWNER）

- `/biz/staff`
- `/biz/staff/{mchAccountNo}/status`
- `/biz/staff/{mchAccountNo}/store`
- `/biz/store/create`
- `/biz/store/{storeNo}/default`
- `/biz/store/{storeNo}/payment`
- `/biz/store/{storeNo}/rename`
- `/biz/store/{storeNo}/status`

### `CAMPAIGN` · 营销活动　（OWNER、MANAGER）

- `/biz/campaign`
- `/biz/campaign/{campaignNo}/toggle`
- `/biz/group-request/pool`
- `/biz/group-request/{requestNo}/quote`
- `/biz/groups`
- `/biz/quote/{quoteNo}/revise`

### `STORE` · 门店信息　（OWNER、MANAGER）

- `/biz/communities/applies`
- `/biz/communities/apply`
- `/biz/delivery/rule`
- `/biz/store`
- `/biz/store/qrcode`
- `/biz/store/share-kit`

### `VERIFY` · 核销　（OWNER、MANAGER、CLERK）

- `/biz/pickup/orders`
- `/biz/pickup/overview`
- `/biz/pickup/verify`
- `/biz/pickup/verify/batch`
- `/biz/pickup/verify/search`

### `STOCK` · 改库存　（OWNER、MANAGER、CLERK、PICKER）

- `/biz/goods`
- `/biz/goods/{goodsNo}`
- `/biz/goods/{goodsNo}/stock`
- `/biz/goods/{goodsNo}/store-stock`

### `AFTERSALE` · 售后处理　（OWNER、MANAGER、CS）

- `/biz/after-sale`
- `/biz/after-sale/{afterSaleNo}/approve`
- `/biz/after-sale/{afterSaleNo}/receive`
- `/biz/after-sale/{afterSaleNo}/reject`

### `RECEIVE` · 到货登记　（OWNER、MANAGER、CLERK、PICKER）

- `/biz/pickup/arrived`
- `/biz/pickup/picking`
- `/biz/pickup/{orderNo}/report`

### `GOODS` · 商品管理　（OWNER、MANAGER）

- `/biz/goods/recognize`
- `/biz/goods/save`
- `/biz/goods/{goodsNo}/toggle`

### `REVIEW` · 回评价　（OWNER、MANAGER、CS）

- `/biz/review`
- `/biz/review/{reviewNo}/appeal`
- `/biz/review/{reviewNo}/reply`

### `SHIP` · 发货　（OWNER、MANAGER、CLERK、COURIER）

- `/biz/order/{subOrderNo}/delivered`
- `/biz/order/{subOrderNo}/ship`

### `ORDER_VIEW` · 看订单　（OWNER、MANAGER、CLERK、COURIER、CS）

- `/biz/order`
- `/biz/order/{subOrderNo}`

### `CUSTOMER` · 客户资料　（OWNER、MANAGER）

- `/biz/customers`
- `/biz/dashboard/stats`

## 三、无需授权的端点

`PUBLIC` 74 条（登录即可，如自己的资料、消息、公共字典），
`ANY_OF` 1 条（汇总型，任一权限即可进，粒度由端上按 perms 裁）。

> **空角色 = 零权限**，不是「零权限 = 全放行」——`BizPerms.can` 对空集合直接返回 false。

