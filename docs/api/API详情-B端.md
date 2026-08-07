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
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | `MERCHANT_OWNED` \| `PLATFORM` | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | `MERCHANT_OWNED` \| `PLATFORM` | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | `MERCHANT_OWNED` \| `PLATFORM` | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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


### communities

#### GET `/biz/communities`

可选社区（设经营范围用）　🔒

**入参**：无

**出参**（`data`）

类型：[`Community`](#community)\[\]


### customers

#### GET `/biz/customers`

客户与复购　🔒

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


### goods

#### GET `/biz/goods`

商品列表　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `page` | query | `number` | 否 | 页码，从 1 起 |
| `size` | query | `number` | 否 | 每页条数 |
| `status` | query | [`GoodsStatus`](#goodsstatus) | 否 | 状态筛选，取值见对应枚举 |

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
| `slots` | [`AppointmentSlot`](#appointmentslot)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段 |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M） |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。不同商品可以给不同积分，不配则按成交额比例默认发放 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


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
| `slots` | [`AppointmentSlot`](#appointmentslot)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段 |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M） |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。不同商品可以给不同积分，不配则按成交额比例默认发放 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |

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
| `slots` | [`AppointmentSlot`](#appointmentslot)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段 |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M） |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。不同商品可以给不同积分，不配则按成交额比例默认发放 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


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

_无字段_

**出参**（`data`）

类型：[`Goods`](#goods)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
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
| `slots` | [`AppointmentSlot`](#appointmentslot)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段 |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M） |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。不同商品可以给不同积分，不配则按成交额比例默认发放 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |

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
| `groupNo` | `string` | 否 | MATCHED 后指向生成的正式团 |
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
| `members` | `object`（见下）\[\] | 是 | 已参团的人及各自件数，展示用 |
| `joined` | `boolean` | 是 | 当前用户是否已参团 |
| `neighborPickup` | [`PickupPoint`](#pickuppoint) | 否 | 邻里自提点（C-GB-06）：发起人勾选「送到我家」时有值。 参团者在这里取货，发起人负责签收与逐单核销 —— **零报酬**（ADR-005 §3）。 |
| `isOwner` | `boolean` | 否 | 我是不是这个团的发起人 —— 决定是否显示轻核销入口 |

`members[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `avatar` | `string` | 是 | — |
| `nickname` | `string` | 是 | — |
| `qty` | `number` | 是 | — |


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


#### GET `/biz/merchant/apply`

上次入驻申请　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantApplyReq`](#merchantapplyreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 拟用店铺名 |
| `subject` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型。个人 → 个体户 → 企业，门槛前低后高 |
| `contact` | `string` | 是 | 联系人姓名 |
| `phone` | `string` | 是 | 联系手机号 |
| `category` | `string` | 是 | 主营类目 |
| `desc` | `string` | 是 | 店铺简介 |
| `asPickupPoint` | `boolean` | 是 | 承接自提点：小店既是供给方也是取货点（ADR-005 type=STORE） |
| `licenses` | `string`\[\] | 是 | 资质图片（营业执照/身份证），个人主体可为空 |
| `settleAccountType` | `PERSONAL_OPENID` \| `MERCHANT_ID` | 是 | 结算账户类型。真实账号由后端持有，C 端与 B 端都不回显（ADR-002 §5） |


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


### order

#### GET `/biz/order`

订单列表　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `page` | query | `number` | 否 | 页码，从 1 起 |
| `size` | query | `number` | 否 | 每页条数 |
| `status` | query | [`OrderStatus`](#orderstatus) | 否 | 状态筛选，取值见对应枚举 |

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
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | `MERCHANT_OWNED` \| `PLATFORM` | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | `MERCHANT_OWNED` \| `PLATFORM` | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | `MERCHANT_OWNED` \| `PLATFORM` | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `kind` | `SHORTAGE` \| `DAMAGE` | 是 | 问题类型：少件 / 破损。两者的售后责任判定不同 |
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
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | `MERCHANT_OWNED` \| `PLATFORM` | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


#### POST `/biz/pickup/arrived`

标记到货　🔒

**入参**

请求体：[`MarkArrivedReq`](#markarrivedreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNos` | `string`\[\] | 是 | 批量：一次到货通常是一整批，逐单调用会让通知发成 N 条 |

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
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | `MERCHANT_OWNED` \| `PLATFORM` | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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


### settle

#### GET `/biz/settle/bills`

结算单列表　🔒

**入参**：无

**出参**（`data`）

类型：[`SettleBill`](#settlebill)\[\]


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


### spec-templates

#### GET `/biz/spec-templates`

规格模板　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `categoryType` | query | `string` | 否 | 品类形态 |

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
| `scope` | `PLATFORM` \| `MERCHANT` | 是 | 模板归属：平台统一维护 or 商家自存。商家只能改自己的 |
| `categoryType` | [`CategoryType`](#categorytype) | 否 | 平台模板按类目推荐；商家模板不限类目 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`SpecOption`](#specoption)\[\] | 是 | 该维度的可选项 |
| `merchantNo` | `string` | 否 | scope=MERCHANT 时归属的商家 |


### store

#### GET `/biz/store`

店铺门面　🔒

**入参**：无

**出参**（`data`）

类型：[`StoreProfile`](#storeprofile)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `announcement` | `string` | 是 | 店铺公告：「今日到货」「今天有土鸡蛋」，店主自发（C-ST-04） |
| `openHours` | `string` | 是 | 营业时间文案，店主自填 |
| `address` | `string` | 是 | 店铺地址，店主自填 |
| `featured` | `string`\[\] | 是 | 主推商品，按顺序展示在门店主页首屏 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围（B 端自选）。**决定这家店的货在 C 端能被谁看到** —— 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款）， 选小了则整片小区的人都搜不到这家店。所以 B 端要给出后果说明，不能只给三个单选。 |
| `serviceCommunityNos` | `string`\[\] | 是 | scope=COMMUNITY 时覆盖的社区。空表示还没谈下任何小区，此时 C 端一律不可见 |
| `serviceCityCode` | `string` | 否 | scope=CITY 时覆盖的城市 |


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
| `openHours` | `string` | 是 | 营业时间文案，店主自填 |
| `address` | `string` | 是 | 店铺地址，店主自填 |
| `featured` | `string`\[\] | 是 | 主推商品，按顺序展示在门店主页首屏 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围（B 端自选）。**决定这家店的货在 C 端能被谁看到** —— 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款）， 选小了则整片小区的人都搜不到这家店。所以 B 端要给出后果说明，不能只给三个单选。 |
| `serviceCommunityNos` | `string`\[\] | 是 | scope=COMMUNITY 时覆盖的社区。空表示还没谈下任何小区，此时 C 端一律不可见 |
| `serviceCityCode` | `string` | 否 | scope=CITY 时覆盖的城市 |


#### GET `/biz/store/qrcode`

店铺码　🔒

**入参**：无

**出参**（`data`）

类型：[`StoreQrcode`](#storeqrcode)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `url` | `string` | 是 | 扫码后进入的落地页地址，带 merchant_no 归因参数 |
| `printUrl` | `string` | 是 | 可打印版（贴纸尺寸），真实环境由后端生成小程序码 |


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
| `posterUrl` | `string` | 是 | 分享海报图 URL |


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

### AfterSale

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `afterSaleNo` | `string` | 是 | 售后单号。**售后是独立资源，不是订单上的一个字段** —— 它有自己的生命周期（申请→同意/驳回→寄回→收货→退款），能被取消、能上升平台， 一个订单还可能先后发起多次。挂在订单下用 orderNo 寻址，第二次申请就没法表达了。 后端一开始就是这么建的（/mp/after-sale/{afterSaleNo}/**），这里向它对齐。 |
| `type` | [`AfterSaleType`](#aftersaletype) | 是 | 售后类型：仅退款 / 退货退款 |
| `status` | [`AfterSaleStatus`](#aftersalestatus) | 是 | 售后单状态，独立于订单状态流转 |
| `reason` | `string` | 是 | 用户填写的售后原因 |
| `images` | `string`\[\] | 是 | 举证图（破损、少件的照片）。是否必填由售后类型决定 |
| `merchantReply` | `string` | 否 | 商家同意/驳回时的说明 |
| `returnExpressNo` | `string` | 否 | 用户寄回的运单号（RETURN_REFUND） |
| `disputeReason` | `string` | 否 | 上升平台时用户的申诉理由 |
| `updatedAt` | `number` | 是 | 最后一次状态变更时间。超时自动同意等时效规则以它为基准 |

### AfterSaleStatus

枚举取值：

- `PENDING`
- `AGREED`
- `RETURNING`
- `RECEIVED`
- `DONE`
- `REJECTED`
- `DISPUTED`

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

### AppointmentSlot

预约可选时段（SERVICE + APPOINTMENT）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `date` | `string` | 是 | YYYY-MM-DD（市场本地时区） |
| `times` | `object`（见下）\[\] | 是 | 当天各时段的余量。`time` 形如 `14:00`，`left` 为剩余可约数，0 表示约满 |

`times[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `time` | `string` | 是 | — |
| `left` | `number` | 是 | — |

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

### CategoryType

枚举取值：

- `GOODS`
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
| `distance` | `number` | 是 | 米 |
| `pickups` | [`Pickup`](#pickup)\[\] | 是 | 本社区可用的自提点 |

### CreateGroupReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 要开团的商品，必须是本店已上架商品 |

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

### FulfillmentType

枚举取值：

- `PICKUP`
- `NEIGHBOR_PICKUP`
- `DELIVERY`
- `EXPRESS`
- `STORE_VERIFY`
- `APPOINTMENT`
- `INSTANT`

### Goods

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `title` | `string` | 是 | 商品标题 |
| `subtitle` | `string` | 是 | 副标题/卖点一句话 |
| `cover` | `string` | 是 | 封面图 URL。列表页用这一张 |
| `images` | `string`\[\] | 是 | 详情轮播图 URL 列表 |
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
| `slots` | [`AppointmentSlot`](#appointmentslot)\[\] | 否 | SERVICE + APPOINTMENT：可预约时段 |
| `card` | [`CardSpec`](#cardspec) | 否 | CARD |
| `virtual` | [`VirtualSpec`](#virtualspec) | 否 | VIRTUAL |
| `promotions` | [`Promotion`](#promotion)\[\] | 否 | 促销（一期只有买 N 送 M） |
| `groupBuy` | `object`（见下） | 否 | 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 |
| `points` | `number` | 否 | 本商品每件赠送的积分。不同商品可以给不同积分，不配则按成交额比例默认发放 |
| `limitPerUser` | `number` | 是 | 每人限购，0 = 不限 |
| `onSale` | `boolean` | 是 | 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |

### GoodsDraft

商家侧商品草稿。单规格 = 一个规格组一个选项，与多规格同一套结构。 标题与副标题是**三语**：三语是一期范围（C端清单 §五之二）， 但此前商品文案只有一份，中文抄进三语 —— 切到英文看到的还是中文。 **只有中文必填**，其余留空时由服务端回落中文并标注未翻译（不做机翻，见 §M8-2）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 否 | 商品单号。新建时不传，编辑时必传 |
| `title` | [`I18nText`](#i18ntext) | 是 | 商品标题（多语言）。后端按 Accept-Language 下发对应语言给 C 端 |
| `subtitle` | [`I18nText`](#i18ntext) | 是 | 副标题/卖点（多语言） |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，决定详情页用哪套字段。**保存后不建议再改** |
| `specGroups` | [`SpecGroupDraft`](#specgroupdraft)\[\] | 是 | 空数组 = 单规格。非空则 skus 必须是各组选项的笛卡尔积 |
| `skus` | [`SkuDraft`](#skudraft)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |

### GrantType

登录方式。 · WX_MINI  小程序静默登录（只拿 openid，拿不到手机号） · WX_PHONE 小程序一键取手机号（推荐：一次授权直接拿到号，省掉短信） · WX_OPEN  App 微信开放平台 · APPLE    Apple 登录（iOS 上架硬要求） · PHONE_OTP 手机号 + 短信验证码（全端兜底，也是商家账号的主标识）

枚举取值：

- `WX_MINI`
- `WX_PHONE`
- `WX_OPEN`
- `PHONE_OTP`
- `APPLE`

### GroupBuy

商家团 —— 商家在已上架商品上开的团，用户可参与或自己开一桌。 定位：**只是一种活动**，不是平台核心机制。所以单档成团，不做阶梯价。，不是运营配置的活动位。 成团单位是自提点（拼的是一车送到一个点的成本），单档成团，不做阶梯。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `groupNo` | `string` | 是 | 团单号 |
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
| `members` | `object`（见下）\[\] | 是 | 已参团的人及各自件数，展示用 |
| `joined` | `boolean` | 是 | 当前用户是否已参团 |
| `neighborPickup` | [`PickupPoint`](#pickuppoint) | 否 | 邻里自提点（C-GB-06）：发起人勾选「送到我家」时有值。 参团者在这里取货，发起人负责签收与逐单核销 —— **零报酬**（ADR-005 §3）。 |
| `isOwner` | `boolean` | 否 | 我是不是这个团的发起人 —— 决定是否显示轻核销入口 |

`members[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `avatar` | `string` | 是 | — |
| `nickname` | `string` | 是 | — |
| `qty` | `number` | 是 | — |

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
| `groupNo` | `string` | 否 | MATCHED 后指向生成的正式团 |
| `lockedPriceMinor` | `number` | 否 | 选定的报价快照。转成正式团后下单用这个价，**不读商家当前价** —— 这是防加价最硬的一层：加价在技术上做不到，不需要审核。 |
| `confirmed` | `boolean` | 否 | 我（+1 的邻居）是否已二次确认下单。+1 不等于承诺，必须各自确认 |
| `confirmedCount` | `number` | 否 | 已确认下单的人数 |

`neighbours[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `avatar` | `string` | 是 | — |
| `nickname` | `string` | 是 | — |

### GroupRequestStatus

邻里求团：**需求先于供给**。 与「商家团」是两条完全不同的线，刻意不复用一个模型：   商家团 —— 商品已上架、价格已定、库存已备，用户只是参与；适合生鲜日用这类高频标品。   求团   —— 发起时**商品还不存在，甚至没有商家**，用户只有一句「想买儿童床垫」；            适合床垫、校服、家电这类低频高单价、有议价空间的非标品。 关键约束：**意向 ≠ 订单**。求团阶段不收钱、不锁库存 —— 商品还不存在时收钱是给自己找麻烦。 只有发起人选定报价、转成正式商家团之后，才进入交易链路。

枚举取值：

- `OPEN`
- `QUOTING`
- `MATCHED`
- `CLOSED`
- `EXPIRED`

### HandleAfterSaleReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `remark` | `string` | 是 | 驳回理由，**必填**（后端 `@NotBlank`）：用户拿不到理由只能升级平台， 平台再回头问商家，多绕一圈。 字段名是 `remark` 不是 `reply` —— 后端 `BizAfterSaleController.RejectReq` 收的是它。 |

### I18nText

多语言文案（mock 内部用；对外契约由后端按 Accept-Language 返回已本地化的 string）

类型：`Record_Lang_string`

### MarkArrivedReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNos` | `string`\[\] | 是 | 批量：一次到货通常是一整批，逐单调用会让通知发成 N 条 |

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

### MerchantApplyReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 拟用店铺名 |
| `subject` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型。个人 → 个体户 → 企业，门槛前低后高 |
| `contact` | `string` | 是 | 联系人姓名 |
| `phone` | `string` | 是 | 联系手机号 |
| `category` | `string` | 是 | 主营类目 |
| `desc` | `string` | 是 | 店铺简介 |
| `asPickupPoint` | `boolean` | 是 | 承接自提点：小店既是供给方也是取货点（ADR-005 type=STORE） |
| `licenses` | `string`\[\] | 是 | 资质图片（营业执照/身份证），个人主体可为空 |
| `settleAccountType` | `PERSONAL_OPENID` \| `MERCHANT_ID` | 是 | 结算账户类型。真实账号由后端持有，C 端与 B 端都不回显（ADR-002 §5） |

### MerchantApplyReqBody

入驻申请。字段与共享层的 `MerchantApplyReq` 一致，这里只是给契约一个稳定的 DTO 名

类型：`MerchantApplyReq`

### MerchantBrief

商品卡/详情上挂的商家简要信息

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家单号。贯穿商品/订单/评价/结算，是多商家模型的主线（ADR-001） |
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo URL |
| `rating` | `number` | 是 | 综合评分，0–5，保留一位小数 |
| `verified` | `boolean` | 是 | 是否通过资质认证 |
| `breachCount` | `number` | 是 | 选定报价后不履约的次数。>0 会在报价卡上公示 —— 事后信用替代事前审核 |

### MerchantCustomer

商家的客户（B-11.2.8）。 这是「商家自带客流」定位下最该给店主看的东西：**谁在买、谁不来了**。 平台电商给商家看的是流量与转化；小店老板要的是「张阿姨上个月每周都来，这半个月没来」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `nickname` | `string` | 是 | 客户昵称 |
| `avatar` | `string` | 是 | 客户头像 |
| `orderCount` | `number` | 是 | 在本店的累计下单次数 |
| `totalSpentMinor` | `number` | 是 | 在本店的累计消费额（最小货币单位） |
| `lastOrderAt` | `number` | 是 | 最近一次下单时间 |
| `daysSinceLast` | `number` | 是 | 距上次下单天数 |
| `silent` | `boolean` | 是 | 沉默客户：曾经常来、最近没来。**这是店主唯一能立刻行动的信号** |
| `source` | `MERCHANT_OWNED` \| `PLATFORM` | 是 | 客流来源：他是你自己带来的，还是平台分配的 |

### MerchantLoginReqBody

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `grantType` | [`GrantType`](#granttype) | 是 | 登录方式。**商家池与 C 端用户池是两套账号**，同一手机号登两端是两个身份 |
| `principal` | `string` | 是 | `WX_MINI`: wx.login code；`PHONE_OTP`: 手机号 |
| `credential` | `string` | 否 | `PHONE_OTP`: 验证码 |

### MerchantLoginResp

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `token` | `string` | 是 | 访问令牌。**商家池与 C 端用户池是两套账号**，token 不通用 |
| `merchant` | [`MerchantProfile`](#merchantprofile) | 是 | 商家档案 |

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

商家入驻审核状态。与 C 端 LeaderStatus 无关 —— 团长角色已删除（ADR-004）

枚举取值：

- `NONE`
- `APPLYING`
- `REJECTED`
- `ACTIVE`
- `SUSPENDED`

### MerchantSubject

主体类型。个人 → 个体户 → 企业，门槛前低后高（ADR-002 §4）

枚举取值：

- `PERSONAL`
- `INDIVIDUAL_BIZ`
- `COMPANY`

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
| `toVerify` | `number` | 是 | 待核销单数（自提到货、买家还没来取） |
| `toPick` | `number` | 是 | 待分拣单数（到货后按商品汇总点数） |
| `afterSale` | `number` | 是 | 待处理售后单数 |
| `toReply` | `number` | 是 | 待回复的评价数 |
| `quotable` | `number` | 是 | 可报价的求团需求数 |

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
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | `MERCHANT_OWNED` \| `PLATFORM` | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
| `groupNo` | `string` | 否 | 参与的团。邻里自提的核销作用域就靠它裁剪（E16） |
| `afterSale` | [`AfterSale`](#aftersale) | 否 | 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 |
| `merchantNo` | `string` | 否 | 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。 |
| `merchantName` | `string` | 否 | 商家名快照 |
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |

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

### OrderStatus

枚举取值：

- `WAIT_PAY`
- `PAID`
- `PREPARING`
- `ARRIVED`
- `SHIPPED`
- `COMPLETED`
- `CANCELLED`
- `REFUNDING`
- `REFUNDED`

### OrderTimelineNode

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `status` | [`OrderStatus`](#orderstatus) | 是 | 流转到的状态 |
| `label` | `string` | 是 | 展示文案，如「已到货，请到自提点取货」。后端下发已本地化 |
| `at` | `number` | 是 | 发生时间 |

### Partial_Record_CurrencyCode_number

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `CNY` | `number` | 否 | — |
| `USD` | `number` | 否 | — |
| `AED` | `number` | 否 | — |

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

### PickupOverview

自提点履约总览（后端 `GET /biz/pickup/overview`）。 承接方最关心的三个数：还有几单没人来取、今天到了几批、这些活挣了多少服务费。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | 自提点单号 |
| `pickupName` | `string` | 是 | 自提点名称 |
| `pendingVerify` | `number` | 是 | 待核销单数 —— 到货了还没人来取的 |
| `arrivedBatches` | `number` | 是 | 今日到货批次 |
| `serviceFeeMinor` | `number` | 是 | 累计履约服务费（最小货币单位） |

### PickupPoint

自提点实体。 取代了原先的 `Merchant.isPickupPoint` 布尔字段 —— 那个表达不了「承接方是用户」： 邻里自提是送到**团发起人家里**，承接的是邻居本人，不是商家。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | 自提点单号 |
| `type` | `STORE` \| `NEIGHBOR` \| `PLATFORM` | 是 | 自提点由谁承接。**三档，各自的费用规则完全不同**（2026-08-06 定）：   · STORE    商家自己的门店 —— 商家自行解决，平台不收履约服务费   · NEIGHBOR 团发起人家里 —— **零报酬**（ADR-005），有报酬就是团长招募换个名字   · PLATFORM 平台提供的点 —— 收履约服务费，**费率线下逐点协商，由运营平台录入** |
| `ownerType` | `MERCHANT` \| `USER` \| `PLATFORM` | 是 | 承接方所属账号池 |
| `ownerNo` | `string` | 是 | 承接方单号，按 ownerType 落在 merchantNo 或 cUserNo 上 |
| `scope` | `PERMANENT` \| `GROUP_INSTANCE` | 是 | 常驻 \| 团粒度（一团一销） |
| `groupNo` | `string` | 否 | type=NEIGHBOR 时必填：这个点只服务这一个团 |
| `name` | `string` | 是 | 自提点名称 |
| `address` | `string` | 是 | 展示地址。**成团前只到楼栋，付款后才给完整门牌**（B13）—— 未成团的团不该暴露发起人住址。 |
| `timeSlot` | `string` | 否 | 约定取货时段。邻居家不能一直堆着货（B15） |
| `feeMode` | `NONE` \| `PER_ITEM` \| `RATE` | 是 | 计费口径。**必须显式标出用哪一种** —— 库里按件与按率两列长期并存， 没有判别列的话结算侧只能猜，猜错就是给自提点少付或多付钱。 之所以两种都留：费率是**线下逐点协商**的，有的点谈成按件、有的谈成按成交额抽成， 硬统一成一种会让运营在谈判里没有筹码。 |
| `serviceFeePerItemMinor` | `number` | 是 | feeMode=PER_ITEM 时的按件服务费。STORE 与 NEIGHBOR 恒为 0 |
| `serviceFeeRate` | `number` | 是 | feeMode=RATE 时的费率（万分比）。STORE 与 NEIGHBOR 恒为 0 |

### Promotion

促销：买 N 送 M。 语义：购买数量达到 N 件，赠送 M 件 —— 用户**付 N 件的钱，收到 N+M 件**。 赠品不进计价（价格为 0），只作为订单里的独立行存在，履约时随单发出。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | `string` | 是 | 促销类型。目前只有买 N 送 M 一种 |
| `buyN` | `number` | 是 | 购买件数门槛 N |
| `giftM` | `number` | 是 | 赠送件数 M |
| `giftGoodsNo` | `string` | 否 | 赠品商品号；不填则赠同款 |
| `giftTitle` | `string` | 否 | 赠品展示名（后端下发已本地化） |

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

### RecognizeGoodsReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `imageUrl` | `string` | 是 | 待识别的商品图 URL（先走 upload/image 拿到）。返回识别出的标题与类目建议 |

### Record_Lang_string

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `zh-CN` | `string` | 是 | — |
| `en` | `string` | 是 | — |
| `ar` | `string` | 是 | — |

### ReplyReviewReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reply` | `string` | 是 | 回复内容。公开展示在评价下方，一条评价只能回一次 |

### ReportShortageReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 出问题的 SKU |
| `kind` | `SHORTAGE` \| `DAMAGE` | 是 | 问题类型：少件 / 破损。两者的售后责任判定不同 |
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

类型：`GoodsDraft`

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

### ServiceScope

枚举取值：

- `COMMUNITY`
- `CITY`
- `PLATFORM`

### SettleBill

结算单。分账以子订单为单位（ADR-002 §5）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `billNo` | `string` | 是 | 结算单号 |
| `periodStart` | `number` | 是 | 结算周期起（含） |
| `periodEnd` | `number` | 是 | 结算周期止（含） |
| `payableMinor` | `number` | 是 | 应分金额 |
| `settledMinor` | `number` | 是 | 已分账金额 |
| `commissionMinor` | `number` | 是 | 平台佣金 |
| `fulfillFeeMinor` | `number` | 是 | 自提点履约服务费（承接方收，供货方付；口径待定 B9） |
| `status` | `PENDING` \| `PARTIAL` \| `DONE` \| `EXPIRED` | 是 | 结算状态：待结算 / 部分已结 / 已结清 / 已过期 |
| `currency` | [`CurrencyCode`](#currencycode) | 是 | 结算币种 |
| `orderCount` | `number` | 是 | 本期订单笔数 |

### ShareKit

分享素材（B-11.2.7）。文案与海报由服务端按当前语言与市场生成

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `text` | `string` | 是 | 分享文案，已按当前语言与市场生成 |
| `posterUrl` | `string` | 是 | 分享海报图 URL |

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

### SkuDraft

SKU 草稿。`optionValues` 的顺序与 `specGroups` 一一对应 —— 这是矩阵的坐标，错位就会出现「5 斤卖成 10 斤的价」。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 否 | 已有 SKU 带上原编号，改价改库存不会丢历史订单的引用 |
| `optionValues` | `string`\[\] | 是 | 各规格维度上的取值，顺序与 specGroups 一一对应 |
| `price` | `number` | 是 | 当前市场的价（最小货币单位）。兼容单市场调用 |
| `priceByMarket` | [`Partial_Record_CurrencyCode_number`](#partial_record_currencycode_number) | 否 | **按市场分别定价**（B6）。未填的市场不在该市场售卖 —— 汇率换算出的价没有价格心理学（¥29.9 → $4.19 不是任何人会标的价）， 且汇率一动全店价格跟着抖，而商家并没有调价。 |
| `stock` | `number` | 是 | 可售库存 |

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

规格模板。两层：   · PLATFORM —— 平台按类目预置，可聚合可筛选   · MERCHANT —— 商家把自己常用的存下来，第二次建品直接套 ⚠️ **模板是建议不是强制**：卖手工酱菜的没有匹配模板，硬要他选就只能瞎选。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `templateNo` | `string` | 是 | 模板单号 |
| `scope` | `PLATFORM` \| `MERCHANT` | 是 | 模板归属：平台统一维护 or 商家自存。商家只能改自己的 |
| `categoryType` | [`CategoryType`](#categorytype) | 否 | 平台模板按类目推荐；商家模板不限类目 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`SpecOption`](#specoption)\[\] | 是 | 该维度的可选项 |
| `merchantNo` | `string` | 否 | scope=MERCHANT 时归属的商家 |

### StoreProfile

店铺门面（B-11.2 店铺装修 → C 端门店主页的数据源）。 与 Merchant 分开：Merchant 是平台建档的商家主数据（名称/资质/评分，商家改不了）， 这里是**店主自己能改的门面内容**。混在一起的话，改公告要走审核就荒谬了。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `announcement` | `string` | 是 | 店铺公告：「今日到货」「今天有土鸡蛋」，店主自发（C-ST-04） |
| `openHours` | `string` | 是 | 营业时间文案，店主自填 |
| `address` | `string` | 是 | 店铺地址，店主自填 |
| `featured` | `string`\[\] | 是 | 主推商品，按顺序展示在门店主页首屏 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围（B 端自选）。**决定这家店的货在 C 端能被谁看到** —— 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款）， 选小了则整片小区的人都搜不到这家店。所以 B 端要给出后果说明，不能只给三个单选。 |
| `serviceCommunityNos` | `string`\[\] | 是 | scope=COMMUNITY 时覆盖的社区。空表示还没谈下任何小区，此时 C 端一律不可见 |
| `serviceCityCode` | `string` | 否 | scope=CITY 时覆盖的城市 |

### StoreQrcode

店铺码（C-ST-08 扫码进店的商家侧）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `url` | `string` | 是 | 扫码后进入的落地页地址，带 merchant_no 归因参数 |
| `printUrl` | `string` | 是 | 可打印版（贴纸尺寸），真实环境由后端生成小程序码 |

### ToggleCampaignReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `running` | `boolean` | 是 | 目标状态：true 启动、false 暂停。暂停不影响已领取的券 |

### ToggleGoodsReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `onSale` | `boolean` | 是 | 目标状态：true 上架、false 下架。下架后详情页仍可访问但不可下单 |

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
