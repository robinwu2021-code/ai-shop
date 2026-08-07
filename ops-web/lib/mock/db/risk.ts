// 风控 mock（P-16.2）。事件的 refs 指向归因链路号 —— 风控页能跳过去看"人是怎么进来的"。
import type { BlacklistEntry, RiskEvent, RiskRule } from "@/lib/types";

export const riskEvents: RiskEvent[] = [
  {
    eventNo: "RK9001", type: "ABNORMAL_FISSION", subject: "用户8820", subjectType: "USER",
    signals: ["同设备注册 5 个账号", "24 小时内互相邀请", "无真实下单"],
    refs: ["AT9005", "AT9006"], status: "OPEN", createdAt: "2026-08-05T14:30:00Z",
  },
  {
    eventNo: "RK9002", type: "FAKE_ORDER", subject: "老张水果店", subjectType: "MERCHANT",
    signals: ["同一买家 24 小时内下单 12 笔", "全部秒核销", "收货人手机号相同"],
    refs: ["SO2026080502"], status: "OPEN", createdAt: "2026-08-05T09:00:00Z",
  },
  {
    eventNo: "RK9003", type: "MALICIOUS_REFUND", subject: "海棠", subjectType: "USER",
    signals: ["30 天内退款 7 次", "均为仅退款", "凭证图片重复"],
    refs: ["AS9001"], status: "OPEN", createdAt: "2026-08-05T15:00:00Z",
  },
  {
    eventNo: "RK9004", type: "ABNORMAL_FISSION", subject: "d8f2a1c9（设备）", subjectType: "DEVICE",
    signals: ["同设备 14 天内注册 9 个账号"],
    refs: [], status: "CONFIRMED", createdAt: "2026-07-30T02:00:00Z",
    verdict: "确认为工作室批量注册，已拉黑设备并作废关联券",
  },
  {
    eventNo: "RK9005", type: "FAKE_ORDER", subject: "邻家便利", subjectType: "MERCHANT",
    signals: ["单日订单量突增 8 倍"],
    refs: [], status: "DISMISSED", createdAt: "2026-07-25T02:00:00Z",
    verdict: "核实为社区团购日常促销，非刷单",
  },
];

export const blacklists: BlacklistEntry[] = [
  {
    blackNo: "BL9001", subjectType: "DEVICE", subject: "d8f2a1c9",
    reason: "批量注册工作室设备，14 天注册 9 个账号",
    until: "2027-07-30T00:00:00Z", appealStatus: "NONE", active: true, createdAt: "2026-07-30T03:00:00Z",
  },
  {
    blackNo: "BL9002", subjectType: "USER", subject: "用户8820",
    reason: "组织异常裂变，套取新人券",
    until: "2026-11-05T00:00:00Z", appealStatus: "PENDING",
    appealReason: "我是帮家里几位老人注册的，不是工作室，希望复核。",
    active: true, createdAt: "2026-08-05T15:00:00Z",
  },
  {
    blackNo: "BL9003", subjectType: "USER", subject: "旧账号3311",
    reason: "恶意退款画像命中",
    until: "2026-08-01T00:00:00Z", appealStatus: "ACCEPTED",
    appealReason: "退款均因商家漏发，有聊天记录。",
    appealVerdict: "核实属实，商家侧问题，解除拉黑并向商家发起履约质量提醒",
    active: false, createdAt: "2026-07-02T02:00:00Z",
  },
];

export const riskRules: RiskRule[] = [
  { type: "FAKE_ORDER", threshold: 10, autoBlock: false, updatedAt: "2026-07-20T02:00:00Z" },
  { type: "ABNORMAL_FISSION", threshold: 5, autoBlock: true, updatedAt: "2026-07-20T02:00:00Z" },
  { type: "MALICIOUS_REFUND", threshold: 5, autoBlock: false, updatedAt: "2026-07-20T02:00:00Z" },
];
