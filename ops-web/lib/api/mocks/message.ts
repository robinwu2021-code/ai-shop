// 覆盖范围：消息触达与客服（P-14）。
import * as db from "@/lib/mock/db";
import { TICKET_TRANSITIONS, type FaqEntry, type NotifyLog, type Ticket } from "@/lib/types";
import type { MessageApi } from "../contracts/message";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

function findTicket(no: string): Ticket {
  const t = db.tickets.find((x) => x.ticketNo === no);
  if (!t) notFound("工单", "Ticket", no);
  return t;
}

export const messageMock: MessageApi = {
  listMsgTemplates: (q = {}) =>
    wait(db.paginate(db.msgTemplates, q.page, q.size, (t) => db.kwHit(q.keyword, t.templateNo, t.name, t.content))),

  setTemplateEnabled: async (templateNo, enabled) => {
    const t = db.msgTemplates.find((x) => x.templateNo === templateNo);
    if (!t) notFound("模板", "Template", templateNo);
    t.enabled = enabled;
    return wait(t, 400);
  },

  listPushTasks: (q = {}) =>
    wait(db.paginate(db.pushTasks, q.page, q.size, (t) => db.kwHit(q.keyword, t.taskNo, t.name, t.audience))),

  sendPushTask: async (taskNo) => {
    const t = db.pushTasks.find((x) => x.taskNo === taskNo);
    if (!t) notFound("推送任务", "Push task", taskNo);
    if (t.status === "SENT") fail("该任务已发送", "This task has already been sent");
    // 选了个空人群等于白发一次，而且发送记录会污染后面的效果分析
    if (t.estimatedReach <= 0) fail("预估触达为 0，人群可能是空的，请先确认人群再发送", "Estimated reach is 0 — the audience may be empty. Check it before sending");
    const tpl = db.msgTemplates.find((x) => x.templateNo === t.templateNo);
    if (!tpl?.enabled) fail("关联的消息模板已停用，无法发送", "The linked message template is disabled, so this cannot be sent");
    t.status = "SENT";
    return wait(t, 400);
  },

  cancelPushTask: async (taskNo) => {
    const t = db.pushTasks.find((x) => x.taskNo === taskNo);
    if (!t) notFound("推送任务", "Push task", taskNo);
    if (t.status === "SENT") fail("已发送的任务无法撤销", "A task that has been sent cannot be recalled");
    t.status = "CANCELLED";
    return wait(t, 400);
  },

  getNotifyQuota: async () => wait(db.notifyQuota),

  saveNotifyQuota: async (v) => {
    // 0 等于没有频控，但界面上看着像配了 —— 比不配更危险
    if (v.dailyPerUser <= 0) fail("单用户单日上限必须大于 0（0 等于没有频控）", "The daily per-user cap must be above 0 — at 0 there is no rate limit at all");
    if (v.minIntervalHours <= 0) fail("同模板最小间隔必须大于 0 小时", "The minimum gap between sends of one template must be over 0 hours");
    Object.assign(db.notifyQuota, v, { updatedAt: "2026-08-06T00:00:00Z", updatedBy: "admin" });
    return wait(db.notifyQuota, 400);
  },

  /*
   * 发送记录：mock 里给三条，覆盖三种形态 —— 成功的短信、成功的邮件、
   * **失败的那条**。只给成功的话，页面上「错误」那一列永远是空的，
   * 而它恰恰是这张表最要紧的一列（「他为什么没收到」）。
   */
  listNotifyLogs: (q = {}) => {
    const rows: NotifyLog[] = [
      { notifyNo: "NL0001", channel: "SMS", bizType: "OTP", target: "138****8888",
        templateCode: "SMS_474945291", status: "SENT",
        providerMsgId: "765413486616594710^0", createdAt: "2026-08-13T10:20:00Z" },
      { notifyNo: "NL0002", channel: "MAIL", bizType: "OPS_INIT_PASSWORD",
        target: "z***g@neargo.ai", templateCode: "【数智邻购】运营端账号已开通",
        status: "SENT", providerMsgId: "<abc@neargo.ai>", operatorNo: "E1001",
        createdAt: "2026-08-13T10:05:00Z" },
      { notifyNo: "NL0003", channel: "SMS", bizType: "TEST", target: "139****0000",
        status: "FAILED", error: "isv.TEMPLATE_MISSING_PARAMETERS 模板变量缺失",
        operatorNo: "E1001", createdAt: "2026-08-13T09:40:00Z" },
    ];
    return wait(db.paginate(rows, q.page, q.size,
      (r) => db.eqHit(q.channel, r.channel) && db.eqHit(q.status, r.status)), 300);
  },

  getCaptcha: () =>
    // 1x1 透明 png —— mock 下只要「有个图」，图上写什么无所谓
    wait({ captchaId: "mock-captcha", imageBase64:
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==" }, 200),

  testSendNotify: (v) => {
    if (v.captchaCode !== "1234") fail("图形验证码错误或已过期，请重新获取",
      "Captcha is wrong or expired. Please get a new one");
    return wait(undefined as unknown as void, 500);
  },

  listTickets: (q = {}) =>
    wait(
      db.paginate(db.tickets, q.page, q.size, (t) =>
        db.eqHit(q.status, t.status) &&
        db.eqHit(q.assignee, t.assignee) &&
        db.kwHit(q.keyword, t.ticketNo, t.title, t.userNickname, t.orderNo),
      ),
    ),

  assignTicket: async (ticketNo, assignee) => {
    const t = findTicket(ticketNo);
    if (!assignee?.trim()) fail("请指定处理人", "Pick who handles it");
    db.assertTransition(TICKET_TRANSITIONS, t.status, "ASSIGNED", "工单", "Ticket");
    t.assignee = assignee.trim();
    t.status = "ASSIGNED";
    return wait(t, 400);
  },

  replyTicket: async (ticketNo, reply) => {
    const t = findTicket(ticketNo);
    // 空回复会把单子推出待处理队列而用户什么也没收到 —— 比不回更糟
    if (!reply?.trim()) fail("回复内容必填", "The reply cannot be empty");
    db.assertTransition(TICKET_TRANSITIONS, t.status, "RESOLVED", "工单", "Ticket");
    t.reply = reply.trim();
    t.repliedAt = new Date().toISOString();
    t.repliedBy = t.assignee ?? "cs01";
    t.status = "RESOLVED";
    return wait(t, 400);
  },

  addProxyAction: async (ticketNo, action) => {
    const t = findTicket(ticketNo);
    // 代客操作是替用户改数据/退款，没有留痕就查不出是谁做的（矩阵 P-14.2.3）
    if (!action?.trim()) fail("代客操作内容必填，留痕要写清做了什么", "Say what you did on the customer's behalf — the record has to state it");
    if (!t.assignee) fail("请先分派处理人再记录代客操作", "Assign someone before logging an action taken for the customer");
    // mock 侧自己维护这个数组（真接口不下发它，见 types/message.ts 的注）
    (t.proxyActions ??= []).push(`${t.assignee} ${action.trim()}`);
    return wait(t, 400);
  },

  closeTicket: async (ticketNo) => {
    const t = findTicket(ticketNo);
    db.assertTransition(TICKET_TRANSITIONS, t.status, "CLOSED", "工单", "Ticket");
    t.status = "CLOSED";
    return wait(t, 400);
  },

  listFaqs: (q = {}) =>
    wait(db.paginate(db.faqs, q.page, q.size, (f) => db.kwHit(q.keyword, f.faqNo, f.question, f.answer, f.category))),

  saveFaq: async (v) => {
    if (!v.question?.trim()) fail("问题必填", "The question is required");
    const saved = db.upsert<FaqEntry>(
      db.faqs,
      { ...v, published: false, views: 0 },
      "faqNo",
      () => db.nextNo("FQ", db.faqs, 9000, "faqNo"),
    );
    return wait(saved, 400);
  },

  setFaqPublished: async (faqNo, published) => {
    const f = db.faqs.find((x) => x.faqNo === faqNo);
    if (!f) notFound("条目", "Entry", faqNo);
    // 空答案的条目上架后，用户点进去看到一片空白 —— 比没有这条更糟
    if (published && !f.answer.trim()) fail("答案为空，无法上架", "The answer is empty, so it cannot be published");
    f.published = published;
    return wait(f, 400);
  },
};
