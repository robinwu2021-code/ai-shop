// 支付能力矩阵：三个判断函数。
//
// 平台要同时支持 3 种商家主体 × 2 个支付通道 × 5 个端 × 5 个品类。
// 这些组合**不能铺成 if** —— 必然写错且改不动。做法是把「谁能在哪儿收钱」
// 建成数据（sys_channel_category_rule / usr_merchant_payment），
// 判断收敛成这三个纯函数。
//
// **同一份规则，三处使用**：商品列表、购物车结算、下单接口。
// 三处各写一套是这类系统最典型的腐坏方式，所以后端把规则表下发给端上，
// 两侧跑同一套逻辑；服务端在下单时**重跑一遍**，不信任端上的裁剪。
//
// 设计依据：docs/technical/多端多通道-详细设计.md §二
import type { FundsMode, MerchantSubject, PaymentApplyStatus } from "@shared/types";

/** 端。注意是「端」不是「支付通道」—— 同一个通道在不同端受的约束不同 */
export type Scene = "MP_WECHAT" | "MP_ALIPAY" | "IOS" | "ANDROID" | "H5";

export type PayChannel = "WECHAT" | "ALIPAY";

/**
 * 通道取值域的**运行时清单**。
 *
 * 写成 `satisfies Record<PayChannel, 1>` 而不是手写数组：往联合类型里加一个通道
 * 却忘了加进这里，**是编译错误**。手写数组的话两者会静默分叉，
 * 而分叉的表现是「新通道在校验里被当成非法值」——发到线上才知道。
 *
 * 能力位（能否补差/分账/打款）不在这里，在 `sys_pay_channel` 表里 ——
 * **表管能力，类型管取值**：能力会随对方发版而变，取值不会凭空冒出来。
 */
export const PAY_CHANNELS = Object.keys({
  WECHAT: 1,
  ALIPAY: 1,
} satisfies Record<PayChannel, 1>) as PayChannel[];

export type PayMethod = "JSAPI" | "APP" | "H5" | "NATIVE";

/**
 * 行业。**商家的基础属性，与商品类目是两个维度** ——
 * 行业挂商家（一家一个），类目挂商品（一家可卖多类）。
 *
 * 前五个是微信小微准入白名单里明确列出的，`ONLINE` 是明确排除的，
 * `OTHER` 是保守兜底。**不自己发明分类**：多一个我们自造的行业，
 * 就多一次「它到底映射到通道的哪一类」的猜测。
 */
export type Industry =
  | "CATERING"
  | "RETAIL"
  | "LIFE_SERVICE"
  | "ENTERTAINMENT"
  | "TRANSPORT"
  | "ONLINE"
  | "OTHER";

/**
 * 行业取值域的运行时清单。写法与 {@link PAY_CHANNELS} 同 ——
 * 往联合类型里加一个却忘了加进这里，**是编译错误**。
 *
 * 准入能力（能否小微、是否强制开积分）不在这里，在 `sys_industry` 表里：
 * 白名单是通道的规则，会变；取值域不会凭空冒出来。
 */
export const INDUSTRIES = Object.keys({
  CATERING: 1,
  RETAIL: 1,
  LIFE_SERVICE: 1,
  ENTERTAINMENT: 1,
  TRANSPORT: 1,
  ONLINE: 1,
  OTHER: 1,
} satisfies Record<Industry, 1>) as Industry[];

/*
 * 这里曾有一份 `SubjectType = "MICRO" | "INDIVIDUAL" | "ENTERPRISE"` ——
 * 与 types 里的 {@link MerchantSubject} **取值逐字相同**，而后者的注释白纸黑字写着
 * 「不叫 SubjectType：那个名字在平台端已经是风控主体（USER/MERCHANT/DEVICE）」。
 *
 * 同一个包内，规则写下来了仍然被违反 —— 这正是 G1 覆盖守卫存在的理由：
 * 光有注释和文档拦不住，得有一条会让 CI 变红的检查。
 */


/**
 * 判断结果。
 *
 * **每一处拒绝都要能说出原因** —— 只返回 false 的话，
 * 用户看到的是「不可购买」，客服问「为什么」时没人答得上来。
 */
export interface Verdict {
  ok: boolean;
  reason?: string;
}

const allow = (): Verdict => ({ ok: true });
const deny = (reason: string): Verdict => ({ ok: false, reason });

/** 端 → 该端使用的支付产品。小微主体只被授权 JSAPI/NATIVE，没有 APP 与 H5 */
export const SCENE_PAY_METHOD: Record<Scene, PayMethod> = {
  MP_WECHAT: "JSAPI",
  MP_ALIPAY: "JSAPI",
  IOS: "APP",
  ANDROID: "APP",
  H5: "H5",
};

export interface ChannelCategoryRule {
  scene: Scene;
  categoryType: string;
  sellable: boolean;
  reason?: string;
}

export interface MerchantPayment {
  channel: PayChannel;
  subjectType: MerchantSubject;
  applyStatus: PaymentApplyStatus;
  payMethods: PayMethod[];
  invoiceCapable: boolean;
}

/**
 * ① 这件商品在这个端能不能卖。
 *
 * iOS 的 IAP 约束针对**商品性质**（数字内容 vs 实物/线下服务），
 * 所以规则主体是「端 × 品类」，不是给每个商品挂开关。
 */
export function canSell(
  goods: { categoryType: string; sellableOverride?: Partial<Record<Scene, boolean>> },
  scene: Scene,
  rules: ChannelCategoryRule[],
): Verdict {
  // 商品级例外优先，用于个别破例
  const override = goods.sellableOverride?.[scene];
  if (override === false) return deny("该商品在当前端不可售");
  if (override === true) return allow();

  const rule = rules.find((r) => r.scene === scene && r.categoryType === goods.categoryType);
  // **查不到判拒绝，不是放行。**
  // 新增品类或新增端时「查不到 = 可售」会静默放行，而那正是审核被拒的场景。
  if (!rule) return deny("该品类在当前端的售卖规则未配置");
  return rule.sellable ? allow() : deny(rule.reason || "该品类在当前端不可售");
}

/**
 * ② 这个商家在这个端、走这个通道能不能收钱。
 *
 * 三步分别对应三种真实的失败：没进件、进件没通过、进了但该端的支付产品没被授权
 * （典型：小微主体在 App 上收不了钱）。三种的提示语必须不同 ——
 * 商家看到「未开通」和「审核中」要能分辨该做什么。
 */
export function canPay(
  payments: MerchantPayment[],
  scene: Scene,
  channel: PayChannel,
): Verdict {
  const p = payments.find((x) => x.channel === channel);
  if (!p) return deny("商家未开通该支付方式");
  if (p.applyStatus !== "ACTIVE") {
    return deny(p.applyStatus === "APPLYING" ? "商家支付进件审核中" : "商家支付进件未通过");
  }
  const need = SCENE_PAY_METHOD[scene];
  if (!p.payMethods.includes(need)) return deny("商家在当前端不支持支付");
  return allow();
}

/**
 * ③ 积分是否生效。
 *
 * 四级串联：全局 → 社区 → **主体** → 商家开关。
 *
 * 主体这一级排在商家开关**之前**是刻意的：小微是「不可开」而不是「关着」，
 * 提示语也不同 —— 前者要告诉商家「升级后可开启」，后者只是「你自己关了」。
 * 顺序反了的话，小微商家会看到「本店未开启积分」，以为自己打开就行。
 */
export function canPoints(ctx: {
  globalOn: boolean;
  communityOn: boolean;
  merchantOn: boolean;
  /**
   * 这家主体**有没有营业执照**。权威在 `sys_legal_form.need_license`，
   * 由 `/common/master-data` 下发 —— **不要在端上写 `subject === "MICRO"`**：
   * 主体取值正在改造（MICRO → NATURAL_PERSON），写死取值那天会静默失配。
   */
  licensed: boolean;
  /**
   * 资金路径：钱先进谁的账户。
   *
   * **补差只在 `DIRECT` 下发生** —— 钱在商家二级户，积分抵扣让他少收，
   * 平台要补进去，而那是一次**平台付钱给自然人**（扣缴定性模糊）。
   * `AGGREGATED` 下钱本就在平台手里，平台自己少收，没有「补」这个动作。
   *
   * ⚠️ **不要用 `business_mode` 判**：那说的是「谁是销售主体」，
   * 与「钱在谁手里」正交。两者不一致时会判错 —— 而库里恰好有这样的单。
   */
  fundsMode: FundsMode;
  /**
   * 本次支付走的通道能否**补差**（`sys_pay_channel.supports_subsidy`）。
   *
   * **只在 `DIRECT` 下参与判断**：归集路径根本不发起补差，
   * 拿通道能力去拦它会错误拒绝掉本可以开积分的商户（例如农产品农户）。
   */
  channelSupportsSubsidy: boolean;
}): Verdict {
  if (!ctx.globalOn) return deny("积分功能未开放");
  /*
   * 通道能否补差 —— **只在直连路径上问这个问题**。
   * 顺序放在社区/商家开关之前：它是「做不到」，不是「没开」，提示语要让用户
   * 知道换个端就能用，而不是以为这家店不支持。
   */
  if (ctx.fundsMode === "DIRECT" && !ctx.channelSupportsSubsidy) {
    return deny("当前支付方式不支持积分抵扣");
  }
  if (!ctx.communityOn) return deny("本社区暂未开放积分");
  /*
   * 无照 + 直连：补差是一次平台 → 自然人的付款，扣缴义务定性模糊，维持禁止。
   * 无照 + 归集：平台自己少收，没有付款动作 —— 放行。
   *
   * 排在商家开关**之前**是刻意的：这是「不可开」而不是「关着」，
   * 提示语也不同 —— 前者要告诉他条件是什么，后者只是「你自己关了」。
   */
  if (!ctx.licensed && ctx.fundsMode === "DIRECT") {
    return deny("本店暂不支持积分（无营业执照，且收款直连到商家账户）");
  }
  if (!ctx.merchantOn) return deny("本店未开启积分");
  return allow();
}

