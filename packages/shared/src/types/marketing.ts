// 营销：券、活动、拼团与求团报价
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

import type { PickupPoint } from "./fulfillment";
import type { Merchant, MerchantBrief } from "./merchant";
import type { UserCoupon } from "./user";

/** 券的出资方。决定这张券的钱最后从谁账上扣 —— 平台券走平台预算，商家券从结算里扣 */
export type CouponFunder = "PLATFORM" | "MERCHANT";
/** 券类型。与后端 `MktCoupon` 的常量逐字一致 */
export type CouponType =
  /** 满减：减 `faceMinor` */
  | "FULL_CUT"
  /** 折扣：按 `discountRate` 打折，最多减 `maxDiscountMinor` */
  | "DISCOUNT";
/** 券状态。与后端 `MktCoupon` 一致；平台列表要靠它筛出被停的券 */
export type CouponStatus = "ACTIVE" | "PAUSED" | "ENDED";
/**
 * 优惠券模板。**字段与后端 `CouponVO` 一一对应**。
 *
 * 这里原先是一个被简化过的形状（`name` / `discountMinor` / `expireAt`），
 * 与后端一个都对不上，后果不是「少显示一块」而是**领券中心永远是空的**：
 * 页面按 `c.expireAt > now` 过滤，而后端发的是 `endAt` ——
 * `undefined > now` 恒 false，于是商家配好的券一张都露不出来，两边都不报错。
 *
 * <b>而且那个简化本身是错的</b>：`discountMinor` 一个数表达不了折扣券 ——
 * 折扣券要的是「打几折 + 最多减多少」。后端的形状才是对的，端上跟它。
 */
export interface Coupon {
  /** 券单号 */
  couponNo: string;
  /** 券名，如「满 50 减 5」 */
  title: string;
  /** 类型 */
  type: CouponType;
  /** 满减面额（最小货币单位）。`DISCOUNT` 券为 0 */
  faceMinor: number;
  /** 折扣**万分比**，8500 = 八五折。`FULL_CUT` 券为 0 */
  discountRate: number;
  /** 使用门槛（最小货币单位）。0 表示无门槛 */
  thresholdMinor: number;
  /** 折扣券封顶（最小货币单位）。仅 `DISCOUNT` 有意义 */
  maxDiscountMinor: number;
  /** 谁出这笔钱：平台 / 商家。**结算口径不同** */
  funder: CouponFunder;
  /** 商家券的归属商家；平台券为空 */
  merchantNo: string;
  /** 可领取/可用的时间窗 */
  startAt: number;
  /** 结束时刻（毫秒） */
  endAt: number;
  /** 剩余可领数量 */
  remain: number;
  /** 当前用户是否已领取。列表页据此显示「领取」还是「去使用」 */
  received: boolean;
  /** 状态 */
  status: CouponStatus;
  /** 适用范围文案，如「仅限张记粮油店」。展示用，实际校验在服务端 */
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
/**
 * 求团需求单的状态。**取值以库里存的为准**（`mkt_request.status`）。
 *
 * 这里原先是另一套词：OPEN / QUOTING / MATCHED / EXPIRED —— 与后端一个都对不上，
 * 于是页面上 `status === "MATCHED"` 恒 false（已选定报价那一块、二次确认按钮
 * 永远不出现），而 `status !== "MATCHED"` 恒真（锁价之后「选定」按钮仍然挂着）。
 * 两边各写各的，谁也没报错。
 *
 * 枚举对账守卫当时也是绿的：它拿端上的取值去全后端的大写字面量里搜，
 * 而 MATCHED / OPEN / EXPIRED 恰好在别的域里存在（团购、优惠券…）——
 * **同名异义把缺口盖住了**。词袋比对不了「这个字段的取值」。
 */
export type GroupRequestStatus =
  /** 刚发起，等邻居 +1、等商家来报价 */
  | "COLLECTING"
  /** 已有商家报价 */
  | "QUOTED"
  /** 发起人已选定报价，价格就此锁死（ADR-003 第一层） */
  | "LOCKED"
  /** 发起人确认收货，需求单收口 */
  | "CONFIRMED"
  | "CLOSED";
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
  /** LOCKED 之后指向生成的正式团 */
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
/**
 * 商家团 / 邻里团的状态。**与库 `mkt_group_buy.status` 逐字一致**。
 *
 * 契约上原先没有这个字段，端上只能拿 `reached` 判断 —— 而**平台中止的团
 * 人数可能已经够了**，只看 reached 会把一个已经作废的团显示成正常可参的团。
 */
export type GroupBuyStatus =
  /** 开团中，还能参 */
  | "OPEN"
  /** 已成团 */
  | "FORMED"
  /** 没成团 / 被平台中止。不作废订单：按原价照常发货 */
  | "FAILED";
export interface GroupBuy {
  /** 团单号 */
  groupNo: string;
  /** 团的状态 */
  status: GroupBuyStatus;
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
  /**
   * 已参团的邻居，展示用。
   *
   * **没有件数**：参团是一人一份 —— 成团判断、「还差 N 人」的文案、`joinedCount`
   * 全部按人算，库里也没存过件数。这里原先有个 `qty`，页面照着渲染 `×{qty}`，
   * 而它从来没有值。
   */
  members: { avatar: string; nickname: string }[];
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
/** 冲突提示：这件商品已经在另一个还在跑的活动里 */
export interface ActivityConflict {
  /** 商品号 */
  goodsNo: string;
  /** 活动号 */
  activityNo: string;
  /** 活动名 */
  activityName: string;
  /** 优惠方式 */
  benefitType: string;
}
/**
 * 商家自己的券（P4，新模型 `pmt_coupon`）。
 *
 * @remarks **名字前缀 Merchant 是必要的**：`Coupon` 这个名字已经被老模型
 * （平台券 / 领券中心那一套 `mkt_coupon`）占着，两者的字段形状完全不同。
 * 同名会在 import 时静默取到另一个 —— 而 TS 只在字段对不上的那一行报错，
 * 报的还是「类型不匹配」，看不出是拿错了类型。P9 老模型退场后可以去掉前缀。
 *
 * @remarks 七个维度是正交的，不是七个枚举值：**权益 × 门槛 × 范围 × 有效期 ×
 * 发放 × 核销 × 次数**。老模型把这些压在一个 `type` 上，于是加一种玩法
 * 就要改一次算价。
 */
export interface MerchantCoupon {
  /** 券模板号 —— 一张券和它的模板是两个对象 */
  couponNo: string;
  /** 券名，买家在券包里看到的就是它 */
  title: string;
  /** `CASH` 现金 / `PERCENT` 折扣 / `GIFT` 兑换 / `FREE_SHIP` 免运费 */
  benefitMode: string;
  /** CASH = 面额（分）；PERCENT = **万分比**，8500 表示八五折（顾客付 85%） */
  benefitValue: number;
  /** 折扣封顶（分）。PERCENT 必填 —— 不封顶的敞口随订单金额无限放大 */
  benefitCapMinor?: number | null;
  /** GIFT 兑换哪件商品 */
  benefitRef?: string | null;
  /** 用券门槛：订单满多少分。空 = 无门槛 */
  minAmountMinor?: number | null;
  /** 用券门槛：订单满几件。与 minAmountMinor **同时生效**，不是二选一 */
  minQty?: number | null;
  /** `ALL` / `STORE` / `CATEGORY` / `GOODS`。**下单抵扣的券只能是前两种** */
  scopeType: string;
  /** 限定到哪些类目号或商品号，随 scopeType 变 */
  scopeRefs: string[];
  /** 适用范围的**文案**。⚠️ 只是给人看的一句话，**不是校验依据** —— 判的是 scopeType/scopeRefs */
  scopeDesc?: string | null;
  /** `ABSOLUTE` 固定起止 / `RELATIVE` 领取后 N 天 */
  validityMode: string;
  /** 生效时刻（毫秒）。FIXED 用 */
  startAt?: number | null;
  /** 失效时刻（毫秒）。FIXED 用 */
  endAt?: number | null;
  /** 领取后有效天数。RELATIVE 用 */
  validDays?: number | null;
  /** `CENTER` 领券中心 / `TARGETED` 定向发 / `ACTIVITY` 活动发 */
  issueMode: string;
  /** `ORDER` 下单抵扣 / `STORE_CODE` 到店出示核销 */
  redeemMode: string;
  /** 一张能用几次。>1 就是次卡（豆浆 5 杯） */
  timesTotal: number;
  /** 发行量。空 = 不限（只有定向发放允许） */
  totalCount?: number | null;
  /** 已领取数 */
  receivedCount: number;
  /** 每人最多领几张 */
  perUserLimit: number;
  /** 预算上限（分）。空 = 不限 */
  budgetMinor?: number | null;
  /**
   * 最大敞口 = 发行量 × 单张最大优惠。
   * **建券页要显示它** —— 商家填「1000 张 × 20 元」时心里想的是「发 1000 张」，
   * 不是「最多赔两万」。
   */
  maxExposureMinor?: number | null;
  /** `ACTIVE` / `PAUSED` 暂停发放（已领的不受影响）/ `ENDED` */
  status: string;
}
/** 建券入参。`couponNo` 为空 = 新建 */
export interface MerchantCouponDraft {
  /** 券号。**新建时不传** —— 传了就是改这一张 */
  couponNo?: string;
  /** 券名，买家在券包里看到的就是它 */
  title: string;
  /** `CASH` 减固定金额 / `PERCENT` 打折 / `GIFT` 换赠品 / `TIMES` 次卡 */
  benefitMode: string;
  /** 优惠力度。含义**跟着 benefitMode 变**：CASH 是分、PERCENT 是万分比、TIMES 是次数 */
  benefitValue: number;
  /** 折扣券的封顶金额（分）。空 = 不封顶 —— 打折券不封顶时一张大单能吃掉整月预算 */
  benefitCapMinor?: number | null;
  /** 赠品/次卡指向的对象（商品号）。只有 GIFT 与 TIMES 用得上 */
  benefitRef?: string | null;
  /** 用券门槛：订单满多少分。空 = 无门槛 */
  minAmountMinor?: number | null;
  /** 用券门槛：订单满几件。与 minAmountMinor **同时生效**，不是二选一 */
  minQty?: number | null;
  /** `ALL` 全店 / `CATEGORY` 限类目 / `GOODS` 限商品 */
  scopeType?: string;
  /** 限定到哪些类目号或商品号，随 scopeType 变 */
  scopeRefs?: string[];
  /**
   * 适用范围的**文案**。
   *
   * ⚠️ 它只是给人看的一句话，**不是校验依据** —— 老券的「仅限粮油类」从来没进过判定，
   * 判的一直是 scopeType/scopeRefs。搬新模型时特意没把它当规则搬，否则存量券会突然变严。
   */
  scopeDesc?: string | null;
  /** `FIXED` 固定起止时间 / `RELATIVE` 领取后 N 天内有效 */
  validityMode?: string;
  /** 生效时刻（毫秒）。FIXED 用 */
  startAt?: number | null;
  /** 失效时刻（毫秒）。FIXED 用 */
  endAt?: number | null;
  /** 领取后有效天数。RELATIVE 用 —— 定向发放常用它，避免「发出去就快过期」 */
  validDays?: number | null;
  /** `PUBLIC` 买家自己领 / `TARGETED` 只能由商家定向发 */
  issueMode?: string;
  /** `ONLINE` 下单抵扣 / `STORE` 到店出示码核销。**到店券没有码就出示不了** */
  redeemMode?: string;
  /** 一张券可核几次。次卡看这个数，普通券恒为 1 */
  timesTotal?: number;
  /** 总发行量。空 = 不限量 —— 不限量的券没有「发完」这个状态 */
  totalCount?: number | null;
  /** 每人最多领几张 */
  perUserLimit?: number;
  /** 预算上限（分）。空 = 不限。用尽后停发，已发出去的不受影响 */
  budgetMinor?: number | null;
}
/**
 * 一次定向发放的结果。
 *
 * @remarks `skipped` 与 `skipReasons` **必须显示在结果页上**：商家选了 37 个人、
 * 实发 25 张，只说「发放成功」的话，他会以为发出去 37 张 —— 直到某个顾客说没收到。
 */
export interface CouponIssueBatch {
  /** 这一批发放的编号 */
  issueNo: string;
  /** 券模板号 —— 一张券和它的模板是两个对象 */
  couponNo: string;
  /** 发给哪个人群。空 = 手动挑的人 */
  segmentNo?: string | null;
  /** 人群此刻命中多少人 */
  planned: number;
  /** 实发多少张 */
  issued: number;
  /** 跳过多少人 */
  skipped: number;
  /** `UNREACHABLE` 还没注册或已退订 / `ALREADY_HAS` 到每人上限 / `SOLD_OUT` 券发完 */
  skipReasons: Array<{ reason: string; count: number }>;
  /** 这一批券的面额合计（分）—— 商家据此估敞口 */
  amountMinor: number;
  /** 谁发的 */
  operatorNo?: string | null;
  /** 发放时刻（毫秒） */
  issuedAt: number;
}
/**
 * 买家券包里<b>商家发的那一张</b>（新模型，P6）。
 *
 * @remarks 与老的 `UserCoupon` 并存到 P9。**不能合并**：老形状里没有
 * `redeemCode` 也没有次卡的 `remaining`，而这两样正是这批券的全部意义 ——
 * 到店券没有码就出示不了，次卡不显示剩余次数就等于一次性券。
 */
export interface MyStoreCoupon {
  /** 这个人手里那一张的编号 */
  userCouponNo: string;
  /** 券模板号 —— 一张券和它的模板是两个对象 */
  couponNo: string;
  /** 券名，买家在券包里看到的就是它 */
  title: string;
  /** 「减 3 元」「8.5 折」「凭券兑换」这种人话，后端拼好 */
  benefitText: string;
  /** 发这张券的商家 */
  entityNo?: string | null;
  /** `ORDER` 下单抵扣 / `STORE_CODE` 到店出示 */
  redeemMode: string;
  /** 到店出示的码。**只有 STORE_CODE 券有** —— 别给下单券显示码 */
  redeemCode?: string | null;
  /** 用券门槛：订单满多少分。空 = 无门槛 */
  minAmountMinor?: number | null;
  /** 一张券可核几次。次卡看这个数，普通券恒为 1 */
  timesTotal: number;
  /** 已核销次数 */
  timesUsed: number;
  /** 次卡还剩几次 */
  remaining: number;
  /** 过期时刻（毫秒） */
  expireAt: number;
  /** `UNUSED` / `USED` / `EXPIRED` / `REVOKED` */
  status: string;
  /** 此刻能不能用。按时间窗、门槛、剩余次数实时判 —— 不落库，落了就要有人定时刷 */
  usableNow: boolean;
}
/**
 * 到店核销：先看后核里「看」的那一步（P6）。
 *
 * @remarks 店员要先看到「什么券、还剩几次、能不能核」再按 ——
 * 扫完直接扣的话，扫错一张没有回头路（**线下核销不可撤销**）。
 */
export interface CouponRedeemView {
  /** 这个人手里那一张的编号 */
  userCouponNo: string;
  /** 券模板号 —— 一张券和它的模板是两个对象 */
  couponNo: string;
  /** 券名，买家在券包里看到的就是它 */
  title: string;
  /** 「减 3 元」「8.5 折」「兑换」这种人话，后端拼好 */
  benefitText: string;
  /** 持券人手机号后四位。店员认人够用，**永远不给完整号** */
  phoneTail?: string | null;
  /** 过期时刻（毫秒） */
  expireAt: number;
  /** 一张券可核几次。次卡看这个数，普通券恒为 1 */
  timesTotal: number;
  /** 已核销次数 */
  timesUsed: number;
  /** 还能核几次。次卡看这个数 */
  remaining: number;
  /** 此刻能不能核。不能时看 reason */
  redeemable: boolean;
  /** 不能核销时的原因码：`EXPIRED` / `USED_UP` / `REVOKED` / `NOT_STORE_CODE` / `COUPON_INACTIVE` */
  reason?: string | null;
}
/**
 * 核销结果。
 *
 * @remarks `duplicated` 为真 = **店员连点了两下**（3 秒窗口内），不是第二次核销。
 * 这时要提示「刚才那次已经核销成功」而不是报错 ——
 * 报错会让他以为没成功，于是再按一次。
 */
export interface CouponRedeemResult {
  /** 这个人手里那一张的编号 */
  userCouponNo: string;
  /** 已核销次数 */
  timesUsed: number;
  /** 还能核几次 */
  remaining: number;
  /** 次数用尽 */
  usedUp: boolean;
  /** 为真 = 店员**连点了两下**（3 秒窗口内），不是第二次核销 —— 报错会让他以为没成功，于是再按一次 */
  duplicated: boolean;
}
