# C 端功能点 · 登录态 · 页面

> **本文是生成的**：`node scripts/gen-c-feature-matrix.mjs`。不要手改。
> 来源全部取自代码：功能点与登录态取 `c-app/src/api/endpoints.ts`，
> 页面归属扫 `c-app/src/pages/**`（外加 `components/` 与 `stores/`）。
>
> 与 B 端那份的分工：那份的主轴是**权限码**（六角色 × 13 码），
> 而消费者没有角色 —— 照搬会得到一张全是空格的表。
> C 端要回答的是另外两个问题：**要不要登录**、**画在哪一页**。

统计：**83 个功能点**，其中 **21 个游客可用**；**2 个没有任何页面调用**。

## ⚠️ 没有页面调用的功能点

> **做了没出口。** 这一类不报任何错 —— 接口在、契约在、mock 在，
> 只是用户点不到。B 端与运营端各自都栽过一次（运营端 18 条、B 端积分三个接口零页面）。
> 每一条要么给它出口，要么从端点表删掉。

| 功能点 | 方法 | 路径 | 说明 |
|---|---|---|---|
| `myInvoices` | GET | `/mp/invoice/mine` | — |
| `myStores` | GET | `/mp/store/mine` | — |

## 全部功能点

| 功能点 | 路径 | 登录 | 页面 | 说明 |
|---|---|---|---|---|
| `masterData` | `GET /common/master-data` | 游客 | me | — |
| `afterSaleList` | `GET /mp/after-sale` | 是 | orders | — |
| `raiseDispute` | `POST /mp/after-sale/:afterSaleNo/escalate` | 是 | order | — |
| `fillReturnExpress` | `POST /mp/after-sale/:afterSaleNo/ship` | 是 | order | — |
| `afterSaleReasons` | `GET /mp/after-sale/reasons` | 游客 | after-sale | — |
| `myCards` | `GET /mp/card/mine` | 是 | cards | — |
| `cartList` | `GET /mp/cart` | 是 | (stores) | — |
| `cartAdd` | `POST /mp/cart/add` | 是 | (stores) | — |
| `cartRemove` | `POST /mp/cart/remove` | 是 | (stores) | — |
| `cartUpdate` | `POST /mp/cart/update` | 是 | (stores) | — |
| `allCommunities` | `GET /mp/community` | 游客 | (stores) | — |
| `nearbyCommunities` | `GET /mp/community/nearby` | 游客 | (stores) | — |
| `openRegions` | `GET /mp/community/regions` | 游客 | (stores) | — |
| `couponList` | `GET /mp/coupon` | 游客 | coupons · order-confirm | — |
| `receiveCoupon` | `POST /mp/coupon/:couponNo/receive` | 是 | coupons | — |
| `goodsList` | `GET /mp/goods` | 游客 | category · groups · home · merchant · search | — |
| `goodsDetail` | `GET /mp/goods/:goodsNo` | 游客 | goods | — |
| `promotedGoods` | `GET /mp/goods/promoted` | 游客 | home | — |
| `groupBuyList` | `GET /mp/group-buy` | 游客 | groups · home | — |
| `createGroupBuy` | `POST /mp/group-buy` | 是 | groups | — |
| `groupBuyDetail` | `GET /mp/group-buy/:groupNo` | 游客 | group | — |
| `joinGroupBuy` | `POST /mp/group-buy/:groupNo/join` | 是 | group | — |
| `groupPickupOrders` | `GET /mp/group-buy/:groupNo/orders` | 是 | group-host | — |
| `confirmGroupBatch` | `POST /mp/group-buy/:groupNo/receive` | 是 | group-host | — |
| `verifyGroupPickup` | `POST /mp/group-buy/:groupNo/verify` | 是 | group-host | — |
| `myHostedGroups` | `GET /mp/group-buy/hosted` | 是 | group-host | — |
| `requestList` | `GET /mp/group-request` | 游客 | groups | — |
| `createRequest` | `POST /mp/group-request` | 是 | request-create | — |
| `requestDetail` | `GET /mp/group-request/:requestNo` | 游客 | request | — |
| `chooseQuote` | `POST /mp/group-request/:requestNo/choose` | 是 | request | — |
| `confirmRequest` | `POST /mp/group-request/:requestNo/confirm` | 是 | request | — |
| `toggleInterest` | `POST /mp/group-request/:requestNo/interest` | 是 | request | — |
| `applyInvoice` | `POST /mp/invoice/apply` | 是 | order | — |
| `myInvoices` | `GET /mp/invoice/mine` | 是 | **无** | — |
| `invoiceOfOrder` | `GET /mp/invoice/order/:orderNo` | 是 | order | — |
| `merchantList` | `GET /mp/merchant` | 游客 | merchants · search | — |
| `merchantDetail` | `GET /mp/merchant/:merchantNo` | 游客 | merchant | — |
| `merchantApply` | `POST /mp/merchant/apply` | 是 | me | — |
| `myMerchantApply` | `GET /mp/merchant/apply` | 是 | me | — |
| `promotedMerchants` | `GET /mp/merchant/promoted` | 游客 | merchants | — |
| `visitedMerchants` | `GET /mp/merchant/visited` | 是 | merchants | — |
| `messageList` | `GET /mp/message` | 是 | messages | — |
| `readMessage` | `POST /mp/message/:messageNo/read` | 是 | messages | — |
| `readAllMessages` | `POST /mp/message/read-all` | 是 | messages | — |
| `subscribeReport` | `POST /mp/message/subscribe` | 是 | pay | — |
| `unreadMessages` | `GET /mp/message/unread-count` | 是 | me | — |
| `myStoreCoupons` | `GET /mp/my-coupons` | 是 | coupons | — |
| `myMemberships` | `GET /mp/my-memberships` | 是 | my-memberships | — |
| `setMembershipReach` | `PUT /mp/my-memberships/:entityNo/reach` | 是 | my-memberships | — |
| `createOrder` | `POST /mp/order` | 是 | order-confirm | — |
| `orderList` | `GET /mp/order` | 是 | orders · store | — |
| `orderDetail` | `GET /mp/order/:orderNo` | 是 | after-sale · order · pay · review-write | — |
| `applyAfterSale` | `POST /mp/order/:orderNo/after-sale` | 是 | after-sale | — |
| `cancelOrder` | `POST /mp/order/:orderNo/cancel` | 是 | order · pay | — |
| `payOrder` | `POST /mp/order/:orderNo/pay` | 是 | pay | — |
| `reorderFrom` | `POST /mp/order/:orderNo/reorder` | 是 | store | — |
| `orderCapability` | `POST /mp/order/capability` | 是 | order-confirm | — |
| `orderPreview` | `POST /mp/order/preview` | 是 | order-confirm | — |
| `pointAccount` | `GET /mp/points/account` | 是 | me · order-confirm · points | — |
| `pointsDeductible` | `GET /mp/points/deductible` | 是 | order-confirm | — |
| `pointRecords` | `GET /mp/points/records` | 是 | points | — |
| `registerPushToken` | `POST /mp/push-token` | 是 | (stores) | — |
| `unregisterPushToken` | `POST /mp/push-token/unregister` | 是 | (stores) | — |
| `reviewList` | `GET /mp/review` | 游客 | goods · merchant | — |
| `createReview` | `POST /mp/review` | 是 | review-write | — |
| `toggleReviewLike` | `POST /mp/review/:reviewNo/like` | 是 | goods · merchant | — |
| `storeHome` | `GET /mp/store/:merchantNo` | 游客 | store | — |
| `toggleFavoriteStore` | `POST /mp/store/:merchantNo/favorite` | 是 | store | — |
| `frequentItems` | `GET /mp/store/:merchantNo/frequent` | 是 | store | — |
| `myStores` | `GET /mp/store/mine` | 是 | **无** | — |
| `addressList` | `GET /mp/user/address` | 是 | address · order-confirm | — |
| `saveAddress` | `POST /mp/user/address` | 是 | address | — |
| `removeAddress` | `POST /mp/user/address/:addressId/archive` | 是 | address | — |
| `setDefaultAddress` | `POST /mp/user/address/:addressId/default` | 是 | address | — |
| `bindCommunity` | `POST /mp/user/community` | 是 | (stores) | — |
| `deregister` | `POST /mp/user/deregister` | 是 | me | — |
| `login` | `POST /mp/user/login` | 游客 | (stores) | — |
| `logout` | `POST /mp/user/logout` | 是 | (stores) | — |
| `sendOtp` | `POST /mp/user/otp/send` | 游客 | (components) · login | — |
| `bindPhone` | `POST /mp/user/phone/bind` | 是 | (components) | — |
| `phoneCapable` | `GET /mp/user/phone/capable` | 游客 | (components) | — |
| `bindPhoneByWx` | `POST /mp/user/phone/wx` | 是 | (components) | — |
| `profile` | `GET /mp/user/profile` | 是 | (stores) | — |
