// 看板域 mock 数据（P-16.1）。金额均为最小货币单位（分）。
import type { DashboardKpi, FunnelRow, TrendPoint } from "@/lib/types";

export const kpi: DashboardKpi = {
  gmv: 128_640_00,
  orderCount: 1362,
  avgOrderValue: 9_445,
  pendingMerchantAudit: 2,
  pendingAfterSale: 5,
  redeemRate: 0.87,
};

export const trend: TrendPoint[] = [
  { date: "07-30", gmv: 15_820_00, orderCount: 168 },
  { date: "07-31", gmv: 17_240_00, orderCount: 182 },
  { date: "08-01", gmv: 21_050_00, orderCount: 226 },
  { date: "08-02", gmv: 19_360_00, orderCount: 205 },
  { date: "08-03", gmv: 16_910_00, orderCount: 179 },
  { date: "08-04", gmv: 18_470_00, orderCount: 194 },
  { date: "08-05", gmv: 19_790_00, orderCount: 208 },
];

// 获客漏斗（P-16.1.4）。设计上是「扫码 → 进店 → 注册 → 首单」四环，
// **这里只给后两环** —— 与后端同形。
//
// 前两环需要埋点，而平台没有任何扫码/进店的事件表。mock 里编四环的代价是实测过的：
// 开发期看到的是一个完整漂亮的漏斗，连真后端才发现只有两环 ——
// 「mock 比后端好看」正是这个仓库反复出问题的形状。
export const funnel: FunnelRow[] = [
  { step: "REGISTER", count: 1180 },
  { step: "FIRST_ORDER", count: 640 },
];
