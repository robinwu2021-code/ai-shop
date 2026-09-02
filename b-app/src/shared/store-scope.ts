/**
 * 门店维度的**单一模型**：哪些接口按当前门店取数，因而哪些页面要标出「现在是哪家店」。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 为什么改成「按接口」而不是「按页面」（2026-09-03）
 * ─────────────────────────────────────────────────────────────────────────────
 * 上一版这里直接列页面，理由是我一条条写的 —— 而其中**三条是错的**：
 * `coupons` / `coupon-issues` 写着「券按门店发放」，实际 `BizCouponController`
 * 的建券、发券、列表、发放记录**全是主体级**（只有到店核销按门店）；
 * `me` 写着「页内经营统计按门店」，而那一页根本没有统计，只有一个跳转入口。
 * 三页都挂着「当前门店」胶囊，等于**跟店主说了三次谎**。
 *
 * 现在真源是下面这份**接口清单**，每条都指向后端读当前门店的那一行代码。
 * 页面按门店与否由「它调了哪些接口」推出来，闸门（`biz-store-scope`）负责核对：
 * 页面调了按门店的接口却没标 → 红；标了却没调 → 红；清单与代码对不上 → 红。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 切店本身是好的，坏的是「看不见」
 * ─────────────────────────────────────────────────────────────────────────────
 * 切店会写 `STORAGE.storeNo`，http 层统一带 `X-Store-No`（`packages/shared/net`），
 * 后端按它取数 —— 这条链是通的。**mock 此前一处都不认这个键**，于是在 mock 包上
 * （店主与我们平时验交互的那一份）切了门店界面纹丝不动，看起来像「切店没做好」。
 * 见 `b-app/src/api/mock.ts` 的 `currentStoreNo()`。
 */

/**
 * <b>后端按当前门店取数的 B 端接口</b>（端上的 endpoint key）→ 依据。
 *
 * 依据一律指向后端读 `BizContext.currentStoreNo()/currentStoreScope()/requireStoreNo()`
 * 的那个类 —— 不写「我觉得它按门店」。
 */
export const STORE_SCOPED_ENDPOINTS: Record<string, string> = {
  mStats: "BizDashboardController#stats → orderService.stats(merchantNo, ctx.currentStoreScope())",
  mTodo: "BizDashboardController#todo → orderService.todo(..., ctx.currentStoreScope(), ...)",
  mCustomers: "BizDashboardController#customers → ctx.currentStoreScope()",
  mDeliveryRule: "BizDashboardController#deliveryRule → ctx.currentStoreNo()",
  mSaveDeliveryRule: "同上，写路径",
  mStore: "BizMerchantController 的门面资料/公告/服务范围四处 → ctx.currentStoreNo()",
  mSaveStore: "同上，写路径",
  mStoreQrcode: "BizPickupController#qrcode → 一店一码（V298）",
  mGoodsList: "BizGoodsController + MerchantGoodsServiceImpl → 按店的在售与库存投影",
  mOrderList: "BizOrderController 五处判当前门店",
  mDelivered: "同上",
  mStockSummary: "BizStockReportController → 当前门店的库存",
  mStockBalances: "BizStockController → 当前门店的库存",
  mStockPickable: "同上",
  mCountOpen: "BizStockDocController → 盘点开在这家店的库位",
  mCountDetail: "同上",
  mOutboundCreate: "同上，出库扣的是这家店",
};

/**
 * 页面目录名 → **为什么它不标当前门店**，哪怕它调了上面那些接口。
 *
 * 写下来是为了让「漏了」和「想过、不该标」在清单上分得开。
 */
export const NOT_STORE_SCOPED_REASONS: Record<string, string> = {
  customers: "页内自带门店选择器（可选「全部门店」），它比一枚固定胶囊表达力更强；再挂一枚会出现两个互相矛盾的门店提示",
  marketing: "活动可以按店也可以全店，页内自己选",
  orders: "已在筛选条上直接写出当前门店范围（allStores 时写「全部门店」），不重复标",
  order: "订单详情页的门店写在单据本身上（这一单从哪家店出），比页头胶囊更准",
  "after-sale": "同 order：售后跟着那一单走",
  groups: "拼团列表是主体级的活动，商品选择器才按店",
  "goods-edit": "编辑单件商品，页内「本店售卖」那一栏自己写着门店",
  "store-notice": "从门店资料页进来的二级页，门店在上一屏已经确定",
  "purchase-edit": "采购单是主体级的，收货入哪个库位在单据里选",
  transfer: "调拨天然是两家店之间的事，页内两个门店选择器",
  entities: "证照维度，比门店高一层",
  income: "结算与账期按主体，不按门店",
  settle: "同 income",
  me: "整页只有主体名与一堆入口（经营统计是另一页），没有一个数字按门店 —— 标了就是骗人",
  coupons: "建券/发券/列表全是主体级（BizCouponController 走 requireMerchantNo）；只有到店核销按门店，那在核销页",
  "coupon-issues": "同 coupons：发放记录是主体级的",
};

/**
 * 声明「这一页按门店，且要标出来」。
 *
 * <p><b>这份是推出来的，不是想出来的</b>：闸门会按
 * 「页面调用了 {@link STORE_SCOPED_ENDPOINTS} 里的接口」重算一遍，
 * 与这份对不上就红，并直接列出差在哪一页。
 * 不想标的页面写进 {@link NOT_STORE_SCOPED_REASONS} 并说明理由。
 */
export const STORE_SCOPED_PAGES: Record<string, string> = {
  home: "工作台的统计与待办按当前门店（mStats / mTodo）",
  "goods-list": "在售与库存按店（mGoodsList）",
  store: "门面资料、公告、店铺码都是这家店的（mStore / mStoreQrcode）",
  "store-scope": "服务范围与配送规则按门店（mStore / mDeliveryRule）",
  delivery: "配送规则与本店待送（mDeliveryRule / mOrderList）",
  "stock-check": "盘点改的是这家店的库存（mCountOpen / mStockPickable）",
  "stock-out": "出库扣的是这家店的库存（mOutboundCreate / mStockBalances）",
  stock: "库存总览按门店（mStockSummary / mStockBalances）",
  stats: "经营统计整页走 mStats —— 与工作台同一个数",
};

/** 这一页是不是按门店取数。`dir` 是 `b-app/src/pages/` 下的目录名。 */
export function isStoreScopedPage(dir: string): boolean {
  return Object.prototype.hasOwnProperty.call(STORE_SCOPED_PAGES, dir);
}
