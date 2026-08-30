// B 端端点表 —— API 的唯一真源（同 C 端做法）。
// 一处声明，两处消费：http.ts 按表发请求；后端据此生成 controller 骨架。
import type { MerchantApi } from "./contract";

export type HttpMethod = "GET" | "POST" | "PUT";

export interface EndpointDef {
  method: HttpMethod;
  /** 路径，`:name` 为路径参数 */
  path: string;
  /** 是否需要登录态（Bearer） */
  auth: boolean;
  summary: string;
}

/** key 与 MerchantApi 的方法名一一对应，缺一个就编译不过 */
export const ENDPOINTS: Record<keyof MerchantApi, EndpointDef> = {
  mSendOtp: { method: "POST", path: "/biz/auth/otp/send", auth: false, summary: "发送验证码" },
  mSetPassword: { method: "POST", path: "/biz/auth/password", auth: true, summary: "设置登录密码" },
  mHasPassword: { method: "GET", path: "/biz/auth/password", auth: true, summary: "是否已设密码" },
  mLogin: { method: "POST", path: "/biz/auth/login", auth: false, summary: "商家登录" },
  mStaffLogin: { method: "POST", path: "/biz/auth/staff-login", auth: false, summary: "员工登录" },
  mProfile: { method: "GET", path: "/biz/merchant/profile", auth: true, summary: "商家资料" },

  mApply: { method: "POST", path: "/biz/merchant/apply", auth: true, summary: "提交入驻申请" },
  mQuickStart: { method: "POST", path: "/biz/merchant/quick-start", auth: true, summary: "无证照快速开店" },
  mApplyDraft: { method: "GET", path: "/biz/merchant/apply", auth: true, summary: "上次入驻申请" },
  mMasterData: { method: "GET", path: "/common/master-data", auth: false, summary: "平台主数据（行业/主体/通道）" },

  mPayments: { method: "GET", path: "/biz/merchant/payment", auth: true, summary: "收款进件状态" },
  mPayChannels: { method: "GET", path: "/biz/merchant/pay-channel", auth: true, summary: "本店能开的收款通道（含没开的）" },
  mSubmitPayment: { method: "POST", path: "/biz/merchant/payment", auth: true, summary: "补交资料并提交进件" },
  mRefreshPayment: {
    method: "POST",
    path: "/biz/merchant/payment/:payChannel/refresh",
    auth: true,
    summary: "回查进件结果",
  },

  mStore: { method: "GET", path: "/biz/store", auth: true, summary: "店铺门面" },
  mCommunities: { method: "GET", path: "/biz/communities", auth: true, summary: "可选社区（设经营范围用）" },
  mRegions: { method: "GET", path: "/biz/regions", auth: true, summary: "行政区划下一级（框覆盖范围用）" },
  mVillageDict: { method: "GET", path: "/biz/regions/villages", auth: true, summary: "街道/镇下的官方村名词典（提报村用）" },
  mRegionSearch: { method: "GET", path: "/biz/regions/search", auth: true, summary: "跨级搜区划与聚落（选择器搜索）" },
  mRegionPath: { method: "GET", path: "/biz/regions/path", auth: true, summary: "区划从省到自身的路径" },
  mGeoReverse: { method: "GET", path: "/biz/geo/reverse", auth: true, summary: "坐标转地址（门店地址定位）" },
  mGeoTips: { method: "GET", path: "/biz/geo/tips", auth: true, summary: "地点输入提示（提报小区按名搜 POI）" },
  mEstates: { method: "GET", path: "/biz/geo/estates", auth: true, summary: "一片地方的小区（服务端读穿透：缓存优先，不够就问地图）" },
  mEstateCounts: { method: "GET", path: "/biz/geo/estates/counts", auth: true, summary: "下辖各片的小区条数（列表预告）" },
  mApplyCommunity: { method: "POST", path: "/biz/communities/apply", auth: true, summary: "提报平台还没有的小区" },
  mOpenCommunityFromMap: { method: "POST", path: "/biz/communities/from-map", auth: true, summary: "地图上选中的小区直接开通" },
  mMyCommunityApplies: { method: "GET", path: "/biz/communities/applies", auth: true, summary: "我提报过的小区" },
  mSaveStore: { method: "POST", path: "/biz/store", auth: true, summary: "保存店铺门面" },
  mSaveAnnouncement: { method: "POST", path: "/biz/store/announcement", auth: true, summary: "只改公告（含有效期，可同时发到别的门店）" },
  mDropNoticeRecent: { method: "POST", path: "/biz/store/announcement/recent/remove", auth: true, summary: "从常用里删一条" },

  mStoreFulfillment: { method: "GET", path: "/biz/stores/:storeNo/fulfillment", auth: true, summary: "门店送货方式" },
  mSaveStoreFulfillment: { method: "PUT", path: "/biz/stores/:storeNo/fulfillment", auth: true, summary: "保存门店送货方式" },
  mFulfillmentImpact: { method: "GET", path: "/biz/stores/:storeNo/fulfillment/:channel/impact", auth: true, summary: "关掉这一路会影响的在售商品" },
  mPickupCandidates: { method: "GET", path: "/biz/pickup-points/candidates", auth: true, summary: "门店可引用的取货点候选" },
  mSelfBuildPickup: { method: "POST", path: "/biz/pickup-points", auth: true, summary: "自建自提点（待运营核实）" },
  mStoreList: { method: "GET", path: "/biz/store/list", auth: true, summary: "我的门店" },

  // 跨证照（多证照）。**故意不吃当前证照的范围** —— 那是「当前这一张」，
  // 而这三个问的正是「当前之外我还有哪几张」
  mMyStores: { method: "GET", path: "/biz/stores/mine", auth: true, summary: "我能进的所有门店（按证照分组）" },
  mEntities: { method: "GET", path: "/biz/entities", auth: true, summary: "我名下的证照" },
  mEntity: { method: "GET", path: "/biz/entity/:entityNo", auth: true, summary: "一张证照的详情与门店" },
  mCreateStore: { method: "POST", path: "/biz/store/create", auth: true, summary: "新建门店" },
  mRenameStore: { method: "POST", path: "/biz/store/:storeNo/rename", auth: true, summary: "改门店名与地址" },
  mSetStoreStatus: { method: "POST", path: "/biz/store/:storeNo/status", auth: true, summary: "停用/启用门店" },
  mSetDefaultStore: { method: "POST", path: "/biz/store/:storeNo/default", auth: true, summary: "设为默认店" },
  mSetStorePayment: { method: "POST", path: "/biz/store/:storeNo/payment", auth: true, summary: "换门店收款号" },

  // 门店货架（TDD-品类约束全链路）。读挂 biz:store（店长要看得见本店卖哪几类），
  // 写挂 biz:store:admin —— 摆货架是店铺配置，不是日常经营
  mStoreCategories: { method: "GET", path: "/biz/store/:storeNo/categories", auth: true, summary: "本店经营类目" },
  mQualifications: { method: "GET", path: "/biz/qualifications", auth: true, summary: "我的资质与已获授权的类目" },
  mSaveQualification: { method: "POST", path: "/biz/qualifications/save", auth: true, summary: "传一张资质证件" },
  mSaveStoreCategories: { method: "POST", path: "/biz/store/:storeNo/categories", auth: true, summary: "整份替换本店经营类目" },

  mStaffList: { method: "GET", path: "/biz/staff", auth: true, summary: "员工列表" },
  mAddStaff: { method: "POST", path: "/biz/staff", auth: true, summary: "加员工" },
  mSetStaffStatus: { method: "POST", path: "/biz/staff/:mchAccountNo/status", auth: true, summary: "停用/启用员工" },
  mBizScope: { method: "GET", path: "/biz/context", auth: true, summary: "我的作用域与权限" },
  mStaffLogs: { method: "GET", path: "/biz/staff/logs", auth: true, summary: "员工与授权变更记录" },
  mRoles: { method: "GET", path: "/biz/roles", auth: true, summary: "角色列表（预置 + 自定义）" },
  mRolePerms: { method: "GET", path: "/biz/role-perms", auth: true, summary: "可勾的权限点" },
  mCreateRole: { method: "POST", path: "/biz/roles", auth: true, summary: "建自定义角色" },
  mUpdateRole: { method: "POST", path: "/biz/role/:roleCode", auth: true, summary: "改角色" },
  mDeleteRole: {
    method: "POST",
    path: "/biz/role/:roleCode/delete",
    auth: true,
    summary: "删除自定义角色",
  },
  mGrantStore: { method: "POST", path: "/biz/staff/:mchAccountNo/store", auth: true, summary: "授权到店" },
  mStoreQrcode: { method: "GET", path: "/biz/store/qrcode", auth: true, summary: "店铺码" },
  mShareKit: { method: "GET", path: "/biz/store/share-kit", auth: true, summary: "分享素材" },
  mPoster: { method: "GET", path: "/biz/store/poster", auth: true, summary: "分享海报" },

  mTodo: { method: "GET", path: "/biz/dashboard/todo", auth: true, summary: "工作台待办" },
  mStats: { method: "GET", path: "/biz/dashboard/stats", auth: true, summary: "经营数据" },

  // 跨店总览与对比（B-11.12.5 / 11.12.6）。权限与 /biz/dashboard/stats 同一档
  // （biz:customer，后端没有另造 biz:cross-store 码），另有一道能力位门禁：
  // 无 cross_store_stats 的档位会被拒（70023），端上渲染示例态。
  mCrossStoreOverview: {
    method: "GET",
    path: "/biz/cross-store/overview",
    auth: true,
    summary: "跨店总览（按店并列今日/本月/待办）",
  },
  mCrossStoreCompare: {
    method: "GET",
    path: "/biz/cross-store/compare",
    auth: true,
    summary: "跨店对比（销售额/订单/复购/缺货）",
  },

  // 我的增值包（B-11.13，增值包 P4）。挂 biz:store:admin —— **只有老板**：
  // 这一页答的是「主体买了什么」，与建店、挂收款号同属主体结构面。
  // 店长调这两条会 403，所以端上要按 can('biz:store:admin') 决定渲不渲染入口
  mMyPlan: { method: "GET", path: "/biz/plan", auth: true, summary: "我的套餐（档位/用量/三档对比）" },
  mStartTrial: { method: "POST", path: "/biz/plan/trial", auth: true, summary: "自助开通试用（一主体一次）" },

  mGoodsList: { method: "GET", path: "/biz/goods", auth: true, summary: "商品列表" },
  mGoodsDetail: { method: "GET", path: "/biz/goods/:goodsNo", auth: true, summary: "商品详情" },
  mSaveGoods: { method: "POST", path: "/biz/goods/save", auth: true, summary: "新建/编辑商品" },
  mToggleGoods: { method: "POST", path: "/biz/goods/:goodsNo/toggle", auth: true, summary: "上下架" },
  mSaveStock: { method: "POST", path: "/biz/goods/:goodsNo/stock", auth: true, summary: "改库存" },
  mSaveStoreStock: { method: "POST", path: "/biz/goods/:goodsNo/store-stock", auth: true, summary: "改当前门店库存" },
  // 挂 biz:goods 而不是 biz:stock —— 改价是定价权，与补货不是一回事
  mSaveStorePrice: { method: "POST", path: "/biz/goods/:goodsNo/store-price", auth: true, summary: "改当前门店售价" },
  mSubmitGoods: { method: "POST", path: "/biz/goods/:goodsNo/submit", auth: true, summary: "提交审核（草稿→待审）" },
  // 双版本发布（V279）：在售编辑落草稿线上照卖；发布=原子换版（审核开则线上继续卖旧版）
  mGoodsDraft: { method: "GET", path: "/biz/goods/:goodsNo/draft", auth: true, summary: "读草稿（编辑页回填）" },
  mPublishPreview: { method: "GET", path: "/biz/goods/:goodsNo/publish-preview", auth: true, summary: "发布预览（字段级差异）" },
  mPublishGoods: { method: "POST", path: "/biz/goods/:goodsNo/publish", auth: true, summary: "发布草稿（原子换版）" },
  // 只改截单，**不触发重审** —— 走 save 的话生鲜商家改一次截单等于停一天生意
  mSavePresale: { method: "POST", path: "/biz/goods/:goodsNo/presale", auth: true, summary: "改截单与到货说明" },

  mUploadImage: { method: "POST", path: "/biz/upload/image", auth: true, summary: "上传商品图" },
  mRecognizeGoods: { method: "POST", path: "/biz/goods/recognize", auth: true, summary: "拍照识别商品" },
  mDescribeGoods: { method: "POST", path: "/biz/goods/describe", auth: true, summary: "自动生成图文详情" },

  mCategoryTree: { method: "GET", path: "/biz/category/tree", auth: true, summary: "类目树（选类目）" },
  // ⚠️ 注释放在属性外面：生成器正则是 `\{\s*method:`，夹在中间这个端点就不进 spec
  mSpuStdSearch: { method: "GET", path: "/biz/spu-std", auth: true, summary: "标准品搜索（建品用）" },

  mSpecTemplates: { method: "GET", path: "/biz/spec-templates", auth: true, summary: "规格模板" },
  mPickableDims: { method: "GET", path: "/biz/spec-dims", auth: true, summary: "加规格组时能挑的维度（本类目已配 + 平台通用 + 自建）" },
  mSpecProps: { method: "GET", path: "/biz/spec-props", auth: true, summary: "这一类的商品参数（产地/保质期/材质，不分 SKU）" },
  mPickableProps: { method: "GET", path: "/biz/pickable-props", auth: true, summary: "还能加进这一类的商品参数（本类目已配 + 平台通用 + 自建）" },
  mAddSpecValue: { method: "POST", path: "/biz/spec-values", auth: true, summary: "在平台维度下加一个自有规格值" },
  mSkuIdentityExport: { method: "GET", path: "/biz/sku-identity/export", auth: true, summary: "导出本店全部规格行的条码/货号/单位" },
  mSkuIdentityPlan: { method: "POST", path: "/biz/sku-identity/import/plan", auth: true, summary: "商品编码导入试算（不写库）" },
  mSkuIdentityImport: { method: "POST", path: "/biz/sku-identity/import", auth: true, summary: "商品编码批量导入" },
  mAddSpecDim: { method: "POST", path: "/biz/spec-dims", auth: true, summary: "自建规格维度（只本店可用）" },
  mMySpecDims: { method: "GET", path: "/biz/my-spec-dims", auth: true, summary: "我建的规格维度（含用量与配额）" },
  mStoreSpecDims: { method: "GET", path: "/biz/store-spec-dims", auth: true, summary: "本店货架类目各自能用的规格" },
  mDimValues: { method: "GET", path: "/biz/spec-dims/{dimNo}/values", auth: true, summary: "某个规格下平台有的全部档位（加档位的候选）" },
  mSaveSpecOverride: { method: "POST", path: "/biz/spec-override/{categoryNo}", auth: true, summary: "本店用哪几个规格、什么顺序、叫什么" },
  mRenameSpecDim: { method: "POST", path: "/biz/my-spec-dims/{dimNo}/rename", auth: true, summary: "给自建维度改名" },
  mArchiveSpecDim: { method: "POST", path: "/biz/my-spec-dims/{dimNo}/archive", auth: true, summary: "停用/启用自建维度" },
  mSaveSpecTemplate: { method: "POST", path: "/biz/spec-templates", auth: true, summary: "存为常用规格" },

  mOrderList: { method: "GET", path: "/biz/order", auth: true, summary: "订单列表" },
  mOrderDetail: { method: "GET", path: "/biz/order/:orderNo", auth: true, summary: "订单详情" },
  mShip: { method: "POST", path: "/biz/order/:orderNo/ship", auth: true, summary: "快递发货" },
  mDelivered: { method: "POST", path: "/biz/order/:orderNo/delivered", auth: true, summary: "自送已送达" },
  mConfirmOfflinePay: { method: "POST", path: "/biz/order/:orderNo/confirm-offline-pay", auth: true, summary: "确认线下收款" },
  mDeliveryRule: { method: "GET", path: "/biz/delivery/rule", auth: true, summary: "自送规则" },
  mSaveDeliveryRule: { method: "POST", path: "/biz/delivery/rule", auth: true, summary: "保存自送规则" },

  mAppointmentSlots: { method: "GET", path: "/biz/stores/:storeNo/appointment-slots", auth: true, summary: "预约时段列表" },
  mOpenAppointmentSlot: { method: "POST", path: "/biz/stores/:storeNo/appointment-slots", auth: true, summary: "开预约时段" },
  mCloseAppointmentSlot: { method: "POST", path: "/biz/appointment-slots/:slotNo/close", auth: true, summary: "停约" },

  mPickupOverview: { method: "GET", path: "/biz/pickup/overview", auth: true, summary: "自提点履约总览" },
  mPickupOrders: { method: "GET", path: "/biz/pickup/orders", auth: true, summary: "本自提点订单" },
  mPickingList: { method: "GET", path: "/biz/pickup/picking", auth: true, summary: "分拣单" },
  mMarkArrived: { method: "POST", path: "/biz/pickup/arrived", auth: true, summary: "标记到货" },
  mVerify: { method: "POST", path: "/biz/pickup/verify", auth: true, summary: "核销自提码" },
  mVerifyBatch: { method: "POST", path: "/biz/pickup/verify/batch", auth: true, summary: "批量核销" },
  mVerifySearch: {
    method: "GET",
    path: "/biz/pickup/verify/search",
    auth: true,
    summary: "按取货码片段搜单",
  },

  mAfterSaleList: { method: "GET", path: "/biz/after-sale", auth: true, summary: "待处理售后" },
  // 同意与驳回是**两个动词、两条路径**，不是一个布尔参数 ——
  // 与后端一致，也让「谁被调用了」在日志与权限里能分开看
  mApproveAfterSale: { method: "POST", path: "/biz/after-sale/:afterSaleNo/approve", auth: true, summary: "同意售后" },
  mRejectAfterSale: { method: "POST", path: "/biz/after-sale/:afterSaleNo/reject", auth: true, summary: "驳回售后" },

  mConfirmReturn: { method: "POST", path: "/biz/after-sale/:afterSaleNo/receive", auth: true, summary: "确认收到退货" },

  mGroupList: { method: "GET", path: "/biz/groups", auth: true, summary: "我的商家团" },
  mCreateGroup: { method: "POST", path: "/biz/groups", auth: true, summary: "开团" },
  mRequestList: { method: "GET", path: "/biz/group-request/pool", auth: true, summary: "可报价需求单" },
  mQuote: { method: "POST", path: "/biz/group-request/:requestNo/quote", auth: true, summary: "报价" },

  mReviewList: { method: "GET", path: "/biz/review", auth: true, summary: "评价列表" },
  mReplyReview: { method: "POST", path: "/biz/review/:reviewNo/reply", auth: true, summary: "回复评价" },
  mAppealReview: { method: "POST", path: "/biz/review/:reviewNo/appeal", auth: true, summary: "申诉差评" },

  mCampaignList: { method: "GET", path: "/biz/campaign", auth: true, summary: "营销活动列表" },
  mSaveCampaign: { method: "POST", path: "/biz/campaign", auth: true, summary: "新建/编辑活动" },
  mToggleCampaign: { method: "POST", path: "/biz/campaign/:campaignNo/toggle", auth: true, summary: "活动启停" },

  mCustomers: { method: "GET", path: "/biz/customers", auth: true, summary: "客户与复购（跨店总览在用）" },
  // 会员（P1）：客户页的升级版。沿用 biz:customer，不新造权限码
  mMembers: { method: "GET", path: "/biz/members", auth: true, summary: "会员列表（筛选+分页）" },
  mMemberStats: { method: "GET", path: "/biz/members/stats", auth: true, summary: "四层人数与未计入买家" },
  mMemberDetail: { method: "GET", path: "/biz/members/{memberNo}", auth: true, summary: "会员详情：各店往来与来源轨迹" },
  // 录入与标签（P2）
  mEnrollMember: { method: "POST", path: "/biz/members", auth: true, summary: "手工录入（未注册记为线索）" },
  mPatchMember: { method: "PUT", path: "/biz/members/{memberNo}", auth: true, summary: "改备注 / 拉黑" },
  mTagMembers: { method: "POST", path: "/biz/members/tags", auth: true, summary: "批量打标 / 去标" },
  mMemberTags: { method: "GET", path: "/biz/member-tags", auth: true, summary: "标签字典（含人数）" },
  mCreateMemberTag: { method: "POST", path: "/biz/member-tags", auth: true, summary: "新建标签" },
  mEditMemberTag: { method: "PUT", path: "/biz/member-tags/{tagNo}", auth: true, summary: "改名 / 停用" },
  mMergeMemberTag: { method: "POST", path: "/biz/member-tags/{tagNo}/merge", auth: true, summary: "合并（confirm=false 只试算）" },
  mMemberSettings: { method: "GET", path: "/biz/member-settings", auth: true, summary: "会员经营口径" },
  mSaveMemberSettings: { method: "PUT", path: "/biz/member-settings", auth: true, summary: "改口径（店主）" },
  mMemberSegments: { method: "GET", path: "/biz/member-segments", auth: true, summary: "人群列表" },
  mSaveMemberSegment: { method: "POST", path: "/biz/member-segments", auth: true, summary: "存人群（存条件不存名单）" },
  mRemoveMemberSegment: { method: "POST", path: "/biz/member-segments/{segmentNo}/remove", auth: true, summary: "删人群（端上没有 DELETE，见 http-client）" },
  mPreviewMemberSegment: { method: "POST", path: "/biz/member-segments/preview", auth: true, summary: "试算命中与可触达" },
  mCoupons: { method: "GET", path: "/biz/coupons", auth: true, summary: "券列表" },
  mCoupon: { method: "GET", path: "/biz/coupons/{couponNo}", auth: true, summary: "券详情" },
  mSaveCoupon: { method: "POST", path: "/biz/coupons", auth: true, summary: "建券 / 改券（敞口在这一步算清）" },
  mSetCouponStatus: { method: "PUT", path: "/biz/coupons/{couponNo}/status", auth: true, summary: "暂停 / 恢复 / 结束" },
  mIssueCoupon: { method: "POST", path: "/biz/coupons/{couponNo}/issue", auth: true, summary: "按人群定向发券" },
  mCouponIssues: { method: "GET", path: "/biz/coupon-issues", auth: true, summary: "发放记录（含跳过明细）" },
  mPeekCouponCode: { method: "GET", path: "/biz/coupon-redeem/{code}", auth: true, summary: "先看：这张券能不能核" },
  mRedeemCoupon: { method: "POST", path: "/biz/coupon-redeem", auth: true, summary: "到店核销一次（不可撤销）" },
  mActivities: { method: "GET", path: "/biz/activities", auth: true, summary: "活动列表" },
  mActivity: { method: "GET", path: "/biz/activities/{activityNo}", auth: true, summary: "活动详情" },
  mSaveActivity: { method: "POST", path: "/biz/activities", auth: true, summary: "建 / 改活动（敞口在这一步算清）" },
  mSetActivityStatus: { method: "PUT", path: "/biz/activities/{activityNo}/status", auth: true, summary: "启停 / 结束" },
  mActivityConflicts: { method: "POST", path: "/biz/activity-conflicts", auth: true, summary: "这些商品已经在哪些活动里" },
  mPlanReach: { method: "POST", path: "/biz/member-reach/plan", auth: true, summary: "群发试算：能发多少、跳过多少" },
  mSendReach: { method: "POST", path: "/biz/member-reach/send", auth: true, summary: "群发（会打扰真实用户）" },

  mRateCard: { method: "GET", path: "/biz/settle/rate-card", auth: true, summary: "费率卡" },
  mSettleList: { method: "GET", path: "/biz/settle/bills", auth: true, summary: "结算单列表" },
  mReportShortage: { method: "POST", path: "/biz/pickup/:orderNo/report", auth: true, summary: "破损短少上报" },

  // ---------------------------------------------------------------- 积分（B-11.x）
  //
  // 商家**不感知积分抵扣**（V34）：他收到的是订单全额减各项费用。
  // 这里只有他自己发分的成本，以及开关。
  mIncomeSummary: { method: "GET", path: "/biz/settle/income", auth: true, summary: "收入按状态汇总" },

  mPointsAccount: {
    method: "GET",
    path: "/biz/points/account",
    auth: true,
    summary: "本期发分服务费与开关状态",
  },
  mPointsRecords: {
    method: "GET",
    path: "/biz/points/records",
    auth: true,
    summary: "发分服务费明细（按单）",
  },
  mPointsToggle: {
    method: "POST",
    path: "/biz/points/toggle",
    auth: true,
    summary: "开/关本店积分",
  },

  // ---------------------------------------------------------------- 消息（二期）
  mMessageList: { method: "GET", path: "/biz/message", auth: true, summary: "商家消息列表" },
  mMessageUnread: {
    method: "GET",
    path: "/biz/message/unread-count",
    auth: true,
    summary: "未读数（红点轮询，只给一个数）",
  },
  mMessageRead: {
    method: "POST",
    path: "/biz/message/:messageNo/read",
    auth: true,
    summary: "标记已读",
  },
  mMessageReadAll: { method: "POST", path: "/biz/message/read-all", auth: true, summary: "全部已读" },

  // ---------------------------------------------------------------- 推送设备（三期，ADR-018）
  mRegisterPushToken: {
    method: "POST",
    path: "/biz/push-token",
    auth: true,
    summary: "绑定 App 推送设备（登录后）",
  },
  // POST 而非 DELETE：端上 call() 只走 GET/POST 两条路，
  // DELETE 会被静默当成 POST —— 解绑「没报错但没生效」是最坏的失败方式。
  //
  // ⚠️ 注释放在属性外面：生成器正则是 `\{\s*method:`，夹在中间这个端点就不进 spec。
  mUnregisterPushToken: {
    method: "POST",
    path: "/biz/push-token/unregister",
    auth: true,
    summary: "解绑推送设备（登出前，共用设备换班必须解）",
  },

  // ── 进销存（P-18）。**注释别夹在 `{` 与 `method:` 之间** ——
  // 端点表的解析器认那个位置，夹进去这条端点会静默不进 spec。
  mStockSummary: { method: "GET", path: "/biz/inventory/summary", auth: true, summary: "库存总览三个数" },
  mStockBalances: { method: "GET", path: "/biz/inventory/balances", auth: true, summary: "库存列表（默认只给要处理的）" },
  mStockPickable: { method: "GET", path: "/biz/inventory/pickable", auth: true, summary: "可挑的货（含 0 库存，从物料出发）" },
  mStockItem: { method: "GET", path: "/biz/inventory/items/:itemId", auth: true, summary: "单件库存明细" },
  mStockLedger: { method: "GET", path: "/biz/inventory/ledger", auth: true, summary: "库存变动明细" },
  mStockAdjust: { method: "POST", path: "/biz/inventory/adjust", auth: true, summary: "直接改数（走盘点，落单落流水）" },

  mInboundCreate: { method: "POST", path: "/biz/inventory/inbounds", auth: true, summary: "记一笔进货" },
  mInboundUpdate: { method: "PUT", path: "/biz/inventory/inbounds/:no", auth: true, summary: "改进货草稿" },
  mInboundPost: { method: "POST", path: "/biz/inventory/inbounds/:no/post", auth: true, summary: "进货过账" },
  mInboundVoid: { method: "POST", path: "/biz/inventory/inbounds/:no/void", auth: true, summary: "作废入库单" },

  mOutboundCreate: { method: "POST", path: "/biz/inventory/outbounds", auth: true, summary: "报损/领用出库" },
  mOutboundPost: { method: "POST", path: "/biz/inventory/outbounds/:no/post", auth: true, summary: "出库过账" },
  mOutboundVoid: { method: "POST", path: "/biz/inventory/outbounds/:no/void", auth: true, summary: "作废出库单" },

  mCountOpen: { method: "POST", path: "/biz/inventory/counts", auth: true, summary: "开盘点单（锁账面数）" },
  mCountDetail: { method: "GET", path: "/biz/inventory/counts/:no", auth: true, summary: "读回盘点单（含账面快照）" },
  mCountFill: { method: "PUT", path: "/biz/inventory/counts/:no/lines", auth: true, summary: "填实盘数" },
  mCountPost: { method: "POST", path: "/biz/inventory/counts/:no/post", auth: true, summary: "盘点过账" },

  mTransferCreate: { method: "POST", path: "/biz/inventory/transfers", auth: true, summary: "建调拨单" },
  mTransferDetail: { method: "GET", path: "/biz/inventory/transfers/:no", auth: true, summary: "读回调拨单" },
  mTransferShip: { method: "POST", path: "/biz/inventory/transfers/:no/ship", auth: true, summary: "调拨发出" },
  mTransferReceive: { method: "POST", path: "/biz/inventory/transfers/:no/receive", auth: true, summary: "调拨收货" },

  mStockDocuments: { method: "GET", path: "/biz/inventory/documents", auth: true, summary: "出入库单据" },
  mStockMonthly: { method: "GET", path: "/biz/inventory/report/monthly", auth: true, summary: "进销存月报" },
  mStockRanking: { method: "GET", path: "/biz/inventory/report/ranking", auth: true, summary: "动销/滞销榜" },

  mStockLocations: { method: "GET", path: "/biz/inventory/locations", auth: true, summary: "库位与仓" },
  mWarehouseCreate: { method: "POST", path: "/biz/inventory/locations", auth: true, summary: "加一个仓" },
  mLocationSetSource: { method: "PUT", path: "/biz/inventory/locations/:id/source", auth: true, summary: "设发货源" },
};

export function buildPath(path: string, params: Record<string, string | number>): string {
  return path.replace(/:([a-zA-Z]+)/g, (_, k: string) => String(params[k] ?? ""));
}
