// 商品取值守卫 —— 商品必有至少一个 SKU 与一种履约方式，取不到即数据异常，早失败而非静默降级。
import type { FulfillmentType, Goods, Sku } from "@shared/types";

export function firstSku(g: Goods): Sku {
  const sku = g.skus[0];
  if (!sku) throw new Error(`商品缺少规格：${g.goodsNo}`);
  return sku;
}

export function defaultFulfillment(g: Goods): FulfillmentType {
  const f = g.fulfillments[0];
  if (!f) throw new Error(`商品缺少履约方式：${g.goodsNo}`);
  return f;
}
