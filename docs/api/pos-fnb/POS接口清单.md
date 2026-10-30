# 餐饮 POS 对接接口

版本0.1，2026-10-30。**固定URL合同草案：目前后端未实现这些固定路由，不能直接向线上发起调用。** 已部署接口仍为/biz/x/food等原路径。本册用于外部评审及后续适配，不宣称已上线。

## 地址

- 主地址：https://www.hxmall.top/pos/fnb
- 餐饮API入口：https://api.hxmall.top/fnb；路由、证书及等价反代尚未核验。
- 地址来自本次用户要求，不是对现网路由已生效的确认。api.hxmall.top入口直接使用/fnb前缀，不额外拼接/pos/fnb。

## 调用约定

只使用GET和POST，URL路径固定，不包含订单号、门店号等动态段。GET参数放在?后；POST参数放JSON，不把业务参数拼入路径。身份使用Authorization: Bearer <token>，POST为Content-Type: application/json。业务参数storeNo由服务端核对权限，操作者和主体从登录态取得。

除登录外，所有POST带body.idempotencyKey，适配层须映射到原服务幂等机制；重试沿用原键和原内容。version从服务端最近查询结果获取，不能自行递增。?表示字段可选；其余为合同必填。decimal使用十进制字符串传输，金额Minor为币种最小单位整数；不跨币种相加。

统一响应：{"code":0,"msg":"success","data":{...}}。下表输出是data结构。业务失败返回非0 code，data为空；具体错误码沿平台错误码体系及适配后的验收结果，不新增虚假成功码。超时或UNKNOWN先按原操作号查询，不能重新创建结账或重复扣减。GET返回的是实时数据，不承诺冻结快照。

字段、枚举和这些简化输出是拟定的POS合同，需要适配层与既有领域DTO逐项映射后验收；不等于原接口DTO。涉及真实收款、积分、储值及退款执行，仍需专项接入，本文不宣称已接通。退餐库存处置成功不能代表退款成功。打印排队成功不能代表纸面打印成功。当前仅做文档，不改变生产服务。

## URL总表

| 模块 | 功能 | 方法 | 固定路径（拼接主地址） |
|---|---|---|---|
|基础|终端登录|POST|/auth/login|
|基础|读取门店营业信息|GET|/store/info|
|基础|当前操作员权限|GET|/auth/profile|
|桌台|桌台列表|GET|/table/list|
|桌台|开台|POST|/table/open|
|桌台|账单详情|GET|/check/detail|
|桌台|转台|POST|/table/transfer|
|桌台|拼桌|POST|/table/combine|
|桌台|分单或AA预览|POST|/check/split/preview|
|桌台|确认分单方案|POST|/check/split/confirm|
|菜品|可售餐牌列表|GET|/menu/list|
|菜品|餐牌菜品与分类|GET|/menu/detail|
|菜品|菜品规格配料和套餐|GET|/dish/detail|
|购物车|创建购物车|POST|/cart/create|
|购物车|查询购物车|GET|/cart/detail|
|购物车|新增或修改购物车行|POST|/cart/line/save|
|购物车|移除购物车行|POST|/cart/line/remove|
|结账|金额试算|POST|/pricing/quote|
|订单|提交订单|POST|/order/submit|
|订单|订单列表|GET|/order/list|
|订单|订单详情|GET|/order/detail|
|订单|取消订单|POST|/order/cancel|
|结账|结账试算与确认依据|POST|/checkout/preview|
|结账|提交结账|POST|/checkout/confirm|
|结账|查询结账结果|GET|/checkout/result|
|厨房|工位任务列表|GET|/kitchen/task/list|
|厨房|任务操作|POST|/kitchen/task/action|
|售后|提交退餐|POST|/return/create|
|售后|退餐详情|GET|/return/detail|
|售后|处理退餐库存|POST|/return/stock/apply|
|售后|重试退餐库存|POST|/return/stock/retry|
|打印|申请打印任务|POST|/print/create|
|打印|打印任务列表|GET|/print/list|
|打印|重试打印任务|POST|/print/retry|
|库存|库存余额查询|GET|/stock/balance/list|
|库存|出品待处理列表|GET|/stock/production/pending|
|库存|原出品查回或重试|POST|/stock/production/retry|
|库存|镜像同步异常查询|GET|/stock/sync/list|
|库存|重试镜像同步|POST|/stock/sync/retry|
|营业|日结预览|POST|/close/preview|
|营业|日结签署|POST|/close/sign|
|营业|日结结果查询|GET|/close/detail|

## 输入与输出

### 1. 终端登录

`POST https://www.hxmall.top/pos/fnb/auth/login`

JSON参数：

|参数|类型|必填|
|---|---|---|
|account|string|是|
|password|string|是|
|terminalNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|accessToken|string|是|
|expiresAt|string|是|
|staff|Staff|是|
|stores|Store[]|是|

### 2. 读取门店营业信息

`GET https://www.hxmall.top/pos/fnb/store/info`

请求示例：`https://www.hxmall.top/pos/fnb/store/info?storeNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|store|Store|是|
|businessDate|string|是|
|payModes|string[]|是|
|capabilities|string[]|是|

### 3. 当前操作员权限

`GET https://www.hxmall.top/pos/fnb/auth/profile`

请求示例：`https://www.hxmall.top/pos/fnb/auth/profile?storeNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|staff|Staff|是|
|permissions|string[]|是|

### 4. 桌台列表

`GET https://www.hxmall.top/pos/fnb/table/list`

请求示例：`https://www.hxmall.top/pos/fnb/table/list?storeNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|areaNo|string|否|
|status|string|否|
|page|int|否|
|size|int|否|

data输出：

|参数|类型|必填|
|---|---|---|
|records|Table[]|是|
|total|int|是|
|page|int|是|
|size|int|是|

### 5. 开台

`POST https://www.hxmall.top/pos/fnb/table/open`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|tableNo|string|是|
|guestCount|int|是|

data输出：

|参数|类型|必填|
|---|---|---|
|check|Check|是|
|table|Table|是|

### 6. 账单详情

`GET https://www.hxmall.top/pos/fnb/check/detail`

请求示例：`https://www.hxmall.top/pos/fnb/check/detail?storeNo=示例值&checkNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|checkNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|check|Check|是|
|lines|OrderLine[]|是|
|amounts|Amounts|是|

### 7. 转台

`POST https://www.hxmall.top/pos/fnb/table/transfer`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|checkNo|string|是|
|targetTableNo|string|是|
|version|long|是|

data输出：

|参数|类型|必填|
|---|---|---|
|check|Check|是|

### 8. 拼桌

`POST https://www.hxmall.top/pos/fnb/table/combine`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|checkNo|string|是|
|targetTableNos|string[]|是|
|version|long|是|

data输出：

|参数|类型|必填|
|---|---|---|
|check|Check|是|

### 9. 分单或AA预览

`POST https://www.hxmall.top/pos/fnb/check/split/preview`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|checkNo|string|是|
|mode|string|是|
|allocations|Allocation[]|是|
|version|long|是|

data输出：

|参数|类型|必填|
|---|---|---|
|plan|SplitPlan|是|

### 10. 确认分单方案

`POST https://www.hxmall.top/pos/fnb/check/split/confirm`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|checkNo|string|是|
|planNo|string|是|
|version|long|是|

data输出：

|参数|类型|必填|
|---|---|---|
|plan|SplitPlan|是|

### 11. 可售餐牌列表

`GET https://www.hxmall.top/pos/fnb/menu/list`

请求示例：`https://www.hxmall.top/pos/fnb/menu/list?storeNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|channel|string|否|
|at|string|否|

data输出：

|参数|类型|必填|
|---|---|---|
|menus|Menu[]|是|

### 12. 餐牌菜品与分类

`GET https://www.hxmall.top/pos/fnb/menu/detail`

请求示例：`https://www.hxmall.top/pos/fnb/menu/detail?storeNo=示例值&menuNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|menuNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|menu|Menu|是|
|categories|Category[]|是|
|dishes|Dish[]|是|

### 13. 菜品规格配料和套餐

`GET https://www.hxmall.top/pos/fnb/dish/detail`

请求示例：`https://www.hxmall.top/pos/fnb/dish/detail?storeNo=示例值&menuNo=示例值&offerNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|menuNo|string|是|
|offerNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|dish|Dish|是|
|groups|SelectionGroup[]|是|

### 14. 创建购物车

`POST https://www.hxmall.top/pos/fnb/cart/create`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|checkNo|string|否|
|fulfillment|string|是|
|payMode|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|cart|Cart|是|

### 15. 查询购物车

`GET https://www.hxmall.top/pos/fnb/cart/detail`

请求示例：`https://www.hxmall.top/pos/fnb/cart/detail?storeNo=示例值&cartNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|cartNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|cart|Cart|是|
|lines|CartLine[]|是|

### 16. 新增或修改购物车行

`POST https://www.hxmall.top/pos/fnb/cart/line/save`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|cartNo|string|是|
|lineRef|string|是|
|offerNo|string|是|
|skuNo|string|是|
|quantity|decimal|是|
|selections|Selection[]|是|
|version|long|是|

data输出：

|参数|类型|必填|
|---|---|---|
|cart|Cart|是|
|lines|CartLine[]|是|

### 17. 移除购物车行

`POST https://www.hxmall.top/pos/fnb/cart/line/remove`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|cartNo|string|是|
|lineRef|string|是|
|version|long|是|

data输出：

|参数|类型|必填|
|---|---|---|
|cart|Cart|是|
|lines|CartLine[]|是|

### 18. 金额试算

`POST https://www.hxmall.top/pos/fnb/pricing/quote`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|cartNo|string|是|
|version|long|是|
|benefits|BenefitInput|否|
|manualAdjustment|ManualAdjustment|否|

data输出：

|参数|类型|必填|
|---|---|---|
|quote|Quote|是|

### 19. 提交订单

`POST https://www.hxmall.top/pos/fnb/order/submit`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|cartNo|string|是|
|version|long|是|
|quoteRef|string|是|
|quoteHash|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|batchNo|string|是|
|orderNo|string|是|
|status|string|是|
|amounts|Amounts|是|

### 20. 订单列表

`GET https://www.hxmall.top/pos/fnb/order/list`

请求示例：`https://www.hxmall.top/pos/fnb/order/list?storeNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|businessDate|string|否|
|status|string|否|
|page|int|否|
|size|int|否|

data输出：

|参数|类型|必填|
|---|---|---|
|records|Order[]|是|
|total|int|是|
|page|int|是|
|size|int|是|

### 21. 订单详情

`GET https://www.hxmall.top/pos/fnb/order/detail`

请求示例：`https://www.hxmall.top/pos/fnb/order/detail?storeNo=示例值&orderNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|orderNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|order|Order|是|
|lines|OrderLine[]|是|
|amounts|Amounts|是|

### 22. 取消订单

`POST https://www.hxmall.top/pos/fnb/order/cancel`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|orderNo|string|是|
|version|long|是|
|reasonCode|string|是|
|reason|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|orderNo|string|是|
|status|string|是|
|pending|boolean|是|

### 23. 结账试算与确认依据

`POST https://www.hxmall.top/pos/fnb/checkout/preview`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|checkNo|string|是|
|version|long|是|
|benefits|BenefitInput|否|
|manualAdjustment|ManualAdjustment|否|

data输出：

|参数|类型|必填|
|---|---|---|
|checkoutRef|string|是|
|quote|Quote|是|

### 24. 提交结账

`POST https://www.hxmall.top/pos/fnb/checkout/confirm`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|checkNo|string|是|
|checkoutRef|string|是|
|quoteHash|string|是|
|version|long|是|
|payment|PaymentInput|是|

data输出：

|参数|类型|必填|
|---|---|---|
|operationNo|string|是|
|status|string|是|
|pending|boolean|是|
|amounts|Amounts|是|

### 25. 查询结账结果

`GET https://www.hxmall.top/pos/fnb/checkout/result`

请求示例：`https://www.hxmall.top/pos/fnb/checkout/result?storeNo=示例值&operationNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|operationNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|operationNo|string|是|
|status|string|是|
|pending|boolean|是|
|amounts|Amounts|是|

### 26. 工位任务列表

`GET https://www.hxmall.top/pos/fnb/kitchen/task/list`

请求示例：`https://www.hxmall.top/pos/fnb/kitchen/task/list?storeNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|stationNo|string|否|
|status|string|否|
|page|int|否|
|size|int|否|

data输出：

|参数|类型|必填|
|---|---|---|
|records|KitchenTask[]|是|
|total|int|是|
|page|int|是|
|size|int|是|

### 27. 任务操作

`POST https://www.hxmall.top/pos/fnb/kitchen/task/action`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|taskNo|string|是|
|action|string|是|
|quantity|decimal|否|
|version|long|是|
|reason|string|否|

data输出：

|参数|类型|必填|
|---|---|---|
|task|KitchenTask|是|
|productionPending|boolean|是|

### 28. 提交退餐

`POST https://www.hxmall.top/pos/fnb/return/create`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|orderNo|string|是|
|items|ReturnItem[]|是|
|reasonCode|string|是|
|reason|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|returnNo|string|是|
|status|string|是|
|financialStatus|string|是|
|kitchenStatus|string|是|

### 29. 退餐详情

`GET https://www.hxmall.top/pos/fnb/return/detail`

请求示例：`https://www.hxmall.top/pos/fnb/return/detail?storeNo=示例值&returnNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|returnNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|returnNo|string|是|
|status|string|是|
|financialStatus|string|是|
|kitchenStatus|string|是|
|items|ReturnItem[]|是|

### 30. 处理退餐库存

`POST https://www.hxmall.top/pos/fnb/return/stock/apply`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|returnNo|string|是|
|returnItemNo|string|是|
|productionEventNo|string|是|
|quantity|decimal|是|
|disposition|string|是|
|reasonCode|string|是|
|reason|string|是|
|evidenceRef|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|disposition|StockDisposition|是|

### 31. 重试退餐库存

`POST https://www.hxmall.top/pos/fnb/return/stock/retry`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|returnNo|string|是|
|operationNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|disposition|StockDisposition|是|

### 32. 申请打印任务

`POST https://www.hxmall.top/pos/fnb/print/create`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|sourceType|string|是|
|sourceNo|string|是|
|printerNo|string|否|
|templateNo|string|否|

data输出：

|参数|类型|必填|
|---|---|---|
|jobNo|string|是|
|status|string|是|

### 33. 打印任务列表

`GET https://www.hxmall.top/pos/fnb/print/list`

请求示例：`https://www.hxmall.top/pos/fnb/print/list?storeNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|sourceNo|string|否|
|status|string|否|
|page|int|否|
|size|int|否|

data输出：

|参数|类型|必填|
|---|---|---|
|records|PrintJob[]|是|
|total|int|是|
|page|int|是|
|size|int|是|

### 34. 重试打印任务

`POST https://www.hxmall.top/pos/fnb/print/retry`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|jobNo|string|是|
|reason|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|jobNo|string|是|
|status|string|是|

### 35. 库存余额查询

`GET https://www.hxmall.top/pos/fnb/stock/balance/list`

请求示例：`https://www.hxmall.top/pos/fnb/stock/balance/list?storeNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|itemNo|string|否|
|page|int|否|
|size|int|否|

data输出：

|参数|类型|必填|
|---|---|---|
|records|StockBalance[]|是|
|total|int|是|
|page|int|是|
|size|int|是|

### 36. 出品待处理列表

`GET https://www.hxmall.top/pos/fnb/stock/production/pending`

请求示例：`https://www.hxmall.top/pos/fnb/stock/production/pending?storeNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|afterId|long|否|
|limit|int|否|

data输出：

|参数|类型|必填|
|---|---|---|
|items|PendingProduction[]|是|
|nextAfterId|long|否|

### 37. 原出品查回或重试

`POST https://www.hxmall.top/pos/fnb/stock/production/retry`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|eventNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|eventNo|string|是|
|status|string|是|
|pending|boolean|是|

### 38. 镜像同步异常查询

`GET https://www.hxmall.top/pos/fnb/stock/sync/list`

请求示例：`https://www.hxmall.top/pos/fnb/stock/sync/list?storeNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|status|string|否|
|itemNo|string|否|
|page|int|否|
|size|int|否|

data输出：

|参数|类型|必填|
|---|---|---|
|records|SyncDelivery[]|是|
|total|int|是|
|page|int|是|
|size|int|是|

### 39. 重试镜像同步

`POST https://www.hxmall.top/pos/fnb/stock/sync/retry`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|syncNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|syncNo|string|是|
|status|string|是|
|pending|boolean|是|

### 40. 日结预览

`POST https://www.hxmall.top/pos/fnb/close/preview`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|businessDate|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|closeNo|string|是|
|status|string|是|
|amounts|Amounts|是|
|blockingItems|BlockingItem[]|是|

### 41. 日结签署

`POST https://www.hxmall.top/pos/fnb/close/sign`

JSON参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|idempotencyKey|string|是|
|closeNo|string|是|
|version|long|是|

data输出：

|参数|类型|必填|
|---|---|---|
|closeNo|string|是|
|status|string|是|
|signedAt|string|是|

### 42. 日结结果查询

`GET https://www.hxmall.top/pos/fnb/close/detail`

请求示例：`https://www.hxmall.top/pos/fnb/close/detail?storeNo=示例值&closeNo=示例值`

Query参数：

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|closeNo|string|是|

data输出：

|参数|类型|必填|
|---|---|---|
|closeNo|string|是|
|status|string|是|
|amounts|Amounts|是|

## 复用对象字段

### Staff

|参数|类型|必填|
|---|---|---|
|staffNo|string|是|
|name|string|是|

### Store

|参数|类型|必填|
|---|---|---|
|storeNo|string|是|
|name|string|是|
|timeZone|string|是|
|currency|string|是|

### Table

|参数|类型|必填|
|---|---|---|
|tableNo|string|是|
|name|string|是|
|areaNo|string|是|
|status|string|是|
|checkNo|string|否|
|version|long|是|

### Check

|参数|类型|必填|
|---|---|---|
|checkNo|string|是|
|status|string|是|
|tableNos|string[]|是|
|guestCount|int|是|
|payMode|string|是|
|version|long|是|

### Amounts

|参数|类型|必填|
|---|---|---|
|currency|string|是|
|subtotalMinor|long|是|
|discountMinor|long|是|
|taxMinor|long|是|
|payableMinor|long|是|
|paidMinor|long|是|
|refundedMinor|long|是|

### Menu

|参数|类型|必填|
|---|---|---|
|menuNo|string|是|
|name|string|是|
|revision|string|是|
|channel|string|是|

### Category

|参数|类型|必填|
|---|---|---|
|categoryNo|string|是|
|name|string|是|
|sort|int|是|

### Dish

|参数|类型|必填|
|---|---|---|
|offerNo|string|是|
|goodsNo|string|是|
|skuNo|string|是|
|name|string|是|
|imageUrl|string|否|
|priceMinor|long|是|
|currency|string|是|
|saleStatus|string|是|

### SelectionGroup

|参数|类型|必填|
|---|---|---|
|groupNo|string|是|
|name|string|是|
|minSelect|int|是|
|maxSelect|int|是|
|options|Option[]|是|

### Option

|参数|类型|必填|
|---|---|---|
|optionNo|string|是|
|name|string|是|
|priceDeltaMinor|long|是|
|available|boolean|是|

### Selection

|参数|类型|必填|
|---|---|---|
|groupNo|string|是|
|optionNo|string|是|
|quantity|decimal|是|

### Cart

|参数|类型|必填|
|---|---|---|
|cartNo|string|是|
|checkNo|string|否|
|status|string|是|
|version|long|是|

### CartLine

|参数|类型|必填|
|---|---|---|
|lineRef|string|是|
|offerNo|string|是|
|skuNo|string|是|
|quantity|decimal|是|
|selections|Selection[]|是|

### OrderLine

|参数|类型|必填|
|---|---|---|
|lineNo|string|是|
|name|string|是|
|quantity|decimal|是|
|unit|string|是|
|amountMinor|long|是|

### Quote

|参数|类型|必填|
|---|---|---|
|quoteRef|string|是|
|quoteHash|string|是|
|expiresAt|string|是|
|amounts|Amounts|是|
|warnings|string[]|是|

### Order

|参数|类型|必填|
|---|---|---|
|orderNo|string|是|
|batchNo|string|是|
|checkNo|string|否|
|status|string|是|
|version|long|是|

### KitchenTask

|参数|类型|必填|
|---|---|---|
|taskNo|string|是|
|stationNo|string|是|
|status|string|是|
|quantity|decimal|是|
|version|long|是|

### ReturnItem

|参数|类型|必填|
|---|---|---|
|lineNo|string|是|
|quantity|decimal|是|
|reason|string|是|

### StockDisposition

|参数|类型|必填|
|---|---|---|
|operationNo|string|是|
|status|string|是|
|stockCommandNo|string|否|
|lastErrorCode|string|否|

### PrintJob

|参数|类型|必填|
|---|---|---|
|jobNo|string|是|
|sourceNo|string|是|
|status|string|是|
|lastErrorCode|string|否|

### StockBalance

|参数|类型|必填|
|---|---|---|
|itemNo|string|是|
|baseUnit|string|是|
|onHand|decimal|是|
|reservedQty|decimal|是|
|availableQty|decimal|是|
|version|long|是|

### PendingProduction

|参数|类型|必填|
|---|---|---|
|sourceId|long|是|
|eventNo|string|是|
|taskNo|string|是|
|action|string|是|
|status|string|是|

### SyncDelivery

|参数|类型|必填|
|---|---|---|
|syncNo|string|是|
|itemNo|string|是|
|status|string|是|
|attempts|int|是|
|lastErrorCode|string|否|

### BlockingItem

|参数|类型|必填|
|---|---|---|
|type|string|是|
|referenceNo|string|是|
|reason|string|是|

### Allocation

|参数|类型|必填|
|---|---|---|
|lineNo|string|是|
|quantity|decimal|是|
|seatNo|string|否|

### SplitPlan

|参数|类型|必填|
|---|---|---|
|planNo|string|是|
|status|string|是|
|version|long|是|
|allocations|Allocation[]|是|

### BenefitInput

|参数|类型|必填|
|---|---|---|
|couponNos|string[]|是|
|pointsToUse|long|否|
|storedValueToUseMinor|long|否|

### ManualAdjustment

|参数|类型|必填|
|---|---|---|
|kind|string|是|
|value|decimal|是|
|reasonCode|string|是|
|reason|string|是|

### PaymentInput

|参数|类型|必填|
|---|---|---|
|method|string|是|
|paymentRef|string|否|
|tenderedMinor|long|否|

## 导出

运行 `python3 scripts/gen-pos-fnb-doc.py` 可重新生成Markdown、JSON与离线HTML；HTML不依赖外部脚本或网络资源，可直接作为评审附件发送。接口仍处草案阶段，外发时须保留状态说明。

## 后续交付条件

固定POS适配层实现并核对全部字段、错误码、枚举及事务状态；认证门店隔离、幂等、超时查回测试通过；两个域名路由和证书核验；外部评审后锁定合同版本，才标记为可调用。原始线上接口另见[已部署接口清单](../food/接口清单.md)。

[HTML外发版](POS对接文档.html) · [JSON合同](pos-fnb-contract.json)
