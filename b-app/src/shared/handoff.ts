/**
 * 页面之间的一次性交接。
 *
 * <p><b>为什么需要它</b>：商品列表是 tabBar 页面，而 uni 的 `switchTab`
 * **不支持带参数**（`navigateTo` 支持，但它跳不到 tabBar 页）。
 * 于是「从我的类目点一类、落在商品列表并筛好那一类」这条路，
 * 没有任何一个 uni 原生跳转能一次做到。
 *
 * <p>也不能改用 onLoad 接参：tabBar 页面第二次进来只触发 onShow，
 * 参数那条路第二次就哑了 —— 而那正是最常走的第二次。
 *
 * <p><b>一次性</b>是关键：读完就清。留着的话，他下次从 tab 图标进商品列表，
 * 还会莫名其妙地停在上次那个类目上，而界面上没有任何东西解释为什么。
 */
let pendingGoodsCategory: string | null = null;

export function handOffGoodsCategory(categoryNo: string): void {
  pendingGoodsCategory = categoryNo || null;
}

/** 取走并清空。没有待交接的返回 null */
export function takeGoodsCategory(): string | null {
  const v = pendingGoodsCategory;
  pendingGoodsCategory = null;
  return v;
}
