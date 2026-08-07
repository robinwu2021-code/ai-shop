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

/** 端。注意是「端」不是「支付通道」—— 同一个通道在不同端受的约束不同 */
export type Scene = "MP_WECHAT" | "MP_ALIPAY" | "IOS" | "ANDROID" | "H5";

export type PayChannel = "WECHAT" | "ALIPAY";

export type PayMethod = "JSAPI" | "APP" | "H5" | "NATIVE";

export type SubjectType = "MICRO" | "INDIVIDUAL" | "ENTERPRISE";

export type ApplyStatus = "NONE" | "APPLYING" | "ACTIVE" | "REJECTED" | "FROZEN";

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
  subjectType: SubjectType;
  applyStatus: ApplyStatus;
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
  subjectType: SubjectType;
}): Verdict {
  if (!ctx.globalOn) return deny("积分功能未开放");
  if (!ctx.communityOn) return deny("本社区暂未开放积分");
  if (ctx.subjectType === "MICRO") {
    // 积分兑付涉及平台向商家付钱，而小微的税务定性本就模糊，叠加后扣缴风险更高。
    // 同时它天然是一个升级激励。
    return deny("本店暂不支持积分（升级为个体工商户后可开启）");
  }
  if (!ctx.merchantOn) return deny("本店未开启积分");
  return allow();
}
