// 工作台文案（矩阵 P-16.1）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {
  title: "经营看板",
  desc: "平台整体经营与获客概况",

  kpiGmv: "近 7 日 GMV",
  kpiOrders: "近 7 日订单",
  kpiAov: "客单价",
  kpiPendingMerchant: "待审商家",
  kpiPendingMerchantSub: "需要 BD / 审核处理",
  kpiPendingAfterSale: "待处理售后",
  kpiPendingAfterSaleSub: "超时会升级为平台介入",
  kpiRedeemRate: "今日核销率",
  kpiRedeemRateSub: "自提履约健康度",
  storeRankTitle: "门店经营排行",
  // 说清楚它为什么与商家排行并存 —— 否则看起来像同一张表放了两遍
  storeRankDesc: "近 30 天。与下面的商家排行是同一份订单的两种切法：多门店商家的货与单都挂在门店上，而商家维度会把「一家很好、一家半死」平均成「还行」。",
  storeRankColStore: "门店",
  storeRankColMerchant: "所属商家",
  storeRankColRefund: "退款率",
  storeRankEmpty: "近 30 天没有带门店的成交",
  kpiPendingGoods: "待审商品",
  // 「最早等了 N 天」而不是「已积压 N 天」：前者说的是那一件商品的处境，
  // 后者听起来像整批都等了这么久 —— 而 194 件里多半是新旧混着的
  /** `{n}` 是最早那件等待的天数 */
  kpiPendingGoodsSub: "最早一件等了 {n} 天",
  /** 一件没积压时不说天数（说「等了 0 天」像是坏了），改说这张卡点了去哪儿 */
  kpiPendingGoodsSubClear: "点击进入审核队列",

  chartTrend: "GMV 与订单趋势",
  chartFunnel: "获客漏斗",
  /** Y 轴刻度：万元。`{n}` 是数值 */
  axisWan: "{n}万",

  funnelScan: "扫码",
  funnelEnterStore: "进店",
  funnelRegister: "注册",
  funnelFirstOrder: "首单",

  rankTitle: "商家经营排行",
  rankDesc: "近 14 天，按成交额降序 · 只含有成交的商家",
  rankColMerchant: "商家",
  rankColGmv: "成交额",
  rankColOrders: "订单数",
  rankColAov: "客单价",
  rankColAfterSale: "售后率",
  rankEmpty: "近 14 天还没有成交",

  soon: "履约质量、trafficSource 结构分析（P-16.1.5/6）待建",
};

const en: typeof zh = {
  title: "Dashboard",
  desc: "Platform-wide trading and acquisition overview",

  kpiGmv: "GMV, last 7 days",
  kpiOrders: "Orders, last 7 days",
  kpiAov: "Average order value",
  kpiPendingMerchant: "Merchants awaiting review",
  kpiPendingMerchantSub: "Needs BD / reviewer action",
  kpiPendingAfterSale: "Open after-sales",
  kpiPendingAfterSaleSub: "Escalates to platform review once overdue",
  kpiRedeemRate: "Redemption rate today",
  kpiRedeemRateSub: "Pickup fulfillment health",
  storeRankTitle: "Store ranking",
  storeRankDesc: "Last 30 days. Two cuts of the same orders as the merchant ranking below: stock and orders hang off stores, and the merchant view averages \"one great, one dying\" into \"fine\".",
  storeRankColStore: "Store",
  storeRankColMerchant: "Merchant",
  storeRankColRefund: "Refund rate",
  storeRankEmpty: "No store-attributed sales in the last 30 days",
  kpiPendingGoods: "Goods awaiting review",
  kpiPendingGoodsSub: "Oldest has waited {n} days",
  kpiPendingGoodsSubClear: "Open the review queue",

  chartTrend: "GMV & order trend",
  chartFunnel: "Acquisition funnel",
  axisWan: "{n}0k",

  funnelScan: "Scan",
  funnelEnterStore: "Enter store",
  funnelRegister: "Register",
  funnelFirstOrder: "First order",

  rankTitle: "Merchant performance ranking",
  rankDesc: "Last 14 days, by GMV · merchants with sales only",
  rankColMerchant: "Merchant",
  rankColGmv: "GMV",
  rankColOrders: "Orders",
  rankColAov: "Avg order value",
  rankColAfterSale: "After-sales rate",
  rankEmpty: "No sales in the last 14 days",

  soon: "Fulfillment quality and traffic-source breakdown (P-16.1.5/6) are not built yet",
};

export const HOME_COPY: PageCopy<typeof zh> = { zh, en };
