// 消息与客服 mock（P-14）。
import type { FaqEntry, MsgTemplate, NotifyQuota, PushTask, Ticket } from "@/lib/types";

export const msgTemplates: MsgTemplate[] = [
  { templateNo: "MT9001", name: "到货提醒", channel: "SUBSCRIBE", content: "您在{社区}的订单已到达{自提点}，取货码 {code}，请于 {deadline} 前取货。", enabled: true, sentCount: 3820 },
  { templateNo: "MT9002", name: "拼团成功", channel: "SUBSCRIBE", content: "「{团名}」已成团，预计 {date} 到货。", enabled: true, sentCount: 640 },
  { templateNo: "MT9003", name: "逾期未取提醒", channel: "PUSH", content: "您有 {n} 件商品即将超过取货期限。", enabled: true, sentCount: 210 },
  { templateNo: "MT9004", name: "系统维护公告", channel: "INBOX", content: "系统将于 {time} 维护，期间下单可能受影响。", enabled: false, sentCount: 12 },
];

export const pushTasks: PushTask[] = [
  { taskNo: "PT9001", name: "周五生鲜到货提醒", templateNo: "MT9001", audience: "锦绣花园 + 阳光里有未取订单的用户", estimatedReach: 186, status: "SCHEDULED", scheduledAt: "2026-08-07T09:00:00Z", createdAt: "2026-08-06T00:30:00Z" },
  { taskNo: "PT9002", name: "新人礼包召回", templateNo: "MT9003", audience: "注册 7 天未下单", estimatedReach: 412, status: "SENT", createdAt: "2026-08-04T02:00:00Z" },
  // 空人群：发了等于白发一次，用来验"预估触达 0 不许发"
  { taskNo: "PT9003", name: "梧桐苑开城通知（人群待定）", templateNo: "MT9004", audience: "梧桐苑已注册用户", estimatedReach: 0, status: "DRAFT", createdAt: "2026-08-06T01:00:00Z" },
];

export const notifyQuota: NotifyQuota = {
  dailyPerUser: 3,
  minIntervalHours: 6,
  updatedAt: "2026-07-15T02:00:00Z",
  updatedBy: "admin",
};

export const tickets: Ticket[] = [
  { ticketNo: "TK9001", title: "取货码显示已核销但没拿到货", userNickname: "海棠", orderNo: "SO2026080506", status: "OPEN", proxyActions: [], createdAt: "2026-08-06T01:10:00Z" },
  { ticketNo: "TK9002", title: "想改配送地址", userNickname: "阿May", orderNo: "SO2026080504", status: "ASSIGNED", assignee: "cs02", proxyActions: ["cs02 代客修改收货地址：阳光里 6-2 → 阳光里 8-1"], createdAt: "2026-08-05T10:00:00Z" },
  { ticketNo: "TK9003", title: "咨询自提点营业时间", userNickname: "小满", status: "RESOLVED", assignee: "cs02", proxyActions: [], createdAt: "2026-08-04T03:00:00Z" },
  { ticketNo: "TK9004", title: "重复扣款", userNickname: "老周", orderNo: "SO2026080503", status: "CLOSED", assignee: "cs02", proxyActions: ["cs02 代客发起退款：¥18.60"], createdAt: "2026-08-02T06:00:00Z" },
];

export const faqs: FaqEntry[] = [
  { faqNo: "FQ9001", question: "自提码在哪里看？", answer: "在「我的 - 订单」里点开订单详情，页面顶部就是取货码。", category: "取货", published: true, views: 2841 },
  { faqNo: "FQ9002", question: "生鲜称重差价怎么算？", answer: "按实际称重多退少补，差价在取货后 24 小时内自动结算。", category: "生鲜", published: true, views: 1620 },
  { faqNo: "FQ9003", question: "逾期未取会怎样？", answer: "按平台逾期规则处理：默认顺延到下一批，超过顺延上限后按作废退款。", category: "取货", published: true, views: 980 },
  { faqNo: "FQ9004", question: "如何申请开店？", answer: "", category: "商家", published: false, views: 0 },
];
