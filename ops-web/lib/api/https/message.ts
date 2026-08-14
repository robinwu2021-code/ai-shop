// 覆盖范围：见 contracts/message.ts。
import { client } from "../http-client";
import type { MessageApi } from "../contracts/message";

export const messageHttp: MessageApi = {
  listMsgTemplates: (q) => client.get("/ops/msg-templates", q),
  setTemplateEnabled: (no, enabled) => client.post(`/ops/msg-templates/${no}/enabled`, { enabled }),
  getNotifyQuota: () => client.get("/ops/notify-quota"),
  saveNotifyQuota: (v) => client.post("/ops/notify-quota", v),
  listNotifyLogs: (q) => client.get("/ops/notify-logs", q),
  listNotifyChannels: () => client.get("/ops/notify-channels"),
  getWxTemplates: () => client.get("/ops/notify-channels/wx-templates"),
  saveWxTemplates: (v) => client.post("/ops/notify-channels/wx-templates", v),
  testSendInApp: (v) => client.post("/ops/notify-logs/test-inapp", v),
  getCaptcha: () => client.get("/ops/captcha"),
  testSendNotify: (v) => client.post("/ops/notify-logs/test-send", v),
  precheckNotifyTarget: (v) => client.post("/ops/notify-logs/precheck", v),
  listInAppMessages: (q) => client.get("/ops/inapp-messages", q),
  getDefaultLang: () => client.get("/ops/notify-channels/default-lang"),
  saveDefaultLang: (lang) => client.post("/ops/notify-channels/default-lang", { lang }),
  listTickets: (q) => client.get("/ops/tickets", q),
  assignTicket: (no, assignee) => client.post(`/ops/tickets/${no}/assign`, { assignee }),
  replyTicket: (no, reply) => client.post(`/ops/tickets/${no}/reply`, { reply }),
  addProxyAction: (no, action) => client.post(`/ops/tickets/${no}/proxy-actions`, { action }),
  closeTicket: (no) => client.post(`/ops/tickets/${no}/close`),
  listInbox: () => client.get("/ops/message"),
  inboxUnread: () => client.get("/ops/message/unread-count"),
  readInbox: (no) => client.post(`/ops/message/${no}/read`),
  readAllInbox: () => client.post("/ops/message/read-all"),
  listFaqs: (q) => client.get("/ops/faqs", q),
  saveFaq: (v) => client.post("/ops/faqs", v),
  setFaqPublished: (no, published) => client.post(`/ops/faqs/${no}/published`, { published }),
};
