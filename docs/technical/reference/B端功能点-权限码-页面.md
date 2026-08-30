# B 端功能点 · 权限码 · 页面

状态：**已落地（六角色判权 + 13 权限码）· 产物随代码重生成**

> **本文是生成的**：`node scripts/gen-biz-feature-perm-matrix.mjs`。不要手改。

> 四份来源全部取自代码：权限码与含义取 `BizPerms`，角色→码取 `BizPerms.ROLE_PERMS`，
> 端点→码取 `BizEndpointPermTest.REQUIRED`（唯一被守卫强制对过账的那份），
> **功能点中文名与页面归属取 `b-app/src/api/endpoints.ts` 与 `b-app/src/pages/`**。

> 与 [B端功能矩阵-按角色](./B端功能矩阵-按角色.md) 的分工：那份是**角色视角**
> （谁能碰哪些路径），这份是**功能视角**（哪个功能点归哪个码、画在哪一页）。

统计：**13 个权限码 × 6 个角色 × 167 个受控功能点**
（另有 28 个登录即可、1 个「任一权限即可」）。

> ⚠️ 角色列只有 6 个平台预置角色。商家自定义角色（V71 `mch_role`）按主体存库，
> 不在这份生成物里 —— 但它们能勾的权限点就是本表第一列（少一个 `biz:store:admin`）。

## 一、权限码总表

| 权限码 | 常量 | 含义 | 功能点数 | 老板 | 店长 | 店员 | 理货员 | 配送员 | 客服 |
|---|---|---|---|---|---|---|---|---|---|
| `biz:stock` | `STOCK` | 改库存（含门店库存） | 31 | ✅ | ✅ | ✅ | ✅ | — | — |
| `biz:goods` | `GOODS` | 建/改商品、上下架、规格模板、识图 | 26 | ✅ | ✅ | — | — | — | — |
| `biz:store` | `STORE` | 门店经营面：装修、配送规则、店铺码、分享物料 | 19 | ✅ | ✅ | — | — | — | — |
| `biz:store:admin` | `STORE_ADMIN` | 建店、改名、停用、设默认店、挂收款号 | 19 | ✅ | — | — | — | — | — |
| `biz:customer` | `CUSTOMER` | 顾客列表（含累计消费额）、经营数据 | 18 | ✅ | ✅ | — | — | — | — |
| `biz:campaign` | `CAMPAIGN` | 营销活动、开团、报价 | 16 | ✅ | ✅ | — | — | — | — |
| `biz:finance` | `FINANCE` | 结算账单、费率卡、收款进件、积分开关 | 16 | ✅ | — | — | — | — | — |
| `biz:verify` | `VERIFY` | 核销、批量核销、按码搜索 | 7 | ✅ | ✅ | ✅ | — | — | — |
| `biz:receive` | `RECEIVE` | 到货登记、分拣单、短少上报 | 4 | ✅ | ✅ | ✅ | ✅ | — | — |
| `biz:aftersale` | `AFTERSALE` | 售后同意/驳回/收货 | 4 | ✅ | ✅ | — | — | — | ✅ |
| `biz:review` | `REVIEW` | 评价回复、差评申诉 | 3 | ✅ | ✅ | — | — | — | ✅ |
| `biz:ship` | `SHIP` | 发货、标记自送送达 | 2 | ✅ | ✅ | ✅ | — | ✅ | — |
| `biz:order:view` | `ORDER_VIEW` | 订单列表与详情、工作台待办 | 2 | ✅ | ✅ | ✅ | — | ✅ | ✅ |

> `OWNER` 是 `*`：**不走这张表**。新增权限码时老板自动有，其余角色要显式加。

## 二、功能点明细（按权限码分组）

「页面」列为空 = **后端有能力、b-app 没有入口**，见 §四。

### `biz:stock`　改库存（含门店库存）

**可用角色**：老板、店长、店员、理货员

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 承运方可选列表（只列启用的） | GET | `/biz/fulfillment/carriers` | `mCarriers` | transfer |
| 商品列表 | GET | `/biz/goods` | `mGoodsList` | goods-list、groups、marketing |
| 商品详情 | GET | `/biz/goods/:goodsNo` | `mGoodsDetail` | goods-edit、goods-publish |
| 改库存 | POST | `/biz/goods/:goodsNo/stock` | `mSaveStock` | goods-list |
| 改当前门店库存 | POST | `/biz/goods/:goodsNo/store-stock` | `mSaveStoreStock` | goods-list |
| 直接改数（走盘点，落单落流水） | POST | `/biz/inventory/adjust` | `mStockAdjust` | stock-detail |
| 库存列表（默认只给要处理的） | GET | `/biz/inventory/balances` | `mStockBalances` | stock、stock-out、transfer |
| 开盘点单（锁账面数） | POST | `/biz/inventory/counts` | `mCountOpen` | stock-check |
| 读回盘点单（含账面快照） | GET | `/biz/inventory/counts/:no` | `mCountDetail` | stock-check |
| 填实盘数 | PUT | `/biz/inventory/counts/:no/lines` | `mCountFill` | stock-check |
| 盘点过账 | POST | `/biz/inventory/counts/:no/post` | `mCountPost` | stock-check |
| 出入库单据 | GET | `/biz/inventory/documents` | `mStockDocuments` | stock-docs |
| 记一笔进货 | POST | `/biz/inventory/inbounds` | `mInboundCreate` | purchase-edit |
| 改进货草稿 | PUT | `/biz/inventory/inbounds/:no` | `mInboundUpdate` | — |
| 进货过账 | POST | `/biz/inventory/inbounds/:no/post` | `mInboundPost` | purchase-edit |
| 作废入库单 | POST | `/biz/inventory/inbounds/:no/void` | `mInboundVoid` | stock-docs |
| 单件库存明细 | GET | `/biz/inventory/items/:itemId` | `mStockItem` | stock-detail |
| 库存变动明细 | GET | `/biz/inventory/ledger` | `mStockLedger` | stock-detail、stock-docs |
| 库位与仓 | GET | `/biz/inventory/locations` | `mStockLocations` | locations、transfer |
| 加一个仓 | POST | `/biz/inventory/locations` | `mWarehouseCreate` | locations |
| 报损/领用出库 | POST | `/biz/inventory/outbounds` | `mOutboundCreate` | stock-out |
| 出库过账 | POST | `/biz/inventory/outbounds/:no/post` | `mOutboundPost` | stock-out |
| 作废出库单 | POST | `/biz/inventory/outbounds/:no/void` | `mOutboundVoid` | stock-docs |
| 可挑的货（含 0 库存，从物料出发） | GET | `/biz/inventory/pickable` | `mStockPickable` | purchase-edit、stock-check |
| 库存总览三个数 | GET | `/biz/inventory/summary` | `mStockSummary` | home、stock |
| 供应商档案（挑供应商传 activeOnly=true） | GET | `/biz/inventory/suppliers` | `mSuppliers` | purchase-edit、suppliers |
| 建供应商档案 | POST | `/biz/inventory/suppliers` | `mSupplierCreate` | purchase-edit、suppliers |
| 改供应商档案（引用平台档案的只能改备注） | PUT | `/biz/inventory/suppliers/:no` | `mSupplierUpdate` | suppliers |
| 停用 / 启用供应商 | POST | `/biz/inventory/suppliers/:no/active` | `mSupplierActive` | suppliers |
| 建调拨单 | POST | `/biz/inventory/transfers` | `mTransferCreate` | transfer |
| 读回调拨单 | GET | `/biz/inventory/transfers/:no` | `mTransferDetail` | transfer |
| 调拨收货 | POST | `/biz/inventory/transfers/:no/receive` | `mTransferReceive` | transfer |
| 调拨发出 | POST | `/biz/inventory/transfers/:no/ship` | `mTransferShip` | transfer |

### `biz:goods`　建/改商品、上下架、规格模板、识图

**可用角色**：老板、店长

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 读草稿（编辑页回填） | GET | `/biz/goods/:goodsNo/draft` | `mGoodsDraft` | goods-edit |
| 放弃草稿（线上不动，幂等） | POST | `/biz/goods/:goodsNo/draft/discard` | `mDiscardGoodsDraft` | goods-publish |
| 改截单与到货说明 | POST | `/biz/goods/:goodsNo/presale` | `mSavePresale` | — |
| 发布草稿（原子换版；冲突后带 confirmVersion） | POST | `/biz/goods/:goodsNo/publish` | `mPublishGoods` | goods-publish |
| 发布预览（字段级差异） | GET | `/biz/goods/:goodsNo/publish-preview` | `mPublishPreview` | goods-publish |
| 改当前门店售价 | POST | `/biz/goods/:goodsNo/store-price` | `mSaveStorePrice` | goods-list |
| 提交审核（草稿→待审） | POST | `/biz/goods/:goodsNo/submit` | `mSubmitGoods` | goods-edit、goods-list |
| 上下架 | POST | `/biz/goods/:goodsNo/toggle` | `mToggleGoods` | goods-list |
| 自动生成图文详情 | POST | `/biz/goods/describe` | `mDescribeGoods` | goods-edit |
| 拍照识别商品 | POST | `/biz/goods/recognize` | `mRecognizeGoods` | goods-edit |
| 新建/编辑商品 | POST | `/biz/goods/save` | `mSaveGoods` | goods-edit |
| 我建的规格维度（含用量与配额） | GET | `/biz/my-spec-dims` | `mMySpecDims` | my-specs |
| 停用/启用自建维度 | POST | `/biz/my-spec-dims/{dimNo}/archive` | `mArchiveSpecDim` | — |
| 给自建维度改名 | POST | `/biz/my-spec-dims/{dimNo}/rename` | `mRenameSpecDim` | — |
| 还能加进这一类的商品参数（本类目已配 + 平台通用 + 自建） | GET | `/biz/pickable-props` | `mPickableProps` | my-specs |
| 导出本店全部规格行的条码/货号/单位 | GET | `/biz/sku-identity/export` | `mSkuIdentityExport` | sku-identity |
| 商品编码批量导入 | POST | `/biz/sku-identity/import` | `mSkuIdentityImport` | sku-identity |
| 商品编码导入试算（不写库） | POST | `/biz/sku-identity/import/plan` | `mSkuIdentityPlan` | sku-identity |
| 加规格组时能挑的维度（本类目已配 + 平台通用 + 自建） | GET | `/biz/spec-dims` | `mPickableDims` | goods-edit、my-specs |
| 自建规格维度（只本店可用） | POST | `/biz/spec-dims` | `mAddSpecDim` | goods-edit、my-specs |
| 某个规格下平台有的全部档位（加档位的候选） | GET | `/biz/spec-dims/{dimNo}/values` | `mDimValues` | goods-edit、my-specs |
| 本店用哪几个规格、什么顺序、叫什么 | POST | `/biz/spec-override/{categoryNo}` | `mSaveSpecOverride` | goods-edit、my-specs |
| 这一类的商品参数（产地/保质期/材质，不分 SKU） | GET | `/biz/spec-props` | `mSpecProps` | goods-edit |
| 规格模板 | GET | `/biz/spec-templates` | `mSpecTemplates` | goods-edit |
| 存为常用规格 | POST | `/biz/spec-templates` | `mSaveSpecTemplate` | — |
| 在平台维度下加一个自有规格值 | POST | `/biz/spec-values` | `mAddSpecValue` | goods-edit、my-specs |
| 标准品搜索（建品用） | GET | `/biz/spu-std` | `mSpuStdSearch` | goods-edit |
| 本店货架类目各自能用的规格 | GET | `/biz/store-spec-dims` | `mStoreSpecDims` | my-specs |

### `biz:store`　门店经营面：装修、配送规则、店铺码、分享物料

**可用角色**：老板、店长

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 停约 | POST | `/biz/appointment-slots/:slotNo/close` | `mCloseAppointmentSlot` | schedule |
| 我提报过的小区 | GET | `/biz/communities/applies` | `mMyCommunityApplies` | store-scope |
| 提报平台还没有的小区 | POST | `/biz/communities/apply` | `mApplyCommunity` | — |
| 地图上选中的小区直接开通 | POST | `/biz/communities/from-map` | `mOpenCommunityFromMap` | — |
| 自送规则 | GET | `/biz/delivery/rule` | `mDeliveryRule` | delivery、store-scope |
| 保存自送规则 | POST | `/biz/delivery/rule` | `mSaveDeliveryRule` | delivery、store-scope |
| 自建自提点（待运营核实） | POST | `/biz/pickup-points` | `mSelfBuildPickup` | — |
| 门店可引用的取货点候选 | GET | `/biz/pickup-points/candidates` | `mPickupCandidates` | — |
| 我的资质与已获授权的类目 | GET | `/biz/qualifications` | `mQualifications` | entity-detail、qualifications |
| 传一张资质证件 | POST | `/biz/qualifications/save` | `mSaveQualification` | qualifications |
| 店铺门面 | GET | `/biz/store` | `mStore` | home、store、store-notice、store-scope |
| 保存店铺门面 | POST | `/biz/store` | `mSaveStore` | store、store-scope |
| 本店经营类目 | GET | `/biz/store/:storeNo/categories` | `mStoreCategories` | goods-list、store-categories |
| 整份替换本店经营类目 | POST | `/biz/store/:storeNo/categories` | `mSaveStoreCategories` | store-categories |
| 只改公告（含有效期，可同时发到别的门店） | POST | `/biz/store/announcement` | `mSaveAnnouncement` | store-notice |
| 从常用里删一条 | POST | `/biz/store/announcement/recent/remove` | `mDropNoticeRecent` | store-notice |
| 分享海报 | GET | `/biz/store/poster` | `mPoster` | goods-list、store |
| 店铺码 | GET | `/biz/store/qrcode` | `mStoreQrcode` | store |
| 分享素材 | GET | `/biz/store/share-kit` | `mShareKit` | goods-list、store |
| 预约时段列表 | GET | `/biz/stores/:storeNo/appointment-slots` | `mAppointmentSlots` | schedule |
| 开预约时段 | POST | `/biz/stores/:storeNo/appointment-slots` | `mOpenAppointmentSlot` | schedule |
| 门店送货方式 | GET | `/biz/stores/:storeNo/fulfillment` | `mStoreFulfillment` | goods-edit、store-scope |
| 保存门店送货方式 | PUT | `/biz/stores/:storeNo/fulfillment` | `mSaveStoreFulfillment` | store-scope |
| —（b-app 未接） | — | `/biz/qualifications/recognize` | — | — |

### `biz:store:admin`　建店、改名、停用、设默认店、挂收款号

**可用角色**：老板

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 我名下的证照 | GET | `/biz/entities` | `mEntities` | entities |
| 一张证照的详情与门店 | GET | `/biz/entity/:entityNo` | `mEntity` | entity-detail |
| 设发货源 | PUT | `/biz/inventory/locations/:id/source` | `mLocationSetSource` | locations |
| 会员经营口径 | GET | `/biz/member-settings` | `mMemberSettings` | member-settings |
| 改口径（店主） | PUT | `/biz/member-settings` | `mSaveMemberSettings` | member-settings |
| 我的套餐（档位/用量/三档对比） | GET | `/biz/plan` | `mMyPlan` | me、plan、stores |
| 自助开通试用（一主体一次） | POST | `/biz/plan/trial` | `mStartTrial` | plan、stores |
| 可勾的权限点 | GET | `/biz/role-perms` | `mRolePerms` | role-detail |
| 改角色 | POST | `/biz/role/:roleCode` | `mUpdateRole` | role-detail |
| 删除自定义角色 | POST | `/biz/role/:roleCode/delete` | `mDeleteRole` | role-detail |
| 角色列表（预置 + 自定义） | GET | `/biz/roles` | `mRoles` | role-detail、staff、staff-detail |
| 建自定义角色 | POST | `/biz/roles` | `mCreateRole` | role-detail |
| 员工列表 | GET | `/biz/staff` | `mStaffList` | role-detail、staff、staff-detail |
| 加员工 | POST | `/biz/staff` | `mAddStaff` | — |
| 停用/启用员工 | POST | `/biz/staff/:mchAccountNo/status` | `mSetStaffStatus` | staff-detail |
| 授权到店 | POST | `/biz/staff/:mchAccountNo/store` | `mGrantStore` | staff-detail |
| 员工与授权变更记录 | GET | `/biz/staff/logs` | `mStaffLogs` | staff、staff-detail |
| 设为默认店 | POST | `/biz/store/:storeNo/default` | `mSetDefaultStore` | stores |
| 换门店收款号 | POST | `/biz/store/:storeNo/payment` | `mSetStorePayment` | stores |
| 改门店名与地址 | POST | `/biz/store/:storeNo/rename` | `mRenameStore` | stores |
| 停用/启用门店 | POST | `/biz/store/:storeNo/status` | `mSetStoreStatus` | stores |
| 新建门店 | POST | `/biz/store/create` | `mCreateStore` | stores |

### `biz:customer`　顾客列表（含累计消费额）、经营数据

**可用角色**：老板、店长

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 跨店对比（销售额/订单/复购/缺货） | GET | `/biz/cross-store/compare` | `mCrossStoreCompare` | cross-store |
| 跨店总览（按店并列今日/本月/待办） | GET | `/biz/cross-store/overview` | `mCrossStoreOverview` | stores |
| 客户与复购（跨店总览在用） | GET | `/biz/customers` | `mCustomers` | — |
| 经营数据 | GET | `/biz/dashboard/stats` | `mStats` | home、stats |
| 进销存月报 | GET | `/biz/inventory/report/monthly` | `mStockMonthly` | stock-report |
| 动销/滞销榜 | GET | `/biz/inventory/report/ranking` | `mStockRanking` | stock-report |
| 群发试算：能发多少、跳过多少 | POST | `/biz/member-reach/plan` | `mPlanReach` | member-reach |
| 人群列表 | GET | `/biz/member-segments` | `mMemberSegments` | coupon-issues、coupons、member-reach、member-segments |
| 存人群（存条件不存名单） | POST | `/biz/member-segments` | `mSaveMemberSegment` | customers、member-segments |
| 删人群（端上没有 DELETE，见 http-client） | POST | `/biz/member-segments/{segmentNo}/remove` | `mRemoveMemberSegment` | member-segments |
| 试算命中与可触达 | POST | `/biz/member-segments/preview` | `mPreviewMemberSegment` | customers |
| 标签字典（含人数） | GET | `/biz/member-tags` | `mMemberTags` | customers、member-add、member-segments、member-tags |
| 新建标签 | POST | `/biz/member-tags` | `mCreateMemberTag` | member-tags |
| 改名 / 停用 | PUT | `/biz/member-tags/{tagNo}` | `mEditMemberTag` | member-tags |
| 合并（confirm=false 只试算） | POST | `/biz/member-tags/{tagNo}/merge` | `mMergeMemberTag` | member-tags |
| 会员列表（筛选+分页） | GET | `/biz/members` | `mMembers` | customers |
| 手工录入（未注册记为线索） | POST | `/biz/members` | `mEnrollMember` | member-add |
| 会员详情：各店往来与来源轨迹 | GET | `/biz/members/{memberNo}` | `mMemberDetail` | member-detail |
| 改备注 / 拉黑 | PUT | `/biz/members/{memberNo}` | `mPatchMember` | — |
| 四层人数与未计入买家 | GET | `/biz/members/stats` | `mMemberStats` | customers |
| 批量打标 / 去标 | POST | `/biz/members/tags` | `mTagMembers` | — |
| —（b-app 未接） | — | `/biz/inventory/export` | — | — |

### `biz:campaign`　营销活动、开团、报价

**可用角色**：老板、店长

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 活动列表 | GET | `/biz/activities` | `mActivities` | activities |
| 建 / 改活动（敞口在这一步算清） | POST | `/biz/activities` | `mSaveActivity` | activity-edit |
| 活动详情 | GET | `/biz/activities/{activityNo}` | `mActivity` | activity-edit |
| 启停 / 结束 | PUT | `/biz/activities/{activityNo}/status` | `mSetActivityStatus` | activities |
| 这些商品已经在哪些活动里 | POST | `/biz/activity-conflicts` | `mActivityConflicts` | activity-edit |
| 营销活动列表 | GET | `/biz/campaign` | `mCampaignList` | marketing |
| 新建/编辑活动 | POST | `/biz/campaign` | `mSaveCampaign` | marketing |
| 活动启停 | POST | `/biz/campaign/:campaignNo/toggle` | `mToggleCampaign` | marketing |
| 发放记录（含跳过明细） | GET | `/biz/coupon-issues` | `mCouponIssues` | coupon-issues |
| 券列表 | GET | `/biz/coupons` | `mCoupons` | coupon-issues、coupons |
| 建券 / 改券（敞口在这一步算清） | POST | `/biz/coupons` | `mSaveCoupon` | coupon-edit |
| 券详情 | GET | `/biz/coupons/{couponNo}` | `mCoupon` | coupon-edit |
| 按人群定向发券 | POST | `/biz/coupons/{couponNo}/issue` | `mIssueCoupon` | coupons |
| 暂停 / 恢复 / 结束 | PUT | `/biz/coupons/{couponNo}/status` | `mSetCouponStatus` | coupons |
| 报价 | POST | `/biz/group-request/:requestNo/quote` | `mQuote` | quotes |
| 可报价需求单 | GET | `/biz/group-request/pool` | `mRequestList` | quotes |
| 我的商家团 | GET | `/biz/groups` | `mGroupList` | groups |
| 开团 | POST | `/biz/groups` | `mCreateGroup` | groups |
| 群发（会打扰真实用户） | POST | `/biz/member-reach/send` | `mSendReach` | member-reach |
| —（b-app 未接） | — | `/biz/quote/{}/revise` | — | — |

### `biz:finance`　结算账单、费率卡、收款进件、积分开关

**可用角色**：老板

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 本店能开的收款通道（含没开的） | GET | `/biz/merchant/pay-channel` | `mPayChannels` | payment |
| 收款进件状态 | GET | `/biz/merchant/payment` | `mPayments` | entity-detail、home、stores |
| 补交资料并提交进件 | POST | `/biz/merchant/payment` | `mSubmitPayment` | payment |
| 回查进件结果 | POST | `/biz/merchant/payment/:payChannel/refresh` | `mRefreshPayment` | payment |
| 本期发分服务费与开关状态 | GET | `/biz/points/account` | `mPointsAccount` | points、settle |
| 发分服务费明细（按单） | GET | `/biz/points/records` | `mPointsRecords` | points-records、settle |
| 开/关本店积分 | POST | `/biz/points/toggle` | `mPointsToggle` | points、settle |
| 结算单列表 | GET | `/biz/settle/bills` | `mSettleList` | settle |
| 收入按状态汇总 | GET | `/biz/settle/income` | `mIncomeSummary` | income |
| 费率卡 | GET | `/biz/settle/rate-card` | `mRateCard` | settle |
| —（b-app 未接） | — | `/biz/settle/bills/{}` | — | — |
| —（b-app 未接） | — | `/biz/settle/invoice-title` | — | — |
| —（b-app 未接） | — | `/biz/settle/invoices` | — | — |
| —（b-app 未接） | — | `/biz/settle/statement` | — | — |
| —（b-app 未接） | — | `/biz/merchant/payment/store/{}` | — | — |
| —（b-app 未接） | — | `/biz/deposit` | — | — |
| —（b-app 未接） | — | `/biz/deposit/txns` | — | — |

### `biz:verify`　核销、批量核销、按码搜索

**可用角色**：老板、店长、店员

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 到店核销一次（不可撤销） | POST | `/biz/coupon-redeem` | `mRedeemCoupon` | verify |
| 先看：这张券能不能核 | GET | `/biz/coupon-redeem/{code}` | `mPeekCouponCode` | verify |
| 本自提点订单 | GET | `/biz/pickup/orders` | `mPickupOrders` | picking、verify |
| 自提点履约总览 | GET | `/biz/pickup/overview` | `mPickupOverview` | verify |
| 核销自提码 | POST | `/biz/pickup/verify` | `mVerify` | verify |
| 批量核销 | POST | `/biz/pickup/verify/batch` | `mVerifyBatch` | verify |
| 按取货码片段搜单 | GET | `/biz/pickup/verify/search` | `mVerifySearch` | verify |

### `biz:receive`　到货登记、分拣单、短少上报

**可用角色**：老板、店长、店员、理货员

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 确认线下收款 | POST | `/biz/order/:orderNo/confirm-offline-pay` | `mConfirmOfflinePay` | order |
| 破损短少上报 | POST | `/biz/pickup/:orderNo/report` | `mReportShortage` | picking |
| 标记到货 | POST | `/biz/pickup/arrived` | `mMarkArrived` | picking |
| 分拣单 | GET | `/biz/pickup/picking` | `mPickingList` | picking |

### `biz:aftersale`　售后同意/驳回/收货

**可用角色**：老板、店长、客服

| 功能点 | 方法 | 端点 | 契约方法 | 页面 |
|---|---|---|---|---|
| 待处理售后 | GET | `/biz/after-sale` | `mAfterSaleList` | after-sale、orders |
| 同意售后 | POST | `/biz/after-sale/:afterSaleNo/approve` | `mApproveAfterSale` | after-sale |
| 确认收到退货 | POST | `/biz/after-sale/:afterSaleNo/receive` | `mConfirmReturn` | after-sale |
| 驳回售后 | POST | `/biz/after-sale/:afterSaleNo/reject` | `mRejectAfterSale` | after-sale |

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

## 三、页面 × 门禁　—— 前端裁剪与后端判权对不对得上

**页面的 `denied` 门禁必须 ⊇ 该页所有调用所需的权限码。**
不满足时的表现不是「拒绝」而是「整页空白」：后端返回 70006，
而页面把它 catch 成空数据或整个 `Promise.all` reject。

「⚠ 会撞码」列里的角色**进得了这一页，但页面里有他打不通的请求**。

| 页面 | 门禁 | 该页需要的码 | 进得来的角色 | ⚠ 会撞码 |
|---|---|---|---|---|
| `activities` | `biz:campaign` | `biz:campaign` | 老板、店长 | — |
| `activity-edit` | `biz:campaign` | `biz:campaign` | 老板、店长 | — |
| `after-sale` | `biz:aftersale` | `biz:aftersale`、`biz:order:view` | 老板、店长、客服 | — |
| `coupon-edit` | `biz:campaign` | `biz:campaign` | 老板、店长 | — |
| `coupon-issues` | `biz:campaign` | `biz:campaign`、`biz:customer` | 老板、店长 | — |
| `coupons` | `biz:campaign` | `biz:campaign`、`biz:customer` | 老板、店长 | — |
| `cross-store` | `biz:customer` | `biz:customer` | 老板、店长 | — |
| `customers` | `biz:customer` | `biz:customer` | 老板、店长 | — |
| `delivery` | `biz:ship` | `biz:store`、`biz:order:view`、`biz:ship` | 老板、店长、店员、配送员 | 店员（缺 biz:store）　配送员（缺 biz:store） |
| `entities` | `biz:store:admin` | `biz:store:admin` | 老板 | — |
| `entity-detail` | `biz:store:admin` | `biz:store:admin`、`biz:store`、`biz:finance` | 老板 | — |
| `goods-edit` | `biz:goods` | `biz:goods`、`biz:store`、`biz:stock` | 老板、店长 | — |
| `goods-list` | `biz:stock` | `biz:store`、`biz:stock`、`biz:goods` | 老板、店长、店员、理货员 | 店员（缺 biz:store、biz:goods）　理货员（缺 biz:store、biz:goods） |
| `goods-publish` | `biz:goods` | `biz:goods`、`biz:stock` | 老板、店长 | — |
| `groups` | `biz:campaign` | `biz:campaign`、`biz:stock` | 老板、店长 | — |
| `home` | **无** | `biz:customer`、`biz:finance`、`biz:store`、`biz:stock` | 老板、店长、店员、理货员、配送员、客服 | 店长（缺 biz:finance）　店员（缺 biz:customer、biz:finance、biz:store）　理货员（缺 biz:customer、biz:finance、biz:store）　配送员（缺 biz:customer、biz:finance、biz:store、biz:stock）　客服（缺 biz:customer、biz:finance、biz:store、biz:stock） |
| `income` | `biz:finance` | `biz:finance` | 老板 | — |
| `locations` | `biz:store:admin` | `biz:stock`、`biz:store:admin` | 老板 | — |
| `marketing` | `biz:campaign` | `biz:campaign`、`biz:stock` | 老板、店长 | — |
| `me` | **无** | `biz:store:admin` | 老板、店长、店员、理货员、配送员、客服 | 店长（缺 biz:store:admin）　店员（缺 biz:store:admin）　理货员（缺 biz:store:admin）　配送员（缺 biz:store:admin）　客服（缺 biz:store:admin） |
| `member-add` | `biz:customer` | `biz:customer` | 老板、店长 | — |
| `member-detail` | `biz:customer` | `biz:customer` | 老板、店长 | — |
| `member-reach` | `biz:customer` | `biz:customer`、`biz:campaign` | 老板、店长 | — |
| `member-segments` | `biz:customer` | `biz:customer` | 老板、店长 | — |
| `member-settings` | `biz:store:admin` | `biz:store:admin` | 老板 | — |
| `member-tags` | `biz:customer` | `biz:customer` | 老板、店长 | — |
| `my-specs` | `biz:goods` | `biz:goods` | 老板、店长 | — |
| `order` | `biz:order:view` | `biz:receive`、`biz:order:view`、`biz:ship` | 老板、店长、店员、配送员、客服 | 配送员（缺 biz:receive）　客服（缺 biz:receive、biz:ship） |
| `orders` | `biz:order:view` | `biz:aftersale`、`biz:order:view` | 老板、店长、店员、配送员、客服 | 店员（缺 biz:aftersale）　配送员（缺 biz:aftersale） |
| `payment` | `biz:finance` | `biz:finance` | 老板 | — |
| `picking` | `biz:receive` | `biz:receive`、`biz:verify` | 老板、店长、店员、理货员 | 理货员（缺 biz:verify） |
| `plan` | `biz:store:admin` | `biz:store:admin` | 老板 | — |
| `points` | `biz:finance` | `biz:finance` | 老板 | — |
| `points-records` | `biz:finance` | `biz:finance` | 老板 | — |
| `purchase-edit` | `biz:stock` | `biz:stock` | 老板、店长、店员、理货员 | — |
| `qualifications` | `biz:store` | `biz:store` | 老板、店长 | — |
| `quotes` | `biz:campaign` | `biz:campaign` | 老板、店长 | — |
| `reviews` | `biz:review` | `biz:review` | 老板、店长、客服 | — |
| `role-detail` | `biz:store:admin` | `biz:store:admin` | 老板 | — |
| `schedule` | `biz:store` | `biz:store` | 老板、店长 | — |
| `settle` | `biz:finance` | `biz:finance` | 老板 | — |
| `sku-identity` | `biz:goods` | `biz:goods` | 老板、店长 | — |
| `staff` | `biz:store:admin` | `biz:store:admin` | 老板 | — |
| `staff-detail` | `biz:store:admin` | `biz:store:admin` | 老板 | — |
| `stats` | `biz:customer` | `biz:customer` | 老板、店长 | — |
| `stock` | `biz:stock` | `biz:stock` | 老板、店长、店员、理货员 | — |
| `stock-check` | `biz:stock` | `biz:stock` | 老板、店长、店员、理货员 | — |
| `stock-detail` | `biz:stock` | `biz:stock` | 老板、店长、店员、理货员 | — |
| `stock-docs` | `biz:stock` | `biz:stock` | 老板、店长、店员、理货员 | — |
| `stock-out` | `biz:stock` | `biz:stock` | 老板、店长、店员、理货员 | — |
| `stock-report` | `biz:customer` | `biz:customer` | 老板、店长 | — |
| `store` | `biz:store` | `biz:store` | 老板、店长 | — |
| `store-categories` | `biz:store:admin` | `biz:store` | 老板 | — |
| `store-notice` | `biz:store` | `biz:store` | 老板、店长 | — |
| `store-scope` | `biz:store` | `biz:store` | 老板、店长 | — |
| `stores` | `biz:store:admin` | `biz:finance`、`biz:store:admin`、`biz:customer` | 老板 | — |
| `suppliers` | `biz:stock` | `biz:stock` | 老板、店长、店员、理货员 | — |
| `transfer` | `biz:stock` | `biz:stock` | 老板、店长、店员、理货员 | — |
| `verify` | `biz:verify` | `biz:verify` | 老板、店长、店员 | — |

> 「进得来的角色」只按 `denied` 门禁算，**不含页面内部按 `can()` 逐块裁的部分** ——
> 工作台那种「每个格子跟着自己的权限走」的写法在这张表里会显示为「会撞码」，但它是对的。
> 这张表定位问题，不定罪。

## 四、后端有能力、b-app 没有入口

| 端点 | 权限码 | 可用角色 |
|---|---|---|
| `/biz/deposit` | `biz:finance` | 老板 |
| `/biz/deposit/txns` | `biz:finance` | 老板 |
| `/biz/inventory/export` | `biz:customer` | 老板、店长 |
| `/biz/merchant/payment/store/{}` | `biz:finance` | 老板 |
| `/biz/qualifications/recognize` | `biz:store` | 老板、店长 |
| `/biz/quote/{}/revise` | `biz:campaign` | 老板、店长 |
| `/biz/settle/bills/{}` | `biz:finance` | 老板 |
| `/biz/settle/invoice-title` | `biz:finance` | 老板 |
| `/biz/settle/invoices` | `biz:finance` | 老板 |
| `/biz/settle/statement` | `biz:finance` | 老板 |

> 这些是「写了、测了、没人调用」—— 要么排期接上，要么从 `REQUIRED` 删掉。
> `noStaleEntries` 只拦已删除的端点，**拦不住「端点还在但没人用」**。

### 四之二、契约接了、页面没画

比上一张更隐蔽：`endpoints.ts` 里有、`contract.ts` 里有类型，
**唯独没有任何一页调用它** —— 看起来像做完了。

| 功能点 | 端点 | 契约方法 | 权限码 |
|---|---|---|---|
| 提报平台还没有的小区 | `/biz/communities/apply` | `mApplyCommunity` | `biz:store` |
| 地图上选中的小区直接开通 | `/biz/communities/from-map` | `mOpenCommunityFromMap` | `biz:store` |
| 客户与复购（跨店总览在用） | `/biz/customers` | `mCustomers` | `biz:customer` |
| 改截单与到货说明 | `/biz/goods/:goodsNo/presale` | `mSavePresale` | `biz:goods` |
| 改进货草稿 | `/biz/inventory/inbounds/:no` | `mInboundUpdate` | `biz:stock` |
| 改备注 / 拉黑 | `/biz/members/{memberNo}` | `mPatchMember` | `biz:customer` |
| 批量打标 / 去标 | `/biz/members/tags` | `mTagMembers` | `biz:customer` |
| 停用/启用自建维度 | `/biz/my-spec-dims/{dimNo}/archive` | `mArchiveSpecDim` | `biz:goods` |
| 给自建维度改名 | `/biz/my-spec-dims/{dimNo}/rename` | `mRenameSpecDim` | `biz:goods` |
| 自建自提点（待运营核实） | `/biz/pickup-points` | `mSelfBuildPickup` | `biz:store` |
| 门店可引用的取货点候选 | `/biz/pickup-points/candidates` | `mPickupCandidates` | `biz:store` |
| 存为常用规格 | `/biz/spec-templates` | `mSaveSpecTemplate` | `biz:goods` |
| 加员工 | `/biz/staff` | `mAddStaff` | `biz:store:admin` |

## 五、登录即可（不需要授权）

**空角色的人也能调**，所以这张表的每一条都要能回答「为什么它不需要权限」。

| 端点 | 功能点 |
|---|---|
| `/biz/auth/login` | 商家登录 |
| `/biz/auth/otp/send` | 发送验证码 |
| `/biz/auth/password` | 设置登录密码 |
| `/biz/auth/staff-login` | 员工登录 |
| `/biz/category/tree` | 类目树（选类目） |
| `/biz/communities` | 可选社区（设经营范围用） |
| `/biz/context` | 我的作用域与权限 |
| `/biz/geo/estates` | 一片地方的小区（服务端读穿透：缓存优先，不够就问地图） |
| `/biz/geo/estates/counts` | 下辖各片的小区条数（列表预告） |
| `/biz/geo/geocode` | — |
| `/biz/geo/reverse` | 坐标转地址（门店地址定位） |
| `/biz/geo/tips` | 地点输入提示（提报小区按名搜 POI） |
| `/biz/merchant/apply` | 提交入驻申请 |
| `/biz/merchant/profile` | 商家资料 |
| `/biz/merchant/quick-start` | 无证照快速开店 |
| `/biz/message` | 商家消息列表 |
| `/biz/message/read-all` | 全部已读 |
| `/biz/message/unread-count` | 未读数（红点轮询，只给一个数） |
| `/biz/message/{}/read` | 标记已读 |
| `/biz/push-token` | 绑定 App 推送设备（登录后） |
| `/biz/push-token/unregister` | 解绑推送设备（登出前，共用设备换班必须解） |
| `/biz/regions` | 行政区划下一级（框覆盖范围用） |
| `/biz/regions/path` | 区划从省到自身的路径 |
| `/biz/regions/search` | 跨级搜区划与聚落（选择器搜索） |
| `/biz/regions/villages` | 街道/镇下的官方村名词典（提报村用） |
| `/biz/store/list` | 我的门店 |
| `/biz/stores/mine` | 我能进的所有门店（按证照分组） |
| `/biz/upload/image` | 上传商品图 |

## 六、任一权限即可（汇总型端点）

一次返回好几件互不相干的事，**粒度由端上按 `perms` 裁**。
仍然要求「在这家店有角色」——空角色的人一样进不来。

- `/biz/dashboard/todo`　工作台待办

