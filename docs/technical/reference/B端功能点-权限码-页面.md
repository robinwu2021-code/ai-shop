# B 端功能点 · 权限码 · 页面

状态：**已落地（六角色判权 + 13 权限码）· 产物随代码重生成**

> **本文是生成的**：`node scripts/gen-biz-feature-perm-matrix.mjs`。不要手改。

> 四份来源全部取自代码：权限码与含义取 `BizPerms`，角色→码取 `BizPerms.ROLE_PERMS`，
> 端点→码取 `BizEndpointPermTest.REQUIRED`（唯一被守卫强制对过账的那份），
> **功能点中文名与页面归属取 `b-app/src/api/endpoints.ts` 与 `b-app/src/pages/`**。

> 与 [B端功能矩阵-按角色](./B端功能矩阵-按角色.md) 的分工：那份是**角色视角**
> （谁能碰哪些路径），这份是**功能视角**（哪个功能点归哪个码、画在哪一页）。

统计：**13 个权限码 × 6 个角色 × 63 个受控功能点**
（另有 12 个登录即可、1 个「任一权限即可」）。

## 一、权限码总表

| 权限码 | 常量 | 含义 | 功能点数 | 老板 | 店长 | 店员 | 理货员 | 配送员 | 客服 |
|---|---|---|---|---|---|---|---|---|---|
| `biz:finance` | `FINANCE` | 结算账单、费率卡、收款进件、积分开关 | 14 | ✅ | — | — | — | — | — |
| `biz:store:admin` | `STORE_ADMIN` | 建店、改名、停用、设默认店、挂收款号 | 9 | ✅ | — | — | — | — | — |
| `biz:campaign` | `CAMPAIGN` | 营销活动、开团、报价 | 6 | ✅ | ✅ | — | — | — | — |
| `biz:store` | `STORE` | 门店经营面：装修、配送规则、店铺码、分享物料 | 6 | ✅ | ✅ | — | — | — | — |
| `biz:verify` | `VERIFY` | 核销、批量核销、按码搜索 | 5 | ✅ | ✅ | ✅ | — | — | — |
| `biz:stock` | `STOCK` | 改库存（含门店库存） | 4 | ✅ | ✅ | ✅ | ✅ | — | — |
| `biz:aftersale` | `AFTERSALE` | 售后同意/驳回/收货 | 4 | ✅ | ✅ | — | — | — | ✅ |
| `biz:receive` | `RECEIVE` | 到货登记、分拣单、短少上报 | 3 | ✅ | ✅ | ✅ | ✅ | — | — |
| `biz:goods` | `GOODS` | 建/改商品、上下架、规格模板、识图 | 3 | ✅ | ✅ | — | — | — | — |
| `biz:review` | `REVIEW` | 评价回复、差评申诉 | 3 | ✅ | ✅ | — | — | — | ✅ |
| `biz:ship` | `SHIP` | 发货、标记自送送达 | 2 | ✅ | ✅ | ✅ | — | ✅ | — |
| `biz:order:view` | `ORDER_VIEW` | 订单列表与详情、工作台待办 | 2 | ✅ | ✅ | ✅ | — | ✅ | ✅ |
| `biz:customer` | `CUSTOMER` | 顾客列表（含累计消费额）、经营数据 | 2 | ✅ | ✅ | — | — | — | — |

> `OWNER` 是 `*`：**不走这张表**。新增权限码时老板自动有，其余角色要显式加。

## 二、功能点明细（按权限码分组）

「页面」列为空 = **后端有能力、b-app 没有入口**，见 §四。

### `biz:finance`　结算账单、费率卡、收款进件、积分开关

**可用角色**：老板

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 收款进件状态 | GET | `/biz/merchant/payment` | `mPayments` | home、payment、stores |
| 补交资料并提交进件 | POST | `/biz/merchant/payment` | `mSubmitPayment` | payment |
| 回查进件结果 | POST | `/biz/merchant/payment/:payChannel/refresh` | `mRefreshPayment` | payment |
| 本期发分服务费与开关状态 | GET | `/biz/points/account` | `mPointsAccount` | settle |
| 发分服务费明细（按单） | GET | `/biz/points/records` | `mPointsRecords` | settle |
| 开/关本店积分 | POST | `/biz/points/toggle` | `mPointsToggle` | settle |
| 结算单列表 | GET | `/biz/settle/bills` | `mSettleList` | settle |
| 费率卡 | GET | `/biz/settle/rate-card` | `mRateCard` | settle |
| —（b-app 未接） | — | `/biz/settle/bills/{}` | — | — |
| —（b-app 未接） | — | `/biz/settle/invoice-title` | — | — |
| —（b-app 未接） | — | `/biz/settle/invoices` | — | — |
| —（b-app 未接） | — | `/biz/settle/statement` | — | — |
| —（b-app 未接） | — | `/biz/merchant/payment/store/{}` | — | — |
| —（b-app 未接） | — | `/biz/deposit` | — | — |
| —（b-app 未接） | — | `/biz/deposit/txns` | — | — |

### `biz:store:admin`　建店、改名、停用、设默认店、挂收款号

**可用角色**：老板

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 员工列表 | GET | `/biz/staff` | `mStaffList` | staff |
| 加员工 | POST | `/biz/staff` | `mAddStaff` | staff |
| 停用/启用员工 | POST | `/biz/staff/:mchAccountNo/status` | `mSetStaffStatus` | staff |
| 授权到店 | POST | `/biz/staff/:mchAccountNo/store` | `mGrantStore` | staff |
| 员工与授权变更记录 | GET | `/biz/staff/logs` | `mStaffLogs` | staff |
| 设为默认店 | POST | `/biz/store/:storeNo/default` | `mSetDefaultStore` | stores |
| 换门店收款号 | POST | `/biz/store/:storeNo/payment` | `mSetStorePayment` | stores |
| 改门店名与地址 | POST | `/biz/store/:storeNo/rename` | `mRenameStore` | stores |
| 停用/启用门店 | POST | `/biz/store/:storeNo/status` | `mSetStoreStatus` | stores |
| 新建门店 | POST | `/biz/store/create` | `mCreateStore` | stores |

### `biz:campaign`　营销活动、开团、报价

**可用角色**：老板、店长

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 营销活动列表 | GET | `/biz/campaign` | `mCampaignList` | marketing |
| 新建/编辑活动 | POST | `/biz/campaign` | `mSaveCampaign` | marketing |
| 活动启停 | POST | `/biz/campaign/:campaignNo/toggle` | `mToggleCampaign` | marketing |
| 报价 | POST | `/biz/group-request/:requestNo/quote` | `mQuote` | quotes |
| 可报价需求单 | GET | `/biz/group-request/pool` | `mRequestList` | quotes |
| 我的商家团 | GET | `/biz/groups` | `mGroupList` | groups |
| 开团 | POST | `/biz/groups` | `mCreateGroup` | groups |
| —（b-app 未接） | — | `/biz/quote/{}/revise` | — | — |

### `biz:store`　门店经营面：装修、配送规则、店铺码、分享物料

**可用角色**：老板、店长

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 我提报过的小区 | GET | `/biz/communities/applies` | `mMyCommunityApplies` | store |
| 提报平台还没有的小区 | POST | `/biz/communities/apply` | `mApplyCommunity` | store |
| 自送规则 | GET | `/biz/delivery/rule` | `mDeliveryRule` | delivery |
| 保存自送规则 | POST | `/biz/delivery/rule` | `mSaveDeliveryRule` | delivery |
| 店铺门面 | GET | `/biz/store` | `mStore` | home、store |
| 保存店铺门面 | POST | `/biz/store` | `mSaveStore` | store |
| 店铺码 | GET | `/biz/store/qrcode` | `mStoreQrcode` | store |
| 分享素材 | GET | `/biz/store/share-kit` | `mShareKit` | store |

### `biz:verify`　核销、批量核销、按码搜索

**可用角色**：老板、店长、店员

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 本自提点订单 | GET | `/biz/pickup/orders` | `mPickupOrders` | picking、verify |
| 自提点履约总览 | GET | `/biz/pickup/overview` | `mPickupOverview` | verify |
| 核销自提码 | POST | `/biz/pickup/verify` | `mVerify` | verify |
| 批量核销 | POST | `/biz/pickup/verify/batch` | `mVerifyBatch` | verify |
| 按取货码片段搜单 | GET | `/biz/pickup/verify/search` | `mVerifySearch` | verify |

### `biz:stock`　改库存（含门店库存）

**可用角色**：老板、店长、店员、理货员

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 商品列表 | GET | `/biz/goods` | `mGoodsList` | goods-list、groups、marketing |
| 商品详情 | GET | `/biz/goods/:goodsNo` | `mGoodsDetail` | goods-edit |
| 改库存 | POST | `/biz/goods/:goodsNo/stock` | `mSaveStock` | goods-list |
| 改当前门店库存 | POST | `/biz/goods/:goodsNo/store-stock` | `mSaveStoreStock` | goods-list |

### `biz:aftersale`　售后同意/驳回/收货

**可用角色**：老板、店长、客服

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 待处理售后 | GET | `/biz/after-sale` | `mAfterSaleList` | after-sale、orders |
| 同意售后 | POST | `/biz/after-sale/:afterSaleNo/approve` | `mApproveAfterSale` | after-sale |
| 确认收到退货 | POST | `/biz/after-sale/:afterSaleNo/receive` | `mConfirmReturn` | after-sale |
| 驳回售后 | POST | `/biz/after-sale/:afterSaleNo/reject` | `mRejectAfterSale` | after-sale |

### `biz:receive`　到货登记、分拣单、短少上报

**可用角色**：老板、店长、店员、理货员

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 破损短少上报 | POST | `/biz/pickup/:orderNo/report` | `mReportShortage` | picking |
| 标记到货 | POST | `/biz/pickup/arrived` | `mMarkArrived` | picking |
| 分拣单 | GET | `/biz/pickup/picking` | `mPickingList` | picking |

### `biz:goods`　建/改商品、上下架、规格模板、识图

**可用角色**：老板、店长

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 上下架 | POST | `/biz/goods/:goodsNo/toggle` | `mToggleGoods` | goods-list |
| 拍照识别商品 | POST | `/biz/goods/recognize` | `mRecognizeGoods` | goods-edit |
| 新建/编辑商品 | POST | `/biz/goods/save` | `mSaveGoods` | goods-edit |

### `biz:review`　评价回复、差评申诉

**可用角色**：老板、店长、客服

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 评价列表 | GET | `/biz/review` | `mReviewList` | reviews |
| 申诉差评 | POST | `/biz/review/:reviewNo/appeal` | `mAppealReview` | reviews |
| 回复评价 | POST | `/biz/review/:reviewNo/reply` | `mReplyReview` | reviews |

### `biz:ship`　发货、标记自送送达

**可用角色**：老板、店长、店员、配送员

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 自送已送达 | POST | `/biz/order/:orderNo/delivered` | `mDelivered` | delivery、order |
| 快递发货 | POST | `/biz/order/:orderNo/ship` | `mShip` | order |

### `biz:order:view`　订单列表与详情、工作台待办

**可用角色**：老板、店长、店员、配送员、客服

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 订单列表 | GET | `/biz/order` | `mOrderList` | after-sale、delivery、orders |
| 订单详情 | GET | `/biz/order/:orderNo` | `mOrderDetail` | order |

### `biz:customer`　顾客列表（含累计消费额）、经营数据

**可用角色**：老板、店长

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 客户与复购 | GET | `/biz/customers` | `mCustomers` | customers |
| 经营数据 | GET | `/biz/dashboard/stats` | `mStats` | home、stats |

## 三、页面 × 门禁　—— 前端裁剪与后端判权对不对得上

**页面的 `denied` 门禁必须 ⊇ 该页所有调用所需的权限码。**
不满足时的表现不是「拒绝」而是「整页空白」：后端返回 70006，
而页面把它 catch 成空数据或整个 `Promise.all` reject。

「⚠ 会撞码」列里的角色**进得了这一页，但页面里有他打不通的请求**。

| 页面 | 门禁 | 该页需要的码 | 进得来的角色 | ⚠ 会撞码 |
|---|---|---|---|---|
| `after-sale` | `biz:aftersale` | `biz:aftersale`、`biz:order:view` | 老板、店长、客服 | — |
| `customers` | `biz:customer` | `biz:customer` | 老板、店长 | — |
| `delivery` | `biz:ship` | `biz:store`、`biz:order:view`、`biz:ship` | 老板、店长、店员、配送员 | 店员（缺 biz:store）　配送员（缺 biz:store） |
| `goods-edit` | `biz:goods` | `biz:goods`、`biz:stock` | 老板、店长 | — |
| `goods-list` | `biz:stock` | `biz:stock`、`biz:goods` | 老板、店长、店员、理货员 | 店员（缺 biz:goods）　理货员（缺 biz:goods） |
| `groups` | `biz:campaign` | `biz:campaign`、`biz:stock` | 老板、店长 | — |
| `home` | **无** | `biz:customer`、`biz:finance`、`biz:store` | 老板、店长、店员、理货员、配送员、客服 | 店长（缺 biz:finance）　店员（缺 biz:customer、biz:finance、biz:store）　理货员（缺 biz:customer、biz:finance、biz:store）　配送员（缺 biz:customer、biz:finance、biz:store）　客服（缺 biz:customer、biz:finance、biz:store） |
| `marketing` | `biz:campaign` | `biz:campaign`、`biz:stock` | 老板、店长 | — |
| `order` | `biz:order:view` | `biz:order:view`、`biz:ship` | 老板、店长、店员、配送员、客服 | 客服（缺 biz:ship） |
| `orders` | `biz:order:view` | `biz:aftersale`、`biz:order:view` | 老板、店长、店员、配送员、客服 | 店员（缺 biz:aftersale）　配送员（缺 biz:aftersale） |
| `payment` | `biz:finance` | `biz:finance` | 老板 | — |
| `picking` | `biz:receive` | `biz:receive`、`biz:verify` | 老板、店长、店员、理货员 | 理货员（缺 biz:verify） |
| `quotes` | `biz:campaign` | `biz:campaign` | 老板、店长 | — |
| `reviews` | `biz:review` | `biz:review` | 老板、店长、客服 | — |
| `settle` | `biz:finance` | `biz:finance` | 老板 | — |
| `staff` | `biz:store:admin` | `biz:store:admin` | 老板 | — |
| `stats` | `biz:customer` | `biz:customer` | 老板、店长 | — |
| `store` | `biz:store` | `biz:store` | 老板、店长 | — |
| `stores` | `biz:store:admin` | `biz:finance`、`biz:store:admin` | 老板 | — |
| `verify` | `biz:verify` | `biz:verify` | 老板、店长、店员 | — |

> 「进得来的角色」只按 `denied` 门禁算，**不含页面内部按 `can()` 逐块裁的部分** ——
> 工作台那种「每个格子跟着自己的权限走」的写法在这张表里会显示为「会撞码」，但它是对的。
> 这张表定位问题，不定罪。

## 四、后端有能力、b-app 没有入口

| 端点 | 权限码 | 可用角色 |
|---|---|---|
| `/biz/deposit` | `biz:finance` | 老板 |
| `/biz/deposit/txns` | `biz:finance` | 老板 |
| `/biz/merchant/payment/store/{}` | `biz:finance` | 老板 |
| `/biz/quote/{}/revise` | `biz:campaign` | 老板、店长 |
| `/biz/settle/bills/{}` | `biz:finance` | 老板 |
| `/biz/settle/invoice-title` | `biz:finance` | 老板 |
| `/biz/settle/invoices` | `biz:finance` | 老板 |
| `/biz/settle/statement` | `biz:finance` | 老板 |

> 这些是「写了、测了、没人调用」—— 要么排期接上，要么从 `REQUIRED` 删掉。
> `noStaleEntries` 只拦已删除的端点，**拦不住「端点还在但没人用」**。

## 五、登录即可（不需要授权）

**空角色的人也能调**，所以这张表的每一条都要能回答「为什么它不需要权限」。

| 端点 | 功能点 |
|---|---|
| `/biz/auth/login` | 商家登录 |
| `/biz/auth/otp/send` | 发送验证码 |
| `/biz/auth/staff-login` | 员工登录 |
| `/biz/category/tree` | 类目树（选类目） |
| `/biz/communities` | 可选社区（设经营范围用） |
| `/biz/context` | 我的作用域与权限 |
| `/biz/merchant/apply` | 提交入驻申请 |
| `/biz/merchant/profile` | 商家资料 |
| `/biz/regions` | 行政区划下一级（框覆盖范围用） |
| `/biz/spec-templates` | 规格模板 |
| `/biz/store/list` | 我的门店 |
| `/biz/upload/image` | 上传商品图 |

## 六、任一权限即可（汇总型端点）

一次返回好几件互不相干的事，**粒度由端上按 `perms` 裁**。
仍然要求「在这家店有角色」——空角色的人一样进不来。

- `/biz/dashboard/todo`　工作台待办

