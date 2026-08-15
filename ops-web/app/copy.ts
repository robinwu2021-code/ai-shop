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
