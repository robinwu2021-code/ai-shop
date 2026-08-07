// 履约调度 mock（P-5.1）。批次覆盖四个状态，分拣只挂在已签收批次上。
import type { ArrivalBatch, OverdueRule, RedeemStat, SortingRow } from "@/lib/types";

export const batches: ArrivalBatch[] = [
  { batchNo: "B20260806A", status: "SIGNED", communityNo: "C001", communityName: "锦绣花园", pickupNo: "P001", pickupName: "邻家便利·锦绣店", planArriveAt: "2026-08-06T08:00:00Z", vehicle: "浙A·2301 / 老陈", itemCount: 142, merchantCount: 4 },
  { batchNo: "B20260806B", status: "ARRIVED", communityNo: "C002", communityName: "阳光里", pickupNo: "P002", pickupName: "老张水果店", planArriveAt: "2026-08-06T07:30:00Z", vehicle: "浙A·5518 / 小吴", itemCount: 96, merchantCount: 3 },
  { batchNo: "B20260806C", status: "DISPATCHED", communityNo: "C003", communityName: "梧桐苑", pickupNo: "P004", pickupName: "梧桐苑 12-3 王女士家", planArriveAt: "2026-08-06T09:30:00Z", vehicle: "浙A·7742 / 老周", itemCount: 28, merchantCount: 1 },
  { batchNo: "B20260807A", status: "PLANNED", communityNo: "C001", communityName: "锦绣花园", pickupNo: "P001", pickupName: "邻家便利·锦绣店", planArriveAt: "2026-08-07T08:00:00Z", vehicle: "待派", itemCount: 118, merchantCount: 5 },
];

// 只有已签收批次（B20260806A）的货才进分拣 —— 与 P-5.1.1→5.1.2 的先后关系一致。
export const sorting: SortingRow[] = [
  { pickupNo: "P001", pickupName: "邻家便利·锦绣店", skuNo: "SKU1001", title: "本地小番茄 500g", merchantName: "邻家便利", qty: 24, shortQty: 0 },
  { pickupNo: "P001", pickupName: "邻家便利·锦绣店", skuNo: "SKU2003", title: "阳光玫瑰 2 斤装", merchantName: "老张水果店", qty: 12, shortQty: 2 },
  { pickupNo: "P001", pickupName: "邻家便利·锦绣店", skuNo: "SKU3007", title: "现摘毛豆 1kg", merchantName: "阿姨家的菜摊", qty: 31, shortQty: 0 },
  { pickupNo: "P001", pickupName: "邻家便利·锦绣店", skuNo: "SKU1102", title: "抽纸 3 层 12 包", merchantName: "邻家便利", qty: 8, shortQty: 1 },
];

export const redeemStats: RedeemStat[] = [
  { pickupNo: "P001", pickupName: "邻家便利·锦绣店", communityName: "锦绣花园", pending: 23, redeemed: 108, overdue: 4, rate: 0.8 },
  { pickupNo: "P002", pickupName: "老张水果店", communityName: "阳光里", pending: 41, redeemed: 52, overdue: 1, rate: 0.55 },
  { pickupNo: "P004", pickupName: "梧桐苑 12-3 王女士家", communityName: "梧桐苑", pending: 6, redeemed: 21, overdue: 0, rate: 0.78 },
  { pickupNo: "P005", pickupName: "梧桐苑 5-1 李先生家", communityName: "梧桐苑", pending: 2, redeemed: 9, overdue: 2, rate: 0.69 },
];

// 单例配置：mock 层用一个可变对象承载，保存后重新读取应拿到新值。
export const overdueRule: OverdueRule = {
  action: "POSTPONE",
  graceHours: 24,
  maxPostpone: 2,
  updatedAt: "2026-07-30T06:00:00Z",
  updatedBy: "admin",
};
