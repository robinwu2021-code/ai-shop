# C 端 API 详情 · c-app（消费者小程序 / H5）

> 由 `npm run gen:api-detail` 从 OpenAPI 生成，**请勿手改**。
> 契约源：[`openapi.yaml`](openapi.yaml)　总表：[API 清单](API清单.md)

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

#### GET `/mp/after-sale`

我的售后单　🔒

**入参**：无

**出参**（`data`）

类型：[`AfterSale`](#aftersale)\[\]


#### POST `/mp/after-sale/{afterSaleNo}/escalate`

上升平台裁决　🔒

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


#### POST `/mp/after-sale/{afterSaleNo}/ship`

填退货运单号　🔒

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


#### GET `/mp/after-sale/reasons`

售后原因清单　🔒

**入参**：无

**出参**（`data`）

类型：[`AfterSaleReason`](#aftersalereason)\[\]


### card

#### GET `/mp/card/mine`

我的卡包　🔒

**入参**：无

**出参**（`data`）

类型：[`UserCard`](#usercard)\[\]


### cart

#### GET `/mp/cart`

购物车　🔒

**入参**：无

**出参**（`data`）

类型：[`CartItem`](#cartitem)\[\]


#### POST `/mp/cart/add`

加入购物车　🔒

**入参**

请求体：[`CartAddReq`](#cartaddreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `skuNo` | `string` | 是 | SKU 单号。购物车按 SKU 去重，同商品不同规格是两行 |
| `qty` | `number` | 是 | 加购件数，正整数 |

**出参**（`data`）

类型：[`CartItem`](#cartitem)\[\]


#### POST `/mp/cart/remove`

移除商品　🔒

**入参**

请求体：[`CartRemoveReq`](#cartremovereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNos` | `string`\[\] | 是 | 要删除的 SKU 列表。批量是因为购物车支持多选删除 |

**出参**（`data`）

类型：[`CartItem`](#cartitem)\[\]


#### POST `/mp/cart/update`

修改数量　🔒

**入参**

请求体：[`CartUpdateReq`](#cartupdatereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 要改的 SKU |
| `qty` | `number` | 是 | 改后的件数。传 0 等同于删除该行 |

**出参**（`data`）

类型：[`CartItem`](#cartitem)\[\]


### community

#### GET `/mp/community`

全部已开通社区（附近为空时的出路）　🔒

**入参**：无

**出参**（`data`）

类型：[`Community`](#community)\[\]


#### GET `/mp/community/nearby`

附近社区与自提点　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `lat` | query | `number` | 否 | 纬度 |
| `lng` | query | `number` | 否 | 经度 |

**出参**（`data`）

类型：[`Community`](#community)\[\]


#### GET `/mp/community/regions`

有已开通社区的区域清单　🔒

**入参**：无

**出参**（`data`）

类型：[`RegionOption`](#regionoption)\[\]


### coupon

#### GET `/mp/coupon`

优惠券列表　🔒

**入参**：无

**出参**（`data`）

类型：[`Coupon`](#coupon)\[\]


#### POST `/mp/coupon/{couponNo}/receive`

领取优惠券　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `couponNo` | path | `string` | 是 | 券单号 |

**出参**（`data`）

类型：[`Coupon`](#coupon)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `couponNo` | `string` | 是 | 券单号 |
| `title` | `string` | 是 | 券名，如「满 50 减 5」 |
| `type` | [`CouponType`](#coupontype) | 是 | — |
| `faceMinor` | `number` | 是 | 满减面额（最小货币单位）。`DISCOUNT` 券为 0 |
| `discountRate` | `number` | 是 | 折扣**万分比**，8500 = 八五折。`FULL_CUT` 券为 0 |
| `thresholdMinor` | `number` | 是 | 使用门槛（最小货币单位）。0 表示无门槛 |
| `maxDiscountMinor` | `number` | 是 | 折扣券封顶（最小货币单位）。仅 `DISCOUNT` 有意义 |
| `funder` | [`CouponFunder`](#couponfunder) | 是 | — |
| `merchantNo` | `string` | 是 | 商家券的归属商家；平台券为空 |
| `startAt` | `number` | 是 | 可领取/可用的时间窗 |
| `endAt` | `number` | 是 | — |
| `remain` | `number` | 是 | 剩余可领数量 |
| `received` | `boolean` | 是 | 当前用户是否已领取。列表页据此显示「领取」还是「去使用」 |
| `status` | [`CouponStatus`](#couponstatus) | 是 | — |
| `scopeDesc` | `string` | 是 | 适用范围文案，如「仅限张记粮油店」。展示用，实际校验在服务端 |


### goods

#### GET `/mp/goods`

商品列表　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `page` | query | `number` | 否 | 页码，从 1 起 |
| `size` | query | `number` | 否 | 每页条数 |
| `merchantNo` | query | `string` | 否 | 商家单号 |
| `type` | query | [`CategoryType`](#categorytype) | 否 | 类型筛选，取值见对应枚举 |
| `categoryNo` | query | `string` | 否 | 类目单号 |
| `keyword` | query | `string` | 否 | 搜索关键词 |
| `communityNo` | query | `string` | 否 | 社区单号 |

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Goods`](#goods)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/mp/goods/{goodsNo}`

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

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |


#### GET `/mp/goods/promoted`

推荐商品（运营位）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `communityNo` | query | `string` | 否 | 社区单号 |
| `size` | query | `number` | 否 | 每页条数 |

**出参**（`data`）

类型：[`Goods`](#goods)\[\]


### group-buy

#### GET `/mp/group-buy`

商家团列表　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `pickupNo` | query | `string` | 否 | 自提点单号 |

**出参**（`data`）

类型：[`GroupBuy`](#groupbuy)\[\]


#### POST `/mp/group-buy`

发起商家团　🔒

**入参**

请求体：[`CreateGroupBuyReq`](#creategroupbuyreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 要开团的商品，必须是已上架商品 |
| `pickupNo` | `string` | 是 | 成团的自提点 |
| `neighbor` | `object`（见下） | 否 | 邻里自提：送到我家（ADR-005）。只能是发起人自己家，不能指定别人家 |

`neighbor` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `toMyHome` | `boolean` | 是 | — |
| `address` | `string` | 是 | — |
| `timeSlot` | `string` | 是 | — |

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


#### GET `/mp/group-buy/{groupNo}`

商家团详情　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `groupNo` | path | `string` | 是 | 团单号 |

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


#### POST `/mp/group-buy/{groupNo}/join`

参团　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `groupNo` | path | `string` | 是 | 团单号 |

请求体：[`JoinGroupBuyReq`](#joingroupbuyreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `qty` | `number` | 是 | 参团件数，正整数 |

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


#### GET `/mp/group-buy/{groupNo}/orders`

本团待取订单　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `groupNo` | path | `string` | 是 | 团单号 |

**出参**（`data`）

类型：[`Order`](#order)\[\]


#### POST `/mp/group-buy/{groupNo}/receive`

批次签收　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `groupNo` | path | `string` | 是 | 团单号 |

**出参**（`data`）

类型：[`Order`](#order)\[\]


#### POST `/mp/group-buy/{groupNo}/verify`

发起人核销　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `groupNo` | path | `string` | 是 | 团单号 |

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


#### GET `/mp/group-buy/hosted`

我发起的团　🔒

**入参**：无

**出参**（`data`）

类型：[`GroupBuy`](#groupbuy)\[\]


### group-request

#### GET `/mp/group-request`

求团列表　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `pickupNo` | query | `string` | 否 | 自提点单号 |

**出参**（`data`）

类型：[`GroupRequest`](#grouprequest)\[\]


#### POST `/mp/group-request`

发起求团　🔒

**入参**

请求体：[`CreateRequestReq`](#createrequestreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | 需求所属的自提点/小区 |
| `title` | `string` | 是 | 需求标题，如「想团儿童床垫」。发起时**商品还不存在** |
| `desc` | `string` | 是 | 需求详述：尺寸、材质、用途，供商家判断能不能接 |
| `expectQty` | `number` | 是 | 期望数量 |
| `budgetMinor` | `number` | 否 | 心理价位（最小货币单位），可不填。填了商家报价更有的放矢 |

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


#### GET `/mp/group-request/{requestNo}`

求团详情　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `requestNo` | path | `string` | 是 | 求团需求单号 |

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


#### POST `/mp/group-request/{requestNo}/choose`

发起人选定报价（锁价）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `requestNo` | path | `string` | 是 | 求团需求单号 |

请求体：[`ChooseQuoteReq`](#choosequotereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `quoteNo` | `string` | 是 | 选定的报价。**选定即锁价**，此后下单一律用快照价（ADR-003） |

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


#### POST `/mp/group-request/{requestNo}/confirm`

二次确认下单　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `requestNo` | path | `string` | 是 | 求团需求单号 |

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


#### POST `/mp/group-request/{requestNo}/interest`

+1 / 取消（意向，非订单）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `requestNo` | path | `string` | 是 | 求团需求单号 |

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


### invoice

#### POST `/mp/invoice/apply`

申请开票　🔒

**入参**：无

**出参**（`data`）

类型：[`InvoiceRequest`](#invoicerequest)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestNo` | `string` | 是 | — |
| `orderNo` | `string` | 是 | 按**主单**申请，不按子单 —— 消费者眼里那是一次购买，票也该是一张 |
| `titleType` | [`InvoiceTitleType`](#invoicetitletype) | 是 | — |
| `title` | `string` | 是 | — |
| `taxNo` | `string` | 否 | 单位抬头必填 |
| `email` | `string` | 是 | 电子票只能发到这里，填错就是开了也收不到 |
| `amountMinor` | `number` | 是 | 开票金额快照。**不实时读订单** —— 退款会改订单金额，已开的票不会跟着变 |
| `status` | [`InvoiceRequestStatus`](#invoicerequeststatus) | 是 | — |
| `invoiceNo` | `string` | 否 | — |
| `issuedAt` | `number` | 否 | — |
| `rejectReason` | `string` | 否 | 驳回原因。不写原因的驳回等于让消费者再猜一遍 |
| `createdAt` | `number` | 否 | — |


#### GET `/mp/invoice/mine`

我的开票申请　🔒

**入参**：无

**出参**（`data`）

类型：[`InvoiceRequest`](#invoicerequest)\[\]


#### GET `/mp/invoice/order/{orderNo}`

某单的开票状态　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

**出参**（`data`）

类型：[`InvoiceRequest`](#invoicerequest)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestNo` | `string` | 是 | — |
| `orderNo` | `string` | 是 | 按**主单**申请，不按子单 —— 消费者眼里那是一次购买，票也该是一张 |
| `titleType` | [`InvoiceTitleType`](#invoicetitletype) | 是 | — |
| `title` | `string` | 是 | — |
| `taxNo` | `string` | 否 | 单位抬头必填 |
| `email` | `string` | 是 | 电子票只能发到这里，填错就是开了也收不到 |
| `amountMinor` | `number` | 是 | 开票金额快照。**不实时读订单** —— 退款会改订单金额，已开的票不会跟着变 |
| `status` | [`InvoiceRequestStatus`](#invoicerequeststatus) | 是 | — |
| `invoiceNo` | `string` | 否 | — |
| `issuedAt` | `number` | 否 | — |
| `rejectReason` | `string` | 否 | 驳回原因。不写原因的驳回等于让消费者再猜一遍 |
| `createdAt` | `number` | 否 | — |


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

#### GET `/mp/merchant`

商家列表/搜索　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `keyword` | query | `string` | 否 | 搜索关键词 |
| `communityNo` | query | `string` | 否 | 社区单号 |

**出参**（`data`）

类型：[`Merchant`](#merchant)\[\]


#### GET `/mp/merchant/{merchantNo}`

商家详情　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`Merchant`](#merchant)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家单号。贯穿商品/订单/评价/结算，是多商家模型的主线（ADR-001） |
| `selfOperated` | `boolean` | 否 | 这单是不是**平台自营**（销售主体是平台）。 **必须显示出来 —— 电商法 §37 要求平台以显著方式区分标记自营业务， 不得误导消费者。这是法定义务，不是产品选择。** 而它同时是资金模式合法性的一部分：归集路径下平台是销售主体， 页面上却让消费者以为在跟商家交易，四流就不一致了（ADR-017 §3.4）。 ⚠️ 自营时**商家信息照常展示**（供货商、产地、门店、评分）—— 要禁的是把销售方指给商家的**表述**，不是商家信息本身。 见 `packages/shared/tests/seller-statement.test.ts` 的禁用词表。 |
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo URL |
| `rating` | `number` | 是 | 综合评分，0–5，保留一位小数。**0 分要配合 `ratingCount` 一起看** |
| `ratingCount` | `number` | 是 | 参与评分的评价条数 |
| `verified` | `boolean` | 是 | 是否通过资质认证 |
| `breachCount` | `number` | 是 | 选定报价后不履约的次数。>0 会在报价卡上公示 —— 事后信用替代事前审核 |
| `type` | [`MerchantType`](#merchanttype) | 是 | 商家类型：平台自营 / 企业 / 个体 |
| `desc` | `string` | 是 | 店铺简介 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围 —— 邻里购物的核心约束：**商家是有服务半径的**。 隔壁区的生鲜店对我没有意义，它送不到我的自提点。见 SERVICE_SCOPE。 |
| `serviceCommunityNos` | `string`\[\] | 是 | 覆盖哪些社区。**仅 scope=COMMUNITY 时有意义**，其余情况忽略 |
| `serviceCityCode` | `string` | 否 | 覆盖哪个城市。**仅 scope=CITY 时有意义** |
| `distance` | `number` | 否 | 距当前社区的距离（米）。由服务端按用户当前社区算好下发，端上不自己算 |
| `salesCount` | `number` | 是 | 累计订单量（评分权重之一） |
| `goodsCount` | `number` | 是 | 在售商品数 |
| `address` | `string` | 否 | 店铺地址。纯线上商家可能没有 |
| `openHours` | `string` | 否 | 营业时间文案 |
| `joinedAt` | `number` | 是 | 入驻时间 |
| `tags` | `string`\[\] | 是 | 店铺标签，如「生鲜」「次日达」。展示用，不参与筛选 |
| `scores` | `object`（见下） | 是 | 分维度评分：商品/服务/时效 |

`scores` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goods` | `number` | 是 | — |
| `service` | `number` | 是 | — |
| `speed` | `number` | 是 | — |


#### POST `/mp/merchant/apply`

商家入驻申请　🔒

**入参**

请求体：[`MerchantApplyReq`](#merchantapplyreq)

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

**出参**（`data`）

类型：[`MerchantApplyStatus`](#merchantapplystatus)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `applyNo` | `string` | 是 | 申请单号 |
| `name` | `string` | 是 | 申请时填的店铺名。**存快照** —— 后来改名不该让历史申请跟着变 |
| `subject` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型。决定分账主体形态与所需资质（ADR-002 §4） |
| `status` | [`MerchantApplyReviewStatus`](#merchantapplyreviewstatus) | 是 | 审核状态。迁移见本类型的注释，APPROVED 为终态 |
| `rejectReason` | `string` | 否 | 驳回理由。**驳回必须写** —— 不写就等于让人猜着改 |
| `merchantNo` | `string` | 否 | 通过后生成的商家单号。未通过时为空 —— 商家在通过之前根本不存在 |
| `createdAt` | `number` | 是 | 提交时间 |
| `auditedAt` | `number` | 否 | 审核完成时间。PENDING/REVIEWING 期间为空 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 联系手机号。这是申请人自己填的联系号码，**不是登录号**，不脱敏 |
| `category` | `string` | 是 | 主营类目 |
| `desc` | `string` | 是 | 店铺简介 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 否 | 期望经营范围（ADR-009） |
| `communityNos` | `string`\[\] | 否 | 期望覆盖的社区 |
| `licenses` | `string`\[\] | 否 | 已传的资质图（只有图片 URL，看不出是哪种证、什么时候过期） |
| `qualificationItems` | [`QualificationItem`](#qualificationitem)\[\] | 否 | 结构化资质（V79）：**哪张证、证件号、有效期**。 ⚠️ 这一段的标题写着「用于驳回后回填」，而此前只回填了  {@link  licenses }  ——只有图片。**证件类型、编号、有效期三项全丢**，商家重提时得逐格再填一遍， 而这正是本段注释想避免的那件事：「把补交变成重来」。 后端 `MerchantApplyVO` 一直在发它（审核台就靠它看类型与有效期）， 端上这里没声明。 |
| `industry` | `string` | 否 | 申请时选的行业。驳回回填要用它 —— 换个行业可能连主体类型都得跟着换 |
| `asPickupPoint` | `boolean` | 否 | 是否愿意承接自提点（ADR-005）。 **只是意愿，不代表点已建立** —— 建点要谈服务费口径，一期由运营在通过后另行处理。 所以商家勾了这一项、通过后却还没看到履约台，是正常的中间状态而不是故障。 |


#### GET `/mp/merchant/apply`

我的入驻申请状态　🔒

**入参**：无

**出参**（`data`）

类型：[`MerchantApplyStatus`](#merchantapplystatus)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `applyNo` | `string` | 是 | 申请单号 |
| `name` | `string` | 是 | 申请时填的店铺名。**存快照** —— 后来改名不该让历史申请跟着变 |
| `subject` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型。决定分账主体形态与所需资质（ADR-002 §4） |
| `status` | [`MerchantApplyReviewStatus`](#merchantapplyreviewstatus) | 是 | 审核状态。迁移见本类型的注释，APPROVED 为终态 |
| `rejectReason` | `string` | 否 | 驳回理由。**驳回必须写** —— 不写就等于让人猜着改 |
| `merchantNo` | `string` | 否 | 通过后生成的商家单号。未通过时为空 —— 商家在通过之前根本不存在 |
| `createdAt` | `number` | 是 | 提交时间 |
| `auditedAt` | `number` | 否 | 审核完成时间。PENDING/REVIEWING 期间为空 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 联系手机号。这是申请人自己填的联系号码，**不是登录号**，不脱敏 |
| `category` | `string` | 是 | 主营类目 |
| `desc` | `string` | 是 | 店铺简介 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 否 | 期望经营范围（ADR-009） |
| `communityNos` | `string`\[\] | 否 | 期望覆盖的社区 |
| `licenses` | `string`\[\] | 否 | 已传的资质图（只有图片 URL，看不出是哪种证、什么时候过期） |
| `qualificationItems` | [`QualificationItem`](#qualificationitem)\[\] | 否 | 结构化资质（V79）：**哪张证、证件号、有效期**。 ⚠️ 这一段的标题写着「用于驳回后回填」，而此前只回填了  {@link  licenses }  ——只有图片。**证件类型、编号、有效期三项全丢**，商家重提时得逐格再填一遍， 而这正是本段注释想避免的那件事：「把补交变成重来」。 后端 `MerchantApplyVO` 一直在发它（审核台就靠它看类型与有效期）， 端上这里没声明。 |
| `industry` | `string` | 否 | 申请时选的行业。驳回回填要用它 —— 换个行业可能连主体类型都得跟着换 |
| `asPickupPoint` | `boolean` | 否 | 是否愿意承接自提点（ADR-005）。 **只是意愿，不代表点已建立** —— 建点要谈服务费口径，一期由运营在通过后另行处理。 所以商家勾了这一项、通过后却还没看到履约台，是正常的中间状态而不是故障。 |


#### GET `/mp/merchant/promoted`

推荐门店（运营位）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `communityNo` | query | `string` | 否 | 社区单号 |
| `size` | query | `number` | 否 | 每页条数 |

**出参**（`data`）

类型：[`Merchant`](#merchant)\[\]


#### GET `/mp/merchant/visited`

我买过的商家　🔒

**入参**：无

**出参**（`data`）

类型：[`VisitedMerchant`](#visitedmerchant)\[\]


### message

#### GET `/mp/message`

消息列表　🔒

**入参**：无

**出参**（`data`）

类型：[`Message`](#message)\[\]


#### POST `/mp/message/{messageNo}/read`

标记已读　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `messageNo` | path | `string` | 是 | 站内消息单号 |

**出参**（`data`）

类型：[`Message`](#message)\[\]


#### POST `/mp/message/read-all`

全部已读　🔒

**入参**：无

**出参**（`data`）

类型：[`Message`](#message)\[\]


#### POST `/mp/message/subscribe`

订阅消息授权上报（同意与拒绝都报：后端记额度 + 防反复弹窗）　🔒

**入参**：无

**出参**（`data`）

类型：`any`


#### GET `/mp/message/unread-count`

未读数（角标用，只给一个数）　🔒

**入参**：无

**出参**（`data`）

类型：`number`


### my-coupons

#### GET `/mp/my-coupons`

商家发给我的券（含到店码）　🔒

**入参**：无

**出参**（`data`）

类型：[`MyStoreCoupon`](#mystorecoupon)\[\]


### my-memberships

#### GET `/mp/my-memberships`

我是哪几家店的会员　🔒

**入参**：无

**出参**（`data`）

类型：[`MyMembership`](#mymembership)\[\]


### order

#### POST `/mp/order`

下单（幂等）　🔒

**入参**

请求体：[`CreateOrderReqBody`](#createorderreqbody)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `items` | `object`（见下）\[\] | 是 | 下单行。跨商家时服务端**拆成多笔子订单**，共享一个 payGroupNo（E3） |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式。决定下面 pickupNo / addressId / appointmentAt 哪个必填 |
| `pickupNo` | `string` | 否 | PICKUP 必填：自提点单号 |
| `addressId` | `string` | 否 | EXPRESS / 自送必填：收货地址。下单时地址整体**快照**进订单 |
| `couponNo` | `string` | 否 | 使用的优惠券 |
| `usePoints` | `number` | 否 | 使用的积分数。服务端按抵扣上限与账户余额截断，端上传的只是意愿 |
| `remark` | `string` | 否 | 买家留言 |
| `groupNo` | `string` | 否 | 参团下单时传团单号。**后端 CreateOrderReq 目前不认这个字段**，接上去会静默变成普通单 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `payMode` | `string` | 否 | 支付方式（`PAY_MODE`）。**不传按 ONLINE** —— 存量端上没有这个字段， 不能因为补了它就让老版本下不了单。 能不能选 OFFLINE 由 `orderCapability` 的 `usablePayModes` 说了算， 而后端在 create 里会**再判一次**：端上不该是唯一的闸。 |
| `appointmentSlotNo` | `string` | 否 | APPOINTMENT：选定的**预约时段**。这家店开了时段就必填 —— 没开则忽略，走 `appointmentAt` 那条旧路（兼容期）。 |
| `idempotencyKey` | `string` | 是 | 幂等 key，防重复提交 |

`items[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `skuNo` | `string` | 是 | SKU 单号 |
| `qty` | `number` | 是 | 件数 |

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


#### GET `/mp/order`

订单列表　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `page` | query | `number` | 否 | 页码，从 1 起 |
| `size` | query | `number` | 否 | 每页条数 |
| `status` | query | `string` | 否 | 状态筛选，取值见对应枚举 |

**出参**（`data`）

类型：`object`（见下）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `records` | [`Order`](#order)\[\] | 是 | — |
| `total` | `integer` | 是 | — |
| `page` | `integer` | 是 | — |
| `size` | `integer` | 是 | — |


#### GET `/mp/order/{orderNo}`

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


#### POST `/mp/order/{orderNo}/after-sale`

申请售后　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

请求体：[`AfterSaleReq`](#aftersalereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | [`AfterSaleType`](#aftersaletype) | 否 | 仅退款 / 退货退款 —— 两者流程根本不同，不能合成一个 |
| `reason` | `string` | 是 | 已拼好的原因文案（前端把 reason 枚举与补充说明合并后提交） |
| `images` | `string`\[\] | 是 | 举证图。破损/少件类售后没有图基本判不了 |
| `reasonCode` | [`AfterSaleReason`](#aftersalereason) | 否 | 结构化原因，便于服务端统计与风控 |

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


#### POST `/mp/order/{orderNo}/cancel`

取消订单　🔒

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


#### POST `/mp/order/{orderNo}/pay`

支付　🔒

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


#### POST `/mp/order/{orderNo}/reorder`

一键再来一单　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `orderNo` | path | `string` | 是 | 订单单号（按商家拆单后的子订单） |

**出参**（`data`）

类型：[`ReorderResult`](#reorderresult)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `added` | `number` | 是 | 成功加入购物车的件数 |
| `dropped` | `string`\[\] | 是 | 已失效、没加进购物车的商品名 |
| `priceUp` | `string`\[\] | 是 | 涨价了但仍加入的商品名 |


#### POST `/mp/order/capability`

结算页能力提示（开票/支付方式/额度）　🔒

**入参**：无

**出参**（`data`）

类型：[`CheckoutCapability`](#checkoutcapability)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `usablePayMethods` | `string`\[\] \| `null` | 是 | 整单可用的支付方式 = <b>各商家支持集合的交集</b>。 交集而非并集：一笔支付覆盖整单，有一家不支持就用不了。 <b>空数组 = 这一车货没有任何方式能付</b>，端上要拦在结算页 —— 让他点下去只会得到一个说不清原因的「支付失败」。 <b>null = 未配置</b>（一个商家都还没进件完）——端上<b>不要拦</b>。 两者混成空数组的话，一个完全正常的订单会被拦死。 |
| `anyNotInvoiceCapable` | `boolean` | 是 | 车里有商家开不了票。**必须在付款前告诉用户**：买完才发现，平台补救不了 |
| `merchants` | [`MerchantCapability`](#merchantcapability)\[\] | 是 | 逐商家的能力，端上据此在对应的商家分组上打标 |
| `usablePayModes` | `string`\[\] | 是 | 整单可用的**支付方式**（`PAY_MODE`：ONLINE / OFFLINE）。 ⚠️ **与 `usablePayMethods` 是两根轴，别混**：那个是**通道** （WECHAT / ALIPAY / H5…），这个是**线上付还是当面付**。 一笔订单要同时确定两者。 同样取交集（一笔支付覆盖整单）。**ONLINE 永远在里面**， 所以不会是空集，也就不需要 `null` 那一档 —— 与 `usablePayMethods` 的取舍不同，因为那边真的可能「没配过」。 |


#### POST `/mp/order/preview`

订单预览（金额以后端为准）　🔒

**入参**：无

**出参**（`data`）

类型：[`OrderPreview`](#orderpreview)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `amount` | [`OrderAmount`](#orderamount) | 是 | 试算出来的金额。**页面显示的应付必须等于这里的 payableMinor** —— 端上不要自己再算一遍：优惠叠加顺序（先活动后券）在后端， 两处各算一次必然算出两个数，而用户看到的是「确认页 46.40、付款 51.40」。 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 试算出来的订单行，含赠品行（价格 0）。数量与下单后落库的一致 |


### points

#### GET `/mp/points/account`

积分账户　🔒

**入参**：无

**出参**（`data`）

类型：[`PointAccount`](#pointaccount)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `balance` | `number` | 是 | 当前可用余额。**只含能花的分**，待生效的在 pendingBalance |
| `pendingBalance` | `number` | 是 | 待生效积分：已发放但未过售后期，**不计入 balance**。 两个数必须分开展示（「可用 400 / 待生效 100」）。合成一个的话， 用户看到「我有 500 分」却只能用 400，没有任何办法解释这个差额。 |
| `pendingActivateAt` | `number` | 否 | 最近一批待生效积分的可用时间。`pendingBalance=0` 时为空 |
| `totalEarned` | `number` | 是 | 累计获得（含已用、已过期），只增不减 |
| `totalUsed` | `number` | 是 | 累计已抵扣 |
| `expiringSoon` | `number` | 是 | 30 天内将过期的积分 |
| `expiringAt` | `number` | 否 | 最近一批积分的过期时间。`expiringSoon=0` 时为空 |


#### GET `/mp/points/deductible`

结算页试算：本单最多可抵多少　🔒

**入参**：无

**出参**（`data`）

类型：[`PointsDeductible`](#pointsdeductible)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `maxPoints` | `number` | 是 | 本单最多可抵扣的积分数。已扣掉四级开关与上限，端上直接用 |
| `maxAmountMinor` | `number` | 是 | 对应金额（分） |
| `balance` | `number` | 是 | 用户当前可用余额，用于展示「你有 X 分」 |
| `disabledReason` | `string` | 否 | 不可用时的原因，直接展示。可用时为空 |


#### GET `/mp/points/records`

积分流水　🔒

**入参**：无

**出参**（`data`）

类型：[`PointRecord`](#pointrecord)\[\]


### push-token

#### POST `/mp/push-token`

绑定 App 推送设备（登录后）　🔒

**入参**：无

**出参**（`data`）

类型：`any`


#### POST `/mp/push-token/unregister`

解绑推送设备（登出前，共用设备换人必须解）　🔒

**入参**：无

**出参**（`data`）

类型：`any`


### regions

#### GET `/mp/regions`

行政区划（省市区三级，地址簿用）　🔒

**入参**：无

**出参**（`data`）

类型：[`RegionNode`](#regionnode)\[\]


### review

#### GET `/mp/review`

评价列表　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `goodsNo` | query | `string` | 否 | 商品单号 |
| `merchantNo` | query | `string` | 否 | 商家单号 |

**出参**（`data`）

类型：[`Review`](#review)\[\]


#### POST `/mp/review`

发表评价　🔒

**入参**

请求体：[`CreateReviewReq`](#createreviewreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 被评价的订单。**必须是已完成订单**，且一单一评 |
| `goodsNo` | `string` | 是 | 被评价的商品。一单多商品时逐个评 |
| `rating` | `number` | 是 | 总分，1–5 整数 |
| `content` | `string` | 是 | 评价正文 |
| `images` | `string`\[\] | 是 | 评价图 URL 列表，可为空数组 |
| `scores` | [`ReviewScores`](#reviewscores) | 否 | 三维分（商品 / 履约 / 服务）。**可选** —— 老客户端只给总分。 评价页一直在发这个字段，但类型里漏了它，于是它是**悄悄漏出去的**： `satisfies` 检查不到、OpenAPI 里没有、后端也就无从知道要收。 是 wire-alignment 守卫在后端实现时把它抓出来的。 |

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


#### POST `/mp/review/{reviewNo}/like`

点赞/取消　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `reviewNo` | path | `string` | 是 | 评价单号 |

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


### store

#### GET `/mp/store/{merchantNo}`

门店主页　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`StoreHome`](#storehome)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 平台建档的商家主数据（名称/资质/评分），店主改不了 |
| `store` | [`StoreFront`](#storefront) | 是 | 店主自己维护的门面内容 |
| `goods` | [`Goods`](#goods)\[\] | 是 | 在售商品。首屏展示，分页靠单独的商品列表接口 |
| `categories` | [`StoreShelf`](#storeshelf)\[\] | 是 | 本店货架：**店主自己排的顺序、自己改的名字**（「本地时鲜」而不是「蔬菜」）。 只含真的有在售商品的类目 —— 摆着却一件货都没有的类目，点进去空手而归。 少于两条时端上不画这一行：一个恒真的筛选开关只是占地方。 |
| `favorited` | `boolean` | 是 | 我是否收藏了这家店 |
| `closed` | `boolean` | 否 | 已停业（门店非 ACTIVE：商家自助停用或平台强制下线）。 **是标志而不是 404**：扫码进来的老客要知道「店关了」，不是「链接坏了」。 端上据此盖「已停业」并禁掉加购。 ⚠️ 后端 `StoreHomeVO` 一直在发这个字段，这里此前没声明 —— 于是**扫码进一家已停业的店，看起来与正常营业毫无区别**， 加购、下单一路走到底，最后在库存或下单闸门上撞一个说不清的错误。 |


#### POST `/mp/store/{merchantNo}/favorite`

收藏本店　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：`object`


#### GET `/mp/store/{merchantNo}/frequent`

常买清单　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `merchantNo` | path | `string` | 是 | 商家单号 |

**出参**（`data`）

类型：[`FrequentItem`](#frequentitem)\[\]


#### GET `/mp/store/mine`

我的常去店　🔒

**入参**：无

**出参**（`data`）

类型：[`Merchant`](#merchant)\[\]


### user

#### GET `/mp/user/address`

地址列表　🔒

**入参**：无

**出参**（`data`）

类型：[`Address`](#address)\[\]


#### POST `/mp/user/address`

新增/编辑地址　🔒

**入参**

请求体：[`SaveAddressReq`](#saveaddressreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `addressId` | `string` | 否 | 有值 = 编辑，无值 = 新增 |
| `name` | `string` | 是 | 收货人姓名 |
| `phone` | `string` | 是 | 收货人手机号 |
| `region` | `string` | 是 | 省市区，拼好给人看的一串 |
| `province` | `string,null` | 否 | 省 / 市 / 区县，分开的三个。后端 `SaveAddressReq` 一直收这三个字段， 端上一直没发 —— 于是 `usr_address` 那三列永远是 null（见 `Address` 的注释） |
| `city` | `string,null` | 否 | — |
| `district` | `string,null` | 否 | — |
| `detail` | `string` | 是 | 详细地址（街道门牌） |
| `isDefault` | `boolean` | 是 | 设为默认。置 true 会把原默认地址改为 false |
| `tag` | `string` | 否 | 标签：家 / 公司 / 其他 |
| `latE6` | `number,null` | 否 | 地图选点给的坐标（gcj02，E6）；不传 = 不改 |
| `lngE6` | `number,null` | 否 | — |

**出参**（`data`）

类型：[`Address`](#address)\[\]


#### POST `/mp/user/address/{addressId}/archive`

删除地址（软删除）　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `addressId` | path | `string` | 是 | 地址簿记录 ID（非业务单号，不进订单快照） |

**出参**（`data`）

类型：[`Address`](#address)\[\]


#### POST `/mp/user/address/{addressId}/default`

设为默认地址　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `addressId` | path | `string` | 是 | 地址簿记录 ID（非业务单号，不进订单快照） |

**出参**（`data`）

类型：[`Address`](#address)\[\]


#### POST `/mp/user/community`

绑定社区自提点　🔒

**入参**

请求体：[`BindCommunityReq`](#bindcommunityreq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communityNo` | `string` | 是 | 要绑定的社区。**商品可见范围依赖它**，绑错了首页就是别的小区的货 |
| `pickupNo` | `string` | 是 | 默认自提点，须属于该社区 |

**出参**（`data`）

类型：[`User`](#user)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `cUserNo` | `string` | 是 | C 端用户单号。前缀 `cUser` 是有意的：B 端商家、平台 STAFF 是**另外两个账号池**，单号不通用 |
| `nickname` | `string` | 是 | 昵称。微信授权取来的，用户可改 |
| `avatar` | `string` | 是 | 头像 URL |
| `phone` | `string` | 是 | 手机号。已脱敏（中间四位星号），完整号码不下发到端上 |
| `communityNo` | `string` | 否 | 当前绑定的社区。未绑定时为空 —— 首页的商品可见范围依赖它 |
| `pickupNo` | `string` | 否 | 默认自提点。下单时预选，用户可改 |
| `merchantNo` | `string` | 否 | 常去的店。与 communityNo 正交 —— 可以在 A 社区却常买 B 店（ADR-004 §5.1） |


#### POST `/mp/user/deregister`

注销账号（匿名化 + 解绑凭证，交易记录留存）　🔒

**入参**：无

**出参**（`data`）

类型：`any`


#### POST `/mp/user/login`

登录建户　🔒

**入参**

请求体：[`LoginReqBody`](#loginreqbody)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `grantType` | [`GrantType`](#granttype) | 是 | 登录方式，决定 principal / credential 各放什么 |
| `principal` | `string` | 是 | WX_MINI: wx.login code；PHONE_OTP: 手机号 |
| `credential` | `string` | 否 | PHONE_OTP: 验证码 |
| `inviterNo` | `string` | 否 | 裂变归因：邀请人 |
| `merchantNo` | `string` | 否 | 进店归因：从店铺码/店铺分享进入时带上（ADR-004 §5.4） |

**出参**（`data`）

类型：[`LoginResp`](#loginresp)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `token` | `string` | 是 | 访问令牌。后续请求放 `Authorization: Bearer <token>` |
| `user` | [`User`](#user) | 是 | 登录用户档案 |


#### POST `/mp/user/logout`

登出（作废服务端会话）　🔒

**入参**：无

**出参**（`data`）

类型：`any`


#### POST `/mp/user/otp/send`

发送验证码　🔒

**入参**：无

**出参**（`data`）

类型：`any`


#### POST `/mp/user/phone/bind`

绑定手机号（验证码）　🔒

**入参**

请求体：[`BindPhoneReq`](#bindphonereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `phone` | `string` | 是 | — |
| `code` | `string` | 是 | — |

**出参**（`data`）

类型：[`User`](#user)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `cUserNo` | `string` | 是 | C 端用户单号。前缀 `cUser` 是有意的：B 端商家、平台 STAFF 是**另外两个账号池**，单号不通用 |
| `nickname` | `string` | 是 | 昵称。微信授权取来的，用户可改 |
| `avatar` | `string` | 是 | 头像 URL |
| `phone` | `string` | 是 | 手机号。已脱敏（中间四位星号），完整号码不下发到端上 |
| `communityNo` | `string` | 否 | 当前绑定的社区。未绑定时为空 —— 首页的商品可见范围依赖它 |
| `pickupNo` | `string` | 否 | 默认自提点。下单时预选，用户可改 |
| `merchantNo` | `string` | 否 | 常去的店。与 communityNo 正交 —— 可以在 A 社区却常买 B 店（ADR-004 §5.1） |


#### GET `/mp/user/phone/capable`

一键授权当前可不可用（游客可读）　🔒

**入参**：无

**出参**（`data`）

类型：[`PhoneCapable`](#phonecapable)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `capable` | `boolean` | 是 | true = 显示「微信一键获取」；false = 显示手机号 + 验证码 |


#### POST `/mp/user/phone/wx`

微信一键授权绑定手机号　🔒

**入参**

请求体：[`WxPhoneReq`](#wxphonereq)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | — |

**出参**（`data`）

类型：[`User`](#user)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `cUserNo` | `string` | 是 | C 端用户单号。前缀 `cUser` 是有意的：B 端商家、平台 STAFF 是**另外两个账号池**，单号不通用 |
| `nickname` | `string` | 是 | 昵称。微信授权取来的，用户可改 |
| `avatar` | `string` | 是 | 头像 URL |
| `phone` | `string` | 是 | 手机号。已脱敏（中间四位星号），完整号码不下发到端上 |
| `communityNo` | `string` | 否 | 当前绑定的社区。未绑定时为空 —— 首页的商品可见范围依赖它 |
| `pickupNo` | `string` | 否 | 默认自提点。下单时预选，用户可改 |
| `merchantNo` | `string` | 否 | 常去的店。与 communityNo 正交 —— 可以在 A 社区却常买 B 店（ADR-004 §5.1） |


#### GET `/mp/user/profile`

我的资料　🔒

**入参**：无

**出参**（`data`）

类型：[`User`](#user)

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `cUserNo` | `string` | 是 | C 端用户单号。前缀 `cUser` 是有意的：B 端商家、平台 STAFF 是**另外两个账号池**，单号不通用 |
| `nickname` | `string` | 是 | 昵称。微信授权取来的，用户可改 |
| `avatar` | `string` | 是 | 头像 URL |
| `phone` | `string` | 是 | 手机号。已脱敏（中间四位星号），完整号码不下发到端上 |
| `communityNo` | `string` | 否 | 当前绑定的社区。未绑定时为空 —— 首页的商品可见范围依赖它 |
| `pickupNo` | `string` | 否 | 默认自提点。下单时预选，用户可改 |
| `merchantNo` | `string` | 否 | 常去的店。与 communityNo 正交 —— 可以在 A 社区却常买 B 店（ADR-004 §5.1） |


---

## 数据模型

### Address

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `addressId` | `string` | 是 | 地址 ID。这里是 `Id` 不是 `No` —— 它不是业务单号，是用户地址簿里的一条本地记录， 不跨端流转、不出现在订单快照里（下单时地址是**整体快照**进订单的） |
| `name` | `string` | 是 | 收货人姓名 |
| `phone` | `string` | 是 | 收货人手机号 |
| `region` | `string` | 是 | 省市区，拼好给人看的一串 |
| `province` | `string,null` | 否 | 省 / 市 / 区县，**分开的三个**。 与 `region` 并存不是冗余：`region` 是展示用的一串（存量地址、地图回填都只有它）， 这三列是**能拿来算的**那份 —— 按省算运费、按区派单、按市校经营范围。 后端 `usr_address` 一直有这三列，端上一直没填，于是那些规则全在 null 上求值， 一条都不命中，而页面上完全正常。 可能为空：存量地址是纯手填的，拆不出来。 |
| `city` | `string,null` | 否 | — |
| `district` | `string,null` | 否 | — |
| `detail` | `string` | 是 | 详细地址（街道门牌） |
| `isDefault` | `boolean` | 是 | 是否默认地址。整个地址簿至多一条为 true |
| `tag` | `string` | 否 | 标签：家 / 公司 / 其他 |
| `latE6` | `number,null` | 否 | 收货点坐标（gcj02，E6）。地图选点回填；**可能为空** —— 存量地址是纯手填的。 商家的「自送半径」要拿它跟门店坐标算距离，没有坐标那条规则就永远算不出结果。 |
| `lngE6` | `number,null` | 否 | — |

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

### AfterSaleReason

售后原因。**取值与后端 `/mp/after-sale/reasons` 下发的一致** —— 端上不再自己硬编码一份清单（此前那份少两个、多一个，两边各自漂移， 运营改后端那份端上纹丝不动）。 后端下发的是**码**不是文案：这是三语 App，翻译得留在端上。

枚举取值：

- `NOT_WANTED`
- `DAMAGED`
- `MISSING`
- `WRONG_ITEM`
- `QUALITY`
- `EXPIRED`
- `OTHER`

### AfterSaleReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | [`AfterSaleType`](#aftersaletype) | 否 | 仅退款 / 退货退款 —— 两者流程根本不同，不能合成一个 |
| `reason` | `string` | 是 | 已拼好的原因文案（前端把 reason 枚举与补充说明合并后提交） |
| `images` | `string`\[\] | 是 | 举证图。破损/少件类售后没有图基本判不了 |
| `reasonCode` | [`AfterSaleReason`](#aftersalereason) | 否 | 结构化原因，便于服务端统计与风控 |

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

### BindCommunityReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communityNo` | `string` | 是 | 要绑定的社区。**商品可见范围依赖它**，绑错了首页就是别的小区的货 |
| `pickupNo` | `string` | 是 | 默认自提点，须属于该社区 |

### BindPhoneReq

绑定手机号（验证码）。号码要以**字符串**传 —— 见 phone-gate.vue 里那段注释

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `phone` | `string` | 是 | — |
| `code` | `string` | 是 | — |

### CardSpec

卡券属性（CARD）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `faceValueMinor` | `number` | 否 | 储值卡面值（最小货币单位）；次卡为空 |
| `timesTotal` | `number` | 否 | 次卡总次数；储值卡为空 |
| `validDays` | `number` | 是 | 有效期天数 |

### CartAddReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `skuNo` | `string` | 是 | SKU 单号。购物车按 SKU 去重，同商品不同规格是两行 |
| `qty` | `number` | 是 | 加购件数，正整数 |

### CartItem

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `skuNo` | `string` | 是 | SKU 单号。购物车按 SKU 去重，不是按商品 |
| `title` | `string` | 是 | 商品标题快照 |
| `cover` | `string` | 是 | 封面图快照 |
| `spec` | `string` | 是 | 规格文案快照 |
| `price` | `number` | 是 | 加购时的单价（最小货币单位）。结算时以服务端最新价为准，不一致会提示 |
| `qty` | `number` | 是 | 数量 |
| `type` | [`CategoryType`](#categorytype) | 是 | 商品形态 |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 用户选定的履约方式。跨履约方式的商品结算时会拆单 |
| `merchantNo` | `string` | 是 | 所属商家。**后端 `CartItemVO` 一直在发这两个字段，是这里此前没声明**—— 于是数据到了端上就被丢掉，购物车只能按履约方式分组，店名一个字都显示不出来。 后果不是「少个标签」：用户从头到尾看到「一单」，提交后拿到的是按商家拆出的 N 笔子订单（`ord_sub_order`）。见 TDD-购物车商家可见。 |
| `merchantName` | `string` | 是 | 商家名。**购物车按它分组** —— 一车东西来自几家店， 结算时会拆成几笔子订单，分组是把这件事提前说清楚（见 TDD-购物车商家可见）。 |
| `invalidReason` | `string` | 否 | 失效原因，如「已下架」「库存不足」。有值即不可勾选结算 |
| `giftQty` | `number` | 否 | 买赠自动带出的赠品件数（不计价） |
| `giftLabel` | `string` | 否 | 赠品说明，如「买 2 送 1」 |

### CartRemoveReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNos` | `string`\[\] | 是 | 要删除的 SKU 列表。批量是因为购物车支持多选删除 |

### CartUpdateReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `skuNo` | `string` | 是 | 要改的 SKU |
| `qty` | `number` | 是 | 改后的件数。传 0 等同于删除该行 |

### CategoryType

枚举取值：

- `NORMAL`
- `FRESH`
- `SERVICE`
- `VIRTUAL`
- `CARD`

### CheckoutCapability

结算页的<b>能力提示</b>：这一车货能不能开票、能用哪些支付方式、额度还够不够。 <p>与  {@link  OrderPreview }  分开是有意的：preview 回答「多少钱」， 这个回答「付得了吗、票拿得到吗」。 <p>三件事一起给，是因为它们的共同后果都是<b>付款那一刻才炸</b>—— 小微没有 H5/App 支付方式（混合购物车整单付不了）、小微不能开票 （买完才发现补救不了）、额度用尽（通道直接拒收）。 每一条单独看都像偶发故障，放在一起看才是同一件事： 平台放弱主体进来了，而结算页还没告诉买家这意味着什么。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `usablePayMethods` | `string`\[\] \| `null` | 是 | 整单可用的支付方式 = <b>各商家支持集合的交集</b>。 交集而非并集：一笔支付覆盖整单，有一家不支持就用不了。 <b>空数组 = 这一车货没有任何方式能付</b>，端上要拦在结算页 —— 让他点下去只会得到一个说不清原因的「支付失败」。 <b>null = 未配置</b>（一个商家都还没进件完）——端上<b>不要拦</b>。 两者混成空数组的话，一个完全正常的订单会被拦死。 |
| `anyNotInvoiceCapable` | `boolean` | 是 | 车里有商家开不了票。**必须在付款前告诉用户**：买完才发现，平台补救不了 |
| `merchants` | [`MerchantCapability`](#merchantcapability)\[\] | 是 | 逐商家的能力，端上据此在对应的商家分组上打标 |
| `usablePayModes` | `string`\[\] | 是 | 整单可用的**支付方式**（`PAY_MODE`：ONLINE / OFFLINE）。 ⚠️ **与 `usablePayMethods` 是两根轴，别混**：那个是**通道** （WECHAT / ALIPAY / H5…），这个是**线上付还是当面付**。 一笔订单要同时确定两者。 同样取交集（一笔支付覆盖整单）。**ONLINE 永远在里面**， 所以不会是空集，也就不需要 `null` 那一档 —— 与 `usablePayMethods` 的取舍不同，因为那边真的可能「没配过」。 |

### ChooseQuoteReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `quoteNo` | `string` | 是 | 选定的报价。**选定即锁价**，此后下单一律用快照价（ADR-003） |

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

### Coupon

优惠券模板。**字段与后端 `CouponVO` 一一对应**。 这里原先是一个被简化过的形状（`name` / `discountMinor` / `expireAt`）， 与后端一个都对不上，后果不是「少显示一块」而是**领券中心永远是空的**： 页面按 `c.expireAt > now` 过滤，而后端发的是 `endAt` —— `undefined > now` 恒 false，于是商家配好的券一张都露不出来，两边都不报错。 <b>而且那个简化本身是错的</b>：`discountMinor` 一个数表达不了折扣券 —— 折扣券要的是「打几折 + 最多减多少」。后端的形状才是对的，端上跟它。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `couponNo` | `string` | 是 | 券单号 |
| `title` | `string` | 是 | 券名，如「满 50 减 5」 |
| `type` | [`CouponType`](#coupontype) | 是 | — |
| `faceMinor` | `number` | 是 | 满减面额（最小货币单位）。`DISCOUNT` 券为 0 |
| `discountRate` | `number` | 是 | 折扣**万分比**，8500 = 八五折。`FULL_CUT` 券为 0 |
| `thresholdMinor` | `number` | 是 | 使用门槛（最小货币单位）。0 表示无门槛 |
| `maxDiscountMinor` | `number` | 是 | 折扣券封顶（最小货币单位）。仅 `DISCOUNT` 有意义 |
| `funder` | [`CouponFunder`](#couponfunder) | 是 | — |
| `merchantNo` | `string` | 是 | 商家券的归属商家；平台券为空 |
| `startAt` | `number` | 是 | 可领取/可用的时间窗 |
| `endAt` | `number` | 是 | — |
| `remain` | `number` | 是 | 剩余可领数量 |
| `received` | `boolean` | 是 | 当前用户是否已领取。列表页据此显示「领取」还是「去使用」 |
| `status` | [`CouponStatus`](#couponstatus) | 是 | — |
| `scopeDesc` | `string` | 是 | 适用范围文案，如「仅限张记粮油店」。展示用，实际校验在服务端 |

### CouponFunder

券的出资方。决定这张券的钱最后从谁账上扣 —— 平台券走平台预算，商家券从结算里扣

枚举取值：

- `PLATFORM`
- `MERCHANT`

### CouponStatus

券状态。与后端 `MktCoupon` 一致；平台列表要靠它筛出被停的券

枚举取值：

- `ACTIVE`
- `PAUSED`
- `ENDED`

### CouponType

券类型。与后端 `MktCoupon` 的常量逐字一致

枚举取值：

- `FULL_CUT`
- `DISCOUNT`

### CreateGroupBuyReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 要开团的商品，必须是已上架商品 |
| `pickupNo` | `string` | 是 | 成团的自提点 |
| `neighbor` | `object`（见下） | 否 | 邻里自提：送到我家（ADR-005）。只能是发起人自己家，不能指定别人家 |

`neighbor` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `toMyHome` | `boolean` | 是 | — |
| `address` | `string` | 是 | — |
| `timeSlot` | `string` | 是 | — |

### CreateOrderReqBody

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `items` | `object`（见下）\[\] | 是 | 下单行。跨商家时服务端**拆成多笔子订单**，共享一个 payGroupNo（E3） |
| `fulfillment` | [`FulfillmentType`](#fulfillmenttype) | 是 | 履约方式。决定下面 pickupNo / addressId / appointmentAt 哪个必填 |
| `pickupNo` | `string` | 否 | PICKUP 必填：自提点单号 |
| `addressId` | `string` | 否 | EXPRESS / 自送必填：收货地址。下单时地址整体**快照**进订单 |
| `couponNo` | `string` | 否 | 使用的优惠券 |
| `usePoints` | `number` | 否 | 使用的积分数。服务端按抵扣上限与账户余额截断，端上传的只是意愿 |
| `remark` | `string` | 否 | 买家留言 |
| `groupNo` | `string` | 否 | 参团下单时传团单号。**后端 CreateOrderReq 目前不认这个字段**，接上去会静默变成普通单 |
| `appointmentAt` | `number` | 否 | APPOINTMENT：预约开始时间戳 |
| `payMode` | `string` | 否 | 支付方式（`PAY_MODE`）。**不传按 ONLINE** —— 存量端上没有这个字段， 不能因为补了它就让老版本下不了单。 能不能选 OFFLINE 由 `orderCapability` 的 `usablePayModes` 说了算， 而后端在 create 里会**再判一次**：端上不该是唯一的闸。 |
| `appointmentSlotNo` | `string` | 否 | APPOINTMENT：选定的**预约时段**。这家店开了时段就必填 —— 没开则忽略，走 `appointmentAt` 那条旧路（兼容期）。 |
| `idempotencyKey` | `string` | 是 | 幂等 key，防重复提交 |

`items[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `skuNo` | `string` | 是 | SKU 单号 |
| `qty` | `number` | 是 | 件数 |

### CreateRequestReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `pickupNo` | `string` | 是 | 需求所属的自提点/小区 |
| `title` | `string` | 是 | 需求标题，如「想团儿童床垫」。发起时**商品还不存在** |
| `desc` | `string` | 是 | 需求详述：尺寸、材质、用途，供商家判断能不能接 |
| `expectQty` | `number` | 是 | 期望数量 |
| `budgetMinor` | `number` | 否 | 心理价位（最小货币单位），可不填。填了商家报价更有的放矢 |

### CreateReviewReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `orderNo` | `string` | 是 | 被评价的订单。**必须是已完成订单**，且一单一评 |
| `goodsNo` | `string` | 是 | 被评价的商品。一单多商品时逐个评 |
| `rating` | `number` | 是 | 总分，1–5 整数 |
| `content` | `string` | 是 | 评价正文 |
| `images` | `string`\[\] | 是 | 评价图 URL 列表，可为空数组 |
| `scores` | [`ReviewScores`](#reviewscores) | 否 | 三维分（商品 / 履约 / 服务）。**可选** —— 老客户端只给总分。 评价页一直在发这个字段，但类型里漏了它，于是它是**悄悄漏出去的**： `satisfies` 检查不到、OpenAPI 里没有、后端也就无从知道要收。 是 wire-alignment 守卫在后端实现时把它抓出来的。 |

### CurrencyCode

枚举取值：

- `CNY`
- `USD`
- `AED`

### FrequentItem

常买清单的一行（C-ST-02）。按购买频次排序，不是按时间

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goodsNo` | `string` | 是 | 商品单号 |
| `skuNo` | `string` | 是 | SKU 单号。常买是按 SKU 记的 —— 买惯了 5 斤装的人不想要 10 斤装 |
| `title` | `string` | 是 | 商品标题 |
| `cover` | `string` | 是 | 封面图 |
| `spec` | `string` | 是 | 规格文案 |
| `price` | `number` | 是 | 当前价（可能已与上次购买时不同） |
| `lastPrice` | `number` | 是 | 上次买的价，用于「涨价了」提示 |
| `times` | `number` | 是 | 买过几次。列表按它排序，不是按时间 |
| `lastAt` | `number` | 是 | 上次购买时间 |
| `invalid` | `boolean` | 否 | 已下架/无库存 —— 一键再来一单时要显式标出，不能静默丢掉 |

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

### InvoiceRequest

开票申请：**平台开给消费者**的销项票。 与结算侧的采购发票（`stl_purchase_invoice`）是两回事： 那是**进项**（供应商开给平台，决定平台能不能列支成本）， 这是**销项**（平台开给消费者，决定归集资金模式成不成立）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `requestNo` | `string` | 是 | — |
| `orderNo` | `string` | 是 | 按**主单**申请，不按子单 —— 消费者眼里那是一次购买，票也该是一张 |
| `titleType` | [`InvoiceTitleType`](#invoicetitletype) | 是 | — |
| `title` | `string` | 是 | — |
| `taxNo` | `string` | 否 | 单位抬头必填 |
| `email` | `string` | 是 | 电子票只能发到这里，填错就是开了也收不到 |
| `amountMinor` | `number` | 是 | 开票金额快照。**不实时读订单** —— 退款会改订单金额，已开的票不会跟着变 |
| `status` | [`InvoiceRequestStatus`](#invoicerequeststatus) | 是 | — |
| `invoiceNo` | `string` | 否 | — |
| `issuedAt` | `number` | 否 | — |
| `rejectReason` | `string` | 否 | 驳回原因。不写原因的驳回等于让消费者再猜一遍 |
| `createdAt` | `number` | 否 | — |

### InvoiceRequestStatus

开票申请的状态（ADR-017 §3.4 条件 2）。 本版是**手工开票**：运营在票据系统里开完，回来回填票号。 接票据系统是第二步，届时在 `ISSUED` 之后延长状态机，不改前面的。

枚举取值：

- `REQUESTED`
- `ISSUED`
- `REJECTED`

### InvoiceTitleType

抬头类型。单位抬头必须有税号，否则对方入不了账 —— 票开出来等于白开

枚举取值：

- `PERSONAL`
- `COMPANY`

### JoinGroupBuyReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `qty` | `number` | 是 | 参团件数，正整数 |

### LoginReqBody

入驻申请可选的商家类型。 不用 `Extract<MerchantType, ...>` —— 生成 schema 时它的名字会变成 `Extract<MerchantType,("COMPANY"\|"INDIVIDUAL")>`，不符合 OpenAPI 的组件命名规则。 契约类型要能干净地映射成 DTO 名，所以这里写成直白的联合。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `grantType` | [`GrantType`](#granttype) | 是 | 登录方式，决定 principal / credential 各放什么 |
| `principal` | `string` | 是 | WX_MINI: wx.login code；PHONE_OTP: 手机号 |
| `credential` | `string` | 否 | PHONE_OTP: 验证码 |
| `inviterNo` | `string` | 否 | 裂变归因：邀请人 |
| `merchantNo` | `string` | 否 | 进店归因：从店铺码/店铺分享进入时带上（ADR-004 §5.4） |

### LoginResp

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `token` | `string` | 是 | 访问令牌。后续请求放 `Authorization: Bearer <token>` |
| `user` | [`User`](#user) | 是 | 登录用户档案 |

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

### Merchant

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家单号。贯穿商品/订单/评价/结算，是多商家模型的主线（ADR-001） |
| `selfOperated` | `boolean` | 否 | 这单是不是**平台自营**（销售主体是平台）。 **必须显示出来 —— 电商法 §37 要求平台以显著方式区分标记自营业务， 不得误导消费者。这是法定义务，不是产品选择。** 而它同时是资金模式合法性的一部分：归集路径下平台是销售主体， 页面上却让消费者以为在跟商家交易，四流就不一致了（ADR-017 §3.4）。 ⚠️ 自营时**商家信息照常展示**（供货商、产地、门店、评分）—— 要禁的是把销售方指给商家的**表述**，不是商家信息本身。 见 `packages/shared/tests/seller-statement.test.ts` 的禁用词表。 |
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo URL |
| `rating` | `number` | 是 | 综合评分，0–5，保留一位小数。**0 分要配合 `ratingCount` 一起看** |
| `ratingCount` | `number` | 是 | 参与评分的评价条数 |
| `verified` | `boolean` | 是 | 是否通过资质认证 |
| `breachCount` | `number` | 是 | 选定报价后不履约的次数。>0 会在报价卡上公示 —— 事后信用替代事前审核 |
| `type` | [`MerchantType`](#merchanttype) | 是 | 商家类型：平台自营 / 企业 / 个体 |
| `desc` | `string` | 是 | 店铺简介 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围 —— 邻里购物的核心约束：**商家是有服务半径的**。 隔壁区的生鲜店对我没有意义，它送不到我的自提点。见 SERVICE_SCOPE。 |
| `serviceCommunityNos` | `string`\[\] | 是 | 覆盖哪些社区。**仅 scope=COMMUNITY 时有意义**，其余情况忽略 |
| `serviceCityCode` | `string` | 否 | 覆盖哪个城市。**仅 scope=CITY 时有意义** |
| `distance` | `number` | 否 | 距当前社区的距离（米）。由服务端按用户当前社区算好下发，端上不自己算 |
| `salesCount` | `number` | 是 | 累计订单量（评分权重之一） |
| `goodsCount` | `number` | 是 | 在售商品数 |
| `address` | `string` | 否 | 店铺地址。纯线上商家可能没有 |
| `openHours` | `string` | 否 | 营业时间文案 |
| `joinedAt` | `number` | 是 | 入驻时间 |
| `tags` | `string`\[\] | 是 | 店铺标签，如「生鲜」「次日达」。展示用，不参与筛选 |
| `scores` | `object`（见下） | 是 | 分维度评分：商品/服务/时效 |

`scores` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goods` | `number` | 是 | — |
| `service` | `number` | 是 | — |
| `speed` | `number` | 是 | — |

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

### MerchantApplyReviewStatus

入驻申请的审核状态。与库 `mch_entity_apply.status` 逐字一致。 ⚠️ 与  {@link  MerchantStatus } （B 端「我能不能干活」的合并视图）不是一回事。

枚举取值：

- `PENDING`
- `REVIEWING`
- `APPROVED`
- `REJECTED`

### MerchantApplyStatus

入驻申请状态（C 端查自己的进度 / 平台端审核队列共用）。 状态机：`PENDING → REVIEWING → APPROVED \| REJECTED`，`REJECTED → PENDING`（补料重提）。 **APPROVED 是终态** —— 已经建了商家、发了账号，回退没有意义。 ⚠️ 这条是**审核**生命周期，与 `Merchant` 上的**经营**状态（ACTIVE/SUSPENDED）无关： 审核发生在商家还不存在的时候，封禁发生在商家已经存在之后。混成一个枚举会让 「驳回一份申请」和「封禁一家店」共用取值，两件事迟早互相踩。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `applyNo` | `string` | 是 | 申请单号 |
| `name` | `string` | 是 | 申请时填的店铺名。**存快照** —— 后来改名不该让历史申请跟着变 |
| `subject` | [`MerchantSubject`](#merchantsubject) | 是 | 主体类型。决定分账主体形态与所需资质（ADR-002 §4） |
| `status` | [`MerchantApplyReviewStatus`](#merchantapplyreviewstatus) | 是 | 审核状态。迁移见本类型的注释，APPROVED 为终态 |
| `rejectReason` | `string` | 否 | 驳回理由。**驳回必须写** —— 不写就等于让人猜着改 |
| `merchantNo` | `string` | 否 | 通过后生成的商家单号。未通过时为空 —— 商家在通过之前根本不存在 |
| `createdAt` | `number` | 是 | 提交时间 |
| `auditedAt` | `number` | 否 | 审核完成时间。PENDING/REVIEWING 期间为空 |
| `contactName` | `string` | 是 | 联系人姓名 |
| `contactPhone` | `string` | 是 | 联系手机号。这是申请人自己填的联系号码，**不是登录号**，不脱敏 |
| `category` | `string` | 是 | 主营类目 |
| `desc` | `string` | 是 | 店铺简介 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 否 | 期望经营范围（ADR-009） |
| `communityNos` | `string`\[\] | 否 | 期望覆盖的社区 |
| `licenses` | `string`\[\] | 否 | 已传的资质图（只有图片 URL，看不出是哪种证、什么时候过期） |
| `qualificationItems` | [`QualificationItem`](#qualificationitem)\[\] | 否 | 结构化资质（V79）：**哪张证、证件号、有效期**。 ⚠️ 这一段的标题写着「用于驳回后回填」，而此前只回填了  {@link  licenses }  ——只有图片。**证件类型、编号、有效期三项全丢**，商家重提时得逐格再填一遍， 而这正是本段注释想避免的那件事：「把补交变成重来」。 后端 `MerchantApplyVO` 一直在发它（审核台就靠它看类型与有效期）， 端上这里没声明。 |
| `industry` | `string` | 否 | 申请时选的行业。驳回回填要用它 —— 换个行业可能连主体类型都得跟着换 |
| `asPickupPoint` | `boolean` | 否 | 是否愿意承接自提点（ADR-005）。 **只是意愿，不代表点已建立** —— 建点要谈服务费口径，一期由运营在通过后另行处理。 所以商家勾了这一项、通过后却还没看到履约台，是正常的中间状态而不是故障。 |

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

### MerchantCapability

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家单号 |
| `merchantName` | `string` | 是 | 商家名，展示用 |
| `invoiceCapable` | `boolean` | 是 | 能否开票 |
| `payMethods` | `string`\[\] | 是 | 该商家支持的支付方式；**空 = 未配置**（进件还没走完），不是「一种都不支持」 |
| `quotaExhausted` | `boolean` | 是 | 本期收款额度已用尽 —— 这家的货现在下不了单 |
| `quotaWouldExceed` | `boolean` | 是 | 加上本车这些货会超额 —— 还没用尽，但这一单过不去 |

### MerchantSubject

枚举取值：

- `NATURAL_PERSON`
- `INDIVIDUAL`
- `ENTERPRISE`

### MerchantType

类型：`MerchantSubject`

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

### MyMembership

「我是这家店的会员」（C 端，P7）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `entityNo` | `string` | 是 | — |
| `entityName` | `string` | 是 | — |
| `level` | `string,null` | 否 | — |
| `orderCount` | `number` | 是 | — |
| `totalSpentMinor` | `number` | 是 | — |
| `reachOptOut` | `boolean` | 是 | 我关掉了这家店的消息没有。**只有本人能改** |
| `joinedAt` | `number` | 是 | — |

### MyStoreCoupon

买家券包里<b>商家发的那一张</b>（新模型，P6）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `userCouponNo` | `string` | 是 | — |
| `couponNo` | `string` | 是 | — |
| `title` | `string` | 是 | — |
| `benefitText` | `string` | 是 | 「减 3 元」「8.5 折」「凭券兑换」这种人话，后端拼好 |
| `entityNo` | `string,null` | 否 | — |
| `redeemMode` | `string` | 是 | `ORDER` 下单抵扣 / `STORE_CODE` 到店出示 |
| `redeemCode` | `string,null` | 否 | 到店出示的码。**只有 STORE_CODE 券有** —— 别给下单券显示码 |
| `minAmountMinor` | `number,null` | 否 | — |
| `timesTotal` | `number` | 是 | — |
| `timesUsed` | `number` | 是 | — |
| `remaining` | `number` | 是 | 次卡还剩几次 |
| `expireAt` | `number` | 是 | — |
| `status` | `string` | 是 | — |
| `usableNow` | `boolean` | 是 | — |

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

### OrderPreview

订单预览的返回。**后端返的是完整 OrderVO，这里只声明端上要用的那部分** —— 预览页只关心金额与行，声明全套会让每次后端加字段都得改端上类型。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `amount` | [`OrderAmount`](#orderamount) | 是 | 试算出来的金额。**页面显示的应付必须等于这里的 payableMinor** —— 端上不要自己再算一遍：优惠叠加顺序（先活动后券）在后端， 两处各算一次必然算出两个数，而用户看到的是「确认页 46.40、付款 51.40」。 |
| `items` | [`OrderItem`](#orderitem)\[\] | 是 | 试算出来的订单行，含赠品行（价格 0）。数量与下单后落库的一致 |

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

### PhoneCapable

微信一键取手机号当前可不可用。 <p>由后端说了算：它取决于小程序认证状态与通道开关，端上判不出来。 写死在端上的话，认证下来之后还要再发一次版。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `capable` | `boolean` | 是 | true = 显示「微信一键获取」；false = 显示手机号 + 验证码 |

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

### PickupFeeMode

自提点计费方式。**与 ops-web 的 `PickupFeeMode` 同值** —— 费率线下逐点协商，故两种都留

枚举取值：

- `NONE`
- `PER_ITEM`
- `RATE`

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

### PointAccount

用户积分账户。**单位是积分个数** —— 商家侧是钱，用  {@link  MerchantPointAccount }

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `balance` | `number` | 是 | 当前可用余额。**只含能花的分**，待生效的在 pendingBalance |
| `pendingBalance` | `number` | 是 | 待生效积分：已发放但未过售后期，**不计入 balance**。 两个数必须分开展示（「可用 400 / 待生效 100」）。合成一个的话， 用户看到「我有 500 分」却只能用 400，没有任何办法解释这个差额。 |
| `pendingActivateAt` | `number` | 否 | 最近一批待生效积分的可用时间。`pendingBalance=0` 时为空 |
| `totalEarned` | `number` | 是 | 累计获得（含已用、已过期），只增不减 |
| `totalUsed` | `number` | 是 | 累计已抵扣 |
| `expiringSoon` | `number` | 是 | 30 天内将过期的积分 |
| `expiringAt` | `number` | 否 | 最近一批积分的过期时间。`expiringSoon=0` 时为空 |

### PointRecord

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `recordNo` | `string` | 是 | 流水单号。积分是平台负债，每一笔变动都要可追溯（ADR-006） |
| `type` | [`PointRecordType`](#pointrecordtype) | 是 | 变动类型，决定这笔是增是减 |
| `points` | `number` | 是 | 变动量，正=增加 负=减少 |
| `title` | `string` | 是 | 流水标题，如「订单消费获得」「过期作废」。展示用 |
| `orderNo` | `string` | 否 | 关联订单。消费/退款类必有，过期/结算类为空 |
| `at` | `number` | 是 | 发生时间 |
| `balanceAfter` | `number` | 是 | 变动后余额，用于对账 —— 只存变动量的话，一条记录出错后面全错 |

### PointRecordType

枚举取值：

- `EARN`
- `USE`
- `REFUND`
- `EXPIRE`
- `RECEIVE`
- `SETTLE`

### PointsDeductible

结算页的积分试算结果。**服务端算**，端上只负责显示。 端上自己算的话，下单时服务端会再算一遍 —— 两处算法只要有一点不同 （券后金额口径、运费是否参与、开关判断顺序），用户就会看到 「结算页说能抵 30，下单后只抵了 25」，而这个差额没人解释得清。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `maxPoints` | `number` | 是 | 本单最多可抵扣的积分数。已扣掉四级开关与上限，端上直接用 |
| `maxAmountMinor` | `number` | 是 | 对应金额（分） |
| `balance` | `number` | 是 | 用户当前可用余额，用于展示「你有 X 分」 |
| `disabledReason` | `string` | 否 | 不可用时的原因，直接展示。可用时为空 |

### Promotion

促销：买 N 送 M。 语义：购买数量达到 N 件，赠送 M 件 —— 用户**付 N 件的钱，收到 N+M 件**。 赠品不进计价（价格为 0），只作为订单里的独立行存在，履约时随单发出。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `type` | `string` | 是 | 促销类型。目前只有买 N 送 M 一种 |
| `buyN` | `number` | 是 | 购买件数门槛 N |
| `giftM` | `number` | 是 | 赠送件数 M |
| `giftGoodsNo` | `string` | 否 | 赠品商品号；不填则赠同款 |
| `giftTitle` | `string` | 否 | 赠品展示名（后端下发已本地化） |

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

### QuoteRevision

一次改价的留痕

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `priceMinor` | `number` | 是 | 改价后的单价（最小货币单位） |
| `at` | `number` | 是 | 改价时间 |

### Record_string_number

类型：`object`

### Record_string_string

类型：`object`

### RegionNode

行政区划树上的一个节点（`/mp/regions`）。 与  {@link  RegionOption }  是**两个问题的答案**，不要混用： `RegionOption` 答的是「我能在哪儿取货」（只列有已开通社区的区）， 这个答的是「我家在哪儿」—— 没开通的区也要能选出来，人确实住在那儿。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | 国标码：省 2 位 / 市 4 位 / 区县 6 位 |
| `parentCode` | `string,null` | 否 | 上级码。省级为 null |
| `level` | `string` | 是 | `PROVINCE` \| `CITY` \| `DISTRICT`。地址簿只到区县，街道与村不下发 |
| `name` | `string` | 是 | — |
| `hasChild` | `boolean` | 是 | 还有没有下一级。**区县恒为 false** —— 地址表只有省市区三列， 让人点进街道再挑一个存不下去的东西，比不让他挑更糟。 |

### RegionOption

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `regionCode` | `string` | 是 | 区县级国标码（6 位）。社区可能挂在街道级，聚合时截到区县 |
| `name` | `string` | 是 | 区县名，如「西湖区」 |
| `cityCode` | `string` | 是 | 所属市码（4 位） |
| `cityName` | `string` | 是 | 所属市名。同名区县全国很多（如「城关区」），不带市名用户分不清是哪一个 |
| `communityCount` | `number` | 是 | 该区县下已开通的社区数。「西湖区 · 2 个小区」比光秃秃一个区名有用得多 |

### ReorderResult

一键再来一单的结果（C-ST-03）。**丢了什么必须说清楚**，静默少加是投诉源头

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `added` | `number` | 是 | 成功加入购物车的件数 |
| `dropped` | `string`\[\] | 是 | 已失效、没加进购物车的商品名 |
| `priceUp` | `string`\[\] | 是 | 涨价了但仍加入的商品名 |

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

### SaveAddressReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `addressId` | `string` | 否 | 有值 = 编辑，无值 = 新增 |
| `name` | `string` | 是 | 收货人姓名 |
| `phone` | `string` | 是 | 收货人手机号 |
| `region` | `string` | 是 | 省市区，拼好给人看的一串 |
| `province` | `string,null` | 否 | 省 / 市 / 区县，分开的三个。后端 `SaveAddressReq` 一直收这三个字段， 端上一直没发 —— 于是 `usr_address` 那三列永远是 null（见 `Address` 的注释） |
| `city` | `string,null` | 否 | — |
| `district` | `string,null` | 否 | — |
| `detail` | `string` | 是 | 详细地址（街道门牌） |
| `isDefault` | `boolean` | 是 | 设为默认。置 true 会把原默认地址改为 false |
| `tag` | `string` | 否 | 标签：家 / 公司 / 其他 |
| `latE6` | `number,null` | 否 | 地图选点给的坐标（gcj02，E6）；不传 = 不改 |
| `lngE6` | `number,null` | 否 | — |

### ServiceScope

枚举取值：

- `COMMUNITY`
- `CITY`
- `PLATFORM`

### SettleAccountType

结算账户形态。个人 openid 收款 / 对公商户号收款（ADR-002 §5）

枚举取值：

- `PERSONAL_BANK_CARD`
- `MERCHANT_ID`

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

### SpecGroup

规格维度，例：{ name: "重量", options: ["约5斤", "约10斤"] }

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 规格维度名，如「重量」「包装」 |
| `options` | `string`\[\] | 是 | 该维度的可选值，如 `["约5斤", "约10斤"]` |
| `optionCodes` | `string` \| `any`\[\] | 否 | 与 options 一一对应的模板编码。来自模板的选项有值，自由输入的为空。 一期只写入不消费 —— 但不留位的话，二期做规格聚合要刷全部历史商品。 |
| `templateNo` | `string` | 否 | 该规格组来自哪个模板（便于「用的人多不多」这类平台侧统计） |

### StoreFront

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `announcement` | `string` | 是 | 店铺公告：「今日到货」「今天有土鸡蛋」，店主自发（C-ST-04） |
| `announcementAt` | `number,null` | 否 | 公告最后一次发布的时刻（epoch 毫秒）。没发过、或已过期时为空。 **这一行必须带时间**：一句没有时间的「今天到了新米」，既可能是今早写的， 也可能是上个月忘了撤的 —— 老客分不出来就不会再照着它跑一趟， 而「照着公告来一趟」正是这行字存在的全部理由。 |
| `openHours` | `string` | 是 | 营业时间文案，店主自填 |
| `address` | `string` | 是 | 店铺地址，店主自填 |
| `latE6` | `number,null` | 否 | 门店坐标（gcj02，E6）。**可能为空** —— 商家没在地图上标过点。 买家侧据此决定「导航到这里」显不显示：没坐标的导航按钮点了只会打开一片空白。 |
| `lngE6` | `number,null` | 否 | — |

### StoreHome

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchant` | [`MerchantBrief`](#merchantbrief) | 是 | 平台建档的商家主数据（名称/资质/评分），店主改不了 |
| `store` | [`StoreFront`](#storefront) | 是 | 店主自己维护的门面内容 |
| `goods` | [`Goods`](#goods)\[\] | 是 | 在售商品。首屏展示，分页靠单独的商品列表接口 |
| `categories` | [`StoreShelf`](#storeshelf)\[\] | 是 | 本店货架：**店主自己排的顺序、自己改的名字**（「本地时鲜」而不是「蔬菜」）。 只含真的有在售商品的类目 —— 摆着却一件货都没有的类目，点进去空手而归。 少于两条时端上不画这一行：一个恒真的筛选开关只是占地方。 |
| `favorited` | `boolean` | 是 | 我是否收藏了这家店 |
| `closed` | `boolean` | 否 | 已停业（门店非 ACTIVE：商家自助停用或平台强制下线）。 **是标志而不是 404**：扫码进来的老客要知道「店关了」，不是「链接坏了」。 端上据此盖「已停业」并禁掉加购。 ⚠️ 后端 `StoreHomeVO` 一直在发这个字段，这里此前没声明 —— 于是**扫码进一家已停业的店，看起来与正常营业毫无区别**， 加购、下单一路走到底，最后在库存或下单闸门上撞一个说不清的错误。 |

### StoreShelf

店铺页上的一类。`count` 直接显示，省得买家点进去数

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `categoryNo` | `string` | 是 | — |
| `name` | `string` | 是 | — |
| `count` | `number` | 是 | — |

### TrafficSource

流量来源。**与 ops-web 的 `TrafficSource` 同名** —— 那边多 INVITE/CHANNEL 两个值（已标 MERGE）

枚举取值：

- `MERCHANT_OWNED`
- `PLATFORM`

### User

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `cUserNo` | `string` | 是 | C 端用户单号。前缀 `cUser` 是有意的：B 端商家、平台 STAFF 是**另外两个账号池**，单号不通用 |
| `nickname` | `string` | 是 | 昵称。微信授权取来的，用户可改 |
| `avatar` | `string` | 是 | 头像 URL |
| `phone` | `string` | 是 | 手机号。已脱敏（中间四位星号），完整号码不下发到端上 |
| `communityNo` | `string` | 否 | 当前绑定的社区。未绑定时为空 —— 首页的商品可见范围依赖它 |
| `pickupNo` | `string` | 否 | 默认自提点。下单时预选，用户可改 |
| `merchantNo` | `string` | 否 | 常去的店。与 communityNo 正交 —— 可以在 A 社区却常买 B 店（ADR-004 §5.1） |

### UserCard

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `cardNo` | `string` | 是 | 卡号。核销时出示的就是它 |
| `goodsNo` | `string` | 是 | 购买时的商品单号 |
| `title` | `string` | 是 | 卡名快照 |
| `cover` | `string` | 是 | 卡面图 |
| `balanceMinor` | `number` | 否 | 储值卡剩余额度（最小货币单位） |
| `timesLeft` | `number` | 否 | 次卡剩余次数 |
| `expireAt` | `number` | 是 | 过期时间。过期后余额/次数作废 |
| `currency` | [`CurrencyCode`](#currencycode) | 是 | 购卡时锁定的货币，不随用户切市场变化 |

### VirtualSpec

虚拟商品属性（VIRTUAL）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `deliverDesc` | `string` | 是 | 发放说明，如「支付后 1 分钟内短信发码」 |

### VisitedMerchant

消费过的商家（「我买过的」列表用）

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家单号。贯穿商品/订单/评价/结算，是多商家模型的主线（ADR-001） |
| `selfOperated` | `boolean` | 否 | 这单是不是**平台自营**（销售主体是平台）。 **必须显示出来 —— 电商法 §37 要求平台以显著方式区分标记自营业务， 不得误导消费者。这是法定义务，不是产品选择。** 而它同时是资金模式合法性的一部分：归集路径下平台是销售主体， 页面上却让消费者以为在跟商家交易，四流就不一致了（ADR-017 §3.4）。 ⚠️ 自营时**商家信息照常展示**（供货商、产地、门店、评分）—— 要禁的是把销售方指给商家的**表述**，不是商家信息本身。 见 `packages/shared/tests/seller-statement.test.ts` 的禁用词表。 |
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo URL |
| `rating` | `number` | 是 | 综合评分，0–5，保留一位小数。**0 分要配合 `ratingCount` 一起看** |
| `ratingCount` | `number` | 是 | 参与评分的评价条数 |
| `verified` | `boolean` | 是 | 是否通过资质认证 |
| `breachCount` | `number` | 是 | 选定报价后不履约的次数。>0 会在报价卡上公示 —— 事后信用替代事前审核 |
| `type` | [`MerchantType`](#merchanttype) | 是 | 商家类型：平台自营 / 企业 / 个体 |
| `desc` | `string` | 是 | 店铺简介 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围 —— 邻里购物的核心约束：**商家是有服务半径的**。 隔壁区的生鲜店对我没有意义，它送不到我的自提点。见 SERVICE_SCOPE。 |
| `serviceCommunityNos` | `string`\[\] | 是 | 覆盖哪些社区。**仅 scope=COMMUNITY 时有意义**，其余情况忽略 |
| `serviceCityCode` | `string` | 否 | 覆盖哪个城市。**仅 scope=CITY 时有意义** |
| `distance` | `number` | 否 | 距当前社区的距离（米）。由服务端按用户当前社区算好下发，端上不自己算 |
| `salesCount` | `number` | 是 | 累计订单量（评分权重之一） |
| `goodsCount` | `number` | 是 | 在售商品数 |
| `address` | `string` | 否 | 店铺地址。纯线上商家可能没有 |
| `openHours` | `string` | 否 | 营业时间文案 |
| `joinedAt` | `number` | 是 | 入驻时间 |
| `tags` | `string`\[\] | 是 | 店铺标签，如「生鲜」「次日达」。展示用，不参与筛选 |
| `scores` | `object`（见下） | 是 | 分维度评分：商品/服务/时效 |
| `orderCount` | `number` | 是 | 在该商家的下单次数 |
| `lastOrderAt` | `number` | 是 | 最近一次下单时间 |

`scores` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `goods` | `number` | 是 | — |
| `service` | `number` | 是 | — |
| `speed` | `number` | 是 | — |

### WxPhoneReq

微信一键授权：端上只拿得到 code，换号在后端做

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `code` | `string` | 是 | — |
