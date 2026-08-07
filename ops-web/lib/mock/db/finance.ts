// 结算与资金 mock（P-12）。覆盖五种状态与两类失败（可重试 / 已超时），
// 否则「重试上限」与「超时兜底」两条规则在页面上验不到。
import type { FeeRule, Settlement, SplitRecord } from "@/lib/types";

export const settlements: Settlement[] = [
  {
    settleNo: "ST9001", merchantNo: "M903", merchantName: "邻家便利", period: "2026-08-上",
    orderCount: 128, grossAmount: 486_500, platformFee: 0, serviceFee: 7_290, netAmount: 479_210,
    // 商家自带客流为主 → 平台佣金 0（R16 建议值）
    status: "PENDING", retryCount: 0, frozenAt: "2026-08-06T00:00:00Z", createdAt: "2026-08-06T00:00:00Z",
  },
  {
    settleNo: "ST9002", merchantNo: "M902", merchantName: "老张水果店", period: "2026-08-上",
    orderCount: 64, grossAmount: 238_400, platformFee: 4_768, serviceFee: 3_576, netAmount: 230_056,
    status: "SPLIT", retryCount: 0, frozenAt: "2026-08-05T00:00:00Z", createdAt: "2026-08-05T00:00:00Z",
  },
  {
    // 失败 2 次：再失败一次就到上限转人工
    settleNo: "ST9003", merchantNo: "M905", merchantName: "快修家电服务", period: "2026-08-上",
    orderCount: 12, grossAmount: 153_600, platformFee: 4_608, serviceFee: 0, netAmount: 148_992,
    status: "FAILED", retryCount: 2, failReason: "分账接收方账户状态异常（PSP 返回 ACCOUNT_ABNORMAL）",
    frozenAt: "2026-08-04T00:00:00Z", createdAt: "2026-08-04T00:00:00Z",
  },
  {
    // 未报备分账接收方：执行分账会被拒（M901 的 settleAccountReady = false）
    settleNo: "ST9004", merchantNo: "M901", merchantName: "阿姨家的菜摊", period: "2026-08-上",
    orderCount: 23, grossAmount: 41_800, platformFee: 836, serviceFee: 627, netAmount: 40_337,
    status: "PENDING", retryCount: 0, frozenAt: "2026-08-06T00:00:00Z", createdAt: "2026-08-06T00:00:00Z",
  },
  {
    // 冻结已久：用来验超时兜底
    settleNo: "ST9005", merchantNo: "M906", merchantName: "夜市烧烤", period: "2026-07-下",
    orderCount: 8, grossAmount: 32_000, platformFee: 640, serviceFee: 480, netAmount: 30_880,
    status: "PENDING", retryCount: 0, frozenAt: "2026-07-01T00:00:00Z", createdAt: "2026-07-01T00:00:00Z",
  },
];

export const splitRecords: SplitRecord[] = [
  { splitNo: "SP9001", settleNo: "ST9001", orderNo: "SO2026080501", merchantName: "邻家便利", trafficSource: "MERCHANT_OWNED", grossAmount: 1_780, feeRate: 0, platformFee: 0, pickupNo: "P002", serviceFee: 27, netAmount: 1_753 },
  { splitNo: "SP9002", settleNo: "ST9001", orderNo: "SO2026080504", merchantName: "邻家便利", trafficSource: "INVITE", grossAmount: 6_550, feeRate: 300, platformFee: 197, serviceFee: 0, netAmount: 6_353 },
  { splitNo: "SP9003", settleNo: "ST9002", orderNo: "SO2026080502", merchantName: "老张水果店", trafficSource: "MERCHANT_OWNED", grossAmount: 3_980, feeRate: 0, platformFee: 0, pickupNo: "P001", serviceFee: 60, netAmount: 3_920 },
  { splitNo: "SP9004", settleNo: "ST9002", orderNo: "SO2026080506", merchantName: "老张水果店", trafficSource: "MERCHANT_OWNED", grossAmount: 4_580, feeRate: 0, platformFee: 0, pickupNo: "P001", serviceFee: 69, netAmount: 4_511 },
  { splitNo: "SP9005", settleNo: "ST9003", orderNo: "SO2026080505", merchantName: "快修家电服务", trafficSource: "CHANNEL", grossAmount: 12_800, feeRate: 500, platformFee: 640, serviceFee: 0, netAmount: 12_160 },
];

export const feeRule: FeeRule = {
  byTrafficSource: {
    // R16 建议：自带客流零佣金 —— 抽了商家就会把客人带去别处成交
    MERCHANT_OWNED: 0,
    PLATFORM: 500,
    INVITE: 300,
    CHANNEL: 500,
  },
  pickupServiceFeeRate: 150,
  freezeDays: 15,
  updatedAt: "2026-07-25T02:00:00Z",
  updatedBy: "finance01",
};
