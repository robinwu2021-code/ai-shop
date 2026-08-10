// 端点表 —— **API 的唯一真源**。
//
// 之前端点散在 http.ts 的实现里，mock 不知道自己对应哪个 URL，后端也无从对照。
// 现在一处声明，三处消费：
//   1. http.ts       —— 按表生成请求实现，不再手写 URL
//   2. mock          —— 按同一份 key 组织，保证一一对应
//   3. gen-openapi   —— 生成 docs/api/openapi.yaml，后端据此生成 controller 骨架
//
// 迁移到真实后端时，端点表不用改：`VITE_USE_MOCK=0` 之后走的就是这张表。
import type { ShopApi } from "./contract";

export type HttpMethod = "GET" | "POST";

export interface EndpointDef {
  method: HttpMethod;
  /** 路径，`:name` 为路径参数 */
  path: string;
  /** 是否需要登录态（Bearer）。false 的接口游客也能调 */
  auth: boolean;
  /** 一句话说明，会写进 OpenAPI 的 summary */
  summary: string;
  /**
   * 把契约方法的位置参数映射成 { path 参数, query/body }。
   * 不写则默认：第一个参数是对象 → 整体作为 query(GET)/body(POST)。
   */
  params?: (...args: never[]) => { path?: Record<string, string>; data?: unknown };
}

/** 键必须与 ShopApi 的方法名一一对应，漏一个会编译报错 */
export const ENDPOINTS: Record<keyof ShopApi, EndpointDef> = {
  // ---------------------------------------------------------------- 用户
  sendOtp: { method: "POST", path: "/mp/user/otp/send", auth: false, summary: "发送验证码" },
  login: { method: "POST", path: "/mp/user/login", auth: false, summary: "登录建户" },
  profile: { method: "GET", path: "/mp/user/profile", auth: true, summary: "我的资料" },
  bindCommunity: {
    method: "POST",
    path: "/mp/user/community",
    auth: true,
    summary: "绑定社区自提点",
  },

  // ---------------------------------------------------------------- 地址簿
  addressList: { method: "GET", path: "/mp/user/address", auth: true, summary: "地址列表" },
  saveAddress: { method: "POST", path: "/mp/user/address", auth: true, summary: "新增/编辑地址" },
  removeAddress: {
    method: "POST",
    path: "/mp/user/address/:addressId/archive",
    auth: true,
    summary: "删除地址（软删除）",
  },
  setDefaultAddress: {
    method: "POST",
    path: "/mp/user/address/:addressId/default",
    auth: true,
    summary: "设为默认地址",
  },

  // ---------------------------------------------------------------- 社区
  nearbyCommunities: {
    method: "GET",
    path: "/mp/community/nearby",
    auth: false,
    summary: "附近社区与自提点",
  },

  // ---------------------------------------------------------------- 商品
  goodsList: { method: "GET", path: "/mp/goods", auth: false, summary: "商品列表" },
  goodsDetail: { method: "GET", path: "/mp/goods/:goodsNo", auth: false, summary: "商品详情" },

  // ---------------------------------------------------------------- 购物车
  cartList: { method: "GET", path: "/mp/cart", auth: true, summary: "购物车" },
  cartAdd: { method: "POST", path: "/mp/cart/add", auth: true, summary: "加入购物车" },
  cartUpdate: { method: "POST", path: "/mp/cart/update", auth: true, summary: "修改数量" },
  cartRemove: { method: "POST", path: "/mp/cart/remove", auth: true, summary: "移除商品" },

  // ---------------------------------------------------------------- 交易
  createOrder: { method: "POST", path: "/mp/order", auth: true, summary: "下单（幂等）" },
  payOrder: { method: "POST", path: "/mp/order/:orderNo/pay", auth: true, summary: "支付" },
  orderList: { method: "GET", path: "/mp/order", auth: true, summary: "订单列表" },
  promotedGoods: { method: "GET", path: "/mp/goods/promoted", auth: false, summary: "推荐商品（运营位）" },
  promotedMerchants: { method: "GET", path: "/mp/merchant/promoted", auth: false, summary: "推荐门店（运营位）" },
  orderDetail: { method: "GET", path: "/mp/order/:orderNo", auth: true, summary: "订单详情" },
  cancelOrder: { method: "POST", path: "/mp/order/:orderNo/cancel", auth: true, summary: "取消订单" },
  applyAfterSale: {
    method: "POST",
    path: "/mp/order/:orderNo/after-sale",
    auth: true,
    summary: "申请售后",
  },

  // ---------------------------------------------------------------- 营销
  afterSaleReasons: { method: "GET", path: "/mp/after-sale/reasons", auth: false, summary: "售后原因清单" },
  afterSaleList: { method: "GET", path: "/mp/after-sale", auth: true, summary: "我的售后单" },
  fillReturnExpress: { method: "POST", path: "/mp/after-sale/:afterSaleNo/ship", auth: true, summary: "填退货运单号" },
  raiseDispute: { method: "POST", path: "/mp/after-sale/:afterSaleNo/escalate", auth: true, summary: "上升平台裁决" },

  couponList: { method: "GET", path: "/mp/coupon", auth: false, summary: "优惠券列表" },
  receiveCoupon: {
    method: "POST",
    path: "/mp/coupon/:couponNo/receive",
    auth: true,
    summary: "领取优惠券",
  },

  // ---------------------------------------------------------------- 拼团
  groupBuyList: { method: "GET", path: "/mp/group-buy", auth: false, summary: "商家团列表" },
  groupBuyDetail: {
    method: "GET",
    path: "/mp/group-buy/:groupNo",
    auth: false,
    summary: "商家团详情",
  },
  joinGroupBuy: {
    method: "POST",
    path: "/mp/group-buy/:groupNo/join",
    auth: true,
    summary: "参团",
  },
  createGroupBuy: { method: "POST", path: "/mp/group-buy", auth: true, summary: "发起商家团" },

  // ---------------------------------------------------------------- 邻里求团
  myHostedGroups: { method: "GET", path: "/mp/group-buy/hosted", auth: true, summary: "我发起的团" },
  confirmGroupBatch: { method: "POST", path: "/mp/group-buy/:groupNo/receive", auth: true, summary: "批次签收" },
  verifyGroupPickup: { method: "POST", path: "/mp/group-buy/:groupNo/verify", auth: true, summary: "发起人核销" },
  groupPickupOrders: { method: "GET", path: "/mp/group-buy/:groupNo/orders", auth: true, summary: "本团待取订单" },

  requestList: { method: "GET", path: "/mp/group-request", auth: false, summary: "求团列表" },
  requestDetail: {
    method: "GET",
    path: "/mp/group-request/:requestNo",
    auth: false,
    summary: "求团详情",
  },
  createRequest: { method: "POST", path: "/mp/group-request", auth: true, summary: "发起求团" },
  toggleInterest: {
    method: "POST",
    path: "/mp/group-request/:requestNo/interest",
    auth: true,
    summary: "+1 / 取消（意向，非订单）",
  },
  chooseQuote: {
    method: "POST",
    path: "/mp/group-request/:requestNo/choose",
    auth: true,
    summary: "发起人选定报价（锁价）",
  },
  confirmRequest: {
    method: "POST",
    path: "/mp/group-request/:requestNo/confirm",
    auth: true,
    summary: "二次确认下单",
  },

  // ---------------------------------------------------------------- 商家
  merchantList: { method: "GET", path: "/mp/merchant", auth: false, summary: "商家列表/搜索" },
  storeHome: { method: "GET", path: "/mp/store/:merchantNo", auth: false, summary: "门店主页" },
  frequentItems: { method: "GET", path: "/mp/store/:merchantNo/frequent", auth: true, summary: "常买清单" },
  reorderFrom: { method: "POST", path: "/mp/order/:orderNo/reorder", auth: true, summary: "一键再来一单" },
  toggleFavoriteStore: { method: "POST", path: "/mp/store/:merchantNo/favorite", auth: true, summary: "收藏本店" },
  myStores: { method: "GET", path: "/mp/store/mine", auth: true, summary: "我的常去店" },

  merchantDetail: {
    method: "GET",
    path: "/mp/merchant/:merchantNo",
    auth: false,
    summary: "商家详情",
  },
  visitedMerchants: {
    method: "GET",
    path: "/mp/merchant/visited",
    auth: true,
    summary: "我买过的商家",
  },
  masterData: { method: "GET", path: "/common/master-data", auth: false, summary: "平台主数据（行业/主体/通道）" },
  merchantApply: { method: "POST", path: "/mp/merchant/apply", auth: true, summary: "商家入驻申请" },
  myMerchantApply: { method: "GET", path: "/mp/merchant/apply", auth: true, summary: "我的入驻申请状态" },

  // ---------------------------------------------------------------- 评价
  reviewList: { method: "GET", path: "/mp/review", auth: false, summary: "评价列表" },
  createReview: { method: "POST", path: "/mp/review", auth: true, summary: "发表评价" },
  toggleReviewLike: {
    method: "POST",
    path: "/mp/review/:reviewNo/like",
    auth: true,
    summary: "点赞/取消",
  },

  // ---------------------------------------------------------------- 积分（一期开关关闭）
  //
  // 路径用**复数** `points`：与设计文档、B 端 `/biz/points/**` 一致。
  // 商家侧的两条已迁到 b-app —— ADR-007 §3「contract 层不共享」，
  // 它们挂在 C 端是契约写在 b-app 拆分之前留下的。
  pointAccount: { method: "GET", path: "/mp/points/account", auth: true, summary: "积分账户" },
  pointRecords: { method: "GET", path: "/mp/points/records", auth: true, summary: "积分流水" },
  pointsDeductible: {
    method: "GET",
    path: "/mp/points/deductible",
    auth: true,
    summary: "结算页试算：本单最多可抵多少",
  },

  // ---------------------------------------------------------------- 卡包
  myCards: { method: "GET", path: "/mp/card/mine", auth: true, summary: "我的卡包" },

  // ---------------------------------------------------------------- 消息
  messageList: { method: "GET", path: "/mp/message", auth: true, summary: "消息列表" },
  readMessage: {
    method: "POST",
    path: "/mp/message/:messageNo/read",
    auth: true,
    summary: "标记已读",
  },
  readAllMessages: { method: "POST", path: "/mp/message/read-all", auth: true, summary: "全部已读" },

  // ---------------------------------------------------------------- 团长
};

/** 把 `:name` 替换成实际值 */
export function buildPath(path: string, params?: Record<string, string>): string {
  if (!params) return path;
  return path.replace(/:([a-zA-Z]+)/g, (_, k: string) => encodeURIComponent(params[k] ?? ""));
}
