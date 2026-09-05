// 商品取值守卫 —— 商品必有至少一个 SKU 与一种履约方式，取不到即数据异常，早失败而非静默降级。
import type { FulfillmentType, Goods, Sku } from "@shared/types";

export function firstSku(g: Goods): Sku {
  const sku = g.skus[0];
  if (!sku) throw new Error(`商品缺少规格：${g.goodsNo}`);
  return sku;
}

/**
 * 列表页那个「＋」该加哪个规格：**第一个有货的**，都没货才回落到第一个。
 *
 * ⚠️ 与 {@link firstSku} 的区别是这次修的那个缺陷本身：列表页原本直接用
 * `skus[0]`，而商品详情页用的是「第一个有货的」——**同一件商品，两处挑出
 * 不同的规格**。首个规格售罄时，列表页的「＋」会静默加进一件当场就失效的货
 *（加购这条路后端与 mock 都不校验库存），用户要到购物车的失效区才看见。
 *
 * <p>都没货时仍然回落到 `skus[0]` 而不是抛错：那种情况下卡片已经画成售罄、
 * 「＋」也换成了标记，走不到这里；真走到了，宁可让后端拒也别在端上崩。
 */
export function firstBuyableSku(g: Goods): Sku {
  return g.skus.find((s) => (s.stock ?? 0) > 0) ?? firstSku(g);
}

/**
 * 整件商品都没货了。**`skus` 为空时返回 false** ——
 * 那是「没拿到规格」，不是「卖光了」，两者在界面上是完全不同的话。
 */
export function goodsSoldOut(g: Goods): boolean {
  return (g.skus?.length ?? 0) > 0 && g.skus.every((s) => (s.stock ?? 0) <= 0);
}

export function defaultFulfillment(g: Goods): FulfillmentType {
  const f = g.fulfillments[0];
  if (!f) throw new Error(`商品缺少履约方式：${g.goodsNo}`);
  return f;
}
