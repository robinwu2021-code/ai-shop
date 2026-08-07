// 交易域 mock 数据（P-4.1）。按商家拆单：parentNo 相同即同一次结算拆出的子订单（E3）。
import type { Order } from "@/lib/types";

export const orders: Order[] = [
  {
    orderNo: "SO2026080501", parentNo: "PO20260805A", status: "ARRIVED",
    merchantNo: "M903", merchantName: "邻家便利",
    communityNo: "C002", communityName: "阳光里", pickupNo: "P002",
    fulfillType: "PICKUP_STORE", trafficSource: "MERCHANT_OWNED",
    buyerNickname: "小满", items: [{ skuNo: "SKU1001", title: "本地小番茄 500g", qty: 2, price: 890 }],
    payAmount: 1780, createdAt: "2026-08-05T00:12:00Z", paidAt: "2026-08-05T00:13:20Z",
  },
  {
    orderNo: "SO2026080502", parentNo: "PO20260805A", status: "PREPARING",
    merchantNo: "M902", merchantName: "老张水果店",
    communityNo: "C001", communityName: "锦绣花园", pickupNo: "P001",
    fulfillType: "PICKUP_STORE", trafficSource: "MERCHANT_OWNED",
    buyerNickname: "小满", items: [{ skuNo: "SKU2003", title: "阳光玫瑰 2 斤装", qty: 1, price: 3980 }],
    payAmount: 3980, createdAt: "2026-08-05T00:12:00Z", paidAt: "2026-08-05T00:13:20Z",
  },
  {
    orderNo: "SO2026080503", parentNo: "PO20260805B", status: "PENDING_PAY",
    merchantNo: "M901", merchantName: "阿姨家的菜摊",
    communityNo: "C001", communityName: "锦绣花园", pickupNo: "P001",
    fulfillType: "PICKUP_NEIGHBOR", trafficSource: "PLATFORM",
    buyerNickname: "老周", items: [{ skuNo: "SKU3007", title: "现摘毛豆 1kg", qty: 3, price: 620 }],
    payAmount: 1860, createdAt: "2026-08-05T01:40:00Z", paidAt: null,
  },
  {
    orderNo: "SO2026080504", parentNo: "PO20260805C", status: "DELIVERING",
    merchantNo: "M903", merchantName: "邻家便利",
    communityNo: "C002", communityName: "阳光里",
    fulfillType: "MERCHANT_DELIVERY", trafficSource: "INVITE",
    buyerNickname: "阿May", items: [
      { skuNo: "SKU1102", title: "抽纸 3 层 12 包", qty: 1, price: 2990 },
      { skuNo: "SKU1103", title: "洗衣液 2kg", qty: 1, price: 3560 },
    ],
    payAmount: 6550, createdAt: "2026-08-04T09:05:00Z", paidAt: "2026-08-04T09:06:10Z",
  },
  {
    orderNo: "SO2026080505", parentNo: "PO20260804D", status: "COMPLETED",
    merchantNo: "M905", merchantName: "快修家电服务",
    communityNo: "C003", communityName: "梧桐苑",
    fulfillType: "SERVICE", trafficSource: "CHANNEL",
    buyerNickname: "梧桐苑 12-3", items: [{ skuNo: "SKU9001", title: "空调深度清洗（1 台）", qty: 1, price: 12800 }],
    payAmount: 12800, createdAt: "2026-08-03T02:00:00Z", paidAt: "2026-08-03T02:01:00Z",
  },
  {
    orderNo: "SO2026080506", parentNo: "PO20260803E", status: "AFTER_SALE",
    merchantNo: "M902", merchantName: "老张水果店",
    communityNo: "C001", communityName: "锦绣花园", pickupNo: "P001",
    fulfillType: "PICKUP_STORE", trafficSource: "MERCHANT_OWNED",
    buyerNickname: "海棠", items: [{ skuNo: "SKU2011", title: "冰糖心苹果 5 斤", qty: 1, price: 4580 }],
    payAmount: 4580, createdAt: "2026-08-02T07:20:00Z", paidAt: "2026-08-02T07:21:00Z",
  },
];

/**
 * 各订单在当前状态停留的时长（分钟）。
 *
 * ⚠️ `statusAt` 用「相对现在」生成而不是写死时间戳：写死的话过一天，
 * 所有非终态订单都会超时，异常队列退化成订单列表 —— 那就没法演示也没法开发了。
 * createdAt/paidAt 仍是固定的业务数据，只有"卡了多久"跟着当下走。
 */
const STATUS_AGE_MINUTES: Record<string, number> = {
  SO2026080501: 2000, // ARRIVED 超过 1440 → 异常
  SO2026080502: 30,   // PREPARING 未超 120 → 正常
  SO2026080503: 45,   // PENDING_PAY 超过 15 → 关单任务没跑
  SO2026080504: 600,  // DELIVERING 超过 240 → 异常
  SO2026080506: 100,  // AFTER_SALE 未超 2880 → 正常
};

for (const o of orders) {
  o.statusAt = new Date(Date.now() - (STATUS_AGE_MINUTES[o.orderNo] ?? 5) * 60_000).toISOString();
}

/** 人工干预留痕。异常单改状态、代客取消都往这里记。 */
export const orderInterventions: import("@/lib/types").OrderIntervention[] = [];
