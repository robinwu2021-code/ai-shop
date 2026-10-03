// 覆盖范围：消息触达与客服（P-14）。
import * as db from "@/lib/mock/db";
import { TICKET_TRANSITIONS, type FaqEntry, type NotifyChannelRow, type NotifyLog, type NotifyPushTask, type Ticket } from "@/lib/types";
import type { MessageApi } from "../contracts/message";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

// 渠道注册表 mock 状态。平台通道本地全走桩（= 真实状态，编成 READY 会误导）；
// INAPP 就绪且锁定（站内信不可关）。启停可变，setChannelEnabled 就地改。
const channels: NotifyChannelRow[] = [
  { channelNo: "NCH-SMS-ALI", channelType: "SMS", provider: "ALI", scope: "PLATFORM", ownerNo: "", enabled: true, status: "STUB", priority: 100, credRef: "shop.sms.ali", configJson: "{}", missingCreds: [], locked: false },
  { channelNo: "NCH-MAIL-SMTP", channelType: "MAIL", provider: "SMTP", scope: "PLATFORM", ownerNo: "", enabled: true, status: "STUB", priority: 100, credRef: "shop.mail", configJson: "{}", missingCreds: [], locked: false },
  { channelNo: "NCH-WXSUB-WECHAT", channelType: "WXSUB", provider: "WECHAT", scope: "PLATFORM", ownerNo: "", enabled: true, status: "STUB", priority: 100, credRef: "shop.wx", configJson: "{}", missingCreds: [], locked: false },
  { channelNo: "NCH-PUSH-GETUI", channelType: "PUSH", provider: "GETUI", scope: "PLATFORM", ownerNo: "", enabled: true, status: "STUB", priority: 100, credRef: "shop.push.getui", configJson: "{}", missingCreds: [], locked: false },
  { channelNo: "NCH-PUSH-FCM", channelType: "PUSH", provider: "FCM", scope: "PLATFORM", ownerNo: "", enabled: true, status: "STUB", priority: 100, credRef: "shop.push.fcm", configJson: "{}", missingCreds: [], locked: false },
  { channelNo: "NCH-PUSH-APNS", channelType: "PUSH", provider: "APNS", scope: "PLATFORM", ownerNo: "", enabled: true, status: "STUB", priority: 100, credRef: "shop.push.apns", configJson: "{}", missingCreds: [], locked: false },
  { channelNo: "NCH-INAPP", channelType: "INAPP", provider: "INTERNAL", scope: "PLATFORM", ownerNo: "", enabled: true, status: "READY", priority: 100, credRef: null, configJson: "{}", missingCreds: [], locked: true },
  { channelNo: "NCH-PUSH-GETUI-TEST", channelType: "PUSH", provider: "GETUI", scope: "TEST", ownerNo: "", enabled: true, status: "STUB", priority: 100, credRef: null, configJson: "{}", missingCreds: [], locked: false },
];

// 营销广播 mock 状态。建/取消就地改。
const pushTasks: NotifyPushTask[] = [
  { taskNo: "NPT-DEMO-1", name: "双十一预热", audienceType: "ALL_APP_USER", channel: "PUSH",
    title: "秒杀来了", body: "点进来看看今天的秒杀", link: "/pages/activity/1111",
    scheduledAt: null, status: "DONE", estimatedCount: 5230, sentCount: 5180, finishedAt: null },
];

// 铃铛收件箱的 mock 状态。发给运营的待办，与 NotifyLog（发给用户的留痕）是两回事
const inbox: import("@/lib/types").InboxMessage[] = [
  { messageNo: "MSO-1", type: "SYSTEM", title: "新工单", body: "「取货码扫不出来」等待处理",
    link: "/messages?tab=tickets", read: false, at: Date.now() - 8 * 60_000 },
  { messageNo: "MSO-2", type: "SYSTEM", title: "入驻待审核", body: "「张记粮油」提交了进件资料",
    link: "/merchants?tab=applies", read: false, at: Date.now() - 42 * 60_000 },
  { messageNo: "MSO-3", type: "SYSTEM", title: "对账差异", body: "昨日结算对账有 1 笔差异待处理",
    link: "/finance?tab=recon", read: true, at: Date.now() - 26 * 3600_000 },
];

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
  /*
   * 发送记录：mock 下**是空的**，这是本地开发的真实状态 ——
   * 桩通道不真发，也就不记（页面上那句 nlEmpty 说的就是这件事）。
   *
   * 此前这里编了六条「历史记录」，会让人以为「已经在发了」。
   * 那六条唯一的价值是能暴露「新通道被显示成短信」那类映射缺陷，
   * 而那条防线已经交给 lib/notify-label.test.ts（纯函数，5 个用例）。
   */
  listNotifyLogs: (q = {}) =>
    wait(db.paginate([] as NotifyLog[], q.page, q.size), 300),

  // 通道体检。mock 下四条都在桩模式、凭据全空 —— 那正是本地开发的真实状态，
  // 编成「全绿」会让人以为配好了
  listNotifyChannels: () => wait([
    { channel: "SMS" as const, stub: true, enabled: false,
      credentials: [{ envVar: "ALI_SMS_AK", present: false, required: true },
                    { envVar: "ALI_SMS_SK", present: false, required: true },
                    { envVar: "ALI_SMS_SIGN", present: true, required: true },
                    { envVar: "ALI_SMS_TPL_OTP", present: true, required: true }],
      params: [{ key: "endpoint", value: "dysmsapi.aliyuncs.com" },
               { key: "sign", value: "数智邻购" },
               { key: "templates.otp", value: "SMS_474945291" }],
      todaySent: 12, todayFailed: 1 },
    { channel: "MAIL" as const, stub: true, enabled: false,
      credentials: [{ envVar: "MAIL_USERNAME", present: true, required: true },
                    { envVar: "MAIL_PASSWORD", present: false, required: true },
                    { envVar: "MAIL_FROM", present: true, required: true }],
      params: [{ key: "host", value: "smtp.office365.com" },
               { key: "from", value: "platform@neargo.ai" }],
      todaySent: 3, todayFailed: 0 },
    { channel: "WXSUB" as const, stub: true, enabled: false,
      credentials: [{ envVar: "WX_APPID", present: false, required: true },
                    { envVar: "WX_SECRET", present: false, required: true },
                    { envVar: "WX_TPL_ORDER_ARRIVED", present: false, required: true },
                    { envVar: "WX_TPL_REFUNDED", present: false, required: true }],
      params: [{ key: "mpState", value: "formal" },
               { key: "templates.orderArrived", value: "STUB_TPL_ORDER_ARRIVED" },
               { key: "templates.refunded", value: "STUB_TPL_REFUNDED" }],
      todaySent: 8, todayFailed: 0 },
    { channel: "PUSH" as const, stub: true, enabled: false,
      credentials: [{ envVar: "GETUI_APP_ID", present: false, required: true },
                    { envVar: "GETUI_APP_KEY", present: false, required: true },
                    { envVar: "GETUI_MASTER_SECRET", present: false, required: true }],
      params: [{ key: "appId", value: "" }],
      todaySent: 5, todayFailed: 2 },
  ], 300),

  // 渠道注册表。mock 下平台通道全走桩（本地真实状态），INAPP 就绪且锁定；启停可变。
  listChannelRegistry: () => wait(channels.slice(), 300),
  setChannelEnabled: (channelNo, enabled) => {
    const ch = channels.find((x) => x.channelNo === channelNo);
    if (!ch) return notFound("渠道", "Channel", channelNo);
    if (ch.locked) return fail("站内信不可关", "In-app messages cannot be disabled");
    ch.enabled = enabled;
    ch.status = enabled ? (ch.channelType === "INAPP" ? "READY" : "STUB") : "DISABLED";
    return wait(ch, 300);
  },

  // 营销广播（N6）。mock 下预估按人群给个固定数；建/取消就地改。
  listPushTasks: (q = {}) => wait(db.paginate(
    pushTasks.filter((t) => !q.status || t.status === q.status), q.page, q.size), 300),
  estimatePushTask: (audienceType) =>
    wait({ audienceType, count: audienceType === "ALL_STAFF" ? 128 : 5230 }, 200),
  createPushTask: (v) => {
    const t: NotifyPushTask = {
      taskNo: "NPT" + Date.now(), name: v.name, audienceType: v.audienceType,
      channel: "PUSH", title: v.title, body: v.body, link: v.link ?? null,
      scheduledAt: v.scheduledAt ?? null, status: "QUEUED",
      estimatedCount: v.audienceType === "ALL_STAFF" ? 128 : 5230, sentCount: 0, finishedAt: null,
    };
    pushTasks.unshift(t);
    return wait(t, 400);
  },
  cancelPushTask: (taskNo) => {
    const t = pushTasks.find((x) => x.taskNo === taskNo);
    if (!t) return notFound("广播任务", "Broadcast task", taskNo);
    if (t.status !== "QUEUED") return fail("只有待发的广播可以取消", "Only queued broadcasts can be cancelled");
    t.status = "CANCELLED";
    return wait(t, 300);
  },

  getWxTemplates: () => wait({ orderArrived: "STUB_TPL_ORDER_ARRIVED",
                               refunded: "STUB_TPL_REFUNDED" }, 200),
  saveWxTemplates: (v) => wait(v, 400),
  testSendInApp: () => wait(undefined as unknown as void, 400),

  getCaptcha: () =>
    // 1x1 透明 png —— mock 下只要「有个图」，图上写什么无所谓
    wait({ captchaId: "mock-captcha", imageBase64:
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==" }, 200),

  /*
   * mock 下按 userNo 演两种失败：真实后端查的是订阅额度与设备绑定，
   * 而本地没有那些数据 —— 编成「一律通过」会让这条路在 mock 下永远走不到失败分支，
   * 而运营第一次见到它恰恰是失败的时候。
   */
  // mock 下也存一份：只回固定值的话，「改了保存后还是旧的」这类缺陷在本地看不出来
  // 与真后端同形状：mock 下站内信只有模拟发送塞进去的那些
  listInAppMessages: (q = {}) => wait(db.paginate(db.inAppLogs, q.page, q.size)),

  getDefaultLang: () => wait({ lang: db.defaultLang.value, options: ["zh-CN", "en", "ar"] }),

  saveDefaultLang: async (lang) => {
    if (!["zh-CN", "en", "ar"].includes(lang)) {
      fail("不支持的语言", "Unsupported language");
    }
    db.defaultLang.value = lang;
    return wait({ lang, options: ["zh-CN", "en", "ar"] }, 300);
  },

  precheckNotifyTarget: (v) => {
    if (/no-?quota/i.test(v.target)) {
      fail("该用户没有可用的订阅额度，这条测试会白发",
        "This user has no subscribe-message quota left; the test would go nowhere");
    }
    if (/no-?device/i.test(v.target)) {
      fail("该用户没有绑定 App 设备：可能没装、没登录过，或已登出解绑",
        "This user has no app device bound: not installed, never signed in, or unbound at logout");
    }
    return wait(undefined as unknown as void, 200);
  },

  testSendNotify: (v) => {
    if (v.captchaCode !== "1234") fail("图形验证码错误或已过期，请重新获取",
      "Captcha is wrong or expired. Please get a new one");
    return wait(undefined as unknown as void, 500);
  },

  // 演示两台设备：一台安卓（个推）、一台 iOS（APNs）。收件人含 no-device 的返回空
  listPushDevices: (userNo) => {
    if (/no-?device/i.test(userNo)) return wait([]);
    return wait([
      { receiverType: "USER", platform: "APP_ANDROID", provider: "GETUI",
        clientId: "cid-android-0001", clientIdMask: "****0001", updatedAt: "2026-08-16T10:00:00" },
      { receiverType: "USER", platform: "APP_IOS", provider: "APNS",
        clientId: "cid-ios-0002", clientIdMask: "****0002", updatedAt: "2026-08-15T22:30:00" },
    ]);
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

  listInbox: () => wait([...inbox].sort((a, b) => b.at - a.at)),

  inboxUnread: () => wait(inbox.filter((m) => !m.read).length),

  readInbox: async (messageNo) => {
    const m = inbox.find((x) => x.messageNo === messageNo);
    if (m) m.read = true;
    return wait([...inbox]);
  },

  readAllInbox: async () => {
    inbox.forEach((m) => (m.read = true));
    return wait([...inbox]);
  },

  sceneChannels: async () => wait(db.sceneChannels),

  setSceneChannel: async (v) => {
    const cell = db.sceneChannels.find(
      (x) => x.scene === v.scene && x.audience === v.audience && x.channel === v.channel);
    if (!cell) notFound("场景通道配置", "Scene channel", `${v.scene}/${v.audience}/${v.channel}`);
    // **与后端同一条规则**：站内信是事实记录，不给关。界面上那一行本来就禁用，
    // 这里兜的是「前端被绕过」—— mock 放行的话，这条规则在开发时等于不存在，
    // 而它恰恰是这一屏唯一一条不能靠界面保证的约束。
    if (cell!.locked && !v.enabled) {
      fail("站内信是事实记录，不能关闭", "In-app messages are a record of fact and cannot be turned off");
    }
    cell!.enabled = v.enabled;
    return wait(cell!, 300);
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
