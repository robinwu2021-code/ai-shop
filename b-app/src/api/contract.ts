// B 端唯一契约。页面只依赖这个接口，不感知 mock / 真实后端。
// 端点对齐后端 B 端 BFF `/mb/**`（C 端是 `/mp/**`）。
//
// 类型全部来自 @shared/types —— **不在这里重复定义**。同一笔订单两端看到的是同一个
// Order 结构，只是可见字段与可执行动作不同；各定义一份必然漂移。
import type {
  AfterSale,
  Category,
  Community,
  DeliveryRule,
  Goods,
  CurrencyCode,
  GoodsStatus,
  I18nText,
  MasterData,
  MerchantStaff,
  Store,
  PaymentApplyment,
  MerchantApplyReq,
  MerchantApplyStatus,
  MerchantPointAccount,
  MerchantPointsRecord,
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
  /**
   * 类目单号（三级树的任意一级，通常是叶子）。选填。
   *
   * ⚠️ 与上面的 `type` 是两个维度：`type` 决定履约与合规、平台硬编码；
   * 类目决定归类与经营准入、运营可维护。
   *
   * <p>**保存草稿时不校验资质，上架时才校验** —— 商家可能正准备去申请那张证，
   * 保存这一步就拦住他，等于逼他先把商品归到一个错误的类目下。
   */
  categoryNo?: string;
  /**
   * 封面图 URL（来自 `mUploadImage`）。
   *
   * <p>此前<b>页面上传了封面却没放进提交体</b> —— 店主选了图、页面上也显示出来了，
   * 保存后 C 端拿到的却是空封面。而空封面不报错，只是列表里一块留白。
   */
  cover?: string;
  /** 详情轮播图 */
  images?: string[];
  /** 空数组 = 单规格。非空则 skus 必须是各组选项的笛卡尔积 */
  specGroups: SpecGroupDraft[];
  /** SKU 列表。单规格商品也有且仅有一条 */
  skus: SkuDraft[];
}

import type { PointsRecordQuery, StaffLoginReq, StoreEditReq, SubmitPaymentReq, TogglePointsReq } from "./requests";

export interface MerchantApi {
  // ---- 账号与入驻（B-11.1）
  mLogin(req: LoginReq): Promise<MerchantLoginResp>;
  mProfile(): Promise<MerchantProfile>;
  /** 上次提交的申请，驳回后回填用 */
  /**
   * 员工登录：手机号 + 验证码，**不建 C 端账号**。
   *
   * 与 {@link mLogin} 是两条路：那条走消费者账号（老板自己），
   * 这条走商家账号（`mch_account`）。店员多半**没有也不需要**消费者账号 ——
   * 要求他先注册成消费者才能上班，是把雇佣关系硬塞进一个消费关系里。
   *
   * 不是本主体在职员工时后端返回 403，**不是「账号不存在」** ——
   * 后者会把「谁是这家店的员工」变成一条可枚举的信息。
   */
  /**
   * 发送短信验证码。
   *
   * **它此前不在端点表里** —— B 端登录页的「发送验证码」按钮只是把 1234 填进输入框：
   * mock 下直接把 1234 填进输入框，于是这条缺失一直被盖住，
   * 而真实环境里没有人收得到验证码，登录整条路走不通。
   *
   * 返回体不含验证码（后端刻意如此）—— 这条别为了联调方便破例。
   */
  mSendOtp(phone: string): Promise<void>;

  mStaffLogin(payload: StaffLoginReq): Promise<MerchantLoginResp>;

  mApply(payload: MerchantApplyReq): Promise<MerchantProfile>;
  /**
   * 上次申请。返回的是**申请单**而不是请求体 —— 回填要用的是「上次填了什么」，
   * 而「审到哪一步、为什么被驳回」和它是同一份数据，拆成两条接口只会让两边不同步。
   */
  mApplyDraft(): Promise<MerchantApplyStatus | null>;
  /**
   * 平台主数据：行业 / 主体类型 / 支付通道。
   *
   * 入驻表单需要它才能回答「这个行业能不能选小微」—— 此前主体列表是页面里的常量，
   * 微信放开某个行业的小微白名单要发版才能生效，而选错主体的后果是进件被拒。
   */
  mMasterData(): Promise<MasterData>;

  // ---- 收款进件（ADR-002）—— **与入驻审核是两条独立链路**
  /** 每通道一条。微信过了、支付宝还没过是正常状态，合并成一个会让人以为都没好 */
  mPayments(): Promise<PaymentApplyment[]>;
  /** 结算账号明文只在这一次请求里存在：库里只留掩码，回显也只有掩码 */
  mSubmitPayment(payload: SubmitPaymentReq): Promise<PaymentApplyment>;
  /** 主动回查。留这个入口是因为**回调会丢**，丢了商家就永远停在「审核中」 */
  mRefreshPayment(payChannel: string): Promise<PaymentApplyment>;

  // ---- 店铺与获客（B-11.2）—— **一期主获客路径的商家侧**（ADR-004）
  mStore(): Promise<StoreProfile>;
  /** 设经营范围时可勾选的社区。真实环境应只返回该商家已签约自提点所在的社区 */
  mCommunities(): Promise<Community[]>;
  mSaveStore(payload: StoreProfile): Promise<StoreProfile>;

  // ---- 门店管理（M6）—— 与 mStore 的分工：那个管**一家店的门面**，这个管**有几家店**
  /** 含停用的。停用的也要看得见 —— 看不见的话商家会以为店被删了 */
  mStoreList(): Promise<Store[]>;
  /** 新建。**超额直接拒** —— 建出来却打不开的店比拒绝更难解释 */
  mCreateStore(payload: StoreEditReq): Promise<Store>;
  mRenameStore(storeNo: string, payload: StoreEditReq): Promise<Store>;
  /** 停用/启用。**默认店不能停用** —— 停掉之后「这个主体的店在哪」就没答案了 */
  mSetStoreStatus(storeNo: string, active: boolean): Promise<Store>;
  /** 转移默认标。显式动作 —— 勾选式会出现两家默认或零家默认的中间态 */
  mSetDefaultStore(storeNo: string): Promise<Store>;
  /** 换收款号。只能挑本主体已开通的；传空 = 回到主体默认号（合法操作） */
  mSetStorePayment(storeNo: string, payMerchantNo?: string): Promise<Store>;

  // ---- 员工与授权（B-11.10）
  /** 含停用的。手机号已脱敏 */
  mStaffList(): Promise<MerchantStaff[]>;
  /** 加员工。**不发密码、不建 C 端账号** —— 他用自己的手机号验证码登录 */
  mAddStaff(loginPhone: string): Promise<MerchantStaff>;
  /** 停用/启用。**老板不能被停用** */
  mSetStaffStatus(mchAccountNo: string, active: boolean): Promise<MerchantStaff>;
  /** 授权到店。role 传空 = 收回这家店的授权 */
  mGrantStore(mchAccountNo: string, storeNo: string, role?: "MANAGER" | "CLERK"): Promise<MerchantStaff>;
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

  // ---- 类目（B-11.3.1）
  /**
   * 三级类目树 —— 编辑商品时选类目用。
   *
   * ⚠️ 与 `Goods["type"]`（五品类）**不是一回事**：type 决定履约与合规，平台硬编码；
   * 类目决定归类与经营准入，运营可维护。挂了资质门槛的类目，
   * 没拿到授权的商家**上架时**会被拒（保存草稿不拦）。
   */
  mCategoryTree(): Promise<Category[]>;

  // ---- 规格模板（B-11.3.2）
  /** 按类目取可用模板：平台预置 + 本商家存的常用 */
  mSpecTemplates(categoryType?: Goods["type"]): Promise<SpecTemplate[]>;
  /** 把当前编辑的规格组存为「我的常用」，下次建品直接套 */
  mSaveSpecTemplate(payload: { name: string; options: string[] }): Promise<SpecTemplate>;

  // ---- 订单与配送（B-11.4）
  /** `status` 用 `OrderStatus` 而不是 `string` —— 松成 string 后，前端传个 "toShip"
   *  这种 tab key 上去也编译得过，而服务端只认状态枚举（由 requests.ts 的 satisfies 抓出） */
  mOrderList(q: PageQuery & { status?: OrderStatus; allStores?: boolean }): Promise<PageResult<Order>>;
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
  /**
   * 待处理售后。**返回售后单，不是订单** —— 后端 /biz/after-sale 给的是
   * List<AfterSaleVO>，端上此前把它类型成 Order[]，形状根本对不上。
   */
  mAfterSaleList(): Promise<AfterSale[]>;
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

  // ---- 积分（商家侧只有成本与开关，看不到抵扣与补差）
  mPointsAccount(): Promise<MerchantPointAccount>;
  /** 发分服务费明细：一单一条，数据来自 stl_bill.points_fee_minor */
  mPointsRecords(q?: PointsRecordQuery): Promise<MerchantPointsRecord[]>;
  /**
   * 开/关本店积分。
   *
   * **关闭只影响将来** —— 已发出的分仍有效、已扣的服务费不退，
   * 否则关一次开关就是一次资金事故。
   */
  mPointsToggle(req: TogglePointsReq): Promise<MerchantPointAccount>;
}
