// 团购与求团 mock（P-8）。刻意留了「改价 3 次的报价」与「毁约商家」——
// ADR-003 的两条约束（改价超阈禁止、毁约禁止报价）没有样本就验不到。
import type { DemandOrder, GroupCampaign, Quote } from "@/lib/types";

export const groupCampaigns: GroupCampaign[] = [
  { groupNo: "GB9001", merchantNo: "M902", merchantName: "老张水果店", skuTitle: "阳光玫瑰 2 斤装", originPrice: 3_980, groupPrice: 3_280, minCount: 10, joined: 7, status: "RUNNING", endAt: Date.parse("2026-08-07T16:00:00Z"), createdAt: Date.parse("2026-08-04T02:00:00Z") },
  { groupNo: "GB9002", merchantNo: "M903", merchantName: "邻家便利", skuTitle: "抽纸 3 层 12 包", originPrice: 2_990, groupPrice: 2_390, minCount: 20, joined: 24, status: "SUCCESS", endAt: Date.parse("2026-08-05T16:00:00Z"), createdAt: Date.parse("2026-08-02T02:00:00Z") },
  { groupNo: "GB9003", merchantNo: "M901", merchantName: "阿姨家的菜摊", skuTitle: "现摘毛豆 1kg", originPrice: 620, groupPrice: 520, minCount: 15, joined: 3, status: "PENDING", endAt: Date.parse("2026-08-09T16:00:00Z"), createdAt: Date.parse("2026-08-06T00:30:00Z") },
  { groupNo: "GB9004", merchantNo: "M905", merchantName: "快修家电服务", skuTitle: "空调清洗（2 台起）", originPrice: 12_800, groupPrice: 9_900, minCount: 5, joined: 1, status: "FAILED", endAt: Date.parse("2026-08-03T16:00:00Z"), createdAt: Date.parse("2026-07-30T02:00:00Z") },
];

export const demandOrders: DemandOrder[] = [
  { demandNo: "RQ9001", title: "小区统一订学生校服（150-160cm）", initiatorNickname: "王女士", communityNo: "C003", communityName: "梧桐苑", plusOneCount: 23, status: "QUOTING", quoteCount: 2, createdAt: "2026-08-03T10:00:00Z" },
  { demandNo: "RQ9002", title: "想团一批乳胶床垫（1.8m）", initiatorNickname: "老周", communityNo: "C001", communityName: "锦绣花园", plusOneCount: 11, status: "OPEN", quoteCount: 0, createdAt: "2026-08-05T12:00:00Z" },
  { demandNo: "RQ9003", title: "中秋月饼礼盒团购", initiatorNickname: "李慧", communityNo: "C002", communityName: "阳光里", plusOneCount: 46, status: "CHOSEN", quoteCount: 3, createdAt: "2026-07-28T02:00:00Z" },
  { demandNo: "RQ9004", title: "夏季凉席（已过季关闭）", initiatorNickname: "小满", communityNo: "C001", communityName: "锦绣花园", plusOneCount: 4, status: "CLOSED", quoteCount: 1, createdAt: "2026-06-20T02:00:00Z" },
];

export const quotes: Quote[] = [
  { quoteNo: "QT9001", demandNo: "RQ9001", demandTitle: "小区统一订学生校服（150-160cm）", merchantNo: "M903", merchantName: "邻家便利", price: 12_800, minQty: 20, validTo: Date.parse("2026-08-10T16:00:00Z"), priceChanges: 0, breached: false, createdAt: Date.parse("2026-08-04T02:00:00Z") },
  // 改价 3 次：用来验「超阈禁止再改」
  { quoteNo: "QT9002", demandNo: "RQ9001", demandTitle: "小区统一订学生校服（150-160cm）", merchantNo: "M905", merchantName: "快修家电服务", price: 13_600, minQty: 15, validTo: Date.parse("2026-08-09T16:00:00Z"), priceChanges: 3, breached: false, createdAt: Date.parse("2026-08-04T06:00:00Z") },
  { quoteNo: "QT9003", demandNo: "RQ9003", demandTitle: "中秋月饼礼盒团购", merchantNo: "M902", merchantName: "老张水果店", price: 8_800, minQty: 30, validTo: Date.parse("2026-08-20T16:00:00Z"), priceChanges: 1, breached: false, createdAt: Date.parse("2026-07-29T02:00:00Z") },
  // 毁约记录：M906 累计毁约，禁止对新需求报价
  { quoteNo: "QT9004", demandNo: "RQ9004", demandTitle: "夏季凉席（已过季关闭）", merchantNo: "M906", merchantName: "夜市烧烤", price: 5_900, minQty: 10, validTo: Date.parse("2026-06-30T16:00:00Z"), priceChanges: 2, breached: true, createdAt: Date.parse("2026-06-21T02:00:00Z") },
];
