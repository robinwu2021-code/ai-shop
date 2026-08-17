// 覆盖范围：消息触达（P-14.1）与客服（P-14.2）。
import type { Captcha, FaqEntry, InAppLog, InboxMessage, MsgTemplate, NotifyChannel, NotifyChannelHealth, NotifyChannelRow, NotifyLog, NotifyPushTask, NotifyQuota, Page, Ticket, WxTemplates } from "@/lib/types";
import type { PageQ, TicketQ } from "../query";

export interface MessageApi {
  listMsgTemplates(q?: PageQ): Promise<Page<MsgTemplate>>;
  setTemplateEnabled(templateNo: string, enabled: boolean): Promise<MsgTemplate>;

  /** 发送推送（P-14.1.2）。人群为空（预估触达 0）时拒绝 —— 发了等于白发一次。 */

  getNotifyQuota(): Promise<NotifyQuota>;
  /** 触达频控（P-14.1.4）。两个上限都必须 > 0 —— 0 等于没有频控但看着像配了。 */
  saveNotifyQuota(v: Pick<NotifyQuota, "dailyPerUser" | "minIntervalHours">): Promise<NotifyQuota>;

  /**
   * 发送记录（P-14.3）。`channel`/`status`/`bizType` 为空表示不筛。
   *
   * <p>通道与用途是**两个正交维度**：同一条短信通道上既有验证码也有交易触达，
   * 同一个交易触达会同时走微信与推送 —— 只按通道筛答不了「今天的验证码发得怎么样」。
   */
  listNotifyLogs(q?: PageQ & { channel?: string; status?: string; bizType?: string;
    /** 供应商（N3）：channel=PUSH 时可只看 GETUI / FCM / APNS 的记录 */
    provider?: string;
    /** 起止日期 yyyy-MM-dd，含当天 */
    from?: string; to?: string;
    /** 收件人。后端会把完整手机号/邮箱按同一口径掩码后再匹配 */
    target?: string }): Promise<Page<NotifyLog>>;
  /** 四条通道的体检：开没开、凭据齐不齐、今天发了多少。**不含任何密钥明文**。 */
  listNotifyChannels(): Promise<NotifyChannelHealth[]>;
  /** 渠道注册表（触达推送中台 N2）：类型×供应商×接入范围×归属 + 读时派生状态。 */
  listChannelRegistry(): Promise<NotifyChannelRow[]>;
  /** 软启停某条渠道（N2）。INAPP 后端拒关（站内信是事实记录）。 */
  setChannelEnabled(channelNo: string, enabled: boolean): Promise<NotifyChannelRow>;

  /** 营销广播任务列表（N6）。`status` 为空表示不筛。 */
  listPushTasks(q?: PageQ & { status?: string }): Promise<Page<NotifyPushTask>>;
  /** 预估触达：**建任务前**先看某人群当下覆盖多少人（N6b）。 */
  estimatePushTask(audienceType: string): Promise<{ audienceType: string; count: number }>;
  /** 新建广播（N6）。创建时后端即预估触达并落 QUEUED。 */
  createPushTask(v: { name: string; audienceType: string; title: string; body: string;
    /** 点开落点，可空 */
    link?: string;
    /** 定时下发 ISO 本地时刻；空=尽快发 */
    scheduledAt?: string }): Promise<NotifyPushTask>;
  /** 取消广播（仅 QUEUED 可取消）。 */
  cancelPushTask(taskNo: string): Promise<NotifyPushTask>;
  getWxTemplates(): Promise<WxTemplates>;
  /**
   * 保存微信模板号。**空值 = 清掉覆盖回落环境变量**，不是「设成空」。
   *
   * <p>⚠️ 端上 `VITE_WX_TPL_*` 必须同值 —— 不同值的话前端攒的订阅额度后端查不到。
   */
  saveWxTemplates(v: WxTemplates): Promise<WxTemplates>;

  /** 站内信的模拟发送：往某个收件箱塞一条。**不过图形验证码**（它发不出平台）。 */
  testSendInApp(v: { receiverType: string; receiverNo: string;
                     title: string; body?: string; link?: string }): Promise<void>;

  /** 取一张图形验证码。它保护的是下面那条**能指定任意收件人**的接口。 */
  getCaptcha(): Promise<Captcha>;
  /**
   * 测试发送。**三道闸齐**：权限码 + 图形验证码 + 按操作人限流。
   *
   * 只上权限码是不够的：运营账号泄漏就等于拿到一台群发机，
   * 而且发出去的是带平台签名的正规短信，比垃圾短信更能骗到人。
   */
  testSendNotify(v: {
    channel: NotifyChannel; target: string; level?: string;
    /** 邮件主题 / 推送标题。**短信忽略** —— 正文由报备模板决定 */
    subject?: string;
    /** 邮件正文 / 推送正文。同上 */
    body?: string;
    /** 模板参数（短信 code、微信 thing2…）。**短信只认这个** */
    params?: Record<string, string>;
    captchaId: string; captchaCode: string;
  }): Promise<void>;

  /**
   * 收件人预检。**在运营填完 userNo 时调，早于取验证码** ——
   * 图形验证码一次性，而「没额度 / 没绑设备」输完就能知道。
   * 不预检的话，填完表、输完验证码、点了发送才被告知「换个账号」，那张码已经废了。
   *
   * @param scene 仅微信用。额度逐模板授权，要查选中的那条模板
   */
  precheckNotifyTarget(v: { channel: NotifyChannel; target: string; scene?: string }): Promise<void>;

  /**
   * 平台默认语言：**收件人语言未知时按哪种发**（不是「所有邮件用哪种语言」）。
   * 知道收件人语言时一律用他自己的 —— 目前唯一用到它的是「管理员替别人建账号」。
   * `options` 由后端下发，端上不硬编码一份：加语言时两边会不同步。
   */
  /**
   * 站内信记录。**与 listNotifyLogs 分开**：外发答「发出去了吗」（有失败态），
   * 站内信答「他读了吗」（入库即到达）——合成一列的话「已发送」会有两种意思。
   */
  listInAppMessages(q?: PageQ & {
    receiverType?: string; receiverNo?: string; from?: string; to?: string;
  }): Promise<Page<InAppLog>>;

  getDefaultLang(): Promise<{ lang: string; options: string[] }>;
  saveDefaultLang(lang: string): Promise<{ lang: string; options: string[] }>;

  listTickets(q?: TicketQ): Promise<Page<Ticket>>;
  /** 分派工单（P-14.2.1）。必须指定处理人；已关闭工单不能再分派。 */
  assignTicket(ticketNo: string, assignee: string): Promise<Ticket>;
  /**
   * 客服回复（P-14.2.2）。回复正文**直接发给用户**，不能为空。
   *
   * 这个功能点此前**在契约里根本不存在** —— 分派、关闭、代客留痕都有，唯独没有回复。
   * 漏实现是排期问题，漏定义是没人发现这件事需要做。
   */
  replyTicket(ticketNo: string, reply: string): Promise<Ticket>;
  /** 记录代客操作（P-14.2.3）：谁、对什么、做了什么。 */
  addProxyAction(ticketNo: string, action: string): Promise<Ticket>;
  closeTicket(ticketNo: string): Promise<Ticket>;

  // ---- 运营自己的通知收件箱（顶栏铃铛，TDD-通知与消息推送 §二期）
  listInbox(): Promise<InboxMessage[]>;
  /** 未读数。铃铛 15s 轮询用，只给一个数。 */
  inboxUnread(): Promise<number>;
  readInbox(messageNo: string): Promise<InboxMessage[]>;
  readAllInbox(): Promise<InboxMessage[]>;

  listFaqs(q?: PageQ): Promise<Page<FaqEntry>>;
  /** 帮助中心（P-14.2.4）。上架前答案不能为空 —— 空答案比没有条目更糟。 */
  saveFaq(v: Pick<FaqEntry, "faqNo" | "question" | "answer" | "category">): Promise<FaqEntry>;
  setFaqPublished(faqNo: string, published: boolean): Promise<FaqEntry>;
}
