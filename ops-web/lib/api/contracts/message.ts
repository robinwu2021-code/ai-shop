// 覆盖范围：消息触达（P-14.1）与客服（P-14.2）。
import type { Captcha, FaqEntry, MsgTemplate, NotifyChannel, NotifyLog, NotifyQuota, Page, PushTask, Ticket } from "@/lib/types";
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

  /** 发送记录（P-14.3）。`channel`/`status` 为空表示不筛。 */
  listNotifyLogs(q?: PageQ & { channel?: string; status?: string }): Promise<Page<NotifyLog>>;
  /** 取一张图形验证码。它保护的是下面那条**能指定任意收件人**的接口。 */
  getCaptcha(): Promise<Captcha>;
  /**
   * 测试发送。**三道闸齐**：权限码 + 图形验证码 + 按操作人限流。
   *
   * 只上权限码是不够的：运营账号泄漏就等于拿到一台群发机，
   * 而且发出去的是带平台签名的正规短信，比垃圾短信更能骗到人。
   */
  testSendNotify(v: { channel: NotifyChannel; target: string;
                      captchaId: string; captchaCode: string }): Promise<void>;

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

  listFaqs(q?: PageQ): Promise<Page<FaqEntry>>;
  /** 帮助中心（P-14.2.4）。上架前答案不能为空 —— 空答案比没有条目更糟。 */
  saveFaq(v: Pick<FaqEntry, "faqNo" | "question" | "answer" | "category">): Promise<FaqEntry>;
  setFaqPublished(faqNo: string, published: boolean): Promise<FaqEntry>;
}
