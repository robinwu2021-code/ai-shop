// 覆盖范围：消息触达（P-14.1）与客服（P-14.2）。
import type { Captcha, FaqEntry, InboxMessage, MsgTemplate, NotifyChannel, NotifyChannelHealth, NotifyLog, NotifyQuota, Page, PushTask, Ticket, WxTemplates } from "@/lib/types";
import type { PageQ, TicketQ } from "../query";

export interface MessageApi {
  listMsgTemplates(q?: PageQ): Promise<Page<MsgTemplate>>;
  setTemplateEnabled(templateNo: string, enabled: boolean): Promise<MsgTemplate>;

  listPushTasks(q?: PageQ): Promise<Page<PushTask>>;
  /** 发送推送（P-14.1.2）。人群为空（预估触达 0）时拒绝 —— 发了等于白发一次。 */
  sendPushTask(taskNo: string): Promise<PushTask>;
  cancelPushTask(taskNo: string): Promise<PushTask>;

  getNotifyQuota(): Promise<NotifyQuota>;
  /** 触达频控（P-14.1.4）。两个上限都必须 > 0 —— 0 等于没有频控但看着像配了。 */
  saveNotifyQuota(v: Pick<NotifyQuota, "dailyPerUser" | "minIntervalHours">): Promise<NotifyQuota>;

  /**
   * 发送记录（P-14.3）。`channel`/`status`/`bizType` 为空表示不筛。
   *
   * <p>通道与用途是**两个正交维度**：同一条短信通道上既有验证码也有交易触达，
   * 同一个交易触达会同时走微信与推送 —— 只按通道筛答不了「今天的验证码发得怎么样」。
   */
  listNotifyLogs(q?: PageQ & { channel?: string; status?: string; bizType?: string }): Promise<Page<NotifyLog>>;
  /** 四条通道的体检：开没开、凭据齐不齐、今天发了多少。**不含任何密钥明文**。 */
  listNotifyChannels(): Promise<NotifyChannelHealth[]>;
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
