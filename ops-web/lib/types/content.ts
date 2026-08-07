// 素材与内容域（矩阵 P-15.1 素材中心 / P-15.2 种草内容审核与榜单问答）。
export type MaterialKind = "COPY" | "IMAGE" | "POSTER" | "VIDEO";

/** 可见范围（P-15.1.3 / 15.1.4）：一份素材投给谁，和素材本身是一件事。 */
export type MaterialScope = "ALL" | "COMMUNITY" | "MERCHANT";

export interface Material {
  /** 素材单号 */
  materialNo: string;
  /** 素材标题，供商家在素材中心检索 */
  title: string;
  /** 素材形态 */
  kind: MaterialKind;
  /** 文案正文 / 图片或视频 URL（mock 阶段是 URL 字段，接后端换对象存储） */
  content: string;
  /** 可见范围。**投给谁和素材本身是一件事** */
  scope: MaterialScope;
  /** scope=COMMUNITY 时的社区列表；=MERCHANT 时的商家列表。ALL 时为空 */
  scopeRefs: string[];
  /** 适用语言，空 = 不限 */
  langs: string[];
  /** 是否已发布。未发布的素材商家看不到 */
  published: boolean;
  /** 被下载次数，衡量素材有没有人用 */
  downloads: number;
  /** 创建时间 */
  createdAt: string;
}

// ── 种草内容审核（P-15.2.1）──────────────────────────────────────

export type PostAuthorType = "USER" | "MERCHANT";

/**
 * 种草内容状态。
 *
 * ⚠️ `PASSED → OFFLINE` 是**单独一条路**，而不是"改回待审"：
 * 内容已经露出过、被人看过、可能已经被引用，把它退回待审等于假装没发生过。
 * 下架同样要写原因。
 */
export type PostStatus = "PENDING" | "PASSED" | "REJECTED" | "OFFLINE";

export const POST_TRANSITIONS: Record<PostStatus, PostStatus[]> = {
  PENDING: ["PASSED", "REJECTED"],
  PASSED: ["OFFLINE"],
  REJECTED: [],
  OFFLINE: [],
};

export interface Post {
  /** 内容单号 */
  postNo: string;
  /** 作者类型：普通用户 or 商家。商家发的内容审核标准更严 */
  authorType: PostAuthorType;
  /** 作者昵称/店名 */
  authorName: string;
  /** 内容标题 */
  title: string;
  /** 正文 */
  content: string;
  /** 归属社区。内容只在本社区露出 */
  communityNo: string;
  /** 社区名快照 */
  communityName: string;
  /** 关联商品；纯分享贴可以没有 */
  skuNo?: string | null;
  /**
   * 命中的风险词。
   * ⚠️ 命中的内容**不进批量通过** —— 批量 + 风险内容 = 事故，必须逐条看。
   */
  riskHits: string[];
  /** 审核状态。允许的流转见 `POST_TRANSITIONS`（`PASSED → OFFLINE` 是单独一条路） */
  status: PostStatus;
  /** 审核意见 / 下架原因。原样回作者 */
  auditRemark?: string | null;
  /** 点赞数 */
  likeCount: number;
  /** 发布时间 */
  createdAt: string;
  /** 审核完成时间。未审为 null */
  decidedAt?: string | null;
  /** 审核人（STAFF 账号）。未审为 null */
  decidedBy?: string | null;
}

// ── 榜单与问答（P-15.2.2 / 15.2.3）───────────────────────────────

/**
 * 榜单口径。
 *
 * `MANUAL` 与其余三类是两种东西：前三类由数据算出来，`MANUAL` 由人指定。
 * 混在一个结构里但**校验路径完全不同** —— 算出来的榜带 `manualSkus` 就是调用方理解错了。
 */
export type RankingKind = "SALES" | "RATING" | "NEW" | "MANUAL";

export interface Ranking {
  /** 榜单单号 */
  rankNo: string;
  /** 榜单名，如「本周热销」 */
  name: string;
  /** 榜单口径。**`MANUAL` 与其余三类校验路径完全不同** */
  kind: RankingKind;
  /** 取前 N 名 */
  size: number;
  /** 仅 MANUAL：人工指定的商品，顺序即榜位 */
  manualSkus: string[];
  /** 是否启用。停用后 C 端不再展示该榜 */
  enabled: boolean;
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}

export type QuestionStatus = "PENDING" | "ANSWERED" | "HIDDEN";

export interface Question {
  /** 提问单号 */
  questionNo: string;
  /** 被提问的商品 */
  skuNo: string;
  /** 商品标题快照 */
  skuTitle: string;
  /** 提问正文 */
  content: string;
  /** 提问人昵称 */
  askedBy: string;
  /** 回答正文。未回答为 null */
  answer?: string | null;
  /** 回答人（STAFF 或商家）。未回答为 null */
  answeredBy?: string | null;
  /** 回答时间。未回答为 null */
  answeredAt?: string | null;
  /** 问答状态 */
  status: QuestionStatus;
  /** 提问时间 */
  createdAt: string;
  /** 隐藏原因。隐藏也要写清为什么，否则用户来问时没人说得清 */
  hideReason?: string | null;
}
