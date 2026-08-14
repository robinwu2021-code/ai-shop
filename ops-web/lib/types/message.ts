// 消息与客服域（矩阵 P-14）。
/**
 * 模板所属通道。**与后端 `SysNotifyLog` 的四个常量 + INAPP 一致**。
 *
 * <p>旧值 `SUBSCRIBE/PUSH/INBOX` 来自 V20 的建表注释，而代码与种子（V141）
 * 用的一直是这一套 —— 两套名字并存时，模板列表的通道列会显示成空白。
 */
export type MsgChannel = "SMS" | "MAIL" | "WXSUB" | "PUSH" | "INAPP";
export type PushStatus = "DRAFT" | "SCHEDULED" | "SENT" | "CANCELLED";

/** 订阅消息模板（P-14.1.1）。 */
export interface MsgTemplate {
  /** 模板单号 */
  templateNo: string;
  /** 模板名 */
  name: string;
  /** 触达渠道 */
  channel: MsgChannel;
  /** 模板正文，含 {占位符}。**模拟发送靠它展示「会发出什么」并做预览** */
  content: string;
  /**
   * 渠道侧模板 ID（阿里云 `SMS_xxx` / 微信模板号）。站内信为空。
   *
   * <p>后端 `TemplateVO` 一直有这个字段，端上类型此前漏了 —— 于是页面拿不到它，
   * 而它正是运营核对「我们发的是哪个报备模板」的唯一凭据。
   */
  providerTemplateId?: string | null;
  /** 是否启用。停用后引用它的推送任务发不出去 */
  enabled: boolean;
  /** 近 30 天发送量 */
  sentCount: number;
}

/** 推送任务（P-14.1.2）。 */
export interface PushTask {
  /** 任务单号 */
  taskNo: string;
  /** 任务名 */
  name: string;
  /** 使用的消息模板 */
  templateNo: string;
  /** 人群描述，如「近 7 日未下单的老客」 */
  audience: string;
  /** 预估触达数。为 0 说明人群是空的，发了等于白发 */
  estimatedReach: number;
  /** 任务状态 */
  status: PushStatus;
  /** 计划发送时间。`status=SCHEDULED` 时有值 */
  scheduledAt?: string;
  /** 创建时间 */
  createdAt: string;
}

/**
 * 触达频控（P-14.1.4）。
 * 两个上限都必须 > 0 —— 0 等于没有频控，但界面上看着像配了，比不配更危险。
 */
export interface NotifyQuota {
  /** 单用户单日消息上限 */
  dailyPerUser: number;
  /** 同一模板对同一用户的最小间隔（小时） */
  minIntervalHours: number;
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}

export type TicketStatus = "PENDING" | "ASSIGNED" | "RESOLVED" | "CLOSED";

export const TICKET_TRANSITIONS: Record<TicketStatus, TicketStatus[]> = {
  PENDING: ["ASSIGNED", "CLOSED"],
  ASSIGNED: ["RESOLVED", "CLOSED"],
  RESOLVED: ["CLOSED", "ASSIGNED"],
  CLOSED: [],
};

/** 客服工单（P-14.2.1）。 */
export interface Ticket {
  /** 工单号 */
  ticketNo: string;
  /** 工单标题 */
  title: string;
  /** 提单用户昵称 */
  userNickname: string;
  /** 关联订单，可空 */
  orderNo?: string;
  /** 工单状态。允许的流转见 `TICKET_TRANSITIONS` */
  status: TicketStatus;
  /** 处理人（员工登录名）；未分派为空 */
  assignee?: string;
  /**
   * 代客操作留痕（P-14.2.3）：谁、对什么、做了什么。
   *
   * **可选，不要去掉 `?`。** 后端 `TicketVO` 目前不下发这个字段
   * （`MessageVOs.java` 里只有 ticketNo/subject/content/orderNo/status/reply/createdAt/repliedAt），
   * 只有 mock 有。声明成必填数组 + `page.tsx` 直接 `.length` = 真接口下抛 TypeError。
   * 与 `Merchant.qualifications` 同一形状，由 `ops-contract-fields` 守卫抓出。
   */
  proxyActions?: string[];
  /** 提单时间 */
  createdAt: string;
  /**
   * 客服回复正文。**用户在 C 端工单详情页看的就是这个字段**。
   *
   * 此前它在三层上各缺一处：后端 `msg_ticket` 建表就留了 `reply`/`replied_at`/`replied_by`
   * 且注释写明「代客操作要能追到人」，但没有任何代码写过它们；
   * 契约里也从没定义过「回复」这个动作（只有分派、关闭、代客留痕）。
   * 于是用户提单后反复点开详情，看到的永远是空的，而且不报任何错。
   */
  reply?: string;
  /** 回复时间；未回复为空 */
  repliedAt?: string;
  /** 回复人（员工登录名）。回复署的是平台的名，必须能追到人 */
  repliedBy?: string;
}

/** 帮助中心条目（P-14.2.4）。 */
export interface FaqEntry {
  /** 条目单号 */
  faqNo: string;
  /** 问题 */
  question: string;
  /** 答案正文 */
  answer: string;
  /** 所属分类，用于帮助中心分组 */
  category: string;
  /** 是否已发布。未发布的用户看不到 */
  published: boolean;
  /** 浏览量，用来发现「大家其实在问什么」 */
  views: number;
}

/**
 * 一条短信/邮件发送记录。
 *
 * `target` 是**掩码后的**收件人（138****8888 / r***n@neargo.ai）——
 * 这张表运营都看得到，而收件人是用户的手机号与邮箱。
 * 要查具体一条，靠 `providerMsgId` 去通道后台查。
 */
/**
 * 外发渠道。**与后端 `SysNotifyLog` 的四个常量逐字一致**。
 *
 * <p>WXSUB = 微信小程序订阅消息（服务通知），target 是掩码后的 openid；
 * PUSH = App 推送（个推/uni-push，ADR-018），target 是掩码后的 clientId。
 *
 * <p><b>站内信（INAPP）不在这里</b>：它不进 `sys_notify_log`，
 * 它自己就是一张可查的表（`msg_message`）。混进来会让人以为能在发送记录里查到它。
 */
export type NotifyChannel = "SMS" | "MAIL" | "WXSUB" | "PUSH";

/**
 * 通道体检（TDD-运营端触达中心 §4.1）。
 *
 * <p><b>凭据只有「配没配」，没有值</b>：一个能在 Web 上读出生产短信密钥的接口，
 * 泄漏一次就是全平台可群发。要改密钥去改环境变量并重启，不在这个页面上改。
 */
export interface NotifyChannelHealth {
  channel: NotifyChannel;
  /** 走桩：不真发，只记日志 */
  stub: boolean;
  /** 真实通道已启用（!stub） */
  enabled: boolean;
  credentials: { envVar: string; present: boolean; required: boolean }[];
  /** 非密业务参数，可回显（模板号、endpoint 这类本就印在短信里的东西） */
  params: { key: string; value: string }[];
  todaySent: number;
  todayFailed: number;
}

/** 微信订阅消息的模板号映射。**唯一一项开放到运营端的通道参数**（模板号不是凭据）。 */
export interface WxTemplates {
  orderArrived: string;
  refunded: string;
}

/**
 * 运营自己的通知收件箱（顶栏铃铛）。
 * 与 NotifyLog 是两回事：这个是**发给运营的待办**（新工单/待审核/告警），
 * 那个是**平台发给用户**的触达留痕。
 */
/** 与后端 MsgMessage 的三个常量逐字一致 —— 内联在 interface 里的话对登记表不可见（规范 §D5） */
export type InboxMessageType = "TRADE" | "MARKETING" | "SYSTEM";

export interface InboxMessage {
  messageNo: string;
  type: InboxMessageType;
  title: string;
  body: string;
  link?: string | null;
  read: boolean;
  at: number;
}

/** 发送结果。**失败也记**——只记成功的话，这张表回答不了「他为什么没收到」。 */
export type NotifyStatus = "SENT" | "FAILED";

export interface NotifyLog {
  notifyNo: string;
  channel: NotifyChannel;
  /** OTP / OPS_INIT_PASSWORD / OPS_RESET_PASSWORD / TEST */
  bizType: string;
  target: string;
  /** 短信是阿里云模板号；邮件是主题 */
  templateCode?: string | null;
  status: NotifyStatus;
  /** 失败时通道返回的原文。**排查第一眼看它** */
  error?: string | null;
  /** 阿里云 BizId / 邮件 Message-ID */
  providerMsgId?: string | null;
  operatorNo?: string | null;
  createdAt: string;
}

/** 图形验证码挑战。`imageBase64` 不带 data: 前缀，端上自己拼 */
export interface Captcha {
  captchaId: string;
  imageBase64: string;
}
