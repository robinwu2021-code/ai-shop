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
  /** B inbox projection; business action state is independent of read/unread. */
  bizType?: string | null;
  /** 关联业务单号，缺失时无业务定位 */
  bizNo?: string | null;
  /** 业务所属门店，跳转前校验门店权限 */
  storeNo?: string | null;
  /** 是否仍需处理业务动作，与已读状态独立 */
  actionPending?: boolean;
  /** 是否已读。未读数按 type 分别统计 */
  read: boolean;
  /** 消息产生时间 */
  at: number;
}

// ---------------------------------------------------------------- 商户问题回报（TDD-商户App问题回报与版本）

/**
 * 回报分类。**冻结这四个**（已拍板 P4）：以后加分类要服务端与 App 同发；
 * 端上读到不认识的码一律按「其他」显示，这样加码不用强更。
 */
export type FeedbackCategory = "BUG" | "PAYMENT" | "IDEA" | "OTHER";

/** 回报状态。`CLOSED` 是客服直接结案（没回复也可能关），端上显示「已结案」 */
export type FeedbackStatus = "OPEN" | "REPLIED" | "CLOSED";

/**
 * 回报附带的一条诊断。服务端只留这四个字段，路径去查询串、8 位以上数字只留后 4 位。
 */
export interface FeedbackDiagnostic {
  /** 请求时刻。带时区的值存成 UTC（…Z）；不带时区的原样（设备本地时间）；解析不了为 null */
  at: string | null;
  /** HTTP 方法 */
  method: string;
  /** 请求路径（已脱敏） */
  path: string;
  /** 结果摘要，如 `ok`、`error 70006` */
  outcome: string;
}

/** 「我的回报」一条（`GET /biz/feedback/mine`）。时间是 ISO-8601 字符串，不是毫秒数 */
export interface MerchantFeedback {
  /** 回报号，即客服工单号（TK…） */
  feedbackNo: string;
  /** 分类 */
  category: FeedbackCategory;
  /** 描述原文 */
  description: string;
  /** 处理状态 */
  status: FeedbackStatus;
  /** 客服回复；没回复为 null */
  reply: string | null;
  /** 提交时所在门店（核对属于本账号才存） */
  storeNo: string | null;
  /** 提交时间（ISO-8601 UTC） */
  createdAt: string;
  /** 回复时间（ISO-8601 UTC）；没回复为 null */
  repliedAt: string | null;
}

/** 提交回报的回执 */
export interface FeedbackReceipt {
  /** 回报号，即客服工单号（TK…） */
  feedbackNo: string;
}

/** App 平台（版本策略按它分行）。与后端 sys_app_version.platform 一致 */
export type AppPlatform = "ANDROID" | "IOS";

/**
 * 版本检查（`GET /common/app/version`，匿名）。该平台没配置时 `latest`/`minSupported` 为空串、
 * `notes` 为空数组、`storeUrl` 为 null —— 端上据此不提示。
 */
export interface AppVersionInfo {
  /** 归一后的平台 */
  platform: AppPlatform;
  /** 最新版；空串 = 不提示更新 */
  latest: string;
  /** 最低支持版；低于它的包写请求会收到 10426 CLIENT_UPGRADE_REQUIRED */
  minSupported: string;
  /** 按 Accept-Language 取的更新说明，没填回落繁中 */
  notes: string[];
  /** 商店地址（只会是 App Store 或 Google Play 的 https 地址） */
  storeUrl: string | null;
  /** 只在请求带了 build 时有值；端上应以它为准 */
  upgradeRequired: boolean | null;
}
