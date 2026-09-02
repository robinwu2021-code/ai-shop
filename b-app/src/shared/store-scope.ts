/**
 * 门店维度的**单一模型**：哪些页面是按当前门店取数的。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 为什么需要它
 * ─────────────────────────────────────────────────────────────────────────────
 * 「这一页是不是按门店取数」此前**不存在于任何地方** —— 它隐含在
 * 「这一页调了哪些接口、那些接口后端有没有读 `X-Store-No`」里。
 * 于是每加一个按门店的页面，都要有人凭记忆想起来在模板里加一枚当前门店胶囊，
 * 而**忘了不报错**：页面照常出数，只是那些数属于另一家店。
 *
 * 2026-09-02 盘点时的实际状态：后端有 10 个 B 端控制器按门店取数，
 * 而胶囊只出现在 4 个页面上。用户报上来的症状是「切了门店，『我的』顶部没变」——
 * 而那一页顶部显示的是**主体名**，它按定义就不随切店变。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 这个模型怎么起作用
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. 这里声明「哪些页面按门店」，**一处**，且每条要写清依据；
 * 2. 页面模板里渲染 `<biz-store-tag readonly>`，它读的是
 *    `merchant.currentStore` —— 门店本身的唯一真源仍在 pinia，这里只声明「谁依赖它」；
 * 3. `packages/shared` 的 `biz-store-scope` 闸门**双向**核对：
 *    声明了却没渲染 → 红；渲染了却没声明 → 也红。
 *    两个方向都要，只查一边的话清单会慢慢变成一份和代码无关的愿望列表。
 *
 * <b>不做的事</b>：这里不管「切店之后要不要重取数据」。那件事由各页的 `onShow`
 * 负责（切店入口在工作台，切完必然要跳转回来），加第二套订阅机制只会多一处能忘的地方。
 */

/** 页面目录名 → **为什么**它按门店。理由为空等于没声明（闸门会红）。 */
export const STORE_SCOPED_PAGES: Record<string, string> = {
  home: "工作台的待办与统计走 currentStoreScope（BizDashboardController）",
  "goods-list": "在售商品与库存按店（BizGoodsController，店级覆盖行存在时按店算）",
  store: "门面资料、配送范围、店铺码都是这家店的（BizMerchantController / BizPickupController）",
  "store-scope": "服务范围是按门店配的",
  me: "顶部是主体名（按定义不随切店变），而页内「经营统计」按门店 —— 不标出来会被读成「没切过去」",
  coupons: "券按门店发放与核销（BizCouponController）",
  "coupon-issues": "发放记录按门店，同 coupons",
  delivery: "配送规则是这家店的（BizPickupController 全程按当前门店）",
  "stock-check": "盘点改的是这家店的库存 —— 库位由 acl.locationIdOf(merchantNo, currentStoreNo) 解出",
  "stock-out": "出库扣的是这家店的库存，同 stock-check",
};

/**
 * <b>刻意不在上面</b>的页面，以及为什么。
 *
 * 写下来是为了让下一次盘点不必重新论证一遍 —— 也为了让「漏了」和「想过、不该有」
 * 在清单上分得开。
 */
export const NOT_STORE_SCOPED_REASONS: Record<string, string> = {
  customers: "页内自带门店选择器（可选「全部门店」），它比一枚固定胶囊表达力更强；再挂一枚会出现两个互相矛盾的门店提示",
  marketing: "同 customers：活动可以按店也可以全店，页内自己选",
  orders: "已在筛选条上直接写出当前门店范围（allStores 时写「全部门店」），不重复标",
  entities: "证照维度，比门店高一层",
  income: "结算与账期按主体，不按门店",
  settle: "同 income",
};

/** 这一页是不是按门店取数。`dir` 是 `b-app/src/pages/` 下的目录名。 */
export function isStoreScopedPage(dir: string): boolean {
  return Object.prototype.hasOwnProperty.call(STORE_SCOPED_PAGES, dir);
}
