import type { Goods } from "@shared/types";
import { ROUTES } from "@shared/utils/constants";

/**
 * 从列表点进商品详情的地址。**带上提供这件货的门店**（2026-09-30 门店化口径）。
 *
 * <p>为什么必须带：详情、加购、下单三处都按门店判「在架 ∧ 有货」，
 * 而买家是在列表上看着某一家店的货点进去的。不带门店的话详情给的是主体总量 ——
 * 页面显示有货、加到车里、到下单落店那一步才发现那家店没有，
 * 而那时人已经在结算页了。
 *
 * <p>门店门户那条路本来就带（`pages/store` 里写着 `&storeNo=`），
 * 这里补的是首页 / 分类 / 搜索 / 收藏这四个跨店入口。
 *
 * <p>没有门店时不拼这个参数（不是拼一个空串）：后端按「没有门店上下文」处理，
 * 与门店化之前逐字相同。**空串会被当成一个叫「」的门店**，那一支查不到行、
 * 按覆盖层语义算成 0 件，整页商品会显示售罄。
 */
export function goodsUrl(g: Pick<Goods, "goodsNo" | "store">): string {
  const base = `${ROUTES.goods}?goodsNo=${g.goodsNo}`;
  const storeNo = g.store?.storeNo;
  return storeNo ? `${base}&storeNo=${encodeURIComponent(storeNo)}` : base;
}
