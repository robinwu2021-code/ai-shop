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
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
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
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
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
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
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

类型：[`void`](#void)


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
| `perms` | `string`\[\] | 是 | 这些角色合起来的权限码，**已取并集**（老板是 `["*"]`）。 端上照它裁剪入口，**不要自己按角色再推一遍** —— 两处各推一次迟早分岔， 而分岔的表现是「看得见但点了报错」。 |


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
| `status` | [`MerchantGoodsStatus`](#merchantgoodsstatus) | 否 | 审核与在售状态（**只有商家侧 `/biz/goods` 下发**，C 端拿不到也不需要）。 为什么不能只看 `onSale`：新建和每次改动都会回到审核中，而那时 `onSale` 是 false —— 界面照着布尔值写就成了「已下架 + 上架按钮」， 点下去后端必然拒（70003「商品还在审核中」）。**商家看到的是一个永远点不动的按钮**。 ⚠️ 待审在这里是 `AUDITING`（后端 `prd_goods.audit_status` 的原值）， 而  {@link  GoodsStatus }  用的是 `PENDING`（ops-web 的 SkuStatus 口径）—— 同一件事两个词，词典 §11 该收敛哪一个还没定。这里如实写后端发的那个。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |

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
| `status` | [`MerchantGoodsStatus`](#merchantgoodsstatus) | 否 | 审核与在售状态（**只有商家侧 `/biz/goods` 下发**，C 端拿不到也不需要）。 为什么不能只看 `onSale`：新建和每次改动都会回到审核中，而那时 `onSale` 是 false —— 界面照着布尔值写就成了「已下架 + 上架按钮」， 点下去后端必然拒（70003「商品还在审核中」）。**商家看到的是一个永远点不动的按钮**。 ⚠️ 待审在这里是 `AUDITING`（后端 `prd_goods.audit_status` 的原值）， 而  {@link  GoodsStatus }  用的是 `PENDING`（ops-web 的 SkuStatus 口径）—— 同一件事两个词，词典 §11 该收敛哪一个还没定。这里如实写后端发的那个。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |

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
| `status` | [`MerchantGoodsStatus`](#merchantgoodsstatus) | 否 | 审核与在售状态（**只有商家侧 `/biz/goods` 下发**，C 端拿不到也不需要）。 为什么不能只看 `onSale`：新建和每次改动都会回到审核中，而那时 `onSale` 是 false —— 界面照着布尔值写就成了「已下架 + 上架按钮」， 点下去后端必然拒（70003「商品还在审核中」）。**商家看到的是一个永远点不动的按钮**。 ⚠️ 待审在这里是 `AUDITING`（后端 `prd_goods.audit_status` 的原值）， 而  {@link  GoodsStatus }  用的是 `PENDING`（ops-web 的 SkuStatus 口径）—— 同一件事两个词，词典 §11 该收敛哪一个还没定。这里如实写后端发的那个。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |

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
| `status` | [`MerchantGoodsStatus`](#merchantgoodsstatus) | 否 | 审核与在售状态（**只有商家侧 `/biz/goods` 下发**，C 端拿不到也不需要）。 为什么不能只看 `onSale`：新建和每次改动都会回到审核中，而那时 `onSale` 是 false —— 界面照着布尔值写就成了「已下架 + 上架按钮」， 点下去后端必然拒（70003「商品还在审核中」）。**商家看到的是一个永远点不动的按钮**。 ⚠️ 待审在这里是 `AUDITING`（后端 `prd_goods.audit_status` 的原值）， 而  {@link  GoodsStatus }  用的是 `PENDING`（ops-web 的 SkuStatus 口径）—— 同一件事两个词，词典 §11 该收敛哪一个还没定。这里如实写后端发的那个。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |

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

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 否 | 商品单号。新建时不传，编辑时必传 |
| `title` | `string` | 是 | 基准语言（zh-CN）的标题。后端按 Accept-Language 下发时的兜底 |
| `subtitle` | `string` | 是 | 基准语言（zh-CN）的副标题/卖点 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 是 | 标题的三语原文，键是 Lang。缺译的语言按 R9 回落展示中文 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 是 | 副标题的三语原文，同上 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，决定履约与合规（生鲜要截单、服务不发货、iOS 可售规则） |
| `categoryNo` | `string` | 否 | 类目单号。选填，决定归类与经营准入 —— 与 `type` 是两个正交维度 |
| `cover` | `string` | 否 | 封面图 URL（来自 mUploadImage）。漏传的话 C 端列表里是一块留白，且不报错 |
| `images` | `string`\[\] | 否 | 详情轮播图 |
| `specGroups` | [`SpecGroupDraft`](#specgroupdraft)\[\] | 是 | 空数组 = 单规格。非空则 skus 必须是各组选项的笛卡尔积 |
| `fulfillments` | `string`\[\] | 否 | 支持的履约方式；不传 = 不改（新建默认四种全支持） |
| `skus` | [`SkuDraft`](#skudraft)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |

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
| `status` | [`MerchantGoodsStatus`](#merchantgoodsstatus) | 否 | 审核与在售状态（**只有商家侧 `/biz/goods` 下发**，C 端拿不到也不需要）。 为什么不能只看 `onSale`：新建和每次改动都会回到审核中，而那时 `onSale` 是 false —— 界面照着布尔值写就成了「已下架 + 上架按钮」， 点下去后端必然拒（70003「商品还在审核中」）。**商家看到的是一个永远点不动的按钮**。 ⚠️ 待审在这里是 `AUDITING`（后端 `prd_goods.audit_status` 的原值）， 而  {@link  GoodsStatus }  用的是 `PENDING`（ops-web 的 SkuStatus 口径）—— 同一件事两个词，词典 §11 该收敛哪一个还没定。这里如实写后端发的那个。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |

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
| `contactName` | `string` | 是 | 联系人姓名。审核要打电话找人，只有号码没有姓名不合适 |
| `contactPhone` | `string` | 是 | 联系手机号 |
| `category` | `string` | 是 | 主营类目 |
| `desc` | `string` | 是 | 店铺简介 |
| `asPickupPoint` | `boolean` | 否 | 承接自提点：小店既是供给方也是取货点（ADR-005 type=STORE） |
| `serviceScope` | [`ServiceScope`](#servicescope) | 否 | 期望经营范围（ADR-009）。申请时可空，<b>审核通过时必须确定</b> —— 否则商家上着架却对谁都不可见，且没有任何报错。 |
| `communityNos` | `string`\[\] | 否 | 期望覆盖的社区。scope=COMMUNITY 时审核通过必须非空 |
| `licenses` | `string`\[\] | 否 | 资质图片（营业执照/身份证）。**选填** —— 一期 EDI 不强制。 与下面的结算账户一样，属于**分账主体开户**而不是入驻申请本身（ADR-002）： `usr_merchant_payment` 是独立一张表、有自己的 `apply_status`，就是这个道理。 申请时能传就传，通过后在 B 端补也行 —— 逼一个还没通过审核的人先传营业执照， 只会把人挡在门外。 |
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 否 | 结算账户类型。真实账号由后端持有，C 端与 B 端都不回显（ADR-002 §5）。**选填**，同上 |
| `industry` | `string` | 否 | 行业（`sys_industry.industry`）。 **它决定这家店能不能以小微主体进件** —— 微信的小微白名单是按行业给的， 也是 `points_forced` 默认值的来源。 后端一直在收、库里一直有这一列，但契约没登记、端也没传， 于是 `mch_entity.industry` 恒空：进件时才发现主体类型选错了， 而那时商家已经开完店、上完架。 |


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
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 否 | 结算账户形态：小微打个人（PERSONAL_OPENID），其余打对公（MERCHANT_ID） |
| `settleAccountMasked` | `string` | 否 | 结算账号掩码。**明文永不回显**，包括给商家自己（ADR-002 §5） |
| `rejectReason` | `string` | 否 | 驳回原因。驳回时必有 —— 没有原因商家只能反复重提 |
| `missing` | `string`\[\] | 是 | 还缺哪些资料（settleAccount / licenses / settleAccountType）。空 = 资料齐了在等通道 |
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
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 否 | 结算账户形态：小微打个人（PERSONAL_OPENID），其余打对公（MERCHANT_ID） |
| `settleAccountMasked` | `string` | 否 | 结算账号掩码。**明文永不回显**，包括给商家自己（ADR-002 §5） |
| `rejectReason` | `string` | 否 | 驳回原因。驳回时必有 —— 没有原因商家只能反复重提 |
| `missing` | `string`\[\] | 是 | 还缺哪些资料（settleAccount / licenses / settleAccountType）。空 = 资料齐了在等通道 |
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
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
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
| `receiver` | [`OrderReceiver`](#orderreceiver) | 否 | 收件人（下单时的**快照**，自提单没有）。 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了， 而货已经按旧地址在路上。 ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号 （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。 |
| `reviewed` | `boolean` | 否 | 已评价 |
| `pointsGranted` | `boolean` | 否 | 积分是否已发放（幂等标记，防止重复核销重复发分） |
| `trafficSource` | [`TrafficSource`](#trafficsource) | 否 | 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。 |
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
| `kind` | [`ArrivalIssueKind`](#arrivalissuekind) | 是 | 问题类型：少件 / 破损。两者的售后责任判定不同 |
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
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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


#### GET `/biz/pickup/verify/search`

按取货码片段搜单　🔒

**入参**：无

**出参**（`data`）

类型：[`Order`](#order)\[\]


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


### regions

#### GET `/biz/regions`

行政区划下一级（框覆盖范围用）　🔒

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

类型：[`void`](#void)


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
| `scope` | [`SpecTemplateScope`](#spectemplatescope) | 是 | 模板归属：平台统一维护 or 商家自存。商家只能改自己的 |
| `categoryType` | [`CategoryType`](#categorytype) | 否 | 平台模板按类目推荐；商家模板不限类目 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`SpecOption`](#specoption)\[\] | 是 | 该维度的可选项 |
| `merchantNo` | `string` | 否 | scope=MERCHANT 时归属的商家 |


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
| `openHours` | `string` | 是 | 营业时间文案，店主自填 |
| `address` | `string` | 是 | 店铺地址，店主自填 |
| `featured` | `string`\[\] | 是 | 主推商品，按顺序展示在门店主页首屏 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围（B 端自选）。**决定这家店的货在 C 端能被谁看到** —— 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款）， 选小了则整片小区的人都搜不到这家店。所以 B 端要给出后果说明，不能只给三个单选。 |
| `serviceCommunityNos` | `string`\[\] | 是 | scope=COMMUNITY 时覆盖的社区。空表示还没谈下任何小区，此时 C 端一律不可见 |
| `serviceCityCode` | `string` | 否 | scope=CITY 时覆盖的城市 |
| `fulfillmentReach` | [`FulfillmentReach`](#fulfillmentreach) | 否 | 履约能力（ADR-013 阶段二）。**只说「怎么送到你手上」**，送得到哪儿看  {@link  serviceAreas } 。 与上面两个 `@deprecated` 字段的关系：新旧两套并存期间，端上**只传一套** —— 传了 `serviceAreas` 就走新模型，后端不再看 `serviceScope`。 |
| `serviceAreas` | [`ServiceArea`](#servicearea)\[\] | 否 | 地理覆盖项，可跨粒度组合（三个小区 + 一个区）。 **空的含义由 `fulfillmentReach` 决定**，这是这个字段最容易踩的地方： PICKUP 空 = 谁也看不到（没配自提点就没法履约）； ONSITE / SHIPPING 空 = 不限。同一个空数组两种意思，所以别拿它判「有没有设置过」。 |


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
| `fulfillmentReach` | [`FulfillmentReach`](#fulfillmentreach) | 否 | 履约能力（ADR-013 阶段二）。**只说「怎么送到你手上」**，送得到哪儿看  {@link  serviceAreas } 。 与上面两个 `@deprecated` 字段的关系：新旧两套并存期间，端上**只传一套** —— 传了 `serviceAreas` 就走新模型，后端不再看 `serviceScope`。 |
| `serviceAreas` | [`ServiceArea`](#servicearea)\[\] | 否 | 地理覆盖项，可跨粒度组合（三个小区 + 一个区）。 **空的含义由 `fulfillmentReach` 决定**，这是这个字段最容易踩的地方： PICKUP 空 = 谁也看不到（没配自提点就没法履约）； ONSITE / SHIPPING 空 = 不限。同一个空数组两种意思，所以别拿它判「有没有设置过」。 |


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


#### POST `/biz/store/create`

新建门店　🔒

**入参**

请求体：[`StoreEditReq`](#storeeditreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 否 | 门店地址 |

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


#### GET `/biz/store/list`

我的门店　🔒

**入参**：无

**出参**（`data`）

类型：[`Store`](#store)\[\]


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
| `merchantReply` | `string` | 否 | 商家同意/驳回时的说明 |
| `returnExpressNo` | `string` | 否 | 用户寄回的运单号（RETURN_REFUND） |
| `disputeReason` | `string` | 否 | 上升平台时用户的申诉理由 |
| `updatedAt` | `number` | 是 | 最后一次状态变更时间。超时自动同意等时效规则以它为基准 |

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

### AreaLevel

枚举取值：

- `COMMUNITY`
- `STREET`
- `DISTRICT`
- `CITY`

### AreaStatus

枚举取值：

- `ACTIVE`
- `PENDING`

### ArrivalIssueKind

到货异常类型：缺件 / 破损。B 端到货登记时上报（ADR-005 履约链路）

枚举取值：

- `SHORTAGE`
- `DAMAGE`

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

### Category

类目树节点（对齐后端 `CategoryVO`）。 <p>⚠️ **不要把它和 `CategoryType` 搞混** —— 那是五品类枚举 （NORMAL/FRESH/SERVICE/VIRTUAL/CARD），挂在商品上、由平台硬编码，决定履约与合规 （冷链、不发货、iOS 可售规则）。这里的类目树是运营可维护的数据，决定归类与经营准入。 两个维度正交，见 `docs/technical/类目树补齐方案.md`。 <p>这个类型此前声明了一个后端根本不返回的 `type` 字段，并写着「仅两级」—— 而后端一直是三级。没人用它，所以错了很久也没暴露。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categoryNo` | `string` | 是 | 类目单号 |
| `parentNo` | `string,null` | 否 | 上级类目单号。一级类目为空 |
| `level` | `number` | 是 | 1–3。**三级封顶** |
| `name` | `string` | 是 | 类目名（后端按 Accept-Language 下发已本地化文案） |
| `icon` | `string` | 否 | 类目图标 URL。运营没配就是空串，端上按占位渲染 |
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
| `distance` | `number` | 是 | 米 |
| `pickups` | [`Pickup`](#pickup)\[\] | 是 | 本社区可用的自提点 |

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
| `status` | [`MerchantGoodsStatus`](#merchantgoodsstatus) | 否 | 审核与在售状态（**只有商家侧 `/biz/goods` 下发**，C 端拿不到也不需要）。 为什么不能只看 `onSale`：新建和每次改动都会回到审核中，而那时 `onSale` 是 false —— 界面照着布尔值写就成了「已下架 + 上架按钮」， 点下去后端必然拒（70003「商品还在审核中」）。**商家看到的是一个永远点不动的按钮**。 ⚠️ 待审在这里是 `AUDITING`（后端 `prd_goods.audit_status` 的原值）， 而  {@link  GoodsStatus }  用的是 `PENDING`（ops-web 的 SkuStatus 口径）—— 同一件事两个词，词典 §11 该收敛哪一个还没定。这里如实写后端发的那个。 |
| `titleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格， 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**： C 端缺译文时回落中文，看起来一切正常。 |
| `subtitleI18n` | [`Record_string_string`](#record_string_string) | 否 | 三语副标题原文，同 `titleI18n` |

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |

### GrantStoreReq

授予或撤销**一个**门店角色。 **增量式，不是覆盖式**：这一次只动 `role` 这一个角色，不碰他在这家店的其他角色。 覆盖式在多角色下是错的 —— 老板想「再加一个配送员」，结果把「店员」冲掉了。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `storeNo` | `string` | 是 | 授权到哪家店。只能是本主体的门店 |
| `role` | [`StaffRole`](#staffrole) | 是 | 要授予/撤销的那一个角色 |
| `granted` | `boolean` | 否 | true 授予（默认）、false 撤销。撤到一个不剩 = 从这家店移除他 |

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
| `source` | [`TrafficSource`](#trafficsource) | 是 | 客流来源：他是你自己带来的，还是平台分配的 |

### MerchantGoodsStatus

商家侧商品状态（`/biz/goods` 下发的四态）。 ⚠️ 待审在这里是 `AUDITING`（后端 `prd_goods.audit_status` 的原值）， 而  {@link  GoodsStatus }  用 `PENDING`（ops-web 的 SkuStatus 口径）—— **同一件事两个词**，词典 §11 该收敛哪一个还没定。这里如实写后端发的那个， 收敛之前不要在端上做映射：映射会让「界面显示对了」掩盖住口径还没统一。

枚举取值：

- `ON_SALE`
- `OFF_SALE`
- `AUDITING`
- `REJECTED`

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
- `ACTIVE`
- `SUSPENDED`

### MerchantSubject

商家主体类型 —— **权威口径取通道侧**（ADR-010）。 主体类型的唯一硬约束来自支付通道：能不能进件、要什么资质、钱打到个人还是对公。 展示名反而可以随便改。让权威贴着约束走，映射就只需要一个方向。 规则（要不要执照、受不受行业白名单限制、结算账户形态）在 `sys_merchant_subject` 表里，随通道调整；**这里只管取值域**。 端上取 `GET /common/master-data`，不要在页面里写死。 <p><b>不叫 `SubjectType`</b>：那个名字在平台端已经是**风控主体** （DEVICE/MERCHANT/USER）。两个不同的概念同名，读代码的人迟早会把 一个当成另一个 —— 类型对齐守卫正是为此存在的。

枚举取值：

- `MICRO`
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

### OrderReceiver

收件人。下单时固化在子订单上，**不是用户当前的地址簿条目**。 三端共用：C 端订单详情、B 端配送/发货、平台端查单。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 否 | 收货人姓名。取不到时为空 —— 空就是空，不要回落成「顾客」 |
| `phone` | `string` | 否 | 脱敏程度由后端定，见 `Order.receiver` 的说明 |
| `address` | `string` | 否 | 省市区 + 详细，拼好的一行 |

### OrderStatus

订单状态。**这是后端真实下发的取值**，不是端上想象的流程。 ⚠️ 曾经这里还有一个 `PREPARING`（备货中）—— 那是 mock 里多出来的一步， 后端从付款直接到 `PAID`（待发货），没有独立的备货态。 端上按一个后端永远不会给的值去筛，筛出来的就是空列表，而且不报错。 ⚠️ 也曾有一个 `REFUNDING` —— 那是**售后单**的状态（ {@link  AfterSaleStatus } ）， 不是订单的。订单只会到 `REFUNDED`。这个混淆的代价是两端的「售后」页签： 它们按 `order.status === "REFUNDING"` 筛，而后端从不下发， **b 端「售后中」页签与工作台售后待办数因此恒为空 / 恒为 0**。 一个订单可以「已完成」的同时挂着一张处理中的售后单 —— 两者并存， 做成互斥的状态就必须二选一，而那是表达不了的。售后要从 `/mp/after-sale` 与 `/biz/after-sale` 单独查。

枚举取值：

- `WAIT_PAY`
- `PAID`
- `ARRIVED`
- `SHIPPED`
- `COMPLETED`
- `CANCELLED`
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
| `settleAccountType` | [`SettleAccountType`](#settleaccounttype) | 否 | 结算账户形态：小微打个人（PERSONAL_OPENID），其余打对公（MERCHANT_ID） |
| `settleAccountMasked` | `string` | 否 | 结算账号掩码。**明文永不回显**，包括给商家自己（ADR-002 §5） |
| `rejectReason` | `string` | 否 | 驳回原因。驳回时必有 —— 没有原因商家只能反复重提 |
| `missing` | `string`\[\] | 是 | 还缺哪些资料（settleAccount / licenses / settleAccountType）。空 = 资料齐了在等通道 |
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

### PickupScope

自提点作用域：常驻 / 团粒度（一团一销）

枚举取值：

- `PERMANENT`
- `GROUP_INSTANCE`

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

### Record_string_string

类型：`object`

### Region

行政区划的一级（`/biz/regions`）。国家统计局统计用区划代码，省 2 / 市 4 / 区 6 / 街道 9 位

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | 统计用区划代码：省 2 位 / 市 4 位 / 区县 6 位 / 街道 9 位。**前缀即层级**，下级码以上级码开头 |
| `parentCode` | `string` | 否 | 上级区划码。省级为空 —— 逐级选择器据此判断自己在不在顶层 |
| `level` | `string` | 是 | PROVINCE / CITY / DISTRICT / STREET |
| `name` | `string` | 是 | 本级名称，**不含上级**（「西湖区」不是「杭州市 / 西湖区」）。要整条路径的地方自己拼 |
| `enabled` | `boolean` | 是 | 是否启用。B 端只会拿到启用的 —— 停用的区划是运营的维护对象，不该出现在商家的选择器里 |
| `hasChild` | `boolean` | 是 | 下面还有没有下级。端上据此决定「还要不要再往下选一层」，而不是点进去才发现是空的 |

### ReplyReviewReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `reply` | `string` | 是 | 回复内容。公开展示在评价下方，一条评价只能回一次 |

### ReportShortageReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 出问题的 SKU |
| `kind` | [`ArrivalIssueKind`](#arrivalissuekind) | 是 | 问题类型：少件 / 破损。两者的售后责任判定不同 |
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
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态，决定履约与合规（生鲜要截单、服务不发货、iOS 可售规则） |
| `categoryNo` | `string` | 否 | 类目单号。选填，决定归类与经营准入 —— 与 `type` 是两个正交维度 |
| `cover` | `string` | 否 | 封面图 URL（来自 mUploadImage）。漏传的话 C 端列表里是一块留白，且不报错 |
| `images` | `string`\[\] | 否 | 详情轮播图 |
| `specGroups` | [`SpecGroupDraft`](#specgroupdraft)\[\] | 是 | 空数组 = 单规格。非空则 skus 必须是各组选项的笛卡尔积 |
| `fulfillments` | `string`\[\] | 否 | 支持的履约方式；不传 = 不改（新建默认四种全支持） |
| `skus` | [`SkuDraft`](#skudraft)\[\] | 是 | SKU 列表。单规格商品也有且仅有一条 |

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
| `level` | [`AreaLevel`](#arealevel) | 是 | 粒度：社区 / 街道 / 区县 / 城市。**可跨粒度组合** —— 三个小区 + 一个区是四条 |
| `refCode` | `string` | 是 | level=COMMUNITY 时是社区号，否则是区划码 |
| `name` | `string` | 是 | 展示名。区级以上是「浙江省 / 杭州市 / 西湖区」整条路径 —— 光一个「西湖区」全国有好几个，商家分不出删哪条 |
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

- `PERSONAL_OPENID`
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

结算流水状态。**与后端 `StlBill` 逐字一致**。 > 2026-08-11 收敛：这里此前是 `PENDING/PARTIAL/DONE/EXPIRED` —— 一套后端从来没有过的词， > 描述的是「周期账单」而不是「按子单的分账流水」。内联时对所有工具不可见， > 具名化之后才暴露出来（见 enum-registry 里这条的 note）。 - `PENDING` 待分账 · `SPLITTING` 分账中 · `SPLIT` 已分账 - `RETRYING` 失败重试中 · `MANUAL` 转人工（重试用尽，**不会自动再动钱**） - `REVERSED` 已回退（退款前必须先回退分账）

枚举取值：

- `PENDING`
- `SPLITTING`
- `SPLIT`
- `RETRYING`
- `MANUAL`
- `REVERSED`

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
| `scope` | [`SpecTemplateScope`](#spectemplatescope) | 是 | 模板归属：平台统一维护 or 商家自存。商家只能改自己的 |
| `categoryType` | [`CategoryType`](#categorytype) | 否 | 平台模板按类目推荐；商家模板不限类目 |
| `name` | `string` | 是 | 规格维度名，如「重量」「香型」 |
| `options` | [`SpecOption`](#specoption)\[\] | 是 | 该维度的可选项 |
| `merchantNo` | `string` | 否 | scope=MERCHANT 时归属的商家 |

### SpecTemplateScope

规格模板归属：平台统一维护 / 商家自存

枚举取值：

- `PLATFORM`
- `MERCHANT`

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

### Store

门店（商家侧管理用）。 <p><b>门店与主体是关联不是归属</b>：换执照店照开。所以 `storeNo` 一旦生成就不再变 —— 评价、订单、顾客的「我常逛的店」都挂在它上面。

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

### StoreEditReq

新建/改名门店。门面其余部分（公告/营业时间/主推）走 SaveStoreReqBody

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 门店名 |
| `address` | `string` | 否 | 门店地址 |

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
| `fulfillmentReach` | [`FulfillmentReach`](#fulfillmentreach) | 否 | 履约能力（ADR-013 阶段二）。**只说「怎么送到你手上」**，送得到哪儿看  {@link  serviceAreas } 。 与上面两个 `@deprecated` 字段的关系：新旧两套并存期间，端上**只传一套** —— 传了 `serviceAreas` 就走新模型，后端不再看 `serviceScope`。 |
| `serviceAreas` | [`ServiceArea`](#servicearea)\[\] | 否 | 地理覆盖项，可跨粒度组合（三个小区 + 一个区）。 **空的含义由 `fulfillmentReach` 决定**，这是这个字段最容易踩的地方： PICKUP 空 = 谁也看不到（没配自提点就没法履约）； ONSITE / SHIPPING 空 = 不限。同一个空数组两种意思，所以别拿它判「有没有设置过」。 |

### StoreQrcode

店铺码（C-ST-08 扫码进店的商家侧）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `url` | `string` | 是 | 扫码后进入的落地页地址，带 merchant_no 归因参数 |
| `printUrl` | `string` | 是 | 可打印版（贴纸尺寸），真实环境由后端生成小程序码 |

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
