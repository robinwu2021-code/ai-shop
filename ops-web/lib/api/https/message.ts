// 覆盖范围：见 contracts/message.ts。
import { client } from "../http-client";
import type { MessageApi } from "../contracts/message";

export const messageHttp: MessageApi = {
  listMsgTemplates: (q) => client.get("/ops/msg-templates", q),
  setTemplateEnabled: (no, enabled) => client.post(`/ops/msg-templates/${no}/enabled`, { enabled }),
  listPushTasks: (q) => client.get("/ops/push-tasks", q),
  sendPushTask: (no) => client.post(`/ops/push-tasks/${no}/send`),
  cancelPushTask: (no) => client.post(`/ops/push-tasks/${no}/cancel`),
  getNotifyQuota: () => client.get("/ops/notify-quota"),
  saveNotifyQuota: (v) => client.post("/ops/notify-quota", v),
  listNotifyLogs: (q) => client.get("/ops/notify-logs", q),
  getCaptcha: () => client.get("/ops/captcha"),
  testSendNotify: (v) => client.post("/ops/notify-logs/test-send", v),
  listTickets: (q) => client.get("/ops/tickets", q),
  assignTicket: (no, assignee) => client.post(`/ops/tickets/${no}/assign`, { assignee }),
  replyTicket: (no, reply) => client.post(`/ops/tickets/${no}/reply`, { reply }),
  addProxyAction: (no, action) => client.post(`/ops/tickets/${no}/proxy-actions`, { action }),
  closeTicket: (no) => client.post(`/ops/tickets/${no}/close`),
  listFaqs: (q) => client.get("/ops/faqs", q),
  saveFaq: (v) => client.post("/ops/faqs", v),
  setFaqPublished: (no, published) => client.post(`/ops/faqs/${no}/published`, { published }),
};
