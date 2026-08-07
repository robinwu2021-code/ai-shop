// 标品计价：固定单价 × 数量。快递按满额包邮，自提免运费。
import { FULFILLMENT, TRADE_RULES } from "@shared/utils/constants";
import type { PricingStrategy } from "./types";
import { baseAmount } from "./types";

/** 快递运费规则（最小货币单位）。多市场下这两个数应由后端按市场下发，见 TDD 待办 */
const FREIGHT = {
  base: 600,
  freeThreshold: 9900,
} as const;

export const fixedPricing: PricingStrategy = {
  estimate(items, ctx) {
    const goodsMinor = items.reduce((s, it) => s + it.price * it.qty, 0);
    let freight = 0;
    if (ctx.fulfillment === FULFILLMENT.EXPRESS) {
      freight = goodsMinor >= FREIGHT.freeThreshold ? 0 : FREIGHT.base;
    } else if (ctx.fulfillment === FULFILLMENT.DELIVERY) {
      // 送货上门：自提点到家的最后一段，门槛比快递低
      freight =
        goodsMinor >= TRADE_RULES.deliveryFreeThresholdMinor
          ? 0
          : TRADE_RULES.deliveryFeeMinor;
    }
    return baseAmount(items, ctx, freight);
  },

  settle() {
    return 0; // 标品无履约后差价
  },
};
