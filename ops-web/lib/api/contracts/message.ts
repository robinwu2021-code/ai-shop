// 覆盖范围：消息触达（P-14.1）与客服（P-14.2）。
import type { FaqEntry, MsgTemplate, NotifyQuota, Page, PushTask, Ticket } from "@/lib/types";
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

  listTickets(q?: TicketQ): Promise<Page<Ticket>>;
  /** 分派工单（P-14.2.1）。必须指定处理人；已关闭工单不能再分派。 */
  assignTicket(ticketNo: string, assignee: string): Promise<Ticket>;
  /** 记录代客操作（P-14.2.3）：谁、对什么、做了什么。 */
  addProxyAction(ticketNo: string, action: string): Promise<Ticket>;
  closeTicket(ticketNo: string): Promise<Ticket>;

  listFaqs(q?: PageQ): Promise<Page<FaqEntry>>;
  /** 帮助中心（P-14.2.4）。上架前答案不能为空 —— 空答案比没有条目更糟。 */
  saveFaq(v: Pick<FaqEntry, "faqNo" | "question" | "answer" | "category">): Promise<FaqEntry>;
  setFaqPublished(faqNo: string, published: boolean): Promise<FaqEntry>;
}
