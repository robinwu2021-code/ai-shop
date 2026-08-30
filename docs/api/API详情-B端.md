# B 端 API 详情 · b-app（商家端）

> 由 `npm run gen:api-detail` 从 OpenAPI 生成，**请勿手改**。
> 契约源：[`openapi-b.yaml`](openapi-b.yaml)　总表：[API 清单](API清单.md)

## 通用约定

| 项 | 约定 |
|---|---|
| 响应包 | `{ code, msg, data }`，`code=0` 表示成功；下文「出参」只描述 `data` |
| 分页 | 入参 `page`（从 1 起）、`size`；出参 `{ records, total, page, size }` |
| 金额 | 一律**最小货币单位整数**（分），字段名以 `Minor` 结尾。禁止浮点 |
| 时间 | 毫秒时间戳整数，字段名以 `At` 结尾 |
| 业务单号 | 字符串，字段名以 `No` 结尾（`orderNo`/`goodsNo`…），非自增 ID |
| 枚举 | 大写下划线常量；取值见「数据模型」对应条目 |
| 命名 | camelCase |
| 鉴权 | 🔒 = 需 Bearer token；越权拦截以后端为准，前端仅做展示裁剪 |

完整口径（错误码分段、HTTP 状态码取舍、空值语义、幂等）见 [响应格式规范](响应格式规范.md)。

---

## 接口

### activities

#### GET `/biz/activities`

活动列表　🔒

**入参**：无

**出参**（`data`）

类型：[`StoreActivity`](#storeactivity)\[\]


#### POST `/biz/activities`

建 / 改活动（敞口在这一步算清）　🔒

**入参**：无

**出参**（`data`）

类型：[`StoreActivity`](#storeactivity)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `activityNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `goal` | `string,null` | 否 | `ACQUIRE` 拉新 / `WAKEUP` 唤回 / `CLEAR` 清库存 / `BASKET` 提客单。只影响建的时候的默认值 |
| `storeNo` | `string,null` | 否 | — |
| `triggerType` | `string` | 是 | `NONE` / `AMOUNT` 满额 / `QTY` 件数 / `GOODS` 命中商品 |
| `triggerAmountMinor` | `number,null` | 否 | — |
| `triggerQty` | `number,null` | 否 | — |
| `benefitType` | `string` | 是 | `CUT` 减钱 / `PRICE` 改单价 / `GIFT` 送商品 / `COUPON` 发券 |
| `benefitAmountMinor` | `number,null` | 否 | — |
| `benefitQty` | `number,null` | 否 | — |
| `benefitRef` | `string,null` | 否 | — |
| `scheduleType` | `string` | 是 | `ONE_OFF` 短期 / `ALWAYS_ON` 长期 / `RECURRING` 周期 |
| `startAt` | `number,null` | 否 | — |
| `endAt` | `number,null` | 否 | — |
| `scheduleRule` | `string,null` | 否 | RECURRING 的 JSON：`{"weekdays":[3],"from":"08:00","to":"20:00"}` |
| `quota` | `number,null` | 否 | — |
| `quotaUsed` | `number` | 是 | — |
| `quotaLeft` | `number,null` | 否 | — |
| `budgetMinor` | `number,null` | 否 | — |
| `budgetUsedMinor` | `number` | 是 | — |
| `maxExposureMinor` | `number,null` | 否 | 最大敞口 = 限量 × 单次优惠。建活动页要显示它 |
| `audiences` | `object`（见下）\[\] | 是 | 空数组 = **对所有人生效**。老活动迁过来就是这个状态 |
| `goodsNos` | `string`\[\] | 是 | — |
| `status` | `string` | 是 | `DRAFT` / `RUNNING` / `PAUSED` / `ENDED` |
| `endedReason` | `string,null` | 否 | `EXPIRED` / `QUOTA` / `BUDGET` / `MANUAL`。商家问「怎么停了」要有答案 |
| `liveNow` | `boolean` | 是 | 此刻是不是真的在生效。**与 status 分开**：周期活动在非时段里 status 仍是 RUNNING， 而商家问的是「现在减不减」。 |

`audiences[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | `string` | 是 | — |
| `value` | `string` | 是 | — |


#### GET `/biz/activities/{activityNo}`

活动详情　🔒

**入参**：无

**出参**（`data`）

类型：[`StoreActivity`](#storeactivity)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `activityNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `goal` | `string,null` | 否 | `ACQUIRE` 拉新 / `WAKEUP` 唤回 / `CLEAR` 清库存 / `BASKET` 提客单。只影响建的时候的默认值 |
| `storeNo` | `string,null` | 否 | — |
| `triggerType` | `string` | 是 | `NONE` / `AMOUNT` 满额 / `QTY` 件数 / `GOODS` 命中商品 |
| `triggerAmountMinor` | `number,null` | 否 | — |
| `triggerQty` | `number,null` | 否 | — |
| `benefitType` | `string` | 是 | `CUT` 减钱 / `PRICE` 改单价 / `GIFT` 送商品 / `COUPON` 发券 |
| `benefitAmountMinor` | `number,null` | 否 | — |
| `benefitQty` | `number,null` | 否 | — |
| `benefitRef` | `string,null` | 否 | — |
| `scheduleType` | `string` | 是 | `ONE_OFF` 短期 / `ALWAYS_ON` 长期 / `RECURRING` 周期 |
| `startAt` | `number,null` | 否 | — |
| `endAt` | `number,null` | 否 | — |
| `scheduleRule` | `string,null` | 否 | RECURRING 的 JSON：`{"weekdays":[3],"from":"08:00","to":"20:00"}` |
| `quota` | `number,null` | 否 | — |
| `quotaUsed` | `number` | 是 | — |
| `quotaLeft` | `number,null` | 否 | — |
| `budgetMinor` | `number,null` | 否 | — |
| `budgetUsedMinor` | `number` | 是 | — |
| `maxExposureMinor` | `number,null` | 否 | 最大敞口 = 限量 × 单次优惠。建活动页要显示它 |
| `audiences` | `object`（见下）\[\] | 是 | 空数组 = **对所有人生效**。老活动迁过来就是这个状态 |
| `goodsNos` | `string`\[\] | 是 | — |
| `status` | `string` | 是 | `DRAFT` / `RUNNING` / `PAUSED` / `ENDED` |
| `endedReason` | `string,null` | 否 | `EXPIRED` / `QUOTA` / `BUDGET` / `MANUAL`。商家问「怎么停了」要有答案 |
| `liveNow` | `boolean` | 是 | 此刻是不是真的在生效。**与 status 分开**：周期活动在非时段里 status 仍是 RUNNING， 而商家问的是「现在减不减」。 |

`audiences[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | `string` | 是 | — |
| `value` | `string` | 是 | — |


#### PUT `/biz/activities/{activityNo}/status`

启停 / 结束　🔒

**入参**：无

**出参**（`data`）

类型：[`StoreActivity`](#storeactivity)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `activityNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `goal` | `string,null` | 否 | `ACQUIRE` 拉新 / `WAKEUP` 唤回 / `CLEAR` 清库存 / `BASKET` 提客单。只影响建的时候的默认值 |
| `storeNo` | `string,null` | 否 | — |
| `triggerType` | `string` | 是 | `NONE` / `AMOUNT` 满额 / `QTY` 件数 / `GOODS` 命中商品 |
| `triggerAmountMinor` | `number,null` | 否 | — |
| `triggerQty` | `number,null` | 否 | — |
| `benefitType` | `string` | 是 | `CUT` 减钱 / `PRICE` 改单价 / `GIFT` 送商品 / `COUPON` 发券 |
| `benefitAmountMinor` | `number,null` | 否 | — |
| `benefitQty` | `number,null` | 否 | — |
| `benefitRef` | `string,null` | 否 | — |
| `scheduleType` | `string` | 是 | `ONE_OFF` 短期 / `ALWAYS_ON` 长期 / `RECURRING` 周期 |
| `startAt` | `number,null` | 否 | — |
| `endAt` | `number,null` | 否 | — |
| `scheduleRule` | `string,null` | 否 | RECURRING 的 JSON：`{"weekdays":[3],"from":"08:00","to":"20:00"}` |
| `quota` | `number,null` | 否 | — |
| `quotaUsed` | `number` | 是 | — |
| `quotaLeft` | `number,null` | 否 | — |
| `budgetMinor` | `number,null` | 否 | — |
| `budgetUsedMinor` | `number` | 是 | — |
| `maxExposureMinor` | `number,null` | 否 | 最大敞口 = 限量 × 单次优惠。建活动页要显示它 |
| `audiences` | `object`（见下）\[\] | 是 | 空数组 = **对所有人生效**。老活动迁过来就是这个状态 |
| `goodsNos` | `string`\[\] | 是 | — |
| `status` | `string` | 是 | `DRAFT` / `RUNNING` / `PAUSED` / `ENDED` |
| `endedReason` | `string,null` | 否 | `EXPIRED` / `QUOTA` / `BUDGET` / `MANUAL`。商家问「怎么停了」要有答案 |
| `liveNow` | `boolean` | 是 | 此刻是不是真的在生效。**与 status 分开**：周期活动在非时段里 status 仍是 RUNNING， 而商家问的是「现在减不减」。 |

`audiences[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | `string` | 是 | — |
| `value` | `string` | 是 | — |


### activity-conflicts

#### POST `/biz/activity-conflicts`

这些商品已经在哪些活动里　🔒

**入参**：无

**出参**（`data`）

类型：[`ActivityConflict`](#activityconflict)\[\]


### after-sale

#### GET `/biz/after-sale`

待处理售后　🔒

**入参**：无

**出参**（`data`）

类型：[`Order`](#order)\[\]


#### POST `/biz/after-sale/{afterSaleNo}/approve`

同意售后　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `afterSaleNo` | path | `string` | 是 | 售后单号 |

请求体：[`HandleAfterSaleReq`](#handleaftersalereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `remark` | `string` | 是 | 驳回理由，**必填**（后端 `@NotBlank`）：用户拿不到理由只能升级平台， 平台再回头问商家，多绕一圈。 字段名是 `remark` 不是 `reply` —— 后端 `BizAfterSaleController.RejectReq` 收的是它。 |

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单单号 |
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。粗粒度；售后细节见 `afterSale` |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式，下单时锁定 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行。含赠品行（`isGift`，价格为 0） |
| `amount` | [`OrderAmount`](#orderamount) | 是 | 金额明细 |
| `verifyCode` | `string` | 否 | 自提码 / 核销码 |
| `redeemCode` | `string` | 否 | VIRTUAL：兑换码；CARD：卡号 |
| `pickupNo` | `string` | 否 | PICKUP：自提点单号 |
| `pickupName` | `string` | 否 | PICKUP：自提点名称快照 |
| `expressNo` | `string` | 否 | EXPRESS：快递单号，发货后才有 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `createdAt` | `number` | 是 | 下单时间 |
| `payDeadlineAt` | `number` | 否 | 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 |
| `timeline` | [`OrderTimelineNode`](#ordertimelinenode)\[\] | 是 | 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 |
| `idempotencyKey` | `string` | 否 | 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 |
| `buyerNickname` | `string` | 否 | 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 |
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 ⚠️ **后端叫 `payOrderNo`，库里是 `ord_order.order_no`** —— 三处三个名字。 按这个名去后端或库里找会找不到（2026-08-17 人工测试时撞到）。 |
| `subOrders` | [`Order`](#order)\[\] | 否 | **仅支付视角**：这次付款覆盖的各商家订单。订单视角为空。 后端 `OrderVO` 一直在发（同一个结构承担订单/支付两种视角）， 端上此前没声明 —— 于是收银台是整条拆单链路里**唯一哑掉的一屏**： 购物车说会拆 2 单、确认页说会拆 2 单、订单详情各自标着商家， 中间付款那一步却只有一个总额。 |


#### POST `/biz/after-sale/{afterSaleNo}/receive`

确认收到退货　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `afterSaleNo` | path | `string` | 是 | 售后单号 |

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单单号 |
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。粗粒度；售后细节见 `afterSale` |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式，下单时锁定 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行。含赠品行（`isGift`，价格为 0） |
| `amount` | [`OrderAmount`](#orderamount) | 是 | 金额明细 |
| `verifyCode` | `string` | 否 | 自提码 / 核销码 |
| `redeemCode` | `string` | 否 | VIRTUAL：兑换码；CARD：卡号 |
| `pickupNo` | `string` | 否 | PICKUP：自提点单号 |
| `pickupName` | `string` | 否 | PICKUP：自提点名称快照 |
| `expressNo` | `string` | 否 | EXPRESS：快递单号，发货后才有 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `createdAt` | `number` | 是 | 下单时间 |
| `payDeadlineAt` | `number` | 否 | 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 |
| `timeline` | [`OrderTimelineNode`](#ordertimelinenode)\[\] | 是 | 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 |
| `idempotencyKey` | `string` | 否 | 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 |
| `buyerNickname` | `string` | 否 | 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 |
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 ⚠️ **后端叫 `payOrderNo`，库里是 `ord_order.order_no`** —— 三处三个名字。 按这个名去后端或库里找会找不到（2026-08-17 人工测试时撞到）。 |
| `subOrders` | [`Order`](#order)\[\] | 否 | **仅支付视角**：这次付款覆盖的各商家订单。订单视角为空。 后端 `OrderVO` 一直在发（同一个结构承担订单/支付两种视角）， 端上此前没声明 —— 于是收银台是整条拆单链路里**唯一哑掉的一屏**： 购物车说会拆 2 单、确认页说会拆 2 单、订单详情各自标着商家， 中间付款那一步却只有一个总额。 |


#### POST `/biz/after-sale/{afterSaleNo}/reject`

驳回售后　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `afterSaleNo` | path | `string` | 是 | 售后单号 |

请求体：[`HandleAfterSaleReq`](#handleaftersalereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `remark` | `string` | 是 | 驳回理由，**必填**（后端 `@NotBlank`）：用户拿不到理由只能升级平台， 平台再回头问商家，多绕一圈。 字段名是 `remark` 不是 `reply` —— 后端 `BizAfterSaleController.RejectReq` 收的是它。 |

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单单号 |
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。粗粒度；售后细节见 `afterSale` |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式，下单时锁定 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行。含赠品行（`isGift`，价格为 0） |
| `amount` | [`OrderAmount`](#orderamount) | 是 | 金额明细 |
| `verifyCode` | `string` | 否 | 自提码 / 核销码 |
| `redeemCode` | `string` | 否 | VIRTUAL：兑换码；CARD：卡号 |
| `pickupNo` | `string` | 否 | PICKUP：自提点单号 |
| `pickupName` | `string` | 否 | PICKUP：自提点名称快照 |
| `expressNo` | `string` | 否 | EXPRESS：快递单号，发货后才有 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `createdAt` | `number` | 是 | 下单时间 |
| `payDeadlineAt` | `number` | 否 | 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 |
| `timeline` | [`OrderTimelineNode`](#ordertimelinenode)\[\] | 是 | 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 |
| `idempotencyKey` | `string` | 否 | 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 |
| `buyerNickname` | `string` | 否 | 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 |
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 ⚠️ **后端叫 `payOrderNo`，库里是 `ord_order.order_no`** —— 三处三个名字。 按这个名去后端或库里找会找不到（2026-08-17 人工测试时撞到）。 |
| `subOrders` | [`Order`](#order)\[\] | 否 | **仅支付视角**：这次付款覆盖的各商家订单。订单视角为空。 后端 `OrderVO` 一直在发（同一个结构承担订单/支付两种视角）， 端上此前没声明 —— 于是收银台是整条拆单链路里**唯一哑掉的一屏**： 购物车说会拆 2 单、确认页说会拆 2 单、订单详情各自标着商家， 中间付款那一步却只有一个总额。 |


### appointment-slots

#### POST `/biz/appointment-slots/{slotNo}/close`

停约　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `slotNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`AppointmentSlot`](#appointmentslot)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `slotNo` | `string` | 是 | — |
| `storeNo` | `string` | 是 | — |
| `startAt` | `number` | 是 | — |
| `endAt` | `number` | 是 | — |
| `capacity` | `number` | 是 | — |
| `booked` | `number` | 是 | — |
| `remaining` | `number` | 是 | — |
| `status` | `OPEN` \| `CLOSED` | 是 | OPEN 可约 / CLOSED 停约。停约**不删行也不赶人** |


### auth

#### POST `/biz/auth/login`

商家登录　🔒

**入参**

请求体：[`MerchantLoginReqBody`](#merchantloginreqbody)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `grantType` | [`GrantType`](#granttype) | 是 | 登录方式。**商家池与 C 端用户池是两套账号**，同一手机号登两端是两个身份 |
| `principal` | `string` | 是 | `WX_MINI`: wx.login code；`PHONE_OTP`: 手机号 |
| `credential` | `string` | 否 | `PHONE_OTP`: 验证码 |
| `agreed` | `boolean` | 否 | 是否勾选了用户协议与隐私政策 —— 注册的合规前置，服务端要留痕。 登录页一直在发（`{ ...req }` 把 `LoginReq.agreed` 带了出去）， **漏的是这里没声明**，于是生成的 OpenAPI 里没有它，而后端 `LoginReq` 有。 这类漏声明比漏发更难发现：联调时一切正常，直到有人照着 spec 写另一个客户端。 |

**出参**（`data`）

类型：[`MerchantLoginResp`](#merchantloginresp)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `token` | `string` | 是 | 访问令牌。**商家池与 C 端用户池是两套账号**，token 不通用 |
| `merchant` | [`MerchantProfile`](#merchantprofile) | 是 | 商家档案 |


#### POST `/biz/auth/otp/send`

发送验证码　🔒

**入参**：无

**出参**（`data`）

类型：`any`


#### POST `/biz/auth/password`

设置登录密码　🔒

**入参**：无

**出参**（`data`）

类型：`any`


#### GET `/biz/auth/password`

是否已设密码　🔒

**入参**：无

**出参**（`data`）

类型：[`HasPasswordResp`](#haspasswordresp)


#### POST `/biz/auth/staff-login`

员工登录　🔒

**入参**

请求体：[`StaffLoginReq`](#staffloginreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `phone` | `string` | 是 | 员工的登录手机号（老板在员工管理里加的那个） |
| `code` | `string` | 是 | 短信验证码 |

**出参**（`data`）

类型：[`MerchantLoginResp`](#merchantloginresp)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `token` | `string` | 是 | 访问令牌。**商家池与 C 端用户池是两套账号**，token 不通用 |
| `merchant` | [`MerchantProfile`](#merchantprofile) | 是 | 商家档案 |


### campaign

#### GET `/biz/campaign`

营销活动列表　🔒

**入参**：无

**出参**（`data`）

类型：[`MarketingCampaign`](#marketingcampaign)\[\]


#### POST `/biz/campaign`

新建/编辑活动　🔒

**入参**

请求体：[`SaveCampaignReqBody`](#savecampaignreqbody)

_无字段_

**出参**（`data`）

类型：[`MarketingCampaign`](#marketingcampaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `campaignNo` | `string` | 是 | 活动单号 |
| `merchantNo` | `string` | 是 | 所属商家。活动是店铺级的，不跨店 |
| `type` | [`CampaignType`](#campaigntype) | 是 | 活动类型，决定下面哪几个可选字段有意义 |
| `name` | `string` | 是 | 活动名，展示给用户 |
| `status` | [`CampaignStatus`](#campaignstatus) | 是 | 活动状态 |
| `startAt` | `number` | 是 | 生效开始时间 |
| `endAt` | `number` | 是 | 生效结束时间 |
| `thresholdMinor` | `number` | 否 | 门槛：满多少（最小货币单位）。FLASH / BUY_GIFT 不用 |
| `discountMinor` | `number` | 否 | 优惠额：COUPON / FULL_CUT 用（最小货币单位） |
| `flashPriceMinor` | `number` | 否 | FLASH：活动价（最小货币单位） |
| `buyN` | `number` | 否 | BUY_GIFT：购买件数门槛 N |
| `giftM` | `number` | 否 | BUY_GIFT：赠送件数 M |
| `goodsNos` | `string`\[\] | 是 | 参与商品；空 = 全店 |
| `totalCount` | `number` | 否 | COUPON：发放总量。**预算上限，防止发穿** |
| `takenCount` | `number` | 否 | COUPON：已被领取的数量 |
| `usedCount` | `number` | 是 | 已核销/已使用次数，衡量效果 |
| `storeNo` | `string` | 否 | 只对这家门店生效；**空 = 全主体**（存量活动都是它）。 多门店商家必须看得见 —— 否则两条同名的「开业满减」分不清是哪家店的。 |


#### POST `/biz/campaign/{campaignNo}/toggle`

活动启停　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `campaignNo` | path | `string` | 是 | 活动单号 |

请求体：[`ToggleCampaignReq`](#togglecampaignreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `running` | `boolean` | 是 | 目标状态：true 启动、false 暂停。暂停不影响已领取的券 |

**出参**（`data`）

类型：[`MarketingCampaign`](#marketingcampaign)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `campaignNo` | `string` | 是 | 活动单号 |
| `merchantNo` | `string` | 是 | 所属商家。活动是店铺级的，不跨店 |
| `type` | [`CampaignType`](#campaigntype) | 是 | 活动类型，决定下面哪几个可选字段有意义 |
| `name` | `string` | 是 | 活动名，展示给用户 |
| `status` | [`CampaignStatus`](#campaignstatus) | 是 | 活动状态 |
| `startAt` | `number` | 是 | 生效开始时间 |
| `endAt` | `number` | 是 | 生效结束时间 |
| `thresholdMinor` | `number` | 否 | 门槛：满多少（最小货币单位）。FLASH / BUY_GIFT 不用 |
| `discountMinor` | `number` | 否 | 优惠额：COUPON / FULL_CUT 用（最小货币单位） |
| `flashPriceMinor` | `number` | 否 | FLASH：活动价（最小货币单位） |
| `buyN` | `number` | 否 | BUY_GIFT：购买件数门槛 N |
| `giftM` | `number` | 否 | BUY_GIFT：赠送件数 M |
| `goodsNos` | `string`\[\] | 是 | 参与商品；空 = 全店 |
| `totalCount` | `number` | 否 | COUPON：发放总量。**预算上限，防止发穿** |
| `takenCount` | `number` | 否 | COUPON：已被领取的数量 |
| `usedCount` | `number` | 是 | 已核销/已使用次数，衡量效果 |
| `storeNo` | `string` | 否 | 只对这家门店生效；**空 = 全主体**（存量活动都是它）。 多门店商家必须看得见 —— 否则两条同名的「开业满减」分不清是哪家店的。 |


### category

#### GET `/biz/category/tree`

类目树（选类目）　🔒

**入参**：无

**出参**（`data`）

类型：[`Category`](#category)\[\]


### communities

#### GET `/biz/communities`

可选社区（设经营范围用）　🔒

**入参**：无

**出参**（`data`）

类型：[`Community`](#community)\[\]


#### GET `/biz/communities/applies`

我提报过的小区　🔒

**入参**：无

**出参**（`data`）

类型：[`CommunityApply`](#communityapply)\[\]


#### POST `/biz/communities/apply`

提报平台还没有的小区　🔒

**入参**：无

**出参**（`data`）

类型：[`CommunityApply`](#communityapply)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `applyNo` | `string` | 是 | 提报单业务键 |
| `merchantNo` | `string` | 是 | 提报的商家 |
| `merchantName` | `string` | 是 | 商家名。运营看着一串 M20260811… 判断不了任何事 |
| `name` | `string` | 是 | 小区名，商家填 |
| `address` | `string` | 否 | 地址。运营靠它判断这是不是已有社区的另一个叫法 —— 批重了商家会分不清该勾哪个 |
| `regionCode` | `string` | 否 | 商家选的区划，**只是建议** —— 最终以运营裁决时填的为准 |
| `regionPath` | `string` | 否 | 区划整条路径名。「北山街道」全国有好几个，光末级判断不了是不是同一个地方 |
| `note` | `string` | 否 | 补充说明：为什么要开这个点 |
| `status` | [`CommunityApplyStatus`](#communityapplystatus) | 是 | 待审 / 已建社区 / 已驳回。裁完即终态 |
| `communityNo` | `string` | 否 | 通过后建出来的社区号；待审与驳回时为空 |
| `reason` | `string` | 否 | 驳回原因，**原样展示给商家** —— 不给理由他只会原样再提一次 |
| `submittedAt` | `number` | 是 | 提报时间（毫秒时间戳） |


#### POST `/biz/communities/from-map`

地图上选中的小区直接开通　🔒

**入参**：无

**出参**（`data`）

类型：[`Community`](#community)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communityNo` | `string` | 是 | 社区单号 |
| `name` | `string` | 是 | 社区名（小区名） |
| `address` | `string` | 是 | 社区地址 |
| `cityCode` | `string` | 是 | 所属城市。全市范围的商家靠它判定可达 |
| `regionCode` | `string` | 否 | 所属街道/镇（9 位区划码）。商家框范围时「按街道看聚落」靠它 —— 不下发的话端上只能拿到一锅平铺清单，街道视图无从分组。 |
| `kind` | `string` | 否 | ESTATE 小区 / VILLAGE 村。只是展示标签，不参与匹配 |
| `distance` | `number` | 是 | 米 |
| `pickups` | [`Pickup`](#pickup)\[\] | 是 | 本社区可用的自提点 |
| `originCode` | `string,null` | 否 | 官方村码，只有 `kind=VILLAGE` 且经官方名录开通的才有。**`regionCode` 是它挂的 街道/镇，不是它自己** —— 经营范围选择器再往下钻一层要用这个码，不能用 regionCode， 否则「牛杜村」会被当成「牛杜镇」去下钻。 |
| `originName` | `string,null` | 否 | `originCode` 对应的原始官方名（「景滑村委会」，未清理）——仅供展示/追溯， 判「是不是村委会」不要解析它，用下面的 `rural` 字段（服务端存的，不是端上猜的）。 |
| `rural` | `boolean` | 否 | 是不是村委会（`sys_region.rural`，经 origin_code 反查）。只对 kind=VILLAGE 有意义： 村委会到此为止、不再下钻；居委会/社区还能再挑具体小区。 |
| `latE6` | `number,null` | 否 | 官方村名录批量补录过的坐标，可能为空 |
| `lngE6` | `number,null` | 否 | — |


### context

#### GET `/biz/context`

我的作用域与权限　🔒

**入参**：无

**出参**（`data`）

类型：[`BizScope`](#bizscope)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 当前用户所属的商家主体 |
| `currentStoreNo` | `string` | 是 | 当前选中的门店。 **切门店后要重新拉这个接口** —— 角色跟着门店走：同一个人可能在 A 店是店长、 B 店是店员，权限跟着变。不重拉的话，界面按上一家店的权限渲染。 |
| `owner` | `boolean` | 是 | 是不是老板（主体所有者）。老板不受门店角色限制 |
| `storeNos` | `string`\[\] | 是 | 我能管哪些门店。空 = 只能看当前这家 |
| `pickupNos` | `string`\[\] | 是 | 我能核销哪些自提点 |
| `groupNos` | `string`\[\] | 是 | 我发起了哪些团。**第三个作用域**，与门店 / 自提点正交 |
| `staffRoles` | [`StaffRole`](#staffrole) \| `string`\[\] | 是 | 我在**当前门店**持有的角色（可多个）。老板恒为 `["OWNER"]` |
| `categoryCodes` | `string`\[\] | 否 | 主体已获批的经营类目码（如 `["FRESH_VEG"]`）。 **与门店货架是两件事**：这是平台批的证（能不能卖这一类）， 货架是商家自己摆的（店里怎么摆）。 |
| `switches` | [`Record_string_boolean`](#record_string_boolean) | 否 | 平台开关里与商家侧有关的那几个（后端 `/biz/context` 下发）。 <p>`categoryGate`：类目资质校验**是否真的拦人**。 <p>此前这是 `b-app/src/shared/flags.ts` 里的编译期常量，运营改一次开关要重新 打包发版；更糟的是它与后端那份不同步时，症状是「点不动一个其实能按的按钮」 或者「点下去吃一句说不清缘由的报错」—— 两种都难查，因为界面与后端各自看起来都对。 <p>取不到时按 **false（不拦）** 处理：与后端默认值一致，且宁可放行也不要 凭一个拿不到的开关把商家挡在门外。 |
| `perms` | `string`\[\] | 是 | 这些角色合起来的权限码，**已取并集**（老板是 `["*"]`）。 端上照它裁剪入口，**不要自己按角色再推一遍** —— 两处各推一次迟早分岔， 而分岔的表现是「看得见但点了报错」。 |


### coupon-issues

#### GET `/biz/coupon-issues`

发放记录（含跳过明细）　🔒

**入参**：无

**出参**（`data`）

类型：[`CouponIssueBatch`](#couponissuebatch)\[\]


### coupon-redeem

#### POST `/biz/coupon-redeem`

到店核销一次（不可撤销）　🔒

**入参**：无

**出参**（`data`）

类型：[`CouponRedeemResult`](#couponredeemresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `userCouponNo` | `string` | 是 | — |
| `timesUsed` | `number` | 是 | — |
| `remaining` | `number` | 是 | — |
| `usedUp` | `boolean` | 是 | — |
| `duplicated` | `boolean` | 是 | — |


#### GET `/biz/coupon-redeem/{code}`

先看：这张券能不能核　🔒

**入参**：无

**出参**（`data`）

类型：[`CouponRedeemView`](#couponredeemview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `userCouponNo` | `string` | 是 | — |
| `couponNo` | `string` | 是 | — |
| `title` | `string` | 是 | — |
| `benefitText` | `string` | 是 | 「减 3 元」「8.5 折」「兑换」这种人话，后端拼好 |
| `phoneTail` | `string,null` | 否 | — |
| `expireAt` | `number` | 是 | — |
| `timesTotal` | `number` | 是 | — |
| `timesUsed` | `number` | 是 | — |
| `remaining` | `number` | 是 | 还能核几次。次卡看这个数 |
| `redeemable` | `boolean` | 是 | — |
| `reason` | `string,null` | 否 | 不能核销时的原因码：`EXPIRED` / `USED_UP` / `REVOKED` / `NOT_STORE_CODE` / `COUPON_INACTIVE` |


### coupons

#### GET `/biz/coupons`

券列表　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantCoupon`](#merchantcoupon)\[\]


#### POST `/biz/coupons`

建券 / 改券（敞口在这一步算清）　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantCoupon`](#merchantcoupon)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `couponNo` | `string` | 是 | — |
| `title` | `string` | 是 | — |
| `benefitMode` | `string` | 是 | `CASH` 现金 / `PERCENT` 折扣 / `GIFT` 兑换 / `FREE_SHIP` 免运费 |
| `benefitValue` | `number` | 是 | CASH = 面额（分）；PERCENT = **万分比**，8500 表示八五折（顾客付 85%） |
| `benefitCapMinor` | `number,null` | 否 | 折扣封顶（分）。PERCENT 必填 —— 不封顶的敞口随订单金额无限放大 |
| `benefitRef` | `string,null` | 否 | GIFT 兑换哪件商品 |
| `minAmountMinor` | `number,null` | 否 | — |
| `minQty` | `number,null` | 否 | — |
| `scopeType` | `string` | 是 | `ALL` / `STORE` / `CATEGORY` / `GOODS`。**下单抵扣的券只能是前两种** |
| `scopeRefs` | `string`\[\] | 是 | — |
| `scopeDesc` | `string,null` | 否 | — |
| `validityMode` | `string` | 是 | `ABSOLUTE` 固定起止 / `RELATIVE` 领取后 N 天 |
| `startAt` | `number,null` | 否 | — |
| `endAt` | `number,null` | 否 | — |
| `validDays` | `number,null` | 否 | — |
| `issueMode` | `string` | 是 | `CENTER` 领券中心 / `TARGETED` 定向发 / `ACTIVITY` 活动发 |
| `redeemMode` | `string` | 是 | `ORDER` 下单抵扣 / `STORE_CODE` 到店出示核销 |
| `timesTotal` | `number` | 是 | 一张能用几次。>1 就是次卡（豆浆 5 杯） |
| `totalCount` | `number,null` | 否 | 发行量。空 = 不限（只有定向发放允许） |
| `receivedCount` | `number` | 是 | — |
| `perUserLimit` | `number` | 是 | — |
| `budgetMinor` | `number,null` | 否 | — |
| `maxExposureMinor` | `number,null` | 否 | 最大敞口 = 发行量 × 单张最大优惠。 **建券页要显示它** —— 商家填「1000 张 × 20 元」时心里想的是「发 1000 张」， 不是「最多赔两万」。 |
| `status` | `string` | 是 | `ACTIVE` / `PAUSED` 暂停发放（已领的不受影响）/ `ENDED` |


#### GET `/biz/coupons/{couponNo}`

券详情　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantCoupon`](#merchantcoupon)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `couponNo` | `string` | 是 | — |
| `title` | `string` | 是 | — |
| `benefitMode` | `string` | 是 | `CASH` 现金 / `PERCENT` 折扣 / `GIFT` 兑换 / `FREE_SHIP` 免运费 |
| `benefitValue` | `number` | 是 | CASH = 面额（分）；PERCENT = **万分比**，8500 表示八五折（顾客付 85%） |
| `benefitCapMinor` | `number,null` | 否 | 折扣封顶（分）。PERCENT 必填 —— 不封顶的敞口随订单金额无限放大 |
| `benefitRef` | `string,null` | 否 | GIFT 兑换哪件商品 |
| `minAmountMinor` | `number,null` | 否 | — |
| `minQty` | `number,null` | 否 | — |
| `scopeType` | `string` | 是 | `ALL` / `STORE` / `CATEGORY` / `GOODS`。**下单抵扣的券只能是前两种** |
| `scopeRefs` | `string`\[\] | 是 | — |
| `scopeDesc` | `string,null` | 否 | — |
| `validityMode` | `string` | 是 | `ABSOLUTE` 固定起止 / `RELATIVE` 领取后 N 天 |
| `startAt` | `number,null` | 否 | — |
| `endAt` | `number,null` | 否 | — |
| `validDays` | `number,null` | 否 | — |
| `issueMode` | `string` | 是 | `CENTER` 领券中心 / `TARGETED` 定向发 / `ACTIVITY` 活动发 |
| `redeemMode` | `string` | 是 | `ORDER` 下单抵扣 / `STORE_CODE` 到店出示核销 |
| `timesTotal` | `number` | 是 | 一张能用几次。>1 就是次卡（豆浆 5 杯） |
| `totalCount` | `number,null` | 否 | 发行量。空 = 不限（只有定向发放允许） |
| `receivedCount` | `number` | 是 | — |
| `perUserLimit` | `number` | 是 | — |
| `budgetMinor` | `number,null` | 否 | — |
| `maxExposureMinor` | `number,null` | 否 | 最大敞口 = 发行量 × 单张最大优惠。 **建券页要显示它** —— 商家填「1000 张 × 20 元」时心里想的是「发 1000 张」， 不是「最多赔两万」。 |
| `status` | `string` | 是 | `ACTIVE` / `PAUSED` 暂停发放（已领的不受影响）/ `ENDED` |


#### POST `/biz/coupons/{couponNo}/issue`

按人群定向发券　🔒

**入参**：无

**出参**（`data`）

类型：[`CouponIssueBatch`](#couponissuebatch)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `issueNo` | `string` | 是 | — |
| `couponNo` | `string` | 是 | — |
| `segmentNo` | `string,null` | 否 | — |
| `planned` | `number` | 是 | 人群此刻命中多少人 |
| `issued` | `number` | 是 | — |
| `skipped` | `number` | 是 | — |
| `skipReasons` | `object`（见下）\[\] | 是 | `UNREACHABLE` 还没注册或已退订 / `ALREADY_HAS` 到每人上限 / `SOLD_OUT` 券发完 |
| `amountMinor` | `number` | 是 | — |
| `operatorNo` | `string,null` | 否 | — |
| `issuedAt` | `number` | 是 | — |

`skipReasons[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reason` | `string` | 是 | — |
| `count` | `number` | 是 | — |


#### PUT `/biz/coupons/{couponNo}/status`

暂停 / 恢复 / 结束　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantCoupon`](#merchantcoupon)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `couponNo` | `string` | 是 | — |
| `title` | `string` | 是 | — |
| `benefitMode` | `string` | 是 | `CASH` 现金 / `PERCENT` 折扣 / `GIFT` 兑换 / `FREE_SHIP` 免运费 |
| `benefitValue` | `number` | 是 | CASH = 面额（分）；PERCENT = **万分比**，8500 表示八五折（顾客付 85%） |
| `benefitCapMinor` | `number,null` | 否 | 折扣封顶（分）。PERCENT 必填 —— 不封顶的敞口随订单金额无限放大 |
| `benefitRef` | `string,null` | 否 | GIFT 兑换哪件商品 |
| `minAmountMinor` | `number,null` | 否 | — |
| `minQty` | `number,null` | 否 | — |
| `scopeType` | `string` | 是 | `ALL` / `STORE` / `CATEGORY` / `GOODS`。**下单抵扣的券只能是前两种** |
| `scopeRefs` | `string`\[\] | 是 | — |
| `scopeDesc` | `string,null` | 否 | — |
| `validityMode` | `string` | 是 | `ABSOLUTE` 固定起止 / `RELATIVE` 领取后 N 天 |
| `startAt` | `number,null` | 否 | — |
| `endAt` | `number,null` | 否 | — |
| `validDays` | `number,null` | 否 | — |
| `issueMode` | `string` | 是 | `CENTER` 领券中心 / `TARGETED` 定向发 / `ACTIVITY` 活动发 |
| `redeemMode` | `string` | 是 | `ORDER` 下单抵扣 / `STORE_CODE` 到店出示核销 |
| `timesTotal` | `number` | 是 | 一张能用几次。>1 就是次卡（豆浆 5 杯） |
| `totalCount` | `number,null` | 否 | 发行量。空 = 不限（只有定向发放允许） |
| `receivedCount` | `number` | 是 | — |
| `perUserLimit` | `number` | 是 | — |
| `budgetMinor` | `number,null` | 否 | — |
| `maxExposureMinor` | `number,null` | 否 | 最大敞口 = 发行量 × 单张最大优惠。 **建券页要显示它** —— 商家填「1000 张 × 20 元」时心里想的是「发 1000 张」， 不是「最多赔两万」。 |
| `status` | `string` | 是 | `ACTIVE` / `PAUSED` 暂停发放（已领的不受影响）/ `ENDED` |


### cross-store

#### GET `/biz/cross-store/compare`

跨店对比（销售额/订单/复购/缺货）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `days` | query | `number` | 否 | — |

**出参**（`data`）

类型：[`CrossStoreCompare`](#crossstorecompare)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `days` | `number` | 是 | **实际生效**的窗口天数（后端已夹在 1–365）。回显它，端上才知道传 99999 被截成了 365 |
| `currency` | [`CurrencyCode`](#currencycode) | 是 | 统计口径的币种 |
| `rating` | `number` | 是 | **主体整体评分**（各店的合成，也是 C 端商家卡上显示的那个）。 每家店自己的分在  {@link  CrossStoreCompareRow#rating  }  上（V155 起）。 【历史】V155 之前 `rvw_review` 只有 `entity_no` 没有 `store_no`， 门店维度的评分没有数据源，所以这个数只能放顶层。 <p>渲染成一条「本店铺整体评分」的说明；对比表格里那一列用每行自己的  {@link  CrossStoreCompareRow#rating  } 。**别拿这个数去填表格列** —— 那样三家店会显示同一个数字，而这正是 V155 之前的样子。 |
| `ratingCount` | `number` | 是 | 计入评分的评价条数。0 = 还没人评过，显示「暂无评价」而不是 0 颗星 |
| `stores` | [`CrossStoreCompareRow`](#crossstorecomparerow)\[\] | 是 | 按店并列，顺序同门店列表 |


#### GET `/biz/cross-store/overview`

跨店总览（按店并列今日/本月/待办）　🔒

**入参**：无

**出参**（`data`）

类型：[`CrossStoreOverview`](#crossstoreoverview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `currency` | [`CurrencyCode`](#currencycode) | 是 | 统计口径的币种。与 `/biz/dashboard/stats` 同一个字段 |
| `stores` | [`CrossStoreRow`](#crossstorerow)\[\] | 是 | 按店并列。顺序与门店列表一致（默认店在前），端上不必自己排 |


### customers

#### GET `/biz/customers`

客户与复购（跨店总览在用）　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantCustomer`](#merchantcustomer)\[\]


### dashboard

#### GET `/biz/dashboard/stats`

经营数据　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantStats`](#merchantstats)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `todayOrders` | `number` | 是 | 今日订单数（自然日，按市场本地时区切分） |
| `todayGmvMinor` | `number` | 是 | 今日成交额（最小货币单位） |
| `monthOrders` | `number` | 是 | 本月订单数 |
| `monthGmvMinor` | `number` | 是 | 本月成交额（最小货币单位） |
| `currency` | [`CurrencyCode`](#currencycode) | 是 | 统计口径的币种 |
| `rating` | `number` | 是 | 店铺综合评分，0–5 |
| `ratingCount` | `number` | 是 | 参与评分的评价条数 |
| `ownedTrafficRate` | `number` | 是 | 自带客流占比（trafficSource=MERCHANT_OWNED），决定费率档（ADR-004 §6） |


#### GET `/biz/dashboard/todo`

工作台待办　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantTodo`](#merchanttodo)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `toShip` | `number` | 是 | 待发货单数（EXPRESS 履约） |
| `toDeliver` | `number` | 是 | 待自送单数（商家自送履约） |
| `toStock` | `number` | 是 | 待备货单数（自提单已付款，货还没送到自提点）。**按门店算**，这是供货方的活。 与  {@link  toPick }  是同一批单的两头，**两个数不相等**： 买家常常选别家的自提点。`toPick` 按自提点算（我要在点上分多少）， 这一个按门店算（我要送出去多少）。 |
| `toVerify` | `number` | 是 | 待核销单数（自提到货、买家还没来取） |
| `toPick` | `number` | 是 | 待分拣单数（到货后按商品汇总点数） |
| `afterSale` | `number` | 是 | 待处理售后单数 |
| `toReply` | `number` | 是 | 待回复的评价数 |
| `quotable` | `number` | 是 | 可报价的求团需求数 |


### delivery

#### GET `/biz/delivery/rule`

自送规则　🔒

**入参**：无

**出参**（`data`）

类型：[`DeliveryRule`](#deliveryrule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `radius` | `number` | 是 | 配送半径，米 |
| `minOrderMinor` | `number` | 是 | 起送价，最小货币单位 |
| `feeMinor` | `number` | 是 | 配送费，最小货币单位 |
| `freeThresholdMinor` | `number` | 是 | 免配送费门槛，最小货币单位；0 表示不免 |


#### POST `/biz/delivery/rule`

保存自送规则　🔒

**入参**

请求体：[`SaveDeliveryRuleReqBody`](#savedeliveryrulereqbody)

_无字段_

**出参**（`data`）

类型：[`DeliveryRule`](#deliveryrule)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `radius` | `number` | 是 | 配送半径，米 |
| `minOrderMinor` | `number` | 是 | 起送价，最小货币单位 |
| `feeMinor` | `number` | 是 | 配送费，最小货币单位 |
| `freeThresholdMinor` | `number` | 是 | 免配送费门槛，最小货币单位；0 表示不免 |


### entities

#### GET `/biz/entities`

我名下的证照　🔒

**入参**：无

**出参**（`data`）

类型：[`Entity`](#entity)\[\]


### entity

#### GET `/biz/entity/{entityNo}`

一张证照的详情与门店　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `entityNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`EntityStores`](#entitystores)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entity` | [`Entity`](#entity) | 是 | — |
| `stores` | [`Store`](#store)\[\] | 是 | — |


### fulfillment

#### GET `/biz/fulfillment/carriers`

承运方可选列表（只列启用的）　🔒

**入参**：无

**出参**（`data`）

类型：[`Carrier`](#carrier)\[\]


### geo

#### GET `/biz/geo/estates`

一片地方的小区（服务端读穿透：缓存优先，不够就问地图）　🔒

**入参**：无

**出参**（`data`）

类型：[`EstateList`](#estatelist)


#### GET `/biz/geo/estates/counts`

下辖各片的小区条数（列表预告）　🔒

**入参**：无

**出参**（`data`）

类型：[`Record<string, number>`](#recordstringnumber)


#### GET `/biz/geo/reverse`

坐标转地址（门店地址定位）　🔒

**入参**：无

**出参**（`data`）

类型：[`GeoReverseResult`](#georeverseresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `recommend` | `string` | 是 | — |
| `address` | `string` | 是 | — |


#### GET `/biz/geo/tips`

地点输入提示（提报小区按名搜 POI）　🔒

**入参**：无

**出参**（`data`）

类型：[`GeoTip`](#geotip)\[\]


### goods

#### GET `/biz/goods`

商品列表　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `page` | query | `number` | 否 | 页码，从 1 起 |
| `size` | query | `number` | 否 | 每页条数 |
| `status` | query | [`GoodsStatus`](#goodsstatus) \| `string` | 否 | 状态筛选，取值见对应枚举 |
| `keyword` | query | `string` | 否 | 搜索关键词 |
| `categoryNo` | query | `string` | 否 | 类目单号 |

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Goods`](#goods)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/biz/goods/{goodsNo}`

商品详情　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：[`Goods`](#goods)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
| `detailImages` | `string`\[\] | 否 | 图文详情区的长图，按顺序全宽竖排。 **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是 一张 1:3 的长图被塞进方形轮播里。 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。 <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU， 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 |
| `categoryNo` | `string` | 是 | 所属类目 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 所属商家 —— 商品与服务都要展示商家信息 |
| `rating` | `number` | 否 | 本商品的评分与评价数（区别于商家整体评分） |
| `ratingCount` | `number` | 否 | 本商品的评价条数 |
| `price` | `number` | 是 | 展示价（最小货币单位），取各 SKU 最低价 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位） |
| `fulfillments` | [`FulfillmentType`](#fulfillmenttype)\[\] | 是 | 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 规格维度定义；单规格商品也有一组 |
| `skus` | [`Sku`](#sku)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `sales` | `number` | 是 | 累计销量，展示用 |
| `cutoffAt` | `number` | 否 | FRESH：预售截单时间戳 |
| `arrivalDesc` | `string` | 否 | FRESH：预计到货描述 |
| `weighed` | `boolean` | 否 | FRESH：是否按实称多退少补 |
| `origin` | `string` | 否 | FRESH：产地 |
| `durationMin` | `number` | 否 | SERVICE：服务时长（分钟） |
| `storeName` | `string` | 否 | SERVICE：可核销门店 |
| `slots` | [`AppointmentDaySlots`](#appointmentdayslots)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段。**后端未下发** |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD。**后端未下发** |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL。**后端未下发** |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M）。**后端未下发** |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列， 但全仓没有任何读写。等积分域接上再兑现。 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染， 别拿一个空白区块占着详情页。 |
| `status` | [`GoodsStatus`](#goodsstatus) | 否 | — |
| `auditReason` | `string` | 否 | 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。 **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳 还是被平台下的。过审时清空。 ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。 那句注释描述的是一件**从未发生过**的事。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |
| `stdNo` | `string` | 否 | 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。** <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于 **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛， 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。 |
| `hasDraft` | `boolean` | 否 | 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**， C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。 <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行， 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


#### GET `/biz/goods/{goodsNo}/draft`

读草稿（编辑页回填）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：[`SaveGoodsReqBody`](#savegoodsreqbody)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 否 | 商品单号。新建时不传，编辑时必传 |
| `title` | `string` | 是 | 基准语言（zh-CN）的标题。后端按 Accept-Language 下发时的兜底 |
| `subtitle` | `string` | 是 | 基准语言（zh-CN）的副标题/卖点 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 是 | 标题的三语原文，键是 Lang。缺译的语言按 R9 回落展示中文 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 是 | 副标题的三语原文，同上 |
| `categoryNo` | `string` | 是 | 类目单号。**必填，且是唯一的分类输入** —— 商品形态（生鲜要截单、服务不发货、iOS 可售规则）由它派生，请求体里不再有 `type`。 |
| `cover` | `string` | 否 | 封面图 URL（来自 mUploadImage）。漏传的话 C 端列表里是一块留白，且不报错 |
| `images` | `string`\[\] | 否 | 详情轮播图 |
| `detailImages` | `string`\[\] | 否 | 详情区长图。**空数组也要发** —— 与 images 同一口径，不发就删不掉 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。**空串也要发** —— 后端「不传 = 不改」，删光了不发就删不掉 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | 商品参数（产地/保质期/材质…）。**整份覆盖，空数组也要发**。 <p>此前这个字段**契约里没有、http.ts 也没发** —— 而编辑页一直在收集它 （`goods-edit` 里那一栏和 `paramValues` 都在）。于是商家填完保存， 参数原地消失，且不报错：后端把 `params == null` 当「不改」， 所以旧值还在、新填的进不去、想删的删不掉。 |
| `specGroups` | [`SpecGroupDraft`](#specgroupdraft)\[\] | 是 | 空数组 = 单规格。非空则 skus 必须是各组选项的笛卡尔积 |
| `fulfillments` | `string`\[\] | 否 | 支持的履约方式；不传 = 不改（新建默认四种全支持） |
| `skus` | [`SkuDraft`](#skudraft)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `limitPerUser` | `number` | 否 | 每人限购，0 = 不限。不传 = 不改 |
| `fresh` | `object`（见下） | 否 | 生鲜段：截单 / 到货描述 / 是否按实称 / 产地。不传 = 不改 |
| `service` | `object`（见下） | 否 | 服务段：时长 / 可核销门店。不传 = 不改 |
| `groupBuy` | `object`（见下） | 否 | 拼团档：起团人数 + 团价，要么都给要么都不给 |
| `stdNo` | `string` | 否 | 引用的平台标准品。传了它，服务端会用标准品的 categoryNo 与 optionCode **覆盖**请求里的值；不传 = 自建品 / 脱离标准品。 |

`fresh` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `cutoffAt` | `number` | 否 | 当天几点前下单（毫秒时间戳）。与「到点」是两件事：截单管下单，到点管到货 |
| `arrivalDesc` | `string` | 否 | 预计到货描述，如「次日 17:00 前到点」 |
| `weighed` | `boolean` | 否 | 是否按实称多退少补 |
| `origin` | `string` | 否 | 产地 |

`service` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `durationMin` | `number` | 否 | 服务时长（分钟） |
| `storeName` | `string` | 否 | 可核销门店名 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 否 | 起团人数，最小 2 —— 一个人不叫团 |
| `price` | `number` | 否 | 团购价（最小货币单位） |


#### POST `/biz/goods/{goodsNo}/draft/discard`

放弃草稿（线上不动，幂等）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：[`Goods`](#goods)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
| `detailImages` | `string`\[\] | 否 | 图文详情区的长图，按顺序全宽竖排。 **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是 一张 1:3 的长图被塞进方形轮播里。 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。 <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU， 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 |
| `categoryNo` | `string` | 是 | 所属类目 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 所属商家 —— 商品与服务都要展示商家信息 |
| `rating` | `number` | 否 | 本商品的评分与评价数（区别于商家整体评分） |
| `ratingCount` | `number` | 否 | 本商品的评价条数 |
| `price` | `number` | 是 | 展示价（最小货币单位），取各 SKU 最低价 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位） |
| `fulfillments` | [`FulfillmentType`](#fulfillmenttype)\[\] | 是 | 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 规格维度定义；单规格商品也有一组 |
| `skus` | [`Sku`](#sku)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `sales` | `number` | 是 | 累计销量，展示用 |
| `cutoffAt` | `number` | 否 | FRESH：预售截单时间戳 |
| `arrivalDesc` | `string` | 否 | FRESH：预计到货描述 |
| `weighed` | `boolean` | 否 | FRESH：是否按实称多退少补 |
| `origin` | `string` | 否 | FRESH：产地 |
| `durationMin` | `number` | 否 | SERVICE：服务时长（分钟） |
| `storeName` | `string` | 否 | SERVICE：可核销门店 |
| `slots` | [`AppointmentDaySlots`](#appointmentdayslots)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段。**后端未下发** |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD。**后端未下发** |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL。**后端未下发** |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M）。**后端未下发** |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列， 但全仓没有任何读写。等积分域接上再兑现。 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染， 别拿一个空白区块占着详情页。 |
| `status` | [`GoodsStatus`](#goodsstatus) | 否 | — |
| `auditReason` | `string` | 否 | 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。 **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳 还是被平台下的。过审时清空。 ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。 那句注释描述的是一件**从未发生过**的事。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |
| `stdNo` | `string` | 否 | 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。** <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于 **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛， 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。 |
| `hasDraft` | `boolean` | 否 | 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**， C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。 <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行， 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


#### POST `/biz/goods/{goodsNo}/presale`

改截单与到货说明　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：[`Goods`](#goods)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
| `detailImages` | `string`\[\] | 否 | 图文详情区的长图，按顺序全宽竖排。 **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是 一张 1:3 的长图被塞进方形轮播里。 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。 <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU， 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 |
| `categoryNo` | `string` | 是 | 所属类目 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 所属商家 —— 商品与服务都要展示商家信息 |
| `rating` | `number` | 否 | 本商品的评分与评价数（区别于商家整体评分） |
| `ratingCount` | `number` | 否 | 本商品的评价条数 |
| `price` | `number` | 是 | 展示价（最小货币单位），取各 SKU 最低价 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位） |
| `fulfillments` | [`FulfillmentType`](#fulfillmenttype)\[\] | 是 | 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 规格维度定义；单规格商品也有一组 |
| `skus` | [`Sku`](#sku)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `sales` | `number` | 是 | 累计销量，展示用 |
| `cutoffAt` | `number` | 否 | FRESH：预售截单时间戳 |
| `arrivalDesc` | `string` | 否 | FRESH：预计到货描述 |
| `weighed` | `boolean` | 否 | FRESH：是否按实称多退少补 |
| `origin` | `string` | 否 | FRESH：产地 |
| `durationMin` | `number` | 否 | SERVICE：服务时长（分钟） |
| `storeName` | `string` | 否 | SERVICE：可核销门店 |
| `slots` | [`AppointmentDaySlots`](#appointmentdayslots)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段。**后端未下发** |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD。**后端未下发** |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL。**后端未下发** |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M）。**后端未下发** |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列， 但全仓没有任何读写。等积分域接上再兑现。 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染， 别拿一个空白区块占着详情页。 |
| `status` | [`GoodsStatus`](#goodsstatus) | 否 | — |
| `auditReason` | `string` | 否 | 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。 **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳 还是被平台下的。过审时清空。 ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。 那句注释描述的是一件**从未发生过**的事。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |
| `stdNo` | `string` | 否 | 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。** <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于 **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛， 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。 |
| `hasDraft` | `boolean` | 否 | 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**， C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。 <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行， 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


#### POST `/biz/goods/{goodsNo}/publish`

发布草稿（原子换版；冲突后带 confirmVersion）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：[`Goods`](#goods)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
| `detailImages` | `string`\[\] | 否 | 图文详情区的长图，按顺序全宽竖排。 **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是 一张 1:3 的长图被塞进方形轮播里。 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。 <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU， 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 |
| `categoryNo` | `string` | 是 | 所属类目 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 所属商家 —— 商品与服务都要展示商家信息 |
| `rating` | `number` | 否 | 本商品的评分与评价数（区别于商家整体评分） |
| `ratingCount` | `number` | 否 | 本商品的评价条数 |
| `price` | `number` | 是 | 展示价（最小货币单位），取各 SKU 最低价 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位） |
| `fulfillments` | [`FulfillmentType`](#fulfillmenttype)\[\] | 是 | 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 规格维度定义；单规格商品也有一组 |
| `skus` | [`Sku`](#sku)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `sales` | `number` | 是 | 累计销量，展示用 |
| `cutoffAt` | `number` | 否 | FRESH：预售截单时间戳 |
| `arrivalDesc` | `string` | 否 | FRESH：预计到货描述 |
| `weighed` | `boolean` | 否 | FRESH：是否按实称多退少补 |
| `origin` | `string` | 否 | FRESH：产地 |
| `durationMin` | `number` | 否 | SERVICE：服务时长（分钟） |
| `storeName` | `string` | 否 | SERVICE：可核销门店 |
| `slots` | [`AppointmentDaySlots`](#appointmentdayslots)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段。**后端未下发** |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD。**后端未下发** |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL。**后端未下发** |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M）。**后端未下发** |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列， 但全仓没有任何读写。等积分域接上再兑现。 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染， 别拿一个空白区块占着详情页。 |
| `status` | [`GoodsStatus`](#goodsstatus) | 否 | — |
| `auditReason` | `string` | 否 | 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。 **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳 还是被平台下的。过审时清空。 ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。 那句注释描述的是一件**从未发生过**的事。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |
| `stdNo` | `string` | 否 | 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。** <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于 **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛， 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。 |
| `hasDraft` | `boolean` | 否 | 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**， C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。 <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行， 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


#### GET `/biz/goods/{goodsNo}/publish-preview`

发布预览（字段级差异）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：[`PublishPreview`](#publishpreview)


#### POST `/biz/goods/{goodsNo}/stock`

改库存　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

请求体：[`SaveStockReq`](#savestockreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 要改库存的 SKU |
| `stock` | `number` | 是 | 改后的库存数。**是绝对值不是增量** |

**出参**（`data`）

类型：[`Goods`](#goods)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
| `detailImages` | `string`\[\] | 否 | 图文详情区的长图，按顺序全宽竖排。 **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是 一张 1:3 的长图被塞进方形轮播里。 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。 <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU， 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 |
| `categoryNo` | `string` | 是 | 所属类目 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 所属商家 —— 商品与服务都要展示商家信息 |
| `rating` | `number` | 否 | 本商品的评分与评价数（区别于商家整体评分） |
| `ratingCount` | `number` | 否 | 本商品的评价条数 |
| `price` | `number` | 是 | 展示价（最小货币单位），取各 SKU 最低价 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位） |
| `fulfillments` | [`FulfillmentType`](#fulfillmenttype)\[\] | 是 | 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 规格维度定义；单规格商品也有一组 |
| `skus` | [`Sku`](#sku)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `sales` | `number` | 是 | 累计销量，展示用 |
| `cutoffAt` | `number` | 否 | FRESH：预售截单时间戳 |
| `arrivalDesc` | `string` | 否 | FRESH：预计到货描述 |
| `weighed` | `boolean` | 否 | FRESH：是否按实称多退少补 |
| `origin` | `string` | 否 | FRESH：产地 |
| `durationMin` | `number` | 否 | SERVICE：服务时长（分钟） |
| `storeName` | `string` | 否 | SERVICE：可核销门店 |
| `slots` | [`AppointmentDaySlots`](#appointmentdayslots)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段。**后端未下发** |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD。**后端未下发** |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL。**后端未下发** |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M）。**后端未下发** |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列， 但全仓没有任何读写。等积分域接上再兑现。 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染， 别拿一个空白区块占着详情页。 |
| `status` | [`GoodsStatus`](#goodsstatus) | 否 | — |
| `auditReason` | `string` | 否 | 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。 **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳 还是被平台下的。过审时清空。 ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。 那句注释描述的是一件**从未发生过**的事。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |
| `stdNo` | `string` | 否 | 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。** <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于 **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛， 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。 |
| `hasDraft` | `boolean` | 否 | 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**， C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。 <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行， 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


#### POST `/biz/goods/{goodsNo}/store-price`

改当前门店售价　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：[`Goods`](#goods)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
| `detailImages` | `string`\[\] | 否 | 图文详情区的长图，按顺序全宽竖排。 **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是 一张 1:3 的长图被塞进方形轮播里。 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。 <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU， 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 |
| `categoryNo` | `string` | 是 | 所属类目 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 所属商家 —— 商品与服务都要展示商家信息 |
| `rating` | `number` | 否 | 本商品的评分与评价数（区别于商家整体评分） |
| `ratingCount` | `number` | 否 | 本商品的评价条数 |
| `price` | `number` | 是 | 展示价（最小货币单位），取各 SKU 最低价 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位） |
| `fulfillments` | [`FulfillmentType`](#fulfillmenttype)\[\] | 是 | 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 规格维度定义；单规格商品也有一组 |
| `skus` | [`Sku`](#sku)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `sales` | `number` | 是 | 累计销量，展示用 |
| `cutoffAt` | `number` | 否 | FRESH：预售截单时间戳 |
| `arrivalDesc` | `string` | 否 | FRESH：预计到货描述 |
| `weighed` | `boolean` | 否 | FRESH：是否按实称多退少补 |
| `origin` | `string` | 否 | FRESH：产地 |
| `durationMin` | `number` | 否 | SERVICE：服务时长（分钟） |
| `storeName` | `string` | 否 | SERVICE：可核销门店 |
| `slots` | [`AppointmentDaySlots`](#appointmentdayslots)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段。**后端未下发** |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD。**后端未下发** |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL。**后端未下发** |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M）。**后端未下发** |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列， 但全仓没有任何读写。等积分域接上再兑现。 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染， 别拿一个空白区块占着详情页。 |
| `status` | [`GoodsStatus`](#goodsstatus) | 否 | — |
| `auditReason` | `string` | 否 | 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。 **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳 还是被平台下的。过审时清空。 ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。 那句注释描述的是一件**从未发生过**的事。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |
| `stdNo` | `string` | 否 | 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。** <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于 **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛， 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。 |
| `hasDraft` | `boolean` | 否 | 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**， C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。 <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行， 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


#### POST `/biz/goods/{goodsNo}/store-stock`

改当前门店库存　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：[`Goods`](#goods)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
| `detailImages` | `string`\[\] | 否 | 图文详情区的长图，按顺序全宽竖排。 **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是 一张 1:3 的长图被塞进方形轮播里。 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。 <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU， 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 |
| `categoryNo` | `string` | 是 | 所属类目 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 所属商家 —— 商品与服务都要展示商家信息 |
| `rating` | `number` | 否 | 本商品的评分与评价数（区别于商家整体评分） |
| `ratingCount` | `number` | 否 | 本商品的评价条数 |
| `price` | `number` | 是 | 展示价（最小货币单位），取各 SKU 最低价 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位） |
| `fulfillments` | [`FulfillmentType`](#fulfillmenttype)\[\] | 是 | 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 规格维度定义；单规格商品也有一组 |
| `skus` | [`Sku`](#sku)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `sales` | `number` | 是 | 累计销量，展示用 |
| `cutoffAt` | `number` | 否 | FRESH：预售截单时间戳 |
| `arrivalDesc` | `string` | 否 | FRESH：预计到货描述 |
| `weighed` | `boolean` | 否 | FRESH：是否按实称多退少补 |
| `origin` | `string` | 否 | FRESH：产地 |
| `durationMin` | `number` | 否 | SERVICE：服务时长（分钟） |
| `storeName` | `string` | 否 | SERVICE：可核销门店 |
| `slots` | [`AppointmentDaySlots`](#appointmentdayslots)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段。**后端未下发** |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD。**后端未下发** |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL。**后端未下发** |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M）。**后端未下发** |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列， 但全仓没有任何读写。等积分域接上再兑现。 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染， 别拿一个空白区块占着详情页。 |
| `status` | [`GoodsStatus`](#goodsstatus) | 否 | — |
| `auditReason` | `string` | 否 | 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。 **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳 还是被平台下的。过审时清空。 ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。 那句注释描述的是一件**从未发生过**的事。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |
| `stdNo` | `string` | 否 | 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。** <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于 **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛， 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。 |
| `hasDraft` | `boolean` | 否 | 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**， C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。 <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行， 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


#### POST `/biz/goods/{goodsNo}/submit`

提交审核（草稿→待审）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

**出参**（`data`）

类型：[`Goods`](#goods)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
| `detailImages` | `string`\[\] | 否 | 图文详情区的长图，按顺序全宽竖排。 **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是 一张 1:3 的长图被塞进方形轮播里。 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。 <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU， 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 |
| `categoryNo` | `string` | 是 | 所属类目 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 所属商家 —— 商品与服务都要展示商家信息 |
| `rating` | `number` | 否 | 本商品的评分与评价数（区别于商家整体评分） |
| `ratingCount` | `number` | 否 | 本商品的评价条数 |
| `price` | `number` | 是 | 展示价（最小货币单位），取各 SKU 最低价 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位） |
| `fulfillments` | [`FulfillmentType`](#fulfillmenttype)\[\] | 是 | 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 规格维度定义；单规格商品也有一组 |
| `skus` | [`Sku`](#sku)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `sales` | `number` | 是 | 累计销量，展示用 |
| `cutoffAt` | `number` | 否 | FRESH：预售截单时间戳 |
| `arrivalDesc` | `string` | 否 | FRESH：预计到货描述 |
| `weighed` | `boolean` | 否 | FRESH：是否按实称多退少补 |
| `origin` | `string` | 否 | FRESH：产地 |
| `durationMin` | `number` | 否 | SERVICE：服务时长（分钟） |
| `storeName` | `string` | 否 | SERVICE：可核销门店 |
| `slots` | [`AppointmentDaySlots`](#appointmentdayslots)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段。**后端未下发** |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD。**后端未下发** |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL。**后端未下发** |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M）。**后端未下发** |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列， 但全仓没有任何读写。等积分域接上再兑现。 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染， 别拿一个空白区块占着详情页。 |
| `status` | [`GoodsStatus`](#goodsstatus) | 否 | — |
| `auditReason` | `string` | 否 | 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。 **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳 还是被平台下的。过审时清空。 ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。 那句注释描述的是一件**从未发生过**的事。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |
| `stdNo` | `string` | 否 | 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。** <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于 **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛， 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。 |
| `hasDraft` | `boolean` | 否 | 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**， C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。 <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行， 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


#### POST `/biz/goods/{goodsNo}/toggle`

上下架　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | path | `string` | 是 | 商品单号 |

请求体：[`ToggleGoodsReq`](#togglegoodsreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `onSale` | `boolean` | 是 | 目标状态：true 上架、false 下架。下架后详情页仍可访问但不可下单 |

**出参**（`data`）

类型：[`Goods`](#goods)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
| `detailImages` | `string`\[\] | 否 | 图文详情区的长图，按顺序全宽竖排。 **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是 一张 1:3 的长图被塞进方形轮播里。 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。 <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU， 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 |
| `categoryNo` | `string` | 是 | 所属类目 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 所属商家 —— 商品与服务都要展示商家信息 |
| `rating` | `number` | 否 | 本商品的评分与评价数（区别于商家整体评分） |
| `ratingCount` | `number` | 否 | 本商品的评价条数 |
| `price` | `number` | 是 | 展示价（最小货币单位），取各 SKU 最低价 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位） |
| `fulfillments` | [`FulfillmentType`](#fulfillmenttype)\[\] | 是 | 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 规格维度定义；单规格商品也有一组 |
| `skus` | [`Sku`](#sku)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `sales` | `number` | 是 | 累计销量，展示用 |
| `cutoffAt` | `number` | 否 | FRESH：预售截单时间戳 |
| `arrivalDesc` | `string` | 否 | FRESH：预计到货描述 |
| `weighed` | `boolean` | 否 | FRESH：是否按实称多退少补 |
| `origin` | `string` | 否 | FRESH：产地 |
| `durationMin` | `number` | 否 | SERVICE：服务时长（分钟） |
| `storeName` | `string` | 否 | SERVICE：可核销门店 |
| `slots` | [`AppointmentDaySlots`](#appointmentdayslots)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段。**后端未下发** |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD。**后端未下发** |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL。**后端未下发** |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M）。**后端未下发** |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列， 但全仓没有任何读写。等积分域接上再兑现。 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染， 别拿一个空白区块占着详情页。 |
| `status` | [`GoodsStatus`](#goodsstatus) | 否 | — |
| `auditReason` | `string` | 否 | 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。 **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳 还是被平台下的。过审时清空。 ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。 那句注释描述的是一件**从未发生过**的事。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |
| `stdNo` | `string` | 否 | 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。** <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于 **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛， 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。 |
| `hasDraft` | `boolean` | 否 | 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**， C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。 <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行， 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


#### POST `/biz/goods/describe`

自动生成图文详情　🔒

**入参**：无

**出参**（`data`）

类型：[`{ detail: string }`](#detailstring)


#### POST `/biz/goods/recognize`

拍照识别商品　🔒

**入参**

请求体：[`RecognizeGoodsReq`](#recognizegoodsreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `imageUrl` | `string` | 是 | 待识别的商品图 URL（先走 upload/image 拿到）。返回识别出的标题与类目建议 |

**出参**（`data`）

类型：[`GoodsGuess`](#goodsguess)


#### POST `/biz/goods/save`

新建/编辑商品　🔒

**入参**

请求体：[`SaveGoodsReqBody`](#savegoodsreqbody)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 否 | 商品单号。新建时不传，编辑时必传 |
| `title` | `string` | 是 | 基准语言（zh-CN）的标题。后端按 Accept-Language 下发时的兜底 |
| `subtitle` | `string` | 是 | 基准语言（zh-CN）的副标题/卖点 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 是 | 标题的三语原文，键是 Lang。缺译的语言按 R9 回落展示中文 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 是 | 副标题的三语原文，同上 |
| `categoryNo` | `string` | 是 | 类目单号。**必填，且是唯一的分类输入** —— 商品形态（生鲜要截单、服务不发货、iOS 可售规则）由它派生，请求体里不再有 `type`。 |
| `cover` | `string` | 否 | 封面图 URL（来自 mUploadImage）。漏传的话 C 端列表里是一块留白，且不报错 |
| `images` | `string`\[\] | 否 | 详情轮播图 |
| `detailImages` | `string`\[\] | 否 | 详情区长图。**空数组也要发** —— 与 images 同一口径，不发就删不掉 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。**空串也要发** —— 后端「不传 = 不改」，删光了不发就删不掉 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | 商品参数（产地/保质期/材质…）。**整份覆盖，空数组也要发**。 <p>此前这个字段**契约里没有、http.ts 也没发** —— 而编辑页一直在收集它 （`goods-edit` 里那一栏和 `paramValues` 都在）。于是商家填完保存， 参数原地消失，且不报错：后端把 `params == null` 当「不改」， 所以旧值还在、新填的进不去、想删的删不掉。 |
| `specGroups` | [`SpecGroupDraft`](#specgroupdraft)\[\] | 是 | 空数组 = 单规格。非空则 skus 必须是各组选项的笛卡尔积 |
| `fulfillments` | `string`\[\] | 否 | 支持的履约方式；不传 = 不改（新建默认四种全支持） |
| `skus` | [`SkuDraft`](#skudraft)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `limitPerUser` | `number` | 否 | 每人限购，0 = 不限。不传 = 不改 |
| `fresh` | `object`（见下） | 否 | 生鲜段：截单 / 到货描述 / 是否按实称 / 产地。不传 = 不改 |
| `service` | `object`（见下） | 否 | 服务段：时长 / 可核销门店。不传 = 不改 |
| `groupBuy` | `object`（见下） | 否 | 拼团档：起团人数 + 团价，要么都给要么都不给 |
| `stdNo` | `string` | 否 | 引用的平台标准品。传了它，服务端会用标准品的 categoryNo 与 optionCode **覆盖**请求里的值；不传 = 自建品 / 脱离标准品。 |

`fresh` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `cutoffAt` | `number` | 否 | 当天几点前下单（毫秒时间戳）。与「到点」是两件事：截单管下单，到点管到货 |
| `arrivalDesc` | `string` | 否 | 预计到货描述，如「次日 17:00 前到点」 |
| `weighed` | `boolean` | 否 | 是否按实称多退少补 |
| `origin` | `string` | 否 | 产地 |

`service` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `durationMin` | `number` | 否 | 服务时长（分钟） |
| `storeName` | `string` | 否 | 可核销门店名 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 否 | 起团人数，最小 2 —— 一个人不叫团 |
| `price` | `number` | 否 | 团购价（最小货币单位） |

**出参**（`data`）

类型：[`Goods`](#goods)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
| `detailImages` | `string`\[\] | 否 | 图文详情区的长图，按顺序全宽竖排。 **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是 一张 1:3 的长图被塞进方形轮播里。 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。 <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU， 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 |
| `categoryNo` | `string` | 是 | 所属类目 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 所属商家 —— 商品与服务都要展示商家信息 |
| `rating` | `number` | 否 | 本商品的评分与评价数（区别于商家整体评分） |
| `ratingCount` | `number` | 否 | 本商品的评价条数 |
| `price` | `number` | 是 | 展示价（最小货币单位），取各 SKU 最低价 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位） |
| `fulfillments` | [`FulfillmentType`](#fulfillmenttype)\[\] | 是 | 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 规格维度定义；单规格商品也有一组 |
| `skus` | [`Sku`](#sku)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `sales` | `number` | 是 | 累计销量，展示用 |
| `cutoffAt` | `number` | 否 | FRESH：预售截单时间戳 |
| `arrivalDesc` | `string` | 否 | FRESH：预计到货描述 |
| `weighed` | `boolean` | 否 | FRESH：是否按实称多退少补 |
| `origin` | `string` | 否 | FRESH：产地 |
| `durationMin` | `number` | 否 | SERVICE：服务时长（分钟） |
| `storeName` | `string` | 否 | SERVICE：可核销门店 |
| `slots` | [`AppointmentDaySlots`](#appointmentdayslots)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段。**后端未下发** |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD。**后端未下发** |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL。**后端未下发** |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M）。**后端未下发** |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列， 但全仓没有任何读写。等积分域接上再兑现。 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染， 别拿一个空白区块占着详情页。 |
| `status` | [`GoodsStatus`](#goodsstatus) | 否 | — |
| `auditReason` | `string` | 否 | 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。 **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳 还是被平台下的。过审时清空。 ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。 那句注释描述的是一件**从未发生过**的事。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |
| `stdNo` | `string` | 否 | 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。** <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于 **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛， 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。 |
| `hasDraft` | `boolean` | 否 | 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**， C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。 <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行， 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


### group-request

#### POST `/biz/group-request/{requestNo}/quote`

报价　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `requestNo` | path | `string` | 是 | 求团需求单号 |

请求体：[`QuoteReq`](#quotereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `unitPriceMinor` | `number` | 是 | 单价（最小货币单位）。名字带 unit 是有意义的：报的是单价不是总价 |
| `minQty` | `number` | 是 | 起订量 |
| `note` | `string` | 是 | 报价说明：规格、材质、是否含安装等，供发起人比价 |
| `validDays` | `number` | 否 | 报价有效期（天）。后端不传时默认 7 天 —— 报价不能无限期挂着 |

**出参**（`data`）

类型：[`GroupRequest`](#grouprequest)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestNo` | `string` | 是 | 求团需求单号 |
| `initiatorNickname` | `string` | 是 | 发起人昵称 |
| `initiatorAvatar` | `string` | 是 | 发起人头像 |
| `pickupNo` | `string` | 是 | 需求的范围仍是自提点/小区 —— 邻里的意义就在于此 |
| `pickupName` | `string` | 是 | 自提点名称快照 |
| `title` | `string` | 是 | 需求标题，如「想团儿童床垫」。**此时商品还不存在**，只有这句话 |
| `desc` | `string` | 是 | 需求详述：尺寸、材质、用途等，供商家判断能不能接 |
| `images` | `string`\[\] | 是 | 参考图。发起人拍的样图或截图 |
| `expectQty` | `number` | 是 | 发起人期望的数量 |
| `budgetMinor` | `number` | 否 | 心理价位（可不填） |
| `status` | [`GroupRequestStatus`](#grouprequeststatus) | 是 | 需求单状态 |
| `interestedCount` | `number` | 是 | 表达意向的邻居数（含发起人）—— 不是订单数 |
| `interested` | `boolean` | 是 | 当前用户是否已 +1。决定按钮显示「我也要」还是「已加入」 |
| `neighbours` | `object`（见下）\[\] | 是 | +1 的邻居头像墙。只取前若干个用于展示，不是全量 |
| `quotes` | [`Quote`](#quote)\[\] | 是 | 收到的报价。一个需求单可多家报价，由发起人挑 |
| `createdAt` | `number` | 是 | 发起时间 |
| `expireAt` | `number` | 是 | 需求单过期时间。过期即 EXPIRED，不再接受报价 |
| `groupNo` | `string` | 否 | LOCKED 之后指向生成的正式团 |
| `lockedPriceMinor` | `number` | 否 | 选定的报价快照。转成正式团后下单用这个价，**不读商家当前价** —— 这是防加价最硬的一层：加价在技术上做不到，不需要审核。 |
| `confirmed` | `boolean` | 否 | 我（+1 的邻居）是否已二次确认下单。+1 不等于承诺，必须各自确认 |
| `confirmedCount` | `number` | 否 | 已确认下单的人数 |

`neighbours[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `avatar` | `string` | 是 | — |
| `nickname` | `string` | 是 | — |


#### GET `/biz/group-request/pool`

可报价需求单　🔒

**入参**：无

**出参**（`data`）

类型：[`GroupRequest`](#grouprequest)\[\]


### groups

#### GET `/biz/groups`

我的商家团　🔒

**入参**：无

**出参**（`data`）

类型：[`GroupBuy`](#groupbuy)\[\]


#### POST `/biz/groups`

开团　🔒

**入参**

请求体：[`CreateGroupReq`](#creategroupreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 要开团的商品，必须是本店已上架商品 |

**出参**（`data`）

类型：[`GroupBuy`](#groupbuy)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `groupNo` | `string` | 是 | 团单号 |
| `status` | [`GroupBuyStatus`](#groupbuystatus) | 是 | 团的状态 |
| `goodsNo` | `string` | 是 | 开团的商品 |
| `title` | `string` | 是 | 商品标题快照 |
| `cover` | `string` | 是 | 商品封面快照 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 供货商家 |
| `initiatorNickname` | `string` | 是 | 发起人昵称 |
| `initiatorAvatar` | `string` | 是 | 发起人头像 |
| `pickupNo` | `string` | 是 | ★ 成团范围：**成团单位是自提点**，拼的是一车送到一个点的成本 |
| `pickupName` | `string` | 是 | 自提点名称快照 |
| `basePrice` | `number` | 是 | 不成团时的价格（降级发货用此价） |
| `groupPrice` | `number` | 是 | 成团价 |
| `minCount` | `number` | 是 | 成团所需人数 |
| `joinedCount` | `number` | 是 | 已参团人数 |
| `reached` | `boolean` | 是 | 已成团 |
| `need` | `number` | 是 | 还差几人 |
| `expireAt` | `number` | 是 | 截止时间：发起后 validHours 与商品截单时间取更早 |
| `members` | `object`（见下）\[\] | 是 | 已参团的邻居，展示用。 **没有件数**：参团是一人一份 —— 成团判断、「还差 N 人」的文案、`joinedCount` 全部按人算，库里也没存过件数。这里原先有个 `qty`，页面照着渲染 `×{qty}`， 而它从来没有值。 |
| `joined` | `boolean` | 是 | 当前用户是否已参团 |
| `neighborPickup` | [`PickupPoint`](#pickuppoint) | 否 | 邻里自提点（C-GB-06）：发起人勾选「送到我家」时有值。 参团者在这里取货，发起人负责签收与逐单核销 —— **零报酬**（ADR-005 §3）。 |
| `isOwner` | `boolean` | 否 | 我是不是这个团的发起人 —— 决定是否显示轻核销入口 |

`members[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `avatar` | `string` | 是 | — |
| `nickname` | `string` | 是 | — |


### inventory

#### POST `/biz/inventory/adjust`

直接改数（走盘点，落单落流水）　🔒

**入参**：无

**出参**（`data`）

类型：`any`


#### GET `/biz/inventory/balances`

库存列表（默认只给要处理的）　🔒

**入参**：无

**出参**（`data`）

类型：[`StockBalance`](#stockbalance)\[\]


#### POST `/biz/inventory/counts`

开盘点单（锁账面数）　🔒

**入参**：无

**出参**（`data`）

类型：`string`


#### GET `/biz/inventory/counts/{no}`

读回盘点单（含账面快照）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：[`StockCount`](#stockcount)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `countNo` | `string` | 是 | — |
| `status` | `string` | 是 | COUNTING 进行中 / POSTED 已过账 |
| `locationId` | `string` | 否 | — |
| `startedAt` | `string` | 否 | — |
| `operator` | `string` | 否 | — |
| `lines` | [`StockCountLine`](#stockcountline)\[\] | 是 | — |


#### PUT `/biz/inventory/counts/{no}/lines`

填实盘数　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：`any`


#### POST `/biz/inventory/counts/{no}/post`

盘点过账　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：`any`


#### GET `/biz/inventory/documents`

出入库单据　🔒

**入参**：无

**出参**（`data`）

类型：[`StockDocument`](#stockdocument)\[\]


#### POST `/biz/inventory/inbounds`

记一笔进货　🔒

**入参**：无

**出参**（`data`）

类型：`string`


#### PUT `/biz/inventory/inbounds/{no}`

改进货草稿　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：`any`


#### POST `/biz/inventory/inbounds/{no}/post`

进货过账　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：`any`


#### POST `/biz/inventory/inbounds/{no}/void`

作废入库单　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：`any`


#### GET `/biz/inventory/items/{itemId}`

单件库存明细　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `itemId` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`StockItemDetail`](#stockitemdetail)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `specText` | `string` | 否 | — |
| `baseUom` | `string` | 否 | — |
| `barcode` | `string` | 否 | — |
| `itemCode` | `string` | 否 | — |
| `onHand` | `number` | 是 | — |
| `reserved` | `number` | 是 | — |
| `available` | `number` | 是 | — |
| `byLocation` | `object`（见下）\[\] | 是 | — |

`byLocation[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `locationId` | `string` | 是 | — |
| `locationName` | `string` | 是 | — |
| `onHand` | `number` | 是 | — |


#### GET `/biz/inventory/ledger`

库存变动明细　🔒

**入参**：无

**出参**（`data`）

类型：[`StockLedgerPage`](#stockledgerpage)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entries` | [`StockLedgerRow`](#stockledgerrow)\[\] | 是 | — |
| `nextCursor` | `number,null` | 否 | — |


#### GET `/biz/inventory/locations`

库位与仓　🔒

**入参**：无

**出参**（`data`）

类型：[`StockLocation`](#stocklocation)\[\]


#### POST `/biz/inventory/locations`

加一个仓　🔒

**入参**：无

**出参**（`data`）

类型：`string`


#### PUT `/biz/inventory/locations/{id}/source`

设发货源　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `id` | path | `string` | 是 | — |

**出参**（`data`）

类型：`any`


#### POST `/biz/inventory/outbounds`

报损/领用出库　🔒

**入参**：无

**出参**（`data`）

类型：`string`


#### POST `/biz/inventory/outbounds/{no}/post`

出库过账　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：`any`


#### POST `/biz/inventory/outbounds/{no}/void`

作废出库单　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：`any`


#### GET `/biz/inventory/pickable`

可挑的货（含 0 库存，从物料出发）　🔒

**入参**：无

**出参**（`data`）

类型：[`StockBalance`](#stockbalance)\[\]


#### GET `/biz/inventory/report/monthly`

进销存月报　🔒

**入参**：无

**出参**（`data`）

类型：[`StockMonthly`](#stockmonthly)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `month` | `string` | 是 | — |
| `opening` | `number` | 是 | — |
| `purchased` | `number` | 是 | — |
| `sold` | `number` | 是 | — |
| `lost` | `number` | 是 | — |
| `adjusted` | `number` | 是 | — |
| `closing` | `number` | 是 | — |
| `balanced` | `boolean` | 是 | 算式对不对得上。**对不上要显眼**，那说明台账漏了一笔 |
| `soldCostMinor` | `number` | 是 | 本月销售出库的成本合计（分）。**按每一笔当时的单位成本累加**， 不是「销量 × 当前成本价」—— 后者在进价波动时会把上个月的账算成今天的价。 **这不是毛利。** 毛利 = 收入 − 成本，而收入不在进销存域： 出库单只带成本、不带售价（同一件货不同渠道价不一样，写进来就有了第二个真源）。 要毛利得由知道收入的那一侧拿这个数去减。 |
| `lostCostMinor` | `number` | 是 | 本月报损 + 盘亏的成本合计（分）—— 「这个月亏了多少钱」那个数 |


#### GET `/biz/inventory/report/ranking`

动销/滞销榜　🔒

**入参**：无

**出参**（`data`）

类型：[`StockRank`](#stockrank)\[\]


#### GET `/biz/inventory/summary`

库存总览三个数　🔒

**入参**：无

**出参**（`data`）

类型：[`StockSummary`](#stocksummary)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemCount` | `number` | 是 | — |
| `shortageCount` | `number` | 是 | — |
| `staleCount` | `number` | 是 | — |
| `inTransitCount` | `number` | 是 | 待收货的调拨单数。**按单不按件** —— 收货是按单做的，给件数点不进任何一张单 |
| `openCountNo` | `string,null` | 否 | 还开着的那张盘点单的单号，没有就没有这个字段。 **给单号不给个数**：工作台的「继续盘点」要带着它跳， 不带的话那一页会开一张**新的**盘点单，而按钮上写着「继续」。 |


#### GET `/biz/inventory/suppliers`

供应商档案（挑供应商传 activeOnly=true）　🔒

**入参**：无

**出参**（`data`）

类型：[`Supplier`](#supplier)\[\]


#### POST `/biz/inventory/suppliers`

建供应商档案　🔒

**入参**：无

**出参**（`data`）

类型：[`{ supplierNo: string }`](#suppliernostring)


#### PUT `/biz/inventory/suppliers/{no}`

改供应商档案（引用平台档案的只能改备注）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：`any`


#### POST `/biz/inventory/suppliers/{no}/active`

停用 / 启用供应商　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：`any`


#### POST `/biz/inventory/transfers`

建调拨单　🔒

**入参**：无

**出参**（`data`）

类型：`string`


#### GET `/biz/inventory/transfers/{no}`

读回调拨单　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：[`StockTransfer`](#stocktransfer)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `transferNo` | `string` | 是 | — |
| `status` | `string` | 是 | DRAFT 草稿 / SHIPPED 已发出 / RECEIVED 已收到 |
| `fromLocationId` | `string` | 否 | — |
| `fromLocationName` | `string` | 否 | — |
| `toLocationId` | `string` | 否 | — |
| `toLocationName` | `string` | 否 | — |
| `shippedAt` | `string` | 否 | — |
| `receivedAt` | `string` | 否 | — |
| `carrierName` | `string` | 否 | 承运方名字快照。空 = 自己送或发货时没记 —— 不是「数据缺失」 |
| `trackingNo` | `string` | 否 | 运单号。与 carrierName 一起给收货方核对用 |
| `totalQty` | `number` | 是 | — |
| `lines` | `object`（见下）\[\] | 是 | — |

`lines[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `specText` | `string` | 否 | — |
| `qty` | `number` | 是 | — |
| `uom` | `string` | 否 | — |


#### POST `/biz/inventory/transfers/{no}/receive`

调拨收货　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：`any`


#### POST `/biz/inventory/transfers/{no}/ship`

调拨发出　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `no` | path | `string` | 是 | 该资源的业务单号 |

**出参**（`data`）

类型：`any`


### master-data

#### GET `/common/master-data`

平台主数据（行业/主体/通道）　🔒

**入参**：无

**出参**（`data`）

类型：[`MasterData`](#masterdata)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `industries` | [`MasterDataIndustry`](#masterdataindustry)\[\] | 是 | 可选行业。**决定能不能以小微主体进件**，也是 points_forced 默认值的来源 |
| `subjects` | [`MasterDataSubject`](#masterdatasubject)\[\] | 是 | 可选主体类型（法律形态）。决定资质要求与结算账户形态 |
| `channels` | [`MasterDataChannel`](#masterdatachannel)\[\] | 是 | 可用支付通道与其能力位 |
| `serviceScopes` | [`ServiceScope`](#servicescope)\[\] | 是 | **这一期开放的经营范围档位**（`SERVICE_SCOPE` 的启用子集，运营在后台配）。 端上要照它渲染选项，**不要把三档写死**。写死的后果不是「多了个选项」： 一期自营模式关掉了 `PLATFORM`，而 B 端照样把「全平台发货」摆在那里， 商家点下去得到的是「当前不支持这个经营范围」—— 一个必被拒的选项， 而他无从知道自己该选什么。2026-08-11 的端到端实测撞到过。 拿到 EDI 切平台模式时运营在后台放开，端上不发版就跟着变 —— 这正是它下发而不是写死的理由。 |


### member-reach

#### POST `/biz/member-reach/plan`

群发试算：能发多少、跳过多少　🔒

**入参**：无

**出参**（`data`）

类型：[`ReachPlan`](#reachplan)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `matched` | `number` | 是 | — |
| `reachable` | `number` | 是 | — |
| `skips` | `object`（见下）\[\] | 是 | — |

`skips[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reason` | `string` | 是 | — |
| `count` | `number` | 是 | — |


#### POST `/biz/member-reach/send`

群发（会打扰真实用户）　🔒

**入参**：无

**出参**（`data`）

类型：[`ReachResult`](#reachresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `taskNo` | `string` | 是 | — |
| `sent` | `number` | 是 | — |
| `skipped` | `number` | 是 | — |
| `skips` | `object`（见下）\[\] | 是 | — |

`skips[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reason` | `string` | 是 | — |
| `count` | `number` | 是 | — |


### member-segments

#### GET `/biz/member-segments`

人群列表　🔒

**入参**：无

**出参**（`data`）

类型：[`MemberSegment`](#membersegment)\[\]


#### POST `/biz/member-segments`

存人群（存条件不存名单）　🔒

**入参**：无

**出参**（`data`）

类型：[`MemberSegment`](#membersegment)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `segmentNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `scopeStoreNo` | `string,null` | 否 | 限定门店。空 = 全主体 |
| `rule` | [`MemberSegmentRule`](#membersegmentrule) | 是 | — |
| `lastCount` | `number` | 是 | — |
| `countedAt` | `number,null` | 否 | — |


#### POST `/biz/member-segments/{segmentNo}/remove`

删人群（端上没有 DELETE，见 http-client）　🔒

**入参**：无

**出参**（`data`）

类型：`any`


#### POST `/biz/member-segments/preview`

试算命中与可触达　🔒

**入参**：无

**出参**（`data`）

类型：[`MemberSegmentPreview`](#membersegmentpreview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `count` | `number` | 是 | — |
| `reachable` | `number` | 是 | — |


### member-settings

#### GET `/biz/member-settings`

会员经营口径　🔒

**入参**：无

**出参**（`data`）

类型：[`MemberSetting`](#membersetting)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `memberScope` | `string` | 是 | `ENTITY` 按主体（默认）/ `STORE` 按门店 |
| `autoJoinOnOrder` | `boolean` | 是 | 支付成功自动入会。关掉之后只剩手工录入与本人主动加入 |


#### PUT `/biz/member-settings`

改口径（店主）　🔒

**入参**：无

**出参**（`data`）

类型：[`MemberSetting`](#membersetting)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `memberScope` | `string` | 是 | `ENTITY` 按主体（默认）/ `STORE` 按门店 |
| `autoJoinOnOrder` | `boolean` | 是 | 支付成功自动入会。关掉之后只剩手工录入与本人主动加入 |


### member-tags

#### GET `/biz/member-tags`

标签字典（含人数）　🔒

**入参**：无

**出参**（`data`）

类型：[`MemberTag`](#membertag)\[\]


#### POST `/biz/member-tags`

新建标签　🔒

**入参**：无

**出参**（`data`）

类型：[`MemberTag`](#membertag)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `tagNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `tagType` | `string` | 是 | `SYS` 系统算的（只读）/ `MCH` 商家自己的 |
| `status` | `string` | 是 | `ACTIVE` / `DISABLED` 停用（老的还在、新的打不上）/ `MERGED` 已并入别的标签 |
| `count` | `number` | 是 | 打了多少人。服务端 COUNT 出来的，不是冗余列 |


#### PUT `/biz/member-tags/{tagNo}`

改名 / 停用　🔒

**入参**：无

**出参**（`data`）

类型：[`MemberTag`](#membertag)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `tagNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `tagType` | `string` | 是 | `SYS` 系统算的（只读）/ `MCH` 商家自己的 |
| `status` | `string` | 是 | `ACTIVE` / `DISABLED` 停用（老的还在、新的打不上）/ `MERGED` 已并入别的标签 |
| `count` | `number` | 是 | 打了多少人。服务端 COUNT 出来的，不是冗余列 |


#### POST `/biz/member-tags/{tagNo}/merge`

合并（confirm=false 只试算）　🔒

**入参**：无

**出参**（`data`）

类型：[`MemberMergePreview`](#membermergepreview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `affectedMembers` | `number` | 是 | — |
| `bothTagged` | `number` | 是 | 两个标签都有的人。合并后只保留一条 |
| `referencedActivities` | `number` | 是 | — |
| `applied` | `boolean` | 是 | false = 这只是试算，没有落库 |


### members

#### GET `/biz/members`

会员列表（筛选+分页）　🔒

**入参**：无

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Member`](#member)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### POST `/biz/members`

手工录入（未注册记为线索）　🔒

**入参**：无

**出参**（`data`）

类型：[`Member`](#member)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `memberNo` | `string` | 是 | — |
| `personNo` | `string` | 是 | 平台人档号。会员挂人不挂账号 —— 商家看不到，但详情页要用它取来源轨迹 |
| `phoneTail` | `string,null` | 否 | 手机号后四位。**永远不会有完整号** —— 需要它的只有平台申诉处置 |
| `status` | `string` | 是 | `LEAD` 线索（商家录的、本人还没注册，不可触达）/ `ACTIVE` / `BLOCKED` |
| `source` | `string` | 是 | 首次来源 `ORDER`/`SHARE`/`SCAN`/`MANUAL`/`FAVORITE`/`SEARCH` |
| `level` | `string,null` | 否 | `NEW`/`REGULAR`/`LOYAL`/`SLEEPING`。按主体还是按门店算，取决于主体的经营口径 |
| `firstStoreNo` | `string,null` | 否 | 他从哪家门店进来的 |
| `orderCount` | `number` | 是 | — |
| `totalSpentMinor` | `number` | 是 | — |
| `d90OrderCount` | `number` | 是 | — |
| `lastOrderAt` | `number,null` | 否 | — |
| `daysSinceLast` | `number,null` | 否 | — |
| `reachOptOut` | `boolean` | 是 | 买家关掉了这家店的消息。商家看得到状态，看不到原因 |
| `remark` | `string,null` | 否 | — |
| `joinedAt` | `number` | 是 | — |


#### GET `/biz/members/{memberNo}`

会员详情：各店往来与来源轨迹　🔒

**入参**：无

**出参**（`data`）

类型：[`MemberDetail`](#memberdetail)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `member` | [`Member`](#member) | 是 | — |
| `stores` | [`MemberStoreStat`](#memberstorestat)\[\] | 是 | — |
| `sources` | [`MemberSourceItem`](#membersourceitem)\[\] | 是 | — |
| `tags` | [`MemberTag`](#membertag)\[\] | 是 | — |


#### PUT `/biz/members/{memberNo}`

改备注 / 拉黑　🔒

**入参**：无

**出参**（`data`）

类型：[`Member`](#member)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `memberNo` | `string` | 是 | — |
| `personNo` | `string` | 是 | 平台人档号。会员挂人不挂账号 —— 商家看不到，但详情页要用它取来源轨迹 |
| `phoneTail` | `string,null` | 否 | 手机号后四位。**永远不会有完整号** —— 需要它的只有平台申诉处置 |
| `status` | `string` | 是 | `LEAD` 线索（商家录的、本人还没注册，不可触达）/ `ACTIVE` / `BLOCKED` |
| `source` | `string` | 是 | 首次来源 `ORDER`/`SHARE`/`SCAN`/`MANUAL`/`FAVORITE`/`SEARCH` |
| `level` | `string,null` | 否 | `NEW`/`REGULAR`/`LOYAL`/`SLEEPING`。按主体还是按门店算，取决于主体的经营口径 |
| `firstStoreNo` | `string,null` | 否 | 他从哪家门店进来的 |
| `orderCount` | `number` | 是 | — |
| `totalSpentMinor` | `number` | 是 | — |
| `d90OrderCount` | `number` | 是 | — |
| `lastOrderAt` | `number,null` | 否 | — |
| `daysSinceLast` | `number,null` | 否 | — |
| `reachOptOut` | `boolean` | 是 | 买家关掉了这家店的消息。商家看得到状态，看不到原因 |
| `remark` | `string,null` | 否 | — |
| `joinedAt` | `number` | 是 | — |


#### GET `/biz/members/stats`

四层人数与未计入买家　🔒

**入参**：无

**出参**（`data`）

类型：[`MemberStats`](#memberstats)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `newCount` | `number` | 是 | — |
| `regularCount` | `number` | 是 | — |
| `loyalCount` | `number` | 是 | — |
| `sleepingCount` | `number` | 是 | — |
| `reachable` | `number` | 是 | 可触达人数（排除线索、拉黑、已退订） |
| `newThisMonth` | `number` | 是 | — |
| `unlinkedBuyers` | `number` | 是 | 未绑手机号、因此没计进会员的买家数 |


#### POST `/biz/members/tags`

批量打标 / 去标　🔒

**入参**：无

**出参**（`data`）

类型：`any`


### merchant

#### POST `/biz/merchant/apply`

提交入驻申请　🔒

**入参**

请求体：[`MerchantApplyReqBody`](#merchantapplyreqbody)

_无字段_

**出参**（`data`）

类型：[`MerchantProfile`](#merchantprofile)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | 入驻审核状态。非 ACTIVE 时 B 端只能看到入驻流程页 |
| `subject` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型 |
| `tier` | [`MerchantTier`](#merchanttier) | 是 | 商家分层。一期恒为 SMALL |
| `phone` | `string` | 是 | 登录手机号，也是商家账号的主标识 |
| `isPickupPoint` | `boolean` | 是 | 是否承接自提点 —— 决定 B 端是否出现「履约台」入口（ADR-005） |
| `pickupNo` | `string` | 否 | 承接的自提点单号。`isPickupPoint=true` 时有值 |
| `rejectReason` | `string` | 否 | 驳回原因，status=REJECTED 时有值 |
| `loginBy` | [`GrantType`](#granttype) | 否 | 本次会话的登录方式。第三方登录且 phone 为空时，要引导补绑手机号 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 否 | 资金路径。**B 端价格字段叫什么由它决定** —— 归集（钱进平台账户）下平台是销售主体、最终售价平台定，商家填的是「期望收购价」； 直连下他自己就是销售主体，那就是「售价」。 判据用它而不是门店的 `businessMode`：与积分能力同一根轴 —— **责任跟着钱走**。 还没进件的申请人为空：那时资金路径尚未确定， 猜一个默认值会让他在入驻页看到一个还轮不到他的字段名。 |


#### GET `/biz/merchant/apply`

上次入驻申请　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantApplyReq`](#merchantapplyreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 拟用店铺名 |
| `subject` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型。个人 → 个体户 → 企业，门槛前低后高 |
| `contactName` | `string` | 是 | 联系人姓名。审核要打电话找人，只有号码没有姓名不合适 |
| `contactPhone` | `string` | 是 | 联系手机号 |
| `category` | `string` | 是 | 主营类目 |
| `desc` | `string` | 是 | 店铺简介 |
| `asPickupPoint` | `boolean` | 否 | 承接自提点：小店既是供给方也是取货点（ADR-005 type=STORE） |
| `qualificationItems` | [`QualificationItem`](#qualificationitem)\[\] | 否 | 结构化资质。**可选**：老版本端上还在只传 `licenses`， 后端对未传该字段的请求跳过执照校验（见 `OpsServiceImpl.requireLicenseIfNeeded`）—— 校验必须晚于能满足它的 UI 上线，否则拦的不是坏商家，是所有人。 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 否 | 期望经营范围（ADR-009）。申请时可空，<b>审核通过时必须确定</b> —— 否则商家上着架却对谁都不可见，且没有任何报错。 |
| `communityNos` | `string`\[\] | 否 | 期望覆盖的社区。scope=COMMUNITY 时审核通过必须非空 |
| `licenses` | `string`\[\] | 否 | 资质图片（营业执照/身份证）。**选填** —— 一期 EDI 不强制。 与下面的结算账户一样，属于**分账主体开户**而不是入驻申请本身（ADR-002）： `usr_merchant_payment` 是独立一张表、有自己的 `apply_status`，就是这个道理。 申请时能传就传，通过后在 B 端补也行 —— 逼一个还没通过审核的人先传营业执照， 只会把人挡在门外。 |
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 否 | 结算账户类型。真实账号由后端持有，C 端与 B 端都不回显（ADR-002 §5）。**选填**，同上 |
| `industry` | `string` | 否 | 行业（`sys_industry.industry`）。 **它决定这家店能不能以小微主体进件** —— 微信的小微白名单是按行业给的， 也是 `points_forced` 默认值的来源。 后端一直在收、库里一直有这一列，但契约没登记、端也没传， 于是 `mch_entity.industry` 恒空：进件时才发现主体类型选错了， 而那时商家已经开完店、上完架。 |


#### GET `/biz/merchant/pay-channel`

本店能开的收款通道（含没开的）　🔒

**入参**：无

**出参**（`data`）

类型：[`PaymentApplyment`](#paymentapplyment)\[\]


#### GET `/biz/merchant/payment`

收款进件状态　🔒

**入参**：无

**出参**（`data`）

类型：[`PaymentApplyment`](#paymentapplyment)\[\]


#### POST `/biz/merchant/payment`

补交资料并提交进件　🔒

**入参**

请求体：[`SubmitPaymentReq`](#submitpaymentreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payChannel` | `string` | 是 | 给哪个通道进件，如 WECHAT |
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 否 | 结算账户形态。不传时后端按法律形态取默认（小微打个人、其余对公） |
| `settleAccount` | `string` | 是 | 结算账号明文。见上方说明：**不落库、不进日志、不回显** |
| `licenses` | `string`\[\] | 否 | 资质图地址。小微免传，个体户与企业必传 |
| `contactName` | `string` | 否 | 进件联系人。通道核对资料时联系他，不一定等于登录人 |
| `contactPhone` | `string` | 否 | 进件联系电话 |
| `storeNo` | `string` | 否 | 为**哪家门店**进件；不传 = 主体级默认号（单店永远走这条）。 传它就是在走「分开结算」：微信侧一个商户号只能绑一个结算账户， 两家店各收各的钱，就得进件两次拿两个号。 |
| `entityNo` | `string` | 否 | 给哪张证照进件，可空 = 当前证照。多证照的老板在证照详情页进来时会带上它 |

**出参**（`data`）

类型：[`PaymentApplyment`](#paymentapplyment)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payChannel` | `string` | 是 | 通道码，如 WECHAT |
| `channelName` | `string` | 是 | 通道展示名。取服务端的，端上不要再维护一份翻译 |
| `applyStatus` | [`PaymentApplyStatus`](#paymentapplystatus) | 是 | NONE / APPLYING / ACTIVE / REJECTED / FROZEN |
| `canReceiveMoney` | `boolean` | 是 | 这个通道现在能不能收钱。 **照着它显示，不要自己去比 applyStatus** —— 比错的表现是 「显示能收钱但收不了」，而这种错要到第一笔订单才暴露。 |
| `payMerchantNo` | `string` | 否 | 收款商户号业务键，通过后才有。门店挂收款号引用的就是它 |
| `subMchidMasked` | `string` | 否 | 二级商户号掩码。完整号不回显 |
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 否 | 结算账户形态：小微打个人（PERSONAL_BANK_CARD），其余打对公（MERCHANT_ID） |
| `settleAccountMasked` | `string` | 否 | 结算账号掩码。**明文永不回显**，包括给商家自己（ADR-002 §5） |
| `rejectReason` | `string` | 否 | 驳回原因。驳回时必有 —— 没有原因商家只能反复重提 |
| `missing` | `string`\[\] | 是 | 还缺哪些资料（settleAccount / licenses / settleAccountType）。空 = 资料齐了在等通道 |
| `submitted` | `boolean` | 是 | **有没有真的发给通道过。** <p>没有它，`APPLYING` 同时表示两件相反的事：入驻通过时建的占位（商家还没填过 任何东西）与「已发给通道、在等回执」。都显示成「审核中」的话，新商家读到的是 球在平台，而球其实在他自己脚下 —— 这正是「不能收钱」最常卡死的一步。 |
| `appliedAt` | `number` | 否 | 提交进件的时间。没提交过为空 |
| `activatedAt` | `number` | 否 | 通道开户完成的时间 —— 从这一刻起才真的能收钱 |
| `storeNo` | `string` | 否 | 这条进件是**为哪家门店**做的；空 = 主体级默认号。 多门店商家会有多条「微信 · 已开通」，不显示门店就分不清哪条是哪家店 —— 等于让他猜自己的钱打进了哪张卡。 |


#### POST `/biz/merchant/payment/{payChannel}/refresh`

回查进件结果　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `payChannel` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`PaymentApplyment`](#paymentapplyment)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payChannel` | `string` | 是 | 通道码，如 WECHAT |
| `channelName` | `string` | 是 | 通道展示名。取服务端的，端上不要再维护一份翻译 |
| `applyStatus` | [`PaymentApplyStatus`](#paymentapplystatus) | 是 | NONE / APPLYING / ACTIVE / REJECTED / FROZEN |
| `canReceiveMoney` | `boolean` | 是 | 这个通道现在能不能收钱。 **照着它显示，不要自己去比 applyStatus** —— 比错的表现是 「显示能收钱但收不了」，而这种错要到第一笔订单才暴露。 |
| `payMerchantNo` | `string` | 否 | 收款商户号业务键，通过后才有。门店挂收款号引用的就是它 |
| `subMchidMasked` | `string` | 否 | 二级商户号掩码。完整号不回显 |
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 否 | 结算账户形态：小微打个人（PERSONAL_BANK_CARD），其余打对公（MERCHANT_ID） |
| `settleAccountMasked` | `string` | 否 | 结算账号掩码。**明文永不回显**，包括给商家自己（ADR-002 §5） |
| `rejectReason` | `string` | 否 | 驳回原因。驳回时必有 —— 没有原因商家只能反复重提 |
| `missing` | `string`\[\] | 是 | 还缺哪些资料（settleAccount / licenses / settleAccountType）。空 = 资料齐了在等通道 |
| `submitted` | `boolean` | 是 | **有没有真的发给通道过。** <p>没有它，`APPLYING` 同时表示两件相反的事：入驻通过时建的占位（商家还没填过 任何东西）与「已发给通道、在等回执」。都显示成「审核中」的话，新商家读到的是 球在平台，而球其实在他自己脚下 —— 这正是「不能收钱」最常卡死的一步。 |
| `appliedAt` | `number` | 否 | 提交进件的时间。没提交过为空 |
| `activatedAt` | `number` | 否 | 通道开户完成的时间 —— 从这一刻起才真的能收钱 |
| `storeNo` | `string` | 否 | 这条进件是**为哪家门店**做的；空 = 主体级默认号。 多门店商家会有多条「微信 · 已开通」，不显示门店就分不清哪条是哪家店 —— 等于让他猜自己的钱打进了哪张卡。 |


#### GET `/biz/merchant/profile`

商家资料　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantProfile`](#merchantprofile)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | 入驻审核状态。非 ACTIVE 时 B 端只能看到入驻流程页 |
| `subject` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型 |
| `tier` | [`MerchantTier`](#merchanttier) | 是 | 商家分层。一期恒为 SMALL |
| `phone` | `string` | 是 | 登录手机号，也是商家账号的主标识 |
| `isPickupPoint` | `boolean` | 是 | 是否承接自提点 —— 决定 B 端是否出现「履约台」入口（ADR-005） |
| `pickupNo` | `string` | 否 | 承接的自提点单号。`isPickupPoint=true` 时有值 |
| `rejectReason` | `string` | 否 | 驳回原因，status=REJECTED 时有值 |
| `loginBy` | [`GrantType`](#granttype) | 否 | 本次会话的登录方式。第三方登录且 phone 为空时，要引导补绑手机号 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 否 | 资金路径。**B 端价格字段叫什么由它决定** —— 归集（钱进平台账户）下平台是销售主体、最终售价平台定，商家填的是「期望收购价」； 直连下他自己就是销售主体，那就是「售价」。 判据用它而不是门店的 `businessMode`：与积分能力同一根轴 —— **责任跟着钱走**。 还没进件的申请人为空：那时资金路径尚未确定， 猜一个默认值会让他在入驻页看到一个还轮不到他的字段名。 |


#### POST `/biz/merchant/quick-start`

无证照快速开店　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantProfile`](#merchantprofile)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | 入驻审核状态。非 ACTIVE 时 B 端只能看到入驻流程页 |
| `subject` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型 |
| `tier` | [`MerchantTier`](#merchanttier) | 是 | 商家分层。一期恒为 SMALL |
| `phone` | `string` | 是 | 登录手机号，也是商家账号的主标识 |
| `isPickupPoint` | `boolean` | 是 | 是否承接自提点 —— 决定 B 端是否出现「履约台」入口（ADR-005） |
| `pickupNo` | `string` | 否 | 承接的自提点单号。`isPickupPoint=true` 时有值 |
| `rejectReason` | `string` | 否 | 驳回原因，status=REJECTED 时有值 |
| `loginBy` | [`GrantType`](#granttype) | 否 | 本次会话的登录方式。第三方登录且 phone 为空时，要引导补绑手机号 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 否 | 资金路径。**B 端价格字段叫什么由它决定** —— 归集（钱进平台账户）下平台是销售主体、最终售价平台定，商家填的是「期望收购价」； 直连下他自己就是销售主体，那就是「售价」。 判据用它而不是门店的 `businessMode`：与积分能力同一根轴 —— **责任跟着钱走**。 还没进件的申请人为空：那时资金路径尚未确定， 猜一个默认值会让他在入驻页看到一个还轮不到他的字段名。 |


### message

#### GET `/biz/message`

商家消息列表　🔒

**入参**：无

**出参**（`data`）

类型：[`Message`](#message)\[\]


#### POST `/biz/message/{messageNo}/read`

标记已读　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `messageNo` | path | `string` | 是 | 站内消息单号 |

**出参**（`data`）

类型：[`Message`](#message)\[\]


#### POST `/biz/message/read-all`

全部已读　🔒

**入参**：无

**出参**（`data`）

类型：[`Message`](#message)\[\]


#### GET `/biz/message/unread-count`

未读数（红点轮询，只给一个数）　🔒

**入参**：无

**出参**（`data`）

类型：`number`


### my-spec-dims

#### GET `/biz/my-spec-dims`

我建的规格维度（含用量与配额）　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantSpecDim`](#merchantspecdim)\[\]


#### POST `/biz/my-spec-dims/{dimNo}/archive`

停用/启用自建维度　🔒

**入参**：无

**出参**（`data`）

类型：`any`


#### POST `/biz/my-spec-dims/{dimNo}/rename`

给自建维度改名　🔒

**入参**：无

**出参**（`data`）

类型：`any`


### order

#### GET `/biz/order`

订单列表　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `page` | query | `number` | 否 | 页码，从 1 起 |
| `size` | query | `number` | 否 | 每页条数 |
| `status` | query | [`OrderStatus`](#orderstatus) | 否 | 状态筛选，取值见对应枚举 |
| `allStores` | query | `boolean` | 否 | — |

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Order`](#order)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/biz/order/{orderNo}`

订单详情　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单单号 |
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。粗粒度；售后细节见 `afterSale` |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式，下单时锁定 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行。含赠品行（`isGift`，价格为 0） |
| `amount` | [`OrderAmount`](#orderamount) | 是 | 金额明细 |
| `verifyCode` | `string` | 否 | 自提码 / 核销码 |
| `redeemCode` | `string` | 否 | VIRTUAL：兑换码；CARD：卡号 |
| `pickupNo` | `string` | 否 | PICKUP：自提点单号 |
| `pickupName` | `string` | 否 | PICKUP：自提点名称快照 |
| `expressNo` | `string` | 否 | EXPRESS：快递单号，发货后才有 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `createdAt` | `number` | 是 | 下单时间 |
| `payDeadlineAt` | `number` | 否 | 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 |
| `timeline` | [`OrderTimelineNode`](#ordertimelinenode)\[\] | 是 | 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 |
| `idempotencyKey` | `string` | 否 | 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 |
| `buyerNickname` | `string` | 否 | 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 |
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 ⚠️ **后端叫 `payOrderNo`，库里是 `ord_order.order_no`** —— 三处三个名字。 按这个名去后端或库里找会找不到（2026-08-17 人工测试时撞到）。 |
| `subOrders` | [`Order`](#order)\[\] | 否 | **仅支付视角**：这次付款覆盖的各商家订单。订单视角为空。 后端 `OrderVO` 一直在发（同一个结构承担订单/支付两种视角）， 端上此前没声明 —— 于是收银台是整条拆单链路里**唯一哑掉的一屏**： 购物车说会拆 2 单、确认页说会拆 2 单、订单详情各自标着商家， 中间付款那一步却只有一个总额。 |


#### POST `/biz/order/{orderNo}/confirm-offline-pay`

确认线下收款　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单单号 |
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。粗粒度；售后细节见 `afterSale` |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式，下单时锁定 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行。含赠品行（`isGift`，价格为 0） |
| `amount` | [`OrderAmount`](#orderamount) | 是 | 金额明细 |
| `verifyCode` | `string` | 否 | 自提码 / 核销码 |
| `redeemCode` | `string` | 否 | VIRTUAL：兑换码；CARD：卡号 |
| `pickupNo` | `string` | 否 | PICKUP：自提点单号 |
| `pickupName` | `string` | 否 | PICKUP：自提点名称快照 |
| `expressNo` | `string` | 否 | EXPRESS：快递单号，发货后才有 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `createdAt` | `number` | 是 | 下单时间 |
| `payDeadlineAt` | `number` | 否 | 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 |
| `timeline` | [`OrderTimelineNode`](#ordertimelinenode)\[\] | 是 | 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 |
| `idempotencyKey` | `string` | 否 | 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 |
| `buyerNickname` | `string` | 否 | 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 |
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 ⚠️ **后端叫 `payOrderNo`，库里是 `ord_order.order_no`** —— 三处三个名字。 按这个名去后端或库里找会找不到（2026-08-17 人工测试时撞到）。 |
| `subOrders` | [`Order`](#order)\[\] | 否 | **仅支付视角**：这次付款覆盖的各商家订单。订单视角为空。 后端 `OrderVO` 一直在发（同一个结构承担订单/支付两种视角）， 端上此前没声明 —— 于是收银台是整条拆单链路里**唯一哑掉的一屏**： 购物车说会拆 2 单、确认页说会拆 2 单、订单详情各自标着商家， 中间付款那一步却只有一个总额。 |


#### POST `/biz/order/{orderNo}/delivered`

自送已送达　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单单号 |
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。粗粒度；售后细节见 `afterSale` |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式，下单时锁定 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行。含赠品行（`isGift`，价格为 0） |
| `amount` | [`OrderAmount`](#orderamount) | 是 | 金额明细 |
| `verifyCode` | `string` | 否 | 自提码 / 核销码 |
| `redeemCode` | `string` | 否 | VIRTUAL：兑换码；CARD：卡号 |
| `pickupNo` | `string` | 否 | PICKUP：自提点单号 |
| `pickupName` | `string` | 否 | PICKUP：自提点名称快照 |
| `expressNo` | `string` | 否 | EXPRESS：快递单号，发货后才有 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `createdAt` | `number` | 是 | 下单时间 |
| `payDeadlineAt` | `number` | 否 | 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 |
| `timeline` | [`OrderTimelineNode`](#ordertimelinenode)\[\] | 是 | 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 |
| `idempotencyKey` | `string` | 否 | 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 |
| `buyerNickname` | `string` | 否 | 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 |
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 ⚠️ **后端叫 `payOrderNo`，库里是 `ord_order.order_no`** —— 三处三个名字。 按这个名去后端或库里找会找不到（2026-08-17 人工测试时撞到）。 |
| `subOrders` | [`Order`](#order)\[\] | 否 | **仅支付视角**：这次付款覆盖的各商家订单。订单视角为空。 后端 `OrderVO` 一直在发（同一个结构承担订单/支付两种视角）， 端上此前没声明 —— 于是收银台是整条拆单链路里**唯一哑掉的一屏**： 购物车说会拆 2 单、确认页说会拆 2 单、订单详情各自标着商家， 中间付款那一步却只有一个总额。 |


#### POST `/biz/order/{orderNo}/ship`

快递发货　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

请求体：[`ShipReq`](#shipreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `expressNo` | `string` | 是 | 快递单号。填了即视为已发货，订单流转到 SHIPPED |

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单单号 |
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。粗粒度；售后细节见 `afterSale` |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式，下单时锁定 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行。含赠品行（`isGift`，价格为 0） |
| `amount` | [`OrderAmount`](#orderamount) | 是 | 金额明细 |
| `verifyCode` | `string` | 否 | 自提码 / 核销码 |
| `redeemCode` | `string` | 否 | VIRTUAL：兑换码；CARD：卡号 |
| `pickupNo` | `string` | 否 | PICKUP：自提点单号 |
| `pickupName` | `string` | 否 | PICKUP：自提点名称快照 |
| `expressNo` | `string` | 否 | EXPRESS：快递单号，发货后才有 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `createdAt` | `number` | 是 | 下单时间 |
| `payDeadlineAt` | `number` | 否 | 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 |
| `timeline` | [`OrderTimelineNode`](#ordertimelinenode)\[\] | 是 | 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 |
| `idempotencyKey` | `string` | 否 | 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 |
| `buyerNickname` | `string` | 否 | 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 |
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 ⚠️ **后端叫 `payOrderNo`，库里是 `ord_order.order_no`** —— 三处三个名字。 按这个名去后端或库里找会找不到（2026-08-17 人工测试时撞到）。 |
| `subOrders` | [`Order`](#order)\[\] | 否 | **仅支付视角**：这次付款覆盖的各商家订单。订单视角为空。 后端 `OrderVO` 一直在发（同一个结构承担订单/支付两种视角）， 端上此前没声明 —— 于是收银台是整条拆单链路里**唯一哑掉的一屏**： 购物车说会拆 2 单、确认页说会拆 2 单、订单详情各自标着商家， 中间付款那一步却只有一个总额。 |


### pickable-props

#### GET `/biz/pickable-props`

还能加进这一类的商品参数（本类目已配 + 平台通用 + 自建）　🔒

**入参**：无

**出参**（`data`）

类型：[`SpecTemplate`](#spectemplate)\[\]


### pickup

#### POST `/biz/pickup/{orderNo}/report`

破损短少上报　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

请求体：[`ReportShortageReq`](#reportshortagereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 出问题的 SKU |
| `kind` | [`ArrivalIssueKind`](#arrivalissuekind) | 是 | 问题类型：少件 / 破损。两者的售后责任判定不同 |
| `qty` | `number` | 是 | 缺/坏了几件。此前端上不收集这个数，后端落库恒为 1，分拣汇总的短缺数字从设计上就是错的 |
| `note` | `string` | 是 | 情况说明。承接方填，供货方与平台据此定责 |

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单单号 |
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。粗粒度；售后细节见 `afterSale` |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式，下单时锁定 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行。含赠品行（`isGift`，价格为 0） |
| `amount` | [`OrderAmount`](#orderamount) | 是 | 金额明细 |
| `verifyCode` | `string` | 否 | 自提码 / 核销码 |
| `redeemCode` | `string` | 否 | VIRTUAL：兑换码；CARD：卡号 |
| `pickupNo` | `string` | 否 | PICKUP：自提点单号 |
| `pickupName` | `string` | 否 | PICKUP：自提点名称快照 |
| `expressNo` | `string` | 否 | EXPRESS：快递单号，发货后才有 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `createdAt` | `number` | 是 | 下单时间 |
| `payDeadlineAt` | `number` | 否 | 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 |
| `timeline` | [`OrderTimelineNode`](#ordertimelinenode)\[\] | 是 | 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 |
| `idempotencyKey` | `string` | 否 | 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 |
| `buyerNickname` | `string` | 否 | 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 |
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 ⚠️ **后端叫 `payOrderNo`，库里是 `ord_order.order_no`** —— 三处三个名字。 按这个名去后端或库里找会找不到（2026-08-17 人工测试时撞到）。 |
| `subOrders` | [`Order`](#order)\[\] | 否 | **仅支付视角**：这次付款覆盖的各商家订单。订单视角为空。 后端 `OrderVO` 一直在发（同一个结构承担订单/支付两种视角）， 端上此前没声明 —— 于是收银台是整条拆单链路里**唯一哑掉的一屏**： 购物车说会拆 2 单、确认页说会拆 2 单、订单详情各自标着商家， 中间付款那一步却只有一个总额。 |


#### POST `/biz/pickup/arrived`

标记到货　🔒

**入参**

请求体：[`MarkArrivedReq`](#markarrivedreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNos` | `string`\[\] | 是 | 批量：一次到货通常是一整批，逐单调用会让通知发成 N 条 |
| `pickupNo` | `string` | 否 | 给哪个自提点登记；**不传 = 当前门店的那个点**。 一个商家两家店两个点是常态（自提点归属到门店之后）。不传且当前门店没有点时 后端会拒 —— 而不是悄悄登记到另一个点上。 |

**出参**（`data`）

类型：[`Order`](#order)\[\]


#### GET `/biz/pickup/orders`

本自提点订单　🔒

**入参**：无

**出参**（`data`）

类型：[`Order`](#order)\[\]


#### GET `/biz/pickup/overview`

自提点履约总览　🔒

**入参**：无

**出参**（`data`）

类型：[`PickupOverview`](#pickupoverview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | 自提点单号 |
| `pickupName` | `string` | 是 | 自提点名称 |
| `pendingVerify` | `number` | 是 | 待核销单数 —— 到货了还没人来取的 |
| `arrivedBatches` | `number` | 是 | 今日到货批次 |
| `serviceFeeMinor` | `number` | 是 | 累计履约服务费（最小货币单位） |


#### GET `/biz/pickup/picking`

分拣单　🔒

**入参**：无

**出参**（`data`）

类型：[`PickingRow`](#pickingrow)\[\]


#### POST `/biz/pickup/verify`

核销自提码　🔒

**入参**

请求体：[`VerifyReq`](#verifyreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `verifyCode` | `string` | 是 | 取货码。字段名必须是 `verifyCode` —— 后端 `BizPickupController.VerifyReq` 收的是它。 这里曾经写作 `code`：**路径对得上、body 对不上**，守卫只比路径看不出来， 联调时才会以 400 的形式暴露。 |
| `onBehalf` | `boolean` | 否 | 代客核销（老人没带手机，店主代为确认）。留痕在服务端 |

**出参**（`data`）

类型：[`Order`](#order)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单单号 |
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。粗粒度；售后细节见 `afterSale` |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式，下单时锁定 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行。含赠品行（`isGift`，价格为 0） |
| `amount` | [`OrderAmount`](#orderamount) | 是 | 金额明细 |
| `verifyCode` | `string` | 否 | 自提码 / 核销码 |
| `redeemCode` | `string` | 否 | VIRTUAL：兑换码；CARD：卡号 |
| `pickupNo` | `string` | 否 | PICKUP：自提点单号 |
| `pickupName` | `string` | 否 | PICKUP：自提点名称快照 |
| `expressNo` | `string` | 否 | EXPRESS：快递单号，发货后才有 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `createdAt` | `number` | 是 | 下单时间 |
| `payDeadlineAt` | `number` | 否 | 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 |
| `timeline` | [`OrderTimelineNode`](#ordertimelinenode)\[\] | 是 | 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 |
| `idempotencyKey` | `string` | 否 | 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 |
| `buyerNickname` | `string` | 否 | 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 |
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 ⚠️ **后端叫 `payOrderNo`，库里是 `ord_order.order_no`** —— 三处三个名字。 按这个名去后端或库里找会找不到（2026-08-17 人工测试时撞到）。 |
| `subOrders` | [`Order`](#order)\[\] | 否 | **仅支付视角**：这次付款覆盖的各商家订单。订单视角为空。 后端 `OrderVO` 一直在发（同一个结构承担订单/支付两种视角）， 端上此前没声明 —— 于是收银台是整条拆单链路里**唯一哑掉的一屏**： 购物车说会拆 2 单、确认页说会拆 2 单、订单详情各自标着商家， 中间付款那一步却只有一个总额。 |


#### POST `/biz/pickup/verify/batch`

批量核销　🔒

**入参**

请求体：[`VerifyBatchReq`](#verifybatchreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `verifyCodes` | `string`\[\] | 是 | 一批取货码。**逐条尝试、不整批回滚** —— 失败的逐条回报（见 `VerifyBatchResult`）， 否则一张废码会让另外几单白扫。 |

**出参**（`data`）

类型：[`VerifyBatchResult`](#verifybatchresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `successCount` | `number` | 是 | 成功核销的单数 |
| `failed` | `object`（见下）\[\] | 是 | 失败明细。code 是那张码，reason 是为什么不行 |

`failed[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | — |
| `reason` | `string` | 是 | — |


#### GET `/biz/pickup/verify/search`

按取货码片段搜单　🔒

**入参**：无

**出参**（`data`）

类型：[`Order`](#order)\[\]


### pickup-points

#### POST `/biz/pickup-points`

自建自提点（待运营核实）　🔒

**入参**：无

**出参**（`data`）

类型：[`PickupCandidate`](#pickupcandidate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `address` | `string,null` | 否 | — |
| `type` | [`PickupPointType`](#pickuppointtype) | 是 | — |
| `status` | `string` | 是 | ACTIVE / PENDING / REJECTED …；本店自建的 PENDING 点可引用，别家的不行 |
| `communityNo` | `string` | 是 | — |
| `communityName` | `string,null` | 否 | — |
| `ownerStoreNo` | `string,null` | 否 | STORE 点的承接门店；= 本店即「我自建的」 |
| `rejectReason` | `string,null` | 否 | — |


#### GET `/biz/pickup-points/candidates`

门店可引用的取货点候选　🔒

**入参**：无

**出参**（`data`）

类型：[`PickupCandidate`](#pickupcandidate)\[\]


### plan

#### GET `/biz/plan`

我的套餐（档位/用量/三档对比）　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantPlan`](#merchantplan)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `planCode` | `string` | 是 | 档位码。**文案用 `planName`，不要按 code 自己映射** —— 运营改了名端上不会跟着变 |
| `planName` | `string` | 是 | 档位显示名（「成长版」） |
| `status` | [`PlanStatus`](#planstatus) | 是 | ACTIVE 生效中 / GRACE 宽限期（**能力全保留**，7 天）/ EXPIRED 已过期并降级。 <p>GRACE 要显示成「即将到期，请尽快续费」而**不是**「已失效」： 他的门店、子账号、跨店数据一样都没少，这时候说失效只会让他打客服电话。 |
| `startAt` | `number,null` | 否 | 订阅起始时间（毫秒）。null = 还没有过任何订阅 |
| `expireAt` | `number,null` | 否 | 到期时间（毫秒）。null = 不到期（免费档） |
| `storeQuota` | `number` | 是 | 生效门店额度 |
| `storeUsed` | `number` | 是 | 已用门店数。**后端算，只数营业中的店** —— 端上自己数会与建店那道闸的口径分岔 |
| `staffQuota` | `number` | 是 | 生效子账号额度 |
| `staffUsed` | `number` | 是 | 已用子账号数（不含老板本人） |
| `crossStoreStats` | `boolean` | 是 | 有没有跨店总览与对比 |
| `trialUsed` | `boolean` | 是 | 试用是否已用过。**一主体一次，永不回退** |
| `trialTier` | `string,null` | 否 | 可试用的目标档位码；null = 现在不能试用（已用过 / 已经是付费档 / 平台没配试用）。 <p>端上按它决定要不要显示「免费试用」按钮 —— 不要自己用 `planCode === 'FREE' && !trialUsed` 推：那会漏掉「平台把试用天数配成 0」这种情况。 |
| `trialDays` | `number,null` | 否 | 试用天数，配合 `trialTier` 显示「免费试用 14 天」 |
| `suspendedStores` | `string`\[\] | 是 | 因降级被压成只读的门店名。 <p>**只含平台压的那几家**，商家自己停用的不在里面 —— 页面要写明是「哪几家」：只说「部分门店已停用」，他得自己一家家点开去找。 |
| `tiers` | [`PlanTier`](#plantier)\[\] | 是 | 三档对比，顺序即展示顺序（后端按 sort 排好） |


#### POST `/biz/plan/trial`

自助开通试用（一主体一次）　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantPlan`](#merchantplan)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `planCode` | `string` | 是 | 档位码。**文案用 `planName`，不要按 code 自己映射** —— 运营改了名端上不会跟着变 |
| `planName` | `string` | 是 | 档位显示名（「成长版」） |
| `status` | [`PlanStatus`](#planstatus) | 是 | ACTIVE 生效中 / GRACE 宽限期（**能力全保留**，7 天）/ EXPIRED 已过期并降级。 <p>GRACE 要显示成「即将到期，请尽快续费」而**不是**「已失效」： 他的门店、子账号、跨店数据一样都没少，这时候说失效只会让他打客服电话。 |
| `startAt` | `number,null` | 否 | 订阅起始时间（毫秒）。null = 还没有过任何订阅 |
| `expireAt` | `number,null` | 否 | 到期时间（毫秒）。null = 不到期（免费档） |
| `storeQuota` | `number` | 是 | 生效门店额度 |
| `storeUsed` | `number` | 是 | 已用门店数。**后端算，只数营业中的店** —— 端上自己数会与建店那道闸的口径分岔 |
| `staffQuota` | `number` | 是 | 生效子账号额度 |
| `staffUsed` | `number` | 是 | 已用子账号数（不含老板本人） |
| `crossStoreStats` | `boolean` | 是 | 有没有跨店总览与对比 |
| `trialUsed` | `boolean` | 是 | 试用是否已用过。**一主体一次，永不回退** |
| `trialTier` | `string,null` | 否 | 可试用的目标档位码；null = 现在不能试用（已用过 / 已经是付费档 / 平台没配试用）。 <p>端上按它决定要不要显示「免费试用」按钮 —— 不要自己用 `planCode === 'FREE' && !trialUsed` 推：那会漏掉「平台把试用天数配成 0」这种情况。 |
| `trialDays` | `number,null` | 否 | 试用天数，配合 `trialTier` 显示「免费试用 14 天」 |
| `suspendedStores` | `string`\[\] | 是 | 因降级被压成只读的门店名。 <p>**只含平台压的那几家**，商家自己停用的不在里面 —— 页面要写明是「哪几家」：只说「部分门店已停用」，他得自己一家家点开去找。 |
| `tiers` | [`PlanTier`](#plantier)\[\] | 是 | 三档对比，顺序即展示顺序（后端按 sort 排好） |


### points

#### GET `/biz/points/account`

本期发分服务费与开关状态　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantPointAccount`](#merchantpointaccount)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `periodExpenseMinor` | `number` | 是 | 本期发分服务费支出（分）。**商家唯一感知到的积分成本** |
| `period` | `string` | 是 | 当前账期标识，如 `2026-08` |
| `enabled` | `boolean` | 是 | 本店积分是否生效 —— 全局 AND 社区 AND 主体非小微 AND 本店开关 |
| `disabledReason` | `string` | 否 | 不生效的原因，直接展示给商家。 小微主体要说「升级为个体工商户后可开启」，不能说「本店未开启积分」—— 后者会让商家去开一个他根本开不了的开关。 |
| `forced` | `boolean` | 是 | 平台按行业强制开，商家不可自行关闭 |


#### GET `/biz/points/records`

发分服务费明细（按单）　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantPointsRecord`](#merchantpointsrecord)\[\]


#### POST `/biz/points/toggle`

开/关本店积分　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantPointAccount`](#merchantpointaccount)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `periodExpenseMinor` | `number` | 是 | 本期发分服务费支出（分）。**商家唯一感知到的积分成本** |
| `period` | `string` | 是 | 当前账期标识，如 `2026-08` |
| `enabled` | `boolean` | 是 | 本店积分是否生效 —— 全局 AND 社区 AND 主体非小微 AND 本店开关 |
| `disabledReason` | `string` | 否 | 不生效的原因，直接展示给商家。 小微主体要说「升级为个体工商户后可开启」，不能说「本店未开启积分」—— 后者会让商家去开一个他根本开不了的开关。 |
| `forced` | `boolean` | 是 | 平台按行业强制开，商家不可自行关闭 |


### push-token

#### POST `/biz/push-token`

绑定 App 推送设备（登录后）　🔒

**入参**：无

**出参**（`data`）

类型：`any`


#### POST `/biz/push-token/unregister`

解绑推送设备（登出前，共用设备换班必须解）　🔒

**入参**：无

**出参**（`data`）

类型：`any`


### qualifications

#### GET `/biz/qualifications`

我的资质与已获授权的类目　🔒

**入参**：无

**出参**（`data`）

类型：[`MyQualifications`](#myqualifications)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `items` | [`Qualification`](#qualification)\[\] | 是 | — |
| `grantedCodes` | `string`\[\] | 是 | 已获授权的类目码。端上据此把「已解锁 / 待授权」标出来 |
| `catalog` | [`AuthCodeInfo`](#authcodeinfo)\[\] | 是 | — |


#### POST `/biz/qualifications/save`

传一张资质证件　🔒

**入参**：无

**出参**（`data`）

类型：[`Qualification`](#qualification)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `qualNo` | `string` | 是 | — |
| `entityNo` | `string` | 否 | 归属主体。端上其实用不到（只会看自己的），但后端在发 —— 声明出来免得契约守卫把它算成缺口 |
| `qualType` | [`QualificationType`](#qualificationtype) | 是 | — |
| `qualName` | `string` | 是 | 证件名，如「食品经营许可证」。上架校验拿它与类目门槛的文案比对 |
| `qualNumber` | `string,null` | 否 | — |
| `imageUrl` | `string,null` | 否 | — |
| `expireAt` | `number,null` | 否 | 有效期截止（毫秒）。**空 = 长期有效**，与「已过期」是两回事 |
| `status` | `string` | 是 | VALID / EXPIRED / REVOKED |


### regions

#### GET `/biz/regions`

行政区划下一级（框覆盖范围用）　🔒

**入参**：无

**出参**（`data`）

类型：[`Region`](#region)\[\]


#### GET `/biz/regions/path`

区划从省到自身的路径　🔒

**入参**：无

**出参**（`data`）

类型：[`Region`](#region)\[\]


#### GET `/biz/regions/search`

跨级搜区划与聚落（选择器搜索）　🔒

**入参**：无

**出参**（`data`）

类型：[`RegionSearchResult`](#regionsearchresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regions` | `object`（见下）\[\] | 是 | — |
| `communities` | `object`（见下）\[\] | 是 | — |
| `villages` | `object`（见下）\[\] | 否 | 还没开通的**官方村**（第五级名录）。已开通的那些走 `communities`（能直接勾）， 这里只出没开通的 —— 同一个地方不该在两组里各出现一次。 官方村提报即开通，所以端上点一条就能直接用。 |
| `places` | [`GeoTip`](#geotip)\[\] | 否 | 地图上的地点（v5）。**只在库里没有村/小区命中时才有值** —— 服务端先查库， 库里没有才现问高德；App 不用再自己调原生 SDK 兜底了。 |

`regions[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | — |
| `level` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `path` | `string` | 是 | — |

`communities[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communityNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `regionCode` | `string,null` | 否 | — |
| `path` | `string` | 是 | — |
| `kind` | `string,null` | 否 | ESTATE 小区 / VILLAGE 村。判「这一条底下还有没有下一级」用它，名字这时已经是口语名了 |
| `originCode` | `string,null` | 否 | 下钻要用它，不是 regionCode（那是它挂的街道/镇）。没有它就是地图开通的小区，没有下一级 |
| `originName` | `string,null` | 否 | 原始官方名（如「景滑村委会」），仅供展示/追溯 —— 判城乡用下面的 rural |
| `rural` | `boolean` | 否 | 是不是村委会（服务端存的）。判「这一条给不给 ›」用它，不要解析 originName |
| `latE6` | `number,null` | 否 | — |
| `lngE6` | `number,null` | 否 | — |

`villages[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `streetCode` | `string` | 是 | 它挂的街道码（9 位）。提报要挂到这下面 |
| `path` | `string` | 是 | — |
| `latE6` | `number,null` | 否 | — |
| `lngE6` | `number,null` | 否 | — |
| `rural` | `boolean` | 否 | 是不是村委会（服务端存的）。判「这一条给不给 ›」用它 |


#### GET `/biz/regions/villages`

街道/镇下的官方村名词典（提报村用）　🔒

**入参**：无

**出参**（`data`）

类型：[`Region`](#region)\[\]


### review

#### GET `/biz/review`

评价列表　🔒

**入参**：无

**出参**（`data`）

类型：[`Review`](#review)\[\]


#### POST `/biz/review/{reviewNo}/appeal`

申诉差评　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `reviewNo` | path | `string` | 是 | 评价单号 |

请求体：[`AppealReviewReq`](#appealreviewreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reason` | `string` | 是 | 申诉理由。这是**唯一**能把差评送进平台裁决台的入口 |
| `images` | `string`\[\] | 否 | 举证图：聊天记录、物流截图 |

**出参**（`data`）

类型：[`Review`](#review)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reviewNo` | `string` | 是 | 评价单号 |
| `goodsNo` | `string` | 是 | 被评价的商品 |
| `merchantNo` | `string` | 是 | 被评价的商家。差评会计入商家评分与申诉流程 |
| `nickname` | `string` | 是 | 评价人昵称（匿名评价时为「匿名用户」） |
| `avatar` | `string` | 是 | 评价人头像 |
| `rating` | `number` | 是 | 总分，1–5 整数 |
| `content` | `string` | 是 | 评价正文 |
| `images` | `string`\[\] | 是 | 评价图 URL 列表 |
| `spec` | `string` | 是 | 购买规格。展示在评价上，让人知道这条评价说的是哪个 SKU |
| `createdAt` | `number` | 是 | 评价提交时间 |
| `likeCount` | `number` | 是 | 点赞数 |
| `liked` | `boolean` | 是 | 当前用户是否已点赞 |
| `reply` | `string` | 否 | 商家回复 |
| `scores` | [`ReviewScores`](#reviewscores) | 否 | 三维度评分（B-9.3 / P-13.1.4）。总分 `rating` 仍保留 —— 老数据没有分维度分，列表页也只显示一个星级；维度分用于**评分算法与商家诊断**： 「货好但送得慢」这种问题，只看总分永远看不出来。 |
| `appeal` | [`ReviewAppeal`](#reviewappeal) | 否 | 商家申诉（B-9.4）。裁决在平台端 P-13.1 |


#### POST `/biz/review/{reviewNo}/reply`

回复评价　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `reviewNo` | path | `string` | 是 | 评价单号 |

请求体：[`ReplyReviewReq`](#replyreviewreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reply` | `string` | 是 | 回复内容。公开展示在评价下方，一条评价只能回一次 |

**出参**（`data`）

类型：[`Review`](#review)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reviewNo` | `string` | 是 | 评价单号 |
| `goodsNo` | `string` | 是 | 被评价的商品 |
| `merchantNo` | `string` | 是 | 被评价的商家。差评会计入商家评分与申诉流程 |
| `nickname` | `string` | 是 | 评价人昵称（匿名评价时为「匿名用户」） |
| `avatar` | `string` | 是 | 评价人头像 |
| `rating` | `number` | 是 | 总分，1–5 整数 |
| `content` | `string` | 是 | 评价正文 |
| `images` | `string`\[\] | 是 | 评价图 URL 列表 |
| `spec` | `string` | 是 | 购买规格。展示在评价上，让人知道这条评价说的是哪个 SKU |
| `createdAt` | `number` | 是 | 评价提交时间 |
| `likeCount` | `number` | 是 | 点赞数 |
| `liked` | `boolean` | 是 | 当前用户是否已点赞 |
| `reply` | `string` | 否 | 商家回复 |
| `scores` | [`ReviewScores`](#reviewscores) | 否 | 三维度评分（B-9.3 / P-13.1.4）。总分 `rating` 仍保留 —— 老数据没有分维度分，列表页也只显示一个星级；维度分用于**评分算法与商家诊断**： 「货好但送得慢」这种问题，只看总分永远看不出来。 |
| `appeal` | [`ReviewAppeal`](#reviewappeal) | 否 | 商家申诉（B-9.4）。裁决在平台端 P-13.1 |


### role

#### POST `/biz/role/{roleCode}`

改角色　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `roleCode` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`MerchantRole`](#merchantrole)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `roleCode` | `string` | 是 | 角色码。预置是 `OWNER`/`MANAGER`… ，自定义是生成的业务键 —— **别拿它给店主看** |
| `name` | `string` | 是 | 显示名。预置角色也有 —— 别拿 `MANAGER` 直接给店主看 |
| `builtin` | `boolean` | 是 | 平台预置：**只读**，要改先「复制为自定义角色」 |
| `perms` | `string`\[\] | 是 | 这个角色带的权限码。老板那行是 `["*"]`（全部），别按长度当权限数 |
| `permLabels` | `string`\[\] | 是 | 与 `perms` 一一对应的中文短说明 |
| `usedBy` | `number` | 是 | 几个人在用。删除按钮据此禁用，并且要显示出来 |


#### POST `/biz/role/{roleCode}/delete`

删除自定义角色　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `roleCode` | path | `string` | 是 | — |

**出参**（`data`）

类型：`any`


### role-perms

#### GET `/biz/role-perms`

可勾的权限点　🔒

**入参**：无

**出参**（`data`）

类型：[`PermOption`](#permoption)\[\]


### roles

#### GET `/biz/roles`

角色列表（预置 + 自定义）　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantRole`](#merchantrole)\[\]


#### POST `/biz/roles`

建自定义角色　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantRole`](#merchantrole)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `roleCode` | `string` | 是 | 角色码。预置是 `OWNER`/`MANAGER`… ，自定义是生成的业务键 —— **别拿它给店主看** |
| `name` | `string` | 是 | 显示名。预置角色也有 —— 别拿 `MANAGER` 直接给店主看 |
| `builtin` | `boolean` | 是 | 平台预置：**只读**，要改先「复制为自定义角色」 |
| `perms` | `string`\[\] | 是 | 这个角色带的权限码。老板那行是 `["*"]`（全部），别按长度当权限数 |
| `permLabels` | `string`\[\] | 是 | 与 `perms` 一一对应的中文短说明 |
| `usedBy` | `number` | 是 | 几个人在用。删除按钮据此禁用，并且要显示出来 |


### settle

#### GET `/biz/settle/bills`

结算单列表　🔒

**入参**：无

**出参**（`data`）

类型：[`SettleBill`](#settlebill)\[\]


#### GET `/biz/settle/income`

收入按状态汇总　🔒

**入参**：无

**出参**（`data`）

类型：[`IncomeSummary`](#incomesummary)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `receivedMinor` | `number` | 是 | 已到账：通道回执确认过的 |
| `inFlightMinor` | `number` | 是 | 已发起、等通道确认。**此前它混在「已到账」里**，而底下是桩 |
| `pendingMinor` | `number` | 是 | 待结算 |
| `offlineMinor` | `number` | 是 | 当面收款：**他早就拿到了**，无需结算 |
| `inFlightCount` | `number` | 是 | — |
| `oldestInFlightAt` | `number,null` | 否 | 最早一笔在途的发起时刻。**「卡了多久」是商家真正想问的** |


#### GET `/biz/settle/rate-card`

费率卡　🔒

**入参**：无

**出参**（`data`）

类型：[`RateCard`](#ratecard)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantOwnedRate` | `number` | 是 | 自带客流费率（万分比）。商家自己带来的客人，平台抽成低 |
| `platformRate` | `number` | 是 | 平台客流费率（万分比）。平台分发带来的订单 |
| `note` | `string` | 是 | 费率说明文案。**须写明「以下单时快照为准，调整不影响历史订单」** |


### sku-identity

#### GET `/biz/sku-identity/export`

导出本店全部规格行的条码/货号/单位　🔒

**入参**：无

**出参**（`data`）

类型：[`{ csv: string }`](#csvstring)


#### POST `/biz/sku-identity/import`

商品编码批量导入　🔒

**入参**：无

**出参**（`data`）

类型：[`SkuIdentityReport`](#skuidentityreport)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 数据行数，不含表头 |
| `willSet` | `number` | 是 | 会真正写下去的行数 |
| `noChange` | `number` | 是 | 匹配上了但三列都没变的行数 |
| `problems` | `object`（见下）\[\] | 是 | — |
| `samples` | `object`（见下）\[\] | 是 | 前几行的前后对照，让他确认「改的是不是我想的那些」 |

`problems[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `line` | `number` | 是 | — |
| `reason` | `string` | 是 | — |

`samples[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | — |
| `goods` | `string` | 是 | — |
| `spec` | `string` | 是 | — |
| `barcodeFrom` | `string,null` | 否 | — |
| `barcodeTo` | `string,null` | 否 | — |
| `codeFrom` | `string,null` | 否 | — |
| `codeTo` | `string,null` | 否 | — |
| `unitFrom` | `string,null` | 否 | — |
| `unitTo` | `string,null` | 否 | — |


#### POST `/biz/sku-identity/import/plan`

商品编码导入试算（不写库）　🔒

**入参**：无

**出参**（`data`）

类型：[`SkuIdentityReport`](#skuidentityreport)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 数据行数，不含表头 |
| `willSet` | `number` | 是 | 会真正写下去的行数 |
| `noChange` | `number` | 是 | 匹配上了但三列都没变的行数 |
| `problems` | `object`（见下）\[\] | 是 | — |
| `samples` | `object`（见下）\[\] | 是 | 前几行的前后对照，让他确认「改的是不是我想的那些」 |

`problems[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `line` | `number` | 是 | — |
| `reason` | `string` | 是 | — |

`samples[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | — |
| `goods` | `string` | 是 | — |
| `spec` | `string` | 是 | — |
| `barcodeFrom` | `string,null` | 否 | — |
| `barcodeTo` | `string,null` | 否 | — |
| `codeFrom` | `string,null` | 否 | — |
| `codeTo` | `string,null` | 否 | — |
| `unitFrom` | `string,null` | 否 | — |
| `unitTo` | `string,null` | 否 | — |


### spec-dims

#### GET `/biz/spec-dims`

加规格组时能挑的维度（本类目已配 + 平台通用 + 自建）　🔒

**入参**：无

**出参**（`data`）

类型：[`SpecTemplate`](#spectemplate)\[\]


#### POST `/biz/spec-dims`

自建规格维度（只本店可用）　🔒

**入参**：无

**出参**（`data`）

类型：[`SpecTemplate`](#spectemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `scope` | [`SpecTemplateScope`](#spectemplatescope) | 是 | 模板归属：平台统一维护 or 商家自存。商家只能改自己的 |
| `categoryType` | [`CategoryType`](#categorytype) | 否 | 平台模板按品类推荐；商家模板不限品类 |
| `categoryNo` | `string` | 否 | 类目级模板的归属类目；**空 = 品类兜底**。 <p>端上靠它区分两层：类目级排在前面并标出来。不下发的话两批混在一起， 商家分不出哪个是「专门给这一类的」。 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`SpecOption`](#specoption)\[\] | 是 | 该维度的可选项 |
| `merchantNo` | `string` | 否 | scope=MERCHANT 时归属的商家 |
| `primary` | `boolean` | 否 | **主维度**：选完类目该自动建出来的就是这一组（每个类目至多一个，守卫测住）。 <p>不下发的话端上只能靠「数组第一个」猜 —— 后端确实那么排，但那是巧合而非契约： 排序一改端上跟着错，症状是「自动建出来的是包装不是重量」，没有一处会报错。 <p>商家自存模板与品类兜底模板恒为 false：主维度是**类目绑定**上的判据， 那两条路不经过绑定表。 |


#### GET `/biz/spec-dims/{dimNo}/values`

某个规格下平台有的全部档位（加档位的候选）　🔒

**入参**：无

**出参**（`data`）

类型：[`SpecOption`](#specoption)\[\]


### spec-override

#### POST `/biz/spec-override/{categoryNo}`

本店用哪几个规格、什么顺序、叫什么　🔒

**入参**：无

**出参**（`data`）

类型：[`SpecTemplate`](#spectemplate)\[\]


### spec-props

#### GET `/biz/spec-props`

这一类的商品参数（产地/保质期/材质，不分 SKU）　🔒

**入参**：无

**出参**（`data`）

类型：[`SpecTemplate`](#spectemplate)\[\]


### spec-templates

#### GET `/biz/spec-templates`

规格模板　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `categoryType` | query | `string` | 否 | 品类形态 |
| `categoryNo` | query | `string` | 否 | 类目单号 |

**出参**（`data`）

类型：[`SpecTemplate`](#spectemplate)\[\]


#### POST `/biz/spec-templates`

存为常用规格　🔒

**入参**

请求体：[`SaveSpecTemplateReq`](#savespectemplatereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 规格维度名，如「重量」 |
| `options` | `string`\[\] | 是 | 可选值列表。存成商家自己的模板（scope=MERCHANT），不影响平台模板 |

**出参**（`data`）

类型：[`SpecTemplate`](#spectemplate)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `scope` | [`SpecTemplateScope`](#spectemplatescope) | 是 | 模板归属：平台统一维护 or 商家自存。商家只能改自己的 |
| `categoryType` | [`CategoryType`](#categorytype) | 否 | 平台模板按品类推荐；商家模板不限品类 |
| `categoryNo` | `string` | 否 | 类目级模板的归属类目；**空 = 品类兜底**。 <p>端上靠它区分两层：类目级排在前面并标出来。不下发的话两批混在一起， 商家分不出哪个是「专门给这一类的」。 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`SpecOption`](#specoption)\[\] | 是 | 该维度的可选项 |
| `merchantNo` | `string` | 否 | scope=MERCHANT 时归属的商家 |
| `primary` | `boolean` | 否 | **主维度**：选完类目该自动建出来的就是这一组（每个类目至多一个，守卫测住）。 <p>不下发的话端上只能靠「数组第一个」猜 —— 后端确实那么排，但那是巧合而非契约： 排序一改端上跟着错，症状是「自动建出来的是包装不是重量」，没有一处会报错。 <p>商家自存模板与品类兜底模板恒为 false：主维度是**类目绑定**上的判据， 那两条路不经过绑定表。 |


### spec-values

#### POST `/biz/spec-values`

在平台维度下加一个自有规格值　🔒

**入参**：无

**出参**（`data`）

类型：[`SpecValueAdded`](#specvalueadded)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `valueNo` | `string` | 是 | — |
| `code` | `string` | 是 | — |
| `label` | `string` | 是 | — |


### spu-std

#### GET `/biz/spu-std`

标准品搜索（建品用）　🔒

**入参**：无

**出参**（`data`）

类型：[`SpuStd`](#spustd)\[\]


### staff

#### GET `/biz/staff`

员工列表　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantStaff`](#merchantstaff)\[\]


#### POST `/biz/staff`

加员工　🔒

**入参**

请求体：[`AddStaffReq`](#addstaffreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `loginPhone` | `string` | 是 | 员工手机号（11 位）。**它就是登录号** —— 员工用它 + 验证码进 B 端 |
| `displayName` | `string` | 否 | 备注名（如「小张」）。选填但强烈建议 —— 不填的话列表与审计里都只有一串脱敏尾号，三个人以后就分不清谁是谁。 |

**出参**（`data`）

类型：[`MerchantStaff`](#merchantstaff)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `mchAccountNo` | `string` | 是 | 商家账号号。**不叫 staffNo** —— 那个名字被平台运营占着，两者是不同的人 |
| `displayName` | `string` | 否 | 姓名（老板自己写的，如「小张」）。**认人靠它** —— 一列号码谁也分不清。为空时端上回落 `loginPhone`。 |
| `loginPhone` | `string` | 是 | 登录手机号，**完整、不脱敏**。 它**就是这个员工的登录用户名**（手机号 + 验证码，没有密码）—— 老板要能核对「他用哪个号登录」、人换号时要能改，脱敏之后这两件事都做不了。 |
| `isOwner` | `boolean` | 是 | 老板。**不受门店授权限制**，他的店都归他管 |
| `status` | [`StaffStatus`](#staffstatus) | 是 | ACTIVE / DISABLED |
| `roles` | [`StoreRole`](#storerole)\[\] | 是 | 他在各门店的角色。老板为空 —— 不是"没授权"，是"不需要授权" |


#### POST `/biz/staff/{mchAccountNo}/status`

停用/启用员工　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `mchAccountNo` | path | `string` | 是 | — |

请求体：[`SetActiveReq`](#setactivereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `active` | `boolean` | 是 | true 启用 / false 停用 |

**出参**（`data`）

类型：[`MerchantStaff`](#merchantstaff)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `mchAccountNo` | `string` | 是 | 商家账号号。**不叫 staffNo** —— 那个名字被平台运营占着，两者是不同的人 |
| `displayName` | `string` | 否 | 姓名（老板自己写的，如「小张」）。**认人靠它** —— 一列号码谁也分不清。为空时端上回落 `loginPhone`。 |
| `loginPhone` | `string` | 是 | 登录手机号，**完整、不脱敏**。 它**就是这个员工的登录用户名**（手机号 + 验证码，没有密码）—— 老板要能核对「他用哪个号登录」、人换号时要能改，脱敏之后这两件事都做不了。 |
| `isOwner` | `boolean` | 是 | 老板。**不受门店授权限制**，他的店都归他管 |
| `status` | [`StaffStatus`](#staffstatus) | 是 | ACTIVE / DISABLED |
| `roles` | [`StoreRole`](#storerole)\[\] | 是 | 他在各门店的角色。老板为空 —— 不是"没授权"，是"不需要授权" |


#### POST `/biz/staff/{mchAccountNo}/store`

授权到店　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `mchAccountNo` | path | `string` | 是 | — |

请求体：[`GrantStoreReq`](#grantstorereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 授权到哪家店。只能是本主体的门店 |
| `role` | [`StaffRole`](#staffrole) | 是 | 要授予/撤销的那一个角色 |
| `granted` | `boolean` | 否 | true 授予（默认）、false 撤销。撤到一个不剩 = 从这家店移除他 |

**出参**（`data`）

类型：[`MerchantStaff`](#merchantstaff)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `mchAccountNo` | `string` | 是 | 商家账号号。**不叫 staffNo** —— 那个名字被平台运营占着，两者是不同的人 |
| `displayName` | `string` | 否 | 姓名（老板自己写的，如「小张」）。**认人靠它** —— 一列号码谁也分不清。为空时端上回落 `loginPhone`。 |
| `loginPhone` | `string` | 是 | 登录手机号，**完整、不脱敏**。 它**就是这个员工的登录用户名**（手机号 + 验证码，没有密码）—— 老板要能核对「他用哪个号登录」、人换号时要能改，脱敏之后这两件事都做不了。 |
| `isOwner` | `boolean` | 是 | 老板。**不受门店授权限制**，他的店都归他管 |
| `status` | [`StaffStatus`](#staffstatus) | 是 | ACTIVE / DISABLED |
| `roles` | [`StoreRole`](#storerole)\[\] | 是 | 他在各门店的角色。老板为空 —— 不是"没授权"，是"不需要授权" |


#### GET `/biz/staff/logs`

员工与授权变更记录　🔒

**入参**：无

**出参**（`data`）

类型：[`StaffLog`](#stafflog)\[\]


### store

#### GET `/biz/store`

店铺门面　🔒

**入参**：无

**出参**（`data`）

类型：[`StoreProfile`](#storeprofile)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `announcement` | `string` | 是 | 店铺公告：「今日到货」「今天有土鸡蛋」，店主自发（C-ST-04） |
| `announcementUntil` | `number,null` | 否 | 公告失效时刻（epoch 毫秒）。**空 = 长期有效**。 过期由服务端读时判断，端上拿到的 `announcement` 已经是「此刻该显示的」—— 端上不要自己再判一次：两处判断迟早会不一致，而不一致的表现是 「商家看是空的、买家看到的是昨天的货」。 |
| `announcementRecent` | `string`\[\] | 否 | 最近用过的公告，最多 5 条，按最近使用排序。服务端维护，端上只读 |
| `noticePending` | `object`（见下） \| `null` | 否 | 正卡在人审里的那条公告（机审命中转的），没有就是 null。 **必须读它**：命中期间后端保留旧公告并返回旧资料 —— 端上不看这个字段的话， 会照旧提示「已发布」，而输入框还原成上一条，商家只会以为自己手滑， 反复再发一次，队列里堆出一串同样的单子。 |
| `openHours` | `string` | 是 | 营业时间文案，店主自填 |
| `address` | `string` | 是 | 店铺地址。**来自地图选点**（省市区 + 小区/路名），店主可改但一般不用改。 与  {@link  addressDetail }  分开：重新选点只覆盖这一条。 |
| `addressDetail` | `string` | 否 | 门牌号 / 楼栋（「3 栋 2 单元 501」），店主手填。 为什么单独一格：地图给的地址只到小区门口，而买家照着找门缺的正是这一截； 合成一格的话，商家补完再点一次选点就被整条覆盖 —— 补的那截无声消失， 地址看着还是对的，只是又回到了小区门口。 |
| `featured` | `string`\[\] | 是 | 主推商品，按顺序展示在门店主页首屏 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围（B 端自选）。**决定这家店的货在 C 端能被谁看到** —— 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款）， 选小了则整片小区的人都搜不到这家店。所以 B 端要给出后果说明，不能只给三个单选。 |
| `serviceCommunityNos` | `string`\[\] | 是 | scope=COMMUNITY 时覆盖的社区。空表示还没谈下任何小区，此时 C 端一律不可见 |
| `serviceCityCode` | `string` | 否 | scope=CITY 时覆盖的城市 |
| `fulfillmentReach` | [`FulfillmentReach`](#fulfillmentreach) | 否 | 履约能力（ADR-013 阶段二）。**只说「怎么送到你手上」**，送得到哪儿看  {@link  serviceAreas } 。 与上面两个 `@deprecated` 字段的关系：新旧两套并存期间，端上**只传一套** —— 传了 `serviceAreas` 就走新模型，后端不再看 `serviceScope`。 |
| `serviceAreas` | [`ServiceArea`](#servicearea)\[\] | 否 | 地理覆盖项，可跨粒度组合（三个小区 + 一个区）。 **空的含义由 `fulfillmentReach` 决定**，这是这个字段最容易踩的地方： PICKUP 空 = 谁也看不到（没配自提点就没法履约）； ONSITE / SHIPPING 空 = 不限。同一个空数组两种意思，所以别拿它判「有没有设置过」。 |
| `latE6` | `number,null` | 否 | 门店坐标（gcj02，E6）。地图选点回填；买家侧「门店自取」导航与候选取货点排距离靠它。 不传 = 这次不改；老版本端上不知道这个字段，后端不能把缺省当成清空。 |
| `lngE6` | `number,null` | 否 | — |


#### POST `/biz/store`

保存店铺门面　🔒

**入参**

请求体：[`SaveStoreReqBody`](#savestorereqbody)

_无字段_

**出参**（`data`）

类型：[`StoreProfile`](#storeprofile)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `announcement` | `string` | 是 | 店铺公告：「今日到货」「今天有土鸡蛋」，店主自发（C-ST-04） |
| `announcementUntil` | `number,null` | 否 | 公告失效时刻（epoch 毫秒）。**空 = 长期有效**。 过期由服务端读时判断，端上拿到的 `announcement` 已经是「此刻该显示的」—— 端上不要自己再判一次：两处判断迟早会不一致，而不一致的表现是 「商家看是空的、买家看到的是昨天的货」。 |
| `announcementRecent` | `string`\[\] | 否 | 最近用过的公告，最多 5 条，按最近使用排序。服务端维护，端上只读 |
| `noticePending` | `object`（见下） \| `null` | 否 | 正卡在人审里的那条公告（机审命中转的），没有就是 null。 **必须读它**：命中期间后端保留旧公告并返回旧资料 —— 端上不看这个字段的话， 会照旧提示「已发布」，而输入框还原成上一条，商家只会以为自己手滑， 反复再发一次，队列里堆出一串同样的单子。 |
| `openHours` | `string` | 是 | 营业时间文案，店主自填 |
| `address` | `string` | 是 | 店铺地址。**来自地图选点**（省市区 + 小区/路名），店主可改但一般不用改。 与  {@link  addressDetail }  分开：重新选点只覆盖这一条。 |
| `addressDetail` | `string` | 否 | 门牌号 / 楼栋（「3 栋 2 单元 501」），店主手填。 为什么单独一格：地图给的地址只到小区门口，而买家照着找门缺的正是这一截； 合成一格的话，商家补完再点一次选点就被整条覆盖 —— 补的那截无声消失， 地址看着还是对的，只是又回到了小区门口。 |
| `featured` | `string`\[\] | 是 | 主推商品，按顺序展示在门店主页首屏 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围（B 端自选）。**决定这家店的货在 C 端能被谁看到** —— 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款）， 选小了则整片小区的人都搜不到这家店。所以 B 端要给出后果说明，不能只给三个单选。 |
| `serviceCommunityNos` | `string`\[\] | 是 | scope=COMMUNITY 时覆盖的社区。空表示还没谈下任何小区，此时 C 端一律不可见 |
| `serviceCityCode` | `string` | 否 | scope=CITY 时覆盖的城市 |
| `fulfillmentReach` | [`FulfillmentReach`](#fulfillmentreach) | 否 | 履约能力（ADR-013 阶段二）。**只说「怎么送到你手上」**，送得到哪儿看  {@link  serviceAreas } 。 与上面两个 `@deprecated` 字段的关系：新旧两套并存期间，端上**只传一套** —— 传了 `serviceAreas` 就走新模型，后端不再看 `serviceScope`。 |
| `serviceAreas` | [`ServiceArea`](#servicearea)\[\] | 否 | 地理覆盖项，可跨粒度组合（三个小区 + 一个区）。 **空的含义由 `fulfillmentReach` 决定**，这是这个字段最容易踩的地方： PICKUP 空 = 谁也看不到（没配自提点就没法履约）； ONSITE / SHIPPING 空 = 不限。同一个空数组两种意思，所以别拿它判「有没有设置过」。 |
| `latE6` | `number,null` | 否 | 门店坐标（gcj02，E6）。地图选点回填；买家侧「门店自取」导航与候选取货点排距离靠它。 不传 = 这次不改；老版本端上不知道这个字段，后端不能把缺省当成清空。 |
| `lngE6` | `number,null` | 否 | — |


#### GET `/biz/store/{storeNo}/categories`

本店经营类目　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`StoreCategory`](#storecategory)\[\]


#### POST `/biz/store/{storeNo}/categories`

整份替换本店经营类目　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`StoreCategory`](#storecategory)\[\]


#### POST `/biz/store/{storeNo}/default`

设为默认店　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`Store`](#store)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号。一旦生成不再变 —— 换主体只换归属，不换它 |
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 否 | 门店地址。顾客据此找到取货点，也是履约范围的锚点 |
| `isDefault` | `boolean` | 是 | 是否默认店。一个主体**恰好一家** —— 它是「找不到具体门店时去哪」的答案 |
| `status` | [`StoreStatus`](#storestatus) | 是 | ACTIVE 正常营业 / READONLY 已停用（不再接新单，已有单照常履约） |
| `payMerchantNo` | `string` | 否 | 这家店用哪个收款号。**空 = 用主体的默认收款号**，不是"没配" |
| `payReady` | `boolean` | 是 | 这家店现在能不能收钱。照它显示，别自己去比状态串 |
| `staffCount` | `number` | 是 | 授权到这家店的员工数（不含老板）。0 表示只有老板能管这家店 |
| `rating` | `number` | 否 | 门店评分 ×10（V155）。与主体评分是两个数：主体分是各店的合成，反过来推不回去 |
| `ratingCount` | `number` | 否 | 计入门店评分的条数。**0 = 暂无评价**，不是 0 分 |
| `planSuspended` | `boolean` | 否 | 这家店的只读**是套餐降级压下来的**，不是店主自己停的。 <p>两者的 `status` 一模一样（都是 `READONLY`），而端上要给的下一步完全不同： 降级压的要**补缴/升档**，自己停的**点一下启用就开**。 不分开的表现是店主反复点那个对降级店无效的启用按钮。 |


#### POST `/biz/store/{storeNo}/payment`

换门店收款号　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

请求体：[`SetStorePaymentReq`](#setstorepaymentreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payMerchantNo` | `string` | 否 | 目标收款商户号。只能是本主体已开通的号；空 = 回到主体默认号 |

**出参**（`data`）

类型：[`Store`](#store)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号。一旦生成不再变 —— 换主体只换归属，不换它 |
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 否 | 门店地址。顾客据此找到取货点，也是履约范围的锚点 |
| `isDefault` | `boolean` | 是 | 是否默认店。一个主体**恰好一家** —— 它是「找不到具体门店时去哪」的答案 |
| `status` | [`StoreStatus`](#storestatus) | 是 | ACTIVE 正常营业 / READONLY 已停用（不再接新单，已有单照常履约） |
| `payMerchantNo` | `string` | 否 | 这家店用哪个收款号。**空 = 用主体的默认收款号**，不是"没配" |
| `payReady` | `boolean` | 是 | 这家店现在能不能收钱。照它显示，别自己去比状态串 |
| `staffCount` | `number` | 是 | 授权到这家店的员工数（不含老板）。0 表示只有老板能管这家店 |
| `rating` | `number` | 否 | 门店评分 ×10（V155）。与主体评分是两个数：主体分是各店的合成，反过来推不回去 |
| `ratingCount` | `number` | 否 | 计入门店评分的条数。**0 = 暂无评价**，不是 0 分 |
| `planSuspended` | `boolean` | 否 | 这家店的只读**是套餐降级压下来的**，不是店主自己停的。 <p>两者的 `status` 一模一样（都是 `READONLY`），而端上要给的下一步完全不同： 降级压的要**补缴/升档**，自己停的**点一下启用就开**。 不分开的表现是店主反复点那个对降级店无效的启用按钮。 |


#### POST `/biz/store/{storeNo}/rename`

改门店名与地址　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

请求体：[`StoreEditReq`](#storeeditreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 否 | 门店地址 |
| `categoryNos` | `string`\[\] | 否 | 这家店摆哪些货架（**只有新建时有意义**，改名时后端忽略）。 <p><b>不传 = 复制默认店的</b>：多门店商家开分店卖的多半是同一批货， 从零勾选是纯负担。一个都没有也合法 —— 建品时会自动加入。 |
| `entityNo` | `string` | 否 | 这家店挂在哪张证照下（多证照）。 <p><b>不传 = 当前证照</b>，与单证照时代一模一样 —— 只有一张证照的账号 端上整个不渲染这一步。传了别人的证照号后端直接 403，不会静默落到当前这张。 <p>注意**额度按证照算**：挂到另一张证照下时撞的是那张的门店额度， 而不是当前这张的。这是应该的，额度是那张证照买的。 |

**出参**（`data`）

类型：[`Store`](#store)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号。一旦生成不再变 —— 换主体只换归属，不换它 |
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 否 | 门店地址。顾客据此找到取货点，也是履约范围的锚点 |
| `isDefault` | `boolean` | 是 | 是否默认店。一个主体**恰好一家** —— 它是「找不到具体门店时去哪」的答案 |
| `status` | [`StoreStatus`](#storestatus) | 是 | ACTIVE 正常营业 / READONLY 已停用（不再接新单，已有单照常履约） |
| `payMerchantNo` | `string` | 否 | 这家店用哪个收款号。**空 = 用主体的默认收款号**，不是"没配" |
| `payReady` | `boolean` | 是 | 这家店现在能不能收钱。照它显示，别自己去比状态串 |
| `staffCount` | `number` | 是 | 授权到这家店的员工数（不含老板）。0 表示只有老板能管这家店 |
| `rating` | `number` | 否 | 门店评分 ×10（V155）。与主体评分是两个数：主体分是各店的合成，反过来推不回去 |
| `ratingCount` | `number` | 否 | 计入门店评分的条数。**0 = 暂无评价**，不是 0 分 |
| `planSuspended` | `boolean` | 否 | 这家店的只读**是套餐降级压下来的**，不是店主自己停的。 <p>两者的 `status` 一模一样（都是 `READONLY`），而端上要给的下一步完全不同： 降级压的要**补缴/升档**，自己停的**点一下启用就开**。 不分开的表现是店主反复点那个对降级店无效的启用按钮。 |


#### POST `/biz/store/{storeNo}/status`

停用/启用门店　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

请求体：[`SetActiveReq`](#setactivereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `active` | `boolean` | 是 | true 启用 / false 停用 |

**出参**（`data`）

类型：[`Store`](#store)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号。一旦生成不再变 —— 换主体只换归属，不换它 |
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 否 | 门店地址。顾客据此找到取货点，也是履约范围的锚点 |
| `isDefault` | `boolean` | 是 | 是否默认店。一个主体**恰好一家** —— 它是「找不到具体门店时去哪」的答案 |
| `status` | [`StoreStatus`](#storestatus) | 是 | ACTIVE 正常营业 / READONLY 已停用（不再接新单，已有单照常履约） |
| `payMerchantNo` | `string` | 否 | 这家店用哪个收款号。**空 = 用主体的默认收款号**，不是"没配" |
| `payReady` | `boolean` | 是 | 这家店现在能不能收钱。照它显示，别自己去比状态串 |
| `staffCount` | `number` | 是 | 授权到这家店的员工数（不含老板）。0 表示只有老板能管这家店 |
| `rating` | `number` | 否 | 门店评分 ×10（V155）。与主体评分是两个数：主体分是各店的合成，反过来推不回去 |
| `ratingCount` | `number` | 否 | 计入门店评分的条数。**0 = 暂无评价**，不是 0 分 |
| `planSuspended` | `boolean` | 否 | 这家店的只读**是套餐降级压下来的**，不是店主自己停的。 <p>两者的 `status` 一模一样（都是 `READONLY`），而端上要给的下一步完全不同： 降级压的要**补缴/升档**，自己停的**点一下启用就开**。 不分开的表现是店主反复点那个对降级店无效的启用按钮。 |


#### POST `/biz/store/announcement`

只改公告（含有效期，可同时发到别的门店）　🔒

**入参**：无

**出参**（`data`）

类型：[`StoreProfile`](#storeprofile)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `announcement` | `string` | 是 | 店铺公告：「今日到货」「今天有土鸡蛋」，店主自发（C-ST-04） |
| `announcementUntil` | `number,null` | 否 | 公告失效时刻（epoch 毫秒）。**空 = 长期有效**。 过期由服务端读时判断，端上拿到的 `announcement` 已经是「此刻该显示的」—— 端上不要自己再判一次：两处判断迟早会不一致，而不一致的表现是 「商家看是空的、买家看到的是昨天的货」。 |
| `announcementRecent` | `string`\[\] | 否 | 最近用过的公告，最多 5 条，按最近使用排序。服务端维护，端上只读 |
| `noticePending` | `object`（见下） \| `null` | 否 | 正卡在人审里的那条公告（机审命中转的），没有就是 null。 **必须读它**：命中期间后端保留旧公告并返回旧资料 —— 端上不看这个字段的话， 会照旧提示「已发布」，而输入框还原成上一条，商家只会以为自己手滑， 反复再发一次，队列里堆出一串同样的单子。 |
| `openHours` | `string` | 是 | 营业时间文案，店主自填 |
| `address` | `string` | 是 | 店铺地址。**来自地图选点**（省市区 + 小区/路名），店主可改但一般不用改。 与  {@link  addressDetail }  分开：重新选点只覆盖这一条。 |
| `addressDetail` | `string` | 否 | 门牌号 / 楼栋（「3 栋 2 单元 501」），店主手填。 为什么单独一格：地图给的地址只到小区门口，而买家照着找门缺的正是这一截； 合成一格的话，商家补完再点一次选点就被整条覆盖 —— 补的那截无声消失， 地址看着还是对的，只是又回到了小区门口。 |
| `featured` | `string`\[\] | 是 | 主推商品，按顺序展示在门店主页首屏 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围（B 端自选）。**决定这家店的货在 C 端能被谁看到** —— 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款）， 选小了则整片小区的人都搜不到这家店。所以 B 端要给出后果说明，不能只给三个单选。 |
| `serviceCommunityNos` | `string`\[\] | 是 | scope=COMMUNITY 时覆盖的社区。空表示还没谈下任何小区，此时 C 端一律不可见 |
| `serviceCityCode` | `string` | 否 | scope=CITY 时覆盖的城市 |
| `fulfillmentReach` | [`FulfillmentReach`](#fulfillmentreach) | 否 | 履约能力（ADR-013 阶段二）。**只说「怎么送到你手上」**，送得到哪儿看  {@link  serviceAreas } 。 与上面两个 `@deprecated` 字段的关系：新旧两套并存期间，端上**只传一套** —— 传了 `serviceAreas` 就走新模型，后端不再看 `serviceScope`。 |
| `serviceAreas` | [`ServiceArea`](#servicearea)\[\] | 否 | 地理覆盖项，可跨粒度组合（三个小区 + 一个区）。 **空的含义由 `fulfillmentReach` 决定**，这是这个字段最容易踩的地方： PICKUP 空 = 谁也看不到（没配自提点就没法履约）； ONSITE / SHIPPING 空 = 不限。同一个空数组两种意思，所以别拿它判「有没有设置过」。 |
| `latE6` | `number,null` | 否 | 门店坐标（gcj02，E6）。地图选点回填；买家侧「门店自取」导航与候选取货点排距离靠它。 不传 = 这次不改；老版本端上不知道这个字段，后端不能把缺省当成清空。 |
| `lngE6` | `number,null` | 否 | — |


#### POST `/biz/store/announcement/recent/remove`

从常用里删一条　🔒

**入参**：无

**出参**（`data`）

类型：[`StoreProfile`](#storeprofile)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `announcement` | `string` | 是 | 店铺公告：「今日到货」「今天有土鸡蛋」，店主自发（C-ST-04） |
| `announcementUntil` | `number,null` | 否 | 公告失效时刻（epoch 毫秒）。**空 = 长期有效**。 过期由服务端读时判断，端上拿到的 `announcement` 已经是「此刻该显示的」—— 端上不要自己再判一次：两处判断迟早会不一致，而不一致的表现是 「商家看是空的、买家看到的是昨天的货」。 |
| `announcementRecent` | `string`\[\] | 否 | 最近用过的公告，最多 5 条，按最近使用排序。服务端维护，端上只读 |
| `noticePending` | `object`（见下） \| `null` | 否 | 正卡在人审里的那条公告（机审命中转的），没有就是 null。 **必须读它**：命中期间后端保留旧公告并返回旧资料 —— 端上不看这个字段的话， 会照旧提示「已发布」，而输入框还原成上一条，商家只会以为自己手滑， 反复再发一次，队列里堆出一串同样的单子。 |
| `openHours` | `string` | 是 | 营业时间文案，店主自填 |
| `address` | `string` | 是 | 店铺地址。**来自地图选点**（省市区 + 小区/路名），店主可改但一般不用改。 与  {@link  addressDetail }  分开：重新选点只覆盖这一条。 |
| `addressDetail` | `string` | 否 | 门牌号 / 楼栋（「3 栋 2 单元 501」），店主手填。 为什么单独一格：地图给的地址只到小区门口，而买家照着找门缺的正是这一截； 合成一格的话，商家补完再点一次选点就被整条覆盖 —— 补的那截无声消失， 地址看着还是对的，只是又回到了小区门口。 |
| `featured` | `string`\[\] | 是 | 主推商品，按顺序展示在门店主页首屏 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围（B 端自选）。**决定这家店的货在 C 端能被谁看到** —— 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款）， 选小了则整片小区的人都搜不到这家店。所以 B 端要给出后果说明，不能只给三个单选。 |
| `serviceCommunityNos` | `string`\[\] | 是 | scope=COMMUNITY 时覆盖的社区。空表示还没谈下任何小区，此时 C 端一律不可见 |
| `serviceCityCode` | `string` | 否 | scope=CITY 时覆盖的城市 |
| `fulfillmentReach` | [`FulfillmentReach`](#fulfillmentreach) | 否 | 履约能力（ADR-013 阶段二）。**只说「怎么送到你手上」**，送得到哪儿看  {@link  serviceAreas } 。 与上面两个 `@deprecated` 字段的关系：新旧两套并存期间，端上**只传一套** —— 传了 `serviceAreas` 就走新模型，后端不再看 `serviceScope`。 |
| `serviceAreas` | [`ServiceArea`](#servicearea)\[\] | 否 | 地理覆盖项，可跨粒度组合（三个小区 + 一个区）。 **空的含义由 `fulfillmentReach` 决定**，这是这个字段最容易踩的地方： PICKUP 空 = 谁也看不到（没配自提点就没法履约）； ONSITE / SHIPPING 空 = 不限。同一个空数组两种意思，所以别拿它判「有没有设置过」。 |
| `latE6` | `number,null` | 否 | 门店坐标（gcj02，E6）。地图选点回填；买家侧「门店自取」导航与候选取货点排距离靠它。 不传 = 这次不改；老版本端上不知道这个字段，后端不能把缺省当成清空。 |
| `lngE6` | `number,null` | 否 | — |


#### POST `/biz/store/create`

新建门店　🔒

**入参**

请求体：[`StoreEditReq`](#storeeditreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 否 | 门店地址 |
| `categoryNos` | `string`\[\] | 否 | 这家店摆哪些货架（**只有新建时有意义**，改名时后端忽略）。 <p><b>不传 = 复制默认店的</b>：多门店商家开分店卖的多半是同一批货， 从零勾选是纯负担。一个都没有也合法 —— 建品时会自动加入。 |
| `entityNo` | `string` | 否 | 这家店挂在哪张证照下（多证照）。 <p><b>不传 = 当前证照</b>，与单证照时代一模一样 —— 只有一张证照的账号 端上整个不渲染这一步。传了别人的证照号后端直接 403，不会静默落到当前这张。 <p>注意**额度按证照算**：挂到另一张证照下时撞的是那张的门店额度， 而不是当前这张的。这是应该的，额度是那张证照买的。 |

**出参**（`data`）

类型：[`Store`](#store)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号。一旦生成不再变 —— 换主体只换归属，不换它 |
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 否 | 门店地址。顾客据此找到取货点，也是履约范围的锚点 |
| `isDefault` | `boolean` | 是 | 是否默认店。一个主体**恰好一家** —— 它是「找不到具体门店时去哪」的答案 |
| `status` | [`StoreStatus`](#storestatus) | 是 | ACTIVE 正常营业 / READONLY 已停用（不再接新单，已有单照常履约） |
| `payMerchantNo` | `string` | 否 | 这家店用哪个收款号。**空 = 用主体的默认收款号**，不是"没配" |
| `payReady` | `boolean` | 是 | 这家店现在能不能收钱。照它显示，别自己去比状态串 |
| `staffCount` | `number` | 是 | 授权到这家店的员工数（不含老板）。0 表示只有老板能管这家店 |
| `rating` | `number` | 否 | 门店评分 ×10（V155）。与主体评分是两个数：主体分是各店的合成，反过来推不回去 |
| `ratingCount` | `number` | 否 | 计入门店评分的条数。**0 = 暂无评价**，不是 0 分 |
| `planSuspended` | `boolean` | 否 | 这家店的只读**是套餐降级压下来的**，不是店主自己停的。 <p>两者的 `status` 一模一样（都是 `READONLY`），而端上要给的下一步完全不同： 降级压的要**补缴/升档**，自己停的**点一下启用就开**。 不分开的表现是店主反复点那个对降级店无效的启用按钮。 |


#### GET `/biz/store/list`

我的门店　🔒

**入参**：无

**出参**（`data`）

类型：[`Store`](#store)\[\]


#### GET `/biz/store/poster`

分享海报　🔒

**入参**：无

**出参**（`data`）

类型：[`Poster`](#poster)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `imageBase64` | `string,null` | 是 | PNG 的 base64（不含 data: 前缀）。生不出来（商家异常）时为 null |


#### GET `/biz/store/qrcode`

店铺码　🔒

**入参**：无

**出参**（`data`）

类型：[`StoreQrcode`](#storeqrcode)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 否 | 商家单号 |
| `storeCode` | `string` | 否 | 印在贴纸上的短码。**去掉了 0/O/1/I/L**，让人手输时不会认错 |
| `url` | `string,null` | 否 | 落地页链接。**未配对外域名时为 null** —— 端上据此不显示链接那一行。 ⚠️ 此前后端在两处各写死一个 `https://shop.example.com/s/<code>` 占位域名， 商家复制出去的链接与印出去的贴纸**全都指向一个不存在的地方**， 而这两个功能点在清单上标着「已实现」。不发假链接比发一个点不开的强。 |
| `imageBase64` | `string,null` | 否 | 店铺**小程序码**的 PNG base64（不含 `data:` 前缀）。通道未开启时为 null。 用小程序码而不是 H5 链接：ADR-004 的主获客路径是「码印在包装袋上，老客扫码直达」， 而小程序码**不依赖备案域名**（备案要 7–20 个工作日），扫了直接进门店页。 |
| `printableHint` | `string` | 否 | 打印建议，服务端给的一句话 |


#### GET `/biz/store/share-kit`

分享素材　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | query | `string` | 否 | 商品单号 |

**出参**（`data`）

类型：[`ShareKit`](#sharekit)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `text` | `string` | 是 | 分享文案，已按当前语言与市场生成 |
| `posterUrl` | `string` | 是 | 落地页链接，文案里已经拼过一次；未配对外域名时为空串。真正的海报图走  {@link  Poster } |


### store-spec-dims

#### GET `/biz/store-spec-dims`

本店货架类目各自能用的规格　🔒

**入参**：无

**出参**（`data`）

类型：[`StoreCategorySpecs`](#storecategoryspecs)\[\]


### stores

#### GET `/biz/stores/{storeNo}/appointment-slots`

预约时段列表　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`AppointmentSlot`](#appointmentslot)\[\]


#### POST `/biz/stores/{storeNo}/appointment-slots`

开预约时段　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`AppointmentSlot`](#appointmentslot)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `slotNo` | `string` | 是 | — |
| `storeNo` | `string` | 是 | — |
| `startAt` | `number` | 是 | — |
| `endAt` | `number` | 是 | — |
| `capacity` | `number` | 是 | — |
| `booked` | `number` | 是 | — |
| `remaining` | `number` | 是 | — |
| `status` | `OPEN` \| `CLOSED` | 是 | OPEN 可约 / CLOSED 停约。停约**不删行也不赶人** |


#### GET `/biz/stores/{storeNo}/fulfillment`

门店送货方式　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`StoreFulfillment`](#storefulfillment)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | — |
| `channels` | [`StoreFulfillmentChannel`](#storefulfillmentchannel)\[\] | 是 | 固定四行，顺序即开关顺序 —— 服务端补缺，端上不用自己造 |


#### PUT `/biz/stores/{storeNo}/fulfillment`

保存门店送货方式　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`StoreFulfillment`](#storefulfillment)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | — |
| `channels` | [`StoreFulfillmentChannel`](#storefulfillmentchannel)\[\] | 是 | 固定四行，顺序即开关顺序 —— 服务端补缺，端上不用自己造 |


#### GET `/biz/stores/{storeNo}/fulfillment/{channel}/impact`

关掉这一路会影响的在售商品　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `storeNo` | path | `string` | 是 | — |
| `channel` | path | `string` | 是 | — |

**出参**（`data`）

类型：[`FulfillmentImpactItem`](#fulfillmentimpactitem)\[\]


#### GET `/biz/stores/mine`

我能进的所有门店（按证照分组）　🔒

**入参**：无

**出参**（`data`）

类型：[`EntityStores`](#entitystores)\[\]


### upload

#### POST `/biz/upload/image`

上传商品图　🔒

**入参**

请求体：[`UploadImageReq`](#uploadimagereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `tempPath` | `string` | 是 | 端上的临时文件路径。真实实现走 multipart，这里是 mock 与 H5 的折中 |

**出参**（`data`）

类型：`object`


---

## 数据模型

### ActivityConflict

冲突提示：这件商品已经在另一个还在跑的活动里

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | — |
| `activityNo` | `string` | 是 | — |
| `activityName` | `string` | 是 | — |
| `benefitType` | `string` | 是 | — |

### AddStaffReq

加员工。只要手机号 —— 不发密码、不建 C 端账号

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `loginPhone` | `string` | 是 | 员工手机号（11 位）。**它就是登录号** —— 员工用它 + 验证码进 B 端 |
| `displayName` | `string` | 否 | 备注名（如「小张」）。选填但强烈建议 —— 不填的话列表与审计里都只有一串脱敏尾号，三个人以后就分不清谁是谁。 |

### AfterSale

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `afterSaleNo` | `string` | 是 | 售后单号。**售后是独立资源，不是订单上的一个字段** —— 它有自己的生命周期（申请→同意/驳回→寄回→收货→退款），能被取消、能上升平台， 一个订单还可能先后发起多次。挂在订单下用 orderNo 寻址，第二次申请就没法表达了。 后端一开始就是这么建的（/mp/after-sale/{afterSaleNo}/**），这里向它对齐。 |
| `subOrderNo` | `string` | 是 | 所属**子订单**号（`SUB…`）。 ⚠️ **要关联回订单卡片用的是这个，不是下面的 `orderNo`。** C/B 两端列表里的一行是一张子订单，而 `Order.orderNo` 字段里装的就是子订单号 （后端 `OrderVO.orderNo` = `SUB…`）；售后单上的 `orderNo` 却是**主单号**（`SO…`）。 两个字段同名不同物 —— 按 `orderNo` 去 join 一条也匹配不上， 而症状是「售后页签空着」，与它本来要修的 bug 一模一样。 |
| `orderNo` | `string` | 是 | 所属**主订单**号（`SO…`）。跨商家下单会拆成多笔子订单，它们共用这一个主单号。 展示「同一次下单」时用它，关联单张订单卡片请用  {@link  subOrderNo } 。 |
| `type` | [`AfterSaleType`](#aftersaletype) | 是 | 售后类型：仅退款 / 退货退款 |
| `status` | [`AfterSaleStatus`](#aftersalestatus) | 是 | 售后单状态，独立于订单状态流转 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `images` | `string`\[\] | 是 | 举证图（破损、少件的照片）。是否必填由售后类型决定 |
| `refundMinor` | `number` | 是 | 这张售后单要退的钱（分）。**不等于订单金额** —— 一张子订单可以只退其中一件，也可以先后发起多次。 <p>后端一直在发（`AfterSaleVO.refundMinor`），只是契约里漏了声明， 于是 B 端售后页拿不到它，只能退而求其次显示**整张子订单的应付**。 单件单品的单子上两个数恰好相等，所以这个错在联调环境里看不出来 —— 直到有人退三件里的一件。 |
| `instant` | `boolean` | 否 | 极速退：金额在阈值内的仅退款，系统自动通过。 **商家只可见不可拒**，所以这类单上不该出现同意/驳回按钮。 |
| `merchantReply` | `string` | 否 | 商家同意/驳回时的说明 |
| `returnExpressNo` | `string` | 否 | 用户寄回的运单号（RETURN_REFUND） |
| `disputeReason` | `string` | 否 | 上升平台时用户的申诉理由 |
| `updatedAt` | `number` | 是 | 最后一次状态变更时间。超时自动同意等时效规则以它为基准 |
| `createdAt` | `number` | 否 | 申请时间 |
| `liability` | `string` | 否 | 责任方，平台裁决后才有（口径未定） |
| `timeline` | `object`（见下）\[\] | 否 | 售后自己的时间线（申请 → 同意 → 寄回 → 退款），与订单时间线分开 |

`timeline[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `status` | `string` | 是 | — |
| `label` | `string` | 是 | — |
| `at` | `number` | 是 | — |

### AfterSaleStatus

售后单状态。**这是后端 `OrdAfterSale` 真实存的取值。** ⚠️ 这里此前是完全另一套：`PENDING`/`AGREED`/`RETURNING`/`RECEIVED`/`DONE`/`DISPUTED`， 与后端**只有 `REJECTED` 一个词重合**。c/b 两端按它判断、按它建 i18n 词条， 于是售后详情页的状态永远落进兜底分支，「填退货单号」按钮永远不出现 （它 gate 在一个后端永远不会下发的 `AGREED` 上）。 那一套描述的是**想象中更细的流程**：同意 → 寄回 → 收货 → 退款四步。 后端没有把「寄回中」「已收货」做成独立状态 —— 商家一同意就进 `REFUNDING`， 退货物流走 `expressNo` 字段而不是状态。粒度差异是真实的设计选择， 端上不能自己补一套更细的词然后假装后端会给。

枚举取值：

- `APPLIED`
- `REFUNDING`
- `REFUNDED`
- `REJECTED`
- `ARBITRATING`
- `CLOSED`

### AfterSaleType

售后类型。**仅退款与退货退款的流程根本不同** —— 仅退款同意即退；退货退款必须**先收到货再退款**，否则「退款了货没回来」。 此前两者走同一条路，是售后闭环缺的后半段（B-7.3）。

枚举取值：

- `REFUND_ONLY`
- `RETURN_REFUND`

### AppealReviewReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reason` | `string` | 是 | 申诉理由。这是**唯一**能把差评送进平台裁决台的入口 |
| `images` | `string`\[\] | 否 | 举证图：聊天记录、物流截图 |

### AppointmentDaySlots

预约时段的**按天展示分组**（SERVICE + APPOINTMENT）。 ⚠️ <b>与  {@link  AppointmentSlot }  不是一回事</b>，别混：   · 这个是「一天 × 若干时间点」的**展示结构**，给选择器分组用   · 那个是排期的**一行**（有 slotNo，下单占的就是它） 它此前就叫 AppointmentSlot，而唯一的使用处是 `GoodsVO.slots?` —— 一个注释里明写着「后端从不下发」的幽灵字段。真排期落地时把名字让了出来。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `date` | `string` | 是 | YYYY-MM-DD（市场本地时区） |
| `times` | `object`（见下）\[\] | 是 | 当天各时段的余量。`time` 形如 `14:00`，`left` 为剩余可约数，0 表示约满 |

`times[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `time` | `string` | 是 | — |
| `left` | `number` | 是 | — |

### AppointmentSlot

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `slotNo` | `string` | 是 | — |
| `storeNo` | `string` | 是 | — |
| `startAt` | `number` | 是 | — |
| `endAt` | `number` | 是 | — |
| `capacity` | `number` | 是 | — |
| `booked` | `number` | 是 | — |
| `remaining` | `number` | 是 | — |
| `status` | `OPEN` \| `CLOSED` | 是 | OPEN 可约 / CLOSED 停约。停约**不删行也不赶人** |

### AreaLevel

枚举取值：

- `COMMUNITY`
- `STREET`
- `DISTRICT`
- `CITY`
- `PROVINCE`

### AreaStatus

枚举取值：

- `ACTIVE`
- `PENDING`

### ArrivalIssueKind

到货异常类型：缺件 / 破损。B 端到货登记时上报（ADR-005 履约链路）

枚举取值：

- `SHORTAGE`
- `DAMAGE`

### AuthCodeInfo

门槛码字典的一条：这个码要哪一类证、对应哪些类目。 `categoryNames` 由**应用层**拼（商家域不读商品域的类目，见 `CategoryUsagePort` 的说明）—— 商家看的是「食品经营许可证能解锁：肉禽蛋、乳制品、熟食卤味」， 而不是三个码。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `requiredQualification` | `string,null` | 否 | 给人读的一句话，如「食品经营许可证」。空 = 这一类不需要证 |
| `qualType` | [`QualificationType`](#qualificationtype) \| `null` | 否 | 机器判的类型，与  {@link  QualificationType }  同值域。空 = 无需证件 |
| `categoryNames` | `string`\[\] | 是 | — |

### BizScope

我在**当前门店**能做什么（`GET /biz/context`）。B 端每次会话恢复与切门店后都要重取。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 当前用户所属的商家主体 |
| `currentStoreNo` | `string` | 是 | 当前选中的门店。 **切门店后要重新拉这个接口** —— 角色跟着门店走：同一个人可能在 A 店是店长、 B 店是店员，权限跟着变。不重拉的话，界面按上一家店的权限渲染。 |
| `owner` | `boolean` | 是 | 是不是老板（主体所有者）。老板不受门店角色限制 |
| `storeNos` | `string`\[\] | 是 | 我能管哪些门店。空 = 只能看当前这家 |
| `pickupNos` | `string`\[\] | 是 | 我能核销哪些自提点 |
| `groupNos` | `string`\[\] | 是 | 我发起了哪些团。**第三个作用域**，与门店 / 自提点正交 |
| `staffRoles` | [`StaffRole`](#staffrole) \| `string`\[\] | 是 | 我在**当前门店**持有的角色（可多个）。老板恒为 `["OWNER"]` |
| `categoryCodes` | `string`\[\] | 否 | 主体已获批的经营类目码（如 `["FRESH_VEG"]`）。 **与门店货架是两件事**：这是平台批的证（能不能卖这一类）， 货架是商家自己摆的（店里怎么摆）。 |
| `switches` | [`Record_string_boolean`](#record_string_boolean) | 否 | 平台开关里与商家侧有关的那几个（后端 `/biz/context` 下发）。 <p>`categoryGate`：类目资质校验**是否真的拦人**。 <p>此前这是 `b-app/src/shared/flags.ts` 里的编译期常量，运营改一次开关要重新 打包发版；更糟的是它与后端那份不同步时，症状是「点不动一个其实能按的按钮」 或者「点下去吃一句说不清缘由的报错」—— 两种都难查，因为界面与后端各自看起来都对。 <p>取不到时按 **false（不拦）** 处理：与后端默认值一致，且宁可放行也不要 凭一个拿不到的开关把商家挡在门外。 |
| `perms` | `string`\[\] | 是 | 这些角色合起来的权限码，**已取并集**（老板是 `["*"]`）。 端上照它裁剪入口，**不要自己按角色再推一遍** —— 两处各推一次迟早分岔， 而分岔的表现是「看得见但点了报错」。 |

### CampaignDraft

新建/编辑活动的入参

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `campaignNo` | `string` | 否 | 活动单号。新建时不传，编辑时必传 |
| `type` | [`CampaignType`](#campaigntype) | 是 | 活动类型。**创建后不可改** —— 改类型等于换一套优惠语义，应当新建 |
| `name` | `string` | 是 | 活动名 |
| `startAt` | `number` | 是 | 生效开始时间 |
| `endAt` | `number` | 是 | 生效结束时间。须晚于 startAt |
| `thresholdMinor` | `number` | 否 | 门槛：满多少（最小货币单位）。COUPON / FULL_CUT 用 |
| `discountMinor` | `number` | 否 | 优惠额（最小货币单位）。COUPON / FULL_CUT 用 |
| `flashPriceMinor` | `number` | 否 | 活动价（最小货币单位）。FLASH 用 |
| `buyN` | `number` | 否 | 购买件数门槛 N。BUY_GIFT 用 |
| `giftM` | `number` | 否 | 赠送件数 M。BUY_GIFT 用 |
| `goodsNos` | `string`\[\] | 是 | 参与商品；空数组 = 全店 |
| `totalCount` | `number` | 否 | 发放总量。COUPON 用，不传表示不限量 |
| `storeNo` | `string` | 否 | 只对这家门店生效；不传 = 全主体。 **只有 FULL_CUT 接受它**（后端会拒 70005）。判据是活动在哪一刻生效： 满减在算价时生效，那时顾客已选好自提点；限时特价与买赠改的是商品页的展示， 而浏览商品时自提点还没选 —— 允许限定门店会让页面价与下单价打架。 |

### CampaignStatus

枚举取值：

- `DRAFT`
- `RUNNING`
- `PAUSED`
- `ENDED`

### CampaignType

商家营销活动。 为什么统一成一个 `MarketingCampaign` 而不是四张表：券、满减、限时特价、买赠 在数据上只差「触发条件 + 优惠方式」，各建一套的结果是四份几乎一样的增删改查， 以及四份互不知情的叠加规则 —— 而叠加恰恰是最容易算错的地方。

枚举取值：

- `COUPON`
- `FULL_CUT`
- `FLASH`
- `BUY_GIFT`

### CardSpec

卡券属性（CARD）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `faceValueMinor` | `number` | 否 | 储值卡面值（最小货币单位）；次卡为空 |
| `timesTotal` | `number` | 否 | 次卡总次数；储值卡为空 |
| `validDays` | `number` | 是 | 有效期天数 |

### Carrier

一家承运方（`CarrierVO`）。**归履约域维护，进销存只读**。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `carrier` | `string` | 是 | 编号，如 `SF`。**调拨单存的就是它** —— 跨库不能外键，所以存对方的业务键 |
| `name` | `string` | 是 | 名字。选中后要**一起回传**给发货接口：进销存读不了主库，快照只能由端上带过去 |

### Category

类目树节点（对齐后端 `CategoryVO`）。 <p>⚠️ **不要把它和 `CategoryType` 搞混** —— 那是五品类枚举 （NORMAL/FRESH/SERVICE/VIRTUAL/CARD），挂在商品上、由平台硬编码，决定履约与合规 （冷链、不发货、iOS 可售规则）。这里的类目树是运营可维护的数据，决定归类与经营准入。 两个维度正交，见 `docs/technical/类目树补齐方案.md`。 <p>这个类型此前声明了一个后端根本不返回的 `type` 字段，并写着「仅两级」—— 而后端一直是三级。没人用它，所以错了很久也没暴露。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categoryNo` | `string` | 是 | 类目单号 |
| `parentNo` | `string,null` | 否 | 上级类目单号。一级类目为空 |
| `level` | `number` | 是 | 1–3。**三级封顶** |
| `name` | `string` | 是 | 类目名（后端按 Accept-Language 下发已本地化文案） |
| `icon` | `string` | 否 | 类目图标 URL。运营没配就是空串，端上按占位渲染 |
| `template` | `string` | 否 | 该类目的**品类模板**：`STANDARD` / `FRESH` / `SERVICE` / `VOUCHER`。 <p><b>它就是「品类」，只是另一套码</b>（STANDARD↔NORMAL、VOUCHER↔CARD， 见 `TEMPLATE_TO_TYPE`）。选定类目即可推出品类 —— 让商家把同一件事填两遍， 唯一的产出是两者可能互相矛盾，而矛盾没有任何一处会拦。 |
| `requiredCode` | `string` | 否 | 经营这个类目要的授权码；**空 = 无门槛**。 <p>与 `BizScope.categoryCodes` 比对，端上就能在选之前说清楚「你还不能卖这一类」—— 不下发的话商家只能靠「选了、保存、被拒」这条路才知道， 而那句报错既说不出缺哪张证，也说不出去哪申请。 |
| `qualifications` | `string`\[\] | 否 | 人读的资质名，如「食品经营许可证」。展示用，判据是 `requiredCode` |
| `sort` | `number` | 是 | 同级内的展示顺序，小的在前。运营在后台拖动排序改的就是它 |
| `children` | [`Category`](#category)\[\] | 是 | 子类目。叶子是空数组而不是 undefined —— 端上少一次判空 |

### CategoryType

枚举取值：

- `NORMAL`
- `FRESH`
- `SERVICE`
- `VIRTUAL`
- `CARD`

### Community

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communityNo` | `string` | 是 | 社区单号 |
| `name` | `string` | 是 | 社区名（小区名） |
| `address` | `string` | 是 | 社区地址 |
| `cityCode` | `string` | 是 | 所属城市。全市范围的商家靠它判定可达 |
| `regionCode` | `string` | 否 | 所属街道/镇（9 位区划码）。商家框范围时「按街道看聚落」靠它 —— 不下发的话端上只能拿到一锅平铺清单，街道视图无从分组。 |
| `kind` | `string` | 否 | ESTATE 小区 / VILLAGE 村。只是展示标签，不参与匹配 |
| `distance` | `number` | 是 | 米 |
| `pickups` | [`Pickup`](#pickup)\[\] | 是 | 本社区可用的自提点 |
| `originCode` | `string,null` | 否 | 官方村码，只有 `kind=VILLAGE` 且经官方名录开通的才有。**`regionCode` 是它挂的 街道/镇，不是它自己** —— 经营范围选择器再往下钻一层要用这个码，不能用 regionCode， 否则「牛杜村」会被当成「牛杜镇」去下钻。 |
| `originName` | `string,null` | 否 | `originCode` 对应的原始官方名（「景滑村委会」，未清理）——仅供展示/追溯， 判「是不是村委会」不要解析它，用下面的 `rural` 字段（服务端存的，不是端上猜的）。 |
| `rural` | `boolean` | 否 | 是不是村委会（`sys_region.rural`，经 origin_code 反查）。只对 kind=VILLAGE 有意义： 村委会到此为止、不再下钻；居委会/社区还能再挑具体小区。 |
| `latE6` | `number,null` | 否 | 官方村名录批量补录过的坐标，可能为空 |
| `lngE6` | `number,null` | 否 | — |

### CommunityApply

商家提报的新社区（ADR-013 阶段三）。 提报**不等于**社区已存在：审过之后平台才建出来，`communityNo` 这时才有值。 端上别拿它去当社区用 —— 待审的小区不在任何选点列表里。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `applyNo` | `string` | 是 | 提报单业务键 |
| `merchantNo` | `string` | 是 | 提报的商家 |
| `merchantName` | `string` | 是 | 商家名。运营看着一串 M20260811… 判断不了任何事 |
| `name` | `string` | 是 | 小区名，商家填 |
| `address` | `string` | 否 | 地址。运营靠它判断这是不是已有社区的另一个叫法 —— 批重了商家会分不清该勾哪个 |
| `regionCode` | `string` | 否 | 商家选的区划，**只是建议** —— 最终以运营裁决时填的为准 |
| `regionPath` | `string` | 否 | 区划整条路径名。「北山街道」全国有好几个，光末级判断不了是不是同一个地方 |
| `note` | `string` | 否 | 补充说明：为什么要开这个点 |
| `status` | [`CommunityApplyStatus`](#communityapplystatus) | 是 | 待审 / 已建社区 / 已驳回。裁完即终态 |
| `communityNo` | `string` | 否 | 通过后建出来的社区号；待审与驳回时为空 |
| `reason` | `string` | 否 | 驳回原因，**原样展示给商家** —— 不给理由他只会原样再提一次 |
| `submittedAt` | `number` | 是 | 提报时间（毫秒时间戳） |

### CommunityApplyStatus

枚举取值：

- `PENDING`
- `APPROVED`
- `REJECTED`

### CouponIssueBatch

一次定向发放的结果。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `issueNo` | `string` | 是 | — |
| `couponNo` | `string` | 是 | — |
| `segmentNo` | `string,null` | 否 | — |
| `planned` | `number` | 是 | 人群此刻命中多少人 |
| `issued` | `number` | 是 | — |
| `skipped` | `number` | 是 | — |
| `skipReasons` | `object`（见下）\[\] | 是 | `UNREACHABLE` 还没注册或已退订 / `ALREADY_HAS` 到每人上限 / `SOLD_OUT` 券发完 |
| `amountMinor` | `number` | 是 | — |
| `operatorNo` | `string,null` | 否 | — |
| `issuedAt` | `number` | 是 | — |

`skipReasons[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reason` | `string` | 是 | — |
| `count` | `number` | 是 | — |

### CouponRedeemResult

核销结果。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `userCouponNo` | `string` | 是 | — |
| `timesUsed` | `number` | 是 | — |
| `remaining` | `number` | 是 | — |
| `usedUp` | `boolean` | 是 | — |
| `duplicated` | `boolean` | 是 | — |

### CouponRedeemView

到店核销：先看后核里「看」的那一步（P6）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `userCouponNo` | `string` | 是 | — |
| `couponNo` | `string` | 是 | — |
| `title` | `string` | 是 | — |
| `benefitText` | `string` | 是 | 「减 3 元」「8.5 折」「兑换」这种人话，后端拼好 |
| `phoneTail` | `string,null` | 否 | — |
| `expireAt` | `number` | 是 | — |
| `timesTotal` | `number` | 是 | — |
| `timesUsed` | `number` | 是 | — |
| `remaining` | `number` | 是 | 还能核几次。次卡看这个数 |
| `redeemable` | `boolean` | 是 | — |
| `reason` | `string,null` | 否 | 不能核销时的原因码：`EXPIRED` / `USED_UP` / `REVOKED` / `NOT_STORE_CODE` / `COUPON_INACTIVE` |

### CreateGroupReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 要开团的商品，必须是本店已上架商品 |

### CrossStoreCompare

跨店对比（B-11.12.6）· `GET /biz/cross-store/compare?days=30`。 <p>门禁与  {@link  CrossStoreOverview }  相同（`cross_store_stats` 能力位）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `days` | `number` | 是 | **实际生效**的窗口天数（后端已夹在 1–365）。回显它，端上才知道传 99999 被截成了 365 |
| `currency` | [`CurrencyCode`](#currencycode) | 是 | 统计口径的币种 |
| `rating` | `number` | 是 | **主体整体评分**（各店的合成，也是 C 端商家卡上显示的那个）。 每家店自己的分在  {@link  CrossStoreCompareRow#rating  }  上（V155 起）。 【历史】V155 之前 `rvw_review` 只有 `entity_no` 没有 `store_no`， 门店维度的评分没有数据源，所以这个数只能放顶层。 <p>渲染成一条「本店铺整体评分」的说明；对比表格里那一列用每行自己的  {@link  CrossStoreCompareRow#rating  } 。**别拿这个数去填表格列** —— 那样三家店会显示同一个数字，而这正是 V155 之前的样子。 |
| `ratingCount` | `number` | 是 | 计入评分的评价条数。0 = 还没人评过，显示「暂无评价」而不是 0 颗星 |
| `stores` | [`CrossStoreCompareRow`](#crossstorecomparerow)\[\] | 是 | 按店并列，顺序同门店列表 |

### CrossStoreCompareRow

跨店对比的一行 —— 窗口内这家店的销售额 / 订单 / 复购 / 缺货（B-11.12.6）。 <p>⚠️ **这里没有评分**，它在  {@link  CrossStoreCompare#rating  }  上，是主体级的。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号 |
| `storeName` | `string` | 是 | 门店名 |
| `isDefault` | `boolean` | 是 | 是否默认店 |
| `status` | [`StoreStatus`](#storestatus) | 是 | ACTIVE 正常营业 / READONLY 已停用 |
| `orders` | `number` | 是 | 窗口内订单数（不含已取消） |
| `gmvMinor` | `number` | 是 | 窗口内成交额（最小货币单位） |
| `buyers` | `number` | 是 | 窗口内下过单的买家数（去重）。复购率的分母 |
| `repeatBuyers` | `number` | 是 | 其中下过 ≥2 单的买家数 |
| `repeatRate` | `number` | 是 | `repeatBuyers / buyers`，0–1。**分母为 0 时是 0**，一家还没开张的店显示 0% |
| `rating` | `number` | 是 | **这家店自己的**评分（V155，ADR-011：评价归门店）。 ⚠️ 与顶层的  {@link  CrossStoreCompare#rating  }  是两个数：那个是主体整体分 （C 端商家卡上显示的那个），这个是「楼下那家」的分。两个都要显示 —— 商家问「为什么我的店 4.9 而搜索里是 4.6」时，只有并排看得到才解释得通。 |
| `ratingCount` | `number` | 是 | 计入这家店评分的条数。**0 = 暂无评价**，按条数判空而不是按分值 —— 老评价没有门店归属，所以老店在第一条新评价到来之前也是 0。 |
| `outOfStockSkus` | `number` | 是 | 该店可用量（stock − locked）≤ 0 的 SKU 数。 **只数已启用分店库存的 SKU** —— 一条店级行都没有的 SKU 走主体总量，不算这家店缺货。 |

### CrossStoreOverview

跨店总览（B-11.12.5）· `GET /biz/cross-store/overview`。 <p>**只有门店维度的三项待办**：工作台上的 `toVerify`（待核销）与 `toPick`（待分拣） 后端刻意不给 —— 那两个数是**自提点**维度且不限商家（一个自提点承接多家商家的货， ADR-005）。摆进「门店」这一列，商家会读成「这家店的活」，点进去却是别人的货。 <p>需要 `cross_store_stats` 能力位（PRO / CHAIN）。FREE 档访问会被后端以 `PLAN_CAPABILITY_REQUIRED`(70023) 拒绝 —— 端上要渲染**示例态 + 升档说明**， 不是空白页也不是红色报错。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `currency` | [`CurrencyCode`](#currencycode) | 是 | 统计口径的币种。与 `/biz/dashboard/stats` 同一个字段 |
| `stores` | [`CrossStoreRow`](#crossstorerow)\[\] | 是 | 按店并列。顺序与门店列表一致（默认店在前），端上不必自己排 |

### CrossStoreRow

跨店总览的一行 —— 一家门店的今日 / 本月 / 三项待办（B-11.12.5）。 <p>**没有单的门店也占一行（全零），不会从列表里消失**： 一家今天还没开张的店从总览里不见了，店主的第一反应是「我的店呢」。 零是一个答案，缺席不是。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号。点进去切门店时用它 |
| `storeName` | `string` | 是 | 门店名。列表里认店靠它，不要拿门店号显示 |
| `isDefault` | `boolean` | 是 | 是否默认店。**一个主体恰好一家**，界面上要标出来 |
| `status` | [`StoreStatus`](#storestatus) | 是 | ACTIVE 正常营业 / READONLY 已停用。停用的店仍在列表里 —— 看不见会被当成「店被删了」 |
| `todayOrders` | `number` | 是 | 今日订单数（自然日，按市场本地时区切分） |
| `todayGmvMinor` | `number` | 是 | 今日成交额（最小货币单位） |
| `monthOrders` | `number` | 是 | 本月订单数 |
| `monthGmvMinor` | `number` | 是 | 本月成交额（最小货币单位） |
| `toShip` | `number` | 是 | 待发货单数（快递） |
| `toDeliver` | `number` | 是 | 待自送单数（商家自送） |
| `toStock` | `number` | 是 | 待备货单数（自提单已付款、货还没送到自提点）。按**门店**算 |

### CurrencyCode

枚举取值：

- `CNY`
- `USD`
- `AED`

### DeliveryRule

商家自送规则（ADR-005 §5：不做骑手系统，只有范围与门槛）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `radius` | `number` | 是 | 配送半径，米 |
| `minOrderMinor` | `number` | 是 | 起送价，最小货币单位 |
| `feeMinor` | `number` | 是 | 配送费，最小货币单位 |
| `freeThresholdMinor` | `number` | 是 | 免配送费门槛，最小货币单位；0 表示不免 |

### Entity

一张**证照**（营业执照）。库里叫 `mch_entity`，**对外一律叫「证照」**—— 老板不认识「主体」「实体」这两个词。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entityNo` | `string` | 是 | — |
| `name` | `string` | 是 | 执照上的名称。**待补证照时它是老板随手填的店名**—— 补齐执照、审核通过后被执照上的正式名称覆盖 |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | ACTIVE 营业中 / PENDING_LICENSE 待补证照 / SUSPENDED、BANNED 已停业。 **端上照它给下一步**：待补证照给「去补执照」，已停业给客服入口 |
| `verified` | `boolean` | 是 | 平台已认证。待补证照恒为 false —— 这个标是审核给的，不能自己开店就带上 |
| `legalForm` | `string` | 否 | 个体户 / 有限公司…… 待补证照时为空，那时还不知道是哪种 |
| `storeCount` | `number` | 是 | 这张证照下**我能进**的门店数。老板 = 全部；店员 = 只数被授权到的那几家 |
| `isPrimary` | `boolean` | 是 | 默认证照。不带 `X-Store-No` 时后端解析到的就是它 |
| `canManage` | `boolean` | 是 | 我是不是这张证照的持有人。**只有持有人能改资料、交执照、挂收款号** |

### EntityStores

一张证照 + 它下面我能进的门店。门店选择器按这个分组渲染。 **为什么分组而不是拍平**：两家店同名是常事（「文三路店」在两张执照下各有一家）， 拍平之后点哪个都不知道进了哪张证照，而进错的表现是「商品怎么全没了」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entity` | [`Entity`](#entity) | 是 | — |
| `stores` | [`Store`](#store)\[\] | 是 | — |

### FulfillmentImpactItem

关掉某条履约路会影响到的商品（P2 处置前的预览）。 **给它起个名字，不写成内联的 `Array<{ ... }>`**：契约里的匿名结构在规格生成器 那边引用不到，只能落成一个空 object —— 后端照着实现就得自己猜返回什么。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | — |
| `title` | `string` | 是 | — |

### FulfillmentReach

枚举取值：

- `PICKUP`
- `ONSITE`
- `SHIPPING`

### FulfillmentType

枚举取值：

- `STORE_PICKUP`
- `NEIGHBOR_PICKUP`
- `MERCHANT_DELIVERY`
- `EXPRESS`
- `STORE_VERIFY`
- `APPOINTMENT`
- `INSTANT`

### FundsMode

资金路径：**钱先进谁的账户**。与 `mch_entity.funds_mode` 同值。 ⚠️ **与「经营模式」（谁是销售主体）正交，不要合并** —— 合成一个枚举后，「直连 + 自营」（钱进商家户却说平台是卖方） 这种非法组合在类型上就是可表达的（同 ADR-013 教训）。 结算侧「要不要给积分补差」判的是**这一个**： 钱在商家二级户才需要补进去，钱在平台户是平台自己少收。

枚举取值：

- `AGGREGATED`
- `DIRECT`

### GeoReverseResult

逆地理编码结果（P2）：recommend 是带楼盘/门牌的人话版

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `recommend` | `string` | 是 | — |
| `address` | `string` | 是 | — |

### GeoTip

地点输入提示（高德 inputtips 经后端代理）。提报小区时按名搜 POI，选中就带上坐标 —— 否则坐标只能是「提交那一刻商家站的地方」，多半不在那个小区里。 后端没配 Web 服务 key 时返回空数组，端上就当没有这个功能。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | — |
| `address` | `string,null` | 否 | — |
| `adcode` | `string,null` | 否 | — |
| `latE6` | `number,null` | 否 | 有些提示（纯地名、公交线）没坐标，这种不值得选 |
| `lngE6` | `number,null` | 否 | — |
| `typecode` | `string,null` | 否 | — |

### Goods

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
| `detailImages` | `string`\[\] | 否 | 图文详情区的长图，按顺序全宽竖排。 **与 `images` 分开**：轮播是详情页顶部的方图、可左右滑；这些是正文下方的长图、 竖着一张接一张。合成一个数组之后端上只能靠宽高比猜哪几张该轮播 —— 猜错就是 一张 1:3 的长图被塞进方形轮播里。 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | **商品参数**（产地 / 保质期 / 材质…）—— 规格库里 `usage_type=PROP` 的那批。 <p>与 `specGroups` 形状相近、语义相反：那个的每一项都会进笛卡尔积生成 SKU， 这个一项也不进。买家不用挑，只是看；筛选靠 `code` / `valueNo`。 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 |
| `categoryNo` | `string` | 是 | 所属类目 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 所属商家 —— 商品与服务都要展示商家信息 |
| `rating` | `number` | 否 | 本商品的评分与评价数（区别于商家整体评分） |
| `ratingCount` | `number` | 否 | 本商品的评价条数 |
| `price` | `number` | 是 | 展示价（最小货币单位），取各 SKU 最低价 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位） |
| `fulfillments` | [`FulfillmentType`](#fulfillmenttype)\[\] | 是 | 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 规格维度定义；单规格商品也有一组 |
| `skus` | [`Sku`](#sku)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `sales` | `number` | 是 | 累计销量，展示用 |
| `cutoffAt` | `number` | 否 | FRESH：预售截单时间戳 |
| `arrivalDesc` | `string` | 否 | FRESH：预计到货描述 |
| `weighed` | `boolean` | 否 | FRESH：是否按实称多退少补 |
| `origin` | `string` | 否 | FRESH：产地 |
| `durationMin` | `number` | 否 | SERVICE：服务时长（分钟） |
| `storeName` | `string` | 否 | SERVICE：可核销门店 |
| `slots` | [`AppointmentDaySlots`](#appointmentdayslots)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段。**后端未下发** |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD。**后端未下发** |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL。**后端未下发** |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M）。**后端未下发** |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。**后端未下发**：库里有 `prd_goods.points_config` 这一列， 但全仓没有任何读写。等积分域接上再兑现。 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。空 = 商家没写 —— 端上整段不渲染， 别拿一个空白区块占着详情页。 |
| `status` | [`GoodsStatus`](#goodsstatus) | 否 | — |
| `auditReason` | `string` | 否 | 最近一次驳回 / 平台强制下架的原因（**只在商家侧与运营端下发，C 端恒空**）。 **没有它，商家面对 `REJECTED` 只能猜要改什么** —— 审计日志只有运营看得到。 平台强制下架时后端会带「平台强制下架」前缀，商家据此知道是自己被驳 还是被平台下的。过审时清空。 ⚠️ 后端 `GoodsVO` 一直在发它，`MerchantGoodsService` 的注释甚至写着 「它会出现在商家 B 端（`auditReason`）」—— 而端上从没声明这个字段。 那句注释描述的是一件**从未发生过**的事。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |
| `stdNo` | `string` | 否 | 引用的平台标准品；空 = 自建品。**只有商家侧与运营端下发，C 端恒空。** <p>必须下发：编辑页保存是整份覆盖，拿不到它就等于 **打开编辑页再保存一次就自动脱离了标准品** —— 商品从此不再被收敛， 而界面上没有任何变化。与 `titleI18n` / `priceByMarket` 是同一个形状的故障。 |
| `hasDraft` | `boolean` | 否 | 有未发布的修改（双版本草稿，V279）。**只有商家侧 `/biz/goods` 下发**， C 端与运营端恒空 —— 它是商家的编辑态提示，买家与审核队列都不消费它。 <p>判据是**草稿行存在与否**，不比内容：保存的内容与线上相同时后端直接删行， 所以 true 一定意味着「发布会改变线上」。列表页据此挂「有未发布修改」徽标。 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |

### GoodsParam

一条商品参数。 <p>`valueNo` 是平台值池里的编号，**有它才参与筛选与跨店比较**； 量纲型（功率、净重）平台不枚举值，那时只有 `label`。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dimNo` | `string` | 是 | 所属规格维度（`usage_type=PROP`） |
| `name` | `string` | 否 | 维度名（「产地」「保质期」）。**买家页要显示它** —— 只有 dimNo 的话详情页上是一行 `SD_ORIGIN: 本地`。 <p>存在商品身上而不是每次去规格库查：它是**下单那一刻的快照**， 与规格组同一口径 —— 商家事后把本店叫法改了，已卖出的商品不该跟着变。 |
| `valueNo` | `string` | 否 | 平台值编号。量纲型没有 |
| `code` | `string` | 否 | 平台值编码，跨店可比 |
| `label` | `string` | 是 | 展示文案 |

### GoodsStatus

商家侧商品状态。 <p><b>DRAFT 与 PENDING 是两件事</b>：草稿是「还没提交，等你」，待审是「已提交，等平台」—— 说错了商家的下一步就错了。也与 OFF_SALE（点一下就能卖）分开。

枚举取值：

- `DRAFT`
- `ON_SALE`
- `OFF_SALE`
- `PENDING`
- `REJECTED`

### GrantStoreReq

授予或撤销**一个**门店角色。 **增量式，不是覆盖式**：这一次只动 `role` 这一个角色，不碰他在这家店的其他角色。 覆盖式在多角色下是错的 —— 老板想「再加一个配送员」，结果把「店员」冲掉了。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 授权到哪家店。只能是本主体的门店 |
| `role` | [`StaffRole`](#staffrole) | 是 | 要授予/撤销的那一个角色 |
| `granted` | `boolean` | 否 | true 授予（默认）、false 撤销。撤到一个不剩 = 从这家店移除他 |

### GrantType

登录方式。 · WX_MINI  小程序静默登录（只拿 openid，拿不到手机号） · WX_PHONE 小程序一键取手机号（推荐：一次授权直接拿到号，省掉短信） · WX_OPEN  App 微信开放平台 · APPLE    Apple 登录（iOS 上架硬要求） · PHONE_OTP 手机号 + 短信验证码（全端兜底，也是商家账号的主标识） · PASSWORD  手机号 + 密码（**只有 B 端有**）。商家一天开好几次 App，   每次等一条短信是实打实的摩擦；而它与其它方式最本质的差别是**不建户** ——   能用密码登录的前提是他已经设过密码，而设密码本身要先登录。

枚举取值：

- `WX_MINI`
- `WX_PHONE`
- `WX_OPEN`
- `PHONE_OTP`
- `APPLE`
- `PASSWORD`

### GroupBuy

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `groupNo` | `string` | 是 | 团单号 |
| `status` | [`GroupBuyStatus`](#groupbuystatus) | 是 | 团的状态 |
| `goodsNo` | `string` | 是 | 开团的商品 |
| `title` | `string` | 是 | 商品标题快照 |
| `cover` | `string` | 是 | 商品封面快照 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 供货商家 |
| `initiatorNickname` | `string` | 是 | 发起人昵称 |
| `initiatorAvatar` | `string` | 是 | 发起人头像 |
| `pickupNo` | `string` | 是 | ★ 成团范围：**成团单位是自提点**，拼的是一车送到一个点的成本 |
| `pickupName` | `string` | 是 | 自提点名称快照 |
| `basePrice` | `number` | 是 | 不成团时的价格（降级发货用此价） |
| `groupPrice` | `number` | 是 | 成团价 |
| `minCount` | `number` | 是 | 成团所需人数 |
| `joinedCount` | `number` | 是 | 已参团人数 |
| `reached` | `boolean` | 是 | 已成团 |
| `need` | `number` | 是 | 还差几人 |
| `expireAt` | `number` | 是 | 截止时间：发起后 validHours 与商品截单时间取更早 |
| `members` | `object`（见下）\[\] | 是 | 已参团的邻居，展示用。 **没有件数**：参团是一人一份 —— 成团判断、「还差 N 人」的文案、`joinedCount` 全部按人算，库里也没存过件数。这里原先有个 `qty`，页面照着渲染 `×{qty}`， 而它从来没有值。 |
| `joined` | `boolean` | 是 | 当前用户是否已参团 |
| `neighborPickup` | [`PickupPoint`](#pickuppoint) | 否 | 邻里自提点（C-GB-06）：发起人勾选「送到我家」时有值。 参团者在这里取货，发起人负责签收与逐单核销 —— **零报酬**（ADR-005 §3）。 |
| `isOwner` | `boolean` | 否 | 我是不是这个团的发起人 —— 决定是否显示轻核销入口 |

`members[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `avatar` | `string` | 是 | — |
| `nickname` | `string` | 是 | — |

### GroupBuyStatus

商家团 / 邻里团的状态。**与库 `mkt_group_buy.status` 逐字一致**。 契约上原先没有这个字段，端上只能拿 `reached` 判断 —— 而**平台中止的团 人数可能已经够了**，只看 reached 会把一个已经作废的团显示成正常可参的团。

枚举取值：

- `OPEN`
- `FORMED`
- `FAILED`

### GroupRequest

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestNo` | `string` | 是 | 求团需求单号 |
| `initiatorNickname` | `string` | 是 | 发起人昵称 |
| `initiatorAvatar` | `string` | 是 | 发起人头像 |
| `pickupNo` | `string` | 是 | 需求的范围仍是自提点/小区 —— 邻里的意义就在于此 |
| `pickupName` | `string` | 是 | 自提点名称快照 |
| `title` | `string` | 是 | 需求标题，如「想团儿童床垫」。**此时商品还不存在**，只有这句话 |
| `desc` | `string` | 是 | 需求详述：尺寸、材质、用途等，供商家判断能不能接 |
| `images` | `string`\[\] | 是 | 参考图。发起人拍的样图或截图 |
| `expectQty` | `number` | 是 | 发起人期望的数量 |
| `budgetMinor` | `number` | 否 | 心理价位（可不填） |
| `status` | [`GroupRequestStatus`](#grouprequeststatus) | 是 | 需求单状态 |
| `interestedCount` | `number` | 是 | 表达意向的邻居数（含发起人）—— 不是订单数 |
| `interested` | `boolean` | 是 | 当前用户是否已 +1。决定按钮显示「我也要」还是「已加入」 |
| `neighbours` | `object`（见下）\[\] | 是 | +1 的邻居头像墙。只取前若干个用于展示，不是全量 |
| `quotes` | [`Quote`](#quote)\[\] | 是 | 收到的报价。一个需求单可多家报价，由发起人挑 |
| `createdAt` | `number` | 是 | 发起时间 |
| `expireAt` | `number` | 是 | 需求单过期时间。过期即 EXPIRED，不再接受报价 |
| `groupNo` | `string` | 否 | LOCKED 之后指向生成的正式团 |
| `lockedPriceMinor` | `number` | 否 | 选定的报价快照。转成正式团后下单用这个价，**不读商家当前价** —— 这是防加价最硬的一层：加价在技术上做不到，不需要审核。 |
| `confirmed` | `boolean` | 否 | 我（+1 的邻居）是否已二次确认下单。+1 不等于承诺，必须各自确认 |
| `confirmedCount` | `number` | 否 | 已确认下单的人数 |

`neighbours[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `avatar` | `string` | 是 | — |
| `nickname` | `string` | 是 | — |

### GroupRequestStatus

求团需求单的状态。**取值以库里存的为准**（`mkt_request.status`）。 这里原先是另一套词：OPEN / QUOTING / MATCHED / EXPIRED —— 与后端一个都对不上， 于是页面上 `status === "MATCHED"` 恒 false（已选定报价那一块、二次确认按钮 永远不出现），而 `status !== "MATCHED"` 恒真（锁价之后「选定」按钮仍然挂着）。 两边各写各的，谁也没报错。 枚举对账守卫当时也是绿的：它拿端上的取值去全后端的大写字面量里搜， 而 MATCHED / OPEN / EXPIRED 恰好在别的域里存在（团购、优惠券…）—— **同名异义把缺口盖住了**。词袋比对不了「这个字段的取值」。

枚举取值：

- `COLLECTING`
- `QUOTED`
- `LOCKED`
- `CONFIRMED`
- `CLOSED`

### HandleAfterSaleReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `remark` | `string` | 是 | 驳回理由，**必填**（后端 `@NotBlank`）：用户拿不到理由只能升级平台， 平台再回头问商家，多绕一圈。 字段名是 `remark` 不是 `reply` —— 后端 `BizAfterSaleController.RejectReq` 收的是它。 |

### IncomeSummary

商家收入按状态汇总。 ⚠️ **四个数是四种状态，不是四个口袋** —— 它们加起来等于全部结算单。 结算页此前只显示一个「商家实得」，读起来像已到手：商家拿它去对银行流水， 对不上就来找客服，而客服看到的状态也只有一个词。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `receivedMinor` | `number` | 是 | 已到账：通道回执确认过的 |
| `inFlightMinor` | `number` | 是 | 已发起、等通道确认。**此前它混在「已到账」里**，而底下是桩 |
| `pendingMinor` | `number` | 是 | 待结算 |
| `offlineMinor` | `number` | 是 | 当面收款：**他早就拿到了**，无需结算 |
| `inFlightCount` | `number` | 是 | — |
| `oldestInFlightAt` | `number,null` | 否 | 最早一笔在途的发起时刻。**「卡了多久」是商家真正想问的** |

### MarkArrivedReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNos` | `string`\[\] | 是 | 批量：一次到货通常是一整批，逐单调用会让通知发成 N 条 |
| `pickupNo` | `string` | 否 | 给哪个自提点登记；**不传 = 当前门店的那个点**。 一个商家两家店两个点是常态（自提点归属到门店之后）。不传且当前门店没有点时 后端会拒 —— 而不是悄悄登记到另一个点上。 |

### MarketingCampaign

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `campaignNo` | `string` | 是 | 活动单号 |
| `merchantNo` | `string` | 是 | 所属商家。活动是店铺级的，不跨店 |
| `type` | [`CampaignType`](#campaigntype) | 是 | 活动类型，决定下面哪几个可选字段有意义 |
| `name` | `string` | 是 | 活动名，展示给用户 |
| `status` | [`CampaignStatus`](#campaignstatus) | 是 | 活动状态 |
| `startAt` | `number` | 是 | 生效开始时间 |
| `endAt` | `number` | 是 | 生效结束时间 |
| `thresholdMinor` | `number` | 否 | 门槛：满多少（最小货币单位）。FLASH / BUY_GIFT 不用 |
| `discountMinor` | `number` | 否 | 优惠额：COUPON / FULL_CUT 用（最小货币单位） |
| `flashPriceMinor` | `number` | 否 | FLASH：活动价（最小货币单位） |
| `buyN` | `number` | 否 | BUY_GIFT：购买件数门槛 N |
| `giftM` | `number` | 否 | BUY_GIFT：赠送件数 M |
| `goodsNos` | `string`\[\] | 是 | 参与商品；空 = 全店 |
| `totalCount` | `number` | 否 | COUPON：发放总量。**预算上限，防止发穿** |
| `takenCount` | `number` | 否 | COUPON：已被领取的数量 |
| `usedCount` | `number` | 是 | 已核销/已使用次数，衡量效果 |
| `storeNo` | `string` | 否 | 只对这家门店生效；**空 = 全主体**（存量活动都是它）。 多门店商家必须看得见 —— 否则两条同名的「开业满减」分不清是哪家店的。 |

### MasterData

平台主数据快照（`GET /common/master-data`）。 合成一个响应而不是三条接口，是因为它们在**同一屏上被同时用到**： 「选行业 → 据此过滤可选主体 → 主体决定要不要传营业执照」。 分三次请求会出现「行业回来了、主体还没回来」的中间态， 而那个中间态里表单不知道该不该禁用某个选项。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `industries` | [`MasterDataIndustry`](#masterdataindustry)\[\] | 是 | 可选行业。**决定能不能以小微主体进件**，也是 points_forced 默认值的来源 |
| `subjects` | [`MasterDataSubject`](#masterdatasubject)\[\] | 是 | 可选主体类型（法律形态）。决定资质要求与结算账户形态 |
| `channels` | [`MasterDataChannel`](#masterdatachannel)\[\] | 是 | 可用支付通道与其能力位 |
| `serviceScopes` | [`ServiceScope`](#servicescope)\[\] | 是 | **这一期开放的经营范围档位**（`SERVICE_SCOPE` 的启用子集，运营在后台配）。 端上要照它渲染选项，**不要把三档写死**。写死的后果不是「多了个选项」： 一期自营模式关掉了 `PLATFORM`，而 B 端照样把「全平台发货」摆在那里， 商家点下去得到的是「当前不支持这个经营范围」—— 一个必被拒的选项， 而他无从知道自己该选什么。2026-08-11 的端到端实测撞到过。 拿到 EDI 切平台模式时运营在后台放开，端上不发版就跟着变 —— 这正是它下发而不是写死的理由。 |

### MasterDataChannel

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payChannel` | `string` | 是 | 通道码（`sys_pay_channel.pay_channel`），如 WECHAT |
| `name` | `string` | 是 | 展示名 |
| `enabled` | `boolean` | 是 | 通道是否可用。关掉时下单页不给这个支付方式，而不是点了才失败 |
| `payMethods` | `string`\[\] | 是 | 该通道支持的支付方式，如 JSAPI / APP / H5 |

### MasterDataIndustry

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `industry` | `string` | 是 | 行业码（`sys_industry.industry`），提交申请时回传的就是它 |
| `name` | `string` | 是 | 展示名。**取服务端的**，不要在端上再维护一份翻译 |
| `microAllowed` | `boolean` | 是 | 该行业能否以小微主体进件。**false 时小微选项要禁用**，不是提交后才报错 |

### MasterDataSubject

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `subjectType` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型码 |
| `name` | `string` | 是 | 展示名 |
| `needLicense` | `boolean` | 是 | 要不要传营业执照 |
| `industryGated` | `boolean` | 是 | 是否受行业白名单管控（小微受管，其余不受） |
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 是 | 该主体默认的结算账户形态：小微打个人，其余打对公 |

### Member

会员：一个人与这家商家的关系（P1）。 <p>与  {@link  MerchantCustomer }  的分工：那个是按订单实时聚合出来的「谁来过」， 这个是**沉淀下来的关系** —— 有来源、有分层、能挂标签、能被筛出来做活动。 客户页升级为会员页之后，前者只剩跨店总览还在用。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `memberNo` | `string` | 是 | — |
| `personNo` | `string` | 是 | 平台人档号。会员挂人不挂账号 —— 商家看不到，但详情页要用它取来源轨迹 |
| `phoneTail` | `string,null` | 否 | 手机号后四位。**永远不会有完整号** —— 需要它的只有平台申诉处置 |
| `status` | `string` | 是 | `LEAD` 线索（商家录的、本人还没注册，不可触达）/ `ACTIVE` / `BLOCKED` |
| `source` | `string` | 是 | 首次来源 `ORDER`/`SHARE`/`SCAN`/`MANUAL`/`FAVORITE`/`SEARCH` |
| `level` | `string,null` | 否 | `NEW`/`REGULAR`/`LOYAL`/`SLEEPING`。按主体还是按门店算，取决于主体的经营口径 |
| `firstStoreNo` | `string,null` | 否 | 他从哪家门店进来的 |
| `orderCount` | `number` | 是 | — |
| `totalSpentMinor` | `number` | 是 | — |
| `d90OrderCount` | `number` | 是 | — |
| `lastOrderAt` | `number,null` | 否 | — |
| `daysSinceLast` | `number,null` | 否 | — |
| `reachOptOut` | `boolean` | 是 | 买家关掉了这家店的消息。商家看得到状态，看不到原因 |
| `remark` | `string,null` | 否 | — |
| `joinedAt` | `number` | 是 | — |

### MemberDetail

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `member` | [`Member`](#member) | 是 | — |
| `stores` | [`MemberStoreStat`](#memberstorestat)\[\] | 是 | — |
| `sources` | [`MemberSourceItem`](#membersourceitem)\[\] | 是 | — |
| `tags` | [`MemberTag`](#membertag)\[\] | 是 | — |

### MemberMergePreview

合并标签的影响面。**先给商家看这几个数，再让他按** —— 合并不可逆。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `affectedMembers` | `number` | 是 | — |
| `bothTagged` | `number` | 是 | 两个标签都有的人。合并后只保留一条 |
| `referencedActivities` | `number` | 是 | — |
| `applied` | `boolean` | 是 | false = 这只是试算，没有落库 |

### MemberSegment

人群：一组筛选条件，可命名保存、反复用。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `segmentNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `scopeStoreNo` | `string,null` | 否 | 限定门店。空 = 全主体 |
| `rule` | [`MemberSegmentRule`](#membersegmentrule) | 是 | — |
| `lastCount` | `number` | 是 | — |
| `countedAt` | `number,null` | 否 | — |

### MemberSegmentPreview

人群试算。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `count` | `number` | 是 | — |
| `reachable` | `number` | 是 | — |

### MemberSegmentRule

人群条件。**只存号**（标签号/门店号）—— 标签改名之后条件还得成立

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `level` | `string,null` | 否 | — |
| `source` | `string,null` | 否 | — |
| `status` | `string,null` | 否 | — |
| `tagNos` | `string`\[\] | 否 | **取交集**：选两个标签是「都要满足」。界面上写「同时含以下标签」 |
| `lastOrderBefore` | `number,null` | 否 | — |
| `lastOrderAfter` | `number,null` | 否 | — |
| `spentMin` | `number,null` | 否 | — |
| `spentMax` | `number,null` | 否 | — |

### MemberSetting

会员经营口径（P3）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `memberScope` | `string` | 是 | `ENTITY` 按主体（默认）/ `STORE` 按门店 |
| `autoJoinOnOrder` | `boolean` | 是 | 支付成功自动入会。关掉之后只剩手工录入与本人主动加入 |

### MemberSourceItem

一次来源。**谁发的链接**要写出来，否则分享激励没法结算，商家也不知道该谢谁

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `sourceType` | `string` | 是 | — |
| `storeNo` | `string,null` | 否 | — |
| `linkNo` | `string,null` | 否 | — |
| `inviterUserNo` | `string,null` | 否 | — |
| `inviterRole` | `string,null` | 否 | — |
| `operatorNo` | `string,null` | 否 | — |
| `activityNo` | `string,null` | 否 | — |
| `isFirst` | `boolean` | 是 | — |
| `occurredAt` | `number` | 是 | — |

### MemberStats

会员四层人数 + 两个提醒数。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `newCount` | `number` | 是 | — |
| `regularCount` | `number` | 是 | — |
| `loyalCount` | `number` | 是 | — |
| `sleepingCount` | `number` | 是 | — |
| `reachable` | `number` | 是 | 可触达人数（排除线索、拉黑、已退订） |
| `newThisMonth` | `number` | 是 | — |
| `unlinkedBuyers` | `number` | 是 | 未绑手机号、因此没计进会员的买家数 |

### MemberStoreStat

他在某一家门店的往来。单店主体没有这一段

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | — |
| `orderCount` | `number` | 是 | — |
| `totalSpentMinor` | `number` | 是 | — |
| `lastOrderAt` | `number,null` | 否 | — |
| `isFirstStore` | `boolean` | 是 | — |

### MemberTag

会员标签。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `tagNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `tagType` | `string` | 是 | `SYS` 系统算的（只读）/ `MCH` 商家自己的 |
| `status` | `string` | 是 | `ACTIVE` / `DISABLED` 停用（老的还在、新的打不上）/ `MERGED` 已并入别的标签 |
| `count` | `number` | 是 | 打了多少人。服务端 COUNT 出来的，不是冗余列 |

### MerchantApplyReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 拟用店铺名 |
| `subject` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型。个人 → 个体户 → 企业，门槛前低后高 |
| `contactName` | `string` | 是 | 联系人姓名。审核要打电话找人，只有号码没有姓名不合适 |
| `contactPhone` | `string` | 是 | 联系手机号 |
| `category` | `string` | 是 | 主营类目 |
| `desc` | `string` | 是 | 店铺简介 |
| `asPickupPoint` | `boolean` | 否 | 承接自提点：小店既是供给方也是取货点（ADR-005 type=STORE） |
| `qualificationItems` | [`QualificationItem`](#qualificationitem)\[\] | 否 | 结构化资质。**可选**：老版本端上还在只传 `licenses`， 后端对未传该字段的请求跳过执照校验（见 `OpsServiceImpl.requireLicenseIfNeeded`）—— 校验必须晚于能满足它的 UI 上线，否则拦的不是坏商家，是所有人。 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 否 | 期望经营范围（ADR-009）。申请时可空，<b>审核通过时必须确定</b> —— 否则商家上着架却对谁都不可见，且没有任何报错。 |
| `communityNos` | `string`\[\] | 否 | 期望覆盖的社区。scope=COMMUNITY 时审核通过必须非空 |
| `licenses` | `string`\[\] | 否 | 资质图片（营业执照/身份证）。**选填** —— 一期 EDI 不强制。 与下面的结算账户一样，属于**分账主体开户**而不是入驻申请本身（ADR-002）： `usr_merchant_payment` 是独立一张表、有自己的 `apply_status`，就是这个道理。 申请时能传就传，通过后在 B 端补也行 —— 逼一个还没通过审核的人先传营业执照， 只会把人挡在门外。 |
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 否 | 结算账户类型。真实账号由后端持有，C 端与 B 端都不回显（ADR-002 §5）。**选填**，同上 |
| `industry` | `string` | 否 | 行业（`sys_industry.industry`）。 **它决定这家店能不能以小微主体进件** —— 微信的小微白名单是按行业给的， 也是 `points_forced` 默认值的来源。 后端一直在收、库里一直有这一列，但契约没登记、端也没传， 于是 `mch_entity.industry` 恒空：进件时才发现主体类型选错了， 而那时商家已经开完店、上完架。 |

### MerchantApplyReqBody

入驻申请。字段与共享层的 `MerchantApplyReq` 一致，这里只是给契约一个稳定的 DTO 名

类型：`MerchantApplyReq`

### MerchantBrief

商品卡/详情上挂的商家简要信息

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家单号。贯穿商品/订单/评价/结算，是多商家模型的主线（ADR-001） |
| `selfOperated` | `boolean` | 否 | 这单是不是**平台自营**（销售主体是平台）。 **必须显示出来 —— 电商法 §37 要求平台以显著方式区分标记自营业务， 不得误导消费者。这是法定义务，不是产品选择。** 而它同时是资金模式合法性的一部分：归集路径下平台是销售主体， 页面上却让消费者以为在跟商家交易，四流就不一致了（ADR-017 §3.4）。 ⚠️ 自营时**商家信息照常展示**（供货商、产地、门店、评分）—— 要禁的是把销售方指给商家的**表述**，不是商家信息本身。 见 `packages/shared/tests/seller-statement.test.ts` 的禁用词表。 |
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo URL |
| `rating` | `number` | 是 | 综合评分，0–5，保留一位小数。**0 分要配合 `ratingCount` 一起看** |
| `ratingCount` | `number` | 是 | 计入评分的评价条数。 **没有它就分不清「0 分」和「还没人评过」** —— 而这两件事对买家是相反的信号： 一家 0 分的店是被人打差评打出来的，一家没人评过的店只是新开的。 端上按 `ratingCount === 0` 显示「暂无评价」，不要显示 0 颗星。 |
| `verified` | `boolean` | 是 | 是否通过资质认证 |
| `breachCount` | `number` | 是 | 选定报价后不履约的次数。>0 会在报价卡上公示 —— 事后信用替代事前审核 |

### MerchantCoupon

商家自己的券（P4，新模型 `pmt_coupon`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `couponNo` | `string` | 是 | — |
| `title` | `string` | 是 | — |
| `benefitMode` | `string` | 是 | `CASH` 现金 / `PERCENT` 折扣 / `GIFT` 兑换 / `FREE_SHIP` 免运费 |
| `benefitValue` | `number` | 是 | CASH = 面额（分）；PERCENT = **万分比**，8500 表示八五折（顾客付 85%） |
| `benefitCapMinor` | `number,null` | 否 | 折扣封顶（分）。PERCENT 必填 —— 不封顶的敞口随订单金额无限放大 |
| `benefitRef` | `string,null` | 否 | GIFT 兑换哪件商品 |
| `minAmountMinor` | `number,null` | 否 | — |
| `minQty` | `number,null` | 否 | — |
| `scopeType` | `string` | 是 | `ALL` / `STORE` / `CATEGORY` / `GOODS`。**下单抵扣的券只能是前两种** |
| `scopeRefs` | `string`\[\] | 是 | — |
| `scopeDesc` | `string,null` | 否 | — |
| `validityMode` | `string` | 是 | `ABSOLUTE` 固定起止 / `RELATIVE` 领取后 N 天 |
| `startAt` | `number,null` | 否 | — |
| `endAt` | `number,null` | 否 | — |
| `validDays` | `number,null` | 否 | — |
| `issueMode` | `string` | 是 | `CENTER` 领券中心 / `TARGETED` 定向发 / `ACTIVITY` 活动发 |
| `redeemMode` | `string` | 是 | `ORDER` 下单抵扣 / `STORE_CODE` 到店出示核销 |
| `timesTotal` | `number` | 是 | 一张能用几次。>1 就是次卡（豆浆 5 杯） |
| `totalCount` | `number,null` | 否 | 发行量。空 = 不限（只有定向发放允许） |
| `receivedCount` | `number` | 是 | — |
| `perUserLimit` | `number` | 是 | — |
| `budgetMinor` | `number,null` | 否 | — |
| `maxExposureMinor` | `number,null` | 否 | 最大敞口 = 发行量 × 单张最大优惠。 **建券页要显示它** —— 商家填「1000 张 × 20 元」时心里想的是「发 1000 张」， 不是「最多赔两万」。 |
| `status` | `string` | 是 | `ACTIVE` / `PAUSED` 暂停发放（已领的不受影响）/ `ENDED` |

### MerchantCustomer

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `nickname` | `string` | 是 | 客户昵称 |
| `avatar` | `string` | 是 | 客户头像 |
| `orderCount` | `number` | 是 | 在本店的累计下单次数 |
| `totalSpentMinor` | `number` | 是 | 在本店的累计消费额（最小货币单位） |
| `lastOrderAt` | `number` | 是 | 最近一次下单时间 |
| `daysSinceLast` | `number` | 是 | 距上次下单天数 |
| `silent` | `boolean` | 是 | 沉默客户：曾经常来、最近没来。**这是店主唯一能立刻行动的信号** |
| `source` | [`TrafficSource`](#trafficsource) | 是 | 客流来源：他是你自己带来的，还是平台分配的 |

### MerchantLoginReqBody

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `grantType` | [`GrantType`](#granttype) | 是 | 登录方式。**商家池与 C 端用户池是两套账号**，同一手机号登两端是两个身份 |
| `principal` | `string` | 是 | `WX_MINI`: wx.login code；`PHONE_OTP`: 手机号 |
| `credential` | `string` | 否 | `PHONE_OTP`: 验证码 |
| `agreed` | `boolean` | 否 | 是否勾选了用户协议与隐私政策 —— 注册的合规前置，服务端要留痕。 登录页一直在发（`{ ...req }` 把 `LoginReq.agreed` 带了出去）， **漏的是这里没声明**，于是生成的 OpenAPI 里没有它，而后端 `LoginReq` 有。 这类漏声明比漏发更难发现：联调时一切正常，直到有人照着 spec 写另一个客户端。 |

### MerchantLoginResp

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `token` | `string` | 是 | 访问令牌。**商家池与 C 端用户池是两套账号**，token 不通用 |
| `merchant` | [`MerchantProfile`](#merchantprofile) | 是 | 商家档案 |

### MerchantPlan

我的增值包（B-11.13，`GET /biz/plan`）。 <p>与运营端那份（`MerchantPlanRow`）刻意是两个类型：运营看的是「这家商家买了什么」， 商家看的是「我有什么、还差什么、能不能试」。挤成一个的结果是商家侧要接一堆 用不上的字段（授予方、降级时间、额度来源），而它们每一个都会被端上误读成给他看的。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `planCode` | `string` | 是 | 档位码。**文案用 `planName`，不要按 code 自己映射** —— 运营改了名端上不会跟着变 |
| `planName` | `string` | 是 | 档位显示名（「成长版」） |
| `status` | [`PlanStatus`](#planstatus) | 是 | ACTIVE 生效中 / GRACE 宽限期（**能力全保留**，7 天）/ EXPIRED 已过期并降级。 <p>GRACE 要显示成「即将到期，请尽快续费」而**不是**「已失效」： 他的门店、子账号、跨店数据一样都没少，这时候说失效只会让他打客服电话。 |
| `startAt` | `number,null` | 否 | 订阅起始时间（毫秒）。null = 还没有过任何订阅 |
| `expireAt` | `number,null` | 否 | 到期时间（毫秒）。null = 不到期（免费档） |
| `storeQuota` | `number` | 是 | 生效门店额度 |
| `storeUsed` | `number` | 是 | 已用门店数。**后端算，只数营业中的店** —— 端上自己数会与建店那道闸的口径分岔 |
| `staffQuota` | `number` | 是 | 生效子账号额度 |
| `staffUsed` | `number` | 是 | 已用子账号数（不含老板本人） |
| `crossStoreStats` | `boolean` | 是 | 有没有跨店总览与对比 |
| `trialUsed` | `boolean` | 是 | 试用是否已用过。**一主体一次，永不回退** |
| `trialTier` | `string,null` | 否 | 可试用的目标档位码；null = 现在不能试用（已用过 / 已经是付费档 / 平台没配试用）。 <p>端上按它决定要不要显示「免费试用」按钮 —— 不要自己用 `planCode === 'FREE' && !trialUsed` 推：那会漏掉「平台把试用天数配成 0」这种情况。 |
| `trialDays` | `number,null` | 否 | 试用天数，配合 `trialTier` 显示「免费试用 14 天」 |
| `suspendedStores` | `string`\[\] | 是 | 因降级被压成只读的门店名。 <p>**只含平台压的那几家**，商家自己停用的不在里面 —— 页面要写明是「哪几家」：只说「部分门店已停用」，他得自己一家家点开去找。 |
| `tiers` | [`PlanTier`](#plantier)\[\] | 是 | 三档对比，顺序即展示顺序（后端按 sort 排好） |

### MerchantPointAccount

商家的积分成本视图。**单位是钱，不是分**。 商家只感知**一件事**：开了积分，每笔订单要付一笔发分服务费。 他**看不到**用户抵了多少分、平台补了多少、资金池 —— 对他而言订单就是全额， 收到的是「订单金额 − 各项费用」（V34）。 所以这里没有 income/net：商家侧不存在「积分兑付进账」这个概念。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `periodExpenseMinor` | `number` | 是 | 本期发分服务费支出（分）。**商家唯一感知到的积分成本** |
| `period` | `string` | 是 | 当前账期标识，如 `2026-08` |
| `enabled` | `boolean` | 是 | 本店积分是否生效 —— 全局 AND 社区 AND 主体非小微 AND 本店开关 |
| `disabledReason` | `string` | 否 | 不生效的原因，直接展示给商家。 小微主体要说「升级为个体工商户后可开启」，不能说「本店未开启积分」—— 后者会让商家去开一个他根本开不了的开关。 |
| `forced` | `boolean` | 是 | 平台按行业强制开，商家不可自行关闭 |

### MerchantPointsRecord

商家的一条发分服务费记录：一单一条，来自 `stl_bill.points_fee_minor`

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleNo` | `string` | 是 | 结算单号 |
| `subOrderNo` | `string` | 是 | 关联子单，商家据此对到具体订单 |
| `points` | `number` | 是 | 本单发放的积分数 |
| `feeMinor` | `number` | 是 | 本单的发分服务费（分）。**这是商家唯一感知到的积分成本** |
| `period` | `string` | 是 | 账期 `YYYYMM` |
| `at` | `number` | 是 | 计提时间（支付成功时），不是分账时间 —— 两者相差一个售后期 |

### MerchantProfile

登录后的商家会话

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家单号 |
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo |
| `status` | [`MerchantStatus`](#merchantstatus) | 是 | 入驻审核状态。非 ACTIVE 时 B 端只能看到入驻流程页 |
| `subject` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型 |
| `tier` | [`MerchantTier`](#merchanttier) | 是 | 商家分层。一期恒为 SMALL |
| `phone` | `string` | 是 | 登录手机号，也是商家账号的主标识 |
| `isPickupPoint` | `boolean` | 是 | 是否承接自提点 —— 决定 B 端是否出现「履约台」入口（ADR-005） |
| `pickupNo` | `string` | 否 | 承接的自提点单号。`isPickupPoint=true` 时有值 |
| `rejectReason` | `string` | 否 | 驳回原因，status=REJECTED 时有值 |
| `loginBy` | [`GrantType`](#granttype) | 否 | 本次会话的登录方式。第三方登录且 phone 为空时，要引导补绑手机号 |
| `fundsMode` | [`FundsMode`](#fundsmode) | 否 | 资金路径。**B 端价格字段叫什么由它决定** —— 归集（钱进平台账户）下平台是销售主体、最终售价平台定，商家填的是「期望收购价」； 直连下他自己就是销售主体，那就是「售价」。 判据用它而不是门店的 `businessMode`：与积分能力同一根轴 —— **责任跟着钱走**。 还没进件的申请人为空：那时资金路径尚未确定， 猜一个默认值会让他在入驻页看到一个还轮不到他的字段名。 |

### MerchantRole

一个角色：6 个平台预置（只读）+ 商家自定义（V71）。 **权限码的中文说明由后端给**（`permLabels`），前端不抄一份 —— 抄的那份迟早与权限码本身漂开，而漂开的表现是 「界面写着能改库存，实际打不通」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `roleCode` | `string` | 是 | 角色码。预置是 `OWNER`/`MANAGER`… ，自定义是生成的业务键 —— **别拿它给店主看** |
| `name` | `string` | 是 | 显示名。预置角色也有 —— 别拿 `MANAGER` 直接给店主看 |
| `builtin` | `boolean` | 是 | 平台预置：**只读**，要改先「复制为自定义角色」 |
| `perms` | `string`\[\] | 是 | 这个角色带的权限码。老板那行是 `["*"]`（全部），别按长度当权限数 |
| `permLabels` | `string`\[\] | 是 | 与 `perms` 一一对应的中文短说明 |
| `usedBy` | `number` | 是 | 几个人在用。删除按钮据此禁用，并且要显示出来 |

### MerchantSpecDim

「我的规格」里的一条自建维度。 <p>与  {@link  SpecTemplate }  的差别是**视角**：那个回答「建品时能挑什么」， 这个回答「我拥有什么、能改什么、动它会影响多少」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `dimNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `valueCount` | `number` | 是 | 这个维度下的取值数（含平台档位 + 自己加的） |
| `usedCount` | `number` | 是 | 用在几件商品上。**按规格组名统计** —— 存量商品的规格快照里只有名字， 没有维度编号（那个字段是后加的），按编号统计的话老商品一件都算不进来， 而「停用它会影响什么」问的恰恰是历史。 |
| `status` | `ACTIVE` \| `ARCHIVED` | 是 | — |
| `dimUsed` | `number` | 是 | 已建 / 上限。摆出来，而不是等他建到第 11 个才被拒 |
| `dimQuota` | `number` | 是 | — |
| `valueQuota` | `number` | 是 | — |
| `values` | [`SpecOption`](#specoption)\[\] | 是 | — |

### MerchantStaff

商家员工（B 端账号 + 他在各门店的角色）。 <p>**逐店授权**：A 店店长可以同时是 B 店店员 —— 老店的店长去新店帮忙， 但新店不归他管，这是小连锁的常态。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `mchAccountNo` | `string` | 是 | 商家账号号。**不叫 staffNo** —— 那个名字被平台运营占着，两者是不同的人 |
| `displayName` | `string` | 否 | 姓名（老板自己写的，如「小张」）。**认人靠它** —— 一列号码谁也分不清。为空时端上回落 `loginPhone`。 |
| `loginPhone` | `string` | 是 | 登录手机号，**完整、不脱敏**。 它**就是这个员工的登录用户名**（手机号 + 验证码，没有密码）—— 老板要能核对「他用哪个号登录」、人换号时要能改，脱敏之后这两件事都做不了。 |
| `isOwner` | `boolean` | 是 | 老板。**不受门店授权限制**，他的店都归他管 |
| `status` | [`StaffStatus`](#staffstatus) | 是 | ACTIVE / DISABLED |
| `roles` | [`StoreRole`](#storerole)\[\] | 是 | 他在各门店的角色。老板为空 —— 不是"没授权"，是"不需要授权" |

### MerchantStats

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `todayOrders` | `number` | 是 | 今日订单数（自然日，按市场本地时区切分） |
| `todayGmvMinor` | `number` | 是 | 今日成交额（最小货币单位） |
| `monthOrders` | `number` | 是 | 本月订单数 |
| `monthGmvMinor` | `number` | 是 | 本月成交额（最小货币单位） |
| `currency` | [`CurrencyCode`](#currencycode) | 是 | 统计口径的币种 |
| `rating` | `number` | 是 | 店铺综合评分，0–5 |
| `ratingCount` | `number` | 是 | 参与评分的评价条数 |
| `ownedTrafficRate` | `number` | 是 | 自带客流占比（trafficSource=MERCHANT_OWNED），决定费率档（ADR-004 §6） |

### MerchantStatus

商家在 B 端的**综合状态**：既要表达「还没入驻成功」，也要表达「已经在经营」。 ⚠️ 它是一个**展示用的合并视图**，底下是两条互不相干的生命周期：   · 审核（`MerchantApplyStatus`）—— 商家还不存在时的事，归 `usr_merchant_apply`   · 经营（ACTIVE / SUSPENDED）—— 商家已存在之后的事，归 `usr_merchant.status` B 端首页要在一个地方回答「我现在能不能做生意」，所以合并； 但**库里绝不能合并** —— 一旦合并，「驳回一份申请」和「封禁一家店」就共用取值， 而这两件事的操作人、审计口径、可逆性全都不同。

枚举取值：

- `NONE`
- `APPLYING`
- `REVIEWING`
- `REJECTED`
- `PENDING_LICENSE`
- `ACTIVE`
- `SUSPENDED`

### MerchantSubject

枚举取值：

- `NATURAL_PERSON`
- `INDIVIDUAL`
- `ENTERPRISE`

### MerchantTier

商家分层。为「流量起来后引入大商家」预留，一期只用 SMALL（ADR-004 §7）

枚举取值：

- `SMALL`
- `MEDIUM`
- `LARGE`

### MerchantTodo

工作台待办。**数字即入口** —— 商家打开 App 只想知道「有几件事要我做」

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `toShip` | `number` | 是 | 待发货单数（EXPRESS 履约） |
| `toDeliver` | `number` | 是 | 待自送单数（商家自送履约） |
| `toStock` | `number` | 是 | 待备货单数（自提单已付款，货还没送到自提点）。**按门店算**，这是供货方的活。 与  {@link  toPick }  是同一批单的两头，**两个数不相等**： 买家常常选别家的自提点。`toPick` 按自提点算（我要在点上分多少）， 这一个按门店算（我要送出去多少）。 |
| `toVerify` | `number` | 是 | 待核销单数（自提到货、买家还没来取） |
| `toPick` | `number` | 是 | 待分拣单数（到货后按商品汇总点数） |
| `afterSale` | `number` | 是 | 待处理售后单数 |
| `toReply` | `number` | 是 | 待回复的评价数 |
| `quotable` | `number` | 是 | 可报价的求团需求数 |

### Message

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `messageNo` | `string` | 是 | 消息单号 |
| `type` | [`MessageType`](#messagetype) | 是 | 消息分类，决定它落在哪个 tab |
| `title` | `string` | 是 | 标题（列表页展示） |
| `body` | `string` | 是 | 正文 |
| `link` | `string` | 否 | 点进去要跳哪（订单详情/商品/团），已是完整页面路径带参 |
| `read` | `boolean` | 是 | 是否已读。未读数按 type 分别统计 |
| `at` | `number` | 是 | 消息产生时间 |

### MessageType

站内消息。 三类分开是因为**用户对它们的期待完全不同**：交易类必须看到（到货了要去取）， 活动类可以错过，系统类是通知。混在一个列表里，交易消息会被活动消息淹没。

枚举取值：

- `TRADE`
- `MARKETING`
- `SYSTEM`

### MyQualifications

「我的资质」这一页要的三份数据。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `items` | [`Qualification`](#qualification)\[\] | 是 | — |
| `grantedCodes` | `string`\[\] | 是 | 已获授权的类目码。端上据此把「已解锁 / 待授权」标出来 |
| `catalog` | [`AuthCodeInfo`](#authcodeinfo)\[\] | 是 | — |

### Order

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 订单单号 |
| `status` | [`OrderStatus`](#orderstatus) | 是 | 订单状态。粗粒度；售后细节见 `afterSale` |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式，下单时锁定 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 订单行。含赠品行（`isGift`，价格为 0） |
| `amount` | [`OrderAmount`](#orderamount) | 是 | 金额明细 |
| `verifyCode` | `string` | 否 | 自提码 / 核销码 |
| `redeemCode` | `string` | 否 | VIRTUAL：兑换码；CARD：卡号 |
| `pickupNo` | `string` | 否 | PICKUP：自提点单号 |
| `pickupName` | `string` | 否 | PICKUP：自提点名称快照 |
| `expressNo` | `string` | 否 | EXPRESS：快递单号，发货后才有 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `createdAt` | `number` | 是 | 下单时间 |
| `payDeadlineAt` | `number` | 否 | 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 |
| `timeline` | [`OrderTimelineNode`](#ordertimelinenode)\[\] | 是 | 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 |
| `idempotencyKey` | `string` | 否 | 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 |
| `buyerNickname` | `string` | 否 | 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 |
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 ⚠️ **后端叫 `payOrderNo`，库里是 `ord_order.order_no`** —— 三处三个名字。 按这个名去后端或库里找会找不到（2026-08-17 人工测试时撞到）。 |
| `subOrders` | [`Order`](#order)\[\] | 否 | **仅支付视角**：这次付款覆盖的各商家订单。订单视角为空。 后端 `OrderVO` 一直在发（同一个结构承担订单/支付两种视角）， 端上此前没声明 —— 于是收银台是整条拆单链路里**唯一哑掉的一屏**： 购物车说会拆 2 单、确认页说会拆 2 单、订单详情各自标着商家， 中间付款那一步却只有一个总额。 |

### OrderAmount

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsMinor` | `number` | 是 | 商品小计（最小货币单位），不含运费与优惠 |
| `freightMinor` | `number` | 是 | 运费 |
| `discountMinor` | `number` | 是 | 优惠合计（券 + 活动），正数表示减掉多少 |
| `payableMinor` | `number` | 是 | 应付：`goodsMinor + freightMinor - discountMinor - pointsDeductMinor` |
| `paidMinor` | `number` | 是 | 实付。未支付时为 0；称重差价补退后与 payableMinor 可能不等 |
| `weighAdjustMinor` | `number` | 否 | 称重差价（正=补款 负=退款），仅 FRESH |
| `pointsDeductMinor` | `number` | 是 | 积分抵扣的金额 |
| `pointsUsed` | `number` | 是 | 本单使用的积分数 |
| `pointsEarn` | `number` | 是 | 本单可获得的积分（订单完成时才真正入账） |
| `currency` | [`CurrencyCode`](#currencycode) | 是 | 下单时的货币，订单一经创建即锁定，不随用户切市场变化 |

### OrderItem

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `merchantNo` | `string` | 是 | 所属商家 —— 分账与「我买过的商家」都依赖它落在订单行上 |
| `skuNo` | `string` | 是 | SKU 单号 |
| `title` | `string` | 是 | 下单时的商品标题**快照**。商品后续改名不影响历史订单 |
| `cover` | `string` | 是 | 封面图快照 |
| `spec` | `string` | 是 | 规格文案快照 |
| `price` | `number` | 是 | 成交单价（最小货币单位）快照。改价不追溯已成交订单 |
| `qty` | `number` | 是 | 数量 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态 |
| `nominalGram` | `number` | 否 | FRESH 且按重计价：下单时的标称重量（克） |
| `weighed` | `boolean` | 否 | 是否已实际称重。称重后按实重产生差价，见 `OrderAmount.weighAdjustMinor` |
| `isGift` | `boolean` | 否 | 赠品行：价格为 0，不参与计价，履约时随单发出 |
| `points` | `number` | 否 | 该商品每件赠送的积分 |

### OrderReceiver

收件人。下单时固化在子订单上，**不是用户当前的地址簿条目**。 三端共用：C 端订单详情、B 端配送/发货、平台端查单。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 否 | 收货人姓名。取不到时为空 —— 空就是空，不要回落成「顾客」 |
| `phone` | `string` | 否 | 脱敏程度由后端定，见 `Order.receiver` 的说明 |
| `address` | `string` | 否 | 省市区 + 详细，拼好的一行 |

### OrderStatus

枚举取值：

- `WAIT_PAY`
- `WAIT_OFFLINE_PAY`
- `PAID`
- `FULFILLING`
- `COMPLETED`
- `CANCELLED`
- `REFUNDED`

### OrderTimelineNode

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `status` | [`OrderStatus`](#orderstatus) | 是 | 流转到的状态 |
| `label` | `string` | 是 | 展示文案，如「已到货，请到自提点取货」。后端下发已本地化 |
| `at` | `number` | 是 | 发生时间 |

### Partial_Record_MarketId_number

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `CN` | `number` | 否 | — |
| `AE` | `number` | 否 | — |
| `US` | `number` | 否 | — |

### PaymentApplyStatus

支付**进件**状态（`MerchantPayment.applyStatus`）。 ⚠️ 此前叫 `ApplyStatus`，与入驻审核的 `ApplyStatus` 同名不同义 —— `ACTIVE`/`FROZEN` 两个值就是证据：审核不会有这两个态。

枚举取值：

- `NONE`
- `APPLYING`
- `ACTIVE`
- `REJECTED`
- `FROZEN`

### PaymentApplyment

收款进件状态（每通道一条）。 <p><b>它与入驻审核是两件事</b>：入驻过了店就能开、货能上架， 但通道没批就收不了钱。合成一个「入驻进度」，商家问「我能收钱了吗」就没法回答。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payChannel` | `string` | 是 | 通道码，如 WECHAT |
| `channelName` | `string` | 是 | 通道展示名。取服务端的，端上不要再维护一份翻译 |
| `applyStatus` | [`PaymentApplyStatus`](#paymentapplystatus) | 是 | NONE / APPLYING / ACTIVE / REJECTED / FROZEN |
| `canReceiveMoney` | `boolean` | 是 | 这个通道现在能不能收钱。 **照着它显示，不要自己去比 applyStatus** —— 比错的表现是 「显示能收钱但收不了」，而这种错要到第一笔订单才暴露。 |
| `payMerchantNo` | `string` | 否 | 收款商户号业务键，通过后才有。门店挂收款号引用的就是它 |
| `subMchidMasked` | `string` | 否 | 二级商户号掩码。完整号不回显 |
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 否 | 结算账户形态：小微打个人（PERSONAL_BANK_CARD），其余打对公（MERCHANT_ID） |
| `settleAccountMasked` | `string` | 否 | 结算账号掩码。**明文永不回显**，包括给商家自己（ADR-002 §5） |
| `rejectReason` | `string` | 否 | 驳回原因。驳回时必有 —— 没有原因商家只能反复重提 |
| `missing` | `string`\[\] | 是 | 还缺哪些资料（settleAccount / licenses / settleAccountType）。空 = 资料齐了在等通道 |
| `submitted` | `boolean` | 是 | **有没有真的发给通道过。** <p>没有它，`APPLYING` 同时表示两件相反的事：入驻通过时建的占位（商家还没填过 任何东西）与「已发给通道、在等回执」。都显示成「审核中」的话，新商家读到的是 球在平台，而球其实在他自己脚下 —— 这正是「不能收钱」最常卡死的一步。 |
| `appliedAt` | `number` | 否 | 提交进件的时间。没提交过为空 |
| `activatedAt` | `number` | 否 | 通道开户完成的时间 —— 从这一刻起才真的能收钱 |
| `storeNo` | `string` | 否 | 这条进件是**为哪家门店**做的；空 = 主体级默认号。 多门店商家会有多条「微信 · 已开通」，不显示门店就分不清哪条是哪家店 —— 等于让他猜自己的钱打进了哪张卡。 |

### PermOption

自定义角色**可以勾的一个权限点**。 为什么不让端上「把预置角色的权限并起来」当选项：那个并集**少一条** —— `biz:finance` 只有老板有，而老板那行是 `*`。于是后端明明收这个码， 界面上却勾不到，看起来像功能没做。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | 权限码，如 `biz:stock`。**只用于提交，不展示** |
| `label` | `string` | 是 | 中文短说明，兜底用。端上自己有中/英/阿三份文案 |

### PickingRow

分拣单的一行：按商品汇总，团长照着这个点数

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `skuNo` | `string` | 是 | SKU 单号。分拣按 SKU 汇总，不是按商品 |
| `title` | `string` | 是 | 商品标题 |
| `cover` | `string` | 是 | 封面图，照着点数时用来认货 |
| `spec` | `string` | 是 | 规格文案 |
| `totalQty` | `number` | 是 | 该 SKU 在本自提点的总件数（含赠品） |
| `buyers` | `object`（见下）\[\] | 是 | 谁要几件 |

`buyers[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `nickname` | `string` | 是 | — |
| `qty` | `number` | 是 | — |
| `orderNo` | `string` | 是 | — |

### Pickup

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | 自提点单号 |
| `name` | `string` | 是 | 自提点名称（通常是承接店铺的店名） |
| `address` | `string` | 是 | 自提点地址 |
| `distance` | `number` | 是 | 距当前社区的距离（米），服务端算好下发 |
| `hostMerchantNo` | `string` | 是 | 承接这个自提点的商家（ADR-005：PickupPoint.type=STORE，承接方是入驻商家而非团长） |
| `hostName` | `string` | 是 | 承接商家的店名 |
| `hostAvatar` | `string` | 是 | 承接商家的头像/门头图 |
| `openHours` | `string` | 是 | 营业时间文案，如 `08:00-21:00`。展示用，不参与计算 |
| `arrivalDesc` | `string` | 是 | 到货时间说明，如「次日 18:00 后到」。影响用户选不选这个点 |
| `latE6` | `number,null` | 否 | 取货点坐标（gcj02，E6）。**可能为空** —— 存量点是手填地址建的。 买家要拿着它导航过去，没有就只能显示地址文本。 |
| `lngE6` | `number,null` | 否 | — |

### PickupCandidate

门店可引用的取货点候选（P1）：范围内的常驻点 + 本店自建的点

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `address` | `string,null` | 否 | — |
| `type` | [`PickupPointType`](#pickuppointtype) | 是 | — |
| `status` | `string` | 是 | ACTIVE / PENDING / REJECTED …；本店自建的 PENDING 点可引用，别家的不行 |
| `communityNo` | `string` | 是 | — |
| `communityName` | `string,null` | 否 | — |
| `ownerStoreNo` | `string,null` | 否 | STORE 点的承接门店；= 本店即「我自建的」 |
| `rejectReason` | `string,null` | 否 | — |

### PickupFeeMode

自提点计费方式。**与 ops-web 的 `PickupFeeMode` 同值** —— 费率线下逐点协商，故两种都留

枚举取值：

- `NONE`
- `PER_ITEM`
- `RATE`

### PickupOverview

自提点履约总览（后端 `GET /biz/pickup/overview`）。 承接方最关心的三个数：还有几单没人来取、今天到了几批、这些活挣了多少服务费。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | 自提点单号 |
| `pickupName` | `string` | 是 | 自提点名称 |
| `pendingVerify` | `number` | 是 | 待核销单数 —— 到货了还没人来取的 |
| `arrivedBatches` | `number` | 是 | 今日到货批次 |
| `serviceFeeMinor` | `number` | 是 | 累计履约服务费（最小货币单位） |

### PickupOwnerType

自提点承接方类型。与  {@link  PickupPointType }  不同：那个说「是什么点」，这个说「谁在承接」

枚举取值：

- `MERCHANT`
- `USER`
- `PLATFORM`

### PickupPoint

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | 自提点单号 |
| `type` | [`PickupPointType`](#pickuppointtype) | 是 | 自提点由谁承接。**三档，各自的费用规则完全不同**（2026-08-06 定）：   · STORE    商家自己的门店 —— 商家自行解决，平台不收履约服务费   · NEIGHBOR 团发起人家里 —— **零报酬**（ADR-005），有报酬就是团长招募换个名字   · PLATFORM 平台提供的点 —— 收履约服务费，**费率线下逐点协商，由运营平台录入** |
| `ownerType` | [`PickupOwnerType`](#pickupownertype) | 是 | 承接方所属账号池 |
| `ownerNo` | `string` | 是 | 承接方单号，按 ownerType 落在 merchantNo 或 cUserNo 上 |
| `scope` | [`PickupScope`](#pickupscope) | 是 | 常驻 \| 团粒度（一团一销） |
| `groupNo` | `string` | 否 | type=NEIGHBOR 时必填：这个点只服务这一个团 |
| `name` | `string` | 是 | 自提点名称 |
| `address` | `string` | 是 | 展示地址。**成团前只到楼栋，付款后才给完整门牌**（B13）—— 未成团的团不该暴露发起人住址。 |
| `timeSlot` | `string` | 否 | 约定取货时段。邻居家不能一直堆着货（B15） |
| `feeMode` | [`PickupFeeMode`](#pickupfeemode) | 是 | 计费口径。**必须显式标出用哪一种** —— 库里按件与按率两列长期并存， 没有判别列的话结算侧只能猜，猜错就是给自提点少付或多付钱。 之所以两种都留：费率是**线下逐点协商**的，有的点谈成按件、有的谈成按成交额抽成， 硬统一成一种会让运营在谈判里没有筹码。 |
| `serviceFeePerItemMinor` | `number` | 是 | feeMode=PER_ITEM 时的按件服务费。STORE 与 NEIGHBOR 恒为 0 |
| `serviceFeeRate` | `number` | 是 | feeMode=RATE 时的费率（万分比）。STORE 与 NEIGHBOR 恒为 0 |

### PickupPointType

自提点类型。对应 `cmt_pickup_point.type`。 ⚠️ 此前只以裸字面量的形式内联在 `PickupPoint.type` 里 —— 值是对的， 但**没有单一声明处**：对账工具扫不到它，各处写的是裸字符串。 `CATEGORY_TYPE` 出事前正是这个状态（见 docs/technical/枚举统一方案.md §2「C 无主」）： 今天没 bug，但下一个人在别处再写一次时，没有任何东西会拦住他写错。

枚举取值：

- `STORE`
- `NEIGHBOR`
- `PLATFORM`

### PickupRef

门店引用的取货点。status 来自 cmt_pickup_point：只有 ACTIVE 参与买家侧

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `address` | `string,null` | 否 | — |
| `type` | [`PickupPointType`](#pickuppointtype) | 是 | — |
| `status` | `string` | 是 | — |

### PickupScope

自提点作用域：常驻 / 团粒度（一团一销）

枚举取值：

- `PERMANENT`
- `GROUP_INSTANCE`

### PlanStatus

增值包订阅状态。 `GRACE`（宽限期，7 天）**能力全保留** —— 到期当天就压店的话， 一次忘记续费等于让他的店在客户面前消失，而他往往正在门店里忙。

枚举取值：

- `ACTIVE`
- `GRACE`
- `EXPIRED`

### PlanTier

档位对比的一行。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `planCode` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `storeQuota` | `number` | 是 | — |
| `staffQuota` | `number` | 是 | — |
| `crossStoreStats` | `boolean` | 是 | — |
| `trialDays` | `number` | 是 | 0 = 这一档不提供试用 |
| `current` | `boolean` | 是 | 是不是他现在用的那一档 |

### Poster

真海报（B-11.11 补，2026-08-24）：封面图/店名/价格/小程序码合成的一张 PNG， 能直接发朋友圈——`ShareKit.posterUrl` 一期只是落地页链接，不是图。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `imageBase64` | `string,null` | 是 | PNG 的 base64（不含 data: 前缀）。生不出来（商家异常）时为 null |

### Promotion

促销：买 N 送 M。 语义：购买数量达到 N 件，赠送 M 件 —— 用户**付 N 件的钱，收到 N+M 件**。 赠品不进计价（价格为 0），只作为订单里的独立行存在，履约时随单发出。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | `string` | 是 | 促销类型。目前只有买 N 送 M 一种 |
| `buyN` | `number` | 是 | 购买件数门槛 N |
| `giftM` | `number` | 是 | 赠送件数 M |
| `giftGoodsNo` | `string` | 否 | 赠品商品号；不填则赠同款 |
| `giftTitle` | `string` | 否 | 赠品展示名（后端下发已本地化） |

### Qualification

已登记的一张资质（`mch_qualification`）。 与  {@link  QualificationItem }  的差别：那个是**入驻申请时提交的**（还没入库）， 这个是**已经登记在案的**（有编号、有状态、能被上架校验读到）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `qualNo` | `string` | 是 | — |
| `entityNo` | `string` | 否 | 归属主体。端上其实用不到（只会看自己的），但后端在发 —— 声明出来免得契约守卫把它算成缺口 |
| `qualType` | [`QualificationType`](#qualificationtype) | 是 | — |
| `qualName` | `string` | 是 | 证件名，如「食品经营许可证」。上架校验拿它与类目门槛的文案比对 |
| `qualNumber` | `string,null` | 否 | — |
| `imageUrl` | `string,null` | 否 | — |
| `expireAt` | `number,null` | 否 | 有效期截止（毫秒）。**空 = 长期有效**，与「已过期」是两回事 |
| `status` | `string` | 是 | VALID / EXPIRED / REVOKED |

### QualificationItem

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | [`QualificationType`](#qualificationtype) | 是 | 资质类型码 |
| `code` | `string` | 是 | 证照编号 |
| `imageUrl` | `string` | 是 | — |
| `expireAt` | `number,null` | 是 | 有效期截止（毫秒）。**长期有效传 `null`** —— 不要用 0 或一个很大的数字冒充：过期扫描会把前者当成已过期、 后者当成永不过期，两种都错且都不报错。 |
| `issuer` | `string` | 否 | — |

### QualificationType

资质类型码。取值同后端 `mch_qualification.qual_type`。 ⚠️ **`BUSINESS_LICENSE` 是入驻校验的判据** —— 需要执照的档位必须含它， 改名会让那条校验静默失效（找不到就当没传，然后放行）。

枚举取值：

- `BUSINESS_LICENSE`
- `FOOD_PERMIT`
- `FOOD_WORKSHOP`
- `OTHER`

### Quote

商家对某个需求单的报价。一个需求单可多家报价，由发起人挑。 **报价不做事前审核，防加价靠三层机制**（见 docs/technical/ADR/ADR-003）：   1. 锁价 —— 被选定后 `locked`，下单一律用快照价，系统层面加不了价   2. 公示 —— 每次改价都写进 `revisions` 并对所有邻居可见，谁涨价谁被看见   3. 信用 —— 选定后不履约计入商家 `breachCount` 与评分，累计则限制报价资格

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `quoteNo` | `string` | 是 | 报价单号 |
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 报价商家。`breachCount` 会在报价卡上公示 —— 事后信用替代事前审核 |
| `priceMinor` | `number` | 是 | 报价单价 |
| `minCount` | `number` | 是 | 起订量：低于这个数商家不接 |
| `desc` | `string` | 是 | 报价说明：规格、材质、是否含安装等 |
| `validUntil` | `number` | 是 | 报价有效期。过期后不可被选定 —— 报价不能无限期挂着 |
| `createdAt` | `number` | 是 | 报价时间 |
| `chosen` | `boolean` | 是 | 是否被发起人选定。一个需求单只有一条为 true |
| `revisions` | [`QuoteRevision`](#quoterevision)\[\] | 是 | 改价历史，公示给所有人。空数组 = 从未改过价 |
| `locked` | `boolean` | 是 | 已锁价：选定后为 true，此后价格不可变 |

### QuoteReq

报价。四个字段名全部按后端 `BizQuoteController.QuoteReq` 对齐 —— 此前前端发的是 `{priceMinor, minCount, desc}`，后端收的是 `{unitPriceMinor, minQty, note, validDays}`，**没有一个对得上**，联调必 400。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `unitPriceMinor` | `number` | 是 | 单价（最小货币单位）。名字带 unit 是有意义的：报的是单价不是总价 |
| `minQty` | `number` | 是 | 起订量 |
| `note` | `string` | 是 | 报价说明：规格、材质、是否含安装等，供发起人比价 |
| `validDays` | `number` | 否 | 报价有效期（天）。后端不传时默认 7 天 —— 报价不能无限期挂着 |

### QuoteRevision

一次改价的留痕

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `priceMinor` | `number` | 是 | 改价后的单价（最小货币单位） |
| `at` | `number` | 是 | 改价时间 |

### RateCard

费率卡（后端 `GET /biz/settle/rate-card`）。 ⚠️ 费率是**万分比整数**（后端 `platformRate / 100.0` 才是百分数）—— 直接当百分数显示会把 2% 显示成 200%。 语义同样要照搬：**费率以下单时快照为准，调整不影响历史订单** —— 不写清楚的话，商家会以为平台调价能追溯到已成交的单。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantOwnedRate` | `number` | 是 | 自带客流费率（万分比）。商家自己带来的客人，平台抽成低 |
| `platformRate` | `number` | 是 | 平台客流费率（万分比）。平台分发带来的订单 |
| `note` | `string` | 是 | 费率说明文案。**须写明「以下单时快照为准，调整不影响历史订单」** |

### ReachPlan

群发试算结果（P7）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `matched` | `number` | 是 | — |
| `reachable` | `number` | 是 | — |
| `skips` | `object`（见下）\[\] | 是 | — |

`skips[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reason` | `string` | 是 | — |
| `count` | `number` | 是 | — |

### ReachResult

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `taskNo` | `string` | 是 | — |
| `sent` | `number` | 是 | — |
| `skipped` | `number` | 是 | — |
| `skips` | `object`（见下）\[\] | 是 | — |

`skips[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reason` | `string` | 是 | — |
| `count` | `number` | 是 | — |

### RecognizeGoodsReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `imageUrl` | `string` | 是 | 待识别的商品图 URL（先走 upload/image 拿到）。返回识别出的标题与类目建议 |

### Record_string_boolean

类型：`object`

### Record_string_number

类型：`object`

### Record_string_string

类型：`object`

### Region

行政区划的一级（`/biz/regions`）。省 2 / 市 4 / 区 6 / 街道 9 / 村 12 位

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | 统计用区划代码：省 2 / 市 4 / 区县 6 / 街道 9 / 村 12 位。**前缀即层级**，下级码以上级码开头。 商家补录的村是 `街道码 + M + 2 位`，字母保证与官方纯数字码永不冲突 |
| `parentCode` | `string` | 否 | 上级区划码。省级为空 —— 逐级选择器据此判断自己在不在顶层 |
| `level` | `string` | 是 | PROVINCE / CITY / DISTRICT / STREET / VILLAGE（村委会·居委会，第五级） |
| `latE6` | `number,null` | 否 | 中心点（gcj02，E6）。**可能为空** —— 全国 62 万条村级里只有批量补录命中的那部分有坐标， 端上据此决定是直接用，还是临时去地图上搜一次。 |
| `lngE6` | `number,null` | 否 | — |
| `name` | `string` | 是 | 本级名称，**不含上级**（「西湖区」不是「杭州市 / 西湖区」）。要整条路径的地方自己拼 |
| `enabled` | `boolean` | 是 | 是否启用。B 端只会拿到启用的 —— 停用的区划是运营的维护对象，不该出现在商家的选择器里 |
| `hasChild` | `boolean` | 是 | 下面还有没有下级。端上据此决定「还要不要再往下选一层」，而不是点进去才发现是空的 |
| `source` | `string` | 否 | `OFFICIAL`（官方数据）/ `MERCHANT`（本店补录）。端上据此标出「我加的」 |
| `pending` | `boolean` | 否 | 本店补录且运营还没确认 —— **只有自己看得见**。 <p>要标出来：不标的话商家不知道这条还没共享， 会以为别的店也能看到他加的这个村。 |
| `auditStatus` | `string` | 否 | `PENDING` / `APPROVED` / `REJECTED`。官方数据恒为 APPROVED |
| `rejectReason` | `string` | 否 | 驳回理由。**要显示给商家** —— 看不到的话那个村在他那里凭空消失， 他不知道为什么，多半原样再录一遍。 |
| `rural` | `boolean` | 否 | 只对 level=VILLAGE 有意义：是不是村委会（`sys_region.rural`，服务端存的， 不是端上按名字猜的）。`true` = 到此为止，选择器不再往下钻（自然村数据地图上 本来就搜不全）；`false` = 居委会/社区，或非第五级，底下还能再挑具体小区。 |

### RegionSearchResult

跨级搜索（P1）：区划命中带从省到父级的路径，聚落命中带所在街道路径

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regions` | `object`（见下）\[\] | 是 | — |
| `communities` | `object`（见下）\[\] | 是 | — |
| `villages` | `object`（见下）\[\] | 否 | 还没开通的**官方村**（第五级名录）。已开通的那些走 `communities`（能直接勾）， 这里只出没开通的 —— 同一个地方不该在两组里各出现一次。 官方村提报即开通，所以端上点一条就能直接用。 |
| `places` | [`GeoTip`](#geotip)\[\] | 否 | 地图上的地点（v5）。**只在库里没有村/小区命中时才有值** —— 服务端先查库， 库里没有才现问高德；App 不用再自己调原生 SDK 兜底了。 |

`regions[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | — |
| `level` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `path` | `string` | 是 | — |

`communities[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communityNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `regionCode` | `string,null` | 否 | — |
| `path` | `string` | 是 | — |
| `kind` | `string,null` | 否 | ESTATE 小区 / VILLAGE 村。判「这一条底下还有没有下一级」用它，名字这时已经是口语名了 |
| `originCode` | `string,null` | 否 | 下钻要用它，不是 regionCode（那是它挂的街道/镇）。没有它就是地图开通的小区，没有下一级 |
| `originName` | `string,null` | 否 | 原始官方名（如「景滑村委会」），仅供展示/追溯 —— 判城乡用下面的 rural |
| `rural` | `boolean` | 否 | 是不是村委会（服务端存的）。判「这一条给不给 ›」用它，不要解析 originName |
| `latE6` | `number,null` | 否 | — |
| `lngE6` | `number,null` | 否 | — |

`villages[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `streetCode` | `string` | 是 | 它挂的街道码（9 位）。提报要挂到这下面 |
| `path` | `string` | 是 | — |
| `latE6` | `number,null` | 否 | — |
| `lngE6` | `number,null` | 否 | — |
| `rural` | `boolean` | 否 | 是不是村委会（服务端存的）。判「这一条给不给 ›」用它 |

### ReplyReviewReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reply` | `string` | 是 | 回复内容。公开展示在评价下方，一条评价只能回一次 |

### ReportShortageReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 出问题的 SKU |
| `kind` | [`ArrivalIssueKind`](#arrivalissuekind) | 是 | 问题类型：少件 / 破损。两者的售后责任判定不同 |
| `qty` | `number` | 是 | 缺/坏了几件。此前端上不收集这个数，后端落库恒为 1，分拣汇总的短缺数字从设计上就是错的 |
| `note` | `string` | 是 | 情况说明。承接方填，供货方与平台据此定责 |

### Review

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reviewNo` | `string` | 是 | 评价单号 |
| `goodsNo` | `string` | 是 | 被评价的商品 |
| `merchantNo` | `string` | 是 | 被评价的商家。差评会计入商家评分与申诉流程 |
| `nickname` | `string` | 是 | 评价人昵称（匿名评价时为「匿名用户」） |
| `avatar` | `string` | 是 | 评价人头像 |
| `rating` | `number` | 是 | 总分，1–5 整数 |
| `content` | `string` | 是 | 评价正文 |
| `images` | `string`\[\] | 是 | 评价图 URL 列表 |
| `spec` | `string` | 是 | 购买规格。展示在评价上，让人知道这条评价说的是哪个 SKU |
| `createdAt` | `number` | 是 | 评价提交时间 |
| `likeCount` | `number` | 是 | 点赞数 |
| `liked` | `boolean` | 是 | 当前用户是否已点赞 |
| `reply` | `string` | 否 | 商家回复 |
| `scores` | [`ReviewScores`](#reviewscores) | 否 | 三维度评分（B-9.3 / P-13.1.4）。总分 `rating` 仍保留 —— 老数据没有分维度分，列表页也只显示一个星级；维度分用于**评分算法与商家诊断**： 「货好但送得慢」这种问题，只看总分永远看不出来。 |
| `appeal` | [`ReviewAppeal`](#reviewappeal) | 否 | 商家申诉（B-9.4）。裁决在平台端 P-13.1 |

### ReviewAppeal

商家对差评的申诉。 这是**唯一**能把差评送进平台裁决台的入口 —— 平台端 P-13.1 的裁决页早就建好了， 但 B 端一直没有申诉入口，那张台子收不到任何单，等于空转。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `appealNo` | `string` | 是 | 申诉单号 |
| `reason` | `string` | 是 | 申诉理由，商家填写 |
| `images` | `string`\[\] | 是 | 举证图（聊天记录、物流截图） |
| `status` | [`ReviewAppealStatus`](#reviewappealstatus) | 是 | 裁决状态 |
| `submittedAt` | `number` | 是 | 申诉提交时间 |
| `verdict` | `string` | 否 | 裁决说明。**无论成立还是驳回都必须写** —— 商家会看到，「已读不处理」不是一种结果 |

### ReviewAppealStatus

枚举取值：

- `PENDING`
- `UPHELD`
- `REJECTED`

### ReviewScores

三维度：商品本身 / 履约（快慢、包装、缺损） / 服务（沟通、售后态度）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goods` | `number` | 是 | 商品本身，1–5 |
| `fulfillment` | `number` | 是 | 履约：快慢、包装、缺损，1–5 |
| `service` | `number` | 是 | 服务：沟通、售后态度，1–5 |

### SaveCampaignReqBody

类型：`CampaignDraft`

### SaveDeliveryRuleReqBody

类型：`DeliveryRule`

### SaveGoodsReqBody

保存商品的**线上格式**，与页面用的  {@link  GoodsDraft }  不同形状。 <p>后端要的是「基准语言的那一份 + 三语 map」两个字段，而不是一个三语对象。 此前这里直接 `= GoodsDraft`，于是端上把 `title` 当对象发过去， 后端反序列化直接抛 —— **b-app 保存商品在真实后端上一次都没成功过**， 而 mock 上完全正常，所以没人发现。拍平在 `http.ts` 里做，页面不受影响。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 否 | 商品单号。新建时不传，编辑时必传 |
| `title` | `string` | 是 | 基准语言（zh-CN）的标题。后端按 Accept-Language 下发时的兜底 |
| `subtitle` | `string` | 是 | 基准语言（zh-CN）的副标题/卖点 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 是 | 标题的三语原文，键是 Lang。缺译的语言按 R9 回落展示中文 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 是 | 副标题的三语原文，同上 |
| `categoryNo` | `string` | 是 | 类目单号。**必填，且是唯一的分类输入** —— 商品形态（生鲜要截单、服务不发货、iOS 可售规则）由它派生，请求体里不再有 `type`。 |
| `cover` | `string` | 否 | 封面图 URL（来自 mUploadImage）。漏传的话 C 端列表里是一块留白，且不报错 |
| `images` | `string`\[\] | 否 | 详情轮播图 |
| `detailImages` | `string`\[\] | 否 | 详情区长图。**空数组也要发** —— 与 images 同一口径，不发就删不掉 |
| `detail` | `string` | 否 | 图文详情正文（纯文本）。**空串也要发** —— 后端「不传 = 不改」，删光了不发就删不掉 |
| `params` | [`GoodsParam`](#goodsparam)\[\] | 否 | 商品参数（产地/保质期/材质…）。**整份覆盖，空数组也要发**。 <p>此前这个字段**契约里没有、http.ts 也没发** —— 而编辑页一直在收集它 （`goods-edit` 里那一栏和 `paramValues` 都在）。于是商家填完保存， 参数原地消失，且不报错：后端把 `params == null` 当「不改」， 所以旧值还在、新填的进不去、想删的删不掉。 |
| `specGroups` | [`SpecGroupDraft`](#specgroupdraft)\[\] | 是 | 空数组 = 单规格。非空则 skus 必须是各组选项的笛卡尔积 |
| `fulfillments` | `string`\[\] | 否 | 支持的履约方式；不传 = 不改（新建默认四种全支持） |
| `skus` | [`SkuDraft`](#skudraft)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |
| `limitPerUser` | `number` | 否 | 每人限购，0 = 不限。不传 = 不改 |
| `fresh` | `object`（见下） | 否 | 生鲜段：截单 / 到货描述 / 是否按实称 / 产地。不传 = 不改 |
| `service` | `object`（见下） | 否 | 服务段：时长 / 可核销门店。不传 = 不改 |
| `groupBuy` | `object`（见下） | 否 | 拼团档：起团人数 + 团价，要么都给要么都不给 |
| `stdNo` | `string` | 否 | 引用的平台标准品。传了它，服务端会用标准品的 categoryNo 与 optionCode **覆盖**请求里的值；不传 = 自建品 / 脱离标准品。 |

`fresh` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `cutoffAt` | `number` | 否 | 当天几点前下单（毫秒时间戳）。与「到点」是两件事：截单管下单，到点管到货 |
| `arrivalDesc` | `string` | 否 | 预计到货描述，如「次日 17:00 前到点」 |
| `weighed` | `boolean` | 否 | 是否按实称多退少补 |
| `origin` | `string` | 否 | 产地 |

`service` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `durationMin` | `number` | 否 | 服务时长（分钟） |
| `storeName` | `string` | 否 | 可核销门店名 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 否 | 起团人数，最小 2 —— 一个人不叫团 |
| `price` | `number` | 否 | 团购价（最小货币单位） |

### SaveSpecTemplateReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 规格维度名，如「重量」 |
| `options` | `string`\[\] | 是 | 可选值列表。存成商家自己的模板（scope=MERCHANT），不影响平台模板 |

### SaveStockReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 要改库存的 SKU |
| `stock` | `number` | 是 | 改后的库存数。**是绝对值不是增量** |

### SaveStoreReqBody

类型：`StoreProfile`

### ServiceArea

一条地理覆盖项。名字由后端拼好下发 —— 端上只拿到 330106 的话，要么显示一串数字，要么自己再查一次

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `level` | [`AreaLevel`](#arealevel) | 是 | 粒度：社区 / 村 / 街道 / 区县 / 城市。**可跨粒度组合** —— 三个小区 + 一个区是四条 |
| `refCode` | `string` | 是 | level=COMMUNITY 时是社区号，否则是区划码 |
| `name` | `string` | 是 | 展示名。区级以上是「浙江省 / 杭州市 / 西湖区」整条路径 —— 光一个「西湖区」全国有好几个，商家分不出删哪条 |
| `areaNo` | `string` | 否 | 业务键（服务端回填）。范围子集（P2）按它引用；端上新加的项没有，保存后才有 |
| `status` | [`AreaStatus`](#areastatus) | 否 | `ACTIVE` 已生效 / `PENDING` 待运营审核。 勾已有社区自助生效；勾区、街道要审 —— 一家菜摊声称覆盖整个西湖区， 影响面差一个量级（ADR-013 §4.2）。**端上必须把待审标出来**： 待审的不参与展开，商家看着它在清单里却一个订单也不来， 而这是他自己永远查不出来的那类故障。 |

### ServiceScope

枚举取值：

- `COMMUNITY`
- `CITY`
- `PLATFORM`

### SetActiveReq

停用/启用（门店与员工共用同一个形状）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `active` | `boolean` | 是 | true 启用 / false 停用 |

### SetStorePaymentReq

换门店收款号。**不传或传空 = 回到主体默认号**，这是合法操作不是清空错误

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payMerchantNo` | `string` | 否 | 目标收款商户号。只能是本主体已开通的号；空 = 回到主体默认号 |

### SettleAccountType

结算账户形态。个人 openid 收款 / 对公商户号收款（ADR-002 §5）

枚举取值：

- `PERSONAL_BANK_CARD`
- `MERCHANT_ID`

### SettleBill

结算流水。**一个子订单一行**（ADR-002 §5），不是周期账单。 > 2026-08-11 更正：这个类型此前描述的是一套「周期账单」（`billNo` / `periodStart` > / `orderCount` / `settledMinor`），而后端 `/biz/settle/bills` 从来返回的都是 > 按子单一行的分账流水。**字段一个都对不上**，页面靠 mock 才看起来是好的 —— > 连真后端会整片 undefined。与本轮反复撞到的「单看任一端都完整，断在两端之间」同形状。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `settleNo` | `string` | 是 | 结算单号 |
| `subOrderNo` | `string` | 是 | 对应的子订单号 —— 分账以它为单位 |
| `orderNo` | `string` | 是 | 所属主单号 |
| `merchantNo` | `string` | 是 | 主体号 |
| `grossMinor` | `number` | 是 | 结算基数（分）= 用户实付 + 平台补贴。**平台出资的优惠要补回给商家** |
| `commissionMinor` | `number` | 是 | 平台佣金（分） |
| `serviceFeeMinor` | `number` | 是 | 自提点履约服务费（分）。供货方付、承接方收，两个角色都是自己时账面抵消 |
| `netMinor` | `number` | 是 | 商家实得（分）= 基数 − 佣金 − 服务费 |
| `trafficSource` | `string` | 否 | 客流来源：MERCHANT_OWNED 自带客流（零佣金）/ PLATFORM |
| `commissionRate` | `number` | 是 | 佣金费率快照（万分比）。费率会变，历史账不跟着变 |
| `status` | [`SettleBillStatus`](#settlebillstatus) | 是 | PENDING / SPLIT / RETRYING / MANUAL / REVERSED |
| `createdAt` | `number` | 是 | 生成时间 |
| `splitAt` | `number` | 否 | 分账完成时间；没分完为空 |
| `storeNo` | `string` | 否 | 这笔钱是**哪家店**挣的（统计维度）。空 = 存量主体级流水。 它**不决定钱打给谁** —— 打给谁看 `payMerchantNo`。 两家店可以共用一个收款号（合并结算），也可以各配各的（分开结算）。 |
| `payMerchantNo` | `string` | 否 | 这笔钱打给**哪个收款号**（结算维度，生成时快照）。空 = 当时进件还没走完 |

### SettleBillStatus

结算流水状态。**与后端 `StlBill` 逐字一致**。 > 2026-08-11 收敛：这里此前是 `PENDING/PARTIAL/DONE/EXPIRED` —— 一套后端从来没有过的词， > 描述的是「周期账单」而不是「按子单的分账流水」。内联时对所有工具不可见， > 具名化之后才暴露出来（见 enum-registry 里这条的 note）。 - `PENDING` 待分账 · `SPLITTING` 分账中 - `SPLIT` **指令已发出，等通道确认** · `SPLIT_CONFIRMED` **已到账**（终态） - `RETRYING` 失败重试中 · `MANUAL` 转人工（重试用尽，**不会自动再动钱**） - `REVERSED` 已回退（退款前必须先回退分账） - `OFFLINE_SETTLED` 当面收款，不走分账（钱从没进过平台） - 自营轨道：`PENDING_RECON` 待对账 · `CONFIRMED` 已确认应付 · `PAID` 已付款（自营终态） ⚠️ **两条轨道互不相通**：第三方的单不会走到 `PAID`，自营的单不会走到 `SPLIT` —— 它们的钱根本不是同一条路径下去的。此前 shared 这份漏了自营那三个值， 于是 b-app 判 `status === "PAID"` 时类型说「不可能」，而后端一直在下发。 ⚠️ **`SPLIT` 曾经同时表示「指令已发出」与「钱已到」**，而底下调的是桩实现 —— 账面显示已分账而一分钱没动。2026-08-26 拆开：`SPLIT` 退回「已发出」， 到账另立 `SPLIT_CONFIRMED`，且**只能由通道回执产生**。 端上措辞跟着改：`SPLIT` 不能再叫「已结算」—— 商家拿它去对银行流水，对不上就来找客服，而客服看到的状态也是同一个词。

枚举取值：

- `PENDING`
- `SPLITTING`
- `SPLIT`
- `SPLIT_CONFIRMED`
- `OFFLINE_SETTLED`
- `PENDING_RECON`
- `CONFIRMED`
- `PAID`
- `RETRYING`
- `MANUAL`
- `REVERSED`

### ShareKit

分享素材（B-11.2.7）。文案由服务端按当前语言与市场生成

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `text` | `string` | 是 | 分享文案，已按当前语言与市场生成 |
| `posterUrl` | `string` | 是 | 落地页链接，文案里已经拼过一次；未配对外域名时为空串。真正的海报图走  {@link  Poster } |

### ShipReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `expressNo` | `string` | 是 | 快递单号。填了即视为已发货，订单流转到 SHIPPED |

### Sku

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | SKU 单号。下单、库存、订单行都指向它，不是指向 goodsNo |
| `optionValues` | `string`\[\] | 是 | 各规格维度上的取值，顺序与 Goods.specGroups 一一对应。 单规格商品长度为 1；多规格（如 重量 × 包装）长度 >1。 |
| `spec` | `string` | 是 | 展示用拼接文案（后端下发，端上不自己拼，避免多语言分隔符差异） |
| `price` | `number` | 是 | 售价（最小货币单位） |
| `originPrice` | `number` | 否 | 划线价（最小货币单位）。为空表示不展示划线价 |
| `stock` | `number` | 是 | 可售库存。下单时服务端二次校验，端上这个值只用于展示与预校验 |
| `nominalGram` | `number` | 否 | FRESH 且按重计价：标称重量（克） |
| `costPrice` | `number` | 否 | 成本价（最小货币单位）。**只有商家侧 `/biz/goods/{no}` 下发，C 端恒空。** 进货价是商家的经营秘密，出现在买家端的响应里就等于公开了。 它不参与任何计价，只用来在编辑页实时算毛利。 |
| `barcode` | `string` | 否 | 商品条码 EAN-13 / UPC（V252）。**只在商家侧下发** —— 它是商家与供应商/ERP 之间的键，对买家没有用处，而条码还能反查到进货渠道。 |
| `merchantSkuCode` | `string` | 否 | 商家自有货号。他 ERP 里的主键，同样只在商家侧下发 |
| `saleUnit` | `string` | 否 | 计量单位（件 / 斤 / kg / 份）。**买家侧也要** —— 「5」到底是 5 件还是 5 斤，买家同样需要知道才判断得了贵不贵。 |
| `priceByMarket` | [`Record_string_number`](#record_string_number) | 否 | 各市场价（市场码 → 最小货币单位）。**只有商家侧 `/biz/goods/{no}` 下发，C 端恒空。** <p>编辑页按市场逐格填，而保存是**整份覆盖** —— 拿不到整张表就只能回填当前 那一格，于是改一次标题，其余市场的价格行就被删了，且不报错： 那两个市场的买家从此看不到这件商品。与 `titleI18n` 是同一个形状的故障。 |
| `storePrice` | `number` | 否 | 本店单独定的价（最小货币单位）。**只在 B 端下发，空 = 同主体价**，不是 0。 <p>与门店库存回退方向相反：没设过价的店按主体价卖，没设过库存的店按 0 卖 —— 价格视为 0 就是白送。 |

### SkuDraft

SKU 草稿。`optionValues` 的顺序与 `specGroups` 一一对应 —— 这是矩阵的坐标，错位就会出现「5 斤卖成 10 斤的价」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 否 | 已有 SKU 带上原编号，改价改库存不会丢历史订单的引用 |
| `optionValues` | `string`\[\] | 是 | 各规格维度上的取值，顺序与 specGroups 一一对应 |
| `price` | `number` | 是 | 当前市场的价（最小货币单位）。兼容单市场调用 |
| `priceByMarket` | [`Partial_Record_MarketId_number`](#partial_record_marketid_number) | 否 | **按市场分别定价**（B6）。未填的市场不在该市场售卖 —— 汇率换算出的价没有价格心理学（¥29.9 → $4.19 不是任何人会标的价）， 且汇率一动全店价格跟着抖，而商家并没有调价。 <p>⚠️ 键是**市场码**（CN/AE/US），不是币种码。这两套码一一对应，所以写错了 不报任何错 —— 但落到 `prd_sku.market` 上就成了一行 `market='CNY'` 的死数据： C 端按市场取价永远取不到它。此前端上按 `currency` 发，于是每个新 SKU 多一行脏数据， 且商家在 AED/USD 页签填的价**在那两个市场一分钱也卖不出去**。 |
| `stock` | `number` | 是 | 可售库存 |
| `originPrice` | `number` | 否 | 划线价（最小货币单位）。**留空 = 不改**，传 0 = 清掉。 <p>它是派生展示值（标折扣用），不是定价 —— 必须**高于售价**， 否则渲染出来是个「涨价了」的折扣标，后端会拒。 <p>此前有列、有契约、**没有写入路径**：折扣标因此永远不出现。 |
| `nominalGram` | `number` | 否 | 标称重量（克），生鲜按重计价用。**留空 = 不改**，传 0 = 清掉。 「按标称预扣、称重后多退少补」这条链靠它，没有它整条链跑不起来。 |
| `costPrice` | `number` | 否 | 成本价（最小货币单位）。**留空 = 不改**，传 0 = 清掉。 <p>只用来在编辑页实时算毛利，不参与任何对外计价，也**不下发买家端**。 不校验与售价的大小关系 —— 引流款本来就可能亏本卖。 |
| `barcode` | `string` | 否 | 商品条码 EAN-13 / UPC（V252）。**只在商家侧下发** —— 它是商家与供应商/ERP 之间的键，对买家没有用处，而条码还能反查到进货渠道。 |
| `merchantSkuCode` | `string` | 否 | 商家自有货号。他 ERP 里的主键，同样只在商家侧下发 |
| `saleUnit` | `string` | 否 | 计量单位（件 / 斤 / kg / 份）。**买家侧也要** —— 「5」到底是 5 件还是 5 斤，买家同样需要知道才判断得了贵不贵。 |

### SkuIdentityReport

商品编码批量导入的试算 / 结果（P4）。 <p>四个数各回答一件事：**这份表有多少行、会改几行、几行没变化、几行有问题**。 少了「没变化」那一格，商家会把「改了 3 行」读成「另外 197 行失败了」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `total` | `number` | 是 | 数据行数，不含表头 |
| `willSet` | `number` | 是 | 会真正写下去的行数 |
| `noChange` | `number` | 是 | 匹配上了但三列都没变的行数 |
| `problems` | `object`（见下）\[\] | 是 | — |
| `samples` | `object`（见下）\[\] | 是 | 前几行的前后对照，让他确认「改的是不是我想的那些」 |

`problems[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `line` | `number` | 是 | — |
| `reason` | `string` | 是 | — |

`samples[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | — |
| `goods` | `string` | 是 | — |
| `spec` | `string` | 是 | — |
| `barcodeFrom` | `string,null` | 否 | — |
| `barcodeTo` | `string,null` | 否 | — |
| `codeFrom` | `string,null` | 否 | — |
| `codeTo` | `string,null` | 否 | — |
| `unitFrom` | `string,null` | 否 | — |
| `unitTo` | `string,null` | 否 | — |

### SpecGroup

规格维度，例：{ name: "重量", options: ["约5斤", "约10斤"] }

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 规格维度名，如「重量」「包装」 |
| `options` | `string`\[\] | 是 | 该维度的可选值，如 `["约5斤", "约10斤"]` |
| `optionCodes` | `string` \| `any`\[\] | 否 | 与 options 一一对应的模板编码。来自模板的选项有值，自由输入的为空。 一期只写入不消费 —— 但不留位的话，二期做规格聚合要刷全部历史商品。 |
| `templateNo` | `string` | 否 | 该规格组来自哪个模板（便于「用的人多不多」这类平台侧统计） |

### SpecGroupDraft

规格组草稿：一个维度（如「重量」）与它的取值（「5 斤」「10 斤」）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | `string`\[\] | 是 | 该维度的可选值 |
| `optionCodes` | `string` \| `any`\[\] | 否 | 与 options 一一对应的模板编码。来自平台模板的有值，手输/改过的为空。 **一期只存不用** —— 但不存的话，二期做规格聚合要刷全部历史商品。 |
| `templateNo` | `string` | 否 | 该规格组来自哪个平台模板。手输的为空 |

### SpecOption

规格选项。 `code` 是**能不能做规格聚合的分水岭**： 三家店卖同一种米，自由输入会写成「5斤」「五斤」「2.5kg」—— 这三个字符串在库里毫无关系，将来想做「按重量筛选 / 同规格比价」全部落空， 而且不可回溯（历史商品已经写死）。所以模板带来的值必须带 code。 自由输入的值只有 label、没有 code：照常展示，但不参与聚合。 **一期只写入不消费**，聚合搜索是二期 —— 但字段现在就得留位。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 否 | 来自模板时有值；商家自己输入的没有 |
| `label` | `string` | 是 | 选项展示文案，如「约5斤」 |

### SpecTemplate

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `scope` | [`SpecTemplateScope`](#spectemplatescope) | 是 | 模板归属：平台统一维护 or 商家自存。商家只能改自己的 |
| `categoryType` | [`CategoryType`](#categorytype) | 否 | 平台模板按品类推荐；商家模板不限品类 |
| `categoryNo` | `string` | 否 | 类目级模板的归属类目；**空 = 品类兜底**。 <p>端上靠它区分两层：类目级排在前面并标出来。不下发的话两批混在一起， 商家分不出哪个是「专门给这一类的」。 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`SpecOption`](#specoption)\[\] | 是 | 该维度的可选项 |
| `merchantNo` | `string` | 否 | scope=MERCHANT 时归属的商家 |
| `primary` | `boolean` | 否 | **主维度**：选完类目该自动建出来的就是这一组（每个类目至多一个，守卫测住）。 <p>不下发的话端上只能靠「数组第一个」猜 —— 后端确实那么排，但那是巧合而非契约： 排序一改端上跟着错，症状是「自动建出来的是包装不是重量」，没有一处会报错。 <p>商家自存模板与品类兜底模板恒为 false：主维度是**类目绑定**上的判据， 那两条路不经过绑定表。 |

### SpecTemplateScope

规格模板归属：平台统一维护 / 商家自存

枚举取值：

- `PLATFORM`
- `MERCHANT`

### SpecValueAdded

新加的规格取值（商家自建维度）。同上：命名是为了它能进契约

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `valueNo` | `string` | 是 | — |
| `code` | `string` | 是 | — |
| `label` | `string` | 是 | — |

### SpuStd

平台标准品（TDD-标准品库）：商家引用建品的**模子**。 <p>**无价、无库存、无履约** —— 那些永远是商家的。它存在的理由是 `specGroups` 里的 `optionCode`：没有标准品，三家店各自录「本地菠菜」得到三个毫无关系的商品， 聚合、比价、统计全都无从谈起。 <p>取用时端上只是把字段**填进表单**，商家可以改标题与图；但**类目与 optionCode 由服务端强制以标准品为准** —— 能改掉的话，标准品就退化成一个填表助手。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `stdNo` | `string` | 是 | — |
| `categoryNo` | `string` | 是 | 所属类目。取用后**改不掉**：类目决定形态（生鲜要截单、服务不发货） |
| `categoryName` | `string` | 否 | 类目名，展示用 |
| `title` | `string` | 是 | — |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | — |
| `subtitle` | `string` | 否 | — |
| `cover` | `string` | 否 | — |
| `images` | `string`\[\] | 否 | — |
| `specGroups` | [`SpecGroup`](#specgroup)\[\] | 是 | 每个选项都带 `optionCode` —— 跨店可比靠的就是它 |
| `keywords` | `string` | 否 | 别名/品牌/俗称，搜索用。端上可以不展示 |
| `status` | `string` | 否 | — |
| `refCount` | `number` | 否 | 被引用次数，只给运营排序用 |
| `barcode` | `string` | 否 | 商品条码。**空是常态** —— 生鲜、现做熟食、服务本来就没有条码 |
| `source` | `string` | 否 | 出处：`OPS` 运营手录 / `OFF` 从 Open Food Facts 导入。 众包来的那批全是待审状态，运营靠它把「还没人看过的」与「自己录的」分开审。 |

### StaffLog

一条员工与授权的变更记录（B-11.10.3）。 **授权变更是权限扩散的唯一入口** —— 加人、停用、给角色、撤角色。 别的动作都有业务单据兜底，唯独这几个此前做完就没了： 三个月后问「谁把张三提成了店长」，库里只有一行当前状态。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `actor` | `string` | 否 | 操作人手机号（脱敏）。取不到当时身份时为空 —— 空就是空，不写「系统」 |
| `targetName` | `string` | 否 | 被操作员工的手机号（脱敏） |
| `action` | `string` | 是 | STAFF_ADD / STAFF_ENABLE / STAFF_DISABLE / ROLE_GRANT / ROLE_REVOKE |
| `storeName` | `string` | 否 | 涉及门店的名字。加人与启停为空 |
| `role` | [`StaffRole`](#staffrole) | 否 | 涉及的角色码。加人与启停为空 |
| `detail` | `string` | 否 | 人能读的一句话，直接展示 |
| `at` | `number` | 是 | 发生时间，毫秒时间戳 |

### StaffLoginReq

员工登录。与商家登录同形状，但打的是另一个端点、解析出的是另一套身份

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `phone` | `string` | 是 | 员工的登录手机号（老板在员工管理里加的那个） |
| `code` | `string` | 是 | 短信验证码 |

### StaffRole

门店角色（B 端）。**一人一店可持有多个**，权限取并集。 分界线画在「出错的后果」上，而不是功能重要性 —— 履约被拆成三种活，因为它们面对的对象不同：分拣对货、核销对顾客、发货对收件人。 拆开之后理货员与配送员才装得下。判断依据见 `docs/requirements/三端角色权限功能对齐清单.md` §4。 ⚠️ `CS` 与运营端的 `Role.CS` **同名不同义**：这个是商家自己雇的客服（只管自己店）， 那个是平台客服（跨商家、能仲裁）。 老板不在这里 —— 他是 `isOwner`，不需要逐店授权。

枚举取值：

- `MANAGER`
- `CLERK`
- `PICKER`
- `COURIER`
- `CS`

### StaffStatus

员工账号状态

枚举取值：

- `ACTIVE`
- `DISABLED`

### StockBalance

一行库存（`BalanceVO`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `specText` | `string` | 否 | — |
| `baseUom` | `string` | 否 | — |
| `onHand` | `number` | 是 | — |
| `reserved` | `number` | 是 | — |
| `available` | `number` | 是 | 可用 = 实存 − 预留。预留是别人下了单还没付钱的量，付了款才真扣 |
| `safetyStock` | `number` | 否 | — |
| `lastMovedAt` | `string` | 否 | 最后一次动过的时间；滞销判据 |
| `flags` | `string`\[\] | 是 | SHORTAGE 缺货 · STALE 滞销。**空数组 = 这件没事** |

### StockCount

一张盘点单（`CountVO`）。**`bookQty` 是开单那一刻的快照** —— 端上不要拿当前余额去顶替它：盘的过程中照常卖，用当前数算差异， 中间卖掉的量会被算成盘亏，而那是一笔凭空出现的损失。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `countNo` | `string` | 是 | — |
| `status` | `string` | 是 | COUNTING 进行中 / POSTED 已过账 |
| `locationId` | `string` | 否 | — |
| `startedAt` | `string` | 否 | — |
| `operator` | `string` | 否 | — |
| `lines` | [`StockCountLine`](#stockcountline)\[\] | 是 | — |

### StockCountLine

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `specText` | `string` | 否 | — |
| `baseUom` | `string` | 否 | — |
| `bookQty` | `number` | 是 | — |
| `countedQty` | `number,null` | 否 | 还没填时是 null，**不是 0** —— 0 的意思是「盘了，一件不差」 |
| `diffQty` | `number,null` | 否 | — |
| `reasonCode` | `string` | 否 | — |

### StockDocument

单据中心的一行（`DocumentVO`）。四类单据长得不一样，下发的是它们的交集

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `kind` | `string` | 是 | IN 入库 · OUT 出库 · COUNT 盘点 · TRANSFER 调拨 |
| `docNo` | `string` | 是 | — |
| `status` | `string` | 是 | DRAFT / POSTED / VOIDED，调拨还有 SHIPPED / RECEIVED |
| `subtitle` | `string` | 否 | 「订单 SO-88213」「来自 CNT-24082601」这类一句话出处 |
| `totalQty` | `number` | 是 | — |
| `occurredAt` | `string` | 是 | — |
| `operator` | `string` | 否 | — |

### StockItemDetail

某个物料在各库位的分布（`ItemDetailVO`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `specText` | `string` | 否 | — |
| `baseUom` | `string` | 否 | — |
| `barcode` | `string` | 否 | — |
| `itemCode` | `string` | 否 | — |
| `onHand` | `number` | 是 | — |
| `reserved` | `number` | 是 | — |
| `available` | `number` | 是 | — |
| `byLocation` | `object`（见下）\[\] | 是 | — |

`byLocation[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `locationId` | `string` | 是 | — |
| `locationName` | `string` | 是 | — |
| `onHand` | `number` | 是 | — |

### StockLedgerPage

台账一页（`LedgerPageVO`）。游标由服务端给，前端不要自己拿最后一行的 id 推

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entries` | [`StockLedgerRow`](#stockledgerrow)\[\] | 是 | — |
| `nextCursor` | `number,null` | 否 | — |

### StockLedgerRow

台账一行（`LedgerVO`）。**不可变** —— 只有查看，没有编辑

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `id` | `number` | 是 | — |
| `itemId` | `string` | 是 | 这一行动的是哪件货。**按单查靠它** —— 只给单号的话那一屏是一列没名字的数 |
| `itemName` | `string` | 是 | — |
| `docKind` | `IN` \| `OUT` | 是 | — |
| `docNo` | `string` | 是 | — |
| `reasonCode` | `string` | 是 | — |
| `qtyDelta` | `number` | 是 | — |
| `balanceAfter` | `number` | 是 | — |
| `occurredAt` | `string` | 是 | — |
| `operator` | `string` | 否 | — |

### StockLocation

库位（`InvLocation`）。**仓是一种库位，不是一种门店**

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `locationId` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `kind` | `string` | 是 | STORE 门店 · WAREHOUSE 仓 · TRANSIT 在途（系统的，不可删） |
| `externalRef` | `string` | 否 | 门店库位对应的 storeNo |
| `sourceLocationId` | `string` | 否 | 发货源：设了之后这家店下单扣的是源仓的库存。**不允许接力** |
| `isDefault` | `number` | 否 | — |
| `status` | `string` | 否 | — |

### StockMonthly

进销存月报（`MonthlyVO`）。界面上要能看出 期初 + 进 − 销 − 损 ± 调 = 期末

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `month` | `string` | 是 | — |
| `opening` | `number` | 是 | — |
| `purchased` | `number` | 是 | — |
| `sold` | `number` | 是 | — |
| `lost` | `number` | 是 | — |
| `adjusted` | `number` | 是 | — |
| `closing` | `number` | 是 | — |
| `balanced` | `boolean` | 是 | 算式对不对得上。**对不上要显眼**，那说明台账漏了一笔 |
| `soldCostMinor` | `number` | 是 | 本月销售出库的成本合计（分）。**按每一笔当时的单位成本累加**， 不是「销量 × 当前成本价」—— 后者在进价波动时会把上个月的账算成今天的价。 **这不是毛利。** 毛利 = 收入 − 成本，而收入不在进销存域： 出库单只带成本、不带售价（同一件货不同渠道价不一样，写进来就有了第二个真源）。 要毛利得由知道收入的那一侧拿这个数去减。 |
| `lostCostMinor` | `number` | 是 | 本月报损 + 盘亏的成本合计（分）—— 「这个月亏了多少钱」那个数 |

### StockRank

榜单一行（`RankVO`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `specText` | `string` | 否 | — |
| `qty` | `number` | 是 | — |
| `costAmountMinor` | `number,null` | 否 | 金额（分）。**滞销榜不算这个数，会是 `null`** —— 后端没配 `NON_NULL`，null 是照常下发的，所以类型要允许它。 兜底成 0 会让人以为这批货不值钱，而它恰恰是压着钱的那批。 |

### StockSummary

库存总览的三个数（`SummaryVO`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemCount` | `number` | 是 | — |
| `shortageCount` | `number` | 是 | — |
| `staleCount` | `number` | 是 | — |
| `inTransitCount` | `number` | 是 | 待收货的调拨单数。**按单不按件** —— 收货是按单做的，给件数点不进任何一张单 |
| `openCountNo` | `string,null` | 否 | 还开着的那张盘点单的单号，没有就没有这个字段。 **给单号不给个数**：工作台的「继续盘点」要带着它跳， 不带的话那一页会开一张**新的**盘点单，而按钮上写着「继续」。 |

### StockTransfer

一张调拨单（`TransferVO`）。**草稿态没有行**（行在发出的那张出库单上），不是空单

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `transferNo` | `string` | 是 | — |
| `status` | `string` | 是 | DRAFT 草稿 / SHIPPED 已发出 / RECEIVED 已收到 |
| `fromLocationId` | `string` | 否 | — |
| `fromLocationName` | `string` | 否 | — |
| `toLocationId` | `string` | 否 | — |
| `toLocationName` | `string` | 否 | — |
| `shippedAt` | `string` | 否 | — |
| `receivedAt` | `string` | 否 | — |
| `carrierName` | `string` | 否 | 承运方名字快照。空 = 自己送或发货时没记 —— 不是「数据缺失」 |
| `trackingNo` | `string` | 否 | 运单号。与 carrierName 一起给收货方核对用 |
| `totalQty` | `number` | 是 | — |
| `lines` | `object`（见下）\[\] | 是 | — |

`lines[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `itemId` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `specText` | `string` | 否 | — |
| `qty` | `number` | 是 | — |
| `uom` | `string` | 否 | — |

### Store

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 门店号。一旦生成不再变 —— 换主体只换归属，不换它 |
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 否 | 门店地址。顾客据此找到取货点，也是履约范围的锚点 |
| `isDefault` | `boolean` | 是 | 是否默认店。一个主体**恰好一家** —— 它是「找不到具体门店时去哪」的答案 |
| `status` | [`StoreStatus`](#storestatus) | 是 | ACTIVE 正常营业 / READONLY 已停用（不再接新单，已有单照常履约） |
| `payMerchantNo` | `string` | 否 | 这家店用哪个收款号。**空 = 用主体的默认收款号**，不是"没配" |
| `payReady` | `boolean` | 是 | 这家店现在能不能收钱。照它显示，别自己去比状态串 |
| `staffCount` | `number` | 是 | 授权到这家店的员工数（不含老板）。0 表示只有老板能管这家店 |
| `rating` | `number` | 否 | 门店评分 ×10（V155）。与主体评分是两个数：主体分是各店的合成，反过来推不回去 |
| `ratingCount` | `number` | 否 | 计入门店评分的条数。**0 = 暂无评价**，不是 0 分 |
| `planSuspended` | `boolean` | 否 | 这家店的只读**是套餐降级压下来的**，不是店主自己停的。 <p>两者的 `status` 一模一样（都是 `READONLY`），而端上要给的下一步完全不同： 降级压的要**补缴/升档**，自己停的**点一下启用就开**。 不分开的表现是店主反复点那个对降级店无效的启用按钮。 |

### StoreActivity

商家活动（P5，新模型 `pmt_activity`）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `activityNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `goal` | `string,null` | 否 | `ACQUIRE` 拉新 / `WAKEUP` 唤回 / `CLEAR` 清库存 / `BASKET` 提客单。只影响建的时候的默认值 |
| `storeNo` | `string,null` | 否 | — |
| `triggerType` | `string` | 是 | `NONE` / `AMOUNT` 满额 / `QTY` 件数 / `GOODS` 命中商品 |
| `triggerAmountMinor` | `number,null` | 否 | — |
| `triggerQty` | `number,null` | 否 | — |
| `benefitType` | `string` | 是 | `CUT` 减钱 / `PRICE` 改单价 / `GIFT` 送商品 / `COUPON` 发券 |
| `benefitAmountMinor` | `number,null` | 否 | — |
| `benefitQty` | `number,null` | 否 | — |
| `benefitRef` | `string,null` | 否 | — |
| `scheduleType` | `string` | 是 | `ONE_OFF` 短期 / `ALWAYS_ON` 长期 / `RECURRING` 周期 |
| `startAt` | `number,null` | 否 | — |
| `endAt` | `number,null` | 否 | — |
| `scheduleRule` | `string,null` | 否 | RECURRING 的 JSON：`{"weekdays":[3],"from":"08:00","to":"20:00"}` |
| `quota` | `number,null` | 否 | — |
| `quotaUsed` | `number` | 是 | — |
| `quotaLeft` | `number,null` | 否 | — |
| `budgetMinor` | `number,null` | 否 | — |
| `budgetUsedMinor` | `number` | 是 | — |
| `maxExposureMinor` | `number,null` | 否 | 最大敞口 = 限量 × 单次优惠。建活动页要显示它 |
| `audiences` | `object`（见下）\[\] | 是 | 空数组 = **对所有人生效**。老活动迁过来就是这个状态 |
| `goodsNos` | `string`\[\] | 是 | — |
| `status` | `string` | 是 | `DRAFT` / `RUNNING` / `PAUSED` / `ENDED` |
| `endedReason` | `string,null` | 否 | `EXPIRED` / `QUOTA` / `BUDGET` / `MANUAL`。商家问「怎么停了」要有答案 |
| `liveNow` | `boolean` | 是 | 此刻是不是真的在生效。**与 status 分开**：周期活动在非时段里 status 仍是 RUNNING， 而商家问的是「现在减不减」。 |

`audiences[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | `string` | 是 | — |
| `value` | `string` | 是 | — |

### StoreCategory

门店经营类目 —— 商家给自己的店摆的<b>货架</b>。 <p>与「主体已获授权的类目」是两件事：那是<b>平台批的证</b>（能不能卖这一类）， 这是<b>商家的货架</b>（店里怎么摆）。责任人不同，所以不合成一个字段。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categoryNo` | `string` | 是 | 平台类目号。**改显示名不动它** —— 跨店聚合与比价都认这个 |
| `name` | `string` | 是 | 展示名：`displayName` 有就用它，否则是平台类目名。直接照它渲染 |
| `platformName` | `string` | 是 | 平台类目名。改名时要让商家看得见自己改的是谁 |
| `displayName` | `string` | 否 | 商家改的名。空 = 用平台名，不是「叫空字符串」 |
| `sort` | `number` | 是 | 店内展示顺序，小的在前。商家拖出来的顺序 |
| `goodsCount` | `number` | 是 | 这个货架上有几件商品 —— **撤架之前商家要看得见代价**（有货就撤不掉） |
| `onSaleCount` | `number` | 是 | 正在卖的件数。**与 goodsCount 分开**：商家问「这一类卖得怎么样」时要的是它， 问「能不能撤架」时要的是上面那个（含下架与待审的全部）。 |
| `pendingCount` | `number` | 是 | 待审的件数。它常常是「这一类为什么看起来没货」的答案 |

### StoreCategorySpecs

「我的规格」里的一组：**这家店的一个货架类目**，以及它能用到的规格。 <p>按货架类目给而不是给平台的全部通用维度：一家只卖蔬菜和肉的店， 看到「尺码」「口径」「时长」是纯噪音，而噪音会让他觉得这一页与自己无关。 <p>`dims` 可能是空的 —— 那是运营还没给这个类目配规格，**商家看得见才问得出来**。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categoryNo` | `string` | 是 | — |
| `categoryName` | `string` | 是 | 店主改过名的用店主的叫法（「好菜」而不是「蔬菜」）—— 这一页是给他看的 |
| `dims` | [`SpecTemplate`](#spectemplate)\[\] | 是 | 销售规格：买家要挑一档，每档单独定价、单独算库存 |
| `props` | [`SpecTemplate`](#spectemplate)\[\] | 否 | 商品参数：只描述，不分 SKU、不影响价格与库存。 <p>与 `dims` 并排而不是合成一列加个字段：它们在界面上是两块， 合成一列端上每处都要先过滤，而漏过滤一次就是「产地」被当成规格。 |

### StoreEditReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 否 | 门店地址 |
| `categoryNos` | `string`\[\] | 否 | 这家店摆哪些货架（**只有新建时有意义**，改名时后端忽略）。 <p><b>不传 = 复制默认店的</b>：多门店商家开分店卖的多半是同一批货， 从零勾选是纯负担。一个都没有也合法 —— 建品时会自动加入。 |
| `entityNo` | `string` | 否 | 这家店挂在哪张证照下（多证照）。 <p><b>不传 = 当前证照</b>，与单证照时代一模一样 —— 只有一张证照的账号 端上整个不渲染这一步。传了别人的证照号后端直接 403，不会静默落到当前这张。 <p>注意**额度按证照算**：挂到另一张证照下时撞的是那张的门店额度， 而不是当前这张的。这是应该的，额度是那张证照买的。 |

### StoreFulfillment

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | — |
| `channels` | [`StoreFulfillmentChannel`](#storefulfillmentchannel)\[\] | 是 | 固定四行，顺序即开关顺序 —— 服务端补缺，端上不用自己造 |

### StoreFulfillmentChannel

门店送货方式的一行（方案 v4：channel 挂门店，每店每路一行）。 <p>取代商家级 fulfillmentReach 单选。四路可配：STORE_PICKUP / NEIGHBOR_PICKUP / MERCHANT_DELIVERY / EXPRESS —— 服务类两值是商品属性，不出现在这里。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `channel` | [`FulfillmentType`](#fulfillmenttype) | 是 | — |
| `enabled` | `boolean` | 是 | — |
| `denied` | `boolean` | 是 | 准入矩阵不允许（按主体类型）。端上置灰＋原因，不隐藏 |
| `templateNo` | `string,null` | 否 | 仅 EXPRESS：运费模板号；空 = 平台默认模板 |
| `pickups` | [`PickupRef`](#pickupref)\[\] | 否 | 仅 NEIGHBOR_PICKUP（P1）：已引用的取货点，含 PENDING 的自建点（商家要看到「审核中」） |
| `locked` | `boolean` | 否 | 运营锁路（P2）：置灰不可改，文案「平台已暂停，联系运营」 |
| `scopeMode` | `string` | 否 | ALL / SUBSET（P2）：这一路只送经营范围的一个子集 |
| `areaNos` | `string`\[\] | 否 | SUBSET 时适用的范围项 area_no |

### StoreProfile

店铺门面（B-11.2 店铺装修 → C 端门店主页的数据源）。 与 Merchant 分开：Merchant 是平台建档的商家主数据（名称/资质/评分，商家改不了）， 这里是**店主自己能改的门面内容**。混在一起的话，改公告要走审核就荒谬了。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `announcement` | `string` | 是 | 店铺公告：「今日到货」「今天有土鸡蛋」，店主自发（C-ST-04） |
| `announcementUntil` | `number,null` | 否 | 公告失效时刻（epoch 毫秒）。**空 = 长期有效**。 过期由服务端读时判断，端上拿到的 `announcement` 已经是「此刻该显示的」—— 端上不要自己再判一次：两处判断迟早会不一致，而不一致的表现是 「商家看是空的、买家看到的是昨天的货」。 |
| `announcementRecent` | `string`\[\] | 否 | 最近用过的公告，最多 5 条，按最近使用排序。服务端维护，端上只读 |
| `noticePending` | `object`（见下） \| `null` | 否 | 正卡在人审里的那条公告（机审命中转的），没有就是 null。 **必须读它**：命中期间后端保留旧公告并返回旧资料 —— 端上不看这个字段的话， 会照旧提示「已发布」，而输入框还原成上一条，商家只会以为自己手滑， 反复再发一次，队列里堆出一串同样的单子。 |
| `openHours` | `string` | 是 | 营业时间文案，店主自填 |
| `address` | `string` | 是 | 店铺地址。**来自地图选点**（省市区 + 小区/路名），店主可改但一般不用改。 与  {@link  addressDetail }  分开：重新选点只覆盖这一条。 |
| `addressDetail` | `string` | 否 | 门牌号 / 楼栋（「3 栋 2 单元 501」），店主手填。 为什么单独一格：地图给的地址只到小区门口，而买家照着找门缺的正是这一截； 合成一格的话，商家补完再点一次选点就被整条覆盖 —— 补的那截无声消失， 地址看着还是对的，只是又回到了小区门口。 |
| `featured` | `string`\[\] | 是 | 主推商品，按顺序展示在门店主页首屏 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围（B 端自选）。**决定这家店的货在 C 端能被谁看到** —— 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款）， 选小了则整片小区的人都搜不到这家店。所以 B 端要给出后果说明，不能只给三个单选。 |
| `serviceCommunityNos` | `string`\[\] | 是 | scope=COMMUNITY 时覆盖的社区。空表示还没谈下任何小区，此时 C 端一律不可见 |
| `serviceCityCode` | `string` | 否 | scope=CITY 时覆盖的城市 |
| `fulfillmentReach` | [`FulfillmentReach`](#fulfillmentreach) | 否 | 履约能力（ADR-013 阶段二）。**只说「怎么送到你手上」**，送得到哪儿看  {@link  serviceAreas } 。 与上面两个 `@deprecated` 字段的关系：新旧两套并存期间，端上**只传一套** —— 传了 `serviceAreas` 就走新模型，后端不再看 `serviceScope`。 |
| `serviceAreas` | [`ServiceArea`](#servicearea)\[\] | 否 | 地理覆盖项，可跨粒度组合（三个小区 + 一个区）。 **空的含义由 `fulfillmentReach` 决定**，这是这个字段最容易踩的地方： PICKUP 空 = 谁也看不到（没配自提点就没法履约）； ONSITE / SHIPPING 空 = 不限。同一个空数组两种意思，所以别拿它判「有没有设置过」。 |
| `latE6` | `number,null` | 否 | 门店坐标（gcj02，E6）。地图选点回填；买家侧「门店自取」导航与候选取货点排距离靠它。 不传 = 这次不改；老版本端上不知道这个字段，后端不能把缺省当成清空。 |
| `lngE6` | `number,null` | 否 | — |

### StoreQrcode

店铺码（C-ST-08 扫码进店的商家侧）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 否 | 商家单号 |
| `storeCode` | `string` | 否 | 印在贴纸上的短码。**去掉了 0/O/1/I/L**，让人手输时不会认错 |
| `url` | `string,null` | 否 | 落地页链接。**未配对外域名时为 null** —— 端上据此不显示链接那一行。 ⚠️ 此前后端在两处各写死一个 `https://shop.example.com/s/<code>` 占位域名， 商家复制出去的链接与印出去的贴纸**全都指向一个不存在的地方**， 而这两个功能点在清单上标着「已实现」。不发假链接比发一个点不开的强。 |
| `imageBase64` | `string,null` | 否 | 店铺**小程序码**的 PNG base64（不含 `data:` 前缀）。通道未开启时为 null。 用小程序码而不是 H5 链接：ADR-004 的主获客路径是「码印在包装袋上，老客扫码直达」， 而小程序码**不依赖备案域名**（备案要 7–20 个工作日），扫了直接进门店页。 |
| `printableHint` | `string` | 否 | 打印建议，服务端给的一句话 |

### StoreRole

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 哪家店 |
| `storeName` | `string` | 是 | 门店名快照，列表直接显示，省一次查询 |
| `role` | [`StaffRole`](#staffrole) | 是 | MANAGER 店长 / CLERK 店员 |

### StoreStatus

门店状态。READONLY = 已停用（不再接新单，已有单照常履约）

枚举取值：

- `ACTIVE`
- `READONLY`

### SubmitPaymentReq

提交收款进件。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `payChannel` | `string` | 是 | 给哪个通道进件，如 WECHAT |
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 否 | 结算账户形态。不传时后端按法律形态取默认（小微打个人、其余对公） |
| `settleAccount` | `string` | 是 | 结算账号明文。见上方说明：**不落库、不进日志、不回显** |
| `licenses` | `string`\[\] | 否 | 资质图地址。小微免传，个体户与企业必传 |
| `contactName` | `string` | 否 | 进件联系人。通道核对资料时联系他，不一定等于登录人 |
| `contactPhone` | `string` | 否 | 进件联系电话 |
| `storeNo` | `string` | 否 | 为**哪家门店**进件；不传 = 主体级默认号（单店永远走这条）。 传它就是在走「分开结算」：微信侧一个商户号只能绑一个结算账户， 两家店各收各的钱，就得进件两次拿两个号。 |
| `entityNo` | `string` | 否 | 给哪张证照进件，可空 = 当前证照。多证照的老板在证照详情页进来时会带上它 |

### Supplier

一家供应商（`SupplierVO`）。进货单指向的那个**稳定对象** —— 在它之前只有一个会漂的名字字符串。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `supplierNo` | `string` | 是 | 档案编号 `SUP…`。**进货单存的是它，不是名字** —— 名字会改，指向不会 |
| `name` | `string` | 是 | 全名。同一商家内唯一（后端 `uk_sup_name`），重名建档会被 10409 拒 |
| `shortName` | `string,null` | 否 | 短名，单据列表上显示它 —— 长名换行会把一行撑成两行 |
| `contactName` | `string,null` | 否 | 联系人。**只作记录**，不发通知：这一版没有给供应商推消息的通道 |
| `contactPhone` | `string,null` | 否 | 联系电话。同上，给人打的，不参与任何自动流程 |
| `remark` | `string,null` | 否 | 备注。**引用平台档案时这一列仍归商家写** —— 那是他自己的话 |
| `status` | `string` | 是 | ACTIVE 在用 · ARCHIVED 已停用。**停用不删除** —— 历史单据要指得回去 |
| `fromPlatform` | `boolean` | 是 | 引用平台档案。**据此把名称与联系方式置灰** —— 不看这一位的话，商家会改了才发现改不动。 |

### ToggleCampaignReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `running` | `boolean` | 是 | 目标状态：true 启动、false 暂停。暂停不影响已领取的券 |

### ToggleGoodsReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `onSale` | `boolean` | 是 | 目标状态：true 上架、false 下架。下架后详情页仍可访问但不可下单 |

### TrafficSource

流量来源。**与 ops-web 的 `TrafficSource` 同名** —— 那边多 INVITE/CHANNEL 两个值（已标 MERGE）

枚举取值：

- `MERCHANT_OWNED`
- `PLATFORM`

### UploadImageReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `tempPath` | `string` | 是 | 端上的临时文件路径。真实实现走 multipart，这里是 mock 与 H5 的折中 |

### VerifyBatchReq

批量核销（后端已实现 `/biz/pickup/verify/batch`）。高峰期一个个扫码是真实痛点

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `verifyCodes` | `string`\[\] | 是 | 一批取货码。**逐条尝试、不整批回滚** —— 失败的逐条回报（见 `VerifyBatchResult`）， 否则一张废码会让另外几单白扫。 |

### VerifyBatchResult

批量核销结果。 **不是整批回滚**：逐条尝试，失败的逐条回报 —— 店主需要知道**哪一单**没成， 而不是「3 成功 2 失败」然后自己一个个找。整批回滚更糟：一张废码会让另外四单白扫。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `successCount` | `number` | 是 | 成功核销的单数 |
| `failed` | `object`（见下）\[\] | 是 | 失败明细。code 是那张码，reason 是为什么不行 |

`failed[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | — |
| `reason` | `string` | 是 | — |

### VerifyReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `verifyCode` | `string` | 是 | 取货码。字段名必须是 `verifyCode` —— 后端 `BizPickupController.VerifyReq` 收的是它。 这里曾经写作 `code`：**路径对得上、body 对不上**，守卫只比路径看不出来， 联调时才会以 400 的形式暴露。 |
| `onBehalf` | `boolean` | 否 | 代客核销（老人没带手机，店主代为确认）。留痕在服务端 |

### VirtualSpec

虚拟商品属性（VIRTUAL）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `deliverDesc` | `string` | 是 | 发放说明，如「支付后 1 分钟内短信发码」 |
