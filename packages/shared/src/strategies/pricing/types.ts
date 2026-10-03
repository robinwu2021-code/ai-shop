// 计价策略的接口与共用计算。独立成文件，避免 index ↔ 实现 的循环依赖。
import { POINTS } from "@shared/utils/constants";
import type {
  Coupon,
  CurrencyCode,
  FulfillmentType,
  OrderAmount,
  OrderItem,
} from "@shared/types";

export interface PricingContext {
  fulfillment: FulfillmentType;
  currency: CurrencyCode;
  coupon?: Coupon;
  /** 用户想用的积分数（会被抵扣上限截断） */
  usePoints?: number;
  /** 本单可获得的积分 */
  earnPoints?: number;
}

export interface PricingStrategy {
  /** 下单时估价 */
  estimate(items: OrderItem[], ctx: PricingContext): OrderAmount;
  /**
   * 履约后结算（生鲜实称多退少补）。
   * @param actualGrams skuNo → 实际重量（克）
   * @returns 差价（最小货币单位），正=补款 负=退款
   */
  settle(
    amount: OrderAmount,
    items: OrderItem[],
    actualGrams?: Record<string, number>,
  ): number;
}

/**
 * 共用：商品小计 → 券 → 积分 → 运费 → OrderAmount。
 *
 * 抵扣顺序是有讲究的：**券先抵、积分后抵**，且积分抵扣上限按「券后金额」算。
 * 反过来的话，用户会先用积分把金额压低，导致券的门槛达不到 —— 对用户不利，也更难解释。
 * 运费不参与积分抵扣：运费是要付给运力的真金白银，用积分抵等于平台自掏腰包。
 */
/**
 * 这张券在这笔金额上能减多少。**与后端 `MktCoupon.discountFor` 同一套算法** ——
 * 两处各写一遍的表现是「确认订单页显示减 8 元，付完发现只减了 5 元」，
 * 而两边都不报错，用户只会觉得平台在骗他。
 *
 * 契约此前只有一个 `discountMinor`，端上直接减那个数 ——
 * **折扣券根本表达不了**：打几折要看订单金额，还有封顶。
 *
 * @param goodsMinor 参与计算的商品额；商家券只算本店那部分
 */
export function couponDiscount(coupon: Coupon | null | undefined, goodsMinor: number): number {
  if (!coupon || goodsMinor < coupon.thresholdMinor) return 0;
  if (coupon.type === "DISCOUNT") {
    const off = Math.floor((goodsMinor * (10_000 - coupon.discountRate)) / 10_000);
    return coupon.maxDiscountMinor > 0 ? Math.min(off, coupon.maxDiscountMinor) : off;
  }
  // 满减不能减成负数：券面额大于商品额时按商品额封顶
  return Math.min(coupon.faceMinor, goodsMinor);
}

export function baseAmount(
  items: OrderItem[],
  ctx: PricingContext,
  freightMinor: number,
): OrderAmount {
  const goodsMinor = items.reduce((s, it) => s + it.price * it.qty, 0);

  const discountMinor = couponDiscount(ctx.coupon, goodsMinor);

  const afterCoupon = Math.max(0, goodsMinor - discountMinor);
  // 抵扣上限：不能整单抵掉，否则平台收不到现金却要向商家兑付这笔积分
  const capMinor = Math.floor(afterCoupon * POINTS.maxDeductRatio);
  const wantMinor = Math.floor((ctx.usePoints ?? 0) / POINTS.perMinor);
  const pointsDeductMinor = Math.max(0, Math.min(capMinor, wantMinor));
  const pointsUsed = pointsDeductMinor * POINTS.perMinor;

  const payableMinor = Math.max(
    0,
    goodsMinor + freightMinor - discountMinor - pointsDeductMinor,
  );

  return {
    goodsMinor,
    freightMinor,
    discountMinor,
    pointsDeductMinor,
    pointsUsed,
    pointsEarn: ctx.earnPoints ?? 0,
    payableMinor,
    paidMinor: 0,
    currency: ctx.currency,
  };
}

/** 本单可获得的积分：商品单独配了就按配的，没配按成交额比例 */
export function earnPointsFor(items: OrderItem[]): number {
  return items.reduce((sum, it) => {
    if (it.isGift) return sum; // 赠品不送积分，否则「买赠 + 积分」可以叠出套利
    const per = it.points ?? Math.round(it.price * POINTS.defaultEarnRatio);
    return sum + per * it.qty;
  }, 0);
}

/** 无运费、无履约后差价的品类共用实现（服务 / 虚拟 / 卡券） */
export const noFreightPricing: PricingStrategy = {
  estimate: (items, ctx) => baseAmount(items, ctx, 0),
  settle: () => 0,
};
