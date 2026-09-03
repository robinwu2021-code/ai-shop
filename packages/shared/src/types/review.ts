// 评价与申诉
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

// ---------------------------------------------------------------- 评价

export interface Review {
  /** 评价单号 */
  reviewNo: string;
  /** 被评价的商品 */
  goodsNo: string;
  /** 被评价的商家。差评会计入商家评分与申诉流程 */
  merchantNo: string;
  /** 评价人昵称（匿名评价时为「匿名用户」） */
  nickname: string;
  /** 评价人头像 */
  avatar: string;
  /** 总分，1–5 整数 */
  rating: number;
  /** 评价正文 */
  content: string;
  /** 评价图 URL 列表 */
  images: string[];
  /** 购买规格。展示在评价上，让人知道这条评价说的是哪个 SKU */
  spec: string;
  /** 评价提交时间 */
  createdAt: number;
  /** 点赞数 */
  likeCount: number;
  /** 当前用户是否已点赞 */
  liked: boolean;
  /** 商家回复 */
  reply?: string;
  /**
   * 三维度评分（B-9.3 / P-13.1.4）。总分 `rating` 仍保留 ——
   * 老数据没有分维度分，列表页也只显示一个星级；维度分用于**评分算法与商家诊断**：
   * 「货好但送得慢」这种问题，只看总分永远看不出来。
   */
  scores?: ReviewScores;
  /** 商家申诉（B-9.4）。裁决在平台端 P-13.1 */
  appeal?: ReviewAppeal;
}
/** 三维度：商品本身 / 履约（快慢、包装、缺损） / 服务（沟通、售后态度） */
export interface ReviewScores {
  /** 商品本身，1–5 */
  goods: number;
  /** 履约：快慢、包装、缺损，1–5 */
  fulfillment: number;
  /** 服务：沟通、售后态度，1–5 */
  service: number;
}
export type ReviewAppealStatus =
  | "PENDING" // 待平台裁决
  | "UPHELD" // 申诉成立 —— 原评价下架
  | "REJECTED"; // 申诉驳回 —— 评价保留
/**
 * 商家对差评的申诉。
 * 这是**唯一**能把差评送进平台裁决台的入口 —— 平台端 P-13.1 的裁决页早就建好了，
 * 但 B 端一直没有申诉入口，那张台子收不到任何单，等于空转。
 */
export interface ReviewAppeal {
  /** 申诉单号 */
  appealNo: string;
  /** 申诉理由，商家填写 */
  reason: string;
  /** 举证图（聊天记录、物流截图） */
  images: string[];
  /** 裁决状态 */
  status: ReviewAppealStatus;
  /** 申诉提交时间 */
  submittedAt: number;
  /** 裁决说明。**无论成立还是驳回都必须写** —— 商家会看到，「已读不处理」不是一种结果 */
  verdict?: string;
}
