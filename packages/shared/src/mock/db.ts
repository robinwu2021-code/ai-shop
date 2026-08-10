// 全域 mock 数据集。
// 约定（同 powerbank）：mock 必须**真改 db**，重开能读回；状态机在 mock 层强制，非法迁移抛错。
// 多语言：真实后端按 Accept-Language 返回已本地化的字符串，所以 mock 也在「出口」处本地化。
// 金额：最小货币单位整数。商品价按 `priceByMarket` **分别定价**（B6）；
//       划线价/卡面额/报价等派生值仍按汇率折算，它们不是定价。
// 图片：M0 用 emoji 占位（小程序无需图片域名白名单，全端可跑），接真图后替换为 URL。
import { CATEGORY_TYPE, FULFILLMENT, GROUP_BUY, TRADE_RULES, MOCK_DB_KEY } from "@shared/utils/constants";
import { currentLang } from "@shared/utils/locale";
import { currentCurrency } from "@shared/utils/money";
import { isoDate, todayAtLocal } from "@shared/utils/datetime";
import type {
  OrderItem,
  ServiceScope,
  MerchantApplyReq,
  MerchantApplyStatus,
  SpecTemplate,
  PickupPoint,
  DeliveryRule,
  MarketingCampaign,
  MerchantProfile,
  MerchantStaff,
  PaymentApplyment,
  Store,
  StoreProfile,
  Address,
  Message,
  PointRecord,
  AppointmentSlot,
  CartItem,
  Community,
  Coupon,
  CurrencyCode,
  Goods,
  GroupBuy,
  I18nText,
  Merchant,
  MerchantBrief,
  MerchantType,
  GroupRequest,
  Order,
  OrderStatus,
  Review,
  Sku,
  SpecGroup,
  User,
  UserCard,
} from "@shared/types";

const DAY = 86400_000;

/** 三语文案：[中, 英, 阿] */
function t(zh: string, en: string, ar: string): I18nText {
  return { "zh-CN": zh, en, ar };
}

export function pick(text: I18nText): string {
  return text[currentLang()] ?? text["zh-CN"];
}

/**
 * 汇率**只用于给老种子数据补一个初始价**，不参与运行时取价。
 *
 * 为什么不能用汇率换算当定价（B6）：
 *   1. 换出来的价没有价格心理学 —— ¥29.9 换成 $4.19，不是任何人会标的价
 *   2. 汇率一动全店价格跟着抖，而商家并没有调价
 *   3. 各市场的成本结构不同（运费、税、竞品），本来就该分别定
 *
 * 真源是 `priceByMarket`，见 `seedPrices()`。
 */
const FX: Record<CurrencyCode, number> = { CNY: 1, USD: 0.14, AED: 0.51 };

/** 老种子只有一个 CNY 价，用汇率生成各市场初始价 —— 仅作种子，商家可逐个改 */
function seedPrices(cnyMinor: number): Partial<Record<CurrencyCode, number>> {
  return {
    CNY: cnyMinor,
    USD: Math.round(cnyMinor * FX.USD),
    AED: Math.round(cnyMinor * FX.AED),
  };
}

/**
 * 按市场取价。**未在该市场定价 = 不在该市场售卖**，返回 undefined ——
 * 而不是用汇率兜个底：错价上架比不上架危险得多。
 */
function priceIn(byMarket: Partial<Record<CurrencyCode, number>> | undefined, fallbackCny: number): number | undefined {
  const cur = currentCurrency();
  if (byMarket && byMarket[cur] !== undefined) return byMarket[cur];
  // 没有 priceByMarket 的老数据（商家还没分别定价）回退到汇率换算，
  // 并在 TDD 里登记为待商家补录 —— 不是长期方案
  return Math.round(fallbackCny * FX[cur]);
}

/** 今日截单：市场本地时区的 21:00 */
function todayCutoff(): number {
  return todayAtLocal(TRADE_RULES.freshCutoffTime);
}

/** 未来 N 天的预约时段 */
function buildSlots(times: string[]): AppointmentSlot[] {
  const out: AppointmentSlot[] = [];
  for (let i = 1; i <= TRADE_RULES.appointmentWindowDays; i += 1) {
    out.push({
      date: isoDate(Date.now() + i * DAY),
      times: times.map((time, idx) => ({ time, left: ((i + idx) % 3) + 1 })),
    });
  }
  return out;
}

interface SkuSeed extends Omit<Sku, "spec" | "optionValues"> {
  optionValues: I18nText[];
  /** 按市场分别定价（真源）。未填的市场不在该市场售卖 */
  priceByMarket?: Partial<Record<CurrencyCode, number>>;
}

interface SpecGroupSeed {
  name: I18nText;
  options: I18nText[];
  /** 模板编码（跨商家统一口径）。手输的规格没有 */
  optionCodes?: (string | undefined)[];
  templateNo?: string;
}

interface GoodsSeed
  extends Omit<
    Goods,
    | "title"
    | "subtitle"
    | "skus"
    | "specGroups"
    | "arrivalDesc"
    | "storeName"
    | "origin"
    | "price"
    | "originPrice"
    | "virtual"
    | "merchant"
  > {
  /** 只存引用，toGoods 时按当前语言拍平成 MerchantBrief */
  merchantNo: string;
  title: I18nText;
  subtitle: I18nText;
  specGroups: SpecGroupSeed[];
  skus: SkuSeed[];
  arrivalDesc?: I18nText;
  storeName?: I18nText;
  origin?: I18nText;
  price: number;
  originPrice?: number;
  /** 商品级按市场定价（展示价）。SKU 级各自还有一份 */
  priceByMarket?: Partial<Record<CurrencyCode, number>>;
  virtual?: { deliverDesc: I18nText };
}

/** 种子 → 契约对象（按当前语言与货币拍平） */
export function toGoods(seed: GoodsSeed): Goods {
  const specGroups: SpecGroup[] = seed.specGroups.map((g) => ({
    name: pick(g.name),
    options: g.options.map(pick),
    // 出口处一并带出：编辑页读回商品时要知道哪些选项还算模板值
    optionCodes: g.optionCodes,
    templateNo: g.templateNo,
  }));
  return {
    ...seed,
    merchant: merchantBrief(seed.merchantNo),
    title: pick(seed.title),
    subtitle: pick(seed.subtitle),
    price: priceIn(seed.priceByMarket, seed.price)!,
    // 划线价是**派生展示值**（用来标折扣），不是定价，跟着实售价按汇率走即可
    originPrice: seed.originPrice ? Math.round(seed.originPrice * FX[currentCurrency()]) : undefined,
    specGroups,
    skus: seed.skus.map((s) => {
      const optionValues = s.optionValues.map(pick);
      return {
        ...s,
        optionValues,
        // 展示文案由后端拼（多语言分隔符不同），端上不自己拼
        spec: optionValues.join(" · "),
        price: priceIn(s.priceByMarket, s.price)!,
        originPrice: s.originPrice ? Math.round(s.originPrice * FX[currentCurrency()]) : undefined,
      };
    }),
    arrivalDesc: seed.arrivalDesc ? pick(seed.arrivalDesc) : undefined,
    storeName: seed.storeName ? pick(seed.storeName) : undefined,
    origin: seed.origin ? pick(seed.origin) : undefined,
    card: seed.card
      ? {
          ...seed.card,
          faceValueMinor: seed.card.faceValueMinor
            ? Math.round(seed.card.faceValueMinor * FX[currentCurrency()])
            : undefined,
        }
      : undefined,
    virtual: seed.virtual ? { deliverDesc: pick(seed.virtual.deliverDesc) } : undefined,
  };
}

// ---------------------------------------------------------------- 商家
//
// 一期平台方是唯一入驻方（M001），其余是二期开放入驻后的样子 —— 现在放进来是为了让
// 「商家展示 / 评分 / 商家详情」这条链路在一期就跑通，二期只是数据变多，页面不用改。

interface MerchantSeed {
  merchantNo: string;
  name: I18nText;
  logo: string;
  type: MerchantType;
  desc: I18nText;
  salesCount: number;
  verified: boolean;
  /** 经营范围。见 SERVICE_SCOPE —— 决定这家店的货能卖到哪 */
  serviceScope: ServiceScope;
  /** 覆盖社区，仅 scope=COMMUNITY 有意义 */
  serviceCommunityNos: string[];
  /** 覆盖城市，仅 scope=CITY 有意义 */
  serviceCityCode?: string;
  /** 距 CM001 的距离（米）。真实系统按用户当前社区实时算 */
  distanceFromCM001: number;
  /** 选定报价后不履约的次数 */
  breachCount: number;
  address?: I18nText;
  openHours?: string;
  joinedAt: number;
  tags: I18nText[];
}

const merchantSeeds: MerchantSeed[] = [
  {
    merchantNo: "M001",
    breachCount: 0,
    // 平台自营卖的是卡券、虚拟商品和快递百货，没有履约半径
    serviceScope: "PLATFORM",
    serviceCommunityNos: [],
    distanceFromCM001: 0,
    name: t("邻里优选自营", "Neighbourly Select", "نيبرلي المختارة"),
    logo: "🏪",
    // 「平台自营」不是主体类型（ADR-010）—— 自营的主体也是个企业。
  // 一期没有真实自营商家，演示数据按企业处理
  type: "ENTERPRISE",
    desc: t(
      "平台自营，日用百货与卡券由平台直采直供",
      "Platform-operated. Household goods and cards sourced directly.",
      "تُدار من المنصة. مستلزمات المنزل والبطاقات بتوريد مباشر.",
    ),
    salesCount: 8420,
    verified: true,
    openHours: "00:00–24:00",
    joinedAt: Date.now() - 400 * DAY,
    tags: [t("平台自营", "Platform", "المنصة"), t("闪电退款", "Fast refund", "استرجاع سريع")],
  },
  {
    merchantNo: "M002",
    // 生鲜靠自提点，只做谈得下来的这两个小区
    serviceScope: "COMMUNITY",
    serviceCommunityNos: ["CM001", "CM002"],
    distanceFromCM001: 1800,
    breachCount: 0,
    name: t("阿明果蔬合作社", "Aming Fresh Co-op", "تعاونية أمينغ للطازج"),
    logo: "🥕",
    type: "INDIVIDUAL",
    desc: t(
      "本地农户合作社，当日采摘次日直达自提点",
      "Local farmers' co-op. Picked today, at your pickup point tomorrow.",
      "تعاونية مزارعين محليين. يُقطف اليوم ويصل غدًا.",
    ),
    salesCount: 2100,
    verified: true,
    address: t("杭州市余杭区良渚街道", "Liangzhu St, Yuhang, Hangzhou", "شارع ليانغتشو، يوهانغ"),
    openHours: "05:00–14:00",
    joinedAt: Date.now() - 210 * DAY,
    tags: [t("个体户", "Sole trader", "تاجر فردي"), t("产地直发", "Farm direct", "من المزرعة")],
  },
  {
    merchantNo: "M003",
    // 家政是上门服务，能跑全市 —— 但进不了任何自提点，履约只能走上门
    serviceScope: "CITY",
    serviceCommunityNos: [],
    serviceCityCode: "330100",
    distanceFromCM001: 950,
    breachCount: 1,
    name: t("邻里家政", "Neighbourly Home Care", "خدمات الجوار المنزلية"),
    logo: "🧰",
    type: "ENTERPRISE",
    desc: t(
      "持证家政团队，家电清洗与保洁上门服务",
      "Certified home-care team. Appliance cleaning and housekeeping.",
      "فريق معتمد للعناية المنزلية وتنظيف الأجهزة.",
    ),
    salesCount: 640,
    verified: true,
    address: t("杭州市西湖区文三路 88 号", "88 Wensan Rd, West Lake", "٨٨ شارع ونسان"),
    openHours: "08:00–20:00",
    joinedAt: Date.now() - 150 * DAY,
    tags: [t("企业商家", "Company", "شركة"), t("持证上门", "Certified", "معتمد")],
  },
  {
    merchantNo: "M004",
    // 理发要到店，出了这个小区没人会走过来
    serviceScope: "COMMUNITY",
    serviceCommunityNos: ["CM001"],
    distanceFromCM001: 260,
    breachCount: 0,
    name: t("SHOW 造型", "SHOW Studio", "استوديو شو"),
    logo: "💇",
    type: "INDIVIDUAL",
    desc: t(
      "社区连锁理发，到店核销，支持次卡",
      "Neighbourhood salon. Redeem in store, visit cards accepted.",
      "صالون الحي. الاستخدام في المتجر مع بطاقات الزيارات.",
    ),
    salesCount: 780,
    verified: false,
    address: t("阳光里小区商业街 12 号", "12 Sunnyside Retail St", "١٢ شارع صني سايد التجاري"),
    openHours: "10:00–22:00",
    joinedAt: Date.now() - 60 * DAY,
    tags: [t("个体户", "Sole trader", "تاجر فردي"), t("到店核销", "In store", "في المتجر")],
  },
];

/**
 * 商家评分 = 消费者评价均分 × 0.8 + 订单量得分 × 0.2。
 * 只按评价算的话，新商家一条五星就能顶满；把订单量按对数折算进来，
 * 让「卖得多且评价好」才拿得到高分，也避免刷单少量评价就冲顶。
 * ⚠️ 真实权重要业务定，这里是可跑通的占位算法 —— 见 TDD 待办。
 */
function computeRating(merchantNo: string): { rating: number; ratingCount: number } {
  const rs = db.reviews.filter((r) => r.merchantNo === merchantNo);
  const seed = merchantSeeds.find((m) => m.merchantNo === merchantNo);
  if (!rs.length) return { rating: seed?.verified ? 4.8 : 4.5, ratingCount: 0 };
  const avg = rs.reduce((s, r) => s + r.rating, 0) / rs.length;
  const volume = Math.min(1, Math.log10((seed?.salesCount ?? 0) + 1) / 4); // 万单封顶
  const score = avg * 0.8 + (3 + volume * 2) * 0.2;
  return { rating: Math.round(Math.min(5, score) * 10) / 10, ratingCount: rs.length };
}

export function merchantBrief(merchantNo: string): MerchantBrief {
  const seed = merchantSeeds.find((m) => m.merchantNo === merchantNo) ?? merchantSeeds[0]!;
  return {
    merchantNo: seed.merchantNo,
    name: pick(seed.name),
    logo: seed.logo,
    rating: computeRating(seed.merchantNo).rating,
    verified: seed.verified,
    breachCount: seed.breachCount,
  };
}

export function toMerchant(merchantNo: string): Merchant {
  const seed = merchantSeeds.find((m) => m.merchantNo === merchantNo);
  if (!seed) throw new Error(`商家不存在：${merchantNo}`);
  const { rating, ratingCount } = computeRating(merchantNo);
  return {
    merchantNo: seed.merchantNo,
    name: pick(seed.name),
    logo: seed.logo,
    verified: seed.verified,
    breachCount: seed.breachCount,
    type: seed.type,
    desc: pick(seed.desc),
    rating,
    ratingCount,
    salesCount: seed.salesCount,
    goodsCount: goodsSeeds.filter((g) => g.merchantNo === merchantNo).length,
    address: seed.address ? pick(seed.address) : undefined,
    openHours: seed.openHours,
    joinedAt: seed.joinedAt,
    serviceScope: seed.serviceScope,
    serviceCommunityNos: seed.serviceCommunityNos,
    serviceCityCode: seed.serviceCityCode,
    distance: seed.distanceFromCM001,
    tags: seed.tags.map(pick),
    // 分维度评分：真实系统按评价的分项均值算，这里由总分派生出可展示的三档
    scores: {
      goods: Math.round(Math.min(5, rating + 0.1) * 10) / 10,
      service: Math.round(Math.min(5, rating - 0.1) * 10) / 10,
      speed: Math.round(Math.min(5, rating) * 10) / 10,
    },
  };
}

const ARRIVE = t(
  "次日 16:00 后到自提点",
  "At pickup point after 4 PM tomorrow",
  "في نقطة الاستلام بعد ٤ عصر غدًا",
);

const goodsSeeds: GoodsSeed[] = [
  // ---------------------------------------------------------------- 生鲜
  {
    goodsNo: "G001",
    points: 30,
    merchantNo: "M002",
    title: t("山东烟台红富士苹果", "Yantai Fuji Apples", "تفاح فوجي من يانتاي"),
    subtitle: t("脆甜多汁 · 产地直发", "Crisp & sweet · direct from source", "مقرمش وحلو · مباشرة من المصدر"),
    cover: "🍎",
    images: ["🍎"],
    type: CATEGORY_TYPE.FRESH,
    categoryNo: "C_FRUIT",
    price: 2980,
    originPrice: 3980,
    fulfillments: [FULFILLMENT.PICKUP, FULFILLMENT.DELIVERY],
    specGroups: [{ name: t("规格", "Size", "الحجم"), options: [t("约 5 斤", "approx. 2.5 kg", "نحو ٢٫٥ كجم"), t("约 10 斤", "approx. 5 kg", "نحو ٥ كجم")] }],
    skus: [
      { skuNo: "G001S1", optionValues: [t("约 5 斤", "approx. 2.5 kg", "نحو ٢٫٥ كجم")], price: 2980, originPrice: 3980, stock: 200, nominalGram: 2500 },
      { skuNo: "G001S2", optionValues: [t("约 10 斤", "approx. 5 kg", "نحو ٥ كجم")], price: 5580, originPrice: 7580, stock: 80, nominalGram: 5000 },
    ],
    sales: 1240,
    // 商家开放的拼团档：够 3 人享此价。不配这个字段的商品就不能发起团
    groupBuy: { minCount: 3, price: 2480 },
    cutoffAt: todayCutoff(),
    arrivalDesc: ARRIVE,
    origin: t("山东烟台", "Yantai, Shandong", "يانتاي، شاندونغ"),
    weighed: true,
    limitPerUser: 5,
    onSale: true,
  },
  {
    goodsNo: "G002",
    points: 12,
    merchantNo: "M002",
    title: t("本地绿叶菜组合", "Local Leafy Greens Box", "صندوق خضار ورقية محلية"),
    subtitle: t("当日采摘 · 三样搭配", "Picked today · 3 varieties", "قُطفت اليوم · ٣ أصناف"),
    cover: "🥬",
    images: ["🥬"],
    type: CATEGORY_TYPE.FRESH,
    categoryNo: "C_VEG",
    price: 1280,
    // 邻里自提：这条链路（ADR-005）此前**没有任何种子商品支持**，
    // 于是「送到发起人家里」整条分支从未被验证过 —— 由 mock-coverage 体检查出。
    // 绿叶菜正是最典型的品：单价低、凑单送到邻居家最划算
    fulfillments: [FULFILLMENT.PICKUP, FULFILLMENT.DELIVERY, FULFILLMENT.NEIGHBOR_PICKUP],
    specGroups: [{ name: t("规格", "Size", "الحجم"), options: [t("3 样装", "3-variety box", "صندوق ٣ أصناف")] }],
    skus: [{ skuNo: "G002S1", optionValues: [t("3 样装", "3-variety box", "صندوق ٣ أصناف")], price: 1280, stock: 150 }],
    sales: 860,
    groupBuy: { minCount: 3, price: 1080 },
    cutoffAt: todayCutoff(),
    arrivalDesc: ARRIVE,
    origin: t("本地农场", "Local farm", "مزرعة محلية"),
    limitPerUser: 3,
    onSale: true,
  },
  // ---------------------------------------------------------------- 日用品（多规格示例）
  {
    goodsNo: "G101",
    points: 60,
    merchantNo: "M001",
    title: t("洗衣液 大容量装", "Laundry Liquid · Bulk", "سائل غسيل · عبوة كبيرة"),
    subtitle: t("低泡易漂 · 两种香型", "Low suds · two scents", "رغوة قليلة · رائحتان"),
    cover: "🧴",
    images: ["🧴"],
    type: CATEGORY_TYPE.NORMAL,
    categoryNo: "C_CLEAN",
    price: 2990,
    originPrice: 3990,
    fulfillments: [FULFILLMENT.PICKUP, FULFILLMENT.EXPRESS],
    // ★ 多规格：容量 × 香型 → 4 个 SKU
    specGroups: [
      { name: t("容量", "Size", "الحجم"), options: [t("3kg", "3 kg", "٣ كجم"), t("5kg", "5 kg", "٥ كجم")] },
      { name: t("香型", "Scent", "الرائحة"), options: [t("薰衣草", "Lavender", "لافندر"), t("茉莉", "Jasmine", "ياسمين")] },
    ],
    skus: [
      { skuNo: "G101S1", optionValues: [t("3kg", "3 kg", "٣ كجم"), t("薰衣草", "Lavender", "لافندر")], price: 2990, originPrice: 3990, stock: 200 },
      { skuNo: "G101S2", optionValues: [t("3kg", "3 kg", "٣ كجم"), t("茉莉", "Jasmine", "ياسمين")], price: 2990, originPrice: 3990, stock: 0 },
      { skuNo: "G101S3", optionValues: [t("5kg", "5 kg", "٥ كجم"), t("薰衣草", "Lavender", "لافندر")], price: 4590, stock: 120 },
      { skuNo: "G101S4", optionValues: [t("5kg", "5 kg", "٥ كجم"), t("茉莉", "Jasmine", "ياسمين")], price: 4590, stock: 60 },
    ],
    sales: 3200,
    promotions: [{ type: "BUY_N_GET_M", buyN: 3, giftM: 1 }],
    groupBuy: { minCount: 5, price: 2390 },
    limitPerUser: 0,
    onSale: true,
  },
  {
    goodsNo: "G102",
    points: 40,
    merchantNo: "M001",
    title: t("抽纸 4 层加厚", "4-Ply Facial Tissue", "مناديل ورقية ٤ طبقات"),
    subtitle: t("整箱 24 包 · 家庭装", "Case of 24 · family pack", "كرتون ٢٤ · عبوة عائلية"),
    cover: "🧻",
    images: ["🧻"],
    type: CATEGORY_TYPE.NORMAL,
    categoryNo: "C_PAPER",
    price: 3990,
    originPrice: 5990,
    fulfillments: [FULFILLMENT.PICKUP, FULFILLMENT.EXPRESS],
    specGroups: [{ name: t("规格", "Pack", "العبوة"), options: [t("24 包", "24 packs", "٢٤ عبوة"), t("48 包", "48 packs", "٤٨ عبوة")] }],
    skus: [
      { skuNo: "G102S1", optionValues: [t("24 包", "24 packs", "٢٤ عبوة")], price: 3990, originPrice: 5990, stock: 300 },
      { skuNo: "G102S2", optionValues: [t("48 包", "48 packs", "٤٨ عبوة")], price: 7280, stock: 120 },
    ],
    sales: 2100,
    // 买 2 送 1：付 2 件钱，收到 3 件
    promotions: [{ type: "BUY_N_GET_M", buyN: 2, giftM: 1 }],
    limitPerUser: 0,
    onSale: true,
  },
  // ---------------------------------------------------------------- 服务（预约）
  {
    goodsNo: "G201",
    points: 500,
    merchantNo: "M003",
    title: t("家电深度清洗 · 空调", "Deep Clean · Air Conditioner", "تنظيف عميق · مكيف"),
    subtitle: t("上门拆洗 · 需预约时段", "At-home service · book a slot", "خدمة منزلية · احجز موعدًا"),
    cover: "❄️",
    images: ["❄️"],
    type: CATEGORY_TYPE.SERVICE,
    categoryNo: "C_HOME",
    price: 12800,
    originPrice: 19800,
    fulfillments: [FULFILLMENT.APPOINTMENT],
    specGroups: [{ name: t("机型", "Unit type", "نوع الوحدة"), options: [t("挂机", "Wall unit", "وحدة جدارية"), t("柜机", "Floor unit", "وحدة أرضية")] }],
    skus: [
      { skuNo: "G201S1", optionValues: [t("挂机", "Wall unit", "وحدة جدارية")], price: 12800, originPrice: 19800, stock: 50 },
      { skuNo: "G201S2", optionValues: [t("柜机", "Floor unit", "وحدة أرضية")], price: 19800, originPrice: 26800, stock: 30 },
    ],
    sales: 180,
    durationMin: 60,
    storeName: t("邻里家政（文三路店）", "Neighbourly Home Care", "خدمات الجوار المنزلية"),
    slots: buildSlots(["09:00", "11:00", "14:00", "16:00"]),
    limitPerUser: 0,
    onSale: true,
  },
  // ---------------------------------------------------------------- 服务（到店核销）
  {
    goodsNo: "G202",
    points: 80,
    merchantNo: "M004",
    title: t("洗剪吹 · 男士", "Cut & Style · Men", "قص وتصفيف · رجالي"),
    subtitle: t("到店核销 · 不限发型师", "Redeem in store · any stylist", "الاستخدام في المتجر · أي مصفف"),
    cover: "💈",
    images: ["💈"],
    type: CATEGORY_TYPE.SERVICE,
    categoryNo: "C_BEAUTY",
    price: 2900,
    originPrice: 5800,
    fulfillments: [FULFILLMENT.STORE_VERIFY],
    specGroups: [{ name: t("项目", "Service", "الخدمة"), options: [t("单次", "Single visit", "زيارة واحدة")] }],
    skus: [{ skuNo: "G202S1", optionValues: [t("单次", "Single visit", "زيارة واحدة")], price: 2900, originPrice: 5800, stock: 100 }],
    sales: 640,
    durationMin: 45,
    storeName: t("SHOW 造型（阳光里店）", "SHOW Studio (Sunnyside)", "استوديو شو (صني سايد)"),
    limitPerUser: 2,
    onSale: true,
  },
  // ---------------------------------------------------------------- 虚拟商品
  {
    goodsNo: "G301",
    points: 15,
    merchantNo: "M001",
    title: t("视频会员月卡", "Streaming Membership · 1 Month", "اشتراك بث · شهر"),
    subtitle: t("兑换码直发 · 全平台通用", "Code delivered instantly", "يُرسل الرمز فورًا"),
    cover: "🎬",
    images: ["🎬"],
    type: CATEGORY_TYPE.VIRTUAL,
    categoryNo: "C_DIGITAL",
    price: 1500,
    originPrice: 2500,
    fulfillments: [FULFILLMENT.INSTANT],
    specGroups: [{ name: t("时长", "Duration", "المدة"), options: [t("1 个月", "1 month", "شهر"), t("3 个月", "3 months", "٣ أشهر")] }],
    skus: [
      { skuNo: "G301S1", optionValues: [t("1 个月", "1 month", "شهر")], price: 1500, originPrice: 2500, stock: 9999 },
      { skuNo: "G301S2", optionValues: [t("3 个月", "3 months", "٣ أشهر")], price: 4000, originPrice: 7500, stock: 9999 },
    ],
    sales: 5600,
    virtual: {
      deliverDesc: t(
        "支付成功后立即发码，可在订单详情查看",
        "Code issued right after payment, see order detail",
        "يُصدر الرمز فور الدفع، انظر تفاصيل الطلب",
      ),
    },
    limitPerUser: 5,
    onSale: true,
  },
  // ---------------------------------------------------------------- 卡券
  {
    goodsNo: "G401",
    points: 0,
    merchantNo: "M001",
    title: t("生鲜储值卡 200 元", "Fresh Store Card 200", "بطاقة رصيد للطازج ٢٠٠"),
    subtitle: t("充 200 得 220 · 一年有效", "Pay 200, get 220 · valid 1 year", "ادفع ٢٠٠ واحصل ٢٢٠ · صالحة سنة"),
    cover: "💳",
    images: ["💳"],
    type: CATEGORY_TYPE.CARD,
    categoryNo: "C_CARD",
    price: 20000,
    originPrice: 22000,
    fulfillments: [FULFILLMENT.INSTANT],
    specGroups: [{ name: t("面值", "Value", "القيمة"), options: [t("200 元", "200", "٢٠٠")] }],
    skus: [{ skuNo: "G401S1", optionValues: [t("200 元", "200", "٢٠٠")], price: 20000, originPrice: 22000, stock: 500 }],
    sales: 320,
    card: { faceValueMinor: 22000, validDays: 365 },
    limitPerUser: 0,
    onSale: true,
  },
  {
    goodsNo: "G402",
    points: 200,
    merchantNo: "M004",
    title: t("洗剪吹 5 次卡", "Cut & Style · 5 Visits", "بطاقة ٥ زيارات للقص"),
    subtitle: t("按次核销 · 半年有效", "Redeem per visit · valid 6 months", "استخدام لكل زيارة · صالحة ٦ أشهر"),
    cover: "🎟️",
    images: ["🎟️"],
    type: CATEGORY_TYPE.CARD,
    categoryNo: "C_CARD",
    price: 11900,
    originPrice: 14500,
    fulfillments: [FULFILLMENT.INSTANT],
    specGroups: [{ name: t("次数", "Visits", "الزيارات"), options: [t("5 次", "5 visits", "٥ زيارات")] }],
    skus: [{ skuNo: "G402S1", optionValues: [t("5 次", "5 visits", "٥ زيارات")], price: 11900, originPrice: 14500, stock: 200 }],
    sales: 140,
    card: { timesTotal: 5, validDays: 180 },
    limitPerUser: 0,
    onSale: true,
  },
];

interface PickupSeed {
  pickupNo: string;
  name: I18nText;
  address: I18nText;
  distance: number;
  /** 承接这个点的入驻商家。ADR-005：自提点由商家承接，不再是团长个人 */
  hostMerchantNo: string;
  hostName: I18nText;
  hostAvatar: string;
  openHours: string;
  arrivalDesc: I18nText;
}

interface CommunitySeed {
  communityNo: string;
  /** 所属城市。全市范围的商家靠它判定可达 */
  cityCode: string;
  name: I18nText;
  address: I18nText;
  distance: number;
  pickups: PickupSeed[];
}

const communitySeeds: CommunitySeed[] = [
  {
    communityNo: "CM001",
    cityCode: "330100",
    name: t("阳光里小区", "Sunnyside Gardens", "حدائق صني سايد"),
    address: t("杭州市西湖区文三路 100 号", "100 Wensan Rd, West Lake", "١٠٠ شارع ونسان، ويست ليك"),
    distance: 320,
    pickups: [
      {
        pickupNo: "PK001",
        name: t("阳光里 3 幢自提点", "Sunnyside Block 3 Point", "نقطة صني سايد مبنى ٣"),
        address: t("阳光里小区 3 幢 101", "Block 3, Unit 101", "مبنى ٣، وحدة ١٠١"),
        distance: 320,
        hostMerchantNo: "M002",
        hostName: t("阿明果蔬合作社", "Aming Fresh Co-op", "تعاونية أمينغ للطازج"),
        hostAvatar: "🥕",
        openHours: "8:00–21:00",
        arrivalDesc: t("次日 16:00 后到点", "Arrives after 4 PM tomorrow", "يصل بعد ٤ عصر غدًا"),
      },
      {
        pickupNo: "PK002",
        name: t("阳光里西门便利店", "Sunnyside West Gate Store", "متجر بوابة صني سايد الغربية"),
        address: t("阳光里小区西门", "West gate, Sunnyside", "البوابة الغربية، صني سايد"),
        distance: 480,
        hostMerchantNo: "M004",
        hostName: t("SHOW 造型", "SHOW Studio", "استوديو شو"),
        hostAvatar: "💇",
        openHours: "7:00–23:00",
        arrivalDesc: t("次日 15:00 后到点", "Arrives after 3 PM tomorrow", "يصل بعد ٣ عصرًا غدًا"),
      },
    ],
  },
  {
    communityNo: "CM002",
    cityCode: "330100",
    name: t("翠苑一区", "Greenpark One", "غرين بارك ١"),
    address: t("杭州市西湖区翠苑街道", "Cuiyuan St, West Lake", "شارع تسوييوان، ويست ليك"),
    distance: 1250,
    pickups: [
      {
        pickupNo: "PK003",
        name: t("翠苑一区门房", "Greenpark One Lodge", "استقبال غرين بارك ١"),
        address: t("翠苑一区 1 号门", "Gate 1, Greenpark One", "البوابة ١، غرين بارك ١"),
        distance: 1250,
        hostMerchantNo: "M001",
        hostName: t("邻里优选自营", "Neighbourly Select", "نيبرلي المختارة"),
        hostAvatar: "🏪",
        openHours: "8:00–20:00",
        arrivalDesc: t("次日 17:00 后到点", "Arrives after 5 PM tomorrow", "يصل بعد ٥ مساءً غدًا"),
      },
    ],
  },
];

export function toCommunity(seed: CommunitySeed): Community {
  return {
    communityNo: seed.communityNo,
    cityCode: seed.cityCode,
    name: pick(seed.name),
    address: pick(seed.address),
    distance: seed.distance,
    pickups: seed.pickups.map((p) => ({
      pickupNo: p.pickupNo,
      name: pick(p.name),
      address: pick(p.address),
      distance: p.distance,
      hostMerchantNo: p.hostMerchantNo,
      hostName: pick(p.hostName),
      hostAvatar: p.hostAvatar,
      openHours: p.openHours,
      arrivalDesc: pick(p.arrivalDesc),
    })),
  };
}

const couponSeeds: (Omit<Coupon, "name" | "scopeDesc"> & {
  name: I18nText;
  scopeDesc: I18nText;
})[] = [
  {
    couponNo: "CP001",
    name: t("新人首单券", "First order voucher", "قسيمة أول طلب"),
    thresholdMinor: 0,
    discountMinor: 500,
    expireAt: Date.now() + 7 * DAY,
    received: false,
    scopeDesc: t("全场可用", "Valid storewide", "صالحة على كل المتجر"),
  },
  {
    couponNo: "CP002",
    name: t("生鲜满减", "Fresh discount", "خصم الطازج"),
    thresholdMinor: 5000,
    discountMinor: 800,
    expireAt: Date.now() + 3 * DAY,
    received: false,
    scopeDesc: t("限生鲜水果", "Fresh & fruit only", "الطازج والفواكه فقط"),
  },
];

/** 评价种子：三语内容太啰嗦，评价这类 UGC 真实场景本就是原文展示、不翻译 —— 这里也保持原文 */
const reviewSeeds: Review[] = [
  {
    reviewNo: "RV001", goodsNo: "G001", merchantNo: "M002",
    nickname: "楼上李姐", avatar: "👩", rating: 5,
    content: "苹果很脆，甜度够，5 斤装实际称重比标称还多一点，孩子很爱吃。",
    images: ["🍎", "📦"], spec: "约 5 斤",
    createdAt: Date.now() - 2 * DAY, likeCount: 12, liked: false,
    scores: { goods: 5, fulfillment: 5, service: 5 },
    reply: "感谢支持！我们每天早上现摘现发～",
  },
  {
    // **低分评价**：没有它，B 端的申诉入口（只对 ≤3 星显示）与平台端的申诉裁决台
    // 就永远没有样本 —— 两边的代码都在，链路却验不了。
    // 维度分故意拉开：货是好的、慢在履约 —— 这正是三维度要暴露的那类问题，
    // 只看总分 2.0 商家只会以为「东西不好」，然后去改错的地方。
    reviewNo: "RV010", goodsNo: "G001", merchantNo: "M002",
    nickname: "6 幢陈叔", avatar: "🧓", rating: 2,
    content: "说好次日到，等了两天才通知取货，菜都蔫了。东西本身没问题，就是太慢。",
    images: [], spec: "约 5 斤",
    createdAt: Date.now() - 1 * DAY, likeCount: 0, liked: false,
    scores: { goods: 4, fulfillment: 1, service: 2 },
  },
  {
    reviewNo: "RV002", goodsNo: "G001", merchantNo: "M002",
    nickname: "3 幢老王", avatar: "🧑", rating: 4,
    content: "整体不错，有两个磕碰，客服很快补了券，处理挺爽快。",
    images: [], spec: "约 10 斤",
    createdAt: Date.now() - 5 * DAY, likeCount: 3, liked: false,
  },
  {
    reviewNo: "RV003", goodsNo: "G002", merchantNo: "M002",
    nickname: "小区团子", avatar: "👧", rating: 5,
    content: "菜很新鲜，next day 到点就能取，比超市方便多了。",
    images: ["🥬"], spec: "3 样装",
    createdAt: Date.now() - 1 * DAY, likeCount: 8, liked: false,
  },
  {
    reviewNo: "RV004", goodsNo: "G201", merchantNo: "M003",
    nickname: "西湖住户", avatar: "🧔", rating: 5,
    content: "师傅准时上门，拆洗很彻底，走的时候还把阳台收拾了，好评。",
    images: ["❄️"], spec: "挂机",
    createdAt: Date.now() - 3 * DAY, likeCount: 21, liked: false,
    reply: "谢谢认可，后续保养有问题随时联系我们。",
  },
  {
    reviewNo: "RV005", goodsNo: "G202", merchantNo: "M004",
    nickname: "阳光里小陈", avatar: "👦", rating: 3,
    content: "手艺可以，就是周末要等位，建议加个预约。",
    images: [], spec: "单次",
    createdAt: Date.now() - 6 * DAY, likeCount: 5, liked: false,
  },
  {
    reviewNo: "RV006", goodsNo: "G101", merchantNo: "M001",
    nickname: "囤货达人", avatar: "🙂", rating: 5,
    content: "5kg 装很划算，薰衣草味道不冲，回购第三次了。",
    images: ["🧴"], spec: "5kg · 薰衣草",
    createdAt: Date.now() - 8 * DAY, likeCount: 15, liked: false,
  },
];

/**
 * 造一笔已完成的历史订单。只填**聚合常买真正会读到的字段**
 * （items / status / createdAt），其余按 Order 的必填给出合理值。
 * daysAgo 让三笔单有先后，好验证「同频次时按最近购买排」。
 */
function histOrder(
  orderNo: string,
  daysAgo: number,
  rows: [goodsNo: string, skuNo: string, price: number, qty: number][],
): Order {
  const at = Date.now() - daysAgo * DAY;
  const items = rows.map(([goodsNo, skuNo, price, qty]) => {
    const seed = goodsSeeds.find((g) => g.goodsNo === goodsNo)!;
    const sku = seed.skus.find((k) => k.skuNo === skuNo)!;
    return {
      goodsNo,
      merchantNo: seed.merchantNo,
      skuNo,
      title: pick(seed.title),
      cover: seed.cover,
      spec: sku.optionValues.map(pick).join(" · "),
      price,
      qty,
      type: seed.type,
    } as OrderItem;
  });
  const goodsMinor = items.reduce((n, it) => n + it.price * it.qty, 0);
  return {
    orderNo,
    status: "COMPLETED",
    fulfillment: "STORE_PICKUP",
    items,
    amount: {
      goodsMinor,
      freightMinor: 0,
      discountMinor: 0,
      payableMinor: goodsMinor,
      paidMinor: goodsMinor,
      pointsDeductMinor: 0,
      pointsUsed: 0,
      pointsEarn: 0,
      currency: "CNY",
    },
    pickupNo: "PK001",
    createdAt: at,
    timeline: [{ status: "COMPLETED", label: "已完成", at }],
    merchantNo: items[0]!.merchantNo,
  };
}

export const db = {
  user: {
    cUserNo: "CU10001",
    nickname: "邻居小张",
    avatar: "🙂",
    phone: "13800138000",
  } as User,

  /**
   * B 端当前登录的商家。
   * 与 db.user 共存于同一份 db 定义：订单/商品结构两端只有一处来源，不会漂移。
   * （运行时状态仍按 origin 隔离，跨端联动要等真后端 —— 见 TDD-b-app §4.4）
   * 初始为未入驻（NONE），走完 B 端入驻流程后变 ACTIVE 并绑定到某个 merchantSeed。
   */
  merchant: {
    merchantNo: "",
    name: "",
    logo: "🏪",
    status: "NONE",
    subject: "MICRO",
    tier: "SMALL",
    phone: "",
    isPickupPoint: false,
  } as MerchantProfile,

  /** 店铺门面（店主可改的部分）。C 端门店主页读的就是它 */
  store: {
    announcement: "今天到了新米和土鸡蛋，来早的挑得好",
    openHours: "06:30–21:00",
    address: "阳光里小区南门 · 张记粮油",
    featured: [] as string[],
    // 演示商家是社区生鲜：靠自提点履约，只做谈下来的两个小区
    serviceScope: "COMMUNITY",
    serviceCommunityNos: ["CM001", "CM002"],
  } as StoreProfile,

  /**
   * 门店。**默认只有一家**（= FREE 档），与生产默认额度一致 ——
   * mock 里塞三家的话，「只能开一家」这条最常被触发的限制在开发期永远看不到。
   */
  stores: [
    {
      storeNo: "ST-MOCK-1",
      name: "张记粮油",
      address: "阳光里小区南门 · 张记粮油",
      isDefault: true,
      status: "ACTIVE",
      payReady: true,
      staffCount: 1,
    },
  ] as Store[],

  /** 门店额度。改这个数就能在 mock 下体验 PRO/CHAIN 档 */
  storeQuota: 1,

  /** 员工。第一条是老板 —— 列表第一眼要能看出谁是老板 */
  staff: [
    {
      mchAccountNo: "SF-MOCK-OWNER",
      loginPhone: "138****8000",
      isOwner: true,
      status: "ACTIVE",
      roles: [],
    },
  ] as MerchantStaff[],

  /**
   * 收款进件。**默认停在 APPLYING** —— 演示环境也要能看到「店开了但还收不了钱」这个状态，
   * 它是真实世界里最常见的一种，做成 ACTIVE 就把这段界面藏起来了。
   */
  payment: {
    payChannel: "WECHAT",
    channelName: "微信支付",
    applyStatus: "APPLYING",
    canReceiveMoney: false,
    missing: ["settleAccount"],
    appliedAt: 0,
  } as PaymentApplyment,

  /**
   * 类目树。**编号与后端 V4__category_tree.sql、ops-web 的 mock 完全一致** ——
   * 三处对不上时的症状是「mock 上跑得通、连真库就找不到类目」，而三处各自都自洽，
   * 谁也不报错。
   *
   * ⚠️ 与 `CATEGORY_TYPE`（五品类）是两个正交维度：那个决定履约与合规、平台硬编码；
   * 这棵树决定归类与经营准入、运营可维护。
   */
  categories: [
    {
      categoryNo: "CAT100", parentNo: null, level: 1, name: "食品生鲜", icon: "", sort: 10,
      children: [
        {
          categoryNo: "CAT110", parentNo: "CAT100", level: 2, name: "蔬菜", icon: "", sort: 10,
          children: [
            { categoryNo: "CAT111", parentNo: "CAT110", level: 3, name: "叶菜", icon: "", sort: 10, children: [] },
            { categoryNo: "CAT112", parentNo: "CAT110", level: 3, name: "根茎菜", icon: "", sort: 20, children: [] },
          ],
        },
        {
          categoryNo: "CAT120", parentNo: "CAT100", level: 2, name: "水果", icon: "", sort: 20,
          children: [
            { categoryNo: "CAT121", parentNo: "CAT120", level: 3, name: "浆果", icon: "", sort: 10, children: [] },
          ],
        },
      ],
    },
    {
      categoryNo: "CAT200", parentNo: null, level: 1, name: "日用百货", icon: "", sort: 20,
      children: [
        { categoryNo: "CAT210", parentNo: "CAT200", level: 2, name: "纸品清洁", icon: "", sort: 10, children: [] },
      ],
    },
    { categoryNo: "CAT300", parentNo: null, level: 1, name: "生活服务", icon: "", sort: 30, children: [] },
    { categoryNo: "CAT400", parentNo: null, level: 1, name: "卡券", icon: "", sort: 40, children: [] },
  ],

  /**
   * 规格模板。平台预置的按类目给，商家存的常用模板追加在后面。
   * 平台模板的 code 是**跨商家统一**的 —— 这正是它能做聚合而自由输入不能的原因。
   */
  specTemplates: [
    {
      templateNo: "ST_FRESH_WEIGHT",
      scope: "PLATFORM" as const,
      categoryType: CATEGORY_TYPE.FRESH,
      name: "重量",
      options: [
        { code: "W500G", label: "500g" },
        { code: "W1JIN", label: "1 斤" },
        { code: "W2JIN", label: "2 斤" },
        { code: "W5JIN", label: "5 斤" },
        { code: "W10JIN", label: "10 斤" },
      ],
    },
    {
      templateNo: "ST_FRESH_GRADE",
      scope: "PLATFORM" as const,
      categoryType: CATEGORY_TYPE.FRESH,
      name: "等级",
      options: [
        { code: "G_STD", label: "标准果" },
        { code: "G_BIG", label: "大果" },
        { code: "G_GIFT", label: "礼盒装" },
      ],
    },
    {
      templateNo: "ST_GOODS_PACK",
      scope: "PLATFORM" as const,
      categoryType: CATEGORY_TYPE.NORMAL,
      name: "包装",
      options: [
        { code: "P_BAG", label: "袋装" },
        { code: "P_BOX", label: "盒装" },
        { code: "P_BARREL", label: "桶装" },
        { code: "P_CASE", label: "整箱" },
      ],
    },
    {
      templateNo: "ST_GOODS_SCENT",
      scope: "PLATFORM" as const,
      categoryType: CATEGORY_TYPE.NORMAL,
      name: "香型",
      options: [
        { code: "S_LAV", label: "薰衣草" },
        { code: "S_JAS", label: "茉莉" },
        { code: "S_NONE", label: "无香" },
      ],
    },
    {
      templateNo: "ST_SERVICE_DURATION",
      scope: "PLATFORM" as const,
      categoryType: CATEGORY_TYPE.SERVICE,
      name: "时长",
      options: [
        { code: "D_60", label: "60 分钟" },
        { code: "D_90", label: "90 分钟" },
        { code: "D_120", label: "120 分钟" },
      ],
    },
  ] as SpecTemplate[],

  /**
   * 上一次提交的入驻申请（内容 + 审核进度，**一条记录**）。
   *
   * <p>曾经拆成「内容」与「状态」两份存。拆开的结果是 B 端草稿回显读一份、
   * C 端进度查询读另一份，两份各自更新 —— 而后端 `usr_merchant_apply` 只有一行。
   * 于是同一次提交在两个端上能显示成两件事，mock 也就不再是后端的替身。
   *
   * <p>驳回后要能改了再交：整份带回来，因为驳回往往只是缺一张执照。
   */
  merchantApply: null as MerchantApplyStatus | null,

  /** 我收藏的店（C-ST-07「常去的店」） */
  favoriteStores: [] as string[],

  /** 商家配的营销活动（B-11.8） */
  campaigns: [] as MarketingCampaign[],

  /** 商家自送规则（按当前登录商家，一期单商家够用） */
  deliveryRule: {
    radius: 3000,
    minOrderMinor: 2000,
    feeMinor: 300,
    freeThresholdMinor: 5000,
  } as DeliveryRule,

  goodsSeeds,
  merchantSeeds,
  communitySeeds,
  couponSeeds,
  reviews: reviewSeeds,

  addresses: [
    {
      addressId: "AD001",
      name: "张先生",
      phone: "13800138000",
      region: "浙江省 杭州市 西湖区",
      detail: "阳光里小区 3 幢 2 单元 601",
      isDefault: true,
      tag: "家",
    },
  ] as Address[],

  messages: [
    {
      messageNo: "MS001",
      type: "SYSTEM",
      title: "欢迎来到社区好物",
      body: "选好你的自提点，就能看到邻居们今天在买什么。",
      read: false,
      at: Date.now() - 3 * 86400_000,
    },
    {
      messageNo: "MS002",
      type: "MARKETING",
      title: "今晚 21:00 截单",
      body: "生鲜次日到自提点，错过要等明天。",
      read: false,
      at: Date.now() - 6 * 3600_000,
    },
  ] as Message[],

  /** C 端积分流水（倒序）。余额由流水推导，不单独存 —— 两处存会对不上 */
  points: [] as PointRecord[],
  /** B 端（商家/团长）收到的积分流水 */
  merchantPoints: [] as PointRecord[],

  cart: [] as CartItem[],
  /*
   * 种几笔已完成的历史订单。**不是为了好看** —— 首页「再来一单」和店内常买清单
   * 都是从订单聚合出来的，没有历史这两个模块永远是空的，等于没法验证也没法演示。
   * 频次刻意做出差异（绿叶菜 3 次 > 苹果 2 次 > 洗衣液 1 次），
   * 好检验排序走的是**次数**而不是时间。
   */
  orders: [
    histOrder("SO901", 26, [["G002", "G002S1", 1280, 1]]),
    histOrder("SO902", 19, [
      ["G002", "G002S1", 1280, 1],
      ["G001", "G001S1", 2980, 1],
    ]),
    histOrder("SO903", 11, [
      ["G002", "G002S1", 1280, 2],
      ["G001", "G001S1", 2980, 1],
      ["G101", "G101S1", 2990, 1],
    ]),
  ] as Order[],
  cards: [] as UserCard[],

  /**
   * 邻里求团：需求先于供给。存全量（含报价）——
   * 报价是商家侧写入的，C 端只读，所以不像团那样需要实时推导。
   */
  requests: [
    {
      requestNo: "RQ001",
      initiatorNickname: "3 幢老王",
      initiatorAvatar: "🧑",
      pickupNo: "PK001",
      title: "想团儿童护脊床垫，1.2m",
      desc: "孩子上小学要换床，想找个椰棕的，1.2×2.0。楼里有同款需求的一起，量大能便宜不少。",
      images: ["🛏️", "📏"],
      expectQty: 1,
      budgetMinor: 80000,
      status: "QUOTING",
      interestedCount: 6,
      interested: false,
      neighbours: [
        { avatar: "🧑", nickname: "3 幢老王" },
        { avatar: "👩", nickname: "王姐" },
        { avatar: "👵", nickname: "李阿姨" },
        { avatar: "👧", nickname: "团子妈" },
        { avatar: "🧔", nickname: "西湖住户" },
        { avatar: "👦", nickname: "小陈" },
      ],
      quotes: [
        {
          quoteNo: "QT001",
          merchantNo: "M001",
          priceMinor: 69900,
          minCount: 5,
          desc: "天然椰棕 + 3E 环保胶，1.2×2.0×10cm，含面套可拆洗。满 5 单送货上门并安装。",
          validUntil: Date.now() + 5 * DAY,
          createdAt: Date.now() - 20 * 3600_000,
          chosen: false,
          revisions: [],
          locked: false,
        },
        {
          quoteNo: "QT002",
          merchantNo: "M003",
          priceMinor: 75800,
          minCount: 3,
          desc: "山棕 + 乳胶复合，偏软，1.2×2.0×12cm。3 单即可发，含旧床垫回收。",
          validUntil: Date.now() + 3 * DAY,
          createdAt: Date.now() - 8 * 3600_000,
          chosen: false,
          // 这家改过价（涨了）—— 改价留痕并公示，不审核也让人看得见
          revisions: [{ priceMinor: 72900, at: Date.now() - 30 * 3600_000 }],
          locked: false,
        },
      ],
      createdAt: Date.now() - 2 * DAY,
      expireAt: Date.now() + 5 * DAY,
    },
    {
      requestNo: "RQ002",
      initiatorNickname: "团子妈",
      initiatorAvatar: "👧",
      pickupNo: "PK001",
      title: "求团实验小学秋季校服（备用一套）",
      desc: "学校发的不够换洗，想再买一套备用。同校家长一起报个数，量够了找厂家做。",
      images: ["👕"],
      expectQty: 2,
      status: "OPEN",
      interestedCount: 3,
      interested: false,
      neighbours: [
        { avatar: "👧", nickname: "团子妈" },
        { avatar: "👩", nickname: "王姐" },
        { avatar: "🧑", nickname: "3 幢老王" },
      ],
      quotes: [],
      createdAt: Date.now() - 6 * 3600_000,
      expireAt: Date.now() + 6 * DAY,
    },
  ] as RequestSeed[],

  /** 商家团：只存「谁发起、谁参与」，价格与成团状态由 buildGroupBuy 按商家配置实时算 */
  groupSeeds: [
    {
      groupNo: "GB001",
      goodsNo: "G001",
      pickupNo: "PK001",
      initiatorNickname: "王姐",
      initiatorAvatar: "👩",
      createdAt: Date.now() - 3 * 3600_000,
      members: [
        { avatar: "👩", nickname: "王姐", qty: 1 },
        { avatar: "🧑", nickname: "老陈", qty: 2 },
      ],
      joined: false,
    },
  ] as GroupSeed[],

  seq: 1000,
};

// ---------------------------------------------------------------- 持久化
//
// mock 要**像真后端一样**：写进去的东西刷新后还在。购物车、订单、卡包都是服务端状态，
// 只放内存的话一刷新就没了 —— 那不是「后端行为」，是「假的后端行为」，
// 会掩盖掉真接后端时才暴露的问题。
//
// 库存不落盘：库存是种子数据的一部分，每次启动重新播种。真后端不会这样，
// 但对 mock 而言重置库存比持久化一份会漂移的副本更可预期。

// 带端前缀，见 constants 里 NS 的说明：两端的 mock「数据库」不能共用一个 key
const PERSIST_KEY = MOCK_DB_KEY;

/**
 * 落盘范围：**默认全存**，只显式排除少数几项。
 *
 * 之前这里是一张手写白名单，代价是每加一个 db 字段就得记得同步加一行 ——
 * 已经因此漏过三次（商家开的团、报的价、评价回复；新建的商品；配的营销活动），
 * 症状都是「操作成功、刷新就没了」，而且不报错，只能靠手测撞见。
 *
 * 现在反过来：新增字段自动持久化，漏配的后果从「静默丢数据」变成「多存了一点」。
 * 后者最多浪费几 KB storage，前者是 bug。
 */
const TRANSIENT_KEYS = [
  // 纯种子：结构与文案来自代码，用户改不了，存了反而会在改版后读回旧文案
  "communitySeeds",
  "merchantSeeds",
  "couponSeeds",
  // 评价正文同样来自种子，只有「点赞态」和「商家回复」是用户/商家产生的，单独存
  "reviews",
] as const;

type TransientKey = (typeof TRANSIENT_KEYS)[number];

/** 自动派生：db 去掉瞬时字段，再加上三项从种子里抽出来的用户态 */
type Persisted = Omit<typeof db, TransientKey> & {
  /** 已领取的券号 —— 券本身是种子，只有「谁领了」需要存 */
  receivedCoupons: string[];
  /** 评价点赞态 */
  likes: Record<string, boolean>;
  /** 商家对评价的回复 */
  replies: Record<string, string>;
  /** 商家申诉。与 reply 同理：评价正文是种子，**申诉是商家产生的**，必须单独存 */
  appeals: Record<string, NonNullable<Review["appeal"]>>;
};

let saveTimer: ReturnType<typeof setTimeout> | undefined;

/** 写操作后调用。合并 60ms 内的多次写，避免连续加购刷爆 storage */
export function persist(): void {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try {
      const rest = { ...db } as Record<string, unknown>;
      for (const k of TRANSIENT_KEYS) delete rest[k];

      const data = {
        ...rest,
        receivedCoupons: db.couponSeeds.filter((c) => c.received).map((c) => c.couponNo),
        likes: Object.fromEntries(db.reviews.map((r) => [r.reviewNo, r.liked])),
        replies: Object.fromEntries(
          db.reviews.filter((r) => r.reply).map((r) => [r.reviewNo, r.reply!]),
        ),
        appeals: Object.fromEntries(
          db.reviews.filter((r) => r.appeal).map((r) => [r.reviewNo, r.appeal!]),
        ),
      } as Persisted;

      uni.setStorageSync(PERSIST_KEY, JSON.stringify(data));
    } catch {
      // storage 满或被禁用时降级为纯内存，不影响本次会话可用性
    }
  }, 60);
}

export function restoreDb(): void {
  try {
    const raw = uni.getStorageSync(PERSIST_KEY) as string;
    if (!raw) return;
    const data = JSON.parse(raw) as Partial<Persisted> & Record<string, unknown>;

    // 与 persist 同一套规则：**存了什么就读回什么**，不再逐字段手写。
    // 手写的读回列表与写出列表是两处，容易只改一边 —— 那种 bug 表现为
    // 「存进去了但读不回来」，比不存更难查。
    const target = db as unknown as Record<string, unknown>;
    for (const key of Object.keys(data)) {
      if ((TRANSIENT_KEYS as readonly string[]).includes(key)) continue;
      if (
        key === "receivedCoupons" ||
        key === "likes" ||
        key === "replies" ||
        key === "appeals"
      ) {
        continue;
      }
      if (!(key in target)) continue; // 旧版本残留的字段直接忽略
      if (data[key] !== undefined) target[key] = data[key];
    }

    // 三项从种子里抽出来的用户态，回填到种子对象上
    const received = new Set(data.receivedCoupons ?? []);
    db.couponSeeds.forEach((c) => {
      if (received.has(c.couponNo)) c.received = true;
    });

    const replies = data.replies ?? {};
    const appeals = data.appeals ?? {};
    const likes = data.likes ?? {};
    db.reviews.forEach((r) => {
      if (replies[r.reviewNo]) r.reply = replies[r.reviewNo];
      if (appeals[r.reviewNo]) r.appeal = appeals[r.reviewNo];
      if (likes[r.reviewNo] && !r.liked) {
        r.liked = true;
        r.likeCount += 1;
      }
    });
  } catch {
    // 数据损坏（如改过结构）时丢弃，从空态重来，好过整个 mock 起不来
  }
}


/** 报价里只存 merchantNo，出口时拍平成 MerchantBrief（同商品的处理方式） */
interface QuoteSeed {
  quoteNo: string;
  merchantNo: string;
  priceMinor: number;
  minCount: number;
  desc: string;
  validUntil: number;
  createdAt: number;
  chosen: boolean;
  revisions: { priceMinor: number; at: number }[];
  locked: boolean;
}

interface RequestSeed extends Omit<GroupRequest, "quotes" | "pickupName"> {
  quotes: QuoteSeed[];
}

export function toGroupRequest(seed: RequestSeed): GroupRequest {
  const pickupSeed = db.communitySeeds
    .flatMap((c) => c.pickups)
    .find((p) => p.pickupNo === seed.pickupNo);
  return {
    ...seed,
    pickupName: pickupSeed ? pick(pickupSeed.name) : "",
    quotes: seed.quotes
      .map((q) => ({
        quoteNo: q.quoteNo,
        merchant: merchantBrief(q.merchantNo),
        priceMinor: Math.round(q.priceMinor * FX[currentCurrency()]),
        minCount: q.minCount,
        desc: q.desc,
        validUntil: q.validUntil,
        createdAt: q.createdAt,
        chosen: q.chosen,
        // 历史价也要换算货币，否则切市场后「曾报 ¥729」会串成另一个币种的数字
        revisions: q.revisions.map((r) => ({ priceMinor: Math.round(r.priceMinor * FX[currentCurrency()]), at: r.at })),
        locked: q.locked,
      }))
      // 报价按价格从低到高 —— 发起人最关心的就是谁更便宜
      .sort((a, b) => a.priceMinor - b.priceMinor),
  };
}

interface GroupSeed {
  groupNo: string;
  goodsNo: string;
  pickupNo: string;
  /** 邻里自提点：发起人勾了「送到我家」时有值（ADR-005，scope=GROUP_INSTANCE） */
  neighborPickup?: PickupPoint;
  /** 发起人是不是当前用户 —— mock 里用它区分「我发起的团」 */
  ownedByMe?: boolean;
  /** 整批到货已签收 */
  received?: boolean;
  /** 发起人 —— 团是用户自发建的，必然有一个发起人 */
  initiatorNickname: string;
  initiatorAvatar: string;
  createdAt: number;
  members: { avatar: string; nickname: string; qty: number }[];
  joined: boolean;
}

/**
 * 按拼团规则算出团的当前状态。
 * 价格不存库 —— 存下来就会与商品/规则漂移；商家配置的 groupBuy 是唯一真源。
 */
export function buildGroupBuy(seed: GroupSeed): GroupBuy {
  const goods = toGoods(findGoodsSeed(seed.goodsNo));
  const pickupSeed = db.communitySeeds
    .flatMap((c) => c.pickups)
    .find((p) => p.pickupNo === seed.pickupNo);

  const cfg = goods.groupBuy;
  if (!cfg) throw new Error(`商品未开放拼团：${seed.goodsNo}`);

  // 成团人数按「人」而非「份」计（一人买 3 份仍算 1 人）—— 口径待确认，见需求 §五之四 G3
  const joinedCount = seed.members.length;
  const reached = joinedCount >= cfg.minCount;

  // 截止时间取「团有效期」与「商品当日截单」的更早者 ——
  // 生鲜过了截单就发不出货，团再有效也没意义
  const byValidity = seed.createdAt + GROUP_BUY.validHours * 3600_000;
  const expireAt = goods.cutoffAt ? Math.min(byValidity, goods.cutoffAt) : byValidity;

  return {
    groupNo: seed.groupNo,
    goodsNo: seed.goodsNo,
    title: goods.title,
    cover: goods.cover,
    merchant: goods.merchant,
    initiatorNickname: seed.initiatorNickname,
    initiatorAvatar: seed.initiatorAvatar,
    pickupNo: seed.pickupNo,
    pickupName: pickupSeed ? pick(pickupSeed.name) : "",
    basePrice: goods.price,
    // 团购价是商品价的折扣档，跟着实售价的市场走
    groupPrice: Math.round(cfg.price * FX[currentCurrency()]),
    minCount: cfg.minCount,
    joinedCount,
    reached,
    need: Math.max(0, cfg.minCount - joinedCount),
    expireAt,
    members: seed.members,
    joined: seed.joined,
    neighborPickup: seed.neighborPickup,
    isOwner: !!seed.ownedByMe,
    // 送到发起人家时，取货点名与地址走临时点，不再指向门店
    ...(seed.neighborPickup
      ? { pickupNo: seed.neighborPickup.pickupNo, pickupName: seed.neighborPickup.name }
      : {}),
  };
}

/** 全量商品（已本地化 + 已换算货币） */
export function allGoods(): Goods[] {
  return db.goodsSeeds.map(toGoods);
}

export function findGoodsSeed(goodsNo: string): GoodsSeed {
  const g = db.goodsSeeds.find((x) => x.goodsNo === goodsNo);
  if (!g) throw new Error(`商品不存在：${goodsNo}`);
  return g;
}

export function nextNo(prefix: string): string {
  db.seq += 1;
  return `${prefix}${Date.now().toString().slice(-8)}${db.seq}`;
}

export function paginate<T>(list: T[], page = 1, size = 20) {
  const start = (page - 1) * size;
  return { records: list.slice(start, start + size), total: list.length, page, size };
}

/**
 * 模拟网络往返。**返回值是深拷贝**，这一点不是洁癖：
 *
 * 之前直接把 db 里的对象原样返回，于是 `order.value = await api.xxx()` 拿到的是
 * 同一个引用 —— Vue 判定「没变」，界面不刷新（填完退货运单号后卡片纹丝不动就是这么来的）。
 * 真实 HTTP 每次都给新对象，mock 也必须如此，否则页面在 mock 下能跑、接上后端才暴露，
 * 或者反过来：页面顺手改了返回值，等于偷偷改了「数据库」。
 */
export function delay<T>(data: T, ms = 200): Promise<T> {
  const copy = data === undefined || typeof data !== "object" ? data : structuredClone(data);
  return new Promise((resolve) => setTimeout(() => resolve(copy), ms));
}

/** 推一条站内消息。交易类的消息必须能点回订单，所以 link 是必填的实践约定 */
export function pushMessage(
  type: Message["type"],
  title: string,
  body: string,
  link?: string,
): void {
  db.messages.unshift({
    messageNo: nextNo("MS"),
    type,
    title,
    body,
    link,
    read: false,
    at: Date.now(),
  });
}

/**
 * 记一笔积分流水并返回变动后余额。
 * 余额由流水推导而不是单独存一个字段 —— 两处存迟早对不上，
 * 而积分能兑付成钱，对不上就是资损。
 */
export function pushPoint(
  ledger: PointRecord[],
  type: PointRecord["type"],
  points: number,
  title: string,
  orderNo?: string,
): PointRecord {
  const balanceAfter = pointBalance(ledger) + points;
  const rec: PointRecord = {
    recordNo: nextNo("PT"),
    type,
    points,
    title,
    orderNo,
    at: Date.now(),
    balanceAfter,
  };
  ledger.unshift(rec);
  return rec;
}

export function pointBalance(ledger: PointRecord[]): number {
  return ledger.reduce((s, r) => s + r.points, 0);
}

/**
 * 订单状态机 —— 非法迁移直接抛错。
 *
 * 没有 `REFUNDING`：那是**售后单**的状态，不是订单的。
 * 订单在售后期间保持它原本的状态（已完成的单照样能申请售后），
 * 退款真正到账才迁到 `REFUNDED`。
 * 做成订单状态就会强迫「已完成」和「退款中」二选一，而两者本就并存。
 */
const TRANSITIONS: Record<OrderStatus, OrderStatus[]> = {
  WAIT_PAY: ["PAID", "CANCELLED"],
  // 没有独立的「备货中」：后端从付款直接到 PAID（待发货），mock 与它保持一致
  PAID: ["ARRIVED", "SHIPPED", "COMPLETED", "REFUNDED", "CANCELLED"],
  ARRIVED: ["COMPLETED", "REFUNDED"],
  SHIPPED: ["COMPLETED", "REFUNDED"],
  COMPLETED: ["REFUNDED"],
  REFUNDED: [],
  CANCELLED: [],
};

export function assertTransition(from: OrderStatus, to: OrderStatus): void {
  if (!TRANSITIONS[from]?.includes(to)) {
    throw new Error(`非法状态迁移：${from} → ${to}`);
  }
}
