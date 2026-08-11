# API 清单（三端 · 供审核）

> 2026-08-06 由 `npm run gen:api-index` 生成，**请勿手改**。
> 三个来源合成：契约（前端生成的 OpenAPI，形状真源）× 后端实现扫描 × 前端调用扫描。
> 完整入参/出参 schema 见三份 OpenAPI；这里是给人审的总表。

图例：**后端** ✅ 已实现 / ⬜ 未实现 ｜ **前端** ✅ 在调 / ⬜ 未接

对照：[响应格式规范](响应格式规范.md) ｜ [三端与后端对照](三端与后端对照.md) ｜ [后端验收清单](后端验收清单.md) ｜ [项目词典](../requirements/项目词典.md)

**合计 322 个接口**：后端已实现 173（54%）· 前端在调 321

---

## C 端 `/mp/**` · c-app（消费者）

共 **64** 个接口 ｜ 后端已实现 **63**（98%）｜ 前端在调 **64**

### after-sale（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/after-sale` | 我的售后单 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/after-sale/{afterSaleNo}/escalate` | 上升平台裁决 | — | `Order` | 🔒 | ✅ | ✅ |
| POST | `/mp/after-sale/{afterSaleNo}/ship` | 填退货运单号 | — | `Order` | 🔒 | ✅ | ✅ |
| GET | `/mp/after-sale/reasons` | 售后原因清单 | — | `数组` | 🔒 | ✅ | ✅ |

### card（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/card/mine` | 我的卡包 | — | `数组` | 🔒 | ⬜ | ✅ |

### cart（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/cart` | 购物车 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/cart/add` | 加入购物车 | `CartAddReq` | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/cart/remove` | 移除商品 | `CartRemoveReq` | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/cart/update` | 修改数量 | `CartUpdateReq` | `数组` | 🔒 | ✅ | ✅ |

### community（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/community/nearby` | 附近社区与自提点 | — | `数组` | 🔒 | ✅ | ✅ |

### coupon（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/coupon` | 优惠券列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/coupon/{couponNo}/receive` | 领取优惠券 | — | `Coupon` | 🔒 | ✅ | ✅ |

### goods（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/goods` | 商品列表 | — | `object` | 🔒 | ✅ | ✅ |
| GET | `/mp/goods/{goodsNo}` | 商品详情 | — | `Goods` | 🔒 | ✅ | ✅ |
| GET | `/mp/goods/promoted` | 推荐商品（运营位） | — | `数组` | 🔒 | ✅ | ✅ |

### group-buy（8）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/group-buy` | 商家团列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-buy` | 发起商家团 | `CreateGroupBuyReq` | `GroupBuy` | 🔒 | ✅ | ✅ |
| GET | `/mp/group-buy/{groupNo}` | 商家团详情 | — | `GroupBuy` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-buy/{groupNo}/join` | 参团 | `JoinGroupBuyReq` | `GroupBuy` | 🔒 | ✅ | ✅ |
| GET | `/mp/group-buy/{groupNo}/orders` | 本团待取订单 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-buy/{groupNo}/receive` | 批次签收 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-buy/{groupNo}/verify` | 发起人核销 | — | `Order` | 🔒 | ✅ | ✅ |
| GET | `/mp/group-buy/hosted` | 我发起的团 | — | `数组` | 🔒 | ✅ | ✅ |

### group-request（6）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/group-request` | 求团列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-request` | 发起求团 | `CreateRequestReq` | `GroupRequest` | 🔒 | ✅ | ✅ |
| GET | `/mp/group-request/{requestNo}` | 求团详情 | — | `GroupRequest` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-request/{requestNo}/choose` | 发起人选定报价（锁价） | `ChooseQuoteReq` | `GroupRequest` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-request/{requestNo}/confirm` | 二次确认下单 | — | `GroupRequest` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-request/{requestNo}/interest` | +1 / 取消（意向，非订单） | — | `GroupRequest` | 🔒 | ✅ | ✅ |

### merchant（6）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/merchant` | 商家列表/搜索 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/mp/merchant/{merchantNo}` | 商家详情 | — | `Merchant` | 🔒 | ✅ | ✅ |
| POST | `/mp/merchant/apply` | 商家入驻申请 | `MerchantApplyReq` | `MerchantApplyStatus` | 🔒 | ✅ | ✅ |
| GET | `/mp/merchant/apply` | 我的入驻申请状态 | — | `MerchantApplyStatus` | 🔒 | ✅ | ✅ |
| GET | `/mp/merchant/promoted` | 推荐门店（运营位） | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/mp/merchant/visited` | 我买过的商家 | — | `数组` | 🔒 | ✅ | ✅ |

### message（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/message` | 消息列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/message/{messageNo}/read` | 标记已读 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/message/read-all` | 全部已读 | — | `数组` | 🔒 | ✅ | ✅ |

### order（8）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/mp/order` | 下单（幂等） | `CreateOrderReqBody` | `Order` | 🔒 | ✅ | ✅ |
| GET | `/mp/order` | 订单列表 | — | `object` | 🔒 | ✅ | ✅ |
| GET | `/mp/order/{orderNo}` | 订单详情 | — | `Order` | 🔒 | ✅ | ✅ |
| POST | `/mp/order/{orderNo}/after-sale` | 申请售后 | `AfterSaleReq` | `Order` | 🔒 | ✅ | ✅ |
| POST | `/mp/order/{orderNo}/cancel` | 取消订单 | — | `Order` | 🔒 | ✅ | ✅ |
| POST | `/mp/order/{orderNo}/pay` | 支付 | — | `Order` | 🔒 | ✅ | ✅ |
| POST | `/mp/order/{orderNo}/reorder` | 一键再来一单 | — | `ReorderResult` | 🔒 | ✅ | ✅ |
| POST | `/mp/order/preview` | 订单预览（金额以后端为准） | — | `OrderPreview` | 🔒 | ✅ | ✅ |

### points（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/points/account` | 积分账户 | — | `PointAccount` | 🔒 | ✅ | ✅ |
| GET | `/mp/points/deductible` | 结算页试算：本单最多可抵多少 | — | `PointsDeductible` | 🔒 | ✅ | ✅ |
| GET | `/mp/points/records` | 积分流水 | — | `数组` | 🔒 | ✅ | ✅ |

### review（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/review` | 评价列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/review` | 发表评价 | `CreateReviewReq` | `Review` | 🔒 | ✅ | ✅ |
| POST | `/mp/review/{reviewNo}/like` | 点赞/取消 | — | `Review` | 🔒 | ✅ | ✅ |

### store（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/store/{merchantNo}` | 门店主页 | — | `StoreHome` | 🔒 | ✅ | ✅ |
| POST | `/mp/store/{merchantNo}/favorite` | 收藏本店 | — | `object` | 🔒 | ✅ | ✅ |
| GET | `/mp/store/{merchantNo}/frequent` | 常买清单 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/mp/store/mine` | 我的常去店 | — | `数组` | 🔒 | ✅ | ✅ |

### user（8）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/user/address` | 地址列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/user/address` | 新增/编辑地址 | `SaveAddressReq` | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/user/address/{addressId}/archive` | 删除地址（软删除） | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/user/address/{addressId}/default` | 设为默认地址 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/user/community` | 绑定社区自提点 | `BindCommunityReq` | `User` | 🔒 | ✅ | ✅ |
| POST | `/mp/user/login` | 登录建户 | `LoginReqBody` | `LoginResp` | 🔒 | ✅ | ✅ |
| POST | `/mp/user/otp/send` | 发送验证码 | — | `void` | 🔒 | ✅ | ✅ |
| GET | `/mp/user/profile` | 我的资料 | — | `User` | 🔒 | ✅ | ✅ |

## B 端 `/biz/**` · b-app（商家）

共 **70** 个接口 ｜ 后端已实现 **67**（96%）｜ 前端在调 **70**

### after-sale（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/after-sale` | 待处理售后 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/after-sale/{afterSaleNo}/approve` | 同意售后 | `HandleAfterSaleReq` | `Order` | 🔒 | ✅ | ✅ |
| POST | `/biz/after-sale/{afterSaleNo}/receive` | 确认收到退货 | — | `Order` | 🔒 | ✅ | ✅ |
| POST | `/biz/after-sale/{afterSaleNo}/reject` | 驳回售后 | `HandleAfterSaleReq` | `Order` | 🔒 | ✅ | ✅ |

### auth（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/auth/login` | 商家登录 | `MerchantLoginReqBody` | `MerchantLoginResp` | 🔒 | ✅ | ✅ |
| POST | `/biz/auth/otp/send` | 发送验证码 | — | `void` | 🔒 | ✅ | ✅ |
| POST | `/biz/auth/staff-login` | 员工登录 | `StaffLoginReq` | `MerchantLoginResp` | 🔒 | ✅ | ✅ |

### campaign（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/campaign` | 营销活动列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/campaign` | 新建/编辑活动 | `SaveCampaignReqBody` | `MarketingCampaign` | 🔒 | ✅ | ✅ |
| POST | `/biz/campaign/{campaignNo}/toggle` | 活动启停 | `ToggleCampaignReq` | `MarketingCampaign` | 🔒 | ✅ | ✅ |

### category（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/category/tree` | 类目树（选类目） | — | `数组` | 🔒 | ✅ | ✅ |

### communities（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/communities` | 可选社区（设经营范围用） | — | `数组` | 🔒 | ✅ | ✅ |

### customers（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/customers` | 客户与复购 | — | `数组` | 🔒 | ✅ | ✅ |

### dashboard（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/dashboard/stats` | 经营数据 | — | `MerchantStats` | 🔒 | ✅ | ✅ |
| GET | `/biz/dashboard/todo` | 工作台待办 | — | `MerchantTodo` | 🔒 | ✅ | ✅ |

### delivery（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/delivery/rule` | 自送规则 | — | `DeliveryRule` | 🔒 | ✅ | ✅ |
| POST | `/biz/delivery/rule` | 保存自送规则 | `SaveDeliveryRuleReqBody` | `DeliveryRule` | 🔒 | ✅ | ✅ |

### goods（7）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/goods` | 商品列表 | — | `object` | 🔒 | ✅ | ✅ |
| GET | `/biz/goods/{goodsNo}` | 商品详情 | — | `Goods` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/{goodsNo}/stock` | 改库存 | `SaveStockReq` | `Goods` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/{goodsNo}/store-stock` | 改当前门店库存 | — | `Goods` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/{goodsNo}/toggle` | 上下架 | `ToggleGoodsReq` | `Goods` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/recognize` | 拍照识别商品 | `RecognizeGoodsReq` | `GoodsGuess` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/save` | 新建/编辑商品 | `SaveGoodsReqBody` | `Goods` | 🔒 | ✅ | ✅ |

### group-request（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/group-request/{requestNo}/quote` | 报价 | `QuoteReq` | `GroupRequest` | 🔒 | ✅ | ✅ |
| GET | `/biz/group-request/pool` | 可报价需求单 | — | `数组` | 🔒 | ✅ | ✅ |

### groups（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/groups` | 我的商家团 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/groups` | 开团 | `CreateGroupReq` | `GroupBuy` | 🔒 | ✅ | ✅ |

### merchant（6）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/merchant/apply` | 提交入驻申请 | `MerchantApplyReqBody` | `MerchantProfile` | 🔒 | ✅ | ✅ |
| GET | `/biz/merchant/apply` | 上次入驻申请 | — | `MerchantApplyReq` | 🔒 | ✅ | ✅ |
| GET | `/biz/merchant/payment` | 收款进件状态 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/merchant/payment` | 补交资料并提交进件 | `SubmitPaymentReq` | `PaymentApplyment` | 🔒 | ✅ | ✅ |
| POST | `/biz/merchant/payment/{payChannel}/refresh` | 回查进件结果 | — | `PaymentApplyment` | 🔒 | ✅ | ✅ |
| GET | `/biz/merchant/profile` | 商家资料 | — | `MerchantProfile` | 🔒 | ✅ | ✅ |

### order（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/order` | 订单列表 | — | `object` | 🔒 | ✅ | ✅ |
| GET | `/biz/order/{orderNo}` | 订单详情 | — | `Order` | 🔒 | ⬜ | ✅ |
| POST | `/biz/order/{orderNo}/delivered` | 自送已送达 | — | `Order` | 🔒 | ⬜ | ✅ |
| POST | `/biz/order/{orderNo}/ship` | 快递发货 | `ShipReq` | `Order` | 🔒 | ⬜ | ✅ |

### pickup（7）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/pickup/{orderNo}/report` | 破损短少上报 | `ReportShortageReq` | `Order` | 🔒 | ✅ | ✅ |
| POST | `/biz/pickup/arrived` | 标记到货 | `MarkArrivedReq` | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/pickup/orders` | 本自提点订单 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/pickup/overview` | 自提点履约总览 | — | `PickupOverview` | 🔒 | ✅ | ✅ |
| GET | `/biz/pickup/picking` | 分拣单 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/pickup/verify` | 核销自提码 | `VerifyReq` | `Order` | 🔒 | ✅ | ✅ |
| POST | `/biz/pickup/verify/batch` | 批量核销 | `VerifyBatchReq` | `VerifyBatchResult` | 🔒 | ✅ | ✅ |

### points（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/points/account` | 本期发分服务费与开关状态 | — | `MerchantPointAccount` | 🔒 | ✅ | ✅ |
| GET | `/biz/points/records` | 发分服务费明细（按单） | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/points/toggle` | 开/关本店积分 | — | `MerchantPointAccount` | 🔒 | ✅ | ✅ |

### review（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/review` | 评价列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/review/{reviewNo}/appeal` | 申诉差评 | `AppealReviewReq` | `Review` | 🔒 | ✅ | ✅ |
| POST | `/biz/review/{reviewNo}/reply` | 回复评价 | `ReplyReviewReq` | `Review` | 🔒 | ✅ | ✅ |

### settle（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/settle/bills` | 结算单列表 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/settle/rate-card` | 费率卡 | — | `RateCard` | 🔒 | ✅ | ✅ |

### spec-templates（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/spec-templates` | 规格模板 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/spec-templates` | 存为常用规格 | `SaveSpecTemplateReq` | `SpecTemplate` | 🔒 | ✅ | ✅ |

### staff（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/staff` | 员工列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/staff` | 加员工 | `AddStaffReq` | `MerchantStaff` | 🔒 | ✅ | ✅ |
| POST | `/biz/staff/{mchAccountNo}/status` | 停用/启用员工 | `SetActiveReq` | `MerchantStaff` | 🔒 | ✅ | ✅ |
| POST | `/biz/staff/{mchAccountNo}/store` | 授权到店 | `GrantStoreReq` | `MerchantStaff` | 🔒 | ✅ | ✅ |

### store（10）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/store` | 店铺门面 | — | `StoreProfile` | 🔒 | ✅ | ✅ |
| POST | `/biz/store` | 保存店铺门面 | `SaveStoreReqBody` | `StoreProfile` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/{storeNo}/default` | 设为默认店 | — | `Store` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/{storeNo}/payment` | 换门店收款号 | `SetStorePaymentReq` | `Store` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/{storeNo}/rename` | 改门店名与地址 | `StoreEditReq` | `Store` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/{storeNo}/status` | 停用/启用门店 | `SetActiveReq` | `Store` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/create` | 新建门店 | `StoreEditReq` | `Store` | 🔒 | ✅ | ✅ |
| GET | `/biz/store/list` | 我的门店 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/store/qrcode` | 店铺码 | — | `StoreQrcode` | 🔒 | ✅ | ✅ |
| GET | `/biz/store/share-kit` | 分享素材 | — | `ShareKit` | 🔒 | ✅ | ✅ |

### upload（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/upload/image` | 上传商品图 | `UploadImageReq` | `object` | 🔒 | ✅ | ✅ |

## 平台端 `/ops/**` · ops-web（运营）

共 **188** 个接口 ｜ 后端已实现 **43**（23%）｜ 前端在调 **187**

### aftersale（5）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/after-sales` | listAfterSales | — | `object` | — | ✅ | ✅ |
| POST | `/ops/after-sales/{asNo}/decide` | 平台介入裁决（P-6.1.3 + 6.1.4） | — | `AfterSale` | — | ⬜ | ✅ |
| POST | `/ops/after-sales/{no}/status` | 状态推进，非法迁移抛错（驳回不是终点，用户可上升平台） | — | `AfterSale` | — | ⬜ | ✅ |
| GET | `/ops/after-sales/fast-refund-rule` | getFastRefundRule | — | `FastRefundRule` | — | ✅ | ✅ |
| POST | `/ops/after-sales/fast-refund-rule` | 极速退阈值（P-6.1.2）：金额上限 > 0、时限 ≥ 1 小时 | — | `FastRefundRule` | — | ✅ | ✅ |

### community（12）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/communities` | listCommunities | — | `object` | — | ✅ | ✅ |
| POST | `/ops/communities/{no}/archive` | archiveCommunity | — | `Community` | — | ⬜ | ✅ |
| POST | `/ops/communities/{no}/fence` | 覆盖围栏半径，米（P-2.1.3） | — | `Community` | — | ⬜ | ✅ |
| POST | `/ops/communities/{no}/open` | 开城/停城（P-2.1.2） | — | `Community` | — | ⬜ | ✅ |
| POST | `/ops/communities/{no}/unarchive` | unarchiveCommunity | — | `Community` | — | ⬜ | ✅ |
| GET | `/ops/pickups` | listPickups | — | `object` | — | ✅ | ✅ |
| POST | `/ops/pickups` | 建自提点 | — | `PickupPoint` | — | ✅ | ✅ |
| POST | `/ops/pickups/{no}/archive` | archivePickup | — | `PickupPoint` | — | ⬜ | ✅ |
| POST | `/ops/pickups/{no}/service-fee` | 履约服务费费率，万分比（P-2.2.4） | — | `PickupPoint` | — | ⬜ | ✅ |
| POST | `/ops/pickups/{no}/status` | 启停与迁移（P-2.2.2），非法迁移抛错 | — | `PickupPoint` | — | ⬜ | ✅ |
| POST | `/ops/pickups/{no}/unarchive` | unarchivePickup | — | `PickupPoint` | — | ⬜ | ✅ |
| GET | `/ops/pickups/risky` | 疑似职业化的临时自提点（P-2.2.5）：近 30 天承接次数 ≥ 阈值 | — | `object` | — | ✅ | ✅ |

### content（12）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/contents/posts` | listPosts | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/contents/posts/{postNo}/decide` | 裁决一条种草内容 | — | `Post` | — | ⬜ | ✅ |
| POST | `/ops/contents/posts/batch-pass` | 批量通过 | — | `数组` | — | ⬜ | ✅ |
| GET | `/ops/contents/questions` | listQuestions | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/contents/questions/{questionNo}/answer` | 回答 | — | `Question` | — | ⬜ | ✅ |
| POST | `/ops/contents/questions/{questionNo}/hide` | 隐藏提问（如导流、辱骂） | — | `Question` | — | ⬜ | ✅ |
| GET | `/ops/contents/rankings` | listRankings | — | `数组` | — | ⬜ | ✅ |
| POST | `/ops/contents/rankings` | 保存榜单 | — | `Ranking` | — | ⬜ | ✅ |
| POST | `/ops/contents/rankings/{rankNo}/enabled` | setRankingEnabled | — | `Ranking` | — | ⬜ | ✅ |
| GET | `/ops/materials` | listMaterials | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/materials` | 保存素材（P-15.1.1–15.1.4） | — | `Material` | — | ⬜ | ✅ |
| POST | `/ops/materials/{no}/published` | setMaterialPublished | — | `Material` | — | ⬜ | ✅ |

### dashboard（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/ops/auth/login` | 登录 | — | `LoginResp` | — | ✅ | ⬜ |
| GET | `/ops/dashboard/funnel` | getAcquisitionFunnel | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/dashboard/kpi` | getDashboardKpi | — | `DashboardKpi` | — | ✅ | ✅ |
| GET | `/ops/dashboard/trend` | getDashboardTrend | — | `数组` | — | ✅ | ✅ |

### finance（15）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/fee-rule` | getFeeRule | — | `FeeRule` | — | ⬜ | ✅ |
| POST | `/ops/fee-rule` | 费率配置（P-12.1.7 / 12.1.8 / 12.1.4） | — | `FeeRule` | — | ⬜ | ✅ |
| GET | `/ops/finance/invoices` | listInvoiceRequests | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/finance/invoices/{invoiceNo}/issue` | 开票 | — | `InvoiceRequest` | — | ⬜ | ✅ |
| POST | `/ops/finance/invoices/{invoiceNo}/reject` | rejectInvoice | — | `InvoiceRequest` | — | ⬜ | ✅ |
| GET | `/ops/finance/tax-rule` | getTaxRule | — | `TaxRule` | — | ⬜ | ✅ |
| PUT | `/ops/finance/tax-rule` | 个税代扣规则 | — | `TaxRule` | — | ⬜ | ✅ |
| GET | `/ops/finance/withdrawals` | listWithdrawals | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/finance/withdrawals/{withdrawNo}/decide` | 审批一笔提现 | — | `Withdrawal` | — | ⬜ | ✅ |
| GET | `/ops/refund-split-backs` | 待回退分账的售后单（P-12.1.5 / E4）：售后裁决打的 `refundSplitPending` 标记 | — | `数组` | — | ⬜ | ✅ |
| POST | `/ops/refund-split-backs/{asNo}/execute` | 执行退款回退分账，**执行后清除该售后单的标记**，否则队列永远消不掉 | — | `AfterSale` | — | ⬜ | ✅ |
| GET | `/ops/settlements` | listSettlements | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/settlements/{no}/freeze-back` | 超时兜底（P-12.1.4）：冻结超过 freezeDays 仍未成功的，解冻回平台 | — | `Settlement` | — | ⬜ | ✅ |
| POST | `/ops/settlements/{no}/split` | 下发分账指令（P-12.1.3） | — | `Settlement` | — | ⬜ | ✅ |
| GET | `/ops/split-records` | listSplitRecords | — | `object` | — | ⬜ | ✅ |

### fulfillment（15）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/freight-templates` | `showArchived` 为真时连归档的一起返回（G1：归档不是删除，得看得见） | — | `数组` | — | ⬜ | ✅ |
| POST | `/ops/freight-templates` | 新建/保存运费模板（含超区规则） | — | `FreightTemplate` | — | ⬜ | ✅ |
| POST | `/ops/freight-templates/{templateNo}/archive` | 归档模板（G1：软删除，不是删除） | — | `FreightTemplate` | — | ⬜ | ✅ |
| POST | `/ops/freight-templates/{templateNo}/unarchive` | unarchiveFreightTemplate | — | `FreightTemplate` | — | ⬜ | ✅ |
| GET | `/ops/fulfillment/batches` | listArrivalBatches | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/fulfillment/batches/{batchNo}/status` | 批次推进（计划→已发车→已到货→已签收），跳步抛错 | — | `ArrivalBatch` | — | ⬜ | ✅ |
| GET | `/ops/fulfillment/carriers` | listCarriers | — | `数组` | — | ⬜ | ✅ |
| PUT | `/ops/fulfillment/carriers/{carrier}` | 保存一家运力的接入配置 | — | `CarrierConfig` | — | ⬜ | ✅ |
| POST | `/ops/fulfillment/carriers/{carrier}/enabled` | 启停一家运力 | — | `CarrierConfig` | — | ⬜ | ✅ |
| GET | `/ops/fulfillment/overdue-rule` | getOverdueRule | — | `OverdueRule` | — | ⬜ | ✅ |
| POST | `/ops/fulfillment/overdue-rule` | 逾期规则（P-5.1.4） | — | `OverdueRule` | — | ⬜ | ✅ |
| GET | `/ops/fulfillment/redeem` | 核销监控与逾期看板（P-5.1.3） | — | `数组` | — | ⬜ | ✅ |
| GET | `/ops/fulfillment/sorting` | 按自提点汇总分拣（P-5.1.2） | — | `数组` | — | ⬜ | ✅ |
| GET | `/ops/shipments` | listShipments | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/shipments/{shipmentNo}/waybill` | 换运单号（录错了、或承运商重新出单） | — | `Shipment` | — | ⬜ | ✅ |

### group（8）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/demands` | listDemands | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/demands/{demandNo}/quotes` | 人肉指派商家报价（P-8.2.2，初期靠运营撮合） | — | `Quote` | — | ⬜ | ✅ |
| GET | `/ops/groups` | listGroupCampaigns | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/groups/{no}/audit` | 团模板审核（P-8.1.1）：起团人数 ≥2、团购价必须低于原价 | — | `GroupCampaign` | — | ⬜ | ✅ |
| POST | `/ops/groups/{no}/status` | setGroupStatus | — | `GroupCampaign` | — | ⬜ | ✅ |
| GET | `/ops/quotes` | listQuotes | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/quotes/{no}/breach` | 标记毁约（P-8.2.5）：累计进商家信用档案 | — | `Quote` | — | ⬜ | ✅ |
| POST | `/ops/quotes/{no}/price` | 改价（P-8.2.4）：留痕并公示，超过阈值禁止再改 | — | `Quote` | — | ⬜ | ✅ |

### growth（6）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/attribution-rule` | getAttributionRule | — | `AttributionRule` | — | ⬜ | ✅ |
| POST | `/ops/attribution-rule` | 归因规则（P-9.1.1/9.1.2/9.1.5） | — | `AttributionRule` | — | ⬜ | ✅ |
| GET | `/ops/attribution-traces` | 归因链路查询与审计（P-9.1.3） | — | `object` | — | ⬜ | ✅ |
| GET | `/ops/fission-campaigns` | listFissionCampaigns | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/fission-campaigns` | 邀请有礼（P-9.2.1） | — | `FissionCampaign` | — | ⬜ | ✅ |
| POST | `/ops/fission-campaigns/{no}/enabled` | setFissionEnabled | — | `FissionCampaign` | — | ⬜ | ✅ |

### iam（7）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/audit-logs` | 审计日志（P-1.1.4） | — | `object` | — | ⬜ | ✅ |
| GET | `/ops/roles` | listRoles | — | `数组` | — | ⬜ | ✅ |
| POST | `/ops/roles/{role}/perms` | 改角色权限 | — | `RoleDef` | — | ⬜ | ✅ |
| GET | `/ops/staffs` | listStaffs | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/staffs/{no}/enabled` | 停用/启用（软删除语义，不删账号 —— 审计要能追溯到人） | — | `Staff` | — | ⬜ | ✅ |
| POST | `/ops/staffs/{no}/role` | 改角色 | — | `Staff` | — | ⬜ | ✅ |
| POST | `/ops/staffs/{no}/scope` | 数据域授权（P-1.1.3） | — | `Staff` | — | ⬜ | ✅ |

### marketing（21）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/campaigns` | listCampaigns | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/campaigns` | 保存活动（P-7.2） | — | `Campaign` | — | ⬜ | ✅ |
| POST | `/ops/campaigns/{no}/archive` | archiveCampaign | — | `Campaign` | — | ⬜ | ✅ |
| POST | `/ops/campaigns/{no}/unarchive` | unarchiveCampaign | — | `Campaign` | — | ⬜ | ✅ |
| GET | `/ops/content-slots` | listContentSlots | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/content-slots/{no}/archive` | archiveSlot | — | `ContentSlot` | — | ⬜ | ✅ |
| POST | `/ops/content-slots/{no}/enabled` | 上下线开关（P-7.3.5） | — | `ContentSlot` | — | ⬜ | ✅ |
| POST | `/ops/content-slots/{no}/schedule` | 定时上下线：下线必须晚于上线 | — | `ContentSlot` | — | ⬜ | ✅ |
| POST | `/ops/content-slots/{no}/unarchive` | unarchiveSlot | — | `ContentSlot` | — | ⬜ | ✅ |
| GET | `/ops/coupon-issues` | listCouponIssues | — | `object` | — | ⬜ | ✅ |
| GET | `/ops/coupons` | listCoupons | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/coupons/{couponNo}/issue` | 发券（P-7.1.2） | — | `CouponIssue` | — | ⬜ | ✅ |
| POST | `/ops/coupons/{no}/archive` | archiveCoupon | — | `Coupon` | — | ⬜ | ✅ |
| POST | `/ops/coupons/{no}/budget` | 调预算（P-7.1.3） | — | `Coupon` | — | ⬜ | ✅ |
| POST | `/ops/coupons/{no}/status` | 状态推进（草稿→启用⇄暂停→结束），非法迁移抛错 | — | `Coupon` | — | ⬜ | ✅ |
| POST | `/ops/coupons/{no}/unarchive` | unarchiveCoupon | — | `Coupon` | — | ⬜ | ✅ |
| GET | `/ops/marketing/member-cards` | listMemberCards | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/marketing/member-cards` | 保存会员卡 | — | `MemberCard` | — | ⬜ | ✅ |
| POST | `/ops/marketing/member-cards/{cardNo}/archive` | 归档 | — | `MemberCard` | — | ⬜ | ✅ |
| POST | `/ops/marketing/member-cards/{cardNo}/status` | 状态推进（草稿→启用⇄暂停→停售），非法迁移抛错 | — | `MemberCard` | — | ⬜ | ✅ |
| POST | `/ops/marketing/member-cards/{cardNo}/unarchive` | unarchiveMemberCard | — | `MemberCard` | — | ⬜ | ✅ |

### merchant（13）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/ops/merchant/apply/{applyNo}/accept` | 受理：告诉商家「有人在看了」 | — | `object` | — | ✅ | ✅ |
| POST | `/ops/merchant/apply/{applyNo}/audit` | 审核 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/merchant/apply/search` | 入驻申请检索 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/merchants` | listMerchants | — | `object` | — | ✅ | ✅ |
| GET | `/ops/merchants/{merchantNo}` | getMerchant | — | `Merchant` | — | ✅ | ✅ |
| POST | `/ops/merchants/{merchantNo}/archive` | archiveMerchant | — | `Merchant` | — | ⬜ | ✅ |
| PUT | `/ops/merchants/{merchantNo}/auth-codes` | 改一个商家的类目授权范围 | — | `Merchant` | — | ✅ | ✅ |
| POST | `/ops/merchants/{merchantNo}/status` | 审核推进 | — | `Merchant` | — | ✅ | ✅ |
| POST | `/ops/merchants/{merchantNo}/unarchive` | unarchiveMerchant | — | `Merchant` | — | ⬜ | ✅ |
| POST | `/ops/merchants/{merchantNo}/verified` | 认证标授予/撤销（P-11.1.2） | — | `Merchant` | — | ✅ | ✅ |
| POST | `/ops/merchants/{merchantNo}/violations` | 记一条违规并执行处置 | — | `Violation` | — | ✅ | ✅ |
| GET | `/ops/merchants/auth-codes` | 授权码目录 | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/merchants/violations` | listViolations | — | `数组` | — | ✅ | ✅ |

### message（14）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/faqs` | listFaqs | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/faqs` | 帮助中心（P-14.2.4） | — | `FaqEntry` | — | ⬜ | ✅ |
| POST | `/ops/faqs/{no}/published` | setFaqPublished | — | `FaqEntry` | — | ⬜ | ✅ |
| GET | `/ops/msg-templates` | listMsgTemplates | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/msg-templates/{no}/enabled` | setTemplateEnabled | — | `MsgTemplate` | — | ⬜ | ✅ |
| GET | `/ops/notify-quota` | getNotifyQuota | — | `NotifyQuota` | — | ⬜ | ✅ |
| POST | `/ops/notify-quota` | 触达频控（P-14.1.4） | — | `NotifyQuota` | — | ⬜ | ✅ |
| GET | `/ops/push-tasks` | listPushTasks | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/push-tasks/{no}/cancel` | cancelPushTask | — | `PushTask` | — | ⬜ | ✅ |
| POST | `/ops/push-tasks/{no}/send` | 发送推送（P-14.1.2） | — | `PushTask` | — | ⬜ | ✅ |
| GET | `/ops/tickets` | listTickets | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/tickets/{no}/assign` | 分派工单（P-14.2.1） | — | `Ticket` | — | ⬜ | ✅ |
| POST | `/ops/tickets/{no}/close` | closeTicket | — | `Ticket` | — | ⬜ | ✅ |
| POST | `/ops/tickets/{no}/proxy-actions` | 记录代客操作（P-14.2.3）：谁、对什么、做了什么 | — | `Ticket` | — | ⬜ | ✅ |

### order（8）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/orders` | listOrders | — | `object` | — | ✅ | ✅ |
| GET | `/ops/orders/{orderNo}` | getOrder | — | `Order` | — | ✅ | ✅ |
| POST | `/ops/orders/{orderNo}/intervene` | 人工把订单推到另一个状态 | — | `Order` | — | ✅ | ✅ |
| GET | `/ops/orders/{orderNo}/interventions` | 某单的人工干预历史 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/orders/{orderNo}/proxy-cancel` | 代客取消 | — | `Order` | — | ✅ | ✅ |
| GET | `/ops/orders/exceptions` | 异常单队列 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/orders/parent/{parentNo}` | 同一次结算拆出的全部子订单（E3 按商家拆单，详情抽屉要能看到兄弟单） | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/orders/proxy` | 代客下单（客服电话代下） | — | `Order` | — | ⬜ | ✅ |

### payment（5）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/payments/close-rule` | getCloseRule | — | `CloseRule` | — | ⬜ | ✅ |
| PUT | `/ops/payments/close-rule` | 关单策略（P-4.2.3） | — | `CloseRule` | — | ⬜ | ✅ |
| GET | `/ops/payments/recon-diffs` | 对账差异列表（P-4.2.1） | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/payments/recon-diffs/{diffNo}/ignore` | 忽略一条差异（如渠道手续费导致的分位差） | — | `ReconDiff` | — | ⬜ | ✅ |
| POST | `/ops/payments/recon-diffs/{diffNo}/resolve` | 处置一条差异（P-4.2.1 / 4.2.2） | — | `ReconDiff` | — | ⬜ | ✅ |

### product（11）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/categories` | 类目树：一次给全量（三级树总量有限，前端自己组树比逐层拉更快） | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/categories` | saveCategory | — | `Category` | — | ✅ | ✅ |
| POST | `/ops/categories/{no}/archive` | 有子类目或有在售商品的类目不能归档 —— 归档后 C 端类目树会断枝 | — | `Category` | — | ⬜ | ✅ |
| POST | `/ops/categories/{no}/unarchive` | unarchiveCategory | — | `Category` | — | ⬜ | ✅ |
| POST | `/ops/goods/{goodsNo}/audit` | 审核商品 | — | `GoodsAudit` | — | ✅ | ✅ |
| GET | `/ops/goods/audit-queue` | 待审队列 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/skus` | listSkus | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/skus/{no}/audit` | 商品审核（P-3.2.2） | — | `Sku` | — | ⬜ | ✅ |
| POST | `/ops/skus/{no}/force-off` | 强制下架（P-3.2.3）：必须带原因，原样进商家 B 端 | — | `Sku` | — | ⬜ | ✅ |
| POST | `/ops/skus/{no}/presale` | 预售额度与截单时间（P-3.3.1 / 3.3.2）：截单必须早于到货 | — | `Sku` | — | ⬜ | ✅ |
| GET | `/ops/skus/oversell` | 超卖告警（P-3.3.3）：已售 > 预售额度 | — | `数组` | — | ⬜ | ✅ |

### review（6）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/review-appeals` | listReviewAppeals | — | `object` | — | ✅ | ✅ |
| POST | `/ops/review-appeals/{no}/decide` | 申诉裁决（P-13.1.3） | — | `ReviewAppeal` | — | ⬜ | ✅ |
| GET | `/ops/review-score-config` | getScoreConfig | — | `ScoreConfig` | — | ✅ | ✅ |
| POST | `/ops/review-score-config` | 评分算法参数（P-13.1.4） | — | `ScoreConfig` | — | ✅ | ✅ |
| GET | `/ops/reviews` | listReviews | — | `object` | — | ✅ | ✅ |
| POST | `/ops/reviews/{no}/decide` | 审核裁决（P-13.1.1/13.1.2） | — | `Review` | — | ⬜ | ✅ |

### risk（7）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/blacklists` | listBlacklists | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/blacklists` | 拉黑（P-16.2.4） | — | `BlacklistEntry` | — | ⬜ | ✅ |
| POST | `/ops/blacklists/{no}/appeal` | 解禁申诉裁决：接受则解除拉黑，两种结论都要写说明 | — | `BlacklistEntry` | — | ⬜ | ✅ |
| GET | `/ops/risk-events` | listRiskEvents | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/risk-events/{no}/decide` | 事件处置（P-16.2.1–3）：确认或排除，都要写结论 | — | `RiskEvent` | — | ⬜ | ✅ |
| GET | `/ops/risk-rules` | listRiskRules | — | `数组` | — | ⬜ | ✅ |
| POST | `/ops/risk-rules/{type}` | 拦截规则（P-16.2.5） | — | `RiskRule` | — | ⬜ | ✅ |

### store（7）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/stores/acquisition` | 门店获客效果（P-10.1.4） | — | `object` | — | ⬜ | ✅ |
| GET | `/ops/stores/audits` | listStoreAudits | — | `object` | — | ✅ | ✅ |
| POST | `/ops/stores/audits/{auditNo}/decide` | 审核裁决（P-10.1.2） | — | `StorePageAudit` | — | ✅ | ✅ |
| GET | `/ops/stores/qrcodes` | 店铺码（P-10.1.3），供 BD 批量导出去印刷 | — | `object` | — | ⬜ | ✅ |
| GET | `/ops/stores/templates` | listStoreTemplates | — | `数组` | — | ⬜ | ✅ |
| POST | `/ops/stores/templates` | 新建/保存模板 | — | `StoreTemplate` | — | ⬜ | ✅ |
| POST | `/ops/stores/templates/{templateNo}/enabled` | 启用/停用模板 | — | `StoreTemplate` | — | ⬜ | ✅ |

### system（12）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/appearance` | getAppearance | — | `AppearanceConfig` | — | ⬜ | ✅ |
| POST | `/ops/appearance` | 皮肤下发（P-17.1.1 / C-TH-05） | — | `AppearanceConfig` | — | ⬜ | ✅ |
| GET | `/ops/feature-flags` | listFeatureFlags | — | `数组` | — | ⬜ | ✅ |
| POST | `/ops/feature-flags/{key}` | 开关与灰度（P-17.1.5） | — | `FeatureFlag` | — | ⬜ | ✅ |
| GET | `/ops/industries` | listIndustries | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/industries/{industry}/enabled` | 停用后入驻表单里不再出现这个行业 | — | `Industry` | — | ✅ | ✅ |
| POST | `/ops/industries/{industry}/micro-allowed` | 改某通道的小微白名单 | — | `Industry` | — | ✅ | ✅ |
| POST | `/ops/industries/{industry}/points-forced` | 强制开启积分：商家不可自行关闭 */ | — | `Industry` | — | ✅ | ✅ |
| GET | `/ops/markets` | listMarkets | — | `数组` | — | ⬜ | ✅ |
| POST | `/ops/markets/{code}` | 市场与汇率（P-17.1.3） | — | `MarketConfig` | — | ⬜ | ✅ |
| GET | `/ops/rule-texts` | getRuleTexts | — | `RuleTexts` | — | ⬜ | ✅ |
| POST | `/ops/rule-texts` | 规则文案（P-17.1.4） | — | `RuleTexts` | — | ⬜ | ✅ |
