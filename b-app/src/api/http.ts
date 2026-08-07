// 真实后端实现 —— **不手写 URL**，全部从 endpoints.ts 取。
// 迁移时这个文件基本不用改，改的是 .env 里的开关。
import { http } from "@shared/net/http-client";
import { buildPath, ENDPOINTS as E } from "./endpoints";
import type { GoodsDraft, GoodsGuess, MerchantApi } from "./contract";
// 入参的 wire 契约。`satisfies` 让「实际发出去的 body」在编译期受检 ——
// 字段写错、少传、多传都编译不过，而不是等联调才发现（与 C 端同一套做法）
import type {
  AppealReviewReq,
  CreateGroupReq,
  GoodsListQuery,
  HandleAfterSaleReq,
  MarkArrivedReq,
  MerchantLoginReqBody,
  OrderListQuery,
  QuoteReq,
  RecognizeGoodsReq,
  ReplyReviewReq,
  ReportShortageReq,
  SaveSpecTemplateReq,
  SaveStockReq,
  ShareKitQuery,
  ShipReq,
  SpecTemplatesQuery,
  ToggleCampaignReq,
  ToggleGoodsReq,
  UploadImageReq,
  VerifyBatchReq,
  VerifyReq,
} from "./requests";
import type {
  Community,
  DeliveryRule,
  Goods,
  MerchantApplyReq,
  MerchantLoginResp,
  MerchantProfile,
  MerchantCustomer,
  MerchantStats,
  MerchantTodo,
  Order,
  PageResult,
  PickingRow,
  GroupBuy,
  GroupRequest,
  CampaignDraft,
  MarketingCampaign,
  Review,
  SettleBill,
  ShareKit,
  SpecTemplate,
  StoreProfile,
  StoreQrcode,
  LoginReq,
  VerifyBatchResult,
  PickupOverview,
  RateCard,
} from "@shared/types";

export const httpApi: MerchantApi = {
  mLogin: (req: LoginReq) =>
    http.post<MerchantLoginResp>(E.mLogin.path, { ...req } satisfies MerchantLoginReqBody),
  mProfile: () => http.get<MerchantProfile>(E.mProfile.path),
  mApply: (payload: MerchantApplyReq) => http.post<MerchantProfile>(E.mApply.path, payload),

  mApplyDraft: () => http.get<MerchantApplyReq | null>(E.mApplyDraft.path),

  mStore: () => http.get<StoreProfile>(E.mStore.path),
  mCommunities: () => http.get<Community[]>(E.mCommunities.path),
  mSaveStore: (payload) => http.post<StoreProfile>(E.mSaveStore.path, payload),
  mStoreQrcode: () => http.get<StoreQrcode>(E.mStoreQrcode.path),
  mShareKit: (goodsNo) =>
    http.get<ShareKit>(E.mShareKit.path, { goodsNo } satisfies ShareKitQuery),

  mTodo: () => http.get<MerchantTodo>(E.mTodo.path),
  mStats: () => http.get<MerchantStats>(E.mStats.path),

  mGoodsList: (q) => http.get<PageResult<Goods>>(E.mGoodsList.path, { ...q } satisfies GoodsListQuery),
  mGoodsDetail: (goodsNo) => http.get<Goods>(buildPath(E.mGoodsDetail.path, { goodsNo })),
  mSaveGoods: (payload: GoodsDraft) => http.post<Goods>(E.mSaveGoods.path, payload),
  mToggleGoods: (goodsNo, onSale) =>
    http.post<Goods>(buildPath(E.mToggleGoods.path, { goodsNo }), { onSale } satisfies ToggleGoodsReq),
  mSaveStock: (goodsNo, skuNo, stock) =>
    http.post<Goods>(buildPath(E.mSaveStock.path, { goodsNo }), { skuNo, stock } satisfies SaveStockReq),

  mUploadImage: (tempPath) =>
    http.post<{ url: string }>(E.mUploadImage.path, { tempPath } satisfies UploadImageReq),
  mRecognizeGoods: (imageUrl) =>
    http.post<GoodsGuess>(E.mRecognizeGoods.path, { imageUrl } satisfies RecognizeGoodsReq),

  mSpecTemplates: (categoryType) =>
    http.get<SpecTemplate[]>(E.mSpecTemplates.path, { categoryType } satisfies SpecTemplatesQuery),
  mSaveSpecTemplate: (payload) =>
    http.post<SpecTemplate>(E.mSaveSpecTemplate.path, { ...payload } satisfies SaveSpecTemplateReq),

  mOrderList: (q) => http.get<PageResult<Order>>(E.mOrderList.path, { ...q } satisfies OrderListQuery),
  mOrderDetail: (orderNo) => http.get<Order>(buildPath(E.mOrderDetail.path, { orderNo })),
  mShip: (orderNo, expressNo) =>
    http.post<Order>(buildPath(E.mShip.path, { orderNo }), { expressNo } satisfies ShipReq),
  mDelivered: (orderNo) => http.post<Order>(buildPath(E.mDelivered.path, { orderNo }), {}),
  mDeliveryRule: () => http.get<DeliveryRule>(E.mDeliveryRule.path),
  mSaveDeliveryRule: (rule) => http.post<DeliveryRule>(E.mSaveDeliveryRule.path, rule),

  mPickupOverview: () => http.get<PickupOverview>(E.mPickupOverview.path),
  mPickupOrders: () => http.get<Order[]>(E.mPickupOrders.path),
  mPickingList: () => http.get<PickingRow[]>(E.mPickingList.path),
  mMarkArrived: (orderNos) =>
    http.post<Order[]>(E.mMarkArrived.path, { orderNos } satisfies MarkArrivedReq),
  mVerify: (code) =>
    http.post<Order>(E.mVerify.path, { verifyCode: code } satisfies VerifyReq),
  mVerifyBatch: (codes) =>
    http.post<VerifyBatchResult>(E.mVerifyBatch.path, { verifyCodes: codes } satisfies VerifyBatchReq),

  mAfterSaleList: () => http.get<Order[]>(E.mAfterSaleList.path),
  mApproveAfterSale: (afterSaleNo, reply) =>
    http.post<Order>(buildPath(E.mApproveAfterSale.path, { afterSaleNo }), {
      remark: reply,
    } satisfies HandleAfterSaleReq),
  mRejectAfterSale: (afterSaleNo, reply) =>
    http.post<Order>(buildPath(E.mRejectAfterSale.path, { afterSaleNo }), {
      remark: reply,
    } satisfies HandleAfterSaleReq),

  mConfirmReturn: (afterSaleNo) =>
    http.post<Order>(buildPath(E.mConfirmReturn.path, { afterSaleNo }), {}),

  mGroupList: () => http.get<GroupBuy[]>(E.mGroupList.path),
  mCreateGroup: (goodsNo) =>
    http.post<GroupBuy>(E.mCreateGroup.path, { goodsNo } satisfies CreateGroupReq),
  mRequestList: () => http.get<GroupRequest[]>(E.mRequestList.path),
  mQuote: (requestNo, payload) =>
    http.post<GroupRequest>(buildPath(E.mQuote.path, { requestNo }), {
      // 契约方法的参数名是前端语义（priceMinor/minCount/desc），
      // 发到线上的字段名必须是后端那套 —— 这一层就是干这个的
      unitPriceMinor: payload.priceMinor,
      minQty: payload.minCount,
      note: payload.desc,
    } satisfies QuoteReq),

  mReviewList: () => http.get<Review[]>(E.mReviewList.path),
  mReplyReview: (reviewNo, reply) =>
    http.post<Review>(buildPath(E.mReplyReview.path, { reviewNo }), { reply } satisfies ReplyReviewReq),
  mAppealReview: (reviewNo, reason, images) =>
    http.post<Review>(buildPath(E.mAppealReview.path, { reviewNo }), {
      reason,
      images,
    } satisfies AppealReviewReq),

  mCampaignList: () => http.get<MarketingCampaign[]>(E.mCampaignList.path),
  mSaveCampaign: (payload) => http.post<MarketingCampaign>(E.mSaveCampaign.path, payload),
  mToggleCampaign: (campaignNo, running) =>
    http.post<MarketingCampaign>(buildPath(E.mToggleCampaign.path, { campaignNo }), {
      running,
    } satisfies ToggleCampaignReq),

  mCustomers: () => http.get<MerchantCustomer[]>(E.mCustomers.path),

  mSettleList: () => http.get<SettleBill[]>(E.mSettleList.path),
  mRateCard: () => http.get<RateCard>(E.mRateCard.path),
  mReportShortage: (orderNo, payload) =>
    http.post<Order>(buildPath(E.mReportShortage.path, { orderNo }), {
      ...payload,
    } satisfies ReportShortageReq),
};
