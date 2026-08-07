// 业务常量 —— 零硬编码：页面/组件不许直接写数字与魔法字符串，一律从这里取。

/**
 * 本地存储 key 的**端前缀**（c- / b-）。
 *
 * 为什么不是固定的 `sh_`：C 端与 B 端一旦落在同一个域名下（曾经把两端合成一个站点、
 * B 端挂 `/m/`），localStorage 是按 origin 隔离的，于是登录态、皮肤、语言、
 * 连 mock 的整个「数据库」都变成同一份 —— 商家端读到消费者的订单，界面自然是乱的。
 * 现在两端各自独立部署（ADR-008 §5），前缀是第二道保险：
 * 就算有人把它们放回同一域名，两端的存储也各归各。
 */
const NS = import.meta.env?.VITE_APP_NS || "sh";

/** 本地存储 key */
export const STORAGE = {
  token: `${NS}_token`,
  user: `${NS}_user`,
  skin: `${NS}_skin`,
  mode: `${NS}_mode`,
  lang: `${NS}_lang`,
  market: `${NS}_market`,
  community: `${NS}_community`,
  cart: `${NS}_cart`,
  searchHistory: `${NS}_search_history`,
} as const;

/** mock 落盘用的 key —— 同样带端前缀，两端的「数据库」不能是同一份 */
export const MOCK_DB_KEY = `${NS}_mock_db`;

/** 语言 —— ar 走 RTL 镜像布局 */
export const LANGS = [
  { id: "zh-CN", label: "中文", rtl: false },
  { id: "en", label: "English", rtl: false },
  { id: "ar", label: "العربية", rtl: true },
] as const;

export const DEFAULT_LANG = "zh-CN";

/**
 * 货币。
 * 金额一律以「最小单位」在内部流转（人民币=分，美元=美分，迪拉姆=菲尔），
 * `minorUnits` 指明小数位数 —— 日元这类零小数货币接进来时只需改这里，不动业务代码。
 * 不用 Intl.NumberFormat：小程序基础库对 Intl 的支持不稳定，手写格式化才能跨端一致。
 */
export const CURRENCIES = {
  CNY: { code: "CNY", symbol: "¥", minorUnits: 2, symbolAfter: false },
  USD: { code: "USD", symbol: "$", minorUnits: 2, symbolAfter: false },
  AED: { code: "AED", symbol: "د.إ", minorUnits: 2, symbolAfter: true },
} as const;

/**
 * 市场（地区）—— 一个市场决定货币 + 时区，语言仍可独立切换。
 * `utcOffsetMinutes` 用固定偏移而非 IANA 时区：小程序没有可靠的 tz 数据库，
 * 而目标市场（中国 +8 / 海湾 +4）均无夏令时，固定偏移是准确的。
 * ⚠️ 若将来进入有夏令时的市场（欧美），必须换成带 tz 数据的方案，见 TDD 风险表。
 */
export const MARKETS = [
  { id: "CN", currency: "CNY", utcOffsetMinutes: 8 * 60, labelKey: "market.CN" },
  { id: "AE", currency: "AED", utcOffsetMinutes: 4 * 60, labelKey: "market.AE" },
  { id: "US", currency: "USD", utcOffsetMinutes: -5 * 60, labelKey: "market.US" },
] as const;

export const DEFAULT_MARKET = "CN";

/** 品类类型 —— 驱动计价/履约策略分发 */
export const CATEGORY_TYPE = {
  /** 日用品（标品） */
  GOODS: "GOODS",
  /** 生鲜水果（预售 · 约重） */
  FRESH: "FRESH",
  /** 服务（到店核销 / 预约上门） */
  SERVICE: "SERVICE",
  /** 虚拟商品（话费/会员/兑换码，下单即发码，无物流） */
  VIRTUAL: "VIRTUAL",
  /** 卡券（次卡/储值卡/券包，购买后进卡包，按次或按额度核销） */
  CARD: "CARD",
} as const;

/** 履约方式 */
/**
 * 商家**经营范围**。决定「这家店的货能不能卖到我这儿」，是商品可见性的第一道闸门。
 *
 * 为什么必须显式建模、不能用「社区列表为空 = 全域」糊过去：
 * 那样分不清「还没设置」和「就是要全平台」，商家漏配一次，货就悄悄卖到了送不到的地方 ——
 * 用户下单后才发现提不了货，是纯粹的投诉与退款。范围写死成枚举，漏配就是配置错误，能被拦住。
 *
 * ⚠️ 范围 ≠ 履约方式。范围管**能不能卖给你**，履约管**怎么送到你手上**：
 * 一家全市范围的家政能上门到你家，但它不可能进你社区的自提点。
 * 所以 COMMUNITY 通常配自提，CITY 配快递/同城配送，两者要一起校验（见 strategies/fulfillment）。
 */
export const SERVICE_SCOPE = {
  /** 仅指定社区 —— 楼下的菜摊、理发店。靠自提点履约，出了这几个小区就送不到 */
  COMMUNITY: "COMMUNITY",
  /** 全市 —— 家政、维修这类上门服务，或有同城配送能力的商家 */
  CITY: "CITY",
  /** 全平台 —— 无履约半径的：虚拟商品、卡券、平台自营的快递品 */
  PLATFORM: "PLATFORM",
} as const;

export const FULFILLMENT = {
  /** 到店自提：商家门店（PickupPoint.type=STORE） */
  PICKUP: "PICKUP",
  /** 邻里自提：送到团发起人家里（PickupPoint.type=NEIGHBOR，ADR-005）
   *  ⚠️ 承接方是用户不是商家，**零报酬**，且只能是自己发起的团 */
  NEIGHBOR_PICKUP: "NEIGHBOR_PICKUP",
  /** 送货上门（从自提点二次配送到家） */
  DELIVERY: "DELIVERY",
  /** 快递配送 */
  EXPRESS: "EXPRESS",
  /** 到店核销 */
  STORE_VERIFY: "STORE_VERIFY",
  /** 预约（到店或上门，需选时段） */
  APPOINTMENT: "APPOINTMENT",
  /** 即时发放（虚拟商品发码 / 卡券入卡包） */
  INSTANT: "INSTANT",
} as const;

/** 交易规则（一期） */
export const TRADE_RULES = {
  /** 未支付自动关单（分钟） */
  payTimeoutMinutes: 15,
  /** 提单锁库超时（分钟） */
  stockLockMinutes: 15,
  /** 生鲜每日截单时间（HH:mm，市场本地时区） */
  freshCutoffTime: "21:00",
  /** 坏果包赔申请时限（小时，自核销起算） */
  freshClaimHours: 24,
  /** 极速退款自动通过的金额上限（最小货币单位） */
  instantRefundMaxMinor: 5000,
  /** 逾期未自提：顺延天数，超出作废 */
  pickupGraceDays: 1,
  /** 拼团超时未成团自动退款（小时） */
  groupBuyTimeoutHours: 24,
  /** 预约可选天数窗口 */
  appointmentWindowDays: 7,
  /** 预约最晚可改期（服务开始前小时数） */
  appointmentChangeBeforeHours: 4,
  /** 送货上门费（最小货币单位）与免配送门槛 */
  deliveryFeeMinor: 300,
  deliveryFreeThresholdMinor: 5900,
} as const;

/**
 * 拼团规则。
 *
 * 定位修正：拼团**只是一种活动**，不是平台的核心机制。核心是邻里之间买东西。
 * 所以这里刻意做薄：
 *   · 团由**用户自发发起**，不是运营预先配置好的活动位
 *   · 只有单档（够 N 人成团享团购价），不做阶梯价 —— 阶梯是增长玩法，不是邻里日常
 *   · 已参团的人同享成团价，差价退回（这条保留：先买的人拉人是为自己，不是为别人）
 *   · 不成团不作废，按原价照常发货
 * 完整说明见 docs/requirements/C端功能清单.md §五之四。
 */
export const GROUP_BUY = {
  /** 团有效期（小时）。发起后多久截止，与生鲜截单取更早的那个 */
  validHours: 24,
  /** 未达成团人数时：降级按原价发货（false = 取消退款） */
  fallbackShipAtBasePrice: true,
} as const;

/**
 * 促销类型。买 N 送 M：付 N 件的钱，收到 N+M 件。
 * 赠品默认同款，也可指定别的商品（如买米送油）。
 */
export const PROMOTION_TYPE = {
  BUY_N_GET_M: "BUY_N_GET_M",
} as const;

/** 裂变与归因（窗口期待业务最终确认，见 TDD §7） */
export const ATTRIBUTION = {
  /** 邀请归因窗口期（天） */
  inviteWindowDays: 30,
  /** 归因优先级：数组靠前者优先 */
  priority: ["INVITER", "LEADER", "CHANNEL"] as const,
} as const;

/**
 * 功能开关。
 *
 * 一期不上的功能在这里关掉，**代码保留不删** —— 删了二期要重写，
 * 留着但不接开关又会漏在界面上。开关是唯一的真源：所有入口、所有展示都读它。
 */
/**
 * 二期功能开关。**一期一律 false，代码与接口保留** ——
 * 二期范围（2026-08-06 定）：积分 · 卡包 · 工单 · 在线客服。
 *
 * 为什么保留代码而不是删掉：这几块的模型都已经建好并跑通了，
 * 删了二期要重做一遍，而留着的成本只是一个 if。**但入口必须真的关掉** ——
 * 半开的功能（页面能进、数据是空的）比没有这个功能更糟。
 */
export const FEATURES = {
  /**
   * 积分。一期**不上线**。
   * 成本模型已定（商家结算时扣除），但跨商家清算方案未定 —— 见 ADR-006。
   * 打开这个开关即可上线：定价、账户、流水、B 端接收都已实现并通过编译。
   */
  points: false,
  /**
   * 卡包（储值卡余额 / 次卡次数）。一期**不上线**，二期与积分一起做。
   *
   * 理由不是功能不重要 —— 次卡是社区服务商家的主要收款形式。是**储值余额属于平台负债**：
   * 用户预付的钱记在哪、怎么对账、跑路了怎么办，与积分是同一类问题（ADR-006），
   * 要一起解决。半做一个「能买卡但对不平账」的卡包，比不做更危险。
   *
   * 库表也因此不建（`mkt_user_card` + 流水表留到二期）。
   */
  cards: false,
} as const;

/**
 * 积分规则。
 *
 * 成本模型（已定）：**用户抵扣的金额，在商家收入结算时扣除** —— 即积分等同于商家给的折扣，
 * 平台不垫付、不承担兑付，只做记账与清算。详见 ADR-006。
 *
 * 由此推出的三条硬约束：
 *   1. **抵扣有上限**：整单抵扣会让商家一分钱收不到，商家不会接受
 *   2. **有有效期**：未使用的积分是商家账上的或有负债，不能无限期挂着
 *   3. **完成时才发放**：支付即发放的话，退款要追回已花掉的积分，很难收场
 */
export const POINTS = {
  /** 抵扣汇率：多少积分抵 1 个最小货币单位（100 积分 = 1 元） */
  perMinor: 1,
  /** 单笔订单积分最多抵扣的金额比例 */
  maxDeductRatio: 0.3,
  /** 有效期（天） */
  validDays: 365,
  /** 未单独配置积分的商品，按成交金额的千分之几发放 */
  defaultEarnRatio: 0.01,
} as const;

/** 分页 */
export const PAGE = {
  size: 20,
} as const;

/** 页面路径 —— 禁止在业务代码里手写路径字符串 */
export const ROUTES = {
  goods: "/pages/goods/index",
  merchant: "/pages/merchant/index",
  merchants: "/pages/merchants/index",
  store: "/pages/store/index",
  groupHost: "/pages/group-host/index",
  search: "/pages/search/index",
  address: "/pages/address/index",
  orderConfirm: "/pages/order-confirm/index",
  pay: "/pages/pay/index",
  orders: "/pages/orders/index",
  order: "/pages/order/index",
  afterSale: "/pages/after-sale/index",
  coupons: "/pages/coupons/index",
  cards: "/pages/cards/index",
  messages: "/pages/messages/index",
  requestCreate: "/pages/request-create/index",
  points: "/pages/points/index",
  reviewWrite: "/pages/review-write/index",
  group: "/pages/group/index",
  groups: "/pages/groups/index",
  request: "/pages/request/index",
  login: "/pages/login/index",
  community: "/pages/community/index",
  home: "/pages/home/index",
  category: "/pages/category/index",
  cart: "/pages/cart/index",
  me: "/pages/me/index",
} as const;

/** 底部菜单 —— 自定义 tabBar（原生 tabBar 字号锁死且不吃 CSS 变量）。
 *  图标用线性/实心两态：未选线性、选中实心，比只靠颜色区分更清晰。 */
export const TABS = [
  { key: "home", route: ROUTES.home, icon: "home", iconOn: "homeFilled", labelKey: "tab.home" },
  { key: "category", route: ROUTES.category, icon: "grid", iconOn: "gridFilled", labelKey: "tab.category" },
  { key: "merchants", route: ROUTES.merchants, icon: "store", iconOn: "storeFilled", labelKey: "tab.merchants" },
  { key: "cart", route: ROUTES.cart, icon: "cart", iconOn: "cartFilled", labelKey: "tab.cart" },
  { key: "me", route: ROUTES.me, icon: "user", iconOn: "userFilled", labelKey: "tab.me" },
] as const;

/**
 * 结算参数。
 *
 * ⚠️ **这里全是占位值，接入前必须换成书面口径**：
 *   · 平台费率分档（B10）：商家自带客流建议**零佣金** —— 他带来的客户在别家的消费
 *     才是平台的收益，从这单抽 5% 是捡芝麻（ADR-004 §6）
 *   · 自提点履约服务费（B9）：按件计费，生鲜加价。原来分拣核销是团长顺手干的，
 *     现在拆开必须单独付钱，不定这个数小店不会接这个活
 *   · 分账比例上限、时限、个人接收方限额（B7）：各签约模式不同且会变，
 *     **按经验值写进代码是给自己埋雷**，必须从支付服务商处拿书面口径（ADR-002 §3）
 */
/** 评价治理规则 */
export const REVIEW_RULES = {
  /**
   * 可申诉的最高星级。四星五星开放申诉等于「凡是不满意的都申诉一遍」，
   * 平台裁决台会被淹掉，真正的恶意差评反而排不上号。
   */
  appealMaxRating: 3,
  /** 三维度权重之和必须为 100（平台端 P-13.1 可调，这里是默认值） */
  scoreWeights: { goods: 50, fulfillment: 30, service: 20 },
} as const;

export const SETTLE = {
  /** 平台佣金率，按客流来源分档 */
  commissionRate: {
    MERCHANT_OWNED: 0,
    PLATFORM: 0.02,
  },
  /** 自提点履约服务费：按件（最小货币单位） */
  fulfillFeePerItemMinor: 30,
  /** 结算周期：自然周 */
  periodDays: 7,
} as const;
