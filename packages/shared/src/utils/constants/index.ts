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

/**
 * 类目模板 → 品类。**两套码指同一件事，只是不同名。**
 *
 * <p>`prd_category.template` 用 STANDARD/VOUCHER，而 `prd_goods.type` 用 NORMAL/CARD ——
 * 这个不一致是历史遗留，改任何一边都要刷数据，所以留一张映射把它挡在这里，
 * 别让每个用到的地方各写一遍 `=== "STANDARD" ? "NORMAL" : ...`。
 *
 * <p>`VOUCHER → CARD` 而不是 VIRTUAL：卡券要到店核销，虚拟商品是即时发放，
 * 两者履约方式不同（STORE_VERIFY vs INSTANT）。
 */
export const TEMPLATE_TO_TYPE: Record<string, string> = {
  STANDARD: "NORMAL",
  FRESH: "FRESH",
  SERVICE: "SERVICE",
  VOUCHER: "CARD",
};

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
  /**
   * 日用品（标品）。
   *
   * ⚠️ 这个键此前叫 `GOODS` 而值是 `"NORMAL"` —— 名实不符。
   * 能跑，但下一个人读到 `CATEGORY_TYPE.NORMAL` 会以为 wire 上是 `GOODS`，
   * 而这**正是它当初写错的原因**（值也曾写成 "GOODS"，C 端「日用百货」
   * 标签页因此永远是空的）。键改成与值一致，把这个歧义源掐掉。
   */
  NORMAL: "NORMAL",
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

/**
 * 履约**能力**（ADR-013 阶段二）。回答的是「怎么送到你手上」这一件事 ——
 * 不再顺带回答「送得到哪儿」，后者由 {@link ServiceArea} 列表单独说。
 *
 * 为什么要跟 {@link SERVICE_SCOPE} 拆开：三档枚举把两件事压进一个字段，
 * 于是「三个小区 + 整个西湖区」这种再普通不过的诉求**没有字段可写** ——
 * 商家只能选 CITY（卖到全市，送不到）或 COMMUNITY（丢掉那个区）。
 *
 * ⚠️ 它与 `FULFILLMENT` 不是一回事：那个是**某一单**怎么送（落在订单上），
 * 这个是**这家店**有什么送法（落在主体上）。
 */
export const FULFILLMENT_REACH = {
  /** 靠自提点：出了配了的点就送不到。菜摊、理发店 */
  PICKUP: "PICKUP",
  /** 上门或同城配送：能到的范围按区/市框 */
  ONSITE: "ONSITE",
  /** 快递：没有履约半径 */
  SHIPPING: "SHIPPING",
} as const;

/**
 * 覆盖项的生效状态。勾已有社区自助生效；勾区、街道要运营审 ——
 * 一家菜摊声称覆盖整个西湖区，影响面差一个量级（ADR-013 §4.2）。
 */
export const AREA_STATUS = {
  /** 已生效，参与展开 */
  ACTIVE: "ACTIVE",
  /** 待运营审核。**不参与展开** —— 端上必须标出来，否则商家看着它在清单里却没有订单 */
  PENDING: "PENDING",
} as const;

/**
 * 商家提报新社区的单据状态（ADR-013 阶段三）。
 *
 * 与 {@link AREA_STATUS} 不是一回事：那个说「这一条覆盖算不算数」，
 * 这个说「这张提报单走到哪了」—— APPROVED 意味着平台**已经建出了社区**。
 */
export const COMMUNITY_APPLY_STATUS = {
  PENDING: "PENDING",
  /** 已通过，社区建出来了（单据上的 communityNo 这时才有值） */
  APPROVED: "APPROVED",
  /** 驳回。理由必须原样回给商家，否则他只会再提一次同样的 */
  REJECTED: "REJECTED",
} as const;

/**
 * 覆盖项的粒度。可跨粒度组合 —— 「三个小区 + 一个区」是两条 COMMUNITY 加一条 DISTRICT。
 */
export const AREA_LEVEL = {
  /**
   * 聚落：小区或村（cmt_community，kind 区分）。
   *
   * <p>聚落模型（2026-08-22）：**村不是区划粒度**，它和小区一样是挂在
   * 街道/镇（L4）下的聚落 —— 此前短暂加过 VILLAGE 一档，随模型统一撤掉。
   */
  COMMUNITY: "COMMUNITY",
  STREET: "STREET",
  DISTRICT: "DISTRICT",
  CITY: "CITY",
  /**
   * 省。**经营范围本来就是「任意一级的并集」** —— 走快递的商家框的就是省，
   * 而此前这一档不存在，他只能把一个省下面的市一个个勾（山西 11 个、广东 21 个），
   * 或者干脆放弃、把范围留空（留空对自提商家是「谁也看不到」）。
   *
   * 后端不需要为它加任何东西：覆盖展开走国标码前缀（省码 2 位），
   * 审核归入「区/市/省要审」那一档 —— 两条规则都已经在了。
   */
  PROVINCE: "PROVINCE",
} as const;

/**
 * 履约方式。**键名是代码里的叫法，值是 wire 契约 —— 值必须逐字等于
 * `ord_sub_order.fulfillment` 库里存的东西**（见 docs/technical/枚举统一方案.md §3）。
 *
 * 这条规则是两次同形状故障换来的：`PICKUP` 与 `DELIVERY` 都曾把端上的叫法
 * 当成了 wire 值（"PICKUP" / "DELIVERY"），而库里存的是 "STORE_PICKUP" /
 * "MERCHANT_DELIVERY"。后果在确认订单页直接可见 —— 履约方式那一栏显示
 * `fulfillment.MERCHANT_DELIVERY`，**i18n 键原样打给用户**：
 * 词条按端上的叫法建，后端下发库里的值，查不到就回退成键名。
 *
 * 下面分两组。分组不是文档，是**给对账工具看的**：
 * `IMPLEMENTED` 里的值后端此刻就会下发，必须与库严格一致；
 * `PLANNED` 里的后端还没有，对账时不该报「端上编了个不存在的词」。
 */
export const FULFILLMENT = {
  /** 到店自提：商家门店（PickupPoint.type=STORE） */
  PICKUP: "STORE_PICKUP",
  /** 邻里自提：送到团发起人家里（PickupPoint.type=NEIGHBOR，ADR-005）
   *  ⚠️ 承接方是用户不是商家，**零报酬**，且只能是自己发起的团 */
  NEIGHBOR_PICKUP: "NEIGHBOR_PICKUP",
  /** 送货上门（从自提点二次配送到家）。**库里存 MERCHANT_DELIVERY** */
  DELIVERY: "MERCHANT_DELIVERY",
  /** 快递配送 */
  EXPRESS: "EXPRESS",

  /**
   * 到店核销（SERVICE 商品）。**与 STORE_PICKUP 是两件事**：
   * 自提是去代收点取别人送来的货，到店核销是去卖家门店消费自己买的服务。
   * 没有「发货」这一步 —— 付款即出码，支付成功直接落 FULFILLING。
   */
  STORE_VERIFY: "STORE_VERIFY",

  /**
   * 预约到店或上门，需选时段（SERVICE 商品）。
   * **两道必填闸**：预约时间（没时间的「待服务」等于没说）、上门地址（师傅要知道去哪）。
   */
  APPOINTMENT: "APPOINTMENT",

  // ── 以下后端未实现（见 PLANNED_FULFILLMENTS） ──
  /** 即时发放：虚拟商品发码 / 卡券入卡包（VIRTUAL / CARD 商品） */
  INSTANT: "INSTANT",
} as const;

/**
 * 后端**尚未实现**的履约方式。
 *
 * <p>为什么保留而不是删掉：它们不是端上臆想出来的词，而是
 * `prd_goods.type` 里已经存在的 SERVICE / VIRTUAL / CARD 三种形态的
 * 必然对应物，端上有完整的 strategy 实现（`strategies/fulfillment/`）。
 *
 * <p>为什么必须显式列出来：这三个与 `MERCHANT_DELIVERY` 那类错误
 * **形状完全不同，危害也不同**，混在一起就没法自动判定：
 * <ul>
 *   <li>同物异名（DELIVERY）—— 端上主动发出去筛选/展示，**现在就在坏**
 *   <li>后端未实现（本组）—— 由后端下发，后端不发只是让 strategy 暂时不跑，
 *       不产生错误行为
 * </ul>
 * 对账工具据此区别对待：前者必须报，后者是待办不是缺陷。
 */
export const PLANNED_FULFILLMENTS: readonly string[] = [
  // STORE_VERIFY 与 APPOINTMENT 已于 2026-08-17 接通（服务履约一、二期）：
  // 后端取值域、支付后落 FULFILLING（不经「待发货」）、预约时段与上门地址两道闸、核销全链路
  FULFILLMENT.INSTANT,
];

/** 交易规则（一期） */
export const TRADE_RULES = {
  /**
   * 未支付自动关单（分钟）—— **出厂默认值，可被平台配置覆盖**。
   *
   * 运营在 /orders?tab=close 配的值存在后端 `trade.close-rule`，
   * 下单时按当时的配置盖在 `ord_order.pay_deadline_at` 上；没配过时才落回这个 15。
   *
   * 所以它的身份是「默认值」而不是「关单时长的值」——
   * 这张表仍是唯一事实源，它记的是默认值这件事，仍然只有一份。
   *
   * 端上倒计时**必须读接口返回的 `payDeadlineAt`，不要读这个常量**：
   * 运营改过之后两者就不一样了，而症状是「用户看着还剩 3 分钟，订单已经关了」。
   */
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

/*
 * 这里曾有一个 `PROMOTION_TYPE = { BUY_N_GET_M: "BUY_N_GET_M" }`。
 * 删掉的原因：**全仓零引用**（所有地方都直接写字面量），而它是
 * `Promotion.type` 的重复声明 —— 一个概念一个声明处，见
 * docs/technical/枚举统一方案.md §3。
 *
 * 它还在主动误导对账工具：让工具以为 shared 用 BUY_N_GET_M 表达
 * **营销活动**类型，从而报出一条并不存在的差异。真正对应
 * `mkt_campaign.type` 的是 types 里的 `CampaignType`，那个逐字对齐。
 * 商品级买赠（Promotion）与店铺级买赠活动（Campaign BUY_GIFT）是
 * 同一件业务事建了两次模，详见 docs/technical/营销枚举对账报告.md §1②。
 */

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
   * 积分。一期**不上线**，但**挡着它的技术问题已经没有了**（2026-08-13）。
   *
   * 这里原先写着「跨商家清算方案未定」—— 那句话已经过期：清算方案就是预付费池子，
   * 而本轮把池子的两端都接上了（发分收费入池 / 兑付与到期出池），
   * 并补了每日恒等式自检（`PointsIdentityJob`）。
   *
   * **打开之前只剩一件事**：先让自检在真实数据上连跑几天且不告警。
   * 失衡是单调增长的 —— 开了之后才发现差额，就已经查不回是从哪天开始的。
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

  /**
   * 给会员群发消息（P7）。**默认关着**。
   *
   * <p>后端已经就绪（频次闸、退订、跳过统计都在），端上两页也做完了 ——
   * 唯独差一件事：**灰度对象还没定**。方案里写着「先只对一家自己的测试商户开，
   * 观察一周退订率」，而这是整个系统里唯一会打扰真实用户的功能。
   *
   * <p>把入口藏起来而不是不发代码：代码留在包里，开的时候只改这一个值，
   * 不用重新走一遍发布。**要开之前先确认灰度商户是谁** —— 全量打开的第一天，
   * 如果文案或频次有问题，收到的人不会再给第二次机会。
   */
  memberReach: false,
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
  /**
   * 发放后多少天转为**可用**（售后期）。
   *
   * 发出来的分先进 `pending_balance`（可见不可用），过了售后期才挪进 `balance`。
   * 理由是退款：售后期内退了款要连分一起收回，而已经花掉的分收不回来。
   *
   * ⚠️ **这个值 0 也要走转正流程**，不能特判成「立即可用」——
   * 特判会让两条路径（立即 / 延迟）各写一套加余额的逻辑，
   * 而其中一条平时跑不到，坏了没人知道。
   */
  pendingDays: 7,
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

/**
 * 商家头像兜底。和 {@link GOODS_COVER_FALLBACK} 是同一个毛病的另一半：
 * `logo` 在后端是可空字段，**商家不上传是常态**，而 mock 里每家都带 emoji，
 * 所以接真后端之前一次都没露过。
 *
 * <p>空字符串渲染出来是一个 80rpx 的**灰色空方块** —— 不报错、不塌版，
 * 看着像头像正在加载，人会等一会儿才反应过来它就是这样。
 * 端上一律走 `merchant.logo || MERCHANT_LOGO_FALLBACK`，不许各页面各写各的。
 */
export const MERCHANT_LOGO_FALLBACK = "🏪";

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
  myMemberships: "/pages/my-memberships/index",
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
 * tab 页路径集合。**推送落点判断要用它**（ADR-018）：tab 页只能 switchTab 打开，
 * navigateTo 会静默失败 —— 点开推送却停在原地，与没推没有区别。
 */
export const TAB_ROUTES: ReadonlySet<string> = new Set(TABS.map((t) => t.route));

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
