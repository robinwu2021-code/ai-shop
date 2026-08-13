// 真实后端实现 —— **不手写 URL**，全部从 endpoints.ts 的端点表取。
//
// 这样做的意义：端点只有一处定义，http 实现、mock、OpenAPI 三者不会漂移。
// 迁移到真实后端时这个文件基本不用改 —— 改的是 `.env` 里的开关。
import { http } from "@shared/net/http-client";
import { buildPath, ENDPOINTS } from "./endpoints";
import type { CreateOrderReq, GoodsQuery, ShopApi } from "./contract";
import type { InvoiceRequest } from "@shared/types";
// 入参的 wire 契约。satisfies 让「实际发出去的 body」在编译期受检 ——
// 字段写错、少传、多传都编译不过，而不是等联调才发现。
import type {
  AfterSaleReq,
  BindCommunityReq,
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
  return ep.method === "GET" ? http.get<T>(url, d) : http.post<T>(url, d);
}

export const httpApi: ShopApi = {
  // ---- 用户
  sendOtp: (phone: string) => call<void>("sendOtp", undefined, { phone }),
  login: (req: LoginReq) => call<LoginResp>("login", undefined, { ...req } satisfies LoginReqBody),
  profile: () => call<User>("profile"),
  logout: () => call<void>("logout"),
  bindCommunity: (communityNo, pickupNo) =>
    call<User>("bindCommunity", undefined, { communityNo, pickupNo } satisfies BindCommunityReq),

  // ---- 地址簿
  addressList: () => call<Address[]>("addressList"),
  saveAddress: (payload) => call<Address[]>("saveAddress", undefined, { ...payload } satisfies SaveAddressReq),
  removeAddress: (addressId) => call<Address[]>("removeAddress", { addressId }),
  setDefaultAddress: (addressId) => call<Address[]>("setDefaultAddress", { addressId }),

  // ---- 社区
  nearbyCommunities: (lat, lng) =>
    call<Community[]>("nearbyCommunities", undefined, { lat, lng } satisfies NearbyQuery),

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
  payOrder: (orderNo) => call<Order>("payOrder", { orderNo }),
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
  merchantList: (q) => call<Merchant[]>("merchantList", undefined, { ...q } satisfies MerchantListQuery),
  storeHome: (merchantNo, from) =>
    http.get<StoreHome>(buildPath(ENDPOINTS.storeHome.path, { merchantNo }), { from }),
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

  // ---- 团长
};
