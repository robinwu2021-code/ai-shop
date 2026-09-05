// 真实后端实现 —— **不手写 URL**，全部从 endpoints.ts 取。
// 迁移时这个文件基本不用改，改的是 .env 里的开关。
import { http } from "@shared/net/http-client";
import { buildPath, ENDPOINTS as E } from "./endpoints";
import type { EstateList, GoodsDraft, GoodsGuess, MerchantApi, PublishPreview,
  WithdrawPage, WithdrawRecord, DepositAccount, DepositTxn, PendingInvoice, PlatformInvoiceTitle,
  PurchaseInvoice, Statement } from "./contract";
// 入参的 wire 契约。`satisfies` 让「实际发出去的 body」在编译期受检 ——
// 字段写错、少传、多传都编译不过，而不是等联调才发现（与 C 端同一套做法）
import type {
  AppointmentSlotOpenReq,
  AppealReviewReq,
  SaveGoodsReqBody,
  CreateGroupReq,
  CrossStoreCompareQuery,
  GoodsListQuery,
  HandleAfterSaleReq,
  MarkArrivedReq,
  MerchantLoginReqBody,
  PointsRecordQuery,
  TogglePointsReq,
  OrderListQuery,
  QuoteReq,
  DescribeGoodsReq,
  RecognizeGoodsReq,
  ReplyReviewReq,
  ReportShortageReq,
  SaveSpecTemplateReq,
  SaveStockReq,
  ShareKitQuery,
  ShipReq,
  SpecTemplatesQuery,
  StaffLoginReq,
  SubmitPaymentReq,
  StoreEditReq,
  SetActiveReq,
  SetStorePaymentReq,
  AddStaffReq,
  GrantStoreReq,
  ToggleCampaignReq,
  ToggleGoodsReq,
  VerifyBatchReq,
  VerifyReq,
  StoreFulfillmentSaveReq,
  PickupSelfBuildReq,
  OpenFromMapReq,
} from "./requests";
import type {
  IncomeSummary,
  AppointmentSlot,
  BizScope,
  AfterSale,
  Category,
  Community,
  CommunityApply,
  CommunityApplyReq,
  CrossStoreCompare,
  CrossStoreOverview,
  MerchantPlan,
  Region,
  DeliveryRule,
  Goods,
  MerchantApplyReq,
  MerchantPointAccount,
  MerchantPointsRecord,
  MasterData,
  Message,
  MerchantStaff,
  StaffLog,
  MerchantRole,
  PermOption,
  PickupOrder,
  Quote,
  VerifyResult,
  Entity,
  EntityStores,
  Store,
  StoreCategory,
  PaymentApplyment,
  MerchantApplyStatus,
  MerchantLoginResp,
  MerchantProfile,
  MerchantCustomer,
  Member,
  MemberDetail,
  ActivityConflict,
  ReachPlan,
  ReachResult,
  CouponIssueBatch,
  CouponRedeemResult,
  CouponRedeemView,
  MerchantCoupon,
  StoreActivity,
  MemberMergePreview,
  MemberSegment,
  MemberSegmentPreview,
  MemberSetting,
  MemberStats,
  MemberTag,
  MerchantStats,
  MerchantTodo,
  Order,
  PageResult,
  PickingRow,
  GroupBuy,
  GroupRequest,
  CampaignDraft,
  MarketingCampaign,
  Poster,
  Review,
  SettleBill,
  ShareKit,
  SkuIdentityReport,
  SpecTemplate,
  SpuStd,
  ScopePreview,
  StoreProfile,
  StoreQrcode,
  LoginReq,
  VerifyBatchResult,
  PickupOverview,
  MyDebt,
  MySettleBatch,
  RateCard,
  StoreFulfillment,
  GeoReverseResult,
  GeoTip,
  PickupCandidate,
  RegionSearchResult,
  MyQualifications,
  Qualification, MerchantSpecDim, StoreCategorySpecs, SpecOverride, SpecOption,
  StockSummary, StockBalance, StockCrossStoreRow, StockItemDetail, StockLedgerPage, StockDocument, Supplier, Carrier,
  StockMonthly, StockRank, StockLocation, StockLineReq, StockCountFilled,
  StockCount, StockTransfer, I18nText,
} from "@shared/types";

export const httpApi: MerchantApi = {
  mSendOtp: (phone) => http.post<void>(E.mSendOtp.path, { phone }),
  mSetPassword: (password) => http.post<void>(E.mSetPassword.path, { password }),
  mHasPassword: () => http.get<{ hasPassword: boolean }>(E.mHasPassword.path),
  mLogin: (req: LoginReq) =>
    http.post<MerchantLoginResp>(E.mLogin.path, { ...req } satisfies MerchantLoginReqBody),
  mStaffLogin: (payload) =>
    http.post<MerchantLoginResp>(E.mStaffLogin.path, payload satisfies StaffLoginReq),

  mProfile: () => http.get<MerchantProfile>(E.mProfile.path),

  mApply: (payload: MerchantApplyReq) => http.post<MerchantProfile>(E.mApply.path, payload),
  mQuickStart: (payload) => http.post<MerchantProfile>(E.mQuickStart.path, payload),
  mApplyDraft: () => http.get<MerchantApplyStatus | null>(E.mApplyDraft.path),
  mMasterData: () => http.get<MasterData>(E.mMasterData.path),

  mPayments: (entityNo) =>
    http.get<PaymentApplyment[]>(E.mPayments.path, entityNo ? { entityNo } : undefined),
  mPayChannels: (entityNo) =>
    http.get<PaymentApplyment[]>(E.mPayChannels.path, entityNo ? { entityNo } : undefined),
  mSubmitPayment: (payload) =>
    http.post<PaymentApplyment>(E.mSubmitPayment.path, payload satisfies SubmitPaymentReq),
  mRefreshPayment: (payChannel) =>
    http.post<PaymentApplyment>(buildPath(E.mRefreshPayment.path, { payChannel })),
  mOpenStorePayment: (storeNo, payChannel) =>
    http.post<PaymentApplyment>(buildPath(E.mOpenStorePayment.path, { storeNo }),
      payChannel ? { payChannel } : {}),

  mStore: () => http.get<StoreProfile>(E.mStore.path),
  mCommunities: () => http.get<Community[]>(E.mCommunities.path),
  mRegions: (parent) =>
    http.get<Region[]>(E.mRegions.path, parent ? { parent } : undefined),

  mVillageDict: (street, keyword) =>
    http.get<Region[]>(E.mVillageDict.path, { street, keyword }),
  mOpenCommunityFromMap: (payload) =>
    http.post<Community>(E.mOpenCommunityFromMap.path, payload satisfies OpenFromMapReq),
  mApplyCommunity: (payload) =>
    http.post<CommunityApply>(E.mApplyCommunity.path, payload satisfies CommunityApplyReq),
  mMyCommunityApplies: () => http.get<CommunityApply[]>(E.mMyCommunityApplies.path),
  mSaveStore: (payload) => http.post<StoreProfile>(E.mSaveStore.path, payload),
  mScopePreview: (areas) => http.post<ScopePreview>(E.mScopePreview.path, { areas }),
  mSaveAnnouncement: (payload) => http.post<StoreProfile>(E.mSaveAnnouncement.path, payload),
  mDropNoticeRecent: (text) => http.post<StoreProfile>(E.mDropNoticeRecent.path, { text }),
  mStoreFulfillment: (storeNo) =>
    http.get<StoreFulfillment>(buildPath(E.mStoreFulfillment.path, { storeNo })),
  mSaveStoreFulfillment: (storeNo, payload) =>
    http.put<StoreFulfillment>(
      buildPath(E.mSaveStoreFulfillment.path, { storeNo }),
      payload satisfies StoreFulfillmentSaveReq,
    ),
  mFulfillmentImpact: (storeNo, channel) =>
    http.get<Array<{ goodsNo: string; title: string }>>(
      buildPath(E.mFulfillmentImpact.path, { storeNo, channel }),
    ),
  mPickupCandidates: (storeNo) =>
    http.get<PickupCandidate[]>(E.mPickupCandidates.path, { storeNo }),
  mSelfBuildPickup: (payload) =>
    http.post<PickupCandidate>(E.mSelfBuildPickup.path, payload satisfies PickupSelfBuildReq),
  mRegionSearch: (kw, near) =>
    http.get<RegionSearchResult>(E.mRegionSearch.path,
      near ? { kw, latE6: near.latE6, lngE6: near.lngE6 } : { kw }),
  mRegionPath: (code) => http.get<Region[]>(E.mRegionPath.path, { code }),
  mGeoReverse: (lat, lng) => http.get<GeoReverseResult>(E.mGeoReverse.path, { lat, lng }),
  mGeoTips: (kw, city) => http.get<GeoTip[]>(E.mGeoTips.path, city ? { kw, city } : { kw }),
  mEstates: (regionCode, opts) => http.get<EstateList>(E.mEstates.path, { regionCode, ...opts }),
  mEstateCounts: (parentCode) => http.get<Record<string, number>>(E.mEstateCounts.path, { parentCode }),
  mStoreList: () => http.get<Store[]>(E.mStoreList.path),
  mMyStores: () => http.get<EntityStores[]>(E.mMyStores.path),
  mEntities: () => http.get<Entity[]>(E.mEntities.path),
  mEntity: (entityNo) => http.get<EntityStores>(buildPath(E.mEntity.path, { entityNo })),
  mCreateStore: (payload) =>
    http.post<Store>(E.mCreateStore.path, payload satisfies StoreEditReq),
  mRenameStore: (storeNo, payload) =>
    http.post<Store>(buildPath(E.mRenameStore.path, { storeNo }), payload satisfies StoreEditReq),
  mSetStoreStatus: (storeNo, active) =>
    http.post<Store>(buildPath(E.mSetStoreStatus.path, { storeNo }), { active } satisfies SetActiveReq),
  mSetDefaultStore: (storeNo) =>
    http.post<Store>(buildPath(E.mSetDefaultStore.path, { storeNo })),
  mSetStorePayment: (storeNo, payMerchantNo) =>
    http.post<Store>(buildPath(E.mSetStorePayment.path, { storeNo }),
      { payMerchantNo } satisfies SetStorePaymentReq),
  mQualifications: (entityNo) =>
    http.get<MyQualifications>(E.mQualifications.path, entityNo ? { entityNo } : undefined),
  mSaveQualification: (payload) =>
    http.post<Qualification>(E.mSaveQualification.path, { ...payload }),
  mStoreCategories: (storeNo) =>
    http.get<StoreCategory[]>(buildPath(E.mStoreCategories.path, { storeNo })),
  mSaveStoreCategories: (storeNo, items) =>
    http.post<StoreCategory[]>(buildPath(E.mSaveStoreCategories.path, { storeNo }), { items }),

  mStaffList: () => http.get<MerchantStaff[]>(E.mStaffList.path),
  mAddStaff: (loginPhone, displayName) =>
    http.post<MerchantStaff>(E.mAddStaff.path,
      { loginPhone, displayName } satisfies AddStaffReq),
  mSetStaffStatus: (mchAccountNo, active) =>
    http.post<MerchantStaff>(buildPath(E.mSetStaffStatus.path, { mchAccountNo }),
      { active } satisfies SetActiveReq),
  mBizScope: () => http.get<BizScope>(E.mBizScope.path),
  mGrantStore: (mchAccountNo, storeNo, role, granted) =>
    http.post<MerchantStaff>(buildPath(E.mGrantStore.path, { mchAccountNo }),
      { storeNo, role, granted } satisfies GrantStoreReq),
  // 只看某个人的时候才带参数：后端把空串当成「全部」也行，但少发一个空参数少一处歧义
  mStaffLogs: (mchAccountNo) =>
    http.get<StaffLog[]>(E.mStaffLogs.path, mchAccountNo ? { mchAccountNo } : undefined),

  mRoles: () => http.get<MerchantRole[]>(E.mRoles.path),
  mRolePerms: () => http.get<PermOption[]>(E.mRolePerms.path),
  mCreateRole: (payload) => http.post<MerchantRole>(E.mCreateRole.path, payload),
  mUpdateRole: (roleCode, payload) =>
    http.post<MerchantRole>(buildPath(E.mUpdateRole.path, { roleCode }), payload),
  mDeleteRole: (roleCode) =>
    http.post<void>(buildPath(E.mDeleteRole.path, { roleCode }), {}),

  mStoreQrcode: () => http.get<StoreQrcode>(E.mStoreQrcode.path),
  mShareKit: (goodsNo) =>
    http.get<ShareKit>(E.mShareKit.path, { goodsNo } satisfies ShareKitQuery),
  mPoster: (goodsNo) =>
    http.get<Poster>(E.mPoster.path, { goodsNo } satisfies ShareKitQuery),

  mTodo: () => http.get<MerchantTodo>(E.mTodo.path),
  mStats: () => http.get<MerchantStats>(E.mStats.path),

  mCrossStoreOverview: () => http.get<CrossStoreOverview>(E.mCrossStoreOverview.path),
  /*
   * days 不传就不发这个参数：后端的默认值是 30，端上再抄一遍就是两处默认值，
   * 改一处另一处静默不动。
   */
  mCrossStoreCompare: (days) =>
    http.get<CrossStoreCompare>(
      E.mCrossStoreCompare.path,
      (days === undefined ? {} : { days }) satisfies CrossStoreCompareQuery,
    ),

  mMyPlan: () => http.get<MerchantPlan>(E.mMyPlan.path),
  // 无 body：试用的目标档位由后端按「可试用且在售、sort 最小」选 ——
  // 端上传档位码等于把定价逻辑抄到端上，而它会与后端各自演进
  mStartTrial: () => http.post<MerchantPlan>(E.mStartTrial.path),

  mGoodsList: (q) => http.get<PageResult<Goods>>(E.mGoodsList.path, { ...q } satisfies GoodsListQuery),
  mGoodsDetail: (goodsNo) => http.get<Goods>(buildPath(E.mGoodsDetail.path, { goodsNo })),
  /*
   * 拍平三语：页面拿 I18nText（一个对象）编辑，后端要的是
   * 「基准语言那一份 + 三语 map」。这层转换放在这里而不是页面里 ——
   * 页面不该知道线上格式，而后端也不该被迫接受两种形状。
   */
  mSaveGoods: (payload: GoodsDraft) =>
    http.post<Goods>(E.mSaveGoods.path, {
      goodsNo: payload.goodsNo,
      title: payload.title["zh-CN"] ?? "",
      subtitle: payload.subtitle["zh-CN"] ?? "",
      titleI18n: { ...payload.title },
      subtitleI18n: { ...payload.subtitle },
      // type 不发：五品类由 categoryNo 派生，后端拿到也会忽略（P1-1）
      categoryNo: payload.categoryNo,
      cover: payload.cover,
      images: payload.images,
      // 详情图：空数组也要发，理由同 images
      detailImages: payload.detailImages,
      // 图文详情：空串也要发 —— 后端「不传 = 不改」，删光了不发就删不掉
      detail: payload.detail,
      // 商品参数：同上，**空数组也要发**。漏发这一行的代价是编辑页那一栏白填 ——
      // 商家填完保存、参数原地消失，而页面、后端、日志都不报错
      params: payload.params,
      specGroups: payload.specGroups,
      skus: payload.skus,
      // 溯源。不传 = 自建品 / 脱离标准品（后端据此清空 std_no）
      stdNo: payload.stdNo,
      limitPerUser: payload.limitPerUser,
      fresh: payload.fresh,
      service: payload.service,
      groupBuy: payload.groupBuy,
      fulfillments: payload.fulfillments,
    } satisfies SaveGoodsReqBody),
  mToggleGoods: (goodsNo, onSale) =>
    http.post<Goods>(buildPath(E.mToggleGoods.path, { goodsNo }), { onSale } satisfies ToggleGoodsReq),
  mSaveStock: (goodsNo, skuNo, stock) =>
    http.post<Goods>(buildPath(E.mSaveStock.path, { goodsNo }), { skuNo, stock } satisfies SaveStockReq),
  mSaveStoreStock: (goodsNo, skuNo, stock) =>
    http.post<Goods>(buildPath(E.mSaveStoreStock.path, { goodsNo }), { skuNo, stock } satisfies SaveStockReq),
  mSaveStorePrice: (goodsNo, skuNo, price) =>
    http.post<Goods>(buildPath(E.mSaveStorePrice.path, { goodsNo }), { skuNo, price }),
  mSubmitGoods: (goodsNo) => http.post<Goods>(buildPath(E.mSubmitGoods.path, { goodsNo })),
  /*
   * 草稿回读：线上存的是 mSaveGoods 拍平后的那份（title 是基准语言字符串 +
   * titleI18n 三语 map），这里做**逆转换**回页面的三语对象 —— 与 mSaveGoods
   * 的拍平同一个边界：页面不该知道线上格式。老草稿缺 titleI18n 时回落单语。
   */
  mGoodsDraft: async (goodsNo) => {
    const d = await http.get<SaveGoodsReqBody | null>(buildPath(E.mGoodsDraft.path, { goodsNo }));
    if (!d) return null;
    // I18nText 三键齐全：老草稿缺三语 map 时用基准语言兜底，缺译的语言留空
    // （空 = 未翻译，与编辑页三格的初始态同义；渲染端按 R9 回落中文）
    const langs = (base: string, m?: Record<string, string>): I18nText => ({
      "zh-CN": m?.["zh-CN"] ?? base,
      en: m?.en ?? "",
      ar: m?.ar ?? "",
    });
    return {
      ...d,
      title: langs(d.title, d.titleI18n),
      subtitle: langs(d.subtitle, d.subtitleI18n),
    };
  },
  mPublishPreview: (goodsNo) =>
    http.get<PublishPreview>(buildPath(E.mPublishPreview.path, { goodsNo })),
  // confirmVersion 不传就不发 body：后端 @RequestBody(required=false)，正常发布无需确认
  mPublishGoods: (goodsNo, confirmVersion) =>
    http.post<Goods>(buildPath(E.mPublishGoods.path, { goodsNo }),
      confirmVersion === undefined ? undefined : { confirmVersion }),
  mDiscardGoodsDraft: (goodsNo) =>
    http.post<Goods>(buildPath(E.mDiscardGoodsDraft.path, { goodsNo })),
  mSavePresale: (goodsNo, cutoffAt, arrivalDesc) =>
    http.post<Goods>(buildPath(E.mSavePresale.path, { goodsNo }), { cutoffAt, arrivalDesc }),

  // 真上传文件字节（multipart），不是把本地路径当 JSON 发 —— 后端要 MultipartFile
  mUploadImage: (tempPath) =>
    http.uploadFile<{ url: string }>(E.mUploadImage.path, tempPath),
  mRecognizeGoods: (imageUrl) =>
    http.post<GoodsGuess>(E.mRecognizeGoods.path, { imageUrl } satisfies RecognizeGoodsReq),

  mDescribeGoods: (req) =>
    http.post<{ detail: string }>(E.mDescribeGoods.path, req satisfies DescribeGoodsReq),

  mCategoryTree: () => http.get<Category[]>(E.mCategoryTree.path),
  mSpuStdSearch: (q) => http.get<SpuStd[]>(E.mSpuStdSearch.path, { ...q }),

  mSpecTemplates: (categoryType, categoryNo) =>
    http.get<SpecTemplate[]>(E.mSpecTemplates.path, { categoryType, categoryNo } satisfies SpecTemplatesQuery),
  mPickableDims: (categoryNo) => http.get<SpecTemplate[]>(E.mPickableDims.path, { categoryNo }),
  mSpecProps: (categoryNo) => http.get<SpecTemplate[]>(E.mSpecProps.path, { categoryNo }),
  mPickableProps: (categoryNo) => http.get<SpecTemplate[]>(E.mPickableProps.path, { categoryNo }),
  mAddSpecValue: (dimNo, label) =>
    http.post<{ valueNo: string; code: string; label: string }>(E.mAddSpecValue.path, { dimNo, label }),
  mSkuIdentityExport: () => http.get<{ csv: string }>(E.mSkuIdentityExport.path),
  mSkuIdentityPlan: (csv) => http.post<SkuIdentityReport>(E.mSkuIdentityPlan.path, { csv }),
  mSkuIdentityImport: (csv) => http.post<SkuIdentityReport>(E.mSkuIdentityImport.path, { csv }),
  mAddSpecDim: (name, labels, usageType) =>
    http.post<SpecTemplate>(E.mAddSpecDim.path, { name, labels, usageType }),
  mMySpecDims: () => http.get<MerchantSpecDim[]>(E.mMySpecDims.path),
  mStoreSpecDims: (storeNo) => http.get<StoreCategorySpecs[]>(E.mStoreSpecDims.path, { storeNo }),
  mDimValues: (dimNo) => http.get<SpecOption[]>(E.mDimValues.path.replace("{dimNo}", dimNo)),
  mSaveSpecOverride: (categoryNo, dims) =>
    http.post<SpecTemplate[]>(E.mSaveSpecOverride.path.replace("{categoryNo}", categoryNo), { dims }),
  mRenameSpecDim: (dimNo, name) =>
    http.post<void>(E.mRenameSpecDim.path.replace("{dimNo}", dimNo), { name }),
  mArchiveSpecDim: (dimNo, archived) =>
    http.post<void>(E.mArchiveSpecDim.path.replace("{dimNo}", dimNo), { archived }),
  mSaveSpecTemplate: (payload) =>
    http.post<SpecTemplate>(E.mSaveSpecTemplate.path, { ...payload } satisfies SaveSpecTemplateReq),

  mOrderList: (q) => http.get<PageResult<Order>>(E.mOrderList.path, { ...q } satisfies OrderListQuery),
  mOrderDetail: (orderNo) => http.get<Order>(buildPath(E.mOrderDetail.path, { orderNo })),
  mShip: (orderNo, expressNo) =>
    http.post<Order>(buildPath(E.mShip.path, { orderNo }), { expressNo } satisfies ShipReq),
  mDelivered: (orderNo) => http.post<Order>(buildPath(E.mDelivered.path, { orderNo }), {}),
  mConfirmOfflinePay: (subOrderNo) =>
    http.post<Order>(buildPath(E.mConfirmOfflinePay.path, { orderNo: subOrderNo }), {}),
  mAppointmentSlots: (storeNo, from, to) =>
    http.get<AppointmentSlot[]>(buildPath(E.mAppointmentSlots.path, { storeNo }), { from, to }),
  mOpenAppointmentSlot: (storeNo, slot) =>
    http.post<AppointmentSlot>(
      buildPath(E.mOpenAppointmentSlot.path, { storeNo }),
      slot satisfies AppointmentSlotOpenReq,
    ),
  mCloseAppointmentSlot: (slotNo) =>
    http.post<AppointmentSlot>(buildPath(E.mCloseAppointmentSlot.path, { slotNo }), {}),
  mDeliveryRule: () => http.get<DeliveryRule>(E.mDeliveryRule.path),
  mSaveDeliveryRule: (rule) => http.post<DeliveryRule>(E.mSaveDeliveryRule.path, rule),

  mPickupOverview: () => http.get<PickupOverview>(E.mPickupOverview.path),
  mPickupOrders: () => http.get<PickupOrder[]>(E.mPickupOrders.path),
  mPickingList: () => http.get<PickingRow[]>(E.mPickingList.path),
  mMarkArrived: (orderNos, pickupNo) =>
    http.post<PickupOrder[]>(E.mMarkArrived.path, { orderNos, pickupNo } satisfies MarkArrivedReq),
  mVerify: (code) =>
    http.post<VerifyResult>(E.mVerify.path, { verifyCode: code } satisfies VerifyReq),
  mVerifyBatch: (codes) =>
    http.post<VerifyBatchResult>(E.mVerifyBatch.path, { verifyCodes: codes } satisfies VerifyBatchReq),
  // 按码片段搜单：输码核销失败后的兜底，keyword 走 query
  mVerifySearch: (keyword) =>
    http.get<PickupOrder[]>(E.mVerifySearch.path, { keyword }),

  mWithdrawPage: () => http.get<WithdrawPage>(E.mWithdrawPage.path),
  mDeposit: () => http.get<DepositAccount>(E.mDeposit.path),
  mDepositTxns: () => http.get<DepositTxn[]>(E.mDepositTxns.path),
  mPendingInvoice: () => http.get<PendingInvoice>(E.mPendingInvoice.path),
  mInvoiceTitle: () => http.get<PlatformInvoiceTitle>(E.mInvoiceTitle.path),
  mMyInvoices: () => http.get<PurchaseInvoice[]>(E.mMyInvoices.path),
  mStatement: (period) => http.get<Statement>(E.mStatement.path, period ? { period } : undefined),
  mSubmitInvoice: (v) => http.post<PurchaseInvoice>(E.mSubmitInvoice.path, v),
  mApplyWithdraw: (amountMinor: number) =>
    http.post<WithdrawRecord>(E.mApplyWithdraw.path, { amountMinor }),
  mAfterSaleList: () => http.get<AfterSale[]>(E.mAfterSaleList.path),
  mApproveAfterSale: (afterSaleNo, reply) =>
    http.post<AfterSale>(buildPath(E.mApproveAfterSale.path, { afterSaleNo }), {
      remark: reply,
    } satisfies HandleAfterSaleReq),
  mRejectAfterSale: (afterSaleNo, reply) =>
    http.post<AfterSale>(buildPath(E.mRejectAfterSale.path, { afterSaleNo }), {
      remark: reply,
    } satisfies HandleAfterSaleReq),

  mConfirmReturn: (afterSaleNo) =>
    http.post<AfterSale>(buildPath(E.mConfirmReturn.path, { afterSaleNo }), {}),

  mGroupList: () => http.get<GroupBuy[]>(E.mGroupList.path),
  mCreateGroup: (goodsNo) =>
    http.post<GroupBuy>(E.mCreateGroup.path, { goodsNo } satisfies CreateGroupReq),
  mRequestList: () => http.get<GroupRequest[]>(E.mRequestList.path),
  mQuote: (requestNo, payload) =>
    http.post<Quote>(buildPath(E.mQuote.path, { requestNo }), {
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
  mMembers: (q) => http.get<PageResult<Member>>(E.mMembers.path, { ...(q ?? {}) }),
  mMemberStats: (storeNo) => http.get<MemberStats>(E.mMemberStats.path, { storeNo }),
  mMemberDetail: (memberNo) =>
    http.get<MemberDetail>(buildPath(E.mMemberDetail.path, { memberNo })),
  mEnrollMember: (payload) => http.post<Member>(E.mEnrollMember.path, payload),
  mPatchMember: (memberNo, payload) =>
    http.put<Member>(buildPath(E.mPatchMember.path, { memberNo }), payload),
  mTagMembers: (payload) => http.post<void>(E.mTagMembers.path, payload),
  mMemberTags: () => http.get<MemberTag[]>(E.mMemberTags.path),
  mCreateMemberTag: (name) => http.post<MemberTag>(E.mCreateMemberTag.path, { name }),
  mEditMemberTag: (tagNo, payload) =>
    http.put<MemberTag>(buildPath(E.mEditMemberTag.path, { tagNo }), payload),
  mMergeMemberTag: (tagNo, payload) =>
    http.post<MemberMergePreview>(buildPath(E.mMergeMemberTag.path, { tagNo }), payload),

  mMemberSettings: () => http.get<MemberSetting>(E.mMemberSettings.path),
  mSaveMemberSettings: (payload) =>
    http.put<MemberSetting>(E.mSaveMemberSettings.path, payload),
  mMemberSegments: () => http.get<MemberSegment[]>(E.mMemberSegments.path),
  mSaveMemberSegment: (payload) =>
    http.post<MemberSegment>(E.mSaveMemberSegment.path, payload),
  mRemoveMemberSegment: (segmentNo) =>
    http.post<void>(buildPath(E.mRemoveMemberSegment.path, { segmentNo })),
  mPreviewMemberSegment: (payload) =>
    http.post<MemberSegmentPreview>(E.mPreviewMemberSegment.path, payload),

  mCoupons: (includeEnded) =>
    http.get<MerchantCoupon[]>(E.mCoupons.path, includeEnded ? { includeEnded: true } : undefined),
  mCoupon: (couponNo) => http.get<MerchantCoupon>(buildPath(E.mCoupon.path, { couponNo })),
  mSaveCoupon: (payload) => http.post<MerchantCoupon>(E.mSaveCoupon.path, payload),
  mSetCouponStatus: (couponNo, status) =>
    http.put<MerchantCoupon>(buildPath(E.mSetCouponStatus.path, { couponNo }), { status }),
  mIssueCoupon: (couponNo, segmentNo) =>
    http.post<CouponIssueBatch>(buildPath(E.mIssueCoupon.path, { couponNo }), { segmentNo }),
  mCouponIssues: (couponNo) =>
    http.get<CouponIssueBatch[]>(E.mCouponIssues.path, couponNo ? { couponNo } : undefined),
  mPeekCouponCode: (code) =>
    http.get<CouponRedeemView>(buildPath(E.mPeekCouponCode.path, { code })),
  mRedeemCoupon: (code) => http.post<CouponRedeemResult>(E.mRedeemCoupon.path, { code }),

  mActivities: (includeEnded) =>
    http.get<StoreActivity[]>(E.mActivities.path, includeEnded ? { includeEnded: true } : undefined),
  mActivity: (activityNo) => http.get<StoreActivity>(buildPath(E.mActivity.path, { activityNo })),
  mSaveActivity: (payload) => http.post<StoreActivity>(E.mSaveActivity.path, payload),
  mSetActivityStatus: (activityNo, status) =>
    http.put<StoreActivity>(buildPath(E.mSetActivityStatus.path, { activityNo }), { status }),
  mActivityConflicts: (goodsNos) =>
    http.post<ActivityConflict[]>(E.mActivityConflicts.path, { goodsNos }),
  mPlanReach: (payload) => http.post<ReachPlan>(E.mPlanReach.path, payload),
  mSendReach: (payload) => http.post<ReachResult>(E.mSendReach.path, payload),

  mIncomeSummary: (allStores) =>
    http.get<IncomeSummary>(E.mIncomeSummary.path, allStores ? { allStores: true } : undefined),
  mSettleList: (allStores) =>
    http.get<SettleBill[]>(E.mSettleList.path, allStores ? { allStores: true } : undefined),
  mRateCard: () => http.get<RateCard>(E.mRateCard.path),
  mSettleBatches: () => http.get<MySettleBatch[]>(E.mSettleBatches.path),
  mMyDebt: () => http.get<MyDebt>(E.mMyDebt.path),
  mReportShortage: (subOrderNo, payload) =>
    http.post<PickupOrder>(buildPath(E.mReportShortage.path, { orderNo: subOrderNo }), {
      ...payload,
    } satisfies ReportShortageReq),

  // ---- 积分
  mPointsAccount: () => http.get<MerchantPointAccount>(E.mPointsAccount.path),
  mPointsRecords: (q) =>
    http.get<MerchantPointsRecord[]>(E.mPointsRecords.path, { ...q } satisfies PointsRecordQuery),
  mPointsToggle: (req) =>
    http.post<MerchantPointAccount>(E.mPointsToggle.path, { ...req } satisfies TogglePointsReq),

  // ---- 消息
  mMessageList: () => http.get<Message[]>(E.mMessageList.path),
  mMessageUnread: () => http.get<number>(E.mMessageUnread.path),
  mMessageRead: (messageNo) => http.post<Message[]>(buildPath(E.mMessageRead.path, { messageNo })),
  mMessageReadAll: () => http.post<Message[]>(E.mMessageReadAll.path),

  // ---- 推送设备
  mRegisterPushToken: (platform, provider, clientId) =>
    http.post<void>(E.mRegisterPushToken.path, { platform, provider, clientId }),
  mUnregisterPushToken: (clientId) =>
    http.post<void>(E.mUnregisterPushToken.path, { clientId }),

  // ---- 进销存（P-18）
  mStockSummary: () => http.get<StockSummary>(E.mStockSummary.path),
  mStockBalances: (q) => http.get<StockBalance[]>(E.mStockBalances.path, q),
  mStockCrossStore: (q) => http.get<StockCrossStoreRow[]>(E.mStockCrossStore.path, q),
  mStockPickable: (q) => http.get<StockBalance[]>(E.mStockPickable.path, q),
  mSuppliers: (q) => http.get<Supplier[]>(E.mSuppliers.path, q),
  mSupplierCreate: (body) => http.post<{ supplierNo: string }>(E.mSupplierCreate.path, body),
  mSupplierUpdate: (no, body) => http.put<void>(E.mSupplierUpdate.path.replace(":no", no), body),
  mSupplierActive: (no, body) => http.post<void>(E.mSupplierActive.path.replace(":no", no), body),
  mStockItem: (itemId) => http.get<StockItemDetail>(buildPath(E.mStockItem.path, { itemId })),
  mStockLedger: (q) => http.get<StockLedgerPage>(E.mStockLedger.path, q),
  mStockAdjust: (req) => http.post<void>(E.mStockAdjust.path, req),
  mSafetyStock: (itemId, body) => http.put<void>(E.mSafetyStock.path, { itemId, ...body }),
  mItemBySku: (skuNo) => http.get<StockItemDetail | null>(E.mItemBySku.path, { skuNo }),
  mItemByBarcode: (code) => http.get<StockBalance | null>(E.mItemByBarcode.path, { code }),
  mBindBarcode: (body) => http.post<void>(E.mBindBarcode.path, body),

  /*
   * 五个「新建」口返回的是 **`{no}` 而不是裸字符串**。
   *
   * `ApiResponseWrapper` 把 `String` 返回**故意排除**在 `{code,msg,data}` 信封之外
   *（StringHttpMessageConverter 的经典坑），于是裸串到了这里，
   * 而 http 客户端读 `body.code` —— 直接抛「响应格式不符合契约」。
   * 症状很坏：**服务端把单建好了，端上报错**，商家再点一次就是两张草稿单。
   *
   * 契约仍是 `Promise<string>`：页面要的是单号，不该知道 wire 上多包了一层。
   */
  mInboundCreate: (req) => http.post<{ no: string }>(E.mInboundCreate.path, req).then((r) => r.no),
  mInboundUpdate: (no, req) => http.put<void>(buildPath(E.mInboundUpdate.path, { no }), req),
  mInboundPost: (no) => http.post<void>(buildPath(E.mInboundPost.path, { no })),
  mInboundVoid: (no) => http.post<void>(buildPath(E.mInboundVoid.path, { no })),

  mOutboundCreate: (req) => http.post<{ no: string }>(E.mOutboundCreate.path, req).then((r) => r.no),
  mOutboundPost: (no) => http.post<void>(buildPath(E.mOutboundPost.path, { no })),
  mOutboundVoid: (no) => http.post<void>(buildPath(E.mOutboundVoid.path, { no })),

  // **裸数组直接当 body 发** —— 后端收的是 `List<Filled>`，包一层 `{lines}` 会解成空列表，
  // 而空列表在盘点里是「一件都没盘」，不报错
  mCountOpen: (itemIds) =>
    http.post<{ no: string }>(E.mCountOpen.path, { itemIds }).then((r) => r.no),
  mCountDetail: (no) => http.get<StockCount>(buildPath(E.mCountDetail.path, { no })),
  mCountFill: (no, lines) => http.put<void>(buildPath(E.mCountFill.path, { no }), lines),
  mCountPost: (no) => http.post<void>(buildPath(E.mCountPost.path, { no })),

  mTransferCreate: (req) =>
    http.post<{ no: string }>(E.mTransferCreate.path, req).then((r) => r.no),
  mTransferDetail: (no) => http.get<StockTransfer>(buildPath(E.mTransferDetail.path, { no })),
  mTransferShip: (no, body) => http.post<void>(buildPath(E.mTransferShip.path, { no }), body),
  mCarriers: () => http.get<Carrier[]>(E.mCarriers.path),
  mTransferReceive: (no) => http.post<void>(buildPath(E.mTransferReceive.path, { no })),
  mTransferVoid: (no) => http.post<void>(buildPath(E.mTransferVoid.path, { no })),

  mStockDocuments: (q) => http.get<StockDocument[]>(E.mStockDocuments.path, q),
  mStockMonthly: (month) => http.get<StockMonthly>(E.mStockMonthly.path, { month }),
  mStockRanking: (q) => http.get<StockRank[]>(E.mStockRanking.path, q),

  mStockLocations: () => http.get<StockLocation[]>(E.mStockLocations.path),
  mWarehouseCreate: (name) =>
    http.post<{ no: string }>(E.mWarehouseCreate.path, { name }).then((r) => r.no),
  mLocationSetSource: (id, sourceLocationId) =>
    http.put<void>(buildPath(E.mLocationSetSource.path, { id }), { sourceLocationId }),
};
