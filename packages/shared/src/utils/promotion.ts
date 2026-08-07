// 促销计算。一期只有「买 N 送 M」。
//
// 口径：**付 N 件的钱，收到 N+M 件**。
// 另一种常见口径是「每 N+M 件里有 M 件免费」（即买 3 付 2），两者算出来的赠品数不同：
//   买 2 送 1，买了 4 件 —— 本口径送 2 件（2 个整组），另一口径送 1 件（4 件里凑出 1 组 3 件）。
// 选前者是因为它和商家口头说的「买二送一」一致，用户不会算错。
//   ⚠️ 这个口径要业务确认，见需求 §六 R15。
import type { Promotion } from "@shared/types";

/** 按购买件数算赠品件数 */
export function giftQtyFor(promotion: Promotion | undefined, qty: number): number {
  if (!promotion || promotion.buyN <= 0) return 0;
  return Math.floor(qty / promotion.buyN) * promotion.giftM;
}

/** 取商品上生效的买赠促销（一期一个商品最多一条） */
export function buyNGetM(promotions?: Promotion[]): Promotion | undefined {
  return promotions?.find((p) => p.type === "BUY_N_GET_M");
}

/**
 * 展示文案的插值参数。文案本身在 i18n（promo.buyNGetM），
 * 这里只负责给出参数 —— 「买 2 送 1」在阿语里语序完全不同，不能在代码里拼字符串。
 */
export function promoLabelArgs(promotion: Promotion): { n: number; m: number } {
  return { n: promotion.buyN, m: promotion.giftM };
}
