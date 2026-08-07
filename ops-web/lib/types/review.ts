// 评价治理域（矩阵 P-13.1）。
export type ReviewStatus = "PENDING" | "PASSED" | "REJECTED";

/** 刷评信号（P-13.1.5）。不是结论，是**给人审的线索** —— 命中不等于判定。 */
export type RiskFlag = "SAME_DEVICE" | "SAME_IP" | "TEXT_DUP" | "BURST";

export interface Review {
  /** 评价单号 */
  reviewNo: string;
  /** 关联订单。一单一评 */
  orderNo: string;
  /** 被评价商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 评价人昵称 */
  authorNickname: string;
  /** 总评 1–5 */
  score: number;
  /** 三维分（商品 / 履约 / 服务），评分算法按权重合成 */
  scoreProduct: number;
  /** 履约分 1–5（快慢、包装、缺损） */
  scoreFulfill: number;
  /** 服务分 1–5（沟通、售后态度） */
  scoreService: number;
  /** 评价正文 */
  content: string;
  /** 配图数量。列表页不下发图本身，点进详情才取 */
  imageCount: number;
  /** 审核状态 */
  status: ReviewStatus;
  /** 命中的刷评信号。**是线索不是结论** —— 命中不等于判定 */
  riskFlags: RiskFlag[];
  /** 评价提交时间 */
  createdAt: string;
  /** 驳回原因：与门店审核同一条规矩 —— 驳回必须写清楚 */
  reason?: string;
}

export type AppealStatus = "PENDING" | "UPHELD" | "DISMISSED";

/** 恶意差评申诉（P-13.1.3）。UPHELD = 支持商家（差评下架），DISMISSED = 驳回申诉（差评保留）。 */
export interface ReviewAppeal {
  /** 申诉单号 */
  appealNo: string;
  /** 被申诉的评价 */
  reviewNo: string;
  /** 申诉方商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 商家的申诉理由 */
  reason: string;
  /** 举证材料数量（截图/聊天记录） */
  evidenceCount: number;
  /** 裁决状态。UPHELD = 支持商家（差评下架），DISMISSED = 驳回申诉（差评保留） */
  status: AppealStatus;
  /** 申诉提交时间 */
  submittedAt: string;
  /** 裁决说明：无论支持还是驳回都必须写，商家会看到 */
  verdict?: string;
}

/**
 * 评分算法参数（P-13.1.4）。
 * ⚠️ 改这些参数会**改变历史评价的呈现**（时效衰减是实时算的），
 * 所以每次改动都要留痕；影响预览等有真实数据后再做。
 */
export interface ScoreConfig {
  /** 三维权重，百分比，**和必须为 100** */
  weightProduct: number;
  /** 履约维度权重（百分比） */
  weightFulfill: number;
  /** 服务维度权重（百分比） */
  weightService: number;
  /** 新商家保护期（天）：期内不展示低于阈值的均分，避免首单差评直接判死 */
  newMerchantProtectDays: number;
  /** 时效衰减半衰期（天）：越久远的评价权重越低 */
  decayHalfLifeDays: number;
  /** 最后修改时间。改参数会**改变历史评价的呈现**，必须留痕 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}
