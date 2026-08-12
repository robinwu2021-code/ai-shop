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
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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

#### GET `/mp/community/nearby`

附近社区与自提点　🔒

**入参**

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|:---:|---|
| `lat` | query | `number` | 否 | 纬度 |
| `lng` | query | `number` | 否 | 经度 |

**出参**（`data`）

类型：[`Community`](#community)\[\]


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
| `name` | `string` | 是 | 券名，如「满 50 减 5」 |
| `thresholdMinor` | `number` | 是 | 使用门槛（最小货币单位）。0 表示无门槛 |
| `discountMinor` | `number` | 是 | 抵扣金额（最小货币单位） |
| `expireAt` | `number` | 是 | 过期时间 |
| `received` | `boolean` | 是 | 当前用户是否已领取。列表页据此显示「领取」还是「去使用」 |
| `scopeDesc` | `string` | 是 | 适用范围文案，如「仅限张记生鲜」。展示用，实际校验在服务端 |


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
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `groupNo` | `string` | 否 | MATCHED 后指向生成的正式团 |
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
| `groupNo` | `string` | 否 | MATCHED 后指向生成的正式团 |
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
| `groupNo` | `string` | 否 | MATCHED 后指向生成的正式团 |
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
| `groupNo` | `string` | 否 | MATCHED 后指向生成的正式团 |
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
| `groupNo` | `string` | 否 | MATCHED 后指向生成的正式团 |
| `lockedPriceMinor` | `number` | 否 | 选定的报价快照。转成正式团后下单用这个价，**不读商家当前价** —— 这是防加价最硬的一层：加价在技术上做不到，不需要审核。 |
| `confirmed` | `boolean` | 否 | 我（+1 的邻居）是否已二次确认下单。+1 不等于承诺，必须各自确认 |
| `confirmedCount` | `number` | 否 | 已确认下单的人数 |

`neighbours[]` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `avatar` | `string` | 是 | — |
| `nickname` | `string` | 是 | — |


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
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo URL |
| `rating` | `number` | 是 | 综合评分，0–5，保留一位小数 |
| `verified` | `boolean` | 是 | 是否通过资质认证 |
| `breachCount` | `number` | 是 | 选定报价后不履约的次数。>0 会在报价卡上公示 —— 事后信用替代事前审核 |
| `type` | [`MerchantType`](#merchanttype) | 是 | 商家类型：平台自营 / 企业 / 个体 |
| `desc` | `string` | 是 | 店铺简介 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围 —— 邻里购物的核心约束：**商家是有服务半径的**。 隔壁区的生鲜店对我没有意义，它送不到我的自提点。见 SERVICE_SCOPE。 |
| `serviceCommunityNos` | `string`\[\] | 是 | 覆盖哪些社区。**仅 scope=COMMUNITY 时有意义**，其余情况忽略 |
| `serviceCityCode` | `string` | 否 | 覆盖哪个城市。**仅 scope=CITY 时有意义** |
| `distance` | `number` | 否 | 距当前社区的距离（米）。由服务端按用户当前社区算好下发，端上不自己算 |
| `salesCount` | `number` | 是 | 累计订单量（评分权重之一） |
| `ratingCount` | `number` | 是 | 参与评分的评价条数 |
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
| `licenses` | `string`\[\] | 否 | 已传的资质图 |
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
| `licenses` | `string`\[\] | 否 | 已传的资质图 |
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
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `payGroupNo` | `string` | 否 | 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。 |


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
| `merchant` | [`Merchant`](#merchant) | 是 | 平台建档的商家主数据（名称/资质/评分），店主改不了 |
| `store` | [`StoreProfile`](#storeprofile) | 是 | 店主自己维护的门面内容（公告/营业时间/地址） |
| `goods` | [`Goods`](#goods)\[\] | 是 | 在售商品。首屏展示，分页靠单独的商品列表接口 |
| `favorited` | `boolean` | 是 | 我是否收藏了这家店 |


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
| `region` | `string` | 是 | 省市区 |
| `detail` | `string` | 是 | 详细地址（街道门牌） |
| `isDefault` | `boolean` | 是 | 设为默认。置 true 会把原默认地址改为 false |
| `tag` | `string` | 否 | 标签：家 / 公司 / 其他 |

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

类型：[`void`](#void)


#### POST `/mp/user/otp/send`

发送验证码　🔒

**入参**：无

**出参**（`data`）

类型：[`void`](#void)


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
| `region` | `string` | 是 | 省市区 |
| `detail` | `string` | 是 | 详细地址（街道门牌） |
| `isDefault` | `boolean` | 是 | 是否默认地址。整个地址簿至多一条为 true |
| `tag` | `string` | 否 | 标签：家 / 公司 / 其他 |

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

### BindCommunityReq

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `communityNo` | `string` | 是 | 要绑定的社区。**商品可见范围依赖它**，绑错了首页就是别的小区的货 |
| `pickupNo` | `string` | 是 | 默认自提点，须属于该社区 |

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
| `distance` | `number` | 是 | 米 |
| `pickups` | [`Pickup`](#pickup)\[\] | 是 | 本社区可用的自提点 |

### Coupon

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `couponNo` | `string` | 是 | 券单号 |
| `name` | `string` | 是 | 券名，如「满 50 减 5」 |
| `thresholdMinor` | `number` | 是 | 使用门槛（最小货币单位）。0 表示无门槛 |
| `discountMinor` | `number` | 是 | 抵扣金额（最小货币单位） |
| `expireAt` | `number` | 是 | 过期时间 |
| `received` | `boolean` | 是 | 当前用户是否已领取。列表页据此显示「领取」还是「去使用」 |
| `scopeDesc` | `string` | 是 | 适用范围文案，如「仅限张记生鲜」。展示用，实际校验在服务端 |

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

`groupBuy` 的字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `minCount` | `number` | 是 | — |
| `price` | `number` | 是 | — |

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
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo URL |
| `rating` | `number` | 是 | 综合评分，0–5，保留一位小数 |
| `verified` | `boolean` | 是 | 是否通过资质认证 |
| `breachCount` | `number` | 是 | 选定报价后不履约的次数。>0 会在报价卡上公示 —— 事后信用替代事前审核 |
| `type` | [`MerchantType`](#merchanttype) | 是 | 商家类型：平台自营 / 企业 / 个体 |
| `desc` | `string` | 是 | 店铺简介 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围 —— 邻里购物的核心约束：**商家是有服务半径的**。 隔壁区的生鲜店对我没有意义，它送不到我的自提点。见 SERVICE_SCOPE。 |
| `serviceCommunityNos` | `string`\[\] | 是 | 覆盖哪些社区。**仅 scope=COMMUNITY 时有意义**，其余情况忽略 |
| `serviceCityCode` | `string` | 否 | 覆盖哪个城市。**仅 scope=CITY 时有意义** |
| `distance` | `number` | 否 | 距当前社区的距离（米）。由服务端按用户当前社区算好下发，端上不自己算 |
| `salesCount` | `number` | 是 | 累计订单量（评分权重之一） |
| `ratingCount` | `number` | 是 | 参与评分的评价条数 |
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
| `licenses` | `string`\[\] | 否 | 已传的资质图 |
| `industry` | `string` | 否 | 申请时选的行业。驳回回填要用它 —— 换个行业可能连主体类型都得跟着换 |
| `asPickupPoint` | `boolean` | 否 | 是否愿意承接自提点（ADR-005）。 **只是意愿，不代表点已建立** —— 建点要谈服务费口径，一期由运营在通过后另行处理。 所以商家勾了这一项、通过后却还没看到履约台，是正常的中间状态而不是故障。 |

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

### MerchantCapability

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchantNo` | `string` | 是 | 商家单号 |
| `merchantName` | `string` | 是 | 商家名，展示用 |
| `invoiceCapable` | `boolean` | 是 | 能否开票 |
| `payMethods` | `string`\[\] | 是 | 该商家支持的支付方式；**空 = 未配置**（进件还没走完），不是「一种都不支持」 |
| `quotaExhausted` | `boolean` | 是 | 本期收款额度已用尽 —— 这家的货现在下不了单 |
| `quotaWouldExceed` | `boolean` | 是 | 加上本车这些货会超额 —— 还没用尽，但这一单过不去 |

### MerchantGoodsStatus

商家侧商品状态（`/biz/goods` 下发的四态）。 ⚠️ 待审在这里是 `AUDITING`（后端 `prd_goods.audit_status` 的原值）， 而  {@link  GoodsStatus }  用 `PENDING`（ops-web 的 SkuStatus 口径）—— **同一件事两个词**，词典 §11 该收敛哪一个还没定。这里如实写后端发的那个， 收敛之前不要在端上做映射：映射会让「界面显示对了」掩盖住口径还没统一。

枚举取值：

- `ON_SALE`
- `OFF_SALE`
- `AUDITING`
- `REJECTED`

### MerchantSubject

商家主体类型 —— **权威口径取通道侧**（ADR-010）。 主体类型的唯一硬约束来自支付通道：能不能进件、要什么资质、钱打到个人还是对公。 展示名反而可以随便改。让权威贴着约束走，映射就只需要一个方向。 规则（要不要执照、受不受行业白名单限制、结算账户形态）在 `sys_merchant_subject` 表里，随通道调整；**这里只管取值域**。 端上取 `GET /common/master-data`，不要在页面里写死。 <p><b>不叫 `SubjectType`</b>：那个名字在平台端已经是**风控主体** （DEVICE/MERCHANT/USER）。两个不同的概念同名，读代码的人迟早会把 一个当成另一个 —— 类型对齐守卫正是为此存在的。

枚举取值：

- `MICRO`
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
| `region` | `string` | 是 | 省市区 |
| `detail` | `string` | 是 | 详细地址（街道门牌） |
| `isDefault` | `boolean` | 是 | 设为默认。置 true 会把原默认地址改为 false |
| `tag` | `string` | 否 | 标签：家 / 公司 / 其他 |

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

### SettleAccountType

结算账户形态。个人 openid 收款 / 对公商户号收款（ADR-002 §5）

枚举取值：

- `PERSONAL_OPENID`
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

### SpecGroup

规格维度，例：{ name: "重量", options: ["约5斤", "约10斤"] }

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `name` | `string` | 是 | 规格维度名，如「重量」「包装」 |
| `options` | `string`\[\] | 是 | 该维度的可选值，如 `["约5斤", "约10斤"]` |
| `optionCodes` | `string` \| `any`\[\] | 否 | 与 options 一一对应的模板编码。来自模板的选项有值，自由输入的为空。 一期只写入不消费 —— 但不留位的话，二期做规格聚合要刷全部历史商品。 |
| `templateNo` | `string` | 否 | 该规格组来自哪个模板（便于「用的人多不多」这类平台侧统计） |

### StoreHome

门店主页数据（C-ST-01）。 ⚠️ 这是**交易页不是介绍页**：登录用户第一屏是「我买过的」，不是店招 Banner。 粮油副食的复购路径必须压到三步 —— 打开 → 常买 → 下单（ADR-004 §3.3）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|:---:|---|
| `merchant` | [`Merchant`](#merchant) | 是 | 平台建档的商家主数据（名称/资质/评分），店主改不了 |
| `store` | [`StoreProfile`](#storeprofile) | 是 | 店主自己维护的门面内容（公告/营业时间/地址） |
| `goods` | [`Goods`](#goods)\[\] | 是 | 在售商品。首屏展示，分页靠单独的商品列表接口 |
| `favorited` | `boolean` | 是 | 我是否收藏了这家店 |

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
| `name` | `string` | 是 | 店铺名 |
| `logo` | `string` | 是 | 店铺 logo URL |
| `rating` | `number` | 是 | 综合评分，0–5，保留一位小数 |
| `verified` | `boolean` | 是 | 是否通过资质认证 |
| `breachCount` | `number` | 是 | 选定报价后不履约的次数。>0 会在报价卡上公示 —— 事后信用替代事前审核 |
| `type` | [`MerchantType`](#merchanttype) | 是 | 商家类型：平台自营 / 企业 / 个体 |
| `desc` | `string` | 是 | 店铺简介 |
| `serviceScope` | [`ServiceScope`](#servicescope) | 是 | 经营范围 —— 邻里购物的核心约束：**商家是有服务半径的**。 隔壁区的生鲜店对我没有意义，它送不到我的自提点。见 SERVICE_SCOPE。 |
| `serviceCommunityNos` | `string`\[\] | 是 | 覆盖哪些社区。**仅 scope=COMMUNITY 时有意义**，其余情况忽略 |
| `serviceCityCode` | `string` | 否 | 覆盖哪个城市。**仅 scope=CITY 时有意义** |
| `distance` | `number` | 否 | 距当前社区的距离（米）。由服务端按用户当前社区算好下发，端上不自己算 |
| `salesCount` | `number` | 是 | 累计订单量（评分权重之一） |
| `ratingCount` | `number` | 是 | 参与评分的评价条数 |
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
