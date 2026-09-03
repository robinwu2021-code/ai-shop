// 真实后端实现 —— **不手写 URL**，全部从 endpoints.ts 的端点表取。
//
// 这样做的意义：端点只有一处定义，http 实现、mock、OpenAPI 三者不会漂移。
// 迁移到真实后端时这个文件基本不用改 —— 改的是 `.env` 里的开关。
import { http } from "@shared/net/http-client";
import { buildPath, ENDPOINTS } from "./endpoints";
import type { CreateOrderReq, GoodsQuery, ShopApi , PayInit, PayMethodList} from "./contract";
import type { InvoiceRequest, MyMembership, MyStoreCoupon, RegionNode, RegionOption,
  PhoneCapable,
} from "@shared/types";
// 入参的 wire 契约。satisfies 让「实际发出去的 body」在编译期受检 ——
// 字段写错、少传、多传都编译不过，而不是等联调才发现。
import type {
  AfterSaleReq,
  BindCommunityReq,
  BindPhoneReq,
  WxPhoneReq,
  CartAddReq,
  CartRemoveReq,
  CartUpdateReq,
  ChooseQuoteReq,
  CreateGroupBuyReq,
  CreateOrderReqBody,
  CreateRequestReq,
  CreateReviewReq,
  GoodsListQuery,
  GroupBuyListQuery,
  JoinGroupBuyReq,
  LoginReqBody,
  MarkArrivedReq,
  MerchantListQuery,
  NearbyQuery,
  PromotedGoodsQuery,
  PromotedMerchantsQuery,
  OrderListQuery,
  RequestListQuery,
  ReviewListQuery,
  SaveAddressReq,
  VerifyPickupReq,
  PointsDeductibleQuery,
} from "./requests";
import type {
  AfterSale,
  OrderPreview,
  CheckoutCapability,
  AfterSaleReason,
  Address,
  CartItem,
  Community,
  Coupon,
  UserCoupon,
  GroupPickupOrder,
  Goods,
  GroupBuy,
  GroupRequest,
  LoginReq,
  LoginResp,
  FrequentItem,
  Merchant,
  ReorderResult,
  StoreHome,
  Message,
  Order,
  PageQuery,
  PageResult,
  PointAccount,
  PointsDeductible,
  PointRecord,
  Review,
  MerchantApplyReq,
  MasterData,
  MerchantApplyStatus,
  User,
  UserCard,
  VisitedMerchant,
} from "@shared/types";

/** 按端点表发起请求。`p` 是路径参数，`d` 是 query(GET) 或 body(POST) */
function call<T>(
  key: keyof ShopApi,
  p?: Record<string, string>,
  d?: Record<string, unknown>,
): Promise<T> {
  const ep = ENDPOINTS[key];
  const url = buildPath(ep.path, p);
  /*
   * **按端点表里声明的方法发**，不是「非 GET 即 POST」。
   *
   * 原来那句 `ep.method === "GET" ? get : post` 把 PUT 静默降级成了 POST ——
   * 端点表里写着 PUT、真实后端上是 PUT，而端上发出去的是 POST：
   * 405，而且是运行时才知道。mock 下永远看不出来（mock 不看方法）。
   */
  if (ep.method === "GET") {
    return http.get<T>(url, d);
  }
  return ep.method === "PUT" ? http.put<T>(url, d) : http.post<T>(url, d);
}

export const httpApi: ShopApi = {
  // ---- 用户
  sendOtp: (phone: string) => call<void>("sendOtp", undefined, { phone }),
  login: (req: LoginReq) => call<LoginResp>("login", undefined, { ...req } satisfies LoginReqBody),
  profile: () => call<User>("profile"),
  logout: () => call<void>("logout"),
  bindPhone: (phone, code) =>
    call<User>("bindPhone", undefined, { phone, code } satisfies BindPhoneReq),
  bindPhoneByWx: (code) => call<User>("bindPhoneByWx", undefined, { code } satisfies WxPhoneReq),
  phoneCapable: () => call<PhoneCapable>("phoneCapable"),
  deregister: () => call<void>("deregister"),
  bindCommunity: (communityNo, pickupNo) =>
    call<User>("bindCommunity", undefined, { communityNo, pickupNo } satisfies BindCommunityReq),

  // ---- 地址簿
  activeAddress: () => call<Address | null>("activeAddress"),
  switchActiveAddress: (addressId) => call<Address>("switchActiveAddress", { addressId }),
  addressList: () => call<Address[]>("addressList"),
  saveAddress: (payload) => call<Address[]>("saveAddress", undefined, { ...payload } satisfies SaveAddressReq),
  removeAddress: (addressId) => call<Address[]>("removeAddress", { addressId }),
  setDefaultAddress: (addressId) => call<Address[]>("setDefaultAddress", { addressId }),

  // ---- 社区
  nearbyCommunities: (lat, lng) =>
    call<Community[]>("nearbyCommunities", undefined, { lat, lng } satisfies NearbyQuery),
  allCommunities: (regionCode) =>
    call<Community[]>("allCommunities", undefined, { regionCode }),
  openRegions: () => call<RegionOption[]>("openRegions"),
  regions: (parent) => call<RegionNode[]>("regions", undefined, { parent }),

  // ---- 商品
  goodsList: (q: GoodsQuery) => call<PageResult<Goods>>("goodsList", undefined, { ...q } satisfies GoodsListQuery),
  goodsDetail: (goodsNo) => call<Goods>("goodsDetail", { goodsNo }),

  // ---- 购物车
  cartList: () => call<CartItem[]>("cartList"),
  cartAdd: (goodsNo, skuNo, qty) =>
    call<CartItem[]>("cartAdd", undefined, { goodsNo, skuNo, qty } satisfies CartAddReq),
  cartUpdate: (skuNo, qty) => call<CartItem[]>("cartUpdate", undefined, { skuNo, qty } satisfies CartUpdateReq),
  cartRemove: (skuNos) => call<CartItem[]>("cartRemove", undefined, { skuNos } satisfies CartRemoveReq),

  // ---- 交易
  createOrder: (req: CreateOrderReq) => call<Order>("createOrder", undefined, { ...req } satisfies CreateOrderReqBody),
  payMethods: (orderNo) => call<PayMethodList>("payMethods", { orderNo }),
  payOrder: (orderNo, payChannel) => call<PayInit>("payOrder", { orderNo }, { payChannel }),
  orderList: (q: PageQuery & { status?: string }) =>
    call<PageResult<Order>>("orderList", undefined, { ...q } satisfies OrderListQuery),

  promotedGoods: (q) =>
    call<Goods[]>("promotedGoods", undefined, { ...q } satisfies PromotedGoodsQuery),
  promotedMerchants: (q) =>
    call<Merchant[]>("promotedMerchants", undefined, { ...q } satisfies PromotedMerchantsQuery),
  applyInvoice: (req) => call<InvoiceRequest>("applyInvoice", undefined, req),
  myInvoices: () => call<InvoiceRequest[]>("myInvoices"),
  invoiceOfOrder: (orderNo) => call<InvoiceRequest | null>("invoiceOfOrder", { orderNo }),
  orderDetail: (orderNo) => call<Order>("orderDetail", { orderNo }),
  cancelOrder: (orderNo) => call<Order>("cancelOrder", { orderNo }),
  applyAfterSale: (orderNo, reason, images, type) =>
    call<AfterSale>("applyAfterSale", { orderNo }, { reason, images, type } satisfies AfterSaleReq),

  afterSaleReasons: () => call<AfterSaleReason[]>("afterSaleReasons"),
  afterSaleList: () => call<AfterSale[]>("afterSaleList"),
  orderPreview: (req) => http.post<OrderPreview>(ENDPOINTS.orderPreview.path, req),
  orderCapability: (req) => http.post<CheckoutCapability>(ENDPOINTS.orderCapability.path, req),

  // ---- 营销
  fillReturnExpress: (afterSaleNo, expressNo) =>
    http.post<AfterSale>(buildPath(ENDPOINTS.fillReturnExpress.path, { afterSaleNo }), { expressNo }),
  raiseDispute: (afterSaleNo, reason) =>
    http.post<AfterSale>(buildPath(ENDPOINTS.raiseDispute.path, { afterSaleNo }), { reason }),

  couponList: () => call<Coupon[]>("couponList"),
  myStoreCoupons: () => call<MyStoreCoupon[]>("myStoreCoupons"),
  myMemberships: () => call<MyMembership[]>("myMemberships"),
  setMembershipReach: (entityNo, optOut) =>
    call<void>("setMembershipReach", { entityNo }, { optOut }),
  receiveCoupon: (couponNo) => call<UserCoupon>("receiveCoupon", { couponNo }),

  // ---- 拼团
  groupBuyList: (pickupNo) => call<GroupBuy[]>("groupBuyList", undefined, { pickupNo } satisfies GroupBuyListQuery),
  groupBuyDetail: (groupNo) => call<GroupBuy>("groupBuyDetail", { groupNo }),
  joinGroupBuy: (groupNo, qty) =>
    call<{ group: GroupBuy; justReached: boolean; refundPerMember: number }>(
      "joinGroupBuy",
      { groupNo },
      { qty } satisfies JoinGroupBuyReq,
    ),
  createGroupBuy: (goodsNo, pickupNo, neighbor) =>
    call<GroupBuy>("createGroupBuy", undefined, {
      goodsNo,
      pickupNo,
      neighbor,
    } satisfies CreateGroupBuyReq),

  // ---- 邻里求团
  myHostedGroups: () => http.get<GroupBuy[]>(ENDPOINTS.myHostedGroups.path),
  confirmGroupBatch: (groupNo) =>
    http.post<GroupBuy>(buildPath(ENDPOINTS.confirmGroupBatch.path, { groupNo }), {}),
  verifyGroupPickup: (groupNo, code) =>
    http.post<GroupPickupOrder>(buildPath(ENDPOINTS.verifyGroupPickup.path, { groupNo }), { code }),
  groupPickupOrders: (groupNo) =>
    http.get<GroupPickupOrder[]>(buildPath(ENDPOINTS.groupPickupOrders.path, { groupNo })),

  requestList: (pickupNo) => call<GroupRequest[]>("requestList", undefined, { pickupNo } satisfies RequestListQuery),
  requestDetail: (requestNo) => call<GroupRequest>("requestDetail", { requestNo }),
  createRequest: (payload) => call<GroupRequest>("createRequest", undefined, { ...payload } satisfies CreateRequestReq),
  toggleInterest: (requestNo) => call<GroupRequest>("toggleInterest", { requestNo }),
  chooseQuote: (requestNo, quoteNo) =>
    call<GroupRequest>("chooseQuote", { requestNo }, { quoteNo } satisfies ChooseQuoteReq),
  confirmRequest: (requestNo) => call<GroupRequest>("confirmRequest", { requestNo }),

  // ---- 商家
  /*
   * **分页信封要在这里拆掉。**
   *
   * 契约写的是 `Promise<Merchant[]>`，而 `/mp/merchant` 实际回的是
   * MyBatis-Plus 的分页对象 `{records: [...], total, ...}`。
   * 网络边界上的泛型是**断言不是校验** —— `call<Merchant[]>` 不会核对，
   * TypeScript 也拦不住，于是对象一路流进页面。
   *
   * 后果不是「列表空着」，是**整页空白**：`merchants` 页把它交给
   * `nearby.value.filter(...)`，对象没有 `filter`，computed 抛异常，
   * 连它自己的空态都渲染不出来 —— 页面上只剩标题栏和底部菜单，
   * 看着像还没加载完，而它已经结束了。
   *
   * 两种形状都收：后端哪天改回数组也不会再坏一次。
   */
  merchantList: (q) =>
    call<Merchant[] | { records?: Merchant[] }>("merchantList", undefined, {
      ...q,
    } satisfies MerchantListQuery).then((r) =>
      Array.isArray(r) ? r : (r?.records ?? []),
    ),
  storeHome: (merchantNo, from) =>
    http.get<StoreHome>(buildPath(ENDPOINTS.storeHome.path, { merchantNo }), { from }),
  storeByCode: (storeCode, deviceId) =>
    http.get<StoreHome>(ENDPOINTS.storeByCode.path, { storeCode, deviceId }),
  frequentItems: (merchantNo) =>
    http.get<FrequentItem[]>(buildPath(ENDPOINTS.frequentItems.path, { merchantNo })),
  reorderFrom: (orderNo) =>
    http.post<ReorderResult>(buildPath(ENDPOINTS.reorderFrom.path, { orderNo }), {}),
  toggleFavoriteStore: (merchantNo) =>
    http.post<boolean>(buildPath(ENDPOINTS.toggleFavoriteStore.path, { merchantNo }), {}),
  myStores: () => http.get<Merchant[]>(ENDPOINTS.myStores.path),

  merchantDetail: (merchantNo) => call<Merchant>("merchantDetail", { merchantNo }),
  visitedMerchants: () => call<VisitedMerchant[]>("visitedMerchants"),
  myMerchantApply: () => call<MerchantApplyStatus | null>("myMerchantApply"),
  masterData: () => call<MasterData>("masterData"),
  merchantApply: (payload) =>
    call<MerchantApplyStatus>("merchantApply", undefined, { ...payload } satisfies MerchantApplyReq),

  // ---- 评价
  reviewList: (q) => call<Review[]>("reviewList", undefined, { ...q } satisfies ReviewListQuery),
  createReview: (payload) => call<Review>("createReview", undefined, { ...payload } satisfies CreateReviewReq),
  toggleReviewLike: (reviewNo) => call<Review>("toggleReviewLike", { reviewNo }),

  // ---- 积分
  pointAccount: () => call<PointAccount>("pointAccount"),
  pointRecords: () => call<PointRecord[]>("pointRecords"),
  pointsDeductible: (q) => call<PointsDeductible>("pointsDeductible", undefined, { ...q } satisfies PointsDeductibleQuery),

  // ---- 卡包
  myCards: () => call<UserCard[]>("myCards"),

  // ---- 消息
  messageList: () => call<Message[]>("messageList"),
  readMessage: (messageNo) => call<Message[]>("readMessage", { messageNo }),
  readAllMessages: () => call<Message[]>("readAllMessages"),
  unreadMessages: () => call<number>("unreadMessages"),
  subscribeReport: (templateIds, accepted) =>
    call<void>("subscribeReport", undefined, { templateIds, accepted }),

  registerPushToken: (platform, provider, clientId) =>
    call<void>("registerPushToken", undefined, { platform, provider, clientId }),
  unregisterPushToken: (clientId) =>
    call<void>("unregisterPushToken", undefined, { clientId }),

  // ---- 团长
};
