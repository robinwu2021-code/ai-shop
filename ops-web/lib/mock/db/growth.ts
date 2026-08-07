// 增长与归因 mock（P-9）。归因链路刻意造了三种典型：正常、冲突（B1 的现实场景）、
// 异常裂变（同设备批量注册后互相邀请）—— 后两种是这套配置存在的理由。
import type { AttributionRule, AttributionTrace, FissionCampaign } from "@/lib/types";

export const attributionRule: AttributionRule = {
  // 矩阵 §六 P-9.1.1 明写的顺序：店铺码 > 邀请人 > 渠道
  priority: ["STORE_CODE", "INVITER", "CHANNEL"],
  windowDays: 30,
  conflictPolicy: "KEEP_FIRST",
  newUserFactors: ["DEVICE", "PHONE"],
  updatedAt: "2026-07-20T02:00:00Z",
  updatedBy: "admin",
};

export const attributionTraces: AttributionTrace[] = [
  { traceNo: "AT9001", userNickname: "小满", source: "STORE_CODE", sourceRef: "shop_M903_c2（邻家便利）", attributedAt: "2026-07-28T02:00:00Z", orderNo: "SO2026080501", riskSignals: [] },
  { traceNo: "AT9002", userNickname: "老周", source: "INVITER", sourceRef: "海棠", attributedAt: "2026-08-01T06:00:00Z", orderNo: "SO2026080503", riskSignals: [] },
  // B1 的现实场景：已归属 A 店，又扫了 B 店的码
  { traceNo: "AT9003", userNickname: "阿May", source: "STORE_CODE", sourceRef: "shop_M902_c1（老张水果店）", attributedAt: "2026-08-04T09:00:00Z", conflictWith: "AT9004", riskSignals: [] },
  { traceNo: "AT9004", userNickname: "阿May", source: "STORE_CODE", sourceRef: "shop_M903_c2（邻家便利）", attributedAt: "2026-08-05T10:00:00Z", orderNo: "SO2026080504", conflictWith: "AT9003", riskSignals: [] },
  // 异常裂变：同设备批量注册后互相邀请
  { traceNo: "AT9005", userNickname: "用户8821", source: "INVITER", sourceRef: "用户8820", attributedAt: "2026-08-05T14:00:00Z", riskSignals: ["同设备", "短时集中"] },
  { traceNo: "AT9006", userNickname: "用户8822", source: "INVITER", sourceRef: "用户8820", attributedAt: "2026-08-05T14:02:00Z", riskSignals: ["同设备", "同 IP", "短时集中"] },
  { traceNo: "AT9007", userNickname: "梧桐苑 12-3", source: "CHANNEL", sourceRef: "物业公众号推文", attributedAt: "2026-08-02T02:00:00Z", orderNo: "SO2026080505", riskSignals: [] },
];

export const fissionCampaigns: FissionCampaign[] = [
  { fissionNo: "FS9001", name: "邀请邻居得 5 元券", rewardType: "COUPON", couponNo: "CP9001", inviterCount: 1, inviteeCount: 1, enabled: true, invitedCount: 428, convertedCount: 186, createdAt: "2026-07-28T02:00:00Z" },
  { fissionNo: "FS9002", name: "老带新双人券（暂停）", rewardType: "COUPON", couponNo: "CP9002", inviterCount: 2, inviteeCount: 1, enabled: false, invitedCount: 96, convertedCount: 31, createdAt: "2026-07-10T02:00:00Z" },
];
