// 站内消息
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

// ---------------------------------------------------------------- 消息

/**
 * 站内消息。
 * 三类分开是因为**用户对它们的期待完全不同**：交易类必须看到（到货了要去取），
 * 活动类可以错过，系统类是通知。混在一个列表里，交易消息会被活动消息淹没。
 */
export type MessageType = "TRADE" | "MARKETING" | "SYSTEM";
export interface Message {
  /** 消息单号 */
  messageNo: string;
  /** 消息分类，决定它落在哪个 tab */
  type: MessageType;
  /** 标题（列表页展示） */
  title: string;
  /** 正文 */
  body: string;
  /** 点进去要跳哪（订单详情/商品/团），已是完整页面路径带参 */
  link?: string;
  /** 是否已读。未读数按 type 分别统计 */
  read: boolean;
  /** 消息产生时间 */
  at: number;
}
