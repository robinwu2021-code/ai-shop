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
const APP_NS = import.meta.env?.VITE_APP_NS || "sh";

/**
 * 数据源后缀（`m` = mock / `r` = real）。**切 `VITE_USE_MOCK` 就换一整套存储空间。**
 *
 * 不加这个后缀的话，切到真实后端时本地还留着 mock 时代的选择 ——
 * 而 mock 的社区号是 `CM001`、真库是 `C0001`，两边的号<b>互不存在</b>。
 * 结果是首页老老实实按 `communityNo=CM001` 去查，后端老老实实返回空，
 * 用户看到「这个社区暂时还没有商家上架」，<b>一个报错都没有</b> ——
 * 他会以为平台上没商家，而不是自己选了一个不存在的小区。
 *
 * 登录态同理：mock 的 token 在真后端一律 401，而 401 会被当成"登录过期"，
 * 于是切过去的第一件事是被莫名其妙地踢出登录。
 *
 * 换命名空间比"切换时清理旧数据"可靠：清理要枚举所有 key，加一个就漏一个；
 * 而且切回 mock 时，之前那套 mock 数据还原样在。
 */
const SOURCE = import.meta.env?.VITE_USE_MOCK === "0" ? "r" : "m";

const NS = `${APP_NS}${SOURCE}`;

/** 本地存储 key */
export const STORAGE = {
  token: `${NS}_token`,
  user: `${NS}_user`,
  skin: `${NS}_skin`,
  mode: `${NS}_mode`,
  lang: `${NS}_lang`,
  market: `${NS}_market`,
  community: `${NS}_community`,
  /**
   * B 端「当前门店」。**存本地**而不是每次问服务端：
   * 它是会话上下文，切一次要在整个 App 里生效，重开也要还在原来那家店 ——
   * 每次回落默认店的话，多门店老板每天早上都要重选一次。
   */
  storeNo: `${NS}_store_no`,
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
/**
 * 五品类。**取值必须与 `prd_goods.type` 一致** —— 它是商品品类的权威字段。
 *
 * ⚠️ 键叫 GOODS 而值是 "NORMAL"：库里存的就是 NORMAL，键名只是代码里的叫法。
 * 这里此前值也写成 "GOODS"，于是 C 端「日用百货」标签页筛 type=GOODS，
 * 而库里 32 条商品全是 NORMAL —— **一条也筛不出来**，页面却写着
 * 「你的社区还没有这类商家」，把 bug 伪装成了业务事实。
 *
 * 混淆的来源：后端另有一张 `sys_channel_category_rule.category_type`
 * 用的是 GOODS（端上当年跟的是它）。同一个「五品类」在后端有两套名字，
 * 而商品筛选走的是前者。
 */
export const CATEGORY_TYPE = {
  /** 日用品（标品）。库里存 NORMAL */
  GOODS: "NORMAL",
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
  /**
   * 到店自提：商家门店（PickupPoint.type=STORE）。**库里存 STORE_PICKUP**。
   *
   * ⚠️ 键叫 PICKUP 而值是 "STORE_PICKUP"：后端 ord_sub_order.fulfillment 存的是后者。
   * 这里此前值也写成 "PICKUP"，后果在确认订单页直接可见 ——
   * 履约方式那一栏显示的是 `fulfillment.STORE_PICKUP`（i18n 键原样打出来），
   * 因为词条按 PICKUP 建、而后端下发 STORE_PICKUP，查不到就回退成键名。
   */
  PICKUP: "STORE_PICKUP",
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
 * 成本模型：**预付费** —— 商家发放积分的那一刻就从货款里扣走对应的钱，进平台积分资金池；
 * 用户在任意一家花分时，由池子付给收单方。发放后这批分与发放商家再无关系。
 * 详见 [积分域-完整方案](../../../../docs/technical/积分域-完整方案.md)。
 *
 * 三条硬约束：
 *   1. **抵扣有上限**：整单抵扣会让商家一分钱收不到，商家不会接受
 *   2. **有有效期**：流通中的积分都占着池子里的钱，不能无限期挂着
 *   3. **发放即时、可用延后**：支付成功就发（用户立刻看得到），
 *      但推到售后期后才能花 —— 否则「下大单 → 拿分 → 立刻花掉 → 退单」白拿
 *
 * 第 2、3 条曾经是另一个样子（「或有负债挂在商家账上」「完成时才发放」），
 * 那是信用模型的说法，已随 V22 废除。
 */
export const POINTS = {
  /** 抵扣汇率：多少积分抵 1 个最小货币单位（100 积分 = 1 元） */
  perMinor: 1,
  /** 单笔订单积分最多抵扣的金额比例 */
  maxDeductRatio: 0.3,
  /**
   * 无活动多久清零。**滚动到期**：任何积分变动（获得或使用）都把到期日推后这么久。
   *
   * 为什么不用固定期限（获得日 + N 天）：那要在每条发放流水上维护批次剩余与到期日，
   * 而它们唯一的读者是过期任务本身；用户还要面对「300 分 1/5 过期、200 分 1/18 过期」
   * 这种记不住的规则。账户级只有一个日子，批次概念整个不需要。
   *
   * 行业主流：万豪 24 个月内有活动即延期；达美/美联航里程永久有效；招行积分永久有效。
   *
   * ⚠️ 它是**一次性全部清零**，冲击远大于零星过期 ——
   * 到期前 30 天推送提醒不是可选项，没有提醒这个模型对用户是敌意的。
   */
  inactiveDays: 365,
  /** 未单独配置积分的商品，按成交金额的千分之几发放 */
  defaultEarnRatio: 0.01,
} as const;

/** 分页 */
export const PAGE = {
  size: 20,
} as const;

/** 页面路径 —— 禁止在业务代码里手写路径字符串 */
/**
 * 商品封面缺失时的占位。
 *
 * <p>**没图是常态，不是异常**：商家在 B 端建完商品往往先上架、图片回头补，
 * 而 mock 数据每一条都带 emoji 封面，所以这个情况在接真后端之前一次都没出现过。
 * 不给占位的结果是列表里一块空白 —— 看着像图没加载出来，人会一直等。
 */
export const GOODS_COVER_FALLBACK = "🛒";

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
