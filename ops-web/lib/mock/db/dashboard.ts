// 看板域 mock 数据（P-16.1）。金额均为最小货币单位（分）。
import type { DashboardKpi, FunnelStep, TrendPoint } from "@/lib/types";

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

// 获客漏斗（P-16.1.4）：扫码 → 进店 → 注册 → 首单
export const funnel: FunnelStep[] = [
  { step: "SCAN", count: 4210 },
  { step: "ENTER_STORE", count: 2860 },
  { step: "REGISTER", count: 1180 },
  { step: "FIRST_ORDER", count: 640 },
];
