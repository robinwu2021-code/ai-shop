// B 端唯一契约。页面只依赖这个接口，不感知 mock / 真实后端。
// 端点对齐后端 B 端 BFF `/mb/**`（C 端是 `/mp/**`）。
//
// 类型全部来自 @shared/types —— **不在这里重复定义**。同一笔订单两端看到的是同一个
// Order 结构，只是可见字段与可执行动作不同；各定义一份必然漂移。
import type {
  Community,
  DeliveryRule,
  Goods,
  CurrencyCode,
  GoodsStatus,
  I18nText,
  MerchantApplyReq,
  MerchantLoginResp,
  MerchantProfile,
  MerchantCustomer,
  MerchantStats,
  MerchantTodo,
  Order,
  PageQuery,
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
  OrderStatus,
  VerifyBatchResult,
  PickupOverview,
  RateCard,
} from "@shared/types";

/** 拍照识别的结果。全部是**建议值**，店主可改可弃 */
export interface GoodsGuess {
  title: string;
  type: Goods["type"];
  /** 识别置信度 0–1。低于阈值时页面不预填，只提示「没认出来」 */
  confidence: number;
}

/** 规格组草稿：一个维度（如「重量」）与它的取值（「5 斤」「10 斤」） */
export interface SpecGroupDraft {
  /** 规格维度名，如「重量」「香型」 */
  name: string;
  /** 该维度的可选值 */
  options: string[];
  /**
   * 与 options 一一对应的模板编码。来自平台模板的有值，手输/改过的为空。
   * **一期只存不用** —— 但不存的话，二期做规格聚合要刷全部历史商品。
   */
  optionCodes?: (string | undefined)[];
  /** 该规格组来自哪个平台模板。手输的为空 */
  templateNo?: string;
}

/**
 * SKU 草稿。`optionValues` 的顺序与 `specGroups` 一一对应 ——
 * 这是矩阵的坐标，错位就会出现「5 斤卖成 10 斤的价」。
 */
export interface SkuDraft {
  /** 已有 SKU 带上原编号，改价改库存不会丢历史订单的引用 */
  skuNo?: string;
  /** 各规格维度上的取值，顺序与 specGroups 一一对应 */
  optionValues: string[];
  /** 当前市场的价（最小货币单位）。兼容单市场调用 */
  price: number;
  /**
   * **按市场分别定价**（B6）。未填的市场不在该市场售卖 ——
   * 汇率换算出的价没有价格心理学（¥29.9 → $4.19 不是任何人会标的价），
   * 且汇率一动全店价格跟着抖，而商家并没有调价。
   */
  priceByMarket?: Partial<Record<CurrencyCode, number>>;
  /** 可售库存 */
  stock: number;
}

/**
 * 商家侧商品草稿。单规格 = 一个规格组一个选项，与多规格同一套结构。
 *
 * 标题与副标题是**三语**：三语是一期范围（C端清单 §五之二），
 * 但此前商品文案只有一份，中文抄进三语 —— 切到英文看到的还是中文。
 * **只有中文必填**，其余留空时由服务端回落中文并标注未翻译（不做机翻，见 §M8-2）。
 */
export interface GoodsDraft {
  /** 商品单号。新建时不传，编辑时必传 */
  goodsNo?: string;
  /** 商品标题（多语言）。后端按 Accept-Language 下发对应语言给 C 端 */
  title: I18nText;
  /** 副标题/卖点（多语言） */
  subtitle: I18nText;
  /** 商品形态，决定详情页用哪套字段。**保存后不建议再改** */
  type: Goods["type"];
  /** 空数组 = 单规格。非空则 skus 必须是各组选项的笛卡尔积 */
  specGroups: SpecGroupDraft[];
  /** SKU 列表。单规格商品也有且仅有一条 */
  skus: SkuDraft[];
}

export interface MerchantApi {
  // ---- 账号与入驻（B-11.1）
  mLogin(req: LoginReq): Promise<MerchantLoginResp>;
  mProfile(): Promise<MerchantProfile>;
  mApply(payload: MerchantApplyReq): Promise<MerchantProfile>;
  /** 上次提交的申请，驳回后回填用 */
  mApplyDraft(): Promise<MerchantApplyReq | null>;

  // ---- 店铺与获客（B-11.2）—— **一期主获客路径的商家侧**（ADR-004）
  mStore(): Promise<StoreProfile>;
  /** 设经营范围时可勾选的社区。真实环境应只返回该商家已签约自提点所在的社区 */
  mCommunities(): Promise<Community[]>;
  mSaveStore(payload: StoreProfile): Promise<StoreProfile>;
  mStoreQrcode(): Promise<StoreQrcode>;
  /** 分享素材：整店或单品。文案要带「还差 N 人」这类可直接转发的内容 */
  mShareKit(goodsNo?: string): Promise<ShareKit>;

  // ---- 工作台（B-10.1 + B-11 汇总）
  mTodo(): Promise<MerchantTodo>;
  mStats(): Promise<MerchantStats>;

  // ---- 商品（B-11.3）
  mGoodsList(q: PageQuery & { status?: GoodsStatus }): Promise<PageResult<Goods>>;
  mGoodsDetail(goodsNo: string): Promise<Goods>;
  mSaveGoods(payload: GoodsDraft): Promise<Goods>;
  mToggleGoods(goodsNo: string, onSale: boolean): Promise<Goods>;
  mSaveStock(goodsNo: string, skuNo: string, stock: number): Promise<Goods>;

  // ---- 商品图片与拍照建品（B-11.3.7 / E9）
  /** 上传一张图，返回可访问 URL。小程序侧走 uploadFile，域名需在白名单 */
  mUploadImage(tempPath: string): Promise<{ url: string }>;
  /**
   * 拍照识别：**只用来猜一个标题**，猜错不影响，店主可改。
   * ⚠️ 绝不做「一拍就自动上架」—— 识别错了价格也错，货会以错价卖出去。
   */
  mRecognizeGoods(imageUrl: string): Promise<GoodsGuess>;

  // ---- 规格模板（B-11.3.2）
  /** 按类目取可用模板：平台预置 + 本商家存的常用 */
  mSpecTemplates(categoryType?: Goods["type"]): Promise<SpecTemplate[]>;
  /** 把当前编辑的规格组存为「我的常用」，下次建品直接套 */
  mSaveSpecTemplate(payload: { name: string; options: string[] }): Promise<SpecTemplate>;

  // ---- 订单与配送（B-11.4）
  /** `status` 用 `OrderStatus` 而不是 `string` —— 松成 string 后，前端传个 "toShip"
   *  这种 tab key 上去也编译得过，而服务端只认状态枚举（由 requests.ts 的 satisfies 抓出） */
  mOrderList(q: PageQuery & { status?: OrderStatus }): Promise<PageResult<Order>>;
  mOrderDetail(orderNo: string): Promise<Order>;
  /** 快递发货：回填运单号 */
  mShip(orderNo: string, expressNo: string): Promise<Order>;
  /** 商家自送：老板点一下「已送达」。不做骑手轨迹（ADR-005 §5） */
  mDelivered(orderNo: string): Promise<Order>;
  mDeliveryRule(): Promise<DeliveryRule>;
  mSaveDeliveryRule(rule: DeliveryRule): Promise<DeliveryRule>;

  // ---- 自提点履约（B-10）
  /** 本自提点的订单 —— 含别家商家的货，字段已按履约必需裁剪（B12） */
  /** 自提点履约总览（后端已实现）。承接方进履约台第一眼要看的三个数 */
  mPickupOverview(): Promise<PickupOverview>;
  mPickupOrders(): Promise<Order[]>;
  mPickingList(): Promise<PickingRow[]>;
  mMarkArrived(orderNos: string[]): Promise<Order[]>;
  /** 核销自提码。核销成功 → C 端该订单立刻变已完成 */
  mVerify(code: string): Promise<Order>;
  /**
   * 批量核销（B-6.4 扩展）。高峰期一个个扫码是自提点的真实痛点。
   * **逐条尝试、失败逐条回报**，不整批回滚 —— 一张废码不该让另外四单白扫。
   */
  mVerifyBatch(codes: string[]): Promise<VerifyBatchResult>;

  // ---- 售后（B-11.5）
  mAfterSaleList(): Promise<Order[]>;
  /** 同意：仅退款直接退；退货退款要等收货后才退（见 mConfirmReturn） */
  mApproveAfterSale(afterSaleNo: string, reply: string): Promise<Order>;
  /** 驳回**必须给理由** —— 用户据此决定要不要上升平台，没理由就是把路堵死 */
  mRejectAfterSale(afterSaleNo: string, reply: string): Promise<Order>;
  /**
   * 确认收到退货 → 随即退款（B-7.3）。
   * **退款必须在这一步之后** —— 同意即退的话，货没回来钱先出去了。
   */
  mConfirmReturn(afterSaleNo: string): Promise<Order>;

  // ---- 团购与报价（B-11.6）
  mGroupList(): Promise<GroupBuy[]>;
  /** 商家在已上架商品上开团。商品未配 groupBuy 则不能开 */
  mCreateGroup(goodsNo: string): Promise<GroupBuy>;
  /** 可报价的邻里需求单 */
  mRequestList(): Promise<GroupRequest[]>;
  /** 报价。改价留痕并公示涨价（ADR-003）；已锁价的不可改 */
  mQuote(
    requestNo: string,
    payload: { priceMinor: number; minCount: number; desc: string },
  ): Promise<GroupRequest>;

  // ---- 评价（B-11.7）
  mReviewList(): Promise<Review[]>;
  mReplyReview(reviewNo: string, reply: string): Promise<Review>;
  /**
   * 申诉差评（B-9.4）。**只有低分评价可申诉** —— 四星五星去申诉没有意义，
   * 开放了只会变成「凡是不满意的评价都申诉一遍」，把平台裁决台淹掉。
   * 裁决在平台端（P-13.1），这里只负责把单送进去。
   */
  mAppealReview(reviewNo: string, reason: string, images?: string[]): Promise<Review>;

  // ---- 营销（B-11.8）
  mCampaignList(): Promise<MarketingCampaign[]>;
  mSaveCampaign(payload: CampaignDraft): Promise<MarketingCampaign>;
  /** 启停。**只允许 RUNNING ↔ PAUSED** —— 已结束的活动不可复活，否则时段与预算全乱 */
  mToggleCampaign(campaignNo: string, running: boolean): Promise<MarketingCampaign>;

  // ---- 客户与复购（B-11.2.8）
  mCustomers(): Promise<MerchantCustomer[]>;

  // ---- 结算（B-11.9）
  mSettleList(): Promise<SettleBill[]>;
  /** 费率卡（后端已实现）。把费率讲清楚是「自带客流零佣金」能起作用的前提 */
  mRateCard(): Promise<RateCard>;

  // ---- 到货异常上报（B-10.4.2）
  /** 破损 / 短少上报。下游是售后责任判定（平台 / 供货商家 / 自提点商家，M4 待定） */
  mReportShortage(
    orderNo: string,
    payload: { skuNo: string; kind: "SHORTAGE" | "DAMAGE"; note: string },
  ): Promise<Order>;
}
