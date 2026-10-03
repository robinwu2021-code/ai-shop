// 支付管理 mock 数据（P-4.2）。
//
// 差异样本刻意三类都有，且**每类都对应一种真实成因**，不是随机造的数：
// - CHANNEL_ONLY：关单太快 → 用户付了钱但订单已关（这正是关单策略那一页存在的理由）
// - PLATFORM_ONLY：重复回调导致平台多记一笔
// - AMOUNT_DIFF：优惠券金额算错，两边差一个券面额
import type { CloseRule, ReconDiff } from "@/lib/types";

export const reconDiffs: ReconDiff[] = [
  {
    diffNo: "RC2026080601", billDate: "2026-08-05", channel: "WECHAT",
    channelTxnNo: "4200002181202608051234", orderNo: null,
    type: "CHANNEL_ONLY", channelAmount: 1860, platformAmount: 0,
    status: "PENDING", createdAt: "2026-08-06T01:00:00Z",
  },
  {
    diffNo: "RC2026080602", billDate: "2026-08-05", channel: "WECHAT",
    channelTxnNo: "4200002181202608055678", orderNo: null,
    type: "CHANNEL_ONLY", channelAmount: 6550, platformAmount: 0,
    status: "PENDING", createdAt: "2026-08-06T01:00:00Z",
  },
  {
    diffNo: "RC2026080603", billDate: "2026-08-05", channel: "WECHAT",
    channelTxnNo: null, orderNo: "SO2026080505",
    type: "PLATFORM_ONLY", channelAmount: 0, platformAmount: 12800,
    status: "PENDING", createdAt: "2026-08-06T01:00:00Z",
  },
  {
    diffNo: "RC2026080604", billDate: "2026-08-05", channel: "WECHAT",
    channelTxnNo: "4200002181202608059012", orderNo: "SO2026080506",
    type: "AMOUNT_DIFF", channelAmount: 4580, platformAmount: 3980,
    status: "PENDING", createdAt: "2026-08-06T01:00:00Z",
  },
  {
    diffNo: "RC2026080405", billDate: "2026-08-04", channel: "WECHAT",
    channelTxnNo: "4200002181202608043344", orderNo: "SO2026080401",
    type: "AMOUNT_DIFF", channelAmount: 2000, platformAmount: 1800,
    status: "RESOLVED", resolution: "商家改价后用户用旧价下单，差额 ¥2 已按新价退回",
    createdAt: "2026-08-05T01:00:00Z", resolvedAt: "2026-08-05T03:20:00Z", resolvedBy: "finance01",
  },
];

/** 关单策略。默认 15 分钟 —— 微信收银台的正常停留时间在 1~3 分钟，留 5 倍余量。 */
export const closeRule: CloseRule = {
  unpaidMinutes: 15,
  remindBeforeMinutes: 5,
  autoRefundOnLateCallback: false,
  updatedAt: "2026-07-28T10:00:00Z",
  updatedBy: "finance01",
};
