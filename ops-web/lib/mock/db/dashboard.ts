// 看板域 mock 数据（P-16.1）。金额均为最小货币单位（分）。
import type { DashboardKpi, FunnelRow, MerchantRankRow, TrendPoint } from "@/lib/types";

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

// 商家经营排行（P-16.1.2 / P-16.1.3）。按 GMV 降序 —— 与后端同一口径。
//
// 第三行刻意是「GMV 中等但售后率高」的形状：排行如果只按 GMV 排，
// 那种商家永远排在中间不显眼，而他恰恰是平台最该盯的一家。
export const merchantRanking: MerchantRankRow[] = [
  { merchantNo: "M901", merchantName: "张记粮油", gmv: 42_360_00, orderCount: 452, avgOrderValue: 9_371, afterSaleCount: 9, afterSaleRate: 0.0199 },
  { merchantNo: "M902", merchantName: "阿明果蔬合作社", gmv: 31_180_00, orderCount: 388, avgOrderValue: 8_036, afterSaleCount: 12, afterSaleRate: 0.0309 },
  { merchantNo: "M903", merchantName: "巷口鲜肉铺", gmv: 18_940_00, orderCount: 176, avgOrderValue: 10_761, afterSaleCount: 31, afterSaleRate: 0.1761 },
  { merchantNo: "M904", merchantName: "社区烘焙工坊", gmv: 12_470_00, orderCount: 214, avgOrderValue: 5_827, afterSaleCount: 4, afterSaleRate: 0.0187 },
];
