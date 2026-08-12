// 契约镜像 —— 与后端 /mp/** 同源。后端 openapi 就绪后改为自动生成。
// 口径：camelCase · 单号 xxxNo · 时间 xxxAt(毫秒时间戳/UTC) · 枚举大写下划线
// 金额：一律「最小货币单位」整数（人民币分 / 美分 / 菲尔），展示时按市场货币格式化

import type {
  AREA_LEVEL,
  AREA_STATUS,
  COMMUNITY_APPLY_STATUS,
  CATEGORY_TYPE,
  CURRENCIES,
  FULFILLMENT,
  FULFILLMENT_REACH,
  LANGS,
  MARKETS,
  SERVICE_SCOPE,
} from "@shared/utils/constants";

export type CategoryType = (typeof CATEGORY_TYPE)[keyof typeof CATEGORY_TYPE];
export type FulfillmentType = (typeof FULFILLMENT)[keyof typeof FULFILLMENT];
export type Lang = (typeof LANGS)[number]["id"];
export type CurrencyCode = keyof typeof CURRENCIES;
export type MarketId = (typeof MARKETS)[number]["id"];
export type ServiceScope = (typeof SERVICE_SCOPE)[keyof typeof SERVICE_SCOPE];
export type FulfillmentReach = (typeof FULFILLMENT_REACH)[keyof typeof FULFILLMENT_REACH];
export type AreaLevel = (typeof AREA_LEVEL)[keyof typeof AREA_LEVEL];
export type AreaStatus = (typeof AREA_STATUS)[keyof typeof AREA_STATUS];
export type CommunityApplyStatus =
  (typeof COMMUNITY_APPLY_STATUS)[keyof typeof COMMUNITY_APPLY_STATUS];

/** 多语言文案（mock 内部用；对外契约由后端按 Accept-Language 返回已本地化的 string） */
export type I18nText = Record<Lang, string>;

/** 统一响应包 */
export interface Result<T> {
  /** 业务状态码，`0` 表示成功；非 0 时 `data` 无意义，按 `msg` 提示用户 */
  code: number;
  /** 面向用户的提示文案，已按 Accept-Language 本地化 */
  msg: string;
  /** 业务数据。成功时必定存在（无返回值的接口给 `null`） */
  data: T;
}

/** 统一分页包 */
export interface PageResult<T> {
  /** 当前页数据 */
  records: T[];
  /** 满足条件的总条数（不是总页数）——端上据此判断还有没有下一页 */
  total: number;
  /** 当前页码，从 1 起 */
  page: number;
  /** 每页条数 */
  size: number;
}

export interface PageQuery {
  /** 页码，从 1 起。不传按 1 处理 */
  page?: number;
  /** 每页条数。不传按各接口默认值（通常 10 或 20） */
  size?: number;
}

// ---------------------------------------------------------------- 用户与归属

export interface User {
  /** C 端用户单号。前缀 `cUser` 是有意的：B 端商家、平台 STAFF 是**另外两个账号池**，单号不通用 */
  cUserNo: string;
  /** 昵称。微信授权取来的，用户可改 */
  nickname: string;
  /** 头像 URL */
  avatar: string;
  /** 手机号。已脱敏（中间四位星号），完整号码不下发到端上 */
  phone: string;
  /** 当前绑定的社区。未绑定时为空 —— 首页的商品可见范围依赖它 */
  communityNo?: string;
  /** 默认自提点。下单时预选，用户可改 */
  pickupNo?: string;
  /** 常去的店。与 communityNo 正交 —— 可以在 A 社区却常买 B 店（ADR-004 §5.1） */
  merchantNo?: string;
}

export interface Community {
  /** 社区单号 */
  communityNo: string;
  /** 社区名（小区名） */
  name: string;
  /** 社区地址 */
  address: string;
  /** 所属城市。全市范围的商家靠它判定可达 */
  cityCode: string;
  /** 米 */
  distance: number;
  /** 本社区可用的自提点 */
  pickups: Pickup[];
}

export interface Pickup {
  /** 自提点单号 */
  pickupNo: string;
  /** 自提点名称（通常是承接店铺的店名） */
  name: string;
  /** 自提点地址 */
  address: string;
  /** 距当前社区的距离（米），服务端算好下发 */
  distance: number;
  /** 承接这个自提点的商家（ADR-005：PickupPoint.type=STORE，承接方是入驻商家而非团长） */
  hostMerchantNo: string;
  /** 承接商家的店名 */
  hostName: string;
  /** 承接商家的头像/门头图 */
  hostAvatar: string;
  /** 营业时间文案，如 `08:00-21:00`。展示用，不参与计算 */
  openHours: string;
  /** 到货时间说明，如「次日 18:00 后到」。影响用户选不选这个点 */
  arrivalDesc: string;
}

// ---------------------------------------------------------------- 积分
//
// 积分能被商家接收并向平台兑付 → 它是平台的负债，不是营销数字。
// 因此账户、流水、有效期都要按「资金」的标准建模，见 ADR-006。

export type PointRecordType =
  /** 消费获得 */
  | "EARN"
  /** 下单抵扣 */
  | "USE"
  /** 退款返还 */
  | "REFUND"
  /** 过期作废 */
  | "EXPIRE"
  /** 商家收款（B 端账户） */
  | "RECEIVE"
  /** 平台兑付给商家（B 端账户） */
  | "SETTLE";

export interface PointRecord {
  /** 流水单号。积分是平台负债，每一笔变动都要可追溯（ADR-006） */
  recordNo: string;
  /** 变动类型，决定这笔是增是减 */
  type: PointRecordType;
  /** 变动量，正=增加 负=减少 */
  points: number;
  /** 流水标题，如「订单消费获得」「过期作废」。展示用 */
  title: string;
  /** 关联订单。消费/退款类必有，过期/结算类为空 */
  orderNo?: string;
  /** 发生时间 */
  at: number;
  /** 变动后余额，用于对账 —— 只存变动量的话，一条记录出错后面全错 */
  balanceAfter: number;
}

/** 用户积分账户。**单位是积分个数** —— 商家侧是钱，用 {@link MerchantPointAccount} */
export interface PointAccount {
  /** 当前可用余额。**只含能花的分**，待生效的在 pendingBalance */
  balance: number;
  /**
   * 待生效积分：已发放但未过售后期，**不计入 balance**。
   *
   * 两个数必须分开展示（「可用 400 / 待生效 100」）。合成一个的话，
   * 用户看到「我有 500 分」却只能用 400，没有任何办法解释这个差额。
   */
  pendingBalance: number;
  /** 最近一批待生效积分的可用时间。`pendingBalance=0` 时为空 */
  pendingActivateAt?: number;
  /** 累计获得（含已用、已过期），只增不减 */
  totalEarned: number;
  /** 累计已抵扣 */
  totalUsed: number;
  /** 30 天内将过期的积分 */
  expiringSoon: number;
  /** 最近一批积分的过期时间。`expiringSoon=0` 时为空 */
  expiringAt?: number;
}

/** 商家的一条发分服务费记录：一单一条，来自 `stl_bill.points_fee_minor` */
export interface MerchantPointsRecord {
  /** 结算单号 */
  settleNo: string;
  /** 关联子单，商家据此对到具体订单 */
  subOrderNo: string;
  /** 本单发放的积分数 */
  points: number;
  /** 本单的发分服务费（分）。**这是商家唯一感知到的积分成本** */
  feeMinor: number;
  /** 账期 `YYYYMM` */
  period: string;
  /** 计提时间（支付成功时），不是分账时间 —— 两者相差一个售后期 */
  at: number;
}

/**
 * 结算页的积分试算结果。**服务端算**，端上只负责显示。
 *
 * 端上自己算的话，下单时服务端会再算一遍 —— 两处算法只要有一点不同
 * （券后金额口径、运费是否参与、开关判断顺序），用户就会看到
 * 「结算页说能抵 30，下单后只抵了 25」，而这个差额没人解释得清。
 */
export interface PointsDeductible {
  /** 本单最多可抵扣的积分数。已扣掉四级开关与上限，端上直接用 */
  maxPoints: number;
  /** 对应金额（分） */
  maxAmountMinor: number;
  /** 用户当前可用余额，用于展示「你有 X 分」 */
  balance: number;
  /** 不可用时的原因，直接展示。可用时为空 */
  disabledReason?: string;
}

/**
 * 商家的积分成本视图。**单位是钱，不是分**。
 *
 * 商家只感知**一件事**：开了积分，每笔订单要付一笔发分服务费。
 * 他**看不到**用户抵了多少分、平台补了多少、资金池 —— 对他而言订单就是全额，
 * 收到的是「订单金额 − 各项费用」（V34）。
 *
 * 所以这里没有 income/net：商家侧不存在「积分兑付进账」这个概念。
 */
export interface MerchantPointAccount {
  /** 本期发分服务费支出（分）。**商家唯一感知到的积分成本** */
  periodExpenseMinor: number;
  /** 当前账期标识，如 `2026-08` */
  period: string;
  /** 本店积分是否生效 —— 全局 AND 社区 AND 主体非小微 AND 本店开关 */
  enabled: boolean;
  /**
   * 不生效的原因，直接展示给商家。
   *
   * 小微主体要说「升级为个体工商户后可开启」，不能说「本店未开启积分」——
   * 后者会让商家去开一个他根本开不了的开关。
   */
  disabledReason?: string;
  /** 平台按行业强制开，商家不可自行关闭 */
  forced: boolean;
}

// ---------------------------------------------------------------- 消息

/**
 * 站内消息。
 * 三类分开是因为**用户对它们的期待完全不同**：交易类必须看到（到货了要去取），
 * 活动类可以错过，系统类是通知。混在一个列表里，交易消息会被活动消息淹没。
 */
export type MessageType = "TRADE" | "MARKETING" | "SYSTEM";

export interface Message {
  /** 消息单号 */
  messageNo: string;
  /** 消息分类，决定它落在哪个 tab */
  type: MessageType;
  /** 标题（列表页展示） */
  title: string;
  /** 正文 */
  body: string;
  /** 点进去要跳哪（订单详情/商品/团），已是完整页面路径带参 */
  link?: string;
  /** 是否已读。未读数按 type 分别统计 */
  read: boolean;
  /** 消息产生时间 */
  at: number;
}

// ---------------------------------------------------------------- 地址簿

export interface Address {
  /**
   * 地址 ID。这里是 `Id` 不是 `No` —— 它不是业务单号，是用户地址簿里的一条本地记录，
   * 不跨端流转、不出现在订单快照里（下单时地址是**整体快照**进订单的）
   */
  addressId: string;
  /** 收货人姓名 */
  name: string;
  /** 收货人手机号 */
  phone: string;
  /** 省市区 */
  region: string;
  /** 详细地址（街道门牌） */
  detail: string;
  /** 是否默认地址。整个地址簿至多一条为 true */
  isDefault: boolean;
  /** 标签：家 / 公司 / 其他 */
  tag?: string;
}

// ---------------------------------------------------------------- 售后

/**
 * 售后原因。**取值与后端 `/mp/after-sale/reasons` 下发的一致** ——
 * 端上不再自己硬编码一份清单（此前那份少两个、多一个，两边各自漂移，
 * 运营改后端那份端上纹丝不动）。
 *
 * 后端下发的是**码**不是文案：这是三语 App，翻译得留在端上。
 */
export type AfterSaleReason =
  | "NOT_WANTED"
  | "DAMAGED"
  | "MISSING"
  | "WRONG_ITEM"
  | "QUALITY"
  | "EXPIRED"
  | "OTHER";

// ---------------------------------------------------------------- 商家
//
// 数据模型从一开始就按**多商家**建：merchantNo 贯穿商品/订单/评价/结算。
// 一期平台方是唯一入驻方，所有数据都挂在它名下 —— 二期开放第三方入驻是配置变更，不是重构。
// 形态与拆分时机见 docs/technical/ADR/ADR-001。

/**
 * 商家主体类型 —— **权威口径取通道侧**（ADR-010）。
 *
 * 主体类型的唯一硬约束来自支付通道：能不能进件、要什么资质、钱打到个人还是对公。
 * 展示名反而可以随便改。让权威贴着约束走，映射就只需要一个方向。
 *
 * 规则（要不要执照、受不受行业白名单限制、结算账户形态）在
 * `sys_merchant_subject` 表里，随通道调整；**这里只管取值域**。
 * 端上取 `GET /common/master-data`，不要在页面里写死。
 *
 * <p><b>不叫 `SubjectType`</b>：那个名字在平台端已经是**风控主体**
 * （DEVICE/MERCHANT/USER）。两个不同的概念同名，读代码的人迟早会把
 * 一个当成另一个 —— 类型对齐守卫正是为此存在的。
 */
export type MerchantSubject = "MICRO" | "INDIVIDUAL" | "ENTERPRISE";

/**
 * @deprecated 用 {@link MerchantSubject}。旧取值 `PLATFORM/COMPANY/INDIVIDUAL` 已废弃 ——
 * 其中 `PLATFORM`（平台自营）**不是一种主体类型**：平台自营的主体也是个企业，
 * 「自营」是归属标记，混进主体枚举里让这一列同时承担了两件事。
 * 一期没有真实的自营商家，暂不为它单开字段。
 */
export type MerchantType = MerchantSubject;

/** 商品卡/详情上挂的商家简要信息 */
export interface MerchantBrief {
  /** 商家单号。贯穿商品/订单/评价/结算，是多商家模型的主线（ADR-001） */
  merchantNo: string;
  /** 店铺名 */
  name: string;
  /** 店铺 logo URL */
  logo: string;
  /** 综合评分，0–5，保留一位小数 */
  rating: number;
  /** 是否通过资质认证 */
  verified: boolean;
  /** 选定报价后不履约的次数。>0 会在报价卡上公示 —— 事后信用替代事前审核 */
  breachCount: number;
}

/** 消费过的商家（「我买过的」列表用） */
export interface VisitedMerchant extends Merchant {
  /** 在该商家的下单次数 */
  orderCount: number;
  /** 最近一次下单时间 */
  lastOrderAt: number;
}

export interface Merchant extends MerchantBrief {
  /** 商家类型：平台自营 / 企业 / 个体 */
  type: MerchantType;
  /** 店铺简介 */
  desc: string;
  /**
   * 经营范围 —— 邻里购物的核心约束：**商家是有服务半径的**。
   * 隔壁区的生鲜店对我没有意义，它送不到我的自提点。见 SERVICE_SCOPE。
   */
  serviceScope: ServiceScope;
  /** 覆盖哪些社区。**仅 scope=COMMUNITY 时有意义**，其余情况忽略 */
  serviceCommunityNos: string[];
  /** 覆盖哪个城市。**仅 scope=CITY 时有意义** */
  serviceCityCode?: string;
  /** 距当前社区的距离（米）。由服务端按用户当前社区算好下发，端上不自己算 */
  distance?: number;
  /** 累计订单量（评分权重之一） */
  salesCount: number;
  /** 参与评分的评价条数 */
  ratingCount: number;
  /** 在售商品数 */
  goodsCount: number;
  /** 店铺地址。纯线上商家可能没有 */
  address?: string;
  /** 营业时间文案 */
  openHours?: string;
  /** 入驻时间 */
  /** 入驻时间 */
  joinedAt: number;
  /** 店铺标签，如「生鲜」「次日达」。展示用，不参与筛选 */
  tags: string[];
  /** 分维度评分：商品/服务/时效 */
  scores: { goods: number; service: number; speed: number };
}

// ---------------------------------------------------------------- 评价

export interface Review {
  /** 评价单号 */
  reviewNo: string;
  /** 被评价的商品 */
  goodsNo: string;
  /** 被评价的商家。差评会计入商家评分与申诉流程 */
  merchantNo: string;
  /** 评价人昵称（匿名评价时为「匿名用户」） */
  nickname: string;
  /** 评价人头像 */
  avatar: string;
  /** 总分，1–5 整数 */
  rating: number;
  /** 评价正文 */
  content: string;
  /** 评价图 URL 列表 */
  images: string[];
  /** 购买规格。展示在评价上，让人知道这条评价说的是哪个 SKU */
  spec: string;
  /** 评价提交时间 */
  createdAt: number;
  /** 点赞数 */
  likeCount: number;
  /** 当前用户是否已点赞 */
  liked: boolean;
  /** 商家回复 */
  reply?: string;
  /**
   * 三维度评分（B-9.3 / P-13.1.4）。总分 `rating` 仍保留 ——
   * 老数据没有分维度分，列表页也只显示一个星级；维度分用于**评分算法与商家诊断**：
   * 「货好但送得慢」这种问题，只看总分永远看不出来。
   */
  scores?: ReviewScores;
  /** 商家申诉（B-9.4）。裁决在平台端 P-13.1 */
  appeal?: ReviewAppeal;
}

/** 三维度：商品本身 / 履约（快慢、包装、缺损） / 服务（沟通、售后态度） */
export interface ReviewScores {
  /** 商品本身，1–5 */
  goods: number;
  /** 履约：快慢、包装、缺损，1–5 */
  fulfillment: number;
  /** 服务：沟通、售后态度，1–5 */
  service: number;
}

/**
 * 批量核销结果。
 * **不是整批回滚**：逐条尝试，失败的逐条回报 —— 店主需要知道**哪一单**没成，
 * 而不是「3 成功 2 失败」然后自己一个个找。整批回滚更糟：一张废码会让另外四单白扫。
 */
export interface VerifyBatchResult {
  /** 成功核销的单数 */
  successCount: number;
  /** 失败明细。code 是那张码，reason 是为什么不行 */
  failed: { code: string; reason: string }[];
}

/**
 * 自提点履约总览（后端 `GET /biz/pickup/overview`）。
 * 承接方最关心的三个数：还有几单没人来取、今天到了几批、这些活挣了多少服务费。
 */
export interface PickupOverview {
  /** 自提点单号 */
  pickupNo: string;
  /** 自提点名称 */
  pickupName: string;
  /** 待核销单数 —— 到货了还没人来取的 */
  pendingVerify: number;
  /** 今日到货批次 */
  arrivedBatches: number;
  /** 累计履约服务费（最小货币单位） */
  serviceFeeMinor: number;
}

/**
 * 费率卡（后端 `GET /biz/settle/rate-card`）。
 *
 * ⚠️ 费率是**万分比整数**（后端 `platformRate / 100.0` 才是百分数）——
 * 直接当百分数显示会把 2% 显示成 200%。
 * 语义同样要照搬：**费率以下单时快照为准，调整不影响历史订单** ——
 * 不写清楚的话，商家会以为平台调价能追溯到已成交的单。
 */
export interface RateCard {
  /** 自带客流费率（万分比）。商家自己带来的客人，平台抽成低 */
  merchantOwnedRate: number;
  /** 平台客流费率（万分比）。平台分发带来的订单 */
  platformRate: number;
  /** 费率说明文案。**须写明「以下单时快照为准，调整不影响历史订单」** */
  note: string;
}

export type ReviewAppealStatus =
  | "PENDING" // 待平台裁决
  | "UPHELD" // 申诉成立 —— 原评价下架
  | "REJECTED"; // 申诉驳回 —— 评价保留

/**
 * 商家对差评的申诉。
 * 这是**唯一**能把差评送进平台裁决台的入口 —— 平台端 P-13.1 的裁决页早就建好了，
 * 但 B 端一直没有申诉入口，那张台子收不到任何单，等于空转。
 */
export interface ReviewAppeal {
  /** 申诉单号 */
  appealNo: string;
  /** 申诉理由，商家填写 */
  reason: string;
  /** 举证图（聊天记录、物流截图） */
  images: string[];
  /** 裁决状态 */
  status: ReviewAppealStatus;
  /** 申诉提交时间 */
  submittedAt: number;
  /** 裁决说明。**无论成立还是驳回都必须写** —— 商家会看到，「已读不处理」不是一种结果 */
  verdict?: string;
}

// ---------------------------------------------------------------- 商品

/**
 * 类目树节点（对齐后端 `CategoryVO`）。
 *
 * <p>⚠️ **不要把它和 `CategoryType` 搞混** —— 那是五品类枚举
 * （NORMAL/FRESH/SERVICE/VIRTUAL/CARD），挂在商品上、由平台硬编码，决定履约与合规
 * （冷链、不发货、iOS 可售规则）。这里的类目树是运营可维护的数据，决定归类与经营准入。
 * 两个维度正交，见 `docs/technical/类目树补齐方案.md`。
 *
 * <p>这个类型此前声明了一个后端根本不返回的 `type` 字段，并写着「仅两级」——
 * 而后端一直是三级。没人用它，所以错了很久也没暴露。
 */
export interface Category {
  /** 类目单号 */
  categoryNo: string;
  /** 上级类目单号。一级类目为空 */
  parentNo?: string | null;
  /** 1–3。**三级封顶** */
  level: number;
  /** 类目名（后端按 Accept-Language 下发已本地化文案） */
  name: string;
  /** 类目图标 URL。运营没配就是空串，端上按占位渲染 */
  icon?: string;
  /** 同级内的展示顺序，小的在前。运营在后台拖动排序改的就是它 */
  sort: number;
  /** 子类目。叶子是空数组而不是 undefined —— 端上少一次判空 */
  children: Category[];
}

/** 规格维度，例：{ name: "重量", options: ["约5斤", "约10斤"] } */
export interface SpecGroup {
  /** 规格维度名，如「重量」「包装」 */
  name: string;
  /** 该维度的可选值，如 `["约5斤", "约10斤"]` */
  options: string[];
  /**
   * 与 options 一一对应的模板编码。来自模板的选项有值，自由输入的为空。
   * 一期只写入不消费 —— 但不留位的话，二期做规格聚合要刷全部历史商品。
   */
  optionCodes?: (string | undefined)[];
  /** 该规格组来自哪个模板（便于「用的人多不多」这类平台侧统计） */
  templateNo?: string;
}

export interface Sku {
  /** SKU 单号。下单、库存、订单行都指向它，不是指向 goodsNo */
  skuNo: string;
  /**
   * 各规格维度上的取值，顺序与 Goods.specGroups 一一对应。
   * 单规格商品长度为 1；多规格（如 重量 × 包装）长度 >1。
   */
  optionValues: string[];
  /** 展示用拼接文案（后端下发，端上不自己拼，避免多语言分隔符差异） */
  spec: string;
  /** 售价（最小货币单位） */
  price: number;
  /** 划线价（最小货币单位）。为空表示不展示划线价 */
  originPrice?: number;
  /** 可售库存。下单时服务端二次校验，端上这个值只用于展示与预校验 */
  stock: number;
  /** FRESH 且按重计价：标称重量（克） */
  nominalGram?: number;
}

/** 预约可选时段（SERVICE + APPOINTMENT） */
export interface AppointmentSlot {
  /** YYYY-MM-DD（市场本地时区） */
  date: string;
  /** 当天各时段的余量。`time` 形如 `14:00`，`left` 为剩余可约数，0 表示约满 */
  times: { time: string; left: number }[];
}

/** 卡券属性（CARD） */
export interface CardSpec {
  /** 储值卡面值（最小货币单位）；次卡为空 */
  faceValueMinor?: number;
  /** 次卡总次数；储值卡为空 */
  timesTotal?: number;
  /** 有效期天数 */
  validDays: number;
}

/**
 * 促销：买 N 送 M。
 * 语义：购买数量达到 N 件，赠送 M 件 —— 用户**付 N 件的钱，收到 N+M 件**。
 * 赠品不进计价（价格为 0），只作为订单里的独立行存在，履约时随单发出。
 */
export interface Promotion {
  /** 促销类型。目前只有买 N 送 M 一种 */
  type: "BUY_N_GET_M";
  /** 购买件数门槛 N */
  buyN: number;
  /** 赠送件数 M */
  giftM: number;
  /** 赠品商品号；不填则赠同款 */
  giftGoodsNo?: string;
  /** 赠品展示名（后端下发已本地化） */
  giftTitle?: string;
}

/** 虚拟商品属性（VIRTUAL） */
export interface VirtualSpec {
  /** 发放说明，如「支付后 1 分钟内短信发码」 */
  deliverDesc: string;
}

export interface Goods {
  /** 商品单号 */
  goodsNo: string;
  /** 商品标题 */
  title: string;
  /** 副标题/卖点一句话 */
  subtitle: string;
  /** 封面图 URL。列表页用这一张 */
  cover: string;
  /** 详情轮播图 URL 列表 */
  images: string[];
  /** 商品形态，与所属类目的 type 一致。决定详情页用哪套字段 */
  type: CategoryType;
  /** 所属类目 */
  categoryNo: string;
  /** 所属商家 —— 商品与服务都要展示商家信息 */
  merchant: MerchantBrief;
  /** 本商品的评分与评价数（区别于商家整体评分） */
  rating?: number;
  /** 本商品的评价条数 */
  ratingCount?: number;
  /** 展示价（最小货币单位），取各 SKU 最低价 */
  price: number;
  /** 划线价（最小货币单位） */
  originPrice?: number;
  /** 支持的履约方式。**数组**：同一商品可以既自提又快递，下单时由用户选 */
  fulfillments: FulfillmentType[];
  /** 规格维度定义；单规格商品也有一组 */
  specGroups: SpecGroup[];
  /** SKU 列表。单规格商品也有且仅有一条 */
  skus: Sku[];
  /** 累计销量，展示用 */
  sales: number;
  /** FRESH：预售截单时间戳 */
  cutoffAt?: number;
  /** FRESH：预计到货描述 */
  arrivalDesc?: string;
  /** FRESH：是否按实称多退少补 */
  weighed?: boolean;
  /** FRESH：产地 */
  origin?: string;
  /** SERVICE：服务时长（分钟） */
  durationMin?: number;
  /** SERVICE：可核销门店 */
  storeName?: string;
  /** SERVICE + APPOINTMENT：可预约时段 */
  slots?: AppointmentSlot[];
  /** CARD */
  card?: CardSpec;
  /** VIRTUAL */
  virtual?: VirtualSpec;
  /** 促销（一期只有买 N 送 M） */
  promotions?: Promotion[];
  /** 商家为本商品开放的拼团档：够 minCount 人享 price。不配则本商品不能发起团 */
  groupBuy?: { minCount: number; price: number };
  /** 本商品每件赠送的积分。不同商品可以给不同积分，不配则按成交额比例默认发放 */
  points?: number;
  /** 每人限购，0 = 不限 */
  limitPerUser: number;
  /** 是否在售。下架后详情页仍可访问（历史订单要点得进去），但不可下单 */
  onSale: boolean;
  /**
   * 审核与在售状态（**只有商家侧 `/biz/goods` 下发**，C 端拿不到也不需要）。
   *
   * 为什么不能只看 `onSale`：新建和每次改动都会回到审核中，而那时
   * `onSale` 是 false —— 界面照着布尔值写就成了「已下架 + 上架按钮」，
   * 点下去后端必然拒（70003「商品还在审核中」）。**商家看到的是一个永远点不动的按钮**。
   *
   * ⚠️ 待审在这里是 `AUDITING`（后端 `prd_goods.audit_status` 的原值），
   * 而 {@link GoodsStatus} 用的是 `PENDING`（ops-web 的 SkuStatus 口径）——
   * 同一件事两个词，词典 §11 该收敛哪一个还没定。这里如实写后端发的那个。
   */
  status?: MerchantGoodsStatus;
  /**
   * 三语标题原文，**只有商家侧 `/biz/goods/{no}` 下发**。
   *
   * 编辑页按语言逐格填，而保存是整份覆盖 —— 拿不到原文就只能回填当前那一格，
   * 于是用中文改一次，英文与阿语就被清空了。**这个故障不报错**：
   * C 端缺译文时回落中文，看起来一切正常。
   */
  titleI18n?: Record<string, string>;
  /** 三语副标题原文，同 `titleI18n` */
  subtitleI18n?: Record<string, string>;
}

/**
 * 商家侧商品状态（`/biz/goods` 下发的四态）。
 *
 * ⚠️ 待审在这里是 `AUDITING`（后端 `prd_goods.audit_status` 的原值），
 * 而 {@link GoodsStatus} 用 `PENDING`（ops-web 的 SkuStatus 口径）——
 * **同一件事两个词**，词典 §11 该收敛哪一个还没定。这里如实写后端发的那个，
 * 收敛之前不要在端上做映射：映射会让「界面显示对了」掩盖住口径还没统一。
 */
export type MerchantGoodsStatus = "ON_SALE" | "OFF_SALE" | "AUDITING" | "REJECTED";

// ---------------------------------------------------------------- 购物车

export interface CartItem {
  /** 商品单号 */
  goodsNo: string;
  /** SKU 单号。购物车按 SKU 去重，不是按商品 */
  skuNo: string;
  /** 商品标题快照 */
  title: string;
  /** 封面图快照 */
  cover: string;
  /** 规格文案快照 */
  spec: string;
  /** 加购时的单价（最小货币单位）。结算时以服务端最新价为准，不一致会提示 */
  price: number;
  /** 数量 */
  qty: number;
  /** 商品形态 */
  type: CategoryType;
  /** 用户选定的履约方式。跨履约方式的商品结算时会拆单 */
  fulfillment: FulfillmentType;
  /** 失效原因，如「已下架」「库存不足」。有值即不可勾选结算 */
  invalidReason?: string;
  /** 买赠自动带出的赠品件数（不计价） */
  giftQty?: number;
  /** 赠品说明，如「买 2 送 1」 */
  giftLabel?: string;
}

// ---------------------------------------------------------------- 订单


/* ────────────────────────────────────────────────────────────────────────────
 * 以下这些类型此前都是**内联在 interface 里的字面量联合** —— 值是对的，
 * 但没有单一声明处：对账工具扫不到、别处要用只能再抄一遍、改名时必漏。
 * `CATEGORY_TYPE` 出事前正是这个状态。
 *
 * 具名化的过程本身就暴露了三对**异名同义**（都是内联时看不见的）：
 *   · `feeMode` 与 ops-web 的 `PickupFeeMode` 同值
 *   · `subjectType`（风控）与 ops-web 的 `SubjectType` 同值
 *   · `type: "REFUND_ONLY" | "RETURN_REFUND"` 就是已有的 `AfterSaleType`
 * ──────────────────────────────────────────────────────────────────────────── */

/** 结算账户形态。个人 openid 收款 / 对公商户号收款（ADR-002 §5） */
export type SettleAccountType = "PERSONAL_OPENID" | "MERCHANT_ID";

/**
 * 支付**进件**状态（`MerchantPayment.applyStatus`）。
 *
 * ⚠️ 此前叫 `ApplyStatus`，与入驻审核的 `ApplyStatus` 同名不同义 ——
 * `ACTIVE`/`FROZEN` 两个值就是证据：审核不会有这两个态。
 */
export type PaymentApplyStatus = "NONE" | "APPLYING" | "ACTIVE" | "REJECTED" | "FROZEN";

/** 门店状态。READONLY = 已停用（不再接新单，已有单照常履约） */
export type StoreStatus = "ACTIVE" | "READONLY";

/** 员工账号状态 */
export type StaffStatus = "ACTIVE" | "DISABLED";

/**
 * 门店角色（B 端）。**一人一店可持有多个**，权限取并集。
 *
 * 分界线画在「出错的后果」上，而不是功能重要性 ——
 * 履约被拆成三种活，因为它们面对的对象不同：分拣对货、核销对顾客、发货对收件人。
 * 拆开之后理货员与配送员才装得下。判断依据见
 * `docs/requirements/三端角色权限功能对齐清单.md` §4。
 *
 * ⚠️ `CS` 与运营端的 `Role.CS` **同名不同义**：这个是商家自己雇的客服（只管自己店），
 * 那个是平台客服（跨商家、能仲裁）。
 *
 * 老板不在这里 —— 他是 `isOwner`，不需要逐店授权。
 */
export type StaffRole =
  | "MANAGER" // 店长：除结算与员工管理外的经营权限
  | "CLERK" // 店员：收银台 —— 核销、到货分拣、发货、改库存
  | "PICKER" // 理货员：只到货分拣，不核销（那要面对顾客）、不看金额
  | "COURIER" // 配送员：只自送，看不到金额与全店订单
  | "CS"; // 线上客服：回评价、处理售后、看单。不碰货、不碰钱

/**
 * 我在**当前门店**能做什么（`GET /biz/context`）。B 端每次会话恢复与切门店后都要重取。
 *
 * @property merchantNo 我所属的主体
 * @property currentStoreNo 当前门店。切门店由 `X-Store-No` 决定，不是本地推的
 * @property owner 是否主体属主。属主的 `perms` 是 `["*"]`
 * @property storeNos 我能碰数据的门店。老板是主体全部，员工只有被授权的那几家
 * @property pickupNos 我能核销的自提点，按门店算出来
 * @property groupNos 我发起的团
 * @property staffRoles 我在当前门店持有的角色，**只用于展示**。判权一律看 perms。
 *   比 {@link StaffRole} 多一个 `OWNER`：那个类型是「可以授予的角色」（授权面板用），
 *   而属主的身份不来自逐店授权，是 `mch_account.is_owner`。同名不同集合，
 *   混用的表现是授权面板里冒出一个点了会报错的「老板」选项
 * @property perms 当前门店上的权限码并集。空数组 = 这家店没给我任何角色 = 零权限
 */
export interface BizScope {
  /** 当前用户所属的商家主体 */
  merchantNo: string;
  /**
   * 当前选中的门店。
   *
   * **切门店后要重新拉这个接口** —— 角色跟着门店走：同一个人可能在 A 店是店长、
   * B 店是店员，权限跟着变。不重拉的话，界面按上一家店的权限渲染。
   */
  currentStoreNo: string;
  /** 是不是老板（主体所有者）。老板不受门店角色限制 */
  owner: boolean;
  /** 我能管哪些门店。空 = 只能看当前这家 */
  storeNos: string[];
  /** 我能核销哪些自提点 */
  pickupNos: string[];
  /** 我发起了哪些团。**第三个作用域**，与门店 / 自提点正交 */
  groupNos: string[];
  /** 我在**当前门店**持有的角色（可多个）。老板恒为 `["OWNER"]` */
  staffRoles: (StaffRole | "OWNER")[];
  /**
   * 这些角色合起来的权限码，**已取并集**（老板是 `["*"]`）。
   *
   * 端上照它裁剪入口，**不要自己按角色再推一遍** —— 两处各推一次迟早分岔，
   * 而分岔的表现是「看得见但点了报错」。
   */
  perms: string[];
}

/**
 * 结算流水状态。**与后端 `StlBill` 逐字一致**。
 *
 * > 2026-08-11 收敛：这里此前是 `PENDING/PARTIAL/DONE/EXPIRED` —— 一套后端从来没有过的词，
 * > 描述的是「周期账单」而不是「按子单的分账流水」。内联时对所有工具不可见，
 * > 具名化之后才暴露出来（见 enum-registry 里这条的 note）。
 *
 * - `PENDING` 待分账 · `SPLITTING` 分账中 · `SPLIT` 已分账
 * - `RETRYING` 失败重试中 · `MANUAL` 转人工（重试用尽，**不会自动再动钱**）
 * - `REVERSED` 已回退（退款前必须先回退分账）
 */
export type SettleBillStatus =
  | "PENDING"
  | "SPLITTING"
  | "SPLIT"
  | "RETRYING"
  | "MANUAL"
  | "REVERSED";

/**
 * 入驻申请的审核状态。与库 `mch_entity_apply.status` 逐字一致。
 * ⚠️ 与 {@link MerchantStatus}（B 端「我能不能干活」的合并视图）不是一回事。
 */
export type MerchantApplyReviewStatus = "PENDING" | "REVIEWING" | "APPROVED" | "REJECTED";

/** 自提点承接方类型。与 {@link PickupPointType} 不同：那个说「是什么点」，这个说「谁在承接」 */
export type PickupOwnerType = "MERCHANT" | "USER" | "PLATFORM";

/** 自提点作用域：常驻 / 团粒度（一团一销） */
export type PickupScope = "PERMANENT" | "GROUP_INSTANCE";

/** 自提点计费方式。**与 ops-web 的 `PickupFeeMode` 同值** —— 费率线下逐点协商，故两种都留 */
export type PickupFeeMode = "NONE" | "PER_ITEM" | "RATE";

/** 到货异常类型：缺件 / 破损。B 端到货登记时上报（ADR-005 履约链路） */
export type ArrivalIssueKind = "SHORTAGE" | "DAMAGE";

/** 规格模板归属：平台统一维护 / 商家自存 */
export type SpecTemplateScope = "PLATFORM" | "MERCHANT";

/** 流量来源。**与 ops-web 的 `TrafficSource` 同名** —— 那边多 INVITE/CHANNEL 两个值（已标 MERGE） */
export type TrafficSource = "MERCHANT_OWNED" | "PLATFORM";

/**
 * 订单状态。**这是后端真实下发的取值**，不是端上想象的流程。
 *
 * ⚠️ 曾经这里还有一个 `PREPARING`（备货中）—— 那是 mock 里多出来的一步，
 * 后端从付款直接到 `PAID`（待发货），没有独立的备货态。
 * 端上按一个后端永远不会给的值去筛，筛出来的就是空列表，而且不报错。
 *
 * ⚠️ 也曾有一个 `REFUNDING` —— 那是**售后单**的状态（{@link AfterSaleStatus}），
 * 不是订单的。订单只会到 `REFUNDED`。这个混淆的代价是两端的「售后」页签：
 * 它们按 `order.status === "REFUNDING"` 筛，而后端从不下发，
 * **b 端「售后中」页签与工作台售后待办数因此恒为空 / 恒为 0**。
 *
 * 一个订单可以「已完成」的同时挂着一张处理中的售后单 —— 两者并存，
 * 做成互斥的状态就必须二选一，而那是表达不了的。售后要从 `/mp/after-sale`
 * 与 `/biz/after-sale` 单独查。
 */
export type OrderStatus =
  | "WAIT_PAY"
  | "PAID"
  | "ARRIVED"
  | "SHIPPED"
  | "COMPLETED"
  | "CANCELLED"
  | "REFUNDED";

/**
 * 订单预览的返回。**后端返的是完整 OrderVO，这里只声明端上要用的那部分** ——
 * 预览页只关心金额与行，声明全套会让每次后端加字段都得改端上类型。
 */
export interface OrderPreview {
  /**
   * 试算出来的金额。**页面显示的应付必须等于这里的 payableMinor** ——
   * 端上不要自己再算一遍：优惠叠加顺序（先活动后券）在后端，
   * 两处各算一次必然算出两个数，而用户看到的是「确认页 46.40、付款 51.40」。
   */
  amount: OrderAmount;
  /** 试算出来的订单行，含赠品行（价格 0）。数量与下单后落库的一致 */
  items: OrderItem[];
}

/**
 * 结算页的<b>能力提示</b>：这一车货能不能开票、能用哪些支付方式、额度还够不够。
 *
 * <p>与 {@link OrderPreview} 分开是有意的：preview 回答「多少钱」，
 * 这个回答「付得了吗、票拿得到吗」。
 *
 * <p>三件事一起给，是因为它们的共同后果都是<b>付款那一刻才炸</b>——
 * 小微没有 H5/App 支付方式（混合购物车整单付不了）、小微不能开票
 * （买完才发现补救不了）、额度用尽（通道直接拒收）。
 * 每一条单独看都像偶发故障，放在一起看才是同一件事：
 * 平台放弱主体进来了，而结算页还没告诉买家这意味着什么。
 */
export interface CheckoutCapability {
  /**
   * 整单可用的支付方式 = <b>各商家支持集合的交集</b>。
   *
   * 交集而非并集：一笔支付覆盖整单，有一家不支持就用不了。
   * <b>空数组 = 这一车货没有任何方式能付</b>，端上要拦在结算页 ——
   * 让他点下去只会得到一个说不清原因的「支付失败」。
   *
   * <b>null = 未配置</b>（一个商家都还没进件完）——端上<b>不要拦</b>。
   * 两者混成空数组的话，一个完全正常的订单会被拦死。
   */
  usablePayMethods: string[] | null;
  /** 车里有商家开不了票。**必须在付款前告诉用户**：买完才发现，平台补救不了 */
  anyNotInvoiceCapable: boolean;
  /** 逐商家的能力，端上据此在对应的商家分组上打标 */
  merchants: MerchantCapability[];
}

export interface MerchantCapability {
  /** 商家单号 */
  merchantNo: string;
  /** 商家名，展示用 */
  merchantName: string;
  /** 能否开票 */
  invoiceCapable: boolean;
  /** 该商家支持的支付方式；**空 = 未配置**（进件还没走完），不是「一种都不支持」 */
  payMethods: string[];
  /** 本期收款额度已用尽 —— 这家的货现在下不了单 */
  quotaExhausted: boolean;
  /** 加上本车这些货会超额 —— 还没用尽，但这一单过不去 */
  quotaWouldExceed: boolean;
}

export interface OrderItem {
  /** 商品单号 */
  goodsNo: string;
  /** 所属商家 —— 分账与「我买过的商家」都依赖它落在订单行上 */
  merchantNo: string;
  /** SKU 单号 */
  skuNo: string;
  /** 下单时的商品标题**快照**。商品后续改名不影响历史订单 */
  title: string;
  /** 封面图快照 */
  cover: string;
  /** 规格文案快照 */
  spec: string;
  /** 成交单价（最小货币单位）快照。改价不追溯已成交订单 */
  price: number;
  /** 数量 */
  qty: number;
  /** 商品形态 */
  type: CategoryType;
  /** FRESH 且按重计价：下单时的标称重量（克） */
  nominalGram?: number;
  /** 是否已实际称重。称重后按实重产生差价，见 `OrderAmount.weighAdjustMinor` */
  weighed?: boolean;
  /** 赠品行：价格为 0，不参与计价，履约时随单发出 */
  isGift?: boolean;
  /** 该商品每件赠送的积分 */
  points?: number;
}

export interface OrderAmount {
  /** 商品小计（最小货币单位），不含运费与优惠 */
  goodsMinor: number;
  /** 运费 */
  freightMinor: number;
  /** 优惠合计（券 + 活动），正数表示减掉多少 */
  discountMinor: number;
  /** 应付：`goodsMinor + freightMinor - discountMinor - pointsDeductMinor` */
  payableMinor: number;
  /** 实付。未支付时为 0；称重差价补退后与 payableMinor 可能不等 */
  paidMinor: number;
  /** 称重差价（正=补款 负=退款），仅 FRESH */
  weighAdjustMinor?: number;
  /** 积分抵扣的金额 */
  pointsDeductMinor: number;
  /** 本单使用的积分数 */
  pointsUsed: number;
  /** 本单可获得的积分（订单完成时才真正入账） */
  pointsEarn: number;
  /** 下单时的货币，订单一经创建即锁定，不随用户切市场变化 */
  currency: CurrencyCode;
}

/**
 * 收件人。下单时固化在子订单上，**不是用户当前的地址簿条目**。
 *
 * 三端共用：C 端订单详情、B 端配送/发货、平台端查单。
 */
export interface OrderReceiver {
  /** 收货人姓名。取不到时为空 —— 空就是空，不要回落成「顾客」 */
  name?: string;
  /** 脱敏程度由后端定，见 `Order.receiver` 的说明 */
  phone?: string;
  /** 省市区 + 详细，拼好的一行 */
  address?: string;
}

export interface Order {
  /** 订单单号 */
  orderNo: string;
  /** 订单状态。粗粒度；售后细节见 `afterSale` */
  status: OrderStatus;
  /** 履约方式，下单时锁定 */
  fulfillment: FulfillmentType;
  /** 订单行。含赠品行（`isGift`，价格为 0） */
  items: OrderItem[];
  /** 金额明细 */
  amount: OrderAmount;
  /** 自提码 / 核销码 */
  verifyCode?: string;
  /** VIRTUAL：兑换码；CARD：卡号 */
  redeemCode?: string;
  /** PICKUP：自提点单号 */
  pickupNo?: string;
  /** PICKUP：自提点名称快照 */
  pickupName?: string;
  /** EXPRESS：快递单号，发货后才有 */
  expressNo?: string;
  /** APPOINTMENT：预约开始时间戳 */
  appointmentAt?: number;
  /** 下单时间 */
  createdAt: number;
  /** 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 */
  payDeadlineAt?: number;
  /** 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 */
  timeline: OrderTimelineNode[];
  /** 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 */
  idempotencyKey?: string;
  /** 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 */
  buyerNickname?: string;
  /**
   * 收件人（下单时的**快照**，自提单没有）。
   *
   * 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了，
   * 而货已经按旧地址在路上。
   *
   * ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号
   * （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。
   * 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。
   */
  receiver?: OrderReceiver;
  /** 已评价 */
  reviewed?: boolean;
  /** 积分是否已发放（幂等标记，防止重复核销重复发分） */
  pointsGranted?: boolean;
  /**
   * 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户
   * 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。
   */
  trafficSource?: TrafficSource;
  /** 参与的团。邻里自提的核销作用域就靠它裁剪（E16） */
  groupNo?: string;
  /** 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 */
  afterSale?: AfterSale;
  /**
   * 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。
   * 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。
   */
  merchantNo?: string;
  /** 商家名快照 */
  merchantName?: string;
  /**
   * 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。
   * 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。
   */
  payGroupNo?: string;
}

export interface OrderTimelineNode {
  /** 流转到的状态 */
  status: OrderStatus;
  /** 展示文案，如「已到货，请到自提点取货」。后端下发已本地化 */
  label: string;
  /** 发生时间 */
  at: number;
}

// ---------------------------------------------------------------- 卡包

export interface UserCard {
  /** 卡号。核销时出示的就是它 */
  cardNo: string;
  /** 购买时的商品单号 */
  goodsNo: string;
  /** 卡名快照 */
  title: string;
  /** 卡面图 */
  cover: string;
  /** 储值卡剩余额度（最小货币单位） */
  balanceMinor?: number;
  /** 次卡剩余次数 */
  timesLeft?: number;
  /** 过期时间。过期后余额/次数作废 */
  expireAt: number;
  /** 购卡时锁定的货币，不随用户切市场变化 */
  currency: CurrencyCode;
}

// ---------------------------------------------------------------- 营销

export interface Coupon {
  /** 券单号 */
  couponNo: string;
  /** 券名，如「满 50 减 5」 */
  name: string;
  /** 使用门槛（最小货币单位）。0 表示无门槛 */
  thresholdMinor: number;
  /** 抵扣金额（最小货币单位） */
  discountMinor: number;
  /** 过期时间 */
  expireAt: number;
  /** 当前用户是否已领取。列表页据此显示「领取」还是「去使用」 */
  received: boolean;
  /** 适用范围文案，如「仅限张记生鲜」。展示用，实际校验在服务端 */
  scopeDesc: string;
}

/**
 * 邻里求团：**需求先于供给**。
 *
 * 与「商家团」是两条完全不同的线，刻意不复用一个模型：
 *   商家团 —— 商品已上架、价格已定、库存已备，用户只是参与；适合生鲜日用这类高频标品。
 *   求团   —— 发起时**商品还不存在，甚至没有商家**，用户只有一句「想买儿童床垫」；
 *            适合床垫、校服、家电这类低频高单价、有议价空间的非标品。
 *
 * 关键约束：**意向 ≠ 订单**。求团阶段不收钱、不锁库存 —— 商品还不存在时收钱是给自己找麻烦。
 * 只有发起人选定报价、转成正式商家团之后，才进入交易链路。
 */
export type GroupRequestStatus =
  /** 刚发起，等邻居 +1 */
  | "OPEN"
  /** 已有商家报价 */
  | "QUOTING"
  /** 发起人已选定报价，已转成正式团 */
  | "MATCHED"
  | "CLOSED"
  | "EXPIRED";

/** 一次改价的留痕 */
export interface QuoteRevision {
  /** 改价后的单价（最小货币单位） */
  priceMinor: number;
  /** 改价时间 */
  at: number;
}

/**
 * 商家对某个需求单的报价。一个需求单可多家报价，由发起人挑。
 *
 * **报价不做事前审核，防加价靠三层机制**（见 docs/technical/ADR/ADR-003）：
 *   1. 锁价 —— 被选定后 `locked`，下单一律用快照价，系统层面加不了价
 *   2. 公示 —— 每次改价都写进 `revisions` 并对所有邻居可见，谁涨价谁被看见
 *   3. 信用 —— 选定后不履约计入商家 `breachCount` 与评分，累计则限制报价资格
 */
export interface Quote {
  /** 报价单号 */
  quoteNo: string;
  /** 报价商家。`breachCount` 会在报价卡上公示 —— 事后信用替代事前审核 */
  merchant: MerchantBrief;
  /** 报价单价 */
  priceMinor: number;
  /** 起订量：低于这个数商家不接 */
  minCount: number;
  /** 报价说明：规格、材质、是否含安装等 */
  desc: string;
  /** 报价有效期。过期后不可被选定 —— 报价不能无限期挂着 */
  validUntil: number;
  /** 报价时间 */
  createdAt: number;
  /** 是否被发起人选定。一个需求单只有一条为 true */
  chosen: boolean;
  /** 改价历史，公示给所有人。空数组 = 从未改过价 */
  revisions: QuoteRevision[];
  /** 已锁价：选定后为 true，此后价格不可变 */
  locked: boolean;
}

export interface GroupRequest {
  /** 求团需求单号 */
  requestNo: string;
  /** 发起人昵称 */
  initiatorNickname: string;
  /** 发起人头像 */
  initiatorAvatar: string;
  /** 需求的范围仍是自提点/小区 —— 邻里的意义就在于此 */
  pickupNo: string;
  /** 自提点名称快照 */
  pickupName: string;
  /** 需求标题，如「想团儿童床垫」。**此时商品还不存在**，只有这句话 */
  title: string;
  /** 需求详述：尺寸、材质、用途等，供商家判断能不能接 */
  desc: string;
  /** 参考图。发起人拍的样图或截图 */
  images: string[];
  /** 发起人期望的数量 */
  expectQty: number;
  /** 心理价位（可不填） */
  budgetMinor?: number;
  /** 需求单状态 */
  status: GroupRequestStatus;
  /** 表达意向的邻居数（含发起人）—— 不是订单数 */
  interestedCount: number;
  /** 当前用户是否已 +1。决定按钮显示「我也要」还是「已加入」 */
  interested: boolean;
  /** +1 的邻居头像墙。只取前若干个用于展示，不是全量 */
  neighbours: { avatar: string; nickname: string }[];
  /** 收到的报价。一个需求单可多家报价，由发起人挑 */
  quotes: Quote[];
  /** 发起时间 */
  createdAt: number;
  /** 需求单过期时间。过期即 EXPIRED，不再接受报价 */
  expireAt: number;
  /** MATCHED 后指向生成的正式团 */
  groupNo?: string;
  /**
   * 选定的报价快照。转成正式团后下单用这个价，**不读商家当前价** ——
   * 这是防加价最硬的一层：加价在技术上做不到，不需要审核。
   */
  lockedPriceMinor?: number;
  /** 我（+1 的邻居）是否已二次确认下单。+1 不等于承诺，必须各自确认 */
  confirmed?: boolean;
  /** 已确认下单的人数 */
  confirmedCount?: number;
}

/**
 * 商家团 —— 商家在已上架商品上开的团，用户可参与或自己开一桌。
 * 定位：**只是一种活动**，不是平台核心机制。所以单档成团，不做阶梯价。，不是运营配置的活动位。
 * 成团单位是自提点（拼的是一车送到一个点的成本），单档成团，不做阶梯。
 */
export interface GroupBuy {
  /** 团单号 */
  groupNo: string;
  /** 开团的商品 */
  goodsNo: string;
  /** 商品标题快照 */
  title: string;
  /** 商品封面快照 */
  cover: string;
  /** 供货商家 */
  merchant: MerchantBrief;
  /** 发起人昵称 */
  initiatorNickname: string;
  /** 发起人头像 */
  initiatorAvatar: string;
  /** ★ 成团范围：**成团单位是自提点**，拼的是一车送到一个点的成本 */
  pickupNo: string;
  /** 自提点名称快照 */
  pickupName: string;
  /** 不成团时的价格（降级发货用此价） */
  basePrice: number;
  /** 成团价 */
  groupPrice: number;
  /** 成团所需人数 */
  minCount: number;
  /** 已参团人数 */
  joinedCount: number;
  /** 已成团 */
  reached: boolean;
  /** 还差几人 */
  need: number;
  /** 截止时间：发起后 validHours 与商品截单时间取更早 */
  expireAt: number;
  /** 已参团的人及各自件数，展示用 */
  members: { avatar: string; nickname: string; qty: number }[];
  /** 当前用户是否已参团 */
  joined: boolean;
  /**
   * 邻里自提点（C-GB-06）：发起人勾选「送到我家」时有值。
   * 参团者在这里取货，发起人负责签收与逐单核销 —— **零报酬**（ADR-005 §3）。
   */
  neighborPickup?: PickupPoint;
  /** 我是不是这个团的发起人 —— 决定是否显示轻核销入口 */
  isOwner?: boolean;
}

// ---------------------------------------------------------------- 团长


/** 分拣单的一行：按商品汇总，团长照着这个点数 */
export interface PickingRow {
  /** 商品单号 */
  goodsNo: string;
  /** SKU 单号。分拣按 SKU 汇总，不是按商品 */
  skuNo: string;
  /** 商品标题 */
  title: string;
  /** 封面图，照着点数时用来认货 */
  cover: string;
  /** 规格文案 */
  spec: string;
  /** 该 SKU 在本自提点的总件数（含赠品） */
  totalQty: number;
  /** 谁要几件 */
  buyers: { nickname: string; qty: number; orderNo: string }[];
}

// ---------------------------------------------------------------- 登录

/**
 * 登录方式。
 * · WX_MINI  小程序静默登录（只拿 openid，拿不到手机号）
 * · WX_PHONE 小程序一键取手机号（推荐：一次授权直接拿到号，省掉短信）
 * · WX_OPEN  App 微信开放平台
 * · APPLE    Apple 登录（iOS 上架硬要求）
 * · PHONE_OTP 手机号 + 短信验证码（全端兜底，也是商家账号的主标识）
 */
export type GrantType = "WX_MINI" | "WX_PHONE" | "WX_OPEN" | "PHONE_OTP" | "APPLE";

export interface LoginReq {
  /** 登录方式，决定 principal / credential 各放什么 */
  grantType: GrantType;
  /** 凭证主体：`WX_*` 放 wx.login 的 code；`PHONE_OTP` 放手机号；`APPLE` 放 identityToken */
  principal: string;
  /** 凭证副本：仅 `PHONE_OTP` 需要，放短信验证码 */
  credential?: string;
  /** 邀请人。从邀请链接进入时带上，用于拉新归因 */
  inviterNo?: string;
  /** 从店铺码/店铺分享进入时带上，用于进店归因与费率分档（ADR-004 §5.4） */
  merchantNo?: string;
  /** 是否已勾选同意用户协议与隐私政策 —— 注册的合规前置，服务端要留痕 */
  agreed?: boolean;
}

export interface LoginResp {
  /** 访问令牌。后续请求放 `Authorization: Bearer <token>` */
  token: string;
  /** 登录用户档案 */
  user: User;
}

// ================================================================ B 端（商家端）
// 说明：B 端复用 C 端的 Goods / Order / Review / Merchant 等主类型，
// 只在此追加「经营侧独有」的类型。两端共享同一份定义，避免契约漂移。

/**
 * 商家在 B 端的**综合状态**：既要表达「还没入驻成功」，也要表达「已经在经营」。
 *
 * ⚠️ 它是一个**展示用的合并视图**，底下是两条互不相干的生命周期：
 *   · 审核（`MerchantApplyStatus`）—— 商家还不存在时的事，归 `usr_merchant_apply`
 *   · 经营（ACTIVE / SUSPENDED）—— 商家已存在之后的事，归 `usr_merchant.status`
 *
 * B 端首页要在一个地方回答「我现在能不能做生意」，所以合并；
 * 但**库里绝不能合并** —— 一旦合并，「驳回一份申请」和「封禁一家店」就共用取值，
 * 而这两件事的操作人、审计口径、可逆性全都不同。
 */
export type MerchantStatus =
  | "NONE" // 未申请
  | "APPLYING" // 已提交，待审核（对应申请单 PENDING）
  | "REVIEWING" // 已受理，客服正在看 —— 让商家知道「有人在看」
  | "REJECTED" // 驳回，可补料重提
  | "ACTIVE" // 正常经营
  | "SUSPENDED"; // 被封禁或被冻结 —— 见下方「为什么没有 FROZEN」

/*
 * **为什么这里没有 FROZEN**（库里 mch_entity.status 是 ACTIVE/SUSPENDED/FROZEN）：
 * 后端在下发这一层就把 FROZEN 折叠进了 SUSPENDED（BizMerchantController.bizStatus），
 * 因为冻结与封禁对「我现在能不能干活」的答案一样。
 *
 * 补一个 FROZEN 进来是错的 —— 它永远不会被下发，只会变成一个筛不出东西的死分支。
 * 映射有测试：backend/.../portal/biz/MerchantStatusMappingTest.java
 */



/** 商家分层。为「流量起来后引入大商家」预留，一期只用 SMALL（ADR-004 §7） */
export type MerchantTier = "SMALL" | "MEDIUM" | "LARGE";

/** 登录后的商家会话 */
export interface MerchantProfile {
  /** 商家单号 */
  merchantNo: string;
  /** 店铺名 */
  name: string;
  /** 店铺 logo */
  logo: string;
  /** 入驻审核状态。非 ACTIVE 时 B 端只能看到入驻流程页 */
  status: MerchantStatus;
  /** 主体类型 */
  subject: MerchantSubject;
  /** 商家分层。一期恒为 SMALL */
  tier: MerchantTier;
  /** 登录手机号，也是商家账号的主标识 */
  phone: string;
  /** 是否承接自提点 —— 决定 B 端是否出现「履约台」入口（ADR-005） */
  isPickupPoint: boolean;
  /** 承接的自提点单号。`isPickupPoint=true` 时有值 */
  pickupNo?: string;
  /** 驳回原因，status=REJECTED 时有值 */
  rejectReason?: string;
  /** 本次会话的登录方式。第三方登录且 phone 为空时，要引导补绑手机号 */
  loginBy?: GrantType;
}

export interface MerchantLoginResp {
  /** 访问令牌。**商家池与 C 端用户池是两套账号**，token 不通用 */
  token: string;
  /** 商家档案 */
  merchant: MerchantProfile;
}

export interface MerchantApplyReq {
  /** 拟用店铺名 */
  name: string;
  /** 主体类型。个人 → 个体户 → 企业，门槛前低后高 */
  subject: MerchantSubject;
  /** 联系人姓名。审核要打电话找人，只有号码没有姓名不合适 */
  contactName: string;
  /** 联系手机号 */
  contactPhone: string;
  /** 主营类目 */
  category: string;
  /** 店铺简介 */
  desc: string;
  /** 承接自提点：小店既是供给方也是取货点（ADR-005 type=STORE） */
  asPickupPoint?: boolean;
  /**
   * 期望经营范围（ADR-009）。申请时可空，<b>审核通过时必须确定</b> ——
   * 否则商家上着架却对谁都不可见，且没有任何报错。
   */
  serviceScope?: ServiceScope;
  /** 期望覆盖的社区。scope=COMMUNITY 时审核通过必须非空 */
  communityNos?: string[];
  /**
   * 资质图片（营业执照/身份证）。**选填** —— 一期 EDI 不强制。
   *
   * 与下面的结算账户一样，属于**分账主体开户**而不是入驻申请本身（ADR-002）：
   * `usr_merchant_payment` 是独立一张表、有自己的 `apply_status`，就是这个道理。
   * 申请时能传就传，通过后在 B 端补也行 —— 逼一个还没通过审核的人先传营业执照，
   * 只会把人挡在门外。
   */
  licenses?: string[];
  /** 结算账户类型。真实账号由后端持有，C 端与 B 端都不回显（ADR-002 §5）。**选填**，同上 */
  settleAccountType?: SettleAccountType;
  /**
   * 行业（`sys_industry.industry`）。
   *
   * **它决定这家店能不能以小微主体进件** —— 微信的小微白名单是按行业给的，
   * 也是 `points_forced` 默认值的来源。
   *
   * 后端一直在收、库里一直有这一列，但契约没登记、端也没传，
   * 于是 `mch_entity.industry` 恒空：进件时才发现主体类型选错了，
   * 而那时商家已经开完店、上完架。
   */
  industry?: string;
}

/**
 * 平台主数据快照（`GET /common/master-data`）。
 *
 * 合成一个响应而不是三条接口，是因为它们在**同一屏上被同时用到**：
 * 「选行业 → 据此过滤可选主体 → 主体决定要不要传营业执照」。
 * 分三次请求会出现「行业回来了、主体还没回来」的中间态，
 * 而那个中间态里表单不知道该不该禁用某个选项。
 */
export interface MasterData {
  /** 可选行业。**决定能不能以小微主体进件**，也是 points_forced 默认值的来源 */
  industries: MasterDataIndustry[];
  /** 可选主体类型（法律形态）。决定资质要求与结算账户形态 */
  subjects: MasterDataSubject[];
  /** 可用支付通道与其能力位 */
  channels: MasterDataChannel[];
  /**
   * **这一期开放的经营范围档位**（`SERVICE_SCOPE` 的启用子集，运营在后台配）。
   *
   * 端上要照它渲染选项，**不要把三档写死**。写死的后果不是「多了个选项」：
   * 一期自营模式关掉了 `PLATFORM`，而 B 端照样把「全平台发货」摆在那里，
   * 商家点下去得到的是「当前不支持这个经营范围」—— 一个必被拒的选项，
   * 而他无从知道自己该选什么。2026-08-11 的端到端实测撞到过。
   *
   * 拿到 EDI 切平台模式时运营在后台放开，端上不发版就跟着变 ——
   * 这正是它下发而不是写死的理由。
   */
  serviceScopes: ServiceScope[];
}

export interface MasterDataIndustry {
  /** 行业码（`sys_industry.industry`），提交申请时回传的就是它 */
  industry: string;
  /** 展示名。**取服务端的**，不要在端上再维护一份翻译 */
  name: string;
  /** 该行业能否以小微主体进件。**false 时小微选项要禁用**，不是提交后才报错 */
  microAllowed: boolean;
}

export interface MasterDataSubject {
  /** 主体类型码 */
  subjectType: MerchantSubject;
  /** 展示名 */
  name: string;
  /** 要不要传营业执照 */
  needLicense: boolean;
  /** 是否受行业白名单管控（小微受管，其余不受） */
  industryGated: boolean;
  /** 该主体默认的结算账户形态：小微打个人，其余打对公 */
  settleAccountType: SettleAccountType;
}

export interface MasterDataChannel {
  /** 通道码（`sys_pay_channel.pay_channel`），如 WECHAT */
  payChannel: string;
  /** 展示名 */
  name: string;
  /** 通道是否可用。关掉时下单页不给这个支付方式，而不是点了才失败 */
  enabled: boolean;
  /** 该通道支持的支付方式，如 JSAPI / APP / H5 */
  payMethods: string[];
}

/**
 * 收款进件状态（每通道一条）。
 *
 * <p><b>它与入驻审核是两件事</b>：入驻过了店就能开、货能上架，
 * 但通道没批就收不了钱。合成一个「入驻进度」，商家问「我能收钱了吗」就没法回答。
 */
export interface PaymentApplyment {
  /** 通道码，如 WECHAT */
  payChannel: string;
  /** 通道展示名。取服务端的，端上不要再维护一份翻译 */
  channelName: string;
  /** NONE / APPLYING / ACTIVE / REJECTED / FROZEN */
  applyStatus: PaymentApplyStatus;
  /**
   * 这个通道现在能不能收钱。
   *
   * **照着它显示，不要自己去比 applyStatus** —— 比错的表现是
   * 「显示能收钱但收不了」，而这种错要到第一笔订单才暴露。
   */
  canReceiveMoney: boolean;
  /** 收款商户号业务键，通过后才有。门店挂收款号引用的就是它 */
  payMerchantNo?: string;
  /** 二级商户号掩码。完整号不回显 */
  subMchidMasked?: string;
  /** 结算账户形态：小微打个人（PERSONAL_OPENID），其余打对公（MERCHANT_ID） */
  settleAccountType?: SettleAccountType;
  /** 结算账号掩码。**明文永不回显**，包括给商家自己（ADR-002 §5） */
  settleAccountMasked?: string;
  /** 驳回原因。驳回时必有 —— 没有原因商家只能反复重提 */
  rejectReason?: string;
  /** 还缺哪些资料（settleAccount / licenses / settleAccountType）。空 = 资料齐了在等通道 */
  missing: string[];
  /** 提交进件的时间。没提交过为空 */
  appliedAt?: number;
  /** 通道开户完成的时间 —— 从这一刻起才真的能收钱 */
  activatedAt?: number;
  /**
   * 这条进件是**为哪家门店**做的；空 = 主体级默认号。
   *
   * 多门店商家会有多条「微信 · 已开通」，不显示门店就分不清哪条是哪家店 ——
   * 等于让他猜自己的钱打进了哪张卡。
   */
  storeNo?: string;
}

/**
 * 门店（商家侧管理用）。
 *
 * <p><b>门店与主体是关联不是归属</b>：换执照店照开。所以 `storeNo` 一旦生成就不再变 ——
 * 评价、订单、顾客的「我常逛的店」都挂在它上面。
 */
export interface Store {
  /** 门店号。一旦生成不再变 —— 换主体只换归属，不换它 */
  storeNo: string;
  /** 门店名 */
  name: string;
  /** 门店地址。顾客据此找到取货点，也是履约范围的锚点 */
  address?: string;
  /** 是否默认店。一个主体**恰好一家** —— 它是「找不到具体门店时去哪」的答案 */
  isDefault: boolean;
  /** ACTIVE 正常营业 / READONLY 已停用（不再接新单，已有单照常履约） */
  status: StoreStatus;
  /** 这家店用哪个收款号。**空 = 用主体的默认收款号**，不是"没配" */
  payMerchantNo?: string;
  /** 这家店现在能不能收钱。照它显示，别自己去比状态串 */
  payReady: boolean;
  /** 授权到这家店的员工数（不含老板）。0 表示只有老板能管这家店 */
  staffCount: number;
}

/**
 * 商家员工（B 端账号 + 他在各门店的角色）。
 *
 * <p>**逐店授权**：A 店店长可以同时是 B 店店员 —— 老店的店长去新店帮忙，
 * 但新店不归他管，这是小连锁的常态。
 */
export interface MerchantStaff {
  /** 商家账号号。**不叫 staffNo** —— 那个名字被平台运营占着，两者是不同的人 */
  mchAccountNo: string;
  /**
   * 姓名（老板自己写的，如「小张」）。**认人靠它** ——
   * 一列号码谁也分不清。为空时端上回落 `loginPhone`。
   */
  displayName?: string;
  /**
   * 登录手机号，**完整、不脱敏**。
   *
   * 它**就是这个员工的登录用户名**（手机号 + 验证码，没有密码）——
   * 老板要能核对「他用哪个号登录」、人换号时要能改，脱敏之后这两件事都做不了。
   */
  loginPhone: string;
  /** 老板。**不受门店授权限制**，他的店都归他管 */
  isOwner: boolean;
  /** ACTIVE / DISABLED */
  status: StaffStatus;
  /** 他在各门店的角色。老板为空 —— 不是"没授权"，是"不需要授权" */
  roles: StoreRole[];
}

/**
 * 一条员工与授权的变更记录（B-11.10.3）。
 *
 * **授权变更是权限扩散的唯一入口** —— 加人、停用、给角色、撤角色。
 * 别的动作都有业务单据兜底，唯独这几个此前做完就没了：
 * 三个月后问「谁把张三提成了店长」，库里只有一行当前状态。
 */
export interface StaffLog {
  /** 操作人手机号（脱敏）。取不到当时身份时为空 —— 空就是空，不写「系统」 */
  actor?: string;
  /** 被操作员工的手机号（脱敏） */
  targetName?: string;
  /** STAFF_ADD / STAFF_ENABLE / STAFF_DISABLE / ROLE_GRANT / ROLE_REVOKE */
  action: string;
  /** 涉及门店的名字。加人与启停为空 */
  storeName?: string;
  /** 涉及的角色码。加人与启停为空 */
  role?: StaffRole;
  /** 人能读的一句话，直接展示 */
  detail?: string;
  /** 发生时间，毫秒时间戳 */
  at: number;
}

/**
 * 一个角色：6 个平台预置（只读）+ 商家自定义（V71）。
 *
 * **权限码的中文说明由后端给**（`permLabels`），前端不抄一份 ——
 * 抄的那份迟早与权限码本身漂开，而漂开的表现是
 * 「界面写着能改库存，实际打不通」。
 */
export interface MerchantRole {
  /** 角色码。预置是 `OWNER`/`MANAGER`… ，自定义是生成的业务键 —— **别拿它给店主看** */
  roleCode: string;
  /** 显示名。预置角色也有 —— 别拿 `MANAGER` 直接给店主看 */
  name: string;
  /** 平台预置：**只读**，要改先「复制为自定义角色」 */
  builtin: boolean;
  /** 这个角色带的权限码。老板那行是 `["*"]`（全部），别按长度当权限数 */
  perms: string[];
  /** 与 `perms` 一一对应的中文短说明 */
  permLabels: string[];
  /** 几个人在用。删除按钮据此禁用，并且要显示出来 */
  usedBy: number;
}

/**
 * 自定义角色**可以勾的一个权限点**。
 *
 * 为什么不让端上「把预置角色的权限并起来」当选项：那个并集**少一条** ——
 * `biz:finance` 只有老板有，而老板那行是 `*`。于是后端明明收这个码，
 * 界面上却勾不到，看起来像功能没做。
 */
export interface PermOption {
  /** 权限码，如 `biz:stock`。**只用于提交，不展示** */
  code: string;
  /** 中文短说明，兜底用。端上自己有中/英/阿三份文案 */
  label: string;
}

export interface StoreRole {
  /** 哪家店 */
  storeNo: string;
  /** 门店名快照，列表直接显示，省一次查询 */
  storeName: string;
  /** MANAGER 店长 / CLERK 店员 */
  role: StaffRole;
}

/** 商品在商家侧的状态。C 端只看得到 ON_SALE */
/**
 * 商品状态。
 *
 * ⚠️ 待审用 `PENDING` 不用 `AUDITING` —— ops-web 的 `SkuStatus` 一直用
 * `PENDING`，同一件事两个词。词典 §11 的通用状态词表规定「已提交待处理」= `PENDING`。
 */
export type GoodsStatus = "ON_SALE" | "OFF_SALE" | "PENDING" | "REJECTED";

/** 商家自送规则（ADR-005 §5：不做骑手系统，只有范围与门槛） */
export interface DeliveryRule {
  /** 配送半径，米 */
  radius: number;
  /** 起送价，最小货币单位 */
  minOrderMinor: number;
  /** 配送费，最小货币单位 */
  feeMinor: number;
  /** 免配送费门槛，最小货币单位；0 表示不免 */
  freeThresholdMinor: number;
}

/**
 * 结算流水。**一个子订单一行**（ADR-002 §5），不是周期账单。
 *
 * > 2026-08-11 更正：这个类型此前描述的是一套「周期账单」（`billNo` / `periodStart`
 * > / `orderCount` / `settledMinor`），而后端 `/biz/settle/bills` 从来返回的都是
 * > 按子单一行的分账流水。**字段一个都对不上**，页面靠 mock 才看起来是好的 ——
 * > 连真后端会整片 undefined。与本轮反复撞到的「单看任一端都完整，断在两端之间」同形状。
 */
export interface SettleBill {
  /** 结算单号 */
  settleNo: string;
  /** 对应的子订单号 —— 分账以它为单位 */
  subOrderNo: string;
  /** 所属主单号 */
  orderNo: string;
  /** 主体号 */
  merchantNo: string;
  /** 结算基数（分）= 用户实付 + 平台补贴。**平台出资的优惠要补回给商家** */
  grossMinor: number;
  /** 平台佣金（分） */
  commissionMinor: number;
  /** 自提点履约服务费（分）。供货方付、承接方收，两个角色都是自己时账面抵消 */
  serviceFeeMinor: number;
  /** 商家实得（分）= 基数 − 佣金 − 服务费 */
  netMinor: number;
  /** 客流来源：MERCHANT_OWNED 自带客流（零佣金）/ PLATFORM */
  trafficSource?: string;
  /** 佣金费率快照（万分比）。费率会变，历史账不跟着变 */
  commissionRate: number;
  /** PENDING / SPLIT / RETRYING / MANUAL / REVERSED */
  status: SettleBillStatus;
  /** 生成时间 */
  createdAt: number;
  /** 分账完成时间；没分完为空 */
  splitAt?: number;
  /**
   * 这笔钱是**哪家店**挣的（统计维度）。空 = 存量主体级流水。
   *
   * 它**不决定钱打给谁** —— 打给谁看 `payMerchantNo`。
   * 两家店可以共用一个收款号（合并结算），也可以各配各的（分开结算）。
   */
  storeNo?: string;
  /** 这笔钱打给**哪个收款号**（结算维度，生成时快照）。空 = 当时进件还没走完 */
  payMerchantNo?: string;
}

/** 工作台待办。**数字即入口** —— 商家打开 App 只想知道「有几件事要我做」 */
export interface MerchantTodo {
  /** 待发货单数（EXPRESS 履约） */
  toShip: number;
  /** 待自送单数（商家自送履约） */
  toDeliver: number;
  /**
   * 待备货单数（自提单已付款，货还没送到自提点）。**按门店算**，这是供货方的活。
   *
   * 与 {@link toPick} 是同一批单的两头，**两个数不相等**：
   * 买家常常选别家的自提点。`toPick` 按自提点算（我要在点上分多少），
   * 这一个按门店算（我要送出去多少）。
   */
  toStock: number;
  /** 待核销单数（自提到货、买家还没来取） */
  toVerify: number;
  /** 待分拣单数（到货后按商品汇总点数） */
  toPick: number;
  /** 待处理售后单数 */
  afterSale: number;
  /** 待回复的评价数 */
  toReply: number;
  /** 可报价的求团需求数 */
  quotable: number;
}

export interface MerchantStats {
  /** 今日订单数（自然日，按市场本地时区切分） */
  todayOrders: number;
  /** 今日成交额（最小货币单位） */
  todayGmvMinor: number;
  /** 本月订单数 */
  monthOrders: number;
  /** 本月成交额（最小货币单位） */
  monthGmvMinor: number;
  /** 统计口径的币种 */
  currency: CurrencyCode;
  /** 店铺综合评分，0–5 */
  rating: number;
  /** 参与评分的评价条数 */
  ratingCount: number;
  /** 自带客流占比（trafficSource=MERCHANT_OWNED），决定费率档（ADR-004 §6） */
  ownedTrafficRate: number;
}

/**
 * 店铺门面（B-11.2 店铺装修 → C 端门店主页的数据源）。
 * 与 Merchant 分开：Merchant 是平台建档的商家主数据（名称/资质/评分，商家改不了），
 * 这里是**店主自己能改的门面内容**。混在一起的话，改公告要走审核就荒谬了。
 */
export interface StoreProfile {
  /** 店铺公告：「今日到货」「今天有土鸡蛋」，店主自发（C-ST-04） */
  announcement: string;
  /** 营业时间文案，店主自填 */
  openHours: string;
  /** 店铺地址，店主自填 */
  address: string;
  /** 主推商品，按顺序展示在门店主页首屏 */
  featured: string[];
  /**
   * 经营范围（B 端自选）。**决定这家店的货在 C 端能被谁看到** ——
   * 选错不是展示问题：选大了会卖到送不到的地方（下单后提不了货 → 退款），
   * 选小了则整片小区的人都搜不到这家店。所以 B 端要给出后果说明，不能只给三个单选。
   */
  serviceScope: ServiceScope;
  /** scope=COMMUNITY 时覆盖的社区。空表示还没谈下任何小区，此时 C 端一律不可见 */
  serviceCommunityNos: string[];
  /** scope=CITY 时覆盖的城市 */
  serviceCityCode?: string;
  /**
   * 履约能力（ADR-013 阶段二）。**只说「怎么送到你手上」**，送得到哪儿看 {@link serviceAreas}。
   *
   * 与上面两个 `@deprecated` 字段的关系：新旧两套并存期间，端上**只传一套** ——
   * 传了 `serviceAreas` 就走新模型，后端不再看 `serviceScope`。
   */
  fulfillmentReach?: FulfillmentReach;
  /**
   * 地理覆盖项，可跨粒度组合（三个小区 + 一个区）。
   *
   * **空的含义由 `fulfillmentReach` 决定**，这是这个字段最容易踩的地方：
   * PICKUP 空 = 谁也看不到（没配自提点就没法履约）；
   * ONSITE / SHIPPING 空 = 不限。同一个空数组两种意思，所以别拿它判「有没有设置过」。
   */
  serviceAreas?: ServiceArea[];
}

/** 一条地理覆盖项。名字由后端拼好下发 —— 端上只拿到 330106 的话，要么显示一串数字，要么自己再查一次 */
export interface ServiceArea {
  /** 粒度：社区 / 街道 / 区县 / 城市。**可跨粒度组合** —— 三个小区 + 一个区是四条 */
  level: AreaLevel;
  /** level=COMMUNITY 时是社区号，否则是区划码 */
  refCode: string;
  /** 展示名。区级以上是「浙江省 / 杭州市 / 西湖区」整条路径 —— 光一个「西湖区」全国有好几个，商家分不出删哪条 */
  name: string;
  /**
   * `ACTIVE` 已生效 / `PENDING` 待运营审核。
   *
   * 勾已有社区自助生效；勾区、街道要审 —— 一家菜摊声称覆盖整个西湖区，
   * 影响面差一个量级（ADR-013 §4.2）。**端上必须把待审标出来**：
   * 待审的不参与展开，商家看着它在清单里却一个订单也不来，
   * 而这是他自己永远查不出来的那类故障。
   */
  status?: AreaStatus;
}

/**
 * 商家提报的新社区（ADR-013 阶段三）。
 *
 * 提报**不等于**社区已存在：审过之后平台才建出来，`communityNo` 这时才有值。
 * 端上别拿它去当社区用 —— 待审的小区不在任何选点列表里。
 */
export interface CommunityApply {
  /** 提报单业务键 */
  applyNo: string;
  /** 提报的商家 */
  merchantNo: string;
  /** 商家名。运营看着一串 M20260811… 判断不了任何事 */
  merchantName: string;
  /** 小区名，商家填 */
  name: string;
  /** 地址。运营靠它判断这是不是已有社区的另一个叫法 —— 批重了商家会分不清该勾哪个 */
  address?: string;
  /** 商家选的区划，**只是建议** —— 最终以运营裁决时填的为准 */
  regionCode?: string;
  /** 区划整条路径名。「北山街道」全国有好几个，光末级判断不了是不是同一个地方 */
  regionPath?: string;
  /** 补充说明：为什么要开这个点 */
  note?: string;
  /** 待审 / 已建社区 / 已驳回。裁完即终态 */
  status: CommunityApplyStatus;
  /** 通过后建出来的社区号；待审与驳回时为空 */
  communityNo?: string;
  /** 驳回原因，**原样展示给商家** —— 不给理由他只会原样再提一次 */
  reason?: string;
  /** 提报时间（毫秒时间戳）*/
  submittedAt: number;
}

export interface CommunityApplyReq {
  /** 小区名。**必填** —— 运营要靠它与地址一起判断是不是已有社区的另一个叫法 */
  name: string;
  /** 地址。不填也能提，但运营多半会驳回：光一个小区名判断不了是不是重复 */
  address?: string;
  /** 商家选的区划，只是建议。留空由运营裁决时补 */
  regionCode?: string;
  /** 补充说明：为什么要开这个点 */
  note?: string;
}

/** 行政区划的一级（`/biz/regions`）。国家统计局统计用区划代码，省 2 / 市 4 / 区 6 / 街道 9 位 */
export interface Region {
  /** 统计用区划代码：省 2 位 / 市 4 位 / 区县 6 位 / 街道 9 位。**前缀即层级**，下级码以上级码开头 */
  regionCode: string;
  /** 上级区划码。省级为空 —— 逐级选择器据此判断自己在不在顶层 */
  parentCode?: string;
  /** PROVINCE / CITY / DISTRICT / STREET */
  level: string;
  /** 本级名称，**不含上级**（「西湖区」不是「杭州市 / 西湖区」）。要整条路径的地方自己拼 */
  name: string;
  /** 是否启用。B 端只会拿到启用的 —— 停用的区划是运营的维护对象，不该出现在商家的选择器里 */
  enabled: boolean;
  /** 下面还有没有下级。端上据此决定「还要不要再往下选一层」，而不是点进去才发现是空的 */
  hasChild: boolean;
}

/** 店铺码（C-ST-08 扫码进店的商家侧） */
export interface StoreQrcode {
  /** 扫码后进入的落地页地址，带 merchant_no 归因参数 */
  url: string;
  /** 可打印版（贴纸尺寸），真实环境由后端生成小程序码 */
  printUrl: string;
}

/** 分享素材（B-11.2.7）。文案与海报由服务端按当前语言与市场生成 */
export interface ShareKit {
  /** 分享文案，已按当前语言与市场生成 */
  text: string;
  /** 分享海报图 URL */
  posterUrl: string;
}

// ================================================================ 营销（B 端配置侧）

/**
 * 商家营销活动。
 *
 * 为什么统一成一个 `MarketingCampaign` 而不是四张表：券、满减、限时特价、买赠
 * 在数据上只差「触发条件 + 优惠方式」，各建一套的结果是四份几乎一样的增删改查，
 * 以及四份互不知情的叠加规则 —— 而叠加恰恰是最容易算错的地方。
 */
export type CampaignType =
  | "COUPON" // 店铺券：用户领取后在结算页抵扣
  | "FULL_CUT" // 满减：满 X 减 Y，无需领取
  | "FLASH" // 限时特价：指定商品在时段内改价
  | "BUY_GIFT"; // 买赠：买 N 送 M

export type CampaignStatus = "DRAFT" | "RUNNING" | "PAUSED" | "ENDED";

export interface MarketingCampaign {
  /** 活动单号 */
  campaignNo: string;
  /** 所属商家。活动是店铺级的，不跨店 */
  merchantNo: string;
  /** 活动类型，决定下面哪几个可选字段有意义 */
  type: CampaignType;
  /** 活动名，展示给用户 */
  name: string;
  /** 活动状态 */
  status: CampaignStatus;
  /** 生效开始时间 */
  startAt: number;
  /** 生效结束时间 */
  endAt: number;
  /** 门槛：满多少（最小货币单位）。FLASH / BUY_GIFT 不用 */
  thresholdMinor?: number;
  /** 优惠额：COUPON / FULL_CUT 用（最小货币单位） */
  discountMinor?: number;
  /** FLASH：活动价（最小货币单位） */
  flashPriceMinor?: number;
  /** BUY_GIFT：购买件数门槛 N */
  buyN?: number;
  /** BUY_GIFT：赠送件数 M */
  giftM?: number;
  /** 参与商品；空 = 全店 */
  goodsNos: string[];
  /** COUPON：发放总量。**预算上限，防止发穿** */
  totalCount?: number;
  /** COUPON：已被领取的数量 */
  takenCount?: number;
  /** 已核销/已使用次数，衡量效果 */
  usedCount: number;
  /**
   * 只对这家门店生效；**空 = 全主体**（存量活动都是它）。
   *
   * 多门店商家必须看得见 —— 否则两条同名的「开业满减」分不清是哪家店的。
   */
  storeNo?: string;
}

/** 新建/编辑活动的入参 */
export interface CampaignDraft {
  /** 活动单号。新建时不传，编辑时必传 */
  campaignNo?: string;
  /** 活动类型。**创建后不可改** —— 改类型等于换一套优惠语义，应当新建 */
  type: CampaignType;
  /** 活动名 */
  name: string;
  /** 生效开始时间 */
  startAt: number;
  /** 生效结束时间。须晚于 startAt */
  endAt: number;
  /** 门槛：满多少（最小货币单位）。COUPON / FULL_CUT 用 */
  thresholdMinor?: number;
  /** 优惠额（最小货币单位）。COUPON / FULL_CUT 用 */
  discountMinor?: number;
  /** 活动价（最小货币单位）。FLASH 用 */
  flashPriceMinor?: number;
  /** 购买件数门槛 N。BUY_GIFT 用 */
  buyN?: number;
  /** 赠送件数 M。BUY_GIFT 用 */
  giftM?: number;
  /** 参与商品；空数组 = 全店 */
  goodsNos: string[];
  /** 发放总量。COUPON 用，不传表示不限量 */
  totalCount?: number;
  /**
   * 只对这家门店生效；不传 = 全主体。
   *
   * **只有 FULL_CUT 接受它**（后端会拒 70005）。判据是活动在哪一刻生效：
   * 满减在算价时生效，那时顾客已选好自提点；限时特价与买赠改的是商品页的展示，
   * 而浏览商品时自提点还没选 —— 允许限定门店会让页面价与下单价打架。
   */
  storeNo?: string;
}

// ================================================================ 门店主页（C 端）

/**
 * 门店主页数据（C-ST-01）。
 * ⚠️ 这是**交易页不是介绍页**：登录用户第一屏是「我买过的」，不是店招 Banner。
 * 粮油副食的复购路径必须压到三步 —— 打开 → 常买 → 下单（ADR-004 §3.3）。
 */
export interface StoreHome {
  /** 平台建档的商家主数据（名称/资质/评分），店主改不了 */
  merchant: Merchant;
  /** 店主自己维护的门面内容（公告/营业时间/地址） */
  store: StoreProfile;
  /** 在售商品。首屏展示，分页靠单独的商品列表接口 */
  goods: Goods[];
  /** 我是否收藏了这家店 */
  favorited: boolean;
}

/** 常买清单的一行（C-ST-02）。按购买频次排序，不是按时间 */
export interface FrequentItem {
  /** 商品单号 */
  goodsNo: string;
  /** SKU 单号。常买是按 SKU 记的 —— 买惯了 5 斤装的人不想要 10 斤装 */
  skuNo: string;
  /** 商品标题 */
  title: string;
  /** 封面图 */
  cover: string;
  /** 规格文案 */
  spec: string;
  /** 当前价（可能已与上次购买时不同） */
  price: number;
  /** 上次买的价，用于「涨价了」提示 */
  lastPrice: number;
  /** 买过几次。列表按它排序，不是按时间 */
  times: number;
  /** 上次购买时间 */
  lastAt: number;
  /** 已下架/无库存 —— 一键再来一单时要显式标出，不能静默丢掉 */
  invalid?: boolean;
}

/** 一键再来一单的结果（C-ST-03）。**丢了什么必须说清楚**，静默少加是投诉源头 */
export interface ReorderResult {
  /** 成功加入购物车的件数 */
  added: number;
  /** 已失效、没加进购物车的商品名 */
  dropped: string[];
  /** 涨价了但仍加入的商品名 */
  priceUp: string[];
}

/**
 * 入驻申请状态（C 端查自己的进度 / 平台端审核队列共用）。
 *
 * 状态机：`PENDING → REVIEWING → APPROVED | REJECTED`，`REJECTED → PENDING`（补料重提）。
 * **APPROVED 是终态** —— 已经建了商家、发了账号，回退没有意义。
 *
 * ⚠️ 这条是**审核**生命周期，与 `Merchant` 上的**经营**状态（ACTIVE/SUSPENDED）无关：
 * 审核发生在商家还不存在的时候，封禁发生在商家已经存在之后。混成一个枚举会让
 * 「驳回一份申请」和「封禁一家店」共用取值，两件事迟早互相踩。
 */
export interface MerchantApplyStatus {
  /** 申请单号 */
  applyNo: string;
  /** 申请时填的店铺名。**存快照** —— 后来改名不该让历史申请跟着变 */
  name: string;
  /** 主体类型。决定分账主体形态与所需资质（ADR-002 §4） */
  subject: MerchantSubject;
  /** 审核状态。迁移见本类型的注释，APPROVED 为终态 */
  status: MerchantApplyReviewStatus;
  /** 驳回理由。**驳回必须写** —— 不写就等于让人猜着改 */
  rejectReason?: string;
  /** 通过后生成的商家单号。未通过时为空 —— 商家在通过之前根本不存在 */
  merchantNo?: string;
  /** 提交时间 */
  createdAt: number;
  /** 审核完成时间。PENDING/REVIEWING 期间为空 */
  auditedAt?: number;

  // ── 以下是**申请时填的原样内容**，用于驳回后回填 ──────────────────
  //
  // 为什么整份带回来而不是只给状态：驳回往往只缺一张执照，
  // 让人从头重填一遍是把「补交」变成「重来」—— 而重来的人有相当一部分就不回来了。

  /** 联系人姓名 */
  contactName: string;
  /** 联系手机号。这是申请人自己填的联系号码，**不是登录号**，不脱敏 */
  contactPhone: string;
  /** 主营类目 */
  category: string;
  /** 店铺简介 */
  desc: string;
  /** 期望经营范围（ADR-009） */
  serviceScope?: ServiceScope;
  /** 期望覆盖的社区 */
  communityNos?: string[];
  /** 已传的资质图 */
  licenses?: string[];
  /** 申请时选的行业。驳回回填要用它 —— 换个行业可能连主体类型都得跟着换 */
  industry?: string;
  /**
   * 是否愿意承接自提点（ADR-005）。
   *
   * **只是意愿，不代表点已建立** —— 建点要谈服务费口径，一期由运营在通过后另行处理。
   * 所以商家勾了这一项、通过后却还没看到履约台，是正常的中间状态而不是故障。
   */
  asPickupPoint?: boolean;
}

// ================================================================ 自提点（ADR-005）

/**
 * 自提点实体。
 *
 * 取代了原先的 `Merchant.isPickupPoint` 布尔字段 —— 那个表达不了「承接方是用户」：
 * 邻里自提是送到**团发起人家里**，承接的是邻居本人，不是商家。
 */
/**
 * 自提点类型。对应 `cmt_pickup_point.type`。
 *
 * ⚠️ 此前只以裸字面量的形式内联在 `PickupPoint.type` 里 —— 值是对的，
 * 但**没有单一声明处**：对账工具扫不到它，各处写的是裸字符串。
 * `CATEGORY_TYPE` 出事前正是这个状态（见 docs/technical/枚举统一方案.md §2「C 无主」）：
 * 今天没 bug，但下一个人在别处再写一次时，没有任何东西会拦住他写错。
 */
export type PickupPointType =
  | "STORE" // 商家自有门店，不收费
  | "NEIGHBOR" // 邻居家。**承接方是用户不是商家，零报酬**（ADR-005）
  | "PLATFORM"; // 平台提供，线下协商

export interface PickupPoint {
  /** 自提点单号 */
  pickupNo: string;
  /**
   * 自提点由谁承接。**三档，各自的费用规则完全不同**（2026-08-06 定）：
   *   · STORE    商家自己的门店 —— 商家自行解决，平台不收履约服务费
   *   · NEIGHBOR 团发起人家里 —— **零报酬**（ADR-005），有报酬就是团长招募换个名字
   *   · PLATFORM 平台提供的点 —— 收履约服务费，**费率线下逐点协商，由运营平台录入**
   */
  type: PickupPointType;
  /** 承接方所属账号池 */
  ownerType: PickupOwnerType;
  /** 承接方单号，按 ownerType 落在 merchantNo 或 cUserNo 上 */
  ownerNo: string;
  /** 常驻 | 团粒度（一团一销） */
  scope: PickupScope;
  /** type=NEIGHBOR 时必填：这个点只服务这一个团 */
  groupNo?: string;
  /** 自提点名称 */
  name: string;
  /**
   * 展示地址。**成团前只到楼栋，付款后才给完整门牌**（B13）——
   * 未成团的团不该暴露发起人住址。
   */
  address: string;
  /** 约定取货时段。邻居家不能一直堆着货（B15） */
  timeSlot?: string;
  /**
   * 计费口径。**必须显式标出用哪一种** —— 库里按件与按率两列长期并存，
   * 没有判别列的话结算侧只能猜，猜错就是给自提点少付或多付钱。
   * 之所以两种都留：费率是**线下逐点协商**的，有的点谈成按件、有的谈成按成交额抽成，
   * 硬统一成一种会让运营在谈判里没有筹码。
   */
  feeMode: PickupFeeMode;
  /** feeMode=PER_ITEM 时的按件服务费。STORE 与 NEIGHBOR 恒为 0 */
  serviceFeePerItemMinor: number;
  /** feeMode=RATE 时的费率（万分比）。STORE 与 NEIGHBOR 恒为 0 */
  serviceFeeRate: number;
}

/**
 * 商家的客户（B-11.2.8）。
 *
 * 这是「商家自带客流」定位下最该给店主看的东西：**谁在买、谁不来了**。
 * 平台电商给商家看的是流量与转化；小店老板要的是「张阿姨上个月每周都来，这半个月没来」。
 */
export interface MerchantCustomer {
  /** 脱敏昵称，不给完整手机号（B12） */
  /** 客户昵称 */
  nickname: string;
  /** 客户头像 */
  avatar: string;
  /** 在本店的累计下单次数 */
  orderCount: number;
  /** 在本店的累计消费额（最小货币单位） */
  totalSpentMinor: number;
  /** 最近一次下单时间 */
  lastOrderAt: number;
  /** 距上次下单天数 */
  daysSinceLast: number;
  /** 沉默客户：曾经常来、最近没来。**这是店主唯一能立刻行动的信号** */
  silent: boolean;
  /** 客流来源：他是你自己带来的，还是平台分配的 */
  source: TrafficSource;
}

// ================================================================ 规格模板

/**
 * 规格选项。
 *
 * `code` 是**能不能做规格聚合的分水岭**：
 * 三家店卖同一种米，自由输入会写成「5斤」「五斤」「2.5kg」——
 * 这三个字符串在库里毫无关系，将来想做「按重量筛选 / 同规格比价」全部落空，
 * 而且不可回溯（历史商品已经写死）。所以模板带来的值必须带 code。
 *
 * 自由输入的值只有 label、没有 code：照常展示，但不参与聚合。
 * **一期只写入不消费**，聚合搜索是二期 —— 但字段现在就得留位。
 */
export interface SpecOption {
  /** 来自模板时有值；商家自己输入的没有 */
  code?: string;
  /** 选项展示文案，如「约5斤」 */
  label: string;
}

/**
 * 规格模板。两层：
 *   · PLATFORM —— 平台按类目预置，可聚合可筛选
 *   · MERCHANT —— 商家把自己常用的存下来，第二次建品直接套
 *
 * ⚠️ **模板是建议不是强制**：卖手工酱菜的没有匹配模板，硬要他选就只能瞎选。
 */
export interface SpecTemplate {
  /** 模板单号 */
  templateNo: string;
  /** 模板归属：平台统一维护 or 商家自存。商家只能改自己的 */
  scope: SpecTemplateScope;
  /** 平台模板按类目推荐；商家模板不限类目 */
  categoryType?: CategoryType;
  /** 规格维度名，如「重量」「香型」 */
  name: string;
  /** 该维度的可选项 */
  options: SpecOption[];
  /** scope=MERCHANT 时归属的商家 */
  merchantNo?: string;
}

// ================================================================ 售后（完整状态机）

/**
 * 售后类型。**仅退款与退货退款的流程根本不同** ——
 * 仅退款同意即退；退货退款必须**先收到货再退款**，否则「退款了货没回来」。
 * 此前两者走同一条路，是售后闭环缺的后半段（B-7.3）。
 */
export type AfterSaleType = "REFUND_ONLY" | "RETURN_REFUND";

/**
 * 售后单状态。**这是后端 `OrdAfterSale` 真实存的取值。**
 *
 * ⚠️ 这里此前是完全另一套：`PENDING`/`AGREED`/`RETURNING`/`RECEIVED`/`DONE`/`DISPUTED`，
 * 与后端**只有 `REJECTED` 一个词重合**。c/b 两端按它判断、按它建 i18n 词条，
 * 于是售后详情页的状态永远落进兜底分支，「填退货单号」按钮永远不出现
 * （它 gate 在一个后端永远不会下发的 `AGREED` 上）。
 *
 * 那一套描述的是**想象中更细的流程**：同意 → 寄回 → 收货 → 退款四步。
 * 后端没有把「寄回中」「已收货」做成独立状态 —— 商家一同意就进 `REFUNDING`，
 * 退货物流走 `expressNo` 字段而不是状态。粒度差异是真实的设计选择，
 * 端上不能自己补一套更细的词然后假装后端会给。
 */
export type AfterSaleStatus =
  | "APPLIED" // 待商家处理
  | "REFUNDING" // 商家已同意，退款处理中；退货退款时这一段也含「等买家寄回」
  | "REFUNDED" // 退款完成
  | "REJECTED" // 商家驳回
  | "ARBITRATING" // 用户不服，已上升平台裁决
  | "CLOSED"; // 用户撤销，或超时关闭

export interface AfterSale {
  /**
   * 售后单号。**售后是独立资源，不是订单上的一个字段** ——
   * 它有自己的生命周期（申请→同意/驳回→寄回→收货→退款），能被取消、能上升平台，
   * 一个订单还可能先后发起多次。挂在订单下用 orderNo 寻址，第二次申请就没法表达了。
   * 后端一开始就是这么建的（/mp/after-sale/{afterSaleNo}/**），这里向它对齐。
   */
  afterSaleNo: string;
  /**
   * 所属**子订单**号（`SUB…`）。
   *
   * ⚠️ **要关联回订单卡片用的是这个，不是下面的 `orderNo`。**
   * C/B 两端列表里的一行是一张子订单，而 `Order.orderNo` 字段里装的就是子订单号
   * （后端 `OrderVO.orderNo` = `SUB…`）；售后单上的 `orderNo` 却是**主单号**（`SO…`）。
   * 两个字段同名不同物 —— 按 `orderNo` 去 join 一条也匹配不上，
   * 而症状是「售后页签空着」，与它本来要修的 bug 一模一样。
   */
  subOrderNo: string;
  /**
   * 所属**主订单**号（`SO…`）。跨商家下单会拆成多笔子订单，它们共用这一个主单号。
   * 展示「同一次下单」时用它，关联单张订单卡片请用 {@link subOrderNo}。
   */
  orderNo: string;
  /** 售后类型：仅退款 / 退货退款 */
  type: AfterSaleType;
  /** 售后单状态，独立于订单状态流转 */
  status: AfterSaleStatus;
  /** 用户填写的售后原因 */
  reason: string;
  /** 举证图（破损、少件的照片）。是否必填由售后类型决定 */
  images: string[];
  /** 商家同意/驳回时的说明 */
  merchantReply?: string;
  /** 用户寄回的运单号（RETURN_REFUND） */
  returnExpressNo?: string;
  /** 上升平台时用户的申诉理由 */
  disputeReason?: string;
  /** 最后一次状态变更时间。超时自动同意等时效规则以它为基准 */
  updatedAt: number;
}
