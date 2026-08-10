// 售后 mock（P-6.1）。orderNo 与 lib/mock/db/order.ts 对齐 —— 退款金额校验要跨域查订单，
// 编的单号对不上的话那条规则永远验不到。
import type { AfterSale, FastRefundRule } from "@/lib/types";

export const afterSales: AfterSale[] = [
  {
    asNo: "AS9001", orderNo: "SO2026080506", merchantNo: "M902", merchantName: "老张水果店",
    buyerNickname: "海棠", type: "REFUND_ONLY", status: "ARBITRATING",
    amount: 2_290, reason: "5 斤苹果里有 2 个坏的，商家只肯退 5 元", evidenceCount: 3,
    createdAt: "2026-08-05T14:00:00Z",
  },
  {
    asNo: "AS9002", orderNo: "SO2026080501", merchantNo: "M903", merchantName: "邻家便利",
    buyerNickname: "小满", type: "REFUND_ONLY", status: "APPLIED",
    amount: 890, reason: "小番茄少发一份", evidenceCount: 1,
    createdAt: "2026-08-06T01:20:00Z",
  },
  {
    asNo: "AS9003", orderNo: "SO2026080504", merchantNo: "M903", merchantName: "邻家便利",
    buyerNickname: "阿May", type: "RETURN_REFUND", status: "REFUNDING",
    amount: 3_560, reason: "洗衣液漏液，包装破损", evidenceCount: 2,
    createdAt: "2026-08-05T09:10:00Z",
  },
  {
    asNo: "AS9004", orderNo: "SO2026080505", merchantNo: "M905", merchantName: "快修家电服务",
    buyerNickname: "梧桐苑 12-3", type: "REFUND_ONLY", status: "REJECTED",
    amount: 12_800, reason: "清洗后第二天又有异味", evidenceCount: 0,
    createdAt: "2026-08-04T02:00:00Z",
  },
  {
    asNo: "AS9005", orderNo: "SO2026080502", merchantNo: "M902", merchantName: "老张水果店",
    buyerNickname: "小满", type: "REFUND_ONLY", status: "REFUNDED",
    amount: 1_200, reason: "阳光玫瑰有压伤（极速退自动通过）", evidenceCount: 1,
    liability: "MERCHANT", share: { platform: 0, merchant: 100, pickup: 0 },
    verdict: "小额极速退，系统自动通过，责任按供货方计",
    createdAt: "2026-08-03T08:00:00Z",
  },
];

export const fastRefundRule: FastRefundRule = {
  enabled: true,
  maxAmount: 2_000,
  withinHours: 72,
  categories: [],
  updatedAt: "2026-07-20T02:00:00Z",
  updatedBy: "admin",
};
