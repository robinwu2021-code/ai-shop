# API 清单（三端 · 供审核）

> 2026-08-06 由 `npm run gen:api-index` 生成，**请勿手改**。
> 三个来源合成：契约（前端生成的 OpenAPI，形状真源）× 后端实现扫描 × 前端调用扫描。
> 完整入参/出参 schema 见三份 OpenAPI；这里是给人审的总表。

图例：**后端** ✅ 已实现 / ⬜ 未实现 ｜ **前端** ✅ 在调 / ⬜ 未接

对照：[响应格式规范](响应格式规范.md) ｜ [三端与后端对照](三端与后端对照.md) ｜ [后端验收清单](后端验收清单.md) ｜ [项目词典](../requirements/项目词典.md)

**合计 654 个接口**：后端已实现 535（82%）· 前端在调 592

---

## C 端 `/mp/**` · c-app（消费者）

共 **83** 个接口 ｜ 后端已实现 **82**（99%）｜ 前端在调 **83**

### after-sale（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/after-sale` | 我的售后单 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/after-sale/{afterSaleNo}/escalate` | 上升平台裁决 | — | `Order` | 🔒 | ✅ | ✅ |
| POST | `/mp/after-sale/{afterSaleNo}/ship` | 填退货运单号 | — | `Order` | 🔒 | ✅ | ✅ |
| GET | `/mp/after-sale/reasons` | 售后原因清单 | — | `数组` | — | ✅ | ✅ |

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

### community（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/community` | 全部已开通社区（附近为空时的出路） | — | `数组` | — | ✅ | ✅ |
| GET | `/mp/community/nearby` | 附近社区与自提点 | — | `数组` | — | ✅ | ✅ |
| GET | `/mp/community/regions` | 有已开通社区的区域清单 | — | `数组` | — | ✅ | ✅ |

### coupon（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/coupon` | 优惠券列表 | — | `数组` | — | ✅ | ✅ |
| POST | `/mp/coupon/{couponNo}/receive` | 领取优惠券 | — | `Coupon` | 🔒 | ✅ | ✅ |

### goods（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/goods` | 商品列表 | — | `object` | — | ✅ | ✅ |
| GET | `/mp/goods/{goodsNo}` | 商品详情 | — | `Goods` | — | ✅ | ✅ |
| GET | `/mp/goods/promoted` | 推荐商品（运营位） | — | `数组` | — | ✅ | ✅ |

### group-buy（8）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/group-buy` | 商家团列表 | — | `数组` | — | ✅ | ✅ |
| POST | `/mp/group-buy` | 发起商家团 | `CreateGroupBuyReq` | `GroupBuy` | 🔒 | ✅ | ✅ |
| GET | `/mp/group-buy/{groupNo}` | 商家团详情 | — | `GroupBuy` | — | ✅ | ✅ |
| POST | `/mp/group-buy/{groupNo}/join` | 参团 | `JoinGroupBuyReq` | `GroupBuy` | 🔒 | ✅ | ✅ |
| GET | `/mp/group-buy/{groupNo}/orders` | 本团待取订单 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-buy/{groupNo}/receive` | 批次签收 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-buy/{groupNo}/verify` | 发起人核销 | — | `Order` | 🔒 | ✅ | ✅ |
| GET | `/mp/group-buy/hosted` | 我发起的团 | — | `数组` | 🔒 | ✅ | ✅ |

### group-request（6）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/group-request` | 求团列表 | — | `数组` | — | ✅ | ✅ |
| POST | `/mp/group-request` | 发起求团 | `CreateRequestReq` | `GroupRequest` | 🔒 | ✅ | ✅ |
| GET | `/mp/group-request/{requestNo}` | 求团详情 | — | `GroupRequest` | — | ✅ | ✅ |
| POST | `/mp/group-request/{requestNo}/choose` | 发起人选定报价（锁价） | `ChooseQuoteReq` | `GroupRequest` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-request/{requestNo}/confirm` | 二次确认下单 | — | `GroupRequest` | 🔒 | ✅ | ✅ |
| POST | `/mp/group-request/{requestNo}/interest` | +1 / 取消（意向，非订单） | — | `GroupRequest` | 🔒 | ✅ | ✅ |

### invoice（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/mp/invoice/apply` | 申请开票 | — | `InvoiceRequest` | 🔒 | ✅ | ✅ |
| GET | `/mp/invoice/mine` | 我的开票申请 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/mp/invoice/order/{orderNo}` | 某单的开票状态 | — | `InvoiceRequest` | 🔒 | ✅ | ✅ |

### merchant（6）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/merchant` | 商家列表/搜索 | — | `数组` | — | ✅ | ✅ |
| GET | `/mp/merchant/{merchantNo}` | 商家详情 | — | `Merchant` | — | ✅ | ✅ |
| POST | `/mp/merchant/apply` | 商家入驻申请 | `MerchantApplyReq` | `MerchantApplyStatus` | 🔒 | ✅ | ✅ |
| GET | `/mp/merchant/apply` | 我的入驻申请状态 | — | `MerchantApplyStatus` | 🔒 | ✅ | ✅ |
| GET | `/mp/merchant/promoted` | 推荐门店（运营位） | — | `数组` | — | ✅ | ✅ |
| GET | `/mp/merchant/visited` | 我买过的商家 | — | `数组` | 🔒 | ✅ | ✅ |

### message（5）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/message` | 消息列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/message/{messageNo}/read` | 标记已读 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/message/read-all` | 全部已读 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/message/subscribe` | 订阅消息授权上报（同意与拒绝都报：后端记额度 + 防反复弹窗） | — | — | 🔒 | ✅ | ✅ |
| GET | `/mp/message/unread-count` | 未读数（角标用，只给一个数） | — | `number` | 🔒 | ✅ | ✅ |

### my-coupons（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/my-coupons` | 商家发给我的券（含到店码） | — | `数组` | 🔒 | ✅ | ✅ |

### my-memberships（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/my-memberships` | 我是哪几家店的会员 | — | `数组` | 🔒 | ✅ | ✅ |
| PUT | `/mp/my-memberships/{entityNo}/reach` | 关掉/打开某家店的消息 | — | — | 🔒 | ✅ | ✅ |

### order（9）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/mp/order` | 下单（幂等） | `CreateOrderReqBody` | `Order` | 🔒 | ✅ | ✅ |
| GET | `/mp/order` | 订单列表 | — | `object` | 🔒 | ✅ | ✅ |
| GET | `/mp/order/{orderNo}` | 订单详情 | — | `Order` | 🔒 | ✅ | ✅ |
| POST | `/mp/order/{orderNo}/after-sale` | 申请售后 | `AfterSaleReq` | `Order` | 🔒 | ✅ | ✅ |
| POST | `/mp/order/{orderNo}/cancel` | 取消订单 | — | `Order` | 🔒 | ✅ | ✅ |
| POST | `/mp/order/{orderNo}/pay` | 支付 | — | `Order` | 🔒 | ✅ | ✅ |
| POST | `/mp/order/{orderNo}/reorder` | 一键再来一单 | — | `ReorderResult` | 🔒 | ✅ | ✅ |
| POST | `/mp/order/capability` | 结算页能力提示（开票/支付方式/额度） | — | `CheckoutCapability` | 🔒 | ✅ | ✅ |
| POST | `/mp/order/preview` | 订单预览（金额以后端为准） | — | `OrderPreview` | 🔒 | ✅ | ✅ |

### points（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/points/account` | 积分账户 | — | `PointAccount` | 🔒 | ✅ | ✅ |
| GET | `/mp/points/deductible` | 结算页试算：本单最多可抵多少 | — | `PointsDeductible` | 🔒 | ✅ | ✅ |
| GET | `/mp/points/records` | 积分流水 | — | `数组` | 🔒 | ✅ | ✅ |

### push-token（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/mp/push-token` | 绑定 App 推送设备（登录后） | — | — | 🔒 | ✅ | ✅ |
| POST | `/mp/push-token/unregister` | 解绑推送设备（登出前，共用设备换人必须解） | — | — | 🔒 | ✅ | ✅ |

### regions（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/regions` | 行政区划（省市区三级，地址簿用） | — | `数组` | — | ✅ | ✅ |

### review（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/review` | 评价列表 | — | `数组` | — | ✅ | ✅ |
| POST | `/mp/review` | 发表评价 | `CreateReviewReq` | `Review` | 🔒 | ✅ | ✅ |
| POST | `/mp/review/{reviewNo}/like` | 点赞/取消 | — | `Review` | 🔒 | ✅ | ✅ |

### store（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/store/{merchantNo}` | 门店主页 | — | `StoreHome` | — | ✅ | ✅ |
| POST | `/mp/store/{merchantNo}/favorite` | 收藏本店 | — | `object` | 🔒 | ✅ | ✅ |
| GET | `/mp/store/{merchantNo}/frequent` | 常买清单 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/mp/store/mine` | 我的常去店 | — | `数组` | 🔒 | ✅ | ✅ |

### user（13）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/mp/user/address` | 地址列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/user/address` | 新增/编辑地址 | `SaveAddressReq` | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/user/address/{addressId}/archive` | 删除地址（软删除） | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/user/address/{addressId}/default` | 设为默认地址 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/mp/user/community` | 绑定社区自提点 | `BindCommunityReq` | `User` | 🔒 | ✅ | ✅ |
| POST | `/mp/user/deregister` | 注销账号（匿名化 + 解绑凭证，交易记录留存） | — | — | 🔒 | ✅ | ✅ |
| POST | `/mp/user/login` | 登录建户 | `LoginReqBody` | `LoginResp` | — | ✅ | ✅ |
| POST | `/mp/user/logout` | 登出（作废服务端会话） | — | — | 🔒 | ✅ | ✅ |
| POST | `/mp/user/otp/send` | 发送验证码 | — | — | — | ✅ | ✅ |
| POST | `/mp/user/phone/bind` | 绑定手机号（验证码） | `BindPhoneReq` | `User` | 🔒 | ✅ | ✅ |
| GET | `/mp/user/phone/capable` | 一键授权当前可不可用（游客可读） | — | `PhoneCapable` | — | ✅ | ✅ |
| POST | `/mp/user/phone/wx` | 微信一键授权绑定手机号 | `WxPhoneReq` | `User` | 🔒 | ✅ | ✅ |
| GET | `/mp/user/profile` | 我的资料 | — | `User` | 🔒 | ✅ | ✅ |

## B 端 `/biz/**` · b-app（商家）

共 **211** 个接口 ｜ 后端已实现 **175**（83%）｜ 前端在调 **211**

### activities（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/activities` | 活动列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/activities` | 建 / 改活动（敞口在这一步算清） | — | `StoreActivity` | 🔒 | ✅ | ✅ |
| GET | `/biz/activities/{activityNo}` | 活动详情 | — | `StoreActivity` | 🔒 | ✅ | ✅ |
| PUT | `/biz/activities/{activityNo}/status` | 启停 / 结束 | — | `StoreActivity` | 🔒 | ✅ | ✅ |

### activity-conflicts（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/activity-conflicts` | 这些商品已经在哪些活动里 | — | `数组` | 🔒 | ✅ | ✅ |

### after-sale（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/after-sale` | 待处理售后 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/after-sale/{afterSaleNo}/approve` | 同意售后 | `HandleAfterSaleReq` | `Order` | 🔒 | ✅ | ✅ |
| POST | `/biz/after-sale/{afterSaleNo}/receive` | 确认收到退货 | — | `Order` | 🔒 | ✅ | ✅ |
| POST | `/biz/after-sale/{afterSaleNo}/reject` | 驳回售后 | `HandleAfterSaleReq` | `Order` | 🔒 | ✅ | ✅ |

### appointment-slots（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/appointment-slots/{slotNo}/close` | 停约 | — | `AppointmentSlot` | 🔒 | ✅ | ✅ |

### auth（5）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/auth/login` | 商家登录 | `MerchantLoginReqBody` | `MerchantLoginResp` | — | ✅ | ✅ |
| POST | `/biz/auth/otp/send` | 发送验证码 | — | — | — | ✅ | ✅ |
| POST | `/biz/auth/password` | 设置登录密码 | — | — | 🔒 | ✅ | ✅ |
| GET | `/biz/auth/password` | 是否已设密码 | — | `HasPasswordResp` | 🔒 | ✅ | ✅ |
| POST | `/biz/auth/staff-login` | 员工登录 | `StaffLoginReq` | `MerchantLoginResp` | — | ✅ | ✅ |

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

### communities（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/communities` | 可选社区（设经营范围用） | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/communities/applies` | 我提报过的小区 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/communities/apply` | 提报平台还没有的小区 | — | `CommunityApply` | 🔒 | ✅ | ✅ |
| POST | `/biz/communities/from-map` | 地图上选中的小区直接开通 | — | `Community` | 🔒 | ✅ | ✅ |

### context（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/context` | 我的作用域与权限 | — | `BizScope` | 🔒 | ✅ | ✅ |

### coupon-issues（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/coupon-issues` | 发放记录（含跳过明细） | — | `数组` | 🔒 | ✅ | ✅ |

### coupon-redeem（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/coupon-redeem` | 到店核销一次（不可撤销） | — | `CouponRedeemResult` | 🔒 | ✅ | ✅ |
| GET | `/biz/coupon-redeem/{code}` | 先看：这张券能不能核 | — | `CouponRedeemView` | 🔒 | ✅ | ✅ |

### coupons（5）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/coupons` | 券列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/coupons` | 建券 / 改券（敞口在这一步算清） | — | `MerchantCoupon` | 🔒 | ✅ | ✅ |
| GET | `/biz/coupons/{couponNo}` | 券详情 | — | `MerchantCoupon` | 🔒 | ✅ | ✅ |
| POST | `/biz/coupons/{couponNo}/issue` | 按人群定向发券 | — | `CouponIssueBatch` | 🔒 | ✅ | ✅ |
| PUT | `/biz/coupons/{couponNo}/status` | 暂停 / 恢复 / 结束 | — | `MerchantCoupon` | 🔒 | ✅ | ✅ |

### cross-store（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/cross-store/compare` | 跨店对比（销售额/订单/复购/缺货） | — | `CrossStoreCompare` | 🔒 | ✅ | ✅ |
| GET | `/biz/cross-store/overview` | 跨店总览（按店并列今日/本月/待办） | — | `CrossStoreOverview` | 🔒 | ✅ | ✅ |

### customers（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/customers` | 客户与复购（跨店总览在用） | — | `数组` | 🔒 | ✅ | ✅ |

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

### entities（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/entities` | 我名下的证照 | — | `数组` | 🔒 | ✅ | ✅ |

### entity（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/entity/{entityNo}` | 一张证照的详情与门店 | — | `EntityStores` | 🔒 | ✅ | ✅ |

### fulfillment（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/fulfillment/carriers` | 承运方可选列表（只列启用的） | — | `数组` | 🔒 | ✅ | ✅ |

### geo（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/geo/estates` | 一片地方的小区（服务端读穿透：缓存优先，不够就问地图） | — | `EstateList` | 🔒 | ✅ | ✅ |
| GET | `/biz/geo/estates/counts` | 下辖各片的小区条数（列表预告） | — | — | 🔒 | ✅ | ✅ |
| GET | `/biz/geo/reverse` | 坐标转地址（门店地址定位） | — | `GeoReverseResult` | 🔒 | ✅ | ✅ |
| GET | `/biz/geo/tips` | 地点输入提示（提报小区按名搜 POI） | — | `数组` | 🔒 | ✅ | ✅ |

### goods（15）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/goods` | 商品列表 | — | `object` | 🔒 | ✅ | ✅ |
| GET | `/biz/goods/{goodsNo}` | 商品详情 | — | `Goods` | 🔒 | ✅ | ✅ |
| GET | `/biz/goods/{goodsNo}/draft` | 读草稿（编辑页回填） | — | `SaveGoodsReqBody` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/{goodsNo}/draft/discard` | 放弃草稿（线上不动，幂等） | — | `Goods` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/{goodsNo}/presale` | 改截单与到货说明 | — | `Goods` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/{goodsNo}/publish` | 发布草稿（原子换版；冲突后带 confirmVersion） | — | `Goods` | 🔒 | ✅ | ✅ |
| GET | `/biz/goods/{goodsNo}/publish-preview` | 发布预览（字段级差异） | — | `PublishPreview` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/{goodsNo}/stock` | 改库存 | `SaveStockReq` | `Goods` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/{goodsNo}/store-price` | 改当前门店售价 | — | `Goods` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/{goodsNo}/store-stock` | 改当前门店库存 | — | `Goods` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/{goodsNo}/submit` | 提交审核（草稿→待审） | — | `Goods` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/{goodsNo}/toggle` | 上下架 | `ToggleGoodsReq` | `Goods` | 🔒 | ✅ | ✅ |
| POST | `/biz/goods/describe` | 自动生成图文详情 | — | — | 🔒 | ✅ | ✅ |
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

### inventory（31）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/inventory/adjust` | 直接改数（走盘点，落单落流水） | — | — | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/balances` | 库存列表（默认只给要处理的） | — | `数组` | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/counts` | 开盘点单（锁账面数） | — | `string` | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/counts/{no}` | 读回盘点单（含账面快照） | — | `StockCount` | 🔒 | ⬜ | ✅ |
| PUT | `/biz/inventory/counts/{no}/lines` | 填实盘数 | — | — | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/counts/{no}/post` | 盘点过账 | — | — | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/documents` | 出入库单据 | — | `数组` | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/inbounds` | 记一笔进货 | — | `string` | 🔒 | ⬜ | ✅ |
| PUT | `/biz/inventory/inbounds/{no}` | 改进货草稿 | — | — | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/inbounds/{no}/post` | 进货过账 | — | — | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/inbounds/{no}/void` | 作废入库单 | — | — | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/items/{itemId}` | 单件库存明细 | — | `StockItemDetail` | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/ledger` | 库存变动明细 | — | `StockLedgerPage` | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/locations` | 库位与仓 | — | `数组` | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/locations` | 加一个仓 | — | `string` | 🔒 | ⬜ | ✅ |
| PUT | `/biz/inventory/locations/{id}/source` | 设发货源 | — | — | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/outbounds` | 报损/领用出库 | — | `string` | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/outbounds/{no}/post` | 出库过账 | — | — | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/outbounds/{no}/void` | 作废出库单 | — | — | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/pickable` | 可挑的货（含 0 库存，从物料出发） | — | `数组` | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/report/monthly` | 进销存月报 | — | `StockMonthly` | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/report/ranking` | 动销/滞销榜 | — | `数组` | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/summary` | 库存总览三个数 | — | `StockSummary` | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/suppliers` | 供应商档案（挑供应商传 activeOnly=true） | — | `数组` | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/suppliers` | 建供应商档案 | — | — | 🔒 | ⬜ | ✅ |
| PUT | `/biz/inventory/suppliers/{no}` | 改供应商档案（引用平台档案的只能改备注） | — | — | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/suppliers/{no}/active` | 停用 / 启用供应商 | — | — | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/transfers` | 建调拨单 | — | `string` | 🔒 | ⬜ | ✅ |
| GET | `/biz/inventory/transfers/{no}` | 读回调拨单 | — | `StockTransfer` | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/transfers/{no}/receive` | 调拨收货 | — | — | 🔒 | ⬜ | ✅ |
| POST | `/biz/inventory/transfers/{no}/ship` | 调拨发出 | — | — | 🔒 | ⬜ | ✅ |

### member-reach（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/member-reach/plan` | 群发试算：能发多少、跳过多少 | — | `ReachPlan` | 🔒 | ✅ | ✅ |
| POST | `/biz/member-reach/send` | 群发（会打扰真实用户） | — | `ReachResult` | 🔒 | ✅ | ✅ |

### member-segments（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/member-segments` | 人群列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/member-segments` | 存人群（存条件不存名单） | — | `MemberSegment` | 🔒 | ✅ | ✅ |
| POST | `/biz/member-segments/{segmentNo}/remove` | 删人群（端上没有 DELETE，见 http-client） | — | — | 🔒 | ✅ | ✅ |
| POST | `/biz/member-segments/preview` | 试算命中与可触达 | — | `MemberSegmentPreview` | 🔒 | ✅ | ✅ |

### member-settings（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/member-settings` | 会员经营口径 | — | `MemberSetting` | 🔒 | ✅ | ✅ |
| PUT | `/biz/member-settings` | 改口径（店主） | — | `MemberSetting` | 🔒 | ✅ | ✅ |

### member-tags（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/member-tags` | 标签字典（含人数） | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/member-tags` | 新建标签 | — | `MemberTag` | 🔒 | ✅ | ✅ |
| PUT | `/biz/member-tags/{tagNo}` | 改名 / 停用 | — | `MemberTag` | 🔒 | ✅ | ✅ |
| POST | `/biz/member-tags/{tagNo}/merge` | 合并（confirm=false 只试算） | — | `MemberMergePreview` | 🔒 | ✅ | ✅ |

### members（6）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/members` | 会员列表（筛选+分页） | — | `object` | 🔒 | ✅ | ✅ |
| POST | `/biz/members` | 手工录入（未注册记为线索） | — | `Member` | 🔒 | ✅ | ✅ |
| GET | `/biz/members/{memberNo}` | 会员详情：各店往来与来源轨迹 | — | `MemberDetail` | 🔒 | ✅ | ✅ |
| PUT | `/biz/members/{memberNo}` | 改备注 / 拉黑 | — | `Member` | 🔒 | ✅ | ✅ |
| GET | `/biz/members/stats` | 四层人数与未计入买家 | — | `MemberStats` | 🔒 | ✅ | ✅ |
| POST | `/biz/members/tags` | 批量打标 / 去标 | — | — | 🔒 | ✅ | ✅ |

### merchant（9）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/merchant/apply` | 提交入驻申请 | `MerchantApplyReqBody` | `MerchantProfile` | 🔒 | ✅ | ✅ |
| GET | `/biz/merchant/apply` | 上次入驻申请 | — | `MerchantApplyReq` | 🔒 | ✅ | ✅ |
| GET | `/biz/merchant/debt` | 我的欠款与流水 | — | `MyDebt` | 🔒 | ✅ | ✅ |
| GET | `/biz/merchant/pay-channel` | 本店能开的收款通道（含没开的） | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/merchant/payment` | 收款进件状态 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/merchant/payment` | 补交资料并提交进件 | `SubmitPaymentReq` | `PaymentApplyment` | 🔒 | ✅ | ✅ |
| POST | `/biz/merchant/payment/{payChannel}/refresh` | 回查进件结果 | — | `PaymentApplyment` | 🔒 | ✅ | ✅ |
| GET | `/biz/merchant/profile` | 商家资料 | — | `MerchantProfile` | 🔒 | ✅ | ✅ |
| POST | `/biz/merchant/quick-start` | 无证照快速开店 | — | `MerchantProfile` | 🔒 | ✅ | ✅ |

### message（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/message` | 商家消息列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/message/{messageNo}/read` | 标记已读 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/message/read-all` | 全部已读 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/message/unread-count` | 未读数（红点轮询，只给一个数） | — | `number` | 🔒 | ✅ | ✅ |

### my-spec-dims（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/my-spec-dims` | 我建的规格维度（含用量与配额） | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/my-spec-dims/{dimNo}/archive` | 停用/启用自建维度 | — | — | 🔒 | ✅ | ✅ |
| POST | `/biz/my-spec-dims/{dimNo}/rename` | 给自建维度改名 | — | — | 🔒 | ✅ | ✅ |

### order（5）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/order` | 订单列表 | — | `object` | 🔒 | ✅ | ✅ |
| GET | `/biz/order/{orderNo}` | 订单详情 | — | `Order` | 🔒 | ⬜ | ✅ |
| POST | `/biz/order/{orderNo}/confirm-offline-pay` | 确认线下收款 | — | `Order` | 🔒 | ⬜ | ✅ |
| POST | `/biz/order/{orderNo}/delivered` | 自送已送达 | — | `Order` | 🔒 | ⬜ | ✅ |
| POST | `/biz/order/{orderNo}/ship` | 快递发货 | `ShipReq` | `Order` | 🔒 | ⬜ | ✅ |

### pickable-props（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/pickable-props` | 还能加进这一类的商品参数（本类目已配 + 平台通用 + 自建） | — | `数组` | 🔒 | ✅ | ✅ |

### pickup（8）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/pickup/{orderNo}/report` | 破损短少上报 | `ReportShortageReq` | `Order` | 🔒 | ✅ | ✅ |
| POST | `/biz/pickup/arrived` | 标记到货 | `MarkArrivedReq` | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/pickup/orders` | 本自提点订单 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/pickup/overview` | 自提点履约总览 | — | `PickupOverview` | 🔒 | ✅ | ✅ |
| GET | `/biz/pickup/picking` | 分拣单 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/pickup/verify` | 核销自提码 | `VerifyReq` | `Order` | 🔒 | ✅ | ✅ |
| POST | `/biz/pickup/verify/batch` | 批量核销 | `VerifyBatchReq` | `VerifyBatchResult` | 🔒 | ✅ | ✅ |
| GET | `/biz/pickup/verify/search` | 按取货码片段搜单 | — | `数组` | 🔒 | ✅ | ✅ |

### pickup-points（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/pickup-points` | 自建自提点（待运营核实） | — | `PickupCandidate` | 🔒 | ✅ | ✅ |
| GET | `/biz/pickup-points/candidates` | 门店可引用的取货点候选 | — | `数组` | 🔒 | ✅ | ✅ |

### plan（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/plan` | 我的套餐（档位/用量/三档对比） | — | `MerchantPlan` | 🔒 | ✅ | ✅ |
| POST | `/biz/plan/trial` | 自助开通试用（一主体一次） | — | `MerchantPlan` | 🔒 | ✅ | ✅ |

### points（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/points/account` | 本期发分服务费与开关状态 | — | `MerchantPointAccount` | 🔒 | ✅ | ✅ |
| GET | `/biz/points/records` | 发分服务费明细（按单） | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/points/toggle` | 开/关本店积分 | — | `MerchantPointAccount` | 🔒 | ✅ | ✅ |

### push-token（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/push-token` | 绑定 App 推送设备（登录后） | — | — | 🔒 | ✅ | ✅ |
| POST | `/biz/push-token/unregister` | 解绑推送设备（登出前，共用设备换班必须解） | — | — | 🔒 | ✅ | ✅ |

### qualifications（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/qualifications` | 我的资质与已获授权的类目 | — | `MyQualifications` | 🔒 | ✅ | ✅ |
| POST | `/biz/qualifications/save` | 传一张资质证件 | — | `Qualification` | 🔒 | ✅ | ✅ |

### regions（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/regions` | 行政区划下一级（框覆盖范围用） | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/regions/path` | 区划从省到自身的路径 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/regions/search` | 跨级搜区划与聚落（选择器搜索） | — | `RegionSearchResult` | 🔒 | ✅ | ✅ |
| GET | `/biz/regions/villages` | 街道/镇下的官方村名词典（提报村用） | — | `数组` | 🔒 | ✅ | ✅ |

### review（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/review` | 评价列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/review/{reviewNo}/appeal` | 申诉差评 | `AppealReviewReq` | `Review` | 🔒 | ✅ | ✅ |
| POST | `/biz/review/{reviewNo}/reply` | 回复评价 | `ReplyReviewReq` | `Review` | 🔒 | ✅ | ✅ |

### role（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/role/{roleCode}` | 改角色 | — | `MerchantRole` | 🔒 | ✅ | ✅ |
| POST | `/biz/role/{roleCode}/delete` | 删除自定义角色 | — | — | 🔒 | ✅ | ✅ |

### role-perms（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/role-perms` | 可勾的权限点 | — | `数组` | 🔒 | ✅ | ✅ |

### roles（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/roles` | 角色列表（预置 + 自定义） | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/roles` | 建自定义角色 | — | `MerchantRole` | 🔒 | ✅ | ✅ |

### settle（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/settle/batch` | 我的账期批次 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/settle/bills` | 结算单列表 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/settle/income` | 收入按状态汇总 | — | `IncomeSummary` | 🔒 | ✅ | ✅ |
| GET | `/biz/settle/rate-card` | 费率卡 | — | `RateCard` | 🔒 | ✅ | ✅ |

### sku-identity（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/sku-identity/export` | 导出本店全部规格行的条码/货号/单位 | — | — | 🔒 | ✅ | ✅ |
| POST | `/biz/sku-identity/import` | 商品编码批量导入 | — | `SkuIdentityReport` | 🔒 | ✅ | ✅ |
| POST | `/biz/sku-identity/import/plan` | 商品编码导入试算（不写库） | — | `SkuIdentityReport` | 🔒 | ✅ | ✅ |

### spec-dims（3）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/spec-dims` | 加规格组时能挑的维度（本类目已配 + 平台通用 + 自建） | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/spec-dims` | 自建规格维度（只本店可用） | — | `SpecTemplate` | 🔒 | ✅ | ✅ |
| GET | `/biz/spec-dims/{dimNo}/values` | 某个规格下平台有的全部档位（加档位的候选） | — | `数组` | 🔒 | ✅ | ✅ |

### spec-override（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/spec-override/{categoryNo}` | 本店用哪几个规格、什么顺序、叫什么 | — | `数组` | 🔒 | ✅ | ✅ |

### spec-props（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/spec-props` | 这一类的商品参数（产地/保质期/材质，不分 SKU） | — | `数组` | 🔒 | ✅ | ✅ |

### spec-templates（2）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/spec-templates` | 规格模板 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/spec-templates` | 存为常用规格 | `SaveSpecTemplateReq` | `SpecTemplate` | 🔒 | ✅ | ✅ |

### spec-values（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/spec-values` | 在平台维度下加一个自有规格值 | — | `SpecValueAdded` | 🔒 | ✅ | ✅ |

### spu-std（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/spu-std` | 标准品搜索（建品用） | — | `数组` | 🔒 | ✅ | ✅ |

### staff（5）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/staff` | 员工列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/staff` | 加员工 | `AddStaffReq` | `MerchantStaff` | 🔒 | ✅ | ✅ |
| POST | `/biz/staff/{mchAccountNo}/status` | 停用/启用员工 | `SetActiveReq` | `MerchantStaff` | 🔒 | ✅ | ✅ |
| POST | `/biz/staff/{mchAccountNo}/store` | 授权到店 | `GrantStoreReq` | `MerchantStaff` | 🔒 | ✅ | ✅ |
| GET | `/biz/staff/logs` | 员工与授权变更记录 | — | `数组` | 🔒 | ✅ | ✅ |

### store（15）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/store` | 店铺门面 | — | `StoreProfile` | 🔒 | ✅ | ✅ |
| POST | `/biz/store` | 保存店铺门面 | `SaveStoreReqBody` | `StoreProfile` | 🔒 | ✅ | ✅ |
| GET | `/biz/store/{storeNo}/categories` | 本店经营类目 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/{storeNo}/categories` | 整份替换本店经营类目 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/{storeNo}/default` | 设为默认店 | — | `Store` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/{storeNo}/payment` | 换门店收款号 | `SetStorePaymentReq` | `Store` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/{storeNo}/rename` | 改门店名与地址 | `StoreEditReq` | `Store` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/{storeNo}/status` | 停用/启用门店 | `SetActiveReq` | `Store` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/announcement` | 只改公告（含有效期，可同时发到别的门店） | — | `StoreProfile` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/announcement/recent/remove` | 从常用里删一条 | — | `StoreProfile` | 🔒 | ✅ | ✅ |
| POST | `/biz/store/create` | 新建门店 | `StoreEditReq` | `Store` | 🔒 | ✅ | ✅ |
| GET | `/biz/store/list` | 我的门店 | — | `数组` | 🔒 | ✅ | ✅ |
| GET | `/biz/store/poster` | 分享海报 | — | `Poster` | 🔒 | ✅ | ✅ |
| GET | `/biz/store/qrcode` | 店铺码 | — | `StoreQrcode` | 🔒 | ✅ | ✅ |
| GET | `/biz/store/share-kit` | 分享素材 | — | `ShareKit` | 🔒 | ✅ | ✅ |

### store-spec-dims（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/store-spec-dims` | 本店货架类目各自能用的规格 | — | `数组` | 🔒 | ✅ | ✅ |

### stores（6）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/biz/stores/{storeNo}/appointment-slots` | 预约时段列表 | — | `数组` | 🔒 | ✅ | ✅ |
| POST | `/biz/stores/{storeNo}/appointment-slots` | 开预约时段 | — | `AppointmentSlot` | 🔒 | ✅ | ✅ |
| GET | `/biz/stores/{storeNo}/fulfillment` | 门店送货方式 | — | `StoreFulfillment` | 🔒 | ✅ | ✅ |
| PUT | `/biz/stores/{storeNo}/fulfillment` | 保存门店送货方式 | — | `StoreFulfillment` | 🔒 | ✅ | ✅ |
| GET | `/biz/stores/{storeNo}/fulfillment/{channel}/impact` | 关掉这一路会影响的在售商品 | — | `数组` | 🔒 | ⬜ | ✅ |
| GET | `/biz/stores/mine` | 我能进的所有门店（按证照分组） | — | `数组` | 🔒 | ✅ | ✅ |

### upload（1）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/biz/upload/image` | 上传商品图 | `UploadImageReq` | `object` | 🔒 | ✅ | ✅ |

## 平台端 `/ops/**` · ops-web（运营）

共 **360** 个接口 ｜ 后端已实现 **278**（77%）｜ 前端在调 **298**

### aftersale（4）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/after-sales` | listAfterSales | — | `object` | — | ✅ | ✅ |
| POST | `/ops/after-sales/{afterSaleNo}/decide` | 平台介入裁决（`ARBITRATING` 的唯一出口） | — | `AfterSale` | — | ✅ | ✅ |
| GET | `/ops/after-sales/fast-refund-rule` | getFastRefundRule | — | `FastRefundRule` | — | ✅ | ✅ |
| POST | `/ops/after-sales/fast-refund-rule` | 极速退阈值（P-6.1.2）：金额上限 > 0、时限 ≥ 1 小时 | — | `FastRefundRule` | — | ✅ | ✅ |

### community（25）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/communities` | listCommunities | — | `object` | — | ✅ | ✅ |
| POST | `/ops/communities/{no}/archive` | archiveCommunity | — | `Community` | — | ⬜ | ✅ |
| POST | `/ops/communities/{no}/fence` | 覆盖围栏半径，米（P-2.1.3） | — | `Community` | — | ⬜ | ✅ |
| POST | `/ops/communities/{no}/open` | 开城/停城（P-2.1.2） | — | `Community` | — | ⬜ | ✅ |
| POST | `/ops/communities/{no}/region` | 把社区挂到行政区划下（ADR-013） | — | `Community` | — | ⬜ | ✅ |
| POST | `/ops/communities/{no}/unarchive` | unarchiveCommunity | — | `Community` | — | ⬜ | ✅ |
| GET | `/ops/communities/applies` | 提报队列 | — | `object` | — | ✅ | ✅ |
| POST | `/ops/communities/applies/{applyNo}/decide` | 裁决 | — | `CommunityApply` | — | ✅ | ✅ |
| GET | `/ops/communities/duplicates` | 疑似重复的聚落两两清单 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/communities/merge` | 合并：把 fromNo 并进 intoNo | — | `Community` | — | ✅ | ✅ |
| GET | `/ops/communities/near` | 一个坐标附近已开通的聚落，按距离升序 —— 裁决时查重用 */ | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/pickups` | listPickups | — | `object` | — | ✅ | ✅ |
| POST | `/ops/pickups` | 建自提点 | — | `PickupPoint` | — | ✅ | ✅ |
| POST | `/ops/pickups/{no}/archive` | archivePickup | — | `PickupPoint` | — | ⬜ | ✅ |
| POST | `/ops/pickups/{no}/decide` | 裁决商家自建的自提点（P1）：PENDING → ACTIVE / REJECTED | — | `PickupPoint` | — | ⬜ | ✅ |
| POST | `/ops/pickups/{no}/service-fee` | 履约服务费费率，万分比（P-2.2.4） | — | `PickupPoint` | — | ⬜ | ✅ |
| POST | `/ops/pickups/{no}/status` | 启停与迁移（P-2.2.2），非法迁移抛错 | — | `PickupPoint` | — | ⬜ | ✅ |
| POST | `/ops/pickups/{no}/unarchive` | unarchivePickup | — | `PickupPoint` | — | ⬜ | ✅ |
| GET | `/ops/pickups/risky` | 疑似职业化的临时自提点（P-2.2.5）：近 30 天承接次数 ≥ 阈值 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/regions` | 某区划的直接下级 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/regions` | 区划人工维护（新增 / 停用 / 改名） | — | `Region` | — | ✅ | ✅ |
| POST | `/ops/regions/{code}/rename` | 改名不动码，存量引用不受影响 */ | — | `Region` | — | ✅ | ✅ |
| POST | `/ops/regions/{code}/toggle` | 停用只影响新选择，存量商家的范围不动 | — | `Region` | — | ✅ | ✅ |
| GET | `/ops/regions/path` | 从省到自身的整条链路 | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/regions/resolve` | 按提报单的地址与坐标推断该挂哪个街道 | — | `数组` | — | ✅ | ✅ |

### content（12）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/contents/posts` | listPosts | — | `object` | — | ✅ | ✅ |
| POST | `/ops/contents/posts/{postNo}/decide` | 裁决一条种草内容 | — | `Post` | — | ✅ | ✅ |
| POST | `/ops/contents/posts/batch-pass` | 批量通过 | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/contents/questions` | listQuestions | — | `object` | — | ✅ | ✅ |
| POST | `/ops/contents/questions/{questionNo}/answer` | 回答 | — | `Question` | — | ✅ | ✅ |
| POST | `/ops/contents/questions/{questionNo}/hide` | 隐藏提问（如导流、辱骂） | — | `Question` | — | ✅ | ✅ |
| GET | `/ops/contents/rankings` | listRankings | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/contents/rankings` | 保存榜单 | — | `Ranking` | — | ✅ | ✅ |
| POST | `/ops/contents/rankings/{rankNo}/enabled` | setRankingEnabled | — | `Ranking` | — | ✅ | ✅ |
| GET | `/ops/materials` | listMaterials | — | `object` | — | ✅ | ✅ |
| POST | `/ops/materials` | 保存素材（P-15.1.1–15.1.4） | — | `Material` | — | ✅ | ✅ |
| POST | `/ops/materials/{no}/published` | setMaterialPublished | — | `Material` | — | ⬜ | ✅ |

### dashboard（9）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| POST | `/ops/auth/forgot` | 忘记密码：往登录名那个邮箱发一次性重置码 | — | `object` | — | ✅ | ✅ |
| POST | `/ops/auth/login` | 登录 | — | `LoginResp` | — | ✅ | ⬜ |
| GET | `/ops/auth/me` | 拿当前登录人的最新身份（`GET /ops/auth/me`） | — | `LoginResp` | — | ✅ | ⬜ |
| POST | `/ops/auth/reset` | 用邮件里的重置码设新密码 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/dashboard/funnel` | getAcquisitionFunnel | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/dashboard/kpi` | getDashboardKpi | — | `DashboardKpi` | — | ✅ | ✅ |
| GET | `/ops/dashboard/merchants` | 商家经营排行（P-16.1.2 / P-16.1.3）——大盘之下的第一层下钻 | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/dashboard/trend` | getDashboardTrend | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/menu` | 当前登录人的**动态菜单**（`GET /ops/menu`） | — | `数组` | — | ✅ | ✅ |

### finance（37）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/debts/{entityNo}` | 某商家的欠款余额与流水 */ | — | `MerchantDebt` | — | ✅ | ✅ |
| POST | `/ops/debts/{entityNo}/deposit-offset` | 用保证金抵掉一部分欠款 | — | `MerchantDebt` | — | ✅ | ✅ |
| GET | `/ops/finance/invoice-title` | 平台开票抬头 | — | `InvoiceTitle` | — | ✅ | ✅ |
| POST | `/ops/finance/invoice-title` | 公司全称与税号必填 —— 缺了供应商开不出票，存下去只会让人以为已经配好了 | — | `InvoiceTitle` | — | ✅ | ✅ |
| GET | `/ops/finance/invoices` | listInvoiceRequests | — | `object` | — | ✅ | ✅ |
| POST | `/ops/finance/invoices/{invoiceNo}/issue` | 开票 | — | `InvoiceRequest` | — | ✅ | ✅ |
| POST | `/ops/finance/invoices/{invoiceNo}/reject` | rejectInvoice | — | `InvoiceRequest` | — | ✅ | ✅ |
| GET | `/ops/finance/tax-rule` | getTaxRule | — | `TaxRule` | — | ✅ | ✅ |
| PUT | `/ops/finance/tax-rule` | 个税代扣规则 | — | `TaxRule` | — | ✅ | ✅ |
| GET | `/ops/finance/withdrawals` | listWithdrawals | — | `object` | — | ✅ | ✅ |
| POST | `/ops/finance/withdrawals/{withdrawNo}/decide` | 审批一笔提现 | — | `Withdrawal` | — | ✅ | ✅ |
| GET | `/ops/invoice-requests` | listBuyerInvoiceRequests | — | `数组` | — | ✅ | ⬜ |
| POST | `/ops/invoice-requests/{requestNo}/issued` | markBuyerInvoiceIssued | — | `BuyerInvoiceRequest` | — | ✅ | ⬜ |
| POST | `/ops/invoice-requests/{requestNo}/reject` | rejectBuyerInvoiceRequest | — | `BuyerInvoiceRequest` | — | ✅ | ⬜ |
| GET | `/ops/payables` | listPayables | — | `数组` | — | ✅ | ⬜ |
| POST | `/ops/payables/{settleNo}/confirm` | 确认对账：双方认了这个数 | — | `Settlement` | — | ✅ | ⬜ |
| POST | `/ops/payables/{settleNo}/no-invoice` | 标记无票供应商：**不进发票流程，但要在应付列表上标出来** —— 让财务付款前就看见 */ | — | `Settlement` | — | ✅ | ⬜ |
| POST | `/ops/payables/{settleNo}/paid` | 登记已付款 | — | `Settlement` | — | ✅ | ⬜ |
| GET | `/ops/points/client-policy` | 积分的**端策略**：哪个端不发放、哪个端不核销、当面付能不能抵扣 | — | `ClientPointsPolicy` | — | ✅ | ⬜ |
| POST | `/ops/points/client-policy` | savePointsClientPolicy | — | `ClientPointsPolicy` | — | ✅ | ⬜ |
| GET | `/ops/points/overview` | 积分资金总览 | — | `PointsOverview` | — | ✅ | ✅ |
| GET | `/ops/purchase-invoices` | listPurchaseInvoices | — | `数组` | — | ✅ | ⬜ |
| POST | `/ops/purchase-invoices/{invoiceNo}/reject` | rejectPurchaseInvoice | — | `PurchaseInvoice` | — | ✅ | ⬜ |
| POST | `/ops/purchase-invoices/{invoiceNo}/verify` | verifyPurchaseInvoice | — | `PurchaseInvoice` | — | ✅ | ⬜ |
| GET | `/ops/refund-split-backs` | 待回退分账的售后单（P-12.1.5 / E4）：售后裁决打的 `refundSplitPending` 标记 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/refund-split-backs/{asNo}/execute` | 执行退款回退分账，**执行后清除该售后单的标记**，否则队列永远消不掉 | — | `AfterSale` | — | ⬜ | ✅ |
| GET | `/ops/settle-batches` | 账期批次列表 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/settle-batches/{batchNo}/hold` | 继续挂起 | — | `SettleBatch` | — | ✅ | ✅ |
| POST | `/ops/settle-batches/{batchNo}/release` | 人工放行一批 | — | `SettleBatch` | — | ✅ | ✅ |
| GET | `/ops/settle/fee-rules` | 全部费率版本，含历史 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/settle/fee-rules` | 新增一个费率版本 | — | `FeeRuleVersion` | — | ✅ | ✅ |
| GET | `/ops/settle/fee-rules/effective` | 某时刻实际生效的四格费率 | — | `EffectiveFeeRates` | — | ✅ | ✅ |
| GET | `/ops/settle/pay-channels` | 支付通道设置 + 每个通道的费率版本 | — | `数组` | — | ✅ | ✅ |
| PUT | `/ops/settle/pay-channels/{channel}` | 改通道的开关与结算属性 | — | `PayChannelSetting` | — | ✅ | ✅ |
| POST | `/ops/settle/pay-channels/{channel}/rates` | 加一版通道费率 | — | `PayChannelRateVersion` | — | ✅ | ✅ |
| GET | `/ops/settlements` | listSettlements | — | `object` | — | ✅ | ✅ |
| GET | `/ops/split-records` | listSplitRecords | — | `object` | — | ✅ | ✅ |

### fulfillment（15）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/freight-templates` | `showArchived` 为真时连归档的一起返回（G1：归档不是删除，得看得见） | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/freight-templates` | 新建/保存运费模板（含超区规则） | — | `FreightTemplate` | — | ✅ | ✅ |
| POST | `/ops/freight-templates/{templateNo}/archive` | 归档模板（G1：软删除，不是删除） | — | `FreightTemplate` | — | ✅ | ✅ |
| POST | `/ops/freight-templates/{templateNo}/unarchive` | unarchiveFreightTemplate | — | `FreightTemplate` | — | ✅ | ✅ |
| GET | `/ops/fulfillment/batches` | listArrivalBatches | — | `object` | — | ✅ | ✅ |
| POST | `/ops/fulfillment/batches/{batchNo}/status` | 批次推进（计划→已发车→已到货→已签收），跳步抛错 | — | `ArrivalBatch` | — | ✅ | ✅ |
| GET | `/ops/fulfillment/carriers` | listCarriers | — | `数组` | — | ✅ | ✅ |
| PUT | `/ops/fulfillment/carriers/{carrier}` | 保存一家运力的接入配置 | — | `CarrierConfig` | — | ✅ | ✅ |
| POST | `/ops/fulfillment/carriers/{carrier}/enabled` | 启停一家运力 | — | `CarrierConfig` | — | ✅ | ✅ |
| GET | `/ops/fulfillment/overdue-rule` | getOverdueRule | — | `OverdueRule` | — | ✅ | ✅ |
| POST | `/ops/fulfillment/overdue-rule` | 逾期规则（P-5.1.4） | — | `OverdueRule` | — | ✅ | ✅ |
| GET | `/ops/fulfillment/redeem` | 核销监控与逾期看板（P-5.1.3） | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/fulfillment/sorting` | 按自提点汇总分拣（P-5.1.2） | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/shipments` | listShipments | — | `object` | — | ✅ | ✅ |
| POST | `/ops/shipments/{shipmentNo}/waybill` | 换运单号（录错了、或承运商重新出单） | — | `Shipment` | — | ✅ | ✅ |

### group（8）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/demands` | listDemands | — | `object` | — | ✅ | ✅ |
| POST | `/ops/demands/{demandNo}/quotes` | 人肉指派商家报价（P-8.2.2，初期靠运营撮合） | — | `Quote` | — | ✅ | ✅ |
| GET | `/ops/groups` | listGroupCampaigns | — | `object` | — | ✅ | ✅ |
| POST | `/ops/groups/{no}/audit` | 团模板审核（P-8.1.1）：起团人数 ≥2、团购价必须低于原价 | — | `GroupCampaign` | — | ⬜ | ✅ |
| POST | `/ops/groups/{no}/status` | setGroupStatus | — | `GroupCampaign` | — | ⬜ | ✅ |
| GET | `/ops/quotes` | listQuotes | — | `object` | — | ✅ | ✅ |
| POST | `/ops/quotes/{no}/breach` | 标记毁约（P-8.2.5）：累计进商家信用档案 | — | `Quote` | — | ⬜ | ✅ |
| POST | `/ops/quotes/{no}/price` | 改价（P-8.2.4）：留痕并公示，超过阈值禁止再改 | — | `Quote` | — | ⬜ | ✅ |

### growth（6）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/attribution-rule` | getAttributionRule | — | `AttributionRule` | — | ✅ | ✅ |
| POST | `/ops/attribution-rule` | 归因规则（P-9.1.1/9.1.2/9.1.5） | — | `AttributionRule` | — | ✅ | ✅ |
| GET | `/ops/attribution-traces` | 归因链路查询与审计（P-9.1.3） | — | `object` | — | ✅ | ✅ |
| GET | `/ops/fission-campaigns` | listFissionCampaigns | — | `object` | — | ✅ | ✅ |
| POST | `/ops/fission-campaigns` | 邀请有礼（P-9.2.1） | — | `FissionCampaign` | — | ✅ | ✅ |
| POST | `/ops/fission-campaigns/{no}/enabled` | setFissionEnabled | — | `FissionCampaign` | — | ⬜ | ✅ |

### iam（18）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/audit-log` | 审计日志（P-1.1.4） | — | `object` | — | ✅ | ⬜ |
| GET | `/ops/perm/functions` | 功能与功能点全集 —— 权限树的数据源 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/perm/functions/${encodeURIComponent(functionCode)}/move` | 菜单调序：同级内上移/下移 | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/perm/functions/reorder` | 整段重排（拖动用）：传该父级下的**完整顺序** | — | `object` | — | ✅ | ✅ |
| POST | `/ops/perm/points/${encodeURIComponent(pointCode)}/move` | movePermPoint | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/perm/points/reorder` | reorderPermPoints | — | `object` | — | ✅ | ✅ |
| GET | `/ops/perm/roles` | listRoles | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/perm/roles` | createRole | — | `RoleDef` | — | ✅ | ✅ |
| POST | `/ops/perm/roles/{roleCode}/delete` | 删角色 | — | `object` | — | ✅ | ✅ |
| POST | `/ops/perm/roles/{roleCode}/force-logout` | **强制该角色的成员重新登录**（紧急撤回） | — | `object` | — | ✅ | ✅ |
| GET | `/ops/perm/roles/{roleCode}/points` | 某个角色已勾的功能点码 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/perm/roles/{roleCode}/points` | 改角色的功能点 | — | `RoleDef` | — | ✅ | ✅ |
| POST | `/ops/perm/roles/{roleCode}/rename` | 改角色展示名 | — | `RoleDef` | — | ✅ | ✅ |
| GET | `/ops/staffs` | listStaffs | — | `object` | — | ✅ | ⬜ |
| POST | `/ops/staffs` | 新建员工 | — | `object` | — | ✅ | ⬜ |
| POST | `/ops/staffs/{no}/enabled` | 停用/启用（软删除语义，不删账号 —— 审计要能追溯到人） | — | `Staff` | — | ⬜ | ⬜ |
| POST | `/ops/staffs/{no}/roles` | 改角色（**多角色**） | — | `Staff` | — | ⬜ | ⬜ |
| POST | `/ops/staffs/{no}/scope` | 数据域授权（P-1.1.3） | — | `Staff` | — | ⬜ | ⬜ |

### inventory（7）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/inventory/balances` | **某一个商家**的库存待办（健康度页点进一行之后看的） | — | `数组` | — | ⬜ | ⬜ |
| GET | `/ops/inventory/credentials` | 某个商家发过哪些开放对接的钥匙 | — | `数组` | — | ⬜ | ⬜ |
| POST | `/ops/inventory/credentials` | 签发 | — | `InvCredentialIssued` | — | ⬜ | ⬜ |
| POST | `/ops/inventory/credentials/{credentialId}/revoke` | 吊销 | — | `object` | — | ⬜ | ⬜ |
| GET | `/ops/inventory/health` | 库存健康度：负库存 / 零库存仍在架 / 长期未动销 | — | `数组` | — | ✅ | ⬜ |
| GET | `/ops/inventory/ledger` | 商家台账（只读） | — | `InvLedgerPage` | — | ⬜ | ⬜ |
| GET | `/ops/inventory/recon` | 库存对差 | — | `InvReconReport` | — | ✅ | ⬜ |

### job（7）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/jobs` | 任务清单：定义与当前状态**已在后端合成一行** | — | `数组` | — | ✅ | ⬜ |
| GET | `/ops/jobs/${encodeURIComponent(name)}` | getJob | — | `JobRow` | — | ⬜ | ⬜ |
| PUT | `/ops/jobs/${encodeURIComponent(name)}/cron` | 改频率 | — | `JobRow` | — | ⬜ | ⬜ |
| POST | `/ops/jobs/${encodeURIComponent(name)}/disable` | 关 | — | `JobRow` | — | ⬜ | ⬜ |
| POST | `/ops/jobs/${encodeURIComponent(name)}/enable` | 开 | — | `JobRow` | — | ⬜ | ⬜ |
| POST | `/ops/jobs/${encodeURIComponent(name)}/trigger` | 立即执行一次 | — | `JobRow` | — | ⬜ | ⬜ |
| GET | `/ops/jobs/${encodeURIComponent(q.name)}/logs` | 执行日志，倒序 | — | `数组` | — | ⬜ | ⬜ |

### marketing（22）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/campaigns` | **商家自建的店铺活动**（平台治理视角） | — | `object` | — | ✅ | ✅ |
| POST | `/ops/campaigns/{no}/archive` | archiveCampaign | — | `MerchantCampaign` | — | ⬜ | ✅ |
| POST | `/ops/campaigns/{no}/toggle` | 停用 / 启用商家活动（矩阵 §2.3） | — | `MerchantCampaign` | — | ⬜ | ✅ |
| POST | `/ops/campaigns/{no}/unarchive` | unarchiveCampaign | — | `MerchantCampaign` | — | ⬜ | ✅ |
| GET | `/ops/content-slots` | listContentSlots | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/content-slots/{no}/archive` | archiveSlot | — | `ContentSlot` | — | ⬜ | ✅ |
| POST | `/ops/content-slots/{no}/enabled` | 上下线开关（P-7.3.5） | — | `ContentSlot` | — | ⬜ | ✅ |
| POST | `/ops/content-slots/{no}/schedule` | 定时上下线：下线必须晚于上线 | — | `ContentSlot` | — | ⬜ | ✅ |
| POST | `/ops/content-slots/{no}/unarchive` | unarchiveSlot | — | `ContentSlot` | — | ⬜ | ✅ |
| GET | `/ops/coupon-issues` | listCouponIssues | — | `object` | — | ✅ | ✅ |
| GET | `/ops/coupons` | listCoupons | — | `object` | — | ✅ | ✅ |
| POST | `/ops/coupons` | 建券 / 改券（TDD-营销预算前置） | — | `Coupon` | — | ✅ | ✅ |
| POST | `/ops/coupons/{couponNo}/issue` | 主动发券（P-7.1.2） | — | `CouponIssue` | — | ✅ | ✅ |
| POST | `/ops/coupons/{no}/archive` | archiveCoupon | — | `Coupon` | — | ⬜ | ✅ |
| POST | `/ops/coupons/{no}/budget` | 调预算（P-7.1.3） | — | `Coupon` | — | ⬜ | ✅ |
| POST | `/ops/coupons/{no}/status` | 改券状态（暂停 / 恢复 / 结束） | — | `Coupon` | — | ⬜ | ✅ |
| POST | `/ops/coupons/{no}/unarchive` | unarchiveCoupon | — | `Coupon` | — | ⬜ | ✅ |
| GET | `/ops/marketing/member-cards` | listMemberCards | — | `object` | — | ⬜ | ✅ |
| POST | `/ops/marketing/member-cards` | 保存会员卡 | — | `MemberCard` | — | ⬜ | ✅ |
| POST | `/ops/marketing/member-cards/{cardNo}/archive` | 归档 | — | `MemberCard` | — | ⬜ | ✅ |
| POST | `/ops/marketing/member-cards/{cardNo}/status` | 状态推进（草稿→启用⇄暂停→停售），非法迁移抛错 | — | `MemberCard` | — | ⬜ | ✅ |
| POST | `/ops/marketing/member-cards/{cardNo}/unarchive` | unarchiveMemberCard | — | `MemberCard` | — | ⬜ | ✅ |

### member（7）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/members` | 跨商家会员名单 | — | `OpsPerson` | — | ✅ | ✅ |
| GET | `/ops/members/reach-stats` | 触达量与退订率，**按退订率倒序** —— 发得多不是成绩，发到有人关掉才是问题 */ | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/persons/{personNo}` | 人档：他是哪几家店的会员 —— 这正是人档存在的理由 */ | — | `object` | — | ✅ | ✅ |
| POST | `/ops/persons/{personNo}/reveal-phone` | 查看完整手机号（申诉处置） | — | `object` | — | ✅ | ✅ |
| GET | `/ops/promotion/activities` | 全平台活动（新模型）：归属、受众、限量 */ | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/promotion/activities/{activityNo}/stop` | 强制停止一个活动 | — | `OpsPromoActivity` | — | ✅ | ✅ |
| GET | `/ops/promotion/coupons` | 全平台券（新模型）：归属、敞口、异常标记 */ | — | `数组` | — | ✅ | ✅ |

### merchant（37）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/admission/deposits/{merchantNo}` | merchantDeposit | — | `MerchantDeposit` | — | ✅ | ✅ |
| GET | `/ops/admission/deposits/{merchantNo}/txns` | depositTxns | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/admission/deposits/{merchantNo}/txns` | addDepositTxn | — | `object` | — | ✅ | ✅ |
| GET | `/ops/admission/pay-quotas/{merchantNo}` | 当前收款额度 | — | `数组` | — | ✅ | ✅ |
| PUT | `/ops/admission/pay-quotas/{merchantNo}` | 设置收款额度上限 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/admission/policies` | 三档准入策略 | — | `数组` | — | ✅ | ✅ |
| PUT | `/ops/admission/policies/{legalForm}` | updateAdmissionPolicy | — | `object` | — | ✅ | ✅ |
| GET | `/ops/merchant-plans` | 到期与降级看板 | — | `object` | — | ✅ | ✅ |
| POST | `/ops/merchant-plans/{merchantNo}/grant` | 授予 / 延长 | — | `MerchantPlanRow` | — | ✅ | ✅ |
| PUT | `/ops/merchant-plans/{merchantNo}/quota` | 单商家额度覆盖 | — | `MerchantPlanRow` | — | ✅ | ✅ |
| GET | `/ops/merchant-plans/upgrade-signals` | 升档信号：一个人名下多个主体 = 他已经在多店经营，只是绕过了额度 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/merchant/apply/{applyNo}/accept` | 受理：告诉商家「有人在看了」 | — | `object` | — | ✅ | ✅ |
| POST | `/ops/merchant/apply/{applyNo}/audit` | 审核 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/merchant/apply/search` | 入驻申请检索 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/merchants` | listMerchants | — | `object` | — | ✅ | ✅ |
| GET | `/ops/merchants/{merchantNo}` | getMerchant | — | `Merchant` | — | ✅ | ✅ |
| POST | `/ops/merchants/{merchantNo}/archive` | archiveMerchant | — | `Merchant` | — | ✅ | ✅ |
| PUT | `/ops/merchants/{merchantNo}/auth-codes` | 全量覆盖经营授权码 | — | `AuthCodeSetResult` | — | ✅ | ✅ |
| GET | `/ops/merchants/{merchantNo}/fulfillment` | 商家履约配置（方案 v4，**只读**）：门店 × 送货方式矩阵 | — | `数组` | — | ✅ | ✅ |
| PUT | `/ops/merchants/{merchantNo}/funds-mode` | 改资金路径 | — | `Merchant` | — | ✅ | ✅ |
| GET | `/ops/merchants/{merchantNo}/qualifications` | 某商家已登记的资质 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/merchants/{merchantNo}/qualifications` | 登记或更新 | — | `Qualification` | — | ✅ | ✅ |
| GET | `/ops/merchants/{merchantNo}/staff` | 这家商家的员工与门店授权（**只读**） | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/merchants/{merchantNo}/status` | 审核推进 | — | `Merchant` | — | ✅ | ✅ |
| GET | `/ops/merchants/{merchantNo}/store-modes` | storeModes | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/merchants/{merchantNo}/unarchive` | unarchiveMerchant | — | `Merchant` | — | ✅ | ✅ |
| POST | `/ops/merchants/{merchantNo}/verified` | 认证标授予/撤销（P-11.1.2） | — | `Merchant` | — | ✅ | ✅ |
| POST | `/ops/merchants/{merchantNo}/violations` | 记一条违规并执行处置 | — | `Violation` | — | ✅ | ✅ |
| GET | `/ops/merchants/auth-codes` | 授权码目录 | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/merchants/mode-risk` | 无照主体 × 自营门店的税务敞口清单 | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/merchants/violations` | listViolations | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/plan-defs` | 档位定义 | — | `数组` | — | ✅ | ✅ |
| PUT | `/ops/plan-defs/{planCode}` | 改档位定义 | — | `PlanDef` | — | ✅ | ✅ |
| POST | `/ops/qualifications/{qualNo}/revoke` | 撤销 | — | `Qualification` | — | ✅ | ✅ |
| PUT | `/ops/stores/{storeNo}/business-mode` | 改门店经营模式 | — | `StoreMode` | — | ✅ | ✅ |
| POST | `/ops/stores/{storeNo}/channels/{channel}/lock` | lockChannel | — | `object` | — | ✅ | ✅ |
| POST | `/ops/stores/{storeNo}/channels/{channel}/unlock` | unlockChannel | — | `object` | — | ✅ | ✅ |

### message（36）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/captcha` | 取一张图形验证码 | — | `Captcha` | — | ✅ | ✅ |
| GET | `/ops/faqs` | listFaqs | — | `object` | — | ✅ | ✅ |
| POST | `/ops/faqs` | 帮助中心（P-14.2.4） | — | `FaqEntry` | — | ✅ | ✅ |
| POST | `/ops/faqs/{no}/published` | setFaqPublished | — | `FaqEntry` | — | ⬜ | ✅ |
| GET | `/ops/inapp-messages` | 站内信记录 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/message` | listInbox | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/message/{no}/read` | readInbox | — | `数组` | — | ⬜ | ✅ |
| POST | `/ops/message/read-all` | readAllInbox | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/message/unread-count` | 未读数 | — | `integer` | — | ✅ | ✅ |
| GET | `/ops/msg-templates` | listMsgTemplates | — | `object` | — | ✅ | ✅ |
| POST | `/ops/msg-templates/{no}/enabled` | setTemplateEnabled | — | `MsgTemplate` | — | ⬜ | ✅ |
| GET | `/ops/notify-channels` | 四条通道的体检：开没开、凭据齐不齐、今天发了多少 | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/notify-channels/default-lang` | getDefaultLang | — | `object` | — | ✅ | ✅ |
| POST | `/ops/notify-channels/default-lang` | saveDefaultLang | — | `object` | — | ✅ | ✅ |
| GET | `/ops/notify-channels/registry` | 渠道注册表（触达推送中台 N2）：类型×供应商×接入范围×归属 + 读时派生状态 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/notify-channels/registry/{channelNo}/enabled` | 软启停某条渠道（N2） | — | `NotifyChannelRow` | — | ✅ | ✅ |
| GET | `/ops/notify-channels/wx-templates` | getWxTemplates | — | `WxTemplates` | — | ✅ | ✅ |
| POST | `/ops/notify-channels/wx-templates` | 保存微信模板号 | — | `WxTemplates` | — | ✅ | ✅ |
| GET | `/ops/notify-logs` | 发送记录（P-14.3） | — | `object` | — | ✅ | ✅ |
| POST | `/ops/notify-logs/precheck` | 收件人预检 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/notify-logs/push-devices` | 某收件人绑定的推送终端列表（仅 PUSH 测试用） | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/notify-logs/test-inapp` | 站内信的模拟发送：往某个收件箱塞一条 | — | `object` | — | ✅ | ✅ |
| POST | `/ops/notify-logs/test-send` | 测试发送 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/notify-quota` | 发送推送（P-14.1.2） | — | `NotifyQuota` | — | ✅ | ✅ |
| POST | `/ops/notify-quota` | 触达频控（P-14.1.4） | — | `NotifyQuota` | — | ✅ | ✅ |
| GET | `/ops/push-tasks` | 营销广播任务列表（N6） | — | `object` | — | ✅ | ✅ |
| POST | `/ops/push-tasks` | 新建广播（N6） | — | `NotifyPushTask` | — | ✅ | ✅ |
| POST | `/ops/push-tasks/{taskNo}/cancel` | 取消广播（仅 QUEUED 可取消） | — | `NotifyPushTask` | — | ✅ | ✅ |
| GET | `/ops/push-tasks/estimate` | 预估触达：**建任务前**先看某人群当下覆盖多少人（N6b） | — | `object` | — | ✅ | ✅ |
| GET | `/ops/scene-channel` | 场景×通道矩阵 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/scene-channel/{scene}/{audience}/{channel}` | 切换某一格 | — | `SceneChannelCell` | — | ✅ | ✅ |
| GET | `/ops/tickets` | listTickets | — | `object` | — | ✅ | ✅ |
| POST | `/ops/tickets/{no}/assign` | 分派工单（P-14.2.1） | — | `Ticket` | — | ⬜ | ✅ |
| POST | `/ops/tickets/{no}/close` | closeTicket | — | `Ticket` | — | ⬜ | ✅ |
| POST | `/ops/tickets/{no}/proxy-actions` | 记录代客操作（P-14.2.3）：谁、对什么、做了什么 | — | `Ticket` | — | ⬜ | ✅ |
| POST | `/ops/tickets/{no}/reply` | 客服回复（P-14.2.2） | — | `Ticket` | — | ⬜ | ✅ |

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

### payment（7）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/payments/close-rule` | getCloseRule | — | `CloseRule` | — | ✅ | ✅ |
| PUT | `/ops/payments/close-rule` | 关单策略（P-4.2.3） | — | `CloseRule` | — | ✅ | ✅ |
| GET | `/ops/payments/recon-axes` | 四条轴各跑一轮 | — | `数组` | — | ✅ | ⬜ |
| GET | `/ops/payments/recon-coverage` | 对账覆盖范围 | — | `ReconCoverage` | — | ✅ | ⬜ |
| GET | `/ops/payments/recon-diffs` | 对账差异列表（P-4.2.1） | — | `object` | — | ✅ | ✅ |
| POST | `/ops/payments/recon-diffs/{diffNo}/ignore` | 忽略一条差异（如渠道手续费导致的分位差） | — | `ReconDiff` | — | ✅ | ✅ |
| POST | `/ops/payments/recon-diffs/{diffNo}/resolve` | 处置一条差异（P-4.2.1 / 4.2.2） | — | `ReconDiff` | — | ✅ | ✅ |

### product（45）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/categories` | 类目树：一次给全量（三级树总量有限，前端自己组树比逐层拉更快） | — | `数组` | — | ✅ | ⬜ |
| POST | `/ops/categories` | 新建 / 改类目 | — | `Category` | — | ✅ | ✅ |
| POST | `/ops/categories/{no}/archive` | 有子类目或有在售商品的类目不能归档 —— 归档后 C 端类目树会断枝 | — | `Category` | — | ⬜ | ✅ |
| GET | `/ops/categories/{no}/archive-impact` | 停用一个类目会影响什么 | — | `CategoryArchiveImpact` | — | ⬜ | ⬜ |
| POST | `/ops/categories/{no}/unarchive` | unarchiveCategory | — | `Category` | — | ⬜ | ✅ |
| GET | `/ops/category-pay-modes` | 类目 × 支付方式 | — | `数组` | — | ✅ | ⬜ |
| POST | `/ops/category-pay-modes/{categoryNo}` | saveCategoryPayMode | — | `数组` | — | ✅ | ⬜ |
| GET | `/ops/category-points` | 类目 × 积分 | — | `数组` | — | ✅ | ⬜ |
| POST | `/ops/category-points/{categoryNo}` | `earnMode` 传 null = 清除这条规则，回到平台兜底 */ | — | `数组` | — | ✅ | ⬜ |
| GET | `/ops/category-specs` | 类目 × 规格总览（规格库 V195） | — | `数组` | — | ✅ | ⬜ |
| POST | `/ops/category-specs/{categoryNo}` | 整份替换一个类目的绑定 */ | — | `数组` | — | ✅ | ⬜ |
| POST | `/ops/community-pool/resync` | 重建社区池（「这件商品出现在哪些社区」的派生索引） | — | `integer` | — | ✅ | ✅ |
| GET | `/ops/goods` | 商品池：按商家/类目/关键词/状态筛，goods 粒度（每行一个商品，SKU 嵌在 `skus[]` 里） | — | `object` | — | ✅ | ⬜ |
| GET | `/ops/goods/{goodsNo}` | 商品详情：三语文案、SKU 矩阵、规格组、驳回原因，审核抽屉读的就是它 | — | `GoodsDetail` | — | ✅ | ✅ |
| POST | `/ops/goods/{goodsNo}/audit` | 审核商品 | — | `GoodsAudit` | — | ✅ | ✅ |
| GET | `/ops/goods/{goodsNo}/draft-preview` | 待审草稿的字段级差异（双版本） | — | `object` | — | ✅ | ✅ |
| POST | `/ops/goods/{goodsNo}/force-off` | 平台强制下架（P-3.2.3），goods 粒度 = **撤销过审**：商品回到 `REJECTED` | — | `GoodsDetail` | — | ✅ | ✅ |
| GET | `/ops/goods/audit-queue` | 待审队列 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/skus` | sku 粒度全量查询 | — | `object` | — | ✅ | ⬜ |
| POST | `/ops/skus/{no}/audit` | 商品审核（P-3.2.2），sku 粒度入口 | — | `Sku` | — | ⬜ | ⬜ |
| POST | `/ops/skus/{no}/force-off` | 平台**压下架**（P-3.2.3）：必须带原因，原样进商家 B 端 | — | `Sku` | — | ⬜ | ⬜ |
| POST | `/ops/skus/{no}/presale` | 预售额度与截单时间（P-3.3.1 / 3.3.2）：截单必须早于到货 | — | `Sku` | — | ⬜ | ⬜ |
| GET | `/ops/skus/oversell` | 超卖告警（P-3.3.3）：已售 > 预售额度 | — | `数组` | — | ✅ | ⬜ |
| GET | `/ops/spec-dims` | listSpecDims | — | `数组` | — | ✅ | ⬜ |
| POST | `/ops/spec-dims` | saveSpecDim | — | `SpecDim` | — | ✅ | ⬜ |
| POST | `/ops/spec-dims/{no}/archive` | archiveSpecDim | — | `SpecDim` | — | ⬜ | ⬜ |
| POST | `/ops/spec-dims/{no}/unarchive` | unarchiveSpecDim | — | `SpecDim` | — | ⬜ | ⬜ |
| GET | `/ops/spec-templates` | 平台模板列表 | — | `object` | — | ✅ | ⬜ |
| POST | `/ops/spec-templates` | 新建或更新（`templateNo` 为空即新建） | — | `SpecTemplate` | — | ✅ | ⬜ |
| POST | `/ops/spec-templates/{no}/archive` | 归档：商家侧立刻不再下发 | — | `SpecTemplate` | — | ⬜ | ⬜ |
| POST | `/ops/spec-templates/{no}/unarchive` | unarchiveSpecTemplate | — | `SpecTemplate` | — | ⬜ | ⬜ |
| POST | `/ops/spec-values` | saveSpecValue | — | `SpecValue` | — | ✅ | ⬜ |
| POST | `/ops/spec-values/{no}/archive` | archiveSpecValue | — | `SpecValue` | — | ⬜ | ⬜ |
| POST | `/ops/spec-values/{no}/promote` | 商家自有值 → 平台值 | — | `SpecValue` | — | ⬜ | ⬜ |
| POST | `/ops/spec-values/{no}/unarchive` | unarchiveSpecValue | — | `SpecValue` | — | ⬜ | ⬜ |
| GET | `/ops/spu-std` | 标准品列表 | — | `object` | — | ✅ | ✅ |
| POST | `/ops/spu-std` | 新建 / 更新 | — | `SpuStd` | — | ✅ | ✅ |
| POST | `/ops/spu-std/{no}/archive` | 归档 | — | `SpuStd` | — | ⬜ | ✅ |
| POST | `/ops/spu-std/{no}/unarchive` | unarchiveSpuStd | — | `SpuStd` | — | ⬜ | ✅ |
| POST | `/ops/spu-std/bulk-status` | 批量改状态 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/topics` | 专题列表 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/topics` | 新建 / 改 | — | `Topic` | — | ✅ | ✅ |
| POST | `/ops/topics/{topicNo}/archived` | 归档 / 取消归档 | — | `Topic` | — | ✅ | ✅ |
| GET | `/ops/topics/{topicNo}/goods` | 专题里的商品，按专题内排序 */ | — | `object` | — | ✅ | ✅ |
| POST | `/ops/topics/{topicNo}/goods` | 整份替换专题里的商品，顺序即展示顺序 | — | `object` | — | ✅ | ✅ |

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
| GET | `/ops/blacklists` | listBlacklists | — | `object` | — | ✅ | ✅ |
| POST | `/ops/blacklists` | 拉黑（P-16.2.4） | — | `BlacklistEntry` | — | ✅ | ✅ |
| POST | `/ops/blacklists/{no}/appeal` | 解禁申诉裁决：接受则解除拉黑，两种结论都要写说明 | — | `BlacklistEntry` | — | ⬜ | ✅ |
| GET | `/ops/risk-events` | listRiskEvents | — | `object` | — | ✅ | ✅ |
| POST | `/ops/risk-events/{no}/decide` | 事件处置（P-16.2.1–3）：确认或排除，都要写结论 | — | `RiskEvent` | — | ⬜ | ✅ |
| GET | `/ops/risk-rules` | listRiskRules | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/risk-rules/{type}` | 拦截规则（P-16.2.5） | — | `RiskRule` | — | ✅ | ✅ |

### store（11）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/stores` | 跨主体门店检索 | — | `object` | — | ✅ | ✅ |
| GET | `/ops/stores/{storeNo}` | 门店档案详情：门面 + 配送规则 + 经营模式 + 收款商户号 | — | `StoreGovern` | — | ✅ | ✅ |
| POST | `/ops/stores/{storeNo}/restore` | 解除门店强制下线，恢复被平台压下的货架行 | — | `StoreGovern` | — | ✅ | ✅ |
| GET | `/ops/stores/{storeNo}/stats` | 门店经营状况：今日/本月订单与 GMV，外加待发货/待自送/缺货三项待办堆积 | — | `StoreStats` | — | ✅ | ✅ |
| GET | `/ops/stores/acquisition` | 门店获客效果（P-10.1.4） | — | `object` | — | ⬜ | ✅ |
| GET | `/ops/stores/audits` | listStoreAudits | — | `object` | — | ✅ | ✅ |
| POST | `/ops/stores/audits/{auditNo}/decide` | 审核裁决（P-10.1.2） | — | `StorePageAudit` | — | ✅ | ✅ |
| GET | `/ops/stores/qrcodes` | 店铺码（P-10.1.3），供 BD 批量导出去印刷 | — | `object` | — | ⬜ | ✅ |
| GET | `/ops/stores/templates` | listStoreTemplates | — | `数组` | — | ⬜ | ✅ |
| POST | `/ops/stores/templates` | 新建/保存模板 | — | `StoreTemplate` | — | ⬜ | ✅ |
| POST | `/ops/stores/templates/{templateNo}/enabled` | 启用/停用模板 | — | `StoreTemplate` | — | ⬜ | ✅ |

### system（26）

| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |
|---|---|---|---|---|:---:|:---:|:---:|
| GET | `/ops/appearance` | getAppearance | — | `AppearanceConfig` | — | ✅ | ✅ |
| POST | `/ops/appearance` | 皮肤下发（P-17.1.1 / C-TH-05） | — | `AppearanceConfig` | — | ✅ | ✅ |
| GET | `/ops/auth-codes` | <b>全量，含停用</b>，带商家数与类目引用数 | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/auth-codes` | 新建或更新 | — | `AuthCodeAdmin` | — | ✅ | ✅ |
| POST | `/ops/auth-codes/{code}/enabled` | 启停 | — | `AuthCodeAdmin` | — | ✅ | ✅ |
| GET | `/ops/feature-flags` | listFeatureFlags | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/feature-flags/{key}` | 开关与灰度（P-17.1.5） | — | `FeatureFlag` | — | ✅ | ✅ |
| GET | `/ops/industries` | listIndustries | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/industries/{industry}/enabled` | 停用后入驻表单里不再出现这个行业 | — | `Industry` | — | ✅ | ✅ |
| POST | `/ops/industries/{industry}/micro-allowed` | 改某通道的小微白名单 | — | `Industry` | — | ✅ | ✅ |
| POST | `/ops/industries/{industry}/points-forced` | 强制开启积分：商家不可自行关闭 */ | — | `Industry` | — | ✅ | ✅ |
| GET | `/ops/markets` | listMarkets | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/markets/{code}` | 市场与汇率（P-17.1.3） | — | `MarketConfig` | — | ✅ | ✅ |
| POST | `/ops/media/backfill` | 磁盘对账：把「磁盘上有、库里没有」的文件补录进来 | — | `MediaBackfillResult` | — | ✅ | ✅ |
| GET | `/ops/media/batches` | listMediaBatches | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/media/batches/{batchNo}` | getMediaBatch | — | `MediaBatchDetail` | — | ✅ | ✅ |
| GET | `/ops/media/overview` | getMediaOverview | — | `MediaOverview` | — | ✅ | ✅ |
| POST | `/ops/media/purge` | 提交回收 | — | `object` | — | ✅ | ✅ |
| POST | `/ops/media/purge/preview` | 预览这一票有多少张、多少字节 | — | `MediaPurgePreview` | — | ✅ | ✅ |
| GET | `/ops/media/reclaimable` | listMediaReclaimable | — | `object` | — | ✅ | ✅ |
| POST | `/ops/media/scan` | 重扫 | — | `MediaScanResult` | — | ✅ | ✅ |
| GET | `/ops/media/stores` | 门店占用 | — | `数组` | — | ✅ | ✅ |
| GET | `/ops/rule-texts` | getRuleTexts | — | `RuleTexts` | — | ✅ | ✅ |
| POST | `/ops/rule-texts` | 规则文案（P-17.1.4） | — | `RuleTexts` | — | ✅ | ✅ |
| GET | `/ops/service-scopes` | listServiceScopes | — | `数组` | — | ✅ | ✅ |
| POST | `/ops/service-scopes/{scope}/enabled` | 开关某一档，返回最新的三档全量 | — | `数组` | — | ✅ | ✅ |
