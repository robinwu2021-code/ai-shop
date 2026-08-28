// B 端唯一契约。页面只依赖这个接口，不感知 mock / 真实后端。
// 端点对齐后端 B 端 BFF `/mb/**`（C 端是 `/mp/**`）。
//
// 类型全部来自 @shared/types —— **不在这里重复定义**。同一笔订单两端看到的是同一个
// Order 结构，只是可见字段与可执行动作不同；各定义一份必然漂移。
import type {
  IncomeSummary,
  AppointmentSlot,
  Entity,
  EntityStores,
  GeoReverseResult,
  GeoTip,
  PickupCandidate,
  RegionSearchResult,
  BizScope,
  Region,
  StaffRole,
  AfterSale,
  ArrivalIssueKind,
  Category,
  Community,
  CommunityApply,
  CommunityApplyReq,
  CrossStoreCompare,
  CrossStoreOverview,
  MerchantPlan,
  DeliveryRule,
  Goods,
  CurrencyCode,
  MarketId,
  GoodsStatus,
  I18nText,
  MasterData,
  Message,
  MerchantStaff,
  StaffLog,
  MerchantRole,
  PickupOrder,
  PermOption,
  Quote,
  VerifyResult,
  Store,
  StoreCategory,
  PaymentApplyment,
  MerchantApplyReq,
  MerchantApplyStatus,
  MerchantPointAccount,
  MerchantPointsRecord,
  MerchantLoginResp,
  MerchantProfile,
  MerchantCustomer,
  ActivityConflict,
  ReachPlan,
  ReachResult,
  CouponIssueBatch,
  CouponRedeemResult,
  CouponRedeemView,
  MerchantCoupon,
  MerchantCouponDraft,
  StoreActivity,
  StoreActivityDraft,
  Member,
  MemberDetail,
  MemberSegment,
  MemberSegmentPreview,
  MemberSegmentRule,
  MemberSetting,
  MemberMergePreview,
  MemberStats,
  MemberTag,
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
  Poster,
  Review,
  SettleBill,
  ShareKit,
  SkuIdentityReport,
  SpecTemplate,
  GoodsParam,
  SpuStd,
  StoreProfile,
  StoreQrcode,
  LoginReq,
  OrderStatus,
  VerifyBatchResult,
  PickupOverview,
  RateCard,
  StoreFulfillment,
  MyQualifications,
  Qualification,
  QualificationSaveReq, MerchantSpecDim, StoreCategorySpecs, SpecOverride, SpecOption,
  // 进销存（P-18）
  StockSummary, StockBalance, StockItemDetail, StockLedgerPage, StockDocument,
  StockMonthly, StockRank, StockLocation, StockLineReq, StockCountFilled,
  StockCount, StockTransfer,
  FulfillmentImpactItem,
  SpecValueAdded,
} from "@shared/types";

/** 拍照识别的结果。全部是**建议值**，店主可改可弃 */
export interface GoodsGuess {
  title: string;
  /** 一句话卖点。模型从包装上的广告语里提取，店主可改可弃 */
  subtitle: string;
  type: Goods["type"];
  /**
   * 类目编号。**后端已按候选表校验过** —— 模型给出的编号不在类目树里时是空串，
   * 不会把一个查无此项的编号塞进草稿（那样商家要到点保存那一刻才撞上类目校验）。
   */
  categoryNo: string;
  /**
   * 识别置信度 0–1。低于阈值时页面不预填，只提示「没认出来」。
   *
   * <p>⚠️ 它答的是「**我看这张图看得准不准**」，不是「这是不是一个能上架的商品」。
   * 拿一张纯色纹理图去识别，模型会 0.95 地确信那是「红色斜纹图案」—— 实测过。
   * 所以这道闸拦得住「看不清」，拦不住「看清了但那不是商品」。
   */
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
   *
   * <p>⚠️ 键是**市场码**（CN/AE/US），不是币种码。这两套码一一对应，所以写错了
   * 不报任何错 —— 但落到 `prd_sku.market` 上就成了一行 `market='CNY'` 的死数据：
   * C 端按市场取价永远取不到它。此前端上按 `currency` 发，于是每个新 SKU 多一行脏数据，
   * 且商家在 AED/USD 页签填的价**在那两个市场一分钱也卖不出去**。
   */
  priceByMarket?: Partial<Record<MarketId, number>>;
  /** 可售库存 */
  stock: number;
  /**
   * 划线价（最小货币单位）。**留空 = 不改**，传 0 = 清掉。
   *
   * <p>它是派生展示值（标折扣用），不是定价 —— 必须**高于售价**，
   * 否则渲染出来是个「涨价了」的折扣标，后端会拒。
   *
   * <p>此前有列、有契约、**没有写入路径**：折扣标因此永远不出现。
   */
  originPrice?: number;
  /**
   * 标称重量（克），生鲜按重计价用。**留空 = 不改**，传 0 = 清掉。
   * 「按标称预扣、称重后多退少补」这条链靠它，没有它整条链跑不起来。
   */
  nominalGram?: number;
  /**
   * 成本价（最小货币单位）。**留空 = 不改**，传 0 = 清掉。
   *
   * <p>只用来在编辑页实时算毛利，不参与任何对外计价，也**不下发买家端**。
   * 不校验与售价的大小关系 —— 引流款本来就可能亏本卖。
   */
  costPrice?: number;
  /**
   * 商品条码 EAN-13 / UPC（V252）。**只在商家侧下发** —— 它是商家与供应商/ERP
   * 之间的键，对买家没有用处，而条码还能反查到进货渠道。
   */
  barcode?: string;
  /** 商家自有货号。他 ERP 里的主键，同样只在商家侧下发 */
  merchantSkuCode?: string;
  /**
   * 计量单位（件 / 斤 / kg / 份）。**买家侧也要** ——
   * 「5」到底是 5 件还是 5 斤，买家同样需要知道才判断得了贵不贵。
   */
  saleUnit?: string;
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
  /**
   * 图文详情正文（纯文本，非多语言）。**不传 = 不改**，传空串 = 清空。
   *
   * <p>不做多语言：详情是商家自己一段一段敲的长文，逼他填三遍的结果是两遍空着，
   * 而空着的那两个语种看到的是一整块空白 —— 不如统一回落。
   */
  detail?: string;
  /**
   * 类目单号（三级树的任意一级，通常是叶子）。**必填，且是唯一的分类输入。**
   *
   * <p>商品形态（`Goods["type"]`：NORMAL/FRESH/SERVICE/VIRTUAL/CARD）**由它派生** ——
   * 类目节点上带着 `template`，两者是同一件事的两套码。草稿里因此**没有 type 字段**：
   * 后端拿到也会忽略，自己按 categoryNo 查一遍。
   *
   * <p>此前两者是两个并列的输入，商家把同一件事填两遍，而且填得出
   * 「叶菜类目 + 日用品形态」这种组合 —— 页面只提示不阻断，代价要到下单
   * 那一刻才显形（生鲜要截单、服务不发货）。
   *
   * <p>**保存草稿时不校验资质，上架时才校验** —— 商家可能正准备去申请那张证，
   * 保存这一步就拦住他，等于逼他先把商品归到一个错误的类目下。
   */
  categoryNo: string;
  /**
   * 引用的平台标准品；**不传 = 自建品，也 = 脱离标准品**。
   *
   * <p>传了它，服务端会用标准品的 `categoryNo` 与 `optionCode` **覆盖**请求里的值 ——
   * 端上「从标准品开始」只是把字段填进表单，而填充过的表单商家能随便改。
   * 标题与图改了没关系；类目与 code 不行：前者决定形态，后者是跨店可比的唯一依据。
   *
   * <p>与其余字段的「不传 = 不改」**相反**：商家点「脱离标准品」之后端上就是不再带它，
   * 而「不改」会让他脱不掉 —— 商品继续被收敛，界面上却已经不显示来源了。
   */
  stdNo?: string;
  /**
   * 封面图 URL（来自 `mUploadImage`）。
   *
   * <p>此前<b>页面上传了封面却没放进提交体</b> —— 店主选了图、页面上也显示出来了，
   * 保存后 C 端拿到的却是空封面。而空封面不报错，只是列表里一块留白。
   */
  cover?: string;
  /** 详情轮播图 */
  images?: string[];
  /**
   * 详情区长图，按顺序全宽竖排。**不传 = 不改**，传空数组 = 清空。
   *
   * <p>与 `images` 分开：那是详情页顶部可左右滑的方图，这些是正文下方一张接一张的长图。
   */
  detailImages?: string[];
  /**
   * **商品参数**（产地 / 保质期 / 材质…）。**不传 = 不改**，传空数组 = 清空。
   *
   * <p>与 `specGroups` 的差别是性质不是范围：那些进笛卡尔积生成 SKU，
   * 这些一项也不进 —— 买家不用挑，只是看。
   */
  params?: GoodsParam[];
  /** 空数组 = 单规格。非空则 skus 必须是各组选项的笛卡尔积 */
  specGroups: SpecGroupDraft[];
  /** SKU 列表。单规格商品也有且仅有一条 */
  skus: SkuDraft[];
  /**
   * 该商品支持的履约方式：`STORE_PICKUP` / `NEIGHBOR_PICKUP` /
   * `MERCHANT_DELIVERY` / `EXPRESS`。
   *
   * <p><b>留空 = 不改</b>（新建时后端默认四种全支持，由商家收窄）。
   * 传空数组是另一件事 —— 一种履约都不支持的商品谁也买不了，后端会拒。
   *
   * <p>下单时会校验「用户选的方式该商品必须支持」，所以收窄之后
   * C 端就选不到被去掉的那几种了。
   */
  fulfillments?: string[];
  /** 每人限购，0 = 不限。**留空 = 不改** */
  limitPerUser?: number;
  /**
   * 生鲜段。**留空 = 不改**；只在商品形态是 `FRESH` 时生效（形态由类目派生）。
   *
   * <p>这几个字段此前只有种子数据写得进去 —— 商家建出来的生鲜没有截单时间、
   * 没有产地、不按实称，于是「按标称预扣、多退少补」在真实数据上跑不起来。
   */
  fresh?: {
    /** 当天几点前下单（毫秒时间戳）。与「到点」是两件事：截单管下单，到点管到货 */
    cutoffAt?: number;
    /** 预计到货描述，如「次日 17:00 前到点」 */
    arrivalDesc?: string;
    /** 是否按实称多退少补 */
    weighed?: boolean;
    /** 产地 */
    origin?: string;
  };
  /** 服务段。**留空 = 不改**；只在形态是 `SERVICE` 时生效 */
  service?: {
    /** 服务时长（分钟） */
    durationMin?: number;
    /** 可核销门店名 */
    storeName?: string;
  };
  /**
   * 拼团档。**留空 = 不改**；两个字段都传 `undefined` 的对象 = 显式关闭拼团。
   *
   * <p>两个值要么都给要么都不给 —— 缺一个后端拒。原因是「能不能开团」按两者都齐来判，
   * 只填一个的话商家在界面上填了团价却开不出团，而没有任何提示。
   *
   * <p>此前这两列没有任何写入路径，「可开团的商品」那一栏因此**恒为空**。
   */
  groupBuy?: {
    /** 起团人数，最小 2 —— 一个人不叫团 */
    minCount?: number;
    /** 团购价（最小货币单位） */
    price?: number;
  };
}

import type {
  DescribeGoodsReq,
  PickupSelfBuildReq,
  PointsRecordQuery,
  StaffLoginReq,
  StoreEditReq,
  StoreFulfillmentSaveReq,
  SubmitPaymentReq,
  TogglePointsReq,
  OpenFromMapReq,
  StockInboundReq, StockOutboundReq,
} from "./requests";

/** 缓存里的一条小区。坐标必有 —— 没坐标的在服务端就被滤掉了（买家定位落不进去） */
export interface EstateItem {
  name: string;
  address?: string;
  latE6?: number | null;
  lngE6?: number | null;
  poiId?: string | null;
}

export interface EstateList {
  scopeCode: string;
  items: EstateItem[];
  /** 这一片抓过没有。false = 从没抓过（不是「抓过但是空的」） */
  cached: boolean;
  /** 抓过但超过 TTL：先用着，同时再问一次地图 */
  stale: boolean;
}

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

  /**
   * 设置 / 修改登录密码。**要求已登录** —— 当前会话即授权，不收旧密码
   * （要旧密码会把「忘了密码」变成死路，而重设的正路本来就是「验证码登录进来再设」）。
   */
  mSetPassword(password: string): Promise<void>;

  /** 我设过密码没有 —— 决定「我的」页里显示「设置密码」还是「修改密码」 */
  mHasPassword(): Promise<{ hasPassword: boolean }>;

  mStaffLogin(payload: StaffLoginReq): Promise<MerchantLoginResp>;

  mApply(payload: MerchantApplyReq): Promise<MerchantProfile>;
  /**
   * 无证照快速开店：填个店名就把店开起来，不进审核队列。
   *
   * 与 {@link mApply} 是两条路：那条要交执照、等平台审；这条让老板先把准备工作
   * 做完（录商品、配范围、加员工），**补齐证照之前买家看不到、也下不了单**。
   * 之后补证照走的还是 `mApply`，服务端会认领这家店，店与货原样留着。
   */
  mQuickStart(payload: { storeName: string; address?: string }): Promise<MerchantProfile>;
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
  /** @param entityNo 看哪张证照的进件，不传 = 当前证照 */
  mPayments(entityNo?: string): Promise<PaymentApplyment[]>;
  /** 结算账号明文只在这一次请求里存在：库里只留掩码，回显也只有掩码 */
  mSubmitPayment(payload: SubmitPaymentReq): Promise<PaymentApplyment>;
  /** 主动回查。留这个入口是因为**回调会丢**，丢了商家就永远停在「审核中」 */
  mRefreshPayment(payChannel: string): Promise<PaymentApplyment>;

  // ---- 店铺与获客（B-11.2）—— **一期主获客路径的商家侧**（ADR-004）
  mStore(): Promise<StoreProfile>;
  /** 设经营范围时可勾选的社区。真实环境应只返回该商家已签约自提点所在的社区 */
  mCommunities(): Promise<Community[]>;
  /**
   * 区划的**直接下级**，逐级往下点。`parent` 空取省级。
   *
   * 一次只给一级而不是整棵树：全国到街道是 4.4 万行，一次拉下来端上要等好几秒，
   * 而商家真正要点开的只有其中一条路径。
   */
  mRegions(parent?: string): Promise<Region[]>;
  /**
   * 街道/镇下的官方村名**词典**（提报村时的名称联想）。
   *
   * <p>不是导航层级：62 万村级行退出了选择器，但它是全国村名的唯一权威清单 ——
   * 不用它的话提报村全靠手打，同一个村会被打出三种写法。
   * 选中词典项的提报带 originCode，运营裁决时据此查重（一村一聚落）。
   */
  mVillageDict(street: string, keyword?: string): Promise<Region[]>;
  /**
   * 提报一个平台还没有的小区（ADR-013 阶段三）。
   *
   * 在这之前商家**无路可走**：覆盖项只能从已有社区里勾，而「让平台加一个小区」
   * 没有入口 —— 只能找 BD 口头说，说完没人知道进展。
   */
  mApplyCommunity(payload: CommunityApplyReq): Promise<CommunityApply>;
  /**
   * 地图上选中的小区**直接开通并返回**，商家当场就能勾。
   * 重复由后端三道闸挡（村码 / 同街道归一名 / 坐标 150 米内），撞上返回既有那条。
   */
  mOpenCommunityFromMap(payload: OpenFromMapReq): Promise<Community>;
  /** 我提报过的。没有它，提报出去等于石沉大海，商家只会隔几天再提一次同样的 */
  mMyCommunityApplies(): Promise<CommunityApply[]>;
  mSaveStore(payload: StoreProfile): Promise<StoreProfile>;
  /**
   * 只改公告。与 mSaveStore 分开的一条路 —— 公告一天可能改两次，
   * 混在整份门面里保存，改一句话要连带提交地址与营业时间。
   */
  /**
   * 只改公告。
   *
   * @param payload.alsoStoreNos 同时发到这些门店（多店主体）。**默认不带** ——
   *                             「南门店今天停电」只对一家店成立；而「今天到货」
   *                             三家都成立时，让他进三次店发三遍是纯粹的重复劳动
   */
  mSaveAnnouncement(payload: {
    announcement: string; announcementUntil: number | null; alsoStoreNos?: string[];
  }): Promise<StoreProfile>;
  /** 从「常用」里删掉一条（按原文匹配）。删候选不动当前公告 */
  mDropNoticeRecent(text: string): Promise<StoreProfile>;

  // ---- 门店管理（M6）—— 与 mStore 的分工：那个管**一家店的门面**，这个管**有几家店**
  /** 含停用的。停用的也要看得见 —— 看不见的话商家会以为店被删了。**只给当前证照的店** */
  mStoreList(): Promise<Store[]>;

  // ---- 跨证照（多证照）。这三个**故意不吃当前证照的范围** ----
  /**
   * 我能进的所有门店，**按证照分组**（门店选择器）。
   *
   * <p>与 {@link mStoreList} 的分工：那个是「当前这张证照下的门店」，
   * 这个是「我名下所有证照的门店」。没有分开的话，切到另一张证照的店这件事
   * 根本无从发起 —— 列表里压根看不到它。
   *
   * <p>店员也能调，结果按授权裁剪：他只拿到 `mch_store_role` 授权到的那几家。
   */
  mMyStores(): Promise<EntityStores[]>;

  /** 我名下的证照（只有我是持有人的那些）。「证照与账户」列表页用 */
  mEntities(): Promise<Entity[]>;

  /** 一张证照的详情与它的门店。**不是我的 → 403**（它存在，只是不属于我） */
  mEntity(entityNo: string): Promise<EntityStores>;

  /**
   * 门店送货方式（方案 v4）：四路开关。storeNo 传 "default" = 默认门店 ——
   * 单店商家不用感知门店号。
   */
  mStoreFulfillment(storeNo: string): Promise<StoreFulfillment>;

  /** 全量保存。「关一路」是 enabled=false 不是删配置 */
  mSaveStoreFulfillment(storeNo: string, payload: StoreFulfillmentSaveReq): Promise<StoreFulfillment>;
  /** 关掉某一路会影响的在售商品：本店货架上只勾了这一路的（P1） */
  mFulfillmentImpact(storeNo: string, channel: string): Promise<FulfillmentImpactItem[]>;
  /** 门店可引用的取货点候选：范围内的常驻点 + 本店自建的点（P1） */
  mPickupCandidates(storeNo: string): Promise<PickupCandidate[]>;
  /** 自建自提点 → PENDING 待运营核实（P1） */
  mSelfBuildPickup(payload: PickupSelfBuildReq): Promise<PickupCandidate>;
  /** 跨级搜索区划与聚落（P1） */
  /**
   * 跨级搜区划与聚落。带上位置时**村级按距离排** —— 同名的村全国到处都是，
   * 深圳的商家搜「福城」，不带位置排在最前的是新疆的「同和幸福城」。
   */
  mRegionSearch(kw: string, near?: { latE6: number; lngE6: number }): Promise<RegionSearchResult>;
  /** 从省到自身的整条链路（选择器从搜索命中下钻用） */
  mRegionPath(code: string): Promise<Region[]>;
  /** 坐标转地址（P2）。未开通时抛 10503，端上据此藏按钮 */
  mGeoReverse(lat: number, lng: number): Promise<GeoReverseResult>;
  /** 地点输入提示。未开通（后端没配 Web 服务 key）返回空数组，端上退回自由输入 */
  mGeoTips(kw: string, city?: string): Promise<GeoTip[]>;
  /**
   * 一片地方（街道 / 村·社区）里的小区。**服务端读穿透**：缓存新鲜直接给；
   * 缺失或过期，且给得出圆心（坐标，或一条能被地理编码的地址），就服务端自己
   * 现问地图、写回缓存、再返回。App 不用再自己调原生 SDK、也不用把结果传回来写缓存。
   *
   * @param opts.latE6/lngE6 已知坐标时给，省一次服务端地理编码
   * @param opts.addressPath 没坐标时用它地理编码（如「浙江省 / 杭州市 / 西湖区 / 北山街道 / 宝石社区」）
   * @param opts.city 高德 city 偏好（可选）
   * @param opts.rural 这一片是村委会还是社区/居委会。城乡搜法不一样：城区搜「住宅小区」，
   *                   农村搜「村」——沿用城区那套在农村会一条都搜不到
   */
  mEstates(regionCode: string, opts?: {
    parentCode?: string; latE6?: number; lngE6?: number; addressPath?: string; city?: string; rural?: boolean;
  }): Promise<EstateList>;
  /** 下辖各片的小区条数：列表要在每一行上预告「12 个小区 / 暂无小区」 */
  mEstateCounts(parentCode: string): Promise<Record<string, number>>;
  /** 新建。**超额直接拒** —— 建出来却打不开的店比拒绝更难解释 */
  mCreateStore(payload: StoreEditReq): Promise<Store>;
  mRenameStore(storeNo: string, payload: StoreEditReq): Promise<Store>;
  /** 停用/启用。**默认店不能停用** —— 停掉之后「这个主体的店在哪」就没答案了 */
  mSetStoreStatus(storeNo: string, active: boolean): Promise<Store>;
  /** 转移默认标。显式动作 —— 勾选式会出现两家默认或零家默认的中间态 */
  mSetDefaultStore(storeNo: string): Promise<Store>;
  /** 换收款号。只能挑本主体已开通的；传空 = 回到主体默认号（合法操作） */
  mSetStorePayment(storeNo: string, payMerchantNo?: string): Promise<Store>;

  /** 本店货架。**空数组是合法状态**（新店还没建过货），不是加载失败 */
  mStoreCategories(storeNo: string): Promise<StoreCategory[]>;
  /**
   * 我的资质 + 已获授权的类目码 + 码字典。
   *
   * <p>**此前商家侧没有任何入口**：只有入驻申请那一步能传，传完也看不到。
   * 于是「上架被拒 → 去哪补证」这条路在 B 端是断的 —— 他看到「你还没有该授权」，
   * 然后没有下一步（线上资质表 0 条、授权码全 NULL，就是这么来的）。
   */
  /** @param entityNo 看哪张证照的证件，不传 = 当前证照（存量单证照账号永远不传） */
  mQualifications(entityNo?: string): Promise<MyQualifications>;
  /** 传一张证。**传完不自动授码** —— 授权是平台看过证之后的动作，界面要说清楚 */
  mSaveQualification(payload: QualificationSaveReq): Promise<Qualification>;
  /**
   * 整份替换 —— 勾选式界面的天然形状。
   *
   * <p><b>撤掉一个底下还有商品的类目会被拒（80008）</b>：不拦的话那些商品会挂在
   * 一个这家店已经不存在的货架上，店铺页里就此消失，而商品列表里还看得到。
   * 没那张证的类目也会被拒（70002），端上要把「缺哪张证」说出来。
   */
  mSaveStoreCategories(
    storeNo: string,
    items: { categoryNo: string; displayName?: string; sort?: number }[],
  ): Promise<StoreCategory[]>;

  // ---- 员工与授权（B-11.10）
  /** 含停用的。手机号已脱敏 */
  mStaffList(): Promise<MerchantStaff[]>;
  /** 加员工。**不发密码、不建 C 端账号** —— 他用自己的手机号验证码登录 */
  mAddStaff(loginPhone: string, displayName?: string): Promise<MerchantStaff>;
  /** 停用/启用。**老板不能被停用** */
  mSetStaffStatus(mchAccountNo: string, active: boolean): Promise<MerchantStaff>;
  /** 授权到店。role 传空 = 收回这家店的授权 */
  /**
   * 授予或撤销**一个**门店角色（一人一店可多角色，权限取并集）。
   *
   * @param granted 不传 = 授予；传 false = 撤销这一个角色
   */
  /**
   * 我在当前门店的作用域与权限。**登录后与切门店后都要调**。
   *
   * 页面按返回的 `perms` 裁剪入口 —— 后端拒绝是安全边界，前端隐藏是体验，
   * 两者都要有：只做后端，员工会看到一堆点了报错的入口；只做前端，那不是安全。
   */
  mBizScope(): Promise<BizScope>;

  mGrantStore(mchAccountNo: string, storeNo: string, role: StaffRole,
              granted?: boolean): Promise<MerchantStaff>;
  /**
   * 员工与授权的变更记录。**与员工管理同一档权限**（老板）——
   * 「谁给谁加了什么权限」本身就是权限信息，能看它的人不该比能改它的人多。
   *
   * @param mchAccountNo 只看某个人的；不传看全部
   */
  mStaffLogs(mchAccountNo?: string): Promise<StaffLog[]>;

  // ---- 角色（V71 自定义角色）
  /**
   * 本主体可用的角色：6 个预置（只读）+ 自定义。
   *
   * **每个角色带 `permLabels`（中文）与 `usedBy`（几个人在用）** ——
   * 前者让老板勾权限时不用盲选，后者是删除按钮的依据。
   */
  mRoles(): Promise<MerchantRole[]>;
  /**
   * 自定义角色可以勾的权限点（不含 `biz:store:admin`）。
   *
   * **勾选面板的唯一取值来源** —— 不要拿 `mRoles()` 的并集当选项，
   * 那份少一条 `biz:finance`（只有老板有，而老板那行是 `*`）。
   */
  mRolePerms(): Promise<PermOption[]>;
  /**
   * 建自定义角色。
   *
   * ⚠️ **`biz:store:admin` 不在可勾列表里**，传了后端也会拒（70006）——
   * 那是「管人」的码，授出去等于让被授权的人能改所有人的授权。
   */
  mCreateRole(payload: { name: string; perms: string[] }): Promise<MerchantRole>;
  /** 改名 / 改权限。**预置角色会被拒**（10400），要改先复制一份 */
  mUpdateRole(roleCode: string, payload: { name: string; perms: string[] }): Promise<MerchantRole>;
  /** 删除。**还有人在用时拒**（10409）—— 删了那些人的权限凭空消失 */
  mDeleteRole(roleCode: string): Promise<void>;

  mStoreQrcode(): Promise<StoreQrcode>;
  /** 分享素材：整店或单品。文案要带「还差 N 人」这类可直接转发的内容 */
  mShareKit(goodsNo?: string): Promise<ShareKit>;
  /** 真海报：封面/店名/价格/小程序码合成的一张图。整店或单品，取决于传不传 goodsNo */
  mPoster(goodsNo?: string): Promise<Poster>;

  // ---- 工作台（B-10.1 + B-11 汇总）
  mTodo(): Promise<MerchantTodo>;
  mStats(): Promise<MerchantStats>;

  // ---- 跨店总览与对比（B-11.12.5 / 11.12.6，增值包 P2）
  /**
   * 按店并列的今日 / 本月 / 三项待办。**单店商家也能打开** —— 他看到的就是他那一家，
   * 不是空列表也不是报错。
   *
   * ⚠️ **无能力位（FREE 档）会抛 `ApiError(70023)`**（`PLAN_CAPABILITY_REQUIRED`，
   * 文案带当前档位）。调用方要接住它并渲染**示例态**：
   * 入口照常显示、点进去看得到这一页长什么样 + 一句升档说明。
   * 渲染成空白页或红色报错的话，商家看到的是「功能坏了」而不是「这是付费功能」。
   */
  mCrossStoreOverview(): Promise<CrossStoreOverview>;
  /**
   * 按店对比：销售额 / 订单数 / 复购率 / 缺货数，外加一个**主体级**评分。
   *
   * ⚠️ 返回体里的 `rating` **不在每店那一行上**（`rvw_review` 只有 `entity_no`）——
   * 画成表格的一列会让各店显示同一个数字。放在页面顶部作主体口径说明。
   *
   * @param days 回看天数（含今天），默认 30。后端夹在 1–365 并在 `days` 字段回显实际值
   */
  mCrossStoreCompare(days?: number): Promise<CrossStoreCompare>;

  // ---- 我的增值包（B-11.13，增值包 P4）
  /**
   * 我的档位、用量与三档对比。
   *
   * ⚠️ **只有老板调得到**（`biz:store:admin`）—— 店长会拿到 403。
   * 端上按 `can('biz:store:admin')` 决定要不要渲染入口，别让店长点进去看报错。
   *
   * 用量（`storeUsed` / `staffUsed`）**一律用后端给的**：自己拿门店列表数会与
   * 建店那道闸的口径分岔（闸门只数营业中的店），表现是「页面说满了、其实还能建」。
   */
  mMyPlan(): Promise<MerchantPlan>;
  /**
   * 自助开通试用。**一主体一次，永不回退**。
   *
   * 能不能点看 `MerchantPlan.trialTier`（null = 不能）。**不要自己用
   * `planCode === 'FREE' && !trialUsed` 推** —— 那会漏掉「平台把试用天数配成 0」。
   *
   * @returns 开通后的视图，直接用它重渲染，不必再拉一次 {@link mMyPlan}
   */
  mStartTrial(): Promise<MerchantPlan>;

  // ---- 商品（B-11.3）
  /**
   * @param q.keyword 按标题模糊搜。服务层一直支持，端点此前写死传 null ——
   *                  商品一多，没有搜索的列表就只能靠滚
   */
  /**
   * @param status 四个状态词 + `OUT_OF_STOCK`（缺货）。
   *               缺货不是 `GoodsStatus` 的一员 —— 库里没有这个状态，它是按
   *               「所有 SKU 可用量都 ≤ 0」算出来的，且与在售**不互斥**
   *               （一件在售商品照样能全规格断货）。
   * @param categoryNo 按类目筛（通常是二级）。类目变必填之后，这是商家找货的主路径
   */
  /**
   * 标准品搜索 —— 建品页「从标准品开始」。按标题与别名匹配，只返回启用中的。
   *
   * <p>**搜不到不是错误**：标准库对「张姐家的酱菜」永远无效，而那类货是这个平台的
   * 一部分主力。端上必须让「搜不到 → 直接自建」这条路一样顺。
   */
  mSpuStdSearch(q: { keyword?: string; categoryNo?: string; limit?: number }): Promise<SpuStd[]>;
  mGoodsList(q: PageQuery & {
    status?: GoodsStatus | "OUT_OF_STOCK";
    keyword?: string;
    categoryNo?: string;
  }): Promise<PageResult<Goods>>;
  mGoodsDetail(goodsNo: string): Promise<Goods>;
  mSaveGoods(payload: GoodsDraft): Promise<Goods>;
  mToggleGoods(goodsNo: string, onSale: boolean): Promise<Goods>;
  mSaveStock(goodsNo: string, skuNo: string, stock: number): Promise<Goods>;
  /**
   * 改**当前门店**的库存（多门店）。门店走 `X-Store-No` 头，http-client 自动带。
   *
   * ⚠️ 第一次对某个 SKU 调用它，这个 SKU 就整体转为按店管理 ——
   * 此后没设过库存的门店卖不出这件商品（视为 0，不是回退主体总量）。
   * 所以只在商家**确实有多家店**时才用它，单店仍走 mSaveStock。
   */
  mSaveStoreStock(goodsNo: string, skuNo: string, stock: number): Promise<Goods>;

  /**
   * 改**当前门店**的售价。
   *
   * <p>与门店库存回退方向相反：没设过价的门店按主体价卖（设成 0 就是白送）。
   * `price` 传 `null` = <b>取消本店单独定价</b>，回到主体价 ——
   * 没有这条，商家给某店定过价之后就再也回不去。
   */
  mSaveStorePrice(goodsNo: string, skuNo: string, price: number | null): Promise<Goods>;

  /**
   * 提交审核：草稿 → 待审。
   *
   * <p>新建落的是**草稿**，不进运营的待审队列 —— 此前是「保存即提审」，
   * 商家填一半点保存就进了队列，而他自己看到「审核中」，既不敢改也不知道在等什么。
   * <b>重复调用无副作用</b>。
   */
  mSubmitGoods(goodsNo: string): Promise<Goods>;

  /**
   * 只改截单与到货说明（生鲜）。<b>不触发重审、不下架</b> ——
   * 生鲜商家每天晚上定明天的截单，走 `mSaveGoods` 的话改一次等于停一天生意。
   */
  mSavePresale(goodsNo: string, cutoffAt?: number, arrivalDesc?: string): Promise<Goods>;

  // ---- 商品图片与拍照建品（B-11.3.7 / E9）
  /** 上传一张图，返回可访问 URL。小程序侧走 uploadFile，域名需在白名单 */
  mUploadImage(tempPath: string): Promise<{ url: string }>;
  /**
   * 拍照识别：**只用来猜一个标题**，猜错不影响，店主可改。
   * ⚠️ 绝不做「一拍就自动上架」—— 识别错了价格也错，货会以错价卖出去。
   */
  mRecognizeGoods(imageUrl: string): Promise<GoodsGuess>;
  /**
   * 按商品名与主图生成**图文详情草稿**。
   *
   * <p>返回空串 = 没生成出来（模型不可达或写不出来）。这不是错误：
   * 「自动生成」是省事的捷径，不是必经步骤 —— 端上提示一句，商家照样能自己写。
   *
   * <p>结果**只填进输入框，不直接保存**：模型不知道这家店真实的产地与保质期，
   * 一键写进详情等于替商家做了他没做过的承诺。
   */
  mDescribeGoods(req: DescribeGoodsReq): Promise<{ detail: string }>;

  // ---- 类目（B-11.3.1）
  /**
   * 平台类目树（两级封顶）—— 编辑商品时选类目用。
   *
   * ⚠️ 与 `Goods["type"]`（五品类）**不是一回事**：type 决定履约与合规，平台硬编码；
   * 类目决定归类与经营准入，运营可维护。挂了资质门槛的类目，
   * 没拿到授权的商家**上架时**会被拒（保存草稿不拦）。
   */
  mCategoryTree(): Promise<Category[]>;

  // ---- 规格模板（B-11.3.2）
  /** 按类目取可用模板：平台预置 + 本商家存的常用 */
  /**
   * 可用规格模板。**两层：类目级优先于品类兜底。**
   *
   * <p>只传品类是不够的：品类只有 3 个而二级类目有 32 个，
   * STANDARD 一个就盖住 18 个 —— 手机数码与鲜花共用「包装：袋装/瓶装/罐装」
   * 这种推荐等于没有推荐。传上已选类目，后端会把该类目的专属模板排在前面，
   * 并用同名规格组顶掉兜底那条。
   */
  mSpecTemplates(categoryType?: Goods["type"], categoryNo?: string): Promise<SpecTemplate[]>;
  /**
   * 「加一个规格组」能挑的维度。**顺序即建议顺序**：
   * 本类目已配的（`categoryNo` 有值）→ 平台通用（`categoryNo` 为空、`scope=PLATFORM`）
   * → 这家店自建的（`scope=MERCHANT`）。
   *
   * <p>与 {@link mSpecTemplates} 的差别是范围：那个只给本类目配好的几条（自动预填用它），
   * 这个多给平台通用维度 —— 让商家**先看后挑**。此前他点「自定义规格」是对着一个
   * 空输入框凭记忆敲维度名：后端有「与平台重名就用平台那个」的兜底，
   * 但那要他恰好敲对字，敲「味道」而平台叫「口味」就撞不上，
   * 于是多出一个只有他一家能用的维度，他的货从此掉出跨店聚合。
   */
  mPickableDims(categoryNo?: string): Promise<SpecTemplate[]>;
  /**
   * 这一类的**商品参数**（产地 / 保质期 / 材质…）。
   *
   * <p>与 {@link mSpecTemplates} 的差别不是范围而是**性质**：那些是销售规格，
   * 买家要挑一档、每档单独定价单独算库存；这些只描述，不分 SKU、不影响价格。
   * 混在一起的后果是「本地 × 500g」变成一个要单独定价备货的行，
   * 而他只想说「这袋菜是本地的」。
   *
   * <p>形状复用 `SpecTemplate`：后端就是同一条装配链路出来的（只换 usage 判据），
   * 端上再造一个几乎一样的类型只会让两边慢慢长歪。
   */
  mSpecProps(categoryNo?: string): Promise<SpecTemplate[]>;
  /**
   * 还能加进这一类的**商品参数**。与 {@link mPickableDims} 是同一对关系
   * （那个之于销售规格），差别只在 usage。
   *
   * <p>参数也要能加：平台给这一类配的几条是起点不是上限 ——
   * 卖山货的想标「海拔」，平台没配，他该有地方把它加进来。
   */
  mPickableProps(categoryNo?: string): Promise<SpecTemplate[]>;
  /**
   * 在**平台维度**下加一个自己的规格值：「我这袋是 750g，平台没这一档」。
   *
   * <p>它挂在平台维度上，所以与平台值天然同轴 —— 跨店比价照样成立。
   * 撞上平台已有的那一档时后端不新建，直接把那一档返回来（端上按返回的 label 用）。
   *
   * <p>量纲维度（重量、容量…）会从文案里抽数字并换算（「1.5kg」→ 1500g）；
   * 抽不出来会拒，端上提示商家把数量写清楚。
   */
  mAddSpecValue(dimNo: string, label: string): Promise<SpecValueAdded>;

  // ---- 商品编码批量导入导出（P4）
  /**
   * 导出本店全部规格行的条码 / 货号 / 单位。
   *
   * <p>回的是 CSV 文本，不是文件流 —— 端上要先拿到内容才能显示、才能转 Blob 存盘，
   * 而 http 客户端只认 `{code,msg,data}` 信封（裸文件会被判成「响应格式不符合契约」）。
   */
  mSkuIdentityExport(): Promise<{ csv: string }>;
  /**
   * 试算：这份表会改什么、哪几行有问题。**不写库**。
   *
   * <p>批量写入必须先算后做：只报一个「导入成功」，商家会以为每行都生效了，
   * 而匹配不上的那些恰恰是他最需要知道的。
   */
  mSkuIdentityPlan(csv: string): Promise<SkuIdentityReport>;
  /** 真写。只写没问题的行，问题照旧报出来 */
  mSkuIdentityImport(csv: string): Promise<SkuIdentityReport>;
  /**
   * 自建一个规格维度（平台没有的，如「辣度」）。
   *
   * <p>**只在本店可用，不参与跨店比价** —— 这是它的定义，不是缺陷，界面上要说出来。
   * 与平台维度重名时后端直接返回平台那个：他要的是「按这个维度分规格」，
   * 而不是拥有一个自己的颜色维度。
   */
  /**
   * @param usageType `SALE`（默认，销售规格）/ `PROP`（商品参数）。
   *   **在「商品参数」栏里建出来的必须是参数** —— 建成销售规格的话它会跑去分 SKU，
   *   而他只是想标一个「海拔」。不传按 SALE，老调用方行为不变。
   */
  mAddSpecDim(name: string, labels: string[], usageType?: "SALE" | "PROP"): Promise<SpecTemplate>;

  /**
   * 「我的规格」：这家店自己建的维度 + 用量 + 配额。
   *
   * <p>此前商家**只能建、不能管** —— 建品页里输一个名字就落进规格库，
   * 之后没有任何地方看得到它。建错了只能一直留着，还占着配额，
   * 而配额用完那句报错也说不清是被什么占了。
   */
  mMySpecDims(): Promise<MerchantSpecDim[]>;
  /**
   * 本店货架类目各自能用的规格。**「我的规格」那一页的主体** ——
   * 自建规格线上是 0 条，只列自建的话这一页对所有商家都是空的，
   * 而它该回答的是「我能用哪些」。
   */
  mStoreSpecDims(storeNo?: string): Promise<StoreCategorySpecs[]>;
  /**
   * 保存本店对某个类目规格的覆盖。**返回合并后的最新结果** ——
   * 端上照它重渲染，省一次往返，也免得两边各算一遍合并规则。
   */
  /**
   * 某个规格下平台有的全部档位 —— 给「＋」做候选。
   *
   * <p>类目通常只裁了其中几档，而商家要加的往往正是没裁进来的那一档。
   * 不给候选他只剩手输，而手输的值没有编码，跨店聚合就断了。
   */
  mDimValues(dimNo: string): Promise<SpecOption[]>;
  mSaveSpecOverride(categoryNo: string, dims: SpecOverride[]): Promise<SpecTemplate[]>;
  /**
   * 改名。**不影响已建商品** —— 商品存的是规格快照。
   *
   * <p>不声明返回值：改完之后配额没变但用量可能变（改名会让老商品对不上，
   * 那是有意的 —— 历史不该被改名波及），就地更新反而容易与服务端不一致。
   * 页面整份重拉，一次请求换一个准确的界面。
   */
  mRenameSpecDim(dimNo: string, name: string): Promise<void>;
  /**
   * 停用 / 启用。**停用不是删除**：历史商品的规格组要靠它解释自己是什么。
   * 停用后只是建品时挑不到。
   */
  mArchiveSpecDim(dimNo: string, archived: boolean): Promise<void>;
  /** 把当前编辑的规格组存为「我的常用」，下次建品直接套 */
  mSaveSpecTemplate(payload: { name: string; options: string[] }): Promise<SpecTemplate>;

  // ---- 订单与配送（B-11.4）
  /** `status` 用 `OrderStatus` 而不是 `string` —— 松成 string 后，前端传个 "toShip"
   *  这种 tab key 上去也编译得过，而服务端只认状态枚举（由 requests.ts 的 satisfies 抓出）
   *
   *  ⚠️ **配送员拿到的是裁剪档**：只有 `COURIER` 一个角色的人，后端返回
   *  `CourierOrderVO`（`orderNo` / `status` / `fulfillment` / `itemQty` / `createdAt`），
   *  **没有 `amount`、没有 `verifyCode`、没有 `items`**（需求 §4.4：他送的是货不是钱）。
   *  类型这里仍声明为 `Order` —— 收窄成联合类型会让每个用到订单的页面都要分支，
   *  而只有配送页会遇到裁剪档。**用到金额的地方按字段有无渲染**，别按角色判。 */
  /** `status` 与 `fulfillments` 正交，见 c-app 的 orderList 注释 */
  mOrderList(
    q: PageQuery & { status?: OrderStatus; fulfillments?: string[]; allStores?: boolean },
  ): Promise<PageResult<Order>>;
  mOrderDetail(orderNo: string): Promise<Order>;
  /** 快递发货：回填运单号 */
  mShip(orderNo: string, expressNo: string): Promise<Order>;
  /** 商家自送：老板点一下「已送达」。不做骑手轨迹（ADR-005 §5） */
  mDelivered(orderNo: string): Promise<Order>;
  /**
   * 确认线下收款。**这是整条链路上唯一一处「钱」离开系统、落到人当面执行的动作** ——
   * 平台不代收这笔款，能提供的只有一条留痕（谁在什么时候点的）。
   * 权限用 `biz:receive` 不是 `biz:order:view`：后者是只读权限，配送员也持有。
   */
  mConfirmOfflinePay(subOrderNo: string): Promise<Order>;

  // ---- 预约排期（B-11.5）
  /** 本店时段。**连约满的和停掉的一起列** —— 只给「还能约的」，商家看不出为什么没人约 */
  mAppointmentSlots(storeNo: string, from: number, to: number): Promise<AppointmentSlot[]>;
  mOpenAppointmentSlot(
    storeNo: string,
    slot: { startAt: number; endAt: number; capacity: number },
  ): Promise<AppointmentSlot>;
  /** 停约。**不删行也不赶人** —— 语义是「别再往里放人」 */
  mCloseAppointmentSlot(slotNo: string): Promise<AppointmentSlot>;
  mDeliveryRule(): Promise<DeliveryRule>;
  mSaveDeliveryRule(rule: DeliveryRule): Promise<DeliveryRule>;

  // ---- 自提点履约（B-10）
  /** 本自提点的订单 —— 含别家商家的货，字段已按履约必需裁剪（B12） */
  /** 自提点履约总览（后端已实现）。承接方进履约台第一眼要看的三个数 */
  mPickupOverview(): Promise<PickupOverview>;
  /**
   * 本自提点的待履约单。**返回的是 `PickupOrder`，不是 `Order`** ——
   * 承接方可能替别家收货，字段按履约必需裁到最小（B12），
   * 状态也是子单那一套（`WAIT_FULFILL`…），不是主单的。
   */
  mPickupOrders(): Promise<PickupOrder[]>;
  mPickingList(): Promise<PickingRow[]>;
  /**
   * 到货登记。
   *
   * @param pickupNo 给哪个自提点登记；不传 = 当前门店的那个点。
   *                 多点商家必须能指定 —— 否则另一个点的货永远登记不上
   */
  mMarkArrived(subOrderNos: string[], pickupNo?: string): Promise<PickupOrder[]>;
  /**
   * 核销自提码。核销成功 → C 端该订单立刻变已完成。
   *
   * ⚠️ **失败也是 code 0**，靠返回体的 `success` 判 —— 不要只看有没有抛异常。
   * 码无效、已核销、不是本点这三种都会带 `reason` 回来，
   * 而它们对店主意味着完全不同的下一步。
   */
  mVerify(code: string): Promise<VerifyResult>;
  /**
   * 批量核销（B-6.4 扩展）。高峰期一个个扫码是自提点的真实痛点。
   * **逐条尝试、失败逐条回报**，不整批回滚 —— 一张废码不该让另外四单白扫。
   */
  mVerifyBatch(codes: string[]): Promise<VerifyBatchResult>;
  /**
   * 按取货码**片段**搜单。输码核销的兜底之兜底：
   * 码磨花了、屏幕反光、邻居只记得后四位时，全码输入这条路也走不通。
   *
   * 返回的是 `Order`，端上据此让店主确认是哪一单再核销 ——
   * **不直接核销**：模糊匹配可能命中多单，替他选一单是替他承担风险。
   */
  mVerifySearch(keyword: string): Promise<PickupOrder[]>;

  // ---- 售后（B-11.5）
  /**
   * 待处理售后。**返回售后单，不是订单** —— 后端 /biz/after-sale 给的是
   * List<AfterSaleVO>，端上此前把它类型成 Order[]，形状根本对不上。
   */
  mAfterSaleList(): Promise<AfterSale[]>;
  /** 同意：仅退款直接退；退货退款要等收货后才退（见 mConfirmReturn） */
  mApproveAfterSale(afterSaleNo: string, reply: string): Promise<AfterSale>;
  /** 驳回**必须给理由** —— 用户据此决定要不要上升平台，没理由就是把路堵死 */
  mRejectAfterSale(afterSaleNo: string, reply: string): Promise<AfterSale>;
  /**
   * 确认收到退货 → 随即退款（B-7.3）。
   * **退款必须在这一步之后** —— 同意即退的话，货没回来钱先出去了。
   */
  mConfirmReturn(afterSaleNo: string): Promise<AfterSale>;

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
  ): Promise<Quote>;

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

  /**
   * 会员列表。
   *
   * @param q.phone **完整手机号才匹配** —— 前缀模糊会把会员库变成一本通讯录，
   *                所以给一半查不到人，这是设计不是 bug
   * @param q.storeNo 主体开了「按门店经营会员」时必填；按主体经营时留空
   */
  mMembers(q?: {
    storeNo?: string; level?: string; source?: string; status?: string; phone?: string;
    /** 逗号分隔的标签号。**取交集**：选两个是「都要满足」 */
    tagNos?: string;
    lastOrderBefore?: number; lastOrderAfter?: number; spentMin?: number; spentMax?: number;
    page?: number; size?: number;
  }): Promise<PageResult<Member>>;

  /** 四层人数 + 可触达 + 本月新增 + **未绑手机号因此未计入的买家数** */
  mMemberStats(storeNo?: string): Promise<MemberStats>;

  mMemberDetail(memberNo: string): Promise<MemberDetail>;

  /**
   * 手工录入一个手机号。
   *
   * 本人还没在平台出现过时返回的会员 `status` 是 `LEAD`（线索）——
   * **不可触达、不进任何受众**。这句话要在**保存之前**告诉商家，
   * 否则他会以为「发不出去」是功能坏了。
   *
   * 已存在就返回那一条并把备注并进去，不报错：店员重复录入是常态。
   */
  mEnrollMember(payload: {
    phone: string; remark?: string; tagNos?: string[]; storeNo?: string;
  }): Promise<Member>;

  /** 改备注 / 拉黑与恢复。**线索不能被商家点成正式会员** —— 那只能由本人绑号触发 */
  mPatchMember(memberNo: string, payload: { remark?: string; status?: string }): Promise<Member>;

  /** 批量打标 / 去标。先筛出人再一次性打 —— 一个个点是这一页最没必要的重复劳动 */
  mTagMembers(payload: { memberNos: string[]; add?: string[]; remove?: string[] }): Promise<void>;

  /** 标签字典 + 每个标签多少人 */
  mMemberTags(): Promise<MemberTag[]>;

  mCreateMemberTag(name: string): Promise<MemberTag>;

  /** `enabled` 非空 = 停用/恢复；否则按 `name` 改名。**系统标签两样都不许** */
  mEditMemberTag(tagNo: string, payload: { name?: string; enabled?: boolean }): Promise<MemberTag>;

  /**
   * 合并两个标签。
   *
   * `confirm` 不传或 false = **只试算**：返回影响面不落库。
   * 界面必须先把「多少人会改、其中多少人两个都有」摆出来再让他按 —— 合并不可逆。
   */
  mMergeMemberTag(tagNo: string, payload: {
    intoTagNo: string; confirm?: boolean;
  }): Promise<MemberMergePreview>;

  /** 会员经营口径（按主体 / 按门店）+ 支付自动入会开关 */
  mMemberSettings(): Promise<MemberSetting>;

  /**
   * 改经营口径。**只有店主能改**（`biz:store:admin`）—— 它一改，
   * 全主体的分层与所有活动受众跟着变。
   *
   * 界面上必须写清两件事：改成按门店后，在别的店买过的人**在这家店仍算新客**；
   * 以及**随时可以切回来，一个数都不少**（两份指标一直都在算）。
   */
  mSaveMemberSettings(payload: {
    memberScope?: string; autoJoinOnOrder?: boolean;
  }): Promise<MemberSetting>;

  /** 人群列表。`lastCount` 只是「上次算于 X 时」，别拿它当当前人数用 */
  mMemberSegments(): Promise<MemberSegment[]>;

  /**
   * 存人群。**存的是条件不是名单** —— 发券那一刻会重算，
   * 「那一刻命中了谁」记在发放记录里。
   *
   * 传 `segmentNo` 是改；不传且同名视为改同一个（不报重名错）。
   */
  mSaveMemberSegment(payload: {
    segmentNo?: string; name: string; scopeStoreNo?: string; rule: MemberSegmentRule;
  }): Promise<MemberSegment>;

  mRemoveMemberSegment(segmentNo: string): Promise<void>;

  /** 试算：命中多少人、其中多少人能真正收到。**两个数都要显示** */
  mPreviewMemberSegment(payload: {
    scopeStoreNo?: string; rule: MemberSegmentRule;
  }): Promise<MemberSegmentPreview>;

  // ---- 券（P4，新模型）
  /** 商家自己的券。`includeEnded` 为真时把已结束的也带上 */
  mCoupons(includeEnded?: boolean): Promise<MerchantCoupon[]>;

  mCoupon(couponNo: string): Promise<MerchantCoupon>;

  /**
   * 建券 / 改券。**敞口在这一步就要算清**，页面上四条硬校验与后端一致：
   * 折扣券必须封顶；折扣率是**万分比**且不低于一折（填 88 表示顾客付 0.88%，
   * 等于白送）；发行量只有定向发放允许留空；预算非零时必须兜得住
   * 发行量 × 单张最大优惠。
   *
   * 还有一条容易被当成后端小气的：**下单抵扣的券不能按类目或商品限定范围** ——
   * 算价拿不到商品明细，放行的话「仅限粮油」的券买猫粮照样能用。
   * 要按品类限，就得改成到店核销。
   */
  mSaveCoupon(payload: MerchantCouponDraft): Promise<MerchantCoupon>;

  /** 暂停 / 恢复 / 结束。**不动已经发到用户手上的券** —— 那是他已有的权益 */
  mSetCouponStatus(couponNo: string, status: string): Promise<MerchantCoupon>;

  /**
   * 按人群定向发券。
   *
   * 返回里的 `skipped` / `skipReasons` **必须显示在结果页上** ——
   * 不显示的话商家会以为人群里每个人都收到了。
   * 超预算时整批拒绝（不部分发放），这时抛错而不是返回一个「发了一半」的结果。
   */
  mIssueCoupon(couponNo: string, segmentNo: string): Promise<CouponIssueBatch>;

  mCouponIssues(couponNo?: string): Promise<CouponIssueBatch[]>;

  /**
   * 到店核销「先看」那一步。**不要跳过它** ——
   * 扫完直接核的话，扫错一张没有回头路（线下核销不可撤销）。
   */
  mPeekCouponCode(code: string): Promise<CouponRedeemView>;

  /**
   * 核销一次。返回 `duplicated=true` 表示 3 秒内重复提交（店员连点），
   * **这不是失败**：要提示「刚才那次已经成功」，而不是报错让他再按一次。
   */
  mRedeemCoupon(code: string): Promise<CouponRedeemResult>;

  // ---- 活动（P5，新模型）
  mActivities(includeEnded?: boolean): Promise<StoreActivity[]>;

  mActivity(activityNo: string): Promise<StoreActivity>;

  /**
   * 建 / 改活动。**端上的校验与后端一字不差**：
   * 长期活动必须有限量或预算（没有结束时间又没上限 = 永久敞口）；
   * 改单价与送商品必须限量且必须选商品（单次成本由商品决定，卖得越好亏得越多）。
   */
  mSaveActivity(payload: StoreActivityDraft): Promise<StoreActivity>;

  /** 启停 / 结束。**已结束的不能复活** —— 复活会覆盖掉「当初为什么停」 */
  mSetActivityStatus(activityNo: string, status: string): Promise<StoreActivity>;

  /** 冲突提示。不阻止保存，但要在保存前说出来 */
  mActivityConflicts(goodsNos: string[]): Promise<ActivityConflict[]>;

  // ---- 触达（P7）
  /**
   * 群发试算。**先算后发** —— 这是唯一会打扰真实用户的动作。
   * `scene` 决定频次闸的档位：`NOTICE` 公告 / `WAKEUP` 唤回 / `COUPON` 发券通知。
   */
  mPlanReach(payload: { segmentNo?: string; scene: string }): Promise<ReachPlan>;

  /** 真发。跳过分布要显示在结果里，不能只报一句「发送成功」 */
  mSendReach(payload: {
    segmentNo?: string; scene: string; title: string; body: string;
  }): Promise<ReachResult>;

  // ---- 结算（B-11.9）
  /**
   * 结算流水。**一笔子订单一行**，不是周期账单。
   *
   * @param allStores 是否看全部门店。默认（false）只看当前门店 ——
   *                  与订单页同一套惯例；「全部」对老板和店员不是一回事，后端按授权收窄
   */
  mSettleList(allStores?: boolean): Promise<SettleBill[]>;
  /** 费率卡（后端已实现）。把费率讲清楚是「自带客流零佣金」能起作用的前提 */
  mRateCard(): Promise<RateCard>;

  // ---- 到货异常上报（B-10.4.2）
  /** 破损 / 短少上报。下游是售后责任判定（平台 / 供货商家 / 自提点商家，M4 待定） */
  mReportShortage(
    subOrderNo: string,
    payload: { skuNo: string; kind: ArrivalIssueKind; qty: number; note: string },
  ): Promise<PickupOrder>;

  // ---- 积分（商家侧只有成本与开关，看不到抵扣与补差）
  /** 收入按状态汇总（四个数）。**门店收窄与 mSettleBills 逐字一致** —— 否则总览与明细对不上 */
  mIncomeSummary(allStores?: boolean): Promise<IncomeSummary>;

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

  // ---- 消息（新订单/售后/评价的落点；推送与响铃是三期的加速通道，这里是事实记录）
  mMessageList(): Promise<Message[]>;
  /** 未读数。tabBar 红点 30s 轮询用 —— 拉整个列表数未读是把带宽当角标用 */
  mMessageUnread(): Promise<number>;
  mMessageRead(messageNo: string): Promise<Message[]>;
  mMessageReadAll(): Promise<Message[]>;

  // ---- App 推送设备（ADR-018；仅 App 构建有 clientId）
  mRegisterPushToken(platform: string, provider: string, clientId: string): Promise<void>;
  /**
   * 解绑。**登出必须调** —— 门店共用一台手机换班时，
   * 上一班的人不能继续收到这家店的订单推送。
   */
  mUnregisterPushToken(clientId: string): Promise<void>;

  // ---- 进销存（P-18 / B-1…B-21）。**独立模块、独立库**，见 shop-inventory
  //
  // 这一组的形状**逐字对着后端 record 抄**，不照界面拟：运营端那三页就是照自拟的
  // 形状接的，mock 也照那份写，于是 mock 自查全过而真接口一个都调不通。

  /** 库存总览三个数：在售 SKU / 缺货 / 滞销 */
  mStockSummary(): Promise<StockSummary>;
  /**
   * 库存列表。
   *
   * @param filter `todo` 只给有待办标记的（默认，**要处理的排最前**）· `all` 全部 ·
   *               `reserved` 有预留。一屏 200 多个 SKU，按字母排等于没排 ——
   *               商家打开这一页只为两件事：哪件断了、哪件压着
   */
  mStockBalances(q?: { filter?: string; size?: number }): Promise<StockBalance[]>;
  /**
   * 开单时挑货用。**与 `mStockBalances` 问的不是一件事**：
   * 那一条问「我有多少」（读余额），这一条问「哪件货」（读物料）。
   *
   * 余额行是按需建的，一件从没进过货的物料没有那一行 —— 读余额的话它不存在，
   * 于是**商家没法给它记第一笔进货**。进货、盘点两处必须走这一条。
   */
  mStockPickable(q?: { q?: string; size?: number }): Promise<StockBalance[]>;
  /** 单件明细：各库位分布 + 条码货号 */
  mStockItem(itemId: string): Promise<StockItemDetail>;
  /** 变动明细。每一行都带操作人、单据号、变动前后 —— 「昨天还有 20 袋今天剩 3 袋」从这里回答 */
  mStockLedger(q?: { itemId?: string; cursor?: number; size?: number }): Promise<StockLedgerPage>;
  /**
   * 直接改数（把实存改成点出来的数）。
   *
   * **它不是「设置库存」** —— 后端按盘点走：生成一张单、落一行流水。
   * 差异不为 0 时 `reasonCode` 必填，否则这个月报损了多少永远汇总不出来。
   */
  mStockAdjust(req: { itemId: string; countedQty: number; reasonCode?: string }): Promise<void>;

  /** 记一笔进货 → 单号。存草稿用它，过账另调 */
  mInboundCreate(req: StockInboundReq): Promise<string>;
  mInboundUpdate(no: string, req: StockInboundReq): Promise<void>;
  /** 过账。**过账才动库存**，草稿不动 */
  mInboundPost(no: string): Promise<void>;
  /** 作废。**已过账的只能整单作废重录** —— 改单据等于改历史 */
  mInboundVoid(no: string): Promise<void>;

  /** 报损/领用出库 → 单号。`purpose` 见 InvEnums.OutboundPurpose */
  mOutboundCreate(req: StockOutboundReq): Promise<string>;
  mOutboundPost(no: string): Promise<void>;
  mOutboundVoid(no: string): Promise<void>;

  /** 开一张盘点单 → 单号。**开单那一刻锁账面数**，盘的过程中照常卖不影响差异 */
  mCountOpen(itemIds: string[]): Promise<string>;
  /** 读回一张盘点单。**账面数是开单那一刻的快照**，端上直接用它算差异 */
  mCountDetail(no: string): Promise<StockCount>;
  mCountFill(no: string, lines: StockCountFilled[]): Promise<void>;
  /** 过账：盘盈生成入库单、盘亏生成出库单 —— 盘点自己不改库存，走同一个过账口 */
  mCountPost(no: string): Promise<void>;

  /** 调拨 → 单号。**一定生成两张单**，哪怕骑车十分钟就送到 */
  mTransferCreate(req: { fromLocationId: string; toLocationId: string; lines: StockLineReq[] }): Promise<string>;
  /** 读回一张调拨单。**草稿态没有行**（行在发出的那张出库单上），不是空单 */
  mTransferDetail(no: string): Promise<StockTransfer>;
  mTransferShip(no: string): Promise<void>;
  mTransferReceive(no: string): Promise<void>;

  /** 单据中心。`kind` 空=全部，否则 IN / OUT / COUNT / TRANSFER */
  mStockDocuments(q?: { kind?: string; size?: number }): Promise<StockDocument[]>;
  /** 月报。`month` 形如 2026-08 */
  mStockMonthly(month: string): Promise<StockMonthly>;
  /** 榜单。`type` fast 动销 / slow 滞销 */
  mStockRanking(q?: { type?: string; size?: number }): Promise<StockRank[]>;

  /** 库位与仓。**在途是一个真实的库位**，不是「暂时没有」 */
  mStockLocations(): Promise<StockLocation[]>;
  mWarehouseCreate(name: string): Promise<string>;
  /** 设发货源。**不允许接力**（A→B→C）：第一个后果是环，第二个是没人说得清货从哪出 */
  mLocationSetSource(id: string, sourceLocationId: string | null): Promise<void>;
}
