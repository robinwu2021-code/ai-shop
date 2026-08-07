// 生鲜计价：下单按标称重量估价，履约时按实称重量多退少补。
// 自提免运费（社区团购主路径）。
// 注意：策略层不依赖任何数据源，实称基准由 OrderItem.nominalGram 自带。
import { FULFILLMENT, TRADE_RULES } from "@shared/utils/constants";
import type { PricingStrategy } from "./types";
import { baseAmount } from "./types";

export const weighedPricing: PricingStrategy = {
  estimate(items, ctx) {
    // 自提免运费；选送货上门则按社区配送规则收（满额免）
    let freight = 0;
    if (ctx.fulfillment === FULFILLMENT.DELIVERY) {
      const goodsMinor = items.reduce((s, it) => s + it.price * it.qty, 0);
      freight =
        goodsMinor >= TRADE_RULES.deliveryFreeThresholdMinor
          ? 0
          : TRADE_RULES.deliveryFeeMinor;
    }
    return baseAmount(items, ctx, freight);
  },

  /**
   * 实称结算：差价 = Σ(实重/标称重 - 1) × 单价 × 数量。
   * 正数 = 用户补款，负数 = 原路退款给用户。
   */
  settle(_amount, items, actualGrams) {
    if (!actualGrams) return 0;
    let adjust = 0;
    for (const it of items) {
      const actual = actualGrams[it.skuNo];
      if (actual == null || !it.weighed || !it.nominalGram) continue;
      adjust += Math.round((actual / it.nominalGram - 1) * it.price * it.qty);
    }
    return adjust;
  },
};
