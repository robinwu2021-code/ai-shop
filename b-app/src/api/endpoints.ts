// B 端端点表 —— API 的唯一真源（同 C 端做法）。
// 一处声明，两处消费：http.ts 按表发请求；后端据此生成 controller 骨架。
import type { MerchantApi } from "./contract";

export type HttpMethod = "GET" | "POST";

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
  mLogin: { method: "POST", path: "/biz/auth/login", auth: false, summary: "商家登录" },
  mStaffLogin: { method: "POST", path: "/biz/auth/staff-login", auth: false, summary: "员工登录" },
  mProfile: { method: "GET", path: "/biz/merchant/profile", auth: true, summary: "商家资料" },

  mApply: { method: "POST", path: "/biz/merchant/apply", auth: true, summary: "提交入驻申请" },
  mApplyDraft: { method: "GET", path: "/biz/merchant/apply", auth: true, summary: "上次入驻申请" },
  mMasterData: { method: "GET", path: "/common/master-data", auth: false, summary: "平台主数据（行业/主体/通道）" },

  mPayments: { method: "GET", path: "/biz/merchant/payment", auth: true, summary: "收款进件状态" },
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
  mApplyCommunity: { method: "POST", path: "/biz/communities/apply", auth: true, summary: "提报平台还没有的小区" },
  mMyCommunityApplies: { method: "GET", path: "/biz/communities/applies", auth: true, summary: "我提报过的小区" },
  mSaveStore: { method: "POST", path: "/biz/store", auth: true, summary: "保存店铺门面" },

  mStoreList: { method: "GET", path: "/biz/store/list", auth: true, summary: "我的门店" },
  mCreateStore: { method: "POST", path: "/biz/store/create", auth: true, summary: "新建门店" },
  mRenameStore: { method: "POST", path: "/biz/store/:storeNo/rename", auth: true, summary: "改门店名与地址" },
  mSetStoreStatus: { method: "POST", path: "/biz/store/:storeNo/status", auth: true, summary: "停用/启用门店" },
  mSetDefaultStore: { method: "POST", path: "/biz/store/:storeNo/default", auth: true, summary: "设为默认店" },
  mSetStorePayment: { method: "POST", path: "/biz/store/:storeNo/payment", auth: true, summary: "换门店收款号" },

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

  mUploadImage: { method: "POST", path: "/biz/upload/image", auth: true, summary: "上传商品图" },
  mRecognizeGoods: { method: "POST", path: "/biz/goods/recognize", auth: true, summary: "拍照识别商品" },

  mCategoryTree: { method: "GET", path: "/biz/category/tree", auth: true, summary: "类目树（选类目）" },

  mSpecTemplates: { method: "GET", path: "/biz/spec-templates", auth: true, summary: "规格模板" },
  mSaveSpecTemplate: { method: "POST", path: "/biz/spec-templates", auth: true, summary: "存为常用规格" },

  mOrderList: { method: "GET", path: "/biz/order", auth: true, summary: "订单列表" },
  mOrderDetail: { method: "GET", path: "/biz/order/:orderNo", auth: true, summary: "订单详情" },
  mShip: { method: "POST", path: "/biz/order/:orderNo/ship", auth: true, summary: "快递发货" },
  mDelivered: { method: "POST", path: "/biz/order/:orderNo/delivered", auth: true, summary: "自送已送达" },
  mDeliveryRule: { method: "GET", path: "/biz/delivery/rule", auth: true, summary: "自送规则" },
  mSaveDeliveryRule: { method: "POST", path: "/biz/delivery/rule", auth: true, summary: "保存自送规则" },

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

  mCustomers: { method: "GET", path: "/biz/customers", auth: true, summary: "客户与复购" },

  mRateCard: { method: "GET", path: "/biz/settle/rate-card", auth: true, summary: "费率卡" },
  mSettleList: { method: "GET", path: "/biz/settle/bills", auth: true, summary: "结算单列表" },
  mReportShortage: { method: "POST", path: "/biz/pickup/:orderNo/report", auth: true, summary: "破损短少上报" },

  // ---------------------------------------------------------------- 积分（B-11.x）
  //
  // 商家**不感知积分抵扣**（V34）：他收到的是订单全额减各项费用。
  // 这里只有他自己发分的成本，以及开关。
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
};

export function buildPath(path: string, params: Record<string, string | number>): string {
  return path.replace(/:([a-zA-Z]+)/g, (_, k: string) => String(params[k] ?? ""));
}
